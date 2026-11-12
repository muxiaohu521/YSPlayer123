package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;

/**
 * 看看新闻（SMG）解析器 —— 网页版取流逻辑。
 *
 * 流地址 token 绑定取详情时的出口 IP 与 UA，客户端直连极易 403。
 * 因此通过 LocalHttpProxy 全代理 m3u8+ts 请求，并定时刷新签名 URL。
 */
public class KankanewsParser extends BaseParser {

    private static final String TAG = "KankanewsParser";
    private static final String DOMAIN = "kankanews.com";
    private static final String CHANNEL_DETAIL_URL =
            "https://kapi.kankanews.com/content/pc/tv/channel/detail";
    private static final String PROGRAM_LIST_URL =
            "https://kapi.kankanews.com/content/pc/tv/programs";
    private static final String PROGRAM_DETAIL_URL =
            "https://kapi.kankanews.com/content/pc/tv/program/detail";
    // private static final String SIGN_SALT = "28c8edde3d61a0411511d3b1866f0636";
    private static final String APP_VERSION = "2.45.2";
    private static final String API_VERSION = "v1";
    // 签名盐云端抓取：首页 HTML -> app.<version>.js，盐以 +="<32位hex>" 形式出现
    private static final String PAGE_URL = "https://live.kankanews.com/huikan/";
    private static final long SIGN_SALT_CACHE_TTL_MS = 30 * 60 * 1000L;
    private static final Pattern APP_JS_PAT =
            Pattern.compile("(/_knews_nuxt/js/app\\.v[\\d.]+\\.js)");
    private static final Pattern SIGN_SALT_PAT =
            Pattern.compile("\\+=\"([0-9a-f]{32})\"");
    private static final String NONCE_ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final String UUID_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final long DEFAULT_REFRESH_MS = 150 * 1000L;
    private static final long DETAIL_RETRY_MS = 60 * 1000L;
    private static final long EXPIRY_SKEW_MS = 5 * 60 * 1000L;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    // 注意：官网把 UA 的 MD5 写进播放 token，验签 UA 与随后代理分片 UA 必须一致，
    // 因此统一经 resolveUa(userAgent) 取同一 UA，避免混用导致 CDN 403。
    private static final String STREAM_PUBLIC_KEY_PEM =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDP5hzPUW5RFeE2xBT1ERB3hHZI\n" +
            "Votn/qatWhgc1eZof09qKjElFN6Nma461ZAwGpX4aezKP8Adh4WJj4u2O54xCXDt\n" +
            "wzKRqZO2oNZkuNmF2Va8kLgiEQAAcxYc8JgTN+uQQNpsep4n/o1sArTJooZIF17E\n" +
            "tSqSgXDcJ7yDj5rc7wIDAQAB\n" +
            "-----END PUBLIC KEY-----";

    private static class ChannelDef {
        final String id;
        final String name;
        ChannelDef(String id, String name) { this.id = id; this.name = name; }
    }

    private static final ChannelDef[] CHANNELS = {
        new ChannelDef("1", "东方卫视"),
        new ChannelDef("2", "上海新闻综合"),
        new ChannelDef("11", "魔都眼"),
        new ChannelDef("5", "第一财经"),
        new ChannelDef("12", "新纪实"),
        new ChannelDef("10", "五星体育"),
        new ChannelDef("4", "上海都市"),
        new ChannelDef("9", "哈哈炫动"),
    };

    private static class DetailCache {
        String streamUrl;
        long refreshAt;
        long validUntil;
        long retryAt;
    }

    // 官网明确停供（版权屏蔽/无当前节目/无地址）时不能沿用旧 token；
    // 只有临时网络故障才允许复用尚有效的缓存。
    private static class ChannelUnavailableError extends Exception {
        ChannelUnavailableError(String message) { super(message); }
    }

    private static class ProgramAddress {
        final String liveAddress;
        final long programEnd;
        ProgramAddress(String liveAddress, long programEnd) {
            this.liveAddress = liveAddress;
            this.programEnd = programEnd;
        }
    }

    private static final Map<String, DetailCache> sDetailCache = new HashMap<>();
    private static final Map<String, LocalHttpProxy> sProxies = new HashMap<>();
    private static PublicKey sPublicKey;
    private static String sDeviceUuid;

