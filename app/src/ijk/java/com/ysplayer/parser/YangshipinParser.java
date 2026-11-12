package com.ysplayer.parser;

import android.util.Base64;

import com.ysplayer.LogUtil;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class YangshipinParser extends BaseParser {

    private static final String TAG = "Yangshipin";
    private static final String NAME = "央视频FHD";

    // ---- JCE 协议（主路径：jacc.ysp.cctv.cn） ----
    private static final String JCE_URL = "https://jacc.ysp.cctv.cn";
    private static final String JCE_VER_NAME = "3.2.7.26212";
    private static final String JCE_VER_CODE = "302070";
    private static final String JCE_APP_ID = "1200013";
    private static final int JCE_QMF_APP_ID = 10012;
    private static final int JCE_QMF_PLATFORM = 1;
    private static final int JCE_BIZ_ID = 0;
    private static final String JCE_CHAN_ID = "10070";
    private static final int JCE_CMD_TIMESHIFT = 25312;
    private static final String JCE_DEVICE_MODEL = "Pixel 6";

    // JCE 返回坏域名，必须走 bkliveinfo 的频道
    private static final Set<String> FORCE_BK_CHANNELS;
    static {
        Set<String> s = new HashSet<>();
        Collections.addAll(s,
            "cctv11", "cctv12", "cctv14", "cctv15", "cctv16", "cctv164k",
            "cctv17", "cctv4k", "cctvfyjc", "cctvdyjc", "cctvhjjc");
        FORCE_BK_CHANNELS = Collections.unmodifiableSet(s);
    }

    private static volatile String sJceGuid;

    // ---- bkliveinfo 回退 API ----
    private static final String API_URL = "https://bkliveinfo.ysp.cctv.cn/";
    private static final String REFERER = "https://live.cctv.cn/";

    // ---- capi 网页端频道列表 API ----
    private static final String CAPI_URL =
            "https://capi.yangshipin.cn/api/oms/pc/page/PG00000004";
    private static final long CAPT_CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    private static final int PLATFORM = 4330403;
    private static final String APP_VERSION = "V8.22.1035.3031";
    private static final String H264_CAPABILITY = "SChDb2RlYyBpbnN0YWxsZWQ6IEhBdkMgMS4wIik=";
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int API_MAX_RETRIES = 3;
    private static final long API_RETRY_BASE_DELAY_MS = 800;

    // ---- PID → channelId 缓存：首次访问题从 capi 动态获取，之后缓存 24h ----
    private static volatile Map<String, String> sPidToChannelId;
    private static volatile long sCapiCacheTimestamp;

    // ---- playurl 缓存 ----
    private static final Map<String, CachedUrl> sUrlCache = new ConcurrentHashMap<>();

    private static class CachedUrl {
        String m3u8Url;
        Map<String, String> httpHeaders;
        long expireTime;
    }

    // ============================================================
    // ProtoBuf 简易解析器（仅解 tag=2 的嵌套/字符串 + tag=0 的 varint）
    // ============================================================

    private static class PbReader {
        byte[] data;
        int offset;

        PbReader(byte[] data) {
            this.data = data;
            this.offset = 0;
        }

        int readVarint() {
            long value = 0;
            int shift = 0;
            while (offset < data.length) {
                int b = data[offset++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return (int) value;
        }

        boolean hasMore() {
            return offset < data.length;
        }
    }

    private static Map<Integer, Object> parseProtobuf(byte[] raw, int depth) {
        Map<Integer, Object> result = new HashMap<>();
        PbReader r = new PbReader(raw);
        while (r.hasMore()) {
            int tag = r.readVarint();
            int fid = tag >> 3;
            int wire = tag & 0x07;
            Object val = null;

            if (wire == 0) {
                val = r.readVarint();
            } else if (wire == 2) {
                int length = r.readVarint();
                byte[] sub = new byte[length];
                System.arraycopy(r.data, r.offset, sub, 0, length);
                r.offset += length;
                if (depth < 5) {
                    try {
                        String text = new String(sub, "UTF-8");
                        boolean hasCtrl = false;
                        for (int i = 0; i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                                hasCtrl = true;
                                break;
                            }
                        }
                        if (hasCtrl) {
                            val = parseProtobuf(sub, depth + 1);
                        } else {
                            val = text;
                        }
                    } catch (Exception e) {
                        try {
                            val = parseProtobuf(sub, depth + 1);
                        } catch (Exception ex) {
                            val = sub;
                        }
                    }
                } else {
                    val = sub;
                }
            } else {
                r.offset++;
                continue;
            }

            Object existing = result.get(fid);
            if (existing == null) {
                result.put(fid, val);
            } else if (existing instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) existing;
                list.add(val);
            } else {
                java.util.List<Object> list = new java.util.ArrayList<>();
                list.add(existing);
                list.add(val);
                result.put(fid, list);
            }
        }
        return result;
    }

    // ============================================================
    // JCE 协议实现：无需硬编码加密密钥，仅需随机 GUID
    // 来源：参考 ysp-live-main 项目
    // ============================================================

    private static class JceWriter {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        void writeHead(int typ, int tag) {
            if (tag < 15) {
                bos.write(((tag & 0xf) << 4) | (typ & 0xf));
            } else {
                bos.write(0xf0 | (typ & 0xf));
                bos.write(tag);
            }
        }

        void writeByte(int v, int tag) {
            if (v == 0) {
                writeHead(12, tag);
            } else {
                writeHead(0, tag);
                bos.write((byte) v);
            }
        }

        void writeShort(int v, int tag) {
            if (v >= -128 && v <= 127) {
                writeByte(v, tag);
            } else {
                writeHead(1, tag);
                bos.write((v >> 8) & 0xFF);
                bos.write(v & 0xFF);
            }
        }

        void writeInt(int v, int tag) {
            if (v >= -32768 && v <= 32767) {
                writeShort(v, tag);
            } else {
                writeHead(2, tag);
                bos.write((v >> 24) & 0xFF);
                bos.write((v >> 16) & 0xFF);
                bos.write((v >> 8) & 0xFF);
                bos.write(v & 0xFF);
            }
        }

        void writeLong(long v, int tag) {
            if (v >= -2147483648L && v <= 2147483647L) {
                writeInt((int) v, tag);
            } else {
                writeHead(3, tag);
                bos.write((int) ((v >> 56) & 0xFF));
                bos.write((int) ((v >> 48) & 0xFF));
                bos.write((int) ((v >> 40) & 0xFF));
                bos.write((int) ((v >> 32) & 0xFF));
                bos.write((int) ((v >> 24) & 0xFF));
                bos.write((int) ((v >> 16) & 0xFF));
                bos.write((int) ((v >> 8) & 0xFF));
                bos.write((int) (v & 0xFF));
            }
        }

        void writeString(String s, int tag) {
            if (s == null) return;
            try {
                byte[] data = s.getBytes("UTF-8");
                if (data.length > 255) {
                    writeHead(7, tag);
                    writeIntBE(data.length);
                    try { bos.write(data); } catch (Exception ignored) {}
                } else {
                    writeHead(6, tag);
                    bos.write(data.length);
                    try { bos.write(data); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        void writeBytes(byte[] data, int tag) {
            writeHead(13, tag);
            writeHead(0, 0);
            writeInt(data.length, 0);
            try { bos.write(data); } catch (Exception ignored) {}
        }

        void writeStructBegin(int tag) {
            writeHead(10, tag);
        }

        void writeStructEnd() {
            writeHead(11, 0);
        }

        void writeFloat(float v, int tag) {
            writeHead(4, tag);
            int bits = Float.floatToIntBits(v);
            writeIntBE(bits);
        }

        void writeDouble(double v, int tag) {
            writeHead(5, tag);
            long bits = Double.doubleToLongBits(v);
            bos.write((int) ((bits >> 56) & 0xFF));
            bos.write((int) ((bits >> 48) & 0xFF));
            bos.write((int) ((bits >> 40) & 0xFF));
            bos.write((int) ((bits >> 32) & 0xFF));
            bos.write((int) ((bits >> 24) & 0xFF));
            bos.write((int) ((bits >> 16) & 0xFF));
            bos.write((int) ((bits >> 8) & 0xFF));
            bos.write((int) (bits & 0xFF));
        }

        void writeIntBE(int v) {
            bos.write((v >> 24) & 0xFF);
            bos.write((v >> 16) & 0xFF);
            bos.write((v >> 8) & 0xFF);
            bos.write(v & 0xFF);
        }

        byte[] toBytes() {
            return bos.toByteArray();
        }
    }

    private static String getJceGuid() {
        if (sJceGuid != null) return sJceGuid;
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            sb.append("0123456789abcdef".charAt(SECURE_RANDOM.nextInt(16)));
        }
        sJceGuid = sb.toString();
        return sJceGuid;
    }

    private static void buildQua(JceWriter w) {
        w.writeString(JCE_VER_NAME, 0);
        w.writeString(JCE_VER_CODE, 1);
        w.writeInt(1080, 2);
        w.writeInt(2400, 3);
        w.writeInt(3, 4);
        w.writeString("12", 5);
        w.writeInt(1, 6);
        w.writeInt(1, 7);
        w.writeInt(420, 8);
        w.writeString(JCE_CHAN_ID, 9);
        for (int i = 10; i <= 14; i++) w.writeString("", i);
        w.writeStructBegin(15);
        w.writeInt(0, 0);
        w.writeByte(0, 1);
        w.writeString("", 2);
        w.writeStructEnd();
        w.writeString("", 16);
        w.writeString("", 17);
        w.writeString("", 18);
        w.writeStructBegin(19);
        w.writeInt(0, 0);
        w.writeFloat(0, 1);
        w.writeFloat(0, 2);
        w.writeLong(0, 3);
        w.writeStructEnd();
        String guid = getJceGuid();
        w.writeString(guid.substring(0, 16), 20);
        w.writeString(JCE_DEVICE_MODEL, 21);
        w.writeInt(1, 22);
        for (int i = 23; i <= 26; i++) w.writeInt(0, i);
        w.writeString("", 27);
        w.writeString("", 28);
        w.writeString(guid, 29);
    }

    private static void buildJceHead(JceWriter w, int cmd, int reqid) {
        w.writeInt(reqid, 0);
        w.writeInt(cmd, 1);
        w.writeStructBegin(2);
        buildQua(w);
        w.writeStructEnd();
        w.writeString(JCE_APP_ID, 3);
        w.writeString(getJceGuid(), 4);
        w.writeHead(9, 5);
        w.writeInt(0, 0);
        w.writeStructBegin(6);
        w.writeStructEnd();
        w.writeHead(9, 7);
        w.writeInt(0, 0);
        w.writeInt(0, 8);
        w.writeInt(0, 9);
        w.writeInt(0, 10);
    }

    private static byte[] wrapJcePacket(int cmd, byte[] body, int reqid) throws Exception {
        JceWriter w = new JceWriter();
        w.writeStructBegin(0);
        buildJceHead(w, cmd, reqid);
        w.writeStructEnd();
        w.writeBytes(body, 1);
        byte[] reqcmd = w.toBytes();

        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.write(38);
        int innerPayloadLen = reqcmd.length + 17;
        inner.write((innerPayloadLen >> 24) & 0xFF);
        inner.write((innerPayloadLen >> 16) & 0xFF);
        inner.write((innerPayloadLen >> 8) & 0xFF);
        inner.write(innerPayloadLen & 0xFF);
        inner.write(1);
        for (int i = 0; i < 10; i++) inner.write(0);
        inner.write(reqcmd);
        inner.write(40);
        byte[] innerBytes = inner.toByteArray();

        ByteArrayOutputStream compBaos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(compBaos);
        gzos.write(innerBytes);
        gzos.finish();
        byte[] comp = compBaos.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(19);
        out.write(0); out.write(0); out.write(0); out.write(0);
        out.write(0); out.write(2);
        out.write((65281 >> 8) & 0xFF); out.write(65281 & 0xFF);
        out.write((cmd >> 8) & 0xFF); out.write(cmd & 0xFF);
        out.write(0); out.write(0);
        writeInt64BE(out, reqid);
        writeInt32BE(out, 531);
        writeInt32BE(out, JCE_QMF_APP_ID);
        writeInt64BE(out, JCE_BIZ_ID);
        byte[] guidBytes = getJceGuid().getBytes("UTF-8");
        out.write(guidBytes, 0, Math.min(32, guidBytes.length));
        for (int i = guidBytes.length; i < 32; i++) out.write(0);
        out.write((byte) JCE_QMF_PLATFORM);
        writeInt32BE(out, Integer.parseInt(JCE_VER_CODE));
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(0);
        out.write(0); out.write(0);
        out.write(0); out.write(0);
        writeInt32BE(out, innerBytes.length);
        out.write(comp);
        out.write(3);

        byte[] result = out.toByteArray();
        int totalLen = result.length;
        result[1] = (byte) ((totalLen >> 24) & 0xFF);
        result[2] = (byte) ((totalLen >> 16) & 0xFF);
        result[3] = (byte) ((totalLen >> 8) & 0xFF);
        result[4] = (byte) (totalLen & 0xFF);
        return result;
    }

    // ============================================================
    // JCE 响应解析器（完整 struct 解析，匹配 ysp-live.py 的 R 类行为）
    // ============================================================

    private static class JceReader {
        final byte[] data;
        int p;
        final int end;

        JceReader(byte[] data, int off, int end) {
            this.data = data;
            this.p = off;
            this.end = end;
        }

        boolean hasMore() { return p < end; }
        int u8() { return data[p++] & 0xFF; }

        int[] head() {
            int b = u8();
            int typ = b & 0xf;
            int tag = (b >> 4) & 0xf;
            if (tag == 15) tag = u8();
            return new int[]{typ, tag};
        }

        int readInt() {
            int v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | (data[p + i] & 0xFF);
            p += 4;
            return v;
        }

        long readLong() {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[p + i] & 0xFF);
            p += 8;
            return v;
        }

        int decodeInt() {
            int[] ht = head();
            int t = ht[0];
            int v;
            if (t == 0) v = data[p++] & 0xFF;
            else if (t == 1) { v = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; }
            else if (t == 2) v = readInt();
            else if (t == 12) v = 0;
            else v = 0;
            return v;
        }

        Object fieldValue() {
            int[] ht = head();
            int t = ht[0];
            switch (t) {
                case 0:  return data[p++] & 0xFF;
                case 1:  { int v = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; return v; }
                case 2:  return readInt();
                case 3:  return readLong();
                case 4:  { p += 4; return null; }
                case 5:  { p += 8; return null; }
                case 6:  { int n = u8(); String s = safeString(n); return s; }
                case 7:  { int n = readInt(); String s = safeString(n); return s; }
                case 8:  return readMap();
                case 9:  return readList();
                case 10: return readStruct();
                case 11: return null;
                case 12: return 0;
                case 13: {
                    // 格式: head(13,tag) + head(0,0) + int(count,0) + data
                    head();  // 吃掉内部的 head(0,0)
                    int count = decodeInt();
                    byte[] raw = new byte[count];
                    System.arraycopy(data, p, raw, 0, count);
                    p += count;
                    return raw;
                }
                default: return null;
            }
        }

        private int decodeIntFromHead(int[] ht) {
            int t = ht[0], v;
            if (t == 0) v = data[p++] & 0xFF;
            else if (t == 1) { v = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; }
            else if (t == 2) v = readInt();
            else if (t == 12) v = 0;
            else v = 0;
            return v;
        }

        private String safeString(int len) {
            try {
                String s = new String(data, p, len, "UTF-8");
                p += len;
                return s;
            } catch (Exception e) {
                p += len;
                return "";
            }
        }

        Map<Integer, Object> readStruct() {
            Map<Integer, Object> m = new HashMap<>();
            while (hasMore()) {
                int[] ht = head();
                int t = ht[0], tag = ht[1];
                if (t == 11) break;
                m.put(tag, fieldValueForType(t));
            }
            return m;
        }

        private Object fieldValueForType(int t) {
            switch (t) {
                case 0:  return data[p++] & 0xFF;
                case 1:  { int v = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; return v; }
                case 2:  return readInt();
                case 3:  return readLong();
                case 4:  { p += 4; return null; }
                case 5:  { p += 8; return null; }
                case 6:  { int n = u8(); String s = safeString(n); return s; }
                case 7:  { int n = readInt(); String s = safeString(n); return s; }
                case 8:  return readMap();
                case 9:  return readList();
                case 10: return readStruct();
                case 11: return null;
                case 12: return 0;
                case 13: {
                    head();
                    int count = decodeInt();
                    byte[] raw = new byte[count];
                    System.arraycopy(data, p, raw, 0, count);
                    p += count;
                    return raw;
                }
                default: return null;
            }
        }

        private Map<Object, Object> readMap() {
            int n = decodeInt();
            Map<Object, Object> m = new HashMap<>();
            for (int i = 0; i < n && hasMore(); i++) {
                Object k = fieldValue();
                Object v = fieldValue();
                m.put(k, v);
            }
            return m;
        }

        private java.util.List<Object> readList() {
            int n = decodeInt();
            java.util.List<Object> list = new java.util.ArrayList<>();
            for (int i = 0; i < n && hasMore(); i++) {
                list.add(fieldValue());
            }
            return list;
        }
    }

    private static byte[] unwrapJceResponse(byte[] data) throws Exception {
        if (data.length < 90 || (data[0] & 0xFF) != 0x13) return null;
        int flags = ((data[21] & 0xFF) << 24) | ((data[22] & 0xFF) << 16)
                | ((data[23] & 0xFF) << 8) | (data[24] & 0xFF);
        int payloadLen = data.length - 90;
        byte[] payload;
        if ((flags & 2) != 0) {
            GZIPInputStream gzis = new GZIPInputStream(
                    new ByteArrayInputStream(data, 89, payloadLen));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = gzis.read(buf)) != -1) bos.write(buf, 0, n);
            payload = bos.toByteArray();
        } else {
            payload = new byte[payloadLen];
            System.arraycopy(data, 89, payload, 0, payloadLen);
        }
        if (payload.length < 17 || payload[0] != '&' || payload[payload.length - 1] != '(')
            return null;

        JceReader r = new JceReader(payload, 16, payload.length - 1);
        Map<Integer, Object> rc = r.readStruct();
        return (byte[]) rc.get(1);
    }

    private static int readJceInt(byte[] data, int off, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (data[off + i] & 0xFF);
        return v;
    }

    // ============================================================
    // JCE 旧版 skip 方法保留用于向后兼容（实际已不使用）
    // ============================================================

    private static int skipJceValue(byte[] data, int p, int end) {
        if (p >= end) return p;
        int b = data[p++] & 0xFF;
        int typ = b & 0xf;
        int tag = (b >> 4) & 0xf;
        if (tag == 15) p++;
        switch (typ) {
            case 0: return p + 1;
            case 1: return p + 2;
            case 2: return p + 4;
            case 3: return p + 8;
            case 4: return p + 4;
            case 5: return p + 8;
            case 6: return p + 1 + (data[p] & 0xFF);
            case 7: return p + 4 + readJceInt(data, p, 4);
            case 8: return skipJceMap(data, p, end);
            case 9: return skipJceList(data, p, end);
            case 10: return skipJceStruct(data, p, end);
            case 12: return p;
            case 13: return p = skipJceBytes(data, p, end);
            default: return end;
        }
    }

    private static int skipJceBytes(byte[] data, int p, int end) {
         if (p >= end) return end;
        // 格式: head(0,0) + int(count,0) — _int() 模式
        int[] htInner = readJceHead(data, p);
        p = htInner[0]; // 跳过 head(0,0)
        // 再读 count 的 type+value
        int[] htCount = readJceHead(data, p);
        p = htCount[0]; int ct = htCount[1];
        int count;
        if (ct == 12) count = 0;
        else if (ct == 0) { count = data[p++] & 0xFF; }
        else if (ct == 1) { count = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; }
        else if (ct == 2) { count = readJceInt(data, p, 4); p += 4; }
        else return end;
        return p + count;
    }

    private static int[] readJceHead(byte[] data, int p) {
        int b = data[p++] & 0xFF;
        int typ = b & 0xf;
        int tag = (b >> 4) & 0xf;
        if (tag == 15) tag = data[p++] & 0xFF;
        return new int[]{p, typ, tag};
    }

    private static int skipJceStruct(byte[] data, int p, int end) {
        while (p < end) {
            int b = data[p] & 0xFF;
            if ((b & 0xf) == 11 && (b >> 4) == 0) return p + 1;
            p = skipJceValue(data, p, end);
        }
        return end;
    }

    private static int skipJceMap(byte[] data, int p, int end) {
        int[] ht = readJceHead(data, p);
        p = ht[0]; int t = ht[1];
        int n;
        if (t == 12) n = 0;
        else if (t == 0) { n = data[p++] & 0xFF; }
        else if (t == 1) { n = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; }
        else if (t == 2) { n = readJceInt(data, p, 4); p += 4; }
        else n = 0;
        for (int i = 0; i < n * 2 && p < end; i++) p = skipJceValue(data, p, end);
        return p;
    }

    private static int skipJceList(byte[] data, int p, int end) {
        int[] ht = readJceHead(data, p);
        p = ht[0]; int t = ht[1];
        int n;
        if (t == 12) n = 0;
        else if (t == 0) { n = data[p++] & 0xFF; }
        else if (t == 1) { n = ((data[p] & 0xFF) << 8) | (data[p+1] & 0xFF); p += 2; }
        else if (t == 2) { n = readJceInt(data, p, 4); p += 4; }
        else n = 0;
        for (int i = 0; i < n && p < end; i++) p = skipJceValue(data, p, end);
        return p;
    }

    private static void writeInt32BE(ByteArrayOutputStream bos, int v) {
        bos.write((v >> 24) & 0xFF);
        bos.write((v >> 16) & 0xFF);
        bos.write((v >> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static void writeInt64BE(ByteArrayOutputStream bos, long v) {
        bos.write((int) ((v >> 56) & 0xFF));
        bos.write((int) ((v >> 48) & 0xFF));
        bos.write((int) ((v >> 40) & 0xFF));
        bos.write((int) ((v >> 32) & 0xFF));
        bos.write((int) ((v >> 24) & 0xFF));
        bos.write((int) ((v >> 16) & 0xFF));
        bos.write((int) ((v >> 8) & 0xFF));
        bos.write((int) (v & 0xFF));
    }

    private static String jceTimeshiftUrl(String pid, String sid) throws Exception {
        JceWriter bodyW = new JceWriter();
        bodyW.writeString(pid, 0);
        bodyW.writeString(sid, 1);
        long now = System.currentTimeMillis() / 1000L;
        bodyW.writeLong(now - 300, 2);
        bodyW.writeLong(now, 3);
        bodyW.writeString("fhd", 4);
        byte[] body = bodyW.toBytes();

        int reqid = (int) (System.currentTimeMillis() & 0x7fffffff);
        byte[] packet = wrapJcePacket(JCE_CMD_TIMESHIFT, body, reqid);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/octet-stream");

        HttpFetcher.Response resp = HttpFetcher.post(JCE_URL, headers, packet);
        if (resp.code != 200) {
            throw new RuntimeException("JCE HTTP " + resp.code);
        }

        byte[] respBody = unwrapJceResponse(resp.body);
        if (respBody == null || respBody.length == 0) {
            throw new RuntimeException("JCE empty response");
        }
        JceReader bodyReader = new JceReader(respBody, 0, respBody.length);
        Map<Integer, Object> bodyMap = bodyReader.readStruct();
        Object errObj = bodyMap.get(0);
        int errCode = errObj instanceof Integer ? (Integer) errObj : 0;
        if (errCode != 0) {
            String errMsg = bodyMap.get(1) instanceof String ? (String) bodyMap.get(1) : "";
            throw new RuntimeException("JCE errCode=" + errCode + " " + errMsg);
        }
        String m3u8Url = (String) bodyMap.get(2);
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            throw new RuntimeException("JCE empty m3u8");
        }
        if (m3u8Url.contains("liverecord.video.cloud.cctv.com")) {
            throw new DeadHostException("JCE dead CDN host");
        }
        return m3u8Url;
    }

    private static class DeadHostException extends RuntimeException {
        DeadHostException(String msg) { super(msg); }
    }

    // JCE 失效频道缓存：避免每次都重试已知失败的频道
    private static final Map<String, Long> sJceDeadCache = new ConcurrentHashMap<>();
    private static final long JCE_DEAD_CACHE_TTL_MS = 30 * 60 * 1000L;

    // ============================================================
    // capi 频道列表获取 → PID→channelId 映射
    // ============================================================

    public static void warmUp(final String userAgent) {
        if (sPidToChannelId != null) return;
        execute(new Runnable() {
            @Override
            public void run() {
                fetchCapiChannelList(userAgent);
            }
        });
    }

    public static void invalidatePlayUrlCache(String url) {
        if (url == null) return;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]pid=([^&]*)").matcher(url);
        String pid = m.find() ? m.group(1) : null;
        if (pid != null) {
            sUrlCache.remove(pid);
            LogUtil.i(TAG + ": invalidate cache for pid=" + pid);
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, String> getPidToChannelId() {
        long now = System.currentTimeMillis();
        if (sPidToChannelId != null && (now - sCapiCacheTimestamp) < CAPT_CACHE_TTL_MS) {
            return sPidToChannelId;
        }
        return sPidToChannelId;
    }

    private static synchronized void fetchCapiChannelList(String userAgent) {
        long now = System.currentTimeMillis();
        if (sPidToChannelId != null && (now - sCapiCacheTimestamp) < CAPT_CACHE_TTL_MS) {
            return;
        }

        Map<String, String> map = new HashMap<>();
        try {
            LogUtil.d(TAG + ": 从 capi 获取频道列表...");
            String capiUrl = CAPI_URL + "?t=" + System.currentTimeMillis();
            Map<String, String> capiHeaders = new HashMap<>();
            capiHeaders.put("User-Agent", resolveUa(userAgent));
            capiHeaders.put("Referer", "https://www.yangshipin.cn/");
            capiHeaders.put("yspappid", "519748109");
            capiHeaders.put("platform", "109");
            capiHeaders.put("app-platform", "pc");
            capiHeaders.put("app-version", "1.0.0");

            HttpFetcher.Response capiResp = HttpFetcher.get(capiUrl, capiHeaders);
            int code = capiResp.code;
            if (code != 200) {
                LogUtil.e(TAG + ": capi HTTP " + code);
                return;
            }

            Map<Integer, Object> root = parseProtobuf(capiResp.body, 0);
            int retCode = toInt(root.get(1));
            if (retCode != 200) {
                LogUtil.e(TAG + ": capi retCode=" + retCode);
                return;
            }

            Object pageObj = root.get(2);
            if (!(pageObj instanceof Map)) return;
            Map<Integer, Object> page = (Map<Integer, Object>) pageObj;
            Object modulesObj = page.get(2);
            if (!(modulesObj instanceof Map)) return;
            Map<Integer, Object> modules = (Map<Integer, Object>) modulesObj;

            Object chListObj = modules.get(15);
            java.util.List<Object> chList;
            if (chListObj instanceof java.util.List) {
                chList = (java.util.List<Object>) chListObj;
            } else if (chListObj != null) {
                chList = new java.util.ArrayList<>();
                chList.add(chListObj);
            } else {
                return;
            }

            for (Object chObj : chList) {
                if (!(chObj instanceof Map)) continue;
                Map<Integer, Object> ch = (Map<Integer, Object>) chObj;
                String pid = toString(ch.get(4));
                String cid = toString(ch.get(6));
                String name = toString(ch.get(2));
                if (pid != null && !pid.isEmpty() && cid != null && !cid.isEmpty()) {
                    map.put(pid, cid);
                    LogUtil.d(TAG + ": capi映射 " + pid + " → " + cid + " (" + name + ")");
                }
            }

            sPidToChannelId = map;
            sCapiCacheTimestamp = now;
            LogUtil.i(TAG + ": capi频道列表获取成功, " + map.size() + "个频道");
        } catch (Exception e) {
            LogUtil.e(TAG + ": capi获取失败", e);
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Integer) return (Integer) v;
        if (v instanceof Long) return ((Long) v).intValue();
        return 0;
    }

    private static String toString(Object v) {
        if (v instanceof String) return (String) v;
        if (v == null) return null;
        return v.toString();
    }

    /**
     * TEA/CBC 加密密钥与 XOR 常数。
     * 来源：央视频移动端 APK 二进制中硬编码的客户端指纹参数，
     * 与参考 iptv-main/extractors/yangshipin/ckey.js 中的 CKEY_TEA_KEY 等完全一致。
     * 这些是客户端身份加密常数（非视频流解密密钥），仅在央视频 App 大版本升级时可能变化。
     * 如果 cKey 票据生成后 API 持续返回 iretcode!=0，此处的密钥可能需要对照新 APK 更新。
     */
    private static final byte[] TEA_KEY = hexToBytes("59b2f7cf725ef43c34fdd7c123411ed3");
    private static final byte[] GUARD_KEY = hexToBytes("110DBEC10C23E7D2E56A1CAD6914EF1B");
    private static final byte[] CKEY_XOR = {
        (byte) 0x84, (byte) 0x2e, (byte) 0xed, (byte) 0x08,
        (byte) 0xf0, (byte) 0x66, (byte) 0xe6, (byte) 0xea,
        (byte) 0x48, (byte) 0xb4, (byte) 0xca, (byte) 0xa9,
        (byte) 0x91, (byte) 0xed, (byte) 0x6f, (byte) 0xf3
    };
    private static final byte[] GUARD_XOR = {
        (byte) 0xb3, (byte) 0xc9, (byte) 0x53, (byte) 0xa0,
        (byte) 0x69, (byte) 0x13, (byte) 0xad, (byte) 0x4d
    };
    private static final int TEA_DELTA = 0x9E3779B9;
    private static final int TEA_ROUNDS = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static byte[] hexToBytes(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    private static int u32(int v) {
        return (int) (v & 0xFFFFFFFFL);
    }

    private static long u32l(long v) {
        return v & 0xFFFFFFFFL;
    }

    private static byte[] intToBytesBE(int v) {
        return new byte[] {
            (byte) ((v >>> 24) & 0xFF),
            (byte) ((v >>> 16) & 0xFF),
            (byte) ((v >>> 8) & 0xFF),
            (byte) (v & 0xFF)
        };
    }

    private static int bytesToIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                | (b[off + 3] & 0xFF);
    }

    private static byte[] teaEncryptBlock(byte[] block, byte[] key) {
        int y = bytesToIntBE(block, 0);
        int z = bytesToIntBE(block, 4);
        int[] k = new int[4];
        for (int i = 0; i < 4; i++) k[i] = bytesToIntBE(key, i * 4);

        int s = 0;
        for (int i = 0; i < TEA_ROUNDS; i++) {
            s = u32(s + TEA_DELTA);
            y = u32(y + u32(u32((z << 4) + k[0]) ^ u32(z + s) ^ u32((z >>> 5) + k[1])));
            z = u32(z + u32(u32((y << 4) + k[2]) ^ u32(y + s) ^ u32((y >>> 5) + k[3])));
        }

        byte[] out = new byte[8];
        System.arraycopy(intToBytesBE(y), 0, out, 0, 4);
        System.arraycopy(intToBytesBE(z), 0, out, 4, 4);
        return out;
    }

    private static int checksum(byte[] data) {
        long v = 0;
        for (byte b : data) v = (u32l(v * 0x83) + (b & 0xFF)) & 0x7FFFFFFF;
        return (int) v;
    }

    private static byte[] encryptTeaPacket(byte[] data, byte[] key) {
        int padLen = (8 - ((data.length + 10) % 8)) % 8;
        byte[] random = new byte[padLen + 9];
        SECURE_RANDOM.nextBytes(random);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write((random[0] & 0xF8) | padLen);
        bos.write(random, 1, padLen);
        bos.write(random, padLen + 1, 2);
        bos.write(data, 0, data.length);
        for (int i = 0; i < 7; i++) bos.write(0);
        byte[] plain = bos.toByteArray();

        byte[] output = new byte[plain.length];
        byte[] prevPlain = new byte[8];
        byte[] prevCipher = new byte[8];

        for (int off = 0; off < plain.length; off += 8) {
            byte[] src = new byte[8];
            System.arraycopy(plain, off, src, 0, 8);

            byte[] mixed = new byte[8];
            for (int i = 0; i < 8; i++) mixed[i] = (byte) (src[i] ^ prevCipher[i]);

            byte[] encrypted = teaEncryptBlock(mixed, key);

            byte[] cipher = new byte[8];
            for (int i = 0; i < 8; i++) cipher[i] = (byte) (encrypted[i] ^ prevPlain[i]);

            System.arraycopy(cipher, 0, output, off, 8);
            prevPlain = mixed;
            prevCipher = cipher;
        }
        return output;
    }

    private static byte[] lengthPrefixed(String s) {
        try {
            byte[] data = s.getBytes("UTF-8");
            byte[] result = new byte[2 + data.length];
            result[0] = (byte) ((data.length >>> 8) & 0xFF);
            result[1] = (byte) (data.length & 0xFF);
            System.arraycopy(data, 0, result, 2, data.length);
            return result;
        } catch (Exception e) {
            return new byte[2];
        }
    }

    private static byte[] lengthPrefixedBytes(byte[] data) {
        byte[] result = new byte[2 + data.length];
        result[0] = (byte) ((data.length >>> 8) & 0xFF);
        result[1] = (byte) (data.length & 0xFF);
        System.arraycopy(data, 0, result, 2, data.length);
        return result;
    }

    private static void bosWriteBA(ByteArrayOutputStream bos, byte[] data) {
        if (data != null && data.length > 0) bos.write(data, 0, data.length);
    }

    private static String tail5(String s) {
        if (s == null) return "";
        if (s.length() < 5) return "";
        return s.substring(s.length() - 5);
    }

    private static String createGuard(long timestamp, String guid) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(intToBytesBE((int) timestamp), 0, 4);
        bosWriteBA(bos, lengthPrefixed(tail5(guid)));
        bosWriteBA(bos, lengthPrefixed(tail5("null")));
        bosWriteBA(bos, lengthPrefixed(tail5("null")));
        bosWriteBA(bos, lengthPrefixed("-1"));
        byte[] body = bos.toByteArray();

        byte[] plain = lengthPrefixedBytes(body);
        byte[] encrypted = encryptTeaPacket(plain, GUARD_KEY);

        int cs = checksum(plain);
        byte[] csBytes = intToBytesBE(cs);

        byte[] result = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, result, 0, encrypted.length);
        System.arraycopy(csBytes, 0, result, encrypted.length, 4);

        for (int i = 0; i < result.length; i++) result[i] ^= GUARD_XOR[i & 7];

        StringBuilder hex = new StringBuilder();
        for (byte b : result) hex.append(String.format("%02X", b & 0xFF));
        return hex.toString();
    }

    private static byte[] buildPacket(String channelId, long timestamp,
            String guid, String guard, String uid) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(hexToBytes("0000004200000004000004d2"), 0, 12);
        bos.write(intToBytesBE(PLATFORM), 0, 4);
        bos.write(intToBytesBE(0), 0, 4);
        bos.write(intToBytesBE((int) timestamp), 0, 4);
        bosWriteBA(bos, lengthPrefixed("dcgh"));
        bosWriteBA(bos, lengthPrefixed("_zj1A5Gh6QYcxWjIUGos2w=="));
        bosWriteBA(bos, lengthPrefixed(APP_VERSION));
        bosWriteBA(bos, lengthPrefixed(channelId));
        bosWriteBA(bos, lengthPrefixed(guid));
        bos.write(intToBytesBE(1), 0, 4);
        bos.write(intToBytesBE(1), 0, 4);
        bosWriteBA(bos, lengthPrefixed(uid));
        bosWriteBA(bos, lengthPrefixed("nil"));
        bosWriteBA(bos, lengthPrefixed("57eab0c4-2c58-44c6-8ae9-dd2757525dc5"));
        bosWriteBA(bos, lengthPrefixed("nil"));
        bosWriteBA(bos, lengthPrefixed("v0.1.000"));
        bosWriteBA(bos, lengthPrefixed("com.cctv.yangshipin.app.iphone"));
        bosWriteBA(bos, lengthPrefixed(String.valueOf(PLATFORM)));
        bosWriteBA(bos, lengthPrefixed("ex_json_bus"));
        bosWriteBA(bos, lengthPrefixed("ex_json_vs"));
        bosWriteBA(bos, lengthPrefixed(guard));
        byte[] body = bos.toByteArray();

        byte[] packet = new byte[2 + body.length];
        packet[0] = (byte) ((body.length >>> 8) & 0xFF);
        packet[1] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, packet, 2, body.length);

        int cs = checksum(packet);
        byte[] csBytes = intToBytesBE(cs);
        System.arraycopy(csBytes, 0, packet, 18, 4);
        return packet;
    }

    private static class CKeyTicket {
        String cKey;
        String guid;
        long timestamp;
        String flowId;
        String uid;
    }

    private static CKeyTicket createCKey(String channelId) {
        long timestamp = System.currentTimeMillis() / 1000L;
        byte[] guidBytes = new byte[16];
        SECURE_RANDOM.nextBytes(guidBytes);
        StringBuilder guidHex = new StringBuilder();
        for (byte b : guidBytes) guidHex.append(String.format("%02x", b));
        String guid = guidHex.toString();

        byte[] uidBytes = new byte[4];
        SECURE_RANDOM.nextBytes(uidBytes);
        StringBuilder uidHex = new StringBuilder();
        for (byte b : uidBytes) uidHex.append(String.format("%02X", b));
        String uid = uidHex.toString();

        String guard = createGuard(timestamp, guid);
        byte[] packet = buildPacket(channelId, timestamp, guid, guard, uid);
        byte[] encrypted = encryptTeaPacket(packet, TEA_KEY);

        int cs = checksum(packet);
        byte[] csBytes = intToBytesBE(cs);

        byte[] withCs = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, withCs, 0, encrypted.length);
        System.arraycopy(csBytes, 0, withCs, encrypted.length, 4);

        for (int i = 0; i < withCs.length; i++) withCs[i] ^= CKEY_XOR[i & 15];

        String b64 = Base64.encodeToString(withCs, Base64.NO_WRAP)
                .replace('+', '_')
                .replace('/', '-');
        while (b64.endsWith("=")) b64 = b64.substring(0, b64.length() - 1);

        CKeyTicket ticket = new CKeyTicket();
        ticket.cKey = "--01" + b64;
        ticket.guid = guid;
        ticket.timestamp = timestamp;
        ticket.flowId = UUID.randomUUID().toString().replace("-", "").toUpperCase()
                + "_" + PLATFORM;
        ticket.uid = uid;
        return ticket;
    }

    // ============================================================
    // bkliveinfo API 请求
    // ============================================================

    private static Map<String, String> buildParams(String channelId, String pid, String defn) {
        CKeyTicket ticket = createCKey(channelId);

        Map<String, String> params = new HashMap<>();
        params.put("atime", "120");
        params.put("livepid", pid);
        params.put("cnlid", channelId);
        params.put("appVer", APP_VERSION);
        params.put("app_version", "300090");
        params.put("caplv", "1");
        params.put("cmd", "2");
        params.put("defn", defn);
        params.put("device", "iPhone");
        params.put("encryptVer", "4.2");
        params.put("getpreviewinfo", "0");
        params.put("hevclv", "0");
        params.put("lang", "zh-Hans_CN");
        params.put("livequeue", "0");
        params.put("logintype", "1");
        params.put("nettype", "1");
        params.put("newnettype", "1");
        params.put("newplatform", String.valueOf(PLATFORM));
        params.put("platform", String.valueOf(PLATFORM));
        params.put("sdtfrom", "v3021");
        params.put("spacode", "23");
        params.put("spaudio", "1");
        params.put("spdemuxer", "6");
        params.put("spdrm", "2");
        params.put("spdynamicrange", "1");
        params.put("spflv", "1");
        params.put("spflvaudio", "1");
        params.put("sphdrfps", "60");
        params.put("sphttps", "1");
        params.put("spvcode", H264_CAPABILITY);
        params.put("spvideo", "4");
        params.put("stream", "1");
        params.put("system", "1");
        params.put("sysver", "ios18.2.1");
        params.put("uhd_flag", "0");
        params.put("cKey", ticket.cKey);
        params.put("guid", ticket.guid);
        params.put("fntick", String.valueOf(ticket.timestamp));
        params.put("flowid", ticket.flowId);
        params.put("playbacktime", "0");
        params.put("_uid", ticket.uid);
        return params;
    }

    // ============================================================
    // Parser 接口
    // ============================================================

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void clearCache() {
        sUrlCache.clear();
        sJceDeadCache.clear();
        sPidToChannelId = null;
        sCapiCacheTimestamp = 0;
    }

    @Override
    public boolean canParse(String url) {
        if (url == null) return false;
        return url.contains("yangshipin.cn/tv/home?pid=");
    }

    @Override
    public void parse(final String url, final String userAgent, final ParseCallback callback) {
        executeParse(url, userAgent, new Runnable() {
            @Override
            public void run() {
                try {
                    ParseResult result = resolve(url, userAgent);
                    if (result != null && result.isValid()) {
                        callback.onSuccess(result);
                    } else {
                        callback.onError("央视频540P解析失败");
                    }
                } catch (Exception e) {
                    LogUtil.e(TAG + ": exception", e);
                    callback.onError("央视频540P异常: " + e.getMessage());
                }
            }
        });
    }

    private ParseResult resolve(String url, String userAgent) throws Exception {
        String pid = extractQueryParam(url, "pid");
        if (pid == null) return null;

        Map<String, String> pidMap = getPidToChannelId();
        if (pidMap == null) {
            fetchCapiChannelList(userAgent);
            pidMap = getPidToChannelId();
        }
        if (pidMap == null) return null;
        String channelId = pidMap.get(pid);
        if (channelId == null) return null;

        long now = System.currentTimeMillis();
        CachedUrl cached = sUrlCache.get(pid);
        if (cached != null && now < cached.expireTime) {
            return new ParseResult(cached.m3u8Url, null, "FHD",
                    cached.httpHeaders, null);
        }

        // 主路径：JCE 协议（无需硬编码加密密钥）
        Long deadUntil = sJceDeadCache.get(pid);
        if (deadUntil == null || now > deadUntil) {
            try {
                LogUtil.d(TAG + ": JCE fetching pid=" + pid + " cid=" + channelId);
                String m3u8Url = jceTimeshiftUrl(pid, channelId);

                Map<String, String> playHeaders = new HashMap<>();
                playHeaders.put("Referer", REFERER);

                CachedUrl entry = new CachedUrl();
                entry.m3u8Url = m3u8Url;
                entry.httpHeaders = playHeaders;
                entry.expireTime = now + CACHE_TTL_MS;
                sUrlCache.put(pid, entry);

                sJceDeadCache.remove(pid);
                LogUtil.i(TAG + ": pid=" + pid + " JCE OK");
                return new ParseResult(m3u8Url, null, "FHD", playHeaders, null);

            } catch (DeadHostException e) {
                LogUtil.i(TAG + ": JCE dead host for pid=" + pid
                        + ", fallback to bkliveinfo");
                sJceDeadCache.put(pid, now + JCE_DEAD_CACHE_TTL_MS);
            } catch (Exception e) {
                LogUtil.e(TAG + ": JCE failed for pid=" + pid + ": "
                        + e.getMessage());
            }
        }

        // 回退路径：bkliveinfo (TEA cKey)
        return resolveViaBkLiveInfo(pid, channelId, now);
    }

    private ParseResult resolveViaBkLiveInfo(String pid, String channelId,
            long now) throws Exception {
        String[] defns = {"fhd", "shd"};
        String lastError = null;

        for (String defn : defns) {
            for (int attempt = 1; attempt <= API_MAX_RETRIES; attempt++) {
                LogUtil.d(TAG + ": bkliveinfo " + defn + " pid=" + pid
                        + " cid=" + channelId + " attempt=" + attempt);

                Map<String, String> params = buildParams(channelId, pid, defn);

                StringBuilder qs = new StringBuilder();
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    String key = entry.getKey();
                    if ("_uid".equals(key)) continue;
                    if (qs.length() > 0) qs.append("&");
                    qs.append(key).append("=").append(encode(entry.getValue()));
                }

                Map<String, String> reqHeaders = new HashMap<>();
                reqHeaders.put("User-Agent", "qqlive");
                reqHeaders.put("Accept", "application/json");

                HttpFetcher.Response resp = HttpFetcher.get(
                        API_URL + "?" + qs.toString(), reqHeaders);
                if (resp.code != 200) {
                    lastError = "HTTP " + resp.code;
                    LogUtil.e(TAG + ": API " + lastError + " pid=" + pid
                            + " defn=" + defn + " attempt=" + attempt);
                    if (attempt < API_MAX_RETRIES) {
                        sleepRetry(attempt);
                        continue;
                    }
                    break;
                }

                JSONObject json;
                try {
                    json = new JSONObject(resp.bodyAsString());
                } catch (Exception e) {
                    lastError = "JSON: " + e.getMessage();
                    if (attempt < API_MAX_RETRIES) {
                        sleepRetry(attempt);
                        continue;
                    }
                    break;
                }

                int iretcode = json.optInt("iretcode", -1);
                if (iretcode != 0) {
                    lastError = "iretcode=" + iretcode;
                    if (iretcode == 25 && "fhd".equals(defn)) {
                        LogUtil.i(TAG + ": fhd rejected, trying shd for pid=" + pid);
                        break;
                    }
                    LogUtil.e(TAG + ": API " + lastError + " " + json.optString("errinfo", "")
                            + " pid=" + pid + " defn=" + defn);
                    if (attempt < API_MAX_RETRIES) {
                        sleepRetry(attempt);
                        continue;
                    }
                    break;
                }

                String playUrl = json.optString("playurl", "");
                if (playUrl.isEmpty()) {
                    lastError = "empty playurl";
                    if (attempt < API_MAX_RETRIES) {
                        sleepRetry(attempt);
                        continue;
                    }
                    break;
                }

                Map<String, String> playHeaders = new HashMap<>();
                playHeaders.put("Referer", REFERER);
                JSONObject httpHeader = json.optJSONObject("http_header");
                if (httpHeader != null) {
                    String uid = httpHeader.optString("UID", "");
                    if (!uid.isEmpty()) playHeaders.put("UID", uid);
                }

                CachedUrl entry = new CachedUrl();
                entry.m3u8Url = playUrl;
                entry.httpHeaders = playHeaders;
                entry.expireTime = now + CACHE_TTL_MS;
                sUrlCache.put(pid, entry);

                String label = "fhd".equals(defn) ? "FHD" : "SHD";
                LogUtil.i(TAG + ": pid=" + pid + " " + label + " attempt=" + attempt + " OK");
                return new ParseResult(playUrl, null, label, playHeaders, null);
            }
        }
        LogUtil.e(TAG + ": all attempts failed for pid=" + pid
                + " last=" + lastError);
        return null;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    private static void sleepRetry(int attempt) {
        long delay = API_RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
        LogUtil.d(TAG + ": retry sleep " + delay + "ms before attempt " + (attempt + 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}