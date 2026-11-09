package com.ysplayer;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import com.ysplayer.parser.CctvParser;
import com.ysplayer.parser.ChannelParser;
import com.ysplayer.parser.ParserManager;
import com.ysplayer.parser.ParseResult;

import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSession.PermissionDelegate;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.io.File;
import java.net.InetAddress;

public class GeckoWebEngine implements WebEngine {

    private static final String TAG = "GeckoWebEngine68";
    private static GeckoRuntime sRuntime;
    private static boolean sExtLoaded = false;

    private static String optimizeDesktopUA(UserAgentPreset preset) {
        String ua = preset.ua;
        if ("桌面模式".equals(preset.category)) {
            ua = ua.replace("Windows NT 10.0; Win64; x64", "X11; Linux x86_64");
            ua = ua.replace("Macintosh; Intel Mac OS X 14_6", "X11; Linux x86_64");
            ua = ua.replace("Firefox/129.0", "Firefox/68.0");
            ua = ua.replace("rv:129.0", "rv:68.0");
            ua = ua.replace("Version/17.6", "Version/13.0");
        }
        return ua;
    }

    public static void shutdownRuntime() {
        if (sRuntime != null) {
            sRuntime = null;
        }
    }

    private final ViewGroup mContainer;
    private Callback mCallback;
    private int mUaMode;

