package com.deepseek.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一条 AI 回复拆成若干条短消息，用于「回复连发」模式，
 * 模拟真人一条条发消息的节奏（而不是一次性甩出一大段）。
 *
 * 纯函数、无副作用，便于用 app_process 反射做单元验证。
 *
 * 拆分规则：
 *   1) 硬换行一定是边界；
 *   2) 句末标点（。！？；!?;）是边界；行内省略号（……）不是边界，
 *      因为「我这个人……不太会说这种话呢」中的省略号只是停顿；
 *   3) 过短的碎片（少于 MIN_SEG_CHARS 个字）并入相邻片段，
 *      避免拆出「哦」「嗯」这种孤零零一条；
 *   4) 结果超过 maxSegments 时，从尾部合并，保证不超过上限；
 *   5) 若整段没有任何句末标点但很长，则在最靠近中点的逗号处切一刀，
 *      保证至少能拆成两条。
 */
public class ReplySplitter {

    /** 单条最少字符数：短于此的片段会并入相邻片段 */
    private static final int MIN_SEG_CHARS = 2;

    /** 第 5 条兜底规则：无标点长句超过此长度才考虑在逗号处切分 */
    private static final int FALLBACK_MIN_CHARS = 50;

    private ReplySplitter() { }

    /** 判断是否句末标点 */
    private static boolean isEnder(char c) {
        return c == '。' || c == '！' || c == '？'
                || c == '!' || c == '?' || c == '；' || c == ';';
    }

    /**
     * 拆分成若干条消息。
     *
     * @param reply       原始回复（可含换行，会先去掉首尾空白）
     * @param maxSegments 最多拆成几条（&lt;1 视为 1）
     * @return 拆分结果；输入为空时返回空列表；无需拆分时返回只含一条的列表
     */
    public static List<String> split(String reply, int maxSegments) {
        List<String> out = new ArrayList<String>();
        if (reply == null) return out;
        String s = reply.trim();
        if (s.length() == 0) return out;
        if (maxSegments < 1) maxSegments = 1;

        // 1) 先按换行 + 句末标点切成原始片段
        List<String> raw = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\r') {
                if (cur.length() > 0) { raw.add(cur.toString()); cur.setLength(0); }
                continue;
            }
            cur.append(c);
            if (isEnder(c)) {
                raw.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) raw.add(cur.toString());

        // 2) 去空白；过短片段与相邻片段合并
        for (int i = 0; i < raw.size(); i++) {
            String t = raw.get(i).trim();
            if (t.length() == 0) continue;
            if (t.length() < MIN_SEG_CHARS && out.size() > 0) {
                // 并进前一条
                out.set(out.size() - 1, out.get(out.size() - 1) + t);
            } else {
                out.add(t);
            }
        }
        // 首条过短且有后继 → 并进后一条
        if (out.size() >= 2 && out.get(0).length() < MIN_SEG_CHARS) {
            out.set(1, out.get(0) + out.get(1));
            out.remove(0);
        }

        // 3) 一条也没拆出来 + 很长 → 在中点附近的逗号处切一刀
        if (out.size() <= 1 && s.length() >= FALLBACK_MIN_CHARS) {
            int cut = nearestComma(s);
            if (cut > 0 && cut < s.length() - 1) {
                out.clear();
                out.add(s.substring(0, cut + 1).trim());
                out.add(s.substring(cut + 1).trim());
            }
        }

        // 4) 超过上限 → 从尾部合并
        while (out.size() > maxSegments) {
            int last = out.size() - 1;
            out.set(last - 1, out.get(last - 1) + out.get(last));
            out.remove(last);
        }
        return out;
    }

    /** 找最靠近字符串中点的逗号下标；找不到返回 -1 */
    private static int nearestComma(String s) {
        int mid = s.length() / 2;
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '，' || c == ',' || c == '、') {
                int d = Math.abs(i - mid);
                if (d < bestDist) { bestDist = d; best = i; }
            }
        }
        return best;
    }
}
