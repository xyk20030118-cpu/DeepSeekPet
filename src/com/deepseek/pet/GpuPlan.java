package com.deepseek.pet;

/**
 * 本地推理的「**自动**硬件决策」—— 纯类，**一个 {@code android.*} 都没有**。
 *
 * <p>用户原话（轮次74）：<b>「核心思路：运行时自动检测硬件 → 动态计算 -(n-gpu-layers)
 * → 自动降级兜底不用用户手动调参数。」</b>
 *
 * <h3>★★★ 这个类解决的是什么问题</h3>
 * 轮次66~73 的教训是：<b>GPU 能不能用、能放几层，只能靠真机试</b>，
 * 而用户不该去理解 {@code n_gpu_layers} / MMVQ / fp16 这些东西。这个类把三件事收成一处：
 * <ol>
 *   <li><b>看硬件</b>：有没有 Vulkan 设备、设备名是什么（{@code Adreno (TM) 830}）、
 *       有多少可用内存；</li>
 *   <li><b>算层数</b>：{@code ngl = 预算 / 每层大小}，其中每层大小 = 模型体积 / 层数
 *       （层数从 GGUF 头里的 {@code <arch>.block_count} 读，见 {@link ModelFile#ggufLayerCount}）；</li>
 *   <li><b>兜底</b>：拿不出一个像样的层数就直接纯 CPU；GPU 建管线失败时由调用方
 *       自动降级一次（见 {@link #degrade(Plan)} 与 {@link #looksLikeGpuFailure(String)}）。</li>
 * </ol>
 *
 * <h3>★★ 已知不能用 GPU 的机型（实测，不是猜的）</h3>
 * {@code Adreno (TM) 830}：轮次72 试了共享内存 / subgroup 两条归约，轮次73 又试了第三条
 * （HYBRID）与关掉 fp16 —— <b>四组全部被驱动拒绝</b>
 * （{@code compute pipeline creation failed for mul_mat_vec_q4_k_f32_f32}）。
 * ⇒ 自动模式下**直接选纯 CPU**，不再让用户白等一次失败。
 * ★ 这条名单是**证据**，不是"所有 Adreno 都不行"：740 / 其他型号仍然会尝试 GPU。
 *
 * <h3>★ 纯类的意义</h3>
 * 这个类不 import android.*，所以断言套件可以用 javac 把**出厂这一份源码**真编出来、
 * 再用 java 真跑一遍边界（0 层 / 预算不够 / 已知坏机型 / 估算层数）。
 */
public final class GpuPlan {

    /** 估算层数用的默认值（GGUF 头读不到 block_count 时；0.5B~7B 大多在 24~36 层）。 */
    public static final int EST_LAYERS = 32;

    /** GPU 预算占"可用内存"的比例（手机 GPU 与 CPU 共享内存，必须留出余量）。 */
    public static final int BUDGET_PCT = 55;
    /** 预算上限（MB）：再大也没意义 —— 手机 GPU 的可用显存不会超过这个量级。 */
    public static final int BUDGET_CAP_MB = 3072;
    /** 至少要把这么多比例的层放上去，GPU 才可能比 CPU 快。 */
    public static final int MIN_PARTIAL_PCT = 33;

    /** 硬件事实（由 {@code LocalHardware} 在真机上填）。 */
    public static final class Facts {
        /** 有没有 Vulkan 设备（{@code gpu_device_count > 0}）。 */
        public boolean hasVulkan;
        /** 设备名，例如 {@code Adreno (TM) 830}。 */
        public String device = "";
        /** 驱动版本串（可空）。 */
        public String driver = "";
        /** subgroup 大小（0 = 不知道）。 */
        public int subgroup;
        /** 物理内存（MB，0 = 不知道）。 */
        public int totalRamMb;
        /** 当前可用内存（MB，0 = 不知道）。 */
        public int availRamMb;
        /** 模型文件体积（字节，0 = 不知道）。 */
        public long modelBytes;
        /** 模型层数（0 = 不知道 ⇒ 用 {@link #EST_LAYERS} 估算）。 */
        public int modelLayers;
    }

    /** 决策结果（给界面 / 日志 / 自检报告共用）。 */
    public static final class Plan {
        /** 要不要用 GPU。 */
        public boolean gpu;
        /** 请求卸载到 GPU 的层数（{@code gpu=false} 时恒为 0）。 */
        public int ngl;
        /** 档位标签（稳定的机器可读值，别改字面量）。 */
        public String tier = "cpu-no-vulkan";
        /** 一句中文原因（直接给用户看）。 */
        public String reason = "";
        /** 本次 GPU 预算（MB）。 */
        public int budgetMb;
        /** 每层估算大小（MB）。 */
        public int layerMb;
        /** 层数是不是估算出来的（true = GGUF 头没读到）。 */
        public boolean estLayers;
        /** 这个计划是不是"自动"算出来的（false = 用户手动指定开/关）。 */
        public boolean auto = true;
    }

