package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaSwitch extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private float progress;
    private int accent = 0xffffffff;
    private int thumbOn = 0xff111114;
    private int offTrack = 0x1fffffff;
    private int offEdge = 0x59ffffff;
    private int offThumb = 0x8cffffff;
    private final PrizmaSpring spring = new PrizmaSpring(0f, PrizmaSpring.MEDIUM, 0.62f, v -> {
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
        rect.set(0, 0, w, h);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PrizmaPalette.mix(offTrack, accent, t));
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        if (t < 1f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dpf2(1.6f));
            paint.setColor(offEdge);
            paint.setAlpha((int) ((offEdge >>> 24) * (1f - t)));
            rect.inset(AndroidUtilities.dpf2(0.8f), AndroidUtilities.dpf2(0.8f));
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        }
        float small = h * 0.27f, large = h * 0.38f;
        float r = small + (large - small) * progress;
        float pad = h / 2f;
        float cx = pad + (w - pad * 2) * progress;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(PrizmaPalette.mix(offThumb, thumbOn, t));
        canvas.drawCircle(cx, h / 2f, r, paint);
    }
}
