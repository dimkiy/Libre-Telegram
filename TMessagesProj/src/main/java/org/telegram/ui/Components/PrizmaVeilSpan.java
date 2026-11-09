package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ReplacementSpan;

public class PrizmaVeilSpan extends ReplacementSpan {
    private final int width;
    private final RectF rect = new RectF();

    private PrizmaVeilSpan(int width) {
        this.width = Math.max(1, width);
    }

    public static CharSequence bar(long seed, int salt, float available, float min, float max) {
        long h = seed * 31 + salt * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        float f = min + (max - min) * ((h & 0xffff) / 65535f);
        SpannableString s = new SpannableString(" ");
        s.setSpan(new PrizmaVeilSpan((int) (available * f)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
        if (fm != null) {
            Paint.FontMetricsInt m = paint.getFontMetricsInt();
            fm.ascent = m.ascent;
            fm.descent = m.descent;
            fm.top = m.top;
            fm.bottom = m.bottom;
        }
        return width;
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint paint) {
        float size = paint.getTextSize();
        float h = size * 0.62f;
        float cy = y - size * 0.34f;
        rect.set(x, cy - h / 2f, x + width, cy + h / 2f);
        int old = paint.getColor();
        Paint.Style style = paint.getStyle();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((old & 0x00ffffff) | (Math.max(30, (int) (((old >>> 24) & 0xff) * 0.2f)) << 24));
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);
        paint.setColor(old);
        paint.setStyle(style);
    }
}
