package com.deepseek.pet;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 调用 DeepSeek 官方接口：
 *   GET  /user/balance
 *   POST /chat/completions
 */
public class ApiClient {

    /** 应用上下文（用于读取模型/推理强度等偏好）。由 init() 注入。 */
    private static volatile android.content.Context appCtx = null;
    public static void init(android.content.Context ctx) {
        if (ctx != null) appCtx = ctx.getApplicationContext();
    }

    /**
     * 单次读取超时（毫秒）。默认 30s 适合日常短对话，
     * 但 stream=false 时服务端要整篇生成完才回首个字节，
     * 人格蒸馏这类上万字的生成必须临时调大，否则必然 SocketTimeout。
     * 用完记得改回来。
     */
    private static volatile int readTimeoutMs = 30000;
    public static void setReadTimeout(int ms) { if (ms > 0) readTimeoutMs = ms; }
    public static int getReadTimeout() { return readTimeoutMs; }

    /** 自定义 API 基地址（默认 DeepSeek 官方）。由 setBaseUrl() 注入，去掉尾部斜杠。 */
    private static volatile String BASE_URL = "https://api.deepseek.com";
    public static void setBaseUrl(String base) {
        String b = (base == null || base.trim().length() == 0) ? "https://api.deepseek.com" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        BASE_URL = b;
    }
    public static String getBaseUrl() { return BASE_URL; }
    /** 对话补全地址：兼容用户填 https://x.com 或 https://x.com/v1 两种写法 */
    public static String chatUrl() {
        String b = BASE_URL;
        if (b.endsWith("/chat/completions")) return b;
        return b + "/chat/completions";
    }
    /** 余额查询地址见 {@link #balanceUrlFor}（按服务商不同，不再是 DeepSeek 专有） */

    /** 模型列表地址（OpenAI 兼容 /models） */
    public static String modelsUrl() {
        String b = BASE_URL;
        if (b.endsWith("/models")) return b;
        return b + "/models";
    }

    /** 从 /models 拉取到的模型信息 */
    public static class ModelInfo {
        public String id;
        public String name;          // 友好名，可能为空
        public int contextWindow;    // 0 表示未知
        public String defaultEffort; // 默认推理强度，可能为空
        public java.util.List<String> effortLevels = new java.util.ArrayList<String>();
        @Override public String toString() { return (name != null && name.length() > 0) ? name : id; }
    }

