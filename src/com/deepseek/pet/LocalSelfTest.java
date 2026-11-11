package com.deepseek.pet;

/**
 * 「\uD83E\uDDEA 本地推理自检」的**报告组装与判定**（轮次71 建立）——
 * 纯类，**一个 {@code android.*} 都没有**。
 *
 * <h3>★★★ 为什么要有它（这一轮最要紧的一件事）</h3>
 * 轮次66~70 每一轮都卡在同一句话上：<b>"这台手机的 GPU 到底行不行、哪个参数有用，
 * 没有真机读数"</b>。开发机 {@code adb devices} 从头到尾是空的，而
 * <b>真机是用户自己的日常机</b> —— 他不会连电脑、更不会挂 logcat。
 * ⇒ 这一轮把答案要用的东西**做成他手机上的一颗按钮**：
 * <pre>
 *   设置 → 陪伴 → 对话与人格 → 🤖 本地模型 → 🧪 本地推理自检（跑一句，出一屏结果）
 * </pre>
 * 点一下就用**当前设置**载入**当前模型**、跑一句固定短提示，然后把
 * GPU 档位 / 有没有 Vulkan 设备（名字）/ 卸载层数 / prefill tok/s / decode tok/s /
 * 首字 ms / 内存账（RssFile · VmSwap · MemAvailable）/ 失败时的原始错误串
 * **一屏显示出来，并且能一键复制**（剪贴板）。
 *
 * <h3>★ 分工：原生侧给数字，这里给结论</h3>
 * 那些数字**只存在于原生侧**（`jni/dspllm_jni.cpp` 的 {@code build_last_report()}），
 * Java 侧拿不到 ⇒ 报告的可读部分由原生侧给，**这一段负责**：
 * <ol>
 *   <li>加上抬头（这次用的是哪套参数）与结尾（怎么发回来）；</li>
 *   <li>把那几行机器可读的 {@code 数值.*} 解析出来，翻成**一句人话结论**
 *       （{@link #verdict(String)}）—— 用户不该自己去算 6500 ms/token 意味着什么；</li>
 *   <li>给出**下一步该点哪里**（{@link #nextStep(String)}）——
 *       "失败了"三个字不是结论，那是把人堵死。</li>
 * </ol>
 * ★★ 它是纯类 ⇒ 断言套件能用 javac 编**出厂这一份源码**、再用 java 真跑几组
 * 边界（见 {@code build/verify_local_llm_speed.py} 第十七节）：
 * "结论那一句到底对不对"不许只靠读代码觉得对。
 *
 * <h3>★★ 两条纪律</h3>
 * <ul>
 *   <li>**认不出就不下结论**：报告里没有 {@code 数值.ok} 这一行时，
 *       结论必须是"读数不完整，请重跑一次"，绝不许猜成"成功"或"失败"；</li>
 *   <li>**判据只认形状、不认绝对数**（本工程的老规矩）："快 / 慢"用
 *       {@link #SLOW_MS_PER_TOKEN} 这种**阈值常量**判，而且阈值写在明面上，
 *       改它就得连着断言一起改。</li>
 * </ul>
 */
public final class LocalSelfTest {

    /** 自检用的固定短提示（★ 固定 = 两次自检之间可比；换提示词就没法比了）。 */
    public static final String PROMPT = "你好！请用一句话介绍你自己。";

    /**
     * 自检最多生成多少 token。
     * ★ 24 是有讲究的：这台机器实测 decode 是 ~6.5 秒/字，再长一点用户就要等好几分钟；
     * 而 8~24 个 token 已经足够算出"ms/token"这个最关键的数（本工程的老规矩：
     * 没有读数的优化等于没做，但读数不能靠让用户等十分钟换）。
     */
    public static final int MAX_TOKENS = 24;

    /** 自检的标题（设置页那颗按钮与结果框共用一份）。 */
    public static final String BUTTON = "\uD83E\uDDEA 本地推理自检（跑一句，出一屏结果）";
    public static final String DIALOG_TITLE = "\uD83E\uDDEA 本地推理自检结果";

