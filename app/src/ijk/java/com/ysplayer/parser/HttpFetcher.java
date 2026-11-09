package com.ysplayer.parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class HttpFetcher {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int MAX_REDIRECTS = 5;

    private HttpFetcher() {
    }

    public static Response get(String url) throws IOException {
        return get(url, null);
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return request(url, "GET", headers, null);
    }

    public static Response post(String url, Map<String, String> headers, byte[] body) throws IOException {
        return request(url, "POST", headers, body);
    }

    private static Response request(String url, String method,
            Map<String, String> headers, byte[] body) throws IOException {
        HttpURLConnection conn = null;
        int redirects = 0;

        while (redirects <= MAX_REDIRECTS) {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept-Encoding", "gzip");
            conn.setRequestProperty("Accept",
                    "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            conn.setRequestProperty("Cache-Control", "no-cache, no-store");
            conn.setRequestProperty("Pragma", "no-cache");

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            if (body != null) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                    os.flush();
                }
            }

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without Location header");
                }
                if (!location.startsWith("http")) {
                    URL base = new URL(url);
                    location = new URL(base, location).toString();
                }
                url = location;
                redirects++;
                conn.disconnect();
                continue;
            }

            int respCode = conn.getResponseCode();
            Map<String, String> respHeaders = new HashMap<>();
            Map<String, java.util.List<String>> allHeaders = new HashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                String key = e.getKey();
                java.util.List<String> vals = e.getValue();
                if (key != null && vals != null && !vals.isEmpty()) {
                    respHeaders.put(key, vals.get(0));
                    allHeaders.put(key, vals);
                }
            }

            byte[] data;
            String contentType = conn.getContentType();
            String contentEncoding = conn.getContentEncoding();

            if (respCode >= 200 && respCode < 300) {
                InputStream is = conn.getInputStream();
                if ("gzip".equalsIgnoreCase(contentEncoding)) {
                    is = new GZIPInputStream(is);
                }
                data = readAll(is);
            } else {
                InputStream es = conn.getErrorStream();
                if (es == null) {
                    data = new byte[0];
                } else {
                    if ("gzip".equalsIgnoreCase(contentEncoding)) {
                        es = new GZIPInputStream(es);
                    }
                    data = readAll(es);
                }
            }

            conn.disconnect();
            return new Response(respCode, data, respHeaders, allHeaders, contentType, url);
        }

        throw new IOException("Too many redirects: " + url);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        is.close();
        return bos.toByteArray();
    }

    public static class Response {
        public final int code;
        public final byte[] body;
        public final Map<String, String> headers;
        public final Map<String, java.util.List<String>> allHeaders;
        public final String contentType;
        public final String finalUrl;

        Response(int code, byte[] body, Map<String, String> headers,
                Map<String, java.util.List<String>> allHeaders,
                String contentType, String finalUrl) {
            this.code = code;
            this.body = body;
            this.headers = headers;
            this.allHeaders = allHeaders;
            this.contentType = contentType;
            this.finalUrl = finalUrl;
        }

        public String bodyAsString() {
            if (body == null || body.length == 0) return "";
            String ct = contentType;
            String charset = "UTF-8";
            if (ct != null) {
                int idx = ct.indexOf("charset=");
                if (idx >= 0) {
                    charset = ct.substring(idx + 8).trim();
                    int semi = charset.indexOf(';');
                    if (semi >= 0) charset = charset.substring(0, semi).trim();
                }
            }
            try {
                return new String(body, charset);
            } catch (Exception e) {
                return new String(body);
            }
        }
    }
}