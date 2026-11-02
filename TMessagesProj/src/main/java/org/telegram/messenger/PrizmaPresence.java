package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/*
 * Libre — что это за файл:
 * Статусы как в Discord в списке чатов (цвет кружка), Libre-подарок у ника и анонимный номер (+888).
 */

/**
 * Libre: что видно о других людях прямо в списке чатов и рядом с их именем.
 *
 * 1. Цвет кружка «в сети». Если человек в Libre поставил статус «Не беспокоить», вместо зелёного
 *    кружка у его аватарки рисуется красный, «Нет на месте» — жёлтый (как в Discord).
 * 2. Подарок у ника. Libre-подарок, который человек «надел», показывается рядом с его именем и в
 *    шапке профиля так же, как настоящий подарок Telegram.
 *
 * Как это работает: список чатов спрашивает dotColor() для каждого человека на экране. Мы копим
 * их id и раз в полсекунды одним запросом спрашиваем у сервера Libre сразу про всех
 * (POST /v1/presence). Ответ хранится 2 минуты, потом спрашиваем снова.
 */
public final class PrizmaPresence {

    private PrizmaPresence() {}

    /** Что мы знаем о человеке. */
    private static class Info {
        int status = -1;     // PrizmaSocial.STATUS_*; -1 = статуса нет или человек не в Libre
        long statusUntil;    // до какого времени статус (секунды), 0 = пока не сменит
        TLRPC.TL_emojiStatusCollectible wear; // надетый Libre-подарок или null
        long fetchedAt;      // когда спрашивали сервер (мс)
    }

    private static final long FRESH_MS = 2 * 60_000L;      // столько верим ответу сервера
    private static final int BATCH = 200;                  // больше сервер за раз не примет

    private static final HashMap<Long, Info> known = new HashMap<>();
    private static final HashSet<Long> wanted = new HashSet<>();
    private static boolean scheduled, inFlight;
    private static long serverTooOldUntil;                 // старый сервер без /v1/presence — не дёргаем его

    // ===================== кружок в списке чатов =====================

    /**
     * Цвет кружка «в сети» для человека: красный («Не беспокоить»), жёлтый («Нет на месте») или
     * 0 — оставить обычный зелёный. Вызывается при отрисовке, поэтому только читает кэш.
     */
    public static int dotColor(int account, long userId) {
        want(account, userId);
        Info info = known.get(userId);
        if (info == null || info.status < 0) return 0;
        if (info.statusUntil != 0 && info.statusUntil * 1000L < System.currentTimeMillis()) return 0;
        if (info.status == PrizmaSocial.STATUS_IDLE || info.status == PrizmaSocial.STATUS_DND) {
            return PrizmaSocial.STATUS_COLORS[info.status];
        }
        return 0;
    }

