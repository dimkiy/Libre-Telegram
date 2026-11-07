package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaShape;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

public class PrizmaFeedActivity extends BaseFragment {
    private static final int REFRESH = 1;
    private static final int PER_CHANNEL = 6;
    private static final int MAX_CHANNELS = 60;
    private static final int PARALLEL = 4;

    private final LinkedHashMap<String, HashSet<Long>> groups = new LinkedHashMap<>();
    private final ArrayList<MessageObject> posts = new ArrayList<>();
    private final HashMap<Long, Integer> oldest = new HashMap<>();
    private final HashSet<Long> exhausted = new HashSet<>();
    private String group;
    private int generation;
    private int pending;
    private boolean loading;

    private LinearLayout chips;
    private RecyclerListView list;
    private Adapter adapter;
    private TextView empty;

    public PrizmaFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public View createView(Context context) {
        boolean tabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        if (!tabs) actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Лента");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == REFRESH) {
                    reload();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(REFRESH, R.drawable.msg_retry);

        readGroups();
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView chipScroll = new HorizontalScrollView(context);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        chipScroll.addView(chips);
        column.addView(chipScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, AndroidUtilities.dp(tabs ? 96 : 16));
        list.setAdapter(adapter = new Adapter(context));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && !loading && lm.findLastVisibleItemPosition() >= posts.size() - 4) {
                    loadMore();
                }
            }
        });
        list.setOnItemClickListener((view, position) -> open(posts.get(position)));
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        drawChips(context);
        reload();
        return root;
    }

    private void readGroups() {
        groups.clear();
        try {
            JSONArray arr = new JSONArray(TextUtils.isEmpty(PrizmaConfig.feedGroups()) ? "[]" : PrizmaConfig.feedGroups());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                HashSet<Long> ids = new HashSet<>();
                JSONArray c = o.optJSONArray("c");
                if (c != null) for (int j = 0; j < c.length(); j++) ids.add(c.getLong(j));
                groups.put(o.getString("n"), ids);
            }
        } catch (Throwable ignore) {
        }
    }

    private void saveGroups() {
        try {
            JSONArray arr = new JSONArray();
            for (String name : groups.keySet()) {
                JSONObject o = new JSONObject();
                o.put("n", name);
                JSONArray c = new JSONArray();
                for (Long id : groups.get(name)) c.put(id);
                o.put("c", c);
                arr.put(o);
            }
            PrizmaConfig.setFeedGroups(arr.toString());
        } catch (Throwable ignore) {
        }
    }

    private void drawChips(Context context) {
        chips.removeAllViews();
        chips.addView(chip(context, "Все", group == null, v -> select(null), null));
        for (String name : groups.keySet()) {
            chips.addView(chip(context, name, name.equals(group), v -> select(name), v -> {
                manage(context, name);
                return true;
            }));
        }
        chips.addView(chip(context, "+ Категория", false, v -> askName(context, null), null));
    }

    private TextView chip(Context context, String text, boolean active, View.OnClickListener click, View.OnLongClickListener longClick) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        t.setTextColor(active ? Theme.getColor(Theme.key_featuredStickers_buttonText) : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        t.setBackground(PrizmaShape.fill(active ? accent : Theme.getColor(Theme.key_windowBackgroundWhite), AndroidUtilities.dp(16)));
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, AndroidUtilities.dp(34));
        lp.rightMargin = AndroidUtilities.dp(8);
        t.setLayoutParams(lp);
        return t;
    }

    private void select(String name) {
        group = name;
        drawChips(getParentActivity());
        reload();
    }

    private void askName(Context context, String current) {
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHint("Например, Новости");
        input.setText(current == null ? "" : current);
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        FrameLayout box = new FrameLayout(context);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
        new AlertDialog.Builder(context)
                .setTitle(current == null ? "Новая категория" : "Название")
                .setView(box)
                .setPositiveButton("Дальше", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    HashSet<Long> ids = current != null && groups.containsKey(current) ? groups.remove(current) : new HashSet<>();
                    groups.put(name, ids);
                    saveGroups();
                    if (current != null && current.equals(group)) group = name;
                    drawChips(context);
                    pickChannels(context, name);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void manage(Context context, String name) {
        new AlertDialog.Builder(context)
                .setTitle(name)
                .setItems(new CharSequence[]{"Выбрать каналы", "Переименовать", "Удалить"}, (d, which) -> {
                    if (which == 0) {
                        pickChannels(context, name);
                    } else if (which == 1) {
                        askName(context, name);
                    } else {
                        groups.remove(name);
                        saveGroups();
                        if (name.equals(group)) group = null;
                        drawChips(context);
                        reload();
                    }
                })
                .show();
    }

    private void pickChannels(Context context, String name) {
        HashSet<Long> chosen = groups.get(name);
        if (chosen == null) return;
        BottomSheet sheet = new BottomSheet(context, false);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView head = new TextView(context);
        head.setText("Каналы в «" + name + "»");
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        head.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        column.addView(head);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        for (TLRPC.Chat chat : channels(Integer.MAX_VALUE)) {
            CheckBoxCell cell = new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_DEFAULT);
            cell.setBackground(Theme.getSelectorDrawable(false));
            cell.setText(chat.title, "", chosen.contains(chat.id), false);
            cell.setOnClickListener(v -> {
                boolean now = !cell.isChecked();
                cell.setChecked(now, true);
                if (now) chosen.add(chat.id);
                else chosen.remove(chat.id);
            });
            rows.addView(cell);
        }
        column.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 380));
        TextView done = new TextView(context);
        done.setText("Готово");
        done.setGravity(Gravity.CENTER);
        done.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        done.setTypeface(AndroidUtilities.bold());
        done.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        done.setBackground(PrizmaShape.fill(Theme.getColor(Theme.key_featuredStickers_addButton), AndroidUtilities.dp(18)));
        done.setOnClickListener(v -> sheet.dismiss());
        column.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 14));
        sheet.setCustomView(column);
        sheet.setOnDismissListener(d -> {
            saveGroups();
            if (name.equals(group)) reload();
        });
        sheet.show();
    }

    private ArrayList<TLRPC.Chat> channels(int limit) {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        HashSet<Long> filter = group == null || limit == Integer.MAX_VALUE ? null : groups.get(group);
        for (TLRPC.Dialog d : getMessagesController().getAllDialogs()) {
            if (d.id >= 0) continue;
            TLRPC.Chat chat = getMessagesController().getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) && !chat.kicked) continue;
            if (filter != null && !filter.contains(chat.id)) continue;
            out.add(chat);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private void reload() {
        generation++;
        posts.clear();
        oldest.clear();
        exhausted.clear();
        adapter.notifyDataSetChanged();
        loadMore();
    }

    private void loadMore() {
        if (loading) return;
        ArrayList<TLRPC.Chat> targets = channels(MAX_CHANNELS);
        ArrayList<TLRPC.Chat> todo = new ArrayList<>();
        for (TLRPC.Chat c : targets) if (!exhausted.contains(c.id)) todo.add(c);
        if (todo.isEmpty()) {
            showEmpty(targets.isEmpty() ? (group == null ? "Вы пока не подписаны на каналы" : "В категории нет каналов. Удерживайте её, чтобы выбрать") : null);
            return;
        }
        loading = true;
        empty.setText("Загружаю посты…");
        empty.setVisibility(posts.isEmpty() ? View.VISIBLE : View.GONE);
        final int gen = generation;
        final ArrayList<MessageObject> batch = new ArrayList<>();
        pending = todo.size();
        final int[] next = {0};
        Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            if (gen != generation || next[0] >= todo.size()) return;
            TLRPC.Chat chat = todo.get(next[0]++);
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(-chat.id);
            req.limit = PER_CHANNEL;
            Integer from = oldest.get(chat.id);
            req.offset_id = from == null ? 0 : from;
            getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) return;
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages m = (TLRPC.messages_Messages) res;
                    getMessagesController().putUsers(m.users, false);
                    getMessagesController().putChats(m.chats, false);
                    int min = Integer.MAX_VALUE;
                    for (TLRPC.Message msg : m.messages) {
                        if (msg instanceof TLRPC.TL_messageService) continue;
                        min = Math.min(min, msg.id);
                        batch.add(new MessageObject(currentAccount, msg, false, false));
                    }
                    if (m.messages.size() < PER_CHANNEL) exhausted.add(chat.id);
                    if (min != Integer.MAX_VALUE) oldest.put(chat.id, min);
                } else {
                    exhausted.add(chat.id);
                }
                if (--pending == 0) {
                    finish(batch);
                } else {
                    pump[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL; i++) pump[0].run();
    }

    private void finish(ArrayList<MessageObject> batch) {
        loading = false;
        HashSet<String> seen = new HashSet<>();
        for (MessageObject mo : posts) seen.add(mo.getDialogId() + ":" + mo.getId());
        for (MessageObject mo : batch) {
            if (mo.messageOwner.grouped_id != 0 && TextUtils.isEmpty(mo.messageOwner.message)) continue;
            if (seen.add(mo.getDialogId() + ":" + mo.getId())) posts.add(mo);
        }
        Collections.sort(posts, (a, b) -> Integer.compare(b.messageOwner.date, a.messageOwner.date));
        adapter.notifyDataSetChanged();
        showEmpty(posts.isEmpty() ? "Новых постов нет" : null);
    }

    private void showEmpty(String text) {
        empty.setText(text == null ? "" : text);
        empty.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private void open(MessageObject mo) {
        Bundle args = new Bundle();
        args.putLong("chat_id", -mo.getDialogId());
        args.putInt("message_id", mo.getId());
        presentFragment(new ChatActivity(args));
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return posts.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            PostView v = new PostView(context);
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((PostView) holder.itemView).bind(posts.get(position));
        }
    }

    private final class PostView extends LinearLayout {
        private final BackupImageView avatar;
        private final AvatarDrawable avatarDrawable = new AvatarDrawable();
        private final TextView name, time, text, views;
        private final BackupImageView media;

        PostView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(VERTICAL);
            card.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
            card.setBackground(PrizmaShape.fill(Theme.getColor(Theme.key_windowBackgroundWhite), AndroidUtilities.dp(24)));
            addView(card, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(18));
            top.addView(avatar, LayoutHelper.createLinear(36, 36));
            LinearLayout titles = new LinearLayout(context);
            titles.setOrientation(VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTypeface(AndroidUtilities.bold());
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titles.addView(name);
            time = new TextView(context);
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            titles.addView(time);
            top.addView(titles, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
            card.addView(top);

            media = new BackupImageView(context);
            media.setRoundRadius(AndroidUtilities.dp(16));
            card.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 10, 0, 0));

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.setMaxLines(9);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setLineSpacing(AndroidUtilities.dp(1.5f), 1f);
            card.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));

            views = new TextView(context);
            views.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            views.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            card.addView(views, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT, 0, 6, 0, 0));
        }

        void bind(MessageObject mo) {
            TLRPC.Chat chat = getMessagesController().getChat(-mo.getDialogId());
            avatarDrawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
            name.setText(chat != null ? chat.title : "");
            time.setText(LocaleController.formatDateAudio(mo.messageOwner.date, true));
            CharSequence body = !TextUtils.isEmpty(mo.caption) ? mo.caption : mo.messageOwner.media == null || mo.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty || mo.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage ? mo.messageText : mo.messageOwner.message;
            text.setText(body);
            text.setVisibility(TextUtils.isEmpty(body) ? GONE : VISIBLE);
            views.setText(mo.messageOwner.views > 0 ? LocaleController.formatShortNumber(mo.messageOwner.views, null) + " просмотров" : "");
            bindMedia(mo);
        }

        private void bindMedia(MessageObject mo) {
            TLRPC.MessageMedia m = mo.messageOwner.media;
            if (m instanceof TLRPC.TL_messageMediaPhoto && m.photo != null) {
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 800);
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 40);
                media.setVisibility(VISIBLE);
                media.setImage(ImageLocation.getForObject(big, m.photo), "400_200", ImageLocation.getForObject(small, m.photo), "400_200_b", 0, mo);
            } else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null && (mo.isVideo() || mo.isGif())) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(m.document.thumbs, 320);
                media.setVisibility(thumb != null ? VISIBLE : GONE);
                if (thumb != null) media.setImage(ImageLocation.getForDocument(thumb, m.document), "400_200", (android.graphics.drawable.Drawable) null, mo);
            } else {
                media.setVisibility(GONE);
            }
        }
    }
}
