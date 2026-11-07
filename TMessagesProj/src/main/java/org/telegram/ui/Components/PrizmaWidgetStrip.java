package org.telegram.ui.Components;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaNet;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

public class PrizmaWidgetStrip extends HorizontalScrollView {
    private static final long FRESH_MS = 30 * 60 * 1000L;

    private static String ratesText;
    private static String weatherText;
    private static String weatherFor;
    private static long ratesAt, weatherAt;

    private final TextView rates, weather;

    public PrizmaWidgetStrip(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(6));
        addView(row);
        rates = chip(context);
        weather = chip(context);
        row.addView(rates, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 8, 0));
        row.addView(weather, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32));
        weather.setOnClickListener(v -> askCity(context));
        rates.setOnClickListener(v -> {
            ratesAt = 0;
            refresh();
        });
        render();
        refresh();
    }

    private TextView chip(Context context) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        t.setSingleLine(true);
        return t;
    }

    public void applyColors() {
        int back = Theme.getColor(Theme.key_windowBackgroundGray);
        int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        for (TextView t : new TextView[]{rates, weather}) {
            t.setTextColor(ink);
            t.setBackground(PrizmaShape.fill(back, AndroidUtilities.dp(14)));
        }
    }

    private void render() {
        applyColors();
        rates.setText(ratesText != null ? ratesText : "Курсы…");
        String city = PrizmaConfig.weatherCity();
        if (TextUtils.isEmpty(city)) {
            weather.setText("Погода: выбрать город");
        } else {
            weather.setText(weatherText != null && city.equals(weatherFor) ? weatherText : city + " …");
        }
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        if (now - ratesAt > FRESH_MS) {
            ratesAt = now;
            Utilities.globalQueue.postRunnable(() -> {
                String text = loadRates();
                AndroidUtilities.runOnUIThread(() -> {
                    if (text != null) ratesText = text;
                    render();
                });
            });
        }
        String city = PrizmaConfig.weatherCity();
        if (!TextUtils.isEmpty(city) && (now - weatherAt > FRESH_MS || !city.equals(weatherFor))) {
            weatherAt = now;
            Utilities.globalQueue.postRunnable(() -> {
                String text = loadWeather(city);
                AndroidUtilities.runOnUIThread(() -> {
                    if (text != null) {
                        weatherText = text;
                        weatherFor = city;
                    }
                    render();
                });
            });
        }
    }

    private static String loadRates() {
        try {
            String body = PrizmaCloud.enabled() ? PrizmaNet.get(PrizmaCloud.host() + "/v1/rates", 8000) : null;
            if (body == null) body = PrizmaNet.get("https://open.er-api.com/v6/latest/USD", 8000);
            JSONObject table = body == null ? null : new JSONObject(body).optJSONObject("rates");
            if (table == null) return null;
            double rub = table.optDouble("RUB", 0), eur = table.optDouble("EUR", 0);
            if (rub <= 0) return null;
            String usd = String.format(Locale.ROOT, "$ %.2f", rub).replace('.', ',');
            String euro = eur > 0 ? String.format(Locale.ROOT, "€ %.2f", rub / eur).replace('.', ',') : null;
            return euro != null ? usd + " · " + euro : usd;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String loadWeather(String city) {
        try {
            String geo = PrizmaNet.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + Uri.encode(city), 8000);
            JSONArray found = geo == null ? null : new JSONObject(geo).optJSONArray("results");
            if (found == null || found.length() == 0) return city + ": не нашёл";
            JSONObject place = found.getJSONObject(0);
            String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code",
                    place.getDouble("latitude"), place.getDouble("longitude"));
            String body = PrizmaNet.get(url, 8000);
            JSONObject current = body == null ? null : new JSONObject(body).optJSONObject("current");
            if (current == null) return null;
            int t = (int) Math.round(current.getDouble("temperature_2m"));
            return place.optString("name", city) + " " + (t > 0 ? "+" : "") + t + "° " + sky(current.optInt("weather_code"));
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
                    render();
                    refresh();
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }
}
