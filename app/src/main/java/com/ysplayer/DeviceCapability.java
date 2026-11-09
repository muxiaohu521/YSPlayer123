package com.ysplayer;

import android.os.Build;

import android.opengl.GLES20;

public final class DeviceCapability {

    private DeviceCapability() {
    }

    public static class GlesInfo {
        public final int majorVersion;
        public final int minorVersion;
        public final String vendor;
        public final String renderer;
        public final boolean supportsES30;
        public final boolean supportsES31;
        public final boolean supportsES32;

        GlesInfo(int major, int minor, String vendor, String renderer) {
            this.majorVersion = major;
            this.minorVersion = minor;
            this.vendor = vendor;
            this.renderer = renderer;
            this.supportsES30 = majorVersion >= 3;
            this.supportsES31 = majorVersion > 3 || (majorVersion == 3 && minorVersion >= 1);
            this.supportsES32 = majorVersion > 3 || (majorVersion == 3 && minorVersion >= 2);
        }

        @Override
        public String toString() {
            return "GLES " + majorVersion + "." + minorVersion
                    + " | " + vendor + " | " + renderer;
        }
    }

    public static class CompatibilityResult {
        public final boolean pass;
        public final String message;
        public final int requiredMajor;
        public final int requiredMinor;
        public final int actualMajor;
        public final int actualMinor;

        CompatibilityResult(boolean pass, String message,
                int reqMajor, int reqMinor, int actMajor, int actMinor) {
            this.pass = pass;
            this.message = message;
            this.requiredMajor = reqMajor;
            this.requiredMinor = reqMinor;
            this.actualMajor = actMajor;
            this.actualMinor = actMinor;
        }
    }

    public static CompatibilityResult checkCompatibility(GlesInfo info) {
        int reqMajor = BuildConfig.GLES_VERSION;
        int reqMinor = BuildConfig.GLES_MINOR_VERSION;
        String gfxApi = BuildConfig.GRAPHICS_API;

        if ("auto".equals(gfxApi)) {
            String msg = "AUTO | device=" + info.majorVersion + "." + info.minorVersion
                    + " | " + info.vendor + " | " + info.renderer;
            return new CompatibilityResult(true, msg,
                    reqMajor, reqMinor, info.majorVersion, info.minorVersion);
        }

        boolean pass;
        String reason;
        if (info.majorVersion > reqMajor) {
            pass = true;
        } else if (info.majorVersion < reqMajor) {
            pass = false;
        } else {
            pass = info.minorVersion >= reqMinor;
        }

        if (pass) {
            reason = "OK";
        } else {
            reason = "MISMATCH";
        }

        String msg = reason + " | target=" + reqMajor + "." + reqMinor
                + " device=" + info.majorVersion + "." + info.minorVersion
                + " (" + gfxApi + ")";
        return new CompatibilityResult(pass, msg, reqMajor, reqMinor,
                info.majorVersion, info.minorVersion);
    }

    public static GlesInfo detectGlesCapability() {
        EGLLog eglLog = new EGLLog();
        javax.microedition.khronos.egl.EGL10 egl10 = null;
        javax.microedition.khronos.egl.EGLDisplay display = null;
        boolean displayInitialized = false;

        try {
            egl10 = (javax.microedition.khronos.egl.EGL10)
                    javax.microedition.khronos.egl.EGLContext.getEGL();
            display = egl10.eglGetDisplay(
                    javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY);

            if (display == javax.microedition.khronos.egl.EGL10.EGL_NO_DISPLAY) {
                return new GlesInfo(2, 0, "unknown", "unknown");
            }

            int[] version = new int[2];
            if (!egl10.eglInitialize(display, version)) {
                return new GlesInfo(2, 0, "unknown", "unknown");
            }
            displayInitialized = true;

            probeGlesVersion(egl10, display, eglLog);
        } catch (Exception e) {
            eglLog.error = e.toString();
        } finally {
            if (displayInitialized && egl10 != null && display != null) {
                try {
                    egl10.eglTerminate(display);
                } catch (Exception ignored) {
                }
            }
        }

        String vendor = safeGlGet(GLES20.GL_VENDOR);
        String renderer = safeGlGet(GLES20.GL_RENDERER);

        return new GlesInfo(eglLog.major > 0 ? eglLog.major : 2,
                eglLog.minor,
                vendor != null ? vendor : "unknown",
                renderer != null ? renderer : "unknown");
    }

