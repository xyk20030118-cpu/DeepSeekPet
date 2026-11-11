package com.deepseek.pet;

/**
 * 纯本地模式的**自定义发言** —— 场景表 + 文本解析 + 选句，零 Android 依赖。
 *
 * <h3>为什么单独一个纯类</h3>
 * 「用户填了一堆台词，却没生效」这种问题在界面上只表现为"它没按我写的说"，
 * 分不清是解析错了、选句错了还是压根没读出来。抽成纯 Java 就能在桌面 JVM 上
 * 逐条断言（见 {@code build/fpstest} 第 [17] 节），验的是打进 APK 的那份代码本身。
 *
 * <h3>语义（用户 2026-10-07 要求）</h3>
 * <ul>
 *   <li>每个场景一个**多行文本框**，一行一句；</li>
 *   <li><b>留空 = 用内置台词池</b>（不是"不说话"）—— 这样用户只改想改的那几个场景；</li>
 *   <li>填了就**只**从用户写的里面挑，不会混进内置句子（否则"我删掉的那句又出现了"）；</li>
 *   <li>纯本地模式下"对话回复"也是一个场景（{@link #CHAT}），所以输入框发消息
 *       也能有本地回应，而不是只回一句"不会调用 API"。</li>
 * </ul>
 */
public final class LocalLines {

    private LocalLines() {}

    // ---- 历史 id（**prefs 键后缀，绝不能改字面量**：改了用户的台词就丢了）----
    //
    // ★ 轮次㊲：场景表**改成与动作清单一一对应**（用户要求「对话台词选项同步动作选项
    //   （动作有那些选项 台词就有哪些选项）」）⇒ 键后缀优先用**动作场景 id**。
    //   下面四个是历史遗留的键名，各对应一个动作场景（见 keyOf）—— 留着是为了不动用户数据。
    /** 历史键：点一下（= 动作场景 {@code click}） */
    public static final String TAP = "tap";
    /** 历史键：被连戳（= 动作场景 {@code interact}） */
    public static final String TAP_ANNOYED = "tap_annoyed";
    /** 历史键：被拎起来 / 拖动（= 动作场景 {@code drag}） */
    public static final String LIFT = "lift";
    /** 历史键：主动搭话（= 动作场景 {@code whisper}） */
    public static final String PROACTIVE = "proactive";

    // ★ 曾经有 DOUBLE_TAP / LONG_PRESS 两个场景被**删除**过（理由：那两条路不说话）。
    //   轮次㊲ 又加回来了 —— 但这次**它们真的会说话**（双击确认后 / 长按弹菜单前各说一句），
    //   而且 id 就是动作场景 id，所以"台词选项 = 动作选项"成立、不是摆着不生效的入口。
    // ★ 曾经有过一个 CHAT 场景（"纯本地对话回复"），**仍是删除状态**：
    //   用户 2026-10-07 定稿口径 —— 凡是"打字发消息"的入口（双击桌宠弹出的输入条、
    //   完整对话窗口）**都只走 API**。旧值 `local_line_chat` 留在 prefs 里无害。

    /**
     * 全部场景 = **动作场景清单**（顺序也一致）。
     *
     * <p>★ 这就是"台词选项同步动作选项"的**结构性保证**：清单直接从 {@link SceneRegistry}
     * 派生，所以往动作系统里加场景时，台词页**自动**多一行，两张表不可能再各说各话
     * （判据见 {@code verify_local_lines.py} 第 1 节 + {@code verify_scene_exits.py}）。
     */
    public static final String[] IDS = SceneRegistry.allIds();

    /**
     * 场景中文名（与 {@link #IDS} 一一对应）。
     * <p>**派生自** {@link SceneRegistry} 的标签表（不是手抄的第二份），所以改场景名只需要改一处。
     */
    public static final String[] LABELS = labelsOf(IDS);

