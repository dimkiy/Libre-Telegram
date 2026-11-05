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
    private final PrizmaSpring spring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.58f, v -> {
        progress = v;
        invalidate();
    });

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
        checked = value;
        if (animated) {
            spring.to(value ? 1f : 0f);
        } else {
            spring.snap(value ? 1f : 0f);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        float t = Math.max(0f, Math.min(1f, progress));
        float cy = h / 2f;
        float side = h * (0.62f + 0.2f * t);
        float pad = h * 0.42f;
        float cx = pad + (w - pad * 2) * progress;

        paint.setStyle(Paint.Style.FILL);
        float line = AndroidUtilities.dpf2(3f);
        paint.setColor(offTrack);
        rect.set(0, cy - line / 2f, w, cy + line / 2f);
        canvas.drawRoundRect(rect, line / 2f, line / 2f, paint);
        paint.setColor(accent);
        rect.set(0, cy - line / 2f, cx, cy + line / 2f);
        canvas.drawRoundRect(rect, line / 2f, line / 2f, paint);

        canvas.save();
        canvas.rotate(180f * progress, cx, cy);
        rect.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);
        PrizmaShape.leaf(path, rect, side * 0.42f, side * 0.12f, 0f);
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
        canvas.restore();
        if (t > 0.01f) {
            paint.setColor(thumbOn);
            paint.setAlpha((int) (255 * t));
            canvas.drawCircle(cx, cy, side * 0.13f, paint);
        }
    }
}
