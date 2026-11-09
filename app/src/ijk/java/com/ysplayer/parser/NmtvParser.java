package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内蒙古广播电视台（奔腾融媒）解析器。
 *
 * XXTEA 加解密 API 取流：
 * 1. 固定频道表（22 路：6 省级卫视 + 5 省级专业 + 10 盟市 + 1 蒙古语文化）
 * 2. POST XXTEA-encrypted JSON 到 api-bt.nmtv.cn/broadcast/list
 * 3. 解密响应，按 upstreamId 匹配频道
 * 4. 白名单校验媒体主机（play1-qk.nmtv.cn / livestream-bt.nmtv.cn）
 * 5. 缓存 30 秒，失败复用旧缓存
 */
public class NmtvParser extends BaseParser {

    private static final String TAG = "NmtvParser";
    private static final String DOMAIN = "nmtv.cn";
    private static final String API_URL = "https://api-bt.nmtv.cn/broadcast/list";
    private static final String XXTEA_KEY = "5b28bae827e651b3";
    private static final long CACHE_TTL_MS = 30 * 1000L;
    private static final long RETRY_MS = 5 * 1000L;

    private static class ChannelDef {
        final int upstreamId;
        final String rawName;
        final String displayName;
        String fallbackUrl;
        ChannelDef(int upstreamId, String rawName, String displayName) {
            this.upstreamId = upstreamId; this.rawName = rawName; this.displayName = displayName;
        }
        ChannelDef(int upstreamId, String rawName, String displayName, String fallbackUrl) {
            this(upstreamId, rawName, displayName);
            this.fallbackUrl = fallbackUrl;
        }
    }

    private static final ChannelDef[] CHANNELS = {
        new ChannelDef(3621481, "内蒙古卫视", "内蒙古卫视",
                "http://play1-qk.nmtv.cn/live/1769652018126032.m3u8"),
        new ChannelDef(2315, "内蒙古蒙古语卫视", "内蒙古蒙古语卫视"),
        new ChannelDef(2316, "新闻综合", "内蒙古新闻综合"),
        new ChannelDef(2317, "经济生活", "内蒙古经济生活"),
        new ChannelDef(2318, "少儿频道", "内蒙古少儿"),
        new ChannelDef(2319, "文体娱乐", "内蒙古文体娱乐"),
        new ChannelDef(2320, "农牧频道", "内蒙古农牧"),
        new ChannelDef(2321, "内蒙古蒙古语文化频道", "内蒙古蒙古语文化频道",
                "http://play1-qk.nmtv.cn/live/1769652109096027.m3u8"),
        new ChannelDef(2331, "呼和浩特", "呼和浩特"),
        new ChannelDef(2358, "包头", "包头"),
        new ChannelDef(2355, "乌海", "乌海"),
        new ChannelDef(2351, "赤峰", "赤峰"),
        new ChannelDef(2356, "呼伦贝尔", "呼伦贝尔"),
        new ChannelDef(2357, "兴安盟", "兴安盟"),
        new ChannelDef(2353, "通辽", "通辽"),
        new ChannelDef(2346, "锡林郭勒", "锡林郭勒"),
        new ChannelDef(2354, "乌兰察布", "乌兰察布"),
        new ChannelDef(2349, "鄂尔多斯", "鄂尔多斯"),
        new ChannelDef(2348, "巴彦淖尔", "巴彦淖尔"),
        new ChannelDef(2347, "阿拉善", "阿拉善"),
    };

    private static class StreamEntry {
        String url;
        long expiresAt;
    }

    private static class ListCache {
        final Map<Integer, StreamEntry> streams = new HashMap<>();
        long expiresAt;
        long retryAt;
    }

    private static volatile ListCache sListCache;

    @Override public String getName() { return "奔腾融媒(内蒙古)"; }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    ChannelDef def = findChannel(url);
                    if (def == null) {
                        def = CHANNELS[0];
                        LogUtil.i(TAG + ": channel not recognized, defaulting to " + def.displayName);
                    }

