package com.deepseek.pet;

import android.app.ActivityManager;
import android.content.Context;

import java.io.File;

/**
 * ★★★ 轮次74：**运行时硬件探测**（把真机事实交给 {@link GpuPlan} 做决策）。
 *
 * <p>用户原话：「核心思路：运行时自动检测硬件 → 动态计算 -(n-gpu-layers)
 * → 自动降级兜底不用用户手动调参数。」
 *
 * <p>三样事实从哪里来：
 * <ul>
 *   <li><b>GPU 设备</b>：{@code LocalLlm.deviceInfo()}（JNI 里枚举 ggml 的后端设备，
 *       拿到名字 / 显存 / Vulkan 是否可用）。★ 第一次调用会初始化后端，**别在主线程调**；</li>
 *   <li><b>内存</b>：{@link ActivityManager.MemoryInfo}（总内存 + 当前可用）；</li>
 *   <li><b>模型层数</b>：{@link ModelFile#ggufLayerCount(File)}（只读 GGUF 前 64 KB）。</li>
 * </ul>
 *
 * <p>★ 这个类只做"取数 + 缓存"，**决策逻辑一行都不在这里**（都在 {@link GpuPlan}，
 * 那个类是纯类，可以被离线断言真编真跑）。这样"为什么选 CPU/GPU"永远只有一个出处。
 */
public final class LocalHardware {

    /** 设备信息缓存（后端枚举一次就够；失败也缓存，避免每次都卡一下）。 */
    private static volatile String deviceInfo = null;

    /** 最近一次算出来的计划（面板 / 自检报告 / 日志共用）。 */
    private static volatile GpuPlan.Plan lastPlan = null;
    private static volatile GpuPlan.Facts lastFacts = null;
    /** ★ 最近一次"GPU 失败 ⇒ 自动降级到 CPU"的原始原因（面板显示用；null = 没降过）。 */
    private static volatile String lastFallback = null;

    private LocalHardware() {}

    /** 清掉"最近一次计划"（卸载 / 换模型时调用；设备信息缓存**保留**）。 */
    public static void forgetPlan() {
        lastPlan = null;
        lastFacts = null;
        lastFallback = null;
    }

    /** 记下一次自动降级（{@link LocalChat} 调）。 */
    static void noteFallback(String why) {
        lastFallback = why;
    }

    /** 最近一次自动降级的原因；null = 没降过。 */
    public static String lastFallback() { return lastFallback; }

    public static GpuPlan.Plan lastPlan() { return lastPlan; }

    public static GpuPlan.Facts lastFacts() { return lastFacts; }

    /** 记下这一次真正用的计划（{@link LocalChat} 在载入时调）。 */
    static void remember(GpuPlan.Facts f, GpuPlan.Plan p) {
        lastFacts = f;
        lastPlan = p;
    }

    /**
     * 取硬件事实（同步；**必须在后台线程调** —— 第一次会初始化 Vulkan 后端）。
     *
     * @param model 当前模型文件（可为 null；null 时体积/层数记 0）
     */
    public static GpuPlan.Facts facts(Context ctx, File model) {
        final GpuPlan.Facts f = new GpuPlan.Facts();

        if (deviceInfo == null) {
            String s = "";
            try {
                s = LocalLlm.deviceInfo();
            } catch (Throwable t) {
                s = "";
            }
            deviceInfo = s == null ? "" : s;
        }
        parseDeviceInfo(deviceInfo, f);

        if (ctx != null) {
            try {
                final ActivityManager am =
                        (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    f.totalRamMb = (int) (mi.totalMem / 1048576L);
                    f.availRamMb = (int) (mi.availMem / 1048576L);
                }
            } catch (Throwable ignore) {
                // 读不到就留 0：GpuPlan 会把 0 当"不知道"，不会武断关掉 GPU。
            }
        }

        if (model != null && model.isFile()) {
            f.modelBytes = model.length();
            f.modelLayers = ModelFile.ggufLayerCount(model);
        }
        return f;
    }

    /** 取事实 + 算计划（同步；后台线程）。 */
    public static GpuPlan.Plan plan(Context ctx, File model) {
        final GpuPlan.Facts f = facts(ctx, model);
        final GpuPlan.Plan p = GpuPlan.decide(f);
        remember(f, p);
        return p;
    }

    /**
     * 解析 JNI 的设备信息串：
     * {@code vulkan=1;count=1;name=Adreno (TM) 830;desc=...;vram_free=1234;vram_total=15252;subgroup=64;driver=...}
     * ★ 认不出的键直接忽略（与调优串同一条纪律：脏值不许改变行为）。
     */
    static void parseDeviceInfo(String s, GpuPlan.Facts f) {
        if (s == null || s.length() == 0) {
            f.hasVulkan = false;
            return;
        }
        int p = 0;
        while (p < s.length()) {
            int e = s.indexOf(';', p);
            if (e < 0) e = s.length();
            final int eq = s.indexOf('=', p);
            if (eq > p && eq < e) {
                final String k = s.substring(p, eq).trim();
                final String v = s.substring(eq + 1, e).trim();
                if ("vulkan".equals(k)) f.hasVulkan = "1".equals(v);
                else if ("name".equals(k)) f.device = v;
                else if ("driver".equals(k)) f.driver = v;
                else if ("subgroup".equals(k)) f.subgroup = intOf(v);
            }
            p = e + 1;
        }
    }

    private static int intOf(String v) {
        try { return Integer.parseInt(v); } catch (Throwable t) { return 0; }
    }
}
