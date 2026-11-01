package org.telegram.messenger;

import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;

/**
 * Prizma: music status. While a track plays, the profile's "About" shows "🎧 Artist — Title",
 * visible to everyone like a normal bio. The real bio is saved first and put back ~1.5 minutes
 * after the music stops (also after a crash or restart — the saved bio survives).
 *
 * Telegram limits how often a profile can change, so updates are debounced (skipping through
 * tracks doesn't fire a request per track) and spaced at least 30 seconds apart.
 */
public final class PrizmaMusicStatus implements NotificationCenter.NotificationCenterDelegate {

    private static final String MARK = "🎧 ";
    private static final long DEBOUNCE_MS = 4_000L;
    private static final long MIN_INTERVAL_MS = 30_000L;
    private static final long RESTORE_AFTER_MS = 90_000L;

    private static PrizmaMusicStatus instance;

    private final String[] lastSent = new String[UserConfig.MAX_ACCOUNT_COUNT];
    private final long[] lastSentAt = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private int pendingAccount = -1;
    private String pendingText;

    private final Runnable setRunnable = () -> {
        if (pendingText != null && pendingAccount >= 0) apply(pendingAccount, pendingText);
    };
    private final Runnable restoreRunnable = this::restoreAll;

    private PrizmaMusicStatus() {}

    public static void init() {
        if (instance != null) return;
        instance = new PrizmaMusicStatus();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter nc = NotificationCenter.getInstance(a);
            nc.addObserver(instance, NotificationCenter.messagePlayingDidStart);
            nc.addObserver(instance, NotificationCenter.messagePlayingPlayStateChanged);
            nc.addObserver(instance, NotificationCenter.messagePlayingDidReset);
        }
        // a status left over from a crash / killed app gets cleaned up shortly after start
        AndroidUtilities.runOnUIThread(instance::evaluate, 8_000);
    }

    /** The toggle in Libre settings changed. */
    public static void onToggle() {
        if (instance != null) instance.evaluate();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        evaluate();
    }

    private void evaluate() {
        MessageObject mo = MediaController.getInstance().getPlayingMessageObject();
        boolean playing = mo != null && mo.isMusic() && !MediaController.getInstance().isMessagePaused();
        if (PrizmaConfig.musicStatus() && playing) {
            AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
            pendingAccount = mo.currentAccount;
            pendingText = format(mo, mo.currentAccount);
            AndroidUtilities.cancelRunOnUIThread(setRunnable);
            AndroidUtilities.runOnUIThread(setRunnable, DEBOUNCE_MS);
        } else {
            AndroidUtilities.cancelRunOnUIThread(setRunnable);
            pendingText = null;
            if (hasSavedBio()) {
                AndroidUtilities.cancelRunOnUIThread(restoreRunnable);
                AndroidUtilities.runOnUIThread(restoreRunnable, PrizmaConfig.musicStatus() ? RESTORE_AFTER_MS : 0);
            }
        }
    }

    private boolean hasSavedBio() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (PrizmaConfig.musicStatusOriginalBio(a) != null) return true;
        }
        return false;
    }

    private void apply(int account, String text) {
        if (!UserConfig.getInstance(account).isClientActivated() || text.equals(lastSent[account])) return;
        long now = SystemClock.elapsedRealtime();
        long wait = lastSentAt[account] + MIN_INTERVAL_MS - now;
        if (lastSentAt[account] != 0 && wait > 0) {
            AndroidUtilities.cancelRunOnUIThread(setRunnable);
            AndroidUtilities.runOnUIThread(setRunnable, wait);
            return;
        }
        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        if (self == null) return;
        TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(self.id);
        if (PrizmaConfig.musicStatusOriginalBio(account) == null) {
            // never overwrite the bio before we know (and saved) what it was
            if (full == null) {
                MessagesController.getInstance(account).loadFullUser(self, 0, true, f -> AndroidUtilities.runOnUIThread(this::evaluate));
                return;
            }
            PrizmaConfig.setMusicStatusOriginalBio(account, full.about == null ? "" : full.about);
        } else if (full != null && full.about != null && !full.about.startsWith(MARK)) {
            // the user wrote a new bio while music was playing — that's the one to bring back later
            PrizmaConfig.setMusicStatusOriginalBio(account, full.about);
        }
        lastSent[account] = text;
        lastSentAt[account] = now;
        sendBio(account, text, null, () -> {
            if (text.equals(lastSent[account])) lastSent[account] = null; // let the next try send it again
        });
    }

    private void restoreAll() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int account = a;
            final String original = PrizmaConfig.musicStatusOriginalBio(account);
            if (original == null) continue;
            lastSent[account] = null;
            if (!UserConfig.getInstance(account).isClientActivated()) {
                PrizmaConfig.setMusicStatusOriginalBio(account, null);
                continue;
            }
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(UserConfig.getInstance(account).getClientUserId());
            if (full != null && full.about != null && !full.about.startsWith(MARK)) {
                // the user wrote a new bio by hand meanwhile — keep theirs, forget the old one
                PrizmaConfig.setMusicStatusOriginalBio(account, null);
                continue;
            }
            sendBio(account, original, () -> PrizmaConfig.setMusicStatusOriginalBio(account, null), null);
        }
    }

    private static void sendBio(int account, String about, Runnable onDone, Runnable onError) {
        TL_account.updateProfile req = new TL_account.updateProfile();
        req.about = about;
        req.flags |= 4;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (err != null) { // e.g. FLOOD_WAIT — the next track change / restore tries again
                if (onError != null) onError.run();
                return;
            }
            long selfId = UserConfig.getInstance(account).getClientUserId();
            TLRPC.UserFull full = MessagesController.getInstance(account).getUserFull(selfId);
            if (full != null) {
                full.about = about;
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userInfoDidLoad, selfId, full);
            }
            if (onDone != null) onDone.run();
        }));
    }

    private static String format(MessageObject mo, int account) {
        String title = clean(mo.getMusicTitle());
        String author = clean(mo.getMusicAuthor());
        String s = MARK + (author.isEmpty() ? "" : author + " — ") + (title.isEmpty() ? "музыка" : title);
        int limit = UserConfig.getInstance(account).isPremium() ? 140 : 70;
        if (s.length() > limit) {
            int cut = limit - 1;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--; // don't split an emoji in half
            s = s.substring(0, cut) + "…";
        }
        return s;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("<unknown>", "").replace('\n', ' ').trim();
    }
}
