package org.telegram.ui.Stars;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.ui.Stars.StarsController.findAttribute;

import android.animation.TimeInterpolator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.ButtonBounce;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ProfileGiftsView extends View implements NotificationCenter.NotificationCenterDelegate {

    private final int currentAccount;
    private final long dialogId;
    private final View avatarContainer;
    private final ProfileActivity.AvatarImageView avatarImage;
    private final Theme.ResourcesProvider resourcesProvider;

    private boolean active = true;
    public void setActive(boolean f) {
        this.active = f;
    }

    public ProfileGiftsView(Context context, int currentAccount, long dialogId, @NonNull View avatarContainer, ProfileActivity.AvatarImageView avatarImage, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        this.currentAccount = currentAccount;
        this.dialogId = dialogId;

        this.avatarContainer = avatarContainer;
        this.avatarImage = avatarImage;

        this.resourcesProvider = resourcesProvider;
    }

    public float expandProgress, collapseProgress;
    public boolean isOpening;

    public void setExpandProgress(float progress) {
        if (this.expandProgress != progress) {
            this.expandProgress = progress;
            invalidate();
        }
    }

    public void setCollapseProgress(float progress, boolean isOpening) {
        this.isOpening = isOpening;
        float newProgress = Utilities.clamp01((progress - 0.3f) / 0.7f);
        if (this.collapseProgress != newProgress) {
            this.collapseProgress = newProgress;
            invalidate();
        }
    }

    private float actionBarProgress;

    public void setActionBarActionMode(float progress) {
//        if (Theme.isCurrentThemeDark()) {
//            return;
//        }
        actionBarProgress = progress;
        invalidate();
    }


    private float left, right, cy;
    private float expandY, maxExpandY;
    private final AnimatedFloat rightAnimated = new AnimatedFloat(this, 0, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

    public void setBounds(float left, float right, float cy, boolean animated, int maxExpandY) {
        boolean changed = Math.abs(left - this.left) > 0.1f || Math.abs(right - this.right) > 0.1f || Math.abs(cy - this.cy) > 0.1f;
        this.left = left;
        this.right = right;
        if (!animated) {
            this.rightAnimated.set(this.right, true);
        }
        this.cy = cy;
        this.maxExpandY = maxExpandY + cy;
        if (changed) {
            invalidate();
        }
    }

    public void setExpandCoords(float y) {
        this.expandY = y;
        invalidate();
    }

    private float progressToInsets = 1f;

    public void setProgressToStoriesInsets(float progressToInsets) {
        if (this.progressToInsets == progressToInsets) {
            return;
        }
        this.progressToInsets = progressToInsets;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        NotificationCenter.getInstance(currentAccount).addWeakObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.addView(this);
        }

        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starUserGiftsLoaded);

        for (Gift gift : gifts) {
            gift.emojiDrawable.removeView(this);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starUserGiftsLoaded) {
            if ((long) args[0] == dialogId) {
                update();
            }
        }
    }

    public final class Gift {

        public final long id;
        public final TLRPC.Document document;
        public final long documentId;
        public final int color;
        public final String slug;
        private StarsReactionsSheet.Particles particles;

        public int position = -1;

        public Gift(TL_stars.TL_starGiftUnique gift) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
            color = backdrop.center_color | 0xFF000000;
            slug = gift.slug;
            initParticles();
        }

        public Gift(TL_stars.StarGift gift, int tint) {
            id = gift.id;
            document = gift.getDocument();
            documentId = document == null ? 0 : document.id;
            color = tint;
            slug = gift instanceof TL_stars.TL_starGiftUnique ? ((TL_stars.TL_starGiftUnique) gift).slug : null;
            initParticles();
        }

        public float bx = Float.NaN, by, vx, vy, phase = (float) (Math.random() * Math.PI * 2);
        public float squashAngle;
        public final org.telegram.ui.Components.PrizmaMotion.Value squash = new org.telegram.ui.Components.PrizmaMotion.Value(0f, new org.telegram.ui.Components.PrizmaMotion.Spec(320, 9));
        public final org.telegram.ui.Components.PrizmaMotion.Value inflate = new org.telegram.ui.Components.PrizmaMotion.Value(0f, org.telegram.ui.Components.PrizmaMotion.SETTLE);
        public final org.telegram.ui.Components.PrizmaMotion.Value appear = new org.telegram.ui.Components.PrizmaMotion.Value(0f, org.telegram.ui.Components.PrizmaMotion.POP);
        public long inflatedAt;

        void hit(float nx, float ny, float strength) {
            squashAngle = (float) Math.toDegrees(Math.atan2(ny, nx));
            squash.v -= Math.min(6f, strength);
        }

        public Gift(TLRPC.TL_emojiStatusCollectible status) {
            id = status.collectible_id;
            document = null;
            documentId = status.document_id;
            color = status.center_color | 0xFF000000;
            slug = status.slug;
            initParticles();
        }

        private void initParticles() {
            particles = new StarsReactionsSheet.Particles(StarsReactionsSheet.Particles.TYPE_RADIAL, 6);
            final float gsz = dp(36);
            particles.bounds.set(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f);
        }

        public boolean equals(Gift b) {
            return b != null && b.id == id;
        }

        public RadialGradient gradient;
        public final Matrix gradientMatrix = new Matrix();
        public Paint gradientPaint;
        public AnimatedEmojiDrawable emojiDrawable;
        public AnimatedFloat animatedFloat;

        public final RectF bounds = new RectF();
        public final ButtonBounce bounce = new ButtonBounce(ProfileGiftsView.this);

        public void copy(Gift b) {
            gradient = b.gradient;
            emojiDrawable = b.emojiDrawable;
            gradientPaint = b.gradientPaint;
            animatedFloat = b.animatedFloat;
            particles = b.particles;
            position = b.position;
            bx = b.bx;
            by = b.by;
            vx = b.vx;
            vy = b.vy;
            phase = b.phase;
            appear.snap(b.appear.x);
            appear.t = 1f;
        }

        public void draw(
                Canvas canvas,
                float cx, float cy,
                float ascale, float rotate,
                float alpha,
                float gradientAlpha
        ) {
            if (alpha <= 0.0f) return;
            final float gsz = dp(45);
            bounds.set(cx - gsz / 2, cy - gsz / 2, cx + gsz / 2, cy + gsz / 2);
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(rotate);
            final float scale = ascale * bounce.getScale(0.1f);
            canvas.scale(scale, scale);
            particles.process();
            particles.draw(canvas, color, alpha);
            if (gradientPaint != null) {
                gradientPaint.setAlpha((int) (0xFF * alpha * gradientAlpha));
                canvas.drawRect(-gsz / 2.0f, -gsz / 2.0f, gsz / 2.0f, gsz / 2.0f, gradientPaint);
            }
            if (emojiDrawable != null) {
                final int sz = dp(24);
                emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
                emojiDrawable.setAlpha((int) (0xFF * alpha));
                emojiDrawable.draw(canvas);
            }
            canvas.restore();
        }
    }

    private StarsController.GiftsList list;

    public final ArrayList<Gift> oldGifts = new ArrayList<>();
    public final ArrayList<Gift> gifts = new ArrayList<>();
    public final HashSet<Long> giftIds = new HashSet<>();
    public int maxCount;

    public void update() {
        if (!MessagesController.getInstance(currentAccount).enableGiftsInProfile) {
            return;
        }

        maxCount = MessagesController.getInstance(currentAccount).stargiftsPinnedToTopLimit;
        oldGifts.clear();
        oldGifts.addAll(gifts);
        gifts.clear();
        giftIds.clear();

        final TLRPC.EmojiStatus emojiStatus;
        if (dialogId >= 0) {
            final TLRPC.User user = MessagesController.getInstance(currentAccount).getUser(dialogId);
            emojiStatus = user == null ? null : user.emoji_status;
        } else {
            final TLRPC.User chat = MessagesController.getInstance(currentAccount).getUser(-dialogId);
            emojiStatus = chat == null ? null : chat.emoji_status;
        }
        if (emojiStatus instanceof TLRPC.TL_emojiStatusCollectible) {
            giftIds.add(((TLRPC.TL_emojiStatusCollectible) emojiStatus).collectible_id);
        }
        list = StarsController.getInstance(currentAccount).getProfileGiftsList(dialogId);
        if (list != null) {
            for (int i = 0; i < list.gifts.size(); i++) {
                final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                if (!savedGift.unsaved && savedGift.pinned_to_top && savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                    final Gift gift = new Gift((TL_stars.TL_starGiftUnique) savedGift.gift);
                    if (!giftIds.contains(gift.id)) {
                        gifts.add(gift);
                        giftIds.add(gift.id);
                    }
                }
            }
            if (bubbles()) {
                for (int i = 0; i < list.gifts.size() && gifts.size() < BUBBLES_MAX; i++) {
                    final TL_stars.SavedStarGift savedGift = list.gifts.get(i);
                    if (savedGift.unsaved || savedGift.gift == null || savedGift.gift.getDocument() == null) continue;
                    if (giftIds.contains(savedGift.gift.id)) continue;
                    int tint = BUBBLE_TINTS[gifts.size() % BUBBLE_TINTS.length];
                    if (savedGift.gift instanceof TL_stars.TL_starGiftUnique) {
                        final TL_stars.starGiftAttributeBackdrop backdrop = findAttribute(savedGift.gift.attributes, TL_stars.starGiftAttributeBackdrop.class);
                        if (backdrop != null) tint = backdrop.center_color | 0xFF000000;
                    }
                    final Gift gift = new Gift(savedGift.gift, tint);
                    gifts.add(gift);
                    giftIds.add(gift.id);
                }
                if (list.gifts.isEmpty() && !list.loading && !list.endReached) list.load();
            }
        }

        boolean changed = false;
        if (gifts.size() != oldGifts.size()) {
            changed = true;
        } else for (int i = 0; i < gifts.size(); i++) {
            if (!gifts.get(i).equals(oldGifts.get(i))) {
                changed = true;
                break;
            }
        }

        for (int i = 0; i < gifts.size(); i++) {
            final Gift g = gifts.get(i);
            Gift oldGift = null;
            for (int j = 0; j < oldGifts.size(); ++j) {
                if (oldGifts.get(j).id == g.id) {
                    oldGift = oldGifts.get(j);
                    break;
                }
            }

            if (oldGift != null) {
                g.copy(oldGift);
            } else {
                g.gradient = new RadialGradient(0, 0, dp(22.5f), new int[]{g.color, Theme.multAlpha(g.color, 0.0f)}, new float[]{0, 1}, Shader.TileMode.CLAMP);
                g.gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                g.gradientPaint.setShader(g.gradient);
                if (g.document != null) {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.document);
                } else {
                    g.emojiDrawable = AnimatedEmojiDrawable.make(currentAccount, AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES, g.documentId);
                }
                g.animatedFloat = new AnimatedFloat(this, 0, 320, null);
                g.animatedFloat.force(0.0f);
                if (isAttachedToWindow()) {
                    g.emojiDrawable.addView(this);
                }
            }
        }

        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < maxCount; i++) {
            positions.add(i);
        }

        for (int i = 0; i < oldGifts.size(); i++) {
            final Gift g = oldGifts.get(i);
            Gift newGift = null;
            for (int j = 0; j < gifts.size(); ++j) {
                if (gifts.get(j).id == g.id) {
                    newGift = gifts.get(j);
                    break;
                }
            }
            if (newGift == null) {
                g.emojiDrawable.removeView(this);
                g.emojiDrawable = null;
                g.gradient = null;
            } else {
                positions.remove((Object) g.position);
            }
        }

        if (!positions.isEmpty()) {
            BagRandomizer<Integer> bagRandomizer = new BagRandomizer<>(positions);
            for (int i = 0; i < gifts.size(); ++i) {
                final Gift g = gifts.get(i);
                if (g.position == -1) {
                    g.position = bagRandomizer.next();
                }
            }
        }

        if (changed)
            invalidate();
    }

    private final TimeInterpolator giftCollapseXInterpolator = new DecelerateInterpolator();
    private final TimeInterpolator giftCollapseYInterpolator = new LinearInterpolator();

    private static final int BUBBLES_MAX = 9;
    private static final int[] BUBBLE_TINTS = {0xff8fb8ff, 0xffffb38f, 0xffb08fff, 0xff8fffc7, 0xffff8fc4, 0xffffe08f};
    private final org.telegram.ui.Components.PrizmaMotion.Clock bubbleClock = new org.telegram.ui.Components.PrizmaMotion.Clock();
    private final Paint bubbleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleShine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bubbleRect = new RectF();
    private android.graphics.Shader bubbleShader;
    private Gift dragged;
    private float dragX, dragY, dragDownX, dragDownY;
    private long dragDownAt;
    private boolean dragMoved;
    private android.view.VelocityTracker dragVelocity;
    private float bubbleAlpha;

    private boolean bubbles() {
        return org.telegram.messenger.PrizmaConfig.giftBubbles();
    }

    private float bubbleRadius(Gift g) {
        float base = dpf2(g.slug != null ? 24 : 21);
        return base * (1f + 1.9f * Math.max(0f, g.inflate.x)) * Math.max(0f, g.appear.x);
    }

    private float bubbleTop() {
        return AndroidUtilities.statusBarHeight + dp(4);
    }

    private float bubbleBottom() {
        return Math.max(bubbleTop() + dp(60), expandY - dp(2));
    }

    private void placeBubble(Gift g, int index, float acx, float acy) {
        double angle = index * 2.399963 + Math.random() * 0.4;
        float dist = dp(78) + (index % 3) * dp(18);
        g.bx = Math.max(dp(24), Math.min(getWidth() - dp(24), acx + (float) Math.cos(angle) * dist));
        g.by = Math.max(bubbleTop() + dp(24), Math.min(bubbleBottom() - dp(24), acy + (float) Math.sin(angle) * dist * 0.75f));
        g.vx = (float) (Math.random() - 0.5) * dp(40);
        g.vy = (float) (Math.random() - 0.5) * dp(40);
        g.appear.snap(0f);
        g.appear.t = 1f;
    }

    private void stepBubbles(float dt, float acx, float acy) {
        final float top = bubbleTop(), bottom = bubbleBottom(), w = getWidth();
        long now = System.currentTimeMillis();
        for (int i = 0; i < gifts.size(); i++) {
            Gift g = gifts.get(i);
            if (Float.isNaN(g.bx)) placeBubble(g, i, acx, acy);
            if (g.inflate.t > 0 && g != dragged && now - g.inflatedAt > 3200) g.inflate.t = 0f;
            org.telegram.ui.Components.PrizmaMotion.run(dt, g.squash, g.inflate, g.appear);
            if (g == dragged) continue;
            g.phase += dt * 0.6f;
            float wander = dp(14);
            g.vx += (float) Math.cos(g.phase * 1.3f + i) * wander * dt;
            g.vy += (float) Math.sin(g.phase + i * 0.7f) * wander * dt;
            float dx = g.bx - acx, dy = g.by - acy;
            float far = (float) Math.hypot(dx, dy);
            float home = dp(170);
            if (far > home) {
                g.vx -= dx / far * (far - home) * 1.6f * dt;
                g.vy -= dy / far * (far - home) * 1.6f * dt;
            }
            float drag = (float) Math.exp(-dt * 0.9f);
            g.vx *= drag;
            g.vy *= drag;
            float speed = (float) Math.hypot(g.vx, g.vy);
            float maxSpeed = dp(900);
            if (speed > maxSpeed) {
                g.vx *= maxSpeed / speed;
                g.vy *= maxSpeed / speed;
            }
            if (speed < dp(10) && g.inflate.t == 0f) {
                g.vx += (float) Math.cos(g.phase * 2.1f) * dp(6) * dt * 6f;
                g.vy += (float) Math.sin(g.phase * 1.7f) * dp(6) * dt * 6f;
            }
            g.bx += g.vx * dt;
            g.by += g.vy * dt;
            float r = bubbleRadius(g);
            if (g.bx < r) { g.bx = r; if (g.vx < 0) { g.hit(1, 0, -g.vx / dp(120)); g.vx = -g.vx * 0.85f; } }
            if (g.bx > w - r) { g.bx = w - r; if (g.vx > 0) { g.hit(-1, 0, g.vx / dp(120)); g.vx = -g.vx * 0.85f; } }
            if (g.by < top + r) { g.by = top + r; if (g.vy < 0) { g.hit(0, 1, -g.vy / dp(120)); g.vy = -g.vy * 0.85f; } }
            if (g.by > bottom - r) { g.by = bottom - r; if (g.vy > 0) { g.hit(0, -1, g.vy / dp(120)); g.vy = -g.vy * 0.85f; } }
        }
        for (int i = 0; i < gifts.size(); i++) {
            Gift a = gifts.get(i);
            for (int j = i + 1; j < gifts.size(); j++) {
                Gift b = gifts.get(j);
                float ra = bubbleRadius(a), rb = bubbleRadius(b);
                float dx = b.bx - a.bx, dy = b.by - a.by;
                float d = (float) Math.hypot(dx, dy);
                float min = ra + rb;
                if (d >= min || d < 0.01f) continue;
                float nx = dx / d, ny = dy / d;
                float overlap = min - d;
                float wa = a == dragged ? 0f : (b == dragged ? 1f : rb * rb / (ra * ra + rb * rb));
                float wb = 1f - wa;
                if (a == dragged) wb = 1f;
                a.bx -= nx * overlap * wa;
                a.by -= ny * overlap * wa;
                b.bx += nx * overlap * wb;
                b.by += ny * overlap * wb;
                float rel = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
                if (rel < 0) {
                    float impulse = -rel * 0.92f;
                    if (a != dragged) {
                        a.vx -= nx * impulse * wa * 2f;
                        a.vy -= ny * impulse * wa * 2f;
                    }
                    if (b != dragged) {
                        b.vx += nx * impulse * wb * 2f;
                        b.vy += ny * impulse * wb * 2f;
                    }
                    float strength = -rel / dp(140);
                    a.hit(nx, ny, strength);
                    b.hit(-nx, -ny, strength);
                }
            }
        }
    }

    private void drawBubble(Canvas canvas, Gift g, float alpha) {
        float r = bubbleRadius(g);
        if (r < 1f || alpha <= 0.01f) return;
        float s = Math.max(-0.28f, Math.min(0.28f, g.squash.x * 0.09f));
        float wob = 0.025f * (float) Math.sin(g.phase * 5.3f);
        canvas.save();
        canvas.translate(g.bx, g.by);
        canvas.rotate(g.squashAngle);
        canvas.scale(1f + s + wob, 1f - s - wob);
        canvas.rotate(-g.squashAngle);
        if (g.emojiDrawable != null) {
            int sz = (int) (r * 1.15f);
            g.emojiDrawable.setBounds(-sz / 2, -sz / 2, sz / 2, sz / 2);
            g.emojiDrawable.setAlpha((int) (0xFF * alpha));
            g.emojiDrawable.draw(canvas);
        }
        canvas.scale(r, r);
        if (bubbleShader == null) {
            bubbleShader = new android.graphics.RadialGradient(-0.18f, -0.22f, 1.25f,
                    new int[]{0x0affffff, 0x10ffffff, 0x38ffffff, 0x70ffffff},
                    new float[]{0f, 0.62f, 0.86f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            bubbleFill.setShader(bubbleShader);
        }
        bubbleTint.setColor(g.color);
        bubbleTint.setAlpha((int) (0x1c * alpha));
        canvas.drawCircle(0, 0, 1f, bubbleTint);
        bubbleFill.setAlpha((int) (255 * alpha));
        canvas.drawCircle(0, 0, 1f, bubbleFill);
        bubbleRim.setStyle(Paint.Style.STROKE);
        bubbleRim.setStrokeWidth(dpf2(1.1f) / r);
        bubbleRim.setColor(0xffffffff);
        bubbleRim.setAlpha((int) (0x73 * alpha));
        canvas.drawCircle(0, 0, 1f - dpf2(0.6f) / r, bubbleRim);
        bubbleShine.setStyle(Paint.Style.STROKE);
        bubbleShine.setStrokeCap(Paint.Cap.ROUND);
        bubbleShine.setStrokeWidth(Math.max(dpf2(2f) / r, 0.07f));
        bubbleShine.setColor(0xffffffff);
        bubbleShine.setAlpha((int) (0xb0 * alpha));
        bubbleRect.set(-0.72f, -0.72f, 0.72f, 0.72f);
        canvas.drawArc(bubbleRect, 200f, 50f, false, bubbleShine);
        bubbleShine.setAlpha((int) (0x60 * alpha));
        bubbleShine.setStrokeWidth(Math.max(dpf2(1.4f) / r, 0.05f));
        canvas.drawArc(bubbleRect, 30f, 22f, false, bubbleShine);
        canvas.restore();
        g.bounds.set(g.bx - r, g.by - r, g.bx + r, g.by + r);
    }

    private void drawBubbles(Canvas canvas) {
        float dt = bubbleClock.tick();
        final float acx = avatarContainer.getX() + avatarContainer.getWidth() * avatarContainer.getScaleX() / 2f;
        final float acy = avatarContainer.getY() + avatarContainer.getHeight() * avatarContainer.getScaleY() / 2f;
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));
        bubbleAlpha = (1.0f - expandProgress) * (1.0f - actionBarProgress) * closedAlpha * Utilities.clamp01(collapseProgress);
        stepBubbles(dt, acx, acy);
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), expandY);
        Gift top = null;
        for (int i = 0; i < gifts.size(); i++) {
            Gift g = gifts.get(i);
            if (g.inflate.x > 0.02f || g == dragged) {
                if (top == null || g.inflate.x > top.inflate.x) top = g;
                continue;
            }
            drawBubble(canvas, g, bubbleAlpha);
        }
        for (int i = 0; i < gifts.size(); i++) {
            Gift g = gifts.get(i);
            if ((g.inflate.x > 0.02f || g == dragged) && g != top) drawBubble(canvas, g, bubbleAlpha);
        }
        if (top != null) drawBubble(canvas, top, bubbleAlpha);
        canvas.restore();
        if (bubbleAlpha > 0.01f && isAttachedToWindow()) postInvalidateOnAnimation();
    }

    private Gift bubbleUnder(float x, float y) {
        Gift best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < gifts.size(); i++) {
            Gift g = gifts.get(i);
            if (Float.isNaN(g.bx)) continue;
            float d = (float) Math.hypot(x - g.bx, y - g.by);
            float r = bubbleRadius(g) + dp(6);
            if (d < r && d - r < bestD) {
                bestD = d - r;
                best = g;
            }
        }
        return best;
    }

    private boolean bubbleTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (bubbleAlpha < 0.5f) return false;
                dragged = bubbleUnder(e.getX(), e.getY());
                if (dragged == null) return false;
                dragX = e.getX();
                dragY = e.getY();
                dragDownX = e.getX();
                dragDownY = e.getY();
                dragDownAt = System.currentTimeMillis();
                dragMoved = false;
                dragged.vx = 0;
                dragged.vy = 0;
                if (dragVelocity == null) dragVelocity = android.view.VelocityTracker.obtain();
                dragVelocity.clear();
                dragVelocity.addMovement(e);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragged == null) return false;
                if (dragVelocity != null) dragVelocity.addMovement(e);
                if (!dragMoved && Math.hypot(e.getX() - dragDownX, e.getY() - dragDownY) > dp(8)) dragMoved = true;
                if (dragMoved) {
                    dragged.bx += e.getX() - dragX;
                    dragged.by += e.getY() - dragY;
                }
                dragX = e.getX();
                dragY = e.getY();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragged == null) return false;
                Gift g = dragged;
                dragged = null;
                if (dragMoved && dragVelocity != null) {
                    dragVelocity.addMovement(e);
                    dragVelocity.computeCurrentVelocity(1000);
                    g.vx = dragVelocity.getXVelocity() * 0.9f;
                    g.vy = dragVelocity.getYVelocity() * 0.9f;
                    float speed = (float) Math.hypot(g.vx, g.vy);
                    if (speed > 1f) g.hit(g.vx / speed, g.vy / speed, speed / dp(400));
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP && System.currentTimeMillis() - dragDownAt < 450) {
                    if (g.inflate.t > 0f) {
                        g.inflate.t = 0f;
                        if (g.slug != null) onGiftClick(g);
                    } else {
                        for (Gift o : gifts) o.inflate.t = 0f;
                        g.inflate.t = 1f;
                        g.inflatedAt = System.currentTimeMillis();
                        g.squash.v += 4f;
                        try {
                            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        } catch (Throwable ignore) {
                        }
                    }
                }
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
        }
        return dragged != null;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (gifts.isEmpty() || expandProgress >= 1.0f || collapseProgress <= 0f) return;
        if (bubbles()) {
            drawBubbles(canvas);
            return;
        }

        final float realX = avatarContainer.getX();
        final float realY = avatarContainer.getY();
        final float realW = (avatarContainer.getWidth()) * avatarContainer.getScaleX();
        final float realH = (avatarContainer.getHeight()) * avatarContainer.getScaleY();

        final float sz = dpf2(96);
        final float ax = Math.min(realX, (getWidth() - sz) / 2f);
        final float ay = Math.max(realY, (maxExpandY - sz) / 2f);
        final float aw = Math.max(realW, sz);
        final float ah = Math.max(realH, sz);


        canvas.save();
        canvas.clipRect(0, 0, getWidth(), expandY);

        final float acx = ax + aw / 2.0f;
        final float acy = ay + ah / 2.0f;

        final float realCX = realX + realW / 2.0f;
        final float realCY = realY + realH / 2.0f;

        float expandScale = expandY / maxExpandY;
        float closedAlpha = Utilities.clamp01((expandY - (AndroidUtilities.statusBarHeight + ActionBar.getCurrentActionBarHeight())) / dp(50));

        for (int i = 0; i < gifts.size(); ++i) {
            final Gift gift = gifts.get(i);
            final float enter = gift.animatedFloat.set(1.0f);
            float scale = lerp(0.5f, 1.0f, enter);
            final float alpha = enter * (1.0f - expandProgress) * (1.0f - actionBarProgress) * (closedAlpha);

            float gx, gy;
            float delayValue;

            switch (gift.position) {
                case 0: // left
                    gx = acx / 2f - (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 1: // top left
                    gx = acx * 2f / 3f - (dp(6) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0;
                    break;
                case 2: // bottom left
                    gx = acx * 2f / 3f - (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0.9f;
                    break;
                case 3: // right
                    gx = acx * 1.5f + (dp(20) * expandScale);
                    gy = acy - dp(13);
                    delayValue = 1.6f;
                    break;
                case 4: // top right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay - dp(4);
                    delayValue = 0.9f;
                    break;
                default: // bottom right
                    gx = acx * 4f / 3f + (dp(12) * expandScale);
                    gy = ay + ah - dp(16);
                    delayValue = 0;
                    break;
            }

            final float collapseProgressWithEnter;
            if (isOpening || enter >= 1f) {
                collapseProgressWithEnter = collapseProgress;
            } else {
                collapseProgressWithEnter = Math.min(enter, collapseProgress);
            }

            final float delayFraction = 0.2f;
            final float maxDelayFraction = 1.6f * delayFraction;
            final float intervalFraction = 1f - maxDelayFraction;

            float delay = delayValue * delayFraction;
            float collapse = collapseProgressWithEnter >= 1f - delay ? 1f
                    : Utilities.clamp01((collapseProgressWithEnter - maxDelayFraction + delay) / intervalFraction);
            if (collapse < 1f) {
                gx = AndroidUtilities.lerp(realCX, gx, giftCollapseXInterpolator.getInterpolation(collapse));
                gy = AndroidUtilities.lerp(realCY, gy, giftCollapseYInterpolator.getInterpolation(collapse));
                scale = AndroidUtilities.lerp(scale / 2f, scale, collapse);
            }

            gift.draw(canvas, gx, gy, scale, 0, alpha, 1f);
        }

        canvas.restore();
    }

    public Gift getGiftUnder(float x, float y) {
        for (int i = 0; i < gifts.size(); ++i) {
            if (gifts.get(i).bounds.contains(x, y))
                return gifts.get(i);
        }
        return null;
    }

    private Gift pressedGift;

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!active) return false;
        if (bubbles()) return bubbleTouch(event);
        final Gift hit = getGiftUnder(event.getX(), event.getY());
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            pressedGift = hit;
            if (pressedGift != null) {
                pressedGift.bounce.setPressed(true);
            }
        } else if (event.getAction() == MotionEvent.ACTION_MOVE) {
            if (pressedGift != hit && pressedGift != null) {
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            if (pressedGift != null) {
                onGiftClick(pressedGift);
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        } else if (event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (pressedGift != null) {
                pressedGift.bounce.setPressed(false);
                pressedGift = null;
            }
        }
        return pressedGift != null;
    }

    public void onGiftClick(Gift gift) {
        Browser.openUrl(getContext(), "https://t.me/nft/" + gift.slug);
    }
}
