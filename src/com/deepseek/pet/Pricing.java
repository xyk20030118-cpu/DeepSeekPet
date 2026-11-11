package com.deepseek.pet;

import java.util.Calendar;

/**
 * DeepSeek API 计费时段判定。
 *
 * 依据官方文档 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
 *
 *   「空闲时段价格为高峰时段价格的一半。北京时间周一至周五（不含中国法定节假日）
 *     9:00 - 12:00、14:00 - 18:00 为高峰时段；其余时段，包括周末及中国法定节假日
 *     全天均为空闲时段。」
 *
 * 因此本类只需回答一个问题：此刻算高峰还是空闲。
 * 全部按设备本地时区计算（本项目面向国内用户，与北京时间一致）。
 */
public final class Pricing {

    private Pricing() {}

    /**
     * 高峰「峰」字配色：红。
     *
     * <p><b>注意别直接用这两个常量做前景色</b> —— 它们是**浅色主题**下的取值。
     * 实算确认：深色底（#1E2024）上 #D32F2F 只有 3.28:1、#2E7D32 只有 3.18:1，
     * 都达不到 AA。要按主题选色请用 {@link #colorPeak(boolean)}。
     *
     * <p>保留这两个常量是因为余额气泡的 Spannable 仍在直接用（那里背景是
     * 气泡自己的半透明底，与弹窗不同，见 {{@code FloatingService}}）。
     */
    public static final int COLOR_PEAK = 0xFFD32F2F;
    /** 空闲「谷」字配色：绿。仅适用于浅色底，见 {@link #COLOR_PEAK} 的说明。 */
    public static final int COLOR_IDLE = 0xFF2E7D32;

    /**
     * 深色主题下的「峰」色 —— 在深底上把亮度提上去，实测可过 AA。
     */
    public static final int COLOR_PEAK_DARK = 0xFFFF8A80;
    /**
     * 深色主题下的「谷」色。
     */
    public static final int COLOR_IDLE_DARK = 0xFF81C995;

    /** 按主题取「峰」色 */
    public static int colorPeak(boolean dark) {
        return dark ? COLOR_PEAK_DARK : COLOR_PEAK;
    }

    /** 按主题取「谷」色 */
    public static int colorIdle(boolean dark) {
        return dark ? COLOR_IDLE_DARK : COLOR_IDLE;
    }

    // ==================== 时段判定 ====================

    public static boolean isPeak() {
        return isPeakAt(Calendar.getInstance());
    }

    public static boolean isIdle() {
        return !isPeak();
    }

    /** 「峰」/「谷」单字标签 */
    public static String tag() {
        return isPeak() ? "峰" : "谷";
    }

    /** 标签配色 */
    public static int color() {
        return isPeak() ? COLOR_PEAK : COLOR_IDLE;
    }

    /** 口语化描述，供桌宠台词使用 */
    public static String label() {
        return isPeak() ? "高峰时段" : "空闲时段";
    }

    /** 价格倍数说明 */
    public static String priceHint() {
        return isPeak() ? "现在单价是平峰的两倍" : "现在是半价";
    }

    /** 给定时刻是否高峰 */
    private static boolean isPeakAt(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;   // 周末全天空闲
        if (isHoliday(c)) return false;                                        // 法定节假日全天空闲
        int mins = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (mins >= 9 * 60 && mins < 12 * 60) return true;                     // 09:00-12:00
        if (mins >= 14 * 60 && mins < 18 * 60) return true;                    // 14:00-18:00
        return false;
    }

    /**
     * 距离下一次「峰↔谷」切换还有多久，返回毫秒；找不到（理论上不会）返回 0。
     * 逐分钟向前试探，最多看 8 天，足以跨过整个假期。
     */
    public static long msToNextSwitch() {
        Calendar probe = Calendar.getInstance();
        probe.set(Calendar.SECOND, 0);
        probe.set(Calendar.MILLISECOND, 0);
        boolean cur = isPeakAt(probe);
        for (int i = 0; i < 8 * 24 * 60; i++) {
            probe.add(Calendar.MINUTE, 1);
            if (isPeakAt(probe) != cur) {
                return probe.getTimeInMillis() - System.currentTimeMillis();
            }
        }
        return 0L;
    }

    /** 「还有 1 小时 20 分」这类人话 */
    public static String nextSwitchHint() {
        long ms = msToNextSwitch();
        if (ms <= 0) return "";
        long mins = ms / 60000L;
        long h = mins / 60, m = mins % 60;
        String t = (h > 0 ? (h + " 小时 ") : "") + m + " 分";
        return isPeak() ? ("距空闲还有 " + t) : ("距高峰还有 " + t);
    }

    // ==================== 中国法定节假日 ====================

    /**
     * 中国法定节假日（休息日）近似表。
     *
     * ⚠ 国务院每年 11~12 月才公布次年安排，且含调休。这里列出的是常规日期，
     *   与 TimeContext 中农历节日的近似公历日期保持一致。跨年后如有偏差，
     *   只需更新本表（格式：{月, 起日, 止日}）。
     */
    private static final int[][] HOLIDAYS = {
        { 1,  1,  3},    // 元旦
        { 2, 15, 21},    // 春节（2026 除夕 2/16、初一 2/17）
        { 4,  4,  6},    // 清明节
        { 5,  1,  5},    // 劳动节
        { 6, 19, 21},    // 端午节（2026 端午 6/19）
        { 9, 25, 27},    // 中秋节（2026 中秋 9/25）
        {10,  1,  8},    // 国庆节
    };

    public static boolean isHoliday(Calendar c) {
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        for (int[] h : HOLIDAYS) {
            if (m == h[0] && d >= h[1] && d <= h[2]) return true;
        }
        return false;
    }

    // ==================== 单价表（元 / 百万 tokens）====================

    /**
     * 官方价目。idx: 0=flash, 1=pro。
     * 每项 {高峰输入命中, 高峰输入未命中, 高峰输出, 空闲输入命中, 空闲输入未命中, 空闲输出}
     * 空闲价 = 高峰价的一半。
     */
    private static final double[][] PRICE = {
        { 0.04, 2.0,  8.0,  0.02, 1.0,  4.0 },   // deepseek-flash
        { 0.30, 9.0, 27.0,  0.15, 4.5, 13.5 },   // deepseek-v4-pro
    };

    /** 按模型名猜档位：含 pro 视为高端价，否则按 flash */
    public static int tierOf(String model) {
        if (model == null) return 0;
        String m = model.toLowerCase();
        return m.contains("pro") ? 1 : 0;
    }

    /**
     * 估算一次调用的费用（元）。peak=true 用高峰价，否则空闲价。
     * 只用于给用户一个量级感受，不作为对账依据。
     */
    public static double estimateCost(String model, boolean peak,
                                      long cacheHit, long cacheMiss, long output) {
        double[] p = PRICE[tierOf(model)];
        double inHit  = peak ? p[0] : p[3];
        double inMiss = peak ? p[1] : p[4];
        double out    = peak ? p[2] : p[5];
        return (cacheHit * inHit + cacheMiss * inMiss + output * out) / 1_000_000.0;
    }
}
