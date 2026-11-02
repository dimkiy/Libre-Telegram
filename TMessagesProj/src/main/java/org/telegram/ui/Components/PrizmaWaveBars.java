package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

/*
 * Libre — что это за файл:
 * Анимированные полоски, которые прыгают под музыку.
 */

/**
 * Prizma: animated bar visualizer that reacts to the currently playing track.
 * Fed by {@link PrizmaVisualizerData} with amplitude values [0..5].
 */
public class PrizmaWaveBars extends View implements PrizmaVisualizerData.Listener {

    private static final int BARS = 6;
    private final float[] target = new float[BARS];
    private final float[] cur = new float[BARS];
    private boolean playing;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private long lastTime;

    public PrizmaWaveBars(Context context) {
        super(context);
        paint.setColor(0xffffffff);
        // a cheap offset shadow instead of setShadowLayer: the shadow layer forced a SOFTWARE layer,
        // i.e. the view was re-rendered on the CPU every frame while music played
        shadowPaint.setColor(0x40000000);
    }

    public void setBarColor(int color) {
        paint.setColor(color);
    }

    @Override
    public void onWaveform(boolean playing, boolean animate, float[] values) {
        this.playing = playing;
        if (values != null) {
            for (int i = 0; i < BARS; i++) {
                float v = i < values.length ? values[i] : 0;
                if (v < 0) v = 0;
                if (v > 1) v = 1;
                target[i] = v;
            }
        } else {
            for (int i = 0; i < BARS; i++) {
                target[i] = 0;
            }
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PrizmaVisualizerData.setListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PrizmaVisualizerData.clearListener(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        float dt = lastTime == 0 ? 16 : Math.min(40, now - lastTime);
        lastTime = now;
        float k = Math.min(1f, dt / 120f * 3f);

        int w = getWidth();
        int h = getHeight();
        int gap = AndroidUtilities.dp(4);
        float barW = (w - gap * (BARS - 1)) / (float) BARS;
        float radius = barW / 2f;
        float minH = AndroidUtilities.dp(4);

        boolean need = false;
        for (int i = 0; i < BARS; i++) {
            float t = playing ? target[i] : 0;
            cur[i] += (t - cur[i]) * k;
            if (Math.abs(t - cur[i]) > 0.004f) {
                need = true;
            }
            float bh = minH + cur[i] * (h - minH);
            float left = i * (barW + gap);
            float top = (h - bh) / 2f;
            rect.set(left, top + AndroidUtilities.dp(1), left + barW, top + bh + AndroidUtilities.dp(1));
            canvas.drawRoundRect(rect, radius, radius, shadowPaint);
            rect.set(left, top, left + barW, top + bh);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        if (need || playing) {
            org.telegram.messenger.PrizmaConfig.nextDecorFrame(this);
        }
    }
}
