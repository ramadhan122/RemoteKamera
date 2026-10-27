
package com.remotecamera.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.remotecamera.app.BuildConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class UploadWorker extends Worker {

    public static final String KEY_ID = "upload_id";

    private static final String SERVER_URL =
        "http://100.123.127.19:8000/upload";

    // Ganti dengan token baru yang sama dengan token server.
    private static final String API_TOKEN = BuildConfig.REMOTE_CAMERA_TOKEN;

    public UploadWorker(
        @NonNull Context context,
        @NonNull WorkerParameters params
    ) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String id = getInputData().getString(KEY_ID);

        if (id == null || id.trim().isEmpty()) {
            return Result.failure();
        }

        UploadQueueDb db = new UploadQueueDb(getApplicationContext());
        UploadQueueDb.UploadItem item = db.getItem(id);

        if (item == null || UploadQueueDb.SENT.equals(item.status)) {
            db.close();
            return Result.success();
        }

        File file = new File(item.path);

        if (!file.isFile()) {
            db.setStatus(id, UploadQueueDb.FAILED,
                "Salinan antrean tidak ditemukan.");
            db.close();
            return Result.failure();
        }

        if ("GANTI_DENGAN_TOKEN_BARU".equals(API_TOKEN)
                || API_TOKEN.trim().isEmpty()) {
            db.setStatus(id, UploadQueueDb.FAILED,
                "API_TOKEN belum diisi.");
            db.close();
            return Result.failure();
        }

        db.setStatus(id, UploadQueueDb.SENDING, null);

        HttpURLConnection connection = null;

        try {
            String boundary = "----RemoteCamera" + UUID.randomUUID();

            connection = (HttpURLConnection)
                new URL(SERVER_URL).openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);

            connection.setRequestProperty(
                "Authorization", "Bearer " + API_TOKEN
            );
            connection.setRequestProperty(
                "X-File-ID", item.id
            );
            connection.setRequestProperty(
                "Content-Type", "multipart/form-data; boundary=" + boundary
            );

            String safeName = item.filename
                .replace("\\", "_")
                .replace("\"", "_")
                .replace("\r", "_")
                .replace("\n", "_");

            try (OutputStream output = new BufferedOutputStream(
                    connection.getOutputStream());
                 InputStream input = new BufferedInputStream(
                    new FileInputStream(file))) {

                writeText(output, "--" + boundary + "\r\n");
                writeText(output,
                    "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeName + "\"\r\n");
                writeText(output,
                    "Content-Type: application/octet-stream\r\n\r\n");

                byte[] buffer = new byte[64 * 1024];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    if (isStopped()) {
                        db.setStatus(id, UploadQueueDb.PENDING,
                            "Transfer dijadwalkan ulang.");
                        return Result.retry();
                    }

                    output.write(buffer, 0, count);
                }

                writeText(output, "\r\n--" + boundary + "--\r\n");
                output.flush();
            }

            int status = connection.getResponseCode();

            if (status >= 200 && status < 300) {
                db.setStatus(id, UploadQueueDb.SENT, null);

                // Hapus hanya salinan antrean, bukan file asli HP.
                file.delete();

                return Result.success();
            }

            String detail = "HTTP " + status;
            InputStream errorStream = connection.getErrorStream();

            if (errorStream != null) {
                try (InputStream error = errorStream;
                     ByteArrayOutputStream bytes =
                         new ByteArrayOutputStream()) {

                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = error.read(buffer)) != -1
                            && bytes.size() < 4096) {
                        bytes.write(buffer, 0, count);
                    }

                    String response = new String(
                        bytes.toByteArray(), StandardCharsets.UTF_8
                    );

                    if (!response.isEmpty()) {
                        detail += ": " + response;
                    }
                } catch (Exception ignored) {
                }
            }

            boolean temporary =
                status == 408 || status == 429 || status >= 500;

            if (temporary) {
                db.setStatus(id, UploadQueueDb.PENDING, detail);
                return Result.retry();
            }

            db.setStatus(id, UploadQueueDb.FAILED, detail);
            return Result.failure();

        } catch (Exception e) {
            String detail = e.getMessage() == null
                ? "Koneksi gagal." : e.getMessage();

            db.setStatus(id, UploadQueueDb.PENDING, detail);
            return Result.retry();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            db.close();
        }
    }

    private void writeText(OutputStream output, String text)
            throws java.io.IOException {
        output.write(text.getBytes(StandardCharsets.UTF_8));
    }
}
