package com.deepseek.pet;

import java.nio.charset.Charset;
import java.util.Locale;

/**
 * 本地 GGUF 模型的**纯函数**层（轮次62 · 第二轮 · 导入层）。
 *
 * <p>用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
 * 上一轮（第一轮）做完了引擎（llama.cpp + JNI + {@link LocalLlm}）；
 * 这一层只管**导入时那些"算一算"的事**：这到底是不是 GGUF？名字能不能落盘？多大？装得下吗？
 *
 * <h3>★★ 为什么单独抽一个类，而不是塞进导入代码里</h3>
 * 这个类**零 Android 依赖**（只用 {@code java.nio.charset} / {@code java.util.Locale}），
 * 所以 {@code build/fpstest/run.bat} 能用**光秃秃的 javac** 编它、并在
 * {@code FpsTest [33]} 里**真跑**一遍 —— 假名字、坏 magic、空间不够这些分支
 * 都能在电脑上真验，不必靠"看代码觉得对"。
 * ★ 一旦这里出现 {@code import android.*}，fpstest 当场编不过（那条断言是结构性的）。
 *
 * <h3>★ 为什么只读文件头就够</h3>
 * 手机只有几 GB 内存，模型动辄 0.4~2 GB ⇒ **绝不可能**把文件读进来判类型。
 * GGUF 的身份写在**前 4 个字节**（magic {@code "GGUF"}），所以
 * {@link #HEAD_PEEK} 这么多字节（64 KB）既够判类型、也够读几个头部字段，
 * 而且和文件多大**完全无关**。
 *
 * <h3>★ 这一层守的三个"静默坏法"</h3>
 * <ol>
 *   <li><b>不是 GGUF 也照收</b> —— 用户随手点了个 zip/mp4，拷贝一路绿灯，
 *       等到真机上 {@code llama_load_model_from_file} 才失败，而那时用户
 *       早就以为"导入成功了"。⇒ {@link #isGguf} 是**硬门槛**，不合格当场拒绝。</li>
 *   <li><b>文件名没净化</b> —— SAF 给的名字理论上可控，带 {@code /} 或 {@code ..}
 *       就可能写到目录外面去。⇒ {@link #sanitizeName} 只取最后一段、
 *       剔掉控制字符与非法字符、削掉前导点、限长。</li>
 *   <li><b>空间不够就开拷</b> —— 拷到 90% 才发现写不下去，用户白等十分钟。
 *       ⇒ {@link #enoughSpace} 在**动手之前**先算，还留了余量。</li>
 * </ol>
 */
public final class ModelFile {

    private ModelFile() {}

    /** GGUF 的文件头 magic（前 4 字节，ASCII）。 */
    public static final String MAGIC = "GGUF";

    /** 模型文件的扩展名（★ 只是**给人看的**，真判据是 {@link #isGguf}）。 */
    public static final String EXT = ".gguf";

    /** 落盘目录名（相对 {@code filesDir}）—— 见 {@link ModelStore}。 */
    public static final String DIR = "models";

    /**
     * 半拉文件的后缀。
     *
     * <p>先写 {@code xxx.gguf.part}、**成功后才改名**成 {@code xxx.gguf}：
     * 这样"拷到一半失败/被取消/进程被杀"都不会留下一个看起来像正常模型的残file
     * （那种残file 会被当成"导入成功了"，直到加载时才炸）。
     */
    public static final String PART = ".part";

    /**
     * 文件名主体（不含扩展名）的**字符**数上限。
     *
     * <p>★ 按字符而不是字节卡：Android 的文件系统上限是 **255 字节**，
     * 而一个汉字在 UTF-8 里是 3 字节 ⇒ 64 字符最多 192 字节，
     * 加 {@code ".gguf"}（5 字节）最多 197 字节，**稳稳在限内**。
     * 按 255 字符卡就会在中文名上踩到 {@code FileNotFoundException: ENAMETOOLONG}。
     */
    public static final int MAX_BASE_CHARS = 64;

    /** 拷完之后要留的余量下限（空间检查用）。 */
    public static final long RESERVE_MIN = 64L * 1024 * 1024;

    /** GGUF 头的定长部分：magic(4) + version(4) + tensorCount(8) + kvCount(8)。 */
    public static final int HEADER_MIN = 24;

    /** 判类型/读头时最多看这么多字节（★ 与文件大小无关，绝不整读）。 */
    public static final int HEAD_PEEK = 64 * 1024;

    /** 文件名里不许出现的字符（Android 上这些要么非法、要么会让文件"消失"）。 */
    private static final String ILLEGAL = "/\\:*?\"<>|";

