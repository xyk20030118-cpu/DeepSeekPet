package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文压缩 —— 窗口要爆的时候，砍什么、怎么砍。
 *
 * <p>顺序是**有讲究的**（先做代价最小的）：
 * <ol>
 *   <li><b>① 剪工具结果</b>：{@code read} 回来的 200 KB 文件，对"下一步该干什么"往往只需要
 *       开头和结尾。保留头 1200 字 + 尾 600 字，中间标注省略了多少 —— 信息密度最高的一刀。</li>
 *   <li><b>② 丢最老的消息</b>：还超就从头丢（跳过 system），换成一条
 *       {@code [上下文压缩] 更早的 N 条消息已省略} 的说明。模型得知道"前面有东西被删了"，
 *       否则它会以为自己一直没看过。</li>
 *   <li><b>③ 仍然超</b>：不硬砍工具调用配对（那会让 API 直接报 400），
 *       而是**返回 false** 让调用方决定（通常是缩短 max_tokens 或换更小的工具结果重来）。</li>
 * </ol>
 *
 * <p>★ 铁律：{@code assistant(tool_calls)} 与其对应的 {@code role:tool} 消息**必须同生同死**。
 * 拆开任何一对，OpenAI 兼容网关都会回 400（"tool_call_id not found"）。
 * 本类在丢弃时按"回合块"整体处理，就是为了守住这条。
 */
public final class DshCompaction {

    private DshCompaction() {}

    public static final int TOOL_HEAD_CHARS = 1200;
    public static final int TOOL_TAIL_CHARS = 600;

    /** 一次压缩的战果（给日志和 UI 用，别猜）。 */
    public static final class Report {
        public int beforeTokens;
        public int afterTokens;
        public int prunedTools;
        public int droppedMsgs;

        public boolean changed() { return prunedTools > 0 || droppedMsgs > 0; }

        @Override public String toString() {
            return "压缩 " + beforeTokens + "→" + afterTokens + " token（剪工具 " + prunedTools
                    + " 条，丢消息 " + droppedMsgs + " 条）";
        }
    }

    /**
     * 就地压缩消息数组。
     *
     * @param budget 允许的最大 token 数（{@link DshMeter#budget}）
     * @return 战果；{@code afterTokens > budget} 表示没能压到预算内
     */
    public static Report compact(List<DshWire.Msg> msgs, int budget) {
        Report r = new Report();
        r.beforeTokens = DshMeter.estimate(msgs);
        if (msgs == null || msgs.isEmpty()) {
            r.afterTokens = 0;
            return r;
        }
        // ① 剪工具结果
        if (DshMeter.estimate(msgs) > budget) {
            for (DshWire.Msg m : msgs) {
                if (!"tool".equals(m.role)) continue;
                if (m.text.length() <= TOOL_HEAD_CHARS + TOOL_TAIL_CHARS) continue;
                int omitted = m.text.length() - TOOL_HEAD_CHARS - TOOL_TAIL_CHARS;
                m.text = m.text.substring(0, TOOL_HEAD_CHARS)
                        + "\n…（此处省略 " + omitted + " 字，原文过长；需要细节请重新读取更小的范围）…\n"
                        + m.text.substring(m.text.length() - TOOL_TAIL_CHARS);
                r.prunedTools++;
                if (DshMeter.estimate(msgs) <= budget) break;
            }
        }
        // ② 丢最老的回合块（**system 永远不动**：它在最前面，丢了模型就没规矩了）
        if (DshMeter.estimate(msgs) > budget) {
            int sysEnd = 0;
            while (sysEnd < msgs.size() && "system".equals(msgs.get(sysEnd).role)) sysEnd++;
            int dropped = 0;
            while (DshMeter.estimate(msgs) > budget && msgs.size() > sysEnd + 2) {
                int cut = blockEnd(msgs, sysEnd);
                if (cut <= sysEnd) break;
                for (int i = sysEnd; i < cut; i++) {
                    DshWire.Msg m = msgs.remove(sysEnd);
                    if (!"system".equals(m.role)) dropped++;
                }
            }
            if (dropped > 0) {
                DshWire.Msg note = DshWire.Msg.user("［上下文压缩］更早的 " + dropped
                        + " 条消息已省略（内容仍保存在会话日志里）。");
                msgs.add(sysEnd, note);
                r.droppedMsgs = dropped;
            }
        }
        r.afterTokens = DshMeter.estimate(msgs);
        return r;
    }

    /**
     * 从 {@code from} 开始的第一个"完整回合块"的结束下标（不含）。
     * <p>规则：一条 assistant 消息带着 N 个 tool-call，就必须紧跟 N 条 role=tool 消息；
     * 整块一起丢。中途遇到下一条 user/assistant 消息即为块尾。
     */
    static int blockEnd(List<DshWire.Msg> msgs, int from) {
        int i = from;
        while (i < msgs.size() && "system".equals(msgs.get(i).role)) i++;
        if (i >= msgs.size()) return -1;
        DshWire.Msg first = msgs.get(i);
        if (!"assistant".equals(first.role)) {
            // user 消息自成一块
            return i + 1;
        }
        int callCount = first.calls.size();
        i++;
        int seen = 0;
        while (i < msgs.size() && seen < callCount && "tool".equals(msgs.get(i).role)) {
            i++;
            seen++;
        }
        return i;
    }

    /** 便利：复制一份再压（不想动原数组时用）。 */
    public static List<DshWire.Msg> compactCopy(List<DshWire.Msg> msgs, int budget, Report out) {
        List<DshWire.Msg> copy = new ArrayList<DshWire.Msg>(msgs.size());
        for (DshWire.Msg m : msgs) {
            DshWire.Msg c = new DshWire.Msg(m.role);
            c.text = m.text;
            c.reasoning = m.reasoning;
            c.toolCallId = m.toolCallId;
            c.name = m.name;
            c.calls.addAll(m.calls);
            copy.add(c);
        }
        Report r = compact(copy, budget);
        if (out != null) {
            out.beforeTokens = r.beforeTokens;
            out.afterTokens = r.afterTokens;
            out.prunedTools = r.prunedTools;
            out.droppedMsgs = r.droppedMsgs;
        }
        return copy;
    }
}
