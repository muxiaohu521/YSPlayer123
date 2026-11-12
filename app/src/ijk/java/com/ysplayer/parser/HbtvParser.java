package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 湖北长江云 (news.hbtv.com.cn) 解析器 —— IJK 模式。
 *
 * 取流链路完全对齐官网（纯 HTTP 模拟，去浏览器化）：
 *   1) 官网频道页内联六套频道的 stream 地址（live21-cjy.hbtv.com.cn，带短效 auth_key）
 *   2) 官网下发匿名 Cookie（client-id / client-token / aa-look / acw_tc / PASSPORTID）
 *   3) 通过 WebSocket（wss://remote-wa.cjyun.org.cn/liveweb）周期上报
 *      {@code {client_id, aa_look, client_token}} 心跳，激活该 client-id 会话
 *      —— 这是 CDN 源站放行清单请求的关键，缺失则清单固定 403
 *   4) 用激活的 client-id 调 /ajax/get_cdn_leech 换取带 auth_key + extrakey + aalook
 *      三段防盗链参数的正式播放地址
 *   5) 清单与分片经 LocalHttpProxy 全代理，清单由激活会话拉取，入口短效自动续签
 */
public class HbtvParser extends BaseParser {

    private static final String TAG = "HbtvParser";
    private static final String DOMAIN = "news.hbtv.com.cn";
    private static final String PAGE_ORIGIN = "https://news.hbtv.com.cn";
    private static final String REFERER = "https://news.hbtv.com.cn/";
    private static final String CDN_HOST = "live21-cjy.hbtv.com.cn";
    private static final String LEECH_API = "https://news.hbtv.com.cn/ajax/get_cdn_leech";
    private static final String WS_HOST = "remote-wa.cjyun.org.cn";
    private static final String WS_PATH = "/liveweb";
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;

    private static final Pattern CHANNEL_ID_PAT =
            Pattern.compile("/app/tv/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_KEY_PAT =
            Pattern.compile("^\\d{10}-[0-9a-f]{32}-\\d+-[0-9a-f]{32}$",
                    Pattern.CASE_INSENSITIVE);

    // 频道 id -> [显示名, 官网固定 HLS 路径]
    private static final Map<String, String[]> CHANNELS = new HashMap<>();

    static {
        CHANNELS.put("431", new String[]{"湖北卫视", "new-hbws"});
        CHANNELS.put("432", new String[]{"湖北经视", "new-hbjs"});
        CHANNELS.put("433", new String[]{"湖北综合", "new-hbzh"});
        CHANNELS.put("435", new String[]{"湖北影视", "new-hbys"});
        CHANNELS.put("437", new String[]{"湖北教育", "new-hbjy"});
        CHANNELS.put("438", new String[]{"湖北垄上", "new-hbls"});
    }

    private static final Map<String, LocalHttpProxy> sProxies = new ConcurrentHashMap<>();
    private static final Map<String, Session> sSessions = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "长江云(湖北)";
    }

    @Override
    public void clearCache() {
        for (String key : new ArrayList<>(sProxies.keySet())) {
            stopProxy(key);
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
                    final String channelId = extractChannelId(url);
                    if (channelId == null) {
                        callback.onError("长江云：无法解析频道标识");
                        return;
                    }
                    String[] def = CHANNELS.get(channelId);
                    if (def == null) {
                        callback.onError("长江云：不支持的频道标识 " + channelId);
                        return;
                    }

                    stopProxy(channelId);

                    Session session = new Session(channelId, def[1], userAgent);
                    String streamUrl = session.prepare();
                    if (streamUrl == null) {
                        session.close();
                        callback.onError("长江云：获取播放地址失败");
                        return;
                    }
                    sSessions.put(channelId, session);

                    java.net.URL parsed = new java.net.URL(streamUrl);
                    String cdnHost = parsed.getHost();
                    String m3u8Base = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);

                    String ua = resolveUa(userAgent);
                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("User-Agent", ua);
                    cdnHeaders.put("Referer", REFERER);
                    cdnHeaders.put("Origin", PAGE_ORIGIN);
                    cdnHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    LocalHttpProxy proxy = new LocalHttpProxy(cdnHost, streamUrl, m3u8Base, cdnHeaders);
                    proxy.setManifestFetcher(new LocalHttpProxy.ManifestFetcher() {
                        @Override
                        public byte[] fetchManifest() {
                            return session.fetchManifest(session.getTicket());
                        }
                    });
                    proxy.setEntryRefresher(new LocalHttpProxy.EntryRefresher() {
                        @Override
                        public String refreshEntryUrl(String expiredUrl) {
                            LogUtil.w(TAG + ": refreshing stream URL for channel " + channelId);
                            String newUrl = session.renew();
                            if (newUrl != null) {
                                LogUtil.i(TAG + ": renewed stream URL for channel " + channelId);
                            } else {
                                LogUtil.e(TAG + ": renew failed for channel " + channelId);
                            }
                            return newUrl;
                        }
                    });

                    int port = proxy.start();
                    sProxies.put(channelId, proxy);

                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", REFERER);
                    outHeaders.put("User-Agent", ua);

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(proxy.getLocalUrl(), "HD", 1280, 720));

