package org.telegram.ui.Components;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaClips;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaLyrics;
import org.telegram.messenger.PrizmaPlaylist;
import org.telegram.messenger.PrizmaSleepTimer;
import org.telegram.messenger.PrizmaWaveform;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class PrizmaFullPlayer extends Dialog implements PrizmaQueuePanel.Host {
    private static final int PAGE_COVER = 0, PAGE_LYRICS = 1, PAGE_QUEUE = 2;
    private static final int TEXT = 0xfff2f1ee, TEXT_2 = 0xa6f2f1ee, TEXT_3 = 0x66f2f1ee;

    private static boolean open;
    private static PrizmaFullPlayer instance;

    public static boolean isOpen() {
        return open;
    }

    public static void show(Context context) {
        if (context == null || open || MediaController.getInstance().getPlayingMessageObject() == null) {
            return;
        }
        try {
            new PrizmaFullPlayer(context).show();
        } catch (Throwable ignore) {
        }
    }

    private final Context ctx;
    private DragRoot root;
    private LinearLayout column;
    private PrizmaPlayerBackdrop backdrop;
    private TextView topTitle, topSubtitle;
    private LinearLayout togetherBar;
    private TextView togetherEyebrow, togetherLabel, togetherButton;
    private String togetherKey = "";
    private FrameLayout stage;
    private LyricsPanel lyricsPanel;
    private PrizmaQueuePanel queuePanel;
    private FrameLayout coverBox, coverInner;
    private BackupImageView cover;
    private PrizmaClipView clipView;
    private FrameLayout titleRow;
    private LinearLayout titles;
    private TextView titleView, authorView;
    private HeartButton heart;
    private PrizmaSeekBars seek;
    private TextView timeCur, timeDur;
    private DotIcon shuffleButton, repeatButton;
    private PillButton prevButton, playButton, nextButton;
    private PlayPauseDrawable playPause;
    private TextView speedButton;
    private GroupButton lyricsButton, queueButton;
    private ImageView optionsButton;

    private PrizmaPalette palette = PrizmaPalette.fallback();
    private int lastSeed;
    private long loadedKey = Long.MIN_VALUE;
    private long lyricsKey = Long.MIN_VALUE;
    private long clipKey = Long.MIN_VALUE;
    private long blurKey = Long.MIN_VALUE;
    private long wavePeaksKey = Long.MIN_VALUE;
    private long waveRetryAt;
    private boolean running;
    private boolean coverWaiting;
    private boolean closing;
    private Bitmap blurBitmap, blurSource;

    private int page = PAGE_COVER;
    private float pageProgress;
    private float lyricsAlpha, queueAlpha;
    private int coverSide;
    private float bigX, bigY, smallX, smallY;
    private int smallSize;
    private boolean titlesInset;
    private PrizmaLyrics.Lyrics currentLyrics;
    private boolean lyricsAnswered;

    private final PrizmaSpring pageSpring = new PrizmaSpring(0f, 380f, 0.84f, v -> {
        pageProgress = v;
        applyPage();
    });
    private final PrizmaSpring lyricsSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 1f, v -> {
        lyricsAlpha = v;
        applyPanels();
    });
    private final PrizmaSpring queueSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 1f, v -> {
        queueAlpha = v;
        applyPanels();
    });
    private final PrizmaSpring kick = new PrizmaSpring(1f, 520f, 0.42f, v -> {
        if (coverInner != null) {
            coverInner.setScaleX(v);
            coverInner.setScaleY(v);
        }
    });
    private final PrizmaSpring swipe = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.78f, v -> {
        if (coverInner != null) {
            coverInner.setTranslationX(v);
            coverInner.setRotation(coverSide == 0 ? 0f : v / coverSide * 7f);
        }
    });

    public PrizmaFullPlayer(Context context) {
        super(context, R.style.TransparentDialog);
        this.ctx = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (org.telegram.ui.ActionBar.Theme.playPauseAnimator == null) org.telegram.ui.ActionBar.Theme.createChatResources(ctx, false);

        root = new DragRoot(ctx);
        root.setClipChildren(false);

        backdrop = new PrizmaPlayerBackdrop(ctx);
        backdrop.beatSink = this::onBeat;
        root.addView(backdrop, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(AndroidUtilities.dp(20), AndroidUtilities.statusBarHeight, AndroidUtilities.dp(20), AndroidUtilities.dp(12) + AndroidUtilities.navigationBarHeight);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        column.addView(buildTopBar(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        column.addView(buildTogetherBar(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        stage = new FrameLayout(ctx);
        lyricsPanel = new LyricsPanel(ctx);
        lyricsPanel.setVisibility(View.GONE);
        stage.addView(lyricsPanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        queuePanel = new PrizmaQueuePanel(ctx, this);
        queuePanel.setVisibility(View.GONE);
        stage.addView(queuePanel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(stage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        column.addView(buildTitleRow(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68, 0, 8, 0, 0));

        seek = new PrizmaSeekBars(ctx);
        seek.listener = (progress, committed) -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            timeCur.setText(formatTime((int) (mo.getDuration() * progress)));
            if (committed) MediaController.getInstance().seekToProgress(mo, progress);
        };
        column.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 2, 0, 0));

        FrameLayout times = new FrameLayout(ctx);
        timeCur = timeLabel();
        timeDur = timeLabel();
        times.addView(timeCur, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        times.addView(timeDur, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        column.addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        column.addView(buildControls(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 76, 0, 10, 0, 0));
        column.addView(buildBottomRow(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));

        coverBox = new FrameLayout(ctx);
        coverBox.setClipChildren(false);
        coverBox.setPivotX(0);
        coverBox.setPivotY(0);
        coverInner = new FrameLayout(ctx);
        cover = new BackupImageView(ctx);
        cover.setRoundRadius(AndroidUtilities.dp(28));
        coverInner.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        clipView = new PrizmaClipView(ctx);
        clipView.setRadius(AndroidUtilities.dp(28));
        coverInner.addView(clipView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        coverBox.addView(coverInner, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        attachCoverGestures();
        root.addView(coverBox, new FrameLayout.LayoutParams(AndroidUtilities.dp(200), AndroidUtilities.dp(200)));

        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setupWindow();

        column.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> root.post(this::measureStage));
        root.setTranslationY(AndroidUtilities.displaySize.y);
        applyPalette(PrizmaPalette.fallback(), false);
        updateTrack(true);
    }

    private void setupWindow() {
        Window window = getWindow();
        PrizmaGlass.fullBleed(window);
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        }
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(ctx);
        ImageView close = iconButton(R.drawable.arrow_more, TEXT);
        close.setContentDescription("Свернуть плеер");
        close.setOnClickListener(v -> dismiss());
        bar.addView(close, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, -10, 0, 0, 0));

        LinearLayout center = new LinearLayout(ctx);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        topTitle = new TextView(ctx);
        topTitle.setTextColor(TEXT);
        topTitle.setTextSize(15);
        topTitle.setTypeface(AndroidUtilities.bold());
        topTitle.setSingleLine(true);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        center.addView(topTitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        topSubtitle = new TextView(ctx);
        topSubtitle.setTextColor(TEXT_3);
        topSubtitle.setTextSize(12.5f);
        topSubtitle.setSingleLine(true);
        center.addView(topSubtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 1, 0, 0));
        center.setOnClickListener(v -> setPage(page == PAGE_QUEUE ? PAGE_COVER : PAGE_QUEUE));
        bar.addView(center, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 44, 0, 44, 0));

        optionsButton = iconButton(R.drawable.ic_ab_other, TEXT);
        optionsButton.setContentDescription("Ещё");
        PrizmaPopup.attachHold(optionsButton, this::openMenu);
        bar.addView(optionsButton, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, -10, 0));
        return bar;
    }

    private View buildTogetherBar() {
        togetherBar = new LinearLayout(ctx);
        togetherBar.setOrientation(LinearLayout.HORIZONTAL);
        togetherBar.setGravity(Gravity.CENTER_VERTICAL);
        togetherBar.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(18));
        bg.setColor(0x1fffffff);
        togetherBar.setBackground(bg);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        togetherEyebrow = new TextView(ctx);
        togetherEyebrow.setTextSize(12);
        togetherEyebrow.setTypeface(AndroidUtilities.bold());
        texts.addView(togetherEyebrow);
        togetherLabel = new TextView(ctx);
        togetherLabel.setTextColor(0xe6ffffff);
        togetherLabel.setTextSize(13.5f);
        togetherLabel.setMaxLines(1);
        togetherLabel.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(togetherLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        togetherBar.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        togetherButton = new TextView(ctx);
        togetherButton.setTextColor(0xffffffff);
        togetherButton.setTextSize(13);
        togetherButton.setTypeface(AndroidUtilities.bold());
        togetherButton.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(6), AndroidUtilities.dp(13), AndroidUtilities.dp(6));
        GradientDrawable button = new GradientDrawable();
        button.setCornerRadius(AndroidUtilities.dp(14));
        button.setColor(0x33ffffff);
        togetherButton.setBackground(button);
        togetherButton.setOnClickListener(v -> {
            PrizmaTogether t = PrizmaTogether.get();
            if (t.isHosting()) {
                t.stopHosting();
            } else if (t.isFollowing()) {
                t.leave();
            }
            updateTogetherBar();
        });
        togetherBar.addView(togetherButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        togetherBar.setVisibility(View.GONE);
        return togetherBar;
    }

    private View buildTitleRow() {
        titleRow = new FrameLayout(ctx);
        titles = new LinearLayout(ctx);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(ctx);
        titleView.setTextColor(TEXT);
        titleView.setTextSize(23);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        authorView = new TextView(ctx);
        authorView.setTextColor(TEXT_2);
        authorView.setTextSize(15.5f);
        authorView.setSingleLine(true);
        authorView.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(authorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        titleRow.addView(titles, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT, 0, 0, 52, 0));

        heart = new HeartButton(ctx);
        heart.setContentDescription("Добавить в плейлист");
        heart.setOnClickListener(v -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            PrizmaPlaylistSheet.showAddToPlaylist(ctx, root, palette.accent, mo, () -> heart.setChecked(PrizmaPlaylist.inAny(mo), true));
        });
        titleRow.addView(heart, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, -6, 0));
        return titleRow;
    }

    private View buildControls() {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);

        shuffleButton = new DotIcon(ctx, R.drawable.player_new_shuffle);
        shuffleButton.setContentDescription("Перемешать");
        shuffleButton.setOnClickListener(v -> {
            boolean on = SharedConfig.shuffleMusic;
            try {
                MediaController.getInstance().setPlaybackOrderType(on ? 0 : 2);
            } catch (Throwable ignore) {
            }
            updateShuffle();
            if (queuePanel != null) queuePanel.refresh();
            updateTopBar();
        });
        row.addView(shuffleButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        prevButton = new PillButton(ctx);
        prevButton.setContentDescription("Предыдущий трек");
        prevButton.setIcon(ctx.getResources().getDrawable(R.drawable.prizma_ic_prev).mutate());
        prevButton.setOnClickListener(v -> {
            press(v);
            prevButton.nudge(-1f);
            MediaController.getInstance().playPreviousMessage();
        });
        row.addView(prevButton, LayoutHelper.createLinear(0, 62, 1f, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

        playButton = new PillButton(ctx);
        playPause = new PlayPauseDrawable(30);
        playPause.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setIcon(playPause);
        playButton.setContentDescription("Пауза или воспроизведение");
        playButton.neighbors(prevButton, null);
        playButton.setOnClickListener(v -> {
            press(v);
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(mo);
            } else {
                MediaController.getInstance().pauseMessage(mo);
            }
        });
        row.addView(playButton, LayoutHelper.createLinear(0, 74, 1.5f, Gravity.CENTER_VERTICAL, 6, 0, 6, 0));

        nextButton = new PillButton(ctx);
        nextButton.setContentDescription("Следующий трек");
        nextButton.setIcon(ctx.getResources().getDrawable(R.drawable.prizma_ic_next).mutate());
        nextButton.setOnClickListener(v -> {
            press(v);
            nextButton.nudge(1f);
            MediaController.getInstance().playNextMessage();
        });
        playButton.neighbors(prevButton, nextButton);
        row.addView(nextButton, LayoutHelper.createLinear(0, 62, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));

        repeatButton = new DotIcon(ctx, R.drawable.player_new_repeatall);
        repeatButton.setContentDescription("Повтор");
        repeatButton.setOnClickListener(v -> {
            SharedConfig.setRepeatMode((SharedConfig.repeatMode + 1) % 3);
            updateRepeat();
        });
        row.addView(repeatButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));
        return row;
    }

    private View buildBottomRow() {
        FrameLayout row = new FrameLayout(ctx);
        speedButton = new TextView(ctx);
        speedButton.setTextColor(TEXT);
        speedButton.setTextSize(14);
        speedButton.setTypeface(AndroidUtilities.bold());
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        speedButton.setMinWidth(AndroidUtilities.dp(62));
        speedButton.setContentDescription("Скорость воспроизведения");
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(AndroidUtilities.dp(18));
        pill.setStroke(AndroidUtilities.dp(1.4f), 0x59ffffff);
        speedButton.setBackground(pill);
        speedButton.setOnClickListener(this::openSpeed);
        row.addView(speedButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout group = new LinearLayout(ctx);
        group.setOrientation(LinearLayout.HORIZONTAL);
        lyricsButton = new GroupButton(ctx, true);
        lyricsButton.setIcon(ctx.getResources().getDrawable(R.drawable.prizma_ic_lyrics).mutate());
        lyricsButton.setContentDescription("Текст песни");
        lyricsButton.setOnClickListener(v -> setPage(page == PAGE_LYRICS ? PAGE_COVER : PAGE_LYRICS));
        group.addView(lyricsButton, LayoutHelper.createLinear(70, 48));
        queueButton = new GroupButton(ctx, false);
        queueButton.setIcon(ctx.getResources().getDrawable(R.drawable.prizma_ic_queue).mutate());
        queueButton.setContentDescription("Очередь");
        queueButton.setOnClickListener(v -> setPage(page == PAGE_QUEUE ? PAGE_COVER : PAGE_QUEUE));
        group.addView(queueButton, LayoutHelper.createLinear(70, 48, 3, 0, 0, 0));
        row.addView(group, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        return row;
    }

    private ImageView iconButton(int icon, int color) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(icon);
        v.setColorFilter(color);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(org.telegram.ui.ActionBar.Theme.createSelectorDrawable(0x22ffffff, 1, AndroidUtilities.dp(22)));
        return v;
    }

    private TextView timeLabel() {
        TextView t = new TextView(ctx);
        t.setTextColor(TEXT_2);
        t.setTextSize(12);
        t.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        return t;
    }

    private static void press(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void measureStage() {
        if (root == null || stage == null) return;
        int w = root.getWidth(), stageH = stage.getHeight();
        if (w == 0 || stageH == 0) return;
        float scale = Math.max(0.6f, Math.min(1f, PrizmaConfig.playerCoverScale()));
        int base = Math.min(w - AndroidUtilities.dp(40), stageH - AndroidUtilities.dp(10));
        int side = Math.max(AndroidUtilities.dp(120), (int) (base * scale));
        if (side != coverSide) {
            coverSide = side;
            ViewGroup.LayoutParams lp = coverBox.getLayoutParams();
            lp.width = side;
            lp.height = side;
            coverBox.setLayoutParams(lp);
            coverInner.setPivotX(side / 2f);
            coverInner.setPivotY(side / 2f);
            cover.setCameraDistance(ctx.getResources().getDisplayMetrics().density * 1800);
        }
        smallSize = AndroidUtilities.dp(54);
        bigX = (w - side) / 2f;
        bigY = stage.getTop() + (stageH - side) / 2f;
        smallX = AndroidUtilities.dp(20);
        smallY = titleRow.getTop() + (titleRow.getHeight() - smallSize) / 2f;
        applyPage();
    }

    private void applyPage() {
        if (coverSide == 0) return;
        float p = pageProgress;
        float scale = AndroidUtilities.lerp(1f, smallSize / (float) coverSide, p);
        float x = AndroidUtilities.lerp(bigX, smallX, p);
        float y = AndroidUtilities.lerp(bigY, smallY, p);
        coverBox.setScaleX(scale);
        coverBox.setScaleY(scale);
        coverBox.setTranslationX(x);
        coverBox.setTranslationY(y);
        float clamped = Math.max(0f, Math.min(1f, p));
        int radius = (int) (AndroidUtilities.lerp(AndroidUtilities.dpf2(28), AndroidUtilities.dpf2(14), clamped) / Math.max(0.05f, scale));
        cover.setRoundRadius(radius);
        clipView.setRadius(radius);

        float inset = smallSize + AndroidUtilities.dp(12);
        boolean wantInset = page != PAGE_COVER;
        if (wantInset && !titlesInset) {
            titlesInset = true;
            titles.setPadding((int) inset, 0, 0, 0);
        } else if (!wantInset && titlesInset && p < 0.004f) {
            titlesInset = false;
            titles.setPadding(0, 0, 0, 0);
        }
        titles.setTranslationX(titlesInset ? (clamped - 1f) * inset : 0f);

        float side = coverSide * scale;
        backdrop.setCoverRect(x, y, x + side, y + side, AndroidUtilities.dpf2(28) * Math.max(0.3f, 1f - clamped), clamped < 0.2f);
    }

    private void applyPanels() {
        fadePanel(lyricsPanel, lyricsAlpha);
        fadePanel(queuePanel, queueAlpha);
    }

    private static void fadePanel(View panel, float a) {
        if (panel == null) return;
        float v = Math.max(0f, Math.min(1f, a));
        panel.setAlpha(v);
        panel.setTranslationY((1f - v) * AndroidUtilities.dp(20));
        panel.setVisibility(v <= 0.01f ? View.GONE : View.VISIBLE);
    }

    private void setPage(int next) {
        if (next == page) return;
        page = next;
        if (next == PAGE_QUEUE) {
            queuePanel.refresh();
            queuePanel.scrollToStart();
        } else {
            queuePanel.hideKeyboard();
        }
        if (next == PAGE_LYRICS) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo != null) loadLyrics(mo);
        }
        pageSpring.to(next == PAGE_COVER ? 0f : 1f);
        lyricsSpring.to(next == PAGE_LYRICS ? 1f : 0f);
        queueSpring.to(next == PAGE_QUEUE ? 1f : 0f);
        lyricsButton.setSelectedState(next == PAGE_LYRICS);
        queueButton.setSelectedState(next == PAGE_QUEUE);
        applyPage();
    }

    @SuppressWarnings("ClickableViewAccessibility")
    private void attachCoverGestures() {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        final float[] down = new float[2];
        final boolean[] moving = new boolean[1];
        coverInner.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = e.getRawX();
                    down[1] = e.getRawY();
                    moving[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (page != PAGE_COVER) return true;
                    float dx = e.getRawX() - down[0], dy = e.getRawY() - down[1];
                    if (!moving[0] && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                        moving[0] = true;
                        coverInner.getParent().requestDisallowInterceptTouchEvent(true);
                        root.requestDisallowInterceptTouchEvent(true);
                    }
                    if (moving[0]) {
                        swipe.snap(dx * 0.72f);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - down[0];
                    if (page != PAGE_COVER) {
                        setPage(PAGE_COVER);
                    } else if (moving[0] && Math.abs(dx) > coverSide * 0.26f) {
                        if (dx < 0) {
                            MediaController.getInstance().playNextMessage();
                        } else {
                            MediaController.getInstance().playPreviousMessage();
                        }
                        AndroidUtilities.runOnUIThread(() -> swipe.to(0f), 320);
                    } else {
                        swipe.to(0f);
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    swipe.to(0f);
                    return true;
            }
            return false;
        });
    }

    private void onBeat(float strength) {
        if (!PrizmaConfig.beatEffects()) return;
        if (PrizmaConfig.playerCoverKick() && page == PAGE_COVER) {
            kick.snap(1f + 0.032f * strength);
            kick.to(1f);
        }
        if (lyricsPanel != null && page == PAGE_LYRICS) {
            lyricsPanel.view.bump(strength);
        }
        if (PrizmaConfig.playerHaptics() && strength > 0.4f) {
            try {
                root.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Throwable ignore) {
            }
        }
    }

    @Override
    public void show() {
        super.show();
        open = true;
        instance = this;
        running = true;
        AndroidUtilities.runOnUIThread(ticker, 60);
        startParallax();
        root.post(() -> {
            root.setTranslationY(root.getHeight() == 0 ? AndroidUtilities.displaySize.y : root.getHeight());
            root.settle();
        });
    }

    @Override
    public void onBackPressed() {
        if (PrizmaPopup.closeIfOpen() || PrizmaPlayerSettings.closeIfOpen()) return;
        if (page != PAGE_COVER) {
            setPage(PAGE_COVER);
            return;
        }
        dismiss();
    }

    @Override
    public void dismiss() {
        if (closing) return;
        closing = true;
        release();
        if (root == null) {
            realDismiss();
            return;
        }
        root.animate().cancel();
        root.animate().translationY(root.getHeight()).setDuration(230)
                .setInterpolator(CubicBezierInterpolator.EASE_IN)
                .withEndAction(this::realDismiss).start();
    }

    private void realDismiss() {
        try {
            super.dismiss();
        } catch (Throwable ignore) {
        }
    }

    private void release() {
        open = false;
        running = false;
        if (instance == this) instance = null;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        stopParallax();
        PrizmaPlayerSettings.closeIfOpen();
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!ApplicationLoader.isScreenOn || ApplicationLoader.mainInterfacePaused) {
                if (parallaxSensors != null) {
                    stopParallax();
                    parallaxPaused = true;
                }
                clipView.setPaused(true);
                AndroidUtilities.runOnUIThread(this, 500);
                return;
            }
            if (parallaxPaused) {
                parallaxPaused = false;
                startParallax();
            }
            updateTrack(false);
            AndroidUtilities.runOnUIThread(this, page == PAGE_LYRICS ? 33 : 66);
        }
    };

    private android.hardware.SensorManager parallaxSensors;
    private boolean parallaxPaused;
    private boolean parallaxBaseSet;
    private float parallaxBaseX, parallaxBaseY, parallaxRx, parallaxRy;

    private final android.hardware.SensorEventListener parallaxListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            if (cover == null) return;
            float gx = e.values[0], gy = e.values[1];
            if (!parallaxBaseSet) {
                parallaxBaseX = gx;
                parallaxBaseY = gy;
                parallaxBaseSet = true;
            }
            float dx = deadZone(gx - parallaxBaseX), dy = deadZone(gy - parallaxBaseY);
            float wantY = Math.max(-10f, Math.min(10f, -dx * 1.6f));
            float wantX = Math.max(-10f, Math.min(10f, dy * 1.6f));
            parallaxRy += (wantY - parallaxRy) * 0.08f;
            parallaxRx += (wantX - parallaxRx) * 0.08f;
            float k = 1f - Math.max(0f, Math.min(1f, pageProgress));
            coverInner.setRotationX(parallaxRx * k);
            coverInner.setRotationY(parallaxRy * k);
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {
        }
    };

    private static float deadZone(float v) {
        if (v > 0.35f) return v - 0.35f;
        if (v < -0.35f) return v + 0.35f;
        return 0f;
    }

    private void startParallax() {
        try {
            if (!PrizmaConfig.fancyEffects() || !PrizmaConfig.coverParallax() || parallaxSensors != null) return;
            parallaxSensors = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (parallaxSensors != null) {
                android.hardware.Sensor s = parallaxSensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                if (s != null) {
                    parallaxBaseSet = false;
                    parallaxSensors.registerListener(parallaxListener, s, android.hardware.SensorManager.SENSOR_DELAY_GAME);
                }
            }
        } catch (Throwable ignore) {
        }
    }

    private void stopParallax() {
        try {
            if (parallaxSensors != null) parallaxSensors.unregisterListener(parallaxListener);
        } catch (Throwable ignore) {
        }
        parallaxSensors = null;
        if (coverInner != null) {
            coverInner.setRotationX(0);
            coverInner.setRotationY(0);
        }
    }

    private void updateTrack(boolean force) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) {
            dismiss();
            return;
        }
        long key = trackKey(mo);
        if (force || key != loadedKey) {
            boolean animate = !force && loadedKey != Long.MIN_VALUE;
            loadedKey = key;
            if (animate) {
                animateTrackChange(mo);
            } else {
                applyTrack(mo);
            }
        }
        checkLateCover();
        tryBuildBlur(mo);
        updateTogetherBar();
        checkWave(mo);
        checkTagLyrics(mo);

        boolean paused = MediaController.getInstance().isMessagePaused();
        playPause.setPause(!paused, true);
        playButton.setRounded(paused);
        clipView.setPaused(paused);
        if (!seek.isDragging()) {
            seek.setValue(mo.audioProgress);
            timeCur.setText(formatTime(mo.audioProgressSec));
        }
        float speed = MediaController.getInstance().getPlaybackSpeed(true);
        String label = formatSpeed(speed) + "x";
        if (!label.contentEquals(speedButton.getText())) speedButton.setText(label);
        if (page == PAGE_LYRICS) {
            lyricsPanel.view.setPosition((long) (mo.audioProgress * mo.getDuration() * 1000) + 220);
        }
    }

    private void applyTrack(MessageObject mo) {
        loadCover(mo);
        String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
        titleView.setText(TextUtils.isEmpty(t) ? "Неизвестный трек" : t);
        authorView.setText(a == null ? "" : a);
        int dur = (int) mo.getDuration();
        timeDur.setText(formatTime(dur));
        seek.setDuration(dur);
        seek.setPeaks(null, false);
        wavePeaksKey = Long.MIN_VALUE;
        waveRetryAt = 0;
        heart.setChecked(PrizmaPlaylist.inAny(mo), false);
        updateTopBar();
        updateShuffle();
        updateRepeat();
        if (page == PAGE_QUEUE) queuePanel.refresh();
        loadLyrics(mo);
        loadClip(mo);
    }

    private void updateTopBar() {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) return;
        topTitle.setText(sourceName(mo));
        int total = MediaController.getInstance().getPlaylist().size();
        int position = MediaController.getInstance().prizmaQueuePosition();
        topSubtitle.setText(total > 1 && position > 0 ? "Трек " + position + " из " + total : "Один трек");
    }

    private String sourceName(MessageObject mo) {
        String playlist = PrizmaPlaylist.playingName(mo);
        if (playlist != null) return playlist;
        try {
            long did = mo.getDialogId();
            MessagesController mc = MessagesController.getInstance(mo.currentAccount);
            if (DialogObject.isUserDialog(did)) {
                TLRPC.User u = mc.getUser(did);
                if (u != null) return org.telegram.messenger.UserObject.getUserName(u);
            } else {
                TLRPC.Chat c = mc.getChat(-did);
                if (c != null) return c.title;
            }
        } catch (Throwable ignore) {
        }
        return "Сейчас играет";
    }

    private void animateTrackChange(MessageObject mo) {
        final View[] texts = {titleView, authorView};
        final float dir = coverInner.getTranslationX() > AndroidUtilities.dp(1) ? 1f : -1f;
        final float off = coverSide * 0.5f;
        for (View v : texts) {
            v.animate().cancel();
            v.animate().alpha(0f).translationY(-AndroidUtilities.dp(8)).setDuration(130).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        coverBox.animate().cancel();
        coverBox.animate().alpha(page == PAGE_COVER ? 0f : 0.4f).setDuration(150).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            applyTrack(mo);
            swipe.snap(-dir * off);
            coverBox.animate().alpha(1f).setDuration(240).withEndAction(null).start();
            swipe.to(0f);
            for (View v : texts) {
                v.setTranslationY(AndroidUtilities.dp(10));
                v.animate().alpha(1f).translationY(0f).setDuration(280).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        }).start();
    }

    private void loadCover(MessageObject mo) {
        Bitmap bmp = audioInfoCover();
        if (bmp != null) {
            coverWaiting = false;
            cover.setImageBitmap(bmp);
            return;
        }
        coverWaiting = true;
        String artwork = mo.getArtworkUrl(false);
        ImageLocation thumb = PrizmaTrackCover.thumb(mo, 360);
        Drawable placeholder = coverPlaceholder(mo);
        if (artwork != null && !artwork.isEmpty()) {
            cover.setImage(ImageLocation.getForPath(artwork), null, thumb, null, placeholder, null, 0, 1, mo);
        } else if (thumb != null) {
            cover.setImage(null, null, thumb, null, placeholder, null, 0, 1, mo);
        } else {
            cover.setImageDrawable(placeholder);
        }
    }

    private void checkLateCover() {
        if (!coverWaiting) return;
        if (cover.getImageReceiver() != null && cover.getImageReceiver().hasImageLoaded() && cover.getImageReceiver().getBitmap() != null) {
            coverWaiting = false;
            return;
        }
        Bitmap bmp = audioInfoCover();
        if (bmp != null) {
            coverWaiting = false;
            cover.setImageBitmap(bmp);
        }
    }

    private static Bitmap audioInfoCover() {
        try {
            if (MediaController.getInstance().getAudioInfo() != null) {
                Bitmap b = MediaController.getInstance().getAudioInfo().getCover();
                return b != null && !b.isRecycled() ? b : null;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private Drawable coverPlaceholder(MessageObject mo) {
        Drawable bg = PrizmaTrackCover.placeholder(mo, AndroidUtilities.dp(16));
        Drawable note = ctx.getResources().getDrawable(R.drawable.prizma_ic_music).mutate();
        note.setColorFilter(new android.graphics.PorterDuffColorFilter(0x66ffffff, android.graphics.PorterDuff.Mode.SRC_IN));
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(new Drawable[]{bg, note});
        int inset = AndroidUtilities.dp(84);
        layers.setLayerInset(1, inset, inset, inset, inset);
        return layers;
    }

    private void tryBuildBlur(MessageObject mo) {
        long key = trackKey(mo);
        if (blurKey == key && blurBitmap != null) return;
        Bitmap src = null;
        try {
            if (cover.getImageReceiver() != null) src = cover.getImageReceiver().getBitmap();
            if (src == null) src = audioInfoCover();
        } catch (Throwable ignore) {
        }
        if (src == null || src.isRecycled() || src == blurSource) return;
        try {
            Bitmap small = Bitmap.createScaledBitmap(src, 56, 56, true).copy(Bitmap.Config.ARGB_8888, true);
            Utilities.stackBlurBitmap(small, 4);
            Bitmap old = blurBitmap;
            blurBitmap = small;
            blurSource = src;
            blurKey = key;
            backdrop.setCoverBitmaps(small, src);
            if (old != null && !old.isRecycled()) old.recycle();
            lastSeed = PrizmaPalette.seedOf(src);
            refreshPalette(true);
        } catch (Throwable ignore) {
        }
    }

    private void refreshPalette(boolean animated) {
        PrizmaPalette next;
        if (!PrizmaConfig.coverColorsBig() || lastSeed == 0) {
            next = PrizmaPalette.neutral();
        } else {
            next = PrizmaPalette.of(lastSeed);
        }
        applyPalette(next, animated);
    }

    private void applyPalette(PrizmaPalette p, boolean animated) {
        palette = p;
        backdrop.setPalette(p, animated);
        seek.setColors(p.accent, p.onAccent);
        playButton.setColors(p.accent, p.onAccent, animated);
        playPause.setColor(p.onAccent);
        prevButton.setColors(p.container, p.onContainer, animated);
        nextButton.setColors(p.container, p.onContainer, animated);
        lyricsButton.setColors(p.accent, p.onAccent, p.container, p.onContainer);
        queueButton.setColors(p.accent, p.onAccent, p.container, p.onContainer);
        heart.setAccent(p.accent);
        shuffleButton.setAccent(p.accent);
        repeatButton.setAccent(p.accent);
        queuePanel.setPalette(p);
        lyricsPanel.setPalette(p);
        updateShuffle();
        updateRepeat();
    }

    private void checkWave(MessageObject mo) {
        long key = trackKey(mo);
        if (wavePeaksKey == key) return;
        long now = System.currentTimeMillis();
        if (now < waveRetryAt) return;
        waveRetryAt = now + 2500;
        PrizmaWaveform.load(mo, peaks -> {
            if (!running || loadedKey != key) return;
            wavePeaksKey = key;
            seek.setPeaks(peaks, true);
        });
    }

    private void checkTagLyrics(MessageObject mo) {
        if (currentLyrics != null || page != PAGE_LYRICS) return;
        try {
            if (MediaController.getInstance().getAudioInfo() == null) return;
            PrizmaLyrics.Lyrics tags = PrizmaLyrics.fromTags(MediaController.getInstance().getAudioInfo().getLyrics());
            if (tags != null) {
                currentLyrics = tags;
                lyricsPanel.showLines(tags);
            }
        } catch (Throwable ignore) {
        }
    }

    private void loadLyrics(MessageObject mo) {
        long key = trackKey(mo);
        if (lyricsKey == key && lyricsAnswered) return;
        lyricsKey = key;
        lyricsAnswered = false;
        currentLyrics = null;
        if (!PrizmaConfig.lyricsOnline()) {
            if (PrizmaConfig.lyricsAsked()) {
                lyricsPanel.showOff();
            } else {
                lyricsPanel.showAsk();
            }
            return;
        }
        lyricsPanel.showLoading();
        PrizmaLyrics.fetch(mo.getMusicTitle(), mo.getMusicAuthor(), (int) mo.getDuration(), result -> {
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            if (!running || cur == null || trackKey(cur) != key) return;
            lyricsAnswered = true;
            if (result == null) {
                lyricsPanel.showNone();
            } else if (result.instrumental) {
                lyricsPanel.showInstrumental();
            } else {
                currentLyrics = result;
                lyricsPanel.showLines(result);
            }
        });
    }

    private void forceLyrics() {
        lyricsKey = Long.MIN_VALUE;
        lyricsAnswered = false;
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo != null) loadLyrics(mo);
    }

    private void loadClip(MessageObject mo) {
        long key = trackKey(mo);
        if (clipKey == key) return;
        clipKey = key;
        if (PrizmaConfig.liteMode()) {
            clipView.hide();
            return;
        }
        clipView.hide();
        PrizmaClips.find(mo, clip -> {
            if (!running || clipKey != key) return;
            if (clip != null) clipView.show(clip.file);
        });
    }

    private void onSettingsChanged() {
        refreshPalette(true);
        measureStage();
        seek.invalidate();
        lyricsPanel.view.invalidate();
        backdrop.invalidate();
        if (PrizmaConfig.coverParallax()) startParallax();
        else stopParallax();
        clipKey = Long.MIN_VALUE;
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo != null) {
            loadClip(mo);
            if (currentLyrics == null) forceLyrics();
        }
    }

    private void updateShuffle() {
        if (shuffleButton != null) shuffleButton.setActive(SharedConfig.shuffleMusic);
    }

    private void updateRepeat() {
        if (repeatButton == null) return;
        int m = SharedConfig.repeatMode;
        repeatButton.setIcon(m == 2 ? R.drawable.player_new_repeatone : R.drawable.player_new_repeatall);
        repeatButton.setActive(m != 0);
    }

    private void updateTogetherBar() {
        PrizmaTogether t = PrizmaTogether.get();
        boolean hosting = t.isHosting(), following = t.isFollowing();
        String key;
        if (hosting) {
            key = "H" + t.getHostChatName() + "|" + t.audienceNames();
        } else if (following) {
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            boolean waiting = cur == null || cur.getId() != t.getGuestMsgId();
            key = "G" + t.getGuestHostName() + "|" + t.getAudience().size() + (waiting ? "|w" : "");
        } else {
            key = "";
        }
        if (key.equals(togetherKey)) return;
        togetherKey = key;
        if (key.isEmpty()) {
            togetherBar.setVisibility(View.GONE);
        } else if (hosting) {
            String name = t.getHostChatName();
            togetherEyebrow.setText("Вы ведёте эфир");
            togetherEyebrow.setTextColor(0xffff7a70);
            String who = t.audienceNames();
            togetherLabel.setText(!who.isEmpty() ? "Слушают: " + who : (name.isEmpty() ? "" : "Чат: " + name + " · ") + "пока никто не подключился");
            togetherButton.setText("Стоп");
            togetherBar.setVisibility(View.VISIBLE);
        } else {
            String name = t.getGuestHostName();
            togetherEyebrow.setText("Слушаете вместе");
            togetherEyebrow.setTextColor(palette.accent);
            int others = t.getAudience().size();
            togetherLabel.setText((name.isEmpty() ? "Эфир" : "Ведёт: " + name)
                    + (key.endsWith("|w") ? " · ждём следующий трек…" : others > 0 ? " · с вами ещё " + others : " · синхронно"));
            togetherButton.setText("Выйти");
            togetherBar.setVisibility(View.VISIBLE);
        }
    }

    private int popupSurface() {
        return PrizmaPalette.mix(palette.surface, 0xffffffff, 0.05f);
    }

    private PrizmaPopup openMenu() {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        PrizmaPopup popup = new PrizmaPopup(root, popupSurface(), TEXT);
        if (mo != null) {
            popup.item(R.drawable.msg_forward, "Переслать", () -> forward(mo));
            popup.item(R.drawable.msg_share, "Поделиться", () -> share(mo));
            popup.item(R.drawable.msg_download, "Сохранить в Музыку", () -> saveToMusic(mo));
            popup.item(R.drawable.msg_openin, "Открыть в чате", () -> openInChat(mo));
        }
        popup.item(R.drawable.prizma_ic_music, "Моя музыка", () -> PrizmaPlaylistSheet.show(ctx));
        popup.item(R.drawable.media_settings, "Эквалайзер", () -> PrizmaEqualizerSheet.show(ctx, root, palette.accent));
        popup.more(R.drawable.msg_autodelete, PrizmaSleepTimer.isActive() ? "Таймер сна включён" : "Таймер сна", () -> popup.replaceWith(this::fillSleep));
        addTogether(popup);
        if (mo != null) {
            final int account = mo.currentAccount;
            final boolean saved = PrizmaProfileMusic.isSaved(account, mo);
            Runnable toggle = () -> PrizmaProfileMusic.toggle(account, mo, text -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
            if (saved) {
                popup.danger(R.drawable.msg_delete, "Убрать из профиля", toggle);
            } else {
                popup.item(R.drawable.left_status_profile, "Добавить в профиль", toggle);
            }
            popup.divider();
            popup.more(R.drawable.msg_info, "Детали", () -> popup.replaceWith(p -> fillDetails(p, mo)));
            popup.item(R.drawable.prizma_ic_clip, PrizmaClips.hasOwn(mo) ? "Убрать свой клип" : "Свой клип к треку", () -> toggleOwnClip(mo));
        } else {
            popup.divider();
        }
        popup.item(R.drawable.msg_settings, "Настройки плеера", () -> PrizmaPlayerSettings.show(root, palette, this::onSettingsChanged));
        popup.showAt(optionsButton, true);
        return popup;
    }

    private void addTogether(PrizmaPopup popup) {
        final PrizmaTogether together = PrizmaTogether.get();
        if (together.isHosting()) {
            popup.item(R.drawable.msg_leave, "Завершить эфир", together::stopHosting);
        } else if (together.isFollowing()) {
            popup.item(R.drawable.msg_leave, "Выйти из эфира", together::leave);
        } else {
            popup.item(R.drawable.msg_groups, "Слушать вместе", () -> {
                dismiss();
                PrizmaTogether.pickChatAndStart(ctx);
            });
        }
    }

    private void fillSleep(PrizmaPopup p) {
        for (int m : new int[]{15, 30, 45, 60}) {
            final int minutes = m;
            p.item(R.drawable.msg_autodelete, "Через " + m + " мин", () -> {
                PrizmaSleepTimer.schedule(minutes);
                Toast.makeText(ctx, "Таймер сна: " + minutes + " мин", Toast.LENGTH_SHORT).show();
            });
        }
        if (PrizmaSleepTimer.isActive()) {
            p.danger(R.drawable.msg_delete, "Выключить таймер", PrizmaSleepTimer::cancel);
        }
    }

    private void fillDetails(PrizmaPopup p, MessageObject mo) {
        TLRPC.Document doc = mo.getDocument();
        int seconds = Math.max(1, (int) mo.getDuration());
        p.note("Длительность", formatTime(seconds));
        if (doc != null) {
            String mime = doc.mime_type == null ? "" : doc.mime_type.replace("audio/", "").toUpperCase();
            if (!mime.isEmpty()) p.note("Формат", mime);
            p.note("Размер", AndroidUtilities.formatFileSize(doc.size));
            p.note("Битрейт", (doc.size * 8 / 1000 / seconds) + " кбит/с");
        }
        p.note("Откуда", sourceName(mo));
    }

    private void openSpeed(View anchor) {
        PrizmaPopup popup = new PrizmaPopup(root, popupSurface(), TEXT);
        float cur = MediaController.getInstance().getPlaybackSpeed(true);
        for (float s : new float[]{0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f}) {
            final float value = s;
            popup.check(R.drawable.msg_speed, formatSpeed(s) + "x", Math.abs(cur - s) < 0.025f,
                    () -> MediaController.getInstance().setPlaybackSpeed(true, value));
        }
        popup.showAt(anchor, false);
    }

    private void forward(MessageObject mo) {
        try {
            ArrayList<MessageObject> list = new ArrayList<>();
            list.add(mo);
            new ShareAlert(ctx, list, null, false, null, false).show();
        } catch (Throwable ignore) {
        }
    }

    private File trackFile(MessageObject mo) {
        File f = null;
        if (!TextUtils.isEmpty(mo.messageOwner.attachPath)) {
            f = new File(mo.messageOwner.attachPath);
            if (!f.exists()) f = null;
        }
        if (f == null) f = FileLoader.getInstance(mo.currentAccount).getPathToMessage(mo.messageOwner);
        return f;
    }

    private void share(MessageObject mo) {
        try {
            File f = trackFile(mo);
            if (f == null || !f.exists()) {
                Toast.makeText(ctx, "Трек ещё не скачался", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(mo.getMimeType());
            intent.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(ApplicationLoader.applicationContext, ApplicationLoader.getApplicationId() + ".provider", f));
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Activity activity = AndroidUtilities.findActivity(ctx);
            if (activity != null) activity.startActivity(Intent.createChooser(intent, "Поделиться треком"));
        } catch (Throwable ignore) {
        }
    }

    private void saveToMusic(MessageObject mo) {
        Activity activity = AndroidUtilities.findActivity(ctx);
        if (activity == null) return;
        if (Build.VERSION.SDK_INT <= 28 || BuildVars.NO_SCOPED_STORAGE) {
            if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4);
                return;
            }
        }
        File f = trackFile(mo);
        if (f == null || !f.exists()) {
            Toast.makeText(ctx, "Трек ещё не скачался", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = FileLoader.getDocumentFileName(mo.getDocument());
        if (TextUtils.isEmpty(name)) name = mo.getFileName();
        MediaController.saveFile(f.getAbsolutePath(), activity, 3, name, mo.getDocument() != null ? mo.getDocument().mime_type : "",
                uri -> Toast.makeText(ctx, "Сохранено в Музыку", Toast.LENGTH_SHORT).show());
    }

    private void openInChat(MessageObject mo) {
        BaseFragment fragment = LaunchActivity.getSafeLastFragment();
        if (fragment == null) return;
        Bundle args = new Bundle();
        long did = mo.getDialogId();
        if (DialogObject.isEncryptedDialog(did)) {
            args.putInt("enc_id", DialogObject.getEncryptedChatId(did));
        } else if (DialogObject.isUserDialog(did)) {
            args.putLong("user_id", did);
        } else {
            args.putLong("chat_id", -did);
        }
        args.putInt("message_id", mo.getId());
        dismiss();
        fragment.presentFragment(new ChatActivity(args));
    }

    private void toggleOwnClip(MessageObject mo) {
        if (PrizmaClips.hasOwn(mo)) {
            PrizmaClips.clearOwn(mo);
            clipKey = Long.MIN_VALUE;
            loadClip(mo);
            return;
        }
        Activity activity = AndroidUtilities.findActivity(ctx);
        if (activity == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            activity.startActivityForResult(Intent.createChooser(intent, "Клип к треку"), PrizmaClips.OWN_REQUEST);
        } catch (Throwable ignore) {
        }
    }

    public static void handleClipPicked(Activity activity, Uri uri) {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (activity == null || uri == null || mo == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            File dst = PrizmaClips.ownTarget(mo);
            try (InputStream in = activity.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                long total = 0;
                int r;
                while (in != null && (r = in.read(buf)) > 0) {
                    total += r;
                    if (total > 120L * 1024 * 1024) throw new java.io.IOException("too big");
                    out.write(buf, 0, r);
                }
                ok = total > 0;
            } catch (Throwable e) {
                dst.delete();
            }
            final boolean done = ok;
            AndroidUtilities.runOnUIThread(() -> {
                Toast.makeText(activity, done ? "Клип добавлен" : "Не получилось взять это видео", Toast.LENGTH_SHORT).show();
                if (instance != null) {
                    instance.clipKey = Long.MIN_VALUE;
                    instance.loadClip(mo);
                }
            });
        });
    }

    @Override
    public ViewGroup popupHost() {
        return root;
    }

    @Override
    public PrizmaPalette palette() {
        return palette;
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.01f) return String.valueOf(Math.round(s));
        String v = String.valueOf(Math.round(s * 100f) / 100f);
        return v.endsWith("0") && v.contains(".") ? v.substring(0, v.length() - 1) : v;
    }

    private static long trackKey(MessageObject mo) {
        return mo.getDialogId() * 1_000_003L + mo.getId();
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60, s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }

    private final class DragRoot extends FrameLayout {
        private final int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float startX, startY, baseY;
        private boolean dragging, allowed;
        private VelocityTracker velocity;
        private float cornerRadius;
        private final PrizmaSpring sheet = new PrizmaSpring(0f, 300f, 0.92f, this::setTranslationY);

        DragRoot(Context context) {
            super(context);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + AndroidUtilities.dp(40), cornerRadius);
                }
            });
        }

        void settle() {
            sheet.snap(getTranslationY());
            sheet.to(0f);
        }

        private void track(MotionEvent e) {
            if (velocity == null) velocity = VelocityTracker.obtain();
            MotionEvent copy = MotionEvent.obtain(e);
            copy.setLocation(e.getRawX(), e.getRawY());
            velocity.addMovement(copy);
            copy.recycle();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    dragging = false;
                    int limit = page == PAGE_COVER ? seek.getTop() : stage.getTop();
                    allowed = e.getY() < limit && getChildCount() <= 3;
                    if (velocity != null) velocity.clear();
                    track(e);
                    break;
                case MotionEvent.ACTION_MOVE:
                    track(e);
                    float dy = e.getRawY() - startY, dx = e.getRawX() - startX;
                    if (allowed && dy > slop && dy > Math.abs(dx) * 1.3f) {
                        dragging = true;
                        baseY = slop;
                        sheet.cancel();
                        setClipToOutline(true);
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
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return true;
                    track(e);
                    float y = Math.max(0f, e.getRawY() - startY - baseY);
                    setTranslationY(y);
                    float wanted = Math.min(AndroidUtilities.dpf2(30), y / 3f);
                    if (wanted != cornerRadius) {
                        cornerRadius = wanted;
                        invalidateOutline();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) {
                        dragging = false;
                        float v = 0;
                        if (velocity != null) {
                            track(e);
                            velocity.computeCurrentVelocity(1000);
                            v = velocity.getYVelocity();
                        }
                        if (getTranslationY() > getHeight() * 0.2f || v > AndroidUtilities.dp(900)) {
                            PrizmaFullPlayer.this.dismiss();
                        } else {
                            setClipToOutline(false);
                            cornerRadius = 0;
                            settle();
                        }
                    }
                    return true;
            }
            return true;
        }
    }

    private final class LyricsPanel extends FrameLayout {
        final PrizmaLyricsView view;
        private final LinearLayout state;
        private final ImageView icon;
        private final TextView title, text, action;
        private final GradientDrawable actionBg = new GradientDrawable();

        LyricsPanel(Context c) {
            super(c);
            view = new PrizmaLyricsView(c);
            view.listener = ms -> {
                MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                if (mo != null && mo.getDuration() > 0) {
                    MediaController.getInstance().seekToProgress(mo, (float) (ms / (mo.getDuration() * 1000f)));
                }
            };
            addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            state = new LinearLayout(c);
            state.setOrientation(LinearLayout.VERTICAL);
            state.setGravity(Gravity.CENTER_HORIZONTAL);
            icon = new ImageView(c);
            icon.setImageResource(R.drawable.prizma_ic_empty);
            icon.setColorFilter(TEXT_2);
            icon.setScaleX(2.6f);
            icon.setScaleY(2.6f);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            state.addView(icon, LayoutHelper.createLinear(84, 84, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));
            title = new TextView(c);
            title.setTextColor(TEXT);
            title.setTextSize(21);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            state.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            text = new TextView(c);
            text.setTextColor(TEXT_2);
            text.setTextSize(14);
            text.setGravity(Gravity.CENTER);
            text.setLineSpacing(AndroidUtilities.dp(2), 1f);
            state.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 12, 8, 12, 0));
            action = new TextView(c);
            action.setTextSize(15);
            action.setTypeface(AndroidUtilities.bold());
            action.setGravity(Gravity.CENTER);
            action.setPadding(AndroidUtilities.dp(34), 0, AndroidUtilities.dp(34), 0);
            actionBg.setCornerRadius(AndroidUtilities.dp(24));
            action.setBackground(actionBg);
            PrizmaGlass.pressable(action);
            state.addView(action, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 48, Gravity.CENTER_HORIZONTAL, 0, 22, 0, 0));
            addView(state, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        }

        void setPalette(PrizmaPalette p) {
            actionBg.setColor(p.accent);
            action.setTextColor(p.onAccent);
        }

        private void state(String head, String body, String button, Runnable click) {
            view.setVisibility(GONE);
            state.setVisibility(VISIBLE);
            state.setAlpha(0f);
            state.animate().alpha(1f).setDuration(200).start();
            title.setText(head);
            text.setText(body);
            text.setVisibility(TextUtils.isEmpty(body) ? GONE : VISIBLE);
            action.setVisibility(button == null ? GONE : VISIBLE);
            action.setText(button);
            action.setOnClickListener(click == null ? null : v -> click.run());
        }

        void showAsk() {
            state("Найти текст песни?", "Название и исполнитель уйдут на сайт LRCLIB. Если подключён сервер Libre, запрос пойдёт через него, а не с вашего телефона.",
                    "Искать текст", () -> {
                        PrizmaConfig.setLyricsOnline(true);
                        PrizmaConfig.setLyricsAsked(true);
                        forceLyrics();
                    });
        }

        void showOff() {
            state("Поиск текстов выключен", "В самом треке текста нет. Включите «Тексты из сети», чтобы искать его онлайн.",
                    "Включить", () -> {
                        PrizmaConfig.setLyricsOnline(true);
                        forceLyrics();
                    });
        }

        void showLoading() {
            state("Ищем текст…", null, null, null);
        }

        void showNone() {
            state("Текст не нашёлся", "Для этого трека пока никто не добавил слова.", "Попробовать ещё раз", PrizmaFullPlayer.this::forceLyrics);
        }

        void showInstrumental() {
            state("Инструментал", "Трек отмечен как композиция без слов.", null, null);
        }

        void showLines(PrizmaLyrics.Lyrics lyrics) {
            state.setVisibility(GONE);
            view.setVisibility(VISIBLE);
            view.setLyrics(lyrics);
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(260).start();
        }
    }

    private static final class PillButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Drawable icon;
        private int color, tint;
        private float grow, shift, round;
        private PillButton left, right;
        private final PrizmaSpring growSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.48f, v -> {
            grow = v;
            invalidate();
        });
        private final PrizmaSpring shiftSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.5f, v -> {
            shift = v;
            invalidate();
        });
        private final PrizmaSpring roundSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.8f, v -> {
            round = v;
            invalidate();
        });

        PillButton(Context c) {
            super(c);
            setClickable(true);
        }

        void setIcon(Drawable d) {
            icon = d;
            if (d != null) d.setCallback(this);
            if (d instanceof PlayPauseDrawable) ((PlayPauseDrawable) d).setParent(this);
            invalidate();
        }

        @Override
        protected boolean verifyDrawable(Drawable who) {
            return who == icon || super.verifyDrawable(who);
        }

        void neighbors(PillButton l, PillButton r) {
            left = l;
            right = r;
        }

        void setRounded(boolean paused) {
            if (roundSpring.target() != (paused ? 1f : 0f)) roundSpring.to(paused ? 1f : 0f);
        }

        void setColors(int fillColor, int iconColor, boolean animated) {
            if (!animated) {
                color = fillColor;
                tint = iconColor;
                paint();
                return;
            }
            final int fromFill = color, fromTint = tint;
            android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(420);
            va.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                color = PrizmaPalette.mix(fromFill, fillColor, t);
                tint = PrizmaPalette.mix(fromTint, iconColor, t);
                paint();
            });
            va.start();
        }

        private void paint() {
            if (icon != null && !(icon instanceof PlayPauseDrawable)) {
                icon.setColorFilter(new android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN));
            }
            invalidate();
        }

        void nudge(float direction) {
            shiftSpring.snap(direction * AndroidUtilities.dp(7));
            shiftSpring.to(0f);
        }

        void squeeze(float amount) {
            growSpring.to(amount);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    growSpring.to(AndroidUtilities.dp(7));
                    if (left != null) left.squeeze(-AndroidUtilities.dp(5));
                    if (right != null) right.squeeze(-AndroidUtilities.dp(5));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    growSpring.to(0f);
                    if (left != null) left.squeeze(0f);
                    if (right != null) right.squeeze(0f);
                    break;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            rect.set(-grow, Math.min(0f, -grow * 0.35f), w + grow, h - Math.min(0f, -grow * 0.35f));
            float r = AndroidUtilities.lerp(AndroidUtilities.dpf2(24), h / 2f, Math.max(0f, Math.min(1f, round)));
            fill.setColor(color);
            canvas.drawRoundRect(rect, r, r, fill);
            if (icon != null) {
                int iw = icon.getIntrinsicWidth(), ih = icon.getIntrinsicHeight();
                if (iw <= 0) iw = AndroidUtilities.dp(26);
                if (ih <= 0) ih = AndroidUtilities.dp(26);
                int cx = (int) (w / 2f + shift), cy = (int) (h / 2f);
                icon.setBounds(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2);
                icon.draw(canvas);
            }
        }
    }

    private static final class GroupButton extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private final float[] radii = new float[8];
        private final boolean leading;
        private Drawable icon;
        private int onColor, onTint, offColor, offTint;
        private float selected;
        private final PrizmaSpring spring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.7f, v -> {
            selected = v;
            invalidate();
        });

        GroupButton(Context c, boolean leading) {
            super(c);
            this.leading = leading;
            setClickable(true);
        }

        void setIcon(Drawable d) {
            icon = d;
        }

        void setColors(int on, int onIcon, int off, int offIcon) {
            onColor = on;
            onTint = onIcon;
            offColor = off;
            offTint = offIcon;
            invalidate();
        }

        void setSelectedState(boolean value) {
            spring.to(value ? 1f : 0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float t = Math.max(0f, Math.min(1f, selected));
            float outer = h / 2f;
            float inner = AndroidUtilities.lerp(AndroidUtilities.dpf2(8), outer, t);
            float a = leading ? outer : inner;
            float b = leading ? inner : outer;
            radii[0] = radii[1] = a;
            radii[2] = radii[3] = b;
            radii[4] = radii[5] = b;
            radii[6] = radii[7] = a;
            path.rewind();
            rect.set(0, 0, w, h);
            path.addRoundRect(rect, radii, Path.Direction.CW);
            fill.setColor(PrizmaPalette.mix(offColor, onColor, t));
            canvas.drawPath(path, fill);
            if (icon != null) {
                int s = AndroidUtilities.dp(24);
                int cx = (int) (w / 2f), cy = (int) (h / 2f);
                icon.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
                icon.setColorFilter(new android.graphics.PorterDuffColorFilter(PrizmaPalette.mix(offTint, onTint, t), android.graphics.PorterDuff.Mode.SRC_IN));
                icon.draw(canvas);
            }
        }
    }

    private static final class DotIcon extends FrameLayout {
        private final ImageView image;
        private final View dot;
        private final GradientDrawable dotBg = new GradientDrawable();
        private int accent = 0xffffffff;
        private boolean active;

        DotIcon(Context c, int icon) {
            super(c);
            image = new ImageView(c);
            image.setImageResource(icon);
            image.setScaleType(ImageView.ScaleType.CENTER);
            addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            dot = new View(c);
            dotBg.setShape(GradientDrawable.OVAL);
            dot.setBackground(dotBg);
            dot.setScaleX(0f);
            dot.setScaleY(0f);
            addView(dot, LayoutHelper.createFrame(5, 5, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 3));
            PrizmaGlass.pressable(this);
        }

        void setIcon(int icon) {
            image.setImageResource(icon);
        }

        void setAccent(int color) {
            accent = color;
            dotBg.setColor(color);
            setActive(active);
        }

        void setActive(boolean value) {
            active = value;
            image.setColorFilter(value ? accent : 0x8cffffff);
            dot.animate().scaleX(value ? 1f : 0f).scaleY(value ? 1f : 0f).setDuration(240)
                    .setInterpolator(new OvershootInterpolator(2.4f)).start();
        }
    }

    private static final class HeartButton extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final android.graphics.Matrix matrix = new android.graphics.Matrix();
        private final Path scaled = new Path();
        private int accent = 0xffffffff;
        private float fill;
        private boolean checked;
        private float pop = 1f;
        private final PrizmaSpring popSpring = new PrizmaSpring(1f, PrizmaSpring.MEDIUM, 0.4f, v -> {
            pop = v;
            invalidate();
        });
        private final PrizmaSpring fillSpring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 1f, v -> {
            fill = v;
            invalidate();
        });

        HeartButton(Context c) {
            super(c);
            path.moveTo(12, 20.6f);
            path.cubicTo(12, 20.6f, 3.2f, 14.7f, 3.2f, 8.8f);
            path.cubicTo(3.2f, 5.9f, 5.4f, 3.8f, 8.1f, 3.8f);
            path.cubicTo(9.7f, 3.8f, 11.2f, 4.7f, 12, 6.1f);
            path.cubicTo(12.8f, 4.7f, 14.3f, 3.8f, 15.9f, 3.8f);
            path.cubicTo(18.6f, 3.8f, 20.8f, 5.9f, 20.8f, 8.8f);
            path.cubicTo(20.8f, 14.7f, 12, 20.6f, 12, 20.6f);
            path.close();
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(AndroidUtilities.dpf2(1.9f));
            setClickable(true);
        }

        void setAccent(int color) {
            accent = color;
            invalidate();
        }

        void setChecked(boolean value, boolean animated) {
            boolean changed = checked != value;
            checked = value;
            if (animated) {
                fillSpring.to(value ? 1f : 0f);
                if (changed && value) {
                    popSpring.snap(0.72f);
                    popSpring.to(1f);
                }
            } else {
                fillSpring.snap(value ? 1f : 0f);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float size = AndroidUtilities.dp(26);
            float k = size / 24f;
            matrix.reset();
            matrix.setScale(k, k);
            matrix.postTranslate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
            path.transform(matrix, scaled);
            canvas.save();
            canvas.scale(pop, pop, getWidth() / 2f, getHeight() / 2f);
            float t = Math.max(0f, Math.min(1f, fill));
            if (t > 0f) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(accent);
                paint.setAlpha((int) (255 * t));
                canvas.drawPath(scaled, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(PrizmaPalette.mix(0xd9ffffff, accent, t));
            canvas.drawPath(scaled, paint);
            canvas.restore();
        }
    }
}
