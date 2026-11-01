package org.telegram.ui.Components;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaLyrics;
import org.telegram.messenger.PrizmaSleepTimer;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/**
 * Prizma: immersive full-screen music player, Apple Music styled.
 * Background is a soft blurred version of the album art; a thin capsule seek bar;
 * Spotify-style synced lyrics panel at the bottom with smooth line transitions.
 */
public class PrizmaFullPlayer extends Dialog {

    private final Context ctx;
    private BackupImageView cover;
    private PrizmaWaveBars waveBars;
    private TextView titleView;
    private PrizmaLyrics.Lyrics currentLyrics;
    private TextView authorView;
    private TextView timeCur;
    private TextView timeDur;
    private AppleSeek seek;
    private ImageView playButton;
    private ImageView likeButton;
    private PlayPauseDrawable playPauseDrawable;
    private LyricsPanel lyricsPanel;
    private QueueView queueView;
    private FrameLayout root;
    private ImageView repeatButton;
    private ImageView shuffleButton;
    private ImageView shareButtonTop;
    private BeatBackground beatBg;
    private int accentColor = ACCENT;

    private LinearLayout controls;
    private LinearLayout nextBar;
    private TextView nextBarLabel;
    // "listen together" status row under the top bar
    private LinearLayout togetherBar;
    private TextView togetherEyebrow, togetherLabel, togetherButton;
    private String togetherKey = "";
    private TextView nextEyebrow;
    private BackupImageView nextCover;
    private int coverBaseSide;
    private boolean editMode;
    private static boolean lite() {
        // Lite mode, a weak device or the battery saver → no beat glow / zoom pulse / parallax
        try { return !org.telegram.messenger.PrizmaConfig.fancyEffects(); } catch (Throwable e) { return false; }
    }
    private LinearLayout editBar;

    private boolean userSeeking;
    private int loadedMsgId = Integer.MIN_VALUE;
    private int lyricsForMsgId = Integer.MIN_VALUE;
    private boolean running;

    private Bitmap blurBg;
    private Bitmap blurSrcRef;
    private int blurForMsgId = Integer.MIN_VALUE;
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint();
    private int scrimForHeight = -1;
    private final Rect bgDst = new Rect();
    private final Rect bgSrc = new Rect();
    private final Paint themePaint = new Paint();
    private int themeForH = -1;
    private final Paint accentBgPaint = new Paint();
    private int accentBgForH = -1, accentBgColor = 0;

    // beat-reactive background: zoom pulse + side glow
    private float beatAmp, beatAmpTarget;
    private long beatLastTime;
    private final Paint edgePaint = new Paint();
    private LinearGradient edgeLeft, edgeRight;
    private int edgeW = -1;
    private int edgeAccent = 0;
    private final PrizmaVisualizerData.Listener beatListener = (playing, animate, values) -> {
        float m = 0;
        if (values != null) {
            for (float v : values) m = Math.max(m, v);
        }
        beatAmpTarget = playing ? Math.max(0f, Math.min(1f, m)) : 0f;
        if (root != null) root.postInvalidateOnAnimation();
    };

    private static boolean open;

    public static boolean isOpen() {
        return open;
    }

    public static void show(Context context) {
        if (context == null || MediaController.getInstance().getPlayingMessageObject() == null) {
            return;
        }
        if (open) {
            return;
        }
        // «Новый плеер (бета)» switched on in the player's menu
        if (org.telegram.messenger.PrizmaConfig.betaPlayer()) {
            PrizmaCardPlayer.show(context);
            return;
        }
        try {
            new PrizmaFullPlayer(context).show();
        } catch (Throwable ignore) {
        }
    }

