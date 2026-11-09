package com.ysplayer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.os.Build;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;

public class IjkVideoPlayer implements IVideoPlayer {

    private static final String TAG = "IjkVideoPlayer";

    public interface Callback {
        void onPlayerClosed();
        void onPlayerError(String message);
        void onPlayerStateChanged(int state, String info);
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_BUFFERING = 1;
    public static final int STATE_READY = 2;
    public static final int STATE_ENDED = 3;
    public static final int STATE_ERROR = 4;

    private final Context mContext;
    private final ViewGroup mParentContainer;
    private final Callback mCallback;

    private IjkMediaPlayer mPlayer;
    private TextureView mTextureView;
    private View mBlackView;
    private Surface mSurface;

    private boolean mActive;
    private boolean mPreparing;
    private boolean mSurfaceReady;
    private boolean mAudioOnly;
    private String mCurrentUrl;
    private Map<String, String> mCurrentHeaders;
    private String mUserAgent;
    private int mPlayerState = STATE_IDLE;

    private static boolean sIjkLoaded = false;

    public IjkVideoPlayer(Context context, ViewGroup parentContainer, Callback callback) {
        mContext = context.getApplicationContext();
        mParentContainer = parentContainer;
        mCallback = callback;
    }

    public void playUrl(String url) {
        playUrl(url, null);
    }

    public void playUrl(String url, Map<String, String> headers) {
        playUrl(url, headers, false);
    }

    public void playUrl(String url, Map<String, String> headers, boolean audioOnly) {
        if (url == null || url.isEmpty()) return;

        mAudioOnly = audioOnly;

        LogUtil.i("IJK_PLAY: >>> FINAL URL = " + url
                + (mAudioOnly ? " [AUDIO-ONLY]" : ""));
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                LogUtil.i("IJK_PLAY: >>> HEADER: " + e.getKey() + ": " + e.getValue());
            }
        } else {
            LogUtil.i("IJK_PLAY: >>> HEADERS = null");
        }

        if (mPlayer != null) {
            mPlayer.setSurface(null);
            mPlayer.reset();
            mPlayer.release();
            mPlayer = null;
        }

        mCurrentUrl = url;
        mCurrentHeaders = headers;
        mPreparing = true;

        initPlayer();
        if (mPlayer == null) return;

