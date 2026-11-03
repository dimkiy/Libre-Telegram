package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;

import java.util.WeakHashMap;

public final class PrizmaEntrance {
    private static final long ROW_DURATION = 430;
    private static final long ROW_STAGGER = 55;
    private static final int MAX_STAGGERED = 14;

    private static final WeakHashMap<RecyclerView, Integer> GEN = new WeakHashMap<>();
    private static final WeakHashMap<RecyclerView, Boolean> DETACH_HOOKED = new WeakHashMap<>();

    private PrizmaEntrance() {}

    public static void play(RecyclerView rv) {
        play(rv, false, 0);
    }

    public static void play(RecyclerView rv, boolean fromBottom) {
        play(rv, fromBottom, 0);
    }

    private static void play(RecyclerView rv, boolean fromBottom, int attempt) {
        if (rv == null) {
            return;
        }
        if (!PrizmaConfig.fancyEffects()) {
            reset(rv);
            return;
        }
        hookDetachReset(rv);
        int n = rv.getChildCount();
        if (n == 0) {
            if (attempt < 8) {
                final int next = attempt + 1;
                rv.postOnAnimation(() -> play(rv, fromBottom, next));
            }
            return;
        }
        final int gen = (GEN.get(rv) == null ? 0 : GEN.get(rv)) + 1;
        GEN.put(rv, gen);

        float startOffset = AndroidUtilities.dp(24);
        for (int i = 0; i < n; i++) {
            final View child = rv.getChildAt(i);
            if (child == null) {
                continue;
            }
            child.animate().setListener(null).cancel();
            child.setAlpha(0f);
            child.setTranslationY(startOffset);
            int order = fromBottom ? (n - 1 - i) : i;
            long delay = (long) Math.min(order, MAX_STAGGERED) * ROW_STAGGER;
            final AnimatorListenerAdapter resetter = new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    child.setAlpha(1f);
                    child.setTranslationY(0f);
                }
            };
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(delay)
                    .setDuration(ROW_DURATION)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setListener(resetter)
                    .withLayer()
                    .start();
        }

        long total = ROW_DURATION + (long) Math.min(n, MAX_STAGGERED + 1) * ROW_STAGGER + 300;
        AndroidUtilities.runOnUIThread(() -> {
            Integer cur = GEN.get(rv);
            if (cur != null && cur == gen) {
                forceVisible(rv);
            }
        }, total);
    }

    private static void forceVisible(RecyclerView rv) {
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child != null && (child.getAlpha() < 1f || child.getTranslationY() != 0f)) {
                child.animate().cancel();
                child.setAlpha(1f);
                child.setTranslationY(0f);
            }
        }
    }

    private static void hookDetachReset(RecyclerView rv) {
        if (Boolean.TRUE.equals(DETACH_HOOKED.get(rv))) {
            return;
        }
        DETACH_HOOKED.put(rv, true);
        rv.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {}

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                view.animate().cancel();
                view.setAlpha(1f);
                view.setTranslationY(0f);
            }
        });
    }

    public static void playOnFirstDraw(RecyclerView rv) {
        if (rv == null || !PrizmaConfig.fancyEffects()) {
            return;
        }
        final ViewTreeObserver vto = rv.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            final long deadline = System.currentTimeMillis() + 2000;
            @Override
            public boolean onPreDraw() {
                boolean done = false;
                if (!PrizmaConfig.fancyEffects() || System.currentTimeMillis() > deadline) {
                    done = true;
                } else if (rv.getChildCount() > 0) {
                    play(rv);
                    done = true;
                }
                if (done) {
                    ViewTreeObserver obs = rv.getViewTreeObserver();
                    if (obs.isAlive()) {
                        obs.removeOnPreDrawListener(this);
                    }
                }
                return true;
            }
        });
    }

    public static void reset(RecyclerView rv) {
        if (rv == null) {
            return;
        }
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child != null) {
                child.animate().cancel();
                child.setAlpha(1f);
                child.setTranslationY(0f);
            }
        }
    }
}
