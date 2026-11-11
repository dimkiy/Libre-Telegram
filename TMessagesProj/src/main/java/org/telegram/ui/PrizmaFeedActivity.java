package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
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
import org.telegram.messenger.ChatObject;
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
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaFeedIcons;
import org.telegram.ui.Components.PrizmaFeedKit;
import org.telegram.ui.Components.PrizmaGlassFrame;
import org.telegram.ui.Components.PrizmaPostCell;
import org.telegram.ui.Components.PrizmaShape;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;

public class PrizmaFeedActivity extends BaseFragment implements PrizmaPostCell.Host {
    private static final int MAX_CHANNELS = 60;
    private static final int MAX_RECOMMENDED = 16;
    private static final int PARALLEL = 10;
    private static final int PAGE = 20;
    private static final long FIRST_PAGE_TTL = 4 * 60_000L;

    private static final class FirstPage {
        ArrayList<TLRPC.Message> messages;
        boolean exhausted;
        long at;
    }

    private static final HashMap<String, FirstPage> firstPages = new HashMap<>();
    private static final String FOR_YOU = "\u0000for_you";
    private static final String FOLLOWING = "\u0000following";

    private final LinkedHashMap<String, HashSet<Long>> groups = new LinkedHashMap<>();
    private final ArrayList<ArrayList<MessageObject>> posts = new ArrayList<>();
    private final ArrayList<ArrayList<MessageObject>> pool = new ArrayList<>();
    private final HashMap<Long, ArrayList<MessageObject>> albums = new HashMap<>();
    private final HashSet<String> seen = new HashSet<>();
    private final HashMap<Long, Integer> oldest = new HashMap<>();
    private final HashMap<Long, Integer> oldestDate = new HashMap<>();
    private final HashSet<Long> exhausted = new HashSet<>();
    private final HashSet<Long> requested = new HashSet<>();
    private final HashSet<String> expanded = new HashSet<>();
    private final HashSet<String> dismissed = new HashSet<>();
    private final HashSet<String> appeared = new HashSet<>();
    private final HashMap<String, TLRPC.TL_textWithEntities> translations = new HashMap<>();
    private final ArrayList<TLRPC.Chat> recommended = new ArrayList<>();
    private final HashSet<Long> recommendedIds = new HashSet<>();
    private final HashMap<Long, Float> affinity = new HashMap<>();
    private ArrayList<ArrayList<MessageObject>> pending;
    private String mode = FOR_YOU;
    private int generation;
    private int inflight;
    private boolean loading;
    private boolean published;
    private boolean fromCache;
    private boolean userScrolled;
    private boolean recommendationsLoaded;
    private int windowHours = 72;
    private int newestDate;
    private long staggerBase;

    private PrizmaGlassFrame root;
    private FrameLayout header;
    private FrameLayout topRow;
    private Tabs tabs;
    private FrameLayout tabsCapsule;
    private ImageView glassTune;
    private final HashMap<Long, Float> medianCache = new HashMap<>();
    private RecyclerListView list;
    private LinearLayoutManager layout;
    private Adapter adapter;
    private TextView empty;
    private RadialProgressView spinner;
    private Pill pill;
    private ImageView up;
    private boolean upShown;
    private boolean mainTabs;
    private float shift;
    private ValueAnimator snap;

    private final Runnable freshCheck = new Runnable() {
        @Override
        public void run() {
            checkFresh();
            AndroidUtilities.runOnUIThread(this, 30000);
        }
    };

    public PrizmaFeedActivity(Bundle args) {
        super(args);
    }

    @Override
    public BaseFragment fragment() {
        return this;
    }

    @Override
    public void openPost(ArrayList<MessageObject> group) {
        presentFragment(new PrizmaPostActivity(group, translations));
    }

    @Override
    public void reply(ArrayList<MessageObject> group) {
        openPost(group);
    }

    @Override
    public boolean isExpanded(String key) {
        return expanded.contains(key);
    }

    @Override
    public void expand(String key) {
        expanded.add(key);
    }

    @Override
    public boolean isSuggested(long chatId) {
        return recommendedIds.contains(chatId);
    }

    @Override
    public boolean isTrending(MessageObject mo) {
        Float med = medianCache.get(mo.getDialogId());
        return med != null && med >= 50 && mo.messageOwner.views >= med * 2.2f
                && getConnectionsManager().getCurrentTime() - mo.messageOwner.date < 2 * 86400;
    }

    @Override
    public TLRPC.TL_textWithEntities translation(String key) {
        return translations.get(key);
    }

    @Override
    public void putTranslation(String key, TLRPC.TL_textWithEntities text) {
        translations.put(key, text);
    }

    @Override
    public void changed(PrizmaPostCell cell) {
        int pos = posts.indexOf(cell.getGroup());
        if (pos >= 0) adapter.notifyItemChanged(pos);
    }

