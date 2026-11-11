package com.deepseek.pet;

/**
 * 本地推理的**进阶调优旋钮**（轮次71 建立）—— 纯类，**一个 {@code android.*} 都没有**。
 *
 * <p>用户这几轮的原话一直是同一件事：<b>「速度慢的离谱 用gpu加速」→「默认使用gpu加速」→
 * 「强制使用gpu加速」→「把加速做成开GPU」</b>。轮次66~69 把「⚡ GPU 加速」做成了设置里
 * 一个**看得见**的开/关，可是真机上 Vulkan 的计算管线仍然被 Adreno 740 拒绝
 * （{@code vk::Device::createComputePipeline: ErrorUnknown}）——
 * 而"到底哪个旋钮能治好它"**只能在用户自己的手机上试**：
 * 开发机 {@code adb devices} 从头到尾是空的，这台机器上根本跑不了 arm64 的包。
 *
 * <h3>★★★ 这个类是干什么的：把"只能在 PC 上做的 A/B"搬进手机的设置页</h3>
 * 轮次67 留了一个 A/B 开关，但它的用法是
 * {@code adb shell setprop debug.dsp.adreno_subgroup 1} + {@code am force-stop} ——
 * <b>用户必须连电脑、必须敲命令</b>，所以他一直没能自己得出"到底哪个参数有用"的结论。
 * 这一轮把那一路（以及另外几个同样只能靠改参数试的 ggml/llama.cpp 开关）
 * 全部做成设置页里**可见、可点、会持久化、会写进日志**的旋钮：
 * <pre>
 *   设置 → 陪伴 → 对话与人格 → 🤖 本地模型 → 🎛 本地推理调优（进阶 · A/B 用）
 * </pre>
 *
 * <table border="1">
 *   <tr><th>键</th><th>旋钮</th><th>取值</th><th>默认</th><th>依据</th></tr>
 *   <tr><td>{@link #K_ADRENO}</td><td>Adreno 的归约方式</td>
 *       <td>auto / subgroup / hybrid</td><td>auto</td>
 *       <td><b>有上游依据</b>：llama.cpp issue #28635 / PR #29165；本工程轮次67 的本地补丁。
 *           ★ 第三档 {@code hybrid} 是轮次73 新增：上游只对 NVIDIA/Intel 选它，
 *           Qualcomm 上**永远选不到** ⇒ 本工程加了一条本地补丁（{@code GGML_VK_ADRENO_HYBRID}）</td></tr>
 *   <tr><td>{@link #K_FLASH}</td><td>flash attention</td>
 *       <td>auto / on / off</td><td>auto</td>
 *       <td><b>有上游依据</b>：{@code llama_context_params.flash_attn_type}</td></tr>
 *   <tr><td>{@link #K_UBATCH}</td><td>n_ubatch（物理批大小）</td>
 *       <td>128 / 256 / 512 / 1024</td><td>512</td>
 *       <td><b>有上游依据</b>：{@code llama_context_params.n_ubatch}</td></tr>
 *   <tr><td>{@link #K_MLOCK}</td><td>权重锁进内存（mlock）</td>
 *       <td>on / off</td><td>on</td>
 *       <td><b>有上游依据</b>：{@code RLIMIT_MEMLOCK} / {@code mlock(2)}</td></tr>
 *   <tr><td>{@link #K_LOADMODE}</td><td>权重载入方式</td>
 *       <td>mmap / none / mlock</td><td>mmap</td>
 *       <td><b>有上游依据</b>：{@code llama_load_mode}</td></tr>
 *   <tr><td>{@link #K_MMVQ}</td><td>Vulkan 量化 mat-vec（MMVQ）</td>
 *       <td>auto / off / force</td><td>auto</td>
 *       <td><b>有上游开关</b>（ggml-vulkan.cpp 里的 {@code GGML_VK_DISABLE_MMVQ} /
 *           {@code GGML_VK_FORCE_MMVQ}）；<b>它能不能绕开 Adreno 那个失败的着色器是"我们的推测"，没验过</b></td></tr>
 *   <tr><td>{@link #K_FUSION}</td><td>Vulkan 算子融合</td>
 *       <td>on / off</td><td>on</td>
 *       <td><b>有上游开关</b>（{@code GGML_VK_DISABLE_FUSION}）；对 Adreno 有效与否是<b>我们的推测</b></td></tr>
 *   <tr><td>{@link #K_ASYNC}</td><td>Vulkan 异步提交</td>
 *       <td>on / off</td><td>on</td>
 *       <td><b>有上游开关</b>（{@code GGML_VK_DISABLE_ASYNC}）；对 Adreno 有效与否是<b>我们的推测</b></td></tr>
 *   <tr><td>{@link #K_F16}</td><td>Vulkan 半精度（fp16）</td>
 *       <td>auto / off</td><td>auto</td>
 *       <td><b>有上游开关</b>（{@code GGML_VK_DISABLE_F16}，设备级）；对那条被拒的
 *           q4_K matvec 管线有没有用是<b>我们的推测</b>，轮次73 在真机上 A/B</td></tr>
 * </table>
 *
 * <h3>★★ 上线串（wire format）：一个自描述的字符串，跨过 JNI</h3>
 * {@code k=v;k=v;…}，全部小写、字母序固定（{@link #KEYS} 的顺序）。
 * 例：{@code adreno=auto;flash=auto;ubatch=512;mlock=on;loadmode=mmap;mmvq=auto;fusion=on;async=on}
 * <p>★★★ 为什么用字符串而不是再往 JNI 里塞 8 个 int：这几个旋钮**只在载入时读一次**，
 * 而字符串有一个整数没有的好处 —— 它会被**原样打进 logcat**
 * （{@code DSPLlm} 的「本次调优」那一行）。用户在真机上改了一个开关之后，
 * 日志里那一行就是他"这次到底用的哪套参数"的**现场证据**，也是我们要他把结果发回来时
 * 唯一需要对上的东西。整数串没有这种自证能力。
 * <p>★★ 键名与取值在 {@code jni/dspllm_jni.cpp} 的解析器里**逐字出现第二遍**
 * （{@code parse_tune}），这是"同一个事实抄两处"的经典坑 ⇒
 * {@code build/verify_local_llm_speed.py} 第十八节把**每一个键、每一个取值**
 * 两边对起来查，改一边就红。
 *
 * <h3>★★ 认不出的值怎么办：走默认，而且**不落盘**</h3>
 * {@link #normalize(String)} 是唯一的入口：认不出的键丢掉、认不出的值换成该键的默认值、
 * 缺的键补上默认值，最后按 {@link #KEYS} 的固定顺序重新拼一遍。
 * ⇒ 磁盘上永远只有一条**合法且规范**的串，脏值既不会传进原生层、也不会被写回去。
 * ★ 这一条与「⚡ GPU 档位」的 {@code GpuMode.normalize} 是同一个思路：
 * <b>认不出时不许猜</b>，落在"这一项没被动过"的安全一侧。
 */
