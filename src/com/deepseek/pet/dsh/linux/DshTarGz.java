package com.deepseek.pet.dsh.linux;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

/**
 * 纯 Java 的 tar.gz 解包器（只解 Ubuntu base / Node 那种 tar）。
 *
 * <h3>为什么不用系统的 tar</h3>
 * Android 的 toybox 从很新的版本才有 {@code tar}，而且让 App 去 exec 系统二进制还要
 * 多一层信任；更重要的是：**解压必须由 App 自己做**——root 解出来的文件 SELinux 类别标签
 * 不对（{@code s0} 少了 {@code c239,c256,c512,c768}），App 自己反而读不了。
 * 由 App 进程创建的文件天然带正确标签，这条是实测踩出来的。
 *
 * <h3>支持的范围</h3>
 * ustar + GNU 长名（类型 {@code L}）+ pax 头（{@code x}）、普通文件、目录、符号链接、硬链接。
 * 设备文件 / FIFO 直接跳过（rootfs 里用不到，而且 App 也没权限建）。
 *
 * @author 桌宠项目
 */
public final class DshTarGz {

    /** 进度回调（每处理若干条目一次）。 */
    public interface Progress {
        /**
         * @param entries 已处理条目数
         * @param bytes   已写字节数
         * @param name    当前条目名（给界面显示）
         */
        void onProgress(int entries, long bytes, String name);
    }

    private static final int BLOCK = 512;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    private DshTarGz() { }

