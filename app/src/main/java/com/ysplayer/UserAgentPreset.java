package com.ysplayer;


public enum UserAgentPreset {
    ANDROID_TV(0, "X5原生 / Android TV",
            "Mozilla/5.0 (Linux; Android 10; TV Box) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.164 Mobile Safari/537.36",
            "安卓模式"),
    CHROME_PHONE(4, "Chrome Phone",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
            "安卓模式"),
    SAFARI_IOS(5, "Safari iPhone",
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Mobile/15E148 Safari/604.1",
            "安卓模式"),
    CHROME_DESKTOP(1, "Chrome Win",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "桌面模式"),
    FIREFOX_DESKTOP(2, "Firefox Win",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0",
            "桌面模式"),
    SAFARI_MAC(3, "Safari Mac",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/604.1",
            "桌面模式");

    public final int id;
    public final String label;
    public final String ua;
    public final String category;

    UserAgentPreset(int id, String label, String ua, String category) {
        this.id = id;
        this.label = label;
        this.ua = ua;
        this.category = category;
    }

    public static UserAgentPreset fromId(int id) {
        for (UserAgentPreset p : values()) {
            if (p.id == id) return p;
        }
        return ANDROID_TV;
    }

    public static final UserAgentPreset DEFAULT = FIREFOX_DESKTOP;
}