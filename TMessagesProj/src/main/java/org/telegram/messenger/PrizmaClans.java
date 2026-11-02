package org.telegram.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/*
 * Libre — что это за файл:
 * Кланы: создать, вступить по ссылке, роли (глава, офицеры, участники). Данные живут на сервере Libre.
 */

/**
 * Prizma: clans on Libre Cloud — create one with a tag and colour, invite by link, roles (head,
 * officers, members), a clan wall and a rating. One clan per person.
 */
public final class PrizmaClans {

    private PrizmaClans() {}

    // one entry per account slot: with two accounts the second one used to see the first one's clan
    private static final PrizmaSocial.Clan[] mine = new PrizmaSocial.Clan[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] mineAt = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final long[] mineOwner = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private static final boolean[] mineLoading = new boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final ArrayList<Runnable>[] mineWaiters = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];

    private static boolean badAccount(int account) {
        return account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT;
    }

    /** My clan (cached); refreshes now and then. UI thread. */
    public static PrizmaSocial.Clan mine(int account) {
        if (badAccount(account)) return null;
        long self = UserConfig.getInstance(account).getClientUserId();
        if (mineOwner[account] != self) {
            // another Telegram account logged into this slot — the old clan isn't theirs
            mine[account] = null;
            mineAt[account] = 0;
            mineOwner[account] = self;
        }
        if (!mineLoading[account] && System.currentTimeMillis() - mineAt[account] > 120_000L) refreshMine(account, (Utilities.Callback<String>) null);
        return mine[account];
    }

    public static PrizmaSocial.Clan mine() {
        return mine(UserConfig.selectedAccount);
    }

    /** {@code done} runs once the server answered (or failed); the error text is null on success. */
    public static void refreshMine(int account, Runnable done) {
        Utilities.Callback<String> cb = null;
        if (done != null) cb = err -> done.run();
        refreshMine(account, cb);
    }

    public static void refreshMine(int account, Utilities.Callback<String> done) {
        if (badAccount(account) || !PrizmaCloud.enabled()) {
            if (done != null) done.run(badAccount(account) ? null : PrizmaWall.error(-1, null));
            return;
        }
        if (mineWaiters[account] == null) mineWaiters[account] = new ArrayList<>();
        if (done != null) mineWaiters[account].add(() -> done.run(lastError[account]));
        if (mineLoading[account]) return; // the answer to the request on its way serves everyone waiting
        mineLoading[account] = true;
        final long self = UserConfig.getInstance(account).getClientUserId();
        PrizmaCloud.call(account, "GET", "/v1/clan/my", null, r -> {
            mineLoading[account] = false;
            String err = null;
            if (self != UserConfig.getInstance(account).getClientUserId()) {
                err = "Аккаунт сменился — откройте ещё раз";
            } else if (r.code == 200 && r.body != null) {
                try {
                    JSONObject o = new JSONObject(r.body);
                    JSONObject c = o.optJSONObject("clan");
                    setMine(account, c != null ? parse(account, c) : null);
                    if (c == null) migrateLegacy(account);
                    else sayHello(account);
                } catch (Throwable e) {
                    FileLog.e(e);
                    err = "Сервер Libre прислал непонятный ответ";
                }
            } else {
                err = error(r);
                mineAt[account] = System.currentTimeMillis() - 90_000L; // try again in half a minute, not in two
            }
            lastError[account] = err;
            ArrayList<Runnable> waiting = mineWaiters[account];
            mineWaiters[account] = null;
            if (waiting != null) {
                for (Runnable w : waiting) {
                    try {
                        w.run();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            }
        });
    }

    private static final String[] lastError = new String[UserConfig.MAX_ACCOUNT_COUNT];

    private static final String[] helloSent = new String[UserConfig.MAX_ACCOUNT_COUNT];

    /** My name for the other members' lists (they may never have chatted with me). Once per run. */
    private static void sayHello(int account) {
        String name = myName(account);
        if (name.isEmpty() || name.equals(helloSent[account])) return;
        helloSent[account] = name;
        JSONObject body = new JSONObject();
        try {
            body.put("my_name", name);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/clan/hello", body, r -> {
            if (r.code != 200) helloSent[account] = null; // an older server without names — try next time
        });
    }

    private static String myName(int account) {
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        return me != null ? UserObject.getUserName(me) : "";
    }

    /** «Удалить мои данные»: the server forgot the membership, so do we. */
    static void forgetMine(int account) {
        if (badAccount(account)) return;
        setMine(account, null);
    }

    private static void setMine(int account, PrizmaSocial.Clan c) {
        mine[account] = c;
        mineAt[account] = System.currentTimeMillis();
        mineOwner[account] = UserConfig.getInstance(account).getClientUserId();
        PrizmaSocial.clansChanged();
        PrizmaCloud.notifyChanged();
    }

    /** A clan created before the server knew clans moves there under the same name, tag and colour. */
    private static void migrateLegacy(int account) {
        JSONObject old = PrizmaSocial.legacyClan();
        if (old == null) return;
        create(account, old.optString("name"), old.optString("tag"), old.optInt("color", PrizmaSocial.SAGE), (c, err) -> {
            if (c != null || err != null && err.contains("тег")) PrizmaSocial.forgetLegacyClan();
        });
    }

    static PrizmaSocial.Clan parse(int account, JSONObject o) throws Exception {
        PrizmaSocial.Clan c = new PrizmaSocial.Clan();
        c.id = o.getLong("id");
        c.name = o.optString("name");
        c.tag = o.optString("tag");
        c.color = o.optInt("color", PrizmaSocial.SAGE);
        c.createdAt = o.optLong("created") * 1000L;
        c.ownerId = o.optLong("owner");
        c.members = o.optInt("members", 0);
        c.rating = o.optLong("rating", 0);
        c.level = o.optInt("level", 1);
        c.myRole = o.isNull("myRole") ? -1 : o.optInt("myRole", -1);
        c.mine = c.myRole >= 0;
        JSONArray list = o.optJSONArray("list");
        if (list != null) {
            c.full = true;
            MessagesController mc = MessagesController.getInstance(account);
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                PrizmaSocial.Member mem = new PrizmaSocial.Member();
                mem.userId = m.getLong("user");
                mem.roleId = m.optInt("role", 0);
                mem.role = PrizmaSocial.roleName(mem.roleId);
                TLRPC.User u = mc.getUser(mem.userId);
                String told = m.optString("name", "").trim(); // what their Libre told the server
                mem.name = u != null ? UserObject.getUserName(u) : !told.isEmpty() ? told : "Пользователь Libre";
                c.list.add(mem);
            }
        }
        return c;
    }

    /** Server error code → words. */
    private static String error(PrizmaNet.Response r) {
        String e = "";
        try {
            e = r.body != null ? new JSONObject(r.body).optString("error", "") : "";
        } catch (Throwable ignore) {
        }
        switch (e) {
            case "tag taken": return "Такой тег уже занят — придумайте другой";
            case "already in a clan": return "Вы уже в клане. Сначала выйдите из него";
            case "bad name or tag": return "Название — от 2 до 32 символов, тег — 2–5 букв или цифр";
            case "bad code": return "Приглашение не найдено — возможно, клан распущен";
            case "clan full": return "В клане уже 200 участников";
            case "not allowed": return "Это может сделать только глава или офицер";
            case "not found": return "Клан не найден — возможно, его распустили";
        }
        if (r.code == 404) return "Клан не найден — возможно, его распустили";
        return PrizmaWall.error(r.code, null);
    }

    // ===================== actions =====================

    public static void create(int account, String name, String tag, int color, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name);
            body.put("tag", tag);
            body.put("color", color);
            body.put("my_name", myName(account));
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/clan", body, r -> clanReply(account, r, true, done));
    }

    public static void load(int account, long clanId, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        PrizmaCloud.call(account, "GET", "/v1/clan/" + clanId, null, r -> clanReply(account, r, false, done));
    }

    public static void preview(int account, String code, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        PrizmaCloud.call(account, "GET", "/v1/clan/code/" + code, null, r -> clanReply(account, r, false, done));
    }

    public static void join(int account, String code, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("my_name", myName(account));
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/clan/join", body, r -> clanReply(account, r, true, done));
    }

    public static void setRole(int account, long clanId, long userId, int role, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("user", userId);
            body.put("role", role);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/clan/" + clanId + "/role", body, r -> clanReply(account, r, true, done));
    }

    private static void clanReply(int account, PrizmaNet.Response r, boolean isMine, Utilities.Callback2<PrizmaSocial.Clan, String> done) {
        if (r.code == 200 && r.body != null) {
            try {
                PrizmaSocial.Clan c = parse(account, new JSONObject(r.body));
                if (isMine) {
                    setMine(account, c);
                    PrizmaCloud.forget(UserConfig.getInstance(account).getClientUserId());
                }
                done.run(c, null);
                return;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        done.run(null, error(r));
    }

    /** {@code done} gets null on success or the error text. */
    public static void leave(int account, Utilities.Callback<String> done) {
        PrizmaCloud.call(account, "POST", "/v1/clan/leave", null, r -> {
            if (r.code == 200) {
                setMine(account, null);
                PrizmaCloud.forget(UserConfig.getInstance(account).getClientUserId());
            }
            done.run(r.code == 200 ? null : error(r));
        });
    }

    public static void disband(int account, long clanId, Utilities.Callback<String> done) {
        PrizmaCloud.call(account, "DELETE", "/v1/clan/" + clanId, null, r -> {
            if (r.code == 200) {
                setMine(account, null);
                PrizmaCloud.forget(UserConfig.getInstance(account).getClientUserId());
            }
            done.run(r.code == 200 ? null : error(r));
        });
    }

    public static void kick(int account, long clanId, long userId, Utilities.Callback<String> done) {
        JSONObject body = new JSONObject();
        try {
            body.put("user", userId);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "POST", "/v1/clan/" + clanId + "/kick", body, r -> {
            if (r.code == 200) PrizmaCloud.forget(userId);
            done.run(r.code == 200 ? null : error(r));
        });
    }

    /** The invite link (made once per clan, the same afterwards). */
    public static void invite(int account, long clanId, Utilities.Callback2<String, String> done) {
        PrizmaCloud.call(account, "POST", "/v1/clan/" + clanId + "/invite", null, r -> {
            try {
                if (r.code == 200 && r.body != null) {
                    done.run(new JSONObject(r.body).getString("link"), null);
                    return;
                }
            } catch (Throwable ignore) {
            }
            done.run(null, error(r));
        });
    }

    public static void top(int account, Utilities.Callback2<ArrayList<PrizmaSocial.Clan>, String> done) {
        PrizmaCloud.call(account, "GET", "/v1/clans/top", null, r -> {
            ArrayList<PrizmaSocial.Clan> out = new ArrayList<>();
            try {
                if (r.code == 200 && r.body != null) {
                    JSONArray arr = new JSONObject(r.body).getJSONArray("clans");
                    for (int i = 0; i < arr.length(); i++) out.add(parse(account, arr.getJSONObject(i)));
                    done.run(out, null);
                    return;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            done.run(null, error(r));
        });
    }

    /** «https://<server>/c/<code>» or just the code → the code; null when it isn't one of ours. */
    public static String codeOf(String text) {
        if (text == null) return null;
        text = text.trim();
        int i = text.indexOf("/c/");
        if (i >= 0) {
            String host = PrizmaCloud.host().replaceFirst("^https?://", "");
            if (host.isEmpty() || !text.contains(host)) return null;
            text = text.substring(i + 3);
        }
        int q = text.indexOf('?');
        if (q >= 0) text = text.substring(0, q);
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text.matches("[A-Za-z0-9]{4,16}") ? text : null;
    }
}
