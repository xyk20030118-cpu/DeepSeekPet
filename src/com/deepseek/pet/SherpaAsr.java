package com.deepseek.pet;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 离线语音识别引擎（sherpa-onnx + SenseVoice int8 + Silero VAD）。
 *
 * 设计要点：
 *  - 100% 本地：模型随 APK 打包在 assets/asr/，首次使用时释放到 files/asr/，
 *    之后完全离线推理，不联网、不接任何第三方 ASR API。
 *  - 单例常驻：SenseVoice int8 约 239MB，只加载一次（约 4.5s），之后每次识别 RTF≈0.05。
 *  - 支持中/英/日/韩/粤（language=auto 自动判定），并带 ITN（数字/标点归一化）。
 *  - **Silero VAD（643KB）**：用于「说完自动断句」。实测真机安静环境误报 0~1.3%，
 *    已知人声正确捕获，远优于纯能量阈值（后者在 SM6 上约 0.4% 底噪却因阈值过低误判 6~55%）。
 *
 * 线程安全：installModel/ensure/recognize 均加锁，可被任意后台线程调用。
 */
public final class SherpaAsr {

    private static final String TAG = "DSAsr";
    private static final String ASSET_DIR = "asr";
    private static final String MODEL_NAME = "sensevoice.int8.onnx";
    private static final String TOKENS_NAME = "tokens.txt";
    private static final String VAD_NAME = "silero_vad.onnx";
    private static final String MARK = ".extracted_v88";

    /** 常驻识别器（null = 未加载；failed = 加载失败不再重试）。 */
    private static com.k2fsa.sherpa.onnx.OfflineRecognizer rec;
    private static boolean failed = false;
    private static String failMsg = null;

    // ------------------------------------------------- 未就绪的原因（供 UI 分话术）
    /** 模型没内置（正常状态：可选资源，需用户下载） */
    public static final String REASON_NOT_BUNDLED = "not_bundled";
    /** 释放/写入失败（真正的错误） */
    public static final String REASON_IO = "io_error";

    private static volatile String lastReason = null;

    /** 上次未就绪的原因；null = 没有失败过 */
    public static String lastReason() { return lastReason; }

    /** 常驻 VAD（与识别器同时加载；失败则断句自动退回能量判定）。 */
    private static com.k2fsa.sherpa.onnx.Vad vad;
    private static boolean vadFailed = false;

    private SherpaAsr() {}

    // ------------------------------------------------------------- 路径

    public static File dir(Context c) { return new File(c.getApplicationContext().getFilesDir(), "asr"); }
    public static File modelFile(Context c) { return new File(dir(c), MODEL_NAME); }
    public static File tokensFile(Context c) { return new File(dir(c), TOKENS_NAME); }
    public static File vadFile(Context c) { return new File(dir(c), VAD_NAME); }

    /** 模型是否已释放到私有目录。 */
    public static boolean isInstalled(Context c) {
        try {
            // ★ 体积下限收敛到 MODEL_MIN_BYTES（与 importModelFile 同源），
            //   改一处阈值两处同时生效，不会出现"导入时嫌小、判定又嫌大"。
            return modelFile(c).isFile() && modelFile(c).length() > MODEL_MIN_BYTES
                    && tokensFile(c).isFile() && tokensFile(c).length() > 1000;
        } catch (Exception e) {
            return false;
        }
    }

    /** VAD 模型是否已释放。 */
    public static boolean isVadInstalled(Context c) {
        try {
            return vadFile(c).isFile() && vadFile(c).length() > 100 * 1024;
        } catch (Exception e) {
            return false;
        }
    }

    /** 是否已加载好、可以立即识别。 */
    public static boolean isReady() { return rec != null; }

    /** VAD 是否可用（用于断句）。 */
    public static boolean isVadReady() { return vad != null; }

    // ------------------------------------------------------------- 模型释放

