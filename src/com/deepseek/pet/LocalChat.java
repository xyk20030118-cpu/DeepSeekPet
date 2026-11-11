package com.deepseek.pet;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.List;

/**
 * 本地模型这个**对话后端**（轮次64 第三轮 · 对话接线）。
 *
 * <p>用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
 * 第一轮给了引擎（{@link LocalLlm}：load / generate / unload），第二轮给了模型文件
 * （{@code ModelStore} / {@code ModelImporter}），这一层把两者接起来，回答两个问题：
 * <b>什么时候把模型载进内存</b>、<b>一句话怎么变成一次本地生成</b>。
 *
 * <h3>★★ 本类里**一个 HTTP 符号都没有**（有断言钉着）</h3>
 * 这里没有 {@code ApiClient}、没有 {@code Authorization}、没有 URL、**连 API Key 都不接**
 * （{@link #answer} 的签名里根本没有 {@code apiKey}）。这是刻意的，也是本轮最关键的结构决定：
 * 在线那条链是 HTTP + JSON（{@code ApiClient.chatUrl()} / {@code Authorization} / {@code max_tokens}），
 * 本地推理没有 URL、没有状态码、没有 Key —— <b>把本地推理塞进 ApiClient 只会得到一堆分支</b>。
 * 分流放在调用方（{@link ChatRouter}），本类只讲"本机引擎"这一件事。
 *
 * <h3>★ 加载策略：第一次用时载一次，之后**常驻**；换模型才卸</h3>
 * {@code LocalLlm.load} 是几百 MB 的 mmap + 图构建，手机上要**几秒到十几秒**。
 * 所以：
 * <ul>
 *   <li>**绝不**在 UI 线程 load（会冻住界面，用户以为死机）；</li>
 *   <li>**绝不**每发一句话就 load/unload（每句话都干等十几秒）；</li>
 *   <li>用 {@link #loadedPath} 记住"内存里现在载的是哪个文件"：
 *       下次要生成时，只要还是同一个文件就直接用；换了文件才先卸再载。</li>
 * </ul>
 * <p>★★ 为什么还要自己记 {@code loadedPath}（引擎不是有 {@code isLoaded()} 吗）：
 * {@code isLoaded()} 只回答"载没载"，**说不出载的是哪一个**。
 * 用户换了当前模型（或删了重导）之后，光看 {@code isLoaded()} 会以为"已经好了"，
 * 于是一直拿**旧模型**回答新问题 —— 症状是"换了模型没效果"，最难查的那一类。
 * 记了路径之后，这条判断就有了唯一依据，也才有断言能钉住它。
 *
 * <h3>★ 调用纪律（第一轮定的，这里照做）</h3>
 * <ul>
 *   <li>{@link #ensureLoaded} 与 {@link #answer} 都是**同步阻塞**的 ⇒ 只能在后台线程调；</li>
 *   <li>{@code sink} 回调里**只做 append**，绝不回来调 load/unload/generate（同一把锁 = 自锁）；</li>
 *   <li>{@link LocalLlm#unload()} 会先发停止信号 ⇒ 换模型/删模型时在后台线程调它不会卡 UI。</li>
 * </ul>
 */
public final class LocalChat {

    private static final String TAG = "DSPLocalChat";

    /**
     * 对话单次生成的 token 上限。
     *
     * <p>★ 刻意比引擎默认（256）小：手机小模型大约每秒十几到几十个 token，
     * 256 个 token 意味着"最坏情况要等十几秒"，而桌宠气泡要的是**一句话**。
     * 风格要求已经写明"40 字以内"，128 有充足余量，还能在最坏情况下兜住等待时间。
     */
    public static final int DIALOG_MAX_TOKENS = 128;

    /** 内存里**现在载着的**模型文件绝对路径；null = 没载任何东西。 */
    private static String loadedPath = null;

    /**
     * ★ 只用来串行化"载入"这个动作，**不是**引擎那把锁。
     *
     * <p>为什么需要：载入要几秒，而"用户连点两次发送"或者"主动搭话刚好撞上问一句"
     * 都会各自起一个后台线程 —— 两个线程同时 {@code load} 会一个把另一个刚载好的卸掉
     * （{@code nativeLoad} 会先卸旧的）⇒ 白载一遍，还可能让前一个调用拿到"载入失败"。
     * 拿这把锁之后第二个线程会等第一个载完，然后发现路径已经对了，直接返回。
     */
    private static final Object LOAD_LOCK = new Object();

    private LocalChat() {}

