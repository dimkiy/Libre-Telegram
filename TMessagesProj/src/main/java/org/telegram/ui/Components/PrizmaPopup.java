package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

import java.util.ArrayList;

public class PrizmaPopup {
    private static final long HOLD_MS = 170;

    private static PrizmaPopup shown;

    public static boolean closeIfOpen() {
        if (shown != null && shown.isShowing()) {
            shown.dismiss();
            return true;
        }
        return false;
    }

    private final ViewGroup host;
    private final Context context;
    private final FrameLayout overlay;
    private final LinearLayout card;
    private final LinearLayout list;
    private final ArrayList<View> rows = new ArrayList<>();
    private final int textColor;
    private final int surface;
    private final Rect hit = new Rect();
    private final int[] location = new int[2];
    private View highlighted;
    private boolean closed;
    private Runnable onClosed;

    public PrizmaPopup(ViewGroup host, int surface, int textColor) {
        this.host = host;
        this.context = host.getContext();
        this.surface = surface;
        this.textColor = textColor;
        overlay = new FrameLayout(context);
        overlay.setOnClickListener(v -> dismiss());
        card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(surface);
        bg.setCornerRadius(AndroidUtilities.dp(22));
        card.setBackground(bg);
        card.setElevation(AndroidUtilities.dpf2(10));
        card.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        card.setClickable(true);
        list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.72f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(list);
        card.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    public PrizmaPopup onClosed(Runnable r) {
        onClosed = r;
        return this;
    }

    public PrizmaPopup item(int icon, CharSequence text, Runnable action) {
        return item(icon, text, textColor, false, action, true);
    }

    public PrizmaPopup danger(int icon, CharSequence text, Runnable action) {
        return item(icon, text, 0xffFF6B6B, false, action, true);
    }

    public PrizmaPopup more(int icon, CharSequence text, Runnable action) {
        return item(icon, text, textColor, true, action, false);
    }

    public PrizmaPopup check(int icon, CharSequence text, boolean checked, Runnable action) {
        return item(checked ? R.drawable.ic_ab_done : icon, text, textColor, false, action, true);
    }

    public PrizmaPopup divider() {
        View line = new View(context);
        line.setBackgroundColor(0x14ffffff);
        list.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 0, 6, 0, 6));
        return this;
    }

    public PrizmaPopup note(CharSequence label, CharSequence value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(9), AndroidUtilities.dp(18), AndroidUtilities.dp(9));
        TextView l = new TextView(context);
        l.setText(label);
        l.setTextColor((textColor & 0x00ffffff) | 0x99000000);
        l.setTextSize(14);
        row.addView(l, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        TextView v = new TextView(context);
        v.setText(value);
        v.setTextColor(textColor);
        v.setTextSize(14);
        v.setGravity(Gravity.RIGHT);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return this;
    }

    private PrizmaPopup item(int icon, CharSequence text, int color, boolean chevron, Runnable action, boolean closes) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(14), 0);
        ImageView iv = new ImageView(context);
        iv.setImageResource(icon);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iv, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(15.5f);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (chevron) {
            ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.msg_arrowright);
            arrow.setColorFilter((color & 0x00ffffff) | 0x80000000);
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(arrow, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }
        row.setOnClickListener(v -> {
            if (closes) dismiss();
            if (action != null) action.run();
        });
        rows.add(row);
        list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));
        return this;
    }

    public interface Filler {
        void fill(PrizmaPopup popup);
    }

    public void replaceWith(Filler filler) {
        list.removeAllViews();
        rows.clear();
        filler.fill(this);
        list.setAlpha(0f);
        list.setTranslationX(AndroidUtilities.dp(18));
        list.animate().alpha(1f).translationX(0f).setDuration(200).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    public void showAt(View anchor, boolean top) {
        anchor.getLocationInWindow(location);
        int ax = location[0], ay = location[1];
        host.getLocationInWindow(location);
        ax -= location[0];
        ay -= location[1];
        int width = AndroidUtilities.dp(250);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        boolean alignRight = ax + anchor.getWidth() / 2 > host.getWidth() / 2;
        lp.leftMargin = alignRight ? Math.max(AndroidUtilities.dp(8), ax + anchor.getWidth() - width) : Math.max(AndroidUtilities.dp(8), ax);
        overlay.addView(card, lp);
        host.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        shown = this;
        final int anchorTop = ay, anchorBottom = ay + anchor.getHeight();
        card.setAlpha(0f);
        card.setScaleX(0.82f);
        card.setScaleY(0.82f);
        card.post(() -> {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) card.getLayoutParams();
            p.topMargin = top ? anchorBottom + AndroidUtilities.dp(2) : Math.max(AndroidUtilities.statusBarHeight, anchorTop - card.getHeight() - AndroidUtilities.dp(6));
            card.setLayoutParams(p);
            card.setPivotX(alignRight ? card.getWidth() : 0);
            card.setPivotY(top ? 0 : card.getHeight());
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
        });
    }

    public void dismiss() {
        if (closed) return;
        closed = true;
        if (shown == this) shown = null;
        card.animate().cancel();
        card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(140).withEndAction(() -> {
            AndroidUtilities.removeFromParent(overlay);
            if (onClosed != null) onClosed.run();
        }).start();
    }

    public boolean isShowing() {
        return overlay.getParent() != null && !closed;
    }

    private void track(float rawX, float rawY) {
        View found = null;
        for (View row : rows) {
            row.getGlobalVisibleRect(hit);
            if (hit.contains((int) rawX, (int) rawY)) {
                found = row;
                break;
            }
        }
        if (found == highlighted) return;
        if (highlighted != null) highlighted.setBackground(null);
        highlighted = found;
        if (found != null) {
            GradientDrawable d = new GradientDrawable();
            d.setColor(0x1fffffff);
            d.setCornerRadius(AndroidUtilities.dp(14));
            found.setBackground(d);
            found.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void release() {
        View row = highlighted;
        highlighted = null;
        if (row != null) {
            row.setBackground(null);
            row.performClick();
        }
    }

    public interface Factory {
        PrizmaPopup create();
    }

    public static void attachHold(View anchor, Factory factory) {
        final PrizmaPopup[] open = new PrizmaPopup[1];
        final boolean[] held = new boolean[1];
        final Runnable[] arm = new Runnable[1];
        anchor.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    held[0] = false;
                    open[0] = null;
                    arm[0] = () -> {
                        held[0] = true;
                        open[0] = factory.create();
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    };
                    AndroidUtilities.runOnUIThread(arm[0], HOLD_MS);
                    v.setPressed(true);
                    if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (open[0] != null) open[0].track(e.getRawX(), e.getRawY());
                    return true;
                case MotionEvent.ACTION_UP:
                    AndroidUtilities.cancelRunOnUIThread(arm[0]);
                    v.setPressed(false);
                    if (open[0] != null) {
                        open[0].release();
                    } else if (!held[0]) {
                        factory.create();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    AndroidUtilities.cancelRunOnUIThread(arm[0]);
                    v.setPressed(false);
                    return true;
            }
            return false;
        });
    }
}
