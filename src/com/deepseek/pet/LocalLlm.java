package com.deepseek.pet;

import android.util.Log;

/**
 * 本地 GGUF 大模型引擎（轮次62 · 引擎层）—— **纯本地、不联网**。
 *
 * <p>用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
 * 这一层只干一件事：把 llama.cpp 编成 arm64 的原生库，再包一层能被 Java 直接调用的 API。
 * 模型从哪来（导入）、谁来用（对话接线）在后面的轮次里做。
 *
 * <h3>为什么自建 JNI，而不是找一个现成的 Android 绑定</h3>
 * <ul>
 *   <li>本工程是**自研构建链**（没有 Gradle / AAR / Maven），引第三方 AAR 得先把它拆开；</li>
 *   <li>我们要的能力只有四个（加载 / 生成 / 卸载 / 查状态），
 *       而桌宠气泡是**逐句出字**的 ⇒ {@link TokenSink} 流式回调是硬需求；</li>
 *   <li>自己写 ⇒ 方法名、回调时机、错误话术全在自己手里，能被离线断言逐条钉住
 *       （见 {@code build/verify_local_llm.py}）。</li>
 * </ul>
 *
 * <h3>★ 原生库与依赖</h3>
 * 原生库是 {@code libdspllm.so}（本类的 JNI），它链着交叉编译出来的
 * {@code libllama.so} / {@code libggml.so} / {@code libggml-cpu.so} / {@code libggml-base.so}
 * 与 {@code libc++_shared.so} —— 六个 .so 都在 {@code lib/arm64-v8a/}，
 * 由 {@code build/assemble.py} 自动收进 APK（它是**通配收**，不是白名单）。
 * ★ 因为同时有多个 shared library，NDK 规定必须用 {@code c++_shared}（文档明说不支持
 * 多个 so 各自静态链 libc++）⇒ 所以多带一个 {@code libc++_shared.so}。
 *
 * <h3>★ 调用纪律（照着做就不会踩坑）</h3>
 * <ul>
 *   <li>{@link #generate} 是**同步阻塞**的 —— 必须放在后台线程调用，否则直接卡死 UI；</li>
 *   <li>回调 {@link TokenSink#onToken} 在**调用 generate 的那个线程**上同步发生，
 *       ★ **绝不要在回调里再调 {@link #load} / {@link #unload} / {@link #generate}**
 *       （前两个要拿同一把锁 ⇒ 自锁；后者本来就不许重入）；</li>
 *   <li>回调返回 {@code false} = 请求停止，生成会**立刻**收手并把已生成的部分返回回来
 *       （桌宠"用户一说话就闭嘴"就靠它）；</li>
 *   <li>{@link #unload()} 会先发停止信号再去等锁 —— 所以不会让调用方（往往是 UI 线程）
 *       干等一个正在跑的生成。</li>
 * </ul>
 *
 * <h3>★ 诚实边界（轮次62）</h3>
 * 本轮的验证是**离线断言**（ELF 架构 / 导出符号 / Java↔JNI 方法名一一对应 / APK 里真的带上了）。
 * 真机跑分（tok/s、内存）**没有验过** —— APK 只有 {@code arm64-v8a}，而模拟器是 x86_64，
 * 装了也起不来。设备实测留给用户接真机。
 */
public final class LocalLlm {

    private static final String TAG = "DSPLlm";

    /** 原生库名（对应 libdspllm.so） */
    private static final String LIB_NAME = "dspllm";

    /** 默认上下文长度。小模型 + 手机内存，2048 是"能聊天又不至于把内存吃穿"的档。 */
    public static final int DEFAULT_CTX = 2048;
    /** 默认批大小（预填充一次喂多少 token） */
    public static final int DEFAULT_BATCH = 512;
    /** 线程数下限/上限（上限给 16：8 核手机开 8 也够，留点余量给超线程） */
    public static final int MIN_THREADS = 1;
    public static final int MAX_THREADS = 16;
    /** 上下文下限/上限（下限 128 是 llama 能建图的最小量级，上限对齐默认的 16 倍） */
    public static final int MIN_CTX = 128;
    public static final int MAX_CTX = 32768;
    /** 单次生成的 token 上限（默认给人看的一句话远远够） */
    public static final int DEFAULT_MAX_TOKENS = 256;