    // 签名盐运行时状态：仅自动抓取成功后更新，测试期无硬编码兜底
    private static volatile String sSignSalt = null;
    private static volatile long sSignSaltCheckedAt = 0L;
    private static volatile FutureTask<String> sSignSaltRefreshTask = null;
    private static final Object sSignSaltLock = new Object();

    @Override public String getName() { return "看看新闻(SMG)"; }
    @Override public void clearCache() {
        for (String id : new ArrayList<>(sProxies.keySet())) {
            stopProxy(id);
        }
        sDetailCache.clear();
        synchronized (sSignSaltLock) {
            sSignSaltCheckedAt = 0;
            sSignSaltRefreshTask = null;
        }
    }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    ensureSignSaltAsync();
                    ChannelDef def = findChannel(url);
                    if (def == null) {
                        def = CHANNELS[0];
                        LogUtil.i(TAG + ": channel not recognized, defaulting to " + def.name);
                    }

                    stopProxy(def.id);

                    String streamUrl = getStreamUrl(def, userAgent, false);
                    if (streamUrl == null) {
                        callback.onError("看看新闻未找到可用直播流");
                        return;
                    }

                    Map<String, String> cdnHeaders = new HashMap<>();
                    cdnHeaders.put("User-Agent", resolveUa(userAgent));
                    cdnHeaders.put("Referer", "https://live.kankanews.com/huikan/");
                    cdnHeaders.put("Origin", "https://live.kankanews.com");
                    cdnHeaders.put("Accept", "application/vnd.apple.mpegurl, */*");

                    // 部分频道（如上海都市）的流地址是 master 变体列表，其内部媒体
                    // 播放列表的分片为相对路径。若不先下钻到真正的媒体播放列表，本地
                    // 代理会把这些相对分片解析到 /seg/ 目录而 404。master 则下钻到
                    // media，本身就是 media 播放列表时原样返回（其余频道不受影响）。
                    String mediaUrl = resolveMediaPlaylist(streamUrl, cdnHeaders);
                    if (mediaUrl == null) {
                        mediaUrl = streamUrl;
                    }

                    java.net.URL parsed = new java.net.URL(mediaUrl);
                    String cdnHost = parsed.getHost();
                    String m3u8Base = mediaUrl.substring(0,
                            mediaUrl.lastIndexOf('/') + 1);

                    final ChannelDef finalDef = def;
                    LocalHttpProxy proxy = new LocalHttpProxy(cdnHost, mediaUrl,
                            m3u8Base, cdnHeaders);
                    proxy.setEntryRefresher(new LocalHttpProxy.EntryRefresher() {
                        @Override
                        public String refreshEntryUrl(String expiredUrl) {
                            LogUtil.w(TAG + ": refreshing SMG stream URL for "
                                    + finalDef.name);
                            String newUrl = getStreamUrl(finalDef, userAgent, true);
                            if (newUrl != null) {
                                String newMedia = resolveMediaPlaylist(newUrl, cdnHeaders);
                                if (newMedia != null) {
                                    newUrl = newMedia;
                                }
                                LogUtil.i(TAG + ": got new SMG URL for "
                                        + finalDef.name);
                            }
                            return newUrl;
                        }
                    });

                    int port = proxy.start();
                    sProxies.put(def.id, proxy);

