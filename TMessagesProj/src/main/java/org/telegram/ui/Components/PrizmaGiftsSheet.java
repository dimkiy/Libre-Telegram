package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

/*
 * Libre — что это за файл:
 * Выбор подарков для витрины.
 */

/**
 * Prizma: picker for the profile gift showcase. Shows the public gift catalog;
 * tapping a gift adds/removes it from the user's own decorative showcase.
 */
public class PrizmaGiftsSheet implements NotificationCenter.NotificationCenterDelegate {

    private static final int COLUMNS = 3;

    private final Context context;
    private final int currentAccount = UserConfig.selectedAccount;
    private LinearLayout grid;
    private TextView status;
    private Runnable onChanged;
    private boolean changed;

    public static void show(Context context, Runnable onChanged) {
        new PrizmaGiftsSheet(context, onChanged).build();
    }

    private PrizmaGiftsSheet(Context context, Runnable onChanged) {
        this.context = context;
        this.onChanged = onChanged;
    }

    private BottomSheet sheet;

    private void build() {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView title = new TextView(context);
        title.setText("Витрина подарков");
        title.setTextColor(textColor);
        title.setTextSize(21);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText("Тап — добавить копию на профиль, долгое нажатие — убрать одну. Улучшить в NFT — нажми на подарок в своём профиле. NFT видны другим пользователям Libre.");
        subtitle.setTextColor(grayColor);
        subtitle.setTextSize(13);
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 12));

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setNestedScrollingEnabled(true); // the sheet follows only the list's overscroll at the top
        grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(grid, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        status = new TextView(context);
        status.setTextColor(grayColor);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, AndroidUtilities.dp(30), 0, AndroidUtilities.dp(30));
        grid.addView(status);

        int h = (int) (AndroidUtilities.displaySize.y / AndroidUtilities.density * 0.56f);
        root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, h));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setCustomView(root);
        sheet = builder.create();
        // a fast fling up through the gifts must not read as "swipe the sheet away"
        sheet.setCanDismissWithSwipe(false);
        sheet.setOnHideListener(d -> {
            NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.starGiftsLoaded);
            if (changed) {
                try {
                    org.telegram.ui.Stars.StarsController.getInstance(currentAccount)
                            .invalidateProfileGifts(UserConfig.getInstance(currentAccount).getClientUserId());
                } catch (Throwable ignore) {}
                if (onChanged != null) {
                    onChanged.run();
                }
            }
        });
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.starGiftsLoaded);
        sheet.show();

        rebuild();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.starGiftsLoaded) {
            rebuild();
        }
    }

    private void rebuild() {
        ArrayList<TL_stars.StarGift> catalog = PrizmaGifts.catalog();
        grid.removeAllViews();
        if (catalog.isEmpty()) {
            status.setText("Загрузка подарков…");
            grid.addView(status);
            return;
        }

        LinearLayout row = null;
        ArrayList<TL_stars.StarGift> gifts = new ArrayList<>();
        for (TL_stars.StarGift g : catalog) if (g != null) gifts.add(g); // a gap in the catalog must not leave a row unmade
        int count = gifts.size();
        for (int i = 0; i < count; i++) {
            final TL_stars.StarGift gift = gifts.get(i);
            if (i % COLUMNS == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                grid.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            row.addView(giftCell(gift), LayoutHelper.createLinear(0, 132, 1f));
        }
        if (row != null) {
            int rem = count % COLUMNS;
            if (rem != 0) {
                for (int k = rem; k < COLUMNS; k++) {
                    row.addView(new View(context), LayoutHelper.createLinear(0, 132, 1f));
                }
            }
        }
    }

    private static String formatStars(long n) {
        try {
            return String.format(java.util.Locale.US, "%,d", n);
        } catch (Throwable e) {
            return String.valueOf(n);
        }
    }

    private View giftCell(TL_stars.StarGift gift) {
        final android.widget.FrameLayout cell = new android.widget.FrameLayout(context);
        cell.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));

        // market-style card
        final android.widget.FrameLayout card = new android.widget.FrameLayout(context);
        card.setClipToPadding(false);

        final BackupImageView iv = new BackupImageView(context);
        PrizmaGifts.applySticker(iv, gift, org.telegram.messenger.PrizmaConfig.fancyEffects());
        card.addView(iv, LayoutHelper.createFrame(72, 72, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 16, 0, 0));

        // price chip ⭐ N
        final TextView price = new TextView(context);
        price.setText("⭐ " + formatStars(gift.stars));
        price.setTextColor(0xffE8A400);
        price.setTextSize(12.5f);
        price.setTypeface(AndroidUtilities.bold());
        price.setGravity(Gravity.CENTER);
        price.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(3), AndroidUtilities.dp(10), AndroidUtilities.dp(3));
        GradientDrawable priceBg = new GradientDrawable();
        priceBg.setCornerRadius(AndroidUtilities.dp(12));
        priceBg.setColor(Theme.multAlpha(0xffE8A400, 0.14f));
        price.setBackground(priceBg);
        card.addView(price, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, 0, 0, 12));

        // "NFT" badge shown when upgraded
        final TextView badge = new TextView(context);
        badge.setText("NFT");
        badge.setTextColor(0xff1a1a1a);
        badge.setTextSize(9);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(AndroidUtilities.dp(7));
        badgeBg.setColor(0xffFFD24A);
        badge.setBackground(badgeBg);
        card.addView(badge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.RIGHT, 0, 7, 7, 0));

        // "×N" copies badge (shown when more than one of the same gift)
        final TextView countBadge = new TextView(context);
        countBadge.setTextColor(0xffffffff);
        countBadge.setTextSize(10);
        countBadge.setTypeface(AndroidUtilities.bold());
        countBadge.setGravity(Gravity.CENTER);
        countBadge.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(1), AndroidUtilities.dp(5), AndroidUtilities.dp(1));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(AndroidUtilities.dp(8));
        cbg.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        countBadge.setBackground(cbg);
        card.addView(countBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 7, 7, 0, 0));

        cell.addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final Runnable refresh = () -> {
            int c = PrizmaGifts.count(gift.id);
            countBadge.setText("×" + c);
            countBadge.setVisibility(c >= 2 ? View.VISIBLE : View.GONE);
            applyCellState(card, badge, c > 0, false); // NFT upgrade is per-copy, done from the profile
        };
        refresh.run();

        cell.setOnClickListener(v -> {
            if (!PrizmaGifts.add(gift.id)) {
                android.widget.Toast.makeText(context, "Витрина заполнена", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            refresh.run();
            pressFeedback(card);
            changed = true;
        });
        cell.setOnLongClickListener(v -> {
            try { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
            // long-press = remove one copy right here, no menu; plain copies go first, an NFT only after asking
            if (PrizmaGifts.count(gift.id) > 0) {
                final Runnable remove = () -> {
                    PrizmaGifts.removeOne(gift.id);
                    refresh.run();
                    pressFeedback(card);
                    changed = true;
                    android.widget.Toast.makeText(context, "Убрано из витрины", android.widget.Toast.LENGTH_SHORT).show();
                };
                if (PrizmaGifts.onlyUpgradedCopies(gift.id)) {
                    new AlertDialog.Builder(context)
                            .setTitle("Убрать NFT?")
                            .setMessage("Все копии этого подарка улучшены в NFT. Убрать одну вместе с её моделью, фоном и номером?")
                            .setPositiveButton("Убрать", (d, w) -> remove.run())
                            .setNegativeButton("Отмена", null)
                            .show();
                } else {
                    remove.run();
                }
            } else {
                android.widget.Toast.makeText(context, "Этого подарка нет в витрине", android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        });
        return cell;
    }

    /** Long-press menu: add/remove copies. NFT upgrade is per-copy, done from the profile. */
    private void showGiftMenu(TL_stars.StarGift gift, Runnable refresh) {
        int c = PrizmaGifts.count(gift.id);
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        java.util.ArrayList<Runnable> actions = new java.util.ArrayList<>();

        items.add("Добавить ещё одну");
        actions.add(() -> {
            if (!PrizmaGifts.add(gift.id)) {
                android.widget.Toast.makeText(context, "Витрина заполнена", android.widget.Toast.LENGTH_SHORT).show();
            } else {
                refresh.run();
                changed = true;
            }
        });
        if (c > 0) {
            items.add("Убрать одну");
            actions.add(() -> { PrizmaGifts.removeOne(gift.id); refresh.run(); changed = true; });
            items.add("Убрать все");
            actions.add(() -> { PrizmaGifts.removeAll(gift.id); refresh.run(); changed = true; });
        }

        final String[] arr = items.toArray(new String[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(c >= 1 ? ("В витрине: " + c + " · улучшить можно в профиле") : "Подарок");
        b.setItems(arr, (d, which) -> {
            if (which >= 0 && which < actions.size()) actions.get(which).run();
        });
        b.show();
    }

    private static void pressFeedback(View v) {
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f)
                        .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(220).start())
                .start();
    }

    private void applyCellState(View card, View badge, boolean selected, boolean upgraded) {
        if (badge != null) {
            badge.setVisibility(upgraded ? View.VISIBLE : View.GONE);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(16));
        if (upgraded) {
            bg.setColor(0x26FFD24A);
            bg.setStroke(AndroidUtilities.dp(2), 0xffFFD24A);
        } else if (selected) {
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText), 0.16f));
            bg.setStroke(AndroidUtilities.dp(2), Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        } else {
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.06f));
        }
        card.setBackground(bg);
    }
}