    /** 一次本地生成的结果（本地侧自己的类型 —— 不借用在线那条链的任何东西）。 */    public static final class Reply {
        public boolean ok;
        /** 生成出来的文本（已清理）；失败时为空串 */
        public String text = "";
        /** 失败原因（中文、能直接给用户看）；成功时为 null */
        public String error = null;
        /** 本次用的对话模板 id（日志/诊断用） */
        public String template = LocalPrompt.T_CHATML;

        static Reply fail(String why) {
            Reply r = new Reply();
            r.ok = false;
            r.error = why;
            return r;
        }
    }

    // ------------------------------------------------------------------ 流式：token → 界面

    /** 界面侧只关心"现在已经攒到多少字"（拿到就把这段画出来）。 */
    public interface PieceSink {
        void onPiece(String soFar);
    }

    /**
     * 把引擎的流式回调接到界面上：**攒起来 + 按 {@link LocalPrompt#FLUSH_MS} 节流**。
     *
     * <p>★★ 两条纪律都在这里落实，而且**只有这一处**（两个界面共用）：
     * <ol>
     *   <li>回调里**只做 append** —— 绝不回头调 load / unload / generate（同一把锁 = 自锁）；</li>
     *   <li>刷新的**频率本身就是一种资源**：小模型每秒十几到几十个 token，
     *       一个 token 刷一次就是每秒几十次 measure/layout（第二轮那条教训）。
     *       250 ms 一次既看得出"在出字"，又不会把 UI 线程压住。</li>
     * </ol>
     * ★ 返回的 sink 一律返回 true（不主动打断生成）——
     * "用户一说话桌宠就闭嘴"那是调用方自己的事，不该藏在节流器里。
     */
    public static LocalLlm.TokenSink throttledSink(final PieceSink out) {
        return new LocalLlm.TokenSink() {
            private final StringBuilder acc = new StringBuilder();
            private long lastMs = 0;

            @Override public boolean onToken(String piece) {
                if (piece == null) return true;
                acc.append(piece);                       // ★★ 只做 append
                if (out == null) return true;
                final long now = System.currentTimeMillis();
                if (!LocalPrompt.shouldFlush(lastMs, now, LocalPrompt.FLUSH_MS)) return true;
                lastMs = now;
                out.onPiece(acc.toString());
                return true;
            }
        };
    }

    // ------------------------------------------------------------------ 状态

    /** 内存里载着哪个文件（绝对路径）；null = 没载。 */
    public static String loadedPath() { return loadedPath; }

    /**
     * 是否**需要**先载入（便宜的判断：不读盘、不建图）。
     *
     * <p>调用方拿它决定"要不要先给用户一个『正在载入』的等待反馈"——
     * 首次使用本地模型会慢好几秒，**没有反馈就是静默卡住**。
     */
    public static boolean needsLoad(Context ctx) {
        final File f = ModelStore.currentFile(ctx);
        if (f == null) return false;                 // 没模型不算"要载入"，那是引导问题
        if (!LocalLlm.isLoaded()) return true;
        return !f.getAbsolutePath().equals(loadedPath);
    }

    /**
     * 忘掉"内存里载着谁"。
     *
     * <p>给**别处调用了 {@code LocalLlm.unload()}** 的路径用（换模型 / 删模型），
     * 让下一次 {@link #ensureLoaded} 一定重新载。不调它也不会错（{@code isLoaded()} 会兜住），
     * 但会多绕一圈"以为还载着"的判断。
     */
    public static void forget() {
        loadedPath = null;
    }

    /**
     * ★★★ 轮次69 新增：**明确卸载**内存里的模型（用户点名要的"卸载功能"）。
     *
     * <p>与 {@link #forget()} 的区别：{@code forget()} 只忘账本、**不动引擎**；
     * 这个方法是**真的**把引擎里那个模型卸掉、把 1 GB 量级的内存交还（{@code llama_free}
     * + {@code llama_model_free} + 我们钉住的那份映射），**文件一个字节都不动**。
     *
     * <p>★★ 三条纪律（都照第一轮定的调用纪律）：
     * <ol>
     *   <li>{@link LocalLlm#unload()} 会**先发停止信号**再等锁 ⇒ 万一此刻正在生成，
     *       它也能很快收手；★ 但它仍然是阻塞的 ⇒ **UI 不许在主线程调**（放后台线程，
     *       一行 {@code Toast} 都不许等它）；</li>
     *   <li>卸完必须 {@link #forget()}：不然下一次 {@code ensureLoaded} 会以为"还载着"，
     *       症状是"卸载之后第一次对话拿不到模型"；</li>
     *   <li>★ 幂等：没载任何东西时调它是**安全的空操作**，并且要如实告诉用户
     *       "本来就没有载入"（不许静默、也不许假装卸掉了什么）。</li>
     * </ol>
     *
     * @return 被卸掉的那个**文件名**；{@code null} = 本来就没载入（或者名字已经不知道了）。
     *         ★ 界面要不要说"内存里本来就没有载入"，请用它自己在**调这个方法之前**读到的
     *         {@code LocalLlm.isLoaded()} 判断（这里返回 null 有两种含义，不适合当唯一判据）。
     */
    public static String unloadNow() {
        final boolean wasLoaded = LocalLlm.isLoaded();
        final String wasPath = loadedPath;
        String name = null;
        if (wasPath != null) {
            try { name = new File(wasPath).getName(); } catch (Throwable ignore) {}
        }
        try {
            LocalLlm.unload();
        } catch (Throwable t) {
            // ★ 卸载失败也要留证据（否则界面上就是"点了没反应"）
            Log.e(TAG, "★★ 卸载本地模型时抛异常（已兜住，界面会如实说明）：" + t);
        }
        forget();
        Log.i(TAG, LocalModelMenu.unloadLog(wasLoaded, name));
        return name;
    }

