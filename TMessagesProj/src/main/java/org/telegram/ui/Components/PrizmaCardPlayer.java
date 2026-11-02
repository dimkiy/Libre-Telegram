package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/*
 * Libre — что это за файл:
 * «Новый плеер (бета)»: спокойный плеер-карточка с размытой обложкой на фоне.
 */

/**
 * Prizma: «Новый плеер (бета)» — a calm card player: the cover blurred behind everything, one
 * frosted card with the big cover, title, a thin progress line, three controls, volume and the
 * output device. Switched on (and off) from the full player's menu.
 */
public class PrizmaCardPlayer extends Dialog {

    private static boolean open;

    public static boolean isOpen() {
        return open;
    }

    public static void show(Context context) {
        if (context == null || open || MediaController.getInstance().getPlayingMessageObject() == null) return;
        try {
            new PrizmaCardPlayer(context).show();
        } catch (Throwable ignore) {
        }
    }

    private final Context ctx;
    private FrameLayout root;
    private LinearLayout card;
    private BackupImageView cover;
    private TextView title, author, timeLeft, timeRight, device;
    private PrizmaWaveBars bars;
    private Line progress, volume;
    private PlayPauseDrawable playDrawable;
    private AudioManager audio;

    private Bitmap backdrop;          // the cover, small and blurred
    private long backdropFor = Long.MIN_VALUE;
    private int tint = 0xff1b2a4a;    // the cover's mood, for the card and the background
    private long loadedId = Long.MIN_VALUE;
    private boolean running;
    private final Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Rect dst = new Rect();

