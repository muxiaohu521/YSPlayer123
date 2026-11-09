package com.ysplayer.parser;

/**
 * XXTEA 加密算法（Corrected Block TEA）。
 *
 * 用于内蒙古广播电视台「奔腾融媒」接口数据加解密。
 * 密钥固定使用 16 字节 ASCII 文本 "5b28bae827e651b3"。
 *
 * 算法规范：
 * - 分组大小：32 位无符号整数（4 字节）
 * - Delta 常数：0x9E3779B9
 * - 轮数：6 + 52 / n（n 为分组数）
 * - 加密时包含原始明文字节长度作为消息长度
 */
final class Xxtea {

    private Xxtea() {}

    /**
     * XXTEA 加密。
     * @param data 明文数据
     * @param key  16 字节密钥
     * @return 密文数据（长度可能比明文略长）
     */
    static byte[] encrypt(byte[] data, byte[] key) {
        if (data == null || data.length == 0) return data;
        int[] words = toWords(data, true);
        int[] keyWords = toKeyWords(key);
        int n = words.length;
        int rounds = 6 + 52 / n;
        int sum = 0;
        int delta = 0x9E3779B9;

        int z = words[n - 1];
        for (int q = 0; q < rounds; q++) {
            sum += delta;
            int e = (sum >>> 2) & 3;
            for (int p = 0; p < n - 1; p++) {
                int y = words[p + 1];
                int mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4)))
                        ^ ((sum ^ y) + (keyWords[(p & 3) ^ e] ^ z));
                z = words[p] = words[p] + mx;
            }
            int y = words[0];
            int mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4)))
                    ^ ((sum ^ y) + (keyWords[((n - 1) & 3) ^ e] ^ z));
            z = words[n - 1] = words[n - 1] + mx;
        }
        return toBytes(words, false);
    }

    /**
     * XXTEA 解密。
     * @param data 密文数据
     * @param key  16 字节密钥
     * @return 明文数据
     */
    static byte[] decrypt(byte[] data, byte[] key) {
        if (data == null || data.length == 0) return data;
        int[] words = toWords(data, false);
        int[] keyWords = toKeyWords(key);
        int n = words.length;
        int rounds = 6 + 52 / n;
        int delta = 0x9E3779B9;
        int sum = rounds * delta;

        int y = words[0];
        for (int q = 0; q < rounds; q++) {
            int e = (sum >>> 2) & 3;
            for (int p = n - 1; p > 0; p--) {
                int z = words[p - 1];
                int mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4)))
                        ^ ((sum ^ y) + (keyWords[(p & 3) ^ e] ^ z));
                y = words[p] = words[p] - mx;
            }
            int z = words[n - 1];
            int mx = (((z >>> 5) ^ (y << 2)) + ((y >>> 3) ^ (z << 4)))
                    ^ ((sum ^ y) + (keyWords[e] ^ z));
            y = words[0] = words[0] - mx;
            sum -= delta;
        }
        return toBytes(words, true);
    }

    private static int[] toWords(byte[] bytes, boolean includeLength) {
        int size = (bytes.length + 3) / 4;
        int[] words = new int[includeLength ? size + 1 : size];
        for (int i = 0; i < bytes.length; i++) {
            words[i >>> 2] |= (bytes[i] & 0xFF) << ((i & 3) << 3);
        }
        if (includeLength) {
            words[size] = bytes.length;
        }
        return words;
    }

    private static byte[] toBytes(int[] words, boolean includeLength) {
        int size = words.length * 4;
        if (includeLength) {
            int expected = words[words.length - 1];
            size -= 4;
            if (expected < size - 3 || expected > size) {
                throw new IllegalStateException("XXTEA decryption failed: invalid message length");
            }
            size = expected;
        }
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (words[i >>> 2] >>> ((i & 3) << 3));
        }
        return bytes;
    }

    private static int[] toKeyWords(byte[] key) {
        int[] words = new int[4];
        for (int i = 0; i < key.length && i < 16; i++) {
            words[i >>> 2] |= (key[i] & 0xFF) << ((i & 3) << 3);
        }
        return words;
    }
}