package com.deepseek.pet;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 游戏助手链路探针 —— 逐环定位"到底断在哪一步"。
 *
 * <h3>★ 为什么必须做这个探针</h3>
 * 整条链路有 7 环，任何一环断掉，用户看到的都只是"读数不出来"，
 * 完全分不清是 Shizuku 没授权、取数服务没绑上、还是图层名没解析到。
 * 真机上就出现过「Shizuku 状态：已就绪」与「实时帧率：—（Shizuku 未就绪）」
 * **同时显示**的矛盾界面 —— 所以这里把每一环单独报出来。
 *
 * <h3>怎么在本地跑出"远端"效果</h3>
 * {@code Stub.asInterface()} 对本地对象会直接返回原对象，**绕过编解码**，
 * 所以本地往返测试必须显式构造 {@code new Proxy(impl.asBinder())}。
 *
 * <h3>决定性证据</h3>
 * C4 那一行的 {@code id} 输出：经 Shizuku 后必须是 {@code uid=2000(shell)}。
 * 若仍是应用的 uid，说明命令没跑在 shell 身份下，取数必然失败。
 *
 * <p>排查命令：{@code adb logcat -s DSPFpsProbe:* DSPShizuku:*}
 */
public class FpsProbeActivity extends Activity {

    static final String TAG = "DSPFpsProbe";

    private final StringBuilder report = new StringBuilder();
    private TextView tv;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ★ 用户 2026-10-11：「所有三级菜单都要跟随主题切换」——系统自绘的东西
        //   （对话框确认框 / 输入法选择菜单 / 长按文本菜单）只认 Activity 主题，
        //   不认我们的令牌 ⇒ 必须先换成与明暗一致的系统主题。
        //   必须在 setContentView 之前（setTheme 的硬性要求）。
        Tokens.applyPlatformTheme(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (getResources().getDisplayMetrics().density * 12);
        box.setPadding(pad, pad, pad, pad);

        tv = new TextView(this);
        tv.setTextSize(11f);
        tv.setTextIsSelectable(true);
        box.addView(tv);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);

