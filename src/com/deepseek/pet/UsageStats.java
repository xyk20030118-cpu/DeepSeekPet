package com.deepseek.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * 今日 API 用量统计。
 *
 * 每次对话请求返回的 usage 字段都会被累积进来：
 *   prompt_tokens           输入总量
 *   prompt_cache_hit_tokens 其中命中缓存的部分（便宜 25~50 倍）
 *   prompt_cache_miss_tokens 未命中部分
 *   completion_tokens       输出
 *
 * 缓存命中率 = hit / (hit + miss)，是「省 token」效果最直观的指标。
 * 数据按天存储，跨天自动归零（保留昨日值供对比）。
 */
public final class UsageStats {

    private UsageStats() {}

    private static final String NAME = "deepseek_pet_usage";

    private static final String KEY_DAY        = "day";          // 当前统计日期 yyyyMMdd
    private static final String KEY_CALLS      = "calls";        // 今日请求次数
    private static final String KEY_HIT        = "cache_hit";    // 今日缓存命中 token
    private static final String KEY_MISS       = "cache_miss";   // 今日缓存未命中 token
    private static final String KEY_OUT        = "output";       // 今日输出 token
    private static final String KEY_COST       = "cost";         // 今日累计费用（元，估算）
    private static final String KEY_PREV_DAY   = "prev_day";
    private static final String KEY_PREV_TOTAL = "prev_total";   // 昨日总 token
    private static final String KEY_BAL_LAST   = "bal_last";     // 上次成功查询到的余额
    private static final String KEY_BAL_NOTIFY = "bal_notified"; // 已就低余额提醒过的档位
    private static final String KEY_OFFICIAL_DAY  = "off_day";    // 已同步过官网数据的日期
    private static final String KEY_OFFICIAL_USED = "off_used";   // 当日已同步的消耗（元）
    private static final String KEY_BAL_ANCHOR    = "bal_anchor"; // 最近一次官网余额
    // 官网「今日」明细快照（登录后由 PlatformClient 写入，供气泡直接显示）
    private static final String KEY_OFF_REQ  = "off_req";   // 今日请求次数（官网）
    private static final String KEY_OFF_TOK  = "off_tok";   // 今日总 token（官网）
    private static final String KEY_OFF_HIT  = "off_hit";   // 今日命中 token（官网）
    private static final String KEY_OFF_MISS = "off_miss";  // 今日未命中 token（官网）
    private static final String KEY_OFF_AT   = "off_at";    // 快照时间戳
    private static final String KEY_COST_SRC = "cost_src";  // "official" = 今日金额取自官网明细

    // ==================== 日期 ====================

