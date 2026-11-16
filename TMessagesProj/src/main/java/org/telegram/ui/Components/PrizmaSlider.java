package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaSlider extends View {
    public interface OnChange {
        void onChange(float value, boolean fromUser);
    }

    public interface Label {
        String format(float value);
    }

    private OnChange onChange;
    private Label label;
    private boolean ticks;
    private float value;
    private boolean pressed;
    private int lastTick = -1;

    private int accent = 0xff3f6bf5;
    private int trackColor = 0x33808080;
    private int onAccent = 0xff111114;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path neck = new Path();

    private final PrizmaMotion.Value x = new PrizmaMotion.Value(0f, PrizmaMotion.FOLLOW);
    private final PrizmaMotion.Value dropX = new PrizmaMotion.Value(0f, new PrizmaMotion.Spec(260, 18));
    private final PrizmaMotion.Value dropUp = new PrizmaMotion.Value(0f, PrizmaMotion.BUBBLE);
    private final PrizmaMotion.Value press = new PrizmaMotion.Value(1f, PrizmaMotion.POP);
    private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();
    private boolean placed;

    public PrizmaSlider(Context context) {
        super(context);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(AndroidUtilities.bold());
        text.setTextSize(AndroidUtilities.dp(12));
    }

    public void setColors(int accent, int track) {
        this.accent = accent;
        this.trackColor = track;
        int r = (accent >> 16) & 0xff, g = (accent >> 8) & 0xff, b = accent & 0xff;
        onAccent = (r * 299 + g * 587 + b * 114) / 1000 > 150 ? 0xff111114 : 0xffffffff;
        invalidate();
    }

    public void setOnChange(OnChange c) {
        onChange = c;
    }

    public void setLabel(Label l) {
        label = l;
        requestLayout();
        invalidate();
    }

    public void setTicks(boolean on) {
        ticks = on;
        invalidate();
    }

    public void setValue(float v) {
        value = clamp(v);
        if (getWidth() > 0) {
            float target = toX(value);
            if (placed && !pressed) {
                x.tune(PrizmaMotion.GLIDE);
                x.t = target;
                clock.reset();
            } else {
                x.snap(target);
                dropX.snap(target);
            }
        }
        invalidate();
    }

    public float getValue() {
        return value;
    }

    private float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private float pad() {
        return AndroidUtilities.dp(12);
    }

    private float span() {
        return Math.max(1f, getWidth() - pad() * 2);
    }

    private float toX(float v) {
        return pad() + span() * v;
    }

    private float toV(float px) {
        return clamp((px - pad()) / span());
    }

    private float trackY() {
        return label != null ? getHeight() - AndroidUtilities.dp(20) : getHeight() / 2f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : AndroidUtilities.dp(label != null ? 72 : 40);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float target = toX(value);
        x.snap(target);
        dropX.snap(target);
        placed = w > 0;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                x.tune(PrizmaMotion.FOLLOW);
                press.t = 1.16f;
                dropUp.t = label != null ? 1f : 0f;
                apply(e.getX());
                clock.reset();
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(e.getX());
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                x.tune(PrizmaMotion.RELEASE);
                x.t = toX(value);
                press.t = 1f;
                dropUp.t = 0f;
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void apply(float px) {
        float min = pad(), max = pad() + span();
        x.t = PrizmaMotion.rubber(px, min, max, AndroidUtilities.dp(18));
        float v = toV(px);
        value = v;
        if (ticks) {
            int tick = Math.round(v * 20);
            if (tick != lastTick) {
                if (lastTick >= 0 && tick % 5 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                lastTick = tick;
            }
        }
        if (onChange != null) onChange.onChange(v, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        dropX.t = x.x;
        boolean moving = PrizmaMotion.run(clock.tick(), x, dropX, dropUp, press);

        float p = pad();
        float cy = trackY();
        float trackH = AndroidUtilities.dp(4);
        float fx = x.x;
        float shown = toV(fx);

        paint.setColor(trackColor);
        rect.set(p, cy - trackH / 2, p + span(), cy + trackH / 2);
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint);

        float rad = AndroidUtilities.dp(8);
        float fillEnd = Math.max(p, Math.min(p + span(), fx)) * PrizmaMotion.clamp((fx - p) / rad, 0f, 1f);
        if (fillEnd > p) {
            paint.setColor(accent);
            rect.set(p, cy - trackH / 2, fillEnd, cy + trackH / 2);
            canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint);
        }

        if (ticks) {
            for (int i = 0; i <= 20; i++) {
                float tx = p + span() * i / 20f;
                boolean major = i % 5 == 0;
                boolean lit = i / 20f <= shown && shown > 0;
                paint.setColor(lit ? accent : trackColor);
                float th = AndroidUtilities.dp(major ? 6 : 3);
                float top = cy + AndroidUtilities.dp(9);
                rect.set(tx - AndroidUtilities.dpf2(0.75f), top, tx + AndroidUtilities.dpf2(0.75f), top + th);
                canvas.drawRoundRect(rect, AndroidUtilities.dpf2(0.75f), AndroidUtilities.dpf2(0.75f), paint);
            }
        }

        float up = PrizmaMotion.clamp(dropUp.x, 0f, 1.2f);
        if (label != null && up > 0.02f) {
            float dr = AndroidUtilities.dp(16) * (0.5f + 0.5f * Math.min(1f, up));
            float dy = cy - AndroidUtilities.dp(36) * up;
            float dx = dropX.x;
            float tilt = PrizmaMotion.clamp((dropX.x - x.x) * 0.9f, -28f, 28f);
            float gap = Math.abs(cy - dy);
            if (gap < AndroidUtilities.dp(34)) {
                float k = 1f - gap / AndroidUtilities.dp(34);
                float half = Math.max(AndroidUtilities.dp(2), rad * 0.85f * k);
                neck.rewind();
                neck.moveTo(fx - half, cy);
                neck.quadTo((fx + dx) / 2f, (cy + dy) / 2f, dx - half * 0.8f, dy);
                neck.lineTo(dx + half * 0.8f, dy);
                neck.quadTo((fx + dx) / 2f, (cy + dy) / 2f, fx + half, cy);
                neck.close();
                paint.setColor(accent);
                canvas.drawPath(neck, paint);
            }
            canvas.save();
            canvas.rotate(tilt, dx, dy);
            paint.setColor(accent);
            canvas.drawCircle(dx, dy, dr, paint);
            canvas.restore();
            float alpha = PrizmaMotion.clamp((up - 0.7f) / 0.25f, 0f, 1f);
            if (alpha > 0f) {
                text.setColor(onAccent);
                text.setAlpha((int) (255 * alpha));
                canvas.drawText(label.format(value), dx, dy + AndroidUtilities.dp(4), text);
            }
        }

        float st = 1f + Math.min(Math.abs(x.v) / AndroidUtilities.dp(900), 0.4f);
        float s = press.x;
        if (pressed || Math.abs(s - 1f) > 0.01f) {
            paint.setColor((accent & 0x00ffffff) | (((int) (0x3a * PrizmaMotion.clamp((s - 1f) / 0.16f, 0f, 1f))) << 24));
            canvas.drawCircle(fx, cy, rad * 1.9f * s, paint);
        }
        paint.setColor(accent);
        rect.set(fx - rad * st * s, cy - rad / st * s, fx + rad * st * s, cy + rad / st * s);
        canvas.drawOval(rect, paint);
        paint.setColor(onAccent);
        canvas.drawCircle(fx, cy, rad * 0.36f * s, paint);

        if (moving || pressed) postInvalidateOnAnimation();
    }
}