    private GpuPlan() {}

    // ------------------------------------------------------------------ 决策

    /** 已知**驱动拒绝计算管线**的机型（实测；见类注释）。 */
    public static boolean knownBad(String device) {
        if (device == null) return false;
        final String d = device.replace(" ", "");
        return d.contains("Adreno(TM)830") || d.contains("Adreno830") || d.contains("Adreno830v2");
    }

    /**
     * ★★★ 唯一的决策入口：硬件事实 → (要不要 GPU, 放几层)。
     *
     * <p>规则（顺序固定，任何一条命中就返回）：
     * <ol>
     *   <li>没有 Vulkan 设备 ⇒ 纯 CPU（{@code cpu-no-vulkan}）；</li>
     *   <li>已知坏机型（Adreno 830）⇒ 纯 CPU（{@code cpu-known-bad}）；</li>
     *   <li>层数不知道 ⇒ 用 {@link #EST_LAYERS} 估算（并在结果里标 {@code estLayers}）；</li>
     *   <li>预算 = 可用内存 × {@link #BUDGET_PCT}%（上限 {@link #BUDGET_CAP_MB}）；
     *       可用内存不知道时退到总内存的 25%；</li>
     *   <li>{@code ngl = 预算 / 每层大小}；放得下全部层 ⇒ {@code gpu-full}；
     *       放得下 {@link #MIN_PARTIAL_PCT}% 以上 ⇒ {@code gpu-partial}；
     *       再少 ⇒ 纯 CPU（{@code cpu-budget}，部分卸载往往比纯 CPU 更慢）；</li>
     *   <li>体积/层数都不知道 ⇒ 交给 llama.cpp（{@code GPU_LAYERS_ALL} 的语义），
     *       由运行时的自动降级兜底。</li>
     * </ol>
     */
    public static Plan decide(Facts f) {
        final Plan p = new Plan();
        if (f == null) {
            p.reason = "没有硬件信息 ⇒ 先用纯 CPU（最安全的一档）";
            return p;
        }
        if (!f.hasVulkan) {
            p.tier = "cpu-no-vulkan";
            p.reason = "这台设备没有可用的 Vulkan GPU 设备 ⇒ 纯 CPU";
            return p;
        }
        if (knownBad(f.device)) {
            p.tier = "cpu-known-bad";
            p.reason = "本机 GPU（" + safe(f.device) + "）的驱动会拒绝计算管线"
                    + "（轮次72/73 真机实测：三条归约变体 + 关掉 fp16 全部失败）⇒ 直接用纯 CPU";
            return p;
        }

        final int layers = f.modelLayers > 0 ? f.modelLayers : EST_LAYERS;
        p.estLayers = f.modelLayers <= 0;

        if (f.modelBytes <= 0) {
            // 连模型体积都不知道：交给 llama.cpp 自己决定，运行时再兜底。
            p.gpu = true;
            p.ngl = 999;
            p.tier = "gpu-all-unknown";
            p.reason = "体积/层数未知 ⇒ 交给 llama.cpp 决定（失败会自动降级到 CPU）";
            return p;
        }

        final long layerBytes = Math.max(1L, f.modelBytes / layers);
        p.layerMb = (int) Math.max(1L, (layerBytes + 1048575L) / 1048576L);

        int budget = 0;
        if (f.availRamMb > 0) budget = (int) ((long) f.availRamMb * BUDGET_PCT / 100L);
        else if (f.totalRamMb > 0) budget = (int) ((long) f.totalRamMb * 25L / 100L);
        if (budget > BUDGET_CAP_MB) budget = BUDGET_CAP_MB;
        p.budgetMb = budget;

        if (budget <= 0) {
            // 内存账完全读不到：不要武断地关掉 GPU，交给运行时兜底。
            p.gpu = true;
            p.ngl = 999;
            p.tier = "gpu-all-unknown";
            p.reason = "读不到可用内存 ⇒ 先按全部层试（失败会自动降级到 CPU）";
            return p;
        }

        int ngl = budget / p.layerMb;
        if (ngl > layers) ngl = layers;
        final int minPartial = Math.max(4, layers * MIN_PARTIAL_PCT / 100);

        if (ngl >= layers) {
            p.gpu = true;
            p.ngl = layers;
            p.tier = "gpu-full";
            p.reason = "GPU 放得下全部 " + layers + " 层（预算 " + budget + " MB / 每层约 "
                    + p.layerMb + " MB）";
            return p;
        }
        if (ngl >= minPartial) {
            p.gpu = true;
            p.ngl = ngl;
            p.tier = "gpu-partial";
            p.reason = "GPU 放得下 " + ngl + "/" + layers + " 层（预算 " + budget
                    + " MB / 每层约 " + p.layerMb + " MB）";
            return p;
        }
        p.tier = "cpu-budget";
        p.reason = "可用内存只够把 " + ngl + "/" + layers + " 层放到 GPU（不到 "
                + MIN_PARTIAL_PCT + "%）⇒ 部分卸载通常更慢，改用纯 CPU";
        return p;
    }

