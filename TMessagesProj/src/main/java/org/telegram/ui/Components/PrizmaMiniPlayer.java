package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

public class PrizmaMiniPlayer extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    public static final int HEIGHT = 58;

    private final LinearLayout body;
    private final BackupImageView cover;
    private final TextView title, author;
    private final ImageView next, close;
    private final TextView live;
    private final Runnable togetherChanged = this::applyTogether;
    private String togetherKey = "";
    private String plainAuthor = "";
    private final PlayKey ring;
    private final PulseLine pulse;
    private final Paint fill = new Paint();
    private final int slop;

    private int color = 0xff22242b;
    private int tint = 0xfff2f1ee;
    private long boundKey = Long.MIN_VALUE;
    private long paletteKey = Long.MIN_VALUE;
    private int account = -1;

    private float downX, downY;
    private boolean swiping, longFired;
    private final Runnable longPress = this::showMenu;
    private final PrizmaSpring shift = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.76f, this::applyShift);

    public PrizmaMiniPlayer(Context context) {
        super(context);
        if (org.telegram.ui.ActionBar.Theme.playPauseAnimator == null) org.telegram.ui.ActionBar.Theme.createChatResources(context, false);
        setWillNotDraw(false);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        body.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(8), AndroidUtilities.dp(5));
        addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        pulse = new PulseLine(context);
        addView(pulse, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 9, Gravity.BOTTOM, 12, 0, 12, 3));

        cover = new BackupImageView(context);
        int leaf = AndroidUtilities.dp(14), corner = (int) (leaf * PrizmaShape.SMALL);
        cover.setRoundRadius(leaf, corner, corner, leaf);
        body.addView(cover, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 0, 0, 11, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setTextSize(15);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        author = new TextView(context);
        author.setTextSize(12.5f);
        author.setSingleLine(true);
        author.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        body.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        ring = new PlayKey(context);
        ring.setContentDescription("Пауза или воспроизведение");
        ring.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(mo);
            } else {
                MediaController.getInstance().pauseMessage(mo);
            }
        });
        body.addView(ring, LayoutHelper.createLinear(42, 36, Gravity.CENTER_VERTICAL, 2, 0, 0, 0));

        live = new TextView(context);
        live.setTextSize(12.5f);
        live.setTypeface(AndroidUtilities.bold());
        live.setGravity(Gravity.CENTER);
        live.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        live.setVisibility(GONE);
        live.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            PrizmaTogether t = PrizmaTogether.get();
            if (t.isHosting()) t.stopHosting();
            else if (t.isFollowing()) t.leave();
            applyTogether();
        });
        PrizmaGlass.pressable(live);
        body.addView(live, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        next = button(R.drawable.prizma_ic_next, "Следующий трек");
        next.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MediaController.getInstance().playNextMessage();
        });
        body.addView(next, LayoutHelper.createLinear(36, 44, Gravity.CENTER_VERTICAL));

        close = button(R.drawable.miniplayer_close, "Закрыть трек");
        close.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MediaController.getInstance().cleanupPlayer(true, true);
        });
        body.addView(close, LayoutHelper.createLinear(32, 44, Gravity.CENTER_VERTICAL));
        paint();
    }

    private ImageView button(int icon, String label) {
        ImageView v = new ImageView(getContext());
        v.setImageResource(icon);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setContentDescription(label);
        PrizmaGlass.pressable(v);
        return v;
    }

    private void paint() {
        fill.setColor(color);
        title.setTextColor(tint);
        author.setTextColor((tint & 0x00ffffff) | 0xa6000000);
        PorterDuffColorFilter f = new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN);
        next.setColorFilter(f);
        close.setColorFilter(f);
        ring.setColors(tint, color);
        live.setTextColor(color);
        live.setBackground(PrizmaShape.fill(tint, AndroidUtilities.dp(13)));
        pulse.setColors(tint, (tint & 0x00ffffff) | 0x38000000);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), fill);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                swiping = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(e.getX() - downX) > slop * 2 && Math.abs(e.getX() - downX) > Math.abs(e.getY() - downY)) {
                    swiping = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                longFired = false;
                swiping = false;
                AndroidUtilities.runOnUIThread(longPress, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (!swiping && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e.getY() - downY)) {
                    swiping = true;
                    AndroidUtilities.cancelRunOnUIThread(longPress);
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (swiping) shift.snap(dx * 0.6f);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                AndroidUtilities.cancelRunOnUIThread(longPress);
                float dx = e.getX() - downX;
                if (swiping) {
                    if (Math.abs(dx) > getWidth() * 0.28f) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (dx < 0) {
                            MediaController.getInstance().playNextMessage();
                        } else {
                            MediaController.getInstance().playPreviousMessage();
                        }
                    }
                    shift.to(0f);
                } else if (!longFired) {
                    PrizmaFullPlayer.show(getContext());
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                AndroidUtilities.cancelRunOnUIThread(longPress);
                shift.to(0f);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void applyShift(float v) {
        body.setTranslationX(v);
        body.setAlpha(1f - Math.min(0.7f, Math.abs(v) / Math.max(1f, getWidth() * 0.6f)));
    }

    private void showMenu() {
        longFired = true;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        View top = getRootView();
        if (!(top instanceof ViewGroup)) return;
        new PrizmaPopup((ViewGroup) top, 0xff24252b, 0xfff2f1ee)
                .item(R.drawable.prizma_ic_music, "Моя музыка", () -> PrizmaPlaylistSheet.show(getContext()))
                .danger(R.drawable.msg_delete, "Закрыть плеер", () -> MediaController.getInstance().cleanupPlayer(true, true))
                .showAt(this, true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter n = NotificationCenter.getInstance(a);
            n.addObserver(this, NotificationCenter.messagePlayingDidStart);
            n.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            n.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        }
        PrizmaTogether.get().addListener(togetherChanged);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        AndroidUtilities.cancelRunOnUIThread(longPress);
        PrizmaTogether.get().removeListener(togetherChanged);
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter n = NotificationCenter.getInstance(a);
            n.removeObserver(this, NotificationCenter.messagePlayingDidStart);
            n.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            n.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        }
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        refresh();
    }

    public void refresh() {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) return;
        long key = mo.getDialogId() * 1_000_003L + mo.getId();
        if (key != boundKey) {
            boolean animate = boundKey != Long.MIN_VALUE;
            boundKey = key;
            bind(mo, animate);
        }
        pulse.setProgress(mo.audioProgress);
        ring.setPlaying(!MediaController.getInstance().isMessagePaused());
        if (paletteKey != key) tryPalette(key);
        applyTogether();
    }

    private void applyTogether() {
        PrizmaTogether t = PrizmaTogether.get();
        String status = null, action = null;
        if (t.isHosting()) {
            String who = t.audienceNames();
            status = who.isEmpty() ? "Эфир · пока никто не слушает" : "Эфир · слушают: " + who;
            action = "Стоп";
        } else if (t.isFollowing()) {
            String host = t.getGuestHostName();
            status = host.isEmpty() ? "Слушаете вместе" : "Слушаете вместе · ведёт " + host;
            action = "Выйти";
        }
        String key = status == null ? "" : status;
        if (key.equals(togetherKey)) return;
        togetherKey = key;
        author.setText(status != null ? status : plainAuthor);
        live.setVisibility(status != null ? VISIBLE : GONE);
        next.setVisibility(status != null ? GONE : VISIBLE);
        if (action != null) live.setText(action);
    }

    private void bind(MessageObject mo, boolean animate) {
        String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
        Runnable set = () -> {
            title.setText(TextUtils.isEmpty(t) ? "Неизвестный трек" : t);
            plainAuthor = a == null ? "" : a;
            author.setText(togetherKey.isEmpty() ? plainAuthor : togetherKey);
            Bitmap bmp = embedded();
            if (bmp != null) {
                cover.setImageBitmap(bmp);
            } else {
                PrizmaTrackCover.bindSmall(cover, mo);
            }
        };
        if (!animate) {
            set.run();
            return;
        }
        title.animate().alpha(0f).translationY(-AndroidUtilities.dp(6)).setDuration(110).withEndAction(() -> {
            set.run();
            title.setTranslationY(AndroidUtilities.dp(8));
            title.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(null).start();
        }).start();
    }

    private static Bitmap embedded() {
        try {
            if (MediaController.getInstance().getAudioInfo() != null) {
                Bitmap b = MediaController.getInstance().getAudioInfo().getCover();
                return b != null && !b.isRecycled() ? b : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void tryPalette(long key) {
        Bitmap src = embedded();
        if (src == null && cover.getImageReceiver() != null) {
            src = cover.getImageReceiver().getBitmap();
        }
        if (src == null || src.isRecycled()) return;
        paletteKey = key;
        int seed = PrizmaConfig.coverColorsMini() ? PrizmaPalette.seedOf(src) : 0;
        PrizmaPalette p = seed == 0 ? PrizmaPalette.neutral() : PrizmaPalette.of(seed);
        final int fromColor = color, fromTint = tint;
        final int toColor = seed == 0 ? 0xff22242b : p.container, toTint = seed == 0 ? 0xfff2f1ee : p.onContainer;
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(420);
        va.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            color = PrizmaPalette.mix(fromColor, toColor, t);
            tint = PrizmaPalette.mix(fromTint, toTint, t);
            paint();
        });
        va.start();
    }

    private static final class PlayKey extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final PlayPauseDrawable icon = new PlayPauseDrawable(18);
        private boolean playing;
        private float flip;
        private final PrizmaSpring flipSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.8f, v -> {
            flip = v;
            invalidate();
        });

        PlayKey(Context c) {
            super(c);
            setClickable(true);
            icon.setParent(this);
            icon.setCallback(this);
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == icon || super.verifyDrawable(who);
        }

        void setColors(int back, int glyph) {
            fill.setColor(back);
            icon.setColor(glyph);
            invalidate();
        }

        void setPlaying(boolean value) {
            if (playing != value) {
                playing = value;
                icon.setPause(value, true);
                flipSpring.to(value ? 0f : 1f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            rect.set(0, 0, getWidth(), getHeight());
            float big = AndroidUtilities.dpf2(16);
            PrizmaShape.leaf(path, rect, big, big * PrizmaShape.SMALL, Math.max(0f, Math.min(1f, flip)));
            canvas.drawPath(path, fill);
            int s = AndroidUtilities.dp(18), cx = getWidth() / 2, cy = getHeight() / 2;
            icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            icon.draw(canvas);
        }
    }

    private static final class PulseLine extends View implements PrizmaBeat.Listener {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int played = 0xffffffff, rest = 0x40ffffff;
        private float progress, shown;

        PulseLine(Context c) {
            super(c);
        }

        void setColors(int on, int off) {
            played = on;
            rest = off;
            invalidate();
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        @Override
        public void onBeat(float strength) {
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (PrizmaConfig.beatEffects()) {
                PrizmaBeat.add(this);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            PrizmaBeat.remove(this);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            shown += (progress - shown) * 0.2f;
            float w = getWidth(), h = getHeight();
            float bar = AndroidUtilities.dpf2(2f), gap = AndroidUtilities.dpf2(2.4f);
            int count = (int) (w / (bar + gap));
            boolean fancy = PrizmaConfig.beatEffects();
            boolean alive = false;
            for (int i = 0; i < count; i++) {
                float v = fancy ? PrizmaBeat.band(i % PrizmaBeat.BANDS) : 0f;
                if (v > 0.02f) alive = true;
                float bh = AndroidUtilities.dpf2(2) + v * (h - AndroidUtilities.dpf2(2));
                float x = i * (bar + gap);
                rect.set(x, h - bh, x + bar, h);
                paint.setColor(x / w <= shown ? played : rest);
                canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint);
            }
            if (alive || Math.abs(progress - shown) > 0.001f) {
                PrizmaConfig.nextDecorFrame(this);
            }
        }
    }
}
