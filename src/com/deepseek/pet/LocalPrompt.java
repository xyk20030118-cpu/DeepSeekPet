package com.deepseek.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地小模型的**提示词与回复清理**（轮次64 第三轮 · 对话接线）—— 纯函数，零 Android 依赖。
 *
 * <p>用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
 * 第一轮做了引擎（{@link LocalLlm}）、第二轮做了导入（{@code ModelStore} / {@code ModelImporter}），
 * 这一轮把对话接上。接线时最先撞到的一个事实是：
 *
 * <h3>★★ {@link LocalLlm#generate} 收的是**一条裸字符串**，不是消息数组</h3>
 * 在线那条链（{@code ApiClient}）发的是 OpenAI 的 {@code messages} JSON，
 * <b>对话模板由服务端按模型自己套</b>；本地引擎没这个待遇 ——
 * JNI 里是直接 {@code llama_tokenize} 然后预填充（见 {@code jni/dspllm_jni.cpp}），
 * <b>没有任何 chat template</b>。所以模板必须在这里拼出来，而且拼错了不会报错，
 * 只会看到"模型答非所问 / 复读 / 把问题也一起答了"这种**软故障**。
 *
 * <h3>★ 为什么模板要按模型名分</h3>
 * 用户导入的是**任意** GGUF（我们不做 App 内下载，见第一轮四个拍板）。
 * 而 GGUF 里虽然有 {@code tokenizer.chat_template}，我们**没有去读它** ——
 * 读它要么加 JNI（动原生库 = 重新交叉编译 + 六个 .so 全换），
 * 要么在 Java 侧用迷你 jinja 解释器（几十 KB 的坑，而且大概率解释不对）。
 * 两端都不划算，于是按**模型名前缀**认模板：
 * <table border="1">
 *   <tr><th>模板</th><th>谁用</th></tr>
 *   <tr><td>{@link #T_CHATML}</td><td>Qwen2/2.5、InternLM、DeepSeek 蒸馏版、GLM、MiniCPM —— **绝大多数小模型**</td></tr>
 *   <tr><td>{@link #T_LLAMA3}</td><td>Llama-3 / 3.1 / 3.2 的 instruct 版</td></tr>
 *   <tr><td>{@link #T_GEMMA}</td><td>Gemma / Gemma-2（没有独立 system 轮，系统段并进第一条 user）</td></tr>
 *   <tr><td>{@link #T_PLAIN}</td><td>兜底：纯文本「### 用户 / ### 助手」</td></tr>
 * </table>
 * ★ 认不出就**默认 ChatML**：HF 上 0.5B~3B 的中文 instruct 模型绝大多数是 Qwen 系，
 * 而 ChatML 对别的模型也不算灾难（比"完全没有角色标记"强）。
 *
 * <h3>★ 为什么模板与预算要放在纯类里</h3>
 * 这两件事**全是可以离线真跑的算术**：字符串怎么拼、上下文装不装得下、
 * 回复要不要清引号。放进纯类就能被 {@code build/fpstest} 用光秃秃的 javac 真编真跑
 * （{@code [34] LocalPrompt}）—— 而"模板拼错"恰恰是**真机上最难看出来**的那类问题。
 */
public final class LocalPrompt {

    private LocalPrompt() {}

    // ------------------------------------------------------------------ 模板

    /** ChatML（Qwen 系最常用）：{@code <|im_start|>role\n内容<|im_end|>} */
    public static final String T_CHATML = "chatml";
    /** Llama-3：{@code <|start_header_id|>role<|end_header_id|>\n\n内容<|eot_id|>} */
    public static final String T_LLAMA3 = "llama3";
    /** Gemma：{@code <start_of_turn>user\n…<end_of_turn>}（role 只有 user / model） */
    public static final String T_GEMMA = "gemma";
    /** 兜底：纯文本标记 */
    public static final String T_PLAIN = "plain";

    /** 流式回调**最多**多久刷一次界面（毫秒）。
     *
     * <p>★ 这条来自第二轮的教训：回调频率本身就是一种资源 ——
     * 一个 token 刷一次气泡 = 每秒几十次 measure/layout，
     * 而手机上的小模型（0.5B）在一部中端机上也就每秒十几到几十个 token。
     * 250 ms 既看得出"在出字"，又不会把 UI 线程压住。 */
    public static final long FLUSH_MS = 250;

    // ------------------------------------------------------------------ 采样温度

    /** 本地采样温度的下限/上限与默认值。★★ 上限就是"别把在线那套高温搬过来"。 */
    public static final float TEMP_MIN = 0.5f;
    public static final float TEMP_MAX = 1.0f;
    public static final float TEMP_DEF = 0.8f;

    /** 上下文里给采样器留的余量（BOS/EOS、模板标记这些零碎） */
    private static final int CTX_RESERVE = 64;
    /** 提示词预算的硬下限：再小就没法聊了（真超了让引擎报"ctx 太小"更好） */
    private static final int MIN_PROMPT_TOKENS = 128;

    /**
     * 引擎默认的上下文长度与单次输出上限。
     *
     * <p>★★ 这里是**抄了一份常量**而不是 {@code LocalLlm.DEFAULT_CTX} ——
     * 看起来是重复，其实是必需的：本类是"纯类"（要被 {@code build/fpstest}
     * 用光秃秃的 javac 真编真跑），而 {@link LocalLlm} **引了 android.util.Log**，
     * 纯 javac 编不过它。fpstest 第一次跑就把这条抓出来了
     * （{@code 找不到符号 LocalLlm}）—— 这正是「纯类靠真编译证明自己纯」的价值。
     * ★ 两处数值必须一致，有断言钉着（见 {@code verify_local_llm_chat.py}）。
     */
    public static final int DEF_CTX = 2048;
    public static final int DEF_OUT = 256;

    /**
     * 按模型名认对话模板。
     *
     * @param modelName 文件名或 {@code general.name}（大小写不敏感；null/空 ⇒ ChatML）
     */
    public static String templateFor(String modelName) {
        if (modelName == null) return T_CHATML;
        final String n = modelName.toLowerCase();
        if (n.length() == 0) return T_CHATML;
        // ★ 顺序有讲究：Llama 系要排在"通配"之前，而 DeepSeek 的蒸馏小模型
        //   （deepseek-r1-distill-qwen）恰恰是 **Qwen 的模板**，所以归 ChatML。
        if (n.indexOf("llama-3") >= 0 || n.indexOf("llama3") >= 0) return T_LLAMA3;
        if (n.indexOf("gemma") >= 0) return T_GEMMA;
        if (n.indexOf("qwen") >= 0 || n.indexOf("internlm") >= 0 || n.indexOf("minicpm") >= 0
                || n.indexOf("glm") >= 0 || n.indexOf("deepseek") >= 0
                || n.indexOf("yi-") >= 0 || n.indexOf("smollm") >= 0) return T_CHATML;
        return T_CHATML;
    }

    /** 模板的人类可读名（日志/设置页用）。 */
    public static String describeTemplate(String tmpl) {
        if (T_LLAMA3.equals(tmpl)) return "Llama-3 头标记";
        if (T_GEMMA.equals(tmpl)) return "Gemma 轮次标记";
        if (T_PLAIN.equals(tmpl)) return "纯文本（无模板）";
        return "ChatML（Qwen / InternLM 等）";
    }

    // ------------------------------------------------------------------ token 预算

    /** 这个字符算不算"一个 token 一个字"的表意文字（中日韩 + 全角标点）。 */
    private static boolean isWide(char c) {
        if (c >= 0x2E80 && c <= 0xA4CF) return true;   // 部首扩展 / 假名 / 注音 / 汉字 / 彝文
        if (c >= 0xAC00 && c <= 0xD7AF) return true;   // 谚文音节
        if (c >= 0xF900 && c <= 0xFAFF) return true;   // 兼容汉字
        if (c >= 0xFE30 && c <= 0xFE4F) return true;   // 中日韩兼容形式
        if (c >= 0xFF00 && c <= 0xFFEF) return true;   // 全角字符
        return false;
    }

    /**
     * 粗估一段文字要占多少 token。
     *
     * <p>★ 只求**保守**，不求精确 —— 这是"能不能塞进上下文"的判据，
     * 估小了会在真机上撞"ctx 太小"（引擎报 decode 失败），估大了只是少带几轮历史。
     * 口径：表意文字 1 字 ≈ 1 token（实测 Qwen 系中文大致如此），其余 4 字符 ≈ 1 token。
     */
    public static int estimateTokens(String s) {
        if (s == null) return 0;
        int wide = 0, narrow = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isWide(s.charAt(i))) wide++; else narrow++;
        }
        return wide + (narrow + 3) / 4;
    }

    /** 提示词最多能占多少 token：上下文 − 输出预算 − 余量（带硬下限）。 */
    public static int maxPromptTokens(int ctxTokens, int outTokens) {
        final int ctx = ctxTokens > 0 ? ctxTokens : DEF_CTX;
        final int out = outTokens > 0 ? outTokens : DEF_OUT;
        int room = ctx - out - CTX_RESERVE;
        if (room < MIN_PROMPT_TOKENS) room = MIN_PROMPT_TOKENS;
        return room;
    }

    /**
     * 只保留**最近的**若干轮，直到预算装得下。
     *
     * <p>★ 从最新往回装（人聊天时最近的话最要紧），装不下就停。
     * ★★ 装完之后若第一条是助手说的，就把它也丢掉：留下"开局就是桌宠自言自语"
     * 的历史，小模型很容易接着自说自话（它看到的是"轮到我说"，于是又主动说一句）。
     */
    public static List<String[]> trimHistory(List<String[]> history, int budgetTokens) {
        final List<String[]> out = new ArrayList<String[]>();
        if (history == null || budgetTokens <= 0) return out;
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            final String[] h = history.get(i);
            if (h == null || h.length < 2 || h[1] == null) continue;
            final int t = estimateTokens(h[1]) + 4;     // +4 = 每轮的角色标记开销
            if (used + t > budgetTokens) break;
            used += t;
            out.add(0, new String[]{h[0], h[1]});
        }
        if (!out.isEmpty() && !"user".equals(out.get(0)[0])) out.remove(0);
        return out;
    }

    // ------------------------------------------------------------------ 提示词组装

    /** 本地模式额外压一句风格要求（小模型不压就会写小作文）。 */
    public static String styleLine() {
        return "（本机小模型模式）用中文、口语化、简短地回答：一两句话，40 字以内；"
                + "不要解释自己在做什么、不要加引号、不要用列表和标题。";
    }

    private static String joinSystem(String persona, String facts, String antiRepeat) {
        final StringBuilder sb = new StringBuilder();
        appendBlock(sb, persona);
        appendBlock(sb, facts);
        appendBlock(sb, antiRepeat);
        appendBlock(sb, styleLine());
        return sb.toString();
    }

    private static void appendBlock(StringBuilder sb, String s) {
        if (s == null) return;
        final String t = s.trim();
        if (t.length() == 0) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append(t);
    }

    /**
     * 组装消息序列：system（人格 + 记忆点 + 去重规则 + 风格）→ 历史 → 本次用户消息。
     *
     * <p>★ 与在线那条链（{@code ApiClient.chatFull}）**顺序一致但实现独立**：
     * 在线链里那些段是按"最大化前缀缓存命中"排的（价差 25 倍），
     * 本地没有缓存计费这回事，只有"装不装得下"。
     */
    public static List<String[]> messages(String persona, String facts, String antiRepeat,
                                          List<String[]> history, String userMsg) {
        final List<String[]> ms = new ArrayList<String[]>();
        final String sys = joinSystem(persona, facts, antiRepeat);
        if (sys.length() > 0) ms.add(new String[]{"system", sys});
        if (history != null) {
            for (String[] h : history) {
                if (h == null || h.length < 2 || h[1] == null) continue;
                if (h[1].trim().length() == 0) continue;
                ms.add(new String[]{h[0], h[1]});
            }
        }
        ms.add(new String[]{"user", userMsg == null ? "" : userMsg});
        return ms;
    }

    /** 把消息序列渲染成某个模板下的**一条**提示词。 */
    public static String render(String tmpl, List<String[]> ms) {
        if (ms == null || ms.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        if (T_LLAMA3.equals(tmpl)) {
            for (String[] m : ms) {
                sb.append("<|start_header_id|>").append(role(m[0], T_LLAMA3))
                  .append("<|end_header_id|>\n\n").append(m[1]).append("<|eot_id|>");
            }
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n");
        } else if (T_GEMMA.equals(tmpl)) {
            // ★ Gemma 没有独立 system 轮：系统段并进**紧跟其后**的那条 user 消息。
            String pending = null;
            for (String[] m : ms) {
                if ("system".equals(m[0])) { pending = m[1]; continue; }
                if ("assistant".equals(m[0])) {
                    sb.append("<start_of_turn>model\n").append(m[1]).append("<end_of_turn>\n");
                } else {
                    sb.append("<start_of_turn>user\n");
                    if (pending != null) { sb.append(pending).append("\n\n"); pending = null; }
                    sb.append(m[1]).append("<end_of_turn>\n");
                }
            }
            // 只剩 system（没有 user）时也不能把内容吞掉
            if (pending != null) sb.append("<start_of_turn>user\n").append(pending)
                    .append("<end_of_turn>\n");
            sb.append("<start_of_turn>model\n");
        } else if (T_PLAIN.equals(tmpl)) {
            for (String[] m : ms) {
                sb.append("### ").append(role(m[0], T_PLAIN)).append("\n").append(m[1]).append("\n\n");
            }
            sb.append("### 助手\n");
        } else {   // ChatML（默认）
            for (String[] m : ms) {
                sb.append("<|im_start|>").append(role(m[0], T_CHATML)).append("\n")
                  .append(m[1]).append("<|im_end|>\n");
            }
            sb.append("<|im_start|>assistant\n");
        }
        return sb.toString();
    }

    /** 角色名归一化：本地模板只认 system / user / assistant（历史里可能是别的写法）。 */
    public static String role(String raw, String tmpl) {
        final String r = raw == null ? "" : raw.trim().toLowerCase();
        if ("system".equals(r)) return T_GEMMA.equals(tmpl) ? "user" : "system";
        if ("assistant".equals(r) || "model".equals(r)) {
            if (T_GEMMA.equals(tmpl)) return "model";
            if (T_PLAIN.equals(tmpl)) return "助手";
            return "assistant";
        }
        if (T_PLAIN.equals(tmpl)) return "用户";
        if (T_LLAMA3.equals(tmpl) || T_CHATML.equals(tmpl)) return "user";
        return "user";
    }

    /** 一步到位：拼出可以直接喂给 {@link LocalLlm#generate} 的提示词。 */
    public static String build(String tmpl, String persona, String facts, String antiRepeat,
                               List<String[]> history, String userMsg, int promptBudgetTokens) {
        final List<String[]> kept = trimHistory(history, promptBudgetTokens);
        return render(tmpl, messages(persona, facts, antiRepeat, kept, userMsg));
    }

    // ------------------------------------------------------------------ 回复清理

    /** 模型可能吐出来的"结束标记"与"下一轮开头"——出现即截断（小模型没吐 EOG 时会带上）。 */
    private static final String[] STOP_MARKS = {
        "<|im_end|>", "<|im_start|>", "<|eot_id|>", "<|start_header_id|>",
        "<end_of_turn>", "<start_of_turn>", "<|endoftext|>", "<|end_of_text|>",
        "</s>", "<s>", "### 用户", "### 助手", "### User", "### Assistant",
    };

    /**
     * 清理模型输出。
     *
     * <p>三条都要做，因为**每一条都会让用户看到明显不对的东西**：
     * <ol>
     *   <li>截断在结束标记处 —— 引擎按 EOG 停，但有些模型（或没配 eog 的量化版）
     *       会把 {@code <|im_end|>} 当普通文本吐出来；</li>
     *   <li>去掉开头的自我称呼（{@code assistant：} / {@code 助手：}）；</li>
     *   <li>去掉整段外的引号/书名号 —— 桌宠的台词带着「」出现在气泡里很怪。</li>
     * </ol>
     */
    public static String cleanReply(String raw) {
        if (raw == null) return "";
        String s = raw;
        for (String m : STOP_MARKS) {
            final int i = s.indexOf(m);
            if (i >= 0) s = s.substring(0, i);
        }
        s = s.trim();
        for (String p : new String[]{"assistant", "Assistant", "助手", "AI", "model"}) {
            if (s.startsWith(p + "：") || s.startsWith(p + ":")) {
                s = s.substring(p.length() + 1).trim();
                break;
            }
        }
        return stripWrappers(s).trim();
    }

    private static String stripWrappers(String s) {
        String cur = s;
        while (cur.length() >= 2) {
            final char a = cur.charAt(0);
            final char b = cur.charAt(cur.length() - 1);
            final boolean pair = (a == '"' && b == '"')
                    || (a == '\u201C' && b == '\u201D')
                    || (a == '\u300C' && b == '\u300D')
                    || (a == '\u300E' && b == '\u300F')
                    || (a == '\uFF08' && b == '\uFF09');
            if (!pair) break;
            cur = cur.substring(1, cur.length() - 1).trim();
        }
        return cur;
    }

    // ------------------------------------------------------------------ 采样温度 / 流式节流

    /**
     * 把在线那套温度**夹进本地能用的范围**。
     *
     * <p>★★ 在线链默认 1.3（见 {@code ApiClient.chat} 的注释）—— 那对大模型是"活泼"，
     * 对小模型是**灾难**：0.5B 在 1.3 上会开始复读、串语言、把指令当正文写。
     * 所以本地一律夹到 0.5~1.0（默认 0.8），且 {@code <=0} 视为"没给"⇒ 用默认值
     * （★ 注意：不能把 {@code <=0} 传给引擎 —— 那里 {@code temp<=0} 是"贪心解码"的语义，
     * 会得到一条永远一样的回复）。
     */
    public static float tempFor(double apiTemp) {
        if (apiTemp <= 0) return TEMP_DEF;
        if (apiTemp < TEMP_MIN) return TEMP_MIN;
        if (apiTemp > TEMP_MAX) return TEMP_MAX;
        return (float) apiTemp;
    }

    /**
     * 流式回调要不要**现在**刷一次界面。
     *
     * <p>{@code lastMs <= 0} 表示"这一段还没刷过" ⇒ 必须刷（否则首字要等满一个间隔才出现，
     * 那正是"体感有没有反应"的关键）。{@code intervalMs <= 0} ⇒ 退回 {@link #FLUSH_MS}。
     */
    public static boolean shouldFlush(long lastMs, long nowMs, long intervalMs) {
        final long iv = intervalMs > 0 ? intervalMs : FLUSH_MS;
        if (lastMs <= 0) return true;
        return nowMs - lastMs >= iv;
    }

    // ------------------------------------------------------------------ 诊断

    /** 一行摘要（日志/设置页）：模板 + 预算 + 历史条数。 */
    public static String describe(String modelName, List<String[]> history, int promptBudgetTokens) {
        final String tmpl = templateFor(modelName);
        final int n = history == null ? 0 : history.size();
        return describeTemplate(tmpl) + " · 预算 " + promptBudgetTokens + " tok · 历史 " + n + " 条";
    }
}
