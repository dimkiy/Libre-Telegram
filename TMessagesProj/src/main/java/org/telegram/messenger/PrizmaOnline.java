package org.telegram.messenger;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;

/**
 * Prizma: keeps the account shown as online by periodically pinging updateStatus(offline=false).
 * Disabled automatically when "hide online" is on (the two would contradict).
 */
public final class PrizmaOnline {

    private static final long INTERVAL = 55_000L;
    private static boolean running;

    private PrizmaOnline() {}

    public static void start() {
        if (running) {
            return;
        }
        running = true;
        AndroidUtilities.cancelRunOnUIThread(tick);
        AndroidUtilities.runOnUIThread(tick, 800);
    }

    public static void stop() {
        running = false;
        AndroidUtilities.cancelRunOnUIThread(tick);
    }

    private static final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!PrizmaConfig.alwaysOnline() || PrizmaConfig.hideOnline()) {
                running = false;
                return;
            }
            try {
                int acc = UserConfig.selectedAccount;
                if (UserConfig.getInstance(acc).isClientActivated()) {
                    TL_account.updateStatus req = new TL_account.updateStatus();
                    req.offline = false;
                    ConnectionsManager.getInstance(acc).sendRequest(req, null);
                }
            } catch (Throwable ignore) {}
            AndroidUtilities.runOnUIThread(this, INTERVAL);
        }
    };
}
