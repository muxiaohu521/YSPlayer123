package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GxtvParser extends BaseParser {

    private static final String TAG = "GxtvParser";
    private static final String DOMAIN = "gxtv.cn";

    private static final String API_URL =
            "https://api2019.gxtv.cn/memberApi/channel/channelList";

    /** UUID from channel URL → rawName in API response */
    private static final Map<String, String> UUID_TO_RAWNAME = new LinkedHashMap<>();

    static {
        UUID_TO_RAWNAME.put("e7a7ab7df9fe11e88bcfe41f13b60c62", "广西卫视");
        UUID_TO_RAWNAME.put("f3335975f9fe11e88bcfe41f13b60c62", "综艺旅游频道");
        UUID_TO_RAWNAME.put("fdbaf085f9fe11e88bcfe41f13b60c62", "都市频道");
        UUID_TO_RAWNAME.put("5e923d82058e11e9ba67e41f13b60c62", "影视频道");
        UUID_TO_RAWNAME.put("9dfd8600075811e9ba67e41f13b60c62", "新闻频道");
        UUID_TO_RAWNAME.put("bfa17b64157f11e999f0e41f13b60c62", "国际频道");
        UUID_TO_RAWNAME.put("78dbfd44e6b74ab687204d2d8113cbf5", "移动数字电视频道");
        UUID_TO_RAWNAME.put("ffa6b6e1b32b4a16a73eb3ef66f8bfc7", "中国教育电视台CETV-1频道");
        UUID_TO_RAWNAME.put("80d0ffb42c114eaf9663708629ff0a3e", "中国教育电视台CETV-2频道");
        UUID_TO_RAWNAME.put("67eace939278435bb4bca90800fb4225", "中国教育电视台CETV-4频道");
    }

    private static LocalHttpProxy sProxy;

    @Override
    public String getName() {
        return "广西网络广播电视台";
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
                    Map<String, String> pageHeaders = defaultHeadersWithUA(
                            "https://tv.gxtv.cn/", userAgent);
                    String logTag = TAG + ":" + url;

                    // ---------- 1. 从 URL 提取频道 UUID ----------
                    String channelUuid = extractUuid(url);
                    String expectedRawName = channelUuid != null
                            ? UUID_TO_RAWNAME.get(channelUuid) : null;
                    LogUtil.i(logTag + " uuid=" + channelUuid
                            + " -> rawName=" + expectedRawName);

                    // ---------- 2. POST API 获取频道列表 ----------
                    Map<String, String> apiHeaders = new HashMap<>();
                    apiHeaders.put("Content-Type",
                            "application/x-www-form-urlencoded");
                    apiHeaders.put("Referer", "https://tv.gxtv.cn/");
                    apiHeaders.put("User-Agent", resolveUa(userAgent));
                    apiHeaders.put("Accept", "application/json");

                    HttpFetcher.Response apiResp = HttpFetcher.post(API_URL,
                            apiHeaders,
                            "pageNo=1&pageSize=1000&liveMethod=0".getBytes("UTF-8"));
                    if (apiResp.code != 200) {
                        callback.onError("广西台接口返回状态码: " + apiResp.code);
                        return;
                    }

                    JSONObject apiJson = new JSONObject(apiResp.bodyAsString());
                    if (apiJson.optInt("code", -1) != 0) {
                        callback.onError("广西台接口错误: "
                                + apiJson.optString("message", "未知"));
                        return;
                    }

                    JSONArray rows = apiJson.getJSONObject("data")
                            .getJSONArray("rows");

                    // ---------- 3. 匹配频道，提取流地址 + 密钥 ----------
                    String m3u8Url = null;
                    String encodingId = "";
                    String encodingKey = "";
                    String channelName = "广西";

                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.getJSONObject(i);
                        if (row.optInt("state", 0) != 1) continue;
                        if (row.optInt("showChannel", 0) != 1) continue;

                        String rname = row.optString("name", "").trim();
                        // 按 rawName 精确匹配；若 URL 中没匹配到 uuid 则按 API 的 id 字段匹配
                        boolean match = false;
                        if (expectedRawName != null) {
                            match = expectedRawName.equals(rname);
                        } else {
                            String rowId = row.optString("id", "");
                            match = !rowId.isEmpty() && url.contains(rowId);
                        }

                        if (!match) continue;

                        channelName = rname;
                        m3u8Url = streamUrlOf(row);
                        encodingId = row.optString("encodingId", "").trim();
                        encodingKey = row.optString("encodingKey", "").trim();
                        break;
                    }

                    // 如果按 UUID 名字没匹配到，尝试反向映射（API 可能已改名）
                    if (m3u8Url == null && expectedRawName == null) {
                        String guessName = guessChannelName(url);
                        if (guessName != null) {
                            for (int i = 0; i < rows.length(); i++) {
                                JSONObject row = rows.getJSONObject(i);
                                if (row.optInt("state", 0) != 1) continue;
                                if (row.optInt("showChannel", 0) != 1) continue;
                                String rname = row.optString("name", "").trim();
                                if (rname.equals(guessName)
                                        || rname.contains(guessName)
                                        || guessName.contains(rname)) {
                                    channelName = rname;
                                    m3u8Url = streamUrlOf(row);
                                    encodingId = row.optString("encodingId", "").trim();
                                    encodingKey = row.optString("encodingKey", "").trim();
                                    break;
                                }
                            }
                        }
                    }

                    if (m3u8Url == null) {
                        callback.onError("广西台接口中未找到频道: "
                                + (expectedRawName != null ? expectedRawName
                                        : channelUuid));
                        return;
                    }

                    LogUtil.i(logTag + " stream=" + m3u8Url
                            + " encId=" + encodingId);

                    // ---------- 4. 校验 m3u8 可访问 ----------
                    String m3u8Base = m3u8Url.substring(0,
                            m3u8Url.lastIndexOf('/') + 1);
                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("Referer", "https://tv.gxtv.cn/");
                    cdnHeaders.put("User-Agent", resolveUa(userAgent));

                    HttpFetcher.Response m3u8Resp = HttpFetcher.get(m3u8Url,
                            cdnHeaders);
                    if (m3u8Resp.code != 200) {
                        callback.onError("广西台 m3u8 不可访问: "
                                + m3u8Resp.code);
                        return;
                    }

                    // ---------- 5. 启动本地解密代理 ----------
                    sProxy = new LocalHttpProxy("hlscdn.liangtv.cn",
                            m3u8Url, m3u8Base, cdnHeaders);

                    if (!encodingId.isEmpty() && !encodingKey.isEmpty()) {
                        final GxtvTsDecrypt decryptor = new GxtvTsDecrypt(
                                encodingId, encodingKey);
                        sProxy.setSegmentTransformer(
                                new LocalHttpProxy.SegmentTransformer() {
                                    @Override
                                    public byte[] transform(byte[] raw) {
                                        decryptor.decrypt(raw);
                                        return raw;
                                    }
                                });
                        LogUtil.i(logTag + " 已启用 TS 解密: "
                                + decryptor);
                    } else {
                        LogUtil.w(logTag + " 无加密密钥，TS 不解密");
                    }

                    int port = sProxy.start();
                    String localUrl = sProxy.getLocalUrl();
                    LogUtil.i(logTag + " proxy on port " + port
                            + " -> " + localUrl);

                    // ---------- 6. 回调 ----------
                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", "https://tv.gxtv.cn/");

                    callback.onSuccess(new ParseResult(localUrl,
                            "广西-" + channelName, "默认", outHeaders, null));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("广西台解析异常: " + e.getMessage());
                }
            }
        });
    }

    // ====== 辅助方法 ======

    private static void stopProxy() {
        if (sProxy != null) {
            sProxy.stop();
            sProxy = null;
        }
    }

    /** 从 URL 提取频道 UUID */
    private String extractUuid(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile(
                "channelivePlay_([0-9a-fA-F]{32})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile(
                "([0-9a-fA-F]{32})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    /** 从 row 中提取第一个合法 m3u8 URL */
    private String streamUrlOf(JSONObject row) {
        String[] fields = { "encodeM3u8", "decodeM3u8",
                "source0M3u8", "channelM3u8", "subjectM3u8" };
        for (String field : fields) {
            String val = row.optString(field, "").trim();
            if (val.isEmpty() || !val.startsWith("http")) continue;
            if (val.contains(".m3u8")) return val;
        }
        return null;
    }

    /** 如果 UUID 匹配失败，按 URL 关键词猜测频道名 */
    private String guessChannelName(String url) {
        if (url == null) return null;
        for (Map.Entry<String, String> e : UUID_TO_RAWNAME.entrySet()) {
            if (url.contains(e.getKey())) return e.getValue();
        }
        return null;
    }
}