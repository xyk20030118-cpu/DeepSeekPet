package com.deepseek.pet;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 联网检索：**请求地址与结果解析**的纯逻辑 —— <b>零 Android 依赖</b>
 * （照 {@link ClipNaming} / {@link ContentInset} 的手法写，所以能在 {@code build/fpstest} 里
 * 拿**真实抓下来的报文**做断言）。
 *
 * <h3>为什么选必应，而不是原计划的 DDG / 维基 / 百度</h3>
 * 待办 0d 原本写的是"DuckDuckGo HTML / 维基百科 API / 百度兜底"。2026-10-08 在本机
 * （国内网络）**实测**这四个源：
 *
 * <table border="1">
 *   <tr><th>源</th><th>结果</th></tr>
 *   <tr><td>{@code cn.bing.com/search?q=…&format=rss}</td><td>✅ <b>200，3.8 KB，干净的 RSS 2.0</b>（本类主用它）</td></tr>
 *   <tr><td>{@code cn.bing.com/search?q=…}（网页版）</td><td>✅ 200，63 KB，{@code <li class="b_algo">} 结构清晰（兜底用它）</td></tr>
 *   <tr><td>{@code html.duckduckgo.com/html/?q=…}</td><td>❌ 连接超时（被墙）</td></tr>
 *   <tr><td>{@code zh.wikipedia.org/w/api.php}</td><td>❌ 连接失败（被墙）</td></tr>
 *   <tr><td>{@code www.baidu.com/s?wd=…}</td><td>⚠️ 200 但 1.8~2.3 MB，且结果**是 JS 渲染的**（静态 HTML 里没有结果）⇒ 抓不到</td></tr>
 *   <tr><td>{@code m.baidu.com/s?word=…}</td><td>❌ 只有 1.5 KB 的 JS 壳</td></tr>
 * </table>
 *
 * ⇒ <b>只有必应能免 Key 稳定抓到</b>；RSS 又比网页版小 16 倍且结构稳定，所以主用 RSS、
 * 网页版做兜底（RSS 是必应未公开的老接口，万一哪天没了，兜底还能用）。
 * 跨过墙的用户以后想加 DDG，只要补一个解析函数 + 一份 fixture 即可（本类就是为此分层的）。
 *
 * <h3>★ 为什么把解析放在纯类里</h3>
 * 搜索结果页是**别人的**格式，随时会变。把解析做成纯函数之后：
 * <ol>
 *   <li>可以用**真实报文**（抓下来存进 {@code build/fpstest/fixtures/}）做离线断言 ——
 *       格式一变，套件立刻红，而不是等用户发现"它说查不到"；</li>
 *   <li>断言跑在桌面 JVM 上，不需要联网、不需要手机。</li>
 * </ol>
 */
public final class WebSearch {

    private WebSearch() {}

    /** 一条搜索结果 */
    public static final class Hit {
        public final String title;
        public final String url;
        public final String snippet;

        public Hit(String title, String url, String snippet) {
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.snippet = snippet == null ? "" : snippet;
        }

        /** 标题/摘要都空 = 这条没用（解析时会被丢掉） */
        public boolean usable() {
            return url.length() > 0 && (title.length() > 0 || snippet.length() > 0);
        }
    }

    /**
     * 一次搜索最多取几条。
     * <p>★ 轮次㉗ 实测：必应 RSS 一次给 **9~10 条**、摘要最长 **320 字**（网页版只有 5 条、
     * 摘要被截到 ~85 字）⇒ 原来只取 5 条等于白扔一半资料；网页版也提到 8 条对齐。
     * 提示词块的上限见 {@link WebPrompt#MAX_BLOCK_CHARS}。
     */
    public static final int MAX_HITS = 8;

    // ==================== 请求地址 ====================

    /** 必应 RSS（主源；3.8 KB，结构稳定） */
    public static String rssUrl(String query) {
        return "https://cn.bing.com/search?q=" + enc(query) + "&format=rss&setlang=zh-CN";
    }

