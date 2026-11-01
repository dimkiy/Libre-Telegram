package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Prizma: profile walls on Libre Cloud — what one person writes, everyone with Libre sees.
 *
 * Screens read the cached {@link Wall}s and get told through {@link PrizmaCloud}'s listeners when
 * something arrives. Writes are optimistic where it feels better (likes) and confirmed by the
 * server where it matters (posts, deletions).
 */
public final class PrizmaWall {

    private PrizmaWall() {}

    private static final long WALL_TTL = 90_000L;
    private static final DispatchQueue io = new DispatchQueue("prizmaWall");

    public static class Post {
        public long id;
        public long ownerId;
        public long authorId;
        public String name = "";
        public String text = "";
        public String photoUrl;
        public String localPhoto;   // our own photo while it's being sent
        public long date;
        public int likes;
        public int comments;
        public boolean liked;
        public boolean sending;     // shown right away, the server hasn't confirmed it yet
        public final ArrayList<Comment> last = new ArrayList<>();
    }

    public static class Comment {
        public long id;
        public long authorId;
        public String name = "";
        public String text = "";
        public long date;
        public boolean sending;
    }

    public static class Wall {
        public final ArrayList<Post> posts = new ArrayList<>();
        public int total;
        public int who;
        public boolean hidden;
        public boolean canPost = true;
        public boolean canComment = true;
        public final HashSet<Long> blocked = new HashSet<>();
        public boolean loaded;
        public boolean notLibre;    // the owner doesn't use Libre (yet)
        public boolean failed;      // last load didn't reach the server
        public boolean hasMore;
        public boolean moderator;   // may delete anything here (my wall, or a clan wall I run)
        long fetchedAt;
        boolean loading;
    }

    private static final HashMap<Long, Wall> walls = new HashMap<>();

    /** What we know, without asking. */
    public static Wall peek(long ownerId) {
        return walls.get(ownerId);
    }

    /** What we know; refreshes in the background when old. UI thread. */
    public static Wall get(int account, long ownerId) {
        Wall w = walls.get(ownerId);
        if (w == null || !w.loading && System.currentTimeMillis() - w.fetchedAt > WALL_TTL) {
            load(account, ownerId, false, null);
            w = walls.get(ownerId);
        }
        return w;
    }

    /** Human words for a failed request. */
    public static String error(int code, String ownerName) {
        switch (code) {
            case -1: return !PrizmaCloud.configured() ? "Сервер Libre не подключён"
                    : PrizmaNet.proxyBlocks() ? "Включён MTProto-прокси — через него сервер Libre недоступен. Выключите его или используйте SOCKS5"
                    : "Нет связи с сервером Libre";
            case 403: return "Владелец стены ограничил, кто может здесь писать";
            case 404: return ownerName != null ? ownerName + " пока не пользуется Libre" : "Запись уже удалена";
            case 409: return "Здесь уже слишком много комментариев";
            case 413: return "Фото слишком большое";
            case 429: return "Слишком часто — подождите минуту";
            default: return "Сервер Libre не ответил (" + code + ")";
        }
    }

    private static boolean mutualWith(int account, long ownerId) {
        TLRPC.User u = MessagesController.getInstance(account).getUser(ownerId);
        return u != null && u.mutual_contact;
    }

    private static String myName(int account) {
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        return me != null ? UserObject.getUserName(me) : "";
    }

    // ===================== reading =====================

