package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;

public final class PrizmaBitmaps {
    private PrizmaBitmaps() {}

    public static Bitmap decode(String path, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    public static Bitmap fit(Bitmap bitmap, int maxSide) {
        if (bitmap == null) return null;
        float scale = Math.min(1f, maxSide / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
        if (scale >= 1f) return bitmap;
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
            if (scaled != bitmap) bitmap.recycle();
            return scaled;
        } catch (Throwable e) {
            return bitmap;
        }
    }

    public static byte[] jpeg(String path, int maxSide, int quality) {
        Bitmap bitmap = fit(decode(path, maxSide), maxSide);
        if (bitmap == null) return null;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        } finally {
            bitmap.recycle();
        }
    }
}
