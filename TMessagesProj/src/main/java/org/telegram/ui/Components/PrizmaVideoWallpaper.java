package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.UserConfig;

import java.io.File;

public class PrizmaVideoWallpaper extends MotionBackgroundDrawable {
    private static final int PLACEHOLDER_COLOR = 0xff0e0e12;

    private final String path;
    private ImageReceiver receiver;
    private AnimatedFileDrawable animation;
    private View parent;
    private boolean attached;

    public PrizmaVideoWallpaper(String path) {
        super();
        this.path = path;
        try {
            setColors(0xff0e0e12, 0xff121318, 0xff0e0e12, 0xff15161c);
        } catch (Throwable ignore) {
        }
    }

    public static AnimatedFileDrawable createLooping(String path) {
        try {
            int w = AndroidUtilities.displaySize.x / 2;
            int h = AndroidUtilities.displaySize.y / 2;
            AnimatedFileDrawable d = new AnimatedFileDrawable(new File(path), true, 0, 0, null, null, null, 0, UserConfig.selectedAccount, false, w, h, null);
            d.setLimitFps(true);
            return d;
        } catch (Throwable e) {
            return null;
        }
    }

    private void ensure() {
        if (receiver == null) {
            receiver = new ImageReceiver(parent);
            receiver.setAllowStartAnimation(true);
            receiver.setAutoRepeat(1);
            animation = createLooping(path);
            receiver.setImageBitmap(animation);
            if (attached) {
                receiver.onAttachedToWindow();
            }
            receiver.startAnimation();
        }
    }

    private void release() {
        if (receiver != null) {
            receiver.onDetachedFromWindow();
            receiver.setImageBitmap((android.graphics.drawable.Drawable) null);
            receiver = null;
        }
        if (animation != null) {
            animation.recycle();
            animation = null;
        }
    }

    @Override
    public void setParentView(View view) {
        super.setParentView(view);
        parent = view;
        if (receiver != null) {
            receiver.setParentView(view);
        }
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        ensure();
        if (receiver != null) {
            receiver.onAttachedToWindow();
            receiver.startAnimation();
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        release();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (!attached) {
            canvas.drawColor(PLACEHOLDER_COLOR);
            return;
        }
        ensure();
        if (animation == null || !animation.hasBitmap()) {
            canvas.drawColor(PLACEHOLDER_COLOR);
        }
        if (receiver == null) {
            return;
        }
        receiver.setImageCoords(b.left, b.top, b.width(), b.height());
        receiver.draw(canvas);
    }
}
