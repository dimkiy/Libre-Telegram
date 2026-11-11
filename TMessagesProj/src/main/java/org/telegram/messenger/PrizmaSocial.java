package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Components.PrizmaGifts;
import org.telegram.ui.Stars.StarsController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Random;

public final class PrizmaSocial {
    private PrizmaSocial() {}

    private static int version;

    public static int version() {
        return version;
    }

    private static void touched() {
        version++;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_social", Context.MODE_PRIVATE);
    }

    public static boolean cardEnabled() {
        return prefs().getBoolean("card", true);
    }

    public static void setCardEnabled(boolean v) {
        touched();
        prefs().edit().putBoolean("card", v).apply();
    }

    public static boolean demo() {
        return false;
    }

    public static final long CREATOR_ID = 1433938238L;

    public static boolean isCreator(long userId) {
        return userId == CREATOR_ID;
    }

    private static final long[] TEAM = {5229866647L, 5898279328L};

    public static boolean isTeam(long userId) {
        for (long id : TEAM) if (id == userId) return true;
        return false;
    }

    public static void setDemo(boolean v) {
        touched();
        prefs().edit().putBoolean("demo", v).apply();
    }

    public static boolean noViewMark() {
        return prefs().getBoolean("no_view_mark", false);
    }

    public static void setNoViewMark(boolean v) {
        prefs().edit().putBoolean("no_view_mark", v).apply();
    }

    public static final int WALL_ALL = 0, WALL_CONTACTS = 1, WALL_NOBODY = 2;

    public static int wallWho() {
        return prefs().getInt("wall_who", WALL_ALL);
    }

    public static String wallWhoName(int v) {
        return v == WALL_CONTACTS ? "Только контакты" : v == WALL_NOBODY ? "Никто, кроме меня" : "Все пользователи Libre";
    }

    public static boolean wallHidden() {
        return prefs().getBoolean("wall_hidden", false);
    }

    public static final String C_HOSTED = "hosted";
    public static final String C_JOINED = "joined";
    public static final String C_NFC = "nfc";

    private static long selfId(int account) {
        return UserConfig.getInstance(account).getClientUserId();
    }

    private static long legacyOwner() {
        SharedPreferences p = prefs();
        long owner = p.getLong("legacy_owner", 0);
        if (owner == 0) {
            owner = UserConfig.getInstance(0).getClientUserId();
            if (owner == 0) owner = selfId(UserConfig.selectedAccount);
            if (owner != 0) p.edit().putLong("legacy_owner", owner).apply();
        }
        return owner;
    }

    private static int perUser(String key, long userId) {
        SharedPreferences p = prefs();
        String k = key + "_" + userId;
        if (p.contains(k)) return p.getInt(k, 0);
        return userId != 0 && userId == legacyOwner() ? p.getInt(key, 0) : 0;
    }

    public static int counter(String key, long userId) {
        return perUser("c_" + key, userId);
    }

    public static void inc(String key) {
        long uid = selfId(UserConfig.selectedAccount);
        if (uid == 0) return;
        touched();
        prefs().edit().putInt("c_" + key + "_" + uid, counter(key, uid) + 1).apply();
        PrizmaCloud.syncAchievementsSoon(UserConfig.selectedAccount);
    }

    public static final int STATUS_ONLINE = 0, STATUS_IDLE = 1, STATUS_DND = 2, STATUS_INVISIBLE = 3;
    public static final int[] STATUS_COLORS = {0xff43b581, 0xfffaa61a, 0xfff04747, 0xff8a8f98};

    public static String statusName(int s) {
        switch (s) {
            case STATUS_IDLE: return "Нет на месте";
            case STATUS_DND: return "Не беспокоить";
            case STATUS_INVISIBLE: return "Невидимка";
            default: return "В сети";
        }
    }

    public static Object[] myStatus(int account) {
        long uid = selfId(account);
        SharedPreferences p = prefs();
        int s = p.getInt("st_" + uid, STATUS_ONLINE);
        String text = p.getString("st_text_" + uid, "");
        long until = p.getLong("st_until_" + uid, 0);
        if (until > 0 && until * 1000L < System.currentTimeMillis()) {
            applyStatusEffects(STATUS_ONLINE);
            p.edit().remove("st_" + uid).remove("st_text_" + uid).remove("st_until_" + uid).apply();
            touched();
            return new Object[]{STATUS_ONLINE, "", 0L};
        }
        return new Object[]{s, text, until};
    }