    /** 拉取可用模型列表；失败时返回空列表（不是 null）。 */
    public static java.util.List<ModelInfo> fetchModels(String apiKey) {
        java.util.List<ModelInfo> out = new java.util.ArrayList<ModelInfo>();
        HttpResult hr = httpGet(modelsUrl(), apiKey, 10000);
        lastModelsError = hr.describe();
        if (!hr.ok()) return out;
        try {
            JSONObject json = new JSONObject(hr.body);
            JSONArray arr = json.optJSONArray("data");
            if (arr == null) {
                lastModelsError = "返回的 JSON 里没有 data 数组（可能不是 OpenAI 兼容接口）："
                        + snippet(hr.body);
                return out;
            }
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ModelInfo m = new ModelInfo();
                m.id = o.optString("id", "");
                if (m.id.length() == 0) continue;
                m.name = o.optString("name", "");
                m.contextWindow = o.optInt("context_window", 0);
                JSONObject effort = o.optJSONObject("effort");
                if (effort != null) {
                    m.defaultEffort = effort.optString("default_level", "");
                    JSONArray lv = effort.optJSONArray("supported_levels");
                    if (lv != null) {
                        for (int k = 0; k < lv.length(); k++) {
                            String v = lv.optString(k, "");
                            if (v.length() > 0) m.effortLevels.add(v);
                        }
                    }
                }
                out.add(m);
            }
            if (out.isEmpty()) lastModelsError = "接口通了，但模型列表是空的";
        } catch (Exception e) {
            lastModelsError = "解析模型列表失败：" + e;
        }
        return out;
    }

    // ==================== 「测试连接」+ 失败原因（轮次56）====================

    /**
     * 上一次拉取模型列表的**真实失败原因**（成功时为空串）。
     *
     * <p>★ 为什么要有它：原来 {@code fetchModels} 是 {@code catch (Exception ignore) {}} ——
     * 异常被整个吞掉，用户只看到"获取模型失败（可能是网络问题）"，看不到
     * "CLEARTEXT communication … not permitted" / "Connection refused" 这种**自己能修**的原因。
     * 而用户报的"自定义 API 用不了"恰恰就卡在这一步。
     */
    private static volatile String lastModelsError = "";
    public static String modelsError() { return lastModelsError; }

    /** 一次 GET 的原始结果（状态码 / 正文 / 异常原文），不做任何业务解释 */
    public static class HttpResult {
        public int code;             // 0 = 没拿到响应（见 error）
        public String body = "";
        public String error = "";    // 异常原文（含 CLEARTEXT 这类平台策略错误）

        public boolean ok() { return code >= 200 && code < 300; }

        /** 给用户看的一句话（真实原因，不是笼统的"网络错误"） */
        public String describe() {
            if (error.length() > 0) return error;
            if (ok()) return "";
            return "HTTP " + code + (body.length() > 0 ? "：" + snippet(body) : "");
        }
    }

    /** 正文片段（弹窗/日志里只摆一小段，避免刷屏） */
    public static String snippet(String s) {
        if (s == null) return "";
        String t = s.trim().replace('\n', ' ').replace('\r', ' ');
        return t.length() <= 200 ? t : (t.substring(0, 200) + "…");
    }

    /** 一次 GET（供 /models 与「测试连接」共用）；**不抛异常**，把原因放进结果里 */
    public static HttpResult httpGet(String url, String apiKey, int timeoutMs) {
        HttpResult r = new HttpResult();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && apiKey.trim().length() > 0) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            }
            r.code = conn.getResponseCode();
            InputStream is = (r.code >= 200 && r.code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            r.body = readAll(is);
        } catch (Exception e) {
            // ★ 这里**必须**把异常原文留住：明文被禁、连接被拒、DNS 失败都在这条路上
            String m = e.getMessage();
            r.error = (m == null || m.length() == 0)
                    ? (e.getClass().getSimpleName() + "（没有详细信息）")
                    : (e.getClass().getSimpleName() + "：" + m);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    /** 「测试连接」的结果：地址 + 一次 /models 往返 + 给用户的建议 */
    public static class Probe {
        public String url;
        public HttpResult http;
        public String advice;        // Endpoint.advice（地址本身有问题时的原话）
        public int modelCount;

        public boolean ok() { return http != null && http.ok() && modelCount > 0; }
    }

    /**
     * **测试连接**：拿 {@code /models} 做一次最小往返，把真实结果原样带回来。
     * <p>不改任何状态（不写盘、不切换服务商）—— 纯诊断。
     */
    public static Probe probeModels(String apiKey) {
        Probe p = new Probe();
        p.url = modelsUrl();
        p.advice = Endpoint.advice(getBaseUrl());
        if (apiKey == null || apiKey.trim().length() == 0) {
            p.http = new HttpResult();
            p.http.error = "还没有填 API Key";
            return p;
        }
        p.http = httpGet(p.url, apiKey, 8000);
        if (p.http.ok()) {
            try {
                JSONArray arr = new JSONObject(p.http.body).optJSONArray("data");
                p.modelCount = (arr == null) ? 0 : arr.length();
            } catch (Throwable ignore) {}
        }
        return p;
    }

    /** 余额查询地址见 {@link #balanceUrlFor}（按服务商不同，不再是 DeepSeek 专有） */

    /** 查询结果 */
    public static class Result {
        public boolean ok;
        public double totalBalance;   // 总余额
        public String currency;       // 币种
        public String error;          // 失败信息

        static Result ok(double bal, String cur) {
            Result r = new Result();
            r.ok = true;
            r.totalBalance = bal;
            r.currency = cur;
            return r;
        }

        static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.error = msg;
            return r;
        }
    }

    /**
     * 余额查询地址。
     *
     * <p>★ 各家接口完全不同，不能再写死 DeepSeek 的路径 ——
     * 这正是用户报的"Kimi 无法显示余额"。
     * 路径与取值字段全部来自官方文档实测，见 {@link ChatProvider} 的条目定义。
     *
     * <p>以 {@code /} 开头的路径视为**绝对路径**（不再拼 baseUrl）——
     * 智谱的余额走的是 biz 网关，与 {@code /api/paas/v4} 不同层，必须这样处理。
     */
    public static String balanceUrlFor(ChatProvider.Entry e) {
        if (e == null || !e.hasBalance()) return null;
        return ChatBalance.buildUrl(BASE_URL, e.balancePath);
    }

    /** 兼容旧调用：DeepSeek 的余额地址 */
    public static String balanceUrl() {
        return balanceUrlFor(ChatProvider.find("deepseek"));
    }

    /**
     * 按 {@code a.b.c} 形式的路径从 JSON 里取值。
     * 实现已移到 {@link ChatBalance#pickNumber}（纯 Java，可离线断言）。
     */
    static double pickNumber(JSONObject root, String path) {
        return ChatBalance.pickNumber(root, path);
    }

    /**
     * 查询当前服务商的余额。
     *
     * @return ok=true 时 {@code totalBalance} 有效；否则 {@code error} 给出原因
     */
    public static Result getBalance(String apiKey) {
        android.content.Context ctx = appCtx;
        ChatProvider.Entry e = ChatProvider.find(
                ctx == null ? "deepseek" : Prefs.getChatProvider(ctx));
        return getBalance(apiKey, e);
    }

    public static Result getBalance(String apiKey, ChatProvider.Entry e) {
        if (apiKey == null || apiKey.trim().length() == 0) {
            return Result.fail("未设置 API Key");
        }
        if (e == null || !e.hasBalance()) {
            // ★ 明确区分"该服务商没有这个接口"与"查询失败"。
            //   给用户看"查询失败"会让他们以为是自己配错了，反复折腾。
            return Result.fail("该服务商未提供余额接口");
        }
        String urlStr = balanceUrlFor(e);
        if (urlStr == null) return Result.fail("该服务商未提供余额接口");

        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Accept", "application/json");
            // ★ 认证方式按服务商：智谱国内站要求裸 Key，带 Bearer 会 401
            conn.setRequestProperty("Authorization",
                    (e.balanceRawAuth ? "" : "Bearer ") + apiKey.trim());

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);

            // ★ 错误信息带上**实际请求的地址**。
            //   用户报过"余额获取失败：HTTP 404"，但光看 404 无法知道
            //   是哪条路径错了（可能只是 API 地址里多填了一段）。
            //   把 URL 打出来，问题一眼可辨，不必再靠猜。
            String where = "（" + urlStr + "）";

            if (code == 401 || code == 403) {
                return Result.fail("Key 无效(" + code + ")"
                        + ("kimi".equals(e.id)
                           ? "；Kimi 的 .ai 与 .com 平台 Key 不通用，请确认地址与 Key 同源" : "")
                        + where);
            }
            if (code == 404) {
                return Result.fail("HTTP 404 接口不存在" + where
                        + "；请检查「API 地址」是否多填了路径段");
            }
            if (code < 200 || code >= 300) {
                return Result.fail("HTTP " + code + where);
            }

            JSONObject json = new JSONObject(body);
            // ★ 有些服务商 HTTP 返回 200，但体内带业务错误码。
            //   实测智谱 biz 端点就是这样：
            //     HTTP 200 + {"code":401,"msg":"令牌已过期或验证不正确","success":false}
            //   只看 HTTP 状态会把"Key 无效"误判成"无余额数据"。
            if (ChatBalance.hasBusinessError(json)) {
                String msg = ChatBalance.firstNonEmpty(json, "msg", "message", "error");
                return Result.fail("接口返回错误"
                        + (msg.length() > 0 ? "：" + msg : "") + where);
            }
            double total = pickNumber(json, e.balanceField);
            if (Double.isNaN(total)) {
                // DeepSeek 返回多币种数组，优先 CNY —— 单独兜一层
                Double alt = deepseekCnyFallback(e, json);
                if (alt != null) return Result.ok(alt, "CNY");
                return Result.fail("无余额数据（字段 " + e.balanceField + "）" + where);
            }
            return Result.ok(total, e.balanceCurrency);
        } catch (Exception ex) {
            return Result.fail("网络错误：" + ex.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** DeepSeek 的 balance_infos 是多币种数组，优先取 CNY */
    private static Double deepseekCnyFallback(ChatProvider.Entry e, JSONObject json) {
        if (!"deepseek".equals(e.id)) return null;
        try {
            JSONArray infos = json.optJSONArray("balance_infos");
            if (infos == null || infos.length() == 0) return null;
            for (int i = 0; i < infos.length(); i++) {
                JSONObject o = infos.optJSONObject(i);
                if (o != null && "CNY".equalsIgnoreCase(o.optString("currency"))) {
                    return Double.valueOf(o.optString("total_balance", "0"));
                }
            }
            JSONObject first = infos.optJSONObject(0);
            if (first != null) return Double.valueOf(first.optString("total_balance", "0"));
        } catch (Exception ignore) {}
        return null;
    }

    /** 对话结果 */
    public static class ChatResult {
        public boolean ok;
        public String reply;
        public String memeName;              // 模型选中的配图名（无则 null）
        public String error;
        // —— 本次调用的用量（网关未返回时为 0 / -1）——
        public long promptTokens = 0;        // 输入 token 总量
        public long cacheHitTokens = -1;     // 命中缓存部分；-1 = 本网关未提供
        public long cacheMissTokens = -1;    // 未命中部分
        public long completionTokens = 0;    // 输出 token
        public long totalTokens = 0;
        public boolean usageParsed = false;  // 是否成功解析出 usage
    }

    /**
     * 解析响应里的 usage 段。
     * DeepSeek 官方返回 prompt_cache_hit_tokens / prompt_cache_miss_tokens；
     * 部分第三方网关只给 prompt_tokens，此时把全部输入算作未命中（高估花费，偏安全）。
     */
    private static void parseUsage(JSONObject json, ChatResult r) {
        try {
            JSONObject u = json.optJSONObject("usage");
            if (u == null) return;
            r.promptTokens     = u.optLong("prompt_tokens", 0);
            r.completionTokens = u.optLong("completion_tokens", 0);
            r.totalTokens      = u.optLong("total_tokens", r.promptTokens + r.completionTokens);
            if (u.has("prompt_cache_hit_tokens") || u.has("prompt_cache_miss_tokens")) {
                r.cacheHitTokens  = u.optLong("prompt_cache_hit_tokens", 0);
                r.cacheMissTokens = u.optLong("prompt_cache_miss_tokens", 0);
                long sum = r.cacheHitTokens + r.cacheMissTokens;
                if (sum <= 0 && r.promptTokens > 0) {   // 字段存在但都是 0，退回总量
                    r.cacheHitTokens = 0;
                    r.cacheMissTokens = r.promptTokens;
                }
            } else if (r.promptTokens > 0) {
                // 网关没给缓存细分：保守地全算未命中
                r.cacheHitTokens  = 0;
                r.cacheMissTokens = r.promptTokens;
            }
            r.usageParsed = true;
        } catch (Exception ignore) {}
    }

    /** 把握手到的用量写进今日统计（含按时段单价估算的费用） */
    private static void recordUsage(String model, ChatResult r) {
        try {
            if (r == null || !r.usageParsed) return;
            android.content.Context ctx = appCtx;
            if (ctx == null) return;
            long hit  = r.cacheHitTokens  < 0 ? 0 : r.cacheHitTokens;
            long miss = r.cacheMissTokens < 0 ? r.promptTokens : r.cacheMissTokens;
            UsageStats.record(ctx, model, Pricing.isPeak(), hit, miss, r.completionTokens);
        } catch (Exception ignore) {}
    }

    /** 兼容旧调用：不带记忆点 */
    public static ChatResult chat(String apiKey, String model, String persona,
                                  java.util.List<String[]> history, String userMsg) {
        return chat(apiKey, model, persona, null, history, userMsg);
    }

    /**
     * 带「主动记忆点」的对话。
     * factsPrompt 作为一条轻量 system 消息注入（排在 persona 之后、历史之前），
     * 即使 history 被裁剪，关键事实也能被记住。
     */
    public static ChatResult chat(String apiKey, String model, String persona,
                                  String factsPrompt,
                                  java.util.List<String[]> history, String userMsg) {
        // 普通对话：temperature 1.3，无额外 system 段
        return chatFull(apiKey, model, persona, factsPrompt, null, history, userMsg, 1.3);
    }

    /**
     * 主动发言专用：可以额外注入一段「请勿重复」的 system 段（antiRepeat），
     * 并把 temperature 调低到 0.9，减少「每次都换汤不换药」的模板化输出。
     */
    /** 触摸反应（戳/拖）：带记忆点 + 最近对话 + 去重清单，并带频率/存在惩罚，避免复读 */
    public static ChatResult chatReactive(String apiKey, String model, String persona,
                                          String factsPrompt, String antiRepeat,
                                          java.util.List<String[]> history, String userMsg) {
        return chatFull(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg, 1.0, true);
    }

    public static ChatResult chatProactive(String apiKey, String model, String persona,
                                           String factsPrompt, String antiRepeat,
                                           java.util.List<String[]> history, String userMsg) {
        // 主动发言：temperature 拉高 + 频率/存在惩罚，显著降低「复读」倾向
        return chatFull(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg, 1.0, true);
    }

    /** 兼容旧签名：不带惩罚参数 */
    public static ChatResult chatFull(String apiKey, String model, String persona,
                                  String factsPrompt, String antiRepeat,
                                  java.util.List<String[]> history, String userMsg,
                                  double temperature) {
        return chatFull(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg,
                temperature, false);
    }

    /** 内部完整实现：persona → facts → antiRepeat → history → user，temperature / penalty 可调 */
    public static ChatResult chatFull(String apiKey, String model, String persona,
                                  String factsPrompt, String antiRepeat,
                                  java.util.List<String[]> history, String userMsg,
                                  double temperature, boolean penalize) {
        return chatFull(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg,
                temperature, penalize, 800);
    }

    /**
     * 同上，但可以指定 max_tokens。
     * 普通对话 800 足够（桌宠要的就是短句），但人格蒸馏这类「一次生成上万字文档」的
     * 任务必须放开上限，否则结果会被腰斩。
     */
    public static ChatResult chatFull(String apiKey, String model, String persona,
                                  String factsPrompt, String antiRepeat,
                                  java.util.List<String[]> history, String userMsg,
                                  double temperature, boolean penalize, int maxTokens) {
        ChatResult r = new ChatResult();
        if (apiKey == null || apiKey.trim().length() == 0) {
            r.error = "未设置 API Key";
            return r;
        }
        if (model == null || model.trim().length() == 0) model = "deepseek-chat";

        HttpURLConnection conn = null;
        try {
            JSONArray messages = new JSONArray();
            if (persona != null && persona.trim().length() > 0) {
                JSONObject sys = new JSONObject();
                sys.put("role", "system");
                sys.put("content", persona.trim());
                messages.put(sys);
            }
            // ======== 消息顺序：由「最稳定」到「最易变」，最大化命中 DeepSeek 前缀缓存 ========
            // 缓存是从头逐 token 前缀匹配，一遇差异，后面全部按未命中计费（价差 25 倍）。
            // 因此把纯静态内容排在前面，带时间戳 / 逐轮增长的内容排在后面。

            // ① 主动发言去重规则（调用方拼好；较稳定）
            if (antiRepeat != null && antiRepeat.trim().length() > 0) {
                JSONObject ar = new JSONObject();
                ar.put("role", "system");
                ar.put("content", antiRepeat.trim());
                messages.put(ar);
            }
            // ② 输出风格护栏（纯静态）
            //    只在「日常聊天」这种短输出场景加；人格蒸馏（maxTokens 上万）是
            //    一次生成整份文档，加这条会干扰它写正文，故排除。
            if (maxTokens > 0 && maxTokens <= 2000) {
                JSONObject sg = new JSONObject();
                sg.put("role", "system");
                sg.put("content", StyleGuard.prompt());
                messages.put(sg);
            }
            // ③ 表情选图协议 + 清单（纯静态）
            //    仅当「气泡配图」与「模型选图」都开启时注入；关闭则零 token 开销。
            if (maxTokens > 0 && maxTokens <= 2000 && appCtx != null
                    && Prefs.memeEnabled(appCtx) && Prefs.memeModelPick(appCtx)) {
                String mc = Memes.prompt(appCtx);
                if (mc != null && mc.length() > 0) {
                    JSONObject mm = new JSONObject();
                    mm.put("role", "system");
                    mm.put("content", mc);
                    messages.put(mm);
                }
            }
            // ④ 主动记忆点（变动频率低）
            if (factsPrompt != null && factsPrompt.trim().length() > 0) {
                JSONObject fs = new JSONObject();
                fs.put("role", "system");
                fs.put("content", factsPrompt.trim());
                messages.put(fs);
            }
            // ⑤ 时间上下文（精确到分钟，几乎每轮都变 → 只能排在稳定区之后）
            JSONObject tm = new JSONObject();
            tm.put("role", "system");
            tm.put("content", TimeContext.prompt());
            messages.put(tm);
            if (history != null) {
                for (String[] h : history) {
                    if (h == null || h.length < 2) continue;
                    JSONObject m = new JSONObject();
                    m.put("role", h[0]);
                    // ★ 历史正文保持「原话」，不再拼「（相对时间）」前缀。
                    //   旧版给每条都拼了「（刚刚）」，结果是：（1）模型学会了这个格式，
                    //   每段回复都顶着「（刚刚）」；（2）上下文被无用的时间噪声占满。
                    //   时间信息由 TimeContext.prompt() 单独提供一份，已经足够，
                    //   这里只需要把存量历史里已经写死的痕迹清掉（见 StyleGuard.strip）。
                    m.put("content", StyleGuard.strip(h[1]));
                    messages.put(m);
                }
                // ⑥ 聊天节奏：只在「隔了一段时间没聊」时补一句。
                //    依赖时间且最易变，放在历史之后、用户消息之前。
                for (int i = history.size() - 1; i >= 0; i--) {
                    String[] h = history.get(i);
                    if (h == null || h.length < 3) continue;
                    String g = TimeContext.gap(parseTs(h[2]));
                    if (g != null) {
                        JSONObject gr = new JSONObject();
                        gr.put("role", "system");
                        gr.put("content", "【聊天节奏】距离上次互动已经 " + g
                                + "。你可以自然地接上（例如「你终于回来啦」或关心一下这么久在忙什么），"
                                + "但只在合适的时候提，不要生硬报时。");
                        messages.put(gr);
                    }
                    break;
                }
            }
            // ⑦ 本轮用户消息（★ 只放一次 —— 旧版这里连续 put 了两遍，白花 token 还干扰模型）
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", userMsg == null ? "" : userMsg);
            messages.put(user);

            JSONObject payload = new JSONObject();
            payload.put("model", model.trim());
            payload.put("messages", messages);
            payload.put("stream", false);
            payload.put("max_tokens", maxTokens > 0 ? maxTokens : 800);
            // ★ 参数按服务商能力表决定发不发 —— 见 ChatProvider 的类注释。
            //
            // 用户接 Kimi 时报过：
            //   HTTP 400: invalid temperature: only 1 is allowed for this model
            // 根因是 Kimi K2/K3 系列把这些参数**锁死**，而官方文档明确要求
            // "do not pass it explicitly"（是"不发"，不是"改成正确的值"）。
            // 所以这里一律先问能力表，而不是无条件 put。
            com.deepseek.pet.ChatProvider.Entry prov =
                    com.deepseek.pet.ChatProvider.find(
                            Prefs.getChatProvider(appCtx));
            boolean force = Prefs.chatForceParams(appCtx)
                    && "custom".equals(prov.id);
            if (force || ChatProvider.supports(prov, model, "temp") == ChatProvider.OK) {
                payload.put("temperature", temperature);
            }
            // ★ 开关只管**标准参数**（温度 / 惩罚）：见 applyReasoning 的注释
            applyReasoning(payload, prov, model);
            if (penalize
                    && (force || ChatProvider.supports(prov, model, "penal") == ChatProvider.OK)) {
                // 降低重复：频率惩罚压制高频字词复现，存在惩罚鼓励引入新话题
                payload.put("frequency_penalty", 0.6);
                payload.put("presence_penalty", 0.6);
            }

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(chatUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);

            if (code == 401 || code == 403) {
                r.error = "Key 无效(" + code + ")";
                return r;
            }
            if (code == 402) {
                r.error = "余额不足，请充值";
                return r;
            }
            if (code < 200 || code >= 300) {
                String detail = "";
                try {
                    JSONObject eo = new JSONObject(body);
                    JSONObject err = eo.optJSONObject("error");
                    if (err != null) detail = err.optString("message", "");
                } catch (Exception ignore) {}
                r.error = "HTTP " + code + (detail.length() > 0 ? "：" + detail : "");
                return r;
            }

            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                r.error = "无回复内容";
                return r;
            }
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            String content = msg != null ? msg.optString("content", "") : "";
            if (content == null || content.trim().length() == 0) {
                r.error = "模型返回空回复";
                return r;
            }
            r.ok = true;
            r.reply = content.trim();
            // ★ 从源头清洗：气泡是直接显示这份文本的，不经过 ChatMemory，
            //   所以必须在这里也过一遍，否则「（刚刚）」/[菜刀] 会直接露在气泡上。
            //   只作用于日常闲聊（maxTokens<=2000），人格蒸馏的长文档不动。
            if (maxTokens > 0 && maxTokens <= 2000) {
                String cleaned = StyleGuard.strip(r.reply);
                if (cleaned != null && cleaned.trim().length() > 0) r.reply = cleaned;
                // ★ 表情选图：抽走 @@MEME:名称@@ 标记，并把正文还原干净。
                //   必须放在 StyleGuard.strip 之后 —— 否则标记可能先被当成
                //   普通文本删掉。校验过 has() 的名字才会写进 memeName。
                if (appCtx != null) {
                    String[] out = new String[1];
                    String memeBody = Memes.extract(appCtx, r.reply, out);
                    if (memeBody != null) r.reply = memeBody;
                    r.memeName = out[0];
                }
            }
            parseUsage(json, r);
            recordUsage(model, r);
            // 极端情况：模型只回了一个表情标记、没有一个字的正文。
            // 用量已经计入上面，这里只把它当空回复退回，让调用方走兜底台词。
            if (r.reply == null || r.reply.trim().length() == 0) {
                r.ok = false;
                r.memeName = null;
                r.error = "模型只回了一个表情标记，没有文字";
            }
            return r;
        } catch (Exception e) {
            String emsg = e.getMessage();
            r.error = (emsg == null || emsg.length() == 0) ? "网络错误" : ("网络错误：" + emsg);
            return r;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 主动记忆点提取：把「已有记忆点 + 本轮往来」交给模型，让它输出更新后的
     * 精简事实清单（每行一条，最多 6 条）。失败时返回 null，由调用方决定保留原值。
     *
     * 用量极小：max_tokens 限制在 200，且只在主对话成功后异步触发一次。
     */
    /** 解析历史条目里的时间戳（支持 "1234567890" 或空串） */
    private static long parseTs(String s) {
        if (s == null || s.length() == 0) return 0L;
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 按偏好注入推理强度与思考开关（DeepSeek 新 API 支持 reasoning_effort / thinking）。
     *
     * <p>★ 现在受能力表约束：Kimi K2 不支持 reasoning_effort/thinking、
     * OpenAI o 系列也不接受这些字段 —— 无条件发送会让整个请求 400。
     *
     * <p>★★ 轮次56：**「强制发送参数」开关不再管这两个字段**。
     * 那个开关写的是"强制发送**温度等参数**"，而 {@code thinking} 是 **DeepSeek 专有**、
     * {@code reasoning_effort} 也不是每个 OpenAI 兼容网关都认。用户打开开关的本意是
     * "让我的自建网关也能用上参数"，结果却把 DeepSeek 专有字段发给第三方网关 ——
     * 多数网关遇到未知字段**直接 400（unknown field）**，正好把整个请求打挂
     * （用户报的"自定义 API 用不了"就有这一条）。所以这两个字段**只按能力表发**。
     *
     * @param prov  当前服务商（见 {@link ChatProvider}）
     * @param model 当前模型名，用于模型级覆盖
     */
    private static void applyReasoning(JSONObject payload, ChatProvider.Entry prov,
                                       String model) {
        try {
            android.content.Context ctx = appCtx;
            if (ctx == null) return;
            if (ChatProvider.supports(prov, model, "reason") == ChatProvider.OK) {
                String effort = Prefs.getReasoningEffort(ctx);   // "" / minimal / low / medium / high / none
                if (effort != null && effort.length() > 0) {
                    payload.put("reasoning_effort", effort);
                }
            }
            if (ChatProvider.supports(prov, model, "think") == ChatProvider.OK) {
                String thinking = Prefs.getThinkingType(ctx);     // "" / enabled / disabled
                if (thinking != null && thinking.length() > 0) {
                    JSONObject t = new JSONObject();
                    t.put("type", thinking);
                    payload.put("thinking", t);
                }
            }
        } catch (Exception ignore) {}
    }

    public static String extractFacts(String apiKey, String model, String persona,
                                      String oldFacts, java.util.List<String[]> recent) {
        if (apiKey == null || apiKey.trim().length() == 0) return null;
        if (model == null || model.trim().length() == 0) model = "deepseek-chat";

        HttpURLConnection conn = null;
        try {
            JSONArray messages = new JSONArray();

            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content",
                    "你是记忆整理助手。请从对话中提炼关于「主人（用户）」的、以后有用的长期事实，"
                    + "例如：称呼/名字、喜好厌恶、重要约定、正在做的事、在意的人。"
                    + "要求：\n"
                    + "1) 每行一条，以「- 」开头；\n"
                    + "2) 最多 " + ChatMemory.FACTS_MAX_LINES + " 条，优先保留最重要、最长期有效的信息；\n"
                    + "3) 合并重复、删掉过时或临时的寒暄；\n"
                    + "4) 每条不超过 20 字；\n"
                    + "5) 只输出清单本身，不要解释、不要标题。\n"
                    + "如果没有任何值得长期记住的信息，输出「- （暂无）」或留空。");
            messages.put(sys);

            StringBuilder sb = new StringBuilder();
            sb.append("【已有记忆点】\n");
            sb.append(oldFacts == null || oldFacts.trim().length() == 0 ? "（空）" : oldFacts.trim());
            sb.append("\n\n【最近的对话】\n");
            if (recent != null) {
                for (String[] h : recent) {
                    if (h == null || h.length < 2) continue;
                    sb.append("user".equals(h[0]) ? "主人：" : "桌宠：").append(h[1]).append("\n");
                }
            }
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", sb.toString());
            messages.put(user);

            JSONObject payload = new JSONObject();
            payload.put("model", model.trim());
            payload.put("messages", messages);
            payload.put("stream", false);
            payload.put("max_tokens", 200);
            // ★ 同样受能力表约束：记忆点提炼也走同一个服务商，
            //   Kimi 上无条件发 temperature 一样会 400，
            //   而失败会导致「记忆点悄悄不再更新」——很难发现。
            ChatProvider.Entry prov = ChatProvider.find(Prefs.getChatProvider(appCtx));
            boolean force = Prefs.chatForceParams(appCtx) && "custom".equals(prov.id);
            if (force || ChatProvider.supports(prov, model, "temp") == ChatProvider.OK) {
                payload.put("temperature", 0.3);
            }

            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(chatUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            OutputStream os = conn.getOutputStream();
            os.write(bodyBytes);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.optJSONObject(0).optJSONObject("message");
            String content = msg != null ? msg.optString("content", "") : "";
            if (content == null) return null;
            content = content.trim();
            if (content.length() == 0) return null;
            // 模型有时会写「（暂无）」，视为清空
            if (content.equals("- （暂无）") || content.equals("（暂无）") || content.equals("-")) {
                return "";
            }
            return content;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        br.close();
        return sb.toString();
    }
}
