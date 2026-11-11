package com.deepseek.pet.dsh.linux;

import android.content.Context;

import com.deepseek.pet.dsh.DshBridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * 在「Linux 引擎」这个 App 里跑 proot + Ubuntu。
 *
 * <h3>为什么这个类能成而主 App 里不行</h3>
 * 本包 {@code targetSdk=28}。Android 10+ 只对 targetSdk≥29 的应用禁止 execve 自己
 * data 目录里的文件；28 及以下不受这条限制（Termux 同理）。实测三条对照见
 * {@code docs/第二十版-proot落地-W^X实测.md}。
 *
 * <h3>Android 的两条限制与对策（这两条与 targetSdk 无关）</h3>
 * <ol>
 *   <li>从 App 私有目录 exec 二进制在 29+ 被禁 ⇒ 本包 proot 仍放在 {@code lib/<abi>/}
 *       （改名 {@code libproot.so}），依赖放 {@code assets/prootlibs/<abi>/} 首启解出来。</li>
 *   <li>Android 没有 {@code /etc/resolv.conf} ⇒ 从 ConnectivityManager 取 DNS 写进共享目录，
 *       bind 成 guest 的 {@code /etc/resolv.conf}（否则 apt/npm 全报"解析失败"）。</li>
 * </ol>
 */
public final class EngineProot {

    interface Sink {
        void onLine(String line);
    }

    /** 诊断开关：proot 详细日志（查明 ENOSYS 用，平时关）。 */
    static final boolean VERBOSE = false;

    private Process proc;
    private Thread pump;
    /** 记录当前进程的启动身份；root 子进程不能靠普通 App 的 killProcess 清理。 */
    private boolean procRoot;
    private Context procContext;

    // ==================== 路径 ====================

    static File binary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    static File loader(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
    }

    static File libDir(Context c) {
        return new File(c.getFilesDir(), "prootlib");
    }

    public static File rootfs(Context c) {
        return new File(c.getFilesDir(), "linux-rootfs");
    }

    static File share(Context c) {
        return new File(c.getFilesDir(), "share");
    }

    static File tmp(Context c) {
        return new File(c.getFilesDir(), "proot-tmp");
    }

    static File logFile(Context c) {
        return new File(c.getFilesDir(), "engine.log");
    }

    /** 三个前提都满足才算就绪。 */
    public static boolean available(Context c) {
        return binary(c).isFile() && binary(c).canExecute()
                && loader(c).isFile()
                && new File(rootfs(c), "usr/bin/bash").isFile()
                && new File(rootfs(c), "opt/node/bin/node").isFile();
    }

    static String status(Context c) {
        if (!binary(c).isFile()) return "缺 proot 运行时";
        if (!new File(rootfs(c), "usr/bin/bash").isFile()) return "还没装 Ubuntu 根";
        if (!new File(rootfs(c), "opt/node/bin/node").isFile()) return "还没装 Node";
        return "就绪";
    }

    // ==================== 运行 ====================

    /**
     * 起一段 bash 脚本（在 rootfs 里，fake root）。
     *
     * @param script 交给 {@code /bin/bash -lc} 的脚本
     * @param sink   输出回调
     */
    boolean runScript(Context c, String script, Sink sink) {
        List<String> inner = new ArrayList<String>();
        inner.add("/usr/bin/env");
        inner.add("-i");
        inner.add("PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        inner.add("HOME=/root");
        inner.add("TERM=dumb");
        inner.add("LANG=C.UTF-8");
        inner.add("DSH_HOME=/root/.dsh");
        inner.add("/bin/bash");
        inner.add("-lc");
        inner.add(script);
        return runArgv(c, inner, sink);
    }

    /** 目标目录存在才挂（proot 不允许挂到不存在的访客路径）。 */
    private static void addBind(List<String> cmd, File root, String host, String guest) {
        if (!new File(host).exists()) return;
        if (!new File(root, guest.substring(1)).exists()) {
            // 访客侧目标目录不存在就不挂，避免把宿主弄挂
            return;
        }
        cmd.add("-b");
        cmd.add(host + ":" + guest);
    }

    /** root 能不能用：限时跑一次 su -c id，看到 uid=0 才算。
     *
     * <p>只缓存成功：用户第一次拒绝或没及时授权，不应把本次进程里的 root
     * 永久判死。等待必须有上限，否则授权弹窗被后台限制挡住时启动线程会卡住。
     */
    private static boolean rootGranted;
    private static final long ROOT_PROBE_TIMEOUT_MS = 12000L;
    static synchronized boolean rootUsable(Context c) {
        if (rootGranted) return true;
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
            if (!p.waitFor(ROOT_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return false;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = r.readLine()) != null) sb.append(ln);
            rootGranted = p.exitValue() == 0 && sb.indexOf("uid=0") >= 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignore) {
            // 没装 su、SELinux 拒绝、启动失败：都按普通模式继续。
        } finally {
            if (p != null) p.destroy();
        }
        return rootGranted;
    }

