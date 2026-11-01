package org.telegram.ui.Components;

import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.Utilities;

/**
 * Prizma: chat wallpapers of our own — the living (continuously shifting) gradient built on
 * {@link MotionBackgroundDrawable}, and a custom photo / video picked by the user.
 *
 * ChatActivity's getNewDrawable() is called on every frame of the chat background, so everything
 * it touches here must be cheap: the setting key is cached per config version and the photo is
 * decoded once (ahead of time on a background thread).
 */
public class PrizmaWallpaper {

    /** Each palette is 4 colors for the 4-point motion gradient. */
    public static final int[][] PALETTES = new int[][]{
            // 0 — Призма (переливающийся бренд: розовый/фиолетовый/голубой/синий)
            {0xffFF6FD8, 0xff8A5CFF, 0xff3CC8FF, 0xff5B6CFF},
            // 1 — Аврора (мятный/бирюзовый/зелёный/голубой)
            {0xff34E0A1, 0xff2AC9D6, 0xff6FE7A8, 0xff3FB6F0},
            // 2 — Закат (оранжевый/розовый/фиолетовый/коралл)
            {0xffFF9A5A, 0xffFF5E8A, 0xffB15BFF, 0xffFF7E6B},
            // 3 — Океан (синий/голубой/индиго/бирюза)
            {0xff2E6BFF, 0xff28B6FF, 0xff5E3CFF, 0xff23D5C9},
            // 4 — Неон (магента/фиолетовый/синий/циан)
            {0xffFF3CAC, 0xff784BA0, 0xff2B86C5, 0xff2BE0C5},
    };

    public static final String[] PALETTE_NAMES = new String[]{
            "Призма", "Аврора", "Закат", "Океан", "Неон"
    };

    public static int clampPalette(int index) {
        if (index < 0) return 0;
        if (index >= PALETTES.length) return PALETTES.length - 1;
        return index;
    }

    /** Builds a small standalone preview drawable for a palette swatch (not animated). */
    public static MotionBackgroundDrawable preview(int paletteIndex) {
        int[] c = PALETTES[clampPalette(paletteIndex)];
        return new MotionBackgroundDrawable(c[0], c[1], c[2], c[3], false);
    }

    /** Called when the palette / lite mode changes so open chats rebuild their wallpaper. */
    public static void invalidate() {
        keyVersion = -1;
    }

    // ===== custom chat wallpaper (own photo / video) =====

    public static boolean hasCustom() {
        String p = PrizmaConfig.chatWallpaperPath();
        return p != null && p.length() > 0;
    }

    public static void invalidateCustom() {
        synchronized (PrizmaWallpaper.class) {
            customBitmap = null;
            customBitmapPath = null;
        }
        keyVersion = -1;
        preload();
    }

    // One decoded bitmap, shared read-only across views (safe); each view wraps it in its own drawable.
    private static android.graphics.Bitmap customBitmap;
    private static String customBitmapPath;

    /** Decodes the custom photo wallpaper in the background so opening a chat never decodes it on the UI thread. */
    public static void preload() {
        if (!hasCustom() || PrizmaConfig.chatWallpaperIsVideo()) {
            return;
        }
        Utilities.globalQueue.postRunnable(PrizmaWallpaper::getCustomBitmap);
    }

    private static synchronized android.graphics.Bitmap getCustomBitmap() {
        String path = PrizmaConfig.chatWallpaperPath();
        if (path == null || path.length() == 0) {
            customBitmap = null;
            customBitmapPath = null;
            return null;
        }
        if (customBitmap != null && !customBitmap.isRecycled() && path.equals(customBitmapPath)) {
            return customBitmap;
        }
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, bounds);
            int target = Math.max(org.telegram.messenger.AndroidUtilities.displaySize.x, org.telegram.messenger.AndroidUtilities.displaySize.y);
            if (target <= 0) target = 1280;
            int sample = 1;
            int longer = Math.max(bounds.outWidth, bounds.outHeight);
            while (longer / sample > target * 1.5f) {
                sample *= 2;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            customBitmap = android.graphics.BitmapFactory.decodeFile(path, o);
            customBitmapPath = path;
        } catch (Throwable e) {
            customBitmap = null;
            customBitmapPath = null;
        }
        return customBitmap;
    }

    // ===== per-view wallpaper (no cross-window sharing — avoids chat jitter) =====

    private static int keyVersion = -1;
    private static boolean keyFancy;
    private static String keyCached;

    /** A key describing the current wallpaper setting; null when there is none. Hosts cache per-view by this. */
    public static String currentKey() {
        final int v = PrizmaConfig.version();
        final boolean fancy = PrizmaConfig.fancyEffects();
        if (v != keyVersion || fancy != keyFancy) {
            keyCached = computeKey(fancy);
            keyVersion = v;
            keyFancy = fancy;
        }
        return keyCached;
    }

    private static String computeKey(boolean fancy) {
        if (hasCustom()) {
            return "c:" + (PrizmaConfig.chatWallpaperIsVideo() ? "v:" : "p:") + PrizmaConfig.chatWallpaperPath();
        }
        if (PrizmaConfig.liveWallpaper()) {
            return "l:" + clampPalette(PrizmaConfig.liveWallpaperPalette()) + (fancy ? "" : ":static");
        }
        return null;
    }

    /** Builds a FRESH drawable for the current setting, owned by a single view (no sharing → no jitter). */
    public static android.graphics.drawable.Drawable createForView() {
        if (hasCustom()) {
            if (PrizmaConfig.chatWallpaperIsVideo()) {
                return new PrizmaVideoWallpaper(PrizmaConfig.chatWallpaperPath());
            }
            android.graphics.Bitmap bmp = getCustomBitmap();
            if (bmp == null) {
                return null;
            }
            return new android.graphics.drawable.BitmapDrawable(
                    org.telegram.messenger.ApplicationLoader.applicationContext.getResources(), bmp);
        }
        if (PrizmaConfig.liveWallpaper()) {
            int[] c = PALETTES[clampPalette(PrizmaConfig.liveWallpaperPalette())];
            MotionBackgroundDrawable d = new MotionBackgroundDrawable(c[0], c[1], c[2], c[3], false);
            d.setIndeterminateSpeedScale(1.0f);
            // the endless animation redraws the whole chat background every frame — static gradient
            // in Lite mode, on weak devices and in battery saver
            d.setIndeterminateAnimation(PrizmaConfig.fancyEffects());
            return d;
        }
        return null;
    }
}
