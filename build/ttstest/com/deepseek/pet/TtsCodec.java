package com.deepseek.pet;

/**
 * 语音合成的「协议编解码」纯逻辑层：拼请求体、解析响应、解码音频。
 *
 * <p>刻意<b>不依赖 Android</b>（不用 org.json、不用 android.util.Base64），
 * 这样可以在 Linux 环境直接 javac + java 单测 —— 多协议适配最容易错的就是
 * 请求体字段和响应取值路径，这部分必须能离线验。</p>
 *
 * <p>响应解析思路：<b>先嗅探、再按需取值</b>。不假设服务商一定怎么回，
 * 而是看实际字节 —— 是音频就直接用，是 JSON 就按可能的路径把音频捞出来
 * （url / base64 / hex 三种都有服务商用），是 base64 或 hex 文本就解出来。</p>
 */
public final class TtsCodec {

    private TtsCodec() {}

    // ==================== 请求体 ====================

    /** 拼请求体需要的一切，从 Prefs 组装后传进来 */
    public static final class Config {
        public String kind   = TtsProvider.K_OPENAI;
        public String model  = "";
        public String voice  = "";
        public String format = "mp3";
        public float  speed  = 1.0f;
        public String text   = "";
        public String appId  = "";      // 火山
        public String token  = "";      // 火山独立 access token（留空则用 Key）
        // 音色克隆（仅 OpenAI 兼容）
        public String cloneRefAudio = "";
        public String cloneRefText  = "";
    }

    /**
     * 按协议类型拼请求体 JSON。
     *
     * <p>★ 字段名严格照各家文档，不能想当然统一 —— 这正是老版本「只支持一种协议」
     * 的病根：它把 OpenAI 的 {@code input/voice} 结构当成放之四海皆准。</p>
     */
    public static String buildBody(Config c) {
        if (c == null) return "{}";
        String kind = c.kind == null ? TtsProvider.K_OPENAI : c.kind;

        if (TtsProvider.K_DASHSCOPE.equals(kind)) {
            // 阿里云百炼 qwen-tts：input 下嵌套 text / voice
            return "{\"model\":\"" + esc(nz(c.model, "qwen-tts")) + "\""
                 + ",\"input\":{\"text\":\"" + esc(c.text) + "\""
                 + ",\"voice\":\"" + esc(nz(c.voice, "Cherry")) + "\"}}";
        }

        if (TtsProvider.K_VOLC.equals(kind)) {
            // 火山引擎 openspeech v1：app/user/audio/request 四段，格式字段叫 encoding
            return "{\"app\":{\"appid\":\"" + esc(c.appId) + "\""
                 + ",\"token\":\"" + esc(nz(c.token, "")) + "\""
                 + ",\"cluster\":\"volcano_tts\"}"
                 + ",\"user\":{\"uid\":\"deepseek_pet\"}"
                 + ",\"audio\":{\"voice_type\":\"" + esc(nz(c.voice, "BV700_streaming")) + "\""
                 + ",\"encoding\":\"" + esc(encFmt(c.format)) + "\""
                 + ",\"speed_ratio\":" + num(c.speed) + "}"
                 + ",\"request\":{\"reqid\":\"" + reqId() + "\""
                 + ",\"text\":\"" + esc(c.text) + "\""
                 + ",\"operation\":\"query\"}}";
        }

        if (TtsProvider.K_MINIMAX.equals(kind)) {
            // MiniMax T2A v2：voice_setting / audio_setting 分组，格式字段叫 format
            return "{\"model\":\"" + esc(nz(c.model, "speech-2.8-hd")) + "\""
                 + ",\"text\":\"" + esc(c.text) + "\""
                 + ",\"stream\":false"
                 + ",\"voice_setting\":{\"voice_id\":\"" + esc(nz(c.voice, "female-shaonv")) + "\""
                 + ",\"speed\":" + num(c.speed) + ",\"vol\":1.0,\"pitch\":0}"
                 + ",\"audio_setting\":{\"sample_rate\":32000,\"bitrate\":128000"
                 + ",\"format\":\"" + esc(encFmt(c.format)) + "\"}}";
        }

        // OpenAI 兼容（含自定义、原样直发）：input / voice / response_format
        String voicePart;
        if (c.cloneRefAudio != null && c.cloneRefAudio.length() > 0
                && c.cloneRefText != null && c.cloneRefText.length() > 0) {
            // 克隆：voice 传空 + references，这是硅基流动 CosyVoice2 的动态音色用法
            voicePart = ",\"voice\":\"\""
                      + ",\"references\":[{\"audio\":\"" + esc(c.cloneRefAudio) + "\""
                      + ",\"text\":\"" + esc(c.cloneRefText) + "\"}]";
        } else {
            voicePart = ",\"voice\":\"" + esc(c.voice) + "\"";
        }
        return "{\"model\":\"" + esc(c.model) + "\""
             + ",\"input\":\"" + esc(c.text) + "\""
             + voicePart
             + ",\"response_format\":\"" + esc(encFmt(c.format)) + "\""
             + ",\"speed\":" + num(c.speed) + "}";
    }