    /** 头部字段里最长的那个串最多接受多少字节（防一个坏头把内存吃掉）。 */
    private static final int MAX_STR = 64 * 1024;
    /** 头部最多扫多少条 KV（{@code general.name} 通常在第 2 条，扫不到就放弃）。 */
    private static final int MAX_KV_SCAN = 64;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // ------------------------------------------------------------------ 一、GGUF 身份

    /**
     * 这 4 个字节是不是 GGUF 的 magic。
     *
     * <p>★★ 这是**唯一的**类型判据 —— 不看扩展名、不看 MIME（手机上的
     * {@code .gguf} 大多被报成 {@code application/octet-stream}，而用户
     * 改过名的文件照样是合法 GGUF）。{@code head} 只要求前 4 字节有效。
     */
    public static boolean isGguf(byte[] head) {
        return head != null && head.length >= 4
                && head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F';
    }

    /** GGUF 格式版本（小端 uint32，在偏移 4）；不是 GGUF / 头太短 ⇒ -1。 */
    public static int ggufVersion(byte[] head) {
        if (!isGguf(head) || head.length < HEADER_MIN) return -1;
        long v = leU32(head, 4);
        if (v < 0 || v > Integer.MAX_VALUE) return -1;
        return (int) v;
    }

    /** 张量个数（小端 uint64，在偏移 8）；读不到 ⇒ -1。 */
    public static long ggufTensorCount(byte[] head) {
        if (!isGguf(head) || head.length < HEADER_MIN) return -1;
        return leU64(head, 8);
    }

    /** 元数据 KV 条数（小端 uint64，在偏移 16）；读不到 ⇒ -1。 */
    public static long ggufKvCount(byte[] head) {
        if (!isGguf(head) || head.length < HEADER_MIN) return -1;
        return leU64(head, 16);
    }

    /**
     * 从头部元数据里找 {@code general.name}（模型自己报的名字，如
     * {@code Qwen2.5-0.5B-Instruct}）。
     *
     * <p>★ 只在**已有的这点头部缓冲**里走：一旦遇到读不动的类型（数组/未知类型）
     * 或越界就立刻收工返回 null。宁可显示文件名，也**不去读文件后面的部分**
     * （那正是"把 1 GB 读进内存"的入口）。
     *
     * @return 模型名；没找到/不是 GGUF/头部不完整 ⇒ null
     */
    public static String ggufName(byte[] head) {
        if (!isGguf(head) || head.length < HEADER_MIN) return null;
        final long kv = ggufKvCount(head);
        if (kv <= 0) return null;
        int p = HEADER_MIN;
        final long scan = kv < MAX_KV_SCAN ? kv : MAX_KV_SCAN;
        for (long i = 0; i < scan; i++) {
            long klen = leU64(head, p);
            p += 8;
            if (klen <= 0 || klen > MAX_STR || p + klen > head.length) return null;
            String key = new String(head, p, (int) klen, UTF8);
            p += (int) klen;
            long vt = leU32(head, p);
            p += 4;
            if (vt < 0) return null;
            if (vt == 8) {                                  // STRING
                long vlen = leU64(head, p);
                p += 8;
                if (vlen < 0 || vlen > MAX_STR || p + vlen > head.length) return null;
                if ("general.name".equals(key)) {
                    String v = new String(head, p, (int) vlen, UTF8).trim();
                    return v.length() == 0 ? null : v;
                }
                p += (int) vlen;
            } else {
                int sz = scalarSize((int) vt);
                if (sz < 0) return null;                    // 数组/未知 ⇒ 后面走不动了
                p += sz;
            }
            if (p > head.length) return null;
        }
        return null;
    }

