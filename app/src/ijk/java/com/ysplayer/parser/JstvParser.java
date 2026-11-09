package com.ysplayer.parser;

import android.util.Base64;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JstvParser extends BaseParser {

    private static final String TAG = "JstvParser";
    private static final String DOMAIN = "live.jstv.com";

    // ---- 鉴权参数（荔枝网官网 Web 应用公开常量） ----
    private static final String APP_ID = "3b93c452b851431c8b3a076789ab1e14";
    private static final String APP_SECRET = "9dd4b0400f6e4d558f2b3497d734c2b4";
    private static final String AUTH_URL = "https://api-auth-lizhi.jstv.com/JwtAuth/GetWebToken";
    private static final String CHANNEL_LIST_URL = "https://publish-lizhi.jstv.com/nav/8385";

    // ---- 流签名参数 ----
    private static final String STREAM_SECRET = "wrf2yJaCwC8HX3cfJz8P";
    private static final String STREAM_HOST = "litchi-play-encrypted-site.jstv.com";
    private static final int STREAM_TTL_SECONDS = 180;

    // ---- 缓存 ----
    private static final long TOKEN_CACHE_TTL_MS = 10 * 60 * 1000L;     // 令牌缓存 10 分钟
    private static final long TOKEN_RETRY_MS = 15 * 1000L;              // 令牌失败后重试间隔
    private static final long CHANNEL_CACHE_TTL_MS = 4 * 60 * 60 * 1000L; // 频道列表缓存 4 小时
    private static final long CHANNEL_RETRY_MS = 60 * 1000L;            // 频道列表失败后重试间隔
    private static final int REQUEST_TIMEOUT_MS = 10000;

    private static String sCachedToken;
    private static long sTokenExpiresAt;
    private static long sTokenRetryAfter;
    private static final Object sTokenLock = new Object();

    private static volatile List<ChannelInfo> sCachedChannels;
    private static volatile long sChannelCacheExpiresAt;
    private static volatile long sChannelRetryAfter;
    private static final Object sChannelLock = new Object();

    // ---- channelId(网页参数) → extraId(API标识) 映射 ----
    // 大多数频道的 channelId 与 extraId 一致，仅少数不同
    private static final Map<String, String> CHANNEL_ID_TO_EXTRA_ID = new HashMap<>();
    static {
        CHANNEL_ID_TO_EXTRA_ID.put("660", "670");  // 江苏卫视
        CHANNEL_ID_TO_EXTRA_ID.put("659", "676");  // 江苏卫视4K
    }

    private static final Map<String, String> CHANNEL_NAMES = new HashMap<>();
    static {
        CHANNEL_NAMES.put("670", "江苏卫视");
        CHANNEL_NAMES.put("676", "江苏卫视4K");
        CHANNEL_NAMES.put("669", "江苏城市");
        CHANNEL_NAMES.put("663", "江苏综艺");
        CHANNEL_NAMES.put("664", "江苏影视");
        CHANNEL_NAMES.put("668", "江苏新闻");
        CHANNEL_NAMES.put("666", "江苏教育");
        CHANNEL_NAMES.put("665", "江苏体育休闲");
        CHANNEL_NAMES.put("667", "优漫卡通");
        CHANNEL_NAMES.put("671", "江苏国际");
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Pattern CHANNEL_ID_PAT =
            Pattern.compile("channelId=(\\d+)");
    private static final Pattern VALID_STREAM_PAT =
            Pattern.compile("https?://[^/]*jstv\\.com/.+\\.m3u8", Pattern.CASE_INSENSITIVE);

    // ---- 数据结构 ----

    private static class ChannelInfo {
        final String extraId;
        final String name;
        final String rawUrl;
        final String logo;

        ChannelInfo(String extraId, String name, String rawUrl, String logo) {
            this.extraId = extraId;
            this.name = name;
            this.rawUrl = rawUrl;
            this.logo = logo;
        }
    }

    private static class AuthResult {
        final String url;
        final String body;

        AuthResult(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    // ======================== 接口实现 ========================

    @Override
    public String getName() {
        return "荔枝网(江苏)";
    }

    @Override
    public void clearCache() {
        synchronized (sTokenLock) {
            sCachedToken = null;
            sTokenExpiresAt = 0;
            sTokenRetryAfter = 0;
        }
        synchronized (sChannelLock) {
            sCachedChannels = null;
            sChannelCacheExpiresAt = 0;
            sChannelRetryAfter = 0;
        }
        LogUtil.i(TAG + ": cache cleared");
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
                    String channelId = extractChannelId(url);
                    LogUtil.i(TAG + ": parsing channelId=" + channelId);

                    // Step1: 获取 JWT 令牌
                    String token = getAccessToken();
                    if (token == null || token.isEmpty()) {
                        callback.onError("荔枝网鉴权失败，无法获取访问令牌");
                        return;
                    }

                    // Step2: 获取频道列表（缓存）
                    List<ChannelInfo> channels = getCachedChannelList(token);
                    if (channels == null || channels.isEmpty()) {
                        callback.onError("荔枝网频道列表为空");
                        return;
                    }

                    // Step3: 根据 channelId 查找对应频道
                    String extraId = CHANNEL_ID_TO_EXTRA_ID.get(channelId);
                    if (extraId == null) {
                        extraId = channelId;
                    }

                    ChannelInfo channel = null;
                    for (ChannelInfo ch : channels) {
                        if (extraId.equals(ch.extraId)) {
                            channel = ch;
                            break;
                        }
                    }

                    if (channel == null) {
                        // 尝试按原始 channelId 再找一次
                        for (ChannelInfo ch : channels) {
                            if (channelId.equals(ch.extraId)) {
                                channel = ch;
                                break;
                            }
                        }
                    }

                    if (channel == null) {
                        LogUtil.w(TAG + ": channel extraId=" + extraId + " not found in list");
                        callback.onError("荔枝网频道列表中未找到该频道(channelId="
                                + channelId + ", extraId=" + extraId + ")");
                        return;
                    }

                    // Step4: 对取流地址签名
                    String signedUrl = signStreamUrl(channel.rawUrl);

                    // Step5: 构建防盗链请求头
                    Map<String, String> playHeaders = new HashMap<>();
                    playHeaders.put("User-Agent",
                            userAgent != null && !userAgent.isEmpty() ? userAgent : UA_MOBILE);
                    playHeaders.put("Origin", "https://live.jstv.com");
                    playHeaders.put("Referer", "https://live.jstv.com/");
                    playHeaders.put("Accept", "*/*");
                    playHeaders.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");

                    // Step6: 构建清晰度选项
                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    String label = detectQualityLabel(signedUrl);
                    options.add(new ParseResult.QualityOption(signedUrl, label, 0,
                            labelToHeight(label)));

                    String channelName = CHANNEL_NAMES.containsKey(extraId)
                            ? CHANNEL_NAMES.get(extraId)
                            : (channel.name != null ? channel.name : ("江苏-" + channelId));

                    LogUtil.i(TAG + ": channelId=" + channelId + " extraId=" + extraId
                            + " -> " + channelName + " (" + label + ")");

                    callback.onSuccess(new ParseResult(signedUrl, channelName, label,
                            playHeaders, options));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("荔枝网解析异常: " + e.getMessage());
                }
            }
        });
    }

    // ======================== 鉴权 ========================

    /**
     * 获取匿名 Web JWT，带缓存。
     * 令牌失败不反复重试：短时间内只报错。
     */
    private static String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();

        synchronized (sTokenLock) {
            if (sCachedToken != null && sTokenExpiresAt > now + 120_000L) {
                return sCachedToken;
            }
            if (sTokenRetryAfter > now) {
                throw new Exception("荔枝网鉴权接口暂时不可用，请稍后重试");
            }
        }

        AuthResult authReq = buildAuthRequest(now, randomHex(16));

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA_MOBILE);
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        HttpFetcher.Response resp = HttpFetcher.post(authReq.url, headers,
                authReq.body.getBytes("UTF-8"));
        if (resp.code != 200) {
            synchronized (sTokenLock) {
                sTokenRetryAfter = now + TOKEN_RETRY_MS;
            }
            throw new Exception("荔枝网鉴权接口返回 HTTP " + resp.code);
        }

        String json = resp.bodyAsString();
        JSONObject payload = new JSONObject(json);
        JSONObject data = payload.optJSONObject("data");
        String token = data != null ? data.optString("accessToken", "") : "";
        if (token == null || token.isEmpty()) {
            synchronized (sTokenLock) {
                sTokenRetryAfter = now + TOKEN_RETRY_MS;
            }
            throw new Exception("荔枝网鉴权响应中没有 accessToken");
        }

        long expiry = extractJwtExpiry(token);
        if (expiry <= 0) {
            expiry = now + 8 * 60 * 1000L; // 保守默认 8 分钟
        }

        synchronized (sTokenLock) {
            sCachedToken = token;
            sTokenExpiresAt = Math.min(expiry, now + TOKEN_CACHE_TTL_MS);
            sTokenRetryAfter = 0;
        }

        LogUtil.i(TAG + ": got accessToken, expires in "
                + ((sTokenExpiresAt - now) / 1000) + "s");
        return token;
    }

    /**
     * 从 JWT 中提取 exp 字段（毫秒），解码失败返回 0。
     */
    private static long extractJwtExpiry(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return 0;
            // Base64url → Base64
            String b64 = parts[1].replace('-', '+').replace('_', '/');
            while (b64.length() % 4 != 0) b64 += "=";
            byte[] decoded = Base64.decode(b64, Base64.DEFAULT);
            JSONObject claims = new JSONObject(new String(decoded, "UTF-8"));
            long exp = claims.optLong("exp", 0);
            return exp * 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 官网时间戳混淆：每字节高4位取反，低4位+1（模16）。
     * JS signed int32 行为保持一致。
     */
    private static int encodeTimestamp(long seconds) {
        int value = (int) seconds;
        int b0 = transformByte(value & 0xFF);
        int b1 = transformByte((value >>> 8) & 0xFF);
        int b2 = transformByte((value >>> 16) & 0xFF);
        int b3 = transformByte((value >>> 24) & 0xFF);
        return b3 | (b2 << 8) | (b1 << 16) | (b0 << 24);
    }

    private static int transformByte(int b) {
        int hi = (b & 0xF0) ^ 0xF0;
        int lo = ((b & 0x0F) + 1) & 0x0F;
        return hi | lo;
    }

    /**
     * 递归扁平化对象用于 MD5 签名：数组拼接、对象按 key 排序拼接。
     */
    private static String flattenForSign(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value;
            java.util.TreeSet<String> sortedKeys = new java.util.TreeSet<>();
            java.util.Iterator<String> kit = obj.keys();
            while (kit.hasNext()) sortedKeys.add(kit.next());
            StringBuilder sb = new StringBuilder();
            for (String key : sortedKeys) {
                sb.append(key);
                sb.append(flattenForSign(obj.opt(key)));
            }
            return sb.toString();
        }
        if (value instanceof JSONArray) {
            JSONArray arr = (JSONArray) value;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                sb.append(flattenForSign(arr.opt(i)));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    /**
     * 构建 GetWebToken 请求：拼接 URL 参数并签名。
     */
    private static AuthResult buildAuthRequest(long nowMillis, String uuid) throws Exception {
        long seconds = nowMillis / 1000;
        JSONObject body = new JSONObject();
        body.put("platform", 41);
        body.put("uuid", uuid);
        body.put("appId", APP_ID);

        String path = "/JwtAuth/GetWebToken?AppID=" + APP_ID;
        String signInput = APP_SECRET + path + flattenForSign(body) + seconds;

        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(signInput.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        String sign = hex.toString();

        int tt = encodeTimestamp(seconds);
        String url = AUTH_URL + "?AppID=" + APP_ID + "&TT=" + tt + "&Sign=" + sign;

        return new AuthResult(url, body.toString());
    }

    // ======================== 频道列表 ========================

    /**
     * 获取频道列表（带缓存）
     */
    private static List<ChannelInfo> getCachedChannelList(String token) throws Exception {
        long now = System.currentTimeMillis();

        if (sCachedChannels != null && sChannelCacheExpiresAt > now) {
            return sCachedChannels;
        }
        if (sChannelRetryAfter > now) {
            if (sCachedChannels != null) return sCachedChannels;
            throw new Exception("荔枝网频道列表接口暂时不可用");
        }

        synchronized (sChannelLock) {
            if (sCachedChannels != null && sChannelCacheExpiresAt > now) {
                return sCachedChannels;
            }

            List<ChannelInfo> channels = fetchChannelList(token);
            sCachedChannels = channels;
            sChannelCacheExpiresAt = now + CHANNEL_CACHE_TTL_MS;
            sChannelRetryAfter = 0;
            LogUtil.i(TAG + ": channel list cached, " + channels.size() + " channels");
            return channels;
        }
    }

    private static List<ChannelInfo> fetchChannelList(String token) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA_MOBILE);
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);

        HttpFetcher.Response resp = HttpFetcher.get(CHANNEL_LIST_URL, headers);
        if (resp.code != 200) {
            sChannelRetryAfter = System.currentTimeMillis() + CHANNEL_RETRY_MS;
            throw new Exception("荔枝网频道接口返回 HTTP " + resp.code);
        }

        JSONObject payload = new JSONObject(resp.bodyAsString());
        JSONArray articles = payload.optJSONObject("data").optJSONArray("articles");
        if (articles == null) {
            sChannelRetryAfter = System.currentTimeMillis() + CHANNEL_RETRY_MS;
            throw new Exception("荔枝网频道接口返回数据异常");
        }

        List<ChannelInfo> rows = new ArrayList<>();
        for (int i = 0; i < articles.length(); i++) {
            JSONObject article = articles.optJSONObject(i);
            if (article == null) continue;

            String extraId = article.optString("extraId", "").trim();
            if (!extraId.matches("\\d{1,8}")) continue;

            String rawName = article.optString("title", "").trim();
            JSONObject extraJson = article.optJSONObject("extraJson");
            if (extraJson == null) continue;

            String streamUrl = extraJson.optString("url", "").trim();
            if (!isValidStreamUrl(streamUrl)) continue;

            String name = CHANNEL_NAMES.get(extraId);
            if (name == null) name = rawName;
            if (name == null || name.isEmpty()) continue;

            JSONArray thumbnails = article.optJSONArray("thumbnailsJson");
            String logo = (thumbnails != null && thumbnails.length() > 0)
                    ? thumbnails.optString(0, "").trim() : "";

            rows.add(new ChannelInfo(extraId, name, streamUrl, logo));
        }

        if (rows.isEmpty()) {
            sChannelRetryAfter = System.currentTimeMillis() + CHANNEL_RETRY_MS;
            throw new Exception("荔枝网频道列表为空，接口可能已改版");
        }

        return rows;
    }

    private static boolean isValidStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        return VALID_STREAM_PAT.matcher(url).find();
    }

    // ======================== 流签名 ========================

    /**
     * 对原始取流地址进行短效签名（约 3 分钟有效）。
     * 1. 替换 CDN 域名为 litchi-play-encrypted-site.jstv.com
     * 2. 计算 txSecret = MD5(STREAM_SECRET + 流名 + txTime)
     */
    private static String signStreamUrl(String rawUrl) throws Exception {
        java.net.URL urlObj = new java.net.URL(rawUrl);
        String path = urlObj.getPath();
        String streamName = path.substring(path.lastIndexOf('/') + 1)
                .replaceFirst("\\.m3u8$", "");

        long now = System.currentTimeMillis();
        String txTime = Long.toHexString(now / 1000 + STREAM_TTL_SECONDS);

        String signInput = STREAM_SECRET + streamName + txTime;
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(signInput.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        String txSecret = hex.toString();

        String protocol = "https";
        int port = urlObj.getPort();
        String hostPart = STREAM_HOST;
        if (port != -1) hostPart += ":" + port;

        StringBuilder signed = new StringBuilder();
        signed.append(protocol).append("://").append(hostPart).append(path);
        signed.append("?txSecret=").append(txSecret);
        signed.append("&txTime=").append(txTime);
        return signed.toString();
    }

    // ======================== 辅助方法 ========================

    private String extractChannelId(String url) {
        Matcher m = CHANNEL_ID_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return "0";
    }

    private static String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        SECURE_RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(byteLength * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static int labelToHeight(String label) {
        if (label == null) return 0;
        switch (label) {
            case "4K": return 2160;
            case "1080P": return 1080;
            case "720P": return 720;
            case "480P": return 480;
            case "360P": return 360;
            default: return 0;
        }
    }

    private static String detectQualityLabel(String url) {
        if (url == null) return "默认";
        String l = url.toLowerCase();
        if (l.contains("4k") || l.contains("2160")) return "4K";
        if (l.contains("1080") || l.contains("fhd")) return "1080P";
        if (l.contains("720") || l.contains("hd")) return "720P";
        if (l.contains("sd") || l.contains("480")) return "480P";
        if (l.contains("low") || l.contains("360")) return "360P";
        return "720P"; // 默认假定 720P
    }
}