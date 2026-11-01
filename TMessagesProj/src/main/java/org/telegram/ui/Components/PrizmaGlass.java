package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.Utilities;

import java.util.Random;

/**
 * Prizma: "liquid glass" material for our music screens (equalizer, add-to-playlist).
 *
 * Android before 12 can't blur what is behind a window, so the frost is made by hand: a downscaled,
 * blurred and slightly over-saturated snapshot of the screen behind fills the dialog, and the panels
 * on top are dark translucent glass with a lit rim, a top sheen and a faint grain (no banding).
 * On weak devices / Lite mode / battery saver the panels become solid and the motion shorter.
 */
public final class PrizmaGlass {

    public static final int TEXT = 0xffffffff;
    public static final int TEXT_2 = 0x99ffffff;   // secondary, 60%
    public static final int TEXT_3 = 0x5cffffff;   // hints, 36%
    public static final int DEFAULT_ACCENT = 0xffFF4D6D; // Libre music pink

    private PrizmaGlass() {}

    // ===================== frost =====================

    /** Downscaled + blurred copy of what is on screen behind; null if it can't be taken. */
    public static Bitmap snapshot(View source) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return null;
        }
        try {
            final int scale = 8;
            int w = Math.max(1, source.getWidth() / scale);
            int h = Math.max(1, source.getHeight() / scale);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(1f / scale, 1f / scale);
            source.draw(c);
            Utilities.stackBlurBitmap(b, 12);
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Full-screen frosted backdrop: the blurred snapshot, saturated, under a dark scrim for legibility. */
    public static class Backdrop extends View {
        private final Bitmap bitmap;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        public Backdrop(Context context, Bitmap bitmap) {
            super(context);
            this.bitmap = bitmap;
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.25f); // keep the cover colours alive through the blur, without going candy
            paint.setColorFilter(new ColorMatrixColorFilter(cm));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap != null && !bitmap.isRecycled()) {
                dst.set(0, 0, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, null, dst, paint);
            } else {
                canvas.drawColor(0xff15161c);
            }
            canvas.drawColor(0x5c000000); // scrim: white text stays readable even over a white album cover
        }
    }

    // ===================== glass surface =====================

    private static Bitmap grainBitmap;

    private static Bitmap grain() {
        if (grainBitmap == null) {
            final int size = 96;
            int[] px = new int[size * size];
            Random r = new Random(7);
            for (int i = 0; i < px.length; i++) {
                int v = r.nextInt(256);
                px[i] = Color.argb(255, v, v, v);
            }
            grainBitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        }
        return grainBitmap;
    }

    /**
     * Dark glass: translucent fill, a sheen fading down from the top, a rim that is brightest at the
     * top-left (where the light comes from) and dim at the bottom-right, plus faint grain.
     * {@link #setTint} turns it into coloured glass (selected chip, primary button).
     */
    public static class GlassDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final boolean solid;
        private int tint;
        private int shaderW = -1, shaderH = -1;

        public GlassDrawable(float radius) {
            this.radius = radius;
            this.solid = !PrizmaConfig.fancyEffects();
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1f)));
            if (!solid) {
                grainPaint.setShader(new BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
                grainPaint.setAlpha(9); // ≈ 3.5% — breaks gradient banding, invisible as texture
            }
        }

        public GlassDrawable setGlassTint(int color) {
            tint = color;
            invalidateSelf();
            return this;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            if (shaderW != b.width() || shaderH != b.height()) {
                shaderW = b.width();
                shaderH = b.height();
                sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.6f,
                        0x14ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                rim.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                        new int[]{0x52ffffff, 0x0fffffff, 0x24ffffff}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            }
            float inset = rim.getStrokeWidth() / 2f;
            rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            if (tint != 0) {
                fill.setColor(tint);
            } else {
                fill.setColor(solid ? 0xf01a1b22 : 0x6b0e0e14);
            }
            canvas.drawRoundRect(rect, radius, radius, fill);
            canvas.drawRoundRect(rect, radius, radius, sheen);
            if (!solid && tint == 0) {
                canvas.drawRoundRect(rect, radius, radius, grainPaint);
            }
            canvas.drawRoundRect(rect, radius, radius, rim);
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ===================== small pieces =====================

    /** Small caps section label. */
    public static TextView label(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text.toUpperCase());
        t.setTextColor(TEXT_2);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        t.setTypeface(AndroidUtilities.bold());
        t.setLetterSpacing(0.12f);
        return t;
    }

    /** Glass capsule; selected = filled with the accent. */
    public static TextView chip(Context context, String text, boolean selected, int accent) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTypeface(AndroidUtilities.bold());
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(8), AndroidUtilities.dp(15), AndroidUtilities.dp(8));
        setChipSelected(t, selected, accent);
        pressable(t);
        return t;
    }

    public static void setChipSelected(TextView chip, boolean selected, int accent) {
        GlassDrawable g = new GlassDrawable(AndroidUtilities.dp(17));
        if (selected) {
            g.setGlassTint((accent & 0x00ffffff) | 0xe6000000);
        }
        chip.setBackground(g);
    }

    /** Soft press: the element sinks a little and springs back. */
    public static void pressable(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().cancel();
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(260)
                            .setInterpolator(new android.view.animation.OvershootInterpolator(2.2f)).start();
                    break;
            }
            return false; // the click itself is handled by the normal click listener
        });
    }

    /** iOS-style glass switch. */
    public static class Toggle extends View {
        private boolean checked;
        private float progress;
        private int accent = DEFAULT_ACCENT;
        private ValueAnimator animator;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public Toggle(Context context) {
            super(context);
        }

        public void setAccent(int accent) {
            this.accent = accent;
            invalidate();
        }

        public boolean isChecked() {
            return checked;
        }

        public void setChecked(boolean value, boolean animated) {
            checked = value;
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            final float target = value ? 1f : 0f;
            if (!animated) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    animator = null;
                }
            });
            animator.setDuration(260);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = h / 2f;
            rect.set(0, 0, w, h);
            paint.setColor(blend(0x2effffff, accent, progress));
            canvas.drawRoundRect(rect, r, r, paint);
            float pad = AndroidUtilities.dpf2(2.5f);
            float tr = r - pad;
            float cx = pad + tr + (w - 2 * (pad + tr)) * progress;
            paint.setColor(0x33000000);
            canvas.drawCircle(cx, r + AndroidUtilities.dpf2(1f), tr, paint);
            paint.setColor(0xffffffff);
            canvas.drawCircle(cx, r, tr, paint);
        }
    }

    /** Round check: an empty ring, or a filled disc with a tick that draws itself. */
    public static class Check extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path tick = new android.graphics.Path();
        private final android.graphics.PathMeasure measure = new android.graphics.PathMeasure();
        private final android.graphics.Path part = new android.graphics.Path();
        private int accent = 0xffffffff;
        private float progress;
        private ValueAnimator animator;

        public Check(Context context) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        public void setAccent(int color) {
            accent = color;
            invalidate();
        }

        public void setChecked(boolean value, boolean animated) {
            if (animator != null) {
                animator.cancel();
                animator = null;
            }
            float target = value ? 1f : 0f;
            if (!animated) {
                progress = target;
                invalidate();
                return;
            }
            animator = ValueAnimator.ofFloat(progress, target);
            animator.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.setDuration(240);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f - AndroidUtilities.dpf2(1f);
            float cx = w / 2f, cy = h / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dpf2(1.6f));
            paint.setColor(0x73ffffff);
            paint.setAlpha((int) (0x73 * (1f - progress)));
            canvas.drawCircle(cx, cy, r, paint);
            if (progress <= 0.01f) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawCircle(cx, cy, r * (0.6f + 0.4f * progress), paint);
            tick.rewind();
            tick.moveTo(cx - r * 0.42f, cy + r * 0.02f);
            tick.lineTo(cx - r * 0.1f, cy + r * 0.34f);
            tick.lineTo(cx + r * 0.45f, cy - r * 0.3f);
            measure.setPath(tick, false);
            part.rewind();
            measure.getSegment(0, measure.getLength() * progress, part, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dpf2(2.2f));
            paint.setColor(accent == 0xffffffff ? 0xff111114 : 0xffffffff);
            canvas.drawPath(part, paint);
        }
    }

    public static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = a >>> 24, ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int ba = b >>> 24, br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    // ===================== the sheet =====================

    /**
     * Full-screen dialog: frosted copy of the screen behind + one floating glass panel at the bottom
     * holding {@code content} (scrolls if taller than ~88% of the screen). Tap outside / back closes it.
     */
    public static class Sheet extends Dialog {
        private final Bitmap frost;
        private final View content;
        private FrameLayout root;
        private Backdrop backdrop;
        private FrameLayout panel;
        private boolean dismissing;

        public Sheet(Context context, View behind, View content) {
            super(context);
            this.frost = snapshot(behind);
            this.content = content;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            requestWindowFeature(Window.FEATURE_NO_TITLE);
            final Context ctx = getContext();
            root = new FrameLayout(ctx);

            backdrop = new Backdrop(ctx, frost);
            backdrop.setOnClickListener(v -> dismiss());
            root.addView(backdrop, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            panel = new FrameLayout(ctx);
            panel.setBackground(new GlassDrawable(AndroidUtilities.dp(30)));
            panel.setClickable(true); // taps on the panel don't fall through to the backdrop
            final int maxH = (int) (AndroidUtilities.displaySize.y * 0.88f);
            ScrollView scroll = new ScrollView(ctx) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
                }
            };
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            panel.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            FrameLayout.LayoutParams lp = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM);
            lp.leftMargin = lp.rightMargin = AndroidUtilities.dp(10);
            lp.bottomMargin = AndroidUtilities.dp(10) + AndroidUtilities.navigationBarHeight;
            root.addView(panel, lp);
            setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            Window window = getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setDimAmount(0f);
                try {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    window.setStatusBarColor(0x00000000);
                    window.setNavigationBarColor(0x00000000);
                    window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                } catch (Throwable ignore) {
                }
            }

            // entrance: the frost fades in while the panel rises into place
            final boolean fancy = PrizmaConfig.fancyEffects();
            backdrop.setAlpha(0f);
            backdrop.animate().alpha(1f).setDuration(fancy ? 220 : 120).start();
            panel.setAlpha(0f);
            panel.setTranslationY(AndroidUtilities.dp(fancy ? 56 : 24));
            panel.animate().alpha(1f).translationY(0).setDuration(fancy ? 420 : 180)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }

        @Override
        public void dismiss() {
            if (dismissing || panel == null) {
                try {
                    super.dismiss();
                } catch (Throwable ignore) {
                }
                return;
            }
            dismissing = true;
            backdrop.animate().alpha(0f).setDuration(160).start();
            panel.animate().alpha(0f).translationY(AndroidUtilities.dp(32)).setDuration(170)
                    .setInterpolator(CubicBezierInterpolator.EASE_IN).withEndAction(() -> {
                        try {
                            Sheet.super.dismiss();
                        } catch (Throwable ignore) {
                        }
                    }).start();
        }
    }
}