    /**
     * 鉴权头。★ 火山用的是 {@code Bearer;<token>}（带分号），
     * 跟通用的 {@code Bearer <token>} 不一样，混用会直接 401。
     */
    public static String authHeader(String kind, String key, String appId) {
        if (TtsProvider.K_VOLC.equals(kind)) return "Bearer;" + nz(key, "");
        return "Bearer " + nz(key, "");
    }

    // ==================== MiniMax 音色克隆 ====================

    /**
     * MiniMax 官方域名。
     *
     * <p>开放平台文档（platform.minimax.cn）给的是 {@code https://api.minimax.cn}，
     * 同步语音合成端点是 {@code /v1/t2a_v2}。</p>
     *
     * <p><b>★ 注意：这里原先的注释声称「.cn 的 Key 只在 api.minimax.cn 上有效」，
     * 这是没有证据的推断，已删除。</b>实测用无效 Key 探测 5 个域名
     * （.cn / minimaxi.com / minimax.chat / minimaxi.chat / minimax.io）
     * 返回完全一致，DNS 也显示 .cn 与 .chat 是同一批 IP ——
     * 说明「换域名」解决不了问题。选用 .cn 只是因为它与国内平台的文档一致。
     * 真正的坑是业务错误码被吞掉（见 {@link #parse}）。</p>
     */
    public static final String MINIMAX_API = "https://api.minimax.cn";

    /**
     * 上传复刻音频：multipart/form-data，purpose=voice_clone
     *
     * <p>★ <b>不要再拼 {@code ?GroupId=}</b>。官方现行文档里
     * {@code /v1/files/upload} 与 {@code /v1/voice_clone} 都只认
     * {@code Authorization: Bearer}，示例 URL 上没有任何查询参数。
     * {@code GroupId}（驼峰）是旧版鉴权参数，网关上还留着旧分支：
     * URL 里出现大小写完全匹配的 {@code GroupId} 会切到 group 鉴权，
     * 于是拿新 Key 去比对一个不存在的分组 → {@code 1004: token not match group}。
     * 小写 {@code group_id} 则被忽略。参数保留只是为了不改调用点，已不再使用。</p>
     */
    public static String minimaxUploadUrl(String groupId) {
        return MINIMAX_API + "/v1/files/upload";
    }

    /** 音频复刻：拿 file_id 换一个自定义 voice_id（同样不拼 GroupId，理由见上） */
    public static String minimaxCloneUrl(String groupId) {
        return MINIMAX_API + "/v1/voice_clone";
    }