    /** 设置页里那颗按钮下面的一行说明（★ 用户点之前就该知道会发生什么、能拿到什么）。 */
    public static final String HINT = "· 点一下 = 用**当前设置**载入**当前模型** + 跑一句固定短提示"
            + "（「" + "你好！请用一句话介绍你自己。" + "」），"
            + "然后给你一屏**可以直接复制**的读数：GPU 档位 / 有没有 Vulkan 设备（名字）/ "
            + "卸载层数 / prefill 与 decode 的 tok/s / 首字 ms / 内存账（RssFile · VmSwap · "
            + "MemAvailable）/ 失败时的原始错误串（含被驱动拒绝的**着色器名**）。\n"
            + "　★ 后台跑（载入 + 生成要十几秒到几十秒），有进度提示、**随时能取消**；"
            + "跑之前会先把内存里的模型放掉 —— 这样用的才是**当前**设置（GPU 档位与调优旋钮"
            + "都只在载入时给一次）。";

    /** 进度框里那句话（★ 说清"要等多久"与"可以取消"）。 */
    public static final String PROGRESS = "正在自检：载入模型 + 跑一句固定短提示…\n"
            + "（首次要十几秒；慢的机器可能更久。可以点「取消」。）";

    /**
     * "decode 慢"的阈值（每 token 毫秒）。
     *
     * <p>★★★ **按机型分段记**（轮次72 定的纪律：两台机器的读数**不许混**）：
     * <ul>
     *   <li>{@code 2210132C / nuwa}（小米 13 / 骁龙 8 Gen 2 / **Adreno 740** / 8 GB）：
     *       <b>~6500 ms/token</b>（0.15 tok/s）—— 那是"1 GB 模型 + 只剩 ~170 MB 可用内存"
     *       那台机器的读数，根因是权重被换页；</li>
     *   <li>{@code 25091RP04C / piano}（小米 25091RP04C / 骁龙 8 Elite / **Adreno 830** / 12 GB）：
     *       <b>22 ms/token</b>（44.75 tok/s，**纯 CPU**）—— 权重常驻内存
     *       （{@code RssFile ≈ 模型体积}）。</li>
     * </ul>
     * ⇒ 取 3000 是"明显不对"的下限（正常的小模型哪怕纯 CPU 也该在几百 ms/token 以内）。
     * ★★ 这个数是**阈值常量**，不是实测出来的分界线；改了它就要连着断言一起改（反向项盯着它）。
     */
    public static final double SLOW_MS_PER_TOKEN = 3000.0;

    /** "快"的阈值（每 token 毫秒）：低于它才算"GPU 真的帮上忙了"。 */
    public static final double FAST_MS_PER_TOKEN = 1000.0;

    /**
     * ★★★ 轮次72：给用户的**那一条可执行结论**（真机实测之后才敢写的那句）。
     *
     * <p>为什么它必须单独成一个常量、并且真的印进报告：用户**看不懂 logcat**，
     * 他要的是"我该怎么办"。轮次72 在真机上把 6 组「开 GPU」的组合逐个试过
     * （{@code 25091RP04C} / Adreno 830），**全部被驱动拒绝**；而同一台机器
     * **纯 CPU 就有 44.75 tok/s（22 ms/token）** ⇒ 这台机器上「关」不是退而求其次，
     * 而是**更好的那一档**。
     *
     * <p>★ 诚实边界：这句话只说"**这台机器**"（有读数的这台），
     * 联发科（Mali）一台都没测过 —— 不许推广成"GPU 都没用"。
     */
    public static final String CPU_RECOMMEND =
            "★ 建议这台机器把「\u26A1 GPU 加速」设成「关」（纯 CPU）";

    /** 原生侧（ggml）打出"驱动拒绝计算管线"的那一行的原文标记（判定只认这个形状）。 */
    public static final String PIPELINE_FAIL = "compute pipeline creation failed";

    private LocalSelfTest() {}

    // ---------------------------------------------------------------- 解析（纯函数）

