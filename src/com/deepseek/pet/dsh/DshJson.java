package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极小的 JSON 输出器 —— <b>纯类，零 Android 依赖</b>。
 *
 * <h3>为什么自己写而不是用 org.json</h3>
 * 本包的"纯类"要能在 PC 上用光秃秃的 {@code javac + java} 跑起来（{@code build/dshtest}），
 * 一旦 import {@code org.json} 就必须挂 {@code android.jar} 或者额外 jar（那是 Android 的
 * 桩实现，方法体全是 {@code throw new RuntimeException("Stub!")}，能编不能跑）。
 * 解析侧复用工程已有的 {@code com.deepseek.pet.Json}（纯类），输出侧只差这一个 30 行的写入器。
 *
 * <p>覆盖：{@code Map} / {@code List} / {@code String} / 数字 / 布尔 / {@code null}。
 * 不做美化、不做浮点精度处理 —— DSH 的事件日志是**一行一条**，美化反而坏格式。
 */
public final class DshJson {

    private DshJson() {}

    /** 把任意受支持的值写成 JSON 文本。 */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        writeTo(sb, v);
        return sb.toString();
    }

    /** 写进一个已有的 StringBuilder（事件拼装时省一次分配）。 */
    public static void writeTo(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(((Boolean) v).booleanValue() ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append('0');
            else sb.append(trimNumber(d));
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeTo(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                writeTo(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Object[]) {
            sb.append('[');
            Object[] arr = (Object[]) v;
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(',');
                writeTo(sb, arr[i]);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    /** 数字：整数不带小数点，其余去掉尾随 0（{@code 0.5} 不写成 {@code 0.500000}）。 */
    private static String trimNumber(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return String.valueOf((long) d);
        }
        String s = String.valueOf(d);
        if (s.indexOf('E') >= 0 || s.indexOf('e') >= 0) return s;
        if (s.indexOf('.') >= 0) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s;
    }

    /** 字符串转义（控制字符走 {@code \\u00XX}，与标准 JSON 一致）。 */
    public static void quote(StringBuilder sb, String s) {
        sb.append('"');
        int n = s == null ? 0 : s.length();
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int k = hex.length(); k < 4; k++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 超长就截断加省略号（日志与 toString 里用，别把 50 KB 参数塞进一行）。 */
    public static String oneLineIfLong(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ');
        if (one.length() <= max) return one;
        return one.substring(0, max) + "…";
    }

    /** 便利：新建一个保持插入顺序的 map（事件字段顺序要稳定，便于人工比对）。 */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    /** 便利：可变长列表。 */
    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<Object>();
        for (Object o : items) l.add(o);
        return l;
    }
}
