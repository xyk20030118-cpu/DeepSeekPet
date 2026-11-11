package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容协议的编码/解码 —— <b>纯类</b>，harness 与模型之间的"海关"。
 *
 * <p>桌宠本来就走 OpenAI 兼容通道（{@code ApiClient.chatUrl()} / 自定义服务商），
 * 但原来的请求体只有 {@code messages}。harness 需要三样新东西，全在这个类里：
 * <ol>
 *   <li><b>tools</b>：把 {@link DshToolSpec} 编成 {@code [{"type":"function","function":{name,description,parameters}}]}；</li>
 *   <li><b>tool_calls</b>：assistant 消息里带 {@code tool_calls[]}，下一轮以 {@code role:"tool"} 回填结果；</li>
 *   <li><b>reasoning_content</b>：DeepSeek 的思维链字段，回放历史时<u>不能</u>混进正文。</li>
 * </ol>
 *
 * <p>★ 解码侧全部"容错"：模型偶尔会返回 {@code arguments} 为空串、或者把参数写成嵌套对象，
 * 这里都当**字符串**收下（上游 harness 也是这么存的），由工具执行层去解析；
 * 解析失败不等于请求失败 —— 结果是 {@code isError} 的 tool-result，模型能看到并自我修正。
 */
public final class DshWire {

    private DshWire() {}

    /** 一次工具调用（对应 content part {@code {"type":"tool-call","id":…,"name":…,"arguments":"…"}}）。 */
    public static final class Call {
        public String id = "";
        public String name = "";
        public String arguments = "";

        public Call() {}

        public Call(String id, String name, String arguments) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
            this.arguments = arguments == null ? "" : arguments;
        }

