package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GzstvParser extends BaseParser {

    private static final String TAG = "GzstvParser";
    private static final String DOMAIN = "gzstv.com";

    private static final String API_ORIGIN = "https://api.gzstv.com";
    private static final String PAGE_ORIGIN = "https://www.gzstv.com";
    private static final String MEDIA_HOST = "9bwaz8y2.gzstv.com";
    private static final String MEDIA_PATH_PREFIX = "/live/";

    private static final long TOKEN_REFRESH_MS = 30 * 1000L;
    private static final long TOKEN_HARD_TTL_MS = 2 * 60 * 1000L;

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final String LOGO_BASE =
            "https://mstatic.gzstv.com/media/streams/images/2021/01/04/";

    private static final ChannelDef[] CHANNELS = {
        new ChannelDef("gzstv-satellite",    "ch01", "CH01_lo.m3u8", "贵州卫视",     "贵州卫视",
                LOGO_BASE + "Wv8DYDU_DFQg.jpg"),
        new ChannelDef("gzstv-public",       "ch02", "CH02_lo.m3u8", "公共频道",     "贵州公共",
                LOGO_BASE + "SoNtDtRcuDbR.jpg"),
        new ChannelDef("gzstv-film-arts",    "ch03", "CH03_lo.m3u8", "影视文艺频道", "贵州影视文艺",
                LOGO_BASE + "kq09201cePrU.jpg"),
        new ChannelDef("gzstv-life",         "ch04", "CH04_lo.m3u8", "大众生活频道", "贵州大众生活",
                LOGO_BASE + "TlQgNo6srb2p.jpg"),
        new ChannelDef("gzstv-eco-rural",    "ch05", "CH05_lo.m3u8", "生态·乡村频道", "贵州生态乡村",
                LOGO_BASE + "fGTUM0FqTtzG.jpg"),
        new ChannelDef("gzstv-science-health","ch06", "CH06_lo.m3u8", "科教健康频道", "贵州科教健康",
                LOGO_BASE + "sz3062_R-Ma-.jpg"),
        new ChannelDef("gzstv-economy",      "ch09", "CH09_lo.m3u8", "贵州经济频道", "贵州经济",
                LOGO_BASE + "Y2UfPmdykgir.jpg"),
        new ChannelDef("gzstv-mobile",       "ch13", "CH13_lo.m3u8", "贵州移动电视", "贵州移动电视",
                LOGO_BASE + "b9BSCCj5xEsx.jpg"),
    };

    private static final Map<String, ChannelDef> CHANNEL_BY_SLUG = new HashMap<>();
    static {
        for (ChannelDef ch : CHANNELS) {
            CHANNEL_BY_SLUG.put(ch.slug, ch);
        }
    }

    private static final class ChannelDef {
        final String ref;
        final String slug;
        final String streamName;
        final String rawName;
        final String displayName;
        final String logo;

        ChannelDef(String ref, String slug, String streamName,
                String rawName, String displayName, String logo) {
            this.ref = ref;
            this.slug = slug;
            this.streamName = streamName;
            this.rawName = rawName;
            this.displayName = displayName;
            this.logo = logo;
        }
    }

    private static final class CachedToken {
        String url;
        long refreshAt;
        long hardExpiresAt;
    }

    private static final Map<String, CachedToken> sTokenCache = new ConcurrentHashMap<>();

    @Override
    public String getName() { return "贵州网络广播电视台"; }

    @Override
    public void clearCache() { clearTokenCache(); }

    @Override
    public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String slug = extractSlug(url);
                    if (slug == null) {
                        callback.onError("贵州台：无法解析频道标识");
                        return;
                    }
                    ChannelDef channel = CHANNEL_BY_SLUG.get(slug);
                    if (channel == null) {
                        callback.onError("贵州台：未知频道 " + slug);
                        return;
                    }

                    String m3u8Url = getSignedStream(channel);
                    if (m3u8Url == null || m3u8Url.isEmpty()) {
                        callback.onError("贵州台：获取播放地址失败");
                        return;
                    }

                    Map<String, String> headers = new HashMap<>();
                    headers.put("Origin", PAGE_ORIGIN);
                    headers.put("Referer", PAGE_ORIGIN + "/tv/" + channel.slug);

                    LogUtil.i(TAG + ": " + channel.displayName + " -> " + m3u8Url);
                    callback.onSuccess(new ParseResult(m3u8Url, channel.displayName,
                            "HD", headers, null));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("贵州台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String extractSlug(String url) {
        Matcher m = Pattern.compile("/tv/(ch\\d+)").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String getSignedStream(ChannelDef channel) throws Exception {
        long now = System.currentTimeMillis();
        CachedToken cached = sTokenCache.get(channel.ref);
        if (cached != null && (cached.refreshAt > now || cached.hardExpiresAt > now)) {
            return cached.url;
        }

        String apiUrl = API_ORIGIN + "/v1/tv/" + channel.slug
                + "/?fields=title,stream_url";

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", PAGE_ORIGIN);
        headers.put("Referer", PAGE_ORIGIN + "/tv/" + channel.slug);
        headers.put("User-Agent", UA);

        HttpFetcher.Response resp = HttpFetcher.get(apiUrl, headers);
        if (resp.code != 200) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API HTTP " + resp.code);
        }

        JSONObject json;
        try {
            json = new JSONObject(resp.bodyAsString());
        } catch (Exception e) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回非 JSON");
        }

        String title = json.optString("title", "");
        String streamUrl = json.optString("stream_url", "");

        if (!channel.rawName.equals(title) || streamUrl.isEmpty()) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回无效 (title=" + title + ")");
        }

        String validated = validateStreamUrl(streamUrl, channel.streamName);
        if (validated == null) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回非官方地址");
        }

        CachedToken token = new CachedToken();
        token.url = validated;
        token.refreshAt = now + TOKEN_REFRESH_MS;
        token.hardExpiresAt = now + TOKEN_HARD_TTL_MS;
        sTokenCache.put(channel.ref, token);

        return validated;
    }

    private static String validateStreamUrl(String raw, String expectedStreamName) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            java.net.URL url = new java.net.URL(raw);
            if (!"https".equals(url.getProtocol())) return null;
            if (!MEDIA_HOST.equals(url.getHost())) return null;
            if (!url.getPath().startsWith(MEDIA_PATH_PREFIX)) return null;
            if (!url.getPath().toLowerCase().endsWith(".m3u8")) return null;
            if (expectedStreamName != null
                    && !url.getPath().equals(MEDIA_PATH_PREFIX + expectedStreamName)) {
                return null;
            }
            String txSecret = getQueryParam(url.getQuery(), "txSecret");
            String txTime = getQueryParam(url.getQuery(), "txTime");
            if (txSecret == null || !txSecret.matches("(?i)^[a-f0-9]{32}$")) return null;
            if (txTime == null || !txTime.matches("(?i)^[a-f0-9]{8,16}$")) return null;
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static String getQueryParam(String query, String key) {
        if (query == null || key == null) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0 && key.equals(pair.substring(0, idx))) {
                return pair.substring(idx + 1);
            }
        }
        return null;
    }

    /**
     * 清除 token 缓存（可在 App 启动或切换网络时调用）。
     */
    public static void clearTokenCache() {
        sTokenCache.clear();
        LogUtil.d(TAG + ": token cache cleared");
    }
}