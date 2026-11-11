package org.telegram.messenger;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrizmaInputCalc {
    public static final class Result {
        public final String label;
        public final String value;

        Result(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final Pattern AMOUNT = Pattern.compile("^([\\d\\s.,]*\\d)\\s*([^\\d\\s][^\\s]*)\\s*(?:в|во|to|in|на|=)\\s+([^\\s]+)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BARE = Pattern.compile("^([\\d\\s.,]*\\d)\\s*([^\\d\\s]+)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_IN = Pattern.compile("^(?:сколько\\s+)?(?:время|времени|time)\\s+(?:в|во|in)\\s+(.+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TIME_CONVERT = Pattern.compile("^(\\d{1,2})(?::(\\d{2}))?\\s+(\\S+)\\s+(?:в|во|to|in)\\s+(\\S+)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final HashMap<String, String> CURRENCY = new HashMap<>();
    private static final HashMap<String, double[]> UNITS = new HashMap<>();
    private static final HashMap<String, String> UNIT_LABEL = new HashMap<>();
    private static final HashMap<String, String> ZONES = new HashMap<>();
    private static final HashMap<String, Double> rates = new HashMap<>();
    private static long ratesAt;
    private static boolean ratesLoading;

    static {
        cur("USD", "usd", "$", "доллар", "доллара", "долларов", "доллары", "долларах", "бакс", "баксов");
        cur("EUR", "eur", "€", "евро");
        cur("RUB", "rub", "₽", "руб", "рубль", "рубля", "рублей", "рубли", "рублях", "р");
        cur("UAH", "uah", "₴", "грн", "гривна", "гривны", "гривен", "гривнах");
        cur("KZT", "kzt", "₸", "тенге");
        cur("BYN", "byn", "бел", "белруб");
        cur("GBP", "gbp", "£", "фунт", "фунтов", "фунта", "фунтах");
        cur("CNY", "cny", "¥", "юань", "юаней", "юани", "юанях");
        cur("JPY", "jpy", "иена", "иен", "иены", "иенах");
        cur("TRY", "try", "₺", "лира", "лир", "лиры", "лирах");
        cur("GEL", "gel", "₾", "лари");
        cur("AMD", "amd", "драм", "драмов");
        cur("KRW", "krw", "вон");
        cur("PLN", "pln", "злотых", "злотый");
        cur("CHF", "chf", "франк", "франков");
        cur("AED", "aed", "дирхам", "дирхамов");

        unit("length", 1, "м", "метр", "метра", "метров", "метры", "метрах", "m", "meter", "meters");
        unit("length", 1000, "км", "километр", "километра", "километров", "километры", "километрах", "km");
        unit("length", 0.01, "см", "сантиметр", "сантиметров", "сантиметры", "cm");
        unit("length", 0.001, "мм", "миллиметр", "миллиметров", "mm");
        unit("length", 1609.344, "миль", "миля", "мили", "милях", "mi", "mile", "miles");
        unit("length", 0.3048, "футов", "фут", "футы", "футах", "ft", "foot", "feet");
        unit("length", 0.0254, "дюймов", "дюйм", "дюймы", "дюймах", "inch", "inches");
        unit("length", 0.9144, "ярдов", "ярд", "yd", "yard", "yards");
        unit("mass", 1, "кг", "килограмм", "килограмма", "килограммов", "килограммы", "kg");
        unit("mass", 0.001, "г", "гр", "грамм", "грамма", "граммов", "g");
        unit("mass", 1000, "т", "тонна", "тонны", "тонн", "t");
        unit("mass", 0.45359237, "фунтов", "фунт", "фунта", "фунтах", "lb", "lbs");
        unit("mass", 0.0283495, "унций", "унция", "унции", "oz");
        unit("speed", 1, "м/с", "мс", "m/s");
        unit("speed", 0.277778, "км/ч", "кмч", "km/h", "kmh");
        unit("speed", 0.44704, "миль/ч", "mph");
        unit("volume", 1, "л", "литр", "литра", "литров", "литры", "l");
        unit("volume", 0.001, "мл", "миллилитр", "ml");
        unit("volume", 3.78541, "галлонов", "галлон", "галлоны", "gal");
        unit("data", 1, "байт", "b");
        unit("data", 1024, "КБ", "кб", "kb");
        unit("data", 1048576, "МБ", "мб", "mb");
        unit("data", 1073741824, "ГБ", "гб", "gb");
        unit("data", 1099511627776d, "ТБ", "тб", "tb");
        unit("time", 1, "с", "сек", "секунд", "секунды", "s", "sec");
        unit("time", 60, "мин", "минут", "минуты", "min");
        unit("time", 3600, "ч", "час", "часа", "часов", "часы", "h", "hour", "hours");
        unit("time", 86400, "дн", "д", "день", "дня", "дней", "дни", "day", "days");
        unit("time", 604800, "нед", "неделя", "недели", "недель", "week", "weeks");

        zone("Europe/Moscow", "москва", "москве", "мск", "msk", "moscow", "питер", "питере", "петербург", "петербурге");
        zone("Europe/London", "лондон", "лондоне", "london", "gmt", "uk");
        zone("Europe/Paris", "париж", "париже", "paris", "cet");
        zone("Europe/Berlin", "берлин", "берлине", "berlin", "германия", "германии");
        zone("Europe/Kyiv", "киев", "киеве", "kyiv", "kiev");
        zone("Europe/Minsk", "минск", "минске", "minsk");
        zone("Europe/Istanbul", "стамбул", "стамбуле", "istanbul", "турция", "турции");
        zone("Asia/Dubai", "дубай", "дубае", "dubai", "оаэ");
        zone("Asia/Tbilisi", "тбилиси", "tbilisi");
        zone("Asia/Yerevan", "ереван", "ереване", "yerevan");
        zone("Asia/Almaty", "алматы", "almaty", "астана", "астане");
        zone("Asia/Tashkent", "ташкент", "ташкенте", "tashkent");
        zone("Asia/Yekaterinburg", "екатеринбург", "екатеринбурге", "екб");
        zone("Asia/Novosibirsk", "новосибирск", "новосибирске");
        zone("Asia/Krasnoyarsk", "красноярск", "красноярске");
        zone("Asia/Irkutsk", "иркутск", "иркутске");
        zone("Asia/Vladivostok", "владивосток", "владивостоке");
        zone("Asia/Kolkata", "дели", "индия", "индии", "delhi", "india");
        zone("Asia/Bangkok", "бангкок", "бангкоке", "bangkok", "тайланд", "таиланд", "таиланде");
        zone("Asia/Shanghai", "пекин", "пекине", "шанхай", "шанхае", "китай", "китае", "beijing", "shanghai", "china");
        zone("Asia/Seoul", "сеул", "сеуле", "seoul", "корея", "корее");
        zone("Asia/Tokyo", "токио", "tokyo", "япония", "японии", "japan", "jst");
        zone("Australia/Sydney", "сидней", "сиднее", "sydney");
        zone("America/New_York", "нью-йорк", "нью-йорке", "ньюйорк", "newyork", "nyc", "est", "edt");
        zone("America/Chicago", "чикаго", "chicago", "cst");
        zone("America/Los_Angeles", "лос-анджелес", "лос-анджелесе", "лосанджелес", "la", "pst", "pdt");
        zone("UTC", "utc");
    }

    private PrizmaInputCalc() {
    }

    private static void cur(String code, String... names) {
        for (String n : names) CURRENCY.put(n, code);
    }

    private static void unit(String kind, double factor, String... names) {
        int k = kind.hashCode();
        for (String n : names) {
            UNITS.put(n, new double[]{k, factor});
            UNIT_LABEL.put(n, names[0]);
        }
    }

    private static void zone(String id, String... names) {
        for (String n : names) ZONES.put(n, id);
    }

    public static Result evaluate(CharSequence raw) {
        if (raw == null || raw.length() < 3 || raw.length() > 80) return null;
        String text = raw.toString().trim().toLowerCase(Locale.ROOT).replace('×', '*').replace('÷', '/').replace('−', '-');
        if (text.indexOf('\n') >= 0) return null;
        try {
            Result r = time(text);
            if (r == null) r = convert(text);
            if (r == null) r = math(text);
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    private static Result math(String text) {
        String expr = text.endsWith("=") ? text.substring(0, text.length() - 1).trim() : text;
        if (!expr.matches("[\\d\\s.,+\\-*/^()%]+")) return null;
        if (!expr.matches(".*\\d\\s*[+\\-*/^%].*")) return null;
        if (expr.matches("[\\d\\s+\\-()]+") && !expr.matches(".*\\d\\s*[+\\-]\\s*\\d.*")) return null;
        if (expr.matches("\\+?[\\d\\s\\-()]{7,}")) return null;
        double value = new Parser(expr.replace(" ", "").replace(',', '.')).parse();
        if (Double.isNaN(value) || Double.isInfinite(value)) return null;
        return new Result(expr + " =", format(value));
    }

    private static Result convert(String text) {
        Matcher m = AMOUNT.matcher(text);
        if (!m.matches()) {
            Matcher b = BARE.matcher(text);
            if (!b.matches()) return null;
            String from = code(b.group(2));
            if (from == null) return null;
            double amount = number(b.group(1));
            String to = "RUB".equals(from) ? "USD" : "RUB";
            return currency(amount, from, to);
        }
        double amount = number(m.group(1));
        String a = clean(m.group(2)), b = clean(m.group(3));
        double[] ua = UNITS.get(a), ub = UNITS.get(b);
        if (ua != null && ub != null && ua[0] == ub[0]) {
            return new Result(format(amount) + " " + UNIT_LABEL.get(a) + " =", format(amount * ua[1] / ub[1]) + " " + UNIT_LABEL.get(b));
        }
        String fromCur = code(a), toCur = code(b);
        if (fromCur != null && toCur != null) return currency(amount, fromCur, toCur);
        if (a.equals("c") || a.equals("с") || a.equals("f") || a.equals("ф") || a.equals("°c") || a.equals("°f")) {
            Result t = temperature(amount, a, b);
            if (t != null) return t;
        }
        return null;
    }

    private static Result temperature(double v, String from, String to) {
        boolean fromC = from.endsWith("c") || from.endsWith("с");
        boolean toF = to.endsWith("f") || to.endsWith("ф") || to.startsWith("фаренг");
        boolean toC = to.endsWith("c") || to.endsWith("с") || to.startsWith("цельс");
        if (fromC && toF) return new Result(format(v) + " °C =", format(v * 9 / 5 + 32) + " °F");
        if (!fromC && toC) return new Result(format(v) + " °F =", format((v - 32) * 5 / 9) + " °C");
        return null;
    }

    private static String clean(String s) {
        return s.replaceAll("[.,;!?]+$", "");
    }

    private static String code(String s) {
        String c = clean(s);
        String found = CURRENCY.get(c);
        if (found != null) return found;
        String upper = c.toUpperCase(Locale.ROOT);
        return upper.length() == 3 && rates.containsKey(upper) ? upper : null;
    }

    private static Result currency(double amount, String from, String to) {
        loadRates();
        Double a = rates.get(from), b = rates.get(to);
        if (a == null || b == null) return null;
        double v = amount / a * b;
        return new Result(format(amount) + " " + from + " =", format(v) + " " + to);
    }

    private static Result time(String text) {
        Matcher m = TIME_IN.matcher(text);
        if (m.matches()) {
            String id = ZONES.get(clean(m.group(1)).trim());
            if (id == null) return null;
            Calendar c = Calendar.getInstance(TimeZone.getTimeZone(id));
            return new Result("Время в " + m.group(1).trim(), String.format(Locale.ROOT, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)));
        }
        Matcher t = TIME_CONVERT.matcher(text);
        if (!t.matches()) return null;
        String from = ZONES.get(clean(t.group(3))), to = ZONES.get(clean(t.group(4)));
        if (from == null || to == null) return null;
        int h = Integer.parseInt(t.group(1));
        int min = t.group(2) == null ? 0 : Integer.parseInt(t.group(2));
        if (h > 23 || min > 59) return null;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone(from));
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, min);
        Calendar out = Calendar.getInstance(TimeZone.getTimeZone(to));
        out.setTimeInMillis(c.getTimeInMillis());
        return new Result(String.format(Locale.ROOT, "%02d:%02d %s =", h, min, t.group(3)), String.format(Locale.ROOT, "%02d:%02d %s", out.get(Calendar.HOUR_OF_DAY), out.get(Calendar.MINUTE), t.group(4)));
    }

    private static void loadRates() {
        if (ratesLoading || !rates.isEmpty() && System.currentTimeMillis() - ratesAt < 6 * 3600 * 1000L) return;
        ratesLoading = true;
        PrizmaJobs.net(() -> {
            try {
                String body = PrizmaCloud.enabled() ? PrizmaNet.get(PrizmaCloud.host() + "/v1/rates", 8000) : null;
                if (body == null) body = PrizmaNet.get("https://open.er-api.com/v6/latest/USD", 8000);
                JSONObject table = body == null ? null : new JSONObject(body).optJSONObject("rates");
                if (table != null) {
                    HashMap<String, Double> next = new HashMap<>();
                    java.util.Iterator<String> keys = table.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        next.put(k, table.optDouble(k));
                    }
                    final HashMap<String, Double> ready = next;
                    AndroidUtilities.runOnUIThread(() -> {
                        rates.clear();
                        rates.putAll(ready);
                        ratesAt = System.currentTimeMillis();
                    });
                }
            } catch (Throwable ignore) {
            }
            ratesLoading = false;
        });
    }

    private static double number(String s) {
        return Double.parseDouble(s.replace(" ", "").replace(',', '.'));
    }

    private static String format(double v) {
        BigDecimal d = new BigDecimal(v, new MathContext(12, RoundingMode.HALF_UP));
        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.ROOT);
        sym.setGroupingSeparator(' ');
        sym.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(Math.abs(v) >= 100 ? "#,##0.##" : "#,##0.####", sym);
        return f.format(d);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        double parse() {
            double v = sum();
            if (i != s.length()) throw new IllegalArgumentException();
            return v;
        }

        private double sum() {
            double v = product();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != '+' && c != '-') break;
                i++;
                double r = product();
                if (i < s.length() && s.charAt(i) == '%') {
                    i++;
                    r = v * r / 100;
                }
                v = c == '+' ? v + r : v - r;
            }
            return v;
        }

        private double product() {
            double v = power();
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '*') {
                    i++;
                    v *= power();
                } else if (c == '/') {
                    i++;
                    v /= power();
                } else if (c == '%' && i + 1 < s.length() && "+-*/)".indexOf(s.charAt(i + 1)) < 0) {
                    i++;
                    v %= power();
                } else {
                    break;
                }
            }
            return v;
        }

        private double power() {
            double v = atom();
            if (i < s.length() && s.charAt(i) == '^') {
                i++;
                v = Math.pow(v, power());
            }
            return v;
        }

        private double atom() {
            if (i >= s.length()) throw new IllegalArgumentException();
            char c = s.charAt(i);
            if (c == '-') {
                i++;
                return -atom();
            }
            if (c == '(') {
                i++;
                double v = sum();
                if (i >= s.length() || s.charAt(i) != ')') throw new IllegalArgumentException();
                i++;
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException();
            return Double.parseDouble(s.substring(start, i));
        }
    }
}