    private GeckoView mGeckoView;
    private GeckoSession mSession;
    private int mDecodingMode = DECODING_MODE_IJK;
    private IjkVideoPlayer mIjkPlayer;
    private HardWebViewManager mHardMgr;
    private boolean mHardActive;
    private boolean mIjkActive;
    private boolean mStopExpected;
    private String mCurrentUrl = "about:blank";
    private String mPendingUrl;
    private String mPendingUa;
    private volatile int mParseGeneration;
    private int mParserRetryCount;
    private int mPlayerRetryCount;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1500;
    private Runnable mPendingRetryRunnable;
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public GeckoWebEngine(ViewGroup container) {
        this.mContainer = container;
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mHardMgr != null) mHardMgr.setCallback(callback);
    }

    @Override
    public void init(ViewGroup container) {
        LogUtil.i(TAG + ": init start, container=" + (container != null));

        mCurrentUrl = "about:blank";
        mHardMgr = new HardWebViewManager(mContainer);
        mHardMgr.setCallback(mCallback);

        try {
            GeckoRuntime rt = getOrCreateRuntime(mContainer.getContext());

            GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                    .useTrackingProtection(false)
                    .allowJavascript(true)
                    .suspendMediaWhenInactive(false)
                    .build();

            GeckoSession session = new GeckoSession(sessionSettings);
            configureSession(session);
            session.open(rt);

            mGeckoView = new GeckoView(mContainer.getContext());
            mContainer.addView(mGeckoView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            mGeckoView.setSession(session);
            mSession = session;

            if (mDecodingMode == DECODING_MODE_IJK) {
                ensureIjkParserEngine();
            }

            LogUtil.i(TAG + ": init success");
        } catch (Exception e) {
            LogUtil.e(TAG + ": init failed: " + e.getMessage(), e);
        }
    }

    private static synchronized GeckoRuntime getOrCreateRuntime(Context context) {
        if (sRuntime == null) {
            GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
                    .javaScriptEnabled(true)
                    .autoplayDefault(GeckoRuntimeSettings.AUTOPLAY_DEFAULT_ALLOWED)
                    .remoteDebuggingEnabled(BuildConfig.LOG_ENABLED)
                    .consoleOutput(BuildConfig.LOG_ENABLED);
            sRuntime = GeckoRuntime.create(context.getApplicationContext(), builder.build());
            loadExtension(sRuntime);
        }
        return sRuntime;
    }

    private static synchronized void loadExtension(GeckoRuntime runtime) {
        if (sExtLoaded) return;
        sExtLoaded = true;
        try {
            WebExtension ext = new WebExtension(
                "resource://android/assets/gecko68-extension/",
                "gecko68-autoplay@ysplayer",
                WebExtension.Flags.NONE
            );
            runtime.registerWebExtension(ext);
            LogUtil.i(TAG + ": extension registered ok");
        } catch (Exception e) {
            LogUtil.e(TAG + ": extension reg failed: " + e.getMessage());
        }
    }

    private void configureSession(GeckoSession session) {
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession session, String url) {
                LogUtil.d(TAG + ": onLocationChange: " + url);

                if (url != null && url.startsWith("ysplayer://ijk?url=")) {
                    if (mIjkActive) return;
                    mIjkActive = true;
                    try {
                        String encoded = url.substring("ysplayer://ijk?url=".length());
                        final String videoUrl = CctvParser.convertCdrmToHls(
                                java.net.URLDecoder.decode(encoded, "UTF-8"));
                        LogUtil.i(TAG + ": IJK scheme intercepted: " + videoUrl);

                        if (mGeckoView != null) {
                            mGeckoView.setVisibility(View.INVISIBLE);
                        }
                        if (mIjkPlayer != null) {
                            mIjkPlayer.playUrl(videoUrl);
                        }
                    } catch (Exception e) {
                        mIjkActive = false;
                        LogUtil.e(TAG + ": scheme decode error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            }

            @Override
            public void onCanGoForward(GeckoSession session, boolean canGoForward) {
            }

            @Override
            public org.mozilla.geckoview.GeckoResult<org.mozilla.geckoview.AllowOrDeny> onLoadRequest(
                    GeckoSession session, LoadRequest request) {
                return org.mozilla.geckoview.GeckoResult.fromValue(
                        org.mozilla.geckoview.AllowOrDeny.ALLOW);
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession session, String url) {
                LogUtil.d(TAG + ": onPageStart: " + url);
                if ("about:blank".equals(url)) return;
                mStopExpected = false;
                if (url != null) {
                    mCurrentUrl = url;
                }
                if (mCallback != null) {
                    mCallback.onPageStarted();
                }
            }

            @Override
            public void onPageStop(GeckoSession session, boolean success) {
                LogUtil.d(TAG + ": onPageStop: success=" + success + " url=" + mCurrentUrl);
                if (success) {
                    if (mStopExpected) {
                        mStopExpected = false;
                        return;
                    }
                    if (mCallback != null) {
                        mCallback.onPageFinished();
                    }
                } else {
                    if (mStopExpected) {
                        mStopExpected = false;
                        return;
                    }
                    if (mCurrentUrl != null && !mCurrentUrl.equals("about:blank")) {
                        LogUtil.e(TAG + ": page load failed: " + mCurrentUrl);
                        if (mCallback != null) {
                            mCallback.onLoadError("页面加载失败");
                        }
                    } else {
                        LogUtil.d(TAG + ": ignoring about:blank page stop failure");
                    }
                }
            }

            @Override
            public void onProgressChange(GeckoSession session, int progress) {
            }

            @Override
            public void onSecurityChange(GeckoSession session,
                    GeckoSession.ProgressDelegate.SecurityInformation info) {
            }
        });

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession session, String title) {
            }

            @Override
            public void onCrash(GeckoSession session) {
                LogUtil.e(TAG + ": onCrash");
            }

            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                LogUtil.i(TAG + ": onFullScreen=" + fullScreen);
            }
        });

        session.setPermissionDelegate(new PermissionDelegate() {
            @Override
            public void onContentPermissionRequest(GeckoSession session, String uri,
                                                    int type, Callback callback) {
                LogUtil.w(TAG + ": permission requested type=" + type
                        + " uri=" + uri + " - granting");
                callback.grant();
            }
        });

        applyUa(session);
    }

    private void applyUa(GeckoSession session) {
        if (mUaMode == 0) {
            session.getSettings().setUserAgentOverride(null);
            LogUtil.i(TAG + ": UA -> Gecko Native (default)");
            return;
        }
        UserAgentPreset preset = UserAgentPreset.fromId(mUaMode);
        String ua = optimizeDesktopUA(preset);
        session.getSettings().setUserAgentOverride(ua);
        LogUtil.i(TAG + ": UA -> " + preset.label
                + (preset.category.equals("桌面模式") ? " (optimized for Linux)" : ""));
    }

    private void ensureIjkParserEngine() {
        if (mIjkPlayer == null && mContainer != null) {
            mIjkPlayer = new IjkVideoPlayer(
                    mContainer.getContext(), mContainer,
                    new IjkVideoPlayer.Callback() {
                        @Override
                        public void onPlayerClosed() {
                            mIjkActive = false;
                        }
                        @Override
                        public void onPlayerError(String message) {
                            LogUtil.e(TAG + ": IjkPlayer error: " + message
                                    + " retry=" + mPlayerRetryCount + "/" + MAX_RETRIES);
                            retryPlayFromParserUrl();
                        }
                        @Override
                        public void onPlayerStateChanged(int state, String info) {
                            LogUtil.d(TAG + ": IjkPlayer state=" + state
                                    + (info != null ? " " + info : ""));
                            if (state == IjkVideoPlayer.STATE_READY) {
                                mPlayerRetryCount = 0;
                            } else if (state == IjkVideoPlayer.STATE_ENDED) {
                                LogUtil.i(TAG + ": IJK playback ended (live reconnect)");
                                retryPlayFromParserUrl();
                            }
                        }
                    });
            mIjkPlayer.setUserAgent(UserAgentPreset.fromId(mUaMode).ua);
            LogUtil.i(TAG + ": IjkPlayer parser engine created");
        }
    }

    private void hideIjkPlayer() {
        mIjkActive = false;
        if (mGeckoView != null) {
            mGeckoView.setVisibility(View.VISIBLE);
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.stop();
            mIjkPlayer.hide();
        }
    }

    private void cleanupIjkForNavigation() {
        mIjkActive = false;
    }

    private void hideGeckoView() {
        if (mSession == null || mGeckoView == null) return;
        LogUtil.i(TAG + ": closing GeckoView session (mode switch)");
        mStopExpected = true;
        try {
            mSession.stop();
        } catch (Exception ignored) {}
        mStopExpected = true;
        try {
            mSession.close();
        } catch (Exception ignored) {}
        try {
            mContainer.removeView(mGeckoView);
        } catch (Exception ignored) {}
        mSession = null;
        mGeckoView = null;
    }

    private void showGeckoView() {
        if (mSession == null || mGeckoView == null) {
            LogUtil.i(TAG + ": creating GeckoView for SOFT mode");
            try {
                GeckoRuntime runtime = getOrCreateRuntime(mContainer.getContext());
                GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                        .useTrackingProtection(false)
                        .allowJavascript(true)
                        .suspendMediaWhenInactive(false)
                        .build();
                mSession = new GeckoSession(sessionSettings);
                mCurrentUrl = "about:blank";
                configureSession(mSession);
                mSession.open(runtime);

                mGeckoView = new GeckoView(mContainer.getContext());
                mGeckoView.setSession(mSession);
                mContainer.addView(mGeckoView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                applyUa(mSession);
            } catch (Exception e) {
                LogUtil.e(TAG + ": failed to create GeckoView", e);
                return;
            }
        }
        try {
            mSession.setActive(true);
        } catch (Exception ignored) {}
        mGeckoView.setVisibility(View.VISIBLE);
        unmuteGeckoVideo();
    }

    private void muteGeckoVideo() {
        // Gecko doesn't support evaluateJavascript directly; mute handled by page reload
    }

    private void unmuteGeckoVideo() {
        // Gecko doesn't support evaluateJavascript directly; unmute handled by page reload
    }

    @Override
    public void loadUrl(String url) {
        cancelPendingRetry();
        if (mDecodingMode == DECODING_MODE_IJK
                && IjkMediaHelper.isDirectMediaUrl(url)) {
            LogUtil.i(TAG + ": direct media URL, playing via IjkPlayer: " + url);
            mPendingUrl = url;
            mPendingUa = UserAgentPreset.fromId(mUaMode).ua;
            mParserRetryCount = 0;
            mPlayerRetryCount = 0;
            ++mParseGeneration;
            cleanupIjkForNavigation();
            ensureIjkParserEngine();
            mIjkActive = true;
            if (mIjkPlayer != null) {
                mIjkPlayer.setUserAgent(mPendingUa);
                mIjkPlayer.playUrl(CctvParser.convertCdrmToHls(url));
            }
            return;
        }

        if (mDecodingMode == DECODING_MODE_IJK) {
            mPendingUrl = url;
            mParserRetryCount = 0;
            mPlayerRetryCount = 0;
            final int gen = ++mParseGeneration;
            LogUtil.i(TAG + ": IJK parser mode - parsing: " + url + " gen=" + gen);
            cleanupIjkForNavigation();
            ensureIjkParserEngine();
            if (mCallback != null) {
                mCallback.onPageStarted();
            }
            String ua = UserAgentPreset.fromId(mUaMode).ua;
            mPendingUa = ua;
            executeParseAndPlay(url, ua, gen);
            return;
        }

        mPendingUrl = url;
        if (mHardActive) {
            mHardMgr.stopLoading();
            mHardMgr.loadUrl(url);
            return;
        }
        if (mSession != null) {
            mCurrentUrl = null;
            mSession.stop();
            mSession.loadUri(url);
            LogUtil.i(TAG + ": loading " + url);
        } else {
            LogUtil.w(TAG + ": GeckoView not ready, url queued: " + url);
        }
    }

    private void executeParseAndPlay(final String url, final String ua, final int gen) {
        ParserManager.getInstance().parse(url, ua, new ChannelParser.ParseCallback() {
            @Override
            public void onSuccess(final ParseResult result) {
                if (gen != mParseGeneration) {
                    LogUtil.d(TAG + ": stale parse result gen=" + gen
                            + " current=" + mParseGeneration + ", discarding");
                    return;
                }
                LogUtil.i(TAG + ": parser success -> " + result.getVideoUrl() + " gen=" + gen);
                mParserRetryCount = 0;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mParseGeneration) return;
                        mIjkActive = true;
                        if (mIjkPlayer != null) {
                            String finalUrl = CctvParser.convertCdrmToHls(result.getVideoUrl());
                            java.util.Map<String, String> headers = result.getHeaders();
                            mIjkPlayer.setUserAgent(ua);
                            mIjkPlayer.playUrl(finalUrl,
                                    headers != null ? headers : null,
                                    result.isAudioOnly());
                        }
                        if (mCallback != null) {
                            mCallback.onPageFinished();
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                if (gen != mParseGeneration) {
                    LogUtil.d(TAG + ": stale parse error gen=" + gen
                            + " current=" + mParseGeneration + ", discarding");
                    return;
                }
                LogUtil.e(TAG + ": parser error: " + message
                        + " retry=" + mParserRetryCount + "/" + MAX_RETRIES);
                mParserRetryCount++;
                if (mParserRetryCount <= MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * mParserRetryCount;
                    LogUtil.i(TAG + ": parser retry in " + delay + "ms");
                    cancelPendingRetry();
                    mPendingRetryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            mPendingRetryRunnable = null;
                            executeParseAndPlay(url, ua, mParseGeneration);
                        }
                    };
                    mMainHandler.postDelayed(mPendingRetryRunnable, delay);
                } else {
                    mIjkActive = false;
                    if (mCallback != null) {
                        mCallback.onLoadError(message + " (重试" + MAX_RETRIES + "次失败)");
                    }
                }
            }
        });
    }

    private void cancelPendingRetry() {
        if (mPendingRetryRunnable != null) {
            mMainHandler.removeCallbacks(mPendingRetryRunnable);
            mPendingRetryRunnable = null;
        }
    }

    private void retryPlayFromParserUrl() {
        cancelPendingRetry();
        mPlayerRetryCount++;
        if (mPlayerRetryCount > MAX_RETRIES) {
            LogUtil.e(TAG + ": player retry exhausted after " + MAX_RETRIES + " attempts");
            return;
        }
        long delay = RETRY_BASE_DELAY_MS * mPlayerRetryCount;
        if (mPendingUrl == null || mPendingUa == null) {
            LogUtil.e(TAG + ": no pending URL for player retry");
            return;
        }
        final String url = mPendingUrl;
        final String ua = mPendingUa;
        if (IjkMediaHelper.isDirectMediaUrl(url)) {
            LogUtil.i(TAG + ": player error, direct replay in " + delay + "ms");
            mPendingRetryRunnable = new Runnable() {
                @Override
                public void run() {
                    mPendingRetryRunnable = null;
                    if (mIjkPlayer != null) {
                        mIjkPlayer.setUserAgent(ua);
                        mIjkPlayer.playUrl(CctvParser.convertCdrmToHls(url));
                    }
                }
            };
        } else {
            LogUtil.i(TAG + ": player error, re-parse in " + delay + "ms");
            final int gen = mParseGeneration;
            mPendingRetryRunnable = new Runnable() {
                @Override
                public void run() {
                    mPendingRetryRunnable = null;
                    executeParseAndPlay(url, ua, gen);
                }
            };
        }
        mMainHandler.postDelayed(mPendingRetryRunnable, delay);
    }

    @Override
    public void stopLoading() {
        cancelPendingRetry();
        ++mParseGeneration;
        clearAllCaches();
        if (mIjkPlayer != null) {
            mIjkPlayer.stop();
        }
        if (mHardActive) {
            mHardMgr.stopLoading();
            return;
        }
        if (mSession != null) {
            mCurrentUrl = null;
            mSession.stop();
        }
    }

    @Override
    public void reload() {
        cancelPendingRetry();
        ++mParseGeneration;
        clearAllCaches();
        if (mHardActive) {
            mHardMgr.reload();
            return;
        }
        if (mIjkActive && mIjkPlayer != null) {
            if (mPendingUrl != null && !mPendingUrl.isEmpty()) {
                loadUrl(mPendingUrl);
            }
            return;
        }
        if (mSession != null) {
            mSession.reload();
        }
    }

    @Override
    public void setUaMode(int mode) {
        if (mode == mUaMode) return;
        mUaMode = mode;
        clearAllCaches();
        mHardMgr.setUaMode(mode);
        if (mSession != null) {
            applyUa(mSession);
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.setUserAgent(UserAgentPreset.fromId(mUaMode).ua);
        }
    }

    @Override
    public String getEngineType() {
        return BuildConfig.ENGINE_TYPE;
    }

    @Override
    public void setDecodingMode(int mode) {
        if (mode == mDecodingMode) return;
        cancelPendingRetry();
        ++mParseGeneration;
        clearAllCaches();
        mDecodingMode = mode;
        switch (mode) {
            case DECODING_MODE_HARD:
                LogUtil.i(TAG + ": decoding mode -> HARD (System WebView)");
                releaseIjkPlayer();
                hideGeckoView();
                mHardMgr.create();
                mHardMgr.setVisible(true);
                mHardActive = true;
                break;
            case DECODING_MODE_IJK:
                LogUtil.i(TAG + ": decoding mode -> IJK (parser engine, no WebView)");
                releaseIjkPlayer();
                hideGeckoView();
                if (mHardActive) {
                    mHardMgr.stopLoading();
                    mHardMgr.loadUrl("about:blank");
                }
                mHardMgr.setVisible(false);
                mHardActive = false;
                ensureIjkParserEngine();
                break;
            case DECODING_MODE_SOFT:
            default:
                LogUtil.i(TAG + ": decoding mode -> SOFT (Gecko native)");
                releaseIjkPlayer();
                if (mHardActive) {
                    mHardMgr.stopLoading();
                    mHardMgr.loadUrl("about:blank");
                }
                mHardMgr.setVisible(false);
                mHardActive = false;
                showGeckoView();
                break;
        }
    }

    private void releaseIjkPlayer() {
        if (mIjkPlayer != null) {
            mIjkPlayer.release();
            mIjkPlayer = null;
        }
        mIjkActive = false;
    }

    private void clearAllCaches() {
        try {
            ParserManager.getInstance().clearAllCaches();
            LogUtil.d(TAG + ": parser caches cleared");
        } catch (Exception e) {
            LogUtil.w(TAG + ": failed to clear parser caches: " + e.getMessage());
        }

        try {
            if (mContainer != null) {
                Context ctx = mContainer.getContext();
                if (ctx != null) {
                    String[] geckoDirs = {
                            "app_gecko_cache",
                            "app_webview",
                            "gecko"
                    };
                    File dataDir = ctx.getCacheDir().getParentFile();
                    if (dataDir != null) {
                        for (String dir : geckoDirs) {
                            File geckoCache = new File(dataDir, dir);
                            if (geckoCache.exists()) {
                                deleteRecursive(geckoCache);
                                LogUtil.d(TAG + ": deleted Gecko cache dir: " + geckoCache.getAbsolutePath());
                            }
                        }
                    }

                    ctx.deleteDatabase("gecko-cookies.sqlite");
                }
            }
        } catch (Exception e) {
            LogUtil.w(TAG + ": failed to clear gecko caches: " + e.getMessage());
        }

        try {
            java.lang.reflect.Field cacheField = InetAddress.class.getDeclaredField("addressCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache instanceof java.util.Map) {
                ((java.util.Map<?, ?>) cache).clear();
                LogUtil.d(TAG + ": DNS cache cleared");
            }
        } catch (Exception e) {
            LogUtil.d(TAG + ": DNS cache clear skipped (Android " + android.os.Build.VERSION.SDK_INT + ")");
        }
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    @Override
    public int[] getSupportedDecodingModes() {
        return new int[]{DECODING_MODE_HARD, DECODING_MODE_SOFT, DECODING_MODE_IJK};
    }

    @Override
    public String getDecodingModeLabel(int mode) {
        switch (mode) {
            case DECODING_MODE_HARD: return "HARD (System WebView)";
            case DECODING_MODE_SOFT: return "SOFT (Gecko)";
            case DECODING_MODE_IJK:  return "IJK (ijkplayer)";
            default: return "Unknown";
        }
    }

    @Override
    public void onPause() {
        if (mHardActive) {
            mHardMgr.onPause();
            return;
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.pause();
        }
        if (mSession != null) {
            mSession.setActive(false);
        }
    }

    @Override
    public void onResume() {
        if (mHardActive) {
            mHardMgr.onResume();
            return;
        }
        if (mSession != null) {
            mSession.setActive(true);
        }
    }

    @Override
    public void onDestroy() {
        cancelPendingRetry();
        mMainHandler.removeCallbacksAndMessages(null);
        releaseIjkPlayer();
        if (mHardMgr != null) {
            mHardMgr.destroy();
        }
        if (mSession != null) {
            try {
                mSession.close();
            } catch (Exception e) {
            }
            mSession = null;
        }
        if (mGeckoView != null) {
            mContainer.removeView(mGeckoView);
            mGeckoView = null;
        }
        mCallback = null;
    }
}