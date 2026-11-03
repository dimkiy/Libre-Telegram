package org.telegram.ui.Components;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;

public class PrizmaScreenBlur {
    private PrizmaScreenBlur() {}

    public static void show(Activity act) {
        if (act == null) {
            return;
        }
        try {
            final View decor = act.getWindow().getDecorView();
            int w = Math.max(1, decor.getWidth() / 5), h = Math.max(1, decor.getHeight() / 5);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(1f / 5f, 1f / 5f);
            decor.draw(c);
            Utilities.stackBlurBitmap(b, 14);

            final ViewGroup content = act.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }

            final FrameLayout overlay = new FrameLayout(act);
            overlay.setClickable(true);

            ImageView iv = new ImageView(act);
            iv.setImageBitmap(b);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            overlay.addView(iv, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            overlay.setBackgroundColor(0x44000000);

            android.widget.LinearLayout hint = new android.widget.LinearLayout(act);
            hint.setOrientation(android.widget.LinearLayout.VERTICAL);
            hint.setGravity(Gravity.CENTER);

            ImageView lock = new ImageView(act);
            lock.setImageResource(R.drawable.msg_blur_linear);
            lock.setColorFilter(0xffffffff);
            hint.addView(lock, LayoutHelper.createLinear(36, 36, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

            TextView tv = new TextView(act);
            tv.setText("Нажмите, чтобы показать");
            tv.setTextColor(0xddffffff);
            tv.setTextSize(15);
            tv.setTypeface(AndroidUtilities.bold());
            hint.addView(tv, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));

            overlay.addView(hint, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

            overlay.setOnClickListener(v -> overlay.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> {
                        try { content.removeView(overlay); } catch (Throwable ignore) {}
                    }).start());

            content.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.setAlpha(0f);
            overlay.animate().alpha(1f).setDuration(180).start();
        } catch (Throwable ignore) {}
    }
}
