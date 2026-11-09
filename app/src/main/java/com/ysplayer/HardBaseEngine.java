package com.ysplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 纯 HARD 解码模式 WebView 管理器 — 所有内核通用的公共基础类。
 * 仅管理 System WebView 的创建、配置、全屏、可见性、销毁。
 * 不含任何 IJK parser/player 逻辑。
 */
public class HardBaseEngine implements WebEngine {

    protected static final String TAG = "HardBase";

    protected final ViewGroup mContainer;
    protected final Handler mMainHandler = new Handler(Looper.getMainLooper());
    protected WebView mWebView;
    protected Callback mCallback;
    protected int mUaMode;
    protected View mFullscreenView;
    protected WebChromeClient.CustomViewCallback mFullscreenCallback;
    protected boolean mSuppressCallbacks;

    private boolean mUseZOrderFix;
    private static final int WEVBVIEW_THRESHOLD = 80;

    private static int getWebViewMajorVersion(Context ctx) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.content.pm.PackageInfo pi = WebView.getCurrentWebViewPackage();
                if (pi != null && pi.versionName != null) return parseMajor(pi.versionName);
            }
        } catch (Exception ignored) {}
        for (String pkg : new String[]{"com.google.android.webview", "com.android.webview"}) {
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                if (pi != null && pi.versionName != null) return parseMajor(pi.versionName);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    private static int parseMajor(String v) {
        int dot = v.indexOf('.');
        try { return Integer.parseInt(dot > 0 ? v.substring(0, dot) : v); }
        catch (NumberFormatException e) { return 0; }
    }

    public HardBaseEngine(ViewGroup container) {
        this.mContainer = container;
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void init(ViewGroup container) {
        LogUtil.i(TAG + ": [HARD_TRACE] init start: container=" + (container != null)
                + " containerChildCount=" + (container != null ? container.getChildCount() : -1)
                + " sdkInt=" + android.os.Build.VERSION.SDK_INT
                + " manufacturer=" + android.os.Build.MANUFACTURER
                + " model=" + android.os.Build.MODEL);
        try {
            mWebView = new WebView(mContainer.getContext());
            LogUtil.i(TAG + ": [HARD_TRACE] init: WebView created, class="
                    + (mWebView != null ? mWebView.getClass().getName() : "null")
                    + " hashCode=" + (mWebView != null ? Integer.toHexString(mWebView.hashCode()) : "N/A"));
            int wvVer = getWebViewMajorVersion(mContainer.getContext());
            mUseZOrderFix = wvVer > 0 && wvVer <= WEVBVIEW_THRESHOLD;
            LogUtil.i(TAG + ": [HARD_TRACE] init: WebView major=" + wvVer
                    + " threshold=" + WEVBVIEW_THRESHOLD
                    + " useZOrderFix=" + mUseZOrderFix);
            configureWebView();
            mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            LogUtil.i(TAG + ": [HARD_TRACE] init: WebView added to container,"
                    + " childCount=" + mContainer.getChildCount()
                    + " visibility=" + mWebView.getVisibility());
            LogUtil.i(TAG + ": [HARD_TRACE] init: DONE");
        } catch (Exception e) {
            LogUtil.e(TAG + ": [HARD_TRACE] init FAILED: " + e.getMessage());
        }
    }

    public void setVisible(boolean visible) {
        LogUtil.i(TAG + ": [HARD_TRACE] setVisible(" + visible + "): mWebView=" + (mWebView != null)
                + " fullscreenActive=" + (mFullscreenView != null)
                + " currentVisibility=" + (mWebView != null ? mWebView.getVisibility() : -1));
        if (mWebView != null) {
            mSuppressCallbacks = !visible;
            mWebView.setVisibility(visible ? View.VISIBLE : View.GONE);
            LogUtil.i(TAG + ": [HARD_TRACE] setVisible: newVisibility=" + mWebView.getVisibility()
                    + " suppressCallbacks=" + mSuppressCallbacks);
        }
    }

    public boolean isFullscreenActive() {
        boolean result = mFullscreenView != null;
        LogUtil.d(TAG + ": [HARD_TRACE] isFullscreenActive() = " + result);
        return result;
    }

    protected WebChromeClient createWebChromeClient() {
        return new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (mFullscreenView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                if (mUseZOrderFix) {
                    callback.onCustomViewHidden();
                    LogUtil.i(TAG + ": [HARD_TRACE] onShowCustomView: zOrderFix, dismissed native fullscreen, CSS fallback will handle");
                    return;
                }
                mFullscreenView = view;
                mFullscreenCallback = callback;
                mContainer.addView(mFullscreenView, new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }

            @Override
            public void onHideCustomView() {
                if (mFullscreenView == null) return;
                mContainer.removeView(mFullscreenView);
                if (mFullscreenCallback != null) {
                    mFullscreenCallback.onCustomViewHidden();
                }
                mFullscreenView = null;
                mFullscreenCallback = null;
            }
        };
    }

    @SuppressLint("SetJavaScriptEnabled")
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
                LogUtil.i(TAG + ": [HARD_TRACE] onPageStarted: url=" + url
                        + " webViewVisibility=" + view.getVisibility()
                        + " useZOrderFix=" + mUseZOrderFix
                        + " fullscreenActive=" + (mFullscreenView != null)
                        + " suppressCallbacks=" + mSuppressCallbacks);
                if (mSuppressCallbacks) return;
                if ("about:blank".equals(url)) return;
                if (mCallback != null) {
                    mCallback.onPageStarted();
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                LogUtil.i(TAG + ": [HARD_TRACE] onPageFinished: url=" + url
                        + " webViewVisibility=" + view.getVisibility()
                        + " fullscreenActive=" + (mFullscreenView != null)
                        + " suppressCallbacks=" + mSuppressCallbacks);
                if (mSuppressCallbacks) return;
                if ("about:blank".equals(url)) return;
                injectVideoEnhancement(url);
                if (mCallback != null) {
                    mCallback.onPageFinished();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
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

    protected void applyUa() {
        if (mWebView != null) {
            if (mUaMode == 0) {
                mWebView.getSettings().setUserAgentString(null);
                LogUtil.i(TAG + ": UA -> System Native");
                return;
            }
            UserAgentPreset preset = UserAgentPreset.fromId(mUaMode);
            mWebView.getSettings().setUserAgentString(preset.ua);
            LogUtil.i(TAG + ": UA -> " + preset.label);
        }
    }

    // ---- WebEngine public interface ----

    @Override
    public void loadUrl(String url) {
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.clearHistory();
            LogUtil.i(TAG + ": loading " + url);
            mWebView.loadUrl(url);
            if (mCallback != null && !"about:blank".equals(url)) {
                mCallback.onPageStarted();
            }
        }
    }

    @Override
    public void stopLoading() {
        if (mWebView != null) {
            mWebView.stopLoading();
        }
    }

    @Override
    public void setUaMode(int mode) {
        mUaMode = mode;
        applyUa();
    }

    @Override
    public String getEngineType() {
        return "hardbase";
    }

    @Override
    public void setDecodingMode(int mode) {
        LogUtil.d(TAG + ": setDecodingMode(" + mode + "): HardBaseEngine only supports HARD, ignoring");
    }

    @Override
    public int[] getSupportedDecodingModes() {
        return new int[]{DECODING_MODE_HARD};
    }

    @Override
    public String getDecodingModeLabel(int mode) {
        if (mode == DECODING_MODE_HARD) return "硬解 (MediaCodec)";
        return "未知";
    }

    @Override
    public void reload() {
        if (mWebView != null) {
            mWebView.reload();
        }
    }

    @Override
    public void onResume() {
        LogUtil.i(TAG + ": [HARD_TRACE] onResume: mWebView=" + (mWebView != null)
                + " fullscreenActive=" + (mFullscreenView != null));
        if (mWebView != null) {
            mWebView.onResume();
            mWebView.resumeTimers();
            LogUtil.i(TAG + ": [HARD_TRACE] onResume: mWebView.onResume() + resumeTimers() called");
        }
    }

    @Override
    public void onPause() {
        LogUtil.i(TAG + ": [HARD_TRACE] onPause: mWebView=" + (mWebView != null)
                + " fullscreenActive=" + (mFullscreenView != null));
        if (mWebView != null) {
            mWebView.onPause();
            mWebView.pauseTimers();
        }
    }

    @Override
    public void onDestroy() {
        mMainHandler.removeCallbacksAndMessages(null);
        if (mFullscreenView != null) {
            mContainer.removeView(mFullscreenView);
            mFullscreenView = null;
        }
        mFullscreenCallback = null;
        if (mWebView != null) {
            mWebView.loadUrl("about:blank");
            mWebView.clearHistory();
            mContainer.removeView(mWebView);
            mWebView.destroy();
            mWebView = null;
        }
        mCallback = null;
        LogUtil.i(TAG + ": destroyed");
    }

    // ---- HARD mode: video enhancement JS ----

    protected void injectVideoEnhancement(String url) {
        LogUtil.i(TAG + ": [HARD_TRACE] injectVideoEnhancement: url="
                + (url != null ? url.substring(0, Math.min(80, url.length())) + "..." : "null")
                + " mWebView=" + (mWebView != null)
                + " fullscreenActive=" + (mFullscreenView != null));
        if (mWebView == null) return;

        final boolean isSpecial = url != null && (
                url.contains("live.jstv.com") ||
                url.contains("live.snrtv.com") ||
                url.contains("dbw.cn") ||
                url.contains("jxntv.cn"));

        String script;
        if (isSpecial) {
            script = buildSpecialScript();
        } else {
            script = buildGeneralScript();
        }
        mWebView.evaluateJavascript(script, null);
    }

    private String buildGeneralScript() {
        return
            "(function(){" +
            "var __ysVs=[];window.__ysNativeFs=false;" +
            "function __ysUm(v){" +
            " try{if(v.muted)v.muted=false;}catch(e){}" +
            " try{if(v.volume===0)v.volume=1.0;}catch(e){}" +
            " v.setAttribute('muted','false');" +
            "}" +
            "function __ysSt(el,k,v){" +
            " try{el.style.setProperty(k,v,'important');}catch(e){" +
            "  try{el.style.setProperty(k,v);}catch(e2){}}" +
            "}" +
            "function __ysCsFs(v){" +
            " if(!document.getElementById('__ysOverlay')){" +
            "  var ov=document.createElement('div');" +
            "  ov.id='__ysOverlay';" +
            "  ov.style.cssText='position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:2147483646;background:#000;';" +
            "  (document.body||document.documentElement).appendChild(ov);" +
            " }" +
            " __ysSt(v,'position','fixed');__ysSt(v,'top','0');__ysSt(v,'left','0');" +
            " __ysSt(v,'width','100vw');__ysSt(v,'height','100vh');" +
            " __ysSt(v,'z-index','2147483647');__ysSt(v,'object-fit','contain');" +
            " __ysSt(v,'background','#000');__ysSt(v,'margin','0');" +
            " __ysSt(v,'padding','0');__ysSt(v,'border','none');" +
            " if(v.parentNode!==document.body){" +
            "  try{document.body.appendChild(v);}catch(e){}" +
            "  try{v.play();}catch(e){}" +
            " }" +
            "}" +
            "function __ysAfs(el){" +
            " try{" +
            "  var f=el.requestFullscreen||el.webkitRequestFullscreen;" +
            "  if(f)f.call(el).catch(function(){});" +
            " }catch(e){}" +
            "}" +
            "function __ysFs(v){" +
            " __ysUm(v);" +
            " try{v.webkitEnterFullscreen&&v.webkitEnterFullscreen();}catch(e){}" +
            " __ysAfs(v);" +
            " var p=v.parentElement;" +
            " while(p&&p!==document.body&&p!==document.documentElement){" +
            "  __ysAfs(p);p=p.parentElement;" +
            " }" +
            " setTimeout(function(){" +
            "  if(!document.fullscreenElement&&!document.webkitFullscreenElement&&!window.__ysNativeFs)" +
            "   __ysCsFs(v);" +
            " },1200);" +
            "}" +
            "function __ysEn(v){" +
            " if(!v||v.__ys_En)return;v.__ys_En=true;__ysVs.push(v);__ysUm(v);" +
            " v.setAttribute('playsinline','true');" +
            " v.addEventListener('play',function(){" +
            "  __ysUm(v);setTimeout(function(){__ysFs(v);},400);" +
            " });" +
            " v.addEventListener('playing',function(){" +
            "  __ysUm(v);setTimeout(function(){__ysFs(v);},600);" +
            " });" +
            " v.addEventListener('loadedmetadata',function(){" +
            "  __ysUm(v);try{v.play().catch(function(){})}catch(e){}" +
            "  setTimeout(function(){__ysFs(v);},800);" +
            " });" +
            " v.addEventListener('canplay',function(){" +
            "  setTimeout(function(){__ysFs(v);},500);" +
            " });" +
            " v.addEventListener('volumechange',function(){" +
            "  if(v.muted||v.volume===0)__ysUm(v);" +
            " });" +
            " try{v.play().catch(function(){})}catch(e){}" +
            "}" +
            "function __ysSc(r){" +
            " if(!r||!r.querySelectorAll)return;" +
            " var vs=r.querySelectorAll('video');" +
            " for(var i=0;i<vs.length;i++)__ysEn(vs[i]);" +
            " var all=r.querySelectorAll('*');" +
            " for(var j=0;j<all.length;j++)" +
            "  if(all[j].shadowRoot)__ysSc(all[j].shadowRoot);" +
            "}" +
            "function __ysClk(sels){" +
            " for(var i=0;i<sels.length;i++){" +
            "  try{var bs=document.querySelectorAll(sels[i]);" +
            "   for(var j=0;j<bs.length;j++){" +
            "    if(bs[j].offsetParent)try{bs[j].click();}catch(e){}" +
            "   }" +
            "  }catch(e){}" +
            " }" +
            "}" +
            "__ysSc(document);" +
            "[500,1500,3000,6000,12000,25000].forEach(function(d){" +
            " setTimeout(function(){" +
            "  __ysSc(document);" +
            "  if(__ysVs.length===0){" +
            "   __ysClk(['[class*=fullscreen]','[class*=full-screen]','[id*=fullscreen]'," +
            "    '[id*=full-screen]','.prism-fullscreen','.xgplayer-fullscreen'," +
            "    '.txp_btn_fullscreen','.dplayer-full-icon','.videoFull'," +
            "    '[title*=全屏]','[aria-label*=全屏]','button[class*=full]']);" +
            "   __ysClk(['[class*=play-btn]','[id*=play-btn]','.prism-play-btn'," +
            "    '.prism-big-play-btn','.xgplayer-play','.txp_btn_play'," +
            "    '.dplayer-play-icon','.vcp-play-btn','.vjs-big-play-button'," +
            "    '.big-play','[aria-label*=播放]','[title*=播放]']);" +
            "  }" +
            "  var ifs=document.querySelectorAll('iframe');" +
            "  for(var k=0;k<ifs.length;k++){" +
            "   try{var dd=ifs[k].contentDocument;if(dd)__ysSc(dd);}catch(e){}" +
            "  }" +
            " },d);" +
            "});" +
            "if(window.MutationObserver){" +
            " var o=new MutationObserver(function(ms){" +
            "  for(var i=0;i<ms.length;i++){" +
            "   var ns=ms[i].addedNodes;if(!ns)continue;" +
            "   for(var j=0;j<ns.length;j++)" +
            "    if(ns[j].nodeType===1)__ysSc(ns[j]);" +
            "  }" +
            " });" +
            " var t=document.body||document.documentElement;" +
            " if(t)o.observe(t,{childList:true,subtree:true});" +
            "}" +
            "setInterval(function(){" +
            " for(var i=0;i<__ysVs.length;i++){" +
            "  var v=__ysVs[i];if(!v||!document.contains(v))continue;" +
            "  __ysUm(v);" +
            "  if(!document.fullscreenElement&&!document.webkitFullscreenElement" +
            "     &&!window.__ysNativeFs&&(v.paused===false||v.currentTime>0.1))" +
            "   __ysCsFs(v);" +
            " }" +
            "},3000);" +
            "document.addEventListener('webkitfullscreenchange',function(){" +
            " if(document.webkitFullscreenElement||document.fullscreenElement){" +
            "  var ov=document.getElementById('__ysOverlay');" +
            "  if(ov&&ov.parentNode)ov.parentNode.removeChild(ov);" +
            " }" +
            "});" +
            "document.addEventListener('fullscreenchange',function(){" +
            " if(document.webkitFullscreenElement||document.fullscreenElement){" +
            "  var ov=document.getElementById('__ysOverlay');" +
            "  if(ov&&ov.parentNode)ov.parentNode.removeChild(ov);" +
            " }" +
            "});" +
            "})();";
    }

    private String buildSpecialScript() {
        return
            "(function(){" +
            "var HOST=window.location.hostname||'';" +
            "var IS_JSTV=/jstv\\.com/.test(HOST);" +
            "var IS_SN=/snrtv\\.com/.test(HOST);" +
            "var IS_DBW=/dbw\\.cn/.test(HOST);" +
            "var IS_JX=/jxntv\\.cn/.test(HOST);" +
            "var __trackedIframe=null;" +
            "var __ov=null;" +
            "function __css(el,k,v){" +
            " try{el.style.setProperty(k,v,'important');}catch(e){try{el.style.setProperty(k,v);}catch(e2){}}" +
            "}" +
            "function __ensureOverlay(){" +
            " if(__ov&&document.contains(__ov))return;" +
            " __ov=document.createElement('div');" +
            " __ov.id='__ysOverlay';" +
            " __ov.style.cssText='position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:2147483646;background:#000;';" +
            " (document.body||document.documentElement).appendChild(__ov);" +
            "}" +
            "function __fs(el){" +
            " if(!el||!document.contains(el))return;" +
            " __ensureOverlay();" +
            " __css(el,'position','fixed');__css(el,'top','0');__css(el,'left','0');" +
            " __css(el,'width','100vw');__css(el,'height','100vh');" +
            " __css(el,'z-index','2147483647');__css(el,'background','#000');" +
            " __css(el,'margin','0');__css(el,'padding','0');__css(el,'border','none');" +
            "}" +
            "function __findPlayerIframe(){" +
            " var ifs=document.querySelectorAll('iframe');" +
            " var best=null,ba=0;" +
            " for(var i=0;i<ifs.length;i++){" +
            "  var r=ifs[i].getBoundingClientRect(),a=r.width*r.height;" +
            "  if(a>30000&&a>ba){best=ifs[i];ba=a;}" +
            " }" +
            " return best;" +
            "}" +
            "function __tryNativeFs(){" +
            " if(__trackedIframe&&document.contains(__trackedIframe)){" +
            "  try{var f=__trackedIframe.requestFullscreen||__trackedIframe.webkitRequestFullscreen;" +
            "   if(f){f.call(__trackedIframe).then(function(){window.__ysNativeFs=true;}).catch(function(){});}" +
            "  }catch(e){}" +
            " }" +
            "}" +
            "function __clk(sel){" +
            " try{var el=document.querySelector(sel);if(el&&el.offsetParent)el.click();}catch(e){}" +
            "}" +
            "function __clickChannelBtns(){" +
            " if(IS_JSTV){__clk('.live-controls-fullscreen');__clk('.jstv-fullscreen-btn');__clk('.control-bar-fullscreen');}" +
            " if(IS_SN){__clk('.sn-fullscreen');__clk('.player-control-fullscreen');}" +
            " if(IS_DBW){__clk('.dbw-full');__clk('.fullscreen-button');__clk('.zhibo-fullscreen');}" +
            " if(IS_JX){__clk('.jx-fullscreen');__clk('.live-fullscreen');}" +
            " __clk('.fullscreen');__clk('.full-screen');__clk('[class*=fullscreen]');__clk('[id*=fullscreen]');" +
            " __clk('[title*=全屏]');__clk('[aria-label*=全屏]');" +
            "}" +
            "function __init(){" +
            " var bf=__findPlayerIframe();" +
            " if(bf){__trackedIframe=bf;__fs(bf);__tryNativeFs();}" +
            "}" +
            "__init();" +
            "__clickChannelBtns();" +
            "[1000,3000,6000,15000].forEach(function(d){" +
            " setTimeout(function(){" +
            "  __init();__clickChannelBtns();" +
            " },d);" +
            "});" +
            "var __mutPending=false;" +
            "if(window.MutationObserver){" +
            " var obs=new MutationObserver(function(ms){" +
            "  if(__mutPending)return;__mutPending=true;" +
            "  setTimeout(function(){" +
            "   __mutPending=false;" +
            "   for(var i=0;i<ms.length;i++){" +
            "    var ns=ms[i].addedNodes;if(!ns)continue;" +
            "    for(var j=0;j<ns.length;j++){" +
            "     if(ns[j].nodeType===1&&ns[j].tagName==='IFRAME'){" +
            "      var r=ns[j].getBoundingClientRect();" +
            "      if(r.width*r.height>30000){__trackedIframe=ns[j];__fs(ns[j]);__tryNativeFs();}" +
            "     }" +
            "    }" +
            "   }" +
            "  },500);" +
            " });" +
            " var t=document.body||document.documentElement;" +
            " if(t)obs.observe(t,{childList:true,subtree:true});" +
            "}" +
            "setInterval(function(){" +
            " if(__trackedIframe&&document.contains(__trackedIframe)){" +
            "  if(!document.fullscreenElement&&!document.webkitFullscreenElement){" +
            "   try{__fs(__trackedIframe);}catch(e){__trackedIframe=null;}" +
            "  }" +
            " }else if(__trackedIframe){" +
            "  __trackedIframe=null;" +
            " }" +
            " if(!__trackedIframe)__init();" +
            "},3000);" +
            "function __onNativeFs(){" +
            " window.__ysNativeFs=true;" +
            " if(__ov&&__ov.parentNode)__ov.parentNode.removeChild(__ov);" +
            "}" +
            "document.addEventListener('webkitfullscreenchange',__onNativeFs);" +
            "document.addEventListener('fullscreenchange',__onNativeFs);" +
            "})();";
    }

    protected void logViewHierarchy(View view, String prefix) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (view != null && depth < 10) {
            sb.append("\n")
              .append(prefix)
              .append("depth=").append(depth)
              .append(" class=").append(view.getClass().getName())
              .append(" hash=").append(Integer.toHexString(view.hashCode()))
              .append(" visibility=").append(view.getVisibility())
              .append(" width=").append(view.getWidth())
              .append(" height=").append(view.getHeight());
            if (view instanceof android.view.SurfaceView) {
                try {
                    java.lang.reflect.Method m = android.view.SurfaceView.class.getMethod("isZOrderMediaOverlay");
                    sb.append(" zOrderMediaOverlay=").append(m.invoke(view));
                } catch (Exception ignored) {}
                try {
                    java.lang.reflect.Method m = android.view.SurfaceView.class.getMethod("isZOrderOnTop");
                    sb.append(" zOrderOnTop=").append(m.invoke(view));
                } catch (Exception ignored) {}
            }
            if (view instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) view;
                sb.append(" childCount=").append(vg.getChildCount());
            }
            sb.append(" isHardwareAccelerated=").append(view.isHardwareAccelerated());
            if (view.getParent() instanceof View) {
                view = (View) view.getParent();
                depth++;
            } else {
                break;
            }
        }
        LogUtil.i(TAG + ": " + sb.toString());
    }
}