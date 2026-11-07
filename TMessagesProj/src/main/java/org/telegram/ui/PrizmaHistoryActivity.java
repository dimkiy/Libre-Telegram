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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaShape;

import java.util.ArrayList;

public class PrizmaHistoryActivity extends BaseFragment {
    private static final int CLEAR = 1;

    private final long dialogId;
    private LinearLayout column;
    private TextView empty;

    public PrizmaHistoryActivity(long dialogId) {
        this.dialogId = dialogId;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Удалённые и правки");
        actionBar.setSubtitle(dialogName());
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == CLEAR) {
                    askClear(context);
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(CLEAR, R.drawable.msg_delete);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scroll.addView(column);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

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

    private void load(Context context) {
        Utilities.globalQueue.postRunnable(() -> {
            ArrayList<PrizmaHistory.Entry> list = PrizmaHistory.list(currentAccount, dialogId);
            AndroidUtilities.runOnUIThread(() -> show(context, list));
        });
    }

    private void show(Context context, ArrayList<PrizmaHistory.Entry> list) {
        column.removeAllViews();
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (PrizmaHistory.Entry e : list) {
            column.addView(card(context, e), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        }
    }

    private View card(Context context, PrizmaHistory.Entry e) {
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
        CharSequence text = text(e.message);
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
