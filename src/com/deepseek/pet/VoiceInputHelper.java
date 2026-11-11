package com.deepseek.pet;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 语音输入（离线版，全自动断句 + 自动发送）。
 *
 * 设计要点：
 *  - 录音完全在本地完成：AudioRecord 16kHz / 16bit / 单声道，采到内存里的 PCM。
 *  - 识别完全在本地完成：sherpa-onnx + SenseVoice int8（见 {@link SherpaAsr}）。
 *    **不联网、不上传任何音频、不接任何第三方 ASR API。**
 *  - **全自动**：点一下麦克风开始说，说完停顿约 0.9 秒自动结束识别并直接提问发送，
 *    全程不需要再点任何按钮。
 *
 * 断句判据（v93 重写，真机实测标定）：
 *  ① 「说完」= VAD 确认过人声后，静音累计 900ms → 自动断句。
 *  ② 「一直没听到人声」= **2 秒**（v93 由 12 秒缩短）→ 自动断开。
 *     并且**不再直接放弃**：只要录到的音频里存在声音迹象（peakRms ≥ 0.0012，
 *     约为实测安静底噪 0.0004 的 3 倍），就把这段送去识别；
 *     识别出真正的文字（含字母/汉字/数字）就照常发送，
 *     只有确实是纯静音或纯标点幻觉时才提示「没听清」。
 *  ③ 声音判据用 **Silero VAD**（概率>0.5 且该帧 RMS>0.0006）。
 *     ⚠️ 旧实现只用「能量 > max(噪声底×2, 0.004)」：真机噪声底约 0.0004 时阈值落到
 *     0.004，而安静环境仍有 6.8% 的帧超过它 → 静音计时被不断重置 →
 *     **900ms 静音永远攒不满 → 自动断句从不触发**（正是「说完不会自动发送」的根因）。
 *     横向实测：VR 源安静 15s，Silero 误报 0.0%，纯能量（0.004）误报 6.8%。
 *  - VAD 不可用时退回能量判定（阈值随噪声底自适应）。
 *  - 识别前用同一套判定裁掉首尾静音，减少 SenseVoice 在纯静音上的幻觉。
 */
public class VoiceInputHelper {

    public interface Callback {
        /** 识别成功，返回文字（已 trim）。 */
        void onResult(String text);
        /** 状态变化：正在聆听… / 识别中… */
        void onState(String state);
        /** 失败（权限、设备、模型、识别为空等）。 */
        void onError(String msg);
        /** 实时音量（0~1，仅用于界面显示）。默认忽略。 */
        default void onLevel(float level) {}
    }

    private static final String TAG = "DSVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_MS = SAMPLE_RATE / 1000 * 2;   // 32 B/ms

    // ---------------------------------------------------------------- 断句参数
    /** VAD 窗口：Silero 在 16kHz 下窗口固定 512 采样 = 32ms。 */
    private static final int VAD_WIN = 512;
    private static final int VAD_FRAME_MS = 32;
    private static final int VAD_FRAME_BYTES = VAD_WIN * 2;

    /** 读缓冲：一次读多帧，兼顾实时性与开销。 */
    private static final int READ_FRAMES = 4;
    private static final int READ_SAMPLES = VAD_WIN * READ_FRAMES;

    /** VAD 概率超过它才算「像人声」。 */
    private static final float VAD_SPEECH_PROB = 0.5f;
    /** 额外能量闸门：低于它的一律不算说话（实测真安静底噪 p50≈0.0004）。 */
    private static final float MIN_SPEECH_RMS = 0.0006f;
    /** 说完之后静音这么久 → 自动结束（主人不需要再点任何按钮）。 */
    private static final int SILENCE_END_MS = 900;

    /**
     * 「识别不到文字」的超时：一直没检测到人声，2 秒就自动断开。
     * （旧值 12 秒太长，主人点完麦没说话要干等；且超时后旧实现直接丢弃录音。）
     */
    private static final int NO_SPEECH_TIMEOUT_MS = 2000;

    /**
     * 声音迹象门限：峰值 RMS 达到它，说明麦克风确实收到过人声（含 VAD 漏检的轻声）。
     * 实测安静室内底噪 p50≈0.0004、峰值≈0.0006，故 0.0012 有约 2 倍安全余量，
     * 既能兜住「说话太轻导致 VAD 没认出来」，又不会把纯静音当成有人说话。
     */
    private static final float SOUND_HINT_RMS = 0.0012f;

