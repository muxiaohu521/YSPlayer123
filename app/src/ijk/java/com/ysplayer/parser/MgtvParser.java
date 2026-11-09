package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 芒果TV 解析器。
 *
 * 取流流程：
 * 1. 调用 live/source API（MD5 签名：SIGN_SECRET + 按 key 排序的 key+value 串 + SIGN_SECRET，结果大写）
 * 2. 从 sources 中选最高 definition 的 .m3u8 流，同档优先 H.264
 * 3. 校验域名白名单（mgtv.com / hitv.com / imgo.tv）
 * 4. 地址短效，约 10 分钟缓存
 *
 * 签名注意：对参数原始值（未 URL 编码）排序拼接后计算 MD5，与参考实现一致。
 */
public class MgtvParser extends BaseParser {

    private static final String TAG = "MgtvParser";
    private static final String DOMAIN = "mgtv.com";
    private static final String LIVE_SOURCE_URL =
            "https://pwlp.bz.mgtv.com/v1/live/source";
    private static final String SIGN_SECRET = "LMFwh1k1m@pvt#Pt";
    private static final String DEVICE_ID = UUID.randomUUID().toString();
    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;

    private static final Map<String, String> CHANNEL_NAMES = new HashMap<>();
    static {
        CHANNEL_NAMES.put("287", "金鹰卡通");
        CHANNEL_NAMES.put("280", "湖南经视");
        CHANNEL_NAMES.put("344", "湖南娱乐");
        CHANNEL_NAMES.put("221", "湖南电影");
        CHANNEL_NAMES.put("346", "湖南都市");
        CHANNEL_NAMES.put("484", "湖南电视剧");
        CHANNEL_NAMES.put("316", "金鹰纪实");
        CHANNEL_NAMES.put("261", "湖南爱晚");
        CHANNEL_NAMES.put("229", "湖南国际");
        CHANNEL_NAMES.put("218", "快乐垂钓");
        CHANNEL_NAMES.put("269", "长沙新闻综合");
        CHANNEL_NAMES.put("254", "长沙政法");
    }

    private static final Map<String, String> SLUG_TO_ID = new HashMap<>();
    static {
        SLUG_TO_ID.put("287", "287");
        SLUG_TO_ID.put("hunan",     "287");
        SLUG_TO_ID.put("jingji",    "280");
        SLUG_TO_ID.put("yule",      "344");
        SLUG_TO_ID.put("dianying",  "221");
        SLUG_TO_ID.put("dushi",     "346");
        SLUG_TO_ID.put("dianshiju", "484");
        SLUG_TO_ID.put("jinian",    "316");
        SLUG_TO_ID.put("aiwan",     "261");
        SLUG_TO_ID.put("guoji",     "229");
        SLUG_TO_ID.put("chuidiao",  "218");
        SLUG_TO_ID.put("changsha1", "269");
        SLUG_TO_ID.put("changsha2", "254");
    }

    @Override public String getName() { return "芒果TV(湖南)"; }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String channelId = matchChannelId(url);
                    String name = CHANNEL_NAMES.containsKey(channelId)
                            ? CHANNEL_NAMES.get(channelId) : "芒果TV";
                    LogUtil.d(TAG + ": channelId=" + channelId + " name=" + name);

                    String m3u8Url = fetchLiveSource(channelId, userAgent);
                    if (m3u8Url == null) {
                        callback.onError("芒果TV获取播放地址失败");
                        return;
                    }