    /**
     * 复刻请求体。
     * <p>★ 传了 text 就必须同时传 model，这是官方硬要求；不传 text 则只复刻、不出试听。
     * 我们总是带试听，好让用户当场听到像不像。</p>
     */
    public static String buildCloneBody(String fileId, String voiceId, String model, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"file_id\":").append(nz(fileId, "0"));
        sb.append(",\"voice_id\":\"").append(esc(voiceId)).append("\"");
        if (text != null && text.length() > 0) {
            sb.append(",\"text\":\"").append(esc(text)).append("\"");
            sb.append(",\"model\":\"").append(esc(nz(model, "speech-2.8-hd"))).append("\"");
        }
        return sb.append("}").toString();
    }

    /** 从 JSON 里取数字字段（file_id 是 int64，字符串和数字两种写法都吃） */
    public static String jsonNum(String json, String key) {
        if (json == null) return null;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int i = json.indexOf(':', k + key.length() + 2);
        if (i < 0) return null;
        i++;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) i++;
        int j = i;
        while (j < json.length() && json.charAt(j) >= '0' && json.charAt(j) <= '9') j++;
        return j > i ? json.substring(i, j) : null;
    }

    /**
     * 取业务状态码（MiniMax 的 {@code base_resp.status_code} 等）。
     *
     * @return 解析到的码；字段不存在或不是数字时返回 {@link #NO_BIZ_CODE}
     *         （用哨兵而不是 0，否则「没有该字段」会被误判成「成功」——
     *         那正是把所有错误都报成「没有音频字段」的老毛病）
     */
    public static final int NO_BIZ_CODE = Integer.MIN_VALUE;

    public static int jsonCode(String json) {
        if (json == null) return NO_BIZ_CODE;
        String v = jsonNum(json, "status_code");
        if (v == null) return NO_BIZ_CODE;
        try { return Integer.parseInt(v); } catch (Throwable t) { return NO_BIZ_CODE; }
    }

    /** 从 JSON 里取字符串字段（会做最小反转义） */
    public static String jsonStr(String json, String key) {
        if (json == null) return null;
        int k = json.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int i = json.indexOf('"', json.indexOf(':', k + key.length() + 2));
        if (i < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int p = i + 1; p < json.length(); p++) {
            char ch = json.charAt(p);
            if (ch == '\\' && p + 1 < json.length()) {
                char nx = json.charAt(p + 1);
                if (nx == 'n') sb.append('\n');
                else if (nx == 'u' && p + 5 < json.length()) {
                    try { sb.append((char) Integer.parseInt(json.substring(p + 2, p + 6), 16)); }
                    catch (Exception ignore) {}
                    p += 4;
                } else sb.append(nx);
                p++;
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    // ==================== 响应解析 ====================

    /** 解析结果：要么拿到音频字节，要么拿到一个需要再去下载的 url，要么是错误 */
    public static final class Parsed {
        public byte[] audio;      // 直接可播的音频字节
        public String audioUrl;   // 需要二次下载的地址
        public String error;      // 出错原因（已翻成人话）
        public String mime;       // 嗅探出的类型：mp3 / wav / pcm / ogg / 空

        public boolean ok() { return audio != null || (audioUrl != null && audioUrl.length() > 0); }
    }

    /**
     * 解析响应体。
     *
     * @param body     HTTP 响应字节
     * @param httpCode 状态码（非 200 时优先报错）
     */
    public static Parsed parse(byte[] body, int httpCode) {
        Parsed p = new Parsed();
        if (httpCode != 200) {
            p.error = httpError(httpCode, body);
            return p;
        }
        if (body == null || body.length == 0) {
            p.error = "服务端返回空内容";
            return p;
        }

        // 1) 直接就是音频（OpenAI 兼容的常态）
        String m = sniff(body);
        if (m.length() > 0) {
            p.audio = body;
            p.mime = m;
            return p;
        }

        // 2) 文本：可能是 JSON、可能是一段 base64/hex、也可能是个 url
        String txt = new String(body, java.nio.charset.Charset.forName("UTF-8")).trim();

        if (txt.startsWith("{") || txt.startsWith("[")) {
            // ★ 先看业务码。MiniMax 这类服务商「出错也返回 HTTP 200」，
            //   真正的错误在 base_resp.status_code / status_msg 里。
            //   不先查它就会把 2049（Key 无效）、2013（参数错）、1008（余额不足）
            //   一律报成下面那句「没有音频字段」—— 这是本工程历史上最误导人的提示，
            //   每次都逼着人从零重新排查。现在直接把服务商的原始错误透传出来。
            //   只对带 base_resp 的响应生效：status_code 是极常见的字段名，
            //   别的网关可能拿它表示别的含义，无差别解读会误报成功/失败。
            int biz = txt.indexOf("\"base_resp\"") >= 0 ? jsonCode(txt) : NO_BIZ_CODE;
            if (biz != 0 && biz != NO_BIZ_CODE) {
                String bmsg = jsonStr(txt, "status_msg");
                String trace = jsonStr(txt, "trace_id");
                p.error = "服务端返回业务错误 " + biz + "："
                        + (bmsg == null || bmsg.length() == 0 ? "（无描述）" : bmsg)
                        + (trace != null && trace.length() > 0 ? "［trace_id " + trace + "］" : "");
                return p;
            }

            String u = firstString(txt,
                    "\"url\"", "\"audio_url\"", "\"audioUrl\"", "\"audio\"");
            // url 优先（阿里云百炼、部分网关给的是链接）
            if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
                p.audioUrl = u;
                return p;
            }
            // base64 / hex 字段
            String b64 = firstString(txt, "\"data\"", "\"audio\"", "\"audio_base64\"", "\"base64\"");
            if (b64 == null && u != null) b64 = u;
            byte[] dec = decodeLoose(b64);
            if (dec != null) {
                p.audio = dec;
                p.mime = sniff(dec);
                if (p.mime.length() == 0) p.mime = "mp3";
                return p;
            }
            // 只有报错信息
            String msg = firstString(txt, "\"message\"", "\"msg\"", "\"error_msg\"", "\"detail\"");
            if (msg == null) msg = firstString(txt, "\"error\"");
            if (msg != null) {
                p.error = msg;
            } else {
                // 以前这里只说「没有音频字段」，把鉴权/参数/余额等真实原因全盖住了。
                // 现在把响应体前 200 字带出来，至少能看出服务商到底回了什么。
                p.error = "无法从服务端响应中取到音频（响应开头：" + snippet(txt) + "）";
            }
            return p;
        }

        // 3) 纯文本：可能是裸 url、base64 或 hex
        if (txt.startsWith("http://") || txt.startsWith("https://")) {
            if (txt.indexOf('\n') < 0 && txt.length() < 2048) {
                p.audioUrl = txt;
                return p;
            }
        }
        byte[] dec = decodeLoose(txt);
        if (dec != null) {
            p.audio = dec;
            p.mime = sniff(dec);
            if (p.mime.length() == 0) p.mime = "mp3";
            return p;
        }
        if (txt.length() < 300) {
            p.error = txt;
            return p;
        }
        p.error = "返回内容不是有效音频"; return p;
    }

    /** 取响应体开头一小段，用于报错时展示原文（去掉换行，避免撑爆弹窗） */
    public static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 200 ? t : t.substring(0, 200) + "…";
    }

    /** 把 HTTP 错误码翻成能指导用户下一步的中文 */
    public static String httpError(int code, byte[] body) {
        String extra = "";
        if (body != null && body.length > 0 && body.length < 1200) {
            String t = new String(body, java.nio.charset.Charset.forName("UTF-8")).trim();
            String msg = firstString(t, "\"message\"", "\"msg\"", "\"error_msg\"");
            if (msg != null && msg.length() > 0) extra = "：" + msg;
        }
        if (code == 401 || code == 403) return "HTTP " + code + "：Key 无效或无权调用" + extra;
        if (code == 404) return "HTTP 404：地址不存在。请检查「接口地址」是否填对，"
                + "多数服务商需要完整地址而不是只填域名" + extra;
        if (code == 429) return "HTTP 429：请求过于频繁或额度用尽" + extra;
        if (code >= 500) return "HTTP " + code + "：服务端错误，稍后再试" + extra;
        return "HTTP " + code + extra;
    }

    // ==================== 嗅探与解码 ====================

    /** 判断字节开头像什么音频格式；认不出返回空串 */
    public static String sniff(byte[] b) {
        if (b == null || b.length < 12) return "";
        if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "mp3";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "mp3";   // mp3 帧同步
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "wav";
        if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "ogg";
        if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "flac";
        return "";
    }

    /** base64 还是 hex 都试一遍；都不像就返回 null */
    public static byte[] decodeLoose(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() == 0) return null;
        if (t.startsWith("data:")) {            // data:audio/mp3;base64,xxxx
            int i = t.indexOf("base64,");
            if (i >= 0) t = t.substring(i + 7);
        }
        t = t.replaceAll("\\s", "");
        if (t.length() < 16) return null;

        if (isHex(t)) {
            byte[] d = fromHex(t);
            if (d != null && sniff(d).length() > 0) return d;
            if (d != null && d.length > 256) return d;   // 没有魔数的裸 PCM 也接受
        }
        if (isBase64(t)) {
            byte[] d = fromBase64(t);
            if (d != null && sniff(d).length() > 0) return d;
            if (d != null && d.length > 256) return d;
        }
        return null;
    }

    static boolean isHex(String s) {
        if (s.length() % 2 != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    static byte[] fromHex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        try {
            for (int i = 0; i < n; i++) {
                out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (Throwable t) { return null; }
        return out;
    }

    static boolean isBase64(String s) {
        if (s.length() < 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** 自己实现 base64，避免依赖 android.util（这样 Linux 环境也能测） */
    static byte[] fromBase64(String s) {
        final String AL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        String t = s.replace('-', '+');     // base64url
        int pad = 0;
        while (t.endsWith("=")) { t = t.substring(0, t.length() - 1); pad++; }
        int outLen = t.length() * 6 / 8;
        byte[] out = new byte[outLen];
        int buf = 0, bits = 0, oi = 0;
        for (int i = 0; i < t.length(); i++) {
            int v = AL.indexOf(t.charAt(i));
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                if (oi < outLen) out[oi++] = (byte) ((buf >> bits) & 0xFF);
            }
        }
        return out;
    }

    // ==================== 极简 JSON 取值 ====================

    /**
     * 找形如 {@code "key": "value"} 的第一个字符串值（支持转义）。
     * 只做「找到第一个匹配」，够用且不引入 JSON 库依赖。
     */
    public static String firstString(String json, String... keys) {
        if (json == null) return null;
        for (String k : keys) {
            int i = json.indexOf(k);
            while (i >= 0) {
                int j = i + k.length();
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                if (j < json.length() && json.charAt(j) == ':') {
                    j++;
                    while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                    if (j < json.length() && json.charAt(j) == '"') {
                        StringBuilder sb = new StringBuilder();
                        j++;
                        while (j < json.length()) {
                            char c = json.charAt(j);
                            if (c == '\\' && j + 1 < json.length()) {
                                char n = json.charAt(j + 1);
                                if (n == 'n') sb.append('\n');
                                else if (n == 't') sb.append('\t');
                                else if (n == 'r') sb.append('\r');
                                else if (n == 'u' && j + 5 < json.length()) {
                                    try {
                                        sb.append((char) Integer.parseInt(
                                                json.substring(j + 2, j + 6), 16));
                                        j += 4;
                                    } catch (Throwable ignore) {}
                                } else sb.append(n);
                                j += 2;
                                continue;
                            }
                            if (c == '"') break;
                            sb.append(c);
                            j++;
                        }
                        return sb.toString();
                    }
                }
                i = json.indexOf(k, i + 1);
            }
        }
        return null;
    }

    // ==================== 小工具 ====================

    /** 归一化返回格式：各家的合法值不完全一样，做一次映射 */
    public static String encFmt(String f) {
        if (f == null || f.length() == 0) return "mp3";
        String x = f.trim().toLowerCase();
        if (x.equals("wav") || x.equals("pcm") || x.equals("mp3")
                || x.equals("ogg_opus") || x.equals("opus") || x.equals("flac")) return x;
        return "mp3";
    }

    static String nz(String v, String def) { return v == null || v.length() == 0 ? def : v; }

    /** 语速保留两位小数，避免科学计数法进 JSON 把服务端搞挂 */
    static String num(float f) {
        if (f < 0.25f) f = 0.25f;
        if (f > 4.0f) f = 4.0f;
        return String.format(java.util.Locale.US, "%.2f", f);
    }

    static String reqId() {
        return "dsp" + Long.toString(System.currentTimeMillis(), 36);
    }

    /** 最小 JSON 字符串转义 */
    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }
}
