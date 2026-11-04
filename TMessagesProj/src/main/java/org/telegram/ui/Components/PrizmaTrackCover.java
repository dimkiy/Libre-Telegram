package org.telegram.ui.Components;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;

public final class PrizmaTrackCover {
    private static final int[][] TONES = {
            {0xff2f3a52, 0xff1b2130}, {0xff3d2f4f, 0xff221a2e}, {0xff2c4440, 0xff182624}, {0xff4a3a2a, 0xff2a2018}
    };

    private PrizmaTrackCover() {
    }

    public static ImageLocation thumb(MessageObject mo, int size) {
        TLRPC.Document doc = mo.getDocument();
        TLRPC.PhotoSize t = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, size) : null;
        if (!(t instanceof TLRPC.TL_photoSize) && !(t instanceof TLRPC.TL_photoSizeProgressive)) {
            return null;
        }
        return ImageLocation.getForDocument(t, doc);
    }

    public static Drawable placeholder(MessageObject mo, int radius) {
        String key = mo == null || mo.getMusicAuthor() == null ? "" : mo.getMusicAuthor();
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, TONES[Math.abs(key.hashCode() % TONES.length)]);
        d.setCornerRadius(radius);
        return d;
    }

    public static void bindSmall(BackupImageView view, MessageObject mo) {
        if (view == null || mo == null) return;
        try {
            String artwork = mo.getArtworkUrl(true);
            ImageLocation thumb = thumb(mo, 120);
            Drawable ph = placeholder(mo, AndroidUtilities.dp(10));
            if (artwork != null && !artwork.isEmpty()) {
                view.setImage(ImageLocation.getForPath(artwork), "90_90", thumb, null, ph, null, 0, 1, mo);
            } else if (thumb != null) {
                view.setImage(null, null, thumb, null, ph, null, 0, 1, mo);
            } else {
                view.setImageDrawable(ph);
            }
        } catch (Throwable ignore) {
        }
    }
}
