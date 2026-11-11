package org.telegram.messenger;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.tgnet.TLRPC;

public final class PrizmaSendErrors {
    private static final long REPEAT_AFTER_MS = 4000;

    private static String lastText;
    private static long lastAt;

    private PrizmaSendErrors() {}

    public static void server(TLRPC.TL_error error) {
        if (error == null || error.text == null || error.text.startsWith("ALLOW_PAYMENT_REQUIRED_")) return;
        show(explain(error.text), error.text);
    }

    public static void upload() {
        show("Файл не загрузился на сервер Telegram. Проверьте интернет, VPN или прокси и отправьте ещё раз", "upload");
    }

    public static void failure(Throwable e) {
        if (e == null) return;
        String detail = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        show("Отправка сорвалась внутри приложения (" + detail + ")", detail);
    }

    public static String last() {
        return ApplicationLoader.applicationContext.getSharedPreferences("libre_send", Context.MODE_PRIVATE).getString("last", "");
    }

    private static String explain(String code) {
        if (code.startsWith("FLOOD_WAIT_") || code.startsWith("FLOOD_PREMIUM_WAIT_") || code.startsWith("SLOWMODE_WAIT_")) {
            return "Telegram просит подождать " + code.replaceAll("[^0-9]", "") + " с перед следующей отправкой";
        }
        if (code.startsWith("FILE_REFERENCE_") || code.equals("MEDIA_EMPTY") || code.equals("FILE_ID_INVALID")) {
            return "Ссылка на файл устарела. Откройте исходное сообщение с файлом и отправьте его заново";
        }
        if (code.startsWith("CHAT_SEND_") || code.equals("CHAT_WRITE_FORBIDDEN") || code.equals("USER_BANNED_IN_CHANNEL") || code.equals("CHAT_RESTRICTED")) {
            return "В этом чате вам нельзя отправлять такие сообщения";
        }
        if (code.equals("PEER_FLOOD")) {
            return "Telegram временно ограничил ваш аккаунт за слишком частые отправки";
        }
        if (code.startsWith("FROZEN_")) {
            return "Telegram заморозил этот аккаунт, отправка недоступна";
        }
        if (code.contains("PREMIUM")) {
            return "Для такой отправки Telegram требует Premium";
        }
        if (code.startsWith("FILE_PART") || code.equals("FILE_PARTS_INVALID") || code.equals("MEDIA_INVALID") || code.startsWith("PHOTO_") || code.startsWith("IMAGE_PROCESS")) {
            return "Telegram не принял файл (" + code + "). Попробуйте отправить его как файл, без сжатия";
        }
        return "Telegram отклонил отправку: " + code;
    }

    private static void show(String text, String raw) {
        AndroidUtilities.runOnUIThread(() -> {
            long now = SystemClock.elapsedRealtime();
            if (text.equals(lastText) && now - lastAt < REPEAT_AFTER_MS) return;
            lastText = text;
            lastAt = now;
            try {
                ApplicationLoader.applicationContext.getSharedPreferences("libre_send", Context.MODE_PRIVATE).edit()
                        .putString("last", raw + " · " + new java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(new java.util.Date())).apply();
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_LONG).show();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}
