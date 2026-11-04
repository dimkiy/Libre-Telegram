package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.core.graphics.ColorUtils;

public final class PrizmaPalette {
    public static final int FALLBACK_SEED = 0xffFF7A59;

    public final int seed;
    public final int accent;
    public final int onAccent;
    public final int container;
    public final int onContainer;
    public final int surface;
    public final int backgroundTop;
    public final int backgroundBottom;

    private PrizmaPalette(int seed, boolean neutral) {
        this.seed = seed;
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(seed, hsl);
        float hue = hsl[0];
        float sat = neutral ? 0.03f : Math.max(0.38f, Math.min(0.72f, hsl[1]));
        accent = ColorUtils.HSLToColor(new float[]{hue, sat, neutral ? 0.82f : 0.78f});
        onAccent = ColorUtils.HSLToColor(new float[]{hue, sat * 0.8f, 0.13f});
        container = ColorUtils.HSLToColor(new float[]{hue, sat * 0.42f, 0.26f});
        onContainer = ColorUtils.HSLToColor(new float[]{hue, sat, 0.9f});
        surface = ColorUtils.HSLToColor(new float[]{hue, sat * 0.24f, 0.115f});
        backgroundTop = ColorUtils.HSLToColor(new float[]{hue, neutral ? 0.02f : Math.min(0.6f, sat * 0.8f), neutral ? 0.17f : 0.22f});
        backgroundBottom = ColorUtils.HSLToColor(new float[]{hue, sat * 0.3f, 0.07f});
    }

    public static PrizmaPalette of(int seed) {
        return new PrizmaPalette(seed, false);
    }

    public static PrizmaPalette neutral() {
        return new PrizmaPalette(0xff8a8d96, true);
    }

    public static PrizmaPalette fallback() {
        return of(FALLBACK_SEED);
    }

    public static int seedOf(Bitmap source) {
        if (source == null || source.isRecycled()) return 0;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(source, 32, 32, true);
        } catch (Throwable e) {
            return 0;
        }
        final int buckets = 24;
        float[] weight = new float[buckets];
        float[] satSum = new float[buckets];
        float[] lumSum = new float[buckets];
        float[] hueSum = new float[buckets];
        float[] hsl = new float[3];
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int c = small.getPixel(x, y);
                if (Color.alpha(c) < 200) continue;
                ColorUtils.colorToHSL(c, hsl);
                float vivid = hsl[1] * (1f - Math.abs(hsl[2] - 0.5f) * 1.7f);
                if (vivid < 0.06f) continue;
                int b = Math.min(buckets - 1, (int) (hsl[0] / 360f * buckets));
                weight[b] += vivid;
                satSum[b] += hsl[1] * vivid;
                lumSum[b] += hsl[2] * vivid;
                hueSum[b] += hsl[0] * vivid;
            }
        }
        if (small != source) small.recycle();
        int best = -1;
        float bestWeight = 0f;
        for (int i = 0; i < buckets; i++) {
            float around = weight[i] + 0.5f * weight[(i + 1) % buckets] + 0.5f * weight[(i + buckets - 1) % buckets];
            if (around > bestWeight) {
                bestWeight = around;
                best = i;
            }
        }
        if (best < 0 || weight[best] <= 0f || bestWeight < 6f) return 0;
        return ColorUtils.HSLToColor(new float[]{hueSum[best] / weight[best], satSum[best] / weight[best], lumSum[best] / weight[best]});
    }

    public static int mix(int a, int b, float t) {
        return ColorUtils.blendARGB(a, b, Math.max(0f, Math.min(1f, t)));
    }
}
