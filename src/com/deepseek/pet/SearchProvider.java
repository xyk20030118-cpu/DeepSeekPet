package com.deepseek.pet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **可插拔的搜索服务** —— <b>纯类，零 Android 依赖</b>。
 *
 * <h3>为什么做成"配置"而不是"写死几家"</h3>
 * 用户 2026-10-08 的要求：「**不用 kimi 的搜索，要让任何 api 接入进来都能搜索**」。
 * 所以这里不做"Kimi 专用代码"，而是把"一个搜索服务"抽象成 6 件事：
 * <pre>
 *   ① 地址（可含 {q} / {key} 占位符）        ② 方法 GET/POST
 *   ③ 请求头（值里可用 {key}）              ④ 请求体模板（{q} / {key}，自动做 JSON 转义）
 *   ⑤ 结果数组的路径                        ⑥ 每条结果里 标题/链接/摘要/正文 的路径
 * </pre>
 * 有了这 6 件，{@link #parse} 就能把**任意**搜索 API 的 JSON 变成我们内部统一的
 * {@link WebSearch.Hit}；用户只要在设置页贴一段 JSON 就能接新服务（见 {@link #exampleJson}）。
 *
 * <h3>路径语法</h3>
 * 复用 {@link Json#path}：{@code a.b}、{@code a[0].b}、<b>{@code a[].b}（数组摊平）</b>。
 * 例：Kimi Pro 的相关段落是 {@code chunks[].text} ⇒ 配 {@code "content": "chunks[].text"}。
 * 智谱那类 {@code data.webPages.value} 也能写。
 *
 * <h3>内置预设的"证据等级"（别当成全都验过）</h3>
 * <ul>
 *   <li>{@code bing}：本工程自己的免费路（解析公开搜索页），**一直在用**；</li>
 *   <li>{@code kimi}：2026-10-08 **用真 Key 实测通过**（`/tools/search_pro` + `/tools/fetch`）；</li>
 *   <li>{@code searxng} / {@code tavily}：**照官方文档写的模板**，未实测（用户自建/自购后可用）；
 *       设置页里也把它们当"示例"给出，方便照着改。</li>
 * </ul>
 */
public final class SearchProvider {

    /** 内置：本工程的免费路（不在这里发请求，WebClient 里特判 —— 它返回的是 XML/HTML，不是 JSON） */
    public static final String BING = "bing";

    public final String id;
    public final String label;
    /** 一句话说明（设置页显示：要不要 Key / 计费方式 / 适用场景） */
    public final String note;
    public final String endpoint;
    public final String method;                 // GET / POST
    public final String body;                   // POST 用；GET 可空
    public final Map<String, String> headers;   // 值里可用 {key}
    public final String listPath;
    public final String titlePath;
    public final String linkPath;
    public final String snippetPath;
    public final String contentPath;
    /** 正文第二跳（可选）：给一个 URL 取回整页正文，如 Kimi 的 /tools/fetch */
    public final String bodyEndpoint;           // 模板，用 {u} 表示"要抓的网页地址"
    public final String bodyMethod;             // GET / POST（默认 POST）
    public final String bodyBody;               // 第二跳的请求体模板（{u}）
    public final String bodyField;              // 该响应里正文的路径（如 markdown）
    public final boolean needsKey;
    public final String keyHint;
    public final int max;

    private SearchProvider(Map<String, String> m) {
        this.id = val(m, "id", "");
        this.label = val(m, "label", id);
        this.note = val(m, "note", "");
        this.endpoint = val(m, "endpoint", "");
        this.method = val(m, "method", "POST").toUpperCase();
        this.body = val(m, "body", "");
        this.listPath = val(m, "list", "");
        this.titlePath = val(m, "title", "title");
        this.linkPath = val(m, "url", "url");
        this.snippetPath = val(m, "snippet", "snippet");
        this.contentPath = val(m, "content", "");
        this.bodyEndpoint = val(m, "body_endpoint", "");
        this.bodyMethod = val(m, "body_method", "POST").toUpperCase();
        this.bodyBody = val(m, "body_body", "");
        this.bodyField = val(m, "body_field", "");
        this.needsKey = !"false".equalsIgnoreCase(val(m, "needs_key", "true"));
        this.keyHint = val(m, "key_hint", "");
        int mx = 8;
        try {
            mx = Integer.parseInt(val(m, "max", "8"));
        } catch (Throwable ignore) {}
        this.max = mx <= 0 ? 8 : (mx > 20 ? 20 : mx);

        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("Accept", "application/json");
        if ("POST".equals(this.method)) h.put("Content-Type", "application/json");
        Object raw = Json.parseOrNull(val(m, "headers", ""));
        if (raw instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
                List<String> v = Json.strs(e.getValue());
                if (!v.isEmpty()) h.put(String.valueOf(e.getKey()), v.get(0));
            }
        }
        this.headers = h;
    }

    private static String val(Map<String, String> m, String k, String def) {
        String v = m.get(k);
        return v == null ? def : v;
    }

    // ==================== 构造：内置预设 / 用户自定义 ====================

    /** 解析用户贴的 JSON（字段名见 {@link #exampleJson}）；失败返回 null */
    public static SearchProvider fromJson(String json) {
        Object o = Json.parseOrNull(json);
        if (!(o instanceof Map)) return null;
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map) {
                m.put(String.valueOf(e.getKey()), safeJson(v));
            } else {
                List<String> sv = Json.strs(v);
                m.put(String.valueOf(e.getKey()), sv.isEmpty() ? "" : sv.get(0));
            }
        }
        SearchProvider p = new SearchProvider(m);
        return p.endpoint.length() == 0 ? null : p;
    }

    static String safeJson(Object v) {
        try {
            return write(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 极小的 JSON 输出（只需要给 headers 这种一层的对象用） */
    static String write(Object v) {
        StringBuilder b = new StringBuilder();
        wr(v, b);
        return b.toString();
    }

    private static void wr(Object v, StringBuilder b) {
        if (v == null) { b.append("null"); return; }
        if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append('"').append(esc(String.valueOf(e.getKey()))).append("\":");
                wr(e.getValue(), b);
            }
            b.append('}');
            return;
        }
        if (v instanceof List) {
            b.append('[');
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) b.append(',');
                first = false;
                wr(o, b);
            }
            b.append(']');
            return;
        }
        if (v instanceof Boolean) { b.append(v.toString()); return; }
        if (v instanceof Double) {
            double d = (Double) v;
            b.append(d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d));
            return;
        }
        b.append('"').append(esc(String.valueOf(v))).append('"');
    }

    /** 内置预设（顺序就是设置页里的顺序） */
    public static List<SearchProvider> presets() {
        List<SearchProvider> out = new ArrayList<SearchProvider>();
        out.add(fromJson(BING_JSON));
        out.add(fromJson(KIMI_JSON));
        out.add(fromJson(SEARXNG_JSON));
        out.add(fromJson(TAVILY_JSON));
        return out;
    }

    /** 内置里找；找不到返回 null（调用方再去用户自定义里找） */
    public static SearchProvider preset(String id) {
        if (id == null) return null;
        for (SearchProvider p : presets()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** 解析用户存的"自定义服务"JSON 数组（存在 prefs 里的一整段文本；坏数据当空表） */
    public static List<SearchProvider> fromJsonArray(String jsonArray) {
        List<SearchProvider> out = new ArrayList<SearchProvider>();
        Object o = Json.parseOrNull(jsonArray);
        if (!(o instanceof List)) return out;
        for (Object one : (List<?>) o) {
            SearchProvider p = fromJson(safeJson(one));
            if (p != null && p.id.length() > 0) out.add(p);
        }
        return out;
    }

    /**
     * 把"内置预设 + 用户自定义"合成一个列表（设置页要显示全部；同 id 时**用户自定义优先** ——
     * 这样用户能"覆盖"某个内置模板而不必改代码）。
     */
    public static List<SearchProvider> all(String customJson) {
        List<SearchProvider> out = new ArrayList<SearchProvider>();
        List<SearchProvider> custom = fromJsonArray(customJson);
        out.addAll(custom);
        for (SearchProvider p : presets()) {
            boolean shadowed = false;
            for (SearchProvider c : custom) {
                if (c.id.equals(p.id)) { shadowed = true; break; }
            }
            if (!shadowed) out.add(p);
        }
        return out;
    }

    /** 按 id 在"内置 + 自定义"里找（自定义优先） */
    public static SearchProvider byId(String id, String customJson) {
        if (id == null) return null;
        for (SearchProvider p : all(customJson)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** 免费内置路（不需要任何配置） */
    static final String BING_JSON =
            "{\"id\":\"bing\",\"label\":\"必应（免费，内置）\","
            + "\"note\":\"不填任何 Key。解析公开搜索页，拿标题+摘要；质量一般但零成本。\","
            + "\"needs_key\":\"false\",\"endpoint\":\"bing://rss\"}";

    /** 实测通过（2026-10-08，真 Key）：search_pro 给"相关段落"，fetch 给整页 Markdown */
    static final String KIMI_JSON =
            "{\"id\":\"kimi\",\"label\":\"Kimi（月之暗面）\","
            + "\"note\":\"按次计费（约 $0.002~0.003/次）。search_pro 直接给与问题最相关的段落，质量最好。\","
            + "\"endpoint\":\"https://api.moonshot.cn/v1/tools/search_pro\","
            + "\"method\":\"POST\","
            + "\"headers\":{\"Authorization\":\"Bearer {key}\"},"
            + "\"body\":\"{\\\"text_query\\\": \\\"{q}\\\"}\","
            + "\"list\":\"search_results\",\"title\":\"title\",\"url\":\"url\","
            + "\"snippet\":\"snippet\",\"content\":\"chunks[].text\","
            + "\"body_endpoint\":\"https://api.moonshot.cn/v1/tools/fetch\","
            + "\"body_body\":\"{\\\"url\\\": \\\"{u}\\\"}\","
            + "\"body_field\":\"markdown\","
            + "\"key_hint\":\"platform.moonshot.cn → API Key\"}";

    /** 自建 SearXNG（零成本、可离线内网）：GET /search?q=…&format=json */
    static final String SEARXNG_JSON =
            "{\"id\":\"searxng\",\"label\":\"SearXNG（自建，免费）\","
            + "\"note\":\"示例模板：把 endpoint 换成你自己的 SearXNG 地址（需在 settings.yml 打开 json 格式）。\","
            + "\"endpoint\":\"http://127.0.0.1:8888/search?q={q}&format=json\","
            + "\"method\":\"GET\",\"needs_key\":\"false\","
            + "\"list\":\"results\",\"title\":\"title\",\"url\":\"url\",\"snippet\":\"content\"}";

    /** Tavily（照官方文档写的模板，未实测）：POST /search，results[].content 就是正文片段 */
    static final String TAVILY_JSON =
            "{\"id\":\"tavily\",\"label\":\"Tavily（文档模板，未实测）\","
            + "\"note\":\"示例模板：Bearer Key，results[].content 是正文片段。海外服务，国内网络可能连不上。\","
            + "\"endpoint\":\"https://api.tavily.com/search\",\"method\":\"POST\","
            + "\"headers\":{\"Authorization\":\"Bearer {key}\"},"
            + "\"body\":\"{\\\"query\\\": \\\"{q}\\\", \\\"max_results\\\": 8}\","
            + "\"list\":\"results\",\"title\":\"title\",\"url\":\"url\",\"content\":\"content\"}";

    /**
     * 设置页里给用户抄的模板（任何 API 照这个填就能接进来）。
     * ★ 必须是**合法 JSON**（不带注释）—— 用户是 copy-paste 进来解析的，
     * 模板里带 `//` 注释会让"照抄却报错"，那是最气人的一种 bug。
     * 每个字段的含义写在设置页的说明里（`MainActivity` 的 addLabel）。
     */
    public static String exampleJson() {
        return "{"
                + "\"id\": \"myapi\", "
                + "\"label\": \"我的搜索 API\", "
                + "\"endpoint\": \"https://example.com/search?q={q}\", "
                + "\"method\": \"POST\", "
                + "\"headers\": {\"Authorization\": \"Bearer {key}\"}, "
                + "\"body\": \"{\\\"query\\\": \\\"{q}\\\"}\", "
                + "\"list\": \"data.items\", "
                + "\"title\": \"name\", "
                + "\"url\": \"link\", "
                + "\"snippet\": \"summary\", "
                + "\"content\": \"paragraphs[].text\", "
                + "\"body_endpoint\": \"https://example.com/fetch?url={u}\", "
                + "\"body_body\": \"{\\\"url\\\": \\\"{u}\\\"}\", "
                + "\"body_field\": \"markdown\""
                + "}";
    }

    // ==================== 请求组装 ====================

    /** 地址：{q} 做 URL 编码，{key} 也编码（放 query 里当参数用的服务） */
    public String urlFor(String query, String apiKey) {
        return endpoint.replace("{q}", WebSearch.enc(query == null ? "" : query))
                .replace("{key}", WebSearch.enc(apiKey == null ? "" : apiKey));
    }

    /** 请求体：{q}/{key} 做 **JSON 字符串转义**（塞进 JSON 值里必须转义，否则引号会把 JSON 弄坏） */
    public String bodyFor(String query, String apiKey) {
        return body.replace("{q}", jsonEsc(query == null ? "" : query))
                .replace("{key}", jsonEsc(apiKey == null ? "" : apiKey));
    }

    /** 请求头：{key} 原样替换（Key 一般已经在模板里带了 Bearer 前缀） */
    public Map<String, String> headersFor(String apiKey) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            out.put(e.getKey(), e.getValue().replace("{key}", apiKey == null ? "" : apiKey));
        }
        return out;
    }

    /** 第二跳（抓整页正文）的地址 */
    public String bodyUrlFor(String pageUrl) {
        return bodyEndpoint.replace("{u}", WebSearch.enc(pageUrl == null ? "" : pageUrl))
                .replace("{u_raw}", pageUrl == null ? "" : pageUrl);
    }

    /** 请求体里 {u}（有些 fetch 接口是 POST + JSON body） */
    public String bodyBodyFor(String pageUrl) {
        return body.replace("{u}", jsonEsc(pageUrl == null ? "" : pageUrl));
    }

    static String jsonEsc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') b.append('\\').append(c);
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) b.append(' ');
            else b.append(c);
        }
        return b.toString();
    }

    static String esc(String s) {
        return jsonEsc(s);
    }

    // ==================== 响应解析 ====================

    /**
     * 把响应 JSON 变成统一的检索结果。
     *
     * @return 结果列表；解析不出来/没有结果 ⇒ 空表（调用方据此"如实说没查到"或退回别的源）
     */
    public List<WebSearch.Hit> parse(String response) {
        List<WebSearch.Hit> out = new ArrayList<WebSearch.Hit>();
        Object root = Json.parseOrNull(response);
        if (root == null) return out;
        Object list = Json.path(root, listPath);
        if (!(list instanceof List)) {
            // 有些服务直接给对象（单条），也照顾一下
            WebSearch.Hit one = hit(root);
            if (one != null) out.add(one);
            return out;
        }
        for (Object item : (List<?>) list) {
            if (out.size() >= max) break;
            WebSearch.Hit h = hit(item);
            if (h != null) out.add(h);
        }
        return out;
    }

    /** 单条结果：标题/链接/摘要 + 正文（段落摊平后附在摘要后面 —— 模型看到的"料"更足） */
    WebSearch.Hit hit(Object item) {
        if (!(item instanceof Map)) return null;
        String title = Json.text(item, titlePath);
        String url = Json.text(item, linkPath);
        String snip = Json.text(item, snippetPath);
        String body = contentPath.length() > 0 ? Json.text(item, contentPath) : "";
        String all = snip;
        if (body.length() > 0) {
            all = snip.length() > 0 ? (snip + "\n" + body) : body;
        }
        WebSearch.Hit h = new WebSearch.Hit(title, url, all);
        return h.usable() ? h : null;
    }

    /** 从"抓正文"响应里取正文（如 Kimi 的 markdown） */
    public String bodyFrom(String response) {
        Object root = Json.parseOrNull(response);
        if (root == null) return "";
        return Json.text(root, bodyField);
    }
}
