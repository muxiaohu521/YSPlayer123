package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class QualitySelector {

    private static final String TAG = "QualitySelector";
    private static final int TARGET_HEIGHT = 720;

    private QualitySelector() {
    }

    public static String selectBestQuality(List<ParseResult.QualityOption> options) {
        if (options == null || options.isEmpty()) return null;
        if (options.size() == 1) return options.get(0).url;

        List<ParseResult.QualityOption> sorted = new ArrayList<>(options);
        Collections.sort(sorted, new Comparator<ParseResult.QualityOption>() {
            @Override
            public int compare(ParseResult.QualityOption a, ParseResult.QualityOption b) {
                return Integer.compare(a.height, b.height);
            }
        });

        ParseResult.QualityOption best = null;
        int bestDiff = Integer.MAX_VALUE;

        for (ParseResult.QualityOption opt : sorted) {
            if (opt.height <= 0) continue;
            int diff = Math.abs(opt.height - TARGET_HEIGHT);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = opt;
            }
        }

        if (best != null) {
            LogUtil.i(TAG + ": selected " + best.label + " (" + best.width + "x" + best.height
                    + ") diff=" + bestDiff);
            return best.url;
        }

        LogUtil.i(TAG + ": fallback to first option: " + sorted.get(0).label);
        return sorted.get(0).url;
    }

    public static String selectBestQualityUrl(List<ParseResult.QualityOption> options) {
        return selectBestQuality(options);
    }

    public static String selectBestQualityOrFirst(List<ParseResult.QualityOption> options) {
        String url = selectBestQuality(options);
        if (url != null) return url;
        if (options != null && !options.isEmpty()) return options.get(0).url;
        return null;
    }

    public static ParseResult pickBestQuality(ParseResult result) {
        if (result == null || !result.hasMultipleQualities()) return result;

        List<ParseResult.QualityOption> options = result.getQualityOptions();
        String bestUrl = selectBestQuality(options);
        if (bestUrl == null || bestUrl.equals(result.getVideoUrl())) return result;

        String selectedLabel = result.getQuality();
        for (ParseResult.QualityOption opt : options) {
            if (opt.url.equals(bestUrl)) {
                selectedLabel = opt.label;
                break;
            }
        }

        return new ParseResult(bestUrl, result.getTitle(), selectedLabel,
                result.getHeaders(), options);
    }
}