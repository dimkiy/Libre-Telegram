package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaNet;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Locale;

public class PrizmaWidgetStrip extends HorizontalScrollView {
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static final String[] IDS = {"bitcoin", "the-open-network", "monero", "tether", "euro-coin"};
    private static final String[] NAMES = {"Bitcoin", "Gram", "Monero", "Доллар", "Евро"};
    private static final String[] GLYPHS = {"₿", "◆", "ɱ", "$", "€"};
    private static final int[] TINTS = {0xfff7931a, 0xff2aa3ef, 0xffff6a13, 0xff27b46e, 0xff3b6fe0};

    private static final class Quote {
        double price, change, percent;
        float[] line;
    }

    private static Quote[] quotes;
    private static long quotesAt;
    private static String weatherText;
    private static String weatherFor;
    private static long weatherAt;

    private final ArrayList<Card> cards = new ArrayList<>();
    private final Card weather;
    private Runnable onWallet;

    public PrizmaWidgetStrip(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        addView(row);
        for (int i = 0; i < IDS.length; i++) {
            Card c = new Card(context, i);
            c.setOnClickListener(v -> {
                if (onWallet != null) onWallet.run();
            });
            cards.add(c);
            row.addView(c, LayoutHelper.createLinear(158, 132, 0, 0, 8, 0));
        }
        weather = new Card(context, -1);
        weather.setOnClickListener(v -> askCity(context));
        row.addView(weather, LayoutHelper.createLinear(158, 132));
        refresh();
    }

    public void setOnWallet(Runnable r) {
        onWallet = r;
    }

    public void applyColors() {
        for (Card c : cards) c.invalidate();
        weather.invalidate();
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        if (now - quotesAt > FRESH_MS) {
            quotesAt = now;
            Utilities.globalQueue.postRunnable(() -> {
                Quote[] q = loadQuotes();
                AndroidUtilities.runOnUIThread(() -> {
                    if (q != null) quotes = q;
                    else quotesAt = 0;
                    applyColors();
                });
            });
        }
        String city = PrizmaConfig.weatherCity();
        if (!TextUtils.isEmpty(city) && (now - weatherAt > 30 * 60 * 1000L || !city.equals(weatherFor))) {
            weatherAt = now;
            Utilities.globalQueue.postRunnable(() -> {
                String text = loadWeather(city);
                AndroidUtilities.runOnUIThread(() -> {
                    if (text != null) {
                        weatherText = text;
                        weatherFor = city;
                    }
                    weather.invalidate();
                });
            });
        }
    }

    private static Quote[] loadQuotes() {
        try {
            String body = PrizmaNet.get("https://api.coingecko.com/api/v3/coins/markets?vs_currency=rub&sparkline=true&price_change_percentage=24h&ids=" + TextUtils.join(",", IDS), 10000);
            if (body == null) return null;
            JSONArray arr = new JSONArray(body);
            Quote[] out = new Quote[IDS.length];
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                int index = -1;
                for (int k = 0; k < IDS.length; k++) if (IDS[k].equals(o.optString("id"))) index = k;
                if (index < 0) continue;
                Quote q = new Quote();
                q.price = o.optDouble("current_price");
                q.change = o.optDouble("price_change_24h");
                q.percent = o.optDouble("price_change_percentage_24h");
                JSONObject spark = o.optJSONObject("sparkline_in_7d");
                JSONArray points = spark != null ? spark.optJSONArray("price") : null;
                if (points != null && points.length() > 1) {
                    int from = Math.max(0, points.length() - 30);
                    q.line = new float[points.length() - from];
                    for (int p = from; p < points.length(); p++) q.line[p - from] = (float) points.optDouble(p);
                }
                out[index] = q;
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String loadWeather(String city) {
        try {
            String geo = PrizmaNet.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + Uri.encode(city), 8000);
            JSONArray found = geo == null ? null : new JSONObject(geo).optJSONArray("results");
            if (found == null || found.length() == 0) return "не нашёл|" + city;
            JSONObject place = found.getJSONObject(0);
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code",
                    place.getDouble("latitude"), place.getDouble("longitude"));
            String body = PrizmaNet.get(url, 8000);
            JSONObject current = body == null ? null : new JSONObject(body).optJSONObject("current");
            if (current == null) return null;
            int t = (int) Math.round(current.getDouble("temperature_2m"));
            return (t > 0 ? "+" : "") + t + "°|" + place.optString("name", city) + " · " + sky(current.optInt("weather_code"));
        } catch (Throwable e) {
            return null;
        }
    }

