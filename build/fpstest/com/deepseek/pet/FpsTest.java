package com.deepseek.pet;

/**
 * FpsMath 离线断言（纯 javac + java，不需要 Android 运行时）。
 *
 * <h3>为什么能直接编真源码</h3>
 * {@link FpsMath} 被刻意设计成**零 Android 依赖**（只用 java.util），
 * 所以不像 vidtest 那样需要 prepare.py 做常量替换 —— 这里编的就是
 * 打进 APK 的那份代码本身。
 *
 * <h3>钉的关键性质</h3>
 * <ol>
 *   <li>精确帧率：造 60fps / 30fps 的时间戳，必须算出对应值</li>
 *   <li><b>静默期必须被过滤</b>：这是最容易算错的地方 ——
 *       环形缓冲里混着"两次出帧之间隔了很久"的相邻帧，
 *       不过滤的话同一份数据会算出明显偏低的假数字</li>
 *   <li>空槽（全 0）必须跳过</li>
 *   <li>垃圾输入必须安全返回 -1，绝不抛异常</li>
 *   <li>BOM / 多余表头不能导致整份数据解析失败</li>
 *   <li>图层名解析：必须去掉 hash 前缀、保留 #id、排除非绘制层</li>
 * </ol>
 */
public class FpsTest {

    private static int fails = 0;
    private static int total = 0;

    private static void ok(String name, boolean cond) {
        ok(name, cond, "");
    }

    private static void ok(String name, boolean cond, String detail) {
        total++;
        if (!cond) fails++;
        System.out.println((cond ? "  [OK]   " : "  [FAIL] ") + name
                + (detail.isEmpty() ? "" : " :: " + detail));
    }

    /** 造一份 --latency 输出：每帧间隔 gapNs，共 n 帧。 */
    private static String fixture(long refreshNs, long gapNs, int n) {
        StringBuilder sb = new StringBuilder();
        sb.append(refreshNs).append('\n');
        long t = 1_000_000_000L;
        for (int i = 0; i < n; i++) {
            sb.append(t).append(' ').append(t + 1000).append(' ').append(t - 500).append('\n');
            t += gapNs;
        }
        return sb.toString();
    }

    /** 数一个子串出现几次（合成配置的断言要数"这个动作名登记了几次"） */
    private static int countOf(String hay, String needle) {
        int n = 0, at = 0;
        while ((at = hay.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }

    /** 浮点近似相等（比例判据用，避免 0.1f 这类二进制误差把断言弄红） */
    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.0005f;
    }

    /** 快速造一个字符串列表（[29] ClipList 那一节用） */
    private static java.util.List<String> list(String... xs) {
        return new java.util.ArrayList<String>(java.util.Arrays.asList(xs));
    }

    /** 顺序与内容都要一致（列表判据不能只看 size） */
    private static boolean sameList(java.util.List<String> a, java.util.List<String> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!a.get(i).equals(b.get(i))) return false;
        return true;
    }

    /** 峰值绝对值（[31] 点击音效的波形判据用） */
    private static int peak(short[] w) {
        int m = 0;
        for (short v : w) {
            int a = Math.abs((int) v);
            if (a > m) m = a;
        }
        return m;
    }

    // ---- [33] 本地 GGUF 模型：造一个真的 GGUF 头（纯字节，不依赖任何文件）----
    //
    // ★ 格式照 llama.cpp 的 gguf 规范：
    //   magic "GGUF" | version u32 | tensorCount u64 | kvCount u64 | (key,value)*
    //   key = u64 长度 + UTF-8 字节；value = u32 类型 + 内容（8 = STRING = u64 长度 + 字节）
    //   ★ 全部**小端**——写成大端的话，下面几条判据会当场红（这正是它们存在的意义）。

    /** 造一个最小 GGUF 头：永远带 general.architecture，name 非空时再带一条 general.name。 */
    private static byte[] ggufFixture(int version, long tensors, String name) {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        b.write('G'); b.write('G'); b.write('U'); b.write('F');
        putLe32(b, version);
        putLe64(b, tensors);
        putLe64(b, name == null ? 1L : 2L);
        putGgufStr(b, "general.architecture");
        putLe32(b, 8);
        putGgufStr(b, "qwen2");
        if (name != null) {
            putGgufStr(b, "general.name");
            putLe32(b, 8);
            putGgufStr(b, name);
        }
        return b.toByteArray();
    }

