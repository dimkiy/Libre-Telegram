package org.telegram.ui.Components;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;

import org.telegram.messenger.PrizmaConfig;

public final class PrizmaStreamer {
    private PrizmaStreamer() {
    }

    public static CharSequence veil(CharSequence text) {
        if (!PrizmaConfig.streamer() || TextUtils.isEmpty(text)) return text;
        SpannableStringBuilder out = text instanceof SpannableStringBuilder ? (SpannableStringBuilder) text : new SpannableStringBuilder(text);
        TextStyleSpan.TextStyleRun run = new TextStyleSpan.TextStyleRun();
        run.flags |= TextStyleSpan.FLAG_STYLE_SPOILER;
        out.setSpan(new TextStyleSpan(run), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return out;
    }

    public static int avatarRadius(int full) {
        int percent = PrizmaConfig.avatarCorner();
        if (percent >= 100) return full;
        return Math.max(0, full * percent / 100);
    }
}