    /**
     * 「GPU 加速」开着时要请求卸载到 GPU 的层数。
     *
     * <p>★★ 轮次66：给一个**远大于任何手机模型层数**的数（1.5B 大约 28 层），
     * 让 llama.cpp 自己把它夹到"这个模型实际有几层" —— 它本来就是这个语义
     * （{@code n_gpu_layers} 超过层数 = 全部卸载），我们不该在 Java 侧猜层数
     * （不同模型层数不一样，猜错就是"以为全卸了其实只卸了一半"）。
     * <p>★ 真正有没有 GPU 不在这里判断：机型 / 驱动只有 ggml 自己知道 ——
     * 原生侧按 {@link GpuMode} 分流：{@code off} 直接把层数压成 0（纯 CPU），
     * {@code on} **不压回**、没有可用 GPU 时直接把失败原因交回来（**不偷偷回落 CPU**）。
     */
    public static final int GPU_LAYERS_ALL = 999;

    /** 流式回调：**每凑齐一段完整文本**回调一次；返回 false 表示请求停止。 */
    public interface TokenSink {
        /**
         * @param piece 这一小段文本（一定是**完整字符**，不会出现半个汉字 ——
         *              原生侧会攒够一个 UTF-8 序列才回调；桌宠对话全是中文，这条很要紧）
         * @return true 继续生成；false 立刻停止（已生成的部分照常返回）
         */
        boolean onToken(String piece);
    }

    /** 原生库是否装载成功（false 时一切调用都会安全失败，不会崩） */
    private static boolean libOk = false;
    /** 原生库装载失败的原因（给 UI 分话术用） */
    private static String libErr = null;

    static {
        try {
            System.loadLibrary(LIB_NAME);
            libOk = true;
        } catch (Throwable t) {
            // ★ 不许抛：类初始化里抛 Throwable 会让**第一次触碰这个类**的地方直接崩
            //   （哪怕是只想读个常量）。装不上就老老实实记原因，调用方按 isAvailable() 分话术。
            libOk = false;
            libErr = "原生库 lib" + LIB_NAME + ".so 装载失败：" + t;
            Log.e(TAG, libErr);
        }
    }

    private LocalLlm() {}

    // ------------------------------------------------------------------ 原生接口
    // ★★ 下面七个方法名必须与 jni/dspllm_jni.cpp 里的
    //    Java_com_deepseek_pet_LocalLlm_<名字> **逐字对应**。
    //    拼错时 javac 不报、链接器不报，要到真机第一次调用才抛 UnsatisfiedLinkError ——
    //    verify_local_llm.py 把两边逐字比对，专门防这个。
    // ★★★ 轮次71：`nativeLoad` 多了第 7 个参数 `tune`（调优串，见 {@link LocalTune}），
    //    并且新增了 `nativeLastStats`（自检报告）。★ 两个包名那一套在 JNI 里都要跟着改，
    //    只改开发版那套的话正式版会**静默失效**（UnsatisfiedLinkError 被兜住 ⇒ 不报错也不工作）。

    private static native boolean nativeLoad(String path, int threads, int ctxSize, int batch,
                                             int gpuLayers, int gpuMode, String tune);

    private static native String nativeGenerate(String prompt, int maxTokens, float temp,
                                                TokenSink sink);

    private static native void nativeUnload();

    private static native boolean nativeLoaded();

    private static native String nativeVersion();

    private static native String nativeLastError();

    /**
     * ★★★ 轮次71：「🧪 本地推理自检」那一屏的数据来源。
     *
     * <p>最近一次载入 / 生成的全部读数（GPU 档位、后端设备名、卸载层数、
     * prefill / decode 的 tok/s、首字 ms、内存账 RssFile / VmSwap / MemAvailable、
     * 最近的后端警告与**失败的着色器名**）。原生侧整理成一段多行文本交回来，
     * 界面上一屏显示 + 一键复制 ⇒ 用户不连电脑也能把结论发回来。
     */
    private static native String nativeLastStats();

