package com.ysplayer;

import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ysplayer.adapter.ChannelAdapter;
import com.ysplayer.adapter.SettingGroupAdapter;
import com.ysplayer.adapter.SettingItemAdapter;
import com.ysplayer.bean.ChannelInfo;
import com.ysplayer.bean.ChannelInfo.Route;
import com.ysplayer.bean.MgtvChannel;
import com.ysplayer.bean.SettingGroup;
import com.ysplayer.bean.SettingItemInfo;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "ysplayer_prefs";
    private static final String KEY_UA_MODE = "ua_mode";
    private static final String KEY_UA_MODE_PREFIX = "ua_mode_";
    private static final String KEY_DECODING_MODE = "decoding_mode";
    private static final String KEY_ROUTE_INDEX_PREFIX = "route_idx_";
    private static final String KEY_LAST_CHANNEL = "last_channel";
    private static final String KEY_UA_CHANNEL_PREFIX = "ua_ch_";
    private static final String KEY_DECODING_CHANNEL_PREFIX = "decoding_ch_";
    private static final int CHANNEL_SETTING_UNSET = -1;

    private FrameLayout rootLayout;
    private FrameLayout engineContainer;
    private WebEngine engine;
    private AudioManager audioManager;

    private View clickOverlayLeft;
    private View clickOverlayRight;
    private LinearLayout leftChannelPanel;
    private LinearLayout rightSettingsPanel;
    private LinearLayout bottomInfoBar;
    private TextView tvCurrentChannel;
    private TextView tvPanelHint;

    private RecyclerView rvChannelList;
    private ChannelAdapter channelAdapter;

    private RecyclerView rvSettingTabs;
    private RecyclerView rvSettingItems;
    private View vSettingDivider;
    private SettingGroupAdapter settingGroupAdapter;
    private SettingItemAdapter settingItemAdapter;
    private final List<SettingGroup> settingGroups = new ArrayList<>();
    private int currentSettingGroupIndex = -1;
    private int currentSettingItemIndex = -1;
    private boolean focusOnLevel1 = true;

    private int[] mSupportedDecodingModes = new int[]{WebEngine.DECODING_MODE_IJK};
    private String[] mDecodingModeLabels = new String[]{};

    private final List<ChannelInfo> allChannelList = new ArrayList<>();
    private final List<MgtvChannel> mgtvChannels = new ArrayList<>();
    private int currentChannelIndex = -1;
    private Handler mainHandler;

    private boolean channelPanelVisible;
    private boolean settingsPanelVisible;
    private int currentUaMode = 2;
    private int currentDecodingMode = WebEngine.DECODING_MODE_IJK;
    private boolean mImmersiveApplied;
    private boolean mPageLoaded;
    private boolean mPendingBottomBarShow;

    private static final long PANEL_AUTO_HIDE_DELAY = 10000;
    private static final long BAR_AUTO_HIDE_DELAY = 5000;
    private Runnable hidePanelTask;
    private Runnable hideBarTask;
    private Runnable mResumeReloadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            getWindow().addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        enterImmersiveFullscreen();

        mainHandler = new Handler(Looper.getMainLooper());

        initViews();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        initEngine();
        initChannelList();
        initClickListeners();
        loadSettings();
        buildChannelList();
        refreshMgtvChannels();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
    }

    private void enterImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            int current = getWindow().getDecorView().getSystemUiVisibility();
            if (current != flags) {
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
            mImmersiveApplied = true;
        }
    }

    private void initViews() {
        rootLayout = findViewById(R.id.rootLayout);
        engineContainer = findViewById(R.id.engineContainer);
        clickOverlayLeft = findViewById(R.id.clickOverlayLeft);
        clickOverlayRight = findViewById(R.id.clickOverlayRight);
        leftChannelPanel = findViewById(R.id.leftChannelPanel);
        rightSettingsPanel = findViewById(R.id.rightSettingsPanel);
        bottomInfoBar = findViewById(R.id.bottomInfoBar);
        tvCurrentChannel = findViewById(R.id.tvCurrentChannel);
        tvPanelHint = findViewById(R.id.tvPanelHint);
        rvChannelList = findViewById(R.id.rvChannelList);
        rvSettingTabs = findViewById(R.id.rvSettingTabs);
        rvSettingItems = findViewById(R.id.rvSettingItems);
        vSettingDivider = findViewById(R.id.vSettingDivider);
    }

    private void initEngine() {
        engine = EngineFactory.createEngine(engineContainer);
        engine.setCallback(new WebEngine.Callback() {
            @Override
            public void onPageStarted() {
            }

            @Override
            public void onPageFinished() {
                mPageLoaded = true;
                mainHandler.post(() -> {
                    if (mPendingBottomBarShow) {
                        mPendingBottomBarShow = false;
                        if (!channelPanelVisible && !settingsPanelVisible) {
                            showBottomBar();
                        }
                        updateBottomInfoBar();
                    }
                });
            }

            @Override
            public void onLoadError(String description) {
                mPageLoaded = false;
                LogUtil.w("MainActivity: 频道加载失败: " + description);
            }
        });
        engine.init(engineContainer);
    }

    private void initClickListeners() {
        clickOverlayLeft.setOnClickListener(v -> {
            if (settingsPanelVisible) hideSettingsPanel();
            if (channelPanelVisible) {
                hideChannelPanel();
            } else {
                showChannelPanel();
            }
        });
        clickOverlayRight.setOnClickListener(v -> {
            if (channelPanelVisible) hideChannelPanel();
            if (settingsPanelVisible) {
                hideSettingsPanel();
            } else {
                showSettingsPanel();
            }
        });
        bottomInfoBar.setOnClickListener(v -> {
            if (channelPanelVisible) hideChannelPanel();
            else if (settingsPanelVisible) hideSettingsPanel();
        });

        // ==================== 面板计时器重置：鼠标悬停 / 触屏滚动 ====================
        RecyclerView.OnScrollListener scrollResetListener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (channelPanelVisible || settingsPanelVisible) {
                    scheduleHidePanel();
                }
            }
        };
        rvChannelList.addOnScrollListener(scrollResetListener);

        leftChannelPanel.setOnHoverListener((v, event) -> {
            if (channelPanelVisible) scheduleHidePanel();
            return false;
        });
        rightSettingsPanel.setOnHoverListener((v, event) -> {
            if (settingsPanelVisible) scheduleHidePanel();
            return false;
        });
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        currentUaMode = getDefaultUaMode(prefs);
        engine.setUaMode(currentUaMode);
        int savedMode = prefs.getInt(KEY_DECODING_MODE, WebEngine.DECODING_MODE_IJK);
        int[] supported = engine.getSupportedDecodingModes();
        currentDecodingMode = isModeSupported(savedMode, supported) ? savedMode : supported[0];
        engine.setDecodingMode(currentDecodingMode);
    }

    private static boolean isModeSupported(int mode, int[] supported) {
        if (supported == null || supported.length == 0) return false;
        for (int s : supported) {
            if (s == mode) return true;
        }
        return false;
    }

    private int getDefaultUaMode(SharedPreferences prefs) {
        String uaKey = getUaPrefKey();
        if (prefs.contains(uaKey)) {
            return prefs.getInt(uaKey, 2);
        }
        return prefs.getInt(KEY_UA_MODE, 2);
    }

    private String getUaPrefKey() {
        return KEY_UA_MODE_PREFIX + engine.getEngineType();
    }

    private void applyUaMode(int mode) {
        if (currentUaMode == mode) return;
        currentUaMode = mode;
        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            ChannelInfo ch = allChannelList.get(currentChannelIndex);
            saveChannelUaMode(ch.getName(), mode);
        }
        engine.setUaMode(mode);
        refreshSettingItems();
        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            ChannelInfo ch = allChannelList.get(currentChannelIndex);
            engine.loadUrl(ch.getWebUrl());
        }
    }

    private void initChannelList() {
        channelAdapter = new ChannelAdapter();
        rvChannelList.setLayoutManager(new WrapAroundLayoutManager(this));
        rvChannelList.setAdapter(channelAdapter);
        channelAdapter.setOnChannelClickListener((channel, pos) -> {
            int realIdx = allChannelList.indexOf(channel);
            if (realIdx >= 0) {
                switchChannel(realIdx);
            }
            hideChannelPanel();
        });
    }

    private void buildChannelList() {
        allChannelList.clear();
        mgtvChannels.clear();
        mgtvChannels.addAll(MgtvChannelManager.loadChannels(this));
        buildChannelData();
        channelAdapter.setChannels(allChannelList, () -> {
            if (allChannelList.isEmpty()) return;
            loadRoutePreferences();
            int savedIndex = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .getInt(KEY_LAST_CHANNEL, 0);
            if (savedIndex < 0 || savedIndex >= allChannelList.size()) {
                savedIndex = 0;
            }
            final int index = savedIndex;
            mainHandler.post(() -> switchChannel(index));
        });
    }

    private void loadRoutePreferences() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        for (ChannelInfo ch : allChannelList) {
            if (ch.getRouteCount() > 0) {
                int savedIdx = prefs.getInt(KEY_ROUTE_INDEX_PREFIX + ch.getName(), 0);
                if (savedIdx >= 0 && savedIdx < ch.getRouteCount()) {
                    ch.setCurrentRouteIndex(savedIdx);
                }
            }
        }
    }

    private void saveRoutePreference(ChannelInfo channel) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_ROUTE_INDEX_PREFIX + channel.getName(),
                        channel.getCurrentRouteIndex()).apply();
    }

    // ==================== 频道数据 ====================
    // TODO: Extract channel data to JSON/XML config for non-developer maintenance
    private void buildChannelData() {
        int num = 1;

        // CCTV      
        allChannelList.add(createChannel("CCTV13 新闻", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001811"),
                route("央视网网页", "https://tv.cctv.com/live/cctv13/")));
        allChannelList.add(createChannel("CCTV8 电视剧", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001803"),
                route("央视网网页", "https://tv.cctv.com/live/cctv8/")));
        allChannelList.add(createChannel("CCTV6 电影", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600108442"),
                route("央视网网页", "https://tv.cctv.com/live/cctv6/")));
        allChannelList.add(createChannel("CCTV1 综合", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001859"),
                route("央视网网页", "https://tv.cctv.com/live/cctv1/")));
        
        // 山东
        allChannelList.add(createChannel("山东卫视", num++,
                route("齐鲁网", "https://v.iqilu.com/live/sdtv/"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002513")));
        allChannelList.add(createChannel("山东齐鲁频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/qlpd/")));
        allChannelList.add(createChannel("山东新闻频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/ggpd/")));
        allChannelList.add(createChannel("山东生活频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/shpd/")));
        allChannelList.add(createChannel("山东综艺频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/zypd/")));        
        allChannelList.add(createChannel("山东体育频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/typd/")));        
        allChannelList.add(createChannel("山东农科频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/nkpd/")));
        allChannelList.add(createChannel("山东文旅频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/yspd/")));
        allChannelList.add(createChannel("山东少儿频道", num++,
                route("齐鲁网", "https://v.iqilu.com/live/sepd/")));

        // 青岛
        allChannelList.add(createChannel("青岛新闻综合", num++,
                route("青岛网络电视台", "https://www.qtv.com.cn/live/tv/")));
        allChannelList.add(createChannel("青岛生活服务", num++,
                route("青岛网络电视台", "https://www.qtv.com.cn/live/tv/tv_qtv2.shtml")));
        allChannelList.add(createChannel("青岛文旅体育", num++,
                route("青岛网络电视台", "https://www.qtv.com.cn/live/tv/tv_qtv3.shtml")));        
        allChannelList.add(createChannel("青岛教育", num++,
                route("青岛网络电视台", "https://www.qtv.com.cn/live/tv/tv_qtv6.shtml"))); 
        allChannelList.add(createChannel("青岛都市", num++,
                route("青岛网络电视台", "https://www.qtv.com.cn/live/tv/tv_qtv4.shtml")));     

        // 浙江
        allChannelList.add(createChannel("浙江卫视", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/101"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002520")));
        allChannelList.add(createChannel("科教影视", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/104")));
        allChannelList.add(createChannel("钱江都市", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/102")));
        allChannelList.add(createChannel("经济生活", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/103")));     
        allChannelList.add(createChannel("浙江新闻", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/107")));
        allChannelList.add(createChannel("浙江少儿", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/108")));
        allChannelList.add(createChannel("浙江国际", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/110")));   
        allChannelList.add(createChannel("民生休闲", num++,
                route("中国蓝TV", "https://www.cztv.com/liveTV/106")));

        // 江苏
        num = allChannelList.size() + 1;
        allChannelList.add(createChannel("江苏卫视", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=660"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002521"),
                route("荔枝网4K", "https://live.jstv.com/?channelId=659")));
        allChannelList.add(createChannel("江苏综艺", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=663")));
        allChannelList.add(createChannel("江苏影视", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=664")));
        allChannelList.add(createChannel("江苏体育", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=665")));
        allChannelList.add(createChannel("江苏教育", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=666")));
        allChannelList.add(createChannel("优漫卡通", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=667")));
        allChannelList.add(createChannel("江苏新闻", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=668")));
        allChannelList.add(createChannel("江苏城市", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=669")));
        allChannelList.add(createChannel("江苏国际", num++,
                route("荔枝网", "https://live.jstv.com/?channelId=671")));

        // 卫视
        allChannelList.add(createChannel("北京卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002309")));
        allChannelList.add(createChannel("天津卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600152137")));
        allChannelList.add(createChannel("重庆卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002531")));
        allChannelList.add(createChannel("深圳卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002481")));      
        allChannelList.add(createChannel("安徽卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002532")));
        allChannelList.add(createChannel("河南卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002525")));
        allChannelList.add(createChannel("辽宁卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002505")));
        allChannelList.add(createChannel("吉林卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190405")));
        allChannelList.add(createChannel("云南卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190402"))); 
        allChannelList.add(createChannel("四川卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002516")));
        allChannelList.add(createChannel("甘肃卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190408")));
        allChannelList.add(createChannel("青海卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190406")));  

        // 湖南（芒果TV 线路：动态远程 JSON，内置列表兜底）
        for (MgtvChannel c : mgtvChannels) {
            if ("湖南卫视".equals(c.getName())) {
                allChannelList.add(createChannel(c.getName(), num++,
                        route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002475"),
                        route("芒果TV", MgtvChannelManager.liveUrl(c))));
            } else {
                allChannelList.add(createChannel(c.getName(), num++,
                        route("芒果TV", MgtvChannelManager.liveUrl(c))));
            }
        }

        // 山西
        allChannelList.add(createChannel("山西卫视", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#q8RVWgs"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190407")));
        allChannelList.add(createChannel("黄河电视台", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#lce1mC4")));
        allChannelList.add(createChannel("山西经济与科技", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#4j01KWX")));
        allChannelList.add(createChannel("山西影视", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#Md571Kv")));
        allChannelList.add(createChannel("山西社会与法治", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#p4y5do9")));
        allChannelList.add(createChannel("山西文体生活", num++,
                route("山西网络广播电视台", "https://www.sxrtv.com/tv/index.shtml#agmpyEk")));

        // 陕西
        allChannelList.add(createChannel("陕西卫视", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/star"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190400")));
        allChannelList.add(createChannel("陕西农林", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/nl"))); 
        allChannelList.add(createChannel("陕西移动电视", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/11")));
        allChannelList.add(createChannel("陕西体育休闲", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/7")));
        allChannelList.add(createChannel("陕西新闻资讯", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/1")));
        allChannelList.add(createChannel("陕西都市青春", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/2")));
        allChannelList.add(createChannel("陕西银龄", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/3")));
        allChannelList.add(createChannel("陕西秦腔", num++,
                route("陕西网络广播电视台", "http://live.snrtv.com/5")));       
               
        // 广西
        allChannelList.add(createChannel("广西卫视", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_e7a7ab7df9fe11e88bcfe41f13b60c62.html"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002509")));
        allChannelList.add(createChannel("广西综艺旅游", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_f3335975f9fe11e88bcfe41f13b60c62.html")));
        allChannelList.add(createChannel("广西都市", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_fdbaf085f9fe11e88bcfe41f13b60c62.html")));
        allChannelList.add(createChannel("广西影视", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_5e923d82058e11e9ba67e41f13b60c62.html")));
        allChannelList.add(createChannel("广西新闻", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_9dfd8600075811e9ba67e41f13b60c62.html")));
        allChannelList.add(createChannel("CETV1", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_ffa6b6e1b32b4a16a73eb3ef66f8bfc7.html"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600171827")));
        allChannelList.add(createChannel("CETV2", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_80d0ffb42c114eaf9663708629ff0a3e.html")));
        allChannelList.add(createChannel("CETV4", num++,
                route("广西网络广播电视台",
                        "https://tv.gxtv.cn/channel/channelivePlay_67eace939278435bb4bca90800fb4225.html")));

        // 上海
        allChannelList.add(createChannel("东方卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002483"),
                route("看看新闻", "https://live.kankanews.com/huikan/")));
        allChannelList.add(createChannel("上海新闻综合", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=2")));
        allChannelList.add(createChannel("魔都眼", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=11")));
        allChannelList.add(createChannel("第一财经", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=5")));
        allChannelList.add(createChannel("新纪实", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=12")));
        allChannelList.add(createChannel("五星体育", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=10")));
        allChannelList.add(createChannel("上海都市", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=4")));
        allChannelList.add(createChannel("哈哈炫动", num++,
                route("看看新闻", "https://live.kankanews.com/huikan/?id=9")));

        // 宁夏
        allChannelList.add(createChannel("宁夏卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190737"),
                route("宁夏网络广播电视台", "https://www.nxtv.com.cn/19/19kds/dsp/")));
        allChannelList.add(createChannel("宁夏公共", num++,
                route("宁夏网络广播电视台", "https://www.nxtv.com.cn/19/19kds/nxggpd/")));
        allChannelList.add(createChannel("宁夏文旅", num++,
                route("宁夏网络广播电视台", "https://www.nxtv.com.cn/19/19kds/nxwlpd/")));
        allChannelList.add(createChannel("宁夏少儿", num++,
                route("宁夏网络广播电视台", "https://www.nxtv.com.cn/19/19kds/nxsepd/")));        

        // 湖北
        allChannelList.add(createChannel("湖北卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002508"),
                route("长江云", "https://news.hbtv.com.cn/app/tv/431")));
        allChannelList.add(createChannel("湖北经视", num++,
                route("长江云", "https://news.hbtv.com.cn/app/tv/432")));
        allChannelList.add(createChannel("湖北综合", num++,
                route("长江云", "https://news.hbtv.com.cn/app/tv/433")));
        allChannelList.add(createChannel("湖北影视", num++,
                route("长江云", "https://news.hbtv.com.cn/app/tv/435")));
        allChannelList.add(createChannel("湖北教育", num++,
                route("长江云", "https://news.hbtv.com.cn/app/tv/437")));
        allChannelList.add(createChannel("湖北垄上", num++,
                route("长江云", "https://news.hbtv.com.cn/app/tv/438")));

        // 广东
        allChannelList.add(createChannel("广东卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002485"),
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/43"),
                route("荔枝网4K", "https://gdtv.cn/tvChannelDetail/16")));
        allChannelList.add(createChannel("广东珠江", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/44")));
        allChannelList.add(createChannel("广东新闻", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/45")));
        allChannelList.add(createChannel("广东民生", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/48")));
        allChannelList.add(createChannel("广东体育", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/47")));
        allChannelList.add(createChannel("大湾区卫视", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/51")));
        allChannelList.add(createChannel("大湾区卫视海外版", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/46")));
        allChannelList.add(createChannel("广东影视", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/53")));
        allChannelList.add(createChannel("广东少儿", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/54")));
        allChannelList.add(createChannel("嘉佳卡通", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/66")));
        allChannelList.add(createChannel("南方购物", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/42")));
        allChannelList.add(createChannel("岭南戏曲", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/15")));
        allChannelList.add(createChannel("广东移动", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/74")));
        allChannelList.add(createChannel("广东台经典剧", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/100")));
        allChannelList.add(createChannel("广东纪录片", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/94")));
        allChannelList.add(createChannel("广东健康", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/99")));
        allChannelList.add(createChannel("GRTN生活", num++,
                route("荔枝网", "https://gdtv.cn/tvChannelDetail/102")));

        // 香港 / 澳门
        allChannelList.add(createChannel("香港卫视", num++,
                route("香港卫视官网", "https://www.hkstv.tv/live")));
        allChannelList.add(createChannel("澳门莲花卫视", num++,
                route("莲花卫视官网", "https://www.lotustv.mo/")));

        // 江西        
        allChannelList.add(createChannel("江西卫视", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv1"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002503")));
        allChannelList.add(createChannel("江西都市", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv2")));
        allChannelList.add(createChannel("江西经济生活", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv3")));
        allChannelList.add(createChannel("江西公共农业", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv5")));
        allChannelList.add(createChannel("江西少儿", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv6")));
        allChannelList.add(createChannel("江西新闻", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv7")));
        allChannelList.add(createChannel("江西移动电视", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/jxtv8")));
        allChannelList.add(createChannel("江西陶瓷", num++,
                route("江西网络广播电视台", "https://www.jxntv.cn/live/#/tcpd")));

        // 贵州
        allChannelList.add(createChannel("贵州卫视", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch01"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002490")));
        allChannelList.add(createChannel("贵州公共", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch02")));
        allChannelList.add(createChannel("贵州影视文艺", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch03")));
        allChannelList.add(createChannel("贵州大众生活", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch04")));
        allChannelList.add(createChannel("贵州生态乡村", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch05")));
        allChannelList.add(createChannel("贵州科教健康", num++,
                route("贵州网络广播电视台", "https://gzstv.com/tv/ch06")));

        // 福建
        allChannelList.add(createChannel("东南卫视", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/setv/"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002484")));
        allChannelList.add(createChannel("福建综合", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/zhpd/")));
        allChannelList.add(createChannel("福建新闻", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/xwpd/")));
        allChannelList.add(createChannel("福建文旅体育", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/dspd/")));
        allChannelList.add(createChannel("福建少儿", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/child/")));
        allChannelList.add(createChannel("海峡卫视", num++,
                route("福建网络广播电视台", "https://live.fjtv.net/haixiapd/")));

        // 内蒙古
        allChannelList.add(createChannel("内蒙古卫视", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=3621481"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190401")));
        allChannelList.add(createChannel("内蒙古蒙古语卫视", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2315")));
        allChannelList.add(createChannel("内蒙古新闻综合", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2316")));
        allChannelList.add(createChannel("内蒙古经济生活", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2317")));
        allChannelList.add(createChannel("内蒙古少儿", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2318")));
        allChannelList.add(createChannel("内蒙古文体娱乐", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2319")));
        allChannelList.add(createChannel("内蒙古农牧", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2320")));
        allChannelList.add(createChannel("内蒙古蒙古语文化", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2321")));
        allChannelList.add(createChannel("呼和浩特", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2331")));
        allChannelList.add(createChannel("包头", num++,
                route("内蒙古网络广播电视台", "https://www.nmtv.cn/liveTv?id=2358")));
        allChannelList.add(createChannel("西藏卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600190403")));
        allChannelList.add(createChannel("新疆卫视", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600152138")));  

        // 黑龙江
        allChannelList.add(createChannel("黑龙江文体", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljwt/")));
        allChannelList.add(createChannel("黑龙江农业科教", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljnykj/")));
        allChannelList.add(createChannel("黑龙江新闻法制", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/heilongjiang/")));
        allChannelList.add(createChannel("黑龙江少儿", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljse/")));
        allChannelList.add(createChannel("黑龙江都市", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljds/")));
        allChannelList.add(createChannel("黑龙江影视", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljys/")));
        allChannelList.add(createChannel("黑龙江卫视", num++,
                route("东北网", "https://www.dbw.cn/zhibodan/zhibo_dan/hljws/"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002498")));

        // 海南
        allChannelList.add(createChannel("海南自贸", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=1&referPage=home")));
        allChannelList.add(createChannel("海南新闻", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=3&referPage=home")));
        allChannelList.add(createChannel("海南社会与法", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=4&referPage=home")));
        allChannelList.add(createChannel("海南文旅", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=6&referPage=home")));
        allChannelList.add(createChannel("海南少儿", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=7&referPage=home")));
        allChannelList.add(createChannel("三沙卫视", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=5&referPage=home")));
        allChannelList.add(createChannel("海南卫视", num++,
                route("海南网络广播电视台", 
                        "https://www.hnntv.cn/live.html?playType=livePlay&channelId=13&referPage=home"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002506")));

        // 河北
        allChannelList.add(createChannel("河北文旅体育", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=3")));
        allChannelList.add(createChannel("河北少儿科教", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=4")));
        allChannelList.add(createChannel("河北三农频道", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=5")));
        allChannelList.add(createChannel("河北经济生活", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=1")));
        allChannelList.add(createChannel("河北都市", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=2")));
        allChannelList.add(createChannel("河北卫视", num++,
                route("河北网络广播电视台",
                        "https://www.hebrts.cn/19/19js/st/xdszb/index.shtml?index=0"),
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600002493")));

        // CCTV 
        allChannelList.add(createChannel("CCTV17 农业农村", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001810"),
                route("央视网网页", "https://tv.cctv.com/live/cctv17/")));
        allChannelList.add(createChannel("CCTV16 奥林匹克", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600098637"),
                route("央视网网页", "https://tv.cctv.com/live/cctv16/")));
        allChannelList.add(createChannel("CCTV15 音乐", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001815"),
                route("央视网网页", "https://tv.cctv.com/live/cctv15/"))); 
        allChannelList.add(createChannel("CCTV14 少儿", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001809"),
                route("央视网网页", "https://tv.cctv.com/live/cctvchild/")));
        allChannelList.add(createChannel("CCTV12 社会与法", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001807"),
                route("央视网网页", "https://tv.cctv.com/live/cctv12/")));
        allChannelList.add(createChannel("CCTV11 戏曲", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001806"),
                route("央视网网页", "https://tv.cctv.com/live/cctv11/")));        
        allChannelList.add(createChannel("CCTV10 科教", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001805"),
                route("央视网网页", "https://tv.cctv.com/live/cctv10/")));
        allChannelList.add(createChannel("CCTV9 纪录", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600004078"),
                route("央视网网页", "https://tv.cctv.com/live/cctvjilu/")));
        allChannelList.add(createChannel("CCTV7 国防军事", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600004092"),
                route("央视网网页", "https://tv.cctv.com/live/cctv7/")));               
        allChannelList.add(createChannel("CCTV5+ 体育赛事", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001817"),
                route("央视网网页", "https://tv.cctv.com/live/cctv5plus/")));
        allChannelList.add(createChannel("CCTV5 体育", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001818"),
                route("央视网网页", "https://tv.cctv.com/live/cctv5/")));
        allChannelList.add(createChannel("CCTV4 中文国际", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001814"),
                route("央视网网页", "https://tv.cctv.com/live/cctv4/")));        
        allChannelList.add(createChannel("CCTV3 综艺", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001801"),
                route("央视网网页", "https://tv.cctv.com/live/cctv3/")));        
        allChannelList.add(createChannel("CCTV2 财经", num++,
                route("央视频网页", "https://www.yangshipin.cn/tv/home?pid=600001800"),
                route("央视网网页", "https://tv.cctv.com/live/cctv2/")));      

    }

    private ChannelInfo createChannel(String name, int num, Route first, Route... rest) {
        ChannelInfo ch = new ChannelInfo();
        ch.setName(name);
        ch.setChannelNum(num);
        List<Route> routes = new ArrayList<>();
        routes.add(first);
        if (rest != null) {
            for (Route r : rest) routes.add(r);
        }
        ch.setRoutes(routes);
        ch.setCurrentRouteIndex(0);
        return ch;
    }

    private static Route route(String name, String url) {
        return new Route(name, url);
    }

    private void refreshMgtvChannels() {
        MgtvChannelManager.refreshAsync(this, false, newList -> {
            if (newList == null) return;
            mainHandler.post(() -> applyMgtvChannels(newList));
        });
    }

    private void applyMgtvChannels(List<MgtvChannel> newList) {
        if (newList == null || newList.isEmpty()) return;
        if (newList.equals(mgtvChannels)) return;
        String currentName = getCurrentChannelName();
        mgtvChannels.clear();
        mgtvChannels.addAll(newList);
        allChannelList.clear();
        buildChannelData();
        loadRoutePreferences();
        channelAdapter.setChannels(allChannelList, () -> {
            if (currentName == null) return;
            for (int i = 0; i < allChannelList.size(); i++) {
                if (allChannelList.get(i).getName().equals(currentName)) {
                    currentChannelIndex = i;
                    channelAdapter.setSelectedPosition(i);
                    updateBottomInfoBar();
                    break;
                }
            }
        });
    }

    private String getCurrentChannelName() {
        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            return allChannelList.get(currentChannelIndex).getName();
        }
        return null;
    }

    // ==================== 频道切换 ====================
    private void switchChannel(int index) {
        if (index < 0 || index >= allChannelList.size()) return;

        currentChannelIndex = index;
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_LAST_CHANNEL, index).apply();
        ChannelInfo channel = allChannelList.get(index);

        int filteredPos = channelAdapter.findChannelIndex(channel);
        if (filteredPos >= 0) {
            channelAdapter.setSelectedPosition(filteredPos);
            rvChannelList.scrollToPosition(filteredPos);
        }

        loadChannelSettings(channel);

        engine.stopLoading();
        updateBottomInfoBar();
        if (!channelPanelVisible && !settingsPanelVisible) {
            showBottomBar();
        }

        String url = channel.getWebUrl();
        if (url != null && !url.isEmpty()) {
            mPendingBottomBarShow = true;
            engine.loadUrl(url);
        }
    }

    private void loadChannelSettings(ChannelInfo channel) {
        if (channel == null) return;
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String channelName = channel.getName();

        int savedUa = prefs.getInt(KEY_UA_CHANNEL_PREFIX + channelName, CHANNEL_SETTING_UNSET);
        if (savedUa != CHANNEL_SETTING_UNSET) {
            if (savedUa != currentUaMode) {
                currentUaMode = savedUa;
                engine.setUaMode(savedUa);
            }
        } else {
            int defaultUa = getDefaultUaMode(prefs);
            if (defaultUa != currentUaMode) {
                currentUaMode = defaultUa;
                engine.setUaMode(defaultUa);
            }
        }

        int savedDecoding = prefs.getInt(KEY_DECODING_CHANNEL_PREFIX + channelName, CHANNEL_SETTING_UNSET);
        if (savedDecoding != CHANNEL_SETTING_UNSET) {
            if (savedDecoding != currentDecodingMode) {
                currentDecodingMode = savedDecoding;
                engine.setDecodingMode(savedDecoding);
            }
        } else {
            int defaultDecoding = prefs.getInt(KEY_DECODING_MODE, WebEngine.DECODING_MODE_IJK);
            int[] supported = engine.getSupportedDecodingModes();
            if (!isModeSupported(defaultDecoding, supported)) {
                defaultDecoding = supported.length > 0 ? supported[0] : WebEngine.DECODING_MODE_HARD;
            }
            if (defaultDecoding != currentDecodingMode) {
                currentDecodingMode = defaultDecoding;
                engine.setDecodingMode(defaultDecoding);
            }
        }
    }

    private void saveChannelUaMode(String channelName, int mode) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_UA_CHANNEL_PREFIX + channelName, mode).apply();
    }

    private void saveChannelDecodingMode(String channelName, int mode) {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit().putInt(KEY_DECODING_CHANNEL_PREFIX + channelName, mode).apply();
    }

    private void updateBottomInfoBar() {
        if (currentChannelIndex < 0 || currentChannelIndex >= allChannelList.size())
            return;
        ChannelInfo ch = allChannelList.get(currentChannelIndex);
        String routeName = ch.getCurrentRouteName();
        String text = ch.getChannelNum() + ". " + ch.getName();
        if (!routeName.isEmpty()) {
            text += " [" + routeName + "]";
        }
        tvCurrentChannel.setText(text);
        tvPanelHint.setText("←频道 | →设置");
    }

    // ==================== 面板显示/隐藏 ====================
    private void showChannelPanel() {
        if (settingsPanelVisible) hideSettingsPanel();
        channelPanelVisible = true;
        leftChannelPanel.bringToFront();
        leftChannelPanel.setVisibility(View.VISIBLE);
        bottomInfoBar.setVisibility(View.GONE);
        cancelBarHide();

        int pos = channelAdapter.getSelectedPosition();
        if (pos >= 0) {
            rvChannelList.scrollToPosition(pos);
            channelAdapter.setFocusedPosition(pos);
        } else {
            channelAdapter.setFocusedPosition(0);
        }
        rvChannelList.requestFocus();
        mainHandler.postDelayed(() -> rvChannelList.requestFocus(), 100);

        scheduleHidePanel();
    }

    private void hideChannelPanel() {
        channelPanelVisible = false;
        leftChannelPanel.setVisibility(View.GONE);
        if (!settingsPanelVisible) {
            showBottomBar();
        }
        updateBottomInfoBar();
        if (hidePanelTask != null) {
            mainHandler.removeCallbacks(hidePanelTask);
        }
    }

    private void showSettingsPanel() {
        if (channelPanelVisible) hideChannelPanel();
        settingsPanelVisible = true;
        rightSettingsPanel.bringToFront();
        rightSettingsPanel.setVisibility(View.VISIBLE);
        bottomInfoBar.setVisibility(View.GONE);
        cancelBarHide();
        initSettingViews();
        focusOnLevel1 = true;
        currentSettingGroupIndex = 3;
        rvSettingItems.setVisibility(View.VISIBLE);
        vSettingDivider.setVisibility(View.VISIBLE);
        selectSettingGroup(currentSettingGroupIndex);
        currentSettingItemIndex = 0;
        settingItemAdapter.setFocusedItemIndex(0);
        scheduleHidePanel();
    }

    private void hideSettingsPanel() {
        settingsPanelVisible = false;
        rightSettingsPanel.setVisibility(View.GONE);
        if (!channelPanelVisible) {
            showBottomBar();
        }
        updateBottomInfoBar();
        if (hidePanelTask != null) {
            mainHandler.removeCallbacks(hidePanelTask);
        }
    }

    private void initSettingViews() {
        if (settingGroupAdapter == null) {
            settingGroupAdapter = new SettingGroupAdapter();
            rvSettingTabs.setLayoutManager(
                    new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
            rvSettingTabs.setAdapter(settingGroupAdapter);
            settingGroupAdapter.setOnGroupSelectListener(groupIndex -> {
                selectSettingGroup(groupIndex);
                scheduleHidePanel();
            });
        }

        if (settingItemAdapter == null) {
            settingItemAdapter = new SettingItemAdapter();
            rvSettingItems.setLayoutManager(
                    new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
            rvSettingItems.setAdapter(settingItemAdapter);
            settingItemAdapter.setOnItemClickListener((position, item) -> {
                handleSettingItemClick(position, item);
            });
            rvSettingItems.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    if (settingsPanelVisible) {
                        scheduleHidePanel();
                    }
                }
            });
        }

        buildSettingGroups();
    }

    private void buildSettingGroups() {
        settingGroups.clear();

        SettingGroup androidGroup = new SettingGroup(0, "安卓模式");
        SettingGroup desktopGroup = new SettingGroup(1, "桌面模式");
        SettingGroup decodingGroup = new SettingGroup(2, "解码方式");
        SettingGroup routeGroup = new SettingGroup(3, "线路选择");

        for (UserAgentPreset preset : UserAgentPreset.values()) {
            SettingItemInfo item = new SettingItemInfo(preset.label,
                    preset.id == currentUaMode, preset.id);
            if ("安卓模式".equals(preset.category)) {
                androidGroup.getItems().add(item);
            } else if ("桌面模式".equals(preset.category)) {
                desktopGroup.getItems().add(item);
            }
        }

        mSupportedDecodingModes = engine.getSupportedDecodingModes();
        mDecodingModeLabels = new String[mSupportedDecodingModes.length];
        for (int i = 0; i < mSupportedDecodingModes.length; i++) {
            mDecodingModeLabels[i] = engine.getDecodingModeLabel(mSupportedDecodingModes[i]);
            decodingGroup.getItems().add(new SettingItemInfo(mDecodingModeLabels[i],
                    mSupportedDecodingModes[i] == currentDecodingMode, mSupportedDecodingModes[i]));
        }

        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            ChannelInfo channel = allChannelList.get(currentChannelIndex);
            List<Route> routes = channel.getRoutes();
            if (routes != null) {
                int currentIdx = channel.getCurrentRouteIndex();
                for (int i = 0; i < routes.size(); i++) {
                    Route route = routes.get(i);
                    routeGroup.getItems().add(new SettingItemInfo(route.getName(),
                            i == currentIdx, i));
                }
            }
        }

        settingGroups.add(androidGroup);
        settingGroups.add(desktopGroup);
        settingGroups.add(decodingGroup);
        settingGroups.add(routeGroup);

        settingGroupAdapter.setGroups(settingGroups);
    }

    private void selectSettingGroup(int groupIndex) {
        if (groupIndex < 0 || groupIndex >= settingGroups.size()) return;

        currentSettingGroupIndex = groupIndex;
        settingGroupAdapter.setSelectedGroupIndex(groupIndex);
        settingGroupAdapter.setFocusedGroupIndex(groupIndex);

        SettingGroup group = settingGroups.get(groupIndex);
        settingItemAdapter.setItems(group.getItems());
        currentSettingItemIndex = 0;
        settingItemAdapter.setFocusedItemIndex(0);
        rvSettingItems.scrollToPosition(0);
    }

    private void refreshSettingItems() {
        SettingGroup group = (currentSettingGroupIndex >= 0
                && currentSettingGroupIndex < settingGroups.size())
                ? settingGroups.get(currentSettingGroupIndex) : null;
        if (group == null) return;

        if (currentSettingGroupIndex == 0 || currentSettingGroupIndex == 1) {
            for (SettingItemInfo item : group.getItems()) {
                item.setSelected(item.getTagValue() == currentUaMode);
            }
        } else if (currentSettingGroupIndex == 2) {
            for (SettingItemInfo item : group.getItems()) {
                item.setSelected(item.getTagValue() == currentDecodingMode);
            }
        } else if (currentSettingGroupIndex == 3) {
            if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
                ChannelInfo channel = allChannelList.get(currentChannelIndex);
                int currentIdx = channel.getCurrentRouteIndex();
                for (SettingItemInfo item : group.getItems()) {
                    item.setSelected(item.getTagValue() == currentIdx);
                }
            }
        }
        settingItemAdapter.notifyDataSetChanged();
    }

    private void handleSettingItemClick(int position, SettingItemInfo item) {
        int groupIndex = currentSettingGroupIndex;
        if (groupIndex == 0 || groupIndex == 1) {
            applyUaMode(item.getTagValue());
        } else if (groupIndex == 2) {
            applyDecodingMode(item.getTagValue());
        } else if (groupIndex == 3) {
            ChannelInfo channel = (currentChannelIndex >= 0
                    && currentChannelIndex < allChannelList.size())
                    ? allChannelList.get(currentChannelIndex) : null;
            if (channel != null && item.getTagValue() < channel.getRouteCount()) {
                applyRoute(channel, item.getTagValue());
            }
        }
        hideSettingsPanel();
    }

    private void applyDecodingMode(int mode) {
        if (currentDecodingMode == mode) return;
        currentDecodingMode = mode;
        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            ChannelInfo ch = allChannelList.get(currentChannelIndex);
            saveChannelDecodingMode(ch.getName(), mode);
        }
        engine.setDecodingMode(mode);
        refreshSettingItems();
        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()) {
            ChannelInfo ch = allChannelList.get(currentChannelIndex);
            String url = ch.getWebUrl();
            if (url != null && !url.isEmpty()) {
                engine.stopLoading();
                engine.loadUrl(url);
            }
        }
    }

    private void applyRoute(ChannelInfo channel, int routeIndex) {
        if (routeIndex < 0 || routeIndex >= channel.getRouteCount()) return;
        channel.setCurrentRouteIndex(routeIndex);
        saveRoutePreference(channel);
        refreshSettingItems();
        updateBottomInfoBar();

        if (currentChannelIndex >= 0 && currentChannelIndex < allChannelList.size()
                && allChannelList.get(currentChannelIndex) == channel) {
            engine.stopLoading();
            engine.loadUrl(channel.getWebUrl());
        }
    }

    private void scheduleHidePanel() {
        if (hidePanelTask != null) {
            mainHandler.removeCallbacks(hidePanelTask);
        }
        hidePanelTask = () -> {
            if (!hasWindowFocus()) return;
            if (channelPanelVisible) hideChannelPanel();
            else if (settingsPanelVisible) hideSettingsPanel();
        };
        mainHandler.postDelayed(hidePanelTask, PANEL_AUTO_HIDE_DELAY);
    }

    private void showBottomBar() {
        bottomInfoBar.bringToFront();
        bottomInfoBar.setVisibility(View.VISIBLE);
        if (hideBarTask != null) {
            mainHandler.removeCallbacks(hideBarTask);
        }
        hideBarTask = () -> {
            if (channelPanelVisible || settingsPanelVisible) return;
            bottomInfoBar.setVisibility(View.GONE);
        };
        mainHandler.postDelayed(hideBarTask, BAR_AUTO_HIDE_DELAY);
    }

    private void cancelBarHide() {
        if (hideBarTask != null) {
            mainHandler.removeCallbacks(hideBarTask);
        }
    }

    // ==================== 遥控器 + 键盘控制 ====================
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        int keyCode = event.getKeyCode();

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (settingsPanelVisible) {
                    if (!focusOnLevel1) {
                        focusOnLevel1 = true;
                        settingItemAdapter.setFocusedItemIndex(-1);
                        settingGroupAdapter.setFocusedGroupIndex(currentSettingGroupIndex);
                    } else {
                        hideSettingsPanel();
                    }
                    scheduleHidePanel();
                } else if (channelPanelVisible) {
                    hideChannelPanel();
                } else {
                    showChannelPanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (channelPanelVisible) {
                    hideChannelPanel();
                    showSettingsPanel();
                } else if (settingsPanelVisible) {
                    if (focusOnLevel1) {
                        focusOnLevel1 = false;
                        settingGroupAdapter.setFocusedGroupIndex(-1);
                        if (currentSettingItemIndex < 0) currentSettingItemIndex = 0;
                        settingItemAdapter.setFocusedItemIndex(currentSettingItemIndex);
                    }
                    scheduleHidePanel();
                } else {
                    showSettingsPanel();
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (channelPanelVisible) {
                    int itemCount = channelAdapter.getItemCount();
                    if (itemCount <= 0) return true;
                    int focused = channelAdapter.getFocusedPosition();
                    int prev = (focused - 1 + itemCount) % itemCount;
                    channelAdapter.setFocusedPosition(prev);
                    rvChannelList.scrollToPosition(prev);
                    scheduleHidePanel();
                } else if (settingsPanelVisible) {
                    handleSettingsNavUp();
                    scheduleHidePanel();
                } else {
                    int size = allChannelList.size();
                    int prev = (currentChannelIndex - 1 + size) % size;
                    switchChannel(prev);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (channelPanelVisible) {
                    int itemCount = channelAdapter.getItemCount();
                    if (itemCount <= 0) return true;
                    int focused = channelAdapter.getFocusedPosition();
                    int next = (focused + 1) % itemCount;
                    channelAdapter.setFocusedPosition(next);
                    rvChannelList.scrollToPosition(next);
                    scheduleHidePanel();
                } else if (settingsPanelVisible) {
                    handleSettingsNavDown();
                    scheduleHidePanel();
                } else {
                    int size = allChannelList.size();
                    int next = (currentChannelIndex + 1) % size;
                    switchChannel(next);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (channelPanelVisible
                        && channelAdapter.getFocusedPosition() >= 0) {
                    ChannelInfo ch = channelAdapter.getItem(
                            channelAdapter.getFocusedPosition());
                    if (ch != null) {
                        int idx = allChannelList.indexOf(ch);
                        if (idx >= 0) switchChannel(idx);
                    }
                    hideChannelPanel();
                } else if (settingsPanelVisible) {
                    handleSettingsConfirm();
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (channelPanelVisible) {
                    hideChannelPanel();
                } else if (settingsPanelVisible) {
                    hideSettingsPanel();
                } else {
                    finish();
                }
                return true;

            case KeyEvent.KEYCODE_MENU:
                if (settingsPanelVisible) hideSettingsPanel();
                showChannelPanel();
                return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void handleSettingsNavUp() {
        if (!focusOnLevel1) {
            int itemCount = settingItemAdapter.getItemCount();
            if (itemCount <= 0) return;
            if (currentSettingItemIndex <= 0) {
                currentSettingItemIndex = itemCount - 1;
            } else {
                currentSettingItemIndex--;
            }
            settingItemAdapter.setFocusedItemIndex(currentSettingItemIndex);
            rvSettingItems.scrollToPosition(currentSettingItemIndex);
        } else {
            int count = settingGroups.size();
            if (count <= 0) return;
            if (currentSettingGroupIndex <= 0) {
                currentSettingGroupIndex = count - 1;
            } else {
                currentSettingGroupIndex--;
            }
            selectSettingGroup(currentSettingGroupIndex);
        }
    }

    private void handleSettingsNavDown() {
        if (!focusOnLevel1) {
            int itemCount = settingItemAdapter.getItemCount();
            if (itemCount <= 0) return;
            if (currentSettingItemIndex >= itemCount - 1) {
                currentSettingItemIndex = 0;
            } else {
                currentSettingItemIndex++;
            }
            settingItemAdapter.setFocusedItemIndex(currentSettingItemIndex);
            rvSettingItems.scrollToPosition(currentSettingItemIndex);
        } else {
            int count = settingGroups.size();
            if (count <= 0) return;
            if (currentSettingGroupIndex >= count - 1) {
                currentSettingGroupIndex = 0;
            } else {
                currentSettingGroupIndex++;
            }
            selectSettingGroup(currentSettingGroupIndex);
        }
    }

    private void handleSettingsConfirm() {
        if (focusOnLevel1) {
            focusOnLevel1 = false;
            settingGroupAdapter.setFocusedGroupIndex(-1);
            if (currentSettingItemIndex < 0) currentSettingItemIndex = 0;
            settingItemAdapter.setFocusedItemIndex(currentSettingItemIndex);
            return;
        }

        SettingGroup group = (currentSettingGroupIndex >= 0
                && currentSettingGroupIndex < settingGroups.size())
                ? settingGroups.get(currentSettingGroupIndex) : null;
        if (group == null) return;

        List<SettingItemInfo> items = group.getItems();
        if (currentSettingItemIndex >= 0 && currentSettingItemIndex < items.size()) {
            handleSettingItemClick(currentSettingItemIndex, items.get(currentSettingItemIndex));
        }
    }

    // ==================== 生命周期 ====================
    @Override
    public void onBackPressed() {
        if (engine != null) {
            engine.onDestroy();
            engine = null;
        }
        super.onBackPressed();
        android.os.Process.killProcess(android.os.Process.myPid());
        Runtime.getRuntime().halt(0);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) {
            engine.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine != null) {
            engine.onResume();
        }
        enterImmersiveFullscreen();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (hidePanelTask != null) {
            mainHandler.removeCallbacks(hidePanelTask);
        }
        if (hideBarTask != null) {
            mainHandler.removeCallbacks(hideBarTask);
        }
        if (mResumeReloadTask != null) {
            mainHandler.removeCallbacks(mResumeReloadTask);
        }
        if (engine != null) {
            engine.onDestroy();
            engine = null;
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        Runtime.getRuntime().halt(0);
    }
}