    /**
     * ★★★ 轮次74：从 GGUF 头部元数据里读**层数**（{@code <arch>.block_count}）。
     *
     * <p>为什么需要它：GPU 加速要动态算 {@code n_gpu_layers}（放几层到 GPU），
     * 而"层数"只有模型自己知道。llama.cpp 要**载入之后**才给得出层数，
     * 但 {@code n_gpu_layers} 必须在**载入之前**就定下来 ⇒ 只能读 GGUF 头。
     * ★ 只读**前 {@link #HEAD_PEEK} 字节**（64 KB），绝不把 1 GB 读进内存。
     *
     * <p>★ 读不到（头部被截断 / KV 里遇到数组 / 不是 GGUF）⇒ 返回 **0**，
     * 调用方必须把 0 当"不知道"，用估算值，**不许**当"零层"。
     *
     * @return 层数（1~1024）；不知道 ⇒ 0
     */
    public static int ggufLayerCount(byte[] head) {
        if (!isGguf(head) || head.length < HEADER_MIN) return 0;
        final long kv = ggufKvCount(head);
        if (kv <= 0) return 0;
        int p = HEADER_MIN;
        final long scan = kv < MAX_KV_SCAN ? kv : MAX_KV_SCAN;
        for (long i = 0; i < scan; i++) {
            long klen = leU64(head, p);
            p += 8;
            if (klen <= 0 || klen > MAX_STR || p + klen > head.length) return 0;
            final String key = new String(head, p, (int) klen, UTF8);
            p += (int) klen;
            long vt = leU32(head, p);
            p += 4;
            if (vt < 0) return 0;
            if (vt == 8) {                                  // STRING：跳过
                long vlen = leU64(head, p);
                p += 8;
                if (vlen < 0 || vlen > MAX_STR || p + vlen > head.length) return 0;
                p += (int) vlen;
            } else if (vt == 9) {                           // ARRAY：跳不过去 ⇒ 收工
                return 0;
            } else {
                final int sz = scalarSize((int) vt);
                if (sz < 0) return 0;
                if (key.endsWith(".block_count") || "block_count".equals(key)) {
                    final long v = (sz == 8) ? leU64(head, p) : leU32(head, p);
                    return (v > 0 && v <= 1024) ? (int) v : 0;
                }
                p += sz;
            }
            if (p > head.length) return 0;
        }
        return 0;
    }

