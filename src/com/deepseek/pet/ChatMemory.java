package com.deepseek.pet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 连续对话记忆 + 主动记忆点。
 *
 * 两套数据分开存，控制 token 消耗：
 *   1) 短期往来 history：最近 N 条「用户/桌宠」消息（N 可调，见 ctxLimit）。
 *   2) 长期记忆点 facts：一小段「已知信息」摘要（名字、喜好、约定等），
 *      始终注入，长度有硬上限，不会随聊天条数增长。就算 history 被裁掉，
 *      关键事实也还在。
 *
 * 存储格式：
 *   history: JSON 数组 [{"r":"user","t":"..."}, ...]
 *   facts:   纯文本，每行一条，最多 FACTS_MAX_LINES 行
 */
public class ChatMemory {

    private static final String NAME = "deepseek_pet_chat";
    private static final String KEY_HISTORY = "history";
    private static final String KEY_ENABLED = "memory_enabled";
    private static final String KEY_CTX_LIMIT = "ctx_limit";
    private static final String KEY_FACTS = "facts";
    private static final String KEY_FACTS_ON = "facts_enabled";

    /** history 最多保留的消息条数（user+assistant 合计）——封顶，防止无限增长 */
    public static final int MAX_ITEMS = 40;
    /** 单条消息最长字符数，超长截断，防止上下文过大 */
    private static final int MAX_ITEM_CHARS = 600;

    /** 上下文条数可选项（0 表示不带历史，最省 token） */
    public static final int[] CTX_OPTIONS = new int[]{0, 4, 6, 8, 12, 20, 24, 32, 40};
    /** 默认上下文条数（旧版是 20，太费；降到 8） */
    public static final int DEFAULT_CTX_LIMIT = 8;

    /** 主动记忆点：最多保留几条、每条最长字符数 */
    public static final int FACTS_MAX_LINES = 6;
    private static final int FACTS_MAX_CHARS = 400;

    // ---------------- 开关：连续对话记忆 ----------------

