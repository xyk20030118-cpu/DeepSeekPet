package com.deepseek.pet;

/**
 * 系统监测指标 —— **解析 + 格式化**，零 Android 依赖。
 *
 * <h3>★ 为什么单独拆一个纯类</h3>
 * 和 {@link FpsMath} 同一个理由：解析与格式化最容易算错，而错了在界面上
 * 只表现为"数字看着不对"，分不清是取数问题还是算法问题。
 * 纯 Java 就能在桌面 JVM 上拿**真机抓下来的原始输出**跑断言
 * （见 {@code build/fpstest}），验的是打进 APK 的那份代码本身。
 *
 * <h3>八项指标与数据源（真机 Xiaomi 2210132C / Android 17 实测）</h3>
 * <table>
 *   <tr><td>CPU</td><td>{@code /proc/stat} 两次差值（busy/total）</td></tr>
 *   <tr><td>GPU</td><td>{@code /sys/class/kgsl/kgsl-3d0/gpubusy} → "busy total" 两次差值</td></tr>
 *   <tr><td>RAM</td><td>{@code ActivityManager.MemoryInfo}（Java 侧取，不走 shell）</td></tr>
 *   <tr><td>FPS</td><td>SurfaceFlinger timestats（见 {@link GameFpsMonitor}）</td></tr>
 *   <tr><td>温度</td><td>thermal_zone 里 CPU 相关区取最高（真机有 cpuss-* / cpu-0-* / cpu-1-* / cpu_therm）</td></tr>
 *   <tr><td>电池</td><td>粘性 {@code ACTION_BATTERY_CHANGED} 的 level/scale</td></tr>
 *   <tr><td>功耗</td><td>电压(mV) × 电流(µA) → mW（BatteryManager 属性 + 粘性广播）</td></tr>
 *   <tr><td>网络</td><td>{@code /proc/net/dev} 全部非 lo 网卡 rx+tx 字节两次差值</td></tr>
 * </table>
 *
 * <p>取不到一律用 {@code -1} 表示，界面显示 {@code --} —— **不猜、不沿用旧值**，
 * 否则用户看到的是一个"看起来正常其实是假的"数字（本工程踩过这个坑）。
 */
public final class SysMetrics {

    private SysMetrics() {}

    // ---- 指标 id（存进 prefs，**不能随便改字面量**：改了老用户的选择就丢了）----
    public static final String NONE  = "none";
    public static final String CPU   = "cpu";
    public static final String GPU   = "gpu";
    public static final String RAM   = "ram";
    public static final String FPS   = "fps";
    public static final String TEMP  = "temp";
    public static final String BATT  = "batt";
    public static final String POWER = "power";
    public static final String NET   = "net";

    /** 八项指标（设置页下拉里的顺序）。 */
    public static final String[] IDS = { CPU, GPU, RAM, FPS, TEMP, BATT, POWER, NET };

    /** id 是否合法（含 NONE）。 */
    public static boolean isValid(String id) {
        if (id == null) return false;
        if (NONE.equals(id)) return true;
        for (String s : IDS) if (s.equals(id)) return true;
        return false;
    }

    /** 设置页下拉里的中文名。 */
    public static String label(String id) {
        if (CPU.equals(id))   return "CPU 占用";
        if (GPU.equals(id))   return "GPU 占用";
        if (RAM.equals(id))   return "RAM 内存";
        if (FPS.equals(id))   return "实时帧率";
        if (TEMP.equals(id))  return "温度";
        if (BATT.equals(id))  return "电池电量";
        if (POWER.equals(id)) return "功耗";
        if (NET.equals(id))   return "网络速率";
        return "不显示";
    }

