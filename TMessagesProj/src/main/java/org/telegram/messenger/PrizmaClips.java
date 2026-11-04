package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;

public final class PrizmaClips {
    public static final int OWN_REQUEST = 48221;

    public static final class Clip {
        public final File file;
        public final boolean exact;
        public final boolean own;

        Clip(File file, boolean exact, boolean own) {
            this.file = file;
            this.exact = exact;
            this.own = own;
        }
    }

    public interface Callback {
        void onResult(Clip clip);
    }

    private static final long MAX_CLIP = 40L * 1024 * 1024;
    private static final long CACHE_LIMIT = 220L * 1024 * 1024;
    private static final long MISS_RETRY_MS = 6 * 3600 * 1000L;

    private static final HashMap<String, Long> misses = new HashMap<>();

    private PrizmaClips() {
    }

    public static void find(MessageObject mo, Callback callback) {
        if (mo == null) {
            callback.onResult(null);
            return;
        }
        final String title = cleanTitle(mo.getMusicTitle());
        final String artist = cleanArtist(mo.getMusicAuthor());
        final Clip own = ownClip(title, artist);
        if (own != null) {
            callback.onResult(own);
            return;
        }
        if (!PrizmaConfig.playerClips() || title.isEmpty() || artist.isEmpty()) {
            callback.onResult(null);
            return;
        }
        final boolean allowArtist = PrizmaConfig.playerArtistClips();
        final String key = sha(lower(artist) + "|" + lower(title));
        final File exactFile = new File(dir(), key + ".m4v");
        if (exactFile.exists() && exactFile.length() > 0) {
            exactFile.setLastModified(System.currentTimeMillis());
            callback.onResult(new Clip(exactFile, true, false));
            return;
        }
        final File artistFile = new File(dir(), "a" + sha(lower(artist)) + ".m4v");
        if (!PrizmaConfig.playerClipsMobile() && metered()) {
            callback.onResult(allowArtist && artistFile.exists() ? new Clip(artistFile, false, false) : null);
            return;
        }
        synchronized (misses) {
            Long at = misses.get(key);
            if (at != null && System.currentTimeMillis() - at < MISS_RETRY_MS) {
                callback.onResult(allowArtist && artistFile.exists() ? new Clip(artistFile, false, false) : null);
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            Clip result = null;
            try {
                String[] found = lookup(title, artist);
                boolean exact = found != null && "1".equals(found[1]);
                if (found != null && (exact || allowArtist)) {
                    File target = exact ? exactFile : artistFile;
                    if (target.exists() || PrizmaNet.download(found[0], target, MAX_CLIP, 25000)) {
                        result = new Clip(target, exact, false);
                        trim();
                    }
                }
                if (!exact) {
                    synchronized (misses) {
                        misses.put(key, System.currentTimeMillis());
                    }
                }
            } catch (Throwable ignore) {
            }
            final Clip done = result;
            AndroidUtilities.runOnUIThread(() -> callback.onResult(done));
        });
    }

