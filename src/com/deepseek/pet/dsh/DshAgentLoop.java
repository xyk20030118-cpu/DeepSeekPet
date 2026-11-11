package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;

/**
 * agent loop —— harness 的心脏：**想一步 → 调工具 → 看结果 → 再想一步**，直到不用工具为止。
 *
 * <h3>状态机（与上游 {@code dsh-agent-loop} 同构）</h3>
 * <pre>
 * turn/start
 *   step/start → 拼请求（messages + tools）→ LLM
 *     ├─ 没有 tool-call ⇒ assistant/message 落盘 → step/end → turn/end → 结束（把正文给 UI）
 *     └─ 有 tool-call ⇒ assistant/message + 每条 tool/call + 每条 tool/result 落盘
 *                    ⇒ step/end → 下一步（带着工具结果再问一次）
 * </pre>
 *
 * <p>★ 为什么工具调用也必须落盘成事件（而不是只存在内存）：进程被杀之后要能**接着跑**，
 * 而且"模型到底调了什么、拿到什么"是排障的唯一依据（轮次72 的教训：没有现场证据就只能猜）。
 *
 * <p>★ 两个保险丝：
 * <ul>
 *   <li>{@code maxSteps}：模型可能陷入"调工具 → 报错 → 再调"的死循环，必须有硬上限；</li>
 *   <li>{@code maxTokens}：每次回复的上限，防止一段话把窗口吃掉。</li>
 * </ul>
 *
 * <p>本类是**纯逻辑**：LLM 调用与工具执行都是注入的接口，所以能在 PC 上用假模型把
 * 每一条分支（无工具/单工具/多工具/工具报错/超步数）都真跑一遍。
 */
public final class DshAgentLoop {

    /** 一次模型调用（入参是完整请求体 JSON，出参是原始响应 JSON）。 */
    public interface Llm {
        String complete(String requestBodyJson) throws Exception;
    }

    /** 进度回调（UI 用；可以为 null）。 */
    public interface Listener {
        /** 助手正文（含估算 token 数与这一步的用时 —— 桌面端会显示「用时/tokens」）。 */
        void onAssistantText(String text, int tokens, long millis);
        void onToolCall(String name, String arguments);
        void onToolResult(String name, boolean isError, String text, long millis);
        void onStepStart(int turn, int step);
        void onError(String message);
    }

    /** 运行参数。 */
    public static final class Config {
        public int maxSteps = 6;
        public int maxTokens = 2048;
        public double temperature = 0.7;
        public int contextWindow = 32000;
        public boolean compact = true;
    }

    /** 运行结果。 */
    public static final class Result {
        public boolean ok;
        public String text = "";
        public String error = "";
        public int steps;
        public boolean stoppedByLimit;
        public DshCompaction.Report compaction;

        @Override public String toString() {
            return "Result(ok=" + ok + ", steps=" + steps + ", text=" + text.length() + "B"
                    + (error.length() > 0 ? ", error=" + error : "") + ")";
        }
    }

    private final Llm llm;
    private final DshToolRegistry tools;
    private final DshPolicy policy;
    private final Config cfg;
    private Listener listener;
    /** 用户点了「停止生成」——在两个 step 之间生效（正在等 HTTP 的那一步要等它回来）。 */
    private volatile boolean cancelled;

    public DshAgentLoop(Llm llm, DshToolRegistry tools, DshPolicy policy, Config cfg) {
        this.llm = llm;
        this.tools = tools;
        this.policy = policy;
        this.cfg = cfg == null ? new Config() : cfg;
    }

    /**
     * 请求停止（线程安全，随时可调）。
     * <p>★ 生效点：下一次 step 开始前 / 模型回复返回后。**不会**打断正在进行的 HTTP 请求
     * （HTTP 层没有可移植的中断手段，硬杀线程会漏连接）——用户点停止后最多再等一次往返。
     * 这是权衡后的行为，界面上「停止生成」按下即变灰，不让用户以为按了没反应。
     */
    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public DshAgentLoop listener(Listener l) {
        this.listener = l;
        return this;
    }

