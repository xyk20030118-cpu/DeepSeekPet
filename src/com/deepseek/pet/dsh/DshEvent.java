package com.deepseek.pet.dsh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条会话事件 —— 与 DSH {@code session.v3.jsonl} 的**每一行**一一对应。
 *
 * <h3>格式（照抄上游，不是自创）</h3>
 * <pre>
 * {"type":"tool/call","seq":16,"time":1792121864363,"data":{...}}
 * </pre>
 * 上游实测的事件类型（{@code %APPDATA%\dsh-desktop\harness\sessions\*.jsonl.zstd} 里逐条读出来的）：
 * <ul>
 *   <li>会话层：{@code session} {@code session/title} {@code session/checkpoint}</li>
 *   <li>轮次/步骤：{@code turn/start} {@code turn/end} {@code step/start} {@code step/end}</li>
 *   <li>消息：{@code system/message} {@code user/message} {@code assistant/message} {@code tool/result}</li>
 *   <li>工具：{@code tool/call}（与 {@code assistant/message} 里的 tool-call part 一一对应）</li>
 *   <li>环境：{@code sandbox/mode} {@code approval/policy} {@code permission/preset}
 *       {@code request/header} {@code request/context}</li>
 * </ul>
 *
 * <p>★ 为什么字段名要和上游一致：桌宠这边生成的会话以后要能**双向导入导出**，
 * 桌面 harness 的会话记录也可以在手机上接着看。自创一套字段名的代价是永远对不上。
 */
public final class DshEvent {

    public static final String SESSION = "session";
    public static final String TURN_START = "turn/start";
    public static final String TURN_END = "turn/end";
    public static final String STEP_START = "step/start";
    public static final String STEP_END = "step/end";
    public static final String SYSTEM_MESSAGE = "system/message";
    public static final String USER_MESSAGE = "user/message";
    public static final String ASSISTANT_MESSAGE = "assistant/message";
    public static final String TOOL_CALL = "tool/call";
    public static final String TOOL_RESULT = "tool/result";
    public static final String SESSION_TITLE = "session/title";
    public static final String SANDBOX_MODE = "sandbox/mode";
    public static final String APPROVAL_POLICY = "approval/policy";
    public static final String REQUEST_HEADER = "request/header";
    public static final String ERROR = "error";

    public final String type;
    public final long seq;
    public final long time;
    public final Map<String, Object> data;

    public DshEvent(String type, long seq, long time, Map<String, Object> data) {
        this.type = type == null ? "" : type;
        this.seq = seq;
        this.time = time <= 0 ? System.currentTimeMillis() : time;
        this.data = data == null ? new LinkedHashMap<String, Object>() : data;
    }

    /** 单行 JSON（**不带换行**，写日志时由调用方补 {@code \n}）。 */
    public String toJsonLine() {
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        DshJson.quote(sb, "type");
        sb.append(':');
        DshJson.quote(sb, type);
        sb.append(',');
        DshJson.quote(sb, "seq");
        sb.append(':').append(seq);
        sb.append(',');
        DshJson.quote(sb, "time");
        sb.append(':').append(time);
        sb.append(',');
        DshJson.quote(sb, "data");
        sb.append(':');
        DshJson.writeTo(sb, data);
        sb.append('}');
        return sb.toString();
    }

    /** 从一行 JSON 还原；解析不出来返回 {@code null}（坏行跳过，不炸整条会话）。 */
    @SuppressWarnings("unchecked")
    public static DshEvent fromJsonLine(String line) {
        if (line == null) return null;
        Object o = com.deepseek.pet.Json.parseOrNull(line.trim());
        if (!(o instanceof Map)) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        Object t = m.get("type");
        if (!(t instanceof String)) return null;
        long sq = num(m.get("seq"), 0);
        long tm = num(m.get("time"), 0);
        Object d = m.get("data");
        Map<String, Object> data = (d instanceof Map)
                ? (Map<String, Object>) d
                : new LinkedHashMap<String, Object>();
        return new DshEvent((String) t, sq, tm, data);
    }

    private static long num(Object v, long dflt) {
        if (v instanceof Number) return ((Number) v).longValue();
        return dflt;
    }

    @Override
    public String toString() {
        return type + "#" + seq;
    }
}