    /**
     * ★★★ 自动降级：GPU 计划 → 纯 CPU 计划。
     *
     * <p>只降一次（GPU → CPU），不会再往上跳 —— 反复试 GPU 只会让用户多等。
     * 降级后的 {@code reason} 会保留"为什么降"（自检报告 / 面板都要能看见）。
     */
    public static Plan degrade(Plan p) {
        final Plan q = new Plan();
        q.auto = p == null || p.auto;
        q.tier = "cpu-fallback";
        q.ngl = 0;
        q.gpu = false;
        q.budgetMb = p == null ? 0 : p.budgetMb;
        q.layerMb = p == null ? 0 : p.layerMb;
        q.estLayers = p != null && p.estLayers;
        q.reason = "GPU 这条路失败了 ⇒ 已**自动**改用纯 CPU（不需要你手动改设置）"
                + (p != null && p.ngl > 0 ? "；原计划是放 " + p.ngl + " 层" : "");
        return q;
    }

    /**
     * 这个错误像不像"GPU 这条路失败"（决定要不要自动降级）。
     *
     * <p>★ 判据必须**窄**：模型文件坏了 / 内存不够这类错误降级到 CPU 也没用，
     * 只会让用户多等一次。只认 Vulkan / 计算管线 / 设备丢失这几类。
     */
    public static boolean looksLikeGpuFailure(String err) {
        if (err == null || err.length() == 0) return false;
        final String e = err.toLowerCase(java.util.Locale.ROOT);
        return e.contains("compute pipeline") || e.contains("vulkan") || e.contains("vk::")
                || e.contains("errorunknown") || e.contains("vk_error")
                || e.contains("ggml_vk") || e.contains("device lost")
                || e.contains("gpu 失败") || e.contains("开 gpu 失败");
    }

    // ------------------------------------------------------------------ 文案

    /** 一行摘要（面板 / 自检报告 / 日志共用）："自动：<设备> → <决定>"。 */
    public static String summary(Facts f, Plan p) {
        if (p == null) return "自动：还没有算过（下一次载入时决定）";
        final StringBuilder sb = new StringBuilder();
        sb.append("自动：");
        sb.append(f == null || f.device.length() == 0 ? "（没认出 GPU 设备）" : safe(f.device));
        sb.append(" → ");
        if (p.gpu) {
            sb.append("用 GPU，放 ").append(p.ngl).append(" 层");
        } else {
            sb.append("纯 CPU");
        }
        sb.append("（").append(p.reason).append("）");
        return sb.toString();
    }

    /** 自检报告里的机器可读行（★ 中文只给人看，机器只读这一行）。 */
    public static String reportLines(Facts f, Plan p, boolean fellBack) {
        final StringBuilder sb = new StringBuilder();
        sb.append("自动计划=").append(p == null ? "unknown" : p.tier)
                .append(" ｜ 层数=").append(p == null ? 0 : p.ngl)
                .append(p != null && p.estLayers ? "（估算）" : "")
                .append(" ｜ 预算=").append(p == null ? 0 : p.budgetMb).append(" MB")
                .append(" ｜ 每层≈").append(p == null ? 0 : p.layerMb).append(" MB")
                .append(" ｜ 自动降级=").append(fellBack ? "是" : "否")
                .append("\n");
        sb.append("硬件=").append(f == null ? "unknown" : (f.device.length() == 0 ? "无" : f.device))
                .append(" ｜ Vulkan=").append(f != null && f.hasVulkan ? 1 : 0)
                .append(" ｜ 驱动=").append(f == null || f.driver.length() == 0 ? "unknown" : f.driver)
                .append(" ｜ 可用内存=").append(f == null ? 0 : f.availRamMb).append(" MB")
                .append(" ｜ 模型=").append(f == null ? 0 : f.modelLayers).append(" 层");
        return sb.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
