package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Outline;
import android.net.Uri;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import java.io.File;

public class PrizmaClipView extends FrameLayout {
    private VideoPlayer player;
    private TextureView texture;
    private File file;
    private boolean attached;
    private boolean paused;
    private boolean revealed;
    private int radius;

    public PrizmaClipView(Context context) {
        super(context);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        setAlpha(0f);
        setVisibility(GONE);
    }

    public void setRadius(int r) {
        if (radius != r) {
            radius = r;
            invalidateOutline();
        }
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
            }

            @Override
            public void onError(VideoPlayer p, Exception e) {
                hide();
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                fit(width * pixelWidthHeightRatio, height);
            }

            @Override
            public void onRenderedFirstFrame() {
                reveal();
            }
        });
        player.setTextureView(texture);
        player.setLooping(true);
        player.setMute(true);
        player.preparePlayer(Uri.fromFile(file), "other");
        player.setPlayWhenReady(!paused);
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
