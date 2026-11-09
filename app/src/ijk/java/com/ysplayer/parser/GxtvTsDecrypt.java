package com.ysplayer.parser;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 广西网络台 TS 分片 PES 载荷解密（移植自 iptv-main/extractors/gxtv/decrypt.js）
 *
 * 加密方式：非标准 HLS，无 EXT-X-KEY。TS/PAT/PMT/PES 头保持明文，
 * 仅对 PES elementary-stream payload 做 AES-128-ECB 加密并交换等长块。
 * 普通播放器能识别 H.264/AAC 编码，但解码时报告大量损坏帧（花屏/无声）。
 */
public final class GxtvTsDecrypt {

    private static final int TS_PACKET_SIZE = 188;
    private static final String KEY_SALT = "01234568";

    private final byte[] mAesKey;
    private final String mCustomId;
    private final String mContentId;

    /** 从 API 返回的 encodingId 和 encodingKey 构造 */
    public GxtvTsDecrypt(String customId, String contentId) {
        this.mCustomId = customId != null ? customId : "";
        this.mContentId = contentId != null ? contentId : "";
        this.mAesKey = md5(KEY_SALT + this.mCustomId);
    }

    // ====== 公开入口 ======

    /**
     * 原地解密整个 MPEG-TS 分片，返回处理的 PES 数量。
     * 按 PID 拼回完整 PES 再解密：AES 区间和块交换以完整 PES payload 为基准。
     */
    public int decrypt(byte[] buffer) {
        if (buffer == null || buffer.length < TS_PACKET_SIZE
                || mCustomId.isEmpty() || mContentId.isEmpty()) {
            return 0;
        }
        Map<Integer, PesBuilder> active = new HashMap<>();
        int decrypted = 0;

        for (int packetAt = 0; packetAt + TS_PACKET_SIZE <= buffer.length; packetAt += TS_PACKET_SIZE) {
            if (buffer[packetAt] != 0x47) continue;

            int packetByte1 = buffer[packetAt + 1] & 0xFF;
            boolean payloadUnitStart = (packetByte1 & 0x40) != 0;
            int pid = ((packetByte1 & 0x1F) << 8) | (buffer[packetAt + 2] & 0xFF);
            int adaptationControl = (buffer[packetAt + 3] >>> 4) & 0x03;

            // adaptationControl == 0 (保留) 或 == 2 (只有适应字段，无负载) 跳过
            if (adaptationControl == 0 || adaptationControl == 2) continue;

            int payloadAt = packetAt + 4;
            if (adaptationControl == 3) {
                int afLen = buffer[payloadAt] & 0xFF;
                payloadAt += 1 + afLen;
            }
            int packetEnd = packetAt + TS_PACKET_SIZE;
            if (payloadAt >= packetEnd) continue;

            if (payloadUnitStart) {
                // 先清理旧 builder
                PesBuilder prev = active.get(pid);
                if (prev != null && prev.slices.size() > 0) {
                    if (flushPes(buffer, prev)) decrypted++;
                }
                active.remove(pid);

                // 检查 PES 包头：00 00 01
                if (payloadAt + 3 <= packetEnd
                        && buffer[payloadAt] == 0
                        && buffer[payloadAt + 1] == 0
                        && buffer[payloadAt + 2] == 1) {
                    PesBuilder builder = new PesBuilder();
                    builder.slices.add(new Slice(payloadAt, packetEnd));
                    active.put(pid, builder);
                }
            } else {
                PesBuilder builder = active.get(pid);
                if (builder != null) {
                    builder.slices.add(new Slice(payloadAt, packetEnd));
                }
            }
        }

        // 清理剩余
        for (PesBuilder builder : active.values()) {
            if (builder.slices.size() > 0) {
                if (flushPes(buffer, builder)) decrypted++;
            }
        }
        return decrypted;
    }

    // ====== 内部实现 ======