    /**
     * ★★★ 轮次74：**运行时硬件探测** —— 枚举 ggml 的后端设备。
     *
     * <p>返回一行自描述的 {@code k=v;k=v} 串（与调优串同一种风格）：
     * <pre>vulkan=1;count=1;name=Adreno (TM) 830;backend=Vulkan0;vram_free=15252;vram_total=15252;subgroup=0</pre>
     * ★ {@code name} 取的是设备的 **description**（{@code Vulkan0} 那个是后端名，
     * 不是机型）；Java 侧的 {@link GpuPlan#knownBad(String)} 认的就是它。
     * ★★ **第一次调用会初始化后端**（几十~几百毫秒）⇒ 只能在后台线程调
     * （{@link LocalHardware} 已经这么用）。
     */
    private static native String nativeDeviceInfo();

    /**
     * 取硬件信息（原生库没装好 ⇒ 空串；调用方按"没有 Vulkan 设备"处理）。
     *
     * @return {@code k=v;…}；失败时 {@code ""}
     */
    public static String deviceInfo() {
        if (!libOk) return "";
        try {
            final String s = nativeDeviceInfo();
            return s == null ? "" : s;
        } catch (Throwable t) {
            Log.w(TAG, "硬件探测失败：" + t);
            return "";
        }
    }

    // ------------------------------------------------------------------ 给 UI 的能力探测

    /** 原生库在不在（不在 = 这台设备装不了这个功能，UI 要给明确话术而不是静默失败）。 */
    public static boolean isAvailable() { return libOk; }

    /** 原生库装载失败的原因；null = 没失败过。 */
    public static String unavailableReason() { return libErr; }

    /** 底层 llama.cpp 的版本串（诊断用；原生库没装好时返回 null）。 */
    public static String version() {
        if (!libOk) return null;
        try {
            return nativeVersion();
        } catch (Throwable t) {
            Log.w(TAG, "取版本失败：" + t);
            return null;
        }
    }