    public PrizmaFullPlayer(Context context) {
        super(context);
        this.ctx = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        root = new FrameLayout(ctx) {
            @Override
            protected void onDetachedFromWindow() {
                super.onDetachedFromWindow();
                release();
            }

            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                // smooth the beat amplitude (slow = calmer)
                long now = System.currentTimeMillis();
                float dt = beatLastTime == 0 ? 16 : Math.min(40, now - beatLastTime);
                beatLastTime = now;
                beatAmp += (beatAmpTarget - beatAmp) * Math.min(1f, dt / 170f * 2f);

                int mode;
                try { mode = org.telegram.messenger.PrizmaConfig.playerBg(); } catch (Throwable e) { mode = 0; }

                try {
                    if (mode == 2) {               // dark theme — calm vertical graphite
                        drawThemeBg(canvas, w, h);
                    } else if (mode == 3) {        // accent gradient from the cover colour
                        drawAccentBg(canvas, w, h);
                    } else if (mode == 1 && blurSrcRef != null && !blurSrcRef.isRecycled()) {
                        // sharp cover, centre-cropped with a gentle beat zoom
                        float s = 1f + 0.02f * beatAmp;
                        drawCenterCrop(canvas, blurSrcRef, w, h, s);
                    } else if (blurBg != null && !blurBg.isRecycled()) {
                        float s = 1f + 0.028f * beatAmp; // very gentle zoom pulse
                        int ex = (int) (w * (s - 1f) / 2f), ey = (int) (h * (s - 1f) / 2f);
                        bgDst.set(-ex, -ey, w + ex, h + ey);
                        canvas.drawBitmap(blurBg, null, bgDst, bgPaint);
                    } else {
                        canvas.drawColor(0xff0e0e12);
                    }
                } catch (Throwable e) {
                    canvas.drawColor(0xff0e0e12); // never crash the player over a recycled cover bitmap
                }
                if (scrimForHeight != h) {
                    scrimForHeight = h;
                    scrimPaint.setShader(new LinearGradient(0, 0, 0, h,
                            new int[]{0x55000000, 0x22000000, 0x66000000, 0xdd000000},
                            new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                }
                canvas.drawRect(0, 0, w, h, scrimPaint);

                // soft iridescent side glow — wide, feathered, gently pulsing (fits any cover)
                float glow = (float) Math.pow(Math.max(0f, Math.min(1f, beatAmp)), 0.7f);
                if (!lite() && glow > 0.015f && w > 0) {
                    int edgeWidth = (int) (w * 0.34f); // wider, softer wash
                    float hue = (now / 70f) % 360f;    // slower, calmer hue drift
                    int bucket = (int) (hue / 8f);
                    if (edgeW != w || edgeAccent != bucket) {
                        edgeW = w;
                        edgeAccent = bucket;
                        int c0 = Color.HSVToColor(new float[]{hue, 0.52f, 1f});
                        int cMid = (c0 & 0x00ffffff) | 0x30000000;
                        int c1 = c0 & 0x00ffffff;
                        edgeLeft = new LinearGradient(0, 0, edgeWidth, 0,
                                new int[]{c0, cMid, c1}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                        edgeRight = new LinearGradient(w - edgeWidth, 0, w, 0,
                                new int[]{c1, cMid, c0}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                    }
                    int a = (int) (58 * glow); // gentler peak
                    edgePaint.setShader(edgeLeft);
                    edgePaint.setAlpha(a);
                    canvas.drawRect(0, 0, edgeWidth, h, edgePaint);
                    edgePaint.setShader(edgeRight);
                    edgePaint.setAlpha(a);
                    canvas.drawRect(w - edgeWidth, 0, w, h, edgePaint);
                    org.telegram.messenger.PrizmaConfig.nextDecorFrame(this); // keep the hue drifting
                } else if (Math.abs(beatAmpTarget - beatAmp) > 0.01f) {
                    postInvalidateOnAnimation();
                }
            }
        };
        root.setWillNotDraw(false);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(26), AndroidUtilities.dp(10) + AndroidUtilities.statusBarHeight, AndroidUtilities.dp(26), AndroidUtilities.dp(18) + AndroidUtilities.navigationBarHeight);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        root.setClipChildren(false);

        // top bar: close chevron (left) + like (right)
        ImageView close = new ImageView(ctx);
        close.setImageResource(R.drawable.arrow_more);
        close.setColorFilter(0xffffffff);
        close.setScaleType(ImageView.ScaleType.CENTER);
        close.setOnClickListener(v -> dismiss());
        FrameLayout topBar = new FrameLayout(ctx);
        topBar.addView(close, LayoutHelper.createFrame(40, 40, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        LinearLayout rightBar = new LinearLayout(ctx);
        rightBar.setOrientation(LinearLayout.HORIZONTAL);
        rightBar.setGravity(Gravity.CENTER_VERTICAL);

        shareButtonTop = new ImageView(ctx);
        shareButtonTop.setImageResource(R.drawable.msg_share);
        shareButtonTop.setColorFilter(0xffffffff);
        shareButtonTop.setScaleType(ImageView.ScaleType.CENTER);
        shareButtonTop.setOnClickListener(v -> shareCurrentTrack());
        rightBar.addView(shareButtonTop, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        // "+" → pick which playlist(s) the track goes to; it turns into a tick once the track is in one
        likeButton = new ImageView(ctx);
        likeButton.setScaleType(ImageView.ScaleType.CENTER);
        likeButton.setImageDrawable(addDrawable = new AddDrawable());
        likeButton.setContentDescription("Добавить в плейлист");
        likeButton.setOnClickListener(v -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            PrizmaPlaylistSheet.showAddToPlaylist(ctx, root, accentColor, mo, () -> {
                boolean in = org.telegram.messenger.PrizmaPlaylist.inAny(mo);
                if (in && !addDrawable.isChecked()) popAddButton();
                updateLikeIcon(in);
            });
        });
        rightBar.addView(likeButton, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));

        ImageView options = new ImageView(ctx);
        options.setImageResource(R.drawable.ic_ab_other);
        options.setColorFilter(0xffffffff);
        options.setScaleType(ImageView.ScaleType.CENTER);
        options.setOnClickListener(v -> showOptions());
        rightBar.addView(options, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));

        topBar.addView(rightBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        column.addView(topBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 10, 0, 8));

        // "listen together": who we're in sync with (or that we're the host) + one tap to leave
        togetherBar = new LinearLayout(ctx);
        togetherBar.setOrientation(LinearLayout.HORIZONTAL);
        togetherBar.setGravity(Gravity.CENTER_VERTICAL);
        togetherBar.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        GradientDrawable tbBg = new GradientDrawable();
        tbBg.setCornerRadius(AndroidUtilities.dp(16));
        tbBg.setColor(0x26ffffff);
        togetherBar.setBackground(tbBg);
        LinearLayout tbTexts = new LinearLayout(ctx);
        tbTexts.setOrientation(LinearLayout.VERTICAL);
        togetherEyebrow = new TextView(ctx);
        togetherEyebrow.setTextSize(9.5f);
        togetherEyebrow.setTypeface(AndroidUtilities.bold());
        togetherEyebrow.setLetterSpacing(0.14f);
        tbTexts.addView(togetherEyebrow);
        togetherLabel = new TextView(ctx);
        togetherLabel.setTextColor(0xe6ffffff);
        togetherLabel.setTextSize(13.5f);
        togetherLabel.setMaxLines(1);
        togetherLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tbTexts.addView(togetherLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        togetherBar.addView(tbTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        togetherButton = new TextView(ctx);
        togetherButton.setTextColor(0xffffffff);
        togetherButton.setTextSize(13);
        togetherButton.setTypeface(AndroidUtilities.bold());
        togetherButton.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(6), AndroidUtilities.dp(13), AndroidUtilities.dp(6));
        GradientDrawable tbBtnBg = new GradientDrawable();
        tbBtnBg.setCornerRadius(AndroidUtilities.dp(14));
        tbBtnBg.setColor(0x33ffffff);
        togetherButton.setBackground(tbBtnBg);
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
        column.addView(togetherBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // compact "up next" bar above the cover — next track's art + title, tap expands the full queue
        nextBar = new LinearLayout(ctx);
        nextBar.setOrientation(LinearLayout.HORIZONTAL);
        nextBar.setGravity(Gravity.CENTER_VERTICAL);
        nextBar.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(7), AndroidUtilities.dp(12), AndroidUtilities.dp(7));
        GradientDrawable nbBg = new GradientDrawable();
        nbBg.setCornerRadius(AndroidUtilities.dp(16));
        nbBg.setColor(0x26ffffff);
        nextBar.setBackground(nbBg);

        nextCover = new BackupImageView(ctx);
        nextCover.setRoundRadius(AndroidUtilities.dp(8));
        nextBar.addView(nextCover, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout nbTexts = new LinearLayout(ctx);
        nbTexts.setOrientation(LinearLayout.VERTICAL);
        nextEyebrow = new TextView(ctx);
        nextEyebrow.setText("ДАЛЕЕ");
        nextEyebrow.setTextColor(accentColor);
        nextEyebrow.setTextSize(9.5f);
        nextEyebrow.setTypeface(AndroidUtilities.bold());
        nextEyebrow.setLetterSpacing(0.14f);
        nbTexts.addView(nextEyebrow);
        nextBarLabel = new TextView(ctx);
        nextBarLabel.setTextColor(0xe6ffffff);
        nextBarLabel.setTextSize(13.5f);
        nextBarLabel.setMaxLines(1);
        nextBarLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nbTexts.addView(nextBarLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        nextBar.addView(nbTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        TextView nbChevron = new TextView(ctx);
        nbChevron.setText("›");
        nbChevron.setTextColor(0x99ffffff);
        nbChevron.setTextSize(20);
        nextBar.addView(nbChevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        nextBar.setOnClickListener(v -> {
            if (queueView != null) queueView.showFull();
        });
        column.addView(nextBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        // cover (large centered square)
        cover = new BackupImageView(ctx);
        cover.setRoundRadius(AndroidUtilities.dp(18));
        int side = Math.min(AndroidUtilities.displaySize.x - AndroidUtilities.dp(72), (int) (AndroidUtilities.displaySize.y * 0.34f));
        coverBaseSide = side;
        LinearLayout.LayoutParams coverLp = new LinearLayout.LayoutParams(side, side);
        coverLp.gravity = Gravity.CENTER_HORIZONTAL;
        coverLp.topMargin = AndroidUtilities.dp(6);
        coverLp.bottomMargin = AndroidUtilities.dp(14);
        column.addView(cover, coverLp);

        // swipes on the cover: down = close, left = next, right = previous
        final android.view.GestureDetector coverGestures = new android.view.GestureDetector(ctx, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true; // consume so the fling/swipe stream is delivered
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dy) > Math.abs(dx) && dy > AndroidUtilities.dp(60)) {
                    dismiss();
                    return true;
                }
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > AndroidUtilities.dp(60)) {
                    if (dx < 0) MediaController.getInstance().playNextMessage();
                    else MediaController.getInstance().playPreviousMessage();
                    return true;
                }
                return false;
            }
        });
        cover.setOnTouchListener((v, ev) -> coverGestures.onTouchEvent(ev));

        // title + author (left aligned)
        titleView = new TextView(ctx);
        titleView.setTextColor(0xffffffff);
        titleView.setTextSize(24);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        authorView = new TextView(ctx);
        authorView.setTextColor(0xb3ffffff);
        authorView.setTextSize(15);
        authorView.setMaxLines(1);
        authorView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        column.addView(authorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 10));

        // capsule seek
        seek = new AppleSeek(ctx);
        seek.onSeek = (progress, committed) -> {
            userSeeking = !committed;
            if (committed) {
                MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
                if (mo != null) {
                    MediaController.getInstance().seekToProgress(mo, progress);
                }
            }
        };
        column.addView(seek, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));

        LinearLayout times = new LinearLayout(ctx);
        times.setOrientation(LinearLayout.HORIZONTAL);
        timeCur = timeLabel(Gravity.LEFT);
        timeDur = timeLabel(Gravity.RIGHT);
        times.addView(timeCur, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        times.addView(timeDur, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        column.addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 8));

        // controls
        controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        shuffleButton = new ImageView(ctx);
        shuffleButton.setScaleType(ImageView.ScaleType.CENTER);
        shuffleButton.setImageResource(R.drawable.player_new_shuffle);
        shuffleButton.setOnClickListener(v -> {
            boolean on = org.telegram.messenger.SharedConfig.shuffleMusic;
            try { MediaController.getInstance().setPlaybackOrderType(on ? 0 : 2); } catch (Throwable ignore) {}
            updateShuffleIcon();
            updateNextBar();
        });
        controls.addView(shuffleButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

        RLottieImageView prev = new RLottieImageView(ctx);
        prev.setAnimation(R.raw.player_prev, 32, 32);
        tintLottie(prev);
        prev.setScaleType(ImageView.ScaleType.CENTER);
        prev.setOnClickListener(v -> MediaController.getInstance().playPreviousMessage());
        controls.addView(prev, LayoutHelper.createLinear(62, 62, Gravity.CENTER_VERTICAL, 0, 0, 22, 0));

        playButton = new ImageView(ctx);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseDrawable = new PlayPauseDrawable(48);
        playPauseDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        playButton.setImageDrawable(playPauseDrawable);
        playButton.setColorFilter(0xffffffff);
        playButton.setOnClickListener(v -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (MediaController.getInstance().isMessagePaused()) {
                MediaController.getInstance().playMessage(mo);
            } else {
                MediaController.getInstance().pauseMessage(mo);
            }
        });
        controls.addView(playButton, LayoutHelper.createLinear(84, 84, Gravity.CENTER_VERTICAL));

        RLottieImageView next = new RLottieImageView(ctx);
        next.setAnimation(R.raw.player_prev, 32, 32);
        tintLottie(next);
        next.setScaleType(ImageView.ScaleType.CENTER);
        next.setScaleX(-1f);
        next.setOnClickListener(v -> MediaController.getInstance().playNextMessage());
        controls.addView(next, LayoutHelper.createLinear(62, 62, Gravity.CENTER_VERTICAL, 22, 0, 0, 0));

        repeatButton = new ImageView(ctx);
        repeatButton.setScaleType(ImageView.ScaleType.CENTER);
        repeatButton.setOnClickListener(v -> {
            int m = (org.telegram.messenger.SharedConfig.repeatMode + 1) % 3;
            org.telegram.messenger.SharedConfig.setRepeatMode(m);
            updateRepeatIcon();
        });
        controls.addView(repeatButton, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL, 14, 0, 0, 0));

        updateRepeatIcon();
        updateShuffleIcon();

        column.addView(controls, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 8));

