package org.telegram.messenger;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

/*
 * Libre — что это за файл:
 * Режим Tor: трафик Telegram идёт через приложение Orbot.
 */

/**
 * Prizma: "Tor mode" — routes Telegram's own traffic through a local Tor SOCKS5 proxy
 * (provided by the Orbot app at 127.0.0.1:9050). Hides the user's IP from Telegram's
 * servers and helps bypass ordinary blocks. Against strict whitelists, Orbot must use
 * obfs4 bridges. This does NOT turn Telegram into a darknet browser.
 */
public class PrizmaTor {

    public static final String ORBOT_PKG = "org.torproject.android";
    public static final String HOST = "127.0.0.1";
    public static final int PORT = 9050;

    public static boolean isActive() {
        if (!SharedConfig.isProxyEnabled()) {
            return false;
        }
        SharedConfig.ProxyInfo p = SharedConfig.currentProxy;
        return p != null && HOST.equals(p.settings.getAddress()) && p.settings.getPort() == PORT;
    }

    public static boolean isOrbotInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(ORBOT_PKG, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static void enable() {
        ProxySettings s = ProxySettings.builder()
                .setType(ProxySettings.Type.SOCKS5)
                .setAddress(HOST)
                .setPort(PORT)
                .setUser("")
                .setPassword("")
                .setSecret("")
                .build();
        SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(s));
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = info;
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    public static void disable() {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", false);
        editor.commit();
        if (SharedConfig.currentProxy != null) {
            ConnectionsManager.setProxySettings(false, SharedConfig.currentProxy.settings);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }

    /** Ask Orbot to start (no-op if not installed or it blocks background start). */
    public static void startOrbot(Context ctx) {
        try {
            Intent i = new Intent("org.torproject.android.intent.action.START");
            i.setPackage(ORBOT_PKG);
            ctx.sendBroadcast(i);
        } catch (Throwable ignore) {
        }
    }

    public static void openOrbotInstall(Context ctx) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ORBOT_PKG));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignore) {
        }
    }
}
