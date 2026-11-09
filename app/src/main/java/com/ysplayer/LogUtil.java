package com.ysplayer;

import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public final class LogUtil {

    private static final String TAG = "YSPlayer";
    private static final String LOG_DIR = "/sdcard/YSPlayerLogs";
    private static final int MAX_LOG_FILES = 5;
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat FILE_FMT =
            new SimpleDateFormat("yyyyMMdd", Locale.US);
    private static BufferedWriter sWriter;
    private static boolean sFileEnabled = true;
    private static boolean sInitialized;
    private static int sWriteCount;

    private LogUtil() {
    }

    public static void init() {
        if (sInitialized || !BuildConfig.LOG_ENABLED) return;
        try {
            File dir = new File(LOG_DIR);
            cleanupOldLogs(dir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.isDirectory()) {
                rotateLogFiles(dir);
                 String filename = "ysplayer_" + FILE_FMT.format(new Date())
                        + "_pid" + android.os.Process.myPid() + ".log";
                sWriter = new BufferedWriter(new FileWriter(new File(dir, filename), true),
                        8192);
                sInitialized = true;
                android.util.Log.i(TAG, "[LOG] file created: " + new File(dir, filename).getAbsolutePath());
            }
        } catch (IOException e) {
            sFileEnabled = false;
            android.util.Log.w(TAG, "[LOG] Failed to init log file", e);
        }
    }

    private static void rotateLogFiles(File dir) {
        File[] logFiles = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".log");
            }
        });
        if (logFiles == null || logFiles.length < MAX_LOG_FILES) {
            return;
        }
        Arrays.sort(logFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int toDelete = logFiles.length - MAX_LOG_FILES + 1;
        for (int i = 0; i < toDelete && i < logFiles.length; i++) {
            logFiles[i].delete();
        }
    }

    public static void close() {
        if (!BuildConfig.LOG_ENABLED) return;
        if (sWriter != null) {
            try {
                sWriter.flush();
                sWriter.close();
            } catch (IOException ignored) {
            }
            sWriter = null;
        }
    }

    public static void flush() {
        if (sWriter != null) {
            try {
                sWriter.flush();
            } catch (IOException ignored) {
            }
        }
    }

    public static void cleanup() {
        File dir = new File(LOG_DIR);
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    private static void cleanupOldLogs(File dir) {
        // 崩溃调试期间不删除旧日志
        if (!dir.exists() || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            // 保留最近10个日志文件
            if (files.length > 10) {
                Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < files.length - 10 && i < files.length; i++) {
                    files[i].delete();
                }
            }
        }
        // 不再删除目录
    }

    public static void d(String msg) {
        log(Log.DEBUG, msg, null);
    }

    public static void i(String msg) {
        log(Log.INFO, msg, null);
    }

    public static void w(String msg) {
        log(Log.WARN, msg, null);
    }

    public static void e(String msg) {
        log(Log.ERROR, msg, null);
    }

    public static void e(String msg, Throwable tr) {
        log(Log.ERROR, msg, tr);
    }

    private static void log(int priority, String msg, Throwable tr) {
        if (!BuildConfig.LOG_ENABLED) {
            return;
        }

        String fullLine = DATE_FMT.format(new Date()) + " ["
                + priorityChar(priority) + "] " + msg;

        if (tr != null) {
            StringWriter sw = new StringWriter();
            tr.printStackTrace(new PrintWriter(sw));
            fullLine += "\n" + sw.toString();
        }

        switch (priority) {
            case Log.DEBUG:
                Log.d(TAG, fullLine);
                break;
            case Log.INFO:
                Log.i(TAG, fullLine);
                break;
            case Log.WARN:
                Log.w(TAG, fullLine);
                break;
            case Log.ERROR:
                Log.e(TAG, fullLine);
                break;
            default:
                Log.v(TAG, fullLine);
                break;
        }

        writeToFile(fullLine);
    }

    private static char priorityChar(int priority) {
        switch (priority) {
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            default:
                return 'V';
        }
    }

    private static final int FLUSH_INTERVAL = 20;

    private static void writeToFile(String line) {
        if (!sFileEnabled || sWriter == null) {
            return;
        }
        try {
            sWriter.write(line);
            sWriter.newLine();
            sWriteCount++;
            if (sWriteCount % FLUSH_INTERVAL == 0) {
                sWriter.flush();
            }
        } catch (IOException e) {
            sFileEnabled = false;
            try {
                sWriter.close();
            } catch (IOException ignored) {
            }
            sWriter = null;
        }
    }

    public static String getLogDirectory() {
        return LOG_DIR;
    }
}