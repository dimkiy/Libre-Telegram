package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaHistory;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaGlassBar;
import org.telegram.ui.Components.PrizmaGlassFrame;
import org.telegram.ui.Components.PrizmaShape;

import java.util.ArrayList;

public class PrizmaHistoryActivity extends BaseFragment {
    private static final int PAGE = 60;

    private final long dialogId;
    private LinearLayout column;
    private TextView empty;
    private int loadRequest;

    public PrizmaHistoryActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        PrizmaGlassFrame root = new PrizmaGlassFrame(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root.setBaseColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(12), PrizmaGlassBar.height() + AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        PrizmaGlassBar bar = new PrizmaGlassBar(context, root, "Удалённые и правки", this::finishFragment);
        bar.setSubtitle(dialogName());
        bar.addAction(R.drawable.msg_delete, "Очистить историю", () -> askClear(context));
        bar.follow(scroll);

        empty = new TextView(context);
        empty.setText("Здесь появятся удалённые сообщения и старые версии правок.\nОни хранятся только на этом телефоне и не пропадают после очистки кэша.");
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        load(context);
        return root;
    }

    private String dialogName() {
        MessagesController mc = getMessagesController();
        if (dialogId > 0) {
            TLRPC.User u = mc.getUser(dialogId);
            return u != null ? UserObject.getUserName(u) : null;
        }
        TLRPC.Chat c = mc.getChat(-dialogId);
        return c != null ? c.title : null;
    }

    @Override
    public boolean isLightStatusBar() {
        return PrizmaGlassBar.lightStatusBar(Theme.getColor(Theme.key_windowBackgroundGray));
    }

    private static final class Row {
        PrizmaHistory.Entry entry;
        CharSequence text;
    }

    private void load(Context context) {
        final int request = ++loadRequest;
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<PrizmaHistory.Entry> list = PrizmaHistory.list(currentAccount, dialogId);
            ArrayList<Row> rows = new ArrayList<>(list.size());
            for (PrizmaHistory.Entry e : list) {
                Row row = new Row();
                row.entry = e;
                row.text = text(e.message);
                rows.add(row);
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (request == loadRequest && fragmentView != null && !isFinishing()) show(context, rows, 0);
            });
        });
    }

    private void show(Context context, ArrayList<Row> rows, int from) {
        if (from == 0) column.removeAllViews();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        int to = Math.min(rows.size(), from + PAGE);
        for (int i = from; i < to; i++) {
            column.addView(card(context, rows.get(i)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
        if (to < rows.size()) {
            TextView more = new TextView(context);
            more.setText("Показать ещё " + Math.min(PAGE, rows.size() - to));
            more.setGravity(Gravity.CENTER);
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            more.setTypeface(AndroidUtilities.bold());
            more.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
            more.setBackground(PrizmaShape.fill(Theme.getColor(Theme.key_windowBackgroundWhite), AndroidUtilities.dp(22)));
            more.setOnClickListener(v -> {
                column.removeView(more);
                show(context, rows, to);
            });
            column.addView(more, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        }
    }

    private View card(Context context, Row row) {
        final PrizmaHistory.Entry e = row.entry;
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        card.setBackground(PrizmaShape.fill(Theme.getColor(Theme.key_windowBackgroundWhite), AndroidUtilities.dp(22)));

        TextView head = new TextView(context);
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        head.setTextColor(e.kind == PrizmaHistory.DELETED ? 0xffd65a52 : Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        String who = sender(e.message);
        String when = e.kind == PrizmaHistory.DELETED ? "удалено " + LocaleController.formatDateAudio(e.savedAt / 1000, true) : "до правки, отправлено " + LocaleController.formatDateAudio(e.message.date, true);
        head.setText(TextUtils.isEmpty(who) ? when : who + " · " + when);
        card.addView(head);

        TextView body = new TextView(context);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setTextIsSelectable(true);
        CharSequence text = row.text;
        body.setText(text);
        card.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        card.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(text);
            BulletinFactory.of(this).createCopyBulletin("Текст скопирован").show();
        });
        return card;
    }

    private CharSequence text(TLRPC.Message m) {
        try {
            MessageObject mo = new MessageObject(currentAccount, m, false, false);
            CharSequence caption = mo.caption;
            if (!TextUtils.isEmpty(m.message) && m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty)) {
                return mo.messageText + "\n" + m.message;
            }
            if (!TextUtils.isEmpty(caption)) return caption;
            return mo.messageText != null ? mo.messageText : "";
        } catch (Throwable e) {
            return m.message != null ? m.message : "";
        }
    }

    private String sender(TLRPC.Message m) {
        long from = MessageObject.getFromChatId(m);
        if (from > 0) {
            TLRPC.User u = getMessagesController().getUser(from);
            return u != null ? UserObject.getUserName(u) : null;
        }
        TLRPC.Chat c = getMessagesController().getChat(-from);
        return c != null ? c.title : null;
    }

    private void askClear(Context context) {
        new AlertDialog.Builder(context)
                .setTitle("Очистить историю?")
                .setMessage("Сохранённые удалённые сообщения и правки этого чата исчезнут с телефона.")
                .setPositiveButton("Очистить", (d, w) -> {
                    PrizmaHistory.clear(currentAccount, dialogId);
                    load(context);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }
}
