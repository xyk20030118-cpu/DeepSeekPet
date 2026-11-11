package com.deepseek.pet;

/**
 * FPS 计算 —— 从 {@code dumpsys SurfaceFlinger --latency} 的原始输出算实时帧率。
 *
 * <h3>★ 为什么单独拆一个类、且不依赖任何 Android API</h3>
 * 这样可以在**桌面 JVM 上直接用真机抓到的数据跑**（见 {@code build/verify_fps_math.py}
 * 配套的 main 入口），验证的是**这个类的真实代码**，不是另写一份复制品。
 * 算法一旦算错，在 App 里表现为"数字看着不对"，很难判断是取数问题还是算法问题。
 *
 * <h3>--latency 输出格式</h3>
 * <pre>
 *   第 1 行     ：显示刷新周期（纳秒），如 16666666 = 1/60s
 *   之后 128 行 ：desiredPresentTime \t actualPresentTime \t frameReadyTime
 *                 三者全为 0 表示该槽位是空的（环形缓冲未填满）
 * </pre>
 *
 * <h3>★ 核心陷阱（已实测量化）</h3>
 * 环形缓冲里会混着"很久以前的帧"——比如两次滑动之间的静默期。
 * 若直接对全部有效帧求平均间隔，那些巨大间隔会把结果拉垮：
 * 同一份数据，正确算法得 <b>20.2 fps</b>，不过滤静默期只得 <b>4.8 fps</b>。
 * 所以必须只统计**连续出帧区间**（间隔小于 {@link #LIVE_GAP_MS}）。
 */
public final class FpsMath {

    private FpsMath() {}

    /** 连续帧的判定阈值（毫秒）：间隔大于它就认为中间是静默期，断开统计。 */
    private static final double LIVE_GAP_MS = 100.0;

    /** 帧槽总数（SurfaceFlinger 的环形缓冲固定 128 槽）。 */
    public static final int RING = 128;

    /** 计算结果。 */
    public static final class Result {
        /** 实测 FPS；-1 表示没有有效帧（画面静止或取数失败）。 */
        public final float fps;
        /** 参与统计的连续帧间隔数。 */
        public final int liveFrames;
        /** 有效帧总数（含被静默期隔断的）。 */
        public final int validFrames;
        /** 屏幕刷新周期（纳秒）；0 表示没解析出来。 */
        public final long refreshNs;

        Result(float fps, int liveFrames, int validFrames, long refreshNs) {
            this.fps = fps;
            this.liveFrames = liveFrames;
            this.validFrames = validFrames;
            this.refreshNs = refreshNs;
        }