        runLocalTests();      // A/B：本地即可验
        runShizukuChain();    // C：需要 Shizuku，异步
    }

    private void log(final String s) {
        Log.i(TAG, s);
        main.post(new Runnable() {
            @Override public void run() {
                report.append(s).append('\n');
                if (tv != null) tv.setText(report.toString());
            }
        });
    }

    // ==================== 本地：不依赖 Shizuku ====================

    private void runLocalTests() {
        log("=== DSPFpsProbe ===");
        log("[A] 手写 Binder 编解码（本地，不需要 Shizuku）");
        try {
            IFpsUserService impl = new FpsUserService();
            // ★ 必须用 asBinder() 构造 Proxy：asInterface 对本地对象直接返回原对象，
            //   那样就绕过了 Parcel 编解码 = 测试空转。
            IFpsUserService viaProxy = new IFpsUserService.Proxy(impl.asBinder());

            String r1 = viaProxy.exec("echo BINDER_ROUNDTRIP_OK");
            log("    exec 往返: " + (r1 != null && r1.contains("BINDER_ROUNDTRIP_OK") ? "OK" : "FAIL")
                    + "  -> " + oneLine(r1));

            String r2 = viaProxy.execWithErr("echo OUT; echo ERR 1>&2");
            log("    execWithErr 合并 stderr: "
                    + (r2 != null && r2.contains("OUT") && r2.contains("ERR") ? "OK" : "FAIL")
                    + "  -> " + oneLine(r2));

            log("    空命令安全: " + (viaProxy.exec("") != null ? "OK" : "OK(null)"));
        } catch (Throwable t) {
            log("    异常: " + t);
        }

        log("");
        log("[B] FpsMath（本地造数据）");
        try {
            long a1 = 1000010000L;
            long a2 = a1 + 16666666L;      // 恰好一帧 60Hz
            FpsMath.Result r = FpsMath.compute("16666666\n"
                    + "1000000000 " + a1 + " 1000005000\n"
                    + "1000000000 " + a2 + " " + (a2 - 5000) + "\n0 0 0\n");
            log("    60fps 造数据 => " + r.fps + "  "
                    + (Math.abs(r.fps - 60f) < 1f ? "OK" : "FAIL"));
        } catch (Throwable t) {
            log("    异常: " + t);
        }
        log("");
    }

    // ==================== Shizuku 链路：逐环定位 ====================

    private void runShizukuChain() {
        log("[C] Shizuku 链路");

        boolean inst = ShizukuBridge.isInstalled(this);
        ShizukuBridge.State st = ShizukuBridge.refresh(this);
        log("    C1 已安装=" + inst + "  状态=" + st);
        log("       服务端版本=" + versionInfo());

        if (st != ShizukuBridge.State.READY) {
            log("       -> 未到 READY，链路到此为止。");
            log("       提示：" + ShizukuBridge.hint(this));
            log("");
            log("=== END ===");
            return;
        }

        // 发起绑定，然后在后台轮询等结果
        log("    C2 发起 bindUserService…");
        ShizukuBridge.bind(this);

        new Thread(new Runnable() {
            @Override public void run() {
                // 最多 3 次尝试：真机上 ShizukuX 会复用旧服务进程导致 token 失配，
                // 换进程名后缀重试能逼它起新进程（见 ShizukuBridge.processSuffix）。
                ShizukuBridge.BindState bs = ShizukuBridge.BindState.IDLE;
                long totalCost = 0;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    if (attempt > 1) {
                        log("    C2b 第 " + attempt + " 次尝试（换进程名）…");
                        ShizukuBridge.retryIfTimedOut(FpsProbeActivity.this);
                    }
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < 12000) {
                        bs = ShizukuBridge.bindState();
                        if (bs == ShizukuBridge.BindState.BOUND
                                || bs == ShizukuBridge.BindState.FAILED) {
                            break;
                        }
                        try { Thread.sleep(300); } catch (InterruptedException e) { break; }
                    }
                    totalCost += System.currentTimeMillis() - t0;
                    bs = ShizukuBridge.bindState();
                    if (bs == ShizukuBridge.BindState.BOUND) break;
                    log("        第 " + attempt + " 次失败：" + ShizukuBridge.bindError());
                }
                log("    C3 绑定结果=" + bs + "  总耗时=" + totalCost + "ms");
                // ★ 绑定失败**不再提前退出**：主路径是 IShizukuService.newProcess，
                //   它不需要绑定。第一版在这里 return，于是在 ShizukuX（双服务端 bug）
                //   上明明 newProcess 能用，探针却什么都不测就结束了。
                if (bs != ShizukuBridge.BindState.BOUND) {
                    log("       （绑定失败不影响主路径 newProcess，继续测）");
                    log("       绑定错误=" + ShizukuBridge.bindError());
                }

                // ★ 决定性证据：身份必须**高于普通应用**。
                //   官方 Shizuku（adb 激活）=> uid=2000(shell)
                //   Sui / KernelSU 等 root 方案 => uid=0(root)
                //   第一版只认 2000，结果在用户真机（KernelSU，uid=0）上误报 FAIL ——
                //   是探针自己的判据太窄，不是链路有问题。
                String who = ShizukuBridge.exec("id");
                log("    C4 身份 id => " + oneLine(who));
                boolean elevated = who != null
                        && (who.contains("uid=0(") || who.contains("uid=2000("));
                log("       身份已提权(root 或 shell): "
                        + (elevated ? "OK" : "FAIL（取不到 dumpsys 数据）"));

                // 图层枚举（旧口径，仅作参考：Android 17 上 --latency 已失效）
                String list = ShizukuBridge.exec("dumpsys SurfaceFlinger --list");
                int lines = list == null ? -1 : list.split("\n").length;
                log("    C5 SurfaceFlinger --list 行数=" + lines
                        + (lines > 0 ? "  OK" : "  FAIL"));

                // ★ 新口径：timestats 的按层帧统计（Android 17 上唯一可用的）
                ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -enable");
                try { Thread.sleep(500); } catch (InterruptedException ignore) {}

                // 完整取数
                // ★ 先等一个"别人"到前台：探针自己要占前台，而它要测的往往是游戏。
                //   轮询等最多 40 秒 —— 这段时间足够手动切到目标应用。
                //   等不到就退回采样本应用（仍能证明链路通）。
                MoodWatcher.Foreground fg = null;
                log("    C6 等待非本应用的前台应用（最多 40 秒，请切到游戏）…");
                long waitStart = android.os.SystemClock.uptimeMillis();
                while (android.os.SystemClock.uptimeMillis() - waitStart < 40000) {
                    fg = MoodWatcher.current(FpsProbeActivity.this);
                    if (fg != null) break;
                    try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                }
                if (fg == null) {
                    String raw = MoodWatcher.currentPackageRaw(FpsProbeActivity.this);
                    if (raw != null && raw.length() > 0) {
                        fg = new MoodWatcher.Foreground(raw, raw + "（本应用）", null);
                    }
                }
                if (fg == null) {
                    log("    C6 未识别到前台应用 —— 请停在某个应用里再跑一次");
                } else {
                    log("    C6 前台=" + fg.pkg);
                    String d1 = ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -dump");
                    java.util.List<FpsMath.LayerStat> st1 = FpsMath.parseTimeStats(d1);
                    FpsMath.LayerStat l1 = FpsMath.pickLayer(st1, fg.pkg);
                    log("       层数=" + st1.size() + "  选中层=" + (l1 == null ? "null" : l1.layer));
                    if (l1 == null) {
                        log("       -> 该应用没有可采样的绘制层");
                    } else {
                        log("       基准 totalFrames=" + l1.totalFrames
                                + "  averageFPS=" + l1.averageFps);
                        long t0 = android.os.SystemClock.uptimeMillis();
                        try { Thread.sleep(2000); } catch (InterruptedException ignore) {}
                        String d2 = ShizukuBridge.exec(
                                "dumpsys SurfaceFlinger --timestats -dump");
                        FpsMath.LayerStat l2 = FpsMath.pickLayer(
                                FpsMath.parseTimeStats(d2), fg.pkg);
                        if (l2 == null) {
                            log("       -> 第二次采样丢层");
                        } else {
                            long dt = android.os.SystemClock.uptimeMillis() - t0;
                            float fps = FpsMath.fpsFromDelta(
                                    l1.totalFrames, l2.totalFrames, dt);
                            log("    C7 差值法: " + l1.totalFrames + " -> " + l2.totalFrames
                                    + " / " + dt + "ms");
                            log("       => " + (fps >= 0
                                    ? ("实测 " + fps + " FPS  ★链路完全打通★")
                                    : "无新帧（画面静止）"));
                            log("       层内 averageFPS=" + l2.averageFps + "（累计值，仅参考）");
                        }
                    }
                }
                log("");
                log("=== END ===");
            }
        }, "probe-chain").start();
    }

    private String versionInfo() {
        try {
            return "v" + rikka.shizuku.Shizuku.getVersion()
                    + " preV11=" + rikka.shizuku.Shizuku.isPreV11()
                    + " latest=" + rikka.shizuku.Shizuku.getLatestServiceVersion();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String oneLine(String s) {
        if (s == null) return "null";
        String t = s.replace("\n", "\\n").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }
}