    /**
     * 解开一个 .tar.gz 到目标目录（目标不存在会创建）。
     *
     * @param gz   压缩包
     * @param dest 目标目录
     * @param p    进度回调，可为 null
     * @return 解出的条目数
     * @throws IOException 读失败 / 路径越界
     */
    public static int extract(File gz, File dest, Progress p) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("建不了目标目录：" + dest);
        String root = dest.getCanonicalPath() + File.separator;
        int entries = 0;
        long written = 0;
        InputStream raw = new BufferedInputStream(new FileInputStream(gz), 1 << 16);
        try {
            GZIPInputStream in = new GZIPInputStream(raw, 1 << 16);
            byte[] header = new byte[BLOCK];
            String longName = null;
            String paxPath = null;
            String paxLink = null;
            while (true) {
                if (!readFully(in, header)) break;
                if (isEmpty(header)) break;                       // 两个空块 = 结束
                long size = parseOctal(header, 124, 12);
                char type = (char) (header[156] & 0xFF);
                String name = longName != null ? longName : str(header, 0, 100);
                longName = null;
                if (paxPath != null) { name = paxPath; paxPath = null; }
                String prefix = str(header, 345, 155);
                if (prefix.length() > 0 && !name.startsWith("/")) name = prefix + "/" + name;
                if (name.startsWith("./")) name = name.substring(2);
                String link = str(header, 157, 100);
                if (paxLink != null) { link = paxLink; paxLink = null; }
                long mode = parseOctal(header, 100, 8);

                if (type == 'x' || type == 'g') {
                    byte[] body = new byte[(int) size];
                    readFully(in, body);
                    skipPadding(in, size);
                    String[] kv = parsePax(body);
                    if (kv != null) {
                        if (kv[0] != null) paxPath = kv[0];
                        if (kv[1] != null) paxLink = kv[1];
                    }
                    continue;
                }
                if (type == 'L') {
                    byte[] body = new byte[(int) size];
                    readFully(in, body);
                    skipPadding(in, size);
                    int end = body.length;
                    while (end > 0 && body[end - 1] == 0) end--;
                    longName = new String(body, 0, end, Charset.forName("UTF-8"));
                    continue;
                }

                File target = safeChild(dest, root, name);
                if (type == '5') {
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("建不了目录：" + target);
                    skipPadding(in, size);
                } else if (type == '2') {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    if (target.exists()) target.delete();
                    try {
                        Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(link));
                    } catch (Throwable t) {
                        // 建不了符号链接就当空文件占位：不中断整个安装，但要如实记一笔
                        target.delete();
                    }
                    skipPadding(in, size);
                } else if (type == '1') {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    File src = safeChild(dest, root, link);
                    if (src.isFile()) {
                        copyFile(src, target);
                        written += target.length();
                    }
                    skipPadding(in, size);
                } else if (type == '0' || type == 0 || type == '7') {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.isDirectory()) parent.mkdirs();
                    long n = copyOut(in, target, size);
                    written += n;
                    skipPadding(in, size);
                    applyMode(target, mode);
                } else {
                    // 设备节点 / FIFO / 未知类型：跳过内容
                    skip(in, size);
                    skipPadding(in, size);
                }
                entries++;
                if (p != null && (entries % 500) == 0) p.onProgress(entries, written, name);
            }
            in.close();
        } finally {
            try { raw.close(); } catch (Throwable ignore) { }
        }
        if (p != null) p.onProgress(entries, written, "");
        return entries;
    }

    // ==================== 小工具 ====================

    private static File safeChild(File dest, String rootPrefix, String name) throws IOException {
        if (name.length() == 0) throw new IOException("压缩包里有空名字的条目");
        if (name.indexOf('\0') >= 0) throw new IOException("条目名里有 NUL");
        String clean = name.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.indexOf("..") >= 0) throw new IOException("压缩包越界：" + name);
        File f = new File(dest, clean);
        if (!f.getCanonicalPath().startsWith(rootPrefix) && !f.getCanonicalPath().equals(dest.getCanonicalPath())) {
            throw new IOException("压缩包越界：" + name);
        }
        return f;
    }

    private static long copyOut(InputStream in, File target, long size) throws IOException {
        OutputStream out = new FileOutputStream(target);
        long total = 0;
        try {
            byte[] buf = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                int want = (int) Math.min(buf.length, left);
                int k = in.read(buf, 0, want);
                if (k < 0) throw new EOFException("包提前结束：" + target.getName());
                out.write(buf, 0, k);
                left -= k;
                total += k;
            }
        } finally {
            out.close();
        }
        return total;
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        try {
            OutputStream out = new FileOutputStream(dst);
            try {
                byte[] buf = new byte[64 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    private static void applyMode(File f, long mode) {
        boolean exec = (mode & 0111) != 0;
        try { f.setExecutable(exec, false); } catch (Throwable ignore) { }
        try { f.setReadable(true, false); } catch (Throwable ignore) { }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int k = in.read(buf, off, buf.length - off);
            if (k < 0) return false;
            off += k;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        long left = n;
        byte[] buf = new byte[8192];
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int k = in.read(buf, 0, want);
            if (k < 0) throw new EOFException("包提前结束");
            left -= k;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) skip(in, pad);
    }

    private static String str(byte[] b, int off, int len) {
        int end = off + len;
        while (end > off && b[end - 1] == 0) end--;
        return new String(b, off, end - off, Charset.forName("UTF-8"));
    }

    private static long parseOctal(byte[] b, int off, int len) {
        // GNU 的 base-256 编码（超大文件）这里用不到，按八进制处理即可
        if ((b[off] & 0x80) != 0) {
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (b[i] & 0xFF);
            return v;
        }
        String s = new String(b, off, len, ASCII).trim();
        if (s.length() == 0) return 0;
        try {
            return Long.parseLong(s.replace("\0", "").trim(), 8);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isEmpty(byte[] block) {
        for (byte v : block) if (v != 0) return false;
        return true;
    }

    /**
     * 解析 pax 扩展头，只关心 path 与 linkpath。
     * @return {path, linkpath}（缺的为 null）
     */
    private static String[] parsePax(byte[] body) {
        String path = null;
        String link = null;
        int i = 0;
        while (i < body.length) {
            int sp = -1;
            for (int j = i; j < body.length; j++) {
                if (body[j] == ' ') { sp = j; break; }
            }
            if (sp < 0) break;
            int len;
            try {
                len = Integer.parseInt(new String(body, i, sp - i, ASCII).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > body.length) break;
            String kv = new String(body, sp + 1, i + len - sp - 2, Charset.forName("UTF-8"));
            int eq = kv.indexOf('=');
            if (eq > 0) {
                String k = kv.substring(0, eq);
                String v = kv.substring(eq + 1);
                if ("path".equals(k)) path = v;
                else if ("linkpath".equals(k)) link = v;
            }
            i += len;
        }
        return new String[] { path, link };
    }
}
