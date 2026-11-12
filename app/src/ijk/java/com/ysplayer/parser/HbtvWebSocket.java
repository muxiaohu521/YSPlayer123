package com.ysplayer.parser;

import android.net.SSLCertificateSocketFactory;
import android.util.Base64;

import com.ysplayer.LogUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;

/**
 * 最小化 WebSocket 客户端（纯 Java SSLSocket 实现，RFC 6455）。
 *
 * 用途：长江云（news.hbtv.com.cn）防盗链链路的「会话激活」——
 * 官网页面 JS 会建立 wss://remote-wa.cjyun.org.cn/liveweb 连接，并周期上报
 * {@code {client_id, aa_look, client_token}} 心跳；只有完成该心跳激活的 client-id，
 * 其 /ajax/get_cdn_leech 换票得到的播放地址才能通过 CDN 源站校验。
 *
 * 本类仅依赖 JDK/Android 原生 API（SSLSocket + Base64），不引入任何浏览器或
 * 第三方网络库，严格满足 IJK 模式「去浏览器化、纯模拟解析」的设计目标。
 *
 * 支持：HTTP Upgrade 握手、客户端掩码文本帧、入站 ping→pong 应答、close 识别。
 */
public final class HbtvWebSocket {

    private static final String TAG = "HbtvWebSocket";

    private SSLSocket mSocket;
    private InputStream mIn;
    private OutputStream mOut;
    private volatile boolean mRunning;
    private volatile boolean mClosedByPeer;
    private final SecureRandom mRandom = new SecureRandom();
    private Thread mReader;

    /**
     * 建立 wss 连接并完成握手。
     *
     * @param host     目标主机
     * @param port     端口（通常 443）
     * @param path     路径（如 /liveweb）
     * @param origin   Origin 头（官网首页）
     * @param userAgent User-Agent
     * @param timeoutMs 连接超时
     * @return 握手成功（HTTP 101）返回 true
     */
    public boolean connect(String host, int port, String path,
                           String origin, String userAgent, int timeoutMs) {
        try {
            SSLCertificateSocketFactory factory =
                    (SSLCertificateSocketFactory) SSLCertificateSocketFactory.getDefault(15000, null);
            SSLSocket ssl = (SSLSocket) factory.createSocket();
            ssl.connect(new InetSocketAddress(host, port), timeoutMs);
            factory.setHostname(ssl, host);
            ssl.setSoTimeout(15000);
            ssl.startHandshake();

            mSocket = ssl;
            mIn = ssl.getInputStream();
            mOut = ssl.getOutputStream();

            byte[] keyBytes = new byte[16];
            mRandom.nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("User-Agent: ").append(userAgent).append("\r\n");
            req.append("Origin: ").append(origin).append("\r\n");
            req.append("\r\n");
            mOut.write(req.toString().getBytes("US-ASCII"));
            mOut.flush();

            String head = readHttpHead();
            if (head == null || !head.startsWith("HTTP/1.1 101")) {
                LogUtil.e(TAG + ": handshake failed: " + head);
                close();
                return false;
            }

            mRunning = true;
            mClosedByPeer = false;
            startReader();
            LogUtil.i(TAG + ": connected to " + host + path);
            return true;
        } catch (Exception e) {
            LogUtil.e(TAG + ": connect failed: " + e.getMessage());
            close();
            return false;
        }
    }

    /** 读取 HTTP 响应头（直到 \r\n\r\n）。 */
    private String readHttpHead() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int p3 = -1, p2 = -1, p1 = -1, ch;
        while ((ch = mIn.read()) != -1) {
            baos.write(ch);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && ch == '\n') {
                byte[] data = baos.toByteArray();
                return new String(data, 0, data.length - 4, "ISO-8859-1");
            }
            p3 = p2;
            p2 = p1;
            p1 = ch;
        }
        return null;
    }

    /** 后台读取线程：应答 ping，识别 close。 */
    private void startReader() {
        mReader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "hbtv-ws-reader");
        mReader.setDaemon(true);
        mReader.start();
    }

    private void readLoop() {
        try {
            while (mRunning) {
                int b0 = mIn.read();
                if (b0 < 0) break;
                int b1 = mIn.read();
                if (b1 < 0) break;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) {
                    int h1 = mIn.read();
                    int h2 = mIn.read();
                    len = ((long) h1 << 8) | (h2 & 0xFFL);
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) {
                        int c = mIn.read();
                        if (c < 0) { len = -1; break; }
                        len = (len << 8) | (c & 0xFFL);
                    }
                }
                if (len < 0 || len > 1 << 20) break;
                byte[] payload = new byte[(int) len];
                int off = 0;
                while (off < len) {
                    int n = mIn.read(payload, off, (int) len - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off < len) break;

                if (opcode == 0x9) {
                    sendFrame(0xA, payload); // pong
                } else if (opcode == 0x8) {
                    break; // close
                }
            }
        } catch (Exception ignored) {
            // 连接被对端关闭或读超时
        } finally {
            mClosedByPeer = true;
        }
    }

    /** 发送文本帧（客户端帧必须掩码）。 */
    public boolean sendText(String text) {
        try {
            sendFrame(0x1, text.getBytes("UTF-8"));
            return true;
        } catch (Exception e) {
            LogUtil.e(TAG + ": sendText failed: " + e.getMessage());
            return false;
        }
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (mOut == null || !mRunning) return;
        int len = payload.length;
        ByteArrayOutputStream header = new ByteArrayOutputStream(16);
        header.write(0x80 | opcode);
        if (len < 126) {
            header.write(0x80 | len);
        } else if (len < 65536) {
            header.write(0x80 | 126);
            header.write((len >> 8) & 0xFF);
            header.write(len & 0xFF);
        } else {
            header.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                header.write((int) ((len >> (i * 8)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        mRandom.nextBytes(mask);
        header.write(mask, 0, 4);
        mOut.write(header.toByteArray());
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        mOut.write(masked);
        mOut.flush();
    }

    public boolean isRunning() {
        return mRunning && !mClosedByPeer;
    }

    public void close() {
        mRunning = false;
        try {
            if (mSocket != null) mSocket.close();
        } catch (Exception ignored) {
        }
        mSocket = null;
        mIn = null;
        mOut = null;
    }
}