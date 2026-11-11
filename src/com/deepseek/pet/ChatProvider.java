package com.deepseek.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话服务商能力表（OpenAI 兼容协议）。
 *
 * <h3>为什么需要这张表</h3>
 * 各家虽然都号称"OpenAI 兼容"，但**参数约束差别很大**，而且**不支持的参数
 * 是直接报 400，不是忽略**。用户接 Kimi 时就撞上过：
 * <pre>
 *   HTTP 400: invalid temperature: only 1 is allowed for this model
 * </pre>
 * 光改 temperature 还不够 —— 实测官方文档（platform.kimi.com/docs/api/models-overview）
 * 明确写着 Kimi K2/K3 系列：
 * <pre>
 *   temperature / top_p / n / presence_penalty / frequency_penalty
 *   全部 "Cannot be modified"（固定值），
 *   "passing any other value returns an error, so do not pass it explicitly."
 * </pre>
 * 注意最后半句：**正确做法是"不发这个字段"**，而不是"发一个正确的值"。
 *
 * <h3>本表只描述"能力"，不描述"业务"</h3>
 * 每个参数用一个三态表示：
 * <ul>
 *   <li>{@link #FIXED} —— 该服务商/模型固定此参数，**不能发**（发了就 400）</li>
 *   <li>{@link #OK}    —— 支持，按 App 的业务值发</li>
 *   <li>{@link #NO}    —— 不支持该参数（与 FIXED 同效：不发），但语义不同：
 *       FIXED 是"有但固定"，NO 是"根本没有"</li>
 * </ul>
 *
 * <h3>★ 未知模型怎么办：保守不发</h3>
 * 文档里写得很清楚：「不确定时不要显式传」。所以对表里没有的模型，
 * {@link #supports(Settings, String)} 一律回 {@link #FIXED}（=不发）。
 * 代价是"某些模型用不上我们调的温度"，收益是**不会因为多传一个字段就整个请求失败** ——
 * 后者对用户来说是无法自行排查的硬故障，前者只是效果略平。
 */
public final class ChatProvider {

    private ChatProvider() {}

    /**
     * 「本地模型」这个后端的 id（轮次64 第三轮）。
     *
     * <p>★★ 它是本表里**唯一一个不发 HTTP 的条目**：选了它，
     * 对话走 {@link LocalChat} → {@link LocalLlm}（手机上本机推理，离线、不花 Key）。
     * 分流点在 {@link ChatRouter}，**不在** {@code ApiClient}（那条链只讲 HTTP）。
     */
    public static final String LOCAL_ID = "local";

    /** 参数能力：不能发（有但固定） */
    public static final int FIXED = 0;
    /** 参数能力：支持，按业务值发 */
    public static final int OK = 1;
    /** 参数能力：不支持（等效于不发，但语义是"压根没有"） */
    public static final int NO = 2;

    /** 某个具体服务的参数能力集合 */
    public static class Settings {
        public int temperature = OK;
        public int reasoningEffort = OK;
        public int thinking = OK;
        public int penalty = OK;
        public int topP = OK;

        Settings temp(int v)      { temperature = v;   return this; }
        Settings reason(int v)    { reasoningEffort = v; return this; }
        Settings think(int v)     { thinking = v;      return this; }
        Settings penal(int v)     { penalty = v;       return this; }
        Settings topp(int v)      { topP = v;          return this; }
    }

    /** 一个服务商条目 */
    public static class Entry {
        public final String id;       // 稳定标识，存进 prefs
        public final String name;     // 界面显示名
        public final String baseUrl;  // 默认基地址（不含 /chat/completions）
        public final String model;    // 默认模型
        public final String hint;     // 填写提示
        public final Settings def;    // 默认参数能力
        /** 模型名前缀 → 覆盖默认能力。用于同一服务商下不同系列约束不同 */
        public final List<String[]> modelOverrides = new ArrayList<String[]>();

        // ———— 余额查询（各家接口完全不同，字段与认证方式都得逐个记）————

        /** 余额接口路径（相对 baseUrl）；null/空 = 该服务商没有公开余额接口 */
        public String balancePath;
        /**
         * 余额 JSON 的取值路径，用 {@code .} 分隔，如 {@code data.available_balance}。
         * 数组下标用 {@code [i]}，如 {@code balance_infos[0].total_balance}。
         */
        public String balanceField;
        /** 返回的币种（各家固定：DeepSeek 可多币种，Kimi 是 USD） */
        public String balanceCurrency = "CNY";
        /**
         * 认证头是否用裸 Key（不带 "Bearer "）。
         *
         * <p>★ 这不是可选项：智谱国内站 {@code open.bigmodel.cn} 的监控接口
         * 要求**裸 Key**，带 Bearer 会 401。这是实测别家实现得出的结论
         * （见 glm-for-copilot 的 authHeader：按 host 名判断）。
         */
        public boolean balanceRawAuth = false;

        Entry(String id, String name, String baseUrl, String model,
              String hint, Settings def) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
            this.model = model;
            this.hint = hint == null ? "" : hint;
            this.def = def;
        }

        /** 设置余额接口 */
        Entry balance(String path, String field, String currency) {
            this.balancePath = path;
            this.balanceField = field;
            this.balanceCurrency = currency;
            return this;
        }

        Entry rawAuth() { this.balanceRawAuth = true; return this; }

        /** 是否有余额查询能力 */
        public boolean hasBalance() {
            return balancePath != null && balancePath.length() > 0
                    && balanceField != null && balanceField.length() > 0;
        }

        /** 加一条"模型名前缀 → 能力"的覆盖规则 */
        Entry over(String prefix, Settings s) {
            modelOverrides.add(new String[]{prefix, encode(s)});
            return this;
        }

        @Override public String toString() { return name; }
    }

    // 把 Settings 压成一个字符串，避免再定义一个类（模型覆盖表只需读，不需写）
    private static String encode(Settings s) {
        return s.temperature + "," + s.reasoningEffort + "," + s.thinking
                + "," + s.penalty + "," + s.topP;
    }

    private static Settings decode(String v) {
        String[] p = v.split(",");
        Settings s = new Settings();
        if (p.length == 5) {
            s.temperature = Integer.parseInt(p[0]);
            s.reasoningEffort = Integer.parseInt(p[1]);
            s.thinking = Integer.parseInt(p[2]);
            s.penalty = Integer.parseInt(p[3]);
            s.topP = Integer.parseInt(p[4]);
        }
        return s;
    }

    /** 通用能力（大多数 OpenAI 兼容服务）：参数都支持 */
    private static Settings general() {
        return new Settings();
    }

    /**
     * Kimi（月之暗面）K2/K3 系列：这几项**固定**，发了就 400。
     *
     * <p>来源：platform.kimi.com/docs/api/models-overview
     * 「temperature / top_p / n / presence_penalty / frequency_penalty
     *   全部 Cannot be modified，passing any other value returns an error,
     *   so do not pass it explicitly.」
     */
    private static Settings kimiFixed() {
        return new Settings()
                .temp(FIXED).topp(FIXED).penal(FIXED)
                .reason(FIXED).think(FIXED);
    }

    private static final List<Entry> ALL = new ArrayList<Entry>();

    static {
        // ★★★ 轮次75：本地模型入口下线期间**不放这一项**（用户原话：
        //    「先去掉本地模型部署这个接口 保留项目内容后续再加上」）。
        //    ★ 代码、模型文件、prefs 全部保留；{@link LocalModelFeature#ENABLED} 改成 true 就恢复。
        if (LocalModelFeature.ENABLED) {
            // ★★ 第一项就是「本地模型」（轮次64 第三轮）：放在最前面，因为它回答的是
            //    同一个问题——"这句话由谁来想"——而且是**不花钱、不联网**的那个答案，
            //    用户最容易忽略它，所以摆在最显眼的位置。
            //    它不发 HTTP，所以 baseUrl / model 都留空（applyChatProvider 见空就不改地址与模型）。
            ALL.add(new Entry(LOCAL_ID, "本地模型（手机本机跑，不联网）",
                    "", "",
                    "把 .gguf 模型放在**手机本机**跑：不联网、不花 Key、内容不出手机。"
                    + "先在设置页最下面「\uD83E\uDD16 本地模型」里导入一个 0.5B~3B 的量化模型。"
                    + "★ 本机小模型「慢一些、也更笨」，复杂问题建议切回在线服务商。",
                    // 本机推理没有"参数能不能发"这回事 —— 一律标 FIXED（不发），
                    // 这样万一有别的代码路径按能力表判断，也不会把 HTTP 参数带到本地。
                    new Settings().temp(FIXED).reason(FIXED).think(FIXED).penal(FIXED).topp(FIXED)));
        }

        // 余额接口的字段路径都来自各家官方文档实测（见下方注释），不是猜的。
        ALL.add(new Entry("deepseek", "DeepSeek 官方",
                "https://api.deepseek.com", "deepseek-chat",
                "官方支持 temperature 与 reasoning_effort / thinking，"
                + "「深度思考」模型会自动更聪明但更慢",
                general())
                .balance("/user/balance", "balance_infos[0].total_balance", "CNY")
                // 推理模型 R1 不支持 temperature（会忽略或报错），保守不发
                .over("deepseek-reasoner", new Settings().temp(FIXED)));

        // ★ Kimi：这个条目就是用户报的那个 400 的修复
        ALL.add(new Entry("kimi", "Kimi（月之暗面）",
                "https://api.moonshot.cn/v1", "kimi-k2.6",
                "K2/K3 系列的温度等参数由服务端固定，本 App 会自动不再发送这些"
                + "参数（这正是之前报「invalid temperature: only 1 is allowed」的原因）",
                general())
                // 余额：platform.kimi.com/docs/api/balance → data.available_balance（USD）
                // ⚠️ 官方警告：platform.kimi.ai 与 platform.kimi.com 的 Key **互不通用**，
                //    用错平台会 401。所以余额地址必须跟随 baseUrl（这里用相对路径实现）。
                .balance("/users/me/balance", "data.available_balance", "USD")
                // K2 系列：温度/惩罚/推理强度全部锁死
                .over("kimi-k2", kimiFixed())
                // K3：温度与惩罚仍锁死，但**推理强度是支持的**（low/high/max）——
                // 所以不能整个套用 kimiFixed()，否则会白丢掉 K3 唯一可调的旋钮。
                .over("kimi-k3", new Settings()
                        .temp(FIXED).topp(FIXED).penal(FIXED)
                        .reason(OK).think(FIXED))
                .over("moonshot-v1", general()));   // 老模型仍可调温度

        ALL.add(new Entry("siliconflow", "硅基流动 SiliconFlow",
                "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3",
                "聚合多家模型，模型名形如 deepseek-ai/DeepSeek-V3、"
                + "Qwen/Qwen2.5-72B-Instruct",
                general())
                // 余额：docs.siliconflow.com/.../get-user-info → data.totalBalance（字符串）
                .balance("/user/info", "data.totalBalance", "CNY"));

        ALL.add(new Entry("zhipu", "智谱 AI（GLM）",
                "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
                "模型如 glm-4-plus / glm-4-flash / glm-4-air",
                general())
                // 余额：走的是 biz 网关（与 /api/paas/v4 不同层），
                // 所以这里用**绝对路径**（以 / 开头时不再拼 baseUrl）。
                // 字段取 availableBalance，退回 balance。
                // ★ 国内站要求**裸 Key**（不带 Bearer），带前缀会 401。
                .balance("/api/biz/account/query-customer-account-report",
                         "data.availableBalance", "CNY")
                .rawAuth());

        // 百炼与 OpenAI 都**没有公开的余额查询接口**。
        // 这一步是刻意留空的：与其去调一个不存在的接口、然后给用户看
        // "查询失败"（用户会以为是自己配置错了），不如明确说明"该服务商不提供"。
        ALL.add(new Entry("dashscope", "阿里云百炼（通义千问）",
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                "兼容模式地址，模型如 qwen-plus / qwen-max / qwen-turbo",
                general()));

        ALL.add(new Entry("openai", "OpenAI 官方",
                "https://api.openai.com/v1", "gpt-4o-mini",
                "模型如 gpt-4o / gpt-4o-mini / gpt-4.1",
                general())
                // o 系列推理模型不接受 temperature
                .over("o1", new Settings().temp(FIXED))
                .over("o3", new Settings().temp(FIXED))
                .over("o4", new Settings().temp(FIXED)));

        ALL.add(new Entry("custom", "自定义（OpenAI 兼容地址）",
                "", "",
                "任何 OpenAI 兼容的服务都能用：填基地址（如 https://x.com/v1）。"
                + "未知服务默认不发温度等参数，以避免报错；"
                + "若你的服务支持这些参数，可在下方强制开启",
                // ★ 未知服务：保守不发 —— 见类注释「未知模型怎么办」
                new Settings().temp(FIXED).reason(FIXED).think(FIXED).penal(FIXED)));
    }

    /** 所有预置服务商 */
    public static List<Entry> all() { return ALL; }

    public static Entry find(String id) {
        for (Entry e : ALL) if (e.id.equals(id)) return e;
        return find("deepseek");
    }

    /**
     * 按地址反推服务商 id（用户手改了地址时也能跟上）。
     * 与 {@code TtsProvider.detectId} 同样的思路：认域名关键词。
     */
    public static String detectId(String url) {
        if (url == null) return "custom";
        String u = url.toLowerCase();
        if (u.length() == 0) return "custom";
        if (u.indexOf("api.deepseek.com") >= 0)  return "deepseek";
        if (u.indexOf("moonshot.cn") >= 0)        return "kimi";
        if (u.indexOf("moonshot.ai") >= 0)        return "kimi";
        if (u.indexOf("kimi.com") >= 0)           return "kimi";
        if (u.indexOf("siliconflow") >= 0)        return "siliconflow";
        if (u.indexOf("bigmodel.cn") >= 0)        return "zhipu";
        if (u.indexOf("dashscope") >= 0)          return "dashscope";
        if (u.indexOf("api.openai.com") >= 0)     return "openai";
        return "custom";
    }

    /**
     * 查询某服务商 + 某模型下，某参数能不能发。
     *
     * @param entry 服务商（null 时按 custom 处理）
     * @param model 模型名（可为空）
     * @param which 取 {@code temp/reason/think/penal/topP} 之一
     * @return {@link #OK} / {@link #FIXED} / {@link #NO}
     */
    public static int supports(Entry entry, String model, String which) {
        Settings s = settingsFor(entry, model);
        if ("temp".equals(which))    return s.temperature;
        if ("reason".equals(which))  return s.reasoningEffort;
        if ("think".equals(which))   return s.thinking;
        if ("penal".equals(which))   return s.penalty;
        if ("topP".equals(which))    return s.topP;
        return FIXED;
    }

    /** 取"服务商默认 + 模型前缀覆盖"之后的最终能力 */
    public static Settings settingsFor(Entry entry, String model) {
        if (entry == null) entry = find("custom");
        Settings base = new Settings()
                .temp(entry.def.temperature)
                .reason(entry.def.reasoningEffort)
                .think(entry.def.thinking)
                .penal(entry.def.penalty)
                .topp(entry.def.topP);
        if (model == null) model = "";
        String m = model.trim().toLowerCase();
        if (m.length() == 0) return base;
        // 后加的覆盖优先（越具体越靠后写）
        Settings hit = null;
        for (String[] ov : entry.modelOverrides) {
            if (m.startsWith(ov[0].toLowerCase()) || m.indexOf(ov[0].toLowerCase()) >= 0) {
                hit = decode(ov[1]);
            }
        }
        return hit != null ? hit : base;
    }

    /** 界面上给用户看的一行能力摘要 */
    public static String describe(Entry entry, String model) {
        // ★★ 本地模型：没有"服务端固定参数"这回事 —— 那句话给它会变成
        //    "有 5 项参数由服务端固定"，牛头不对马嘴。这里换成真正该说的两句：
        //    ① 这条链**完全不出手机**；② 本机小模型的**代价**（慢、笨）。
        //    第 ② 句是刻意写的：不写，用户会拿它跟云端比，然后以为功能坏了。
        if (entry != null && LOCAL_ID.equals(entry.id)) {
            return "本机推理：不联网、不需要 API Key、不产生费用。"
                    + "★ 本机小模型慢一些、也更笨（几百 MB vs 云端几千亿参数），"
                    + "复杂问题建议切回在线服务商。";
        }
        Settings s = settingsFor(entry, model);
        int n = 0;
        if (s.temperature == FIXED) n++;
        if (s.penalty == FIXED) n++;
        if (s.reasoningEffort == FIXED) n++;
        if (s.thinking == FIXED) n++;
        if (n == 0) return "全部参数可调";
        return "有 " + n + " 项参数由服务端固定，已自动不发送（避免报错）";
    }
}