    /** 必应网页版（兜底；RSS 没了就用它） */
    public static String htmlUrl(String query) {
        return "https://cn.bing.com/search?q=" + enc(query) + "&setlang=zh-CN";
    }

    /** URL 编码（空格变 {@code +}，查询串里合法） */
    public static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    // ==================== 解析：必应 RSS ====================

    /**
     * 解析必应 RSS。结构（2026-10-08 实测）：
     * <pre>{@code <item><title>…</title><link>https://…</link>
     * <description>摘要…</description><pubDate>…</pubDate></item>}</pre>
     */
    public static List<Hit> parseRss(String xml, int max) {
        List<Hit> out = new ArrayList<Hit>();
        if (xml == null || xml.length() == 0) return out;
        if (max <= 0) max = MAX_HITS;
        int from = 0;
        while (out.size() < max) {
            int a = indexOfIgnoreCase(xml, "<item", from);
            if (a < 0) break;
            int b = indexOfIgnoreCase(xml, "</item>", a);
            if (b < 0) b = xml.length();
            String blk = xml.substring(a, b);
            from = b + 1;
            String url = decodeEntities(stripTags(tagText(blk, "link"))).trim();
            if (!isHttp(url)) continue;
            Hit h = new Hit(
                    decodeEntities(stripTags(tagText(blk, "title"))).trim(),
                    url,
                    decodeEntities(stripTags(tagText(blk, "description"))).trim());
            if (h.usable()) out.add(h);
        }
        return out;
    }

    // ==================== 解析：必应网页版 ====================

    /**
     * 解析必应网页版结果块。结构（2026-10-08 实测）：
     * <pre>{@code <li class="b_algo" …><a href="https://…"><h2 …>标题</h2></a>
     * <div class="b_caption"><p class="b_lineclamp3">摘要…</p></div></li>}</pre>
     * <p>★ 链接取的是 {@code <h2>} <b>之前最近的那个</b> {@code href} ——
     * 结果块开头还有一条"站点信息"链接（指向站点根），先出现的那个不是文章地址。
     */
    public static List<Hit> parseHtml(String html, int max) {
        List<Hit> out = new ArrayList<Hit>();
        if (html == null || html.length() == 0) return out;
        if (max <= 0) max = MAX_HITS;
        String[] blocks = splitBlocks(html, "class=\"b_algo\"");
        for (int i = 1; i < blocks.length && out.size() < max; i++) {
            String blk = blocks[i];
            int h2 = indexOfIgnoreCase(blk, "<h2");
            if (h2 < 0) continue;
            int h2end = indexOfIgnoreCase(blk, "</h2>", h2);
            if (h2end < 0) h2end = blk.length();
            String title = decodeEntities(stripTags(blk.substring(h2, h2end))).trim();
            String url = lastHref(blk.substring(0, h2));
            if (!isHttp(url)) continue;
            String snippet = firstTagText(blk.substring(h2end), "p");
            if (snippet.length() == 0) snippet = firstTagText(blk.substring(h2end), "div");
            Hit h = new Hit(title, url, decodeEntities(stripTags(snippet)).trim());
            if (h.usable()) out.add(h);
        }
        return out;
    }

    // ==================== 文本小工具（也可给抓正文用）====================

    /** 取 {@code <tag>…</tag>} 的内容（支持 CDATA）；没有返回 "" */
    public static String tagText(String block, String tag) {
        if (block == null || tag == null) return "";
        String open = "<" + tag;
        int a = indexOfIgnoreCase(block, open, 0);
        if (a < 0) return "";
        int gt = block.indexOf('>', a);
        if (gt < 0) return "";
        int b = indexOfIgnoreCase(block, "</" + tag + ">", gt);
        if (b < 0) return "";
        return unwrapCdata(block.substring(gt + 1, b));
    }

    /** 第一个 {@code <tag …>…</tag>} 的内容（不区分大小写） */
    public static String firstTagText(String block, String tag) {
        return tagText(block, tag);
    }

