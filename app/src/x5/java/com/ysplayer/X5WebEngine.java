package com.ysplayer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;

import com.tencent.smtt.export.external.interfaces.IX5WebChromeClient;
import com.tencent.smtt.export.external.interfaces.SslError;
import com.tencent.smtt.export.external.interfaces.SslErrorHandler;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

import com.ysplayer.parser.CctvParser;
import com.ysplayer.parser.ChannelParser;
import com.ysplayer.parser.ParserManager;
import com.ysplayer.parser.ParseResult;
import com.ysplayer.parser.YangshipinParser;

import java.net.URLDecoder;

public class X5WebEngine implements WebEngine {

    private static final String TAG = "X5WebEngine";
    private static boolean sX5Initialized;

    public static void onX5CoreReady() {
        sX5Initialized = true;
        LogUtil.i(TAG + ": X5 core ready flag set");
    }

    public static boolean isX5Ready() {
        return sX5Initialized;
    }

    private final Context mContext;
    private ViewGroup mContainer;
    private Callback mCallback;
    private int mUaMode;

    private WebView mWebView;
    private View mXCustomView;
    private IX5WebChromeClient.CustomViewCallback mXCustomViewCallback;
    private int mDecodingMode = DECODING_MODE_IJK;
    private IjkVideoPlayer mIjkPlayer;
    private IjkPlayerBridge mIjkBridge;
    private HardWebViewManager mHardMgr;
    private boolean mHardActive;
    private boolean mIjkActive;
    private boolean mStopExpected;
    private String mPendingUrl;
    private String mPendingUa;
    private int mParserRetryCount;
    private int mPlayerRetryCount;
    private volatile int mParseGeneration;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1500;
    private Runnable mPendingRetryRunnable;
    private final android.os.Handler mMainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    public X5WebEngine(Context context) {
        this.mContext = context.getApplicationContext();
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mHardMgr != null) mHardMgr.setCallback(callback);
    }

    @Override
    public void init(ViewGroup container) {
        LogUtil.i(TAG + ": init start, container=" + (container != null));

        if (!sX5Initialized) {
            int version = QbSdk.getTbsVersion(mContext);
            if (version > 0) {
                LogUtil.i(TAG + ": X5 kernel already ready (prev session), version=" + version);
                sX5Initialized = true;
            }
        }

        mContainer = container;
        mHardMgr = new HardWebViewManager(container);
        mHardMgr.setCallback(mCallback);

        try {
            mWebView = new WebView(mContainer.getContext());
            configureWebView(mWebView);
            mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            if (mDecodingMode == DECODING_MODE_IJK) {
                ensureIjkPlayer();
            }
            if (mPendingUrl != null) {
                if (mWebView != null) {
                    mWebView.loadUrl(mPendingUrl);
                } else {
                    loadUrl(mPendingUrl);
                }
                mPendingUrl = null;
            }
        } catch (Exception e) {
            LogUtil.e(TAG + ": X5 WebView create failed: " + e.getMessage());
            if (mCallback != null) {
                mCallback.onLoadError("X5 init failed: " + e.getMessage());
            }
        }

        LogUtil.i(TAG + ": init done");
    }

    private void configureWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setBlockNetworkImage(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportMultipleWindows(true);
        ws.setAppCacheEnabled(true);
        ws.setSaveFormData(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setSupportZoom(true);
        ws.setLayoutAlgorithm(WebSettings.LayoutAlgorithm.NARROW_COLUMNS);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setTextZoom(100);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            ws.setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        wv.setFocusable(true);

        wv.setWebViewClient(new WebViewClient() {
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
                            LogUtil.i(TAG + ": CDRM->HLS: " + originalUrl + " => " + videoUrl);
                        }
                        LogUtil.i(TAG + ": IJK scheme intercepted: " + videoUrl);
                        mIjkActive = true;
                        if (mWebView != null) {
                            mWebView.setVisibility(View.INVISIBLE);
                        }
                        if (mIjkPlayer != null) {
                            mIjkPlayer.playUrl(videoUrl);
                        }
                    } catch (Exception e) {
                        LogUtil.e(TAG + ": IJK scheme decode error: " + e.getMessage());
                    }
                    return true;
                }
                return super.shouldOverrideUrlLoading(view, url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view,
                    com.tencent.smtt.export.external.interfaces.WebResourceRequest request) {
                if (request.getUrl() != null) {
                    String url = request.getUrl().toString();
                    if (url != null && url.startsWith("ysplayer://ijk?url=")) {
                        return shouldOverrideUrlLoading(view, url);
                    }
                }
                return super.shouldOverrideUrlLoading(view, request);
            }

            @Override
            public com.tencent.smtt.export.external.interfaces.WebResourceResponse
                    shouldInterceptRequest(WebView view,
                        com.tencent.smtt.export.external.interfaces.WebResourceRequest request) {
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view,
                    SslErrorHandler handler, SslError error) {
                LogUtil.w(TAG + ": SSL error: " + error.getUrl()
                        + " code=" + error.getPrimaryError());
                if (mStopExpected) return;
                handler.cancel();
                if (mCallback != null) {
                    mCallback.onLoadError("SSL证书错误: " + error.getUrl());
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                    String description, String failingUrl) {
                LogUtil.e(TAG + ": onReceivedError: code=" + errorCode
                        + " desc=" + description + " url=" + failingUrl);
                if (mStopExpected) {
                    mStopExpected = false;
                    return;
                }
                if (mCallback != null) {
                    mCallback.onLoadError(description);
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                LogUtil.d(TAG + ": onPageStarted: " + url);
                if ("about:blank".equals(url)) return;
                mStopExpected = false;
                if (mCallback != null) {
                    mCallback.onPageStarted();
                }

                view.evaluateJavascript(
                        "(function(){" +
                        "var __ysFlStop=false,__ysFlCnt=0;" +
                        "function FastLoading(){" +
                        " if(__ysFlStop||__ysFlCnt>3000)return;" +
                        " __ysFlCnt++;" +
                        " var v=document.querySelector('video');" +
                        " if(v&&(v.currentTime>0||v.duration>0)){__ysFlStop=true;return;}" +
                        " var imgs=document.getElementsByTagName('img');" +
                        " for(var i=0;i<imgs.length;i++)imgs[i].src='';" +
                        " var kw=['login','index','daohang','grey','jquery'];" +
                        " var scripts=document.getElementsByTagName('script');" +
                        " for(var i=0;i<scripts.length;i++){" +
                        "  var s=scripts[i];" +
                        "  for(var j=0;j<kw.length;j++){" +
                        "   if(s.src.indexOf(kw[j])>=0){s.src='';break;}" +
                        "  }" +
                        " }" +
                        " var cls=['newmap','newtopbz','newtopbzTV','column_wrapper'];" +
                        " for(var i=0;i<cls.length;i++){" +
                        "  var els=document.getElementsByClassName(cls[i]);" +
                        "  for(var j=0;j<els.length;j++)els[j].innerHTML='';" +
                        " }" +
                        " setTimeout(FastLoading,4);" +
                        "}" +
                        "document.addEventListener('DOMContentLoaded',function(){__ysFlStop=true;});" +
                        "FastLoading();" +
                        "})();",
                        null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                LogUtil.d(TAG + ": onPageFinished: " + url);
                if (mStopExpected) {
                    mStopExpected = false;
                    return;
                }
                if (!"about:blank".equals(url)) {
                    if (mDecodingMode == DECODING_MODE_SOFT) {
                        view.evaluateJavascript(getSoftModeJs(url), null);
                    }
                    if (mDecodingMode == DECODING_MODE_IJK) {
                        setupIjkBridgeJs(view);
                    }
                }
                if (mCallback != null) {
                    mCallback.onPageFinished();
                }
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            private View mXProgressVideo;

            @Override
            public void onShowCustomView(View view, IX5WebChromeClient.CustomViewCallback callback) {
                if (mXCustomView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                mXCustomView = view;
                mXCustomViewCallback = callback;
                mContainer.addView(mXCustomView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                mWebView.setVisibility(View.INVISIBLE);
            }

            @Override
            public void onHideCustomView() {
                if (mXCustomView == null) return;
                mContainer.removeView(mXCustomView);
                mXCustomView = null;
                mXCustomViewCallback.onCustomViewHidden();
                mWebView.setVisibility(View.VISIBLE);
            }

            @Override
            public View getVideoLoadingProgressView() {
                if (mXProgressVideo == null) {
                    mXProgressVideo = LayoutInflater.from(mContainer.getContext())
                            .inflate(R.layout.video_loading_progress, mContainer, false);
                }
                return mXProgressVideo;
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                if (super.getDefaultVideoPoster() == null) {
                    return BitmapFactory.decodeResource(
                            mContainer.getContext().getResources(),
                            android.R.drawable.ic_media_play);
                }
                return super.getDefaultVideoPoster();
            }
        });

        applyUa(wv, false);
    }

    private void applyUa(WebView wv, boolean switching) {
        if (wv == null) return;

        if (mUaMode == 0) {
            if (switching) {
                wv.getSettings().setUserAgentString(null);
                LogUtil.i(TAG + ": UA -> X5 Native (default)");
            }
            return;
        }

        UserAgentPreset preset = UserAgentPreset.fromId(mUaMode);
        wv.getSettings().setUserAgentString(preset.ua);
        LogUtil.i(TAG + ": UA -> " + preset.label);
    }

    private String getSoftModeJs(String url) {
        String cssInject = "var __ys=s=document.createElement('style');"
                    + "s.id='__ysFs';"
                    + "s.textContent='video{position:fixed!important;top:0!important;left:0!important;"
                    + "width:100vw!important;height:100vh!important;object-fit:contain!important;"
                    + "z-index:2147483647!important;background:#000!important;"
                    + "margin:0!important;padding:0!important}';"
                    + "(document.head||document.documentElement).appendChild(__ys);";
        return "(function(){" + cssInject +
                "var __ysE={};" +
                "function __ysEh(v){" +
                " if(!v||__ysE[v])return;__ysE[v]=1;" +
                " v.muted=false;v.volume=1.0;v.removeAttribute('muted');" +
                " v.setAttribute('playsinline','true');" +
                " v.setAttribute('webkit-playsinline','true');" +
                " try{v.play()}catch(e){}" +
                "}" +
                "function __ysS(r){" +
                " if(!r||!r.querySelectorAll)return;" +
                " var vs=r.querySelectorAll('video');" +
                " for(var i=0;i<vs.length;i++)__ysEh(vs[i]);" +
                " for(var i=0;i<r.children.length;i++)__ysS(r.children[i]);" +
                "}" +
                "function __ysI(){" +
                " var ifs=document.querySelectorAll('iframe');" +
                " for(var i=0;i<ifs.length;i++){" +
                "  try{var d=ifs[i].contentDocument||ifs[i].contentWindow.document;if(d)__ysS(d)}catch(e){}" +
                " }" +
                "}" +
                "__ysS(document);__ysI();" +
                "[500,1500,3000,6000,12000,25000].forEach(function(d){" +
                " setTimeout(function(){__ysS(document);__ysI()},d);" +
                "});" +
                "if(window.MutationObserver){" +
                " var o=new MutationObserver(function(m){" +
                "  for(var i=0;i<m.length;i++){" +
                "   var ns=m[i].addedNodes;if(!ns)continue;" +
                "   for(var j=0;j<ns.length;j++){" +
                "    var n=ns[j];" +
                "    if(n.nodeType===1){if(n.tagName==='VIDEO')__ysEh(n);__ysS(n)}" +
                "   }" +
                "  }" +
                " });" +
                " var t=document.body||document.documentElement;" +
                " if(t)o.observe(t,{childList:true,subtree:true});" +
                "}" +
                "})();";
    }

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
            setupIjkBridgeJs(mWebView);
        }
    }

    private void setupIjkBridgeJs(WebView view) {
        if (view == null || mIjkPlayer == null) return;

        view.evaluateJavascript(
                "(function(){" +
                "var __nt={},__fd=false;" +
                "function __fi(u){" +
                " if(__fd)return;" +
                " if(!u||u.indexOf('blob:')===0||u.indexOf('data:')===0||__nt[u])return;" +
                " __nt[u]=true;__fd=true;window.__ysIjkFired=true;" +
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
                "})();",
                null);

        view.evaluateJavascript(
                "(function(){" +
                "if(window.__ysIjkFired)return;" +
                "function __ysMuteVs(){" +
                " if(window.__ysIjkFired)return;" +
                " var vs=document.querySelectorAll('video');" +
                " for(var i=0;i<vs.length;i++){vs[i].muted=true;vs[i].volume=0;}" +
                "}" +
                "__ysMuteVs();" +
                "var __ysMtIv=setInterval(function(){" +
                " if(window.__ysIjkFired){clearInterval(__ysMtIv);return;}" +
                " __ysMuteVs();" +
                "},1500);" +
                "if(window.MutationObserver){" +
                " var __ysMo=new MutationObserver(function(muts){" +
                "  if(window.__ysIjkFired)return;" +
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
                "})();",
                null);

        LogUtil.i(TAG + ": IjkPlayer interceptor JS injected (scheme-based)");
    }

    private void hideIjkPlayer() {
        mIjkActive = false;
        if (mWebView != null) {
            mWebView.setVisibility(View.VISIBLE);
        }
        if (mIjkBridge != null) {
            mIjkBridge.reset();
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.stop();
            mIjkPlayer.hide();
        }
    }

    private void cleanupIjkForNavigation() {
        if (mIjkBridge != null) {
            mIjkBridge.reset();
        }
        mIjkActive = false;
    }

    private void hideWebView() {
        if (mWebView == null) return;
        LogUtil.i(TAG + ": hiding X5 WebView (mode switch)");
        mStopExpected = true;
        mWebView.stopLoading();
        mWebView.loadUrl("about:blank");
        try {
            mWebView.clearHistory();
        } catch (Exception ignored) {}
        mWebView.setVisibility(View.INVISIBLE);
        if (mIjkBridge != null) {
            mIjkBridge.reset();
        }
    }

    private void showWebView() {
        if (mWebView == null) {
            LogUtil.i(TAG + ": creating X5 WebView for SOFT mode");
            try {
                mWebView = new WebView(mContainer.getContext());
                configureWebView(mWebView);
                mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                applyUa(mWebView, false);
            } catch (Exception e) {
                LogUtil.e(TAG + ": failed to create X5 WebView", e);
                return;
            }
        }
        mWebView.setVisibility(View.VISIBLE);
        mWebView.onResume();
    }

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
        if (mHardActive) {
            mHardMgr.stopLoading();
            mHardMgr.loadUrl(url);
            return;
        }
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.loadUrl(url);
            LogUtil.i(TAG + ": loading " + url);
        } else {
            LogUtil.w(TAG + ": X5 WebView not ready, url queued: " + url);
        }
    }

    @Override
    public void stopLoading() {
        cancelPendingRetry();
        ++mParseGeneration;
        if (mIjkPlayer != null) {
            mIjkPlayer.stop();
        }
        if (mHardActive) {
            mHardMgr.stopLoading();
            return;
        }
        if (mWebView != null) {
            mWebView.stopLoading();
        }
    }

    @Override
    public void reload() {
        if (mHardActive) {
            mHardMgr.reload();
            return;
        }
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
    public void setUaMode(int mode) {
        int oldMode = mUaMode;
        mUaMode = mode;
        mHardMgr.setUaMode(mode);
        if (mWebView != null) {
            applyUa(mWebView, oldMode != mode);
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.setUserAgent(UserAgentPreset.fromId(mUaMode).ua);
        }
    }

    @Override
    public String getEngineType() {
        return "x5";
    }

    @Override
    public void setDecodingMode(int mode) {
        if (mode == mDecodingMode) return;
        cancelPendingRetry();
        ++mParseGeneration;
        mDecodingMode = mode;
        switch (mode) {
            case DECODING_MODE_HARD:
                LogUtil.i(TAG + ": decoding mode -> HARD (System WebView)");
                releaseIjkPlayer();
                hideWebView();
                mHardMgr.create();
                mHardMgr.setVisible(true);
                mHardActive = true;
                break;
            case DECODING_MODE_IJK:
                LogUtil.i(TAG + ": decoding mode -> IJK (parser engine, no WebView)");
                releaseIjkPlayer();
                hideWebView();
                if (mHardActive) {
                    mHardMgr.stopLoading();
                    mHardMgr.loadUrl("about:blank");
                }
                mHardMgr.setVisible(false);
                mHardActive = false;
                ensureIjkPlayer();
                break;
            case DECODING_MODE_SOFT:
            default:
                LogUtil.i(TAG + ": decoding mode -> SOFT (X5 native)"
                        + " x5Ready=" + sX5Initialized);
                releaseIjkPlayer();
                if (mHardActive) {
                    mHardMgr.stopLoading();
                    mHardMgr.loadUrl("about:blank");
                }
                mHardMgr.setVisible(false);
                mHardActive = false;
                if (!sX5Initialized) {
                    LogUtil.w(TAG + ": X5 kernel not ready, using fallback WebView");
                }
                showWebView();
                break;
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

    @Override
    public int[] getSupportedDecodingModes() {
        return new int[]{DECODING_MODE_HARD, DECODING_MODE_SOFT, DECODING_MODE_IJK};
    }

    @Override
    public String getDecodingModeLabel(int mode) {
        switch (mode) {
            case DECODING_MODE_HARD: return "HARD (MediaCodec)";
            case DECODING_MODE_SOFT: return "SOFT (X5)";
            case DECODING_MODE_IJK:  return "IJK (ijkplayer)";
            default: return "Unknown";
        }
    }

    @Override
    public void onResume() {
        if (mHardActive) {
            mHardMgr.onResume();
            return;
        }
        if (mWebView != null) {
            mWebView.onResume();
            mWebView.resumeTimers();
        }
    }

    @Override
    public void onPause() {
        if (mHardActive) {
            mHardMgr.onPause();
            return;
        }
        if (mWebView != null) {
            mWebView.pauseTimers();
            mWebView.onPause();
        }
    }

    @Override
    public void onDestroy() {
        cancelPendingRetry();
        mMainHandler.removeCallbacksAndMessages(null);
        mHardMgr.destroy();
        if (mIjkBridge != null) {
            mIjkBridge.release();
            mIjkBridge = null;
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.release();
            mIjkPlayer = null;
        }
        if (mXCustomView != null) {
            mContainer.removeView(mXCustomView);
            mXCustomView = null;
        }
        mXCustomViewCallback = null;
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.loadUrl("about:blank");
            try {
                mWebView.clearHistory();
            } catch (Exception ignored) {}
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.destroy();
            mWebView = null;
        }
        mCallback = null;
        LogUtil.i(TAG + ": destroyed");
    }

    private void executeParseAndPlay(final String url, final String ua) {
        final int gen = mParseGeneration;
        ParserManager.getInstance().parse(url, ua, new ChannelParser.ParseCallback() {
            @Override
            public void onSuccess(final ParseResult result) {
                if (gen != mParseGeneration) {
                    LogUtil.d(TAG + ": stale parse success discarding");
                    return;
                }
                LogUtil.i(TAG + ": parser success -> " + result.getVideoUrl());
                mParserRetryCount = 0;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mParseGeneration) return;
                        if (mDecodingMode != DECODING_MODE_IJK) return;
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
                    LogUtil.d(TAG + ": stale parse error discarding");
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
        if (mDecodingMode != DECODING_MODE_IJK) return;
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
            LogUtil.i(TAG + ": player error, direct replay in " + delay + "ms attempt="
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