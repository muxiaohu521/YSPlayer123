package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 黑龙江广播电视台（极光新闻 / 东北网）解析器。
 *
 * 官方直播入口为固定地址，端口 4430（非标准），不带签名，清单和分片不校验 Referer，
 * 不挑 UA。参照 PCLive1.4 黑龙江模块的默认「本机转发」策略：解析器用 LocalHttpProxy
 * 在本机起一个本地 HTTP 代理，把清单与分片从官方 CDN 转发给 IJK 播放器，播放器只连
 * 127.0.0.1。这样可绕过部分网络路径对非标准 4430 端口 / 域名解析（fake-IP）的限制，
 * 无需任何浏览器 / 跨会话换票，纯本地转发抓取视频流。
 */
public class DbwParser extends BaseParser {

    private static final String TAG = "DbwParser";
    private static final String DOMAIN = "dbw.cn";
    private static final String MEDIA_HOST = "idclive.hljtv.com";
    private static final String MEDIA_ORIGIN = "https://" + MEDIA_HOST + ":4430";
    private static final String MEDIA_PATH = "/live/";
    private static final String MEDIA_BASE = MEDIA_ORIGIN + MEDIA_PATH;
    private static final Pattern SLUG_PAT =
            Pattern.compile("/zhibodan/zhibo_dan/([a-z0-9_]+)/?", Pattern.CASE_INSENSITIVE);

    private static class ChannelDef {
        final String name;
        final String stream;
        ChannelDef(String name, String stream) { this.name = name; this.stream = stream; }
    }

    private static final Map<String, ChannelDef> CHANNEL_BY_SLUG = new LinkedHashMap<>();
    static {
        CHANNEL_BY_SLUG.put("hljws", new ChannelDef("黑龙江卫视", "hljws_own.m3u8"));
        CHANNEL_BY_SLUG.put("hljds", new ChannelDef("黑龙江都市", "dushi_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljxw", new ChannelDef("黑龙江新闻法治", "hljxw_hd.m3u8"));
        CHANNEL_BY_SLUG.put("heilongjiang", new ChannelDef("黑龙江新闻法治", "hljxw_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljwt", new ChannelDef("黑龙江文体", "hljwy_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljse", new ChannelDef("黑龙江少儿", "hljse_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljys", new ChannelDef("黑龙江影视", "hljys_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljnykj", new ChannelDef("黑龙江农业科教", "hljgg_hd.m3u8"));
    }

    private static final Map<String, LocalHttpProxy> sProxies = new ConcurrentHashMap<>();

    @Override public String getName() { return "东北网(黑龙江)"; }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void clearCache() {
        for (String key : new ArrayList<>(sProxies.keySet())) {
            stopProxy(key);
        }
        LogUtil.i(TAG + ": cache cleared");
    }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String slug = extractSlug(url);
                    ChannelDef def = CHANNEL_BY_SLUG.get(slug);
                    if (def == null) {
                        def = CHANNEL_BY_SLUG.get("hljws");
                        LogUtil.i(TAG + ": slug not recognized, defaulting to " + def.name);
                    }

                    String streamUrl = officialStreamUrl(def.stream);
                    if (streamUrl == null) {
                        callback.onError("黑龙江频道流名不在官方白名单: " + def.stream);
                        return;
                    }

                    // 同一频道复用/重建代理，避免重复起端口
                    stopProxy(slug);

                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("Referer", "https://www.dbw.cn/");
                    cdnHeaders.put("Origin", "https://www.dbw.cn");
                    cdnHeaders.put("User-Agent", resolveUa(userAgent));

                    LocalHttpProxy proxy = new LocalHttpProxy(
                            MEDIA_HOST, streamUrl, MEDIA_BASE, cdnHeaders);
                    proxy.setUrlValidator(new LocalHttpProxy.UrlValidator() {
                        @Override
                        public String validate(String remoteUrl) {
                            return isOfficialMediaUrl(remoteUrl) ? remoteUrl : null;
                        }
                    });
                    int port = proxy.start();
                    sProxies.put(slug, proxy);

                    String localUrl = proxy.getLocalUrl();

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.dbw.cn/", userAgent);
                    headers.put("Referer", "https://www.dbw.cn/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(localUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed slug=" + slug + " -> " + def.name
                            + " via local proxy port " + port);
                    callback.onSuccess(new ParseResult(localUrl, def.name, "720P",
                            headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("东北网解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static void stopProxy(String key) {
        LocalHttpProxy old = sProxies.remove(key);
        if (old != null) {
            old.stop();
        }
    }

    private String extractSlug(String url) {
        if (url == null) return null;
        Matcher m = SLUG_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 仅允许七路官方流名，构建官方清单地址，非法时返回 null。 */
    private static String officialStreamUrl(String stream) {
        if (stream == null) return null;
        String url = MEDIA_BASE + stream;
        return isOfficialMediaUrl(url) ? url : null;
    }

    /** 白名单校验：只接受官方主机 + 4430 端口 + /live/ 下的清单或当前序号分片。 */
    private static boolean isOfficialMediaUrl(String raw) {
        if (raw == null) return false;
        try {
            java.net.URL u = new java.net.URL(raw);
            if (!"https".equalsIgnoreCase(u.getProtocol())) return false;
            if (!MEDIA_HOST.equalsIgnoreCase(u.getHost())) return false;
            if (u.getPort() != 4430) return false;
            if (u.getUserInfo() != null || u.getQuery() != null || u.getRef() != null) return false;
            String path = u.getPath();
            if (path == null || !path.startsWith(MEDIA_PATH)) return false;
            String filename = path.substring(MEDIA_PATH.length());
            for (ChannelDef def : CHANNEL_BY_SLUG.values()) {
                String stem = def.stream.substring(0, def.stream.length() - ".m3u8".length());
                if (filename.equals(def.stream)) return true;
                if (filename.endsWith(".ts") && filename.matches(stem + "-[0-9]+\\.ts")) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}