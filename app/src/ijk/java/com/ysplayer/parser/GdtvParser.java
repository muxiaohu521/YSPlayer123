package com.ysplayer.parser;

import android.os.Build;
import android.util.Base64;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 广东台荔枝网 (gdtv.cn) 解析器 —— IJK 模式。
 *
 * 取流链路对齐官网 Web 应用（与桌面端 PCLive1.4 sniffer.js 一致）：
 *   1) getParam 接口取初始化节点（HMAC-SHA256 签名头）
 *   2) 节点尾缀 -1 时经 WebSocket 换取会话节点
 *   3) 频道详情接口先 OPTIONS 预检，再带签名头请求，解析 playUrl.hd
 * 官方流地址为短效签名（约 2 分钟失效），因此通过 LocalHttpProxy 全代理
 * m3u8 + ts 请求，并在入口失效时自动重新取流续签，避免播放中断。
 */
public class GdtvParser extends BaseParser {

    private static final String TAG = "GdtvParser";
    private static final String DOMAIN = "gdtv.cn";

    private static final String GDTV_ORIGIN = "https://www.gdtv.cn";
    private static final String GDTV_PARAM_URL = "https://tcdn-api.itouchtv.cn/getParam";
    private static final String GDTV_SOCKET_HOST = "tcdn-ws.itouchtv.cn";
    private static final int GDTV_SOCKET_PORT = 3800;
    private static final String GDTV_SOCKET_PATH = "/connect";
    private static final String GDTV_CHANNEL_API = "https://gdtv-api.gdtv.cn/api/tv/v2/tvChannel";
    // 官网 WebAssembly 签名模块里 gdtv-api / tcdn-api 的固定 keyset（非用户凭据）。
    // 密钥嵌入在懒加载的 WASM 模块中，属固定 HMAC 密钥而非轮换签名盐，动态提取复杂度高、
    // 收益低，故保留硬编码。来源：img.gdtv.cn 站点 JS 懒加载 chunk 内的 WASM 模块。
    private static final String GDTV_CA_KEY = "89541943007407288657755311868534";
    private static final String GDTV_CA_SECRET = "dfkcY1c3sfuw1Cii9DWj8UO3iQy2hqlDxyvDXd1oVMxwYVDSgeB6phO9eW1dfuwX";
    private static final String GDTV_CLIENT = "WEB_PC";
    private static final int GDTV_TIMEOUT_MS = 20000;
    private static final String GDTV_PREFLIGHT_HEADERS =
            "content-type,x-itouchtv-ca-key,x-itouchtv-ca-signature,x-itouchtv-ca-timestamp,x-itouchtv-client,x-itouchtv-device-id";
    // 会话级设备号：进程内复用，避免每次取流换新号
    private static final String GDTV_DEVICE_ID = "WEB_" + UUID.randomUUID().toString();

