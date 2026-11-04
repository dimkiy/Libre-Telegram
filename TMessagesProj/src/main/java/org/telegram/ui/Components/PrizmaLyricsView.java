package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaLyrics;

import java.util.ArrayList;

public class PrizmaLyricsView extends View {
    public interface Listener {
        void onSeekTo(long ms);
    }

    public static final int FILL_OFF = 0;
    public static final int FILL_HORIZONTAL = 1;
    public static final int FILL_VERTICAL = 2;

    private static final long RESUME_FOLLOW_MS = 3500;
    private static final int DIM_ALPHA = 0x5c;

    public Listener listener;

    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<PrizmaLyrics.Line> lines = new ArrayList<>();
    private StaticLayout[] layouts = new StaticLayout[0];
    private float[] tops = new float[0];
    private float[] focus = new float[0];
    private boolean synced;
    private int builtWidth;
    private float contentHeight;

    private int current = -1;
    private long positionMs;
    private long lineStart, lineEnd;

    private float scroll;
    private boolean following = true;
    private final PrizmaSpring follow = new PrizmaSpring(0f, 90f, 1f, v -> {
        if (following) {
            scroll = v;
            invalidate();
        }
    });
    private long userScrolledAt;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private float downY, lastY;
    private boolean moved;
    private final int touchSlop;

    private final Matrix fillMatrix = new Matrix();
    private LinearGradient fillShaderH, fillShaderV;
    private int fillColorKey;
    private float bump;
    private long lastDraw;

