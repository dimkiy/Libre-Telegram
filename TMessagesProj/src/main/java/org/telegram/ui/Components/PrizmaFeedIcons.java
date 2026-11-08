package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

import org.telegram.messenger.AndroidUtilities;

public class PrizmaFeedIcons extends Drawable {
    public static final int REPLY = 0;
    public static final int REPOST = 1;
    public static final int LIKE = 2;
    public static final int VIEWS = 3;
    public static final int BOOKMARK = 4;
    public static final int SHARE = 5;
    public static final int TUNE = 6;
    public static final int BACK = 7;
    public static final int MORE = 8;
    public static final int SEND = 9;
    public static final int CHEVRON = 10;
    public static final int PLUS = 11;
    public static final int UP = 12;
    public static final int FILE = 13;

    private final int type;
    private final int size;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean filled;

    public PrizmaFeedIcons(int type) {
        this(type, 22);
    }

    public PrizmaFeedIcons(int type, int sizeDp) {
        this.type = type;
        this.size = AndroidUtilities.dp(sizeDp);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeWidth(1.8f);
        fill.setStyle(Paint.Style.FILL);
        build();
    }

    public PrizmaFeedIcons setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidateSelf();
        return this;
    }

    public void setFilled(boolean filled) {
        if (this.filled != filled) {
            this.filled = filled;
            invalidateSelf();
        }
    }

    public boolean isFilled() {
        return filled;
    }

    private void build() {
        Path p = path;
        switch (type) {
            case REPLY: {
                Path bubble = new Path();
                bubble.addRoundRect(new RectF(3.2f, 3.8f, 20.8f, 16.8f), 6.5f, 6.5f, Path.Direction.CW);
                Path tail = new Path();
                tail.moveTo(7.2f, 14.5f);
                tail.lineTo(5.6f, 20.6f);
                tail.lineTo(12.6f, 16.2f);
                tail.close();
                bubble.op(tail, Path.Op.UNION);
                p.set(bubble);
                break;
            }
            case REPOST:
                p.moveTo(7f, 3.8f);
                p.lineTo(3.8f, 7f);
                p.lineTo(7f, 10.2f);
                p.moveTo(3.8f, 7f);
                p.lineTo(16.5f, 7f);
                p.quadTo(20.2f, 7f, 20.2f, 10.7f);
                p.lineTo(20.2f, 11.5f);
                p.moveTo(17f, 20.2f);
                p.lineTo(20.2f, 17f);
                p.lineTo(17f, 13.8f);
                p.moveTo(20.2f, 17f);
                p.lineTo(7.5f, 17f);
                p.quadTo(3.8f, 17f, 3.8f, 13.3f);
                p.lineTo(3.8f, 12.5f);
                break;
            case LIKE:
                p.moveTo(12f, 20.3f);
                p.cubicTo(5.2f, 15.9f, 2.6f, 12.4f, 2.6f, 8.8f);
                p.cubicTo(2.6f, 6.1f, 4.7f, 4f, 7.3f, 4f);
                p.cubicTo(9.3f, 4f, 11.1f, 5.2f, 12f, 6.9f);
                p.cubicTo(12.9f, 5.2f, 14.7f, 4f, 16.7f, 4f);
                p.cubicTo(19.3f, 4f, 21.4f, 6.1f, 21.4f, 8.8f);
                p.cubicTo(21.4f, 12.4f, 18.8f, 15.9f, 12f, 20.3f);
                p.close();
                break;
            case VIEWS:
                p.moveTo(4.5f, 20f);
                p.lineTo(4.5f, 13f);
                p.moveTo(9.5f, 20f);
                p.lineTo(9.5f, 4.5f);
                p.moveTo(14.5f, 20f);
                p.lineTo(14.5f, 9f);
                p.moveTo(19.5f, 20f);
                p.lineTo(19.5f, 15f);
                break;
            case BOOKMARK:
                p.moveTo(6.2f, 5.5f);
                p.quadTo(6.2f, 3.6f, 8.1f, 3.6f);
                p.lineTo(15.9f, 3.6f);
                p.quadTo(17.8f, 3.6f, 17.8f, 5.5f);
                p.lineTo(17.8f, 20.6f);
                p.lineTo(12f, 16.4f);
                p.lineTo(6.2f, 20.6f);
                p.close();
                break;
            case SHARE:
                p.moveTo(12f, 3.6f);
                p.lineTo(12f, 15f);
                p.moveTo(7.4f, 8.2f);
                p.lineTo(12f, 3.6f);
                p.lineTo(16.6f, 8.2f);
                p.moveTo(4.2f, 13.5f);
                p.lineTo(4.2f, 18.4f);
                p.quadTo(4.2f, 20.4f, 6.2f, 20.4f);
                p.lineTo(17.8f, 20.4f);
                p.quadTo(19.8f, 20.4f, 19.8f, 18.4f);
                p.lineTo(19.8f, 13.5f);
                break;
            case TUNE:
                p.moveTo(3.8f, 7f);
                p.lineTo(6.4f, 7f);
                p.moveTo(11.6f, 7f);
                p.lineTo(20.2f, 7f);
                p.addCircle(9f, 7f, 2.6f, Path.Direction.CW);
                p.moveTo(3.8f, 17f);
                p.lineTo(12.4f, 17f);
                p.moveTo(17.6f, 17f);
                p.lineTo(20.2f, 17f);
                p.addCircle(15f, 17f, 2.6f, Path.Direction.CW);
                break;
            case BACK:
                p.moveTo(20f, 12f);
                p.lineTo(4.4f, 12f);
                p.moveTo(10.6f, 5.8f);
                p.lineTo(4.4f, 12f);
                p.lineTo(10.6f, 18.2f);
                break;
            case MORE:
                p.addCircle(12f, 5.5f, 1.6f, Path.Direction.CW);
                p.addCircle(12f, 12f, 1.6f, Path.Direction.CW);
                p.addCircle(12f, 18.5f, 1.6f, Path.Direction.CW);
                break;
            case SEND:
            case UP:
                p.moveTo(12f, 19.5f);
                p.lineTo(12f, 4.8f);
                p.moveTo(6.2f, 10.6f);
                p.lineTo(12f, 4.8f);
                p.lineTo(17.8f, 10.6f);
                break;
            case CHEVRON:
                p.moveTo(6.5f, 9.5f);
                p.lineTo(12f, 15f);
                p.lineTo(17.5f, 9.5f);
                break;
            case PLUS:
                p.moveTo(12f, 5f);
                p.lineTo(12f, 19f);
                p.moveTo(5f, 12f);
                p.lineTo(19f, 12f);
                break;
            case FILE:
                p.moveTo(13.5f, 3.6f);
                p.lineTo(7f, 3.6f);
                p.quadTo(5.2f, 3.6f, 5.2f, 5.4f);
                p.lineTo(5.2f, 18.6f);
                p.quadTo(5.2f, 20.4f, 7f, 20.4f);
                p.lineTo(17f, 20.4f);
                p.quadTo(18.8f, 20.4f, 18.8f, 18.6f);
                p.lineTo(18.8f, 8.9f);
                p.close();
                p.moveTo(13.5f, 3.6f);
                p.lineTo(13.5f, 8.9f);
                p.lineTo(18.8f, 8.9f);
                break;
        }
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        canvas.save();
        float s = Math.min(b.width(), b.height()) / 24f;
        canvas.translate(b.centerX() - 12f * s, b.centerY() - 12f * s);
        canvas.scale(s, s);
        if (type == MORE) {
            canvas.drawPath(path, fill);
        } else if (filled && (type == LIKE || type == BOOKMARK)) {
            canvas.drawPath(path, fill);
            canvas.drawPath(path, stroke);
        } else {
            canvas.drawPath(path, stroke);
        }
        canvas.restore();
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        stroke.setAlpha(alpha);
        fill.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        stroke.setColorFilter(colorFilter);
        fill.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