    /**
     * 取 {@code key=值} 那一行的值（原生侧的报告就是一行一个 {@code 键=值}）。
     *
     * <p>★ 只认**整行**：`key` 后面必须紧跟 `=`，而且必须是行首
     * （否则 `decode` 会匹配到 `decode_ms_per_token` 那一行的中间，取出一串垃圾）。
     * 找不到 ⇒ 空串（**不是 null** —— 调用方可以直接 equals）。
     */
    public static String field(String report, String key) {
        if (report == null || key == null || key.length() == 0) return "";
        final String want = key + "=";
        int p = 0;
        while (p <= report.length()) {
            int e = report.indexOf('\n', p);
            if (e < 0) e = report.length();
            final String line = report.substring(p, e).trim();
            if (line.startsWith(want)) return line.substring(want.length()).trim();
            if (e >= report.length()) break;
            p = e + 1;
        }
        return "";
    }

    /** 取一个数值型的字段；没有 / 认不出 ⇒ {@code dflt}（★ 不猜成 0：0 会被读成"真的是 0"）。 */
    public static double num(String report, String key, double dflt) {
        final String v = field(report, key);
        if (v.length() == 0) return dflt;
        try {
            return Double.parseDouble(v);
        } catch (Throwable t) {
            return dflt;
        }
    }

    /** 取一个整数型的字段；没有 / 认不出 ⇒ {@code dflt}。 */
    public static int intOf(String report, String key, int dflt) {
        final double d = num(report, key, (double) dflt);
        if (d == (double) dflt && field(report, key).length() == 0) return dflt;
        return (int) d;
    }

    // ---------------------------------------------------------------- 判定（纯函数）

