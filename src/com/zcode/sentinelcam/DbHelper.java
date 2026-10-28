package com.zcode.sentinelcam;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;

/**
 * 事件留档 + 认识名单。
 * events.marked: 0=默认告警（陌生人），1=豁免（命中认识名单）。
 */
class DbHelper extends SQLiteOpenHelper {
    static class EventRow {
        public long id;
        public long ts;
        public String path;
        public String rect;
        public boolean face;
        public boolean motion;
        public int marked;
    }

    static class FaceRec {
        public long id;
        public String name;
        public long[] hash;
    }

    DbHelper(Context c) {
        super(c, "sentinel.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE events("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "ts INTEGER NOT NULL,"
                + "path TEXT DEFAULT '',"
                + "rect TEXT DEFAULT '',"
                + "face INTEGER DEFAULT 0,"
                + "motion INTEGER DEFAULT 0,"
                + "marked INTEGER DEFAULT 0)");
        d.execSQL("CREATE TABLE faces("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "h1 INTEGER NOT NULL,"
                + "h2 INTEGER NOT NULL,"
                + "h3 INTEGER NOT NULL,"
                + "created INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase d, int o, int n) {
        if (o < 2) {
            try {
                d.execSQL("ALTER TABLE events ADD COLUMN rect TEXT DEFAULT ''");
            } catch (Exception ignored) {}
            try {
                d.execSQL("CREATE TABLE IF NOT EXISTS faces("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "name TEXT NOT NULL,"
                        + "h1 INTEGER NOT NULL,"
                        + "h2 INTEGER NOT NULL,"
                        + "h3 INTEGER NOT NULL,"
                        + "created INTEGER)");
            } catch (Exception ignored) {}
        }
    }

    void insert(long ts, String path, String rect, boolean face, boolean motion, int marked) {
        ContentValues cv = new ContentValues();
        cv.put("ts", ts);
        cv.put("path", path);
        cv.put("rect", rect);
        cv.put("face", face ? 1 : 0);
        cv.put("motion", motion ? 1 : 0);
        cv.put("marked", marked);
        try {
            getWritableDatabase().insert("events", null, cv);
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db insert failed", e);
        }
    }

    void updateMarked(long id, int marked) {
        ContentValues cv = new ContentValues();
        cv.put("marked", marked);
        try {
            getWritableDatabase().update("events", cv, "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db updateMarked failed", e);
        }
    }

    void delete(long id) {
        try {
            getWritableDatabase().delete("events", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db delete failed", e);
        }
    }

    void clear() {
        try {
            getWritableDatabase().delete("events", null, null);
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db clear failed", e);
        }
    }

    long count() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM events", null);
        long n = 0;
        if (c.moveToFirst()) n = c.getLong(0);
        c.close();
        return n;
    }

    EventRow[] recent(int n) {
        Cursor c = getReadableDatabase().query(
                "events", null, null, null, null, null, "id DESC", String.valueOf(n));
        EventRow[] rows = new EventRow[c.getCount()];
        int i = 0;
        while (c.moveToNext()) {
            EventRow r = new EventRow();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
            r.path = c.getString(c.getColumnIndexOrThrow("path"));
            r.rect = c.getString(c.getColumnIndexOrThrow("rect"));
            r.face = c.getInt(c.getColumnIndexOrThrow("face")) != 0;
            r.motion = c.getInt(c.getColumnIndexOrThrow("motion")) != 0;
            r.marked = c.getInt(c.getColumnIndexOrThrow("marked"));
            rows[i++] = r;
        }
        c.close();
        return rows;
    }

    void addFace(String name, long[] h) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("h1", h[0]);
        cv.put("h2", h[1]);
        cv.put("h3", h[2]);
        cv.put("created", System.currentTimeMillis());
        try {
            getWritableDatabase().insert("faces", null, cv);
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db addFace failed", e);
        }
    }

    ArrayList<FaceRec> allFaces() {
        ArrayList<FaceRec> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("faces", null, null, null, null, null, "id ASC");
        while (c.moveToNext()) {
            FaceRec r = new FaceRec();
            r.id = c.getLong(c.getColumnIndexOrThrow("id"));
            r.name = c.getString(c.getColumnIndexOrThrow("name"));
            r.hash = new long[]{
                    c.getLong(c.getColumnIndexOrThrow("h1")),
                    c.getLong(c.getColumnIndexOrThrow("h2")),
                    c.getLong(c.getColumnIndexOrThrow("h3"))};
            out.add(r);
        }
        c.close();
        return out;
    }

    void deleteFace(long id) {
        try {
            getWritableDatabase().delete("faces", "id=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.e(SentinelService.TAG, "db deleteFace failed", e);
        }
    }
}
