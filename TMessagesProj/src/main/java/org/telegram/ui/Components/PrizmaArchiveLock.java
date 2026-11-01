package org.telegram.ui.Components;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

import java.security.MessageDigest;

/**
 * Prizma: a PIN lock for the chat archive, with a nice unlock screen — the background behind
 * blurs, and the keypad buttons appear one after another. The PIN is stored only as a SHA-256 hash.
 */
public class PrizmaArchiveLock {

    private PrizmaArchiveLock() {}

    public static boolean isLocked() {
        return PrizmaConfig.archiveLock() && PrizmaConfig.hasArchivePin();
    }

    public static void verify(Context ctx, Runnable onSuccess) {
        if (!isLocked()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        prompt(ctx, "Архив заблокирован", "Введите PIN", pin -> {
            if (sha256(pin).equals(PrizmaConfig.archivePinHash())) {
                if (onSuccess != null) onSuccess.run();
                return true;
            }
            return false;
        });
    }

    public static void setupPin(Context ctx, Runnable onDone) {
        prompt(ctx, "Пароль на архив", "Придумайте PIN (от 4 цифр)", pin -> {
            if (pin == null || pin.length() < 4) {
                return false;
            }
            PrizmaConfig.setArchivePinHash(sha256(pin));
            PrizmaConfig.setArchiveLock(true);
            if (onDone != null) onDone.run();
            return true;
        });
    }

    public static void disable() {
        PrizmaConfig.setArchiveLock(false);
        PrizmaConfig.setArchivePinHash("");
    }

    private interface PinCallback {
        boolean onPin(String pin);
    }

    private static void prompt(Context ctx, String titleText, String hintText, PinCallback cb) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout overlay = new FrameLayout(ctx);

        // blurred snapshot of what's behind
        try {
            android.app.Activity act = AndroidUtilities.findActivity(ctx);
            if (act != null && act.getWindow() != null) {
                View decor = act.getWindow().getDecorView();
                int w = Math.max(1, decor.getWidth() / 6), h = Math.max(1, decor.getHeight() / 6);
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                c.scale(1f / 6f, 1f / 6f);
                decor.draw(c);
                Utilities.stackBlurBitmap(b, 10);
                ImageView bgImage = new ImageView(ctx);
                bgImage.setImageBitmap(b);
                bgImage.setScaleType(ImageView.ScaleType.FIT_XY);
                overlay.addView(bgImage, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            }
        } catch (Throwable ignore) {}
        overlay.setBackgroundColor(0xAA000000); // scrim on top of blur

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), AndroidUtilities.dp(28));

