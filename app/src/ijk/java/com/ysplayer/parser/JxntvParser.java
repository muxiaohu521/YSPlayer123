package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 江西网络广播电视台（今视频）解析器。
 *
 * 取流流程：
 * 1. 固定频道表（8 路），按 URL route 识别频道
 * 2. MD5 签名鉴权 API：cdnauth.jxgdw.com/liveauth/pc（POST）
 * 3. 返回 {t, token}，拼接签名的 HLS 直连地址
 * 4. 短效 token（45s 缓存），过期后台刷新
 */
public class JxntvParser extends BaseParser {

    private static final String TAG = "JxntvParser";
    private static final String DOMAIN = "jxntv.cn";
    private static final String AUTH_API = "https://cdnauth.jxgdw.com/liveauth/pc";
    private static final String MEDIA_BASE = "https://yun-live.jxtvcn.com.cn/live-jxtv/";
    // 【测试】硬编码兜底值已注释：抓取失败时盐为 null，签名直接失效以便暴露问题。
    // private static final String AUTH_SALT = "gXmNaQROStYfd";
    private static final String ETAG_CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678oOLl9gqVvUuI1";
    private static final long TOKEN_TTL_MS = 45 * 1000L;
    private static final Pattern ROUTE_PAT =
            Pattern.compile("/#/([a-z0-9_]+)", Pattern.CASE_INSENSITIVE);

    // ---- 签名盐自动抓取 ----
    private static final String PAGE_URL = "https://www.jxntv.cn/live/";
    private static final long SALT_CACHE_TTL_MS = 30 * 60 * 1000L; // 盐缓存 30 分钟
    private static final Pattern INDEX_JS_PAT =
            Pattern.compile("/assets/index-([A-Za-z0-9_-]+)\\.js");
    private static final Pattern CHARSET_PAT =
            Pattern.compile("\"([A-Za-z0-9]{55,70})\"");
    private static final Pattern SALT_IDX_PAT =
            Pattern.compile("\\[\\s*(\\d+(?:\\s*,?\\s*\\d+){10,20})\\s*\\]\\s*\\[");

    private static volatile String sAuthSalt = null;              // 当前生效盐（仅自动抓取成功后更新；测试期无硬编码兜底）
    private static volatile long sAuthSaltCheckedAt = 0L;         // 上次抓取盐的时间戳
    private static volatile FutureTask<String> sSaltRefreshTask = null; // 并发去重：进行中的抓取
    private static final Object sSaltLock = new Object();

    private static class ChannelDef {
        final String route;
        final String streamName;
        final String name;
        ChannelDef(String route, String streamName, String name) {
            this.route = route; this.streamName = streamName; this.name = name;
        }
    }

    private static final Map<String, ChannelDef> CHANNEL_BY_ROUTE = new HashMap<>();
    static {
        add("jxtv1", "tv_jxtv1.m3u8", "江西卫视");
        add("jxtv2", "tv_jxtv2.m3u8", "江西都市");
        add("jxtv3", "tv_jxtv3_hd.m3u8", "江西经济生活");
        add("jxtv5", "tv_jxtv5.m3u8", "江西公共农业");
        add("jxtv6", "tv_jxtv6.m3u8", "江西少儿");
        add("jxtv7", "tv_jxtv7.m3u8", "江西新闻");
        add("jxtv8", "tv_jxtv8.m3u8", "江西移动电视");
        add("tcpd", "tv_taoci.m3u8", "江西陶瓷");
    }
    private static void add(String route, String stream, String name) {
        CHANNEL_BY_ROUTE.put(route, new ChannelDef(route, stream, name));
    }

    // 匿名设备标识：MD5("jxntv:" + UA) 前 12 位；固定常量，非账号凭据
    private static String sDeviceUuid = null;

    private static class TokenCache {
        String url;
        long expiresAt;
    }

    private static final Map<String, TokenCache> sCache = new HashMap<>();
    private static final SecureRandom RANDOM = new SecureRandom();

    @Override public String getName() { return "江西网络广播电视台"; }

    @Override public void clearCache() {
        sCache.clear();
        synchronized (sSaltLock) {
            sAuthSaltCheckedAt = 0;
            sSaltRefreshTask = null;
        }
        LogUtil.i(TAG + ": cache cleared");
    }

    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    initDeviceUuid(userAgent);
                    String route = extractRoute(url);
                    ChannelDef def = CHANNEL_BY_ROUTE.get(route);
                    if (def == null) {
                        def = CHANNEL_BY_ROUTE.get("jxtv1");
                        LogUtil.i(TAG + ": route not recognized, defaulting to " + def.name);
                    }

                    // 并行刷新签名盐（与鉴权请求重叠，降低首次解析延迟）
                    ensureAuthSaltAsync();

