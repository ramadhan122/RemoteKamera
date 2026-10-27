
package com.remotecamera.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class UploadQueueDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "upload_queue.db";
    private static final int DB_VERSION = 1;

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";

    public UploadQueueDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(
            "CREATE TABLE uploads (" +
            "id TEXT PRIMARY KEY, " +
            "filename TEXT NOT NULL, " +
            "local_path TEXT NOT NULL, " +
            "status TEXT NOT NULL, " +
            "error TEXT, " +
            "created_at INTEGER NOT NULL)"
        );
    }

    @Override
    public void onUpgrade(
        SQLiteDatabase db, int oldVersion, int newVersion
    ) {
        // Tidak menghapus data antrean.
    }

    public void add(String id, String filename, String path) {
        ContentValues values = new ContentValues();
        values.put("id", id);
        values.put("filename", filename);
        values.put("local_path", path);
        values.put("status", PENDING);
        values.put("created_at", System.currentTimeMillis());

        getWritableDatabase().insertOrThrow("uploads", null, values);
    }

    public UploadItem getItem(String id) {
        try (Cursor c = getReadableDatabase().query(
            "uploads",
            new String[]{"id", "filename", "local_path", "status"},
            "id = ?",
            new String[]{id},
            null, null, null
        )) {
            if (!c.moveToFirst()) return null;

            return new UploadItem(
                c.getString(0), c.getString(1),
                c.getString(2), c.getString(3)
            );
        }
    }

    public List<String> getIdsForRecovery() {
        List<String> ids = new ArrayList<>();

        try (Cursor c = getReadableDatabase().query(
            "uploads",
            new String[]{"id"},
            "status IN (?, ?)",
            new String[]{PENDING, SENDING},
            null, null, "created_at ASC"
        )) {
            while (c.moveToNext()) {
                ids.add(c.getString(0));
            }
        }

        return ids;
    }

    public List<String> getFailedIds() {
        List<String> ids = new ArrayList<>();

        try (Cursor c = getReadableDatabase().query(
            "uploads",
            new String[]{"id"},
            "status = ?",
            new String[]{FAILED},
            null, null, "created_at ASC"
        )) {
            while (c.moveToNext()) {
                ids.add(c.getString(0));
            }
        }

        return ids;
    }

    public int countStatus(String status) {
        try (Cursor c = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM uploads WHERE status = ?",
            new String[]{status}
        )) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public void setStatus(String id, String status, String error) {
        ContentValues values = new ContentValues();
        values.put("status", status);
        values.put("error", error);

        getWritableDatabase().update(
            "uploads", values, "id = ?", new String[]{id}
        );
    }

    public static class UploadItem {
        public final String id;
        public final String filename;
        public final String path;
        public final String status;

        public UploadItem(
            String id, String filename, String path, String status
        ) {
            this.id = id;
            this.filename = filename;
            this.path = path;
            this.status = status;
        }
    }
}
