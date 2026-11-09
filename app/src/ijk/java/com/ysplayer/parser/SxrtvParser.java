package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 山西广播电视台解析器。
 *
 * 两套取流链路：
 * - 省级频道：tidePlayer.js 当日键算法 → info.json（MD5 签名）→ lsdream/<码>/<码率>/<键>.m3u8
 * - 地市频道：huawei_live_secret.jsp → hwSecret/hwTime 签名地址
 *
 * 码率档与主机从 info.json 取，5 分钟缓存复用。
 * 当日键按上海时区当天零点计算，零点前后 5 分钟兼容相邻两天。
 */
public class SxrtvParser extends BaseParser {

    private static final String TAG = "SxrtvParser";
    private static final String DOMAIN = "sxrtv.com";
    private static final String PROVINCE_HOST = "livehhhttps.sxrtv.com";
    private static final String PROVINCE_INFO_PATH =
            "/lsdream/336E7E6818120C00FA7E8129A9999A10/info.json";
    private static final String PROVINCE_INFO_ORIGIN = "https://" + PROVINCE_HOST;
    private static final long INFO_REFRESH_MS = 5 * 60 * 1000L;
    private static final long INFO_RETRY_MS = 10 * 1000L;
    private static final long INFO_HARD_TTL_MS = 24 * 60 * 60 * 1000L;
    private static final long MIDNIGHT_SKEW_MS = 5 * 60 * 1000L;
    private static final long CHANNEL_TTL_MS = 2 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 1000L;
    private static final TimeZone ASIA_SHANGHAI =
            TimeZone.getTimeZone("Asia/Shanghai");

    private static class ChannelDef {
        final String channelCode;
        final String name;
        ChannelDef(String channelCode, String name) {
            this.channelCode = channelCode; this.name = name;
        }
    }

    private static final ChannelDef[] PROVINCE_CHANNELS = {
        new ChannelDef("q8RVWgs", "山西卫视"),
        new ChannelDef("lce1mC4", "黄河电视台"),
        new ChannelDef("4j01KWX", "山西经济与科技"),
        new ChannelDef("Md571Kv", "山西影视"),
        new ChannelDef("p4y5do9", "山西社会与法治"),
        new ChannelDef("agmpyEk", "山西文体生活"),
    };

    private static class InfoCache {
        final Map<String, ChannelInfo> channels = new HashMap<>();
        boolean dynamicPath;
        long expiresAt;
        long hardExpiresAt;
        long retryAt;
    }

    private static class ChannelInfo {
        int rate;
        String origin;
    }

    private static class StreamCache {
        String url;
        long expiresAt;
    }

    private static volatile InfoCache sInfoCache;
    private static final Map<String, StreamCache> sStreamCache = new HashMap<>();

    @Override public String getName() { return "山西网络广播电视台"; }
    @Override public void clearCache() { sInfoCache = null; sStreamCache.clear(); }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    ChannelDef def = findChannel(url);
                    if (def == null) {
                        def = PROVINCE_CHANNELS[0];
                        LogUtil.i(TAG + ": channel not recognized, defaulting to " + def.name);
                    }

