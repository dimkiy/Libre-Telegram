package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.text.InputType;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.graphics.ColorUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.PrizmaNet;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;

public class PrizmaWidgetStrip extends HorizontalScrollView {
    private static final long FRESH_MS = 5 * 60 * 1000L;
    private static final long WEATHER_MS = 30 * 60 * 1000L;

    private static final String[] TYPES = {"coin", "fx", "weather", "clock", "count", "note", "link"};
    private static final String[] TYPE_NAMES = {"Криптовалюта", "Курс валюты", "Погода", "Часы в городе", "Обратный отсчёт", "Заметка", "Ссылка"};
    private static final String[] TYPE_HINTS = {"Цена монеты и график за неделю", "Например, доллар в рублях", "Температура и небо в городе", "Время в любом городе мира", "Сколько дней осталось до даты", "Любой ваш текст", "Канал, чат, человек или сайт"};
    private static final int[] PALETTE = {0xfff7931a, 0xff2aa3ef, 0xff27b46e, 0xff3b6fe0, 0xffe5484d, 0xffa15ae8, 0xffe0458f, 0xff5a8dee, 0xff8a8f98, 0xffd9a400};
    private static final String[][] POPULAR = {
            {"bitcoin", "Bitcoin", "btc"}, {"ethereum", "Ethereum", "eth"}, {"the-open-network", "Toncoin", "ton"},
            {"solana", "Solana", "sol"}, {"tether", "Tether", "usdt"}, {"monero", "Monero", "xmr"},
            {"dogecoin", "Dogecoin", "doge"}, {"notcoin", "Notcoin", "not"}, {"tron", "TRON", "trx"},
            {"litecoin", "Litecoin", "ltc"}, {"ripple", "XRP", "xrp"}, {"binancecoin", "BNB", "bnb"}};

