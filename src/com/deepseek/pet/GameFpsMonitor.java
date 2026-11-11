package com.deepseek.pet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 游戏助手 · 前台应用实时帧率采样。
 *
 * <h3>数据流（Android 17 真机实测口径）</h3>
 * <pre>
 *   MoodWatcher.current()  取前台包名（复用现有能力，靠 PACKAGE_USAGE_STATS）
 *        ↓
 *   ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -dump")
 *        ↓
 *   FpsMath.parseTimeStats() → pickLayer()  找到该应用的主绘制层
 *        ↓
 *   FpsMath.fpsFromDelta()  相邻两次 totalFrames 差值 / 间隔 = 实时 FPS
 * </pre>
 *
 * <h3>★ 为什么改成"多监听 + 引用计数"</h3>
 * 第一版是"单个可替换回调 + start/stop"。但悬浮窗（{@code FloatingService}）
 * 和设置页（{@code MainActivity}）**都要用**这份数据：
 * <ul>
 *   <li>单个回调 ⇒ 后注册的把先注册的顶掉（服务在跑，设置页一打开读数就没了）</li>
 *   <li>直接 stop ⇒ 设置页 {@code onPause} 一停，悬浮窗的挂牌也停了</li>
 * </ul>
 * 所以改成：多个监听者各自收结果；生命周期用引用计数，
 * 谁需要谁 {@link #acquire}、不需要就 {@link #release}，
 * 计数归零才真正停止采样。
 *
 * <h3>★★ 引用计数是"归还自己那一份"，不是"停掉采样"（真机 bug 的教训）</h3>
 * 用户真机反馈：**"帧率监测容易失效卡住不动，点开游戏助手的选项才恢复正常，
 * 一退出又卡住"**。根因是调用方 acquire/release **不配对**：
 * {@code MainActivity} 只在进过第 7 节时才 {@code acquire}，却在
 * {@code onPause} 与 {@code onDestroy} 里**无条件** {@code release} 两次 ——
 * 于是"打开 App 再退出"这条最普通的路径会把悬浮窗那一份引用抵消掉，
 * 计数归零 ⇒ 采样线程被停 ⇒ 挂牌数字**冻在最后一帧**（既不更新、也不消失）。
 * 进第 7 节又 acquire 一次 ⇒ 恢复；退出再 release ⇒ 又冻住。
 *
 * <p>现在的三道防线：
 * <ol>
 *   <li>调用方严格配对（{@code MainActivity.gameFpsAcquired} 标志位）；</li>
 *   <li>{@link #release()} 在计数已为 0 时**忽略**（多还一份不再能停掉采样）；</li>
 *   <li>悬浮窗 5 秒巡检发现"我还持有引用、但采样已经停了 / 线程卡住了"
 *       ⇒ 自愈（{@link #acquire} / {@link #restart}）。</li>
 * </ol>
 */
public final class GameFpsMonitor {

    private GameFpsMonitor() {}

    /** 日志 TAG：真机排查用 `adb logcat -s DSPFpsMon:* DSPFpsBadge:*`。 */
    private static final String TAG = "DSPFpsMon";

    /** 采样间隔（毫秒）。1 秒足够 —— FPS 读数抖得太快反而看不清。 */
    private static final long INTERVAL_MS = 1000;

    /**
     * 换层后"快速重建基准"的等待时长（毫秒）。
     *
     * <p>★ 用户反馈："只要切换应用就暂时无法监测帧率需要等待"。
     * 根因是换层后要等一个完整周期（{@link #INTERVAL_MS} = 1 秒）才出数。
     * 这里隔 300ms 补采一次，加上 dump 本身耗时，约 0.3~0.4 秒就能出数。
     * 300ms 在 60fps 下约 18 帧，足够算出一个稳定的值。
     */
    private static final long FAST_REBASE_MS = 300;

    /**
     * 全量审计间隔（毫秒）：稳态走 grep 快路径时**看不到整张图层表**，
     * 靠它定期拉一次完整 dump 核对表容量（见 {@link FpsMath#LAYER_TABLE_CLEAR_AT}）。
     * 完整 dump 实测约 100ms / 1MB，两分钟一次可忽略。
     */
    private static final long FULL_AUDIT_MS = 120000;

    /** 采样结果回调（都在主线程）。 */
    public interface Callback {
        /**
         * @param pkg       前台包名
         * @param appName   应用名（可能等于包名）
         * @param fps       实测帧率；小于 0 表示画面静止（没在出帧）或取不到
         * @param refreshHz 屏幕刷新率
         * @param layer     解析到的图层名（诊断用，可能为 null）
         * @param m         同一次采样里取到的系统指标（CPU/GPU/RAM/温度/电池/功耗/网络）；
         *                  取不到的项是 -1，界面显示 {@code --}。
         *                  ★ 与 fps 同源同批：**一轮 shell 命令取回全部**，
         *                  不再为每个指标各起一次进程（否则每秒 8 次 newProcess）。
         */
        void onSample(String pkg, String appName, float fps,
                      float refreshHz, String layer, SysMetrics.Snapshot m);

        /** 采样不可用（Shizuku 未就绪 / 没有前台应用）。reason 是给用户看的短句。 */
        void onUnavailable(String reason);
    }

    private static final CopyOnWriteArrayList<Callback> listeners =
            new CopyOnWriteArrayList<Callback>();

    /** 引用计数：>0 才真正在采样。 */
    private static int refs = 0;
    private static volatile boolean running = false;
    /** 采样线程代号：每起一条新线程 +1，旧线程靠它认出"我已经不是当前那条了"。 */
    private static volatile int generation = 0;
    private static Thread worker;
    private static Context appCtx;

    /**
     * 采样线程最近一次**跑完一轮**的时刻（{@code SystemClock.uptimeMillis()}）。
     *
     * <p>★ 为什么要有它：{@code exec()} 读 shell 输出是**阻塞**的，
     * 历史上没有超时 —— 一旦 {@code dumpsys} 卡住，采样线程就永远停在那次
     * {@code read()} 上：既没有回调、也没有异常，UI 上的数字**冻在最后一帧**，
     * 从外面完全看不出来（线程还"活着"）。有了心跳时间戳，
     * 悬浮窗的巡检就能发现"跑完一轮的间隔异常长"并重启线程。
     */
    private static volatile long lastTickMs = 0;

    /** 最近一次**成功**采样的时刻（uptimeMillis）。用来判断挂牌上的数字是否过期。 */
    private static volatile long lastSampleMs = 0;

    /**
     * 上一次"全量审计图层表"的时刻（uptimeMillis）。0 = 本会话还没审计过
     * （于是**本会话第一个样本就会审计** —— 这是"一进页面就自愈"的关键）。
     */
    private static volatile long lastFullAuditMs = 0;

    // ---- 系统指标的"上一次原始计数"（差值法）----
    // ★ 状态与分段解析都收在 SysMetrics.Accumulator 里（纯类，桌面 JVM 可断言）：
    //   这里只留一个实例。只有采样线程读写，不需要 volatile。
    private static final SysMetrics.Accumulator metricsAcc = new SysMetrics.Accumulator();

    /**
     * 最近一次成功采样（供"后加入的监听者"立刻显示，不用等下一秒）。
     * 为 null 表示还没采到过。
     */
    private static volatile Sample lastSample;

    public static final class Sample {
        public final String pkg, appName, layer;
        public final float fps, refreshHz;
        /** 同批取到的系统指标（可能为 null = 老调用方没传）。 */
        public final SysMetrics.Snapshot metrics;
        public Sample(String pkg, String appName, float fps, float refreshHz, String layer) {
            this(pkg, appName, fps, refreshHz, layer, null);
        }
        public Sample(String pkg, String appName, float fps, float refreshHz, String layer,
                      SysMetrics.Snapshot metrics) {
            this.pkg = pkg; this.appName = appName;
            this.fps = fps; this.refreshHz = refreshHz; this.layer = layer;
            this.metrics = metrics;
        }
    }

    /** 最近一次采样结果；没有则返回 null。 */
    public static Sample lastSample() {
        return lastSample;
    }

    /**
     * 上一次采样的图层基准 —— 用来算 {@code totalFrames} 差值。
     *
     * <p>★ 为什么必须自己存基准：{@code --timestats} 里的 {@code averageFPS}
     * 是**自 -enable 起的累计平均**，实测连续三次 dump 都是同一个值（62.500），
     * 不随时间变。要"实时"就得自己取窗口：相邻两次的 totalFrames 差值 / 间隔。
     */
    private static String lastLayer = null;
    private static long lastFrames = -1;
    private static long lastAtMs = 0;

    private static final Handler main = new Handler(Looper.getMainLooper());

    // ==================== 生命周期（引用计数）====================

    /** 声明"我要用数据"。第一次调用才真正开始采样。 */
    public static synchronized void acquire(Context ctx) {
        refs++;
        if (running) return;
        appCtx = ctx.getApplicationContext();
        startWorker();
    }

    /**
     * 声明"我不再需要数据"。计数归零才真正停止。
     *
     * <p>★★ 计数已经是 0 时**直接忽略** —— 这是本轮真机 bug 的最后一道防线。
     *
     * <p>{@code release()} 的语义是"归还**我自己那一份**"，而不是"停掉采样"。
     * 真机上出现过调用方多还一份（Activity 在 {@code onPause} 与
     * {@code onDestroy} 里各还一次，而它只在进过游戏助手页时才借过一份），
     * 于是悬浮窗那一份引用被凭空抵消 ⇒ 采样线程被停 ⇒ 桌宠挂牌上的数字
     * **永远冻在最后一帧**，用户只能"点开游戏助手"把它救回来。
     *
     * <p>调用方已经修好（{@code MainActivity} 用标志位严格配对），
     * 但这里再加一道防线：把"多还一份"从**停掉全世界的采样**降级成**无害空操作**。
     * 这类 bug 的症状（读数静默冻结）太难查，不能只靠调用方自觉。
     */
    public static synchronized void release() {
        if (refs <= 0) {
            android.util.Log.i(TAG, "release 被忽略：引用计数已是 0（调用方多还了一份）");
            return;
        }
        refs--;
        if (refs > 0) return;
        stopWorker();
    }

    /** 强制停止（服务销毁 / 功能关闭时用）。 */
    public static synchronized void stop() {
        refs = 0;
        stopWorker();
    }

    /**
     * 重启采样线程，**但保留引用计数**。
     *
     * <p>★ 与 {@link #stop()} 的区别：{@code stop()} 会把计数清零 ——
     * 如果只是"这一条线程卡住了"，用 {@code stop()} 会把别的使用者
     * （设置页 / 悬浮窗）一起踢掉，还会让计数与调用方记录的
     * {@code fpsAcquired} 对不上。重启只换线程，不动账本。
     *
     * <p>什么时候用：巡检发现"线程还活着但长时间跑不完一轮"（卡在阻塞的
     * shell 读取上）。旧线程会留在 {@code read()} 里，等它自己返回时
     * 因为 {@code running==false} 而退出，不会有两个线程长期并存。
     */
    public static synchronized void restart(Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
        if (refs <= 0) refs = 1;      // 调用方声明"我还在用"，别把账本记成没人要
        Thread old = worker;
        worker = null;
        running = false;
        if (old != null) {
            try { old.interrupt(); } catch (Throwable ignore) {}
        }
        startWorker();
    }

    /** 起线程（含基准重置）。调用方负责 {@code running} 与 {@code refs} 的语义。 */
    private static void startWorker() {
        running = true;
        // ★ 线程代号：每次起线程都换一个，循环里比对。
        //
        //   为什么必须有：restart() 会把 running 重新置真，而**卡死的那条旧线程**
        //   一旦从阻塞读里返回，会看到 running==true 就继续循环 —— 于是变成两条
        //   采样线程并行，每秒跑两条 shell 命令（真机上是白耗电 + 读数打架）。
        //   代号一变，旧线程下一轮就自己退出。
        final int myGen = ++generation;
        // 重置差值基准：上一次会话的 totalFrames 与当前无关，
        // 不清掉会算出一个荒谬的差值（跨会话的时间间隔对不上帧增量）。
        lastLayer = null;
        lastFrames = -1;
        lastAtMs = 0;
        lastActiveLayer = null;      // 快路径的已知层名也要清（上次会话的已失效）
        layerBaseline.clear();
        // ★ 上一次会话的读数一并作废：不然新加入的监听者会被 addListener
        //   "回放"一个上一轮的旧数字（挂牌/设置页会先闪一个过期值）。
        lastSample = null;
        lastSampleMs = 0;
        lastTickMs = android.os.SystemClock.uptimeMillis();   // 别让卡死看门狗误判
        // ★ 归零 ⇒ 本会话第一个样本就会做一次全量审计（图层表满了就地清掉）。
        //   真机实测：表满时"打开设置页 → 立刻恢复"正是靠这一次审计。
        lastFullAuditMs = 0;
        // 系统指标的差值基准也一并清：新会话的第一个样本只建基准、不出数
        // （跨会话算 CPU/网络差值会得出一个跟眼前无关的荒谬值）。
        metricsAcc.reset();
        // timestats 必须先 -enable 才会收集（-dump 才有内容）
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -enable");
                } catch (Throwable ignore) {}
            }
        }, "fps-enable").start();

        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (running && myGen == generation) {
                    long t0 = System.currentTimeMillis();
                    try {
                        if (appCtx != null) sampleOnce(appCtx);
                    } catch (Throwable t) {
                        postUnavailable("采样异常");
                    }
                    // ★ 心跳放在**所有出口之后**：正常返回、异常、提前 return 都算
                    //   "跑完一轮"。这样"心跳停了"只可能是真的卡在某次阻塞调用上。
                    lastTickMs = android.os.SystemClock.uptimeMillis();
                    long spent = System.currentTimeMillis() - t0;
                    long sleep = INTERVAL_MS - spent;
                    if (sleep > 0) {
                        try { Thread.sleep(sleep); } catch (InterruptedException e) { break; }
                    }
                }
            }
        }, "game-fps");
        worker.setDaemon(true);
        worker.start();
    }

    private static void stopWorker() {
        running = false;
        // 代号也推进一格：即使旧线程此刻正卡在阻塞读里，等它返回时也认得出
        // "我已经被换掉了"，不会因为 running 又被置真而接着跑（见 startWorker）。
        generation++;
        Thread w = worker;
        worker = null;
        if (w != null) w.interrupt();
    }

    public static boolean isRunning() {
        return running;
    }

    /** 当前引用计数（诊断用：真机日志里能一眼看出是谁把账记歪了）。 */
    public static synchronized int holderCount() {
        return refs;
    }

    /**
     * 距上一轮采样**跑完**过去了多少毫秒；从未跑过返回 -1。
     *
     * <p>正常值在 {@link #INTERVAL_MS} 附近（±一次 dumpsys 的耗时）。
     * 远大于它 ⇒ 线程卡在阻塞调用上（见 {@link #lastTickMs}）。
     */
    public static long millisSinceLastTick() {
        long t = lastTickMs;
        if (t == 0) return -1;
        return android.os.SystemClock.uptimeMillis() - t;
    }

    /**
     * 距最近一次**成功**采样过去了多少毫秒；从未采到返回 -1。
     *
     * <p>挂牌用它判断"手上这个数字是不是过期的"：过期就必须显示占位符，
     * 不能让用户看着一个冻住的旧数字以为游戏还是那个帧率。
     */
    public static long millisSinceLastSample() {
        long t = lastSampleMs;
        if (t == 0) return -1;
        return android.os.SystemClock.uptimeMillis() - t;
    }

    // ==================== 监听者 ====================

    public static void addListener(Callback cb) {
        if (cb == null || listeners.contains(cb)) return;
        listeners.add(cb);
        // 已有结果就立刻回放一次，避免新界面空等一秒
        Sample s = lastSample;
        if (s != null) cb.onSample(s.pkg, s.appName, s.fps, s.refreshHz, s.layer, s.metrics);
    }

    public static void removeListener(Callback cb) {
        listeners.remove(cb);
    }

    // ==================== 采样 ====================

    /** 单次采样。 */
    public static void sampleOnce(Context app) {
        // ★ 判据只看 Shizuku 状态，**不看取数服务的绑定状态**：
        //   主路径是 IShizukuService.newProcess（见 ShizukuBridge.exec），
        //   它不需要绑定。第一版要求"必须已绑定"才采样，于是在绑定不可靠的
        //   设备（ShizukuX 双服务端 bug）上读数永远出不来。
        if (ShizukuBridge.state() != ShizukuBridge.State.READY) {
            postUnavailable("Shizuku 未就绪");
            return;
        }
        final long t0 = android.os.SystemClock.uptimeMillis();

        // ---- 一条命令同时拿"前台应用" + "图层统计" ----
        //
        // ★★ 为什么要合并 + 用 grep 缩小：
        //   完整 timestats dump 有 **1.1 MB / 6000 行**（真机实测 126ms 才传完），
        //   每秒来一次会把采样周期从 1s 拖到 3s+，用户感觉"卡顿、要等"。
        //   稳态下我们已知活跃层名，用 grep 精确取它那一段即可（降到几十行）。
        //   两条命令并成一条也省一次进程启动（newProcess 每次都有开销）。
        String out = ShizukuBridge.exec(buildSampleCmd(lastActiveLayer));
        long execMs = android.os.SystemClock.uptimeMillis();
        if (out == null) {
            postUnavailable("读取帧数据失败");
            return;
        }
        int mi = out.indexOf(MARK);
        String fgPart = (mi >= 0) ? out.substring(0, mi) : out;
        // ★ 图层段必须在**系统指标段之前**截断：两者拼在同一条命令的输出里，
        //   不切开的话指标文本（/proc/net/dev、热区 name=value）会混进
        //   parseTimeStats 的输入。虽然当前不会误命中，但那是"碰巧安全"。
        int mc = (mi >= 0) ? out.indexOf(MARK_CPU) : -1;
        String layerPart = (mi >= 0) ? slice(out, mi + MARK.length(), mc) : "";
        String metPart = (mc >= 0) ? slice(out, mc, out.length()) : "";

        // ---- 前台应用：优先 dumpsys 的即时真值 ----
        String pkg = parseForegroundPkg(fgPart);
        if (pkg == null || pkg.isEmpty()) {
            MoodWatcher.Foreground mf = MoodWatcher.current(app);
            if (mf != null) {
                pkg = mf.pkg;
            } else {
                String raw = MoodWatcher.currentPackageRaw(app);
                if (raw == null || raw.isEmpty()) {
                    postUnavailable("未识别到前台应用");
                    return;
                }
                pkg = raw;
            }
        }
        boolean isSelf = pkg.equals(app.getPackageName());
        String appName = isSelf
                ? "本应用（设置页在前台，看不到其他应用）" : MoodWatcher.appName(pkg);

        java.util.List<FpsMath.LayerStat> cands =
                FpsMath.filterLayers(FpsMath.parseTimeStats(layerPart), pkg);

        long now = android.os.SystemClock.uptimeMillis();
        FpsMath.LayerStat active = pickGrowing(cands, now);
        float fps = -1f;
        boolean needFull = false;
        /** 本次全量 dump 里的图层条数（-1 = 本次没做全量 dump）。诊断用。 */
        int layerCount = -1;
        // ★ 定期全量审计：快路径只 grep 已知层那一段，**看不到整张表**，
        //   光靠它永远发现不了"表满了"（见下面的清理逻辑）。
        boolean audit = (now - lastFullAuditMs) > FULL_AUDIT_MS;

        if (active != null) {
            long[] prev = layerBaseline.get(active.layer);
            long dt = now - prev[1];
            if (dt > 0) fps = (active.totalFrames - prev[0]) * 1000f / dt;
        } else if (cands.isEmpty()) {
            // 快路径没命中（层名过期 / 刚换应用）→ 需要完整 dump 找活跃层
            needFull = true;
        } else {
            boolean anyBaseline = false;
            for (FpsMath.LayerStat st : cands) {
                if (layerBaseline.containsKey(st.layer)) { anyBaseline = true; break; }
            }
            if (!anyBaseline) needFull = true;   // 全是新层（刚切过来）→ 完整 dump
            // anyBaseline==true 且没层在涨 ⇒ 真静止，fps 保持 -1（UI 显示 0）
        }

        if (needFull || audit) {
            String full = ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -dump");
            lastFullAuditMs = android.os.SystemClock.uptimeMillis();
            // ★★ 图层表满了就必须清空重建 —— 这是"读数永久冻住"的真根因：
            //   TimeStats 的图层表上限 200 条，满了以后**新图层根本进不来**，
            //   表里只剩历史死层（计数冻结）⇒ 候选层全都不涨 ⇒ 帧率永远 -1
            //   ⇒ 界面永远"静止 / 0 FPS"，划屏也不涨，且**不会自己恢复**。
            //   真机实测：清空前 201 条且活层缺失；清空后 4 条、活层立刻出现。
            layerCount = FpsMath.countLayers(full);
            if (FpsMath.layerTableNeedsClear(full)) {
                android.util.Log.w(TAG, "图层表已 " + layerCount + " 条（上限 "
                        + FpsMath.LAYER_TABLE_CAP + "）→ 清空重建"
                        + "（否则新图层进不来、读数会永久冻住）");
                clearLayerTable();
                // 本轮**不出数**：基准刚被清掉，硬算会得出荒谬值。
                // 下一轮走完整 dump 重建基准，约 0.4 秒后恢复读数；
                // 挂牌有 5 秒新鲜度窗口，所以用户看不到闪断。
                return;
            }
            cands = FpsMath.filterLayers(FpsMath.parseTimeStats(full), pkg);
            long t2 = android.os.SystemClock.uptimeMillis();
            active = pickGrowing(cands, t2);
            if (active != null) {
                long[] prev = layerBaseline.get(active.layer);
                long dt = t2 - prev[1];
                if (dt > 0) fps = (active.totalFrames - prev[0]) * 1000f / dt;
            } else {
                // 仍没活跃层：先建基准，再快速补采一次（切应用后约 0.3~0.4s 出数）
                remember(cands, t2);
                try { Thread.sleep(FAST_REBASE_MS); } catch (InterruptedException ignore) {}
                java.util.List<FpsMath.LayerStat> c2 = FpsMath.filterLayers(FpsMath.parseTimeStats(
                        ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -dump")), pkg);
                long t3 = android.os.SystemClock.uptimeMillis();
                active = pickGrowing(c2, t3);
                if (active != null) {
                    long[] prev = layerBaseline.get(active.layer);
                    long dt = t3 - prev[1];
                    if (dt > 0) fps = (active.totalFrames - prev[0]) * 1000f / dt;
                }
                cands = c2;
                t2 = t3;
            }
            now = t2;
        }

        if (cands.isEmpty()) {
            postUnavailable("该应用没有可采样的绘制层");
            return;
        }
        // ★★ 关键判据：**选"正在增长"的层**，而不是"帧数最大"的层。
        //
        //   真机实测教训：--timestats 保留**所有历史图层** —— 我们 App 有 144 个，
        //   名字是 ...MainActivity#2576 / #2470 / #2417 ...（Activity 每重建一次留一个）。
        //   "帧数最大"的恰恰是早已死掉的旧层（计数冻结在 13464），差值恒为 0、
        //   帧率永远 -1、挂牌一直显示 "--"。
        //   只有"两次采样之间计数在涨"的层才代表当前前台应用在刷。
        //
        //   这个判据还顺带解决了用户反馈的"切应用要等"：
        //   切到**之前见过**的应用时，上一层基准还在表里，立刻就能算出帧率（零等待）。
        remember(cands, now);
        String layerName = (active != null) ? active.layer : cands.get(0).layer;
        // ★★ 快路径的层名**只认"真的看到在涨"的那个**。
        //
        //   真机实测踩过：这里原来写 `lastActiveLayer = layerName`，而 active==null 时
        //   layerName 取的是 cands.get(0) —— filterLayers 按帧数降序，
        //   于是它正是那个**死掉的旧层**（帧数冻结在 13464）。
        //   快路径接着一直查这个不动的层，永远不涨，帧率永远 -1，
        //   而且因为"有基准"就 never 触发全量 dump，彻底卡死。
        //   现在：只在 active!=null 时更新；连续两次没涨就丢掉快路径、下次全量重找。
        if (active != null) {
            lastActiveLayer = active.layer;
            fastMiss = 0;
        } else {
            fastMiss++;
            if (fastMiss >= 2) {
                lastActiveLayer = null;
                fastMiss = 0;
            }
        }
        if (layerBaseline.size() > 400) layerBaseline.clear();

        // 屏幕刷新率：走 WindowManager（DisplayMetrics 里**没有** refreshRate 字段 ——
        // 第一版写成 getDisplayMetrics().refreshRate，编译报"找不到符号"）
        float refreshHz = 0f;
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    app.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                refreshHz = wm.getDefaultDisplay().getRefreshRate();
            }
        } catch (Throwable ignore) {}

        // ---- 系统指标（与帧率同一批取回；差值法要上一次的原始计数）----
        SysMetrics.Snapshot met = collectMetrics(app, metPart, fps);

        postSample(pkg, appName, fps, refreshHz, layerName, met);
        android.util.Log.i(TAG, "  detail layer=" + layerName
                + " active=" + (active != null)
                + " cands=" + cands.size()
                + " tf=" + (active != null ? active.totalFrames
                        : (cands.isEmpty() ? -1 : cands.get(0).totalFrames))
                + " base=" + layerBaseline.size()
                + " needFull=" + needFull
                + " audit=" + audit
                + " layers=" + layerCount
                + " cpu=" + met.cpu + " gpu=" + met.gpu + " ram=" + met.ramUsedKb
                + " temp=" + met.temp + " batt=" + met.batt + " pw=" + met.power
                + " net=" + met.net
                + " execMs=" + (execMs - t0)
                + " totalMs=" + (android.os.SystemClock.uptimeMillis() - t0)
                + " outLen=" + out.length());
    }

    /**
     * 从同一批 shell 输出里取系统指标 + Java 侧补 RAM/电池/功耗。
     *
     * <p>★ 取不到的项一律留 -1（界面显示 {@code --}）：
     * 这块最容易"看起来有数其实是假的"——例如 GPU 空闲时 {@code gpubusy} 返回
     * {@code "0 0"}，若当成 0% 占用就会一直显示一个假的 0%。
     */
    private static SysMetrics.Snapshot collectMetrics(Context app, String metPart, float fps) {
        SysMetrics.Snapshot s = new SysMetrics.Snapshot();
        s.fps = fps;
        try {
            // ★ 差值法的全部状态 + 分段解析都在 SysMetrics.Accumulator（**纯类**，
            //   桌面 JVM 上有断言）。这里只负责"喂进去"。
            metricsAcc.update(metPart, android.os.SystemClock.uptimeMillis(), s);
        } catch (Throwable ignore) {}
        fillAndroidMetrics(app, s);
        return s;
    }

    /**
     * Java 侧能拿到的指标：RAM（MemoryInfo）、电池电量与功耗（粘性广播 + BatteryManager）。
     *
     * <p>这几项**不需要 shell 权限**，所以即使 Shizuku 那条路出问题也照样有数。
     */
    private static void fillAndroidMetrics(Context app, SysMetrics.Snapshot s) {
        if (app == null) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi =
                        new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.totalMem > 0) {
                    s.ramTotalKb = mi.totalMem / 1024L;
                    s.ramUsedKb = (mi.totalMem - mi.availMem) / 1024L;
                }
            }
        } catch (Throwable ignore) {}
        try {
            // registerReceiver(null, filter) = 只读粘性广播，不注册接收者（不会泄漏）
            android.content.Intent it = app.registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (it != null) {
                int lvl = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (lvl >= 0 && scale > 0) s.batt = lvl * 100f / scale;
                int volt = it.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, -1);
                int curr = 0;
                try {
                    android.os.BatteryManager bm = (android.os.BatteryManager)
                            app.getSystemService(Context.BATTERY_SERVICE);
                    if (bm != null) {
                        curr = bm.getIntProperty(
                                android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                    }
                } catch (Throwable ignore) {}
                // Integer.MIN_VALUE = 该机型不支持这个属性（不是"0 电流"）
                if (curr == Integer.MIN_VALUE) curr = 0;
                if (volt > 0 && curr != 0) s.power = SysMetrics.powerW(volt, curr);
                // 温度兜底：热区读不到（没 shell 权限）时用电池温度，总比显示 -- 有用
                if (s.temp < 0f) {
                    int t = it.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (t > 0) s.temp = t / 10f;
                }
            }
        } catch (Throwable ignore) {}
    }

    /** 前台段与图层段的分隔标记。 */
    private static final String MARK = "===DSPMARK===";

    // ---- 系统指标段的分隔标记：**定义在 SysMetrics 里**（拼命令与解析两处共用）----
    //   以前这里各存一份、解析那边再写一遍字符串，改一处就会静默错位。
    private static final String MARK_CPU = SysMetrics.MARK_CPU;
    private static final String MARK_GPU = SysMetrics.MARK_GPU;
    private static final String MARK_NET = SysMetrics.MARK_NET;
    private static final String MARK_TMP = SysMetrics.MARK_TMP;

    /** GPU 占用（Adreno/kgsl；读不到就显示 --，不猜）。 */
    private static final String GPU_BUSY_PATH = "/sys/class/kgsl/kgsl-3d0/gpubusy";

    /** 上一次选中的活跃层名 —— 稳态下用它把 dump 缩到几十行。 */
    private static volatile String lastActiveLayer = null;

    /** 快路径连续没找到"在涨的层"的次数；达阈值就放弃快路径、下次全量重找。 */
    private static int fastMiss = 0;

    /** 拼采样命令：前台 + 图层统计（稳态只 grep 已知层，省 1MB 传输）+ 系统指标。 */
    private static String buildSampleCmd(String knownLayer) {
        StringBuilder sb = new StringBuilder();
        sb.append("dumpsys activity activities | grep -m1 topResumedActivity; echo ")
          .append(MARK).append("; dumpsys SurfaceFlinger --timestats -dump");
        if (knownLayer != null && knownLayer.length() > 0) {
            // -F 固定串（层名里有 [ ] ( ) # 这些正则元字符）
            sb.append(" | grep -F -A60 'layerName = ").append(knownLayer).append("'");
        }
        // ---- 系统指标：与帧率**同一趟命令**取回（省 7 次 newProcess 进程开销）----
        //
        // ★ 每段用独立标记隔开，解析器才不会互相误吃：
        //   /proc/net/dev 的行里也有"两个数字"，不隔开会被 gpubusy 的解析器当成 GPU 计数。
        // ★ 全部带 2>/dev/null：某些机型没有 kgsl 节点、某些热区不可读，
        //   报错文本混进输出会污染解析（宁可这一段为空 → 显示 --）。
        sb.append("; echo ").append(MARK_CPU)
          .append("; cat /proc/stat")
          .append("; echo ").append(MARK_GPU)
          .append("; cat ").append(GPU_BUSY_PATH).append(" 2>/dev/null")
          .append("; echo ").append(MARK_NET)
          .append("; cat /proc/net/dev 2>/dev/null")
          .append("; echo ").append(MARK_TMP)
          .append("; for z in /sys/class/thermal/thermal_zone*; do ")
          .append("echo \"$(cat $z/type 2>/dev/null)=$(cat $z/temp 2>/dev/null)\"; done");
        return sb.toString();
    }

    /** 取 {@code [from, to)} 之间的片段（标记位置无效时返回空串）。 */
    private static String slice(String s, int from, int to) {
        if (s == null || from < 0) return "";
        if (to < 0 || to < from) to = s.length();
        if (from >= s.length()) return "";
        return s.substring(from, to);
    }

    /** 从 dumpsys 输出里解析前台包名。 */
    private static String parseForegroundPkg(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\su\\d+\\s+([a-zA-Z0-9_.]+)/").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** 每层上一次的 (totalFrames, 采样时刻)。用于"谁在涨"和"切换后立刻出数"。 */
    private static final java.util.HashMap<String, long[]> layerBaseline =
            new java.util.HashMap<String, long[]>();

    private static void remember(java.util.List<FpsMath.LayerStat> cands, long atMs) {
        for (FpsMath.LayerStat st : cands) {
            layerBaseline.put(st.layer, new long[]{st.totalFrames, atMs});
        }
    }

    /**
     * 清空 SurfaceFlinger 的图层统计表，并作废我们这边的全部基准。
     *
     * <p>实测（Android 17 真机）：{@code --timestats -clear} **不影响采集开关** ——
     * clear 之后计数继续涨（106 → 476 帧），所以不需要再补一次 {@code -enable}。
     *
     * <p>★ 清表后**必须**清基准：表里那些层名（{@code ...#998}）会连同记录一起消失，
     * 留着旧基准只会在新层上算出荒谬的差值。
     */
    private static void clearLayerTable() {
        try {
            ShizukuBridge.exec("dumpsys SurfaceFlinger --timestats -clear");
        } catch (Throwable ignore) {}
        layerBaseline.clear();
        lastActiveLayer = null;
        fastMiss = 0;
    }

    /** 在候选层里找"两次采样之间帧数涨得最多"的那个（= 当前在刷的层）。 */
    private static FpsMath.LayerStat pickGrowing(java.util.List<FpsMath.LayerStat> cands,
                                                 long now) {
        FpsMath.LayerStat best = null;
        long bestDelta = 0;
        for (FpsMath.LayerStat st : cands) {
            long[] prev = layerBaseline.get(st.layer);
            if (prev == null) continue;
            if (now - prev[1] <= 0) continue;
            long d = st.totalFrames - prev[0];
            if (d > bestDelta) { bestDelta = d; best = st; }
        }
        return best;
    }

    private static void postSample(final String pkg, final String appName,
                                   final float fps, final float refreshHz,
                                   final String layer, final SysMetrics.Snapshot met) {
        android.util.Log.i(TAG, "sample pkg=" + pkg + " fps=" + fps
                + " layer=" + layer);
        // ★ 记"成功采样的时刻"：挂牌靠它判断手上数字是否过期（见 millisSinceLastSample）。
        lastSampleMs = android.os.SystemClock.uptimeMillis();
        lastSample = new Sample(pkg, appName, fps, refreshHz, layer, met);
        main.post(new Runnable() {
            @Override public void run() {
                for (Callback cb : listeners) {
                    try {
                        cb.onSample(pkg, appName, fps, refreshHz, layer, met);
                    } catch (Throwable ignore) {}
                }
            }
        });
    }

    private static void postUnavailable(final String reason) {
        android.util.Log.i(TAG, "unavailable: " + reason);
        main.post(new Runnable() {
            @Override public void run() {
                for (Callback cb : listeners) {
                    try {
                        cb.onUnavailable(reason);
                    } catch (Throwable ignore) {}
                }
            }
        });
    }
}