                    LogUtil.i(TAG + ": parsed " + def[0] + " via proxy port " + port);
                    callback.onSuccess(new ParseResult(proxy.getLocalUrl(),
                            def[0], "HD", outHeaders, options));

                } catch (IOException e) {
                    LogUtil.e(TAG + ": proxy start failed", e);
                    callback.onError("长江云代理启动失败: " + e.getMessage());
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("长江云解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static void stopProxy(String channelId) {
        LocalHttpProxy old = sProxies.remove(channelId);
        if (old != null) {
            old.stop();
        }
        Session session = sSessions.remove(channelId);
        if (session != null) {
            session.close();
        }
    }

    // ======================== 取流链路 ========================

    private static String extractChannelId(String url) {
        if (url == null) return null;
        Matcher m = CHANNEL_ID_PAT.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static String extractStreamUrl(String html, String streamPath) {
        if (html == null || html.isEmpty()) return null;
        String text = html.replace("\\/", "/").replace("&amp;", "&");
        Pattern p = Pattern.compile(
                "https://" + Pattern.quote(CDN_HOST) + "/new-hbtv/"
                        + Pattern.quote(streamPath) + "\\.m3u8[^\"'\\s<>]*",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    private static boolean isOfficialStreamUrl(String url, String streamPath) {
        if (url == null || url.isEmpty()) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            if (!"https".equals(u.getProtocol())) return false;
            if (!CDN_HOST.equals(u.getHost())) return false;
            if (!("/new-hbtv/" + streamPath + ".m3u8").equals(u.getPath())) return false;
            String query = u.getQuery();
            if (query == null) return false;
            Matcher qm = Pattern.compile("(?:^|&)auth_key=([^&]+)").matcher(query);
            if (!qm.find()) return false;
            return AUTH_KEY_PAT.matcher(qm.group(1)).matches();
        } catch (Exception e) {
            return false;
        }
    }

    // ======================== 频道会话 ========================

    /**
     * 单个频道的完整会话：Cookie 集、内联 stream、WebSocket 心跳、当前正式播放地址。
     */
    private static final class Session {
        private final String channelId;
        private final String streamPath;
        private final String userAgent;
        private final Map<String, String> cookieJar = new HashMap<>();
        private final Object wsLock = new Object();

        private String inlineStream;
        private volatile String ticket;
        private volatile HbtvWebSocket ws;
        private volatile boolean closed;
        private Thread heartbeatThread;
        private String heartbeatJson;

        Session(String channelId, String streamPath, String userAgent) {
            this.channelId = channelId;
            this.streamPath = streamPath;
            this.userAgent = userAgent;
        }

        private String pageUrl() {
            return PAGE_ORIGIN + "/app/tv/" + channelId;
        }

        String getTicket() {
            return ticket;
        }

        /** 首次准备：抓页 → 心跳激活 → 换票。 */
        String prepare() {
            if (!fetchPage()) {
                LogUtil.e(TAG + ": fetch page failed for " + channelId);
                return null;
            }
            if (!activate()) {
                LogUtil.e(TAG + ": ws activate failed for " + channelId);
                return null;
            }
            String t = exchangeTicket();
            if (t != null && isOfficialStreamUrl(t, streamPath)) {
                ticket = t;
                LogUtil.i(TAG + ": ticket acquired for channel " + channelId);
                return t;
            }
            LogUtil.e(TAG + ": ticket exchange failed for " + channelId);
            return null;
        }

        /** 续签：优先用现有内联 stream 换票，失败则重抓页 + 重激活。 */
        String renew() {
            String t = exchangeTicket();
            if (t != null && isOfficialStreamUrl(t, streamPath)) {
                ticket = t;
                return t;
            }
            LogUtil.w(TAG + ": renew fallback, re-fetching page for " + channelId);
            if (fetchPage() && activate()) {
                t = exchangeTicket();
                if (t != null && isOfficialStreamUrl(t, streamPath)) {
                    ticket = t;
                    return t;
                }
            }
            return null;
        }

        private boolean fetchPage() {
            Map<String, String> h = new HashMap<>();
            h.put("User-Agent", resolveUa(userAgent));
            h.put("Referer", REFERER);
            h.put("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

            HttpFetcher.Response resp;
            try {
                resp = HttpFetcher.get(pageUrl(), h);
            } catch (IOException e) {
                LogUtil.e(TAG + ": page fetch failed: " + e.getMessage());
                return false;
            }
            if (resp.code != 200) {
                LogUtil.e(TAG + ": page HTTP " + resp.code);
                return false;
            }

            collectCookies(resp);
            String html = resp.bodyAsString();
            String stream = extractStreamUrl(html, streamPath);
            if (stream == null || !isOfficialStreamUrl(stream, streamPath)) {
                LogUtil.e(TAG + ": no valid official stream for channel " + channelId);
                return false;
            }
            inlineStream = stream;
            buildHeartbeatJson();
            return true;
        }

        private void collectCookies(HttpFetcher.Response resp) {
            if (resp == null) return;
            List<String> list = resp.allHeaders.get("Set-Cookie");
            if (list == null) return;
            for (String line : list) {
                if (line == null) continue;
                String kv = line.split(";")[0].trim();
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    cookieJar.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }

        private String cookieHeader() {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookieJar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        private void buildHeartbeatJson() {
            try {
                JSONObject jo = new JSONObject();
                jo.put("client_id", cookieJar.get("client-id"));
                jo.put("aa_look", cookieJar.get("aa-look"));
                jo.put("client_token", cookieJar.get("client-token"));
                heartbeatJson = jo.toString();
            } catch (Exception e) {
                heartbeatJson = "{}";
            }
        }

        private boolean activate() {
            synchronized (wsLock) {
                if (ws == null || !ws.isRunning()) {
                    HbtvWebSocket w = new HbtvWebSocket();
                    if (!w.connect(WS_HOST, 443, WS_PATH, PAGE_ORIGIN, resolveUa(userAgent), 10000)) {
                        LogUtil.e(TAG + ": ws connect failed");
                        return false;
                    }
                    ws = w;
                }
                if (heartbeatJson != null && !ws.sendText(heartbeatJson)) {
                    LogUtil.e(TAG + ": ws first heartbeat failed");
                    ws.close();
                    ws = null;
                    return false;
                }
            }
            startHeartbeat();
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            return true;
        }

        private void startHeartbeat() {
            if (heartbeatThread != null && heartbeatThread.isAlive()) return;
            heartbeatThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!closed) {
                        try {
                            Thread.sleep(HEARTBEAT_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            break;
                        }
                        if (closed) break;
                        synchronized (wsLock) {
                            if (ws == null || !ws.isRunning()) {
                                HbtvWebSocket w = new HbtvWebSocket();
                                if (w.connect(WS_HOST, 443, WS_PATH, PAGE_ORIGIN, resolveUa(userAgent), 10000)) {
                                    ws = w;
                                } else {
                                    continue;
                                }
                            }
                            if (heartbeatJson != null) {
                                ws.sendText(heartbeatJson);
                            }
                        }
                    }
                }
            }, "hbtv-ws-heartbeat");
            heartbeatThread.setDaemon(true);
            heartbeatThread.start();
        }

        private String exchangeTicket() {
            if (inlineStream == null) return null;
            try {
                String cid = cookieJar.get("client-id");
                String query = "?url=" + URLEncoder.encode(inlineStream, "UTF-8")
                        + "&client-id=" + URLEncoder.encode(cid == null ? "" : cid, "UTF-8");

                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", resolveUa(userAgent));
                h.put("X-Requested-With", "XMLHttpRequest");
                h.put("Referer", pageUrl());
                h.put("Accept", "application/json, text/plain, */*");
                h.put("Cookie", cookieHeader());

                HttpFetcher.Response resp = HttpFetcher.get(LEECH_API + query, h);
                if (resp.code != 200) {
                    LogUtil.e(TAG + ": leech HTTP " + resp.code);
                    return null;
                }
                JSONObject jo = new JSONObject(resp.bodyAsString());
                if (!jo.optBoolean("state", false)) {
                    LogUtil.e(TAG + ": leech state false: " + jo.optString("message", ""));
                    return null;
                }
                String data = jo.optString("data", "");
                return data.isEmpty() ? null : data;
            } catch (Exception e) {
                LogUtil.e(TAG + ": leech exchange failed: " + e.getMessage());
                return null;
            }
        }

        private byte[] fetchManifest(String url) {
            if (url == null) return null;
            try {
                Map<String, String> h = new HashMap<>();
                h.put("User-Agent", resolveUa(userAgent));
                h.put("Referer", REFERER);
                h.put("Origin", PAGE_ORIGIN);
                h.put("Accept", "application/vnd.apple.mpegurl, */*");
                h.put("Cookie", cookieHeader());

                HttpFetcher.Response resp = HttpFetcher.get(url, h);
                if (resp.code != 200) {
                    LogUtil.e(TAG + ": manifest HTTP " + resp.code);
                    return null;
                }
                return resp.body;
            } catch (IOException e) {
                LogUtil.e(TAG + ": manifest fetch failed: " + e.getMessage());
                return null;
            }
        }

        void close() {
            closed = true;
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
                heartbeatThread = null;
            }
            synchronized (wsLock) {
                if (ws != null) {
                    ws.close();
                    ws = null;
                }
            }
        }
    }
}