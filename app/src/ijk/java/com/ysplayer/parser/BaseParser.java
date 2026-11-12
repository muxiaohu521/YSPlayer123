package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class BaseParser implements ChannelParser {

    private static final String TAG = "BaseParser";
    private static final ExecutorService sExecutor = Executors.newFixedThreadPool(4);

    /**
     * 统一 UA 解析：UA 完全由项目 UA 机制（调用方传入的 userAgent）决定，
     * 解析逻辑不保留任何兜底 UA。调用方（UserAgentPreset.fromId）保证传入值非空。
     */
    protected static String resolveUa(String userAgent) {
        return userAgent;
    }

    private static final Pattern M3U8_PATTERN =
            Pattern.compile("(https?://[^\"'\\s<>]{5,}\\.m3u8[^\"'\\s<>]*)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\"[^\"]*\"\\s*:\\s*\"[^\"]*\\.m3u8[^\"]*\"[^{}]*)*\\}");

    protected static void execute(Runnable task) {
        sExecutor.execute(task);
    }

    protected Map<String, String> defaultHeadersWithUA(String referer, String userAgent) {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", referer);
        h.put("User-Agent", resolveUa(userAgent));
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return h;
    }

    protected String extractM3u8FromHtml(String html) {
        if (html == null || html.isEmpty()) return null;

        Matcher m = M3U8_PATTERN.matcher(html);
        if (m.find()) {
            String url = m.group(1);
            url = cleanUrl(url);
            LogUtil.i(TAG + ": found m3u8 in HTML: " + url);
            return url;
        }
        return null;
    }

    protected List<String> extractAllM3u8FromHtml(String html) {
        List<String> urls = new ArrayList<>();
        if (html == null || html.isEmpty()) return urls;

        Matcher m = M3U8_PATTERN.matcher(html);
        while (m.find()) {
            String url = cleanUrl(m.group(1));
            if (!urls.contains(url)) {
                urls.add(url);
            }
        }
        return urls;
    }

    protected String extractJsonValue(String json, String key) {
        if (json == null || key == null) return null;
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        Matcher m = Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    protected String cleanUrl(String url) {
        if (url == null) return null;
        url = url.replace("\\/", "/");
        int idx = url.indexOf('"');
        if (idx > 0) url = url.substring(0, idx);
        idx = url.indexOf('\'');
        if (idx > 0) url = url.substring(0, idx);
        idx = url.indexOf(',');
        if (idx > 0) url = url.substring(0, idx);
        return url.trim();
    }

    protected String extractQueryParam(String url, String param) {
        if (url == null || param == null) return null;
        String pattern = "[?&]" + param + "=([^&]*)";
        Matcher m = Pattern.compile(pattern).matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    protected void executeParse(final String url, final String userAgent,
            final Runnable parseTask) {
        execute(new Runnable() {
            @Override
            public void run() {
                try {
                    parseTask.run();
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse error for " + url, e);
                }
            }
        });
    }
}