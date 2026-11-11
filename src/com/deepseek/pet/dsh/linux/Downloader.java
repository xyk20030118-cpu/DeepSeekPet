package com.deepseek.pet.dsh.linux;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 载荷下载：按 manifest 拉三个包，边下边算 sha256，校验过才落成正式文件名。
 *
 * <h3>为什么 manifest 本身就是"信任锚"</h3>
 * 我们不签自己的包，能做的只有一件事：**从哪儿拿清单，就只信那份清单里写的 sha256**。
 * 所以界面上要用户自己填一个服务器地址 —— 他信任那个服务器，等于信任清单。
 * 这一点必须写在界面上，不能让人以为"校验过就等于官方"。
 *
 * <h3>为什么先写 .part 再改名</h3>
 * 半截文件如果直接叫正式名字，下次启动会被当成"已下好"而解包失败；
 * 只有 sha256 对上了才 rename 成正式名，失败就删掉重来。
 */
final class Downloader {

    /** 下载进度回调。 */
    interface Progress {
        /**
         * @param name      当前文件
         * @param got       已下字节
         * @param total     总字节（-1 表示服务器没给 Content-Length）
         */
        void onProgress(String name, long got, long total);
    }

    private Downloader() { }

    /**
     * 下载一个文件并校验。
     *
     * @param url      完整 URL
     * @param sha256   期望的 sha256（十六进制小写）
     * @param dest     目标文件（先写 .part，校验过才改名）
     * @param p        进度回调，可为 null
     * @return 是否成功（已存在且校验通过也算成功）
     */
    static boolean fetch(Context c, String url, String sha256, File dest, Progress p) throws IOException {
        if (dest.isFile() && digestOf(dest).equalsIgnoreCase(sha256)) return true;
        File part = new File(dest.getAbsolutePath() + ".part");
        if (part.isFile()) part.delete();

        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("没有 SHA-256");
        }
        long total;
        try {
            if (url.startsWith("content://")) {
                // ★ 同机随包提供（另一个 App 的 ContentProvider）：不联网、不需要服务器
                android.content.ContentResolver cr = c.getContentResolver();
                android.net.Uri u = android.net.Uri.parse(url);
                total = sizeOf(cr, u);
                in = cr.openInputStream(u);
                if (in == null) throw new IOException("打不开 " + url);
            } else {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            total = conn.getContentLengthLong();
            in = conn.getInputStream();
            }
            out = new FileOutputStream(part);
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            long last = 0;
            int k;
            while ((k = in.read(buf)) > 0) {
                out.write(buf, 0, k);
                md.update(buf, 0, k);
                got += k;
                long now = System.currentTimeMillis();
                if (p != null && now - last > 400) {
                    last = now;
                    p.onProgress(dest.getName(), got, total);
                }
            }
            out.flush();
            if (p != null) p.onProgress(dest.getName(), got, total);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) { }
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) { }
        }
        String got = hex(md.digest());
        if (!got.equalsIgnoreCase(sha256)) {
            part.delete();
            throw new IOException("sha256 不符（期望 " + shortSha(sha256) + "，实际 " + shortSha(got) + "）");
        }
        if (dest.isFile()) dest.delete();
        if (!part.renameTo(dest)) throw new IOException("改名失败：" + dest.getName());
        return true;
    }

    /** 算一个文件的 sha256。 */
    /** 从 ContentProvider 问文件大小（问不到就 -1，进度条只会显示已下量）。 */
    static long sizeOf(android.content.ContentResolver cr, android.net.Uri u) {
        android.database.Cursor cur = null;
        try {
            cur = cr.query(u, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (i >= 0 && !cur.isNull(i)) return cur.getLong(i);
            }
        } catch (Throwable ignore) {
        } finally {
            try { if (cur != null) cur.close(); } catch (Throwable ignore) { }
        }
        return -1;
    }

    static String digestOf(File f) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) md.update(buf, 0, k);
            return hex(md.digest());
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
        }
    }

    /** 读一段文本（清单）。 */
    static String readText(Context c, String url) throws IOException {
        if (url.startsWith("content://")) {
            InputStream cin = null;
            try {
                cin = c.getContentResolver().openInputStream(android.net.Uri.parse(url));
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int k;
                while ((k = cin.read(b)) > 0) bo.write(b, 0, k);
                return new String(bo.toByteArray(), "UTF-8");
            } finally {
                try { if (cin != null) cin.close(); } catch (Throwable ignore) { }
            }
        }
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("清单 HTTP " + code);
            in = conn.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int k;
            int guard = 0;
            while ((k = in.read(buf)) > 0) {
                bos.write(buf, 0, k);
                if (++guard > 4096) throw new IOException("清单太大");
            }
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignore) { }
        }
    }

    /** 载荷目录（外部私有目录，够大也好换）。 */
    static File payloadDir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : new File(c.getFilesDir(), "payload");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v & 0xFF));
        return sb.toString();
    }

    private static String shortSha(String s) {
        return s == null ? "?" : (s.length() > 8 ? s.substring(0, 8) : s);
    }
}
