package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaSocial;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

/*
 * Libre — что это за файл:
 * Выбор статуса: в сети, нет на месте, не беспокоить, невидимка, свой текст.
 */

/**
 * Prizma: statuses like in Discord — В сети, Нет на месте, Не беспокоить, Невидимка — plus a short
 * text of your own and when it should clear. Other Libre users see it in the Libre block of your
 * profile; «Не беспокоить» also silences notifications here, «Невидимка» hides «в сети».
 */
public final class PrizmaStatusSheet {

    private PrizmaStatusSheet() {}

    private static final String[] SUBTITLES = {
            "Обычный статус",
            "Видно, что вы отошли",
            "Уведомления на этом телефоне замолкают",
            "Статус не виден, «в сети» скрыто"
    };
    private static final String[] DURATIONS = {"Не сбрасывать", "30 минут", "1 час", "4 часа", "Сегодня"};

    /** A status dot, the way Discord draws it. */
    static View dot(Context ctx, int status, int sizeDp) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        int s = Math.max(0, Math.min(PrizmaSocial.STATUS_COLORS.length - 1, status));
        d.setColor(PrizmaSocial.STATUS_COLORS[s]);
        v.setBackground(d);
        return v;
    }

    public static void show(Context ctx, int account, Theme.ResourcesProvider rp, Runnable onChanged) {
        final Object[] cur = PrizmaSocial.myStatus(account);
        final int[] picked = {(Integer) cur[0]};
        final int[] duration = {0};
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);

        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        TextView title = new TextView(ctx);
        title.setText("Статус");
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        v.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 2));
        TextView sub = new TextView(ctx);
        sub.setText("Его видят пользователи Libre в вашем профиле");
        sub.setTextColor(gray);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 10));

        final LinearLayout[] rows = new LinearLayout[4];
        final Runnable[] restyle = new Runnable[1];
        for (int i = 0; i < 4; i++) {
            final int s = i;
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(9), AndroidUtilities.dp(12), AndroidUtilities.dp(9));
            row.addView(dot(ctx, s, 12), LayoutHelper.createLinear(12, 12, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            LinearLayout texts = new LinearLayout(ctx);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView n = new TextView(ctx);
            n.setText(PrizmaSocial.statusName(s));
            n.setTextColor(text);
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            texts.addView(n);
            TextView d = new TextView(ctx);
            d.setText(SUBTITLES[s]);
            d.setTextColor(gray);
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            texts.addView(d);
            row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            row.setOnClickListener(x -> {
                picked[0] = s;
                restyle[0].run();
            });
            rows[i] = row;
            v.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        }
        restyle[0] = () -> {
            for (int i = 0; i < 4; i++) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(12));
                bg.setColor(Theme.multAlpha(text, i == picked[0] ? 0.08f : 0f));
                if (i == picked[0]) bg.setStroke(AndroidUtilities.dp(1), Theme.multAlpha(text, 0.18f));
                rows[i].setBackground(bg);
            }
        };
        restyle[0].run();

        final EditTextBoldCursor field = new EditTextBoldCursor(ctx);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(text);
        field.setHintTextColor(gray);
        field.setHint("Свой текст, например «на паре до 15:00»");
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(64)});
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setCursorColor(text);
        field.setCursorSize(AndroidUtilities.dp(20));
        field.setCursorWidth(1.5f);
        field.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, rp),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, rp),
                Theme.getColor(Theme.key_text_RedBold, rp));
        field.setText((String) cur[1]);
        v.addView(field, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 4, 10, 4, 6));

        TextView clearLabel = new TextView(ctx);
        clearLabel.setText("Сбросить через");
        clearLabel.setTextColor(gray);
        clearLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        v.addView(clearLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 8, 4, 6));
        android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(ctx);
        chipsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(ctx);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chipViews = new TextView[DURATIONS.length];
        final Runnable[] restyleChips = new Runnable[1];
        for (int i = 0; i < DURATIONS.length; i++) {
            final int k = i;
            TextView c = new TextView(ctx);
            c.setText(DURATIONS[i]);
            c.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            c.setGravity(Gravity.CENTER);
            c.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
            c.setOnClickListener(x -> {
                duration[0] = k;
                restyleChips[0].run();
            });
            chipViews[i] = c;
            chips.addView(c, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 6, 0));
        }
        restyleChips[0] = () -> {
            for (int i = 0; i < chipViews.length; i++) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(AndroidUtilities.dp(16));
                boolean on = i == duration[0];
                bg.setColor(Theme.multAlpha(text, on ? 0.12f : 0.05f));
                chipViews[i].setBackground(bg);
                chipViews[i].setTextColor(on ? text : gray);
            }
        };
        restyleChips[0].run();
        chipsScroll.addView(chips);
        v.addView(chipsScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 32, 4, 0, 4, 14));

        TextView save = new TextView(ctx);
        save.setText("Сохранить");
        save.setGravity(Gravity.CENTER);
        save.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, rp));
        save.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        save.setTypeface(AndroidUtilities.bold());
        save.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        v.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        BottomSheet.Builder b = new BottomSheet.Builder(ctx, true, rp);
        b.setCustomView(v);
        final BottomSheet sheet = b.create();
        save.setOnClickListener(x -> {
            String t = field.getText().toString().trim();
            long until = untilFor(duration[0]);
            sheet.dismiss();
            PrizmaSocial.setMyStatus(account, picked[0], t, until, err -> {
                if (err != null) Toast.makeText(ctx, err, Toast.LENGTH_LONG).show();
            });
            if (onChanged != null) onChanged.run();
            if (picked[0] == PrizmaSocial.STATUS_DND) {
                Toast.makeText(ctx, "Уведомления замолчат, пока стоит «Не беспокоить»", Toast.LENGTH_SHORT).show();
            }
        });
        sheet.show();
    }

    /** Unix seconds when the status clears, 0 = never. */
    private static long untilFor(int i) {
        long now = System.currentTimeMillis() / 1000;
        switch (i) {
            case 1: return now + 30 * 60;
            case 2: return now + 3600;
            case 3: return now + 4 * 3600;
            case 4: {
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.set(java.util.Calendar.HOUR_OF_DAY, 23);
                c.set(java.util.Calendar.MINUTE, 59);
                c.set(java.util.Calendar.SECOND, 59);
                return c.getTimeInMillis() / 1000;
            }
            default: return 0;
        }
    }
}
