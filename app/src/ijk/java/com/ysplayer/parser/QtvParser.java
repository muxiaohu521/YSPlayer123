package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class QtvParser extends BaseParser {

    private static final String TAG = "QtvParser";
    private static final String DOMAIN = "qtv.com.cn";
    private static final String API_BASE = "http://video10.qtv.com.cn";
    private static final Pattern CHANNEL_NAME_PATTERN =
            Pattern.compile("channelName:\\s*'([^']+)'");
    private static final Pattern URL_QTV_PATTERN =
            Pattern.compile("tv_qtv(\\d+)");

    private static LocalHttpProxy sProxy;

    @Override
    public String getName() {
        return "青岛网络电视台";
    }

    @Override
    public void clearCache() {
        stopProxy();
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

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.qtv.com.cn/", userAgent);

                    HttpFetcher.Response resp = HttpFetcher.get(url, headers);
                    if (resp.code != 200) {
                        callback.onError("青岛台页面返回状态码: " + resp.code);
                        return;
                    }

                    String html = resp.bodyAsString();
                    String channelName = extractChannelName(html, url);
                    if (channelName == null) {
                        callback.onError("青岛台页面未找到channelName");
                        return;
                    }

                    LogUtil.i(TAG + ": found channelName=" + channelName);

                    String m3u8Url = API_BASE + "/drm/" + channelName + "/manifest.m3u8";
                    String m3u8Base = API_BASE + "/drm/" + channelName + "/";
                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("Referer", "https://www.qtv.com.cn/");
                    cdnHeaders.put("Origin", "https://www.qtv.com.cn");
                    cdnHeaders.put("User-Agent", resolveUa(userAgent));

                    HttpFetcher.Response m3u8Resp = HttpFetcher.get(m3u8Url, cdnHeaders);
                    if (m3u8Resp.code != 200) {
                        callback.onError("青岛台m3u8返回状态码: " + m3u8Resp.code);
                        return;
                    }

                    sProxy = new LocalHttpProxy("video10.qtv.com.cn", m3u8Url,
                            m3u8Base, cdnHeaders);
                    sProxy.setSegmentTransformer(new LocalHttpProxy.SegmentTransformer() {
                        @Override
                        public byte[] transform(byte[] raw) {
                            return ArcvideoDecryptor.decrypt(raw);
                        }
                    });
                    int port = sProxy.start();
                    String localUrl = sProxy.getLocalUrl();

                    LogUtil.i(TAG + ": proxy started, local m3u8: " + localUrl);

                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", "https://www.qtv.com.cn/");

                    callback.onSuccess(new ParseResult(localUrl,
                            "青岛-" + channelName, "默认", outHeaders, null));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("青岛台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static void stopProxy() {
        if (sProxy != null) {
            sProxy.stop();
            sProxy = null;
        }
    }

    private String extractChannelName(String html, String url) {
        Matcher m = CHANNEL_NAME_PATTERN.matcher(html);
        if (m.find()) return m.group(1);
        String lc = html.toLowerCase();
        for (String name : new String[]{"qtv1at", "qtv2at", "qtv3at", "qtv4at", "qtv6at"}) {
            if (lc.contains(name)) return name;
        }
        Matcher um = URL_QTV_PATTERN.matcher(url);
        if (um.find()) {
            return "qtv" + um.group(1) + "at";
        }
        if (url != null && url.contains("/live/tv/")) {
            return "qtv1at";
        }
        return null;
    }
}