    /** 最长录音时长，防止环境一直有响声。 */
    private static final int MAX_RECORD_MS = 30000;
    /** 有效语音最短时长（不足则报「没听清」，但只要有声音迹象仍会尝试识别）。 */
    private static final int MIN_SPEECH_MS = 200;
    /** 首尾各留的余量，避免削掉字头字尾。 */
    private static final int EDGE_PAD_MS = 250;

    // —— 能量判定的后备参数（仅 VAD 不可用时使用）——
    /** 起始噪声底校准时长。 */
    private static final int CALIB_MS = 500;
    /** 能量超过噪声底多少倍算「有人在说话」。 */
    private static final float SPEECH_RATIO = 3.0f;
    private static final float NOISE_SMOOTH = 0.05f;
    /** 判为「长时间静音」后开始更新噪声底。 */
    private static final int NOISE_UPDATE_AFTER_MS = 700;

    private final android.content.Context ctx;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();

    private AudioRecord recorder;
    private Thread recThread;
    private volatile boolean listening = false;

    /** 首/末个「有人声」帧在 pcm 里的字节偏移，用于裁掉首尾静音。 */
    private volatile int firstVoiceByte = -1;
    private volatile int lastVoiceByte = -1;
    /** 本段最后一个「有人声」帧的结尾，用于自动断句的静音计时。 */
    private volatile long lastVoiceMs = 0L;
    private volatile int speechMs = 0;
    private volatile String endReason = null;
    /** 诊断用：本次录到的最大 RMS。 */
    private volatile float peakRms = 0f;
    /** 诊断用：VAD 是否真的参与了本次判定。 */
    private volatile boolean usedVad = false;
    /** 实时音量上报节流用。 */
    private long lastLevelMs = 0L;
    /** 是否因为「一直识别不到人声」而 2 秒超时断开。 */
    private volatile boolean timedOutNoSpeech = false;

    // —— Android 15 麦克风并发限制：把 VOICE_RECOGNITION 用途上报为「麦克风」，避免被静音 ——
    private android.media.AudioManager am;
    private android.media.AudioManager.AudioRecordingCallback arCb;

