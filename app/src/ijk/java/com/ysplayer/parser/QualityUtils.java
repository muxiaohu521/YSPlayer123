package com.ysplayer.parser;

import java.util.ArrayList;
import java.util.List;

final class QualityUtils {

    private QualityUtils() {
    }

    static List<ParseResult.QualityOption> buildFromUrls(List<String> urls) {
        List<ParseResult.QualityOption> options = new ArrayList<>();
        for (String u : urls) {
            int h = parseHeight(u);
            options.add(new ParseResult.QualityOption(u, h > 0 ? h + "P" : "默认", 0, h));
        }
        return options;
    }

    static int parseHeight(String url) {
        String l = url.toLowerCase();
        if (l.contains("1080") || l.contains("fhd")) return 1080;
        if (l.contains("720") || l.contains("hd")) return 720;
        if (l.contains("480") || l.contains("sd")) return 480;
        if (l.contains("360") || l.contains("ld")) return 360;
        return 0;
    }

    static String findLabel(List<ParseResult.QualityOption> options, String url) {
        for (ParseResult.QualityOption o : options) {
            if (o.url.equals(url)) return o.label;
        }
        return "默认";
    }
}