        // lyrics panel (Spotify style, scrollable)
        lyricsPanel = new LyricsPanel(ctx);
        lyricsPanel.onSeekToMs = (ms) -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            int dur = mo == null ? 0 : (int) (mo.getDuration() * 1000);
            if (mo != null && dur > 0) {
                MediaController.getInstance().seekToProgress(mo, ms / (float) dur);
            }
        };
        column.addView(lyricsPanel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // queue moved to the compact "up next" bar above the cover; keep the instance for its
        // order logic + the expandable full list (showFull). A flexible spacer fills the bottom.
        queueView = new QueueView(ctx);
        View spacer = new View(ctx);
        column.addView(spacer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        beatBg = new BeatBackground(ctx);
        root.addView(beatBg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        setContentView(root, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.BLACK));
            window.setDimAmount(0f);
            // Prizma: fully immersive — the blurred artwork runs behind BOTH the status bar and the
            // navigation bar, so no system-bar strips of a different colour are visible (only the player).
            try {
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(0x00000000);
                window.setNavigationBarColor(0x00000000);
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            } catch (Throwable ignore) {}
        }

        updateTrack(true);
        root.post(this::applyCustomization);
    }

    /** The track that will actually play next (null in shuffle or when unknown). */
    private MessageObject nextTrack() {
        try {
            ArrayList<MessageObject> pl = MediaController.getInstance().getPlaylist();
            if (pl == null || pl.size() < 2) return null;
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            int curId = cur == null ? Integer.MIN_VALUE : cur.getId();
            int n = pl.size();
            int curIndex = -1;
            for (int i = 0; i < n; i++) {
                MessageObject m = pl.get(i);
                if (m != null && m.getId() == curId) { curIndex = i; break; }
            }
            if (curIndex < 0) return null;
            if (org.telegram.messenger.SharedConfig.shuffleMusic) return null;
            int dir = org.telegram.messenger.SharedConfig.playOrderReversed ? 1 : -1;
            int idx = curIndex + dir;
            if (idx < 0) idx = n - 1; else if (idx >= n) idx = 0;
            return pl.get(idx);
        } catch (Throwable e) {
            return null;
        }
    }

    /** Refreshes the compact "up next" bar above the cover. */
    /** Shows who we're listening with; only rebuilds when the together state actually changes. */
    private void updateTogetherBar() {
        if (togetherBar == null) return;
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
        boolean modeChanged = togetherKey.isEmpty() != key.isEmpty() || (!togetherKey.isEmpty() && togetherKey.charAt(0) != key.charAt(0));
        togetherKey = key;
        if (key.isEmpty()) {
            togetherBar.setVisibility(View.GONE);
        } else if (hosting) {
            String name = t.getHostChatName();
            togetherEyebrow.setText("● ВЫ ВЕДЁТЕ ЭФИР");
            togetherEyebrow.setTextColor(0xffff5a5f);
            String who = t.audienceNames();
            togetherLabel.setText(!who.isEmpty() ? "Слушают: " + who
                    : (name.isEmpty() ? "" : "Чат: " + name + " · ") + "пока никто не подключился");
            togetherButton.setText("Стоп");
            togetherBar.setVisibility(View.VISIBLE);
        } else {
            String name = t.getGuestHostName();
            togetherEyebrow.setText("● СЛУШАЕТЕ ВМЕСТЕ");
            togetherEyebrow.setTextColor(accentColor);
            int others = t.getAudience().size();
            togetherLabel.setText((name.isEmpty() ? "Эфир" : "Ведёт: " + name)
                    + (key.endsWith("|w") ? " · ждём следующий трек…" : others > 0 ? " · с вами ещё " + others : " · синхронно"));
            togetherButton.setText("Выйти");
            togetherBar.setVisibility(View.VISIBLE);
        }
        if (modeChanged) updateNextBar();
    }

    private void updateNextBar() {
        if (nextBar == null) return;
        MessageObject nx = PrizmaTogether.get().isFollowing() ? null : nextTrack();
        if (nx == null) {
            nextBar.setVisibility(View.GONE);
            return;
        }
        String t = nx.getMusicTitle();
        String a = nx.getMusicAuthor();
        String name = (t == null || t.isEmpty()) ? "Неизвестный трек" : t;
        if (a != null && !a.isEmpty()) name += " — " + a;
        nextBarLabel.setText(name);

        int total = 0;
        try {
            ArrayList<MessageObject> pl = MediaController.getInstance().getPlaylist();
            total = pl == null ? 0 : pl.size();
        } catch (Throwable ignore) {}
        int more = Math.max(0, total - 1);
        if (nextEyebrow != null) {
            nextEyebrow.setText(more > 1 ? ("ДАЛЕЕ · +" + more) : "ДАЛЕЕ");
            nextEyebrow.setTextColor(accentColor);
        }
        loadMiniCover(nextCover, nx);
        nextBar.setVisibility(View.VISIBLE);
    }

    /** Loads a small album-art thumbnail of a track into the given image view. */
    private void loadMiniCover(BackupImageView iv, MessageObject mo) {
        if (iv == null || mo == null) return;
        try {
            String artworkUrl = mo.getArtworkUrl(true);
            ImageLocation thumbLoc = thumbLocation(mo);
            if (artworkUrl != null && !artworkUrl.isEmpty()) {
                iv.setImage(ImageLocation.getForPath(artworkUrl), "40_40", thumbLoc, null, null, 0, 1, mo);
            } else if (thumbLoc != null) {
                iv.setImage(null, null, thumbLoc, null, null, 0, 1, mo);
            } else {
                GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff2a2d3a, 0xff15161c});
                ph.setCornerRadius(AndroidUtilities.dp(8));
                iv.setImageDrawable(ph);
            }
        } catch (Throwable ignore) {}
    }

    /** Apply the user's saved cover size. (Moving controls around is gone — old offsets are dropped.) */
    private void applyCustomization() {
        try {
            org.telegram.messenger.PrizmaConfig.clearPlayerOffsets();
            applyCoverSize();
        } catch (Throwable ignore) {}
    }

    private void applyCoverSize() {
        if (cover == null || coverBaseSide <= 0) return;
        float scale = org.telegram.messenger.PrizmaConfig.playerCoverScale();
        scale = Math.max(0.6f, Math.min(1.3f, scale));
        int s = (int) (coverBaseSide * scale);
        android.view.ViewGroup.LayoutParams lp = cover.getLayoutParams();
        if (lp != null) {
            lp.width = s;
            lp.height = s;
            cover.setLayoutParams(lp);
        }
    }

    // ===================== cover size =====================

    private ScaleGestureDetector pinch;
    private View pinchShield;
    private PrizmaSlider sizeSlider;

    private static final float COVER_MIN = 0.6f, COVER_MAX = 1.3f;

    /** Cover size mode: pinch anywhere or drag the slider; the cover resizes live. */
    private void enterEditMode() {
        if (editMode) return;
        editMode = true;
        if (cover != null) {
            cover.setRotationX(0);
            cover.setRotationY(0);
        }
        // full-screen catcher for the pinch gesture (under the bar)
        pinchShield = new View(ctx);
        pinch = new ScaleGestureDetector(ctx, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                setCoverScale(org.telegram.messenger.PrizmaConfig.playerCoverScale() * detector.getScaleFactor());
                return true;
            }
        });
        pinchShield.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            return true;
        });
        root.addView(pinchShield, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        showEditBar();
    }

    private void exitEditMode() {
        editMode = false;
        if (pinchShield != null) {
            try { root.removeView(pinchShield); } catch (Throwable ignore) {}
            pinchShield = null;
        }
        if (editBar != null) {
            final View bar = editBar;
            editBar = null;
            bar.animate().alpha(0f).translationY(AndroidUtilities.dp(24)).setDuration(160)
                    .withEndAction(() -> { try { root.removeView(bar); } catch (Throwable ignore) {} }).start();
        }
        sizeSlider = null;
    }

    private void setCoverScale(float scale) {
        float nv = Math.max(COVER_MIN, Math.min(COVER_MAX, scale));
        org.telegram.messenger.PrizmaConfig.setPlayerCoverScale(nv);
        applyCoverSize();
        if (sizeSlider != null) sizeSlider.setValue((nv - COVER_MIN) / (COVER_MAX - COVER_MIN));
    }

    private void showEditBar() {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(26)));
        bar.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
        bar.setClickable(true);

        TextView title = new TextView(ctx);
        title.setText("Размер обложки");
        title.setTextColor(PrizmaGlass.TEXT);
        title.setTextSize(16);
        title.setTypeface(AndroidUtilities.bold());
        bar.addView(title);
        TextView hint = new TextView(ctx);
        hint.setText("Разведите пальцы, чтобы приблизить, сведите — чтобы отдалить");
        hint.setTextColor(PrizmaGlass.TEXT_2);
        hint.setTextSize(13);
        bar.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 6));

        LinearLayout sliderRow = new LinearLayout(ctx);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);
        sliderRow.addView(editChip("−", () -> setCoverScale(org.telegram.messenger.PrizmaConfig.playerCoverScale() - 0.1f)),
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        sizeSlider = new PrizmaSlider(ctx);
        sizeSlider.setColors(accentColor, 0x2effffff);
        sizeSlider.setValue((org.telegram.messenger.PrizmaConfig.playerCoverScale() - COVER_MIN) / (COVER_MAX - COVER_MIN));
        sizeSlider.setOnChange((v, fromUser) -> {
            if (!fromUser) return;
            org.telegram.messenger.PrizmaConfig.setPlayerCoverScale(COVER_MIN + v * (COVER_MAX - COVER_MIN));
            applyCoverSize();
        });
        sliderRow.addView(sizeSlider, LayoutHelper.createLinear(0, 40, 1f, Gravity.CENTER_VERTICAL, 4, 0, 4, 0));
        sliderRow.addView(editChip("+", () -> setCoverScale(org.telegram.messenger.PrizmaConfig.playerCoverScale() + 0.1f)),
                LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL));
        bar.addView(sliderRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = editChip("Как было", () -> setCoverScale(1f));
        buttons.addView(reset, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 5, 0));
        TextView done = editChip("Готово", this::exitEditMode);
        done.setTextColor(0xff111114);
        done.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(22)).setGlassTint(0xf2ffffff));
        buttons.addView(done, LayoutHelper.createLinear(0, 44, 1f, 5, 0, 0, 0));
        bar.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM);
        lp.leftMargin = lp.rightMargin = AndroidUtilities.dp(12);
        lp.bottomMargin = AndroidUtilities.dp(12) + AndroidUtilities.navigationBarHeight;
        root.addView(bar, lp);
        bar.setAlpha(0f);
        bar.setTranslationY(AndroidUtilities.dp(40));
        bar.animate().alpha(1f).translationY(0).setDuration(380).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        editBar = bar;
    }

    private TextView editChip(String text, Runnable onClick) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(PrizmaGlass.TEXT);
        t.setTextSize(15);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(20)));
        t.setOnClickListener(v -> onClick.run());
        PrizmaGlass.pressable(t);
        return t;
    }

    private AddDrawable addDrawable;

    /** Added to a playlist: the button pops and the "+" morphs into a tick. */
    private void popAddButton() {
        if (likeButton == null) return;
        try { likeButton.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignore) {}
        likeButton.animate().cancel();
        likeButton.setScaleX(0.72f);
        likeButton.setScaleY(0.72f);
        likeButton.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(new OvershootInterpolator(3f)).setDuration(420).start();
    }

    /** "+" that morphs into "✓" (two strokes travel to the tick's two legs). */
    private static class AddDrawable extends android.graphics.drawable.Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private boolean checked;
        private android.animation.ValueAnimator animator;

        AddDrawable() {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(AndroidUtilities.dpf2(2.2f));
            paint.setColor(0xffffffff);
        }

        boolean isChecked() {
            return checked;
        }

        void setColor(int color) {
            paint.setColor(color);
            invalidateSelf();
        }

        void setChecked(boolean value, boolean animated) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            checked = value;
            float target = value ? 1f : 0f;
            if (!animated) {
                progress = target;
                invalidateSelf();
                return;
            }
            animator = android.animation.ValueAnimator.ofFloat(progress, target);
            animator.addUpdateListener(an -> {
                progress = (float) an.getAnimatedValue();
                invalidateSelf();
            });
            animator.setDuration(320);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float h = AndroidUtilities.dpf2(9f);
            float t = progress;
            // horizontal stroke → short leg of the tick, vertical stroke → long leg
            float ax = cx - h, ay = cy, bx = cx + h, by = cy;
            float tax = cx - h * 0.9f, tay = cy + h * 0.05f, tbx = cx - h * 0.25f, tby = cy + h * 0.7f;
            canvas.drawLine(lerp(ax, tax, t), lerp(ay, tay, t), lerp(bx, tbx, t), lerp(by, tby, t), paint);
            float cx1 = cx, cy1 = cy - h, cx2 = cx, cy2 = cy + h;
            float tcx1 = cx + h * 0.95f, tcy1 = cy - h * 0.65f, tcx2 = cx - h * 0.25f, tcy2 = cy + h * 0.7f;
            canvas.drawLine(lerp(cx1, tcx1, t), lerp(cy1, tcy1, t), lerp(cx2, tcx2, t), lerp(cy2, tcy2, t), paint);
        }

        private static float lerp(float a, float b, float t) {
            return a + (b - a) * t;
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(24);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(24);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private void drawThemeBg(Canvas canvas, int w, int h) {
        if (themeForH != h) {
            themeForH = h;
            themePaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{0xff1b1c22, 0xff121318, 0xff0b0b0e}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, themePaint);
    }

    private void drawAccentBg(Canvas canvas, int w, int h) {
        int top = (accentColor & 0x00ffffff);
        if (accentBgForH != h || accentBgColor != accentColor) {
            accentBgForH = h;
            accentBgColor = accentColor;
            int c0 = 0xcc000000 | top;  // accent-tinted top
            int c1 = 0xff0c0c10;        // deep bottom
            accentBgPaint.setShader(new LinearGradient(0, 0, 0, h,
                    new int[]{c0, 0xaa000000 | top, c1}, new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, accentBgPaint);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bmp, int w, int h, float zoom) {
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) { canvas.drawColor(0xff0e0e12); return; }
        float scale = Math.max(w / (float) bw, h / (float) bh) * zoom;
        float dw = bw * scale, dh = bh * scale;
        int left = (int) ((w - dw) / 2f), top = (int) ((h - dh) / 2f);
        bgDst.set(left, top, (int) (left + dw), (int) (top + dh));
        bgSrc.set(0, 0, bw, bh);
        canvas.drawBitmap(bmp, bgSrc, bgDst, bgPaint);
    }

    private TextView timeLabel(int gravity) {
        TextView t = new TextView(ctx);
        t.setTextColor(0x99ffffff);
        t.setTextSize(12);
        t.setGravity(gravity);
        return t;
    }

    private static void tintLottie(RLottieImageView v) {
        v.setLayerColor("Triangle 3", 0xffffffff);
        v.setLayerColor("Triangle 4", 0xffffffff);
        v.setLayerColor("Rectangle 4", 0xffffffff);
    }

    @Override
    public void show() {
        super.show();
        open = true;
        running = true;
        PrizmaVisualizerData.setListener(beatListener);
        AndroidUtilities.runOnUIThread(ticker, 100);
        startParallax();
    }

    @Override
    public void dismiss() {
        release();
        try {
            super.dismiss();
        } catch (Throwable ignore) {
        }
    }

    /** Idempotent: stops everything the player started. Also runs when the window is torn down without dismiss(). */
    private void release() {
        open = false;
        running = false;
        PrizmaVisualizerData.clearListener(beatListener);
        AndroidUtilities.cancelRunOnUIThread(ticker);
        stopParallax();
    }

    // ===================== 3D parallax cover tilt =====================

    private android.hardware.SensorManager parallaxSensors;
    private android.hardware.Sensor parallaxSensor;
    private boolean parallaxBaselineSet;
    private float parallaxBaseX, parallaxBaseY;
    private float parallaxRx, parallaxRy; // smoothed rotation

    private final android.hardware.SensorEventListener parallaxListener = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            if (cover == null || editMode) return;
            float gx = e.values[0];
            float gy = e.values[1];
            if (!parallaxBaselineSet) {
                parallaxBaseX = gx;
                parallaxBaseY = gy;
                parallaxBaselineSet = true;
            }
            final float MAX = 10f;      // max tilt degrees (was 16 — too loose)
            final float K = 1.6f;       // degrees per m/s^2 of tilt (was 2.6)
            final float DEAD = 0.35f;   // ignore hand tremor: tilt below this (m/s^2) doesn't move the cover
            float dx = deadZone(gx - parallaxBaseX, DEAD);
            float dy = deadZone(gy - parallaxBaseY, DEAD);
            float targetRy = Math.max(-MAX, Math.min(MAX, -dx * K)); // left/right → rotateY
            float targetRx = Math.max(-MAX, Math.min(MAX, dy * K));  // up/down → rotateX
            parallaxRy += (targetRy - parallaxRy) * 0.08f;           // heavier, slower follow (was 0.14)
            parallaxRx += (targetRx - parallaxRx) * 0.08f;
            cover.setRotationX(parallaxRx);
            cover.setRotationY(parallaxRy);
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor sensor, int accuracy) {}
    };

    private static float deadZone(float v, float dead) {
        if (v > dead) return v - dead;
        if (v < -dead) return v + dead;
        return 0f;
    }

    private void startParallax() {
        try {
            if (lite() || !org.telegram.messenger.PrizmaConfig.coverParallax()) return;
            if (cover != null) {
                // smaller camera distance = more visible perspective (6000 was so flat the tilt was imperceptible)
                cover.setCameraDistance(ctx.getResources().getDisplayMetrics().density * 1800);
            }
            parallaxSensors = (android.hardware.SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (parallaxSensors != null) {
                parallaxSensor = parallaxSensors.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER);
                if (parallaxSensor != null) {
                    parallaxBaselineSet = false;
                    parallaxSensors.registerListener(parallaxListener, parallaxSensor, android.hardware.SensorManager.SENSOR_DELAY_GAME);
                }
            }
        } catch (Throwable ignore) {}
    }

    private void stopParallax() {
        try {
            if (parallaxSensors != null) {
                parallaxSensors.unregisterListener(parallaxListener);
            }
        } catch (Throwable ignore) {}
        parallaxSensors = null;
        parallaxSensor = null;
        if (cover != null) {
            cover.setRotationX(0);
            cover.setRotationY(0);
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!ApplicationLoader.isScreenOn || ApplicationLoader.mainInterfacePaused) {
                if (parallaxSensors != null) {
                    stopParallax();
                    parallaxPausedInBackground = true;
                }
                AndroidUtilities.runOnUIThread(this, 500); // nothing is visible — just poll slowly
                return;
            }
            if (parallaxPausedInBackground) {
                parallaxPausedInBackground = false;
                startParallax();
            }
            updateTrack(false);
            AndroidUtilities.runOnUIThread(this, 60);
        }
    };
    private boolean parallaxPausedInBackground;

    private void updateTrack(boolean force) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) {
            dismiss();
            return;
        }
        if (force || mo.getId() != loadedMsgId) {
            boolean animate = !force && loadedMsgId != Integer.MIN_VALUE;
            loadedMsgId = mo.getId();
            if (animate) {
                animateTrackChange(mo);
            } else {
                applyTrack(mo);
            }
            updateNextBar();
        }
        tryBuildBlur(mo);
        updateTogetherBar();

        boolean paused = MediaController.getInstance().isMessagePaused();
        playPauseDrawable.setPause(!paused, true);
        if (!userSeeking) {
            seek.setValue(mo.audioProgress);
        }
        timeCur.setText(formatTime(mo.audioProgressSec));
        int dur = (int) (mo.getDuration() * 1000);
        lyricsPanel.setPosition((long) (mo.audioProgress * dur) + 450); // lead to compensate tick+animation latency
    }

    private void applyTrack(MessageObject mo) {
        loadCover(mo);
        String t = mo.getMusicTitle();
        String a = mo.getMusicAuthor();
        titleView.setText(t == null || t.isEmpty() ? "Неизвестный трек" : t);
        authorView.setText(a == null ? "" : a);
        timeDur.setText(formatTime((int) mo.getDuration()));
        updateLikeIcon(org.telegram.messenger.PrizmaPlaylist.inAny(mo));
        loadLyrics(mo);
    }

    private void showOptions() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));

        final BottomSheet[] ref = new BottomSheet[1];
        root.addView(optionRow(ctx, textColor, R.drawable.msg_message, "Текст песни", () -> {
            if (ref[0] != null) ref[0].dismiss();
            showFullLyrics();
        }));
        float sp = 1f;
        try { sp = MediaController.getInstance().getPlaybackSpeed(true); } catch (Throwable ignore) {}
        root.addView(optionRow(ctx, textColor, R.drawable.msg_speed, "Скорость · " + formatSpeed(sp) + "x", () -> {
            if (ref[0] != null) ref[0].dismiss();
            showSpeed();
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.media_settings, "Эквалайзер", () -> {
            if (ref[0] != null) ref[0].dismiss();
            PrizmaEqualizerSheet.show(ctx, root, accentColor);
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.msg_autodelete, PrizmaSleepTimer.isActive() ? "Таймер сна (вкл)" : "Таймер сна", () -> {
            if (ref[0] != null) ref[0].dismiss();
            showSleepTimer();
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.msg_fave, "Мой плейлист", () -> {
            if (ref[0] != null) ref[0].dismiss();
            PrizmaPlaylistSheet.show(ctx);
        }));
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        if (playing != null) {
            final int account = playing.currentAccount;
            final boolean onProfile = PrizmaProfileMusic.isSaved(account, playing);
            root.addView(optionRow(ctx, textColor, onProfile ? R.drawable.msg_delete : R.drawable.left_status_profile,
                    onProfile ? "Убрать из профиля Telegram" : "Добавить в профиль Telegram", () -> {
                if (ref[0] != null) ref[0].dismiss();
                PrizmaProfileMusic.toggle(account, playing, text ->
                        android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show());
            }));
        }
        final PrizmaTogether together = PrizmaTogether.get();
        if (together.isHosting()) {
            root.addView(optionRow(ctx, accentColor, R.drawable.msg_leave, "Завершить эфир", () -> {
                if (ref[0] != null) ref[0].dismiss();
                together.stopHosting();
            }));
        } else if (together.isFollowing()) {
            root.addView(optionRow(ctx, accentColor, R.drawable.msg_leave, "Выйти из эфира", () -> {
                if (ref[0] != null) ref[0].dismiss();
                together.leave();
            }));
        } else {
            root.addView(optionRow(ctx, textColor, R.drawable.msg_groups, "Слушать вместе…", () -> {
                if (ref[0] != null) ref[0].dismiss();
                dismiss(); // the chat picker opens under the player otherwise
                PrizmaTogether.pickChatAndStart(ctx);
            }));
        }
        root.addView(optionRow(ctx, textColor, R.drawable.msg_photo_settings, "Фон плеера", () -> {
            if (ref[0] != null) ref[0].dismiss();
            showPlayerBg();
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.msg_reorder, "Размер обложки", () -> {
            if (ref[0] != null) ref[0].dismiss();
            enterEditMode();
        }));
        boolean par = false;
        try { par = org.telegram.messenger.PrizmaConfig.coverParallax(); } catch (Throwable ignore) {}
        root.addView(optionRow(ctx, par ? accentColor : textColor, R.drawable.msg_photo_settings, "3D-обложка (наклон)" + (par ? "  ✓" : ""), () -> {
            boolean nv = !org.telegram.messenger.PrizmaConfig.coverParallax();
            org.telegram.messenger.PrizmaConfig.setCoverParallax(nv);
            if (nv) startParallax(); else stopParallax();
            if (ref[0] != null) ref[0].dismiss();
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.msg_photo_settings, "Стиль перемотки", () -> {
            if (ref[0] != null) ref[0].dismiss();
            showSeekStyle();
        }));
        root.addView(optionRow(ctx, textColor, R.drawable.msg_media, "Новый плеер (бета)", () -> {
            if (ref[0] != null) ref[0].dismiss();
            org.telegram.messenger.PrizmaConfig.setBetaPlayer(true);
            dismiss();
            AndroidUtilities.runOnUIThread(() -> PrizmaCardPlayer.show(ctx), 220);
        }));

        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private LinearLayout optionRow(Context c, int textColor, int icon, String text, Runnable onClick) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(13), AndroidUtilities.dp(14), AndroidUtilities.dp(13));
        row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        ImageView iv = new ImageView(c);
        iv.setImageResource(icon);
        iv.setColorFilter(textColor);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(iv, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(16);
        row.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void showSleepTimer() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];
        int[] mins = {15, 30, 45, 60};
        for (int m : mins) {
            final int mm = m;
            root.addView(optionRow(ctx, textColor, R.drawable.msg_autodelete, "Через " + m + " мин", () -> {
                PrizmaSleepTimer.schedule(mm);
                if (ref[0] != null) ref[0].dismiss();
                android.widget.Toast.makeText(ctx, "Таймер сна: " + mm + " мин", android.widget.Toast.LENGTH_SHORT).show();
            }));
        }
        if (PrizmaSleepTimer.isActive()) {
            root.addView(optionRow(ctx, 0xffff5e5e, R.drawable.msg_delete, "Выключить таймер", () -> {
                PrizmaSleepTimer.cancel();
                if (ref[0] != null) ref[0].dismiss();
                android.widget.Toast.makeText(ctx, "Таймер сна выключен", android.widget.Toast.LENGTH_SHORT).show();
            }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private void showSeekStyle() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];
        String[] names = {"Дефолтный", "Горящий", "Переливающийся"};
        int[] icons = {R.drawable.msg_media, R.drawable.filled_fire, R.drawable.msg_photo_settings};
        for (int i = 0; i < names.length; i++) {
            final int style = i;
            boolean selected = org.telegram.messenger.PrizmaConfig.seekStyle() == style;
            root.addView(optionRow(ctx, selected ? ACCENT : textColor, icons[i], names[i] + (selected ? "  ✓" : ""), () -> {
                org.telegram.messenger.PrizmaConfig.setSeekStyle(style);
                if (seek != null) seek.invalidate();
                if (ref[0] != null) ref[0].dismiss();
            }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static String formatSpeed(float s) {
        if (Math.abs(s - Math.round(s)) < 0.01f) return String.valueOf(Math.round(s));
        String r = String.valueOf(Math.round(s * 10f) / 10f);
        return r;
    }

    private void showSpeed() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];
        float cur = 1f;
        try { cur = MediaController.getInstance().getPlaybackSpeed(true); } catch (Throwable ignore) {}
        float[] speeds = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        for (float sp : speeds) {
            final float s = sp;
            boolean selected = Math.abs(cur - s) < 0.025f;
            root.addView(optionRow(ctx, selected ? accentColor : textColor, R.drawable.msg_speed,
                    formatSpeed(s) + "x" + (selected ? "  ✓" : ""), () -> {
                        try { MediaController.getInstance().setPlaybackSpeed(true, s); } catch (Throwable ignore) {}
                        if (ref[0] != null) ref[0].dismiss();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private void showPlayerBg() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];
        String[] names = {"Размытая обложка", "Чёткая обложка", "Тёмная тема", "Градиент под трек"};
        int[] icons = {R.drawable.msg_photo_settings, R.drawable.msg_media, R.drawable.msg_theme, R.drawable.msg_colors};
        int cur = org.telegram.messenger.PrizmaConfig.playerBg();
        for (int i = 0; i < names.length; i++) {
            final int m = i;
            boolean selected = cur == m;
            root.addView(optionRow(ctx, selected ? accentColor : textColor, icons[i], names[i] + (selected ? "  ✓" : ""), () -> {
                org.telegram.messenger.PrizmaConfig.setPlayerBg(m);
                if (this.root != null) this.root.invalidate();
                if (ref[0] != null) ref[0].dismiss();
            }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    /** Full-screen lyrics with live karaoke highlight (synced) and tap-to-seek. */
    private void showFullLyrics() {
        final PrizmaLyrics.Lyrics lyr = currentLyrics;
        if (lyr == null || lyr.lines == null || lyr.lines.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Текст песни не найден", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final ArrayList<PrizmaLyrics.Line> lines = lyr.lines;
        final boolean synced = lyr.synced;

        LinearLayout sheetRoot = new LinearLayout(ctx);
        sheetRoot.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(0xff121216);
        rbg.setCornerRadii(new float[]{AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), 0, 0, 0, 0});
        sheetRoot.setBackground(rbg);
        sheetRoot.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(12));

        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        TextView head = new TextView(ctx);
        String tt = mo == null ? "" : mo.getMusicTitle();
        head.setText(tt == null || tt.isEmpty() ? "Текст песни" : tt);
        head.setTextColor(0xffffffff);
        head.setTextSize(19);
        head.setTypeface(AndroidUtilities.bold());
        head.setMaxLines(1);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sheetRoot.addView(head, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 2));

        TextView sub = new TextView(ctx);
        sub.setText(synced ? "караоке · нажми на строку для перехода" : "текст без синхронизации");
        sub.setTextColor(0x80ffffff);
        sub.setTextSize(12);
        sheetRoot.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final ScrollView sc = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int ws, int hs) {
                int maxH = (int) (AndroidUtilities.displaySize.y * 0.66f);
                super.onMeasure(ws, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        sc.setVerticalScrollBarEnabled(false);
        final LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        sc.addView(col, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView[] rowViews = new TextView[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            final PrizmaLyrics.Line ln = lines.get(i);
            TextView tv = new TextView(ctx);
            String txt = ln.text == null || ln.text.isEmpty() ? "♪" : ln.text;
            tv.setText(txt);
            tv.setTextColor(0x66ffffff);
            tv.setTextSize(17);
            tv.setLineSpacing(AndroidUtilities.dp(3), 1f);
            tv.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
            if (synced && ln.timeMs >= 0) {
                final long seekMs = ln.timeMs;
                tv.setOnClickListener(v -> {
                    MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
                    int dur = cur == null ? 0 : (int) (cur.getDuration() * 1000);
                    if (cur != null && dur > 0) {
                        MediaController.getInstance().seekToProgress(cur, seekMs / (float) dur);
                    }
                });
            }
            rowViews[i] = tv;
            col.addView(tv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        sheetRoot.addView(sc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(sheetRoot);
        final BottomSheet sheet = b.create();
        try { sheet.setBackgroundColor(0xff121216); } catch (Throwable ignore) {}
        try { sheet.fixNavigationBar(0xff121216); } catch (Throwable ignore) {}

        if (synced) {
            final int[] last = {-1};
            final Runnable[] hl = new Runnable[1];
            hl[0] = () -> {
                if (!sheet.isShowing()) return;
                MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
                if (cur != null) {
                    long pos = (long) (cur.audioProgress * cur.getDuration() * 1000) + 300;
                    int idx = 0;
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).timeMs <= pos) idx = i; else break;
                    }
                    if (idx != last[0]) {
                        if (last[0] >= 0 && last[0] < rowViews.length) {
                            rowViews[last[0]].setTextColor(0x66ffffff);
                            rowViews[last[0]].setTypeface(Typeface.DEFAULT);
                        }
                        final TextView now = rowViews[idx];
                        now.setTextColor(accentColor);
                        now.setTypeface(AndroidUtilities.bold());
                        now.animate().cancel();
                        now.setScaleX(0.98f); now.setScaleY(0.98f);
                        now.setPivotX(0);
                        now.animate().scaleX(1f).scaleY(1f).setDuration(260)
                                .setInterpolator(new OvershootInterpolator(2f)).start();
                        final int target = idx;
                        sc.post(() -> {
                            View child = target < col.getChildCount() ? col.getChildAt(target) : null;
                            if (child != null) {
                                int y = child.getTop() - sc.getHeight() / 2 + child.getHeight();
                                sc.smoothScrollTo(0, Math.max(0, y));
                            }
                        });
                        last[0] = idx;
                    }
                }
                AndroidUtilities.runOnUIThread(hl[0], 120);
            };
            sheet.setOnDismissListener(d -> AndroidUtilities.cancelRunOnUIThread(hl[0]));
            AndroidUtilities.runOnUIThread(hl[0], 150);
        }
        sheet.show();
    }

    private void updateLikeIcon(boolean inPlaylist) {
        if (likeButton == null || addDrawable == null) return;
        addDrawable.setChecked(inPlaylist, likeButton.isAttachedToWindow());
        addDrawable.setColor(inPlaylist ? accentColor : 0xffffffff);
    }

    private void updateShuffleIcon() {
        if (shuffleButton == null) return;
        boolean on = org.telegram.messenger.SharedConfig.shuffleMusic;
        shuffleButton.setColorFilter(on ? accentColor : 0x66ffffff);
    }

    private void updateRepeatIcon() {
        if (repeatButton == null) return;
        int m = org.telegram.messenger.SharedConfig.repeatMode;
        if (m == 2) {
            repeatButton.setImageResource(R.drawable.player_new_repeatone);
            repeatButton.setColorFilter(accentColor);
        } else if (m == 1) {
            repeatButton.setImageResource(R.drawable.player_new_repeatall);
            repeatButton.setColorFilter(accentColor);
        } else {
            repeatButton.setImageResource(R.drawable.player_new_repeatall);
            repeatButton.setColorFilter(0x66ffffff);
        }
    }

    private void shareCurrentTrack() {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) return;
        try {
            ArrayList<MessageObject> list = new ArrayList<>();
            list.add(mo);
            new ShareAlert(ctx, list, null, false, null, false).show();
        } catch (Throwable ignore) {}
    }

    /** Applies an accent colour (from the cover art) across the player's highlights. */
    private void applyAccent(int color) {
        accentColor = color;
        if (seek != null) seek.setAccent(color);
        if (beatBg != null) beatBg.setAccent(color);
        updateNextBar();
        updateRepeatIcon();
        updateShuffleIcon();
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo != null) updateLikeIcon(org.telegram.messenger.PrizmaPlaylist.inAny(mo));
    }

    /** Picks a vivid representative colour from a cover bitmap, or keeps the default accent. */
    private int dominantColor(Bitmap src) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(src, 24, 24, true);
            long br = 0, bg = 0, bb = 0;
            float bestScore = 0;
            int bestColor = ACCENT;
            float[] hsv = new float[3];
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    float sat = hsv[1], val = hsv[2];
                    float score = sat * (0.4f + 0.6f * val); // prefer saturated, reasonably bright
                    if (score > bestScore) {
                        bestScore = score;
                        bestColor = c;
                    }
                }
            }
            if (bestScore < 0.15f) {
                return ACCENT; // washed-out cover → keep brand accent
            }
            // lift a too-dark pick so highlights read well
            Color.colorToHSV(bestColor, hsv);
            if (hsv[2] < 0.5f) hsv[2] = 0.6f;
            if (hsv[1] < 0.45f) hsv[1] = 0.55f;
            return Color.HSVToColor(hsv);
        } catch (Throwable e) {
            return ACCENT;
        }
    }

    private void animateTrackChange(MessageObject mo) {
        final CubicBezierInterpolator ease = CubicBezierInterpolator.EASE_OUT_QUINT;
        final OvershootInterpolator overshoot = new OvershootInterpolator(1.6f);
        final View[] texts = new View[]{titleView, authorView};
        final float off = AndroidUtilities.dp(90);
        final float textUp = AndroidUtilities.dp(10);

        // text crossfades up
        for (View v : texts) {
            v.animate().cancel();
            v.animate().alpha(0f).translationY(-textUp).setDuration(150).setInterpolator(ease).start();
        }

        // cover is "tossed" out to the left with a tilt, then the new one springs in from the right
        cover.animate().cancel();
        cover.animate()
                .alpha(0f)
                .translationX(-off)
                .rotation(-12f)
                .scaleX(0.82f).scaleY(0.82f)
                .setDuration(190)
                .setInterpolator(ease)
                .withEndAction(() -> {
                    applyTrack(mo);
                    // start state: off to the right, tilted the other way, small
                    cover.setTranslationX(off);
                    cover.setRotation(12f);
                    cover.setScaleX(0.82f);
                    cover.setScaleY(0.82f);
                    cover.setAlpha(0f);
                    cover.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .rotation(0f)
                            .scaleX(1f).scaleY(1f)
                            .setDuration(430)
                            .setInterpolator(overshoot)
                            .start();

                    for (View v : texts) {
                        v.setTranslationY(AndroidUtilities.dp(12));
                        v.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(ease).start();
                    }
                })
                .start();
    }

    private void loadLyrics(MessageObject mo) {
        if (mo.getId() == lyricsForMsgId) {
            return;
        }
        lyricsForMsgId = mo.getId();
        currentLyrics = null;
        lyricsPanel.setLoading();
        final int reqId = mo.getId();
        PrizmaLyrics.fetch(mo.getMusicTitle(), mo.getMusicAuthor(), (int) mo.getDuration(), result -> {
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            if (cur == null || cur.getId() != reqId || !running) {
                return;
            }
            currentLyrics = result;
            lyricsPanel.setLyrics(result);
        });
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
        String artworkUrl = mo.getArtworkUrl(false);
        ImageLocation thumbLoc = thumbLocation(mo);
        if (artworkUrl != null && !artworkUrl.isEmpty()) {
            cover.setImage(ImageLocation.getForPath(artworkUrl), null, thumbLoc, null, null, 0, 1, mo);
        } else if (thumbLoc != null) {
            cover.setImage(null, null, thumbLoc, null, null, 0, 1, mo);
        } else {
            GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff2a2d3a, 0xff15161c});
            ph.setCornerRadius(AndroidUtilities.dp(16));
            cover.setImageDrawable(ph);
        }
    }

    private void tryBuildBlur(MessageObject mo) {
        if (blurForMsgId == mo.getId() && blurBg != null) {
            return; // already built for this track
        }
        // Prefer the bitmap actually shown by the cover view; it reflects the NEW track.
        Bitmap src = null;
        try {
            if (cover.getImageReceiver() != null) {
                src = cover.getImageReceiver().getBitmap();
            }
            if (src == null && MediaController.getInstance().getAudioInfo() != null) {
                src = MediaController.getInstance().getAudioInfo().getCover();
            }
        } catch (Throwable ignore) {}
        if (src == null || src.isRecycled()) {
            return; // keep the previous blur until the new cover is available
        }
        if (src == blurSrcRef) {
            return; // still the previous track's bitmap — wait for the new one
        }
        try {
            Bitmap small = Bitmap.createScaledBitmap(src, 56, 56, true).copy(Bitmap.Config.ARGB_8888, true);
            Utilities.stackBlurBitmap(small, 4);
            Bitmap old = blurBg;
            blurBg = small;
            blurSrcRef = src;
            blurForMsgId = mo.getId();
            if (old != null && !old.isRecycled()) {
                old.recycle();
            }
            applyAccent(dominantColor(src)); // tint highlights with the cover's vivid colour
            if (root != null) root.invalidate();
        } catch (Throwable ignore) {}
    }

    private ImageLocation thumbLocation(MessageObject mo) {
        TLRPC.Document doc = mo.getDocument();
        TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 360) : null;
        if (!(thumb instanceof TLRPC.TL_photoSize) && !(thumb instanceof TLRPC.TL_photoSizeProgressive)) {
            thumb = null;
        }
        return thumb != null ? ImageLocation.getForDocument(thumb, doc) : null;
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : s);
    }

    // ===================== Apple-style capsule seek =====================

    interface OnSeek {
        void onSeek(float progress, boolean committed);
    }

    /** Beat-reactive bar seek: a waveform of bars that pulses with the music and scrubs the track. */
    private static class AppleSeek extends View implements PrizmaVisualizerData.Listener {
        OnSeek onSeek;
        private float value;      // committed 0..1
        private float display;    // animated 0..1
        private boolean pressed;
        private boolean playing;
        private float amp;        // smoothed beat amplitude 0..1
        private float ampTarget;
        private long lastTime;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] hsv = new float[3];
        private final RectF r = new RectF();
        private final RectF rGlow = new RectF();
        private int accent = ACCENT;

        AppleSeek(Context c) {
            super(c);
        }

        void setAccent(int color) {
            accent = color;
            invalidate();
        }

        private static int blend(int a, int b, float t) {
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            int aa = (a >>> 24), ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
            int ba = (b >>> 24), br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
            int oa = (int) (aa + (ba - aa) * t);
            int or = (int) (ar + (br - ar) * t);
            int og = (int) (ag + (bg - ag) * t);
            int ob = (int) (ab + (bb - ab) * t);
            return (oa << 24) | (or << 16) | (og << 8) | ob;
        }

        void setValue(float v) {
            if (pressed) return;
            value = clamp(v);
            invalidate();
        }

        private float clamp(float v) {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            PrizmaVisualizerData.setListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            PrizmaVisualizerData.clearListener(this);
        }

        @Override
        public void onWaveform(boolean playing, boolean animate, float[] values) {
            this.playing = playing;
            float m = 0;
            if (values != null) {
                for (float v : values) m = Math.max(m, v);
            }
            ampTarget = Math.max(0f, Math.min(1f, m));
            postInvalidateOnAnimation();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float w = Math.max(1, getWidth());
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    value = clamp(e.getX() / w);
                    if (onSeek != null) onSeek.onSeek(value, false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    value = clamp(e.getX() / w);
                    if (onSeek != null) onSeek.onSeek(value, false);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    if (onSeek != null) onSeek.onSeek(value, true);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        // stable pseudo-waveform shape per bar
        private float baseHeight(int i) {
            float s = (float) (0.55 + 0.45 * Math.sin(i * 0.9) * Math.cos(i * 0.37 + 1.3));
            return Math.max(0.18f, Math.min(1f, Math.abs(s)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = System.currentTimeMillis();
            float dt = lastTime == 0 ? 16 : Math.min(48, now - lastTime);
            lastTime = now;
            float kv = Math.min(1f, dt / 120f * 3f);
            float ka = Math.min(1f, dt / 90f * 3f);
            display += (value - display) * kv;
            boolean isPlaying;
            try {
                isPlaying = MediaController.getInstance().getPlayingMessageObject() != null
                        && !MediaController.getInstance().isMessagePaused();
            } catch (Throwable e) {
                isPlaying = playing;
            }
            // idle amplitude so bars gently breathe while playing even between beats
            float target = isPlaying ? Math.max(0.18f, ampTarget) : 0.05f;
            amp += (target - amp) * ka;

            int w = getWidth();
            int h = getHeight();
            float cy = h / 2f;
            float barW = AndroidUtilities.dpf2(3f);
            float gap = AndroidUtilities.dpf2(3.5f);
            float step = barW + gap;
            int count = (int) (w / step);
            if (count <= 0) return;
            float maxH = h - AndroidUtilities.dpf2(6);
            float minH = AndroidUtilities.dpf2(3);
            float fx = w * display;
            float rad = barW / 2f;

            int style;
            try {
                style = org.telegram.messenger.PrizmaConfig.seekStyle();
            } catch (Throwable e) {
                style = 0;
            }
            for (int i = 0; i < count; i++) {
                float x = i * step + gap / 2f;
                float cx = x + barW / 2f;
                // organic motion: base shape * beat amp + subtle time ripple
                float wiggle = (float) (0.12 * Math.sin(now / 190.0 + i * 0.6));
                float level = baseHeight(i) * (0.32f + 0.68f * amp) + wiggle;
                level = Math.max(0.08f, Math.min(1f, level));
                float bh = minH + level * (maxH - minH);
                boolean played = cx <= fx;
                r.set(x, cy - bh / 2f, x + barW, cy + bh / 2f);
                if (!played) {
                    p.setColor(0x40ffffff);
                    canvas.drawRoundRect(r, rad, rad, p);
                    continue;
                }
                int col;
                if (style == 1) { // горящий: тёплый градиент, мерцание + свечение
                    float heat = (float) (0.5 + 0.5 * Math.sin(now / 200.0 + i * 0.45));
                    col = blend(0xffFFC24B, 0xffFF2D2D, heat);
                    glowPaint.setColor((col & 0x00ffffff) | 0x55000000);
                    float gp = AndroidUtilities.dpf2(2.6f);
                    rGlow.set(x - gp, cy - bh / 2f - gp, x + barW + gp, cy + bh / 2f + gp);
                    canvas.drawRoundRect(rGlow, rad + gp, rad + gp, glowPaint);
                } else if (style == 2) { // переливающийся: бегущая радуга
                    hsv[0] = (float) (((i * 9) + now * 0.08) % 360.0);
                    hsv[1] = 0.82f;
                    hsv[2] = 1f;
                    col = Color.HSVToColor(hsv);
                } else { // дефолтный — акцент под обложку
                    col = accent;
                }
                p.setColor(col);
                canvas.drawRoundRect(r, rad, rad, p);
            }

            if (isPlaying || Math.abs(value - display) > 0.001f || Math.abs(target - amp) > 0.01f) {
                org.telegram.messenger.PrizmaConfig.nextDecorFrame(this);
            }
        }
    }

    // ===================== Spotify-style lyrics panel =====================

    interface OnSeekMs {
        void run(long ms);
    }

    private static class LyricsPanel extends FrameLayout {
        OnSeekMs onSeekToMs;

        private final LinearLayout island;
        private final TextView lineView;
        private final TextView nextView;
        private PrizmaLyrics.Lyrics lyrics;
        private int current = -1;

        LyricsPanel(Context c) {
            super(c);

            island = new LinearLayout(c);
            island.setOrientation(LinearLayout.VERTICAL);
            island.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(26));
            bg.setColor(0xE6000000); // near-black Dynamic Island
            island.setBackground(bg);
            island.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(9), AndroidUtilities.dp(20), AndroidUtilities.dp(9));
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                island.setElevation(AndroidUtilities.dpf2(6));
            }

            lineView = new TextView(c);
            lineView.setTextColor(0xffffffff);
            lineView.setTextSize(16);
            lineView.setTypeface(AndroidUtilities.bold());
            lineView.setGravity(Gravity.CENTER);
            lineView.setMaxLines(2);
            lineView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            lineView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            island.addView(lineView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            nextView = new TextView(c);
            nextView.setTextColor(0x66ffffff);
            nextView.setTextSize(13);
            nextView.setGravity(Gravity.CENTER);
            nextView.setMaxLines(1);
            nextView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            island.addView(nextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 3, 0, 0));

            int maxW = AndroidUtilities.displaySize.x - AndroidUtilities.dp(90);
            lineView.setMaxWidth(maxW);
            nextView.setMaxWidth(maxW);
            addView(island, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            island.setVisibility(GONE);
        }

        void setLoading() {
            lyrics = null;
            current = -1;
            island.setVisibility(VISIBLE);
            lineView.setText("Загрузка текста…");
            nextView.setText("");
        }

        void setLyrics(PrizmaLyrics.Lyrics l) {
            lyrics = l;
            current = -1;
            if (l == null || l.lines.isEmpty()) {
                island.setVisibility(GONE);
                return;
            }
            if (!l.synced) {
                // no timings — show a gentle hint, no karaoke
                island.setVisibility(VISIBLE);
                lineView.setText("♪");
                nextView.setText("текст без синхронизации");
                return;
            }
            island.setVisibility(VISIBLE);
            lineView.setText("♪");
            nextView.setText("");
        }

        void setPosition(long posMs) {
            if (lyrics == null || !lyrics.synced || lyrics.lines.isEmpty()) {
                return;
            }
            int idx = 0;
            ArrayList<PrizmaLyrics.Line> lines = lyrics.lines;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).timeMs <= posMs) idx = i;
                else break;
            }
            if (idx != current) {
                current = idx;
                animateTo(idx);
            }
        }

        private void animateTo(int idx) {
            final ArrayList<PrizmaLyrics.Line> lines = lyrics.lines;
            String cur = idx >= 0 && idx < lines.size() ? lines.get(idx).text : "";
            if (cur == null || cur.isEmpty()) cur = "♪";
            final String nxt = (idx + 1) < lines.size() ? lines.get(idx + 1).text : "";
            final String curF = cur;

            lineView.animate().cancel();
            lineView.animate().alpha(0f).translationY(-AndroidUtilities.dp(7)).setDuration(85)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .withEndAction(() -> {
                        lineView.setText(curF);
                        nextView.setText(nxt);
                        lineView.setTranslationY(AndroidUtilities.dp(9));
                        lineView.setAlpha(0f);
                        lineView.setScaleX(0.96f);
                        lineView.setScaleY(0.96f);
                        lineView.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                                .setDuration(300)
                                .setInterpolator(new OvershootInterpolator(1.6f))
                                .start();
                    }).start();
        }
    }

    // ===================== Up-next queue =====================

    private static final int ACCENT = 0xffFF4D6D;

    /** Scrollable "Up Next" list: tap a row to jump to that track. */
    private class QueueView extends LinearLayout {
        private final TextView countLabel;
        private final TextView nextLabel;
        private final LinearLayout list;
        private final ScrollView scroll;
        private TextView expand;

        void applyAccentColor() {
            if (expand != null) expand.setTextColor(accentColor);
            rebuild();
        }

        QueueView(Context c) {
            super(c);
            setOrientation(VERTICAL);

            // header: eyebrow "ОЧЕРЕДЬ" + track count
            LinearLayout header = new LinearLayout(c);
            header.setOrientation(HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(2));

            TextView eyebrow = new TextView(c);
            eyebrow.setText("ОЧЕРЕДЬ");
            eyebrow.setTextColor(0x8cffffff);
            eyebrow.setTextSize(11);
            eyebrow.setTypeface(AndroidUtilities.bold());
            eyebrow.setLetterSpacing(0.18f);
            header.addView(eyebrow, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            countLabel = new TextView(c);
            countLabel.setTextColor(0x66ffffff);
            countLabel.setTextSize(11);
            countLabel.setTypeface(AndroidUtilities.bold());
            countLabel.setLetterSpacing(0.04f);
            header.addView(countLabel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            expand = new TextView(c);
            expand.setText("Все  ›");
            expand.setTextColor(accentColor);
            expand.setTextSize(11.5f);
            expand.setTypeface(AndroidUtilities.bold());
            expand.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(2), AndroidUtilities.dp(4));
            header.addView(expand, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

            addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            expand.setOnClickListener(v -> showFull());

            // "Далее: <track>" — tells the user exactly what plays next
            nextLabel = new TextView(c);
            nextLabel.setTextColor(0xccffffff);
            nextLabel.setTextSize(12.5f);
            nextLabel.setMaxLines(1);
            nextLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nextLabel.setPadding(AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4), AndroidUtilities.dp(6));
            addView(nextLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            scroll = new ScrollView(c);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            // soft fade at top/bottom so tracks melt away instead of hard-cutting
            scroll.setVerticalFadingEdgeEnabled(true);
            scroll.setFadingEdgeLength(AndroidUtilities.dp(30));
            list = new LinearLayout(c);
            list.setOrientation(VERTICAL);
            scroll.addView(list, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        }

        void rebuild() {
            ArrayList<MessageObject> pl;
            try {
                pl = MediaController.getInstance().getPlaylist();
            } catch (Throwable e) {
                pl = null;
            }
            list.removeAllViews();
            if (pl == null || pl.isEmpty()) {
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            int curId = cur == null ? Integer.MIN_VALUE : cur.getId();
            int n = pl.size();
            countLabel.setText(n + " " + plural(n));

            // find the currently-playing position
            int curIndex = -1;
            for (int i = 0; i < n; i++) {
                MessageObject m = pl.get(i);
                if (m != null && m.getId() == curId) {
                    curIndex = i;
                    break;
                }
            }

            // Build real play order so the row under "now playing" is genuinely next.
            // Shuffle changes the actual order (not exposed here) — fall back to list order then.
            boolean shuffle = org.telegram.messenger.SharedConfig.shuffleMusic;
            int[] order = new int[n];
            if (curIndex >= 0 && !shuffle) {
                int dir = org.telegram.messenger.SharedConfig.playOrderReversed ? 1 : -1;
                int idx = curIndex;
                for (int k = 0; k < n; k++) {
                    order[k] = idx;
                    idx += dir;
                    if (idx < 0) idx = n - 1;
                    else if (idx >= n) idx = 0;
                }
            } else {
                for (int k = 0; k < n; k++) order[k] = k;
            }

            // "Далее: …" — the track that will actually play next (order[1])
            if (n > 1) {
                MessageObject nx = pl.get(order[1]);
                String t = nx == null ? null : nx.getMusicTitle();
                String a = nx == null ? null : nx.getMusicAuthor();
                String name = (t == null || t.isEmpty()) ? "Неизвестный трек" : t;
                if (a != null && !a.isEmpty()) name += " — " + a;
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("Далее  " + name);
                sb.setSpan(new android.text.style.ForegroundColorSpan(accentColor), 0, 5, 0);
                sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, 5, 0);
                nextLabel.setText(sb);
                nextLabel.setVisibility(VISIBLE);
            } else {
                nextLabel.setVisibility(GONE);
            }

            int max = 300; // guard against enormous chat music lists
            int added = 0;
            int curPos = -1;
            for (int k = 0; k < n && added < max; k++) {
                MessageObject mo = pl.get(order[k]);
                if (mo == null) continue;
                boolean isCur = mo.getId() == curId;
                View row = buildRow(mo, isCur, added + 1);
                if (isCur) curPos = list.getChildCount();
                list.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
                added++;
            }

            // bring the current track near the top so "what's next" is visible
            final int target = curPos;
            if (target >= 0) {
                scroll.post(() -> {
                    View child = target < list.getChildCount() ? list.getChildAt(target) : null;
                    if (child != null) {
                        scroll.smoothScrollTo(0, Math.max(0, child.getTop() - AndroidUtilities.dp(6)));
                    }
                });
            }
        }

        private String plural(int n) {
            int m10 = n % 10, m100 = n % 100;
            if (m10 == 1 && m100 != 11) return "трек";
            if (m10 >= 2 && m10 <= 4 && (m100 < 10 || m100 >= 20)) return "трека";
            return "треков";
        }

        /** Full-screen queue — every track, scrollable, tap to play. */
        void showFull() {
            ArrayList<MessageObject> pl;
            try {
                pl = MediaController.getInstance().getPlaylist();
            } catch (Throwable e) {
                return;
            }
            if (pl == null || pl.isEmpty()) {
                return;
            }
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            int curId = cur == null ? Integer.MIN_VALUE : cur.getId();
            int n = pl.size();
            int curIndex = -1;
            for (int i = 0; i < n; i++) {
                MessageObject m = pl.get(i);
                if (m != null && m.getId() == curId) { curIndex = i; break; }
            }
            boolean shuffle = org.telegram.messenger.SharedConfig.shuffleMusic;
            int[] order = new int[n];
            if (curIndex >= 0 && !shuffle) {
                int dir = org.telegram.messenger.SharedConfig.playOrderReversed ? 1 : -1;
                int idx = curIndex;
                for (int k = 0; k < n; k++) { order[k] = idx; idx += dir; if (idx < 0) idx = n - 1; else if (idx >= n) idx = 0; }
            } else {
                for (int k = 0; k < n; k++) order[k] = k;
            }

            LinearLayout sheetRoot = new LinearLayout(ctx);
            sheetRoot.setOrientation(VERTICAL);
            GradientDrawable rbg = new GradientDrawable();
            rbg.setColor(0xff141418);
            rbg.setCornerRadii(new float[]{AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), 0, 0, 0, 0});
            sheetRoot.setBackground(rbg);
            sheetRoot.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

            TextView t = new TextView(ctx);
            t.setText("Очередь · " + n + " " + plural(n));
            t.setTextColor(0xffffffff);
            t.setTextSize(18);
            t.setTypeface(AndroidUtilities.bold());
            sheetRoot.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

            LinearLayout fullList = new LinearLayout(ctx);
            fullList.setOrientation(VERTICAL);
            int added = 0;
            for (int k = 0; k < n; k++) {
                MessageObject mo = pl.get(order[k]);
                if (mo == null) continue;
                boolean isCur = mo.getId() == curId;
                fullList.addView(buildRow(mo, isCur, added + 1), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
                added++;
            }

            ScrollView sc = new ScrollView(ctx) {
                @Override
                protected void onMeasure(int ws, int hs) {
                    int maxH = (int) (AndroidUtilities.displaySize.y * 0.72f);
                    super.onMeasure(ws, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                }
            };
            sc.setVerticalScrollBarEnabled(false);
            sc.addView(fullList, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            sheetRoot.addView(sc, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            BottomSheet.Builder b = new BottomSheet.Builder(ctx);
            b.setCustomView(sheetRoot);
            BottomSheet s = b.create();
            try { s.setBackgroundColor(0xff141418); } catch (Throwable ignore) {}
            try { s.fixNavigationBar(0xff141418); } catch (Throwable ignore) {}
            // the inner list scroll was being read as a sheet-drag and dismissed the whole sheet
            // when you reached an edge and swiped back — close via tap-outside / back instead
            try { s.setCanDismissWithSwipe(false); } catch (Throwable ignore) {}
            s.show();
        }

        private View buildRow(MessageObject mo, boolean isCur, int index) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(10), 0, AndroidUtilities.dp(12), 0);
            if (isCur) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(14));
                bg.setColor(0x1fffffff);
                row.setBackground(bg);
            } else {
                row.setBackground(Theme.createSelectorDrawable(0x22ffffff, 2, AndroidUtilities.dp(14)));
            }

            // leading slot: index number, or live mini-equalizer when playing
            FrameLayout lead = new FrameLayout(ctx);
            if (isCur) {
                MiniEqBars eq = new MiniEqBars(ctx, accentColor);
                lead.addView(eq, LayoutHelper.createFrame(18, 15, Gravity.CENTER));
            } else {
                TextView num = new TextView(ctx);
                num.setText(String.valueOf(index));
                num.setTextColor(0x59ffffff);
                num.setTextSize(13);
                num.setGravity(Gravity.CENTER);
                lead.addView(num, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
            row.addView(lead, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(VERTICAL);

            TextView t = new TextView(ctx);
            String title = mo.getMusicTitle();
            t.setText(title == null || title.isEmpty() ? "Неизвестный трек" : title);
            t.setTextColor(isCur ? accentColor : 0xffffffff);
            t.setTextSize(15);
            t.setTypeface(isCur ? AndroidUtilities.bold() : Typeface.DEFAULT);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            TextView a = new TextView(ctx);
            String author = mo.getMusicAuthor();
            a.setText(author == null ? "" : author);
            a.setTextColor(0x80ffffff);
            a.setTextSize(12);
            a.setMaxLines(1);
            a.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(a, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));

            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            // trailing duration
            TextView dur = new TextView(ctx);
            dur.setText(formatTime((int) mo.getDuration()));
            dur.setTextColor(0x59ffffff);
            dur.setTextSize(12);
            row.addView(dur, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

            final MessageObject target = mo;
            row.setOnClickListener(v -> {
                if (MediaController.getInstance().getPlayingMessageObject() != null
                        && MediaController.getInstance().getPlayingMessageObject().getId() == target.getId()) {
                    return; // already playing this one
                }
                v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90)
                        .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                                .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start())
                        .start();
                try {
                    MediaController.getInstance().findMessageInPlaylistAndPlay(target);
                } catch (Throwable ignore) {}
            });
            return row;
        }
    }

    /** Tiny self-animating equalizer (independent of the shared visualizer listener). */
    private static class MiniEqBars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final long seed = System.currentTimeMillis();

        MiniEqBars(Context c, int color) {
            super(c);
            p.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            boolean playing;
            try {
                playing = MediaController.getInstance().getPlayingMessageObject() != null
                        && !MediaController.getInstance().isMessagePaused();
            } catch (Throwable e) {
                playing = true;
            }
            int w = getWidth(), h = getHeight();
            int bars = 3;
            float gap = AndroidUtilities.dpf2(2.5f);
            float bw = (w - gap * (bars - 1)) / bars;
            float rad = bw / 2f;
            long now = System.currentTimeMillis() - seed;
            for (int i = 0; i < bars; i++) {
                float level;
                if (playing) {
                    level = (float) (0.35 + 0.55 * Math.abs(Math.sin(now / 150.0 + i * 1.7)));
                } else {
                    level = 0.22f;
                }
                float bh = Math.max(AndroidUtilities.dpf2(3), level * h);
                float left = i * (bw + gap);
                r.set(left, (h - bh) / 2f, left + bw, (h + bh) / 2f);
                canvas.drawRoundRect(r, rad, rad, p);
            }
            if (playing) {
                org.telegram.messenger.PrizmaConfig.nextDecorFrame(this);
            }
        }
    }

    /** Ambient beat-reactive bars across the bottom of the player background. */
    private static class BeatBackground extends View implements PrizmaVisualizerData.Listener {
        private static final int BARS = 42;
        private final float[] target = new float[BARS];
        private final float[] cur = new float[BARS];
        private boolean playing;
        private int accent = ACCENT;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long lastTime;

        BeatBackground(Context c) {
            super(c);
        }

        void setAccent(int color) {
            accent = color;
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            PrizmaVisualizerData.setListener(this);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            PrizmaVisualizerData.clearListener(this);
        }

        @Override
        public void onWaveform(boolean playing, boolean animate, float[] values) {
            this.playing = playing;
            if (values != null && values.length > 0) {
                for (int i = 0; i < BARS; i++) {
                    float v = values[i % values.length];
                    if (v < 0) v = 0;
                    if (v > 1) v = 1;
                    target[i] = v;
                }
            } else {
                for (int i = 0; i < BARS; i++) target[i] = 0;
            }
            postInvalidateOnAnimation();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (lite()) return; // no ambient beat bars in Lite mode
            long now = System.currentTimeMillis();
            float dt = lastTime == 0 ? 16 : Math.min(40, now - lastTime);
            lastTime = now;
            float k = Math.min(1f, dt / 120f * 3f);
            boolean isPlaying;
            try {
                isPlaying = MediaController.getInstance().getPlayingMessageObject() != null
                        && !MediaController.getInstance().isMessagePaused();
            } catch (Throwable e) {
                isPlaying = playing;
            }
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            paint.setColor((accent & 0x00ffffff) | 0x33000000); // subtle accent-tinted, ~20% alpha
            float gap = w / (float) BARS;
            float barW = gap * 0.5f;
            float maxH = h * 0.26f;
            boolean need = false;
            for (int i = 0; i < BARS; i++) {
                float t = isPlaying ? target[i] : 0f;
                cur[i] += (t - cur[i]) * k;
                if (Math.abs(t - cur[i]) > 0.004f) need = true;
                float wiggle = isPlaying ? (float) (AndroidUtilities.dpf2(2) * Math.sin(now / 260.0 + i * 0.5)) : 0f;
                float bh = AndroidUtilities.dpf2(2) + cur[i] * maxH + wiggle;
                float left = i * gap + (gap - barW) / 2f;
                rect.set(left, h - bh, left + barW, h);
                canvas.drawRoundRect(rect, barW / 2f, barW / 2f, paint);
            }
            if (need || isPlaying) {
                org.telegram.messenger.PrizmaConfig.nextDecorFrame(this);
            }
        }
    }
}
