package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.view.TextureView;
import android.widget.FrameLayout;

import androidx.media3.common.Player;

import org.telegram.messenger.AndroidUtilities;

import java.io.File;

public class PrizmaClipView extends FrameLayout {
    private VideoPlayer player;
    private TextureView texture;
    private File file;
    private boolean attached;
    private boolean paused;
    private boolean revealed;
    private int radius;
    private int failures;
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public PrizmaClipView(Context context) {
        super(context);
        setAlpha(0f);
        setVisibility(GONE);
    }

    public void setRadius(int r) {
        if (radius != r) {
            radius = r;
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        rect.set(0, 0, getWidth(), getHeight());
        PrizmaShape.leaf(path, rect, radius, radius * PrizmaShape.SMALL, 0f);
        canvas.save();
        canvas.clipPath(path);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    public void show(File next) {
        if (next == null) {
            hide();
            return;
        }
        if (next.equals(file)) return;
        animate().cancel();
        release();
        file = next;
        failures = 0;
        revealed = false;
        setAlpha(0f);
        setVisibility(VISIBLE);
        start();
    }

    public void hide() {
        if (file == null) return;
        file = null;
        animate().cancel();
        animate().alpha(0f).setDuration(200).withEndAction(() -> {
            if (file == null) {
                release();
                setVisibility(GONE);
            }
        }).start();
    }

    public void setPaused(boolean value) {
        if (paused == value) return;
        paused = value;
        if (player != null) player.setPlayWhenReady(!value);
    }

    private void start() {
        if (!attached || file == null || player != null) return;
        texture = new TextureView(getContext());
        addView(texture, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        player = new VideoPlayer(false, true);
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_ENDED && player != null) {
                    player.seekTo(0);
                    player.setPlayWhenReady(!paused);
                }
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                AndroidUtilities.runOnUIThread(PrizmaClipView.this::recover);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                fit(width * pixelWidthHeightRatio, height);
            }

            @Override
            public void onRenderedFirstFrame() {
                failures = 0;
                reveal();
            }
        });
        player.setTextureView(texture);
        player.setLooping(true);
        player.setMute(true);
        player.preparePlayer(Uri.fromFile(file), "other");
        player.setPlayWhenReady(!paused);
    }

    private void recover() {
        if (file == null) return;
        if (++failures > 3) {
            hide();
            return;
        }
        release();
        AndroidUtilities.runOnUIThread(this::start, 300);
    }

    private void fit(float width, float height) {
        if (texture == null || width <= 0 || height <= 0) return;
        float ratio = width / height;
        texture.setScaleX(ratio > 1f ? ratio : 1f);
        texture.setScaleY(ratio < 1f ? 1f / ratio : 1f);
    }

    private void reveal() {
        if (revealed) return;
        revealed = true;
        animate().cancel();
        animate().alpha(1f).setDuration(420).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
    }

    private void release() {
        if (player != null) {
            player.setDelegate(null);
            player.releasePlayer(true);
            player = null;
        }
        if (texture != null) {
            removeView(texture);
            texture = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        release();
    }
}
