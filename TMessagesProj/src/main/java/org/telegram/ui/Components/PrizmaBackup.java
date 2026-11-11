package org.telegram.ui.Components;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaNet;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class PrizmaBackup {
    private PrizmaBackup() {}

    private static final String[] FILES = {"prizma_cfg", "prizma_playlist", "prizma_eq", "prizma_social", "prizma_cards", "prizma_gifts_rx"};

    private static void toast(String s) {
        Toast.makeText(ApplicationLoader.applicationContext, s, Toast.LENGTH_LONG).show();
    }

    private static boolean ready() {
        if (PrizmaCloud.enabled()) return true;
        toast("Это работает через сервер Libre — включите его в хабе Libre");
        return false;
    }

    public static void show(Context ctx) {
        if (!ready()) return;
        int account = UserConfig.selectedAccount;
        PrizmaCloud.call(account, "GET", "/v1/backup?info=1", null, r -> {
            String state;
            boolean has = false;
            if (r.code == 200 && r.body != null) {
                try {
                    JSONObject o = new JSONObject(r.body);
                    has = true;
                    state = "Последняя копия: " + new SimpleDateFormat("d MMMM yyyy, HH:mm", new Locale("ru"))
                            .format(new Date(o.getLong("date") * 1000L)) + " · " + Math.max(1, o.getLong("size") / 1024) + " КБ";
                } catch (Throwable e) {
                    state = "Копия есть";
                }
            } else if (r.code == 404) {
                state = "Копии пока нет";
            } else {
                toast(org.telegram.messenger.PrizmaWall.error(r.code, null));
                return;
            }
            final boolean exists = has;
            CharSequence[] rows = exists
                    ? new CharSequence[]{"Обновить копию сейчас", "Восстановить из копии", "Удалить копию"}
                    : new CharSequence[]{"Сделать копию сейчас"};
            new AlertDialog.Builder(ctx)
                    .setTitle("Облачная копия Libre")
                    .setMessage(state + "\n\nВ копию попадают настройки и оформление Libre, плейлисты, эквалайзер и витрина подарков. "
                            + "Переписка и вход в аккаунт — нет. Прочитать копию можете только вы.")
                    .setItems(rows, (d, which) -> {
                        if (which == 0) upload(ctx, account);
                        else if (which == 1) confirmRestore(ctx, account);
                        else remove(account);
                    })
                    .show();
        });
    }

    private static void upload(Context ctx, int account) {
        AlertDialog progress = new AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        Utilities.globalQueue.postRunnable(() -> {
            byte[] data = pack();
            AndroidUtilities.runOnUIThread(() -> {
                if (data == null) {
                    progress.dismiss();
                    toast("Не удалось собрать копию");
                    return;
                }
                PrizmaCloud.upload(account, "PUT", "/v1/backup", data, "application/octet-stream", r -> {
                    progress.dismiss();
                    toast(r.code == 200 ? "Копия сохранена на сервере Libre"
                            : r.code == 413 ? "Копия больше 12 МБ — уберите часть треков из плейлистов"
                            : org.telegram.messenger.PrizmaWall.error(r.code, null));
                });
            });
        });
    }

    private static void confirmRestore(Context ctx, int account) {
        new AlertDialog.Builder(ctx)
                .setTitle("Восстановить из копии?")
                .setMessage("Настройки и плейлисты из копии заменят нынешние. После этого Libre перезапустится.")
                .setPositiveButton("Восстановить", (d, w) -> restore(ctx, account))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private static void restore(Context ctx, int account) {
        AlertDialog progress = new AlertDialog(ctx, AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(200);
        PrizmaCloud.call(account, "GET", "/v1/backup", null, r -> Utilities.globalQueue.postRunnable(() -> {
            boolean ok = r.code == 200 && r.bytes != null && unpack(r.bytes);
            AndroidUtilities.runOnUIThread(() -> {
                progress.dismiss();
                if (!ok) {
                    toast(r.code == 404 ? "Копии нет" : "Не удалось восстановить копию");
                    return;
                }
                restart(ctx);
            });
        }));
    }

    private static void remove(int account) {
        PrizmaCloud.call(account, "DELETE", "/v1/backup", null, r -> toast(r.code == 200 ? "Копия удалена с сервера" : org.telegram.messenger.PrizmaWall.error(r.code, null)));
    }

    private static void restart(Context ctx) {
        toast("Копия восстановлена — Libre перезапускается");
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    ctx.startActivity(i);
                }
            } catch (Throwable ignore) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        }, 900);
    }

    private static byte[] pack() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("made", System.currentTimeMillis() / 1000);
            JSONObject files = new JSONObject();
            for (String name : FILES) {
                SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE);
                JSONObject f = new JSONObject();
                for (Map.Entry<String, ?> e : p.getAll().entrySet()) {
                    Object v = e.getValue();
                    JSONArray pair = new JSONArray();
                    if (v instanceof Boolean) pair.put("b").put(v);
                    else if (v instanceof Integer) pair.put("i").put(v);
                    else if (v instanceof Long) pair.put("l").put(v);
                    else if (v instanceof Float) pair.put("f").put(((Float) v).doubleValue());
                    else if (v instanceof String) pair.put("s").put(v);
                    else if (v instanceof Set) pair.put("set").put(new JSONArray((Set<?>) v));
                    else continue;
                    f.put(e.getKey(), pair);
                }
                files.put(name, f);
            }
            root.put("files", files);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(root.toString().getBytes("UTF-8"));
            }
            return out.toByteArray();
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean unpack(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
            }
            JSONObject root = new JSONObject(out.toString("UTF-8"));
            JSONObject files = root.getJSONObject("files");
            for (String name : FILES) {
                JSONObject f = files.optJSONObject(name);
                if (f == null) continue;
                SharedPreferences.Editor ed = ApplicationLoader.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit();
                ed.clear();
                JSONArray keys = f.names();
                if (keys != null) {
                    for (int i = 0; i < keys.length(); i++) {
                        String k = keys.getString(i);
                        JSONArray pair = f.getJSONArray(k);
                        switch (pair.getString(0)) {
                            case "b": ed.putBoolean(k, pair.getBoolean(1)); break;
                            case "i": ed.putInt(k, pair.getInt(1)); break;
                            case "l": ed.putLong(k, pair.getLong(1)); break;
                            case "f": ed.putFloat(k, (float) pair.getDouble(1)); break;
                            case "s": {
                                String s = pair.getString(1);
                                if (s.startsWith("/") && s.length() < 512 && !new java.io.File(s).exists()) s = "";
                                ed.putString(k, s);
                                break;
                            }
                            case "set": {
                                JSONArray arr = pair.getJSONArray(1);
                                HashSet<String> set = new HashSet<>();
                                for (int j = 0; j < arr.length(); j++) set.add(arr.getString(j));
                                ed.putStringSet(k, set);
                                break;
                            }
                        }
                    }
                }
                ed.commit();
            }
            org.telegram.messenger.PrizmaPlaylist.reload();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    public static void showWhatServerKnows(Context ctx) {
        if (!ready()) return;
        PrizmaCloud.call(UserConfig.selectedAccount, "GET", "/v1/me/export", null, r -> {
            if (r.code != 200 || r.body == null) {
                toast(org.telegram.messenger.PrizmaWall.error(r.code, null));
                return;
            }
            try {
                render(ctx, new JSONObject(r.body), r.body);
            } catch (Throwable e) {
                toast("Не удалось прочитать ответ сервера");
            }
        });
    }

    private static void render(Context ctx, JSONObject o, String raw) throws Exception {
        final int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        final int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(12));
        TextView title = new TextView(ctx);
        title.setText("Что сервер Libre знает о вас");
        title.setTextColor(text);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        v.addView(title);
        TextView sub = new TextView(ctx);
        sub.setText("Это всё, что хранится — ответ сервера как есть, только по-русски");
        sub.setTextColor(gray);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 12));

        SimpleDateFormat df = new SimpleDateFormat("d MMMM yyyy", new Locale("ru"));
        add(ctx, v, text, gray, "ID в Telegram", String.valueOf(o.optLong("telegram_id")));
        if (!o.isNull("in_libre_since")) add(ctx, v, text, gray, "В Libre с", df.format(new Date(o.optLong("in_libre_since") * 1000L)));
        add(ctx, v, text, gray, "Просмотры профиля", o.optLong("profile_views") + " (только число, без того, кто смотрел)");
        add(ctx, v, text, gray, "Баннер", o.isNull("banner") ? "нет" : "есть");
        add(ctx, v, text, gray, "Открытые сессии", o.optInt("sessions") + " (телефоны, где вы вошли в Libre)");
        JSONObject ws = o.optJSONObject("wall_settings");
        if (ws != null) add(ctx, v, text, gray, "Стена", (ws.optBoolean("hidden") ? "скрыта" : "открыта")
                + ", писать могут: " + org.telegram.messenger.PrizmaSocial.wallWhoName(ws.optInt("who")).toLowerCase()
                + ", запрещено: " + count(o.optJSONArray("wall_blocked")));
        add(ctx, v, text, gray, "Ваши записи на стенах", count(o.optJSONArray("my_posts")));
        add(ctx, v, text, gray, "Ваши комментарии", count(o.optJSONArray("my_comments")));
        add(ctx, v, text, gray, "Ваши лайки", count(o.optJSONArray("posts_i_liked")));
        add(ctx, v, text, gray, "Записей на вашей стене", String.valueOf(o.optInt("posts_on_my_wall")));
        add(ctx, v, text, gray, "Ваши жалобы", count(o.optJSONArray("my_reports")));
        JSONObject clan = o.optJSONObject("clan");
        add(ctx, v, text, gray, "Клан", clan == null ? "нет" : "«" + clan.optString("name") + "» [" + clan.optString("tag") + "]");
        add(ctx, v, text, gray, "NFT в витрине", count(o.optJSONArray("showcase_nft")));
        JSONObject pl = o.optJSONObject("public_playlist");
        add(ctx, v, text, gray, "Открытый плейлист", pl == null ? "нет" : "«" + pl.optString("name") + "», " + pl.optInt("tracks") + " треков");
        add(ctx, v, text, gray, "Подарки ждут вас", String.valueOf(o.optInt("gifts_waiting_for_me")));
        add(ctx, v, text, gray, "Карточки подарков в чатах", count(o.optJSONArray("gift_cards")));
        add(ctx, v, text, gray, "Облачная копия", o.isNull("cloud_copy") ? "нет" : "есть");
        JSONArray not = o.optJSONArray("not_stored");
        if (not != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < not.length(); i++) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(not.getString(i));
            }
            add(ctx, v, text, gray, "Не хранится вообще", sb.toString());
        }

        final BottomSheet[] ref = new BottomSheet[1];
        TextView export = button(ctx, "Выгрузить всё файлом", false);
        export.setOnClickListener(x -> {
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "Мои данные в Libre");
                send.putExtra(Intent.EXTRA_TEXT, new JSONObject(raw).toString(2));
                ctx.startActivity(Intent.createChooser(send, "Сохранить мои данные"));
            } catch (Throwable e) {
                AndroidUtilities.addToClipboard(raw);
                toast("Данные скопированы");
            }
        });
        v.addView(export, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 16, 0, 0));
        TextView delete = button(ctx, "Удалить мои данные", true);
        delete.setOnClickListener(x -> {
            if (ref[0] != null) ref[0].dismiss();
            PrizmaCloudSetup.confirmDelete(ctx);
        });
        v.addView(delete, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 6, 0, 0));

        ScrollView sv = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((int) (AndroidUtilities.displaySize.y * 0.82f), MeasureSpec.AT_MOST));
            }
        };
        sv.setNestedScrollingEnabled(true);
        sv.addView(v);
        BottomSheet.Builder b = new BottomSheet.Builder(ctx);
        b.setCustomView(sv);
        ref[0] = b.create();
        ref[0].setCanDismissWithSwipe(false);
        ref[0].show();
    }

    private static String count(JSONArray a) {
        return String.valueOf(a != null ? a.length() : 0);
    }

    private static void add(Context ctx, LinearLayout v, int text, int gray, String label, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(7), 0, AndroidUtilities.dp(7));
        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(gray);
        l.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        row.addView(l);
        TextView t = new TextView(ctx);
        t.setText(TextUtils.isEmpty(value) ? "—" : value);
        t.setTextColor(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        row.addView(t);
        v.addView(row);
    }

    private static TextView button(Context ctx, String label, boolean danger) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        if (danger) {
            b.setTextColor(Theme.getColor(Theme.key_text_RedBold));
            b.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 10, 10));
        } else {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            b.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        }
        return b;
    }
}
