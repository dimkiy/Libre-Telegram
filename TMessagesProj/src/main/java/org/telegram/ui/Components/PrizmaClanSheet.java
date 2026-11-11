package org.telegram.ui.Components;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.PrizmaClans;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PrizmaWallActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;

public final class PrizmaClanSheet {
    private PrizmaClanSheet() {}

    private static void toast(String text) {
        if (text != null) Toast.makeText(org.telegram.messenger.ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
    }

    private static boolean serverReady() {
        if (PrizmaCloud.enabled()) return true;
        toast("Кланы работают через сервер Libre — включите его в хабе Libre");
        return false;
    }

    public static void show(BaseFragment fragment, int account, long userId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || !serverReady()) return;
        if (userId == UserConfig.getInstance(account).getClientUserId()) {
            fragment.presentFragment(new org.telegram.ui.PrizmaClanActivity(0));
            return;
        }
        PrizmaSocial.Profile p = PrizmaSocial.profile(account, userId);
        if (p != null && p.clan != null && p.clan.id != 0) {
            fragment.presentFragment(new org.telegram.ui.PrizmaClanActivity(p.clan.id));
        } else {
            toast("Клан ещё загружается — попробуйте через секунду");
        }
    }

    public static void askCode(BaseFragment fragment, int account, Runnable onChanged) {
        final Context ctx = fragment.getParentActivity();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        FrameLayout box = new FrameLayout(ctx);
        EditTextBoldCursor field = field(ctx, rp, "Ссылка-приглашение или код", 200);
        box.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
        AlertDialog dialog = new AlertDialog.Builder(ctx, rp)
                .setTitle("Вступить в клан")
                .setView(box)
                .setPositiveButton("Дальше", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        View ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok != null) ok.setOnClickListener(x -> {
            String code = PrizmaClans.codeOf(field.getText().toString());
            if (code == null) {
                AndroidUtilities.shakeViewSpring(field);
                return;
            }
            dialog.dismiss();
            confirmJoin(fragment, account, code, onChanged);
        });
    }

