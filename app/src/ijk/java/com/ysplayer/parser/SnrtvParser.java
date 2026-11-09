package com.ysplayer.parser;

import android.util.Base64;

import com.ysplayer.LogUtil;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SnrtvParser extends BaseParser {

    private static final String TAG = "SnrtvParser";
    private static final String DOMAIN = "snrtv.com";
    private static final String CATALOG_URL =
            "https://toutiao.cnwest.com/static/v1/group/stream.js";
    private static final Pattern S_TV_RE =
            Pattern.compile("\\bvar\\s+sTV\\s*=\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final Pattern S_RADIO_RE =
            Pattern.compile("\\bvar\\s+sRadio\\s*=\\s*\"([A-Za-z0-9+/=]+)\"");
    private static final long CATALOG_REFRESH_MS = 5 * 60 * 1000L;
    private static final long CATALOG_HARD_TTL_MS = 24 * 60 * 60 * 1000L;

    private static volatile Map<String, String> sCatalogCache;
    private static volatile long sCatalogRefreshAt;
    private static volatile long sCatalogHardExpiresAt;
    private static final Object sCatalogLock = new Object();

    @Override
    public String getName() {
        return "陕西网络广播电视台";
    }

    @Override
    public void clearCache() {
        sCatalogCache = null;
        sCatalogRefreshAt = 0;
        sCatalogHardExpiresAt = 0;
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
                    String key = extractChannelKey(url);
                    if (key == null) {
                        callback.onError("陕西台URL缺少频道标识");
                        return;
                    }

                    String m3u8Url = resolveM3u8(key);
                    if (m3u8Url == null) {
                        callback.onError("陕西台未找到频道流地址");
                        return;
                    }

                    Map<String, String> headers = new HashMap<>();
                    headers.put("Referer", "http://live.snrtv.com/");

                    LogUtil.i(TAG + ": key=" + key + " -> " + m3u8Url);
                    callback.onSuccess(new ParseResult(m3u8Url, "陕西", "默认",
                            headers, null));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("陕西台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String extractChannelKey(String url) {
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0) return null;
        String key = url.substring(lastSlash + 1);
        int q = key.indexOf('?');
        if (q >= 0) key = key.substring(0, q);
        if (key.isEmpty()) return null;
        return key;
    }

    private static String resolveM3u8(String key) throws Exception {
        Map<String, String> catalog = getCatalog();
        if (catalog == null) return null;

        String m3u8Url = catalog.get(key);
        if (m3u8Url == null) {
            LogUtil.e(TAG + ": key=" + key + " 不在频道目录中");
            return null;
        }

        return m3u8Url + "?_=" + (System.currentTimeMillis() / 1000L);
    }

    private static Map<String, String> getCatalog() {
        long now = System.currentTimeMillis();

        Map<String, String> hit = sCatalogCache;
        if (hit != null && now < sCatalogRefreshAt) {
            return hit;
        }

        synchronized (sCatalogLock) {
            hit = sCatalogCache;
            if (hit != null && now < sCatalogRefreshAt) {
                return hit;
            }

            try {
                LogUtil.d(TAG + ": 拉取频道目录...");
                Map<String, String> fresh =
                        fetchAndDecodeCatalog();
                sCatalogCache = fresh;
                sCatalogRefreshAt = now + CATALOG_REFRESH_MS;
                sCatalogHardExpiresAt = now + CATALOG_HARD_TTL_MS;
                LogUtil.i(TAG + ": 频道目录更新成功, " + fresh.size() + "个频道");
                return fresh;
            } catch (Exception e) {
                LogUtil.e(TAG + ": 目录拉取失败", e);
                if (hit != null && now < sCatalogHardExpiresAt) {
                    sCatalogRefreshAt = now + CATALOG_REFRESH_MS;
                    return hit;
                }
                return null;
            }
        }
    }

    private static Map<String, String> fetchAndDecodeCatalog()
            throws Exception {
        Map<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                + "AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36");
        reqHeaders.put("Accept", "*/*");
        reqHeaders.put("Referer", "http://live.snrtv.com/");

        HttpFetcher.Response resp = HttpFetcher.get(CATALOG_URL, reqHeaders);
        if (resp.code != 200) {
            throw new Exception("stream.js HTTP " + resp.code);
        }

        String script = resp.bodyAsString();
        return decodeCatalog(script);
    }

    static Map<String, String> decodeCatalog(String script) throws Exception {
        Matcher tvMatcher = S_TV_RE.matcher(script);
        Matcher radioMatcher = S_RADIO_RE.matcher(script);

        if (!tvMatcher.find() || !radioMatcher.find()) {
            throw new Exception("频道目录格式已变化：找不到sTV/sRadio");
        }

        String sTv = tvMatcher.group(1);
        String sRadio = radioMatcher.group(1);

        if (sTv.length() < 32 || sRadio.length() < 16) {
            throw new Exception("频道目录密钥长度异常");
        }

        byte[] keyBytes = sTv.substring(0, 16).getBytes("UTF-8");
        byte[] ivBytes = sRadio.substring(0, 16).getBytes("UTF-8");
        byte[] encrypted = Base64.decode(sTv.substring(16), Base64.DEFAULT);

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
        byte[] decrypted = cipher.doFinal(encrypted);

        String jsonStr = new String(decrypted, "UTF-8").replaceFirst("\0+$", "");

        org.json.JSONObject data = new org.json.JSONObject(jsonStr);
        org.json.JSONObject sxbc = data.getJSONObject("sxbc");
        if (sxbc == null) {
            throw new Exception("频道目录中没有电视分组");
        }

        Map<String, String> urls = new HashMap<>();
        java.util.Iterator<String> it = sxbc.keys();
        while (it.hasNext()) {
            String key = it.next();
            org.json.JSONObject ch = sxbc.optJSONObject(key);
            if (ch == null) continue;
            String m3u8 = ch.optString("m3u8", "");
            if (m3u8.isEmpty()) continue;

            try {
                java.net.URL parsed = new java.net.URL(m3u8);
                if (!"stream.snrtv.com".equals(parsed.getHost())) continue;
                String path = parsed.getPath();
                if (path == null || !path.endsWith(".m3u8")) continue;
                urls.put(key, m3u8);
                LogUtil.d(TAG + ": 发现频道 key=" + key + " name=" +
                        ch.optString("name", "") + " url=" + m3u8);
            } catch (Exception e) {
                LogUtil.e(TAG + ": 频道地址无效 key=" + key, e);
            }
        }

        if (urls.isEmpty()) {
            throw new Exception("频道目录中没有可用频道");
        }
        return urls;
    }
}