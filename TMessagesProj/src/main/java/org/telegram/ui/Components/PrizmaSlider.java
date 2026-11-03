package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaSlider extends View {
    public interface OnChange {
        void onChange(float value, boolean fromUser);
    }

    private OnChange onChange;
    private float value;
    private float display;
    private float thumbProg;
    private boolean pressed;

    private int accent = 0xff3f6bf5;
    private int trackColor = 0x33808080;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private long lastTime;

    public PrizmaSlider(Context context) {
        super(context);
    }

    public void setColors(int accent, int track) {
        this.accent = accent;
        this.trackColor = track;
        invalidate();
    }

    public void setOnChange(OnChange c) {
        onChange = c;
    }

    public void setValue(float v) {
        value = clamp(v);
        display = value;
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

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(heightMeasureSpec) : AndroidUtilities.dp(40);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float p = pad();
        float w = Math.max(1, getWidth() - p * 2);
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                apply(e.getX(), p, w);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                apply(e.getX(), p, w);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(false);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void apply(float x, float p, float w) {
        float v = clamp((x - p) / w);
        value = v;
        if (onChange != null) {
            onChange.onChange(v, true);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        float dt = lastTime == 0 ? 16 : Math.min(40, now - lastTime);
        lastTime = now;
        float kv = Math.min(1f, dt / 120f * 3f);
        float kt = Math.min(1f, dt / 90f * 3f);

        display += (value - display) * kv;
        float targetThumb = pressed ? 1f : 0f;
        thumbProg += (targetThumb - thumbProg) * kt;

        float p = pad();
        float w = getWidth() - p * 2;
        float cy = getHeight() / 2f;
        float trackH = AndroidUtilities.dp(4);
        float fx = p + w * display;

        paint.setColor(trackColor);
        rect.set(p, cy - trackH / 2, p + w, cy + trackH / 2);
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint);

        paint.setColor(accent);
        rect.set(p, cy - trackH / 2, fx, cy + trackH / 2);
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint);

        if (thumbProg > 0.01f) {
            paint.setColor((accent & 0x00ffffff) | (((int) (0x3a * thumbProg)) << 24));
            canvas.drawCircle(fx, cy, AndroidUtilities.dp(10) + AndroidUtilities.dp(9) * thumbProg, paint);
        }

        float rad = AndroidUtilities.dp(7) + AndroidUtilities.dp(3) * thumbProg;
        paint.setColor(accent);
        canvas.drawCircle(fx, cy, rad, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(fx, cy, rad * 0.42f, paint);

        if (Math.abs(value - display) > 0.002f || Math.abs(targetThumb - thumbProg) > 0.01f) {
            postInvalidateOnAnimation();
        }
    }
}