    /**
     * 一次采样的全部指标取值。
     *
     * <p>{@code -1} = 这次没取到（界面显示 {@code --}）。
     * ★ FPS 的 {@code -1} 有第二层含义：**画面静止**（没新帧）。
     * 所以界面上 FPS 为 -1 时显示 {@code 0} 而不是 {@code --} ——
     * 这与本功能一直以来的口径一致（"静止"是有效读数，不是故障）。
     */
    public static final class Snapshot {
        public float cpu = -1f;
        public float gpu = -1f;
        public float fps = -1f;
        public float temp = -1f;      // ℃
        public float batt = -1f;      // %
        public float power = -1f;     // W
        public float net = -1f;       // B/s
        public long ramUsedKb = -1L;
        public long ramTotalKb = -1L;

        public Snapshot copy() {
            Snapshot s = new Snapshot();
            s.cpu = cpu; s.gpu = gpu; s.fps = fps; s.temp = temp;
            s.batt = batt; s.power = power; s.net = net;
            s.ramUsedKb = ramUsedKb; s.ramTotalKb = ramTotalKb;
            return s;
        }
    }

    // ==================== 解析 ====================

    /**
     * 解析 {@code /proc/stat} 首行 → {@code {busy, total}}（单位 jiffies）。
     *
     * <pre>
     *   cpu  675137 37772 758160 3050873 9743 150463 16553 58 0 0
     *        user   nice system idle    iowait irq softirq steal ...
     * </pre>
     * busy = total − (idle + iowait)：iowait 不算忙（CPU 在等 IO，没干活）。
     */
    public static long[] parseProcStat(String raw) {
        if (raw == null) return null;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (!s.startsWith("cpu")) continue;
            // 只认 "cpu " 这一行（"cpu0"/"cpu1" 是分核，不能混进来）
            if (s.length() < 4 || s.charAt(3) != ' ') continue;
            String[] p = s.substring(4).trim().split("\\s+");
            if (p.length < 5) return null;
            long total = 0;
            long[] v = new long[p.length];
            for (int i = 0; i < p.length; i++) {
                v[i] = parseLongSafe(p[i], 0L);
                total += v[i];
            }
            long idle = v[3] + v[4];              // idle + iowait
            return new long[]{ total - idle, total };
        }
        return null;
    }

    /** 两次 {@link #parseProcStat} 之间的 CPU 占用率（%）；算不出返回 -1。 */
    public static float cpuPercent(long[] prev, long[] cur) {
        return busyPercent(prev, cur);
    }

    /**
     * 解析 {@code gpubusy} → {@code {busy, total}}。
     *
     * <p>真机输出形如 {@code "  18407 1020280"}（前导空格、两个数）。
     * ★ 实测空闲时会返回 {@code "0 0"} —— 那是"没数据"，不是"0% 占用"，
     * 所以下面的百分比函数在 total 不涨时返回 -1（界面显示 --）。
     */
    public static long[] parseGpuBusy(String raw) {
        if (raw == null) return null;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            String[] p = s.split("\\s+");
            if (p.length < 2) continue;
            long b = parseLongSafe(p[0], -1L);
            long t = parseLongSafe(p[1], -1L);
            if (b < 0 || t <= 0) return null;
            return new long[]{ b, t };
        }
        return null;
    }

    /** 两次 {@link #parseGpuBusy} 之间的 GPU 占用率（%）；算不出返回 -1。 */
    public static float gpuPercent(long[] prev, long[] cur) {
        return busyPercent(prev, cur);
    }

    /** busy/total 差值的百分比（CPU 与 GPU 共用）。 */
    private static float busyPercent(long[] prev, long[] cur) {
        if (prev == null || cur == null || prev.length < 2 || cur.length < 2) return -1f;
        long dt = cur[1] - prev[1];
        long db = cur[0] - prev[0];
        if (dt <= 0 || db < 0) return -1f;
        float pct = db * 100f / dt;
        if (pct < 0f) pct = 0f;
        if (pct > 100f) pct = 100f;
        return pct;
    }

    /**
     * 解析 {@code /proc/net/dev} → 全部**非 lo** 网卡的 rx+tx 字节总和。
     *
     * <pre>
     *  face |bytes    packets ... |bytes    packets ...
     *     lo: 17287475 30956 ...
     * rmnet_ipa0: 12345 6 ...
     * </pre>
     * 每行冒号前是网卡名，冒号后第 1 个字段是 rx bytes、第 9 个是 tx bytes。
     * 排除 lo（本机回环，不是"网络流量"）。
     */
    public static long parseNetBytes(String raw) {
        if (raw == null) return -1L;
        long sum = 0;
        boolean any = false;
        for (String line : raw.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;                       // 表头两行没有冒号
            String name = line.substring(0, c).trim();
            if (name.isEmpty() || "lo".equals(name)) continue;
            String[] p = line.substring(c + 1).trim().split("\\s+");
            if (p.length < 9) continue;
            long rx = parseLongSafe(p[0], -1L);
            long tx = parseLongSafe(p[8], -1L);
            if (rx < 0 || tx < 0) continue;
            sum += rx + tx;
            any = true;
        }
        return any ? sum : -1L;
    }

    /** 两次 {@link #parseNetBytes} 之间的速率（字节/秒）；算不出返回 -1。 */
    public static float netBytesPerSec(long prev, long cur, long dtMs) {
        if (prev < 0 || cur < 0 || dtMs <= 0) return -1f;
        long d = cur - prev;
        // 计数器回绕/网卡重连会变小 —— 那是无效窗口，不是"负流量"
        if (d < 0) return -1f;
        return d * 1000f / dtMs;
    }

    /**
     * 从 {@code thermal_zone} 的 "名字=毫摄氏度" 列表里挑一个代表温度（℃）。
     *
     * <p>★ 为什么不是简单取最高：真机有 40+ 个区，其中 mmw/bcl/epm 这些是
     * **未接传感器的占位**，值恒为 {@code -273000}（绝对零度）；还有 usb/charger
     * 这类充电口温度，跟"手机热不热"关系不大。实测该机 CPU 相关区名为
     * {@code cpuss-0..3 / cpu-0-* / cpu-1-* / cpu_therm}，取值 37~45℃，
     * 与游戏时的体感一致 —— 所以**优先 CPU 区取最高**，没有 CPU 区才退回
     * "所有合理值里的最高"。
     */
    public static float pickTempC(String raw) {
        if (raw == null) return -1f;
        float bestCpu = -1f, bestAny = -1f;
        for (String line : raw.split("\n")) {
            String s = line.trim();
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String name = s.substring(0, eq).trim();
            long mv = parseLongSafe(s.substring(eq + 1).trim(), Long.MIN_VALUE);
            if (mv == Long.MIN_VALUE) continue;
            float c = mv / 1000f;
            // 合理范围：-40℃ ~ 150℃（滤掉 -273 占位与明显的脏数据）
            if (c < -40f || c > 150f) continue;
            if (c > bestAny) bestAny = c;
            if (isCpuZone(name) && c > bestCpu) bestCpu = c;
        }
        if (bestCpu >= 0f) return bestCpu;
        return bestAny;
    }

    /** 区名是不是 CPU/SoC 相关（真机实测命名）。 */
    private static boolean isCpuZone(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("cpu") || n.startsWith("cpuss")
                || n.contains("cpu_therm") || n.startsWith("soc")
                || n.startsWith("ap_") || n.startsWith("tsens");
    }

    /**
     * 功耗（瓦）。{@code voltMv} 毫伏、{@code currUa} 微安（可为负 = 放电）。
     *
     * <p>P = U·I = (mV/1000) × (µA/1e6) 瓦 ⇒ <b>W = mV × |µA| / 1e9</b>。
     * 实测 4000mV / 500mA → 2W（手机游戏时 3~8W 是合理量级）。
     *
     * ★ 单位换算踩过一次：写成 {@code /1e6} 得到的是 **mW**（2000），
     *   而方法名与界面都按 W 显示，于是功耗会大 1000 倍（2kW）。
     *   mV×µA 是 1e-9 瓦，这个量级必须一次算对。
     */
    public static float powerW(int voltMv, int currUa) {
        if (voltMv <= 0 || currUa == 0) return -1f;
        long ua = currUa < 0 ? -currUa : currUa;
        return voltMv * (float) ua / 1e9f;
    }

    // ==================== 格式化 ====================

    /**
     * 展开态（侧栏，宽）用的文案：带指标名，如 {@code "CPU 42%"}。
     */
    public static String fullText(String id, Snapshot s) {
        if (s == null || id == null) return "--";
        if (NONE.equals(id)) return "";
        String v = valueText(id, s);
        String l = shortLabel(id);
        return v.equals("--") ? (l + " --") : (l + " " + v);
    }

    /**
     * 吸附态（竖条，窄格）用的文案：**只有数值**。
     *
     * <p>竖条宽度只有 30dp，放不下指标名；数值本身已经够表意
     * （用户自己选的三项，顺序固定）。
     */
    public static String shortText(String id, Snapshot s) {
        if (s == null || id == null || NONE.equals(id)) return "";
        return valueText(id, s);
    }

    /** 指标短名（展开态用）。 */
    public static String shortLabel(String id) {
        if (CPU.equals(id))   return "CPU";
        if (GPU.equals(id))   return "GPU";
        if (RAM.equals(id))   return "RAM";
        if (FPS.equals(id))   return "FPS";
        if (TEMP.equals(id))  return "温度";
        if (BATT.equals(id))  return "电池";
        if (POWER.equals(id)) return "功耗";
        if (NET.equals(id))   return "网络";
        return "";
    }

    /** 单项数值文案（不含指标名）。取不到 = {@code "--"}。 */
    public static String valueText(String id, Snapshot s) {
        if (s == null || id == null) return "--";
        if (CPU.equals(id))   return pct(s.cpu);
        if (GPU.equals(id))   return pct(s.gpu);
        if (RAM.equals(id))   return gb(s.ramUsedKb);
        if (FPS.equals(id))   return s.fps < 0f ? "0" : String.valueOf(Math.round(s.fps));
        if (TEMP.equals(id))  return s.temp < 0f ? "--" : (Math.round(s.temp) + "\u2103");
        if (BATT.equals(id))  return s.batt < 0f ? "--" : (Math.round(s.batt) + "%");
        if (POWER.equals(id)) {
            if (s.power < 0f) return "--";
            // ★ 括号必须写：`cond ? a : b + "W"` 里 + 的优先级高于三目，
            //   会变成 `cond ? a : (b + "W")` —— 前半支没有单位（写错过一次）。
            String n = s.power >= 10f ? String.valueOf(Math.round(s.power))
                    : String.format(java.util.Locale.US, "%.1f", s.power);
            return n + "W";
        }
        if (NET.equals(id))   return rate(s.net);
        return "";
    }

    private static String pct(float v) {
        return v < 0f ? "--" : (Math.round(v) + "%");
    }

    private static String gb(long kb) {
        if (kb < 0) return "--";
        float g = kb / 1048576f;
        return String.format(java.util.Locale.US, "%.1fG", g);
    }

    /** 速率：B/s → 1.2M / 340K / 12B（竖条窄格放不下 "/s"，用单位本身表意）。 */
    private static String rate(float bps) {
        if (bps < 0f) return "--";
        if (bps >= 1048576f) return String.format(java.util.Locale.US, "%.1fM", bps / 1048576f);
        if (bps >= 1024f)    return String.format(java.util.Locale.US, "%.0fK", bps / 1024f);
        return Math.round(bps) + "B";
    }

    // ==================== 侧栏选边（用户要求"取决于位置"的纯判据）====================
    //
    // ★ 为什么把这段几何算成**纯函数**：用户明确要求"展开桌宠时显示在桌宠左边或者
    //   右边（取决于位置）"。这段判据原先内联在 FloatingService 里，只能靠
    //   "源码里有没有这句话"的正则断言 —— 那是**存在性**，不是行为。
    //   抽出来之后可以在桌面 JVM 上真的喂几组坐标断言它选对边、夹得进屏幕。

    /** 挂在桌宠右侧。 */
    public static final int SIDE_RIGHT = 1;
    /** 挂在桌宠左侧。 */
    public static final int SIDE_LEFT = -1;

    /**
     * 侧栏该挂哪一侧：**桌宠中心在屏幕左半边 → 挂右边；否则挂左边**。
     *
     * <p>语义就是"挂到远离屏幕边缘的那一侧" —— 靠边的桌宠把条挂在外侧会顶出屏幕，
     * 挂在内侧才既看得见又不挡手势。
     */
    public static int sideFor(int petLeft, int petW, int screenW) {
        if (screenW <= 0) return SIDE_RIGHT;
        int petCenter = petLeft + petW / 2;
        return petCenter <= screenW / 2 ? SIDE_RIGHT : SIDE_LEFT;
    }

    /**
     * 侧栏窗口的 x：按 {@link #sideFor} 贴到桌宠外侧，再**夹进屏幕**。
     *
     * <p>夹紧这一步不能省：桌宠贴在最左边而侧栏又比剩余空间宽时，
     * 不夹就会有一半画在屏幕外（用户看到的是"条少了一截"）。
     */
    public static int sideBarX(int petLeft, int petW, int screenW, int barW, int gap) {
        int x = sideFor(petLeft, petW, screenW) == SIDE_RIGHT
                ? petLeft + petW + gap
                : petLeft - barW - gap;
        if (x < 0) x = 0;
        if (barW > 0 && x + barW > screenW) x = Math.max(0, screenW - barW);
        return x;
    }

    /** 侧栏窗口的 y：与桌宠垂直居中，再夹进屏幕。 */
    public static int sideBarY(int petTop, int petH, int screenH, int barH) {
        int y = petTop + (petH - barH) / 2;
        if (y < 0) y = 0;
        if (barH > 0 && y + barH > screenH) y = Math.max(0, screenH - barH);
        return y;
    }

    // ==================== 指标累计器（差值法的"上一次计数"，纯逻辑）====================

    /**
     * 系统指标段的分隔标记 —— 拼命令（{@code GameFpsMonitor.buildSampleCmd}）与
     * 解析（本类 {@link Accumulator}）**两处共用一份**。
     *
     * <p>★ 为什么要分段：{@code /proc/net/dev} 的行里也有"两个数字"，
     * 不隔开会被 {@code gpubusy} 的解析器当成 GPU 计数吃掉 —— 同一份输出里
     * 三四个解析器各找各的模式，边界必须显式。
     */
    public static final String MARK_CPU = "===DSPCPU===";
    public static final String MARK_GPU = "===DSPGPU===";
    public static final String MARK_NET = "===DSPNET===";
    public static final String MARK_TMP = "===DSPTMP===";

    /** 取 {@code [from, to)} 片段；标记缺失时返回空串（绝不抛）。 */
    private static String slice(String s, int from, int to) {
        if (s == null || from < 0) return "";
        if (to < 0 || to < from) to = s.length();
        if (from >= s.length()) return "";
        return s.substring(from, to);
    }

    /**
     * 指标累计器：把"上一次的原始计数"收在一个**纯对象**里。
     *
     * <h3>★ 为什么单独抽出来</h3>
     * CPU/GPU/网络都是**累计计数**，要两次差值才有意义 —— 这里有三处最容易错、
     * 而错了在界面上只表现为"数字不对"的地方：
     * <ol>
     *   <li><b>第一次采样必须不出数</b>：没有基准，硬算会得出一个荒谬的百分比；</li>
     *   <li><b>计数器回退必须判无效</b>：网卡重连 / GPU 休眠会归零或回绕，
     *       差值为负 —— 那不是"负流量"，是这一窗口不能用；</li>
     *   <li><b>网络速率要除以真实间隔</b>：采样周期本身会漂（dumpsys 慢时一轮
     *       可能 1.3 秒），写死 1000ms 会系统性偏大。</li>
     * </ol>
     * 抽成纯类之后这几条都能在桌面 JVM 上喂**真机格式的输出**断言
     * （见 {@code build/fpstest}），不必等真机才发现数字不对。
     *
     * <p>温度是**绝对量**（不是差值），所以第一次采样就能出数。
     */
    public static final class Accumulator {
        private long[] cpu = null;
        private long[] gpu = null;
        private long netBytes = -1L;
        private long netAtMs = 0L;

        /** 换会话时清基准：新会话第一次 {@link #update} 只建基准、不出数。 */
        public void reset() {
            cpu = null;
            gpu = null;
            netBytes = -1L;
            netAtMs = 0L;
        }

        /**
         * 用一份带分段标记的原始输出更新快照。
         *
         * <p>只写 cpu/gpu/net/temp，**不动调用方已经填好的 fps**。
         *
         * @param metPart 从 {@link #MARK_CPU} 起的原始文本；null/空表示这次没取到
         * @param nowMs   本次采样时刻（uptimeMillis），算网络速率要用
         * @param out     写进这个快照；为 null 直接返回
         */
        public void update(String metPart, long nowMs, Snapshot out) {
            if (out == null || metPart == null || metPart.isEmpty()) return;
            int a = metPart.indexOf(MARK_CPU);
            int b = metPart.indexOf(MARK_GPU);
            int c = metPart.indexOf(MARK_NET);
            int e = metPart.indexOf(MARK_TMP);
            String cpuRaw = slice(metPart, a, b);
            String gpuRaw = slice(metPart, b, c);
            String netRaw = slice(metPart, c, e);
            String tmpRaw = slice(metPart, e, metPart.length());

            long[] cpuNow = parseProcStat(cpuRaw);
            if (cpuNow != null && cpu != null) out.cpu = cpuPercent(cpu, cpuNow);
            cpu = cpuNow;

            long[] gpuNow = parseGpuBusy(gpuRaw);
            if (gpuNow != null && gpu != null) out.gpu = gpuPercent(gpu, gpuNow);
            gpu = gpuNow;

            long netNow = parseNetBytes(netRaw);
            if (netNow >= 0 && netBytes >= 0 && netAtMs > 0) {
                out.net = netBytesPerSec(netBytes, netNow, nowMs - netAtMs);
            }
            if (netNow >= 0) {
                netBytes = netNow;
                netAtMs = nowMs;
            }

            // 温度是绝对量：第一次采样就能出数（不像上面三项要等一个周期）
            out.temp = pickTempC(tmpRaw);
        }
    }

    // ==================== 监测条形态（开关 × 数据 × 吸附态 × 藏桌宠）====================

    /** 不显示。 */
    public static final int BAR_NONE = 0;
    /** 吸附态：占竖条槽位，**只显示中格那一项**（用户 2026-10-07 修订）。 */
    public static final int BAR_STRIP = 1;
    /** 展开态：桌宠左/右侧三行小胶囊（多项）。 */
    public static final int BAR_SIDE = 2;

    /**
     * 监测条该以什么形态出现 —— 把散在服务里的几个条件收成**一个纯判据**。
     *
     * <p>★ 用户 2026-10-07 修订了形态规则：
     * <ul>
     *   <li><b>吸附贴边的竖条只显示一个数据</b>（中格那一项）—— 还原上一版的单值竖条；
     *       三个数字竖着挤在 20dp 宽的细条里既看不清、也不像"细条"；</li>
     *   <li><b>悬浮窗（展开态）显示多个数据</b>（上/中/下三行胶囊）。</li>
     * </ul>
     * 所以吸附态看的是"**中格**有没有设"，而不是"三格有没有任意一个"。
     *
     * <p>另外三条约束不变：
     * <ul>
     *   <li>开关没过（游戏助手总开关关 / 监测条显示开关关）→ 不显示；</li>
     *   <li>桌宠被临时藏起（进设置页、划卡）→ 不显示 —— 侧栏是**独立悬浮窗**，
     *       不挡这一下它会孤零零挂在设置页上；</li>
     *   <li><b>本会话从未拿到过数据时不占竖条槽位</b>（退回普通竖条）——
     *       避免屏幕上出现一条 "--" 的怪条。</li>
     * </ul>
     *
     * @param gateOpen  开关是否打开（游戏助手总开关 ∧ 监测条显示开关）
     * @param everData  本会话是否**曾经**拿到过数据（不是"最近 5 秒有数据"）
     * @param petHidden 桌宠是否被临时藏起
     * @param collapsed 是否处于吸附态（COLLAPSED）
     * @param hasMid    中格是否设了指标（吸附态只看这一格）
     * @param hasAny    三格里至少有一个不是"不显示"（展开态看这个）
     * @return {@link #BAR_NONE} / {@link #BAR_STRIP} / {@link #BAR_SIDE}
     */
    public static int barForm(boolean gateOpen, boolean everData, boolean petHidden,
                              boolean collapsed, boolean hasMid, boolean hasAny) {
        if (!gateOpen || petHidden) return BAR_NONE;
        if (collapsed) return (everData && hasMid) ? BAR_STRIP : BAR_NONE;
        return hasAny ? BAR_SIDE : BAR_NONE;
    }

    // ==================== 监测范围（所有应用 / 仅选定的应用）====================
    //
    // 历史（两轮相反的决定都在这里，别只看一条）：
    //   轮次⑥ 做过"勾选应用白名单"；轮次⑦ 用户要求"全部打开或关闭"，
    //   于是白名单连同勾选页一并撤除；
    //   ★ 本轮（2026-10-08）用户要求**两者都要、并集成在一起**：
    //   同一行「监测范围：所有应用 ›」里二选 —— 默认"所有应用"（即轮次⑦ 的行为），
    //   需要时切成"仅选定的应用"再进勾选页。所以下面这个判据同时覆盖两种模式，
    //   默认值必须让它退化成"全局"。默认值一旦写反，用户不开任何名单就全都不显示。

    /**
     * 监测条此刻该不该显示（把"总开关 × 显示开关 × 监测范围"收成**一个纯判据**）。
     *
     * <p>★ 为什么值得单独一个函数：这里最容易出的两种错都是**静默**的 ——
     * "部分应用模式下忘了查名单"会让条出现在不该出现的地方；
     * "全局模式下反而被名单挡住"会让条该出现时不出现。
     * 两者都不报错、不崩溃，只能靠穷举断言钉住（见 {@code build/fpstest} 第 [14] 节）。
     *
     * @param enabled      游戏助手总开关
     * @param barOn        监测条显示开关（长按菜单里那个「📊 监测条」）
     * @param onlySelected true = 只在选定的应用里显示
     * @param pkg          当前前台包名；还没采到样时为 {@code null}
     * @param list         选定的应用包名集合（可为 {@code null}）
     */
    public static boolean monitorVisible(boolean enabled, boolean barOn, boolean onlySelected,
                                         String pkg, java.util.Set<String> list) {
        if (!enabled || !barOn) return false;
        if (!onlySelected) return true;
        // ★ 还没拿到前台包名时**不显示**：范围是"部分应用"时，不知道是谁就无从判断。
        //   猜一个（比如沿用上一轮那个包名）会让条出现在根本没勾选的应用上 ——
        //   用户看到的正是"我明明没勾它却在显示"。
        if (pkg == null || pkg.length() == 0) return false;
        return list != null && list.contains(pkg);
    }

    /**
     * 勾选页的搜索匹配：空查询匹配全部；否则**应用名或包名**忽略大小写包含即命中。
     *
     * <p>包名也要匹配：中文名搜不到时用户会去搜包名（真机上验证时就是靠
     * 搜 `settings` 找到「设置」的）。
     */
    public static boolean appMatches(String query, String label, String pkg) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.US);
        if (q.length() == 0) return true;
        String l = label == null ? "" : label.toLowerCase(java.util.Locale.US);
        String p = pkg == null ? "" : pkg.toLowerCase(java.util.Locale.US);
        return l.contains(q) || p.contains(q);
    }

    // ==================== 吸附竖条的宽度（按内容算，不再写死）====================
    //
    // ★ 用户报障（2026-10-07）："吸附贴边时候数据显示不完整""打开性能监测的时候
    //   增大显示面积"。原因是竖条宽度**写死 20dp**，而中格文本可能是
    //   `5.2G` / `100%` / `12.3M/s` —— 9sp 的字在 20dp 里必然被截断
    //   （格子是 setSingleLine，超出就是硬截，界面上只看得到半个字符）。
    //   所以改成"按实际文字宽度给"，并夹在 [20, 52] dp 之间：
    //   短文本仍是一条细条，长文本够放，超过 52dp 就不像"细条"了。

    /** 竖条最小宽度（dp）：短文本（如 `61`）时保持细条观感。 */
    public static final float STRIP_MIN_W_DP = 20f;
    /** 竖条最大宽度（dp）：再宽就比桌宠露出的部分还宽，不像细条了。 */
    public static final float STRIP_MAX_W_DP = 52f;
    /** 文本左右内边距合计（dp）。 */
    public static final float STRIP_PAD_DP = 12f;

    /**
     * 吸附态竖条该多宽（dp）。
     *
     * @param textWidthPx 中格文本的实测宽度（{@code Paint.measureText}），像素
     * @param density     屏幕密度；{@code <= 0} 时退化成最小宽度（不做除法）
     */
    public static float stripWidthDp(float textWidthPx, float density) {
        if (density <= 0f) return STRIP_MIN_W_DP;
        if (textWidthPx <= 0f) return STRIP_MIN_W_DP;
        float want = textWidthPx / density + STRIP_PAD_DP;
        if (want < STRIP_MIN_W_DP) return STRIP_MIN_W_DP;
        if (want > STRIP_MAX_W_DP) return STRIP_MAX_W_DP;
        return want;
    }

    // ==================== 侧栏监测条与桌宠的间距（用户可调）====================
    //
    // ★ 用户要求（2026-10-07）："监测条距离桌宠太远，改为就在桌宠旁边跟随桌宠位置
    //   实时移动" + "加一个可调节的选项"。所以间距不再写死 6dp，做成设置项。
    //   下限允许**负值**：桌宠画布里常带一圈透明留白（立绘本身有边距），
    //   间距调到 0 看着仍可能"有点远" —— 负值让用户能把它压进那圈留白里，
    //   真正贴住看得见的形象。上限防止它盖住桌宠主体。

    /** 间距下限（dp）：负值 = 往桌宠方向压进去一点（吃掉透明留白）。 */
    public static final float MONITOR_GAP_MIN_DP = -12f;
    /** 间距上限（dp）。 */
    public static final float MONITOR_GAP_MAX_DP = 24f;
    /** 间距默认值（dp）。 */
    public static final float MONITOR_GAP_DEFAULT_DP = 6f;

    /** 把可调间距夹进 [MIN, MAX]（NaN 也当默认值处理，绝不返回 NaN）。 */
    public static float clampMonitorGapDp(float v) {
        if (Float.isNaN(v)) return MONITOR_GAP_DEFAULT_DP;
        if (v < MONITOR_GAP_MIN_DP) return MONITOR_GAP_MIN_DP;
        if (v > MONITOR_GAP_MAX_DP) return MONITOR_GAP_MAX_DP;
        return v;
    }

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }
}
