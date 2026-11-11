package org.telegram.ui.Components;

import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.Utilities;

public class PrizmaWallpaper {
    public static final int[][] PALETTES = new int[][]{
            {0xffFF6FD8, 0xff8A5CFF, 0xff3CC8FF, 0xff5B6CFF},
            {0xff34E0A1, 0xff2AC9D6, 0xff6FE7A8, 0xff3FB6F0},
            {0xffFF9A5A, 0xffFF5E8A, 0xffB15BFF, 0xffFF7E6B},
            {0xff2E6BFF, 0xff28B6FF, 0xff5E3CFF, 0xff23D5C9},
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

    public static MotionBackgroundDrawable preview(int paletteIndex) {
        int[] c = PALETTES[clampPalette(paletteIndex)];
        return new MotionBackgroundDrawable(c[0], c[1], c[2], c[3], false);
    }

    public static void invalidate() {
        keyVersion = -1;
    }

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

    private static android.graphics.Bitmap customBitmap;
    private static String customBitmapPath;
    private static String customFailedPath;

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
        if (path.equals(customFailedPath)) {
            return null;
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
        customFailedPath = customBitmap == null ? path : null;
        return customBitmap;
    }

    private static int keyVersion = -1;
    private static boolean keyFancy;
    private static String keyCached;

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
            d.setIndeterminateAnimation(PrizmaConfig.fancyEffects());
            return d;
        }
        return null;
    }
}
