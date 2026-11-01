package org.telegram.ui.Components;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaMaxListener;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Prizma: turning on «Уведомления из MAX» — two things only the person can do: let Libre see
 * notifications (Android settings) and press Start in the Libre bot (otherwise a bot can't write).
 */
public final class PrizmaMaxSetup {

    private PrizmaMaxSetup() {}

    public static void start(Context ctx) {
        if (ctx == null) return;
        if (!PrizmaCloud.configured()) {
            Toast.makeText(ctx, "Это работает через сервер Libre — он не подключён", Toast.LENGTH_LONG).show();
            return;
        }
        final boolean access = PrizmaMaxListener.accessGranted(ctx);
        PrizmaMaxListener.rebind(ctx);
        new AlertDialog.Builder(ctx)
                .setTitle("Уведомления из MAX")
                .setMessage((access ? "" : "1. Разрешите Libre видеть уведомления: в списке включите «Libre: уведомления из MAX». "
                        + "Libre читает только уведомления MAX и только пока включён этот переключатель.\n\n")
                        + (access ? "1" : "2") + ". Откройте бота @" + PrizmaCloud.bot() + " и нажмите «Старт» — иначе он не сможет вам писать.\n\n"
                        + "Сообщения из MAX приходят, пока телефон включён, а на нём стоят MAX и Libre. "
                        + "На Xiaomi разрешите Libre «Автозапуск», чтобы система её не выгружала. Тексты на сервере не хранятся.")
                .setPositiveButton(access ? "Открыть бота" : "Дать доступ", (d, w) -> {
                    if (access) {
                        openBot(ctx);
                    } else {
                        try {
                            ctx.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (Throwable e) {
                            Toast.makeText(ctx, "Откройте вручную: Настройки → Уведомления → Доступ к уведомлениям", Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNeutralButton(access ? null : "Открыть бота", access ? null : (d, w) -> openBot(ctx))
                .setNegativeButton("Позже", null)
                .show();
    }

    private static void openBot(Context ctx) {
        Browser.openUrl(ctx, "https://t.me/" + PrizmaCloud.bot() + "?start=max");
    }
}