    public PrizmaCardPlayer(Context context) {
        super(context);
        ctx = context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        audio = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        root = new FrameLayout(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth(), h = getHeight();
                canvas.drawColor(0xff0b0f1c);
                if (backdrop != null && !backdrop.isRecycled()) {
                    // centre-crop the blurred cover over the whole screen
                    float s = Math.max(w / (float) backdrop.getWidth(), h / (float) backdrop.getHeight());
                    int bw = (int) (backdrop.getWidth() * s), bh = (int) (backdrop.getHeight() * s);
                    dst.set((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
                    canvas.drawBitmap(backdrop, null, dst, bgPaint);
                }
                shade.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x66000000, 0x33000000, 0x99000000},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, w, h, shade);
            }
        };
        root.setWillNotDraw(false);
        root.setOnClickListener(v -> dismiss()); // a tap beside the card closes it

        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(18));
        card.setClickable(true); // taps on the card don't close it
        root.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 18, 0, 18, 0));

        // top: grabber + menu
        FrameLayout top = new FrameLayout(ctx);
        View grab = new View(ctx);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0x55ffffff);
        gb.setCornerRadius(AndroidUtilities.dp(2));
        grab.setBackground(gb);
        top.addView(grab, LayoutHelper.createFrame(36, 4, Gravity.CENTER));
        ImageView more = new ImageView(ctx);
        more.setImageResource(R.drawable.ic_ab_other);
        more.setColorFilter(0xccffffff);
        more.setScaleType(ImageView.ScaleType.CENTER);
        more.setOnClickListener(v -> showMenu());
        top.addView(more, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, -8, 0));
        card.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 30, 0, -8, 0, 8));

        // the cover: a square
        cover = new BackupImageView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, widthMeasureSpec);
            }
        };
        cover.setRoundRadius(AndroidUtilities.dp(14));
        card.addView(cover, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // title + artist, a small live waveform on the right
        LinearLayout meta = new LinearLayout(ctx);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(ctx);
        title.setTextColor(0xffffffff);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        author = new TextView(ctx);
        author.setTextColor(0x99ffffff);
        author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        author.setSingleLine(true);
        author.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(author, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        meta.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        bars = new PrizmaWaveBars(ctx);
        bars.setBarColor(0x88ffffff);
        meta.addView(bars, LayoutHelper.createLinear(22, 18, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));
        card.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 0));

        // progress
        progress = new Line(ctx, false);
        progress.listener = (p, done) -> {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (done) MediaController.getInstance().seekToProgress(mo, p);
            updateTimes(mo, p);
        };
        card.addView(progress, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28, 0, 18, 0, 0));
        FrameLayout times = new FrameLayout(ctx);
        timeLeft = timeLabel();
        timeRight = timeLabel();
        times.addView(timeLeft, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT));
        times.addView(timeRight, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT));
        card.addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, -2, 0, 0));

        // ⏮ ⏯ ⏭
        LinearLayout controls = new LinearLayout(ctx);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        Skip prev = new Skip(ctx, false);
        prev.setOnClickListener(v -> {
            press(v);
            MediaController.getInstance().playPreviousMessage();
        });
        controls.addView(prev, LayoutHelper.createLinear(72, 64));
        ImageView play = new ImageView(ctx);
        playDrawable = new PlayPauseDrawable(44);
        playDrawable.setPause(!MediaController.getInstance().isMessagePaused(), false);
        play.setImageDrawable(playDrawable);
        play.setColorFilter(0xffffffff);
        play.setScaleType(ImageView.ScaleType.CENTER);
        play.setOnClickListener(v -> {
            press(v);
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (MediaController.getInstance().isMessagePaused()) MediaController.getInstance().playMessage(mo);
            else MediaController.getInstance().pauseMessage(mo);
        });
        controls.addView(play, LayoutHelper.createLinear(96, 72, Gravity.CENTER_VERTICAL, 22, 0, 22, 0));
        Skip next = new Skip(ctx, true);
        next.setOnClickListener(v -> {
            press(v);
            MediaController.getInstance().playNextMessage();
        });
        controls.addView(next, LayoutHelper.createLinear(72, 64));
        card.addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        // volume: 🔈 ───── 🔊
        LinearLayout vol = new LinearLayout(ctx);
        vol.setOrientation(LinearLayout.HORIZONTAL);
        vol.setGravity(Gravity.CENTER_VERTICAL);
        vol.addView(new Speaker(ctx, false), LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL));
        volume = new Line(ctx, true);
        volume.listener = (p, done) -> {
            if (audio == null) return;
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(p * max), 0);
        };
        vol.addView(volume, LayoutHelper.createLinear(0, 28, 1f, Gravity.CENTER_VERTICAL, 10, 0, 10, 0));
        vol.addView(new Speaker(ctx, true), LayoutHelper.createLinear(24, 22, Gravity.CENTER_VERTICAL));
        card.addView(vol, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 0));

        // where the sound goes
        device = new TextView(ctx);
        device.setTextColor(0xddffffff);
        device.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        device.setGravity(Gravity.CENTER);
        device.setPadding(AndroidUtilities.dp(18), 0, AndroidUtilities.dp(18), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x26ffffff);
        pill.setCornerRadius(AndroidUtilities.dp(20));
        device.setBackground(pill);
        card.addView(device, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Window w = getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (Build.VERSION.SDK_INT >= 21) {
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(0xff0b0f1c);
            }
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        paintCard();
    }

    private TextView timeLabel() {
        TextView t = new TextView(ctx);
        t.setTextColor(0x99ffffff);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        return t;
    }

    private static void press(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        v.animate().cancel();
        v.setScaleX(0.86f);
        v.setScaleY(0.86f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
    }

    /** The frosted card takes the cover's mood. */
    private void paintCard() {
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                withAlpha(blend(tint, 0xff000000, 0.25f), 0xcc), withAlpha(blend(tint, 0xff000000, 0.55f), 0xe6)});
        bg.setCornerRadius(AndroidUtilities.dp(36));
        card.setBackground(bg);
        progress.setColors(0xccffffff, 0x33ffffff);
        volume.setColors(0xccffffff, 0x33ffffff);
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00ffffff) | (a << 24);
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    @Override
    public void show() {
        super.show();
        open = true;
        running = true;
        update(true);
        AndroidUtilities.runOnUIThread(ticker);
        // the card rises and settles
        card.setAlpha(0f);
        card.setTranslationY(AndroidUtilities.dp(40));
        card.setScaleX(0.96f);
        card.setScaleY(0.96f);
        card.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    @Override
    public void dismiss() {
        running = false;
        open = false;
        AndroidUtilities.cancelRunOnUIThread(ticker);
        try {
            super.dismiss();
        } catch (Throwable ignore) {
        }
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!ApplicationLoader.isScreenOn || ApplicationLoader.mainInterfacePaused) {
                AndroidUtilities.runOnUIThread(this, 500);
                return;
            }
            update(false);
            AndroidUtilities.runOnUIThread(this, 80);
        }
    };

    private void update(boolean force) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null) {
            dismiss();
            return;
        }
        if (force || trackKey(mo) != loadedId) {
            boolean animate = !force && loadedId != Long.MIN_VALUE;
            loadedId = trackKey(mo);
            String t = mo.getMusicTitle(), a = mo.getMusicAuthor();
            title.setText(TextUtils.isEmpty(t) ? "Неизвестный трек" : t);
            author.setText(a == null ? "" : a);
            if (animate) {
                cover.setAlpha(0.3f);
                cover.animate().alpha(1f).setDuration(300).start();
            }
            loadCover(mo);
        }
        checkLateCover();
        if (backdropFor != trackKey(mo)) buildBackdrop(mo);
        boolean paused = MediaController.getInstance().isMessagePaused();
        playDrawable.setPause(!paused, true);
        if (!progress.dragging) {
            progress.setValue(mo.audioProgress);
            updateTimes(mo, mo.audioProgress);
        }
        if (audio != null && !volume.dragging) {
            int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
            volume.setValue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max);
        }
        device.setText(outputName());
    }

    private void updateTimes(MessageObject mo, float p) {
        int dur = (int) mo.getDuration();
        int cur = (int) (dur * p);
        timeLeft.setText(formatTime(cur));
        timeRight.setText("−" + formatTime(Math.max(0, dur - cur)));
    }

    private String outputName() {
        if (audio == null) return "Телефон";
        try {
            if (audio.isBluetoothA2dpOn()) return "🎧  Bluetooth";
            if (audio.isWiredHeadsetOn()) return "🎧  Наушники";
        } catch (Throwable ignore) {
        }
        return "📱  Телефон";
    }

    private static String formatTime(int sec) {
        if (sec < 0) sec = 0;
        return sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60;
    }

    /**
     * Обложка: вшитая в файл → iTunes → картинка Telegram. Под ними всегда заглушка, чтобы не было
     * чёрного квадрата, а когда файл докачается, checkLateCover() подставит вшитую обложку.
     */
    private void loadCover(MessageObject mo) {
        Bitmap bmp = audioInfoCover();
        if (bmp != null) {
            coverWaiting = false;
            cover.setImageBitmap(bmp);
            return;
        }
        coverWaiting = true;
        String artwork = mo.getArtworkUrl(false);
        TLRPC.Document doc = mo.getDocument();
        TLRPC.PhotoSize thumb = doc != null ? FileLoader.getClosestPhotoSizeWithSize(doc.thumbs, 360) : null;
        ImageLocation thumbLoc = thumb instanceof TLRPC.TL_photoSize || thumb instanceof TLRPC.TL_photoSizeProgressive
                ? ImageLocation.getForDocument(thumb, doc) : null;
        GradientDrawable ph = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff2a3556, 0xff141a2c});
        ph.setCornerRadius(AndroidUtilities.dp(14));
        if (artwork != null && !artwork.isEmpty()) {
            cover.setImage(ImageLocation.getForPath(artwork), null, thumbLoc, null, ph, null, 0, 1, mo);
        } else if (thumbLoc != null) {
            cover.setImage(null, null, thumbLoc, null, ph, null, 0, 1, mo);
        } else {
            cover.setImageDrawable(ph);
        }
    }

    private boolean coverWaiting; // на обложке пока заглушка

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

    /** Один трек = чат + номер сообщения (в разных чатах номера повторяются). */
    private static long trackKey(MessageObject mo) {
        return mo.getDialogId() * 1_000_003L + mo.getId();
    }

    /** Waits for the cover bitmap, then blurs a small copy for the background (off the UI thread). */
    private void buildBackdrop(MessageObject mo) {
        Bitmap src = null;
        try {
            src = cover.getImageReceiver() != null ? cover.getImageReceiver().getBitmap() : null;
        } catch (Throwable ignore) {
        }
        if (src == null || src.isRecycled()) return;
        final long id = trackKey(mo);
        backdropFor = id;
        final Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, 48, 48, true).copy(Bitmap.Config.ARGB_8888, true);
        } catch (Throwable e) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            int r = 0, g = 0, b = 0;
            for (int x = 0; x < 48; x += 4) {
                for (int y = 0; y < 48; y += 4) {
                    int c = small.getPixel(x, y);
                    r += Color.red(c);
                    g += Color.green(c);
                    b += Color.blue(c);
                }
            }
            final int avg = Color.rgb(r / 144, g / 144, b / 144);
            try {
                Utilities.stackBlurBitmap(small, 8);
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (!running || id != loadedId) return;
                backdrop = small;
                // keep it moody: never brighter than a dusky tone
                float[] hsv = new float[3];
                Color.colorToHSV(avg, hsv);
                hsv[2] = Math.min(hsv[2], 0.42f);
                hsv[1] = Math.min(hsv[1], 0.65f);
                int target = Color.HSVToColor(hsv);
                final int from = tint;
                ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(400);
                va.addUpdateListener(an -> {
                    tint = blend(from, target, (float) an.getAnimatedValue());
                    paintCard();
                });
                va.start();
                root.invalidate();
            });
        });
    }

    private void showMenu() {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];
        if (mo != null) {
            final boolean onProfile = PrizmaProfileMusic.isSaved(mo.currentAccount, mo);
            list.addView(row(textColor, onProfile ? R.drawable.msg_delete : R.drawable.left_status_profile,
                    onProfile ? "Убрать из профиля Telegram" : "Добавить в профиль Telegram", () -> {
                        ref[0].dismiss();
                        PrizmaProfileMusic.toggle(mo.currentAccount, mo, text -> Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show());
                    }));
        }
        list.addView(row(textColor, R.drawable.msg_fave, "Мой плейлист", () -> {
            ref[0].dismiss();
            PrizmaPlaylistSheet.show(ctx);
        }));
        list.addView(row(textColor, R.drawable.msg_media, "Вернуть обычный плеер", () -> {
            ref[0].dismiss();
            org.telegram.messenger.PrizmaConfig.setBetaPlayer(false);
            dismiss();
            AndroidUtilities.runOnUIThread(() -> PrizmaFullPlayer.show(ctx), 220);
        }));
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(list);
        ref[0] = b.create();
        ref[0].show();
    }

    private View row(int color, int icon, String text, Runnable onClick) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(13), AndroidUtilities.dp(14), AndroidUtilities.dp(13));
        r.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setColorFilter(color);
        iv.setScaleType(ImageView.ScaleType.CENTER);
        r.addView(iv, LayoutHelper.createLinear(26, 26, Gravity.CENTER_VERTICAL, 0, 0, 16, 0));
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(16);
        r.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        r.setOnClickListener(v -> onClick.run());
        return r;
    }

    // ===================== pieces =====================

    /** A thin rounded line you can drag; it thickens a little under the finger. */
    private static class Line extends View {
        interface Listener {
            void onChange(float value, boolean done);
        }

        final boolean volume;
        float value;
        boolean dragging;
        Listener listener;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float grow;

        Line(Context c, boolean volume) {
            super(c);
            this.volume = volume;
        }

        void setColors(int f, int t) {
            fill.setColor(f);
            track.setColor(t);
            invalidate();
        }

        void setValue(float v) {
            value = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float p = Math.max(0f, Math.min(1f, e.getX() / Math.max(1, getWidth())));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    animateGrow(1f);
                    // falls through: a tap jumps right there
                case MotionEvent.ACTION_MOVE:
                    setValue(p);
                    if (listener != null) listener.onChange(p, volume);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    animateGrow(0f);
                    if (listener != null) listener.onChange(value, true);
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void animateGrow(float to) {
            ValueAnimator va = ValueAnimator.ofFloat(grow, to);
            va.setDuration(160);
            va.addUpdateListener(a -> {
                grow = (float) a.getAnimatedValue();
                invalidate();
            });
            va.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = AndroidUtilities.dp(6) + AndroidUtilities.dp(4) * grow;
            float cy = getHeight() / 2f;
            r.set(0, cy - h / 2, getWidth(), cy + h / 2);
            canvas.drawRoundRect(r, h / 2, h / 2, track);
            r.right = Math.max(h, getWidth() * value);
            canvas.drawRoundRect(r, h / 2, h / 2, fill);
        }
    }

    /** ⏮ / ⏭ drawn as two plain triangles, like the reference. */
    private static class Skip extends View {
        private final boolean forward;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Skip(Context c, boolean forward) {
            super(c);
            this.forward = forward;
            p.setColor(0xffffffff);
            p.setPathEffect(new android.graphics.CornerPathEffect(AndroidUtilities.dp(2)));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float s = AndroidUtilities.dp(13);
            path.reset();
            for (int i = 0; i < 2; i++) {
                float x0 = cx - s + i * s;
                if (forward) {
                    path.moveTo(x0, cy - s * 0.82f);
                    path.lineTo(x0 + s, cy);
                    path.lineTo(x0, cy + s * 0.82f);
                } else {
                    path.moveTo(x0 + s, cy - s * 0.82f);
                    path.lineTo(x0, cy);
                    path.lineTo(x0 + s, cy + s * 0.82f);
                }
                path.close();
            }
            canvas.drawPath(path, p);
        }
    }

    /** A small speaker with no / two sound waves. */
    private static class Speaker extends View {
        private final boolean loud;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF arc = new RectF();

        Speaker(Context c, boolean loud) {
            super(c);
            this.loud = loud;
            fill.setColor(0x99ffffff);
            stroke.setColor(0x99ffffff);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(AndroidUtilities.dp(1.6f));
            stroke.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cy = getHeight() / 2f, x = AndroidUtilities.dp(2);
            float u = AndroidUtilities.dp(1);
            path.reset();
            path.moveTo(x, cy - 3 * u);
            path.lineTo(x + 4 * u, cy - 3 * u);
            path.lineTo(x + 9 * u, cy - 7 * u);
            path.lineTo(x + 9 * u, cy + 7 * u);
            path.lineTo(x + 4 * u, cy + 3 * u);
            path.lineTo(x, cy + 3 * u);
            path.close();
            canvas.drawPath(path, fill);
            if (loud) {
                for (int i = 1; i <= 2; i++) {
                    float rr = 4 * u * i;
                    arc.set(x + 9 * u - rr, cy - rr, x + 9 * u + rr, cy + rr);
                    canvas.drawArc(arc, -45, 90, false, stroke);
                }
            }
        }
    }
}