public final class LocalTune {

    // ---------------------------------------------------------------- 键名（★ 与 JNI 解析器逐字对应）

    /** Adreno 的归约方式（= 轮次67 那条本地补丁的 UI 版）。 */
    public static final String K_ADRENO = "adreno";
    /**
     * Vulkan 半精度（fp16）—— 轮次73 新增。
     * ★ 上游开关是 {@code GGML_VK_DISABLE_F16}；它改的是**设备级**的 fp16 能力
     * （{@code device->fp16}），而 fp16 会进很多着色器的编译分支。
     */
    public static final String K_F16 = "f16";
    /** flash attention。 */
    public static final String K_FLASH = "flash";
    /** n_ubatch（一次真正喂进图里的物理批大小）。 */
    public static final String K_UBATCH = "ubatch";
    /** 权重锁进内存（mlock）。 */
    public static final String K_MLOCK = "mlock";
    /** 权重载入方式（mmap / 不用 mmap / mmap+锁）。 */
    public static final String K_LOADMODE = "loadmode";
    /** Vulkan 量化 mat-vec（MMVQ）。 */
    public static final String K_MMVQ = "mmvq";
    /** Vulkan 算子融合。 */
    public static final String K_FUSION = "fusion";
    /** Vulkan 异步提交。 */
    public static final String K_ASYNC = "async";

