package com.zcode.sentinelcam;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;

/** 轻量人脸比对：aHash + dHash(横/竖) 共 192 位感知哈希 + 汉明距离。 */
final class FaceUtil {
    static final int MATCH_THRESHOLD = 30; // 192 位中允许 ~16% 位差

    static long[] hashes(Bitmap face) {
        return new long[]{
                aHash(face),
                dHash(face, 9, 8, true),
                dHash(face, 8, 9, false)
        };
    }

    private static long aHash(Bitmap src) {
        Bitmap b = Bitmap.createScaledBitmap(src, 8, 8, true);
        int[] gray = new int[64];
        int mean = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int g = grayAt(b, x, y);
                gray[y * 8 + x] = g;
                mean += g;
            }
        }
        mean /= 64;
        long h = 0;
        for (int i = 0; i < 64; i++) {
            if (gray[i] > mean) h |= 1L << i;
        }
        return h;
    }

    private static long dHash(Bitmap src, int w, int h, boolean horizontal) {
        Bitmap b = Bitmap.createScaledBitmap(src, w, h, true);
        long hash = 0;
        int bit = 0;
        if (horizontal) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    if (grayAt(b, x, y) > grayAt(b, x + 1, y)) hash |= 1L << bit;
                    bit++;
                }
            }
        } else {
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    if (grayAt(b, x, y) > grayAt(b, x, y + 1)) hash |= 1L << bit;
                    bit++;
                }
            }
        }
        return hash;
    }

    private static int grayAt(Bitmap b, int x, int y) {
        int c = b.getPixel(x, y);
        return (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
    }

    static int distance(long[] a, long[] b) {
        int d = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            d += Long.bitCount(a[i] ^ b[i]);
        }
        return d;
    }

    /** "l,t,r,b" 归一化人脸框解析。 */
    static RectF parseRect(String s) {
        try {
            String[] p = s.split(",");
            return new RectF(Float.parseFloat(p[0]), Float.parseFloat(p[1]),
                    Float.parseFloat(p[2]), Float.parseFloat(p[3]));
        } catch (Exception e) {
            return null;
        }
    }

    private FaceUtil() {}
}
