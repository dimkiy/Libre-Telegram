package org.telegram.ui.Components;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Random;

/*
 * Libre — что это за файл:
 * «Слушать вместе»: музыка синхронно у нескольких людей.
 */

/**
 * Prizma: "Listen together" (Libre ↔ Libre).
 *
 * The host sends the track to a chat as an ordinary audio message. Its caption says what is going
 * on in plain words ("🎧 Слушаем вместе в Libre · ▶ в эфире") and carries the playback state hidden
 * in zero-width characters: session id, position, the server time of that position (ms) and a flag.
 * Pausing or seeking edits that caption; the next track is sent as a new message and the previous
 * one is marked "следующий трек ниже". Plain Telegram just shows an audio message with that text.
 *
 * A friend's Libre shows a pill above the input while the session is live ({@link PrizmaTogetherBar}):
 * who is listening, where they are right now, and one button to join from the same second. Pressing
 * ▶ on the message itself joins too. Once joined, the friend's player follows every pause, seek and
 * track change; pausing or seeking by hand means "I'm on my own now" and leaves the session.
 *
 * Timing uses Telegram's server clock (both phones agree on it), so the drift is a fraction of a
 * second, not the phones' own clock difference.
 */
public final class PrizmaTogether implements NotificationCenter.NotificationCenterDelegate {

    private static final String HEAD_LIVE = "🎧 Слушаем вместе в Libre";
    private static final String HEAD_OVER = "🎧 Слушали вместе в Libre";
    private static final String PROTO = "LT1;";
    private static final char BIT0 = '‌', BIT1 = '‍'; // zero-width non-joiner / joiner
    private static final long EDIT_SPACING_MS = 1200;
    private static final long RESYNC_THRESHOLD_MS = 1500;

    public static final int FLAG_PAUSED = 0;
    public static final int FLAG_PLAYING = 1;
    public static final int FLAG_ENDED = 2;
    /** This track is over, the session goes on in a newer message. */
    public static final int FLAG_NEXT = 3;

    private static PrizmaTogether instance;

    public static PrizmaTogether get() {
        if (instance == null) instance = new PrizmaTogether();
        return instance;
    }

