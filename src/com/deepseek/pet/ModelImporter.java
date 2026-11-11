package com.deepseek.pet;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把用户从文件选择器挑中的 {@code .gguf} **流式**拷进 {@link ModelStore#dir}（轮次62 · 第二轮 · 导入层）。
 *
 * <h3>★★ 为什么必须在这里就"流式 + 后台线程"</h3>
 * 小参数模型也有 **0.4~2 GB**，而手机只有几 GB 内存、桌宠还常驻着。
 * 三件事一件都不能省：
 * <ol>
 *   <li><b>流式</b>：{@code read(buf)} 一块一块走，任何"一次读进 byte[]"
 *       （{@code readAllBytes} / {@code toByteArray} / {@code ByteArrayOutputStream}）
 *       都等于把整个模型塞进堆里 ⇒ 必 OOM，而且是在**用户已经等了半分钟**之后才 OOM。
 *       判据里有一条**静态断言**专门禁这三种写法；</li>
 *   <li><b>后台线程</b>：拷贝是几百 MB 的顺序 IO，放主线程就是"界面冻住几分钟"，
 *       系统还可能直接弹 ANR；这里自己开线程（界面只管把进度画出来）；</li>
 *   <li><b>可取消</b>：用户挑错了文件要能停手。取消 = 置一个 volatile 标志，
 *       拷贝循环每一块都看它一眼 ⇒ 下一块（毫秒级）就收工，并**立刻删掉半拉文件**。</li>
 * </ol>
 *
 * <h3>★★ 三处"静默坏法"在这里被堵住</h3>
 * <ol>
 *   <li><b>先写 {@code .part}、成功才改名</b> —— 中途失败/取消/进程被杀都不会留下
 *       一个"看起来正常"的残模型；</li>
 *   <li><b>拷之前先验 magic</b> —— 不是 GGUF 的文件（zip / mp4 / 改了名的图）
 *       在**开头**就被拒，不会先花十分钟拷完再报错；</li>
 *   <li><b>拷完对账</b> —— {@code OpenableColumns.SIZE} 给得出大小就对一遍，
 *       少一个字节就算失败（截断的模型比没模型更难查：加载时报的是看不懂的错）。</li>
 * </ol>
 *
 * <h3>★ 调用纪律（与 {@link LocalLlm} 一样要记住）</h3>
 * {@link Listener} 的回调发生在**后台线程**上 —— 界面里改控件必须先
 * {@code runOnUiThread}。★ 回调里**不要**再调 {@link #start}（同一个模型目录，
 * 界面本来就是模态进度框，重入没有意义）。
 */
public final class ModelImporter {

    private static final String TAG = "DSPModel";

    /**
     * 单次读的块大小。
     *
     * <p>256 KB 是折中：再小（8 KB）在一块一块的顺序 IO 上多出几百次系统调用，
     * 1 GB 的拷明显变慢；再大（8 MB）在低端机上一次分配就容易触发 GC 抖动。
     */
    public static final int CHUNK = 256 * 1024;

    /** 进度回调的粒度：至少涨 1% 才回调一次（否则 1 GB 要回调几千次，界面反而卡）。 */
    private static final int REPORT_STEP_PCT = 1;

    /**
     * 大小未知时的进度回调粒度：至少又拷了这么多字节才回调一次。
     *
     * <p>★★ 这一条是"防界面被自己刷死"：大小未知时按**块**回调的话，
     * 一块 256 KB、1 GB 就是 4000 多次，而每次回调都要 `runOnUiThread` 去改
     * 进度框的文案（一次 TextView 重排 + 重绘）—— 实测量级是每秒两百次，
     * 界面会明显发顿。按 2 MB 报一次，1 GB 只有 500 次，肉眼看每秒只跳几次。
     */
    private static final long REPORT_STEP_BYTES = 2L * 1024 * 1024;

    private ModelImporter() {}

    /** 可以取消的导入任务。 */
    public static final class Job {
        private volatile boolean cancelled = false;
        /** 用户点了取消 ⇒ 拷贝循环下一块就收工（不会等整个文件拷完）。 */
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    /** 导入结果回调（★ 都在后台线程上）。 */
    public interface Listener {
        /**
         * @param percent 0~100；大小未知时给 -1（界面显示不确定进度）
         * @param copied  已拷字节
         * @param total   总字节；未知 = -1
         */
        void onProgress(int percent, long copied, long total);

        /**
         * @param ok      true = 已落盘、可直接用
         * @param name    净化后的文件名（失败时可能是 null）
         * @param file    落盘文件（失败时 null）
         * @param message 给用户看的一句话（成功/失败都非空 —— 失败时**必须**说清为什么）
         */
        void onDone(boolean ok, String name, File file, String message);
    }

    /**
     * 开始导入（**立刻返回**，活干在后台线程上）。
     *
     * @param ctx        任意 Context（内部只留 applicationContext）
     * @param src        文件选择器给的 content Uri
     * @param wantedName 期望的文件名（通常是 {@code OpenableColumns.DISPLAY_NAME}）
     * @param sizeHint    已知大小（未知传 -1）；用于进度与"拷完对账"
     */
    public static Job start(Context ctx, Uri src, String wantedName,
                            long sizeHint, final Listener lis) {
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();
        final Job job = new Job();
        // ★ 参数先绑成 final 局部量：匿名 Runnable 只能捕获 final（Java 8 的 effectively-final
        //   也可以，但写成显式 final 更不容易在后续编辑里被改坏）。
        final Uri fUri = src;
        final String fName = wantedName;
        final long fSize = sizeHint;
        final Runnable work = new Runnable() {
            @Override public void run() { copy(app, job, lis, fUri, fName, fSize); }
        };
        // ★★ 后台线程：拷贝是几百 MB 的顺序 IO，放主线程 = 界面冻住 + ANR。
        //   daemon：万一进程要退，不要被一个拷了一半的线程拖住。
        Thread t = new Thread(work, "dspllm-import");
        t.setDaemon(true);
        t.start();
        return job;
    }

    // ------------------------------------------------------------------ 真正的拷贝

    private static void copy(Context ctx, Job job, Listener lis,
                             Uri src, String wantedName, long sizeHint) {
        if (src == null) {
            done(lis, false, null, null, "没有拿到文件（选择器没返回内容）");
            return;
        }
        final String name = ModelFile.sanitizeName(wantedName);
        if (name == null) {
            done(lis, false, null, null, "这个文件名没法用（净化后为空）⇒ 已拒绝导入。");
            return;
        }

        // 开工前清掉上一次留下的半拉文件（同一时刻只会有一个导入在跑）
        ModelStore.cleanStaleParts(ctx);

        // ★★ 空间检查在**动手之前**：拷到 90% 才失败，用户白等十分钟
        final long free = ModelStore.freeBytes(ctx);
        if (sizeHint > 0 && !ModelFile.enoughSpace(free, sizeHint)) {
            done(lis, false, name, null, ModelFile.spaceHint(free, sizeHint));
            return;
        }

        final File dir = ModelStore.dir(ctx);
        final File part = new File(dir, ModelFile.partName(name));
        final File target = new File(dir, name);
        InputStream in = null;
        OutputStream out = null;
        long copied = 0;
        int lastPct = -1;
        long lastBytes = 0;      // 大小未知时"上次报过的字节数"（见 REPORT_STEP_BYTES）
        try {
            in = ctx.getContentResolver().openInputStream(src);
            if (in == null) {
                done(lis, false, name, null, "打不开这个文件（可能是云盘上没下载下来，或权限被收回）。");
                return;
            }
            out = new FileOutputStream(part);

            // ★ 定长缓冲：一块一块走。整块读进 byte[] 是**唯一**正确的读法（见类注释）。
            final byte[] buf = new byte[CHUNK];
            boolean first = true;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (job.isCancelled()) {
                    throw new CancelledException();
                }
                if (first) {
                    first = false;
                    // ★★ 第一块就判 magic：不是 GGUF 当场拒绝，
                    //    绝不让一个 zip/mp4 拷完十分钟再报错。
                    if (n < 4 || !ModelFile.isGguf(buf)) {
                        done(lis, false, name, null,
                                "这不是 GGUF 模型文件（文件头不是 “GGUF”，前 4 字节对不上）⇒ 已拒绝导入。");
                        return;
                    }
                }
                out.write(buf, 0, n);
                copied += n;
                if (sizeHint > 0) {
                    int pct = (int) (copied * 100 / sizeHint);
                    if (pct > 100) pct = 100;
                    if (pct - lastPct >= REPORT_STEP_PCT) {
                        lastPct = pct;
                        try { lis.onProgress(pct, copied, sizeHint); } catch (Throwable ignore) {}
                    }
                } else {
                    // 大小未知：按字节报，但**攒够 2 MB 才报一次**（见 REPORT_STEP_BYTES）
                    if (copied - lastBytes >= REPORT_STEP_BYTES) {
                        lastBytes = copied;
                        try { lis.onProgress(-1, copied, -1); } catch (Throwable ignore) {}
                    }
                }
            }
            if (job.isCancelled()) throw new CancelledException();

            // close 之后再改名：handle 没放就 rename，部分 ROM 上会失败
            try { out.close(); } catch (Throwable ignore) {}
            out = null;

            // ★★ 拷完对账：少一个字节就是坏文件，宁可报失败也别留下它
            if (sizeHint > 0 && copied != sizeHint) {
                deleteQuietly(part);
                done(lis, false, name, null,
                        "拷贝不完整：只拷到 " + ModelFile.humanSize(copied) + " / "
                                + ModelFile.humanSize(sizeHint) + "，已清理，请重新导入。");
                return;
            }
            if (copied < ModelFile.HEADER_MIN) {
                deleteQuietly(part);
                done(lis, false, name, null, "这个文件太小，不像一个模型（只有 "
                        + ModelFile.humanSize(copied) + "），已清理。");
                return;
            }

            // ★ 覆盖时先删旧的：先成功写完 .part 再动目标 ⇒ 失败也不会毁掉已有模型
            if (target.exists() && !target.delete()) {
                deleteQuietly(part);
                done(lis, false, name, null, "已有同名模型且删不掉（可能被占用），请先手动删除。");
                return;
            }
            if (!part.renameTo(target)) {
                deleteQuietly(part);
                done(lis, false, name, null, "落盘失败（改名不成），已清理半拉文件。");
                return;
            }
            Log.i(TAG, "导入完成 " + name + " " + ModelFile.humanSize(copied));
            try { lis.onProgress(100, copied, copied); } catch (Throwable ignore) {}
            done(lis, true, name, target, "已导入 " + name + " · " + ModelFile.humanSize(copied));
        } catch (CancelledException ce) {
            deleteQuietly(part);
            Log.i(TAG, "导入被取消，已清理半拉文件：" + part.getName());
            done(lis, false, name, null, "已取消导入，半拉文件已清理。");
        } catch (Throwable t) {
            // ★ 失败一定要清掉半拉文件：留着它既占空间，又会让用户以为"导入过了"
            deleteQuietly(part);
            Log.w(TAG, "导入失败：" + t);
            done(lis, false, name, null, "导入失败：" + shortReason(t) + "，半拉文件已清理。");
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 用户主动取消（用一个私有异常走"打断循环"这条路，避免到处 if-return）。 */
    private static final class CancelledException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static void done(Listener lis, boolean ok, String name, File f, String msg) {
        try {
            lis.onDone(ok, name, f, msg);
        } catch (Throwable ignore) {}
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignore) {}
    }

    private static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (Throwable ignore) {}
    }

    /** 把异常转成一句用户能懂的话（原来的 toString 里全是类名与内存地址）。 */
    private static String shortReason(Throwable t) {
        String m = t.getMessage();
        String cls = t.getClass().getSimpleName();
        if ("ENOSPC".equalsIgnoreCase(m) || (m != null && m.contains("ENOSPC"))) {
            return "存储空间写满了";
        }
        return (m == null || m.length() == 0) ? cls : cls + "：" + m;
    }

    // ------------------------------------------------------------------ 元信息（问系统）

    /** 文件选择器给的显示名；问不到 ⇒ null（调用方退化成"本地模型.gguf"）。 */
    public static String displayName(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String v = c.getString(i);
                    if (v != null && v.trim().length() > 0) return v.trim();
                }
            }
        } catch (Throwable ignore) {
        } finally {
            closeQuietly(c);
        }
        String last = uri == null ? null : uri.getLastPathSegment();
        return last == null ? null : last;
    }

    /** 文件大小；问不到 ⇒ -1（那就只做"不确定进度"，也不做对账）。 */
    public static long sizeOf(Context ctx, Uri uri) {
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Throwable ignore) {
        } finally {
            closeQuietly(c);
        }
        return -1;
    }
}
