package com.deepseek.pet;

/**
 * ChatProvider 能力表的离线断言（不依赖 Android，纯逻辑）。
 *
 * <p>为什么必须有：这张表的价值全在"表说的"和"代码做的"一致。
 * 一旦表里写 FIXED 而 ApiClient 仍无条件发送，Kimi 就会重新报
 * {@code invalid temperature: only 1 is allowed} —— 而那是**用户报过的线上故障**。
 * 所以这里把关键结论钉死。
 *
 * <p>运行：build/cpidtest/run.bat
 */
public class ChatProviderTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) { pass++; }
        else { fail++; System.out.println("  FAIL: " + what); }
    }

    private static void eq(Object a, Object b, String what) {
        boolean c = (a == null) ? (b == null) : a.equals(b);
        ok(c, what + " (got " + a + ", want " + b + ")");
    }

    public static void main(String[] args) {
        System.out.println("=== ChatProvider 能力表断言 ===");

        System.out.println();
        System.out.println("[1] ★ 用户报的那个故障：Kimi K2/K3 不能发 temperature");
        {
            ChatProvider.Entry kimi = ChatProvider.find("kimi");
            eq(kimi.id, "kimi", "kimi 条目存在");
            // 这是线上 400 的直接原因 —— 必须是 FIXED（=不发）
            eq(ChatProvider.supports(kimi, "kimi-k2.6", "temp"),
                    ChatProvider.FIXED, "kimi-k2.6 temperature 必须是不发");
            eq(ChatProvider.supports(kimi, "kimi-k3", "temp"),
                    ChatProvider.FIXED, "kimi-k3 temperature 必须是不发");
            eq(ChatProvider.supports(kimi, "kimi-k2.7-code", "temp"),
                    ChatProvider.FIXED, "kimi-k2.7-code temperature 必须是不发");
            // 文档明确：penalty 两项也固定 0
            eq(ChatProvider.supports(kimi, "kimi-k2.6", "penal"),
                    ChatProvider.FIXED, "K2 penalty 必须是不发");
            eq(ChatProvider.supports(kimi, "kimi-k3", "penal"),
                    ChatProvider.FIXED, "K3 penalty 必须是不发");
            // K2 不支持 reasoning 控制
            eq(ChatProvider.supports(kimi, "kimi-k2.6", "reason"),
                    ChatProvider.FIXED, "K2 reasoning_effort 必须是不发");
            eq(ChatProvider.supports(kimi, "kimi-k2.6", "think"),
                    ChatProvider.FIXED, "K2 thinking 必须是不发");
            System.out.println("      K2/K3 的 temperature/penalty/reasoning 均判定为「不发」");
        }

        System.out.println();
        System.out.println("[2] ★ K3 的 reasoning_effort 是**支持**的，不能一刀切掉");
        {
            // 官方文档：K3 支持 top-level reasoning_effort（low/high/max）
            // 若整个套用 kimiFixed()，会把 K3 唯一可调的旋钮也关掉
            ChatProvider.Entry kimi = ChatProvider.find("kimi");
            eq(ChatProvider.supports(kimi, "kimi-k3", "reason"),
                    ChatProvider.OK, "kimi-k3 支持 reasoning_effort");
            // 但 thinking 仍不支持
            eq(ChatProvider.supports(kimi, "kimi-k3", "think"),
                    ChatProvider.FIXED, "kimi-k3 不支持 thinking");
            System.out.println("      K3：reasoning 可用、thinking 不可用（区分正确）");
        }

        System.out.println();
        System.out.println("[3] 老模型仍可调温度（避免误伤）");
        {
            ChatProvider.Entry kimi = ChatProvider.find("kimi");
            eq(ChatProvider.supports(kimi, "moonshot-v1-8k", "temp"),
                    ChatProvider.OK, "moonshot-v1 系仍可调 temperature");
        }

        System.out.println();
        System.out.println("[4] DeepSeek 官方应该全部可调（不能误伤主用途）");
        {
            ChatProvider.Entry ds = ChatProvider.find("deepseek");
            eq(ChatProvider.supports(ds, "deepseek-chat", "temp"),
                    ChatProvider.OK, "deepseek-chat temperature 可调");
            eq(ChatProvider.supports(ds, "deepseek-chat", "reason"),
                    ChatProvider.OK, "deepseek-chat reasoning 可调");
            // R1 推理模型不接受 temperature
            eq(ChatProvider.supports(ds, "deepseek-reasoner", "temp"),
                    ChatProvider.FIXED, "deepseek-reasoner 不发 temperature");
            System.out.println("      DeepSeek：chat 全可调、reasoner 不发温度");
        }

        System.out.println();
        System.out.println("[5] ★ 未知服务/未知模型必须保守「不发」");
        {
            ChatProvider.Entry custom = ChatProvider.find("custom");
            eq(ChatProvider.supports(custom, "some-unknown-model", "temp"),
                    ChatProvider.FIXED, "custom 默认不发 temperature");
            eq(ChatProvider.supports(custom, "some-unknown-model", "reason"),
                    ChatProvider.FIXED, "custom 默认不发 reasoning");
            // 完全没听过模型名的预置服务商，也应保守
            ChatProvider.Entry ds = ChatProvider.find("deepseek");
            eq(ChatProvider.supports(ds, "brand-new-model-2099", "temp"),
                    ChatProvider.OK, "预置服务商的未知模型沿用服务商默认");
            System.out.println("      custom 保守不发；预置服务商沿用其默认");
        }

        System.out.println();
        System.out.println("[6] OpenAI o 系列不接受 temperature");
        {
            ChatProvider.Entry oa = ChatProvider.find("openai");
            eq(ChatProvider.supports(oa, "o1-mini", "temp"),
                    ChatProvider.FIXED, "o1 系列不发 temperature");
            eq(ChatProvider.supports(oa, "o3-mini", "temp"),
                    ChatProvider.FIXED, "o3 系列不发 temperature");
            eq(ChatProvider.supports(oa, "gpt-4o-mini", "temp"),
                    ChatProvider.OK, "gpt-4o 系列可调 temperature");
        }

        System.out.println();
        System.out.println("[7] 地址反推服务商（老用户免重设）");
        {
            eq(ChatProvider.detectId("https://api.moonshot.cn/v1"), "kimi",
                    "moonshot.cn → kimi");
            eq(ChatProvider.detectId("https://api.moonshot.ai/v1"), "kimi",
                    "moonshot.ai → kimi");
            eq(ChatProvider.detectId("https://api.deepseek.com"), "deepseek",
                    "deepseek → deepseek");
            eq(ChatProvider.detectId("https://api.siliconflow.cn/v1"), "siliconflow",
                    "siliconflow 识别");
            eq(ChatProvider.detectId("https://open.bigmodel.cn/api/paas/v4"), "zhipu",
                    "bigmodel → zhipu");
            eq(ChatProvider.detectId("https://dashscope.aliyuncs.com/compatible-mode/v1"),
                    "dashscope", "dashscope 识别");
            eq(ChatProvider.detectId("https://api.openai.com/v1"), "openai",
                    "openai 识别");
            eq(ChatProvider.detectId("https://my-own-gateway.example.com/v1"), "custom",
                    "未知地址 → custom");
            System.out.println("      8 种地址全部识别正确");
        }

        System.out.println();
        System.out.println("[8] 预置条目的地址/模型不得为空（除了 custom 与**本地模型**）");
        {
            for (ChatProvider.Entry e : ChatProvider.all()) {
                // ★★ 轮次64（对话接线）：本地模型（ChatProvider.LOCAL_ID）是本表里
                //    **唯一一个不发 HTTP 的条目** —— 它没有地址、也没有"默认模型名"
                //    （模型是用户自己导进来的那个 .gguf）。
                //    这里不是"放过它"，而是**反过来把它钉住**：
                //    它必须真的是空的 —— 万一有人给它填了个地址，
                //    那就意味着"本地后端"又要走网了，而这个套件要当场红。
                if (ChatProvider.LOCAL_ID.equals(e.id)) {
                    ok(e.baseUrl == null || e.baseUrl.length() == 0,
                            "本地模型条目不该有地址（它不发 HTTP）");
                    ok(e.model == null || e.model.length() == 0,
                            "本地模型条目不该有默认模型名（模型是用户导入的 .gguf）");
                    // 参数能力一律 FIXED：万一日后有人按能力表判断，也不会把
                    // HTTP 参数带到本机推理那条链上。
                    ok(ChatProvider.supports(e, "", "temp") == ChatProvider.FIXED
                                    && ChatProvider.supports(e, "", "penal") == ChatProvider.FIXED
                                    && ChatProvider.supports(e, "", "think") == ChatProvider.FIXED,
                            "本地模型条目的参数能力应全部为 FIXED");
                    // 也不该能从任何地址"反推"出本地模型来（它是显式选的，不是认域名认出来的）
                    eq(ChatProvider.detectId("https://example.com/v1"), "custom",
                            "本地模型不是靠地址反推出来的");
                    continue;
                }
                if ("custom".equals(e.id)) continue;
                ok(e.baseUrl != null && e.baseUrl.length() > 0,
                        e.id + " 有默认地址");
                ok(e.model != null && e.model.length() > 0,
                        e.id + " 有默认模型");
                // 地址必须是 https
                ok(e.baseUrl.startsWith("https://"), e.id + " 地址是 https");
            }
            System.out.println("      " + ChatProvider.all().size() + " 个条目的地址与模型均有效");
        }

        System.out.println();
        System.out.println("[9] id 唯一（否则 prefs 存的值会指错服务商）");
        {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (ChatProvider.Entry e : ChatProvider.all()) {
                ok(seen.add(e.id), "id 唯一: " + e.id);
            }
        }

        System.out.println();
        System.out.println("全部通过：" + pass + " 项");
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
    }
}
