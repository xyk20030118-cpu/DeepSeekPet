package com.deepseek.pet.dsh.linux;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 引擎的"大脑"：装 rootfs / Node / 包树，起上游宿主，抓住 {@code dsh web:} 那一行。
 *
 * <p>宿主起来的判据只有一条：**输出里出现 {@code dsh web: http://127.0.0.1:PORT/?token=…}**。
 * 抓不到就不算成功（进程在但装不起来是最常见的情形）。
 */
public final class Engine {

    /** 引擎状态（给控制接口与界面用）。 */
    static final String IDLE = "idle";
    static final String INSTALLING = "installing";
    static final String STARTING = "starting";
    static final String RUNNING = "running";
    static final String FAILED = "failed";

    private static final int LOG_LINES = 40;

    /**
     * 上游宿主的监听端口。**固定值，不许改也不许降级**。
     *
     * <p>为什么是 30800：① 与主 App 原生直跑的 3081 错开；② 避开上游默认的 3080
     * （别的工具/旧残留最容易占它）；③ 避开 adb reverse 常用的 43129 与旧控制口 43190；
     * ④ **必须小于 32768** —— 那以上是 Android/Linux 的临时端口范围，
     * listen 套接字落在里面会和出站连接的源端口抢，偶发 bind 失败。
     *
     * <p>★ 为什么必须固定：网页的配置存在 WebView 的 localStorage 里，而它按
     * 「协议+域名+端口」隔离 —— 端口一变，用户配好的模型/API Key 就整片消失。
     */
    static final int PORT = 30800;

    /**
     * 保底端口：只在 30800 被**杀不掉的**外部/孤儿进程占死时使用，且一旦启用就记住
     * （见 {@link #pickPort()} —— 目的是"能打开"而不是"随便换"）。
     */
    static final int PORT_ALT = 30801;

    /** 本次实际用的端口。★ 首选 30800；只有清不掉时才退 30801（见 pickPort）。 */
    private int port = PORT;

    /*
     * 端口策略（唯一出处就是 pickPort / pickFallbackPort，别在这里再放候选表）：
     *   ① 上次用过的端口（30800 或 30801）能绑就继续用 —— 端口稳定 ⇒ 网页配置不丢；
     *   ② 否则用 30800；
     *   ③ 30800 被占 ⇒ 先清残留进程重试；
     *   ④ 仍不行 ⇒ 保底 30801 并**记住**它（配置需重配一次，但之后不再漂）；
     *   ⑤ 两个都被占 ⇒ 如实报错。
     * ★ 历史：旧逻辑是「上次端口 → 3080 → 3090..3095 → 内核随机」，端口一路漂，
     *   而 WebView 的 localStorage 按端口隔离 ⇒ 用户每次都要重配模型。
     */

    /** prefs 键：上次成功用过的端口（下次优先复用，origin 尽量稳定）。 */
    private static final String KEY_PORT = "dsh_engine_port";

    /** prefs 键：引擎自己上一次的地址/token（复用要比"杀进程"优先：端口不变 ⇒ 配置不丢）。 */
    private static final String KEY_LAST_URL = "dsh_engine_last_url";
    private static final String KEY_LAST_TOKEN = "dsh_engine_last_token";

    /** 把上游自己的 startup-*.log 最新一份的前几行打进 logcat（宿主报错只写在这里）。 */
    private void dumpStartupLog(Context c) {
        try {
            File dir = new File(EngineProot.rootfs(c), "home/dsh/.dsh/logs");
            File[] fs = dir.listFiles();
            if (fs == null || fs.length == 0) {
                log("(没有 startup 日志)");
                return;
            }
            File newest = null;
            for (File f : fs) {
                if (!f.isFile()) continue;
                if (newest == null || f.lastModified() > newest.lastModified()) newest = f;
            }
            if (newest == null) return;
            log("== startup 日志：" + newest.getName() + " ==");
            String txt = DshFs.readTextQuiet(newest, 20000);
            if (txt == null) return;
            String[] lines = txt.split("\n");
            int n = 0;
            for (String ln : lines) {
                if (ln.trim().length() == 0) continue;
                log("| " + ln.trim());
                if (++n >= 22) break;
            }
        } catch (Throwable t) {
            log("(读 startup 日志失败：" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * 探一下端口上是不是真的有宿主在服务（有时候它就是不打印 URL）。
     *
     * @return 可用地址（不带 token）；探不到返回空串
     */
    private String probeUrl(int p) {
        if (p <= 0) return "";
        java.net.HttpURLConnection conn = null;
        try {
            String u = "http://127.0.0.1:" + p + "/";
            conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            log("探测 " + u + " → HTTP " + code);
            if (code >= 200 && code < 400) return u;
        } catch (Throwable t) {
            log("探测 " + p + " 失败：" + t.getClass().getSimpleName());
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) { }
        }
        return "";
    }

    /** 这个端口现在能不能绑（绑一下立刻关掉）。 */
    private static boolean canBind(int p) {
        java.net.ServerSocket ss = null;
        try {
            ss = new java.net.ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress("127.0.0.1", p), 1);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (ss != null) ss.close(); } catch (Throwable ignore) { }
        }
    }

    /**
     * 选端口：**首选固定 30800**，只在它被外部占死、且清不掉时保底用 30801。
     *
     * <p>★ 不再"上次用过的随便挑"——那会让端口漂移（旧逻辑会 3080→3090→…），而 WebView 的
     * localStorage 按端口隔离，用户配的模型就跟着丢。也**不允许 0**（内核随机分配）。
     *
     * <p>★ 保底端口为什么必须存在（真机实测，轮次140）：如果 30800 被一个**杀不掉**的
     * 残留进程占着（例如上一次以 root 模式跑留下的 root 孤儿，而用户后来关掉了 root 开关，
     * App 既看不到也杀不掉它），硬失败会让工作模式**彻底不可用**。
     * 这时换到 30801 并**永久记住**它 —— 代价是网页配置需要重配一次，
     * 但至少能用，而且之后端口再次稳定，不会再漂。
     */
    private int pickPort() {
        // ① 上次实际用的端口优先（30801 一旦被选中就固定下来，不再来回跳）
        int remembered = -1;
        try {
            remembered = com.deepseek.pet.dsh.DshBridge.prefs(c).getInt(KEY_PORT, -1);
        } catch (Throwable ignore) { }
        if (remembered > 0 && canBind(remembered)) {
            return remembered;
        }
        // ② 首选 30800
        if (canBind(PORT)) {
            rememberPort(PORT);
            return PORT;
        }
        log("端口 30800 被占用（先清残留进程，再重试；不轻易换端口——换了会丢网页配置）");
        return 0;
    }

    /** 清理无效后的最后一步：退回保底端口。 */
    private int pickFallbackPort() {
        if (canBind(PORT_ALT)) {
            rememberPort(PORT_ALT);
            return PORT_ALT;
        }
        return 0;
    }

    private void rememberPort(int p) {
        try {
            com.deepseek.pet.dsh.DshBridge.prefs(c).edit().putInt(KEY_PORT, p).apply();
        } catch (Throwable ignore) { }
    }

    /**
     * 复用**已在跑**的宿主：prefs 里有 url+token 且 HTTP 还能响应。
     *
     * <p>这是"每次都能打开"最省事的一层：孤儿 proot 占着 30800 时，
     * 不去抢端口、也不报错，直接用它。
     *
     * <p>★ 用的键必须是**引擎自己那一格**（`dsh_engine_last_url`）：以前读的是
     * `dsh_harness_url`（"用户配的远端地址"那一格，本机宿主从来不写它）⇒ 这条复用
     * 永远命中不了，只能靠杀进程。复用比杀进程更好：**端口不变 ⇒ 网页配置不丢**。
     */
    private boolean tryReuseRunning() {
        String u;
        String t;
        try {
            u = com.deepseek.pet.dsh.DshBridge.prefs(c).getString(KEY_LAST_URL, "");
            t = com.deepseek.pet.dsh.DshBridge.prefs(c).getString(KEY_LAST_TOKEN, "");
        } catch (Throwable e) {
            return false;
        }
        if (u == null || !u.startsWith("http://127.0.0.1")) return false;
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 500) {      // 200 或 303（带 token 会跳转）都算活着
                url = u;
                token = t == null ? "" : t;
                state = RUNNING;
                detail = "复用已在运行的宿主";
                log("复用已在跑的宿主：" + u + "（HTTP " + code + "，端口没变 ⇒ 网页配置还在）");
                return true;
            }
        } catch (Throwable ignore) {
            // 连不上 → 说明那个孤儿没在服务，继续走正常启动
        } finally {
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) { }
        }
        return false;
    }

    /** 记住引擎这次的地址与 token（下次启动先拿它试着复用，端口不变就复用）。 */
    private void rememberUrl(String u, String t) {
        try {
            com.deepseek.pet.dsh.DshBridge.prefs(c).edit()
                    .putString(KEY_LAST_URL, u == null ? "" : u)
                    .putString(KEY_LAST_TOKEN, t == null ? "" : t)
                    .apply();
        } catch (Throwable ignore) { }
    }

    private final Context c;
    private final List<String> log = new LinkedList<String>();
    private final EngineProot proot = new EngineProot();

    private String state = IDLE;
    private String detail = "";

    /** 用户切换过的 harness 版本标签（空 = 内置）。 */
    private String harnessLabel = "";
    private String url = "";
    private String token = "";
    private Thread worker;

    Engine(Context c) {
        this.c = c.getApplicationContext();
    }

    // ==================== 查询 ====================

    String state() { return state; }

    String detail() { return detail; }

    String url() { return url; }

    String token() { return token; }

    public boolean running() { return RUNNING.equals(state); }

    /** 最近的日志（JSON 里逐行给出，方便排查）。 */
    public List<String> logLines() {
        synchronized (log) {
            return new ArrayList<String>(log);
        }
    }

    private void log(String line) {
        synchronized (log) {
            log.add(line);
            while (log.size() > LOG_LINES) log.remove(0);
        }
        // ★ 同时写 logcat：设备没 root / 不可调试时，engine.log 读不到，
        //   只能靠 logcat 看宿主的真实报错。
        try { android.util.Log.i("DSPEngine", line == null ? "" : line); } catch (Throwable ignore) { }
    }

    // ==================== 载荷位置 ====================

    /** 载荷放在 App 外部私有目录（adb 好推，也方便以后换成下载）。 */
    static File payloadDir(Context c) {
        File d = c.getExternalFilesDir(null);
        return d != null ? d : new File(c.getFilesDir(), "payload");
    }

    static File payload(Context c, String name) {
        return new File(payloadDir(c), name);
    }

    /** 三个载荷是否齐（缺哪个就明说）。 */
    static String missingPayloads(Context c) {
        String abi = c.getApplicationInfo().nativeLibraryDir.endsWith("x86_64") ? "x64" : "arm64";
        StringBuilder sb = new StringBuilder();
        if (!payload(c, "ubuntu-base.tar.gz").isFile()) sb.append("ubuntu-base.tar.gz ");
        if (!payload(c, "node-linux-" + abi + ".tar.gz").isFile()) sb.append("node-linux-" + abi + ".tar.gz ");
        if (!payload(c, "harness-linux.tgz").isFile()) sb.append("harness-linux.tgz ");
        return sb.toString().trim();
    }

    // ==================== 下载 ====================

    /**
     * 按 `<base>/manifest.json` 把缺的载荷下下来（sha256 不符就整份重下）。
     *
     * <p>清单格式（与电脑端 `tools/pack_engine_payload.py` 产出的一致）：
     * <pre>
     * {"version":"0.1.7-rc.2","files":[
     *   {"name":"ubuntu-base.tar.gz","sha256":"…","bytes":29983772,"url":"ubuntu-base.tar.gz"},
     *   {"name":"node-linux-x64.tar.gz","sha256":"…","url":"node-linux-x64.tar.gz"},
     *   {"name":"harness-linux.tgz","sha256":"…","url":"harness-linux.tgz"}]}
     * </pre>
     * `url` 可以是相对 base 的路径，也可以是完整 URL。
     *
     * @param base 服务器地址（结尾带不带 / 都行）
     * @return 三个载荷是否齐了
     */
    synchronized boolean download(String base) {
        if (base == null || base.trim().length() == 0) {
            state = FAILED;
            detail = "没填服务器地址";
            return false;
        }
        String root = base.trim();
        // ★ 合并进主 App 之后，载荷就在自己的 assets 里：asset://engine 表示"从包内装"。
        //   零网络、零服务器、零跨进程 —— 以前那套 ContentProvider + 系统安装器全都不需要了。
        if (root.startsWith("asset://")) {
            return installFromAssets(root.substring("asset://".length()));
        }
        if (!root.endsWith("/")) root = root + "/";
        state = INSTALLING;
        detail = "取清单…";
        log(detail);
        try {
            File dir = Downloader.payloadDir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了载荷目录");
            String text = Downloader.readText(c, root + "manifest.json");
            org.json.JSONObject o = new org.json.JSONObject(text);
            org.json.JSONArray files = o.optJSONArray("files");
            if (files == null) throw new IOException("清单里没有 files");
            int okCount = 0;
            for (int i = 0; i < files.length(); i++) {
                org.json.JSONObject f = files.getJSONObject(i);
                String name = f.optString("name", "");
                String sha = f.optString("sha256", "");
                String rel = f.optString("url", name);
                long bytes = f.optLong("bytes", -1);
                if (name.length() == 0 || sha.length() == 0) throw new IOException("清单项缺 name/sha256");
                String url = rel.startsWith("http://") || rel.startsWith("https://") ? rel : root + rel;
                final String label = name;
                detail = "下载 " + name;
                log(detail);
                File dest = new File(dir, name);
                // 重试 3 次：移动网络/模拟器 NAT 都会偶发"传一半断掉"，一次失败就放弃太脆。
                IOException lastError = null;
                boolean done = false;
                for (int attempt = 1; attempt <= 3 && !done; attempt++) {
                    try {
                        Downloader.fetch(c, url, sha, dest, new Downloader.Progress() {
                            @Override public void onProgress(String n, long got, long total) {
                                long t = total > 0 ? total : (bytes > 0 ? bytes : -1);
                                detail = "下载 " + label + "：" + (got >> 20) + " MB"
                                        + (t > 0 ? " / " + (t >> 20) + " MB（" + (got * 100 / t) + "%）" : "");
                            }
                        });
                        done = true;
                    } catch (IOException e) {
                        lastError = e;
                        log("下载 " + name + " 第 " + attempt + " 次失败：" + e.getMessage());
                        if (attempt < 3) {
                            detail = "下载 " + name + " 重试中（第 " + (attempt + 1) + " 次）";
                            try { Thread.sleep(1500L * attempt); } catch (InterruptedException ie) { return false; }
                        }
                    }
                }
                if (!done) {
                    detail = "下载失败：" + name + " " + (lastError == null ? "" : lastError.getMessage());
                    log(detail);
                    state = FAILED;
                    return false;
                }
                okCount++;
                log("✅ " + name + " 校验通过");
            }
            // 记住这次用的服务器，下次默认填它
            try { DshFs.writeText(new File(dir, "source.txt"), root); } catch (Throwable ignore) { }
            detail = "载荷齐了（" + okCount + " 个）";
            log(detail);
            state = IDLE;
            boolean installed = install();
            // ★ 装好之后删压缩包：内容已经在 rootfs 里了，留着只是白占 150 MB。
            //   删之前必须 EngineProot.available() 为真（否则重装得重下）。
            if (installed && EngineProot.available(c)) {
                int freed = cleanupPayloads(c);
                if (freed > 0) {
                    detail = "安装完成，已清理 " + (freed >> 20) + " MB 压缩包";
                    log(detail);
                }
            }
            return installed;
        } catch (Throwable t) {
            state = FAILED;
            detail = "下载失败：" + t.getClass().getSimpleName() + "：" + t.getMessage();
            log(detail);
            return false;
        }
    }

    /**
     * 从**本 APK 的 assets** 里装载荷（用户没有电脑、没有服务器时走这条）。
     *
     * <p>三个大文件边拷边算 sha256，先写 `.part`、校验过才改成正式名 —— 和网络下载同一个口径。
     *
     * @param dirName assets 下的目录名（通常 "engine"）
     */
    synchronized boolean installFromAssets(String dirName) {
        File dir = Downloader.payloadDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            state = FAILED;
            detail = "建不了载荷目录";
            return false;
        }
        try {
            String text = DshFs.readAssetText(c, dirName + "/manifest.json");
            org.json.JSONObject o = new org.json.JSONObject(text);
            org.json.JSONArray files = o.optJSONArray("files");
            if (files == null) throw new IOException("清单里没有 files");
            for (int i = 0; i < files.length(); i++) {
                org.json.JSONObject f = files.getJSONObject(i);
                String name = f.optString("name", "");
                String sha = f.optString("sha256", "");
                if (name.length() == 0) throw new IOException("清单项缺 name");
                File dest = new File(dir, name);
                if (dest.isFile() && (sha.length() == 0 || Downloader.digestOf(dest).equalsIgnoreCase(sha))) {
                    continue;
                }
                state = INSTALLING;
                detail = "解包内 " + name;
                log(detail);
                DshFs.copyAsset(c, dirName + "/" + name, dest, sha);
            }
            detail = "包内载荷就绪";
            log(detail);
            state = IDLE;
            return install();
        } catch (Throwable t) {
            state = FAILED;
            detail = "包装载失败：" + t.getClass().getSimpleName() + "：" + t.getMessage();
            log(detail);
            return false;
        }
    }

    /**
     * 换一版 harness（下载一个 tgz → 清掉 {@code /opt/harness} → 解包 → 重启宿主）。
     *
     * <p>用途：用户想用别的版本（例如回退到 0.1.5-rc.2）。rootfs 与 Node 不动，只换包树，
     * 所以很快（一分钟级）。失败时旧包树已经删了 —— 会如实把原因写进 detail，重试即可。
     *
     * @param url   tgz 地址（http/https/content:// 都行）
     * @param label 版本标签（只用于显示）
     */
    public synchronized boolean switchHarness(String url, String label) {
        if (url == null || url.trim().length() == 0) {
            detail = "没填地址";
            return false;
        }
        try {
            File tmp = new File(Downloader.payloadDir(c), "harness-switch.tgz");
            if (tmp.isFile()) tmp.delete();
            state = INSTALLING;
            detail = "下载 " + (label.length() > 0 ? label : "harness") + "…";
            log(detail);
            Downloader.fetch(c, url.trim(), "", tmp, new Downloader.Progress() {
                @Override public void onProgress(String n, long got, long total) {
                    detail = "下载 harness：" + (got >> 20) + " MB"
                            + (total > 0 ? " / " + (total >> 20) + " MB" : "");
                }
            });
            File dir = new File(EngineProot.rootfs(c), "opt/harness");
            detail = "替换 /opt/harness…";
            log(detail);
            if (dir.isDirectory()) DshFs.deleteTree(dir);
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("建不了 /opt/harness");
            DshTarGz.extract(tmp, dir, null);
            tmp.delete();
            File bin = new File(dir, "node_modules/@deepseek-ai/dsh/lib/bin.js");
            if (!bin.isFile()) throw new IOException("这包里没有上游 bin.js（不是 harness 包树）");
            harnessLabel = label.length() > 0 ? label : "";
            try {
                DshFs.writeText(new java.io.File(Downloader.payloadDir(c), "harness-version.txt"),
                        harnessLabel);
            } catch (Throwable ignore) { }
            detail = "已切换 harness：" + (harnessLabel.length() > 0 ? harnessLabel : url);
            log(detail);
            state = IDLE;
            // 换包树之后要重启宿主才生效
            stop();
            start();
            return true;
        } catch (Throwable t) {
            state = FAILED;
            detail = "切换失败：" + t.getClass().getSimpleName() + "：" + t.getMessage();
            log(detail);
            return false;
        }
    }

    /** 当前 harness 的版本标签（内置默认 / 用户切换过就记下来）。 */
    public String harnessLabel() {
        try {
            File f = new File(Downloader.payloadDir(c), "harness-version.txt");
            if (f.isFile()) {
                String t = DshFs.readTextQuiet(f, 128).trim();
                if (t.length() > 0) return t;
            }
        } catch (Throwable ignore) { }
        return harnessLabel != null && harnessLabel.length() > 0 ? harnessLabel : "内置 0.1.7-rc.2";
    }

    /**
     * 删掉下载目录里的压缩包（只删我们认识的那三类，`source.txt` 留着）。
     *
     * <p>删的前提是 rootfs 已经装好 —— 调用方负责检查；这里只做"删文件"这一件事。
     *
     * @return 释放的字节数
     */
    static int cleanupPayloads(Context c) {
        File dir = Downloader.payloadDir(c);
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int freed = 0;
        for (File f : files) {
            String n = f.getName();
            boolean ours = n.equals("ubuntu-base.tar.gz")
                    || n.equals("harness-linux.tgz")
                    || n.startsWith("node-linux-");
            if (!ours || !f.isFile()) continue;
            long len = f.length();
            if (f.delete()) freed += (int) Math.min(Integer.MAX_VALUE, len);
        }
        return freed;
    }
    /** 上次用过的服务器地址（没有就空）。 */    static String lastSource(Context c) {
        try {
            File f = new File(Downloader.payloadDir(c), "source.txt");
            if (!f.isFile()) return "";
            return DshFs.readTextQuiet(f, 512).trim();
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 安装 ====================

    /**
     * 装 rootfs + Node + 包树 + profile（幂等：装过就跳过）。
     *
     * @return 是否已就绪
     */
    /** 进程内取引擎单例（主 App 的界面直接问它，不再走 HTTP 控制接口）。 */
    public static Engine get(Context c) {
        return EngineService.engine(c);
    }

    /** 当前状态名（idle / installing / starting / running / failed）。 */
    public String stateName() { return state; }

    /** 状态说明（下载/解包进度、失败原因都在这）。 */
    public String detailText() { return detail == null ? "" : detail; }

    /** 宿主打印的地址（没起来就是空）。 */
    public String urlText() { return url == null ? "" : url; }

    /** 地址里的 token。 */
    public String tokenText() { return token == null ? "" : token; }

    synchronized boolean install() {
        if (EngineProot.available(c)) return true;
        state = INSTALLING;
        detail = "开始安装";
        try {
            // ★ 缺载荷时先下载（有记住的源就用它），否则给出人话原因。
            //   不加这段的话，`pm clear` 之后启动会直接抛原始
            //   FileNotFoundException: .../ubuntu-base.tar.gz —— 用户看不懂（E2E 第一次跑就撞上）。
            String missing = missingPayloads(c);
            if (missing.length() > 0) {
                String source = lastSource(c);
                if (source.length() > 0) {
                    detail = "缺载荷，先从记住的源下载：" + source;
                    log(detail);
                    return download(source);
                }
                throw new IOException("缺载荷：" + missing
                        + "（请在引擎界面填服务器地址并点「下载并安装」，或由主 App 带 source 拉起）");
            }
            String abi = c.getApplicationInfo().nativeLibraryDir.endsWith("x86_64") ? "x64" : "arm64";
            File root = EngineProot.rootfs(c);
            final long[] lastReport = {0};
            // 1) Ubuntu 根
            if (!new File(root, "usr/bin/bash").isFile()) {
                detail = "解 Ubuntu 根";
                if (root.isDirectory()) DshFs.deleteTree(root);
                if (!root.mkdirs() && !root.isDirectory()) throw new IOException("建不了 rootfs 目录");
                DshTarGz.extract(payload(c, "ubuntu-base.tar.gz"), root, new DshTarGz.Progress() {
                    @Override public void onProgress(int entries, long bytes, String name) {
                        long now = System.currentTimeMillis();
                        if (now - lastReport[0] > 800) {
                            lastReport[0] = now;
                            detail = "解 Ubuntu 根：" + entries + " 条目 / " + (bytes >> 20) + " MB";
                            log(detail);
                        }
                    }
                });
            }
            // 2) Node（官方 Linux 包；解进 rootfs 的 /opt）
            File nodeBin = new File(root, "opt/node/bin/node");
            if (!nodeBin.isFile()) {
                detail = "解 Node";
                File opt = new File(root, "opt");
                if (!opt.isDirectory() && !opt.mkdirs()) throw new IOException("建不了 /opt");
                DshTarGz.extract(payload(c, "node-linux-" + abi + ".tar.gz"), opt, null);
                File unpacked = findDir(opt, "node-v");
                if (unpacked != null) {
                    File dest = new File(opt, "node");
                    if (dest.isDirectory()) DshFs.deleteTree(dest);
                    if (!unpacked.renameTo(dest)) throw new IOException("Node 目录改名失败：" + unpacked);
                }
            }
            if (!nodeBin.isFile()) throw new IOException("解完了还是找不到 /opt/node/bin/node");
            // 3) 上游包树
            File harness = new File(root, "opt/harness/node_modules/@deepseek-ai/dsh/lib/bin.js");
            if (!harness.isFile()) {
                detail = "解上游包树";
                File dir = new File(root, "opt/harness");
                if (dir.isDirectory()) DshFs.deleteTree(dir);
                if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("建不了 /opt/harness");
                DshTarGz.extract(payload(c, "harness-linux.tgz"), dir, null);
            }
            if (!harness.isFile()) throw new IOException("解完了还是找不到上游 bin.js");
            // 4) DSH_HOME 的 profile（patch 故意留空：不禁用任何条目）
            writeProfile(root);
            detail = "安装完成";
            log(detail);
            state = IDLE;
            return true;
        } catch (Throwable t) {
            state = FAILED;
            detail = "安装失败：" + t.getClass().getSimpleName() + "：" + t.getMessage();
            log(detail);
            return false;
        }
    }

    /**
     * 写 DSH_HOME（profiles/web）。{@code cordis.patch.yml} 就是 {@code []}——上游一行不改。
     *
     * <p>★ 用户要求：**主目录不要放在 /root**。虽然 proot 里我们以 root 身份跑，
     * 但用户看到 {@code /root/...} 会以为要真 root 权限。现在改成普通目录
     * {@code /home/dsh}（并 0777），且预建 {@code /home/dsh/workspace}：
     * 顺带治掉上游那句 “Unable to create default workspace”（它想建默认工作区但目录不在）。
     */
    /** 只保证目录存在且 0777（不写任何文件）。每次启动可以安全调用。 */
    private void ensureDirs(File root) throws IOException {
        File home = new File(root, "home/dsh");
        // ★ 用户要求：工作模式要能访问手机**所有**文件夹。
        //   1) 普通模式：/sdcard 就是共享存储（需「访问所有文件」权限）；
        //   2) ADB 模式：额外挂 /data/local/tmp（adb push 的东西都在这）；
        //   3) root 模式：额外挂 /data、/system、/storage、/data/adb。
        //   proot 要求访客侧目标目录存在，所以这些目录都要预建。
        String[] dirs = {"", "workspace", "storage", "mnt/tmp", "mnt/data", "mnt/system",
                "mnt/storagevol", "mnt/adb", ".dsh", ".dsh/sessions", ".dsh/storages",
                ".dsh/cache", ".dsh/logs", ".dsh/profiles"};
        for (String d : dirs) {
            File f = d.length() == 0 ? home : new File(home, d);
            if (!f.isDirectory() && !f.mkdirs()) throw new IOException("建不了 /home/dsh/" + d);
            setOpen(f);
        }
        // ★ 启动前把凭据文件改成 0600（官方插件严格要求）。
        //   我们用 umask 000 启动，宿主自己写出来的凭据会是 0666 ⇒ 下次启动就拒绝加载。
        File cred = new File(home, ".dsh/.credentials.yaml");
        if (cred.isFile()) {
            cred.setReadable(false, false);
            cred.setReadable(true, true);
            cred.setWritable(false, false);
            cred.setWritable(true, true);
        }
        for (String g : new String[] {"sdcard", "home/dsh/storage"}) {
            File gf = new File(root, g);
            if (!gf.isDirectory()) gf.mkdirs();
        }
    }

    private void writeProfile(File root) throws IOException {
        File home = new File(root, "home/dsh");
        // 共享存储里的工作区（用户能看到、能拖文件进去）
        try {
            File ext = new File("/storage/emulated/0/DeepSeekHarness");
            if (!ext.isDirectory()) ext.mkdirs();
        } catch (Throwable ignore) {
        }
        // ★ proot 的 bind 要求**访客侧目标目录必须存在**，否则 proot 报错、宿主起不来
        for (String g : new String[] {"sdcard", "home/dsh/storage"}) {
            File gf = new File(root, g);
            if (!gf.isDirectory() && !gf.mkdirs()) throw new IOException("建不了 /" + g);
            setOpen(gf);
        }
        File ws = new File(home, "workspace");
        if (!ws.isDirectory() && !ws.mkdirs()) throw new IOException("建不了 /home/dsh/workspace");
        // 放宽权限：proot 里以 uid0 跑，但用户可能以别的 uid 读这些目录
        setOpen(home);
        setOpen(ws);
        // ★ 用户报错：会话保存时 link() 报 EACCES。预建这些目录并 0777，
        //   避免它们被以 0700 建出来。
        String[] pre = {".dsh/sessions", ".dsh/storages", ".dsh/cache", ".dsh/logs", ".dsh/profiles"};
        for (String d : pre) {
            File f = new File(home, d);
            if (!f.isDirectory() && !f.mkdirs()) throw new IOException("建不了 " + d);
            setOpen(f);
        }
        File web = new File(home, ".dsh/profiles/web");
        if (!web.isDirectory() && !web.mkdirs()) throw new IOException("建不了 profile 目录");
        // ★ 已存在就不要重写：每次启动都改 package.json 会触发官方宿主的
        //   config 监听重载，实测直接报 'config reload at %C failed'（宿主就起不来）。
        //   目录/权限照样每次修正，但文件内容只在缺失时写。
        File pkg = new File(web, "package.json");
        if (!pkg.isFile()) DshFs.writeText(pkg,
                "{\n"
                        + "  \"name\": \"dsh-profile-web\",\n"
                        + "  \"private\": true,\n"
                        + "  \"dsh\": { \"profile\": { \"bundles\": [\"@deepseek-ai/dsh-base\", \"@deepseek-ai/dsh-web-app\"],"
                        + " \"patchReload\": \"startup\" } }\n"
                        + "}\n");
        File cy = new File(web, "cordis.yml");
        if (!cy.isFile()) DshFs.writeText(cy, "[]\n");
        File cp = new File(web, "cordis.patch.yml");
        if (!cp.isFile()) DshFs.writeText(cp, "[]\n");
        // ★★ 自愈：profile 目录里的文件会被上游改写成 0000（实测 cordis.patch.yml
        //   r=false w=false），于是宿主下次启动就 'EACCES open cordis.patch.yml' 直接退出
        //   —— 用户看到的就是"宿主没打印 dsh web 地址"。
        //   这里每次启动都把不可读的文件改回 owner 可读写（只针对 profile 目录，
        //   不动 .credentials.yaml 那种要求 0600 的）。
        try {
            File[] kids = web.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (!k.isFile() || k.canRead()) continue;
                    if (k.getName().endsWith(".unreadable")) continue;   // 别连环改名
                    // 先试改权限；改不动说明**文件不归本 uid**（实测：cordis.patch.yml），
                    // 那就把它改名让开 —— 目录是我们的，rename 不受影响；宿主会重建一份。
                    // 原文件保留成 .unreadable 供事后查（不直接删）。
                    k.setReadable(true, true);
                    k.setWritable(true, true);
                    boolean okNow = k.canRead();
                    String extra = "";
                    if (!okNow) {
                        File aside = new File(web, k.getName() + ".unreadable");
                        boolean moved = k.renameTo(aside);
                        extra = moved ? "（已改名为 " + aside.getName() + "）"
                                      : "（改名也失败）";
                    }
                    log("修复不可读的 profile 文件：" + k.getName()
                            + " 改权限后 r=" + okNow + extra);
                }
            }
        } catch (Throwable ignore) { }
        // ★ 诊断：宿主报 'EACCES open cordis.patch.yml' 时，这里能看到文件到底怎么了。
        try {
            log("myUid=" + android.os.Process.myUid());
            for (String nm : new String[] {".", "package.json", "cordis.yml", "cordis.patch.yml"}) {
                File f = ".".equals(nm) ? web : new File(web, nm);
                log("profile[" + nm + "] exists=" + f.exists() + " isFile=" + f.isFile()
                        + " isDir=" + f.isDirectory() + " r=" + f.canRead()
                        + " w=" + f.canWrite() + " x=" + f.canExecute()
                        + " len=" + f.length());
            }
        } catch (Throwable ignore) { }
    }

    /** 把目录（及其子项）开成任何人可读写执行（访客里用）。 */
    private static void setOpen(File f) {
        try {
            // ★ ★ 只放宽**目录**，绝不碰文件！
            //   实测教训：这里以前把文件也设成世界可写，
            //   结果 .credentials.yaml 每次启动都被我改成 0666 ⇒
            //   dsh-credentials-local 拒绝加载 ⇒ 宿主永远起不来（难查了好几轮）。
            if (f.isDirectory()) {
                f.setReadable(true, false);
                f.setWritable(true, false);
                f.setExecutable(true, false);
                File[] kids = f.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory()) setOpen(k);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    /** 最近一次启动的 stderr（用来判断失败原因，别只让用户"见日志"）。 */
    private StringBuilder lastStderr = new StringBuilder();
    /** 上一次失败的原因（重试后用）。 */
    private String lastFail = "";
    /** 每次 start 最多尝试一次 root；失败后明确退回普通权限。 */
    private boolean rootAttempted;
    private boolean rootFallback;
    /** 本次用了保底端口（30801）——记下来供日志/排障，不参与逻辑。 */
    private volatile boolean retryLaterToPrimary;

    /** 把失败原因翻成人话：端口占用 / 凭据权限 / 其它。 */
    private String failHint() {
        String t = lastStderr == null ? "" : lastStderr.toString();
        if (t.contains("EADDRINUSE")) {
            return "\u7aef\u53e3 30800 \u88ab\u4e0a\u4e00\u6b21\u7684\u5bbf\u4e3b\u5360\u7740\uff08EADDRINUSE\uff09\uff0c\u5df2\u5c1d\u8bd5\u6e05\u7406\u91cd\u8bd5";
        }
        if (t.contains("readable beyond its owner") || t.contains("credentials-local")) {
            return "\u51ed\u636e\u6587\u4ef6\u6743\u9650\u8fc7\u5bbd\uff08\u9700 0600\uff09\uff0c\u5bc4\u4e3b\u62d2\u7edd\u52a0\u8f7d";
        }
        if (t.contains("config reload") || t.contains("Failed plugins")) {
            return "\u5bc4\u4e3b\u63d2\u4ef6\u542f\u52a8\u5931\u8d25\uff08\u89c1 .dsh/logs/startup-*.log\uff09";
        }
        if (t.length() == 0) {
            return "\u5bc4\u4e3b\u6ca1\u6253\u5370 dsh web \u5730\u5740\uff08\u6ca1\u6709\u4efb\u4f55\u8f93\u51fa\uff0c\u53ef\u80fd\u88ab\u7cfb\u7edf\u6740\u4e86\uff09";
        }
        return "\u5bc4\u4e3b\u6ca1\u6253\u5370 dsh web \u5730\u5740\uff08\u89c1\u65e5\u5fd7\uff09";
    }

    private static File findDir(File parent, String prefix) {
        File[] kids = parent.listFiles();
        if (kids == null) return null;
        for (File f : kids) {
            if (f.isDirectory() && f.getName().startsWith(prefix)) return f;
        }
        return null;
    }

    // ==================== 启动 ====================

    /** 起宿主（异步）。成功后 {@link #url()} 就是 WebView 要打开的地址。 */
    synchronized void start() {
        if (running() || STARTING.equals(state)) return;
        state = STARTING;
        detail = "启动宿主";
        url = "";
        token = "";
        rootAttempted = false;
        rootFallback = false;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                if (!install()) return;
                // ★ 第一层：已经有一个能用的宿主（孤儿）就直接用，不抢端口。
                if (tryReuseRunning()) return;
                // ★ 第二层：选端口（首选固定 30800；被占先清孤儿，仍不行才保底换 30801）。
                port = pickPort();
                if (port <= 0) {
                    int k = EngineProot.killLeftovers(c);
                    log("30800 被占，清掉残留 proot " + k + " 个，重选端口");
                    try { Thread.sleep(1500); } catch (InterruptedException ignore) { }
                    port = pickPort();
                }
                if (port <= 0) {
                    // ★ 清理无效 ⇒ 保底端口（真机实测：root 孤儿关掉 root 开关后杀不掉，
                    //   硬失败会让工作模式彻底不可用）。这个选择会被记住，端口之后不再漂。
                    port = pickFallbackPort();
                    if (port > 0) {
                        detail = "端口 30800 被占用且清不掉，已改用 " + port
                                + "（网页配置需重配一次，之后不会再变）";
                        log(detail);
                        retryLaterToPrimary = true;
                    }
                }
                if (port <= 0) {
                    state = FAILED;
                    detail = "端口 30800/30801 都被其他程序占用；"
                            + "请完全退出 App 重开，或重启手机后再试";
                    log(detail);
                    return;
                }
                log("本次宿主端口：" + port
                        + (port == PORT ? "（固定端口，网页配置不会再丢）" : "（保底端口，已记住）"));
                rootAttempted = com.deepseek.pet.dsh.DshBridge.rootHostEnabled(c);
                rootFallback = false;
                // ★ 用户报“有时候进不去工作模式（宿主没打印 dsh web）”。
                //   偶发 ⇒ 多半是上一轮 proot 没退干净占着 30800，或冷启动慢被系统杀。
                //   这里最多试 2 次：第二次前先清干净再等 1.5s。
                for (int attempt = 1; attempt <= 2; attempt++) {
                if (attempt > 1) {
                    // 先按本次实际身份清理，再杀同 rootfs 的残留；然后重建 argv，
                    // 不能只把 su 换成普通启动而留下 /data 等 root 专用 bind。
                    try { proot.stop(); } catch (Throwable ignore) { }
                    int k2 = EngineProot.killLeftovers(c);
                    log("重试前清掉残留 proot " + k2 + " 个");
                    if (rootAttempted && !rootFallback) {
                        rootFallback = true;
                        detail = "root 宿主启动失败，退回普通权限重试";
                        log(detail);
                    }
                    try { Thread.sleep(1500); } catch (InterruptedException ignore) { }
                    url = "";
                    token = "";
                    state = STARTING;
                    detail = rootFallback ? "以普通权限重试启动宿主" : "重试启动宿主";
                }
                // ★ 每次启动都把 DSH_HOME（含 sessions/storages/workspace）按 0777 重写一遍：
                //   install() 装过就直接 return，不会走 writeProfile；
                //   而官方宿主会自己建 .dsh 子目录（默认 0700），
                //   proot 里 link() 就会 EACCES。这里强制放宽一次。
                // ★ 只修目录与权限，**绝不碰配置文件**：
                //   实测证明每次启动重写 package.json 会把 profile 搞坏，宿主随后就起不来。
                try {
                    File rf = EngineProot.rootfs(c);
                    ensureDirs(rf);          // 目录 + 0777
                    writeProfile(rf);        // 配置文件只在**缺失时**写（幂等）
                } catch (Throwable ignore) { }
                // ★ 系统杀过本进程时会留下孤儿 proot（它还占着 30800），先清掉再起
                lastStderr = new StringBuilder();
                int killed = EngineProot.killLeftovers(c);   // 起之前：清孤儿
                if (killed > 0) log("清掉残留 proot：" + killed + " 个");
                // ★ 必须用 guest 里的 env 把 DSH_HOME/HOME 设成 /root 下的路径。
                //   直接跑 node 的话，宿主的 HOME（App 私有目录）会漏进 guest，
                //   DSH_HOME 于是指向一个 guest 里不存在的路径 ⇒ 原子写的 rename 直接 ENOSYS。
                // ★★ 关键：先 umask 000 再 exec。
                //   官方宿主的会话保存是“写 .tmp → link() → unlink .tmp”，
                //   而 proot 模拟 link() 时对权限比真机严：目录/文件只要不是“任何人可写”就 EACCES。
                //   一句 umask 000 就能让它建出来的东西全都 0666/0777。
                StringBuilder cmd = new StringBuilder();
                // ★★ 不要 umask 000！实测结论：
                //   ① 用 root 在 proot 里对 022/000/077 三种 umask 各跑一次 link，**三个都 LINK-OK**
                //      ⇒ link 与 umask 无关，以前“加 umask 000 就好了”是假因果（那次同时删了 .dsh）；
                //   ② 而 umask 000 会让官方插件写出的 .credentials.yaml 变 0666，
                //      dsh-credentials-local 直接拒绝加载（'readable beyond its owner'）⇒ 宿主启不来。
                //   所以：用系统默认 umask。
                // 以下是旧注释（保留作为记录）：
                //   ① 会话保存用 link()，proot 对“不是任何人可写”的目录直接 EACCES
                //      ⇒ 必须 umask 000（否则会报 link '...tmp' -> '...zstd' EACCES）；
                //   ② dsh-credentials-local 又拒绝“权限过宽”的 .credentials.yaml（要 0600）
                //      ⇒ umask 000 会让它变 0666，所以**启动前**强制改成 600（见 ensureDirs）。
                // ★ 显式 umask 022：实测宿主建出来的文件权限是 **0000**（谁都读不了），
                //   下次启动就 EACCES open cordis.patch.yml ⇒ "宿主没打印 dsh web 地址"。
                //   不能用 umask 000（会让 .credentials.yaml 变 0666，凭据插件拒绝加载）。
                cmd.append("umask 022; exec /usr/bin/env -i ");
                cmd.append("PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin ");
                cmd.append("HOME=/home/dsh DSH_HOME=/home/dsh/.dsh TERM=dumb LANG=C.UTF-8 ");
                cmd.append("/opt/node/bin/node /opt/harness/node_modules/@deepseek-ai/dsh/lib/bin.js ");
                cmd.append("--profile web --no-open --port ").append(port);
                List<String> argv = new ArrayList<String>();
                argv.add("/bin/sh");
                argv.add("-c");
                argv.add(cmd.toString());
                EngineProot.Sink output = new EngineProot.Sink() {
                    @Override public void onLine(String line) {
                        log(line);
                        if (lastStderr.length() < 4000) lastStderr.append(line).append('\n');
                        String u = urlOf(line);
                        if (u.length() > 0) {
                            url = u;
                            token = tokenOf(u);
                            state = RUNNING;
                            detail = rootFallback ? "宿主已启动（普通权限）" : "宿主已启动";
                            // ★ 记住这次的地址：下次启动先试着复用它（端口不变 ⇒ 网页配置还在）
                            rememberUrl(url, token);
                        }
                    }
                };
                boolean ok = rootFallback
                        ? proot.runArgvNonRoot(c, argv, output)
                        : proot.runArgv(c, argv, output);
                if (!ok) {
                    state = FAILED;
                    detail = "proot 起不来";
                    lastFail = detail;
                    log("第 " + attempt + " 次 proot 起不来，重试看看");
                    continue;      // 不 return：交给下一次重试
                }
                if (!RUNNING.equals(state)) {
                    // 进程起来了但没打印地址：等一会儿再看，仍没有就算失败。
                    // ★ 等待从 20s 拉到 28s：冷启动/低电量时 node 启动确实会慢。
                    proot.waitFor(28000);
                    if (!RUNNING.equals(state)) {
                        // ★ 诊断：端口在不在监听？
                        //   不在 ⇒ 宿主真没起来；在 ⇒ 宿主已经起来了，只是我们没抓到那行 URL。
                        boolean listening = port > 0 && !canBind(port);
                        if (listening) {
                            // ★ 宿主已在监听：直接探用。它有时候就是不打印那行 URL，
                            //   但页面本身是能打开的。
                            String probed = probeUrl(port);
                            if (probed.length() > 0) {
                                url = probed;
                                token = "";
                                state = RUNNING;
                                detail = "宿主在监听 " + port + "（未拿到 token，已直接探用）";
                                rememberUrl(url, token);
                                return;
                            }
                        }
                        log("诊断：端口 " + port + " 监听中=" + listening
                                + "，收到输出字节="
                                + (lastStderr == null ? 0 : lastStderr.length()));
                        state = FAILED;
                        detail = listening
                                ? "宿主已在监听 " + port + " 但没打印 token（见日志）"
                                : failHint();
                    }
                }
                if (RUNNING.equals(state)) break;
                lastFail = detail;
                log("第 " + attempt + " 次启动失败：" + detail);
                dumpStartupLog(c);
                }   // for (attempt)
                if (!RUNNING.equals(state) && lastFail.length() > 0) detail = lastFail;
            }
        }, "engine-start");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 自检：rename 在本机（App 身份）到底行不行。
     *
     * <p>上游 `dsh-atomic-write` 用「写临时文件 + rename」落盘；宿主启动时
     * `.credentials.yaml` 那一步如果 rename 返回 ENOSYS，整个 connection 插件就起不来
     * （实测就是卡在这里）。所以这条必须单独验。
     */
    synchronized void selfTest() {
        state = STARTING;
        detail = "自检中";
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder out = new StringBuilder();
                proot.runScript(c,
                        "echo '== whoami =='; id -u;"
                        + " echo '== rename =='; echo one > /root/a.tmp && mv /root/a.tmp /root/a.txt && echo RENAME-OK || echo RENAME-FAIL;"
                        + " echo '== mv-dir =='; mkdir -p /root/d1 /root/d2 && echo y > /root/d1/f && mv /root/d1/f /root/d2/f && echo MOVE-OK || echo MOVE-FAIL;"
                        + " echo '== node =='; /opt/node/bin/node -v;"
                        // node 的 rename 单独验：宿主启动时 dsh-atomic-write 就是死在 rename 上。
                        // 探针脚本用 base64 塞进去，避免多层引号互相吃。
                        + " echo '== node-rename =='; echo "
                        + "Y29uc3QgZnM9cmVxdWlyZSgnZnMnKTsKdHJ5e2ZzLndyaXRlRmlsZVN5bmMoJy9yb290L24udG1wJywneCcpO2ZzLnJlbmFtZVN5bmMoJy9yb290L24udG1wJywnL3Jvb3Qvbi50eHQnKTtjb25zb2xlLmxvZygnTk9ERS1SRU5BTUUtT0snKX1jYXRjaChlKXtjb25zb2xlLmxvZygnTk9ERS1SRU5BTUUtRkFJTCcsZS5tZXNzYWdlKX0KZnMucHJvbWlzZXMucmVuYW1lKCcvcm9vdC9uLnR4dCcsJy9yb290L24yLnR4dCcpLnRoZW4oKCk9PmNvbnNvbGUubG9nKCdOT0RFLVJFTkFNRS1BU1lOQy1PSycpLGU9PmNvbnNvbGUubG9nKCdOT0RFLVJFTkFNRS1BU1lOQy1GQUlMJyxlLm1lc3NhZ2UpKTs="
                        + " | base64 -d > /root/t.js; /opt/node/bin/node /root/t.js 2>&1 | tail -4;"
                        + " echo '== node20 =='; if [ -x /opt/node20/bin/node ]; then /opt/node20/bin/node -v; /opt/node20/bin/node /root/t.js 2>&1 | tail -3; else echo no-node20; fi;"
                        + " echo '== mv-n =='; echo r > /root/m.tmp && mv -n /root/m.tmp /root/m.txt && echo MVN-OK || echo MVN-FAIL;"
                        + " echo '== done =='",
                        new EngineProot.Sink() {
                            @Override public void onLine(String line) {
                                log(line);
                            }
                        });
                proot.waitFor(120000);
                state = IDLE;
                detail = "自检完成";
            }
        }, "engine-selftest").start();
    }
    public synchronized void stop() {
        proot.stop();
        // ★ destroy() 只杀 proot 本身；它底下被 ptraced 的 node 可能活下来继续占着端口
        //   （实测：服务已经没了，30800 还在 LISTEN）。所以停完必须收尸。
        int killed = EngineProot.killLeftovers(c);   // 停之后再收一次
        state = IDLE;
        detail = killed > 0 ? ("已停止（回收残留进程 " + killed + " 个）") : "已停止";
        if (killed > 0) log(detail);
        url = "";
        token = "";
    }

    // ==================== 解析 ====================

    /** 从一行输出里抠出 {@code http://127.0.0.1:PORT/?token=…}。 */
    static String urlOf(String line) {
        if (line == null) return "";
        int i = line.indexOf("http://127.0.0.1:");
        if (i < 0) return "";
        if (line.indexOf("dsh web", 0) < 0 && i > 40) return "";
        String rest = line.substring(i).trim();
        int sp = rest.indexOf(' ');
        return sp > 0 ? rest.substring(0, sp) : rest;
    }

    static String tokenOf(String url) {
        int i = url.indexOf("token=");
        if (i < 0) return "";
        String t = url.substring(i + 6);
        int amp = t.indexOf('&');
        return amp > 0 ? t.substring(0, amp) : t;
    }
}