                    String streamUrl = getSignedStream(def, userAgent);
                    if (streamUrl == null) {
                        callback.onError("江西台鉴权失败");
                        return;
                    }

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.jxntv.cn/", userAgent);
                    headers.put("Origin", "https://www.jxntv.cn");
                    headers.put("Referer", "https://www.jxntv.cn/live/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(streamUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed route=" + route + " -> " + def.name);
                    callback.onSuccess(new ParseResult(streamUrl, def.name, "720P", headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("江西台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private void initDeviceUuid(String userAgent) {
        if (sDeviceUuid != null) return;
        String ua = resolveUa(userAgent);
        sDeviceUuid = md5("jxntv:" + ua).substring(0, 12);
    }

    private String getSignedStream(ChannelDef def, String userAgent) {
        long now = System.currentTimeMillis();
        TokenCache cache = sCache.get(def.route);
        if (cache != null && cache.expiresAt > now) return cache.url;

        try {
            String timestamp = String.valueOf(now / 1000);
            String etag = makeEtag();
            String uuid = sDeviceUuid != null ? sDeviceUuid : "000000000000";

            String authorization = md5(timestamp + def.streamName + etag + getAuthSalt());

            JSONObject body = new JSONObject();
            body.put("t", timestamp);
            body.put("stream", def.streamName);
            body.put("uuid", uuid);

            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", resolveUa(userAgent));
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Content-Type", "application/json");
            headers.put("Origin", "https://www.jxntv.cn");
            headers.put("Referer", "https://www.jxntv.cn/live/");
            headers.put("Authorization", authorization);
            headers.put("ETag", etag);
            headers.put("GPU", "Apple, Apple M1");

            HttpFetcher.Response resp = HttpFetcher.post(
                    AUTH_API, headers, body.toString().getBytes("UTF-8"));
            if (resp.code != 200) {
                LogUtil.w(TAG + ": auth API HTTP " + resp.code);
                return null;
            }

            JSONObject json = new JSONObject(resp.bodyAsString());
            String t = json.optString("t", "");
            String token = json.optString("token", "");
            if (t.isEmpty() || token.isEmpty() || !token.matches("^[a-f0-9]{32}$")) {
                LogUtil.w(TAG + ": invalid auth response");
                return null;
            }

            String streamUrl = MEDIA_BASE + def.streamName
                    + "?source=pc&t=" + t + "&token=" + token + "&uuid=" + uuid;

            TokenCache entry = new TokenCache();
            entry.url = streamUrl;
            entry.expiresAt = now + TOKEN_TTL_MS;
            sCache.put(def.route, entry);
            return entry.url;
        } catch (Exception e) {
            LogUtil.w(TAG + ": " + def.name + " 鉴权失败: " + e.getMessage());
            return null;
        }
    }

    // ======================== 签名盐自动抓取 ========================

    /**
     * 拉取纯文本资源。HttpFetcher 已支持 gzip 解压与最多 5 次重定向。
     */
    private static String httpGetText(String url) {
        try {
            HttpFetcher.Response resp = HttpFetcher.get(url, null);
            if (resp.code == 200) return resp.bodyAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 三步自动抓取签名盐：首页 HTML -> 入口 JS -> 唯一字符集 + 唯一索引数组逐位拼接。
     */
    private static String fetchAuthSalt() {
        try {
            String html = httpGetText(PAGE_URL);
            if (html == null) return null;

            Matcher idxMatcher = INDEX_JS_PAT.matcher(html);
            if (!idxMatcher.find()) return null;
            String indexJs = httpGetText(PAGE_URL + "assets/index-" + idxMatcher.group(1) + ".js");
            if (indexJs == null) return null;

            Matcher charsetMatcher = CHARSET_PAT.matcher(indexJs);
            if (!charsetMatcher.find()) return null;
            String charset = charsetMatcher.group(1);

            Matcher idxArrMatcher = SALT_IDX_PAT.matcher(indexJs);
            if (!idxArrMatcher.find()) return null;
            String[] tokens = idxArrMatcher.group(1).split("[,\\s]+");

            StringBuilder salt = new StringBuilder();
            for (String token : tokens) {
                if (token.isEmpty()) continue;
                int idx = Integer.parseInt(token.trim());
                if (idx >= 0 && idx < charset.length()) {
                    salt.append(charset.charAt(idx));
                }
            }
            return salt.toString();
        } catch (Exception e) {
            LogUtil.e(TAG + ": fetch auth salt failed", e);
            return null;
        }
    }

    /**
     * 启动/复用后台刷新，返回可等待的 FutureTask（盐仍新鲜时返回 null，直接使用 sAuthSalt）。
     */
    private static FutureTask<String> ensureAuthSaltAsync() {
        long now = System.currentTimeMillis();
        synchronized (sSaltLock) {
            if (sAuthSaltCheckedAt != 0 && now - sAuthSaltCheckedAt < SALT_CACHE_TTL_MS) {
                return null;
            }
            if (sSaltRefreshTask != null && !sSaltRefreshTask.isDone()) {
                return sSaltRefreshTask;
            }
            FutureTask<String> task = new FutureTask<>(new Callable<String>() {
                @Override
                public String call() {
                    String fetched = fetchAuthSalt();
                    if (fetched != null) {
                        sAuthSalt = fetched;
                        LogUtil.i(TAG + ": auth salt refreshed from cloud");
                    } else {
                        LogUtil.w(TAG + ": auth salt fetch failed, keep current value");
                    }
                    sAuthSaltCheckedAt = System.currentTimeMillis();
                    return sAuthSalt;
                }
            });
            sSaltRefreshTask = task;
            new Thread(task, "jxntv-salt-refresh").start();
            return task;
        }
    }

    /**
     * 等待盐就绪（含冷却期与并发去重）。解析运行在线程池，允许短暂阻塞。
     */
    private static String getAuthSalt() {
        FutureTask<String> task = ensureAuthSaltAsync();
        if (task != null) {
            try {
                return task.get();
            } catch (Exception ignored) {
            }
        }
        return sAuthSalt;
    }

    private String makeEtag() {
        StringBuilder sb = new StringBuilder(8);
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        for (byte b : bytes) {
            sb.append(ETAG_CHARS.charAt((b & 0xFF) % ETAG_CHARS.length()));
        }
        return sb.toString();
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private String extractRoute(String url) {
        if (url == null) return null;
        Matcher m = ROUTE_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }
}