package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

public class PrizmaPlaylist {
    public static final long DEFAULT_ID = 1L;
    private static final String DEFAULT_NAME = "Мне нравится";

    private static final java.util.HashMap<String, MessageObject> liveCache = new java.util.HashMap<>();

    public static class Playlist {
        public long id;
        public String name;
        public String cover;
        public int count;
    }

    public static class Track {
        public long did;
        public int mid;
        public String title;
        public String author;
    }

    private static String key(long did, int mid) {
        return did + "_" + mid;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_playlist", Context.MODE_PRIVATE);
    }

    private static JSONArray roots() {
        ensureInit();
        try {
            String s = prefs().getString("playlists", "");
            if (s != null && s.length() > 0) {
                return new JSONArray(s);
            }
        } catch (Throwable ignore) {
        }
        return new JSONArray();
    }

    private static void saveRoots(JSONArray a) {
        countCache.clear();
        prefs().edit().putString("playlists", a.toString()).apply();
        if (publicId() != 0) PrizmaCloud.syncPlaylistSoon(UserConfig.selectedAccount);
    }

    public static long publicId() {
        return prefs().getLong("public_pl", 0);
    }

    public static void setPublic(long id) {
        prefs().edit().putLong("public_pl", id).apply();
        PrizmaCloud.syncPlaylistSoon(UserConfig.selectedAccount);
    }