    /** Called once from LaunchActivity: start watching playback and chats. */
    public static void init() {
        PrizmaTogether t = get();
        if (t.inited) return;
        t.inited = true;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter nc = NotificationCenter.getInstance(a);
            nc.addObserver(t, NotificationCenter.messagePlayingDidStart);
            nc.addObserver(t, NotificationCenter.messagePlayingPlayStateChanged);
            nc.addObserver(t, NotificationCenter.messagePlayingDidSeek);
            nc.addObserver(t, NotificationCenter.messagePlayingDidReset);
            nc.addObserver(t, NotificationCenter.messageReceivedByServer);
            nc.addObserver(t, NotificationCenter.replaceMessagesObjects);
            nc.addObserver(t, NotificationCenter.didReceiveNewMessages);
        }
    }

    private boolean inited;
    private final Random random = new Random();
    private final ArrayList<Runnable> listeners = new ArrayList<>();

    // ---- host ----
    private boolean hosting;
    private int hostAccount;
    private long hostDialog;
    private long hostSid;
    private int hostMsgId;          // server id of the message carrying the current state
    private int prevMsgToClose;     // the previous track's message, marked "next track below" once the new one lands
    private long hostDocId;
    private boolean editPending;
    private long lastEditAt;

    // ---- guest ----
    private boolean following;
    private int guestAccount;
    private long guestDialog;
    private long guestSid;
    private int guestMsgId;
    private String guestHostName = "";
    private long guestHostId;
    private long guestEndsAt = Long.MAX_VALUE; // elapsedRealtime when the followed track should run out
    private long selfActionUntil;              // our own play/pause/seek — not the user's

    // ===================== state for the UI =====================

    public boolean isHosting() {
        return hosting;
    }

    public boolean isFollowing() {
        return following;
    }

    public boolean isHostingIn(int account, long dialogId) {
        return hosting && hostAccount == account && hostDialog == dialogId;
    }

    public boolean isFollowingIn(int account, long dialogId) {
        return following && guestAccount == account && guestDialog == dialogId;
    }

    public int getHostMsgId() {
        return hostMsgId;
    }

    public int getGuestMsgId() {
        return guestMsgId;
    }

    /** Who we're listening with (first name, or the chat's title for a channel). */
    public String getGuestHostName() {
        return guestHostName;
    }

    /** Where our session goes: the friend's first name or the group's title. */
    public String getHostChatName() {
        if (!hosting) return "";
        MessagesController mc = MessagesController.getInstance(hostAccount);
        if (DialogObject.isUserDialog(hostDialog)) {
            if (hostDialog == UserConfig.getInstance(hostAccount).getClientUserId()) return "Избранное";
            TLRPC.User u = mc.getUser(hostDialog);
            return u != null ? UserObject.getFirstName(u) : "";
        }
        TLRPC.Chat c = mc.getChat(-hostDialog);
        return c != null ? c.title : "";
    }

    /** UI listeners: run when hosting / following starts or stops. */
    public void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void notifyChanged() {
        for (Runnable r : new ArrayList<>(listeners)) {
            try {
                r.run();
            } catch (Throwable ignore) {
            }
        }
    }

    /** The session state hidden in a Libre "listen together" message, or null. */
    public static State stateOf(MessageObject m) {
        if (m == null || !m.isMusic() || m.messageOwner == null) return null;
        return State.parse(m.messageOwner.message);
    }

    /** Is this message's session still going on right now (not ended, not abandoned)? */
    public static boolean isLive(MessageObject m, State s) {
        if (m == null || s == null || (s.flag != FLAG_PLAYING && s.flag != FLAG_PAUSED)) return false;
        long now = ConnectionsManager.getInstance(m.currentAccount).getCurrentTimeMillis();
        if (s.flag == FLAG_PLAYING) {
            // a few seconds after the track should have ended there must be a newer message —
            // if there isn't, the host's phone went away without saying goodbye
            long durMs = (long) (m.getDuration() * 1000);
            return durMs <= 0 || s.posMs + (now - s.atMs) < durMs + 20_000;
        }
        return now - s.atMs < 30 * 60_000L; // paused for half an hour = over
    }

    /** Where the host is in the track right now, ms. */
    public static long hostPositionMs(MessageObject m, State s) {
        if (s == null) return 0;
        long pos = s.posMs;
        if (s.flag == FLAG_PLAYING) {
            pos += Math.max(0, ConnectionsManager.getInstance(m.currentAccount).getCurrentTimeMillis() - s.atMs);
        }
        long durMs = (long) (m.getDuration() * 1000);
        return durMs > 0 ? Math.min(pos, durMs) : pos;
    }

    /** First name of a message's sender (or the channel's title). */
    public static String senderName(MessageObject m) {
        long from = m.getFromChatId();
        MessagesController mc = MessagesController.getInstance(m.currentAccount);
        if (from > 0) {
            TLRPC.User u = mc.getUser(from);
            return u != null ? UserObject.getFirstName(u) : "";
        }
        TLRPC.Chat c = mc.getChat(-from);
        return c != null ? c.title : "";
    }

    // ===================== host =====================

    /** Player menu → "Слушать вместе…": pick a chat, then the current track goes there. */
    public static void pickChatAndStart(Context context) {
        final MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null || !mo.isMusic() || !(mo.getDocument() instanceof TLRPC.TL_document)) {
            Toast.makeText(context, "Сначала включите трек", Toast.LENGTH_SHORT).show();
            return;
        }
        if (mo.getDocument().id == 0 || mo.getDocument().access_hash == 0) {
            // a file that only lives on this phone — there's nothing on Telegram's servers to point to
            Toast.makeText(context, "Этот трек есть только на телефоне — включите трек из Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putBoolean("resetDelegate", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(mo.currentAccount);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids.isEmpty()) return false;
            long did = dids.get(0).dialogId;
            if (topicsFragment != null) {
                topicsFragment.finishFragment();
                fragment.removeSelfFromStack();
            } else {
                fragment.finishFragment();
            }
            get().startHost(mo.currentAccount, did);
            return true;
        });
        last.presentFragment(picker);
    }

    private void startHost(int account, long dialogId) {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null || !mo.isMusic()) return;
        if (hosting) stopHosting();
        stopFollowing(false);
        hosting = true;
        hostAccount = account;
        hostDialog = dialogId;
        hostSid = 1 + random.nextInt(Integer.MAX_VALUE - 1);
        prevMsgToClose = 0;
        sendTrack(mo);
        audience.clear();
        startPresence();
        org.telegram.messenger.PrizmaSocial.inc(org.telegram.messenger.PrizmaSocial.C_HOSTED);
        toast("Эфир начался — друзья в Libre услышат трек с той же секунды");
        notifyChanged();
    }

    public void stopHosting() {
        if (!hosting) return;
        AndroidUtilities.cancelRunOnUIThread(editRunnable);
        edit(hostMsgId, FLAG_ENDED);      // tell the guests it's over
        edit(prevMsgToClose, FLAG_ENDED);
        hosting = false;
        hostMsgId = 0;
        prevMsgToClose = 0;
        hostDocId = 0;
        stopPresence();
        toast("Эфир завершён");
        notifyChanged();
    }

    private void sendTrack(MessageObject mo) {
        if (!(mo.getDocument() instanceof TLRPC.TL_document) || mo.getDocument().id == 0) return;
        AndroidUtilities.cancelRunOnUIThread(editRunnable);
        hostDocId = mo.getDocument().id;
        hostMsgId = 0;
        editPending = false;
        int flag = currentFlag();
        String caption = caption(stateString(flag), flag);
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                (TLRPC.TL_document) mo.getDocument(), null, null, hostDialog, null, null, caption,
                null, null, null, true, 0, 0, 0, mo, null, false);
        SendMessagesHelper.getInstance(hostAccount).sendMessage(params);
    }

    private int currentFlag() {
        return MediaController.getInstance().getPlayingMessageObject() != null && !MediaController.getInstance().isMessagePaused()
                ? FLAG_PLAYING : FLAG_PAUSED;
    }

    /** "LT1;sid;positionMs;serverTimeMs;flag". */
    private String stateString(int flag) {
        long pos = 0;
        if (flag == FLAG_PLAYING || flag == FLAG_PAUSED) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo != null) pos = (long) (mo.audioProgress * mo.getDuration() * 1000);
        }
        long now = ConnectionsManager.getInstance(hostAccount).getCurrentTimeMillis();
        return PROTO + hostSid + ";" + pos + ";" + now + ";" + flag;
    }

    /** What everyone sees under the track — plain words; the state rides along invisibly. */
    private static String caption(String state, int flag) {
        String hidden = hide(state);
        switch (flag) {
            case FLAG_PLAYING:
                return HEAD_LIVE + hidden + " · ▶ в эфире";
            case FLAG_PAUSED:
                return HEAD_LIVE + hidden + " · ⏸ пауза";
            case FLAG_NEXT:
                return HEAD_OVER + hidden + " · следующий трек ниже ↓";
            default:
                return HEAD_OVER + hidden + " · эфир завершён";
        }
    }

    private final Runnable editRunnable = () -> {
        if (hosting && hostMsgId != 0) {
            lastEditAt = SystemClock.elapsedRealtime();
            edit(hostMsgId, currentFlag());
        }
    };

    private void scheduleEdit() {
        if (!hosting) return;
        if (hostMsgId == 0) {
            editPending = true; // the message isn't on the server yet — edit right after it lands
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(editRunnable);
        long wait = lastEditAt + EDIT_SPACING_MS - SystemClock.elapsedRealtime();
        AndroidUtilities.runOnUIThread(editRunnable, Math.max(150, wait));
    }

    private void edit(int msgId, int flag) {
        if (msgId == 0) return;
        TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
        req.peer = MessagesController.getInstance(hostAccount).getInputPeer(hostDialog);
        req.id = msgId;
        req.message = caption(stateString(flag), flag);
        req.flags |= 2048; // message
        final int account = hostAccount;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (res instanceof TLRPC.Updates) {
                MessagesController.getInstance(account).processUpdates((TLRPC.Updates) res, false);
            }
        });
    }

    // ===================== who's listening (Libre Cloud) =====================
    // A listener's Libre says "I'm here" every 15 s; the host's Libre asks who's here. The server
    // keeps it in memory only. Without the server everything else still works — just no names.

    private static final long PRESENCE_EVERY = 15_000;
    private final ArrayList<Long> audience = new ArrayList<>();
    private final Runnable presenceTick = this::presenceTick;

    /** Other Libre users in the same session right now (not counting yourself). */
    public ArrayList<Long> getAudience() {
        return audience;
    }

    /** "Аня, Макс и ещё 2" — or "" when nobody (or unknown). */
    public String audienceNames() {
        if (audience.isEmpty()) return "";
        int account = hosting ? hostAccount : guestAccount;
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(2, audience.size());
        for (int i = 0; i < shown; i++) {
            TLRPC.User u = MessagesController.getInstance(account).getUser(audience.get(i));
            if (sb.length() > 0) sb.append(", ");
            sb.append(u != null ? UserObject.getFirstName(u) : "друг");
        }
        if (audience.size() > shown) sb.append(" и ещё ").append(audience.size() - shown);
        return sb.toString();
    }

    private void startPresence() {
        AndroidUtilities.cancelRunOnUIThread(presenceTick);
        presenceTick();
    }

    private void stopPresence() {
        AndroidUtilities.cancelRunOnUIThread(presenceTick);
        if (!audience.isEmpty()) {
            audience.clear();
            notifyChanged();
        }
    }

    private void presenceTick() {
        if (!org.telegram.messenger.PrizmaCloud.configured() || !hosting && !following) return;
        final boolean asHost = hosting;
        final int account = asHost ? hostAccount : guestAccount;
        final long sid = asHost ? hostSid : guestSid;
        final long host = asHost ? UserConfig.getInstance(account).getClientUserId() : guestHostId;
        if (host <= 0) return;
        String path = "/v1/together/" + host + "/" + sid + (asHost ? "" : "/here");
        org.telegram.messenger.PrizmaCloud.call(account, asHost ? "GET" : "POST", path, null, r -> {
            // the session may have ended or changed while we were asking
            boolean same = asHost ? hosting && hostSid == sid : following && guestSid == sid;
            if (!same) return;
            if (r.code == 200 && r.body != null) {
                try {
                    org.json.JSONArray arr = new org.json.JSONObject(r.body).optJSONArray("listeners");
                    ArrayList<Long> got = new ArrayList<>();
                    if (arr != null) for (int i = 0; i < arr.length(); i++) got.add(arr.getLong(i));
                    if (!asHost) got.remove(host);
                    if (!got.equals(audience)) {
                        audience.clear();
                        audience.addAll(got);
                        notifyChanged();
                    }
                } catch (Throwable ignore) {
                }
            }
            AndroidUtilities.cancelRunOnUIThread(presenceTick);
            AndroidUtilities.runOnUIThread(presenceTick, PRESENCE_EVERY);
        });
    }

    // ===================== guest =====================

    /** The pill's "Слушать": start this track from where the host is right now. */
    public void join(MessageObject m) {
        State s = stateOf(m);
        if (s == null || !isLive(m, s)) {
            toast("Эфир уже закончился");
            return;
        }
        // can't lead one session and follow another: otherwise the friend's track would be
        // re-broadcast into our own chat as "the host's next track"
        if (hosting) stopHosting();
        MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
        if (cur != null && cur.getId() == m.getId() && cur.getDialogId() == m.getDialogId()) {
            // the track is already loaded (e.g. left a minute ago) — just catch up
            follow(cur, s, 0);
            return;
        }
        selfActionUntil = SystemClock.elapsedRealtime() + 2500;
        double durMs = m.getDuration() * 1000.0;
        long target = hostPositionMs(m, s);
        if (durMs > 0 && target > 0) {
            m.forceSeekTo = (float) Math.min(0.995, target / durMs); // start right there, no audible jump
        }
        ArrayList<MessageObject> one = new ArrayList<>();
        one.add(m);
        MediaController.getInstance().setPlaylist(one, m, 0, false, null);
    }

    /** Opens the host's track in our player, starting right where the host is. */
    private void playFromHost(MessageObject m, State s) {
        guestMsgId = m.getId();
        selfActionUntil = SystemClock.elapsedRealtime() + 2500;
        double durMs = m.getDuration() * 1000.0;
        long target = hostPositionMs(m, s);
        if (durMs > 0 && target > 300) {
            m.forceSeekTo = (float) Math.min(0.995, target / durMs);
        }
        ArrayList<MessageObject> one = new ArrayList<>();
        one.add(m);
        MediaController.getInstance().setPlaylist(one, m, 0, false, null);
    }

    /** "Выйти": stop following and stop the music. */
    public void leave() {
        if (!following) return;
        int msgId = guestMsgId;
        stopFollowing(false);
        MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
        if (cur != null && cur.getId() == msgId && !MediaController.getInstance().isMessagePaused()) {
            MediaController.getInstance().pauseMessage(cur);
        }
        toast("Вы вышли из эфира");
    }

    private void follow(MessageObject mo, State s, long delay) {
        boolean first = !following || guestSid != s.sid || guestAccount != mo.currentAccount;
        following = true;
        guestAccount = mo.currentAccount;
        guestDialog = mo.getDialogId();
        guestSid = s.sid;
        guestMsgId = mo.getId();
        guestHostName = senderName(mo);
        guestHostId = mo.getSenderId();
        guestEndsAt = Long.MAX_VALUE;
        selfActionUntil = SystemClock.elapsedRealtime() + delay + 1500;
        if (first) {
            audience.clear();
            startPresence();
            org.telegram.messenger.PrizmaSocial.inc(org.telegram.messenger.PrizmaSocial.C_JOINED);
            long pos = hostPositionMs(mo, s);
            toast(guestHostName.isEmpty()
                    ? "Вы в эфире — синхронно, с " + formatTime(pos)
                    : "Слушаете вместе: " + guestHostName + " — синхронно, с " + formatTime(pos));
        }
        // give the player a moment to open the track, then jump to where the host is
        if (delay <= 0) {
            apply(s);
        } else {
            AndroidUtilities.runOnUIThread(() -> apply(s), delay);
        }
        notifyChanged();
    }

    public void stopFollowing(boolean notify) {
        if (!following) return;
        following = false;
        // tell the room we're gone, so the host's list is right at once
        if (org.telegram.messenger.PrizmaCloud.configured() && guestHostId > 0) {
            org.telegram.messenger.PrizmaCloud.call(guestAccount, "DELETE", "/v1/together/" + guestHostId + "/" + guestSid + "/here", null, r -> {});
        }
        stopPresence();
        guestMsgId = 0;
        guestEndsAt = Long.MAX_VALUE;
        if (notify) toast("Вы вышли из эфира");
        notifyChanged();
    }

    /** The guest's track ran out a moment before the host's — the player shouldn't decide what comes next. */
    private boolean followedTrackJustEnded() {
        return SystemClock.elapsedRealtime() >= guestEndsAt - 3000;
    }

    /** Pause whatever the player started on its own and wait for the host's next track. */
    private void holdForHost(MessageObject started) {
        guestEndsAt = Long.MAX_VALUE;
        AndroidUtilities.runOnUIThread(() -> {
            MessageObject cur = MediaController.getInstance().getPlayingMessageObject();
            if (following && cur != null && cur.getId() == started.getId() && !MediaController.getInstance().isMessagePaused()) {
                selfActionUntil = SystemClock.elapsedRealtime() + 1000;
                MediaController.getInstance().pauseMessage(cur);
            }
        }, 60);
    }

    private void apply(State s) {
        if (!following) return;
        if (s.flag == FLAG_NEXT) return; // the next track's message is on its way and takes over
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        if (mo == null || mo.getId() != guestMsgId || mo.getDialogId() != guestDialog) return;
        if (s.flag == FLAG_ENDED) {
            stopFollowing(false);
            toast("Эфир завершён");
            return;
        }
        double durationMs = mo.getDuration() * 1000.0;
        if (durationMs <= 0) return;
        long target = hostPositionMs(mo, s);
        if (target >= durationMs - 500) return; // the host is already past this track
        selfActionUntil = SystemClock.elapsedRealtime() + 1500;
        double currentMs = mo.audioProgress * durationMs;
        if (Math.abs(currentMs - target) > RESYNC_THRESHOLD_MS) {
            MediaController.getInstance().seekToProgress(mo, (float) (target / durationMs));
        }
        boolean paused = MediaController.getInstance().isMessagePaused();
        if (s.flag == FLAG_PLAYING && paused) {
            MediaController.getInstance().playMessage(mo);
        } else if (s.flag == FLAG_PAUSED && !paused) {
            MediaController.getInstance().pauseMessage(mo);
        }
        guestEndsAt = s.flag == FLAG_PLAYING ? SystemClock.elapsedRealtime() + (long) (durationMs - target) : Long.MAX_VALUE;
    }

    // ===================== events =====================

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.messagePlayingDidStart) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (hosting && account == hostAccount) {
                // the host moved to another track → it goes to the chat as the next message
                if (mo.isMusic() && mo.getDocument() != null && mo.getDocument().id != hostDocId && mo.getDialogId() != hostDialog) {
                    if (hostMsgId != 0) prevMsgToClose = hostMsgId;
                    sendTrack(mo);
                } else if (mo.getDocument() != null && mo.getDocument().id == hostDocId) {
                    scheduleEdit(); // the same track started over (repeat) — guests jump back too
                }
                return;
            }
            if (following && mo.getId() != guestMsgId && followedTrackJustEnded()) {
                // the track ran out a moment before the host's next one arrived and the player
                // jumped to some other song of the chat — wait for the host instead
                holdForHost(mo);
                return;
            }
            State s = !mo.isOut() ? stateOf(mo) : null;
            if (s != null && isLive(mo, s)) {
                follow(mo, s, 700);
            } else {
                if (following && mo.getId() != guestMsgId) {
                    stopFollowing(true); // the guest picked something else
                }
                if (s != null && ConnectionsManager.getInstance(account).getCurrentTimeMillis() - s.atMs < 6 * 3600_000L) {
                    toast(s.flag == FLAG_NEXT ? "Это прошлый трек эфира — играет как обычный" : "Эфир уже закончился — трек играет как обычный");
                }
            }
        } else if (id == NotificationCenter.messagePlayingDidReset) {
            // the player was closed (switching tracks doesn't send this)
            if (hosting && account == hostAccount) {
                stopHosting();
            } else if (following && account == guestAccount && !followedTrackJustEnded()) {
                stopFollowing(true);
            }
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged || id == NotificationCenter.messagePlayingDidSeek) {
            MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
            if (mo == null) return;
            if (hosting && account == hostAccount) {
                if (mo.getDocument() != null && mo.getDocument().id == hostDocId) {
                    scheduleEdit();
                }
            } else if (following && account == guestAccount && mo.getId() == guestMsgId) {
                // the guest paused or seeked by hand: they're on their own now
                if (SystemClock.elapsedRealtime() < selfActionUntil || followedTrackJustEnded() || mo.audioProgress >= 0.98f) return;
                if (id == NotificationCenter.messagePlayingDidSeek || MediaController.getInstance().isMessagePaused()) {
                    stopFollowing(false);
                    toast("Вы вышли из эфира — вернуться можно кнопкой «Слушать» в чате");
                }
            }
        } else if (id == NotificationCenter.messageReceivedByServer) {
            if (!hosting || account != hostAccount || args.length < 3 || !(args[2] instanceof TLRPC.Message)) return;
            TLRPC.Message m = (TLRPC.Message) args[2];
            State s = State.parse(m.message);
            if (s != null && s.sid == hostSid) {
                hostMsgId = m.id;
                lastEditAt = SystemClock.elapsedRealtime();
                if (prevMsgToClose != 0 && prevMsgToClose != hostMsgId) {
                    // the guests have the new track by now — the old message says where to look
                    final int prev = prevMsgToClose;
                    prevMsgToClose = 0;
                    AndroidUtilities.runOnUIThread(() -> edit(prev, FLAG_NEXT), 1500);
                }
                if (editPending) {
                    editPending = false;
                    scheduleEdit();
                }
                notifyChanged();
            }
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            if (!following || account != guestAccount || !(args[0] instanceof Long) || (Long) args[0] != guestDialog) return;
            @SuppressWarnings("unchecked") ArrayList<MessageObject> list = (ArrayList<MessageObject>) args[1];
            for (MessageObject m : list) {
                if (m != null && m.getId() == guestMsgId) {
                    State s = State.parse(m.messageOwner != null ? m.messageOwner.message : null);
                    if (s == null || s.sid != guestSid) continue;
                    if (s.flag == FLAG_PLAYING && MediaController.getInstance().getPlayingMessageObject() == null) {
                        playFromHost(m, s); // our player already stopped at the end — the host started it over
                    } else {
                        apply(s);
                    }
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            if (!following || account != guestAccount || !(args[0] instanceof Long) || (Long) args[0] != guestDialog) return;
            if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) return; // scheduled
            @SuppressWarnings("unchecked") ArrayList<MessageObject> list = (ArrayList<MessageObject>) args[1];
            for (MessageObject m : list) {
                if (m == null || m.isOut() || !m.isMusic()) continue;
                State s = State.parse(m.messageOwner != null ? m.messageOwner.message : null);
                if (s != null && s.sid == guestSid) {
                    // the host went to the next track — follow it, from where the host already is
                    playFromHost(m, s);
                    notifyChanged();
                    break;
                }
            }
        }
    }

    // ===================== protocol =====================

    public static class State {
        public long sid, posMs, atMs;
        public int flag;

        static State parse(String caption) {
            String text = reveal(caption);
            if (text == null) return null;
            try {
                String[] p = text.substring(PROTO.length()).split(";");
                if (p.length < 4) return null;
                State s = new State();
                s.sid = Long.parseLong(p[0]);
                s.posMs = Long.parseLong(p[1]);
                s.atMs = Long.parseLong(p[2]);
                s.flag = Integer.parseInt(p[3]);
                return s;
            } catch (Throwable e) {
                return null;
            }
        }
    }

    /** Text → invisible characters (each byte as 8 zero-width "bits"). */
    private static String hide(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length * 8);
        for (byte b : bytes) {
            for (int i = 7; i >= 0; i--) {
                sb.append(((b >> i) & 1) == 1 ? BIT1 : BIT0);
            }
        }
        return sb.toString();
    }

    /** Finds the hidden run in a caption and decodes it; null when this isn't a Libre session. */
    private static String reveal(String caption) {
        if (caption == null || caption.indexOf(BIT0) < 0) return null;
        int bestStart = -1, bestLen = 0;
        int i = 0, n = caption.length();
        while (i < n) {
            char c = caption.charAt(i);
            if (c == BIT0 || c == BIT1) {
                int start = i;
                while (i < n && (caption.charAt(i) == BIT0 || caption.charAt(i) == BIT1)) i++;
                if (i - start > bestLen) {
                    bestLen = i - start;
                    bestStart = start;
                }
            } else {
                i++;
            }
        }
        if (bestLen < PROTO.length() * 8 || bestLen % 8 != 0) return null;
        byte[] bytes = new byte[bestLen / 8];
        for (int b = 0; b < bytes.length; b++) {
            int v = 0;
            for (int k = 0; k < 8; k++) {
                v = (v << 1) | (caption.charAt(bestStart + b * 8 + k) == BIT1 ? 1 : 0);
            }
            bytes[b] = (byte) v;
        }
        String s = new String(bytes, StandardCharsets.UTF_8);
        return s.startsWith(PROTO) ? s : null;
    }

    public static String formatTime(long ms) {
        long sec = Math.max(0, ms / 1000);
        return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private static void toast(String text) {
        try {
            Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}
