package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.PrizmaCloud;
import org.telegram.messenger.PrizmaNet;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;

public final class PrizmaUpdater {
    private static final long CHECK_EVERY = 3 * 3600_000L;
    private static final long SNOOZE = 24 * 3600_000L;
    private static final DispatchQueue queue = new DispatchQueue("prizmaUpdate");

    private static boolean checking, showing;

    private PrizmaUpdater() {}

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("prizma_update", Context.MODE_PRIVATE);
    }

    public static void maybeCheck(Activity activity) {
        if (System.currentTimeMillis() - prefs().getLong("checked_at", 0) < CHECK_EVERY) return;
        check(activity, false);
    }

    public static void checkNow(Activity activity) {
        check(activity, true);
    }

    private static boolean direct;

    private static void check(Activity activity, boolean manual) {
        if (checking || showing || activity == null) return;
        String host = PrizmaCloud.host();
        if (host.isEmpty()) {
            if (manual) Toast.makeText(activity, "Сервер Libre не подключён", Toast.LENGTH_SHORT).show();
            return;
        }
        checking = true;
        queue.postRunnable(() -> {
            PrizmaNet.Response r = PrizmaNet.request("GET", host + "/v1/update", null, null, 15000, direct);
            JSONObject info = null;
            try {
                if (r.code == 200 && r.body != null) info = new JSONObject(r.body);
            } catch (Throwable ignore) {
            }
            final JSONObject result = info;
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (r.code == 200) prefs().edit().putLong("checked_at", System.currentTimeMillis()).apply();
                if (r.code == -1 && !direct && PrizmaNet.proxyBlocks()) {
                    if (manual) askDirect(activity);
                    return;
                }
                if (result == null || !result.has("version")) {
                    if (manual) Toast.makeText(activity, r.code == 200 ? "У вас последняя версия Libre" : "Не удалось связаться с сервером Libre", Toast.LENGTH_SHORT).show();
                    return;
                }
                String version = result.optString("version");
                if (compare(version, BuildVars.BUILD_VERSION_STRING) <= 0) {
                    if (manual) Toast.makeText(activity, "У вас последняя версия Libre", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!manual && System.currentTimeMillis() < prefs().getLong("snooze_" + version, 0)) return;
                if (activity.isFinishing()) return;
                show(activity, result);
            });
        });
    }

    private static void askDirect(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        new org.telegram.ui.ActionBar.AlertDialog.Builder(activity)
                .setTitle("Включён MTProto-прокси")
                .setMessage("Через MTProto-прокси сервер Libre недоступен — поэтому обновление не проверяется. "
                        + "Можно проверить и скачать его напрямую, без прокси. Если Telegram у вас работает только с прокси, "
                        + "скачайте новую версию из канала Libre.")
                .setPositiveButton("Напрямую", (d, w) -> {
                    direct = true;
                    check(activity, true);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    static int compare(String a, String b) {
        String[] x = a == null ? new String[0] : a.split("\\.");
        String[] y = b == null ? new String[0] : b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void show(Activity activity, JSONObject info) {
        showing = true;
        final String version = info.optString("version");
        final String url = info.optString("url");
        final String sha = info.optString("sha256", "");
        final long size = info.optLong("size", 0);
        final Context ctx = activity;

        LinearLayout v = new LinearLayout(ctx);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(22), AndroidUtilities.dp(14));

        TextView title = new TextView(ctx);
        title.setText("Доступно обновление");
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        title.setTypeface(AndroidUtilities.bold());
        v.addView(title);
        TextView sub = new TextView(ctx);
        sub.setText("Libre " + version + (size > 0 ? " · " + Math.max(1, Math.round(size / 1048576f)) + " МБ" : "") + " · у вас " + BuildVars.BUILD_VERSION_STRING);
        sub.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        v.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 12));

        JSONArray notes = info.optJSONArray("notes");
        if (notes != null) {
            for (int i = 0; i < notes.length() && i < 10; i++) {
                LinearLayout row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                View dot = new View(ctx);
                GradientDrawable d = new GradientDrawable();
                d.setShape(GradientDrawable.OVAL);
                d.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
                dot.setBackground(d);
                row.addView(dot, LayoutHelper.createLinear(6, 6, Gravity.TOP, 0, 8, 12, 0));
                TextView t = new TextView(ctx);
                t.setText(notes.optString(i));
                t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                row.addView(t, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
                v.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));
            }
        }

        final FrameLayout track = new FrameLayout(ctx);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(AndroidUtilities.dp(2));
        tb.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.1f));
        track.setBackground(tb);
        final View fill = new View(ctx);
        GradientDrawable fb = new GradientDrawable();
        fb.setCornerRadius(AndroidUtilities.dp(2));
        fb.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        fill.setBackground(fb);
        track.addView(fill, new FrameLayout.LayoutParams(0, LayoutHelper.MATCH_PARENT));
        track.setVisibility(View.GONE);
        v.addView(track, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 0, 8, 0, 4));

        final TextView button = new TextView(ctx);
        button.setText("Обновить");
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setBackground(Theme.AdaptiveRipple.filledRectByKey(Theme.key_featuredStickers_addButton, 10));
        v.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 10, 0, 0));
        final TextView later = new TextView(ctx);
        later.setText("Позже");
        later.setGravity(Gravity.CENTER);
        later.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        later.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        later.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 10, 10));
        v.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 4, 0, 0));

        BottomSheet.Builder b = new BottomSheet.Builder(ctx, false);
        b.setCustomView(v);
        final BottomSheet sheet = b.create();
        sheet.setOnDismissListener(di -> showing = false);
        later.setOnClickListener(x -> {
            prefs().edit().putLong("snooze_" + version, System.currentTimeMillis() + SNOOZE).apply();
            sheet.dismiss();
        });

        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
        final File apk = new File(dir, "libre-" + version + ".apk");
        final boolean[] busy = {false};
        button.setOnClickListener(x -> {
            if (busy[0]) return;
            if (apk.exists() && verify(apk, sha) == null) {
                install(activity, apk);
                return;
            }
            busy[0] = true;
            button.setText("Скачиваем…");
            button.setAlpha(0.7f);
            track.setVisibility(View.VISIBLE);
            dir.mkdirs();
            File[] old = dir.listFiles((d2, name) -> name.startsWith("libre-") && name.endsWith(".apk") && !name.equals(apk.getName()));
            if (old != null) for (File f : old) f.delete();
            queue.postRunnable(() -> {
                boolean ok = PrizmaNet.download(url, apk, (size > 0 ? size : 200L * 1024 * 1024) + 1024 * 1024, 30000,
                        p -> AndroidUtilities.runOnUIThread(() -> {
                            int w = track.getWidth();
                            fill.getLayoutParams().width = (int) (w * p);
                            fill.requestLayout();
                        }), direct);
                final String problem = ok ? verify(apk, sha) : "Не удалось скачать — проверьте интернет";
                AndroidUtilities.runOnUIThread(() -> {
                    busy[0] = false;
                    button.setAlpha(1f);
                    if (problem != null) {
                        apk.delete();
                        track.setVisibility(View.GONE);
                        button.setText("Повторить");
                        Toast.makeText(ctx, problem, Toast.LENGTH_LONG).show();
                        return;
                    }
                    fill.getLayoutParams().width = track.getWidth();
                    fill.requestLayout();
                    button.setText("Установить");
                    install(activity, apk);
                });
            });
        });
        sheet.show();
    }

    private static void install(Activity activity, File apk) {
        try {
            AndroidUtilities.openForView(apk, "Libre.apk", "application/vnd.android.package-archive", activity, null, false);
        } catch (Throwable e) {
            Toast.makeText(activity, "Не удалось открыть установщик", Toast.LENGTH_LONG).show();
        }
    }

    private static String verify(File apk, String sha256) {
        try {
            if (sha256 != null && !sha256.isEmpty() && !sha256.equalsIgnoreCase(sha256Of(apk))) {
                return "Файл повреждён при скачивании — попробуйте ещё раз";
            }
            PackageManager pm = ApplicationLoader.applicationContext.getPackageManager();
            String pkg = ApplicationLoader.applicationContext.getPackageName();
            PackageInfo candidate, installed;
            if (Build.VERSION.SDK_INT >= 28) {
                candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
                installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
            } else {
                candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
                installed = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            }
            if (candidate == null || !pkg.equals(candidate.packageName)) {
                return "Это не обновление Libre";
            }
            byte[] theirs = signerOf(candidate), ours = signerOf(installed);
            if (theirs == null || ours == null || !Arrays.equals(theirs, ours)) {
                return "Подпись файла не совпадает с Libre — установка отменена";
            }
            return null;
        } catch (Throwable e) {
            return "Не удалось проверить файл обновления";
        }
    }

    @SuppressWarnings("deprecation")
    private static byte[] signerOf(PackageInfo info) {
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            sigs = info.signingInfo.getApkContentsSigners();
        } else {
            sigs = info.signatures;
        }
        if (sigs == null || sigs.length == 0) return null;
        try {
            return MessageDigest.getInstance("SHA-256").digest(sigs[0].toByteArray());
        } catch (Throwable e) {
            return null;
        }
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