    private static void putLe32(java.io.ByteArrayOutputStream b, long v) {
        for (int i = 0; i < 4; i++) b.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    private static void putLe64(java.io.ByteArrayOutputStream b, long v) {
        for (int i = 0; i < 8; i++) b.write((int) ((v >>> (8 * i)) & 0xFF));
    }

    private static void putGgufStr(java.io.ByteArrayOutputStream b, String s) {
        byte[] u = utf8(s);
        putLe64(b, u.length);
        b.write(u, 0, u.length);
    }

    private static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static int utf8Len(String s) {
        return utf8(s).length;
    }

    /** 两条波形是否逐点相同（长度不同即不同） */
    private static boolean sameWave(short[] a, short[] b) {
        if (a == null || b == null) return a == b;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    /**
     * 读一份**真实报文** fixture（{@code build/fpstest/fixtures/}，抓下来原样存的）。
     * <p>★ 为什么用真报文：搜索结果页是**别人的**格式。手写的假 HTML 只能证明
     * "我按我以为的格式写了解析器"；真报文一变，断言立刻红。
     * run.bat 会 {@code cd} 到本目录，所以相对路径就是 fixtures/。
     */
    private static String readFixture(String name) {
        java.io.InputStream in = null;
        try {
            java.io.File f = new java.io.File("fixtures", name);
            if (!f.isFile()) return null;
            in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) {}
        }
    }

    /** [28] 过期语义：写 1 秒 TTL，把虚拟时钟拨快 2 秒后应取不到（不必真 sleep） */
    private static boolean cacheExpiredWorks() {
        long base = 1700000000000L;
        WebCache.setClock(base);
        WebCache.put("ttl", "x", 1000L);
        boolean hit = "x".equals(WebCache.get("ttl"));
        WebCache.setClock(base + 2000L);
        boolean miss = WebCache.get("ttl") == null;
        WebCache.setClock(0);
        return hit && miss;
    }

    /** [28] 脏 key 不许写进缓存 */
    private static boolean putNothing() {
        WebCache.clear();
        WebCache.put(null, "v", 1000L);
        WebCache.put("", "v", 1000L);
        WebCache.put("k2", null, 1000L);
        boolean empty = WebCache.size() == 0;
        WebCache.clear();
        return empty;
    }

    /** [28] 冷却：cool 之后在冷却期内，到期自动解除 */
    private static boolean cooldownWorks() {
        long base = 1700000000000L;
        WebCache.clear();
        WebCache.setClock(base);
        WebCache.cool("h", 5000L);
        boolean on = WebCache.cooldownLeft("h") > 0 && !WebCache.cooling().isEmpty();
        WebCache.setClock(base + 6000L);
        boolean off = WebCache.cooldownLeft("h") == 0L;
        WebCache.setClock(0);
        WebCache.clear();
        return on && off;
    }

    /** [28] 冷却有上限（对面给个天大的 Retry-After 也不能永久封死） */
    private static boolean cooldownCapped() {
        long base = 1700000000000L;
        WebCache.clear();
        WebCache.setClock(base);
        WebCache.cool("big", 999999999L);
        long left = WebCache.cooldownLeft("big");
        WebCache.setClock(0);
        WebCache.clear();
        return left > 0 && left <= WebCache.MAX_COOLDOWN_MS;
    }

    /** [28] 容量上限：写超过上限也不会无限长大 */
    private static boolean capacityBounded() {
        WebCache.clear();
        for (int i = 0; i < WebCache.MAX_ENTRIES + 10; i++) {
            WebCache.put("c" + i, "v", 60000L);
        }
        int n = WebCache.size();
        WebCache.clear();
        return n <= WebCache.MAX_ENTRIES;
    }

    /** 一份"自定义搜索服务"配置（第 [27] 节用；字段故意与预设不同名，防止"写死某家"） */
    private static SearchProvider customProbe() {
        return SearchProvider.fromJson(
                "{\"id\":\"myapi\",\"label\":\"我的搜索 API\","
                + "\"endpoint\":\"https://example.com/search\",\"method\":\"POST\","
                + "\"headers\":{\"Authorization\":\"Bearer {key}\"},"
                + "\"body\":\"{\\\"query\\\": \\\"{q}\\\"}\","
                + "\"list\":\"data.items\",\"title\":\"name\",\"url\":\"link\","
                + "\"snippet\":\"summary\",\"content\":\"paragraphs[].text\"}");
    }

    /** 重复一个字符串 n 次（构造"超长正文"用） */
    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** 结果字段里还残留 HTML 标签？ */
    private static boolean hasTags(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.indexOf("<a ") >= 0 || t.indexOf("</") >= 0 || t.indexOf("<span") >= 0
                || t.indexOf("<strong") >= 0 || t.indexOf("&amp;") >= 0 || t.indexOf("&#") >= 0;
    }

    private static boolean anyTags(java.util.List<WebSearch.Hit> hits) {
        for (WebSearch.Hit h : hits) {
            if (hasTags(h.title) || hasTags(h.snippet) || hasTags(h.url)) return true;
        }
        return false;
    }

    /** 八项指标都必须有中文名（下拉里显示的就是它）。 */
    private static boolean allNamed() {
        for (String id : SysMetrics.IDS) {
            String l = SysMetrics.label(id);
            if (l == null || l.isEmpty() || "不显示".equals(l)) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        System.out.println("=== FpsMath 离线断言 ===");
        System.out.println();

        final long NS60 = 16_666_666L;

        // ---- ① 精确帧率 ----
        System.out.println("[1] 精确帧率");
        FpsMath.Result r60 = FpsMath.compute(fixture(NS60, NS60, 100));
        ok("60fps 造数据 => 60", Math.abs(r60.fps - 60f) < 0.5f, "实得 " + r60.fps);
        ok("刷新率 60Hz", Math.abs(r60.refreshHz() - 60f) < 0.5f, "实得 " + r60.refreshHz());

        FpsMath.Result r30 = FpsMath.compute(fixture(NS60, NS60 * 2, 100));
        ok("30fps 造数据 => 30", Math.abs(r30.fps - 30f) < 0.5f, "实得 " + r30.fps);

        FpsMath.Result r120 = FpsMath.compute(fixture(8_333_333L, 8_333_333L, 100));
        ok("120fps 造数据 => 120", Math.abs(r120.fps - 120f) < 1f, "实得 " + r120.fps);

        // ---- ② 静默期必须被过滤（核心陷阱）----
        System.out.println();
        System.out.println("[2] 静默期过滤（最容易算错的地方）");
        // 50 帧连续 60fps，然后一段很长的静默，再 50 帧连续 60fps
        StringBuilder sb = new StringBuilder();
        sb.append(NS60).append('\n');
        long t = 1_000_000_000L;
        for (int i = 0; i < 50; i++) { sb.append(t).append(' ').append(t).append(' ').append(t).append('\n'); t += NS60; }
        t += 2_000_000_000L;                       // 静默 2 秒
        for (int i = 0; i < 50; i++) { sb.append(t).append(' ').append(t).append(' ').append(t).append('\n'); t += NS60; }
        FpsMath.Result rMix = FpsMath.compute(sb.toString());
        ok("静默期不拉低结果（仍应≈60）", Math.abs(rMix.fps - 60f) < 1f,
                "实得 " + rMix.fps + "（若不过滤会明显偏低）");
        ok("静默期被排除在 liveFrames 之外", rMix.liveFrames < rMix.validFrames,
                "live=" + rMix.liveFrames + " valid=" + rMix.validFrames);

        // ---- ③ 空槽 ----
        System.out.println();
        System.out.println("[3] 空槽（全 0）跳过");
        FpsMath.Result rHole = FpsMath.compute(
                NS60 + "\n0 0 0\n0 0 0\n" + "1000000000 1000000000 1000000000\n"
                        + "1016666666 1016666666 1016666666\n0 0 0\n");
        ok("空槽不计入 validFrames", rHole.validFrames == 2,
                "valid=" + rHole.validFrames);
        ok("仍算出 60fps", Math.abs(rHole.fps - 60f) < 0.5f, "实得 " + rHole.fps);

        // ---- ④ 垃圾输入安全 ----
        System.out.println();
        System.out.println("[4] 垃圾输入不抛异常");
        String[] junk = {null, "", "   ", "hello", "abc\ndef", NS60 + "\n",
                "1 2", "a b c", "\uFEFF" + NS60, "header line\n" + NS60 + "\n"};
        boolean threw = false;
        for (String j : junk) {
            try {
                FpsMath.Result r = FpsMath.compute(j);
                if (r.fps != -1f && j != null && !j.contains("1000000")) {
                    // 单行刷新周期不该算出 fps
                    if (!j.contains("\n") || j.trim().equals(String.valueOf(NS60))
                            || j.trim().equals("\uFEFF" + NS60)) {
                        if (r.fps != -1f) { threw = true; }
                    }
                }
            } catch (Throwable ex) {
                System.out.println("      抛异常: " + j + " -> " + ex);
                threw = true;
            }
        }
        ok("全部安全（无异常、无数据时 fps=-1）", !threw);

        // ---- ⑤ BOM / 表头容忍 ----
        System.out.println();
        System.out.println("[5] BOM 与多余表头容忍");
        String withBom = "\uFEFF" + fixture(NS60, NS60, 50);
        FpsMath.Result rBom = FpsMath.compute(withBom);
        ok("带 BOM 仍算出 60fps", Math.abs(rBom.fps - 60f) < 0.5f, "实得 " + rBom.fps);

        String withHeader = "some rom header\n" + fixture(NS60, NS60, 50);
        FpsMath.Result rHdr = FpsMath.compute(withHeader);
        ok("带表头仍算出 60fps", Math.abs(rHdr.fps - 60f) < 0.5f, "实得 " + rHdr.fps);

        // ---- ⑥ 图层名解析 ----
        System.out.println();
        System.out.println("[6] findLayer 解析");
        String list = "RequestedLayerState{Wallpaper#1 parentId=0}\n"
                + "RequestedLayerState{ActivityRecord{123 u0 com.foo/.Bar#9} parentId=5}\n"
                + "RequestedLayerState{39b5aab com.foo/com.foo.Bar#1410 parentId=1409}\n"
                + "RequestedLayerState{1f4a5c ActivityRecordInputSink com.foo/com.foo.Bar#1408 parentId=1404 z=1}\n"
                + "RequestedLayerState{com.baz/com.baz.Qux#77 parentId=70}\n";
        String layer = FpsMath.findLayer(list, "com.foo");
        ok("解析出 com.foo 的层", layer != null, "=> " + layer);
        ok("去掉 hash 前缀", layer != null && !layer.startsWith("39b5aab"), "=> " + layer);
        ok("保留 #id", layer != null && layer.contains("#1410"), "=> " + layer);
        ok("排除 ActivityRecord", layer != null && !layer.startsWith("ActivityRecord"), "=> " + layer);
        ok("排除 InputSink", layer != null && !layer.contains("InputSink"), "=> " + layer);
        ok("不串到别的包", layer != null && !layer.contains("com.baz"), "=> " + layer);

        ok("无 hash 前缀的也能解析",
                "com.baz/com.baz.Qux#77".equals(FpsMath.findLayer(list, "com.baz")),
                "=> " + FpsMath.findLayer(list, "com.baz"));
        ok("不存在的包返回 null", FpsMath.findLayer(list, "com.nope") == null);
        ok("null 输入返回 null", FpsMath.findLayer(null, "com.foo") == null);
        ok("空包名返回 null", FpsMath.findLayer(list, "") == null);

        // ---- ⑦ timestats 解析（Android 17 主力口径）----
        System.out.println();
        System.out.println("[7] timestats 解析");
        // 真机抓下来的片段（王者荣耀 + 微信混合），保留真实格式
        String ts = "SurfaceFlinger TimeStats:\n"
                + "Legacy stats are as follows:\n"
                + "totalFrames = 379\n"
                + "\n"
                + "uid = 10322\n"
                + "layerName = 156ec41 SurfaceView[com.game/com.game.Main](BLAST)#112219\n"
                + "packageName =\n"
                + "gameMode = Unsupported\n"
                + "totalFrames = 1057\n"
                + "droppedFrames = 0\n"
                + "averageFPS = 61.762\n"
                + "\n"
                + "uid = 10344\n"
                + "layerName = VRI-com.chat/com.chat.ui.Main#112317\n"
                + "totalFrames = 6787\n"
                + "averageFPS = 121.838\n"
                + "\n"
                + "uid = 10344\n"
                + "layerName = VRI-com.chat/com.chat.ui.Main#112290\n"
                + "totalFrames = 737\n"
                + "averageFPS = 117.133\n"
                + "\n"
                + "uid = 1000\n"
                + "layerName = VRI-StatusBar#110\n"
                + "totalFrames = 75\n"
                + "averageFPS = 3.312\n";

        java.util.List<FpsMath.LayerStat> stats = FpsMath.parseTimeStats(ts);
        ok("解析出 4 个层", stats.size() == 4, "实得 " + stats.size());
        if (stats.size() >= 4) {
            ok("首层 layerName 正确",
                    stats.get(0).layer.startsWith("156ec41 SurfaceView[com.game"),
                    "=> " + stats.get(0).layer);
            ok("首层 uid 正确", stats.get(0).uid == 10322, "=> " + stats.get(0).uid);
            ok("首层 totalFrames 正确", stats.get(0).totalFrames == 1057,
                    "=> " + stats.get(0).totalFrames);
            ok("首层 averageFPS 正确", Math.abs(stats.get(0).averageFps - 61.762f) < 0.01f,
                    "=> " + stats.get(0).averageFps);
            // 全局 totalFrames 不能被当成某个层的
            ok("全局 totalFrames 未污染首层", stats.get(0).totalFrames != 379);
        }
        ok("垃圾输入安全", FpsMath.parseTimeStats(null).isEmpty()
                && FpsMath.parseTimeStats("").isEmpty()
                && FpsMath.parseTimeStats("hello\nworld").isEmpty());

        // ---- ⑧ 选层规则 ----
        System.out.println();
        System.out.println("[8] pickLayer 选层");
        FpsMath.LayerStat g = FpsMath.pickLayer(stats, "com.game");
        ok("选中游戏层", g != null && g.layer.contains("SurfaceView[com.game"),
                "=> " + (g == null ? "null" : g.layer));
        // 微信有两个层（6787 / 737），必须选帧数最多的主层
        FpsMath.LayerStat c = FpsMath.pickLayer(stats, "com.chat");
        ok("多个候选时选 totalFrames 最大", c != null && c.totalFrames == 6787,
                "=> " + (c == null ? "null" : (c.totalFrames + " " + c.layer)));
        ok("不把系统层算进来", FpsMath.pickLayer(stats, "android") == null);
        ok("不存在的包返回 null", FpsMath.pickLayer(stats, "com.nope") == null);
        ok("null 安全", FpsMath.pickLayer(null, "com.game") == null);

        // ---- ⑨ 差值法算实时帧率 ----
        System.out.println();
        System.out.println("[9] fpsFromDelta 差值法");
        float f60 = FpsMath.fpsFromDelta(1000, 1120, 2000);   // 2 秒涨 120 帧
        ok("1000->1120 / 2s => 60", Math.abs(f60 - 60f) < 0.5f, "实得 " + f60);
        // ★ 静止时必须是 -1 而不是 0：0 会被 UI 当成"真的 0fps"，
        //   而实际含义是"没有新帧"（画面没动），两者对用户意义完全不同。
        ok("帧数不变 => -1（静止，不是 0fps）",
                FpsMath.fpsFromDelta(1000, 1000, 1000) == -1f);
        ok("首次无基准 => -1", FpsMath.fpsFromDelta(-1, 1000, 1000) == -1f);
        ok("帧数回退 => -1（层被重建）",
                FpsMath.fpsFromDelta(2000, 100, 1000) == -1f);
        ok("时间间隔为 0 => -1", FpsMath.fpsFromDelta(1000, 2000, 0) == -1f);
        float f120 = FpsMath.fpsFromDelta(0, 240, 2000);
        ok("0->240 / 2s => 120", Math.abs(f120 - 120f) < 0.5f, "实得 " + f120);

        // ---- ⑩ 图层表容量（真机"读数永久冻住"的根因）----
        System.out.println();
        System.out.println("[10] 图层表计数与清理判据");
        // 造一份 N 层的 dump（真机满表时是 201 条）
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 201; i++) {
            big.append("uid = 1000\n")
               .append("layerName = VRI-com.deepseek.pet/com.deepseek.pet.MainActivity#")
               .append(1000 + i).append("\n")
               .append("totalFrames = 2848\n\n");
        }
        ok("满表 dump 数出 201 层", FpsMath.countLayers(big.toString()) == 201,
                "实得 " + FpsMath.countLayers(big.toString()));
        ok("满表判据 = 需要清理", FpsMath.layerTableNeedsClear(big.toString()));
        ok("真机实测上限常量是 200", FpsMath.LAYER_TABLE_CAP == 200);
        ok("清理阈值 < 上限（留余量，别等满了）",
                FpsMath.LAYER_TABLE_CLEAR_AT < FpsMath.LAYER_TABLE_CAP,
                FpsMath.LAYER_TABLE_CLEAR_AT + " < " + FpsMath.LAYER_TABLE_CAP);
        // 刚好到阈值就要清（>= 而不是 >）
        StringBuilder at = new StringBuilder();
        for (int i = 0; i < FpsMath.LAYER_TABLE_CLEAR_AT; i++) {
            at.append("layerName = L#").append(i).append("\ntotalFrames = 1\n");
        }
        ok("正好到阈值 => 需要清理", FpsMath.layerTableNeedsClear(at.toString()));
        // 健康的小表不该触发清理（否则每秒都在清，读数永远出不来）
        ok("4 层的小表不触发清理",
                !FpsMath.layerTableNeedsClear(ts) && FpsMath.countLayers(ts) == 4,
                "实得 " + FpsMath.countLayers(ts));
        // 只认行首：别的字段里出现同名字符串不能算
        ok("不把行中的同名字符串算成图层",
                FpsMath.countLayers("foo layerName = x\ntotalFrames = 1\n") == 0);
        ok("null / 空安全",
                FpsMath.countLayers(null) == 0 && FpsMath.countLayers("") == 0
                && !FpsMath.layerTableNeedsClear(null));

        // ---- ⑪ 系统指标解析（真机抓下来的原始输出）----
        System.out.println();
        System.out.println("[11] SysMetrics 八项指标解析");
        // /proc/stat：真机那一行（cpu 空格开头，后面 10 个计数）
        String statA = "cpu  675137 37772 758160 3050873 9743 150463 16553 58 0 0\n"
                + "cpu0 168784 9437 189540 762718 2435 37615 4138 14 0 0\n";
        long[] sa = SysMetrics.parseProcStat(statA);
        // total = 各项之和；busy = total - (idle + iowait)
        long wantTotal = 675137L + 37772 + 758160 + 3050873 + 9743 + 150463 + 16553 + 58;
        long wantBusy = wantTotal - (3050873L + 9743);
        ok("parseProcStat 取到主行（不是 cpu0）", sa != null && sa[1] == wantTotal,
                "total=" + (sa == null ? "null" : sa[1]) + " 期望 " + wantTotal);
        ok("busy = total - (idle+iowait)", sa != null && sa[0] == wantBusy,
                "busy=" + (sa == null ? "null" : sa[0]) + " 期望 " + wantBusy);
        // 差值：user +100、idle +100 ⇒ busy +100、total +200 ⇒ 50%
        String statB = "cpu  675237 37772 758160 3050973 9743 150463 16553 58 0 0\n";
        float cpu50 = SysMetrics.cpuPercent(sa, SysMetrics.parseProcStat(statB));
        ok("CPU 差值 (busy+100/total+200) => 50%", Math.abs(cpu50 - 50f) < 0.01f,
                "实得 " + cpu50);
        ok("CPU 首次无基准 => -1", SysMetrics.cpuPercent(null, sa) == -1f);
        ok("CPU 计数不涨 => -1（不是 0%）",
                SysMetrics.cpuPercent(sa, sa) == -1f);
        ok("CPU 垃圾输入安全", SysMetrics.parseProcStat(null) == null
                && SysMetrics.parseProcStat("hello\n") == null);

        // gpubusy：真机 "  18407 1020280"
        long[] ga = SysMetrics.parseGpuBusy("  18407 1020280\n");
        ok("parseGpuBusy 取到两个数", ga != null && ga[0] == 18407 && ga[1] == 1020280);
        float gpu50 = SysMetrics.gpuPercent(ga, SysMetrics.parseGpuBusy("18907 1021280\n"));
        ok("GPU 差值 (500/1000) => 50%", Math.abs(gpu50 - 50f) < 0.01f, "实得 " + gpu50);
        // ★ 真机实测：GPU 空闲时这个节点返回 "0 0" —— 那是"没数据"，不是 0% 占用
        ok("gpubusy 返回 0 0 => 当没数据（null）",
                SysMetrics.parseGpuBusy("0 0\n") == null);
        ok("GPU 只有一行一个数 => 当没数据",
                SysMetrics.parseGpuBusy("12345\n") == null);

        // /proc/net/dev：真机三行（lo 必须排除）
        String netA = "Inter-|   Receive                                                |  Transmit\n"
                + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
                + "    lo: 17287475   30956    0    0    0     0          0         0 17287475   30956    0    0    0     0       0          0\n"
                + "rmnet_ipa0: 1000 5 0 0 0 0 0 0 2000 6 0 0 0 0 0 0\n"
                + " wlan0: 3000 7 0 0 0 0 0 0 4000 8 0 0 0 0 0 0\n";
        long net = SysMetrics.parseNetBytes(netA);
        ok("网络字节 = 非 lo 网卡 rx+tx", net == 10000L, "实得 " + net);
        float rate = SysMetrics.netBytesPerSec(10000L, 20000L, 1000L);
        ok("1 秒涨 10000 字节 => 10000 B/s", Math.abs(rate - 10000f) < 0.5f, "实得 " + rate);
        ok("计数器回退 => -1（网卡重连，不是负流量）",
                SysMetrics.netBytesPerSec(20000L, 100L, 1000L) == -1f);
        ok("网络垃圾输入 => -1", SysMetrics.parseNetBytes(null) == -1L
                && SysMetrics.parseNetBytes("no colon here") == -1L);

        // thermal_zone：真机那一份（含 -273 占位与大量非 CPU 区）
        String tmp = "pa=36385\npa1=36204\nmmw3=-273000\nconn_therm=34789\n"
                + "vbat=3737\nbattery=34200\nusb=33000\nwireless=36000\n"
                + "aoss-0=40200\ncpuss-0=42600\ncpuss-1=43000\n"
                + "cpu-1-5=45300\ncpu-1-4=43400\ngpuss-0=37800\ncpu_therm=37766\n"
                + "pm8550b_lite_tz=45762\n";
        float tempC = SysMetrics.pickTempC(tmp);
        ok("温度优先取 CPU 区最高（45.3℃，不是充电 IC 的 45.762）",
                Math.abs(tempC - 45.3f) < 0.01f, "实得 " + tempC);
        ok("没有 CPU 区时退回合理值里的最高",
                Math.abs(SysMetrics.pickTempC("battery=34200\nusb=33000\n") - 34.2f) < 0.01f);
        ok("全是 -273 占位 => -1",
                SysMetrics.pickTempC("mmw3=-273000\nepm0=-273000\n") == -1f);
        ok("温度垃圾输入 => -1", SysMetrics.pickTempC(null) == -1f);

        // 功耗 = 电压(mV) × 电流(µA)
        ok("4000mV × 500mA => 2.0W", Math.abs(SysMetrics.powerW(4000, 500000) - 2f) < 0.001f,
                "实得 " + SysMetrics.powerW(4000, 500000));
        ok("放电（负电流）取绝对值", Math.abs(SysMetrics.powerW(4000, -500000) - 2f) < 0.001f);
        ok("电压为 0 => -1", SysMetrics.powerW(0, 500000) == -1f);
        ok("电流为 0 => -1（不支持该属性的机型）", SysMetrics.powerW(4000, 0) == -1f);

        // ---- ⑫ 八项指标格式化 ----
        System.out.println();
        System.out.println("[12] SysMetrics 格式化（竖条窄格 / 侧栏宽格）");
        SysMetrics.Snapshot sn = new SysMetrics.Snapshot();
        sn.cpu = 42.4f; sn.gpu = 31.6f; sn.fps = 61.4f; sn.temp = 38.4f;
        sn.batt = 87f; sn.power = 4.23f; sn.net = 1258291.2f;
        sn.ramUsedKb = 5452595L; sn.ramTotalKb = 11953766L;
        ok("CPU => 42%", "42%".equals(SysMetrics.valueText(SysMetrics.CPU, sn)),
                SysMetrics.valueText(SysMetrics.CPU, sn));
        ok("GPU => 32%", "32%".equals(SysMetrics.valueText(SysMetrics.GPU, sn)),
                SysMetrics.valueText(SysMetrics.GPU, sn));
        ok("RAM 5.2G", "5.2G".equals(SysMetrics.valueText(SysMetrics.RAM, sn)),
                SysMetrics.valueText(SysMetrics.RAM, sn));
        ok("FPS => 61", "61".equals(SysMetrics.valueText(SysMetrics.FPS, sn)));
        ok("温度 => 38℃", (38 + "\u2103").equals(SysMetrics.valueText(SysMetrics.TEMP, sn)),
                SysMetrics.valueText(SysMetrics.TEMP, sn));
        ok("电池 => 87%", "87%".equals(SysMetrics.valueText(SysMetrics.BATT, sn)));
        ok("功耗 <10W 保留一位 => 4.2W",
                "4.2W".equals(SysMetrics.valueText(SysMetrics.POWER, sn)),
                SysMetrics.valueText(SysMetrics.POWER, sn));
        SysMetrics.Snapshot bigPw = sn.copy();
        bigPw.power = 12.6f;
        ok("功耗 >=10W 取整 => 13W", "13W".equals(SysMetrics.valueText(SysMetrics.POWER, bigPw)),
                SysMetrics.valueText(SysMetrics.POWER, bigPw));
        ok("网络 1.2M/s 显示 1.2M",
                "1.2M".equals(SysMetrics.valueText(SysMetrics.NET, sn)),
                SysMetrics.valueText(SysMetrics.NET, sn));
        SysMetrics.Snapshot slow = sn.copy();
        slow.net = 2048f;
        ok("网络 2K/s 显示 2K", "2K".equals(SysMetrics.valueText(SysMetrics.NET, slow)),
                SysMetrics.valueText(SysMetrics.NET, slow));
        // ★ 取不到一律 --（除了 FPS：-1 表示"静止"，是有效读数）
        SysMetrics.Snapshot none = new SysMetrics.Snapshot();
        ok("CPU 取不到 => --", "--".equals(SysMetrics.valueText(SysMetrics.CPU, none)));
        ok("RAM 取不到 => --", "--".equals(SysMetrics.valueText(SysMetrics.RAM, none)));
        ok("温度取不到 => --", "--".equals(SysMetrics.valueText(SysMetrics.TEMP, none)));
        ok("★ FPS 为 -1（静止）=> 0 而不是 --",
                "0".equals(SysMetrics.valueText(SysMetrics.FPS, none)),
                SysMetrics.valueText(SysMetrics.FPS, none));
        ok("展开态带指标名 => CPU 42%",
                "CPU 42%".equals(SysMetrics.fullText(SysMetrics.CPU, sn)),
                SysMetrics.fullText(SysMetrics.CPU, sn));
        ok("展开态取不到 => CPU --",
                "CPU --".equals(SysMetrics.fullText(SysMetrics.CPU, none)));
        ok("「不显示」槽位 => 空串（不占格）",
                "".equals(SysMetrics.fullText(SysMetrics.NONE, sn))
                && "".equals(SysMetrics.shortText(SysMetrics.NONE, sn)));
        ok("八项指标齐全", SysMetrics.IDS.length == 8);
        ok("id 合法性判定", SysMetrics.isValid(SysMetrics.NET)
                && SysMetrics.isValid(SysMetrics.NONE) && !SysMetrics.isValid("bogus")
                && !SysMetrics.isValid(null));
        ok("每项都有中文名", allNamed());

        // ---- ⑬ 侧栏选边（用户要求"取决于位置"）----
        System.out.println();
        System.out.println("[13] SysMetrics 侧栏选边与夹紧");
        // 桌宠中心在屏幕左半边 → 挂右侧；右半边 → 挂左侧（含正好居中）
        ok("桌宠偏左 → 挂右侧",
                SysMetrics.sideFor(0, 200, 1000) == SysMetrics.SIDE_RIGHT);
        ok("桌宠偏右 → 挂左侧",
                SysMetrics.sideFor(800, 200, 1000) == SysMetrics.SIDE_LEFT);
        ok("正好居中 → 挂右侧（<= 的边界）",
                SysMetrics.sideFor(400, 200, 1000) == SysMetrics.SIDE_RIGHT);
        ok("偏左时 x = 桌宠右边 + gap",
                SysMetrics.sideBarX(0, 200, 1000, 150, 10) == 210,
                "实得 " + SysMetrics.sideBarX(0, 200, 1000, 150, 10));
        ok("偏右时 x = 桌宠左边 - 宽 - gap",
                SysMetrics.sideBarX(800, 200, 1000, 150, 10) == 640,
                "实得 " + SysMetrics.sideBarX(800, 200, 1000, 150, 10));
        // ★ 夹紧：窄屏上"挂右边"会顶出屏幕，必须夹回去（否则条少一截）
        ok("右侧放不下时夹回屏幕内（x=0）",
                SysMetrics.sideBarX(100, 200, 400, 400, 10) == 0,
                "实得 " + SysMetrics.sideBarX(100, 200, 400, 400, 10));
        ok("左侧放不下时夹回屏幕内（x=0）",
                SysMetrics.sideBarX(350, 40, 400, 400, 10) == 0,
                "实得 " + SysMetrics.sideBarX(350, 40, 400, 400, 10));
        ok("垂直与桌宠居中", SysMetrics.sideBarY(100, 400, 1000, 200) == 200,
                "实得 " + SysMetrics.sideBarY(100, 400, 1000, 200));
        ok("底部放不下时夹回屏幕内",
                SysMetrics.sideBarY(900, 400, 1000, 200) == 800,
                "实得 " + SysMetrics.sideBarY(900, 400, 1000, 200));
        ok("屏幕宽为 0 不崩（返回右侧）",
                SysMetrics.sideFor(0, 200, 0) == SysMetrics.SIDE_RIGHT);

        // ---- ⑭ 监测范围 + 勾选页搜索（轮次⑨ 集成"全局 / 部分应用"）----
        //
        // 历史：轮次⑥ 做过应用白名单，轮次⑦ 按用户要求撤成"全部打开/关闭"，
        // 本轮（用户 2026-10-08）要求两者**集成在一起**并保留全局默认。
        // 这一节钉的是那个纯判据 —— 它有两个"静默"的错法（不报错、不崩溃）：
        //   ① 部分应用模式下忘了查名单 ⇒ 条出现在没勾选的应用上；
        //   ② 全局模式下反而被名单挡住 ⇒ 条该出现时不出现。
        // 两种都只有穷举组合才抓得住。
        System.out.println();
        System.out.println("[14] SysMetrics.monitorVisible 监测范围（全局 / 仅选定）");

        java.util.Set<String> picked = new java.util.HashSet<String>();
        picked.add("com.tencent.tmgp.sgame");
        picked.add("com.android.settings");
        java.util.Set<String> empty = new java.util.HashSet<String>();

        // ① 两个开关是硬闸门：任一关掉都不显示（与范围无关）
        ok("总开关关 → 不显示（全局模式）",
                !SysMetrics.monitorVisible(false, true, false, "com.a", picked));
        ok("总开关关 → 不显示（部分应用模式）",
                !SysMetrics.monitorVisible(false, true, true, "com.tencent.tmgp.sgame", picked));
        ok("监测条开关关 → 不显示",
                !SysMetrics.monitorVisible(true, false, false, "com.a", picked));

        // ② 默认档（全局）**不看名单**：名单为空/为 null 都照样显示
        ok("全局模式：名单为空也显示",
                SysMetrics.monitorVisible(true, true, false, "com.a", empty));
        ok("全局模式：名单为 null 也显示",
                SysMetrics.monitorVisible(true, true, false, "com.a", null));
        ok("全局模式：包名为 null 也显示（不需要知道前台是谁）",
                SysMetrics.monitorVisible(true, true, false, null, picked));

        // ③ 部分应用模式：名单里有没有决定显示 —— 这是功能本体
        ok("部分应用模式：勾选过的应用 → 显示",
                SysMetrics.monitorVisible(true, true, true, "com.tencent.tmgp.sgame", picked));
        ok("部分应用模式：没勾选的应用 → 不显示",
                !SysMetrics.monitorVisible(true, true, true, "com.other.app", picked));
        // ★ 空名单 = 哪里都不显示（用户 2026-10-07 选定的语义，本轮沿用）
        ok("部分应用模式：空名单 → 不显示",
                !SysMetrics.monitorVisible(true, true, true, "com.a", empty));
        ok("部分应用模式：名单为 null → 不显示（不当成「全部」）",
                !SysMetrics.monitorVisible(true, true, true, "com.a", null));
        // ★ 还没采到样（pkg=null）时不猜：猜上一轮那个包会出现在没勾选的应用上
        ok("部分应用模式：还不知道前台是谁 → 不显示（不猜）",
                !SysMetrics.monitorVisible(true, true, true, null, picked));
        ok("部分应用模式：空包名字符串 → 不显示",
                !SysMetrics.monitorVisible(true, true, true, "", picked));
        // 包名是**精确匹配**，不能退化成前缀/包含（否则 com.a 会命中 com.ab）
        ok("部分应用模式：包名精确匹配（不糊到相似包名）",
                !SysMetrics.monitorVisible(true, true, true, "com.tencent.tmgp.sgame2", picked));

        System.out.println();
        System.out.println("[14b] SysMetrics.appMatches 勾选页搜索");
        ok("空查询匹配全部（含 null）",
                SysMetrics.appMatches(null, "设置", "com.android.settings")
                        && SysMetrics.appMatches("", "设置", "com.android.settings")
                        && SysMetrics.appMatches("   ", "设置", "com.android.settings"));
        ok("按中文名匹配", SysMetrics.appMatches("设置", "系统设置", "com.android.settings"));
        ok("按包名匹配（中文搜不到时的退路）",
                SysMetrics.appMatches("settings", "设置", "com.android.settings"));
        ok("忽略大小写", SysMetrics.appMatches("SETTINGS", "设置", "com.android.settings"));
        ok("不匹配就是真不匹配", !SysMetrics.appMatches("qq", "设置", "com.android.settings"));
        ok("标签为 null 不崩", !SysMetrics.appMatches("x", null, "com.a"));
        ok("包名为 null 不崩（标签命中仍算命中）",
                SysMetrics.appMatches("设置", "设置", null));

        System.out.println();
        System.out.println("[14c] SysMetrics.stripWidthDp 吸附竖条宽度（按内容算）");
        // 用户报障："吸附贴边时候数据显示不完整""打开性能监测的时候增大显示面积"。
        // 宽度原本写死 20dp，`5.2G`/`100%` 这种文本必然被 setSingleLine 硬截。
        final float DENS = 2.625f;          // 模拟器/真机实测密度
        ok("没有文本（0px）→ 用最小宽度 20dp",
                Math.abs(SysMetrics.stripWidthDp(0f, DENS) - 20f) < 0.01f,
                "实得 " + SysMetrics.stripWidthDp(0f, DENS));
        ok("极短文本 → 仍是细条 20dp（不会缩到看不见）",
                Math.abs(SysMetrics.stripWidthDp(8f, DENS) - 20f) < 0.01f,
                "实得 " + SysMetrics.stripWidthDp(8f, DENS));
        // `5.2G` 这类中格文本：9sp @2.625 ≈ 45px 宽 → 45/2.625+12 ≈ 29.1dp（比 20 宽）
        float mid = SysMetrics.stripWidthDp(45f, DENS);
        ok("`5.2G` 量级文本 → 比最小宽度宽（原文案会被截，现在放得下）",
                mid > 20.5f && mid < 34f, "实得 " + mid);
        ok("宽度 = 文本宽/密度 + 12dp 内边距",
                Math.abs(mid - (45f / DENS + 12f)) < 0.01f, "实得 " + mid);
        ok("超长文本 → 夹到上限 52dp（否则不像细条）",
                Math.abs(SysMetrics.stripWidthDp(5000f, DENS) - 52f) < 0.01f,
                "实得 " + SysMetrics.stripWidthDp(5000f, DENS));
        ok("密度非法（0）不崩、退回最小宽度",
                Math.abs(SysMetrics.stripWidthDp(100f, 0f) - 20f) < 0.01f);
        ok("负宽度不崩、退回最小宽度",
                Math.abs(SysMetrics.stripWidthDp(-5f, DENS) - 20f) < 0.01f);
        ok("上限常量 > 下限（否则夹取逻辑自相矛盾）",
                SysMetrics.STRIP_MAX_W_DP > SysMetrics.STRIP_MIN_W_DP);

        System.out.println();
        System.out.println("[14d] SysMetrics.clampMonitorGapDp 可调间距（含负值）");
        // 用户要求"距离桌宠太远 + 加一个可调节的选项"：间距可负（压进画布的透明留白）。
        ok("正常值原样返回", Math.abs(SysMetrics.clampMonitorGapDp(8f) - 8f) < 0.01f);
        ok("0 是合法的（紧贴）", Math.abs(SysMetrics.clampMonitorGapDp(0f)) < 0.01f);
        ok("负值合法且不被夹掉（压进桌宠那边）",
                Math.abs(SysMetrics.clampMonitorGapDp(-6f) + 6f) < 0.01f);
        ok("小于下限 → 夹到下限",
                Math.abs(SysMetrics.clampMonitorGapDp(-99f) - SysMetrics.MONITOR_GAP_MIN_DP)
                        < 0.01f);
        ok("大于上限 → 夹到上限",
                Math.abs(SysMetrics.clampMonitorGapDp(99f) - SysMetrics.MONITOR_GAP_MAX_DP)
                        < 0.01f);
        ok("NaN → 返回默认值（绝不把 NaN 传给布局）",
                Math.abs(SysMetrics.clampMonitorGapDp(Float.NaN)
                        - SysMetrics.MONITOR_GAP_DEFAULT_DP) < 0.01f);
        ok("默认值本身在区间内",
                SysMetrics.MONITOR_GAP_DEFAULT_DP >= SysMetrics.MONITOR_GAP_MIN_DP
                        && SysMetrics.MONITOR_GAP_DEFAULT_DP <= SysMetrics.MONITOR_GAP_MAX_DP);
        // 负间距必须真的让侧栏更靠近桌宠（侧栏 x 判据是纯函数，可直接算）
        int farX = SysMetrics.sideBarX(0, 200, 1000, 150, 24);
        int nearX = SysMetrics.sideBarX(0, 200, 1000, 150, -12);
        ok("间距越小侧栏越靠近桌宠（负值更近）", nearX < farX,
                "gap24=" + farX + " gap-12=" + nearX);
        ok("间距 = 0 时侧栏紧贴桌宠内容右缘",
                SysMetrics.sideBarX(0, 200, 1000, 150, 0) == 200,
                "实得 " + SysMetrics.sideBarX(0, 200, 1000, 150, 0));

        // ---- ⑰ 纯本地自定义发言（场景表 + 解析 + 选句，用户 2026-10-07 要求）----
        System.out.println();
        System.out.println("[17] LocalLines 纯本地自定义发言");
        ok("null → 空数组（调用方据此退回内置池）",
                LocalLines.parseLines(null).length == 0);
        ok("只有空白 → 空数组", LocalLines.parseLines("  \n \n\t").length == 0);
        ok("按 \\n 拆行并 trim",
                java.util.Arrays.equals(LocalLines.parseLines(" a \n b "),
                        new String[]{"a", "b"}));
        ok("CRLF 也当分隔（Windows 粘贴进来的是 \\r\\n）",
                java.util.Arrays.equals(LocalLines.parseLines("a\r\nb"),
                        new String[]{"a", "b"}));
        ok("空行丢掉（用户习惯用空行分组）",
                LocalLines.parseLines("a\n\n\n b").length == 2);
        ok("同一句写两遍会去重（否则它的出现概率翻倍）",
                LocalLines.parseLines("a\na\nb").length == 2);
        {
            StringBuilder longLine = new StringBuilder();
            for (int i = 0; i < 120; i++) longLine.append('字');
            String[] one = LocalLines.parseLines(longLine.toString());
            ok("超长行截断到 MAX_LINE_LEN",
                    one.length == 1 && one[0].length() == LocalLines.MAX_LINE_LEN,
                    "实得 " + (one.length == 1 ? one[0].length() : -1));
        }
        {
            StringBuilder manyLines = new StringBuilder();
            for (int i = 0; i < LocalLines.MAX_LINES + 5; i++) {
                manyLines.append("第").append(i).append("句\n");
            }
            ok("条数截到 MAX_LINES",
                    LocalLines.parseLines(manyLines.toString()).length == LocalLines.MAX_LINES,
                    "实得 " + LocalLines.parseLines(manyLines.toString()).length);
        }
        ok("pick(null) → null", LocalLines.pick(null, 0.5) == null);
        ok("pick(空数组) → null", LocalLines.pick(new String[0], 0.5) == null);
        ok("pick r=0 → 第一句",
                "a".equals(LocalLines.pick(new String[]{"a", "b"}, 0.0)));
        ok("pick r=1.0 不越界（取最后一句）",
                "b".equals(LocalLines.pick(new String[]{"a", "b"}, 1.0)));
        ok("pick 负 r 不越界",
                "a".equals(LocalLines.pick(new String[]{"a", "b"}, -1.0)));
        ok("pickWithCustom：自定义非空 → 用自定义",
                "mine".equals(LocalLines.pickWithCustom(
                        new String[]{"mine"}, new String[]{"builtin"}, 0.0)));
        ok("pickWithCustom：自定义为空 → 退回内置",
                "builtin".equals(LocalLines.pickWithCustom(
                        new String[0], new String[]{"builtin"}, 0.0)));
        ok("pickWithCustom：自定义 null → 退回内置",
                "builtin".equals(LocalLines.pickWithCustom(
                        null, new String[]{"builtin"}, 0.0)));
        ok("pickWithCustom：两边都空 → null（桌宠不出声）",
                LocalLines.pickWithCustom(new String[0], new String[0], 0.5) == null);
        ok("pickWithCustom：自定义多句时只从自定义里挑（不混内置）",
                "b".equals(LocalLines.pickWithCustom(
                        new String[]{"a", "b"}, new String[]{"builtin"}, 0.99)));
        ok("pick 单句池恒返回它",
                "only".equals(LocalLines.pick(new String[]{"only"}, 0.99)));
        ok("场景表与中文名一一对应",
                LocalLines.IDS.length == LocalLines.LABELS.length);
        // ★★ 轮次㊲（用户要求「对话台词选项同步动作选项（动作有那些选项 台词就有哪些选项）」）：
        //   场景表**派生自动作清单** ⇒ 这里直接比两张表，任何一边加场景都必须两边同时成立。
        ok("★★ 台词场景 = 动作场景（一一对应，顺序也一致）",
                java.util.Arrays.equals(LocalLines.IDS, SceneRegistry.allIds()),
                "台词 " + LocalLines.IDS.length + " / 动作 " + SceneRegistry.allIds().length);
        ok("18 个场景且互不重复",
                LocalLines.IDS.length == 18
                        && new java.util.HashSet<String>(
                        java.util.Arrays.asList(LocalLines.IDS)).size() == 18,
                "实得 " + LocalLines.IDS.length);
        ok("CHAT 场景已删除（打字入口只走 API）", !LocalLines.isValid("chat"));
        // ★ 轮次㊲：双击/长按**又回到清单里了**，但这次它们真的会说话
        //   （服务里 saySceneLine(DOUBLE_TAP / LONG_PRESS)）⇒ 不是"写了不生效"的入口。
        ok("双击/长按在清单里（这次真的会说话）",
                LocalLines.isValid("double_tap") && LocalLines.isValid("long_press"));
        ok("★ 历史 prefs 键各有映射（改了键名 = 用户写过的台词静默消失）",
                "tap".equals(LocalLines.keyOf(SceneRegistry.CLICK))
                        && "tap_annoyed".equals(LocalLines.keyOf(SceneRegistry.INTERACT))
                        && "lift".equals(LocalLines.keyOf(SceneRegistry.DRAG))
                        && "proactive".equals(LocalLines.keyOf(SceneRegistry.EV_WHISPER))
                        && SceneRegistry.DOCK.equals(LocalLines.keyOf(SceneRegistry.DOCK)));
        ok("★ 历史键本身仍算合法场景（老调用点/老数据不炸）",
                LocalLines.isValid("tap") && LocalLines.isValid("lift"));
        ok("★ 部位 → 台词场景（head/body/tail，认不出的返回 null）",
                SceneRegistry.TAP_HEAD.equals(LocalLines.sceneForPart("head"))
                        && SceneRegistry.TAP_BODY.equals(LocalLines.sceneForPart("body"))
                        && SceneRegistry.TAP_TAIL.equals(LocalLines.sceneForPart("tail"))
                        && LocalLines.sceneForPart("nope") == null);
        ok("★ 部位场景的中文名来自动作清单（点哪演哪、说什么都同一个名字）",
                "点击头部".equals(LocalLines.label(SceneRegistry.TAP_HEAD))
                        && "点击尾巴".equals(LocalLines.label(SceneRegistry.TAP_TAIL)));
        ok("未知场景不合法（写 prefs 前会拦掉脏键）", !LocalLines.isValid("nope"));
        ok("label 未知 id 不返回 null（否则界面会崩）",
                "nope".equals(LocalLines.label("nope")));
        ok("summary：没填 → 「用内置台词」",
                "用内置台词".equals(LocalLines.summary("")));
        ok("summary：填了 → 显示句数",
                LocalLines.summary("a\nb").startsWith("自定义 2 句"));

        // ---- ⑮ 指标累计器（差值法：分段 + 基准 + 回退判无效）----
        System.out.println();
        System.out.println("[15] SysMetrics.Accumulator 指标累计器");
        // 真机格式的分段输出（含 /proc/net/dev 的表头两行 —— 最容易误吃的那种）
        String blockA = SysMetrics.MARK_CPU + "\n"
                + "cpu  675137 37772 758160 3050873 9743 150463 16553 58 0 0\n"
                + "cpu0 168784 9437 189540 762718 2435 37615 4138 14 0 0\n"
                + SysMetrics.MARK_GPU + "\n"
                + "  18407 1020280\n"
                + SysMetrics.MARK_NET + "\n"
                + "Inter-|   Receive                                                |  Transmit\n"
                + " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
                + "    lo: 17287475   30956    0    0    0     0          0         0 17287475   30956    0    0    0     0       0          0\n"
                + "rmnet_ipa0: 1000 5 0 0 0 0 0 0 2000 6 0 0 0 0 0 0\n"
                + SysMetrics.MARK_TMP + "\n"
                + "pa=36385\ncpu_therm=37766\ncpu-1-5=45300\nmmw3=-273000\n";
        // 第二次：CPU busy+100/total+200、GPU +500/+1000、网络 +10000 字节
        String blockB = SysMetrics.MARK_CPU + "\n"
                + "cpu  675237 37772 758160 3050973 9743 150463 16553 58 0 0\n"
                + SysMetrics.MARK_GPU + "\n"
                + "18907 1021280\n"
                + SysMetrics.MARK_NET + "\n"
                + "rmnet_ipa0: 6000 5 0 0 0 0 0 0 7000 6 0 0 0 0 0 0\n"
                + SysMetrics.MARK_TMP + "\n"
                + "cpu-1-5=46000\n";

        SysMetrics.Accumulator acc = new SysMetrics.Accumulator();
        SysMetrics.Snapshot s1 = new SysMetrics.Snapshot();
        s1.fps = 61f;
        acc.update(blockA, 1000L, s1);
        ok("第一次采样：CPU 无基准 => -1", s1.cpu == -1f, "cpu=" + s1.cpu);
        ok("第一次采样：GPU 无基准 => -1", s1.gpu == -1f, "gpu=" + s1.gpu);
        ok("第一次采样：网络无基准 => -1", s1.net == -1f, "net=" + s1.net);
        ok("第一次采样：温度是绝对量 => 直接出数 45.3",
                Math.abs(s1.temp - 45.3f) < 0.01f, "temp=" + s1.temp);
        ok("累计器不动调用方填好的 fps", s1.fps == 61f);

        SysMetrics.Snapshot s2 = new SysMetrics.Snapshot();
        acc.update(blockB, 2000L, s2);
        ok("第二次：CPU 差值 => 50%", Math.abs(s2.cpu - 50f) < 0.01f, "cpu=" + s2.cpu);
        ok("第二次：GPU 差值 => 50%", Math.abs(s2.gpu - 50f) < 0.01f, "gpu=" + s2.gpu);
        ok("第二次：网络 (10000B/1s) => 10000 B/s",
                Math.abs(s2.net - 10000f) < 0.5f, "net=" + s2.net);
        ok("第二次：温度跟着更新 => 46.0",
                Math.abs(s2.temp - 46f) < 0.01f, "temp=" + s2.temp);
        // ★ 采样周期会漂：1.5 秒涨 15000 字节 → 仍是 10000 B/s（不能写死 1000ms）
        //   blockB 时总字节 = 6000+7000 = 13000；blockC 改成 21000+7000 = 28000（+15000）
        SysMetrics.Snapshot s3 = new SysMetrics.Snapshot();
        String blockC = blockB.replace("rmnet_ipa0: 6000", "rmnet_ipa0: 21000");
        acc.update(blockC, 3500L, s3);
        ok("周期漂到 1.5s 也按真实间隔算（10000 B/s）",
                Math.abs(s3.net - 10000f) < 0.5f, "net=" + s3.net);
        // ★ 计数器回退（网卡重连）=> 无效窗口，不是负流量
        SysMetrics.Snapshot s4 = new SysMetrics.Snapshot();
        String blockD = blockC.replace("rmnet_ipa0: 21000", "rmnet_ipa0: 5");
        acc.update(blockD, 4500L, s4);
        ok("计数器回退 => 网络判无效(-1)", s4.net == -1f, "net=" + s4.net);

        // reset 之后第一次又是"只建基准"
        acc.reset();
        SysMetrics.Snapshot s5 = new SysMetrics.Snapshot();
        acc.update(blockA, 9000L, s5);
        ok("reset 后第一次：CPU/GPU/网络都回到 -1",
                s5.cpu == -1f && s5.gpu == -1f && s5.net == -1f);
        ok("reset 后温度照样出数", Math.abs(s5.temp - 45.3f) < 0.01f);
        // 空/垃圾输入绝不能抛
        SysMetrics.Snapshot s6 = new SysMetrics.Snapshot();
        acc.update(null, 100L, s6);
        acc.update("", 100L, s6);
        acc.update("随便什么没有标记的文本\n123 456\n", 100L, s6);
        ok("空/垃圾输入安全（不留脏数）",
                s6.cpu == -1f && s6.gpu == -1f && s6.net == -1f && s6.temp == -1f);
        SysMetrics.Snapshot s7 = new SysMetrics.Snapshot();
        acc.update(blockA, 100L, null);
        ok("out 为 null 不崩", true);

        // ---- ⑯ 监测条形态（开关 × 数据 × 吸附态 × 藏桌宠）----
        System.out.println();
        System.out.println("[16] SysMetrics.barForm 形态判据");
        final int NONE = SysMetrics.BAR_NONE, STRIP = SysMetrics.BAR_STRIP,
                SIDE = SysMetrics.BAR_SIDE;
        // 展开态（未吸附）：开关通过 + 有任意一格 → 侧栏（多项）
        ok("展开态 + 开关通过 → 侧栏（多项）",
                SysMetrics.barForm(true, true, false, false, true, true) == SIDE);
        ok("展开态 + 从未有数据 → 仍然显示侧栏（显示 -- 而不是消失）",
                SysMetrics.barForm(true, false, false, false, true, true) == SIDE);
        ok("展开态 + 中格没设但上/下设了 → 仍然显示侧栏",
                SysMetrics.barForm(true, true, false, false, false, true) == SIDE);
        ok("展开态 + 三格全不显示 → 不显示",
                SysMetrics.barForm(true, true, false, false, false, false) == NONE);
        // ★ 吸附态只看"中格"：竖条上就显示那一项（用户 2026-10-07 修订）
        ok("吸附态 + 有数据 + 中格有设 → 单值竖条",
                SysMetrics.barForm(true, true, false, true, true, true) == STRIP);
        ok("★ 吸附态 + 中格没设（只设了上/下）→ 不占槽位",
                SysMetrics.barForm(true, true, false, true, false, true) == NONE);
        ok("吸附态 + 从未有数据 → 不占槽位（退回普通竖条）",
                SysMetrics.barForm(true, false, false, true, true, true) == NONE);
        // 开关没过（总开关关 / 监测条显示开关关）
        ok("开关没过 → 不显示（展开态）",
                SysMetrics.barForm(false, true, false, false, true, true) == NONE);
        ok("开关没过 → 不显示（吸附态）",
                SysMetrics.barForm(false, true, false, true, true, true) == NONE);
        // 桌宠被临时藏起（进设置页/划卡）—— 侧栏是独立窗口，必须挡
        ok("藏桌宠 → 不显示（哪怕开关通过）",
                SysMetrics.barForm(true, true, true, false, true, true) == NONE);
        ok("藏桌宠 → 吸附态也不显示",
                SysMetrics.barForm(true, true, true, true, true, true) == NONE);

        // ---- ⑰ 动作文件命名（待办 0j 的 A + B：前缀当场景 / 自动合成配置）----
        //
        // ★ 这一节全是**真函数**断言（不是 grep 源码）：ClipNaming 是纯类，
        //   编的就是打进 APK 的那一份。为什么值得单独一节：
        //   "文件名前缀决定场景"这条规则一旦错，用户看到的是**动作跑到了别的场景**
        //   （甚至"加了没反应"），而在设备上只能靠一个个点着试，没法穷举 18 个场景。
        System.out.println();
        System.out.println("[17] ClipNaming 动作文件名 / 自动合成配置（0j-A/B）");

        ok("认得动图与静态图（webp/gif/png/jpg/jpeg，大小写不敏感）",
                ClipNaming.isImageName("a.webp") && ClipNaming.isImageName("a.GIF")
                        && ClipNaming.isImageName("a.png") && ClipNaming.isImageName("a.JPG")
                        && ClipNaming.isImageName("a.jpeg"));
        ok("不认视频/文本/无扩展名/骗人的双扩展名",
                !ClipNaming.isImageName("a.mp4") && !ClipNaming.isImageName("a.txt")
                        && !ClipNaming.isImageName("README") && !ClipNaming.isImageName("a.webp.txt")
                        && !ClipNaming.isImageName(null));
        ok("extOf：小写且不带点（A.PNG => png）", "png".equals(ClipNaming.extOf("A.PNG")));
        ok("extOf：没有扩展名 / 结尾是点 => 空串",
                ClipNaming.extOf("没有扩展名").isEmpty() && ClipNaming.extOf("a.").isEmpty());
        ok("baseNameOf：去目录 + 去扩展名（anim/clips/待机-1.webp => 待机-1）",
                "待机-1".equals(ClipNaming.baseNameOf("anim/clips/待机-1.webp"))
                        && "待机-1".equals(ClipNaming.baseNameOf("anim\\clips\\待机-1.webp")));
        ok("safeClipId：剥掉文件系统非法字符与路径",
                "我的猫".equals(ClipNaming.safeClipId("我的猫.png"))
                        && "abc".equals(ClipNaming.safeClipId("a:b*c.png"))
                        && ClipNaming.safeClipId("").isEmpty()
                        && ClipNaming.safeClipId("...").isEmpty()
                        && "png".equals(ClipNaming.safeClipId(".png")));
        {
            StringBuilder longName = new StringBuilder();
            for (int i = 0; i < 60; i++) longName.append('字');
            ok("safeClipId：截到 40 字（文件名过长会撑坏目录）",
                    ClipNaming.safeClipId(longName + ".png").length() == 40);
        }
        ok("extFor：扩展名认不出就按 MIME 猜，再认不出用 png",
                "png".equals(ClipNaming.extFor("x.png", null))
                        && "jpg".equals(ClipNaming.extFor("x", "image/jpeg"))
                        && "webp".equals(ClipNaming.extFor("x", "image/webp"))
                        && "png".equals(ClipNaming.extFor("x", null))
                        && "gif".equals(ClipNaming.extFor("x.mp4", "image/gif")));
        ok("uniqueId：没占用就用原名", "猫".equals(ClipNaming.uniqueId("猫", new String[]{"狗"})));
        ok("uniqueId：重名加序号，绝不覆盖已有动作",
                "猫_2".equals(ClipNaming.uniqueId("猫", new String[]{"猫"}))
                        && "猫_3".equals(ClipNaming.uniqueId("猫", new String[]{"猫", "猫_2"})));

        // —— B：文件名前缀 → 场景 ——
        ok("待机-xxx → idle", SceneRegistry.IDLE.equals(ClipNaming.sceneOf("待机-1.webp")));
        ok("待机呼吸休闲 → idle（真实默认素材名，不带连字符也要认）",
                SceneRegistry.IDLE.equals(ClipNaming.sceneOf("待机呼吸休闲.webp")));
        ok("★ 最长优先：待机随机池-xxx → pool（不能被「待机」抢走）",
                SceneRegistry.POOL.equals(ClipNaming.sceneOf("待机随机池-1.webp")));
        ok("★ 最长优先：点击头部-xxx → tap_head（不能被「点击」抢走）",
                SceneRegistry.TAP_HEAD.equals(ClipNaming.sceneOf("点击头部-1.webp")));
        ok("点击-xxx → click / 互动-xxx → interact / 拖拽-xxx → drag",
                SceneRegistry.CLICK.equals(ClipNaming.sceneOf("点击-1.webp"))
                        && SceneRegistry.INTERACT.equals(ClipNaming.sceneOf("互动-1.gif"))
                        && SceneRegistry.DRAG.equals(ClipNaming.sceneOf("拖拽-1.webp")));
        ok("漫游-xxx → roam / 转向-xxx → turn / 贴边-xxx → dock",
                SceneRegistry.ROAM.equals(ClipNaming.sceneOf("漫游-1.webp"))
                        && SceneRegistry.TURN.equals(ClipNaming.sceneOf("转向-1.webp"))
                        && SceneRegistry.DOCK.equals(ClipNaming.sceneOf("贴边-1.webp")));
        ok("双击-xxx → double_tap / 长按-xxx → long_press",
                SceneRegistry.DOUBLE_TAP.equals(ClipNaming.sceneOf("双击-1.webp"))
                        && SceneRegistry.LONG_PRESS.equals(ClipNaming.sceneOf("长按-1.webp")));
        ok("简称（唯一包含）：头-/身-/尾-xxx → tap_head / tap_body / tap_tail",
                SceneRegistry.TAP_HEAD.equals(ClipNaming.sceneOf("头-被戳到.png"))
                        && SceneRegistry.TAP_BODY.equals(ClipNaming.sceneOf("身-1.png"))
                        && SceneRegistry.TAP_TAIL.equals(ClipNaming.sceneOf("尾-1.png")));
        ok("简称（唯一前缀）：整点-/节日-/余额-/工作- → 对应场景",
                SceneRegistry.HOUR.equals(ClipNaming.sceneOf("整点-报时.webp"))
                        && SceneRegistry.FESTIVAL.equals(ClipNaming.sceneOf("节日-春节.webp"))
                        && SceneRegistry.EV_BALANCE.equals(ClipNaming.sceneOf("余额-分文不剩.webp"))
                        && SceneRegistry.EV_WORK.equals(ClipNaming.sceneOf("工作-码字.webp")));
        ok("碎碎念-xxx → whisper（事件场景也走前缀）",
                SceneRegistry.EV_WHISPER.equals(ClipNaming.sceneOf("碎碎念-发呆.webp")));
        ok("不带前缀 → pool（动作库 / 待机随机池）",
                SceneRegistry.POOL.equals(ClipNaming.sceneOf("小猫咪.png"))
                        && SceneRegistry.POOL.equals(ClipNaming.sceneOf("abc-1.webp")));
        ok("★ 简称「唯一才认」：待-1 有歧义（待机 / 待机随机池）⇒ 落 pool，不猜",
                SceneRegistry.POOL.equals(ClipNaming.sceneOf("待-1.webp")));
        ok("带目录也不影响（anim/clips/点击-1.webp）",
                SceneRegistry.CLICK.equals(ClipNaming.sceneOf("anim/clips/点击-1.webp")));
        ok("空文件名不崩，落 pool", SceneRegistry.POOL.equals(ClipNaming.sceneOf("")));
        {
            // ★★ 本套件最关键的一条：前缀表**派生自 SceneRegistry 的中文标签**。
            //    如果谁把场景名另抄一份常量表，新增场景就会"前缀永远进 pool"且毫无报错。
            String bad = null;
            for (String id : SceneRegistry.allIds()) {
                String f = SceneRegistry.label(id) + "-测试.webp";
                String got = ClipNaming.sceneOf(f);
                if (!id.equals(got)) { bad = id + " 期望 " + id + " 实得 " + got; break; }
            }
            ok("★ 前缀表派生自 SceneRegistry 标签：18 个场景的中文名前缀都命中它自己",
                    bad == null, bad == null ? "18/18" : bad);
        }

        // —— A：合成 2.0 配置 ——
        String synth = ClipNaming.synthesizePack(new String[]{
                "待机呼吸休闲.webp", "点击-摸头.webp", "拖拽-挣扎.gif",
                "头-被戳到.png", "整点-报时.webp", "小猫咪.png"});
        ok("合成配置非空且带 2.0 的 scenes 节点",
                synth.contains("\"version\": 2") && synth.contains("\"scenes\""));
        ok("合成的 idle / click / drag 正确",
                synth.contains("\"idle\": [\"待机呼吸休闲\"]")
                        && synth.contains("\"click\": [\"点击-摸头\"]")
                        && synth.contains("\"drag\": [\"拖拽-挣扎\"]"));
        ok("合成的 tap_head / hour / pool 正确",
                synth.contains("\"tap_head\": [\"头-被戳到\"]")
                        && synth.contains("\"hour\": [\"整点-报时\"]")
                        && synth.contains("\"pool\": [\"小猫咪\"]"));
        ok("★ 有前缀的文件只进自己的场景，不再重复进 pool（否则待机随机会抽到点击/拖拽）",
                !synth.contains("\"pool\": [\"小猫咪\", \"点击-摸头\"")
                        && !synth.contains("[\"小猫咪\", \"待机呼吸休闲\"")
                        && !synth.contains("\"pool\": [\"待机呼吸休闲\""));
        ok("空清单 => 空串（调用方按「包里没有动作文件」报错）",
                ClipNaming.synthesizePack(new String[0]).isEmpty()
                        && ClipNaming.synthesizePack(null).isEmpty());
        String noIdleName = ClipNaming.synthesizePack(new String[]{"点击-1.webp", "小猫咪.png"});
        ok("★ 一个都不含「待机」时 idle 兜底取第一个（桌宠总要有个站着的动作）",
                noIdleName.contains("\"idle\": [\"点击-1\"]"));
        ok("合成里不会冒出没绑任何动作的空场景（不会写 \"interact\": []）",
                !noIdleName.contains("[]"));
        ok("重复文件名只登记一次（idle 与 pool 各一次，不是四次）",
                countOf(ClipNaming.synthesizePack(new String[]{"猫.png", "猫.webp"}), "\"猫\"") == 2,
                "出现 " + countOf(ClipNaming.synthesizePack(
                        new String[]{"猫.png", "猫.webp"}), "\"猫\"") + " 次");
        ok("scenesIn：报得出归了几个场景",
                ClipNaming.scenesIn(new String[]{"待机-1.webp", "点击-1.webp", "猫.png"}).length == 3);
        ok("jsonEscape：引号/反斜杠/换行不会把配置写坏",
                "a\\\"b".equals(ClipNaming.jsonEscape("a\"b"))
                        && "a\\\\b".equals(ClipNaming.jsonEscape("a\\b")));

        // ---- ⑱ 角色包文本生成（轮次㉑：按当前绑定导出要写 pack.json / lines.json）----
        //
        // ★ 导出走的是"有效绑定 → 文本"这条路，而"文本对不对"在设备上只能靠导入回来验。
        //   抽成纯类（PackFormat）之后这里能穷举：权重写法、空场景、转义、台词过滤。
        System.out.println();
        System.out.println("[18] PackFormat 角色包文本（按当前绑定导出）");

        java.util.LinkedHashMap<String, java.util.List<PackFormat.Item>> sc =
                new java.util.LinkedHashMap<>();
        sc.put("idle", java.util.Arrays.asList(new PackFormat.Item("站", 1)));
        sc.put("drag", java.util.Arrays.asList(
                new PackFormat.Item("拖A", 1), new PackFormat.Item("拖B", 3)));
        sc.put("click", new java.util.ArrayList<PackFormat.Item>());     // 空场景
        java.util.LinkedHashMap<String, java.util.List<PackFormat.Item>> ev =
                new java.util.LinkedHashMap<>();
        ev.put("whisper", java.util.Arrays.asList(new PackFormat.Item("碎碎念", 1)));
        String pj = PackFormat.packJson(sc, ev);
        ok("pack.json 是 2.0 形状（version + scenes + events）",
                pj.contains("\"version\": 2") && pj.contains("\"scenes\"") && pj.contains("\"events\""));
        ok("权重 1 写纯名字（文件更可读）", pj.contains("\"idle\": [\"站\"]"));
        ok("★ 权重 >1 写成 {id,weight}（导出不能把 ×N 抹掉）",
                pj.contains("{\"id\": \"拖B\", \"weight\": 3}"), pj);
        ok("★ 空场景不写出来（不产生 ``\"click\": []`` 这种噪音）",
                !pj.contains("\"click\""));
        ok("事件场景照写（events.whisper）", pj.contains("\"whisper\": [\"碎碎念\"]"));
        String emptyCfg = PackFormat.packJson(null, null);
        ok("两张表都空时仍是合法形状的配置（导入器会并到内置基底上）",
                emptyCfg.contains("\"version\": 2") && emptyCfg.contains("\"scenes\"")
                        && emptyCfg.contains("\"events\"") && !emptyCfg.contains("[]"), emptyCfg);

        java.util.LinkedHashMap<String, java.util.List<String>> allLines =
                new java.util.LinkedHashMap<>();
        allLines.put("拖A", java.util.Arrays.asList("台词1", "台词2"));
        allLines.put("不该带的", java.util.Arrays.asList("无关台词"));
        String lj = PackFormat.linesJson(java.util.Arrays.asList("拖A"), allLines);
        ok("★ 台词只保留被包含动作的", lj != null && lj.contains("台词1") && !lj.contains("无关台词"), lj);
        ok("没有任何台词时返回 null（调用方就不写这个文件）",
                PackFormat.linesJson(java.util.Arrays.asList("没有台词的"), allLines) == null);
        ok("ids 为空时返回 null", PackFormat.linesJson(new java.util.ArrayList<String>(), allLines) == null);
        ok("★ 并集去重保序", java.util.Arrays.equals(
                PackFormat.union(PackFormat.union(new java.util.ArrayList<String>(),
                                java.util.Arrays.asList("a", "b")),
                        java.util.Arrays.asList("b", "c")).toArray(new String[0]),
                new String[]{"a", "b", "c"}));
        ok("纯名字表 → Item 表（权重 1）",
                PackFormat.plainGroups(java.util.Collections.singletonMap(
                        "idle", java.util.Arrays.asList("站"))).get("idle").get(0).weight == 1);

        // ---- ⑲ 角色中心：切换 / 删除 / **新增** 的纯逻辑（P3 起，轮次㉛ 重做）----
        //
        // ★ 轮次㉛ 用户要求「不留任何角色，只靠导入」+「可以新增角色」⇒
        //   "内置角色"这个概念没了：列表可以为空、哨兵不是角色、删完落到剩下的第一个。
        //   三件最容易错的事（标错当前角色、把哨兵当角色删、删完指向悬空 id）都抽成纯函数。
        System.out.println();
        System.out.println("[19] CharPicker 角色中心（切换 / 删除 / 新增）");

        ok("当前角色标 ●、其他标 ○",
                CharPicker.rowLabel("小深", true).startsWith("● ")
                        && CharPicker.rowLabel("小深", false).startsWith("○ "));
        ok("★ 行文案里不再有「（内置）」这种东西",
                !CharPicker.rowLabel("小深", false).contains("内置"));
        ok("当前角色那一行报出角色个数",
                CharPicker.currentRowLabel("小深", 3).contains("共 3 个"));
        ok("★ 一个角色都没有时那一行说人话（不是「共 0 个」）",
                CharPicker.currentRowLabel("（没有角色）", 0).contains("还没有"));
        ok("空列表的引导文案在", CharPicker.emptyHint().contains("新增角色")
                && CharPicker.emptyHint().contains("角色包"));
        ok("★ 哨兵（没有角色）不是角色 ⇒ 不可删；真实角色可删",
                !CharPicker.deletable("builtin:default") && CharPicker.deletable("我的猫"));
        ok("空 / null id 不可删", !CharPicker.deletable(null) && !CharPicker.deletable("  "));
        ok("★ 删的正好是当前角色 ⇒ 落到**剩下的第一个**（不是回什么内置）",
                "猫B".equals(CharPicker.currentAfterDelete("猫A", "猫A",
                        java.util.Arrays.asList("猫B", "猫C"))));
        ok("删的不是当前角色 ⇒ 当前角色不动",
                "我的猫".equals(CharPicker.currentAfterDelete("我的猫", "p3test",
                        java.util.Arrays.asList("我的猫"))));
        ok("★ 一个都不剩 ⇒ 回哨兵（= 没有角色，素材层用内置兜底）",
                "builtin:default".equals(CharPicker.currentAfterDelete("猫A", "猫A",
                        java.util.Collections.<String>emptyList()))
                        && "builtin:default".equals(CharPicker.currentAfterDelete("", "x", null)));
        ok("★ 点自己不算切换（省一次 reloadPet + 整页重建）",
                !CharPicker.switchNeeded("我的猫", "我的猫")
                        && CharPicker.switchNeeded("我的猫", "p3test"));
        ok("点空 id 不算切换", !CharPicker.switchNeeded("我的猫", null));
        ok("删除列表里不再有锁图标（没有删不掉的角色了）",
                !CharPicker.deleteRowLabel("我的猫").contains("不能删")
                        && CharPicker.deleteRowLabel("我的猫").contains("我的猫"));
        ok("可删清单只滤掉哨兵",
                CharPicker.deletableIds(java.util.Arrays.asList(
                        "builtin:default", "我的猫")).size() == 1);
        ok("★ 删除确认文案写清删掉什么（动作 / 形象 / 绑定 + 删了就没了）",
                CharPicker.deleteConfirmMessage("我的猫").contains("形象")
                        && CharPicker.deleteConfirmMessage("我的猫").contains("绑定")
                        && CharPicker.deleteConfirmMessage("我的猫").contains("删了就没了"));

        // 新增角色（轮次㉛）
        ok("★ 新增：名字不能为空 / 不能重名 / 不能太长",
                CharPicker.nameError(java.util.Collections.<String>emptyList(), "  ") != null
                        && CharPicker.nameError(java.util.Arrays.asList("我的猫"), "我的猫") != null
                        && CharPicker.nameError(java.util.Arrays.asList("我的猫"), "我的狗") == null);
        ok("★ 新增：id 由名字生成、并避开已有 id（同名不覆盖别人的角色）",
                "我的猫".equals(CharPicker.newId("我的猫", java.util.Collections.<String>emptyList()))
                        && "我的猫_2".equals(CharPicker.newId("我的猫",
                                java.util.Arrays.asList("我的猫"))));
        ok("★ 新增：净化掉路径分隔符（id 是目录名，不能带 / \\ :）",
                CharPicker.newId("a/b:c\\d", null).indexOf('/') < 0
                        && CharPicker.newId("a/b:c\\d", null).indexOf(':') < 0
                        && CharPicker.newId("a/b:c\\d", null).indexOf('\\') < 0);
        ok("★ 新增：名字净化后为空 ⇒ 退回 char 前缀（不是空目录名）",
                "char".equals(CharPicker.newId("", null))
                        && "char".equals(CharPicker.newId(null, null))
                        && "char".equals(CharPicker.newId("default", null)));
        ok("净化判据只有一份（ActionMenuConfig.sanitize 转发到这里）",
                "a_b".equals(CharPicker.sanitizeId("a:b")) && "default".equals(CharPicker.sanitizeId("")));
        ok("新增成功的提示告诉用户下一步干什么",
                CharPicker.createdHint("我的猫").contains("添加动作")
                        && CharPicker.createdHint("我的猫").contains("形象"));

        // ---- ⑳ 形象来源的纯逻辑（P5：形象来源纳入角色模型）----
        //
        // ★ P5 之前"这只桌宠长什么样"是**全局**的（四个 pref + filesDir/custom_pet.png 等），
        //   切了角色形象不跟着走。这里钉四件最容易错的事：取值合法化、素材缺失回退谁、
        //   "关掉一个来源 ≠ 关掉全部"、清单行怎么写。
        System.out.println();
        System.out.println("[20] Appearance 形象来源（P5：按角色）");

        ok("默认是内置角色包", Appearance.DEFAULT.equals(Appearance.BUILTIN));
        ok("空 / null / 脏值一律回内置（绝不返回 null）",
                Appearance.BUILTIN.equals(Appearance.normalize(null))
                        && Appearance.BUILTIN.equals(Appearance.normalize(""))
                        && Appearance.BUILTIN.equals(Appearance.normalize("live2d")));
        ok("五种来源都认", Appearance.valid("builtin") && Appearance.valid("image")
                && Appearance.valid("anim") && Appearance.valid("model")
                && Appearance.valid("custom_l2d"));
        ok("★ 来源 id 就是 FloatingService 的 mode 值（少一层映射就少一处漂移）",
                "custom_l2d".equals(Appearance.L2D) && "model".equals(Appearance.MODEL)
                        && "anim".equals(Appearance.ANIM) && "image".equals(Appearance.IMAGE)
                        && "builtin".equals(Appearance.BUILTIN));
        ok("内置不需要素材文件，其余四种都要",
                !Appearance.needsFile(Appearance.BUILTIN)
                        && Appearance.needsFile(Appearance.IMAGE)
                        && Appearance.needsFile(Appearance.L2D));
        ok("内置不算「自定义形象」",
                !Appearance.isCustom(Appearance.BUILTIN) && Appearance.isCustom(Appearance.IMAGE));

        // 素材缺失 ⇒ 回退内置（不是留一块空白）
        ok("★ 选了图片但文件没了 ⇒ 回内置",
                Appearance.BUILTIN.equals(Appearance.resolve(Appearance.IMAGE,
                        false, false, false, false)));
        ok("素材在就用它",
                Appearance.IMAGE.equals(Appearance.resolve(Appearance.IMAGE,
                        true, false, false, false)));
        ok("★ 四种来源各回退各的（Live2D 少了 meta.json 也不许留空白）",
                Appearance.BUILTIN.equals(Appearance.resolve(Appearance.L2D, true, true, true, false))
                        && Appearance.BUILTIN.equals(Appearance.resolve(Appearance.MODEL,
                        true, true, false, true)));
        ok("内置永远可用（不需要任何素材）",
                Appearance.BUILTIN.equals(Appearance.resolve(Appearance.BUILTIN,
                        false, false, false, false)));

        // ★ 关掉一个来源 ≠ 关掉全部（老代码里"先开 anim 再关 image"的顺序 bug）
        ok("★ 关掉**不是当前**的那个 ⇒ 当前不动（于是顺序无关）",
                Appearance.ANIM.equals(Appearance.afterDisable(Appearance.ANIM, Appearance.IMAGE)));
        ok("★ 关掉的正好是当前那个 ⇒ 回内置",
                Appearance.BUILTIN.equals(Appearance.afterDisable(Appearance.IMAGE, Appearance.IMAGE)));
        ok("关掉内置 ⇒ 还是内置", Appearance.BUILTIN.equals(
                Appearance.afterDisable(Appearance.BUILTIN, Appearance.IMAGE)));

        // 清单行
        ok("当前标 ●、其他标 ○",
                Appearance.rowLabel(Appearance.IMAGE, true, true).startsWith("\u25CF ")
                        && Appearance.rowLabel(Appearance.ANIM, false, true).startsWith("\u25CB "));
        ok("★ 素材没导入的标「（未导入）」但**不隐藏**",
                Appearance.rowLabel(Appearance.MODEL, false, false).contains("（未导入）")
                        && Appearance.rowLabel(Appearance.MODEL, false, true).contains("模型包"));
        ok("设置页那一行写清「当前角色」",
                Appearance.sourceRowLabel(Appearance.L2D).contains("Live2D")
                        && Appearance.sourceRowLabel(Appearance.L2D).contains("当前角色"));
        ok("每种「没素材」的提示都说清下一步点什么",
                Appearance.missingHint(Appearance.IMAGE).contains("上传图片")
                        && Appearance.missingHint(Appearance.L2D).contains("导入 Live2D"));
        ok("★ 恢复默认形象的文案写清「只影响这个角色」",
                Appearance.resetConfirmMessage("我的猫", Appearance.IMAGE).contains("我的猫")
                        && Appearance.resetConfirmMessage("我的猫", Appearance.IMAGE)
                        .contains("其他角色的形象不受影响"));
        ok("★ 给用户看的正文里不许出现 markdown 的 `**`（星号会原样显示）",
                !Appearance.resetConfirmMessage("我的猫", Appearance.IMAGE).contains("**")
                        && !Appearance.missingHint(Appearance.IMAGE).contains("**")
                        && !Appearance.sourceRowLabel(Appearance.IMAGE).contains("**")
                        && !Appearance.rowLabel(Appearance.IMAGE, true, true).contains("**"));

        System.out.println();
        System.out.println("[21] ContentInset 贴边判据（内容留白 + 窗口可达范围）");
        //
        // ★ 背景（2026-10-08 用户报障「宠物悬浮窗状态无法贴边靠边（非吸附状态）」）：
        //   桌宠窗口是 16:9 的固定框，里面的东西常常填不满（内置包 clip 左右各留 33%、
        //   上传的方图左右各留 138px）。所以"贴边"必须让**内容**贴到屏幕边，
        //   也就是允许窗口挂出屏幕外 = 内容留白那么多。
        //   这里钉三件事：画布→视口的换算、换动作时**不许把已知边距退回 null**、
        //   以及窗口 x 可达范围（拖动/吸附/热区/漫游四处共用）。

        // ① 画布与视口长宽比相同 ⇒ 留白比例原样搬过来（内置包 640×360 → 630×354）
        float[] same = ContentInset.of(640, 360, new float[]{0.25f, 0.25f, 0.1f, 0.1f},
                630, 354, true);
        ok("画布=视口长宽比：留白原样（0.25 → 158px）",
                near(same[0], 0.25f) && near(same[1], 0.25f)
                        && Math.round(same[0] * 630) == 158);

        // ② ★ 方图放进 16:9 视口：FIT_CENTER 后左右各空 (630-354)/2 = 138px
        //    这一条就是"上传图片贴不到边"的判据 —— 少了它，138px 永远补不回来。
        float[] square = ContentInset.of(500, 500, new float[]{0f, 0f, 0f, 0f},
                630, 354, true);
        ok("★ 方图在 16:9 视口里：左右各留 138px（= 等比缩放的居中留白）",
                Math.round(square[0] * 630) == 138 && Math.round(square[1] * 630) == 138);
        ok("★ 方图上下没有留白（它按高度铺满）",
                near(square[2], 0f) && near(square[3], 0f));

        // ③ 拉伸铺满（FIT_XY）时没有居中留白
        float[] stretched = ContentInset.of(500, 500, new float[]{0f, 0f, 0f, 0f},
                630, 354, false);
        ok("FIT_XY（拉伸）没有居中留白",
                near(stretched[0], 0f) && near(stretched[1], 0f));

        // ④ 画布留白 + 居中留白要**叠加**
        float[] both = ContentInset.of(500, 500, new float[]{0.1f, 0.1f, 0f, 0f},
                630, 354, true);
        ok("★ 画布内留白与居中留白叠加（138 + 354×0.1 = 173px）",
                Math.round(both[0] * 630) == 173);

        // ⑤ 竖构图（角色包格式文档推荐 ~500×556）在 16:9 视口里上下铺满、左右留白
        float[] portrait = ContentInset.of(500, 556, new float[]{0f, 0f, 0f, 0f},
                630, 354, true);
        ok("★ 竖构图 clip 左右各留 (630-318)/2 = 156px（漏了它贴边就差 156px）",
                Math.round(portrait[0] * 630) == 156);

        // ⑥ 参数不合法不许抛异常、也不许返回 null
        ok("脏参数回「无留白」而不是 null",
                ContentInset.of(0, 0, null, 630, 354, true) == ContentInset.NONE
                        && ContentInset.of(640, 360, new float[]{0f, 0f, 0f, 0f}, 0, 0, true)
                        == ContentInset.NONE);
        // ⑦ 比例越界会被夹住（手改素材也不许算出发疯的窗口位置）
        //    注意别拿 near(0f) 去比：画布 640×360 与视口 630×354 长宽比差一丝，
        //    居中留白会有 0.3px 的量级（这就是"断言自己写太紧"的坑）。
        float[] clamped = ContentInset.of(640, 360, new float[]{-1f, 5f, 0f, 0f},
                630, 354, true);
        ok("留白比例越界会被夹到 0~1（-1 ⇒ 左 0 / 5 ⇒ 右 1）",
                clamped[0] >= 0f && clamped[0] < 0.01f
                        && clamped[1] > 0.99f && clamped[1] <= 1f);

        // ⑦ ★ 铁律：换动作时**不许**把已知边距退回 null
        float[] known = new float[]{0.33f, 0.33f, 0.17f, 0.08f};
        ok("★ 新值算不出来 ⇒ 保留旧值（绝不用 null 覆盖）",
                ContentInset.keep(known, null) == known);
        float[] fresh = new float[]{0.1f, 0.1f, 0.1f, 0.1f};
        ok("新值算得出来 ⇒ 用新值", ContentInset.keep(known, fresh) == fresh);
        ok("usable：null / 短数组 / NaN / 负数都不算可用",
                !ContentInset.usable(null) && !ContentInset.usable(new float[]{0f, 0f})
                        && !ContentInset.usable(new float[]{0f, 0f, Float.NaN, 0f})
                        && !ContentInset.usable(new float[]{-0.1f, 0f, 0f, 0f})
                        && ContentInset.usable(known));

        // ⑧ 窗口 x 可达范围：内置包实测留白 L=0.3344 / R=0.3359，窗口 630，屏宽 1080
        int[] r1 = ContentInset.xRange(1080, 630, new float[]{0.334375f, 0.3359375f, 0.17f, 0.08f});
        ok("★ 内置包：左可达 -210（内容左缘正好贴屏幕左缘）", r1[0] == -210);
        ok("★ 内置包：右可达 661（内容右缘正好贴屏幕右缘）", r1[1] == 661);
        ok("★ 贴边=窗口挂出屏外，不是窗口贴屏幕边（否则差 210px）",
                r1[0] < 0 && r1[1] > 1080 - 630);

        // ⑨ 方图：挂出屏外的量要**多出**那 138px 的居中留白
        int[] r2 = ContentInset.xRange(1080, 630,
                ContentInset.of(500, 500, new float[]{0f, 0f, 0f, 0f}, 630, 354, true));
        ok("★ 方图：左可达 -138 / 右可达 588（比「窗口贴边」多让 138px）",
                r2[0] == -138 && r2[1] == 588);

        // ⑩ 没有留白（模型包 / Live2D / 量不出来）⇒ 退回"窗口贴屏幕边"
        int[] r3 = ContentInset.xRange(1080, 630, null);
        ok("无留白时范围就是 0 ~ 450（旧行为，保证不会把桌宠甩出屏幕）",
                r3[0] == 0 && r3[1] == 450);
        ok("窗口比屏幕还宽时不许把范围算反（maxX < minX ⇒ 取 minX）",
                ContentInset.xRange(300, 630, null)[1] == 0);

        System.out.println();
        System.out.println("[22] PackFormat 包内目录判据（形象随角色包走，待办 0m ①）");
        //
        // ★ 背景：导出/导入要带上**形象**（P5 把它放在 filesDir/appearance/<角色 id>/）。
        //   导入器在 anim/clips/ 找不到动作时会**全树扫**，扫的时候必须跳过辅助目录 ——
        //   漏一个就会把形象图/表情图当成"动作"（用户会在动作库里看到自己的脸）。
        //   判据集中在这里，就是为了"加了新辅助目录忘了同步"这件事只有一个地方要改。

        ok("形象目录名与运行期一致（包内 appearance/ ↔ filesDir/appearance/）",
                "appearance".equals(PackFormat.APPEARANCE_DIR));
        ok("辅助目录清单里有 memes 与 appearance",
                PackFormat.AUX_DIRS.length == 2
                        && PackFormat.isAuxDir("memes") && PackFormat.isAuxDir("appearance"));
        ok("★ 大小写 / 前后空格都要认（zip 里可能是 Memes/）",
                PackFormat.isAuxDir("Memes") && PackFormat.isAuxDir("APPEARANCE")
                        && PackFormat.isAuxDir("  memes  "));
        ok("动作目录 / 其它目录不算辅助目录（不许误伤 anim、clips、model）",
                !PackFormat.isAuxDir("anim") && !PackFormat.isAuxDir("clips")
                        && !PackFormat.isAuxDir("model") && !PackFormat.isAuxDir("l2d")
                        && !PackFormat.isAuxDir(null) && !PackFormat.isAuxDir(""));
        ok("★ 反例：近似的名字不许被当成辅助目录（memes2 / appearance_old）",
                !PackFormat.isAuxDir("memes2") && !PackFormat.isAuxDir("appearance_old"));

        System.out.println();
        System.out.println("[23] 联网检索（必应免 Key 源 / 抓正文 / 提示词拼装）");
        //
        // ★ 待办 0d 的路线 C：App 先搜再拼提示词（不依赖 function calling）。
        //   源选必应也是**实测**的结果：DDG 与维基百科在国内网络连不上、
        //   百度是 JS 渲染（静态 HTML 里没有结果）—— 见 WebSearch 的类注释。
        // ★ 这里的解析断言跑在**真实报文**上（fixtures/ 是抓下来原样存的）：
        //   对面格式一变，这套就红，而不是等用户发现"它说查不到"。

        String rss = readFixture("bing-search.rss");
        ok("★ 读到真实必应 RSS fixture（离线跑，不联网）", rss != null && rss.length() > 500,
                rss == null ? "fixtures/bing-search.rss 缺失" : (rss.length() + " 字符"));

        if (rss != null) {
            java.util.List<WebSearch.Hit> hits = WebSearch.parseRss(rss, 5);
            ok("★ 真实 RSS 解出多条结果", hits.size() >= 3, "解出 " + hits.size() + " 条");
            boolean allOk = !hits.isEmpty();
            for (WebSearch.Hit h : hits) {
                if (!h.usable() || !WebSearch.isHttp(h.url)) allOk = false;
            }
            ok("每条都有 http(s) 链接 + 标题或摘要", allOk);
            ok("★ 字段里不再残留标签 / 实体（&# / &amp; 都不能有）", !anyTags(hits));
            ok("条数上限生效（max=2 ⇒ 只取 2 条）", WebSearch.parseRss(rss, 2).size() == 2);
            ok("★ 解不出结果时返回**空表**（宁可说没查到，也不许编）",
                    WebSearch.parseRss("这不是 rss", 5).isEmpty()
                            && WebSearch.parseRss(null, 5).isEmpty());
        }

        String bhtml = readFixture("bing-search.html");
        ok("★ 读到真实必应网页版 fixture", bhtml != null && bhtml.length() > 500);
        if (bhtml != null) {
            java.util.List<WebSearch.Hit> hits = WebSearch.parseHtml(bhtml, 5);
            ok("网页版兜底也能解出结果", hits.size() >= 1, "解出 " + hits.size() + " 条");
            boolean allOk = !hits.isEmpty();
            for (WebSearch.Hit h : hits) {
                // ★ 链接必须取 h2 之前的**最后一个** href：结果块开头还有一条"站点信息"
                //   链接（指向站点根），取错了就会把每条都指到首页
                if (!h.usable() || !WebSearch.isHttp(h.url)
                        || h.url.equals("http://www.bing.com/") || h.url.equals("https://www.bing.com/")) {
                    allOk = false;
                }
            }
            ok("★ 取到的是文章地址（不是站点根 / 不是 bing 自己的链接）", allOk);
            ok("字段里没有残留标签", !anyTags(hits));
        }

        // —— 合成的边界用例（真实报文里不一定有 CDATA / 脏数据）——
        ok("CDATA 外壳会被剥掉",
                "x<y".equals(WebSearch.tagText("<t><![CDATA[x<y]]></t>", "t")));
        ok("实体解码：命名实体 + 数字实体 + 十六进制（&nbsp; 归一成普通空格，正文里更好排）",
                "A&B'C\u2019 ".equals(WebSearch.decodeEntities("A&amp;B&#39;C&#x2019;&nbsp;")));
        ok("stripTags 连 script/style 里的内容一起去掉",
                WebSearch.stripTags("<b>a</b><script>bad()</script><style>.x{}</style>c")
                        .replace(" ", "").equals("ac"));
        ok("tagText 找不到标签时返回空串（不许把整段吐回来）",
                WebSearch.tagText("<a>hello</a>", "title").length() == 0);
        ok("脏输入不抛异常（null / 空 / 半截标签）",
                WebSearch.parseRss("", 5).isEmpty() && WebSearch.parseHtml("<li class=\"b_algo\"", 5).isEmpty()
                        && WebSearch.stripTags(null).length() == 0
                        && WebSearch.decodeEntities(null).length() == 0);
        ok("请求地址：必应 RSS，查询串已编码",
                WebSearch.rssUrl("今天 新闻&x").indexOf("cn.bing.com/search?q=") >= 0
                        && WebSearch.rssUrl("今天 新闻&x").indexOf("format=rss") >= 0
                        && WebSearch.rssUrl("a b").indexOf("a+b") >= 0
                        && WebSearch.rssUrl("a&b").indexOf("a%26b") >= 0);
        ok("网页版兜底地址也指必应",
                WebSearch.htmlUrl("x").indexOf("cn.bing.com/search?q=x") >= 0);

        // —— 抓正文 ——
        String page = "<html><head><title>页标题</title><style>.a{}</style></head>"
                + "<body><!-- 注释 --><script>var x=1;</script>"
                + "<h1>大标题</h1><p>正文第一段&amp;实体</p><div>第二段</div>"
                + "<p></p><p>  </p><noscript>没有脚本</noscript></body></html>";
        String txt = WebFetch.extractText(page, 0);
        ok("★ 正文抽取：脚本/样式/注释整块丢掉",
                txt.indexOf("var x") < 0 && txt.indexOf(".a{}") < 0 && txt.indexOf("注释") < 0
                        && txt.indexOf("没有脚本") < 0);
        ok("块级标签变换行、空行被合并",
                txt.indexOf("大标题\n正文第一段&实体\n第二段") >= 0, txt.replace("\n", "\\n"));
        ok("标题能单独取出来", "页标题".equals(WebFetch.titleOf(page)));
        ok("★ 抽不出正文时返回空串（不许返回一坨标签）",
                WebFetch.extractText("<html><script>x</script></html>", 0).length() == 0
                        && WebFetch.extractText(null, 0).length() == 0);
        String longText = WebFetch.extractText("<p>" + repeat("字", 5000) + "</p>", 100);
        ok("正文超长会截断到上限（提示词是花钱的）",
                longText.length() <= WebFetch.MAX_CHARS + 1 && longText.endsWith("…"));
        ok("Content-Type 判文本 / 取字符集",
                WebFetch.isTextType("text/html; charset=utf-8")
                        && !WebFetch.isTextType("image/png")
                        && "GBK".equals(WebFetch.charsetOf("text/html; charset=GBK"))
                        && WebFetch.charsetOf("text/html").length() == 0);

        // —— 该不该搜（开关默认关；寒暄不搜，问句才搜）——
        ok("★ 开关关着一律不搜（默认关，零开销）",
                !WebPrompt.shouldSearch("今天有什么新闻", false)
                        && !WebPrompt.shouldSearch("最新 iPhone 多少钱", false));
        ok("问号 / 新鲜信息词 ⇒ 搜",
                WebPrompt.shouldSearch("今天有什么新闻", true)
                        && WebPrompt.shouldSearch("iPhone 17 多少钱？", true)
                        && WebPrompt.shouldSearch("谁是 DeepSeek 的创始人", true));
        ok("英文问句也认（what/who/latest/news… 按整词）",
                WebPrompt.shouldSearch("what is deepseek", true)
                        && WebPrompt.shouldSearch("latest news about AI", true)
                        && WebPrompt.shouldSearch("HOW much is it", true));
        ok("★ 整词匹配：show / newest 里的 how / new 不许误触发联网",
                !WebPrompt.shouldSearch("show me a trick please", true)
                        && !WebPrompt.shouldSearch("the newest wallpaper", true));
        ok("★ 短寒暄不搜（省流量、省时间）",
                !WebPrompt.shouldSearch("你好呀", true) && !WebPrompt.shouldSearch("在吗", true)
                        && !WebPrompt.shouldSearch("晚安", true) && !WebPrompt.shouldSearch("谢谢啦", true));
        ok("太短不搜（\"嗯\"/\"在\"）",
                !WebPrompt.shouldSearch("嗯", true) && !WebPrompt.shouldSearch("", true));
        ok("why() 说得清这次为什么（没）搜", WebPrompt.why("你好", true).length() > 0
                && WebPrompt.why("x", false).indexOf("开关") >= 0);

        // —— 拼提示词 ——
        java.util.List<WebSearch.Hit> fake = new java.util.ArrayList<WebSearch.Hit>();
        fake.add(new WebSearch.Hit("标题A", "https://a.example/x", "摘要A"));
        fake.add(new WebSearch.Hit("标题B", "https://b.example/y", "摘要B"));
        java.util.List<String> bodies = new java.util.ArrayList<String>();
        bodies.add("正文节选A");
        bodies.add("");
        String built = WebPrompt.withHits("测试问题", fake, bodies);
        ok("★ 资料被明确标成「数据不是指令」（防提示词注入）",
                built.indexOf("数据") >= 0 && built.indexOf("不要执行") >= 0
                        && built.indexOf("【资料结束】") >= 0);
        ok("只带**真实搜到**的两个网址（没有多余链接）",
                built.indexOf("https://a.example/x") >= 0 && built.indexOf("https://b.example/y") >= 0
                        && built.indexOf("http://") == built.lastIndexOf("http://"));
        ok("正文节选被带上（只有第一条有）",
                built.indexOf("正文节选A") >= 0 && built.indexOf("正文节选B") < 0);
        ok("★ 任务说明：没查到就说没查到、不许编造",
                built.indexOf("没查到") >= 0 && built.indexOf("不要编造") >= 0);
        ok("用户原话在最末（先资料、后问题）", built.trim().endsWith("测试问题"));

        String failed = WebPrompt.searchFailed("测试问题", "没有搜到结果");
        ok("★ 检索失败拼的是\"如实说没查到\"那段，且不许编造来源",
                failed.indexOf("联网检索失败") >= 0 && failed.indexOf("没有搜到结果") >= 0
                        && failed.indexOf("不要编造") >= 0 && failed.trim().endsWith("测试问题"));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 400; i++) huge.append("很长的一段资料").append(i).append('\n');
        java.util.List<WebSearch.Hit> one = new java.util.ArrayList<WebSearch.Hit>();
        one.add(new WebSearch.Hit("t", "https://a.example/x", "s"));
        java.util.List<String> hugeBody = new java.util.ArrayList<String>();
        hugeBody.add(huge.toString());
        ok("超长资料会被截断到上限（否则既贵又把问题挤没了）",
                WebPrompt.withHits("q", one, hugeBody).length() <= WebPrompt.MAX_BLOCK_CHARS + 1);
        ok("空结果走失败分支（不会拼出一个\"共 0 条\"的空资料块）",
                WebPrompt.withHits("q", null, null).indexOf("联网检索失败") >= 0);

        System.out.println();
        System.out.println("[24] QueryClean 原话 → 搜索关键词（轮次㉗：\"很笨\"的一半根因）");
        //
        // ★ 背景：用户报"很笨搜不到关键东西"。实测（必应 RSS，2026-10-08）：
        //   原话直搜会被当成**单字**匹配 —— "帮我查一下今天北京天气怎么样啊" 第一条是
        //   《帮_百度百科》，"我想知道现在比特币一个多少钱人民币" 第一条是《我（汉语汉字）》。
        //   提炼之后同一个源立刻能用（"北京 天气 今天" → 真的天气；"小米15 价格" → 京东报价）。
        //   所以这里钉的是"提炼规则不许退化"。

        ok("★ 口语问句被提炼（去掉\"帮我/查一下/怎么样\"）",
                QueryClean.keywords("帮我查一下今天北京天气怎么样啊").indexOf("北京") >= 0
                        && QueryClean.keywords("帮我查一下今天北京天气怎么样啊").indexOf("天气") >= 0
                        && QueryClean.keywords("帮我查一下今天北京天气怎么样啊").indexOf("帮") < 0
                        && QueryClean.keywords("帮我查一下今天北京天气怎么样啊").indexOf("怎么样") < 0,
                QueryClean.keywords("帮我查一下今天北京天气怎么样啊"));
        ok("★ 语气词/标点剥掉后是干净关键词",
                "北京天气".equals(QueryClean.keywords("北京天气怎么样？")),
                QueryClean.keywords("北京天气怎么样？"));
        ok("★ \"多少钱\"→\"价格\"（问句词换成资料词，命中率高得多）",
                QueryClean.keywords("小米15多少钱现在").indexOf("价格") >= 0
                        && QueryClean.keywords("小米15多少钱现在").indexOf("小米15") >= 0
                        && QueryClean.keywords("小米15多少钱现在").indexOf("多少钱") < 0,
                QueryClean.keywords("小米15多少钱现在"));
        ok("\"什么时候\"→\"时间\"、\"在哪里\"→\"地点\"",
                QueryClean.keywords("世界杯什么时候开始").indexOf("时间") >= 0
                        && QueryClean.keywords("世界杯在哪里举办").indexOf("地点") >= 0);
        ok("★ 时间词**不许**被删（今天/最新/现在正是\"要新信息\"的信号）",
                QueryClean.keywords("今天有什么新闻").indexOf("今天") >= 0
                        && QueryClean.keywords("deepseek 最新版本是多少来着？").indexOf("最新") >= 0
                        && QueryClean.keywords("现在比特币价格").indexOf("现在") >= 0);
        ok("★ 英文大小写与空格保留（deepseek 不许被吃掉）",
                QueryClean.keywords("deepseek 最新版本是多少来着？").toLowerCase().indexOf("deepseek") >= 0);
        ok("重复 token 去掉", "北京 天气".equals(QueryClean.keywords("北京 北京 天气")));
        ok("★ 提炼不出东西就**退回原话**（宁可原样搜，也不要空查询）",
                "帮我".equals(QueryClean.keywords("帮我"))
                        && "".equals(QueryClean.keywords(""))
                        && "在吗".equals(QueryClean.keywords("在吗")));
        ok("长度与词数都有上限（必应对长句会乱匹配）",
                QueryClean.keywords(repeat("关键词", 40)).length() <= QueryClean.MAX_CHARS
                        && QueryClean.keywords("一 二 三 四 五 六 七 八")
                        .split(" ").length <= QueryClean.MAX_TOKENS);
        ok("changed() 说得清这次提炼有没有起作用",
                QueryClean.changed("帮我查一下今天北京天气怎么样啊")
                        && !QueryClean.changed("北京天气"));
        ok("脏输入不抛异常", QueryClean.keywords(null).length() == 0
                && QueryClean.keywords("   ").length() == 0);

        System.out.println();
        System.out.println("[25] Weather 天气走结构化接口（轮次㉗：\"问个天气都不知道\"的正解）");
        //
        // ★ 背景：用户问"今天天气怎么样"，日志显示检索**成功**（5 条 / 源=rss / 正文=true），
        //   但复刻那条链路才知道模型收到的是什么：5 条全是天气门户首页，抓回的第一条正文是
        //   **导航栏文字**（"预警 信息 地图 卫星 全国 北京 上海…"）—— 1190 字里没有一个温度。
        //   ⇒ 天气必须走中国天气网的结构化接口（d1.weather.com.cn），那要城市码；
        //   城市码表由 build/gen_city_table.py 从官方城市表生成（见 WeatherCities 类注释）。

        ok("★ 天气意图认得出来",
                Weather.isAsk("今天天气怎么样") && Weather.isAsk("北京天气")
                        && Weather.isAsk("明天会下雨吗") && Weather.isAsk("现在几度")
                        && Weather.isAsk("空气质量怎么样") && Weather.isAsk("今天限行几号")
                        && Weather.isAsk("要不要带伞") && Weather.isAsk("冷不冷啊"));
        ok("★ 不是天气的问句不许误判（否则会把普通搜索带沟里）",
                !Weather.isAsk("你好呀") && !Weather.isAsk("今天有什么新闻")
                        && !Weather.isAsk("DeepSeek 最新版本是多少")
                        && !Weather.isAsk("比特币多少钱") && !Weather.isAsk(""));
        ok("★ 从问句里抠出城市（含直辖市 / 副省级 / 县级市 / 自治区首府）",
                "北京".equals(Weather.cityIn("今天北京天气怎么样"))
                        && "上海".equals(Weather.cityIn("明天上海会下雨吗"))
                        && "深圳".equals(Weather.cityIn("深圳天气"))
                        && "乌鲁木齐".equals(Weather.cityIn("乌鲁木齐冷不冷"))
                        && "义乌".equals(Weather.cityIn("义乌天气怎么样"))
                        && "石家庄".equals(Weather.cityIn("帮我查一下石家庄的天气")));
        ok("★ 没写城市就返回空（调用方据此**反问**，不许瞎猜）",
                "".equals(Weather.cityIn("今天天气怎么样"))
                        && "".equals(Weather.cityIn("明天会下雨吗")));
        // ★ 英文也要认：① 用户会用英文问；② **设备脚本只能用 ASCII**
        //   （`adb shell input text` 不支持中文）—— 没有英文入口，"天气链路在设备上跑通"
        //   就只能靠纯断言，而设备验证是本工程的硬要求。
        ok("★ 英文天气问句也认（整词匹配）",
                Weather.isAsk("what's the weather in beijing")
                        && Weather.isAsk("temperature today?") && Weather.isAsk("is it raining")
                        && Weather.isAsk("do I need an umbrella"));
        ok("★ 英文整词：show / newest 里的片段不许误触发",
                !Weather.isAsk("show me a trick") && !Weather.isAsk("the newest wallpaper"));
        ok("★ 英文城市名（含拼音与港澳）抠得出来",
                "北京".equals(Weather.cityIn("weather in beijing"))
                        && "上海".equals(Weather.cityIn("temperature in shanghai?"))
                        && "深圳".equals(Weather.cityIn("rain in shenzhen"))
                        && "香港".equals(Weather.cityIn("weather in hong kong"))
                        && "".equals(Weather.cityIn("what is the weather")));
        ok("城市码表：名 / 带后缀 / 区县 / 省会都对得上",
                "101010100".equals(WeatherCities.code("北京"))
                        && "101010100".equals(WeatherCities.code("北京市"))
                        && "101010200".equals(WeatherCities.code("海淀"))
                        && "101280601".equals(WeatherCities.code("深圳"))
                        && WeatherCities.size() > 3000,
                "表里 " + WeatherCities.size() + " 条");
        ok("查不到的城市返回空串（不许编一个码出来）",
                "".equals(WeatherCities.code("不存在的城市")) && "".equals(WeatherCities.code(null)));
        ok("天气现象代码 → 文字（接口给的是 00/01/07 这种码）",
                "晴".equals(Weather.codeText("00")) && "多云".equals(Weather.codeText("01"))
                        && "小雨".equals(Weather.codeText("07")) && "霾".equals(Weather.codeText("53"))
                        && "".equals(Weather.codeText("")) && "".equals(Weather.codeText(null)));

        String widx = readFixture("weather-index.jsonp");
        ok("★ 读到真实天气接口 fixture（weather_index，离线跑）",
                widx != null && widx.length() > 1000,
                widx == null ? "fixtures/weather-index.jsonp 缺失" : (widx.length() + " 字符"));
        if (widx != null) {
            Weather.Now now = new Weather.Now();
            java.util.List<Weather.Day> days = new java.util.ArrayList<Weather.Day>();
            ok("★ 解析真实 weather_index：有实况 + 有预报", Weather.parse(widx, now, days)
                    && now.ok() && days.size() >= 2, "预报 " + days.size() + " 天");
            ok("★ 实况里有城市与温度（就是模型缺的那个\"数\"）",
                    "北京".equals(now.city) && now.temp.length() > 0
                            && Double.parseDouble(now.temp) > -40 && Double.parseDouble(now.temp) < 60,
                    now.city + " " + now.temp + "℃ " + now.weather);
            ok("实况的风/湿度/气压/能见度/降雨/空气/限行都取到了",
                    now.wd.length() > 0 && now.ws.length() > 0 && now.sd.length() > 0
                            && now.qy.length() > 0 && now.rain.length() > 0 && now.aqi.length() > 0,
                    now.wd + now.ws + " 湿度" + now.sd + " 空气" + now.aqi);
            Weather.Day d0 = days.get(0);
            ok("★ 今天/明天的高低温与天气文字都解析出来",
                    d0.high.length() > 0 && d0.low.length() > 0 && d0.text().length() > 0,
                    d0.label + " " + d0.text() + " " + d0.range());
            String blk = Weather.block("北京", now, days);
            ok("★ 资料块是有数的（不是导航栏）",
                    blk.indexOf("城市：北京") >= 0 && blk.indexOf("℃") >= 0
                            && blk.indexOf("【资料结束】") >= 0 && blk.indexOf(now.temp) >= 0,
                    blk.length() + " 字");
            ok("★ 资料块里不许出现\"预警 信息 地图 卫星\"那种导航栏残留",
                    blk.indexOf("卫星") < 0 && blk.indexOf("热门城市") < 0);
        }

        String wsk = readFixture("weather-sk.jsonp");
        ok("读到实况接口 fixture（sk_2d，兜底用）", wsk != null && wsk.length() > 100);
        if (wsk != null) {
            Weather.Now only = new Weather.Now();
            ok("★ 只有实况的响应也能解析（index 挂了就用它兜底）",
                    Weather.parseNow(wsk, only) && only.ok() && only.city.length() > 0,
                    only.city + " " + only.temp + "℃");
        }
        ok("脏输入不抛异常、也不许假装解析成功",
                !Weather.parse(null, new Weather.Now(), new java.util.ArrayList<Weather.Day>())
                        && !Weather.parse("这根本不是 jsonp", new Weather.Now(),
                        new java.util.ArrayList<Weather.Day>())
                        && !Weather.parseNow("var dataSK={}", new Weather.Now()));
        ok("没城市时拼的是**反问**（不瞎猜城市、不许编天气）",
                Weather.askCityText("今天天气怎么样").indexOf("城市") >= 0
                        && Weather.askCityText("今天天气怎么样").indexOf("不要") >= 0
                        && Weather.askCityText("今天天气怎么样").trim().endsWith("今天天气怎么样"));
        ok("接口挂了拼的是\"没查到\"（与搜索失败同一条纪律）",
                Weather.failText("北京天气", "HTTP 403").indexOf("没查到") >= 0
                        && Weather.failText("北京天气", "HTTP 403").indexOf("HTTP 403") >= 0);
        ok("请求地址 + Referer（d1.weather.com.cn 不带 Referer 会被拦）",
                Weather.indexUrl("101010100", 123).indexOf("d1.weather.com.cn") >= 0
                        && Weather.indexUrl("101010100", 123).indexOf("101010100") >= 0
                        && Weather.indexUrl("101010100", 123).indexOf("_=123") >= 0
                        && Weather.nowUrl("101010100", 9).indexOf("sk_2d") >= 0
                        && Weather.REFERER.indexOf("weather.com.cn") >= 0);
        ok("★ 天气资料块也走 withWeather：先给结论、不许编温度",
                WebPrompt.withWeather("北京天气", Weather.block("北京", new Weather.Now(),
                        new java.util.ArrayList<Weather.Day>())).indexOf("不要编造") >= 0);

        System.out.println();
        System.out.println("[26] Json 极简解析 + 路径取值（可插拔搜索的地基）");
        //
        // ★ 背景：用户要求"让任何 API 接进来都能搜索" ⇒ 必须能从**任意** JSON 响应里按
        //   用户填的路径取字段（纯类不能用 Android 的 org.json，所以自己写一个够用的）。
        //   这一节就是它的规格说明：解析 + a.b / a[0].b / a[].b（摊平）+ 宽容注释。

        Object jo = Json.parseOrNull("{\"a\":{\"b\":[1,2.5,\"x\"]},\"ok\":true,\"n\":null,\"s\":\"中\\n文\"}");
        ok("解析对象/数组/数字/布尔/null/转义", jo instanceof java.util.Map
                && Boolean.TRUE.equals(Json.path(jo, "ok")) && Json.path(jo, "n") == null
                && "中\n文".equals(Json.path(jo, "s")));
        ok("路径 a.b[0] 取到数字并转成文字（整数不带小数点）",
                "1".equals(Json.text(jo, "a.b[0]")) && "2.5".equals(Json.text(jo, "a.b[1]")));
        ok("★ 路径 a[].b 把数组摊平（结果列表 → 每条的字段）",
                "2".equals(String.valueOf(Json.strs(Json.path(
                        Json.parseOrNull("{\"r\":[{\"t\":\"A\"},{\"t\":\"B\"}]}"), "r[].t")).size())));
        ok("取不到的路径返回 null / 空串（配置写错不抛异常）",
                Json.path(jo, "a.zzz") == null && Json.path(jo, "zzz.b") == null
                        && Json.text(jo, "zzz").length() == 0);
        ok("数组摊平后能合成一段文字（搜索 API 的 chunks[].text 就是这个形状）",
                Json.text(Json.parseOrNull("{\"c\":[{\"text\":\"第一段\"},{\"text\":\"第二段\"}]}"),
                        "c[].text").equals("第一段\n第二段"));
        ok("★ 宽容注释（用户手写配置常见 // 与 /* */）",
                Json.parseOrNull("{//说明\n\"a\":1, /* 块注释 */ \"b\":2}") != null
                        && "1".equals(Json.text(Json.parseOrNull("{//x\n\"a\":1}"), "a")));
        ok("坏 JSON 返回 null（不抛给界面）",
                Json.parseOrNull("{不是 json") == null && Json.parseOrNull("") == null
                        && Json.parseOrNull(null) == null);
        boolean badJsonThrew = false;
        try {
            Json.parse("{\"a\":1,}");
        } catch (Throwable badErr) {
            badJsonThrew = true;
        }
        ok("parse 遇到坏 JSON 会抛（错误信息带位置，便于提示用户）", badJsonThrew);

        System.out.println();
        System.out.println("[27] SearchProvider 可插拔搜索服务（任何 API 都能接）");
        //
        // ★ 背景：用户 2026-10-08 明确要求「不用 kimi 的搜索，要让任何 api 接入进来都能搜索」。
        //   所以这里钉的是"配置驱动"这件事本身：地址/方法/请求头/请求体/字段路径。
        //   ★ 解析断言跑在**真实响应**上（fixtures/kimi-*.json 是 2026-10-08 用真 Key 抓的）。

        java.util.List<SearchProvider> presets = SearchProvider.presets();
        SearchProvider bing = SearchProvider.preset(SearchProvider.BING);
        SearchProvider kimi = SearchProvider.preset("kimi");
        ok("内置预设：必应（免费）/ Kimi / SearXNG / Tavily",
                presets.size() >= 4 && bing != null && kimi != null
                        && SearchProvider.preset("searxng") != null
                        && SearchProvider.preset("tavily") != null);
        ok("必应预设不需要 Key；Kimi 需要", !bing.needsKey && kimi.needsKey);
        ok("★ 请求地址/请求体模板带占位符（{q} 关键词、{key} 密钥）",
                kimi.endpoint.indexOf("tools/search_pro") >= 0
                        && kimi.body.indexOf("{q}") >= 0
                        && kimi.headersFor("K").get("Authorization").equals("Bearer K"));
        ok("★ {q} 在地址里做 URL 编码、在 JSON 体里做 JSON 转义（引号不会把 JSON 弄坏）",
                SearchProvider.preset("searxng").urlFor("a b&c", "").indexOf("a+b%26c") >= 0
                        && kimi.bodyFor("他说\"你好\"", "").indexOf("\\\"你好\\\"") >= 0);
        ok("自定义服务：合法 JSON 能解析出各字段",
                customProbe().id.equals("myapi") && customProbe().max == 8
                        && "data.items".equals(customProbe().listPath)
                        && "paragraphs[].text".equals(customProbe().contentPath));
        ok("自定义服务：缺 endpoint / 坏 JSON ⇒ 拒绝（不让坏配置进系统）",
                SearchProvider.fromJson("{\"id\":\"x\"}") == null
                        && SearchProvider.fromJson("{坏}") == null);
        ok("自定义列表存/读（prefs 里是一整段 JSON 数组）",
                SearchProvider.fromJsonArray("[" + SearchProvider.exampleJson() + "]").size() == 1
                        && SearchProvider.fromJsonArray("乱码").isEmpty()
                        && SearchProvider.fromJsonArray("").isEmpty());
        ok("★ 同 id 时**用户自定义覆盖内置**（能改模板而不必动代码）",
                SearchProvider.byId("kimi",
                        "[{\"id\":\"kimi\",\"label\":\"我的 Kimi\",\"endpoint\":\"https://x/y\"}]")
                        .label.equals("我的 Kimi")
                        && SearchProvider.byId("kimi", "").label.indexOf("Kimi") >= 0);
        ok("未知 id ⇒ null（调用方退回必应，不让配置问题变成功能失效）",
                SearchProvider.byId("nope", "") == null);

        String kpro = readFixture("kimi-search-pro.json");
        ok("★ 读到真实 Kimi search_pro 报文（离线跑，不联网）",
                kpro != null && kpro.length() > 2000,
                kpro == null ? "fixtures/kimi-search-pro.json 缺失" : (kpro.length() + " 字符"));
        if (kpro != null && kimi != null) {
            java.util.List<WebSearch.Hit> hits = kimi.parse(kpro);
            ok("★ 按配置的路径解析出多条结果", hits.size() >= 3, "解出 " + hits.size() + " 条");
            boolean allOk = !hits.isEmpty();
            for (WebSearch.Hit h : hits) {
                if (!h.usable() || !WebSearch.isHttp(h.url)) allOk = false;
            }
            ok("每条都有 http 链接 + 标题", allOk);
            ok("★ 段落（chunks[].text）被并进摘要 —— 模型看到的是「料」不是「导航栏」",
                    hits.get(0).snippet.indexOf("DeepSeek") >= 0 && hits.get(0).snippet.length() > 80,
                    hits.get(0).snippet.replace("\n", " ⏎ "));
            ok("条数上限生效（配置里 max 默认 8）", hits.size() <= kimi.max);
            java.util.List<WebSearch.Hit> two = SearchProvider.fromJson(
                    "{\"id\":\"kimi2\",\"endpoint\":\"https://x\",\"list\":\"search_results\","
                    + "\"title\":\"title\",\"url\":\"url\",\"snippet\":\"snippet\",\"max\":\"2\"}")
                    .parse(kpro);
            ok("配置里写 max=2 ⇒ 只取 2 条", two.size() == 2);
        }
        String kbasic = readFixture("kimi-search-basic.json");
        if (kbasic != null) {
            SearchProvider b = SearchProvider.fromJson(
                    "{\"id\":\"kb\",\"endpoint\":\"https://x\",\"list\":\"search_results\","
                    + "\"title\":\"title\",\"url\":\"url\",\"snippet\":\"text\"}");
            ok("★ 同一份解析器换条路径就能吃另一家的形状（Basic 的摘要字段叫 text）",
                    b != null && b.parse(kbasic).size() >= 3);
        }
        String kfetch = readFixture("kimi-fetch.json");
        ok("★ 第二跳（抓整页正文）也能按路径取出来",
                kfetch != null && kimi != null && kimi.bodyFrom(kfetch).length() > 1000,
                kfetch == null ? "缺 fixture" : (kimi.bodyFrom(kfetch).length() + " 字"));
        ok("坏响应不抛异常（返回空表 / 空串）",
                kimi.parse("不是 json").isEmpty() && kimi.bodyFrom("{}").length() == 0
                        && kimi.parse(null).isEmpty());

        System.out.println();
        System.out.println("[28] WebCache 缓存 + 主机冷却（轮次㉙：治 429「请求太多」）");
        //
        // ★ 背景：用户报「搜天气还报错 429」。除了对面限流，我们自己也把请求打多了：
        //   同一城市连着问两次就真请求两次；一次天气最坏发 4 个请求（index → sk_2d 兜底，各自还重试）；
        //   每个请求还带随机 cache-buster（把对面 CDN 缓存全废掉）。这一节钉缓存与退避的语义。

        WebCache.clear();
        WebCache.setClock(0);
        WebCache.put("k", "v", 60000L);
        ok("缓存：put/get 基本语义",
                "v".equals(WebCache.get("k")) && WebCache.size() == 1);
        ok("★ 过期后取不到（且会被清掉）",
                cacheExpiredWorks());
        ok("TTL 剩余时间为 0 表示没有/已过期", WebCache.ttlLeft("nope") == 0L);
        ok("键为空/null 不写不进（脏输入不许污染缓存）",
                putNothing() && WebCache.get(null) == null && WebCache.get("") == null);

        ok("★ 主机冷却：cool 之后 cooldownLeft > 0，且到期自动解除",
                cooldownWorks());
        ok("★ Retry-After 解析（秒数；乱码/负数当没给）",
                WebCache.retryAfterMs("120") == 120000L
                        && WebCache.retryAfterMs("0") == 0L
                        && WebCache.retryAfterMs("abc") == 0L
                        && WebCache.retryAfterMs(null) == 0L
                        && WebCache.retryAfterMs("99999") <= WebCache.MAX_COOLDOWN_MS);
        ok("★ 冷却有上限（对面给个天大的值也不能把源永久封死）",
                cooldownCapped());
        ok("从 URL 取主机（拿不到就空串 ⇒ 不参与冷却判定）",
                "d1.weather.com.cn".equals(WebCache.hostOf("https://d1.weather.com.cn/a/b?x=1"))
                        && "cn.bing.com".equals(WebCache.hostOf("https://cn.bing.com/search?q=x"))
                        && "".equals(WebCache.hostOf("不是网址"))
                        && "".equals(WebCache.hostOf(null)));
        ok("容量有上限（不许无限长大）", capacityBounded());
        WebCache.clear();
        WebCache.setClock(0);

        // —— wttr.in 兜底：真实报文 ——
        String wttr = readFixture("wttr-beijing.json");
        ok("★ 读到真实 wttr.in 报文（第三方天气兜底，离线跑）",
                wttr != null && wttr.length() > 2000,
                wttr == null ? "fixtures/wttr-beijing.json 缺失" : (wttr.length() + " 字符"));
        if (wttr != null) {
            Weather.Now alt = new Weather.Now();
            ok("★ 解析出温度（兜底源也能给模型一个数）", Weather.parseWttr(wttr, alt) && alt.temp.length() > 0,
                    alt.city + " " + alt.temp + "℃ " + alt.weather);
            ok("兜底源的城市/湿度/风也取到了",
                    alt.city.length() > 0 && alt.sd.length() > 0 && alt.wd.length() > 0,
                    alt.city + " 湿度" + alt.sd + " 风" + alt.wd + alt.ws);
        }
        ok("★ wttr 的地址**不能带 cache-buster**（实测带 `&_=` 直接 500）",
                Weather.wttrUrl("beijing", 123L).indexOf("_=") < 0
                        && Weather.wttrUrl("beijing", 123L).indexOf("format=j1") > 0);
        ok("中文城市名 → 拼音（兜底源要用它）",
                "beijing".equals(Weather.enOf("北京")) && "shenzhen".equals(Weather.enOf("深圳"))
                        && "".equals(Weather.enOf("不存在的城市")));
        ok("脏输入不抛异常", !Weather.parseWttr(null, new Weather.Now())
                && !Weather.parseWttr("不是 json", new Weather.Now())
                && !Weather.parseWttr("{}", new Weather.Now()));

        System.out.println();
        System.out.println("[29] ClipList 动作列表的搜索 / 排序 / 拖拽（轮次㉚：106 个动作不能只靠滚）");
        //
        // ★ 背景：角色包有一百多个动作，而动作库 / 场景改绑 / 自定义分类三处原来都是
        //   AlertDialog 的纯滚动列表。搜索的"怎么算命中"、排序"稳不稳"、拖拽"落到第几行"
        //   全是纯逻辑，所以抽成零依赖纯类在这里钉死 —— 这三件事错了，
        //   用户看到的是"搜不到我的动作""列表顺序自己在跳""拖了没反应"。

        // —— 搜索：分词 AND + 分隔符无关 ——
        ok("★ 打 `点击回应3` 能命中 `点击回应-3`（素材名里的分隔符不用记）",
                ClipList.matches("点击回应-3", "点击回应3")
                        && ClipList.matches("工作_状态-1", "工作状态1"));
        ok("★ 多个词是 AND（全部命中才显示）",
                ClipList.matches("点击回应-3", "回应 3")
                        && !ClipList.matches("点击回应-3", "回应 4")
                        && !ClipList.matches("点击回应-3", "点击 4"));
        ok("大小写无关（英文动作名）",
                ClipList.matches("EatToken", "eattoken") && ClipList.matches("EatToken", "EAT"));
        ok("空查询 = 全命中（不筛）",
                ClipList.matches("任意", "") && ClipList.matches("任意", null)
                        && ClipList.matches("任意", "   "));
        ok("分词会去重、空段丢掉",
                ClipList.tokens("吃 吃   token").length == 2
                        && ClipList.tokens("").length == 0 && ClipList.tokens(null).length == 0);
        ok("过滤保序（不改变原顺序）",
                sameList(ClipList.filter(list("吃A", "喝B", "吃C"), "吃"), list("吃A", "吃C"))
                        && ClipList.filter(null, "x").isEmpty());

        // —— 排序 ——
        java.util.List<String> src = list("c", "a", "b");
        ok("原序模式 = 不动",
                sameList(ClipList.sorted(src, null, ClipList.SORT_KEEP), list("c", "a", "b")));
        ok("名称升 / 降序",
                sameList(ClipList.sorted(src, null, ClipList.SORT_NAME_ASC), list("a", "b", "c"))
                        && sameList(ClipList.sorted(src, null, ClipList.SORT_NAME_DESC),
                                list("c", "b", "a")));
        ok("★ 已绑定优先（组内按名称）",
                sameList(ClipList.sorted(list("a", "b", "c"), new int[]{0, 2, 0},
                                ClipList.SORT_BOUND_FIRST), list("b", "a", "c")));
        ok("未绑定优先（组内按名称）",
                sameList(ClipList.sorted(list("a", "b", "c"), new int[]{0, 2, 0},
                                ClipList.SORT_UNBOUND_FIRST), list("a", "c", "b")));
        ok("★ 稳定：键相同的元素保持原相对顺序（否则列表每次打开都在跳）",
                sameList(ClipList.sorted(list("吃_Token", "吃-Token"), null, ClipList.SORT_NAME_ASC),
                        list("吃_Token", "吃-Token")));
        ok("排序不吞元素、不重复",
                ClipList.sorted(src, new int[]{0, 0, 0}, ClipList.SORT_NAME_ASC).size() == 3);
        ok("排序模式循环（按钮点一下换一种，最终回到原序）",
                ClipList.nextSort(ClipList.SORT_KEEP) == ClipList.SORT_NAME_ASC
                        && ClipList.nextSort(ClipList.SORT_COUNT - 1) == ClipList.SORT_KEEP
                        && ClipList.sortButtonText(ClipList.SORT_NAME_ASC).startsWith("排序："));

        // —— 拖拽 ——
        ok("★ 拖拽重排：to 是**移动后**的下标（不用调用方再 ±1）",
                sameList(ClipList.move(list("A", "B", "C", "D"), 0, 2), list("B", "C", "A", "D"))
                        && sameList(ClipList.move(list("A", "B", "C", "D"), 3, 0),
                                list("D", "A", "B", "C")));
        ok("拖到原位 / 非法下标 = 原样返回",
                sameList(ClipList.move(list("A", "B", "C"), 1, 1), list("A", "B", "C"))
                        && sameList(ClipList.move(list("A", "B", "C"), 9, 0), list("A", "B", "C")));
        ok("越界自动钳到首尾（拖出屏幕也不丢元素）",
                sameList(ClipList.move(list("A", "B", "C"), 0, 99), list("B", "C", "A"))
                        && sameList(ClipList.move(list("A", "B", "C"), 2, -5), list("C", "A", "B")));
        ok("★ 落点判定：按每行中线，越界钳到首尾",
                ClipList.dropIndex(new int[]{24, 72, 120}, 0) == 0
                        && ClipList.dropIndex(new int[]{24, 72, 120}, 24) == 0
                        && ClipList.dropIndex(new int[]{24, 72, 120}, 25) == 1
                        && ClipList.dropIndex(new int[]{24, 72, 120}, 999) == 2
                        && ClipList.dropIndex(new int[]{24, 72, 120}, -999) == 0
                        && ClipList.dropIndex(new int[0], 5) == -1);
        ok("拖拽手柄提示文案在（设备脚本按它找行）",
                ClipList.dragHint().indexOf("\u2261") >= 0);

        // —— 行文案 / 勾选 ——
        ok("动作库行文案：已绑 N 个场景 / 未绑定",
                ClipList.rowLabel("撸猫", 3).indexOf("已绑 3 个场景") > 0
                        && ClipList.rowLabel("撸猫", 0).indexOf("未绑定") > 0);
        ok("改绑行文案：权重 1 不显示 ×N（否则整列都是 ×1）",
                ClipList.bindRowLabel("撸猫", 1).equals("撸猫")
                        && ClipList.bindRowLabel("撸猫", 3).indexOf("\u00D73") > 0);
        ok("「只看已选」与计数文案带上数字（用户要知道自己在筛什么）",
                ClipList.onlyPickedText(3, 106).indexOf("3 / 106") > 0
                        && ClipList.countText(106, 12, "吃").indexOf("12 / 106") > 0
                        && ClipList.countText(106, 106, "").indexOf("共 106") >= 0);
        ok("★ 计数行会说明「看到的是哪一部分」（只看已选时不能还写「共 106 个」）",
                ClipList.countText(106, 3, "").indexOf("显示 3 / 106") >= 0
                        && ClipList.countText(106, 3, "").indexOf("只看已选") > 0);
        ok("★ 勾选结果按**原始顺序**取（文件内容不随当时怎么排序而变）",
                sameList(ClipList.pickedOf(list("a", "b", "c", "d"), list("d", "b")),
                        list("b", "d")));
        ok("脏输入不抛异常（null 列表）",
                ClipList.sorted(null, null, ClipList.SORT_NAME_ASC).isEmpty()
                        && ClipList.move(null, 0, 1).isEmpty()
                        && ClipList.pickedOf(null, null).isEmpty());

        System.out.println("[30] Endpoint 自定义 API 地址判据（轮次56：127.0.0.1 指的是设备自己）");
        {
            // —— 主机名解析：三种写法都要认（带 scheme / 不带 / 带端口与路径） ——
            ok("解析主机名：标准 URL",
                    "127.0.0.1".equals(Endpoint.host("http://127.0.0.1:7863/v1"))
                            && "192.168.1.5".equals(Endpoint.host("http://192.168.1.5:7863/v1")));
            ok("解析主机名：没写 scheme 也认",
                    "10.0.2.2".equals(Endpoint.host("10.0.2.2:7863/v1")));
            ok("解析主机名：域名 + 大小写归一",
                    "api.deepseek.com".equals(Endpoint.host("HTTPS://API.DeepSeek.com/v1")));
            ok("解析主机名：IPv6 字面量",
                    "::1".equals(Endpoint.host("http://[::1]:7863/v1")));
            ok("解析主机名：带 user:pass@ 的写法",
                    "example.com".equals(Endpoint.host("http://u:p@example.com/v1")));
            ok("脏输入不抛异常（null / 空串）",
                    Endpoint.host(null).isEmpty() && Endpoint.host("   ").isEmpty());

            // —— 回环识别：这是本轮真凶，判据必须准 ——
            ok("★★ 认出回环：127.0.0.1 / 127.1.2.3（整个 127/8）/ localhost / ::1",
                    Endpoint.isLoopback("http://127.0.0.1:7863/v1")
                            && Endpoint.isLoopback("http://127.1.2.3:7863/v1")
                            && Endpoint.isLoopback("http://localhost:7863/v1")
                            && Endpoint.isLoopback("http://[::1]:7863/v1"));
            ok("★★ 别误伤：局域网 IP / 域名 / 模拟器宿主机别名都不是回环",
                    !Endpoint.isLoopback("http://192.168.1.5:7863/v1")
                            && !Endpoint.isLoopback("http://10.0.2.2:7863/v1")
                            && !Endpoint.isLoopback("https://api.deepseek.com")
                            && !Endpoint.isLoopback("http://1270.0.0.1/v1"));
            ok("★ 0.0.0.0 是「监听所有网卡」，不是能连的地址（单独一类）",
                    Endpoint.isWildcard("http://0.0.0.0:7863/v1")
                            && !Endpoint.isLoopback("http://0.0.0.0:7863/v1"));

            // —— 建议文案：必须真的解释"设备上的 127.0.0.1 是谁"，而不是只说"失败" ——
            String a = Endpoint.advice("http://127.0.0.1:7863/v1");
            ok("★★ 回环地址给出**可操作**的解释（设备自己 / 10.0.2.2 / 局域网 IP / adb reverse）",
                    a.indexOf("设备自己") > 0 && a.indexOf("10.0.2.2") > 0
                            && a.indexOf("局域网") > 0 && a.indexOf("adb reverse") > 0);
            ok("★ 正常地址不给建议（不能对每个地址都弹警告）",
                    Endpoint.advice("http://192.168.1.5:7863/v1").isEmpty()
                            && !Endpoint.suspicious("https://api.deepseek.com"));
            ok("★ 0.0.0.0 的建议说的是“监听”这件事",
                    Endpoint.advice("http://0.0.0.0:7863/v1").indexOf("监听") > 0);
            ok("suspicious 与 advice 同口径（非空即可疑）",
                    Endpoint.suspicious("http://127.0.0.1:1/v1")
                            && !Endpoint.suspicious("http://10.0.2.2:1/v1"));

            // —— 归一化：与 ApiClient.setBaseUrl 同一口径（去空白、去尾部斜杠） ——
            ok("归一化：去空白与尾部斜杠（多个斜杠也去干净）",
                    "http://127.0.0.1:7863/v1".equals(Endpoint.normalize("  http://127.0.0.1:7863/v1///  ")));
            ok("归一化：空输入返回空串（由调用方决定回落默认）",
                    Endpoint.normalize(null).isEmpty() && Endpoint.normalize("  ").isEmpty());
        }

        System.out.println("[31] ClickSound 点击音效波形（轮次57：运行时合成，不引音频素材）");
        {
            ok("长度 = 采样率 × 时长（三档各自对得上）",
                    ClickSound.pcm(ClickSound.STYLE_POP, 0.6f).length
                            == ClickSound.SAMPLE_RATE * ClickSound.durationMs(ClickSound.STYLE_POP) / 1000
                    && ClickSound.pcm(ClickSound.STYLE_TICK, 0.6f).length
                            == ClickSound.SAMPLE_RATE * ClickSound.durationMs(ClickSound.STYLE_TICK) / 1000
                    && ClickSound.pcm(ClickSound.STYLE_DING, 0.6f).length
                            == ClickSound.SAMPLE_RATE * ClickSound.durationMs(ClickSound.STYLE_DING) / 1000);

            // —— ★★ 防"啪"：起手那一跳必须**小到听不见**、收尾必须正好落在 0 ——
            //    起手不是"恰好 0"：淡入期第 1 个采样时波形本身已经在非零相位上，
            //    但只要它 ≤ 峰值的 1% 就听不出来（这正是淡入的作用）。
            boolean edges = true;
            boolean firstTiny = true;
            for (int s = 0; s < ClickSound.STYLE_COUNT; s++) {
                short[] w0 = ClickSound.pcm(s, 1.0f);
                int pk = peak(w0);
                if (w0[w0.length - 1] != 0) edges = false;
                if (Math.abs((int) w0[0]) * 100 > pk) firstTiny = false;
            }
            ok("★★ 收尾正好落在 0（截断在非零值会“啪”一声）", edges);
            ok("★★ 起手那一跳 ≤ 峰值的 1%（听不见；淡入就是为了这个）", firstTiny);

            short[] w = ClickSound.pcm(ClickSound.STYLE_DING, 1.0f);
            ok("★ 淡入是渐进的（第 1 个采样 < 第 20 个）", Math.abs(w[1]) < Math.abs(w[20]));
            ok("★ 淡出是渐进的（倒数第 1 个 < 倒数第 30 个）",
                    Math.abs(w[w.length - 1]) < Math.abs(w[w.length - 30]));

            // —— 音量语义 ——
            short[] mute = ClickSound.pcm(ClickSound.STYLE_POP, 0f);
            boolean allZero = true;
            for (short v : mute) if (v != 0) allZero = false;
            ok("★ 音量 0 ⇒ 全零（静音是全 0，长度与形状照旧）", allZero && mute.length > 0);

            int peakLow = peak(ClickSound.pcm(ClickSound.STYLE_POP, ClickSound.VOL_LOW));
            int peakMid = peak(ClickSound.pcm(ClickSound.STYLE_POP, ClickSound.VOL_MID));
            int peakHigh = peak(ClickSound.pcm(ClickSound.STYLE_POP, ClickSound.VOL_HIGH));
            ok("★ 音量档越大峰值越高（轻 < 中 < 响）", peakLow < peakMid && peakMid < peakHigh);
            ok("★★ 峰值不越界也不削顶（int16 内且留了余量）",
                    peakHigh < 32767 && peakHigh > 20000);
            ok("★ 音量越界被夹住（>1 夹到 1、<0 夹到静音，都不炸）",
                    peak(ClickSound.pcm(ClickSound.STYLE_POP, 5f))
                            == peak(ClickSound.pcm(ClickSound.STYLE_POP, 1f))
                            && peak(ClickSound.pcm(ClickSound.STYLE_POP, -3f)) == 0);

            // —— 三档音色确实不同（否则"选音色"是假功能） ——
            short[] pop = ClickSound.pcm(ClickSound.STYLE_POP, 0.6f);
            short[] tick = ClickSound.pcm(ClickSound.STYLE_TICK, 0.6f);
            short[] ding = ClickSound.pcm(ClickSound.STYLE_DING, 0.6f);
            ok("★★ 三档音色互不相同（波形逐点比对）",
                    !sameWave(pop, tick) && !sameWave(pop, ding) && !sameWave(tick, ding));
            ok("★ 音色名与档位对应（啵/哒/叮）",
                    "啵".equals(ClickSound.styleName(ClickSound.STYLE_POP))
                            && "哒".equals(ClickSound.styleName(ClickSound.STYLE_TICK))
                            && "叮".equals(ClickSound.styleName(ClickSound.STYLE_DING)));

            // —— 脏输入 ——
            ok("★ 非法音色被夹到默认档（-1 / 99 都不越界）",
                    ClickSound.styleIndex(-1) == ClickSound.STYLE_POP
                            && ClickSound.styleIndex(99) == ClickSound.STYLE_POP
                            && ClickSound.pcm(-1, 0.5f).length
                                    == ClickSound.pcm(ClickSound.STYLE_POP, 0.5f).length);

            // —— ★ 确定性 ——
            ok("★ 确定性：两次合成逐点相同",
                    sameWave(ClickSound.pcm(ClickSound.STYLE_DING, 0.7f),
                            ClickSound.pcm(ClickSound.STYLE_DING, 0.7f)));

            ok("★ 音量名与档位对应（轻/中/响）",
                    "轻".equals(ClickSound.volumeName(ClickSound.VOL_LOW))
                            && "中".equals(ClickSound.volumeName(ClickSound.VOL_MID))
                            && "响".equals(ClickSound.volumeName(ClickSound.VOL_HIGH)));
        }

        System.out.println("[32] ContentInset.roamDirAway 漫游方向（轮次60：贴哪边就往另一边走）");
        {
            // 屏幕 1080 宽、窗口 630 ⇒ 可达范围大概是 minX=-210 ~ maxX=661（实测值量级）
            final int MIN = -210, MAX = 661;
            ok("★★ 贴着**左边** ⇒ 向右（不再掷骰子）",
                    ContentInset.roamDirAway(MIN, MAX, MIN) == 1
                            && ContentInset.roamDirAway(MIN, MAX, MIN + 20) == 1);
            ok("★★ 贴着**右边** ⇒ 向左",
                    ContentInset.roamDirAway(MIN, MAX, MAX) == -1
                            && ContentInset.roamDirAway(MIN, MAX, MAX - 20) == -1);
            ok("★ 停在**中间** ⇒ 走向空间更大的一侧",
                    ContentInset.roamDirAway(MIN, MAX, 0) == 1
                            && ContentInset.roamDirAway(MIN, MAX, 600) == -1);
            ok("★ 正中间（左右一样大）⇒ 向右（取定值，不留随机）",
                    ContentInset.roamDirAway(-100, 100, 0) == 1);
            ok("★★ 确定性：同一位置连问 3 次结果全同（旧实现是 Math.random ⇒ 会变）",
                    ContentInset.roamDirAway(MIN, MAX, 300) == ContentInset.roamDirAway(MIN, MAX, 300)
                            && ContentInset.roamDirAway(MIN, MAX, 300)
                                    == ContentInset.roamDirAway(MIN, MAX, 300));
            ok("★ 贴着边往另一边走**一定有路**（这正是“有概率触发不了”的正解）",
                    MIN + ContentInset.roamDirAway(MIN, MAX, MIN) * 70 > MIN
                            && MAX + ContentInset.roamDirAway(MIN, MAX, MAX) * 70 < MAX);
            ok("★ 脏输入不抛异常（minX > maxX 自动交换；范围为零也返回一个方向）",
                    ContentInset.roamDirAway(MAX, MIN, 0) == ContentInset.roamDirAway(MIN, MAX, 0)
                            && (ContentInset.roamDirAway(5, 5, 5) == 1
                                || ContentInset.roamDirAway(5, 5, 5) == -1));
        }

        System.out.println("[33] ModelFile 本地 GGUF 模型的纯函数（轮次62 第二轮：导入层）");
        {
            // —— 造一个最小的 GGUF 头（真格式：magic4 + version4 + tensorCount8 + kvCount8 + KV…）——
            byte[] ok3 = ggufFixture(3, 291L, "Qwen2.5-0.5B-Instruct");
            ok("★★ 认得出 GGUF：前 4 字节 GGUF", ModelFile.isGguf(ok3));
            ok("★ 读到格式版本 3", ModelFile.ggufVersion(ok3) == 3, "实得 " + ModelFile.ggufVersion(ok3));
            ok("★ 读到张量个数 291", ModelFile.ggufTensorCount(ok3) == 291L,
                    "实得 " + ModelFile.ggufTensorCount(ok3));
            ok("★ 读到 KV 条数 2", ModelFile.ggufKvCount(ok3) == 2L,
                    "实得 " + ModelFile.ggufKvCount(ok3));
            ok("★★ 从头部元数据里读得出模型名（general.name）",
                    "Qwen2.5-0.5B-Instruct".equals(ModelFile.ggufName(ok3)),
                    "实得 " + ModelFile.ggufName(ok3));
            ok("★ 没有 general.name 的那份 ⇒ 名字返回 null（不瞎猜）",
                    ModelFile.ggufName(ggufFixture(3, 1L, null)) == null);

            // —— ★★ 不是 GGUF 必须被拒（zip 的头就是 PK\x03\x04）——
            byte[] zip = new byte[]{'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 0, 0, 8, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0};
            ok("★★ 压缩包（PK\\x03\\x04）判为不是 GGUF", !ModelFile.isGguf(zip));
            ok("★★ 不是 GGUF 时版本/条数一律 -1、名字 null（不是「随便读几个字节」）",
                    ModelFile.ggufVersion(zip) == -1 && ModelFile.ggufTensorCount(zip) == -1
                            && ModelFile.ggufKvCount(zip) == -1 && ModelFile.ggufName(zip) == null);
            ok("★ 脏输入不抛异常（null / 空 / 只有 2 字节）",
                    !ModelFile.isGguf(null) && !ModelFile.isGguf(new byte[0])
                            && !ModelFile.isGguf(new byte[]{'G', 'G'})
                            && ModelFile.ggufVersion(null) == -1
                            && ModelFile.ggufName(new byte[0]) == null);
            ok("★ magic 对但头被截断 ⇒ 不抛异常、字段给 -1（读不到就是读不到）",
                    ModelFile.isGguf(new byte[]{'G', 'G', 'U', 'F', 3, 0, 0, 0})
                            && ModelFile.ggufVersion(new byte[]{'G', 'G', 'U', 'F', 3, 0, 0, 0}) == -1);
            ok("★ 头部缓冲里值被截断 ⇒ 名字返回 null（绝不越界读）",
                    ModelFile.ggufName(java.util.Arrays.copyOf(ok3, ok3.length - 3)) == null);

            // —— ★★ 文件名净化（SAF 给的名字不能直接当路径用）——
            ok("★★ 只取最后一段路径（带 / 的名字不能指到目录外面）",
                    "model.gguf".equals(ModelFile.sanitizeName("/sdcard/Download/model.gguf")));
            ok("★★ 反斜杠路径同样只取最后一段",
                    "x.gguf".equals(ModelFile.sanitizeName("..\\evil\\x.gguf")));
            ok("★★ 全是点的名字一律拒绝（\".\" / \"..\" / \"...\"）",
                    ModelFile.sanitizeName(".") == null && ModelFile.sanitizeName("..") == null
                            && ModelFile.sanitizeName("...") == null);
            ok("★ 空 / null / 全空白 ⇒ null（调用方必须拒绝）",
                    ModelFile.sanitizeName(null) == null && ModelFile.sanitizeName("") == null
                            && ModelFile.sanitizeName("   ") == null);
            ok("★★ 非法字符换成下划线（: * ? \" < > |）",
                    "a_b_c_d.gguf".equals(ModelFile.sanitizeName("a:b*c?d.gguf")),
                    "实得 " + ModelFile.sanitizeName("a:b*c?d.gguf"));
            ok("★ 控制字符被剔掉（换行/制表打头的名字不能落盘）",
                    "ab.gguf".equals(ModelFile.sanitizeName("a\u0001b\n.gguf")),
                    "实得 " + ModelFile.sanitizeName("a\u0001b\n.gguf"));
            ok("★ 削掉前导点（点开头的文件在文件管理器里是隐藏的，用户会以为没导入）",
                    "hidden.gguf".equals(ModelFile.sanitizeName(".hidden")),
                    "实得 " + ModelFile.sanitizeName(".hidden"));
            ok("★ 没有 .gguf 后缀就补上（magic 才是真判据，名字只是给人看）",
                    "noext.gguf".equals(ModelFile.sanitizeName("noext")));
            ok("★ 已经带 .gguf / .GGUF 就不重复补",
                    "x.gguf".equals(ModelFile.sanitizeName("x.gguf"))
                            && "x.GGUF".equals(ModelFile.sanitizeName("x.GGUF")));
            String longName = ModelFile.sanitizeName(repeat("长", 200) + ".gguf");
            ok("★★ 超长名字按**字符**截到 64 + 扩展名（CJK 一字 3 字节 ⇒ 稳在 255 字节内）",
                    longName != null && longName.length() == ModelFile.MAX_BASE_CHARS + 5
                            && longName.endsWith(".gguf"),
                    "长度 " + (longName == null ? -1 : longName.length()));
            ok("★ 长名字按 UTF-8 算字节数也 < 255（真落盘不会 ENAMETOOLONG）",
                    longName != null && utf8Len(longName) < 255, "字节 " + utf8Len(longName));

            // —— 清单口径（哪些算模型、哪些算半拉文件）——
            ok("★★ 只有 *.gguf 算模型；*.gguf.part 不算（半拉文件不许出现在清单里）",
                    ModelFile.isModelName("a.gguf") && ModelFile.isModelName("a.GGUF")
                            && !ModelFile.isModelName("a.gguf.part")
                            && !ModelFile.isModelName("a.gguf.part") && !ModelFile.isModelName(null)
                            && !ModelFile.isModelName(".gguf"));
            ok("★ 半拉文件名 = 最终名 + .part",
                    "a.gguf.part".equals(ModelFile.partName("a.gguf"))
                            && ModelFile.isPartName(ModelFile.partName("a.gguf"))
                            && !ModelFile.isPartName("a.gguf"));
            ok("★ 同名判定大小写不敏感（同一文件系统上 a.gguf 与 A.gguf 可能是一个）",
                    ModelFile.sameName("a.gguf", "A.GGUF") && !ModelFile.sameName("a.gguf", "b.gguf"));

            // —— 体积文案 ——
            ok("★ 体积文案：0 / B / KB / MB / GB / TB 各档",
                    "0 B".equals(ModelFile.humanSize(0)) && "0 B".equals(ModelFile.humanSize(-5))
                            && "512 B".equals(ModelFile.humanSize(512))
                            && "1.0 KB".equals(ModelFile.humanSize(1024))
                            && "1.0 MB".equals(ModelFile.humanSize(1024L * 1024))
                            && "398.5 MB".equals(ModelFile.humanSize(417857536L))
                            && "1.0 GB".equals(ModelFile.humanSize(1024L * 1024 * 1024))
                            && "1.0 TB".equals(ModelFile.humanSize(1024L * 1024 * 1024 * 1024)),
                    ModelFile.humanSize(417857536L));
            // ★★ 同一份读数在不同手机上必须长得一样：默认 locale 会把小数点写成逗号
            java.util.Locale oldLoc = java.util.Locale.getDefault();
            try {
                java.util.Locale.setDefault(java.util.Locale.GERMANY);
                ok("★★ 体积文案固定 Locale.US 的小数点（德语环境下也必须是 398.5 而不是 398,5）",
                        "398.5 MB".equals(ModelFile.humanSize(417857536L)),
                        ModelFile.humanSize(417857536L));
            } finally {
                java.util.Locale.setDefault(oldLoc);
            }

            // —— 空间检查 ——
            final long MB = 1024L * 1024;
            ok("★★ 要留余量：400 MB 的模型要 400+64（64 > 10%）= 464 MB 才放行",
                    ModelFile.requiredFree(400 * MB) == 464 * MB,
                    "实得 " + ModelFile.humanSize(ModelFile.requiredFree(400 * MB)));
            ok("★★ 大文件按 10% 留（2 GB 要再留 204.8 MB ⇒ 2.2 GB 量级）",
                    ModelFile.requiredFree(2048 * MB) == 2048 * MB + (2048 * MB) / 10,
                    "实得 " + ModelFile.humanSize(ModelFile.requiredFree(2048 * MB)));
            ok("★★ 刚好够 ⇒ 放行；少 1 字节 ⇒ 拒绝",
                    ModelFile.enoughSpace(ModelFile.requiredFree(400 * MB), 400 * MB)
                            && !ModelFile.enoughSpace(ModelFile.requiredFree(400 * MB) - 1, 400 * MB));
            ok("★★ 脏输入一律判「不够」（可用空间读不到/为 0 ⇒ 不导入，也不能开到一半失败）",
                    !ModelFile.enoughSpace(0, 100) && !ModelFile.enoughSpace(-1, 100)
                            && !ModelFile.enoughSpace(0, -1) && !ModelFile.enoughSpace(100, 200));
            ok("★ 大小未知（<=0）⇒ 只要求留 64 MB 余量",
                    ModelFile.requiredFree(0) == 64 * MB && ModelFile.requiredFree(-9) == 64 * MB
                            && ModelFile.enoughSpace(64 * MB, 0) && !ModelFile.enoughSpace(64 * MB - 1, 0));
            ok("★ 溢出兜底（Long.MAX_VALUE 附近不返回负数）",
                    ModelFile.requiredFree(Long.MAX_VALUE) > 0);

            // —— 设置页那一行 ——
            ok("★ 入口那一行：没导入写「未导入」",
                    "未导入".equals(ModelFile.summary(null, 0, 0))
                            && "未导入".equals(ModelFile.summary("", 0, 0)));
            ok("★ 入口那一行：已导入写「名字 · 体积」",
                    "a.gguf · 398.5 MB".equals(ModelFile.summary("a.gguf", 417857536L, 1)),
                    ModelFile.summary("a.gguf", 417857536L, 1));
            ok("★ 多个模型时缀一个总数（用户要知道自己导了几个）",
                    ModelFile.summary("a.gguf", 417857536L, 3).endsWith("（共 3 个）"));

            // 源文件里必须有 —— 它被 fpstest 真编真跑，说明"零 Android 依赖"这条判据成立
            ok("★ ModelFile 零 Android 依赖（能被光秃秃的 javac 编出来就是证明）",
                    ModelFile.class.getName().startsWith("com.deepseek.pet"));
        }

        System.out.println("[34] LocalPrompt 本地小模型的提示词与清理（轮次64 第三轮：对话接线）");
        {
            // —— ★★ 对话模板按模型名认 ——
            //    本地引擎（LocalLlm）收的是**一条裸字符串**，JNI 里直接 tokenize，
            //    **没有任何 chat template**（在线那条链是服务端按模型自己套的）。
            //    模板拼错不会报错，只会"答非所问 / 复读"——真机上最难看出来的那类。
            ok("★★ Qwen 系 ⇒ ChatML（手机上的中文小模型绝大多数是它）",
                    LocalPrompt.T_CHATML.equals(
                            LocalPrompt.templateFor("Qwen2.5-0.5B-Instruct-Q4_K_M.gguf")));
            ok("★ Llama-3.x 的 instruct 版 ⇒ Llama-3 头标记",
                    LocalPrompt.T_LLAMA3.equals(
                            LocalPrompt.templateFor("Llama-3.2-1B-Instruct-Q4_K_M.gguf")));
            ok("★ Gemma ⇒ Gemma 轮次标记",
                    LocalPrompt.T_GEMMA.equals(
                            LocalPrompt.templateFor("gemma-2-2b-it-Q4_K_M.gguf")));
            ok("★ DeepSeek 的蒸馏小模型走 ChatML（它是 Qwen 的模板，不是 llama 的）",
                    LocalPrompt.T_CHATML.equals(
                            LocalPrompt.templateFor("DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf")));
            ok("★ 认不出 ⇒ 默认 ChatML（不是「放弃」，而是选覆盖面最大的那个）",
                    LocalPrompt.T_CHATML.equals(LocalPrompt.templateFor("totally-unknown.gguf"))
                            && LocalPrompt.T_CHATML.equals(LocalPrompt.templateFor(null))
                            && LocalPrompt.T_CHATML.equals(LocalPrompt.templateFor("")));

            // —— ★★ token 估算（**保守**：估小了会在真机上撞「ctx 太小」）——
            ok("★★ 表意文字 1 字 ≈ 1 token", LocalPrompt.estimateTokens("你好世界") == 4,
                    "实得 " + LocalPrompt.estimateTokens("你好世界"));
            ok("★ 拉丁字母约 4 字符 1 token，且向上取整（宁可高估）",
                    LocalPrompt.estimateTokens("abcd") == 1
                            && LocalPrompt.estimateTokens("abcde") == 2);
            ok("★ 中英混排分开算", LocalPrompt.estimateTokens("你好abcd") == 3,
                    "实得 " + LocalPrompt.estimateTokens("你好abcd"));
            ok("★ 脏输入不抛：null / 空 ⇒ 0",
                    LocalPrompt.estimateTokens(null) == 0 && LocalPrompt.estimateTokens("") == 0);

            // —— ★★ 上下文预算：必须给输出留位置 ——
            ok("★★ 提示词预算 = ctx − 输出上限 − 余量",
                    LocalPrompt.maxPromptTokens(2048, 128) == 2048 - 128 - 64,
                    "实得 " + LocalPrompt.maxPromptTokens(2048, 128));
            ok("★★ 预算有硬下限（ctx 给得再小也不会算出 0 或负数）",
                    LocalPrompt.maxPromptTokens(64, 256) == 128);
            ok("★ 传 0 ⇒ 退回引擎默认（ctx 2048 / 出 256）",
                    LocalPrompt.maxPromptTokens(0, 0) == 2048 - 256 - 64);

            // —— ★★ 历史裁剪 ——
            java.util.List<String[]> h = new java.util.ArrayList<String[]>();
            h.add(new String[]{"user", "第一句"});
            h.add(new String[]{"assistant", "第一答"});
            h.add(new String[]{"user", "第二句"});
            h.add(new String[]{"assistant", "第二答"});
            java.util.List<String[]> all = LocalPrompt.trimHistory(h, 1000);
            ok("★★ 预算够 ⇒ 历史全带上，且顺序不乱",
                    all.size() == 4 && "第一句".equals(all.get(0)[1])
                            && "第二答".equals(all.get(3)[1]));
            java.util.List<String[]> two = LocalPrompt.trimHistory(h, 15);
            ok("★★ 预算不够 ⇒ 只留**最近**的（人聊天时最近的话最要紧）",
                    two.size() == 2 && "第二句".equals(two.get(0)[1]));
            ok("★★ 裁完若开头是桌宠自己说的 ⇒ 连它一起丢（否则小模型会接着自说自话）",
                    LocalPrompt.trimHistory(h, 10).isEmpty());
            ok("★ 脏输入不抛：null / 预算<=0 ⇒ 空表",
                    LocalPrompt.trimHistory(null, 100).isEmpty()
                            && LocalPrompt.trimHistory(h, 0).isEmpty());

            // —— ★★ 渲染：模板标记错了模型就乱答 ——
            java.util.List<String[]> ms = LocalPrompt.messages("你是小猫", "主人叫小明", null,
                    LocalPrompt.trimHistory(h, 1000), "在吗");
            String chatml = LocalPrompt.render(LocalPrompt.T_CHATML, ms);
            ok("★★ ChatML：三段标记齐全，且**结尾停在 assistant 开头**（把话头交给模型）",
                    chatml.indexOf("<|im_start|>system\n") == 0
                            && chatml.indexOf("<|im_start|>user\n") > 0
                            && chatml.endsWith("<|im_start|>assistant\n"));
            ok("★★ 人格 + 记忆点 + 风格要求都进了 system 段（不是只发用户那一句）",
                    chatml.indexOf("你是小猫") > 0 && chatml.indexOf("主人叫小明") > 0
                            && chatml.indexOf("本机小模型模式") > 0);
            ok("★★ 本次用户消息排在**最后**（模型要接的是它）",
                    chatml.lastIndexOf("在吗") > chatml.lastIndexOf("第二答"));
            String llama3 = LocalPrompt.render(LocalPrompt.T_LLAMA3, ms);
            ok("★ Llama-3 用自己那套头标记（不是把 ChatML 硬套上去）",
                    llama3.indexOf("<|start_header_id|>") == 0
                            && llama3.endsWith("<|end_header_id|>\n\n"));
            String gemma = LocalPrompt.render(LocalPrompt.T_GEMMA, ms);
            ok("★★ Gemma 没有独立 system 轮 ⇒ 系统段并进第一条 user（丢了人格最要命）",
                    gemma.indexOf("<start_of_turn>user\n你是小猫") == 0
                            && gemma.indexOf("<start_of_turn>system") < 0
                            && gemma.endsWith("<start_of_turn>model\n"));
            ok("★ 脏输入不抛：空消息序列 ⇒ 空串",
                    LocalPrompt.render(LocalPrompt.T_CHATML, null).length() == 0
                            && LocalPrompt.render(LocalPrompt.T_CHATML,
                                    new java.util.ArrayList<String[]>()).length() == 0);

            // —— ★★ 回复清理 ——
            ok("★★ 截断在结束标记处（有的量化模型会把 <|im_end|> 当正文吐出来）",
                    "你好呀".equals(LocalPrompt.cleanReply("你好呀<|im_end|>assistant\n坏东西")));
            ok("★ 去掉开头的自我称呼（assistant: / 助手：）",
                    "你好".equals(LocalPrompt.cleanReply("assistant: 你好"))
                            && "你好".equals(LocalPrompt.cleanReply("助手：你好")));
            ok("★★ 整段被引号/书名号包住 ⇒ 剥掉（桌宠台词不该带壳）",
                    "喵".equals(LocalPrompt.cleanReply("\u300C喵\u300D"))
                            && "喵".equals(LocalPrompt.cleanReply("\"\u55B5\""))
                            && "喵".equals(LocalPrompt.cleanReply("\u201C喵\u201D")));
            ok("★ 只剥**成对**的壳：单边引号要留着（那是内容，不是壳）",
                    "\u300C喵".equals(LocalPrompt.cleanReply("\u300C喵")));
            ok("★ 脏输入不抛：null ⇒ 空串", "".equals(LocalPrompt.cleanReply(null)));

            // —— ★★ 采样温度：在线那套 1.3 搬到 0.5B 上会复读 ——
            ok("★★ 在线默认的 1.3 被夹到 1.0（小模型高温会复读、串语言、把指令当正文）",
                    LocalPrompt.tempFor(1.3) == 1.0f && LocalPrompt.tempFor(1.0) == 1.0f);
            ok("★★ <=0 **不能**直接透传（引擎里 <=0 是「贪心解码」⇒ 每次回复一模一样）",
                    LocalPrompt.tempFor(0) == LocalPrompt.TEMP_DEF
                            && LocalPrompt.tempFor(-1) == LocalPrompt.TEMP_DEF);
            ok("★ 下限 0.5：太低就成复读机", LocalPrompt.tempFor(0.1) == 0.5f);

            // —— ★★ 流式节流（回调频率本身就是一种资源）——
            ok("★★ 第一段必须立刻刷（否则首字要等满一个间隔才出现）",
                    LocalPrompt.shouldFlush(0, 12345, 250));
            ok("★★ 间隔没到不刷、到了就刷",
                    !LocalPrompt.shouldFlush(1000, 1200, 250)
                            && LocalPrompt.shouldFlush(1000, 1250, 250));
            ok("★ 传 0 或负数间隔 ⇒ 退回默认 250ms（不是「每个 token 都刷」）",
                    !LocalPrompt.shouldFlush(1000, 1001, 0)
                            && LocalPrompt.shouldFlush(1000, 1250, 0));

            ok("★ LocalPrompt 零 Android 依赖（能被光秃秃的 javac 编出来就是证明）",
                    LocalPrompt.class.getName().startsWith("com.deepseek.pet"));
        }

        System.out.println("[35] PeekPose 挂边探头姿态（轮次77：只露头肩 + 呼吸 + 小探头）");
        {
            // —— keep 的语义：1 = 完全在屏内，越小 = 越往屏幕外藏 ——
            ok("OFF 是恒等姿态",
                    PeekPose.IDENTITY.keep == 1f
                            && PeekPose.IDENTITY.lean == 0f
                            && PeekPose.at(PeekPose.OFF, 999, 999).keep == 1f);

            // ★★ 挤的时候不许先探出去：否则观感是"还没准备好就滑出屏"
            float maxKeepSqueeze = 0f, minKeepSqueeze = 2f;
            for (int tS = 0; tS <= PeekPose.SQUEEZE_MS; tS++) {
                float k = PeekPose.keepOf(PeekPose.SQUEEZE, tS, 1f);
                if (k > maxKeepSqueeze) maxKeepSqueeze = k;
                if (k < minKeepSqueeze) minKeepSqueeze = k;
            }
            ok("★★ 挤压阶段 keep 恒为 1（只挤不探）",
                    maxKeepSqueeze == 1f && minKeepSqueeze == 1f);

            // ★★ 过冲：先多露出去一点再收回来，才有"探"的动感
            float probeMin = 2f;
            for (int tP = 0; tP <= PeekPose.PROBE_MS; tP++) {
                float k = PeekPose.keepOf(PeekPose.PROBE, tP, 1f);
                if (k < probeMin) probeMin = k;
            }
            ok("★★ PROBE 谷底低于常驻位（过冲真实存在）",
                    probeMin < PeekPose.PEER_KEEP - 0.02f, "min=" + probeMin);
            ok("★★ PROBE 收尾回到常驻位",
                    Math.abs(PeekPose.keepOf(PeekPose.PROBE, PeekPose.PROBE_MS, 1f)
                            - PeekPose.PEER_KEEP) < 0.005f);

            // ★★★ 这条是"露头量锚在身体框"的判据：呼吸不许改变露出量
            boolean perchStable = true;
            float perchMin = 2f, perchMax = -1f;
            for (long age = 0; age <= 6000; age += 50) {
                float k = PeekPose.at(PeekPose.PERCH, 0, age).keep;
                if (Math.abs(k - PeekPose.PEER_KEEP) > 1e-6f) perchStable = false;
                if (k < perchMin) perchMin = k;
                if (k > perchMax) perchMax = k;
            }
            ok("★★★ PERCH 全程 keep 恒等于 PEER_KEEP（呼吸只动角度/横向）",
                    perchStable, "min=" + perchMin + " max=" + perchMax);

            ok("★ 呼吸首尾归零、中途不为零",
                    PeekPose.breathDp(0) == 0f
                            && Math.abs(PeekPose.breathDp(PeekPose.BREATH_PERIOD_MS)) < 1e-4f
                            && Math.abs(PeekPose.breathLean(PeekPose.BREATH_PERIOD_MS / 4)) > 0.5f);

            float nudgeMin = 2f;
            for (int tN = 0; tN <= PeekPose.NUDGE_MS; tN++) {
                float k = PeekPose.keepOf(PeekPose.NUDGE, tN, PeekPose.PEER_KEEP);
                if (k < nudgeMin) nudgeMin = k;
            }
            ok("小探头：中段再探出去、收尾回常驻位",
                    nudgeMin < PeekPose.PEER_KEEP - 0.05f
                            && Math.abs(PeekPose.keepOf(PeekPose.NUDGE, PeekPose.NUDGE_MS,
                                    PeekPose.PEER_KEEP) - PeekPose.PEER_KEEP) < 0.005f);

            ok("被点抬头：收尾 = LIFT_KEEP 且角度归零（站直了）",
                    Math.abs(PeekPose.keepOf(PeekPose.LIFT, PeekPose.LIFT_MS, PeekPose.PEER_KEEP)
                            - PeekPose.LIFT_KEEP) < 0.005f
                            && PeekPose.at(PeekPose.LIFT, PeekPose.LIFT_MS, 0).lean < 1e-4f);

            // ★★ 从任意露出量缩回都不许跳变（用户半截处拖动才不"啪"一下）
            boolean noJump = true;
            for (float from : new float[]{PeekPose.PEER_KEEP, PeekPose.LIFT_KEEP, 0.75f, 1.0f}) {
                float k0 = PeekPose.keepOf(PeekPose.WITHDRAW, 0, from);
                if (Math.abs(k0 - from) > 0.005f) noJump = false;
            }
            ok("★★ WITHDRAW 起点等于 fromKeep（缩回不跳变）", noJump);
            ok("WITHDRAW 收尾 = 1（完全回到屏内）",
                    Math.abs(PeekPose.keepOf(PeekPose.WITHDRAW, PeekPose.WITHDRAW_MS, 0.62f)
                            - 1f) < 0.005f);

            // ★★ SETTLE：拖动松手"没拉够 ⇒ 自己挂回去"的那一段（轮次77 交互修复）
            ok("★★ SETTLE 起点等于 fromKeep（松手瞬间不跳）",
                    Math.abs(PeekPose.keepOf(PeekPose.SETTLE, 0, 0.80f) - 0.80f) < 0.005f);
            ok("★★ SETTLE 收尾回到常驻位（挂回去）",
                    Math.abs(PeekPose.keepOf(PeekPose.SETTLE, PeekPose.SETTLE_MS, 0.80f)
                            - PeekPose.PEER_KEEP) < 0.005f);
            ok("★ SETTLE 从比常驻更靠外处起步也按常驻位收（不会越收越外）",
                    Math.abs(PeekPose.keepOf(PeekPose.SETTLE, PeekPose.SETTLE_MS, 0.3f)
                            - PeekPose.PEER_KEEP) < 0.005f);

            // ★★★ 拖动跟手的两条地基：x ⟺ 露出量的往返，以及"露头量决定倾角"
            boolean xRoundTrip = true;
            for (float kk : new float[]{PeekPose.PEER_KEEP, 0.75f, 0.9f, 1.0f}) {
                int xl2 = PeekPose.peekedX(-195, 400, kk, PeekPose.EDGE_LEFT);
                if (Math.abs(PeekPose.keepForX(-195, 400, xl2, PeekPose.EDGE_LEFT) - kk) > 0.02f) {
                    xRoundTrip = false;
                }
                int xr2 = PeekPose.peekedX(-195, 400, kk, PeekPose.EDGE_RIGHT);
                if (Math.abs(PeekPose.keepForX(-195, 400, xr2, PeekPose.EDGE_RIGHT) - kk) > 0.02f) {
                    xRoundTrip = false;
                }
            }
            ok("★★★ peekedX / keepForX 互为逆运算（左右都对）", xRoundTrip);
            ok("★ 窗口完全在屏内 ⇒ keep=1（拖出来就退出挂边）",
                    PeekPose.keepForX(0, 400, 40, PeekPose.EDGE_LEFT) == 1f
                            && PeekPose.keepForX(0, 400, -40, PeekPose.EDGE_RIGHT) == 1f);

            ok("★★★ 露头量决定倾角：完全在屏内站直、挂到常驻位才 12°",
                    PeekPose.leanForKeep(1f) == 0f
                            && Math.abs(PeekPose.leanForKeep(PeekPose.PEER_KEEP)
                                    - PeekPose.LEAN_PEER_DEG) < 1e-4f);
            boolean leanMono = true;
            float leanPrev = 99f;
            for (float kk = PeekPose.PEER_KEEP; kk <= 1.0001f; kk += 0.05f) {
                float l = PeekPose.leanForKeep(kk);
                if (l > leanPrev + 1e-4f) leanMono = false;
                leanPrev = l;
            }
            ok("★ 倾角随拉出单调减小（不会越拉越歪）", leanMono);
            // ★★★ 轮次78：PROBE 的终点角度必须**正好**等于设定值（曾多乘一次因子 ⇒ 歪过头再闪回）
            boolean probeLeanOk = true;
            String probeBad = "";
            for (float lm : new float[]{6f, 16f, 30f, 45f}) {
                PeekPose.Tuning tn = PeekPose.Tuning.sanitized(PeekPose.PEER_KEEP, lm);
                float endLean = PeekPose.at(PeekPose.PROBE, PeekPose.PROBE_MS, 0, 1f, tn).lean;
                if (Math.abs(endLean - lm) > 1e-3f) { probeLeanOk = false; probeBad = lm + "→" + endLean; }
                for (long tl = 0; tl <= PeekPose.PROBE_MS; tl += 10) {
                    if (PeekPose.at(PeekPose.PROBE, tl, 0, 1f, tn).lean > lm + 1e-3f) {
                        probeLeanOk = false; probeBad = "超 " + lm;
                    }
                }
            }
            ok("★★★ PROBE 终点角度 == 设定值且全程不超（歪过头闪回的根因）", probeLeanOk, probeBad);
            // ★★ 抬头回挂必须走 SETTLE：倾角从 0 单调长回 leanMax
            boolean settleOk = true;
            float prevLean2 = -1f;
            for (long tl = 0; tl <= PeekPose.SETTLE_MS; tl += 10) {
                PeekPose.Pose pz = PeekPose.at(PeekPose.SETTLE, tl, 0, PeekPose.LIFT_KEEP,
                        PeekPose.Tuning.DEFAULT);
                if (pz.lean < prevLean2 - 1e-4f) settleOk = false;
                prevLean2 = pz.lean;
            }
            ok("★★ 抬头回挂用 SETTLE：倾角单调长回 leanMax（不再闪一下）", settleOk);
            ok("★★ SETTLE 起点 = 抬头露出量、收尾 = 常驻位",
                    Math.abs(PeekPose.keepOf(PeekPose.SETTLE, 0, PeekPose.LIFT_KEEP)
                            - PeekPose.LIFT_KEEP) < 5e-3f
                            && Math.abs(PeekPose.keepOf(PeekPose.SETTLE, PeekPose.SETTLE_MS,
                                    PeekPose.LIFT_KEEP) - PeekPose.PEER_KEEP) < 5e-3f);
            ok("★ 挂边倾角够「歪」但不至于横躺（10~25 度）",
                    PeekPose.LEAN_PEER_DEG >= 10f && PeekPose.LEAN_PEER_DEG <= 25f);

            ok("★★ 拖动姿态在常驻位 = 挂住姿态的角度（衔接无跳变）",
                    Math.abs(PeekPose.dragPose(PeekPose.PEER_KEEP).lean
                            - PeekPose.LEAN_PEER_DEG) < 1e-4f
                            && Math.abs(PeekPose.dragPose(1f).lean) < 1e-4f);

            // ★ 角度上限：三档之和，超出就是有人偷偷加了大角度旋转
            float leanCap = PeekPose.LEAN_PEER_DEG + PeekPose.LEAN_NUDGE_EXTRA_DEG
                    + PeekPose.BREATH_DEG;
            boolean leanInRange = true;
            int[] phases = {PeekPose.SQUEEZE, PeekPose.PROBE, PeekPose.PERCH,
                    PeekPose.NUDGE, PeekPose.LIFT, PeekPose.WITHDRAW, PeekPose.SETTLE};
            for (int ph : phases) {
                for (long tL = 0; tL <= 1200; tL += 20) {
                    if (PeekPose.at(ph, tL, 500).lean > leanCap + 1e-4f) leanInRange = false;
                }
            }
            ok("★ 侧倾量级不超过三档之和", leanInRange, "cap=" + leanCap);

            // ★★ 可调参数（用户要求：角度 / 深度自己调）
            PeekPose.Tuning t30 = PeekPose.Tuning.sanitized(0.45f, 30f);
            PeekPose.Pose p30 = PeekPose.at(PeekPose.PERCH, 0, 0, PeekPose.PEER_KEEP, t30);
            ok("★★ 自定义深度生效：PERCH 的 keep == 设定值",
                    Math.abs(p30.keep - 0.45f) < 1e-4f, "keep=" + p30.keep);
            ok("★★ 自定义角度生效：PERCH 的倾角 == 设定值",
                    Math.abs(p30.lean - 30f) < 1e-4f, "lean=" + p30.lean);
            ok("★★ 拉出到 keep=1 时倾角仍归零（自定义角度也是）",
                    Math.abs(PeekPose.dragPose(1f, t30).lean) < 1e-4f);
            ok("★ 拖动姿态的下限跟着自定义深度走",
                    Math.abs(PeekPose.dragPose(0.1f, t30).keep - 0.45f) < 1e-4f);
            PeekPose.Tuning tBad = PeekPose.Tuning.sanitized(0.1f, 90f);
            ok("★★ 越界参数被夹回安全区间（露出 0.30~0.95 / 角度 0~45）",
                    Math.abs(tBad.peerKeep - 0.30f) < 1e-4f
                            && Math.abs(tBad.leanDeg - 45f) < 1e-4f);
            ok("★★ 默认参数 = 常量（不设就是不改变观感）",
                    Math.abs(PeekPose.Tuning.DEFAULT.peerKeep - PeekPose.PEER_KEEP) < 1e-6f
                            && Math.abs(PeekPose.Tuning.DEFAULT.leanDeg - PeekPose.LEAN_PEER_DEG) < 1e-6f);

            ok("★ 左右只差一个符号（几何不写第二遍）",
                    PeekPose.leanSigned(10f, PeekPose.EDGE_LEFT) == 10f
                            && PeekPose.leanSigned(10f, PeekPose.EDGE_RIGHT) == -10f);

            // ★ 位移换算：藏出去的量 = 身体宽 ×(1-keep)，方向由边决定
            int bodyW = 400;
            float keep = PeekPose.PEER_KEEP;
            int hidden = (int) (bodyW * (1f - keep) + 0.5f);
            int xl = PeekPose.peekedX(0, bodyW, keep, PeekPose.EDGE_LEFT);
            int xr = PeekPose.peekedX(0, bodyW, keep, PeekPose.EDGE_RIGHT);
            ok("★ 贴左边 ⇒ x 变小、贴右边 ⇒ x 变大，量 = 身体宽×(1-keep)",
                    xl == -hidden && xr == hidden, "hidden=" + hidden);
            ok("★ keep=1 时位移为 0（正常态不挪窗口）",
                    PeekPose.peekedX(123, bodyW, 1f, PeekPose.EDGE_LEFT) == 123);
            ok("肩线旋转轴在身体高度的 34% 处",
                    PeekPose.shoulderPivotY(60, 270) == 60 + (int) (270 * 0.34f));

            // ★ 小探头间隔：区间 + 确定性（不是 random，断言才钉得住）
            boolean delayOk = true;
            for (int seed = 0; seed < 200; seed++) {
                long d = PeekPose.nextNudgeDelayMs(seed);
                if (d < PeekPose.NUDGE_MIN_MS || d > PeekPose.NUDGE_MAX_MS) delayOk = false;
                if (d != PeekPose.nextNudgeDelayMs(seed)) delayOk = false;
            }
            ok("★★ 小探头间隔落在区间内且同种子同值（可复现）", delayOk);

            // ★ 垃圾输入：负时刻、未知阶段，一律退化成恒等姿态，绝不抛
            boolean safe = true;
            try {
                safe = PeekPose.at(-1, -500, -500).keep == 1f
                        && PeekPose.at(999, 10, 10).keep == 1f
                        && PeekPose.peekedX(0, -5, 2f, PeekPose.EDGE_LEFT) == 0
                        && PeekPose.keepOf(PeekPose.WITHDRAW, 0, -3f) >= PeekPose.PEER_KEEP;
            } catch (Throwable ex) {
                safe = false;
            }
            ok("★ 垃圾输入安全（负时刻/未知阶段/负宽度）", safe);

            ok("★ PeekPose 零 Android 依赖（能被光秃秃的 javac 编出来就是证明）",
                    PeekPose.class.getName().startsWith("com.deepseek.pet"));
        }

        System.out.println();
        System.out.println("==========================================================");
        if (fails > 0) {
            System.out.println(fails + " / " + total + " 项失败");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED (" + total + " 项)");
    }
}
