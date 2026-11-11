package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.Locale;

public class PrizmaZoomSlider extends View {
    private static final int TICKS = 24;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stopText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float slop;

    private float min = 1f, max = 2f, value = 1f;
    private float[] stops = {1f, 1.5f, 2f};
    private Utilities.Callback<Float> listener;
    private ValueAnimator glide;
    private ValueAnimator fade;
    private float downX, downAt, ruler;
    private boolean moved;
    private int lastStop = -1;

    public PrizmaZoomSlider(Context context) {
        super(context);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        track.setColor(0x80000000);
        tick.setColor(0xffffffff);
        tick.setStrokeWidth(AndroidUtilities.dpf2(1.3f));
        tick.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(0xffffffff);
        stopText.setColor(0xd9ffffff);
        stopText.setTextSize(AndroidUtilities.dp(12));
        stopText.setTypeface(AndroidUtilities.bold());
        stopText.setTextAlign(Paint.Align.CENTER);
        knobText.setColor(0xff111114);
        knobText.setTextSize(AndroidUtilities.dp(11.5f));
        knobText.setTypeface(AndroidUtilities.bold());
        knobText.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Приближение");
    }

    public void setRange(float min, float max, float value) {
        this.min = Math.max(0.1f, min);
        this.max = Math.max(this.min + 0.05f, max);
        this.value = Math.max(this.min, Math.min(this.max, value));
        float mid = Math.round((float) Math.sqrt(this.min * this.max) * 2f) / 2f;
        if (mid <= this.min || mid >= this.max) mid = (this.min + this.max) / 2f;
        stops = new float[]{this.min, mid, this.max};
        invalidate();
    }

    public void setValue(float v) {
        value = Math.max(min, Math.min(max, v));
        invalidate();
    }

    public void setListener(Utilities.Callback<Float> listener) {
        this.listener = listener;
    }

    private float pad() {
        return AndroidUtilities.dpf2(26);
    }

    private float position(float v) {
        return (float) (Math.log(v / min) / Math.log(max / min));
    }

    private float valueAt(float t) {
        return (float) (min * Math.pow(max / min, Math.max(0f, Math.min(1f, t))));
    }

    private float xOf(float t) {
        return pad() + t * (getWidth() - 2 * pad());
    }

    private void push(float v, boolean fromUser) {
        value = Math.max(min, Math.min(max, v));
        if (fromUser) {
            int stop = -1;
            for (int i = 0; i < stops.length; i++) {
                if (Math.abs(position(stops[i]) - position(value)) < 0.012f) stop = i;
            }
            if (stop != lastStop) {
                lastStop = stop;
                if (stop >= 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        if (listener != null) listener.run(value);
        invalidate();
    }

    private void showRuler(boolean show) {
        if (fade != null) fade.cancel();
        fade = ValueAnimator.ofFloat(ruler, show ? 1f : 0f);
        fade.setDuration(show ? 140 : 320);
        fade.setStartDelay(show ? 0 : 500);
        fade.addUpdateListener(a -> {
            ruler = (float) a.getAnimatedValue();
            invalidate();
        });
        fade.start();
    }

    private void glideTo(float target) {
        if (glide != null) glide.cancel();
        final float from = position(value), to = position(target);
        glide = ValueAnimator.ofFloat(0f, 1f);
        glide.setDuration(220);
        glide.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        glide.addUpdateListener(a -> push(valueAt(from + (to - from) * (float) a.getAnimatedValue()), false));
        glide.start();
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (glide != null) glide.cancel();
                downX = e.getX();
                downAt = position(value);
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!moved && Math.abs(dx) > slop) {
                    moved = true;
                    showRuler(true);
                }
                if (moved) push(valueAt(downAt + dx / Math.max(1f, getWidth() - 2 * pad())), true);
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved) {
                    float t = (e.getX() - pad()) / Math.max(1f, getWidth() - 2 * pad());
                    float best = stops[0];
                    for (float s : stops) {
                        if (Math.abs(position(s) - t) < Math.abs(position(best) - t)) best = s;
                    }
                    glideTo(best);
                }
                showRuler(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                showRuler(false);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static String label(float v) {
        float rounded = Math.round(v * 10f) / 10f;
        if (Math.abs(rounded - Math.round(rounded)) < 0.05f) return Math.round(rounded) + "×";
        return String.format(Locale.ROOT, "%.1f×", rounded).replace('.', ',');
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = AndroidUtilities.dpf2(40);
        float cy = getHeight() / 2f;
        rect.set(0, cy - h / 2f, getWidth(), cy + h / 2f);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, track);

        float at = position(value);
        float knobX = xOf(at);
        float knobR = AndroidUtilities.dpf2(16);

        if (ruler > 0.01f) {
            for (int i = 0; i <= TICKS; i++) {
                float x = xOf(i / (float) TICKS);
                if (Math.abs(x - knobX) < knobR + AndroidUtilities.dpf2(3)) continue;
                boolean major = i % (TICKS / 2) == 0;
                float len = AndroidUtilities.dpf2(major ? 7 : 4);
                tick.setAlpha((int) (255 * ruler * (major ? 0.9f : 0.45f)));
                canvas.drawLine(x, cy - len, x, cy + len, tick);
            }
        }
        float textY = cy - (stopText.descent() + stopText.ascent()) / 2f;
        stopText.setAlpha((int) (217 * (1f - ruler)));
        for (float s : stops) {
            float x = xOf(position(s));
            if (Math.abs(x - knobX) < knobR + AndroidUtilities.dpf2(8)) continue;
            canvas.drawText(label(s), x, textY, stopText);
        }
        canvas.drawCircle(knobX, cy, knobR, knob);
        canvas.drawText(label(value), knobX, cy - (knobText.descent() + knobText.ascent()) / 2f, knobText);
    }
}