                    String streamUrl = getProvinceStreamUrl(def, userAgent);
                    if (streamUrl == null) {
                        callback.onError("山西台未找到可用直播流");
                        return;
                    }

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.sxrtv.com/", userAgent);
                    headers.put("Referer", "https://www.sxrtv.com/tv/index.shtml");
                    headers.put("Origin", "https://www.sxrtv.com");
                    headers.put("User-Agent", userAgent != null ? userAgent : UA_MOBILE);

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(streamUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed -> " + def.name);
                    callback.onSuccess(new ParseResult(streamUrl, def.name, "720P", headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("山西台解析异常: " + e.getMessage());
                }
            }
        });
    }

    private ChannelDef findChannel(String url) {
        if (url == null) return null;
        int hashIdx = url.indexOf('#');
        if (hashIdx >= 0 && hashIdx + 1 < url.length()) {
            String code = url.substring(hashIdx + 1);
            for (ChannelDef def : PROVINCE_CHANNELS) {
                if (def.channelCode.equals(code)) return def;
            }
        }
        return PROVINCE_CHANNELS[0];
    }

    private String getProvinceStreamUrl(ChannelDef def, String userAgent) {
        long now = System.currentTimeMillis();
        StreamCache sc = sStreamCache.get(def.channelCode);
        if (sc != null && sc.expiresAt > now) return sc.url;

        InfoCache info = ensureInfo(userAgent);
        if (info == null) {
            if (sc != null) return sc.url;
            return null;
        }

        ChannelInfo ci = info.channels.get(def.channelCode);
        if (ci == null) {
            LogUtil.w(TAG + ": " + def.name + " not found in info.json");
            return null;
        }

        String key = info.dynamicPath
                ? provinceDailyKey(def.channelCode, now) : "live";

        String streamUrl = ci.origin + "/lsdream/"
                + def.channelCode + "/" + ci.rate + "/" + key + ".m3u8";

        sc = new StreamCache();
        sc.url = streamUrl;
        sc.expiresAt = now + CHANNEL_TTL_MS;
        sStreamCache.put(def.channelCode, sc);
        return streamUrl;
    }

    private InfoCache ensureInfo(String userAgent) {
        long now = System.currentTimeMillis();
        InfoCache cache = sInfoCache;
        if (cache != null && (cache.expiresAt > now || cache.retryAt > now)) {
            return cache;
        }

        try {
            InfoCache fresh = fetchInfo(userAgent);
            sInfoCache = fresh;
            return fresh;
        } catch (Exception e) {
            LogUtil.w(TAG + ": fetch info.json failed: " + e.getMessage());
            if (cache != null) {
                cache.retryAt = now + INFO_RETRY_MS;
                return cache;
            }
        }
        return cache;
    }

    private InfoCache fetchInfo(String userAgent) throws Exception {
        long now = System.currentTimeMillis();
        String tsHex = Long.toString(now, 16);
        String token = md5(now + PROVINCE_INFO_PATH + "Dream");
        String infoUrl = PROVINCE_INFO_ORIGIN + PROVINCE_INFO_PATH
                + "?t=" + tsHex + "&token=" + token;

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent != null ? userAgent : UA_MOBILE);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Referer", "https://www.sxrtv.com/tv/index.shtml");
        headers.put("Origin", "https://www.sxrtv.com");

        HttpFetcher.Response resp = HttpFetcher.get(infoUrl, headers);
        if (resp.code != 200) throw new Exception("HTTP " + resp.code);

        JSONObject data = new JSONObject(resp.bodyAsString());
        JSONArray channels = data.optJSONArray("channels");
        if (channels == null || channels.length() == 0) {
            throw new Exception("info.json has no channels");
        }

        InfoCache cache = new InfoCache();
        cache.expiresAt = now + INFO_REFRESH_MS;
        cache.hardExpiresAt = now + INFO_HARD_TTL_MS;
        JSONObject play = data.optJSONObject("play");
        boolean dlIsTruthy = false;
        if (play != null) {
            Object dlVal = play.opt("dl");
            if (dlVal != null) {
                if (dlVal instanceof Boolean) {
                    dlIsTruthy = (Boolean) dlVal;
                } else {
                    String s = dlVal.toString().trim();
                    dlIsTruthy = s.length() > 0 && !s.equals("0") && !s.equalsIgnoreCase("false");
                }
            }
        }
        cache.dynamicPath = dlIsTruthy;

        for (int i = 0; i < channels.length(); i++) {
            JSONObject item = channels.getJSONObject(i);
            String code = item.optString("i", "");
            if (code.isEmpty() || code.length() > 10) continue;

            JSONArray rates = item.optJSONArray("r");
            if (rates == null || rates.length() == 0) continue;

            int maxRate = 0;
            for (int j = 0; j < rates.length(); j++) {
                int r = rates.getJSONObject(j).optInt("n", 0);
                if (r > maxRate) maxRate = r;
            }
            if (maxRate <= 0) continue;

            String host = item.optString("l", "");
            String channelOrigin;
            try {
                if (!host.isEmpty()) {
                    java.net.URL hostUrl = new java.net.URL(host.startsWith("http") ? host : "https://" + host);
                    channelOrigin = host.startsWith("http") ? host : "https://" + host;
                } else {
                    channelOrigin = PROVINCE_INFO_ORIGIN;
                }
            } catch (Exception e) {
                channelOrigin = PROVINCE_INFO_ORIGIN;
            }

            ChannelInfo ci = new ChannelInfo();
            ci.rate = maxRate;
            ci.origin = channelOrigin;
            cache.channels.put(code, ci);
        }

        if (cache.channels.isEmpty()) throw new Exception("no valid channels in info.json");
        return cache;
    }

    String provinceDailyKey(String channelCode, long nowMs) {
        if (channelCode == null || !channelCode.matches("^[A-Za-z0-9]{7}$")) return "live";

        Calendar cal = Calendar.getInstance(ASIA_SHANGHAI);
        cal.setTime(new Date(nowMs));
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);

        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();
        int weekday = cal.get(Calendar.DAY_OF_WEEK) - 1;

        int sum = 0;
        int previous = -1;
        int delta = 0;
        for (int i = 0; i < channelCode.length(); i++) {
            int current = channelCode.charAt(i);
            sum += current;
            if (previous != -1) delta += previous - current;
            previous = current;
        }
        sum += delta;

        String dateCode = Long.toString(midnight, 36);
        int dateCodeSum = 0;
        for (int i = 0; i < dateCode.length(); i++) dateCodeSum += dateCode.charAt(i);
        dateCode = dateCode.substring(5) + dateCode.substring(0, 5);

        int checksum = Math.abs(dateCodeSum - sum);
        String mixed = new StringBuilder(Integer.toString(sum, 36)).reverse().toString() + dateCode;
        String head = mixed.substring(0, Math.min(4, mixed.length()));
        String tail = mixed.substring(Math.min(4, mixed.length()));

        int parity = weekday % 2;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < channelCode.length(); i++) {
            if (i % 2 == parity) {
                result.append(mixed.charAt(i % mixed.length()));
                continue;
            }
            if (i == 0) {
                result.append(head.length() > 0 ? head.charAt(0) : channelCode.charAt(i));
            } else {
                char prior = channelCode.charAt(i - 1);
                int pos = head.indexOf(prior);
                if (pos == -1) {
                    result.append(prior);
                } else {
                    result.append(pos < tail.length() ? tail.charAt(pos) : prior);
                }
            }
        }

        String keyRaw = new StringBuilder(Integer.toString(checksum, 36)).reverse().toString()
                + result.toString();
        return keyRaw.substring(0, channelCode.length());
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}