    /**
     * 把 assets/asr 里的模型释放到 files/asr（幂等，带标记文件）。
     *
     * <h3>★ 第二十一轮：模型不再随 APK 内置</h3>
     * 228MB 的 SenseVoice 模型原先打包进 assets，占 APK 体积的 40%。
     * 现在改成**可选资源**（用户要求「把语音输入改为选择性下载资源」），
     * 所以 assets 里**找不到它是正常情况**，不是错误。
     *
     * <p>因此这里的返回值语义要分清：
     * <ul>
     *   <li>{@code true} —— 模型已就绪（本来就装着，或刚释放成功）</li>
     *   <li>{@code false} —— 未就绪，且 {@link #lastReason()} 说明原因：
     *       {@link #REASON_NOT_BUNDLED}（没内置，属于正常）
     *       或别的错误（IO 失败等）</li>
     * </ul>
     * 调用方据此给用户**不同的话术**：「需要先下载」vs「释放失败」。
     * 一律当成错误会让"没内置"这种正常状态弹出吓人的报错。
     *
     * @return true 表示模型可用
     */
    public static synchronized boolean installModel(Context c) {
        final Context ctx = c.getApplicationContext();
        lastReason = null;
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) {
            failMsg = "无法创建模型目录";
            lastReason = REASON_IO;
            return false;
        }
        try {
            AssetManager am = ctx.getAssets();

            // ★ 先判"有没有内置"：没有就直接返回，不去开 assets 里的文件。
            //   这是本轮改动的核心 —— 没内置 = 正常状态，不是失败。
            if (!bundledInAssets(am)) {
                lastReason = REASON_NOT_BUNDLED;
                Log.i(TAG, "语音模型未内置（可选资源），需要用户下载后才能离线识别");
                return false;
            }

            // VAD 很小（643KB），每次启动都校验一次，缺失就补
            if (!isVadInstalled(ctx)) {
                try {
                    copyAsset(am, ASSET_DIR + "/" + VAD_NAME, vadFile(ctx));
                    Log.i(TAG, "VAD 模型已释放");
                } catch (Exception e) {
                    Log.w(TAG, "VAD 释放失败（不影响识别）：" + e);
                }
            }

            File mark = new File(d, MARK);
            if (mark.isFile() && isInstalled(ctx)) return true;

            // tokens.txt 很小，直接写
            copyAsset(am, ASSET_DIR + "/" + TOKENS_NAME, tokensFile(ctx));
            // 大模型：先写 .tmp 再改名，避免半截文件被当成可用
            File tmp = new File(d, MODEL_NAME + ".tmp");
            copyAsset(am, ASSET_DIR + "/" + MODEL_NAME, tmp);
            File dst = modelFile(ctx);
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                copyFile(tmp, dst);
                tmp.delete();
            }
            if (!isInstalled(ctx)) {
                failMsg = "模型释放不完整";
                lastReason = REASON_IO;
                return false;
            }
            FileOutputStream fos = new FileOutputStream(mark);
            fos.write("ok".getBytes());
            fos.close();
            Log.i(TAG, "模型已释放 -> " + d.getAbsolutePath()
                    + " (" + (modelFile(ctx).length() / 1048576) + "MB)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "释放模型失败: " + e);
            failMsg = "释放语音模型失败：" + e.getMessage();
            lastReason = REASON_IO;
            return false;
        }
    }

    /**
     * assets 里是否内置了语音模型。
     *
     * <p>用 {@code open()} 探测而不是列目录：Android 的
     * {@code AssetManager.list()} 在 APK 压缩方式不同的情况下
     * 对子目录的表现不一致，而"能不能真正打开"才是我们要的语义。
     *
     * <p>只探**大模型**：它是最确定的风向标（tokens/vad 可能因为历史版本
     * 残留而存在），且它就是体积问题的本体。
     */
    private static boolean bundledInAssets(AssetManager am) {
        InputStream is = null;
        try {
            is = am.open(ASSET_DIR + "/" + MODEL_NAME);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignore) {}
        }
    }

    /** 大模型最小可信体积（字节）：sensevoice.int8.onnx 约 228MB，低于此值必是坏文件。 */
    public static final long MODEL_MIN_BYTES = 200L * 1024 * 1024;

    /**
     * 从用户选定的文件导入大模型（设置页「从文件导入」用）。
     *
     * <h3>★ 为什么需要它：大模型不再随包内置</h3>
     * 228MB 的 sensevoice.int8.onnx 作为独立文件随交付 zip 分发，
     * 用户在设置页用系统文件选择器挑中它，这里负责拷贝 + 校验 + 落盘。
     * tokens.txt / silero_vad.onnx 随包恒内置，这里顺带从 assets 补齐。
     *
     * <p>校验规则与 {@link #isInstalled} 同源（&gt;200MB），
     * 拷贝走 .tmp + renameTo 原子落盘 —— 半截文件永远不会被当成可用。
     *
     * @param c 上下文
     * @param in 用户选定文件的输入流（调用方负责打开与关闭）
     * @return null 表示成功；否则是给用户看的失败原因
     */
    public static synchronized String importModelFile(Context c, java.io.InputStream in) {
        final Context ctx = c.getApplicationContext();
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) {
            return "无法创建模型目录";
        }
        File tmp = new File(d, MODEL_NAME + ".tmp");
        java.io.OutputStream os = null;
        try {
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[1 << 16];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            os.flush();
            try { os.close(); } catch (Exception ignore) {}
            os = null;
            if (total < MODEL_MIN_BYTES) {
                tmp.delete();
                return "文件太小（" + (total / 1048576) + "MB），不是有效的语音模型"
                        + "（应大于 200MB）。请确认选的是交付包里的 sensevoice.int8.onnx。";
            }
            File dst = modelFile(ctx);
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                copyFile(tmp, dst);
                tmp.delete();
            }
            // 补齐小文件（随包恒内置；历史版本残留缺失时也顺带修复）
            AssetManager am = ctx.getAssets();
            try {
                if (!tokensFile(ctx).isFile() || tokensFile(ctx).length() < 1000) {
                    copyAsset(am, ASSET_DIR + "/" + TOKENS_NAME, tokensFile(ctx));
                }
            } catch (Exception e) {
                Log.w(TAG, "tokens 补齐失败：" + e);
            }
            try {
                if (!isVadInstalled(ctx)) {
                    copyAsset(am, ASSET_DIR + "/" + VAD_NAME, vadFile(ctx));
                }
            } catch (Exception e) {
                Log.w(TAG, "VAD 补齐失败（不影响识别）：" + e);
            }
            if (!isInstalled(ctx)) {
                lastReason = REASON_IO;
                failMsg = "模型导入后校验未通过";
                return failMsg;
            }
            File mark = new File(d, MARK);
            try {
                FileOutputStream fos = new FileOutputStream(mark);
                fos.write("ok".getBytes());
                fos.close();
            } catch (Exception ignore) {}
            lastReason = null;
            failMsg = null;
            Log.i(TAG, "模型已从文件导入 -> " + d.getAbsolutePath()
                    + " (" + (modelFile(ctx).length() / 1048576) + "MB)");
            return null;
        } catch (Exception e) {
            try { if (tmp.exists()) tmp.delete(); } catch (Exception ignore) {}
            Log.e(TAG, "从文件导入模型失败: " + e);
            lastReason = REASON_IO;
            failMsg = "导入语音模型失败：" + e.getMessage();
            return failMsg;
        } finally {
            try { if (os != null) os.close(); } catch (Exception ignore) {}
        }
    }

    private static void copyAsset(AssetManager am, String name, File dst) throws Exception {
        InputStream is = am.open(name);
        OutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { os.flush(); } catch (Exception ignore) {}
            try { os.close(); } catch (Exception ignore) {}
            try { is.close(); } catch (Exception ignore) {}
        }
    }

    private static void copyFile(File src, File dst) throws Exception {
        InputStream is = new java.io.FileInputStream(src);
        OutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        try {
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            try { os.close(); } catch (Exception ignore) {}
            try { is.close(); } catch (Exception ignore) {}
        }
    }

    // ------------------------------------------------------------- 加载 / 识别

    /**
     * 已占用的磁盘大小（字节）—— 三个模型文件之和。
     *
     * <p>给设置页显示"当前占用 xx MB"用。用户要求能选择性删除资源，
     * 那就必须让他**看得见**自己删掉多少、还剩多少。
     */
    public static long installedBytes(Context c) {
        try {
            Context ctx = c.getApplicationContext();
            long n = 0;
            File[] fs = {modelFile(ctx), tokensFile(ctx), vadFile(ctx)};
            for (File f : fs) {
                if (f != null && f.isFile()) n += f.length();
            }
            return n;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 删除已下载/已释放的语音模型，释放磁盘空间。
     *
     * <h3>★ 只删「听」的模型，绝不碰「说」的能力</h3>
     * 用户要求「把语音输入改为选择性下载资源（**不能影响宠物语音说话功能**）」。
     * 说话走 {@link TtsEngine}（**云端**合成），与这里的本地 ASR 模型
     * 完全无关 —— 所以删掉这些**不会**影响说话。
     * 这条约束由 {@code verify_voice_split.py} 的断言守着。
     *
     * <p>同时把已加载的识别器引用清掉：文件删了但内存里还留着
     * 旧的识别器，会让"删了却还能用"这种状态出现，反而混乱。
     * 下次要用时会走 {@link #installModel} 重新拿。
     *
     * @return true 表示删除后确实不再占用（含"本来就没有"的情况）
     */
    public static synchronized boolean deleteModel(Context c) {
        Context ctx = c.getApplicationContext();
        long before = installedBytes(ctx);   // ★ 删之前先量，日志里才能说"释放了多少"
        boolean ok = true;
        File[] fs = {modelFile(ctx), tokensFile(ctx), vadFile(ctx),
                     new File(dir(ctx), MODEL_NAME + ".tmp"),
                     new File(dir(ctx), MARK)};
        for (File f : fs) {
            try {
                if (f != null && f.exists() && !f.delete()) ok = false;
            } catch (Throwable t) {
                ok = false;
            }
        }
        // 内存里的识别器一并释放，避免"删了还能用"的混乱状态
        releaseLoaded();
        Log.i(TAG, "语音模型已删除，释放出 " + (before / 1048576) + "MB");
        return ok;
    }

    /** 释放常驻识别器/VAD，并允许下次重新加载 */
    private static synchronized void releaseLoaded() {
        try {
            if (rec != null) {
                rec.release();
            }
        } catch (Throwable ignore) {
        }
        rec = null;
        try {
            if (vad != null) {
                vad.release();
            }
        } catch (Throwable ignore) {
        }
        vad = null;
        // 复位失败标记：模型被删掉后，"之前加载失败"不该永久粘住 ——
        // 用户重新拿到模型后应该能重试。
        failed = false;
        vadFailed = false;
        failMsg = null;
    }

    /** 加载引擎（幂等）。失败后不再重试，返回 false。 */
    public static synchronized boolean ensure(Context c) {
        if (rec != null) return true;
        if (failed) return false;
        final Context ctx = c.getApplicationContext();
        if (!isInstalled(ctx)) {
            failMsg = "语音模型尚未释放";
            return false;
        }
        try {
            long t0 = System.currentTimeMillis();
            com.k2fsa.sherpa.onnx.OfflineModelConfig mc =
                    new com.k2fsa.sherpa.onnx.OfflineModelConfig();
            mc.setNumThreads(4);
            mc.setDebug(false);
            mc.setTokens(tokensFile(ctx).getAbsolutePath());
            mc.setSenseVoice(new com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                    modelFile(ctx).getAbsolutePath(), "auto", true));
            com.k2fsa.sherpa.onnx.OfflineRecognizerConfig cfg =
                    new com.k2fsa.sherpa.onnx.OfflineRecognizerConfig();
            cfg.setModelConfig(mc);
            rec = new com.k2fsa.sherpa.onnx.OfflineRecognizer(null, cfg);
            Log.i(TAG, "引擎加载完成 " + (System.currentTimeMillis() - t0) + "ms");
            ensureVad(ctx);   // 顺带加载 VAD（643KB，几十毫秒）
            return true;
        } catch (Throwable e) {
            failed = true;
            failMsg = "离线识别引擎加载失败：" + e;
            Log.e(TAG, failMsg, e);
            return false;
        }
    }

    /** 加载 Silero VAD（幂等；失败只记日志，断句自动退回能量判定）。 */
    public static synchronized boolean ensureVad(Context c) {
        if (vad != null) return true;
        if (vadFailed) return false;
        final Context ctx = c.getApplicationContext();
        if (!isVadInstalled(ctx)) { vadFailed = true; return false; }
        try {
            long t0 = System.currentTimeMillis();
            com.k2fsa.sherpa.onnx.SileroVadModelConfig sc =
                    new com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                            vadFile(ctx).getAbsolutePath(),
                            0.5f,   // threshold
                            0.6f,   // minSilenceDuration
                            0.25f,  // minSpeechDuration
                            512,    // windowSize：16kHz 下 Silero 要求 512 采样（32ms）
                            20.0f); // maxSpeechDuration
            com.k2fsa.sherpa.onnx.TenVadModelConfig tc =
                    new com.k2fsa.sherpa.onnx.TenVadModelConfig("", 0.5f, 0.5f, 0.25f, 256, 20.0f);
            com.k2fsa.sherpa.onnx.VadModelConfig vc =
                    new com.k2fsa.sherpa.onnx.VadModelConfig(sc, tc, 16000, 1, "cpu", false);
            vad = new com.k2fsa.sherpa.onnx.Vad(null, vc);
            Log.i(TAG, "VAD 加载完成 " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        } catch (Throwable e) {
            vadFailed = true;
            Log.w(TAG, "VAD 加载失败（断句将退回能量判定）：" + e);
            return false;
        }
    }

    /**
     * 对一帧 16kHz 单声道 PCM（长度须为 512）给出「像人声」的概率 0~1。
     * VAD 未就绪时返回 -1，调用方应退回能量判定。
     */
    public static float vadProbability(float[] frame512) {
        try {
            if (vad == null) return -1f;
            return vad.compute(frame512);
        } catch (Throwable e) {
            Log.w(TAG, "vad.compute 异常: " + e);
            return -1f;
        }
    }

    /** 重置 VAD 内部状态（每次开始新一段录音前调用）。 */
    public static void resetVad() {
        try { if (vad != null) vad.reset(); } catch (Throwable ignore) {}
    }

    /** 加载引擎；模型不存在时先释放。请在后台线程调用。 */
    public static boolean ensureWithInstall(Context c) {
        final Context ctx = c.getApplicationContext();
        if (!isInstalled(ctx) || !isVadInstalled(ctx)) installModel(ctx);
        return ensure(ctx);
    }

    /**
     * 给用户看的**一句状态说明**（设置页显示 / 语音输入报错时用）。
     *
     * <h3>★ 为什么要有它：「没内置」和「出错」必须说不同的话</h3>
     * 模型现在不再随 APK 内置（可选资源）。此时：
     * <ul>
     *   <li>「没内置」= **正常状态**，用户只是还没下载 →
     *       应该说「语音输入需先下载资源」，而不是弹一个像崩溃的报错</li>
     *   <li>IO 失败 = 真的出问题了 → 要说「释放失败」</li>
     * </ul>
     * 一律报"失败"会让正常用户以为 App 坏了。
     *
     * @return 人类可读的一句话；模型已就绪时返回"可用"
     */
    public static String statusText(Context c) {
        Context ctx = c.getApplicationContext();
        try {
            if (isInstalled(ctx)) {
                return "语音输入可用（已占用 "
                        + (installedBytes(ctx) / 1048576) + " MB）";
            }
        } catch (Throwable ignore) {}
        if (REASON_IO.equals(lastReason)) {
            return "语音模型释放失败" + (failMsg == null ? "" : "：" + failMsg);
        }
        // 没内置 / 还没下载 —— 都属于"需要先获取资源"
        return "语音输入资源未安装（约 229 MB）";
    }

    /** 模型是否可用（供 UI 决定要不要提示下载） */
    public static boolean isUsable(Context c) {
        try {
            return isInstalled(c.getApplicationContext());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 识别一段 16kHz 单声道归一化 float PCM。
     * @return 识别文字（可能为空串）；失败返回 null。
     */
    public static synchronized String recognize(float[] samples, int sampleRate) {
        if (rec == null) return null;
        com.k2fsa.sherpa.onnx.OfflineStream s = null;
        try {
            s = rec.createStream();
            s.acceptWaveform(samples, sampleRate);
            rec.decode(s);
            com.k2fsa.sherpa.onnx.OfflineRecognizerResult r = rec.getResult(s);
            String t = r == null ? null : r.getText();
            return t == null ? "" : t.trim();
        } catch (Throwable e) {
            Log.e(TAG, "识别失败: " + e);
            return null;
        } finally {
            try { if (s != null) s.release(); } catch (Throwable ignore) {}
        }
    }

    public static String lastError() { return failMsg; }

    /** 释放引擎内存（一般不需要）。 */
    public static synchronized void release() {
        try { if (rec != null) rec.release(); } catch (Throwable ignore) {}
        rec = null;
        try { if (vad != null) vad.release(); } catch (Throwable ignore) {}
        vad = null;
        vadFailed = false;
    }
}
