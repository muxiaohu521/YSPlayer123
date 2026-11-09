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

    protected static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; SM-G9600) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/97.0.4692.98 Mobile Safari/537.36";

    private static final Pattern M3U8_PATTERN =
            Pattern.compile("(https?://[^\"'\\s<>]{5,}\\.m3u8[^\"'\\s<>]*)",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern JSON_OBJECT_PATTERN =
            Pattern.compile("\\{[^{}]*(?:\"[^\"]*\"\\s*:\\s*\"[^\"]*\\.m3u8[^\"]*\"[^{}]*)*\\}");

    protected static void execute(Runnable task) {
        sExecutor.execute(task);
    }

    protected Map<String, String> defaultHeaders(String referer) {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", referer);
        h.put("User-Agent", UA_MOBILE);
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return h;
    }

    protected Map<String, String> defaultHeadersWithUA(String referer, String userAgent) {
        Map<String, String> h = new HashMap<>();
        h.put("Referer", referer);
        h.put("User-Agent", userAgent != null && !userAgent.isEmpty() ? userAgent : UA_MOBILE);
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