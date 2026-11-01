package org.telegram.ui.Components;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

/**
 * Prizma: where Libre Cloud lives (server address + the Libre bot) and «Удалить мои данные».
 * Release builds come with both filled in; this is for testing and for moving the server.
 */
public final class PrizmaCloudSetup {

    private PrizmaCloudSetup() {}

    public static String status() {
        if (!PrizmaCloud.configured()) return "не подключён — баннер видите только вы";
        return PrizmaCloud.host().replace("https://", "") + " · @" + PrizmaCloud.bot();
    }

    public static void show(Context ctx) {
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);

        TextView hint = new TextView(ctx);
        hint.setText("Через этот сервер пользователи Libre видят баннеры, дату появления в Libre и просмотры друг друга. Переписка туда не попадает.");
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final EditTextBoldCursor host = field(ctx, "Адрес сервера, например api.libre.app");
        host.setText(PrizmaCloud.host().replace("https://", ""));
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        v.addView(host, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 4, 0, 0));
        final EditTextBoldCursor bot = field(ctx, "Бот Libre, например @LibreCloudBot");
        bot.setText(PrizmaCloud.bot().isEmpty() ? "" : "@" + PrizmaCloud.bot());
        v.addView(bot, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 12, 0, 8));

        new AlertDialog.Builder(ctx)
                .setTitle("Сервер Libre")
                .setView(v)
                .setPositiveButton("Сохранить", (d, w) -> {
                    PrizmaCloud.setServer(host.getText().toString(), bot.getText().toString());
                    PrizmaCloud.syncOwnBanner(UserConfig.selectedAccount);
                    Toast.makeText(ctx, PrizmaCloud.configured() ? "Сервер Libre подключён" : "Сервер Libre отключён", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Удалить мои данные", (d, w) -> confirmDelete(ctx))
                .show();
    }

    static void confirmDelete(Context ctx) {
        new AlertDialog.Builder(ctx)
                .setTitle("Удалить мои данные?")
                .setMessage("Сервер Libre забудет ваш баннер, дату появления и просмотры, вашу стену и всё, что вы писали на чужих стенах. На телефоне ничего не изменится.")
                .setPositiveButton("Удалить", (d, w) -> PrizmaCloud.deleteMe(UserConfig.selectedAccount,
                        () -> {
                            org.telegram.messenger.PrizmaWall.clear();
                            Toast.makeText(ctx, "Данные на сервере Libre удалены", Toast.LENGTH_SHORT).show();
                        }))
                .setNegativeButton("Отмена", null)
                .makeRed(AlertDialog.BUTTON_POSITIVE)
                .show();
    }

    private static EditTextBoldCursor field(Context ctx, String hint) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        e.setHint(hint);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack));
        e.setCursorSize(AndroidUtilities.dp(20));
        e.setCursorWidth(1.5f);
        e.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated),
                Theme.getColor(Theme.key_text_RedBold));
        return e;
    }
}
