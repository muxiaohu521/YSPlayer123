package com.ysplayer;

import android.view.ViewGroup;

public interface WebEngine {

    int DECODING_MODE_HARD = 0;
    int DECODING_MODE_SOFT = 1;
    int DECODING_MODE_IJK = 2;

    void setCallback(Callback callback);

    void init(ViewGroup container);

    void loadUrl(String url);

    void stopLoading();

    void setUaMode(int mode);

    String getEngineType();

    void setDecodingMode(int mode);

    int[] getSupportedDecodingModes();

    String getDecodingModeLabel(int mode);

    void reload();

    void onPause();

    void onResume();

    void onDestroy();

    interface Callback {
        void onPageStarted();
        void onPageFinished();
        void onLoadError(String description);
    }
}