package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个会话 —— 事件列表 + **投影**（把事件重放成喂给模型的消息）。
 *
 * <h3>为什么要"投影"这一层</h3>
 * 上游 DSH 也是这么分的：事件日志是**事实**（只追加、可回放、可审计），
 * 消息数组是**视图**（每次请求按当前策略重新拼）。
 * 好处很实在：
 * <ul>
 *   <li>上下文压缩只影响"这次发什么"，不篡改历史事实；</li>
 *   <li>用户删掉一条气泡、切换人格、换工具集，都只是重新投影，不用改日志；</li>
 *   <li>会话可以被打断/恢复（进程被杀也不丢已写的事件）。</li>
 * </ul>
 *
 * <p>数据形状与上游 {@code session.v3} 一致，见 {@link #assistantData} / {@link #toolResultData}。
 */
public final class DshSession {

    public final String id;
    public String title = "";
    public final long createdAt;
    /** 工作区（手机上是沙箱根的展示名，例如 "手机沙箱"）。 */
    public final String cwd;
    public final List<DshEvent> events = new ArrayList<DshEvent>();
    private long seq;
    private int turn;
    private int step;

    /** 事件出口（写盘/推 UI）；为 null 表示只在内存里跑（测试用）。 */
    public interface Sink {
        void onEvent(DshEvent e);
    }

    private Sink sink;

    public DshSession(String id, String title, long createdAt, String cwd) {
        this.id = id == null ? "" : id;
        this.title = title == null ? "" : title;
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.cwd = cwd == null ? "" : cwd;
    }

    public void setSink(Sink s) { this.sink = s; }

    public int turn() { return turn; }
    public int step() { return step; }

    /** 追加一条事件（自动编 seq/time，推给 sink）。 */
    public DshEvent append(String type, Map<String, Object> data) {
        DshEvent e = new DshEvent(type, seq++, 0, data);
        events.add(e);
        if (sink != null) sink.onEvent(e);
        return e;
    }

    /** 从已有事件回填（恢复会话时用；seq 自动接上）。 */
    public void restore(List<DshEvent> loaded, Sink s) {
        if (loaded != null) {
            for (DshEvent e : loaded) {
                events.add(e);
                if (e.seq >= seq) seq = e.seq + 1;
            }
        }
        this.sink = s;
    }

    // ==================== 事件构造（形状与上游一致） ====================

    public DshEvent appendSession(long time) {
        Map<String, Object> d = DshJson.map(
                "id", id,
                "version", 3,
                "cwd", cwd,
                "createdAt", createdAt,
                "origin", "mobile",
                "agentPreset", "standard");
        return append(DshEvent.SESSION, d);
    }

    public DshEvent appendSandbox(String modeName) {
        return append(DshEvent.SANDBOX_MODE, DshJson.map("mode", modeName, "source", "settings"));
    }

    public DshEvent appendApproval(String policyName) {
        return append(DshEvent.APPROVAL_POLICY, DshJson.map("policy", policyName, "source", "settings"));
    }

    public DshEvent startTurn() {
        turn++;
        step = 0;
        return append(DshEvent.TURN_START, DshJson.map("turn", turn));
    }

    public DshEvent endTurn() {
        return append(DshEvent.TURN_END, DshJson.map("turn", turn));
    }

    public DshEvent startStep() {
        step++;
        return append(DshEvent.STEP_START, DshJson.map("turn", turn, "step", step));
    }

    public DshEvent endStep() {
        return append(DshEvent.STEP_END, DshJson.map("turn", turn, "step", step));
    }

    public DshEvent systemMessage(String text) {
        Map<String, Object> d = baseMsgData();
        d.put("message", message("system", textParts(text)));
        return append(DshEvent.SYSTEM_MESSAGE, d);
    }

    public DshEvent userMessage(String text) {
        Map<String, Object> d = DshJson.map("turn", turn, "step", step);
        d.put("content", textParts(text));
        return append(DshEvent.USER_MESSAGE, d);
    }

    /**
     * 给会话起标题：**只记一次**（第一条用户消息）。
     *
     * <p>与上游同名事件 {@code session/title}；左栏用它代替一串 id。
     * 已经有过标题就返回 null（不覆盖用户已经认得的那个名字）。
     */
    public DshEvent setTitle(String text) {
        if (title != null && title.trim().length() > 0) return null;
        String t = DshJson.oneLineIfLong(text == null ? "" : text.trim(), 28);
        if (t == null || t.trim().length() == 0) return null;
        title = t.trim();
        return append(DshEvent.SESSION_TITLE, DshJson.map("title", title, "source", "user"));
    }

    /** assistant 消息：正文 + 思维链 + 工具调用（同一轮里的工具调用存进同一条消息）。 */
    public DshEvent assistantMessage(String text, String reasoning, List<DshWire.Call> calls) {
        Map<String, Object> d = baseMsgData();
        List<Object> parts = new ArrayList<Object>();
        if (reasoning != null && reasoning.length() > 0) {
            parts.add(DshJson.map("type", "reasoning", "text", reasoning));
        }
        if (text != null && text.length() > 0) {
            parts.add(DshJson.map("type", "text", "text", text));
        }
        if (calls != null) {
            for (DshWire.Call c : calls) {
                parts.add(DshJson.map("type", "tool-call", "id", c.id, "name", c.name,
                        "arguments", c.arguments));
            }
        }
        Map<String, Object> m = DshJson.map("role", "assistant");
        m.put("content", parts);
        m.put("source", DshJson.map("kind", "model"));
        d.put("message", m);
        return append(DshEvent.ASSISTANT_MESSAGE, d);
    }

    /** 工具调用事件（一条 call 一条事件，便于按 id 对账）。 */
    public DshEvent toolCall(DshWire.Call c) {
        Map<String, Object> d = baseMsgData();
        d.put("callId", c.id);
        d.put("name", c.name);
        d.put("arguments", c.arguments);
        return append(DshEvent.TOOL_CALL, d);
    }

    /** 工具结果事件（含 isError —— 上游同名同字段）。 */
    public DshEvent toolResult(DshWire.Call c, String text, boolean isError) {
        Map<String, Object> inner = DshJson.map("type", "tool-result", "toolCallId", c.id);
        inner.put("content", DshJson.list(DshJson.map("type", "text", "text", text)));
        inner.put("isError", Boolean.valueOf(isError));
        Map<String, Object> msg = DshJson.map("role", "user");
        msg.put("source", DshJson.map("kind", "tool", "callId", c.id, "name", c.name));
        msg.put("content", DshJson.list(inner));
        Map<String, Object> d = baseMsgData();
        d.put("message", msg);
        return append(DshEvent.TOOL_RESULT, d);
    }

    private Map<String, Object> baseMsgData() {
        return DshJson.map("turn", turn, "step", step);
    }

    private static List<Object> textParts(String text) {
        return DshJson.list(DshJson.map("type", "text", "text", text == null ? "" : text));
    }

    private static Map<String, Object> message(String role, List<Object> parts) {
        Map<String, Object> m = DshJson.map("role", role);
        m.put("content", parts);
        return m;
    }

    // ==================== 投影：事件 → 消息 ====================

    /**
     * 把事件重放成消息数组。
     *
     * @param systemPrefix 这次的 system 提示（不在日志里，每次都按最新策略重拼）
     */
    public List<DshWire.Msg> project(String systemPrefix) {
        List<DshWire.Msg> out = new ArrayList<DshWire.Msg>();
        if (systemPrefix != null && systemPrefix.length() > 0) {
            out.add(DshWire.Msg.system(systemPrefix));
        }
        for (DshEvent e : events) {
            String t = e.type;
            if (DshEvent.USER_MESSAGE.equals(t)) {
                DshWire.Msg m = DshWire.Msg.user(joinTextParts(e.data.get("content")));
                if (m.text.length() > 0) out.add(m);
            } else if (DshEvent.ASSISTANT_MESSAGE.equals(t)) {
                DshWire.Msg m = DshWire.Msg.assistant("");
                Map<String, Object> msg = asMap(e.data.get("message"));
                if (msg != null) {
                    fillAssistant(m, msg.get("content"));
                }
                if (m.text.length() > 0 || !m.calls.isEmpty()) out.add(m);
            } else if (DshEvent.TOOL_RESULT.equals(t)) {
                Map<String, Object> msg = asMap(e.data.get("message"));
                String callId = "";
                String name = "";
                String text = "";
                boolean isError = false;
                if (msg != null) {
                    Map<String, Object> src = asMap(msg.get("source"));
                    if (src != null) {
                        callId = str(src.get("callId"));
                        name = str(src.get("name"));
                    }
                    Object content = msg.get("content");
                    if (content instanceof List) {
                        for (Object o : (List<?>) content) {
                            Map<String, Object> part = asMap(o);
                            if (part == null) continue;
                            if (!"tool-result".equals(str(part.get("type")))) continue;
                            if (callId.length() == 0) callId = str(part.get("toolCallId"));
                            Object ic = part.get("isError");
                            isError = (ic instanceof Boolean) && ((Boolean) ic).booleanValue();
                            Object inner = part.get("content");
                            if (inner instanceof List) text = joinTextParts(inner);
                        }
                    }
                }
                out.add(DshWire.Msg.tool(callId, name, isError ? ("[工具报错] " + text) : text));
            }
            // system/message 由 systemPrefix 统一提供（日志里的旧 system 不再重复投喂）
        }
        return out;
    }

    private static void fillAssistant(DshWire.Msg m, Object content) {
        if (!(content instanceof List)) return;
        for (Object o : (List<?>) content) {
            Map<String, Object> part = asMap(o);
            if (part == null) continue;
            String type = str(part.get("type"));
            if ("text".equals(type)) {
                String s = str(part.get("text"));
                m.text = m.text.length() == 0 ? s : (m.text + "\n" + s);
            } else if ("reasoning".equals(type)) {
                String s = str(part.get("text"));
                m.reasoning = m.reasoning.length() == 0 ? s : (m.reasoning + s);
            } else if ("tool-call".equals(type)) {
                m.calls.add(new DshWire.Call(str(part.get("id")), str(part.get("name")),
                        str(part.get("arguments"))));
            }
        }
    }

    private static String joinTextParts(Object content) {
        if (!(content instanceof List)) return content == null ? "" : str(content);
        StringBuilder sb = new StringBuilder();
        for (Object o : (List<?>) content) {
            Map<String, Object> part = asMap(o);
            if (part == null) continue;
            String type = str(part.get("type"));
            if (type.length() == 0 || "text".equals(type)) {
                String s = str(part.get("text"));
                if (s.length() == 0) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(s);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (o instanceof Map) ? (Map<String, Object>) o : null;
    }

    private static String str(Object o) {
        return o == null ? "" : (o instanceof String ? (String) o : String.valueOf(o));
    }

    /** 最后一条 assistant 的正文字（气泡显示用）。 */
    public String lastAssistantText() {
        for (int i = events.size() - 1; i >= 0; i--) {
            DshEvent e = events.get(i);
            if (!DshEvent.ASSISTANT_MESSAGE.equals(e.type)) continue;
            Map<String, Object> msg = asMap(e.data.get("message"));
            if (msg == null) continue;
            DshWire.Msg m = DshWire.Msg.assistant("");
            fillAssistant(m, msg.get("content"));
            if (m.text.length() > 0) return m.text;
        }
        return "";
    }

    /** 供 meta.json 用的会话摘要。 */
    public Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("title", title);
        m.put("createdAt", createdAt);
        m.put("cwd", cwd);
        m.put("events", events.size());
        m.put("turn", turn);
        return m;
    }
}