    /**
     * ★★★ **一句人话结论**。
     *
     * <p>顺序很重要（先看"跑没跑起来"，再看"GPU 有没有"，最后才谈快慢）：
     * <ol>
     *   <li>{@code 数值.ok} 这一行都没有 ⇒ "读数不完整，请重跑一次"（**不猜**）；</li>
     *   <li>{@code 数值.ok=0} ⇒ 按失败原因分：没有 Vulkan 设备 / 驱动拒绝计算管线 /
     *       其它（原始错误串见报告）；</li>
     *   <li>跑起来了 + 有 GPU 设备 + decode 很快 ⇒ "GPU 真的用上了"；</li>
     *   <li>跑起来了 + decode 仍然 ~6500 ms/token ⇒ "慢的不是 GPU 这条路，
     *       是权重没常驻内存 / 模型太大"（★ 轮次67 的结论，GPU 也救不了）。</li>
     * </ol>
     */
    public static String verdict(String report) {
        if (report == null || field(report, "数值.ok").length() == 0) {
            return "读数不完整（没看到自检的数值行）—— 请重跑一次自检；"
                    + "要是每次都不完整，把下面整段发回来。";
        }
        final int ok = intOf(report, "数值.ok", -1);
        final int gpu = intOf(report, "数值.has_vulkan_gpu", -1);
        final boolean offlKnown = field(report, "数值.offloaded_layers").length() > 0;
        final int offl = intOf(report, "数值.offloaded_layers", -1);
        final double mst = num(report, "数值.decode_ms_per_token", -1.0);
        final boolean pipelineFail = report.contains(PIPELINE_FAIL);

        if (ok == 0) {
            if (gpu == 0) {
                return "❌ 这次**没有跑起来**：这台机器上**一个可用的 Vulkan GPU 设备都没有**"
                        + "（后端设备清单见报告）。⇒ 「开 GPU」在这台机器上没有路可走，"
                        + "把档位改成「关」（纯 CPU）就能继续用。";
            }
            if (pipelineFail) {
                return "❌ 这次**没有跑起来**：GPU 设备认出来了，但**驱动拒绝了计算管线**"
                        + "（报告末尾那句 " + PIPELINE_FAIL + " 后面**括号里的名字**"
                        + "就是被拒绝的着色器 —— 那就是我们要的线索）。⇒ 把那一行**整行**发回来；"
                        + "同时" + CPU_RECOMMEND + "，本机对话立刻就能用。";
            }
            return "❌ 这次**没有跑起来**（原因见报告末尾的「原始错误」与"
                    + "「最近的后端警告/错误」两行）。";
        }

        // ★★★ 轮次72 修掉的一条**会撒谎**的判据：**"有 Vulkan 设备" ≠ "GPU 用上了"**。
        //   真机实测（E7，25091RP04C / Adreno 830）：GPU 档位=关、`数值.has_vulkan_gpu=1`
        //   （设备清单里确实有 `Vulkan0(Adreno (TM) 830)`）、`数值.offloaded_layers=0`
        //   （**一层都没交给 Vulkan**），纯 CPU 跑出 22 ms/token。
        //   只按 `has_vulkan_gpu` 判 ⇒ 这一屏会印出「✅ **GPU 真的用上了**」
        //   —— 那就是把"纯 CPU 很快"写成"GPU 加速成功"，正是本轮诚实条款明令禁止的那句话。
        //   ⇒ 唯一判据是 `数值.offloaded_layers > 0`（层**真的**交上去了才叫 GPU 在用）。
        if (!offlKnown) {
            return "⚠️ 跑起来了：decode = " + fmt(mst) + " ms/token。"
                    + "★ 但报告里没有「交给 GPU 的层数」那一行 ⇒ **无法确认 GPU 到底有没有用上**"
                    + "（有 Vulkan 设备**不等于**层交上去了）—— 请重跑一次自检。";
        }
        if (offl <= 0) {
            if (mst < 0) {
                return "⚠️ 跑起来了（**纯 CPU**：0 层交给 GPU），但这次**一个 token 都没测到**"
                        + "（多半模型第一个字就是结束符）—— 换个模型再自检一次。";
            }
            final StringBuilder sb = new StringBuilder();
            sb.append("✅ 跑起来了：decode = ").append(fmt(mst)).append(" ms/token —— 这次是**纯 CPU**"
                    + "（**0 层**交给 GPU");
            if (gpu > 0) sb.append("，虽然这台机器认得出 Vulkan 设备");
            sb.append("）。");
            if (mst <= FAST_MS_PER_TOKEN) {
                sb.append('\n').append(CPU_RECOMMEND)
                  .append("：轮次72 真机实测（25091RP04C / 骁龙 8 Elite / Adreno 830，"
                          + "qwen2.5-1.5B Q4_K_M）纯 CPU = 44.75 tok/s、22 ms/token，"
                          + "而同一台机器上 6 组「开 GPU」的组合**全部被驱动拒绝**"
                          + "（" + PIPELINE_FAIL + "）⇒ 这台机器上「关」不是退而求其次，"
                          + "而是**更好的那一档**。");
            }
            return sb.toString();
        }
        if (mst < 0) {
            return "⚠️ 跑起来了，也有 GPU 设备，但这次**一个 token 都没测到**"
                    + "（多半模型第一个字就是结束符）—— 换个模型再自检一次。";
        }
        if (mst <= FAST_MS_PER_TOKEN) {
            return "✅ **GPU 真的用上了**：decode = " + fmt(mst) + " ms/token"
                    + "（< " + fmt(FAST_MS_PER_TOKEN) + " ms/token；"
                    + offl + " 层交给了 GPU）。";
        }
        if (mst >= SLOW_MS_PER_TOKEN) {
            return "⚠️ **跑起来了，但慢得离谱**：decode = " + fmt(mst) + " ms/token"
                    + "（≥ " + fmt(SLOW_MS_PER_TOKEN) + "）。"
                    + "★ 按轮次67 的结论，这个数量级不是「算得慢」、而是**权重每吐一个字都要重新捞一遍**"
                    + "（模型太大 / 内存不够，被换页或压进 zram）。"
                    + "**这一条换 GPU 也救不了**（GPU 与 CPU 取的是同一块内存）—— "
                    + "先换 0.5B（≈400 MB）或关掉后台应用再自检。";
        }
        return "⚠️ 跑起来了：decode = " + fmt(mst) + " ms/token"
                + "（比纯 CPU 快，但还没到「明显用上 GPU」的量级）—— 值得再换一个旋钮自检一次比一比。";
    }

