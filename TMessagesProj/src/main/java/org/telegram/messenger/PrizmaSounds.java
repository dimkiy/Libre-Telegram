package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;

public class PrizmaSounds {
    public static final int REQUEST = 48212;

    public static void pick(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Звук уведомлений");
            String cur = MessagesController.getNotificationsSettings(UserConfig.selectedAccount).getString("GlobalSoundPath", null);
            if (cur != null && cur.length() > 0 && !"NoSound".equals(cur)) {
                try {
                    intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cur));
                } catch (Throwable ignore) {
                }
            }
            activity.startActivityForResult(intent, REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void handlePicked(Activity activity, Intent data) {
        try {
            int account = UserConfig.selectedAccount;
            SharedPreferences prefs = MessagesController.getNotificationsSettings(account);
            SharedPreferences.Editor e = prefs.edit();

            Uri uri = data == null ? null : (Uri) data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (uri == null) {
                e.putString("GlobalSound", "NoSound");
                e.putString("GlobalSoundPath", "NoSound");
            } else {
                String title = "Звук";
                try {
                    Ringtone r = RingtoneManager.getRingtone(activity, uri);
                    if (r != null) {
                        title = r.getTitle(activity);
                    }
                } catch (Throwable ignore) {
                }
                e.putString("GlobalSound", title);
                e.putString("GlobalSoundPath", uri.toString());
            }
            e.putLong("GlobalSoundDocId", 0);
            e.apply();

            NotificationsController.getInstance(account).deleteNotificationChannelGlobal(NotificationsController.TYPE_PRIVATE);
            NotificationsController.getInstance(account).updateServerNotificationsSettings(NotificationsController.TYPE_PRIVATE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static String currentName() {
        String n = MessagesController.getNotificationsSettings(UserConfig.selectedAccount).getString("GlobalSound", null);
        if (n == null || n.length() == 0) {
            return "по умолчанию";
        }
        if ("NoSound".equals(n)) {
            return "без звука";
        }
        return n;
    }
}
