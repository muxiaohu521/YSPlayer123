package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CztvParser extends BaseParser {

    private static final String TAG = "CztvParser";
    private static final String DOMAIN = "cztv.com";

    private static final String PLAY_INFO_URL =
            "https://zlive-das.cztv.com/zapp/live/tv/channel/playInfo";
    private static final String AUTH_SALT = "CHWr9VybUeBZE1VB";

    private static final String[] QUALITY_PREFERENCE = {"720P", "1080P"};
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("/liveTV/(\\d+)");

    @Override
    public String getName() {
        return "中国蓝TV(浙江)";
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
                    if (channelId == null) {
                        callback.onError("无法从URL提取浙江频道ID: " + url);
                        return;
                    }
                    LogUtil.d(TAG + ": channelId=" + channelId);

                    PlayInfoResult playInfo = fetchPlayInfo(channelId, userAgent);
                    if (playInfo == null || playInfo.m3u8Url == null) {
                        callback.onError("浙江频道获取播放地址失败: channelId=" + channelId);
                        return;
                    }

                    String channelName = playInfo.channelName != null
                            ? playInfo.channelName : "频道" + channelId;
                    String quality = playInfo.quality != null ? playInfo.quality : "720P";

                    Map<String, String> playHeaders = defaultHeadersWithUA(
                            "https://www.cztv.com/", userAgent);
                    playHeaders.put("Origin", "https://www.cztv.com");
                    playHeaders.put("Referer", "https://www.cztv.com/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(playInfo.m3u8Url, quality, 1280, 720));

                    LogUtil.i(TAG + ": " + url + " -> " + channelName + " (" + quality + ")");

                    callback.onSuccess(new ParseResult(playInfo.m3u8Url, channelName, quality,
                            playHeaders, options));

                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("浙江蓝TV解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static String extractChannelId(String url) {
        if (url == null) return null;
        Matcher m = CHANNEL_ID_PATTERN.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static class PlayInfoResult {
        String m3u8Url;
        String channelName;
        String quality;
    }

    private PlayInfoResult fetchPlayInfo(String channelId, String userAgent) {
        try {
            String url = PLAY_INFO_URL + "?channelId=" + channelId + "&platform=WEB";
            Map<String, String> headers = defaultHeadersWithUA(
                    "https://www.cztv.com/", userAgent);
            headers.put("Accept", "application/json");

            HttpFetcher.Response resp = HttpFetcher.get(url, headers);
            if (resp.code != 200) {
                LogUtil.e(TAG + ": playInfo returned " + resp.code);
                return null;
            }

            JSONObject root = new JSONObject(resp.bodyAsString());
            if (root.optInt("code", -1) != 200) {
                LogUtil.e(TAG + ": playInfo error: " + root.optString("msg", "unknown"));
                return null;
            }

            JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            JSONArray streams = data.optJSONArray("multiBitrateStreamList");
            if (streams == null || streams.length() == 0) return null;

            List<JSONObject> candidates = new ArrayList<>();
            for (int i = 0; i < streams.length(); i++) {
                JSONObject stream = streams.optJSONObject(i);
                if (stream == null) continue;
                String code = stream.optString("bitrateCode", "");
                if ("AUDIO".equalsIgnoreCase(code)) continue;
                JSONArray urlList = stream.optJSONArray("urlList");
                if (urlList == null || urlList.length() == 0) continue;
                candidates.add(stream);
            }

            if (candidates.isEmpty()) return null;

            JSONObject selected = selectByQuality(candidates);
            JSONArray urlList = selected.optJSONArray("urlList");
            if (urlList == null || urlList.length() == 0) return null;

            String rawUrl = urlList.optString(0, "");
            if (rawUrl.isEmpty()) return null;

            PlayInfoResult result = new PlayInfoResult();
            result.channelName = data.optString("channelName", "");
            result.quality = selected.optString("bitrateCode", "720P");
            result.m3u8Url = signStreamUrl(rawUrl);
            return result;

        } catch (Exception e) {
            LogUtil.e(TAG + ": fetch playInfo failed", e);
            return null;
        }
    }

    private static JSONObject selectByQuality(List<JSONObject> candidates) {
        for (String pref : QUALITY_PREFERENCE) {
            for (JSONObject c : candidates) {
                if (pref.equalsIgnoreCase(c.optString("bitrateCode", ""))) {
                    return c;
                }
            }
        }
        return candidates.get(0);
    }

    private static String signStreamUrl(String rawUrl) {
        try {
            java.net.URL parsed = new java.net.URL(rawUrl);
            String pathname = parsed.getPath();
            long timestamp = System.currentTimeMillis();

            String signStr = pathname + "-" + timestamp + "-0-0-" + AUTH_SALT;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(signStr.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }

            String separator = rawUrl.contains("?") ? "&" : "?";
            return rawUrl + separator + "auth_key=" + timestamp + "-0-0-" + hex.toString();
        } catch (Exception e) {
            LogUtil.w(TAG + ": sign stream URL failed, using raw: " + e.getMessage());
            return rawUrl;
        }
    }
}