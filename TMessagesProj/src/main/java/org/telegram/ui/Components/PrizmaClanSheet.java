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

/*
 * Libre — что это за файл:
 * Экран клана: участники, роли, приглашение, стена клана, рейтинг.
 */

/**
 * Prizma: clans on Libre Cloud — emblem (tag on its colour), numbers, members with roles, invite
 * link, the clan's wall and the clan rating. Head: roles, kick, disband; officers: invite, kick.
 */
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

    /** The profile card's «Клан» and the hub's «Мой клан». */
    public static void show(BaseFragment fragment, int account, long userId, Runnable onChanged) {
        if (fragment == null || fragment.getParentActivity() == null || !serverReady()) return;
        boolean self = userId == UserConfig.getInstance(account).getClientUserId();
        if (self) {
            AlertDialog progress = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progress.showDelayed(250);
            PrizmaClans.refreshMine(account, (String err) -> {
                progress.dismiss();
                if (fragment.getParentActivity() == null) return;
                PrizmaSocial.Clan mine = PrizmaClans.mine(account);
                if (mine != null) {
                    showClan(fragment, account, mine.id, onChanged);
                } else if (err != null) {
                    // no answer from the server — not the same as "you have no clan"
                    toast(err);
                } else {
                    showNoClan(fragment, account, onChanged);
                }
            });
            return;
        }
        PrizmaSocial.Profile p = PrizmaSocial.profile(account, userId);
        if (p != null && p.clan != null && p.clan.id != 0) {
            showClan(fragment, account, p.clan.id, onChanged);
        } else {
            toast("Клан ещё загружается — попробуйте через секунду");
        }
    }

    // ===================== no clan yet =====================

    private static void showNoClan(BaseFragment fragment, int account, Runnable onChanged) {
        final Context ctx = fragment.getParentActivity();
        new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                .setTitle("Клан")
                .setItems(new CharSequence[]{"Создать свой клан", "Вступить по ссылке или коду", "Рейтинг кланов"}, (d, which) -> {
                    if (which == 0) showCreate(fragment, account, onChanged);
                    else if (which == 1) askCode(fragment, account, onChanged);
                    else showTop(fragment, account, onChanged);
                })
                .show();
    }

    private static void askCode(BaseFragment fragment, int account, Runnable onChanged) {
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

    /** Links «https://<server>/c/<code>» open the join dialog right in Libre. */
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

    // ===================== a clan =====================

    private static void showClan(BaseFragment fragment, int account, long clanId, Runnable onChanged) {
        PrizmaClans.load(account, clanId, (clan, err) -> {
            if (clan == null) {
                toast(err);
                return;
            }
            if (fragment.getParentActivity() != null) render(fragment, account, clan, onChanged);
        });
    }

    private static void render(BaseFragment fragment, int account, PrizmaSocial.Clan clan, Runnable onChanged) {
        final Context ctx = fragment.getParentActivity();
        final Theme.ResourcesProvider rp = fragment.getResourceProvider();
        final BottomSheet[] ref = new BottomSheet[1];
        final Runnable reopen = () -> {
            if (ref[0] != null) ref[0].dismiss();
            showClan(fragment, account, clan.id, onChanged);
        };
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, AndroidUtilities.dp(24), 0, AndroidUtilities.dp(12));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.addView(emblem(ctx, clan.tag, clan.color, 72), LayoutHelper.createLinear(72, 72));
        TextView name = new TextView(ctx);
        name.setText(clan.name);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        name.setTypeface(AndroidUtilities.bold());
        name.setGravity(Gravity.CENTER);
        head.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 12, 20, 0));
        TextView sub = new TextView(ctx);
        sub.setText("[" + clan.tag + "]" + (clan.mine ? " · вы " + PrizmaSocial.roleName(clan.myRole).toLowerCase() : ""));
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        sub.setGravity(Gravity.CENTER);
        head.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 3, 20, 0));
        v.addView(head);

        LinearLayout stats = new LinearLayout(ctx);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        addStat(ctx, rp, stats, String.valueOf(clan.level), "уровень", true);
        addStat(ctx, rp, stats, PrizmaProfileCard.formatNumber(clan.rating), "рейтинг", false);
        addStat(ctx, rp, stats, String.valueOf(clan.members), PrizmaSocial.plural(clan.members, "участник", "участника", "участников"), false);
        v.addView(stats, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 12, 18, 12, 6));

        // wall + rating
        LinearLayout links = new LinearLayout(ctx);
        links.setOrientation(LinearLayout.HORIZONTAL);
        TextView wall = button(ctx, "Стена клана", false, rp);
        wall.setOnClickListener(x -> {
            if (ref[0] != null) ref[0].dismiss();
            fragment.presentFragment(new PrizmaWallActivity(-clan.id, 0));
        });
        links.addView(wall, LayoutHelper.createLinear(0, 44, 1f, 0, 0, 6, 0));
        TextView top = button(ctx, "Рейтинг", false, rp);
        top.setOnClickListener(x -> {
            if (ref[0] != null) ref[0].dismiss();
            showTop(fragment, account, onChanged);
        });
        links.addView(top, LayoutHelper.createLinear(0, 44, 1f, 6, 0, 0, 0));
        v.addView(links, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 20, 10, 20, 4));

        TextView membersHead = new TextView(ctx);
        membersHead.setText("Участники");
        membersHead.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, rp));
        membersHead.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        membersHead.setTypeface(AndroidUtilities.bold());
        v.addView(membersHead, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 14, 20, 4));

        final long me = UserConfig.getInstance(account).getClientUserId();
        for (PrizmaSocial.Member m : clan.list) {
            LinearLayout r = new LinearLayout(ctx);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(7), AndroidUtilities.dp(20), AndroidUtilities.dp(7));
            r.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 2));
            BackupImageView avatar = new BackupImageView(ctx);
            avatar.setRoundRadius(AndroidUtilities.dp(19));
            AvatarDrawable ad = new AvatarDrawable();
            TLRPC.User u = MessagesController.getInstance(account).getUser(m.userId);
            if (u != null) {
                ad.setInfo(account, u);
                avatar.setForUserOrChat(u, ad);
            } else {
                ad.setInfo(m.userId, m.name, null);
                avatar.setImageDrawable(ad);
            }
            r.addView(avatar, LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            TextView n = new TextView(ctx);
            n.setText(m.userId == me ? m.name + " (вы)" : m.name);
            n.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            TextView role = new TextView(ctx);
            role.setText(m.role);
            role.setTextColor(m.roleId == PrizmaSocial.ROLE_HEAD ? clan.color : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            role.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            r.addView(role, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            r.setOnClickListener(x -> {
                if (u == null) return;
                if (ref[0] != null) ref[0].dismiss();
                Bundle args = new Bundle();
                args.putLong("user_id", m.userId);
                fragment.presentFragment(new ProfileActivity(args));
            });
            // the head manages everyone, officers can remove members
            boolean canManage = m.userId != me && (clan.myRole == PrizmaSocial.ROLE_HEAD
                    || clan.myRole == PrizmaSocial.ROLE_OFFICER && m.roleId == PrizmaSocial.ROLE_MEMBER);
            if (canManage) {
                r.setOnLongClickListener(x -> {
                    manageMember(fragment, account, clan, m, reopen);
                    return true;
                });
            }
            v.addView(r);
        }
        if (clan.myRole >= PrizmaSocial.ROLE_OFFICER) {
            TextView hint = new TextView(ctx);
            hint.setText("Долгое нажатие на участника — роль или исключить");
            hint.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            v.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 4, 20, 0));
        }

        if (clan.myRole >= PrizmaSocial.ROLE_OFFICER) {
            TextView invite = button(ctx, "Пригласить в клан", false, rp);
            invite.setOnClickListener(x -> PrizmaClans.invite(account, clan.id, (link, err) -> {
                if (link == null) {
                    toast(err);
                    return;
                }
                String text = "Вступай в мой клан «" + clan.name + "» [" + clan.tag + "] в Libre: " + link;
                try {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    ctx.startActivity(Intent.createChooser(send, "Приглашение в клан"));
                } catch (Throwable e) {
                    AndroidUtilities.addToClipboard(text);
                    toast("Ссылка-приглашение скопирована");
                }
            }));
            v.addView(invite, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 20, 16, 20, 0));
        }
        if (clan.mine) {
            final boolean isHead = clan.myRole == PrizmaSocial.ROLE_HEAD;
            TextView leave = button(ctx, "Выйти из клана", true, rp);
            leave.setOnClickListener(x -> new AlertDialog.Builder(ctx, rp)
                    .setTitle("Выйти из клана?")
                    .setMessage(isHead && clan.members > 1 ? "Главой станет офицер или самый давний участник." : isHead ? "Вы последний участник — клан закроется." : "Вернуться можно будет по приглашению.")
                    .setPositiveButton("Выйти", (d, w) -> PrizmaClans.leave(account, e -> {
                        toast(e);
                        if (e != null) return;
                        if (ref[0] != null) ref[0].dismiss();
                        if (onChanged != null) onChanged.run();
                    }))
                    .setNegativeButton("Отмена", null)
                    .makeRed(AlertDialog.BUTTON_POSITIVE)
                    .show());
            v.addView(leave, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 20, 6, 20, 0));
            if (isHead) {
                TextView disband = button(ctx, "Распустить клан", true, rp);
                disband.setOnClickListener(x -> new AlertDialog.Builder(ctx, rp)
                        .setTitle("Распустить клан?")
                        .setMessage("Клан «" + clan.name + "» исчезнет вместе с участниками, стеной и рейтингом.")
                        .setPositiveButton("Распустить", (d, w) -> PrizmaClans.disband(account, clan.id, e -> {
                            toast(e);
                            if (e != null) return;
                            if (ref[0] != null) ref[0].dismiss();
                            if (onChanged != null) onChanged.run();
                        }))
                        .setNegativeButton("Отмена", null)
                        .makeRed(AlertDialog.BUTTON_POSITIVE)
                        .show());
                v.addView(disband, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 20, 0, 20, 0));
            }
        }

        ScrollView sv = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.8f), MeasureSpec.AT_MOST));
            }
        };
        sv.setNestedScrollingEnabled(true);
        sv.addView(v);
        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, rp);
        b.setCustomView(sv);
        ref[0] = b.create();
        ref[0].setCanDismissWithSwipe(false); // a fast fling through the members must not close it
        ref[0].show();
    }

    private static void manageMember(BaseFragment fragment, int account, PrizmaSocial.Clan clan, PrizmaSocial.Member m, Runnable reopen) {
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

    // ===================== rating =====================

    private static void showTop(BaseFragment fragment, int account, Runnable onChanged) {
        PrizmaClans.top(account, (list, err) -> {
            Context ctx = fragment.getParentActivity();
            if (list == null || ctx == null) {
                toast(err);
                return;
            }
            Theme.ResourcesProvider rp = fragment.getResourceProvider();
            final BottomSheet[] ref = new BottomSheet[1];
            LinearLayout v = new LinearLayout(ctx);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setPadding(0, AndroidUtilities.dp(18), 0, AndroidUtilities.dp(12));
            TextView title = new TextView(ctx);
            title.setText("Рейтинг кланов");
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
            title.setTypeface(AndroidUtilities.bold());
            v.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 4));
            TextView how = new TextView(ctx);
            how.setText("Очки: 100 за участника, 10 за запись на стене клана, 1 за каждый лайк");
            how.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
            how.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            v.addView(how, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 10));
            if (list.isEmpty()) {
                TextView empty = new TextView(ctx);
                empty.setText("Кланов пока нет — создайте первый");
                empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                empty.setGravity(Gravity.CENTER);
                v.addView(empty, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 24, 20, 24));
            }
            int place = 0;
            for (PrizmaSocial.Clan c : list) {
                place++;
                LinearLayout r = new LinearLayout(ctx);
                r.setOrientation(LinearLayout.HORIZONTAL);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), AndroidUtilities.dp(8));
                r.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, rp), 2));
                TextView pos = new TextView(ctx);
                pos.setText(String.valueOf(place));
                pos.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
                pos.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                pos.setGravity(Gravity.CENTER);
                r.addView(pos, LayoutHelper.createLinear(28, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
                r.addView(emblem(ctx, c.tag, c.color, 38), LayoutHelper.createLinear(38, 38, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
                LinearLayout texts = new LinearLayout(ctx);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView n = new TextView(ctx);
                n.setText(c.name);
                n.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp));
                n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
                n.setTypeface(AndroidUtilities.bold());
                n.setSingleLine(true);
                n.setEllipsize(TextUtils.TruncateAt.END);
                texts.addView(n);
                TextView s = new TextView(ctx);
                s.setText("[" + c.tag + "] · " + c.members + " " + PrizmaSocial.plural(c.members, "участник", "участника", "участников"));
                s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp));
                s.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                texts.addView(s);
                r.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
                TextView pts = new TextView(ctx);
                pts.setText(PrizmaProfileCard.formatNumber(c.rating));
                pts.setTextColor(c.color);
                pts.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                pts.setTypeface(AndroidUtilities.bold());
                r.addView(pts, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
                r.setOnClickListener(x -> {
                    if (ref[0] != null) ref[0].dismiss();
                    showClan(fragment, account, c.id, onChanged);
                });
                v.addView(r);
            }
            ScrollView sv = new ScrollView(ctx) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.75f), MeasureSpec.AT_MOST));
                }
            };
            sv.setNestedScrollingEnabled(true);
            sv.addView(v);
            BottomSheet.Builder b = new BottomSheet.Builder(ctx, false, rp);
            b.setCustomView(sv);
            ref[0] = b.create();
            ref[0].setCanDismissWithSwipe(false);
            ref[0].show();
        });
    }

    // ===================== create =====================

    private static void showCreate(BaseFragment fragment, int account, Runnable onChanged) {
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
                    render(fragment, account, clan, onChanged);
                });
            });
        }
    }

    // ===================== helpers =====================

    static FrameLayout emblem(Context ctx, String tag, int color, int sizeDp) {
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