    /**
     * 全部键，**顺序固定**（编码/解码/展示都用它）。
     * ★ 加一个旋钮 = 往这里加一个键 + 下面六个表各加一格，别的地方不用动。
     */
    public static final String[] KEYS = {
            K_ADRENO, K_FLASH, K_UBATCH, K_MLOCK, K_LOADMODE, K_MMVQ, K_FUSION, K_ASYNC,
            // ★ 轮次73 新增的键**一律追加在末尾**：老版本存下来的串里没有它 ⇒
            //   normalize 会补默认值，磁盘上的串因此向后兼容（顺序变化会让"同一串"变样）。
            K_F16,
    };

    // ---------------------------------------------------------------- 取值（★ 同样与 JNI 逐字对应）

    public static final String ADRENO_AUTO = "auto";
    public static final String ADRENO_SUBGROUP = "subgroup";
    /**
     * ★★★ 轮次73：**第三种**归约变体（上游的 HYBRID / `_subgroup_no_shmem`）。
     * <p>为什么它值得单独一档：上游只对 NVIDIA / Intel 选大工作组，Qualcomm 永远是
     * SUBGROUP ⇒ 这第三种 SPIR-V 在 Adreno 上**从来没被选到过**。而前两档
     * （共享内存 / subgroup）在 Adreno 830 上都被驱动拒绝了（轮次72 E1/E2 实测）。
     * 本工程为此加了一条**只在本工程里存在**的环境变量 {@code GGML_VK_ADRENO_HYBRID}
     * （见 {@code ggml-vulkan.cpp} 的本地补丁注释）。
     */
    public static final String ADRENO_HYBRID = "hybrid";

    public static final String F16_AUTO = "auto";
    public static final String F16_OFF = "off";

    public static final String FLASH_AUTO = "auto";
    public static final String FLASH_ON = "on";
    public static final String FLASH_OFF = "off";

    public static final String ON = "on";
    public static final String OFF = "off";

    public static final String LOADMODE_MMAP = "mmap";
    public static final String LOADMODE_NONE = "none";
    public static final String LOADMODE_MLOCK = "mlock";

    public static final String MMVQ_AUTO = "auto";
    public static final String MMVQ_OFF = "off";
    public static final String MMVQ_FORCE = "force";

    public static final int UBATCH_DEFAULT = 512;

    /**
     * 每个键的合法取值表（★ 与 {@link #CHOICE_LABELS} 一一对应，长度必须相等）。
     * ★★ 这张表就是 {@link #normalize} 的全部判据：表外的值一律换成默认值。
     */
    private static final String[][] VALUES = {
            { ADRENO_AUTO, ADRENO_SUBGROUP, ADRENO_HYBRID },                   // adreno
            { FLASH_AUTO, FLASH_ON, FLASH_OFF },                               // flash
            { "128", "256", "512", "1024" },                                   // ubatch
            { ON, OFF },                                                       // mlock
            { LOADMODE_MMAP, LOADMODE_NONE, LOADMODE_MLOCK },                  // loadmode
            { MMVQ_AUTO, MMVQ_OFF, MMVQ_FORCE },                               // mmvq
            { ON, OFF },                                                       // fusion
            { ON, OFF },                                                       // async
            { F16_AUTO, F16_OFF },                                             // f16
    };

    /** 每个键的默认值（★ 必须落在 {@link #VALUES} 里 —— 有断言钉着）。 */
    private static final String[] DEFAULTS = {
            ADRENO_AUTO, FLASH_AUTO, "512", ON, LOADMODE_MMAP, MMVQ_AUTO, ON, ON,
            F16_AUTO,
    };

    /** 设置页里每一行显示的名字。 */
    private static final String[] TITLES = {
            "Adreno 的归约方式",
            "flash attention",
            "n_ubatch（物理批大小）",
            "权重锁进内存（mlock）",
            "权重载入方式",
            "Vulkan 量化矩阵乘（MMVQ）",
            "Vulkan 算子融合",
            "Vulkan 异步提交",
            "Vulkan 半精度（fp16）",
    };

