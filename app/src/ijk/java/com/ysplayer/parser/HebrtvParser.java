package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HebrtvParser extends BaseParser {

    private static final String TAG = "HebrtvParser";
    private static final String DOMAIN = "hebrts.cn";

    private static final String CHANNEL_LIST_URL =
            "https://api.cmc.hebrts.cn/cmsback/api/com/article/getArticleList"
            + "?catalogId=32557&siteId=1";
    private static final String STREAM_HOST = "tv.pull.hebtv.com";
    private static final long STREAM_TTL_SECONDS = 2 * 60 * 60;
    private static final long CHANNEL_TTL_MS = 4 * 60 * 60 * 1000;

    private static final String[] TITLE_MAP = {
            "河北卫视", "经济生活", "河北都市",
            "文旅体育", "少儿科教", "三农频道"
    };

    private static final String[] NAME_OVERRIDES = {
            "河北卫视", "河北经济生活", "河北都市",
            "河北文旅体育", "河北少儿科教", "河北三农"
    };

    private static volatile List<ChannelInfo> sChannelList;
    private static volatile long sChannelExpiresAt;
    private static final Object sChannelLock = new Object();

    private static final Pattern INDEX_PAT =
            Pattern.compile("[?&]index=(\\d+)");

    private static class ChannelInfo {
        final String id;
        final String name;
        final String liveUri;
        final String liveKey;
        final String url;

        ChannelInfo(String id, String name, String liveUri,
                     String liveKey, String url) {
            this.id = id;
            this.name = name;
            this.liveUri = liveUri;
            this.liveKey = liveKey;
            this.url = url;
        }
    }

    @Override
    public String getName() {
        return "河北网络广播电视台";
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
                    int index = extractIndex(url);
                    if (index < 0 || index >= TITLE_MAP.length) {
                        callback.onError("河北台URL缺少频道索引");
                        return;
                    }

                    List<ChannelInfo> channels = getChannelList();
                    if (channels == null || channels.isEmpty()) {
                        callback.onError("河北台获取频道列表失败");
                        return;
                    }

                    String cmsTitle = TITLE_MAP[index];
                    ChannelInfo ch = null;
                    for (ChannelInfo ci : channels) {
                        if (cmsTitle.equals(ci.name)) {
                            ch = ci;
                            break;
                        }
                    }
                    if (ch == null) {
                        callback.onError("河北台频道不在列表中: " + cmsTitle);
                        return;
                    }

                    String signedUrl = signStreamUrl(ch);
                    String displayName = NAME_OVERRIDES[index];

                    Map<String, String> playHeaders = new HashMap<>();
                    playHeaders.put("Referer", "https://www.hebrts.cn/");
                    playHeaders.put("Origin", "https://www.hebrts.cn");

                    LogUtil.i(TAG + ": " + displayName + " -> " + signedUrl);
                    callback.onSuccess(new ParseResult(signedUrl, displayName,
                            "默认", playHeaders, null));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("河北台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static int extractIndex(String url) {
        Matcher m = INDEX_PAT.matcher(url);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static List<ChannelInfo> getChannelList() {
        long now = System.currentTimeMillis();
        List<ChannelInfo> hit = sChannelList;
        if (hit != null && now < sChannelExpiresAt) {
            return hit;
        }
        synchronized (sChannelLock) {
            hit = sChannelList;
            if (hit != null && now < sChannelExpiresAt) {
                return hit;
            }
            try {
                sChannelList = fetchChannelList();
                sChannelExpiresAt = now + CHANNEL_TTL_MS;
                LogUtil.i(TAG + ": 频道列表更新, " + sChannelList.size() + "个频道");
            } catch (Exception e) {
                LogUtil.e(TAG + ": 频道列表拉取失败", e);
                if (hit != null) {
                    sChannelExpiresAt = now + 60 * 1000;
                }
            }
            return sChannelList;
        }
    }

    private static List<ChannelInfo> fetchChannelList() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                + "AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
        headers.put("Referer", "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml");
        headers.put("Origin", "https://www.hebrts.cn");
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/json;charset=UTF-8");

        HttpFetcher.Response resp = HttpFetcher.post(CHANNEL_LIST_URL,
                headers, "{}".getBytes("UTF-8"));
        if (resp.code != 200) {
            throw new Exception("频道接口 HTTP " + resp.code);
        }

        JSONObject json = new JSONObject(resp.bodyAsString());
        String returnCode = json.optString("returnCode", "");
        if (!"0000".equals(returnCode)) {
            throw new Exception("频道接口返回: " + json.optString("returnDesc", returnCode));
        }

        JSONObject returnData = json.optJSONObject("returnData");
        if (returnData == null) {
            throw new Exception("频道接口无returnData");
        }

        JSONArray news = returnData.optJSONArray("news");
        if (news == null || news.length() == 0) {
            throw new Exception("频道列表为空");
        }

        Map<String, String> titleMap = new HashMap<>();
        for (String t : TITLE_MAP) {
            titleMap.put(t, t);
        }

        List<ChannelInfo> channels = new ArrayList<>();
        for (int i = 0; i < news.length(); i++) {
            JSONObject item = news.optJSONObject(i);
            if (item == null) continue;

            String id = String.valueOf(item.opt("id")).trim();
            String title = item.optString("title", "").trim();
            if (!titleMap.containsKey(title)) continue;

            JSONObject params = item.optJSONObject("appCustomParams");
            if (params == null) {
                params = item.optJSONObject("appCustomParams1");
            }
            if (params == null) continue;

            JSONObject movie = params.optJSONObject("movie");
            if (movie == null) continue;

            String liveUri = movie.optString("liveUri", "").trim();
            String liveKey = movie.optString("liveKey", "").trim();
            if (liveUri.isEmpty() || liveKey.isEmpty()) continue;
            if (!liveUri.matches("^/[A-Za-z0-9][A-Za-z0-9/_.-]*\\.m3u8$")) continue;
            if (!liveKey.matches("^[A-Za-z0-9]{3,64}$")) continue;

            String rawUrl = extractStreamUrl(item);
            if (rawUrl == null || rawUrl.isEmpty()) continue;

            try {
                java.net.URL parsed = new java.net.URL(rawUrl);
                if (!"https".equals(parsed.getProtocol())) continue;
                if (!STREAM_HOST.equals(parsed.getHost())) continue;
                if (!liveUri.equals(parsed.getPath())) continue;
            } catch (Exception e) {
                continue;
            }

            channels.add(new ChannelInfo(id, title, liveUri, liveKey, rawUrl));
            LogUtil.d(TAG + ": 频道 " + title + " id=" + id
                    + " uri=" + liveUri);
        }

        if (channels.isEmpty()) {
            throw new Exception("频道列表中没有可用频道");
        }
        return channels;
    }

    private static String extractStreamUrl(JSONObject item) {
        JSONArray liveVideo = item.optJSONArray("liveVideo");
        if (liveVideo == null) return null;

        for (int i = 0; i < liveVideo.length(); i++) {
            JSONObject device = liveVideo.optJSONObject(i);
            if (device == null) continue;
            JSONArray formats = device.optJSONArray("formats");
            if (formats == null) continue;
            for (int j = 0; j < formats.length(); j++) {
                JSONObject format = formats.optJSONObject(j);
                if (format == null) continue;
                String val = format.optString("url", "");
                if (val.isEmpty()) {
                    val = format.optString("liveStream", "");
                }
                if (!val.isEmpty()) return val.trim();
            }
        }
        return null;
    }

    static String signStreamUrl(ChannelInfo ch) throws Exception {
        long nowSec = System.currentTimeMillis() / 1000L;
        long expires = nowSec + STREAM_TTL_SECONDS;

        String toHash = ch.liveUri + ch.liveKey + expires;
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(toHash.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        String k = sb.toString();

        return "https://" + STREAM_HOST + ch.liveUri
                + "?t=" + expires + "&k=" + k;
    }
}