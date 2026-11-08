package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaShape;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ShareAlert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

public class PrizmaFeedActivity extends BaseFragment {
    private static final int PER_CHANNEL = 5;
    private static final int MAX_CHANNELS = 50;
    private static final int MAX_RECOMMENDED = 18;
    private static final int PARALLEL = 4;
    private static final String FOR_YOU = "\u0000for_you";
    private static final String FOLLOWING = "\u0000following";

    private final LinkedHashMap<String, HashSet<Long>> groups = new LinkedHashMap<>();
    private final ArrayList<MessageObject> posts = new ArrayList<>();
    private final HashMap<Long, Integer> oldest = new HashMap<>();
    private final HashSet<Long> exhausted = new HashSet<>();
    private final HashSet<String> expanded = new HashSet<>();
    private final ArrayList<TLRPC.Chat> recommended = new ArrayList<>();
    private final HashSet<Long> recommendedIds = new HashSet<>();
    private String mode = FOR_YOU;
    private int generation;
    private int pending;
    private boolean loading;
    private boolean recommendationsLoaded;
    private float headerShift;

    private LinearLayout header;
    private LinearLayout tabRow;
    private RecyclerListView list;
    private Adapter adapter;
    private TextView empty;
    private boolean tabs;

    public PrizmaFeedActivity(Bundle args) {
        super(args);
    }

    private SharedPreferences store() {
        return ApplicationLoader.applicationContext.getSharedPreferences("libre_feed", Context.MODE_PRIVATE);
    }

    private HashSet<Long> hidden() {
        HashSet<Long> out = new HashSet<>();
        for (String s : store().getString("hidden", "").split(",")) {
            try {
                if (!s.isEmpty()) out.add(Long.parseLong(s));
            } catch (NumberFormatException ignore) {
            }
        }
        return out;
    }

    private void hide(long chatId) {
        HashSet<Long> h = hidden();
        h.add(chatId);
        store().edit().putString("hidden", TextUtils.join(",", h)).apply();
    }

