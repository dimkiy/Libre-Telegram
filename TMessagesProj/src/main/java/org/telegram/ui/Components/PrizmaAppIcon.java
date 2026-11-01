package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;

import java.io.InputStream;

/**
 * Prizma: «Своя картинка» for the app icon.
 *
 * Android only lets an app switch between icons it was built with (the Libre icons are aliases in
 * the manifest). Any picture of the person's own can go to the home screen as a pinned shortcut —
 * it opens Libre just like the app icon; the launcher asks to confirm adding it.
 */
public final class PrizmaAppIcon {

    public static final int REQUEST = 48240;

    private PrizmaAppIcon() {}

    public static void pick(Activity activity) {
        if (activity == null) return;
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            Toast.makeText(activity, "Ваш рабочий стол не умеет добавлять ярлыки со своей картинкой", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            activity.startActivityForResult(Intent.createChooser(intent, "Картинка для иконки"), REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.onActivityResult. */
    public static void handlePicked(Activity activity, Uri uri) {
        if (activity == null || uri == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap icon = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= 432) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap src;
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    src = BitmapFactory.decodeStream(in, null, o);
                }
                if (src != null) {
                    icon = adaptive(src);
                    src.recycle();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap result = icon;
            AndroidUtilities.runOnUIThread(() -> pin(activity, result));
        });
    }

    /**
     * Adaptive icons are 108 dp with only the middle 72 dp sure to show — the picture is centre-cropped
     * to a square and scaled so that middle part is what the person sees.
     */
    private static Bitmap adaptive(Bitmap src) {
        final int size = 432;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        int side = Math.min(src.getWidth(), src.getHeight());
        float scale = size / (float) side; // the square fills the whole layer; launchers show its middle two thirds
        Matrix m = new Matrix();
        m.postTranslate(-(src.getWidth() - side) / 2f, -(src.getHeight() - side) / 2f);
        m.postScale(scale, scale);
        c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    private static void pin(Context ctx, Bitmap icon) {
        if (icon == null) {
            Toast.makeText(ctx, "Не удалось открыть картинку", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent open = new Intent(ctx, LaunchActivity.class);
            open.setAction(Intent.ACTION_MAIN);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(ctx, "libre_custom_" + System.currentTimeMillis())
                    .setShortLabel("Libre")
                    .setIcon(IconCompat.createWithAdaptiveBitmap(icon))
                    .setIntent(open)
                    .build();
            if (ShortcutManagerCompat.requestPinShortcut(ctx, info, null)) {
                Toast.makeText(ctx, "Подтвердите добавление иконки на рабочий стол", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(ctx, "Рабочий стол отказался добавить иконку", Toast.LENGTH_LONG).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            Toast.makeText(ctx, "Не удалось добавить иконку", Toast.LENGTH_SHORT).show();
        }
    }
}
