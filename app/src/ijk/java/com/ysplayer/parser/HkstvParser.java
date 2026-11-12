package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HkstvParser extends BaseParser {

    private static final String TAG = "HkstvParser";
    private static final String DOMAIN = "hkstv.tv";

    private static final String REFERER = "https://www.hkstv.tv/";
    private static final String ORIGIN = "https://www.hkstv.tv";
    private static final String MASTER_URL =
            "http://webcast.hkstv.tv/livestream/mutfysrq/playlist.m3u8";

    private static final Pattern HLS_CTX_PATTERN =
            Pattern.compile("playlist\\.m3u8\\?hls_ctx=([A-Za-z0-9_\\-]+)");

    // 官方 webcast 地址失效/被拒时，退回已验证可用的 HTTPS CDN 镜像。
    // 绝不把退化主播放列表（BANDWIDTH=1）交给播放器，否则 IJK 会播完首批切片后停止续播。
    private static final String[] CDN_FALLBACKS = {
            "https://piccpndks.v.kcdnvip.com/audio/xianggangweishi_2.m3u8",
            "https://piccpndali.v.myalicdn.com/audio/xianggangweishi_2.m3u8",
    };

    @Override
    public String getName() {
        return "香港卫视";
    }

    @Override
    public boolean canParse(String url) {
        return url != null && url.contains(DOMAIN);
    }

    @Override
    public void parse(final String url, final String userAgent,
                       final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String ua = resolveUa(userAgent);

                    Map<String, String> streamHeaders = new HashMap<>();
                    streamHeaders.put("Referer", REFERER);
                    streamHeaders.put("Origin", ORIGIN);
                    streamHeaders.put("User-Agent", ua);
                    streamHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    String mediaUrl = resolveMediaUrl(streamHeaders);
                    if (mediaUrl != null) {
                        LogUtil.i(TAG + ": media HLS: " + mediaUrl);
                        callback.onSuccess(new ParseResult(mediaUrl,
                                "香港卫视", "默认", streamHeaders, null));
                        return;
                    }

                    // 主播放列表解析失败，回退到已验证 CDN 镜像
                    LogUtil.w(TAG + ": webcast resolve failed, using CDN fallback");
                    callback.onSuccess(new ParseResult(CDN_FALLBACKS[0],
                            "香港卫视", "默认", null, null));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("香港卫视解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String resolveMediaUrl(Map<String, String> headers) {
        try {
            HttpFetcher.Response resp = HttpFetcher.get(MASTER_URL, headers);
            if (resp.code != 200) {
                LogUtil.e(TAG + ": master HTTP " + resp.code);
                return null;
            }
            String text = resp.bodyAsString();
            String base = (resp.finalUrl != null && !resp.finalUrl.isEmpty())
                    ? resp.finalUrl : MASTER_URL;

            // 逐行取第一个非注释的 m3u8 行，并相对主播放列表地址解析成绝对地址
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                if (t.regionMatches(true, t.length() - 5, ".m3u8", 0, 5)
                        || t.matches("(?i).*\\.m3u8\\?.*")) {
                    try {
                        return new java.net.URL(new java.net.URL(base), t).toString();
                    } catch (Exception ignored) {
                    }
                }
            }

            // 兜底：兼容旧的 token 提取方式
            Matcher m = HLS_CTX_PATTERN.matcher(text);
            if (m.find() && m.group(1) != null) {
                return MASTER_URL + "?hls_ctx=" + m.group(1);
            }
        } catch (IOException e) {
            LogUtil.e(TAG + ": master fetch failed: " + e.getMessage());
        }
        return null;
    }
}