    public static void load(int account, long ownerId, boolean more, Runnable done) {
        Wall w = walls.get(ownerId);
        if (w == null) {
            w = new Wall();
            walls.put(ownerId, w);
        }
        if (w.loading) {
            if (done != null) done.run();
            return;
        }
        final Wall wall = w;
        wall.loading = true;
        long before = 0;
        if (more) {
            for (Post p : wall.posts) {
                if (!p.sending && (before == 0 || p.id < before)) before = p.id;
            }
        }
        String path = "/v1/wall/" + ownerId + "?mutual=" + (mutualWith(account, ownerId) ? 1 : 0) + (before > 0 ? "&before=" + before : "");
        PrizmaCloud.call(account, "GET", path, null, r -> {
            wall.loading = false;
            wall.fetchedAt = System.currentTimeMillis();
            wall.failed = false;
            if (r.code == 200 && r.body != null) {
                try {
                    JSONObject o = new JSONObject(r.body);
                    wall.loaded = true;
                    wall.notLibre = false;
                    wall.total = o.optInt("total", 0);
                    wall.who = o.optInt("who", 0);
                    wall.hidden = o.optBoolean("hidden", false);
                    wall.canPost = o.optBoolean("canPost", true);
                    wall.canComment = o.optBoolean("canComment", true);
                    wall.moderator = o.optBoolean("moderator", false);
                    JSONArray b = o.optJSONArray("blocked");
                    if (b != null) {
                        wall.blocked.clear();
                        for (int i = 0; i < b.length(); i++) wall.blocked.add(b.getLong(i));
                    }
                    ArrayList<Post> got = new ArrayList<>();
                    JSONArray arr = o.optJSONArray("posts");
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) got.add(post(arr.getJSONObject(i)));
                    }
                    wall.hasMore = got.size() >= 20;
                    if (!more) {
                        // keep posts that are still on their way up
                        ArrayList<Post> sending = new ArrayList<>();
                        for (Post p : wall.posts) if (p.sending) sending.add(p);
                        wall.posts.clear();
                        wall.posts.addAll(sending);
                    }
                    wall.posts.addAll(got);
                    if (ownerId == UserConfig.getInstance(account).getClientUserId()) {
                        PrizmaSocial.rememberWallSettings(wall.who, wall.hidden);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    wall.failed = true;
                }
            } else if (r.code == 404) {
                wall.loaded = true;
                wall.notLibre = true;
                wall.posts.clear();
                wall.total = 0;
            } else {
                wall.failed = true;
            }
            PrizmaCloud.notifyChanged();
            if (done != null) done.run();
        });
    }

    /** One post with all its comments; {@code failed} gets the HTTP code. */
    public static void loadPost(int account, long postId, Utilities.Callback2<Post, ArrayList<Comment>> done, Utilities.Callback<Integer> failed) {
        PrizmaCloud.call(account, "GET", "/v1/post/" + postId, null, r -> {
            if (r.code != 200 || r.body == null) {
                failed.run(r.code);
                return;
            }
            try {
                JSONObject o = new JSONObject(r.body);
                Post p = post(o.getJSONObject("post"));
                ArrayList<Comment> list = new ArrayList<>();
                JSONArray arr = o.optJSONArray("comments");
                if (arr != null) for (int i = 0; i < arr.length(); i++) list.add(comment(arr.getJSONObject(i)));
                commentAllowed.put(postId, o.optBoolean("canComment", true));
                done.run(p, list);
            } catch (Throwable e) {
                failed.run(0);
            }
        });
    }

    private static final HashMap<Long, Boolean> commentAllowed = new HashMap<>();

    public static boolean canComment(long postId) {
        Boolean b = commentAllowed.get(postId);
        return b == null || b;
    }

    private static Post post(JSONObject o) throws Exception {
        Post p = new Post();
        p.id = o.getLong("id");
        p.ownerId = o.getLong("owner");
        p.authorId = o.getLong("author");
        p.name = o.optString("name", "");
        p.text = o.optString("text", "");
        p.photoUrl = o.isNull("photo") ? null : o.optString("photo", null);
        p.date = o.optLong("date", 0);
        p.likes = o.optInt("likes", 0);
        p.comments = o.optInt("comments", 0);
        p.liked = o.optBoolean("liked", false);
        JSONArray last = o.optJSONArray("last");
        if (last != null) for (int i = 0; i < last.length(); i++) p.last.add(comment(last.getJSONObject(i)));
        return p;
    }

    private static Comment comment(JSONObject o) throws Exception {
        Comment c = new Comment();
        c.id = o.getLong("id");
        c.authorId = o.getLong("author");
        c.name = o.optString("name", "");
        c.text = o.optString("text", "");
        c.date = o.optLong("date", 0);
        return c;
    }

    // ===================== writing =====================

    /**
     * Puts a post on the wall at once (marked as sending) and sends it. {@code done} gets the
     * server's copy, or null plus an error text.
     */
    public static Post addPost(int account, long ownerId, String text, String photoPath, Utilities.Callback2<Post, String> done) {
        final Post draft = new Post();
        draft.id = -System.currentTimeMillis();
        draft.ownerId = ownerId;
        draft.authorId = UserConfig.getInstance(account).getClientUserId();
        draft.name = myName(account);
        draft.text = text == null ? "" : text;
        draft.localPhoto = photoPath;
        draft.date = System.currentTimeMillis() / 1000;
        draft.sending = true;
        Wall wall = walls.get(ownerId);
        if (wall == null) {
            wall = new Wall();
            walls.put(ownerId, wall);
        }
        wall.posts.add(0, draft);
        final Wall w = wall;
        final TLRPC.User owner = MessagesController.getInstance(account).getUser(ownerId);
        final String ownerName = owner != null ? UserObject.getFirstName(owner) : null;

        Utilities.Callback<String> publish = photoName -> {
            JSONObject body = new JSONObject();
            try {
                body.put("text", draft.text);
                body.put("name", draft.name);
                body.put("mutual", mutualWith(account, ownerId));
                if (photoName != null) body.put("photo", photoName);
            } catch (Throwable ignore) {
            }
            PrizmaCloud.call(account, "POST", "/v1/wall/" + ownerId, body, r -> {
                if (r.code == 200 && r.body != null) {
                    try {
                        // the draft becomes the real post — the screen keeps the same card
                        Post p = post(new JSONObject(r.body));
                        draft.id = p.id;
                        draft.name = p.name;
                        draft.text = p.text;
                        draft.photoUrl = p.photoUrl;
                        draft.date = p.date;
                        draft.sending = false;
                        if (photoPath != null && p.photoUrl != null) rememberPhoto(p.photoUrl, photoPath); // no need to download what we just sent
                        w.total++;
                        PrizmaSocial.notePostWritten(account);
                        PrizmaCloud.notifyChanged();
                        done.run(draft, null);
                        return;
                    } catch (Throwable ignore) {
                    }
                }
                w.posts.remove(draft);
                PrizmaCloud.notifyChanged();
                done.run(null, error(r.code, ownerName));
            });
        };
        if (photoPath == null) {
            publish.run(null);
        } else {
            io.postRunnable(() -> {
                byte[] jpeg = readJpeg(photoPath);
                AndroidUtilities.runOnUIThread(() -> {
                    if (jpeg == null) {
                        w.posts.remove(draft);
                        done.run(null, "Не удалось прочитать фото");
                        return;
                    }
                    PrizmaCloud.upload(account, "PUT", "/v1/wall-photo", jpeg, "image/jpeg", r -> {
                        String name = null;
                        try {
                            if (r.code == 200 && r.body != null) name = new JSONObject(r.body).getString("photo");
                        } catch (Throwable ignore) {
                        }
                        if (name == null) {
                            w.posts.remove(draft);
                            PrizmaCloud.notifyChanged();
                            done.run(null, error(r.code, ownerName));
                            return;
                        }
                        publish.run(name);
                    });
                });
            });
        }
        return draft;
    }

    public static void deletePost(int account, Post p, Utilities.Callback<String> done) {
        PrizmaCloud.call(account, "DELETE", "/v1/post/" + p.id, null, r -> {
            if (r.code == 200) {
                Wall w = walls.get(p.ownerId);
                if (w != null) {
                    for (int i = 0; i < w.posts.size(); i++) {
                        if (w.posts.get(i).id == p.id) {
                            w.posts.remove(i);
                            w.total = Math.max(0, w.total - 1);
                            break;
                        }
                    }
                }
                PrizmaCloud.notifyChanged();
                done.run(null);
            } else {
                done.run(error(r.code, null));
            }
        });
    }

    /** Flips the like at once; the server's answer settles the count. */
    public static void toggleLike(int account, Post p, Runnable changed) {
        final boolean was = p.liked;
        p.liked = !was;
        p.likes = Math.max(0, p.likes + (p.liked ? 1 : -1));
        syncCopies(p);
        PrizmaCloud.call(account, "POST", "/v1/post/" + p.id + "/like", null, r -> {
            try {
                if (r.code == 200 && r.body != null) {
                    JSONObject o = new JSONObject(r.body);
                    p.likes = o.getInt("likes");
                    p.liked = o.getBoolean("liked");
                } else {
                    p.liked = was;
                    p.likes = Math.max(0, p.likes + (was ? 1 : -1));
                }
            } catch (Throwable ignore) {
            }
            syncCopies(p);
            if (changed != null) changed.run();
        });
    }

    /** The post screen and the wall hold separate copies — keep the numbers in step. */
    public static void syncCopies(Post p) {
        Wall w = walls.get(p.ownerId);
        if (w == null) return;
        for (Post q : w.posts) {
            if (q != p && q.id == p.id) {
                q.liked = p.liked;
                q.likes = p.likes;
                q.comments = p.comments;
                q.last.clear();
                int from = Math.max(0, p.last.size() - 2);
                q.last.addAll(p.last.subList(from, p.last.size()));
            }
        }
    }

    public static void addComment(int account, Post p, String text, Utilities.Callback2<Comment, String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
            body.put("name", myName(account));
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/post/" + p.id + "/comments", body, r -> {
            if (r.code == 200 && r.body != null) {
                try {
                    Comment c = comment(new JSONObject(r.body));
                    p.comments++;
                    p.last.add(c);
                    syncCopies(p);
                    PrizmaCloud.notifyChanged();
                    done.run(c, null);
                    return;
                } catch (Throwable ignore) {
                }
            }
            done.run(null, error(r.code, null));
        });
    }

    public static void deleteComment(int account, Post p, Comment c, Utilities.Callback<String> done) {
        PrizmaCloud.call(account, "DELETE", "/v1/comment/" + c.id, null, r -> {
            if (r.code == 200) {
                p.comments = Math.max(0, p.comments - 1);
                for (int i = p.last.size() - 1; i >= 0; i--) {
                    if (p.last.get(i).id == c.id) p.last.remove(i);
                }
                syncCopies(p);
                PrizmaCloud.notifyChanged();
                done.run(null);
            } else {
                done.run(error(r.code, null));
            }
        });
    }

    public static void report(int account, Post p) {
        PrizmaCloud.call(account, "POST", "/v1/post/" + p.id + "/report", null, r -> {});
    }

    public static void saveSettings(int account, int who, boolean hidden, Utilities.Callback<String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("who", who);
            body.put("hidden", hidden);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "PUT", "/v1/wall-settings", body, r -> {
            if (r.code == 200) {
                PrizmaSocial.rememberWallSettings(who, hidden);
                Wall w = walls.get(UserConfig.getInstance(account).getClientUserId());
                if (w != null) {
                    w.who = who;
                    w.hidden = hidden;
                }
                PrizmaCloud.notifyChanged();
                done.run(null);
            } else {
                done.run(error(r.code, null));
            }
        });
    }

    public static void setBlocked(int account, long userId, boolean block, Utilities.Callback<String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("user", userId);
            body.put("block", block);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/wall-block", body, r -> {
            if (r.code == 200) {
                long me = UserConfig.getInstance(account).getClientUserId();
                Wall w = walls.get(me);
                if (w != null) {
                    if (block) {
                        w.blocked.add(userId);
                        for (int i = w.posts.size() - 1; i >= 0; i--) {
                            if (w.posts.get(i).authorId == userId) w.posts.remove(i);
                        }
                    } else {
                        w.blocked.remove(userId);
                    }
                    w.fetchedAt = 0; // comments under my posts changed too
                }
                PrizmaCloud.notifyChanged();
                done.run(null);
            } else {
                done.run(error(r.code, null));
            }
        });
    }

    /** «Удалить мои данные» — forget everything cached. */
    public static void clear() {
        walls.clear();
        commentAllowed.clear();
    }

    // ===================== photos =====================

    private static final HashMap<String, String> photoFiles = new HashMap<>();
    private static final HashSet<String> photoLoading = new HashSet<>();

    private static void rememberPhoto(String url, String path) {
        photoFiles.put(url, path);
    }

    private static File photoDir() {
        File d = new File(ApplicationLoader.applicationContext.getCacheDir(), "libre_wall");
        d.mkdirs();
        return d;
    }

    /** A local file for a post's photo, or null while it downloads ({@code ready} runs when it's there). */
    public static String photoPath(Post p, Runnable ready) {
        if (p.localPhoto != null && new File(p.localPhoto).exists()) return p.localPhoto;
        if (p.photoUrl == null) return null;
        String known = photoFiles.get(p.photoUrl);
        if (known != null && new File(known).exists()) return known;
        final String url = p.photoUrl;
        final File dst = new File(photoDir(), Utilities.MD5(url) + ".img");
        if (dst.exists()) {
            photoFiles.put(url, dst.getAbsolutePath());
            return dst.getAbsolutePath();
        }
        if (photoLoading.add(url)) {
            io.postRunnable(() -> {
                boolean ok = PrizmaNet.download(url, dst, 8L * 1024 * 1024, 30000);
                AndroidUtilities.runOnUIThread(() -> {
                    photoLoading.remove(url);
                    if (ok) {
                        photoFiles.put(url, dst.getAbsolutePath());
                        if (ready != null) ready.run();
                    }
                });
            });
        }
        return null;
    }

    /** Photos go up as JPEG ≤ 1600 px. Off the UI thread. */
    private static byte[] readJpeg(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return null;
            float scale = Math.min(1f, 1600f / Math.max(b.getWidth(), b.getHeight()));
            if (scale < 1f) {
                Bitmap s = Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * scale), Math.round(b.getHeight() * scale), true);
                if (s != b) b.recycle();
                b = s;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
            b.recycle();
            return out.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }
}
