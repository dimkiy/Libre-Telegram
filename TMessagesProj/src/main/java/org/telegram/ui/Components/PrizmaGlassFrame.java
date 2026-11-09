package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;

import java.util.ArrayList;

@android.annotation.SuppressLint("NewApi")
public class PrizmaGlassFrame extends FrameLayout {

    public static final int LIQUID = 0;
    public static final int EDGE_TOP = 1;
    public static final int EDGE_BOTTOM = 2;

    private static final class Pane {
        final View view;
        final float radius;
        final int kind;
        Object blur, fill, lens;
        boolean effectSet;
        final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int maskFor, tintFor, rimFor;
        final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

        Pane(View view, float radius, int kind) {
            this.view = view;
            this.radius = radius;
            this.kind = kind;
            mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }
    }

    private View source;
    private Object sourceNode;
    private final ArrayList<Pane> panes = new ArrayList<>();
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flatPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float[] point = new float[2];
    private static boolean lensBroken;
    private int liquidTint;

    public void setLiquidTint(int color) {
        liquidTint = color;
        invalidate();
    }
    private final ViewTreeObserver.OnPreDrawListener watcher = () -> {
        if (source != null && source.isDirty()) invalidate();
        return true;
    };

    public PrizmaGlassFrame(Context context) {
        super(context);
        setWillNotDraw(false);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(0.8f)));
        shadowPaint.setColor(0);
    }

    public static boolean blurSupported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    public static boolean liquidSupported() {
        return Build.VERSION.SDK_INT >= 33;
    }

    public void setSource(View view) {
        source = view;
    }

    public void addPane(View view, float radius, boolean liquid) {
        panes.add(new Pane(view, radius, LIQUID));
    }

    public void addEdgePane(View view) {
        panes.add(new Pane(view, 0, EDGE_TOP));
    }

    public void addBottomEdgePane(View view) {
        panes.add(new Pane(view, 0, EDGE_BOTTOM));
    }

    private boolean dark() {
        int c = Theme.getColor(Theme.key_windowBackgroundWhite);
        return (0.299 * ((c >> 16) & 0xff) + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff)) < 128;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(watcher);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getViewTreeObserver().removeOnPreDrawListener(watcher);
        if (Build.VERSION.SDK_INT >= 29) {
            if (sourceNode != null) ((RenderNode) sourceNode).discardDisplayList();
            for (Pane p : panes) {
                if (p.blur != null) ((RenderNode) p.blur).discardDisplayList();
                if (p.fill != null) ((RenderNode) p.fill).discardDisplayList();
            }
        }
    }

    private boolean visibleChain(View v, View stop) {
        View cur = v;
        while (cur != null) {
            if (cur.getVisibility() != VISIBLE || cur.getAlpha() <= 0.01f) return false;
            if (cur == stop) return true;
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private float chainAlpha(View v, View stop) {
        float a = 1f;
        View cur = v;
        while (cur != null) {
            a *= cur.getAlpha();
            if (cur == stop) break;
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return a;
    }

    private boolean offsetOf(View v, View child, float[] out) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null && cur != this) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            if (cur != v && cur instanceof android.widget.HorizontalScrollView) x -= cur.getScrollX();
            if (cur != v && cur instanceof android.widget.ScrollView) y -= cur.getScrollY();
            if (cur == child) {
                out[0] = x;
                out[1] = y;
                return true;
            }
            ViewParent parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        boolean glass = blurSupported() && canvas.isHardwareAccelerated();
        if (glass && child == source && child.getVisibility() == VISIBLE) {
            RenderNode node = (RenderNode) (sourceNode != null ? sourceNode : (sourceNode = new RenderNode("PrizmaGlassSource")));
            node.setPosition(0, 0, getWidth(), getHeight());
            Canvas rc = node.beginRecording();
            rc.translate(child.getLeft() + child.getTranslationX(), child.getTop() + child.getTranslationY());
            if (child.getAlpha() < 1f) {
                rc.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), (int) (255 * child.getAlpha()));
                child.draw(rc);
                rc.restore();
            } else {
                child.draw(rc);
            }
            node.endRecording();
            canvas.drawRenderNode(node);
            return false;
        }
        boolean useGlass = glass && sourceNode != null;
        for (int i = 0; i < panes.size(); i++) {
            Pane p = panes.get(i);
            View v = p.view;
            if (v.getWidth() <= 0 || v.getHeight() <= 0) continue;
            if (v == child) {
                if (child.getVisibility() == VISIBLE && child.getAlpha() > 0.01f) {
                    drawPane(canvas, p, v, useGlass, v.getLeft() + v.getTranslationX(), v.getTop() + v.getTranslationY(), v.getAlpha());
                }
            } else if (offsetOf(v, child, point) && visibleChain(v, child)) {
                drawPane(canvas, p, v, useGlass, point[0], point[1], chainAlpha(v, child));
            }
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private void drawPane(Canvas canvas, Pane p, View v, boolean glass, float ox, float oy, float alpha) {
        float sx = v.getScaleX(), sy = v.getScaleY();
        float w = v.getWidth() * sx, h = v.getHeight() * sy;
        float l = ox + v.getPivotX() * (1f - sx);
        float t = oy + v.getPivotY() * (1f - sy);
        if (p.kind == LIQUID) {
            drawLiquid(canvas, p, glass, l, t, w, h, p.radius * Math.min(sx, sy), alpha);
        } else {
            drawEdge(canvas, p, glass, l, t, w, h, alpha);
        }
    }

    private void drawEdge(Canvas canvas, Pane p, boolean glass, float l, float t, float w, float h, float alpha) {
        boolean bottom = p.kind == EDGE_BOTTOM;
        float fade = AndroidUtilities.dpf2(bottom ? 22 : 34);
        float top = bottom ? t - fade : t;
        float H = h + fade;
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        boolean darkTheme = dark();
        canvas.save();
        canvas.translate(l, top);
        int key = (int) H * 7 + (bottom ? 1 : 0) + (glass ? 2 : 0) + (darkTheme ? 4 : 0);
        if (p.tintFor != key || p.tint.getShader() == null) {
            p.tintFor = key;
            float solidEnd = bottom ? fade / H : (h * 0.55f) / H;
            int strong = Theme.multAlpha(bg, glass ? (darkTheme ? 0.62f : 0.55f) : 0.94f);
            int mid = Theme.multAlpha(bg, glass ? 0.32f : 0.75f);
            int[] colors;
            float[] stops;
            if (bottom) {
                colors = new int[]{bg & 0x00ffffff, mid, strong, strong};
                stops = new float[]{0f, solidEnd * 0.6f, solidEnd, 1f};
            } else {
                colors = new int[]{strong, strong, mid, bg & 0x00ffffff};
                stops = new float[]{0f, solidEnd, h / H, 1f};
            }
            p.tint.setShader(new LinearGradient(0, 0, 0, H, colors, stops, Shader.TileMode.CLAMP));
            int[] maskColors = bottom ? new int[]{0, 0xff000000, 0xff000000} : new int[]{0xff000000, 0xff000000, 0};
            float[] maskStops = bottom ? new float[]{0f, fade / H, 1f} : new float[]{0f, (h * 0.7f) / H, 1f};
            p.mask.setShader(new LinearGradient(0, 0, 0, H, maskColors, maskStops, Shader.TileMode.CLAMP));
        }
        if (glass && Build.VERSION.SDK_INT >= 31) {
            RenderNode blur = (RenderNode) (p.blur != null ? p.blur : (p.blur = new RenderNode("PrizmaGlassEdge")));
            int iw = Math.max(1, (int) Math.ceil(w)), ih = Math.max(1, (int) Math.ceil(H));
            blur.setPosition(0, 0, iw, ih);
            if (!p.effectSet) {
                p.effectSet = true;
                float r = AndroidUtilities.dpf2(26);
                blur.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP));
            }
            Canvas bc = blur.beginRecording();
            bc.translate(-l, -top);
            bc.drawRenderNode((RenderNode) sourceNode);
            blur.endRecording();
            int save = canvas.saveLayerAlpha(0, 0, w, H, (int) (255 * alpha));
            canvas.drawRenderNode(blur);
            canvas.drawRect(0, 0, w, H, p.mask);
            canvas.restoreToCount(save);
        }
        p.tint.setAlpha((int) (255 * alpha));
        canvas.drawRect(0, 0, w, H, p.tint);
        canvas.restore();
    }

    private void drawLiquid(Canvas canvas, Pane p, boolean glass, float l, float t, float w, float h, float r, float alpha) {
        boolean darkTheme = dark();
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
        int tint = liquidTint != 0 ? (glass ? liquidTint : Theme.multAlpha(liquidTint | 0xff000000, 0.3f)) : Theme.multAlpha(bg, glass ? (darkTheme ? 0.36f : 0.42f) : 0.95f);
        canvas.save();
        canvas.translate(l, t);
        rect.set(0, 0, w, h);

        int shadow = Theme.multAlpha(0xff000000, (darkTheme ? 0.42f : 0.14f) * alpha);
        shadowPaint.setShadowLayer(AndroidUtilities.dpf2(16), 0, AndroidUtilities.dpf2(5), shadow);
        canvas.drawRoundRect(rect, r, r, shadowPaint);

        boolean drawnTint = false;
        if (glass && Build.VERSION.SDK_INT >= 31) {
            RenderNode blur = (RenderNode) (p.blur != null ? p.blur : (p.blur = new RenderNode("PrizmaGlassBlur")));
            int iw = Math.max(1, (int) Math.ceil(w)), ih = Math.max(1, (int) Math.ceil(h));
            blur.setPosition(0, 0, iw, ih);
            if (!p.effectSet) {
                p.effectSet = true;
                float br = AndroidUtilities.dpf2(9);
                blur.setRenderEffect(RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP));
            }
            Outline outline = new Outline();
            outline.setRoundRect(0, 0, iw, ih, r);
            Canvas bc = blur.beginRecording();
            bc.translate(-l, -t);
            bc.drawRenderNode((RenderNode) sourceNode);
            blur.endRecording();

            if (liquidSupported() && p.lens == null && !lensBroken) {
                try {
                    p.fill = new RenderNode("PrizmaGlassLens");
                    p.lens = new LiquidGlassEffect((RenderNode) p.fill);
                } catch (Throwable e) {
                    lensBroken = true;
                    p.lens = null;
                }
            }
            if (p.lens != null && liquidSupported()) {
                RenderNode fill = (RenderNode) p.fill;
                fill.setPosition(0, 0, iw, ih);
                fill.setOutline(outline);
                fill.setClipToOutline(true);
                float thickness = Math.max(1f, Math.min(AndroidUtilities.dpf2(12), Math.min(w, h) / 3.5f));
                ((LiquidGlassEffect) p.lens).update(w, h, 0, 0, r, r, r, r, thickness, 0.85f, 1.55f, tint, 0, 0, 1);
                Canvas fc = fill.beginRecording();
                fc.drawRenderNode(blur);
                fill.endRecording();
                fill.setAlpha(alpha);
                canvas.drawRenderNode(fill);
                drawnTint = true;
            } else {
                blur.setOutline(outline);
                blur.setClipToOutline(true);
                blur.setAlpha(alpha);
                canvas.drawRenderNode(blur);
            }
        }
        if (!drawnTint) {
            flatPaint.setColor(tint);
            flatPaint.setAlpha((int) (flatPaint.getAlpha() * alpha));
            canvas.drawRoundRect(rect, r, r, flatPaint);
        }

        int key = (int) h * 31 + (darkTheme ? 1 : 0);
        if (p.rimFor != key || p.rim.getShader() == null) {
            p.rimFor = key;
            int top = darkTheme ? 0x66ffffff : 0xccffffff;
            int bottom = darkTheme ? 0x1affffff : 0x14000000;
            p.rim.setStyle(Paint.Style.STROKE);
            p.rim.setStrokeWidth(rimPaint.getStrokeWidth());
            p.rim.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        }
        p.rim.setAlpha((int) (255 * alpha));
        float half = p.rim.getStrokeWidth() / 2f;
        rect.inset(half, half);
        canvas.drawRoundRect(rect, Math.max(0, r - half), Math.max(0, r - half), p.rim);
        canvas.restore();
    }
}
