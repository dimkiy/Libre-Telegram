package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaEqualizer;
import org.telegram.ui.ActionBar.AlertDialog;

/*
 * Libre — что это за файл:
 * Экран эквалайзера.
 */

/**
 * Prizma: the equalizer. Floats over a frosted copy of the player. The centre piece is a graphic EQ
 * drawn like a hardware unit — a dB scale, thin fader tracks and one clean response curve through
 * the knobs; picking a preset morphs the curve. Colour is kept for what you touch (the switch and
 * the knob being dragged); everything else is white on glass.
 */
public class PrizmaEqualizerSheet {

    private static PrizmaGlass.Sheet current;
    private static String selectedPreset; // "p:<index>" built-in / "c:<index>" own

    public static void show(Context context) {
        show(context, null, PrizmaGlass.DEFAULT_ACCENT);
    }

    /** @param behind the view whose picture becomes the frosted backdrop (the player); null = the activity */
    public static void show(Context context, View behind, int accent) {
        if (context == null) return;
        if (behind == null) {
            android.app.Activity a = AndroidUtilities.findActivity(context);
            if (a != null && a.getWindow() != null) behind = a.getWindow().getDecorView();
        }
        if (accent == 0) accent = PrizmaGlass.DEFAULT_ACCENT;
        new Builder(context, accent).show(behind);
    }

    private static class Builder {
        final Context context;
        final int accent;
        final PrizmaEqualizer eq = PrizmaEqualizer.getInstance();
        PrizmaGlass.Sheet sheet;
        PrizmaGlass.Toggle toggle;
        TextView status;
        GraphicEq graph;
        LinearLayout presets;
        View presetsScroll;
        PrizmaSlider bass, virt;
        TextView bassValue, virtValue;
        int[] freqs;

        Builder(Context context, int accent) {
            this.context = context;
            this.accent = accent;
        }

        void show(View behind) {
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(18), AndroidUtilities.dp(20), AndroidUtilities.dp(10));

