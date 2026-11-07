package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.Utilities;

public class PrizmaPlayerBackdrop extends View implements PrizmaBeat.Listener {
    public static final int MODE_GRADIENT = 0;
    public static final int MODE_BLUR = 1;
    public static final int MODE_COVER = 2;
    public static final int MODE_DARK = 3;

    private static final int MAX_RINGS = 5;
    private static final int MAX_SPARKS = 44;
    private static final long RING_LIFE_MS = 900;

    public interface BeatSink {
        void onBeat(float strength);
    }

    public BeatSink beatSink;

    private PrizmaPalette palette = PrizmaPalette.fallback();
    private int top = palette.backgroundTop;
    private int bottom = palette.backgroundBottom;
    private int ringColor = palette.accent;
    private Bitmap blur, sharp;

    private final Paint fill = new Paint();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contour = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path ringPath = new android.graphics.Path();
    private final android.graphics.Path blobPath = new android.graphics.Path();
    private final Rect dst = new Rect();
    private final RectF coverRect = new RectF();
    private final RectF ringRect = new RectF();
    private int gradientFor;
    private int scrimFor;
    private int pulseColorFor;
    private float pulseRadiusFor;

    private float pulse;
    private long lastFrame;

    private final float[] ringPower = new float[MAX_RINGS];
    private final long[] ringAt = new long[MAX_RINGS];
    private int ringNext;

    private final float[] sparkX = new float[MAX_SPARKS];
    private final float[] sparkY = new float[MAX_SPARKS];
    private final float[] sparkVx = new float[MAX_SPARKS];
    private final float[] sparkVy = new float[MAX_SPARKS];
    private final float[] sparkLife = new float[MAX_SPARKS];
    private final float[] sparkSize = new float[MAX_SPARKS];
    private int sparkNext;
    private boolean sparksAlive;

    private float coverRadius;
    private boolean coverDominant = true;

