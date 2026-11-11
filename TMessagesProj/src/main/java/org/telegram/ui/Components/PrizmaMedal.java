package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;

public class PrizmaMedal extends View {
    private static final int SLICES = 14;
    private static final float MAX_TILT = 58f;
    private static final int LOCKED = 0xff8b8f97;

    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Matrix shineMatrix = new Matrix();
    private final float[] values = new float[9];
    private final Paint face = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint word = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private Drawable icon;
    private int tone = 0xff4ba3e3;
    private boolean earned = true;
    private String backNote = "";
    private String frontText;
    private final Paint frontPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float turn, tilt, turnSpeed, tiltSpeed, sway;
    private float lastX, lastY;
    private boolean touching, running, attached, wasFront = true;
    private long lastFrame;
    private VelocityTracker tracker;
    private LinearGradient shineShader;
    private int builtFor;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!attached) {
                running = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.034f, Math.max(0.001f, (now - lastFrame) / 1000f));
            lastFrame = now;
            boolean more = step(dt);
            invalidate();
            if (more) {
                postOnAnimation(this);
            } else {
                running = false;
            }
        }
    };

    public PrizmaMedal(Context context) {
        super(context);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(AndroidUtilities.dpf2(1.6f));
        ground.setColor(0x24000000);
        shade.setColor(0xff000000);
        word.setColor(0xf2ffffff);
        word.setTypeface(AndroidUtilities.bold());
        word.setTextAlign(Paint.Align.CENTER);
        note.setColor(0xb3ffffff);
        note.setTextAlign(Paint.Align.CENTER);
        setContentDescription("Достижение. Проведите пальцем, чтобы повернуть");
    }

    public void set(int iconRes, int color, boolean earned, String backNote) {
        this.earned = earned;
        this.tone = earned ? color | 0xff000000 : LOCKED;
        this.backNote = backNote == null ? "" : backNote;
        try {
            icon = getResources().getDrawable(iconRes).mutate();
            icon.setColorFilter(new PorterDuffColorFilter(0xffffffff, PorterDuff.Mode.SRC_IN));
            icon.setAlpha(earned ? 255 : 170);
        } catch (Throwable e) {
            icon = null;
        }
        edge.setColor(ColorUtils.blendARGB(tone, 0xff000000, 0.46f));
        ring.setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(tone, 0xffffffff, 0.55f), 170));
        builtFor = 0;
        invalidate();
    }

    public void setText(String text, int color, String backNote) {
        frontText = text == null ? "" : text;
        icon = null;
        earned = true;
        tone = color | 0xff000000;
        this.backNote = backNote == null ? "" : backNote;
        frontPaint.setColor(0xffffffff);
        frontPaint.setTypeface(AndroidUtilities.bold());
        frontPaint.setTextAlign(Paint.Align.CENTER);
        edge.setColor(ColorUtils.blendARGB(tone, 0xff000000, 0.46f));
        ring.setColor(ColorUtils.setAlphaComponent(ColorUtils.blendARGB(tone, 0xffffffff, 0.55f), 170));
        builtFor = 0;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        turn = -28f;
        turnSpeed = 150f;
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        removeCallbacks(frame);
        running = false;
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private void start() {
        if (running || !attached) return;
        running = true;
        lastFrame = SystemClock.uptimeMillis();
        postOnAnimation(frame);
    }

    private boolean step(float dt) {
        if (touching) return false;
        if (Math.abs(turnSpeed) > 24f || Math.abs(tiltSpeed) > 24f) {
            turn += turnSpeed * dt;
            tilt += tiltSpeed * dt;
            turnSpeed *= (float) Math.exp(-2.1f * dt);
            tiltSpeed *= (float) Math.exp(-5.5f * dt);
            if (tilt > MAX_TILT || tilt < -MAX_TILT) {
                tilt = Math.max(-MAX_TILT, Math.min(MAX_TILT, tilt));
                tiltSpeed = 0f;
            }
            return true;
        }
        turnSpeed = 0f;
        tiltSpeed = 0f;
        float rest = Math.round(turn / 180f) * 180f;
        float pull = Math.min(1f, 9f * dt);
        turn += (rest - turn) * pull;
        tilt += (0f - tilt) * pull;
        boolean settled = Math.abs(rest - turn) < 0.25f && Math.abs(tilt) < 0.25f;
        if (settled) {
            turn = rest % 360f;
            tilt = 0f;
            if (!PrizmaConfig.fancyEffects()) return false;
            sway += dt;
        }
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                turnSpeed = 0f;
                tiltSpeed = 0f;
                sway = 0f;
                lastX = e.getX();
                lastY = e.getY();
                if (tracker == null) tracker = VelocityTracker.obtain();
                tracker.clear();
                tracker.addMovement(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (tracker != null) tracker.addMovement(e);
                float k = 180f / Math.max(1f, getWidth() * 0.62f);
                turn += (e.getX() - lastX) * k;
                tilt = Math.max(-MAX_TILT, Math.min(MAX_TILT, tilt - (e.getY() - lastY) * k * 0.7f));
                lastX = e.getX();
                lastY = e.getY();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touching = false;
                if (tracker != null) {
                    tracker.addMovement(e);
                    tracker.computeCurrentVelocity(1000);
                    float k = 180f / Math.max(1f, getWidth() * 0.62f);
                    turnSpeed = Math.max(-1400f, Math.min(1400f, tracker.getXVelocity() * k));
                    tiltSpeed = Math.max(-500f, Math.min(500f, -tracker.getYVelocity() * k * 0.7f));
                }
                start();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void place(float cx, float cy, float turnDeg, float tiltDeg, float z) {
        camera.save();
        camera.rotateX(tiltDeg);
        camera.rotateY(turnDeg);
        camera.translate(0f, 0f, z);
        camera.getMatrix(matrix);
        camera.restore();
        matrix.getValues(values);
        float density = Math.max(1f, AndroidUtilities.density);
        values[Matrix.MPERSP_0] /= density;
        values[Matrix.MPERSP_1] /= density;
        matrix.setValues(values);
        matrix.preTranslate(-cx, -cy);
        matrix.postTranslate(cx, cy);
    }

    private void build(float cx, float cy, float r) {
        int key = (int) r * 31 + tone;
        if (key == builtFor && shineShader != null) return;
        builtFor = key;
        face.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r,
                ColorUtils.blendARGB(tone, 0xffffffff, 0.26f), ColorUtils.blendARGB(tone, 0xff000000, 0.3f), Shader.TileMode.CLAMP));
        shineShader = new LinearGradient(cx - r * 0.45f, cy - r * 0.45f, cx + r * 0.45f, cy + r * 0.45f,
                new int[]{0x00ffffff, 0x3dffffff, 0x00ffffff}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        shine.setShader(shineShader);
        word.setTextSize(r * 0.44f);
        note.setTextSize(Math.max(AndroidUtilities.dp(10), r * 0.15f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f - AndroidUtilities.dpf2(6);
        float r = Math.min(w, h) / 2f - AndroidUtilities.dpf2(18);
        if (r <= 0) return;
        build(cx, cy, r);

        float showTurn = turn + (float) Math.sin(sway * 0.9f) * 7f;
        float showTilt = tilt + (float) Math.sin(sway * 0.63f) * 3f;
        double ry = Math.toRadians(showTurn), rx = Math.toRadians(showTilt);
        float facing = (float) (Math.cos(ry) * Math.cos(rx));
        boolean front = facing >= 0f;
        if (front != wasFront) {
            wasFront = front;
            if (touching || Math.abs(turnSpeed) > 24f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }

        float spread = 0.35f + 0.6f * Math.abs((float) Math.cos(ry));
        oval.set(cx - r * spread, cy + r + AndroidUtilities.dpf2(9), cx + r * spread, cy + r + AndroidUtilities.dpf2(17));
        canvas.drawOval(oval, ground);

        float thickness = r * 0.17f;
        for (int i = 0; i <= SLICES; i++) {
            float t = i / (float) SLICES;
            float z = front ? thickness / 2f - thickness * t : -thickness / 2f + thickness * t;
            place(cx, cy, showTurn, showTilt, z);
            canvas.save();
            canvas.concat(matrix);
            if (i < SLICES) {
                canvas.drawCircle(cx, cy, r, edge);
            } else {
                drawFace(canvas, cx, cy, r, front, (float) Math.sin(ry), Math.abs(facing));
            }
            canvas.restore();
        }
    }

    private void drawFace(Canvas canvas, float cx, float cy, float r, boolean front, float sweep, float facing) {
        canvas.drawCircle(cx, cy, r, face);
        canvas.drawCircle(cx, cy, r - AndroidUtilities.dpf2(6), ring);
        if (front && frontText != null) {
            float size = r * (frontText.length() <= 2 ? 0.7f : frontText.length() == 3 ? 0.56f : frontText.length() == 4 ? 0.45f : 0.38f);
            frontPaint.setTextSize(size);
            canvas.drawText(frontText, cx, cy - (frontPaint.descent() + frontPaint.ascent()) / 2f, frontPaint);
        } else if (front) {
            if (icon != null) {
                int half = (int) (r * 0.46f);
                icon.setBounds((int) cx - half, (int) cy - half, (int) cx + half, (int) cy + half);
                icon.draw(canvas);
            }
        } else {
            canvas.save();
            canvas.scale(-1f, 1f, cx, cy);
            float baseline = cy - (word.descent() + word.ascent()) / 2f - (backNote.isEmpty() ? 0f : r * 0.1f);
            canvas.drawText("Libre", cx, baseline, word);
            if (!backNote.isEmpty()) canvas.drawText(backNote, cx, baseline + r * 0.3f, note);
            canvas.restore();
        }
        shineMatrix.setTranslate(sweep * r * 1.5f, sweep * r * 0.4f);
        shineShader.setLocalMatrix(shineMatrix);
        canvas.drawCircle(cx, cy, r, shine);
        shade.setAlpha((int) (255 * 0.34f * (1f - facing)));
        canvas.drawCircle(cx, cy, r, shade);
    }
}
