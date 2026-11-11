package com.deepseek.pet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动作文件的命名规则 —— <b>纯 Java，零 Android 依赖</b>（照 {@link LocalLines} /
 * {@link SysMetrics} 的手法写，所以能在 {@code build/fpstest} 里做**真断言**）。
 *
 * <h3>它解决什么（待办 0j 的 A + B）</h3>
 * 用户原话：「角色动作包太麻烦了，有没有更简单的自定义导入方案」。
 * 改造前：必须手写一份 18 个场景的 {@code anim/pack.json}，还要让动作名与文件名严格一致，
 * 否则导入器直接报错（{@code CharacterImporter.java:103}）。真正劝退的就是这一步。
 * 现在两条路都不用写 JSON：
 * <ul>
 *   <li><b>A</b> {@code anim/pack.json} 变成**可选**：没有就用包里找到的动作文件现场合成一份
 *       （{@link #synthesizePack(String[])}）；</li>
 *   <li><b>B</b> **文件名前缀就是场景**（{@link #sceneOf(String)}）：{@code 待机-xxx} → 待机、
 *       {@code 点击-xxx} → 点击……不带前缀的一律进待机随机池。</li>
 * </ul>
 *
 * <h3>★ 前缀表的唯一真相是 {@link SceneRegistry} 的中文标签</h3>
 * 本类**不抄任何中文场景名**：前缀是运行时从 {@code SceneRegistry.label(id)} 取的。
 * 抄一份"待机/点击/互动…"的常量表就一定会漂（新增场景只改一处、另一处忘改，
 * 表现是"新场景的前缀永远进 pool"，而且没有任何报错）。
 *
 * <h3>匹配规则（三级，从严到宽）</h3>
 * <ol>
 *   <li><b>标签前缀，最长优先</b>：文件名以某个场景的中文标签开头就用它。
 *       最长优先是为了让 {@code 点击头部-xxx} 命中 {@code tap_head} 而不是 {@code click}，
 *       {@code 待机随机池-xxx} 命中 {@code pool} 而不是 {@code idle}。</li>
 *   <li><b>短前缀 / 短包含，唯一才认</b>：取文件名开头那一段"连续汉字/字母"
 *       （{@code 整点-报时} → {@code 整点}）：
 *       ① 它是**恰好一个**标签的前缀 ⇒ 用它（{@code 整点}→整点报时、{@code 节日}→节日/节气、
 *       {@code 工作}→工作状态、{@code 余额}→余额事件）；
 *       ② 它被**恰好一个**标签包含 ⇒ 用它（{@code 头}→点击头部、{@code 身}→点击身体、
 *       {@code 尾}→点击尾巴）。★ 是"包含"不是"后缀"：那些标签的末字是「部/体/巴」。
 *       ★ "唯一才认"是必须的：{@code 待} 同时被「待机」与「待机随机池」包含，
 *       这种时候**猜**比不猜更糟，直接落到 pool。</li>
 *   <li>都不命中 ⇒ {@link SceneRegistry#POOL}（＝动作库/待机随机池）。</li>
 * </ol>
 *
 * <p>实测样例（见 {@code build/fpstest} 第 [17] 节）：
 * {@code 待机呼吸休闲} → idle、{@code 点击-1} → click、{@code 点击头部-1} → tap_head、
 * {@code 头-1} → tap_head、{@code 整点-报时} → hour、{@code 余额-分文不剩} → balance、
 * {@code 工作-码字} → workStatus、{@code 小猫咪} → pool。
 *
 * <h3>★ 前缀命中的文件**只**进它自己的场景，不额外进 pool</h3>
 * A 的原文是"全部进 pool"，说的情况是**完全不写前缀**（那时每个文件都没命中，自然全进 pool）。
 * B 加进来之后就不再重复入池了 —— 否则「待机时随机换动作」一开，
 * 点击/拖拽动画也会混进待机轮播，而那正是 P0 刚修掉的问题（用户要"待机只播一个"）。
 */
public final class ClipNaming {

    private ClipNaming() {}

    /**
     * 认得为"动作素材"的扩展名，顺序 = 查找优先级。
     * <p>★ 与「角色包格式.md」第四节一致；{@code .png/.jpg/.jpeg} 是**静态动作**
     * （单帧，见 {@link PetAnimPack}），相册里随手选一张图也能当动作用（待办 0j 的 D）。
     */
    public static final String[] IMAGE_EXTS = {"webp", "gif", "png", "jpg", "jpeg"};

    /** 扩展名清单的可读写法（拼错误提示用） */
    public static String extList() {
        StringBuilder sb = new StringBuilder();
        for (String e : IMAGE_EXTS) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append('.').append(e);
        }
        return sb.toString();
    }

    /** 这个文件名是不是动作素材（按扩展名判；大小写不敏感） */
    public static boolean isImageName(String name) {
        String e = extOf(name);
        for (String x : IMAGE_EXTS) if (x.equals(e)) return true;
        return false;
    }

    /** 扩展名（小写，不带点）；没有扩展名返回 "" */
    public static String extOf(String name) {
        if (name == null) return "";
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot <= 0 || dot == n.length() - 1) return "";
        return n.substring(dot + 1).toLowerCase();
    }

    /** 动作名 = 文件名去掉目录与扩展名（与素材层 {@code clipByName} 的 key 一致） */
    public static String baseNameOf(String name) {
        if (name == null) return "";
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.trim();
    }

    /**
     * 相册给的文件名 → 干净的**动作名**（可空：调用方按"换一个名字"处理）。
     * <p>去掉扩展名与文件系统非法字符，限长 40（与 {@code Memes.cleanName} 同一口径，
     * 但**不**给兜底默认名 —— 这里由调用方决定怎么补救）。
     */
    public static String safeClipId(String raw) {
        String n = baseNameOf(raw);
        if (n.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (ch < 0x20) continue;
            if (ch == '\\' || ch == '/' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|') continue;
            sb.append(ch);
        }
        String out = sb.toString().trim();
        // 首尾的点要去掉：Windows 不允许文件名以点结尾，".png" 这种隐藏文件也不该变成动作名
        while (out.startsWith(".")) out = out.substring(1);
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        out = out.trim();
        if (out.length() > 40) out = out.substring(0, 40).trim();
        return out;
    }

    /**
     * 重名就加序号（{@code 名字} → {@code 名字_2} → {@code 名字_3}…）。
     *
     * @param taken 已经占用的动作名（不含扩展名）
     */
    public static String uniqueId(String base, String[] taken) {
        if (base == null || base.isEmpty()) return "";
        if (!isTaken(base, taken)) return base;
        for (int i = 2; i < 1000; i++) {
            String t = base + "_" + i;
            if (!isTaken(t, taken)) return t;
        }
        return base + "_" + (System.currentTimeMillis() % 100000);
    }

    private static boolean isTaken(String id, String[] taken) {
        if (taken == null) return false;
        for (String t : taken) if (id.equals(t)) return true;
        return false;
    }

    /** 相册文件名的扩展名 → 落盘扩展名（认不出就按 MIME 猜，再认不出用 png） */
    public static String extFor(String displayName, String mime) {
        String e = extOf(displayName);
        for (String x : IMAGE_EXTS) if (x.equals(e)) return x;
        if (mime != null) {
            String m = mime.toLowerCase();
            if (m.contains("webp")) return "webp";
            if (m.contains("gif")) return "gif";
            if (m.contains("jpeg") || m.contains("jpg")) return "jpg";
            if (m.contains("png")) return "png";
        }
        return "png";
    }

    // ==================== B：文件名 → 场景 ====================

    /**
     * 文件名 → 场景 id（见类注释的三级匹配）。认不出返回 {@link SceneRegistry#POOL}。
     *
     * @param fileName 可以是裸文件名，也可以带目录（{@code anim/clips/待机-1.webp}）
     */
    public static String sceneOf(String fileName) {
        String base = baseNameOf(fileName);
        if (base.isEmpty()) return SceneRegistry.POOL;
        // ① 标签前缀，最长优先
        String best = null;
        int bestLen = -1;
        for (String id : SceneRegistry.allIds()) {
            String label = SceneRegistry.label(id);
            if (label == null || label.isEmpty()) continue;
            if (base.startsWith(label) && label.length() > bestLen) {
                best = id;
                bestLen = label.length();
            }
        }
        if (best != null) return best;
        // ② 短前缀 / 短后缀，唯一才认
        String token = leadingToken(base);
        if (!token.isEmpty()) {
            String hit = uniqueStart(token);
            if (hit != null) return hit;
            hit = uniqueHas(token);
            if (hit != null) return hit;
        }
        return SceneRegistry.POOL;
    }

    /** 批量版（保序） */
    public static String[] scenesOf(String[] fileNames) {
        if (fileNames == null) return new String[0];
        String[] out = new String[fileNames.length];
        for (int i = 0; i < fileNames.length; i++) out[i] = sceneOf(fileNames[i]);
        return out;
    }

    /** 文件名开头那一段"连续字母/汉字"（遇到 {@code -}、{@code _}、数字、符号就停） */
    static String leadingToken(String base) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (Character.isLetter(ch)) sb.append(ch);
            else break;
        }
        return sb.toString();
    }

    /** token 是**恰好一个**场景标签的前缀 ⇒ 返回那个场景，否则 null（{@code 整点}→整点报时） */
    static String uniqueStart(String token) {
        String hit = null;
        int n = 0;
        for (String id : SceneRegistry.allIds()) {
            String label = SceneRegistry.label(id);
            if (label != null && !label.isEmpty() && label.startsWith(token)) { hit = id; n++; }
        }
        return n == 1 ? hit : null;
    }

    /**
     * token 被**恰好一个**场景标签包含 ⇒ 返回那个场景，否则 null。
     *
     * <p>★ 这里必须是"包含"而不是"以 token 结尾"：{@code 头-xxx} 要归到「点击头部」，
     * 而那个标签的**最后一个字是「部」**（点击头**部**）—— 第一版按"后缀"写，
     * 于是 {@code 头-被戳到.png} 静默落进了 pool（实测被 fpstest 第 [17] 节抓到）。
     * 同理 {@code 身}→点击身**体**、{@code 尾}→点击尾**巴**。
     *
     * <p>"唯一"仍然是硬条件：{@code 待} 被「待机」与「待机随机池」同时包含 ⇒ 返回 null ⇒ 落 pool。
     */
    static String uniqueHas(String token) {
        String hit = null;
        int n = 0;
        for (String id : SceneRegistry.allIds()) {
            String label = SceneRegistry.label(id);
            if (label != null && !label.isEmpty() && label.contains(token)) { hit = id; n++; }
        }
        return n == 1 ? hit : null;
    }

    // ==================== A：合成 2.0 配置 ====================

    /**
     * 用包里找到的动作文件**合成一份 2.0 配置**（{@code anim/pack.json} 的文本）。
     *
     * <p>规则（与待办 0j 的 A 一致）：
     * <ul>
     *   <li>逐个文件按 {@link #sceneOf} 归场景；没前缀的进 {@code pool}；</li>
     *   <li>{@code idle} 取"文件名含「待机」"的那一个（★ 用 {@link SceneRegistry#label}
     *       取中文，不另抄一份），都没有就取**第一个**文件 —— 桌宠总要有个站着的动作；</li>
     *   <li>去重、保序；空数组返回 ""（调用方按"包里没有动作文件"报错）。</li>
     * </ul>
     *
     * <p>返回值是**可以直接写盘的 JSON 文本**（本类不依赖 org.json）。
     */
    public static String synthesizePack(String[] fileNames) {
        List<String> order = new ArrayList<String>();
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        for (String id : SceneRegistry.allIds()) groups.put(id, new ArrayList<String>());
        if (fileNames != null) {
            for (String f : fileNames) {
                String id = baseNameOf(f);
                if (id.isEmpty()) continue;
                if (!order.contains(id)) order.add(id);
                String scene = sceneOf(f);
                List<String> l = groups.get(scene);
                if (l == null) { l = new ArrayList<String>(); groups.put(scene, l); }
                if (!l.contains(id)) l.add(id);
            }
        }
        if (order.isEmpty()) return "";
        // idle：文件名含「待机」（= SceneRegistry 给 idle 的中文标签）的那个，否则第一个
        String idleMark = SceneRegistry.label(SceneRegistry.IDLE);
        String idleId = null;
        if (idleMark != null && !idleMark.isEmpty()) {
            for (String id : order) if (id.contains(idleMark)) { idleId = id; break; }
        }
        if (idleId == null) idleId = order.get(0);
        List<String> idle = groups.get(SceneRegistry.IDLE);
        if (idle == null) { idle = new ArrayList<String>(); groups.put(SceneRegistry.IDLE, idle); }
        if (!idle.contains(idleId)) idle.add(0, idleId);

        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 2,\n  \"scenes\": {\n");
        boolean firstScene = true;
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            if (!firstScene) sb.append(",\n");
            firstScene = false;
            sb.append("    \"").append(jsonEscape(e.getKey())).append("\": [");
            for (int i = 0; i < e.getValue().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(jsonEscape(e.getValue().get(i))).append('"');
            }
            sb.append(']');
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    /** 合成配置里出现的场景 id（保序）——给"导入完成后提示归了几个场景"用 */
    public static String[] scenesIn(String[] fileNames) {
        List<String> out = new ArrayList<String>();
        if (fileNames != null) {
            for (String f : fileNames) {
                String s = sceneOf(f);
                if (!out.contains(s)) out.add(s);
            }
        }
        return out.toArray(new String[0]);
    }

    /** JSON 字符串转义（引号、反斜杠、控制字符；中文原样输出 UTF-8） */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
