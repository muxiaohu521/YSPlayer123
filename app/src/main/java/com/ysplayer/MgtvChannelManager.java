package com.ysplayer;

import android.content.Context;
import android.content.SharedPreferences;

import com.ysplayer.bean.MgtvChannel;
import com.ysplayer.parser.HttpFetcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MgtvChannelManager {

    private static final String TAG = "MgtvChannelManager";
    private static final String PREF_NAME = "mgtv_channels_prefs";
    private static final String KEY_URL = "channels_url";
    private static final String KEY_LAST_UPDATE = "last_update_ms";
    private static final String CACHE_FILE = "mgtv_channels.json";
    private static final long CACHE_EXPIRATION_MS = 24 * 60 * 60 * 1000L;

    private static final String LIVE_URL_PREFIX = "https://www.mgtv.com/live/";

    // 远程频道列表 JSON 地址：留空表示未配置，仅使用内置列表
    private static final String DEFAULT_CHANNELS_URL = "";

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    private static final List<MgtvChannel> BUILT_IN = new ArrayList<>();
    static {
        BUILT_IN.add(new MgtvChannel("湖南卫视", "2778"));
        BUILT_IN.add(new MgtvChannel("金鹰卡通", "287"));
        BUILT_IN.add(new MgtvChannel("湖南经视", "280"));
        BUILT_IN.add(new MgtvChannel("湖南娱乐", "344"));
        BUILT_IN.add(new MgtvChannel("湖南电影", "221"));
        BUILT_IN.add(new MgtvChannel("湖南都市", "346"));
        BUILT_IN.add(new MgtvChannel("湖南电视剧", "484"));
        BUILT_IN.add(new MgtvChannel("金鹰纪实", "316"));
        BUILT_IN.add(new MgtvChannel("湖南爱晚", "261"));
        BUILT_IN.add(new MgtvChannel("湖南国际", "229"));
        BUILT_IN.add(new MgtvChannel("快乐垂钓", "218"));
        BUILT_IN.add(new MgtvChannel("长沙新闻综合", "269"));
        BUILT_IN.add(new MgtvChannel("长沙政法", "254"));
    }

    public interface Callback {
        void onResult(List<MgtvChannel> channels);
    }

    private MgtvChannelManager() {
    }

    public static String liveUrl(MgtvChannel channel) {
        return LIVE_URL_PREFIX + channel.getCameraId() + "/";
    }

    public static String getChannelsUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, null);
        if (saved != null) return saved;
        return DEFAULT_CHANNELS_URL;
    }

    public static void setChannelsUrl(Context context, String url) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url == null ? "" : url.trim())
                .apply();
    }

    public static List<MgtvChannel> getBuiltInChannels() {
        return new ArrayList<>(BUILT_IN);
    }

    public static List<MgtvChannel> loadChannels(Context context) {
        List<MgtvChannel> cached = readCache(context);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return getBuiltInChannels();
    }

    public static void refreshAsync(final Context context, final boolean force,
            final Callback callback) {
        sExecutor.execute(new Runnable() {
            @Override
            public void run() {
                List<MgtvChannel> result = refresh(context, force);
                if (callback != null) {
                    callback.onResult(result);
                }
            }
        });
    }

    private static List<MgtvChannel> refresh(Context context, boolean force) {
        String url = getChannelsUrl(context);
        if (url == null || url.isEmpty()) {
            return null;
        }
        long lastUpdate = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_UPDATE, 0L);
        if (!force && System.currentTimeMillis() - lastUpdate < CACHE_EXPIRATION_MS) {
            List<MgtvChannel> cached = readCache(context);
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
        }
        try {
            HttpFetcher.Response response = HttpFetcher.get(url);
            if (response.code != 200) {
                LogUtil.w(TAG + ": 芒果TV频道列表请求失败 HTTP " + response.code);
                return null;
            }
            String body = response.bodyAsString();
            List<MgtvChannel> parsed = parse(body);
            if (parsed == null || parsed.isEmpty()) {
                LogUtil.w(TAG + ": 芒果TV频道列表解析为空");
                return null;
            }
            writeCache(context, body);
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply();
            LogUtil.i(TAG + ": 芒果TV频道列表已更新, 共 " + parsed.size() + " 个频道");
            return parsed;
        } catch (Exception e) {
            LogUtil.w(TAG + ": 芒果TV频道列表获取失败");
            return null;
        }
    }

    private static List<MgtvChannel> parse(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        List<MgtvChannel> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String name = obj.optString("name", "").trim();
            String cameraId = obj.optString("cameraId", "").trim();
            if (name.isEmpty() || cameraId.isEmpty()) continue;
            list.add(new MgtvChannel(name, cameraId));
        }
        return list;
    }

    private static List<MgtvChannel> readCache(Context context) {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = in.read(buf);
            if (read <= 0) return null;
            return parse(new String(buf, 0, read, "UTF-8"));
        } catch (Exception e) {
            LogUtil.w(TAG + ": 读取芒果TV频道缓存失败");
            return null;
        }
    }

    private static void writeCache(Context context, String json) {
        File file = new File(context.getFilesDir(), CACHE_FILE);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes("UTF-8"));
            out.flush();
        } catch (IOException e) {
            LogUtil.w(TAG + ": 写入芒果TV频道缓存失败");
        }
    }
}