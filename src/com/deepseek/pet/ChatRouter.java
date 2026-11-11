package com.deepseek.pet;

import java.util.List;

/**
 * 对话后端**分流点**（轮次64 第三轮 · 对话接线）—— 全工程**唯一**一处判断
 * "这句话交给谁去想"。
 *
 * <h3>★★ 为什么需要这个类，而不是在 ApiClient 里加个 if</h3>
 * 施工单把这条写成了硬要求：「**绝不要把本地推理硬塞进 `ApiClient`** ——
 * 那条链是 HTTP/JSON 的（{@code ApiClient.chatUrl()}、{@code Authorization}、
 * {@code max_tokens}……）。要在**调用方分流**」。
 * 具体理由有两条，都很实在：
 * <ol>
 *   <li>{@code ApiClient.chatFull} 那 200 行干的事是"拼 messages JSON → 发 HTTP → 解 SSE/usage"。
 *       本地推理**没有** URL、Key、状态码、usage（更不该产生费用）。
 *       混进去之后，那 200 行里会长出十几个 {@code if (local)} ——
 *       而 {@code ApiClient} 是**在线链唯一的真相**，它一旦开始讲两种语言，
 *       以后任何网络问题的排查都要先问"这句到底走没走网络"；</li>
 *   <li>反过来看，接线只需要一个判断点：{@link #isLocal}。把它单独放一个类里，
 *       测试就能用"结构性判据"钉住 —— <b>{@link LocalChat} 里一个 HTTP 符号都没有</b>
 *       （连 {@code apiKey} 参数都不接）。这比"读代码确认"可靠得多。</li>
 * </ol>
 *
 * <h3>★ 谁在这里分流</h3>
 * 全工程的**对话发起方**一共 6 处，全部换成走本类（原来是直接调 {@code ApiClient}）：
 * <table border="1">
 *   <tr><th>入口</th><th>在哪</th><th>方法</th></tr>
 *   <tr><td>聊天窗口（打字发送）</td><td>{@code ChatActivity.onSend}</td><td>{@link #chat}</td></tr>
 *   <tr><td>悬浮窗「问一句」</td><td>{@code FloatingService} 的输入条</td><td>{@link #chat}</td></tr>
 *   <tr><td>启动问候</td><td>{@code FloatingService.recallStartupGreeting}</td><td>{@link #chat}</td></tr>
 *   <tr><td>继续上次话题</td><td>{@code FloatingService.recallLastTopic}</td><td>{@link #chat}</td></tr>
 *   <tr><td>戳/拖的反应</td><td>{@code FloatingService.genReactiveOnce}</td><td>{@link #chatReactive}</td></tr>
 *   <tr><td>主动搭话</td><td>{@code FloatingService.genProactiveOnce}</td><td>{@link #chatProactive}</td></tr>
 * </table>
 * ★ **不在**这里分流的两处，是有意的：人格蒸馏（{@code MainActivity} 一次性生成上万字）
 * 与主动记忆点提炼（{@code MemoryFacts}）—— 它们是"内部加工任务"不是对话，
 * 拿几百 MB 的小模型干这两件事会又慢又差，而且它们发的是长文档不是聊天。
 * 在线后端选了本地时，这两条会因为没有 Key 而安全失败（各自都有兜底，不会崩）。
 *
 * <h3>★ 流式</h3>
 * 在线链是 {@code stream:false}（整段回来再显示，见 {@code ApiClient} 的注释）；
 * 本地这条**能**逐段回调（{@link LocalLlm.TokenSink}），所以两个"打字发消息"的入口
 * 在本地后端下会**逐字出话**。短句入口（问候/继续话题/戳反应/主动搭话）不传 sink：
 * 它们要么只有 25~30 字、要么带"太像就重来一次"的重试逻辑，
 * 逐字刷气泡会和「回复连发 + 配图 + 朗读」那套机制打架（施工单专门提醒过这条）。
 */
public final class ChatRouter {

    private static final String TAG = "DSPChatRoute";

    private ChatRouter() {}

    /** 当前对话后端是不是"本地模型"。★★ 全工程唯一的分流判据。 */
    public static boolean isLocal(android.content.Context ctx) {
        if (ctx == null) return false;
        return ChatProvider.LOCAL_ID.equals(Prefs.getChatProvider(ctx));
    }

    /**
     * 现在有没有"能说话的对话后端"。
     *
     * <p>在线要有 API Key；本地模型**不要**（它没有 Key 这个概念）。
     * ★★ 全工程只留这一个判据：原来十几处各自写着 {@code !Prefs.hasApiKey(this)}，
     * 本地后端一选，用户就会在每个入口被那句过时的判断挡回去 ——
     * 现象是「功能没反应」，代码却一行没坏（最难查的那类）。
     */
    public static boolean ready(android.content.Context ctx) {
        if (isLocal(ctx)) return true;
        return Prefs.hasApiKey(ctx);
    }

