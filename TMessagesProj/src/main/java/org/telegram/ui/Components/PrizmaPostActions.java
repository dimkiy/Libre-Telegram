package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

public class PrizmaPostActions extends LinearLayout {

    public interface Delegate {
        void onReply();

        void onRepost();

        void onLike();

        void onViews();

        void onSave();

        void onShare();
    }

    private final boolean big;
    private final Slot reply, repost, like, views, save, share;
    private Delegate delegate;

    public PrizmaPostActions(Context context, boolean big) {
        super(context);
        this.big = big;
        setClipChildren(false);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton);
        reply = new Slot(context, PrizmaFeedIcons.REPLY, blue, true);
        repost = new Slot(context, PrizmaFeedIcons.REPOST, PrizmaFeedKit.GREEN, true);
        like = new Slot(context, PrizmaFeedIcons.LIKE, PrizmaFeedKit.PINK, true);
        views = new Slot(context, PrizmaFeedIcons.VIEWS, blue, true);
        save = new Slot(context, PrizmaFeedIcons.BOOKMARK, blue, false);
        share = new Slot(context, PrizmaFeedIcons.SHARE, blue, false);
        int h = big ? 44 : 34;
        addView(reply, LayoutHelper.createLinear(0, h, 1f));
        addView(repost, LayoutHelper.createLinear(0, h, 1f));
        addView(like, LayoutHelper.createLinear(0, h, 1f));
        if (!big) addView(views, LayoutHelper.createLinear(0, h, 1f));
        addView(save, LayoutHelper.createLinear(big ? 0 : 38, h, big ? 1f : 0f));
        addView(share, LayoutHelper.createLinear(big ? 34 : 34, h));
        reply.setOnClickListener(v -> {
            if (delegate != null) delegate.onReply();
        });
        repost.setOnClickListener(v -> {
            if (delegate != null) delegate.onRepost();
        });
        like.setOnClickListener(v -> {
            if (delegate != null) delegate.onLike();
        });
        views.setOnClickListener(v -> {
            if (delegate != null) delegate.onViews();
        });
        save.setOnClickListener(v -> {
            if (delegate != null) delegate.onSave();
        });
        share.setOnClickListener(v -> {
            if (delegate != null) delegate.onShare();
        });
    }

    public void setDelegate(Delegate delegate) {
        this.delegate = delegate;
    }

    public void bind(TLRPC.Message m, boolean saved, boolean animated) {
        reply.setCount(PrizmaFeedKit.count(PrizmaFeedKit.replies(m)), animated);
        reply.setAlpha(PrizmaFeedKit.hasComments(m) || PrizmaFeedKit.replies(m) > 0 ? 1f : 0.45f);
        repost.setCount(PrizmaFeedKit.count(m.forwards), animated);
        like.setCount(PrizmaFeedKit.count(PrizmaFeedKit.reactions(m)), animated);
        like.setActive(PrizmaFeedKit.liked(m), animated);
        views.setCount(PrizmaFeedKit.count(m.views), animated);
        save.setActive(saved, animated);
    }

    public void burstLike() {
        like.burst();
    }

    public void burstSave() {
        save.burst();
    }

    private final class Slot extends LinearLayout {
        private final Glyph glyph;
        private final AnimatedTextView count;
        private final int accent;
        private final int gray;
        private boolean active;

        Slot(Context context, int type, int accent, boolean withCount) {
            super(context);
            this.accent = accent;
            this.gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            setClipChildren(false);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            glyph = new Glyph(context, type, big ? 23 : 19);
            glyph.icon.setColor(gray);
            glyph.setBackground(Theme.createSelectorDrawable(Theme.multAlpha(accent, 0.16f), 1, AndroidUtilities.dp(17)));
            addView(glyph, LayoutHelper.createLinear(34, 34));
            if (withCount) {
                count = new AnimatedTextView(context, false, true, false);
                count.setTextSize(AndroidUtilities.dp(big ? 14 : 13));
                count.setTextColor(gray);
                count.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                count.setAnimationProperties(0.55f, 0, 380, PrizmaMotion.BOUNCE);
                addView(count, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 0, 0, 2, 0));
            } else {
                count = null;
            }
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && glyph.getBackground() != null) {
                glyph.getBackground().setHotspot(glyph.getWidth() / 2f, glyph.getHeight() / 2f);
                glyph.setPressed(true);
                PrizmaMotion.scaleTo(glyph, 0.8f, PrizmaMotion.FOLLOW);
            } else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                glyph.setPressed(false);
                PrizmaMotion.scaleTo(glyph, 1f, PrizmaMotion.POP);
            }
            return super.onTouchEvent(event);
        }

        void setCount(String text, boolean animated) {
            if (count != null) count.setText(text, animated && !count.getText().toString().equals(text));
        }

        void setActive(boolean on, boolean animated) {
            active = on;
            int c = on ? accent : gray;
            glyph.icon.setFilled(on);
            glyph.icon.setColor(c);
            if (count != null) count.setTextColor(c, animated);
        }

        void burst() {
            glyph.burst(accent);
        }
    }

    private static final class Glyph extends View {
        private static final int[] DOTS = {0xffff4d6d, 0xffff9f0a, 0xffffd23f, 0xff34d17a, 0xff3ec9ff, 0xffc69bff, 0xffff6ad5, 0xffff4d6d};
        final PrizmaFeedIcons icon;
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;
        private final PrizmaMotion.Value heart = new PrizmaMotion.Value(1f, PrizmaMotion.HEART);
        private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();
        private float burstTime = 9f;

        Glyph(Context context, int type, int sizeDp) {
            super(context);
            icon = new PrizmaFeedIcons(type, sizeDp);
            icon.setCallback(this);
            size = AndroidUtilities.dp(sizeDp);
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
            return who == icon || super.verifyDrawable(who);
        }

        void burst(int color) {
            ring.setColor(color);
            if (PrizmaMotion.calm()) return;
            heart.x = 0.6f;
            heart.v = 9f;
            heart.t = 1f;
            burstTime = 0f;
            clock.reset();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float dt = clock.tick();
            boolean moving = PrizmaMotion.run(dt, heart);
            if (burstTime < 1f) {
                burstTime += dt;
                moving = true;
            }
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float k = Math.min(1f, burstTime / 0.55f);
            if (k < 1f) {
                float e = 1f - (float) Math.pow(1f - k, 3);
                ring.setStrokeWidth(AndroidUtilities.dp(2f) * (1f - k));
                ring.setAlpha((int) (200 * (1f - k)));
                canvas.drawCircle(cx, cy, size * (0.4f + 0.45f * e), ring);
                float alpha = 1f - k * k;
                for (int i = 0; i < DOTS.length; i++) {
                    float angle = (float) (i / (double) DOTS.length * Math.PI * 2 + 0.2);
                    float r = AndroidUtilities.dp(6) + e * AndroidUtilities.dp(9 + (i % 2) * 3);
                    dot.setColor(DOTS[i]);
                    dot.setAlpha((int) (255 * alpha));
                    canvas.drawCircle(cx + (float) Math.cos(angle) * r, cy + (float) Math.sin(angle) * r, AndroidUtilities.dp(2.2f) * (1f - 0.7f * k), dot);
                }
            }
            float sq = heart.x;
            float sx = sq * (2f - Math.min(sq, 1.15f));
            canvas.save();
            canvas.scale(sx, sq, cx, cy);
            icon.setBounds((int) (cx - size / 2f), (int) (cy - size / 2f), (int) (cx + size / 2f), (int) (cy + size / 2f));
            icon.draw(canvas);
            canvas.restore();
            if (moving) postInvalidateOnAnimation();
        }
    }
}
