package com.ysplayer.parser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParseResult {

    private final String videoUrl;
    private final String title;
    private final String quality;
    private final Map<String, String> headers;
    private final List<QualityOption> qualityOptions;
    private boolean audioOnly;

    public static class QualityOption {
        public final String url;
        public final String label;
        public final int width;
        public final int height;

        public QualityOption(String url, String label, int width, int height) {
            this.url = url;
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }

    public ParseResult(String videoUrl) {
        this(videoUrl, null, null, null, null);
    }

    public ParseResult(String videoUrl, String title, String quality,
            Map<String, String> headers, List<QualityOption> qualityOptions) {
        this.videoUrl = videoUrl;
        this.title = title;
        this.quality = quality;
        this.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
        this.qualityOptions = qualityOptions;
    }

    public String getVideoUrl() {
        return videoUrl;
    }

    public String getTitle() {
        return title;
    }

    public String getQuality() {
        return quality;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public List<QualityOption> getQualityOptions() {
        return qualityOptions;
    }

    public boolean hasMultipleQualities() {
        return qualityOptions != null && qualityOptions.size() > 1;
    }

    public boolean isAudioOnly() {
        return audioOnly;
    }

    public void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    public boolean isValid() {
        return videoUrl != null && !videoUrl.isEmpty();
    }

    @Override
    public String toString() {
        return "ParseResult{url=" + videoUrl + ", title=" + title
                + ", quality=" + quality + "}";
    }
}