    private static boolean metered() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            return cm == null || cm.isActiveNetworkMetered();
        } catch (Throwable e) {
            return true;
        }
    }

    private static String[] lookup(String title, String artist) {
        if (PrizmaCloud.enabled()) {
            String[] viaServer = lookupServer(title, artist);
            if (viaServer != null) return viaServer.length == 0 ? null : viaServer;
        }
        return lookupDirect(title, artist);
    }

    private static String[] lookupServer(String title, String artist) {
        try {
            String url = PrizmaCloud.host() + "/v1/clip?artist=" + URLEncoder.encode(artist, "UTF-8") + "&title=" + URLEncoder.encode(title, "UTF-8");
            String body = PrizmaNet.get(url, 12000);
            if (body == null) return null;
            JSONObject o = new JSONObject(body);
            String path = o.optString("url", "");
            if (path.isEmpty()) return new String[0];
            return new String[]{path.startsWith("http") ? path : PrizmaCloud.host() + path, o.optBoolean("exact") ? "1" : "0"};
        } catch (Throwable e) {
            return null;
        }
    }

    private static String[] lookupDirect(String title, String artist) {
        try {
            String body = PrizmaNet.get("https://itunes.apple.com/search?entity=musicVideo&limit=12&term=" + URLEncoder.encode(artist + " " + title, "UTF-8"), 9000);
            String[] hit = pick(body, title, artist);
            if (hit != null && "1".equals(hit[1])) return hit;
            String more = PrizmaNet.get("https://itunes.apple.com/search?entity=musicVideo&limit=12&attribute=artistTerm&term=" + URLEncoder.encode(artist, "UTF-8"), 9000);
            String[] byArtist = pick(more, title, artist);
            return byArtist != null ? byArtist : hit;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String[] pick(String body, String title, String artist) throws Exception {
        if (body == null) return null;
        JSONArray rows = new JSONObject(body).optJSONArray("results");
        if (rows == null) return null;
        String wantTitle = squash(title);
        String wantArtist = squash(artist);
        String fallback = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.getJSONObject(i);
            String preview = r.optString("previewUrl", "");
            if (preview.isEmpty()) continue;
            String hasArtist = squash(r.optString("artistName", ""));
            if (!sameArtist(hasArtist, wantArtist)) continue;
            String hasTitle = squash(r.optString("trackName", ""));
            if (hasTitle.equals(wantTitle) || hasTitle.startsWith(wantTitle) || wantTitle.startsWith(hasTitle) && hasTitle.length() > 3) {
                return new String[]{preview, "1"};
            }
            if (fallback == null) fallback = preview;
        }
        return fallback == null ? null : new String[]{fallback, "0"};
    }

    private static boolean sameArtist(String a, String b) {
        return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a));
    }

    private static String squash(String s) {
        return lower(s).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).trim();
    }

    public static String cleanTitle(String s) {
        if (s == null) return "";
        return s.replace("<unknown>", "")
                .replaceAll("(?i)[\\(\\[][^\\)\\]]*(official|video|audio|lyric|remaster|feat|prod|explicit|hq|hd)[^\\)\\]]*[\\)\\]]", "")
                .trim();
    }

    public static String cleanArtist(String s) {
        if (s == null) return "";
        String v = s.replace("<unknown>", "").trim();
        int cut = -1;
        String low = v.toLowerCase(Locale.ROOT);
        for (String sep : new String[]{" feat", " ft.", " & ", ",", " x ", " и "}) {
            int i = low.indexOf(sep);
            if (i > 0 && (cut < 0 || i < cut)) cut = i;
        }
        return cut > 0 ? v.substring(0, cut).trim() : v;
    }

    private static File dir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "libre_clips");
        d.mkdirs();
        return d;
    }

    private static File ownDir() {
        File d = new File(ApplicationLoader.getFilesDirFixed(), "libre_clips");
        d.mkdirs();
        return d;
    }

    private static Clip ownClip(String title, String artist) {
        File f = new File(ownDir(), sha(lower(artist) + "|" + lower(title)) + ".mp4");
        return f.exists() && f.length() > 0 ? new Clip(f, true, true) : null;
    }

    public static boolean hasOwn(MessageObject mo) {
        return mo != null && ownClip(cleanTitle(mo.getMusicTitle()), cleanArtist(mo.getMusicAuthor())) != null;
    }

    public static File ownTarget(MessageObject mo) {
        return new File(ownDir(), sha(lower(cleanArtist(mo.getMusicAuthor())) + "|" + lower(cleanTitle(mo.getMusicTitle()))) + ".mp4");
    }

    public static void clearOwn(MessageObject mo) {
        if (mo != null) ownTarget(mo).delete();
    }

    private static void trim() {
        File[] files = dir().listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= CACHE_LIMIT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= CACHE_LIMIT * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 12; i++) b.append(String.format(Locale.ROOT, "%02x", d[i]));
            return b.toString();
        } catch (Throwable e) {
            return String.valueOf(s.hashCode());
        }
    }
}
