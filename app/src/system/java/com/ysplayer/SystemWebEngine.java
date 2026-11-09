package com.ysplayer;

import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ysplayer.parser.CctvParser;
import com.ysplayer.parser.ChannelParser;
import com.ysplayer.parser.ParserManager;
import com.ysplayer.parser.ParseResult;
import com.ysplayer.parser.YangshipinParser;

import java.net.URLDecoder;

/**
 * 系统模式专属引擎 — 继承 HardBaseEngine（纯 WebView 管理），添加 IJK 解析/播放逻辑。
 * 仅编译进 system 变体，不会污染其他内核变体。
 */
public class SystemWebEngine extends HardBaseEngine {

    private static final String TAG = "SysEng";

    private int mDecodingMode = DECODING_MODE_IJK;
    private IjkVideoPlayer mIjkPlayer;
    private IjkPlayerBridge mIjkBridge;
    private boolean mIjkActive;
    private String mPendingUrl;
    private String mPendingUa;
    private int mParserRetryCount;
    private int mPlayerRetryCount;
    private volatile int mParseGeneration;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1500;
    private Runnable mPendingRetryRunnable;

    public SystemWebEngine(ViewGroup container) {
        super(container);
    }

    @Override
    public void init(ViewGroup container) {
        LogUtil.i(TAG + ": init start: decodingMode=" + mDecodingMode
                + " sdkInt=" + android.os.Build.VERSION.SDK_INT);
        try {
            if (mDecodingMode == DECODING_MODE_IJK) {
                LogUtil.i(TAG + ": IJK mode - skipping WebView");
                ensureIjkPlayer();
            } else {
                super.init(container);
            }
        } catch (Exception e) {
            LogUtil.e(TAG + ": init FAILED: " + e.getMessage());
        }
    }

