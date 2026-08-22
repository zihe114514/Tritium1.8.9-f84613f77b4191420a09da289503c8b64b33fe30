package com.muoniumplayer.core.ncm.music;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;

/**
 * QQ 音乐 QRC 逐字歌词的解密。
 *
 * <p>接口(music.musichallSong.PlayLyricInfo)返回的 lyric/trans/roma 三个字段都是十六进制字符串,
 * 内容是"QQ 变体 DES"三重加密后的 zlib 流:按 8 字节分组做 D(k3) -> E(k2) -> D(k1),拼接后 inflate
 * 才是 XML 文本。请求参数里的 {@code crypt=0} 是无效的,服务端始终返回密文。</p>
 *
 * <p><b>为什么不能直接用 JCE 的 DESede。</b>这条链路只有三个地方和 FIPS 46-3 不一样,但每一处都会让
 * 标准实现输出垃圾:</p>
 * <ol>
 *   <li>数据块和每个 8 字节子密钥在进入位运算前,<b>每 4 字节半区内部字节序翻转</b>,出来时再翻回去;</li>
 *   <li>PC2 的后 24 位(D 半区)用 {@code (PC2[j]-1) - 27} 取位而不是标准的 {@code - 28},
 *       因此第 28 个索引落在 28..31 的空位上,恒取 0;</li>
 *   <li>两个 S 盒条目与标准不同:{@code S2[23] = 15}(标准 14)、{@code S4[53] = 10}(标准 1)。</li>
 * </ol>
 *
 * <p>表格全部来自公开的 FIPS 46-3 规范,上面三条只是为了和服务端互通而保留的偏差。解密失败时返回
 * {@code null},调用方回退到普通歌词——歌词永远不该让播放失败。</p>
 */
final class QrcCipher {

    /** 客户端硬编码的 24 字节密钥,分成三段 8 字节子密钥。 */
    private static final byte[] KEY = "!@#)(*$%123ZXC!@!@#)(NHL".getBytes(StandardCharsets.US_ASCII);

    /** PC2 在 D 半区的索引偏移。标准 DES 是 28,QQ 的实现少减 1。 */
    private static final int D_HALF_SHIFT = 27;

    private static final int MAX_INFLATED_BYTES = 4 * 1024 * 1024;

    private static final int[] IP = {
            58, 50, 42, 34, 26, 18, 10, 2,
            60, 52, 44, 36, 28, 20, 12, 4,
            62, 54, 46, 38, 30, 22, 14, 6,
            64, 56, 48, 40, 32, 24, 16, 8,
            57, 49, 41, 33, 25, 17, 9, 1,
            59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5,
            63, 55, 47, 39, 31, 23, 15, 7
    };

    private static final int[] FP = {
            40, 8, 48, 16, 56, 24, 64, 32,
            39, 7, 47, 15, 55, 23, 63, 31,
            38, 6, 46, 14, 54, 22, 62, 30,
            37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28,
            35, 3, 43, 11, 51, 19, 59, 27,
            34, 2, 42, 10, 50, 18, 58, 26,
            33, 1, 41, 9, 49, 17, 57, 25
    };

    private static final int[] EXPANSION = {
            32, 1, 2, 3, 4, 5, 4, 5,
            6, 7, 8, 9, 8, 9, 10, 11,
            12, 13, 12, 13, 14, 15, 16, 17,
            16, 17, 18, 19, 20, 21, 20, 21,
            22, 23, 24, 25, 24, 25, 26, 27,
            28, 29, 28, 29, 30, 31, 32, 1
    };

    private static final int[] PERMUTATION = {
            16, 7, 20, 21, 29, 12, 28, 17,
            1, 15, 23, 26, 5, 18, 31, 10,
            2, 8, 24, 14, 32, 27, 3, 9,
            19, 13, 30, 6, 22, 11, 4, 25
    };

