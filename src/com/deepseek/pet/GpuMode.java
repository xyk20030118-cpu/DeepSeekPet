package com.deepseek.pet;

/**
 * 本地推理的「⚡ GPU 加速」**档位**（轮次66 建立 · 轮次68 三态 · 轮次69 收回两态 ·
 * **轮次74 加回「自动」并把它设成默认**）—— 纯类，**一个 {@code android.*} 都没有**。
 *
 * <p>用户原话（轮次74）：<b>「核心思路：运行时自动检测硬件 → 动态计算 -(n-gpu-layers)
 * → 自动降级兜底不用用户手动调参数。」</b>
 * ⇒ 默认档必须是**自动**：普通用户什么都不用选；「开 / 关」只作为**手动覆盖**保留。
 *
 * <table border="1">
 *   <tr><th>值</th><th>含义</th><th>失败时</th></tr>
 *   <tr><td>{@link #AUTO}（自动，**默认**）</td>
 *       <td>由 {@link GpuPlan} 按硬件与内存**动态决定**要不要用 GPU、放几层</td>
 *       <td><b>自动降级到纯 CPU 一次</b>，并把这件事告诉用户（不是静默）</td></tr>
 *   <tr><td>{@link #ON}（开）</td><td>强制用 GPU（请求把全部层放上去）</td>
 *       <td><b>不回落 CPU</b>：把完整原因当面告诉用户，并给一键改成「关」的退路</td></tr>
 *   <tr><td>{@link #OFF}（关）</td><td>纯 CPU</td><td>—（没有 GPU 这条路）</td></tr>
 * </table>
 *
 * <h3>★★★ 为什么「自动」现在回来了（而轮次69 曾经把它删掉）</h3>
 * 轮次69 删掉的是「**静默**回落 CPU」—— 那一版回落后用户以为自己在用 GPU，
 * 实际每秒 0.15 个 token。这两条性质必须分开看：
 * <ul>
 *   <li>「静默」是坏的那一半 ⇒ 现在的自动**一定**会把降级写进日志、自检报告与提示里；</li>
 *   <li>「自动」是好的那一半 ⇒ 用户不该为了跑起来去理解 n_gpu_layers / MMVQ / fp16。</li>
 * </ul>
 * 所以本轮的口径是：<b>默认自动；失败自动降级，而且必须说出来</b>。
 * 「开」仍然是"只走 GPU、失败不回落"的对照组（给要 A/B 的人）。
 *
 * <h3>★ 纯类的意义</h3>
 * 这个类不 import android.*，所以断言套件可以用 javac 把**出厂这一份源码**真编出来、
 * 再用 java 真跑它 —— 迁移 / 脏值 / 三档判定都不许只靠读代码觉得对。
 */
public final class GpuMode {

    /** ★★★ 自动（默认）：按硬件与内存动态决定；失败自动降级到 CPU（**会说出来**）。 */
    public static final String AUTO = "auto";
    /** 开：强制用 GPU（手动覆盖；失败**不回落 CPU**）。 */
    public static final String ON = "on";
    /** 关：纯 CPU。 */
    public static final String OFF = "off";

    /**
     * ★★★ 默认值 = <b>自动</b>（轮次74 用户原话：不用手动调参数）。
     * ★ 写死在这里，有断言钉着（反向项：把默认改回 on/off 必须当场变红）。
     */
    public static final String DEFAULT_MODE = AUTO;

    // ---------------------------------------------------------------- 轮次68 的旧值（只读，不再写）

    /** 轮次68 的「强制」（只走 GPU、失败不回落）⇒ 现在读成 {@link #ON}。 */
    public static final String LEGACY_FORCE = "force";

    /** 轮次69/70 存下来的「自动」（那一版是**静默**回落）⇒ 现在读成 {@link #AUTO}。 */
    public static final String LEGACY_AUTO = "auto";

    /** 旧值迁移的唯一规则（写进文档与断言）。 */
    public static final String MIGRATE_NOTE =
            "轮次68 的 force 读成「开」；auto 读成「自动」（回落不再静默）；"
            + "off 读成「关」；认不出的脏值当没设置过（走默认 = 自动）。";