    /** 连续对话记忆是否开启（默认开启） */
    public static boolean enabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_ENABLED, on).apply();
    }

    // ---------------- 上下文上限 ----------------

    /** 每次请求携带的历史条数（可调，默认 8） */
    public static int ctxLimit(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        int v = sp.getInt(KEY_CTX_LIMIT, DEFAULT_CTX_LIMIT);
        if (v < 0) v = 0;
        if (v > MAX_ITEMS) v = MAX_ITEMS;
        return v;
    }

    public static void setCtxLimit(Context ctx, int v) {
        if (v < 0) v = 0;
        if (v > MAX_ITEMS) v = MAX_ITEMS;
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_CTX_LIMIT, v).apply();
    }

    /** 供 UI 显示：如「8 条」「不带历史」，并附 token 档次提示 */
    public static String ctxLimitLabel(Context ctx) {
        int v = ctxLimit(ctx);
        if (v <= 0) return "不带历史（最省）";
        return v + " 条 · " + ctxCostHint(v);
    }

    /** token 消耗档次文字提示 */
    public static String ctxCostHint(int v) {
        if (v <= 4) return "省";
        if (v <= 8) return "均衡";
        if (v <= 20) return "较费";
        return "很费";
    }

    // ---------------- 主动记忆点（长期事实） ----------------

    /** 是否启用主动记忆点（默认开启） */
    public static boolean factsEnabled(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_FACTS_ON, true);
    }

    public static void setFactsEnabled(Context ctx, boolean on) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putBoolean(KEY_FACTS_ON, on).apply();
    }

    /** 读取记忆点原文（每行一条），可能为空 */
    public static String facts(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_FACTS, "");
        return s == null ? "" : s.trim();
    }

    /** 覆盖写入记忆点（自动按行裁剪到 FACTS_MAX_LINES） */
    public static void setFacts(Context ctx, String text) {
        String cleaned = sanitizeFacts(text);
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_FACTS, cleaned).apply();
    }

    /** 记忆点条数（行数） */
    public static int factsCount(Context ctx) {
        String f = facts(ctx);
        if (f.length() == 0) return 0;
        int n = 0;
        for (String line : f.split("\n")) if (line.trim().length() > 0) n++;
        return n;
    }

    public static void clearFacts(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_FACTS).apply();
    }

    /**
     * 注入用的记忆点提示词片段；无内容返回 null。
     * 形如：「【已知信息】\n- 用户叫小明\n- 用户喜欢安静」
     */
    public static String factsPrompt(Context ctx) {
        if (!factsEnabled(ctx)) return null;
        String f = facts(ctx);
        if (f.length() == 0) return null;
        return "【关于主人的已知信息，请自然运用、勿生硬复述】\n" + f;
    }

    private static String sanitizeFacts(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n");
        StringBuilder sb = new StringBuilder();
        int kept = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.startsWith("-")) line = line.substring(1).trim();
            if (line.length() == 0) continue;
            if (line.length() > 80) line = line.substring(0, 80);
            if (sb.length() > 0) sb.append("\n");
            sb.append("- ").append(line);
            kept++;
            if (kept >= FACTS_MAX_LINES) break;
        }
        String out = sb.toString();
        if (out.length() > FACTS_MAX_CHARS) out = out.substring(0, FACTS_MAX_CHARS);
        return out;
    }

    // ---------------- 历史读写 ----------------

    /** 读取当前全部记忆（[[role, text], ...]），按时间先后排列 */
    public static List<String[]> load(Context ctx) {
        List<String[]> out = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY_HISTORY, "");
        if (raw == null || raw.length() == 0) return out;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String r = o.optString("r", "");
                String t = o.optString("t", "");
                if (r.length() == 0 || t.length() == 0) continue;
                // 第三位是入库存时间戳；旧记录没有该字段，回落为 "0"（=未知时间）
                String ts = String.valueOf(o.optLong("ts", 0L));
                // 存量历史里已被写进去的（刚刚）/[菜刀] 类痕迹，清掉后再交给上层
                t = StyleGuard.strip(t);
                out.add(new String[]{r, t, ts});
            }
        } catch (Exception ignore) { }
        return out;
    }

    /** 追加一条消息并持久化（自动裁剪到 MAX_ITEMS） */
    public static synchronized void append(Context ctx, String role, String text) {
        if (!enabled(ctx)) return;
        if (role == null || role.length() == 0) return;
        if (text == null) text = "";
        text = text.trim();
        if (text.length() == 0) return;
        if (text.length() > MAX_ITEM_CHARS) text = text.substring(0, MAX_ITEM_CHARS);
        text = StyleGuard.strip(text);

        List<String[]> list = load(ctx);
        list.add(new String[]{role, text, String.valueOf(TimeContext.nowTruncatedToMinute())});
        trim(list);
        save(ctx, list);
    }

    /** 保存一份完整历史（ChatActivity 用），自动裁剪 */
    public static synchronized void save(Context ctx, List<String[]> list) {
        if (list == null) list = new ArrayList<>();
        trim(list);
        JSONArray arr = new JSONArray();
        try {
            // 无时间戳的旧二元组：按"越靠前的消息时间越早"倒推，而不是全填当前时间。
            // 以 2 分钟为一条对话的平均间隔估算，至少保证顺序和相对远近是正确的。
            final long nowTs = TimeContext.nowTruncatedToMinute();
            long missing = 0L;
            for (String[] h : list) if (h != null && h.length < 3) missing++;
            long firstTs = nowTs - Math.max(1L, missing) * 2 * 60_000L;
            long seq = 0L;
            for (String[] h : list) {
                if (h == null || h.length < 2) continue;
                String t = h[1] == null ? "" : h[1];
                if (t.length() > MAX_ITEM_CHARS) t = t.substring(0, MAX_ITEM_CHARS);
                JSONObject o = new JSONObject();
                o.put("r", h[0]);
                o.put("t", t);
                if (h.length >= 3 && h[2] != null && h[2].length() > 0) {
                    o.put("ts", h[2]);
                } else {
                    o.put("ts", String.valueOf(Math.min(nowTs, firstTs + (seq++) * 2 * 60_000L)));
                }
                arr.put(o);
            }
        } catch (Exception ignore) { }
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    /** 取最近 max 条作为请求上下文（最新的排在最后） */
    public static List<String[]> recent(Context ctx, int max) {
        List<String[]> all = load(ctx);
        if (max <= 0) return new ArrayList<>();
        if (all.size() <= max) return all;
        return new ArrayList<>(all.subList(all.size() - max, all.size()));
    }

    /** 清空记忆（历史 + 记忆点） */
    public static void clear(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_HISTORY).remove(KEY_FACTS).apply();
    }

    /** 记忆条数 */
    public static int size(Context ctx) {
        return load(ctx).size();
    }

    // ---------------- 主动发言去重 ----------------

    private static final String KEY_PROACTIVE_RECENT = "proactive_recent";
    /** 记录最近几条主动发言，用作"不要重复"的反例 */
    private static final int PROACTIVE_KEEP = 16;

    /** 读取最近主动说过的话（新→旧无关顺序，仅用于提示去重） */
    public static String proactiveRecent(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_PROACTIVE_RECENT, "");
    }

    /** 追加一条主动发言，自动裁剪到 PROACTIVE_KEEP 条 */
    public static synchronized void appendProactive(Context ctx, String line) {
        if (ctx == null || line == null) return;
        line = line.trim();
        if (line.length() == 0) return;
        if (line.length() > 60) line = line.substring(0, 60);
        java.util.List<String> list = new java.util.ArrayList<>();
        String old = proactiveRecent(ctx);
        if (old.length() > 0) {
            for (String x : old.split("\n")) {
                String t = x.trim();
                if (t.length() > 0) list.add(t);
            }
        }
        list.add(line);
        while (list.size() > PROACTIVE_KEEP) list.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String x : list) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(x);
        }
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PROACTIVE_RECENT, sb.toString()).apply();
    }

    /** 最近主动发言列表（新→旧） */
    public static java.util.List<String> proactiveLines(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String old = proactiveRecent(ctx);
        if (old == null || old.length() == 0) return out;
        for (String x : old.split("\n")) {
            String t = x.trim();
            if (t.length() > 0) out.add(StyleGuard.strip(t));
        }
        return out;
    }

    /**
     * 从最近对话里挑出「可以拿来开启话头」的用户钩子（最后几条 user 消息），
     * 供主动发言时参考。返回形如「用户上次提到：xxx」。无则返回 null。
     */
    public static String recentUserHooks(Context ctx, int maxHooks) {
        List<String[]> all = load(ctx);
        java.util.List<String> hooks = new java.util.ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && hooks.size() < maxHooks; i--) {
            String[] h = all.get(i);
            if (h == null || h.length < 2) continue;
            if (!"user".equals(h[0])) continue;
            String t = h[1] == null ? "" : h[1].trim();
            if (t.length() == 0) continue;
            if (t.length() > 40) t = t.substring(0, 40);
            // 跳过纯语气/极短词
            if (t.length() < 2) continue;
            hooks.add(t);
        }
        if (hooks.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = hooks.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append("「").append(hooks.get(i)).append("」");
        }
        return sb.toString();
    }

    /** 相似度：字符 Jaccard 与 二元组(bigram) Jaccard 取较大者，对「换个说法重复」更敏感 */
    public static double similarity(String a, String b) {
        if (a == null || b == null) return 0;
        a = a.replaceAll("[\\p{Punct}\\s]", "");
        b = b.replaceAll("[\\p{Punct}\\s]", "");
        if (a.length() == 0 || b.length() == 0) return 0;
        return Math.max(charJaccard(a, b), bigramJaccard(a, b));
    }

    private static double charJaccard(String a, String b) {
        java.util.Set<Character> sa = new java.util.HashSet<>();
        for (char c : a.toCharArray()) sa.add(c);
        java.util.Set<Character> sb = new java.util.HashSet<>();
        for (char c : b.toCharArray()) sb.add(c);
        int inter = 0;
        for (char c : sa) if (sb.contains(c)) inter++;
        int uni = sa.size() + sb.size() - inter;
        return uni == 0 ? 0 : (double) inter / uni;
    }

    private static double bigramJaccard(String a, String b) {
        java.util.Set<String> sa = bigrams(a);
        java.util.Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0;
        int inter = 0;
        for (String g : sa) if (sb.contains(g)) inter++;
        int uni = sa.size() + sb.size() - inter;
        return uni == 0 ? 0 : (double) inter / uni;
    }

    private static java.util.Set<String> bigrams(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        return out;
    }

    // ---------------- 主动发言：已问过的问题清单（去重） ----------------

    private static final String KEY_ASKED = "proactive_asked";
    private static final int ASKED_KEEP = 12;

    /** 追加一条「已经问过的问题」核心词，用于避免反复问同一件事 */
    public static synchronized void appendAsked(Context ctx, String line) {
        if (ctx == null || line == null) return;
        String q = extractQuestionCore(line);
        if (q == null || q.length() < 2) return;
        java.util.List<String> list = askedList(ctx);
        // 若与已有太像，先移除旧的，保持清单精简
        for (int i = list.size() - 1; i >= 0; i--) {
            if (similarity(list.get(i), q) >= 0.5) list.remove(i);
        }
        list.add(q);
        while (list.size() > ASKED_KEEP) list.remove(0);
        StringBuilder sb = new StringBuilder();
        for (String x : list) { if (sb.length() > 0) sb.append("\n"); sb.append(x); }
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_ASKED, sb.toString()).apply();
    }

    /** 已问过的问题核心词列表 */
    public static java.util.List<String> askedList(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        String old = sp.getString(KEY_ASKED, "");
        if (old == null || old.length() == 0) return out;
        for (String x : old.split("\n")) { String t = x.trim(); if (t.length() > 0) out.add(StyleGuard.strip(t)); }
        return out;
    }

    /** 本地判断：这句是否和「已问过的问题」太像（用于生成后兜底重试） */
    public static boolean similarToAsked(Context ctx, String line) {
        String q = extractQuestionCore(line);
        if (q == null || q.length() < 2) return false;
        for (String old : askedList(ctx)) {
            if (similarity(old, q) >= 0.5) return true;
        }
        return false;
    }

    /** 从一句话里提取「提问核心」（去掉疑问词/语气词），非疑问句返回整句去噪 */
    public static String extractQuestionCore(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.length() == 0) return null;
        t = t.replaceAll("[?？。！!~～\\s]", "");
        t = t.replaceAll("^(主人|你|那个|所以|那|请问|话说|诶|欸|哎|唉|对了)", "");
        t = t.replaceAll("(吗|呢|吧|啊|呀|嘛|哦|噢|了|的)$", "");
        return t.trim();
    }

    // ---------------- 主动发言：话题角度游标（保证相邻不重复） ----------------

    private static final String KEY_ANGLE = "proactive_last_angle";

    public static int lastAngle(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_ANGLE, -1);
    }

    public static void setLastAngle(Context ctx, int idx) {
        SharedPreferences sp = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        sp.edit().putInt(KEY_ANGLE, idx).apply();
    }

    private static void trim(List<String[]> list) {
        while (list.size() > MAX_ITEMS) list.remove(0);
    }
}