    /**
     * 跑一轮。
     *
     * @param session      会话（事件会追加进去）
     * @param systemPrompt 本次的 system 提示（每次按最新策略重拼，不进日志）
     * @param model        模型 id（写进 request/header）
     */
    public Result run(DshSession session, String systemPrompt, String model) {
        Result out = new Result();
        if (session == null) {
            out.error = "会话为空";
            return out;
        }
        if (llm == null) {
            out.error = "没有配置模型通道";
            return out;
        }

        session.startTurn();
        try {
            for (int i = 0; i < Math.max(1, cfg.maxSteps); i++) {
                if (cancelled) {
                    out.ok = false;
                    out.text = session.lastAssistantText();
                    out.error = "已停止";
                    session.append(DshEvent.ERROR, DshJson.map("phase", "cancel", "message", "用户停止生成"));
                    return out;
                }
                session.startStep();
                final long stepStart = System.currentTimeMillis();
                out.steps = session.step();
                if (listener != null) listener.onStepStart(session.turn(), session.step());

                List<DshWire.Msg> msgs = session.project(systemPrompt);

                // ① 预算检查 + 压缩（只改这次的视图，不动日志）
                int budget = DshMeter.budget(cfg.contextWindow, cfg.maxTokens);
                if (cfg.compact) {
                    DshCompaction.Report rep = new DshCompaction.Report();
                    List<DshWire.Msg> compacted = DshCompaction.compactCopy(msgs, budget, rep);
                    if (rep.changed()) {
                        msgs = compacted;
                        out.compaction = rep;
                    }
                }

                List<DshToolSpec> specs = tools == null || tools.size() == 0
                        ? new ArrayList<DshToolSpec>() : tools.specs();

                if (session.step() == 1) {
                    // 请求头与上下文各记一条（上游同款），排障时"发的什么/带没带工具"一目了然
                    java.util.Map<String, Object> hdr = DshJson.map(
                            "config", DshJson.map("model", model == null ? "" : model,
                                    "maxTokens", cfg.maxTokens, "temperature", cfg.temperature));
                    hdr.put("tools", DshToolSpec.toJsonArray(specs));
                    session.append(DshEvent.REQUEST_HEADER, DshJson.map("header", hdr));
                    session.append("request/context", DshJson.map(
                            "model", model == null ? "" : model,
                            "contextWindow", cfg.contextWindow,
                            "estimatedTokens", DshMeter.estimate(msgs)
                                    + DshMeter.estimateTools(specs),
                            "budget", budget));
                }

                String body = DshWire.requestBody(model, msgs, specs, cfg.maxTokens, cfg.temperature);
                String raw;
                try {
                    raw = llm.complete(body);
                } catch (Exception ex) {
                    out.error = "模型调用失败：" + ex.getMessage();
                    session.append(DshEvent.ERROR, DshJson.map("phase", "llm", "message", out.error));
                    if (listener != null) listener.onError(out.error);
                    return out;
                }

                DshWire.Reply reply = DshWire.parseReply(raw);
                if (!reply.ok) {
                    out.error = "模型返回异常：" + reply.error;
                    session.append(DshEvent.ERROR, DshJson.map("phase", "llm", "message", out.error));
                    if (listener != null) listener.onError(out.error);
                    return out;
                }

                session.assistantMessage(reply.text, reply.reasoning, reply.calls);
                if (reply.text.length() > 0 && listener != null) {
                    listener.onAssistantText(reply.text, DshMeter.estimate(reply.text),
                            System.currentTimeMillis() - stepStart);
                }

                // 模型已经回来了，但用户在我发出请求之后点了「停止生成」⇒ 不再执行工具，直接收尾
                if (cancelled) {
                    out.ok = false;
                    out.text = reply.text;
                    out.error = "已停止";
                    session.endStep();
                    session.append(DshEvent.ERROR, DshJson.map("phase", "cancel", "message", "用户停止生成"));
                    return out;
                }

                if (!reply.hasToolCalls()) {
                    // 收工：没有工具调用 = 这一轮说完了
                    out.ok = true;
                    out.text = reply.text;
                    session.endStep();
                    session.endTurn();
                    return out;
                }

                // ② 有工具调用：逐个执行，结果回填（顺序与 calls 一致 —— 网关要求一一对应）
                for (DshWire.Call c : reply.calls) {
                    session.toolCall(c);
                    if (listener != null) listener.onToolCall(c.name, c.arguments);
                    DshToolRegistry.Result r = tools.execute(c.name, c.arguments, policy);
                    session.toolResult(c, r.text, r.isError);
                    if (listener != null) listener.onToolResult(c.name, r.isError, r.text, r.millis);
                }
                session.endStep();
            }
            // 走到这里 = 用完了步数还在调工具
            out.ok = false;
            out.stoppedByLimit = true;
            out.text = session.lastAssistantText();
            out.error = "已达到单轮最大步数（" + cfg.maxSteps + "），先停在这里";
            session.append(DshEvent.ERROR, DshJson.map("phase", "limit", "message", out.error));
            session.endTurn();
            return out;
        } finally {
            // 任何异常路径都不留半个 turn（幂等：正常收尾时最后一条已经是 turn/end）
            if (!session.events.isEmpty()) {
                String last = session.events.get(session.events.size() - 1).type;
                if (!DshEvent.TURN_END.equals(last)) {
                    session.endTurn();
                }
            }
        }
    }
}
