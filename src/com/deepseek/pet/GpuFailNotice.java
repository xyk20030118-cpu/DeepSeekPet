package com.deepseek.pet;

/**
 * 「开着 GPU 但没跑起来」这件事的**记录与话术**（轮次68 建立 · **轮次69 改成两态口径**）——
 * 纯类，无 {@code android.*}。
 *
 * <h3>★★ 它解决的那个具体问题</h3>
 * 用户要的是「**开 GPU**」。而"不回落"最怕的坏法是
 * **点了没反应 / 一直转圈 / 静默 0.15 tok/s** ——
 * 也就是说：GPU 其实没起来，界面却什么也不说。
 * ⇒ 只要本地推理在**「开」**这一档下失败，就必须：
 * <ol>
 *   <li>把**完整原因**（中文结论 + 原生侧原始错误串）记下来；</li>
 *   <li>在气泡里**当面**说清"已按你的设置用 GPU、没有偷偷回落 CPU"；</li>
 *   <li>给一个**一键**能改成 {@link GpuMode#OFF} 的退路
 *       （{@link GpuModeUi} 把按钮装到对话框上）；</li>
 *   <li>设置页那一行**留着**（不是弹一次就没）。</li>
 * </ol>
 *
 * <h3>★ 为什么把「自动（失败回落 CPU）」那颗按钮去掉了</h3>
 * 轮次68 有三颗按钮（改用自动 / 改用关 / 保持强制）；轮次69 只有两档，
 * 所以退路只剩一颗：**改用「关」（纯 CPU）**。★ 但"退路"不止这一颗 ——
 * {@link LocalModelMenu} 那五条（卸载 / 切换 / 删除 / 导入 / 切回在线）**一条都不许被堵死**，
 * 这是本轮最要紧的一条要求（用户报的"换不了模型"就是它被堵住了）。
 *
 * <h3>★ 状态为什么放在静态字段里</h3>
 * 失败发生在**后台线程**（{@code LocalChat.ensureLoaded} / {@code answer}），
 * 而展示发生在**UI 线程**（悬浮窗气泡 / 聊天窗 / 设置页）——
 * 两边不共享对象，只共享"这次到底失败了没有、原因是什么"这一个事实。
 * {@link com.deepseek.pet.ApiClient.ChatResult} 是**在线那条链**的类型，
 * 按本工程的分工不该往里加"GPU"这种字段（{@code ApiClient} 里不许出现本地推理的符号）。
 *
 * <p>★ {@code popupPending} 保证**一次性**：弹过一次就不再弹（否则用户每问一句都被拦一次）。
 * 用户在设置里主动改了档位就 {@link #clear()}（那不是静默，是他自己做的选择）。
 */
public final class GpuFailNotice {

    /**
     * ★★ "开 GPU 失败"这句话的**唯一前缀**（原生侧 {@code dspllm_jni.cpp} 里是同一串，
     * 断言把两边对起来查；指南里引用的也是它）。
     */
    public static final String PREFIX = "开 GPU 失败（已按你的设置不回落 CPU）";

    /** 最近一次"开着 GPU 但没跑起来"的完整原因；null = 没有失败过。 */
    private static volatile String lastReason = null;

    /** 这一次失败还**没有**弹过"一键退路"。 */
    private static volatile boolean popupPending = false;

    private GpuFailNotice() {}

    /** 记下一次 GPU 失败（原因必须来自原生侧，不许编）。 */
    public static void report(String reason) {
        final String r = (reason == null) ? "" : reason.trim();
        lastReason = (r.length() == 0)
                ? "原因不明（原生侧没给出错误串，原始日志见 logcat 的 DSPLlm）"
                : r;
        popupPending = true;
    }

    /** 最近一次失败的原因；null = 没有。 */
    public static String last() { return lastReason; }

    /** 还有一次没弹过的失败提示。 */
    public static boolean popupPending() { return popupPending; }

    /** 弹过了（只弹一次）。 */
    public static void popupShown() { popupPending = false; }

    /** 清空（GPU 成功载入 / 用户主动改了档位时调）。 */
    public static void clear() {
        lastReason = null;
        popupPending = false;
    }

    /**
     * 这条错误看起来是 GPU 那条路的吗。
     *
     * <p>★ 为什么需要它：「开」这一档下也可能因为**别的原因**失败（文件损坏、内存不够、
     * 提示词为空）。把那些也报成"GPU 失败"，用户就会怀疑一个永远修不好的地方。
     * 判据只认 GPU / Vulkan / 计算管线这几个记号 —— 原生侧 ON 分支的文案**一定**带
     * {@link #PREFIX}（里面有"GPU"三个字母），所以这条判据不会漏。
     */
    public static boolean looksLikeGpuFailure(String error) {
        if (error == null) return false;
        return error.contains("GPU") || error.contains("Vulkan") || error.contains("vk::")
                || error.contains("compute pipeline") || error.contains("计算管线");
    }

    /** 气泡正文（★ 短、说清"没回落"、指到那颗一键按钮）。 */
    public static String bubbleText(String reason) {
        return "\u26A1 " + PREFIX + "：" + oneLine(reason)
                + "\n已按你的设置用 GPU、没有偷偷回落 CPU —— 所以这一句没能答上来。"
                + "\n下一步：点下面那颗按钮，一键改成「关」（纯 CPU）；"
                + "也可以卸载 / 换一个模型 / 切回在线服务商。";
    }

    /** 「🤖 本地模型」面板里那一行（**持久可见**，不是弹一次就没）。 */
    public static String panelLine(String reason) {
        return "\u26A0 上次开 GPU 失败：" + oneLine(reason)
                + "\n　（已按你的设置用 GPU、没有偷偷回落 CPU。下面那颗按钮可以一键改成「关」。）";
    }

    /** 退路对话框的标题。 */
    public static String title() {
        return "\u26A1 开 GPU 失败：要改用纯 CPU 吗？";
    }

    /** 退路对话框的正文（把完整原因也摆出来）。 */
    public static String message(String reason) {
        return "原因：" + oneLine(reason) + "\n\n"
                + "你选的是「⚡ GPU 加速：开（用 GPU）」—— 按你的设置，我们没有偷偷回落 CPU，"
                + "所以刚才那一句没能答上来。\n\n"
                + "下面那颗按钮点一下就切到「关（纯 CPU）」（改完会把内存里的模型放掉，"
                + "下一次对话按纯 CPU 重新载入）：\n\n"
                + "★ 不管这一步成不成，卸载模型 / 删除模型 / 换一个模型 / 切回在线服务商"
                + "**都还是能用的**。";
    }

    /** 一键改成「关」（纯 CPU）。 */
    public static String btnOff() { return "改用「关」（纯 CPU）"; }

    /** 保持开着（只是关掉这个提示框）。 */
    public static String btnKeep() { return "保持开着（我知道了）"; }

    /** 把原因压成一行（气泡/列表行里换行太多会看不清重点）。 */
    static String oneLine(String reason) {
        if (reason == null || reason.trim().length() == 0) return "原因不明（见 logcat 的 DSPLlm）";
        String s = reason.trim().replace('\n', ' ').replace('\r', ' ');
        final int max = 220;
        if (s.length() > max) s = s.substring(0, max) + "…";
        return s;
    }
}
