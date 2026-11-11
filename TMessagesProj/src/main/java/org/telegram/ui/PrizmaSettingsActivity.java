package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.PrizmaClans;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaFonts;
import org.telegram.messenger.PrizmaMusicStatus;
import org.telegram.messenger.PrizmaOnline;
import org.telegram.messenger.PrizmaPresence;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.PrizmaSounds;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaArchiveLock;
import org.telegram.ui.Components.PrizmaBackup;
import org.telegram.ui.Components.PrizmaBanner;
import org.telegram.ui.Components.PrizmaChatWallpaper;
import org.telegram.ui.Components.PrizmaClanSheet;
import org.telegram.ui.Components.PrizmaCloudSetup;
import org.telegram.ui.Components.PrizmaGiftsSheet;
import org.telegram.ui.Components.PrizmaGlass;
import org.telegram.ui.Components.PrizmaGlassBar;
import org.telegram.ui.Components.PrizmaGlassFrame;
import org.telegram.ui.Components.PrizmaMaxSetup;
import org.telegram.ui.Components.PrizmaNfcExchange;
import org.telegram.ui.Components.PrizmaPalette;
import org.telegram.ui.Components.PrizmaPlayerSettings;
import org.telegram.ui.Components.PrizmaShape;
import org.telegram.ui.Components.PrizmaPlaylistSheet;
import org.telegram.ui.Components.PrizmaStealthSheet;
import org.telegram.ui.Components.PrizmaSwitch;
import org.telegram.ui.Components.PrizmaTogether;
import org.telegram.ui.Components.PrizmaTraces;
import org.telegram.ui.Components.PrizmaUpdater;
import org.telegram.ui.Components.PrizmaWallpaper;

import java.util.ArrayList;
import java.util.Locale;

public class PrizmaSettingsActivity extends BaseFragment {
    private static final int HOME = -1;
    private static final int GHOST = 0, GUARD = 1, LOOK = 2, SOCIAL = 3, MUSIC = 4, OTHER = 5;
    private static final String[] NAMES = {"Режим призрака", "Защита", "Оформление", "Профиль Libre", "Музыка", "Прочее"};
    private static final String[] BLURBS = {
            "Что о вас видят другие",
            "Пароли, следы, контакты",
            "Иконка, пузыри, шрифт, обои",
            "Сервер, стена, клан, копия",
            "Плеер, плейлисты, эфир",
            "Реклама, перевод, обновления"
    };
    private static final int[] ICONS = {
            R.drawable.prizma_ic_ghost, R.drawable.prizma_ic_shield, R.drawable.prizma_ic_brush,
            R.drawable.prizma_ic_person, R.drawable.prizma_ic_music, R.drawable.prizma_ic_more
    };
    private static final int[] SEEDS = {0xff8f7bff, 0xff4fb37a, 0xffff8a5c, 0xff4ba3e3, 0xffff6f91, 0xff9aa0a8};

    private interface Flag {
        boolean get();
    }

    private interface Apply {
        void set(boolean value);
    }

    private static final class Entry {
        int section;
        String title, desc;
        Flag flag;
        Apply apply;
        Runnable action;
        View custom;
    }

    private final int section;
    private LinearLayout content;
    private LinearLayout homeBody;
    private LinearLayout results;
    private final ArrayList<Entry> entries = new ArrayList<>();
    private int textColor, grayColor, cardColor, accent, onAccent, dividerColor;
    private PrizmaSwitch ghostSwitch;
    private boolean resumedOnce;

    public PrizmaSettingsActivity() {
        this(HOME);
    }

    private PrizmaSettingsActivity(int section) {
        this.section = section;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        textColor = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        grayColor = getThemedColor(Theme.key_windowBackgroundWhiteGrayText);
        cardColor = getThemedColor(Theme.key_windowBackgroundWhite);
        accent = getThemedColor(Theme.key_featuredStickers_addButton);
        onAccent = AndroidUtilities.computePerceivedBrightness(accent) > 0.62f ? 0xff111114 : 0xffffffff;
        dividerColor = getThemedColor(Theme.key_divider);

        PrizmaGlassFrame frame = new PrizmaGlassFrame(context);
        frame.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        frame.setBaseColor(getThemedColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), PrizmaGlassBar.height() + AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(120));
        scroll.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        frame.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        PrizmaGlassBar bar = new PrizmaGlassBar(context, frame, section == HOME ? "Libre" : NAMES[section], this::finishFragment);
        bar.follow(scroll);
        fragmentView = frame;

