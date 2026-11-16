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

public class PrizmaEqualizerSheet {
    private static PrizmaGlass.Sheet current;
    private static String selectedPreset;

    public static void show(Context context) {
        show(context, null, PrizmaGlass.DEFAULT_ACCENT);
    }

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
        PillRow presets;
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
                    selectedPreset = null;
                    fillPresets();
                }
                if (finished) {
                    updateStatus();
                } else {
                    status.setText(freqFull(freqs[band]) + "  ·  " + dbLabel(level));
                }
            });
            root.addView(graph, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 214, 0, 16, 0, 0));

            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            presets = new PillRow(context, accent);
            presets.setOrientation(LinearLayout.HORIZONTAL);
            presets.setGravity(Gravity.CENTER_VERTICAL);
            scroll.addView(presets);
            presetsScroll = scroll;
            root.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, -6, 14, -6, 0));
            fillPresets();

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
            s.setOnDismissListener(d -> {
                if (current == s) current = null;
            });
            s.show();
        }

        private void fillPresets() {
            if (presets == null) return;
            presets.removeAllViews();
            View chosen = null;
            short count = eq.getNumberOfPresets();
            for (short i = 0; i < count; i++) {
                final short preset = i;
                final String key = "p:" + i;
                TextView item = presetItem(prettyPreset(eq.getPresetName(i)), key.equals(selectedPreset), v -> {
                    eq.usePreset(preset);
                    if (!eq.isEnabled()) setEnabled(true);
                    selectedPreset = key;
                    syncFromEq(true);
                });
                if (key.equals(selectedPreset)) chosen = item;
                presets.addView(item);
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
                if (key.equals(selectedPreset)) chosen = item;
                presets.addView(item);
            }
            TextView save = presetItem("Сохранить…", false, v -> showSaveDialog());
            save.setTextColor(PrizmaGlass.TEXT_3);
            presets.addView(save);
            presets.select(chosen);
        }

        private TextView presetItem(String text, boolean selected, View.OnClickListener onClick) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setSingleLine(true);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            t.setTextColor(selected ? PrizmaGlass.TEXT : PrizmaGlass.TEXT_2);
            t.setTypeface(selected ? AndroidUtilities.bold() : null);
            t.setGravity(Gravity.CENTER);
            t.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
            t.setOnClickListener(v -> {
                PrizmaMotion.pop(v);
                onClick.onClick(v);
            });
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
            slider.setColors(accent, 0x26ffffff);
            slider.setTicks(true);
            slider.setLabel(v -> Math.round(v * 100) + "");
            slider.setValue(strength / 1000f);
            slider.setOnChange((v, fromUser) -> {
                if (!fromUser) return;
                int s = (int) (v * 1000);
                if (isBass) eq.setBassStrength(s); else eq.setVirtStrength(s);
                value.setText(percent(s));
                if (s > 0 && !eq.isEnabled()) setEnabled(true);
            });
            root.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 68, -12, -30, -12, 0));
            return slider;
        }

        private void setEnabled(boolean on) {
            eq.setEnabled(on);
            toggle.setChecked(on, true);
            setEnabledLook(on, true);
            updateStatus();
        }

        private void setEnabledLook(boolean on, boolean animated) {
            float a = on ? 1f : 0.4f;
            View[] views = {graph, presetsScroll, bass, virt};
            for (View v : views) {
                if (v == null) continue;
                if (animated) PrizmaMotion.to(v, androidx.dynamicanimation.animation.DynamicAnimation.ALPHA, a, PrizmaMotion.FADE);
                else v.setAlpha(a);
            }
        }

        private void syncFromEq(boolean animate) {
            if (graph != null) {
                int bands = eq.getNumberOfBands();
                short[] levels = new short[bands];
                for (int b = 0; b < bands; b++) levels[b] = eq.getBandLevel(b);
                graph.setLevels(levels, animate);
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

    private static final class PillRow extends LinearLayout {
        private final PrizmaMotion.Stretch pill = new PrizmaMotion.Stretch();
        private final PrizmaMotion.Value shown = new PrizmaMotion.Value(0f, PrizmaMotion.FADE);
        private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private View selected;

        PillRow(Context c, int accent) {
            super(c);
            setWillNotDraw(false);
            paint.setColor((accent & 0x00ffffff) | 0x38000000);
        }

        void select(View v) {
            selected = v;
            shown.t = v == null ? 0f : 1f;
            clock.reset();
            requestLayout();
            invalidate();
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            if (selected != null && selected.getParent() == this) {
                pill.moveTo(selected.getLeft(), selected.getRight(), pill.placed() && shown.x > 0.05f);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            boolean moving = pill.placed() && pill.step();
            moving |= PrizmaMotion.run(clock.tick(), shown);
            float a = PrizmaMotion.clamp(shown.x, 0f, 1f);
            if (pill.placed() && a > 0.01f) {
                float inset = AndroidUtilities.dp(3);
                rect.set(pill.left(), inset, pill.right(), getHeight() - inset);
                float stretch = Math.max(0f, rect.width() - (selected != null ? selected.getWidth() : rect.width()));
                rect.inset(0, Math.min(AndroidUtilities.dp(4), stretch * 0.03f));
                int base = paint.getAlpha();
                paint.setAlpha((int) (base * a));
                canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint);
                paint.setAlpha(base);
            }
            super.dispatchDraw(canvas);
            if (moving) postInvalidateOnAnimation();
        }
    }

    interface BandListener {
        void onBand(int band, short level, boolean done);
    }

    private static class GraphicEq extends View {
        private final int bands;
        private final int[] freqs;
        private final short min, max;
        private final float[] value;
        private final float[] display;
        private final float[] xs;
        private BandListener listener;
        private int dragging = -1;
        private int lastDragged = -1;
        private boolean snapped;
        private final int accent;
        private final PrizmaMotion.Value[] springs;
        private final PrizmaMotion.Value[] all;
        private final long[] wait;
        private final PrizmaMotion.Value drag = new PrizmaMotion.Value(0f, PrizmaMotion.FADE);
        private final PrizmaMotion.Value bubble = new PrizmaMotion.Value(0f, PrizmaMotion.BUBBLE);
        private final PrizmaMotion.Clock clock = new PrizmaMotion.Clock();
        private final Paint pillar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint drop = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint dropText = new TextPaint(Paint.ANTI_ALIAS_FLAG);

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
            this.accent = accent;
            springs = new PrizmaMotion.Value[bands];
            wait = new long[bands];
            for (int i = 0; i < bands; i++) springs[i] = new PrizmaMotion.Value(0f, PrizmaMotion.SETTLE);
            all = new PrizmaMotion.Value[bands + 2];
            System.arraycopy(springs, 0, all, 0, bands);
            all[bands] = drag;
            all[bands + 1] = bubble;
            pillar.setColor((accent & 0x00ffffff) | 0x2e000000);
            drop.setColor(accent);
            dropText.setTextAlign(Paint.Align.CENTER);
            dropText.setTypeface(AndroidUtilities.bold());
            dropText.setTextSize(AndroidUtilities.dp(12));
            int cr = (accent >> 16) & 0xff, cg = (accent >> 8) & 0xff, cb = accent & 0xff;
            dropText.setColor((cr * 299 + cg * 587 + cb * 114) / 1000 > 150 ? 0xff111114 : 0xffffffff);

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
            long now = System.currentTimeMillis();
            for (int i = 0; i < bands && i < levels.length; i++) {
                value[i] = toValue(levels[i]);
                if (!animate || PrizmaMotion.calm()) {
                    springs[i].snap(value[i]);
                    display[i] = value[i];
                    wait[i] = 0;
                } else {
                    springs[i].tune(PrizmaMotion.SETTLE);
                    wait[i] = now + (long) i * PrizmaMotion.STAGGER;
                }
            }
            clock.reset();
            invalidate();
        }

        private float toValue(short level) {
            return Math.max(0f, Math.min(1f, (level - min) / (float) (max - min)));
        }

        private short toLevel(float v) {
            return (short) Math.round(min + v * (max - min));
        }

        private float gutter() {
            return AndroidUtilities.dp(30);
        }

        private float top() {
            return AndroidUtilities.dp(12);
        }

        private float bottom() {
            return getHeight() - AndroidUtilities.dp(26);
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
                    drag.t = 1f;
                    bubble.t = 1f;
                    springs[dragging].tune(PrizmaMotion.FOLLOW);
                    wait[dragging] = 0;
                    clock.reset();
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
                    if (dragging >= 0) {
                        move(e.getY(), true);
                        springs[dragging].tune(PrizmaMotion.RELEASE);
                        springs[dragging].t = value[dragging];
                    }
                    drag.t = 0f;
                    bubble.t = 0f;
                    dragging = -1;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private void move(float y, boolean done) {
            float raw = (bottom() - y) / Math.max(1f, bottom() - top());
            float v = Math.max(0f, Math.min(1f, raw));
            boolean inSnap = min < 0 && max > 0 && Math.abs(v - toValue((short) 0)) < 0.03f;
            if (inSnap) {
                v = toValue((short) 0);
                if (!snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            snapped = inSnap;
            value[dragging] = v;
            springs[dragging].t = inSnap ? v : PrizmaMotion.rubber(raw, 0f, 1f, 0.06f);
            if (listener != null) listener.onBand(dragging, toLevel(v), done);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getWidth();
            if (w <= 0 || bands == 0) return;
            long now = System.currentTimeMillis();
            boolean moving = false;
            for (int i = 0; i < bands; i++) {
                if (wait[i] != 0 && now >= wait[i]) {
                    springs[i].t = value[i];
                    wait[i] = 0;
                }
                if (wait[i] != 0) moving = true;
            }
            moving |= PrizmaMotion.run(clock.tick(), all);
            for (int i = 0; i < bands; i++) display[i] = springs[i].x;
            float dragProgress = PrizmaMotion.clamp(drag.x, 0f, 1f);

            final float top = top(), bottom = bottom(), g = gutter();
            final float zeroY = yOf(toValue((short) 0));
            final float textDy = AndroidUtilities.dp(3.5f);

            canvas.drawLine(g, top, w, top, hair);
            canvas.drawLine(g, bottom, w, bottom, hair);
            canvas.drawLine(g, zeroY, w, zeroY, zero);
            canvas.drawText(dbShort(max), g - AndroidUtilities.dp(8), top + textDy, scale);
            canvas.drawText("0", g - AndroidUtilities.dp(8), zeroY + textDy, scale);
            canvas.drawText(dbShort(min), g - AndroidUtilities.dp(8), bottom + textDy, scale);

            if (lastDragged >= 0 && dragProgress > 0.01f) {
                float col = (w - g) / (float) bands;
                column.setAlpha((int) (0x0f * dragProgress));
                rect.set(xs[lastDragged] - col / 2f + AndroidUtilities.dp(3), top - AndroidUtilities.dp(6), xs[lastDragged] + col / 2f - AndroidUtilities.dp(3), bottom + AndroidUtilities.dp(6));
                canvas.drawRoundRect(rect, AndroidUtilities.dp(10), AndroidUtilities.dp(10), column);
            }

            final float trackW = AndroidUtilities.dpf2(2f);
            final float pillarW = AndroidUtilities.dpf2(10f);
            for (int i = 0; i < bands; i++) {
                rect.set(xs[i] - trackW / 2f, top, xs[i] + trackW / 2f, bottom);
                canvas.drawRoundRect(rect, trackW / 2f, trackW / 2f, track);
                float ky = yOf(display[i]);
                if (Math.abs(ky - zeroY) > AndroidUtilities.dp(2)) {
                    rect.set(xs[i] - pillarW / 2f, Math.min(ky, zeroY), xs[i] + pillarW / 2f, Math.max(ky, zeroY));
                    canvas.drawRoundRect(rect, pillarW / 2f, pillarW / 2f, pillar);
                }
            }

            buildCurve(g, w);
            fillPath.set(curvePath);
            fillPath.lineTo(w, zeroY);
            fillPath.lineTo(g, zeroY);
            fillPath.close();
            canvas.drawPath(fillPath, fill);
            canvas.drawPath(curvePath, curve);

            for (int i = 0; i < bands; i++) {
                float cx = xs[i], cy = yOf(display[i]);
                float grow = i == lastDragged ? dragProgress : 0f;
                float r = AndroidUtilities.dpf2(7.5f) + AndroidUtilities.dpf2(2.5f) * grow;
                float st = 1f + Math.min(Math.abs(springs[i].v) / 6f, 0.35f);
                rect.set(cx - r / st, cy - r * st + AndroidUtilities.dpf2(1f), cx + r / st, cy + r * st + AndroidUtilities.dpf2(1f));
                canvas.drawOval(rect, knobShadow);
                rect.set(cx - r / st, cy - r * st, cx + r / st, cy + r * st);
                canvas.drawOval(rect, knob);
                if (grow > 0.01f) {
                    ring.setAlpha((int) (255 * grow));
                    canvas.drawCircle(cx, cy, r + AndroidUtilities.dpf2(3f), ring);
                }
                canvas.drawText(freqShort(freqs[i]), cx, getHeight() - AndroidUtilities.dp(6), freq);
            }
            float up = PrizmaMotion.clamp(bubble.x, 0f, 1.2f);
            if (lastDragged >= 0 && up > 0.02f) {
                float cx = xs[lastDragged], cy = yOf(display[lastDragged]);
                String label = dbLabel(toLevel(value[lastDragged]));
                float tw = dropText.measureText(label) + AndroidUtilities.dp(16);
                float th = AndroidUtilities.dp(24);
                float by = cy - AndroidUtilities.dp(22) - th / 2f - AndroidUtilities.dp(6) * up;
                if (by - th / 2f < 0) by = cy + AndroidUtilities.dp(22) + th / 2f;
                float bx = Math.max(gutter() + tw / 2f, Math.min(w - tw / 2f, cx));
                float sc = Math.min(1f, up);
                canvas.save();
                canvas.scale(sc, sc, cx, cy);
                drop.setAlpha((int) (255 * Math.min(1f, up)));
                rect.set(bx - tw / 2f, by - th / 2f, bx + tw / 2f, by + th / 2f);
                canvas.drawRoundRect(rect, th / 2f, th / 2f, drop);
                dropText.setAlpha((int) (255 * PrizmaMotion.clamp((up - 0.6f) / 0.3f, 0f, 1f)));
                canvas.drawText(label, bx, by + AndroidUtilities.dp(4), dropText);
                canvas.restore();
            }
            if (moving || dragging >= 0) postInvalidateOnAnimation();
        }

        private void buildCurve(float left, int w) {
            curvePath.rewind();
            float y0 = yOf(display[0]);
            curvePath.moveTo(left, y0);
            curvePath.lineTo(xs[0], y0);
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
