package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

/*
 * Libre — что это за файл:
 * Плашка «Слушать вместе» над полем ввода в чате.
 */

/**
 * Prizma: the "listen together" pill above the chat input. It answers the question a guest has
 * before touching anything — is somebody playing music here right now, where are they in the
 * track, and what happens if I press the button:
 *
 *  • someone is live    → "Аня слушает сейчас · 1:23 / 3:45 · Трек"   [Слушать]  (joins from that second)
 *  • you joined         → "Слушаете вместе: Аня · 1:24 / 3:45"         [Выйти]
 *  • you are the host   → "Вы ведёте эфир · 1:23 / 3:45 · Трек"        [Стоп]
 *
 * Tapping the pill itself scrolls to the session's message. Nothing is shown when no session is live.
 */
public class PrizmaTogetherBar extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int HIDDEN = 0, INVITE = 1, FOLLOWING = 2, HOSTING = 3;

    private final ChatActivity fragment;
    private final int account;
    private final long dialogId;
    private final Bars bars;
    private final TextView title, sub, button;
    private final GradientDrawable buttonBg = new GradientDrawable();

    private int mode = HIDDEN;
    private MessageObject liveMsg;
    private PrizmaTogether.State liveState;

    private final Runnable rescanRunnable = this::rescan;
    private final Runnable changed = () -> scheduleRescan(0);
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mode == HIDDEN || !isAttachedToWindow()) return;
            if (mode == INVITE && !PrizmaTogether.isLive(liveMsg, liveState)) {
                rescan(); // the session ran out while we were looking
                return;
            }
            updateTexts();
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public PrizmaTogetherBar(Context context, ChatActivity fragment) {
        super(context);
        this.fragment = fragment;
        this.account = fragment.getCurrentAccount();
        this.dialogId = fragment.getDialogId();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(AndroidUtilities.dp(7), AndroidUtilities.dp(7), AndroidUtilities.dp(7), AndroidUtilities.dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xf2202127);
        bg.setCornerRadius(AndroidUtilities.dp(26));
        bg.setStroke(Math.max(1, AndroidUtilities.dp(0.5f)), 0x24ffffff);
        setBackground(bg);
        setElevation(AndroidUtilities.dp(6));

        bars = new Bars(context);
        addView(bars, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        title = new TextView(context);
        title.setTextColor(0xffffffff);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        title.setTypeface(AndroidUtilities.bold());
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        sub = new TextView(context);
        sub.setTextColor(0xb3ffffff);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        button = new TextView(context);
        button.setTextColor(0xffffffff);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        button.setTypeface(AndroidUtilities.bold());
        button.setGravity(Gravity.CENTER);
        button.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
        buttonBg.setCornerRadius(AndroidUtilities.dp(19));
        button.setBackground(buttonBg);
        PrizmaGlass.pressable(button);
        button.setOnClickListener(v -> onButton());
        addView(button, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        setOnClickListener(v -> scrollToSession());
        setVisibility(GONE);
    }

    // ===================== lifecycle =====================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.addObserver(this, NotificationCenter.replaceMessagesObjects);
        nc.addObserver(this, NotificationCenter.messagesDidLoad);
        nc.addObserver(this, NotificationCenter.messagesDeleted);
        nc.addObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        PrizmaTogether.get().addListener(changed);
        scheduleRescan(300);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.removeObserver(this, NotificationCenter.didReceiveNewMessages);
        nc.removeObserver(this, NotificationCenter.replaceMessagesObjects);
        nc.removeObserver(this, NotificationCenter.messagesDidLoad);
        nc.removeObserver(this, NotificationCenter.messagesDeleted);
        nc.removeObserver(this, NotificationCenter.messagePlayingDidStart);
        nc.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        PrizmaTogether.get().removeListener(changed);
        AndroidUtilities.cancelRunOnUIThread(rescanRunnable);
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    @Override
    public void didReceivedNotification(int id, int acc, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart || id == NotificationCenter.messagePlayingPlayStateChanged) {
            if (mode == FOLLOWING || mode == HOSTING) updateTexts();
            return;
        }
        if (args.length > 0 && args[0] instanceof Long && (Long) args[0] != dialogId) return; // another chat
        scheduleRescan(150);
    }

    private void scheduleRescan(long delay) {
        AndroidUtilities.cancelRunOnUIThread(rescanRunnable);
        AndroidUtilities.runOnUIThread(rescanRunnable, delay);
    }

    // ===================== state =====================

    private void rescan() {
        PrizmaTogether t = PrizmaTogether.get();
        int newMode = HIDDEN;
        MessageObject msg = null;
        PrizmaTogether.State st = null;
        if (t.isHostingIn(account, dialogId)) {
            newMode = HOSTING;
        } else if (t.isFollowingIn(account, dialogId)) {
            newMode = FOLLOWING;
        } else if (fragment.messages != null) {
            // newest first; only the newest session message counts (older ones are past tracks)
            ArrayList<MessageObject> list = fragment.messages;
            int n = Math.min(list.size(), 300);
            for (int i = 0; i < n; i++) {
                MessageObject m = list.get(i);
                if (m == null || m.isOut() || !m.isMusic()) continue;
                PrizmaTogether.State s = PrizmaTogether.stateOf(m);
                if (s == null) continue;
                if (PrizmaTogether.isLive(m, s)) {
                    newMode = INVITE;
                    msg = m;
                    st = s;
                }
                break;
            }
        }
        liveMsg = msg;
        liveState = st;
        setMode(newMode);
    }

    private void setMode(int newMode) {
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        if (newMode != HIDDEN) {
            if (newMode == INVITE) {
                button.setText("Слушать");
                buttonBg.setColor(accent);
            } else {
                button.setText(newMode == HOSTING ? "Стоп" : "Выйти");
                buttonBg.setColor(0x33ffffff);
            }
            bars.setColor(accent);
        }
        boolean wasHidden = mode == HIDDEN;
        mode = newMode;
        AndroidUtilities.cancelRunOnUIThread(tick);
        if (newMode == HIDDEN) {
            if (getVisibility() == VISIBLE) {
                animate().cancel();
                animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(180)
                        .withEndAction(() -> {
                            if (mode == HIDDEN) setVisibility(GONE);
                        }).start();
            }
            return;
        }
        updateTexts();
        if (wasHidden || getVisibility() != VISIBLE) {
            setVisibility(VISIBLE);
            setAlpha(0f);
            setScaleX(0.92f);
            setScaleY(0.92f);
            animate().cancel();
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
        AndroidUtilities.runOnUIThread(tick, 1000);
    }

    private void updateTexts() {
        PrizmaTogether t = PrizmaTogether.get();
        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        boolean localPlaying = playing != null && !MediaController.getInstance().isMessagePaused();
        switch (mode) {
            case INVITE: {
                if (liveMsg == null || liveState == null) return;
                String name = PrizmaTogether.senderName(liveMsg);
                boolean live = liveState.flag == PrizmaTogether.FLAG_PLAYING;
                if (name.isEmpty()) {
                    title.setText(live ? "Сейчас идёт эфир" : "Эфир на паузе");
                } else {
                    title.setText(live ? name + " слушает сейчас" : name + " · пауза");
                }
                long pos = PrizmaTogether.hostPositionMs(liveMsg, liveState);
                sub.setText((live ? "▶ " : "⏸ ") + timeLine(pos, liveMsg) + trackName(liveMsg));
                bars.setPlaying(live);
                break;
            }
            case FOLLOWING: {
                String name = t.getGuestHostName();
                int others = t.getAudience().size();
                title.setText((name.isEmpty() ? "Вы в эфире" : "Слушаете вместе: " + name) + (others > 0 ? " · +" + others : ""));
                if (playing != null && playing.getId() == t.getGuestMsgId()) {
                    long pos = (long) (playing.audioProgress * playing.getDuration() * 1000);
                    sub.setText((localPlaying ? "▶ " : "⏸ ") + timeLine(pos, playing) + "синхронно");
                } else {
                    sub.setText("ждём следующий трек…");
                }
                bars.setPlaying(localPlaying);
                break;
            }
            case HOSTING: {
                int n = t.getAudience().size();
                title.setText(n > 0 ? "Вы ведёте эфир · слушают " + n : "Вы ведёте эфир");
                if (playing != null) {
                    long pos = (long) (playing.audioProgress * playing.getDuration() * 1000);
                    sub.setText((localPlaying ? "▶ " : "⏸ ") + timeLine(pos, playing) + trackName(playing));
                } else {
                    sub.setText("пауза и перемотка — у всех");
                }
                bars.setPlaying(localPlaying);
                break;
            }
        }
    }

    private static String timeLine(long posMs, MessageObject m) {
        long dur = (long) (m.getDuration() * 1000);
        return PrizmaTogether.formatTime(posMs) + (dur > 0 ? " / " + PrizmaTogether.formatTime(dur) : "") + " · ";
    }

    private static String trackName(MessageObject m) {
        String t = m.getMusicTitle();
        if (t == null || t.isEmpty() || t.equals("<unknown>")) return "трек";
        return t;
    }

    // ===================== actions =====================

    private void onButton() {
        PrizmaTogether t = PrizmaTogether.get();
        switch (mode) {
            case INVITE:
                if (liveMsg != null) t.join(liveMsg);
                break;
            case FOLLOWING:
                t.leave();
                break;
            case HOSTING:
                t.stopHosting();
                break;
        }
        scheduleRescan(200);
    }

    private void scrollToSession() {
        PrizmaTogether t = PrizmaTogether.get();
        int id = mode == INVITE && liveMsg != null ? liveMsg.getId()
                : mode == FOLLOWING ? t.getGuestMsgId()
                : mode == HOSTING ? t.getHostMsgId() : 0;
        if (id > 0) {
            try {
                fragment.scrollToMessageId(id, 0, true, 0, true, 0);
            } catch (Throwable ignore) {
            }
        }
    }

    // ===================== the little equalizer =====================

    private static class Bars extends View {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean playing;

        Bars(Context c) {
            super(c);
            bar.setColor(0xffffffff);
        }

        void setColor(int color) {
            if (circle.getColor() != color) {
                circle.setColor(color);
                invalidate();
            }
        }

        void setPlaying(boolean p) {
            if (playing != p) {
                playing = p;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            canvas.drawCircle(cx, cy, Math.min(w, h) / 2f, circle);
            float bw = AndroidUtilities.dpf2(3f), gap = AndroidUtilities.dpf2(2.5f);
            float minH = AndroidUtilities.dpf2(4f), maxH = h * 0.44f;
            boolean anim = playing && PrizmaConfig.fancyEffects();
            long now = SystemClock.uptimeMillis();
            float x0 = cx - (bw * 3 + gap * 2) / 2f;
            for (int i = 0; i < 3; i++) {
                float k;
                if (!playing) {
                    k = 0.3f;
                } else if (anim) {
                    k = 0.5f + 0.5f * (float) Math.sin(now / (170f + i * 40f) + i * 1.7f);
                } else {
                    k = i == 1 ? 1f : 0.6f;
                }
                float bh = minH + (maxH - minH) * k;
                float x = x0 + i * (bw + gap);
                r.set(x, cy - bh / 2f, x + bw, cy + bh / 2f);
                canvas.drawRoundRect(r, bw / 2f, bw / 2f, bar);
            }
            if (anim) PrizmaConfig.nextDecorFrame(this);
        }
    }
}
