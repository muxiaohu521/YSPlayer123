package com.ysplayer;

import android.view.ViewGroup;

/**
 * HARD 解码模式统一管理器。
 * 所有内核的 HARD 模式均委托给同一个 System WebView 实例，
 * 内部包装 HardBaseEngine（纯 WebView 管理，不含 IJK 逻辑），
 * 避免与系统模式 SystemWebEngine 的 IJK 解析逻辑冲突。
 */
public class HardWebViewManager {

    private static final String TAG = "HardWebViewMgr";

    private final ViewGroup mContainer;
    private HardBaseEngine mEngine;
    private WebEngine.Callback mCallback;
    private int mUaMode;
    private boolean mCreated;

    public HardWebViewManager(ViewGroup container) {
        this.mContainer = container;
    }

    public void setCallback(WebEngine.Callback callback) {
        this.mCallback = callback;
        if (mEngine != null) {
            mEngine.setCallback(callback);
        }
    }

    public void create() {
        LogUtil.i(TAG + ": [HARD_TRACE] create() called, mCreated=" + mCreated);
        if (mCreated) {
            LogUtil.i(TAG + ": [HARD_TRACE] create: already created, skip");
            return;
        }
        mEngine = new HardBaseEngine(mContainer);
        mEngine.setCallback(mCallback);
        mEngine.setUaMode(mUaMode);
        mEngine.setDecodingMode(WebEngine.DECODING_MODE_HARD);
        mEngine.init(mContainer);
        mEngine.setVisible(false);
        mCreated = true;
        LogUtil.i(TAG + ": [HARD_TRACE] create: done, engine=" + (mEngine != null)
                + " mCreated=" + mCreated);
    }

    public void setUaMode(int mode) {
        mUaMode = mode;
        if (mEngine != null) {
            mEngine.setUaMode(mode);
        }
    }

    public void loadUrl(String url) {
        LogUtil.i(TAG + ": [HARD_TRACE] loadUrl: "
                + (url != null ? url.substring(0, Math.min(80, url.length())) + "..." : "null")
                + " engine=" + (mEngine != null)
                + " fullscreenActive=" + (mEngine != null ? mEngine.isFullscreenActive() : false));
        if (mEngine != null) {
            mEngine.loadUrl(url);
        }
    }

    public void stopLoading() {
        if (mEngine != null) {
            mEngine.stopLoading();
        }
    }

    public void reload() {
        LogUtil.i(TAG + ": [HARD_TRACE] reload: engine=" + (mEngine != null)
                + " fullscreenActive=" + (mEngine != null ? mEngine.isFullscreenActive() : false));
        if (mEngine != null) {
            mEngine.reload();
        }
    }

    public void onResume() {
        boolean fsActive = mEngine != null && mEngine.isFullscreenActive();
        LogUtil.i(TAG + ": [HARD_TRACE] onResume: engine=" + (mEngine != null)
                + " fullscreenActive=" + fsActive);
        if (mEngine != null) {
            mEngine.onResume();
            if (!fsActive) {
                LogUtil.i(TAG + ": [HARD_TRACE] onResume: calling setVisible(true)");
                mEngine.setVisible(true);
            } else {
                LogUtil.i(TAG + ": [HARD_TRACE] onResume: SKIP setVisible (fullscreen active)");
            }
        }
    }

    public void onPause() {
        boolean fsActive = mEngine != null && mEngine.isFullscreenActive();
        LogUtil.i(TAG + ": [HARD_TRACE] onPause: engine=" + (mEngine != null)
                + " fullscreenActive=" + fsActive);
        if (mEngine != null) {
            mEngine.onPause();
            if (!fsActive) {
                LogUtil.i(TAG + ": [HARD_TRACE] onPause: calling setVisible(false)");
                mEngine.setVisible(false);
            } else {
                LogUtil.i(TAG + ": [HARD_TRACE] onPause: SKIP setVisible (fullscreen active)");
            }
        }
    }

    public void setVisible(boolean visible) {
        boolean fsActive = mEngine != null && mEngine.isFullscreenActive();
        LogUtil.i(TAG + ": [HARD_TRACE] setVisible(" + visible + "): engine=" + (mEngine != null)
                + " fullscreenActive=" + fsActive);
        if (mEngine != null) {
            if (visible && fsActive) {
                LogUtil.i(TAG + ": [HARD_TRACE] setVisible: BLOCKED (fullscreen active)");
                return;
            }
            mEngine.setVisible(visible);
        }
    }

    public void destroy() {
        LogUtil.i(TAG + ": [HARD_TRACE] destroy: engine=" + (mEngine != null)
                + " mCreated=" + mCreated);
        if (mEngine != null) {
            mEngine.onDestroy();
            mEngine = null;
        }
        mCreated = false;
        LogUtil.i(TAG + ": [HARD_TRACE] destroy: done");
    }
}