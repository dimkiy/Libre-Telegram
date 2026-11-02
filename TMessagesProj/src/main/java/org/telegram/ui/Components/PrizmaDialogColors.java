package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.util.HashMap;

/*
 * Libre — что это за файл:
 * Цветные метки чатов (полоска слева в списке).
 */

/**
 * Prizma: user color tags for chats. Lets the user highlight important chats with a colour;
 * a small colored stripe is drawn on the chat cell.
 */
public class PrizmaDialogColors {

    // index 0 = none; 1..8 = colors
    public static final int[] COLORS = {
            0x00000000,
            0xffE8534B, // red
            0xffF5A623, // orange
            0xffF7D038, // yellow
            0xff4CC764, // green
            0xff34C6D6, // cyan
            0xff4E8CFF, // blue
            0xffA66BFF, // purple
            0xffFF6FB0, // pink
    };

    public static final String[] NAMES = {
            "Без метки", "Красный", "Оранжевый", "Жёлтый", "Зелёный", "Голубой", "Синий", "Фиолетовый", "Розовый"
    };

    private PrizmaDialogColors() {}

    // Cached parsed map — color() is called from DialogCell.onDraw for every cell every frame,
    // so we must NOT read prefs / allocate / split on each call. Rebuilt only when a tag changes.
    private static volatile HashMap<Long, Integer> cached;
    private static volatile boolean dirty = true;

    private static HashMap<Long, Integer> map() {
        HashMap<Long, Integer> c = cached;
        if (!dirty && c != null) {
            return c;
        }
        HashMap<Long, Integer> m = new HashMap<>();
        String csv = PrizmaConfig.dialogColors();
        if (csv != null && !csv.isEmpty()) {
            for (String part : csv.split(",")) {
                try {
                    String[] kv = part.split(":");
                    if (kv.length == 2) {
                        m.put(Long.parseLong(kv[0].trim()), Integer.parseInt(kv[1].trim()));
                    }
                } catch (Throwable ignore) {}
            }
        }
        cached = m;
        dirty = false;
        return m;
    }

    /** 0 = none; otherwise an index into COLORS. */
    public static int index(long dialogId) {
        Integer v = map().get(dialogId);
        if (v == null || v <= 0 || v >= COLORS.length) return 0;
        return v;
    }

    /** The ARGB color for a chat, or 0 if none. */
    public static int color(long dialogId) {
        int i = index(dialogId);
        return i == 0 ? 0 : COLORS[i];
    }

    public static void set(long dialogId, int idx) {
        HashMap<Long, Integer> m = map();
        if (idx <= 0) {
            m.remove(dialogId);
        } else {
            m.put(dialogId, idx);
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (HashMap.Entry<Long, Integer> e : m.entrySet()) {
            if (!first) sb.append(",");
            sb.append(e.getKey()).append(":").append(e.getValue());
            first = false;
        }
        PrizmaConfig.setDialogColors(sb.toString());
        dirty = true; // invalidate the draw-path cache
    }

    /** Small sheet of color swatches to tag a chat. */
    public static void showPicker(Context ctx, long dialogId, Runnable onChanged) {
        final int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16));

        TextView title = new TextView(ctx);
        title.setText("Цветная метка чата");
        title.setTextColor(textColor);
        title.setTextSize(19);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        final BottomSheet[] ref = new BottomSheet[1];
        final int cur = index(dialogId);
        final int cols = 5;
        LinearLayout rowLay = null;
        for (int i = 0; i < COLORS.length; i++) {
            if (i % cols == 0) {
                rowLay = new LinearLayout(ctx);
                rowLay.setOrientation(LinearLayout.HORIZONTAL);
                root.addView(rowLay, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));
            }
            final int idx = i;
            View sw = swatch(ctx, i, i == cur);
            sw.setOnClickListener(v -> {
                set(dialogId, idx);
                if (ref[0] != null) ref[0].dismiss();
                if (onChanged != null) onChanged.run();
            });
            rowLay.addView(sw, LayoutHelper.createLinear(0, 66, 1f, 4, 0, 4, 0));
        }

        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(root);
        ref[0] = b.create();
        ref[0].show();
    }

    private static View swatch(Context ctx, int index, boolean selected) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        View circle = new View(ctx);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (index == 0) {
            g.setColor(0x11808080);
            g.setStroke(AndroidUtilities.dp(1.5f), 0x66808080);
        } else {
            g.setColor(COLORS[index]);
        }
        if (selected) {
            g.setStroke(AndroidUtilities.dp(3), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
        circle.setBackground(g);
        box.addView(circle, LayoutHelper.createLinear(34, 34, Gravity.CENTER_HORIZONTAL));

        TextView name = new TextView(ctx);
        name.setText(NAMES[index]);
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        name.setTextSize(9.5f);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(1);
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
        return box;
    }
}