    /** 下一步该点哪里（★ "失败了"三个字不是结论）。 */
    public static String nextStep(String report) {
        if (report == null || field(report, "数值.ok").length() == 0) {
            return "重跑一次自检（把 App 完全退出再打开更稳）。";
        }
        final int ok = intOf(report, "数值.ok", -1);
        if (ok == 0) {
            return "① 到「\u26A1 GPU 加速」把档位改成「关（纯 CPU）」就能继续用本机对话"
                    + "（卸载 / 换模型 / 删掉重导 / 切回在线服务商这几条都不受影响）；"
                    + "② 想继续试 GPU：把下面整段复制发回来，同时可以到"
                    + "「\uD83C\uDF9B 本地推理调优」里**一次只改一个**旋钮再自检一次"
                    + "（最值得先试的是「Vulkan 量化矩阵乘（MMVQ）」关掉）；"
                    + "★ Adreno 归约 / MMVQ / 算子融合 / 异步提交这四项改完要**完全退出 App 再打开**才保证生效。\n"
                    + "　" + CPU_RECOMMEND + "。";
        }
        final boolean offlKnown = field(report, "数值.offloaded_layers").length() > 0;
        final int offl = intOf(report, "数值.offloaded_layers", -1);
        final double mst = num(report, "数值.decode_ms_per_token", -1.0);
        if (offlKnown && offl <= 0) {
            if (mst >= 0 && mst <= FAST_MS_PER_TOKEN) {
                return "**什么都不用改**：就用现在这一档（「关」/ 纯 CPU）—— 本机实测已经很快，"
                        + "把这一屏复制发回来即可。"
                        + "★ 不必再去「\uD83C\uDF9B 本地推理调优」里试旋钮："
                        + "轮次72 在同一台机器上把 6 组「开 GPU」的组合逐个试过，**全部被驱动拒绝**"
                        + "（" + PIPELINE_FAIL + "），那不是参数问题。\n"
                        + "　" + CPU_RECOMMEND + "。";
            }
            return "就用现在这一档（「关」/ 纯 CPU）把这一屏复制发回来；"
                    + "想更快就先换一个小一点的模型（0.5B ≈ 400 MB）再自检一次比 decode。\n"
                    + "　" + CPU_RECOMMEND + "。";
        }
        if (mst >= SLOW_MS_PER_TOKEN) {
            return "先换一个 0.5B 的模型（≈400 MB）再自检一次 —— "
                    + "这样才分得清是「模型太大」还是「引擎/GPU 不行」。"
                    + "★ 也值得试「不用 mmap（对照）」那一档：两次的 decode 一对比，"
                    + "就能证明这个数量级的慢到底是不是内存。";
        }
        return "把下面整段复制发回来（里面有这次用的每一个参数与每一个数字）；"
                + "想再压榨一点就一次改一个旋钮、再自检一次，比 decode 那一行。";
    }

    /** 自检开始那一行日志（★ 自检报告的抬头与它是同一份事实，别在两处各写一遍）。 */
    public static String logStart(String modelName, String gpuMode, String tune, int gpuLayers) {
        return "自检开始：模型 " + (modelName == null ? "?" : modelName)
                + " / GPU 档位 " + GpuMode.label(gpuMode)
                + " / 请求卸载 " + gpuLayers + " 层"
                + " / 调优（" + LocalTune.changedCount(tune) + " 项非默认）"
                + emptyDash(LocalTune.changedList(tune))
                + " / 固定提示词 " + PROMPT.length() + " 字 / 上限 " + MAX_TOKENS + " tok / 温度 0（贪心）";
    }

    // ---------------------------------------------------------------- 组装

