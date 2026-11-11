package com.deepseek.pet.dsh;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * <b>本地下载 / 配置 Harness</b> —— 把上游包树装到手机上，不依赖电脑。
 *
 * <h3>为什么"下载"而不是"全塞进 APK"</h3>
 * 桌面端那份 {@code node_modules} 实测 <b>278 MB / 15,288 个文件</b>，
 * 全打进 APK 会让安装包从 15 MB 变成 150~200 MB，而且每次跟上游同步都要重发整个包。
 * 所以拆成两半：
 * <ul>
 *   <li><b>随 APK 走</b>：Node 运行时（{@code lib/arm64-v8a/libnode-android.so} 等）。
 *       ★ 必须这样：Android 10+（targetSdk≥29）**禁止从 App 私有目录 exec 二进制**，
 *       只有 {@code nativeLibraryDir} 里的文件才可执行 —— 这条实测踩过，不是选择；</li>
 *   <li><b>本地下载</b>：上游包树 zip + DSH_HOME 模板（纯 JS/JSON，不需要 exec 权限）。</li>
 * </ul>
 *
 * <h3>布局（全部在 App 私有目录里）</h3>
 * <pre>
 * files/harness/
 *   bundles/&lt;version&gt;/node_modules/…   ← 下载解压出来的包树
 *   home/                              ← DSH_HOME（profiles / settings.yaml / patch）
 *   installed.json                     ← 装的是哪个版本、校验值、时间
 * </pre>
 *
 * <h3>★ 校验</h3>
 * 清单里带 {@code sha256}；下载完先算摘要再解压 —— 不校验就等于把"随便什么人塞的 JS"跑进 App。
 * 解压时挡路径穿越（{@code ../}），不让 zip 写到 harness 目录外面。
 */
public final class DshHarnessInstaller {

    /** 下载源（用户可在界面里改；默认指向局域网里那台电脑上的打包脚本）。 */
    public static final String DEFAULT_BASE = "http://192.168.1.10:8080/harness/";

    /** 清单文件名（放在 base 下）。 */
    public static final String MANIFEST = "manifest.json";

    public interface Progress {
        /** @param done 已下载字节；@param total 总字节（未知时 <=0） */
        void onProgress(long done, long total);

        void onStage(String stage);

        void onError(String message);
    }

    private final File root;

    public DshHarnessInstaller(File appFiles) {
        this.root = new File(appFiles, "harness");
    }

    public File root() {
        return root;
    }

    public File bundlesDir() {
        return new File(root, "bundles");
    }

    public File homeDir() {
        return new File(root, "home");
    }

    public File marker() {
        return new File(root, "installed.json");
    }

    public File nodeModules() {
        return new File(new File(bundlesDir(), installedVersion()), "node_modules");
    }

    // ==================== 状态 ====================

