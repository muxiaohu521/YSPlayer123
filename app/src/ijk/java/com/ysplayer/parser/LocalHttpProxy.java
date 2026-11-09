package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalHttpProxy {

    private static final String TAG = "LocalHttpProxy";
    private static final long M3U8_CACHE_MS = 500;

    /** TS 分片转换回调：传入原始 TS 字节，返回处理后的字节。 */
    public interface SegmentTransformer {
        byte[] transform(byte[] raw);
    }

    /** m3u8 入口 URL 刷新回调：当 CDN 返回过期/拒绝时调用，返回新的签名 URL。 */
    public interface EntryRefresher {
        String refreshEntryUrl(String expiredUrl);
    }

    private final String mRemoteHost;
    private volatile String mM3u8Url;
    private final String mM3u8BaseUrl;
    private final String mM3u8QuerySuffix;
    private final Map<String, String> mRequestHeaders;
    private SegmentTransformer mSegmentTransformer;
    private EntryRefresher mEntryRefresher;

    private ServerSocket mServer;
    private int mPort;
    private ExecutorService mExecutor;
    private volatile boolean mRunning;

    private byte[] mM3u8Cache;
    private long mLastM3u8Fetch;

    public LocalHttpProxy(String remoteHost, String m3u8Url,
                          String m3u8BaseUrl, Map<String, String> requestHeaders) {
        this.mRemoteHost = remoteHost;
        this.mM3u8Url = m3u8Url;
        this.mM3u8BaseUrl = m3u8BaseUrl.endsWith("/")
                ? m3u8BaseUrl : m3u8BaseUrl + "/";
        this.mRequestHeaders = requestHeaders;

        int qi = m3u8Url.indexOf('?');
        this.mM3u8QuerySuffix = (qi >= 0) ? m3u8Url.substring(qi) : "";
    }

    /** 设置 TS 分片转换器（如解密），传 null 则不做转换。 */
    public void setSegmentTransformer(SegmentTransformer transformer) {
        this.mSegmentTransformer = transformer;
    }

    /** 设置 m3u8 入口 URL 刷新回调，传 null 则禁用自动刷新。 */
    public void setEntryRefresher(EntryRefresher refresher) {
        this.mEntryRefresher = refresher;
    }

    public synchronized int start() throws IOException {
        if (mRunning) return mPort;
        mServer = new ServerSocket();
        mServer.setReuseAddress(true);
        mServer.bind(new InetSocketAddress("127.0.0.1", 0));
        mPort = mServer.getLocalPort();
        mExecutor = Executors.newFixedThreadPool(4);
        mRunning = true;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        });
        LogUtil.i(TAG + ": server started on port " + mPort);
        return mPort;
    }

    public synchronized void stop() {
        mRunning = false;
        if (mExecutor != null) {
            mExecutor.shutdownNow();
            mExecutor = null;
        }
        if (mServer != null) {
            try { mServer.close(); } catch (IOException ignored) { }
            mServer = null;
        }
    }

    public String getLocalUrl() {
        return "http://127.0.0.1:" + mPort + "/proxy.m3u8";
    }

    private void acceptLoop() {
        while (mRunning) {
            try {
                final Socket client = mServer.accept();
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                });
            } catch (IOException e) {
                if (mRunning) {
                    LogUtil.e(TAG + ": accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                client.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                client.close();
                return;
            }
            String method = parts[0];
            String path = parts[1];

            Map<String, String> reqHeaders = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int ci = line.indexOf(':');
                if (ci > 0) {
                    reqHeaders.put(line.substring(0, ci).trim().toLowerCase(),
                            line.substring(ci + 1).trim());
                }
            }

            if ("/proxy.m3u8".equals(path)) {
                serveM3u8(out);
            } else if (path.startsWith("/seg/")) {
                serveSegment(path, out);
            } else {
                sendNotFound(out);
            }

            client.close();
        } catch (Exception e) {
            LogUtil.e(TAG + ": client error: " + e.getMessage());
            try { client.close(); } catch (IOException ignored) { }
        }
    }

    private void serveM3u8(OutputStream out) throws IOException {
        byte[] m3u8 = fetchM3u8();
        if (m3u8 == null) {
            sendNotFound(out);
            return;
        }
        String body = new String(m3u8, "UTF-8");
        StringBuilder sb = new StringBuilder();
        for (String line : body.split("\r?\n")) {
            if (!line.startsWith("#") && !line.trim().isEmpty()) {
                String fullUrl;
                if (line.startsWith("http://") || line.startsWith("https://")) {
                    fullUrl = line;
                } else {
                    fullUrl = mM3u8BaseUrl + line;
                    if (!mM3u8QuerySuffix.isEmpty() && !line.contains("?")) {
                        fullUrl += mM3u8QuerySuffix;
                    }
                }
                String encoded = HttpUtils.encodeUrl(fullUrl);
                sb.append("http://127.0.0.1:").append(mPort)
                        .append("/seg/").append(encoded).append("\r\n");
            } else {
                sb.append(line).append("\r\n");
            }
        }

        byte[] content = sb.toString().getBytes("UTF-8");
        sendResponse(out, 200, "OK", "application/vnd.apple.mpegurl", content);
    }

    private byte[] fetchM3u8() {
        long now = System.currentTimeMillis();
        if (mM3u8Cache != null && (now - mLastM3u8Fetch) < M3U8_CACHE_MS) {
            return mM3u8Cache;
        }
        byte[] result = doFetchM3u8();
        if (result != null) return result;

        if (mEntryRefresher != null) {
            LogUtil.w(TAG + ": m3u8 fetch failed, trying entry refresh...");
            String newUrl = mEntryRefresher.refreshEntryUrl(mM3u8Url);
            if (newUrl != null && !newUrl.equals(mM3u8Url)) {
                mM3u8Url = newUrl;
                LogUtil.i(TAG + ": entry refreshed, retrying m3u8 fetch");
                byte[] refreshed = doFetchM3u8();
                if (refreshed != null) return refreshed;
            }
        }
        return mM3u8Cache;
    }

    private byte[] doFetchM3u8() {
        long now = System.currentTimeMillis();
        try {
            URL urlObj = new URL(mM3u8Url);
            HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            if (mRequestHeaders != null) {
                for (Map.Entry<String, String> e : mRequestHeaders.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                LogUtil.e(TAG + ": m3u8 refresh HTTP " + code);
                conn.disconnect();
                return null;
            }
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            is.close();
            conn.disconnect();
            mM3u8Cache = baos.toByteArray();
            mLastM3u8Fetch = now;
            LogUtil.d(TAG + ": m3u8 refreshed, " + mM3u8Cache.length + " bytes");
            return mM3u8Cache;
        } catch (IOException e) {
            LogUtil.e(TAG + ": m3u8 fetch failed: " + e.getMessage());
            return null;
        }
    }

    private void serveSegment(String path, OutputStream out) throws IOException {
        String encoded = path.substring(5);
        String remoteUrl = HttpUtils.decodeUrl(encoded);

        LogUtil.d(TAG + ": fetching segment: " + remoteUrl);

        URL urlObj = new URL(remoteUrl);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Origin", "https://www.qtv.com.cn");
        conn.setRequestProperty("Referer", "https://www.qtv.com.cn/");
        if (mRequestHeaders != null) {
            for (Map.Entry<String, String> e : mRequestHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        int code = conn.getResponseCode();
        if (code != 200) {
            LogUtil.e(TAG + ": segment HTTP " + code + " for " + remoteUrl);
            conn.disconnect();
            sendNotFound(out);
            return;
        }

        InputStream bodyIn = conn.getInputStream();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = bodyIn.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        bodyIn.close();
        conn.disconnect();

        byte[] segmentData = baos.toByteArray();
        if (mSegmentTransformer != null) {
            segmentData = mSegmentTransformer.transform(segmentData);
        }

        sendResponse(out, 200, "OK", "video/mp2t", segmentData);
    }

    private void sendResponse(OutputStream out, int code, String msg,
                               String contentType, byte[] body) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(" ").append(msg).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("\r\n");
        header.append("Content-Length: ").append(body.length).append("\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("Connection: close\r\n");
        header.append("\r\n");
        out.write(header.toString().getBytes("US-ASCII"));
        out.write(body);
        out.flush();
    }

    private void sendNotFound(OutputStream out) throws IOException {
        String body = "Not Found";
        sendResponse(out, 404, "Not Found", "text/plain", body.getBytes("US-ASCII"));
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int prev = -1;
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                if (prev == '\r') {
                    byte[] data = baos.toByteArray();
                    return new String(data, 0, data.length - 1, "ISO-8859-1");
                }
            }
            baos.write(ch);
            prev = ch;
        }
        byte[] data = baos.toByteArray();
        return data.length > 0 ? new String(data, "ISO-8859-1") : null;
    }

    private static final class HttpUtils {
        private static String encodeUrl(String url) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < url.length(); i++) {
                char c = url.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '-' || c == '_'
                        || c == '.' || c == '~' || c == '/' || c == ':'
                        || c == '?' || c == '=' || c == '&' || c == '%') {
                    sb.append(c);
                } else {
                    sb.append(String.format("%%%02X", (int) c));
                }
            }
            return sb.toString();
        }

        private static String decodeUrl(String encoded) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < encoded.length(); i++) {
                char c = encoded.charAt(i);
                if (c == '%' && i + 2 < encoded.length()) {
                    try {
                        sb.append((char) Integer.parseInt(
                                encoded.substring(i + 1, i + 3), 16));
                        i += 2;
                    } catch (Exception e) {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
    }
}