package com.deepseek.pet.dsh;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 在手机上**跑上游 Node 宿主**（而不是连电脑）。
 *
 * <h3>为什么只能用 nativeLibraryDir 里的可执行文件</h3>
 * Android 10+（targetSdk≥29）禁止从 App 私有目录 exec 二进制（W^X）。APK 的
 * {@code lib/<abi>/} 由系统解到 {@code nativeLibraryDir}，那里是**只读代码目录**，可以 exec。
 * 所以 Node 被改名成 {@code libnode-bin.so} 放在 lib/ 里（Android 只打包 .so），
 * 它的依赖（libicu / openssl / libc++ …）同样以 {@code libtermux_*.so} 放进去，
 * 运行时靠 {@code LD_LIBRARY_PATH} 指向 nativeLibraryDir 让链接器找到。
 *
 * <h3>启动契约（照桌面端 harness-node-entry.mjs）</h3>
 * <pre>
 * node &lt;…/@deepseek-ai/dsh/lib/bin.js&gt; --profile web
 * env: DSH_HOME=&lt;files/harness/home&gt;  HOME/TMPDIR=&lt;私有目录&gt;
 * </pre>
 * 宿主起来后会打印 {@code dsh web: http://127.0.0.1:PORT/?token=…} ——
 * 这一行就是 WebView 要打开的地址（{@link Sink#onUrl}）。
 */
public final class DshNodeProcess {

    public interface Sink {
        /** 一行输出（stdout/stderr 混流）。 */
        void onLine(String line);

        /** 抓到了 {@code dsh web: http://…} 那一行。 */
        void onUrl(String url);

        /** 进程退出了（启动失败也走这里，code 可能是 -1）。 */
        void onExit(int code);
    }

    private Process proc;
    private Thread pump;

    /** 宿主输出写一份到这里（{@code files/dsh-node.log}）——界面上的日志会被裁掉，出问题要看全的。 */
    public static File logFile(Context c) {
        return new File(c.getFilesDir(), "dsh-node.log");
    }

    /**
     * 原生直跑这条轨道用的端口。
     *
     * <p>刻意不用上游默认的 3080：那是「Linux 引擎」那条轨道的地盘。
     * 两条同时活着时互不打架（实测过 EADDRINUSE 会把整个 webserver 顶掉，
     * 然后表现成"6 个插件装配失败"，非常难查）。
     */
    public static int port() {
        return 3081;
    }

    /** lib/ 里那颗 node（改名成 .so 才有机会可执行）。 */
    public static File nodeBinary(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libnode-bin.so");
    }

    public static boolean available(Context c) {
        File f = nodeBinary(c);
        return f.isFile() && f.canExecute();
    }

    /** 跑 {@code node -v}：用来验证"运行时能不能起来"而不依赖任何包。 */
    public boolean selfTest(final Context c, final Sink sink) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(nodeBinary(c).getAbsolutePath());
        cmd.add("-v");
        return exec(c, cmd, sink);
    }

    /**
     * 起 web 宿主。
     *
     * @param cliEntry 上游 {@code @deepseek-ai/dsh/lib/bin.js} 的绝对路径
     * @param dshHome  DSH_HOME（files/harness/home）
     */
    public boolean startWeb(Context c, File cliEntry, File dshHome, Sink sink) {
        if (cliEntry == null || !cliEntry.isFile()) {
            if (sink != null) {
                sink.onLine("[node] 找不到上游 CLI：" + (cliEntry == null ? "null" : cliEntry.getAbsolutePath()));
                sink.onExit(-1);
            }
            return false;
        }
        List<String> cmd = new ArrayList<String>();
        cmd.add(nodeBinary(c).getAbsolutePath());
        cmd.add(cliEntry.getAbsolutePath());
        cmd.add("--profile");
        cmd.add("web");
        // 手机上没有浏览器可以"打开"，不加这个上游会去 spawn xdg-open 然后报一行 ENOENT
        cmd.add("--no-open");
        // ★ 端口要跟「Linux 引擎」那条错开：上游默认 3080，引擎也用它。
        //   两条轨道同时活着时（比如引擎在跑、又点了原生直跑）会 EADDRINUSE，
        //   所以原生直跑固定拿 3081。上游 `--port` 是官方参数，不是我们打的补丁。
        cmd.add("--port");
        cmd.add(String.valueOf(port()));
        return exec(c, cmd, sink);
    }

    private boolean exec(Context c, List<String> cmd, final Sink sink) {
        stop();
        File home = new File(c.getFilesDir(), "harness/home");
        File tmp = new File(c.getCacheDir(), "node-tmp");
        if (!tmp.isDirectory()) tmp.mkdirs();
        if (!home.isDirectory()) home.mkdirs();

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(c.getFilesDir());
        java.util.Map<String, String> env = pb.environment();
        // ★ 顺序很重要：LD_LIBRARY_PATH 指向 nativeLibraryDir，node 才能找到同目录的 libicu/openssl
        // ★ 依赖库不能放 lib/（名字得是精确 soname，如 libz.so.1；APK 只打包 lib*.so），
        //   所以从 assets 解到私有目录，再用 LD_LIBRARY_PATH 指过去。
        File libDir = new File(c.getFilesDir(), "harness/lib");
        ensureNativeLibs(c, libDir);
        // 上游在手机上跑不了那三个原生 provider ⇒ 每次启动把 Android 版装上（与 APK 同版本）
        ensureAndroidProfile(c, home);
        // ★ 顺序很重要：**先**指解出来的 node 依赖目录，再指 nativeLibraryDir。
        //   lib/ 里也有 libc++_shared.so（本地模型/语音用的那份 NDK 版本，和 Termux 的 node 不是同一版），
        //   如果 nativeLibraryDir 排在前面，node 会先加载到 NDK 那份 —— 版本不匹配会直接崩。
        //   node 自己的依赖（libicu/libssl/…）只在 harness/lib 里，所以这个顺序对它是正确且必要的。
        env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath()
                + ":" + c.getApplicationInfo().nativeLibraryDir);
        env.put("HOME", c.getFilesDir().getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("DSH_HOME", home.getAbsolutePath());
        env.put("NO_COLOR", "1");

        try {
            proc = pb.start();
        } catch (Throwable t) {
            if (sink != null) {
                sink.onLine("[node] 启动失败：" + t.getClass().getSimpleName() + "：" + t.getMessage());
                sink.onExit(-1);
            }
            return false;
        }

        pump = new Thread(new Runnable() {
            @Override public void run() {
                BufferedReader r = null;
                java.io.Writer log = null;
                try {
                    log = new java.io.OutputStreamWriter(
                            new java.io.FileOutputStream(logFile(c), false), "UTF-8");
                    log.write("# " + new java.util.Date().toString() + " argv=" + cmd + "\n");
                } catch (Throwable ignore) {
                    DshFs.closeQuietly(log);
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
                        if (sink != null) {
                            sink.onLine(line);
                            String u = urlOf(line);
                            if (u.length() > 0) sink.onUrl(u);
                        }
                    }
                } catch (Throwable ignore) {
                } finally {
                    DshFs.closeQuietly(r);
                    DshFs.closeQuietly(log);
                    int code = -1;
                    try {
                        code = proc.waitFor();
                    } catch (Throwable ignore) { }
                    if (sink != null) sink.onExit(code);
                }
            }
        }, "node-pump");
        pump.setDaemon(true);
        pump.start();
        return true;
    }

    /** 把 {@code assets/android-plugin} 装进 profile（每次启动覆盖，保证与 APK 同版本）。 */
    static void ensureAndroidProfile(Context c, File home) {
        try {
            File web = new File(home, "profiles/web");
            if (!web.isDirectory() && !web.mkdirs()) return;
            copyAssetTree(c, "android-plugin", web);
            // profile 根的目录文件（上游要求存在；空数组也算）
            File root = new File(web, "cordis.yml");
            if (!root.isFile()) DshFs.writeText(root, "[]\n");
            File pkg = new File(web, "package.json");
            if (!pkg.isFile()) {
                DshFs.writeText(pkg, "{\n"
                        + "  \"name\": \"dsh-profile-web\",\n"
                        + "  \"private\": true,\n"
                        + "  \"dsh\": {\n"
                        + "    \"profile\": {\n"
                        + "      \"bundles\": [\"@deepseek-ai/dsh-base\", \"@deepseek-ai/dsh-web-app\"],\n"
                        + "      \"patchReload\": \"startup\"\n"
                        + "    }\n"
                        + "  }\n"
                        + "}\n");
            }
        } catch (Throwable ignore) {
        }
    }

    private static void copyAssetTree(Context c, String assetDir, File dest) {
        try {
            String[] names = c.getAssets().list(assetDir);
            if (names == null || names.length == 0) return;
            if (!dest.isDirectory() && !dest.mkdirs()) return;
            for (String n : names) {
                String rel = assetDir + "/" + n;
                String[] kids = c.getAssets().list(rel);
                File target = new File(dest, n);
                if (kids != null && kids.length > 0) {
                    copyAssetTree(c, rel, target);
                    continue;
                }
                java.io.InputStream in = c.getAssets().open(rel);
                java.io.OutputStream out = new java.io.FileOutputStream(target);
                byte[] buf = new byte[16 * 1024];
                int k;
                while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
                out.close();
                in.close();
            }
        } catch (Throwable ignore) {
        }
    }

    /** 从一行输出里抠出 web 地址（上游原话：{@code dsh web: http://127.0.0.1:43129/?token=…}）。 */
    static String urlOf(String line) {
        if (line == null) return "";
        int i = line.indexOf("http://");
        if (i < 0) return "";
        if (line.indexOf("dsh web", 0) < 0 && line.indexOf("Listening", 0) < 0
                && line.indexOf("http://127.0.0.1", 0) < 0) {
            // 只认明显是宿主地址的行，避免把插件日志里的 URL 当入口
            if (line.indexOf("dsh web") < 0) return "";
        }
        String rest = line.substring(i).trim();
        int sp = rest.indexOf(' ');
        return sp > 0 ? rest.substring(0, sp) : rest;
    }


    /** 把 {@code assets/nodelibs/<abi>} 解到私有目录，并补齐 soname 别名（libX.so.V.W → libX.so.V）。 */
    static void ensureNativeLibs(Context c, File libDir) {
        String abi = c.getApplicationInfo().nativeLibraryDir;
        String tag = abi.endsWith("x86_64") ? "x86_64" : "arm64-v8a";
        if (!libDir.isDirectory() && !libDir.mkdirs()) return;
        File stamp = new File(libDir, ".done");
        if (!stamp.isFile()) {
            try {
                String[] names = c.getAssets().list("nodelibs/" + tag);
                if (names != null) {
                    for (String n : names) {
                        java.io.InputStream in = c.getAssets().open("nodelibs/" + tag + "/" + n);
                        java.io.OutputStream out = new java.io.FileOutputStream(new File(libDir, n));
                        byte[] buf = new byte[64 * 1024];
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
        // soname 别名：libicudata.so.78.3 ⇒ 还需要 libicudata.so.78
        File[] fs = libDir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String n = f.getName();
            int i = n.indexOf(".so.");
            if (i < 0) continue;
            String head = n.substring(0, i + 4);       // libicudata.so.
            String ver = n.substring(i + 4);           // 78.3
            if (ver.indexOf('.') < 0) continue;        // 已经是 libX.so.V
            String alias = head + ver.substring(0, ver.indexOf('.'));
            File a = new File(libDir, alias);
            if (!a.exists()) {
                try {
                    java.nio.file.Files.copy(f.toPath(), a.toPath());
                } catch (Throwable ignore) {
                }
            }
        }
    }

    public void stop() {
        try {
            if (proc != null) proc.destroy();
        } catch (Throwable ignore) { }
        proc = null;
        pump = null;
    }

    public boolean running() {
        return proc != null;
    }
}