    @Override
    public View createView(Context context) {
        mainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        actionBar.setAddToContainer(false);
        readGroups();

        root = new PrizmaGlassFrame(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        list = new RecyclerListView(context);
        list.setLayoutManager(layout = new LinearLayoutManager(context));
        list.setClipToPadding(false);
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setAdapter(adapter = new Adapter(context));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (snap != null) snap.cancel();
                setShift(rv.canScrollVertically(-1) ? shift - dy : 0);
                int first = layout.findFirstVisibleItemPosition();
                showUp(first > 4 && dy < 8);
                if (!loading && layout.findLastVisibleItemPosition() >= posts.size() - 5) loadMore();
                root.invalidate();
            }

            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING) userScrolled = true;
                if (state == RecyclerView.SCROLL_STATE_IDLE) settle();
            }
        });
        root.addView(list, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        root.setSource(list);

        header = buildHeader(context);
        root.addView(header, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addEdgePane(header);
        root.addPane(tabsCapsule, AndroidUtilities.dp(22), true);
        root.addPane(glassTune, AndroidUtilities.dp(20), true);
        header.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int top = b - t;
            if (list.getPaddingTop() != top) {
                list.setPadding(0, top, 0, AndroidUtilities.dp(mainTabs ? 96 : 16));
                list.scrollToPosition(0);
                FrameLayout.LayoutParams pl = (FrameLayout.LayoutParams) pill.getLayoutParams();
                pl.topMargin = top + AndroidUtilities.dp(12);
                pill.setLayoutParams(pl);
            }
        });

        spinner = new RadialProgressView(context);
        spinner.setSize(AndroidUtilities.dp(28));
        spinner.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        root.addView(spinner, LayoutHelper.createFrame(44, 44, Gravity.CENTER));

        empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        root.addView(empty, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        pill = new Pill(context);
        root.addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 42, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 120, 0, 0));
        root.addPane(pill, AndroidUtilities.dp(21), true);

        up = new ImageView(context);
        up.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.UP, 22).setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText)));
        up.setScaleType(ImageView.ScaleType.CENTER);
        up.setVisibility(View.GONE);
        up.setOnClickListener(v -> toTop());
        PrizmaFeedKit.press(up, 0.86f);
        root.addView(up, LayoutHelper.createFrame(54, 54, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, 18, mainTabs ? 104 : 24));
        root.addPane(up, AndroidUtilities.dp(27), true);

        fragmentView = root;
        drawTabs();
        reload();
        return root;
    }

    @Override
    public boolean isLightStatusBar() {
        return androidx.core.graphics.ColorUtils.calculateLuminance(Theme.getColor(Theme.key_windowBackgroundWhite)) > 0.7f;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) adapter.notifyDataSetChanged();
        AndroidUtilities.cancelRunOnUIThread(freshCheck);
        AndroidUtilities.runOnUIThread(freshCheck, 4000);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(freshCheck);
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(freshCheck);
    }

    private void toTop() {
        if (layout.findFirstVisibleItemPosition() > 12) list.scrollToPosition(8);
        list.smoothScrollToPosition(0);
        showUp(false);
    }

    private void showUp(boolean show) {
        if (up == null || upShown == show) return;
        upShown = show;
        up.animate().cancel();
        if (show) {
            up.setVisibility(View.VISIBLE);
            up.setAlpha(0f);
            up.setScaleX(0.6f);
            up.setScaleY(0.6f);
            up.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                    .setUpdateListener(a -> root.invalidate()).start();
        } else {
            up.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(180).setInterpolator(CubicBezierInterpolator.EASE_IN)
                    .setUpdateListener(a -> root.invalidate()).withEndAction(() -> up.setVisibility(View.GONE)).start();
        }
    }

    private int topRowHeight() {
        return AndroidUtilities.dp(52);
    }

    private void setShift(float value) {
        shift = Math.max(-topRowHeight(), Math.min(0, value));
        header.setTranslationY(shift);
        float p = 1f + shift / topRowHeight();
        topRow.setAlpha(p);
        topRow.setScaleX(0.94f + 0.06f * p);
        topRow.setScaleY(0.94f + 0.06f * p);
        pill.setTranslationY(shift);
        root.invalidate();
    }

    private void settle() {
        if (shift == 0 || shift == -topRowHeight()) return;
        float target = shift < -topRowHeight() / 2f && list.canScrollVertically(-1) ? -topRowHeight() : 0;
        snap = ValueAnimator.ofFloat(shift, target);
        snap.setDuration(260);
        snap.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        snap.addUpdateListener(a -> setShift((float) a.getAnimatedValue()));
        snap.start();
    }

    private FrameLayout buildHeader(Context context) {
        FrameLayout h = new FrameLayout(context);
        h.setPadding(0, AndroidUtilities.statusBarHeight, 0, 0);
        h.setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        h.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        topRow = new FrameLayout(context);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        if (mainTabs) {
            BackupImageView me = new BackupImageView(context);
            me.setRoundRadius(AndroidUtilities.dp(16));
            TLRPC.User user = getUserConfig().getCurrentUser();
            AvatarDrawable d = new AvatarDrawable();
            d.setInfo(currentAccount, user);
            me.setForUserOrChat(user, d);
            me.setOnClickListener(v -> presentFragment(ProfileActivity.of(getUserConfig().getClientUserId())));
            PrizmaFeedKit.press(me, 0.86f);
            topRow.addView(me, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 16, 0, 0, 0));
        } else {
            ImageView back = button(context, new PrizmaFeedIcons(PrizmaFeedIcons.BACK, 24).setColor(ink));
            back.setOnClickListener(v -> finishFragment());
            topRow.addView(back, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        }
        ImageView logo = new ImageView(context);
        try {
            logo.setImageDrawable(context.getPackageManager().getApplicationIcon(context.getApplicationInfo()));
        } catch (Throwable ignore) {
        }
        logo.setOnClickListener(v -> {
            if (list.canScrollVertically(-1)) toTop();
            else reload(true);
        });
        PrizmaFeedKit.press(logo, 0.84f);
        topRow.addView(logo, LayoutHelper.createFrame(34, 34, Gravity.CENTER));
        ImageView tune = new ImageView(context);
        tune.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.TUNE, 20).setColor(ink));
        tune.setScaleType(ImageView.ScaleType.CENTER);
        tune.setOnClickListener(v -> openSettings());
        PrizmaFeedKit.press(tune, 0.84f);
        topRow.addView(tune, LayoutHelper.createFrame(40, 40, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        glassTune = tune;
        column.addView(topRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52));

        tabsCapsule = new FrameLayout(context);
        tabsCapsule.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
            }
        });
        tabsCapsule.setClipToOutline(true);
        tabs = new Tabs(context);
        tabsCapsule.addView(tabs, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(tabsCapsule, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 12, 2, 12, 10));
        return h;
    }

    private ImageView button(Context context, Drawable icon) {
        ImageView v = new ImageView(context);
        v.setImageDrawable(icon);
        v.setScaleType(ImageView.ScaleType.CENTER);
        v.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
        PrizmaFeedKit.press(v, 0.84f);
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

    private ArrayList<String> tabKeys() {
        ArrayList<String> keys = new ArrayList<>();
        keys.add(FOR_YOU);
        keys.add(FOLLOWING);
        keys.addAll(groups.keySet());
        return keys;
    }

    private void drawTabs() {
        ArrayList<String> keys = tabKeys();
        ArrayList<String> titles = new ArrayList<>();
        for (String k : keys) titles.add(FOR_YOU.equals(k) ? "Для вас" : FOLLOWING.equals(k) ? "Вы читаете" : k);
        tabs.set(keys, titles, mode);
    }

    private void selectTab(String key) {
        if (key.equals(mode)) {
            if (list.canScrollVertically(-1)) toTop();
            else reload(true);
            return;
        }
        ArrayList<String> keys = tabKeys();
        int dir = keys.indexOf(key) > keys.indexOf(mode) ? 1 : -1;
        mode = key;
        tabs.select(key, true);
        list.animate().cancel();
        list.animate().alpha(0f).translationX(-dir * AndroidUtilities.dp(28)).setDuration(140).setInterpolator(CubicBezierInterpolator.EASE_IN)
                .setUpdateListener(a -> root.invalidate())
                .withEndAction(() -> {
                    list.setTranslationX(dir * AndroidUtilities.dp(28));
                    reload();
                }).start();
    }

    private void revealList() {
        if (list.getAlpha() >= 1f && list.getTranslationX() == 0) return;
        list.animate().cancel();
        list.animate().alpha(1f).translationX(0).setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                .setUpdateListener(a -> root.invalidate()).start();
    }

    private boolean recsEnabled() {
        return PrizmaFeedKit.store().getBoolean("recs", true);
    }

    private void openSettings() {
        Context context = getParentActivity();
        if (context == null) return;
        int hiddenCount = PrizmaFeedKit.hidden().size();
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add(recsEnabled() ? "Выключить рекомендации в «Для вас»" : "Включить рекомендации в «Для вас»");
        icons.add(R.drawable.msg_channel);
        actions.add(() -> {
            PrizmaFeedKit.store().edit().putBoolean("recs", !recsEnabled()).apply();
            recommended.clear();
            recommendedIds.clear();
            recommendationsLoaded = false;
            if (FOR_YOU.equals(mode)) reload();
        });
        items.add("Новая вкладка с каналами");
        icons.add(R.drawable.msg_folders);
        actions.add(() -> askName(context, null));
        if (groups.containsKey(mode)) {
            items.add("Изменить вкладку «" + mode + "»");
            icons.add(R.drawable.msg_channel);
            String current = mode;
            actions.add(() -> manage(context, current));
        }
        if (hiddenCount > 0) {
            items.add("Вернуть скрытые каналы (" + hiddenCount + ")");
            icons.add(R.drawable.msg_reset);
            actions.add(() -> {
                PrizmaFeedKit.unhideAll();
                reload();
            });
        }
        items.add("Забыть просмотренное");
        icons.add(R.drawable.msg_cancel);
        actions.add(() -> {
            PrizmaFeedKit.store().edit().remove("seen").apply();
            reload();
        });
        items.add("Обновить ленту");
        icons.add(R.drawable.msg_retry);
        actions.add(() -> reload(true));
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setTitle("Настройка ленты");
        b.setItems(items.toArray(new CharSequence[0]), ic, (d, which) -> actions.get(which).run());
        showDialog(b.create());
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
                .setTitle(current == null ? "Новая вкладка" : "Название")
                .setView(box)
                .setPositiveButton("Дальше", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    HashSet<Long> ids = current != null && groups.containsKey(current) ? groups.remove(current) : new HashSet<>();
                    groups.put(name, ids);
                    saveGroups();
                    if (current != null && current.equals(mode)) mode = name;
                    drawTabs();
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
                        drawTabs();
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
        head.setText("Каналы во вкладке «" + name + "»");
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        head.setTypeface(AndroidUtilities.bold());
        head.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        head.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(16), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
        column.addView(head);
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        for (TLRPC.Chat chat : allChannels()) {
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
        PrizmaFeedKit.press(done, 0.96f);
        column.addView(done, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 14));
        sheet.setCustomView(column);
        sheet.setOnDismissListener(d -> {
            saveGroups();
            if (name.equals(mode)) reload();
        });
        sheet.show();
    }

    private ArrayList<TLRPC.Chat> allChannels() {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        for (TLRPC.Dialog d : getMessagesController().getAllDialogs()) {
            if (d.id >= 0) continue;
            TLRPC.Chat chat = getMessagesController().getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) && !chat.kicked) continue;
            out.add(chat);
        }
        return out;
    }

    private float affinityOf(TLRPC.Dialog d) {
        float a = 1f;
        if (d.pinned) a *= 1.2f;
        if (d.folder_id == 1) a *= 0.6f;
        if (getMessagesController().isDialogMuted(d.id, 0)) a *= 0.85f;
        if (d.unread_count <= 15) a *= 1.15f;
        else if (d.unread_count > 300) a *= 0.75f;
        else if (d.unread_count > 80) a *= 0.9f;
        return a;
    }

    private ArrayList<TLRPC.Chat> candidates() {
        ArrayList<TLRPC.Chat> out = new ArrayList<>();
        HashSet<Long> hide = PrizmaFeedKit.hidden();
        HashSet<Long> filter = groups.get(mode);
        int now = getConnectionsManager().getCurrentTime();
        boolean forYou = FOR_YOU.equals(mode);
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(getMessagesController().getAllDialogs());
        Collections.sort(dialogs, (a, b) -> Integer.compare(b.last_message_date, a.last_message_date));
        for (TLRPC.Dialog d : dialogs) {
            if (d.id >= 0) continue;
            TLRPC.Chat chat = getMessagesController().getChat(-d.id);
            if (chat == null || !ChatObject.isChannelAndNotMegaGroup(chat) || ChatObject.isNotInChat(chat) && !chat.kicked) continue;
            if (filter != null && !filter.contains(chat.id)) continue;
            if (filter == null && hide.contains(chat.id)) continue;
            if (forYou && d.last_message_date > 0 && now - d.last_message_date > windowHours * 3600) continue;
            affinity.put(chat.id, affinityOf(d));
            out.add(chat);
            if (out.size() >= MAX_CHANNELS) break;
        }
        if (forYou) {
            for (TLRPC.Chat c : recommended) {
                if (!hide.contains(c.id)) {
                    affinity.put(c.id, 0.85f);
                    out.add(c);
                }
            }
        }
        return out;
    }

    private void checkFresh() {
        if (posts.isEmpty() || loading || pill == null || pending != null) return;
        HashSet<Long> allowed = new HashSet<>();
        for (TLRPC.Chat c : candidates()) allowed.add(c.id);
        ArrayList<TLRPC.Chat> fresh = new ArrayList<>();
        for (TLRPC.Dialog d : getMessagesController().getAllDialogs()) {
            if (d.id >= 0 || !allowed.contains(-d.id) || d.last_message_date <= newestDate) continue;
            TLRPC.Chat c = getMessagesController().getChat(-d.id);
            if (c != null) fresh.add(c);
            if (fresh.size() >= 3) break;
        }
        if (!fresh.isEmpty()) {
            pill.show(fresh, "опубликовали посты", () -> reload(true));
        }
    }

    private boolean manual;

    private void reload() {
        reload(false);
    }

    private void reload(boolean manualRefresh) {
        generation++;
        manual = manualRefresh;
        posts.clear();
        pool.clear();
        albums.clear();
        seen.clear();
        oldest.clear();
        oldestDate.clear();
        exhausted.clear();
        requested.clear();
        appeared.clear();
        pending = null;
        published = false;
        fromCache = false;
        userScrolled = false;
        windowHours = 72;
        newestDate = 0;
        inflight = 0;
        loading = false;
        if (adapter != null) adapter.notifyDataSetChanged();
        if (list != null) list.scrollToPosition(0);
        if (pill != null) pill.hide();
        if (header != null) setShift(0);
        showUp(false);
        final int gen = generation;
        final String m = mode;
        AndroidUtilities.runOnUIThread(() -> {
            if (gen != generation) return;
            if (inflight > 0 || loading) {
                inflight = 0;
                loading = false;
                if (!published) publish(gen);
                else onWaveDone(gen);
            }
        }, 9000);
        if (!manualRefresh) PrizmaFeedKit.loadCache(currentAccount, m, cached -> {
            if (gen != generation || published || cached == null || cached.groups.isEmpty()) return;
            getMessagesController().putChats(cached.chats, true);
            ArrayList<ArrayList<MessageObject>> restored = new ArrayList<>();
            HashSet<Long> hide = PrizmaFeedKit.hidden();
            for (ArrayList<TLRPC.Message> g : cached.groups) {
                ArrayList<MessageObject> mg = new ArrayList<>();
                for (TLRPC.Message msg : g) mg.add(new MessageObject(currentAccount, msg, false, false));
                if (mg.isEmpty() || hide.contains(-mg.get(0).getDialogId())) continue;
                restored.add(mg);
            }
            if (restored.isEmpty()) return;
            fromCache = true;
            posts.addAll(restored);
            staggerBase = System.currentTimeMillis();
            adapter.notifyDataSetChanged();
            showState(false, null);
            revealList();
        });
        if (FOR_YOU.equals(mode) && !recommendationsLoaded && recsEnabled()) loadRecommendations();
        firstWave();
    }

    private void loadRecommendations() {
        ArrayList<TLRPC.Chat> seeds = allChannels();
        Collections.shuffle(seeds);
        ArrayList<TLRPC.TL_channels_getChannelRecommendations> requests = new ArrayList<>();
        requests.add(new TLRPC.TL_channels_getChannelRecommendations());
        for (int i = 0; i < Math.min(3, seeds.size()); i++) {
            TLRPC.TL_channels_getChannelRecommendations r = new TLRPC.TL_channels_getChannelRecommendations();
            r.flags |= 1;
            r.channel = getMessagesController().getInputChannel(seeds.get(i).id);
            requests.add(r);
        }
        recommendationsLoaded = true;
        final int gen = generation;
        final int[] left = {requests.size()};
        for (TLRPC.TL_channels_getChannelRecommendations r : requests) {
            getConnectionsManager().sendRequest(r, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                ArrayList<TLRPC.Chat> fresh = new ArrayList<>();
                if (res instanceof TLRPC.messages_Chats) {
                    ArrayList<TLRPC.Chat> chats = ((TLRPC.messages_Chats) res).chats;
                    getMessagesController().putChats(chats, false);
                    HashSet<Long> hide = PrizmaFeedKit.hidden();
                    for (TLRPC.Chat c : chats) {
                        if (recommended.size() >= MAX_RECOMMENDED) break;
                        if (c == null || !ChatObject.isChannelAndNotMegaGroup(c) || !ChatObject.isNotInChat(c) || hide.contains(c.id)) continue;
                        if (recommendedIds.add(c.id)) {
                            recommended.add(c);
                            fresh.add(c);
                        }
                    }
                }
                left[0]--;
                if (gen == generation && FOR_YOU.equals(mode) && !fresh.isEmpty()) {
                    for (TLRPC.Chat c : fresh) affinity.put(c.id, 0.85f);
                    fetch(fresh, 4, gen);
                }
            }));
        }
    }

    private void firstWave() {
        ArrayList<TLRPC.Chat> all = candidates();
        if (all.isEmpty() && !FOR_YOU.equals(mode)) {
            showState(false, groups.containsKey(mode) ? "Во вкладке пока нет каналов. Нажмите на настройку справа сверху, чтобы выбрать их" : "Вы пока не подписаны на каналы");
            revealList();
            return;
        }
        if (posts.isEmpty()) showState(true, null);
        loading = true;
        final int gen = generation;
        fetch(all, FOR_YOU.equals(mode) ? 8 : 6, gen);
        AndroidUtilities.runOnUIThread(() -> {
            if (gen == generation && !published && pool.size() >= 12) publish(gen);
        }, 1600);
        AndroidUtilities.runOnUIThread(() -> {
            if (gen == generation && !published) publish(gen);
        }, 6000);
    }

    private void fetch(ArrayList<TLRPC.Chat> chats, int limit, int gen) {
        final ArrayList<TLRPC.Chat> todo = new ArrayList<>();
        for (TLRPC.Chat c : chats) if (!exhausted.contains(c.id)) todo.add(c);
        if (todo.isEmpty()) {
            if (inflight == 0) onWaveDone(gen);
            return;
        }
        inflight += todo.size();
        final int[] next = {0};
        Runnable[] pump = new Runnable[1];
        pump[0] = () -> {
            if (gen != generation || next[0] >= todo.size()) return;
            TLRPC.Chat chat = todo.get(next[0]++);
            requested.add(chat.id);
            TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = getMessagesController().getInputPeer(-chat.id);
            req.limit = limit;
            Integer from = oldest.get(chat.id);
            req.offset_id = from == null ? 0 : from;
            final boolean firstPage = from == null;
            final String pageKey = currentAccount + ":" + chat.id;
            FirstPage cached = firstPage && !manual ? firstPages.get(pageKey) : null;
            if (cached != null && System.currentTimeMillis() - cached.at < FIRST_PAGE_TTL) {
                onHistory(chat, cached.messages, cached.exhausted, gen, pump[0]);
                return;
            }
            int id = getConnectionsManager().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (gen != generation) return;
                if (res instanceof TLRPC.messages_Messages) {
                    TLRPC.messages_Messages m = (TLRPC.messages_Messages) res;
                    getMessagesController().putUsers(m.users, false);
                    getMessagesController().putChats(m.chats, false);
                    boolean end = m.messages.size() < req.limit;
                    if (firstPage) {
                        FirstPage page = new FirstPage();
                        page.messages = m.messages;
                        page.exhausted = end;
                        page.at = System.currentTimeMillis();
                        firstPages.put(pageKey, page);
                    }
                    onHistory(chat, m.messages, end, gen, pump[0]);
                } else {
                    onHistory(chat, null, true, gen, pump[0]);
                }
            }));
            getConnectionsManager().bindRequestToGuid(id, classGuid);
        };
        for (int i = 0; i < PARALLEL; i++) pump[0].run();
    }

    private void onHistory(TLRPC.Chat chat, ArrayList<TLRPC.Message> messages, boolean end, int gen, Runnable next) {
        if (gen != generation) return;
        ArrayList<MessageObject> batch = new ArrayList<>();
        if (messages != null) {
            int min = Integer.MAX_VALUE, minDate = Integer.MAX_VALUE;
            for (TLRPC.Message msg : messages) {
                min = Math.min(min, msg.id);
                minDate = Math.min(minDate, msg.date);
                if (msg instanceof TLRPC.TL_messageService) continue;
                batch.add(new MessageObject(currentAccount, msg, false, false));
            }
            if (min != Integer.MAX_VALUE) {
                oldest.put(chat.id, min);
                oldestDate.put(chat.id, minDate);
            }
        }
        if (end) exhausted.add(chat.id);
        collect(batch);
        inflight--;
        if (inflight <= 0) {
            inflight = 0;
            onWaveDone(gen);
        } else {
            next.run();
        }
    }

    private void collect(ArrayList<MessageObject> batch) {
        Collections.sort(batch, (a, b) -> Integer.compare(a.getId(), b.getId()));
        boolean touchedShown = false;
        for (MessageObject mo : batch) {
            String k = PrizmaFeedKit.key(mo);
            if (dismissed.contains(k) || !seen.add(k)) continue;
            long album = mo.messageOwner.grouped_id;
            if (album != 0) {
                ArrayList<MessageObject> g = albums.get(album);
                if (g != null) {
                    if (TextUtils.isEmpty(g.get(0).messageOwner.message) && !TextUtils.isEmpty(mo.messageOwner.message)) g.add(0, mo);
                    else g.add(mo);
                    if (posts.contains(g)) touchedShown = true;
                    continue;
                }
                g = new ArrayList<>();
                g.add(mo);
                albums.put(album, g);
                pool.add(g);
            } else {
                ArrayList<MessageObject> g = new ArrayList<>();
                g.add(mo);
                pool.add(g);
            }
            if (!recommendedIds.contains(-mo.getDialogId())) newestDate = Math.max(newestDate, mo.messageOwner.date);
        }
        if (touchedShown) adapter.notifyDataSetChanged();
    }

    private void onWaveDone(int gen) {
        if (gen != generation) return;
        if (!published) {
            publish(gen);
            return;
        }
        loading = false;
        if (!pool.isEmpty() && (posts.size() < PAGE || layout.findLastVisibleItemPosition() >= posts.size() - 5)) appendFromPool();
        if (posts.size() < 6 && pool.isEmpty() && FOR_YOU.equals(mode) && windowHours < 24 * 14) {
            windowHours *= 2;
            loadMore();
        }
        if (posts.isEmpty()) showState(false, "Новых постов нет. Нажмите на логотип, чтобы обновить");
        else adapter.notifyItemChanged(posts.size());
    }

    private void publish(int gen) {
        if (gen != generation || published) return;
        published = true;
        if (inflight == 0) loading = false;
        if (FOR_YOU.equals(mode) && rankable() < 15 && windowHours < 24 * 14) {
            windowHours *= 3;
        }
        ArrayList<ArrayList<MessageObject>> ranked = take(pool.size());
        if (ranked.isEmpty()) {
            if (posts.isEmpty()) {
                showState(false, "Новых постов нет. Нажмите на логотип, чтобы обновить");
                revealList();
            }
            return;
        }
        boolean swapNow = manual || !fromCache || !userScrolled && layout.findFirstVisibleItemPosition() <= 0;
        if (swapNow) {
            swapIn(ranked);
        } else {
            pending = ranked;
            ArrayList<TLRPC.Chat> faces = new ArrayList<>();
            HashSet<Long> used = new HashSet<>();
            for (ArrayList<MessageObject> g : ranked) {
                long id = -g.get(0).getDialogId();
                if (used.add(id)) {
                    TLRPC.Chat c = getMessagesController().getChat(id);
                    if (c != null) faces.add(c);
                }
                if (faces.size() >= 3) break;
            }
            pill.show(faces, "Свежая подборка", () -> {
                if (pending != null) swapIn(pending);
                toTop();
            });
        }
    }

    private void swapIn(ArrayList<ArrayList<MessageObject>> ranked) {
        pending = null;
        fromCache = false;
        posts.clear();
        appeared.clear();
        posts.addAll(ranked);
        staggerBase = System.currentTimeMillis();
        adapter.notifyDataSetChanged();
        list.scrollToPosition(0);
        setShift(0);
        showState(false, null);
        revealList();
        ArrayList<TLRPC.Chat> chats = new ArrayList<>();
        HashSet<Long> ids = new HashSet<>();
        for (int i = 0; i < Math.min(posts.size(), 80); i++) {
            long id = -posts.get(i).get(0).getDialogId();
            if (ids.add(id)) {
                TLRPC.Chat c = getMessagesController().getChat(id);
                if (c != null) chats.add(c);
            }
        }
        PrizmaFeedKit.saveCache(currentAccount, mode, posts, chats);
    }

    private int rankable() {
        int now = getConnectionsManager().getCurrentTime();
        int n = 0;
        for (ArrayList<MessageObject> g : pool) if (now - g.get(0).messageOwner.date <= windowHours * 3600) n++;
        return n;
    }

    private void appendFromPool() {
        HashSet<String> shown = new HashSet<>();
        for (ArrayList<MessageObject> g : posts) shown.add(PrizmaFeedKit.key(g.get(0)));
        ArrayList<ArrayList<MessageObject>> next = new ArrayList<>();
        for (ArrayList<MessageObject> g : take(PAGE)) if (shown.add(PrizmaFeedKit.key(g.get(0)))) next.add(g);
        if (next.isEmpty()) return;
        int start = posts.size();
        posts.addAll(next);
        if (start == 0) {
            adapter.notifyDataSetChanged();
            showState(false, null);
            revealList();
        } else {
            adapter.notifyItemRangeInserted(start, next.size());
        }
    }

    private static boolean isAd(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.toLowerCase();
        return t.contains("erid") || t.contains("#реклама") || t.contains("на правах рекламы") || t.contains("#промо")
                || t.contains("реклама.") || t.startsWith("реклама") || t.contains("партнерский пост") || t.contains("партнёрский пост");
    }

    private HashMap<Long, Float> medians() {
        HashMap<Long, ArrayList<Integer>> views = new HashMap<>();
        for (ArrayList<ArrayList<MessageObject>> src : java.util.Arrays.asList(pool, posts)) {
            for (ArrayList<MessageObject> g : src) {
                long id = g.get(0).getDialogId();
                ArrayList<Integer> v = views.get(id);
                if (v == null) views.put(id, v = new ArrayList<>());
                v.add(g.get(0).messageOwner.views);
            }
        }
        HashMap<Long, Float> out = new HashMap<>();
        for (java.util.Map.Entry<Long, ArrayList<Integer>> e : views.entrySet()) {
            ArrayList<Integer> v = e.getValue();
            Collections.sort(v);
            out.put(e.getKey(), (float) Math.max(1, v.get(v.size() / 2)));
        }
        return out;
    }

    private double score(ArrayList<MessageObject> group, int now, HashMap<Long, Float> median) {
        MessageObject top = group.get(0);
        TLRPC.Message m = top.messageOwner;
        double hours = Math.max(0, (now - m.date) / 3600.0);
        if (hours > windowHours) return -1;
        Float med = median.get(top.getDialogId());
        double rel = Math.max(0.3, Math.min(4.0, m.views / (double) (med == null ? Math.max(1, m.views) : med)));
        double engagement = PrizmaFeedKit.reactions(m) + 3.0 * m.forwards + 2.0 * PrizmaFeedKit.replies(m);
        double rate = Math.min(0.15, engagement / Math.max(100.0, m.views));
        boolean media = m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty);
        int len = m.message == null ? 0 : m.message.length();
        double interest = 1.0 + 1.2 * Math.log1p(rel) + 12.0 * rate + Math.log10(Math.max(10, m.views)) * 0.15;
        if (media) interest += 0.25;
        if (group.size() > 1) interest += 0.15;
        if (len >= 80 && len <= 1800) interest += 0.2;
        if (!media && len < 40) interest *= 0.6;
        if (isAd(m.message)) interest *= 0.3;
        if (PrizmaFeedKit.wasSeen(top)) interest *= 0.45;
        double fresh = Math.exp(-hours / 20.0);
        Float aff = affinity.get(-top.getDialogId());
        return interest * fresh * (aff == null ? 1f : aff);
    }

    private ArrayList<ArrayList<MessageObject>> take(int max) {
        ArrayList<ArrayList<MessageObject>> out = new ArrayList<>();
        if (pool.isEmpty()) return out;
        if (!FOR_YOU.equals(mode)) {
            Collections.sort(pool, (a, b) -> Integer.compare(b.get(0).messageOwner.date, a.get(0).messageOwner.date));
            int n = Math.min(max, pool.size());
            out.addAll(pool.subList(0, n));
            pool.subList(0, n).clear();
            return out;
        }
        int now = getConnectionsManager().getCurrentTime();
        HashMap<Long, Float> median = medians();
        medianCache.clear();
        medianCache.putAll(median);
        HashMap<ArrayList<MessageObject>, Double> scores = new HashMap<>();
        ArrayList<ArrayList<MessageObject>> live = new ArrayList<>();
        for (ArrayList<MessageObject> g : pool) {
            double s = score(g, now, median);
            if (s >= 0) {
                scores.put(g, s);
                live.add(g);
            }
        }
        Collections.sort(live, (a, b) -> Double.compare(scores.get(b), scores.get(a)));
        HashMap<Long, Integer> perChannel = new HashMap<>();
        ArrayList<Long> recent = new ArrayList<>();
        for (int i = Math.max(0, posts.size() - 3); i < posts.size(); i++) recent.add(posts.get(i).get(0).getDialogId());
        int sinceRec = 0;
        while (out.size() < max && !live.isEmpty()) {
            int pick = -1;
            for (int i = 0; i < live.size(); i++) {
                long id = live.get(i).get(0).getDialogId();
                Integer used = perChannel.get(id);
                if (recent.contains(id) || used != null && used >= 3) continue;
                boolean rec = recommendedIds.contains(-id);
                if (rec && sinceRec < 4) continue;
                pick = i;
                break;
            }
            if (pick < 0) pick = 0;
            ArrayList<MessageObject> g = live.remove(pick);
            long id = g.get(0).getDialogId();
            Integer was = perChannel.get(id);
            perChannel.put(id, (was == null ? 0 : was) + 1);
            recent.add(id);
            if (recent.size() > 3) recent.remove(0);
            sinceRec = recommendedIds.contains(-id) ? 0 : sinceRec + 1;
            out.add(g);
        }
        pool.removeAll(out);
        if (windowHours >= 24 * 14) {
            for (int i = pool.size() - 1; i >= 0; i--) if (!scores.containsKey(pool.get(i))) pool.remove(i);
        }
        return out;
    }

    private void loadMore() {
        if (loading || !published) return;
        if (!pool.isEmpty()) {
            int before = posts.size();
            appendFromPool();
            if (posts.size() > before) return;
        }
        ArrayList<TLRPC.Chat> next = new ArrayList<>();
        int now = getConnectionsManager().getCurrentTime();
        for (TLRPC.Chat c : candidates()) {
            if (exhausted.contains(c.id) || recommendedIds.contains(c.id)) continue;
            Integer d = oldestDate.get(c.id);
            if (FOR_YOU.equals(mode) && d != null && now - d > windowHours * 3600) continue;
            next.add(c);
        }
        if (next.isEmpty()) {
            if (FOR_YOU.equals(mode) && windowHours < 24 * 14) {
                windowHours *= 2;
                loadMore();
            }
            return;
        }
        loading = true;
        if (!posts.isEmpty()) adapter.notifyItemChanged(posts.size());
        fetch(next, 6, generation);
    }

    private void showState(boolean busy, String text) {
        if (spinner == null) return;
        spinner.setVisibility(busy && posts.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(text == null ? "" : text);
        empty.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    @Override
    public void showMenu(ArrayList<MessageObject> group) {
        Context context = getParentActivity();
        if (context == null) return;
        MessageObject mo = group.get(0);
        long chatId = -mo.getDialogId();
        TLRPC.Chat chat = getMessagesController().getChat(chatId);
        boolean joined = chat != null && !ChatObject.isNotInChat(chat);
        String handle = PrizmaFeedKit.handle(chat);
        String who = handle != null ? handle : chat != null ? "«" + chat.title + "»" : "канал";
        String link = PrizmaFeedKit.link(chat, mo.getId());
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Integer> icons = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Не интересует этот пост");
        icons.add(R.drawable.msg_cancel);
        actions.add(() -> {
            for (MessageObject m : group) dismissed.add(PrizmaFeedKit.key(m));
            Float a = affinity.get(chatId);
            affinity.put(chatId, (a == null ? 1f : a) * 0.7f);
            int pos = posts.indexOf(group);
            if (pos >= 0) {
                posts.remove(pos);
                if (posts.isEmpty()) adapter.notifyDataSetChanged();
                else adapter.notifyItemRemoved(pos);
            }
            BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, "Таких постов будет меньше").show();
        });
        items.add("Не показывать " + who);
        icons.add(R.drawable.msg_block);
        actions.add(() -> {
            PrizmaFeedKit.hide(chatId);
            for (int i = posts.size() - 1; i >= 0; i--) if (-posts.get(i).get(0).getDialogId() == chatId) posts.remove(i);
            for (int i = pool.size() - 1; i >= 0; i--) if (-pool.get(i).get(0).getDialogId() == chatId) pool.remove(i);
            adapter.notifyDataSetChanged();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.ic_ban, "Посты " + who + " больше не появятся").show();
        });
        if (chat != null) {
            items.add(joined ? "Перестать читать " + who : "Читать " + who);
            icons.add(joined ? R.drawable.msg_leave : R.drawable.msg_addcontact);
            actions.add(() -> {
                if (!joined) {
                    getMessagesController().addUserToChat(chatId, getUserConfig().getCurrentUser(), 0, null, this, null);
                    adapter.notifyDataSetChanged();
                    return;
                }
                new AlertDialog.Builder(context)
                        .setTitle("Перестать читать?")
                        .setMessage("Вы выйдете из канала " + who + ". В закрытый канал без приглашения вернуться не получится.")
                        .setPositiveButton("Выйти", (d, w) -> {
                            getMessagesController().deleteParticipantFromChat(chatId, getUserConfig().getCurrentUser());
                            adapter.notifyDataSetChanged();
                        })
                        .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .show();
            });
        }
        items.add("Открыть в канале");
        icons.add(R.drawable.msg_openin);
        actions.add(() -> PrizmaFeedKit.openPost(this, mo));
        if (link != null) {
            items.add("Скопировать ссылку");
            icons.add(R.drawable.msg_link);
            actions.add(() -> {
                AndroidUtilities.addToClipboard(link);
                BulletinFactory.of(this).createCopyLinkBulletin().show();
            });
        }
        if (!groups.isEmpty() && chat != null && joined) {
            items.add("Добавить во вкладку…");
            icons.add(R.drawable.msg_folders);
            actions.add(() -> {
                ArrayList<String> names = new ArrayList<>(groups.keySet());
                CharSequence[] arr = new CharSequence[names.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = (groups.get(names.get(i)).contains(chatId) ? "✓ " : "") + names.get(i);
                new AlertDialog.Builder(context)
                        .setTitle("Вкладка для " + who)
                        .setItems(arr, (d, w) -> {
                            HashSet<Long> set = groups.get(names.get(w));
                            if (!set.remove(chatId)) set.add(chatId);
                            saveGroups();
                        })
                        .show();
            });
        }
        int[] ic = new int[icons.size()];
        for (int i = 0; i < ic.length; i++) ic[i] = icons.get(i);
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setItems(items.toArray(new CharSequence[0]), ic, (d, which) -> actions.get(which).run());
        showDialog(b.create());
    }

    private final class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return false;
        }

        @Override
        public int getItemCount() {
            return posts.size() + (posts.isEmpty() ? 0 : 1);
        }

        @Override
        public int getItemViewType(int position) {
            return position < posts.size() ? 0 : 1;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v;
            if (viewType == 0) {
                v = new PrizmaPostCell(context, PrizmaFeedActivity.this);
            } else {
                FrameLayout f = new FrameLayout(context);
                RadialProgressView p = new RadialProgressView(context);
                p.setSize(AndroidUtilities.dp(22));
                p.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                p.setTag("spin");
                f.addView(p, LayoutHelper.createFrame(36, 36, Gravity.CENTER));
                v = f;
            }
            v.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, viewType == 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : AndroidUtilities.dp(64)));
            return new RecyclerListView.Holder(v);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == 0) {
                ArrayList<MessageObject> g = posts.get(position);
                PrizmaPostCell cell = (PrizmaPostCell) holder.itemView;
                cell.bind(g, false, null);
                cell.animate().cancel();
                String k = PrizmaFeedKit.key(g.get(0));
                if (appeared.add(k)) {
                    PrizmaFeedKit.markSeen(g.get(0));
                    long since = System.currentTimeMillis() - staggerBase;
                    if (since < 1200) {
                        int first = Math.max(0, layout.findFirstVisibleItemPosition());
                        long delay = Math.min(260, Math.max(0, position - first) * 55L);
                        cell.setAlpha(0f);
                        cell.setTranslationY(AndroidUtilities.dp(18));
                        cell.animate().alpha(1f).translationY(0).setStartDelay(delay).setDuration(360)
                                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setUpdateListener(a -> root.invalidate()).start();
                    } else {
                        cell.setAlpha(1f);
                        cell.setTranslationY(0);
                    }
                } else {
                    cell.setAlpha(1f);
                    cell.setTranslationY(0);
                }
            } else {
                View spin = holder.itemView.findViewWithTag("spin");
                if (spin != null) spin.setVisibility(loading ? View.VISIBLE : View.INVISIBLE);
            }
        }
    }

    private final class Tabs extends HorizontalScrollView {
        private final LinearLayout row;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final ArrayList<String> keys = new ArrayList<>();
        private float left, right, lensWidth;
        private ValueAnimator anim;
        private String selected;

        Tabs(Context context) {
            super(context);
            setHorizontalScrollBarEnabled(false);
            setFillViewport(true);
            row = new LinearLayout(context) {
                @Override
                protected void dispatchDraw(Canvas canvas) {
                    super.dispatchDraw(canvas);
                    if (right > left) {
                        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
                        bar.setColor(Theme.multAlpha(ink, 0.1f));
                        float hh = AndroidUtilities.dp(34);
                        float stretch = Math.max(0f, (right - left) - lensWidth) * 0.04f;
                        rect.set(left, (getHeight() - hh) / 2f + stretch, right, (getHeight() + hh) / 2f - stretch);
                        float rr = rect.height() / 2f;
                        canvas.drawRoundRect(rect, rr, rr, bar);
                        bar.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                        float lw = Math.min(AndroidUtilities.dp(18), (right - left) * 0.3f);
                        float cx = rect.centerX();
                        rect.set(cx - lw / 2f, rect.bottom - AndroidUtilities.dp(4.5f), cx + lw / 2f, rect.bottom - AndroidUtilities.dp(2.5f));
                        canvas.drawRoundRect(rect, AndroidUtilities.dp(1), AndroidUtilities.dp(1), bar);
                    }
                }

                @Override
                protected void onLayout(boolean changed, int l, int t, int r, int b) {
                    super.onLayout(changed, l, t, r, b);
                    if (anim == null || !anim.isRunning()) snapIndicator();
                }
            };
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setWillNotDraw(false);
            row.setPadding(AndroidUtilities.dp(5), 0, AndroidUtilities.dp(5), 0);
            addView(row, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void set(ArrayList<String> keys, ArrayList<String> titles, String selected) {
            this.keys.clear();
            this.keys.addAll(keys);
            this.selected = selected;
            row.removeAllViews();
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                TextView t = new TextView(getContext());
                t.setText(titles.get(i));
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
                t.setOnClickListener(v -> selectTab(key));
                PrizmaFeedKit.press(t, 0.92f);
                if (groups.containsKey(key)) {
                    t.setOnLongClickListener(v -> {
                        manage(getContext(), key);
                        return true;
                    });
                }
                row.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            TextView add = new TextView(getContext());
            add.setText("Добавить");
            add.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            add.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            add.setGravity(Gravity.CENTER);
            add.setSingleLine(true);
            PrizmaFeedIcons plus = new PrizmaFeedIcons(PrizmaFeedIcons.PLUS, 16).setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            plus.setBounds(0, 0, plus.getIntrinsicWidth(), plus.getIntrinsicHeight());
            add.setCompoundDrawables(null, null, plus, null);
            add.setCompoundDrawablePadding(AndroidUtilities.dp(4));
            add.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
            add.setOnClickListener(v -> askName(getContext(), null));
            PrizmaFeedKit.press(add, 0.92f);
            row.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            paint(false);
            requestLayout();
        }

        private void paint(boolean animated) {
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            for (int i = 0; i < keys.size() && i < row.getChildCount(); i++) {
                TextView t = (TextView) row.getChildAt(i);
                boolean on = keys.get(i).equals(selected);
                t.setTypeface(on ? AndroidUtilities.bold() : null);
                int to = on ? ink : gray;
                if (animated && t.getCurrentTextColor() != to) {
                    int from = t.getCurrentTextColor();
                    ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
                    va.setDuration(220);
                    va.addUpdateListener(a -> t.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(from, to, (float) a.getAnimatedValue())));
                    va.start();
                } else {
                    t.setTextColor(to);
                }
            }
        }

        private float[] target() {
            int i = keys.indexOf(selected);
            if (i < 0 || i >= row.getChildCount()) return new float[]{0, 0};
            TextView t = (TextView) row.getChildAt(i);
            float w = t.getPaint().measureText(t.getText().toString()) + AndroidUtilities.dp(30);
            float cx = t.getLeft() + t.getWidth() / 2f;
            lensWidth = w;
            return new float[]{cx - w / 2f, cx + w / 2f};
        }

        private void snapIndicator() {
            float[] tg = target();
            left = tg[0];
            right = tg[1];
            row.invalidate();
        }

        void select(String key, boolean animated) {
            selected = key;
            paint(animated);
            float[] tg = target();
            int i = keys.indexOf(key);
            if (i >= 0 && i < row.getChildCount()) {
                View t = row.getChildAt(i);
                smoothScrollTo(Math.max(0, t.getLeft() - (getWidth() - t.getWidth()) / 2), 0);
            }
            if (!animated) {
                snapIndicator();
                return;
            }
            if (anim != null) anim.cancel();
            float l0 = left, r0 = right;
            boolean forward = tg[0] > l0;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(420);
            anim.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            anim.addUpdateListener(a -> {
                float t = (float) a.getAnimatedValue();
                float lead = Math.min(1f, t * 1.35f), trail = Math.max(0f, (t - 0.35f) / 0.65f);
                left = AndroidUtilities.lerp(l0, tg[0], forward ? trail : lead);
                right = AndroidUtilities.lerp(r0, tg[1], forward ? lead : trail);
                row.invalidate();
                root.invalidate();
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (anim == animation) snapIndicator();
                }
            });
            anim.start();
        }
    }

    private final class Pill extends LinearLayout {
        private final LinearLayout faces;
        private final TextView label;
        private Runnable action;
        private boolean shown;

        Pill(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(16), 0);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            ImageView arrow = new ImageView(context);
            arrow.setImageDrawable(new PrizmaFeedIcons(PrizmaFeedIcons.UP, 18).setColor(Theme.getColor(Theme.key_featuredStickers_addButton)));
            addView(arrow, LayoutHelper.createLinear(20, 20, 0, 0, 6, 0));
            faces = new LinearLayout(context);
            faces.setOrientation(HORIZONTAL);
            addView(faces, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 26, 0, 0, 8, 0));
            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            label.setTypeface(AndroidUtilities.bold());
            label.setTextColor(ink);
            addView(label);
            setVisibility(GONE);
            setOnClickListener(v -> {
                hide();
                if (action != null) action.run();
            });
            PrizmaFeedKit.press(this, 0.92f);
        }

        void show(ArrayList<TLRPC.Chat> chats, String text, Runnable onTap) {
            action = onTap;
            label.setText(text);
            faces.removeAllViews();
            for (int i = 0; i < chats.size(); i++) {
                BackupImageView a = new BackupImageView(getContext());
                a.setRoundRadius(AndroidUtilities.dp(13));
                AvatarDrawable d = new AvatarDrawable();
                d.setInfo(currentAccount, chats.get(i));
                a.setForUserOrChat(chats.get(i), d);
                faces.addView(a, LayoutHelper.createLinear(26, 26, i == 0 ? 0 : -8, 0, 0, 0));
            }
            faces.setVisibility(chats.isEmpty() ? GONE : VISIBLE);
            if (shown) return;
            shown = true;
            animate().cancel();
            if (getVisibility() != VISIBLE) {
                setVisibility(VISIBLE);
                setAlpha(0f);
                setScaleX(0.7f);
                setScaleY(0.7f);
            }
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK)
                    .setUpdateListener(a -> root.invalidate()).start();
        }

        void hide() {
            if (!shown) return;
            shown = false;
            animate().cancel();
            animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200).setUpdateListener(a -> root.invalidate())
                    .withEndAction(() -> {
                        if (!shown) setVisibility(GONE);
                    }).start();
        }
    }
}
