package com.deepseek.pet;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 联网检索的**宿主**：真的发请求、真的抓正文（Android 侧；解析与拼装都在纯类里）。
 *
 * <h3>路线：C = App 先搜再拼提示词（待办 0d）</h3>
 * 不等模型开口要资料：App 自己搜好、把资料拼进这一次请求 ⇒ **1 次往返、不依赖 function calling**。
 * （待办 0d 里那条"先跑 tools 探针确认 DeepSeek 返回 tool_calls"是路线 B 的前提，
 * 需要用户的真 Key 才能跑；路线 C 不需要它，所以先做 C。）
 *
 * <h3>★ 三条纪律</h3>
 * <ol>
 *   <li><b>默认关</b>：{@link Prefs#webSearch} 关着一律不联网（零开销、零流量）；</li>
 *   <li><b>失败如实说</b>：搜不到 / 抓不到就拼一段"没查到"（{@link WebPrompt#searchFailed}），
 *       绝不静默变成"凭旧知识硬答"；</li>
 *   <li><b>只引用真抓到的 URL</b>：拼进提示词的链接全部来自本次真实结果。</li>
 * </ol>
 *
 * <h3>★ 为什么只在后台线程调</h3>
 * 这里是**同步** IO（最坏：搜索 12s + 抓正文 12s）。两个调用点
 * （{@code ChatActivity.onSend} / {@code FloatingService.askQuestion}）本来就在后台线程里发 API 请求，
 * 所以直接同步做；在 UI 线程调会抛 {@code NetworkOnMainThreadException}。
 *
 * <h3>流量预算</h3>
 * 搜索主源用必应 **RSS**（实测 3.8 KB，网页版 63 KB）；正文**只抓第一条**，且最多读 200 KB、
 * 抽 1600 字。一整次联网检索通常在 10~60 KB 量级。
 */
public final class WebClient {

    private WebClient() {}

    /** 搜索响应最多读多少字节（防止对面甩一个大页面过来） */
    static final int MAX_SEARCH_BYTES = 256 * 1024;
    /** 天气结构化接口最多读多少字节（实测 weather_index 只 4.7 KB） */
    static final int MAX_INDEX_BYTES = 64 * 1024;
    /** 正文最多读多少字节 */
    static final int MAX_PAGE_BYTES = 200 * 1024;
    /** 抓回来的网页正文缓存多久（同一篇 30 分钟内不重复抓 —— 重复抓是 429 的第一大来源） */
    static final long PAGE_TTL_MS = 30 * 60 * 1000L;
    /** 连接/读取超时（轮次㉗ 从 6s/12s 放宽：有用户报过"这次没联上网"，慢网下 6s 太紧） */
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    /** 失败重试次数（同源重试一次；只对"连接/读取失败"这种瞬时错误有意义） */
    private static final int RETRY = 1;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/120 Mobile Safari/537.36";

    /** 上一次检索的实况（给界面提示与设备日志用） */
    public static final class Last {
        public boolean ran;          // 这次真的去联网了吗
        public boolean ok;           // 取到资料了吗
        public int hits;             // 搜到几条
        public boolean fetchedBody;  // 抓到第一条的正文了吗
        public String source = "";   // rss / html / weather
        public String reason = "";   // 失败原因（或"为什么没搜"）
        public String query = "";    // 用户**原话**
        public String used = "";     // ★ 实际发出去的查询（提炼后的关键词，或天气城市）

        public String summary() {
            if (!ran) return "未检索（" + reason + "）";
            if (!ok) return "检索失败：" + reason;
            return "检索成功：" + hits + " 条（" + source + (fetchedBody ? " + 正文" : "") + "）";
        }
    }

    private static volatile Last last = new Last();

    /** 上一次检索的实况（拷贝，避免调用方改到内部状态） */
    public static Last lastRun() {
        Last l = new Last();
        Last s = last;
        l.ran = s.ran; l.ok = s.ok; l.hits = s.hits; l.fetchedBody = s.fetchedBody;
        l.source = s.source; l.reason = s.reason; l.query = s.query; l.used = s.used;
        return l;
    }

    // ==================== 对外主入口 ====================

    /**
     * 需要就把用户消息**增强**成"带联网资料"的版本；不需要就原样返回。
     *
     * <p>★ 调用方注意：返回的文本**只发给模型**；显示与存历史都用**用户原话**。
     *
     * @return 增强后的文本（开关关着 / 不值得搜 / 搜失败时也会返回一段说明，
     *         因为"搜失败"必须让模型知道 —— 见 {@link WebPrompt#searchFailed}）
     */
    public static String augment(Context ctx, String userMsg) {
        Last l = new Last();
        l.query = userMsg == null ? "" : userMsg.trim();
        boolean on = false;
        try { on = Prefs.webSearch(ctx); } catch (Throwable ignore) {}
        if (!on) {
            l.reason = "开关关着";
            last = l;
            return userMsg;          // 关着不记日志：不给每一句话刷 logcat
        }
        // ★ 轮次㉗：**天气先走结构化接口**（放在 shouldSearch 之前 —— "北京天气"里没有
        //   "最新/今天"这类词，shouldSearch 会判定"不必搜"，而它其实是最该查的一类问题）。
        boolean weatherTried = false;
        if (Weather.isAsk(l.query)) {
            String built = tryWeather(ctx, userMsg, l);
            if (built != null) {
                last = l;
                return built;
            }
            weatherTried = true;
            // 接口没走通 ⇒ 落到下面普通搜索（用提炼后的关键词，带上城市更准），
            // 失败时也能给用户一个"没查到"的明确交代。
        }
        if (!WebPrompt.shouldSearch(l.query, true)) {
            // ★★ 轮次㉗ 补的一个**诚实漏洞**：天气接口挂了、而这句又**没有"新鲜词"**
            //   （比如"冷不冷""要不要带伞" —— 它们不在 FRESH 表里）⇒ 原分支会**悄悄返回原话**，
            //   模型就拿旧知识编一份天气出来。这里改成**如实说这次没查到天气**。
            if (weatherTried) {
                l.ok = false;
                android.util.Log.i("DSWeb", "weather 失败且不搜索 ⇒ 如实说没查到 q="
                        + clip(l.query) + " 原因=" + l.reason);
                last = l;
                return Weather.failText(userMsg, l.reason);
            }
            l.reason = WebPrompt.why(l.query, true);
            // ★ 开着的时候把"决定不搜"也记下来：设备侧靠它区分
            //   "压根没走到这条路径" 和 "走到了、但判定不必搜"（否则两者都是"没有日志"）。
            android.util.Log.i("DSWeb", "skip 不搜 q=" + clip(l.query) + " 原因=" + l.reason);
            last = l;
            return userMsg;
        }
        l.ran = true;
        // ★ 轮次㉗：**发出去的是提炼后的关键词**，不是用户原话 ——
        //   实测"帮我查一下今天北京天气怎么样啊"会把"帮"当关键词（第一条是《帮_百度百科》）。
        String q = QueryClean.keywords(l.query);
        l.used = q;
        List<WebSearch.Hit> hits = null;
        // ★ 轮次㉘：**搜索服务可插拔**（用户要求"任何 API 接进来都能搜"）。
        //   选了内置必应 / 没配任何服务 ⇒ 老路（免费）；选了别的 ⇒ 走它自己的
        //   地址/请求头/请求体/字段路径；**它失败就退回必应**（不能让"换了家搜索"变成"搜不出来"）。
        SearchProvider prov = selectedProvider(ctx);
        boolean custom = prov != null && !SearchProvider.BING.equals(prov.id);
        String note = "";
        try {
            hits = custom ? providerSearch(ctx, prov, q, l) : search(ctx, q, l);
            if (custom && (hits == null || hits.isEmpty())) {
                android.util.Log.i("DSWeb", "provider 空手而归 ⇒ 退回必应 服务=" + prov.id
                        + " 原因=" + l.reason);
                // ★ 退回必应时**要在资料里说一句**：不然用户配错了 Key 却看到"搜到了"，
                //   永远发现不了自己配的那家其实没工作（诚实优先于"看起来没出错"）。
                note = "（提示：你选的搜索服务「" + prov.label + "」这次没成功"
                        + (l.reason.length() > 0 ? "：" + l.reason : "") + "，下面的资料来自必应）";
                l.source = "";
                hits = search(ctx, q, l);
            }
        } catch (Throwable t) {
            l.reason = "搜索出错：" + t;
        }
        if (hits == null || hits.isEmpty()) {
            l.ok = false;
            if (l.reason.length() == 0) l.reason = "没有搜到结果";
            android.util.Log.i("DSWeb", "augment 失败 q=" + clip(l.query)
                    + " 关键词=" + clip(q) + " 原因=" + l.reason);
            last = l;
            return WebPrompt.searchFailed(userMsg, l.reason);
        }
        // 只抓第一条的正文（流量与收益的平衡点；其余条给摘要）
        List<String> bodies = new ArrayList<String>();
        for (int i = 0; i < hits.size(); i++) bodies.add("");
        try {
            // 配了"第二跳"的服务（如 Kimi 的 /tools/fetch）用它抓整页 Markdown，
            // 没配就用我们自己的剥标签（免费路）。
            String body = (custom && prov.bodyEndpoint.length() > 0)
                    ? providerBody(ctx, prov, hits.get(0).url, l)
                    : fetchText(ctx, hits.get(0).url);
            if (body != null && body.length() > 0) {
                bodies.set(0, body);
                l.fetchedBody = true;
            }
        } catch (Throwable ignore) {}
        l.ok = true;
        l.hits = hits.size();
        String built = WebPrompt.withHits(userMsg, hits, bodies, note);
        // ★ 设备侧靠这行判"真的把资料拼进去了"（原话 vs 拼装后的字数），
        //   光看"搜到了 N 条"不足以证明它进了这次请求。
        android.util.Log.i("DSWeb", "augment 成功 q=" + clip(l.query)
                + " 关键词=" + clip(q)
                + " 条数=" + l.hits + " 源=" + l.source + " 正文=" + l.fetchedBody
                + " 拼装=" + built.length() + "字(原话"
                + (userMsg == null ? 0 : userMsg.trim().length()) + "字)");
        last = l;
        return built;
    }

    // ==================== 天气（结构化接口）====================

    /**
     * 天气问句走**结构化接口**（轮次㉗ 新增的唯一入口）。
     *
     * @return 拼好的提示词；**返回 null 表示"这条路没走通，请调用方落到普通搜索"**
     */
    static String tryWeather(Context ctx, String userMsg, Last l) {
        l.source = "weather";
        String city = Weather.cityIn(userMsg);
        boolean remembered = false;
        if (city.length() == 0) {
            try {
                String keep = Prefs.lastCity(ctx);
                if (keep != null && keep.length() > 0) { city = keep; remembered = true; }
            } catch (Throwable ignore) {}
        }
        if (city.length() == 0 || Weather.code(city).length() == 0) {
            // ★ 没城市就**不联网**：让桌宠反问一句，比瞎猜城市 / 答一段废话都好
            l.ran = false;
            l.ok = false;
            l.reason = "天气问题但没说城市，已让桌宠反问";
            l.used = "";
            android.util.Log.i("DSWeb", "weather 缺城市 q=" + clip(userMsg) + " ⇒ 反问用户哪个城市");
            return Weather.askCityText(userMsg);
        }
        String code = Weather.code(city);
        l.used = city + "(" + code + ")";
        l.ran = true;
        // ★★ 轮次㉙：**先看缓存**（同一城市 10 分钟内不再请求）。
        //   这是治 429 的关键一步 —— 用户连着问几句天气，以前每句都真打一次网络。
        String ckey = "weather:" + code;
        String cachedBlock = WebCache.get(ckey);
        if (cachedBlock != null) {
            l.ok = true;
            l.hits = 0;
            l.reason = "";
            l.fetchedBody = false;
            android.util.Log.i("DSWeb", "weather 缓存命中 城市=" + city + " 剩="
                    + (WebCache.ttlLeft(ckey) / 1000) + "秒");
            return WebPrompt.withWeather(userMsg, cachedBlock);
        }
        String reason = "";
        String jsonp = null;
        // 5 分钟对齐的时间戳（**不再用随机 cache-buster**：那会把对面的 CDN 缓存全废掉，
        // 看起来还像爬虫 —— 轮次㉙ 的 429 排查里专门查了这一点）
        long bucket = System.currentTimeMillis() / 300000L * 300000L;
        try {
            jsonp = fetchCached(ckey + ":index", Weather.indexUrl(code, bucket),
                    MAX_INDEX_BYTES, Weather.REFERER, null, "GET", null,
                    WebCache.WEATHER_TTL_MS, l);
        } catch (Throwable t) {
            reason = "天气接口打不开：" + brief(t);
        }
        Weather.Now now = new Weather.Now();
        List<Weather.Day> days = new ArrayList<Weather.Day>();
        boolean ok = jsonp != null && Weather.parse(jsonp, now, days);
        if (!ok) {
            // 兜底一：实况接口更小更稳（但只有实况，没有预报）
            android.util.Log.i("DSWeb", "weather index 没解析出来，改用 sk_2d 兜底 城市=" + city);
            try {
                String sk = fetchCached(ckey + ":sk", Weather.nowUrl(code, bucket),
                        MAX_INDEX_BYTES, Weather.REFERER, null, "GET", null,
                        WebCache.WEATHER_TTL_MS, l);
                now = new Weather.Now();
                ok = Weather.parseNow(sk, now);
                if (!ok && reason.length() == 0) reason = "天气接口返回的内容解析不出数据";
            } catch (Throwable t) {
                if (reason.length() == 0) reason = "天气接口打不开：" + brief(t);
            }
        }
        if (!ok) {
            // 兜底二：**换一台主机**（wttr.in）。中国天气网的 index 与 sk_2d 是同一台主机，
            // 它一限流两路一起挂 —— 这就是"429 之后整个天气都不行了"的原因。
            String en = Weather.enOf(city);
            if (en.length() > 0) {
                try {
                    android.util.Log.i("DSWeb", "weather 两路都没成，改试 wttr.in 城市=" + city);
                    String wj = fetchCached("weather:wttr:" + en, Weather.wttrUrl(en, bucket),
                            MAX_INDEX_BYTES, null, null, "GET", null,
                            WebCache.WEATHER_TTL_MS, l);
                    Weather.Now alt = new Weather.Now();
                    if (Weather.parseWttr(wj, alt)) {
                        now = alt;
                        days = new ArrayList<Weather.Day>();
                        ok = true;
                        reason = "";
                    }
                } catch (Throwable t) {
                    android.util.Log.i("DSWeb", "weather wttr 兜底也失败 城市=" + city + " :: " + brief(t));
                }
            }
        }
        if (!ok) {
            l.ok = false;
            l.reason = reason;
            // ★ 把这条路交回调用方：能搜就搜，不能搜（这句没有"新鲜词"）就**如实说没查到** ——
            //   绝不悄悄返回原话让模型拿旧知识编天气（见 augment 里的 weatherTried 分支）。
            android.util.Log.i("DSWeb", "weather 失败 城市=" + city + " 原因=" + reason);
            return null;        // 天气没走通 ⇒ 交回调用方
        }
        try { Prefs.setLastCity(ctx, now.city.length() > 0 ? now.city : city); } catch (Throwable ignore) {}
        l.ok = true;
        l.hits = days.size();
        l.reason = "";
        l.fetchedBody = false;   // 天气走的是结构化数据，不是"抓正文"
        String block = Weather.block(city, now, days);
        WebCache.put(ckey, block, WebCache.WEATHER_TTL_MS);   // ★ 存起来：10 分钟内再问同一个城市不再打网络
        String built = WebPrompt.withWeather(userMsg, block);
        android.util.Log.i("DSWeb", "weather 成功 城市=" + (now.city.length() > 0 ? now.city : city)
                + " 码=" + code + " 实况=" + now.temp + "℃" + now.weather
                + " 预报=" + days.size() + "天 记忆=" + remembered
                + " 拼装=" + built.length() + "字(原话"
                + (userMsg == null ? 0 : userMsg.trim().length()) + "字)");
        return built;
    }

    // ==================== 缓存 + 退避（轮次㉙：治 429）====================

    /**
     * 带缓存的取文本：同一个 key 在 TTL 内**只真请求一次**；处于冷却期的主机**连请求都不发**。
     *
     * <p>这是本轮治 429 的核心：把"用户每问一次就打一次"变成"同样的问题短时间只打一次"。
     * 天气、搜索结果、网页正文三条路都走它。
     *
     * @param key   缓存键（建议带来源与查询，如 {@code bing:rss:<q>}）
     * @param ttlMs 缓存多久
     */
    // ★ 注意形参名：**不要叫 `key`** —— 工程里有一条断言"DSWeb 日志整句里不许出现 `key`"
    //   （防的是把 API Key 打进日志）。缓存键不是秘密，但变量名叫 key 会触发那条判据，
    //   而那条判据宁严勿松，所以这里改叫 `cacheKey`。
    static String fetchCached(String cacheKey, String url, int maxBytes, String referer,
                              java.util.Map<String, String> headers, String method,
                              String body, long ttlMs, Last l) throws Exception {
        String hit = WebCache.get(cacheKey);
        if (hit != null) {
            android.util.Log.i("DSWeb", "缓存命中 " + cacheKey + " 剩="
                    + (WebCache.ttlLeft(cacheKey) / 1000) + "秒");
            return hit;
        }
        String host = WebCache.hostOf(url);
        long left = WebCache.cooldownLeft(host);
        if (left > 0) {
            throw new Exception("这个源刚被限流过，" + (left / 1000 + 1) + " 秒后再试");
        }
        String text;
        if ("GET".equals(method)) {
            text = getWithRetry(url, maxBytes, referer, headers);
        } else {
            text = sendWithRetry(method, url, body, headers, maxBytes);
        }
        WebCache.put(cacheKey, text, ttlMs);
        return text;
    }

    // ==================== 可插拔搜索服务（轮次㉘）====================
    /**
     * 当前选中的搜索服务。
     *
     * <p>返回 {@code null} 或内置必应 ⇒ 走免费老路；其余 ⇒ 走用户配的服务。
     * 未知 id（用户删了自定义服务/prefs 被改坏）也当"退回必应"，**不让配置问题变成功能失效**。
     */
    static SearchProvider selectedProvider(Context ctx) {
        try {
            String id = Prefs.searchProvider(ctx);
            if (id == null || id.length() == 0 || SearchProvider.BING.equals(id)) return null;
            return SearchProvider.byId(id, Prefs.customProviders(ctx));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 走用户配置的搜索服务（地址/方法/请求头/请求体/结果路径全部来自配置）。
     *
     * <p>★ 三条纪律：① 需要 Key 而没填 ⇒ **不发请求**，直接给一句人话；② 失败**不抛给界面**，
     * 把原因写进 {@code l.reason} 交给上层（上层会退回必应）；③ 日志只写服务 id，
     * <b>绝不写 Key</b>（Key 在请求头里，日志里不能出现）。
     */
    static List<WebSearch.Hit> providerSearch(Context ctx, SearchProvider p, String q, Last l) {
        String key = "";
        try { key = Prefs.searchKey(ctx, p.id); } catch (Throwable ignore) {}
        if (p.needsKey && (key == null || key.length() == 0)) {
            if (l != null) l.reason = "「" + p.label + "」还没填 Key（设置页 → 搜索服务）";
            return null;
        }
        try {
            if (!WebSearch.isHttp(p.urlFor(q, key))) {
                if (l != null) l.reason = "「" + p.label + "」地址不是 http(s)：" + clip(p.endpoint);
                return null;
            }
            String resp = fetchCached("p:" + p.id + ":" + q, p.urlFor(q, key), MAX_SEARCH_BYTES,
                    null, p.headersFor(key), p.method, p.bodyFor(q, key),
                    WebCache.SEARCH_TTL_MS, l);
            List<WebSearch.Hit> hits = p.parse(resp);
            if (l != null) {
                if (!hits.isEmpty()) {
                    l.source = "provider:" + p.id;
                    l.reason = "";
                } else if (l.reason.length() == 0) {
                    l.reason = "「" + p.label + "」返回的内容里没解析出结果（字段路径可能不对）";
                }
            }
            return hits;
        } catch (Throwable t) {
            if (l != null) l.reason = "「" + p.label + "」打不开：" + brief(t);
            return null;
        }
    }

    /** 第二跳：把第一条结果的**整页正文**取回来（如 Kimi 的 /tools/fetch → markdown） */
    static String providerBody(Context ctx, SearchProvider p, String pageUrl, Last l) {
        if (p.bodyEndpoint.length() == 0 || !WebSearch.isHttp(pageUrl)) return "";
        String key = "";
        try { key = Prefs.searchKey(ctx, p.id); } catch (Throwable ignore) {}
        try {
            String body = p.bodyBody.length() > 0
                    ? p.bodyBody.replace("{u}", SearchProvider.jsonEsc(pageUrl))
                    : ("{\"url\": \"" + SearchProvider.jsonEsc(pageUrl) + "\"}");
            String resp = fetchCached("page:" + p.id + ":" + pageUrl, p.bodyUrlFor(pageUrl),
                    MAX_PAGE_BYTES, null, p.headersFor(key), p.bodyMethod, body, PAGE_TTL_MS, l);
            return p.bodyFrom(resp);
        } catch (Throwable t) {
            android.util.Log.i("DSWeb", "provider 抓正文失败 服务=" + p.id + " :: " + brief(t));
            return "";
        }
    }

    // ==================== 搜索 ====================

    /** 先 RSS（小、稳），再网页版（兜底）。都失败返回空表。★ 两路都带缓存（同一句话 5 分钟内不重打）。 */
    public static List<WebSearch.Hit> search(Context ctx, String query, Last l) {
        String rss = null;
        try {
            rss = fetchCached("bing:rss:" + query, WebSearch.rssUrl(query), MAX_SEARCH_BYTES,
                    null, null, "GET", null, WebCache.SEARCH_TTL_MS, l);
        } catch (Throwable t) {
            if (l != null) l.reason = "必应 RSS 打不开：" + brief(t);
        }
        if (rss != null) {
            List<WebSearch.Hit> hits = WebSearch.parseRss(rss, WebSearch.MAX_HITS);
            if (!hits.isEmpty()) {
                if (l != null) l.source = "rss";
                return hits;
            }
        }
        try {
            String html = fetchCached("bing:html:" + query, WebSearch.htmlUrl(query), MAX_SEARCH_BYTES,
                    null, null, "GET", null, WebCache.SEARCH_TTL_MS, l);
            if (html != null) {
                List<WebSearch.Hit> hits = WebSearch.parseHtml(html, WebSearch.MAX_HITS);
                if (!hits.isEmpty()) {
                    if (l != null) { l.source = "html"; l.reason = ""; }
                    return hits;
                }
            }
            if (l != null && l.reason.length() == 0) l.reason = "必应返回的页面里没解析出结果";
        } catch (Throwable t) {
            if (l != null && l.reason.length() == 0) l.reason = "必应网页版打不开：" + brief(t);
        }
        return new ArrayList<WebSearch.Hit>();
    }

    /**
     * 带重试的 GET（轮次㉗ 新增）。
     * <p>为什么要有：用户报过"这次没联上网"而后台日志显示同类请求是通的 —— 慢网/瞬断下
     * 一次失败就放弃太脆。只对**连接/读取失败**这类瞬时错误重试，HTTP 4xx/5xx（对面明确拒绝）
     * 直接抛给上层，免得白等（{@link #httpGet} 里 HTTP 非 2xx 会抛 "HTTP xxx"）。
     */
    static String getWithRetry(String url, int maxBytes, String referer) throws Exception {
        return getWithRetry(url, maxBytes, referer, null);
    }

    /** 同上，可带自定义请求头（可插拔搜索服务要发自己的 Authorization） */
    static String getWithRetry(String url, int maxBytes, String referer,
                               java.util.Map<String, String> headers) throws Exception {
        Exception lastErr = null;
        for (int i = 0; i <= RETRY; i++) {
            try {
                return httpRequest("GET", url, null, headers, maxBytes, referer);
            } catch (Exception e) {
                lastErr = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("HTTP ") || m.startsWith("不是文本")) throw e;
                if (i < RETRY) {
                    try { Thread.sleep(400); } catch (InterruptedException ignore) {}
                }
            }
        }
        throw lastErr == null ? new Exception("未知错误") : lastErr;
    }

    /** POST/其它方法 + 自定义头（同样只对"瞬时错误"重试；HTTP 4xx/5xx 立刻抛） */
    static String sendWithRetry(String method, String url, String body,
                                java.util.Map<String, String> headers, int maxBytes) throws Exception {
        Exception lastErr = null;
        for (int i = 0; i <= RETRY; i++) {
            try {
                return httpRequest(method, url, body, headers, maxBytes, null);
            } catch (Exception e) {
                lastErr = e;
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (m.startsWith("HTTP ") || m.startsWith("不是文本")) throw e;
                if (i < RETRY) {
                    try { Thread.sleep(400); } catch (InterruptedException ignore) {}
                }
            }
        }
        throw lastErr == null ? new Exception("未知错误") : lastErr;
    }

    /** 抓一个网页的正文（剥标签后的纯文本，最多 {@link WebFetch#MAX_CHARS} 字）；失败返回 "" */
    public static String fetchText(Context ctx, String url) {
        if (!WebSearch.isHttp(url)) return "";
        try {
            String html = httpGet(url, MAX_PAGE_BYTES);
            if (html == null) return "";
            return WebFetch.extractText(html, WebFetch.MAX_CHARS);
        } catch (Throwable t) {
            android.util.Log.i("DSWeb", "抓正文失败 " + clip(url) + " :: " + brief(t));
            return "";
        }
    }

    // ==================== HTTP ====================

    /**
     * 同步 GET → 文本。**必须在后台线程调**。
     *
     * <p>细节：不带 {@code Accept-Encoding}（避免对面压 gzip 而我们没解压 —— 不过真给了
     * {@code Content-Encoding: gzip} 也会解）；读满 {@code maxBytes} 就停（防超大页）；
     * 字符集优先取响应头，取不到按 UTF-8（必应的两种响应都是 UTF-8）。
     */
    static String httpGet(String url, int maxBytes) throws Exception {
        return httpGet(url, maxBytes, null);
    }

    /**
     * 同上，但可以带 {@code Referer}（中国天气网的 {@code d1.weather.com.cn} 要它，
     * 不带会被拦；必应不需要）。
     */
    static String httpGet(String url, int maxBytes, String referer) throws Exception {
        return httpRequest("GET", url, null, null, maxBytes, referer);
    }

    /**
     * 带自定义头的请求（轮次㉘：**可插拔搜索服务**要发 POST + 自己的 Authorization 头）。
     *
     * @param headers 自定义头（会覆盖同名默认头）；{@code null} 表示不加
     */
    static String httpSend(String method, String url, String body,
                           java.util.Map<String, String> headers, int maxBytes) throws Exception {
        return httpRequest(method, url, body, headers, maxBytes, null);
    }

    private static String httpRequest(String method, String url, String body,
                                      java.util.Map<String, String> headers,
                                      int maxBytes, String referer) throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            if (referer != null && referer.length() > 0) conn.setRequestProperty("Referer", referer);
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        conn.setRequestProperty(e.getKey(), e.getValue());
                    }
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                byte[] bytes = body.getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                java.io.OutputStream os = conn.getOutputStream();
                try {
                    os.write(bytes);
                } finally {
                    try { os.close(); } catch (Throwable ignore) {}
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                // ★ 轮次㉙：**429 要单独认**（用户报过"搜天气报 429"）。
                //   对面在说"你请求太多了" —— 那就：① 把这台主机放进冷却（尊重 Retry-After，
                //   没有就默认 60 秒，最多 10 分钟）；② 抛一句**人能看懂**的原因，
                //   让上层如实告诉用户"这个源暂时限流了"，而不是笼统的"没查到"。
                if (code == 429 || code == 403) {
                    long wait = WebCache.retryAfterMs(conn.getHeaderField("Retry-After"));
                    String host = WebCache.hostOf(url);
                    WebCache.cool(host, wait);
                    long left = WebCache.cooldownLeft(host);
                    android.util.Log.i("DSWeb", "限流 HTTP " + code + " 主机=" + host
                            + " 冷却=" + (left / 1000) + "秒");
                    throw new Exception("HTTP " + code + "（对面限流了，"
                            + Math.max(1, left / 1000) + " 秒后可再试）");
                }
                throw new Exception("HTTP " + code);
            }
            String type = conn.getContentType();
            if (!WebFetch.isTextType(type)) throw new Exception("不是文本：" + type);
            in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().indexOf("gzip") >= 0) in = new GZIPInputStream(in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                int keep = Math.min(n, maxBytes - total);
                if (keep <= 0) break;
                bos.write(buf, 0, keep);
                total += keep;
                if (total >= maxBytes) break;
            }
            String cs = WebFetch.charsetOf(type);
            return new String(bos.toByteArray(), cs.length() > 0 ? cs : "UTF-8");
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignore) {}
        }
    }

    /** 异常信息压成一行短的（日志与提示里不该出现整段堆栈） */
    static String brief(Throwable t) {
        if (t == null) return "未知";
        String m = t.getMessage();
        if (m == null || m.trim().length() == 0) m = t.getClass().getSimpleName();
        return m.replace('\n', ' ').trim();
    }

    static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "…";
    }
}