    /** 上一次失败的原因（中文、能直接给用户看）；null = 没失败过。 */
    public static String lastError() {
        if (!libOk) return libErr;
        try {
            return nativeLastError();
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    /**
     * ★★★ 轮次71：最近一次载入 / 生成的**全部读数**（「🧪 本地推理自检」那一屏）。
     *
     * @return 多行文本；★ 原生库没装好 / 调用抛异常时返回一句人话（**不是 null**——
     *         null 在界面上就是"点了没反应"，那正是本工程最不想要的坏法）。
     */
    public static String lastStats() {
        if (!libOk) return "结果=失败\n失败的步骤=原生库没装好\n原始错误=" + libErr;
        try {
            final String s = nativeLastStats();
            return s == null ? "结果=失败\n失败的步骤=原生侧没有返回读数\n" : s;
        } catch (Throwable t) {
            return "结果=失败\n失败的步骤=读不出原生侧读数\n原始错误=" + t;
        }
    }

    // ------------------------------------------------------------------ 载入 / 卸载

    /**
     * 加载一个本地 GGUF 模型（**纯 CPU**，等同 {@code gpuLayers = 0}）。
     *
     * <p>★ 保留这个三参重载是为了不让调用方与断言一夜之间全改 ——
     * 真正决定"用不用 GPU"的是五参那个（见 {@link #load(String, int, int, int, int)}）。
     */
    public static boolean load(String path, int threads, int ctxSize) {
        return load(path, threads, ctxSize, 0, GpuMode.CODE_OFF);
    }

    /**
     * 加载一个本地 GGUF 模型（**轮次66 的四参重载**：只表达"要不要用 GPU"）。
     *
     * <p>★ 语义映射（轮次69 两态之后变成一一对应）：{@code gpuLayers > 0} ⇒ {@link GpuMode#ON}，
     * 否则 {@link GpuMode#OFF}。
     */
    public static boolean load(String path, int threads, int ctxSize, int gpuLayers) {
        return load(path, threads, ctxSize, gpuLayers,
                gpuLayers > 0 ? GpuMode.CODE_ON : GpuMode.CODE_OFF);
    }

    /**
     * 加载一个本地 GGUF 模型。
     *
     * <p>★ 会阻塞（几百 MB 的模型在手机上要几秒），必须放在后台线程调。
     * 重复调用是幂等的：会先卸载上一个。
     *
     * @param path      GGUF 文件路径
     * @param threads   生成线程数（&le;0 用默认；会被夹进 {@link #MIN_THREADS}~{@link #MAX_THREADS}）
     * @param ctxSize   上下文长度（&le;0 用 {@link #DEFAULT_CTX}；会被夹进 {@link #MIN_CTX}~{@link #MAX_CTX}）
     * @param gpuLayers 请求卸载到 GPU 的层数；★ 传 {@link #GPU_LAYERS_ALL} = "能用 GPU 就全放上去"，
     *                  传 0 = 纯 CPU。**这一条就是"GPU 加速"的落地方式** ——
     *                  轮次66 之前这里写死 0，把 Vulkan 后端编出来也永远不会被用。
     * @param gpuMode   ★★ 轮次69 的**两态**（{@link GpuMode#code(String)}）：
     *                  {@code off} 纯 CPU；{@code on} **只走 GPU** ——
     *                  初始化 / 计算管线 / 卸载任何一步失败都**不回落**，
     *                  直接把原因写进 {@link #lastError()} 并返回 false。
     * @return true = 可用；false = 失败，原因见 {@link #lastError()}
     */
    public static boolean load(String path, int threads, int ctxSize, int gpuLayers, int gpuMode) {
        return load(path, threads, ctxSize, gpuLayers, gpuMode, LocalTune.defaults());
    }

    /**
     * ★★★ 轮次71：带**调优旋钮**的载入（自检与对话都走这一个）。
     *
     * @param tune 调优串（{@link LocalTune}，例 {@code adreno=auto;flash=auto;…}）。
     *             ★ 认不出的键/值由原生侧的解析器走默认 —— 脏值不会把本地对话弄坏；
     *             传 null 等价于全默认（{@link LocalTune#defaults()}）。
     *             ★★ 这几个旋钮的**定义处**是 {@link LocalTune}，这里只负责规范化 + 透传。
     */
    public static boolean load(String path, int threads, int ctxSize, int gpuLayers, int gpuMode,
                               String tune) {
        if (!libOk) return false;
        if (path == null || path.length() == 0) return false;
        try {
            final boolean ok = nativeLoad(path, clampThreads(threads), clampCtx(ctxSize),
                    DEFAULT_BATCH, clampGpuLayers(gpuLayers), clampGpuMode(gpuMode),
                    LocalTune.normalize(tune));
            if (!ok) Log.w(TAG, "加载失败：" + lastError());
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "加载异常：" + t);
            return false;
        }
    }

    /** 模型是否已经加载好、可以立刻生成。 */
    public static boolean isLoaded() {
        if (!libOk) return false;
        try {
            return nativeLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 卸载模型、把内存交还（下次 {@link #load} 要重新加载）。
     *
     * <p>★ 不加载也能安全调用。若此刻正在生成，会先发停止信号再等它收手，
     * 所以不会让 UI 线程干等。
     */
    public static void unload() {
        if (!libOk) return;
        try {
            nativeUnload();
        } catch (Throwable t) {
            Log.w(TAG, "卸载异常：" + t);
        }
    }

    // ------------------------------------------------------------------ 生成

    /**
     * 用当前已加载的模型生成一段文本（**同步阻塞**，必须在后台线程调）。
     *
     * <h3>★ 流式</h3>
     * 给了 {@code sink} 就会在生成过程中**逐段**回调 —— 桌宠的对话气泡就是靠它逐句出字的，
     * 而不是等整段生成完再一次性显示（那样用户要干等几秒，体感是"死了"）。
     * {@code sink} 返回 false 会立刻停止生成，已生成的部分照常返回。
     *
     * @param prompt    提示词
     * @param maxTokens 最多生成多少 token（&le;0 用 {@link #DEFAULT_MAX_TOKENS}）
     * @param temp      温度；★ <b>&le;0 = 贪心解码</b>（每次同样输入给同样输出，便于人工复现问题）
     * @param sink      流式回调，可为 null（那就只拿整段返回值）
     * @return 生成的完整文本；**null 表示真出错**（原因见 {@link #lastError()}），
     *         空串表示"没出错但一个字都没生成"
     */
    public static String generate(String prompt, int maxTokens, float temp, TokenSink sink) {
        if (!libOk) return null;
        if (prompt == null || prompt.length() == 0) return null;
        if (!isLoaded()) return null;
        final int max = maxTokens > 0 ? maxTokens : DEFAULT_MAX_TOKENS;
        try {
            return nativeGenerate(prompt, max, temp, sink);
        } catch (Throwable t) {
            // ★ 原生侧已经把"生成中出错"转成了 null；能走到这里的是更底层的问题
            //   （例如回调里抛了异常）。一律兜住 —— 桌宠不许因为这个崩。
            Log.w(TAG, "生成异常：" + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 纯函数（可离线断言）

    /** 把任意整数夹进合法线程数；&le;0 视作"要默认值"。 */
    public static int clampThreads(int threads) {
        if (threads <= 0) return defaultThreads();
        if (threads < MIN_THREADS) return MIN_THREADS;
        if (threads > MAX_THREADS) return MAX_THREADS;
        return threads;
    }

    /** 把任意整数夹进合法上下文长度；&le;0 视作"要默认值"。 */
    public static int clampCtx(int ctx) {
        if (ctx <= 0) return DEFAULT_CTX;
        if (ctx < MIN_CTX) return MIN_CTX;
        if (ctx > MAX_CTX) return MAX_CTX;
        return ctx;
    }

    /**
     * 把"请求卸载到 GPU 的层数"夹进合法范围。
     *
     * <p>★ 只有两个语义：**0 = 纯 CPU**、**&gt;0 = 能用 GPU 就用**（上限 {@link #GPU_LAYERS_ALL}）。
     * 负数一律当 0 —— "没给"绝不能变成"全卸载"（那是相反的意图）。
     */
    public static int clampGpuLayers(int layers) {
        if (layers <= 0) return 0;
        return layers > GPU_LAYERS_ALL ? GPU_LAYERS_ALL : layers;
    }

    /**
     * 把"GPU 档位"夹进合法范围。
     *
     * <p>★★ 认不出的值一律当 {@link GpuMode#CODE_OFF}（**不能**当 on）：
     * 参数是从 SharedPreferences 的字符串转过来的，一个脏值如果落成"开"，
     * 用户就会得到"本地对话直接不可用、而且不知道为什么"。
     * **静默用 CPU 是可恢复的，静默用 GPU 不是。**
     */
    public static int clampGpuMode(int mode) {
        if (mode == GpuMode.CODE_ON) return GpuMode.CODE_ON;
        return GpuMode.CODE_OFF;
    }

    /**
     * 按 CPU 核数推荐线程数：**留两个核给系统**（否则桌宠自己会卡、整机也发烫）。
     * 这是纯函数，便于离线断言（{@code cores=1/2/4/8/16} 的取值都能验）。
     */
    public static int threadsFor(int cores) {
        if (cores <= 2) return 1;
        final int t = cores - 2;
        return t > MAX_THREADS ? MAX_THREADS : t;
    }

    /** 本机推荐线程数（读 CPU 核数）。 */
    public static int defaultThreads() {
        int cores;
        try {
            cores = Runtime.getRuntime().availableProcessors();
        } catch (Throwable t) {
            cores = 4;
        }
        return threadsFor(cores);
    }
}