                    String streamUrl = getStreamUrl(def, userAgent);
                    if (streamUrl == null) {
                        callback.onError("内蒙古台未找到可用直播流");
                        return;
                    }

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.nmtv.cn/", userAgent);
                    headers.put("Referer", "https://www.nmtv.cn/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(streamUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed -> " + def.displayName);
                    callback.onSuccess(new ParseResult(streamUrl, def.displayName, "720P", headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("内蒙古台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private ChannelDef findChannel(String url) {
        if (url == null) return CHANNELS[0];
        try {
            java.net.URL u = new java.net.URL(url);
            String query = u.getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("id=")) {
                        int id = Integer.parseInt(part.substring(3));
                        for (ChannelDef def : CHANNELS) {
                            if (def.upstreamId == id) return def;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return CHANNELS[0];
    }

    private String getStreamUrl(ChannelDef def, String userAgent) {
        long now = System.currentTimeMillis();
        ListCache cache = sListCache;
        if (cache != null && (cache.expiresAt > now || cache.retryAt > now)) {
            StreamEntry entry = cache.streams.get(def.upstreamId);
            if (entry != null) return entry.url;
        }

        try {
            ListCache fresh = fetchChannelList(userAgent);
            sListCache = fresh;
            StreamEntry entry = fresh.streams.get(def.upstreamId);
            if (entry != null) return entry.url;
        } catch (Exception e) {
            LogUtil.w(TAG + ": fetch channel list failed: " + e.getMessage());
            if (cache != null) {
                cache.retryAt = now + RETRY_MS;
                StreamEntry entry = cache.streams.get(def.upstreamId);
                if (entry != null) return entry.url;
            }
            if (def.fallbackUrl != null) return def.fallbackUrl;
        }
        return def.fallbackUrl;
    }

    private ListCache fetchChannelList(String userAgent) throws Exception {
        String requestBody = "{\"type\":1,\"size\":100}";
        byte[] encryptedBody = Xxtea.encrypt(
                requestBody.getBytes("UTF-8"), XXTEA_KEY.getBytes("UTF-8"));
        String b64Body = android.util.Base64.encodeToString(
                encryptedBody, android.util.Base64.NO_WRAP);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent != null ? userAgent : UA_MOBILE);
        headers.put("Accept", "application/json");
        headers.put("Client-Type", "web");
        headers.put("Content-Type", "application/json");
        headers.put("Origin", "https://www.nmtv.cn");
        headers.put("Referer", "https://www.nmtv.cn/liveTv");

        HttpFetcher.Response resp = HttpFetcher.post(API_URL, headers, b64Body.getBytes("UTF-8"));
        if (resp.code != 200) throw new Exception("API HTTP " + resp.code);

        String responseText = resp.bodyAsString();
        if (responseText == null || responseText.isEmpty()) {
            throw new Exception("empty API response");
        }
        JSONObject envelope;
        try {
            envelope = new JSONObject(responseText);
            if (envelope.length() == 1 && envelope.has("data")) {
                Object val = envelope.get("data");
                if (val instanceof String) {
                    byte[] decrypted = Xxtea.decrypt(
                            android.util.Base64.decode((String) val, android.util.Base64.NO_WRAP),
                            XXTEA_KEY.getBytes("UTF-8"));
                    envelope = new JSONObject(new String(decrypted, "UTF-8"));
                }
            }
        } catch (Exception e) {
            try {
                byte[] decrypted = Xxtea.decrypt(
                        android.util.Base64.decode(responseText, android.util.Base64.NO_WRAP),
                        XXTEA_KEY.getBytes("UTF-8"));
                envelope = new JSONObject(new String(decrypted, "UTF-8"));
            } catch (Exception e2) {
                throw new Exception("failed to parse API response: " + e2.getMessage());
            }
        }

        if (envelope.optInt("code", -1) != 0 || !envelope.has("data")) {
            throw new Exception("API returned error: " + envelope.optString("msg", ""));
        }

        JSONArray data = envelope.getJSONArray("data");
        long now = System.currentTimeMillis();
        ListCache cache = new ListCache();
        cache.expiresAt = now + CACHE_TTL_MS;

        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            int id = item.optInt("id", 0);
            String title = item.optString("title", "");
            String streamUrl = findStreamUrl(item);

            if (streamUrl != null && isValidMediaUrl(streamUrl)) {
                for (ChannelDef def : CHANNELS) {
                    if (def.upstreamId == id && def.rawName.equals(title)) {
                        StreamEntry entry = new StreamEntry();
                        entry.url = streamUrl;
                        cache.streams.put(id, entry);
                        break;
                    }
                }
            }
        }

        if (cache.streams.isEmpty()) throw new Exception("no valid channels in API response");
        return cache;
    }

    private String findStreamUrl(JSONObject item) {
        String[] keys = {"data.streamUrl", "streamUrl", "url"};
        for (String key : keys) {
            String[] parts = key.split("\\.");
            Object val = item;
            for (String part : parts) {
                if (val instanceof JSONObject) {
                    val = ((JSONObject) val).opt(part);
                } else {
                    val = null;
                    break;
                }
            }
            if (val instanceof String && !((String) val).isEmpty()) {
                return (String) val;
            }
        }
        return item.optString("streamUrl", item.optString("url", ""));
    }

    private boolean isValidMediaUrl(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            java.net.URL url = new java.net.URL(raw);
            String host = url.getHost();
            if (!host.equals("play1-qk.nmtv.cn") && !host.equals("livestream-bt.nmtv.cn")) {
                return false;
            }
            return url.getPath().toLowerCase().endsWith(".m3u8");
        } catch (Exception e) {
            return false;
        }
    }
}