package com.deepseek.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音合成服务商预设表。
 *
 * <p>重做前的老版本把协议写死了：地址末尾无条件拼 {@code /audio/speech}、
 * {@code response_format} 硬编码 mp3、鉴权固定 Bearer。结果只能接 OpenAI 那一套，
 * 而且没填地址时会拿「对话地址」去派生 —— 对话默认是 api.deepseek.com，
 * 那家根本没有语音接口，于是必然 404。</p>
 *
 * <p>现在按「协议类型 kind」分发，地址、鉴权、请求体、响应解析各自独立：</p>
 * <ul>
 *   <li>{@link #K_OPENAI} —— OpenAI 兼容 {@code POST /audio/speech}，响应就是音频字节。
 *       硅基流动、智谱、OpenAI、Groq 以及自建反代都归这一类。</li>
 *   <li>{@link #K_DASHSCOPE} —— 阿里云百炼 qwen-tts，请求体是嵌套结构，
 *       响应 JSON 里给音频 url 或 base64。</li>
 *   <li>{@link #K_VOLC} —— 火山引擎大模型语音合成，要 appid，响应 JSON 给 base64。</li>
 *   <li>{@link #K_MINIMAX} —— MiniMax T2A v2，要 GroupId，响应 JSON 给 hex。</li>
 *   <li>{@link #K_RAW} —— 完全自定义，按你填的 URL 原样直发，响应自动嗅探。</li>
 * </ul>
 *
 * <p>纯静态数据 + 纯字符串逻辑，无 Android 依赖，可直接在 Linux 环境单测。</p>
 */
public final class TtsProvider {

    private TtsProvider() {}

    // ==================== 协议类型 ====================
    public static final String K_OPENAI    = "openai";
    public static final String K_DASHSCOPE = "dashscope";
    public static final String K_VOLC      = "volc";
    public static final String K_MINIMAX   = "minimax";
    public static final String K_RAW       = "raw";

    /** 一条服务商预设 */
    public static final class Entry {
        public final String id;      // 稳定标识，存进 prefs
        public final String name;    // 界面显示名
        public final String kind;    // 协议类型
        public final String url;     // 默认完整地址
        public final String model;   // 默认模型
        public final String voice;   // 默认音色
        public final String format;  // 默认返回格式
        public final String hint;    // 填写提示
        public final boolean needAppId;
        public final boolean needGroupId;
        public final boolean verified;   // 是否已实测通过

        Entry(String id, String name, String kind, String url, String model,
              String voice, String format, String flags, String hint) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.url = url;
            this.model = model;
            this.voice = voice;
            this.format = format;
            this.hint = hint == null ? "" : hint;
            this.needAppId   = flags.indexOf('a') >= 0;
            this.needGroupId = flags.indexOf('g') >= 0;
            this.verified    = flags.indexOf('v') >= 0;
        }
    }

    private static final List<Entry> ALL = new ArrayList<Entry>();

    static {
        // 标记位：a=需要 AppId  g=需要 GroupId  v=已实测
        ALL.add(new Entry("siliconflow", "硅基流动 SiliconFlow", K_OPENAI,
                "https://api.siliconflow.cn/v1/audio/speech",
                "FunAudioLLM/CosyVoice2-0.5B", "FunAudioLLM/CosyVoice2-0.5B:claire",
                "mp3", "v",
                "音色写法是「模型名:音色名」，如 :claire / :bella / :alex。还能配合下方「音色克隆」用参考音频克隆"));

        ALL.add(new Entry("openai", "OpenAI 官方", K_OPENAI,
                "https://api.openai.com/v1/audio/speech",
                "tts-1", "alloy",
                "mp3", "",
                "音色可选 alloy / echo / fable / onyx / nova / shimmer，模型 tts-1 或 gpt-4o-mini-tts"));

        ALL.add(new Entry("zhipu", "智谱 AI（GLM）", K_OPENAI,
                "https://open.bigmodel.cn/api/paas/v4/audio/speech",
                "glm-tts", "tongtong",
                "mp3", "",
                "音色如 tongtong / chuichui / xiaochen / jam / kazi / douji / luodo"));

        ALL.add(new Entry("dashscope", "阿里云百炼（qwen-tts）", K_DASHSCOPE,
                "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
                "qwen-tts", "Cherry",
                "wav", "",
                "音色如 Cherry / Serena / Ethan / Chelsie。请求体是嵌套结构，不用填 /audio/speech"));

        ALL.add(new Entry("volc", "火山引擎（豆包语音）", K_VOLC,
                "https://openspeech.bytedance.com/api/v1/tts",
                "volcano_tts", "BV700_streaming",
                "mp3", "a",
                "需要单独填 AppId（应用 ID）。音色如 BV700_streaming、BV001_streaming"));

        ALL.add(new Entry("minimax", "MiniMax 语音", K_MINIMAX,
                "https://api.minimax.cn/v1/t2a_v2",
                "speech-2.8-hd", "female-shaonv",
                "mp3", "",
                "只要填 API Key（Bearer 鉴权），不需要 GroupId。音色如 female-shaonv / male-qn-qingse"));

        ALL.add(new Entry("custom", "自定义（OpenAI 兼容地址）", K_OPENAI,
                "", "", "",
                "mp3", "",
                "自己填完整地址；若地址不含 /audio/speech 会自动补上。适用于任何 OpenAI 兼容的自建或反代服务"));

        ALL.add(new Entry("raw", "自定义（原样直发）", K_RAW,
                "", "", "",
                "mp3", "",
                "按你填的地址原样 POST，不补任何路径。响应自动识别（音频字节 / base64 / hex / JSON 里的 url）"));
    }

    public static List<Entry> all() {
        return new ArrayList<Entry>(ALL);
    }

    public static Entry find(String id) {
        if (id != null) {
            for (Entry e : ALL) {
                if (e.id.equals(id)) return e;
            }
        }
        return null;
    }

    /** 默认服务商：硅基流动（目前唯一实测通过、且零配置可用的） */
    public static Entry def() {
        return find("siliconflow");
    }

    /** 取不到就回落到默认，避免空指针 */
    public static Entry findOrDef(String id) {
        Entry e = find(id);
        return e == null ? def() : e;
    }

    /**
     * 根据地址猜服务商 —— 用于从老版本配置迁移，以及用户手填地址后自动归位。
     * 认不出返回 null。
     */
    public static String detectId(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();
        if (u.indexOf("siliconflow") >= 0)        return "siliconflow";
        if (u.indexOf("api.openai.com") >= 0)     return "openai";
        if (u.indexOf("bigmodel.cn") >= 0)        return "zhipu";
        if (u.indexOf("dashscope") >= 0)          return "dashscope";
        if (u.indexOf("openspeech.bytedance") >= 0) return "volc";
        if (u.indexOf("minimax") >= 0)            return "minimax";
        return null;
    }

    /** 该协议是否需要走「音色克隆」分支（只有 OpenAI 兼容的 references 才支持） */
    public static boolean supportsClone(String kind) {
        return K_OPENAI.equals(kind);
    }

    public static String kindName(String kind) {
        if (K_OPENAI.equals(kind))    return "OpenAI 兼容";
        if (K_DASHSCOPE.equals(kind)) return "阿里云百炼";
        if (K_VOLC.equals(kind))      return "火山引擎";
        if (K_MINIMAX.equals(kind))   return "MiniMax";
        if (K_RAW.equals(kind))       return "原样直发";
        return kind == null ? "未知" : kind;
    }
}
