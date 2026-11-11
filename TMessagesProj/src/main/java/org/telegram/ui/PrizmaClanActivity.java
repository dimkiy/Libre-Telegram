package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PrizmaClans;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PrizmaClanSheet;
import org.telegram.ui.Components.PrizmaFeedKit;
import org.telegram.ui.Components.PrizmaGlassBar;
import org.telegram.ui.Components.PrizmaGlassFrame;
import org.telegram.ui.Components.PrizmaMedal;
import org.telegram.ui.Components.PrizmaShape;
import org.telegram.ui.Components.RadialProgressView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

public class PrizmaClanActivity extends BaseFragment {
    private static final int LEVEL_STEP = 1000;
    private static final int MAX_LEVEL = 10;
    private static final int MAX_MEMBERS = 200;

    private final long requestedId;
    private final boolean topMode;
    private PrizmaSocial.Clan clan;
    private ArrayList<PrizmaSocial.Clan> top;
    private String problem;
    private boolean loaded;
    private int request;

    private Context ctx;
    private LinearLayout column;
    private int ink, gray, card, accent, onAccent, danger;

    public PrizmaClanActivity(long clanId) {
        requestedId = clanId;
        topMode = false;
    }

    private PrizmaClanActivity() {
        requestedId = 0;
        topMode = true;
    }

    public static PrizmaClanActivity top() {
        return new PrizmaClanActivity();
    }

    @Override
    public boolean isLightStatusBar() {
        return PrizmaGlassBar.lightStatusBar(Theme.getColor(Theme.key_windowBackgroundGray));
    }