                    Map<String, String> playHeaders = defaultHeadersWithUA(
                            "https://www.mgtv.com/", userAgent);
                    playHeaders.put("Origin", "https://www.mgtv.com");
                    playHeaders.put("Referer", "https://www.mgtv.com/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(m3u8Url, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed -> " + name);
                    callback.onSuccess(new ParseResult(m3u8Url, name, "720P",
                            playHeaders, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("芒果TV解析异常: " + e.getMessage());
                }
            }
        });
    }

    private String matchChannelId(String url) {
        if (url == null) return "287";
        String lower = url.toLowerCase();
        for (Map.Entry<String, String> e : SLUG_TO_ID.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return "287";
    }

    private String fetchLiveSource(String channelId, String userAgent) {
        try {
            Map<String, String> params = buildRawParams(channelId);
            String sign = signRawParams(params);
            String url = LIVE_SOURCE_URL + "?"
                    + buildQuery(params) + "&_support=10000000&sign=" + sign;

            Map<String, String> headers = defaultHeadersWithUA(
                    "https://www.mgtv.com/", userAgent);
            headers.put("Origin", "https://www.mgtv.com");
            headers.put("Referer", "https://www.mgtv.com/");
            headers.put("Accept", "application/json");

            HttpFetcher.Response resp = HttpFetcher.get(url, headers);
            if (resp.code != 200) {
                LogUtil.e(TAG + ": live/source returned " + resp.code);
                return null;
            }

            JSONObject root = new JSONObject(resp.bodyAsString());
            if (root.optInt("code", -1) != 0) {
                LogUtil.e(TAG + ": live/source error: " + root.optString("msg", ""));
                return null;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            return selectHighestSource(data);

        } catch (Exception e) {
            LogUtil.e(TAG + ": fetch live source failed", e);
            return null;
        }
    }

    private Map<String, String> buildRawParams(String channelId) {
        Map<String, String> p = new HashMap<>();
        p.put("cameraId", channelId);
        p.put("activityId", "");
        p.put("platform", "4");
        p.put("appVersion", "imgotv-pch5-1.2.3");
        p.put("clientKey", "pcweb");
        p.put("auth_mode", "1");
        p.put("local_definition", "");
        p.put("init_definition", "2");
        p.put("did", DEVICE_ID);
        p.put("uid", "");
        p.put("token", "");
        p.put("_t", String.valueOf(System.currentTimeMillis()));
        p.put("deviceId", DEVICE_ID);
        p.put("hdts", "h265,h264");
        p.put("lls", "1");
        p.put("supportFlv", "1");
        return p;
    }

    private String signRawParams(Map<String, String> params) {
        try {
            List<String> keys = new ArrayList<>(params.keySet());
            Collections.sort(keys);
            StringBuilder flat = new StringBuilder();
            for (String k : keys) {
                String v = params.get(k);
                if (v != null) flat.append(k).append(v);
            }
            String toSign = SIGN_SECRET + flat.toString() + SIGN_SECRET;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(toSign.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString().toUpperCase();
        } catch (Exception e) {
            return "";
        }
    }

    private String buildQuery(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append('&');
            String k = keys.get(i);
            String v = params.get(k);
            if (v == null) v = "";
            try {
                sb.append(k).append('=')
                        .append(java.net.URLEncoder.encode(v, "UTF-8"));
            } catch (Exception ex) {
                sb.append(k).append('=').append(v);
            }
        }
        return sb.toString();
    }

    private String selectHighestSource(JSONObject data) {
        JSONArray sources = data.optJSONArray("sources");
        if (sources == null || sources.length() == 0) return null;

        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            String fmt = s.optString("format", "").toLowerCase();
            if (!fmt.equals(".m3u8")) continue;
            String rawUrl = s.optString("url", "");
            if (rawUrl.isEmpty()) continue;
            if (!isOfficialStreamUrl(rawUrl)) continue;
            candidates.add(s);
        }

        if (candidates.isEmpty()) return null;

        Collections.sort(candidates, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                int aDef = a.optInt("definition", 0);
                int bDef = b.optInt("definition", 0);
                if (aDef != bDef) return Integer.compare(bDef, aDef);
                boolean aH264 = a.optString("videoFormat", "")
                        .toLowerCase().matches(".*(h264|avc).*");
                boolean bH264 = b.optString("videoFormat", "")
                        .toLowerCase().matches(".*(h264|avc).*");
                return Boolean.compare(bH264, aH264);
            }
        });

        return candidates.get(0).optString("url", null);
    }

    private boolean isOfficialStreamUrl(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            java.net.URL url = new java.net.URL(raw);
            if (!"https".equals(url.getProtocol())) return false;
            if (!url.getPath().toLowerCase().endsWith(".m3u8")) return false;
            String host = url.getHost();
            return host.equals("mgtv.com") || host.endsWith(".mgtv.com")
                    || host.equals("hitv.com") || host.endsWith(".hitv.com")
                    || host.equals("imgo.tv") || host.endsWith(".imgo.tv");
        } catch (Exception e) {
            return false;
        }
    }
}