package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>插件</b> —— 对齐官方架构：DeepSeek Harness 是「一切皆插件」（Cordis 架构，
 * 见 https://www.deepseek.com/harness/ ）。桌面端一进来左栏就有「插件」。
 *
 * <h3>手机上"插件"是什么</h3>
 * 桌宠 App 本来就有很多能力（余额、天气、联网检索、表情、语音、角色…）。融合的正确做法
 * **不是**再写一套 agent 内置一堆工具，而是：<b>把每块能力包成一个插件，插件决定"给模型哪些工具"</b>。
 * 于是：
 * <ul>
 *   <li>关掉「网页」插件 ⇒ 模型**根本看不到** {@code web_fetch}（不是靠提示词求它别用）；</li>
 *   <li>关掉「桌宠」插件 ⇒ 模型看不到 {@code pet_status}（桌宠自己的状态）；</li>
 *   <li>新增能力 = 新增一个插件条目 + 它的工具，内核一行都不用改。</li>
 * </ul>
 *
 * <p>★ 这类是**纯类**：只描述"有什么插件、默认开哪些、怎么编码成 pref 串"，
 * 不认识 Android；真正的工具实现在 {@link DshTools}，开关的读写由 {@link DshBridge} 负责。
 */
public final class DshPlugin {

    public final String id;
    public final String name;
    public final String desc;
    /** 这个插件向模型提供的工具名（必须与 {@link DshTools} 里注册的名字一致）。 */
    public final String[] tools;
    /** 是否默认启用（目前全部默认开 —— 用户关掉是他自己的选择）。 */
    public final boolean defaultOn;

    DshPlugin(String id, String name, String desc, String[] tools, boolean defaultOn) {
        this.id = id;
        this.name = name;
        this.desc = desc;
        this.tools = tools == null ? new String[0] : tools;
        this.defaultOn = defaultOn;
    }

    @Override public String toString() {
        return id + "(" + name + ")";
    }

    // ==================== 内置插件表 ====================

    public static final String P_FILES = "core.files";
    public static final String P_WEB = "core.web";
    public static final String P_TODO = "core.todo";
    public static final String P_SKILL = "core.skill";
    public static final String P_CLOCK = "core.clock";
    public static final String P_PET = "pet.status";
    public static final String P_SEARCH = "core.search";
    /** 提醒（与上游 tool-schedule 同名同义：create / list / update / delete）。 */
    public static final String P_SCHEDULE = "core.schedule";

    /** 全部内置插件（顺序 = 插件面板里的顺序）。 */
    public static List<DshPlugin> all() {
        List<DshPlugin> out = new ArrayList<DshPlugin>();
        out.add(new DshPlugin(P_FILES, "文件",
                "读写 App 私有目录里的文件（沙箱由权限档守着）",
                new String[]{"list", "read", "write", "stat"}, true));
        out.add(new DshPlugin(P_SEARCH, "搜索",
                "在工作区里按正则搜文本，返回「文件:行: 内容」",
                new String[]{"search"}, true));
        out.add(new DshPlugin(P_WEB, "网页",
                "抓一个 URL 的正文（不需要额外的 Key）",
                new String[]{"web_fetch"}, true));
        out.add(new DshPlugin(P_TODO, "待办",
                "多步任务清单，落盘 todo.json，跨会话可见",
                new String[]{"todo"}, true));
        out.add(new DshPlugin(P_SKILL, "技能",
                "按需加载 SKILL.md 的完整正文",
                new String[]{"skill"}, true));
        out.add(new DshPlugin(P_CLOCK, "时间",
                "当前本地时间（模型经常需要「今天几号」）",
                new String[]{"now"}, true));
        out.add(new DshPlugin(P_PET, "桌宠",
                "让模型看到桌宠自己的状态：名字 / 角色 / 服务商 / 模型 / 联网与余额开关",
                new String[]{"pet_status"}, true));
        out.add(new DshPlugin(P_SCHEDULE, "提醒",
                "让模型自己建定时提醒（一次性 / 每 N 秒 / 每天 / 每周 / cron），落在「自动化」页里",
                new String[]{"schedule_create", "schedule_list", "schedule_update", "schedule_delete"}, true));
        return out;
    }

    public static DshPlugin byId(String id) {
        for (DshPlugin p : all()) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    // ==================== 启用集合 <-> pref 串 ====================

    /** 默认启用集合（没有存过 pref 时用）。 */
    public static Set<String> defaults() {
        Set<String> s = new LinkedHashSet<String>();
        for (DshPlugin p : all()) {
            if (p.defaultOn) s.add(p.id);
        }
        return s;
    }

    /**
     * 解析 pref 串（{@code "core.files,core.web"}）。
     * <p>★ 空串/认不出的 id 一律**丢弃**；但如果整串是 null（没有存过），返回默认集合。
     * 这样"用户把插件全关掉"（空串）与"从没设置过"能区分开 —— 前者的正确结果是"一个都不开"。
     */
    public static Set<String> decode(String raw) {
        if (raw == null) return defaults();
        Set<String> out = new LinkedHashSet<String>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (id.length() == 0) continue;
            if (byId(id) != null) out.add(id);
        }
        return out;
    }

    public static String encode(Set<String> enabled) {
        if (enabled == null) return "";
        StringBuilder sb = new StringBuilder();
        for (DshPlugin p : all()) {
            if (!enabled.contains(p.id)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(p.id);
        }
        return sb.toString();
    }

    /** 工具名 → 需要开启哪个插件（未知工具返回 null）。 */
    public static String pluginOfTool(String toolName) {
        for (DshPlugin p : all()) {
            for (String t : p.tools) {
                if (t.equals(toolName)) return p.id;
            }
        }
        return null;
    }

    /** 启用集合下**可见的工具名**（模型请求里的 tools 就按这个过滤）。 */
    public static List<String> visibleTools(Set<String> enabled) {
        List<String> out = new ArrayList<String>();
        for (DshPlugin p : all()) {
            if (enabled == null || !enabled.contains(p.id)) continue;
            for (String t : p.tools) out.add(t);
        }
        return out;
    }

    /** 系统提示里那段插件清单（只列**启用**的，附一句能干什么）。 */
    public static String promptBlock(Set<String> enabled) {
        if (enabled == null) enabled = defaults();
        StringBuilder sb = new StringBuilder();
        for (DshPlugin p : all()) {
            if (!enabled.contains(p.id)) continue;
            sb.append("- ").append(p.name).append("：").append(p.desc).append('\n');
        }
        return sb.toString();
    }
}
