package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.HapticFeedbackConstants;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaInputCalc;
import org.telegram.ui.ActionBar.Theme;

public class PrizmaCalcChip extends TextView {
    public interface Sink {
        void replace(String text);
    }

    private final Sink sink;
    private final Theme.ResourcesProvider resources;
    private String value;
    private boolean visible;

    public PrizmaCalcChip(Context context, Theme.ResourcesProvider resources, Sink sink) {
        super(context);
        this.sink = sink;
        this.resources = resources;
        setTextSize(14);
        setSingleLine(true);
        setEllipsize(TextUtils.TruncateAt.END);
        setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        setVisibility(GONE);
        setAlpha(0f);
        setOnClickListener(v -> {
            if (value == null) return;
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            sink.replace(value);
        });
    }

    public void update(CharSequence text) {
        PrizmaInputCalc.Result r = PrizmaConfig.inputCalc() ? PrizmaInputCalc.evaluate(text) : null;
        if (r == null) {
            hide();
            return;
        }
        value = r.value;
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resources);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resources);
        SpannableStringBuilder sb = new SpannableStringBuilder(r.label).append(' ').append(r.value);
        sb.setSpan(new ForegroundColorSpan(Theme.multAlpha(ink, 0.6f)), 0, r.label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(accent), r.label.length() + 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText(sb);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resources));
        bg.setCornerRadius(AndroidUtilities.dp(18));
        setBackground(bg);
        setElevation(AndroidUtilities.dpf2(3));
        show();
    }

    private void show() {
        if (visible) return;
        visible = true;
        animate().cancel();
        setVisibility(VISIBLE);
        setScaleX(0.9f);
        setScaleY(0.9f);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).withEndAction(null).start();
    }

    private void hide() {
        if (!visible) return;
        visible = false;
        value = null;
        animate().cancel();
        animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(140).withEndAction(() -> setVisibility(GONE)).start();
    }
}
