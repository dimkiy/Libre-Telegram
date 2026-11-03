package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PrizmaLyrics {
    public static class Line {
        public long timeMs;
        public String text;

        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static class Lyrics {
        public boolean synced;
        public ArrayList<Line> lines;
        public String plain;
    }

    public interface Callback {
        void onResult(Lyrics lyrics);
    }

    private static final HashMap<String, Lyrics> cache = new HashMap<>();
    private static final HashMap<String, Long> misses = new HashMap<>();
    private static final long MISS_RETRY_MS = 2 * 60 * 1000L;
    private static final Pattern TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    public static void fetch(String title, String author, int durationSec, Callback callback) {
        if (title == null) title = "";
        if (author == null) author = "";
        String t = clean(title);
        String a = clean(author);
        if (t.length() < 1 && a.length() < 1) {
            callback.onResult(null);
            return;
        }
        final String key = (t + "|" + a + "|" + durationSec).toLowerCase();
        synchronized (cache) {
            final Lyrics cached = cache.get(key);
            final Long missAt = misses.get(key);
            if (cached != null || missAt != null && System.currentTimeMillis() - missAt < MISS_RETRY_MS) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(cached));
                return;
            }
        }
        final String track = t;
        final String artist = a;
        Utilities.globalQueue.postRunnable(() -> {
            Lyrics result = null;
            try {
                result = requestGet(track, artist, durationSec);
                if (result == null) {
                    result = requestSearch(track, artist);
                }
            } catch (Throwable ignore) {
            }
            final Lyrics finalResult = result;
            synchronized (cache) {
                if (finalResult != null) {
                    cache.put(key, finalResult);
                    misses.remove(key);
                } else {
                    misses.put(key, System.currentTimeMillis());
                }
            }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(finalResult));
        });
    }

    private static Lyrics requestGet(String track, String artist, int durationSec) throws Exception {
        StringBuilder url = new StringBuilder("https://lrclib.net/api/get?track_name=")
                .append(URLEncoder.encode(track, "UTF-8"))
                .append("&artist_name=").append(URLEncoder.encode(artist, "UTF-8"));
        if (durationSec > 0) {
            url.append("&duration=").append(durationSec);
        }
        String body = httpGet(url.toString());
        if (body == null) {
            return null;
        }
        return parseEntry(new JSONObject(body));
    }

    private static Lyrics requestSearch(String track, String artist) throws Exception {
        String q = (track + " " + artist).trim();
        String url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8");
        String body = httpGet(url);
        if (body == null) {
            return null;
        }
        JSONArray arr = new JSONArray(body);
        for (int i = 0; i < arr.length(); i++) {
            Lyrics l = parseEntry(arr.getJSONObject(i));
            if (l != null) {
                return l;
            }
        }
        return null;
    }

    private static Lyrics parseEntry(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String synced = obj.optString("syncedLyrics", "");
        String plain = obj.optString("plainLyrics", "");
        if (synced != null && synced.length() > 0) {
            ArrayList<Line> lines = parseLrc(synced);
            if (!lines.isEmpty()) {
                Lyrics l = new Lyrics();
                l.synced = true;
                l.lines = lines;
                l.plain = (plain != null && plain.length() > 0) ? plain : stripToPlain(lines);
                return l;
            }
        }
        if (plain != null && plain.length() > 0) {
            Lyrics l = new Lyrics();
            l.synced = false;
            l.lines = new ArrayList<>();
            for (String s : plain.split("\n")) {
                l.lines.add(new Line(-1, s));
            }
            l.plain = plain;
            return l;
        }
        return null;
    }

    private static ArrayList<Line> parseLrc(String lrc) {
        ArrayList<Line> out = new ArrayList<>();
        String[] rows = lrc.split("\n");
        for (String row : rows) {
            Matcher m = TAG.matcher(row);
            ArrayList<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int sec = Integer.parseInt(m.group(2));
                int frac = 0;
                if (m.group(3) != null) {
                    String f = m.group(3);
                    if (f.length() == 1) frac = Integer.parseInt(f) * 100;
                    else if (f.length() == 2) frac = Integer.parseInt(f) * 10;
                    else frac = Integer.parseInt(f.substring(0, 3));
                }
                times.add((long) ((min * 60 + sec) * 1000 + frac));
                lastEnd = m.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = row.substring(lastEnd).trim();
            for (long tm : times) {
                out.add(new Line(tm, text));
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(x.timeMs, y.timeMs));
        return out;
    }

    private static String stripToPlain(ArrayList<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line l : lines) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(l.text);
        }
        return sb.toString();
    }

    private static String httpGet(String url) {
        return PrizmaNet.get(url, 6000);
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace("<unknown>", "").replace("\n", " ").trim();
    }
}