    /** Запомнить, что про этого человека надо спросить сервер (если ещё не знаем или давно). */
    public static void want(int account, long userId) {
        if (userId <= 0 || !PrizmaCloud.enabled()) return;
        Info info = known.get(userId);
        if (info != null && System.currentTimeMillis() - info.fetchedAt < FRESH_MS) return;
        if (System.currentTimeMillis() < serverTooOldUntil) return;
        if (wanted.add(userId) && !scheduled) {
            scheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                scheduled = false;
                askServer(account);
            }, 500);
        }
    }

    private static void askServer(int account) {
        if (wanted.isEmpty()) return;
        if (inFlight) {
            // предыдущий запрос ещё идёт — попробуем после него
            scheduled = true;
            AndroidUtilities.runOnUIThread(() -> {
                scheduled = false;
                askServer(account);
            }, 1000);
            return;
        }
        final ArrayList<Long> ids = new ArrayList<>();
        for (Long id : wanted) {
            ids.add(id);
            if (ids.size() >= BATCH) break;
        }
        wanted.removeAll(ids);
        JSONObject body = new JSONObject();
        try {
            body.put("ids", new JSONArray(ids));
        } catch (Throwable ignore) {
        }
        inFlight = true;
        PrizmaCloud.call(account, "POST", "/v1/presence", body, r -> {
            inFlight = false;
            final long now = System.currentTimeMillis();
            if (r.code == 404) {
                serverTooOldUntil = now + 30 * 60_000L; // сервер ещё не обновлён
            }
            JSONObject users = null;
            if (r.code == 200 && r.body != null) {
                try {
                    users = new JSONObject(r.body).optJSONObject("users");
                } catch (Throwable ignore) {
                }
            }
            for (Long id : ids) {
                Info info = new Info();
                info.fetchedAt = now; // даже при ошибке — чтобы не спрашивать снова каждую секунду
                JSONObject u = users != null ? users.optJSONObject(String.valueOf(id)) : null;
                if (u != null) readInto(info, u);
                known.put(id, info);
            }
            if (users != null) {
                applyAll(account, ids);
            }
            if (!wanted.isEmpty()) askServer(account);
        });
    }

    /** Разбирает ответ сервера про одного человека (его же присылает /v1/profile). */
    private static void readInto(Info info, JSONObject u) {
        JSONObject st = u.optJSONObject("status");
        if (st != null) {
            info.status = st.optInt("s", -1);
            info.statusUntil = st.optLong("until", 0);
        }
        info.wear = wearFromJson(u.optJSONObject("wear"));
    }

    /** Профиль человека пришёл с сервера целиком (PrizmaCloud) — обновим и наш кэш. */
    public static void fromProfile(int account, long userId, JSONObject profile) {
        Info info = new Info();
        info.fetchedAt = System.currentTimeMillis();
        readInto(info, profile);
        known.put(userId, info);
        ArrayList<Long> one = new ArrayList<>();
        one.add(userId);
        applyAll(account, one);
    }

    // ===================== подарок у ника =====================

    /**
     * Libre-подарок, надетый человеком, в виде настоящего статуса-подарка Telegram: рядом с именем
     * рисуется модель подарка, а в профиле — его фон и узор. Номер ссылки «libre…» отличает наш
     * подарок от настоящего.
     */
    private static TLRPC.TL_emojiStatusCollectible wearFromJson(JSONObject w) {
        if (w == null || w.optLong("doc", 0) == 0) return null;
        TLRPC.TL_emojiStatusCollectible s = new TLRPC.TL_emojiStatusCollectible();
        s.collectible_id = w.optLong("id", 0);
        s.document_id = w.optLong("doc", 0);
        s.pattern_document_id = w.optLong("pattern", 0);
        JSONArray c = w.optJSONArray("colors");
        if (c != null && c.length() >= 4) {
            s.center_color = (int) c.optLong(0);
            s.edge_color = (int) c.optLong(1);
            s.pattern_color = (int) c.optLong(2);
            s.text_color = (int) c.optLong(3);
        }
        int num = w.optInt("num", 0);
        s.title = w.optString("title", "Libre") + (num > 0 ? " #" + num : "");
        s.slug = LIBRE_SLUG + Math.abs(s.collectible_id);
        return s;
    }

    private static JSONObject wearToJson(TLRPC.TL_emojiStatusCollectible s, int num, String title) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.collectible_id);
            o.put("doc", s.document_id);
            o.put("pattern", s.pattern_document_id);
            JSONArray c = new JSONArray();
            c.put(s.center_color);
            c.put(s.edge_color);
            c.put(s.pattern_color);
            c.put(s.text_color);
            o.put("colors", c);
            o.put("title", title);
            o.put("num", num);
        } catch (Throwable ignore) {
        }
        return o;
    }

    /** Ссылки наших подарков начинаются так — по этому мы узнаём «ненастоящий» статус. */
    public static final String LIBRE_SLUG = "libre";

    public static boolean isLibreWear(TLRPC.EmojiStatus status) {
        return status instanceof TLRPC.TL_emojiStatusCollectible
                && ((TLRPC.TL_emojiStatusCollectible) status).slug != null
                && ((TLRPC.TL_emojiStatusCollectible) status).slug.startsWith(LIBRE_SLUG);
    }

    /**
     * Вызывается из MessagesController.putUser для каждого человека, которого присылает Telegram:
     * если он надел Libre-подарок, подставляем его вместо статуса. Если снял — убираем наш.
     */
    public static void decorate(int account, TLRPC.User user) {
        if (user == null) return;
        TLRPC.TL_emojiStatusCollectible wear;
        if (user.id == UserConfig.getInstance(account).getClientUserId()) {
            wear = myWear(account);
        } else {
            Info info = known.get(user.id);
            if (info == null) return; // ещё не знаем — не трогаем то, что прислал Telegram
            wear = info.wear;
        }
        if (wear != null) {
            user.emoji_status = wear;
        } else if (isLibreWear(user.emoji_status)) {
            user.emoji_status = new TLRPC.TL_emojiStatusEmpty();
        }
    }

    /** Обновить уже загруженных людей и перерисовать экраны. */
    private static void applyAll(int account, ArrayList<Long> ids) {
        MessagesController mc = MessagesController.getInstance(account);
        boolean any = false;
        for (Long id : ids) {
            TLRPC.User user = mc.getUser(id);
            if (user == null) continue;
            TLRPC.EmojiStatus before = user.emoji_status;
            decorate(account, user);
            any |= before != user.emoji_status;
        }
        // кружки перерисовываем всегда (статус мог смениться), подарки — если что-то поменялось
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                MessagesController.UPDATE_MASK_STATUS | (any ? MessagesController.UPDATE_MASK_EMOJI_STATUS : 0));
    }

    // ===================== мой надетый подарок =====================

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_wear", Context.MODE_PRIVATE);
    }

    private static final HashMap<Long, TLRPC.TL_emojiStatusCollectible> myWearCache = new HashMap<>();

    /** Мой надетый Libre-подарок (или null). */
    public static TLRPC.TL_emojiStatusCollectible myWear(int account) {
        long self = UserConfig.getInstance(account).getClientUserId();
        if (myWearCache.containsKey(self)) return myWearCache.get(self);
        TLRPC.TL_emojiStatusCollectible wear = null;
        try {
            String s = prefs().getString("wear_" + self, "");
            if (!s.isEmpty()) wear = wearFromJson(new JSONObject(s));
        } catch (Throwable ignore) {
        }
        myWearCache.put(self, wear);
        return wear;
    }

    /** Номер копии, который я надел (id экземпляра в витрине), или 0. */
    public static long myWornInstance(int account) {
        TLRPC.TL_emojiStatusCollectible w = myWear(account);
        return w != null ? w.collectible_id : 0;
    }

    /**
     * Надеть Libre-подарок (или снять: {@code wear == null}). Сразу видно у меня, а сервер Libre
     * сообщает остальным. {@code done} получает null при успехе или текст ошибки.
     */
    public static void setMyWear(int account, TLRPC.TL_emojiStatusCollectible wear, int num, String title, Utilities.Callback<String> done) {
        final long self = UserConfig.getInstance(account).getClientUserId();
        JSONObject json = wear != null ? wearToJson(wear, num, title) : new JSONObject();
        if (wear != null) {
            wear.slug = LIBRE_SLUG + Math.abs(wear.collectible_id);
            prefs().edit().putString("wear_" + self, json.toString()).apply();
        } else {
            prefs().edit().remove("wear_" + self).apply();
        }
        myWearCache.put(self, wear);
        TLRPC.User me = MessagesController.getInstance(account).getUser(self);
        if (me != null) {
            decorate(account, me);
            TLRPC.User current = UserConfig.getInstance(account).getCurrentUser();
            if (current != null) current.emoji_status = me.emoji_status;
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_EMOJI_STATUS);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.userEmojiStatusUpdated, me);
        PrizmaCloud.call(account, "PUT", "/v1/wear", json, r -> {
            if (done == null) return;
            if (r.code == 200) done.run(null);
            else if (r.code == 404) done.run("Сервер Libre ещё не обновлён — остальные увидят подарок после обновления сервера");
            else done.run(PrizmaWall.error(r.code, null));
        });
    }

    // ===================== анонимный номер =====================

    /**
     * Мой анонимный номер (+888, куплен на Fragment) показывается другим людям в Libre — в профиле.
     * Обычный номер телефона никогда не отправляется: проверка «888» стоит и здесь, и на сервере.
     */
    public static void syncAnonNumber(int account) {
        if (!PrizmaCloud.enabled()) return;
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        String phone = me != null && me.phone != null ? me.phone.replaceAll("[^0-9]", "") : "";
        String show = PrizmaConfig.showAnonNumber() && isAnonymous(phone) ? phone : "";
        final String key = "anon_" + UserConfig.getInstance(account).getClientUserId();
        if (show.equals(prefs().getString(key, null))) return; // сервер уже знает
        JSONObject body = new JSONObject();
        try {
            body.put("phone", show);
        } catch (Throwable ignore) {
        }
        PrizmaCloud.call(account, "PUT", "/v1/anon", body, r -> {
            if (r.code == 200) prefs().edit().putString(key, show).apply();
        });
    }

    /** Анонимные номера Telegram начинаются с 888. */
    public static boolean isAnonymous(String digits) {
        return digits != null && digits.startsWith("888") && digits.length() >= 7;
    }

    /** +888 0123 4567 — как пишет Telegram. */
    public static String formatAnon(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        try {
            return org.telegram.PhoneFormat.PhoneFormat.getInstance().format("+" + digits);
        } catch (Throwable e) {
            return "+" + digits;
        }
    }
}
