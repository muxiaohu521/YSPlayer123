package com.ysplayer;

public class IjkPlayerBridge {

    private static final String TAG = "IjkPlayerBridge";

    private boolean mIjkActive;

    public IjkPlayerBridge() {
    }

    public void reset() {
        mIjkActive = false;
    }

    public void release() {
        mIjkActive = false;
    }
}