    private static String[] labelsOf(String[] ids) {
        String[] out = new String[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = SceneRegistry.label(ids[i]);
        return out;
    }

    /**
     * 部位 → 台词场景（{@code head} / {@code body} / {@code tail}；不认识的部位返回 null）。
     *
     * <p>★ 轮次㊲（用户要求「**被戳头尾部身体的时候也有自定义对话**」）：以前戳哪个部位都走
     * "点一下"那一套池 ⇒ 用户没法给"摸头"和"拽尾巴"写不同的话。
     * <p>与 {@code SceneRegistry.partScene}（**动作**场景）是两张表各一份：动作场景决定
     * "演哪个动作"（由角色包素材决定），台词场景决定"说什么话"（由用户自定义文本决定），
     * 两者互不依赖、可以只配一边；id 相同（都在 {@link SceneRegistry} 的清单里）。
     */
    public static String sceneForPart(String part) {
        if ("head".equals(part)) return SceneRegistry.TAP_HEAD;
        if ("body".equals(part)) return SceneRegistry.TAP_BODY;
        if ("tail".equals(part)) return SceneRegistry.TAP_TAIL;
        return null;
    }

    /**
     * 动作场景 id → **prefs 键后缀**（只有四个历史场景需要翻译，其余同名）。
     *
     * <p>为什么不让调用方直接用动作 id 当键：那四个历史键里已经存着用户写过的台词，
     * 换键名 = 用户的台词静默消失。
     */
    public static String keyOf(String scene) {
        if (SceneRegistry.CLICK.equals(scene)) return TAP;
        if (SceneRegistry.INTERACT.equals(scene)) return TAP_ANNOYED;
        if (SceneRegistry.DRAG.equals(scene)) return LIFT;
        if (SceneRegistry.EV_WHISPER.equals(scene)) return PROACTIVE;
        return scene;
    }

    /** 场景是否合法（写 prefs 前必须校验，否则会写出用不上的脏键）。 */
    public static boolean isValid(String id) {
        if (id == null) return false;
        if (SceneRegistry.isKnown(id)) return true;
        // 历史键也认（老调用点 / 老数据不炸）
        for (String s : new String[]{TAP, TAP_ANNOYED, LIFT, PROACTIVE}) {
            if (s.equals(id)) return true;
        }
        return false;
    }

    /** 场景中文名（未知 id 返回 id 本身，绝不返回 null）。 */
    public static String label(String id) {
        if (SceneRegistry.isKnown(id)) return SceneRegistry.label(id);
        if (TAP.equals(id)) return "点一下";
        if (TAP_ANNOYED.equals(id)) return "被连戳（烦了）";
        if (LIFT.equals(id)) return "被拎起来 / 拖动";
        if (PROACTIVE.equals(id)) return "主动搭话";
        return String.valueOf(id);
    }

    /** 场景在 {@link #IDS} 里的下标（未知返回 -1）。 */
    public static int indexOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) return i;
        }
        return -1;
    }

    /** 每个场景最多保留多少句（防手滑把一整篇小说贴进 prefs）。 */
    public static final int MAX_LINES = 40;
    /** 单句最长字符数（超出截断，避免一条台词把气泡撑爆）。 */
    public static final int MAX_LINE_LEN = 80;

    /**
     * 把用户填的多行文本解析成台词数组。
     *
     * <p>规则（每条都有意为之）：
     * <ul>
     *   <li>按 {@code \n} 拆行，{@code \r} 一并当分隔（Windows 粘贴进来的是 CRLF）；</li>
     *   <li>逐行 trim；**空行丢掉**（用户习惯用空行分组）；</li>
     *   <li>**去重**（同一句写两遍会让它出现概率翻倍，用户看不出来为什么）；</li>
     *   <li>单行超长截断到 {@link #MAX_LINE_LEN}；总条数截到 {@link #MAX_LINES}；</li>
     *   <li>{@code null} / 空串 → **长度 0 的数组**（调用方据此退回内置池）。</li>
     * </ul>
     */
    public static String[] parseLines(String raw) {
        if (raw == null) return new String[0];
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = s.split("\n");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        for (String p : parts) {
            if (p == null) continue;
            String line = p.trim();
            if (line.length() == 0) continue;
            if (line.length() > MAX_LINE_LEN) line = line.substring(0, MAX_LINE_LEN);
            out.add(line);
            if (out.size() >= MAX_LINES) break;
        }
        return out.toArray(new String[0]);
    }

    /**
     * 从台词数组里挑一句。
     *
     * @param lines 台词（可为 null / 空 → 返回 null，调用方自行兜底）
     * @param r01   0~1 的随机数（由调用方给，方便桌面 JVM 上做确定性断言）
     */
    public static String pick(String[] lines, double r01) {
        if (lines == null || lines.length == 0) return null;
        int i = (int) (r01 * lines.length);
        if (i < 0) i = 0;
        if (i >= lines.length) i = lines.length - 1;
        return lines[i];
    }

    /** 设置页那一行右侧的摘要：「用内置台词」/「自定义 3 句：xxx…」。 */
    public static String summary(String raw) {
        String[] lines = parseLines(raw);
        if (lines.length == 0) return "用内置台词";
        String first = lines[0];
        if (first.length() > 12) first = first.substring(0, 12) + "…";
        return "自定义 " + lines.length + " 句：" + first;
    }

    /**
     * 选一句：**自定义优先**，自定义为空才用内置。
     *
     * <p>★ 为什么把这段优先级也放进纯类（而不是留在服务里）：它是"用户写的话到底有没有
     * 生效"的唯一判据，留在 Android 类里就只能靠"源码里两行的先后顺序"去断言 ——
     * 而顺序断言挡不住"自定义分支永远不返回"这类改动（轮次⑫ 反向验证实测：
     * 把顺序改坏，那条断言照样通过）。抽成纯函数后可以**行为断言**（见 fpstest）。
     *
     * @return 台词；两边都没有时返回 null
     */
    public static String pickWithCustom(String[] custom, String[] builtin, double r01) {
        String c = pick(custom, r01);
        return c != null ? c : pick(builtin, r01);
    }
}
