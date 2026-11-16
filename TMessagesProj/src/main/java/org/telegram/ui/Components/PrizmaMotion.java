package org.telegram.ui.Components;

import android.annotation.SuppressLint;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.PrizmaConfig;

public final class PrizmaMotion {

    public static final class Spec {
        public final float stiffness;
        public final float damping;

        public Spec(float stiffness, float damping) {
            this.stiffness = stiffness;
            this.damping = damping;
        }

        public float ratio() {
            if (calm()) return 1f;
            return raw();
        }

        public float raw() {
            return Math.max(0.05f, damping / (2f * (float) Math.sqrt(stiffness)));
        }
    }

    public static final Spec FOLLOW = new Spec(900, 50);
    public static final Spec SETTLE = new Spec(280, 22);
    public static final Spec SOFT = new Spec(260, 24);
    public static final Spec LEAD = new Spec(620, 36);
    public static final Spec TRAIL = new Spec(190, 19);
    public static final Spec POP = new Spec(520, 16);
    public static final Spec FADE = new Spec(420, 32);
    public static final Spec STRETCH_LEAD = new Spec(620, 40);
    public static final Spec STRETCH_TRAIL = new Spec(170, 19);
    public static final Spec KNOB = new Spec(320, 18);
    public static final Spec KNOB_STRETCH = new Spec(300, 20);
    public static final Spec HEART = new Spec(520, 13);
    public static final Spec FILL = new Spec(300, 26);
    public static final Spec ROLL = new Spec(260, 24);
    public static final Spec KICK = new Spec(600, 14);
    public static final Spec MENU = new Spec(300, 23);
    public static final Spec ROW = new Spec(300, 24);
    public static final Spec RELEASE = new Spec(300, 14);
    public static final Spec GLIDE = new Spec(420, 34);
    public static final Spec BUBBLE = new Spec(420, 22);

    public static final int STAGGER = 45;

    public static final android.animation.TimeInterpolator EASE = curve(FADE);
    public static final android.animation.TimeInterpolator BOUNCE = curve(POP);
    public static final android.animation.TimeInterpolator LAND = curve(SETTLE);

    private static long calmCheckedAt;
    private static boolean calmValue;

    private PrizmaMotion() {}