    public PrizmaLyricsView(Context context) {
        super(context);
        paint.setTypeface(AndroidUtilities.bold());
        paint.setTextSize(AndroidUtilities.dp(24));
        paint.setColor(0xffffffff);
        scroller = new OverScroller(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setLyrics(PrizmaLyrics.Lyrics lyrics) {
        lines.clear();
        if (lyrics != null && lyrics.lines != null) {
            for (PrizmaLyrics.Line l : lyrics.lines) {
                if (lyrics.synced || (l.text != null && !l.text.isEmpty())) lines.add(l);
            }
        }
        synced = lyrics != null && lyrics.synced;
        current = -1;
        following = true;
        scroll = 0;
        follow.snap(0f);
        builtWidth = 0;
        requestLayout();
        invalidate();
    }

    public void bump(float strength) {
        bump = Math.max(bump, strength);
        invalidate();
    }

    public void setPosition(long ms) {
        positionMs = ms;
        if (!synced || lines.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= ms) idx = i;
            else break;
        }
        if (idx != current) {
            current = idx;
            if (idx >= 0) {
                lineStart = lines.get(idx).timeMs;
                lineEnd = idx + 1 < lines.size() ? lines.get(idx + 1).timeMs : lineStart + 5000;
            }
            aim();
        }
        if (!following && System.currentTimeMillis() - userScrolledAt > RESUME_FOLLOW_MS) {
            following = true;
            follow.snap(scroll);
            aim();
        }
        invalidate();
    }

    private void aim() {
        if (!following || getHeight() == 0 || tops.length == 0) return;
        int at = Math.max(0, Math.min(tops.length - 1, current));
        float target = tops[at] - getHeight() * 0.3f;
        follow.to(clampScroll(target));
    }

    private float clampScroll(float s) {
        float max = Math.max(0, contentHeight - getHeight() * 0.55f);
        return Math.max(0, Math.min(max, s));
    }

    private void build(int width) {
        builtWidth = width;
        int n = lines.size();
        layouts = new StaticLayout[n];
        tops = new float[n];
        focus = new float[n];
        float y = AndroidUtilities.dp(16);
        float gap = AndroidUtilities.dp(18);
        for (int i = 0; i < n; i++) {
            String t = lines.get(i).text;
            if (t == null || t.isEmpty()) t = "♪";
            layouts[i] = StaticLayout.Builder.obtain(t, 0, t.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(AndroidUtilities.dp(2), 1f)
                    .setIncludePad(false)
                    .build();
            tops[i] = y;
            y += layouts[i].getHeight() + gap;
        }
        contentHeight = y + AndroidUtilities.dp(40);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        builtWidth = 0;
        post(this::aim);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.abortAnimation();
                downY = lastY = e.getY();
                moved = false;
                if (velocity == null) velocity = VelocityTracker.obtain();
                velocity.clear();
                velocity.addMovement(e);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (velocity != null) velocity.addMovement(e);
                float dy = lastY - e.getY();
                if (!moved && Math.abs(e.getY() - downY) > touchSlop) {
                    moved = true;
                    following = false;
                    follow.cancel();
                }
                if (moved) {
                    scroll = clampScroll(scroll + dy);
                    lastY = e.getY();
                    userScrolledAt = System.currentTimeMillis();
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (moved) {
                    if (velocity != null) {
                        velocity.computeCurrentVelocity(1000);
                        scroller.fling(0, (int) scroll, 0, (int) -velocity.getYVelocity(), 0, 0, 0, (int) clampScroll(Integer.MAX_VALUE), 0, AndroidUtilities.dp(40));
                        invalidate();
                    }
                } else {
                    tapAt(e.getY());
                }
                endTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                endTouch();
                return true;
        }
        return super.onTouchEvent(e);
    }

    private void endTouch() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void tapAt(float y) {
        if (!synced || listener == null) return;
        float at = y + scroll;
        for (int i = 0; i < layouts.length; i++) {
            if (at >= tops[i] - AndroidUtilities.dp(8) && at <= tops[i] + layouts[i].getHeight() + AndroidUtilities.dp(8)) {
                long ms = lines.get(i).timeMs;
                if (ms >= 0) {
                    following = true;
                    follow.snap(scroll);
                    listener.onSeekTo(ms);
                }
                return;
            }
        }
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = clampScroll(scroller.getCurrY());
            userScrolledAt = System.currentTimeMillis();
            postInvalidateOnAnimation();
        }
    }

    private void ensureShaders() {
        int active = paint.getColor();
        if (fillShaderH != null && fillColorKey == active) return;
        fillColorKey = active;
        int dim = (active & 0x00ffffff) | (DIM_ALPHA << 24);
        fillShaderH = new LinearGradient(-1f, 0, 1f, 0, active, dim, Shader.TileMode.CLAMP);
        fillShaderV = new LinearGradient(0, -1f, 0, 1f, active, dim, Shader.TileMode.CLAMP);
    }

    private void drawFilled(Canvas canvas, StaticLayout layout, float progress, int mode) {
        ensureShaders();
        int lineCount = layout.getLineCount();
        if (mode == FILL_HORIZONTAL) {
            float total = 0;
            for (int i = 0; i < lineCount; i++) total += layout.getLineWidth(i);
            float sung = total * progress;
            float feather = AndroidUtilities.dpf2(26);
            for (int i = 0; i < lineCount; i++) {
                float lw = layout.getLineWidth(i);
                float front = Math.min(sung, lw + feather);
                sung -= lw;
                fillMatrix.reset();
                fillMatrix.setScale(feather, 1f);
                fillMatrix.postTranslate(front, 0);
                fillShaderH.setLocalMatrix(fillMatrix);
                paint.setShader(fillShaderH);
                canvas.save();
                canvas.clipRect(0, layout.getLineTop(i), getWidth(), layout.getLineBottom(i));
                layout.draw(canvas);
                canvas.restore();
            }
        } else {
            float h = layout.getHeight();
            float feather = AndroidUtilities.dpf2(9);
            fillMatrix.reset();
            fillMatrix.setScale(1f, feather);
            fillMatrix.postTranslate(0, -feather + (h + feather * 2) * progress);
            fillShaderV.setLocalMatrix(fillMatrix);
            paint.setShader(fillShaderV);
            layout.draw(canvas);
        }
        paint.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || lines.isEmpty()) return;
        if (builtWidth != w) build(w);
        long now = System.currentTimeMillis();
        float dt = lastDraw == 0 ? 16 : Math.min(48, now - lastDraw);
        lastDraw = now;
        bump *= (float) Math.pow(0.01f, dt / 1000f);

        int mode = PrizmaConfig.lyricsFill();
        boolean animating = false;
        float fade = AndroidUtilities.dpf2(46);
        for (int i = 0; i < layouts.length; i++) {
            float want = i == current ? 1f : 0f;
            float was = focus[i];
            focus[i] += (want - was) * Math.min(1f, dt / 90f);
            if (Math.abs(want - focus[i]) > 0.01f) animating = true;
            else focus[i] = want;

            float y = tops[i] - scroll;
            int lh = layouts[i].getHeight();
            if (y + lh < 0 || y > h) continue;

            float edge = Math.min(1f, Math.min((y + lh) / fade, (h - y) / fade));
            edge = Math.max(0f, edge);
            float scale = synced ? 0.94f + 0.06f * focus[i] + (i == current ? 0.012f * bump : 0f) : 1f;
            canvas.save();
            canvas.translate(0, y);
            canvas.scale(scale, scale, 0, lh / 2f);
            if (!synced) {
                paint.setAlpha((int) (235 * edge));
                layouts[i].draw(canvas);
            } else if (i == current && mode != FILL_OFF && lineEnd > lineStart) {
                float p = Math.max(0f, Math.min(1f, (positionMs - lineStart) / (float) (lineEnd - lineStart)));
                paint.setAlpha((int) (255 * edge));
                drawFilled(canvas, layouts[i], p, mode);
                animating = true;
            } else {
                int alpha = (int) ((DIM_ALPHA + (255 - DIM_ALPHA) * focus[i]) * edge);
                paint.setAlpha(alpha);
                layouts[i].draw(canvas);
            }
            canvas.restore();
        }
        paint.setAlpha(255);
        if (animating || bump > 0.01f) {
            PrizmaConfig.nextDecorFrame(this);
        }
    }
}