        /** 屏幕刷新率（Hz），拿不到返回 0。 */
        public float refreshHz() {
            return refreshNs > 0 ? (float) (1e9 / refreshNs) : 0f;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US,
                    "fps=%.1f live=%d valid=%d refresh=%.1fHz",
                    fps, liveFrames, validFrames, refreshHz());
        }
    }

    /**
     * 解析并计算。
     *
     * @param raw {@code dumpsys SurfaceFlinger --latency <layer>} 的原始 stdout；
     *            传 null / 空 / 格式不符都安全返回 fps=-1
     */
    public static Result compute(String raw) {
        if (raw == null) return new Result(-1f, 0, 0, 0L);

        String[] lines = raw.split("\n");
        long refreshNs = 0;
        int valid = 0;
        boolean seenFrame = false;
        // 用 double 数组存 actualPresentTime；128 槽，固定大小足够
        double[] times = new double[RING];
        int n = 0;

        for (String line : lines) {
            // ★ 去 BOM：实测踩过 —— 用 PowerShell 的 Out-File -Encoding utf8
            //   抓下来的数据带 UTF-8 BOM(EF BB BF)，首行变成 "\uFEFF16666666"，
            //   Long.parseLong 直接失败。第一版因此整份数据解析为 0 帧。
            //   真机上某些 ROM 的输出也可能带奇怪前缀，所以这里一律清掉再判。
            String s = line.replace("\uFEFF", "").trim();
            if (s.isEmpty()) continue;

            String[] parts = s.split("\\s+");
            if (parts.length == 3) {
                // 帧三元组
                long d, a, r;
                try {
                    d = Long.parseLong(parts[0]);
                    a = Long.parseLong(parts[1]);
                    r = Long.parseLong(parts[2]);
                } catch (NumberFormatException e) {
                    continue;                        // 不是帧行，跳过
                }
                seenFrame = true;
                if (d == 0 && a == 0 && r == 0) continue;   // 空槽
                if (a <= 0) continue;                       // 没上屏时刻，用不了
                valid++;
                if (n < RING) times[n++] = a;
                continue;
            }

            // 单数字行 = 刷新周期（只在还没见过帧行时认）
            if (!seenFrame && refreshNs == 0 && parts.length == 1) {
                try {
                    refreshNs = Long.parseLong(parts[0]);
                } catch (NumberFormatException ignore) {
                    // ★ 不 return：第一行不是数字不代表整份输出没用。
                    //   继续往下找，兼容带表头的 ROM 输出。
                }
            }
        }

        if (valid == 0 || n < 2) {
            return new Result(-1f, 0, valid, refreshNs);
        }

        // 环形缓冲的物理顺序不保证时间单调，先排序
        double[] t = new double[n];
        System.arraycopy(times, 0, t, 0, n);
        java.util.Arrays.sort(t);

        // 统计连续帧间隔
        double sum = 0;
        int live = 0;
        for (int i = 0; i + 1 < n; i++) {
            double gapMs = (t[i + 1] - t[i]) / 1e6;
            if (gapMs > 0 && gapMs < LIVE_GAP_MS) {
                sum += gapMs;
                live++;
            }
        }
        if (live == 0 || sum <= 0) {
            return new Result(-1f, 0, valid, refreshNs);
        }

        float fps = (float) (1000.0 / (sum / live));
        return new Result(fps, live, valid, refreshNs);
    }

    /**
     * 从 {@code --list} 输出里找某个包对应的图层名。
     *
     * <p>实测格式（Android 16）：{@code RequestedLayerState{<可选hash> <包名>/<Activity>#<id> parentId=<n>}}
     * 传给 {@code --latency} 的正确形式是 <b>去掉 hash 前缀、保留 #id</b>：
     * {@code com.pkg/com.pkg.Activity#1454}。
     * 带 hash 或不带 #id 都取不到数据（实测过四种写法）。
     *
     * @return 图层名；找不到返回 null
     */
    public static String findLayer(String listOutput, String pkg) {
        if (listOutput == null || pkg == null || pkg.isEmpty()) return null;
        String best = null;
        for (String line : listOutput.split("\n")) {
            String s = line.trim();
            int b = s.indexOf("RequestedLayerState{");
            if (b < 0) continue;
            String body = s.substring(b + "RequestedLayerState{".length());
            int e = body.indexOf('}');
            if (e >= 0) body = body.substring(0, e);
            // 去掉 " parentId=..." 尾巴
            int p = body.indexOf(" parentId=");
            if (p >= 0) body = body.substring(0, p);

            // 必须属于目标包
            if (!body.contains(pkg + "/")) continue;
            // 必须带 #id（--latency 认这个形式）
            int hash = body.lastIndexOf('#');
            if (hash < 0) continue;

            // 去掉可选的前导 hash（"39b5aab " 这种 7 位十六进制 + 空格）
            String name = body;
            int sp = name.indexOf(' ');
            if (sp > 0 && sp < 12) {
                String head = name.substring(0, sp);
                if (head.matches("[0-9a-f]+")) {
                    name = name.substring(sp + 1);
                }
            }
            // 排除 ActivityRecord / InputSink 这类非绘制层
            if (name.startsWith("ActivityRecord") || name.contains("InputSink")) continue;

            // 优先选"主窗口"层：包名/Activity 直接开头的那种，长度更短的更可能是主层
            if (best == null || name.length() < best.length()) {
                best = name;
            }
        }
        return best;
    }

    // ==================== SurfaceFlinger timestats（Android 17 主力口径）====================

    /**
     * 一个图层的帧统计。
     *
     * <h3>★ 为什么改用 timestats（Android 17 真机实测）</h3>
     * {@code dumpsys SurfaceFlinger --latency} 在 Android 17（用户真机 HyperOS）
     * 上**已失效**：只返回 1 行刷新周期，128 帧环形缓冲全是 0
     * （同一命令在 Android 16 模拟器上是好的，76/128 有效帧）。
     * {@code dumpsys gfxinfo} 也不行：它只统计 HWUI，游戏（SurfaceView/GL）
     * 实测只报 23 帧（全是登录界面 UI 帧）。
     *
     * <p>而 {@code dumpsys SurfaceFlinger --timestats -dump} 给出**按图层**的
     * {@code totalFrames} 与 {@code averageFPS}，**认游戏**：
     * 实测王者荣耀的 {@code SurfaceView[...](BLAST)} 层报 {@code averageFPS = 62.500}。
     */
    public static final class LayerStat {
        public String layer = "";
        public int uid = -1;
        public long totalFrames = 0;
        public float averageFps = -1f;

        @Override public String toString() {
            return layer + " tf=" + totalFrames + " avg=" + averageFps;
        }
    }

    /**
     * 解析 {@code --timestats -dump} 的全部图层统计。
     *
     * <p>输出按"块"组织，每块含 {@code uid / layerName / totalFrames / averageFPS}。
     * 以 {@code layerName} 为块边界（uid 在其前面一行）。
     */
    public static java.util.List<LayerStat> parseTimeStats(String raw) {
        java.util.List<LayerStat> out = new java.util.ArrayList<LayerStat>();
        if (raw == null) return out;

        LayerStat cur = null;
        int pendingUid = -1;
        for (String line : raw.split("\n")) {
            String s = line.replace("\uFEFF", "").trim();
            if (s.isEmpty()) continue;

            if (s.startsWith("uid = ")) {
                pendingUid = parseIntSafe(s.substring(6), -1);
                continue;
            }
            if (s.startsWith("layerName = ")) {
                if (cur != null) out.add(cur);          // 收上一个块
                cur = new LayerStat();
                cur.uid = pendingUid;
                cur.layer = s.substring("layerName = ".length()).trim();
                continue;
            }
            if (cur == null) continue;
            if (s.startsWith("totalFrames = ")) {
                cur.totalFrames = parseIntSafe(s.substring("totalFrames = ".length()), 0);
            } else if (s.startsWith("averageFPS = ")) {
                cur.averageFps = parseFloatSafe(s.substring("averageFPS = ".length()), -1f);
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    // ==================== 图层表容量（"读数永久冻住"的真根因）====================

    /**
     * SurfaceFlinger TimeStats 图层表的硬上限 —— **真机实测 200 条**。
     *
     * <h3>★★ 满了会怎样（用户反馈的"帧率监测卡住不动"的真根因）</h3>
     * 真机实测（Xiaomi 2210132C / Android 17）：
     * <pre>
     *   dumpsys SurfaceFlinger --timestats -dump | grep -c '^layerName = '   → 201
     *   而**当前前台 Activity 的图层根本不在表里** —— 表里全是我们 App 历史
     *   Activity 的死层（#998 / #1117 / #2879 ...，计数冻结在 2848 / 2445 / 2185）。
     *   ⇒ 所有候选层的 totalFrames 全部不涨 ⇒ 增量恒为 0 ⇒ 帧率永远 -1
     *   ⇒ 界面永远"静止 / 0 FPS"，而且**不会自己恢复**（表一直是满的，
     *     连划屏都不涨 —— 实测划三次设置页，计数一动不动）。
     *   实测执行 `--timestats -clear` 后：表里只剩 4 条，**活层立刻出现**
     *   （#3710，106 → 476 帧），帧率恢复正常。
     * </pre>
     * 所以必须在接近上限时主动 {@code -clear}，不能等它满
     * （满了以后新层直接进不来，功能就永久失效了）。
     */
    public static final int LAYER_TABLE_CAP = 200;

    /** 清理阈值：留余量提前清。 */
    public static final int LAYER_TABLE_CLEAR_AT = 180;

    /**
     * 数一份 {@code --timestats -dump} 里有几个图层段（只认行首的
     * {@code layerName = }，与 {@code grep -c '^layerName = '} 同口径）。
     */
    public static int countLayers(String dump) {
        if (dump == null || dump.isEmpty()) return 0;
        final String key = "layerName = ";
        int n = 0;
        int i = dump.indexOf(key);
        while (i >= 0) {
            if (i == 0 || dump.charAt(i - 1) == '\n') n++;
            i = dump.indexOf(key, i + key.length());
        }
        return n;
    }

    /** 图层表是不是已经该清了（调用方据此执行 {@code --timestats -clear}）。 */
    public static boolean layerTableNeedsClear(String dump) {
        return countLayers(dump) >= LAYER_TABLE_CLEAR_AT;
    }

    /**
     * 从图层统计里挑出目标应用的主绘制层。
     *
     * <h3>挑选规则（真机实测得出的）</h3>
     * <ol>
     *   <li>图层名必须含 {@code <包名>/}（避免把 {@code InputMethod}、{@code StatusBar}
     *       这些系统层算进来）</li>
     *   <li>排除 {@code ActivityRecord} / {@code InputSink} / {@code Background for} 等非绘制层</li>
     *   <li>多个候选时取 {@code totalFrames} <b>最大</b>的那个 ——
     *       实测微信同一 Activity 同时存在 4 个层（6787 / 1580 / 737 / 678 帧），
     *       帧数最多的才是真正在刷的主层</li>
     * </ol>
     *
     * @return 主图层；找不到返回 null
     */
    /**
     * 过滤出属于某包的**候选绘制层**，按 {@code totalFrames} 降序。
     *
     * <h3>★★ 为什么不能直接"选帧数最大的那个"（真机实测的重大教训）</h3>
     * 第一版 {@link #pickLayer} 就是按 {@code totalFrames} 最大来选，结果在真机上
     * 我们自己的 App **一直显示不出帧率**。dump 出来才发现：
     * {@code --timestats} 会**保留所有历史图层** —— 实测我们 App 有 **144 个**层，
     * 名字分别是 {@code ...MainActivity#2576 / #2470 / #2417 / #2623 / #1886 ...}
     * （Activity 每重建一次就留一个新层）。
     * "帧数最大"的那个恰恰是**早已死掉的旧层**（计数冻结在 13464），
     * 于是相邻两次差值恒为 0、帧率永远是 -1。
     *
     * <p>正确判据是"**正在增长**的那个"——只有它才代表当前前台应用在刷。
     * 所以这里只做过滤，选层交给调用方按"两次采样的增量"决定。
     */
    public static java.util.List<LayerStat> filterLayers(
            java.util.List<LayerStat> stats, String pkg) {
        java.util.List<LayerStat> out = new java.util.ArrayList<LayerStat>();
        if (stats == null || pkg == null || pkg.isEmpty()) return out;
        for (LayerStat st : stats) {
            if (st == null || st.layer == null) continue;
            if (!st.layer.contains(pkg + "/")) continue;
            if (st.layer.contains("ActivityRecord")
                    || st.layer.contains("InputSink")
                    || st.layer.contains("Background for")) continue;
            out.add(st);
        }
        // 帧数降序：调用方在增量相同时优先取历史更长的（更可能是主层）
        java.util.Collections.sort(out, new java.util.Comparator<LayerStat>() {
            @Override public int compare(LayerStat a, LayerStat b) {
                return Long.compare(b.totalFrames, a.totalFrames);
            }
        });
        return out;
    }

    /**
     * 从图层统计里挑出目标应用的主绘制层。
     *
     * <p>★ 这个"按帧数最大"的判据**已被真机证伪**（见 {@link #filterLayers} 的说明：
     * 帧数最大的往往是死掉的旧层）。{@code GameFpsMonitor} 已改用
     * {@link #filterLayers} + 增量判据。这里保留它只作**诊断/兜底**用途。
     *
     * @deprecated 用 {@link #filterLayers} 配合"两次采样增量"来选当前活跃层
     */
    @Deprecated
    public static LayerStat pickLayer(java.util.List<LayerStat> stats, String pkg) {
        if (stats == null || pkg == null || pkg.isEmpty()) return null;
        LayerStat best = null;       // 非 VRI（优先）
        LayerStat bestVri = null;    // VRI 兜底
        for (LayerStat st : stats) {
            if (st == null || st.layer == null) continue;
            if (!st.layer.contains(pkg + "/")) continue;
            if (st.layer.contains("ActivityRecord")
                    || st.layer.contains("InputSink")
                    || st.layer.contains("Background for")) continue;
            // ★ 优先非 VRI 层。真机实测踩过：我们自己的 App 被选中了
            //   VRI-com.deepseek.pet/...MainActivity#2470，而它的 totalFrames
            //   **冻结在 6909 不动** ⇒ 差值恒为 0 ⇒ 帧率永远 -1 ⇒ 挂牌一直显示 "--"。
            //   VRI- 是系统的"可变刷新率指示"包装层，不是内容层。
            if (st.layer.contains("VRI-")) {
                if (bestVri == null || st.totalFrames > bestVri.totalFrames) bestVri = st;
            } else {
                if (best == null || st.totalFrames > best.totalFrames) best = st;
            }
        }
        return best != null ? best : bestVri;
    }

    /**
     * 用两次采样的 {@code totalFrames} 差值算实时帧率。
     *
     * <h3>★ 为什么用差值而不是直接读 averageFPS</h3>
     * {@code averageFPS} 是**自 -enable 起的累计平均**，不随时间更新
     * （实测连续三次 dump 都是 62.500）。要"实时"就必须自己取窗口：
     * 记录上一次的 {@code totalFrames} 与时刻，差值除以间隔。
     * 这样每个采样点只需 **1 条命令**，而 {@code -disable/-clear/-enable}
     * 那套要 4 条。
     *
     * @param prevFrames 上次该层的 totalFrames；&lt;0 表示首次（无基准）
     * @param nowFrames  本次 totalFrames
     * @param elapsedMs  两次采样间隔（毫秒）
     * @return 实时 FPS；无法计算返回 -1
     */
    public static float fpsFromDelta(long prevFrames, long nowFrames, long elapsedMs) {
        if (prevFrames < 0 || nowFrames < prevFrames || elapsedMs <= 0) return -1f;
        long d = nowFrames - prevFrames;
        if (d <= 0) return -1f;                     // 画面静止：没有新帧
        return (float) (d * 1000.0 / elapsedMs);
    }
}