    /** 报告抬头：这次用的是哪套参数（★ GPU 档位 + 调优串）。 */
    public static String header(String gpuMode, String tune) {
        return "=== 本地推理自检 · 这份结果可以直接发给别人 ===\n"
                + "GPU 档位：" + GpuMode.label(gpuMode) + "\n"
                + "调优（" + LocalTune.changedCount(tune) + " 项非默认）："
                + emptyDash(LocalTune.changedList(tune)) + "\n"
                + "调优明细：" + LocalTune.reportLine(tune) + "\n"
                + "固定提示词：" + PROMPT + "（最多 " + MAX_TOKENS + " 个 token，贪心解码）\n"
                + "----------------------------------------------------------\n";
    }

    /** 报告结尾：要发回来的东西（★ 与那份一页纸指南里的口径一致）。 */
    public static String footer() {
        return "----------------------------------------------------------\n"
                + "★ 要发回来的就是**上面这一整段**（点「\uD83D\uDCCB 复制结果」都是一样的）；\n"
                + "★ 再加两样：**机型**（设置 →「关于」里那串，例如「小米 13 / 骁龙 8 Gen 2」）"
                + "与**当前模型的文件名**。\n"
                + "　★★ 机型那一串**必须带上** —— 读数**按机型分段**记，两台机器不许混"
                + "（本工程踩过：把 8 GB 那台的「6.5 秒/字」当成了另一台机器的结论）。\n"
                + "★ 上面「最近的后端警告/错误」里如果出现 "
                + PIPELINE_FAIL + " for <名字>，那个 <名字> 最要紧。\n";
    }

    /**
     * ★★★ 组装最终报告（界面直接显示 + 一键复制的那一份）。
     *
     * @param gpuMode 当前的「⚡ GPU 加速」档位
     * @param tune    当前的调优串
     * @param stats   原生侧交回来的读数（{@link LocalLlm#lastStats()}）
     * @param sample  这次真的生成出来的那一小段文本（空串 = 没生成出来）
     * @param err     失败时的原始错误串（{@link LocalLlm#lastError()}；成功时传 null）
     */
    public static String compose(String gpuMode, String tune, String stats,
                                 String sample, String err) {
        return compose(gpuMode, tune, stats, sample, err, null);
    }

    /**
     * ★★★ 轮次74：多带一段**自动计划的现场证据**（硬件 / 层数 / 预算 / 有没有降级）。
     *
     * <p>为什么必须进报告：用户的要求是"自动决定、不用手动调参数"，
     * 那么这份报告就得能回答"**它到底自动决定了什么、为什么**"——
     * 否则下一次又要靠猜。{@code autoLines} 由 {@link GpuPlan#reportLines} 生成。
     *
     * @param autoLines 自动计划那两行（null = 老调用点，不显示）
     */
    public static String compose(String gpuMode, String tune, String stats,
                                 String sample, String err, String autoLines) {
        final StringBuilder sb = new StringBuilder();
        sb.append(header(gpuMode, tune));
        if (autoLines != null && autoLines.length() > 0) {
            sb.append(autoLines);
            if (!autoLines.endsWith("\n")) sb.append('\n');
        }
        sb.append("【结论】").append(verdict(stats)).append('\n');
        sb.append("【下一步】").append(nextStep(stats)).append('\n');
        sb.append("----------------------------------------------------------\n");
        sb.append(stats == null ? "（原生侧没有给出读数）\n" : stats);
        if (sample != null && sample.length() > 0) {
            sb.append("生成出来的话=").append(sample.replace('\n', ' ')).append('\n');
        } else {
            sb.append("生成出来的话=（一个字都没出来）\n");
        }
        if (err != null && err.length() > 0) {
            sb.append("lastError=").append(err).append('\n');
        }
        sb.append(footer());
        return sb.toString();
    }

    // ---------------------------------------------------------------- 小工具

    private static String emptyDash(String s) {
        return (s == null || s.length() == 0) ? "（全部为默认值）" : s;
    }

    /** 数字的显示（整数就不带小数点；-1 显示成"没测到"）。 */
    public static String fmt(double v) {
        if (v < 0) return "没测到";
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.valueOf((long) Math.rint(v));
        return String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(v));
    }
}
