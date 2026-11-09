# YSPlayer 1.5

## 项目概述

基于真实浏览器内核和官方直播网页的 Android TV 播放器，支持多线路源切换、UA 切换、解码方式切换等特性。通过内嵌的 Web 浏览器引擎加载在线视频网站，集成网页标签于频道列表，切换频道通过关闭旧频道网页打开新频道网页来实现。实现自动全屏、自动取消静音、自动播放等功能。提供遥控器控制、鼠标键盘控制、触屏控制等控制方式。最低支持安卓4.4系统，支持多平台架构（arm32、arm64、x86、x86-64）。

**核心特点：**
- **多浏览器内核**：Crosswalk 53、GeckoView 68、X5 (TBS)、System WebView，**构建工具统一环境配置（JDK 11 / AGP 7.4.2 / compileSdk 33）**
- **多线路源切换**：同一频道可配置多个备用网页源
- **多 UA 切换**：支持安卓/桌面多种 User-Agent 模式
- **两级设置列表**：右侧纵向一级标签（安卓模式/桌面模式/解码方式/线路选择）+ 左侧纵向二级动态子菜单，左右并列常驻显示，一级聚焦即刷新二级，使用 RecyclerView 实现遥控器双轴导航
- **沉浸式全屏**：视频播放时自动进入全屏沉浸模式
- **遥控器适配**：左右键分别控制频道列表和设置面板，支持遥控器/键盘/鼠标/触摸四种输入

---

## 项目结构

```
YSPlayer1.5/
├── app/                                 # 应用主模块
│   ├── build.gradle                     # 应用级构建配置（两 flavor 维度 + CLI 构建任务）
│   ├── debug.keystore                   # 调试签名密钥
│   ├── proguard-rules.pro               # 代码混淆规则（GeckoView 精确 keep）
│   ├── local-maven/                     # 本地 Maven 仓库（离线 GeckoView 依赖缓存）
│   │   └── org/mozilla/geckoview/       # GeckoView AAR 及其 POM 文件
│   └── src/
│       ├── main/                        # 公共源代码（所有 flavor 共享）
│       │   ├── AndroidManifest.xml      # 应用清单（权限、Activity 声明）
│       │   ├── java/com/ysplayer/
│       │   │   ├── App.java                # Application 入口（后台 GLES 检测、兼容性校验）
│       │   │   ├── MainActivity.java       # 主界面（频道列表、两级设置面板、引擎容器）
│       │   │   ├── WebEngine.java          # Web 引擎抽象接口
│       │   │   ├── HardBaseEngine.java     # 硬解引擎基类（IJK + ExoPlayer 桥接）
│       │   │   ├── HardWebViewManager.java # 硬解 WebView 管理器
│       │   │   ├── DeviceCapability.java   # 设备能力检测（GLES 版本/Vulkan/兼容性校验）
│       │   │   ├── UserAgentPreset.java    # UA 预设枚举（安卓模式/桌面模式分类）
│       │   │   ├── LogUtil.java            # 日志工具（BufferedWriter + 日志轮转 + Logcat）
│       │   │   ├── WrapAroundLayoutManager.java  # 频道列表循环滚动 LayoutManager
│       │   │   ├── adapter/
│       │   │   │   ├── ChannelAdapter.java        # 频道列表 RecyclerView 适配器
│       │   │   │   ├── SettingGroupAdapter.java   # 一级设置标签 RecyclerView 适配器（纵向）
│       │   │   │   └── SettingItemAdapter.java    # 二级设置项 RecyclerView 适配器（纵向）
│       │   │   └── bean/
│       │   │       ├── ChannelInfo.java       # 频道数据模型（含多线路 Route 内部类）
│       │   │       ├── SettingGroup.java      # 一级设置组数据模型
│       │   │       └── SettingItemInfo.java   # 二级设置项数据模型
│       │   └── res/                     # 资源文件
│       │       ├── drawable/            # 可绘制资源（背景、按钮样式）
│       │       ├── layout/             # 布局文件
│       │       │   ├── activity_main.xml           # 主界面布局（左右面板 + 两级设置 RecyclerView）
│       │       │   ├── item_channel.xml            # 频道列表项布局
│       │       │   ├── item_setting_group_tab.xml  # 一级设置标签项布局
│       │       │   ├── item_setting_sub_item.xml   # 二级设置子项布局（名称 + 选中标记）
│       │       │   └── video_loading_progress.xml  # 视频加载进度条
│       │       ├── mipmap-*/            # 启动图标（多密度适配）
│       │       ├── values/              # 字符串、颜色、样式、尺寸定义
│       │       └── xml/                 # 网络安全配置（debug/release 分离）
│       ├── debug/                       # Debug 构建专属资源
│       │   └── res/xml/
│       │       └── network_security_config.xml  # Debug 用（允许用户证书抓包）
│       ├── crosswalk/                   # Crosswalk 53 引擎 flavor
│       │   └── java/com/ysplayer/
│       │       ├── EngineFactory.java
│       │       └── CrosswalkWebEngine.java
│       ├── gecko68/                     # GeckoView 68 引擎 flavor
│       │   ├── assets/
│       │   │   └── gecko68-extension/
│       │   │       ├── content.js
│       │   │       └── manifest.json
│       │   └── java/com/ysplayer/
│       │       ├── EngineFactory.java
│       │       └── GeckoWebEngine.java
│       ├── ijk/                         # IJK Player 公共源码
│       │   └── java/com/ysplayer/       # 解码器、Parser、外部播放器
│       ├── system/                      # System WebView 引擎 flavor
│       │   └── java/com/ysplayer/
│       │       ├── EngineFactory.java
│       │       └── SystemWebEngine.java
│       ├── x5/                          # X5 (TBS) 引擎 flavor
│       │   ├── AndroidManifest.xml
│       │   └── java/com/ysplayer/
│       │       ├── EngineFactory.java
│       │       ├── X5CoreInitializer.java
│       │       └── X5WebEngine.java
│       ├── arm32X5/                     # X5 32位 ABI 内核 AAR
│       │   └── kernel/
│       └── arm64X5/                     # X5 64位 ABI 内核 AAR
│           └── kernel/
├── scripts/
│   ├── build_gui.py                     # Python GUI 构建工具（一键构建 + 内核自适应 JDK/AGP/compileSdk 环境 + JDK/SDK 自动下载）
│   ├── gen_icon.py                      # 启动图标生成脚本
│   └── .build_cache.json                # 构建缓存（本地生成，不入库）
├── build.gradle                         # 项目级构建配置（AGP 版本通过 -P 参数化注入）
├── settings.gradle                      # Gradle 设置（仓库配置）
├── gradle.properties                    # Gradle 全局属性
├── gradlew / gradlew.bat                # Gradle Wrapper 脚本
├── gradle/wrapper/                      # Gradle Wrapper JAR 和配置
├── LICENSE                              # 开源许可证（GPL-3.0 完整文本）
└── README.md                            # 项目说明文档
```

