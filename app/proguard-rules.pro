# Add project specific ProGuard rules here.

# Crosswalk (Chromium M53) - keep all classes to prevent R8 from stripping
# native JNI bridge classes and internal service declarations
-dontwarn org.xwalk.core.**
-keep class org.xwalk.core.** { *; }
-keep class org.xwalk.core.internal.** { *; }
-keep class org.chromium.** { *; }

# GeckoView - keep all classes to prevent R8 from stripping internal
# Service/Provider classes referenced in AndroidManifest.xml
# (e.g. MediaManager, GeckoServiceChildProcess, SurfaceAllocatorService)
-dontwarn org.mozilla.geckoview.**
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }

# SnakeYAML (bundled in GeckoView) references java.beans.* which
# does not exist on Android — suppress warnings to avoid R8 errors
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.**

# Keep all native methods (critical for GeckoView's libxul.so etc.)
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep BuildConfig for runtime checks
-keep class com.ysplayer.BuildConfig { *; }

# Keep WebEngine implementations
-keep class com.ysplayer.GeckoWebEngine { *; }
-keep class com.ysplayer.SystemWebEngine { *; }
-keep class com.ysplayer.CrosswalkWebEngine { *; }
-keep class com.ysplayer.X5WebEngine { *; }

# IJK native JNI classes — critical: R8 must NOT rename/remove these,
# otherwise JNI method signatures (e.g. _setDataSource(IMediaDataSource)V)
# will fail to register and cause SIGABRT in FFmpegApi_global_init
-keep class tv.danmaku.ijk.media.player.** { *; }
-dontwarn tv.danmaku.ijk.media.player.**

# IJK shared player (JavascriptInterface + reflection)
-keep class com.ysplayer.IjkVideoPlayer { *; }
-keep class com.ysplayer.IjkVideoPlayer$Callback { *; }
-keep class com.ysplayer.IjkPlayerBridge { *; }
-keepclassmembers class com.ysplayer.IjkPlayerBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.ysplayer.IjkMediaHelper { *; }

# Keep HARD mode shared manager
-keep class com.ysplayer.HardWebViewManager { *; }

# Keep WebEngine interface + UserAgentPreset + LogUtil
-keep interface com.ysplayer.WebEngine { *; }
-keep interface com.ysplayer.WebEngine$Callback { *; }
-keep class com.ysplayer.UserAgentPreset { *; }
-keep class com.ysplayer.LogUtil { *; }

# Keep EngineFactory
-keep class com.ysplayer.EngineFactory { *; }

# Keep data models
-keep class com.ysplayer.bean.** { *; }

# AndroidX
-keep class androidx.appcompat.** { *; }
-dontwarn androidx.**

# RecyclerView
-keep class androidx.recyclerview.** { *; }