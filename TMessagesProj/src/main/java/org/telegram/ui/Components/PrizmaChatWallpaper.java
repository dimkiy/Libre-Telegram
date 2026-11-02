package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Intent;
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

/*
 * Libre — что это за файл:
 * Своё фото или видео на обои чата.
 */

/**
 * Prizma: pick an own photo or video to use as the chat wallpaper.
 */
public class PrizmaChatWallpaper {

    public static final int REQUEST = 48211;

    public static void pick(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            activity.startActivityForResult(Intent.createChooser(intent, "Обои чата"), REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void handlePicked(Activity activity, Uri uri) {
        if (activity == null || uri == null) {
            return;
        }
        // copying a picked video (can be hundreds of MB) on the UI thread froze the app ("not responding")
        final android.content.ContentResolver resolver = activity.getContentResolver();
        final android.content.Context appContext = activity.getApplicationContext();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File tmp = new File(ApplicationLoader.getFilesDirFixed(), "prizma_chat_wp_tmp");
                InputStream in = resolver.openInputStream(uri);
                OutputStream os = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    os.write(buf, 0, r);
                }
                os.close();
                in.close();

                // determine if it is a video: trust mime, else probe (not a decodable image => video)
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

                // a new file name each time: open chats / the profile cache the drawable by path, so
                // re-using one name kept showing the previous video after picking a new one
                File dst = new File(ApplicationLoader.getFilesDirFixed(), (isVideo ? "prizma_chat_wp.mp4" : "prizma_chat_wp.jpg").replace(".", "_" + System.currentTimeMillis() + "."));
                if (!tmp.renameTo(dst)) {
                    dst = tmp; // fallback: use the temp file as-is
                }
                final String path = dst.getAbsolutePath();
                final boolean video = isVideo;
                AndroidUtilities.runOnUIThread(() -> {
                    String old = PrizmaConfig.chatWallpaperPath();
                    PrizmaConfig.setChatWallpaperPath(path);
                    PrizmaConfig.setChatWallpaperIsVideo(video);
                    PrizmaWallpaper.invalidateCustom();
                    if (old != null && old.length() > 0 && !old.equals(path)) {
                        new File(old).delete();
                    }
                    android.widget.Toast.makeText(appContext, "Обои чата установлены", android.widget.Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    public static boolean has() {
        String p = PrizmaConfig.chatWallpaperPath();
        return p != null && p.length() > 0;
    }

    public static void clear() {
        String p = PrizmaConfig.chatWallpaperPath();
        PrizmaConfig.setChatWallpaperPath("");
        PrizmaConfig.setChatWallpaperIsVideo(false);
        PrizmaWallpaper.invalidateCustom();
        try {
            if (p != null && p.length() > 0) {
                new File(p).delete();
            }
        } catch (Throwable ignore) {
        }
    }
}