    /**
     * 本地后端下**绝不联网检索**（"纯本地"就是这个意思）。
     *
     * <p>调用方拿它决定要不要走 {@code WebClient.augment}。★ 必须由它统管：
     * 联网开关（{@code Prefs.webSearch}）默认是开的，
     * 本地后端若漏了这道闸，用户看到的"纯本地"其实偷偷发了 HTTP 请求 ——
     * 那正是本轮最不能出的错。
     */
    public static boolean webAllowed(android.content.Context ctx) {
        return !isLocal(ctx);
    }

    /**
     * 需要先载入模型时，给用户一句**等待反馈**；不需要 ⇒ null。
     *
     * <p>调用方（后台线程里）拿到非 null 就先把它显示出来再去 {@link #chat}。
     * 首次载入是几百 MB 的 mmap，手机上要几秒到十几秒 —— 没有这句反馈，用户看到的就是"卡住了"。
     */
    public static String loadHintIfNeeded(android.content.Context ctx) {
        if (!isLocal(ctx)) return null;
        return LocalChat.needsLoad(ctx) ? LocalChat.loadingHint() : null;
    }

    // ------------------------------------------------------------------ 三个对话入口（与 ApiClient 同名同签名 + sink）

    /** 普通对话（聊天窗口 / 问一句 / 启动问候 / 继续话题）。 */
    public static ApiClient.ChatResult chat(android.content.Context ctx, String apiKey, String model,
                                            String persona, String factsPrompt,
                                            List<String[]> history, String userMsg,
                                            LocalLlm.TokenSink sink) {        if (isLocal(ctx)) {
            return local(ctx, persona, factsPrompt, null, history, userMsg, 1.3, sink);
        }
        return ApiClient.chat(apiKey, model, persona, factsPrompt, history, userMsg);
    }

    /**
     * 兼容旧调用（不带「主动记忆点」）：启动问候与「继续上次话题」用的就是这条
     * （与 {@code ApiClient.chat} 的 5 参重载一一对应）。
     */
    public static ApiClient.ChatResult chat(android.content.Context ctx, String apiKey, String model,
                                            String persona, List<String[]> history, String userMsg,
                                            LocalLlm.TokenSink sink) {
        return chat(ctx, apiKey, model, persona, null, history, userMsg, sink);
    }

    /** 触摸反应（戳/拖）。 */
    public static ApiClient.ChatResult chatReactive(android.content.Context ctx, String apiKey,
                                                    String model, String persona, String factsPrompt,
                                                    String antiRepeat, List<String[]> history,
                                                    String userMsg, LocalLlm.TokenSink sink) {
        if (isLocal(ctx)) {
            return local(ctx, persona, factsPrompt, antiRepeat, history, userMsg, 1.0, sink);
        }
        return ApiClient.chatReactive(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg);
    }

    /** 主动搭话。 */
    public static ApiClient.ChatResult chatProactive(android.content.Context ctx, String apiKey,
                                                     String model, String persona, String factsPrompt,
                                                     String antiRepeat, List<String[]> history,
                                                     String userMsg, LocalLlm.TokenSink sink) {
        if (isLocal(ctx)) {
            return local(ctx, persona, factsPrompt, antiRepeat, history, userMsg, 1.0, sink);
        }
        return ApiClient.chatProactive(apiKey, model, persona, factsPrompt, antiRepeat, history, userMsg);
    }

    // ------------------------------------------------------------------ 本机分支

    /**
     * 走本机引擎，再把结果**适配**成调用方已经在用的 {@link ApiClient.ChatResult}。
     *
     * <p>★ 这里（也只有这里）同时认识两种后端 —— 它是个**适配器**，不是"把 HTTP 混进本地"：
     * 上行的 {@code apiKey} 根本没传进来（本地不需要），下行的转换只是填 4 个字段。
     * ★ {@code usageParsed} 保持 false：本地推理**没有 token 费用**这回事，
     * 不能让本地对话往"今日用量/花费"里记一笔（那会让用户以为花了钱）。
     */
    private static ApiClient.ChatResult local(android.content.Context ctx, String persona,
                                              String factsPrompt, String antiRepeat,
                                              List<String[]> history, String userMsg,
                                              double temp, LocalLlm.TokenSink sink) {
        final LocalChat.Reply lr = LocalChat.answer(ctx, persona, factsPrompt, antiRepeat,
                history, userMsg, temp, LocalChat.DIALOG_MAX_TOKENS, sink);
        final ApiClient.ChatResult r = new ApiClient.ChatResult();
        r.ok = lr.ok;
        r.reply = lr.text;
        r.error = lr.error;
        r.usageParsed = false;      // ★ 本机推理不计费、不计用量
        if (!lr.ok) android.util.Log.w(TAG, "本地后端没能出话：" + lr.error);
        return r;
    }
}
