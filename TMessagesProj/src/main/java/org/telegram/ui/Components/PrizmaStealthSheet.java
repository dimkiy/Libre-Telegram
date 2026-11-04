package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.OvershootInterpolator;

import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PrizmaSettingsActivity;

public class PrizmaStealthSheet {
    public static void show(Context context, BaseFragment fragment) {
        if (fragment != null) {
            fragment.presentFragment(new PrizmaSettingsActivity());
        }
    }

    public static LinearLayout paletteRow(Context context) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(12));

        final Swatch[] swatches = new Swatch[PrizmaWallpaper.PALETTES.length];
        for (int i = 0; i < swatches.length; i++) {
            final int index = i;
            Swatch s = new Swatch(context, index);
            swatches[i] = s;
            s.setOnClickListener(v -> {
                PrizmaConfig.setLiveWallpaperPalette(index);
                PrizmaWallpaper.invalidate();
                for (Swatch other : swatches) {
                    other.invalidate();
                }
            });
            row.addView(s, LayoutHelper.createLinear(44, 44, 0, 0, i == swatches.length - 1 ? 0 : 12, 0));
        }
        return row;
    }

    private static class Swatch extends View {
        private final int index;
        private final MotionBackgroundDrawable preview;
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        Swatch(Context context, int index) {
            super(context);
            this.index = index;
            this.preview = PrizmaWallpaper.preview(index);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int pad = AndroidUtilities.dp(2);
            bounds.set(pad, pad, getWidth() - pad, getHeight() - pad);
            float r = bounds.width() / 2f;
            clip.reset();
            clip.addCircle(bounds.centerX(), bounds.centerY(), r, android.graphics.Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            preview.setBounds((int) bounds.left, (int) bounds.top, (int) bounds.right, (int) bounds.bottom);
            preview.draw(canvas);
            canvas.restore();
            if (index == PrizmaWallpaper.clampPalette(PrizmaConfig.liveWallpaperPalette())) {
                ringPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                canvas.drawCircle(bounds.centerX(), bounds.centerY(), r + AndroidUtilities.dp(1f), ringPaint);
            }
        }
    }

    public static void showIconPicker(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(14));

        TextView title = new TextView(context);
        title.setText("Иконка приложения");
        title.setTextColor(textColor);
        title.setTextSize(19);
        title.setTypeface(AndroidUtilities.bold());
        title.setPadding(AndroidUtilities.dp(6), 0, 0, AndroidUtilities.dp(10));
        root.addView(title);

        final BottomSheet[] ref = new BottomSheet[1];
        LinearLayout own = new LinearLayout(context);
        own.setOrientation(LinearLayout.HORIZONTAL);
        own.setGravity(Gravity.CENTER_VERTICAL);
        own.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        own.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
        android.widget.ImageView plus = new android.widget.ImageView(context);
        plus.setImageResource(org.telegram.messenger.R.drawable.msg_gallery);
        plus.setColorFilter(textColor);
        plus.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        android.graphics.drawable.GradientDrawable plusBg = new android.graphics.drawable.GradientDrawable();
        plusBg.setCornerRadius(AndroidUtilities.dp(11));
        plusBg.setColor(Theme.multAlpha(textColor, 0.07f));
        plus.setBackground(plusBg);
        own.addView(plus, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        LinearLayout ownTexts = new LinearLayout(context);
        ownTexts.setOrientation(LinearLayout.VERTICAL);
        TextView ownName = new TextView(context);
        ownName.setText("Своя картинка");
        ownName.setTextColor(textColor);
        ownName.setTextSize(16);
        ownTexts.addView(ownName);
        TextView ownSub = new TextView(context);
        ownSub.setText("любая картинка из галереи — иконкой Libre на рабочем столе");
        ownSub.setTextColor(grayColor);
        ownSub.setTextSize(13);
        ownTexts.addView(ownSub);
        own.addView(ownTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
        own.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            if (context instanceof android.app.Activity) PrizmaAppIcon.pick((android.app.Activity) context);
        });
        root.addView(own, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (org.telegram.ui.LauncherIconController.LauncherIcon icon : org.telegram.ui.LauncherIconController.LauncherIcon.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
            row.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));

            android.widget.FrameLayout preview = new android.widget.FrameLayout(context);
            preview.setClipToOutline(true);
            android.graphics.drawable.GradientDrawable clip = new android.graphics.drawable.GradientDrawable();
            clip.setCornerRadius(AndroidUtilities.dp(11));
            preview.setBackground(clip);
            android.widget.ImageView bgIv = new android.widget.ImageView(context);
            bgIv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            try { bgIv.setImageResource(icon.background); } catch (Throwable ignore) {}
            preview.addView(bgIv, LayoutHelper.createFrame(46, 46));
            android.widget.ImageView fgIv = new android.widget.ImageView(context);
            fgIv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            try { fgIv.setImageResource(icon.foreground); } catch (Throwable ignore) {}
            preview.addView(fgIv, LayoutHelper.createFrame(46, 46));
            android.view.ViewOutlineProvider op = new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(11));
                }
            };
            preview.setOutlineProvider(op);
            row.addView(preview, LayoutHelper.createLinear(46, 46, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));

            TextView name = new TextView(context);
            try { name.setText(getString(icon.title)); } catch (Throwable e) { name.setText(icon.key); }
            name.setTextColor(textColor);
            name.setTextSize(16);
            row.addView(name, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

            if (org.telegram.ui.LauncherIconController.isEnabled(icon)) {
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextColor(0xff4CC764);
                check.setTextSize(18);
                check.setTypeface(AndroidUtilities.bold());
                row.addView(check, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            }

            final org.telegram.ui.LauncherIconController.LauncherIcon fIcon = icon;
            row.setOnClickListener(v -> {
                try {
                    org.telegram.ui.LauncherIconController.setIcon(fIcon);
                    android.widget.Toast.makeText(context, "Иконка изменена", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignore) {}
                if (ref[0] != null) ref[0].dismiss();
            });
            root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        ScrollView scroll = new ScrollView(context);
        scroll.addView(root, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(scroll);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static String getString(int res) {
        return org.telegram.messenger.LocaleController.getString(res);
    }

    public static void showPhoneRoller(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(16));

        TextView title = new TextView(context);
        title.setText("Анонимный номер");
        title.setTextColor(textColor);
        title.setTextSize(20);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

        final TextView numberView = new TextView(context);
        numberView.setText(PrizmaConfig.fakePhoneNumber());
        numberView.setTextColor(textColor);
        numberView.setTextSize(30);
        numberView.setTypeface(AndroidUtilities.bold());
        numberView.setLetterSpacing(0.04f);
        root.addView(numberView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        TextView hint = new TextView(context);
        hint.setText("Крути, пока не понравится, и нажми «Выбрать».");
        hint.setTextColor(grayColor);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 22));

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        final TextView rollBtn = rollerButton(context, "Крутить", false);
        final TextView chooseBtn = rollerButton(context, "Выбрать", true);
        buttons.addView(rollBtn, LayoutHelper.createLinear(0, 48, 1f, 0, 0, 5, 0));
        buttons.addView(chooseBtn, LayoutHelper.createLinear(0, 48, 1f, 5, 0, 0, 0));
        root.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final BottomSheet[] ref = new BottomSheet[1];
        final String[] current = {PrizmaConfig.fakePhoneNumber()};
        final boolean[] spinning = {false};

        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        rollBtn.setOnClickListener(v -> {
            if (spinning[0]) return;
            spinning[0] = true;
            rollBtn.setAlpha(0.6f);
            final int totalSteps = 26;
            final int[] step = {0};
            final String[] fin = {PrizmaConfig.randomFakePhoneNumber()};
            final Runnable[] tick = new Runnable[1];
            tick[0] = () -> {
                boolean done = step[0] >= totalSteps;
                String s = done ? fin[0] : PrizmaConfig.randomFakePhoneNumber();
                numberView.animate().cancel();
                numberView.setText(s);
                numberView.setTranslationY(-AndroidUtilities.dp(14));
                numberView.setAlpha(0.35f);
                numberView.animate().translationY(0f).alpha(1f)
                        .setDuration(done ? 260 : 70)
                        .setInterpolator(done ? new OvershootInterpolator(2.4f) : CubicBezierInterpolator.EASE_OUT)
                        .start();
                if (step[0] % 5 == 0) {
                    try { numberView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignore) {}
                }
                if (done) {
                    current[0] = fin[0];
                    spinning[0] = false;
                    rollBtn.setAlpha(1f);
                    numberView.setTextColor(accent);
                    numberView.setScaleX(0.9f); numberView.setScaleY(0.9f);
                    numberView.animate().scaleX(1f).scaleY(1f).setDuration(300)
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                    AndroidUtilities.runOnUIThread(() -> numberView.setTextColor(textColor), 550);
                    try { numberView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
                    return;
                }
                long delay = 40 + (long) (step[0] * step[0] * 0.9f);
                step[0]++;
                AndroidUtilities.runOnUIThread(tick[0], delay);
            };
            AndroidUtilities.runOnUIThread(tick[0], 30);
        });

        chooseBtn.setOnClickListener(v -> {
            if (spinning[0]) return;
            PrizmaConfig.setFakePhoneNumber(current[0]);
            PrizmaConfig.setFakePhone(true);
            try {
                int acc = org.telegram.messenger.UserConfig.selectedAccount;
                org.telegram.messenger.NotificationCenter.getInstance(acc).postNotificationName(
                        org.telegram.messenger.NotificationCenter.updateInterfaces,
                        org.telegram.messenger.MessagesController.UPDATE_MASK_NAME);
            } catch (Throwable ignore) {}
            if (ref[0] != null) ref[0].dismiss();
            android.widget.Toast.makeText(context, "Номер выбран: " + current[0], android.widget.Toast.LENGTH_SHORT).show();
        });

        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static TextView rollerButton(Context context, String text, boolean filled) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(15);
        t.setTypeface(AndroidUtilities.bold());
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(13));
        if (filled) {
            bg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            t.setTextColor(0xffffffff);
        } else {
            bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.08f));
            t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        t.setBackground(bg);
        return t;
    }

    public static void showStickerSize(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Обычный (100%)", "Поменьше (85%)", "Маленький (70%)", "Крошечный (55%)"};
        float[] vals = {1f, 0.85f, 0.7f, 0.55f};
        float cur = PrizmaConfig.stickerScale();
        for (int i = 0; i < names.length; i++) {
            final float v = vals[i];
            boolean sel = Math.abs(cur - v) < 0.02f;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + (sel ? "  ✓" : ""), "", () -> {
                        PrizmaConfig.setStickerScale(v);
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Размер стикеров изменён (применится при открытии чата)", android.widget.Toast.LENGTH_SHORT).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    public static void showBubbleShape(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Острые", "Средние", "Скруглённые", "Круглые"};
        int[] vals = {3, 8, 13, 17};
        int cur = org.telegram.messenger.SharedConfig.bubbleRadius;
        for (int i = 0; i < names.length; i++) {
            final int v = vals[i];
            boolean sel = Math.abs(cur - v) <= 2;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + " (" + v + ")" + (sel ? "  ✓" : ""), "", () -> {
                        org.telegram.messenger.SharedConfig.bubbleRadius = v;
                        try {
                            org.telegram.messenger.ApplicationLoader.applicationContext
                                    .getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
                                    .edit().putInt("bubbleRadius", v).apply();
                        } catch (Throwable ignore) {}
                        try { Theme.createCommonMessageResources(); } catch (Throwable ignore) {}
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Форма пузырей изменена (применится при открытии чата)", android.widget.Toast.LENGTH_SHORT).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    public static void showEmojiSize(Context context) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int accent = Theme.getColor(Theme.key_featuredStickers_addButton);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(10));
        final BottomSheet[] ref = new BottomSheet[1];

        String[] names = {"Обычный (100%)", "Поменьше (75%)", "Маленький (55%)", "Крошечный (40%)"};
        float[] vals = {1f, 0.75f, 0.55f, 0.4f};
        float cur = PrizmaConfig.emojiScale();
        for (int i = 0; i < names.length; i++) {
            final float v = vals[i];
            boolean sel = Math.abs(cur - v) < 0.02f;
            root.addView(actionRow(context, sel ? accent : textColor, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText),
                    names[i] + (sel ? "  ✓" : ""), "", () -> {
                        PrizmaConfig.setEmojiScale(v);
                        try { Theme.createCommonMessageResources(); } catch (Throwable ignore) {}
                        try {
                            org.telegram.messenger.NotificationCenter.getGlobalInstance().postNotificationName(
                                    org.telegram.messenger.NotificationCenter.emojiLoaded);
                        } catch (Throwable ignore) {}
                        if (ref[0] != null) ref[0].dismiss();
                        android.widget.Toast.makeText(context, "Размер эмодзи сохранён. Перезапусти приложение, чтобы применить ко всем чатам", android.widget.Toast.LENGTH_LONG).show();
                    }));
        }
        BottomSheet.Builder b = new BottomSheet.Builder(context);
        b.setCustomView(root);
        ref[0] = b.create();
        try { ref[0].fixNavigationBar(Theme.getColor(Theme.key_windowBackgroundWhite)); } catch (Throwable ignore) {}
        ref[0].show();
    }

    private static LinearLayout actionRow(Context context, int textColor, int grayColor,
                                          String name, String desc, Runnable onClick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(textColor);
        n.setTextSize(15);
        texts.addView(n);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextColor(grayColor);
        d.setTextSize(12);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        row.setOnClickListener(v -> onClick.run());
        return row;
    }
}
