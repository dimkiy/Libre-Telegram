package org.telegram.messenger;

/*
 * Libre — что это за файл:
 * Таймер сна: ставит музыку на паузу через выбранное время.
 */

/**
 * Prizma: sleep timer — pauses music playback after a chosen number of minutes.
 */
public class PrizmaSleepTimer {

    private static Runnable pending;
    private static long fireAt;

    public static void schedule(int minutes) {
        cancel();
        if (minutes <= 0) {
            return;
        }
        final long delay = minutes * 60000L;
        fireAt = System.currentTimeMillis() + delay;
        pending = () -> {
            try {
                MediaController mc = MediaController.getInstance();
                MessageObject mo = mc.getPlayingMessageObject();
                if (mo != null && !mc.isMessagePaused()) {
                    mc.pauseMessage(mo);
                }
            } catch (Throwable ignore) {
            }
            pending = null;
            fireAt = 0;
        };
        AndroidUtilities.runOnUIThread(pending, delay);
    }

    public static void cancel() {
        if (pending != null) {
            AndroidUtilities.cancelRunOnUIThread(pending);
            pending = null;
        }
        fireAt = 0;
    }

    public static boolean isActive() {
        return pending != null && fireAt > System.currentTimeMillis();
    }

    public static int getRemainingMinutes() {
        if (fireAt == 0) {
            return 0;
        }
        long ms = fireAt - System.currentTimeMillis();
        return ms <= 0 ? 0 : (int) Math.ceil(ms / 60000.0);
    }
}