    public static JSONArray exportPublic(long id) {
        JSONArray out = new JSONArray();
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return out;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            TLRPC.Message msg = decode(o.optString("data", ""));
            if (msg == null || msg.media == null) continue;
            final String src = publicSource(msg);
            final TLRPC.Document doc = msg.media.document;
            try {
                JSONObject e = new JSONObject();
                e.put("t", o.optString("t", ""));
                e.put("a", o.optString("a", ""));
                if (src != null) e.put("src", src);
                if (doc != null) {
                    e.put("doc", doc.id);
                    e.put("size", doc.size);
                }
                out.put(e);
            } catch (Throwable ignore) {
            }
        }
        return out;
    }

    private static String publicSource(TLRPC.Message msg) {
        MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
        if (msg.peer_id instanceof TLRPC.TL_peerChannel) {
            String name = ChatObject.getPublicUsername(mc.getChat(msg.peer_id.channel_id));
            if (name != null && !name.isEmpty()) return name + "/" + msg.id;
        }
        TLRPC.MessageFwdHeader fwd = msg.fwd_from;
        if (fwd != null && fwd.from_id instanceof TLRPC.TL_peerChannel && fwd.channel_post != 0) {
            String name = ChatObject.getPublicUsername(mc.getChat(fwd.from_id.channel_id));
            if (name != null && !name.isEmpty()) return name + "/" + fwd.channel_post;
        }
        if (fwd != null && fwd.saved_from_peer instanceof TLRPC.TL_peerChannel && fwd.saved_from_msg_id != 0) {
            String name = ChatObject.getPublicUsername(mc.getChat(fwd.saved_from_peer.channel_id));
            if (name != null && !name.isEmpty()) return name + "/" + fwd.saved_from_msg_id;
        }
        return null;
    }

    public static long[] sourceMessage(long playlistId, long docId) {
        JSONArray a = roots();
        int idx = indexOf(a, playlistId);
        if (idx < 0) return null;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            TLRPC.Message msg = decode(o.optString("data", ""));
            if (msg != null && msg.media != null && msg.media.document != null && msg.media.document.id == docId) {
                return new long[]{o.optLong("did"), o.optInt("mid")};
            }
        }
        return null;
    }

    public static long importTracks(String name, ArrayList<MessageObject> list) {
        long id = createPlaylist(name, "");
        for (MessageObject mo : list) addTo(id, mo);
        return id;
    }

    private static final java.util.HashMap<Long, Integer> countCache = new java.util.HashMap<>();

    private static void ensureInit() {
        try {
            String s = prefs().getString("playlists", "");
            if (s != null && s.length() > 0) {
                return;
            }
            JSONArray old = new JSONArray();
            try {
                String oldS = prefs().getString("tracks", "");
                if (oldS != null && oldS.length() > 0) {
                    old = new JSONArray(oldS);
                }
            } catch (Throwable ignore) {
            }
            JSONObject def = new JSONObject();
            def.put("id", DEFAULT_ID);
            def.put("name", DEFAULT_NAME);
            def.put("cover", "");
            def.put("tracks", old);
            JSONArray pls = new JSONArray();
            pls.put(def);
            prefs().edit().putString("playlists", pls.toString()).remove("tracks").apply();
        } catch (Throwable ignore) {
        }
    }

    private static int indexOf(JSONArray a, long id) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("id") == id) {
                return i;
            }
        }
        return -1;
    }

    private static JSONArray tracksArr(JSONObject playlist) {
        JSONArray t = playlist.optJSONArray("tracks");
        return t == null ? new JSONArray() : t;
    }

    public static ArrayList<Playlist> playlists() {
        ArrayList<Playlist> out = new ArrayList<>();
        JSONArray a = roots();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Playlist p = new Playlist();
            p.id = o.optLong("id");
            p.name = o.optString("name", "Плейлист");
            p.cover = o.optString("cover", "");
            p.count = tracksArr(o).length();
            out.add(p);
        }
        return out;
    }

    public static Playlist get(long id) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return null;
        JSONObject o = a.optJSONObject(idx);
        Playlist p = new Playlist();
        p.id = o.optLong("id");
        p.name = o.optString("name", "Плейлист");
        p.cover = o.optString("cover", "");
        p.count = tracksArr(o).length();
        return p;
    }

    public static long createPlaylist(String name, String cover) {
        JSONArray a = roots();
        long id = System.currentTimeMillis();
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name == null || name.trim().isEmpty() ? "Новый плейлист" : name.trim());
            o.put("cover", cover == null ? "" : cover);
            o.put("tracks", new JSONArray());
            a.put(o);
            saveRoots(a);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return id;
    }

    public static void renamePlaylist(long id, String name) {
        if (name == null || name.trim().isEmpty()) return;
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return;
        try {
            a.getJSONObject(idx).put("name", name.trim());
            saveRoots(a);
        } catch (Throwable ignore) {
        }
    }

    public static void setCover(long id, String path) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return;
        try {
            a.getJSONObject(idx).put("cover", path == null ? "" : path);
            saveRoots(a);
        } catch (Throwable ignore) {
        }
    }

    public static String cover(long id) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return "";
        return a.optJSONObject(idx).optString("cover", "");
    }

    public static void deletePlaylist(long id) {
        if (id == publicId()) setPublic(0);
        if (id == DEFAULT_ID) return;
        JSONArray a = roots();
        JSONArray out = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optLong("id") == id) continue;
            out.put(a.opt(i));
        }
        saveRoots(out);
    }

    public static int countIn(long id) {
        Integer cached = countCache.get(id);
        if (cached != null) return cached;
        JSONArray a = roots();
        int idx = indexOf(a, id);
        int n = idx < 0 ? 0 : tracksArr(a.optJSONObject(idx)).length();
        countCache.put(id, n);
        return n;
    }

    public static ArrayList<Track> tracks(long id) {
        ArrayList<Track> out = new ArrayList<>();
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return out;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            Track tr = new Track();
            tr.did = o.optLong("did");
            tr.mid = o.optInt("mid");
            tr.title = o.optString("t", "");
            tr.author = o.optString("a", "");
            out.add(tr);
        }
        return out;
    }

    public static boolean containsIn(long id, MessageObject mo) {
        if (mo == null) return false;
        long did = mo.getDialogId();
        int mid = mo.getId();
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return false;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o != null && o.optLong("did") == did && o.optInt("mid") == mid) {
                return true;
            }
        }
        return false;
    }

    public static boolean addTo(long id, MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        if (containsIn(id, mo)) return true;
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return false;
        try {
            long did = mo.getDialogId();
            int mid = mo.getId();
            String data = encode(mo.messageOwner);
            if (data == null) return false;
            JSONObject o = new JSONObject();
            o.put("did", did);
            o.put("mid", mid);
            String t = mo.getMusicTitle();
            String au = mo.getMusicAuthor();
            o.put("t", t == null ? "" : t);
            o.put("a", au == null ? "" : au);
            o.put("data", data);
            JSONObject pl = a.getJSONObject(idx);
            JSONArray t2 = tracksArr(pl);
            t2.put(o);
            pl.put("tracks", t2);
            saveRoots(a);
            liveCache.put(key(did, mid), mo);
            try {
                TLRPC.Document doc = mo.getDocument();
                if (doc != null) {
                    FileLoader.getInstance(UserConfig.selectedAccount).loadFile(doc, mo, 1, 0);
                }
            } catch (Throwable ignore) {
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static boolean removeFrom(long id, MessageObject mo) {
        if (mo == null) return false;
        return removeMatch(id, mo.getDialogId(), mo.getId());
    }

    private static boolean removeMatch(long id, long did, int mid) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return false;
        try {
            JSONObject pl = a.getJSONObject(idx);
            JSONArray t = tracksArr(pl);
            JSONArray out = new JSONArray();
            boolean removed = false;
            for (int i = 0; i < t.length(); i++) {
                JSONObject o = t.optJSONObject(i);
                if (!removed && o != null && o.optLong("did") == did && o.optInt("mid") == mid) {
                    removed = true;
                    continue;
                }
                out.put(t.opt(i));
            }
            pl.put("tracks", out);
            saveRoots(a);
            return removed;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void moveTrack(long id, int from, int to) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return;
        try {
            JSONObject pl = a.getJSONObject(idx);
            JSONArray t = tracksArr(pl);
            int n = t.length();
            if (from < 0 || from >= n || to < 0 || to >= n || from == to) return;
            java.util.ArrayList<Object> items = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) items.add(t.opt(i));
            Object moved = items.remove(from);
            items.add(to, moved);
            JSONArray out = new JSONArray();
            for (Object o : items) out.put(o);
            pl.put("tracks", out);
            saveRoots(a);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void removeTrackAt(long id, int index) {
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return;
        try {
            JSONObject pl = a.getJSONObject(idx);
            JSONArray t = tracksArr(pl);
            JSONArray out = new JSONArray();
            for (int i = 0; i < t.length(); i++) {
                if (i != index) out.put(t.opt(i));
            }
            pl.put("tracks", out);
            saveRoots(a);
        } catch (Throwable ignore) {
        }
    }

    public static boolean toggleIn(long id, MessageObject mo) {
        if (containsIn(id, mo)) {
            removeFrom(id, mo);
            return false;
        }
        return addTo(id, mo);
    }

    public static ArrayList<MessageObject> buildMessages(long id) {
        ArrayList<MessageObject> out = new ArrayList<>();
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return out;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            MessageObject live = liveCache.get(key(o.optLong("did"), o.optInt("mid")));
            if (live != null) {
                out.add(live);
                continue;
            }
            TLRPC.Message msg = decode(o.optString("data", ""));
            if (msg != null) {
                try {
                    MessageObject m = new MessageObject(UserConfig.selectedAccount, msg, false, false);
                    out.add(m);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
        return out;
    }

    private static long playingId;
    private static String playingName;

    public static void setPlaying(long id, String name) {
        playingId = id;
        playingName = name;
    }

    public static String playingName(MessageObject mo) {
        if (playingId == 0 || mo == null) return null;
        return containsIn(playingId, mo) ? playingName : null;
    }

    public static ArrayList<MessageObject> firstMessages(long id, int limit) {
        ArrayList<MessageObject> out = new ArrayList<>();
        JSONArray a = roots();
        int idx = indexOf(a, id);
        if (idx < 0) return out;
        JSONArray t = tracksArr(a.optJSONObject(idx));
        for (int i = 0; i < t.length() && out.size() < limit; i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            MessageObject live = liveCache.get(key(o.optLong("did"), o.optInt("mid")));
            if (live != null) {
                out.add(live);
                continue;
            }
            TLRPC.Message msg = decode(o.optString("data", ""));
            if (msg != null) {
                try {
                    out.add(new MessageObject(UserConfig.selectedAccount, msg, false, false));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
        return out;
    }

    public static boolean inAny(MessageObject mo) {
        if (mo == null) return false;
        for (Playlist p : playlists()) {
            if (containsIn(p.id, mo)) return true;
        }
        return false;
    }

    public static boolean contains(MessageObject mo) {
        return containsIn(DEFAULT_ID, mo);
    }

    public static boolean toggle(MessageObject mo) {
        return toggleIn(DEFAULT_ID, mo);
    }

    public static int count() {
        return countIn(DEFAULT_ID);
    }

    public static ArrayList<MessageObject> buildMessages() {
        return buildMessages(DEFAULT_ID);
    }

    private static String encode(TLRPC.Message msg) {
        try {
            NativeByteBuffer data = new NativeByteBuffer(msg.getObjectSize());
            msg.serializeToStream(data);
            int len = data.buffer.position();
            byte[] bytes = new byte[len];
            data.buffer.position(0);
            data.buffer.get(bytes, 0, len);
            data.reuse();
            return Base64.encodeToString(bytes, Base64.NO_WRAP);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static TLRPC.Message decode(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(s, Base64.NO_WRAP);
            NativeByteBuffer buffer = new NativeByteBuffer(bytes.length);
            buffer.writeBytes(bytes);
            buffer.position(0);
            int constructor = buffer.readInt32(false);
            TLRPC.Message msg = TLRPC.Message.TLdeserialize(buffer, constructor, false);
            buffer.reuse();
            return msg;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
