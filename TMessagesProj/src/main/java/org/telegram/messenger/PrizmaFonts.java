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

/**
 * Prizma: applies a user-chosen custom font across the whole app.
 *
 * Telegram routes its styled text (titles, names, buttons, bold/medium) through
 * {@link AndroidUtilities#getTypeface(String)} and {@link AndroidUtilities#bold()} — we swap those.
 * Plain body text uses the system default, which we replace once at startup via reflection
 * ({@link #applyGlobal()}). Everything is best-effort and wrapped in try/catch so a bad font
 * or a locked-down device can never crash the app.
 */
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

    /** Returns the custom typeface to use in place of the given built-in asset, or null to keep the asset. */
    public static Typeface forAsset(String assetPath) {
        ensureLoaded();
        if (regular == null) {
            return null;
        }
        if (assetPath == null) {
            return regular;
        }
        if (assetPath.contains("mono")) {
            return null; // keep monospace for code blocks
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

    /** Replace the system default fonts so even non-styled text uses the custom font. Best-effort. */
    public static void applyGlobal() {
        ensureLoaded();
        if (regular == null) {
            return;
        }
        // API 28+: the system keeps a name→Typeface map
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
        // Static Typeface.DEFAULT family
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

    /** Forget the loaded font so the next access reloads from the (possibly new) path. */
    public static void invalidate() {
        regular = null;
        bold = null;
        loadedPath = null;
    }

    // ===== picking a font file =====

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

    /** Copies the picked font into app storage and applies it. Returns true on success. */
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
            Typeface test = Typeface.createFromFile(dst); // validate it's a real font
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