    private static final Pattern CHANNEL_ID_PAT =
            Pattern.compile("/tvChannelDetail/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHANNEL_ID_PAT_ALT =
            Pattern.compile("/channelDetail/(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CHANNEL_NAMES = new HashMap<>();

    static {
        CHANNEL_NAMES.put("43", "广东卫视");
        CHANNEL_NAMES.put("16", "广东卫视4K");
        CHANNEL_NAMES.put("44", "广东珠江");
        CHANNEL_NAMES.put("45", "广东新闻");
        CHANNEL_NAMES.put("48", "广东民生");
        CHANNEL_NAMES.put("47", "广东体育");
        CHANNEL_NAMES.put("51", "大湾区卫视");
        CHANNEL_NAMES.put("46", "大湾区卫视海外版");
        CHANNEL_NAMES.put("53", "广东影视");
        CHANNEL_NAMES.put("54", "广东少儿");
        CHANNEL_NAMES.put("66", "嘉佳卡通");
        CHANNEL_NAMES.put("42", "南方购物");
        CHANNEL_NAMES.put("15", "岭南戏曲");
        CHANNEL_NAMES.put("74", "广东移动");
        CHANNEL_NAMES.put("100", "广东台经典剧");
        CHANNEL_NAMES.put("94", "广东纪录片");
        CHANNEL_NAMES.put("99", "广东健康");
        CHANNEL_NAMES.put("102", "GRTN生活");
    }

    private static final Map<String, LocalHttpProxy> sProxies = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "荔枝网(广东)";
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
                        callback.onError("广东台：无法解析频道标识");
                        return;
                    }

                    stopProxy(channelId);

                    String streamUrl = captureStream(channelId, userAgent);
                    if (streamUrl == null) {
                        callback.onError("广东台：获取播放地址失败");
                        return;
                    }

                    java.net.URL parsed = new java.net.URL(streamUrl);
                    String cdnHost = parsed.getHost();
                    String m3u8Base = streamUrl.substring(0, streamUrl.lastIndexOf('/') + 1);

                    String ua = resolveUa(userAgent);
                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("User-Agent", ua);
                    cdnHeaders.put("Referer", GDTV_ORIGIN + "/");
                    cdnHeaders.put("Origin", GDTV_ORIGIN);
                    cdnHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    LocalHttpProxy proxy = new LocalHttpProxy(cdnHost, streamUrl, m3u8Base, cdnHeaders);
                    proxy.setEntryRefresher(new LocalHttpProxy.EntryRefresher() {
                        @Override
                        public String refreshEntryUrl(String expiredUrl) {
                            LogUtil.w(TAG + ": refreshing stream URL for channel " + channelId);
                            try {
                                String newUrl = captureStream(channelId, userAgent);
                                if (newUrl != null) {
                                    LogUtil.i(TAG + ": got new stream URL for channel " + channelId);
                                }
                                return newUrl;
                            } catch (Exception e) {
                                LogUtil.e(TAG + ": refresh failed", e);
                                return null;
                            }
                        }
                    });

                    int port = proxy.start();
                    sProxies.put(channelId, proxy);

                    String name = CHANNEL_NAMES.containsKey(channelId)
                            ? CHANNEL_NAMES.get(channelId) : ("广东台-" + channelId);

                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", GDTV_ORIGIN + "/");
                    outHeaders.put("User-Agent", ua);

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(proxy.getLocalUrl(), "HD", 1280, 720));