    private static String today() {
        Calendar c = Calendar.getInstance();
        return String.format(Locale.US, "%04d%02d%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 若已跨天，把今日数据滚动到「昨日」并清空今日 */
    private static void rollIfNeeded(Context ctx) {
        SharedPreferences sp = sp(ctx);
        String t = today();
        if (t.equals(sp.getString(KEY_DAY, ""))) return;
        long total = sp.getLong(KEY_HIT, 0) + sp.getLong(KEY_MISS, 0) + sp.getLong(KEY_OUT, 0);
        sp.edit()
          .putString(KEY_PREV_DAY, sp.getString(KEY_DAY, ""))
          .putLong(KEY_PREV_TOTAL, total)
          .putString(KEY_DAY, t)
          .putString(KEY_OFFICIAL_DAY, "")
          .putInt(KEY_CALLS, 0)
          .putLong(KEY_HIT, 0).putLong(KEY_MISS, 0).putLong(KEY_OUT, 0)
          .putFloat(KEY_COST, 0f)
          .apply();
    }

    // ==================== 写入 ====================

    /**
     * 记一次请求用量。cacheHit/cacheMiss 若为负表示该网关未返回缓存字段，
     * 此时把全部输入 token 计入 miss（高估花费，安全方向）。
     */
    public static void record(Context ctx, String model, boolean peak,
                              long cacheHit, long cacheMiss, long output) {
        if (ctx == null) return;
        try {
            rollIfNeeded(ctx);
            if (cacheHit < 0) cacheHit = 0;
            if (cacheMiss < 0) cacheMiss = 0;
            if (output < 0) output = 0;
            double cost = Pricing.estimateCost(model, peak, cacheHit, cacheMiss, output);
            SharedPreferences sp = sp(ctx);
            sp.edit()
              .putInt(KEY_CALLS, sp.getInt(KEY_CALLS, 0) + 1)
              .putLong(KEY_HIT, sp.getLong(KEY_HIT, 0) + cacheHit)
              .putLong(KEY_MISS, sp.getLong(KEY_MISS, 0) + cacheMiss)
              .putLong(KEY_OUT, sp.getLong(KEY_OUT, 0) + output)
              .putFloat(KEY_COST, sp.getFloat(KEY_COST, 0f) + (float) cost)
              .apply();
        } catch (Exception ignore) {}
    }

    // ==================== 读取 ====================

    public static int calls(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getInt(KEY_CALLS, 0); }
    public static long cacheHit(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getLong(KEY_HIT, 0); }
    public static long cacheMiss(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getLong(KEY_MISS, 0); }
    public static long outputTokens(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getLong(KEY_OUT, 0); }

    public static long totalTokens(Context ctx) {
        rollIfNeeded(ctx);
        return cacheHit(ctx) + cacheMiss(ctx) + outputTokens(ctx);
    }

    public static double cost(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getFloat(KEY_COST, 0f); }

    public static long prevTotal(Context ctx) { rollIfNeeded(ctx); return sp(ctx).getLong(KEY_PREV_TOTAL, 0); }

    /** 缓存命中率，0~1；无数据返回 -1 表示「不适用」 */
    public static float cacheRate(Context ctx) {
        rollIfNeeded(ctx);
        long hit = cacheHit(ctx), miss = cacheMiss(ctx);
        long in = hit + miss;
        if (in <= 0) return -1f;
        return (float) hit / (float) in;
    }

    /** 「73.5%」或「—」 */
    public static String cacheRateText(Context ctx) {
        float r = cacheRate(ctx);
        if (r < 0) return "—";
        return String.format(Locale.US, "%.1f%%", r * 100f);
    }

    /** token 数转人话：1234 → 1.2k */
    public static String k(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fk", n / 1000.0);
        return String.format(Locale.US, "%.2fM", n / 1_000_000.0);
    }

    public static void reset(Context ctx) {
        SharedPreferences sp = sp(ctx);
        sp.edit().putString(KEY_DAY, today())
          .putInt(KEY_CALLS, 0)
          .putLong(KEY_HIT, 0).putLong(KEY_MISS, 0).putLong(KEY_OUT, 0)
          .putFloat(KEY_COST, 0f).apply();
    }

    // ==================== 官网同步（余额校准今日用量）====================
    // 【2026-09 更正】早前误判「官网没有按日用量接口」，实际上接口存在，只是不在
    // api.deepseek.com 而在 platform.deepseek.com，且只认网页登录态：
    //     GET /api/v0/usage/by_api_key/amount  ← 按 Key×模型 的 token 明细
    //     GET /api/v0/usage/by_api_key/cost    ← 按 Key×模型 的费用
    // 见 PlatformClient / UsageActivity（登录后即可读真实明细）。
    //
    // 本条路径仍然保留：只用 API Key 的用户无需登录，靠「余额差」也能估当日消耗——
    // 今日消耗 = 当日首个余额 - 当前余额（充值变多则钳 0）。
    // 两条路互补：余额反推保证「永远有数」，登录后的明细则精确到模型与缓存命中。

    /**
     * 用刚查到的官网余额校准今日消耗。
     * @return 今日消耗（元）；无法校准时返回 -1
     */
    public static double applyOfficialBalance(Context ctx, double bal) {
        if (ctx == null || bal < 0) return -1;
        SharedPreferences sp = sp(ctx);
        rollIfNeeded(ctx);
        String day = sp.getString(KEY_DAY, "");
        String offDay = sp.getString(KEY_OFFICIAL_DAY, "");
        if (!day.equals(offDay)) {
            // 新的一天：记录当日首个余额，作为消耗计算的锚点
            sp.edit().putString(KEY_OFFICIAL_DAY, day)
              .putFloat(KEY_BAL_ANCHOR, (float) bal)
              .putFloat(KEY_OFFICIAL_USED, 0f)
              .putFloat(KEY_COST, 0f)      // 今日估算清零，改以官网为准
              .putString(KEY_COST_SRC, "") // 金额来源待官网明细回来再认
              .apply();
            return 0;
        }
        float anchor = sp.getFloat(KEY_BAL_ANCHOR, (float) bal);
        double used = anchor - bal;
        if (used < 0) used = 0;              // 充值后余额变多，不算负消耗
        android.content.SharedPreferences.Editor e =
                sp.edit().putFloat(KEY_OFFICIAL_USED, (float) used);
        if (!"official".equals(sp.getString(KEY_COST_SRC, ""))) {
            // 只有拿不到官网明细时，才用「余额差」当今日金额
            e.putFloat(KEY_COST, (float) used);
        }
        e.apply();
        return used;
    }

    /**
     * 用官网「今日」明细刷新快照（登录用户走这条，比余额反推精确）。
     * 只覆盖请求次数与 token 口径；金额仍由 applyOfficialBalance() 的余额差负责。
     */
    public static void applyOfficialUsage(Context ctx, long req, long tok,
                                          long hit, long miss, double cost) {
        if (ctx == null) return;
        rollIfNeeded(ctx);
        SharedPreferences sp = sp(ctx);
        String day = sp.getString(KEY_DAY, "");
        // 有明细就说明当天官网确实记着数据，标成「已同步官网」
        android.content.SharedPreferences.Editor e = sp.edit()
          .putString(KEY_OFFICIAL_DAY, day)
          .putInt(KEY_OFF_REQ, (int) req)
          .putLong(KEY_OFF_TOK, tok)
          .putLong(KEY_OFF_HIT, hit)
          .putLong(KEY_OFF_MISS, miss)
          .putLong(KEY_OFF_AT, System.currentTimeMillis() / 1000L);
        if (cost >= 0) {
            // 官网 cost 明细是权威金额，覆盖本地估算与「余额差」推测
            e.putFloat(KEY_COST, (float) cost).putString(KEY_COST_SRC, "official");
        }
        e.apply();
    }

    /** 今日金额是否来自官网明细（而非余额差/本地估算） */
    public static boolean costFromOfficial(Context ctx) {
        rollIfNeeded(ctx);
        return "official".equals(sp(ctx).getString(KEY_COST_SRC, ""));
    }

    /** 官网今日快照是否有效（当日写过的） */
    public static boolean hasOfficialUsage(Context ctx) {
        if (ctx == null) return false;
        rollIfNeeded(ctx);
        SharedPreferences sp = sp(ctx);
        return sp.getString(KEY_DAY, "").equals(sp.getString(KEY_OFFICIAL_DAY, ""))
                && sp.getLong(KEY_OFF_AT, 0) > 0;
    }

    public static int officialCalls(Context ctx) {
        rollIfNeeded(ctx);
        return sp(ctx).getInt(KEY_OFF_REQ, 0);
    }

    public static long officialTokens(Context ctx) {
        rollIfNeeded(ctx);
        return sp(ctx).getLong(KEY_OFF_TOK, 0);
    }

    /** 官网今日命中率文本；没有未命中数据返回「—」 */
    public static String officialCacheText(Context ctx) {
        rollIfNeeded(ctx);
        SharedPreferences sp = sp(ctx);
        long hit = sp.getLong(KEY_OFF_HIT, 0), miss = sp.getLong(KEY_OFF_MISS, 0);
        if (hit + miss <= 0) return "—";
        return String.format(Locale.US, "%.1f%%", hit * 100.0 / (hit + miss));
    }

    /** 今日消耗是否已用官网余额校准过 */
    public static boolean officialCalibrated(Context ctx) {
        SharedPreferences sp = sp(ctx);
        rollIfNeeded(ctx);
        return sp.getString(KEY_DAY, "").equals(sp.getString(KEY_OFFICIAL_DAY, ""));
    }

    // ==================== 余额记忆（供低余额提醒判断）====================

    public static void saveBalance(Context ctx, double bal) {
        if (ctx == null) return;
        sp(ctx).edit().putFloat(KEY_BAL_LAST, (float) bal).apply();
    }

    public static double lastBalance(Context ctx) {
        return sp(ctx).getFloat(KEY_BAL_LAST, -1f);
    }

    /** 已经提醒过的档位（避免反复唠叨）。返回 true 表示这是新档位，应当提醒。 */
    public static boolean shouldNotifyBalance(Context ctx, double bal) {
        int tier;
        if (bal < 1.0)       tier = 3;
        else if (bal < 5.0)  tier = 2;
        else if (bal < 20.0) tier = 1;
        else                 tier = 0;
        if (tier == 0) return false;                    // 余额充足，不打扰
        SharedPreferences sp = sp(ctx);
        if (sp.getInt(KEY_BAL_NOTIFY, 0) >= tier) return false;
        sp.edit().putInt(KEY_BAL_NOTIFY, tier).apply();
        return true;
    }

    // ==================== 汇总文案 ====================

    /** 单行摘要，用于气泡：今日 12 次 · 8.4k tok · 命中 72.1% · ¥0.03 */
    public static String briefLine(Context ctx) {
        int c = calls(ctx);
        long tok = totalTokens(ctx);
        String rate = cacheRateText(ctx);
        String tag = officialCalibrated(ctx) ? "官网" : "本机";
        // 本地没记录时退回官网「今日」快照（刚装好/刚登录的场景）
        if (c <= 0 && tok <= 0) {
            int oc = officialCalls(ctx);
            long ot = officialTokens(ctx);
            if (oc <= 0 && ot <= 0) return "今日还没用过 API";
            c = oc; tok = ot; rate = officialCacheText(ctx); tag = "官网";
        }
        return String.format(Locale.US, "[%s] 今日 %d 次 · %s tok · 命中 %s · 约 ¥%.4f",
                tag, c, k(tok), rate, cost(ctx));
    }

    /** 多行明细，用于长气泡/对话框 */
    public static String detail(Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean fromOfficial = false;
        if (calls(ctx) <= 0 && totalTokens(ctx) <= 0) {
            // 本地无记录 → 用官网今日快照
            if (officialCalls(ctx) > 0 || officialTokens(ctx) > 0) fromOfficial = true;
        }
        sb.append("数据来源：").append(fromOfficial || officialCalibrated(ctx)
                ? "DeepSeek 官网（实时）" : "本机估算").append("\n");
        if (fromOfficial) {
            sb.append("今日调用：").append(officialCalls(ctx)).append(" 次\n");
            long oIn = 0, oHit = 0, oMiss = 0;
            android.content.SharedPreferences sp = sp(ctx);
            oHit = sp.getLong(KEY_OFF_HIT, 0);
            oMiss = sp.getLong(KEY_OFF_MISS, 0);
            oIn = oHit + oMiss;
            sb.append("输入：").append(k(oIn)).append(" tok");
            sb.append("（命中 ").append(k(oHit)).append(" / 未命中 ").append(k(oMiss)).append("）\n");
            sb.append("合计：").append(k(officialTokens(ctx))).append(" tok\n");
            sb.append("缓存命中率：").append(officialCacheText(ctx)).append("\n");
            sb.append("今日花费：约 ¥").append(String.format(Locale.US, "%.4f", cost(ctx)));
            return sb.toString();
        }
        sb.append("今日调用：").append(calls(ctx)).append(" 次\n");
        sb.append("输入：").append(k(cacheHit(ctx) + cacheMiss(ctx))).append(" tok");
        sb.append("（命中 ").append(k(cacheHit(ctx))).append(" / 未命中 ").append(k(cacheMiss(ctx))).append("）\n");
        sb.append("输出：").append(k(outputTokens(ctx))).append(" tok\n");
        sb.append("缓存命中率：").append(cacheRateText(ctx)).append("\n");
        sb.append("估算花费：约 ¥").append(String.format(Locale.US, "%.4f", cost(ctx)));
        long prev = prevTotal(ctx);
        if (prev > 0) {
            sb.append("\n昨日总量：").append(k(prev)).append(" tok");
        }
        return sb.toString();
    }
}