    public PrizmaPlayerBackdrop(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(AndroidUtilities.dpf2(1.4f));
        spark.setStyle(Paint.Style.FILL);
        contour.setStrokeWidth(AndroidUtilities.dpf2(2.4f));
        contour.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setPalette(PrizmaPalette next, boolean animated) {
        final int fromTop = top, fromBottom = bottom, fromRing = ringColor;
        palette = next;
        if (!animated) {
            top = next.backgroundTop;
            bottom = next.backgroundBottom;
            ringColor = next.accent;
            gradientFor = 0;
            invalidate();
            return;
        }
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(520);
        va.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            top = PrizmaPalette.mix(fromTop, next.backgroundTop, t);
            bottom = PrizmaPalette.mix(fromBottom, next.backgroundBottom, t);
            ringColor = PrizmaPalette.mix(fromRing, next.accent, t);
            gradientFor = 0;
            pulseColorFor = 0;
            invalidate();
        });
        va.start();
    }

    public void setCoverBitmaps(Bitmap blurred, Bitmap original) {
        blur = blurred;
        sharp = original;
        invalidate();
    }

    public void setCoverRect(float l, float t, float r, float b, float radius, boolean dominant) {
        coverRect.set(l, t, r, b);
        coverRadius = radius;
        coverDominant = dominant;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PrizmaBeat.add(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PrizmaBeat.remove(this);
    }

    @Override
    public void onBeat(float strength) {
        if (beatSink != null) beatSink.onBeat(strength);
        if (!PrizmaConfig.beatEffects()) return;
        if (PrizmaConfig.playerRipple() && strength > 0.25f) {
            rippleAt[rippleNext] = System.currentTimeMillis();
            ripplePower[rippleNext] = strength;
            rippleNext = (rippleNext + 1) % rippleAt.length;
            invalidate();
        }
        boolean bg = PrizmaConfig.playerBgBeat();
        if (bg) pulse = Math.max(pulse, strength);
        if (coverDominant) {
            if (PrizmaConfig.playerRings()) {
                ringPower[ringNext] = strength;
                ringAt[ringNext] = System.currentTimeMillis();
                ringNext = (ringNext + 1) % MAX_RINGS;
            }
            if (PrizmaConfig.playerSparks()) {
                emitSparks(strength);
            }
        }
        invalidate();
    }

    private void emitSparks(float strength) {
        int n = 3 + (int) (strength * 6);
        for (int i = 0; i < n; i++) {
            int s = sparkNext;
            sparkNext = (sparkNext + 1) % MAX_SPARKS;
            int side = Utilities.random.nextInt(4);
            float u = Utilities.random.nextFloat();
            float x, y, nx, ny;
            switch (side) {
                case 0:
                    x = coverRect.left + coverRect.width() * u;
                    y = coverRect.top;
                    nx = (u - 0.5f) * 0.9f;
                    ny = -1f;
                    break;
                case 1:
                    x = coverRect.right;
                    y = coverRect.top + coverRect.height() * u;
                    nx = 1f;
                    ny = (u - 0.5f) * 0.9f;
                    break;
                case 2:
                    x = coverRect.left + coverRect.width() * u;
                    y = coverRect.bottom;
                    nx = (u - 0.5f) * 0.9f;
                    ny = 1f;
                    break;
                default:
                    x = coverRect.left;
                    y = coverRect.top + coverRect.height() * u;
                    nx = -1f;
                    ny = (u - 0.5f) * 0.9f;
                    break;
            }
            float speed = AndroidUtilities.dpf2(70 + Utilities.random.nextInt(120)) * (0.6f + strength * 0.7f);
            sparkX[s] = x;
            sparkY[s] = y;
            sparkVx[s] = nx * speed;
            sparkVy[s] = ny * speed;
            sparkLife[s] = 1f;
            sparkSize[s] = AndroidUtilities.dpf2(1f + Utilities.random.nextFloat() * 1.6f);
        }
        sparksAlive = true;
    }

    private void drawBase(Canvas canvas, int w, int h) {
        int mode = PrizmaConfig.playerBackdrop();
        if (mode == MODE_BLUR && blur != null && !blur.isRecycled()) {
            float s = 1f + 0.03f * pulse;
            int ex = (int) (w * (s - 1f) / 2f), ey = (int) (h * (s - 1f) / 2f);
            dst.set(-ex, -ey, w + ex, h + ey);
            canvas.drawBitmap(blur, null, dst, image);
            drawScrim(canvas, w, h);
        } else if (mode == MODE_COVER && sharp != null && !sharp.isRecycled()) {
            float scale = Math.max(w / (float) sharp.getWidth(), h / (float) sharp.getHeight()) * (1f + 0.02f * pulse);
            int dw = (int) (sharp.getWidth() * scale), dh = (int) (sharp.getHeight() * scale);
            dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
            canvas.drawBitmap(sharp, null, dst, image);
            drawScrim(canvas, w, h);
        } else if (mode == MODE_DARK) {
            canvas.drawColor(0xff101114);
        } else {
            int key = top * 31 + bottom + h;
            if (gradientFor != key) {
                gradientFor = key;
                fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{top, PrizmaPalette.mix(top, bottom, 0.55f), bottom},
                        new float[]{0f, 0.46f, 1f}, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, w, h, fill);
        }
    }

    private boolean drawBlob(Canvas canvas, long now) {
        float bass = PrizmaBeat.level();
        float cx = coverRect.centerX(), cy = coverRect.centerY();
        float base = coverRect.width() * 0.58f * (1f + 0.07f * bass);
        float wobble = coverRect.width() * 0.05f * (0.25f + bass);
        double t = now / 900.0;
        blobPath.rewind();
        int steps = 72;
        for (int i = 0; i <= steps; i++) {
            double a = 2 * Math.PI * i / steps;
            double r = base + wobble * (Math.sin(3 * a + t) + 0.6 * Math.sin(5 * a - t * 1.4) + 0.4 * Math.sin(2 * a + t * 0.7));
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            if (i == 0) blobPath.moveTo(x, y);
            else blobPath.lineTo(x, y);
        }
        blobPath.close();
        spark.setColor(ringColor);
        spark.setAlpha(46);
        canvas.drawPath(blobPath, spark);
        return bass > 0.01f;
    }

    private boolean drawContour(Canvas canvas) {
        float l = coverRect.left, t = coverRect.top, r = coverRect.right, b = coverRect.bottom;
        float w = r - l, h = b - t, per = 2 * (w + h);
        int bars = PrizmaBeat.BANDS * 3;
        float gapOut = AndroidUtilities.dpf2(7);
        float reach = AndroidUtilities.dpf2(26);
        boolean live = false;
        contour.setColor(ringColor);
        for (int i = 0; i < bars; i++) {
            int band = i % (PrizmaBeat.BANDS * 2);
            if (band >= PrizmaBeat.BANDS) band = PrizmaBeat.BANDS * 2 - 1 - band;
            float v = PrizmaBeat.band(band);
            if (v > 0.02f) live = true;
            float d = per * i / bars, x, y, nx = 0f, ny = 0f;
            if (d < w) {
                x = l + d;
                y = t;
                ny = -1f;
            } else if (d < w + h) {
                x = r;
                y = t + d - w;
                nx = 1f;
            } else if (d < 2 * w + h) {
                x = r - (d - w - h);
                y = b;
                ny = 1f;
            } else {
                x = l;
                y = b - (d - 2 * w - h);
                nx = -1f;
            }
            float side = nx != 0f ? Math.min(reach, l - gapOut - AndroidUtilities.dpf2(5)) : reach;
            float len = AndroidUtilities.dpf2(2) + v * v * side;
            contour.setAlpha((int) (90 + 150 * v));
            canvas.drawLine(x + nx * gapOut, y + ny * gapOut, x + nx * (gapOut + len), y + ny * (gapOut + len), contour);
        }
        return live;
    }

    private final long[] rippleAt = new long[5];
    private final float[] ripplePower = new float[5];
    private int rippleNext;
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean drawRipples(Canvas canvas, long now, int w, int h) {
        float cx = coverRect.isEmpty() ? w / 2f : coverRect.centerX();
        float cy = coverRect.isEmpty() ? h * 0.36f : coverRect.centerY();
        float reach = (float) Math.hypot(Math.max(cx, w - cx), Math.max(cy, h - cy));
        float start = coverRect.isEmpty() ? 0f : coverRect.width() * 0.5f;
        ripplePaint.setStyle(Paint.Style.STROKE);
        boolean live = false;
        for (int i = 0; i < rippleAt.length; i++) {
            long age = now - rippleAt[i];
            if (rippleAt[i] == 0 || age > 1500) continue;
            float t = age / 1500f;
            float eased = 1f - (1f - t) * (1f - t);
            float r = start + (reach - start) * eased;
            float fade = (1f - t) * (1f - t);
            ripplePaint.setColor(ringColor);
            ripplePaint.setStrokeWidth(AndroidUtilities.dpf2(1.2f + 3.5f * ripplePower[i] * (1f - t)));
            ripplePaint.setAlpha((int) (150 * fade * ripplePower[i]));
            canvas.drawCircle(cx, cy, r, ripplePaint);
            ripplePaint.setAlpha((int) (70 * fade * ripplePower[i]));
            canvas.drawCircle(cx, cy, r * 0.82f, ripplePaint);
            live = true;
        }
        return live;
    }

    private void drawScrim(Canvas canvas, int w, int h) {
        if (scrimFor != h) {
            scrimFor = h;
            scrim.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x66000000, 0x33000000, 0x88000000, 0xe6000000},
                    new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, scrim);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = System.currentTimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;

        drawBase(canvas, w, h);

        boolean more = false;
        if (coverDominant && !coverRect.isEmpty() && PrizmaConfig.beatEffects()) {
            if (PrizmaConfig.playerBlob()) more |= drawBlob(canvas, now);
            if (PrizmaConfig.playerContour()) more |= drawContour(canvas);
        }
        if (PrizmaConfig.playerRipple() && PrizmaConfig.beatEffects()) {
            more |= drawRipples(canvas, now, w, h);
        }
        if (pulse > 0.01f) {
            pulse *= (float) Math.pow(0.02f, dt);
            float cx = coverRect.isEmpty() ? w / 2f : coverRect.centerX();
            float cy = coverRect.isEmpty() ? h * 0.36f : coverRect.centerY();
            float radius = Math.max(w, h) * 0.62f;
            if (pulseColorFor != ringColor || pulseRadiusFor != radius) {
                pulseColorFor = ringColor;
                pulseRadiusFor = radius;
                pulsePaint.setShader(new RadialGradient(0, 0, radius, new int[]{(ringColor & 0x00ffffff) | 0x38000000, ringColor & 0x00ffffff},
                        new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            }
            pulsePaint.setAlpha((int) (255 * Math.min(1f, pulse)));
            canvas.save();
            canvas.translate(cx, cy);
            canvas.drawCircle(0, 0, radius, pulsePaint);
            canvas.restore();
            more = true;
        }

        for (int i = 0; i < MAX_RINGS; i++) {
            long age = now - ringAt[i];
            if (ringAt[i] == 0 || age > RING_LIFE_MS) continue;
            float t = age / (float) RING_LIFE_MS;
            float eased = 1f - (1f - t) * (1f - t) * (1f - t);
            float grow = AndroidUtilities.dpf2(6) + eased * AndroidUtilities.dpf2(46 + 40 * ringPower[i]);
            ringRect.set(coverRect.left - grow, coverRect.top - grow, coverRect.right + grow, coverRect.bottom + grow);
            float a = (1f - t) * (1f - t) * (0.3f + 0.5f * ringPower[i]);
            ring.setColor(ringColor);
            ring.setAlpha((int) (255 * Math.min(1f, a)));
            float r = coverRadius + grow;
            PrizmaShape.leaf(ringPath, ringRect, r, r * PrizmaShape.SMALL, 0f);
            canvas.drawPath(ringPath, ring);
            more = true;
        }

        if (sparksAlive) {
            boolean any = false;
            spark.setColor(ringColor);
            for (int i = 0; i < MAX_SPARKS; i++) {
                if (sparkLife[i] <= 0f) continue;
                sparkLife[i] -= dt * 1.15f;
                if (sparkLife[i] <= 0f) continue;
                sparkX[i] += sparkVx[i] * dt;
                sparkY[i] += sparkVy[i] * dt;
                sparkVx[i] *= 0.985f;
                sparkVy[i] *= 0.985f;
                spark.setAlpha((int) (200 * sparkLife[i]));
                canvas.drawCircle(sparkX[i], sparkY[i], sparkSize[i], spark);
                any = true;
            }
            sparksAlive = any;
            more |= any;
        }

        if (more) {
            PrizmaConfig.nextDecorFrame(this);
        }
    }
}