    /**
     * ★★★ 每一项的**依据等级**（用户与下一个窗口都要能一眼看出"这条是查来的还是猜的"）。
     * 这是本轮的一条硬要求：<b>凡有上游依据的要标注，属于我们推测的也要标注，没验过就写没验过。</b>
     */
    private static final String[] BASIS = {
            "有上游依据：llama.cpp issue #28635 / PR #29165；本工程轮次67 的本地补丁（对 Qualcomm 关掉 subgroup 归约）",
            "有上游依据：llama_context_params.flash_attn_type（auto / enabled / disabled）",
            "有上游依据：llama_context_params.n_ubatch（一次真正喂进图里的物理批大小）",
            "有上游依据：mlock(2) + RLIMIT_MEMLOCK（Android 给普通 App 的配额很小，多半会失败）",
            "有上游依据：llama_model_params.load_mode（llama_load_mode 枚举）",
            "有上游开关：ggml-vulkan.cpp 的 GGML_VK_DISABLE_MMVQ / GGML_VK_FORCE_MMVQ；★ 它能不能绕开 Adreno 上那个失败的 q4_K 着色器是**我们的推测**，没在真机上验过",
            "有上游开关：ggml-vulkan.cpp 的 GGML_VK_DISABLE_FUSION；★ 对 Adreno 有没有用是**我们的推测**，没验过",
            "有上游开关：ggml-vulkan.cpp 的 GGML_VK_DISABLE_ASYNC；★ 对 Adreno 有没有用是**我们的推测**，没验过",
            "有上游开关：ggml-vulkan.cpp 的 GGML_VK_DISABLE_F16（设备级 fp16 能力，会进很多着色器的编译分支）；"
                    + "★ 它能不能绕开被 Adreno 拒绝的那条 q4_K matvec 管线**是推测**，轮次73 在真机上 A/B",
    };