    // ---------------------------------------------------------------- 与 JNI 的约定
    //
    // ★★ 这两个整数码**必须与 `jni/dspllm_jni.cpp` 里的 DSP_GPU_MODE_OFF / _ON 一致**
    //    （断言把两边逐字对起来查）。
    //    ★ 注意：**没有第三个码** —— 「自动」在 Java 侧就被 {@link GpuPlan} 解析成
    //    （要不要 GPU, 层数）二元组；到 JNI 时只剩"用 / 不用"。
    //    这样原生侧不需要理解"自动"，也不会因为一个脏字符串把纯 CPU 变成"用 GPU"。

    public static final int CODE_OFF = 0;
    public static final int CODE_ON = 1;

    private GpuMode() {}

    // ---------------------------------------------------------------- 纯函数（可离线真跑）

    /**
     * 把任意字符串认成三档之一；**认不出返回 null**。
     *
     * <p>★ 认不出**不**返回一个默认值：调用方要能区分"用户真的选了 X"与
     * "这个值是脏的"（见 {@code Prefs.localGpuMode} 的兼容链）。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        final String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (AUTO.equals(v)) return AUTO;
        if (ON.equals(v)) return ON;
        if (OFF.equals(v)) return OFF;
        if (LEGACY_FORCE.equals(v)) return ON;
        // ★ auto 既是旧值也是新值，语义在本轮收紧为"会说出来"。
        return null;
    }

    /**
     * ★★ 轮次66/68 的旧 boolean（{@code Prefs.localGpu}）→ 新的三档。
     *
     * <ul>
     *   <li>他**明确关过**（{@code false}）⇒ {@link #OFF}（尊重他做过的选择）；</li>
     *   <li>他**明确开过**（{@code true}）或**从来没碰过**（键不存在）⇒ {@link #DEFAULT_MODE}
     *       （自动）—— 自动会在需要时用 GPU，与"开过"不冲突，而且更聪明。</li>
     * </ul>
     */
    public static String migrate(boolean legacyPresent, boolean legacyOn) {
        if (legacyPresent && !legacyOn) return OFF;
        return DEFAULT_MODE;
    }

    /** 是不是"自动"。 */
    public static boolean isAuto(String mode) {
        return AUTO.equals(normalize(mode));
    }

    /**
     * ★ 这一档**可能**用 GPU 吗（自动 / 开 ⇒ true；关 ⇒ false）。
     *
     * <p>★★ 它**不是**"这一次真的用不用 GPU"：自动档下那由 {@link GpuPlan} 决定。
     * 需要真实决定请用 {@link LocalHardware#plan} / {@link GpuPlan.Plan#gpu}。
     */
    public static boolean usesGpu(String mode) {
        final String m = normalize(mode);
        return ON.equals(m) || AUTO.equals(m);
    }

    /** 是不是"开"（手动强制）。 */
    public static boolean isOn(String mode) {
        return ON.equals(normalize(mode));
    }

    /** 是不是"关"。 */
    public static boolean isOff(String mode) {
        return OFF.equals(normalize(mode));
    }

    /**
     * ★★★ 这一档允许**自动降级到 CPU** 吗。
     *
     * <p>轮次74：只有「自动」允许（而且降级**必须**被记录/告知，不许静默）。
     * 「开」是"只走 GPU"的对照组 ⇒ 不允许；「关」本来就在 CPU 上。
     * ★ 反向项盯着这一条：把 ON 也改成允许降级 ⇒ 当场变红。
     */
    public static boolean allowCpuFallback(String mode) {
        return isAuto(mode);
    }

    /**
     * 给 JNI 的整数码。
     *
     * <p>★ 认不出的值一律 {@link #CODE_OFF}（**静默用 CPU 是可恢复的，静默用 GPU 不是**）。
     * ★★ 自动档请**先用 {@link GpuPlan} 解析**，再把解析结果的 on/off 传进来 ——
     * 直接把 AUTO 传进来等于"用 GPU"，那不是自动的本意。
     */
    public static int code(String mode) {
        return usesGpu(mode) ? CODE_ON : CODE_OFF;
    }