    public static boolean calm() {
        long now = System.currentTimeMillis();
        if (now - calmCheckedAt > 5000) {
            calmCheckedAt = now;
            float scale = 1f;
            try {
                scale = Settings.Global.getFloat(ApplicationLoader.applicationContext.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            } catch (Throwable ignore) {
            }
            calmValue = scale == 0f;
        }
        return calmValue;
    }

    public static boolean lively() {
        return !calm() && PrizmaConfig.fancyEffects();
    }

    public static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    public static float rubber(float x, float min, float max, float d) {
        if (x < min) return min - (1f - 1f / ((min - x) * 0.55f / d + 1f)) * d;
        if (x > max) return max + (1f - 1f / ((x - max) * 0.55f / d + 1f)) * d;
        return x;
    }

    private static final java.util.WeakHashMap<View, SpringAnimation[]> SPRINGS = new java.util.WeakHashMap<>();

    private static int slot(DynamicAnimation.ViewProperty p) {
        if (p == DynamicAnimation.SCALE_X) return 0;
        if (p == DynamicAnimation.SCALE_Y) return 1;
        if (p == DynamicAnimation.TRANSLATION_X) return 2;
        if (p == DynamicAnimation.TRANSLATION_Y) return 3;
        if (p == DynamicAnimation.ALPHA) return 4;
        return 5;
    }

    public static SpringAnimation spring(View v, DynamicAnimation.ViewProperty p, Spec spec) {
        SpringAnimation[] all = SPRINGS.get(v);
        if (all == null) {
            all = new SpringAnimation[6];
            SPRINGS.put(v, all);
        }
        int i = slot(p);
        SpringAnimation a = all[i];
        if (a == null) {
            a = new SpringAnimation(v, p);
            a.setSpring(new SpringForce());
            if (p == DynamicAnimation.ALPHA || p == DynamicAnimation.SCALE_X || p == DynamicAnimation.SCALE_Y) {
                a.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
            }
            all[i] = a;
        }
        a.getSpring().setStiffness(spec.stiffness).setDampingRatio(spec.ratio());
        return a;
    }

    public static void to(View v, DynamicAnimation.ViewProperty p, float value, Spec spec) {
        if (v == null) return;
        if (calm()) {
            stop(v, p);
            set(v, p, value);
            return;
        }
        SpringAnimation a = spring(v, p, spec);
        a.getSpring().setFinalPosition(value);
        a.animateToFinalPosition(value);
    }

    public static void kick(View v, DynamicAnimation.ViewProperty p, float rest, float velocity, Spec spec) {
        if (v == null || calm()) return;
        SpringAnimation a = spring(v, p, spec);
        a.setStartVelocity(velocity);
        a.getSpring().setFinalPosition(rest);
        a.animateToFinalPosition(rest);
    }

    public static void stop(View v, DynamicAnimation.ViewProperty p) {
        SpringAnimation[] all = SPRINGS.get(v);
        if (all != null && all[slot(p)] != null) all[slot(p)].cancel();
    }

    public static android.animation.TimeInterpolator curve(Spec spec) {
        final float w0 = (float) Math.sqrt(spec.stiffness);
        final float zeta = Math.min(0.999f, spec.raw());
        final float wd = w0 * (float) Math.sqrt(1f - zeta * zeta);
        final float total = duration(spec) / 1000f;
        return t -> {
            if (t >= 1f) return 1f;
            float time = t * total;
            float decay = (float) Math.exp(-zeta * w0 * time);
            return 1f - decay * ((float) Math.cos(wd * time) + zeta * w0 / wd * (float) Math.sin(wd * time));
        };
    }

    public static long duration(Spec spec) {
        float w0 = (float) Math.sqrt(spec.stiffness);
        float zeta = Math.max(0.1f, Math.min(1f, spec.raw()));
        float seconds = 4.6f / (zeta * w0);
        return (long) Math.max(180, Math.min(1100, seconds * 1000));
    }

    public static void halt(View v) {
        if (v == null) return;
        SpringAnimation[] all = SPRINGS.get(v);
        if (all == null) return;
        for (SpringAnimation a : all) if (a != null) a.cancel();
    }

    public static void popIn(View v, float from) {
        if (v == null) return;
        v.animate().cancel();
        if (calm()) {
            halt(v);
            v.setAlpha(1f);
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        if (v.getAlpha() >= 0.99f && v.getScaleX() >= 0.99f) {
            v.setScaleX(from);
            v.setScaleY(from);
        }
        to(v, DynamicAnimation.ALPHA, 1f, FADE);
        scaleTo(v, 1f, POP);
    }

    private static void set(View v, DynamicAnimation.ViewProperty p, float value) {
        if (p == DynamicAnimation.SCALE_X) v.setScaleX(value);
        else if (p == DynamicAnimation.SCALE_Y) v.setScaleY(value);
        else if (p == DynamicAnimation.TRANSLATION_X) v.setTranslationX(value);
        else if (p == DynamicAnimation.TRANSLATION_Y) v.setTranslationY(value);
        else if (p == DynamicAnimation.ALPHA) v.setAlpha(value);
        else if (p == DynamicAnimation.ROTATION) v.setRotation(value);
    }

    public static void scaleTo(View v, float value, Spec spec) {
        to(v, DynamicAnimation.SCALE_X, value, spec);
        to(v, DynamicAnimation.SCALE_Y, value, spec);
    }

    public static void snapScale(View v, float value) {
        stop(v, DynamicAnimation.SCALE_X);
        stop(v, DynamicAnimation.SCALE_Y);
        v.setScaleX(value);
        v.setScaleY(value);
    }

    public static void press(View v, boolean down) {
        if (v == null) return;
        if (down) {
            scaleTo(v, 0.955f, FOLLOW);
        } else {
            scaleTo(v, 1f, POP);
        }
    }

    public static void pop(View v) {
        if (v == null || calm()) return;
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        kick(v, DynamicAnimation.SCALE_X, 1f, 4f, POP);
        kick(v, DynamicAnimation.SCALE_Y, 1f, 4f, POP);
    }

    public static void shake(View v, float direction) {
        if (v == null || calm()) return;
        kick(v, DynamicAnimation.TRANSLATION_X, 0f, 300f * direction * v.getResources().getDisplayMetrics().density / 2f, KICK);
    }

    public static void appear(View v, int order, float fromDp) {
        if (v == null) return;
        if (!lively()) {
            v.setAlpha(1f);
            v.setTranslationY(0f);
            return;
        }
        v.setAlpha(0f);
        v.setTranslationY(fromDp * v.getResources().getDisplayMetrics().density);
        v.postOnAnimationDelayed(() -> {
            to(v, DynamicAnimation.ALPHA, 1f, FADE);
            to(v, DynamicAnimation.TRANSLATION_Y, 0f, SETTLE);
        }, (long) order * STAGGER);
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void pressable(View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    press(view, true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    press(view, false);
                    break;
            }
            return false;
        });
    }

    public static final class Value {
        public float x, v, t;
        public float k, c;

        public Value(float start, Spec spec) {
            x = t = start;
            tune(spec);
        }

        public void tune(Spec spec) {
            k = spec.stiffness;
            c = calm() ? 2f * (float) Math.sqrt(spec.stiffness) : spec.damping;
        }

        public void snap(float value) {
            x = t = value;
            v = 0;
        }

        public void step(float dt) {
            float a = -k * (x - t) - c * v;
            v += a * dt;
            x += v * dt;
        }

        public boolean resting() {
            return Math.abs(x - t) < 0.0005f && Math.abs(v) < 0.005f;
        }
    }

    public static final class Clock {
        private long last;

        public float tick() {
            long now = System.nanoTime();
            float dt = last == 0 ? 1f / 60f : (now - last) / 1_000_000_000f;
            last = now;
            return Math.min(dt, 1f / 30f);
        }

        public void reset() {
            last = 0;
        }
    }

    public static boolean run(float dt, Value... values) {
        boolean moving = false;
        int sub = 4;
        float d = dt / sub;
        for (int s = 0; s < sub; s++) {
            for (Value val : values) val.step(d);
        }
        for (Value val : values) {
            if (val.resting()) {
                val.x = val.t;
                val.v = 0;
            } else {
                moving = true;
            }
        }
        return moving;
    }

    public static final class Stretch {
        public final Value a = new Value(0, LEAD);
        public final Value b = new Value(0, LEAD);
        private final Clock clock = new Clock();
        private boolean placed;

        public void moveTo(float left, float right, boolean animated) {
            if (!placed || !animated || calm()) {
                a.snap(left);
                b.snap(right);
                placed = true;
                clock.reset();
                return;
            }
            if (Math.abs(a.t - left) < 0.5f && Math.abs(b.t - right) < 0.5f) return;
            boolean forward = left + right > a.t + b.t;
            if (forward) {
                b.tune(STRETCH_LEAD);
                a.tune(STRETCH_TRAIL);
            } else {
                a.tune(STRETCH_LEAD);
                b.tune(STRETCH_TRAIL);
            }
            a.t = left;
            b.t = right;
            clock.reset();
        }

        public boolean step() {
            return run(clock.tick(), a, b);
        }

        public float left() {
            return Math.min(a.x, b.x);
        }

        public float right() {
            return Math.max(a.x, b.x);
        }

        public boolean placed() {
            return placed;
        }
    }
}