    // ------------------------------------------------------------------ 载入

    /**
     * 确保"当前模型"已经载好（**同步阻塞**，必须后台线程）。
     *
     * <p>已经载着同一个文件 ⇒ 立刻返回 true（这就是"常驻"）。
     * 载着**别的**文件 ⇒ 先卸再载。
     */
    public static Reply ensureLoaded(Context ctx) {
        if (ctx == null) return Reply.fail(guideNoModel());
        if (!LocalLlm.isAvailable()) return Reply.fail(guideEngineMissing());
        final File f = ModelStore.currentFile(ctx);
        if (f == null) return Reply.fail(guideNoModel());

        synchronized (LOAD_LOCK) {
            final String path = f.getAbsolutePath();
            if (LocalLlm.isLoaded() && path.equals(loadedPath)) {
                Reply r = new Reply();
                r.ok = true;
                return r;
            }
            // ★ 换了模型：先把旧的放掉再载新的。放在**后台线程**里，且不在 sink 回调里，
            //   所以不会自锁（第一轮的纪律）。
            if (LocalLlm.isLoaded()) {
                Log.i(TAG, "换了模型，先卸掉内存里的旧模型：" + loadedPath);
                try { LocalLlm.unload(); } catch (Throwable ignore) {}
                loadedPath = null;
            }
            final long t0 = System.currentTimeMillis();
            // ★★★ 轮次74：**自动**档在这里落地 ——
            //   ① 先探测硬件（GPU 设备 + 可用内存 + GGUF 层数）；
            //   ② 由 GpuPlan（纯类）算出（要不要 GPU, 放几层）；
            //   ③ 载入；GPU 失败且档位是「自动」⇒ **自动降级到纯 CPU 重载一次**（会记下来）。
            //   ★ 用户原话：「运行时自动检测硬件 → 动态计算 -(n-gpu-layers) → 自动降级兜底
            //     不用用户手动调参数。」
            //   ★★ 「开」仍然是手动对照档：只走 GPU、失败不回落（给要 A/B 的人）。
            final String gpuMode = Prefs.localGpuMode(ctx);
            final String tune = Prefs.localTune(ctx);
            final LoadResult lr = loadPlanned(ctx, f, gpuMode, tune);
            final long ms = System.currentTimeMillis() - t0;
            if (!lr.ok) {
                loadedPath = null;
                final String why = lr.why;
                Log.e(TAG, "本地模型载入失败（耗时 " + ms + " ms / GPU 档位 " + gpuMode + "）：" + why);
                if (GpuMode.isOn(gpuMode) && GpuFailNotice.looksLikeGpuFailure(why)) {
                    // ★★ 手动「开」失败 ⇒ 不许静默：记下来（气泡/对话框/设置页都要用它）+ 打完整原因。
                    GpuFailNotice.report(why);
                    Log.e(TAG, "★★ 开 GPU 载入失败（你选的是「开」：只走 GPU、不回落 CPU）：" + why);
                    return Reply.fail(guideGpuFailed(why));
                }
                return Reply.fail(guideLoadFailed(why));
            }
            // ★ 成功 ⇒ 上一次那条"强制失败"的提示收掉（别让一条已经过期的警告一直挂着）。
            GpuFailNotice.clear();
            loadedPath = path;
            final GpuPlan.Plan plan = lr.plan;
            Log.i(TAG, "本地模型已载入并常驻：" + f.getName() + " / 耗时 " + ms + " ms"
                    + " / 线程 " + LocalLlm.defaultThreads() + " / ctx " + LocalLlm.DEFAULT_CTX
                    + " / GPU 档位 " + gpuMode
                    + "（计划 " + (plan == null ? "?" : plan.tier)
                    + "，放 " + (plan == null ? 0 : plan.ngl) + " 层"
                    + (lr.fellBack ? "；★ 已自动降级到纯 CPU" : "") + "）");
            Reply r = new Reply();
            r.ok = true;
            return r;
        }
    }

