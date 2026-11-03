package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public final class PrizmaCloud {
    public static final String DEFAULT_HOST = "https://api.bestfork.online";
    public static final String DEFAULT_BOT = "LibreGram_bot";

    private static final long PROFILE_TTL = 5 * 60_000L;
    private static final long MAX_VIDEO_BANNER = 15L * 1024 * 1024;

    private PrizmaCloud() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_cloud", Context.MODE_PRIVATE);
    }

    private static final java.util.concurrent.ExecutorService api = java.util.concurrent.Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "prizmaCloudApi");
        t.setDaemon(true);
        return t;
    });
    private static final DispatchQueue queue = new DispatchQueue("prizmaCloud");

    public static String host() {
        String h = prefs().getString("host", DEFAULT_HOST);
        if (h == null) return "";
        h = h.trim();
        if (h.isEmpty()) return "";
        if (!h.startsWith("http")) h = "https://" + h;
        while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        return h;
    }

    public static String bot() {
        String b = prefs().getString("bot", DEFAULT_BOT);
        if (b == null) return "";
        b = b.trim();
        if (b.startsWith("@")) b = b.substring(1);
        return b;
    }

    public static void setServer(String host, String bot) {
        prefs().edit().putString("host", host == null ? "" : host.trim()).putString("bot", bot == null ? "" : bot.trim()).apply();
        clearState();
    }

    public static boolean configured() {
        return !host().isEmpty() && !bot().isEmpty();
    }

    public static boolean enabled() {
        return configured() && PrizmaSocial.cardEnabled();
    }

    private static void clearState() {
        AndroidUtilities.runOnUIThread(() -> {
            remotes.clear();
            fetchedAt.clear();
            missing.clear();
            loading.clear();
            version++;
        });
        SharedPreferences.Editor e = prefs().edit();
        for (String k : prefs().getAll().keySet()) {
            if (k.startsWith("token_") || k.startsWith("uploaded_") || k.startsWith("showcase_up_") || k.startsWith("pl_up_")) e.remove(k);
        }
        e.apply();
    }

    public static class Remote {
        public long id;
        public long since;
        public long views = -1;
        public String bannerUrl;
        public boolean bannerVideo;
        public int bannerVer;
        public String bannerPath;
        public int showcaseVer;
        public long clanId;
        public String clanTag, clanName;
        public int clanColor;
        public String playlistName;
        public int playlistCount, playlistVer;
        public ArrayList<String[]> ach;
        public int status = -1;
        public String statusText;
        public long statusUntil;
        public String anon;
    }

    private static final HashMap<Long, Remote> remotes = new HashMap<>();
    private static final HashMap<Long, Long> fetchedAt = new HashMap<>();
    private static final HashSet<Long> missing = new HashSet<>();
    private static final HashSet<Long> loading = new HashSet<>();
    private static final ArrayList<Runnable> listeners = new ArrayList<>();
    private static int version;

    public static int version() {
        return version;
    }

    public static void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    public static void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private static void changed() {
        version++;
        for (Runnable r : new ArrayList<>(listeners)) {
            try {
                r.run();
            } catch (Throwable ignore) {
            }
        }
    }

    public static Remote peek(long userId) {
        return remotes.get(userId);
    }

    public static Remote remote(int account, long userId) {
        if (!enabled() || userId <= 0) return null;
        Long at = fetchedAt.get(userId);
        if ((at == null || System.currentTimeMillis() - at > PROFILE_TTL) && !loading.contains(userId)) {
            fetch(account, userId);
        }
        return remotes.get(userId);
    }

    public static boolean isPending(long userId) {
        if (loading.contains(userId)) return true;
        Remote r = remotes.get(userId);
        return r != null && r.bannerUrl != null && r.bannerPath == null && !bannerFailed.contains(userId);
    }

    private static final HashSet<Long> bannerFailed = new HashSet<>();

    private static String tokenKey(int account) {
        return "token_" + UserConfig.getInstance(account).getClientUserId();
    }

    private static final HashMap<Integer, ArrayList<Utilities.Callback<String>>> authWaiters = new HashMap<>();

    private static void withToken(int account, Utilities.Callback<String> cb) {
        String t = prefs().getString(tokenKey(account), null);
        if (t != null) {
            cb.run(t);
            return;
        }
        ArrayList<Utilities.Callback<String>> waiting = authWaiters.get(account);
        if (waiting != null) {
            waiting.add(cb);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(cb);
        authWaiters.put(account, waiting);
        requestInitData(account, initData -> {
            if (initData == null) {
                finishAuth(account, null);
                return;
            }
            final String host = host();
            api.execute(() -> {
                String token = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("init_data", initData);
                    HashMap<String, String> h = new HashMap<>();
                    h.put("Content-Type", "application/json");
                    PrizmaNet.Response r = PrizmaNet.request("POST", host + "/v1/auth", h, body.toString().getBytes("UTF-8"), 15000);
                    if (r.code == 200 && r.body != null) {
                        token = new JSONObject(r.body).getString("token");
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                final String result = token;
                AndroidUtilities.runOnUIThread(() -> {
                    if (result != null) prefs().edit().putString(tokenKey(account), result).apply();
                    finishAuth(account, result);
                });
            });
        });
    }

    private static void finishAuth(int account, String token) {
        ArrayList<Utilities.Callback<String>> waiting = authWaiters.remove(account);
        if (waiting == null) return;
        for (Utilities.Callback<String> c : waiting) {
            try {
                c.run(token);
            } catch (Throwable ignore) {
            }
        }
    }

    private static void dropToken(int account) {
        prefs().edit().remove(tokenKey(account)).apply();
    }

    private static void requestInitData(int account, Utilities.Callback<String> cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLObject known = mc.getUserOrChat(bot());
        if (known instanceof TLRPC.User) {
            requestInitData(account, (TLRPC.User) known, cb);
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = bot();
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) res;
                mc.putUsers(peer.users, false);
                mc.putChats(peer.chats, false);
                for (TLRPC.User u : peer.users) {
                    if (u.bot && bot().equalsIgnoreCase(UserObject.getPublicUsername(u))) {
                        requestInitData(account, u, cb);
                        return;
                    }
                }
            }
            cb.run(null);
        }));
    }

    private static void requestInitData(int account, TLRPC.User bot, Utilities.Callback<String> cb) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.TL_messages_requestMainWebView req = new TLRPC.TL_messages_requestMainWebView();
        req.peer = mc.getInputPeer(bot);
        req.bot = mc.getInputUser(bot);
        req.platform = "android";
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            String data = res instanceof TLRPC.TL_webViewResultUrl ? initDataOf(((TLRPC.TL_webViewResultUrl) res).url) : null;
            if (data != null) {
                cb.run(data);
                return;
            }
            TLRPC.TL_messages_requestSimpleWebView simple = new TLRPC.TL_messages_requestSimpleWebView();
            simple.bot = mc.getInputUser(bot);
            simple.url = host() + "/app";
            simple.flags |= 8;
            simple.platform = "android";
            ConnectionsManager.getInstance(account).sendRequest(simple, (res2, err2) -> AndroidUtilities.runOnUIThread(() ->
                    cb.run(res2 instanceof TLRPC.TL_webViewResultUrl ? initDataOf(((TLRPC.TL_webViewResultUrl) res2).url) : null)));
        }));
    }

    private static String initDataOf(String url) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        String frag = hash >= 0 ? url.substring(hash + 1) : url;
        for (String part : frag.split("&")) {
            if (part.startsWith("tgWebAppData=")) {
                try {
                    return URLDecoder.decode(part.substring("tgWebAppData=".length()), "UTF-8");
                } catch (Throwable e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void fetch(int account, long userId) {
        loading.add(userId);
        withToken(account, token -> {
            if (token == null) {
                loading.remove(userId);
                fetchedAt.put(userId, System.currentTimeMillis());
                return;
            }
            final String host = host();
            api.execute(() -> {
                HashMap<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                PrizmaNet.Response r = PrizmaNet.request("GET", host + "/v1/profile/" + userId, h, null, 15000);
                Remote remote = null;
                boolean notLibre = r.code == 404;
                if (r.code == 200 && r.body != null) {
                    try {
                        JSONObject o = new JSONObject(r.body);
                        remote = new Remote();
                        remote.id = userId;
                        remote.since = o.optLong("since", 0);
                        remote.views = o.optLong("views", -1);
                        remote.showcaseVer = o.optInt("showcase", 0);
                        JSONObject clan = o.optJSONObject("clan");
                        if (clan != null) {
                            remote.clanId = clan.optLong("id");
                            remote.clanTag = clan.optString("tag", "");
                            remote.clanName = clan.optString("name", "");
                            remote.clanColor = clan.optInt("color", PrizmaSocial.SAGE);
                        }
                        JSONObject pl = o.optJSONObject("playlist");
                        if (pl != null) {
                            remote.playlistName = pl.optString("name", "Плейлист");
                            remote.playlistCount = pl.optInt("count", 0);
                            remote.playlistVer = pl.optInt("ver", 0);
                        }
                        org.json.JSONArray ach = o.optJSONArray("ach");
                        if (ach != null) {
                            remote.ach = new ArrayList<>();
                            for (int i = 0; i < ach.length(); i++) {
                                JSONObject a = ach.optJSONObject(i);
                                if (a != null && a.has("id")) remote.ach.add(new String[]{a.optString("id"), a.optString("desc", "")});
                            }
                        }
                        JSONObject st = o.optJSONObject("status");
                        if (st != null) {
                            remote.status = st.optInt("s", -1);
                            remote.statusText = st.optString("text", "");
                            remote.statusUntil = st.optLong("until", 0);
                        }
                        remote.anon = o.optString("anon", "");
                        if (remote.anon.isEmpty()) remote.anon = null;
                        final JSONObject profileJson = o;
                        AndroidUtilities.runOnUIThread(() -> PrizmaPresence.fromProfile(account, userId, profileJson));
                        JSONObject b = o.optJSONObject("banner");
                        if (b != null) {
                            remote.bannerUrl = b.getString("url");
                            remote.bannerVideo = b.optBoolean("video", false);
                            remote.bannerVer = b.optInt("ver", 0);
                        }
                    } catch (Throwable e) {
                        remote = null;
                    }
                }
                final Remote result = remote;
                final int code = r.code;
                AndroidUtilities.runOnUIThread(() -> {
                    loading.remove(userId);
                    fetchedAt.put(userId, System.currentTimeMillis());
                    if (code == 401) {
                        dropToken(account);
                        fetchedAt.remove(userId);
                        return;
                    }
                    if (result != null) {
                        Remote old = remotes.get(userId);
                        if (old != null && old.bannerUrl != null && old.bannerUrl.equals(result.bannerUrl)) {
                            result.bannerPath = old.bannerPath;
                        }
                        remotes.put(userId, result);
                        missing.remove(userId);
                        checkShowcase(account, userId, result.showcaseVer);
                        if (result.bannerUrl != null && result.bannerPath == null) downloadBanner(result);
                    } else if (notLibre) {
                        remotes.remove(userId);
                        missing.add(userId);
                    }
                    changed();
                });
            });
        });
    }

    private static void downloadBanner(Remote r) {
        final long userId = r.id;
        final String url = r.bannerUrl;
        File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "libre_banners");
        dir.mkdirs();
        final File dst = new File(dir, userId + "_" + r.bannerVer + (r.bannerVideo ? ".mp4" : ".img"));
        if (dst.exists()) {
            r.bannerPath = dst.getAbsolutePath();
            return;
        }
        bannerFailed.remove(userId);
        queue.postRunnable(() -> {
            File[] old = dir.listFiles((d, name) -> name.startsWith(userId + "_") && !name.equals(dst.getName()));
            if (old != null) for (File f : old) f.delete();
            boolean ok = PrizmaNet.download(url, dst, MAX_VIDEO_BANNER + 1024 * 1024, 30000);
            AndroidUtilities.runOnUIThread(() -> {
                Remote now = remotes.get(userId);
                if (ok && now != null && url.equals(now.bannerUrl)) {
                    now.bannerPath = dst.getAbsolutePath();
                } else if (!ok) {
                    bannerFailed.add(userId);
                }
                changed();
            });
        });
    }

    private static final HashMap<Long, ArrayList<org.telegram.ui.Components.PrizmaGifts.RemoteGift>> showcases = new HashMap<>();
    private static final HashMap<Long, Integer> showcaseVers = new HashMap<>();
    private static final HashSet<Long> showcaseLoading = new HashSet<>();

    public static ArrayList<org.telegram.ui.Components.PrizmaGifts.RemoteGift> showcaseOf(long userId) {
        return showcases.get(userId);
    }

    private static void checkShowcase(int account, long userId, int ver) {
        if (userId == UserConfig.getInstance(account).getClientUserId()) return;
        Integer known = showcaseVers.get(userId);
        if (known != null && known == ver) return;
        if (ver == 0) {
            showcaseVers.put(userId, 0);
            if (showcases.remove(userId) != null) invalidateGifts(account, userId);
            return;
        }
        if (!showcaseLoading.add(userId)) return;
        call(account, "GET", "/v1/showcase/" + userId, null, r -> {
            showcaseLoading.remove(userId);
            if (r.code != 200 || r.body == null) return;
            try {
                JSONObject o = new JSONObject(r.body);
                org.json.JSONArray arr = o.optJSONArray("gifts");
                ArrayList<org.telegram.ui.Components.PrizmaGifts.RemoteGift> list = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject g = arr.getJSONObject(i);
                        org.telegram.ui.Components.PrizmaGifts.RemoteGift rg = new org.telegram.ui.Components.PrizmaGifts.RemoteGift();
                        rg.giftId = g.optLong("giftId");
                        rg.attr = g.optString("attr", "");
                        rg.num = g.optString("num", "");
                        rg.title = g.optString("title", "");
                        rg.style = g.optInt("style", 0);
                        rg.crafted = g.optBoolean("crafted", false);
                        rg.pinned = g.optInt("pinned", -1);
                        if (rg.giftId != 0) list.add(rg);
                    }
                }
                showcases.put(userId, list);
                showcaseVers.put(userId, o.optInt("ver", ver));
                invalidateGifts(account, userId);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static void invalidateGifts(int account, long userId) {
        try {
            org.telegram.ui.Stars.StarsController.GiftsList list = org.telegram.ui.Stars.StarsController.getInstance(account).getProfileGiftsList(userId, false);
            if (list != null) list.invalidate(true);
        } catch (Throwable ignore) {
        }
    }

    private static final Runnable[] showcaseSync = new Runnable[1];

    public static void syncShowcaseSoon(int account) {
        if (!enabled()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (showcaseSync[0] != null) AndroidUtilities.cancelRunOnUIThread(showcaseSync[0]);
            showcaseSync[0] = () -> syncShowcase(account);
            AndroidUtilities.runOnUIThread(showcaseSync[0], 4000);
        });
    }

    private static void syncShowcase(int account) {
        if (!enabled()) return;
        final String key = "showcase_up_" + UserConfig.getInstance(account).getClientUserId();
        final String body;
        try {
            JSONObject o = new JSONObject();
            o.put("gifts", org.telegram.ui.Components.PrizmaGifts.showcaseForCloud());
            body = o.toString();
        } catch (Throwable e) {
            return;
        }
        final String hash = Utilities.MD5(body);
        if (hash == null || hash.equals(prefs().getString(key, ""))) return;
        byte[] data;
        try {
            data = body.getBytes("UTF-8");
        } catch (Throwable e) {
            return;
        }
        upload(account, "PUT", "/v1/showcase", data, "application/json", r -> {
            if (r.code != 200) return;
            prefs().edit().putString(key, hash).apply();
            try {
                org.json.JSONArray fixes = r.body != null ? new JSONObject(r.body).optJSONArray("fixes") : null;
                if (fixes == null || fixes.length() == 0) return;
                for (int i = 0; i < fixes.length(); i++) {
                    JSONObject f = fixes.getJSONObject(i);
                    if (f.has("inst") && !f.isNull("inst")) PrizmaConfig.setNftNum(f.getLong("inst"), f.getString("num"));
                }
                invalidateGifts(account, UserConfig.getInstance(account).getClientUserId());
                syncShowcaseSoon(account);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private static final Runnable[] achSync = new Runnable[1];

    public static void syncAchievementsSoon(int account) {
        if (!enabled()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (achSync[0] != null) AndroidUtilities.cancelRunOnUIThread(achSync[0]);
            achSync[0] = () -> syncAchievements(account);
            AndroidUtilities.runOnUIThread(achSync[0], 3000);
        });
    }

    private static void syncAchievements(int account) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        if (self == 0) return;
        final JSONObject body = new JSONObject();
        try {
            org.json.JSONArray list = new org.json.JSONArray();
            for (PrizmaSocial.Achievement a : PrizmaSocial.achievements(account, self, false)) {
                if (!PrizmaSocial.isPhoneAchievement(a.id)) continue;
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("desc", a.desc);
                list.put(o);
            }
            body.put("list", list);
            body.put("since", PrizmaSocial.librSince() / 1000L);
        } catch (Throwable e) {
            return;
        }
        final String key = "ach_up_" + self;
        final String hash = Utilities.MD5(body.toString());
        if (hash == null || hash.equals(prefs().getString(key, ""))) return;
        call(account, "PUT", "/v1/achievements", body, r -> {
            if (r.code == 200) {
                prefs().edit().putString(key, hash).apply();
                forget(self);
            }
        });
    }

    public static void putStatus(int account, int status, String text, long until, Utilities.Callback<String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("s", status);
            body.put("text", text == null ? "" : text);
            body.put("until", until);
        } catch (Throwable ignore) {
        }
        call(account, "PUT", "/v1/status", body, r -> {
            if (r.code == 200) forget(UserConfig.getInstance(account).getClientUserId());
            if (done != null) done.run(r.code == 200 ? null : r.code == 404 ? "Сервер Libre ещё не обновлён — статусы появятся после обновления сервера" : PrizmaWall.error(r.code, null));
        });
    }

    private static final Runnable[] playlistSync = new Runnable[1];

    public static void syncPlaylistSoon(int account) {
        if (!enabled()) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (playlistSync[0] != null) AndroidUtilities.cancelRunOnUIThread(playlistSync[0]);
            playlistSync[0] = () -> syncPlaylist(account);
            AndroidUtilities.runOnUIThread(playlistSync[0], 3000);
        });
    }

    private static void syncPlaylist(int account) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        final String key = "pl_up_" + self;
        final long id = PrizmaPlaylist.publicId();
        PrizmaPlaylist.Playlist pl = id != 0 ? PrizmaPlaylist.get(id) : null;
        if (pl == null) {
            if (prefs().getString(key, "").isEmpty()) return;
            call(account, "DELETE", "/v1/playlist", null, r -> {
                if (r.code == 200) {
                    prefs().edit().remove(key).apply();
                    forget(self);
                }
            });
            return;
        }
        final String body;
        try {
            JSONObject o = new JSONObject();
            o.put("name", pl.name);
            o.put("tracks", PrizmaPlaylist.exportPublic(id));
            body = o.toString();
        } catch (Throwable e) {
            return;
        }
        final String hash = Utilities.MD5(body);
        if (hash == null || hash.equals(prefs().getString(key, ""))) return;
        byte[] data;
        try {
            data = body.getBytes("UTF-8");
        } catch (Throwable e) {
            return;
        }
        upload(account, "PUT", "/v1/playlist", data, "application/json", r -> {
            if (r.code == 200) {
                prefs().edit().putString(key, hash).apply();
                forget(self);
                try {
                    org.json.JSONArray need = new JSONObject(r.body).optJSONArray("need");
                    if (need != null && need.length() > 0) relayTracks(account, id, need);
                } catch (Throwable ignore) {
                }
            } else if (r.code == 413) {
                Toast.makeText(ApplicationLoader.applicationContext, "Плейлист слишком большой для профиля — оставьте до 500 треков", Toast.LENGTH_LONG).show();
            }
        });
    }

    private static void relayTracks(int account, long playlistId, org.json.JSONArray need) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLObject known = mc.getUserOrChat(bot());
        if (known instanceof TLRPC.User) {
            relayTracks(account, (TLRPC.User) known, playlistId, need);
            return;
        }
        mc.getUserNameResolver().resolve(bot(), peerId -> {
            TLRPC.User botUser = peerId != null && peerId > 0 ? mc.getUser(peerId) : null;
            if (botUser != null) relayTracks(account, botUser, playlistId, need);
        });
    }

    private static void relayTracks(int account, TLRPC.User botUser, long playlistId, org.json.JSONArray need) {
        int delay = 0;
        for (int i = 0; i < need.length(); i++) {
            final long[] src = PrizmaPlaylist.sourceMessage(playlistId, need.optLong(i));
            if (src == null || src[0] == 0 || src[1] == 0) continue;
            AndroidUtilities.runOnUIThread(() -> forwardToBot(account, botUser, src[0], (int) src[1]), delay);
            delay += 800;
        }
    }

    private static void forwardToBot(int account, TLRPC.User botUser, long dialogId, int messageId) {
        final MessagesController mc = MessagesController.getInstance(account);
        TLRPC.InputPeer from = mc.getInputPeer(dialogId);
        if (from == null || from instanceof TLRPC.TL_inputPeerEmpty) return;
        TLRPC.TL_messages_forwardMessages req = new TLRPC.TL_messages_forwardMessages();
        req.from_peer = from;
        req.to_peer = mc.getInputPeer(botUser);
        req.id.add(messageId);
        req.random_id.add(Utilities.random.nextLong());
        req.silent = true;
        req.drop_author = true;
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            if (!(res instanceof TLRPC.Updates)) return;
            final TLRPC.Updates updates = (TLRPC.Updates) res;
            final ArrayList<Integer> sent = new ArrayList<>();
            for (TLRPC.Update u : updates.updates) {
                if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updateMessageID) sent.add(((org.telegram.tgnet.tl.TL_update.TL_updateMessageID) u).id);
            }
            mc.processUpdates(updates, false);
            if (!sent.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> mc.deleteMessages(sent, null, null, botUser.id, 0, true, 0), 2500);
            }
        });
    }

    private static final LruCache<String, Bitmap> bitmaps = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final HashSet<String> decoding = new HashSet<>();

    public static Bitmap bannerBitmap(String path) {
        if (path == null) return null;
        Bitmap b = bitmaps.get(path);
        if (b != null && !b.isRecycled()) return b;
        if (decoding.add(path)) {
            Utilities.globalQueue.postRunnable(() -> {
                Bitmap result = null;
                try {
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, o);
                    int target = Math.max(720, Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y));
                    int sample = 1;
                    while (o.outWidth / (sample * 2) >= target) sample *= 2;
                    o = new BitmapFactory.Options();
                    o.inSampleSize = sample;
                    result = BitmapFactory.decodeFile(path, o);
                } catch (Throwable ignore) {
                }
                final Bitmap decoded = result;
                AndroidUtilities.runOnUIThread(() -> {
                    decoding.remove(path);
                    if (decoded != null) bitmaps.put(path, decoded);
                });
            });
        }
        return null;
    }

    public static boolean bitmapPending(String path) {
        return path != null && decoding.contains(path);
    }

    private static final HashSet<Long> viewedToday = new HashSet<>();
    private static long viewedDay;

    public static void markView(int account, long userId) {
        if (!enabled() || PrizmaSocial.noViewMark() || userId <= 0) return;
        if (userId == UserConfig.getInstance(account).getClientUserId()) return;
        long day = System.currentTimeMillis() / 86_400_000L;
        if (day != viewedDay) {
            viewedDay = day;
            viewedToday.clear();
        }
        if (!viewedToday.add(userId)) return;
        withToken(account, token -> {
            if (token == null) return;
            final String host = host();
            api.execute(() -> {
                HashMap<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                PrizmaNet.Response r = PrizmaNet.request("POST", host + "/v1/view/" + userId, h, new byte[0], 15000);
                if (r.code == 401) AndroidUtilities.runOnUIThread(() -> dropToken(account));
            });
        });
    }

    public static void syncOwnBanner(int account) {
        if (!enabled()) return;
        syncShowcaseSoon(account);
        syncAchievementsSoon(account);
        final String uploadedKey = "uploaded_" + UserConfig.getInstance(account).getClientUserId();
        final String path = PrizmaConfig.bannerPath();
        final boolean video = PrizmaConfig.bannerIsVideo();
        final String uploaded = prefs().getString(uploadedKey, "");
        final String want = path == null ? "" : path;
        if (want.equals(uploaded)) return;
        withToken(account, token -> {
            if (token == null) return;
            final String host = host();
            queue.postRunnable(() -> {
                HashMap<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                PrizmaNet.Response r;
                if (want.isEmpty()) {
                    r = PrizmaNet.request("DELETE", host + "/v1/banner", h, null, 15000);
                } else {
                    byte[] data = video ? readVideo(want) : encodeImage(want);
                    if (data == null) {
                        if (video) AndroidUtilities.runOnUIThread(() -> Toast.makeText(ApplicationLoader.applicationContext,
                                "Видео-баннер больше 15 МБ — другие пользователи Libre его не увидят", Toast.LENGTH_LONG).show());
                        return;
                    }
                    h.put("Content-Type", video ? "video/mp4" : "image/jpeg");
                    r = PrizmaNet.request("PUT", host + "/v1/banner", h, data, 60000);
                }
                final int code = r.code;
                AndroidUtilities.runOnUIThread(() -> {
                    if (code == 200) {
                        prefs().edit().putString(uploadedKey, want).apply();
                        fetchedAt.remove(UserConfig.getInstance(account).getClientUserId());
                    } else if (code == 401) {
                        dropToken(account);
                    }
                });
            });
        });
    }

    private static byte[] readVideo(String path) {
        File f = new File(path);
        if (!f.exists() || f.length() > MAX_VIDEO_BANNER) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0, r;
            while (off < data.length && (r = in.read(data, off, data.length - off)) > 0) off += r;
            return data;
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] encodeImage(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1440) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return null;
            float scale = Math.min(1f, 1440f / b.getWidth());
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
                if (s != b) b.recycle();
                b = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 86, out);
            b.recycle();
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    public static void call(int account, String method, String path, JSONObject json, Utilities.Callback<PrizmaNet.Response> done) {
        call(account, method, path, json, null, null, done, true);
    }

    public static void upload(int account, String method, String path, byte[] data, String contentType, Utilities.Callback<PrizmaNet.Response> done) {
        call(account, method, path, null, data, contentType, done, true);
    }

    static void notifyChanged() {
        changed();
    }

    public static void forget(long userId) {
        fetchedAt.remove(userId);
    }

    private static void call(int account, String method, String path, JSONObject json, byte[] raw, String rawType, Utilities.Callback<PrizmaNet.Response> done, boolean retry) {
        if (!configured()) {
            done.run(new PrizmaNet.Response(-1, null));
            return;
        }
        withToken(account, token -> {
            if (token == null) {
                done.run(new PrizmaNet.Response(-1, null));
                return;
            }
            final String host = host();
            final Runnable send = () -> {
                HashMap<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                byte[] body = null;
                if (raw != null) {
                    h.put("Content-Type", rawType);
                    body = raw;
                } else if (json != null) {
                    h.put("Content-Type", "application/json");
                    try {
                        body = json.toString().getBytes("UTF-8");
                    } catch (Throwable ignore) {
                    }
                } else if ("POST".equals(method)) {
                    body = new byte[0];
                }
                PrizmaNet.Response r = PrizmaNet.request(method, host + path, h, body, raw != null ? 60000 : 20000);
                AndroidUtilities.runOnUIThread(() -> {
                    if (r.code == 401 && retry) {
                        dropToken(account);
                        call(account, method, path, json, raw, rawType, done, false);
                    } else {
                        done.run(r);
                    }
                });
            };
            if (raw != null || path.startsWith("/v1/backup")) {
                queue.postRunnable(send);
            } else {
                api.execute(send);
            }
        });
    }

    public static void deleteMe(int account, Runnable done) {
        if (!configured()) {
            if (done != null) done.run();
            return;
        }
        withToken(account, token -> {
            if (token == null) {
                if (done != null) done.run();
                return;
            }
            final String host = host();
            api.execute(() -> {
                HashMap<String, String> h = new HashMap<>();
                h.put("Authorization", "Bearer " + token);
                PrizmaNet.request("DELETE", host + "/v1/me", h, null, 15000);
                AndroidUtilities.runOnUIThread(() -> {
                    dropToken(account);
                    final long self = UserConfig.getInstance(account).getClientUserId();
                    remotes.remove(self);
                    fetchedAt.remove(self);
                    PrizmaClans.forgetMine(account);
                    prefs().edit().remove("uploaded_" + UserConfig.getInstance(account).getClientUserId())
                            .remove("showcase_up_" + UserConfig.getInstance(account).getClientUserId())
                            .remove("pl_up_" + UserConfig.getInstance(account).getClientUserId()).apply();
                    if (done != null) done.run();
                });
            });
        });
    }
}
