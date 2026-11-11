package com.deepseek.pet;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.Locale;

/**
 * DeepSeek 开放平台（platform.deepseek.com）客户端。
 *
 * ★ 为什么需要它
 *   API Key 只能调 api.deepseek.com（余额、对话），拿不到「按天/按模型」的明细用量。
 *   官网的用量接口在 platform.deepseek.com 下，且只认网页登录态：
 *      GET /api/v0/usage/by_api_key/amount   按 Key×模型 的 token 用量
 *      GET /api/v0/usage/by_api_key/cost     按 Key×模型 的费用
 *      GET /api/v0/users/get_user_summary    总额度 / 本月用量 / 钱包余额
 *      GET /api/v0/users/get_api_keys        Key 列表
 *   统一参数 ?start=<秒>&end=<秒>&tz=<秒>，鉴权头 Authorization: Bearer <userToken>。
 *
 * ★ token 从哪来
 *   userToken 由官方登录页写入 WebView 的 localStorage，无法用 API Key 换。
 *   所以走 PlatformLoginActivity 内置 WebView 打开官方登录页，
 *   登录成功后由本类读出 token 并持久化。token 只存应用私有目录，只发往官方域名。
 */
public final class PlatformClient {

    private PlatformClient() {}

    public static final String HOST = "https://platform.deepseek.com";
    private static final String NAME = "deepseek_pet_platform";
    private static final String KEY_TOKEN  = "user_token";
    private static final String KEY_EXPIRE = "token_saved_at";

    // ==================== 登录态 ====================

    public static String token(Context ctx) {
        if (ctx == null) return "";
        String t = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                      .getString(KEY_TOKEN, "");
        return t == null ? "" : t;
    }

    public static void saveToken(Context ctx, String t) {
        if (ctx == null) return;
        String v = t == null ? "" : t.trim();
        while (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
           .putString(KEY_TOKEN, v)
           .putLong(KEY_EXPIRE, System.currentTimeMillis())
           .apply();
    }

    public static void clearToken(Context ctx) {
        if (ctx == null) return;
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit()
           .remove(KEY_TOKEN).remove(KEY_EXPIRE).apply();
    }

    public static boolean isLoggedIn(Context ctx) {
        return token(ctx).length() > 0;
    }

    /** 登录时间文案 */
    public static String loginTimeText(Context ctx) {
        long t = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                    .getLong(KEY_EXPIRE, 0);
        if (t <= 0) return "";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US)
                .format(new java.util.Date(t));
    }

    // ==================== HTTP ====================