    private boolean flushPes(byte[] buffer, PesBuilder builder) {
        // 拼接完整 PES
        int totalLen = 0;
        for (Slice s : builder.slices) {
            totalLen += (s.end - s.start);
        }
        byte[] pes = new byte[totalLen];
        int offset = 0;
        for (Slice s : builder.slices) {
            int len = s.end - s.start;
            System.arraycopy(buffer, s.start, pes, offset, len);
            offset += len;
        }

        // 检查 PES 加密标记
        int[] marker = encryptionMarker(pes);
        if (marker == null || marker[0] != 2) return false;

        int pesHeaderDataLen = pes[8] & 0xFF;
        int payloadAt = 9 + pesHeaderDataLen;
        if (payloadAt >= pes.length) return false;

        // 解密 PES 载荷（先解密临时数组，再写回 pes）
        byte[] pesPayload = new byte[pes.length - payloadAt];
        System.arraycopy(pes, payloadAt, pesPayload, 0, pesPayload.length);
        if (!decryptPayloadV2(pesPayload)) return false;
        System.arraycopy(pesPayload, 0, pes, payloadAt, pesPayload.length);

        // 将整个 PES（含 header + 解密后载荷）写回 buffer 各 slice 位置
        int pesByteOffset = 0;
        for (Slice s : builder.slices) {
            int sliceLen = s.end - s.start;
            System.arraycopy(pes, pesByteOffset, buffer, s.start, sliceLen);
            pesByteOffset += sliceLen;
        }
        return true;
    }

    /**
     * 检查 PES 加密标记字节。
     * 返回 null 表示不加密，非 null 返回 [algorithm, audioFlag, videoFlag]
     */
    private static int[] encryptionMarker(byte[] pes) {
        if (pes.length < 25 || pes[0] != 0 || pes[1] != 0 || pes[2] != 1) return null;
        int flags = pes[7] & 0xFF;
        if ((flags & 0x01) == 0) return null;

        int ptsDtsFlags = flags >>> 6;
        int extensionOffset;
        if (ptsDtsFlags == 2) extensionOffset = 5;
        else if (ptsDtsFlags == 3) extensionOffset = 10;
        else if ((flags & 0x20) != 0) extensionOffset = 6;
        else if ((flags & 0x10) != 0) extensionOffset = 3;
        else if ((flags & 0x08) != 0) extensionOffset = 1;
        else if ((flags & 0x04) != 0) extensionOffset = 1;
        else if ((flags & 0x02) != 0) extensionOffset = 2;
        else extensionOffset = 0;

        int extensionFlagsAt = 9 + extensionOffset;
        int markerAt = 19 + extensionOffset;
        if (markerAt >= pes.length || (pes[extensionFlagsAt] & 0x80) == 0) return null;

        int marker = pes[markerAt] & 0xFF;
        return new int[] { marker & 0x3F, (marker & 0x80) != 0 ? 1 : 0, (marker & 0x40) != 0 ? 1 : 0 };
    }

    /**
     * 第二版负载解密：AES-128-ECB + 块交换。
     * 对标 decrypt.js 的 decryptPayloadV2()。
     */
    private boolean decryptPayloadV2(byte[] payload) {
        if (payload.length == 0) return false;

        int charSumCustomId = charCodeSum(mCustomId);
        int charSumContentId = charCodeSum(mContentId);
        int blocks = (charSumCustomId + charSumContentId) % 32;
        if (blocks < 4) blocks = 4;

        int blockLength = payload.length / blocks;
        if (blockLength <= 0) return false;

        int encryptedOffset = blockLength / 2;
        int encryptedLength = ((payload.length - encryptedOffset - 1) / 16) * 16;

        // AES-128-ECB 解密
        if (encryptedLength > 0) {
            try {
                Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(mAesKey, "AES"));
                byte[] encrypted = new byte[encryptedLength];
                System.arraycopy(payload, encryptedOffset, encrypted, 0, encryptedLength);
                byte[] plain = cipher.doFinal(encrypted);
                System.arraycopy(plain, 0, payload, encryptedOffset, plain.length);
            } catch (Exception e) {
                return false;
            }
        }

        // 块交换：把末块塞到第二块位置
        if (blockLength > 0 && blocks > 1) {
            byte[] lastBlock = new byte[blockLength];
            System.arraycopy(payload, (blocks - 1) * blockLength, lastBlock, 0, blockLength);
            int middleLen = (blocks - 2) * blockLength;
            if (middleLen > 0) {
                System.arraycopy(payload, blockLength, payload, 2 * blockLength, middleLen);
            }
            // 如果只剩 2 个块，middleLen=0，只移动末块
            System.arraycopy(lastBlock, 0, payload, blockLength, blockLength);
        }

        return true;
    }

    private static int charCodeSum(String text) {
        int total = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                total += text.charAt(i);
            }
        }
        return total;
    }

    private static byte[] md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return md.digest(input.getBytes("UTF-8"));
        } catch (Exception e) {
            return new byte[16];
        }
    }

    // ====== 辅助内部类 ======

    private static class Slice {
        final int start;
        final int end;
        Slice(int start, int end) { this.start = start; this.end = end; }
    }

    private static class PesBuilder {
        final List<Slice> slices = new ArrayList<>();
    }
}