    /** 去掉 {@code <![CDATA[ … ]]>} 外壳 */
    public static String unwrapCdata(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("<![CDATA[")) {
            int end = t.lastIndexOf("]]>");
            if (end > 9) return t.substring(9, end);
        }
        return s;
    }

    /** 剥掉所有标签（{@code <script>/<style>} 里的内容也一起去掉） */
    public static String stripTags(String s) {
        if (s == null) return "";
        String t = s;
        t = removeBlocks(t, "script");
        t = removeBlocks(t, "style");
        StringBuilder sb = new StringBuilder(t.length());
        boolean in = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '<') { in = true; continue; }
            if (c == '>') { in = false; continue; }
            if (!in) sb.append(c);
        }
        return sb.toString();
    }

    private static String removeBlocks(String s, String tag) {
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int a = indexOfIgnoreCase(s, open, 0);
        while (a >= 0) {
            int b = indexOfIgnoreCase(s, close, a);
            if (b < 0) {
                s = s.substring(0, a);
                break;
            }
            s = s.substring(0, a) + " " + s.substring(b + close.length());
            a = indexOfIgnoreCase(s, open, a);
        }
        return s;
    }

    /** 常见实体 + 数字实体 */
    public static String decodeEntities(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        String t = s;
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
             .replace("&#39;", "'").replace("&hellip;", "…").replace("&mdash;", "—")
             .replace("&ndash;", "–").replace("&ldquo;", "“").replace("&rdquo;", "”")
             .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&middot;", "·")
             .replace("&times;", "×").replace("&copy;", "©").replace("&deg;", "°");
        // 数字实体（&#123; / &#x1F600;）
        StringBuilder sb = new StringBuilder(t.length());
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == '&' && i + 1 < t.length() && t.charAt(i + 1) == '#') {
                int semi = t.indexOf(';', i);
                if (semi > i + 2 && semi - i <= 10) {
                    String num = t.substring(i + 2, semi);
                    try {
                        int code = (num.charAt(0) == 'x' || num.charAt(0) == 'X')
                                ? Integer.parseInt(num.substring(1), 16) : Integer.parseInt(num);
                        sb.appendCodePoint(code);
                        i = semi + 1;
                        continue;
                    } catch (Throwable ignore) { /* 落回原样 */ }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 是不是可引用的 http(s) 地址 */
    public static boolean isHttp(String url) {
        if (url == null) return false;
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    // ==================== 内部 ====================

    /** 按某个标记切块（第 0 段是标记之前的内容） */
    static String[] splitBlocks(String s, String marker) {
        List<String> parts = new ArrayList<String>();
        int from = 0;
        int at = indexOfIgnoreCase(s, marker, from);
        parts.add(s.substring(0, at < 0 ? s.length() : at));
        while (at >= 0) {
            int next = indexOfIgnoreCase(s, marker, at + marker.length());
            parts.add(s.substring(at, next < 0 ? s.length() : next));
            at = next;
        }
        return parts.toArray(new String[0]);
    }

    /** 一段 HTML 里最后一个 href="http…"（Bing 结果块的链接在 h2 之前） */
    static String lastHref(String html) {
        if (html == null) return "";
        String best = "";
        int i = 0;
        while (true) {
            int a = indexOfIgnoreCase(html, "href=\"", i);
            if (a < 0) break;
            int b = html.indexOf('"', a + 6);
            if (b < 0) break;
            String v = decodeEntities(html.substring(a + 6, b)).trim();
            if (isHttp(v)) best = v;
            i = b + 1;
        }
        return best;
    }

    static int indexOfIgnoreCase(String s, String needle) {
        return indexOfIgnoreCase(s, needle, 0);
    }

    static int indexOfIgnoreCase(String s, String needle, int from) {
        if (s == null || needle == null) return -1;
        int n = s.length(), m = needle.length();
        if (m == 0) return from;
        for (int i = Math.max(0, from); i + m <= n; i++) {
            if (s.regionMatches(true, i, needle, 0, m)) return i;
        }
        return -1;
    }
}
