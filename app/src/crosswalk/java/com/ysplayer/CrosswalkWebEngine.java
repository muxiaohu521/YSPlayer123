package com.ysplayer;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;

import org.xwalk.core.XWalkPreferences;
import org.xwalk.core.XWalkResourceClient;
import org.xwalk.core.XWalkSettings;
import org.xwalk.core.XWalkUIClient;
import org.xwalk.core.XWalkView;

import com.ysplayer.parser.CctvParser;
import com.ysplayer.parser.ChannelParser;
import com.ysplayer.parser.ParserManager;
import com.ysplayer.parser.ParseResult;
import com.ysplayer.parser.YangshipinParser;

import java.net.URLDecoder;

public class CrosswalkWebEngine implements WebEngine {

    private static final String TAG = "CrosswalkWebEngine";

    private final Context mContext;
    private ViewGroup mContainer;
    private Callback mCallback;
    private int mUaMode;

    private XWalkView mWebView;
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

    public CrosswalkWebEngine(Context context) {
        this.mContext = context.getApplicationContext();
    }

    @Override
    public void setCallback(Callback callback) {
        mCallback = callback;
        if (mHardMgr != null) mHardMgr.setCallback(callback);
    }

    @Override
    public void init(ViewGroup container) {
        mContainer = container;

        mHardMgr = new HardWebViewManager(container);
        mHardMgr.setCallback(mCallback);

        try {
            XWalkPreferences.setValue(XWalkPreferences.REMOTE_DEBUGGING, BuildConfig.LOG_ENABLED);

            Activity activity = null;
            if (mContainer.getContext() instanceof Activity) {
                activity = (Activity) mContainer.getContext();
            }

            mWebView = new XWalkView(mContainer.getContext(), activity);
            configureWebView(mWebView);
            mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            if (mDecodingMode == DECODING_MODE_IJK) {
                ensureIjkPlayer();
            }

            if (mPendingUrl != null) {
                mWebView.loadUrl(mPendingUrl);
                mPendingUrl = null;
            }
        } catch (Exception e) {
            LogUtil.e(TAG + ": init failed: " + e.getMessage());
            if (mCallback != null) {
                mCallback.onLoadError("Crosswalk init failed: " + e.getMessage());
            }
        }

        LogUtil.i(TAG + ": init done, version=" + getXWalkVersion()
                + " mode=" + mDecodingMode);
    }

