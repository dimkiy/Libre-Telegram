package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.PrizmaWall;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PrizmaWallActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class PrizmaProfileCard extends LinearLayout {
    private static final int EDGE = 16;

    private final BaseFragment fragment;
    private final Theme.ResourcesProvider rp;
    private int account;
    private long userId;
    private int builtVersion = -1;
    private final Runnable cloudChanged = this::rebuildIfChanged;

    public PrizmaProfileCard(Context context, BaseFragment fragment, Theme.ResourcesProvider rp) {
        super(context);
        this.fragment = fragment;
        this.rp = rp;
        setOrientation(VERTICAL);
        setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(12));
    }

    private int color(int key) {
        return Theme.getColor(key, rp);
    }

    private static int dataVersion() {
        return PrizmaSocial.version() * 31 + PrizmaCloud.version();
    }

    public void bind(int account, long userId) {
        boolean other = userId != this.userId || account != this.account;
        this.account = account;
        this.userId = userId;
        if (other) {
            PrizmaCloud.remote(account, userId);
            PrizmaCloud.markView(account, userId);
        } else if (builtVersion == dataVersion() && telegramSignature().equals(builtSignature)) {
            return;
        }
        rebuild();
    }

    private String builtSignature = "";

    private String telegramSignature() {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.User u = mc.getUser(userId);
        TLRPC.UserFull f = mc.getUserFull(userId);
        org.telegram.ui.Stars.StarsController.GiftsList gifts = org.telegram.ui.Stars.StarsController.getInstance(account).getProfileGiftsList(userId, false);
        return userId + ":" + (u != null && u.premium) + ":" + (u != null && u.verified) + ":" + (u != null ? u.first_name : "")
                + ":" + (f != null ? f.stargifts_count + "," + f.personal_channel_id + "," + (f.business_work_hours != null || f.business_location != null) : "-")
                + ":" + (gifts != null ? gifts.gifts.size() : -1);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        PrizmaCloud.addListener(cloudChanged);
        if (userId != 0 && builtVersion != dataVersion()) {
            post(this::rebuild);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        PrizmaCloud.removeListener(cloudChanged);
    }

    private void rebuildIfChanged() {
        if (userId != 0 && builtVersion != dataVersion()) rebuild();
    }

    private void rebuild() {
        builtVersion = dataVersion();
        builtSignature = telegramSignature();
        removeAllViews();
        final Context ctx = getContext();
        final boolean self = userId == UserConfig.getInstance(account).getClientUserId();
        final PrizmaSocial.Profile prof = PrizmaSocial.profile(account, userId);
        final ArrayList<PrizmaSocial.Achievement> ach = PrizmaSocial.achievements(account, userId, false);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("Libre");
        title.setTextColor(color(Theme.key_windowBackgroundWhiteBlueHeader));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTypeface(AndroidUtilities.bold());
        head.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (prof != null && prof.demo) {
            TextView demo = new TextView(ctx);
            demo.setText("демо");
            demo.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
            demo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            demo.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(2), AndroidUtilities.dp(8), AndroidUtilities.dp(3));
            demo.setBackground(field(10));
            head.addView(demo, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        }
        addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 24, EDGE, 0, EDGE, 10));

        View statusRow = statusRow(self);
        if (statusRow != null) {
            addView(statusRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 10));
        }
        if (self) PrizmaCloud.syncAchievementsSoon(account);

        View anonRow = anonRow(self);
        if (anonRow != null) {
            addView(anonRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 10));
        }
        if (self) org.telegram.messenger.PrizmaPresence.syncAnonNumber(account);

        if (PrizmaSocial.isCreator(userId)) {
            addView(creatorPlate(true), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 10));
        } else if (PrizmaSocial.isTeam(userId)) {
            addView(creatorPlate(false), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 10));
        }

        ArrayList<View> facts = new ArrayList<>();
        int year = PrizmaSocial.telegramYear(userId);
        if (year > 0) facts.add(fact("В Telegram с", "≈ " + year, 0, null));
        if (prof != null && prof.since > 0) {
            String date = new SimpleDateFormat("d MMM yyyy", new Locale("ru")).format(new Date(prof.since)).replace(".", "");
            facts.add(fact("В Libre с", date, 0, null));
        }
        if (prof != null && (prof.clan != null || self)) {
            PrizmaSocial.Clan clan = prof.clan;
            facts.add(fact("Клан", clan != null ? "[" + clan.tag + "] " + clan.name : "Создать",
                    clan != null ? clan.color : color(Theme.key_windowBackgroundWhiteBlueText),
                    () -> PrizmaClanSheet.show(fragment, account, userId, this::rebuild)));
        }
        if (prof != null && prof.views >= 0) {
            facts.add(fact("Просмотры", formatNumber(prof.views), 0, null));
        }
        for (int i = 0; i < facts.size(); i += 2) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.addView(facts.get(i), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, 4, 0));
            if (i + 1 < facts.size()) {
                row.addView(facts.get(i + 1), LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 4, 0, 0, 0));
            } else {
                row.addView(new View(ctx), LayoutHelper.createLinear(0, 1, 1f, 4, 0, 0, 0));
            }
            addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, i == 0 ? 0 : 8, EDGE, 0));
        }

        if (!ach.isEmpty() || self) {
            addView(sectionLabel(ach.isEmpty() ? "Достижения" : "Достижения · " + ach.size()),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 16, EDGE, 4));
            if (ach.isEmpty()) {
                TextView none = new TextView(ctx);
                none.setText("Пока нет — открываются за эфиры, обмен по NFC, плейлисты и клан");
                none.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
                none.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                addView(none, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 2, EDGE, 0));
            }
            HorizontalScrollView scroll = new HorizontalScrollView(ctx);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(OVER_SCROLL_NEVER);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(HORIZONTAL);
            row.setPadding(AndroidUtilities.dp(EDGE - 12), 0, AndroidUtilities.dp(EDGE - 12), 0);
            for (PrizmaSocial.Achievement a : ach) {
                row.addView(tile(a), LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT));
            }
            if (self) {
                row.addView(allTile(), LayoutHelper.createLinear(72, LayoutHelper.WRAP_CONTENT));
            }
            scroll.addView(row);
            addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        }

        PrizmaCloud.Remote remote = PrizmaCloud.peek(userId);
        if (remote != null && remote.playlistName != null) {
            addView(sectionLabel("Плейлист"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 14, EDGE, 8));
            addView(playlistRow(remote), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 0));
        }

        PrizmaWall.Wall wall = PrizmaCloud.enabled() ? PrizmaWall.get(account, userId) : null;
        int total = wall != null && wall.loaded ? wall.total : 0;
        String wallLabel = total == 0 ? "Стена" : "Стена · " + total + " " + PrizmaSocial.plural(total, "запись", "записи", "записей");
        addView(sectionLabel(wallLabel), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 14, EDGE, 8));
        addView(wallPreview(self, wall), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, EDGE, 0, EDGE, 0));
    }

    private View statusRow(boolean self) {
        int s;
        String text;
        if (self) {
            Object[] st = PrizmaSocial.myStatus(account);
            s = (Integer) st[0];
            text = (String) st[1];
        } else {
            PrizmaCloud.Remote r = PrizmaCloud.peek(userId);
            if (r == null || r.status < 0 || r.statusUntil > 0 && r.statusUntil * 1000L < System.currentTimeMillis()) return null;
            s = r.status;
            text = r.statusText;
        }
        final Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        row.setBackground(self ? pressableField(12) : field(12));
        row.addView(PrizmaStatusSheet.dot(ctx, s, 10), LayoutHelper.createLinear(10, 10, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        TextView t = new TextView(ctx);
        String label = PrizmaSocial.statusName(s) + (TextUtils.isEmpty(text) ? "" : " · " + text);
        if (self && s == PrizmaSocial.STATUS_ONLINE && TextUtils.isEmpty(text)) label = "В сети · задать статус";
        t.setText(label);
        t.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        if (self) {
            TextView chevron = new TextView(ctx);
            chevron.setText("›");
            chevron.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            row.addView(chevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
            row.setOnClickListener(v -> PrizmaStatusSheet.show(ctx, account, rp, this::rebuild));
        }
        return row;
    }

    private View anonRow(boolean self) {
        String digits;
        if (self) {
            TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
            digits = me != null && me.phone != null ? me.phone.replaceAll("[^0-9]", "") : "";
            if (!org.telegram.messenger.PrizmaConfig.showAnonNumber() || !org.telegram.messenger.PrizmaPresence.isAnonymous(digits)) return null;
        } else {
            PrizmaCloud.Remote r = PrizmaCloud.peek(userId);
            digits = r != null ? r.anon : null;
            if (!org.telegram.messenger.PrizmaPresence.isAnonymous(digits)) return null;
        }
        final String number = org.telegram.messenger.PrizmaPresence.formatAnon(digits);
        final Context ctx = getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(VERTICAL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
        row.setBackground(pressableField(12));
        TextView label = new TextView(ctx);
        label.setText(self ? "Анонимный номер · видят все в Libre" : "Анонимный номер");
        label.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        row.addView(label);
        TextView value = new TextView(ctx);
        value.setText(number);
        value.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        value.setTypeface(AndroidUtilities.bold());
        row.addView(value, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(number);
            android.widget.Toast.makeText(ctx, "Номер скопирован", android.widget.Toast.LENGTH_SHORT).show();
        });
        return row;
    }

    private View creatorPlate(boolean creator) {
        final Context ctx = getContext();
        LinearLayout plate = new LinearLayout(ctx);
        plate.setOrientation(HORIZONTAL);
        plate.setGravity(Gravity.CENTER_VERTICAL);
        plate.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        final int accent = creator ? color(Theme.key_profile_verifiedBackground) : PrizmaSocial.SAGE;
        bg.setColor(Theme.multAlpha(accent, 0.12f));
        plate.setBackground(bg);

        FrameLayout badge = new FrameLayout(ctx);
        ImageView area = new ImageView(ctx);
        area.setImageResource(R.drawable.verified_area);
        area.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
        badge.addView(area, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ImageView check = new ImageView(ctx);
        check.setImageResource(R.drawable.verified_check);
        check.setColorFilter(new PorterDuffColorFilter(color(Theme.key_profile_verifiedCheck), PorterDuff.Mode.SRC_IN));
        badge.addView(check, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        plate.addView(badge, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView t = new TextView(ctx);
        t.setText(creator ? "Создатель Libre" : "Команда Libre");
        t.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        t.setTypeface(AndroidUtilities.bold());
        texts.addView(t);
        TextView d = new TextView(ctx);
        d.setText(creator ? "Этот человек сделал Libre. Такой значок только один" : "Помогал создавать Libre");
        d.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        plate.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        return plate;
    }

    private GradientDrawable field(int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(AndroidUtilities.dp(radiusDp));
        d.setColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.05f));
        return d;
    }

    private android.graphics.drawable.Drawable pressableField(int radiusDp) {
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                field(radiusDp), Theme.createRadSelectorDrawable(color(Theme.key_listSelector), radiusDp, radiusDp)});
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        t.setTypeface(AndroidUtilities.bold());
        return t;
    }

    private View fact(String label, String value, int valueColor, Runnable onClick) {
        final Context ctx = getContext();
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(VERTICAL);
        cell.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        cell.setBackground(onClick != null ? pressableField(12) : field(12));
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(l);
        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(valueColor != 0 ? valueColor : color(Theme.key_windowBackgroundWhiteBlackText));
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        v.setTypeface(AndroidUtilities.bold());
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        if (onClick != null) {
            cell.setOnClickListener(x -> onClick.run());
        }
        return cell;
    }

    private View tile(PrizmaSocial.Achievement a) {
        final Context ctx = getContext();
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        t.addView(badge(ctx, a.icon, a.color, 48, 24), LayoutHelper.createLinear(48, 48));
        TextView name = new TextView(ctx);
        name.setText(a.title);
        name.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        name.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        name.setLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        t.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
        t.setBackground(Theme.createRadSelectorDrawable(color(Theme.key_listSelector), 12, 12));
        t.setOnClickListener(v -> showAchievement(a));
        return t;
    }

    private View allTile() {
        final Context ctx = getContext();
        LinearLayout t = new LinearLayout(ctx);
        t.setOrientation(VERTICAL);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        TextView circle = new TextView(ctx);
        circle.setText("›");
        circle.setGravity(Gravity.CENTER);
        circle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        circle.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteBlackText), 0.05f));
        circle.setBackground(bg);
        t.addView(circle, LayoutHelper.createLinear(48, 48));
        TextView name = new TextView(ctx);
        name.setText("Все");
        name.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
        name.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        name.setLines(2);
        t.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 2, 6, 2, 0));
        t.setBackground(Theme.createRadSelectorDrawable(color(Theme.key_listSelector), 12, 12));
        t.setOnClickListener(v -> showAll());
        return t;
    }

    static FrameLayout badge(Context ctx, int icon, int tint, int sizeDp, int iconDp) {
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor((tint & 0x00ffffff) | 0x29000000);
        f.setBackground(bg);
        ImageView iv = new ImageView(ctx);
        iv.setImageResource(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setColorFilter(new PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN));
        f.addView(iv, LayoutHelper.createFrame(iconDp, iconDp, Gravity.CENTER));
        return f;
    }

    private View playlistRow(PrizmaCloud.Remote remote) {
        final Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        box.setBackground(pressableField(12));
        FrameLayout icon = new FrameLayout(ctx);
        GradientDrawable ib = new GradientDrawable();
        ib.setCornerRadius(AndroidUtilities.dp(10));
        ib.setColor(Theme.multAlpha(PrizmaSocial.ORCHID, 0.18f));
        icon.setBackground(ib);
        ImageView note = new ImageView(ctx);
        note.setImageResource(R.drawable.msg_played);
        note.setColorFilter(new PorterDuffColorFilter(PrizmaSocial.ORCHID, PorterDuff.Mode.SRC_IN));
        note.setScaleType(ImageView.ScaleType.CENTER);
        icon.addView(note, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        box.addView(icon, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView first = new TextView(ctx);
        first.setText(remote.playlistName);
        first.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        first.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        first.setTypeface(AndroidUtilities.bold());
        first.setSingleLine(true);
        first.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(first);
        TextView second = new TextView(ctx);
        second.setText(remote.playlistCount + " " + PrizmaSocial.plural(remote.playlistCount, "трек", "трека", "треков") + " · послушать или сохранить себе");
        second.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        second.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        second.setSingleLine(true);
        second.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(second);
        box.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        box.setOnClickListener(v -> PrizmaPublicPlaylistSheet.show(getContext(), account, userId));
        return box;
    }

    private View wallPreview(boolean self, PrizmaWall.Wall wall) {
        PrizmaWall.Post newest = null;
        if (wall != null) {
            for (PrizmaWall.Post p : wall.posts) {
                if (!p.sending) {
                    newest = p;
                    break;
                }
            }
        }
        final Context ctx = getContext();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        box.setBackground(pressableField(12));

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(VERTICAL);
        TextView first = new TextView(ctx);
        first.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        first.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        first.setTypeface(AndroidUtilities.bold());
        first.setSingleLine(true);
        first.setEllipsize(TextUtils.TruncateAt.END);
        TextView second = new TextView(ctx);
        second.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        second.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        second.setMaxLines(2);
        second.setEllipsize(TextUtils.TruncateAt.END);

        TLRPC.User owner = MessagesController.getInstance(account).getUser(userId);
        String ownerName = owner != null ? UserObject.getFirstName(owner) : "";
        if (!PrizmaCloud.enabled()) {
            first.setText("Стена");
            second.setText("Стены работают через сервер Libre — включите его в хабе Libre");
        } else if (self && PrizmaSocial.wallHidden()) {
            first.setText("Стена скрыта");
            second.setText("Её видите только вы");
        } else if (wall != null && wall.notLibre) {
            first.setText("Стены пока нет");
            second.setText(ownerName + " ещё не пользуется Libre");
        } else if (!self && wall != null && wall.hidden) {
            first.setText("Стена скрыта");
            second.setText(ownerName + " скрыл стену");
        } else if (newest != null) {
            PrizmaWall.Post p = newest;
            BackupImageView avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(AndroidUtilities.dp(18));
            AvatarDrawable ad = new AvatarDrawable();
            TLRPC.User u = p.authorId > 0 ? MessagesController.getInstance(account).getUser(p.authorId) : null;
            if (u != null) {
                ad.setInfo(account, u);
                avatar.setForUserOrChat(u, ad);
            } else {
                ad.setInfo(p.authorId, authorName(account, p.authorId, p.name), null);
                avatar.setImageDrawable(ad);
            }
            box.addView(avatar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
            first.setText(authorName(account, p.authorId, p.name));
            second.setText(!TextUtils.isEmpty(p.text) ? p.text : p.photoUrl != null ? "Фото" : "");
        } else if (wall == null || !wall.loaded) {
            first.setText(wall != null && wall.failed ? "Стена не загрузилась" : "Загружаем стену…");
            second.setText(wall != null && wall.failed ? "Нет связи с сервером Libre — нажмите, чтобы открыть" : "Записи появятся через секунду");
        } else {
            first.setText(self ? "Пока пусто" : "Напишите первым");
            second.setText(self ? "Здесь будут записи — ваши и друзей" : "На стене " + ownerName + " ещё ничего нет");
        }
        texts.addView(first);
        texts.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
        box.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        TextView chevron = new TextView(ctx);
        chevron.setText("›");
        chevron.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        box.addView(chevron, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));
        box.setOnClickListener(v -> fragment.presentFragment(new PrizmaWallActivity(userId, 0)));
        return box;
    }

    static String authorName(int account, long authorId, String fallback) {
        if (authorId > 0) {
            TLRPC.User u = MessagesController.getInstance(account).getUser(authorId);
            if (u != null) return UserObject.getFirstName(u);
        }
        return fallback != null && !fallback.isEmpty() ? fallback : "Пользователь Libre";
    }

    static String formatNumber(long n) {
        String s = String.valueOf(n);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) out.append(' ');
            out.append(s.charAt(i));
        }
        return out.toString();
    }

    private void showAchievement(PrizmaSocial.Achievement a) {
        final Context ctx = getContext();
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        v.addView(badge(ctx, a.icon, a.color, 80, 40), LayoutHelper.createLinear(80, 80));
        TextView t = new TextView(ctx);
        t.setText(a.title);
        t.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        v.addView(t, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));
        TextView d = new TextView(ctx);
        d.setText(a.desc);
        d.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
        d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        d.setGravity(Gravity.CENTER);
        v.addView(d, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        final BottomSheet[] ref = new BottomSheet[1];
        TextView ok = button(ctx, "Понятно");
        ok.setOnClickListener(x -> {
            if (ref[0] != null) ref[0].dismiss();
        });
        v.addView(ok, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 22, 0, 0));
        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, rp);
        b.setCustomView(v);
        ref[0] = b.create();
        ref[0].show();
    }

    private void showAll() {
        final Context ctx = getContext();
        ArrayList<PrizmaSocial.Achievement> all = PrizmaSocial.achievements(account, userId, true);
        ArrayList<PrizmaSocial.Achievement> sorted = new ArrayList<>();
        for (PrizmaSocial.Achievement a : all) if (a.earned) sorted.add(a);
        for (PrizmaSocial.Achievement a : all) if (!a.earned) sorted.add(a);

        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(VERTICAL);
        list.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(12));
        TextView head = new TextView(ctx);
        int earned = 0;
        for (PrizmaSocial.Achievement a : all) if (a.earned) earned++;
        head.setText("Достижения · " + earned + " из " + all.size());
        head.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
        head.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        head.setTypeface(AndroidUtilities.bold());
        list.addView(head, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));
        for (PrizmaSocial.Achievement a : sorted) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
            r.addView(badge(ctx, a.icon, a.color, 42, 22), LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(VERTICAL);
            TextView n = new TextView(ctx);
            n.setText(a.title);
            n.setTextColor(color(Theme.key_windowBackgroundWhiteBlackText));
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            n.setTypeface(AndroidUtilities.bold());
            texts.addView(n);
            TextView s = new TextView(ctx);
            String line = a.earned ? a.desc : (a.how != null ? a.how : a.desc);
            if (!a.earned && a.goal > 0) line += " · " + a.progress + " из " + a.goal;
            s.setText(line);
            s.setTextColor(color(Theme.key_windowBackgroundWhiteGrayText));
            s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            texts.addView(s, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            if (!a.earned && a.goal > 0) {
                texts.addView(progress(ctx, a), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 0, 6, 0, 2));
            }
            r.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            if (!a.earned) r.setAlpha(0.55f);
            list.addView(r);
        }
        ScrollView sv = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.72f), MeasureSpec.AT_MOST));
            }
        };
        sv.addView(list);
        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, rp);
        b.setCustomView(sv);
        b.create().show();
    }

    private View progress(Context ctx, PrizmaSocial.Achievement a) {
        FrameLayout track = new FrameLayout(ctx);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(AndroidUtilities.dp(2));
        tb.setColor(Theme.multAlpha(color(Theme.key_windowBackgroundWhiteGrayText), 0.18f));
        track.setBackground(tb);
        View fill = new View(ctx);
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(AndroidUtilities.dp(2));
        fb.setColor(a.color);
        fill.setBackground(fb);
        final float part = a.goal > 0 ? Math.max(0.04f, a.progress / (float) a.goal) : 0;
        track.addView(fill, new FrameLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT));
        track.addOnLayoutChangeListener((view, l, t, r, btm, ol, ot, or, ob) -> {
            int w = Math.round((r - l) * part);
            if (fill.getLayoutParams().width != w) {
                fill.getLayoutParams().width = w;
                fill.requestLayout();
            }
        });
        return track;
    }

    TextView button(Context ctx, String text) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color(Theme.key_featuredStickers_buttonText));
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        b.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        return b;
    }
}
