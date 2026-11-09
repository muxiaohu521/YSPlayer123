#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
YSPlayer 构建工具 (GUI)
- 多内核支持: Crosswalk/X5/Gecko68/System WebView
- 构建时选择内核与 Android 版本, 非运行时降级
- minSdk 来自用户选择的 Android 版本, targetSdk 来自 compileSdk (封顶 33)
- KERNELS.minSdk/maxSdk 仅用于 GUI 下拉框过滤, 不参与打包参数
- 自动检测 / 联网下载 JDK & Android SDK（支持手动浏览选择）
"""

import tkinter as tk
from tkinter import ttk, filedialog, messagebox, scrolledtext
import subprocess
import tempfile
import threading
import textwrap
import os
import sys
import re
import json
import queue
import zipfile
import shutil
import ssl
import urllib.request
import urllib.error

VERSION = "1.5.0"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
OUTPUT_DIR = os.path.join(PROJECT_ROOT, 'app', 'build', 'outputs', 'apk')
GRADLEW = os.path.join(PROJECT_ROOT, 'gradlew.bat')
CACHE_FILE = os.path.join(PROJECT_ROOT, 'scripts', '.build_cache.json')

JDK_DOWNLOAD_URL = "https://api.adoptium.net/v3/assets/latest/{version}/hotspot?os=windows&architecture=x64&image_type=jdk"
SDK_DOWNLOAD_URL = "https://dl.google.com/android/repository/commandlinetools-win-{version}_latest.zip"
SDK_DEFAULT_VERSION = "11076708"

VARIANTS = {
    "arm32": {"desc": "32位 (armeabi-v7a)", "minSdk": 19, "abiLabel": "Android4.4", "flavor": "arm32"},
    "arm64": {"desc": "64位 (arm64-v8a)", "minSdk": 21, "abiLabel": "Android5.0", "flavor": "arm64"},
    "x86":   {"desc": "x86 32位 — 模拟器专用", "minSdk": 19, "abiLabel": "Android4.4", "flavor": "x86"},
    "x86_64":{"desc": "x86_64 64位 — 模拟器专用", "minSdk": 21, "abiLabel": "Android5.0", "flavor": "x8664"},
}

KERNELS = {

    "crosswalk":{"desc": "Chromium 53 (2016) ，支持安卓 4.4~7",
                 "minSdk": 19, "maxSdk": 25, "label": "Crosswalk", "jdk": 11,
                 "agp": "7.4.2", "compileSdk": 33, "javaVersion": "11"},
    "gecko68":  {"desc": "Firefox 68 (2019) , 支持安卓 4.4~9",
                 "minSdk": 19, "maxSdk": 28, "label": "GeckoView 68", "jdk": 11,
                 "agp": "7.4.2", "compileSdk": 33, "javaVersion": "11"},
    "x5":       {"desc": "腾讯 x5 - TBS (2023), 支持安卓 4.4~13",
                 "minSdk": 19, "maxSdk": 33, "label": "X5 (TBS)", "jdk": 11,
                 "agp": "7.4.2", "compileSdk": 33, "javaVersion": "11"},
    "system":   {"desc": "系统原生 WebView,支持安卓 4.4~13",
                 "minSdk": 19, "maxSdk": 33, "label": "System WebView", "jdk": 11,
                 "agp": "7.4.2", "compileSdk": 33, "javaVersion": "11"},

}

ANDROID_VERSIONS = {
    "4.4":  {"api": 19, "label": "Android 4.4 (API 19)"},
    "5.0":  {"api": 21, "label": "Android 5.0 (API 21)"},
    "6.0":  {"api": 23, "label": "Android 6.0 (API 23)"},
    "7.0":  {"api": 24, "label": "Android 7.0 (API 24)"},
    "8.0":  {"api": 26, "label": "Android 8.0 (API 26)"},
    "9.0":  {"api": 28, "label": "Android 9.0 (API 28)"},
    "10.0": {"api": 29, "label": "Android 10 (API 29)"},
    "11.0": {"api": 30, "label": "Android 11 (API 30)"},
    "12.0": {"api": 31, "label": "Android 12 (API 31)"},
    "13.0": {"api": 33, "label": "Android 13 (API 33)"},
}

DEFAULT_VERSION = "5.0"


def find_jdk():
    candidates = []
    env_home = os.environ.get('JAVA_HOME', '')
    if env_home:
        candidates.append(env_home)
    try:
        import winreg
        for root in (winreg.HKEY_LOCAL_MACHINE, winreg.HKEY_CURRENT_USER):
            for sub in (r"SOFTWARE\JavaSoft\JDK", r"SOFTWARE\JavaSoft\Java Development Kit"):
                try:
                    key = winreg.OpenKey(root, sub)
                    i = 0
                    while True:
                        try:
                            name = winreg.EnumKey(key, i)
                            subkey = winreg.OpenKey(root, sub + "\\" + name)
                            jh, _ = winreg.QueryValueEx(subkey, "JavaHome")
                            candidates.append(jh)
                            winreg.CloseKey(subkey)
                        except OSError:
                            break
                        i += 1
                    winreg.CloseKey(key)
                except OSError:
                    pass
    except Exception:
        pass

    search_roots = [
        os.environ.get('ProgramFiles', 'C:\\Program Files'),
        os.environ.get('ProgramFiles(x86)', 'C:\\Program Files (x86)'),
        os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Programs'),
        'C:\\Program Files\\Android\\Android Studio\\jbr',
        'C:\\Program Files\\Eclipse Adoptium',
        'C:\\Program Files\\Java',
        os.path.join(SCRIPT_DIR, 'tools', 'jdk-11'),
        os.path.join(SCRIPT_DIR, '..', 'tools', 'app', 'JDK'),
    ]
    for root in search_roots:
        if not os.path.isdir(root):
            continue
        for dirpath, dirs, _ in os.walk(root):
            depth = dirpath.replace(root, '').count(os.sep)
            if depth > 5:
                dirs.clear()
                continue
            if os.path.exists(os.path.join(dirpath, 'bin', 'javac.exe')):
                candidates.append(dirpath)

    seen = set()
    result = []
    for c in candidates:
        c = os.path.normpath(c)
        if c not in seen and os.path.isdir(c):
            release = os.path.join(c, 'release')
            vers = _parse_java_release(release)
            javac = os.path.join(c, 'bin', 'javac.exe')
            java = os.path.join(c, 'bin', 'java.exe')
            if os.path.isfile(javac) and os.path.isfile(java):
                seen.add(c)
                result.append((c, vers or "?"))
    result.sort(key=lambda x: _version_key(x[1]), reverse=True)
    return result


def _parse_java_release(path):
    if not os.path.isfile(path):
        return None
    try:
        with open(path, 'r', encoding='utf-8') as f:
            for line in f:
                m = re.search(r'JAVA_VERSION="([^"]+)"', line)
                if m:
                    return m.group(1).replace('_', '.')
    except Exception:
        pass
    return None


def _get_jdk_major_version(jdk_path):
    release = os.path.join(jdk_path, 'release')
    vers = _parse_java_release(release)
    if vers:
        if vers.startswith('1.8'):
            return 8
        m = re.match(r'(\d+)', vers)
        if m:
            return int(m.group(1))
    dirname = os.path.basename(jdk_path)
    m = re.match(r'(\d+)', dirname)
    if m:
        return int(m.group(1))
    return None


def _resolve_jdk_root(jdk_path, required_version=None):
    if not jdk_path or not os.path.isdir(jdk_path):
        return None
    if os.path.isfile(os.path.join(jdk_path, 'bin', 'javac.exe')):
        return jdk_path
    best = None
    best_ver = None
    try:
        for name in os.listdir(jdk_path):
            sub = os.path.join(jdk_path, name)
            if not os.path.isdir(sub):
                continue
            if not os.path.isfile(os.path.join(sub, 'bin', 'javac.exe')):
                continue
            major = _get_jdk_major_version(sub)
            if major is None:
                continue
            if required_version is not None and major == required_version:
                return sub
            if best_ver is None or major > best_ver:
                best = sub
                best_ver = major
    except OSError:
        pass
    return best


def _version_key(v):
    try:
        parts = v.split('.')
        return tuple(int(x) for x in parts if x.isdigit())
    except Exception:
        return (-1,)


def find_android_sdk():
    candidates = []

    env_home = os.environ.get('ANDROID_HOME', '')
    if env_home:
        candidates.append(env_home)

    common = [
        os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk'),
        os.path.join(os.environ.get('APPDATA', ''), 'Android', 'Sdk'),
    ]
    for p in common:
        if p not in candidates:
            candidates.append(p)

    # 项目本地 tools 目录
    candidates.append(os.path.join(PROJECT_ROOT, 'tools', 'android-sdk'))

    # 上级工具链目录
    candidates.append(os.path.normpath(os.path.abspath(
        os.path.join(PROJECT_ROOT, '..', 'tools', 'app', 'android-sdk'))))

    result = []
    seen = set()
    for c in candidates:
        c = os.path.normpath(c)
        if c in seen or not os.path.isdir(c):
            continue
        adb = os.path.join(c, 'platform-tools', 'adb.exe')
        sdk_mgr = os.path.join(c, 'cmdline-tools', 'latest', 'bin', 'sdkmanager.bat')
        platforms_dir = os.path.join(c, 'platforms')
        has_platforms = os.path.isdir(platforms_dir) and len(os.listdir(platforms_dir)) > 0
        if os.path.isfile(adb) or os.path.isfile(sdk_mgr):
            seen.add(c)
            build_tools = _find_build_tools(c)
            platforms = _list_platforms(c)
            result.append((c, build_tools, platforms, has_platforms))
    return result


def _list_platforms(sdk_root):
    path = os.path.join(sdk_root, 'platforms')
    if not os.path.isdir(path):
        return "无"
    vers = sorted(
        [d.replace('android-', '') for d in os.listdir(path)
         if os.path.isdir(os.path.join(path, d)) and d.startswith('android-')],
        key=lambda x: int(x) if x.isdigit() else 0)
    return ", ".join(vers) if vers else "无"


def _get_available_platform_apis(sdk_root):
    """返回 SDK 中已安装的 platform API 列表 (int 集合)。"""
    path = os.path.join(sdk_root, 'platforms')
    if not os.path.isdir(path):
        return set()
    result = set()
    for d in os.listdir(path):
        if d.startswith('android-'):
            try:
                api = int(d.replace('android-', ''))
                result.add(api)
            except ValueError:
                pass
    return result


def _find_build_tools(sdk_root):
    path = os.path.join(sdk_root, 'build-tools')
    if not os.path.isdir(path):
        return "无"
    vers = [d for d in os.listdir(path) if os.path.isdir(os.path.join(path, d))]
    vers.sort(reverse=True)
    return ", ".join(vers[:3]) if vers else "无"


def download_jdk(version, dest_parent, progress_cb=None):
    os.makedirs(dest_parent, exist_ok=True)
    tmp_dir = os.path.join(dest_parent, 'tmp_extract')
    zip_path = os.path.join(dest_parent, f'jdk-{version}.zip')
    final_path = os.path.join(dest_parent, str(version))

    api_url = JDK_DOWNLOAD_URL.format(version=version)
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    if progress_cb:
        progress_cb(0, "正在查询 JDK 下载地址...")

    req = urllib.request.Request(api_url, headers={'User-Agent': 'Mozilla/5.0'})
    resp = urllib.request.urlopen(req, context=ctx, timeout=30)
    data = json.loads(resp.read().decode('utf-8'))

    dl_url = data[0]['binaries'][0]['package']['link']
    total_size = data[0]['binaries'][0]['package'].get('size', 0)

    if progress_cb:
        progress_cb(1, f"正在下载 JDK {version} ({total_size//1024//1024}MB)...")

    req = urllib.request.Request(dl_url, headers={'User-Agent': 'Mozilla/5.0'})
    resp = urllib.request.urlopen(req, context=ctx, timeout=600)
    with open(zip_path, 'wb') as f:
        downloaded = 0
        while True:
            chunk = resp.read(65536)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            if progress_cb and total_size:
                progress_cb(int(100 * downloaded / total_size),
                            f"下载中... {downloaded//1024//1024}MB / {total_size//1024//1024}MB")

    if progress_cb:
        progress_cb(100, "解压中...")

    if os.path.exists(tmp_dir):
        shutil.rmtree(tmp_dir, ignore_errors=True)
    os.makedirs(tmp_dir, exist_ok=True)

    with zipfile.ZipFile(zip_path, 'r') as zf:
        zf.extractall(tmp_dir)

    os.remove(zip_path)

    for d in os.listdir(tmp_dir):
        full = os.path.join(tmp_dir, d)
        if os.path.isdir(full) and os.path.isfile(os.path.join(full, 'bin', 'javac.exe')):
            if os.path.exists(final_path):
                shutil.rmtree(final_path, ignore_errors=True)
            shutil.move(full, final_path)
            shutil.rmtree(tmp_dir, ignore_errors=True)
            return final_path

    shutil.rmtree(tmp_dir, ignore_errors=True)
    if os.path.isfile(os.path.join(dest_parent, 'bin', 'javac.exe')):
        return dest_parent
    return None


def download_android_sdk(dest_parent, progress_cb=None):
    os.makedirs(dest_parent, exist_ok=True)
    cmdline_dir = os.path.join(dest_parent, 'cmdline-tools', 'latest')
    zip_path = os.path.join(dest_parent, 'sdk-tools.zip')

    url = SDK_DOWNLOAD_URL.format(version=SDK_DEFAULT_VERSION)
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE

    if progress_cb:
        progress_cb(0, "正在下载 Android SDK 命令行工具...")

    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        resp = urllib.request.urlopen(req, context=ctx, timeout=600)
        with open(zip_path, 'wb') as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)
    except Exception:
        alt_url = "https://dl.google.com/android/repository/commandlinetools-win-9477386_latest.zip"
        req = urllib.request.Request(alt_url, headers={'User-Agent': 'Mozilla/5.0'})
        resp = urllib.request.urlopen(req, context=ctx, timeout=600)
        with open(zip_path, 'wb') as f:
            while True:
                chunk = resp.read(65536)
                if not chunk:
                    break
                f.write(chunk)

    if progress_cb:
        progress_cb(80, "解压 SDK 工具...")

    if os.path.exists(cmdline_dir):
        shutil.rmtree(cmdline_dir, ignore_errors=True)
    os.makedirs(cmdline_dir, exist_ok=True)

    with zipfile.ZipFile(zip_path, 'r') as zf:
        members = [m for m in zf.namelist() if m.startswith('cmdline-tools/')]
        for member in members:
            target = os.path.join(cmdline_dir, os.path.relpath(member, 'cmdline-tools'))
            if member.endswith('/'):
                os.makedirs(target, exist_ok=True)
            else:
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with zf.open(member) as src, open(target, 'wb') as dst:
                    dst.write(src.read())

    os.remove(zip_path)
    os.environ['ANDROID_HOME'] = dest_parent
    return dest_parent


def load_cache():
    if os.path.isfile(CACHE_FILE):
        try:
            with open(CACHE_FILE, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def save_cache(data):
    try:
        with open(CACHE_FILE, 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
    except Exception:
        pass


class BuildRunner(threading.Thread):
    def __init__(self, jdk_home, sdk_root, variant, kernel, build_type,
                 clean_first, android_version, android_api,
                 agp_version="7.4.2", compile_sdk=33, java_version="11",
                 log_cb=None, done_cb=None, progress_cb=None, cmd=None):
        super().__init__(daemon=True)
        self.jdk_home = jdk_home
        self.sdk_root = sdk_root
        self.variant = variant
        self.kernel = kernel
        self.build_type = build_type
        self.clean_first = clean_first
        self.android_version = android_version
        self.android_api = android_api
        self.agp_version = agp_version
        self.compile_sdk = compile_sdk
        self.java_version = java_version
        # 目标 SDK:
        # - API 34+ 触发原生库安全策略 → 全局封顶 33
        # - API 29+ 触发 JNI 隐藏字段拦截 (max-target-p) → Gecko 内核封顶 28
        #   (GeckoView native 代码访问 Boolean/Integer/Long/Double.value 字段)
        _jni_cap = 28 if kernel == 'gecko68' else 99
        self.target_sdk = min(compile_sdk, _jni_cap, 33)

        self.log_cb = log_cb
        self.done_cb = done_cb
        self.progress_cb = progress_cb
        self.cmd = cmd
        self._cancel = False

    def cancel(self):
        self._cancel = True
        if hasattr(self, '_proc') and self._proc:
            try:
                self._proc.kill()
            except Exception:
                pass

    def run(self):
        if self.cmd:
            self._run_custom_cmd(self.cmd)
            return

        env = os.environ.copy()
        env['JAVA_HOME'] = self.jdk_home
        if self.sdk_root:
            env['ANDROID_HOME'] = self.sdk_root

        env['PATH'] = os.pathsep.join([
            os.path.join(self.jdk_home, 'bin'),
            os.path.join(self.sdk_root, 'platform-tools') if self.sdk_root else '',
            env.get('PATH', ''),
        ])

        arch_cap = VARIANTS.get(self.variant, {}).get('flavor', self.variant).capitalize()
        kernel_cap = self.kernel.capitalize()
        type_cap = self.build_type.capitalize()
        task = "assemble{}{}{}".format(arch_cap, kernel_cap, type_cap)

        gradle_args = [GRADLEW, '-PandroidApi={}'.format(self.android_api),
                       '-PagpVersion={}'.format(self.agp_version),
                       '-PcompileSdkVersion={}'.format(self.compile_sdk),
                       '-PtargetSdkVersion={}'.format(self.target_sdk),
                       '-PjavaVersion={}'.format(self.java_version)]

        # ---- 清理 Gradle 本地增量编译缓存（不触碰依赖/云端缓存） ----
        if self.log_cb:
            self.log_cb(">>> 清理 Gradle 构建缓存...", "info")

        # 1) 杀掉所有 Gradle daemon 进程（清除内存中的增量编译状态）
        try:
            subprocess.run(
                [GRADLEW, '--stop'],
                cwd=PROJECT_ROOT, env=env,
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                timeout=10)
        except Exception:
            pass

        # 2) --no-build-cache 跳过任务输出缓存读取，强制重新编译（只影响编译缓存，不影响依赖下载）
        gradle_args.append('--no-build-cache')

        # 3) --no-daemon 每次启动新 JVM，避免 daemon 残留状态（与手动构建命令一致）
        gradle_args.append('--no-daemon')

        if self.log_cb:
            self.log_cb(">>> 缓存清理完毕，开始构建...", "info")
            cmd_preview = ' '.join(gradle_args)
            self.log_cb(f">>> 实际执行: {cmd_preview} [clean/assemble]", "info")

        cmds = []
        if self.clean_first:
            cmds.append(('clean', gradle_args + ['clean']))
        cmds.append((task, gradle_args + [task]))

        if self.log_cb:
            self.log_cb("=" * 60, "header")
            kernel_desc = KERNELS.get(self.kernel, {}).get('desc', self.kernel)
            self.log_cb(f"YSPlayer 构建工具 v{VERSION}  |  内核: {self.kernel}", "header")
            self.log_cb(f"JDK:    {self.jdk_home}", "header")
            self.log_cb(f"SDK:    {self.sdk_root or '(detect)'}", "header")
            self.log_cb(f"AGP:    {self.agp_version}  |  compileSdk: {self.compile_sdk}  (targetSdk: {self.target_sdk})  |  Java: {self.java_version}", "header")
            self.log_cb(f"变体:   {self.variant} ({VARIANTS.get(self.variant, {}).get('desc', '')})", "header")
            self.log_cb(f"内核:   {self.kernel} — {kernel_desc}", "header")
            self.log_cb(f"类型:   {self.build_type}", "header")
            self.log_cb(f"Android:  {self.android_version} (minSdk={self.android_api}  targetSdk={self.target_sdk})", "header")
            self.log_cb(f"任务:   {task}", "header")
            self.log_cb("=" * 60, "header")

        phase1_output = []

        def _phase_log(msg, tag="normal"):
            if self.log_cb:
                self.log_cb(msg, tag)
            phase1_output.append(msg)

        success = self._execute_commands(
            env, gradle_args, cmds, len(cmds),
            log_cb=_phase_log, offline=True)

        if success:
            self._show_build_result(True, "构建完成 (本地)")
            return

        if self._cancel:
            self.done_cb(False, "已取消")
            return

        failed_output = '\n'.join(phase1_output[-200:]).upper()
        is_dep_failure = any(kw in failed_output for kw in (
            'COULD NOT RESOLVE', 'COULD NOT GET RESOURCE',
            'COULD NOT HEAD', 'CONNECTION REFUSED',
            'PERMISSION DENIED: CONNECT', 'UNKNOWN HOST',
            'SOCKET TIMEOUT', 'READ TIMED OUT'))

        if not is_dep_failure:
            self.done_cb(False, "构建失败 (离线模式)")
            return

        if self.log_cb:
            self.log_cb("")
            self.log_cb("=" * 60, "header")
            self.log_cb("本地缓存不足，自动切换到在线模式重试...", "info")
            self.log_cb("=" * 60, "header")

        success = self._execute_commands(
            env, gradle_args, cmds, len(cmds),
            log_cb=self.log_cb, offline=False)

        if success:
            self._show_build_result(True, "构建完成 (在线)")

    def _show_build_result(self, success, msg):
        arch_label = VARIANTS.get(self.variant, {}).get('flavor', self.variant)
        kernel_label = self.kernel.upper()

        expected_file = "YSPlayer_{}_{}-{}.apk".format(
            kernel_label, self.build_type, arch_label)
        variant_dir = "{}{}".format(arch_label, self.kernel.capitalize())
        apk_flavor_dir = os.path.join(OUTPUT_DIR, variant_dir, self.build_type)
        apk_path = os.path.join(apk_flavor_dir, expected_file)

        if self.log_cb:
            self.log_cb("=" * 60, "header")
            if success and os.path.isfile(apk_path):
                size_mb = os.path.getsize(apk_path) / 1024 / 1024
                self.log_cb(f"[成功] 构建完成!", "success")
                self.log_cb(f"  文件: {apk_path}", "success")
                self.log_cb(f"  大小: {size_mb:.1f} MB", "success")
            elif success:
                self.log_cb(f"[注意] APK 未在预期位置: {apk_path}", "warning")
                self.log_cb(f"  请检查 {apk_flavor_dir} 目录", "warning")
            self.log_cb("=" * 60, "header")

        self.done_cb(success, msg)

    def _run_custom_cmd(self, cmd):
        if self.log_cb:
            self.log_cb(f"执行: {' '.join(cmd)}", "header")

        proc = None
        try:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding='utf-8', errors='replace',
                cwd=PROJECT_ROOT)
            self._proc = proc
            for line in proc.stdout:
                if self._cancel:
                    proc.kill()
                    break
                if self.log_cb:
                    tag = 'normal'
                    stripped = line.rstrip()
                    if any(kw in stripped.lower() for kw in
                           ('error', 'traceback', 'exception')):
                        tag = 'error'
                    elif any(kw in stripped.lower() for kw in
                             ('warning',)):
                        tag = 'warning'
                    self.log_cb(line.rstrip(), tag)
            proc.wait()
            if self._cancel:
                if self.log_cb:
                    self.log_cb("已取消", "warning")
                if self.done_cb:
                    self.done_cb(False, "已取消")
            else:
                rc = proc.returncode
                if rc == 0:
                    if self.log_cb:
                        self.log_cb(f"\n执行成功 (退出码: {rc})", "success")
                    if self.done_cb:
                        self.done_cb(True, "执行成功")
                else:
                    if self.log_cb:
                        self.log_cb(f"\n执行失败 (退出码: {rc})", "error")
                    if self.done_cb:
                        self.done_cb(False, f"退出码: {rc}")
        except Exception as e:
            if self.log_cb:
                self.log_cb(f"执行异常: {e}", "error")
            if self.done_cb:
                self.done_cb(False, str(e))
        finally:
            self._proc = None

    def _execute_commands(self, env, gradle_args, cmds, total,
                          log_cb=None, offline=True):
        if log_cb is None:
            log_cb = self.log_cb

        for idx, (name, cmd) in enumerate(cmds):
            if self._cancel:
                if log_cb:
                    log_cb("\n[取消] 用户中断", "error")
                self.done_cb(False, "用户取消")
                return False

            if log_cb:
                tag_suffix = " [离线]" if offline else " [本地优先]"
                log_cb(f"\n>>> 执行: {name} ({idx+1}/{total}){tag_suffix}", "info")

            actual_cmd = list(cmd)
            if offline:
                actual_cmd.append('--offline')

            try:
                proc = subprocess.Popen(
                    actual_cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                    text=True,
                    encoding='utf-8',
                    errors='replace',
                    env=env,
                    cwd=PROJECT_ROOT,
                    bufsize=1,
                )
                self._proc = proc

                q = queue.Queue()

                def _read_stdout():
                    try:
                        for line in iter(proc.stdout.readline, ''):
                            q.put(line)
                    except Exception:
                        pass
                    finally:
                        q.put(None)

                reader = threading.Thread(target=_read_stdout, daemon=True)
                reader.start()

                batch = []

                def _flush():
                    nonlocal batch
                    if batch and log_cb:
                        log_cb('\n'.join(batch), 'normal')
                        batch = []

                while True:
                    try:
                        line = q.get(timeout=0.3)
                    except queue.Empty:
                        if self._cancel:
                            try:
                                proc.kill()
                            except Exception:
                                pass
                            reader.join(timeout=2)
                            _flush()
                            if log_cb:
                                log_cb("\n[取消] 用户中断", "warning")
                            self.done_cb(False, "用户取消")
                            return False
                        _flush()
                        continue

                    if line is None:
                        _flush()
                        break

                    line = line.rstrip()
                    if not line:
                        continue

                    tag = "normal"
                    upper = line.upper()
                    if any(k in upper for k in ("ERROR", "FAILED", "EXCEPTION", "BUILD FAILED")):
                        tag = "error"
                    elif any(k in upper for k in ("WARNING", "WARN")):
                        tag = "warning"
                    elif any(k in upper for k in ("SUCCESS", "BUILD SUCCESSFUL")):
                        tag = "success"

                    batch.append(line)
                    if len(batch) >= 20 or tag != "normal":
                        _flush()

                _flush()
                reader.join(timeout=2)
                proc.wait()

                if proc.returncode != 0:
                    if log_cb:
                        log_cb(f"\n[失败] {name} 返回码: {proc.returncode}", "error")
                    if not offline:
                        self.done_cb(False, f"{name} 失败")
                    return False

            except Exception as e:
                if log_cb:
                    log_cb(f"\n[异常] {e}", "error")
                if not offline:
                    self.done_cb(False, str(e))
                return False

            if self.progress_cb:
                self.progress_cb(int(100 * (idx + 1) / total))

        return True


class BuildGUI(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"YSPlayer 构建工具 v{VERSION}")
        self.geometry("800x700")
        self.minsize(680, 520)
        self.resizable(True, True)

        self.cache = load_cache()
        self.jdk_path = tk.StringVar(value=os.path.normpath(self.cache.get('jdk_path', '')))
        self.sdk_path = tk.StringVar(value=os.path.normpath(self.cache.get('sdk_path', '')))
        self.version_var = tk.StringVar(value=self.cache.get('version', DEFAULT_VERSION))
        self.variant_var = tk.StringVar(value=self.cache.get('variant', 'arm32'))
        self.kernel_var = tk.StringVar(value=self.cache.get('kernel', 'gecko68'))
        self.build_type_var = tk.StringVar(value=self.cache.get('build_type', 'release'))
        self.clean_var = tk.BooleanVar(value=False)

        self.runner = None
        self._updating = False
        self._available_apis = set()
        self._build_ui()
        self._auto_detect()

    def _build_ui(self):
        main_frame = ttk.Frame(self, padding=10)
        main_frame.pack(fill=tk.BOTH, expand=True)

        # ===== JAVA 环境面板 =====
        self.java_env_frame = ttk.LabelFrame(
            main_frame, text="JAVA 开发环境", padding=8)

        row0 = ttk.Frame(self.java_env_frame)
        row0.pack(fill=tk.X, pady=2)
        ttk.Label(row0, text="JDK (Java 开发工具包)", width=24).pack(side=tk.LEFT)
        ttk.Entry(row0, textvariable=self.jdk_path).pack(
            side=tk.LEFT, fill=tk.X, expand=True, padx=3)
        ttk.Button(row0, text="浏览", width=5,
                   command=self._browse_jdk).pack(side=tk.LEFT, padx=1)
        ttk.Button(row0, text="检测", width=5,
                   command=self._detect_jdk).pack(side=tk.LEFT, padx=1)
        ttk.Button(row0, text="下载JDK8", width=10,
                   command=lambda: self._dl_jdk(8)).pack(side=tk.LEFT, padx=1)
        ttk.Button(row0, text="下载JDK11", width=10,
                   command=lambda: self._dl_jdk(11)).pack(side=tk.LEFT, padx=1)
        ttk.Button(row0, text="下载JDK17", width=10,
                   command=lambda: self._dl_jdk(17)).pack(side=tk.LEFT, padx=1)

        row1 = ttk.Frame(self.java_env_frame)
        row1.pack(fill=tk.X, pady=2)
        ttk.Label(row1, text="Android SDK", width=24).pack(side=tk.LEFT)
        ttk.Entry(row1, textvariable=self.sdk_path).pack(
            side=tk.LEFT, fill=tk.X, expand=True, padx=3)
        ttk.Button(row1, text="浏览", width=5,
                   command=self._browse_sdk).pack(side=tk.LEFT, padx=1)
        ttk.Button(row1, text="检测", width=5,
                   command=self._detect_sdk).pack(side=tk.LEFT, padx=1)
        ttk.Button(row1, text="下载SDK", width=10,
                   command=self._dl_sdk).pack(side=tk.LEFT, padx=1)

        # 状态标签
        status = ttk.Frame(main_frame)
        status.pack(fill=tk.X, pady=(3, 0))
        self.env_status = ttk.Label(status, text="", foreground="gray", wraplength=650)
        self.env_status.pack(side=tk.LEFT, fill=tk.X, expand=True)

        # ===== 构建选项 =====
        self.opt_frame = ttk.LabelFrame(main_frame, text="构建选项", padding=8)
        self.opt_frame.pack(fill=tk.X, pady=(0, 8))

        ver_row = ttk.Frame(self.opt_frame)
        ver_row.pack(fill=tk.X, pady=2)
        self.opt_ver_label = ttk.Label(ver_row, text="Android 版本", width=24)
        self.opt_ver_label.pack(side=tk.LEFT)
        self.version_combo = ttk.Combobox(
            ver_row, textvariable=self.version_var,
            values=list(ANDROID_VERSIONS.keys()), state='readonly', width=10)
        self.version_combo.pack(side=tk.LEFT, padx=3)
        self.version_combo.bind('<<ComboboxSelected>>',
                                lambda e: self._on_version_change())
        self.version_api_label = ttk.Label(ver_row, text="", foreground="#6a9955", font=("", 8))
        self.version_api_label.pack(side=tk.LEFT, padx=4)
        self.version_hint = ttk.Label(self.opt_frame, text="", foreground="gray", font=("", 8),
                                       wraplength=600)
        self.version_hint.pack(fill=tk.X, padx=(27, 0), pady=(0, 2))

        var_row = ttk.Frame(self.opt_frame)
        var_row.pack(fill=tk.X, pady=2)
        self.opt_var_label = ttk.Label(
            var_row, text="构建变体 (ABI)", width=24)
        self.opt_var_label.pack(side=tk.LEFT)
        self.variant_combo = ttk.Combobox(
            var_row, textvariable=self.variant_var,
            values=[], state='readonly', width=30)
        self.variant_combo.pack(side=tk.LEFT, padx=3)
        self.variant_combo.bind('<<ComboboxSelected>>',
                                lambda e: self._on_variant_change())
        self.variant_hint = ttk.Label(self.opt_frame, text="", foreground="gray", font=("", 8),
                                          wraplength=600)
        self.variant_hint.pack(fill=tk.X, padx=(27, 0), pady=(0, 2))

        kernel_row = ttk.Frame(self.opt_frame)
        kernel_row.pack(fill=tk.X, pady=2)
        self.opt_kernel_label = ttk.Label(
            kernel_row, text="浏览器内核", width=24)
        self.opt_kernel_label.pack(side=tk.LEFT)
        self.kernel_combo = ttk.Combobox(
            kernel_row, textvariable=self.kernel_var,
            values=list(KERNELS.keys()), state='readonly', width=30)
        self.kernel_combo.pack(side=tk.LEFT, padx=3)
        self.kernel_combo.bind('<<ComboboxSelected>>',
                                lambda e: self._on_kernel_change())
        self.kernel_hint = ttk.Label(self.opt_frame, text="", foreground="gray", font=("", 8),
                                         wraplength=600)
        self.kernel_hint.pack(fill=tk.X, padx=(27, 0), pady=(0, 2))

        bt_row = ttk.Frame(self.opt_frame)
        bt_row.pack(fill=tk.X, pady=2)
        self.opt_type_label = ttk.Label(bt_row, text="构建类型", width=24)
        self.opt_type_label.pack(side=tk.LEFT)
        self.build_type_rb1 = ttk.Radiobutton(
            bt_row, text="Release (正式版)",
            variable=self.build_type_var, value='release')
        self.build_type_rb1.pack(side=tk.LEFT, padx=5)
        self.build_type_rb2 = ttk.Radiobutton(
            bt_row, text="Debug (调试版)",
            variable=self.build_type_var, value='debug')
        self.build_type_rb2.pack(side=tk.LEFT, padx=5)

        cl_row = ttk.Frame(self.opt_frame)
        cl_row.pack(fill=tk.X, pady=2)
        self.opt_clean_check = ttk.Checkbutton(
            cl_row, text="构建前清理 (clean)",
            variable=self.clean_var)
        self.opt_clean_check.pack(side=tk.LEFT)

        self._on_version_change()
        self._update_variant_hint()
        self._update_kernel_hint()

        self.btn_frame = ttk.Frame(main_frame)
        self.btn_frame.pack(fill=tk.X, pady=(0, 5))

        self.build_btn = ttk.Button(
            self.btn_frame, text="开始构建", command=self._start_build)
        self.build_btn.pack(side=tk.LEFT, padx=3)

        self.cancel_btn = ttk.Button(
            self.btn_frame, text="取消", command=self._cancel_build, state=tk.DISABLED)
        self.cancel_btn.pack(side=tk.LEFT, padx=3)

        ttk.Button(self.btn_frame, text="打开输出目录",
                   command=self._open_output).pack(side=tk.LEFT, padx=3)

        ttk.Button(self.btn_frame, text="复制日志",
                   command=self._copy_log).pack(side=tk.LEFT, padx=3)

        log_frame = ttk.LabelFrame(main_frame, text="构建日志", padding=5)
        log_frame.pack(fill=tk.BOTH, expand=True)

        self.log_widget = scrolledtext.ScrolledText(
            log_frame, wrap=tk.WORD, font=('Consolas', 9),
            state=tk.DISABLED, bg='#1e1e1e')
        self.log_widget.pack(fill=tk.BOTH, expand=True)

        self.log_widget.tag_configure(
            'header', foreground='#569cd6', font=('Consolas', 9, 'bold'))
        self.log_widget.tag_configure('error', foreground='#f44747')
        self.log_widget.tag_configure('warning', foreground='#cca700')
        self.log_widget.tag_configure('success', foreground='#6a9955')
        self.log_widget.tag_configure('info', foreground='#4fc1ff')
        self.log_widget.tag_configure('normal', foreground='#d4d4d4')

        bottom = ttk.Frame(main_frame)
        bottom.pack(fill=tk.X, pady=(3, 0))
        self.status_label = ttk.Label(bottom, text="就绪")
        self.status_label.pack(side=tk.LEFT)
        ttk.Label(bottom, text=f"v{VERSION} · YSPlayer",
                  foreground="gray").pack(side=tk.RIGHT)

        self.java_env_frame.pack(fill=tk.X, pady=(0, 8),
                                 before=self.env_status.master)

    def _auto_detect(self):
        if not self.jdk_path.get():
            self._detect_jdk()
        else:
            self.jdk_path.set(os.path.normpath(self.jdk_path.get()))
        if not self.sdk_path.get():
            self._detect_sdk()
        else:
            self.sdk_path.set(os.path.normpath(self.sdk_path.get()))
        self._refresh_version_choices()

    def _refresh_version_choices(self):
        """根据 SDK 已安装的 platforms 过滤 Android 版本下拉框。"""
        sdk_path = self.sdk_path.get().strip()
        self._available_apis = _get_available_platform_apis(sdk_path) if sdk_path else set()
        if not self._available_apis:
            self.version_combo['values'] = list(ANDROID_VERSIONS.keys())
            self.log("未检测到 SDK platforms，显示全部版本（构建时可能缺少 android.jar）", "warning")
            self._on_version_change()
            return
        available_versions = [
            ver for ver, info in ANDROID_VERSIONS.items()
            if info['api'] in self._available_apis
        ]
        self.version_combo['values'] = available_versions
        current = self.version_var.get()
        if current not in available_versions:
            self.version_var.set(available_versions[0] if available_versions else list(ANDROID_VERSIONS.keys())[0])
        apis_str = ", ".join(str(a) for a in sorted(self._available_apis))
        self.log(f"SDK 可用 platforms: API {apis_str}", "info")
        self.log(f"可构建版本: {', '.join(available_versions)}", "info")
        self._on_version_change()

    def _detect_jdk(self):
        self.log("正在检测 JDK...", "info")

        current = self.jdk_path.get().strip()
        if current:
            resolved = _resolve_jdk_root(os.path.normpath(current))
            if resolved:
                self.jdk_path.set(resolved)
                self.env_status.config(
                    text=f"JDK: {resolved} (已手动配置)", foreground="green")
                self.log(f"已配置 JDK: {resolved}", "info")
                self._save_state()
                return

        jdks = find_jdk()
        if jdks:
            kernel = self.kernel_var.get()
            required_jdk = KERNELS.get(kernel, {}).get("jdk", 11)
            best = None
            for jdk_path, jdk_ver in jdks:
                major = _get_jdk_major_version(jdk_path)
                if major == required_jdk:
                    best = (jdk_path, jdk_ver)
                    break
            if not best:
                for jdk_path, jdk_ver in jdks:
                    major = _get_jdk_major_version(jdk_path)
                    if major and major >= required_jdk:
                        best = (jdk_path, jdk_ver)
                        break
            if not best:
                best = jdks[0]
            self.jdk_path.set(best[0])
            self.env_status.config(
                text=f"检测到 {len(jdks)} 个 JDK，已选: {best[0]} (v{best[1]})",
                foreground="green")
            self._save_state()
        else:
            self.env_status.config(
                text="未检测到 JDK，请手动选择或下载", foreground="red")

    def _detect_sdk(self):
        self.log("正在检测 Android SDK...", "info")

        current = self.sdk_path.get().strip()
        if current and os.path.isfile(os.path.join(current, 'platform-tools', 'adb.exe')):
            current = os.path.normpath(current)
            self.sdk_path.set(current)
            bt = _find_build_tools(current)
            plats = _list_platforms(current)
            has_plats = len(_get_available_platform_apis(current)) > 0
            status_text = f"SDK: {current} (已手动配置, build-tools: {bt})"
            if plats and plats != "无":
                status_text += f" [platforms: {plats}]"
            status_color = "green" if has_plats else "orange"
            self.env_status.config(text=status_text, foreground=status_color)
            self._refresh_version_choices()
            self._save_state()
            return

        sdks = find_android_sdk()
        if sdks:
            best = sdks[0]
            self.sdk_path.set(best[0])
            bt = best[1] if len(best) > 1 else "?"
            plats = best[2] if len(best) > 2 else ""
            has_plats = best[3] if len(best) > 3 else False
            status_text = f"SDK: {best[0]} (build-tools: {bt})"
            if plats:
                status_text += f" [platforms: {plats}]"
            status_color = "green" if has_plats else "orange"
            self.env_status.config(text=status_text, foreground=status_color)
            if sdks[0][3]:
                self.log(f"SDK platforms: {plats}", "info")
            else:
                self.log(f"SDK platforms 为空，请安装 API packages", "warning")
            self._refresh_version_choices()
            self._save_state()
        else:
            self.env_status.config(
                text="未检测到 Android SDK，请手动选择或安装",
                foreground="red")

    def _browse_jdk(self):
        p = filedialog.askdirectory(title="选择 JDK 根目录 (包含 bin/javac.exe)")
        if p:
            self.jdk_path.set(os.path.normpath(p))
            self._save_state()

    def _browse_sdk(self):
        p = filedialog.askdirectory(
            title="选择 Android SDK 根目录 (包含 platform-tools)")
        if p:
            self.sdk_path.set(os.path.normpath(p))
            self._refresh_version_choices()
            self._save_state()

    def _dl_jdk(self, version):
        size_map = {8: "102MB", 11: "190MB", 17: "182MB"}
        sz = size_map.get(version, "~150MB")
        if not messagebox.askyesno(
            "确认下载",
            f"将下载 JDK {version} (约{sz}) 到项目 tools/app/JDK/{version}/ 目录。\n继续？"):
            return
        jdk_base = os.path.join(SCRIPT_DIR, '..', 'tools', 'app', 'JDK')
        jdk_base = os.path.normpath(jdk_base)
        self._run_download(
            lambda cb: download_jdk(version, jdk_base, progress_cb=cb),
            f"JDK {version}", os.path.join(jdk_base, str(version)), is_jdk=True)

    def _dl_sdk(self):
        if not messagebox.askyesno(
            "确认下载",
            "将下载 Android SDK 命令行工具 (约150MB) 到项目 tools 目录。\n继续？"):
            return
        dest = os.path.join(PROJECT_ROOT, 'tools', 'android-sdk')
        self._run_download(
            lambda cb: download_android_sdk(dest, progress_cb=cb),
            "Android SDK", dest, is_jdk=False)

    def _run_download(self, download_fn, name, dest, is_jdk):
        self.build_btn.config(state=tk.DISABLED)
        self.status_label.config(text=f"准备下载 {name}...")
        def _do():
            try:
                result = download_fn(
                    lambda p, msg: self.after(0, self._on_dl_progress, p, msg))
                self.after(0, self._on_dl_done, result, name, dest, is_jdk)
            except Exception as e:
                self.after(0, self._on_dl_error, str(e))

        threading.Thread(target=_do, daemon=True).start()

    def _on_dl_progress(self, pct, msg):
        self.status_label.config(text=msg)

    def _on_dl_done(self, result, name, dest, is_jdk):
        self.build_btn.config(state=tk.NORMAL)
        if result:
            if is_jdk:
                self.jdk_path.set(result)
            else:
                self.sdk_path.set(dest)
                self._refresh_version_choices()
            self._save_state()
            self.log(f"[下载完成] {name} → {result}", "success")
            self.status_label.config(text=f"{name} 下载完成")
        else:
            self.log(f"[下载失败] {name} 无法定位安装目录", "error")
            self.status_label.config(text="下载失败")

    def _on_dl_error(self, msg):
        self.build_btn.config(state=tk.NORMAL)
        self.log(f"[下载异常] {msg}", "error")
        self.status_label.config(text="下载失败")

    def _get_compatible_kernels(self, api):
        return [
            k for k, info in KERNELS.items()
            if info["minSdk"] <= api <= info.get("maxSdk", 33)
        ]

    def _get_compatible_versions_for_kernel(self, kernel_key):
        if kernel_key not in KERNELS:
            return list(ANDROID_VERSIONS.keys())
        k_info = KERNELS[kernel_key]
        return self._get_compatible_versions(
            min_api=k_info["minSdk"], max_api=k_info.get("maxSdk", 33))

    def _get_compatible_versions_for_variant(self, variant_key):
        variants = self._get_variants()
        if variant_key not in variants:
            return list(ANDROID_VERSIONS.keys())
        min_api = variants[variant_key]["minSdk"]
        return self._get_compatible_versions(min_api=min_api)

    def _get_compatible_versions(self, min_api=None, max_api=None):
        return [
            v for v, info in ANDROID_VERSIONS.items()
            if (min_api is None or info["api"] >= min_api) and
               (max_api is None or info["api"] <= max_api)
        ]

    def _update_version_combo(self, allowed_versions):
        current = self.version_var.get()
        if self._available_apis:
            allowed_versions = [
                v for v in allowed_versions
                if ANDROID_VERSIONS[v]["api"] in self._available_apis
            ]
        self.version_combo['values'] = allowed_versions
        if current not in allowed_versions and allowed_versions:
            self.version_var.set(allowed_versions[0])

    def _update_kernel_combo(self, allowed_kernels):
        current = self.kernel_var.get()
        self.kernel_combo['values'] = allowed_kernels
        if current not in allowed_kernels and allowed_kernels:
            self.kernel_var.set(allowed_kernels[0])
        self._update_kernel_hint()

    def _update_variant_combo(self, allowed_variants):
        current = self.variant_var.get()
        self.variant_combo['values'] = allowed_variants
        if current not in allowed_variants and allowed_variants:
            self.variant_var.set(allowed_variants[0])
        self._update_variant_hint()

    def _on_version_change(self, *_):
        if self._updating:
            return
        self._updating = True
        try:
            ver = self.version_var.get()
            last = self.cache.get('last_version', '')
            if last and last != ver:
                self.clean_var.set(True)
            if ver not in ANDROID_VERSIONS:
                return
            api = ANDROID_VERSIONS[ver]["api"]
            self.version_api_label.config(text=f"API {api}")
            self.version_hint.config(
                text=f"目标 {ver} (API {api}) — minSdkVersion = {api}（仅控制最低安装门槛）")

            allowed_variants = [
                k for k, v in self._get_variants().items()
                if v["minSdk"] <= api
            ]
            self._update_variant_combo(allowed_variants)

            allowed_kernels = self._get_compatible_kernels(api)
            self._update_kernel_combo(allowed_kernels)

            k = self.kernel_var.get()
            if k in KERNELS:
                allowed_versions = self._get_compatible_versions_for_kernel(k)
                v = self.variant_var.get()
                variants = self._get_variants()
                if v in variants:
                    min_variant_api = variants[v]["minSdk"]
                    allowed_versions = [
                        av for av in allowed_versions
                        if ANDROID_VERSIONS[av]["api"] >= min_variant_api
                    ]
                self._update_version_combo(allowed_versions)

                current_api = ANDROID_VERSIONS.get(self.version_var.get(), {}).get("api", 21)
                allowed_variants = [
                    vk for vk, vi in self._get_variants().items()
                    if vi["minSdk"] <= current_api
                ]
                if k == "x5":
                    allowed_variants = ["arm32", "arm64"]
                    if self.variant_var.get() not in allowed_variants:
                        self.variant_var.set("arm32")
                self._update_variant_combo(allowed_variants)

                # 版本可能在步骤3中被自动修正，用最终版本+变体重新过滤内核下拉框
                final_api = ANDROID_VERSIONS.get(self.version_var.get(), {}).get("api", 21)
                allowed_kernels = self._get_compatible_kernels(final_api)
                final_v = self.variant_var.get()
                if final_v not in ("arm32", "arm64"):
                    allowed_kernels = [x for x in allowed_kernels if x != "x5"]
                self._update_kernel_combo(allowed_kernels)

            self._save_state()
        finally:
            self._updating = False

    def _on_variant_change(self, *_):
        if self._updating:
            return
        self._updating = True
        try:
            v = self.variant_var.get()
            last = self.cache.get('last_variant', '')
            if last and last != v:
                self.clean_var.set(True)
            variants = self._get_variants()
            if v in variants:
                self.variant_hint.config(text=f"[{v}] {variants[v]['desc']}")
                min_api = variants[v]["minSdk"]

                allowed_versions = self._get_compatible_versions(min_api=min_api)
                allowed_versions = [
                    av for av in allowed_versions
                    if av in self._get_compatible_versions_for_kernel(self.kernel_var.get())
                ]
                self._update_version_combo(allowed_versions)

                current_api = ANDROID_VERSIONS.get(self.version_var.get(), {}).get("api", 21)
                allowed_kernels = self._get_compatible_kernels(current_api)
                if v not in ("arm32", "arm64"):
                    allowed_kernels = [k for k in allowed_kernels if k != "x5"]
                self._update_kernel_combo(allowed_kernels)

                # 内核可能已被自动切换（如变体从 arm→x86 时 x5 被移除），
                # 用新内核的 API 约束重新过滤版本下拉框
                k = self.kernel_var.get()
                if k in KERNELS:
                    allowed_versions2 = self._get_compatible_versions_for_kernel(k)
                    v2 = self.variant_var.get()
                    if v2 in variants:
                        min_api2 = variants[v2]["minSdk"]
                        allowed_versions2 = [
                            av for av in allowed_versions2
                            if ANDROID_VERSIONS[av]["api"] >= min_api2
                        ]
                    self._update_version_combo(allowed_versions2)

            self._save_state()
        finally:
            self._updating = False

    def _on_kernel_change(self, *_):
        if self._updating:
            return
        self._updating = True
        try:
            k = self.kernel_var.get()
            last = self.cache.get('last_kernel', '')
            if last and last != k:
                self.clean_var.set(True)

            self._update_kernel_hint()

            required_jdk = KERNELS.get(k, {}).get("jdk", 11)
            current_jdk = self.jdk_path.get().strip()
            current_major = _get_jdk_major_version(current_jdk) if current_jdk else None
            if current_major and current_major != required_jdk:
                self.log(f"内核 [{k}] 需要 JDK {required_jdk}，当前 JDK {current_major}，自动切换...", "info")
                jdks = find_jdk()
                best = None
                for jdk_path, jdk_ver in jdks:
                    major = _get_jdk_major_version(jdk_path)
                    if major == required_jdk:
                        best = (jdk_path, jdk_ver)
                        break
                if not best:
                    for jdk_path, jdk_ver in jdks:
                        major = _get_jdk_major_version(jdk_path)
                        if major and major >= required_jdk:
                            best = (jdk_path, jdk_ver)
                            break
                if best:
                    self.jdk_path.set(best[0])
                    self.log(f"已切换 JDK: {best[0]} (v{best[1]})", "success")
                elif current_jdk:
                    parent = os.path.dirname(current_jdk)
                    if parent != current_jdk and os.path.isdir(parent):
                        resolved = _resolve_jdk_root(parent, required_version=required_jdk)
                        if resolved and resolved != current_jdk:
                            self.jdk_path.set(resolved)
                            self.log(f"已切换 JDK: {resolved} (从父目录 {parent} 匹配)", "success")
                            best = (resolved, None)
                if not best:
                    self.log(f"未找到 JDK {required_jdk}+，保留当前 JDK {current_major or '?'}", "warning")
            elif not current_major:
                self._detect_jdk()

            allowed_versions = self._get_compatible_versions_for_kernel(k)

            v = self.variant_var.get()
            variants = self._get_variants()
            if v in variants:
                min_variant_api = variants[v]["minSdk"]
                allowed_versions = [
                    av for av in allowed_versions
                    if ANDROID_VERSIONS[av]["api"] >= min_variant_api
                ]

            self._update_version_combo(allowed_versions)

            if k in KERNELS:
                current_api = ANDROID_VERSIONS.get(self.version_var.get(), {}).get("api", 21)
                allowed_variants = [
                    vk for vk, vi in self._get_variants().items()
                    if vi["minSdk"] <= current_api
                ]
                if k == "x5":
                    allowed_variants = ["arm32", "arm64"]
                    if self.variant_var.get() not in allowed_variants:
                        self.variant_var.set("arm32")
                self._update_variant_combo(allowed_variants)

                # 版本可能在 _update_version_combo 中被自动修正，
                # 用最终版本+变体重新过滤内核下拉框自身的 values
                final_api = ANDROID_VERSIONS.get(self.version_var.get(), {}).get("api", 21)
                allowed_kernels = self._get_compatible_kernels(final_api)
                final_v = self.variant_var.get()
                if final_v not in ("arm32", "arm64"):
                    allowed_kernels = [x for x in allowed_kernels if x != "x5"]
                self._update_kernel_combo(allowed_kernels)

            self._save_state()
        finally:
            self._updating = False

    def _update_variant_hint(self):
        v = self.variant_var.get()
        variants = self._get_variants()
        if v in variants:
            self.variant_hint.config(text=f"[{v}] {variants[v]['desc']}")

    def _update_kernel_hint(self):
        k = self.kernel_var.get()
        if k in KERNELS:
            info = KERNELS[k]
            sdk_range = f"API {info['minSdk']}"
            sdk_range += f"~{info['maxSdk']}" if 'maxSdk' in info else "+"
            self.kernel_hint.config(
                text=f"[{k}] {info['label']} · {sdk_range} — {info['desc']}")

    def _get_variants(self):
        return VARIANTS

    def _start_build(self):
        self._start_java_build()

    def _start_java_build(self):
        jdk = os.path.normpath(self.jdk_path.get().strip())
        sdk = os.path.normpath(self.sdk_path.get().strip())
        version = self.version_var.get()
        variant = self.variant_var.get()
        build_type = self.build_type_var.get()
        clean = self.clean_var.get()

        if not jdk:
            messagebox.showerror("错误", "请先配置 JDK 路径")
            return
        if not os.path.isfile(os.path.join(jdk, 'bin', 'javac.exe')):
            kernel = self.kernel_var.get()
            required_jdk = KERNELS.get(kernel, {}).get("jdk", 11)
            resolved = _resolve_jdk_root(jdk, required_version=required_jdk)
            if resolved:
                jdk = resolved
                self.jdk_path.set(resolved)
                self.log(f"JDK 自动解析: {resolved}", "info")
            else:
                messagebox.showerror("错误", f"JDK 路径无效，未找到 javac.exe:\n{jdk}")
                return

        if sdk and not os.path.isdir(sdk):
            messagebox.showerror("错误", f"Android SDK 路径无效:\n{sdk}")
            return

        kernel = self.kernel_var.get()
        required_jdk = KERNELS.get(kernel, {}).get("jdk", 11)
        current_major = _get_jdk_major_version(jdk)
        if current_major and current_major != required_jdk:
            parent = os.path.dirname(jdk)
            if parent != jdk and os.path.isdir(parent):
                resolved = _resolve_jdk_root(parent, required_version=required_jdk)
                if resolved:
                    self.log(f"JDK 不匹配（需要 {required_jdk}，当前 {current_major}），自动切换: {resolved}", "warning")
                    jdk = resolved
                    self.jdk_path.set(resolved)
                    current_major = _get_jdk_major_version(jdk)
            if current_major != required_jdk:
                messagebox.showerror("JDK 版本错误",
                    f"当前内核 [{kernel}] 需要 JDK {required_jdk}，\n"
                    f"但当前使用 JDK {current_major or '?'}:\n{jdk}\n\n"
                    f"请手动选择 JDK {required_jdk} 版本（点击「浏览」按钮重新选择）。")
                return

        self._save_state()

        self._clear_log()
        self.build_btn.config(state=tk.DISABLED)
        self.cancel_btn.config(state=tk.NORMAL)
        api = ANDROID_VERSIONS.get(version, {}).get("api", 21)
        version_label = f"{version} (API {api})"
        self.status_label.config(
            text=f"正在构建 {variant}/{build_type} for {version_label}...")

        kernel = self.kernel_var.get()
        kernel_info = KERNELS.get(kernel, {})
        self.runner = BuildRunner(
            jdk, sdk, variant, kernel, build_type, clean,
            version, api,
            agp_version=kernel_info.get("agp", "7.4.2"),
            compile_sdk=kernel_info.get("compileSdk", 33),
            java_version=kernel_info.get("javaVersion", "11"),
            log_cb=self._on_log, done_cb=self._on_done)
        self.runner.start()

    def _cancel_build(self):
        if self.runner and self.runner.is_alive():
            self.log("\n正在取消...", "warning")
            self.runner.cancel()
            self.cancel_btn.config(state=tk.DISABLED)

    def _on_done(self, success, msg):
        self.after(0, self._gui_done, success, msg)

    def _gui_done(self, success, msg):
        self.build_btn.config(state=tk.NORMAL)
        self.cancel_btn.config(state=tk.DISABLED)
        if success:
            self.cache['last_version'] = self.version_var.get()
            self.cache['last_variant'] = self.variant_var.get()
            self.cache['last_kernel'] = self.kernel_var.get()
            self.cache['last_build_type'] = self.build_type_var.get()
            save_cache(self.cache)
            self.clean_var.set(False)
            self.status_label.config(
                text=f"{msg} — 点击「打开输出目录」查看 APK")
        else:
            self.status_label.config(text=msg)
        self.runner = None

    def _on_log(self, text, tag="normal"):
        self.after(0, self._append_log, text, tag)

    def _append_log(self, text, tag):
        self.log_widget.config(state=tk.NORMAL)
        self.log_widget.insert(tk.END, text + '\n', tag)
        self.log_widget.see(tk.END)
        self.log_widget.config(state=tk.DISABLED)

    def _clear_log(self):
        self.log_widget.config(state=tk.NORMAL)
        self.log_widget.delete('1.0', tk.END)
        self.log_widget.config(state=tk.DISABLED)

    def log(self, text, tag="normal"):
        self._append_log(text, tag)

    def _save_state(self):
        self.cache['jdk_path'] = self.jdk_path.get()
        self.cache['sdk_path'] = self.sdk_path.get()
        self.cache['version'] = self.version_var.get()
        self.cache['variant'] = self.variant_var.get()
        self.cache['kernel'] = self.kernel_var.get()
        self.cache['build_type'] = self.build_type_var.get()
        save_cache(self.cache)

    def _copy_log(self):
        text = self.log_widget.get('1.0', tk.END).rstrip()
        if not text:
            messagebox.showinfo("提示", "日志为空")
            return
        self.clipboard_clear()
        self.clipboard_append(text)
        self.status_label.config(text="日志已复制到剪贴板")

    def _open_output(self):
        variant = self.variant_var.get()
        kernel = self.kernel_var.get()
        build_type = self.build_type_var.get()
        arch_label = VARIANTS.get(variant, {}).get('flavor', variant)
        variant_dir = "{}{}".format(arch_label, kernel.capitalize())
        apk_dir = os.path.join(OUTPUT_DIR, variant_dir, build_type)
        if os.path.isdir(apk_dir):
            os.startfile(apk_dir)
        else:
            if os.path.isdir(OUTPUT_DIR):
                os.startfile(OUTPUT_DIR)
            else:
                messagebox.showinfo("提示", "输出目录尚不存在，请先完成一次构建。")


if __name__ == '__main__':
    app = BuildGUI()
    app.mainloop()