    private static final int[] PC1 = {
            57, 49, 41, 33, 25, 17, 9,
            1, 58, 50, 42, 34, 26, 18,
            10, 2, 59, 51, 43, 35, 27,
            19, 11, 3, 60, 52, 44, 36,
            63, 55, 47, 39, 31, 23, 15,
            7, 62, 54, 46, 38, 30, 22,
            14, 6, 61, 53, 45, 37, 29,
            21, 13, 5, 28, 20, 12, 4
    };

    private static final int[] PC2 = {
            14, 17, 11, 24, 1, 5,
            3, 28, 15, 6, 21, 10,
            23, 19, 12, 4, 26, 8,
            16, 7, 27, 20, 13, 2,
            41, 52, 31, 37, 47, 55,
            30, 40, 51, 45, 33, 48,
            44, 49, 39, 56, 34, 53,
            46, 42, 50, 36, 29, 32
    };

    private static final int[] SHIFTS = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};

    private static final int[][] SBOX = {
            {
                    14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                    0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                    4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                    15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
            },
            {
                    15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                    3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
                    0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                    13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
            },
            {
                    10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                    13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                    13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                    1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
            },
            {
                    7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                    13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                    10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                    3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
            },
            {
                    2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                    14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                    4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                    11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
            },
            {
                    12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                    10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                    9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                    4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
            },
            {
                    4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                    13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                    1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                    6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
            },
            {
                    13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                    1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                    7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                    2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
            }
    };

    private QrcCipher() {
    }

    /**
     * 解密一段十六进制密文并 inflate 成文本。输入为空、长度不足一个分组、inflate 失败时返回
     * {@code null}。
     */
    static String decryptHex(String hex) {
        byte[] encrypted = hexToBytes(hex);
        if (encrypted == null || encrypted.length < 8) return null;

        byte[][] firstPass = keySchedule(subKey(16), true);
        byte[][] secondPass = keySchedule(subKey(8), false);
        byte[][] thirdPass = keySchedule(subKey(0), true);

        int usable = encrypted.length - (encrypted.length % 8);
        byte[] plain = new byte[usable];
        byte[] block = new byte[8];
        for (int offset = 0; offset + 8 <= usable; offset += 8) {
            System.arraycopy(encrypted, offset, block, 0, 8);
            byte[] decrypted = desBlock(block, firstPass);
            decrypted = desBlock(decrypted, secondPass);
            decrypted = desBlock(decrypted, thirdPass);
            System.arraycopy(decrypted, 0, plain, offset, 8);
        }

        byte[] inflated = inflate(plain);
        if (inflated == null || inflated.length == 0) return null;
        return new String(inflated, StandardCharsets.UTF_8);
    }

    // ── zlib ───────────────────────────────────────────────────────────────

    /**
     * 密文长度是 8 的整数倍,尾部通常带有填充,所以 inflate 到流结束即可,尾部的多余字节直接丢掉。
     * 中途报错时已经解出的部分仍然返回:QRC 的正文在前,截断的结尾至多少几行歌词。
     */
    private static byte[] inflate(byte[] data) {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(1024, data.length * 3));
        try {
            inflater.setInput(data);
            byte[] buffer = new byte[8192];
            while (!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary()) {
                int read = inflater.inflate(buffer);
                if (read <= 0) break;
                output.write(buffer, 0, read);
                if (output.size() > MAX_INFLATED_BYTES) break;
            }
        } catch (Throwable ignored) {
            // 部分结果比没有结果好,下面统一按"有没有解出内容"处理。
        } finally {
            inflater.end();
        }
        return output.size() == 0 ? null : output.toByteArray();
    }

    // ── QQ 变体 DES ─────────────────────────────────────────────────────────

    private static byte[] subKey(int offset) {
        byte[] key = new byte[8];
        System.arraycopy(KEY, offset, key, 0, 8);
        return key;
    }

    private static byte[][] keySchedule(byte[] key, boolean decrypt) {
        byte[] keyBits = toBits(swapHalves(key));
        byte[] permuted = permute(keyBits, PC1);
        byte[] c = new byte[32];
        byte[] d = new byte[32];
        for (int i = 0; i < 28; i++) {
            c[i] = permuted[i];
            d[i] = permuted[28 + i];
        }

        byte[][] schedule = new byte[16][];
        for (int round = 0; round < 16; round++) {
            c = rotateLeft28(c, SHIFTS[round]);
            d = rotateLeft28(d, SHIFTS[round]);
            byte[] subKey = new byte[48];
            for (int j = 0; j < 24; j++) {
                subKey[j] = c[PC2[j] - 1];
            }
            for (int j = 24; j < 48; j++) {
                int index = (PC2[j] - 1) - D_HALF_SHIFT;
                subKey[j] = index < 32 ? d[index] : 0;
            }
            schedule[decrypt ? 15 - round : round] = subKey;
        }
        return schedule;
    }

    private static byte[] rotateLeft28(byte[] half, int shift) {
        byte[] rotated = new byte[32];
        for (int i = 0; i < 28; i++) {
            rotated[i] = half[(i + shift) % 28];
        }
        return rotated;
    }

    private static byte[] desBlock(byte[] block, byte[][] schedule) {
        byte[] bits = permute(toBits(swapHalves(block)), IP);
        byte[] left = new byte[32];
        byte[] right = new byte[32];
        System.arraycopy(bits, 0, left, 0, 32);
        System.arraycopy(bits, 32, right, 0, 32);

        for (int round = 0; round < 16; round++) {
            byte[] mixed = feistel(right, schedule[round]);
            for (int i = 0; i < 32; i++) {
                mixed[i] ^= left[i];
            }
            left = right;
            right = mixed;
        }

        byte[] preOutput = new byte[64];
        System.arraycopy(right, 0, preOutput, 0, 32);
        System.arraycopy(left, 0, preOutput, 32, 32);
        return swapHalves(toBytes(permute(preOutput, FP)));
    }

    private static byte[] feistel(byte[] rightBits, byte[] subKey) {
        byte[] expanded = permute(rightBits, EXPANSION);
        for (int i = 0; i < 48; i++) {
            expanded[i] ^= subKey[i];
        }
        byte[] substituted = new byte[32];
        for (int box = 0; box < 8; box++) {
            int six = 0;
            for (int i = 0; i < 6; i++) {
                six = (six << 1) | expanded[box * 6 + i];
            }
            int row = ((six >> 5) & 1) * 2 + (six & 1);
            int column = (six >> 1) & 0xF;
            int value = SBOX[box][row * 16 + column];
            for (int i = 0; i < 4; i++) {
                substituted[box * 4 + i] = (byte) ((value >> (3 - i)) & 1);
            }
        }
        return permute(substituted, PERMUTATION);
    }

    /** QQ 的偏差之一:每 4 字节半区内部字节序翻转。 */
    private static byte[] swapHalves(byte[] input) {
        byte[] swapped = new byte[input.length];
        for (int half = 0; half + 4 <= input.length; half += 4) {
            for (int i = 0; i < 4; i++) {
                swapped[half + i] = input[half + 3 - i];
            }
        }
        return swapped;
    }

    private static byte[] permute(byte[] bits, int[] table) {
        byte[] output = new byte[table.length];
        for (int i = 0; i < table.length; i++) {
            output[i] = bits[table[i] - 1];
        }
        return output;
    }

    private static byte[] toBits(byte[] bytes) {
        byte[] bits = new byte[bytes.length * 8];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) {
                bits[i * 8 + bit] = (byte) ((value >> (7 - bit)) & 1);
            }
        }
        return bits;
    }

    private static byte[] toBytes(byte[] bits) {
        byte[] bytes = new byte[bits.length / 8];
        for (int i = 0; i < bytes.length; i++) {
            int value = 0;
            for (int bit = 0; bit < 8; bit++) {
                value = (value << 1) | bits[i * 8 + bit];
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String trimmed = hex.trim();
        if (trimmed.length() < 16 || (trimmed.length() % 2) != 0) return null;
        byte[] bytes = new byte[trimmed.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(trimmed.charAt(i * 2), 16);
            int low = Character.digit(trimmed.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
