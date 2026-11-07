package org.telegram.messenger;

import android.net.Uri;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrizmaLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
    private static final HashSet<String> ALWAYS = new HashSet<>(Arrays.asList(
            "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid", "mc_eid", "yclid", "ysclid",
            "igshid", "igsh", "_openstat", "ref_src", "ref_url", "spm", "share_source", "share_medium",
            "_hsenc", "_hsmi", "mkt_tok", "vero_id", "oly_enc_id", "oly_anon_id", "rb_clickid", "wickedid", "trk", "trkid"
    ));

    private PrizmaLinks() {
    }

    public static String clean(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text) || !PrizmaConfig.cleanLinks() || text.indexOf("http") < 0) return text;
        StringBuilder out = new StringBuilder();
        Matcher m = URL.matcher(text);
        int at = 0;
        while (m.find()) {
            String url = m.group();
            String trail = "";
            while (url.length() > 0 && ".,!?;:)]}".indexOf(url.charAt(url.length() - 1)) >= 0) {
                trail = url.charAt(url.length() - 1) + trail;
                url = url.substring(0, url.length() - 1);
            }
            String cleaned = cleanUrl(url);
            out.append(text, at, m.start());
            int shift = cleaned.length() - url.length();
            if (shift != 0 && entities != null) {
                shiftEntities(entities, out.length() + url.length(), shift, out.length(), out.length() + url.length());
            }
            out.append(cleaned).append(trail);
            at = m.end();
        }
        out.append(text.substring(at));
        return out.toString();
    }

    private static void shiftEntities(ArrayList<TLRPC.MessageEntity> entities, int after, int shift, int urlStart, int urlEnd) {
        for (TLRPC.MessageEntity e : entities) {
            int end = e.offset + e.length;
            if (e.offset >= after) {
                e.offset += shift;
            } else if (end >= urlEnd && e.offset <= urlStart) {
                e.length += shift;
            }
        }
    }

    public static String cleanUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String query = uri.getEncodedQuery();
            if (TextUtils.isEmpty(query)) return url;
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
            StringBuilder kept = new StringBuilder();
            boolean removed = false;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                String key = (eq >= 0 ? pair.substring(0, eq) : pair).toLowerCase(Locale.US);
                if (tracking(key, host)) {
                    removed = true;
                    continue;
                }
                if (kept.length() > 0) kept.append('&');
                kept.append(pair);
            }
            if (!removed) return url;
            Uri.Builder b = uri.buildUpon().encodedQuery(kept.length() > 0 ? kept.toString() : null);
            return b.build().toString();
        } catch (Throwable e) {
            return url;
        }
    }

    private static boolean tracking(String key, String host) {
        if (key.startsWith("utm_") || ALWAYS.contains(key)) return true;
        if (host.endsWith("youtube.com") || host.endsWith("youtu.be")) {
            return key.equals("si") || key.equals("feature") || key.equals("pp");
        }
        if (host.endsWith("spotify.com")) {
            return key.equals("si") || key.equals("context") || key.equals("nd");
        }
        if (host.endsWith("twitter.com") || host.equals("x.com") || host.endsWith(".x.com")) {
            return key.equals("s") || key.equals("t");
        }
        if (host.endsWith("tiktok.com")) {
            return key.startsWith("_") || key.equals("is_from_webapp") || key.startsWith("sender_") || key.equals("share_app_id") || key.equals("social_sharing");
        }
        if (host.endsWith("instagram.com")) {
            return key.equals("img_index");
        }
        if (host.endsWith("music.yandex.ru") || host.endsWith("music.yandex.com")) {
            return key.equals("utm_source") || key.equals("ref_id");
        }
        return false;
    }
}