    /** 每一项的选项显示文案（★ 与 {@link #VALUES} 一一对应，一格都不能少）。 */
    private static final String[][] CHOICE_LABELS = {
            { "自动（推荐）\n　Qualcomm 的 GPU 上改走**共享内存归约**（轮次67 的补丁，"
                    + "上游 issue #28635 / PR #29165 的修法）。\n"
                    + "　★ 手机 GPU 上 subgroup 归约的 q4_K matvec 着色器会让驱动拒绝计算管线 —— "
                    + "真机上就是那句 compute pipeline creation failed。",
              "强制 subgroup 归约（对照组）\n　**故意关掉上面那条回退**，回到打补丁之前的行为。\n"
                    + "　★ 这是「对照组」：如果两次都失败、或者这一档反而成功，那就是「回退没用/回退有害」的直接证据。",
              "第三档：子组归约 · 不用共享内存（轮次73 新增）\n"
                    + "　上游只对 NVIDIA / Intel 选这一档 ⇒ 它在 Adreno 上**从来没被选到过**。\n"
                    + "　★ 前两档在 Adreno 830 上都被驱动拒绝了（轮次72 E1/E2 实测）；"
                    + "这一档是**唯一没试过**的那条 SPIR-V。" },
            { "自动（推荐）\n　交给 llama.cpp 按模型与后端自己决定（Vulkan 上有它自己的判断）。",
              "强制开\n　一定要用 flash attention 那条注意力实现（KV cache 更省内存）。",
              "强制关\n　不用 flash attention。★ 如果失败的是 flash_attn 那几个着色器，这一档就是解药。" },
            { "128\n　一次只喂 128 个 token：显存/内存占用最小，但预填充更慢。",
              "256\n　折中。",
              "512（默认）\n　与本轮之前的版本完全一致（原来的行为就是它）。",
              "1024\n　一次喂 1024 个：预填充最快，但峰值内存最高（8 GB 机器上容易触发换页）。" },
            { "尝试（默认）\n　载入后主动读热模型文件，并**尝试** mlock 把权重钉在内存里。\n"
                    + "　★ Android 给普通 App 的锁内存配额通常只有 64 KB，几百 MB 必然失败 —— "
                    + "失败会打日志、然后照常跑。",
              "关\n　不折腾。★ 如果开着它手机被判为异常耗电/被杀，就选这一档。" },
            { "mmap（默认）\n　权重按需从文件映射进来，内存紧张时内核可以丢掉干净的页。\n"
                    + "　★ 本轮之前的版本就是它，实测比「不用 mmap」好得多。",
              "不用 mmap（对照）\n　整个模型 read() 进**匿名内存**。\n"
                    + "　★ 这就是轮次66 那个「越改越慢」的坑：1 GB 匿名内存在 8 GB 机上会被压进 zram 反复换进换出。"
                    + "留这一档是当**对照组**用的。",
              "mmap + 锁进内存\n　映射进来，同时请内核尽量别把它换出去（LLAMA_LOAD_MODE_MMAP_MLOCK）。" },
            { "自动（推荐）\n　ggml 自己按设备决定用不用 MMVQ。",
              "关掉 MMVQ\n　不走「量化矩阵乘专用」那条路，退回通用实现。\n"
                    + "　★ **最值得试的一档**：上游那个被 Adreno 拒绝的着色器正是 mul_mat_vec 系列的 —— "
                    + "关掉 MMVQ 就直接绕开它（这是我们的推测，没验过）。",
              "强制 MMVQ\n　一定要走 MMVQ 那条路（对照组）。" },
            { "开（默认）\n　让 ggml 把相邻算子融合成更少的计算管线（本轮之前的行为）。",
              "关\n　不做融合。★ 融合出来的管线如果正是被驱动拒绝的那个，这一档就可能救回来（我们的推测）。" },
            { "开（默认）\n　允许后端异步提交（本轮之前的行为）。",
              "关\n　不让后端异步提交。★ 对「驱动在并发提交下才出错」这类问题有用（我们的推测）。" },
            { "自动（推荐）\n　交给 ggml 按设备能力决定（fp16 省带宽，通常也更快）。",
              "关掉 fp16（全部走 f32）\n　设备级关掉半精度 ⇒ 很多着色器改走 f32 分支。\n"
                    + "　★ 它能不能绕开被 Adreno 拒绝的那条 q4_K matvec 管线**是推测**，"
                    + "轮次73 在真机上 A/B（`GGML_VK_DISABLE_F16`）。" },
    };

    /**
     * 每一项的"什么时候生效"。
     *
     * <p>★★★ 这一格**不是**随便写的，它分两类 —— 用户改了旋钮却发现"没效果"，
     * 十有八九就是没分清这两类：
     * <ul>
     *   <li><b>后端级（adreno / mmvq / fusion / async）</b>：这四个是 ggml 在
     *       <b>造 Vulkan 设备那一刻</b>读进去的（{@code ggml-vulkan.cpp} 的设备初始化段），
     *       而 Vulkan 设备在**一个进程里只造一次** ⇒ 改完必须
     *       <b>把 App 完全退出再打开</b>才保证生效（只切模型/只重新载入没用）。
     *       ★ 这正是轮次67 那条 {@code setprop} 说明里"要 am force-stop"的同一个原因。</li>
     *   <li><b>载入级（flash / ubatch / mlock / loadmode）</b>：这四个是
     *       {@code llama_context_params} / {@code llama_model_params} 里的字段，
     *       <b>每次都重新读</b> ⇒ 改完放掉内存里的模型、下一次载入就生效。</li>
     * </ul>
     */
    private static final String[] EFFECT = {
            "★ 要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）",
            "下次载入模型生效（改完会先把内存里的模型放掉）",
            "下次载入模型生效（改完会先把内存里的模型放掉）",
            "下次载入模型生效（改完会先把内存里的模型放掉）",
            "下次载入模型生效（改完会先把内存里的模型放掉）",
            "★ 要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）",
            "★ 要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）",
            "★ 要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）",
            "★ 要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）",
    };

