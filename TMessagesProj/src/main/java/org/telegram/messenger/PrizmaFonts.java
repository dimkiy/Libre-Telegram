package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Map;

public final class PrizmaFonts {
    private static Typeface regular;
    private static Typeface bold;
    private static String loadedPath;

    private PrizmaFonts() {}

    public static boolean hasCustom() {
        String p = PrizmaConfig.customFontPath();
        return p != null && p.length() > 0;
    }

    private static void ensureLoaded() {
        String path = PrizmaConfig.customFontPath();
        if (path == null || path.length() == 0) {
            regular = null;
            bold = null;
            loadedPath = null;
            return;
        }
        if (path.equals(loadedPath) && regular != null) {
            return;
        }
        try {
            Typeface base = Typeface.createFromFile(path);
            regular = base;
            if (Build.VERSION.SDK_INT >= 28) {
                bold = Typeface.create(base, 700, false);
            } else {
                bold = Typeface.create(base, Typeface.BOLD);
            }
            loadedPath = path;
        } catch (Throwable e) {
            regular = null;
            bold = null;
            loadedPath = null;
        }
    }

    public static Typeface forAsset(String assetPath) {
        ensureLoaded();
        if (regular == null) {
            return null;
        }
        if (assetPath == null) {
            return regular;
        }
        if (assetPath.contains("mono")) {
            return null;
        }
        if (assetPath.contains("extrabold") || assetPath.contains("medium") || assetPath.contains("bold")) {
            return bold;
        }
        return regular;
    }

    public static Typeface regular() {
        ensureLoaded();
        return regular;
    }

    public static Typeface bold() {
        ensureLoaded();
        return bold;
    }

    public static void applyGlobal() {
        ensureLoaded();
        if (regular == null) {
            return;
        }
        try {
            Field mapField = Typeface.class.getDeclaredField("sSystemFontMap");
            mapField.setAccessible(true);
            Object mapObj = mapField.get(null);
            if (mapObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Typeface> m = (Map<String, Typeface>) mapObj;
                String[] keys = {
                        "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-thin",
                        "sans-serif-condensed", "sans-serif-black", "normal", "default", "roboto", "serif"
                };
                for (String k : keys) {
                    m.put(k, regular);
                }
            }
        } catch (Throwable ignore) {}
        replaceStatic("DEFAULT", regular);
        replaceStatic("DEFAULT_BOLD", bold);
        replaceStatic("SANS_SERIF", regular);
        replaceStatic("SERIF", regular);
    }

    private static void replaceStatic(String name, Typeface t) {
        if (t == null) {
            return;
        }
        try {
            Field f = Typeface.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(null, t);
        } catch (Throwable ignore) {}
    }

    public static void invalidate() {
        regular = null;
        bold = null;
        loadedPath = null;
    }

    public static final int REQUEST = 48219;

    public static void pick(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf",
                    "application/font-sfnt", "application/octet-stream"
            });
            activity.startActivityForResult(Intent.createChooser(intent, "Шрифт (.ttf / .otf)"), REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static boolean handlePicked(Activity activity, Uri uri) {
        if (activity == null || uri == null) {
            return false;
        }
        try {
            File dst = new File(ApplicationLoader.getFilesDirFixed(), "prizma_font.ttf");
            dst.delete();
            InputStream in = activity.getContentResolver().openInputStream(uri);
            OutputStream os = new FileOutputStream(dst);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
            }
            os.close();
            in.close();
            Typeface test = Typeface.createFromFile(dst);
            if (test == null) {
                dst.delete();
                return false;
            }
            PrizmaConfig.setCustomFontPath(dst.getAbsolutePath());
            invalidate();
            applyGlobal();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void clear() {
        String p = PrizmaConfig.customFontPath();
        PrizmaConfig.setCustomFontPath("");
        invalidate();
        try {
            if (p != null && p.length() > 0) {
                new File(p).delete();
            }
        } catch (Throwable ignore) {}
    }
}
