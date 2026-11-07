package org.telegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioManager;

import java.io.File;
import java.io.FileOutputStream;

public final class PrizmaCapture {
    private static final int[] STREAMS = {AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_RING, AudioManager.STREAM_SYSTEM};
    private static boolean quiet;

    private PrizmaCapture() {
    }

    public static void quiet(boolean on) {
        if (on && !PrizmaConfig.muteSystemOnRecord() || on == quiet) return;
        quiet = on;
        try {
            AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            for (int stream : STREAMS) {
                try {
                    am.adjustStreamVolume(stream, on ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
    }

    public static int crop(File file, int orientation) {
        int mode = PrizmaConfig.photoAspect();
        if (mode == 0 || file == null || !file.exists()) return orientation;
        try {
            Bitmap source = BitmapFactory.decodeFile(file.getAbsolutePath());
            if (source == null) return orientation;
            if (orientation > 0) {
                Matrix m = new Matrix();
                m.postRotate(orientation);
                Bitmap turned = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), m, true);
                if (turned != source) source.recycle();
                source = turned;
            }
            int w = source.getWidth(), h = source.getHeight();
            boolean portrait = h >= w;
            float ratio = mode == 1 ? 1f : mode == 2 ? 4f / 3f : 16f / 9f;
            int cw, ch;
            if (portrait) {
                cw = w;
                ch = Math.min(h, Math.round(w * ratio));
            } else {
                ch = h;
                cw = Math.min(w, Math.round(h * ratio));
            }
            if (mode == 1) {
                cw = ch = Math.min(w, h);
            }
            Bitmap cut = Bitmap.createBitmap(source, (w - cw) / 2, (h - ch) / 2, cw, ch);
            try (FileOutputStream out = new FileOutputStream(file)) {
                cut.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            if (cut != source) cut.recycle();
            source.recycle();
            return 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return orientation;
        }
    }
}
