package com.ysplayer;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import com.tencent.smtt.export.external.TbsCoreSettings;
import com.tencent.smtt.sdk.QbSdk;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class X5CoreInitializer {

    private static final String TAG = "X5Core";
    private static final Handler UI_HANDLER = new Handler(Looper.getMainLooper());
    private static Timer sPollTimer;
    private static volatile boolean sPolling;
    private static boolean sPollingInitDone;

    public static void init(final Application app) {
        LogUtil.i(TAG + ": Starting X5 core init...");

        final String abi = android.os.Build.CPU_ABI;
        final String abi2 = android.os.Build.CPU_ABI2;
        final boolean is64Bit = abi.contains("arm64") || (abi2 != null && abi2.contains("arm64"));
        final String tbsDir = is64Bit ? "app_tbs_64" : "app_tbs";
        LogUtil.i(TAG + ": Device ABI: " + abi + ", tbsDir=" + tbsDir);

        QbSdk.setDownloadWithoutWifi(true);

        HashMap<String, Object> settings = new HashMap<>();
        settings.put(TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER, true);
        settings.put(TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE, true);
        QbSdk.initTbsSettings(settings);

        new Thread("X5-Install") {
            @Override
            public void run() {
                boolean copied = copyPreExtractedKernel(app, tbsDir, is64Bit);
                LogUtil.i(TAG + ": kernel copy " + (copied ? "OK" : "FAILED"));

                UI_HANDLER.post(new Runnable() {
                    @Override
                    public void run() {
                        initX5AndPoll(app);
                    }
                });
            }
        }.start();
    }

    private static boolean copyPreExtractedKernel(Application app, String tbsDir, boolean is64Bit) {
        File dataDir = new File(app.getApplicationInfo().dataDir);
        File coreShare = new File(dataDir, tbsDir + "/core_share");
        File corePrivate = new File(dataDir, tbsDir + "/core_private");
        if (!coreShare.exists()) {
            coreShare.mkdirs();
        }
        if (!corePrivate.exists()) {
            corePrivate.mkdirs();
        }

        int copyCount = 0;
        int version = 0;

        try {
            String versionStr = readAssetFile(app, "x5core/version.txt");
            if (versionStr != null) {
                version = Integer.parseInt(versionStr.trim());
            }
        } catch (Exception e) {
            LogUtil.e(TAG + ": Failed to read kernel version: " + e.getMessage());
        }

        try {
            String filesList = readAssetFile(app, "x5core/files.txt");
            if (filesList == null || filesList.isEmpty()) {
                LogUtil.e(TAG + ": files.txt is empty, no kernel files to copy");
                return false;
            }

            String[] files = filesList.split("\n");
            byte[] buf = new byte[65536];

            for (String fileName : files) {
                fileName = fileName.trim();
                if (fileName.isEmpty()) continue;

                File destFile = new File(coreShare, fileName);
                if (destFile.exists() && destFile.length() > 0) {
                    copyCount++;
                    continue;
                }

                try (InputStream is = app.getAssets().open("x5core/" + fileName);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    int len;
                    long total = 0;
                    while ((len = is.read(buf)) > 0) {
                        fos.write(buf, 0, len);
                        total += len;
                    }
                    copyCount++;
                } catch (IOException e) {
                    LogUtil.e(TAG + ": Failed to copy " + fileName + ": " + e.getMessage());
                }
            }

            LogUtil.i(TAG + ": Copied " + copyCount + "/" + files.length + " kernel files to " + coreShare.getAbsolutePath());
        } catch (Exception e) {
            LogUtil.e(TAG + ": Kernel copy failed: " + e.getMessage());
            return false;
        }

        if (copyCount == 0) {
            LogUtil.e(TAG + ": No kernel files copied");
            return false;
        }

        writeTbsCoreInstallTxt(corePrivate, version);
        return true;
    }

    private static String readAssetFile(Application app, String path) {
        try (InputStream is = app.getAssets().open(path);
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }

    static void writeTbsCoreInstallTxt(File corePrivate, int version) {
        File propFile = new File(corePrivate, "tbscoreinstall.txt");
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("#update incrupdate_status and status!\n");
            sb.append("#" + new java.util.Date() + "\n");
            sb.append("install_core_ver=" + version + "\n");
            sb.append("install_status=2\n");
            sb.append("incrupdate_status=-1\n");
            sb.append("unzip_retry_num=0\n");
            sb.append("tbs_local_installation=true\n");
            java.io.FileWriter fw = new java.io.FileWriter(propFile);
            fw.write(sb.toString());
            fw.close();
            LogUtil.i(TAG + ": tbscoreinstall.txt written: version=" + version);
        } catch (IOException e) {
            LogUtil.e(TAG + ": Failed to write tbscoreinstall.txt: " + e.getMessage());
        }
    }

    private static void initX5AndPoll(final Application app) {
        if (sPolling) return;
        sPolling = true;

        if (sPollTimer != null) {
            sPollTimer.cancel();
        }
        sPollTimer = new Timer("X5-Poll", true);
        sPollTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                QbSdk.initX5Environment(app, new QbSdk.PreInitCallback() {
                    @Override
                    public void onCoreInitFinished() {}

                    @Override
                    public void onViewInitFinished(boolean isX5) {
                        LogUtil.i(TAG + ": poll init: isX5=" + isX5
                                + ", version=" + QbSdk.getTbsVersion(app));
                        if (isX5) {
                            LogUtil.i(TAG + ": X5 kernel is active!");
                            X5WebEngine.onX5CoreReady();
                            stopPoll();
                            return;
                        }
                    }
                });
                int version = QbSdk.getTbsVersion(app);
                LogUtil.d(TAG + ": poll: tbsVersion=" + version);
                if (version > 0 && !sPollingInitDone) {
                    sPollingInitDone = true;
                    LogUtil.i(TAG + ": X5 version detected: " + version);
                    X5WebEngine.onX5CoreReady();
                    stopPoll();
                }
            }
        }, 0, 1000);
    }

    private static void stopPoll() {
        sPolling = false;
        if (sPollTimer != null) {
            sPollTimer.cancel();
            sPollTimer = null;
        }
    }
}