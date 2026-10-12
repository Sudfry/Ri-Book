package com.sudfry.todo.billing;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Inflater;

/**
 * 解开「带密码的 zip」——微信账单导出的压缩包就是这种（密码在微信支付公众号的消息里）。
 *
 * Java 自带的 ZipInputStream 遇到加密条目会直接报 "encrypted ZIP entry not supported"，
 * 所以这里自己按 ZIP 规范走一遍：
 *   本地文件头 → 用密码初始化的三重密钥流解密（ZipCrypto）→ Inflate 解压。
 * 只支持最常见的 ZipCrypto（Zip 2.0 传统加密），AES 加密的 zip 会明确报错，不做半吊子支持。
 *
 * 密码是用户自己从微信消息里看到的，不联网、不猜、不做字典爆破。
 */
public final class ZipCrypto {

    private ZipCrypto() {
    }

    /** 这份数据是不是 zip。 */
    public static boolean isZip(byte[] d) {
        return d != null && d.length > 4 && (d[0] & 0xFF) == 0x50 && (d[1] & 0xFF) == 0x4B
                && ((d[2] & 0xFF) == 0x03 || (d[2] & 0xFF) == 0x05);
    }

    /** zip 里是否存在加密条目。 */
    public static boolean isEncrypted(byte[] d) {
        if (!isZip(d)) {
            return false;
        }
        int off = 0;
        while (off + 30 <= d.length) {
            if (le32(d, off) != 0x04034b50L) {
                break;
            }
            int flags = le16(d, off + 6);
            if ((flags & 0x1) != 0) {
                return true;
            }
            int nameLen = le16(d, off + 26);
            int extraLen = le16(d, off + 28);
            long compSize = le32(d, off + 18);
            off += 30 + nameLen + extraLen;
            if (compSize == 0) {
                break;   // 用了 data descriptor，交回上层用普通 zip 读法处理
            }
            off += (int) compSize;
        }
        return false;
    }

    public static class Entry {
        public String name = "";
        public byte[] data;
    }

    /**
     * 用密码解开 zip，返回所有条目。
     *
     * @throws Exception 密码不对或是不支持的加密方式
     */
    public static List<Entry> decrypt(byte[] zip, String password) throws Exception {
        if (!isZip(zip)) {
            throw new Exception("不是 zip 文件");
        }
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        int off = 0;
        while (off + 30 <= zip.length && le32(zip, off) == 0x04034b50L) {
            int flags = le16(zip, off + 6);
            int method = le16(zip, off + 8);
            int nameLen = le16(zip, off + 26);
            int extraLen = le16(zip, off + 28);
            long compSize = le32(zip, off + 18);
            long crc = le32(zip, off + 14);
            if ((flags & 0x8) != 0 || compSize == 0) {
                throw new Exception("这个 zip 的分段方式不支持（缺少长度信息）");
            }
            if ((flags & 0x40) != 0) {   // strong encryption
                throw new Exception("这个 zip 用的是强加密，请先用电脑解压成 csv/xlsx 再导入");
            }
            String name = new String(zip, off + 30, nameLen, StandardCharsets.UTF_8);
            int dataOff = off + 30 + nameLen + extraLen;
            int comp = (int) compSize;
            if (dataOff + comp > zip.length) {
                throw new Exception("zip 数据被截断了");
            }
            byte[] raw = new byte[comp];
            System.arraycopy(zip, dataOff, raw, 0, comp);

            boolean enc = (flags & 0x1) != 0;
            if (enc) {
                raw = unzipCrypto(raw, pw);
            }
            byte[] plain;
            if (method == 0) {
                plain = raw;
            } else if (method == 8) {
                plain = inflate(raw);
            } else {
                throw new Exception("不支持的压缩方式：" + method);
            }
            if (enc && crc != 0) {
                CRC32 c = new CRC32();
                c.update(plain);
                if (c.getValue() != crc) {
                    throw new Exception("密码不对（校验没过），请确认微信支付公众号里给的那串密码");
                }
            }
            Entry e = new Entry();
            e.name = name;
            e.data = plain;
            out.add(e);
            off = dataOff + comp;
        }
        if (out.isEmpty()) {
            throw new Exception("zip 里没有可读文件");
        }
        return out;
    }

    // ───────────────── ZipCrypto ─────────────────

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = ((c & 1) != 0) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            CRC_TABLE[i] = c;
        }
    }

    private static int crc32(int crc, int b) {
        return CRC_TABLE[(crc ^ b) & 0xFF] ^ (crc >>> 8);
    }

    /** 传统加密：12 字节头 + 逐个字节与密钥流异或。 */
    private static byte[] unzipCrypto(byte[] data, byte[] pw) throws Exception {
        int k0 = 0x12345678, k1 = 0x23456789, k2 = 0x34567890;
        for (byte b : pw) {
            k0 = crc32(k0, b);
            k1 = (k1 + (k0 & 0xFF)) * 134775813 + 1;
            k2 = crc32(k2, (byte) (k1 >>> 24));
        }
        int n = data.length;
        if (n <= 12) {
            throw new Exception("加密数据不完整");
        }
        byte[] out = new byte[n - 12];
        for (int i = 0; i < n; i++) {
            int temp = k2 | 2;
            int b = data[i] ^ ((temp * (temp ^ 1)) >>> 8);
            if (i >= 12) {
                out[i - 12] = (byte) b;
            }
            k0 = crc32(k0, b);
            k1 = (k1 + (k0 & 0xFF)) * 134775813 + 1;
            k2 = crc32(k2, (byte) (k1 >>> 24));
        }
        return out;
    }

    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inf = new Inflater(true);
        inf.setInput(data);
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(1024, data.length * 3));
        byte[] buf = new byte[16384];
        try {
            while (!inf.finished()) {
                int k = inf.inflate(buf);
                if (k == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) {
                        break;
                    }
                }
                bo.write(buf, 0, k);
            }
        } finally {
            inf.end();
        }
        return bo.toByteArray();
    }

    // ───────────────── 小工具 ─────────────────

    private static int le16(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static long le32(byte[] d, int o) {
        return (d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8)
                | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24);
    }
}