    @Override
    protected void configureWebView() {
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        if (BuildConfig.LOG_ENABLED) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                LogUtil.i(TAG + ": onPageStarted: url=" + url
                        + " webViewVisibility=" + view.getVisibility()
                        + " fullscreenActive=" + (mFullscreenView != null));
                if (mSuppressCallbacks) return;
                if ("about:blank".equals(url)) return;
                if (mCallback != null) {
                    mCallback.onPageStarted();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                LogUtil.i(TAG + ": onPageFinished: url=" + url
                        + " webViewVisibility=" + view.getVisibility()
                        + " fullscreenActive=" + (mFullscreenView != null)
                        + " decodingMode=" + mDecodingMode);
                if (mSuppressCallbacks) return;
                if ("about:blank".equals(url)) return;
                if (mDecodingMode == DECODING_MODE_IJK) {
                    setupIjkBridgeJs();
                } else {
                    injectVideoEnhancement(url);
                }
                if (mCallback != null) {
                    mCallback.onPageFinished();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url.startsWith("ysplayer://ijk?url=")) {
                        return shouldOverrideUrlLoading(view, url);
                    }
                }
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && url.startsWith("ysplayer://ijk?url=")) {
                    if (mIjkActive) return true;
                    try {
                        String encoded = url.substring("ysplayer://ijk?url=".length());
                        String videoUrl = URLDecoder.decode(encoded, "UTF-8");
                        String originalUrl = videoUrl;
                        videoUrl = CctvParser.convertCdrmToHls(videoUrl);
                        if (!videoUrl.equals(originalUrl)) {
                            LogUtil.i(TAG + ": CDRM->HLS in JS interceptor: " + originalUrl + " => " + videoUrl);
                        }
                        LogUtil.i(TAG + ": IJK scheme intercepted: " + videoUrl);
                        mIjkActive = true;
                        if (mWebView != null) {
                            mWebView.setVisibility(View.INVISIBLE);
                            if (mFullscreenView != null && mFullscreenCallback != null) {
                                mFullscreenCallback.onCustomViewHidden();
                                mContainer.removeView(mFullscreenView);
                                mFullscreenView = null;
                                mFullscreenCallback = null;
                            }
                        }
                        if (mIjkPlayer != null) {
                            mIjkPlayer.playUrl(videoUrl);
                        }
                    } catch (Exception e) {
                        LogUtil.e(TAG + ": IJK scheme decode error: " + e.getMessage());
                    }
                    return true;
                }
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                LogUtil.w(TAG + ": SSL error: " + error.getUrl() + " code=" + error.getPrimaryError());
                if (mSuppressCallbacks) return;
                handler.cancel();
                if (mCallback != null) {
                    mCallback.onLoadError("SSL证书错误: " + error.getUrl());
                }
            }
        });

        mWebView.setWebChromeClient(createWebChromeClient());

        applyUa();
    }

    // ---- IJK player management ----

    private void ensureIjkPlayer() {
        if (mIjkPlayer == null && mContainer != null) {
            mIjkPlayer = new IjkVideoPlayer(
                    mContainer.getContext(), mContainer,
                    new IjkVideoPlayer.Callback() {
                        @Override
                        public void onPlayerClosed() {
                            mIjkActive = false;
                            if (mWebView != null) {
                                mWebView.setVisibility(View.VISIBLE);
                            }
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
        }
        if (mIjkBridge == null) {
            mIjkBridge = new IjkPlayerBridge();
        }
        if (mWebView != null) {
            setupIjkBridgeJs();
        }
    }

    private void releaseIjkPlayer() {
        if (mIjkBridge != null) {
            mIjkBridge.release();
            mIjkBridge = null;
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.release();
            mIjkPlayer = null;
        }
        mIjkActive = false;
    }

    private void hideWebView() {
        if (mWebView == null) return;
        LogUtil.i(TAG + ": hiding WebView (mode switch)");
        mWebView.stopLoading();
        mWebView.loadUrl("about:blank");
        mWebView.clearHistory();
        mWebView.setVisibility(View.INVISIBLE);
        if (mIjkBridge != null) {
            mIjkBridge.reset();
        }
    }

    private void showWebView() {
        if (mWebView == null) {
            LogUtil.i(TAG + ": creating WebView for HARD mode");
            try {
                mWebView = new WebView(mContainer.getContext());
                configureWebView();
                mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                LogUtil.e(TAG + ": failed to create WebView", e);
                return;
            }
        }
        mWebView.stopLoading();
        mWebView.setVisibility(View.VISIBLE);
    }

    private void cleanupIjkForNavigation() {
        if (mIjkBridge != null) {
            mIjkBridge.reset();
        }
        mIjkActive = false;
    }

    // ---- IJK bridge JS injection ----

    private void setupIjkBridgeJs() {
        if (mWebView == null || mIjkPlayer == null) return;

        String interceptorJs =
                "(function(){" +
                "var __nt={},__fd=false;" +
                "function __fi(u){" +
                " if(__fd)return;" +
                " if(!u||u.indexOf('blob:')===0||u.indexOf('data:')===0||__nt[u])return;" +
                " __nt[u]=true;__fd=true;window.__ysIjkFired=true;" +
                " var vs=document.querySelectorAll('video');" +
                " for(var i=0;i<vs.length;i++){try{vs[i].pause();}catch(e){}}" +
                " document.location='ysplayer://ijk?url='+encodeURIComponent(u);" +
                "}" +
                "function __iv(u){" +
                " if(!u)return false;" +
                " var l=u.toLowerCase();" +
                " if(/\\.(m3u8|mp4|flv|ts|mpd|mkv|avi|webm|mov|m4v|wmv|ogv|rmvb)(\\?|$|&|#)/i.test(l))return true;" +
                " if(l.indexOf('m3u8')>=0||l.indexOf('type=mpegurl')>=0||l.indexOf('.mp4')>=0)return true;" +
                " if(l.indexOf('/video/')>=0||l.indexOf('/stream/')>=0)return true;" +
                " return false;" +
                "}" +
                "function __tr(v){" +
                " if(!v)return;" +
                " var u=v.currentSrc||v.src||'';__fi(u);" +
                " if(v.srcObject){try{var ts=v.srcObject.getVideoTracks();" +
                " if(ts&&ts.length>0)__fi('mediastream://captured');}catch(e){}}" +
                " var ss=v.querySelectorAll('source');" +
                " for(var j=0;j<ss.length;j++)__fi(ss[j].src);" +
                "}" +
                "function __hk(v){" +
                " if(!v||v.__hk)return;v.__hk=true;__tr(v);" +
                " v.addEventListener('play',function(e){__tr(e.target);});" +
                " v.addEventListener('loadedmetadata',function(e){__tr(e.target);});" +
                " v.addEventListener('loadstart',function(e){__tr(e.target);});" +
                " v.addEventListener('canplay',function(e){__tr(e.target);});" +
                " if(window.MutationObserver){" +
                "  var ao=new MutationObserver(function(){__tr(ao.__v);});" +
                "  ao.__v=v;ao.observe(v,{attributes:true,attributeFilter:['src']});" +
                " }" +
                "}" +
                "function __ss(r){" +
                " if(!r||!r.querySelectorAll)return;" +
                " var vs=r.querySelectorAll('video,audio');" +
                " for(var i=0;i<vs.length;i++)__hk(vs[i]);" +
                " var al=r.querySelectorAll('*');" +
                " for(var i=0;i<al.length;i++)" +
                "  if(al[i].shadowRoot)__ss(al[i].shadowRoot);" +
                "}" +
                "function __sd(d){" +
                " if(!d)return;__ss(d);" +
                " var ifs=d.querySelectorAll('iframe');" +
                " for(var i=0;i<ifs.length;i++){" +
                "  try{var id=ifs[i].contentDocument||" +
                "   (ifs[i].contentWindow&&ifs[i].contentWindow.document);" +
                "   if(id)__sd(id);}catch(e){}" +
                " }" +
                "}" +
                "if(window.XMLHttpRequest){" +
                " var _o=XMLHttpRequest.prototype.open;" +
                " var _s=XMLHttpRequest.prototype.send;" +
                " XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return _o.apply(this,arguments);};" +
                " XMLHttpRequest.prototype.send=function(){" +
                "  var s=this;s.addEventListener('load',function(){" +
                "   if(__fd)return;" +
                "   var u=s.__u||s.responseURL||'';" +
                "   if(__iv(u))__fi(u);" +
                "   if(s.responseURL&&s.responseURL!==u&&__iv(s.responseURL))__fi(s.responseURL);" +
                "  });return _s.apply(this,arguments);" +
                " };" +
                "}" +
                "if(window.fetch){" +
                " var _f=window.fetch;" +
                " window.fetch=function(url,opts){" +
                "  var u=typeof url==='string'?url:(url&&url.url);" +
                "  if(__fd)return _f.apply(this,arguments);" +
                "  return _f.apply(this,arguments).then(function(r){" +
                "   if(!__fd){var fu=r.url||u||'';if(__iv(fu))__fi(fu);}" +
                "   return r;" +
                "  });" +
                " };" +
                "}" +
                "try{var _pl=HTMLMediaElement.prototype.play;" +
                " HTMLMediaElement.prototype.play=function(){" +
                "  if(!__fd&&!this.__phk){this.__phk=true;__hk(this);}" +
                "  return _pl.apply(this,arguments);" +
                " };}catch(e){}" +
                "__sd(document);" +
                "[500,1500,3000,6000,12000,25000,45000,60000].forEach(function(d){" +
                " setTimeout(function(){if(!__fd)__sd(document);},d);" +
                "});" +
                "setInterval(function(){" +
                " if(__fd)return;" +
                " var vs=document.querySelectorAll('video,audio');" +
                " for(var i=0;i<vs.length;i++)__tr(vs[i]);" +
                "},3000);" +
                "if(window.MutationObserver){" +
                " var ob=new MutationObserver(function(m){" +
                "  if(__fd)return;" +
                "  for(var i=0;i<m.length;i++){" +
                "   var ns=m[i].addedNodes;if(!ns)continue;" +
                "   for(var j=0;j<ns.length;j++){" +
                "    var n=ns[j];if(n.nodeType!==1)continue;" +
                "    if(n.tagName==='VIDEO'||n.tagName==='AUDIO')__hk(n);" +
                "    __sd(document);break;" +
                "   }" +
                "  }" +
                " });" +
                " var t=document.body||document.documentElement;" +
                " if(t)ob.observe(t,{childList:true,subtree:true});" +
                "}" +
                "})();";

        String muteJs =
                "(function(){" +
                "function __ysMuteVs(){" +
                " var vs=document.querySelectorAll('video');" +
                " for(var i=0;i<vs.length;i++){vs[i].muted=true;vs[i].volume=0;}" +
                "}" +
                "__ysMuteVs();" +
                "setInterval(function(){__ysMuteVs();},1500);" +
                "if(window.MutationObserver){" +
                " var __ysMo=new MutationObserver(function(muts){" +
                "  for(var i=0;i<muts.length;i++){" +
                "   var ns=muts[i].addedNodes;if(!ns)continue;" +
                "   for(var j=0;j<ns.length;j++){" +
                "    if(ns[j].nodeType===1&&ns[j].tagName==='VIDEO'){" +
                "     ns[j].muted=true;ns[j].volume=0;" +
                "    }" +
                "   }" +
                "  }" +
                "  __ysMuteVs();" +
                " });" +
                " var __ysT=document.body||document.documentElement;" +
                " if(__ysT)__ysMo.observe(__ysT,{childList:true,subtree:true});" +
                "}" +
                "})();";

        mWebView.evaluateJavascript(interceptorJs, null);
        mWebView.evaluateJavascript(muteJs, null);
        LogUtil.i(TAG + ": IjkPlayer interceptor JS injected");
    }

    // ---- WebEngine overrides ----

    @Override
    public void loadUrl(String url) {
        cancelPendingRetry();
        ++mParseGeneration;
        if (mDecodingMode == DECODING_MODE_IJK
                && IjkMediaHelper.isDirectMediaUrl(url)) {
            LogUtil.i(TAG + ": direct media URL, playing via IjkPlayer: " + url);
            mPendingUrl = url;
            mPendingUa = UserAgentPreset.fromId(mUaMode).ua;
            mParserRetryCount = 0;
            mPlayerRetryCount = 0;
            cleanupIjkForNavigation();
            ensureIjkPlayer();
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
            LogUtil.i(TAG + ": IJK parser mode - parsing: " + url);
            cleanupIjkForNavigation();
            ensureIjkPlayer();
            if (mCallback != null) {
                mCallback.onPageStarted();
            }
            String ua = UserAgentPreset.fromId(mUaMode).ua;
            mPendingUa = ua;
            executeParseAndPlay(url, ua);
            return;
        }

        mPendingUrl = url;
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.clearHistory();
            LogUtil.i(TAG + ": loading " + url);
            mWebView.loadUrl(url);
        }
    }

    @Override
    public void stopLoading() {
        cancelPendingRetry();
        ++mParseGeneration;
        if (mIjkPlayer != null) {
            mIjkPlayer.stop();
        }
        if (mWebView != null) {
            mWebView.stopLoading();
        }
    }

    @Override
    public void setUaMode(int mode) {
        mUaMode = mode;
        applyUa();
        if (mIjkPlayer != null) {
            mIjkPlayer.setUserAgent(UserAgentPreset.fromId(mUaMode).ua);
        }
    }

    @Override
    public String getEngineType() {
        return "system";
    }

    @Override
    public void setDecodingMode(int mode) {
        if (mode == mDecodingMode) return;
        cancelPendingRetry();
        ++mParseGeneration;
        mDecodingMode = mode;

        switch (mode) {
            case DECODING_MODE_IJK:
                LogUtil.i(TAG + ": decoding mode -> IJK (soft decode)");
                releaseIjkPlayer();
                hideWebView();
                ensureIjkPlayer();
                break;
            case DECODING_MODE_HARD:
            default:
                LogUtil.i(TAG + ": decoding mode -> HARD (System WebView native)");
                releaseIjkPlayer();
                showWebView();
                break;
        }
    }

    @Override
    public int[] getSupportedDecodingModes() {
        return new int[]{DECODING_MODE_HARD, DECODING_MODE_IJK};
    }

    @Override
    public String getDecodingModeLabel(int mode) {
        switch (mode) {
            case DECODING_MODE_HARD: return "硬解 (MediaCodec)";
            case DECODING_MODE_IJK:  return "软解 (ijkplayer)";
            default: return "未知";
        }
    }

    @Override
    public void reload() {
        if (mIjkActive && mIjkPlayer != null) {
            mIjkPlayer.stop();
            if (mPendingUrl != null && !mPendingUrl.isEmpty()) {
                mIjkPlayer.playUrl(CctvParser.convertCdrmToHls(mPendingUrl));
            }
            return;
        }
        if (mWebView != null) {
            mWebView.reload();
        }
    }

    @Override
    public void onDestroy() {
        cancelPendingRetry();

        if (mIjkBridge != null) {
            mIjkBridge.release();
            mIjkBridge = null;
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.release();
            mIjkPlayer = null;
        }

        super.onDestroy();
    }

    // ---- Parser logic (system mode only) ----

    private void executeParseAndPlay(final String url, final String ua) {
        final int gen = mParseGeneration;
        ParserManager.getInstance().parse(url, ua, new ChannelParser.ParseCallback() {
            @Override
            public void onSuccess(final ParseResult result) {
                if (gen != mParseGeneration) {
                    LogUtil.d(TAG + ": stale parse success gen=" + gen
                            + " current=" + mParseGeneration + ", discarding");
                    return;
                }
                LogUtil.i(TAG + ": parser success -> " + result.getVideoUrl());
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
                    LogUtil.i(TAG + ": parser retry in " + delay + "ms attempt="
                            + mParserRetryCount);
                    cancelPendingRetry();
                    mPendingRetryRunnable = new Runnable() {
                        @Override
                        public void run() {
                            mPendingRetryRunnable = null;
                            executeParseAndPlay(url, ua);
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
            LogUtil.d(TAG + ": cancelled pending retry");
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
            LogUtil.i(TAG + ": player error, retry direct URL in " + delay + "ms attempt="
                    + mPlayerRetryCount + "/" + MAX_RETRIES);
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
            mMainHandler.postDelayed(mPendingRetryRunnable, delay);
            return;
        }
        LogUtil.i(TAG + ": player error, retry via re-parse in " + delay + "ms attempt="
                + mPlayerRetryCount + "/" + MAX_RETRIES);
        mPendingRetryRunnable = new Runnable() {
            @Override
            public void run() {
                mPendingRetryRunnable = null;
                YangshipinParser.invalidatePlayUrlCache(url);
                executeParseAndPlay(url, ua);
            }
        };
        mMainHandler.postDelayed(mPendingRetryRunnable, delay);
    }
}