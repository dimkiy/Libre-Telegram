package org.telegram.ui.Components;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.PrizmaConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.CacheControlActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;

public final class PrizmaTraces {
    public static final int SEARCH = 1;
    public static final int RECENT = 2;
    public static final int DRAFTS = 4;
    public static final int CLIPBOARD = 8;
    public static final int CACHE = 16;
    public static final int MEDIA = 32;
    public static final int DEFAULT_MASK = SEARCH | RECENT | CLIPBOARD | CACHE;

    private static final long AUTO_DELAY_MS = 10 * 60 * 1000L;

    private PrizmaTraces() {}

    public static void wipe(int mask, Utilities.Callback<Long> done) {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) continue;
            final int account = a;
            if ((mask & SEARCH) != 0) {
                MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
                    try {
                        MessagesStorage.getInstance(account).getDatabase().executeFast("DELETE FROM search_recent WHERE 1").stepThis().dispose();
                        MessagesStorage.getInstance(account).getDatabase().executeFast("DELETE FROM hashtag_recent_v2 WHERE 1").stepThis().dispose();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            }
            if ((mask & RECENT) != 0) {
                MediaDataController.getInstance(account).clearRecentStickers();
            }
            if ((mask & DRAFTS) != 0) {
                ConnectionsManager.getInstance(account).sendRequest(new TLRPC.TL_messages_clearAllDrafts(), (res, err) ->
                        AndroidUtilities.runOnUIThread(() -> MediaDataController.getInstance(account).clearAllDrafts(true)));
            }
        }
        if ((mask & SEARCH) != 0) {
            forgetSearchOnOpenScreens();
        }
        if ((mask & RECENT) != 0) {
            try {
                Emoji.clearRecentEmoji();
            } catch (Throwable ignore) {
            }
        }
        if ((mask & CLIPBOARD) != 0) {
            try {
                ClipboardManager cm = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    if (Build.VERSION.SDK_INT >= 28) {
                        cm.clearPrimaryClip();
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            long freed = 0;
            if ((mask & CACHE) != 0) {
                File cache = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
                freed += clean(cache, 5) + clean(cache, 4);
            }
            if ((mask & MEDIA) != 0) {
                freed += clean(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_IMAGE), 0);
                freed += clean(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO), 0);
                freed += clean(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_AUDIO), 0);
                freed += clean(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_DOCUMENT), 1);
                freed += clean(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_FILES), 1);
            }
            freed += clean(AndroidUtilities.getLogsDir(), 0);
            final long result = freed;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    FileLoader.getInstance(a).checkCurrentDownloadsFiles();
                }
            }
            AndroidUtilities.runOnUIThread(() -> {
                if ((mask & (CACHE | MEDIA)) != 0) {
                    ImageLoader.getInstance().clearMemory();
                }
                if (done != null) done.run(result);
            });
        });
    }

    private static void forgetSearchOnOpenScreens() {
        try {
            LaunchActivity la = LaunchActivity.instance;
            if (la == null || la.getActionBarLayout() == null) return;
            for (BaseFragment f : new java.util.ArrayList<>(la.getActionBarLayout().getFragmentStack())) {
                if (f instanceof DialogsActivity) {
                    ((DialogsActivity) f).prizmaForgetSearch();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static long clean(File dir, int docType) {
        if (dir == null || !dir.exists()) return 0;
        long before = size(dir);
        try {
            CacheControlActivity.cleanDirJava(dir.getAbsolutePath(), docType, null, null);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return Math.max(0, before - size(dir));
    }

    private static long size(File dir) {
        if (dir == null) return 0;
        if (dir.isFile()) return dir.length();
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File f : files) {
            total += f.isDirectory() ? size(f) : f.length();
        }
        return total;
    }

    private static final Runnable autoWipe = () -> {
        if (PrizmaConfig.tracesAuto() && ApplicationLoader.mainInterfacePaused) {
            wipe(PrizmaConfig.tracesMask() & ~CLIPBOARD, null);
        }
    };

    public static void onAppBackground() {
        AndroidUtilities.cancelRunOnUIThread(autoWipe);
        if (PrizmaConfig.tracesAuto()) {
            AndroidUtilities.runOnUIThread(autoWipe, AUTO_DELAY_MS);
        }
    }

    public static void onAppForeground() {
        AndroidUtilities.cancelRunOnUIThread(autoWipe);
    }

    public static void show(Context context, View behind) {
        final int[] mask = {PrizmaConfig.tracesMask()};
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(16));

        TextView title = new TextView(context);
        title.setText("Стереть следы");
        title.setTextColor(PrizmaGlass.TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        title.setTypeface(AndroidUtilities.bold());
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        TextView sub = new TextView(context);
        sub.setText("Что телефон помнит о том, как вы пользовались Libre. Переписка и музыка остаются.");
        sub.setTextColor(PrizmaGlass.TEXT_2);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        root.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 4, 4, 14));

        final TextView[] cacheSize = new TextView[1];
        final TextView[] mediaSize = new TextView[1];
        addRow(root, context, mask, SEARCH, "История поиска", "чаты и хэштеги, которые вы искали");
        addRow(root, context, mask, RECENT, "Недавние стикеры и эмодзи", "что вы отправляли последним");
        addRow(root, context, mask, CLIPBOARD, "Буфер обмена", "последний скопированный текст");
        cacheSize[0] = addRow(root, context, mask, CACHE, "Временный кэш", "миниатюры и служебные файлы");
        addRow(root, context, mask, DRAFTS, "Черновики", "неотправленный текст во всех чатах — его не вернуть");
        mediaSize[0] = addRow(root, context, mask, MEDIA, "Скачанные фото, видео и файлы", "загрузятся снова, если открыть их в чате");

        LinearLayout autoRow = new LinearLayout(context);
        autoRow.setOrientation(LinearLayout.HORIZONTAL);
        autoRow.setGravity(Gravity.CENTER_VERTICAL);
        autoRow.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(14), AndroidUtilities.dp(4), AndroidUtilities.dp(8));
        LinearLayout autoTexts = new LinearLayout(context);
        autoTexts.setOrientation(LinearLayout.VERTICAL);
        TextView autoTitle = new TextView(context);
        autoTitle.setText("Стирать автоматически");
        autoTitle.setTextColor(PrizmaGlass.TEXT);
        autoTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        autoTexts.addView(autoTitle);
        TextView autoSub = new TextView(context);
        autoSub.setText("отмеченное — через 10 минут после того, как Libre свёрнут (буфер обмена — только кнопкой)");
        autoSub.setTextColor(PrizmaGlass.TEXT_3);
        autoSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        autoTexts.addView(autoSub);
        autoRow.addView(autoTexts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 10, 0));
        final PrizmaGlass.Toggle autoToggle = new PrizmaGlass.Toggle(context);
        autoToggle.setChecked(PrizmaConfig.tracesAuto(), false);
        autoRow.addView(autoToggle, LayoutHelper.createLinear(46, 28, Gravity.CENTER_VERTICAL));
        autoRow.setOnClickListener(v -> {
            boolean nv = !PrizmaConfig.tracesAuto();
            PrizmaConfig.setTracesAuto(nv);
            autoToggle.setChecked(nv, true);
        });
        root.addView(autoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        final TextView erase = new TextView(context);
        erase.setText("Стереть");
        erase.setTextColor(0xff111114);
        erase.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        erase.setTypeface(AndroidUtilities.bold());
        erase.setGravity(Gravity.CENTER);
        erase.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(24)).setGlassTint(0xf2ffffff));
        PrizmaGlass.pressable(erase);
        final boolean[] armed = {false};
        final Runnable disarm = () -> {
            armed[0] = false;
            erase.setText("Стереть");
            erase.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(24)).setGlassTint(0xf2ffffff));
            erase.setTextColor(0xff111114);
        };
        erase.setOnClickListener(v -> {
            if (mask[0] == 0) {
                AndroidUtilities.shakeViewSpring(erase);
                return;
            }
            if (!armed[0]) {
                armed[0] = true;
                erase.setText("Нажмите ещё раз — стереть");
                erase.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(24)).setGlassTint(0xffff5a5f));
                erase.setTextColor(0xffffffff);
                AndroidUtilities.runOnUIThread(disarm, 3000);
                return;
            }
            AndroidUtilities.cancelRunOnUIThread(disarm);
            erase.setEnabled(false);
            erase.setText("Стираем…");
            PrizmaConfig.setTracesMask(mask[0]);
            wipe(mask[0], freed -> {
                erase.setText(freed > 0 ? "Готово · освобождено " + AndroidUtilities.formatFileSize(freed) : "Готово");
                erase.setBackground(new PrizmaGlass.GlassDrawable(AndroidUtilities.dp(24)));
                erase.setTextColor(0xffffffff);
                try { erase.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM); } catch (Throwable ignore) {}
                if (cacheSize[0] != null) cacheSize[0].setText("пусто");
                if ((mask[0] & MEDIA) != 0 && mediaSize[0] != null) mediaSize[0].setText("пусто");
            });
        });
        root.addView(erase, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 14, 0, 0));

        Utilities.globalQueue.postRunnable(() -> {
            File cache = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);
            final long c = size(cache);
            final long m = size(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_IMAGE)) + size(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_VIDEO))
                    + size(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_AUDIO)) + size(FileLoader.checkDirectory(FileLoader.MEDIA_DIR_FILES));
            AndroidUtilities.runOnUIThread(() -> {
                if (cacheSize[0] != null) cacheSize[0].setText("миниатюры и служебные файлы · " + AndroidUtilities.formatFileSize(c));
                if (mediaSize[0] != null) mediaSize[0].setText("около " + AndroidUtilities.formatFileSize(m) + " · загрузятся снова, если открыть в чате");
            });
        });

        new PrizmaGlass.Sheet(context, behind, root).show();
    }

    private static TextView addRow(LinearLayout root, Context context, int[] mask, int bit, String name, String desc) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(context);
        n.setText(name);
        n.setTextColor(PrizmaGlass.TEXT);
        n.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        texts.addView(n);
        TextView d = new TextView(context);
        d.setText(desc);
        d.setTextColor(PrizmaGlass.TEXT_3);
        d.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        texts.addView(d);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        final PrizmaGlass.Check check = new PrizmaGlass.Check(context);
        check.setChecked((mask[0] & bit) != 0, false);
        row.addView(check, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));
        row.setOnClickListener(v -> {
            mask[0] ^= bit;
            check.setChecked((mask[0] & bit) != 0, true);
        });
        root.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return d;
    }
}
