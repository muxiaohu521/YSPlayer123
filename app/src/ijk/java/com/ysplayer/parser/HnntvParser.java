package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 海南网络广播电视台解析器。
 *
 * 两阶段取流：
 * 1. 频道列表 API：www.hnntv.cn/api/channel?type=1 → 七套电视（id/name/code/liveUrl）
 * 2. 播放签名 API：ps.hnntv.cn/ps/livePlayUrl?channelCode=xxx → 带 _upt 签名的短效 m3u8
 *
 * _upt 参数末十位为秒级过期时间戳，缓存复用直至签名过期。
 */
public class HnntvParser extends BaseParser {

    private static final String TAG = "HnntvParser";
    private static final String DOMAIN = "hnntv.cn";
    private static final String CHANNEL_API = "https://www.hnntv.cn/api/channel?type=1";
    private static final String PLAY_API = "https://ps.hnntv.cn/ps/livePlayUrl";
    private static final long STREAM_REFRESH_MS = 90 * 60 * 1000L;
    private static final long STREAM_RETRY_MS = 60 * 1000L;
    private static final long EXPIRY_SKEW_MS = 5 * 60 * 1000L;
    private static final long API_TIMEOUT_MS = 20 * 1000L;
    private static final Pattern CHANNEL_ID_PAT =
            Pattern.compile("[?&]channelId=(\\d{1,3})(?:&|$)", Pattern.CASE_INSENSITIVE);

    private static class ChannelDef {
        final String id;
        final String name;
        final String code;
        ChannelDef(String id, String name, String code) { this.id = id; this.name = name; this.code = code; }
    }

    private static final List<ChannelDef> CHANNELS = new ArrayList<>();
    static {
        CHANNELS.add(new ChannelDef("13", "海南卫视", "STHaiNan_channel_lywsgq"));
        CHANNELS.add(new ChannelDef("5", "三沙卫视", "STHaiNan_channel_ssws"));
        CHANNELS.add(new ChannelDef("1", "海南自贸", "jjpd"));
        CHANNELS.add(new ChannelDef("3", "海南新闻", "STHaiNan_channel_xwpd"));
        CHANNELS.add(new ChannelDef("4", "海南社会与法", "ggpd"));
        CHANNELS.add(new ChannelDef("6", "海南文旅", "wlpd"));
        CHANNELS.add(new ChannelDef("7", "海南少儿", "sepd"));
    }

    private static final Map<String, ChannelDef> CHANNEL_BY_ID = new HashMap<>();
    static {
        for (ChannelDef def : CHANNELS) CHANNEL_BY_ID.put(def.id, def);
    }

    private static class StreamCache {
        String url;
        long refreshAt;
        long validUntil;
        long retryAt;
    }

    private static final Map<String, StreamCache> sCache = new HashMap<>();
    private static final Map<String, Object> sLocks = new HashMap<>();

    @Override public String getName() { return "海南网络广播电视台"; }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String channelId = extractChannelId(url);
                    ChannelDef def = CHANNEL_BY_ID.get(channelId);
                    if (def == null) {
                        def = CHANNELS.get(0);
                        LogUtil.i(TAG + ": channelId not recognized, defaulting to " + def.name);
                    }

                    String streamUrl = getSignedStream(def, userAgent);
                    if (streamUrl == null) {
                        callback.onError("海南台获取播放签名失败");
                        return;
                    }

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.hnntv.cn/", userAgent);
                    headers.put("Referer", "https://www.hnntv.cn/");
                    headers.put("Origin", "https://www.hnntv.cn");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(streamUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed channelId=" + channelId + " -> " + def.name);
                    callback.onSuccess(new ParseResult(streamUrl, def.name, "720P", headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("海南台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private String getSignedStream(ChannelDef def, String userAgent) {
        long now = System.currentTimeMillis();
        StreamCache cache;
        Object lock;
        synchronized (sLocks) {
            lock = sLocks.get(def.id);
            if (lock == null) { lock = new Object(); sLocks.put(def.id, lock); }
        }
        synchronized (lock) {
            cache = sCache.get(def.id);
            if (cache != null) {
                if (cache.validUntil > now) {
                    if (cache.refreshAt > now || cache.retryAt > now) return cache.url;
                }
                if (cache.retryAt > now && cache.validUntil > now) return cache.url;
            }
        }

        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", userAgent != null ? userAgent : UA_MOBILE);
            headers.put("Accept", "application/json, text/plain, */*");
            headers.put("Origin", "https://www.hnntv.cn");
            headers.put("Referer", "https://www.hnntv.cn/");

            HttpFetcher.Response resp = HttpFetcher.get(
                    PLAY_API + "?channelCode=" + def.code + "&appCode=&token=", headers);
            if (resp.code != 200) {
                LogUtil.w(TAG + ": play API HTTP " + resp.code);
                return useFallback(def, now);
            }

            JSONObject json = new JSONObject(resp.bodyAsString());
            int businessCode = json.optInt("businessCode", -1);
            JSONArray resultSet = json.optJSONArray("resultSet");
            if (businessCode != 200 || resultSet == null || resultSet.length() == 0) {
                LogUtil.w(TAG + ": play API not ok: " + json.optString("description", ""));
                return useFallback(def, now);
            }

            String signedUrl = resultSet.getJSONObject(0).optString("url", "");
            if (signedUrl.isEmpty() || !signedUrl.contains(".m3u8")) {
                LogUtil.w(TAG + ": no m3u8 in play API response");
                return useFallback(def, now);
            }

            long expiresAt = extractExpiry(signedUrl);
            long validUntil = expiresAt > 0 ? expiresAt - EXPIRY_SKEW_MS : now + 90 * 60 * 1000L;

            StreamCache entry = new StreamCache();
            entry.url = signedUrl;
            entry.validUntil = validUntil;
            entry.refreshAt = Math.min(now + STREAM_REFRESH_MS, validUntil);
            entry.retryAt = 0;

            synchronized (lock) { sCache.put(def.id, entry); }
            return entry.url;
        } catch (Exception e) {
            LogUtil.w(TAG + ": " + def.name + " 签名请求失败: " + e.getMessage());
            return useFallback(def, now);
        }
    }

    private String useFallback(ChannelDef def, long now) {
        Object lock;
        synchronized (sLocks) { lock = sLocks.get(def.id); }
        synchronized (lock) {
            StreamCache cache = sCache.get(def.id);
            if (cache != null && cache.validUntil > now + EXPIRY_SKEW_MS) {
                cache.retryAt = now + STREAM_RETRY_MS;
                return cache.url;
            }
        }
        return null;
    }

    private long extractExpiry(String url) {
        try {
            Matcher m = Pattern.compile("[?&]_upt=([0-9a-f]{8}(\\d{10}))",
                    Pattern.CASE_INSENSITIVE).matcher(url);
            if (m.find()) {
                return Long.parseLong(m.group(2)) * 1000L;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String extractChannelId(String url) {
        if (url == null) return null;
        Matcher m = CHANNEL_ID_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }
}