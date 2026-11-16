package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.ui.ActionBar.Theme;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Random;

public class PrizmaParticles extends View {
    public static final int OFF = 0, AUTO = 1, SNOW = 2, LEAVES = 3, PETALS = 4, FIREFLIES = 5;
    public static final String[] NAMES = {"Выключены", "По времени года", "Снег", "Листья", "Лепестки", "Светлячки"};
    public static final String[] DENSITY = {"Мало", "Средне", "Много"};

    private static final int[] LEAF_COLORS = {0xffd9822b, 0xffc4512b, 0xffe0b13a, 0xff9c5a2a, 0xffb83a2a, 0xffcf9a3c};
    private static final int[] PETAL_COLORS = {0xffffc1d6, 0xfff7a1c0, 0xffffe0ea, 0xfff9b4cb};
    private static final int[] FLY_COLORS = {0xffe8f27a, 0xfff5dc6b, 0xffc9ef7f};

    private static WeakReference<PrizmaParticles> active;

    private static final class P {
        float x, y, size, depth, speed, sway, swayRate, phase, rot, spin, flutter, alpha, vx, vy;
        int color;
    }

    private final Random random = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path leaf = new Path();
    private final Path petal = new Path();
    private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();
    private P[] items = new P[0];
    private int kind = -1;
    private int density = -1;
    private int configVersion = -1;
    private boolean dark;
    private boolean running;
    private float windX, windY;
    private float time;

    public PrizmaParticles(Context context) {
        super(context);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        paint.setStrokeCap(Paint.Cap.ROUND);
        buildShapes();
        refresh();
    }

    public static int resolve(int mode) {
        if (mode != AUTO) return mode;
        int month = Calendar.getInstance().get(Calendar.MONTH);
        if (month == Calendar.DECEMBER || month <= Calendar.FEBRUARY) return SNOW;
        if (month <= Calendar.MAY) return PETALS;
        if (month <= Calendar.AUGUST) return FIREFLIES;
        return LEAVES;
    }

    public static void push(float dx, float dy) {
        PrizmaParticles p = active == null ? null : active.get();
        if (p == null || !p.running) return;
        p.windX = PrizmaMotion.clamp(p.windX - dx * 2.2f, -AndroidUtilities.dp(220), AndroidUtilities.dp(220));
        p.windY = PrizmaMotion.clamp(p.windY - dy * 2.2f, -AndroidUtilities.dp(260), AndroidUtilities.dp(260));
    }

    public static void changed() {
        PrizmaParticles p = active == null ? null : active.get();
        if (p != null) p.refresh();
    }

    private void buildShapes() {
        leaf.rewind();
        leaf.moveTo(0f, -1f);
        leaf.cubicTo(0.62f, -0.62f, 0.62f, 0.42f, 0f, 1f);
        leaf.cubicTo(-0.62f, 0.42f, -0.62f, -0.62f, 0f, -1f);
        leaf.close();
        petal.rewind();
        petal.moveTo(0f, 1f);
        petal.cubicTo(0.85f, 0.45f, 0.75f, -0.75f, 0.22f, -0.95f);
        petal.lineTo(0f, -0.7f);
        petal.lineTo(-0.22f, -0.95f);
        petal.cubicTo(-0.75f, -0.75f, -0.85f, 0.45f, 0f, 1f);
        petal.close();
    }

    private boolean allowed() {
        try {
            if (LiteMode.isPowerSaverApplied()) return false;
        } catch (Throwable ignore) {
        }
        return !PrizmaMotion.calm();
    }

    public void refresh() {
        configVersion = PrizmaConfig.version();
        int mode = PrizmaConfig.particles();
        int k = allowed() ? resolve(mode) : OFF;
        int d = Math.max(0, Math.min(2, PrizmaConfig.particlesDensity()));
        boolean isDark = Theme.isCurrentThemeDark();
        setVisibility(k == OFF ? GONE : VISIBLE);
        if (k == kind && d == density && isDark == dark) return;
        kind = k;
        density = d;
        dark = isDark;
        items = new P[0];
        if (getWidth() > 0) seed(getWidth(), getHeight());
        invalidate();
    }

    private int count() {
        int[] n;
        switch (kind) {
            case SNOW: n = new int[]{26, 48, 84}; break;
            case LEAVES: n = new int[]{7, 13, 22}; break;
            case PETALS: n = new int[]{11, 20, 34}; break;
            case FIREFLIES: n = new int[]{9, 16, 28}; break;
            default: return 0;
        }
        return n[density];
    }

    private void seed(int w, int h) {
        int n = count();
        items = new P[n];
        for (int i = 0; i < n; i++) {
            P p = new P();
            spawn(p, w, h, true);
            items[i] = p;
        }
    }

    private float rnd(float a, float b) {
        return a + random.nextFloat() * (b - a);
    }

