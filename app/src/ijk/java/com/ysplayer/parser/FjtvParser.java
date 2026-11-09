package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FjtvParser extends BaseParser {

    private static final String TAG = "FjtvParser";
    private static final String DOMAIN = "fjtv.net";

    private static final String API_URL =
            "https://live.fjtv.net/m2o/channel/channel_info.php";
    private static final String API_KEY = "877a9ba7a98f75b90a9d49f53f15a858";
    private static final String API_SECRET = "68a04b8177bdc9e5e16a89e6777a7b66";
    private static final String API_VERSION = "1.0.0";

    private static final Set<String> MEDIA_HOSTS = new HashSet<>();
    static {
        MEDIA_HOSTS.add("live1-fuyun.fjtv.net");
        MEDIA_HOSTS.add("live2-fuyun.fjtv.net");
    }

    private static final long EXPIRY_GUARD_MS = 60 * 1000L;
    private static final long REQUEST_TIMEOUT_MS = 10 * 1000L;

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final class ChannelDef {
        final String id;
        final String rawName;
        final String displayName;
        final String path;

        ChannelDef(String id, String rawName, String displayName, String path) {
            this.id = id;
            this.rawName = rawName;
            this.displayName = displayName;
            this.path = path;
        }
    }

    private static final ChannelDef[] PROVINCE_CHANNELS = {
        new ChannelDef("665248990102917120", "综合频道",     "福建综合",     "zhpd/hd"),
        new ChannelDef("665248966136664064", "东南卫视",     "东南卫视",     "dnpd/hd"),
        new ChannelDef("665248914378952704", "新闻频道",     "福建新闻",     "xwpd/hd"),
        new ChannelDef("665248752898248704", "文旅·体育频道", "福建文旅体育", "dspd/hd"),
        new ChannelDef("665248553475870720", "少儿频道",     "福建少儿",     "child/hd"),
        new ChannelDef("665248523855695872", "海峡卫视",     "海峡卫视",     "haixiapd/hd"),
    };

    private static final Map<String, ChannelDef> PATH_TO_CHANNEL = new HashMap<>();
    static {
        PATH_TO_CHANNEL.put("zhpd", PROVINCE_CHANNELS[0]);
        PATH_TO_CHANNEL.put("setv", PROVINCE_CHANNELS[1]);
        PATH_TO_CHANNEL.put("dnpd", PROVINCE_CHANNELS[1]);
        PATH_TO_CHANNEL.put("xwpd", PROVINCE_CHANNELS[2]);
        PATH_TO_CHANNEL.put("dspd", PROVINCE_CHANNELS[3]);
        PATH_TO_CHANNEL.put("sepd", PROVINCE_CHANNELS[4]);
        PATH_TO_CHANNEL.put("child", PROVINCE_CHANNELS[4]);
        PATH_TO_CHANNEL.put("hxtv", PROVINCE_CHANNELS[5]);
        PATH_TO_CHANNEL.put("haixiapd", PROVINCE_CHANNELS[5]);
    }

    private static final class CachedStream {
        String url;
        long expiresAt;
    }

    private static final Map<String, CachedStream> sCache = new ConcurrentHashMap<>();

    @Override
    public String getName() { return "福建网络广播电视台"; }

    @Override
    public void clearCache() {
        sCache.clear();
        LogUtil.d(TAG + ": cache cleared");
    }

    @Override
    public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String pathSeg = extractPathSegment(url);
                    if (pathSeg == null) {
                        callback.onError("福建台：无法识别频道路径");
                        return;
                    }
                    ChannelDef ch = PATH_TO_CHANNEL.get(pathSeg);
                    if (ch == null) {
                        callback.onError("福建台：未知频道 " + pathSeg);
                        return;
                    }

                    String m3u8Url = getSignedStream(ch);
                    if (m3u8Url == null || m3u8Url.isEmpty()) {
                        callback.onError("福建台：获取播放地址失败");
                        return;
                    }

                    Map<String, String> headers = new HashMap<>();
                    headers.put("Referer", "https://live.fjtv.net/");
                    headers.put("User-Agent", UA);

                    LogUtil.i(TAG + ": " + ch.displayName + " -> " + m3u8Url);
                    callback.onSuccess(new ParseResult(m3u8Url, ch.displayName,
                            "FHD", headers, null));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("福建台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String extractPathSegment(String url) {
        Matcher m = Pattern.compile("live\\.fjtv\\.net/([a-z]+)/?").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String getSignedStream(ChannelDef ch) throws Exception {
        long now = System.currentTimeMillis();
        CachedStream cached = sCache.get(ch.id);
        if (cached != null && cached.expiresAt > now + EXPIRY_GUARD_MS) {
            return cached.url;
        }

        String timestamp = String.valueOf(now / 1000);
        String raw = API_KEY + "&" + API_SECRET + "&" + API_VERSION + "&" + timestamp;
        String signature = md5(raw);

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "node");
        headers.put("X-API-TIMESTAMP", timestamp);
        headers.put("X-API-KEY", API_KEY);
        headers.put("X-AUTH-TYPE", "md5");
        headers.put("X-API-VERSION", API_VERSION);
        headers.put("X-API-SIGNATURE", signature);
        headers.put("Accept", "application/json, text/plain, */*");

        String url = API_URL + "?channel_id=" + ch.id;

        HttpFetcher.Response resp = HttpFetcher.get(url, headers);
        if (resp.code != 200) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API HTTP " + resp.code);
        }

        JSONArray arr;
        try {
            arr = new JSONArray(resp.bodyAsString());
        } catch (Exception e) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回非 JSON");
        }

        if (arr.length() == 0) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回空");
        }

        JSONObject row = arr.optJSONObject(0);
        if (row == null) {
            if (cached != null) return cached.url;
            throw new RuntimeException("API 返回格式错误");
        }

        String rowId = row.optString("id", "");
        String rowName = row.optString("name", "");
        if (!ch.id.equals(rowId) || !ch.rawName.equals(rowName.trim())) {
            if (cached != null) return cached.url;
            throw new RuntimeException("频道身份校验失败 (id=" + rowId + " name=" + rowName + ")");
        }

        JSONArray streams = row.optJSONArray("channel_stream");
        String streamM3u8 = null;

        // 优先取 is_main=1 的主流
        if (streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.optJSONObject(i);
                if (s != null && s.optInt("is_main") == 1) {
                    String m3u8 = s.optString("m3u8", "");
                    if (!m3u8.isEmpty()) { streamM3u8 = m3u8; break; }
                }
            }
        }
        // 回退：遍历所有 stream
        if (streamM3u8 == null && streams != null) {
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.optJSONObject(i);
                if (s != null) {
                    String m3u8 = s.optString("m3u8", "");
                    if (!m3u8.isEmpty()) { streamM3u8 = m3u8; break; }
                }
            }
        }
        // 最终回退：row.m3u8
        if (streamM3u8 == null) {
            streamM3u8 = row.optString("m3u8", "");
        }

        if (streamM3u8.isEmpty()) {
            if (cached != null) return cached.url;
            throw new RuntimeException("未找到可用 HLS 流");
        }

        String validated = validateStream(streamM3u8, ch.path);
        if (validated == null) {
            if (cached != null) return cached.url;
            throw new RuntimeException("流地址校验失败");
        }

        CachedStream entry = new CachedStream();
        entry.url = validated;
        entry.expiresAt = extractExpiry(validated);
        sCache.put(ch.id, entry);

        return validated;
    }

    private static String validateStream(String raw, String expectedPath) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            java.net.URL url = new java.net.URL(raw);
            if (!"https".equals(url.getProtocol())) return null;
            if (!MEDIA_HOSTS.contains(url.getHost())) return null;
            String expectedFull = "/" + expectedPath + "/live.m3u8";
            if (!expectedFull.equals(url.getPath())) return null;
            String upt = getQueryParam(url.getQuery(), "_upt");
            if (upt == null) return null;
            if (!upt.matches("(?i)^[0-9a-f]{8}[0-9]{10}$")) return null;
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    private static long extractExpiry(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String upt = getQueryParam(u.getQuery(), "_upt");
            if (upt != null && upt.length() >= 10) {
                String ts = upt.substring(upt.length() - 10);
                return Long.parseLong(ts) * 1000L;
            }
        } catch (Exception e) { /* fallthrough */ }
        return System.currentTimeMillis() + 60 * 1000L;
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

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 failed", e);
        }
    }
}