    public VoiceInputHelper(android.content.Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    public boolean isListening() { return listening; }

    /** 回调统一在主线程触发：调用方（FloatingService）会直接 setText 悬浮窗，跨线程会闪退。 */
    private void post(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else ui.post(r);
    }

    /**
     * 后台预热：释放模型（首次）+ 加载引擎（含 VAD）。应在桌宠启动后调用一次，
     * 这样主人第一次点麦克风就不必等待模型加载。
     */
    public static void preloadAsync(final android.content.Context c) {
        // ★ 资源没装就不必预热（用户要求"语音输入改为选择性下载资源"）：
        //   否则每次开桌宠都白跑一次 assets 探测 + 线程启动。
        //   这里不是错误路径 —— 没装是正常状态。
        if (!SherpaAsr.isUsable(c)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    SherpaAsr.ensureWithInstall(c);
                } catch (Throwable e) {
                    Log.w(TAG, "预加载失败: " + e);
                }
            }
        }, "ds-asr-preload").start();
    }

    // ------------------------------------------------------------ 麦克风注册

    private void registerMicSource() {
        try {
            if (Build.VERSION.SDK_INT < 24) return;
            am = (android.media.AudioManager) ctx.getSystemService(android.content.Context.AUDIO_SERVICE);
            if (am == null) return;
            arCb = new android.media.AudioManager.AudioRecordingCallback() {
                @Override public void onRecordingConfigChanged(
                        java.util.List<android.media.AudioRecordingConfiguration> cfgs) {
                    if (am == null || cfgs == null) return;
                    for (android.media.AudioRecordingConfiguration c : cfgs) {
                        try {
                            if (c.getAudioSource() == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                                try {
                                    android.media.AudioManager.class
                                            .getMethod("setAudioSource", int.class)
                                            .invoke(am, MediaRecorder.AudioSource.MIC);
                                } catch (Throwable ignore) {}
                            }
                        } catch (Exception ignore) {}
                    }
                }
            };
            am.registerAudioRecordingCallback(arCb, null);
        } catch (Exception e) {
            Log.w(TAG, "registerAudioRecordingCallback 失败: " + e);
        }
    }

    private void unregisterMicSource() {
        try {
            if (am != null && arCb != null) am.unregisterAudioRecordingCallback(arCb);
        } catch (Exception ignore) {}
        arCb = null;
        am = null;
    }

    // ------------------------------------------------------------ 录音 + VAD

    /** 开始录音。返回 false 表示无法开始（已在回调里说明原因）。 */
    @SuppressLint("MissingPermission")
    public boolean start() {
        if (listening) return true;
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = SAMPLE_RATE * 2;
        int bufBytes = Math.max(minBuf * 2, VAD_FRAME_BYTES * 16);
        try {
            // VOICE_RECOGNITION：自带轻度降噪/AEC，真机实测底噪最低（p50≈0.0004）
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufBytes);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 创建失败: " + e);
            post(new Runnable() { @Override public void run() { cb.onError("无法创建录音器"); }});
            return false;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            try { recorder.release(); } catch (Exception ignore) {}
            recorder = null;
            post(new Runnable() { @Override public void run() { cb.onError("录音初始化失败（可能缺少麦克风权限）"); }});
            return false;
        }
        registerMicSource();   // 必须在 startRecording 之前注册
        pcm.reset();
        firstVoiceByte = -1;
        lastVoiceByte = -1;
        lastVoiceMs = 0L;
        speechMs = 0;
        peakRms = 0f;
        endReason = null;
        usedVad = false;
        timedOutNoSpeech = false;
        lastLevelMs = 0L;
        listening = true;

        recThread = new Thread(new Runnable() {
            @Override public void run() { recordLoop(); }
        }, "ds-voice-rec");
        recThread.start();
        post(new Runnable() { @Override public void run() { cb.onState("正在聆听…"); }});
        return true;
    }

    /** 录音线程：逐窗口读 PCM，边写缓冲边用 Silero VAD 判定，说完自动结束。 */
    private void recordLoop() {
        final short[] buf = new short[READ_SAMPLES];
        try {
            recorder.startRecording();
        } catch (Exception e) {
            listening = false;
            final String m = e.getMessage();
            ui.post(new Runnable() { @Override public void run() {
                cb.onError("录音启动失败" + (m == null ? "" : ": " + m));
            }});
            return;
        }

        // VAD 就绪则用 VAD，否则退回能量判定
        final boolean vadOk = SherpaAsr.ensureVad(ctx) && SherpaAsr.isVadReady();
        usedVad = vadOk;
        if (vadOk) SherpaAsr.resetVad();
        Log.i(TAG, "断句判据 = " + (vadOk ? "Silero VAD" : "能量（后备）"));

        final long t0 = System.currentTimeMillis();
        final float[] vadFrame = new float[VAD_WIN];
        float noiseRms = -1f;
        float calibSum = 0f;
        int calibFrames = 0;
        final int calibTarget = Math.max(1, CALIB_MS / VAD_FRAME_MS);
        boolean autoStop = false;

        while (listening) {
            int n;
            try { n = recorder.read(buf, 0, buf.length); }
            catch (Exception e) { break; }
            if (n <= 0) continue;

            final int writeOff = pcm.size();
            for (int i = 0; i < n; i++) {
                pcm.write(buf[i] & 0xff);
                pcm.write((buf[i] >> 8) & 0xff);
            }

            // ---- 按 512 采样窗口做判定（VAD 要求固定窗口）----
            for (int off = 0; off + VAD_WIN <= n; off += VAD_WIN) {
                float sum = 0f;
                for (int i = 0; i < VAD_WIN; i++) {
                    float f = buf[off + i] / 32768.0f;
                    vadFrame[i] = f;
                    sum += f * f;
                }
                final float rms = (float) Math.sqrt(sum / VAD_WIN);
                if (rms > peakRms) peakRms = rms;

                final long now = System.currentTimeMillis() - t0;
                final int frameByteOff = writeOff + off * 2;

                boolean isSpeech;
                if (vadOk) {
                    final float prob = SherpaAsr.vadProbability(vadFrame);
                    // VAD 说像人声，且不是极安静的孤立误报。
                    // 注意：VAD 路径**不需要**噪声底校准（Silero 自带自适应），
                    // 旧代码沿用能量路径的 500ms 盲区会吞掉开头 480ms 的说话，
                    // 对 2 秒超时规则尤其致命 —— 人一开口就被误判成「没听到声音」。
                    isSpeech = prob > VAD_SPEECH_PROB && rms > MIN_SPEECH_RMS;
                } else {
                    // ---- 后备：自适应能量判定 ----
                    if (calibFrames < calibTarget) {
                        if (noiseRms < 0 || rms < noiseRms) noiseRms = rms;
                        calibSum += rms;
                        calibFrames++;
                        if (calibFrames == calibTarget) {
                            float avg = calibSum / calibFrames;
                            noiseRms = Math.min(noiseRms, avg * 0.5f);
                            Log.i(TAG, "噪声底校准 noiseRms=" + noiseRms);
                        }
                        continue;
                    }
                    if (noiseRms <= 0f) noiseRms = MIN_SPEECH_RMS;
                    final float threshold = Math.max(noiseRms * SPEECH_RATIO, MIN_SPEECH_RMS);
                    isSpeech = rms > threshold;
                    if (!isSpeech && (lastVoiceMs <= 0 || now - lastVoiceMs > NOISE_UPDATE_AFTER_MS)) {
                        noiseRms = noiseRms * (1f - NOISE_SMOOTH) + rms * NOISE_SMOOTH;
                    }
                }

                if (isSpeech) {
                    if (firstVoiceByte < 0) firstVoiceByte = frameByteOff;
                    lastVoiceByte = frameByteOff + VAD_WIN * 2;
                    speechMs += VAD_FRAME_MS;
                    lastVoiceMs = now;
                }

                // ---- 断句判定 ----
                if (lastVoiceMs > 0) {
                    // ① 已确认人声：停顿够久 → 说完，自动发送
                    if (speechMs >= MIN_SPEECH_MS && now - lastVoiceMs >= SILENCE_END_MS) {
                        autoStop = true;
                        endReason = null;
                    } else if (now >= MAX_RECORD_MS) {
                        autoStop = true;
                        endReason = null;
                    }
                } else if (now >= NO_SPEECH_TIMEOUT_MS) {
                    // ② 一直没识别到人声 → 2 秒自动断开。
                    //    注意：这里**不再直接放弃**，finishRecording 会用 peakRms
                    //    判断是否真有声音，有就把录音送去识别并发送。
                    autoStop = true;
                    timedOutNoSpeech = true;
                    endReason = null;
                }
                if (autoStop) break;

                // 实时音量上报（每 ~160ms 一次，避免刷 UI）
                if (now - lastLevelMs >= 160) {
                    lastLevelMs = now;
                    final float lv = Math.min(1f, rms * 12f);
                    ui.post(new Runnable() { @Override public void run() { cb.onLevel(lv); }});
                }
            }
            if (autoStop) break;
        }

        Log.i(TAG, "录音结束 speechMs=" + speechMs + " peakRms=" + peakRms
                + " vad=" + usedVad + " 超时无语音=" + timedOutNoSpeech + " reason=" + endReason);
        if (autoStop) finishRecording();
    }

    /** 停止录音并异步本地识别；结果通过 onResult / onError 回调（主线程）。 */
    public void stop() {
        if (!listening) return;
        finishRecording();
    }

    /** 停录音 → 裁静音 → 识别 → 回调（幂等）。 */
    private synchronized void finishRecording() {
        if (!listening) return;
        listening = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignore) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignore) {}
        recorder = null;
        unregisterMicSource();

        final byte[] raw = pcm.toByteArray();
        pcm.reset();
        final String reason = endReason;
        final boolean noSpeech = timedOutNoSpeech;
        final boolean hadSound = peakRms >= SOUND_HINT_RMS;

        if (reason != null) {                        // 一直没说话（正常不会走到，保留兜底）
            Log.w(TAG, "放弃识别：peakRms=" + peakRms);
            ui.post(new Runnable() { @Override public void run() { cb.onError(reason); }});
            return;
        }
        // 2 秒超时且麦克风真的什么都没收到 → 不浪费时间识别，直接提示
        if (noSpeech && !hadSound) {
            Log.w(TAG, "2 秒内没有任何声音迹象 (peakRms=" + peakRms + ")，提示没听清");
            ui.post(new Runnable() { @Override public void run() {
                cb.onError("没听清，请再说一次");
            }});
            return;
        }
        if (noSpeech) {
            Log.i(TAG, "2 秒内 VAD 未确认人声，但检测到声音迹象 (peakRms=" + peakRms
                    + ") → 仍送识别，尽量发送");
        }
        if (speechMs < MIN_SPEECH_MS) {
            // 兜底：只要能量足够（说明确实有声音），就别放弃，直接送识别
            if (!hadSound) {
                ui.post(new Runnable() { @Override public void run() {
                    cb.onError("没听清，请再说一次（麦克风几乎没收到声音）");
                }});
                return;
            }
            Log.w(TAG, "speechMs 偏短但 peakRms 足够，仍送识别: " + peakRms);
        }

        // 裁掉首尾静音（各留 250ms 余量）
        int from = firstVoiceByte, to = lastVoiceByte;
        final int pad = BYTES_PER_MS * EDGE_PAD_MS;
        if (from < 0 || to <= from) { from = 0; to = raw.length; }
        else {
            from = Math.max(0, from - pad);
            to = Math.min(raw.length, to + pad);
        }
        final byte[] slice = new byte[to - from];
        System.arraycopy(raw, from, slice, 0, to - from);
        Log.i(TAG, "裁静音 " + raw.length + "B -> " + slice.length + "B");

        post(new Runnable() { @Override public void run() { cb.onState("识别中…"); }});
        new Thread(new Runnable() {
            @Override public void run() {
                final String text = recognizeLocal(slice);
                ui.post(new Runnable() { @Override public void run() {
                    if (text == null) {
                        // ★ 「资源没装」和「识别出错」必须说不同的话：
                        //   模型现在不随 APK 内置（可选资源）。若把"还没下载"
                        //   报成"离线识别失败"，用户会以为 App 坏了，
                        //   而其实只是需要先获取资源。
                        if (SherpaAsr.REASON_NOT_BUNDLED.equals(SherpaAsr.lastReason())
                                || !SherpaAsr.isUsable(ctx)) {
                            cb.onError("语音输入资源未安装（约 229 MB）—— "
                                    + "装好后就能离线听你说话。桌宠说话不受影响。");
                        } else {
                            cb.onError("离线识别失败：" + (SherpaAsr.lastError() == null
                                    ? "请重试" : SherpaAsr.lastError()));
                        }
                    }
                    else if (meaningless(text)) cb.onError("没听清，请再说一次");
                    else cb.onResult(text);
                }});
            }
        }, "ds-voice-asr").start();
    }

    /**
     * 判断识别结果是否是「没有意义的纯标点/符号」。
     * SenseVoice 在纯静音上常吐 “.” 或 “。”，这类结果不能当成主人的话发出去。
     */
    static boolean meaningless(String text) {
        if (text == null) return true;
        final String s = text.trim();
        if (s.length() == 0) return true;
        for (int i = 0; i < s.length(); i++) {
            final char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) return false;   // 汉字/字母/数字 → 有意义
        }
        return true;                                            // 全是标点/符号
    }

    /** 取消（不识别）。 */
    public void cancel() {
        listening = false;
        try { if (recorder != null) recorder.stop(); } catch (Exception ignore) {}
        try { if (recorder != null) recorder.release(); } catch (Exception ignore) {}
        recorder = null;
        unregisterMicSource();
        pcm.reset();
        firstVoiceByte = -1;
        lastVoiceByte = -1;
        lastVoiceMs = 0L;
        speechMs = 0;
        endReason = null;
        timedOutNoSpeech = false;
    }

    public void destroy() { cancel(); }

    // ------------------------------------------------------------ 本地识别

    /** PCM16LE 单声道字节流 → 16k float PCM → SenseVoice 识别。 */
    private String recognizeLocal(byte[] pcmBytes) {
        try {
            if (!SherpaAsr.ensureWithInstall(ctx)) return null;
            final int n = pcmBytes.length / 2;
            float[] f = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = pcmBytes[i * 2] & 0xff;
                int hi = pcmBytes[i * 2 + 1];
                short s = (short) ((hi << 8) | lo);
                f[i] = s / 32768.0f;
            }
            long t0 = System.currentTimeMillis();
            String r = SherpaAsr.recognize(f, SAMPLE_RATE);
            Log.i(TAG, "离线识别 " + (System.currentTimeMillis() - t0) + "ms -> " + r);
            return r;
        } catch (Throwable e) {
            Log.e(TAG, "本地识别异常: " + e);
            return null;
        }
    }
}