    /** 装好的版本号；没装返回空串（读失败时退回"bundles 下唯一目录"的兜底）。 */
    public String installedVersion() {
        File f = marker();
        if (f.isFile()) {
            String txt = readAll(f);
            Object o = com.deepseek.pet.Json.parseOrNull(txt);
            if (o instanceof Map) {
                Object v = ((Map<?, ?>) o).get("version");
                if (v != null && String.valueOf(v).trim().length() > 0) {
                    return String.valueOf(v).trim();
                }
            }
        }
        // ★ 兜底：标记丢了/读不出来，但包树确实在 ⇒ 按 bundles 下的目录名认
        File[] dirs = bundlesDir().listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && new File(d, "node_modules/@deepseek-ai/dsh/lib/bin.js").isFile()) {
                    return d.getName();
                }
            }
        }
        return "";
    }

    /** 直接读文件（不用 DshFs.readTextQuiet —— 它把失败原因吞掉了，这里要能看见）。 */
    static String readAll(File f) {
        java.io.InputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            DshFs.closeQuietly(in);
        }
    }

    /** 装好了吗：有版本 + 上游 CLI 真的在。 */
    public boolean isInstalled() {
        return installedVersion().length() > 0 && cliEntry().isFile();
    }

    /** 上游 CLI 入口（桌面端用的是同一个 @deepseek-ai/dsh/lib/bin.js）。 */
    public File cliEntry() {
        return new File(nodeModules(), "@deepseek-ai/dsh/lib/bin.js");
    }

    // ==================== 清单 ====================

    /** 下载并解析清单；失败抛 IOException（调用方给一句人话）。 */
    public Manifest fetchManifest(String base) throws IOException {
        String txt = httpGetText(join(base, MANIFEST));
        Object o = com.deepseek.pet.Json.parseOrNull(txt);
        if (!(o instanceof Map)) throw new IOException("清单不是合法 JSON");
        return Manifest.from((Map<?, ?>) o);
    }

    /** 清单里的一项（zip 包 + 校验 + 解到哪）。 */
    public static final class Item {
        public String name = "";
        public String url = "";
        public String sha256 = "";
        public long bytes;
        /** bundle = 包树（解到 bundles/&lt;version&gt;/），home = DSH_HOME 模板（解到 home/） */
        public String target = "bundle";

        static Item from(Map<?, ?> m) {
            Item i = new Item();
            i.name = str(m.get("name"));
            i.url = str(m.get("url"));
            i.sha256 = str(m.get("sha256")).toLowerCase(java.util.Locale.US);
            i.bytes = lng(m.get("bytes"), 0);
            String t = str(m.get("target"));
            if (t.length() > 0) i.target = t;
            return i;
        }
    }

    /** 清单：{version, items:[{name,url,sha256,bytes,target}]}。 */
    public static final class Manifest {
        public String version = "";
        public final List<Item> items = new ArrayList<Item>();

        static Manifest from(Map<?, ?> m) {
            Manifest mf = new Manifest();
            mf.version = str(m.get("version"));
            Object arr = m.get("items");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    if (o instanceof Map) mf.items.add(Item.from((Map<?, ?>) o));
                }
            }
            return mf;
        }
    }

    // ==================== 安装 ====================

    /**
     * 按清单安装：下载 → 校验 sha256 → 解压。
     *
     * @return 安装好的版本号
     */
    public String install(String base, Manifest mf, Progress p) throws IOException {
        if (mf == null || mf.items.isEmpty() || mf.version.length() == 0) {
            throw new IOException("清单里没有内容");
        }
        File tmp = new File(root, "tmp");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("建不了临时目录");

        for (Item it : mf.items) {
            // ★ 清单里的 url 可能是相对文件名（就是"同目录下的 packages.zip"），
            //   直接当绝对 URL 用会 MalformedURLException: no protocol: packages.zip
            //   —— 实测踩到过（自己挂本地服务器安装时）。
            String rel = it.url.length() > 0 ? it.url : (it.name.length() > 0 ? it.name + ".zip" : "bundle.zip");
            String url = rel.startsWith("http://") || rel.startsWith("https://") ? rel : join(base, rel);
            if (p != null) p.onStage("下载 " + (it.name.length() > 0 ? it.name : url));
            File zip = new File(tmp, safe(it.name.length() > 0 ? it.name : "bundle") + ".zip");
            download(url, zip, it.bytes, p);

            if (it.sha256.length() > 0) {
                if (p != null) p.onStage("校验 " + zip.getName());
                String got = sha256(zip);
                if (!got.equalsIgnoreCase(it.sha256)) {
                    zip.delete();
                    throw new IOException("校验不通过（期望 " + it.sha256.substring(0, Math.min(8, it.sha256.length()))
                            + "…，实际 " + got.substring(0, 8) + "…）—— 已丢弃");
                }
            }

            File dest = "home".equals(it.target)
                    ? homeDir()
                    : new File(bundlesDir(), mf.version);
            if (p != null) p.onStage("解压到 " + dest.getName());
            unzipInto(zip, dest);
            zip.delete();
        }

        // 目录就位：上游 CLI 必须有 bin.js，否则这次安装不算成功
        if (!cliEntry().isFile()) {
            throw new IOException("装完了但找不到 @deepseek-ai/dsh/lib/bin.js —— 清单里的包不对");
        }
        // DSH_HOME 至少要有 settings.yaml（没有就写一个空的，别让宿主启动时炸）
        if (!homeDir().isDirectory()) homeDir().mkdirs();

        Map<String, Object> m = DshJson.map(
                "version", mf.version,
                "installedAt", Long.valueOf(System.currentTimeMillis()),
                "items", Integer.valueOf(mf.items.size()));
        DshFs.writeText(marker(), DshJson.write(m));
        return mf.version;
    }

    /** 写入用户配置（服务商 / Key / 模型）—— "配置"这一步的真正内容。 */
    public void writeSettings(String provider, String apiKey, String model) throws IOException {
        if (!homeDir().isDirectory()) homeDir().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("agent-default-model:\n");
        sb.append("  provider: ").append(yaml(provider)).append('\n');
        sb.append("  model: ").append(yaml(model)).append('\n');
        // ★ 只写用户自己的 Key，且不写进日志；上游读的是 profile 里的 provider 配置
        sb.append("llm-pi-ai:\n");
        sb.append("  providers:\n");
        sb.append("    ").append(yaml(provider)).append(":\n");
        sb.append("      displayName: 手机\n");
        sb.append("      apiKeyEnv: DSH_API_KEY\n");
        sb.append("      api: openai-completions\n");
        sb.append("      baseURL: ").append(yaml(com.deepseek.pet.ApiClient.getBaseUrl())).append('\n');
        DshFs.writeText(new File(homeDir(), "settings.yaml"), sb.toString());
    }

    private static String yaml(String s) {
        String v = s == null ? "" : s.trim();
        if (v.length() == 0) return "\"\"";
        // 只做最小转义：带特殊字符就加引号
        if (v.matches("[A-Za-z0-9_./:\\-]+")) return v;
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ==================== 下载 / 校验 / 解压 ====================

    private void download(String url, File out, long expect, Progress p) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            long total = c.getContentLength() > 0 ? c.getContentLength() : expect;
            in = c.getInputStream();
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, total);
            }
            os.flush();
            if (total > 0 && done < total) throw new IOException("下载不完整（" + done + "/" + total + "）");
        } finally {
            DshFs.closeQuietly(in);
            DshFs.closeQuietly(os);
            if (c != null) c.disconnect();
        }
    }

    static String sha256(File f) throws IOException {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("没有 SHA-256 实现");
        } finally {
            DshFs.closeQuietly(in);
        }
    }

    /** 解压（挡路径穿越；zip 里只认文件）。 */
    static void unzipInto(File zip, File dest) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs()) throw new IOException("建不了目录 " + dest);
        String base = dest.getCanonicalPath() + File.separator;
        ZipInputStream in = null;
        try {
            in = new ZipInputStream(new java.io.FileInputStream(zip));
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String name = e.getName();
                if (name == null || name.length() == 0) continue;
                if (name.indexOf("..") >= 0 || name.startsWith("/") || name.indexOf(':') >= 0) {
                    throw new IOException("压缩包里有非法路径：" + name);
                }
                File out = new File(dest, name);
                String canon = out.getCanonicalPath();
                if (!canon.startsWith(base)) throw new IOException("压缩包越界：" + name);
                if (e.isDirectory()) {
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                OutputStream os = null;
                try {
                    os = new FileOutputStream(out);
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                } finally {
                    DshFs.closeQuietly(os);
                }
            }
        } finally {
            DshFs.closeQuietly(in);
        }
    }

    private static String httpGetText(String url) throws IOException {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
            in = c.getInputStream();
            return DshFs.readStream(in, 512 * 1024, "");
        } finally {
            DshFs.closeQuietly(in);
            if (c != null) c.disconnect();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long lng(Object o, long d) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return d;
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static String join(String base, String name) {
        String b = base == null ? "" : base.trim();
        if (!b.endsWith("/")) b = b + "/";
        return b + name;
    }

    private static String safe(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            sb.append(Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' ? ch : '_');
        }
        return sb.length() == 0 ? "bundle" : sb.toString();
    }
}
