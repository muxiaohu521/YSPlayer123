package com.ysplayer.parser;

import android.util.Base64;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class IqiluParser extends BaseParser {

    private static final String TAG = "IqiluParser";
    private static final String DOMAIN = "iqilu.com";

    private static final byte[] ZERO_IV = new byte[]{
        0x30,0x30,0x30,0x30,0x30,0x30,0x30,0x30,
        0x30,0x30,0x30,0x30,0x30,0x30,0x30,0x30
    };

    private static final Pattern VAR_PAT =
            Pattern.compile("var\\s+(\\w+)\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern SLUG_PAT =
            Pattern.compile("/live/([a-z0-9]+)", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "齐鲁网(山东)";
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
                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://v.iqilu.com/", userAgent);

                    HttpFetcher.Response resp = HttpFetcher.get(url, headers);
                    if (resp.code != 200) {
                        callback.onError("齐鲁网页面返回状态码: " + resp.code);
                        return;
                    }

                    String html = resp.bodyAsString();
                    IqiluAuth auth = extractAuth(html);
                    if (auth == null || !auth.isValid()) {
                        String directM3u8 = extractM3u8FromHtml(html);
                        if (directM3u8 != null) {
                            callback.onSuccess(buildSimpleResult(directM3u8,
                                    "山东频道", "默认", headers));
                            return;
                        }
                        callback.onError("齐鲁网页面中无法提取认证参数");
                        return;
                    }

                    String slug = extractSlug(url);
                    String name = slugToName(slug);

                    LogUtil.d(TAG + ": slug=" + slug + " id=" + auth.pdCid
                            + " signSecret=" + auth.signSecret.substring(0, 4) + "..."
                            + " aesKey=" + auth.aesKey.substring(0, 4) + "..."
                            + " endpoint=" + auth.endpoint);

                    String m3u8Url = exchangeForStream(auth, userAgent);
                    if (m3u8Url == null) {
                        callback.onError("齐鲁网获取播放地址失败");
                        return;
                    }

                    Map<String, String> playHeaders = defaultHeadersWithUA(
                            "https://v.iqilu.com/", userAgent);
                    playHeaders.put("Origin", "https://v.iqilu.com");
                    playHeaders.put("Referer", url);

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(m3u8Url, "720P", 1280, 720));

                    LogUtil.i(TAG + ": " + url + " -> " + name + " (720P)");

                    callback.onSuccess(new ParseResult(m3u8Url, name, "720P",
                            playHeaders, options));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("齐鲁网解析异常: " + e.getMessage());
                }
            }
        });
    }

    private ParseResult buildSimpleResult(String url, String title, String quality,
            Map<String, String> headers) {
        List<ParseResult.QualityOption> options = new ArrayList<>();
        options.add(new ParseResult.QualityOption(url, quality, 0, 0));
        return new ParseResult(url, title, quality, headers, options);
    }

    private static class IqiluAuth {
        String pdCid;
        String endpoint;
        String signSecret;
        String aesKey;

        boolean isValid() {
            return pdCid != null && pdCid.matches("\\d{1,8}")
                    && endpoint != null && endpoint.startsWith("https://feiying.litenews.cn")
                    && signSecret != null && signSecret.length() == 16
                    && aesKey != null && aesKey.length() == 16;
        }
    }

    private IqiluAuth extractAuth(String html) {
        if (html == null) return null;
        Map<String, String> vars = extractAllVars(html);
        if (vars.isEmpty()) return null;

        IqiluAuth auth = new IqiluAuth();
        auth.pdCid = vars.get("_pdCid");

        String dF = vars.get("dF");
        String aF = vars.get("aF");
        if (dF != null && aF != null) {
            auth.endpoint = dF + aF;
        }

        auth.signSecret = vars.get("mxpx");
        auth.aesKey = vars.get("aly");
        return auth;
    }

    private Map<String, String> extractAllVars(String html) {
        Map<String, String> vars = new HashMap<>();
        Matcher m = VAR_PAT.matcher(html);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2);
            if (key != null && val != null) {
                if (!vars.containsKey(key)) { vars.put(key, val); }
            }
        }
        return vars;
    }

    private String extractSlug(String url) {
        if (url == null) return "sdtv";
        Matcher m = SLUG_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return "sdtv";
    }

    private String slugToName(String slug) {
        switch (slug) {
            case "sdtv": return "山东卫视";
            case "qlpd": return "齐鲁频道";
            case "ggpd": return "山东新闻";
            case "typd": return "山东体育休闲";
            case "shpd": return "山东生活";
            case "zypd": return "山东综艺";
            case "nkpd": return "山东农科";
            case "yspd": return "山东文旅";
            case "sepd": return "山东少儿";
            default: return "山东频道";
        }
    }

    private String exchangeForStream(IqiluAuth auth, String userAgent) {
        try {
            long timestamp = System.currentTimeMillis();
            String tsStr = String.valueOf(timestamp);

            String signStr = auth.pdCid + tsStr + auth.signSecret;
            String sign = md5Hex(signStr);

            String exchangeUrl = auth.endpoint + "?t=" + tsStr + "&s=" + sign;

            String bodyJson = "{\"channelMark\":\"" + auth.pdCid + "\"}";
            String encBody = aesEncryptWithKey(bodyJson, auth.aesKey);
            if (encBody == null) return null;

            Map<String, String> postHeaders = defaultHeadersWithUA(
                    "https://v.iqilu.com/", userAgent);
            postHeaders.put("Content-Type", "text/plain");
            postHeaders.put("Origin", "https://v.iqilu.com");
            postHeaders.put("Referer", "https://v.iqilu.com/");
            postHeaders.put("Accept", "*/*");

            LogUtil.d(TAG + ": POST " + exchangeUrl.substring(0, 80) + "...");

            HttpFetcher.Response resp = HttpFetcher.post(
                    exchangeUrl, postHeaders, encBody.getBytes("UTF-8"));

            if (resp.code != 200) {
                LogUtil.e(TAG + ": exchange returned " + resp.code);
                return null;
            }

            String decrypted = aesDecryptWithKey(resp.bodyAsString().trim(), auth.aesKey);
            if (decrypted == null) return null;

            JSONObject json = new JSONObject(decrypted);
            if (json.optInt("code", -1) != 1) {
                LogUtil.e(TAG + ": exchange code != 1: " + json.optString("msg", ""));
                return null;
            }

            String url = json.optString("data", "");
            if (!url.isEmpty() && url.startsWith("http")) return url;

            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                url = data.optString("playUrl", data.optString("streamUrl", ""));
                if (!url.isEmpty()) return url;
            }

            return null;
        } catch (Exception e) {
            LogUtil.e(TAG + ": exchange failed", e);
            return null;
        }
    }

    private static String aesEncryptWithKey(String plainText, String keyStr) {
        try {
            byte[] keyBytes = keyStr.getBytes("UTF-8");
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ZERO_IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plainText.getBytes("UTF-8"));
            return Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } catch (Exception e) {
            LogUtil.e(TAG + ": AES encrypt error", e);
            return null;
        }
    }

    private static String aesDecryptWithKey(String base64Data, String keyStr) {
        try {
            byte[] keyBytes = keyStr.getBytes("UTF-8");
            byte[] cipherText = Base64.decode(base64Data, Base64.DEFAULT);
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ZERO_IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, "UTF-8");
        } catch (Exception e) {
            LogUtil.e(TAG + ": AES decrypt error", e);
            return null;
        }
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}