    /** 同上，但直接从文件读前 {@link #HEAD_PEEK} 字节（**不整读文件**）。读不到 ⇒ 0。 */
    public static int ggufLayerCount(java.io.File f) {
        if (f == null || !f.isFile()) return 0;
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            final byte[] buf = new byte[HEAD_PEEK];
            int n = 0;
            while (n < buf.length) {
                final int r = in.read(buf, n, buf.length - n);
                if (r <= 0) break;
                n += r;
            }
            if (n <= 0) return 0;
            return ggufLayerCount(n == buf.length ? buf : java.util.Arrays.copyOf(buf, n));
        } catch (Throwable t) {
            return 0;
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignore) {} }
        }
    }

    // ------------------------------------------------------------------ 二、文件名净化
    /**
     * 把 SAF 给的名字净化成一个**能安全落盘**的名字。
     *
     * <p>规则（每一条都对应一个真实的坑）：
     * <ol>
     *   <li>只取**最后一段路径** —— 带 {@code /} 或 {@code \} 的名字可能指到目录外面；</li>
     *   <li>剔掉控制字符；非法字符（{@code : * ? " < > |}）换成 {@code _}；</li>
     *   <li>全是点（{@code .} / {@code ..}）或净化后为空 ⇒ <b>返回 null</b>
     *       （调用方必须拒绝，不能拿它当文件名）；</li>
     *   <li>削掉**前导点** —— 以点开头的文件在文件管理器里默认是隐藏的，
     *       用户会以为"导入没成功"；</li>
     *   <li>没有 {@code .gguf} 后缀就补上（★ magic 才是真判据，这里只是让
     *       文件在列表里一眼看得出是什么）；</li>
     *   <li>按 {@link #MAX_BASE_CHARS} 截主体、**保住扩展名**。</li>
     * </ol>
     *
     * @return 净化后的名字；无法净化 ⇒ null
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() == 0) return null;
        // ① 只取最后一段
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        if (cut >= 0) s = s.substring(cut + 1);
        // ② 剔控制字符 / 换非法字符
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            b.append(ILLEGAL.indexOf(c) >= 0 ? '_' : c);
        }
        s = b.toString().trim();
        // ③ 空 / 全是点 ⇒ 无效
        if (s.length() == 0 || isAllDots(s)) return null;
        // ④ 削前导点
        int i = 0;
        while (i < s.length() && s.charAt(i) == '.') i++;
        s = s.substring(i).trim();
        if (s.length() == 0) return null;
        // ⑤ 补扩展名
        if (!s.toLowerCase(Locale.US).endsWith(EXT)) s = s + EXT;
        // ⑥ 限长（保扩展名）
        if (s.length() > MAX_BASE_CHARS + EXT.length()) {
            s = s.substring(0, s.length() - EXT.length());
            s = s.substring(0, MAX_BASE_CHARS) + EXT;
        }
        return s;
    }

    /** 这个名字看起来是不是一个模型文件（按扩展名；★ 真判据仍是 magic）。 */
    public static boolean isModelName(String fileName) {
        if (fileName == null) return false;
        String n = fileName.trim();
        return n.length() > EXT.length() && n.toLowerCase(Locale.US).endsWith(EXT);
    }

    /** 是不是"半拉文件"（导入中途留下的）。 */
    public static boolean isPartName(String fileName) {
        return fileName != null
                && fileName.toLowerCase(Locale.US).endsWith(PART);
    }

    /** 半拉文件名（最终名 + {@link #PART}）。 */
    public static String partName(String finalName) {
        return finalName == null ? null : finalName + PART;
    }

    /** 两个名字是不是同一个（大小写不敏感 —— 文件系统上 {@code A.gguf} 与 {@code a.gguf} 可能是同一个）。 */
    public static boolean sameName(String a, String b) {
        if (a == null || b == null) return a == b;
        return a.equalsIgnoreCase(b);
    }

    // ------------------------------------------------------------------ 三、体积与空间

    /**
     * 人类可读的体积（{@code 398.5 MB}）。
     *
     * <p>★ 用 {@link Locale#US} 固定小数点 —— 默认 locale 在部分地区会把
     * {@code 398.5} 写成 {@code 398,5}，同一个读数在不同手机上长得不一样。
     */
    public static String humanSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return fmt(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024.0) return fmt(mb) + " MB";
        double gb = mb / 1024.0;
        if (gb < 1024.0) return fmt(gb) + " GB";
        return fmt(gb / 1024.0) + " TB";
    }

    /**
     * 拷这么大一个文件，**至少**要留多少可用空间。
     *
     * <p>= 文件本身 + 余量；余量取"文件的 10%"与 {@link #RESERVE_MIN} 里的大者。
     * ★ 为什么要余量：写盘期间系统自己也在写（日志/缓存/数据库），
     * 卡在最后几 MB 失败的话，前面几分钟全白等、还要再删一遍半拉文件。
     */
    public static long requiredFree(long needBytes) {
        if (needBytes <= 0) return RESERVE_MIN;
        long extra = needBytes / 10;
        if (extra < RESERVE_MIN) extra = RESERVE_MIN;
        long sum = needBytes + extra;
        return sum < 0 ? Long.MAX_VALUE : sum;              // 溢出兜底
    }

    /**
     * 剩余空间够不够（{@code free <= 0} 或 {@code need < 0} 这类脏输入一律判"不够"，
     * 宁可不导入也不能开到一半失败）。
     *
     * @param freeBytes 目标卷的**可用**空间（Android 上是
     *                  {@code File.getUsableSpace()}，不是 {@code getFreeSpace()}）
     * @param needBytes 要写的字节数；{@code <= 0}（大小未知）⇒ 只要求留 {@link #RESERVE_MIN}
     */
    public static boolean enoughSpace(long freeBytes, long needBytes) {
        if (freeBytes <= 0) return false;
        return freeBytes >= requiredFree(needBytes);
    }

    /** 空间不足时给用户看的一句话（把三个数都摆出来，别让他猜）。 */
    public static String spaceHint(long freeBytes, long needBytes) {
        return "空间不够：这个文件 " + humanSize(needBytes) + "，"
                + "导入后还要留 " + humanSize(requiredFree(needBytes) - Math.max(needBytes, 0))
                + " 余量，一共需要 " + humanSize(requiredFree(needBytes))
                + "；当前只剩 " + humanSize(freeBytes) + "。";
    }

    // ------------------------------------------------------------------ 四、给设置页那一行

    /**
     * ★ 建议阈值：模型体积超过这一档就值得提醒（≈0.5B Q4_K_M 那一档，约 0.4 GB）。
     *
     * <p>为什么以"0.5B 那档"为界而不是拿百分比算：真机读数给的是一条**断层** ——
     * 1.5B（1.0 GB）decode 只有 0.15 tok/s（≈6.5 秒/字），
     * 而这类两三百 MB 的小模型是本功能一开始就推荐给手机的体积。
     * 拿"占内存百分比"当判据反而会把 1.5B 判成"没问题"（1 GB 只占 8 GB 的 13%）。
     */
    public static final long ADVISE_MAX_BYTES = 600L * 1024 * 1024;

    /**
     * ★★★ 轮次67：**模型放不放得进内存**的建议（decode 瓶颈的真实原因是"取权重"）。
     *
     * <p>真机实测（小米 2210132C / 骁龙 8 Gen 2 / Qwen2.5-1.5B-Instruct Q4_K_M 1.06 GB）：
     * decode 稳定在 <b>约 6500 ms/token</b>，而且<b>与上下文长度无关</b>；
     * 换算过来单 token 只跑出 ~0.4 GFLOP/s（prefill 段是 ~40 GFLOP/s）
     * ⇒ 瓶颈不在"算"，在"<b>每个字都要把 1 GB 权重重新捞一遍</b>"：
     * 1.06 GB / 6.5 s ≈ <b>163 MB/s</b>，正好是"权重根本没常驻内存"的量级。
     *
     * <p>所以这一行要说的**不是**"模型越大越慢"这种废话，而是把三个数摆出来、
     * 告诉用户"你这个模型偏大，慢是因为它每个字都要从闪存重读"。
     *
     * <p>★ 纯函数（只吃三个数、不碰 Android API）—— 这样它能被 `javac` 真编真跑
     * （见 `build/verify_local_llm_speed.py` 里那一节）。
     *
     * @param modelBytes    当前模型的字节数；{@code <= 0} ⇒ 返回 {@code null}（没模型就别说）
     * @param totalRamBytes 本机总内存；{@code <= 0} ⇒ 不知道就不拿它做判据
     * @param availRamBytes 本机当前可用内存；{@code <= 0} ⇒ 不知道就不拿它做判据
     * @return 一行建议；**没有可说的就返回 {@code null}**（调用方据此不显示这一行）
     */
    public static String memoryAdvice(long modelBytes, long totalRamBytes, long availRamBytes) {
        if (modelBytes <= 0) return null;
        // 两条触发条件，任一成立就值得说：
        //   ① 模型超过 {@link #ADVISE_MAX_BYTES}（≈0.5B 那一档）—— 真机断层就在这附近
        //   ② 模型比**当前可用内存**还大 —— 这一条最直接：现在就已经放不进去了
        final boolean tooBig    = modelBytes > ADVISE_MAX_BYTES;
        final boolean noRoomNow = availRamBytes > 0 && modelBytes > availRamBytes;
        if (!tooBig && !noRoomNow) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("\u00B7 \uD83E\uDDE0 内存提示：模型 ").append(humanSize(modelBytes));
        if (totalRamBytes > 0) sb.append(" / 本机内存 ").append(humanSize(totalRamBytes));
        if (availRamBytes > 0) sb.append("（当前可用 ").append(humanSize(availRamBytes)).append("）");
        sb.append("。手机上的权重一旦放不进内存，每吐一个字都要从闪存重读一遍 —— "
                + "真机实测约 **6.5 秒/字**（1.5B Q4_K_M / 8 GB 机型）。");
        if (tooBig) {
            sb.append("0.5B 的 Q4_K_M 约 0.4 GB，能常驻内存、明显更快，建议先换它试试。");
        }
        if (noRoomNow) {
            sb.append("而且现在可用内存比模型还小 —— 建议先关掉别的 App 再对话。");
        }
        return sb.toString();
    }

    /**
     * 设置页入口那一行的值（「🤖 本地模型：**这里** ›」）。
     *
     * <p>{@code 未导入} / {@code 名字 · 398.5 MB} / 多个时再缀一个总数。
     */
    public static String summary(String currentName, long bytes, int count) {
        if (currentName == null || currentName.trim().length() == 0) return "未导入";
        String s = currentName.trim() + " · " + humanSize(bytes);
        if (count > 1) s = s + "（共 " + count + " 个）";
        return s;
    }

    // ------------------------------------------------------------------ 内部小工具

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static boolean isAllDots(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != '.') return false;
        return s.length() > 0;
    }

    private static long leU32(byte[] b, int off) {
        if (b == null || off < 0 || off + 4 > b.length) return -1;
        return ((long) (b[off] & 0xFF))
                | ((long) (b[off + 1] & 0xFF) << 8)
                | ((long) (b[off + 2] & 0xFF) << 16)
                | ((long) (b[off + 3] & 0xFF) << 24);
    }

    private static long leU64(byte[] b, int off) {
        if (b == null || off < 0 || off + 8 > b.length) return -1;
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }

    /** GGUF 值类型里"定长标量"占几字节；数组(9)/未知 ⇒ -1（表示"读不动了"）。 */
    private static int scalarSize(int type) {
        switch (type) {
            case 0: case 1: case 7: return 1;       // uint8 / int8 / bool
            case 2: case 3: return 2;               // uint16 / int16
            case 4: case 5: case 6: return 4;       // uint32 / int32 / float32
            case 10: case 11: case 12: return 8;    // uint64 / int64 / float64
            default: return -1;                     // 8=string 走单独分支，9=array
        }
    }
}
