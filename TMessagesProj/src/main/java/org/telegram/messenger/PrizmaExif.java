package org.telegram.messenger;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Prizma: strips location (GPS) and other identifying EXIF metadata from outgoing images
 * (camera model, software, timestamps, author, comments…) while keeping orientation so the
 * picture still displays the right way up. Works on a COPY — the user's original file is never
 * modified. Enabled by {@link PrizmaConfig#stripExif()}.
 *
 * Compressed photos are already re-encoded by Telegram (EXIF is lost), so this mainly protects
 * images sent "as a file". Video container metadata needs a remux and is not handled here.
 */
public final class PrizmaExif {

    private PrizmaExif() {}

    /** Returns a path to a metadata-stripped copy of the image, or the original path if nothing to do. */
    public static String stripIfEnabled(String path) {
        try {
            if (path == null || !PrizmaConfig.stripExif()) {
                return path;
            }
            String lower = path.toLowerCase();
            boolean jpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            if (!jpeg) {
                return path; // EXIF/GPS lives in JPEG; other formats rarely carry it
            }
            File src = new File(path);
            if (!src.exists() || src.length() == 0) {
                return path;
            }

            // unique sub-dir per strip so the copy can keep the ORIGINAL file name
            // (otherwise the recipient would see a random "nx_123.jpg" instead of the real name)
            File dir = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE),
                    "prizma_noexif/" + System.currentTimeMillis() + "_" + Math.abs(path.hashCode()));
            dir.mkdirs();
            String name = src.getName();
            if (name == null || name.isEmpty()) name = "image.jpg";
            File dst = new File(dir, name);
            copy(src, dst);

            ExifInterface exif = new ExifInterface(dst.getAbsolutePath());
            // keep TAG_ORIENTATION so the image isn't shown rotated
            for (String tag : TAGS_TO_CLEAR) {
                try {
                    if (exif.getAttribute(tag) != null) {
                        exif.setAttribute(tag, null);
                    }
                } catch (Throwable ignore) {
                }
            }
            exif.saveAttributes();
            return dst.getAbsolutePath();
        } catch (Throwable e) {
            return path; // on any failure, fall back to the original (never block sending)
        }
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
        }
        out.flush();
        out.close();
        in.close();
    }

    private static final String[] TAGS_TO_CLEAR = {
            // --- GPS / location ---
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
            ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES,
            ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_VERSION_ID,
            // --- identifying device / author / timestamps ---
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
            ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
    };
}
