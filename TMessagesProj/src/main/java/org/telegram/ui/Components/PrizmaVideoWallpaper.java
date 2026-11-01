package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.UserConfig;

import java.io.File;

/**
 * Prizma: looping video chat wallpaper. Extends MotionBackgroundDrawable only so that
 * SizeNotifierFrameLayout gives it a parent view (setParentView / onAttached) like it
 * does for motion backgrounds; the actual frames are driven by an ImageReceiver playing
 * an AnimatedFileDrawable, which invalidates the parent each frame.
 *
 * The decoder (native ffmpeg context + several full-size frame bitmaps) is released as soon as
 * the chat leaves the screen. It used to live forever, so every opened chat leaked tens of MB.
 */
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
        // The base MotionBackgroundDrawable defaults to a yellow/green gradient. We draw the video
        // ourselves, but the action-bar / input blur samples this gradient — which showed up as a
        // yellow glow at the top and bottom. Force a neutral dark gradient so no colour bleeds.
        try {
            setColors(0xff0e0e12, 0xff121318, 0xff0e0e12, 0xff15161c);
        } catch (Throwable ignore) {
        }
    }

    /** Creates the looping decoder: half the screen resolution and at most 30 fps is plenty for a background. */
    public static AnimatedFileDrawable createLooping(String path) {
        try {
            int w = AndroidUtilities.displaySize.x / 2; // 0 (display not measured yet) = original size
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
        release(); // recreated on the next attach / draw
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
            canvas.drawColor(PLACEHOLDER_COLOR); // decoder warming up — no black/transparent flash
        }
        if (receiver == null) {
            return;
        }
        receiver.setImageCoords(b.left, b.top, b.width(), b.height());
        receiver.draw(canvas);
    }
}
