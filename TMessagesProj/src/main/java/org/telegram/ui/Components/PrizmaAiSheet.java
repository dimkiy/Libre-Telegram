package org.telegram.ui.Components;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaAi;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

public final class PrizmaAiSheet {
    private static final int LIMIT = 6000;

    private PrizmaAiSheet() {
    }

    public static void summarize(ChatActivity chat, ArrayList<MessageObject> messages) {
        if (chat == null || chat.getParentActivity() == null) return;
        StringBuilder dialog = new StringBuilder();
        for (int i = 0; i < messages.size() && dialog.length() < LIMIT; i++) {
            MessageObject mo = messages.get(i);
            if (mo == null || mo.messageOwner == null || mo.isSponsored()) continue;
            String body = mo.messageOwner.message;
            if (TextUtils.isEmpty(body)) continue;
            String line = name(chat, mo) + ": " + body.replace('\n', ' ') + "\n";
            dialog.insert(0, line);
        }
        if (dialog.length() == 0) {
            BulletinFactory.of(chat).createSimpleBulletin(org.telegram.messenger.R.raw.error, "В чате нет текста для пересказа").show();
            return;
        }
        String prompt = "Кратко перескажи по-русски, о чём эта переписка. 3–6 коротких пунктов, без вступления.\n\n" + dialog;
        open(chat, "Пересказ чата", prompt, null);
    }

    public static void rewrite(ChatActivity chat, CharSequence draft, Runnable empty, org.telegram.messenger.Utilities.Callback<String> apply) {
        if (chat == null || chat.getParentActivity() == null) return;
        if (TextUtils.isEmpty(draft)) {
            if (empty != null) empty.run();
            return;
        }
        String prompt = "Перепиши текст грамотно и чуть живее, сохрани смысл и язык. Верни только новый текст.\n\n" + draft;
        open(chat, "Улучшенный текст", prompt, apply);
    }

    private static String name(ChatActivity chat, MessageObject mo) {
        long from = mo.getFromChatId();
        if (from > 0) {
            TLRPC.User u = chat.getMessagesController().getUser(from);
            return u != null ? UserObject.getFirstName(u) : "Кто-то";
        }
        TLRPC.Chat c = chat.getMessagesController().getChat(-from);
        return c != null ? c.title : "Канал";
    }

    private static void open(ChatActivity chat, String title, String prompt, org.telegram.messenger.Utilities.Callback<String> apply) {
        Context context = chat.getParentActivity();
        BottomSheet sheet = new BottomSheet(context, false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(16));

        TextView head = new TextView(context);
        head.setText(title);
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        column.addView(head);

        TextView note = new TextView(context);
        note.setText("Gemini Nano работает прямо на телефоне, текст никуда не отправляется");
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 14));

        PrizmaAiPill pill = new PrizmaAiPill(context);
        column.addView(pill, LayoutHelper.createLinear(260, 72, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 10));

        ScrollView scroll = new ScrollView(context);
        TextView answer = new TextView(context);
        answer.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        answer.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        answer.setTextIsSelectable(true);
        answer.setLineSpacing(AndroidUtilities.dp(2), 1f);
        scroll.addView(answer);
        scroll.setVisibility(android.view.View.GONE);
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setVisibility(android.view.View.GONE);
        TextView copy = button(context, "Скопировать");
        buttons.addView(copy, LayoutHelper.createLinear(0, 46, 1f, 0, 0, apply != null ? 8 : 0, 0));
        TextView use = button(context, "Вставить в поле");
        if (apply != null) buttons.addView(use, LayoutHelper.createLinear(0, 46, 1f));
        column.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        sheet.setCustomView(column);
        sheet.show();

        PrizmaAi.ask(prompt, new PrizmaAi.Listener() {
            @Override
            public void onStage(String stage) {
                pill.setText(stage, true);
            }

            @Override
            public void onResult(String text) {
                pill.setText("Готово", false);
                answer.setText(text);
                scroll.setVisibility(android.view.View.VISIBLE);
                buttons.setVisibility(android.view.View.VISIBLE);
                copy.setOnClickListener(v -> {
                    AndroidUtilities.addToClipboard(text);
                    sheet.dismiss();
                });
                use.setOnClickListener(v -> {
                    if (apply != null) apply.run(text);
                    sheet.dismiss();
                });
            }

            @Override
            public void onError(String reason) {
                pill.setText("Не вышло", false);
                answer.setText(reason);
                scroll.setVisibility(android.view.View.VISIBLE);
            }
        });
    }

    private static TextView button(Context context, String text) {
        TextView b = new TextView(context);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        b.setBackground(PrizmaShape.fill(Theme.getColor(Theme.key_featuredStickers_addButton), AndroidUtilities.dp(18)));
        return b;
    }
}
