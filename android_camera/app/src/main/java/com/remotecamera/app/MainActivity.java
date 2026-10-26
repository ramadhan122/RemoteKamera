package com.remotecamera.app;

import android.Manifest;
import androidx.appcompat.app.AppCompatActivity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {
        
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private PreviewView previewView;
    private ImageCapture imageCapture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        Button captureButton = new Button(this);
        captureButton.setText("AMBIL FOTO");
        captureButton.setTextSize(16);
        captureButton.setOnClickListener(v -> takePhoto());

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(16, 8, 16, 8);
        bottom.addView(captureButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(bottom);
        setContentView(root);

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

    private void startCamera() {
        ProcessCameraProvider cameraProvider;

        try {
            cameraProvider = ProcessCameraProvider.getInstance(this).get();
        } catch (ExecutionException | InterruptedException e) {
            Toast.makeText(this, "Gagal membuka kamera", Toast.LENGTH_LONG).show();
            return;
        }

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
            );
        } catch (Exception e) {
            Toast.makeText(this, "Kamera tidak dapat dijalankan", Toast.LENGTH_LONG).show();
        }
    }

    private void takePhoto() {
        if (imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
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
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

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
                                "Gagal mengambil foto: " + error.getMessage(),
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
        } else {
            Toast.makeText(
                    this,
                    "Izin kamera diperlukan untuk menggunakan aplikasi",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}