---

## 架构设计

### 1. 引擎抽象层（策略模式）

项目通过 `WebEngine` 接口实现了引擎的多态切换：

```
WebEngine (接口)
├── CrosswalkWebEngine  — 基于 Crosswalk 53 (Chromium)
├── GeckoWebEngine      — 基于 Mozilla GeckoView 68（firefox）
├── SystemWebEngine     — 基于 Android System WebView（google）
└── X5WebEngine         — 基于 X5 TBS（腾讯）
```

`EngineFactory.create()` 是静态工厂方法，每个 kernel flavor（crosswalk/gecko68/x5/system）有各自的实现，在编译时通过 Gradle SourceSet 机制选择正确的引擎。

### 2. Flavor 维度（两轴组合）

**维度 1 — CPU 架构 (arch)：**

| Flavor | ABI | 说明 |
|--------|-----|------|
| `arm32` | armeabi-v7a | 32位 ARM — TV 盒子主力 |
| `arm64` | arm64-v8a | 64位 ARM — 新款盒子/手机 |
| `x86` | x86 | 32位 x86 — 模拟器 |
| `x8664` | x86_64 | 64位 x86_64 — 模拟器 |

**维度 2 — 浏览器内核 (kernel)：**

| Flavor | 版本 | 最低 API | 最高 API | JDK | AGP | compileSdk | 说明 |
|--------|------|---------|---------|-----|-----|-----------|------|
| `crosswalk` | 53 | API 19 | API 25 | JDK 11 | 7.4.2 | 33 | Crosswalk 53 (Chromium) — 早期盒子备选 |
| `gecko68` | 68 | API 19 | API 28 | JDK 11 | 7.4.2 | 33 | GeckoView 68 — 老旧盒子，按架构精确匹配 ABI AAR |
| `x5` | TBS | API 19 | API 33 | JDK 11 | 7.4.2 | 33 | 腾讯 X5 (TBS) — 国内设备兼容性最佳 |
| `system` | 系统 | API 19 | API 33 | JDK 11 | 7.4.2 | 33 | System WebView — 轻量模式 |

**组合约束（构建时自动过滤）：**
- `x5` 仅支持 ARM 架构（arm32 / arm64），x86/x8664 自动跳过

### 3. UI 布局

主界面采用叠加式布局：
- **底层**：Web 引擎容器（全屏视频播放区）
- **中层**：左右透明点击区域（遥控器左右键触控）
- **上层-左**：频道列表面板（RecyclerView + WrapAroundLayoutManager 循环滚动）
- **上层-右**：两级设置面板（右侧纵向一级标签 RecyclerView + 左侧纵向二级子项 RecyclerView，左右并列常驻显示）
- **底部**：信息栏（频道名、当前线路名、操作提示）

### 4. 视频增强 JS

对网页注入了一套视频增强 JavaScript，功能包括：
- 自动取消静音并恢复音量
- CSS 强制全屏（position:fixed 覆盖整个视口）
- Shadow DOM 穿透查找 `<video>` 元素
- MutationObserver 监听动态插入的视频节点
- iframe 内视频递归扫描

