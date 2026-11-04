package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.PrizmaConfig;

public class PrizmaSeekBars extends View implements PrizmaVisualizerData.Listener, PrizmaBeat.Listener {
    public interface Listener {
        void onSeek(float progress, boolean committed);
    }

    private static final int MAX_SHOCKS = 3;
    private static final long SHOCK_LIFE_MS = 620;

    public Listener listener;

    private float value;
    private float display;
    private boolean dragging;
    private float touchX = -1f;
    private float lens;
    private final PrizmaSpring lensSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.72f, v -> {
        lens = v;
        invalidate();
    });

    private float amp, ampTarget;
    private long lastTime;
    private int accent = PrizmaPalette.fallback().accent;
    private int onAccent = PrizmaPalette.fallback().onAccent;

    private float[] peaks;
    private float peaksMorph = 1f;
    private final PrizmaSpring morphSpring = new PrizmaSpring(1f, PrizmaSpring.SOFT, 1f, v -> {
        peaksMorph = v;
        invalidate();
    });

    private final float[] shockX = new float[MAX_SHOCKS];
    private final float[] shockPower = new float[MAX_SHOCKS];
    private final long[] shockAt = new long[MAX_SHOCKS];
    private int shockNext;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bubbleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF rectHalo = new RectF();
    private final float[] hsv = new float[3];
    private int lastTickBucket = -1;
    private int durationSec;

    public PrizmaSeekBars(Context context) {
        super(context);
        bubbleText.setTextSize(AndroidUtilities.dp(13));
        bubbleText.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        bubbleText.setTextAlign(Paint.Align.CENTER);
    }

    public void setColors(int accent, int onAccent) {
        this.accent = accent;
        this.onAccent = onAccent;
        invalidate();
    }

    public void setDuration(int seconds) {
        durationSec = seconds;
    }

    public void setValue(float v) {
        if (!dragging) {
            value = clamp(v);
            invalidate();
        }
    }

    public boolean isDragging() {
        return dragging;
    }

    public float getValue() {
        return value;
    }

    public void setPeaks(float[] next, boolean animated) {
        peaks = next;
        if (next != null && animated) {
            morphSpring.snap(0f);
            morphSpring.to(1f);
        } else {
            morphSpring.snap(1f);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PrizmaVisualizerData.setListener(this);
        PrizmaBeat.add(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PrizmaVisualizerData.clearListener(this);
        PrizmaBeat.remove(this);
    }

    @Override
    public void onWaveform(boolean playing, boolean animate, float[] values) {
        float m = 0;
        if (values != null) {
            for (float v : values) m = Math.max(m, v);
        }
        ampTarget = clamp(m);
        postInvalidateOnAnimation();
    }

    @Override
    public void onBeat(float strength) {
        if (dragging || !PrizmaConfig.beatEffects()) return;
        shockX[shockNext] = getWidth() * display;
        shockPower[shockNext] = strength;
        shockAt[shockNext] = System.currentTimeMillis();
        shockNext = (shockNext + 1) % MAX_SHOCKS;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float w = Math.max(1, getWidth());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                touchX = e.getX();
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                lensSpring.to(1f);
                moveTo(e.getX() / w);
                return true;
            case MotionEvent.ACTION_MOVE:
                touchX = e.getX();
                moveTo(e.getX() / w);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                lastTickBucket = -1;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                lensSpring.to(0f);
                if (listener != null) listener.onSeek(value, true);
                invalidate();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void moveTo(float progress) {
        value = clamp(progress);
        int bucket = (int) (value * 20);
        if (bucket != lastTickBucket) {
            if (lastTickBucket >= 0) {
                try {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } catch (Throwable ignore) {
                }
            }
            lastTickBucket = bucket;
        }
        if (listener != null) listener.onSeek(value, false);
        invalidate();
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static int blend(int a, int b, float t) {
        return PrizmaPalette.mix(a, b, t);
    }

    private static float pseudo(int i) {
        float s = (float) (0.55 + 0.45 * Math.sin(i * 0.9) * Math.cos(i * 0.37 + 1.3));
        return Math.max(0.18f, Math.min(1f, Math.abs(s)));
    }

    private float shapeAt(int i, int count) {
        float base = pseudo(i);
        float[] p = peaks;
        if (p == null || p.length == 0) return base;
        float pos = (i + 0.5f) / count * (p.length - 1);
        int left = (int) pos;
        int right = Math.min(p.length - 1, left + 1);
        float real = p[left] + (p[right] - p[left]) * (pos - left);
        real = Math.max(0.12f, real);
        return base + (real - base) * peaksMorph;
    }

    private static String time(int seconds) {
        int m = seconds / 60, s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : String.valueOf(s));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = System.currentTimeMillis();
        float dt = lastTime == 0 ? 16 : Math.min(48, now - lastTime);
        lastTime = now;
        display += (value - display) * Math.min(1f, dt / 40f);
        boolean playing;
        try {
            playing = MediaController.getInstance().getPlayingMessageObject() != null && !MediaController.getInstance().isMessagePaused();
        } catch (Throwable e) {
            playing = false;
        }
        boolean fancy = PrizmaConfig.beatEffects();
        float target = playing ? Math.max(0.18f, 0.3f * ampTarget + 0.7f * PrizmaBeat.level()) : 0.05f;
        amp += (target - amp) * Math.min(1f, dt / 30f);

        int w = getWidth(), h = getHeight();
        float cy = h / 2f;
        float barW = AndroidUtilities.dpf2(3f);
        float gap = AndroidUtilities.dpf2(3.5f);
        float step = barW + gap;
        int count = (int) (w / step);
        if (count <= 0) return;
        float maxH = h - AndroidUtilities.dpf2(6);
        float minH = AndroidUtilities.dpf2(3);
        float fx = w * display;
        float rad = barW / 2f;
        int style = PrizmaConfig.seekStyle();
        float sigma = AndroidUtilities.dpf2(16);
        float lensSigma = AndroidUtilities.dpf2(46);
        boolean shocksAlive = false;
        float speed = w * 1.05f;

        for (int i = 0; i < count; i++) {
            float x = i * step + gap / 2f;
            float cx = x + barW / 2f;
            float wiggle = playing && fancy ? (float) (0.1 * Math.sin(now / 190.0 + i * 0.6)) : 0f;
            float level = shapeAt(i, count) * (0.34f + 0.66f * amp) + wiggle;
            level = Math.max(0.08f, Math.min(1f, level));
            float extra = 0f;
            for (int s = 0; s < MAX_SHOCKS; s++) {
                long age = now - shockAt[s];
                if (shockAt[s] == 0 || age > SHOCK_LIFE_MS) continue;
                shocksAlive = true;
                float t = age / (float) SHOCK_LIFE_MS;
                float travel = speed * (age / 1000f);
                float d = Math.abs(Math.abs(cx - shockX[s]) - travel);
                extra += shockPower[s] * (1f - t) * (1f - t) * (float) Math.exp(-(d * d) / (2 * sigma * sigma));
            }
            if (lens > 0.01f && touchX >= 0) {
                float d = cx - touchX;
                extra += 0.55f * lens * (float) Math.exp(-(d * d) / (2 * lensSigma * lensSigma));
            }
            float bh = Math.min(h, minH + level * (maxH - minH) + extra * maxH * 0.42f);
            boolean played = cx <= fx;
            rect.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
            if (!played) {
                paint.setColor(0x40ffffff);
                canvas.drawRoundRect(rect, rad, rad, paint);
                continue;
            }
            int color;
            if (style == 1) {
                float heat = (float) (0.5 + 0.5 * Math.sin(now / 200.0 + i * 0.45));
                color = blend(0xffFFC24B, 0xffFF2D2D, heat);
                halo.setColor((color & 0x00ffffff) | 0x55000000);
                float gp = AndroidUtilities.dpf2(2.6f);
                rectHalo.set(x - gp, cy - bh / 2f - gp, x + barW + gp, cy + bh / 2f + gp);
                canvas.drawRoundRect(rectHalo, rad + gp, rad + gp, halo);
            } else if (style == 2) {
                hsv[0] = (float) (((i * 9) + now * 0.08) % 360.0);
                hsv[1] = 0.82f;
                hsv[2] = 1f;
                color = Color.HSVToColor(hsv);
            } else {
                color = accent;
            }
            paint.setColor(color);
            canvas.drawRoundRect(rect, rad, rad, paint);
        }

        if (lens > 0.02f && durationSec > 0 && touchX >= 0) {
            String label = time((int) (durationSec * value));
            float tw = bubbleText.measureText(label);
            float bw = tw + AndroidUtilities.dpf2(20);
            float bh = AndroidUtilities.dpf2(28);
            float bx = Math.max(bw / 2f, Math.min(w - bw / 2f, touchX));
            float top = -bh - AndroidUtilities.dpf2(6) + (1f - lens) * AndroidUtilities.dpf2(10);
            bubble.setColor(accent);
            bubble.setAlpha((int) (255 * clamp(lens)));
            rect.set(bx - bw / 2f, top, bx + bw / 2f, top + bh);
            canvas.drawRoundRect(rect, bh / 2f, bh / 2f, bubble);
            bubbleText.setColor(onAccent);
            bubbleText.setAlpha((int) (255 * clamp(lens)));
            canvas.drawText(label, bx, top + bh / 2f + AndroidUtilities.dpf2(4.5f), bubbleText);
        }

        if (playing || shocksAlive || Math.abs(value - display) > 0.001f || Math.abs(target - amp) > 0.01f) {
            PrizmaConfig.nextDecorFrame(this);
        }
    }
}
