package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class PrizmaBanner {
    public static final int REQUEST = 48210;

    private static Bitmap cached;
    private static String cachedPath;

    public static void pick(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            activity.startActivityForResult(Intent.createChooser(intent, "Баннер профиля"), REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void handlePicked(Activity activity, Uri uri) {
        if (activity == null || uri == null) {
            return;
        }
        final android.content.ContentResolver resolver = activity.getContentResolver();
        final android.content.Context appContext = activity.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File tmp = new File(ApplicationLoader.getFilesDirFixed(), "prizma_banner_tmp");
                InputStream in = resolver.openInputStream(uri);
                OutputStream os = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                }
                os.close();
                in.close();

                String mime = resolver.getType(uri);
                boolean isVideo;
                if (mime != null) {
                    isVideo = mime.startsWith("video");
                } else {
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(tmp.getAbsolutePath(), opt);
                    isVideo = opt.outWidth <= 0;
                }

                File dst = new File(ApplicationLoader.getFilesDirFixed(), (isVideo ? "prizma_banner.mp4" : "prizma_banner.jpg").replace(".", "_" + System.currentTimeMillis() + "."));
                if (!tmp.renameTo(dst)) {
                    dst = tmp;
                }
                final String path = dst.getAbsolutePath();
                final boolean video = isVideo;
                if (!video) org.telegram.messenger.PrizmaExif.normalize(path);
                AndroidUtilities.runOnUIThread(() -> {
                    String old = PrizmaConfig.bannerPath();
                    PrizmaConfig.setBannerPath(path);
                    PrizmaConfig.setBannerIsVideo(video);
                    cached = null;
                    cachedPath = null;
                    failedPath = null;
                    preload();
                    if (old != null && old.length() > 0 && !old.equals(path)) {
                        new File(old).delete();
                    }
                    org.telegram.messenger.PrizmaCloud.syncOwnBanner(org.telegram.messenger.UserConfig.selectedAccount);
                    android.widget.Toast.makeText(appContext, "Баннер профиля установлен", android.widget.Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static Bitmap getBitmap() {
        if (PrizmaConfig.bannerIsVideo()) {
            return null;
        }
        String path = PrizmaConfig.bannerPath();
        if (path == null || path.length() == 0) {
            cached = null;
            cachedPath = null;
            return null;
        }
        Bitmap b = cached;
        if (b != null && !b.isRecycled() && path.equals(cachedPath)) {
            return b;
        }
        preload();
        return null;
    }

    private static volatile boolean decoding;
    private static String failedPath;

    public static void preload() {
        if (decoding || PrizmaConfig.bannerIsVideo()) {
            return;
        }
        final String path = PrizmaConfig.bannerPath();
        if (path == null || path.length() == 0 || path.equals(cachedPath) && cached != null || path.equals(failedPath)) {
            return;
        }
        decoding = true;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap result = null;
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int target = Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y);
                if (target <= 0) {
                    target = 1080;
                }
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= target) {
                    sample *= 2;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                result = BitmapFactory.decodeFile(path, o);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final Bitmap decoded = result;
            AndroidUtilities.runOnUIThread(() -> {
                decoding = false;
                if (decoded == null) {
                    failedPath = path;
                } else if (path.equals(PrizmaConfig.bannerPath())) {
                    cached = decoded;
                    cachedPath = path;
                }
            });
        });
    }

    public static boolean isLoading() {
        return !PrizmaConfig.bannerIsVideo() && hasBanner() && cached == null && !PrizmaConfig.bannerPath().equals(failedPath);
    }

    public static AnimatedFileDrawable createAnimated() {
        if (!PrizmaConfig.bannerIsVideo()) {
            return null;
        }
        String path = PrizmaConfig.bannerPath();
        if (path == null || path.length() == 0) {
            return null;
        }
        return PrizmaVideoWallpaper.createLooping(path);
    }

    public static boolean hasBanner() {
        String path = PrizmaConfig.bannerPath();
        return path != null && path.length() > 0;
    }

    public static void clear() {
        String p = PrizmaConfig.bannerPath();
        PrizmaConfig.setBannerPath("");
        PrizmaConfig.setBannerIsVideo(false);
        cached = null;
        cachedPath = null;
        try {
            if (p != null && p.length() > 0) {
                new File(p).delete();
            }
        } catch (Throwable ignore) {
        }
        org.telegram.messenger.PrizmaCloud.syncOwnBanner(org.telegram.messenger.UserConfig.selectedAccount);
    }
}