        try {
            applyOptions(url, headers);
            android.net.Uri uri = android.net.Uri.parse(url);
            LogUtil.i("IJK_PLAY: >>> setDataSource uri = " + uri.toString());
            mPlayer.setDataSource(mContext, uri);
            mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mPlayer.setScreenOnWhilePlaying(true);
            mPlayer.prepareAsync();
        } catch (Exception e) {
            LogUtil.e(TAG + ": playUrl failed: " + e.getMessage());
            setError(e.getMessage());
        }
    }

    private void applyOptions(String url, Map<String, String> headers) {
        if (mUserAgent != null && !mUserAgent.isEmpty()) {
            mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", mUserAgent);
        }
        if (headers != null && !headers.isEmpty()) {
            StringBuilder hdr = new StringBuilder();
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) continue;
                if ("User-Agent".equalsIgnoreCase(key)) {
                    mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", value);
                } else {
                    if (hdr.length() > 0) hdr.append("\r\n");
                    hdr.append(key).append(": ").append(value);
                }
            }
            if (hdr.length() > 0) {
                hdr.append("\r\n");
                LogUtil.i("IJK_PLAY: >>> IJK headers option = [" + hdr.toString() + "]");
                mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "headers", hdr.toString());
            }
        }
    }

    public void setUserAgent(String ua) {
        mUserAgent = ua;
    }

    public void hide() {
        if (mTextureView != null) {
            mTextureView.setVisibility(View.GONE);
        }
        mActive = false;
        mPreparing = false;
    }

    public void play(String url, Map<String, String> headers) {
        playUrl(url, headers);
    }

    public void setActive(boolean active) {
        mActive = active;
        if (mTextureView != null) {
            mTextureView.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    public void setSurfaceActive(boolean active) {
        if (mTextureView != null) {
            mTextureView.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    public void pause() {
        LogUtil.i(TAG + ": [LIFECYCLE] pause() called, mPlayer=" + (mPlayer != null)
                + " isPlaying=" + (mPlayer != null && mPlayer.isPlaying())
                + " mCurrentUrl=" + mCurrentUrl);
        if (mPlayer != null && mPlayer.isPlaying()) {
            mPlayer.pause();
            LogUtil.i(TAG + ": [LIFECYCLE] pause() done, player paused");
        }
    }

    public void resume() {
        LogUtil.i(TAG + ": [LIFECYCLE] resume() called, mPlayer=" + (mPlayer != null)
                + " isPlaying=" + (mPlayer != null && mPlayer.isPlaying())
                + " mCurrentUrl=" + mCurrentUrl);
        if (mPlayer != null && !mPlayer.isPlaying() && mCurrentUrl != null) {
            mPlayer.start();
            LogUtil.i(TAG + ": [LIFECYCLE] resume() done, player started");
        }
    }

    @Override
    public void stop() {
        LogUtil.i(TAG + ": [LIFECYCLE] stop() called");
        releasePlayer();
        mCurrentUrl = null;
        mPlayerState = STATE_IDLE;
        changeState(STATE_IDLE, "");
    }

    @Override
    public void release() {
        LogUtil.i(TAG + ": [LIFECYCLE] release() called");
        releasePlayer();
        removeSurface();
    }

    @Override
    public boolean isActive() {
        return mActive;
    }

    public boolean isPlaying() {
        return mPlayer != null && mPlayer.isPlaying();
    }

    public boolean hasActivePlayback() {
        return mCurrentUrl != null && mPlayer != null && !mPreparing;
    }

    private void initPlayer() {
        synchronized (IjkVideoPlayer.class) {
            if (!sIjkLoaded) {
                try {
                    IjkMediaPlayer.loadLibrariesOnce(null);
                    sIjkLoaded = true;
                    LogUtil.i(TAG + ": ijkplayer native libraries loaded");
                } catch (Throwable e) {
                    LogUtil.e(TAG + ": Failed to load ijkplayer: " + e.getMessage());
                    setError("IJK load failed: " + e.getMessage());
                    return;
                }
            }
        }

        mPlayer = new IjkMediaPlayer();

        boolean useMediaCodec = Build.VERSION.SDK_INT >= 24;
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", useMediaCodec ? 1 : 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", useMediaCodec ? 1 : 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-avc", useMediaCodec ? 1 : 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", useMediaCodec ? 1 : 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", useMediaCodec ? 1 : 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", useMediaCodec ? 1 : 0);
        LogUtil.i(TAG + ": MediaCodec " + (useMediaCodec ? "enabled" : "disabled") + " (SDK " + Build.VERSION.SDK_INT + ")");

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 5000000);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 5242880);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 31457280);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 10);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max_cached_duration", 1500);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 10);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 60000000);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http_log", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "log_level", 48);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 0);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-fps", 60);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "synctype", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "av_sync_type", 1);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-pictq-size", 3);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek");

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1);

        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 8);
        mPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 8);

        mPlayer.setOnPreparedListener(new IMediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(IMediaPlayer mp) {
                mPreparing = false;
                mPlayerState = STATE_READY;
                LogUtil.i(TAG + ": onPrepared: " + mCurrentUrl);
                changeState(STATE_READY, "");

                if (mSurface != null && mPlayer != null) {
                    mPlayer.setSurface(mSurface);
                }

                mPlayer.start();
            }
        });

        mPlayer.setOnErrorListener(new IMediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(IMediaPlayer mp, int what, int extra) {
                mPreparing = false;
                String msg = "Error code: " + what + ", extra: " + extra;
                LogUtil.e(TAG + ": onError: " + msg);
                setError(msg);
                return true;
            }
        });

        mPlayer.setOnInfoListener(new IMediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(IMediaPlayer mp, int what, int extra) {
                switch (what) {
                    case IMediaPlayer.MEDIA_INFO_BUFFERING_START:
                        changeState(STATE_BUFFERING, "buffering");
                        break;
                    case IMediaPlayer.MEDIA_INFO_BUFFERING_END:
                        changeState(STATE_READY, "buffered");
                        break;
                    case IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START:
                        LogUtil.i(TAG + ": video rendering start");
                        break;
                    default:
                        LogUtil.d(TAG + ": onInfo: what=" + what + " extra=" + extra);
                        break;
                }
                return false;
            }
        });

        mPlayer.setOnCompletionListener(new IMediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(IMediaPlayer mp) {
                mPlayerState = STATE_ENDED;
                changeState(STATE_ENDED, "playback completed");
            }
        });

        mPlayer.setOnBufferingUpdateListener(new IMediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(IMediaPlayer mp, int percent) {
            }
        });

        mPlayer.setOnVideoSizeChangedListener(new IMediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(IMediaPlayer mp, int width, int height,
                                           int sarNum, int sarDen) {
                LogUtil.i(TAG + ": video size: " + width + "x" + height);
            }
        });

        attachSurface();
    }

    private void attachSurface() {
        if (mTextureView != null || mBlackView != null) {
            return;
        }

        if (mAudioOnly) {
            mBlackView = new View(mContext);
            mBlackView.setBackgroundColor(Color.BLACK);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            params.gravity = Gravity.CENTER;
            mParentContainer.addView(mBlackView, params);
            LogUtil.i(TAG + ": audioOnly mode, black surface");
            return;
        }

        mTextureView = new TextureView(mContext);
        mTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                LogUtil.i(TAG + ": surfaceTextureAvailable: " + width + "x" + height);
                if (mSurface != null) {
                    mSurface.release();
                }
                mSurface = new Surface(surface);
                mSurfaceReady = true;
                if (mPlayer != null) {
                    mPlayer.setSurface(mSurface);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                LogUtil.i(TAG + ": surfaceTextureSizeChanged: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                LogUtil.i(TAG + ": surfaceTextureDestroyed");
                mSurfaceReady = false;
                if (mPlayer != null) {
                    mPlayer.setSurface(null);
                }
                if (mSurface != null) {
                    mSurface.release();
                    mSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.gravity = Gravity.CENTER;
        mParentContainer.addView(mTextureView, params);
    }

    private void removeSurface() {
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mTextureView != null) {
            mParentContainer.removeView(mTextureView);
            mTextureView = null;
        }
        if (mBlackView != null) {
            mParentContainer.removeView(mBlackView);
            mBlackView = null;
        }
        mSurfaceReady = false;
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            LogUtil.i(TAG + ": [DIAG] releasePlayer: setSurface(null) + reset + release");
            mPlayer.setSurface(null);
            mPlayer.reset();
            mPlayer.release();
            mPlayer = null;
            LogUtil.i(TAG + ": [DIAG] releasePlayer: done, mPlayer=null");
        }
        mPreparing = false;
    }

    private void setError(String message) {
        mPlayerState = STATE_ERROR;
        if (mCallback != null) {
            mCallback.onPlayerError(message);
        }
        changeState(STATE_ERROR, message);
    }

    private void changeState(int state, String info) {
        LogUtil.d(TAG + ": state: " + state + " " + info);
        if (mCallback != null) {
            mCallback.onPlayerStateChanged(state, info);
        }
    }
}