    // ------------------------------------------------------------------ 轮次74：自动计划 + 自动降级

    /** 一次"按计划载入"的结果（含自动降级与原因）。 */
    private static final class LoadResult {
        boolean ok;
        String why = "";
        GpuPlan.Plan plan;
        boolean fellBack;
    }

    /**
     * 档位 + 硬件事实 → 计划（**纯函数**，便于断言与日志）。
     *
     * <ul>
     *   <li>关 ⇒ 纯 CPU（手动）；</li>
     *   <li>开 ⇒ 强制全部层（手动，失败不回落）；</li>
     *   <li>自动 ⇒ {@link GpuPlan#decide}（硬件 + 内存 + 层数）。</li>
     * </ul>
     */
    static GpuPlan.Plan resolvePlan(String gpuMode, GpuPlan.Facts facts) {
        final GpuPlan.Plan p;
        if (GpuMode.isOff(gpuMode)) {
            p = new GpuPlan.Plan();
            p.auto = false;
            p.gpu = false;
            p.ngl = 0;
            p.tier = "manual-off";
            p.reason = "你选了「关」（纯 CPU）";
        } else if (GpuMode.isOn(gpuMode)) {
            p = new GpuPlan.Plan();
            p.auto = false;
            p.gpu = true;
            p.ngl = LocalLlm.GPU_LAYERS_ALL;
            p.tier = "manual-on";
            p.reason = "你选了「开」（强制用 GPU，失败不回落）";
        } else {
            p = GpuPlan.decide(facts);
            p.auto = true;
        }
        return p;
    }

    /**
     * ★★★ 轮次74：**按计划载入 + 自动降级**（唯一的载入实现）。
     *
     * <p>自动档且这一计划要用 GPU、载入又因为 **GPU 原因**失败时：
     * 卸掉 → {@link GpuPlan#degrade} → 纯 CPU **重载一次**，并记下原因
     * （{@link LocalHardware#noteFallback}，面板与自检报告都会显示）。
     *
     * <p>★ 只降一次（GPU → CPU），不会来回弹；★ 手动「开」**不降级**（对照组语义）。
     * ★ 调用者必须已持有 {@link #LOAD_LOCK}。
     */
    private static LoadResult loadPlanned(Context ctx, File f, String gpuMode, String tune) {
        final LoadResult r = new LoadResult();
        final GpuPlan.Facts facts = LocalHardware.facts(ctx, f);
        GpuPlan.Plan plan = resolvePlan(gpuMode, facts);
        LocalHardware.remember(facts, plan);
        r.plan = plan;

        final String path = f.getAbsolutePath();
        final int threads = LocalLlm.defaultThreads();
        boolean ok = LocalLlm.load(path, threads, LocalLlm.DEFAULT_CTX,
                plan.gpu ? plan.ngl : 0,
                plan.gpu ? GpuMode.CODE_ON : GpuMode.CODE_OFF, tune);

        if (!ok && GpuMode.isAuto(gpuMode) && plan.gpu) {
            final String why0 = LocalLlm.lastError();
            if (GpuPlan.looksLikeGpuFailure(why0)) {
                Log.w(TAG, "★ 自动降级：GPU 载入失败 ⇒ 改用纯 CPU 重载一次：" + why0);
                try { LocalLlm.unload(); } catch (Throwable ignore) {}
                plan = GpuPlan.degrade(plan);
                LocalHardware.remember(facts, plan);
                LocalHardware.noteFallback(why0);
                r.plan = plan;
                r.fellBack = true;
                ok = LocalLlm.load(path, threads, LocalLlm.DEFAULT_CTX, 0, GpuMode.CODE_OFF, tune);
            }
        }
        r.ok = ok;
        r.why = ok ? "" : LocalLlm.lastError();
        return r;
    }