    /** 调官方 JSON 接口，返回 data.biz_data 内的对象；失败抛异常 */
    private static JSONObject bizData(String pathWithQuery, String tk) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(HOST + pathWithQuery);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Authorization", "Bearer " + tk);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Referer", HOST + "/usage");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36");
            // ★ 网页端每个请求都会带这组 x-client-* 头（见前端 createStorageHandles 后的
            //   wrapRequest）。usage/* 这类接口会据此做参数校验：缺了它们会直接返回
            //   biz_msg=INVALID_PARAM，而 users/* 两个接口恰好不校验，所以之前出现
            //   「余额能读到、用量全空」的分裂现象。
            conn.setRequestProperty("x-client-bundle-id", "com.doropet.release");
            conn.setRequestProperty("x-client-platform", "web");
            conn.setRequestProperty("x-client-version", "1.0.0");
            conn.setRequestProperty("x-client-locale", "zh_CN");
            conn.setRequestProperty("x-client-timezone-offset",
                    String.valueOf(java.util.Calendar.getInstance().getTimeZone()
                            .getOffset(System.currentTimeMillis()) / 1000));
            conn.setRequestProperty("Origin", HOST);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            int code = conn.getResponseCode();
            String body = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            // 诊断日志：只记状态码与长度；响应体可能含 api key / 账户信息，发布版不打印。
            android.util.Log.i("DSPlatform", "GET " + pathWithQuery.substring(0,
                    Math.min(60, pathWithQuery.length()))
                    + " http=" + code + " len=" + body.length());
            JSONObject json = new JSONObject(body);
            int c = json.optInt("code", -1);
            if (c != 0) {
                String msg = json.optString("msg", "未知错误");
                android.util.Log.w("DSPlatform", "code=" + c + " msg=" + msg);
                throw new IllegalStateException(msg + " (code=" + c + ")");
            }
            JSONObject data = json.optJSONObject("data");
            if (data == null) throw new IllegalStateException("响应缺少 data（HTTP " + code + "）");
            JSONObject biz = data.optJSONObject("biz_data");
            return biz != null ? biz : data;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 日志用：过长就截断 */
    private static String shortStr(String s) {
        if (s == null) return "";
        return s.length() > 260 ? s.substring(0, 260) + "..." : s;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ==================== 时间区间 ====================

    /**
     * 与官网一致的时间换算：把本地时区偏移归到「整小时」。
     * tz = 28800（UTC+8）。返回 {startSec, endSec, tzSec}
     */
    public static long[] dayRange(int daysBack) {
        Calendar c = Calendar.getInstance();
        long off = c.getTimeZone().getOffset(System.currentTimeMillis()) / 1000L;
        long tzHour = 3600L * Math.floorDiv(off, 3600L);
        long rem = off - tzHour;
        // 今天 00:00（本地）= 当前时刻 - 当天已过秒数
        long nowSec = System.currentTimeMillis() / 1000L;
        long localNow = nowSec + off;
        long localMidnight = localNow - (localNow % 86400L);
        // ★ 官方接口要求 start / end 都是「本地零点对齐」的整日边界。
        //   实测（抓官网前端真实请求）：
        //     amount?start=1788019200&end=1790611200&tz=28800
        //   两个值都 ≡ -tz (mod 86400)，end 甚至是「明天零点」（未来时刻）。
        //   若把 end 传成当前时刻（未对齐），服务端直接回
        //     biz_code=1, biz_msg=INVALID_PARAM
        //   ——这正是「登录成功、余额正常，但用量永远读不到」的原因。
        long start = localMidnight - daysBack * 86400L - off;   // 本地零点
        long end   = localMidnight + 86400L - off;              // 本地明天零点
        return new long[]{ start + rem, end + rem, tzHour };
    }

    private static String q(long[] r) {
        return "?start=" + r[0] + "&end=" + r[1] + "&tz=" + r[2];
    }

    // ==================== 用量 ====================

    /** 一个时间桶的用量 */
    public static class Bucket {
        public long time;          // 桶起点（秒）
        public long hit;           // 缓存命中输入 token
        public long miss;          // 缓存未命中输入 token
        public long out;           // 输出 token
        public long requests;      // 请求次数
        public double cost;        // 费用
    }

    /** 某模型（或某 Key）的用量序列 */
    public static class Series {
        public String model;
        public String keyName;
        public String trackingId;
        public final java.util.List<Bucket> buckets = new java.util.ArrayList<Bucket>();

        public long sumHit()  { long s = 0; for (Bucket b : buckets) s += b.hit;  return s; }
        public long sumMiss() { long s = 0; for (Bucket b : buckets) s += b.miss; return s; }
        public long sumOut()  { long s = 0; for (Bucket b : buckets) s += b.out;  return s; }
        public long sumReq()  { long s = 0; for (Bucket b : buckets) s += b.requests; return s; }
        public double sumCost() { double s = 0; for (Bucket b : buckets) s += b.cost; return s; }
    }

    /** 一次完整用量查询结果 */
    public static class Usage {
        public int bucketMode;                 // 3600 / 86400 / 2592000
        public boolean officialCost;           // cost 明细是否成功解析
        public final java.util.List<Series> amount = new java.util.ArrayList<Series>();
        public final java.util.List<Series> cost   = new java.util.ArrayList<Series>();

        public long totalHit()  { long s = 0; for (Series x : amount) s += x.sumHit();  return s; }
        public long totalMiss() { long s = 0; for (Series x : amount) s += x.sumMiss(); return s; }
        public long totalOut()  { long s = 0; for (Series x : amount) s += x.sumOut();  return s; }
        public long totalReq()  { long s = 0; for (Series x : amount) s += x.sumReq();  return s; }
        public double totalCost() { double s = 0; for (Series x : cost) s += x.sumCost(); return s; }

        public long totalIn() { return totalHit() + totalMiss(); }
        public long totalTokens() { return totalIn() + totalOut(); }
    }

    /**
     * 拉取 [今天-daysBack, 现在] 的用量与费用。
     * 官网会自动选择分桶粒度（今天→小时，一周→天，一月→天/月）。
     */
    public static Usage fetchUsage(Context ctx, int daysBack) throws Exception {
        String tk = token(ctx);
        if (tk.length() == 0) throw new IllegalStateException("尚未登录开放平台");
        long[] r = dayRange(daysBack);
        Usage u = new Usage();

        JSONObject amount = bizData("/api/v0/usage/by_api_key/amount" + q(r), tk);
        u.bucketMode = amount.optInt("bucket", 86400);
        JSONArray series = amount.optJSONArray("series");
        if (series != null) {
            for (int i = 0; i < series.length(); i++) {
                JSONObject s = series.getJSONObject(i);
                Series se = new Series();
                parseKey(se, s.optJSONObject("api_key"));
                se.model = s.optString("model", "");
                JSONArray bs = s.optJSONArray("buckets");
                if (bs != null) {
                    for (int j = 0; j < bs.length(); j++) {
                        JSONObject b = bs.getJSONObject(j);
                        JSONObject us = b.optJSONObject("usage");
                        Bucket bk = new Bucket();
                        bk.time = parseTime(b.opt("time"));
                        if (us != null) {
                            bk.hit = us.optLong("PROMPT_CACHE_HIT_TOKEN", 0);
                            bk.miss = us.optLong("PROMPT_CACHE_MISS_TOKEN", 0);
                            bk.out = us.optLong("RESPONSE_TOKEN", 0);
                            bk.requests = us.optLong("REQUEST", 0);
                        }
                        se.buckets.add(bk);
                    }
                }
                u.amount.add(se);
            }
        }

        try {
            JSONObject costJ = bizData("/api/v0/usage/by_api_key/cost" + q(r), tk);
            // ★ cost 响应与 amount 同构：series 直接挂在 biz_data 顶层。
            //   旧代码在这里找 biz_data.data[] 数组（那是 payments/orders 一类接口的形状），
            //   于是 cost 永远解析不出来 → totalCost() 恒为 0 →「今日¥」一栏总是空的。
            //   为兼容起见仍然保留 data[].series 的兜底分支。
            java.util.List<JSONArray> groups = new java.util.ArrayList<JSONArray>();
            JSONArray direct = costJ.optJSONArray("series");
            if (direct != null) {
                groups.add(direct);
            } else {
                JSONArray nested = costJ.optJSONArray("data");
                if (nested != null) {
                    for (int i = 0; i < nested.length(); i++) {
                        JSONArray ss = nested.getJSONObject(i).optJSONArray("series");
                        if (ss != null) groups.add(ss);
                    }
                }
            }
            for (JSONArray ss : groups) {
                for (int k = 0; k < ss.length(); k++) {
                    JSONObject s = ss.getJSONObject(k);
                    Series se = new Series();
                    parseKey(se, s.optJSONObject("api_key"));
                    se.model = s.optString("model", "");
                    JSONArray bs = s.optJSONArray("buckets");
                    if (bs != null) {
                        for (int j = 0; j < bs.length(); j++) {
                            JSONObject b = bs.getJSONObject(j);
                            Bucket bk = new Bucket();
                            bk.time = parseTime(b.opt("time"));
                            bk.cost = b.optDouble("cost", 0);
                            se.buckets.add(bk);
                        }
                    }
                    u.cost.add(se);
                }
            }
        } catch (Exception ignore) {
            // 费用接口失败不影响用量展示
        }
        u.officialCost = true;   // cost 明细已合并（totalCost() 即官网权威金额）
        return u;
    }

    /** 账户总览 */
    public static class Summary {
        public double balance;        // 正常钱包余额合计
        public String currency = "CNY";
        public double bonus;          // 赠送余额合计
        public long totalUsage;       // 累计用量 token
        public long monthlyUsage;     // 本月用量 token
        public double totalCost;      // 累计花费
    }

    public static Summary fetchSummary(Context ctx) throws Exception {
        String tk = token(ctx);
        if (tk.length() == 0) throw new IllegalStateException("尚未登录开放平台");
        JSONObject b = bizData("/api/v0/users/get_user_summary", tk);
        Summary s = new Summary();
        s.totalUsage = b.optLong("total_usage", 0);
        s.monthlyUsage = b.optLong("monthly_usage", 0);
        JSONArray nw = b.optJSONArray("normal_wallets");
        if (nw != null) {
            for (int i = 0; i < nw.length(); i++) {
                JSONObject w = nw.getJSONObject(i);
                s.balance += w.optDouble("balance", 0);
                String c = w.optString("currency", "");
                if (c.length() > 0) s.currency = c;
            }
        }
        JSONArray bw = b.optJSONArray("bonus_wallets");
        if (bw != null) {
            for (int i = 0; i < bw.length(); i++) {
                s.bonus += bw.getJSONObject(i).optDouble("balance", 0);
            }
        }
        JSONArray tc = b.optJSONArray("total_costs");
        if (tc != null) {
            for (int i = 0; i < tc.length(); i++) {
                JSONObject c = tc.getJSONObject(i);
                if ("CNY".equals(c.optString("currency", ""))) s.totalCost += c.optDouble("amount", 0);
            }
        }
        return s;
    }

    /** Key 列表（tracking_id → 显示名） */
    public static java.util.Map<String, String> fetchApiKeys(Context ctx) throws Exception {
        java.util.Map<String, String> m = new java.util.HashMap<String, String>();
        String tk = token(ctx);
        if (tk.length() == 0) return m;
        JSONObject b = bizData("/api/v0/users/get_api_keys", tk);
        JSONArray arr = b.optJSONArray("api_keys");
        if (arr == null) return m;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject k = arr.getJSONObject(i);
            JSONObject sd = k.optJSONObject("sensitive_data");
            String track = sd != null ? sd.optString("tracking_id", "") : "";
            if (track.length() == 0) track = k.optString("tracking_id", "");
            String name = k.optString("name", "");
            if (name.length() == 0) {
                JSONObject d = k.optJSONObject("display_data");
                if (d != null) name = d.optString("name", "");
            }
            if (track.length() > 0) m.put(track, name);
        }
        return m;
    }

    private static void parseKey(Series se, JSONObject k) {
        if (k == null) return;
        se.trackingId = k.optString("tracking_id", "");
        se.keyName = k.optString("name", "");
    }

    /** time 字段可能是秒数或毫秒数 */
    private static long parseTime(Object o) {
        if (o == null) return 0;
        long v;
        if (o instanceof Number) v = ((Number) o).longValue();
        else {
            try { v = Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
        }
        if (v > 100000000000L) v = v / 1000L;   // 毫秒 → 秒
        return v;
    }

    // ==================== 文案 ====================

    /** 分桶粒度中文名 */
    public static String bucketName(int b) {
        if (b <= 3600) return "小时";
        if (b >= 2592000) return "月";
        return "天";
    }

    public static String money(double v, String cur) {
        String sym = "USD".equals(cur) ? "$" : "¥";
        return sym + String.format(Locale.US, "%.2f", v);
    }
}
