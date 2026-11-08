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
        if (PrizmaConfig.playerRipple() && strength > 0.22f) {
            float cx = coverRect.isEmpty() ? getWidth() / 2f : coverRect.centerX();
            float cy = coverRect.isEmpty() ? getHeight() * 0.36f : coverRect.centerY();
            if ((beatCount++ & 1) == 1 && !coverRect.isEmpty()) {
                cx += (Utilities.random.nextFloat() - 0.5f) * coverRect.width() * 0.5f;
                cy += (Utilities.random.nextFloat() - 0.5f) * coverRect.height() * 0.5f;
            }
            spawnRipple(cx, cy, Math.min(1f, 0.35f + strength * 0.8f), coverRect.isEmpty() ? 0f : coverRect.width() * 0.42f);
        }
        if (PrizmaConfig.playerChroma() && strength > 0.62f) {
            chromaAt = System.currentTimeMillis();
            chromaPower = Math.min(1f, strength);
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

    private static final int WAVES = 6;
    private static final long WAVE_LIFE_MS = 1900;
    private final long[] rippleAt = new long[WAVES];
    private final float[] ripplePower = new float[WAVES];
    private final float[] rippleX = new float[WAVES];
    private final float[] rippleY = new float[WAVES];
    private final float[] rippleStart = new float[WAVES];
    private final float[] rippleReach = new float[WAVES];
    private final long[] rippleLife = new long[WAVES];
    private long chromaAt;
    private float chromaPower;
    private long dropAt;
    private float dropPrev;
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int edgeFor;
    private float edgeLevel;
    private int rippleNext;
    private int beatCount;
    private float swell;
    private Object water;
    private boolean waterBroken;
    private float chromaNow;
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final String WATER = ""
            + "uniform shader img;\n"
            + "uniform float2 res;\n"
            + "uniform float4 w0; uniform float4 w1; uniform float4 w2; uniform float4 w3; uniform float4 w4; uniform float4 w5;\n"
            + "uniform float band;\n"
            + "uniform float amp;\n"
            + "uniform float time;\n"
            + "uniform float swell;\n"
            + "uniform float chroma;\n"
            + "uniform float3 tint;\n"
            + "float3 wave(float2 p, float4 w) {\n"
            + "  if (w.w <= 0.001) return float3(0.0);\n"
            + "  float2 d = p - w.xy;\n"
            + "  float dist = length(d);\n"
            + "  float x = (dist - w.z) / band;\n"
            + "  if (x < -4.0 || x > 4.0) return float3(0.0);\n"
            + "  float env = exp(-x * x * 0.45);\n"
            + "  float2 dir = dist > 0.5 ? d / dist : float2(0.0);\n"
            + "  float s = sin(x * 2.4) * env * w.w;\n"
            + "  return float3(dir * s * amp, cos(x * 2.4) * env * w.w);\n"
            + "}\n"
            + "half4 main(float2 p) {\n"
            + "  float3 acc = wave(p, w0) + wave(p, w1) + wave(p, w2) + wave(p, w3) + wave(p, w4) + wave(p, w5);\n"
            + "  float2 off = acc.xy;\n"
            + "  float2 q = p / max(res.x, 1.0) * 6.2831;\n"
            + "  off += swell * float2(sin(q.y * 2.3 + time * 1.6) + 0.6 * sin(q.x * 3.1 - time * 1.1), cos(q.x * 2.1 + time * 1.3) + 0.6 * cos(q.y * 2.9 + time * 0.9));\n"
            + "  float2 lo = float2(0.5);\n"
            + "  float2 hi = res - float2(0.5);\n"
            + "  half4 c = img.eval(clamp(p - off, lo, hi));\n"
            + "  half4 r = img.eval(clamp(p - off * 1.22 + float2(chroma, 0.0), lo, hi));\n"
            + "  half4 b = img.eval(clamp(p - off * 0.78 - float2(chroma, 0.0), lo, hi));\n"
            + "  c.r = r.r;\n"
            + "  c.b = b.b;\n"
            + "  float l = clamp(acc.z, -1.0, 1.0);\n"
            + "  float crest = max(l, 0.0);\n"
            + "  float3 lit = float3(c.rgb) + (tint * 0.16 + float3(0.06)) * crest * float(c.a) - float3(0.07) * max(-l, 0.0) * float(c.a);\n"
            + "  return half4(half3(clamp(lit, 0.0, 1.0)), c.a);\n"
            + "}\n";

    private void spawnRipple(float x, float y, float power, float start) {
        spawnWave(x, y, power, start, 0f, WAVE_LIFE_MS);
    }

    private void spawnWave(float x, float y, float power, float start, float reach, long life) {
        rippleReach[rippleNext] = reach;
        rippleLife[rippleNext] = life;
        rippleAt[rippleNext] = System.currentTimeMillis();
        ripplePower[rippleNext] = power;
        rippleX[rippleNext] = x;
        rippleY[rippleNext] = y;
        rippleStart[rippleNext] = start;
        rippleNext = (rippleNext + 1) % WAVES;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && PrizmaConfig.playerRipple() && android.os.Build.VERSION.SDK_INT >= 33) {
            spawnRipple(event.getX(), event.getY(), 0.75f, 0f);
        }
        return false;
    }

    private long lifeOf(int i) {
        return rippleLife[i] > 0 ? rippleLife[i] : WAVE_LIFE_MS;
    }

    private void checkDrops(long now, int w, int h) {
        if (!PrizmaConfig.playerDrops() || !PrizmaConfig.beatEffects() || android.os.Build.VERSION.SDK_INT < 33) return;
        float hi = 0f;
        for (int b = PrizmaBeat.BANDS * 2 / 3; b < PrizmaBeat.BANDS; b++) hi = Math.max(hi, PrizmaBeat.band(b));
        if (hi > 0.35f && hi > dropPrev + 0.12f && now - dropAt > 140) {
            dropAt = now;
            float x = w * (0.08f + 0.84f * Utilities.random.nextFloat());
            float y = h * (0.06f + 0.88f * Utilities.random.nextFloat());
            if (!coverRect.isEmpty() && coverRect.contains(x, y)) y = coverRect.bottom + (h - coverRect.bottom) * Utilities.random.nextFloat();
            spawnWave(x, y, 0.28f + hi * 0.35f, 0f, AndroidUtilities.dpf2(120 + 80 * hi), 900);
        }
        dropPrev = dropPrev * 0.7f + hi * 0.3f;
    }

    private boolean drawEdges(Canvas canvas, int w, int h) {
        if (!PrizmaConfig.playerEdges() || !PrizmaConfig.beatEffects()) {
            edgeLevel = 0f;
            return false;
        }
        float target = PrizmaBeat.level();
        edgeLevel += (target - edgeLevel) * (target > edgeLevel ? 0.5f : 0.12f);
        if (edgeLevel < 0.01f) return target > 0.01f;
        int key = ringColor * 31 + w * 7 + h;
        if (edgeFor != key) {
            edgeFor = key;
            float radius = (float) Math.hypot(w, h) * 0.62f;
            edgePaint.setShader(new RadialGradient(w / 2f, h / 2f, radius,
                    new int[]{ringColor & 0x00ffffff, ringColor & 0x00ffffff, (ringColor & 0x00ffffff) | 0x99000000},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        }
        edgePaint.setAlpha((int) (255 * Math.min(1f, edgeLevel * 1.3f)));
        canvas.drawRect(0, 0, w, h, edgePaint);
        return true;
    }

    private float waveRadius(int i, long now, float reach) {
        if (rippleReach[i] > 0) reach = rippleReach[i];
        float t = Math.min(1f, (now - rippleAt[i]) / (float) lifeOf(i));
        float eased = 1f - (float) Math.pow(1f - t, 2.2f);
        return rippleStart[i] + (reach - rippleStart[i]) * eased;
    }

    @android.annotation.SuppressLint("NewApi")
    private boolean applyWater(long now, int w, int h) {
        if (android.os.Build.VERSION.SDK_INT < 33 || waterBroken) return false;
        checkDrops(now, w, h);
        boolean enabled = (PrizmaConfig.playerRipple() || PrizmaConfig.playerDrops() || PrizmaConfig.playerChroma()) && PrizmaConfig.beatEffects();
        float level = enabled && PrizmaConfig.playerRipple() ? PrizmaBeat.level() : 0f;
        float target = PrizmaConfig.fancyEffects() ? Math.min(1f, level * 1.4f) : 0f;
        swell += (target - swell) * 0.12f;
        boolean any = false;
        float[][] packs = new float[WAVES][];
        float reach = (float) Math.hypot(w, h);
        for (int i = 0; i < WAVES; i++) {
            long age = now - rippleAt[i];
            if (!enabled || rippleAt[i] == 0 || age > lifeOf(i)) {
                packs[i] = new float[]{0, 0, 0, 0};
                continue;
            }
            float t = age / (float) lifeOf(i);
            float fade = (float) Math.pow(1f - t, 1.6f) * Math.min(1f, t * 8f);
            packs[i] = new float[]{rippleX[i], rippleY[i], waveRadius(i, now, reach), ripplePower[i] * fade};
            any = true;
        }
        float chroma = 0f;
        if (enabled && chromaAt != 0 && now - chromaAt < 320) {
            float ct = (now - chromaAt) / 320f;
            chroma = AndroidUtilities.dpf2(7) * chromaPower * (1f - ct) * (1f - ct);
            any = true;
        }
        chromaNow = chroma;
        if (!any && swell < 0.01f && !(PrizmaConfig.playerDrops() && enabled && PrizmaBeat.level() > 0.01f)) {
            if (water != null) setRenderEffect(null);
            return false;
        }
        if (waterBroken) return false;
        try {
            if (water == null) water = new android.graphics.RuntimeShader(WATER);
            return pushWater(now, w, h, packs);
        } catch (Throwable e) {
            waterBroken = true;
            setRenderEffect(null);
            return false;
        }
    }

    @android.annotation.SuppressLint("NewApi")
    private boolean pushWater(long now, int w, int h, float[][] packs) {
        android.graphics.RuntimeShader sh = (android.graphics.RuntimeShader) water;
        sh.setFloatUniform("res", w, h);
        sh.setFloatUniform("w0", packs[0]);
        sh.setFloatUniform("w1", packs[1]);
        sh.setFloatUniform("w2", packs[2]);
        sh.setFloatUniform("w3", packs[3]);
        sh.setFloatUniform("w4", packs[4]);
        sh.setFloatUniform("w5", packs[5]);
        sh.setFloatUniform("band", AndroidUtilities.dpf2(30));
        sh.setFloatUniform("amp", AndroidUtilities.dpf2(18));
        sh.setFloatUniform("time", (now % 600000L) / 1000f);
        sh.setFloatUniform("swell", swell * AndroidUtilities.dpf2(3.5f));
        sh.setFloatUniform("chroma", chromaNow);
        sh.setFloatUniform("tint", ((ringColor >> 16) & 0xff) / 255f, ((ringColor >> 8) & 0xff) / 255f, (ringColor & 0xff) / 255f);
        setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "img"));
        return true;
    }

    private boolean drawRipples(Canvas canvas, long now, int w, int h) {
        float reach = (float) Math.hypot(w, h);
        ripplePaint.setStyle(Paint.Style.STROKE);
        boolean live = false;
        for (int i = 0; i < WAVES; i++) {
            long age = now - rippleAt[i];
            if (rippleAt[i] == 0 || age > WAVE_LIFE_MS) continue;
            float t = age / (float) WAVE_LIFE_MS;
            float r = waveRadius(i, now, reach);
            float fade = (1f - t) * (1f - t);
            ripplePaint.setColor(ringColor);
            ripplePaint.setStrokeWidth(AndroidUtilities.dpf2(1.2f + 3.5f * ripplePower[i] * (1f - t)));
            ripplePaint.setAlpha((int) (150 * fade * ripplePower[i]));
            canvas.drawCircle(rippleX[i], rippleY[i], r, ripplePaint);
            ripplePaint.setAlpha((int) (70 * fade * ripplePower[i]));
            canvas.drawCircle(rippleX[i], rippleY[i], r * 0.82f, ripplePaint);
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
        more |= drawEdges(canvas, w, h);
        if (applyWater(now, w, h)) {
            more = true;
        } else if (PrizmaConfig.playerRipple() && PrizmaConfig.beatEffects()) {
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
