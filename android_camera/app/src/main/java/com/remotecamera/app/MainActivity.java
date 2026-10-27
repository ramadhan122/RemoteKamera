package com.remotecamera.app;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST = 100;

    private PreviewView previewView;
    private ImageCapture imageCapture;
    private TextView selectedFilesText;
    private Button sendButton;

    private final List<Uri> selectedFiles = new ArrayList<>();
    private final ExecutorService queueExecutor =
        Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> filePicker;
    private boolean isQueueing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenMultipleDocuments(),
            uris -> {
                selectedFiles.clear();

                if (uris != null) {
                    selectedFiles.addAll(uris);
                }

                updateSelectionText();
            }
        );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(
            PreviewView.ImplementationMode.COMPATIBLE
        );

        root.addView(previewView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(16, 8, 16, 16);

        Button captureButton = new Button(this);
        captureButton.setText("AMBIL FOTO");
        captureButton.setOnClickListener(v -> takePhoto());
        bottom.addView(captureButton, fullWidthParams());

        Button selectButton = new Button(this);
        selectButton.setText("PILIH FOTO / VIDEO");
        selectButton.setOnClickListener(v ->
            filePicker.launch(new String[]{"image/*", "video/*"})
        );
        bottom.addView(selectButton, fullWidthParams());

        selectedFilesText = new TextView(this);
        selectedFilesText.setTextColor(0xFFFFFFFF);
        selectedFilesText.setTextSize(14);
        selectedFilesText.setGravity(Gravity.CENTER);
        selectedFilesText.setPadding(8, 12, 8, 8);
        bottom.addView(selectedFilesText, fullWidthParams());

        sendButton = new Button(this);
        sendButton.setText("MASUKKAN KE ANTREAN");
        sendButton.setOnClickListener(v -> confirmAndQueue());
        bottom.addView(sendButton, fullWidthParams());

        Button retryButton = new Button(this);
        retryButton.setText("ULANGI UPLOAD GAGAL");
        retryButton.setOnClickListener(v -> retryFailedUploads());
        bottom.addView(retryButton, fullWidthParams());

        root.addView(bottom);
        setContentView(root);

        updateSelectionText();
        schedulePendingUploads();

        if (ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(
                new String[]{Manifest.permission.CAMERA},
                CAMERA_PERMISSION_REQUEST
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSelectionText();
    }

    private LinearLayout.LayoutParams fullWidthParams() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void updateSelectionText() {
        if (selectedFilesText == null) return;

        String text = selectedFiles.size() + " file dipilih";

        try (UploadQueueDb db = new UploadQueueDb(this)) {
            text += "\nMenunggu: " +
                (db.countStatus(UploadQueueDb.PENDING)
                + db.countStatus(UploadQueueDb.SENDING));

            text += "\nBerhasil: " +
                db.countStatus(UploadQueueDb.SENT);

            text += "\nGagal: " +
                db.countStatus(UploadQueueDb.FAILED);
        }

        if (isQueueing) {
            text += "\nMenyalin file ke antrean...";
        }

        selectedFilesText.setText(text);

        if (sendButton != null) {
            sendButton.setEnabled(
                !isQueueing && !selectedFiles.isEmpty()
            );
        }
    }

    private void confirmAndQueue() {
        if (selectedFiles.isEmpty()) {
            Toast.makeText(this, "Pilih file terlebih dahulu",
                Toast.LENGTH_SHORT).show();
            return;
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Masukkan ke antrean?")
            .setMessage(
                "Salinan " + selectedFiles.size()
                + " file akan disimpan di aplikasi dan dikirim otomatis "
                + "saat laptop dapat diakses. File asli di HP tidak dihapus."
            )
            .setNegativeButton("Batal", null)
            .setPositiveButton("Lanjutkan",
                (dialog, which) -> queueSelectedFiles())
            .show();
    }

    private void queueSelectedFiles() {
        if (isQueueing) return;

        isQueueing = true;
        updateSelectionText();

        List<Uri> filesToQueue = new ArrayList<>(selectedFiles);

        queueExecutor.execute(() -> {
            int queued = 0;
            List<String> failures = new ArrayList<>();

            for (Uri uri : filesToQueue) {
                File copy = null;
                String id = UUID.randomUUID().toString();

                try {
                    String filename = getDisplayName(uri);
                    String extension = getExtension(filename);

                    String[] allowed = {
                        ".jpg", ".jpeg", ".png", ".webp",
                        ".mp4", ".mov", ".mkv", ".webm"
                    };

                    boolean valid = false;
                    for (String ext : allowed) {
                        if (ext.equalsIgnoreCase(extension)) {
                            valid = true;
                            break;
                        }
                    }

                    if (!valid) {
                        throw new Exception(
                            "Format tidak didukung: " + extension
                        );
                    }

                    File queueDir = new File(
                        getFilesDir(), "upload_queue"
                    );

                    if (!queueDir.exists() && !queueDir.mkdirs()) {
                        throw new Exception(
                            "Tidak dapat membuat folder antrean"
                        );
                    }

                    copy = new File(queueDir, id + extension);

                    try (InputStream input =
                            getContentResolver().openInputStream(uri);
                         FileOutputStream output =
                            new FileOutputStream(copy)) {

                        if (input == null) {
                            throw new Exception(
                                "File asli tidak dapat dibaca"
                            );
                        }

                        byte[] buffer = new byte[64 * 1024];
                        int count;

                        while ((count = input.read(buffer)) != -1) {
                            output.write(buffer, 0, count);
                        }

                        output.flush();
                    }

                    try (UploadQueueDb db = new UploadQueueDb(this)) {
                        db.add(id, filename, copy.getAbsolutePath());
                    }

                    scheduleUpload(id);
                    queued++;

                } catch (Exception e) {
                    if (copy != null) {
                        copy.delete();
                    }

                    failures.add(getDisplayName(uri) + ": " +
                        (e.getMessage() == null
                            ? "Gagal membuat antrean"
                            : e.getMessage()));
                }
            }

            final int totalQueued = queued;
            final List<String> failed = failures;

            runOnUiThread(() -> {
                isQueueing = false;

                if (totalQueued > 0) {
                    selectedFiles.clear();
                }

                updateSelectionText();

                String message = totalQueued +
                    " file masuk antrean.";

                if (!failed.isEmpty()) {
                    message += "\nGagal: " + failed.size()
                        + "\n" + String.join("\n", failed);
                }

                selectedFilesText.setText(message);

                Toast.makeText(
                    MainActivity.this,
                    totalQueued > 0
                        ? "Antrean upload tersimpan"
                        : "Tidak ada file yang masuk antrean",
                    Toast.LENGTH_LONG
                ).show();
            });
        });
    }

    private void scheduleUpload(String id) {
        Constraints constraints = new Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build();

        Data data = new Data.Builder()
            .putString(UploadWorker.KEY_ID, id)
            .build();

        OneTimeWorkRequest request =
            new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build();

        WorkManager.getInstance(getApplicationContext())
            .enqueueUniqueWork(
                "remote_camera_upload_" + id,
                ExistingWorkPolicy.KEEP,
                request
            );
    }

    private void schedulePendingUploads() {
        queueExecutor.execute(() -> {
            List<String> ids;

            try (UploadQueueDb db = new UploadQueueDb(this)) {
                ids = db.getIdsForRecovery();
            }

            for (String id : ids) {
                scheduleUpload(id);
            }
        });
    }

    private void retryFailedUploads() {
        queueExecutor.execute(() -> {
            List<String> ids;

            try (UploadQueueDb db = new UploadQueueDb(this)) {
                ids = db.getFailedIds();

                for (String id : ids) {
                    db.setStatus(
                        id, UploadQueueDb.PENDING, null
                    );
                }
            }

            for (String id : ids) {
                scheduleUpload(id);
            }

            runOnUiThread(() -> {
                updateSelectionText();
                Toast.makeText(
                    MainActivity.this,
                    ids.size() + " file dijadwalkan ulang",
                    Toast.LENGTH_LONG
                ).show();
            });
        });
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(
            uri,
            new String[]{
                android.provider.OpenableColumns.DISPLAY_NAME
            },
            null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                    android.provider.OpenableColumns.DISPLAY_NAME
                );

                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return "file_" + UUID.randomUUID();
    }

    private String getExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0
            ? name.substring(dot).toLowerCase(Locale.ROOT)
            : "";
    }

    private void startCamera() {
        try {
            ProcessCameraProvider cameraProvider =
                ProcessCameraProvider.getInstance(this).get();

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(
                previewView.getSurfaceProvider()
            );

            imageCapture = new ImageCapture.Builder()
                .setCaptureMode(
                    ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                )
                .build();

            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            );

        } catch (Exception e) {
            Toast.makeText(
                this,
                "Gagal membuka kamera: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap",
                Toast.LENGTH_SHORT).show();
            return;
        }

        String timestamp = new SimpleDateFormat(
            "yyyyMMdd_HHmmss", Locale.US
        ).format(new Date());

        ContentValues values = new ContentValues();
        values.put(
            MediaStore.Images.Media.DISPLAY_NAME,
            "RemoteCamera_" + timestamp + ".jpg"
        );
        values.put(
            MediaStore.Images.Media.MIME_TYPE,
            "image/jpeg"
        );

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/RemoteCamera"
            );
        }

        ImageCapture.OutputFileOptions options =
            new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(),
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ).build();

        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(
                    ImageCapture.OutputFileResults result
                ) {
                    Toast.makeText(
                        MainActivity.this,
                        "Foto tersimpan di Pictures/RemoteCamera",
                        Toast.LENGTH_LONG
                    ).show();
                }

                @Override
                public void onError(ImageCaptureException error) {
                    Toast.makeText(
                        MainActivity.this,
                        "Gagal mengambil foto: "
                            + error.getMessage(),
                        Toast.LENGTH_LONG
                    ).show();
                }
            }
        );
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        );

        if (requestCode == CAMERA_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            Toast.makeText(
                this,
                "Izin kamera diperlukan",
                Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        queueExecutor.shutdown();
    }
}
