package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
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
    private final ImageView prev, next;
    private final RingPlay ring;
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
        body.setPadding(AndroidUtilities.dp(9), 0, AndroidUtilities.dp(8), 0);
        addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        cover = new BackupImageView(context);
        cover.setRoundRadius(AndroidUtilities.dp(13));
        body.addView(cover, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL, 0, 0, 11, 0));

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

        prev = button(R.drawable.prizma_ic_prev, "Предыдущий трек");
        prev.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MediaController.getInstance().playPreviousMessage();
        });
        body.addView(prev, LayoutHelper.createLinear(38, 44, Gravity.CENTER_VERTICAL));

        ring = new RingPlay(context);
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
        body.addView(ring, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 2, 0, 2, 0));

        next = button(R.drawable.prizma_ic_next, "Следующий трек");
        next.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            MediaController.getInstance().playNextMessage();
        });
        body.addView(next, LayoutHelper.createLinear(38, 44, Gravity.CENTER_VERTICAL));
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
        prev.setColorFilter(f);
        next.setColorFilter(f);
        ring.setTint(tint);
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
        account = UserConfig.selectedAccount;
        NotificationCenter n = NotificationCenter.getInstance(account);
        n.addObserver(this, NotificationCenter.messagePlayingDidStart);
        n.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        n.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        refresh();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter n = NotificationCenter.getInstance(account);
        n.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        n.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        n.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
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
        ring.setProgress(mo.audioProgress);
        ring.setPlaying(!MediaController.getInstance().isMessagePaused());
        if (paletteKey != key) tryPalette(key);
    }

    private void bind(MessageObject mo, boolean animate) {
        String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
        Runnable set = () -> {
            title.setText(TextUtils.isEmpty(t) ? "Неизвестный трек" : t);
            author.setText(a == null ? "" : a);
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

    private static final class RingPlay extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final PlayPauseDrawable icon = new PlayPauseDrawable(20);
        private int tint = 0xffffffff;
        private float progress, shown;
        private boolean playing;
        private float wave;
        private final PrizmaSpring waveSpring = new PrizmaSpring(0f, PrizmaSpring.SOFT, 1f, v -> {
            wave = v;
            invalidate();
        });

        RingPlay(Context c) {
            super(c);
            setClickable(true);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
            ring.setStrokeJoin(Paint.Join.ROUND);
            ring.setStrokeWidth(AndroidUtilities.dpf2(2.4f));
            icon.setParent(this);
            icon.setCallback(this);
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == icon || super.verifyDrawable(who);
        }

        void setTint(int color) {
            tint = color;
            icon.setColor(color);
            invalidate();
        }

        void setProgress(float p) {
            progress = Math.max(0f, Math.min(1f, p));
            invalidate();
        }

        void setPlaying(boolean value) {
            if (playing != value) {
                playing = value;
                icon.setPause(value, true);
                waveSpring.to(value ? 1f : 0f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float radius = Math.min(cx, cy) - AndroidUtilities.dpf2(4);
            shown += (progress - shown) * 0.2f;
            long now = System.currentTimeMillis();
            boolean fancy = PrizmaConfig.beatEffects();

            ring.setColor((tint & 0x00ffffff) | 0x33000000);
            canvas.drawCircle(cx, cy, radius, ring);

            int steps = 72;
            int drawn = (int) (steps * shown);
            if (drawn > 0) {
                path.rewind();
                float amp = AndroidUtilities.dpf2(1.5f) * wave * (fancy ? 1f : 0f);
                double phase = now / 180.0;
                for (int i = 0; i <= drawn; i++) {
                    double a = -Math.PI / 2 + 2 * Math.PI * i / steps;
                    float r = radius + amp * (float) Math.sin(a * 10 + phase);
                    float x = cx + r * (float) Math.cos(a);
                    float y = cy + r * (float) Math.sin(a);
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                ring.setColor(tint);
                canvas.drawPath(path, ring);
            }
            int s = AndroidUtilities.dp(20);
            icon.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
            icon.draw(canvas);
            if (playing && fancy || Math.abs(progress - shown) > 0.001f) {
                PrizmaConfig.nextDecorFrame(this);
            }
        }
    }
}