                    LogUtil.i(TAG + ": parsed " + name + " via proxy port " + port);
                    callback.onSuccess(new ParseResult(proxy.getLocalUrl(), name, "HD", outHeaders, options));

                } catch (IOException e) {
                    LogUtil.e(TAG + ": proxy start failed", e);
                    callback.onError("广东台代理启动失败: " + e.getMessage());
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("广东台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static void stopProxy(String channelId) {
        LocalHttpProxy old = sProxies.remove(channelId);
        if (old != null) {
            old.stop();
        }
    }

    // ======================== 取流链路 ========================

    private static String extractChannelId(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = CHANNEL_ID_PAT.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        m = CHANNEL_ID_PAT_ALT.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static boolean isOfficialStreamUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        try {
            URL u = new URL(url);
            if (!"https".equals(u.getProtocol())) {
                return false;
            }
            String path = u.getPath();
            if (!path.startsWith("/live/") || !path.toLowerCase().endsWith(".m3u8")) {
                return false;
            }
            String host = u.getHost();
            if ("tcdn.itouchtv.cn".equals(host)) {
                return getQueryParam(url, "t_token") != null;
            }
            if ("lbplay.grtn.cn".equals(host)) {
                String ak = getQueryParam(url, "auth_key");
                return ak != null && ak.matches("\\d{10}-\\w+-\\w+-[0-9a-f]{32}");
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String getQueryParam(String url, String key) {
        try {
            URL u = new URL(url);
            String q = u.getQuery();
            if (q == null) {
                return null;
            }
            for (String pair : q.split("&")) {
                int idx = pair.indexOf('=');
                String k = idx >= 0 ? pair.substring(0, idx) : pair;
                String v = idx >= 0 ? pair.substring(idx + 1) : "";
                if (key.equals(k)) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String hmacSha256Base64(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private static Map<String, String> signedHeaders(String url) throws Exception {
        long now = System.currentTimeMillis();
        String timestamp = String.valueOf(now);
        if (!timestamp.matches("\\d{13}")) {
            throw new Exception("请求时间无效");
        }
        String signature = hmacSha256Base64(GDTV_CA_SECRET, "GET\n" + url + "\n" + timestamp + "\n");
        Map<String, String> headers = new HashMap<>();
        headers.put("X-ITOUCHTV-Ca-Key", GDTV_CA_KEY);
        headers.put("X-ITOUCHTV-Ca-Signature", signature);
        headers.put("X-ITOUCHTV-Ca-Timestamp", timestamp);
        headers.put("X-ITOUCHTV-CLIENT", GDTV_CLIENT);
        return headers;
    }

    private static JSONObject requestJson(String url, String userAgent) throws Exception {
        Map<String, String> headers = signedHeaders(url);
        headers.put("X-ITOUCHTV-DEVICE-ID", GDTV_DEVICE_ID);
        headers.put("User-Agent", resolveUa(userAgent));
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", GDTV_ORIGIN);
        headers.put("Referer", GDTV_ORIGIN + "/");

        HttpFetcher.Response resp = HttpFetcher.get(url, headers);
        if (resp.code < 200 || resp.code >= 300) {
            throw new Exception("广东台接口返回 HTTP " + resp.code);
        }
        return new JSONObject(resp.bodyAsString());
    }

    // ======================== WebSocket 会话节点 ========================

    private static String wsExchangeNode(String initialNode, String userAgent) throws Exception {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            configureSni(socket);
            socket.connect(new InetSocketAddress(GDTV_SOCKET_HOST, GDTV_SOCKET_PORT), GDTV_TIMEOUT_MS);
            socket.setSoTimeout(GDTV_TIMEOUT_MS);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

            StringBuilder hs = new StringBuilder();
            hs.append("GET ").append(GDTV_SOCKET_PATH).append(" HTTP/1.1\r\n");
            hs.append("Host: ").append(GDTV_SOCKET_HOST).append(":").append(GDTV_SOCKET_PORT).append("\r\n");
            hs.append("Upgrade: websocket\r\n");
            hs.append("Connection: Upgrade\r\n");
            hs.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n");
            hs.append("Sec-WebSocket-Version: 13\r\n");
            hs.append("Origin: ").append(GDTV_ORIGIN).append("\r\n");
            hs.append("User-Agent: ").append(resolveUa(userAgent)).append("\r\n");
            hs.append("\r\n");
            out.write(hs.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String statusLine = readHttpLine(in);
            if (statusLine == null || !statusLine.contains("101")) {
                throw new IOException("WebSocket 握手失败: " + statusLine);
            }
            String line;
            while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
                // 跳过响应头
            }

            JSONObject payload = new JSONObject();
            payload.put("route", "getwsparam");
            payload.put("message", initialNode);
            wsSendFrame(out, 0x1, payload.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder text = new StringBuilder();
            while (true) {
                WsFrame frame = wsReadFrame(in);
                if (frame.opcode == 0x8) {
                    break;
                }
                if (frame.opcode == 0x9) {
                    wsSendFrame(out, 0xA, frame.payload);
                    continue;
                }
                if (frame.opcode == 0x1 || frame.opcode == 0x0) {
                    text.append(new String(frame.payload, StandardCharsets.UTF_8));
                    if (frame.fin) {
                        break;
                    }
                }
            }

            String jsonStr = text.toString();
            if (jsonStr.isEmpty()) {
                throw new Exception("广东台 WebSocket 未返回数据");
            }
            JSONObject data = new JSONObject(jsonStr);
            if (data.optInt("status", -1) != 201) {
                throw new Exception("广东台 WebSocket 会话状态异常: " + data.optInt("status", -1));
            }
            String node = data.optString("wsnode", "");
            if (node.isEmpty() || node.length() > 8192) {
                throw new Exception("广东台返回的会话节点无效");
            }
            return node;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void configureSni(SSLSocket socket) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                javax.net.ssl.SSLParameters params = socket.getSSLParameters();
                java.util.List<javax.net.ssl.SNIServerName> names = new java.util.ArrayList<>();
                names.add(new javax.net.ssl.SNIHostName(GDTV_SOCKET_HOST));
                params.setServerNames(names);
                socket.setSSLParameters(params);
            } else {
                try {
                    java.lang.reflect.Method m = socket.getClass().getMethod("setHostname", String.class);
                    m.invoke(socket, GDTV_SOCKET_HOST);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void wsSendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        byte[] header;
        if (payload.length < 126) {
            header = new byte[2];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (0x80 | payload.length);
        } else if (payload.length < 65536) {
            header = new byte[4];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (0x80 | 126);
            header[2] = (byte) (payload.length >>> 8);
            header[3] = (byte) payload.length;
        } else {
            header = new byte[10];
            header[0] = (byte) (0x80 | opcode);
            header[1] = (byte) (0x80 | 127);
            long len = payload.length;
            for (int i = 0; i < 8; i++) {
                header[2 + i] = (byte) (len >>> (56 - 8 * i));
            }
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(header);
        out.write(mask);
        out.write(masked);
        out.flush();
    }

    private static WsFrame wsReadFrame(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new IOException("WebSocket 连接关闭");
        }
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((readByte(in) & 0xFF) << 8) | (readByte(in) & 0xFF);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | (readByte(in) & 0xFF);
            }
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new WsFrame(fin, opcode, payload);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new IOException("WebSocket 连接关闭");
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("WebSocket 连接关闭");
            }
            off += n;
        }
    }

    private static String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                byte[] data = baos.toByteArray();
                int len = data.length;
                if (len > 0 && data[len - 1] == '\r') {
                    len--;
                }
                return new String(data, 0, len, StandardCharsets.ISO_8859_1);
            }
            baos.write(ch);
        }
        byte[] data = baos.toByteArray();
        return data.length > 0 ? new String(data, StandardCharsets.ISO_8859_1) : null;
    }

    private static final class WsFrame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        WsFrame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    // ======================== 完整取流 ========================

    private static String captureStream(String channelId, String userAgent) throws Exception {
        // Step1: getParam 取初始化节点
        JSONObject initial = requestJson(GDTV_PARAM_URL, userAgent);
        String node = initial != null ? initial.optString("node", "") : "";
        if (node.isEmpty() || node.length() > 8192) {
            throw new Exception("广东台返回的初始化节点无效");
        }
        if (node.endsWith("-1")) {
            node = wsExchangeNode(node, userAgent);
        }

        // Step2: 频道详情 URL（node 需 latin1 → base64）
        String nodeB64 = Base64.encodeToString(
                node.getBytes(StandardCharsets.ISO_8859_1), Base64.NO_WRAP);
        String url = GDTV_CHANNEL_API + "/" + channelId
                + "?tvChannelPk=" + channelId + "&node=" + nodeB64;

        // Step3: OPTIONS 预检（针对本次含 node 的完整频道 URL）
        Map<String, String> preflightHeaders = new HashMap<>();
        preflightHeaders.put("Origin", GDTV_ORIGIN);
        preflightHeaders.put("Access-Control-Request-Method", "GET");
        preflightHeaders.put("Access-Control-Request-Headers", GDTV_PREFLIGHT_HEADERS);
        HttpFetcher.Response preflight = HttpFetcher.options(url, preflightHeaders);
        if (preflight.code < 200 || preflight.code >= 300) {
            throw new Exception("广东台频道预检返回 HTTP " + preflight.code);
        }

        // Step4: 频道详情
        JSONObject detail = requestJson(url, userAgent);
        if (!channelId.equals(detail.optString("pk", ""))) {
            throw new Exception("广东台返回的频道不匹配");
        }

        String playUrlStr = detail.optString("playUrl", "");
        if (playUrlStr.isEmpty()) {
            throw new Exception("广东台播放地址为空");
        }
        JSONObject streams;
        try {
            streams = new JSONObject(playUrlStr);
        } catch (Exception e) {
            throw new Exception("广东台播放地址格式无效");
        }

        String stream = streams.optString("hd", "");
        if (!isOfficialStreamUrl(stream)) {
            java.util.Iterator<String> keys = streams.keys();
            while (keys.hasNext()) {
                Object v = streams.opt(keys.next());
                if (v instanceof String && isOfficialStreamUrl((String) v)) {
                    stream = (String) v;
                    break;
                }
            }
        }
        if (!isOfficialStreamUrl(stream)) {
            throw new Exception("官网没有返回有效的广东台 HLS 地址");
        }
        return stream;
    }
}