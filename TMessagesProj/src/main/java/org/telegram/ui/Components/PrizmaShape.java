package org.telegram.ui.Components;

import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;

public final class PrizmaShape {
    public static final float SMALL = 0.3f;

    private static final float[] radii = new float[8];

    private PrizmaShape() {
    }

    public static void leaf(Path path, RectF rect, float big, float small, float flip) {
        float a = big + (small - big) * flip;
        float b = small + (big - small) * flip;
        radii[0] = radii[1] = a;
        radii[2] = radii[3] = b;
        radii[4] = radii[5] = a;
        radii[6] = radii[7] = b;
        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);
    }

    public static GradientDrawable fill(int color, float big) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float s = big * SMALL;
        d.setCornerRadii(new float[]{big, big, s, s, big, big, s, s});
        return d;
    }
}