    public static void setMyStatus(int account, int s, String text, long until, Utilities.Callback<String> done) {
        long uid = selfId(account);
        if (uid == 0) return;
        prefs().edit().putInt("st_" + uid, s).putString("st_text_" + uid, text == null ? "" : text.trim())
                .putLong("st_until_" + uid, until).apply();
        applyStatusEffects(s);
        touched();
        PrizmaCloud.putStatus(account, s, text, until, done);
    }

    private static void applyStatusEffects(int s) {
        SharedPreferences p = prefs();
        boolean wantDnd = s == STATUS_DND, wantHidden = s == STATUS_INVISIBLE;
        if (wantDnd && !PrizmaConfig.dnd()) {
            PrizmaConfig.setDnd(true);
            p.edit().putBoolean("st_set_dnd", true).apply();
        } else if (!wantDnd && p.getBoolean("st_set_dnd", false)) {
            PrizmaConfig.setDnd(false);
            p.edit().remove("st_set_dnd").apply();
        }
        if (wantHidden && !PrizmaConfig.hideOnline()) {
            PrizmaConfig.setHideOnline(true);
            p.edit().putBoolean("st_set_hidden", true).apply();
        } else if (!wantHidden && p.getBoolean("st_set_hidden", false)) {
            PrizmaConfig.setHideOnline(false);
            p.edit().remove("st_set_hidden").apply();
        }
    }

    public static final long LIBRE_EPOCH = 1_791_331_200_000L;

