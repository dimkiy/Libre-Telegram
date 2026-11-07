package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;

import java.util.Locale;

public class PrizmaZoomSlider extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private float min = 1f, max = 2f, value = 1f;
    private Utilities.Callback<Float> listener;
    private int lastStep;

    public PrizmaZoomSlider(Context context) {
        super(context);
        line.setColor(0x80ffffff);
        line.setStrokeWidth(AndroidUtilities.dpf2(2));
        line.setStrokeCap(Paint.Cap.ROUND);
        knob.setColor(0xffffffff);
        knob.setShadowLayer(AndroidUtilities.dp(3), 0, 0, 0x66000000);
        label.setColor(0xff111111);
        label.setTextSize(AndroidUtilities.dp(10));
        label.setTypeface(AndroidUtilities.bold());
        label.setTextAlign(Paint.Align.CENTER);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setRange(float min, float max, float value) {
        this.min = min;
        this.max = Math.max(min + 0.01f, max);
        this.value = Math.max(this.min, Math.min(this.max, value));
        invalidate();
    }

    public void setListener(Utilities.Callback<Float> listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            float top = AndroidUtilities.dp(18), bottom = getHeight() - AndroidUtilities.dp(18);
            float t = 1f - Math.max(0f, Math.min(1f, (e.getY() - top) / (bottom - top)));
            value = min + (max - min) * t;
            int step = Math.round(value * 10);
            if (step != lastStep) {
                lastStep = step;
                if (step % 5 == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            if (listener != null) listener.run(value);
            invalidate();
            return true;
        }
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float top = AndroidUtilities.dp(18), bottom = getHeight() - AndroidUtilities.dp(18);
        canvas.drawLine(cx, top, cx, bottom, line);
        float t = (value - min) / (max - min);
        float y = bottom - (bottom - top) * t;
        float r = AndroidUtilities.dp(15);
        rect.set(cx - r, y - r, cx + r, y + r);
        PrizmaShape.leaf(path, rect, r, r * PrizmaShape.SMALL, 0f);
        canvas.drawPath(path, knob);
        canvas.drawText(String.format(Locale.ROOT, "%.1f×", value), cx, y + AndroidUtilities.dp(3.5f), label);
    }
}