            // ---- header: title + "Готово" like an iOS sheet ----
            LinearLayout header = new LinearLayout(context);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(context);
            title.setText("Эквалайзер");
            title.setTextColor(PrizmaGlass.TEXT);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            title.setTypeface(AndroidUtilities.bold());
            header.addView(title, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            TextView done = new TextView(context);
            done.setText("Готово");
            done.setTextColor(PrizmaGlass.TEXT);
            done.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            done.setTypeface(AndroidUtilities.bold());
            done.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6));
            done.setOnClickListener(v -> {
                if (sheet != null) sheet.dismiss();
            });
            header.addView(done, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
            root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            // ---- state line: what is applied (or the value being dragged) + the switch ----
            LinearLayout stateRow = new LinearLayout(context);
            stateRow.setOrientation(LinearLayout.HORIZONTAL);
            stateRow.setGravity(Gravity.CENTER_VERTICAL);
            status = new TextView(context);
            status.setTextColor(PrizmaGlass.TEXT_2);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            status.setSingleLine(true);
            stateRow.addView(status, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));
            toggle = new PrizmaGlass.Toggle(context);
            toggle.setAccent(accent);
            toggle.setChecked(eq.isEnabled(), false);
            stateRow.addView(toggle, LayoutHelper.createLinear(46, 28, Gravity.CENTER_VERTICAL));
            stateRow.setOnClickListener(v -> setEnabled(!eq.isEnabled()));
            root.addView(stateRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            if (!eq.isReady()) {
                status.setText("Ждёт музыку");
                TextView note = new TextView(context);
                note.setText("Включите любой трек — эквалайзер подключится к нему, и здесь появятся полосы частот.");
                note.setTextColor(PrizmaGlass.TEXT_2);
                note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                note.setLineSpacing(AndroidUtilities.dp(2), 1f);
                root.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 22, 0, 14));
                open(behind, root);
                return;
            }

            // ---- the graphic EQ ----
            final int bands = eq.getNumberOfBands();
            freqs = new int[bands];
            short[] levels = new short[bands];
            for (int b = 0; b < bands; b++) {
                freqs[b] = eq.getCenterFreq(b) / 1000;
                levels[b] = eq.getBandLevel(b);
            }
            graph = new GraphicEq(context, freqs, eq.getMinLevel(), eq.getMaxLevel(), accent);
            graph.setLevels(levels, false);
            graph.setListener((band, level, finished) -> {
                eq.setBandLevel(band, level);
                if (!eq.isEnabled()) setEnabled(true);
                if (selectedPreset != null) {
                    selectedPreset = null; // a hand-made curve is no longer that preset
                    fillPresets();
                }
                if (finished) {
                    updateStatus();
                } else {
                    status.setText(freqFull(freqs[band]) + "  ·  " + dbLabel(level)); // live readout
                }
            });
            root.addView(graph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 214, 0, 16, 0, 0));

            // ---- presets: one line of text, the applied one marked with a dot ----
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            presets = new LinearLayout(context);
            presets.setOrientation(LinearLayout.HORIZONTAL);
            presets.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(presets);
            presetsScroll = scroll;
            root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -6, 14, -6, 0));
            fillPresets();

            // ---- effects ----
            bassValue = new TextView(context);
            bass = effect(root, "Бас", eq.getBassStrength(), bassValue, true, 20);
            virtValue = new TextView(context);
            virt = effect(root, "Объём", eq.getVirtStrength(), virtValue, false, 6);

            TextView reset = new TextView(context);
            reset.setText("Сбросить настройки");
            reset.setTextColor(PrizmaGlass.TEXT_3);
            reset.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            reset.setGravity(Gravity.CENTER);
            reset.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(10));
            reset.setOnClickListener(v -> {
                eq.resetAll();
                selectedPreset = null;
                syncFromEq(true);
            });
            root.addView(reset, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

            setEnabledLook(eq.isEnabled(), false);
            updateStatus();
            open(behind, root);
        }

        private void open(View behind, View content) {
            try {
                if (current != null) current.dismiss();
            } catch (Throwable ignore) {
            }
            final PrizmaGlass.Sheet s = new PrizmaGlass.Sheet(context, behind, content);
            sheet = s;
            current = s;
            // the previous sheet finishes its close animation later — it must not clear the new one
            s.setOnDismissListener(d -> {
                if (current == s) current = null;
            });
            s.show();
        }

        private void fillPresets() {
            if (presets == null) return;
            presets.removeAllViews();
            short count = eq.getNumberOfPresets();
            for (short i = 0; i < count; i++) {
                final short preset = i;
                final String key = "p:" + i;
                presets.addView(presetItem(prettyPreset(eq.getPresetName(i)), key.equals(selectedPreset), v -> {
                    eq.usePreset(preset);
                    if (!eq.isEnabled()) setEnabled(true);
                    selectedPreset = key;
                    syncFromEq(true);
                }));
            }
            int custom = eq.getCustomCount();
            if (custom > 0) {
                View divider = new View(context);
                divider.setBackgroundColor(0x33ffffff);
                presets.addView(divider, LayoutHelper.createLinear(1, 16, Gravity.CENTER_VERTICAL, 6, 0, 6, 6));
            }
            for (int i = 0; i < custom; i++) {
                final int idx = i;
                final String key = "c:" + i;
                TextView item = presetItem(eq.getCustomName(i), key.equals(selectedPreset), v -> {
                    eq.applyCustomPreset(idx);
                    if (!eq.isEnabled()) setEnabled(true);
                    selectedPreset = key;
                    syncFromEq(true);
                });
                item.setOnLongClickListener(v -> {
                    AlertDialog.Builder b = new AlertDialog.Builder(context);
                    b.setTitle("Удалить пресет?");
                    b.setMessage(eq.getCustomName(idx));
                    b.setPositiveButton("Удалить", (d, w) -> {
                        eq.deleteCustomPreset(idx);
                        selectedPreset = null;
                        fillPresets();
                        updateStatus();
                    });
                    b.setNegativeButton("Отмена", null);
                    b.show();
                    return true;
                });
                presets.addView(item);
            }
            TextView save = presetItem("Сохранить…", false, v -> showSaveDialog());
            save.setTextColor(PrizmaGlass.TEXT_3);
            presets.addView(save);
        }

        private TextView presetItem(String text, boolean selected, View.OnClickListener onClick) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setSingleLine(true);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            t.setTextColor(selected ? PrizmaGlass.TEXT : PrizmaGlass.TEXT_2);
            t.setTypeface(selected ? AndroidUtilities.bold() : null);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            t.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(4));
            // the applied preset gets a small dot underneath (an empty one keeps the baseline even)
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(selected ? 0xffffffff : 0x00000000);
            dot.setSize(AndroidUtilities.dp(4), AndroidUtilities.dp(4));
            t.setCompoundDrawablesWithIntrinsicBounds(null, null, null, dot);
            t.setCompoundDrawablePadding(AndroidUtilities.dp(4));
            t.setOnClickListener(onClick);
            return t;
        }

        private PrizmaSlider effect(LinearLayout root, String name, int strength, TextView value, boolean isBass, int topMargin) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView n = new TextView(context);
            n.setText(name);
            n.setTextColor(PrizmaGlass.TEXT);
            n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            line.addView(n, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
            value.setTextColor(PrizmaGlass.TEXT_2);
            value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            value.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
            value.setText(percent(strength));
            line.addView(value);
            root.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, topMargin, 0, 0));

            PrizmaSlider slider = new PrizmaSlider(context);
            slider.setColors(0xf2ffffff, 0x26ffffff);
            slider.setValue(strength / 1000f);
            slider.setOnChange((v, fromUser) -> {
                if (!fromUser) return;
                int s = (int) (v * 1000);
                if (isBass) eq.setBassStrength(s); else eq.setVirtStrength(s);
                value.setText(percent(s));
                if (s > 0 && !eq.isEnabled()) setEnabled(true);
            });
            root.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, -12, 0, -12, 0));
            return slider;
        }

        private void setEnabled(boolean on) {
            eq.setEnabled(on);
            toggle.setChecked(on, true);
            setEnabledLook(on, true);
            updateStatus();
        }

        /** Off = everything stays visible but recedes: the settings are kept, just bypassed. */
        private void setEnabledLook(boolean on, boolean animated) {
            float a = on ? 1f : 0.4f;
            View[] views = {graph, presetsScroll, bass, virt};
            for (View v : views) {
                if (v == null) continue;
                if (animated) v.animate().alpha(a).setDuration(220).start();
                else v.setAlpha(a);
            }
        }

        private void syncFromEq(boolean animate) {
            if (graph != null) {
                int bands = eq.getNumberOfBands();
                short[] levels = new short[bands];
                for (int b = 0; b < bands; b++) levels[b] = eq.getBandLevel(b);
                graph.setLevels(levels, animate); // the curve morphs into the new shape
            }
            if (bass != null) {
                bass.setValue(eq.getBassStrength() / 1000f);
                bassValue.setText(percent(eq.getBassStrength()));
            }
            if (virt != null) {
                virt.setValue(eq.getVirtStrength() / 1000f);
                virtValue.setText(percent(eq.getVirtStrength()));
            }
            fillPresets();
            updateStatus();
        }

        private void updateStatus() {
            if (status == null) return;
            if (!eq.isEnabled()) {
                status.setText("Выключен");
                return;
            }
            String preset = null;
            if (selectedPreset != null) {
                try {
                    int idx = Integer.parseInt(selectedPreset.substring(2));
                    preset = selectedPreset.startsWith("p:") ? prettyPreset(eq.getPresetName((short) idx)) : eq.getCustomName(idx);
                } catch (Throwable ignore) {
                }
            }
            status.setText(preset != null ? preset : "Своя настройка");
        }

        private void showSaveDialog() {
            final EditText input = new EditText(context);
            input.setText("Мой пресет");
            input.setSelectAllOnFocus(true);
            input.setSingleLine(true);
            int pad = AndroidUtilities.dp(22);
            LinearLayout wrap = new LinearLayout(context);
            wrap.setPadding(pad, AndroidUtilities.dp(4), pad, 0);
            wrap.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("Название пресета");
            b.setView(wrap);
            b.setPositiveButton("Сохранить", (d, w) -> {
                eq.saveCustomPreset(input.getText() == null ? "" : input.getText().toString());
                selectedPreset = "c:" + (eq.getCustomCount() - 1);
                fillPresets();
                updateStatus();
            });
            b.setNegativeButton("Отмена", null);
            b.show();
        }
    }

    // ===================== graphic EQ =====================

    interface BandListener {
        void onBand(int band, short level, boolean done);
    }

    /** dB scale on the left, a thin fader per band, one response curve through the knobs. */
    private static class GraphicEq extends View {
        private final int bands;
        private final int[] freqs;
        private final short min, max;
        private final float[] value;   // 0..1 target
        private final float[] display; // 0..1 drawn (eases toward value → morphing)
        private final float[] xs;
        private BandListener listener;
        private int dragging = -1;
        private int lastDragged = -1;
        private float dragProgress;
        private boolean snapped;
        private long lastTime;

        private final Paint hair = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint zero = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint column = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint curve = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint scale = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint freq = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path curvePath = new Path();
        private final Path fillPath = new Path();
        private final RectF rect = new RectF();

        GraphicEq(Context context, int[] freqs, short min, short max, int accent) {
            super(context);
            this.bands = freqs.length;
            this.freqs = freqs;
            this.min = min;
            this.max = (short) Math.max(max, min + 1);
            value = new float[bands];
            display = new float[bands];
            xs = new float[bands];

            hair.setColor(0x17ffffff);
            hair.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(0.6f)));
            zero.setColor(0x38ffffff);
            zero.setStrokeWidth(Math.max(1f, AndroidUtilities.dpf2(1f)));
            track.setColor(0x24ffffff);
            column.setColor(0x0fffffff);
            curve.setStyle(Paint.Style.STROKE);
            curve.setStrokeWidth(AndroidUtilities.dpf2(2f));
            curve.setStrokeCap(Paint.Cap.ROUND);
            curve.setStrokeJoin(Paint.Join.ROUND);
            curve.setColor(0xe6ffffff);
            fill.setColor(0x10ffffff);
            knob.setColor(0xffffffff);
            knobShadow.setColor(0x47000000);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(AndroidUtilities.dpf2(2f));
            ring.setColor(accent);
            scale.setColor(PrizmaGlass.TEXT_3);
            scale.setTextSize(AndroidUtilities.dp(10));
            scale.setTextAlign(Paint.Align.RIGHT);
            scale.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
            freq.setColor(PrizmaGlass.TEXT_2);
            freq.setTextSize(AndroidUtilities.dp(11));
            freq.setTextAlign(Paint.Align.CENTER);
            freq.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        }

        void setListener(BandListener l) {
            listener = l;
        }

        void setLevels(short[] levels, boolean animate) {
            for (int i = 0; i < bands && i < levels.length; i++) {
                value[i] = toValue(levels[i]);
                if (!animate) display[i] = value[i];
            }
            invalidate();
        }

        private float toValue(short level) {
            return Math.max(0f, Math.min(1f, (level - min) / (float) (max - min)));
        }

        private short toLevel(float v) {
            return (short) Math.round(min + v * (max - min));
        }

        private float gutter() {
            return AndroidUtilities.dp(30); // dB scale
        }

        private float top() {
            return AndroidUtilities.dp(12);
        }

        private float bottom() {
            return getHeight() - AndroidUtilities.dp(26); // frequency labels below
        }

        private float yOf(float v) {
            return bottom() - v * (bottom() - top());
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float g = gutter();
            float col = (w - g) / (float) bands;
            for (int i = 0; i < bands; i++) xs[i] = g + col * (i + 0.5f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    float col = (getWidth() - gutter()) / (float) bands;
                    dragging = Math.max(0, Math.min(bands - 1, (int) ((e.getX() - gutter()) / col)));
                    lastDragged = dragging;
                    snapped = Math.abs(value[dragging] - toValue((short) 0)) < 0.001f;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    move(e.getY(), false);
                    return true;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging >= 0) move(e.getY(), false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging >= 0) move(e.getY(), true);
                    dragging = -1;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void move(float y, boolean done) {
            float v = (bottom() - y) / Math.max(1f, bottom() - top());
            v = Math.max(0f, Math.min(1f, v));
            // a gentle magnet at 0 dB with a tick, so "flat" is easy to hit exactly
            boolean inSnap = min < 0 && max > 0 && Math.abs(v - toValue((short) 0)) < 0.03f;
            if (inSnap) {
                v = toValue((short) 0);
                if (!snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            snapped = inSnap;
            value[dragging] = v;
            display[dragging] = v; // the finger leads, no lag
            if (listener != null) listener.onBand(dragging, toLevel(v), done);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            if (w <= 0 || bands == 0) return;
            long now = System.currentTimeMillis();
            float dt = lastTime == 0 ? 16 : Math.min(40, now - lastTime);
            lastTime = now;
            boolean moving = false;
            float k = Math.min(1f, dt / 140f * 2.2f);
            for (int i = 0; i < bands; i++) {
                float d = value[i] - display[i];
                if (Math.abs(d) > 0.0015f) {
                    display[i] += d * k;
                    moving = true;
                } else {
                    display[i] = value[i];
                }
            }
            float targetDrag = dragging >= 0 ? 1f : 0f;
            dragProgress += (targetDrag - dragProgress) * Math.min(1f, dt / 90f * 2f);
            if (Math.abs(targetDrag - dragProgress) > 0.01f) moving = true;
            else dragProgress = targetDrag;

            final float top = top(), bottom = bottom(), g = gutter();
            final float zeroY = yOf(toValue((short) 0));
            final float textDy = AndroidUtilities.dp(3.5f);

            // scale: +max / 0 / min
            canvas.drawLine(g, top, w, top, hair);
            canvas.drawLine(g, bottom, w, bottom, hair);
            canvas.drawLine(g, zeroY, w, zeroY, zero);
            canvas.drawText(dbShort(max), g - AndroidUtilities.dp(8), top + textDy, scale);
            canvas.drawText("0", g - AndroidUtilities.dp(8), zeroY + textDy, scale);
            canvas.drawText(dbShort(min), g - AndroidUtilities.dp(8), bottom + textDy, scale);

            // the column being touched lifts a little
            if (lastDragged >= 0 && dragProgress > 0.01f) {
                float col = (w - g) / (float) bands;
                column.setAlpha((int) (0x0f * dragProgress));
                rect.set(xs[lastDragged] - col / 2f + AndroidUtilities.dp(3), top - AndroidUtilities.dp(6), xs[lastDragged] + col / 2f - AndroidUtilities.dp(3), bottom + AndroidUtilities.dp(6));
                canvas.drawRoundRect(rect, AndroidUtilities.dp(10), AndroidUtilities.dp(10), column);
            }

            // fader tracks
            final float trackW = AndroidUtilities.dpf2(2f);
            for (int i = 0; i < bands; i++) {
                rect.set(xs[i] - trackW / 2f, top, xs[i] + trackW / 2f, bottom);
                canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, track);
            }

            // response curve + a faint wash between it and 0 dB
            buildCurve(g, w);
            fillPath.set(curvePath);
            fillPath.lineTo(w, zeroY);
            fillPath.lineTo(g, zeroY);
            fillPath.close();
            canvas.drawPath(fillPath, fill);
            canvas.drawPath(curvePath, curve);

            // knobs + frequencies
            for (int i = 0; i < bands; i++) {
                float cx = xs[i], cy = yOf(display[i]);
                float grow = i == lastDragged ? dragProgress : 0f;
                float r = AndroidUtilities.dpf2(7.5f) + AndroidUtilities.dpf2(2.5f) * grow;
                canvas.drawCircle(cx, cy + AndroidUtilities.dpf2(1f), r, knobShadow);
                canvas.drawCircle(cx, cy, r, knob);
                if (grow > 0.01f) {
                    ring.setAlpha((int) (255 * grow));
                    canvas.drawCircle(cx, cy, r + AndroidUtilities.dpf2(3f), ring);
                }
                canvas.drawText(freqShort(freqs[i]), cx, getHeight() - AndroidUtilities.dp(6), freq);
            }
            if (moving) postInvalidateOnAnimation();
        }

        private void buildCurve(float left, int w) {
            curvePath.rewind();
            float y0 = yOf(display[0]);
            curvePath.moveTo(left, y0);
            curvePath.lineTo(xs[0], y0);
            // Catmull-Rom → cubic Bézier through the knob centres
            for (int i = 0; i < bands - 1; i++) {
                float x0 = i > 0 ? xs[i - 1] : xs[i], yy0 = yOf(display[i > 0 ? i - 1 : i]);
                float x1 = xs[i], y1 = yOf(display[i]);
                float x2 = xs[i + 1], y2 = yOf(display[i + 1]);
                float x3 = i + 2 < bands ? xs[i + 2] : xs[i + 1], y3 = yOf(display[i + 2 < bands ? i + 2 : i + 1]);
                float c1x = x1 + (x2 - x0) / 6f, c1y = y1 + (y2 - yy0) / 6f;
                float c2x = x2 - (x3 - x1) / 6f, c2y = y2 - (y3 - y1) / 6f;
                curvePath.cubicTo(c1x, c1y, c2x, c2y, x2, y2);
            }
            curvePath.lineTo(w, yOf(display[bands - 1]));
        }
    }

    // ===================== text =====================

    private static String freqShort(int hz) {
        if (hz >= 1000) {
            int khz = hz / 1000;
            int rem = (hz % 1000) / 100;
            return rem > 0 ? (khz + "," + rem + "к") : (khz + "к");
        }
        return String.valueOf(hz);
    }

    private static String freqFull(int hz) {
        if (hz >= 1000) {
            int khz = hz / 1000;
            int rem = (hz % 1000) / 100;
            return (rem > 0 ? (khz + "," + rem) : String.valueOf(khz)) + " кГц";
        }
        return hz + " Гц";
    }

    private static String dbShort(int milliBel) {
        int db = Math.round(milliBel / 100f);
        return db > 0 ? "+" + db : db < 0 ? "−" + (-db) : "0";
    }

    private static String dbLabel(int milliBel) {
        float db = milliBel / 100f;
        String s = Math.abs(db - Math.round(db)) < 0.05f
                ? String.valueOf(Math.abs(Math.round(db)))
                : String.format(java.util.Locale.US, "%.1f", Math.abs(db)).replace('.', ',');
        if (Math.abs(db) < 0.05f) return "0 дБ";
        return (db > 0 ? "+" : "−") + s + " дБ";
    }

    private static String percent(int strength) {
        return Math.round(strength / 10f) + "%";
    }

    private static String prettyPreset(String name) {
        if (name == null) return "";
        switch (name.toLowerCase()) {
            case "normal": return "Обычный";
            case "classical": return "Классика";
            case "dance": return "Танцевальный";
            case "flat": return "Ровный";
            case "folk": return "Фолк";
            case "heavy metal": return "Метал";
            case "hip hop": return "Хип-хоп";
            case "jazz": return "Джаз";
            case "pop": return "Поп";
            case "rock": return "Рок";
            default: return name;
        }
    }
}