    private void configureWebView(XWalkView wv) {
        XWalkSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(false);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(org.xwalk.core.XWalkSettings.LOAD_DEFAULT);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setAllowFileAccess(true);
        ws.setLoadsImagesAutomatically(true);
        ws.setBlockNetworkImage(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportMultipleWindows(false);
        ws.setBuiltInZoomControls(false);
        ws.setSupportZoom(false);

        wv.setFocusable(false);

        wv.setResourceClient(new XWalkResourceClient(wv) {
            @Override
            public boolean shouldOverrideUrlLoading(XWalkView view, String url) {
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
                        mIjkActive = true;
                        muteCrosswalkVideo();
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
            public void onReceivedSslError(XWalkView view,
                    ValueCallback<Boolean> callback, android.net.http.SslError error) {
                LogUtil.w(TAG + ": SSL error: " + error.getUrl()
                        + " code=" + error.getPrimaryError());
                if (mStopExpected) {
                    callback.onReceiveValue(false);
                    return;
                }
                callback.onReceiveValue(false);
                if (mCallback != null) {
                    mCallback.onLoadError("SSL证书错误: " + error.getUrl());
                }
            }

            @Override
            public void onReceivedLoadError(XWalkView view, int errorCode,
                    String description, String failingUrl) {
                LogUtil.e(TAG + ": load error: code=" + errorCode + " " + description);
                if (mCallback != null) {
                    mCallback.onLoadError(description);
                }
            }
        });

        wv.setUIClient(new XWalkUIClient(wv) {
            @Override
            public void onPageLoadStarted(XWalkView view, String url) {
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
            public void onPageLoadStopped(XWalkView view, String url, LoadStatus status) {
                if (status == LoadStatus.FINISHED) {
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
                } else if (status == LoadStatus.FAILED) {
                    if (mStopExpected) {
                        mStopExpected = false;
                        return;
                    }
                    LogUtil.e(TAG + ": page load failed: " + url);
                    if (mCallback != null) {
                        mCallback.onLoadError("Page load failed: " + url);
                    }
                }
            }

            @Override
            public void onFullscreenToggled(XWalkView view, boolean enterFullscreen) {
                super.onFullscreenToggled(view, enterFullscreen);
            }

            @Override
            public boolean onConsoleMessage(XWalkView view, String message,
                    int lineNumber, String sourceId, ConsoleMessageType messageType) {
                if (messageType == ConsoleMessageType.ERROR
                        || messageType == ConsoleMessageType.WARNING) {
                    LogUtil.i(TAG + ": [JS] " + messageType + " " + sourceId
                            + ":" + lineNumber + " " + message);
                }
                return super.onConsoleMessage(view, message, lineNumber, sourceId, messageType);
            }
        });

        applyUa(wv, false);
    }

    private void applyUa(XWalkView wv, boolean switching) {
        if (wv == null) return;
        if (mUaMode == 0) {
            if (switching) {
                wv.getSettings().setUserAgentString(null);
            }
            return;
        }
        UserAgentPreset preset = UserAgentPreset.fromId(mUaMode);
        wv.getSettings().setUserAgentString(preset.ua);
        LogUtil.i(TAG + ": UA -> " + preset.label);
    }

    private String getSoftModeJs(String url) {
        return "(function(){" +
                "var __ysCss=document.createElement('style');" +
                "__ysCss.textContent='" +
                "video:-webkit-full-screen,video:fullscreen{" +
                "object-fit:contain!important;" +
                "width:100%!important;height:100%!important;" +
                "position:fixed!important;top:0!important;left:0!important" +
                "}';" +
                "(document.head||document.documentElement).appendChild(__ysCss);" +
                "var __ysVs=[];window.__ysNativeFs=false;" +
                "function __ysUm(v){" +
                " if(window.__ysMuted)return;" +
                " try{if(v.muted)v.muted=false;}catch(e){}" +
                " try{if(v.volume===0)v.volume=1.0;}catch(e){}" +
                " v.removeAttribute('muted');" +
                "}" +
                "function __ysSt(el,k,v){" +
                " try{el.style.setProperty(k,v,'important');}catch(e){" +
                "  try{el.style.setProperty(k,v);}catch(e2){}}" +
                "}" +
                "function __ysOv(){" +
                " if(!document.getElementById('__ysOverlay')){" +
                "  var ov=document.createElement('div');" +
                "  ov.id='__ysOverlay';" +
                "  ov.style.cssText='position:fixed;top:0;left:0;width:100vw;height:100vh;z-index:2147483646;background:#000;';" +
                "  (document.body||document.documentElement).appendChild(ov);" +
                " }" +
                "}" +
                "function __ysCsFs(v){" +
                " __ysOv();" +
                " __ysSt(v,'position','fixed');__ysSt(v,'top','0');__ysSt(v,'left','0');" +
                " __ysSt(v,'width','100vw');__ysSt(v,'height','100vh');" +
                " __ysSt(v,'z-index','2147483647');__ysSt(v,'object-fit','contain');" +
                " __ysSt(v,'background','#000');__ysSt(v,'margin','0');__ysSt(v,'padding','0');" +
                "}" +
                "function __ysAfs(el){" +
                " try{" +
                "  var f=el.requestFullscreen||el.webkitRequestFullscreen;" +
                "  if(f){f.call(el).then(function(){window.__ysNativeFs=true;}).catch(function(){});}" +
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
                " v.addEventListener('play',function(){__ysUm(v);setTimeout(function(){__ysFs(v);},400);});" +
                " v.addEventListener('playing',function(){__ysUm(v);setTimeout(function(){__ysFs(v);},600);});" +
                " v.addEventListener('loadedmetadata',function(){__ysUm(v);try{v.play().catch(function(){})}catch(e){}" +
                "  setTimeout(function(){__ysFs(v);},800);});" +
                " v.addEventListener('canplay',function(){setTimeout(function(){__ysFs(v);},500);});" +
                " v.addEventListener('volumechange',function(){if(v.muted||v.volume===0)__ysUm(v);});" +
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
                "__ysSc(document);" +
                "[500,1500,3000,6000,12000,25000].forEach(function(d){" +
                " setTimeout(function(){" +
                "  __ysSc(document);" +
                "  var ifs=document.querySelectorAll('iframe');" +
                "  for(var i=0;i<ifs.length;i++){" +
                "   try{var dd=ifs[i].contentDocument;if(dd)__ysSc(dd);}catch(e){}" +
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

    private void ensureIjkPlayer() {
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
                            LogUtil.e(TAG + ": IjkPlayer error: " + message);
                            retryPlayFromParserUrl();
                        }
                        @Override
                        public void onPlayerStateChanged(int state, String info) {
                            if (state == IjkVideoPlayer.STATE_READY) {
                                mPlayerRetryCount = 0;
                            } else if (state == IjkVideoPlayer.STATE_ENDED) {
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

    private void setupIjkBridgeJs(XWalkView view) {
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
    }

    private void hideIjkPlayer() {
        mIjkActive = false;
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

    @Override
    public void loadUrl(String url) {
        cancelPendingRetry();
        ++mParseGeneration;

        if (mDecodingMode == DECODING_MODE_IJK
                && IjkMediaHelper.isDirectMediaUrl(url)) {
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
        } else {
            LogUtil.w(TAG + ": XWalkView not ready, url queued: " + url);
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
            mWebView.reload(0);
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
        return "crosswalk";
    }

    @Override
    public void setDecodingMode(int mode) {
        if (mode == mDecodingMode) return;
        int prevMode = mDecodingMode;

        cancelPendingRetry();
        ++mParseGeneration;

        releaseIjkPlayer();
        if (mHardActive) mHardMgr.stopLoading();
        mHardMgr.setVisible(false);
        if (mHardActive) mHardMgr.loadUrl("about:blank");
        mHardActive = false;

        mDecodingMode = mode;
        LogUtil.i(TAG + ": decoding mode: " + prevMode + " -> " + mode);

        switch (mode) {
            case DECODING_MODE_HARD:
                hideCrosswalkView();
                mHardMgr.create();
                mHardMgr.setVisible(true);
                mHardActive = true;
                break;
            case DECODING_MODE_IJK:
                hideCrosswalkView();
                ensureIjkPlayer();
                break;
            case DECODING_MODE_SOFT:
            default:
                showCrosswalkView();
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

    private void hideCrosswalkView() {
        if (mWebView == null) return;
        LogUtil.i(TAG + ": hiding Crosswalk XWalkView (mode switch)");
        mStopExpected = true;
        mWebView.stopLoading();
        mWebView.loadUrl("about:blank");
        try {
            mWebView.getNavigationHistory().clear();
        } catch (Exception ignored) {}
        mWebView.setVisibility(View.INVISIBLE);
        muteCrosswalkVideo();
    }

    private void showCrosswalkView() {
        if (mWebView == null) {
            LogUtil.i(TAG + ": creating Crosswalk XWalkView for SOFT mode");
            try {
                Activity activity = null;
                if (mContainer.getContext() instanceof Activity) {
                    activity = (Activity) mContainer.getContext();
                }
                mWebView = new XWalkView(mContainer.getContext(), activity);
                configureWebView(mWebView);
                mContainer.addView(mWebView, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                LogUtil.e(TAG + ": failed to create Crosswalk XWalkView: " + e.getMessage());
                return;
            }
        }
        mWebView.setVisibility(View.VISIBLE);
        mWebView.evaluateJavascript(getSoftModeJs(null), null);
        unmuteCrosswalkVideo();
    }

    private void muteCrosswalkVideo() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(
                "(function(){" +
                "window.__ysMuted=true;" +
                "var vs=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<vs.length;i++){vs[i].muted=true;vs[i].volume=0;}" +
                "})();",
                null);
        }
    }

    private void unmuteCrosswalkVideo() {
        if (mWebView != null) {
            mWebView.evaluateJavascript(
                "(function(){" +
                "window.__ysMuted=false;" +
                "var vs=document.querySelectorAll('video,audio');" +
                "for(var i=0;i<vs.length;i++){try{vs[i].muted=false;vs[i].volume=1.0;vs[i].removeAttribute('muted');}catch(e){}}" +
                "})();",
                null);
        }
    }

    @Override
    public int[] getSupportedDecodingModes() {
        return new int[]{DECODING_MODE_HARD, DECODING_MODE_SOFT, DECODING_MODE_IJK};
    }

    @Override
    public String getDecodingModeLabel(int mode) {
        switch (mode) {
            case DECODING_MODE_HARD: return "HARD (MediaCodec)";
            case DECODING_MODE_SOFT: return "SOFT (Crosswalk)";
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
            mWebView.onShow();
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
            mWebView.onHide();
        }
    }

    @Override
    public void onDestroy() {
        cancelPendingRetry();
        mMainHandler.removeCallbacksAndMessages(null);
        if (mHardMgr != null) {
            mHardMgr.destroy();
        }
        if (mIjkBridge != null) {
            mIjkBridge.release();
            mIjkBridge = null;
        }
        if (mIjkPlayer != null) {
            mIjkPlayer.release();
            mIjkPlayer = null;
        }
        if (mWebView != null) {
            mWebView.stopLoading();
            mWebView.loadUrl("about:blank");
            try {
                mWebView.getNavigationHistory().clear();
            } catch (Exception ignored) {}
            ViewGroup parent = (ViewGroup) mWebView.getParent();
            if (parent != null) {
                parent.removeView(mWebView);
            }
            mWebView.onDestroy();
            mWebView = null;
        }
        mCallback = null;
        LogUtil.i(TAG + ": destroyed");
    }

    private void executeParseAndPlay(final String url, final String ua) {
        if (mDecodingMode != DECODING_MODE_IJK) return;
        final int gen = mParseGeneration;
        ParserManager.getInstance().parse(url, ua, new ChannelParser.ParseCallback() {
            @Override
            public void onSuccess(final ParseResult result) {
                if (gen != mParseGeneration) {
                    LogUtil.d(TAG + ": stale parse success discarding");
                    return;
                }
                mParserRetryCount = 0;
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != mParseGeneration) return;
                        mIjkActive = true;
                        if (mIjkPlayer != null) {
                            String finalUrl = CctvParser.convertCdrmToHls(
                                    result.getVideoUrl());
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
                LogUtil.e(TAG + ": parser error: " + message);
                mParserRetryCount++;
                if (mParserRetryCount <= MAX_RETRIES) {
                    long delay = RETRY_BASE_DELAY_MS * mParserRetryCount;
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
                        mCallback.onLoadError(message + " (retry exhausted)");
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
            return;
        }
        long delay = RETRY_BASE_DELAY_MS * mPlayerRetryCount;
        if (mPendingUrl == null || mPendingUa == null) {
            return;
        }
        final String url = mPendingUrl;
        final String ua = mPendingUa;
        if (IjkMediaHelper.isDirectMediaUrl(url)) {
            mPendingRetryRunnable = new Runnable() {
                @Override
                public void run() {
                    mPendingRetryRunnable = null;
                    if (mDecodingMode != DECODING_MODE_IJK) {
                        return;
                    }
                    if (mIjkPlayer != null) {
                        mIjkPlayer.setUserAgent(ua);
                        mIjkPlayer.playUrl(CctvParser.convertCdrmToHls(url));
                    }
                }
            };
            mMainHandler.postDelayed(mPendingRetryRunnable, delay);
            return;
        }
        mPendingRetryRunnable = new Runnable() {
            @Override
            public void run() {
                mPendingRetryRunnable = null;
                if (mDecodingMode != DECODING_MODE_IJK) {
                    return;
                }
                YangshipinParser.invalidatePlayUrlCache(url);
                executeParseAndPlay(url, ua);
            }
        };
        mMainHandler.postDelayed(mPendingRetryRunnable, delay);
    }

    private String getXWalkVersion() {
        try {
            return mWebView.getXWalkVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }
}