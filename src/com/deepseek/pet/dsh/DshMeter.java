package com.deepseek.pet.dsh;

import java.util.List;

/**
 * token 计量与上下文预算 —— <b>纯类</b>。
 *
 * <p>手机上做这件事的意义和桌面不同：桌面的瓶颈是钱和延迟，手机的瓶颈是**上下文窗口**（
 * 各家模型 32k~1M 不等，用户还可能自己填一个网关）与**一次请求的体积**（移动网络）。
 * 所以这里不追求精确，只追求"**别把窗口撑爆、也别把工具结果整段塞进去**"。
 *
 * <p>估算口径（明写出来，因为它会影响判定）：
 * <ul>
 *   <li>CJK 字符 ≈ **1 token/字**（保守，实际多在 0.6~1.0 之间）；</li>
 *   <li>其余字符 ≈ **4 字符/token**（英文单词、标点、JSON 结构）；</li>
 *   <li>每条消息 +4 token 的框架开销；每次请求 +8 token 的请求头开销。</li>
 * </ul>
 * ★ 这是**估算**，不是分词器的读数。判定阈值因此都留了余量（默认只用 70% 窗口）。
 */
public final class DshMeter {

    private DshMeter() {}

    public static final double DEFAULT_BUDGET_RATIO = 0.70;

    /** 字符串 → 估算 token 数。 */
    public static int estimate(String s) {
        if (s == null || s.length() == 0) return 0;
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) cjk++;
            else other++;
        }
        return cjk + (other + 3) / 4;
    }

    static boolean isCjk(char c) {
        // 汉字 / 假名 / 谚文 / 全角标点 / 中文标点
        return (c >= 0x2E80 && c <= 0x9FFF)
                || (c >= 0xAC00 && c <= 0xD7AF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0xFF00 && c <= 0xFFEF);
    }

    /** 一条消息的估算 token（含框架开销）。 */
    public static int estimate(DshWire.Msg m) {
        if (m == null) return 0;
        int n = 4 + estimate(m.text) + estimate(m.reasoning);
        if (m.calls != null) {
            for (DshWire.Call c : m.calls) {
                n += 8 + estimate(c.name) + estimate(c.arguments);
            }
        }
        return n;
    }

    /** 整个消息数组的估算 token。 */
    public static int estimate(List<DshWire.Msg> msgs) {
        int n = 8;
        if (msgs == null) return n;
        for (DshWire.Msg m : msgs) n += estimate(m);
        return n;
    }

    /** 工具声明的估算 token（每次请求都要带上，不算进去会低估得很厉害）。 */
    public static int estimateTools(List<DshToolSpec> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        int n = 0;
        for (DshToolSpec s : tools) {
            n += estimate(s.name) + estimate(s.description)
                    + estimate(DshJson.write(s.parameters));
        }
        return n;
    }

    /** 这次请求的预算（窗口 × 比例，扣掉要留给回复的量）。 */
    public static int budget(int contextWindow, int reserveForReply) {
        if (contextWindow <= 0) contextWindow = 32000;   // 未知窗口时的保守默认
        int b = (int) Math.floor(contextWindow * DEFAULT_BUDGET_RATIO);
        int r = reserveForReply > 0 ? reserveForReply : 2048;
        b -= r;
        return Math.max(2048, b);
    }

    /** 人话：{@code 12.3k / 32k (38%)}。 */
    public static String describe(int used, int window) {
        return String.format(java.util.Locale.US, "%.1fk / %.0fk (%.0f%%)",
                used / 1000.0, window / 1000.0, window <= 0 ? 0.0 : (used * 100.0 / window));
    }
}
