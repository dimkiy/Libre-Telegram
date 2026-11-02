package org.telegram.messenger;

import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;

/*
 * Libre — что это за файл:
 * Обычные интернет-запросы приложения (обложки, тексты песен) — через прокси Telegram, если он включён.
 */

/**
 * Prizma: plain HTTPS requests made by the client itself (album covers, lyrics).
 *
 * They used to go straight to the internet even with a proxy / Tor (Orbot) enabled in Telegram,
 * exposing the real IP and a "Prizma" user agent. Now:
 *  - with a SOCKS5 proxy enabled in Telegram they go through the same proxy;
 *  - with an MTProto proxy (which can't carry HTTP) they are not sent at all;
 *  - the user agent is a generic browser one, so the client is not fingerprinted.
 */
public final class PrizmaNet {

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private PrizmaNet() {}

    /** Sentinel: a proxy is on, but it can't carry this request — don't leak it around the proxy. */
    private static final Proxy BLOCKED = new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("blocked.invalid", 1));

    private static volatile boolean authenticatorSet;

    private static Proxy currentProxy() {
        try {
            if (!SharedConfig.isProxyEnabled() || SharedConfig.currentProxy == null) {
                return Proxy.NO_PROXY;
            }
            final ProxySettings settings = SharedConfig.currentProxy.settings;
            if (settings.getType() != ProxySettings.Type.SOCKS5) {
                return BLOCKED;
            }
            final String user = settings.getUser();
            final String pass = settings.getPassword();
            if (user != null && user.length() > 0 && !authenticatorSet) {
                authenticatorSet = true;
                Authenticator.setDefault(new Authenticator() {
                    @Override
                    protected PasswordAuthentication getPasswordAuthentication() {
                        try {
                            if (SharedConfig.currentProxy != null) {
                                ProxySettings s = SharedConfig.currentProxy.settings;
                                if (s.getType() == ProxySettings.Type.SOCKS5 && s.getAddress() != null && s.getAddress().equalsIgnoreCase(getRequestingHost())) {
                                    String p = s.getPassword();
                                    return new PasswordAuthentication(s.getUser(), (p == null ? "" : p).toCharArray());
                                }
                            }
                        } catch (Throwable ignore) {
                        }
                        return null;
                    }
                });
            }
            return new Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(settings.getAddress(), settings.getPort()));
        } catch (Throwable e) {
            return BLOCKED;
        }
    }

    /** GET a small text/JSON resource; null on any error, non-200 status, or when the proxy can't carry it. */
    public static String get(String url, int timeoutMs) {
        final Proxy proxy = currentProxy();
        if (proxy == BLOCKED) {
            return null;
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Result of {@link #request}: HTTP status (-1 = not sent / network error) and the body text. */
    public static final class Response {
        public final int code;
        public final String body;
        public final byte[] bytes; // the same body, untouched (for binary answers)

        Response(int code, String body) {
            this(code, body, null);
        }

        Response(int code, String body, byte[] bytes) {
            this.code = code;
            this.body = body;
            this.bytes = bytes;
        }
    }

    /** True when Telegram runs through an MTProto proxy — our HTTPS requests can't go through it. */
    public static boolean proxyBlocks() {
        return currentProxy() == BLOCKED;
    }

    /** Any method, optional headers and body. Same proxy rules as {@link #get}. Call off the UI thread. */
    public static Response request(String method, String url, java.util.Map<String, String> headers, byte[] body, int timeoutMs) {
        return request(method, url, headers, body, timeoutMs, false);
    }

    /**
     * {@code direct}: the person agreed to go around an MTProto proxy for this one thing (an update) —
     * the request goes straight to the internet.
     */
    public static Response request(String method, String url, java.util.Map<String, String> headers, byte[] body, int timeoutMs, boolean direct) {
        final Proxy proxy = direct ? Proxy.NO_PROXY : currentProxy();
        if (proxy == BLOCKED) {
            return new Response(-1, null);
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                java.io.OutputStream os = conn.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = conn.getResponseCode();
            java.io.InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = null;
            byte[] raw = null;
            if (in != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                raw = out.toByteArray();
                text = new String(raw, "UTF-8");
            }
            return new Response(code, text, raw);
        } catch (Throwable e) {
            return new Response(-1, null);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Downloads a file (up to {@code maxBytes}) into {@code dst}; false on any error. Off the UI thread. */
    public static boolean download(String url, java.io.File dst, long maxBytes, int timeoutMs) {
        return download(url, dst, maxBytes, timeoutMs, null);
    }

    /** Same, reporting progress 0..1 (when the size is known). */
    public static boolean download(String url, java.io.File dst, long maxBytes, int timeoutMs, Utilities.Callback<Float> progress) {
        return download(url, dst, maxBytes, timeoutMs, progress, false);
    }

    public static boolean download(String url, java.io.File dst, long maxBytes, int timeoutMs, Utilities.Callback<Float> progress, boolean direct) {
        final Proxy proxy = direct ? Proxy.NO_PROXY : currentProxy();
        if (proxy == BLOCKED) {
            return false;
        }
        HttpURLConnection conn = null;
        java.io.File part = new java.io.File(dst.getPath() + ".part");
        try {
            conn = (HttpURLConnection) new URL(url).openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            long total = 0;
            final long length = conn.getContentLength();
            long lastReport = 0;
            try (java.io.InputStream in = conn.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    total += r;
                    if (total > maxBytes) {
                        throw new java.io.IOException("too big");
                    }
                    out.write(buf, 0, r);
                    if (progress != null && length > 0 && total - lastReport > 256 * 1024) {
                        lastReport = total;
                        progress.run(Math.min(1f, total / (float) length));
                    }
                }
            }
            if (dst.exists()) dst.delete();
            return part.renameTo(dst);
        } catch (Throwable e) {
            part.delete();
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** True when an image URL may be loaded directly (no proxy, so nothing to bypass). */
    public static boolean directLoadAllowed() {
        return currentProxy() == Proxy.NO_PROXY;
    }
}
