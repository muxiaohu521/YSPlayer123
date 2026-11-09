package com.ysplayer;

import android.app.Application;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.multidex.MultiDex;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

public class App extends Application {

    private static App sInstance;
    private HandlerThread mBgThread;
    private Handler mBgHandler;
    private static final long T0 = System.nanoTime();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;

        if (isMainProcess()) {
            LogUtil.init();
        }

        String proc = resolveProcessName();
        LogUtil.i("APP[" + elapsed() + "] [pid=" + android.os.Process.myPid()
                + " tid=" + android.os.Process.myTid()
                + " proc=" + proc + "] === APP CREATED ===");
        LogUtil.i("APP[" + elapsed() + "] isMainProcess=" + isMainProcess()
                + " nativeHeap=" + Debug.getNativeHeapAllocatedSize());
        LogUtil.i("APP[" + elapsed() + "] YSPlayer v" + BuildConfig.VERSION_NAME
                + " engine=" + BuildConfig.ENGINE_TYPE
                + " arch=" + BuildConfig.CPU_ARCH
                + " buildTime=" + BuildConfig.BUILD_TIME);

        dumpNativeLibs();

        mBgThread = new HandlerThread("YSPlayer-GLES-Detect");
        mBgThread.start();
        mBgHandler = new Handler(mBgThread.getLooper());

        mBgHandler.post(this::performCapabilityCheck);

        if ("x5".equals(BuildConfig.ENGINE_TYPE)) {
            try {
                Class<?> clz = Class.forName("com.ysplayer.X5CoreInitializer");
                clz.getMethod("init", Application.class).invoke(null, this);
            } catch (Throwable e) {
                LogUtil.e("APP[" + elapsed() + "] X5CoreInitializer not available: " + e.getMessage());
            }
        }

        // Uncaught exception handler
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LogUtil.e("APP[" + elapsed() + "] *** UNCAUGHT EXCEPTION *** thread=" + t.getName()
                    + " pid=" + android.os.Process.myPid()
                    + " msg=" + e.getMessage(), e);
            LogUtil.flush();
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });

        // Shutdown hook to catch native kill
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LogUtil.e("APP[" + elapsed() + "] *** SHUTDOWN HOOK *** pid=" + android.os.Process.myPid()
                    + " proc=" + resolveProcessName());
            dumpNativeLibsStatic();
            LogUtil.flush();
        }, "CrashHook"));

        // Heartbeat thread - logs every 3 seconds to track crash timing
        new Thread(() -> {
            int count = 0;
            while (true) {
                try {
                    Thread.sleep(3000);
                    count++;
                    LogUtil.i("HB[" + elapsed() + "] heartbeat #" + count
                            + " pid=" + android.os.Process.myPid()
                            + " proc=" + resolveProcessName()
                            + " nativeHeap=" + Debug.getNativeHeapAllocatedSize()
                            + " threads=" + Thread.activeCount());
                    LogUtil.flush(); // force flush every heartbeat
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "Heartbeat").start();
    }

    private static String elapsed() {
        return String.format("%.3fs", (System.nanoTime() - T0) / 1_000_000_000.0);
    }

    private void dumpNativeLibs() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/self/maps"));
            String line;
            StringBuilder sb = new StringBuilder("APP[" + elapsed() + "] LOADED_LIBS:");
            while ((line = r.readLine()) != null) {
                if (line.contains(".so")) {
                    int idx = line.lastIndexOf('/');
                    if (idx >= 0) {
                        String name = line.substring(idx + 1);
                        if (name.contains(".")) {
                            int dot = name.indexOf('.');
                            sb.append(" ").append(name.substring(0, dot > 0 ? dot : name.length()));
                        }
                    }
                }
            }
            r.close();
            LogUtil.i(sb.toString());
        } catch (Exception e) {
            LogUtil.e("APP[" + elapsed() + "] dumpNativeLibs failed: " + e.getMessage());
        }
    }

    private static void dumpNativeLibsStatic() {
        try {
            File f = new File("/sdcard/YSPlayerLogs/maps_" + android.os.Process.myPid() + ".txt");
            java.io.InputStream is = new java.io.FileInputStream("/proc/self/maps");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
        } catch (Exception ignored) {
        }
    }

    private void performCapabilityCheck() {
        DeviceCapability.GlesInfo glesInfo = DeviceCapability.detectGlesCapability();
        LogUtil.i("Device GLES: " + glesInfo.toString());

        DeviceCapability.CompatibilityResult glesCompat =
                DeviceCapability.checkCompatibility(glesInfo);
        LogUtil.i("GLES compat: " + glesCompat.message);

        if (!glesCompat.pass) {
            LogUtil.w("!!! WARNING: Built for GLES " + glesCompat.requiredMajor
                    + "." + glesCompat.requiredMinor + " but device only supports "
                    + glesCompat.actualMajor + "." + glesCompat.actualMinor
                    + ". Rendering may fail or be degraded.");
        }
    }

    @Override
    public void onTerminate() {
        LogUtil.i("=== YSPlayer terminated ===");
        closeResources();
        super.onTerminate();
    }

    private void closeResources() {
        if (mBgThread != null) {
            mBgThread.quitSafely();
            mBgThread = null;
        }
        try {
            Class<?> geckoEngine = Class.forName("com.ysplayer.GeckoWebEngine");
            geckoEngine.getMethod("shutdownRuntime").invoke(null);
        } catch (Throwable ignored) {
        }
        LogUtil.cleanup();
        LogUtil.close();
    }

    public static App getInstance() {
        return sInstance;
    }

    private boolean isMainProcess() {
        String procName = resolveProcessName();
        String pkgName = getPackageName();
        return procName == null || procName.equals(pkgName);
    }

    private static String resolveProcessName() {
        try {
            java.lang.reflect.Method method = android.os.Process.class.getMethod(
                    "getProcessName", int.class);
            return (String) method.invoke(null, android.os.Process.myPid());
        } catch (Throwable e) {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.FileReader("/proc/self/cmdline"));
                String name = reader.readLine().trim();
                reader.close();
                return name;
            } catch (Throwable e2) {
                return null;
            }
        }
    }
}