---

## 构建方式

### 方式一：Python GUI 工具（推荐）

```bash
python scripts/build_gui.py
```

支持可视化选择 CPU 架构、内核版本、最低 Android 版本，自动检测或下载 JDK 和 Android SDK，一键构建。

### 方式二：Gradle 命令行

两 flavor 维度命名规则：`assemble{Arch}{Kernel}{BuildType}`

```bash
# GeckoView 68 + 32位 ARM + Release
./gradlew assembleArm32Gecko68Release

# System WebView + 32位 ARM + Release
./gradlew assembleArm32SystemRelease

# X5 + 32位 ARM + Release
./gradlew assembleArm32X5Release

# 完整命名规则: assemble{Arch}{Kernel}{BuildType}
# Arch:    Arm32, Arm64, X86, X8664
# Kernel:  Crosswalk, Gecko68, X5, System
# BuildType: Debug, Release
```

### 方式三：CLI 构建任务（customBuild）

```bash
# 参数化构建，自动应用 minSdk/maxSdk 约束

# 默认使用 AGP 7.4.2 + compileSdk 33，无需额外参数
./gradlew customBuild -Papi=21 -Pkernel=gecko68 -Parch=arm32 -PbuildType=release
```

输出 APK 命名格式：`YSPlayer_{KERNEL}_{buildType}-{ARCH}.apk`

例如：`YSPlayer_GECKO68_release-arm32.apk`

---

## 环境依赖

### 自动管理（推荐）

`scripts/build_gui.py` 构建工具会自动匹配环境配置，无需手动操作：

- **JDK**：支持三种方式获取
  - **浏览** — 用户自由选择任意 JDK 目录（含 `bin/javac.exe` 即可）
  - **检测** — 自动扫描注册表及常见安装路径，列出所有可用 JDK
  - **下载** — 一键下载 Temurin JDK（8 / 11 / 17）到项目 `tools/app/JDK/` 目录
- **Android SDK**：自动检测或一键下载 Android SDK（含 Build Tools）
- **AGP**：命令行 `-P` 参数化注入，所有内核统一使用 AGP 7.4.2
- **compileSdk**：命令行 `-P` 参数化注入，所有内核统一使用 33

### 手动构建环境

| 内核 | JDK | AGP | compileSdk | Gradle |
|------|-----|-----|-----------|--------|
| crosswalk / gecko68 / x5 / system | JDK 11 | 7.4.2 | 33 | 8.7 |

- **Python**：3.8+（仅 `scripts/build_gui.py` 需要，Gradle 构建不需要）

---

## APK 安装与运行

1. 将 APK 安装到 TV 盒子或手机
2. 首次启动会初始化浏览器引擎（需等待数秒解压 native 库）
3. 启动过程中自动执行图形 API 兼容性检测，版本不匹配时输出警告日志
4. 测试版日志文件保存在：`/sdcard/YSPlayerLogs/ysplayer_*.log`（最多保留 5 个，自动轮转）

---

## 安全配置

| 构建类型 | 网络安全策略 |
|---------|-------------|
| Release | 仅信任系统 CA 证书，明文 HTTP 按 `network_security_config` 白名单放行 |
| Debug | 额外信任用户安装的 CA 证书（便于抓包调试） |

Release 签名支持通过环境变量注入正式密钥：
- `RELEASE_KEYSTORE` — 签名文件路径
- `RELEASE_KEYSTORE_PASSWORD` — 签名密码
- `RELEASE_KEY_ALIAS` — 密钥别名
- `RELEASE_KEY_PASSWORD` — 密钥密码

---

## 项目版本

- **应用版本**：1.5.0 (versionCode: 动态生成 = 主版本号x1亿 + 秒级时间戳后8位，确保新构建可覆盖旧版本)
- **构建工具版本**：v1.5.0 (scripts/build_gui.py)
- **编译 SDK**：所有内核统一使用 API 33
- **内核版本与 API 范围**：
  - gecko68: API 19 ~ 28
  - crosswalk: API 19 ~ 25
  - x5 / system: API 19 ~ 33

---

## 链接

- **本项目地址**：
  - [YSPlayer123](https://github.com/muxiaohu521/YSPlayer123)
- **参考资源地址**：
  - [GeckoView 68](https://github.com/mozilla/geckoview)
  - [Crosswalk 53](https://github.com/crosswalk-project/crosswalk)
  - [X5 TBS](https://x5.tencent.com/)
  - [Android System WebView](https://developer.android.google.cn/reference/android/webkit/WebView)
- **app下载地址**：蓝奏云https://wwatk.lanzouv.com/b016l8evib 密码:3u55（安卓4.4，arm32）

---

## 开源许可证

本项目采用 **GNU General Public License v3.0 (GPL-3.0)** 开源，许可证文本见项目根目录 [LICENSE](LICENSE)。
