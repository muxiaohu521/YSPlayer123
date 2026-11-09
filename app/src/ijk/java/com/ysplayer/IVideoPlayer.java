package com.ysplayer;

public interface IVideoPlayer {
    void playUrl(String url);
    boolean isActive();
    void release();
    void stop();
}