    /**
     * ★★★ 轮次74：**生成阶段**的自动降级（真机上 GPU 就炸在这一步 ——
     * `vk::Device::createComputePipeline: ErrorUnknown`）。
     *
     * <p>卸掉 → 纯 CPU 重载 → 调用方**重跑一次生成**。返回 false = 救不回来。
     */
    private static boolean reloadCpu(Context ctx) {
        final File f = ModelStore.currentFile(ctx);
        if (f == null) return false;
        synchronized (LOAD_LOCK) {
            try { LocalLlm.unload(); } catch (Throwable ignore) {}
            forget();
            final boolean ok = LocalLlm.load(f.getAbsolutePath(), LocalLlm.defaultThreads(),
                    LocalLlm.DEFAULT_CTX, 0, GpuMode.CODE_OFF, Prefs.localTune(ctx));
            if (ok) loadedPath = f.getAbsolutePath();
            Log.i(TAG, "★ 自动降级：已按纯 CPU 重新载入 ⇒ " + ok);
            return ok;
        }
    }

    /**
     * 自检报告里的"自动计划"两行（轮次74）—— 让报告能回答
     * "**它到底自动决定了什么、为什么**"。
     */
    private static String autoLines(LoadResult lr, boolean fellBack) {
        final GpuPlan.Plan p = lr != null ? lr.plan : LocalHardware.lastPlan();
        return GpuPlan.reportLines(LocalHardware.lastFacts(), p, fellBack);
    }

    // ------------------------------------------------------------------ 🧪 本地推理自检（轮次71）

    /**
     * 自检的结果（给界面用）。
     * <p>★ 只带两样东西：一句**结论**（标题栏 / Toast）与**可复制的整段报告**。
     */
    public static final class Check {
        public boolean ok;
        public String title = "";
        public String report = "";
    }