        @Override public String toString() {
            return name + "(" + DshJson.oneLineIfLong(arguments, 80) + ")";
        }
    }

    /** 协议级消息（比 DSH 事件轻一级：事件是日志，Msg 是喂给模型的那一层）。 */
    public static final class Msg {
        public String role;            // system | user | assistant | tool
        public String text = "";
        public String reasoning = "";  // 只用于 assistant 回放
        public String toolCallId = ""; // role=tool 时必填
        public String name = "";       // role=tool 时的工具名（OpenAI 允许，非必填）
        public String imageUrl = "";   // 预留：多模态
        public final List<Call> calls = new ArrayList<Call>();

        public Msg(String role) { this.role = role; }

        public static Msg system(String t) { Msg m = new Msg("system"); m.text = t == null ? "" : t; return m; }
        public static Msg user(String t) { Msg m = new Msg("user"); m.text = t == null ? "" : t; return m; }
        public static Msg assistant(String t) { Msg m = new Msg("assistant"); m.text = t == null ? "" : t; return m; }
        public static Msg tool(String callId, String name, String content) {
            Msg m = new Msg("tool");
            m.toolCallId = callId == null ? "" : callId;
            m.name = name == null ? "" : name;
            m.text = content == null ? "" : content;
            return m;
        }
    }

    /** 模型一次回复的解码结果。 */
    public static final class Reply {
        public boolean ok;
        public String text = "";
        public String reasoning = "";
        public String finishReason = "";
        public final List<Call> calls = new ArrayList<Call>();
        public int promptTokens;
        public int completionTokens;
        public String error = "";

        public boolean hasToolCalls() { return !calls.isEmpty(); }

        @Override public String toString() {
            return "Reply(ok=" + ok + ", text=" + text.length() + "B, calls=" + calls.size()
                    + ", finish=" + finishReason + ")";
        }
    }

    // ==================== 编码 ====================

    /**
     * 组装请求体。
     *
     * @param model       模型 id
     * @param msgs        历史消息（system 在最前）
     * @param tools       工具声明；空列表时**不带 tools 字段**（老服务商对空数组会报 400）
     * @param maxTokens   0 表示不指定
     * @param temperature 负值表示不指定
     */
    public static String requestBody(String model, List<Msg> msgs, List<DshToolSpec> tools,
                                     int maxTokens, double temperature) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append('{');
        DshJson.quote(sb, "model");
        sb.append(':');
        DshJson.quote(sb, model == null ? "" : model);
        sb.append(',');
        DshJson.quote(sb, "messages");
        sb.append(':');
        DshJson.writeTo(sb, messagesJson(msgs));
        sb.append(',');
        DshJson.quote(sb, "stream");
        sb.append(":false");
        if (maxTokens > 0) {
            sb.append(',');
            DshJson.quote(sb, "max_tokens");
            sb.append(':').append(maxTokens);
        }
        if (temperature >= 0) {
            sb.append(',');
            DshJson.quote(sb, "temperature");
            sb.append(':').append(temperature);
        }
        if (tools != null && !tools.isEmpty()) {
            sb.append(',');
            DshJson.quote(sb, "tools");
            sb.append(':');
            DshJson.writeTo(sb, DshToolSpec.toJsonArray(tools));
            sb.append(',');
            DshJson.quote(sb, "tool_choice");
            sb.append(':');
            DshJson.quote(sb, "auto");
        }
        sb.append('}');
        return sb.toString();
    }

    /** 把内部消息编成 OpenAI 的 messages 数组。 */
    public static List<Object> messagesJson(List<Msg> msgs) {
        List<Object> out = new ArrayList<Object>();
        if (msgs == null) return out;
        for (Msg m : msgs) {
            if (m == null || m.role == null) continue;
            Map<String, Object> o = DshJson.map("role", m.role);
            if ("assistant".equals(m.role)) {
                // assistant 的正文可以为空（纯工具调用那一轮就是空的），但字段要给 null
                o.put("content", m.text.length() == 0 ? null : m.text);
                if (!m.calls.isEmpty()) {
                    List<Object> cs = new ArrayList<Object>();
                    for (Call c : m.calls) {
                        Map<String, Object> fn = DshJson.map(
                                "name", c.name,
                                "arguments", c.arguments.length() == 0 ? "{}" : c.arguments);
                        cs.add(DshJson.map("id", c.id, "type", "function", "function", fn));
                    }
                    o.put("tool_calls", cs);
                }
            } else if ("tool".equals(m.role)) {
                o.put("tool_call_id", m.toolCallId);
                if (m.name != null && m.name.length() > 0) o.put("name", m.name);
                o.put("content", m.text);
            } else {
                o.put("content", m.text);
            }
            out.add(o);
        }
        return out;
    }

    // ==================== 解码 ====================

    /**
     * 解析一次 chat/completions 响应。
     * <p>HTTP 层只负责把响应体原样递进来（含 4xx/5xx 的 JSON 错误体），
     * 这里统一判定：有 {@code choices[0].message} 才算成功，否则把 {@code error.message} 原文带出去。
     */
    @SuppressWarnings("unchecked")
    public static Reply parseReply(String raw) {
        Reply r = new Reply();
        if (raw == null || raw.trim().length() == 0) {
            r.error = "空响应";
            return r;
        }
        Object root = com.deepseek.pet.Json.parseOrNull(raw);
        if (!(root instanceof Map)) {
            r.error = "响应不是 JSON：" + com.deepseek.pet.Json.oneLine(raw, 200);
            return r;
        }
        Map<String, Object> m = (Map<String, Object>) root;

        // ① 错误体：{"error":{"message":"…","type":"…"}}
        Object err = m.get("error");
        if (err instanceof Map) {
            Object msg = ((Map<String, Object>) err).get("message");
            r.error = msg == null ? "错误体（无 message）" : String.valueOf(msg);
            return r;
        }

        // ② 正常体
        Object choices = m.get("choices");
        if (!(choices instanceof List) || ((List<Object>) choices).isEmpty()) {
            r.error = "没有 choices：" + com.deepseek.pet.Json.oneLine(raw, 200);
            return r;
        }
        Object c0 = ((List<Object>) choices).get(0);
        if (!(c0 instanceof Map)) {
            r.error = "choices[0] 不是对象";
            return r;
        }
        Map<String, Object> choice = (Map<String, Object>) c0;
        Object fr = choice.get("finish_reason");
        r.finishReason = fr == null ? "" : String.valueOf(fr);

        Object msgObj = choice.get("message");
        if (!(msgObj instanceof Map)) {
            r.error = "choices[0].message 缺失";
            return r;
        }
        Map<String, Object> msg = (Map<String, Object>) msgObj;
        r.text = str(msg.get("content"));
        r.reasoning = pickStr(msg, "reasoning_content", "reasoning");
        Object tcs = msg.get("tool_calls");
        if (tcs instanceof List) {
            for (Object o : (List<Object>) tcs) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> tc = (Map<String, Object>) o;
                Call call = new Call(str(tc.get("id")), "", "");
                Object fn = tc.get("function");
                if (fn instanceof Map) {
                    Map<String, Object> f = (Map<String, Object>) fn;
                    call.name = str(f.get("name"));
                    // arguments 在标准里是字符串；个别网关会直接给对象 ⇒ 原样序列化回字符串
                    Object a = f.get("arguments");
                    call.arguments = (a instanceof String) ? (String) a : DshJson.write(a);
                }
                if (call.name.length() == 0) continue;
                r.calls.add(call);
            }
        }
        Object usage = m.get("usage");
        if (usage instanceof Map) {
            Map<String, Object> u = (Map<String, Object>) usage;
            r.promptTokens = (int) num(u.get("prompt_tokens"));
            r.completionTokens = (int) num(u.get("completion_tokens"));
        }
        r.ok = true;
        return r;
    }

    private static String pickStr(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            String s = str(m.get(k));
            if (s.length() > 0) return s;
        }
        return "";
    }

    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }

    private static long num(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        return 0;
    }
}
