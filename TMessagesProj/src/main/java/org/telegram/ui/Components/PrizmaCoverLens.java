package org.telegram.ui.Components;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

public final class PrizmaCoverLens {
    private static final String SOURCE =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "uniform float power;\n" +
            "half4 main(float2 p) {\n" +
            "    float2 uv = p / size - 0.5;\n" +
            "    float d = length(uv);\n" +
            "    float bulge = 1.0 - power * 0.09 * (1.0 - min(1.0, d * 1.7));\n" +
            "    float2 q = (uv * bulge + 0.5) * size;\n" +
            "    float2 split = uv * size * power * 0.022;\n" +
            "    half4 g = content.eval(q);\n" +
            "    half r = content.eval(q + split).r;\n" +
            "    half b = content.eval(q - split).b;\n" +
            "    return half4(r, g.g, b, g.a);\n" +
            "}\n";

    private final View target;
    private final Object shader;
    private boolean active;

    private final PrizmaSpring power = new PrizmaSpring(0f, 150f, 1f, this::apply);

    public PrizmaCoverLens(View target) {
        this.target = target;
        shader = Build.VERSION.SDK_INT >= 33 ? create() : null;
    }

    private static Object create() {
        try {
            return new RuntimeShader(SOURCE);
        } catch (Throwable e) {
            return null;
        }
    }

    public void hit(float strength) {
        if (shader == null) return;
        power.snap(Math.min(1f, strength * 1.2f));
        power.to(0f);
    }

    private void apply(float value) {
        if (shader == null || Build.VERSION.SDK_INT < 33) return;
        try {
            if (value < 0.012f) {
                if (active) {
                    active = false;
                    target.setRenderEffect(null);
                }
                return;
            }
            RuntimeShader s = (RuntimeShader) shader;
            s.setFloatUniform("size", target.getWidth(), target.getHeight());
            s.setFloatUniform("power", value);
            target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"));
            active = true;
        } catch (Throwable ignore) {
        }
    }
}
