package org.telegram.messenger;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;

/**
 * Prizma: «Уведомления из MAX» — whatever MAX shows as a notification on this phone (any chat,
 * personal or group) arrives in Telegram from the Libre bot.
 *
 * MAX has no way for an app to read someone's chats, but Android lets an app the person allowed
 * («Доступ к уведомлениям») see other apps' notifications. Libre reads only MAX's, only while the
 * switch in the hub is on, and passes each message through Libre Cloud to the Libre bot — nothing is
 * kept on the phone or the server. Only one way: there's nothing to answer from.
 */
public class PrizmaMaxListener extends NotificationListenerService {

    /** MAX by VK; a renamed or regional build is caught by its app name below. */
    private static final String[] MAX_PACKAGES = {"ru.oneme.app"};

    private static final HashMap<String, Long> lastTime = new HashMap<>();   // notification key → newest message forwarded
    private static final LinkedHashSet<String> recent = new LinkedHashSet<>(); // what went out lately (MAX re-posts the same lines)
    private static final HashMap<String, Boolean> isMaxCache = new HashMap<>();
    private static long warnedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ApplicationLoader.postInitApplication();
        } catch (Throwable ignore) {
        }
    }

    // ===================== settings =====================

    public static boolean accessGranted(Context ctx) {
        try {
            return androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.getPackageName());
        } catch (Throwable e) {
            return false;
        }
    }

    /** Asks Android to re-bind the listener (after the switch is turned on, or a restart killed it). */
    public static void rebind(Context ctx) {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                requestRebind(new ComponentName(ctx, PrizmaMaxListener.class));
            } catch (Throwable ignore) {
            }
        }
    }

    // ===================== reading =====================

    private boolean isMax(String pkg) {
        if (pkg == null || pkg.equals(getPackageName())) return false;
        for (String p : MAX_PACKAGES) if (p.equals(pkg)) return true;
        Boolean cached = isMaxCache.get(pkg);
        if (cached != null) return cached;
        boolean max = false;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            max = label != null && ("MAX".equalsIgnoreCase(label.toString().trim()) || "Макс".equalsIgnoreCase(label.toString().trim()));
        } catch (Throwable ignore) {
        }
        isMaxCache.put(pkg, max);
        return max;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            if (sbn == null || !PrizmaConfig.maxForward() || !isMax(sbn.getPackageName())) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
            Bundle ex = n.extras;
            if (ex == null) return;
            String title = str(ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE));
            if (title.isEmpty()) title = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            final String key = sbn.getKey();

            Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (msgs != null && msgs.length > 0) {
                // a messaging-style notification: each line is one message, oldest first
                long seen = lastTime.containsKey(key) ? lastTime.get(key) : 0;
                long newest = seen;
                for (Parcelable p : msgs) {
                    if (!(p instanceof Bundle)) continue;
                    Bundle b = (Bundle) p;
                    long time = b.getLong("time", 0);
                    if (time != 0 && time <= seen) continue;
                    String text = str(b.getCharSequence("text"));
                    String sender = str(b.getCharSequence("sender"));
                    if (sender.isEmpty() && Build.VERSION.SDK_INT >= 28) {
                        Object person = b.get("sender_person");
                        if (person instanceof android.app.Person && ((android.app.Person) person).getName() != null) {
                            sender = ((android.app.Person) person).getName().toString();
                        }
                    }
                    forward(title, sender.isEmpty() ? title : sender, text, time);
                    newest = Math.max(newest, time);
                }
                lastTime.put(key, newest);
                if (lastTime.size() > 300) lastTime.clear();
            } else {
                String text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
                if (text.isEmpty()) text = str(ex.getCharSequence(Notification.EXTRA_TEXT));
                forward(title, title, text, sbn.getPostTime());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString().trim();
    }

    private void forward(String chat, String from, String text, long time) {
        if (TextUtils.isEmpty(text)) return;
        // MAX updates its notification with the same lines again — each message goes out once
        String id = chat + "|" + from + "|" + text + "|" + (time / 1000);
        synchronized (recent) {
            if (!recent.add(id)) return;
            while (recent.size() > 300) {
                Iterator<String> it = recent.iterator();
                it.next();
                it.remove();
            }
        }
        final JSONObject body = new JSONObject();
        try {
            body.put("chat", chat);
            body.put("from", from);
            body.put("text", text);
        } catch (Throwable ignore) {
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!PrizmaCloud.configured()) return;
            PrizmaCloud.call(UserConfig.selectedAccount, "POST", "/v1/max/forward", body, r -> {
                if (r.code == 409 && System.currentTimeMillis() - warnedAt > 6 * 3600_000L) {
                    // the bot may only write to people who pressed Start — remind, but not on every message
                    warnedAt = System.currentTimeMillis();
                    Toast.makeText(ApplicationLoader.applicationContext,
                            "Уведомления из MAX: нажмите «Старт» у бота @" + PrizmaCloud.bot() + ", иначе он не может вам писать",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }
}
