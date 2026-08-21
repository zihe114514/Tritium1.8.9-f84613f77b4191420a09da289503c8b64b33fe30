package com.muoniumplayer.core.ncm.music;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Per-host request headers required to actually fetch a third-party audio stream.
 *
 * <p>Most providers (NetEase, JOOX, QQ) serve their CDN links to any client. Bilibili's
 * {@code *.bilivideo.com} CDN is the exception: it answers HTTP 403 unless the request carries a
 * browser User-Agent <em>and</em> a bilibili Referer, which is why a resolved link could look valid
 * while playback failed at download time.</p>
 */
final class AudioStreamHeaders {

    private static final String BROWSER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String BILIBILI_REFERER = "https://www.bilibili.com";

    private AudioStreamHeaders() {
    }

    /** Returns the headers a stream host requires, or {@code null} when no header is needed. */
    static Map<String, String> forUrl(String url) {
        String host = host(url);
        if (host.isEmpty()) return null;
        if (isBilibiliHost(host)) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", BROWSER_AGENT);
            headers.put("Referer", BILIBILI_REFERER);
            headers.put("Origin", BILIBILI_REFERER);
            return headers;
        }
        return null;
    }

    private static boolean isBilibiliHost(String host) {
        return host.endsWith("bilivideo.com")
                || host.endsWith("bilivideo.cn")
                || host.endsWith("bilibili.com")
                || host.endsWith("akamaized.net");
    }

    private static String host(String url) {
        String value = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) value = value.substring(schemeEnd + 3);
        int slash = value.indexOf('/');
        if (slash >= 0) value = value.substring(0, slash);
        int at = value.indexOf('@');
        if (at >= 0) value = value.substring(at + 1);
        int colon = value.indexOf(':');
        if (colon >= 0) value = value.substring(0, colon);
        return value;
    }
}
