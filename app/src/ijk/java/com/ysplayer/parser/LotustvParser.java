package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LotustvParser extends BaseParser {

    private static final String TAG = "LotustvParser";
    private static final String DOMAIN = "lotustv.mo";
    private static final String HOME_PAGE = "https://www.lotustv.mo/";
    private static final String LIVE_PAGE = "https://www.lotustv.mo/live";
    private static final String REFERER = "https://www.lotustv.mo/";
    private static final String ORIGIN = "https://www.lotustv.mo";
    private static final String EMBED_REFERER = "https://cloud.quklive.com/";
    private static final long ENTRY_TTL_MS = 4 * 60 * 1000;

    private static final Pattern MEDIA_HOST_PATTERN =
            Pattern.compile("^play-[a-z0-9-]+\\.quklive\\.com$");
    private static final Pattern KV_URL_PATTERN =
            Pattern.compile("(?:videoUrl|hlsUrl|playUrl|m3u8Url|m3u8|url|src)"
                    + "\\s*[:=]\\s*[\"']([^\"'\\s<>]+)[\"']");
    private static final Pattern JSON_HLSURL_PATTERN =
            Pattern.compile("\"hlsUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern RAW_STREAM_PATTERN =
            Pattern.compile("(?:https?:)?//[a-z0-9-]+\\.quklive\\.com/live/[^\\s\"'<>\\\\]+");
    private static final Pattern ABS_EMBED_PATTERN =
            Pattern.compile("(?:https?:)?//[a-z0-9-]+\\.quklive\\.com/cloud/a/embed/[A-Za-z0-9_-]+");
    private static final Pattern REL_EMBED_PATTERN =
            Pattern.compile("/cloud/a/embed/[A-Za-z0-9_-]+");

    private static LocalHttpProxy sProxy;

    private static volatile String sCurrentEntryUrl;
    private static volatile long sEntryFetchTime;
    private static final Object sEntryLock = new Object();

    @Override
    public String getName() {
        return "澳门莲花卫视";
    }

    @Override
    public void clearCache() {
        if (sProxy != null) {
            sProxy.stop();
            sProxy = null;
        }
        sCurrentEntryUrl = null;
        sEntryFetchTime = 0;
    }

    @Override
    public boolean canParse(String url) {
        return url != null && url.contains(DOMAIN);
    }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    stopProxy();

                    String ua = resolveUa(userAgent);

                    String hlsUrl = fetchEntryUrl(ua, false);
                    if (hlsUrl == null) {
                        callback.onError("莲花卫视未解析出有效直播地址");
                        return;
                    }

                    // 缓存地址可能已随 auth_key 过期，此时强制重新解析
                    if (!probeStream(hlsUrl, ua)) {
                        hlsUrl = fetchEntryUrl(ua, true);
                        if (hlsUrl == null || !probeStream(hlsUrl, ua)) {
                            callback.onError("莲花卫视直播入口已失效");
                            return;
                        }
                    }

                    LogUtil.i(TAG + ": entry HLS: " + hlsUrl);

                    String m3u8Base = hlsUrl.substring(0, hlsUrl.lastIndexOf('/') + 1);

                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("Referer", REFERER);
                    cdnHeaders.put("Origin", ORIGIN);
                    cdnHeaders.put("User-Agent", ua);
                    cdnHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    sProxy = new LocalHttpProxy(new java.net.URL(hlsUrl).getHost(),
                            hlsUrl, m3u8Base, cdnHeaders);
                    sProxy.setEntryRefresher(new LocalHttpProxy.EntryRefresher() {
                        @Override
                        public String refreshEntryUrl(String expiredUrl) {
                            LogUtil.w(TAG + ": refreshing Lotus TV entry URL...");
                            String newUrl = fetchEntryUrl(ua, true);
                            if (newUrl != null) {
                                LogUtil.i(TAG + ": got new entry: " + newUrl);
                            }
                            return newUrl;
                        }
                    });
                    int port = sProxy.start();
                    String localUrl = sProxy.getLocalUrl();

                    LogUtil.i(TAG + ": proxy started on port " + port);

                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", REFERER);

                    callback.onSuccess(new ParseResult(localUrl,
                            "澳门莲花卫视", "默认", outHeaders, null));

                } catch (IOException e) {
                    LogUtil.e(TAG + ": proxy start failed", e);
                    callback.onError("莲花卫视代理启动失败: " + e.getMessage());
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("莲花卫视解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String fetchEntryUrl(String ua, boolean forceRefresh) {
        if (!forceRefresh) {
            synchronized (sEntryLock) {
                long now = System.currentTimeMillis();
                if (sCurrentEntryUrl != null && (now - sEntryFetchTime) < ENTRY_TTL_MS) {
                    return sCurrentEntryUrl;
                }
            }
        }

        String entry = resolveEntryUrl(ua);
        // 解析整体失败时保留旧缓存（可能只是上游临时 5xx），由调用方探测决定是否可用
        if (entry == null) return sCurrentEntryUrl;

        synchronized (sEntryLock) {
            sCurrentEntryUrl = entry;
            sEntryFetchTime = System.currentTimeMillis();
        }
        return entry;
    }

    private static String resolveEntryUrl(String ua) {
        List<String> pages = new ArrayList<>();
        List<String> embeds = new ArrayList<>();
        List<String> pageStreams = new ArrayList<>();
        List<String> embedStreams = new ArrayList<>();

        pages.add(HOME_PAGE);
        pages.add(LIVE_PAGE);

        for (String page : pages) {
            String html = fetchHtml(page, ua);
            if (html == null) continue;
            for (String s : extractStreamUrls(html, page)) {
                if (!pageStreams.contains(s)) pageStreams.add(s);
            }
            for (String e : extractEmbedUrls(html)) {
                if (!embeds.contains(e)) embeds.add(e);
            }
        }

        // embed 页面里的签名地址是当前最新的，优先级高于站点页面内联的旧地址
        for (String embed : embeds) {
            String html = fetchHtml(embed, ua);
            if (html == null) continue;
            for (String s : extractStreamUrls(html, embed)) {
                if (!embedStreams.contains(s)) embedStreams.add(s);
            }
        }

        List<String> candidates = new ArrayList<>();
        candidates.addAll(embedStreams);
        candidates.addAll(pageStreams);
        for (String c : candidates) {
            if (probeStream(c, ua)) return c;
        }
        return null;
    }

    private static String fetchHtml(String url, String ua) {
        String referer = REFERER;
        try {
            String host = new java.net.URL(url).getHost().toLowerCase();
            if (host.contains("quklive.com")) referer = EMBED_REFERER;
        } catch (Exception ignored) {
        }

        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", ua);
            h.put("Referer", referer);
            h.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            HttpFetcher.Response resp = HttpFetcher.get(url, h);
            if (resp.code != 200) return null;
            return resp.bodyAsString();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> extractStreamUrls(String html, String base) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        // JSON/JS 字符串里 URL 可能被转义为 \/，先还原再统一匹配
        String text = html.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/");

        List<String> rawCandidates = new ArrayList<>();
        Matcher kv = KV_URL_PATTERN.matcher(text);
        while (kv.find()) rawCandidates.add(kv.group(1));
        Matcher json = JSON_HLSURL_PATTERN.matcher(text);
        while (json.find()) rawCandidates.add(json.group(1));
        Matcher raw = RAW_STREAM_PATTERN.matcher(text);
        while (raw.find()) rawCandidates.add(raw.group());

        for (String c : rawCandidates) {
            String full = normalizeUrl(c, base);
            if (full != null && isStreamUrl(full) && !out.contains(full)) {
                out.add(full);
            }
        }
        return out;
    }

    private static List<String> extractEmbedUrls(String html) {
        List<String> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;

        Matcher abs = ABS_EMBED_PATTERN.matcher(html);
        while (abs.find()) {
            String full = normalizeUrl(abs.group(), HOME_PAGE);
            if (full != null && !out.contains(full)) out.add(full);
        }
        Matcher rel = REL_EMBED_PATTERN.matcher(html);
        while (rel.find()) {
            String full = "https://cloud.quklive.com" + rel.group();
            if (!out.contains(full)) out.add(full);
        }
        return out;
    }

    private static String normalizeUrl(String raw, String base) {
        if (raw == null) return null;
        String s = raw.trim()
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u002f", "/");
        if (s.isEmpty()) return null;
        if (s.startsWith("//")) s = "https:" + s;
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            try {
                s = new java.net.URL(new java.net.URL(base), s).toString();
            } catch (Exception e) {
                return null;
            }
        }
        return s;
    }

    private static boolean isStreamUrl(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost();
            String path = u.getPath();
            return host != null && MEDIA_HOST_PATTERN.matcher(host).matches()
                    && path != null && path.startsWith("/live/")
                    && path.toLowerCase().endsWith(".m3u8");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean probeStream(String url, String ua) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", ua);
            h.put("Referer", REFERER);
            h.put("Accept", "application/vnd.apple.mpegurl, */*");
            HttpFetcher.Response resp = HttpFetcher.get(url, h);
            if (resp.code != 200) return false;
            String text = resp.bodyAsString();
            return text != null && text.toUpperCase().contains("#EXTM3U");
        } catch (Exception e) {
            return false;
        }
    }

    private static void stopProxy() {
        if (sProxy != null) {
            sProxy.stop();
            sProxy = null;
        }
    }
}