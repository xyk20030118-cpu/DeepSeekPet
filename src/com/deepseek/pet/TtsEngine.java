package com.deepseek.pet;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 桌宠语音合成（TTS）：把气泡文字念出来，让桌宠真正「开口说话」。
 *
 * <p><b>多协议版</b>。老版本只认 OpenAI 那一套：地址末尾无条件拼 {@code /audio/speech}、
 * {@code response_format} 硬编码 mp3、鉴权固定 Bearer，还爱拿「对话地址」去派生 ——
 * 对话默认是 api.deepseek.com，那家没有语音接口，于是必然 404。
 * 现在协议细节全部下沉到 {@link TtsCodec} 与 {@link TtsProvider}，本类只负责
 * 「发请求 → 拿到音频 → 播放 → 通知口型」这条流水线。</p>
 *
 * <p>支持的分发路径见 {@link TtsProvider}：OpenAI 兼容 / 阿里云百炼 / 火山引擎 /
 * MiniMax / 原样直发。响应统一走「先嗅探、再取值」，所以音频字节、JSON 里的 url、
 * JSON 里的 base64、裸 hex 都能吃。</p>
 *
 * <p>设计要点（沿用并加强）：</p>
 * <ul>
 *   <li>所有公开方法可从任意线程调用，回调统一切回主线程。</li>
 *   <li><b>口型同步</b>：音频真正开始 / 结束时通过 {@link MouthListener} 通知宿主。</li>
 *   <li><b>说话序号 speakSeq</b>：新一次说话立即作废上一次（含正在下载的），
 *       避免「打完字了旧语音还在念」。</li>
 *   <li>任何失败都静默降级 —— <b>绝不</b>因为念不出来而影响气泡显示。</li>
 *   <li>PCM 裸流自动补 WAV 头，否则 MediaPlayer 放不出来（火山/百炼都可能返 PCM）。</li>
 *   <li>{@link #prepareText} / {@link #esc} 无 Android 依赖，可直接单测。</li>
 * </ul>
 */
public final class TtsEngine {

    /** 口型回调：由宿主驱动桌宠张嘴 / 闭嘴 */
    public interface MouthListener {
        void onMouthStart();
        void onMouthStop();
    }

    /** 云端合成失败时的错误（最后一次），供「试听」显示原因 */
    private static volatile String lastError = null;

    private final Context appCtx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MouthListener mouth;
    private MediaPlayer player;
    private Thread worker;

    private volatile int speakSeq = 0;
    private volatile boolean released = false;
    private volatile boolean speaking = false;

    public interface ErrorListener { void onError(String reason); }
    private ErrorListener errListener;
    private volatile boolean errNotified = false;

    public TtsEngine(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    public void setMouthListener(MouthListener l) { this.mouth = l; }

    public static String lastError() { return lastError; }

    /** 用户是否开启了「让桌宠说话」 */
    public boolean enabled() { return Prefs.ttsEnabled(appCtx); }

    public boolean isSpeaking() { return speaking; }

    /** 合成中或播放中：用于把「连发」的多次朗读串起来，避免互相打断 */
    public boolean isBusy() { return speaking || player != null || (worker != null && worker.isAlive()); }

    public void setErrorListener(ErrorListener l) { this.errListener = l; }

    private void notifyErrorOnce() {
        if (errNotified) return;
        errNotified = true;
        final ErrorListener l = errListener;
        if (l == null) return;
        final String e = lastError == null ? "未知原因" : lastError;
        main.post(new Runnable() {
            @Override public void run() {
                try { l.onError(e); } catch (Throwable ignore) {}
            }
        });
    }

    /** 让下次失败重新提示一次（用户改了配置后调用） */
    public void resetErrorNotice() { errNotified = false; }

    // ==================== 公开入口 ====================

    /**
     * 念一段文字。返回 true 表示已接受（异步下载/播放中）。
     * 已在说话时会打断上一次 —— 对话场景下新内容优先。
     */
    public boolean speak(final String raw) {
        if (released || !enabled()) return false;
        final String text = prepareText(raw);
        if (text == null || text.length() == 0) return false;
        if (text.length() < Prefs.ttsMinChars(appCtx)) return false;

        final String url = Prefs.ttsEffectiveUrl(appCtx);
        final String key = Prefs.ttsEffectiveKey(appCtx);
        if (url.length() == 0) {
            lastError = "未填写语音接口地址";
            notifyErrorOnce();
            return false;
        }
        // 原样直发模式可能不需要 Key（自建服务常见），其余必须
        final String kind = Prefs.ttsKind(appCtx);
        if (key.length() == 0 && !TtsProvider.K_RAW.equals(kind)) {
            lastError = "未配置语音 API Key";
            notifyErrorOnce();
            return false;
        }

        stop();
        final int seq = ++speakSeq;
        Thread prev = worker;
        if (prev != null && prev.isAlive()) {
            try { prev.interrupt(); } catch (Throwable ignore) {}
        }

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    TtsCodec.Config c = new TtsCodec.Config();
                    c.kind   = kind;
                    c.model  = Prefs.ttsModelOrDefault(appCtx);
                    c.voice  = Prefs.ttsVoice(appCtx);
                    c.format = Prefs.ttsFormat(appCtx);
                    c.speed  = Prefs.ttsSpeed(appCtx);
                    c.text   = text;
                    c.appId  = Prefs.ttsAppId(appCtx);
                    c.token  = Prefs.ttsCloudKey(appCtx);
                    if (Prefs.ttsCloneReady(appCtx) && TtsProvider.supportsClone(kind)) {
                        c.cloneRefAudio = Prefs.ttsCloneRefAudio(appCtx);
                        c.cloneRefText  = Prefs.ttsCloneRefText(appCtx);
                    }

                    // ① 合成请求
                    byte[] body = TtsCodec.buildBody(c).getBytes("UTF-8");
                    Resp r = request(url, key, kind, body);

                    TtsCodec.Parsed p = TtsCodec.parse(r.body, r.code);
                    if (seq != speakSeq) return;
                    if (!p.ok()) {
                        lastError = p.error == null ? "合成失败" : p.error;
                        notifyErrorOnce();
                        return;
                    }

                    byte[] audio = p.audio;
                    String mime = p.mime;

                    // ② 服务端给的是链接 → 再下一趟
                    if (audio == null && p.audioUrl != null) {
                        Resp d = get(p.audioUrl, key, kind);
                        if (seq != speakSeq) return;
                        if (d.code != 200 || d.body == null || d.body.length == 0) {
                            lastError = "音频下载失败：" + TtsCodec.httpError(d.code, d.body);
                            notifyErrorOnce();
                            return;
                        }
                        audio = d.body;
                        mime = TtsCodec.sniff(audio);
                    }

                    if (audio == null || audio.length < 128) {
                        lastError = "返回内容不是有效音频";
                        notifyErrorOnce();
                        return;
                    }

                    // ③ 裸 PCM 补 WAV 头，否则 MediaPlayer 不认
                    if (mime == null || mime.length() == 0) mime = "mp3";
                    if ("pcm".equals(mime) && TtsCodec.sniff(audio).length() == 0) {
                        audio = wrapWav(audio, 24000, 1, 16);
                        mime = "wav";
                    }

                    final File out = new File(appCtx.getCacheDir(), "tts_cloud." + ext(mime));
                    FileOutputStream fo = new FileOutputStream(out);
                    fo.write(audio);
                    fo.flush();
                    fo.close();

                    if (seq != speakSeq) return;
                    lastError = null;
                    final File f = out;
                    main.post(new Runnable() {
                        @Override public void run() { play(f, seq); }
                    });
                } catch (Throwable t) {
                    lastError = describe(t);
                    notifyErrorOnce();
                }
            }
        }, "ds-tts-cloud");
        worker = t;
        t.start();
        return true;
    }

    /** 立即停止当前朗读（打断），并让桌宠闭嘴 */
    public void stop() {
        speakSeq++;
        main.post(new Runnable() {
            @Override public void run() {
                releasePlayer();
                speaking = false;
                mouthOff();
            }
        });
    }

    /** 随 Service 销毁释放全部资源 */
    public void release() {
        released = true;
        speakSeq++;
        releasePlayer();
        if (worker != null) {
            try { worker.interrupt(); } catch (Throwable ignore) {}
            worker = null;
        }
        mouth = null;
        errListener = null;
    }

    // ==================== MiniMax 音色克隆 ====================

    /**
     * 走完 MiniMax 的「上传 → 复刻 → 试听」三步，返回试听音频字节。
     * 失败返回 null，具体原因在 {@link #lastError()}。
     *
     * @param uri       参考音频（10 秒 ~ 5 分钟，mp3/m4a/wav，≤20MB）
     * @param voiceId   自定义音色 ID（8~256 位，首字符字母）
     * @param demoText  试听文本，空则不复刻试听
     */
    public byte[] cloneVoice(android.net.Uri uri, String voiceId, String demoText) {
        if (appCtx == null) { lastError = "语音未初始化"; return null; }
        String key = Prefs.ttsEffectiveKey(appCtx);
        String gid = Prefs.ttsGroupId(appCtx);
        if (key.length() == 0) { lastError = "没填 MiniMax 的 API Key"; return null; }
        // GroupId 不再需要：现行 /v1/files/upload 与 /v1/voice_clone 只认 Bearer，
        // 拼 GroupId 反而会让网关切到旧鉴权分支报 1004。这里不再拦截。

        // ---- 1) 上传参考音频 ----
        Resp up = uploadFile(uri, key, gid);
        if (up.code != 200 || up.body == null) {
            lastError = "上传音频失败（HTTP " + up.code + "）"
                    + (up.err != null ? " " + up.err : "") + snippet(up.body);
            return null;
        }
        String upText = new String(up.body);
        String fileId = TtsCodec.jsonNum(upText, "file_id");
        if (fileId == null) {
            lastError = "上传回包里没有 file_id：" + snippet(up.body);
            return null;
        }

        // ---- 2) 复刻 ----
        String model = Prefs.ttsModel(appCtx);
        if (model.length() == 0) model = "speech-2.8-hd";
        String body = TtsCodec.buildCloneBody(fileId, voiceId, model, demoText);
        Resp cl = request(TtsCodec.minimaxCloneUrl(gid), key, TtsProvider.K_MINIMAX,
                body.getBytes(java.nio.charset.Charset.forName("UTF-8")));
        if (cl.code != 200 || cl.body == null) {
            lastError = "复刻失败（HTTP " + cl.code + "）"
                    + (cl.err != null ? " " + cl.err : "") + snippet(cl.body);
            return null;
        }
        String clText = new String(cl.body);

        // 风控与业务码先查 —— 错误信息比 HTTP 码有用得多
        String sensitive = TtsCodec.jsonNum(clText, "input_sensitive_type");
        if (clText.indexOf("\"input_sensitive\":true") >= 0
                || clText.indexOf("\"input_sensitive\": true") >= 0) {
            lastError = "参考音频命中内容风控，换一段试试（类型 " + sensitive + "）";
            return null;
        }
        String code = TtsCodec.jsonNum(clText, "status_code");
        if (code != null && !"0".equals(code)) {
            String msg = TtsCodec.jsonStr(clText, "status_msg");
            lastError = "复刻失败：" + (msg == null ? "" : msg) + "（码 " + code + "）"
                    + (msg != null && msg.indexOf("认证") >= 0
                       ? " —— 需先在 MiniMax 网页端完成个人认证" : "");
            return null;
        }

        // ---- 3) 下载试听音频 ----
        String demo = TtsCodec.jsonStr(clText, "demo_audio");
        if (demo == null || demo.length() == 0) {
            lastError = "复刻成功但没拿到试听音频（可把音色 ID 填成 "
                    + voiceId + " 直接使用）";
            return null;
        }
        Resp dl = get(demo, "", "");
        if (dl.code != 200 || dl.body == null || dl.body.length == 0) {
            lastError = "试听音频下载失败（HTTP " + dl.code + "）";
            return null;
        }
        lastError = "";
        return dl.body;
    }

    /** 以 multipart/form-data 上传复刻音频 */
    private Resp uploadFile(android.net.Uri uri, String key, String gid) {
        HttpURLConnection conn = null;
        java.io.InputStream is = null;
        try {
            String boundary = "----DSP" + System.currentTimeMillis();
            is = appCtx.getContentResolver().openInputStream(uri);
            if (is == null) return new Resp(-1, null, "打不开所选音频");

            ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"purpose\"\r\n\r\n"
                    + "voice_clone\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"ref.mp3\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            bo.write(head.getBytes("UTF-8"));
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close(); is = null;
            bo.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));

            conn = (HttpURLConnection) new URL(TtsCodec.minimaxUploadUrl(gid)).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);          // 上传大音频要久一点
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + key);
            OutputStream os = conn.getOutputStream();
            bo.writeTo(os);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            return new Resp(code, in == null ? null : readAll(in), null);
        } catch (Throwable t) {
            return new Resp(-1, null, describe(t));
        } finally {
            try { if (is != null) is.close(); } catch (Throwable ignore) {}
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignore) {} }
        }
    }

    /** 从响应体里截一小段当错误详情 */
    private static String snippet(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b);
        if (s.length() > 200) s = s.substring(0, 200);
        return "  " + s;
    }

    // ==================== 网络 ====================

    private static final class Resp {
        int code; byte[] body; String err;
        Resp(int c, byte[] b, String e) { code = c; body = b; err = e; }
    }

    private Resp request(String url, String key, String kind, byte[] body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(90000);            // 克隆要上传参考音频再推理，放宽
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "*/*");
            String auth = TtsCodec.authHeader(kind, key, Prefs.ttsAppId(appCtx));
            if (auth.length() > 7) conn.setRequestProperty("Authorization", auth);
            // 少数网关（如 MiniMax）同时认 X-Api-Key
            if (key != null && key.length() > 0 && TtsProvider.K_MINIMAX.equals(kind)) {
                conn.setRequestProperty("X-Api-Key", key);
            }

            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = in == null ? null : readAll(in);
            return new Resp(code, data, null);
        } catch (Throwable t) {
            return new Resp(-1, null, describe(t));
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignore) {} }
        }
    }

    private Resp get(String url, String key, String kind) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            if (key != null && key.length() > 0) {
                conn.setRequestProperty("Authorization", TtsCodec.authHeader(kind, key, ""));
            }
            int code = conn.getResponseCode();
            InputStream in = code == 200 ? conn.getInputStream() : conn.getErrorStream();
            byte[] data = in == null ? null : readAll(in);
            return new Resp(code, data, null);
        } catch (Throwable t) {
            return new Resp(-1, null, describe(t));
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Throwable ignore) {} }
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** 把异常翻成用户能看懂的一句话 */
    static String describe(Throwable t) {
        if (t == null) return "未知错误";
        String cls = t.getClass().getSimpleName();
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (cls.contains("UnknownHost")) return "无法解析域名，请检查网络或地址拼写";
        if (cls.contains("SocketTimeout") || cls.contains("Timeout")) return "连接超时，服务端响应过慢";
        if (cls.contains("SSL") || cls.contains("Certificate")) return "HTTPS 证书校验失败";
        if (cls.contains("Connect")) return "无法连接服务器";
        return cls + (m.length() == 0 ? "" : "：" + m);
    }

    // ==================== 播放 ====================

    private void play(final File f, final int seq) {
        if (released || seq != speakSeq) return;
        try {
            releasePlayer();
            MediaPlayer mp = new MediaPlayer();
            player = mp;
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer m) {
                    if (seq != speakSeq) { try { m.release(); } catch (Throwable ignore) {} return; }
                    try { m.start(); speaking = true; mouthOn(); }
                    catch (Throwable ignore) { speaking = false; mouthOff(); }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer m) { speaking = false; releasePlayer(); mouthOff(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer m, int what, int extra) {
                    speaking = false; releasePlayer(); mouthOff();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            speaking = false;
            mouthOff();
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Throwable ignore) {}
            try { player.release(); } catch (Throwable ignore) {}
            player = null;
        }
    }

    // ==================== 口型回调 ====================

    private void mouthOn() {
        final MouthListener l = mouth;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() { try { l.onMouthStart(); } catch (Throwable ignore) {} }
        });
    }

    private void mouthOff() {
        final MouthListener l = mouth;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() { try { l.onMouthStop(); } catch (Throwable ignore) {} }
        });
    }

    // ==================== 音频工具 ====================

    /** 给裸 PCM 补一个标准 WAV 头（否则 MediaPlayer 无法播放） */
    public static byte[] wrapWav(byte[] pcm, int sampleRate, int channels, int bits) {
        if (pcm == null) return null;
        int dataLen = pcm.length;
        int byteRate = sampleRate * channels * bits / 8;
        int blockAlign = channels * bits / 8;
        byte[] out = new byte[44 + dataLen];
        int p = 0;
        p = putAscii(out, p, "RIFF");
        p = putInt(out, p, 36 + dataLen);
        p = putAscii(out, p, "WAVE");
        p = putAscii(out, p, "fmt ");
        p = putInt(out, p, 16);
        p = putShort(out, p, (short) 1);              // PCM
        p = putShort(out, p, (short) channels);
        p = putInt(out, p, sampleRate);
        p = putInt(out, p, byteRate);
        p = putShort(out, p, (short) blockAlign);
        p = putShort(out, p, (short) bits);
        p = putAscii(out, p, "data");
        p = putInt(out, p, dataLen);
        System.arraycopy(pcm, 0, out, 44, dataLen);
        return out;
    }

    private static int putAscii(byte[] b, int p, String s) {
        for (int i = 0; i < s.length(); i++) b[p++] = (byte) s.charAt(i);
        return p;
    }
    private static int putInt(byte[] b, int p, int v) {
        b[p++] = (byte) (v & 0xFF); b[p++] = (byte) ((v >> 8) & 0xFF);
        b[p++] = (byte) ((v >> 16) & 0xFF); b[p++] = (byte) ((v >> 24) & 0xFF);
        return p;
    }
    private static int putShort(byte[] b, int p, short v) {
        b[p++] = (byte) (v & 0xFF); b[p++] = (byte) ((v >> 8) & 0xFF);
        return p;
    }

    static String ext(String mime) {
        if (mime == null) return "mp3";
        String m = mime.toLowerCase();
        if (m.indexOf("wav") >= 0) return "wav";
        if (m.indexOf("ogg") >= 0) return "ogg";
        if (m.indexOf("flac") >= 0) return "flac";
        return "mp3";
    }

    // ==================== 文本清洗（静态，可单测） ====================

    /**
     * 把气泡文本收拾成「适合朗读」的纯文字：
     * 去掉链接、markdown 标记、表情符号与装饰性前缀，压缩空白。
     * 返回 null 表示没有可念的内容。
     */
    public static String prepareText(String s) {
        if (s == null) return null;
        String t = s;
        t = t.replaceAll("https?://\\S+", " ");
        t = t.replaceAll("[*_`#>~]+", "");

        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            sb.appendCodePoint(isSpeakable(cp) ? cp : ' ');
            i += Character.charCount(cp);
        }
        t = sb.toString().replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[\\p{Punct}\\s]+", "").trim();
        return t;
    }

    /** 该码位是否「可朗读」：保留中日韩文字、拉丁字母数字与常用标点，其余（表情等）丢弃 */
    private static boolean isSpeakable(int cp) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(cp);
        if (b == null) return false;
        if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS) return true;
        if (b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A) return true;
        if (b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION) return true;
        if (b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS) return true;
        if (b == Character.UnicodeBlock.GENERAL_PUNCTUATION) return true;
        if (b == Character.UnicodeBlock.BASIC_LATIN) return cp >= 32 && cp < 127;
        if (b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT) return true;
        if (b == Character.UnicodeBlock.HIRAGANA) return true;
        if (b == Character.UnicodeBlock.KATAKANA) return true;
        if (b == Character.UnicodeBlock.HANGUL_SYLLABLES) return true;
        return false;
    }

    /** 最小 JSON 字符串转义（保留供外部兼容调用） */
    public static String esc(String s) { return TtsCodec.esc(s); }
}
