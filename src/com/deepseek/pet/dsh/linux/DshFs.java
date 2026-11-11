package com.deepseek.pet.dsh.linux;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 文件读写小工具 —— <b>纯类</b>（只用 {@code java.io}，不认识 Android）。
 *
 * <p>为什么单独一个类：工具实现（{@code read}/{@code write}/{@code list}）和技能扫描
 * 都要读写文件，读写细节（上限、编码、原子写）只该有**一处**。
 *
 * <p>★ 所有读取都有上限：模型让它读一个 1 GB 的日志，不能把手机内存吃干净。
 * 超限就截断并在末尾注明截断了多少 —— 让模型知道"你只看到了一部分"，而不是以为全文就这么点。
 */
public final class DshFs {

    private DshFs() {}

    public static final int DEFAULT_READ_LIMIT = 200 * 1024;   // 200 KB
    public static final int MAX_READ_LIMIT = 1024 * 1024;      // 1 MB 硬上限

    /** 读文本（UTF-8，坏字节替换），失败返回 null。 */
    public static String readTextQuiet(File f, int maxBytes) {
        if (f == null || !f.isFile()) return null;
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            return readStream(in, maxBytes, null);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /**
     * 读流并截断。
     *
     * @param truncNote 非 null 时，若被截断会在末尾追加 {@code truncNote + 已读/总字节}
     */
    public static String readStream(InputStream in, int maxBytes, String truncNote) throws IOException {
        int cap = maxBytes <= 0 ? DEFAULT_READ_LIMIT : Math.min(maxBytes, MAX_READ_LIMIT);
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(cap, 65536));
        int total = 0;
        boolean truncated = false;
        while (true) {
            int want = Math.min(buf.length, cap - total + 1);
            if (want <= 0) { truncated = true; break; }
            int n = in.read(buf, 0, want);
            if (n < 0) break;
            if (total + n > cap) {
                out.write(buf, 0, cap - total);
                total = cap;
                truncated = true;
                break;
            }
            out.write(buf, 0, n);
            total += n;
        }
        String text = new String(out.toByteArray(), "UTF-8");
        if (truncated && truncNote != null) {
            text = text + "\n" + truncNote + "（已读 " + total + " 字节即到上限）";
        }
        return text;
    }

    /** 原子写：先写 {@code .tmp} 再改名，避免写一半掉电留下半个文件。 */
    public static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不了目录：" + parent.getAbsolutePath());
        }
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            os.write((text == null ? "" : text).getBytes("UTF-8"));
            os.flush();
        } finally {
            closeQuietly(os);
        }
        if (f.exists() && !f.delete()) {
            // Windows 上偶发（文件被占用）；退化成直接覆盖
            OutputStream os2 = null;
            try {
                os2 = new FileOutputStream(f);
                os2.write((text == null ? "" : text).getBytes("UTF-8"));
            } finally {
                closeQuietly(os2);
            }
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("改名失败：" + f.getAbsolutePath());
        }
    }

    /** 追加一行（会话日志用；每次都 flush，掉电最多丢最后一条未写完的行）。 */
    public static void appendLine(File f, String line) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("建不了目录：" + parent.getAbsolutePath());
        }
        FileOutputStream os = null;
        try {
            os = new FileOutputStream(f, true);
            os.write((line + "\n").getBytes("UTF-8"));
            os.flush();
        } finally {
            closeQuietly(os);
        }
    }

    /** 人类可读的大小。 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignore) {
            // 关不掉就算了
        }
    }

    /** 递归删除（自写，避免依赖 API 级别）。 */
    static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) {
            for (java.io.File k : kids) deleteTree(k);
        }
        f.delete();
    }
    /** 读 assets 里的文本文件。 */
    static String readAssetText(android.content.Context c, String path) throws IOException {
        InputStream in = null;
        try {
            in = c.getAssets().open(path);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int k;
            while ((k = in.read(b)) > 0) bo.write(b, 0, k);
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 把 assets 里的文件拷到 dest，**边拷边算 sha256**，先写 .part、校验过才改名。
     *
     * @param sha256 期望的摘要；空串表示不校验
     */
    static void copyAsset(android.content.Context c, String assetPath, File dest, String sha256)
            throws IOException {
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.isFile()) part.delete();
        java.security.MessageDigest md;
        try {
            md = java.security.MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("没有 SHA-256");
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = c.getAssets().open(assetPath);
            out = new java.io.FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                md.update(buf, 0, k);
            }
            out.flush();
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
        String got = hex(md.digest());
        if (sha256 != null && sha256.length() > 0 && !got.equalsIgnoreCase(sha256)) {
            part.delete();
            throw new IOException("包内载荷校验不过（期望 " + sha256 + " 实际 " + got + "）");
        }
        if (dest.isFile()) dest.delete();
        if (!part.renameTo(dest)) throw new IOException("改名失败：" + dest.getName());
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

}
