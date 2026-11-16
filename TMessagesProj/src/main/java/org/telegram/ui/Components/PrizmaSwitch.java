package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaSwitch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private boolean checked;
    private float progress;
    private int accent = 0xffffffff;
    private int thumbOn = 0xff111114;
    private int offTrack = 0x26ffffff;
    private int offEdge = 0x73ffffff;
    private int offThumb = 0x8cffffff;
    private final PrizmaMotion.Value pos = new PrizmaMotion.Value(0f, PrizmaMotion.KNOB);
    private final PrizmaMotion.Value grow = new PrizmaMotion.Value(0f, PrizmaMotion.KNOB_STRETCH);
    private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();

    public PrizmaSwitch(Context context) {
        super(context);
    }

    public void setColors(int accent, int thumbOn) {
        this.accent = accent;
        this.thumbOn = thumbOn;
        invalidate();
    }

    public void setOffColors(int track, int edge, int thumb) {
        offTrack = track;
        offEdge = edge;
        offThumb = thumb;
        invalidate();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setChecked(boolean value, boolean animated) {
        boolean changed = value != checked;
        checked = value;
        if (animated && !PrizmaMotion.calm()) {
            pos.t = value ? 1f : 0f;
            if (changed) {
                grow.t = 0f;
                grow.v = 60f;
            }
            clock.reset();
        } else {
            pos.snap(value ? 1f : 0f);
            grow.snap(0f);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean moving = PrizmaMotion.run(clock.tick(), pos, grow);
        progress = pos.x;
        float w = getWidth(), h = getHeight();
        float t = Math.max(0f, Math.min(1f, progress));
        float cy = h / 2f;
        float side = h * (0.62f + 0.16f * t);
        float pad = h * 0.42f;
        float cx = pad + (w - pad * 2) * progress;
        float stretch = Math.max(0f, Math.min(1f, grow.x)) * side * 0.55f;
        float lead = checked ? stretch * t : 0f;
        float tail = checked ? 0f : stretch * (1f - t);

        paint.setStyle(Paint.Style.FILL);
        float line = AndroidUtilities.dpf2(3f);
        paint.setColor(offTrack);
        rect.set(0, cy - line / 2f, w, cy + line / 2f);
        canvas.drawRoundRect(rect, line / 2f, line / 2f, paint);
        paint.setColor(accent);
        rect.set(0, cy - line / 2f, cx, cy + line / 2f);
        canvas.drawRoundRect(rect, line / 2f, line / 2f, paint);

        float half = side / 2f;
        float squash = 1f - Math.min(0.18f, stretch / side * 0.35f);
        rect.set(cx - half - lead, cy - half * squash, cx + half + tail, cy + half * squash);
        float r = Math.min(rect.height(), rect.width()) / 2f;
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        paint.setColor(PrizmaPalette.mix(offThumb, accent, t));
        canvas.drawPath(path, paint);
        if (t < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dpf2(1.3f));
            paint.setColor(offEdge);
            paint.setAlpha((int) ((offEdge >>> 24) * (1f - t)));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        if (t > 0.01f) {
            paint.setColor(thumbOn);
            paint.setAlpha((int) (255 * t));
            canvas.drawCircle(rect.centerX(), cy, side * 0.13f * t, paint);
        }
        if (moving) postInvalidateOnAnimation();
    }
}