    private static String sky(int code) {
        if (code == 0) return "ясно";
        if (code <= 3) return "облачно";
        if (code <= 48) return "туман";
        if (code <= 67 || code >= 80 && code <= 82) return "дождь";
        if (code <= 77 || code == 85 || code == 86) return "снег";
        return "гроза";
    }

    private static String money(double v) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(Math.abs(v) >= 100000 ? "#,##0" : "#,##0.00", sym);
        return f.format(v) + " ₽";
    }

    private void askCity(Context context) {
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHint("Город");
        input.setText(PrizmaConfig.weatherCity());
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        FrameLayout box = new FrameLayout(context);
        box.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 40, Gravity.TOP, 24, 6, 24, 0));
        new AlertDialog.Builder(context)
                .setTitle("Погода")
                .setMessage("Город вводится вручную, местоположение телефона не используется.")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    PrizmaConfig.setWeatherCity(input.getText().toString().trim());
                    weatherAt = 0;
                    weather.invalidate();
                    refresh();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private static final class Card extends View {
        private final int index;
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint price = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint glyph = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path area = new Path();
        private final RectF rect = new RectF();

        Card(Context context, int index) {
            super(context);
            this.index = index;
            title.setTextSize(AndroidUtilities.dp(16));
            title.setTypeface(AndroidUtilities.bold());
            price.setTextSize(AndroidUtilities.dp(19));
            price.setTypeface(AndroidUtilities.bold());
            small.setTextSize(AndroidUtilities.dp(12.5f));
            glyph.setTextSize(AndroidUtilities.dp(13));
            glyph.setTypeface(AndroidUtilities.bold());
            glyph.setColor(0xffffffff);
            glyph.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(AndroidUtilities.dpf2(2));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            back.setColor(ColorUtils.blendARGB(surface, ink, 0.07f));
            rect.set(0, 0, w, h);
            float r = AndroidUtilities.dp(22);
            canvas.drawRoundRect(rect, r, r, back);
            title.setColor(ink);
            price.setColor(ink);
            float pad = AndroidUtilities.dp(12);

            if (index < 0) {
                drawWeather(canvas, w, h, pad, ink, gray);
                return;
            }
            float cy = pad + AndroidUtilities.dp(11);
            badge.setColor(TINTS[index]);
            canvas.drawCircle(pad + AndroidUtilities.dp(11), cy, AndroidUtilities.dp(11), badge);
            canvas.drawText(GLYPHS[index], pad + AndroidUtilities.dp(11), cy - (glyph.descent() + glyph.ascent()) / 2f, glyph);
            canvas.drawText(NAMES[index], pad + AndroidUtilities.dp(28), cy - (title.descent() + title.ascent()) / 2f, title);

            Quote q = quotes != null ? quotes[index] : null;
            float priceY = AndroidUtilities.dp(58);
            if (q == null) {
                small.setColor(gray);
                canvas.drawText("Загружаю…", pad, priceY, small);
                return;
            }
            canvas.drawText(money(q.price), pad, priceY, price);
            boolean up = q.percent >= 0;
            int tone = up ? 0xff2fbf71 : 0xffe5484d;
            String pct = (up ? "↑ " : "↓ ") + String.format(Locale.ROOT, "%.2f%%", Math.abs(q.percent)).replace('.', ',');
            small.setColor(tone);
            float pw = small.measureText(pct) + AndroidUtilities.dp(12);
            rect.set(pad, AndroidUtilities.dp(66), pad + pw, AndroidUtilities.dp(86));
            fill.setShader(null);
            fill.setColor((tone & 0x00ffffff) | 0x26000000);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(7), AndroidUtilities.dp(7), fill);
            canvas.drawText(pct, pad + AndroidUtilities.dp(6), AndroidUtilities.dp(80.5f), small);
            String abs = (up ? "+" : "−") + money(Math.abs(q.change));
            if (small.measureText(abs) + pad + pw + AndroidUtilities.dp(6) < w - AndroidUtilities.dp(4)) {
                canvas.drawText(abs, pad + pw + AndroidUtilities.dp(6), AndroidUtilities.dp(80.5f), small);
            }
            if (q.line != null) drawLine(canvas, q.line, w, h, tone);
        }

        private void drawLine(Canvas canvas, float[] line, int w, int h, int tone) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : line) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (max - min < 1e-9f) max = min + 1;
            float top = AndroidUtilities.dp(94), bottom = h - AndroidUtilities.dp(4);
            path.rewind();
            area.rewind();
            float px = 0, py = 0;
            for (int i = 0; i < line.length; i++) {
                float x = w * i / (float) (line.length - 1);
                float y = bottom - (line[i] - min) / (max - min) * (bottom - top);
                if (i == 0) {
                    path.moveTo(x, y);
                    area.moveTo(x, h);
                    area.lineTo(x, y);
                } else {
                    float mx = (px + x) / 2f;
                    path.cubicTo(mx, py, mx, y, x, y);
                    area.cubicTo(mx, py, mx, y, x, y);
                }
                px = x;
                py = y;
            }
            area.lineTo(w, h);
            area.close();
            canvas.save();
            rect.set(0, 0, w, h);
            Path clip = new Path();
            clip.addRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), Path.Direction.CW);
            canvas.clipPath(clip);
            fill.setShader(new LinearGradient(0, top, 0, h, (tone & 0x00ffffff) | 0x52000000, tone & 0x00ffffff, Shader.TileMode.CLAMP));
            canvas.drawPath(area, fill);
            stroke.setColor(tone);
            canvas.drawPath(path, stroke);
            canvas.restore();
        }

        private void drawWeather(Canvas canvas, int w, int h, float pad, int ink, int gray) {
            float cy = pad + AndroidUtilities.dp(11);
            badge.setColor(0xff5a8dee);
            canvas.drawCircle(pad + AndroidUtilities.dp(11), cy, AndroidUtilities.dp(11), badge);
            canvas.drawText("☀", pad + AndroidUtilities.dp(11), cy - (glyph.descent() + glyph.ascent()) / 2f, glyph);
            canvas.drawText("Погода", pad + AndroidUtilities.dp(28), cy - (title.descent() + title.ascent()) / 2f, title);
            small.setColor(gray);
            String city = PrizmaConfig.weatherCity();
            if (TextUtils.isEmpty(city)) {
                canvas.drawText("Нажмите, чтобы", pad, AndroidUtilities.dp(62), small);
                canvas.drawText("выбрать город", pad, AndroidUtilities.dp(80), small);
                return;
            }
            String t = weatherText != null && city.equals(weatherFor) ? weatherText : "…|" + city;
            String[] parts = t.split("\\|", 2);
            price.setTextSize(AndroidUtilities.dp(30));
            canvas.drawText(parts[0], pad, AndroidUtilities.dp(74), price);
            price.setTextSize(AndroidUtilities.dp(19));
            if (parts.length > 1) {
                CharSequence line = TextUtils.ellipsize(parts[1], small, w - pad * 2, TextUtils.TruncateAt.END);
                canvas.drawText(line, 0, line.length(), pad, AndroidUtilities.dp(100), small);
            }
        }
    }
}