    public static boolean handleInviteLink(Uri uri) {
        if (uri == null || !PrizmaCloud.configured()) return false;
        String code = PrizmaClans.codeOf(uri.toString());
        if (code == null || uri.getPath() == null || !uri.getPath().startsWith("/c/")) return false;
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) return false;
        confirmJoin(last, UserConfig.selectedAccount, code, null);
        return true;
    }

    private static void confirmJoin(BaseFragment fragment, int account, String code, Runnable onChanged) {
        if (!serverReady()) return;
        PrizmaClans.preview(account, code, (clan, err) -> {
            if (clan == null) {
                toast(err);
                return;
            }
            Context ctx = fragment.getParentActivity();
            if (ctx == null) return;
            new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                    .setTitle("Вступить в клан?")
                    .setMessage("«" + clan.name + "» [" + clan.tag + "] · " + clan.members + " "
                            + PrizmaSocial.plural(clan.members, "участник", "участника", "участников"))
                    .setPositiveButton("Вступить", (d, w) -> PrizmaClans.join(account, code, (c, e) -> {
                        if (c == null) {
                            toast(e);
                            return;
                        }
                        toast("Вы в клане «" + c.name + "»");
                        if (onChanged != null) onChanged.run();
                        showClan(fragment, account, c.id, onChanged);
                    }))
                    .setNegativeButton("Отмена", null)
                    .show();
        });
    }

    private static void showClan(BaseFragment fragment, int account, long clanId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        fragment.presentFragment(new org.telegram.ui.PrizmaClanActivity(clanId), fragment instanceof org.telegram.ui.PrizmaClanActivity);
    }

    public static void manageMember(BaseFragment fragment, int account, PrizmaSocial.Clan clan, PrizmaSocial.Member m, Runnable reopen) {
        final Context ctx = fragment.getParentActivity();
        ArrayList<CharSequence> rows = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        if (clan.myRole == PrizmaSocial.ROLE_HEAD) {
            if (m.roleId == PrizmaSocial.ROLE_MEMBER) {
                rows.add("Сделать офицером");
                actions.add(() -> PrizmaClans.setRole(account, clan.id, m.userId, PrizmaSocial.ROLE_OFFICER, (c, e) -> after(e, reopen)));
            } else {
                rows.add("Сделать участником");
                actions.add(() -> PrizmaClans.setRole(account, clan.id, m.userId, PrizmaSocial.ROLE_MEMBER, (c, e) -> after(e, reopen)));
            }
            rows.add("Передать главенство");
            actions.add(() -> new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                    .setTitle("Передать клан?")
                    .setMessage(m.name + " станет главой, а вы — офицером.")
                    .setPositiveButton("Передать", (d, w) -> PrizmaClans.setRole(account, clan.id, m.userId, PrizmaSocial.ROLE_HEAD, (c, e) -> after(e, reopen)))
                    .setNegativeButton("Отмена", null)
                    .show());
        }
        rows.add("Исключить из клана");
        actions.add(() -> PrizmaClans.kick(account, clan.id, m.userId, e -> after(e, reopen)));
        new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                .setTitle(m.name)
                .setItems(rows.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private static void after(String err, Runnable reopen) {
        if (err != null) toast(err);
        else reopen.run();
    }

    public static void showCreate(BaseFragment fragment, int account, Runnable onChanged) {
        final Context ctx = fragment.getParentActivity();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), 0);

        final int[] picked = {PrizmaSocial.SAGE};
        final FrameLayout preview = emblem(ctx, "LBR", picked[0], 56);
        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(preview, LayoutHelper.createLinear(56, 56, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        TextView hint = new TextView(ctx);
        hint.setText("Тег будет виден в профиле у всех участников");
        hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        top.addView(hint, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        v.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final EditTextBoldCursor name = field(ctx, rp, "Название клана", 32);
        v.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 6, 0, 0));
        final EditTextBoldCursor tag = field(ctx, rp, "Тег: 2–5 букв или цифр", 5);
        tag.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(5)});
        tag.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        v.addView(tag, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 10, 0, 0));
        tag.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                ((TextView) preview.getChildAt(0)).setText(s.length() > 0 ? s.toString() : "LBR");
            }
        });

        LinearLayout colors = new LinearLayout(ctx);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        final View[] swatches = new View[PrizmaSocial.PALETTE.length];
        for (int i = 0; i < PrizmaSocial.PALETTE.length; i++) {
            final int c = PrizmaSocial.PALETTE[i];
            View s = new View(ctx);
            swatches[i] = s;
            s.setOnClickListener(x -> {
                picked[0] = c;
                ((GradientDrawable) preview.getBackground()).setColor(c);
                for (int k = 0; k < swatches.length; k++) styleSwatch(swatches[k], PrizmaSocial.PALETTE[k], PrizmaSocial.PALETTE[k] == c, rp);
            });
            styleSwatch(s, c, c == picked[0], rp);
            colors.addView(s, LayoutHelper.createLinear(30, 30, 0, 0, 10, 0));
        }
        v.addView(colors, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 8));

        AlertDialog dialog = new AlertDialog.Builder(ctx, rp)
                .setTitle("Новый клан")
                .setView(v)
                .setPositiveButton("Создать", null)
                .setNegativeButton("Отмена", null)
                .create();
        dialog.show();
        View create = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (create != null) {
            create.setOnClickListener(x -> {
                String n = name.getText().toString().trim();
                String t = tag.getText().toString().trim();
                if (n.length() < 2) {
                    AndroidUtilities.shakeViewSpring(name);
                    return;
                }
                if (t.length() < 2 || !t.matches("[A-ZА-ЯЁ0-9]+")) {
                    AndroidUtilities.shakeViewSpring(tag);
                    return;
                }
                create.setEnabled(false);
                PrizmaClans.create(account, n, t, picked[0], (clan, err) -> {
                    create.setEnabled(true);
                    if (clan == null) {
                        toast(err);
                        return;
                    }
                    dialog.dismiss();
                    if (onChanged != null) onChanged.run();
                    showClan(fragment, account, clan.id, onChanged);
                });
            });
        }
    }

    public static FrameLayout emblem(Context ctx, String tag, int color, int sizeDp) {
        FrameLayout f = new FrameLayout(ctx);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(sizeDp * 0.28f));
        bg.setColor(color);
        f.setBackground(bg);
        TextView t = new TextView(ctx);
        t.setText(tag);
        t.setTextColor(0xffffffff);
        t.setTypeface(AndroidUtilities.bold());
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp * (tag != null && tag.length() > 4 ? 0.22f : 0.26f));
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        f.addView(t, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        return f;
    }

    private static void styleSwatch(View s, int color, boolean selected, Theme.ResourcesProvider rp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (selected) {
            d.setStroke(AndroidUtilities.dp(3), Theme.getColor(Theme.key_dialogBackground, rp));
        }
        s.setBackground(d);
        s.setScaleX(selected ? 1.15f : 1f);
        s.setScaleY(selected ? 1.15f : 1f);
    }

    private static EditTextBoldCursor field(Context ctx, Theme.ResourcesProvider rp, String hint, int max) {
        EditTextBoldCursor e = new EditTextBoldCursor(ctx);
        e.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        e.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        e.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, rp));
        e.setHint(hint);
        e.setSingleLine(true);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        e.setCursorColor(Theme.getColor(Theme.key_dialogTextBlack, rp));
        e.setCursorSize(AndroidUtilities.dp(20));
        e.setCursorWidth(1.5f);
        e.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, rp),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, rp),
                Theme.getColor(Theme.key_text_RedBold, rp));
        return e;
    }

    private static void addStat(Context ctx, Theme.ResourcesProvider rp, LinearLayout stats, String value, String label, boolean first) {
        if (!first) {
            View sep = new View(ctx);
            sep.setBackgroundColor(Theme.getColor(Theme.key_divider, rp));
            stats.addView(sep, LayoutHelper.createLinear(1, 30, Gravity.CENTER_VERTICAL));
        }
        LinearLayout cell = new LinearLayout(ctx);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        v.setTypeface(AndroidUtilities.bold());
        cell.addView(v);
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        cell.addView(l);
        stats.addView(cell, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
    }

    private static TextView button(Context ctx, String text, boolean danger, Theme.ResourcesProvider rp) {
        TextView b = new TextView(ctx);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        if (danger) {
            b.setTextColor(Theme.getColor(Theme.key_text_RedBold, rp));
            b.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 10, 10));
        } else {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
            b.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        }
        return b;
    }
}
