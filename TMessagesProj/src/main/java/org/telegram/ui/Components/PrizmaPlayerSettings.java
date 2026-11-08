package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.R;

public final class PrizmaPlayerSettings {
    public interface Flag {
        boolean get();
    }

    public interface Apply {
        void set(boolean value);
    }

    public interface Pick {
        void set(int index);
    }

    private final Context context;
    private final ViewGroup host;
    private final PrizmaPalette palette;
    private final Runnable onChange;
    private final FrameLayout overlay;
    private final View scrim;
    private final LinearLayout panel;
    private final LinearLayout content;
    private final int textColor = 0xfff2f1ee;
    private final int subColor = 0x8cf2f1ee;
    private final int cardColor;
    private boolean closing;
    private static PrizmaPlayerSettings shown;

    private final PrizmaSpring slide = new PrizmaSpring(1f, 360f, 0.86f, v -> updateSlide(v));

    public static boolean closeIfOpen() {
        if (shown != null) {
            shown.close();
            return true;
        }
        return false;
    }

    public static void show(ViewGroup host, PrizmaPalette palette, Runnable onChange) {
        new PrizmaPlayerSettings(host, palette, onChange).open();
    }

    private PrizmaPlayerSettings(ViewGroup host, PrizmaPalette palette, Runnable onChange) {
        this.host = host;
        this.context = host.getContext();
        this.palette = palette;
        this.onChange = onChange;
        cardColor = PrizmaPalette.mix(palette.surface, 0xffffffff, 0.05f);

        overlay = new FrameLayout(context);
        scrim = new View(context);
        scrim.setBackgroundColor(0x8c000000);
        scrim.setOnClickListener(v -> close());
        overlay.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        float r = AndroidUtilities.dp(30);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setColor(PrizmaPalette.mix(palette.surface, 0xff000000, 0.42f));
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setClickable(true);

        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(22), AndroidUtilities.dp(14), AndroidUtilities.dp(18) + AndroidUtilities.navigationBarHeight);
        ScrollView scroll = new ScrollView(context) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.8f), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new ScrollView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        panel.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        overlay.addView(panel, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));
        build();
    }

    private void build() {
        TextView title = new TextView(context);
        title.setText("Настройки плеера");
        title.setTextColor(textColor);
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        content.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 0, 0, 16));

        section("Внешний вид");
        LinearLayout look = card();
        look.addView(coverColorsRow());
        divider(look);
        look.addView(toggle("Фон в такт музыке", "Фон плеера пульсирует в ритме трека.", PrizmaConfig::playerBgBeat, PrizmaConfig::setPlayerBgBeat));
        divider(look);
        look.addView(stopsRow("Фон", new String[]{"Цвет", "Размытие", "Обложка", "Тёмный"}, PrizmaConfig.playerBackdrop(), v -> {
            PrizmaConfig.setPlayerBackdrop(v);
            onChange.run();
        }));
        content.addView(look);

        section("Под бит");
        LinearLayout beat = card();
        beat.addView(toggle("Кольца от обложки", "На каждый удар от обложки расходится тонкий контур.", PrizmaConfig::playerRings, PrizmaConfig::setPlayerRings));
        divider(beat);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            beat.addView(toggle("Линза на басу", "Обложка выгибается и расслаивается на цвета на каждом ударе.", PrizmaConfig::playerLens, PrizmaConfig::setPlayerLens));
            divider(beat);
        }
        beat.addView(toggle("Эквалайзер вокруг обложки", "Настоящие частоты трека расходятся штрихами по краю обложки.", PrizmaConfig::playerContour, PrizmaConfig::setPlayerContour));
        divider(beat);
        beat.addView(toggle("Живая капля", "За обложкой дышит пятно цвета трека, бас раздувает его.", PrizmaConfig::playerBlob, PrizmaConfig::setPlayerBlob));
        divider(beat);
        beat.addView(toggle("Частицы", "Искры разлетаются от обложки в такт музыке.", PrizmaConfig::playerSparks, PrizmaConfig::setPlayerSparks));
        divider(beat);
        beat.addView(toggle("Удар по обложке", "Обложка коротко подпрыгивает на сильных долях.", PrizmaConfig::playerCoverKick, PrizmaConfig::setPlayerCoverKick));
        divider(beat);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            beat.addView(toggle("Рябь на весь фон", "На каждый удар баса по фону идёт волна, как по воде. Тапните по фону, чтобы пустить свою.", PrizmaConfig::playerRipple, PrizmaConfig::setPlayerRipple));
            divider(beat);
            beat.addView(toggle("Капли под мелодию", "Высокие ноты падают на фон мелкими каплями в случайных местах.", PrizmaConfig::playerDrops, PrizmaConfig::setPlayerDrops));
            divider(beat);
            beat.addView(toggle("Удар цветом", "На самых сильных ударах фон на миг расслаивается на красный и синий.", PrizmaConfig::playerChroma, PrizmaConfig::setPlayerChroma));
            divider(beat);
        }
        beat.addView(toggle("Пульс краёв", "Края экрана подсвечиваются цветом обложки в такт басу.", PrizmaConfig::playerEdges, PrizmaConfig::setPlayerEdges));
        divider(beat);
        beat.addView(toggle("Вибрация в такт", "Телефон легко отзывается на удары. Тратит чуть больше заряда.", PrizmaConfig::playerHaptics, PrizmaConfig::setPlayerHaptics));
        content.addView(beat);

        section("Клипы");
        LinearLayout clips = card();
        clips.addView(toggle("Клипы вместо обложек", "Если у трека есть клип, вместо обложки играет его превью без звука.", PrizmaConfig::playerClips, PrizmaConfig::setPlayerClips));
        divider(clips);
        clips.addView(toggle("Клипы исполнителя", "Показывать другой клип этого исполнителя, если точного нет.", PrizmaConfig::playerArtistClips, PrizmaConfig::setPlayerArtistClips));
        divider(clips);
        clips.addView(toggle("Клипы по мобильному интернету", "Один клип весит около 20 МБ. Без этой настройки клипы качаются только по Wi-Fi.", PrizmaConfig::playerClipsMobile, PrizmaConfig::setPlayerClipsMobile));
        content.addView(clips);

        section("Текст песни");
        LinearLayout lyrics = card();
        lyrics.addView(toggle("Тексты из сети", "Искать текст в интернете, если в треке его нет.", PrizmaConfig::lyricsOnline, v -> {
            PrizmaConfig.setLyricsOnline(v);
            PrizmaConfig.setLyricsAsked(true);
        }));
        divider(lyrics);
        lyrics.addView(stopsRow("Прогресс строки", new String[]{"Откл.", "Слева направо", "Сверху вниз"}, PrizmaConfig.lyricsFill(), v -> {
            PrizmaConfig.setLyricsFill(v);
            onChange.run();
        }));
        content.addView(lyrics);

        section("Перемотка");
        LinearLayout seek = card();
        seek.addView(stopsRow("Цвет полосы", new String[]{"Под обложку", "Горящая", "Перелив"}, PrizmaConfig.seekStyle(), v -> {
            PrizmaConfig.setSeekStyle(v);
            onChange.run();
        }));
        content.addView(seek);

        section("Обложка");
        LinearLayout size = card();
        size.addView(toggle("3D-обложка", "Обложка наклоняется вслед за телефоном.", PrizmaConfig::coverParallax, PrizmaConfig::setCoverParallax));
        divider(size);
        size.addView(sizeRow());
        content.addView(size);
    }

    private void section(String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(palette.accent);
        t.setTextSize(14);
        t.setTypeface(AndroidUtilities.bold());
        content.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 10, 18, 0, 8));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(context);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(PrizmaShape.fill(cardColor, AndroidUtilities.dp(30)));
        return c;
    }

    private void divider(LinearLayout parent) {
        View line = new View(context);
        line.setBackgroundColor(0x12ffffff);
        parent.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1, 18, 0, 0, 0));
    }

    private View toggle(String name, String desc, Flag flag, Apply apply) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(textColor);
        n.setTextSize(16.5f);
        texts.addView(n);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextColor(subColor);
        d.setTextSize(13);
        d.setLineSpacing(AndroidUtilities.dp(1), 1f);
        texts.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        PrizmaSwitch sw = new PrizmaSwitch(context);
        sw.setColors(palette.accent, palette.onAccent);
        sw.setChecked(flag.get(), false);
        row.addView(sw, LayoutHelper.createLinear(50, 30, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next, true);
            apply.set(next);
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            onChange.run();
        });
        return row;
    }

    private View coverColorsRow() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        TextView n = new TextView(context);
        n.setTextColor(textColor);
        n.setTextSize(16.5f);
        row.addView(n, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        ImageView arrow = new ImageView(context);
        arrow.setImageResource(R.drawable.arrow_more);
        arrow.setColorFilter(textColor);
        arrow.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(arrow, LayoutHelper.createLinear(20, 20, Gravity.CENTER_VERTICAL, 4, 0, 0, 0));
        row.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        View sep = new View(context);
        sep.setBackgroundColor(0x1affffff);
        row.addView(sep, LayoutHelper.createLinear(1, 26, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        PrizmaSwitch sw = new PrizmaSwitch(context);
        sw.setColors(palette.accent, palette.onAccent);
        row.addView(sw, LayoutHelper.createLinear(50, 30, Gravity.CENTER_VERTICAL));
        box.addView(row);

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        final PrizmaGlass.Check bigCheck = new PrizmaGlass.Check(context);
        final PrizmaGlass.Check miniCheck = new PrizmaGlass.Check(context);
        final Runnable refresh = () -> {
            int on = (PrizmaConfig.coverColorsBig() ? 1 : 0) + (PrizmaConfig.coverColorsMini() ? 1 : 0);
            n.setText("Цвета обложки  " + on + "/2");
            sw.setChecked(on == 2, true);
            bigCheck.setChecked(PrizmaConfig.coverColorsBig(), true);
            miniCheck.setChecked(PrizmaConfig.coverColorsMini(), true);
        };
        details.addView(checkRow("Большой плеер", bigCheck, () -> {
            PrizmaConfig.setCoverColorsBig(!PrizmaConfig.coverColorsBig());
            refresh.run();
            onChange.run();
        }));
        details.addView(checkRow("Мини-плеер", miniCheck, () -> {
            PrizmaConfig.setCoverColorsMini(!PrizmaConfig.coverColorsMini());
            refresh.run();
            onChange.run();
        }));
        box.addView(details);
        bigCheck.setAccent(palette.accent);
        miniCheck.setAccent(palette.accent);
        bigCheck.setChecked(PrizmaConfig.coverColorsBig(), false);
        miniCheck.setChecked(PrizmaConfig.coverColorsMini(), false);
        int on = (PrizmaConfig.coverColorsBig() ? 1 : 0) + (PrizmaConfig.coverColorsMini() ? 1 : 0);
        n.setText("Цвета обложки  " + on + "/2");
        sw.setChecked(on == 2, false);

        sw.setOnClickListener(v -> {
            boolean all = !sw.isChecked();
            PrizmaConfig.setCoverColorsBig(all);
            PrizmaConfig.setCoverColorsMini(all);
            refresh.run();
            onChange.run();
        });
        row.setOnClickListener(v -> {
            boolean open = details.getVisibility() != View.VISIBLE;
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.animate().rotation(open ? 180f : 0f).setDuration(260).setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK).start();
            if (open) {
                details.setAlpha(0f);
                details.setTranslationY(-AndroidUtilities.dp(8));
                details.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        });
        return box;
    }

    private View checkRow(String name, View check, Runnable click) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(10), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        row.addView(check, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
        TextView t = new TextView(context);
        t.setText(name);
        t.setTextColor(textColor);
        t.setTextSize(15.5f);
        row.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> click.run());
        return row;
    }

    private View stopsRow(String name, String[] labels, int selected, Pick pick) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(13), AndroidUtilities.dp(18), AndroidUtilities.dp(14));
        TextView t = new TextView(context);
        t.setText(name);
        t.setTextColor(textColor);
        t.setTextSize(16.5f);
        box.addView(t, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
        box.addView(new Stops(context, labels, selected, palette.accent, pick), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54));
        return box;
    }

    private View sizeRow() {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(13), AndroidUtilities.dp(18), AndroidUtilities.dp(10));
        TextView t = new TextView(context);
        t.setText("Размер обложки");
        t.setTextColor(textColor);
        t.setTextSize(16.5f);
        box.addView(t);
        PrizmaSlider slider = new PrizmaSlider(context);
        slider.setColors(palette.accent, 0x26ffffff);
        final float min = 0.6f;
        slider.setValue((Math.min(1f, PrizmaConfig.playerCoverScale()) - min) / (1f - min));
        slider.setOnChange((v, fromUser) -> {
            if (!fromUser) return;
            PrizmaConfig.setPlayerCoverScale(min + v * (1f - min));
            onChange.run();
        });
        box.addView(slider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 4, 0, 0));
        return box;
    }

    private void updateSlide(float v) {
        panel.setTranslationY(panel.getHeight() * v);
        scrim.setAlpha(Math.max(0f, Math.min(1f, 1f - v)));
        if (closing && v > 0.98f) {
            AndroidUtilities.removeFromParent(overlay);
            slide.cancel();
        }
    }

    private void open() {
        shown = this;
        host.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scrim.setAlpha(0f);
        panel.setTranslationY(AndroidUtilities.displaySize.y);
        panel.post(() -> {
            slide.snap(1f);
            slide.to(0f);
        });
    }

    private void close() {
        if (closing) return;
        closing = true;
        shown = null;
        slide.to(1.02f);
    }

    private static final class Stops extends View {
        private final String[] labels;
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int accent;
        private final Pick pick;
        private int selected;
        private float pos;
        private boolean touching;
        private final PrizmaSpring spring;

        Stops(Context c, String[] labels, int selected, int accent, Pick pick) {
            super(c);
            this.labels = labels;
            this.selected = Math.max(0, Math.min(labels.length - 1, selected));
            this.accent = accent;
            this.pick = pick;
            pos = this.selected / (float) (labels.length - 1);
            spring = new PrizmaSpring(pos, PrizmaSpring.MEDIUM, 0.7f, v -> {
                pos = v;
                invalidate();
            });
            text.setTextSize(AndroidUtilities.dp(13.5f));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float inner = getWidth() - AndroidUtilities.dp(8);
            float x = Math.max(0f, Math.min(1f, (e.getX() - AndroidUtilities.dp(4)) / inner));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touching = true;
                    getParent().requestDisallowInterceptTouchEvent(true);
                case MotionEvent.ACTION_MOVE:
                    spring.snap(x);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    int next = Math.round(x * (labels.length - 1));
                    spring.to(next / (float) (labels.length - 1));
                    if (next != selected) {
                        selected = next;
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        pick.set(next);
                    }
                    invalidate();
                    return true;
            }
            return super.onTouchEvent(e);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int n = labels.length;
            float baseline = AndroidUtilities.dp(14);
            for (int i = 0; i < n; i++) {
                float at = i / (float) (n - 1);
                float x = at * w;
                text.setColor(i == selected ? accent : 0x99f2f1ee);
                float width = text.measureText(labels[i]);
                float left = i == 0 ? 0 : i == n - 1 ? w - width : x - width / 2f;
                canvas.drawText(labels[i], left, baseline, text);
            }
            float cy = h - AndroidUtilities.dp(14);
            float tall = AndroidUtilities.dp(touching ? 26 : 22);
            float thick = AndroidUtilities.dp(5);
            float gap = AndroidUtilities.dp(5);
            float tx = AndroidUtilities.dp(2) + pos * (w - AndroidUtilities.dp(4));
            line.setColor(accent);
            rect.set(0, cy - thick, Math.max(thick * 2, tx - gap), cy + thick);
            if (tx - gap > 0) canvas.drawRoundRect(rect, thick, thick, line);
            line.setColor(0x33ffffff);
            rect.set(Math.min(w - thick * 2, tx + gap), cy - thick, w, cy + thick);
            if (tx + gap < w) canvas.drawRoundRect(rect, thick, thick, line);
            line.setColor(accent);
            rect.set(tx - AndroidUtilities.dp(2), cy - tall / 2f, tx + AndroidUtilities.dp(2), cy + tall / 2f);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(2), AndroidUtilities.dp(2), line);
        }
    }
}
