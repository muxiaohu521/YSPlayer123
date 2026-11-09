package com.ysplayer;

import android.view.ViewGroup;

public final class EngineFactory {

    private EngineFactory() {
    }

    public static WebEngine createEngine(ViewGroup container) {
        return new GeckoWebEngine(container);
    }
}