    private static void probeGlesVersion(javax.microedition.khronos.egl.EGL10 egl10,
            javax.microedition.khronos.egl.EGLDisplay display, EGLLog log) {

        int[] configAttribs = {
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                javax.microedition.khronos.egl.EGL10.EGL_NONE
        };
        javax.microedition.khronos.egl.EGLConfig[] configs =
                new javax.microedition.khronos.egl.EGLConfig[1];
        int[] numConfigs = new int[1];

        if (!egl10.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)
                || numConfigs[0] == 0) {
            configAttribs[1] = 1;
            if (!egl10.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)
                    || numConfigs[0] == 0) {
                return;
            }
        }

        javax.microedition.khronos.egl.EGLContext context = null;
        javax.microedition.khronos.egl.EGLSurface surface = null;
        boolean madeCurrent = false;

        try {
            int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
            int[] contextAttribs = {
                    EGL_CONTEXT_CLIENT_VERSION, 3,
                    javax.microedition.khronos.egl.EGL10.EGL_NONE
            };
            context = egl10.eglCreateContext(
                    display, configs[0],
                    javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT,
                    contextAttribs);

            if (context == null
                    || context == javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT) {
                return;
            }

            surface = egl10.eglCreatePbufferSurface(display, configs[0],
                    new int[]{
                            javax.microedition.khronos.egl.EGL10.EGL_WIDTH, 1,
                            javax.microedition.khronos.egl.EGL10.EGL_HEIGHT, 1,
                            javax.microedition.khronos.egl.EGL10.EGL_NONE
                    });

            if (surface == null
                    || surface == javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE) {
                return;
            }

            if (!egl10.eglMakeCurrent(display, surface, surface, context)) {
                return;
            }
            madeCurrent = true;

            parseGlVersionString(log);
        } finally {
            if (madeCurrent) {
                try {
                    egl10.eglMakeCurrent(display,
                            javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
                            javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
                            javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT);
                } catch (Exception ignored) {
                }
            }
            if (surface != null) {
                try {
                    egl10.eglDestroySurface(display, surface);
                } catch (Exception ignored) {
                }
            }
            if (context != null) {
                try {
                    egl10.eglDestroyContext(display, context);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void parseGlVersionString(EGLLog log) {
        log.major = 3;
        String glVersion = GLES20.glGetString(GLES20.GL_VERSION);
        if (glVersion == null || !glVersion.startsWith("OpenGL ES ")) {
            return;
        }
        String verPart = glVersion.substring("OpenGL ES ".length());
        int dotIdx = verPart.indexOf('.');
        int spaceIdx = verPart.indexOf(' ');
        int endIdx = spaceIdx > 0 ? spaceIdx : verPart.length();
        if (dotIdx <= 0) {
            return;
        }
        try {
            log.major = Integer.parseInt(verPart.substring(0, dotIdx));
            log.minor = Integer.parseInt(verPart.substring(dotIdx + 1, endIdx));
        } catch (NumberFormatException ignored) {
        }
    }

    private static String safeGlGet(int name) {
        try {
            return GLES20.glGetString(name);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static class EGLLog {
        int major;
        int minor;
        String error;
    }

    public static String getCpuArchitecture() {
        String arch = Build.CPU_ABI;
        if (arch == null || arch.isEmpty()) {
            arch = System.getProperty("os.arch", "unknown");
        }
        return arch;
    }

    public static boolean isEmulator() {
        String fingerprint = Build.FINGERPRINT;
        String model = Build.MODEL;
        String manufacturer = Build.MANUFACTURER;

        return (fingerprint != null && (fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || fingerprint.contains("vbox")
                || fingerprint.contains("qemu")))
                || (model != null && (model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")))
                || (manufacturer != null && manufacturer.contains("Genymotion"))
                || (Build.HARDWARE != null && (Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.HARDWARE.contains("vbox86")));
    }

    public static int getSdkInt() {
        return Build.VERSION.SDK_INT;
    }

    public static String getDeviceSummary() {
        return "SDK=" + Build.VERSION.SDK_INT
                + " | CPU=" + getCpuArchitecture()
                + " | EMU=" + isEmulator()
                + " | MODEL=" + Build.MODEL
                + " | BRAND=" + Build.BRAND;
    }
}