    /**
     * 这一项要不要"重启 App"才保证生效（= 后端级那四个）。
     * ★ 设置页据此给出不同的 Toast；自检报告里也会照实说。
     */
    public static boolean needsRestart(int i) {
        return K_ADRENO.equals(key(i)) || K_MMVQ.equals(key(i))
                || K_FUSION.equals(key(i)) || K_ASYNC.equals(key(i))
                || K_F16.equals(key(i));
    }

    /** 改完某一项之后的 Toast（说清"什么时候生效"—— 两类不能混着说）。 */
    public static String changedToast(int i, String newValue) {
        final String shown = valueLabel(i, set(defaults(), key(i), newValue));
        return needsRestart(i)
                ? "已把「" + title(i) + "」改成：" + shown
                        + "\n★ 这一项要**完全退出 App 再打开**才保证生效"
                        + "（Vulkan 设备在一个进程里只造一次）。"
                : "已把「" + title(i) + "」改成：" + shown
                        + "\n内存里的模型已放掉，下一次载入就按它走。";
    }

    private LocalTune() {}

    // ---------------------------------------------------------------- 纯函数（可离线真跑）

    /** 旋钮个数（设置页那一屏就是照着它循环出来的）。 */
    public static int knobCount() { return KEYS.length; }

    /** 键名 → 下标；认不出返回 -1。 */
    private static int indexOfKey(String k) {
        if (k == null) return -1;
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(k)) return i;
        }
        return -1;
    }

    /** 这个值在这一项的合法表里吗（★ normalize / set / value 三处共用同一张表）。 */
    private static boolean isAllowed(int idx, String v) {
        if (idx < 0 || idx >= VALUES.length || v == null) return false;
        for (String ok : VALUES[idx]) {
            if (ok.equals(v)) return true;
        }
        return false;
    }

    /** 第 i 个旋钮的键名；越界返回 null。 */
    public static String key(int i) {
        return (i < 0 || i >= KEYS.length) ? null : KEYS[i];
    }

    /** 第 i 个旋钮的名字；越界返回空串。 */
    public static String title(int i) {
        return (i < 0 || i >= TITLES.length) ? "" : TITLES[i];
    }

    /** 第 i 个旋钮的**依据等级**（"有上游依据" / "有上游开关 + 我们的推测"）。 */
    public static String basis(int i) {
        return (i < 0 || i >= BASIS.length) ? "" : BASIS[i];
    }

    /** 第 i 个旋钮的生效时机（这一句会原样出现在设置页里）。 */
    public static String effect(int i) {
        return (i < 0 || i >= EFFECT.length) ? "" : EFFECT[i];
    }

    /** 第 i 个旋钮的合法取值（返回副本，调用方改不到内部表）。 */
    public static String[] values(int i) {
        if (i < 0 || i >= VALUES.length) return new String[0];
        return VALUES[i].clone();
    }

    /** 第 i 个旋钮的选项文案（与 {@link #values} 一一对应）。 */
    public static String[] choiceLabels(int i) {
        if (i < 0 || i >= CHOICE_LABELS.length) return new String[0];
        return CHOICE_LABELS[i].clone();
    }

    /** 第 i 个旋钮的默认值。 */
    public static String defaultValue(int i) {
        if (i < 0 || i >= DEFAULTS.length) return "";
        return DEFAULTS[i];
    }

    /** 按顺序拼出一条规范串（不做校验 —— 校验走 {@link #normalize}）。 */
    public static String encode(String[] vals) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            if (i > 0) sb.append(';');
            sb.append(KEYS[i]).append('=');
            sb.append(vals != null && i < vals.length && vals[i] != null ? vals[i] : DEFAULTS[i]);
        }
        return sb.toString();
    }

    /** **出厂默认**那一串（本轮之前的行为完全一致）。 */
    public static String defaults() {
        return encode(DEFAULTS);
    }

    /**
     * 取某一项的值；串里没有这一项（或串是 null）就返回它的**默认值**。
     *
     * <p>★ 认不出**不**返回 null：调用方（设置页）要的是一个能直接显示的值，
     * 而"这一项没被动过"的语义就是默认值。要区分"串脏了"请用 {@link #normalize} 前后对比。
     */
    public static String value(String tune, String key) {
        final int idx = indexOfKey(key);
        if (idx < 0) return "";
        if (tune != null) {
            int p = 0;
            while (p < tune.length()) {
                int e = tune.indexOf(';', p);
                if (e < 0) e = tune.length();
                final int eq = tune.indexOf('=', p);
                if (eq > p && eq < e) {
                    final String k = tune.substring(p, eq).trim();
                    if (k.equals(key)) {
                        final String v = tune.substring(eq + 1, e).trim().toLowerCase(java.util.Locale.ROOT);
                        if (isAllowed(idx, v)) return v;
                        return DEFAULTS[idx];      // ★ 值认不出 ⇒ 默认值（不猜）
                    }
                }
                p = e + 1;
            }
        }
        return DEFAULTS[idx];
    }

    /**
     * ★★★ **唯一的入口**：把任意字符串规范成一条合法串。
     *
     * <p>规则（顺序固定，全部可离线真跑）：
     * <ol>
     *   <li>认不出的键 ⇒ 丢掉；</li>
     *   <li>认得出键但值不在表里 ⇒ 用**该键的默认值**（不猜）；</li>
     *   <li>串里缺的键 ⇒ 补默认值；</li>
     *   <li>结果按 {@link #KEYS} 的固定顺序重新拼（同一个旋钮不会被写两遍）。</li>
     * </ol>
     * ⇒ 磁盘上永远只有规范串，原生层永远只见到合法值。
     * ★ 传 null / 空串 ⇒ 出厂默认那一串。
     */
    public static String normalize(String raw) {
        final String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) out[i] = DEFAULTS[i];
        if (raw != null) {
            int p = 0;
            while (p < raw.length()) {
                int e = raw.indexOf(';', p);
                if (e < 0) e = raw.length();
                final int eq = raw.indexOf('=', p);
                if (eq > p && eq < e) {
                    final String k = raw.substring(p, eq).trim().toLowerCase(java.util.Locale.ROOT);
                    final String v = raw.substring(eq + 1, e).trim().toLowerCase(java.util.Locale.ROOT);
                    final int idx = indexOfKey(k);
                    if (idx >= 0 && isAllowed(idx, v)) out[idx] = v;
                }
                p = e + 1;
            }
        }
        return encode(out);
    }

    /**
     * 改**一个**旋钮，返回一条新的规范串（其余项保持原样）。
     * ★ 值认不出 ⇒ 原样返回（**绝不**把脏值写进去）。
     */
    public static String set(String tune, String key, String val) {
        final int idx = indexOfKey(key);
        if (idx < 0) return normalize(tune);
        final String v = val == null ? "" : val.trim().toLowerCase(java.util.Locale.ROOT);
        if (!isAllowed(idx, v)) return normalize(tune);
        final String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) out[i] = value(tune, KEYS[i]);
        out[idx] = v;
        return encode(out);
    }

    /**
     * n_ubatch 的数值（设置页只显示，真正解析它的是原生侧的白名单）。
     * ★ 认不出 / 越界一律回默认值 —— 与 {@link #normalize} 同一套判据。
     * ★★ 它是这个纯类**唯一**一个"给机器看"的数字出口（自检报告与设置页都用得上）。
     */
    public static int ubatch(String tune) {
        final String v = value(tune, K_UBATCH);
        try {
            final int n = Integer.parseInt(v);
            if (n < 128) return 128;
            if (n > 1024) return 1024;
            return n;
        } catch (Throwable t) {
            return UBATCH_DEFAULT;
        }
    }

    /** 与默认值**不同**的项（"一共动过几个旋钮"）。 */
    public static int changedCount(String tune) {
        final String t = normalize(tune);
        int n = 0;
        for (int i = 0; i < KEYS.length; i++) {
            if (!DEFAULTS[i].equals(value(t, KEYS[i]))) n++;
        }
        return n;
    }

    /** 与默认值不同的那几项，形如 {@code flash=off, ubatch=1024}；全默认时返回空串。 */
    public static String changedList(String tune) {
        final String t = normalize(tune);
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            final String v = value(t, KEYS[i]);
            if (!DEFAULTS[i].equals(v)) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(KEYS[i]).append('=').append(v);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 文案（用户可见的唯一一份）

    /** 那一行在设置页里的标题（带"已改 N 项"）。 */
    public static String label(String tune) {
        final int n = changedCount(tune);
        return "\uD83C\uDF9B 本地推理调优（进阶 · A/B 用）"
                + (n > 0 ? "：已改 " + n + " 项" : "：全是默认值");
    }

    /** 面板里那一段说明（说清"这是什么、改了会怎样、什么时候生效"）。 */
    public static String describe(String tune) {
        final int n = changedCount(tune);
        final String changed = changedList(tune);
        return "· \uD83C\uDF9B 本地推理调优：" + (n == 0
                ? "全部是默认值（= 与上一版完全一样的行为）。"
                : "已改 " + n + " 项 —— " + changed + "。")
                + "\n  这几个旋钮是拿来**做对照试验**的：先跑一次「\uD83E\uDDEA 本地推理自检」记下数字，"
                + "再改**一个**旋钮、再跑一次，比 `prefill` / `decode` 两行数字就行。"
                + "\n  ★ 全部**只在载入模型时读一次** —— 改完会把内存里的模型放掉，"
                + "下一次载入才按新参数走；每一次载入的日志里都会原样打出这一串。"
                + "\n  ★ 认不出的值一律按默认值走，绝不会因为一个脏值把本地对话弄坏。";
    }

    /** 单个旋钮那一行的值（设置页里显示在名字右边）。 */
    public static String valueLabel(int i, String tune) {
        final String k = key(i);
        final String v = value(tune, k);
        // ★★ 必须**按键**分派，不能只看值：`on` 在 flash / mlock / fusion / async 四个旋钮里
        //    都合法，只看值的话 mlock 的"尝试锁进内存"会被显示成 flash 的"强制开"
        //    —— 设置页上就会出现一句自相矛盾的话（轮次71 写完第一版就是这么错的，
        //    被离线驱动的主意对不上才发现）。有断言钉着"四个旋钮的 on 文案各不相同"。
        if (K_ADRENO.equals(k)) {
            if (ADRENO_SUBGROUP.equals(v)) return "强制 subgroup 归约（对照组）";
            if (ADRENO_HYBRID.equals(v)) return "第三档：子组归约 · 不用共享内存";
            return "自动（共享内存归约）";
        }
        if (K_F16.equals(k)) {
            return F16_OFF.equals(v) ? "关掉 fp16（全部走 f32）" : "自动";
        }
        if (K_FLASH.equals(k)) {
            if (FLASH_ON.equals(v)) return "强制开";
            if (FLASH_OFF.equals(v)) return "强制关";
            return "自动";
        }
        if (K_UBATCH.equals(k)) return v;                      // 数值型：直接回显数字
        if (K_MLOCK.equals(k)) return ON.equals(v) ? "尝试锁进内存" : "关";
        if (K_LOADMODE.equals(k)) {
            if (LOADMODE_NONE.equals(v)) return "不用 mmap（对照）";
            if (LOADMODE_MLOCK.equals(v)) return "mmap + 锁进内存";
            return "mmap";
        }
        if (K_MMVQ.equals(k)) {
            if (MMVQ_OFF.equals(v)) return "关掉 MMVQ";
            if (MMVQ_FORCE.equals(v)) return "强制 MMVQ";
            return "自动";
        }
        if (K_FUSION.equals(k) || K_ASYNC.equals(k)) return ON.equals(v) ? "开" : "关";
        return v;
    }

    /** 第 i 个旋钮的对话框标题（★ 带生效时机 —— 用户点之前就该知道"改了什么时候生效"）。 */
    public static String knobDialogTitle(int i) {
        return "\uD83C\uDF9B " + title(i) + "（" + effect(i) + "）";
    }

    /** 自检报告里"这次用的是哪套参数"那一行。 */
    public static String reportLine(String tune) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(title(i)).append('=').append(valueLabel(i, tune));
        }
        return sb.toString();
    }
}
