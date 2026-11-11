package com.deepseek.pet;

/**
 * 「点击音效」的**波形合成**（轮次57）—— **纯函数、零 Android 依赖**。
 *
 * <p>播放那半边在 {@link ClickPlayer}（要 AudioTrack，只能上设备）。
 * 这么分是因为 `fpstest` 用**光秃秃的 javac**（没有 android.jar）编译纯类：
 * 混在一起就编不过 —— 而"波形对不对"恰恰是这一轮最该被真跑一遍的东西。
 *
 * <h3>为什么是"运行时合成"而不是音频素材</h3>
 * 本工程**一个音频文件都没有**（`assets/` 只有 webp/json，`res/` 只有图标与 xml）。
 * 为一个 ~90ms 的"啵"引一个二进制素材，要连带处理交付包/角色包清单、授权声明，
 * 而且断言只能验"文件在不在"。而一段短音的波形**本来就是纯函数** ——
 * 合成它不花钱、能离线断言、还能让用户选音色。
 *
 * <h3>波形怎么定的（每条都有理由，不是随手写的）</h3>
 * <ul>
 *   <li><b>起手淡入 ~1ms</b>：从非零值直接开始会有直流跳变，听起来是"啪"（噪声）不是音；</li>
 *   <li><b>收尾淡出 ~3ms</b>：截断在非零值同样会"啪"一声 —— 这条在波形上一眼可验；</li>
 *   <li><b>指数衰减包络</b>：短促音的自然形状；</li>
 *   <li><b>啵</b>：音高 900→380Hz 下滑（"啵"的听感就来自这个下滑）；
 *       <b>哒</b>：1800Hz 单音 + 极快衰减（清脆）；
 *       <b>叮</b>：1320Hz + 1980Hz 两个分音、衰减慢（像小铃铛）。</li>
 * </ul>
 */
public final class ClickSound {

    /** 音色：啵（默认） */
    public static final int STYLE_POP = 0;
    /** 音色：哒 */
    public static final int STYLE_TICK = 1;
    /** 音色：叮 */
    public static final int STYLE_DING = 2;
    public static final int STYLE_COUNT = 3;

    public static final int SAMPLE_RATE = 44100;

    /** 音量档位（设置页只给这三档；默认"中"） */
    public static final float VOL_LOW = 0.35f;
    public static final float VOL_MID = 0.60f;
    public static final float VOL_HIGH = 0.90f;
    public static final float VOL_DEFAULT = VOL_MID;

    /** 收尾淡出 ~3ms（防"啪"） */
    private static final int FADE_OUT = SAMPLE_RATE * 3 / 1000;
    /** 起手淡入 ~1ms */
    private static final int FADE_IN = SAMPLE_RATE / 1000;

    private ClickSound() {}

    public static String styleName(int style) {
        if (style == STYLE_TICK) return "哒";
        if (style == STYLE_DING) return "叮";
        return "啵";
    }

    /** 把任意整数收进合法音色（脏输入不许越界 —— 数组下标就在它后面） */
    public static int styleIndex(int style) {
        if (style < 0 || style >= STYLE_COUNT) return STYLE_POP;
        return style;
    }

    /** 这一档音色多长（毫秒） */
    public static int durationMs(int style) {
        int s = styleIndex(style);
        if (s == STYLE_TICK) return 45;
        if (s == STYLE_DING) return 260;
        return 90;
    }

    public static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    /** 音量值 → 档位名（"轻/中/响"） */
    public static String volumeName(float v) {
        float x = clamp01(v);
        if (x < 0.45f) return "轻";
        if (x < 0.75f) return "中";
        return "响";
    }

    /**
     * 合成这一档音色的 PCM（16bit 单声道）—— **纯函数**：同样输入永远同样输出。
     *
     * @param style  音色（非法值按 {@link #STYLE_POP} 处理）
     * @param volume 0~1（0 = 全零静音，不是"不生成"；越界会被夹住）
     * @return 采样数组，长度 = {@code SAMPLE_RATE × durationMs / 1000}
     */
    public static short[] pcm(int style, float volume) {
        final int st = styleIndex(style);
        final float vol = clamp01(volume);
        final int n = SAMPLE_RATE * durationMs(st) / 1000;
        short[] out = new short[n];
        if (n <= 0 || vol <= 0f) return out;          // 静音就是全零，形状照旧
        double phase = 0.0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SAMPLE_RATE;      // 秒
            double x;
            if (st == STYLE_TICK) {
                phase += 2.0 * Math.PI * 1800.0 / SAMPLE_RATE;
                x = Math.sin(phase) * Math.exp(-t / 0.006);
            } else if (st == STYLE_DING) {
                x = 0.60 * Math.sin(2.0 * Math.PI * 1320.0 * t) * Math.exp(-t / 0.090)
                        + 0.40 * Math.sin(2.0 * Math.PI * 1980.0 * t) * Math.exp(-t / 0.060);
            } else {
                double f = 380.0 + (900.0 - 380.0) * Math.exp(-t / 0.012);
                phase += 2.0 * Math.PI * f / SAMPLE_RATE;
                x = Math.sin(phase) * Math.exp(-t / 0.020);
            }
            double env = 1.0;
            if (i < FADE_IN) env *= (i + 1) / (double) FADE_IN;
            int rem = n - 1 - i;
            if (rem < FADE_OUT) env *= rem / (double) FADE_OUT;
            double v = x * env * vol;
            if (v > 1.0) v = 1.0; else if (v < -1.0) v = -1.0;
            out[i] = (short) Math.round(v * 32767.0);
        }
        return out;
    }
}
