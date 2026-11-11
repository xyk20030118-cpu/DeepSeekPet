package com.deepseek.pet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 解析 + **路径取值** —— <b>纯类，零 Android 依赖</b>（fpstest 里做真断言）。
 *
 * <h3>为什么自己写一个</h3>
 * 轮次㉘ 要把"搜索服务"做成**可插拔**的：任何搜索 API 接进来，我们都要能从它的 JSON 响应里
 * 按**用户配置的路径**取出标题/链接/摘要/正文。纯类不能用 {@code org.json}（那是 Android 的），
 * 也不想为它引依赖 ⇒ 自己写一个够用的：
 * <ul>
 *   <li>解析：对象 / 数组 / 字符串（含转义）/ 数字 / true / false / null；</li>
 *   <li>取值：{@link #path} 支持 {@code a.b}、{@code a[0].b}、<b>{@code a[].b}（把数组摊平）</b>
 *       —— 最后这个就是"结果列表 → 每条标题"的写法；</li>
 *   <li>{@link #strs} 把取到的值统一成字符串列表（数组摊平、null 丢掉）。
 * </ul>
 * 不追求完整规范（不做 unicode 代理对合并、不做大数精度），够解析各家搜索响应即可 ——
 * 覆盖面由 fpstest 的真实报文 fixture 盯着。
 */
public final class Json {

    private Json() {}

    /** 解析失败（错误信息里带位置，便于定位"用户贴的配置写错了"） */
    public static final class Err extends Exception {
        public Err(String m) { super(m); }
    }

    // ==================== 解析 ====================

    public static Object parse(String s) throws Err {
        if (s == null) throw new Err("空文本");
        P p = new P(s);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) throw new Err("第 " + p.i + " 个字符起有多余内容");
        return v;
    }

    /** 解析失败就返回 null（调用方按"解析不出来"处理，不抛给界面） */
    public static Object parseOrNull(String s) {
        try {
            return parse(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { i++; continue; }
                // ★ 顺手认 `//` 与 `/* */` 注释：用户是**手写/粘贴**配置的，
                //   带注释的 JSON 很常见（"照抄模板却报错"最气人）。标准 JSON 没有注释，这里宽容一点。
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                    int e = s.indexOf('\n', i);
                    i = (e < 0) ? s.length() : e + 1;
                    continue;
                }
                if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                    int e = s.indexOf("*/", i + 2);
                    i = (e < 0) ? s.length() : e + 2;
                    continue;
                }
                break;
            }
        }

        Object value() throws Err {
            if (i >= s.length()) throw new Err("内容不完整");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }

        Map<String, Object> obj() throws Err {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            i++;                       // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw new Err("第 " + i + " 个字符：对象键要以引号开始");
                String k = str();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new Err("第 " + i + " 个字符：键后面缺冒号");
                i++;
                ws();
                m.put(k, value());
                ws();
                if (i >= s.length()) throw new Err("对象没有闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == '}') { i++; return m; }
                throw new Err("第 " + i + " 个字符：对象里出现意外字符 " + c);
            }
        }

        List<Object> arr() throws Err {
            List<Object> a = new ArrayList<Object>();
            i++;                       // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
            while (true) {
                ws();
                a.add(value());
                ws();
                if (i >= s.length()) throw new Err("数组没有闭合");
                char c = s.charAt(i);
                if (c == ',') { i++; continue; }
                if (c == ']') { i++; return a; }
                throw new Err("第 " + i + " 个字符：数组里出现意外字符 " + c);
            }
        }

        String str() throws Err {
            i++;                       // 开引号
            StringBuilder b = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw new Err("\\u 后不足 4 位");
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: b.append(e);   // \" \\ \/ 等
                }
            }
            throw new Err("字符串没有闭合");
        }

        Double num() throws Err {
            int st = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') i++;
                else break;
            }
            if (st == i) throw new Err("第 " + i + " 个字符：不是合法的值");
            try {
                return Double.valueOf(s.substring(st, i));
            } catch (Throwable t) {
                throw new Err("数字解析失败：" + s.substring(st, i));
            }
        }
    }

    // ==================== 取值 ====================

    /**
     * 按路径取值。语法：
     * <pre>
     *   a.b          取对象的 b
     *   a[0].b       取数组第 0 个的 b
     *   a[].b        ★ 显式摊平：返回"每个元素的 b"组成的列表
     *   a.b[].c      混着写也行
     *   （数组上继续写名字也会**隐式摊平**：results.title 等价于 results[].title）
     * </pre>
     * 取不到返回 null（不抛异常 —— 用户配置写错时按"这条字段没有"处理）。
     *
     * <p>★ 摊平必须**继续往下走完剩下的路径**（{@code chunks[].text} 才是段落文本）。
     * 早期版本只把数组转成列表就返回，于是 {@code chunks[].text} 拿到的是**对象列表**、
     * 再取 {@code text} 就成了 null —— fpstest [26] 当场报红（这就是"断言要跑真形状"的价值）。
     */
    public static Object path(Object root, String path) {
        if (root == null) return null;
        if (path == null || path.trim().length() == 0) return root;
        return walk(root, split(path.trim()));
    }

    private static Object walk(Object cur, List<String> segs) {
        if (cur == null || segs.isEmpty()) return cur;
        String seg = segs.get(0);
        List<String> rest = segs.subList(1, segs.size());

        boolean flat = seg.endsWith("[]");
        String name = flat ? seg.substring(0, seg.length() - 2) : seg;
        int idx = -1;
        int br = name.indexOf('[');
        if (br >= 0 && name.endsWith("]")) {
            try {
                idx = Integer.parseInt(name.substring(br + 1, name.length() - 1));
            } catch (Throwable ignore) {
                idx = -1;
            }
            name = name.substring(0, br);
        }

        // ★ 隐式摊平：当前位置是数组、而这一段只是字段名（不是下标）⇒ 对每个元素应用**同一段**
        if (cur instanceof List && idx < 0 && !flat) {
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) cur) out.add(walk(o, segs));
            return out;
        }

        Object next = cur;
        if (name.length() > 0) {
            if (!(next instanceof Map)) return null;
            next = ((Map<?, ?>) next).get(name);
        }
        if (flat) {
            if (!(next instanceof List)) return null;
            List<Object> out = new ArrayList<Object>();
            for (Object o : (List<?>) next) out.add(walk(o, rest));
            return out;
        }
        if (idx >= 0) {
            if (!(next instanceof List) || ((List<?>) next).size() <= idx) return null;
            return walk(((List<?>) next).get(idx), rest);
        }
        return walk(next, rest);
    }

    /**
     * 取一个字段并摊平成字符串：<b>{@code a[].text} 取到的是一个列表，这里合并成一段文字</b>
     * （搜索 API 的"相关段落"就是这种形状）。
     */
    public static String text(Object root, String path) {
        Object v = path(root, path);
        List<String> all = strs(v);
        StringBuilder b = new StringBuilder();
        for (String s : all) {
            if (s == null || s.trim().length() == 0) continue;
            if (b.length() > 0) b.append('\n');
            b.append(s.trim());
        }
        return b.toString();
    }

    /** 把值统一成字符串列表（数组摊平、Map 丢掉、数字/布尔转文字、null 丢） */
    public static List<String> strs(Object v) {
        List<String> out = new ArrayList<String>();
        collect(v, out);
        return out;
    }

    private static void collect(Object v, List<String> out) {
        if (v == null) return;
        if (v instanceof List) {
            for (Object o : (List<?>) v) collect(o, out);
            return;
        }
        if (v instanceof Map) return;                 // 对象不能当文字用
        if (v instanceof Double) {
            double d = (Double) v;
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                out.add(String.valueOf((long) d));
            } else {
                out.add(String.valueOf(d));
            }
            return;
        }
        out.add(String.valueOf(v));
    }

    /** 路径切成段（`a.b[0].c` → [a, b[0], c]） */
    static List<String> split(String path) {
        List<String> out = new ArrayList<String>();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (b.length() > 0) { out.add(b.toString()); b.setLength(0); }
            } else {
                b.append(c);
            }
        }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }

    /** 给日志/错误提示用：把一段文本压成一行短的 */
    public static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