    private void spawn(P p, int w, int h, boolean anywhere) {
        p.depth = rnd(0.35f, 1f);
        p.phase = rnd(0f, (float) (Math.PI * 2));
        p.rot = rnd(0f, 360f);
        p.vx = 0;
        p.vy = 0;
        float dp = AndroidUtilities.density;
        switch (kind) {
            case SNOW:
                p.size = rnd(1.1f, 3.4f) * dp * (0.6f + 0.6f * p.depth);
                p.speed = rnd(16f, 46f) * dp * (0.5f + 0.7f * p.depth);
                p.sway = rnd(6f, 22f) * dp;
                p.swayRate = rnd(0.3f, 0.8f);
                p.alpha = rnd(0.35f, 0.9f) * (0.55f + 0.45f * p.depth);
                p.spin = rnd(-30f, 30f);
                p.color = dark ? 0xffffffff : 0xffaebdd0;
                break;
            case LEAVES:
                p.size = rnd(7f, 12f) * dp * (0.7f + 0.5f * p.depth);
                p.speed = rnd(28f, 62f) * dp * (0.6f + 0.6f * p.depth);
                p.sway = rnd(18f, 46f) * dp;
                p.swayRate = rnd(0.25f, 0.55f);
                p.alpha = rnd(0.7f, 0.95f) * (0.6f + 0.4f * p.depth);
                p.spin = rnd(-90f, 90f);
                p.flutter = rnd(0.8f, 1.8f);
                p.color = LEAF_COLORS[random.nextInt(LEAF_COLORS.length)];
                break;
            case PETALS:
                p.size = rnd(5f, 8.5f) * dp * (0.7f + 0.5f * p.depth);
                p.speed = rnd(20f, 44f) * dp * (0.6f + 0.6f * p.depth);
                p.sway = rnd(16f, 40f) * dp;
                p.swayRate = rnd(0.3f, 0.7f);
                p.alpha = rnd(0.65f, 0.95f) * (0.6f + 0.4f * p.depth);
                p.spin = rnd(-120f, 120f);
                p.flutter = rnd(1.2f, 2.4f);
                p.color = PETAL_COLORS[random.nextInt(PETAL_COLORS.length)];
                break;
            case FIREFLIES:
                p.size = rnd(1.4f, 2.8f) * dp;
                p.speed = rnd(-12f, -4f) * dp;
                p.sway = rnd(14f, 34f) * dp;
                p.swayRate = rnd(0.12f, 0.3f);
                p.alpha = 0f;
                p.flutter = rnd(0.4f, 1.1f);
                p.color = FLY_COLORS[random.nextInt(FLY_COLORS.length)];
                break;
        }
        p.x = rnd(0f, w);
        if (anywhere) {
            p.y = rnd(0f, h);
        } else if (kind == FIREFLIES) {
            p.y = h + p.size * 4;
        } else {
            p.y = -p.size * 3 - rnd(0f, AndroidUtilities.dp(40));
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && (items.length == 0 || oldw == 0)) seed(w, h);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        active = new WeakReference<>(this);
        running = true;
        clock.reset();
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        if (active != null && active.get() == this) active = null;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        running = visibility == VISIBLE;
        clock.reset();
        if (running) {
            if (configVersion != PrizmaConfig.version()) refresh();
            active = new WeakReference<>(this);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || kind == OFF || items.length == 0) return;
        float dt = clock.tick();
        time += dt;
        float decay = (float) Math.exp(-dt * 2.4f);
        windX *= decay;
        windY *= decay;
        for (P p : items) {
            step(p, dt, w, h);
            draw(canvas, p);
        }
        if (running) postInvalidateOnAnimation();
    }

    private void step(P p, float dt, int w, int h) {
        p.phase += dt * p.swayRate * (float) (Math.PI * 2);
        p.vx += (windX * p.depth - p.vx) * Math.min(1f, dt * 3f);
        p.vy += (windY * p.depth - p.vy) * Math.min(1f, dt * 3f);
        p.y += (p.speed + p.vy) * dt;
        p.x += p.vx * dt;
        p.rot += p.spin * dt;
        if (kind == FIREFLIES) {
            p.x += (float) Math.cos(p.phase * 0.7f) * AndroidUtilities.dp(10) * dt;
            float blink = 0.5f + 0.5f * (float) Math.sin(time * p.flutter * 2.6f + p.phase);
            p.alpha = Math.min(1f, p.alpha + dt * 0.6f) * 0.25f + blink * blink * 0.75f;
            if (p.y < -p.size * 4) spawn(p, w, h, false);
        } else if (p.y > h + p.size * 3) {
            spawn(p, w, h, false);
        } else if (p.y < -p.size * 6 - AndroidUtilities.dp(60)) {
            p.y = h + p.size * 2;
        }
        float span = w + p.size * 6;
        if (p.x < -p.size * 3) p.x += span;
        else if (p.x > w + p.size * 3) p.x -= span;
    }

    private void draw(Canvas canvas, P p) {
        float sx = p.x + (float) Math.sin(p.phase) * p.sway;
        paint.setColor(p.color);
        paint.setAlpha((int) (255 * PrizmaMotion.clamp(p.alpha, 0f, 1f)));
        switch (kind) {
            case SNOW:
                if (p.size > AndroidUtilities.dp(3.2f)) {
                    flake(canvas, sx, p.y, p.size * 1.25f, p.rot);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(sx, p.y, p.size, paint);
                }
                break;
            case LEAVES:
                shape(canvas, leaf, sx, p.y, p.size, p, true);
                break;
            case PETALS:
                shape(canvas, petal, sx, p.y, p.size, p, false);
                break;
            case FIREFLIES:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(sx, p.y, p.size, paint);
                break;
        }
    }

    private void flake(Canvas canvas, float cx, float cy, float r, float rot) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, r * 0.22f));
        canvas.save();
        canvas.rotate(rot, cx, cy);
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(cx - r, cy, cx + r, cy, paint);
            canvas.rotate(60f, cx, cy);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private void shape(Canvas canvas, Path path, float cx, float cy, float size, P p, boolean vein) {
        float turn = (float) Math.cos(p.phase * p.flutter);
        canvas.save();
        canvas.translate(cx, cy);
        canvas.rotate(p.rot);
        canvas.scale(size * (0.25f + 0.75f * Math.abs(turn)), size);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawPath(path, paint);
        if (vein) {
            int a = paint.getAlpha();
            paint.setColor(0xff5a3416);
            paint.setAlpha((int) (a * 0.45f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(0.09f);
            canvas.drawLine(0f, -0.85f, 0f, 1.25f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();
    }
}