                    Map<String, String> outHeaders = new HashMap<>();
                    outHeaders.put("Referer", "https://live.kankanews.com/huikan/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(
                            proxy.getLocalUrl(), "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed " + def.name
                            + " via proxy port " + port);
                    callback.onSuccess(new ParseResult(proxy.getLocalUrl(),
                            def.name, "720P", outHeaders, options));

                } catch (IOException e) {
                    LogUtil.e(TAG + ": proxy start failed", e);
                    callback.onError("看看新闻代理启动失败: " + e.getMessage());
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("看看新闻解析异常: " + e.getMessage());
                }
            }
        });
    }

    private static void stopProxy(String channelId) {
        LocalHttpProxy old = sProxies.remove(channelId);
        if (old != null) {
            old.stop();
        }
    }

    private ChannelDef findChannel(String url) {
        if (url == null) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            String query = u.getQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    if (part.startsWith("id=")) {
                        String id = part.substring(3);
                        for (ChannelDef def : CHANNELS) {
                            if (def.id.equals(id)) return def;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return CHANNELS[0];
    }

    /**
     * 下钻到真正的媒体播放列表（不含 #EXT-X-STREAM-INF 的层级）。
     * master 变体列表会逐层跟随变体 URI（相对路径按当前 URL 目录拼接），
     * 本身已是媒体播放列表时原样返回；请求失败或层级过深返回 null。
     */
    private static String resolveMediaPlaylist(String m3u8Url,
                                                Map<String, String> headers) {
        String current = m3u8Url;
        for (int depth = 0; depth < 4; depth++) {
            final HttpFetcher.Response resp;
            try {
                resp = HttpFetcher.get(current, headers);
            } catch (IOException e) {
                LogUtil.w(TAG + ": media playlist resolve failed: " + e.getMessage());
                return null;
            }
            if (resp.code != 200) return null;
            String text = resp.bodyAsString();
            // 媒体播放列表不含 #EXT-X-STREAM-INF，已是可直接播放的层级
            if (!text.contains("#EXT-X-STREAM-INF")) return current;
            String variant = firstNonCommentLine(text);
            if (variant == null) return null;
            if (!variant.startsWith("http://") && !variant.startsWith("https://")) {
                int slash = current.lastIndexOf('/');
                if (slash < 0) return null;
                variant = current.substring(0, slash + 1) + variant;
            }
            current = variant;
        }
        return null;
    }

    private static String firstNonCommentLine(String m3u8) {
        if (m3u8 == null) return null;
        for (String line : m3u8.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) return trimmed;
        }
        return null;
    }

    private static String getStreamUrl(ChannelDef def, String userAgent,
                                        boolean forceRefresh) {
        long now = System.currentTimeMillis();

        if (!forceRefresh) {
            DetailCache cache = sDetailCache.get(def.id);
            if (cache != null && cache.validUntil > now
                    && (cache.refreshAt > now || cache.retryAt > now)) {
                return cache.streamUrl;
            }
        }

        try {
            DetailCache fresh = fetchDetail(def, userAgent, now);
            sDetailCache.put(def.id, fresh);
            return fresh.streamUrl;
        } catch (ChannelUnavailableError e) {
            // 官网明确停供（版权屏蔽/无当前节目/无地址）时不能沿用旧 token，直接失败；
            // 只有临时网络故障才允许复用尚有效的缓存。
            LogUtil.w(TAG + ": " + def.name + " unavailable: " + e.getMessage());
            sDetailCache.remove(def.id);
            return null;
        } catch (Exception e) {
            LogUtil.w(TAG + ": " + def.name + " fetch failed: " + e.getMessage());
            if (!forceRefresh) {
                DetailCache cache = sDetailCache.get(def.id);
                if (cache != null && cache.validUntil > now + EXPIRY_SKEW_MS) {
                    cache.retryAt = now + DETAIL_RETRY_MS;
                    return cache.streamUrl;
                }
            }
        }
        return null;
    }

    private static DetailCache fetchDetail(ChannelDef def, String userAgent,
                                            long now) throws Exception {
        String nonce = randomString(8, NONCE_ALPHABET);
        String uuid = getDeviceUuid();

        Map<String, String> signedHeaders = buildSignedHeaders(
                "channel_id", def.id, nonce, uuid, now);
        signedHeaders.put("User-Agent", resolveUa(userAgent));

        HttpFetcher.Response resp = HttpFetcher.get(
                CHANNEL_DETAIL_URL + "?channel_id=" + def.id, signedHeaders);
        if (resp.code != 200) throw new Exception("API HTTP " + resp.code);

        JSONObject payload = new JSONObject(resp.bodyAsString());
        if (!"1000".equals(payload.optString("code", ""))) {
            throw new Exception("API code: " + payload.optString("code"));
        }

        JSONObject result = payload.optJSONObject("result");
        if (result == null) throw new Exception("no result in API response");

        String liveAddress = null;
        long programEnd = 0L;
        if (result.optInt("is_exist_program", 0) == 1) {
            // 官网有节目单的频道已不再从频道详情发放地址，需查当前节目读
            // program/detail.channel_info.live_address。此处不回退频道详情的空地址。
            ProgramAddress pa = fetchProgramAddress(def, uuid, userAgent, now);
            liveAddress = pa.liveAddress;
            programEnd = pa.programEnd;
        } else {
            liveAddress = result.optString("live_address", "");
        }

        if (liveAddress == null || liveAddress.isEmpty()) {
            throw new ChannelUnavailableError("官网当前未提供直播地址，请稍后重试");
        }

        String decrypted = decryptLiveAddress(liveAddress);
        if (!isValidStreamUrl(decrypted)) throw new Exception("invalid stream URL: " + decrypted);

        long tokenExpiry = streamExpiry(decrypted);
        long apiTtlMs = Math.max(30 * 1000L,
                result.optInt("limit_time", 180) * 1000L - 30 * 1000L);
        // 有效期同时受 token 过期时间与节目结束时间约束，避免跨节目沿用旧流。
        long validUntil = Math.min(tokenExpiry > 0 ? tokenExpiry : now + 30 * 60 * 1000L,
                programEnd > 0 ? programEnd : Long.MAX_VALUE);

        DetailCache cache = new DetailCache();
        cache.streamUrl = decrypted;
        cache.validUntil = validUntil;
        cache.refreshAt = Math.min(now + Math.min(DEFAULT_REFRESH_MS, apiTtlMs), validUntil);
        LogUtil.i(TAG + ": " + def.name + " URL valid until "
                + (cache.validUntil - now) / 1000 + "s");
        return cache;
    }

    private static ProgramAddress fetchProgramAddress(ChannelDef def,
                                                       String uuid, String userAgent,
                                                       long now) throws Exception {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        String date = sdf.format(new java.util.Date(now));

        String nonce = randomString(8, NONCE_ALPHABET);
        Map<String, String> progListHeaders = buildSignedHeadersMulti(
                new String[][]{{"channel_id", def.id}, {"date", date}},
                nonce, uuid, now);
        // 节目单/节目详情验签与随后代理分片必须用同一个 UA：
        // 官网把 UA 的 MD5 写进播放 token，混用移动端 UA 会导致 CDN 403。
        progListHeaders.put("User-Agent", resolveUa(userAgent));

        String programListUrl = PROGRAM_LIST_URL + "?channel_id=" + def.id + "&date=" + date;
        HttpFetcher.Response listResp = HttpFetcher.get(programListUrl, progListHeaders);
        if (listResp.code != 200) throw new Exception("program list HTTP " + listResp.code);

        JSONObject listPayload = new JSONObject(listResp.bodyAsString());
        if (!"1000".equals(listPayload.optString("code", ""))) {
            throw new Exception("program list code: " + listPayload.optString("code"));
        }

        JSONArray programs = listPayload.optJSONObject("result").optJSONArray("programs");
        if (programs == null || programs.length() == 0) {
            throw new ChannelUnavailableError("官网节目单中没有当前正在播出的节目");
        }

        // 与官网一致：直接取第一个落在当前时间窗口内的节目，再单独判断版权屏蔽。
        JSONObject currentProgram = null;
        for (int i = 0; i < programs.length(); i++) {
            JSONObject prog = programs.getJSONObject(i);
            long startMs = prog.optLong("start_time", 0) * 1000L;
            long endMs = prog.optLong("end_time", 0) * 1000L;
            if (startMs <= now && now < endMs) {
                currentProgram = prog;
                break;
            }
        }
        if (currentProgram == null) {
            throw new ChannelUnavailableError("官网节目单中没有当前正在播出的节目");
        }

        long programEnd = currentProgram.optLong("end_time", 0) * 1000L;
        if (currentProgram.optInt("is_shield", 0) == 1) {
            throw new ChannelUnavailableError("当前节目「"
                    + currentProgram.optString("name", "未命名")
                    + "」因版权限制，官网暂停网络直播");
        }

        String programId = currentProgram.optString("id", "");
        if (programId.isEmpty()) throw new Exception("program id is empty");

        String detailUrl = PROGRAM_DETAIL_URL + "?channel_program_id=" + programId;
        Map<String, String> detailHeaders = buildSignedHeaders(
                "channel_program_id", programId, randomString(8, NONCE_ALPHABET), uuid, now);
        detailHeaders.put("User-Agent", resolveUa(userAgent));

        HttpFetcher.Response detailResp = HttpFetcher.get(detailUrl, detailHeaders);
        if (detailResp.code != 200) throw new Exception("program detail HTTP " + detailResp.code);

        JSONObject detailPayload = new JSONObject(detailResp.bodyAsString());
        if (!"1000".equals(detailPayload.optString("code", ""))) {
            throw new Exception("program detail code: " + detailPayload.optString("code"));
        }

        JSONObject detailResult = detailPayload.optJSONObject("result");
        if (detailResult == null) throw new Exception("no result in program detail");

        JSONObject channelInfo = detailResult.optJSONObject("channel_info");
        if (channelInfo == null) throw new Exception("no channel_info in program detail");

        if (!def.id.equals(channelInfo.optString("id", ""))
                || !def.id.equals(detailResult.optString("channel_id", ""))) {
            throw new Exception("program detail channel id mismatch");
        }

        if (detailResult.optInt("is_shield", 0) == 1) {
            throw new ChannelUnavailableError("当前节目「"
                    + currentProgram.optString("name", "未命名")
                    + "」因版权限制，官网暂停网络直播");
        }

        return new ProgramAddress(channelInfo.optString("live_address", ""), programEnd);
    }

    // ======================== 签名盐自动抓取 ========================

    /**
     * 拉取纯文本资源。HttpFetcher 已支持 gzip 解压与最多 5 次重定向。
     */
    private static String httpGetText(String url) {
        try {
            HttpFetcher.Response resp = HttpFetcher.get(url, null);
            if (resp.code == 200) return resp.bodyAsString();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 两步自动抓取签名盐：首页 HTML -> app.<version>.js，盐以 +="<32位hex>" 形式出现。
     */
    private static String fetchSignSalt() {
        try {
            String html = httpGetText(PAGE_URL);
            if (html == null) return null;

            Matcher jsMatcher = APP_JS_PAT.matcher(html);
            if (!jsMatcher.find()) return null;
            String appJsPath = jsMatcher.group(1);

            String appJs = httpGetText("https://live.kankanews.com" + appJsPath);
            if (appJs == null) return null;

            Matcher saltMatcher = SIGN_SALT_PAT.matcher(appJs);
            if (saltMatcher.find()) return saltMatcher.group(1);
            return null;
        } catch (Exception e) {
            LogUtil.e(TAG + ": fetch sign salt failed", e);
            return null;
        }
    }

    /**
     * 启动/复用后台刷新，返回可等待的 FutureTask（盐仍新鲜时返回 null，直接使用 sSignSalt）。
     */
    private static FutureTask<String> ensureSignSaltAsync() {
        long now = System.currentTimeMillis();
        synchronized (sSignSaltLock) {
            if (sSignSaltCheckedAt != 0 && now - sSignSaltCheckedAt < SIGN_SALT_CACHE_TTL_MS) {
                return null;
            }
            if (sSignSaltRefreshTask != null && !sSignSaltRefreshTask.isDone()) {
                return sSignSaltRefreshTask;
            }
            FutureTask<String> task = new FutureTask<>(new Callable<String>() {
                @Override
                public String call() {
                    String fetched = fetchSignSalt();
                    if (fetched != null) {
                        sSignSalt = fetched;
                        LogUtil.i(TAG + ": sign salt refreshed from cloud");
                    } else {
                        LogUtil.w(TAG + ": sign salt fetch failed, keep current value");
                    }
                    sSignSaltCheckedAt = System.currentTimeMillis();
                    return sSignSalt;
                }
            });
            sSignSaltRefreshTask = task;
            new Thread(task, "kankanews-salt-refresh").start();
            return task;
        }
    }

    /**
     * 等待盐就绪（含冷却期与并发去重）。解析运行在线程池，允许短暂阻塞。
     */
    private static String getSignSalt() {
        FutureTask<String> task = ensureSignSaltAsync();
        if (task != null) {
            try {
                return task.get();
            } catch (Exception ignored) {
            }
        }
        return sSignSalt;
    }

    private static Map<String, String> buildSignedHeaders(String key, String value,
                                                            String nonce, String uuid,
                                                            long nowMs) {
        Map<String, String> all = new HashMap<>();
        all.put("platform", "pc");
        all.put("version", APP_VERSION);
        all.put("nonce", nonce);
        all.put("timestamp", String.valueOf(nowMs / 1000));
        all.put("Api-Version", API_VERSION);
        all.put(key, value);

        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder();
        for (String k : keys) {
            canonical.append(k).append("=").append(all.get(k)).append("&");
        }
        String salt = getSignSalt();
        canonical.append(salt == null ? "" : salt);
        String sign = md5(md5(canonical.toString()));

        Map<String, String> headers = new HashMap<>();
        headers.put("nonce", nonce);
        headers.put("timestamp", all.get("timestamp"));
        headers.put("sign", sign);
        headers.put("m-uuid", uuid);
        headers.put("Api-Version", API_VERSION);
        headers.put("platform", "pc");
        headers.put("version", APP_VERSION);
        headers.put("Accept", "application/json");
        headers.put("Origin", "https://live.kankanews.com");
        headers.put("Referer", "https://live.kankanews.com/huikan/");
        return headers;
    }

    private static Map<String, String> buildSignedHeadersMulti(
            String[][] extraParams, String nonce, String uuid, long nowMs) {
        Map<String, String> all = new HashMap<>();
        all.put("platform", "pc");
        all.put("version", APP_VERSION);
        all.put("nonce", nonce);
        all.put("timestamp", String.valueOf(nowMs / 1000));
        all.put("Api-Version", API_VERSION);
        for (String[] p : extraParams) {
            all.put(p[0], p[1]);
        }

        List<String> keys = new ArrayList<>(all.keySet());
        Collections.sort(keys);
        StringBuilder canonical = new StringBuilder();
        for (String k : keys) {
            canonical.append(k).append("=").append(all.get(k)).append("&");
        }
        String salt = getSignSalt();
        canonical.append(salt == null ? "" : salt);
        String sign = md5(md5(canonical.toString()));

        Map<String, String> headers = new HashMap<>();
        headers.put("nonce", nonce);
        headers.put("timestamp", all.get("timestamp"));
        headers.put("sign", sign);
        headers.put("m-uuid", uuid);
        headers.put("Api-Version", API_VERSION);
        headers.put("platform", "pc");
        headers.put("version", APP_VERSION);
        headers.put("Accept", "application/json");
        headers.put("Origin", "https://live.kankanews.com");
        headers.put("Referer", "https://live.kankanews.com/huikan/");
        return headers;
    }

    private static long streamExpiry(String rawUrl) {
        try {
            java.net.URL url = new java.net.URL(rawUrl);
            String token = getQueryParam(url, "token");
            if (token == null || token.isEmpty()) return 0;
            String[] parts = token.split("\\.");
            if (parts.length < 2) return 0;
            String payload = new String(
                    android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE),
                    "UTF-8");
            JSONObject jwt = new JSONObject(payload);
            long exp = jwt.optLong("exp", 0);
            return exp > 0 ? exp * 1000L : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String getQueryParam(java.net.URL url, String name) {
        String query = url.getQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq >= 0 && part.substring(0, eq).equals(name)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }

    private static String decryptLiveAddress(String encrypted) throws Exception {
        if (encrypted == null || encrypted.isEmpty()
                || !encrypted.matches("^[A-Za-z0-9+/]+={0,2}$")) {
            throw new Exception("invalid encrypted play_url format");
        }

        PublicKey key = getPublicKey();
        int blockSize = 128;

        byte[] data = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
        if (data.length == 0 || data.length % blockSize != 0) {
            throw new Exception("encrypted data block alignment error");
        }

        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int offset = 0; offset < data.length; offset += blockSize) {
            byte[] block = cipher.doFinal(data, offset,
                    Math.min(blockSize, data.length - offset));
            byte[] unpadded = unpadSignedBlock(block);
            out.write(unpadded);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static byte[] unpadSignedBlock(byte[] block) {
        if (block.length < 12 || block[0] != 0 || block[1] != 1) {
            throw new IllegalStateException("RSA signed block header invalid");
        }
        int separator = 2;
        while (separator < block.length && block[separator] == (byte) 0xFF) separator++;
        if (separator < 10 || block[separator] != 0) {
            throw new IllegalStateException("RSA signed block padding invalid");
        }
        byte[] result = new byte[block.length - separator - 1];
        System.arraycopy(block, separator + 1, result, 0, result.length);
        return result;
    }

    private static boolean isValidStreamUrl(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        try {
            java.net.URL url = new java.net.URL(raw);
            String host = url.getHost();
            if (!host.equals("kksmg.com") && !host.endsWith(".kksmg.com")) return false;
            if (!url.getPath().toLowerCase().endsWith(".m3u8")) return false;
            if (url.getQuery() == null || url.getQuery().isEmpty()) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static PublicKey getPublicKey() throws Exception {
        if (sPublicKey != null) return sPublicKey;
        String pem = STREAM_PUBLIC_KEY_PEM
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] keyBytes = android.util.Base64.decode(pem, android.util.Base64.NO_WRAP);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        sPublicKey = kf.generatePublic(spec);
        return sPublicKey;
    }

    private static String getDeviceUuid() {
        if (sDeviceUuid == null) {
            sDeviceUuid = randomString(21, UUID_ALPHABET);
        }
        return sDeviceUuid;
    }

    private static String randomString(int length, String alphabet) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
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