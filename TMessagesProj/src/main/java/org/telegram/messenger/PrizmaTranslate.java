package org.telegram.messenger;

import android.net.Uri;

import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.TranslateAlert2;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrizmaTranslate {
    public static final int GOOGLE = 0;
    public static final int YANDEX = 1;

    private static final Pattern TAG = Pattern.compile("<\\s*(/?)\\s*[eE]\\s*(\\d+)\\s*>");
    private static String yandexId;

    private PrizmaTranslate() {
    }

    public static boolean useFree(int account) {
        return PrizmaConfig.freeTranslate() && !UserConfig.getInstance(account).isPremium();
    }

    public static void translate(TLRPC.TL_textWithEntities source, String to, Utilities.Callback<TLRPC.TL_textWithEntities> done) {
        final String text = source == null || source.text == null ? "" : source.text;
        final ArrayList<TLRPC.MessageEntity> entities = source == null ? new ArrayList<>() : new ArrayList<>(source.entities);
        Collections.sort(entities, (a, b) -> Integer.compare(a.offset, b.offset));
        final String tagged = tagged(text, entities);
        run(tagged != null ? tagged : text, to, result -> {
            if (result == null) {
                done.run(null);
                return;
            }
            TLRPC.TL_textWithEntities out = tagged != null ? untag(result, entities) : null;
            if (out == null) {
                out = new TLRPC.TL_textWithEntities();
                out.text = TAG.matcher(result).replaceAll("");
            }
            done.run(out);
        });
    }

    private static String tagged(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (entities.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity e = entities.get(i);
            int start = e.offset, end = e.offset + e.length;
            if (start < at || end > text.length() || e.length <= 0) return null;
            sb.append(text, at, start).append("<e").append(i).append('>').append(text, start, end).append("</e").append(i).append('>');
            at = end;
        }
        sb.append(text.substring(at));
        return sb.toString();
    }

    private static TLRPC.TL_textWithEntities untag(String translated, ArrayList<TLRPC.MessageEntity> entities) {
        StringBuilder plain = new StringBuilder();
        int[] starts = new int[entities.size()];
        int[] ends = new int[entities.size()];
        java.util.Arrays.fill(starts, -1);
        java.util.Arrays.fill(ends, -1);
        Matcher m = TAG.matcher(translated);
        int at = 0;
        while (m.find()) {
            plain.append(translated, at, m.start());
            at = m.end();
            int index;
            try {
                index = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 0 || index >= entities.size()) return null;
            if ("/".equals(m.group(1))) {
                ends[index] = plain.length();
            } else {
                starts[index] = plain.length();
            }
        }
        plain.append(translated.substring(at));
        TLRPC.TL_textWithEntities out = new TLRPC.TL_textWithEntities();
        out.text = plain.toString();
        for (int i = 0; i < entities.size(); i++) {
            if (starts[i] < 0 || ends[i] <= starts[i]) continue;
            TLRPC.MessageEntity copy = copy(entities.get(i));
            if (copy == null) continue;
            copy.offset = starts[i];
            copy.length = ends[i] - starts[i];
            out.entities.add(copy);
        }
        return out;
    }

    private static TLRPC.MessageEntity copy(TLRPC.MessageEntity e) {
        try {
            org.telegram.tgnet.SerializedData data = new org.telegram.tgnet.SerializedData(e.getObjectSize());
            e.serializeToStream(data);
            org.telegram.tgnet.SerializedData in = new org.telegram.tgnet.SerializedData(data.toByteArray());
            TLRPC.MessageEntity c = TLRPC.MessageEntity.TLdeserialize(in, in.readInt32(false), false);
            data.cleanup();
            in.cleanup();
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void run(String text, String to, Utilities.Callback<String> done) {
        if (PrizmaConfig.translateEngine() == YANDEX) {
            Utilities.globalQueue.postRunnable(() -> {
                String result = yandex(text, to);
                if (result != null) {
                    AndroidUtilities.runOnUIThread(() -> done.run(result));
                } else {
                    AndroidUtilities.runOnUIThread(() -> google(text, to, done));
                }
            });
        } else {
            google(text, to, done);
        }
    }

    private static void google(String text, String to, Utilities.Callback<String> done) {
        TranslateAlert2.alternativeTranslate(text, null, to, (result, rateLimit) -> done.run(result));
    }

    private static String yandex(String text, String to) {
        HttpURLConnection c = null;
        try {
            if (yandexId == null) yandexId = UUID.randomUUID().toString().replace("-", "");
            String url = "https://translate.yandex.net/api/v1/tr.json/translate?srv=android&id=" + yandexId + "-0-0&lang=" + Uri.encode(to);
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.getOutputStream().write(("text=" + Uri.encode(text)).getBytes("UTF-8"));
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bytes.write(buf, 0, r);
            JSONObject json = new JSONObject(bytes.toString("UTF-8"));
            if (json.optInt("code") != 200) return null;
            return json.getJSONArray("text").getString(0);
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