    public static long librSince() {
        long saved = prefs().getLong("since", 0);
        long now = System.currentTimeMillis();
        if (saved >= LIBRE_EPOCH && saved <= now && prefs().getBoolean("since_v2", false)) return saved;
        long t = now;
        try {
            Context c = ApplicationLoader.applicationContext;
            long installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).firstInstallTime;
            if (installed >= LIBRE_EPOCH && installed <= now) t = installed;
        } catch (Throwable ignore) {
        }
        if (saved >= LIBRE_EPOCH && saved <= now) t = Math.min(t, saved);
        prefs().edit().putLong("since", t).putBoolean("since_v2", true).apply();
        return t;
    }

    private static final long[] TG_IDS = {
            0L, 2768409L, 7679610L, 11538514L, 15835244L, 23646077L,
            38015510L, 44634663L, 46145305L, 54845238L, 63263518L, 101260938L,
            101323197L, 103204956L, 110306839L, 117597704L, 124872445L, 127929227L,
            133909606L, 143445125L, 152956184L, 171295414L, 181783990L, 222021233L,
            225034354L, 278941742L, 285253072L, 294851037L, 297621225L, 328594461L,
            337808429L, 341546272L, 352940995L, 369669043L, 400169472L, 805158066L,
            1974255900L, 5027173742L, 5045293264L, 5058656439L, 5115611149L, 5146957707L,
            5182233002L, 5266729977L, 5308260177L, 5387226589L, 5434011049L, 5474282362L,
            5531378275L, 5578621357L, 5601951167L, 5681497337L, 5767004208L, 5832370132L,
            5926866061L, 6136110037L, 6326011828L, 6462226143L, 6540611293L, 6694418617L,
            6854829938L, 6909688956L, 7002435197L, 7091188196L, 7233152998L, 7248991615L,
            7283525500L, 7375779833L, 7454492493L, 7692193285L, 7824631473L, 7866754510L,
            8043215005L, 8179125032L, 8284801005L, 8423113779L, 8480708838L, 8559682245L
    };
    private static final int[] TG_DAYS = {
            15931, 16010, 16070, 16102, 16121, 16127, 16130, 16196, 16205, 16333,
            16370, 16500, 16507, 16586, 16600, 16647, 16664, 16697, 16715, 16770,
            16792, 16869, 16901, 16960, 16970, 17054, 17092, 17124, 17151, 17194,
            17218, 17219, 17221, 17256, 17378, 18092, 18912, 18969, 19005, 19049,
            19050, 19051, 19064, 19079, 19107, 19163, 19172, 19195, 19216, 19264,
            19267, 19274, 19336, 19382, 19408, 19477, 19545, 19664, 19722, 19744,
            19754, 19764, 19819, 19886, 19904, 19922, 19949, 19976, 19998, 20097,
            20121, 20252, 20258, 20278, 20337, 20371, 20397, 20403
    };
    private static final double TG_IDS_PER_DAY = 2_670_000d;

    public static long telegramSince(long userId) {
        if (userId <= 0) return 0;
        double day;
        int n = TG_IDS.length;
        if (userId <= TG_IDS[0]) {
            day = TG_DAYS[0];
        } else if (userId >= TG_IDS[n - 1]) {
            day = TG_DAYS[n - 1] + (userId - TG_IDS[n - 1]) / TG_IDS_PER_DAY;
        } else {
            int k = 1;
            while (TG_IDS[k] < userId) k++;
            double t = (userId - TG_IDS[k - 1]) / (double) (TG_IDS[k] - TG_IDS[k - 1]);
            day = TG_DAYS[k - 1] + (TG_DAYS[k] - TG_DAYS[k - 1]) * t;
        }
        long ms = (long) (day * 86_400_000L);
        return Math.min(ms, System.currentTimeMillis());
    }

    public static int telegramYears(long userId) {
        long ms = telegramSince(userId);
        if (ms <= 0) return 0;
        return (int) ((System.currentTimeMillis() - ms) / (365L * 86_400_000L));
    }

    public static int libreDays(long sinceMs) {
        if (sinceMs <= 0) return -1;
        return (int) Math.max(0, (System.currentTimeMillis() - sinceMs) / 86_400_000L);
    }

    public static int telegramYear(long userId) {
        long ms = telegramSince(userId);
        if (ms <= 0) return 0;
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(java.util.Calendar.YEAR);
    }

    public static class Achievement {
        public String id;
        public String title;
        public String desc;
        public String how;
        public int icon;
        public int color;
        public boolean earned;
        public int progress, goal;

        Achievement(String id, String title, int icon, int color) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.color = color;
        }
    }

    public static final int SAGE = 0xff5fae8b, COBALT = 0xff4c7bd9, RUST = 0xffc8643b, ORCHID = 0xffa35dc1,
            EMBER = 0xffd99a2b, MAUVE = 0xffb0607a, SLATE = 0xff6b7c93;
    public static final int[] PALETTE = {SAGE, COBALT, RUST, ORCHID, EMBER, MAUVE, SLATE};

    private static final long EARLY_UNTIL = 1_796_083_200_000L;

    public static ArrayList<Achievement> achievements(int account, long userId, boolean all) {
        ArrayList<Achievement> out = new ArrayList<>();
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.User user = mc.getUser(userId);
        TLRPC.UserFull full = mc.getUserFull(userId);
        boolean self = userId == UserConfig.getInstance(account).getClientUserId();

        Achievement a;
        if (isCreator(userId)) {
            a = new Achievement("creator", "Создатель Libre", R.drawable.outline_shield_check, COBALT);
            a.desc = "Сделал Libre. Такой значок только один";
            a.earned = true;
            out.add(a);
        } else if (isTeam(userId)) {
            a = new Achievement("team", "Команда Libre", R.drawable.msg_groups, SAGE);
            a.desc = "Помогал создавать Libre";
            a.earned = true;
            out.add(a);
        }

        a = new Achievement("premium", "Премиум", R.drawable.msg_settings_premium, EMBER);
        a.desc = "Пользуется Telegram Premium";
        a.how = "Оформить Telegram Premium";
        a.earned = user != null && user.premium;
        add(out, a, all);

        int gifts = full != null ? full.stargifts_count : 0;
        a = new Achievement("collector", gifts >= 100 ? "Коллекционер III" : gifts >= 50 ? "Коллекционер II" : "Коллекционер", R.drawable.msg_gift_premium, ORCHID);
        a.desc = gifts + " " + plural(gifts, "подарок", "подарка", "подарков") + " в профиле";
        a.how = "Собрать 10 подарков в профиле";
        a.earned = gifts >= 10;
        a.progress = Math.min(gifts, 10);
        a.goal = 10;
        add(out, a, all);

        a = new Achievement("nft", "Владелец NFT", R.drawable.menu_feature_unique, COBALT);
        a.desc = "Есть настоящий NFT-подарок Telegram";
        a.how = "Получить или улучшить подарок до NFT в Telegram";
        a.earned = hasRealNft(account, userId);
        add(out, a, all);

        int year = telegramYear(userId);
        int tgYears = telegramYears(userId);
        a = new Achievement("veteran", tgYears >= 10 ? "Старожил" : "Бывалый", R.drawable.msg_calendar2, SLATE);
        a.desc = "В Telegram около " + tgYears + " " + plural(tgYears, "года", "лет", "лет") + " — примерно с " + year + " года";
        a.how = "Пробыть в Telegram 5 лет (10 лет — «Старожил»)";
        a.earned = tgYears >= 5;
        a.progress = Math.min(tgYears, 5);
        a.goal = 5;
        add(out, a, all);

        a = new Achievement("author", "Автор канала", R.drawable.msg_channel, RUST);
        a.desc = "Ведёт свой канал";
        a.how = "Добавить личный канал в профиль";
        a.earned = full != null && full.personal_channel_id != 0;
        add(out, a, all);

        a = new Achievement("verified", "Подтверждён", R.drawable.menu_verification, COBALT);
        a.desc = "Аккаунт подтверждён Telegram";
        a.how = "Пройти верификацию Telegram";
        a.earned = user != null && user.verified && !isCreator(userId) && !isTeam(userId);
        if (a.earned) add(out, a, false);

        a = new Achievement("business", "Бизнес", R.drawable.msg_work, SAGE);
        a.desc = "Указал часы работы или адрес";
        a.how = "Заполнить Telegram Business";
        a.earned = full != null && (full.business_work_hours != null || full.business_location != null);
        if (a.earned) add(out, a, false);

        PrizmaCloud.Remote remote = PrizmaCloud.peek(userId);
        if (self) {
            long since = librSince();
            if (remote != null && remote.since > 0) since = Math.min(since, remote.since * 1000L);
            a = libreAch("libre");
            a.earned = true;
            add(out, a, all);

            a = libreAch("early");
            a.earned = serverSays(remote, a, since < EARLY_UNTIL);
            add(out, a, all);

            int hosted = counter(C_HOSTED, userId);
            a = libreAch("dj");
            a.desc = "Провёл " + hosted + " " + plural(hosted, "эфир", "эфира", "эфиров") + " «Слушать вместе»";
            a.earned = hosted >= 5;
            a.progress = Math.min(hosted, 5);
            a.goal = 5;
            add(out, a, all);

            int joined = counter(C_JOINED, userId);
            a = libreAch("listener");
            a.desc = "Подключался к " + joined + " " + plural(joined, "эфиру", "эфирам", "эфирам") + " друзей";
            a.earned = joined >= 5;
            a.progress = Math.min(joined, 5);
            a.goal = 5;
            add(out, a, all);

            a = libreAch("nfc");
            a.earned = counter(C_NFC, userId) >= 1;
            add(out, a, all);

            int tracks = 0;
            try {
                tracks = PrizmaPlaylist.count();
            } catch (Throwable ignore) {
            }
            a = libreAch("meloman");
            a.desc = tracks + " " + plural(tracks, "трек", "трека", "треков") + " в плейлистах";
            a.earned = tracks >= 30;
            a.progress = Math.min(tracks, 30);
            a.goal = 30;
            add(out, a, all);

            Clan clan = PrizmaClans.mine(account);
            a = libreAch("founder");
            if (clan != null) a.desc = "Основал клан «" + clan.name + "»";
            a.earned = serverSays(remote, a, clan != null && clan.myRole == ROLE_HEAD);
            add(out, a, all);

            int posts = perUser("my_posts", userId);
            a = libreAch("wall");
            a.desc = "Написал " + posts + " " + plural(posts, "запись", "записи", "записей") + " на стенах";
            a.earned = serverSays(remote, a, posts >= 10);
            a.progress = Math.min(posts, a.earned ? 10 : 9);
            a.goal = 10;
            add(out, a, all);

            if (remote != null && remote.views >= 0) {
                a = libreAch("popular");
                a.earned = serverSays(remote, a, remote.views >= 1000);
                a.progress = (int) Math.min(remote.views, 1000);
                a.goal = 1000;
                add(out, a, all);
            }
        } else if (remote != null && remote.ach != null) {
            for (String id : LIBRE_ORDER) {
                for (String[] got : remote.ach) {
                    if (!id.equals(got[0])) continue;
                    a = libreAch(id);
                    if (got[1] != null && !got[1].isEmpty()) a.desc = got[1];
                    a.earned = true;
                    out.add(a);
                    break;
                }
            }
        } else if (remote != null) {
            a = libreAch("libre");
            a.earned = true;
            out.add(a);
            if (remote.views >= 1000) {
                a = libreAch("popular");
                a.desc = remote.views + " просмотров профиля";
                a.earned = true;
                out.add(a);
            }
        }
        if (remote != null && remote.since > 0) {
            int days = libreDays(remote.since * 1000L);
            a = new Achievement("libre_time", days >= 365 ? "Год в Libre" : days >= 180 ? "Полгода в Libre" : "Месяц в Libre", R.drawable.msg_calendar2, EMBER);
            a.desc = "В Libre " + days + " " + plural(days, "день", "дня", "дней");
            a.how = "Пробыть в Libre месяц (дальше — полгода и год)";
            a.earned = days >= 30;
            a.progress = Math.min(days, 30);
            a.goal = 30;
            add(out, a, all);
        }
        return out;
    }

    private static final String[] LIBRE_ORDER = {"libre", "early", "dj", "listener", "nfc", "meloman", "founder", "wall", "popular"};

    public static boolean isPhoneAchievement(String id) {
        return "dj".equals(id) || "listener".equals(id) || "nfc".equals(id) || "meloman".equals(id);
    }

    private static boolean serverSays(PrizmaCloud.Remote remote, Achievement a, boolean local) {
        if (remote == null || remote.ach == null) return local;
        for (String[] got : remote.ach) {
            if (a.id.equals(got[0])) {
                if (got[1] != null && !got[1].isEmpty()) a.desc = got[1];
                return true;
            }
        }
        return false;
    }

    private static Achievement libreAch(String id) {
        Achievement a;
        switch (id) {
            case "early":
                a = new Achievement(id, "Ранний", R.drawable.msg_clear_recent, EMBER);
                a.desc = "С Libre с самого начала";
                a.how = "Установить Libre до декабря 2026";
                return a;
            case "dj":
                a = new Achievement(id, "Диджей", R.drawable.msg_voice_headphones, ORCHID);
                a.desc = "Провёл эфиры «Слушать вместе»";
                a.how = "Провести 5 эфиров «Слушать вместе»";
                return a;
            case "listener":
                a = new Achievement(id, "Слушатель", R.drawable.msg_groups, MAUVE);
                a.desc = "Подключался к эфирам друзей";
                a.how = "Подключиться к 5 эфирам друзей";
                return a;
            case "nfc":
                a = new Achievement(id, "Рукопожатие", R.drawable.msg_contacts, COBALT);
                a.desc = "Обменялся контактом, коснувшись телефонов";
                a.how = "Обменяться контактом по NFC";
                return a;
            case "meloman":
                a = new Achievement(id, "Меломан", R.drawable.media_like, RUST);
                a.desc = "Собрал 30 треков в плейлистах";
                a.how = "Собрать 30 треков в плейлистах";
                return a;
            case "founder":
                a = new Achievement(id, "Основатель клана", R.drawable.msg_groups, SLATE);
                a.desc = "Основал клан";
                a.how = "Создать свой клан";
                return a;
            case "wall":
                a = new Achievement(id, "Душа компании", R.drawable.msg_discuss, SAGE);
                a.desc = "Написал 10 записей на стенах";
                a.how = "Написать 10 записей на стенах";
                return a;
            case "popular":
                a = new Achievement(id, "Популярный", R.drawable.msg_views, COBALT);
                a.desc = "1000 просмотров профиля";
                a.how = "Набрать 1000 просмотров профиля";
                return a;
            default:
                a = new Achievement("libre", "Свой в Libre", R.drawable.outline_shield_check, SAGE);
                a.desc = "Пользуется Libre";
                return a;
        }
    }

    private static void add(ArrayList<Achievement> out, Achievement a, boolean all) {
        if (a.earned || all) out.add(a);
    }

    private static boolean hasRealNft(int account, long userId) {
        try {
            StarsController.GiftsList list = StarsController.getInstance(account).getProfileGiftsList(userId, false);
            if (list == null) return false;
            for (TL_stars.SavedStarGift g : list.gifts) {
                if (g != null && g.gift instanceof TL_stars.TL_starGiftUnique && !PrizmaGifts.isLibreGift(g)) {
                    return true;
                }
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    public static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    public static class Profile {
        public long views = -1;
        public long since;
        public Clan clan;
        public boolean demo;
    }

    public static Profile profile(int account, long userId) {
        boolean self = userId == UserConfig.getInstance(account).getClientUserId();
        PrizmaCloud.Remote remote = PrizmaCloud.peek(userId);
        if (self) {
            Profile p = new Profile();
            p.since = remote != null && remote.since > 0 ? remote.since * 1000L : librSince();
            p.clan = PrizmaClans.mine(account);
            if (remote != null && remote.views >= 0) {
                p.views = remote.views;
            } else if (demo()) {
                p.views = demoViews(userId);
                p.demo = true;
            }
            return p;
        }
        if (remote != null) {
            Profile p = new Profile();
            p.since = remote.since * 1000L;
            p.views = remote.views;
            if (remote.clanId != 0) {
                Clan c = new Clan();
                c.id = remote.clanId;
                c.tag = remote.clanTag;
                c.name = remote.clanName;
                c.color = remote.clanColor;
                p.clan = c;
            }
            return p;
        }
        if (!demo()) return null;
        Random r = new Random(userId * 131 + 3);
        Profile p = new Profile();
        p.demo = true;
        p.views = demoViews(userId);
        long start = 1_791_331_200_000L;
        long span = Math.max(1, System.currentTimeMillis() - start);
        p.since = start + (long) (r.nextDouble() * span);
        p.clan = null;
        return p;
    }

    private static long demoViews(long userId) {
        Random r = new Random(userId * 17 + 11);
        return 40 + (long) (Math.pow(r.nextDouble(), 2.2) * 4800);
    }

    public static final int ROLE_MEMBER = 0, ROLE_OFFICER = 1, ROLE_HEAD = 2;

    public static class Clan {
        public long id;
        public String name;
        public String tag;
        public int color;
        public long createdAt;
        public boolean mine;
        public int myRole = -1;
        public long ownerId;
        public int members;
        public int level = 1;
        public long rating;
        public boolean full;
        public final ArrayList<Member> list = new ArrayList<>();
    }

    public static class Member {
        public long userId;
        public String name;
        public String role;
        public int roleId;
        public int color;
    }

    public static String roleName(int role) {
        return role == ROLE_HEAD ? "Глава" : role == ROLE_OFFICER ? "Офицер" : "Участник";
    }

    public static Clan myClan() {
        return PrizmaClans.mine();
    }

    static JSONObject legacyClan() {
        String json = prefs().getString("clan", null);
        if (json == null) return null;
        try {
            return new JSONObject(json);
        } catch (Throwable e) {
            return null;
        }
    }

    static void forgetLegacyClan() {
        prefs().edit().remove("clan").apply();
    }

    static void clansChanged() {
        touched();
    }

    public static void rememberWallSettings(int who, boolean hidden) {
        if (who == wallWho() && hidden == wallHidden()) return;
        touched();
        prefs().edit().putInt("wall_who", who).putBoolean("wall_hidden", hidden).apply();
    }

    public static void notePostWritten(int account) {
        long uid = selfId(account);
        if (uid == 0) return;
        touched();
        prefs().edit().putInt("my_posts_" + uid, perUser("my_posts", uid) + 1).apply();
    }

    public static final int PHOTO_REQUEST = 48231;
    private static Utilities.Callback<String> photoCallback;

    public static void pickPhoto(Activity activity, Utilities.Callback<String> onPicked) {
        if (activity == null) return;
        photoCallback = onPicked;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            activity.startActivityForResult(Intent.createChooser(intent, "Фото для записи"), PHOTO_REQUEST);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public static void handlePickedPhoto(Activity activity, Uri uri) {
        final Utilities.Callback<String> cb = photoCallback;
        photoCallback = null;
        if (uri == null || cb == null) return;
        Utilities.globalQueue.postRunnable(() -> {
            String path = null;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, o);
                }
                int sample = 1;
                while (Math.max(o.outWidth, o.outHeight) / sample > 2560) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                Bitmap bmp;
                try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, o);
                }
                bmp = PrizmaExif.upright(bmp, PrizmaExif.orientation(activity.getContentResolver(), uri));
                if (bmp != null) {
                    float scale = Math.min(1f, 1280f / Math.max(bmp.getWidth(), bmp.getHeight()));
                    if (scale < 1f) {
                        Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
                        if (s != bmp) bmp.recycle();
                        bmp = s;
                    }
                    File dir = new File(ApplicationLoader.getFilesDirFixed(), "prizma_wall");
                    dir.mkdirs();
                    File f = new File(dir, System.currentTimeMillis() + ".jpg");
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 86, out);
                    }
                    bmp.recycle();
                    path = f.getAbsolutePath();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String result = path;
            AndroidUtilities.runOnUIThread(() -> cb.run(result));
        });
    }
}
