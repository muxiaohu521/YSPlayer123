package com.ysplayer;

import android.view.ViewGroup;

public class EngineFactory {
    public static WebEngine createEngine(ViewGroup container) {
        return new X5WebEngine(container.getContext());
    }
}