    @Override
    public View createView(Context context) {
        tabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        actionBar.setAddToContainer(false);
        readGroups();

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setAdapter(adapter = new Adapter(context));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                headerShift = Math.max(-header.getHeight() + AndroidUtilities.statusBarHeight, Math.min(0, headerShift - dy));
                if (!rv.canScrollVertically(-1)) headerShift = 0;
                header.setTranslationY(headerShift);
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && !loading && lm.findLastVisibleItemPosition() >= posts.size() - 4) {
                    loadMore();
                }
            }
        });
        list.setOnItemClickListener((view, position) -> open(posts.get(position)));
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        header = buildHeader(context);
        root.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int top = b - t;
            if (list.getPaddingTop() != top) {
                list.setPadding(0, top, 0, AndroidUtilities.dp(tabs ? 96 : 16));
                list.scrollToPosition(0);
            }
        });

        empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        fragmentView = root;
        drawTabs(context);
        reload();
        return root;
    }

    private LinearLayout buildHeader(Context context) {
        LinearLayout h = new LinearLayout(context);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        h.setBackgroundColor((Theme.getColor(Theme.key_windowBackgroundWhite) & 0x00ffffff) | 0xf2000000);
        h.setClickable(true);

        FrameLayout top = new FrameLayout(context);
        if (!tabs) {
            ImageView back = icon(context, R.drawable.ic_ab_back);
            back.setOnClickListener(v -> finishFragment());
            top.addView(back, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        }
        TextView title = new TextView(context);
        title.setText("Лента");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setOnClickListener(v -> list.smoothScrollToPosition(0));
        top.addView(title, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        ImageView refresh = icon(context, R.drawable.msg_retry);
        refresh.setOnClickListener(v -> reload());
        top.addView(refresh, LayoutHelper.createFrame(48, 48, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        h.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        tabRow = new LinearLayout(context);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);
        tabRow.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        scroll.addView(tabRow);
        h.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        View line = new View(context);
        line.setBackgroundColor(Theme.getColor(Theme.key_divider));
        h.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        return h;
    }

    private ImageView icon(Context context, int res) {
        ImageView v = new ImageView(context);
        v.setImageResource(res);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        v.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
        return v;
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

    private void drawTabs(Context context) {
        tabRow.removeAllViews();
        tabRow.addView(tab(context, "Для вас", FOR_YOU, null));
        tabRow.addView(tab(context, "Подписки", FOLLOWING, null));
        for (String name : groups.keySet()) {
            tabRow.addView(tab(context, name, name, v -> {
                manage(context, name);
                return true;
            }));
        }
        TextView add = new TextView(context);
        add.setText("+ Категория");
        add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        add.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        add.setGravity(Gravity.CENTER);
        add.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), 0);
        add.setOnClickListener(v -> askName(context, null));
        tabRow.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View tab(Context context, String text, String key, View.OnLongClickListener longClick) {
        boolean active = key.equals(mode);
        TextView t = new TextView(context) {
            private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (active) {
                    bar.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                    float w = getPaint().measureText(getText().toString());
                    float cx = getWidth() / 2f, h = getHeight();
                    canvas.drawRoundRect(cx - w / 2f, h - AndroidUtilities.dp(4), cx + w / 2f, h, AndroidUtilities.dp(2), AndroidUtilities.dp(2), bar);
                }
            }
        };
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTypeface(active ? AndroidUtilities.bold() : null);
        t.setTextColor(Theme.getColor(active ? Theme.key_windowBackgroundWhiteBlackText : Theme.key_windowBackgroundWhiteGrayText));
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        t.setOnClickListener(v -> {
            if (key.equals(mode)) {
                list.smoothScrollToPosition(0);
                return;
            }
            mode = key;
            drawTabs(context);
            reload();
        });
        if (longClick != null) t.setOnLongClickListener(longClick);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return t;
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
                    if (current != null && current.equals(mode)) mode = name;
                    drawTabs(context);
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
                        if (name.equals(mode)) mode = FOR_YOU;
                        drawTabs(context);
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
        for (TLRPC.Chat chat : subscribed(null, Integer.MAX_VALUE)) {
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
            if (name.equals(mode)) reload();
        });
        sheet.show();
    }

    private ArrayList<TLRPC.Chat> subscribed(HashSet<Long> filter, int limit) {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        HashSet<Long> hide = hidden();
        for (TLRPC.Dialog d : getMessagesController().getAllDialogs()) {
            if (d.id >= 0) continue;
            TLRPC.Chat chat = getMessagesController().getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) && !chat.kicked) continue;
            if (filter != null && !filter.contains(chat.id)) continue;
            if (filter == null && hide.contains(chat.id) && limit != Integer.MAX_VALUE) continue;
            out.add(chat);
            if (out.size() >= limit) break;
        }
        return out;
    }

    private ArrayList<TLRPC.Chat> targets() {
        if (FOR_YOU.equals(mode)) {
            ArrayList<TLRPC.Chat> out = subscribed(null, MAX_CHANNELS);
            HashSet<Long> hide = hidden();
            for (TLRPC.Chat c : recommended) if (!hide.contains(c.id)) out.add(c);
            return out;
        }
        if (FOLLOWING.equals(mode)) return subscribed(null, MAX_CHANNELS);
        return subscribed(groups.get(mode), MAX_CHANNELS);
    }

    private void reload() {
        generation++;
        posts.clear();
        oldest.clear();
        exhausted.clear();
        adapter.notifyDataSetChanged();
        headerShift = 0;
        if (header != null) header.setTranslationY(0);
        if (FOR_YOU.equals(mode) && !recommendationsLoaded) {
            loadRecommendations(this::loadMore);
        } else {
            loadMore();
        }
    }

    private void loadRecommendations(Runnable then) {
        loading = true;
        showEmpty("Подбираю каналы…");
        ArrayList<TLRPC.Chat> seeds = subscribed(null, 40);
        Collections.shuffle(seeds);
        ArrayList<TLRPC.TL_channels_getChannelRecommendations> requests = new ArrayList<>();
        requests.add(new TLRPC.TL_channels_getChannelRecommendations());
        for (int i = 0; i < Math.min(3, seeds.size()); i++) {
            TLRPC.TL_channels_getChannelRecommendations r = new TLRPC.TL_channels_getChannelRecommendations();
            r.flags |= 1;
            r.channel = getMessagesController().getInputChannel(seeds.get(i).id);
            requests.add(r);
        }
        final int[] left = {requests.size()};
        final int gen = generation;
        for (TLRPC.TL_channels_getChannelRecommendations r : requests) {
            getConnectionsManager().sendRequest(r, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TLRPC.messages_Chats) {
                    ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) res).chats;
                    getMessagesController().putChats(chats, false);
                    for (TLRPC.Chat c : chats) {
                        if (recommended.size() >= MAX_RECOMMENDED) break;
                        if (c == null || !ChatObject.isChannelAndNotMegaGroup(c) || !ChatObject.isNotInChat(c)) continue;
                        if (recommendedIds.add(c.id)) recommended.add(c);
                    }
                }
                if (--left[0] == 0 && !recommendationsLoaded) {
                    recommendationsLoaded = true;
                    loading = false;
                    if (gen == generation) then.run();
                }
            }));
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (gen == generation && !recommendationsLoaded) {
                recommendationsLoaded = true;
                loading = false;
                then.run();
            }
        }, 6000);
    }

    private void loadMore() {
        if (loading) return;
        ArrayList<TLRPC.Chat> all = targets();
        ArrayList<TLRPC.Chat> todo = new ArrayList<>();
        for (TLRPC.Chat c : all) if (!exhausted.contains(c.id)) todo.add(c);
        if (todo.isEmpty()) {
            if (posts.isEmpty()) {
                showEmpty(all.isEmpty() ? (groups.containsKey(mode) ? "В категории нет каналов. Удерживайте её, чтобы выбрать" : "Вы пока не подписаны на каналы") : "Новых постов нет");
            }
            return;
        }
        loading = true;
        if (posts.isEmpty()) showEmpty("Загружаю посты…");
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
            req.limit = recommendedIds.contains(chat.id) ? 3 : PER_CHANNEL;
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
                        min = Math.min(min, msg.id);
                        if (msg instanceof TLRPC.TL_messageService) continue;
                        batch.add(new MessageObject(currentAccount, msg, false, false));
                    }
                    if (m.messages.size() < req.limit || recommendedIds.contains(chat.id)) exhausted.add(chat.id);
                    if (min != Integer.MAX_VALUE) oldest.put(chat.id, min);
                } else {
                    exhausted.add(chat.id);
                }
                if (!batch.isEmpty()) {
                    append(new ArrayList<>(batch));
                    batch.clear();
                }
                if (--pending == 0) {
                    done(gen);
                } else {
                    pump[0].run();
                }
            }));
        };
        for (int i = 0; i < PARALLEL; i++) pump[0].run();
        AndroidUtilities.runOnUIThread(() -> done(gen), 12000);
    }

    private void done(int gen) {
        if (gen != generation || !loading) return;
        loading = false;
        showEmpty(posts.isEmpty() ? "Новых постов нет. Нажмите обновить" : null);
    }

    private void append(ArrayList<MessageObject> batch) {
        HashSet<String> seen = new HashSet<>();
        HashSet<Long> albums = new HashSet<>();
        for (MessageObject mo : posts) {
            seen.add(mo.getDialogId() + ":" + mo.getId());
            if (mo.messageOwner.grouped_id != 0) albums.add(mo.messageOwner.grouped_id);
        }
        Collections.sort(batch, (a, b) -> Integer.compare(b.messageOwner.date, a.messageOwner.date));
        int start = posts.size();
        for (MessageObject mo : batch) {
            long album = mo.messageOwner.grouped_id;
            if (album != 0 && !albums.add(album)) continue;
            if (seen.add(mo.getDialogId() + ":" + mo.getId())) posts.add(mo);
        }
        ArrayList<MessageObject> fresh = new ArrayList<>(posts.subList(start, posts.size()));
        Collections.sort(fresh, (a, b) -> Integer.compare(b.messageOwner.date, a.messageOwner.date));
        for (int i = 0; i < fresh.size(); i++) posts.set(start + i, fresh.get(i));
        adapter.notifyDataSetChanged();
        if (!posts.isEmpty()) showEmpty(null);
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

    private void openChannel(long chatId) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        presentFragment(new ChatActivity(args));
    }

    private void more(MessageObject mo) {
        long chatId = -mo.getDialogId();
        TLRPC.Chat chat = getMessagesController().getChat(chatId);
        boolean joined = chat != null && !ChatObject.isNotInChat(chat);
        String link = chat != null && ChatObject.getPublicUsername(chat) != null ? "https://t.me/" + ChatObject.getPublicUsername(chat) + "/" + mo.getId() : null;
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Не показывать этот канал");
        actions.add(() -> {
            hide(chatId);
            for (int i = posts.size() - 1; i >= 0; i--) if (-posts.get(i).getDialogId() == chatId) posts.remove(i);
            adapter.notifyDataSetChanged();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_ban, "Посты «" + (chat != null ? chat.title : "канала") + "» больше не появятся").show();
        });
        items.add("Открыть канал");
        actions.add(() -> openChannel(chatId));
        if (link != null) {
            items.add("Скопировать ссылку");
            actions.add(() -> {
                AndroidUtilities.addToClipboard(link);
                BulletinFactory.of(this).createCopyLinkBulletin().show();
            });
        }
        if (chat != null) {
            items.add(joined ? "Отписаться" : "Подписаться");
            actions.add(() -> {
                if (joined) {
                    getMessagesController().deleteParticipantFromChat(chatId, getUserConfig().getCurrentUser());
                } else {
                    getMessagesController().addUserToChat(chatId, getUserConfig().getCurrentUser(), 0, null, this, null);
                }
                adapter.notifyDataSetChanged();
            });
        }
        new AlertDialog.Builder(getParentActivity())
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private static String ago(int date) {
        long diff = System.currentTimeMillis() / 1000 - date;
        if (diff < 60) return "сейчас";
        if (diff < 3600) return (diff / 60) + " мин";
        if (diff < 86400) return (diff / 3600) + " ч";
        if (diff < 7 * 86400) return (diff / 86400) + " д";
        return LocaleController.formatDateAudio(date, false);
    }

    private static String count(int n) {
        return n <= 0 ? "" : LocaleController.formatShortNumber(n, null);
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
        private final TextView reason, follow, name, meta, text, more;
        private final ImageView menu;
        private final BackupImageView media;
        private final TextView replies, forwards, reactions, views;
        private final ImageView share;
        private final LinearLayout reasonRow;
        private MessageObject bound;

        PostView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

            reasonRow = new LinearLayout(context);
            reasonRow.setOrientation(HORIZONTAL);
            reasonRow.setGravity(Gravity.CENTER_VERTICAL);
            reasonRow.setPadding(AndroidUtilities.dp(62), AndroidUtilities.dp(8), AndroidUtilities.dp(14), 0);
            reason = new TextView(context);
            reason.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            reason.setTextColor(gray);
            reason.setText("Рекомендация по вашим интересам");
            reasonRow.addView(reason, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            follow = new TextView(context);
            follow.setText("Подписаться");
            follow.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            follow.setTypeface(AndroidUtilities.bold());
            follow.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            follow.setGravity(Gravity.CENTER);
            follow.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            follow.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(14), Theme.getColor(Theme.key_featuredStickers_addButton)));
            reasonRow.addView(follow, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28));
            addView(reasonRow);

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);
            row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(20));
            row.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.TOP));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            LinearLayout head = new LinearLayout(context);
            head.setOrientation(HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            name.setTypeface(AndroidUtilities.bold());
            name.setTextColor(ink);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0f, 0, 0, 0, 0));
            meta = new TextView(context);
            meta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            meta.setTextColor(gray);
            meta.setSingleLine(true);
            meta.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(meta, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 0, 0));
            menu = new ImageView(context);
            menu.setImageResource(R.drawable.ic_ab_other);
            menu.setScaleType(ImageView.ScaleType.CENTER);
            menu.setColorFilter(gray);
            menu.setScaleX(0.8f);
            menu.setScaleY(0.8f);
            head.addView(menu, LayoutHelper.createLinear(28, 24));
            column.addView(head);

            text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            text.setTextColor(ink);
            text.setLineSpacing(AndroidUtilities.dp(2), 1f);
            column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            more = new TextView(context);
            more.setText("Ещё");
            more.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            more.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            column.addView(more);

            media = new BackupImageView(context) {
                private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    rim.setStyle(Paint.Style.STROKE);
                    rim.setStrokeWidth(1);
                    rim.setColor(Theme.getColor(Theme.key_divider));
                    canvas.drawRoundRect(0.5f, 0.5f, getWidth() - 0.5f, getHeight() - 0.5f, AndroidUtilities.dp(16), AndroidUtilities.dp(16), rim);
                }
            };
            media.setRoundRadius(AndroidUtilities.dp(16));
            column.addView(media, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 200, 0, 10, 0, 0));

            LinearLayout actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            replies = action(context, R.drawable.msg_discussion, gray);
            forwards = action(context, R.drawable.msg_forward, gray);
            reactions = action(context, R.drawable.msg_reactions, gray);
            views = action(context, R.drawable.msg_views, gray);
            for (TextView t : new TextView[]{replies, forwards, reactions, views}) {
                actions.addView(t, LayoutHelper.createLinear(0, 36, 1f));
            }
            share = new ImageView(context);
            share.setImageResource(R.drawable.msg_share);
            share.setColorFilter(gray);
            share.setScaleType(ImageView.ScaleType.CENTER);
            actions.addView(share, LayoutHelper.createLinear(36, 36));
            column.addView(actions, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 4, 0, 2));

            row.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 10, 0, 0, 0));
            addView(row);

            View line = new View(context);
            line.setBackgroundColor(Theme.getColor(Theme.key_divider));
            addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        }

        private TextView action(Context context, int icon, int color) {
            TextView t = new TextView(context);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            t.setTextColor(color);
            t.setGravity(Gravity.CENTER_VERTICAL);
            android.graphics.drawable.Drawable d = context.getResources().getDrawable(icon).mutate();
            d.setColorFilter(new android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN));
            int s = AndroidUtilities.dp(18);
            d.setBounds(0, 0, s, s);
            t.setCompoundDrawables(d, null, null, null);
            t.setCompoundDrawablePadding(AndroidUtilities.dp(5));
            return t;
        }

        void bind(MessageObject mo) {
            bound = mo;
            long chatId = -mo.getDialogId();
            TLRPC.Chat chat = getMessagesController().getChat(chatId);
            boolean suggested = recommendedIds.contains(chatId) && (chat == null || ChatObject.isNotInChat(chat));
            reasonRow.setVisibility(suggested ? VISIBLE : GONE);
            follow.setOnClickListener(v -> {
                getMessagesController().addUserToChat(chatId, getUserConfig().getCurrentUser(), 0, null, PrizmaFeedActivity.this, null);
                follow.setText("Вы подписаны");
                follow.setEnabled(false);
            });
            follow.setEnabled(true);
            follow.setText("Подписаться");

            avatarDrawable.setInfo(currentAccount, chat);
            avatar.setForUserOrChat(chat, avatarDrawable);
            avatar.setOnClickListener(v -> openChannel(chatId));
            name.setText(chat != null ? chat.title : "");
            String user = chat != null ? ChatObject.getPublicUsername(chat) : null;
            meta.setText((user != null ? "@" + user + " · " : "· ") + ago(mo.messageOwner.date));
            menu.setOnClickListener(v -> more(mo));

            CharSequence body = !TextUtils.isEmpty(mo.caption) ? mo.caption
                    : mo.messageOwner.media == null || mo.messageOwner.media instanceof TLRPC.TL_messageMediaEmpty || mo.messageOwner.media instanceof TLRPC.TL_messageMediaWebPage ? mo.messageText : mo.messageOwner.message;
            text.setText(body);
            text.setVisibility(TextUtils.isEmpty(body) ? GONE : VISIBLE);
            String key = mo.getDialogId() + ":" + mo.getId();
            boolean open = expanded.contains(key);
            text.setMaxLines(open ? Integer.MAX_VALUE : 8);
            text.setEllipsize(open ? null : TextUtils.TruncateAt.END);
            boolean longText = body != null && (body.length() > 360 || body.toString().split("\n").length > 8);
            more.setVisibility(longText && !open ? VISIBLE : GONE);
            more.setOnClickListener(v -> {
                expanded.add(key);
                adapter.notifyItemChanged(posts.indexOf(bound));
            });

            bindMedia(mo);

            TLRPC.Message m = mo.messageOwner;
            replies.setText(count(m.replies != null ? m.replies.replies : 0));
            forwards.setText(count(m.forwards));
            int reactionsTotal = 0;
            if (m.reactions != null) for (TLRPC.ReactionCount rc : m.reactions.results) reactionsTotal += rc.count;
            reactions.setText(count(reactionsTotal));
            views.setText(count(m.views));
            share.setOnClickListener(v -> showDialog(ShareAlert.createShareAlert(getParentActivity(), mo, null, false, null, false)));
        }

        private void bindMedia(MessageObject mo) {
            TLRPC.MessageMedia m = mo.messageOwner.media;
            int avail = AndroidUtilities.displaySize.x - AndroidUtilities.dp(12 + 40 + 10 + 12);
            if (m instanceof TLRPC.TL_messageMediaPhoto && m.photo != null) {
                TLRPC.PhotoSize big = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 1000);
                TLRPC.PhotoSize small = FileLoader.getClosestPhotoSizeWithSize(m.photo.sizes, 40);
                size(big != null ? big.w : 0, big != null ? big.h : 0, avail);
                media.setImage(ImageLocation.getForObject(big, m.photo), null, ImageLocation.getForObject(small, m.photo), "b", 0, mo);
            } else if (m instanceof TLRPC.TL_messageMediaDocument && m.document != null && (mo.isVideo() || mo.isGif())) {
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(m.document.thumbs, 640);
                if (thumb == null) {
                    media.setVisibility(GONE);
                    return;
                }
                size(thumb.w, thumb.h, avail);
                media.setImage(ImageLocation.getForDocument(thumb, m.document), null, (android.graphics.drawable.Drawable) null, mo);
            } else {
                media.setVisibility(GONE);
            }
        }

        private void size(int w, int h, int avail) {
            media.setVisibility(VISIBLE);
            int height = w > 0 && h > 0 ? avail * h / w : avail * 9 / 16;
            height = Math.max(AndroidUtilities.dp(140), Math.min(AndroidUtilities.dp(460), height));
            ViewGroup.LayoutParams lp = media.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                media.setLayoutParams(lp);
            }
        }
    }
}