    // ---------------------------------------------------------------- 文案（用户可见的唯一一份）

    /** 设置页那一行的标题（带当前档位）。 */
    public static String label(String mode) {
        final String m = normalize(mode);
        if (AUTO.equals(m)) return "\u26A1 GPU 加速：自动（按硬件决定）";
        if (ON.equals(m)) return "\u26A1 GPU 加速：开（用 GPU）";
        return "\u26A1 GPU 加速：关（纯 CPU）";
    }

    /** 「🤖 本地模型」面板里的状态行：说清"现在这样会发生什么"。 */
    public static String describe(String mode) {
        final String m = normalize(mode);
        if (AUTO.equals(m)) {
            return "· \u26A1 GPU 加速：**自动**（推荐）。运行时先看这台机器的 GPU 与可用内存，"
                    + "再算一次要放几层到 GPU；放不下或驱动拒绝，就**自动**改用纯 CPU"
                    + "（不需要你手动调任何参数，而且会在日志与自检报告里写明降级过）。";
        }
        if (ON.equals(m)) {
            return "· \u26A1 GPU 加速：开（手动）。会把全部层都请求放到 GPU 上（**真的用 GPU**）；"
                    + "初始化 / 计算管线 / 卸载层任何一步失败都**不偷偷回落 CPU**，"
                    + "而是把原因当面告诉你（下面会给一个一键改成「关」的退路）。";
        }
        return "· \u26A1 GPU 加速：关。全部在 CPU 上跑 —— 更省电、更凉，"
                + "也不会因为 GPU 出问题；但慢很多（真机实测 1.5B 约 6.5 秒/字）。";
    }

    /** 三档单选列表（★ 每一行都说清差别与代价 —— 用户点之前就该知道）。 */
    public static String[] choices() {
        return new String[] {
                "自动（默认 · 推荐）\n"
                        + "　运行时检测硬件 + 可用内存，**动态算**放几层到 GPU；"
                        + "失败自动改用纯 CPU（会在日志 / 自检报告里写明）。\n"
                        + "　★ 你不需要理解任何参数 —— 什么都不用调。",
                "开（手动 · 只走 GPU）\n"
                        + "　强制把全部层都请求放到 GPU 上（**真的用 GPU**）。失败**不回落 CPU**，"
                        + "把原因直接告诉你 —— 适合做 A/B 对照。\n"
                        + "　★ 代价：这台机器的 GPU 用不了时，本地对话会直接报错。",
                "关（纯 CPU）\n　完全不上 GPU：更省电、更凉，也不会因为 GPU 出问题。\n"
                        + "　★ 代价：慢很多（真机实测 1.5B 约 6.5 秒/字）。",
        };
    }

    /** 改完档位之后的 Toast（说清"下一次对话才生效"）。 */
    public static String changedToast(String mode) {
        final String m = normalize(mode);
        if (AUTO.equals(m)) {
            return "已改成自动：每次载入时按这台机器的硬件与可用内存决定放几层到 GPU；"
                    + "失败会自动改用纯 CPU（不会让你卡住）。内存里的模型已放掉，"
                    + "下一次对话重新载入";
        }
        if (ON.equals(m)) {
            return "已改成开 GPU：只走 GPU，失败会明确报错、不回落 CPU。"
                    + "内存里的模型已放掉，下一次对话重新载入";
        }
        return "已改成纯 CPU：内存里的模型已放掉，下一次对话按纯 CPU 重新载入"
                + "（更省电，但慢很多）";
    }

    /** 那一行的说明（点它的时候用）。 */
    public static String hint() {
        return "默认是「自动」：运行时检测硬件、动态决定 GPU 层数，失败自动降级到 CPU，"
                + "你不用手动调参数。「开 / 关」是手动覆盖。改了会把内存里的模型放掉"
                + "（GPU 层数是载入时一次性给的），下一次对话按新档位重新载入。";
    }

    /** 单选对话框的标题（设置页与断言共用一份）。 */
    public static String dialogTitle() {
        return "\u26A1 GPU 加速（选定后生效）";
    }
}
