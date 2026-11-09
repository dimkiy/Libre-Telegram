package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
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
import org.telegram.messenger.Utilities;

import java.security.MessageDigest;

public class PrizmaArchiveLock {
    private static final int MAX_FAILS = 5;
    private static final long COOLDOWN_MS = 30000;
    private static int fails;
    private static long blockedUntil;

    private PrizmaArchiveLock() {
    }

    public static boolean isLocked() {
        return PrizmaConfig.archiveLock() && PrizmaConfig.hasArchivePin();
    }

    public static void verify(Context ctx, Runnable onSuccess) {
        if (!isLocked()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        new Pad(ctx, false, onSuccess).show();
    }

    public static void setupPin(Context ctx, Runnable onDone) {
        new Pad(ctx, true, onDone).show();
    }

    public static void disable() {
        PrizmaConfig.setArchiveLock(false);
        PrizmaConfig.setArchivePinHash("");
        PrizmaConfig.setArchivePinLength(0);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(("prizma_arch_" + s).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(Integer.toHexString((b & 0xff) | 0x100).substring(1));
            return sb.toString();
        } catch (Throwable e) {
            return s;
        }
    }

    private static final class Pad {
        private final Context ctx;
        private final boolean setup;
        private final Runnable done;
        private final Dialog dialog;
        private final StringBuilder entered = new StringBuilder();
        private PrizmaGlassFrame root;
        private LinearLayout content;
        private LockGlyph lock;
        private TextView title, hint, action;
        private Dots dots;
        private TextView erase;
        private String firstPin;
        private boolean busy;
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                long left = blockedUntil - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    hint.setText(setup ? "" : "Введите код-пароль");
                    return;
                }
                hint.setText("Слишком много попыток. Подождите " + (left / 1000 + 1) + " с");
                AndroidUtilities.runOnUIThread(this, 250);
            }
        };

        Pad(Context ctx, boolean setup, Runnable done) {
            this.ctx = ctx;
            this.setup = setup;
            this.done = done;
            this.dialog = new Dialog(ctx);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            build();
        }

        private int expected() {
            if (setup) return firstPin != null ? firstPin.length() : 0;
            return PrizmaConfig.archivePinLength();
        }

        private void build() {
            root = new PrizmaGlassFrame(ctx);
            root.setLiquidTint(0x2effffff);

            ImageView bg = new ImageView(ctx);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try {
                android.app.Activity act = AndroidUtilities.findActivity(ctx);
                if (act != null && act.getWindow() != null) {
                    View decor = act.getWindow().getDecorView();
                    int div = 6;
                    int w = Math.max(1, decor.getWidth() / div), h = Math.max(1, decor.getHeight() / div);
                    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(b);
                    c.scale(1f / div, 1f / div);
                    decor.draw(c);
                    Utilities.stackBlurBitmap(b, 12);
                    bg.setImageBitmap(b);
                }
            } catch (Throwable ignore) {
            }
            root.addView(bg, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            root.setSource(bg);

            View dim = new View(ctx) {
                private final Paint p = new Paint();

                @Override
                protected void onDraw(Canvas canvas) {
                    if (p.getShader() == null || getHeight() != (int) p.getStrokeMiter()) {
                        p.setStrokeMiter(getHeight());
                        p.setShader(new LinearGradient(0, 0, 0, getHeight(), new int[]{0x99101216, 0x80101216, 0xcc0b0c0f}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                    }
                    canvas.drawRect(0, 0, getWidth(), getHeight(), p);
                }
            };
            root.addView(dim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            content = new LinearLayout(ctx);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            content.setPadding(0, 0, 0, AndroidUtilities.dp(24));

            lock = new LockGlyph(ctx);
            content.addView(lock, LayoutHelper.createLinear(34, 40, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

            title = new TextView(ctx);
            title.setTextColor(0xffffffff);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
            title.setTypeface(AndroidUtilities.bold());
            title.setGravity(Gravity.CENTER);
            content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 6));

            hint = new TextView(ctx);
            hint.setTextColor(0xb3ffffff);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            hint.setGravity(Gravity.CENTER);
            content.addView(hint, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 0, 24, 22));

            dots = new Dots(ctx);
            content.addView(dots, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 22, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

            action = new TextView(ctx);
            action.setTextColor(0xffffffff);
            action.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            action.setTypeface(AndroidUtilities.bold());
            action.setGravity(Gravity.CENTER);
            action.setPadding(AndroidUtilities.dp(22), 0, AndroidUtilities.dp(22), 0);
            action.setOnClickListener(v -> submit());
            action.setAlpha(0f);
            action.setScaleX(0.85f);
            action.setScaleY(0.85f);
            action.setVisibility(View.INVISIBLE);
            PrizmaFeedKit.press(action, 0.92f);
            content.addView(action, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));
            root.addPane(action, AndroidUtilities.dp(20), true);

            String[] digits = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
            String[] letters = {"", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};
            int index = 0;
            for (int r = 0; r < 3; r++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int c = 0; c < 3; c++) {
                    View key = key(digits[index], letters[index], index);
                    row.addView(key, LayoutHelper.createLinear(78, 78, 12, 8, 12, 8));
                    index++;
                }
                content.addView(row, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            }
            LinearLayout last = new LinearLayout(ctx);
            last.setOrientation(LinearLayout.HORIZONTAL);
            last.setGravity(Gravity.CENTER_VERTICAL);
            TextView cancel = plain("Отмена");
            cancel.setOnClickListener(v -> close(false));
            last.addView(cancel, LayoutHelper.createLinear(78, 78, 12, 8, 12, 8));
            last.addView(key("0", "", 9), LayoutHelper.createLinear(78, 78, 12, 8, 12, 8));
            erase = plain("Стереть");
            erase.setOnClickListener(v -> {
                if (busy || entered.length() == 0) return;
                entered.deleteCharAt(entered.length() - 1);
                dots.set(entered.length(), slots(), true);
                syncButtons();
            });
            erase.setOnLongClickListener(v -> {
                entered.setLength(0);
                dots.set(0, slots(), true);
                syncButtons();
                return true;
            });
            last.addView(erase, LayoutHelper.createLinear(78, 78, 12, 8, 12, 8));
            content.addView(last, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            root.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            title.setText(setup ? "Код для архива" : "Архив закрыт");
            hint.setText(setup ? "Придумайте код из 4–8 цифр" : "Введите код-пароль");
            dots.set(0, slots(), false);
            syncButtons();

            dialog.setContentView(root, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            dialog.setOnDismissListener(d -> AndroidUtilities.cancelRunOnUIThread(tick));
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                window.setDimAmount(0f);
                try {
                    window.setStatusBarColor(0);
                    window.setNavigationBarColor(0);
                    window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                } catch (Throwable ignore) {
                }
            }
        }

        private int slots() {
            int e = expected();
            return e > 0 ? e : Math.max(4, entered.length());
        }

        private TextView plain(String text) {
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(0xffffffff);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            t.setGravity(Gravity.CENTER);
            PrizmaFeedKit.press(t, 0.9f);
            return t;
        }

        private View key(String digit, String letters, int order) {
            FrameLayout k = new FrameLayout(ctx) {
                private final Paint flash = new Paint(Paint.ANTI_ALIAS_FLAG);
                private float glow;
                private ValueAnimator anim;

                @Override
                public boolean onTouchEvent(MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) fade(1f, 60);
                    else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) fade(0f, 420);
                    return super.onTouchEvent(e);
                }

                private void fade(float to, long ms) {
                    if (anim != null) anim.cancel();
                    anim = ValueAnimator.ofFloat(glow, to);
                    anim.setDuration(ms);
                    anim.addUpdateListener(a -> {
                        glow = (float) a.getAnimatedValue();
                        invalidate();
                    });
                    anim.start();
                }

                @Override
                protected void dispatchDraw(Canvas canvas) {
                    if (glow > 0.01f) {
                        flash.setColor(Color.argb((int) (90 * glow), 255, 255, 255));
                        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) / 2f, flash);
                    }
                    super.dispatchDraw(canvas);
                }
            };
            k.setWillNotDraw(false);
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            TextView d = new TextView(ctx);
            d.setText(digit);
            d.setTextColor(0xffffffff);
            d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 32);
            d.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            d.setIncludeFontPadding(false);
            d.setGravity(Gravity.CENTER);
            col.addView(d);
            if (!TextUtils.isEmpty(letters)) {
                TextView l = new TextView(ctx);
                l.setText(letters);
                l.setTextColor(0xccffffff);
                l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9.5f);
                l.setLetterSpacing(0.18f);
                l.setTypeface(AndroidUtilities.bold());
                l.setGravity(Gravity.CENTER);
                col.addView(l);
            }
            k.addView(col, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            k.setOnClickListener(v -> type(digit, v));
            PrizmaFeedKit.press(k, 0.9f);
            root.addPane(k, AndroidUtilities.dp(39), true);
            k.setTag(order);
            return k;
        }

        private void type(String digit, View v) {
            if (busy) return;
            if (SystemClock.elapsedRealtime() < blockedUntil) {
                shake();
                return;
            }
            try {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            } catch (Throwable ignore) {
            }
            if (entered.length() >= 8) return;
            entered.append(digit);
            dots.set(entered.length(), slots(), true);
            syncButtons();
            int e = expected();
            if (e > 0 && entered.length() == e) AndroidUtilities.runOnUIThread(this::submit, 140);
        }

        private void syncButtons() {
            boolean manual = expected() == 0;
            boolean showAction = manual && entered.length() >= 4;
            action.setText(setup ? "Далее" : "Открыть");
            if (showAction && action.getVisibility() != View.VISIBLE) {
                action.setVisibility(View.VISIBLE);
                action.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2f))
                        .setUpdateListener(a -> root.invalidate()).start();
            } else if (!showAction && action.getVisibility() == View.VISIBLE) {
                action.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(160).setUpdateListener(a -> root.invalidate())
                        .withEndAction(() -> action.setVisibility(View.INVISIBLE)).start();
            }
            erase.animate().alpha(entered.length() > 0 ? 1f : 0.35f).setDuration(150).start();
        }

        private void submit() {
            if (busy) return;
            String pin = entered.toString();
            if (pin.length() < 4) {
                shake();
                return;
            }
            if (setup) {
                if (firstPin == null) {
                    firstPin = pin;
                    entered.setLength(0);
                    swapText("Повторите код", "Введите тот же код ещё раз");
                    dots.set(0, slots(), true);
                    syncButtons();
                    return;
                }
                if (!pin.equals(firstPin)) {
                    firstPin = null;
                    shake();
                    swapText("Код для архива", "Коды не совпали. Придумайте заново");
                    entered.setLength(0);
                    AndroidUtilities.runOnUIThread(() -> {
                        dots.set(0, slots(), true);
                        syncButtons();
                    }, 420);
                    return;
                }
                PrizmaConfig.setArchivePinHash(sha256(pin));
                PrizmaConfig.setArchivePinLength(pin.length());
                PrizmaConfig.setArchiveLock(true);
                success();
                return;
            }
            if (sha256(pin).equals(PrizmaConfig.archivePinHash())) {
                fails = 0;
                if (PrizmaConfig.archivePinLength() == 0) PrizmaConfig.setArchivePinLength(pin.length());
                success();
            } else {
                fails++;
                shake();
                entered.setLength(0);
                if (fails >= MAX_FAILS) {
                    fails = 0;
                    blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
                    tick.run();
                } else {
                    hint.setText("Неверный код. Осталось попыток: " + (MAX_FAILS - fails));
                }
                AndroidUtilities.runOnUIThread(() -> {
                    dots.set(0, slots(), true);
                    syncButtons();
                }, 420);
            }
        }

        private void swapText(String t, String h) {
            title.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                title.setText(t);
                title.animate().alpha(1f).setDuration(200).start();
            }).start();
            hint.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                hint.setText(h);
                hint.animate().alpha(1f).setDuration(200).start();
            }).start();
        }

        private void shake() {
            dots.error();
            try {
                dots.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } catch (Throwable ignore) {
            }
            android.animation.ObjectAnimator s = android.animation.ObjectAnimator.ofFloat(dots, View.TRANSLATION_X,
                    0, AndroidUtilities.dp(14), -AndroidUtilities.dp(12), AndroidUtilities.dp(9), -AndroidUtilities.dp(6), AndroidUtilities.dp(3), 0);
            s.setDuration(420);
            s.start();
        }

        private void success() {
            busy = true;
            lock.open();
            dots.fillAll();
            try {
                lock.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(() -> close(true), 380);
        }

        private void close(boolean ok) {
            content.animate().alpha(0f).scaleX(ok ? 1.08f : 0.94f).scaleY(ok ? 1.08f : 0.94f).setDuration(220)
                    .setUpdateListener(a -> root.invalidate()).start();
            root.animate().alpha(0f).setDuration(240).setStartDelay(60).withEndAction(() -> {
                try {
                    dialog.dismiss();
                } catch (Throwable ignore) {
                }
                if (ok && done != null) done.run();
            }).start();
        }

        void show() {
            try {
                dialog.show();
            } catch (Throwable ignore) {
                return;
            }
            content.setAlpha(0f);
            content.setScaleX(0.94f);
            content.setScaleY(0.94f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                    .setUpdateListener(a -> root.invalidate()).start();
            if (!setup && SystemClock.elapsedRealtime() < blockedUntil) tick.run();
        }
    }

    private static final class Dots extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float[] level = new float[0];
        private int filled;
        private int count;
        private float red;

        Dots(Context ctx) {
            super(ctx);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(AndroidUtilities.dpf2(1.4f));
        }

        void set(int filled, int count, boolean animated) {
            if (count != this.count) {
                float[] next = new float[count];
                System.arraycopy(level, 0, next, 0, Math.min(level.length, count));
                level = next;
                this.count = count;
            }
            this.filled = filled;
            for (int i = 0; i < count; i++) {
                final int idx = i;
                float target = i < filled ? 1f : 0f;
                if (!animated) {
                    level[i] = target;
                    continue;
                }
                if (level[i] == target) continue;
                ValueAnimator a = ValueAnimator.ofFloat(level[i], target);
                a.setDuration(target > 0 ? 260 : 160);
                a.setInterpolator(target > 0 ? new OvershootInterpolator(3f) : CubicBezierInterpolator.EASE_OUT);
                a.addUpdateListener(an -> {
                    if (idx < level.length) level[idx] = (float) an.getAnimatedValue();
                    invalidate();
                });
                a.start();
            }
            invalidate();
        }

        void fillAll() {
            set(count, count, true);
        }

        void error() {
            ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
            a.setDuration(700);
            a.addUpdateListener(an -> {
                red = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (count == 0) return;
            float r = AndroidUtilities.dpf2(7);
            float gap = AndroidUtilities.dpf2(22);
            float total = (count - 1) * gap;
            float x0 = getWidth() / 2f - total / 2f;
            float cy = getHeight() / 2f;
            int color = red > 0 ? blend(0xffffffff, 0xffff5a5f, red) : 0xffffffff;
            ring.setColor(color);
            fill.setColor(color);
            for (int i = 0; i < count; i++) {
                float cx = x0 + i * gap;
                float v = level[i];
                canvas.drawCircle(cx, cy, r - ring.getStrokeWidth() / 2f, ring);
                if (v > 0.01f) canvas.drawCircle(cx, cy, r * Math.max(0f, v), fill);
            }
        }

        private static int blend(int a, int b, float t) {
            return Color.argb(255,
                    (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                    (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                    (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }
    }

    private static final class LockGlyph extends View {
        private final Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint shackle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private float open;

        LockGlyph(Context ctx) {
            super(ctx);
            body.setColor(0xffffffff);
            shackle.setColor(0xffffffff);
            shackle.setStyle(Paint.Style.STROKE);
            shackle.setStrokeCap(Paint.Cap.ROUND);
        }

        void open() {
            ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(340);
            a.setInterpolator(new OvershootInterpolator(2.2f));
            a.addUpdateListener(an -> {
                open = (float) an.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float bw = w * 0.86f, bh = h * 0.5f;
            float left = (w - bw) / 2f, top = h - bh;
            rect.set(left, top, left + bw, h);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(5), AndroidUtilities.dp(5), body);
            float sw = w * 0.13f;
            shackle.setStrokeWidth(sw);
            float sr = bw * 0.3f;
            float cx = w / 2f;
            float lift = open * h * 0.16f;
            canvas.save();
            canvas.translate(0, -lift);
            canvas.rotate(-open * 18f, cx + sr, top);
            path.rewind();
            path.moveTo(cx - sr, top + sw * 0.2f);
            path.lineTo(cx - sr, top - h * 0.18f);
            rect.set(cx - sr, top - h * 0.18f - sr, cx + sr, top - h * 0.18f + sr);
            path.arcTo(rect, 180f, 180f);
            path.lineTo(cx + sr, top + sw * 0.2f);
            canvas.drawPath(path, shackle);
            canvas.restore();
        }
    }
}