    /** 给 shell 安全引用一个参数（su -c 走的是 shell，空格/引号都要处理）。 */
    private static String quote(String a) {
        if (a != null && a.length() > 0 && a.indexOf(' ') < 0 && a.indexOf('\'') < 0
                && a.indexOf('"') < 0 && a.indexOf('\\') < 0 && a.indexOf('$') < 0) {
            return a;
        }
        return "'" + a.replace("'", "'\\''") + "'";
    }

    /** 把已经检查过的 proot 参数交给 su；调用方负责避免 root 专用 bind 泄漏。 */
    private static List<String> suLaunch(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String a : cmd) sb.append(quote(a)).append(' ');
        List<String> launch = new ArrayList<String>();
        launch.add("su");
        launch.add("-c");
        launch.add(sb.toString());
        return launch;
    }

    /** 起一条内层命令（第一个元素是 guest 绝对路径）。 */
    boolean runArgv(Context c, List<String> inner, Sink sink) {
        return runArgv(c, inner, sink, false);
    }

    /** root 重试失败时，明确以普通权限重跑同一 guest 命令。 */
    boolean runArgvNonRoot(Context c, List<String> inner, Sink sink) {
        return runArgv(c, inner, sink, true);
    }

    private boolean runArgv(Context c, List<String> inner, Sink sink, boolean forceNonRoot) {
        stop();
        File share = share(c);
        if (!share.isDirectory() && !share.mkdirs()) return false;
        File tmp = tmp(c);
        if (!tmp.isDirectory() && !tmp.mkdirs()) return false;
        File resolv = new File(share, "resolv.conf");
        Dns.write(c, resolv);
        ensureLibs(c);

        List<String> cmd = new ArrayList<String>();
        cmd.add(binary(c).getAbsolutePath());
        if (VERBOSE) { cmd.add("-v"); cmd.add("3"); }
        cmd.add("-r");
        cmd.add(rootfs(c).getAbsolutePath());
        cmd.add("-0");
        // ★ 工作区不能在沙箱里 —— 用户要在文件管理器里看得到。
        //   但万一没拿到「访问所有文件」权限，就必须回退到沙箱，
        //   否则工作目录不存在 ⇒ 宿主直接起不来。
        File ext = new File("/storage/emulated/0");
        File extWs = new File(ext, "DeepSeekHarness");
        boolean useExt = ext.isDirectory() && (extWs.isDirectory() || extWs.mkdirs());
        cmd.add("-w");
        // 直接把宿主的工作目录放在 workspace：
        // 上游 web 档案默认以 cwd 作为工作区，这样就不用用户手点选择。
        cmd.add(useExt ? "/sdcard/DeepSeekHarness" : "/home/dsh/workspace");
        cmd.add("--kill-on-exit");
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        // ★ 用户报障：工作模式里看不到本机文件。
        //   原因：proot 的访客里根本没有 /storage/emulated/0。
        //   这里把它 bind 成 /sdcard（官方惯例）和 /home/dsh/storage（方便选）。
        //   注意：Android 共享存储是 FUSE，**不支持硬链接** ——
        //   所以会话库仍然必须在 /home/dsh/.dsh（App 私有），不能放到 /sdcard。
        // ★ 用户要求：**工作区要放在手机系统存储里**（文件管理器能看到）。
        //   把 /storage/emulated/0 挂成访客里的 /sdcard。
        //   ⚠️ 访客侧目标目录必须存在（见 Engine.ensureDirs 里的 "sdcard"），
        //   否则 proot 会报错、宿主根本起不来。
        //   ⚠️ 会话库仍在 /home/dsh/.dsh（App 私有）：Android 共享存储是 FUSE，
        //   不支持硬链接，而官方存储层要用 link 做原子发布。
        // ★ 工作区必须在手机系统存储里（文件管理器能看到）；
        //   但万一没拿到「访问所有文件」权限，就必须**回退到沙箱**，
        //   否则工作目录不存在 ⇒ 宿主直接起不来（且报错很难看懂）。
        if (useExt) {
            cmd.add("-b");
            cmd.add("/storage/emulated/0:/sdcard");
            // ★ 另一份挂到访客家目录里（/home/dsh/storage）。
            //   原因：官方 web 档案的“选择工作区”面板从**家目录**开始浏览，
            //   不挂就看不到手机存储（用户反馈：别人的 app 能浏览手机公共目录）。
            cmd.add("-b");
            cmd.add("/storage/emulated/0:/home/dsh/storage");
        }
        // ★ 用户要求：工作模式要能访问手机**所有**文件夹（普通 / ADB / root 三层）。
        //   proot 要求访客侧目标目录存在（见 Engine.ensureDirs 里的 mnt/*）。
        //   ① ADB 模式：/data/local/tmp 是 shell 可写目录，adb push 的东西都在这里。
        if (new File("/data/local/tmp").isDirectory()) {
            cmd.add("-b");
            cmd.add("/data/local/tmp:/mnt/tmp");
        }
        //   ② root 模式：整个 /data、/system、/storage 都挂进去（仅开关打开时）。
        boolean usedRoot = false;
        if (!forceNonRoot && com.deepseek.pet.dsh.DshBridge.rootHostEnabled(c) && rootUsable(c)) {
            usedRoot = true;
            addBind(cmd, rootfs(c), "/data", "/mnt/data");
            addBind(cmd, rootfs(c), "/system", "/mnt/system");
            addBind(cmd, rootfs(c), "/storage", "/mnt/storagevol");
            addBind(cmd, rootfs(c), "/data/adb", "/mnt/adb");
        }
        cmd.add("-b");
        cmd.add(share.getAbsolutePath() + ":/host");
        cmd.add("-b");
        cmd.add(resolv.getAbsolutePath() + ":/etc/resolv.conf");
        for (String a : inner) cmd.add(a);

        //   root 模式要真正拿到 root，必须**用 su 起 proot**（否则进程还是 app uid，
        //   bind 了 /data 也读不了别的应用目录）。密码/授权由 root 管理器弹窗。
        //   root 模式只绑定 /data 等特权目录；普通回退绝不复用这些参数。
        List<String> launch = usedRoot ? suLaunch(cmd) : cmd;
        ProcessBuilder pb = new ProcessBuilder(launch);
        pb.redirectErrorStream(true);
        pb.directory(c.getFilesDir());
        java.util.Map<String, String> env = pb.environment();
        env.put("LD_LIBRARY_PATH", libDir(c).getAbsolutePath() + ":"
                + c.getApplicationInfo().nativeLibraryDir);
        env.put("PROOT_LOADER", loader(c).getAbsolutePath());
        env.put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        // ★ 不要设 PROOT_NO_SECCOMP：关掉 seccomp 加速后 proot 要自己模拟每个系统调用，
        //   而它没实现 renameat2（返回 ENOSYS）⇒ node 的 fs.rename 直接失败、宿主起不来。
        //   保留 seccomp 加速时，proot 只拦它要翻译的那些，renameat2 走内核原生实现。
        env.put("HOME", c.getFilesDir().getAbsolutePath());

        try {
            proc = pb.start();
            procRoot = usedRoot;
            procContext = c.getApplicationContext();
        } catch (Throwable t) {
            if (sink != null) {
                sink.onLine("[proot] 启动失败：" + t.getClass().getSimpleName() + "：" + t.getMessage());
                sink.onLine("[proot] argv=" + launch);
            }
            return false;
        }
        pump = new Thread(new Pump(c, proc, sink), "proot-pump");
        pump.setDaemon(true);
        pump.start();
        return true;
    }

    /** 读输出：一行一行交给 sink，同时落盘。 */
    private static final class Pump implements Runnable {
        private final Context c;
        private final Process proc;
        private final Sink sink;

        Pump(Context c, Process proc, Sink sink) {
            this.c = c;
            this.proc = proc;
            this.sink = sink;
        }

        @Override public void run() {
            BufferedReader r = null;
            Writer log = null;
            try {
                log = new OutputStreamWriter(new java.io.FileOutputStream(logFile(c), false), "UTF-8");
            } catch (Throwable ignore) {
                log = null;
            }
            try {
                r = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                String line;
                while ((line = r.readLine()) != null) {
                    if (log != null) {
                        try {
                            log.write(line);
                            log.write('\n');
                            log.flush();
                        } catch (Throwable ignore) { }
                    }
                    if (sink != null) sink.onLine(line);
                }
            } catch (Throwable ignore) {
            } finally {
                try { if (r != null) r.close(); } catch (Throwable ignore) { }
                try { if (log != null) log.close(); } catch (Throwable ignore) { }
            }
        }
    }

    /** 把 assets/prootlibs/<abi> 解到私有目录（只解一次）。 */
    static void ensureLibs(Context c) {
        File dir = libDir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        // ★ 别只看 .done 标记：第一次跑（assets 还没打进去）会留下一个空目录 + .done，
        //   之后 proot 就永远 CANNOT LINK（libtalloc.so.2 not found）。判"真的解出来了"。
        File stamp = new File(dir, ".done");
        if (stamp.isFile() && new File(dir, "libtalloc.so.2").isFile()) return;
        String abi = c.getApplicationInfo().nativeLibraryDir.endsWith("x86_64") ? "x86_64" : "arm64-v8a";
        try {
            String[] names = c.getAssets().list("prootlibs/" + abi);
            if (names != null) {
                for (String n : names) {
                    java.io.InputStream in = c.getAssets().open("prootlibs/" + abi + "/" + n);
                    java.io.FileOutputStream out = new java.io.FileOutputStream(new File(dir, n));
                    byte[] buf = new byte[16 * 1024];
                    int k;
                    while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                    out.close();
                    in.close();
                }
            }
            DshFs.writeText(stamp, "ok");
        } catch (Throwable ignore) {
        }
    }

    /**
     * 清掉本 App 名下、指向本 rootfs 的残留 proot 进程。
     *
     * <p>为什么需要：系统把引擎的 App 进程杀掉时，proot（及其下面的 node）**不会跟着死**
     * —— 它是被 ptraced 的子进程，父进程死了仍在跑并占着 3080。下一次启动就会
     * {@code EADDRINUSE}，然后表现成"宿主没打印地址"，非常难查（实测踩到）。
     *
     * <p>只杀 {@code /proc/<pid>/cmdline} 里含本 rootfs 路径的进程（系统保证我们只能杀
     * 自己名下的），不会误伤别的东西。
     *
     * @return 杀掉的进程数
     */
    static int killLeftovers(Context c) {
        int killed = killLeftovers(c, false);
        // root 子进程不归 App uid（hidepid 让我们连 /proc 条目都看不到），
        // 只有**用户已经开了 root 模式**时才经 su 清理 —— 不开 root 就绝不申请 root。
        // 不开 root 时若真有 root 孤儿占着端口，由 Engine 的保底端口兜住（见 pickPort）。
        if (DshBridge.rootHostEnabled(c) && rootUsable(c)) {
            killed += killLeftoversViaSu(c);
        }
        return killed;
    }

    /**
     * 以 root 身份回收残留宿主（**枚举必须在 su 里做**）。
     *
     * <p>★★ 真机实测（<设备序列号> / PA2671 / Android 16，轮次140）：
     * Android 的 /proc 对普通 uid 有 hidepid 限制 —— App 侧 `new File("/proc").list()`
     * **看不到 root 拥有的进程**，于是"清残留"永远返回 0，端口却仍被那对
     * `libproot.so + node` 占着 ⇒ 新宿主 bind 失败 ⇒ 表现为"宿主没打印 dsh web"。
     * 实测日志原样：
     * <pre>
     * 端口 30800 被占用（…）
     * 30800 被占，清掉残留 proot 0 个，重选端口
     * 端口 30800 被其他程序占用，清理后仍拿不回来…
     * </pre>
     * 而 `ps -A` 里明明还活着 `node … --port 30800`。
     *
     * <p>另一个坑（同一轮实测）：只杀 proot 外壳，node 会被 init 收养并**继续占着端口**，
     * 所以这里必须把 proot 与 node 一起匹配。
     */
    private static int killLeftoversViaSu(Context c) {
        String rootPath = rootfs(c).getAbsolutePath();
        // 整个枚举+匹配+kill 都在 su 的 shell 里跑（它不受 hidepid 限制）。
        // 不用 pkill（旧 toybox 没有），用 /proc 扫描 + case 通配，兼容性最好。
        String script =
                "n=0; for d in /proc/[0-9]*; do "
              + "p=${d#/proc/}; "
              + "t=$(tr '\\0' ' ' < \"$d/cmdline\" 2>/dev/null); "
              + "case \"$t\" in "
              + "*libproot*|*/opt/node/bin/node*|*/opt/harness/node_modules*|*" + rootPath + "*) "
              + "kill -9 \"$p\" 2>/dev/null && n=$((n+1));; "
              + "esac; done; echo KILLED:$n";
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            if (!p.waitFor(8000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return 0;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
            String ln;
            int n = 0;
            while ((ln = r.readLine()) != null) {
                int i = ln.indexOf("KILLED:");
                if (i >= 0) {
                    try {
                        n = Integer.parseInt(ln.substring(i + 7).trim());
                    } catch (NumberFormatException ignore) { }
                }
            }
            if (n > 0) {
                try { Thread.sleep(1200); } catch (InterruptedException ignore) { }
            }
            return n;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Throwable ignore) {
            return 0;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** App 自己 uid 名下的残留（root 名下的那份走 {@link #killLeftoversViaSu}）。 */
    private static int killLeftovers(Context c, boolean unusedAsRoot) {
        int killed = 0;
        // ★ 匹配面要宽：只靠 rootfs 路径会漏掉"用别的参数起的那个 proot"，
        //   而它只要还活着就占着端口 ⇒ 新宿主 EADDRINUSE ⇒ 表现为"没打印 dsh web"（用户报的偶发失败）。
        String[] markers = new String[] {
            rootfs(c).getAbsolutePath(),          // proot 自己（宿主路径）
            "/opt/harness/node_modules",          // 里面的 node（**guest 路径**）
            "/opt/node/bin/node",
            "libproot.so",                        // 任何用我们 proot 起来的进程
            "libproot-loader.so",
            c.getPackageName() + "/files/prootlib",
        };
        String[] names = new File("/proc").list();
        if (names == null) return 0;
        int self = android.os.Process.myPid();
        for (String name : names) {
            int pid;
            try {
                pid = Integer.parseInt(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid == self) continue;
            String text;
            try {
                text = DshFs.readTextQuiet(new File("/proc/" + name + "/cmdline"), 2048);
            } catch (Throwable t) {
                continue;
            }
            if (text == null || text.length() == 0) continue;
            boolean hit = false;
            for (String m : markers) {
                if (text.indexOf(m) >= 0) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            try {
                android.os.Process.killProcess(pid);
                killed++;
            } catch (Throwable ignore) {
            }
        }
        if (killed > 0) {
            try { Thread.sleep(1200); } catch (InterruptedException ignore) { }   // 让端口真的放开
        }
        return killed;
    }

    void stop() {
        boolean wasRoot = procRoot;
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignore) { }
        proc = null;
        pump = null;
        procRoot = false;
        // destroy su 只会结束包装进程；proot/node 仍须由 root 身份回收
        // （★ 走 killLeftoversViaSu：App 侧枚举看不到 root 进程，实测返回 0）。
        if (wasRoot && procContext != null && rootUsable(procContext)) {
            killLeftoversViaSu(procContext);
        }
        procContext = null;
    }

    boolean running() {
        return proc != null;
    }

    /** 等当前进程结束（最多 maxMs）。 */
    boolean waitFor(long maxMs) {
        Thread t = pump;
        if (t == null) return true;
        try {
            t.join(maxMs);
            return !t.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
