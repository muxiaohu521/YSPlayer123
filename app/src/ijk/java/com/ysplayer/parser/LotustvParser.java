package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LotustvParser extends BaseParser {

    private static final String TAG = "LotustvParser";
    private static final String DOMAIN = "lotustv.mo";
    private static final String LIVE_PAGE = "https://www.lotustv.mo/live";
    private static final long ENTRY_TTL_MS = 4 * 60 * 1000;

    private static final Pattern QPLAYER_PATTERN =
            Pattern.compile("new\\s+QPlayer\\s*\\(\\s*\\{([\\s\\S]*?)\\}\\s*\\)");
    private static final Pattern URL_LINE_PATTERN =
            Pattern.compile("url:\\s*[\"']([^\"']+)[\"']");
    private static final Pattern MEDIA_HOST_PATTERN =
            Pattern.compile("^play-[a-z0-9-]+\\.quklive\\.com$");

    private static LocalHttpProxy sProxy;

    private static volatile String sCurrentEntryUrl;
    private static volatile long sEntryFetchTime;
    private static final Object sEntryLock = new Object();

    @Override
    public String getName() {
        return "澳门莲花卫视";
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

                    Map<String, String> pageHeaders = defaultHeadersWithUA(LIVE_PAGE, userAgent);

                    String hlsUrl = fetchEntryUrl(userAgent, pageHeaders, false);
                    if (hlsUrl == null) {
                        callback.onError("莲花卫视页面未找到QPlayer播放地址");
                        return;
                    }

                    LogUtil.i(TAG + ": entry HLS: " + hlsUrl);

                    java.net.URL parsed = new java.net.URL(hlsUrl);
                    String cdnHost = parsed.getHost();
                    String m3u8Base = hlsUrl.substring(0,
                            hlsUrl.lastIndexOf('/') + 1);

                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("Referer", "https://www.lotustv.mo/");
                    cdnHeaders.put("User-Agent", userAgent != null && !userAgent.isEmpty()
                            ? userAgent : UA_MOBILE);
                    cdnHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    sProxy = new LocalHttpProxy(cdnHost, hlsUrl, m3u8Base, cdnHeaders);
                    sProxy.setEntryRefresher(new LocalHttpProxy.EntryRefresher() {
                        @Override
                        public String refreshEntryUrl(String expiredUrl) {
                            LogUtil.w(TAG + ": refreshing Lotus TV entry URL...");
                            String newUrl = fetchEntryUrl(userAgent, pageHeaders, true);
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
                    outHeaders.put("Referer", "https://www.lotustv.mo/");

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

    private static String fetchEntryUrl(String userAgent,
                                        Map<String, String> pageHeaders,
                                        boolean forceRefresh) {
        if (!forceRefresh) {
            synchronized (sEntryLock) {
                long now = System.currentTimeMillis();
                if (sCurrentEntryUrl != null && (now - sEntryFetchTime) < ENTRY_TTL_MS) {
                    return sCurrentEntryUrl;
                }
            }
        }

        try {
            HttpFetcher.Response resp = HttpFetcher.get(LIVE_PAGE, pageHeaders);
            if (resp.code != 200) {
                LogUtil.e(TAG + ": live page HTTP " + resp.code);
                return sCurrentEntryUrl;
            }

            String html = resp.bodyAsString();
            String hlsUrl = extractPlayerUrl(html);
            if (hlsUrl == null) {
                LogUtil.e(TAG + ": QPlayer URL extraction failed");
                return sCurrentEntryUrl;
            }

            synchronized (sEntryLock) {
                sCurrentEntryUrl = hlsUrl;
                sEntryFetchTime = System.currentTimeMillis();
            }
            return hlsUrl;
        } catch (IOException e) {
            LogUtil.e(TAG + ": entry fetch failed: " + e.getMessage());
            return sCurrentEntryUrl;
        }
    }

    private static void stopProxy() {
        if (sProxy != null) {
            sProxy.stop();
            sProxy = null;
        }
    }

    static String extractPlayerUrl(String html) {
        if (html == null || html.isEmpty()) return null;

        Matcher playerMatcher = QPLAYER_PATTERN.matcher(html);
        if (!playerMatcher.find()) {
            LogUtil.w(TAG + ": QPlayer config not found in HTML");
            return null;
        }

        String playerBlock = playerMatcher.group(1);
        if (playerBlock == null || playerBlock.isEmpty()) return null;

        String cleaned = playerBlock.replaceAll("/\\*[\\s\\S]*?\\*/", "");

        String[] lines = cleaned.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;

            Matcher urlMatcher = URL_LINE_PATTERN.matcher(trimmed);
            if (urlMatcher.find()) {
                String raw = urlMatcher.group(1);
                String fullUrl = raw.startsWith("//") ? "https:" + raw : raw;
                fullUrl = fullUrl.trim();
                if (fullUrl.isEmpty()) continue;

                try {
                    java.net.URL parsed = new java.net.URL(fullUrl);
                    String host = parsed.getHost();
                    if (host != null && MEDIA_HOST_PATTERN.matcher(host).matches()
                            && parsed.getPath() != null
                            && parsed.getPath().startsWith("/live/")
                            && parsed.getPath().endsWith(".m3u8")) {
                        return fullUrl;
                    }
                } catch (Exception e) {
                    LogUtil.w(TAG + ": invalid URL format: " + fullUrl);
                }
            }
        }

        LogUtil.w(TAG + ": no valid QPlayer url line found in player block");
        return null;
    }
}