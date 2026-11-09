package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 黑龙江广播电视台（极光新闻 / 东北网）解析器。
 *
 * 官方直播入口为固定地址，端口 4430（非标准），不带签名，清单和分片不校验 Referer，
 * 不挑 UA。频道直连 idclive.hljtv.com:4430/live/<stream>.m3u8，播放器直连官方 CDN。
 */
public class DbwParser extends BaseParser {

    private static final String TAG = "DbwParser";
    private static final String DOMAIN = "dbw.cn";
    private static final String MEDIA_BASE = "https://idclive.hljtv.com:4430/live/";
    private static final Pattern SLUG_PAT =
            Pattern.compile("/zhibodan/zhibo_dan/([a-z0-9_]+)/?", Pattern.CASE_INSENSITIVE);

    private static class ChannelDef {
        final String name;
        final String stream;
        ChannelDef(String name, String stream) { this.name = name; this.stream = stream; }
    }

    private static final Map<String, ChannelDef> CHANNEL_BY_SLUG = new LinkedHashMap<>();
    static {
        CHANNEL_BY_SLUG.put("hljws", new ChannelDef("黑龙江卫视", "hljws_own.m3u8"));
        CHANNEL_BY_SLUG.put("hljds", new ChannelDef("黑龙江都市", "dushi_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljxw", new ChannelDef("黑龙江新闻法治", "hljxw_hd.m3u8"));
        CHANNEL_BY_SLUG.put("heilongjiang", new ChannelDef("黑龙江新闻法治", "hljxw_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljwt", new ChannelDef("黑龙江文体", "hljwy_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljse", new ChannelDef("黑龙江少儿", "hljse_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljys", new ChannelDef("黑龙江影视", "hljys_hd.m3u8"));
        CHANNEL_BY_SLUG.put("hljnykj", new ChannelDef("黑龙江农业科教", "hljgg_hd.m3u8"));
    }

    @Override public String getName() { return "东北网(黑龙江)"; }
    @Override public boolean canParse(String url) { return url != null && url.contains(DOMAIN); }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    String slug = extractSlug(url);
                    ChannelDef def = CHANNEL_BY_SLUG.get(slug);
                    if (def == null) {
                        def = CHANNEL_BY_SLUG.get("hljws");
                        LogUtil.i(TAG + ": slug not recognized, defaulting to " + def.name);
                    }

                    String streamUrl = MEDIA_BASE + def.stream;

                    Map<String, String> headers = defaultHeadersWithUA(
                            "https://www.dbw.cn/", userAgent);
                    headers.put("Referer", "https://www.dbw.cn/");

                    List<ParseResult.QualityOption> options = new ArrayList<>();
                    options.add(new ParseResult.QualityOption(streamUrl, "720P", 1280, 720));

                    LogUtil.i(TAG + ": parsed slug=" + slug + " -> " + def.name);
                    callback.onSuccess(new ParseResult(streamUrl, def.name, "720P",
                            headers, options));
                } catch (Exception e) {
                    LogUtil.e(TAG + ": parse failed", e);
                    callback.onError("东北网解析异常: " + e.getMessage());
                }
            }
        });
    }

    private String extractSlug(String url) {
        if (url == null) return null;
        Matcher m = SLUG_PAT.matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }
}