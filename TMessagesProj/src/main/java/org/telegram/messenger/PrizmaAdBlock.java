package org.telegram.messenger;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;

public final class PrizmaAdBlock {
    private static final HashSet<String> HOSTS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "pagead2.googlesyndication.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com", "imasdk.googleapis.com", "app-measurement.com",
            "an.yandex.ru", "mc.yandex.ru", "yandexadexchange.net", "adfox.ru", "ads.adfox.ru", "awaps.yandex.net", "bs.yandex.ru",
            "ad.mail.ru", "top-fwz1.mail.ru", "r3.mail.ru", "mytarget.ru", "ads.vk.com", "ad.vk.com", "adriver.ru", "begun.ru",
            "tns-counter.ru", "mediametrics.ru", "adhigh.net", "otm-r.com", "buzzoola.com", "relap.io", "smi2.ru", "smi2.net",
            "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "adnxs.com", "rubiconproject.com", "pubmatic.com", "openx.net",
            "amazon-adsystem.com", "scorecardresearch.com", "quantserve.com", "moatads.com", "smartadserver.com", "adsrvr.org",
            "bidswitch.net", "33across.com", "sharethrough.com", "teads.tv", "media.net", "yieldmo.com", "adform.net", "adroll.com",
            "chartbeat.com", "chartbeat.net", "mgid.com", "propellerads.com", "popads.net", "popcash.net", "exoclick.com",
            "trafficjunky.net", "adsterra.com", "hilltopads.net", "juicyads.com", "revcontent.com", "zedo.com", "casalemedia.com",
            "contextweb.com", "lijit.com", "sovrn.com", "indexww.com", "spotxchange.com", "springserve.com", "unrulymedia.com",
            "hotjar.com", "mouseflow.com", "fullstory.com", "inspectlet.com", "clarity.ms", "bat.bing.com", "ads.linkedin.com",
            "connect.facebook.net", "an.facebook.com", "ads-twitter.com", "static.ads-twitter.com", "analytics.tiktok.com",
            "ads.tiktok.com", "unityads.unity3d.com", "applovin.com", "applvn.com", "vungle.com", "chartboost.com", "inmobi.com",
            "adcolony.com", "ironsrc.com", "supersonicads.com", "startappservice.com", "mopub.com", "tapjoy.com", "admob.com",
            "onesignal.com", "branch.io", "adjust.com", "appsflyer.com", "kochava.com", "liftoff.io", "smaato.net", "bidmachine.io",
            "adskeeper.com", "adskeeper.co.uk", "directadvert.ru", "marketgid.com", "tovarro.com", "lentainform.com", "advertur.ru"
    ));

    private PrizmaAdBlock() {
    }

    public static boolean blocked(String url) {
        if (url == null || !PrizmaConfig.adBlock()) return false;
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.US);
            while (true) {
                if (HOSTS.contains(host)) return true;
                int dot = host.indexOf('.');
                if (dot < 0 || host.indexOf('.', dot + 1) < 0) return false;
                host = host.substring(dot + 1);
            }
        } catch (Throwable e) {
            return false;
        }
    }

    public static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }
}