        // lock icon
        ImageView lock = new ImageView(ctx);
        lock.setImageResource(R.drawable.ic_lock_white);
        lock.setColorFilter(0xffffffff);
        lock.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        column.addView(lock, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        TextView title = new TextView(ctx);
        title.setText(titleText);
        title.setTextColor(0xffffffff);
        title.setTextSize(21);
        title.setTypeface(AndroidUtilities.bold());
        title.setGravity(Gravity.CENTER);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 4));

        TextView hint = new TextView(ctx);
        hint.setText(hintText);
        hint.setTextColor(0x99ffffff);
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER);
        column.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        final LinearLayout dots = new LinearLayout(ctx);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        column.addView(dots, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 20, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 28));

        final StringBuilder entered = new StringBuilder();
        final Runnable updateDots = () -> {
            dots.removeAllViews();
            int count = Math.max(4, entered.length());
            for (int i = 0; i < count; i++) {
                View dot = new View(ctx);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                boolean filled = i < entered.length();
                dg.setColor(filled ? 0xffffffff : 0x00000000);
                dg.setStroke(AndroidUtilities.dp(1.5f), filled ? 0xffffffff : 0x66ffffff);
                dot.setBackground(dg);
                dots.addView(dot, LayoutHelper.createLinear(12, 12, Gravity.CENTER, 7, 0, 7, 0));
            }
        };
        updateDots.run();

        // keypad 3x4; buttons appear one after another
        LinearLayout pad = new LinearLayout(ctx);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);

        final boolean[] busy = {false};
        final Runnable[] submit = {null};
        final java.util.ArrayList<View> padButtons = new java.util.ArrayList<>();

        String[][] rows = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"⌫", "0", "✓"}};
        for (String[] r : rows) {
            LinearLayout rowLay = new LinearLayout(ctx);
            rowLay.setOrientation(LinearLayout.HORIZONTAL);
            for (String key : r) {
                final View btn = padButton(ctx, key);
                padButtons.add(btn);
                btn.setOnClickListener(v -> {
                    if (busy[0]) return;
                    try { v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP); } catch (Throwable ignore) {}
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70)
                            .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setInterpolator(new OvershootInterpolator(2.5f)).setDuration(220).start()).start();
                    if ("⌫".equals(key)) {
                        if (entered.length() > 0) entered.deleteCharAt(entered.length() - 1);
                        updateDots.run();
                    } else if ("✓".equals(key)) {
                        if (submit[0] != null) submit[0].run();
                    } else {
                        if (entered.length() < 12) {
                            entered.append(key);
                            updateDots.run();
                        }
                    }
                });
                rowLay.addView(btn, LayoutHelper.createLinear(68, 68, Gravity.CENTER, 8, 8, 8, 8));
            }
            pad.addView(rowLay, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        }
        column.addView(pad, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        TextView cancel = new TextView(ctx);
        cancel.setText("Отмена");
        cancel.setTextColor(0xbbffffff);
        cancel.setTextSize(15);
        cancel.setTypeface(AndroidUtilities.bold());
        cancel.setGravity(Gravity.CENTER);
        cancel.setOnClickListener(v -> dialog.dismiss());
        column.addView(cancel, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44, Gravity.CENTER_HORIZONTAL, 0, 18, 0, 0));

        overlay.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        submit[0] = () -> {
            if (busy[0]) return;
            busy[0] = true;
            if (cb.onPin(entered.toString())) {
                overlay.animate().alpha(0f).setDuration(180).withEndAction(dialog::dismiss).start();
            } else {
                busy[0] = false;
                // wrong → shake + clear
                dots.animate().cancel();
                dots.setTranslationX(0);
                android.animation.ObjectAnimator shake = android.animation.ObjectAnimator.ofFloat(dots, View.TRANSLATION_X,
                        0, AndroidUtilities.dp(10), -AndroidUtilities.dp(10), AndroidUtilities.dp(7), -AndroidUtilities.dp(7), 0);
                shake.setDuration(380);
                shake.start();
                try { dots.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
                entered.setLength(0);
                AndroidUtilities.runOnUIThread(updateDots, 120);
            }
        };

        dialog.setContentView(overlay, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0f);
            try {
                window.setStatusBarColor(0);
                window.setNavigationBarColor(0);
                window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
            } catch (Throwable ignore) {}
        }
        try {
            dialog.show();
        } catch (Throwable ignore) {
            return;
        }

        // staggered appearance of the keypad
        for (int i = 0; i < padButtons.size(); i++) {
            final View btn = padButtons.get(i);
            btn.setAlpha(0f);
            btn.setScaleX(0.7f);
            btn.setScaleY(0.7f);
            btn.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(60L + i * 35L)
                    .setDuration(260)
                    .setInterpolator(new OvershootInterpolator(1.8f))
                    .start();
        }
    }

    private static View padButton(Context ctx, String label) {
        TextView t = new TextView(ctx);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(AndroidUtilities.bold());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        if ("✓".equals(label)) {
            t.setText("OK");
            t.setTextSize(17);
            t.setTextColor(0xffffffff);
            bg.setColor(0xff4CC764); // green confirm
        } else if ("⌫".equals(label)) {
            t.setText("⌫");
            t.setTextSize(22);
            t.setTextColor(0xffffffff);
            bg.setColor(0x33E8534B); // soft red erase
            bg.setStroke(AndroidUtilities.dp(1.5f), 0x80E8534B);
        } else {
            t.setText(label);
            t.setTextSize(26);
            t.setTextColor(0xffffffff);
            bg.setColor(0x1fffffff);
        }
        t.setBackground(bg);
        return t;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("prizma_arch_" + s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(Integer.toHexString((b & 0xff) | 0x100).substring(1));
            }
            return sb.toString();
        } catch (Throwable e) {
            return s;
        }
    }
}
