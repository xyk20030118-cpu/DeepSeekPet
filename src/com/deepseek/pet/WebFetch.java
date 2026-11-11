package com.deepseek.pet;

/**
 * 抓网页正文：**纯文本抽取**（零 Android 依赖，可在 fpstest 里用真报文断言）。
 *
 * <h3>边界（刻意不做的事）</h3>
 * 只做"GET + 剥标签 + 抽正文"，**不做 JS 渲染**：需要脚本才能出内容的站（百度、
 * 知乎、微信）抓到的是空壳 —— 那就如实说抓不到，不假装。抽不出正文时返回 ""，
 * 由调用方按"这条没抓到"处理。
 *
 * <p>为什么要有这个类：搜索摘要往往只有一两句，回答"XX 多少钱 / 怎么回事"这类问题
 * 常常不够；把**第一条结果的正文**取一段塞进提示词，质量差别很明显。
 */
public final class WebFetch {

    private WebFetch() {}

    /** 抓下来的正文最多留多少字（提示词是花钱的，几千字足够回答一个问题） */
    public static final int MAX_CHARS = 1600;

    /**
     * 从 HTML 抽正文文本。
     *
     * <p>规律：{@code <script>/<style>/<noscript>/<head>/注释} 整块丢掉；
     * {@code <br>} 与块级标签的结束当换行；再剥掉剩下的标签；最后按行去空、压掉重复空行。
     */
    public static String extractText(String html, int maxChars) {
        if (html == null || html.length() == 0) return "";
        int cap = maxChars > 0 ? maxChars : MAX_CHARS;
        String s = html;
        s = dropBlock(s, "script");
        s = dropBlock(s, "style");
        s = dropBlock(s, "noscript");
        s = dropBlock(s, "head");
        s = dropBlock(s, "svg");
        s = dropComments(s);
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article|header|footer|blockquote)\\s*>",
                "\n");
        s = s.replaceAll("(?i)<(p|div|li|tr|h[1-6]|section|article|header|footer|blockquote)\\b[^>]*>",
                "\n");
        StringBuilder sb = new StringBuilder(s.length());
        boolean in = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { in = true; continue; }
            if (c == '>') { in = false; continue; }
            if (!in) sb.append(c);
        }
        String t = WebSearch.decodeEntities(sb.toString());
        t = t.replace('\u00a0', ' ').replace('\r', '\n');
        // 按行清理：去首尾空白、丢掉空行、合并连续空格
        String[] lines = t.split("\n");
        StringBuilder out = new StringBuilder();
        for (String ln : lines) {
            String v = ln.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
            if (v.length() == 0) continue;
            if (out.length() > 0) out.append('\n');
            out.append(v);
        }
        String res = out.toString().trim();
        if (res.length() > cap) res = res.substring(0, cap) + "…";
        return res;
    }

    /** 页面标题（{@code <title>}），抽不到返回 "" */
    public static String titleOf(String html) {
        if (html == null) return "";
        String t = WebSearch.tagText(html, "title");
        return WebSearch.decodeEntities(t).replaceAll("\\s+", " ").trim();
    }

    /** 这个 Content-Type 能不能当文本抓（图片/视频/PDF 一律不抓） */
    public static boolean isTextType(String contentType) {
        if (contentType == null || contentType.length() == 0) return true;   // 没给就当文本
        String c = contentType.toLowerCase();
        if (c.startsWith("text/")) return true;
        if (c.indexOf("json") >= 0 || c.indexOf("xml") >= 0) return true;
        if (c.indexOf("xhtml") >= 0) return true;
        return false;
    }

    /** 从 Content-Type 里取字符集（"text/html; charset=GBK" ⇒ GBK），没有返回 "" */
    public static String charsetOf(String contentType) {
        if (contentType == null) return "";
        int i = contentType.toLowerCase().indexOf("charset=");
        if (i < 0) return "";
        String v = contentType.substring(i + 8).trim();
        int semi = v.indexOf(';');
        if (semi >= 0) v = v.substring(0, semi);
        v = v.trim().replace("\"", "");
        return v.length() > 0 && v.length() < 20 ? v : "";
    }

    private static String dropBlock(String s, String tag) {
        String lower = s.toLowerCase();
        String open = "<" + tag;
        String close = "</" + tag + ">";
        int from = 0;
        while (true) {
            int a = lower.indexOf(open, from);
            if (a < 0) break;
            int b = lower.indexOf(close, a);
            if (b < 0) {
                s = s.substring(0, a);
                break;
            }
            s = s.substring(0, a) + " " + s.substring(b + close.length());
            lower = s.toLowerCase();
            from = a + 1;
        }
        return s;
    }

    private static String dropComments(String s) {
        int a = s.indexOf("<!--");
        while (a >= 0) {
            int b = s.indexOf("-->", a);
            if (b < 0) {
                s = s.substring(0, a);
                break;
            }
            s = s.substring(0, a) + " " + s.substring(b + 3);
            a = s.indexOf("<!--");
        }
        return s;
    }
}
