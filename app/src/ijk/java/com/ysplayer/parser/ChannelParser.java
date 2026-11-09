package com.ysplayer.parser;

public interface ChannelParser {

    boolean canParse(String url);

    void parse(String url, String userAgent, ParseCallback callback);

    String getName();

    default void clearCache() {
    }

    interface ParseCallback {
        void onSuccess(ParseResult result);

        void onError(String message);
    }
}