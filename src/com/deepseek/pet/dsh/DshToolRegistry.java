package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表 —— 白名单 + 参数解析 + 权限闸 + 错误归一。
 *
 * <p>三件事都在这里做，工具实现里**一行都不重复**：
 * <ol>
 *   <li><b>白名单</b>：只有注册过的名字能被调用（模型幻觉出来的工具名 ⇒ 明确的错误结果）；</li>
 *   <li><b>参数解析</b>：{@code arguments} 是模型给的 JSON 字符串，可能缺参、可能不是 JSON；</li>
 *   <li><b>权限闸</b>：{@link DshPolicy#allows} 不放行就拒绝，且**错误文案写清楚当前档** ——
 *       模型会据此换一条路（比如改用 {@code list} 而不是 {@code write}）。</li>
 * </ol>
 *
 * <p>★ 所有失败都变成 {@code isError=true} 的 tool-result，不抛给会话层：
 * 一次工具失败不该让整轮对话失败，否则用户看到的就是"它不理我了"。
 */
public final class DshToolRegistry {

    /** 一次工具执行的结果。 */
    public static final class Result {
        public final String name;
        public final String text;
        public final boolean isError;
        public final long millis;

        Result(String name, String text, boolean isError, long millis) {
            this.name = name == null ? "" : name;
            this.text = text == null ? "" : text;
            this.isError = isError;
            this.millis = millis;
        }

        static Result ok(String name, String text, long ms) { return new Result(name, text, false, ms); }
        static Result err(String name, String text, long ms) { return new Result(name, text, true, ms); }

        @Override public String toString() {
            return name + (isError ? " !" : " ok") + " " + millis + "ms";
        }
    }

    private final Map<String, DshTool> tools = new LinkedHashMap<String, DshTool>();

    public DshToolRegistry add(DshTool tool) {
        if (tool != null && tool.spec() != null && tool.spec().name.length() > 0) {
            tools.put(tool.spec().name, tool);
        }
        return this;
    }

    public boolean has(String name) { return tools.containsKey(name); }

    public int size() { return tools.size(); }

    public List<DshToolSpec> specs() {
        List<DshToolSpec> out = new ArrayList<DshToolSpec>();
        for (DshTool t : tools.values()) out.add(t.spec());
        return out;
    }

    public List<String> names() {
        return new ArrayList<String>(tools.keySet());
    }

    /**
     * 执行一次调用。
     *
     * @param argsJson 模型给的 {@code arguments}（JSON 字符串；空/null 当空对象）
     */
    public Result execute(String name, String argsJson, DshPolicy policy) {
        long t0 = System.currentTimeMillis();
        DshTool tool = tools.get(name);
        if (tool == null) {
            return Result.err(name, "未知工具：" + name + "（可用：" + join(names()) + "）",
                    System.currentTimeMillis() - t0);
        }
        if (policy != null && !policy.allows(name)) {
            return Result.err(name,
                    "权限不足：当前档 " + DshPolicy.name(policy.mode) + " 不允许 " + name
                            + "（该工具需要 " + DshPolicy.name(DshPolicy.minModeOf(name)) + "）",
                    System.currentTimeMillis() - t0);
        }
        Map<String, Object> args = parseArgs(argsJson);
        if (args == null) {
            return Result.err(name, "参数不是合法 JSON 对象：" + DshJson.oneLineIfLong(argsJson, 200),
                    System.currentTimeMillis() - t0);
        }
        try {
            String text = tool.run(args);
            return Result.ok(name, text == null ? "（无输出）" : text,
                    System.currentTimeMillis() - t0);
        } catch (DshTool.ToolException te) {
            return Result.err(name, te.getMessage(), System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            // 工具实现里的意外异常（空指针 / IO）也归一成结果，附类名便于排查
            return Result.err(name, t.getClass().getSimpleName() + ": " + t.getMessage(),
                    System.currentTimeMillis() - t0);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null) return new LinkedHashMap<String, Object>();
        String s = argsJson.trim();
        if (s.length() == 0) return new LinkedHashMap<String, Object>();
        Object o = com.deepseek.pet.Json.parseOrNull(s);
        if (o instanceof Map) return (Map<String, Object>) o;
        return null;
    }

    static String join(List<String> xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(xs.get(i));
        }
        return sb.toString();
    }

    // ==================== 常用参数取值（工具实现共用） ====================

    /** 取字符串参数；缺失返回 defaultValue。 */
    public static String str(Map<String, Object> args, String key, String defaultValue) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return defaultValue;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    /** 取整数参数（容忍模型给 "10" 这种字符串）。 */
    public static int num(Map<String, Object> args, String key, int defaultValue) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException ignore) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** 必填字符串参数；缺失抛 ToolException（带可操作的提示）。 */
    public static String required(Map<String, Object> args, String key) throws DshTool.ToolException {
        String v = str(args, key, "");
        if (v.trim().length() == 0) {
            throw new DshTool.ToolException("缺少参数 " + key);
        }
        return v;
    }
}
