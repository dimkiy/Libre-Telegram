package org.telegram.ui.Components;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

public final class PrizmaSpring {
    public interface Sink {
        void set(float value);
    }

    public static final float SOFT = 220f;
    public static final float MEDIUM = 420f;
    public static final float SNAPPY = 820f;

    private final SpringAnimation animation;
    private final FloatValueHolder holder;
    private final Sink sink;
    private float target;

    public PrizmaSpring(float start, float stiffness, float damping, Sink sink) {
        this.sink = sink;
        target = start;
        holder = new FloatValueHolder(start);
        animation = new SpringAnimation(holder);
        animation.setSpring(new SpringForce(start).setStiffness(stiffness).setDampingRatio(damping));
        animation.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE);
        animation.addUpdateListener((a, value, velocity) -> sink.set(value));
    }

    public void to(float value) {
        target = value;
        animation.animateToFinalPosition(value);
    }

    public void snap(float value) {
        animation.cancel();
        target = value;
        holder.setValue(value);
        animation.getSpring().setFinalPosition(value);
        sink.set(value);
    }

    public void kick(float velocity) {
        animation.setStartVelocity(velocity);
        animation.animateToFinalPosition(target);
    }

    public float target() {
        return target;
    }

    public float value() {
        return holder.getValue();
    }

    public void cancel() {
        animation.cancel();
    }
}
