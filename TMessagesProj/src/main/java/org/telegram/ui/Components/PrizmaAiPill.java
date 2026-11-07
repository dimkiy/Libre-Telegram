package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaAiPill extends View {
    private static final int[] RAINBOW = {0xffff8a3d, 0xffd8e83a, 0xff3be3a6, 0xff27c8ff, 0xff5b6cff, 0xffd94bff, 0xffff8a3d};

    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wave = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path capsule = new Path();
    private final Path band = new Path();
    private final RectF box = new RectF();
    private final long born = SystemClock.uptimeMillis();
    private String text = "Думаю";
    private boolean thinking = true;
    private int sizedFor = -1;

    public PrizmaAiPill(Context context) {
        super(context);
        if (Build.VERSION.SDK_INT < 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        wave.setMaskFilter(new BlurMaskFilter(AndroidUtilities.dp(9), BlurMaskFilter.Blur.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(AndroidUtilities.dpf2(1.4f));
        label.setColor(0xffffffff);
        label.setTextSize(AndroidUtilities.dp(19));
        label.setTypeface(AndroidUtilities.bold());
        label.setTextAlign(Paint.Align.CENTER);
        label.setShadowLayer(AndroidUtilities.dp(6), 0, 0, 0x66000000);
    }

    public void setText(String value, boolean busy) {
        text = value;
        thinking = busy;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        box.set(0, 0, w, h);
        capsule.rewind();
        capsule.addRoundRect(box, h / 2f, h / 2f, Path.Direction.CW);
        base.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xff0b0a1c, 0xff16132e, 0xff3a3550}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        frost.setShader(new LinearGradient(0, h * 0.45f, 0, h, new int[]{0x00ffffff, 0x8cf2f0ff, 0xd9ffffff}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        rim.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x55ffffff, 0x22ffffff, 0x99ffffff}, null, Shader.TileMode.CLAMP));
        sizedFor = w;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - born) / 1000f;
        canvas.save();
        canvas.clipPath(capsule);
        canvas.drawRect(box, base);

        float shift = (t * 0.35f % 1f) * w * 2f;
        wave.setShader(new LinearGradient(-shift, 0, w * 2f - shift, 0, RAINBOW, null, Shader.TileMode.MIRROR));
        band.rewind();
        float crest = h * 0.5f;
        band.moveTo(-AndroidUtilities.dp(20), h + AndroidUtilities.dp(10));
        for (int i = 0; i <= 24; i++) {
            float x = w * i / 24f;
            double phase = i / 24.0 * Math.PI * 2;
            float y = crest + (float) (Math.sin(phase * 1.3 + t * 1.7) * h * 0.12 + Math.sin(phase * 2.6 - t * 1.1) * h * 0.07);
            band.lineTo(x, y);
        }
        band.lineTo(w + AndroidUtilities.dp(20), h + AndroidUtilities.dp(10));
        band.close();
        canvas.drawPath(band, wave);
        canvas.drawRect(0, h * 0.45f, w, h, frost);
        canvas.restore();

        canvas.drawPath(capsule, rim);

        String shown = text;
        if (thinking) {
            int dots = (int) (t * 2.5f) % 4;
            StringBuilder sb = new StringBuilder(text);
            for (int i = 0; i < 3; i++) sb.append(i < dots ? '.' : ' ');
            shown = sb.toString();
        }
        float y = h / 2f - (label.descent() + label.ascent()) / 2f;
        canvas.drawText(shown, w / 2f, y, label);
        if (isAttachedToWindow()) {
            postInvalidateOnAnimation();
        }
    }
}