    public static final class Widget {
        String type = "note";
        String title = "";
        String a = "";
        String b = "";
        String link = "";
        int color = PALETTE[0];
        boolean wide;

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("t", type);
                o.put("n", title);
                o.put("a", a);
                o.put("b", b);
                o.put("l", link);
                o.put("c", color);
                o.put("w", wide);
            } catch (Throwable ignore) {
            }
            return o;
        }

        static Widget of(JSONObject o) {
            Widget w = new Widget();
            w.type = o.optString("t", "note");
            w.title = o.optString("n", "");
            w.a = o.optString("a", "");
            w.b = o.optString("b", "");
            w.link = o.optString("l", "");
            w.color = o.optInt("c", PALETTE[0]);
            w.wide = o.optBoolean("w", false);
            return w;
        }

        Widget copy() {
            return of(json());
        }
    }

    private static final class Quote {
        double price, percent, change;
        float[] line;
        String name, symbol;
    }

    private static final HashMap<String, Quote> quotes = new HashMap<>();
    private static final HashMap<String, Long> quotesAt = new HashMap<>();
    private static final HashMap<String, double[]> rates = new HashMap<>();
    private static final HashMap<String, String[]> rateCodes = new HashMap<>();
    private static final HashMap<String, Long> ratesAt = new HashMap<>();
    private static final HashMap<String, String> weatherText = new HashMap<>();
    private static final HashMap<String, Long> weatherAt = new HashMap<>();

    private final LinearLayout row;
    private final ArrayList<Widget> widgets = new ArrayList<>();
    private Runnable onWallet;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            applyColors();
            postDelayed(this, 30_000);
        }
    };

    public PrizmaWidgetStrip(Context context) {
        super(context);
        setHorizontalScrollBarEnabled(false);
        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(6), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        addView(row);
        load();
        rebuild();
        refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(tick);
        postDelayed(tick, 30_000);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(tick);
    }

    public void setOnWallet(Runnable r) {
        onWallet = r;
    }

    public void applyColors() {
        for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).invalidate();
    }

    private void load() {
        widgets.clear();
        String raw = PrizmaConfig.widgets();
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) widgets.add(Widget.of(arr.getJSONObject(i)));
                return;
            } catch (Throwable ignore) {
            }
        }
        widgets.addAll(defaults());
    }

    private static ArrayList<Widget> defaults() {
        ArrayList<Widget> out = new ArrayList<>();
        String[] coins = {"bitcoin", "the-open-network", "monero"};
        int[] tints = {0xfff7931a, 0xff2aa3ef, 0xffff6a13};
        for (int i = 0; i < coins.length; i++) {
            Widget w = new Widget();
            w.type = "coin";
            w.a = coins[i];
            w.b = "rub";
            w.color = tints[i];
            out.add(w);
        }
        Widget usd = new Widget();
        usd.type = "fx";
        usd.a = "USD";
        usd.b = "RUB";
        usd.title = "Доллар";
        usd.color = 0xff27b46e;
        out.add(usd);
        Widget eur = new Widget();
        eur.type = "fx";
        eur.a = "EUR";
        eur.b = "RUB";
        eur.title = "Евро";
        eur.color = 0xff3b6fe0;
        out.add(eur);
        Widget weather = new Widget();
        weather.type = "weather";
        weather.a = PrizmaConfig.weatherCity();
        weather.color = 0xff5a8dee;
        out.add(weather);
        return out;
    }

    private void save() {
        JSONArray arr = new JSONArray();
        for (Widget w : widgets) arr.put(w.json());
        PrizmaConfig.setWidgets(arr.toString());
    }

    private void rebuild() {
        row.removeAllViews();
        for (int i = 0; i < widgets.size(); i++) {
            final Widget w = widgets.get(i);
            final int index = i;
            Card c = new Card(getContext(), w);
            c.setOnClickListener(v -> open(w));
            c.setOnLongClickListener(v -> {
                manage(index);
                return true;
            });
            PrizmaMotion.pressable(c);
            row.addView(c, LayoutHelper.createLinear(w.wide ? 324 : 158, 132, 0, 0, 8, 0));
        }
        AddCard add = new AddCard(getContext());
        add.setOnClickListener(v -> chooseType());
        PrizmaMotion.pressable(add);
        row.addView(add, LayoutHelper.createLinear(widgets.isEmpty() ? 158 : 96, 132));
    }

    private void changed() {
        save();
        rebuild();
        refresh();
    }

    private void open(Widget w) {
        String link = w.type.equals("link") ? w.a : w.link;
        if (!TextUtils.isEmpty(link)) {
            openLink(getContext(), link);
            return;
        }
        if (w.type.equals("coin") && onWallet != null) {
            onWallet.run();
            return;
        }
        edit(widgets.indexOf(w));
    }

    private static void openLink(Context context, String raw) {
        String s = raw.trim();
        if (s.startsWith("@")) s = "https://t.me/" + s.substring(1);
        else if (!s.contains("://")) s = (s.startsWith("t.me/") ? "https://" : (s.contains(".") ? "https://" : "https://t.me/")) + s;
        try {
            Browser.openUrl(context, s);
        } catch (Throwable e) {
            Toast.makeText(context, "Не получилось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void manage(int index) {
        if (index < 0 || index >= widgets.size()) return;
        Context context = getContext();
        ArrayList<CharSequence> items = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        items.add("Изменить");
        actions.add(() -> edit(index));
        if (index > 0) {
            items.add("Сдвинуть влево");
            actions.add(() -> {
                widgets.add(index - 1, widgets.remove(index));
                changed();
            });
        }
        if (index < widgets.size() - 1) {
            items.add("Сдвинуть вправо");
            actions.add(() -> {
                widgets.add(index + 1, widgets.remove(index));
                changed();
            });
        }
        items.add(widgets.get(index).wide ? "Сделать узким" : "Сделать широким");
        actions.add(() -> {
            widgets.get(index).wide = !widgets.get(index).wide;
            changed();
        });
        items.add("Копия");
        actions.add(() -> {
            widgets.add(index + 1, widgets.get(index).copy());
            changed();
        });
        items.add("Удалить");
        actions.add(() -> {
            widgets.remove(index);
            changed();
        });
        new AlertDialog.Builder(context)
                .setTitle(title(widgets.get(index)))
                .setItems(items.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void chooseType() {
        Context context = getContext();
        CharSequence[] items = new CharSequence[TYPES.length + 1];
        for (int i = 0; i < TYPES.length; i++) items[i] = TYPE_NAMES[i] + " — " + TYPE_HINTS[i];
        items[TYPES.length] = "Вернуть стандартные виджеты";
        new AlertDialog.Builder(context)
                .setTitle("Новый виджет")
                .setItems(items, (d, which) -> {
                    if (which == TYPES.length) {
                        widgets.clear();
                        widgets.addAll(defaults());
                        changed();
                        return;
                    }
                    Widget w = new Widget();
                    w.type = TYPES[which];
                    w.color = PALETTE[widgets.size() % PALETTE.length];
                    if (w.type.equals("fx")) {
                        w.a = "USD";
                        w.b = "RUB";
                    } else if (w.type.equals("coin")) {
                        w.b = "rub";
                    } else if (w.type.equals("note")) {
                        w.wide = true;
                    }
                    editor(w, -1);
                })
                .show();
    }

    private void edit(int index) {
        if (index < 0 || index >= widgets.size()) return;
        editor(widgets.get(index).copy(), index);
    }

    private EditTextBoldCursor field(Context context, String hint, String value, boolean multi) {
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        input.setHint(hint);
        input.setText(value);
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField), Theme.getColor(Theme.key_dialogInputFieldActivated), Theme.getColor(Theme.key_text_RedBold));
        input.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        if (multi) {
            input.setSingleLine(false);
            input.setMaxLines(6);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        } else {
            input.setSingleLine(true);
        }
        return input;
    }

    private TextView label(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        return t;
    }

    private void editor(Widget w, int index) {
        Context context = getContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(4), AndroidUtilities.dp(24), AndroidUtilities.dp(4));

        EditTextBoldCursor main = null, second = null;
        switch (w.type) {
            case "coin": {
                box.addView(label(context, "Монета: название или тикер, например bitcoin, TON, eth"));
                main = field(context, "Монета", w.a, false);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                box.addView(label(context, "Валюта цены: rub, usd, eur…"));
                second = field(context, "rub", w.b, false);
                box.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
            case "fx": {
                box.addView(label(context, "Из какой валюты (код: USD, EUR, CNY, KZT…)"));
                main = field(context, "USD", w.a, false);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                box.addView(label(context, "В какую валюту"));
                second = field(context, "RUB", w.b, false);
                box.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
            case "weather":
            case "clock": {
                box.addView(label(context, "Город. Местоположение телефона не используется"));
                main = field(context, "Город", w.a, false);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
            case "count": {
                box.addView(label(context, "Дата в виде дд.мм.гггг"));
                main = field(context, "31.12.2026", w.a, false);
                main.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
            case "note": {
                box.addView(label(context, "Текст заметки"));
                main = field(context, "Что угодно", w.a, true);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
            case "link": {
                box.addView(label(context, "Адрес: @username, t.me/… или сайт"));
                main = field(context, "@durov", w.a, false);
                box.addView(main, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                box.addView(label(context, "Подпись под названием"));
                second = field(context, "Необязательно", w.b, false);
                box.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
                break;
            }
        }

        box.addView(label(context, "Название на виджете"));
        EditTextBoldCursor name = field(context, "Как сейчас", w.title, false);
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        EditTextBoldCursor link = null;
        if (!w.type.equals("link")) {
            box.addView(label(context, "Что открыть по нажатию: @username, t.me/… или сайт"));
            link = field(context, "Необязательно", w.link, false);
            box.addView(link, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));
        }

        box.addView(label(context, "Цвет"));
        final int[] color = {w.color};
        LinearLayout colors = new LinearLayout(context);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        final ArrayList<Swatch> swatches = new ArrayList<>();
        for (int c : PALETTE) {
            Swatch s = new Swatch(context, c);
            s.setSelected(c == color[0]);
            s.setOnClickListener(v -> {
                color[0] = c;
                for (Swatch o : swatches) o.setSelected(o.color == c);
                PrizmaMotion.pop(v);
            });
            swatches.add(s);
            colors.addView(s, LayoutHelper.createLinear(30, 30, 0, 0, 6, 0));
        }
        HorizontalScrollView colorScroll = new HorizontalScrollView(context);
        colorScroll.setHorizontalScrollBarEnabled(false);
        colorScroll.addView(colors);
        box.addView(colorScroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 6, 0, 4));

        TextCheckCell wide = new TextCheckCell(context);
        wide.setTextAndCheck("Широкий виджет", w.wide, false);
        wide.setOnClickListener(v -> wide.setChecked(!wide.isChecked()));
        box.addView(wide, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50, -21, 0, -21, 0));

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);

        final EditTextBoldCursor fMain = main, fSecond = second, fLink = link;
        AlertDialog.Builder b = new AlertDialog.Builder(context)
                .setTitle(index < 0 ? TYPE_NAMES[indexOf(w.type)] : "Виджет «" + title(w) + "»")
                .setView(scroll)
                .setPositiveButton(index < 0 ? "Добавить" : "Сохранить", (d, which) -> {
                    w.title = name.getText().toString().trim();
                    w.color = color[0];
                    w.wide = wide.isChecked();
                    if (fMain != null) w.a = fMain.getText().toString().trim();
                    if (fSecond != null) w.b = fSecond.getText().toString().trim();
                    if (fLink != null) w.link = fLink.getText().toString().trim();
                    commit(w, index);
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        if (index >= 0) {
            b.setNeutralButton("Удалить", (d, which) -> {
                if (index < widgets.size()) {
                    widgets.remove(index);
                    changed();
                }
            });
        }
        b.show();
    }

    private static int indexOf(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return i;
        return 0;
    }

    private void commit(Widget w, int index) {
        switch (w.type) {
            case "fx":
                w.a = TextUtils.isEmpty(w.a) ? "USD" : w.a.toUpperCase(Locale.ROOT);
                w.b = TextUtils.isEmpty(w.b) ? "RUB" : w.b.toUpperCase(Locale.ROOT);
                break;
            case "coin":
                w.b = TextUtils.isEmpty(w.b) ? "rub" : w.b.toLowerCase(Locale.ROOT);
                String found = popular(w.a);
                if (found != null) {
                    w.a = found;
                } else if (!TextUtils.isEmpty(w.a)) {
                    final String query = w.a;
                    org.telegram.messenger.PrizmaJobs.net(() -> {
                        String id = searchCoin(query);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (id == null) {
                                Toast.makeText(getContext(), "Монета «" + query + "» не найдена", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            w.a = id;
                            place(w, index);
                        });
                    });
                    return;
                }
                break;
            case "clock":
                if (!TextUtils.isEmpty(w.a)) {
                    final String city = w.a;
                    org.telegram.messenger.PrizmaJobs.net(() -> {
                        String[] zone = cityZone(city);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (zone == null) {
                                Toast.makeText(getContext(), "Город «" + city + "» не найден", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            w.b = zone[0];
                            if (TextUtils.isEmpty(w.title)) w.title = zone[1];
                            place(w, index);
                        });
                    });
                    return;
                }
                break;
            case "count":
                if (parseDate(w.a) == null) {
                    Toast.makeText(getContext(), "Дата должна быть в виде дд.мм.гггг", Toast.LENGTH_SHORT).show();
                    return;
                }
                break;
        }
        place(w, index);
    }

    private void place(Widget w, int index) {
        if (index >= 0 && index < widgets.size()) widgets.set(index, w);
        else widgets.add(w);
        if (w.type.equals("weather") && !TextUtils.isEmpty(w.a)) weatherAt.remove(w.a.toLowerCase(Locale.ROOT));
        changed();
        if (index < 0) post(() -> fullScroll(FOCUS_RIGHT));
    }

    private static String popular(String q) {
        if (TextUtils.isEmpty(q)) return null;
        String s = q.trim().toLowerCase(Locale.ROOT);
        for (String[] p : POPULAR) {
            if (p[0].equals(s) || p[1].toLowerCase(Locale.ROOT).equals(s) || p[2].equals(s)) return p[0];
        }
        if (s.equals("gram") || s.equals("тон") || s.equals("toncoin")) return "the-open-network";
        if (s.equals("биткоин") || s.equals("биткойн")) return "bitcoin";
        return null;
    }

    private static String searchCoin(String q) {
        try {
            String body = PrizmaNet.get("https://api.coingecko.com/api/v3/search?query=" + Uri.encode(q.trim()), 10000);
            if (body == null) return null;
            JSONArray coins = new JSONObject(body).optJSONArray("coins");
            if (coins == null || coins.length() == 0) return null;
            String s = q.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.getJSONObject(i);
                if (s.equals(c.optString("symbol").toLowerCase(Locale.ROOT)) || s.equals(c.optString("name").toLowerCase(Locale.ROOT))) {
                    return c.optString("id");
                }
            }
            return coins.getJSONObject(0).optString("id", null);
        } catch (Throwable e) {
            return null;
        }
    }

    private static String[] cityZone(String city) {
        try {
            String geo = PrizmaNet.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + Uri.encode(city), 8000);
            JSONArray found = geo == null ? null : new JSONObject(geo).optJSONArray("results");
            if (found == null || found.length() == 0) return null;
            JSONObject place = found.getJSONObject(0);
            String zone = place.optString("timezone", "");
            if (TextUtils.isEmpty(zone)) return null;
            return new String[]{zone, place.optString("name", city)};
        } catch (Throwable e) {
            return null;
        }
    }

    private static Date parseDate(String s) {
        if (TextUtils.isEmpty(s)) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
            f.setLenient(false);
            return f.parse(s.trim());
        } catch (Throwable e) {
            return null;
        }
    }

    public void refresh() {
        long now = System.currentTimeMillis();
        HashMap<String, ArrayList<String>> coinsByCurrency = new HashMap<>();
        ArrayList<String> bases = new ArrayList<>();
        ArrayList<String> cities = new ArrayList<>();
        for (Widget w : widgets) {
            if (w.type.equals("coin") && !TextUtils.isEmpty(w.a)) {
                String key = w.a + "|" + w.b;
                Long at = quotesAt.get(key);
                if (at != null && now - at < FRESH_MS) continue;
                ArrayList<String> list = coinsByCurrency.get(w.b);
                if (list == null) coinsByCurrency.put(w.b, list = new ArrayList<>());
                if (!list.contains(w.a)) list.add(w.a);
                quotesAt.put(key, now);
            } else if (w.type.equals("fx") && !TextUtils.isEmpty(w.a)) {
                Long at = ratesAt.get(w.a);
                if ((at == null || now - at > FRESH_MS * 6) && !bases.contains(w.a)) {
                    bases.add(w.a);
                    ratesAt.put(w.a, now);
                }
            } else if (w.type.equals("weather") && !TextUtils.isEmpty(w.a)) {
                String key = w.a.toLowerCase(Locale.ROOT);
                Long at = weatherAt.get(key);
                if ((at == null || now - at > WEATHER_MS) && !cities.contains(w.a)) {
                    cities.add(w.a);
                    weatherAt.put(key, now);
                }
            }
        }
        for (String currency : coinsByCurrency.keySet()) {
            final String cur = currency;
            final ArrayList<String> ids = coinsByCurrency.get(currency);
            org.telegram.messenger.PrizmaJobs.net(() -> {
                HashMap<String, Quote> got = loadQuotes(ids, cur);
                AndroidUtilities.runOnUIThread(() -> {
                    if (got == null) {
                        for (String id : ids) quotesAt.remove(id + "|" + cur);
                    } else {
                        quotes.putAll(got);
                    }
                    applyColors();
                });
            });
        }
        for (String base : bases) {
            org.telegram.messenger.PrizmaJobs.net(() -> {
                boolean ok = loadRates(base);
                AndroidUtilities.runOnUIThread(() -> {
                    if (!ok) ratesAt.remove(base);
                    applyColors();
                });
            });
        }
        for (String city : cities) {
            org.telegram.messenger.PrizmaJobs.net(() -> {
                String text = loadWeather(city);
                AndroidUtilities.runOnUIThread(() -> {
                    String key = city.toLowerCase(Locale.ROOT);
                    if (text != null) weatherText.put(key, text);
                    else weatherAt.put(key, System.currentTimeMillis() - WEATHER_MS + 60_000);
                    applyColors();
                });
            });
        }
    }

    private static HashMap<String, Quote> loadQuotes(ArrayList<String> ids, String currency) {
        try {
            String body = PrizmaNet.get("https://api.coingecko.com/api/v3/coins/markets?sparkline=true&price_change_percentage=24h&vs_currency=" + Uri.encode(currency) + "&ids=" + Uri.encode(TextUtils.join(",", ids), ","), 10000);
            if (body == null) return null;
            JSONArray arr = new JSONArray(body);
            HashMap<String, Quote> out = new HashMap<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Quote q = new Quote();
                q.price = o.optDouble("current_price");
                if (Double.isNaN(q.price)) continue;
                q.change = o.optDouble("price_change_24h", 0);
                q.percent = o.optDouble("price_change_percentage_24h", 0);
                q.name = o.optString("name", "");
                q.symbol = o.optString("symbol", "");
                JSONObject spark = o.optJSONObject("sparkline_in_7d");
                JSONArray points = spark != null ? spark.optJSONArray("price") : null;
                if (points != null && points.length() > 1) {
                    int from = Math.max(0, points.length() - 30);
                    q.line = new float[points.length() - from];
                    for (int p = from; p < points.length(); p++) q.line[p - from] = (float) points.optDouble(p);
                }
                out.put(o.optString("id") + "|" + currency, q);
            }
            return out;
        } catch (Throwable e) {
            return null;
        }
    }

    private static boolean loadRates(String base) {
        try {
            String body = PrizmaNet.get("https://open.er-api.com/v6/latest/" + Uri.encode(base), 10000);
            if (body == null) return false;
            JSONObject r = new JSONObject(body).optJSONObject("rates");
            if (r == null) return false;
            JSONArray names = r.names();
            if (names == null) return false;
            String[] codes = new String[names.length()];
            double[] values = new double[names.length()];
            for (int i = 0; i < names.length(); i++) {
                codes[i] = names.getString(i);
                values[i] = r.optDouble(codes[i], Double.NaN);
            }
            rates.put(base, values);
            rateCodes.put(base, codes);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static double rate(String base, String quote) {
        String[] codes = rateCodes.get(base);
        double[] values = rates.get(base);
        if (codes == null || values == null) return Double.NaN;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(quote)) return values[i];
        return Double.NaN;
    }

    private static String loadWeather(String city) {
        try {
            String geo = PrizmaNet.get("https://geocoding-api.open-meteo.com/v1/search?count=1&language=ru&name=" + Uri.encode(city), 8000);
            JSONArray found = geo == null ? null : new JSONObject(geo).optJSONArray("results");
            if (found == null || found.length() == 0) return "?|не нашёл город";
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

    private static String symbolOf(String currency) {
        switch (currency.toLowerCase(Locale.ROOT)) {
            case "rub": return " ₽";
            case "usd": return " $";
            case "eur": return " €";
            case "kzt": return " ₸";
            case "uah": return " ₴";
            case "byn": return " Br";
            case "cny": return " ¥";
            case "gbp": return " £";
            case "try": return " ₺";
            default: return " " + currency.toUpperCase(Locale.ROOT);
        }
    }

    private static String money(double v, String currency) {
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        String pattern = Math.abs(v) >= 100000 ? "#,##0" : Math.abs(v) >= 1 ? "#,##0.00" : "0.0000";
        return new DecimalFormat(pattern, sym).format(v) + symbolOf(currency);
    }

    private static String title(Widget w) {
        if (!TextUtils.isEmpty(w.title)) return w.title;
        switch (w.type) {
            case "coin": {
                Quote q = quotes.get(w.a + "|" + w.b);
                if (q != null && !TextUtils.isEmpty(q.name)) return q.name;
                for (String[] p : POPULAR) if (p[0].equals(w.a)) return p[1];
                return TextUtils.isEmpty(w.a) ? "Монета" : w.a;
            }
            case "fx": return w.a + " → " + w.b;
            case "weather": return "Погода";
            case "clock": return TextUtils.isEmpty(w.a) ? "Часы" : w.a;
            case "count": return "Отсчёт";
            case "note": return "Заметка";
            case "link": return TextUtils.isEmpty(w.a) ? "Ссылка" : w.a;
        }
        return "Виджет";
    }

    private static String glyph(Widget w) {
        switch (w.type) {
            case "coin": {
                Quote q = quotes.get(w.a + "|" + w.b);
                String s = q != null && !TextUtils.isEmpty(q.symbol) ? q.symbol : null;
                if (s == null) for (String[] p : POPULAR) if (p[0].equals(w.a)) s = p[2];
                if (w.a.equals("bitcoin")) return "₿";
                return s == null ? "◆" : s.substring(0, 1).toUpperCase(Locale.ROOT);
            }
            case "fx": return symbolOf(w.a).trim().substring(0, 1);
            case "weather": return "☀";
            case "clock": return "◷";
            case "count": return "⧗";
            case "note": return "✎";
            case "link": return "↗";
        }
        return "•";
    }

    private static final class Swatch extends View {
        final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        Swatch(Context context, int color) {
            super(context);
            this.color = color;
        }

        @Override
        public void setSelected(boolean selected) {
            on = selected;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            canvas.drawCircle(cx, cy, on ? r - AndroidUtilities.dp(5) : r - AndroidUtilities.dp(2), paint);
            if (on) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(AndroidUtilities.dp(2));
                canvas.drawCircle(cx, cy, r - AndroidUtilities.dp(1), paint);
            }
        }
    }

    private static final class AddCard extends View {
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        AddCard(Context context) {
            super(context);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeWidth(AndroidUtilities.dp(2));
            text.setTextSize(AndroidUtilities.dp(12.5f));
            text.setTextAlign(Paint.Align.CENTER);
            setContentDescription("Добавить виджет");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            back.setColor(ColorUtils.blendARGB(surface, ink, 0.04f));
            rect.set(0, 0, w, h);
            canvas.drawRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), back);
            float cx = w / 2f, cy = h / 2f - AndroidUtilities.dp(8), arm = AndroidUtilities.dp(9);
            line.setColor(gray);
            canvas.drawLine(cx - arm, cy, cx + arm, cy, line);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, line);
            text.setColor(gray);
            canvas.drawText("Добавить", cx, cy + AndroidUtilities.dp(30), text);
        }
    }

    private static final class Card extends View {
        private final Widget widget;
        private final Paint back = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint price = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint glyph = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path area = new Path();
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private Shader areaShader;
        private int areaShaderTone, areaShaderHeight, clipWidth, clipHeight;
        private StaticLayout noteLayout;
        private String noteText;
        private int noteWidth;

        Card(Context context, Widget widget) {
            super(context);
            this.widget = widget;
            title.setTextSize(AndroidUtilities.dp(16));
            title.setTypeface(AndroidUtilities.bold());
            price.setTextSize(AndroidUtilities.dp(19));
            price.setTypeface(AndroidUtilities.bold());
            small.setTextSize(AndroidUtilities.dp(12.5f));
            body.setTextSize(AndroidUtilities.dp(14));
            glyph.setTextSize(AndroidUtilities.dp(13));
            glyph.setTypeface(AndroidUtilities.bold());
            glyph.setColor(0xffffffff);
            glyph.setTextAlign(Paint.Align.CENTER);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(AndroidUtilities.dpf2(2));
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            setContentDescription(PrizmaWidgetStrip.title(widget));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
            int ink = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
            back.setColor(ColorUtils.blendARGB(ColorUtils.blendARGB(surface, ink, 0.07f), widget.color, 0.06f));
            rect.set(0, 0, w, h);
            float r = AndroidUtilities.dp(22);
            canvas.drawRoundRect(rect, r, r, back);
            title.setColor(ink);
            price.setColor(ink);
            body.setColor(ink);
            float pad = AndroidUtilities.dp(12);

            float cy = pad + AndroidUtilities.dp(11);
            badge.setColor(widget.color);
            canvas.drawCircle(pad + AndroidUtilities.dp(11), cy, AndroidUtilities.dp(11), badge);
            canvas.drawText(PrizmaWidgetStrip.glyph(widget), pad + AndroidUtilities.dp(11), cy - (glyph.descent() + glyph.ascent()) / 2f, glyph);
            CharSequence head = TextUtils.ellipsize(PrizmaWidgetStrip.title(widget), title, w - pad * 2 - AndroidUtilities.dp(28), TextUtils.TruncateAt.END);
            canvas.drawText(head, 0, head.length(), pad + AndroidUtilities.dp(28), cy - (title.descent() + title.ascent()) / 2f, title);

            small.setColor(gray);
            switch (widget.type) {
                case "coin": drawCoin(canvas, w, h, pad, gray); break;
                case "fx": drawFx(canvas, w, pad); break;
                case "weather": drawWeather(canvas, w, pad); break;
                case "clock": drawClock(canvas, w, pad); break;
                case "count": drawCount(canvas, w, pad); break;
                case "note": drawNote(canvas, w, h, pad); break;
                case "link": drawLink(canvas, w, pad); break;
            }
        }

        private void two(Canvas canvas, String a, String b, int w, float pad, float big) {
            price.setTextSize(AndroidUtilities.dp(big));
            CharSequence first = TextUtils.ellipsize(a, price, w - pad * 2, TextUtils.TruncateAt.END);
            canvas.drawText(first, 0, first.length(), pad, AndroidUtilities.dp(big > 24 ? 76 : 62), price);
            price.setTextSize(AndroidUtilities.dp(19));
            if (!TextUtils.isEmpty(b)) {
                CharSequence line = TextUtils.ellipsize(b, small, w - pad * 2, TextUtils.TruncateAt.END);
                canvas.drawText(line, 0, line.length(), pad, AndroidUtilities.dp(100), small);
            }
        }

        private void drawCoin(Canvas canvas, int w, int h, float pad, int gray) {
            Quote q = TextUtils.isEmpty(widget.a) ? null : quotes.get(widget.a + "|" + widget.b);
            float priceY = AndroidUtilities.dp(58);
            if (q == null) {
                canvas.drawText(TextUtils.isEmpty(widget.a) ? "Выберите монету" : PrizmaNet.proxyBlocks() ? "Нет связи через прокси" : "Загружаю…", pad, priceY, small);
                return;
            }
            canvas.drawText(money(q.price, widget.b), pad, priceY, price);
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
            String abs = (up ? "+" : "−") + money(Math.abs(q.change), widget.b);
            if (small.measureText(abs) + pad + pw + AndroidUtilities.dp(6) < w - AndroidUtilities.dp(4)) {
                canvas.drawText(abs, pad + pw + AndroidUtilities.dp(6), AndroidUtilities.dp(80.5f), small);
            }
            small.setColor(gray);
            if (q.line != null) drawLine(canvas, q.line, w, h, tone);
        }

        private void drawFx(Canvas canvas, int w, float pad) {
            double v = rate(widget.a, widget.b);
            if (Double.isNaN(v)) {
                two(canvas, "…", rateCodes.containsKey(widget.a) ? "Нет курса " + widget.b : PrizmaNet.proxyBlocks() ? "Нет связи через прокси" : "Загружаю…", w, pad, 19);
                return;
            }
            two(canvas, money(v, widget.b), "1 " + widget.a + " · обновляется раз в день", w, pad, 22);
        }

        private void drawWeather(Canvas canvas, int w, float pad) {
            if (TextUtils.isEmpty(widget.a)) {
                two(canvas, "Город?", "Нажмите, чтобы выбрать", w, pad, 22);
                return;
            }
            String t = weatherText.get(widget.a.toLowerCase(Locale.ROOT));
            String[] parts = (t == null ? "…|" + widget.a : t).split("\\|", 2);
            two(canvas, parts[0], parts.length > 1 ? parts[1] : "", w, pad, 30);
        }

        private void drawClock(Canvas canvas, int w, float pad) {
            if (TextUtils.isEmpty(widget.b)) {
                two(canvas, "--:--", "Нажмите, чтобы выбрать город", w, pad, 30);
                return;
            }
            TimeZone zone = TimeZone.getTimeZone(widget.b);
            SimpleDateFormat time = new SimpleDateFormat("HH:mm", Locale.ROOT);
            time.setTimeZone(zone);
            SimpleDateFormat day = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));
            day.setTimeZone(zone);
            Date now = new Date();
            int diff = (zone.getOffset(now.getTime()) - TimeZone.getDefault().getOffset(now.getTime())) / 3_600_000;
            String shift = diff == 0 ? "как у вас" : (diff > 0 ? "+" : "−") + Math.abs(diff) + " ч от вас";
            two(canvas, time.format(now), day.format(now) + " · " + shift, w, pad, 30);
        }

        private void drawCount(Canvas canvas, int w, float pad) {
            Date target = parseDate(widget.a);
            if (target == null) {
                two(canvas, "?", "Укажите дату", w, pad, 30);
                return;
            }
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);
            long days = Math.round((target.getTime() - today.getTimeInMillis()) / 86_400_000d);
            String big;
            String sub;
            if (days > 0) {
                big = String.valueOf(days);
                sub = LocaleController.formatPluralString("Days", (int) days).replaceAll("^\\d+\\s*", "") + " до " + widget.a;
            } else if (days == 0) {
                big = "Сегодня";
                sub = widget.a;
            } else {
                big = String.valueOf(-days);
                sub = "дн. прошло с " + widget.a;
            }
            two(canvas, big, sub, w, pad, 30);
        }

        private void drawNote(Canvas canvas, int w, int h, float pad) {
            String text = TextUtils.isEmpty(widget.a) ? "Пустая заметка" : widget.a;
            int width = (int) (w - pad * 2);
            if (width <= 0) return;
            if (noteLayout == null || !text.equals(noteText) || noteWidth != width) {
                noteText = text;
                noteWidth = width;
                noteLayout = StaticLayout.Builder.obtain(text, 0, text.length(), body, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, 1.05f)
                        .setMaxLines(Math.max(1, (int) ((h - AndroidUtilities.dp(52)) / (body.getFontSpacing() * 1.05f))))
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build();
            }
            canvas.save();
            canvas.translate(pad, AndroidUtilities.dp(44));
            body.setColor(TextUtils.isEmpty(widget.a) ? small.getColor() : title.getColor());
            noteLayout.draw(canvas);
            canvas.restore();
        }

        private void drawLink(Canvas canvas, int w, float pad) {
            String addr = widget.a;
            if (addr.startsWith("https://")) addr = addr.substring(8);
            two(canvas, TextUtils.isEmpty(widget.b) ? "Открыть" : widget.b, TextUtils.isEmpty(addr) ? "Укажите адрес" : addr, w, pad, 20);
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
            if (clipWidth != w || clipHeight != h) {
                clipWidth = w;
                clipHeight = h;
                clip.rewind();
                clip.addRoundRect(rect, AndroidUtilities.dp(22), AndroidUtilities.dp(22), Path.Direction.CW);
            }
            canvas.clipPath(clip);
            if (areaShader == null || areaShaderTone != tone || areaShaderHeight != h) {
                areaShaderTone = tone;
                areaShaderHeight = h;
                areaShader = new LinearGradient(0, top, 0, h, (tone & 0x00ffffff) | 0x52000000, tone & 0x00ffffff, Shader.TileMode.CLAMP);
            }
            fill.setShader(areaShader);
            canvas.drawPath(area, fill);
            fill.setShader(null);
            stroke.setColor(tone);
            canvas.drawPath(path, stroke);
            canvas.restore();
        }
    }
}
