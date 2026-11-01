package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

/**
 * Prizma: a floating "Dynamic Island" style now-playing pill. It's much larger and easier to
 * find than Telegram's thin default now-playing bar, and tapping it jumps straight back into the
 * full-screen player. Appears whenever music is playing and tucks itself away otherwise.
 */
public class PrizmaNowPlayingIsland extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int ACCENT = 0xffFF4D6D;

    private final LinearLayout pill;
    private final BackupImageView cover;
    private final TextView title;
    private final TextView author;
    private final ImageView playButton;
    private final PlayPauseDrawable playPause;
    private final ProgressLine progressLine;

    private int boundMsgId = Integer.MIN_VALUE;
    private boolean shown;

    // drag state (user can move the island anywhere; position is persisted)
    private float offX, offY;
    private float downRawX, downRawY, startOffX, startOffY;
    private boolean dragging;
    private final int touchSlop;

    public PrizmaNowPlayingIsland(Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        offX = PrizmaConfig.islandOffX();
        offY = PrizmaConfig.islandOffY();

        pill = new LinearLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                super.dispatchDraw(canvas);
                progressLine.drawOn(canvas, getWidth(), getHeight());
            }
        };
        pill.setWillNotDraw(false);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(26));
        bg.setColor(0xE6000000);
        pill.setBackground(bg);
        pill.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(7), AndroidUtilities.dp(10), AndroidUtilities.dp(7));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            pill.setElevation(AndroidUtilities.dpf2(9));
        }

        progressLine = new ProgressLine();

        cover = new BackupImageView(context);
        cover.setRoundRadius(AndroidUtilities.dp(12));
        pill.addView(cover, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 11, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(context);
        title.setTextColor(0xffffffff);
        title.setTextSize(14);
        title.setTypeface(AndroidUtilities.bold());
        title.setMaxLines(1);
        title.setMaxWidth(AndroidUtilities.dp(190));
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        author = new TextView(context);
        author.setTextColor(0x99ffffff);
        author.setTextSize(11);
        author.setMaxLines(1);
        author.setMaxWidth(AndroidUtilities.dp(190));
        author.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(author, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

        pill.addView(texts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playPause = new PlayPauseDrawable(20);
        playButton.setImageDrawable(playPause);
        playButton.setColorFilter(ACCENT);
        playButton.setBackground(Theme.createSelectorDrawable(0x33ffffff, 1));
        playButton.setOnClickListener(v -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(mo);
            } else {
                MediaController.getInstance().pauseMessage(mo);
            }
        });
        pill.addView(playButton, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL));

        pill.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = e.getRawX();
                    downRawY = e.getRawY();
                    startOffX = offX;
                    startOffY = offY;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downRawX;
                    float dy = e.getRawY() - downRawY;
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                        dragging = true;
                    }
                    if (dragging) {
                        offX = startOffX + dx;
                        offY = startOffY + dy;
                        clampOffsets();
                        setTranslationX(offX);
                        setTranslationY(offY);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        pill.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                                .withEndAction(() -> pill.animate().scaleX(1f).scaleY(1f)
                                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(220).start())
                                .start();
                        PrizmaFullPlayer.show(getContext());
                    } else {
                        snapToEdge();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
            }
            return false;
        });

        addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 54, Gravity.CENTER));

        setVisibility(GONE);
        setAlpha(0f);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        int acc = UserConfig.selectedAccount;
        NotificationCenter n = NotificationCenter.getInstance(acc);
        n.addObserver(this, NotificationCenter.messagePlayingDidStart);
        n.addObserver(this, NotificationCenter.messagePlayingDidReset);
        n.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        n.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        refresh(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        int acc = UserConfig.selectedAccount;
        NotificationCenter n = NotificationCenter.getInstance(acc);
        n.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        n.removeObserver(this, NotificationCenter.messagePlayingDidReset);
        n.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        n.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingProgressDidChanged) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo != null && shown) {
                progressLine.setProgress(mo.audioProgress);
                pill.invalidate();
            }
        } else {
            refresh(true);
        }
    }

    /** Re-reads the player state and shows/updates/hides the island. */
    public void refresh(boolean animate) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        boolean shouldShow = mo != null && mo.isMusic();
        if (!shouldShow) {
            hide(animate);
            return;
        }
        if (mo.getId() != boundMsgId) {
            boundMsgId = mo.getId();
            bind(mo);
        }
        playPause.setPause(!MediaController.getInstance().isMessagePaused(), shown);
        progressLine.setProgress(mo.audioProgress);
        pill.invalidate();
        show(animate);
    }

    private void bind(MessageObject mo) {
        String t = mo.getMusicTitle();
        String a = mo.getMusicAuthor();
        title.setText(t == null || t.isEmpty() ? "Неизвестный трек" : t);
        author.setText(a == null ? "" : a);
        loadCover(mo);
    }

    private void loadCover(MessageObject mo) {
        Bitmap bmp = null;
        try {
            if (MediaController.getInstance().getAudioInfo() != null) {
                bmp = MediaController.getInstance().getAudioInfo().getCover();
            }
        } catch (Throwable ignore) {}
        if (bmp != null) {
            cover.setImageBitmap(bmp);
            return;
        }
        String artworkUrl = mo.getArtworkUrl(true);
        ImageLocation thumb = thumbLocation(mo);
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            cover.setImage(ImageLocation.getForPath(artworkUrl), "40_40", thumb, null, null, 0, 1, mo);
        } else if (thumb != null) {
            cover.setImage(null, null, thumb, null, null, 0, 1, mo);
        } else {
            GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff2a2d3a, 0xff15161c});
            ph.setCornerRadius(AndroidUtilities.dp(12));
            cover.setImageDrawable(ph);
        }
    }

    private ImageLocation thumbLocation(MessageObject mo) {
        TLRPC.Document doc = mo.getDocument();
        TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 120) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        return thumb != null ? ImageLocation.getForDocument(thumb, doc) : null;
    }

    /** After a drag, glide the island to the nearest side edge so it sits neatly out of the way. */
    private void snapToEdge() {
        View parent = (View) getParent();
        if (parent == null || getWidth() == 0) {
            PrizmaConfig.setIslandOffset(offX, offY);
            return;
        }
        clampOffsets();
        float inset = AndroidUtilities.dp(10);
        float leftTarget = inset - getLeft();
        float rightTarget = parent.getWidth() - inset - getRight();
        float center = getLeft() + getWidth() / 2f + offX;
        offX = (center < parent.getWidth() / 2f) ? leftTarget : rightTarget;
        clampOffsets();
        animate().translationX(offX).setDuration(240)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        PrizmaConfig.setIslandOffset(offX, offY);
    }

    private void clampOffsets() {
        View parent = (View) getParent();
        if (parent == null || getWidth() == 0) {
            return;
        }
        float minX = -getLeft();
        float maxX = parent.getWidth() - getRight();
        float minY = -getTop();
        float maxY = parent.getHeight() - getBottom();
        offX = minX > maxX ? 0 : Math.max(minX, Math.min(maxX, offX));
        offY = minY > maxY ? 0 : Math.max(minY, Math.min(maxY, offY));
    }

    private void show(boolean animate) {
        if (shown && getVisibility() == VISIBLE) {
            return;
        }
        shown = true;
        animate().cancel();
        setVisibility(VISIBLE);
        clampOffsets();
        if (!animate) {
            setAlpha(1f);
            setTranslationX(offX);
            setTranslationY(offY);
            setScaleX(1f);
            setScaleY(1f);
            return;
        }
        setAlpha(0f);
        setTranslationX(offX);
        setTranslationY(offY + AndroidUtilities.dp(24));
        setScaleX(0.9f);
        setScaleY(0.9f);
        animate().alpha(1f).translationX(offX).translationY(offY).scaleX(1f).scaleY(1f)
                .setDuration(320)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .start();
    }

    private void hide(boolean animate) {
        if (!shown && getVisibility() != VISIBLE) {
            return;
        }
        shown = false;
        boundMsgId = Integer.MIN_VALUE;
        animate().cancel();
        if (!animate) {
            setVisibility(GONE);
            setAlpha(0f);
            return;
        }
        animate().alpha(0f).translationY(offY + AndroidUtilities.dp(24)).scaleX(0.9f).scaleY(0.9f)
                .setDuration(220)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .withEndAction(() -> setVisibility(GONE))
                .start();
    }

    /** Thin accent progress line along the bottom edge of the pill. */
    private static class ProgressLine {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private float progress;

        ProgressLine() {
            paint.setColor(ACCENT);
        }

        void setProgress(float p) {
            progress = p < 0 ? 0 : (p > 1 ? 1 : p);
        }

        void drawOn(Canvas canvas, int w, int h) {
            if (progress <= 0f) return;
            float inset = AndroidUtilities.dpf2(14);
            float lineH = AndroidUtilities.dpf2(2.5f);
            float bottom = h - AndroidUtilities.dpf2(5);
            float full = w - inset * 2;
            rect.set(inset, bottom - lineH, inset + full * progress, bottom);
            canvas.drawRoundRect(rect, lineH / 2f, lineH / 2f, paint);
        }
    }
}
