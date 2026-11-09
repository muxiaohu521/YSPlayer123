package com.ysplayer.parser;

import com.ysplayer.LogUtil;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class ArcvideoDecryptor {

    private static final String TAG = "ArcvideoDecryptor";
    private static final byte[] MAGIC = "ARCVIDEO-PROTECTED".getBytes();
    private static final byte[] ZERO_IV = new byte[16];
    private static final int HEADER_BYTES = 28;

    private static final String PLAYER_JS_URL =
            "http://video10.qtv.com.cn/drm/player.min.js";
    private static final Pattern OTT_PATTERN =
            Pattern.compile("ott\\s*=\\s*\"([0-9a-fA-F]+)\"");
    private static final Pattern WORDS_PATTERN =
            Pattern.compile("words\\s*=\\s*\"([0-9a-fA-F]+)\"");

    private static volatile byte[] sMasterKey;
    private static volatile boolean sMasterKeyFetched;
    private static final Object sMasterKeyLock = new Object();

    private static final byte[] FALLBACK_MASTER_KEY =
            "qdxmtottarcvidet".getBytes();

    private ArcvideoDecryptor() {
    }

    private static byte[] getMasterKey() {
        if (sMasterKey != null) return sMasterKey;
        if (sMasterKeyFetched) return FALLBACK_MASTER_KEY;
        synchronized (sMasterKeyLock) {
            if (sMasterKey != null) return sMasterKey;
            if (sMasterKeyFetched) return FALLBACK_MASTER_KEY;
            sMasterKey = fetchMasterKey();
            sMasterKeyFetched = true;
            return sMasterKey != null ? sMasterKey : FALLBACK_MASTER_KEY;
        }
    }

    private static byte[] fetchMasterKey() {
        try {
            LogUtil.i(TAG + ": 从官网player.min.js动态获取MASTER_KEY...");
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Referer", "https://www.qtv.com.cn/");
            headers.put("Accept", "*/*");

            HttpFetcher.Response resp = HttpFetcher.get(PLAYER_JS_URL, headers);
            if (resp.code != 200) {
                LogUtil.w(TAG + ": player.min.js HTTP " + resp.code
                        + ", 使用硬编码后备密钥");
                return null;
            }

            String js = resp.bodyAsString();
            String ott = extractHex(OTT_PATTERN, js);
            String words = extractHex(WORDS_PATTERN, js);

            if (ott == null || words == null) {
                LogUtil.w(TAG + ": 无法从player.min.js提取ott/words, 使用硬编码后备密钥");
                return null;
            }

            byte[] ottBytes = hexToBytes(ott);
            byte[] wordsBytes = hexToBytes(words);
            if (ottBytes.length + wordsBytes.length != 16) {
                LogUtil.w(TAG + ": ott+words长度不是16字节("
                        + ((ottBytes.length + wordsBytes.length)) + "), 使用硬编码后备密钥");
                return null;
            }

            byte[] key = new byte[16];
            System.arraycopy(ottBytes, 0, key, 0, ottBytes.length);
            System.arraycopy(wordsBytes, 0, key, ottBytes.length, wordsBytes.length);

            String keyHex = bytesToHex(key);
            LogUtil.i(TAG + ": 动态MASTER_KEY获取成功: " + keyHex);
            return key;
        } catch (Exception e) {
            LogUtil.w(TAG + ": 动态获取MASTER_KEY异常: " + e.getMessage()
                    + ", 使用硬编码后备密钥");
            return null;
        }
    }

    private static String extractHex(Pattern pattern, String js) {
        Matcher m = pattern.matcher(js);
        if (m.find()) {
            String hex = m.group(1);
            if (hex.length() % 2 == 0 && hex.matches("^[0-9a-fA-F]+$")) {
                return hex;
            }
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static boolean isProtected(byte[] data) {
        if (data == null || data.length < HEADER_BYTES + 16) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) return false;
        }
        return true;
    }

    public static byte[] decrypt(byte[] data) {
        if (!isProtected(data)) return data;
        try {
            int packetSize = data[19] & 0xFF;
            int plainLength = ((data[20] & 0xFF) << 24)
                    | ((data[21] & 0xFF) << 16)
                    | ((data[22] & 0xFF) << 8)
                    | (data[23] & 0xFF);
            if (packetSize == 0 || plainLength == 0 || plainLength % packetSize != 0) return data;

            int keyOffset = ((data[32] & 0xFF) + 1) * packetSize;
            int cipherLength = ((plainLength + 15) / 16) * 16;
            int keyAt = Math.min(keyOffset, plainLength) + HEADER_BYTES;

            if (data.length < keyAt + 16) return data;

            byte[] segmentKey = aesCbcDecrypt(getMasterKey(),
                    subarray(data, keyAt, 16));

            byte[] cipher;
            if (keyOffset < cipherLength) {
                int head = HEADER_BYTES + keyOffset;
                int tail = head + 16;
                if (data.length < tail + (cipherLength - keyOffset)) return data;
                byte[] prefix = subarray(data, HEADER_BYTES, keyOffset);
                byte[] suffix = subarray(data, tail, cipherLength - keyOffset);
                cipher = concat(prefix, suffix);
            } else {
                cipher = subarray(data, HEADER_BYTES, cipherLength);
                if (cipher.length % 16 != 0) return data;
            }

            byte[] plain = aesCbcDecrypt(segmentKey, cipher);
            if (plain.length > plainLength) {
                byte[] trimmed = new byte[plainLength];
                System.arraycopy(plain, 0, trimmed, 0, plainLength);
                return trimmed;
            }
            return plain;
        } catch (Exception e) {
            LogUtil.e(TAG + ": decrypt failed: " + e.getMessage());
            return data;
        }
    }

    private static byte[] aesCbcDecrypt(byte[] key, byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(ZERO_IV);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES decrypt failed", e);
        }
    }

    private static byte[] subarray(byte[] src, int offset, int length) {
        byte[] dst = new byte[length];
        System.arraycopy(src, offset, dst, 0, length);
        return dst;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}