        buildEntries(context);
        if (section == HOME) {
            buildHome(context);
        } else {
            buildSection(context);
        }
        return fragmentView;
    }

    @Override
    public boolean isLightStatusBar() {
        return PrizmaGlassBar.lightStatusBar(getThemedColor(Theme.key_windowBackgroundGray));
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getParentActivity();
        if (resumedOnce && activity != null && content != null && section == HOME && homeBody != null) {
            homeBody.removeAllViews();
            buildEntries(activity);
            fillHome(activity);
        }
        resumedOnce = true;
    }

    private void buildHome(Context context) {
        LinearLayout search = new LinearLayout(context);
        search.setOrientation(LinearLayout.HORIZONTAL);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(PrizmaShape.fill(cardColor, AndroidUtilities.dp(24)));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.msg_search);
        icon.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        search.addView(icon, LayoutHelper.createLinear(40, 48, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint("Найти настройку");
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(textColor);
        field.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteHintText));
        field.setCursorColor(accent);
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setPadding(0, 0, AndroidUtilities.dp(14), 0);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.addView(field, LayoutHelper.createLinear(0, 48, 1f, Gravity.CENTER_VERTICAL));
        content.addView(search, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 12));

        homeBody = new LinearLayout(context);
        homeBody.setOrientation(LinearLayout.VERTICAL);
        content.addView(homeBody, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        results = new LinearLayout(context);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setVisibility(View.GONE);
        content.addView(results, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fillHome(context);

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                search(context, s.toString().trim().toLowerCase(Locale.getDefault()));
            }
        });
    }

    private void fillHome(Context context) {
        if (context == null) return;
        homeBody.addView(ghostCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));
        LinearLayout row = null;
        for (int i = 0; i < NAMES.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                homeBody.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
            }
            row.addView(tile(context, i, i), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, i % 2 == 0 ? 0 : 5, 0, i % 2 == 0 ? 5 : 0, 0));
        }
        TextView version = new TextView(context);
        version.setText("Libre " + BuildVars.BUILD_VERSION_STRING);
        version.setTextColor(grayColor);
        version.setTextSize(13);
        version.setGravity(Gravity.CENTER);
        version.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
        version.setOnClickListener(v -> {
            Activity a = getParentActivity();
            if (a != null) PrizmaUpdater.checkNow(a);
        });
        homeBody.addView(version, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private boolean ghostOn() {
        return PrizmaConfig.hideOnline() && PrizmaConfig.hideTyping() && PrizmaConfig.invisibleRead() && PrizmaConfig.stealthStories();
    }

    private View ghostCard(Context context) {
        PrizmaPalette tone = PrizmaPalette.of(SEEDS[GHOST]);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{tone.container, tone.surface});
        float big = AndroidUtilities.dp(34), small = big * PrizmaShape.SMALL;
        bg.setCornerRadii(new float[]{big, big, small, small, big, big, small, small});
        card.setBackground(bg);
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(context);
        title.setText("Невидимка");
        title.setTextColor(tone.onContainer);
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        texts.addView(title);
        TextView sub = new TextView(context);
        sub.setText("Одним нажатием: без онлайна, «печатает», прочтений и следов в сторис");
        sub.setTextColor((tone.onContainer & 0x00ffffff) | 0xb3000000);
        sub.setTextSize(13);
        texts.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        card.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        ghostSwitch = new PrizmaSwitch(context);
        ghostSwitch.setColors(tone.accent, tone.onAccent);
        ghostSwitch.setChecked(ghostOn(), false);
        card.addView(ghostSwitch, LayoutHelper.createLinear(52, 32, Gravity.CENTER_VERTICAL));
        card.setOnClickListener(v -> {
            boolean next = !ghostSwitch.isChecked();
            ghostSwitch.setChecked(next, true);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            PrizmaConfig.setHideOnline(next);
            PrizmaConfig.setHideTyping(next);
            PrizmaConfig.setInvisibleRead(next);
            PrizmaConfig.setStealthStories(next);
            if (next && PrizmaConfig.alwaysOnline()) {
                PrizmaConfig.setAlwaysOnline(false);
                PrizmaOnline.stop();
            }
        });
        return card;
    }

    private String status(int index) {
        int total = 0, on = 0;
        for (Entry e : entries) {
            if (e.section == index && e.flag != null) {
                total++;
                if (e.flag.get()) on++;
            }
        }
        return total >= 3 && on > 0 ? "Включено " + on + " из " + total : BLURBS[index];
    }

    private View tile(Context context, int index, int position) {
        PrizmaPalette tone = PrizmaPalette.of(SEEDS[index]);
        LinearLayout t = new LinearLayout(context);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14));
        t.setBackground(PrizmaShape.fill(cardColor, AndroidUtilities.dp(32)));
        ImageView icon = new ImageView(context);
        icon.setImageResource(ICONS[index]);
        icon.setColorFilter(new PorterDuffColorFilter(tone.onContainer, PorterDuff.Mode.SRC_IN));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        icon.setBackground(PrizmaShape.fill(tone.container, AndroidUtilities.dp(18)));
        t.addView(icon, LayoutHelper.createLinear(44, 44));
        TextView name = new TextView(context);
        name.setText(NAMES[index]);
        name.setTextColor(textColor);
        name.setTextSize(16);
        name.setTypeface(AndroidUtilities.bold());
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        TextView sub = new TextView(context);
        sub.setText(status(index));
        sub.setTextColor(grayColor);
        sub.setTextSize(12.5f);
        sub.setMinLines(2);
        sub.setMaxLines(2);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        t.setOnClickListener(v -> presentFragment(new PrizmaSettingsActivity(index)));
        PrizmaGlass.pressable(t);
        t.setAlpha(0f);
        t.setTranslationY(AndroidUtilities.dp(14));
        t.animate().alpha(1f).translationY(0f).setStartDelay(30L * position).setDuration(300)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        return t;
    }

    private void buildSection(Context context) {
        ArrayList<Entry> list = new ArrayList<>();
        for (Entry e : entries) {
            if (e.section == section) list.add(e);
        }
        content.addView(card(context, list, false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        String hint = hintFor(section);
        if (hint != null) {
            TextView h = new TextView(context);
            h.setText(hint);
            h.setTextColor(grayColor);
            h.setTextSize(12.5f);
            h.setLineSpacing(AndroidUtilities.dp(1), 1f);
            h.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), 0);
            content.addView(h);
        }
    }

    private String hintFor(int s) {
        if (s == GHOST) return "Скрываете «онлайн» — вы тоже не видите, когда другие в сети. Так работает Telegram.";
        if (s == SOCIAL) return "С сервером Libre другие пользователи Libre видят ваш баннер, стену, клан, NFT и открытый плейлист. Переписка на сервер не попадает.";
        if (s == MUSIC) return "Музыкальный статус выдаёт, что вы сейчас в телефоне, даже при скрытом «онлайн».";
        return null;
    }

    private void search(Context context, String query) {
        results.removeAllViews();
        if (query.isEmpty()) {
            results.setVisibility(View.GONE);
            homeBody.setVisibility(View.VISIBLE);
            return;
        }
        homeBody.setVisibility(View.GONE);
        results.setVisibility(View.VISIBLE);
        ArrayList<Entry> found = new ArrayList<>();
        for (Entry e : entries) {
            String t = e.title == null ? "" : e.title.toLowerCase(Locale.getDefault());
            String d = e.desc == null ? "" : e.desc.toLowerCase(Locale.getDefault());
            if (t.contains(query) || d.contains(query)) found.add(e);
        }
        if (found.isEmpty()) {
            TextView none = new TextView(context);
            none.setText("Такой настройки нет. Попробуйте другое слово.");
            none.setTextColor(grayColor);
            none.setTextSize(14);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, AndroidUtilities.dp(30), 0, 0);
            results.addView(none);
            return;
        }
        results.addView(card(context, found, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private View card(Context context, ArrayList<Entry> list, boolean withSection) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(PrizmaShape.fill(cardColor, AndroidUtilities.dp(32)));
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                View line = new View(context);
                line.setBackgroundColor(dividerColor);
                card.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 18, 0, 0, 0));
            }
            Entry e = list.get(i);
            card.addView(e.custom != null ? e.custom : row(context, e, withSection));
        }
        return card;
    }

    private View row(Context context, Entry e, boolean withSection) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 2));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(e.title);
        n.setTextColor(textColor);
        n.setTextSize(16);
        texts.addView(n);
        String d = withSection ? NAMES[e.section] + " · " + e.desc : e.desc;
        if (!TextUtils.isEmpty(d)) {
            TextView s = new TextView(context);
            s.setText(d);
            s.setTextColor(grayColor);
            s.setTextSize(13);
            s.setLineSpacing(AndroidUtilities.dp(1), 1f);
            texts.addView(s, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        if (e.flag != null) {
            PrizmaSwitch sw = new PrizmaSwitch(context);
            sw.setColors(accent, onAccent);
            sw.setOffColors(Theme.multAlpha(textColor, 0.08f), Theme.multAlpha(textColor, 0.34f), Theme.multAlpha(textColor, 0.5f));
            sw.setChecked(e.flag.get(), false);
            row.addView(sw, LayoutHelper.createLinear(50, 30, Gravity.CENTER_VERTICAL));
            row.setOnClickListener(v -> {
                boolean next = !sw.isChecked();
                sw.setChecked(next, true);
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                e.apply.set(next);
            });
        } else {
            ImageView arrow = new ImageView(context);
            arrow.setImageResource(R.drawable.msg_arrowright);
            arrow.setColorFilter(new PorterDuffColorFilter(grayColor, PorterDuff.Mode.SRC_IN));
            arrow.setScaleType(ImageView.ScaleType.CENTER);
            row.addView(arrow, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL));
            row.setOnClickListener(v -> {
                if (e.action != null) e.action.run();
            });
        }
        return row;
    }

    private void toggle(int s, String title, String desc, Flag flag, Apply apply) {
        Entry e = new Entry();
        e.section = s;
        e.title = title;
        e.desc = desc;
        e.flag = flag;
        e.apply = apply;
        entries.add(e);
    }

    private void action(int s, String title, String desc, Runnable action) {
        Entry e = new Entry();
        e.section = s;
        e.title = title;
        e.desc = desc;
        e.action = action;
        entries.add(e);
    }

    private interface IntSetter {
        void set(int value);
    }

    private void choose(Context ctx, String title, String[] names, int[] values, int current, IntSetter setter) {
        org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(ctx);
        b.setTitle(title);
        CharSequence[] items = new CharSequence[names.length];
        for (int i = 0; i < names.length; i++) items[i] = (values[i] == current ? "• " : "") + names[i];
        b.setItems(items, (d, which) -> {
            setter.set(values[which]);
            NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        });
        showDialog(b.create());
    }

    private void buildEntries(Context ctx) {
        entries.clear();
        final Activity activity = AndroidUtilities.findActivity(ctx);
        final int acc = UserConfig.selectedAccount;

        toggle(GHOST, "Скрывать «онлайн»", "Никто не увидит, что вы в сети", PrizmaConfig::hideOnline, PrizmaConfig::setHideOnline);
        toggle(GHOST, "Скрывать «печатает»", "Не показывать, что вы набираете текст", PrizmaConfig::hideTyping, PrizmaConfig::setHideTyping);
        toggle(GHOST, "Всегда в сети", "Поддерживать статус «онлайн». Не вместе со скрытием онлайна", PrizmaConfig::alwaysOnline, v -> {
            PrizmaConfig.setAlwaysOnline(v);
            if (v) PrizmaOnline.start();
            else PrizmaOnline.stop();
        });
        toggle(GHOST, "Невидимое прочтение", "В личных чатах не отправлять «прочитано»", PrizmaConfig::invisibleRead, PrizmaConfig::setInvisibleRead);
        toggle(GHOST, "Невидимый просмотр сторис", "Смотреть истории, не отмечаясь в просмотрах", PrizmaConfig::stealthStories, PrizmaConfig::setStealthStories);
        toggle(GHOST, "Подтверждать звонки", "Сначала кнопка звонка, потом «Позвонить», чтобы не звонить случайно", PrizmaConfig::confirmCalls, PrizmaConfig::setConfirmCalls);
        toggle(GHOST, "Скрывать IP в звонках", "Звонки через сервер: собеседник не видит ваш IP", PrizmaConfig::hideCallIp, PrizmaConfig::setHideCallIp);
        toggle(GHOST, "Антиудаление", "Удалённые сообщения не исчезают из открытого чата", PrizmaConfig::antiDelete, PrizmaConfig::setAntiDelete);
        toggle(GHOST, "Стирать GPS из фото", "Убирать геолокацию и метаданные из отправляемых изображений", PrizmaConfig::stripExif, PrizmaConfig::setStripExif);
        toggle(GHOST, "Анонимная пересылка", "Пересылать без подписи «переслано от»", PrizmaConfig::anonForward, PrizmaConfig::setAnonForward);
        toggle(GHOST, "Режим стримера", "Номер, юзернейм, ID и тексты в списке чатов прячутся под пылью", PrizmaConfig::streamer, v -> {
            PrizmaConfig.setStreamer(v);
            NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
        });
        toggle(GHOST, "Чистить ссылки", "Убирать utm, fbclid, si и другие метки слежки перед отправкой", PrizmaConfig::cleanLinks, PrizmaConfig::setCleanLinks);

        toggle(GUARD, "Не беспокоить", "Глушить все уведомления Telegram на этом телефоне", PrizmaConfig::dnd, PrizmaConfig::setDnd);
        toggle(GUARD, "Сохранять из защищённых", "Скачивание, пересылка и копирование везде", PrizmaConfig::allowSaveRestricted, PrizmaConfig::setAllowSaveRestricted);
        toggle(GUARD, "Анонимный номер +888", "Показывать ваш номер как анонимный (только у вас)", PrizmaConfig::fakePhone, v -> {
            PrizmaConfig.setFakePhone(v);
            NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_NAME);
        });
        action(GUARD, "Выбрать +888 номер", "Крутите, пока не понравится", () -> PrizmaStealthSheet.showPhoneRoller(ctx));
        action(GUARD, "Пароль на архив", PrizmaArchiveLock.isLocked() ? "Включён. Нажмите, чтобы отключить" : "Открывать архив только по PIN", () -> {
            if (PrizmaArchiveLock.isLocked()) {
                PrizmaArchiveLock.verify(ctx, () -> {
                    PrizmaArchiveLock.disable();
                    Toast.makeText(ctx, "Пароль на архив снят", Toast.LENGTH_SHORT).show();
                });
            } else {
                PrizmaArchiveLock.setupPin(ctx, () -> Toast.makeText(ctx, "Код на архив установлен", Toast.LENGTH_SHORT).show());
            }
        });
        action(GUARD, "Блокировка приложения", "Пароль и отпечаток на вход, автоблокировка", () -> presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_MANAGE_CODE_SETTINGS)));
        action(GUARD, "Обмен контактом", "Поднесите телефоны друг к другу: одноразовый код по NFC", () -> PrizmaNfcExchange.show(ctx));
        action(GUARD, "Стереть следы", "Поиск, недавние, буфер и кэш одной кнопкой", () -> PrizmaTraces.show(ctx, activity != null ? activity.getWindow().getDecorView() : null));
        toggle(GUARD, "Хранить удалённые и правки", "Своя база на телефоне, переживает очистку кэша. Меню чата → «Удалённые и правки»", PrizmaConfig::keepHistory, PrizmaConfig::setKeepHistory);
        toggle(GUARD, "Не терять чаты после бана", "Если вас выгнали, чат и его сообщения остаются для чтения", PrizmaConfig::keepBanned, PrizmaConfig::setKeepBanned);

        action(LOOK, "Иконка приложения", "Сменить иконку Libre", () -> PrizmaStealthSheet.showIconPicker(ctx));
        toggle(LOOK, "Лента вместо контактов", "Вкладка с постами всех каналов. Контакты по долгому нажатию. Применится после перезапуска", PrizmaConfig::feedTab, PrizmaConfig::setFeedTab);
        toggle(LOOK, "Прятать панель при прокрутке", "Нижняя панель уезжает, пока листаете чаты вниз", PrizmaConfig::dockAutoHide, PrizmaConfig::setDockAutoHide);
        toggle(LOOK, "Виджеты над чатами", "Курсы и погода над списком чатов. Применится после перезапуска", PrizmaConfig::searchWidgets, PrizmaConfig::setSearchWidgets);
        toggle(LOOK, "Чаты без звука ниже", "Заглушённые чаты опускаются под остальные", PrizmaConfig::mutedBottom, v -> {
            PrizmaConfig.setMutedBottom(v);
            MessagesController.getInstance(acc).sortDialogs(null);
            NotificationCenter.getInstance(acc).postNotificationName(NotificationCenter.dialogsNeedReload);
        });
        toggle(LOOK, "Сообщения без хвостика", "Пузыри без уголка у последнего сообщения", PrizmaConfig::noTail, PrizmaConfig::setNoTail);
        action(LOOK, "Скругление аватаров", "Круг, лист или квадрат", () -> choose(ctx, "Скругление аватаров", new String[]{"Круг", "Сильное", "Среднее", "Лёгкое", "Квадрат"}, new int[]{100, 70, 45, 25, 0}, PrizmaConfig.avatarCorner(), PrizmaConfig::setAvatarCorner));
        toggle(LOOK, "Компактная панель вкладок", "Подпись только у открытой вкладки. Применится после перезапуска", PrizmaConfig::dockTabs, PrizmaConfig::setDockTabs);
        action(LOOK, "Размер эмодзи", "Уменьшить большие эмодзи в сообщениях", () -> PrizmaStealthSheet.showEmojiSize(ctx));
        action(LOOK, "Форма пузырей", "Скругление пузырей сообщений", () -> PrizmaStealthSheet.showBubbleShape(ctx));
        toggle(LOOK, "Стеклянные пузыри", "Полупрозрачные пузыри: сквозь них видно обои", PrizmaConfig::glassBubbles, PrizmaConfig::setGlassBubbles);
        action(LOOK, "Размер стикеров", "Уменьшить стикеры и анимированные эмодзи", () -> PrizmaStealthSheet.showStickerSize(ctx));
        toggle(LOOK, "Переливающийся профиль", "Анимированный градиент в шапке профиля", PrizmaConfig::iridescentProfile, PrizmaConfig::setIridescentProfile);
        toggle(LOOK, "Перелив на имени и аватаре", "Градиент на имени и обводка аватара", PrizmaConfig::iridescentName, PrizmaConfig::setIridescentName);
        final LinearLayout palette = PrizmaStealthSheet.paletteRow(ctx);
        palette.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(12));
        palette.setVisibility(PrizmaConfig.liveWallpaper() ? View.VISIBLE : View.GONE);
        toggle(LOOK, "Живые обои чата", "Переливающийся градиент на фоне переписки", PrizmaConfig::liveWallpaper, v -> {
            PrizmaConfig.setLiveWallpaper(v);
            palette.setVisibility(v ? View.VISIBLE : View.GONE);
        });
        Entry paletteEntry = new Entry();
        paletteEntry.section = LOOK;
        paletteEntry.title = "Палитра живых обоев";
        paletteEntry.custom = palette;
        entries.add(paletteEntry);
        action(LOOK, "Свои обои чата", "Своё изображение или видео фоном чата", () -> {
            if (activity != null) PrizmaChatWallpaper.pick(activity);
        });
        if (PrizmaChatWallpaper.has()) {
            action(LOOK, "Убрать свои обои", "Вернуть обычный фон чата", PrizmaChatWallpaper::clear);
        }
        action(LOOK, "Баннер профиля", "Картинка или видео из галереи", () -> {
            if (activity != null) PrizmaBanner.pick(activity);
        });
        if (PrizmaBanner.hasBanner()) {
            action(LOOK, "Убрать баннер", "Вернуть обычную шапку профиля", PrizmaBanner::clear);
        }
        action(LOOK, "Витрина подарков", "Добавить любые подарки на свой профиль", () -> PrizmaGiftsSheet.show(ctx, null));
        action(LOOK, "Свой шрифт", "Любой шрифт .ttf или .otf на весь интерфейс", () -> {
            if (activity != null) PrizmaFonts.pick(activity);
        });
        if (PrizmaFonts.hasCustom()) {
            action(LOOK, "Сбросить шрифт", "Вернуть стандартный шрифт", () -> {
                PrizmaFonts.clear();
                Toast.makeText(ctx, "Шрифт сброшен. Перезапустите приложение", Toast.LENGTH_LONG).show();
            });
        }

        toggle(SOCIAL, "Блок Libre в профилях", "Достижения, «в Libre с», клан и стена", PrizmaSocial::cardEnabled, PrizmaSocial::setCardEnabled);
        action(SOCIAL, "Моя стена", "Записи, комментарии, кто может писать", () -> presentFragment(new PrizmaWallActivity(UserConfig.getInstance(acc).getClientUserId(), 0)));
        action(SOCIAL, "Мой клан", PrizmaClans.mine(acc) != null ? "Участники и приглашения" : "Создать свой клан с тегом", () -> PrizmaClanSheet.show(this, acc, UserConfig.getInstance(acc).getClientUserId(), null));
        toggle(SOCIAL, "Не отмечаться в просмотрах", "Ваши визиты не попадут в счётчик чужих профилей", PrizmaSocial::noViewMark, PrizmaSocial::setNoViewMark);
        action(SOCIAL, "Облачная копия Libre", "Настройки, темы и плейлисты вернутся на новом телефоне", () -> PrizmaBackup.show(ctx));
        action(SOCIAL, "Что сервер знает обо мне", "Всё, что хранится о вас, и выгрузка", () -> PrizmaBackup.showWhatServerKnows(ctx));
        toggle(SOCIAL, "Показывать анонимный номер", "Номер +888 из Fragment видно в профиле в Libre. Обычный номер не показывается никогда", PrizmaConfig::showAnonNumber, on -> {
            PrizmaConfig.setShowAnonNumber(on);
            PrizmaPresence.syncAnonNumber(acc);
        });
        toggle(SOCIAL, "Уведомления из MAX", "Сообщения из всех чатов MAX приходят от бота Libre в Telegram", PrizmaConfig::maxForward, on -> {
            PrizmaConfig.setMaxForward(on);
            if (on) PrizmaMaxSetup.start(ctx);
        });
        action(SOCIAL, "Сервер Libre", PrizmaCloudSetup.status(), () -> PrizmaCloudSetup.show(ctx));

        action(MUSIC, "Моя музыка", "Плейлисты и любимые треки", () -> PrizmaPlaylistSheet.show(ctx));
        action(MUSIC, "Настройки плеера", "Цвета, клипы, тексты, эффекты под бит", () -> {
            if (fragmentView instanceof ViewGroup) {
                PrizmaPlayerSettings.show((ViewGroup) fragmentView, PrizmaPalette.fallback(), () -> {
                });
            }
        });
        toggle(MUSIC, "Рябь на весь фон", "Волны под басс расходятся по всему экрану плеера", PrizmaConfig::playerRipple, PrizmaConfig::setPlayerRipple);
        toggle(MUSIC, "Капли под мелодию", "Высокие ноты падают на фон плеера мелкими каплями", PrizmaConfig::playerDrops, PrizmaConfig::setPlayerDrops);
        toggle(MUSIC, "Удар цветом", "Фон на миг расслаивается на цвета на самых сильных ударах", PrizmaConfig::playerChroma, PrizmaConfig::setPlayerChroma);
        toggle(MUSIC, "Пульс краёв", "Края экрана плеера подсвечиваются в такт басу", PrizmaConfig::playerEdges, PrizmaConfig::setPlayerEdges);
        action(MUSIC, "Плавный переход", PrizmaConfig.fadeSeconds() == 0 ? "Выключен" : "Затухание " + PrizmaConfig.fadeSeconds() + " с в начале и конце трека", () -> choose(ctx, "Плавный переход", new String[]{"Выключен", "2 секунды", "4 секунды", "6 секунд"}, new int[]{0, 2, 4, 6}, PrizmaConfig.fadeSeconds(), PrizmaConfig::setFadeSeconds));
        action(MUSIC, "Эквалайзер", "Пресеты, свои настройки, бас и объём", () -> org.telegram.ui.Components.PrizmaEqualizerSheet.show(ctx));
        action(MUSIC, "Звук уведомлений", "Свой звук на сообщения", () -> {
            if (activity != null) PrizmaSounds.pick(activity);
        });
        final PrizmaTogether together = PrizmaTogether.get();
        action(MUSIC, together.isHosting() ? "Завершить эфир" : together.isFollowing() ? "Выйти из эфира" : "Слушать вместе",
                together.isHosting() ? "Вы ведёте эфир" : together.isFollowing() ? "Вы слушаете чужой эфир" : "Друг в Libre слышит то же, что и вы, секунда в секунду", () -> {
                    if (together.isHosting()) {
                        together.stopHosting();
                    } else if (together.isFollowing()) {
                        together.leave();
                    } else {
                        PrizmaTogether.pickChatAndStart(ctx);
                    }
                });
        toggle(MUSIC, "Музыкальный статус", "В «О себе» видно исполнителя и трек, потом вернётся ваш текст", PrizmaConfig::musicStatus, v -> {
            PrizmaConfig.setMusicStatus(v);
            PrizmaMusicStatus.onToggle();
        });

        toggle(OTHER, "Перевод без Premium", "Перевод всего чата через Google или Яндекс, разметка сохраняется", PrizmaConfig::freeTranslate, PrizmaConfig::setFreeTranslate);
        action(OTHER, "Переводчик", PrizmaConfig.translateEngine() == 1 ? "Яндекс" : "Google", () -> choose(ctx, "Переводчик", new String[]{"Google", "Яндекс"}, new int[]{0, 1}, PrizmaConfig.translateEngine(), PrizmaConfig::setTranslateEngine));
        toggle(OTHER, "Блокировка рекламы в браузере", "Встроенный браузер не грузит рекламу и счётчики", PrizmaConfig::adBlock, PrizmaConfig::setAdBlock);
        toggle(OTHER, "ИИ на телефоне", "Gemini Nano: пересказ чата и улучшение черновика без интернета", PrizmaConfig::aiEnabled, PrizmaConfig::setAiEnabled);
        toggle(OTHER, "Кружки в 60 кадров", "Плавнее, если камера телефона это умеет", PrizmaConfig::round60, PrizmaConfig::setRound60);
        toggle(OTHER, "Шкала зума в кружках", "Под кругом: нажмите 1×, 2× или ведите пальцем по шкале", PrizmaConfig::roundZoomSlider, PrizmaConfig::setRoundZoomSlider);
        toggle(OTHER, "Кружки в высоком качестве", "Чётче картинка и звук. Файл кружка получается больше", PrizmaConfig::roundHd, PrizmaConfig::setRoundHd);
        toggle(OTHER, "Тишина при записи", "Глушить звуки уведомлений, пока пишете голосовое или кружок", PrizmaConfig::muteSystemOnRecord, PrizmaConfig::setMuteSystemOnRecord);
        action(OTHER, "Пропорции фото", "Для камеры в чате", () -> choose(ctx, "Пропорции фото", new String[]{"Как снято", "Квадрат 1:1", "4:3", "16:9"}, new int[]{0, 1, 2, 3}, PrizmaConfig.photoAspect(), PrizmaConfig::setPhotoAspect));
        toggle(OTHER, "Скрывать рекламу", "Без спонсорских сообщений в каналах и ботах", PrizmaConfig::hideAds, PrizmaConfig::setHideAds);
        toggle(OTHER, "Перевод по тапу", "Пункт «Перевести» всегда в меню сообщения", PrizmaConfig::tapTranslate, PrizmaConfig::setTapTranslate);
        toggle(OTHER, "Подсказки в поле ввода", "Считать примеры, валюты, единицы и часовые пояса прямо при наборе", PrizmaConfig::inputCalc, PrizmaConfig::setInputCalc);
        toggle(OTHER, "Встроенный просмотр PDF", "Открывать PDF прямо в Libre, без других приложений", PrizmaConfig::builtInPdf, PrizmaConfig::setBuiltInPdf);
        toggle(OTHER, "Лёгкий режим", "Отключить тяжёлые анимации и размытие", PrizmaConfig::liteMode, v -> {
            if (v) {
                PrizmaConfig.setLitePrevFlags(LiteMode.getValue(true));
                LiteMode.setAllFlags(LiteMode.PRESET_POWER_SAVER);
                PrizmaConfig.setLiteMode(true);
            } else {
                int prev = PrizmaConfig.litePrevFlags();
                LiteMode.setAllFlags(prev >= 0 ? prev : LiteMode.PRESET_HIGH);
                PrizmaConfig.setLiteMode(false);
            }
            PrizmaWallpaper.invalidate();
        });
        action(OTHER, "Крипто-кошелёк @walt", "Открыть кошелёк", () -> MessagesController.getInstance(acc).openByUserName("walt", this, 0));
        action(OTHER, "Проверить обновления", "Libre " + BuildVars.BUILD_VERSION_STRING, () -> {
            if (activity != null) PrizmaUpdater.checkNow(activity);
        });
    }

}
