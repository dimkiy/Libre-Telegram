package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

public class PrizmaGlassBar extends FrameLayout {
    private final PrizmaGlassFrame frame;
    private final TextView title;
    private final TextView subtitle;
    private final LinearLayout actions;
    private final int ink;

    public PrizmaGlassBar(Context context, PrizmaGlassFrame frame, CharSequence text, Runnable onBack) {
        super(context);
        this.frame = frame;
        ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        setClickable(true);

        ImageView back = new ImageView(context);
        back.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.BACK, 22).setColor(ink));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("Назад");
        back.setOnClickListener(v -> onBack.run());
        PrizmaFeedKit.press(back, 0.86f);
        addView(back, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        LinearLayout names = new LinearLayout(context);
        names.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setText(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(ink);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(title);
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setVisibility(GONE);
        names.addView(subtitle);
        addView(names, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 66, 0, 66, 0));

        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        addView(actions, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        frame.addView(this, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, height(), Gravity.TOP));
        frame.addEdgePane(this);
        frame.addPane(back, AndroidUtilities.dp(20), true);
    }

    public static int height() {
        return AndroidUtilities.statusBarHeight + AndroidUtilities.dp(56);
    }

    public static boolean lightStatusBar(int background) {
        return ColorUtils.calculateLuminance(background) > 0.7f;
    }

    public void setSubtitle(CharSequence text) {
        subtitle.setText(text);
        subtitle.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    public ImageView addAction(int icon, String description, Runnable onClick) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(icon);
        v.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(description);
        v.setOnClickListener(view -> onClick.run());
        PrizmaFeedKit.press(v, 0.86f);
        actions.addView(v, LayoutHelper.createLinear(40, 40, 8, 0, 0, 0));
        frame.addPane(v, AndroidUtilities.dp(20), true);
        return v;
    }

    public void follow(View scrolling) {
        frame.setSource(scrolling);
        scrolling.setOnScrollChangeListener((v, x, y, oldX, oldY) -> frame.invalidate());
    }
}
