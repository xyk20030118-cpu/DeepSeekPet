package com.deepseek.pet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 角色包 2.0 的**文本**生成 —— <b>纯 Java，零 Android 依赖</b>（照 {@link ClipNaming} 的手法）。
 *
 * <h3>它解决什么（用户追加要求：导出角色时按"当前绑定"打包）</h3>
 * 导出的包要**自洽**：配置写的是"当前有效绑定"（角色包 + 用户覆盖层），而不是角色包的原始文件。
 * 于是导出必须有"把绑定写成 pack.json 文本"这一步 —— 抽到这里之后：
 * <ul>
 *   <li>{@code build/fpstest} 能用**真函数**断言它的输出（权重写法、空场景、转义）；</li>
 *   <li>设备侧与离线往返断言（{@code build/rt_test}）用的是**同一份**实现，
 *       不会出现"设备上导出的配置"与"断言里以为的配置"两套。</li>
 * </ul>
 *
 * <p>★ 刻意**不引用** {@link SceneBindings}：那个类 import 了 android.content，
 * 一旦被本类引用，本类就再也不能在桌面 JVM 上编译（fpstest 只编纯类）。
 * 所以这里用自己的 {@link Item}，由 {@link CharacterExporter} 负责转换。
 */
public final class PackFormat {

    private PackFormat() {}

    /**
     * 形象在角色包里的固定目录（与运行期的 {@code filesDir/appearance/<角色 id>/} **同名同结构**）。
     *
     * <p>★ 为什么用同一个名字：导出/导入只是"搬目录"，路径写两份必然漂移
     * （加了新来源忘了改一处，表现成"包里有素材、导进去没生效"）。
     */
    public static final String APPEARANCE_DIR = "appearance";

    /** 包内**辅助**目录：里面也是图片，但**不是动作** */
    public static final String[] AUX_DIRS = {"memes", APPEARANCE_DIR};

    /**
     * 这个目录名是不是"辅助目录"（不是动作）。
     *
     * <p>★ 为什么要集中判：导入器在 {@code anim/clips/} 找不到动作时会**全树扫**，
     * 扫的时候必须跳过辅助目录 —— 漏一个就会把**形象图/表情图当成动作**
     * （用户会在动作库里看到自己的脸）。原来这里硬编码只跳 {@code memes}，
     * 加了 {@code appearance/} 之后漏跳，正好会踩这个坑。
     */
    public static boolean isAuxDir(String name) {
        if (name == null) return false;
        String s = name.trim();
        for (String d : AUX_DIRS) if (d.equalsIgnoreCase(s)) return true;
        return false;
    }

    /** 一条绑定：动作名 + 权重（权重 &le; 1 时按纯名字写，文件更可读） */
    public static final class Item {
        public final String id;
        public final int weight;
        public Item(String id, int weight) {
            this.id = id;
            this.weight = weight < 1 ? 1 : weight;
        }
    }

    /**
     * 把"场景 → 绑定"写成 2.0 {@code anim/pack.json} 文本。
     *
     * <p>规则：空场景**不写出来**（免得导出一个满是 {@code "interact": []} 的噪音包）；
     * 权重 &gt; 1 写 {@code {"id":…,"weight":N}}，否则写纯名字（与角色包/覆盖层同一约定）。
     * 两个表都空时仍返回一份合法配置（导入器会把它并到内置基底上）。
     */
    public static String packJson(Map<String, List<Item>> scenes, Map<String, List<Item>> events) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 2,\n  \"scenes\": {");
        appendGroups(sb, scenes);
        sb.append("\n  },\n  \"events\": {");
        appendGroups(sb, events);
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static void appendGroups(StringBuilder sb, Map<String, List<Item>> groups) {
        boolean firstGroup = true;
        if (groups == null) return;
        for (Map.Entry<String, List<Item>> e : groups.entrySet()) {
            List<Item> items = new ArrayList<Item>();
            if (e.getValue() != null) {
                for (Item it : e.getValue()) {
                    if (it != null && it.id != null && !it.id.isEmpty()) items.add(it);
                }
            }
            if (items.isEmpty()) continue;              // 空场景不写
            if (!firstGroup) sb.append(",");
            firstGroup = false;
            sb.append("\n    \"").append(ClipNaming.jsonEscape(e.getKey())).append("\": [");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                Item it = items.get(i);
                if (it.weight <= 1) {
                    sb.append('"').append(ClipNaming.jsonEscape(it.id)).append('"');
                } else {
                    sb.append("{\"id\": \"").append(ClipNaming.jsonEscape(it.id))
                      .append("\", \"weight\": ").append(it.weight).append('}');
                }
            }
            sb.append(']');
        }
    }

    /**
     * 只保留**被包含动作**的台词：{@code {"动作名": ["台词1", …]}}。
     * <p>找不到任何台词返回 {@code null}（调用方就别写这个文件了，空文件反而让导入器白读一次）。
     */
    public static String linesJson(List<String> ids, Map<String, List<String>> allLines) {
        if (ids == null || ids.isEmpty() || allLines == null || allLines.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            List<String> ls = allLines.get(id);
            if (ls == null || ls.isEmpty()) continue;
            List<String> keep = new ArrayList<String>();
            for (String s : ls) if (s != null && !s.isEmpty()) keep.add(s);
            if (keep.isEmpty()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("\n  \"").append(ClipNaming.jsonEscape(id)).append("\": [");
            for (int i = 0; i < keep.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(ClipNaming.jsonEscape(keep.get(i))).append('"');
            }
            sb.append(']');
        }
        if (first) return null;
        sb.append("\n}\n");
        return sb.toString();
    }

    /** 并集：按给定顺序去重（导出时收集"全部被引用的动作"，顺序要稳定） */
    public static List<String> union(List<String> into, List<String> add) {
        List<String> out = (into != null) ? into : new ArrayList<String>();
        if (add != null) {
            for (String s : add) {
                if (s != null && !s.isEmpty() && !out.contains(s)) out.add(s);
            }
        }
        return out;
    }

    /** 便捷：从"场景 → 动作名"构造 Item 表（权重一律 1） */
    public static LinkedHashMap<String, List<Item>> plainGroups(Map<String, List<String>> src) {
        LinkedHashMap<String, List<Item>> out = new LinkedHashMap<String, List<Item>>();
        if (src == null) return out;
        for (Map.Entry<String, List<String>> e : src.entrySet()) {
            List<Item> l = new ArrayList<Item>();
            if (e.getValue() != null) for (String s : e.getValue()) l.add(new Item(s, 1));
            out.put(e.getKey(), l);
        }
        return out;
    }
}
