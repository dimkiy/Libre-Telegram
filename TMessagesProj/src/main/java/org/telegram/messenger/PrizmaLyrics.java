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
        public boolean instrumental;
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
        PrizmaJobs.net(() -> {
            final Lyrics finalResult = find(track, artist, durationSec);
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

    private interface Attempt {
        Lyrics run() throws Exception;
    }

    private static Lyrics attempt(Attempt a) {
        try {
            return a.run();
        } catch (Throwable e) {
            return null;
        }
    }

    private static Lyrics find(String track, String artist, int durationSec) {
        final String plainTrack = PrizmaClips.cleanTitle(stripArtist(track, artist));
        final String plainArtist = PrizmaClips.cleanArtist(artist);
        final boolean cleaned = !plainTrack.isEmpty() && (!plainTrack.equals(track) || !plainArtist.equals(artist));
        Lyrics result = null;
        if (PrizmaCloud.enabled()) {
            result = attempt(() -> fromServer(track, artist, durationSec));
            if (result == null && cleaned) result = attempt(() -> fromServer(plainTrack, plainArtist, durationSec));
        }
        if (result == null) result = attempt(() -> requestGet(track, artist, durationSec));
        if (result == null && cleaned) result = attempt(() -> requestGet(plainTrack, plainArtist, durationSec));
        final String title = plainTrack.isEmpty() ? track : plainTrack;
        if (result == null) result = attempt(() -> requestSearch((title + " " + plainArtist).trim(), durationSec));
        if (result == null && !plainArtist.isEmpty() && durationSec > 0) result = attempt(() -> requestSearch(title, durationSec));
        return result;
    }

    private static String stripArtist(String track, String artist) {
        String t = track.replace('_', ' ').replaceAll("(?i)\\.(mp3|m4a|flac|ogg|wav|opus)$", "").trim();
        if (!artist.isEmpty() && t.toLowerCase().startsWith(artist.toLowerCase())) {
            String rest = t.substring(artist.length()).trim();
            if (rest.startsWith("-") || rest.startsWith("—") || rest.startsWith("–")) return rest.substring(1).trim();
        }
        return t;
    }

    private static Lyrics fromServer(String track, String artist, int durationSec) throws Exception {
        String answer = askServer(track, artist, durationSec);
        return answer == null ? null : parseEntry(new JSONObject(answer));
    }

    public static Lyrics fromTags(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        ArrayList<Line> timed = parseLrc(text);
        Lyrics l = new Lyrics();
        if (!timed.isEmpty()) {
            l.synced = true;
            l.lines = timed;
            l.plain = stripToPlain(timed);
            return l;
        }
        l.lines = new ArrayList<>();
        for (String s : text.split("\n")) {
            l.lines.add(new Line(-1, s.trim()));
        }
        l.plain = text;
        return l;
    }

    private static String askServer(String track, String artist, int durationSec) throws Exception {
        String url = PrizmaCloud.host() + "/v1/lyrics?title=" + URLEncoder.encode(track, "UTF-8")
                + "&artist=" + URLEncoder.encode(artist, "UTF-8") + "&dur=" + durationSec;
        return PrizmaNet.get(url, 14000);
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

    private static Lyrics requestSearch(String q, int durationSec) throws Exception {
        if (q.isEmpty()) return null;
        String url = "https://lrclib.net/api/search?q=" + URLEncoder.encode(q, "UTF-8");
        String body = httpGet(url);
        if (body == null) {
            return null;
        }
        JSONArray arr = new JSONArray(body);
        Lyrics first = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.getJSONObject(i);
            Lyrics l = parseEntry(row);
            if (l == null) continue;
            double length = row.optDouble("duration", 0);
            if (durationSec <= 0 || length <= 0 || Math.abs(length - durationSec) <= 3) return l;
            if (first == null && Math.abs(length - durationSec) <= 12) first = l;
        }
        return first;
    }

    private static Lyrics parseEntry(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        if (obj.optBoolean("instrumental", false)) {
            Lyrics l = new Lyrics();
            l.instrumental = true;
            l.lines = new ArrayList<>();
            l.plain = "";
            return l;
        }
        String synced = obj.isNull("syncedLyrics") ? "" : obj.optString("syncedLyrics", "");
        String plain = obj.isNull("plainLyrics") ? "" : obj.optString("plainLyrics", "");
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
