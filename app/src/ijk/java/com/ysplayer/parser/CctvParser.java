package com.ysplayer.parser;

import android.util.Base64;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CctvParser extends BaseParser {

    private static final String TAG = "CctvParser";
    private static final String DOMAIN = "tv.cctv.com";

    private static final String VERSION_URL =
            "https://api.live.cntv.cn/livestatic/zs/livestatic_config/pcweb/version.json";

    private static final String[] LIVEPLAYER_ALT_URLS = {
            "https://js.player.cntv.cn/creator/liveplayer.js",
            "https://player.cntv.cn/html5Player/js/liveplayer.js"
    };

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final long KEY_CACHE_TTL_MS = 60 * 60 * 1000L;

    private static byte[] sCachedAesKey;
    private static byte[] sCachedAesIv;
    private static long sKeyCacheTimestamp;

    private static volatile Map<String, String> sChannelUrlCache;
    private static volatile long sChannelCacheTimestamp;
    private static final Object sChannelLock = new Object();

    private static final Pattern LIVEPLAYER_KEY_PATTERN =
            Pattern.compile("[\"']([A-Za-z0-9+/]{43}=)[\"']");
    private static final Pattern LIVEPLAYER_IV_PATTERN =
            Pattern.compile("[\"']([A-Za-z0-9+/]{22}==)[\"']");

    private static final String[] CDN_PRIORITY = {
            "myqcloud.com", "myalicdn.com", "live.cntv.cn",
            "cntv.wscdns.com", "cdn.cctv.cn", "bdydns.com"
    };

    private static final String[] TEMPLATE_PRIORITY = {
            "hls_cdrm", "hls_cdrm_timeshift", "hls_cdrm_av1",
            "hls_nd", "hls_nd_timeshift", "hls_nd_4k"
    };

    private static final Map<String, String> CHANNEL_NAMES = new HashMap<>();
    static {
        CHANNEL_NAMES.put("cctv1",  "CCTV-1综合");
        CHANNEL_NAMES.put("cctv2",  "CCTV-2财经");
        CHANNEL_NAMES.put("cctv3",  "CCTV-3综艺");
        CHANNEL_NAMES.put("cctv4",  "CCTV-4中文国际");
        CHANNEL_NAMES.put("cctv5",  "CCTV-5体育");
        CHANNEL_NAMES.put("cctv5plus","CCTV-5+体育赛事");
        CHANNEL_NAMES.put("cctv6",  "CCTV-6电影");
        CHANNEL_NAMES.put("cctv7",  "CCTV-7国防军事");
        CHANNEL_NAMES.put("cctv8",  "CCTV-8电视剧");
        CHANNEL_NAMES.put("cctv9",  "CCTV-9纪录");
        CHANNEL_NAMES.put("cctv10", "CCTV-10科教");
        CHANNEL_NAMES.put("cctv11", "CCTV-11戏曲");
        CHANNEL_NAMES.put("cctv12", "CCTV-12社会与法");
        CHANNEL_NAMES.put("cctv13", "CCTV-13新闻");
        CHANNEL_NAMES.put("cctv14", "CCTV-14少儿");
        CHANNEL_NAMES.put("cctv15", "CCTV-15音乐");
        CHANNEL_NAMES.put("cctv16", "CCTV-16奥林匹克");
        CHANNEL_NAMES.put("cctv17", "CCTV-17农业农村");
        CHANNEL_NAMES.put("cctv4k", "CCTV-4K超高清");
        CHANNEL_NAMES.put("cctv8k", "CCTV-8K超高清");
        CHANNEL_NAMES.put("4kcctv16","CCTV-16 4K");
        CHANNEL_NAMES.put("cctvchild","CCTV-少儿");
        CHANNEL_NAMES.put("cctvjilu","CCTV-纪录");
        CHANNEL_NAMES.put("cctveurope","CGTN欧洲");
        CHANNEL_NAMES.put("cctvamerica","CGTN美洲");
        CHANNEL_NAMES.put("cctvdocument","CGTN纪录");
        CHANNEL_NAMES.put("cetv1", "中国教育1");
        CHANNEL_NAMES.put("cetv2", "中国教育2");
        CHANNEL_NAMES.put("cetv3", "中国教育3");
        CHANNEL_NAMES.put("cetv4", "中国教育4");
        CHANNEL_NAMES.put("anhui",      "安徽卫视");
        CHANNEL_NAMES.put("beijing",    "北京卫视");
        CHANNEL_NAMES.put("btv1",       "北京卫视");
        CHANNEL_NAMES.put("chongqing",  "重庆卫视");
        CHANNEL_NAMES.put("dongfang",   "东方卫视");
        CHANNEL_NAMES.put("dongnan",    "东南卫视");
        CHANNEL_NAMES.put("gansu",      "甘肃卫视");
        CHANNEL_NAMES.put("guangdong",  "广东卫视");
        CHANNEL_NAMES.put("guangxi",    "广西卫视");
        CHANNEL_NAMES.put("guizhou",    "贵州卫视");
        CHANNEL_NAMES.put("hebei",      "河北卫视");
        CHANNEL_NAMES.put("heilongjiang","黑龙江卫视");
        CHANNEL_NAMES.put("henan",      "河南卫视");
        CHANNEL_NAMES.put("hubei",      "湖北卫视");
        CHANNEL_NAMES.put("hunan",      "湖南卫视");
        CHANNEL_NAMES.put("jiangxi",    "江西卫视");
        CHANNEL_NAMES.put("jilin",      "吉林卫视");
        CHANNEL_NAMES.put("liaoning",   "辽宁卫视");
        CHANNEL_NAMES.put("neimenggu",  "内蒙古卫视");
        CHANNEL_NAMES.put("ningxia",    "宁夏卫视");
        CHANNEL_NAMES.put("qinghai",    "青海卫视");
        CHANNEL_NAMES.put("shandong",   "山东卫视");
        CHANNEL_NAMES.put("shan1xi",    "陕西卫视");
        CHANNEL_NAMES.put("shan3xi",    "山西卫视");
        CHANNEL_NAMES.put("shenzhen",   "深圳卫视");
        CHANNEL_NAMES.put("sichuan",    "四川卫视");
        CHANNEL_NAMES.put("tianjin",    "天津卫视");
        CHANNEL_NAMES.put("xinjiang",   "新疆卫视");
        CHANNEL_NAMES.put("xizang",     "西藏卫视");
        CHANNEL_NAMES.put("yunnan",     "云南卫视");
        CHANNEL_NAMES.put("zhejiang",   "浙江卫视");
    }

    private static final Map<String, String> CHANNEL_ALIAS = new HashMap<>();
    static {
        CHANNEL_ALIAS.put("beijing", "btv1");
        CHANNEL_ALIAS.put("zhejiang", null);
        CHANNEL_ALIAS.put("hunan", null);
    }

    private static final Pattern CHANNEL_PAT =
            Pattern.compile("/live/([a-z0-9]+)/?", Pattern.CASE_INSENSITIVE);

    @Override
    public String getName() {
        return "央视网";
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
                    String channelId = extractChannelId(url);
                    if (channelId == null || channelId.isEmpty()) {
                        callback.onError("无法从URL中提取频道ID: " + url);
                        return;
                    }

                    Map<String, String> channels = getChannelUrls();
                    if (channels == null) {
                        callback.onError("获取频道列表失败");
                        return;
                    }

                    String resolvedId = channelId;
                    if (CHANNEL_ALIAS.containsKey(channelId)) {
                        String alias = CHANNEL_ALIAS.get(channelId);
                        if (alias == null) {
                            callback.onError("频道 " + channelId + " 需专用解析器");
                            return;
                        }
                        resolvedId = alias;
                    }

                    String m3u8Url = channels.get(resolvedId);
                    if (m3u8Url == null) {
                        callback.onError("频道 " + channelId + " 不在免费频道列表中");
                        return;
                    }

                    String originalUrl = m3u8Url;
                    m3u8Url = convertCdrmToHls(m3u8Url);
                    if (!m3u8Url.equals(originalUrl)) {
                        LogUtil.i(TAG + ": CDRM->HLS: " + originalUrl + " => " + m3u8Url);
                    }

                    String name = CHANNEL_NAMES.containsKey(channelId)
                            ? CHANNEL_NAMES.get(channelId)
                            : (CHANNEL_NAMES.containsKey(resolvedId)
                               ? CHANNEL_NAMES.get(resolvedId) : channelId);

                    Map<String, String> playHeaders = defaultHeadersWithUA(
                            "https://tv.cctv.com/", userAgent);
                    playHeaders.put("Origin", "https://tv.cctv.com");
                    playHeaders.put("Referer", "https://tv.cctv.com/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(m3u8Url, "720P", 1280, 720));

                    LogUtil.i(TAG + ": " + url + " -> " + name + " (720P) [AUDIO-ONLY]");

                    ParseResult result = new ParseResult(m3u8Url, name, "720P",
                            playHeaders, options);
                    result.setAudioOnly(true);
                    callback.onSuccess(result);

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed for " + url, e);
                    callback.onError("央视网解析异常: " + e.getMessage());
                }
            }
        });
    }

    public static String convertCdrmToHls(String url) {
        if (url == null || url.isEmpty()) return url;

        LogUtil.i(TAG + ": convertCdrmToHls input: " + url);

        boolean changed = false;

        if (url.contains("dh5.cntv.")) {
            url = url.replace("dh5.cntv.", "hls.cntv.");
            changed = true;
        }

        if (url.contains("/asp/h5e/hls/")) {
            url = url.replace("/asp/h5e/hls/", "/asp/hls/");
            changed = true;
        }

        if (!changed) {
            if (url.contains("/asp/h5e/")) {
                url = url.replace("/asp/h5e/", "/asp/");
                changed = true;
            }
        }

        if (changed) {
            LogUtil.i(TAG + ": CDRM URL converted to plain HLS: " + url);
        } else {
            LogUtil.i(TAG + ": convertCdrmToHls no match, url unchanged");
        }

        return url;
    }

    private String extractChannelId(String url) {
        if (url == null) return null;
        Matcher m = CHANNEL_PAT.matcher(url);
        if (m.find()) return m.group(1).toLowerCase();
        return null;
    }

    private Map<String, String> getChannelUrls() {
        long now = System.currentTimeMillis();
        if (sChannelUrlCache != null && (now - sChannelCacheTimestamp) < CACHE_TTL_MS) {
            return sChannelUrlCache;
        }
        synchronized (sChannelLock) {
            if (sChannelUrlCache != null && (now - sChannelCacheTimestamp) < CACHE_TTL_MS) {
                return sChannelUrlCache;
            }
            sChannelUrlCache = fetchAndDecryptChannels();
            sChannelCacheTimestamp = System.currentTimeMillis();
            return sChannelUrlCache;
        }
    }

    private Map<String, String> fetchAndDecryptChannels() {
        try {
            Map<String, String> headers = defaultHeaders("https://tv.cctv.com/");
            HttpFetcher.Response versionResp = HttpFetcher.get(VERSION_URL, headers);
            if (versionResp.code != 200) {
                LogUtil.e(TAG + ": version.json returned " + versionResp.code);
                return null;
            }

            JSONObject versionJson = new JSONObject(versionResp.bodyAsString());
            String configUrl = versionJson.getJSONObject("emconfig")
                    .getJSONObject("1001")
                    .getString("channelFilePath");

            LogUtil.d(TAG + ": encrypted config URL: " + configUrl);

            HttpFetcher.Response configResp = HttpFetcher.get(configUrl, headers);
            if (configResp.code != 200) {
                LogUtil.e(TAG + ": config download failed: " + configResp.code);
                return null;
            }

            String encryptedB64 = configResp.bodyAsString().trim();
            String json = aesDecrypt(encryptedB64);
            if (json == null) {
                LogUtil.e(TAG + ": AES decrypt failed");
                return null;
            }

            return parseChannelConfig(json);
        } catch (Exception e) {
            LogUtil.e(TAG + ": fetch channels failed", e);
            return null;
        }
    }

    private Map<String, String> parseChannelConfig(String json) {
        Map<String, String> channels = new HashMap<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray policies = root.optJSONArray("live_policy");
            JSONArray policiesExt = root.optJSONArray("live_policy_ext");

            for (JSONArray arr : new JSONArray[]{policies, policiesExt}) {
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject group = arr.optJSONObject(i);
                    if (group == null) continue;

                    JSONObject manifest = group.optJSONObject("cn_manifest");
                    if (manifest == null) {
                        manifest = group.optJSONObject("fn_manifest");
                    }
                    if (manifest == null) continue;

                    String hls720Url = pickSingleTemplate(manifest);
                    if (hls720Url == null) continue;

                    JSONArray chList = group.optJSONArray("channels");
                    if (chList == null) continue;

                    for (int j = 0; j < chList.length(); j++) {
                        JSONObject ch = chList.optJSONObject(j);
                        if (ch == null) continue;
                        String channelId = ch.optString("channel", "");
                        if (channelId.isEmpty()) continue;
                        if (CHANNEL_NAMES.containsKey(channelId)) {
                            String url;
                            if (hls720Url.contains("${channel}")) {
                                url = hls720Url.replace("${channel}", channelId);
                            } else {
                                url = hls720Url;
                            }
                            channels.put(channelId, url);
                        }
                    }
                }
            }

            LogUtil.i(TAG + ": decrypted " + channels.size() + " free channels");
        } catch (Exception e) {
            LogUtil.e(TAG + ": parse channel config failed", e);
        }
        return channels;
    }

    private String pickSingleTemplate(JSONObject manifest) {
        for (String tkey : TEMPLATE_PRIORITY) {
            JSONArray urls = manifest.optJSONArray(tkey);
            if (urls == null || urls.length() == 0) continue;
            LogUtil.i(TAG + ": template[" + tkey + "] count=" + urls.length());
            for (int k = 0; k < urls.length(); k++) {
                LogUtil.i(TAG + ":   [" + k + "] " + urls.optString(k, ""));
            }
            List<String> withPlaceholder = new ArrayList<>();
            List<String> withoutPlaceholder = new ArrayList<>();
            for (int i = 0; i < urls.length(); i++) {
                String u = urls.optString(i, "");
                if (u.isEmpty()) continue;
                if (u.contains("${channel}")) {
                    withPlaceholder.add(u);
                } else {
                    withoutPlaceholder.add(u);
                }
            }
            List<String> candidates = withPlaceholder.isEmpty() ? withoutPlaceholder : withPlaceholder;
            if (candidates.isEmpty()) continue;
            sortByCdnPriority(candidates);
            String url = candidates.get(0);
            LogUtil.i(TAG + ": using template[" + tkey + "] URL: " + url);
            return url;
        }
        return null;
    }

    private static String cdrmToNd(String cdrmUrl) {
        if (cdrmUrl == null || cdrmUrl.isEmpty()) return null;
        String url = cdrmUrl;
        boolean changed = false;
        if (url.contains("wbcd")) {
            url = url.replace("wbcd", "wbnd");
            changed = true;
        }
        if (url.contains("/cdrm")) {
            url = url.replace("/cdrm", "/");
            changed = true;
        }
        if (url.contains("_1/")) {
            url = url.replace("_1/", "_2/");
            changed = true;
        }
        int qIdx = url.indexOf("?b=");
        if (qIdx > 0) {
            url = url.substring(0, qIdx);
            changed = true;
        }
        if (url.contains("wbnd") && url.contains("bdydns.com")) {
            int dotIdx = url.indexOf(".a.bdydns.com");
            if (dotIdx > 0) {
                String prefix = url.substring(0, dotIdx);
                String suffix = url.substring(dotIdx + ".a.bdydns.com".length());
                url = prefix + "txy.liveplay.myqcloud.com" + suffix;
            }
        }
        if (changed) {
            return url;
        }
        return null;
    }

    private void sortByCdnPriority(List<String> urls) {
        java.util.Collections.sort(urls, new java.util.Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Integer.compare(cdnScore(a), cdnScore(b));
            }
        });
    }

    private int cdnScore(String url) {
        for (int i = 0; i < CDN_PRIORITY.length; i++) {
            if (url.contains(CDN_PRIORITY[i])) return i;
        }
        return CDN_PRIORITY.length;
    }

    private static String aesDecrypt(String base64Data) {
        byte[][] keyIv = getAesKeyIv();
        if (keyIv == null || keyIv[0] == null || keyIv[1] == null) return null;

        try {
            byte[] cipherText = Base64.decode(base64Data, Base64.DEFAULT);
            SecretKeySpec keySpec = new SecretKeySpec(keyIv[0], "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(keyIv[1]);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, "UTF-8");
        } catch (Exception e) {
            LogUtil.w(TAG + ": AES decrypt with cached key failed, retrying brute-force...");

            String js = fetchLiveplayerJs();
            if (js == null) return null;

            List<byte[]> keyCandidates = new ArrayList<>();
            List<byte[]> ivCandidates = new ArrayList<>();

            Matcher km = LIVEPLAYER_KEY_PATTERN.matcher(js);
            while (km.find()) {
                try {
                    byte[] kb = Base64.decode(km.group(1), Base64.DEFAULT);
                    if (kb.length == 32) keyCandidates.add(kb);
                } catch (Exception ignored) {}
            }
            Matcher im = LIVEPLAYER_IV_PATTERN.matcher(js);
            while (im.find()) {
                try {
                    byte[] ib = Base64.decode(im.group(1), Base64.DEFAULT);
                    if (ib.length == 16) ivCandidates.add(ib);
                } catch (Exception ignored) {}
            }

            return aesDecryptDynamic(base64Data, keyCandidates, ivCandidates);
        }
    }

    private static final CctvParser sInstance = new CctvParser();

    public static String getChannelUrlForId(String channelId) {
        Map<String, String> channels = sInstance.getChannelUrls();
        if (channels == null) return null;
        return channels.get(channelId);
    }

    public static String getChannelNameForId(String channelId) {
        if (CHANNEL_NAMES.containsKey(channelId)) {
            return CHANNEL_NAMES.get(channelId);
        }
        return channelId;
    }

    private static synchronized byte[][] getAesKeyIv() {
        long now = System.currentTimeMillis();
        if (sCachedAesKey != null && sCachedAesIv != null
                && (now - sKeyCacheTimestamp) < KEY_CACHE_TTL_MS) {
            return new byte[][]{sCachedAesKey, sCachedAesIv};
        }

        String js = fetchLiveplayerJs();
        if (js == null) {
            LogUtil.e(TAG + ": liveplayer.js download failed, AES key unavailable");
            return null;
        }

        List<byte[]> keyCandidates = new ArrayList<>();
        List<byte[]> ivCandidates = new ArrayList<>();

        Matcher km = LIVEPLAYER_KEY_PATTERN.matcher(js);
        while (km.find()) {
            try {
                byte[] keyBytes = Base64.decode(km.group(1), Base64.DEFAULT);
                if (keyBytes.length == 32) {
                    keyCandidates.add(keyBytes);
                }
            } catch (Exception ignored) {}
        }

        Matcher im = LIVEPLAYER_IV_PATTERN.matcher(js);
        while (im.find()) {
            try {
                byte[] ivBytes = Base64.decode(im.group(1), Base64.DEFAULT);
                if (ivBytes.length == 16) {
                    ivCandidates.add(ivBytes);
                }
            } catch (Exception ignored) {}
        }

        if (keyCandidates.isEmpty() || ivCandidates.isEmpty()) {
            LogUtil.e(TAG + ": no AES key/IV found in liveplayer.js");
            return null;
        }

        sCachedAesKey = keyCandidates.get(0);
        sCachedAesIv = ivCandidates.get(0);
        sKeyCacheTimestamp = now;

        LogUtil.d(TAG + ": extracted AES key from JS ("
                + keyCandidates.size() + " keys, "
                + ivCandidates.size() + " IVs)");

        return new byte[][]{sCachedAesKey, sCachedAesIv};
    }

    private static String fetchLiveplayerJs() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://tv.cctv.com/");
        headers.put("User-Agent", UA_MOBILE);
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        for (String url : LIVEPLAYER_ALT_URLS) {
            try {
                HttpFetcher.Response resp = HttpFetcher.get(url, headers);
                if (resp.code == 200 && resp.bodyAsString() != null) {
                    LogUtil.d(TAG + ": downloaded liveplayer.js from " + url
                            + " (" + resp.bodyAsString().length() + " bytes)");
                    return resp.bodyAsString();
                }
            } catch (Exception e) {
                LogUtil.w(TAG + ": liveplayer.js from " + url + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    private static String aesDecryptDynamic(String base64Data,
            List<byte[]> keyCandidates, List<byte[]> ivCandidates) {
        if (keyCandidates == null || ivCandidates == null) return null;
        for (byte[] key : keyCandidates) {
            for (byte[] iv : ivCandidates) {
                try {
                    byte[] cipherText = Base64.decode(base64Data, Base64.DEFAULT);
                    SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
                    IvParameterSpec ivSpec = new IvParameterSpec(iv);
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
                    byte[] plainText = cipher.doFinal(cipherText);
                    String result = new String(plainText, "UTF-8");
                    if (result.startsWith("{") || result.startsWith("[")) {
                        sCachedAesKey = key;
                        sCachedAesIv = iv;
                        sKeyCacheTimestamp = System.currentTimeMillis();
                        LogUtil.d(TAG + ": AES decrypt success (key found by brute-force)");
                        return result;
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }
}