    /**
     * ★★★ 轮次71：「🧪 本地推理自检」—— **用当前设置载入当前模型 + 跑一句固定短提示**，
     * 把这一次的全部读数整理成一屏可复制的报告。
     *
     * <p>★★★ 为什么要有它（这一轮最要紧的一件事）：轮次66~70 每一轮都卡在
     * "这台手机的 GPU 到底行不行、哪个旋钮有用 —— **没有真机读数**"上。
     * 开发机 {@code adb devices} 从头到尾是空的，而**真机是用户自己的日常机**：
     * 他不会连电脑、更不会去挂 logcat。⇒ 把答案要用的每一样东西做成他手机上的一颗按钮。
     *
     * <p>三条纪律（都照本类第一轮定的调用纪律）：
     * <ol>
     *   <li>**同步阻塞**（载入几秒 + 生成几秒）⇒ 只能在后台线程调；
     *       ★ 取消方式就是 {@link LocalLlm#unload()}（它会先发停止信号，
     *       正在跑的生成下一轮就收手）—— 界面那颗「取消」按钮走的就是它；</li>
     *   <li>**先放掉内存里的模型**再载：否则"当前设置"根本没被用上
     *       （GPU 层数 / 调优旋钮都只在载入时给一次）⇒ 自检结果就是假的；</li>
     *   <li>**绝不动文件**：自检只载入 / 生成 / 读读数，不碰 {@code ModelStore}。</li>
     * </ol>
     *
     * <p>★ 温度用 **0（贪心）**：两次自检之间必须可比，随机采样会让数字抖。
     *
     * @return 永远不会是 null；失败时 {@code report} 里也有可读的结论（不是"失败了"三个字）。
     */
    public static Check selfTest(Context ctx) {
        final Check c = new Check();
        if (ctx == null) {
            c.report = "自检没跑起来：拿不到 Context（内部错误）。";
            c.title = "自检没跑起来";
            return c;
        }
        if (!LocalLlm.isAvailable()) {
            c.report = LocalSelfTest.compose(Prefs.localGpuMode(ctx), Prefs.localTune(ctx),
                    null, null, guideEngineMissing());
            c.title = "自检没跑起来：这台设备的本地推理引擎没装上";
            return c;
        }
        final File f = ModelStore.currentFile(ctx);
        if (f == null) {
            c.report = LocalSelfTest.compose(Prefs.localGpuMode(ctx), Prefs.localTune(ctx),
                    null, null, guideNoModel());
            c.title = "自检没跑起来：还没导入本地模型";
            return c;
        }

        final String gpuMode = Prefs.localGpuMode(ctx);
        final String tune = Prefs.localTune(ctx);
        synchronized (LOAD_LOCK) {
            // ① 先把内存里的模型放掉：自检的语义是"**按当前设置重新载入一次**"。
            if (LocalLlm.isLoaded()) {
                Log.i(TAG, "自检：先放掉内存里的模型，按当前设置重新载入");
                try { LocalLlm.unload(); } catch (Throwable ignore) {}
            }
            forget();
            GpuFailNotice.clear();

            // ② ★★★ 轮次74：按**自动计划**载入（硬件 → 层数 → 失败自动降级）
            final long t0 = System.currentTimeMillis();
            final LoadResult lr = loadPlanned(ctx, f, gpuMode, tune);
            Log.i(TAG, LocalSelfTest.logStart(f.getName(), gpuMode, tune,
                    lr.plan == null ? 0 : lr.plan.ngl));
            if (!lr.ok) {
                final String why = lr.why;
                if (GpuMode.isOn(gpuMode) && GpuFailNotice.looksLikeGpuFailure(why)) {
                    GpuFailNotice.report(why);
                }
                Log.e(TAG, "自检：载入失败（耗时 " + (System.currentTimeMillis() - t0) + " ms）：" + why);
                c.report = LocalSelfTest.compose(gpuMode, tune, LocalLlm.lastStats(), null, why,
                        autoLines(lr, lr.fellBack));
                c.title = "自检：载入失败（" + (GpuMode.isOn(gpuMode) ? "手动开着 GPU" : "纯 CPU")
                        + (lr.fellBack ? "；已自动降级" : "") + "）";
                return c;
            }
            loadedPath = f.getAbsolutePath();
            Log.i(TAG, "自检：载入成功（" + (System.currentTimeMillis() - t0) + " ms），开始跑固定短提示");

            // ③ 跑固定短提示（温度 0 = 贪心，两次可比）
            String out = null;
            boolean fellBack = lr.fellBack;
            final long t1 = System.currentTimeMillis();
            try {
                out = LocalLlm.generate(LocalSelfTest.PROMPT, LocalSelfTest.MAX_TOKENS, 0.0f, null);
            } catch (Throwable t) {
                Log.e(TAG, "自检：生成抛异常（已兜住）：" + t);
            }
            String why = (out == null) ? LocalLlm.lastError() : null;
            // ★★★ 轮次74：真机上 GPU 就是在**生成**这一步炸的（建计算管线）⇒
            //    自动档当场换成纯 CPU 再跑一次（用户不需要手动改任何东西）。
            if (out == null && GpuMode.isAuto(gpuMode) && GpuPlan.looksLikeGpuFailure(why)) {
                Log.w(TAG, "★ 自检：生成阶段 GPU 失败 ⇒ 自动降级到纯 CPU 重跑一次：" + why);
                if (reloadCpu(ctx)) {
                    fellBack = true;
                    // ★ 报告里的"自动计划"也要跟着变成降级后的计划 —— 否则会出现
                    //   "计划=用 GPU / 实际跑的是 CPU"这种自相矛盾的一屏。
                    lr.plan = GpuPlan.degrade(lr.plan);
                    lr.fellBack = true;
                    LocalHardware.remember(LocalHardware.lastFacts(), lr.plan);
                    LocalHardware.noteFallback(why);
                    try {
                        out = LocalLlm.generate(LocalSelfTest.PROMPT, LocalSelfTest.MAX_TOKENS, 0.0f, null);
                    } catch (Throwable t) {
                        Log.e(TAG, "自检：降级后重跑仍抛异常（已兜住）：" + t);
                    }
                    why = (out == null) ? LocalLlm.lastError() : null;
                }
            }
            if (why != null && GpuMode.isOn(gpuMode) && GpuFailNotice.looksLikeGpuFailure(why)) {
                GpuFailNotice.report(why);
            }
            final String text = (out == null) ? "" : LocalPrompt.cleanReply(out);
            final String stats = LocalLlm.lastStats();
            c.ok = (out != null);
            c.report = LocalSelfTest.compose(gpuMode, tune, stats, text, why, autoLines(lr, fellBack));
            c.title = c.ok
                    ? "自检完成（生成耗时 " + (System.currentTimeMillis() - t1) + " ms"
                            + (fellBack ? "；已自动降级到 CPU" : "") + "）"
                    : "自检：生成失败";
            Log.i(TAG, "自检完成：ok=" + c.ok + " / 生成耗时 "
                    + (System.currentTimeMillis() - t1) + " ms / 报告 " + c.report.length() + " 字");
            return c;
        }
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 生成一句（**同步阻塞**，必须后台线程；调用前请先 {@link #ensureLoaded}）。
     *
     * <p>★ 提示词由 {@link LocalPrompt} 拼（模板 + 历史裁剪 + 风格要求），
     * 温度由 {@link LocalPrompt#tempFor} 夹紧 —— 在线那套 1.3 搬到小模型上会复读。
     *
     * @param sink 流式回调，可为 null。★ 回调里**只做 append**（不许回来调 load/unload/generate）；
     *             返回 false 会立刻停止生成（"用户一说话桌宠就闭嘴"用它）
     */
    public static Reply answer(Context ctx, String persona, String facts, String antiRepeat,
                               List<String[]> history, String userMsg,
                               double apiTemp, int maxTokens, LocalLlm.TokenSink sink) {
        final Reply r = new Reply();
        if (!LocalLlm.isAvailable()) { r.error = guideEngineMissing(); return r; }
        // ★ 自己兜一层"还没载入"：调用方只需用 needsLoad() 决定**要不要给等待反馈**，
        //   忘了先 ensureLoaded 也不会得到一个莫名其妙的空回复（这条路径本来就是后台线程）。
        final Reply pre = ensureLoaded(ctx);
        if (!pre.ok) { r.error = pre.error; return r; }
        if (userMsg == null || userMsg.trim().length() == 0) {
            r.error = "没有要发的内容";
            return r;
        }

        final int max = maxTokens > 0 ? maxTokens : DIALOG_MAX_TOKENS;
        final int budget = LocalPrompt.maxPromptTokens(LocalLlm.DEFAULT_CTX, max);
        final String modelName = currentModelName(ctx);
        final String tmpl = LocalPrompt.templateFor(modelName);
        r.template = tmpl;
        final String prompt = LocalPrompt.build(tmpl, persona, facts, antiRepeat,
                history, userMsg, budget);
        final float temp = LocalPrompt.tempFor(apiTemp);

        Log.i(TAG, "本地对话开始：模型 " + modelName + " / " + LocalPrompt.describeTemplate(tmpl)
                + " / 提示词 " + prompt.length() + " 字(约 " + LocalPrompt.estimateTokens(prompt)
                + " tok) / 温度 " + temp + " / 上限 " + max + " tok");

        final long t0 = System.currentTimeMillis();
        String out;
        try {
            out = LocalLlm.generate(prompt, max, temp, sink);
        } catch (Throwable t) {
            Log.e(TAG, "本地生成异常：" + t);
            out = null;
        }
        // ★★★ 轮次74：**生成阶段**的自动降级 —— 真机上 GPU 就炸在这一步
        //   （`vk::Device::createComputePipeline: ErrorUnknown`）。
        //   自动档：卸掉 → 纯 CPU 重载 → **重跑一次**（用户什么都不用改）。
        //   ★ 手动「开」仍然**不回落**（对照组语义），下面那段会把它原样报出来。
        if (out == null) {
            final String why0 = LocalLlm.lastError();
            if (GpuMode.isAuto(Prefs.localGpuMode(ctx)) && GpuPlan.looksLikeGpuFailure(why0)) {
                Log.w(TAG, "★ 本地对话：生成阶段 GPU 失败 ⇒ 自动降级到纯 CPU 重跑一次：" + why0);
                if (reloadCpu(ctx)) {
                    LocalHardware.noteFallback(why0);
                    try {
                        out = LocalLlm.generate(prompt, max, temp, sink);
                    } catch (Throwable t) {
                        Log.e(TAG, "本地生成异常（降级后重跑）：" + t);
                        out = null;
                    }
                }
            }
        }
        final long ms = System.currentTimeMillis() - t0;
        if (out == null) {
            final String why = LocalLlm.lastError();
            Log.e(TAG, "本地生成失败（耗时 " + ms + " ms）：" + why);
            // ★★★ 轮次69/74：手动「开」在**生成**阶段失败 ⇒ 不静默、不回落，
            //   把原因记下来（气泡/对话框/设置页共用）并给出可操作的下一步。
            //   （自动档在上面已经尝试过降级；走到这里说明连纯 CPU 都失败或不是 GPU 的错。）
            if (GpuMode.isOn(Prefs.localGpuMode(ctx)) && GpuFailNotice.looksLikeGpuFailure(why)) {
                GpuFailNotice.report(why);
                Log.e(TAG, "★★ 开 GPU 生成失败（你选的是「开」：只走 GPU、不回落 CPU）：" + why);
                r.error = guideGpuFailed(why);
                return r;
            }
            r.error = "本地模型这次没生成出来：" + why
                    + "。可以到「🤖 本地模型」里换一个小一点的模型，或切回在线服务商。";
            return r;
        }
        final String text = LocalPrompt.cleanReply(out);
        Log.i(TAG, "本地对话完成：出字 " + out.length() + " 字 / 清理后 " + text.length()
                + " 字 / 耗时 " + ms + " ms");
        if (text.length() == 0) {
            r.error = "本地模型一个字都没生成出来（小模型偶尔会这样）。再问一次试试，"
                    + "或者到「🤖 本地模型」里换一个模型。";
            return r;
        }
        r.ok = true;
        r.text = text;
        return r;
    }

    /** 当前模型的显示名（文件名；没有则空串）—— 只用来认模板，不影响别的。 */
    private static String currentModelName(Context ctx) {
        try {
            String n = ModelStore.current(ctx);
            return n == null ? "" : n;
        } catch (Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 不许静默失败：引导文案
    //
    // ★★ 本轮的一条硬要求：「没导入模型时不许静默失败」，而且要如实说明
    //    "本机小模型慢、也更笨" —— 否则用户会以为功能坏了。
    //    这几句就是唯一的用户可见口径，断言直接钉在上面。

    /** 没导入模型时的引导（判据就是 {@code ModelStore.currentFile(ctx) == null}）。 */
    public static String guideNoModel() {
        return "本地还没导入模型，所以没法用本机对话。\n"
                + "去「设置 → 陪伴 → 对话与人格 → \uD83E\uDD16 本地模型（手机本机跑，不联网）」里"
                + "点「\uD83D\uDCE5 导入 .gguf 文件…」，挑一个小参数模型"
                + "（建议 0.5B~3B 的量化版，例如 Qwen2.5-0.5B-Instruct Q4_K_M，约 400 MB）；"
                + "导完回到这里就能纯本机出话了。\n"
                + "⚠ 先说清楚：本机小模型只是几百 MB（云端那是几千亿参数），"
                + "所以它「慢一些、也更笨」—— 闲聊和短问答够用，复杂问题建议还是切回在线服务商。";
    }

    /** 这台设备上的原生库没装起来（包里只有 arm64 的 .so）。 */
    public static String guideEngineMissing() {
        return "这台设备的本地推理引擎没装上（本功能只支持 arm64 手机），本地对话用不了。\n"
                + "可以到「设置 → 陪伴 → 对话与人格 → \uD83C\uDF10 服务商」切回在线服务商。"
                + (LocalLlm.unavailableReason() == null
                        ? "" : "\n（原因：" + LocalLlm.unavailableReason() + "）");
    }

    /** 首次载入的等待反馈（几百 MB 的 mmap，手机上要几秒到十几秒）。 */
    public static String loadingHint() {
        return "\uD83E\uDDE0 正在把本地模型载入内存（几百 MB，首次要几秒到十几秒）…";
    }

    /** 载入失败的话术（不静默、也不吓人：给出可做的下一步）。 */
    public static String guideLoadFailed(String why) {
        return "本地模型载入失败：" + (why == null ? "原因不明" : why) + "\n"
                + "常见原因是文件没导完 / 被清理软件动过 / 剩余内存不够。"
                + "可以到「\uD83E\uDD16 本地模型」里重新导入，或切回在线服务商。";
    }

    /**
     * ★★★ 轮次68 建立 / **轮次69 改口径**：**开着 GPU 但没跑起来**的话术
     * （这条是"点了没反应"的解药）。
     *
     * <p>它必须说清三件事，缺一条用户就会以为功能坏了：
     * <ol>
     *   <li><b>原因</b> —— 完整原因（原生侧的中文结论 + 原始错误串），不是"出错了"；</li>
     *   <li><b>没有回落</b> —— "已按你的设置不回落 CPU"（这正是"开 GPU"这个选择的结果）；</li>
     *   <li><b>下一步怎么点**（★ 轮次69 把退路从一条扩成五条）** ——
     *       一键改成「关」（气泡下那颗按钮 / 设置里那一行），
     *       **以及**卸载模型 / 换一个模型 / 删掉重导 / 切回在线服务商
     *       —— 这几条**不会**因为这次失败而失效（见 {@link LocalModelMenu}）。</li>
     * </ol>
     */
    public static String guideGpuFailed(String why) {
        return GpuFailNotice.PREFIX + "：" + (why == null || why.length() == 0 ? "原因不明" : why) + "\n"
                + "已按你的设置「开」用 GPU、没有偷偷回落 CPU —— 所以这一句没能答上来"
                + "（不是静默卡住）。\n"
                + "下一步：再问一句会弹出「改用关（纯 CPU）」的按钮，点一下就切；"
                + "也可以去「设置 → 陪伴 → 对话与人格 → \uD83E\uDD16 本地模型 → \u26A1 GPU 加速」里改。\n"
                + "★ 卸载模型 / 换一个模型 / 删掉重导 / 切回在线服务商这几条**都还能用**，"
                + "不会因为这次失败被挡住。\n"
                + "完整原因已经写进 logcat（DSPLlm）。";
    }
}