    @Override
    public View createView(Context context) {
        ctx = context;
        actionBar.setAddToContainer(false);
        ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        card = Theme.getColor(Theme.key_windowBackgroundWhite);
        accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        onAccent = Theme.getColor(Theme.key_featuredStickers_buttonText);
        danger = Theme.getColor(Theme.key_text_RedBold);

        PrizmaGlassFrame root = new PrizmaGlassFrame(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        root.setBaseColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(AndroidUtilities.dp(14), PrizmaGlassBar.height() + AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(40));
        scroll.addView(column, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        PrizmaGlassBar bar = new PrizmaGlassBar(context, root, topMode ? "Рейтинг кланов" : "Клан", this::finishFragment);
        bar.follow(scroll);
        fragmentView = root;
        render();
        load();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (loaded) load();
    }

    private boolean alive() {
        return fragmentView != null && getParentActivity() != null && !isFinishing();
    }

    private void load() {
        final int id = ++request;
        if (topMode) {
            PrizmaClans.top(currentAccount, (list, err) -> {
                if (id != request || !alive()) return;
                top = list;
                problem = list == null ? err : null;
                loaded = true;
                render();
            });
            return;
        }
        if (requestedId != 0) {
            PrizmaClans.load(currentAccount, requestedId, (c, err) -> show(id, c, err));
            return;
        }
        PrizmaClans.refreshMine(currentAccount, (String err) -> {
            if (id != request || !alive()) return;
            PrizmaSocial.Clan mine = PrizmaClans.mine(currentAccount);
            if (mine == null) {
                clan = null;
                problem = err;
                loaded = true;
                render();
                if (err == null) loadTopPreview(id);
            } else if (mine.full) {
                show(id, mine, null);
            } else {
                PrizmaClans.load(currentAccount, mine.id, (c, e) -> show(id, c != null ? c : mine, null));
            }
        });
    }

    private void loadTopPreview(int id) {
        PrizmaClans.top(currentAccount, (list, err) -> {
            if (id != request || !alive() || list == null) return;
            top = list;
            render();
        });
    }

    private void show(int id, PrizmaSocial.Clan c, String err) {
        if (id != request || !alive()) return;
        if (c != null) {
            clan = c;
            problem = null;
        } else if (clan == null) {
            problem = err;
        }
        loaded = true;
        render();
    }

    private void reload() {
        if (alive()) load();
    }

    private void toast(String text) {
        if (text != null && getParentActivity() != null) Toast.makeText(getParentActivity(), text, Toast.LENGTH_LONG).show();
    }

    private void render() {
        if (column == null) return;
        column.removeAllViews();
        if (!loaded) {
            RadialProgressView spinner = new RadialProgressView(ctx);
            spinner.setSize(AndroidUtilities.dp(28));
            spinner.setProgressColor(accent);
            column.addView(spinner, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL, 0, 120, 0, 0));
        } else if (topMode) {
            renderTop();
        } else if (clan != null) {
            renderClan();
        } else if (problem != null) {
            column.addView(note(problem), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 80, 16, 16));
            TextView again = pill("Повторить", accent, onAccent);
            again.setOnClickListener(v -> {
                loaded = false;
                render();
                load();
            });
            column.addView(again, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 46, Gravity.CENTER_HORIZONTAL));
        } else {
            renderNoClan();
        }
    }

    private LinearLayout box() {
        LinearLayout b = new LinearLayout(ctx);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(PrizmaShape.fill(card, AndroidUtilities.dp(24)));
        return b;
    }

    private TextView text(CharSequence value, float size, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    private TextView note(String value) {
        TextView t = text(value, 15, gray, false);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(AndroidUtilities.dp(2), 1f);
        return t;
    }

    private TextView section(String value) {
        TextView t = text(value, 14, gray, true);
        t.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        return t;
    }

    private TextView pill(String value, int bg, int fg) {
        TextView t = text(value, 15, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), 0);
        t.setBackground(PrizmaShape.fill(bg, AndroidUtilities.dp(22)));
        PrizmaFeedKit.press(t, 0.95f);
        return t;
    }

    private View tile(int icon, String title, String subtitle, int tint, Runnable onClick) {
        LinearLayout t = box();
        t.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14), AndroidUtilities.dp(14));
        ImageView i = new ImageView(ctx);
        i.setImageResource(icon);
        i.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        i.setScaleType(ImageView.ScaleType.CENTER);
        i.setBackground(PrizmaShape.fill(Theme.multAlpha(tint, 0.14f), AndroidUtilities.dp(14)));
        t.addView(i, LayoutHelper.createLinear(40, 40));
        t.addView(text(title, 15.5f, ink, true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        TextView s = text(subtitle, 13, gray, false);
        s.setMaxLines(2);
        t.addView(s, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        t.setOnClickListener(v -> onClick.run());
        PrizmaFeedKit.press(t, 0.96f);
        return t;
    }

    private void addPair(View left, View right) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(left, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 0, 0, 5, 0));
        if (right != null) {
            row.addView(right, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, 5, 0, 0, 0));
        } else {
            row.addView(new View(ctx), LayoutHelper.createLinear(0, 1, 1f, 5, 0, 0, 0));
        }
        column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
    }

    private static String people(int n) {
        return n + " " + PrizmaSocial.plural(n, "участник", "участника", "участников");
    }

    private static String points(long n) {
        return LocaleController.formatShortNumber((int) Math.min(Integer.MAX_VALUE, Math.max(0, n)), null);
    }

    private void renderNoClan() {
        LinearLayout hero = box();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(22));
        PrizmaMedal crest = new PrizmaMedal(ctx);
        crest.setText("?", accent, "клан");
        hero.addView(crest, LayoutHelper.createLinear(170, 170));
        TextView title = text("У вас пока нет клана", 21, ink, true);
        title.setGravity(Gravity.CENTER);
        hero.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        hero.addView(note("Клан — это общий тег в профиле, своя стена и место в рейтинге. Создайте свой или вступите по приглашению."),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        column.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        addPair(tile(R.drawable.msg_add, "Создать клан", "Название, тег и цвет", accent,
                        () -> PrizmaClanSheet.showCreate(this, currentAccount, this::reload)),
                tile(R.drawable.msg_link, "Вступить", "По ссылке или коду", accent,
                        () -> PrizmaClanSheet.askCode(this, currentAccount, this::reload)));

        if (top != null && !top.isEmpty()) {
            column.addView(section("Лучшие кланы"));
            LinearLayout list = box();
            list.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
            for (int i = 0; i < Math.min(5, top.size()); i++) list.addView(rankRow(top.get(i), i + 1));
            column.addView(list);
            TextView all = pill("Весь рейтинг", Theme.multAlpha(ink, 0.07f), ink);
            all.setOnClickListener(v -> presentFragment(top()));
            column.addView(all, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 10, 0, 0));
        }
    }

    private void renderClan() {
        final PrizmaSocial.Clan c = clan;
        final int tone = c.color | 0xff000000;
        LinearLayout hero = box();
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(6), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        PrizmaMedal crest = new PrizmaMedal(ctx);
        String since = c.createdAt > 0 ? new SimpleDateFormat("LLL yyyy", LocaleController.getInstance().getCurrentLocale()).format(new Date(c.createdAt)) : "";
        crest.setText(c.tag, tone, since);
        hero.addView(crest, LayoutHelper.createLinear(186, 186));
        TextView name = text(c.name, 23, ink, true);
        name.setGravity(Gravity.CENTER);
        hero.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        TextView sub = text("[" + c.tag + "]" + (c.mine ? " · вы " + PrizmaSocial.roleName(c.myRole).toLowerCase() : ""), 14.5f, gray, false);
        sub.setGravity(Gravity.CENTER);
        hero.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));
        column.addView(hero, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        LinearLayout level = box();
        level.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        LinearLayout levelHead = new LinearLayout(ctx);
        levelHead.setOrientation(LinearLayout.HORIZONTAL);
        levelHead.setGravity(Gravity.CENTER_VERTICAL);
        levelHead.addView(text("Уровень " + c.level, 17, ink, true), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final boolean maxed = c.level >= MAX_LEVEL;
        final long within = c.rating % LEVEL_STEP;
        levelHead.addView(text(maxed ? "максимальный" : (LEVEL_STEP - within) + " до уровня " + (c.level + 1), 13.5f, gray, false));
        level.addView(levelHead);
        level.addView(bar(maxed ? 1f : within / (float) LEVEL_STEP, tone), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 6, 0, 10, 0, 0));
        TextView how = text("Очки: 100 за участника, 10 за запись на стене клана, 1 за каждый лайк", 12.5f, gray, false);
        level.addView(how, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        column.addView(level, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        addPair(stat(points(c.rating), "рейтинг", tone), stat(c.members + " из " + MAX_MEMBERS, "участников", ink));

        View wall = tile(R.drawable.msg_discuss, "Стена клана", "Записи и обсуждения участников", tone,
                () -> presentFragment(new PrizmaWallActivity(-c.id, 0)));
        View rating = tile(R.drawable.msg_stats, "Рейтинг", "Место среди всех кланов", tone, () -> presentFragment(top()));
        addPair(wall, rating);
        if (c.myRole >= PrizmaSocial.ROLE_OFFICER) {
            addPair(tile(R.drawable.msg_link, "Пригласить", "Отправить ссылку-приглашение", tone, () -> invite(c)), null);
        }

        column.addView(section("Участники · " + c.list.size()));
        LinearLayout members = box();
        members.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        ArrayList<PrizmaSocial.Member> sorted = new ArrayList<>(c.list);
        Collections.sort(sorted, (a, b) -> Integer.compare(b.roleId, a.roleId));
        for (PrizmaSocial.Member m : sorted) members.addView(memberRow(c, m, tone));
        if (sorted.isEmpty()) {
            members.addView(note("Список участников виден только членам клана"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 14, 16, 14));
        }
        column.addView(members);

        if (c.mine) {
            final boolean head = c.myRole == PrizmaSocial.ROLE_HEAD;
            TextView leave = pill("Выйти из клана", Theme.multAlpha(danger, 0.1f), danger);
            leave.setOnClickListener(v -> new AlertDialog.Builder(ctx, getResourceProvider())
                    .setTitle("Выйти из клана?")
                    .setMessage(head && c.members > 1 ? "Главой станет офицер или самый давний участник." : head ? "Вы последний участник — клан закроется." : "Вернуться можно будет по приглашению.")
                    .setPositiveButton("Выйти", (d, w) -> PrizmaClans.leave(currentAccount, e -> {
                        toast(e);
                        if (e == null) finishFragment();
                    }))
                    .setNegativeButton("Отмена", null)
                    .makeRed(AlertDialog.BUTTON_POSITIVE)
                    .show());
            column.addView(leave, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 18, 0, 0));
            if (head) {
                TextView disband = text("Распустить клан", 14.5f, danger, false);
                disband.setGravity(Gravity.CENTER);
                disband.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                disband.setOnClickListener(v -> new AlertDialog.Builder(ctx, getResourceProvider())
                        .setTitle("Распустить клан?")
                        .setMessage("Клан «" + c.name + "» исчезнет вместе с участниками, стеной и рейтингом.")
                        .setPositiveButton("Распустить", (d, w) -> PrizmaClans.disband(currentAccount, c.id, e -> {
                            toast(e);
                            if (e == null) finishFragment();
                        }))
                        .setNegativeButton("Отмена", null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .show());
                column.addView(disband, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            }
        }
    }

    private View stat(String value, String label, int tint) {
        LinearLayout s = box();
        s.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        s.addView(text(value, 20, tint, true));
        s.addView(text(label, 13, gray, false), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        return s;
    }

    private View bar(float part, int tint) {
        FrameLayout track = new FrameLayout(ctx);
        GradientDrawable back = new GradientDrawable();
        back.setCornerRadius(AndroidUtilities.dp(3));
        back.setColor(Theme.multAlpha(gray, 0.18f));
        track.setBackground(back);
        View fill = new View(ctx);
        GradientDrawable front = new GradientDrawable();
        front.setCornerRadius(AndroidUtilities.dp(3));
        front.setColor(tint);
        fill.setBackground(front);
        track.addView(fill, new FrameLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT));
        final float share = Math.max(0.03f, Math.min(1f, part));
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * share);
            if (fill.getLayoutParams().width != w) {
                fill.getLayoutParams().width = w;
                fill.requestLayout();
            }
        });
        return track;
    }

    private View memberRow(PrizmaSocial.Clan c, PrizmaSocial.Member m, int tone) {
        final long me = getUserConfig().getClientUserId();
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8));
        r.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        BackupImageView avatar = new BackupImageView(ctx);
        avatar.setRoundRadius(AndroidUtilities.dp(20));
        AvatarDrawable ad = new AvatarDrawable();
        final TLRPC.User u = getMessagesController().getUser(m.userId);
        if (u != null) {
            ad.setInfo(currentAccount, u);
            avatar.setForUserOrChat(u, ad);
        } else {
            ad.setInfo(m.userId, m.name, null);
            avatar.setImageDrawable(ad);
        }
        r.addView(avatar, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(m.userId == me ? m.name + " (вы)" : m.name, 15.5f, ink, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(n);
        names.addView(text(m.role, 13, m.roleId == PrizmaSocial.ROLE_HEAD ? tone : gray, false));
        r.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        boolean canManage = m.userId != me && (c.myRole == PrizmaSocial.ROLE_HEAD
                || c.myRole == PrizmaSocial.ROLE_OFFICER && m.roleId == PrizmaSocial.ROLE_MEMBER);
        if (canManage) {
            ImageView more = new ImageView(ctx);
            more.setImageResource(R.drawable.ic_ab_other);
            more.setColorFilter(new PorterDuffColorFilter(gray, PorterDuff.Mode.SRC_IN));
            more.setScaleType(ImageView.ScaleType.CENTER);
            more.setContentDescription("Роль или исключение");
            more.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1, AndroidUtilities.dp(20)));
            more.setOnClickListener(v -> PrizmaClanSheet.manageMember(this, currentAccount, c, m, this::reload));
            r.addView(more, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));
        } else {
            r.addView(new View(ctx), LayoutHelper.createLinear(10, 44));
        }
        r.setOnClickListener(v -> {
            if (u == null) return;
            Bundle args = new Bundle();
            args.putLong("user_id", m.userId);
            presentFragment(new ProfileActivity(args));
        });
        return r;
    }

    private void invite(PrizmaSocial.Clan c) {
        PrizmaClans.invite(currentAccount, c.id, (link, err) -> {
            if (link == null) {
                toast(err);
                return;
            }
            String message = "Вступай в мой клан «" + c.name + "» [" + c.tag + "] в Libre: " + link;
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, message);
                getParentActivity().startActivity(Intent.createChooser(send, "Приглашение в клан"));
            } catch (Throwable e) {
                AndroidUtilities.addToClipboard(message);
                toast("Ссылка-приглашение скопирована");
            }
        });
    }

    private void renderTop() {
        if (top == null) {
            column.addView(note(problem != null ? problem : "Не удалось загрузить рейтинг"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 80, 16, 16));
            return;
        }
        if (top.isEmpty()) {
            column.addView(note("Кланов пока нет — создайте первый"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 80, 16, 16));
            return;
        }
        column.addView(note("Очки: 100 за участника, 10 за запись на стене клана, 1 за каждый лайк"),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 10, 4, 10, 12));
        LinearLayout list = box();
        list.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
        for (int i = 0; i < top.size(); i++) list.addView(rankRow(top.get(i), i + 1));
        column.addView(list);
    }

    private View rankRow(PrizmaSocial.Clan c, int place) {
        final int tone = c.color | 0xff000000;
        PrizmaSocial.Clan mine = PrizmaClans.mine(currentAccount);
        final boolean own = mine != null && mine.id == c.id;
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(16), AndroidUtilities.dp(9));
        r.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        TextView pos = text(String.valueOf(place), 14, place <= 3 ? 0xffffffff : gray, true);
        pos.setGravity(Gravity.CENTER);
        if (place <= 3) pos.setBackground(PrizmaShape.fill(tone, AndroidUtilities.dp(11)));
        r.addView(pos, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        r.addView(PrizmaClanSheet.emblem(ctx, c.tag, c.color, 40), LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        LinearLayout names = new LinearLayout(ctx);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView n = text(own ? c.name + " · ваш" : c.name, 15.5f, ink, true);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(n);
        names.addView(text("уровень " + c.level + " · " + people(c.members), 13, gray, false));
        r.addView(names, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        r.addView(text(points(c.rating), 15, tone, true), LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        r.setOnClickListener(v -> presentFragment(new PrizmaClanActivity(c.id)));
        return r;
    }
}
