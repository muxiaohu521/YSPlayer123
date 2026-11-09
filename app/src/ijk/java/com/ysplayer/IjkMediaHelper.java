package com.ysplayer;

import android.net.Uri;

public class IjkMediaHelper {

    public static final String HEADER_FORMAT = "TVBox-Format";

    public static boolean isDirectMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;

        String lower = url.toLowerCase();
        if (lower.startsWith("rtmp://") || lower.startsWith("rtsp://")) {
            return true;
        }

        if (lower.endsWith(".m3u8") || lower.contains(".m3u8?")
                || lower.endsWith(".mp4") || lower.contains(".mp4?")
                || lower.endsWith(".ts") || lower.contains(".ts?")
                || lower.endsWith(".flv") || lower.contains(".flv?")
                || lower.endsWith(".mpd") || lower.contains(".mpd?")
                || lower.endsWith(".avi") || lower.contains(".avi?")
                || lower.endsWith(".mkv") || lower.contains(".mkv?")
                || lower.endsWith(".webm") || lower.contains(".webm?")) {
            return true;
        }

        if (lower.contains("type=m3u8") || lower.contains("type=hls")
                || lower.contains("format=m3u8") || lower.contains("format=hls")) {
            return true;
        }

        if (lower.contains("application/x-mpegurl")
                || lower.contains("application/vnd.apple.mpegurl")) {
            return true;
        }

        if (lower.contains("/live/") || lower.contains("/stream/")) {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (path != null) {
                String pl = path.toLowerCase();
                if (pl.endsWith("/live.php") || pl.endsWith("/stream.php")
                        || pl.endsWith("/playlist.m3u8") || pl.endsWith("/index.m3u8")) {
                    return true;
                }
            }
        }

        return false;
    }
}