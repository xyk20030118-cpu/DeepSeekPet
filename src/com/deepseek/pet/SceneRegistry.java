package com.deepseek.pet;

/**
 * 场景清单 —— <b>代码侧的唯一真相</b>。
 *
 * <h3>边界（这套改造最核心的一条约定）</h3>
 * <b>有哪些场景</b>由本类固定（id + 中文标签 + 触发时机 + 默认播放方式）；
 * <b>每个场景挂哪些动作</b>由角色包（{@code anim/pack.json}）决定。
 * 这样新增场景只要在这里加一行，而用户又不会面对一个空白系统。
 *
 * <h3>为什么要做这件事</h3>
 * 改造前，场景是 {@code PetView.playAnimPackState()} 里的一串硬编码 {@code switch}
 * （idle / turn / drag / click / move），而且：
 * <ul>
 *   <li>{@code click} 与 {@code drag} 两个分支**从来没人设置过**（死分支）；</li>
 *   <li>{@code turn} / {@code move} 的唯一调用点在漫游循环里，而漫游被取消了 ⇒ 一起休眠。</li>
 * </ul>
 * 场景变成数据之后，这些"有素材、没出口"的问题在结构上就不会再出现了。
 */
public final class SceneRegistry {

    private SceneRegistry() {}

    // ===== 基础场景（8 个，2026-10-07 与用户逐项确认）=====
    /** 待机：无操作时的默认循环。用户要求"取消所有待机动作、只留默认那一个"⇒ 默认只放 1 个。 */
    public static final String IDLE = "idle";
    /** 点击：单击/戳桌宠。 */
    public static final String CLICK = "click";
    /** 互动：连戳、部位之外的互动反应。 */
    public static final String INTERACT = "interact";
    /** 拖拽：按住拖动过程中（循环）。 */
    public static final String DRAG = "drag";
    /** 漫游：自动走动过程中（循环）。 */
    public static final String ROAM = "roam";
    /** 转向：漫游换向/走到边上回头（瞬时）。 */
    public static final String TURN = "turn";
    /** 贴边：吸附成竖条时（瞬时）。 */
    public static final String DOCK = "dock";
    /** 待机随机池：**默认关闭**；用户想"待机会随机换动作"时把动作加进来。 */
    public static final String POOL = "pool";

    // ===== 额外场景（7 个，用户 2026-10-07 勾选）=====
    public static final String HOUR = "hour";              // 整点报时
    public static final String FESTIVAL = "festival";      // 节日/节气
    public static final String TAP_HEAD = "tap_head";      // 点击头部
    public static final String TAP_BODY = "tap_body";      // 点击身体
    public static final String TAP_TAIL = "tap_tail";      // 点击尾巴
    public static final String DOUBLE_TAP = "double_tap";  // 双击（开聊天框）
    public static final String LONG_PRESS = "long_press";  // 长按（弹菜单）

    // ===== 事件场景（3 个，由事件触发而不是由状态触发）=====
    public static final String EV_BALANCE = "balance";         // 余额档位变化
    public static final String EV_WHISPER = "whisper";         // 主动搭话（碎碎念）
    public static final String EV_WORK = "workStatus";         // 前台=工作／办公

    /** {id, 中文标签, 触发时机说明, 播放方式(loop/once), 默认抽取方式(first/random/weighted)} */
    private static final String[][] TABLE = {
            {IDLE,       "待机",      "没有任何操作时的默认循环",           "loop", "first"},
            {CLICK,      "点击",      "单击 / 戳一下桌宠",                 "once", "random"},
            {INTERACT,   "互动",      "连续戳、其它互动反应",               "once", "random"},
            {DRAG,       "拖拽",      "按住拖动过程中",                   "loop", "random"},
            {ROAM,       "漫游",      "自动走动过程中（恢复漫游后生效）",      "loop", "random"},
            {TURN,       "转向",      "漫游换向 / 走到屏幕边回头",           "once", "random"},
            {DOCK,       "贴边",      "吸附成屏幕边缘竖条时",               "once", "random"},
            {POOL,       "待机随机池",  "默认关闭；开启后待机会随机换动作",     "loop", "weighted"},
            {HOUR,       "整点报时",   "整点报时那一下",                   "once", "random"},
            {FESTIVAL,   "节日/节气",  "命中节日或时段时（时段加成）",         "once", "random"},
            {TAP_HEAD,   "点击头部",   "戳到头部",                       "once", "random"},
            {TAP_BODY,   "点击身体",   "戳到身体",                       "once", "random"},
            {TAP_TAIL,   "点击尾巴",   "戳到尾巴",                       "once", "random"},
            {DOUBLE_TAP, "双击",      "双击桌宠（打开输入条）",             "once", "random"},
            {LONG_PRESS, "长按",      "长按桌宠（弹出功能菜单）",            "once", "random"},
    };

    /** 事件场景：由外部事件触发，不走"状态"那条路 */
    private static final String[][] EVENTS = {
            {EV_BALANCE, "余额事件", "余额档位变化时（<1 / 1-5 / 5-10 / 10-20 / 20-50 / ≥50 元）", "once", "random"},
            {EV_WHISPER, "碎碎念",   "主动搭话时（纯本地档与 API 档都走）",              "once", "random"},
            {EV_WORK,    "工作状态", "前台应用属于「工作／办公」时",                  "once", "random"},
    };

    /** 展示顺序（设置页「动作系统」按这个顺序列） */
    public static String[] ids() {
        String[] out = new String[TABLE.length];
        for (int i = 0; i < TABLE.length; i++) out[i] = TABLE[i][0];
        return out;
    }

    /** 事件场景 id 清单 */
    public static String[] eventIds() {
        String[] out = new String[EVENTS.length];
        for (int i = 0; i < EVENTS.length; i++) out[i] = EVENTS[i][0];
        return out;
    }

    /** 基础 + 额外 + 事件，全部场景 id */
    public static String[] allIds() {
        String[] out = new String[TABLE.length + EVENTS.length];
        for (int i = 0; i < TABLE.length; i++) out[i] = TABLE[i][0];
        for (int i = 0; i < EVENTS.length; i++) out[TABLE.length + i] = EVENTS[i][0];
        return out;
    }

    public static boolean isEvent(String id) {
        for (String[] e : EVENTS) if (e[0].equals(id)) return true;
        return false;
    }

    public static boolean isKnown(String id) {
        return find(id) != null;
    }

    private static String[] find(String id) {
        if (id == null) return null;
        for (String[] r : TABLE) if (r[0].equals(id)) return r;
        for (String[] r : EVENTS) if (r[0].equals(id)) return r;
        return null;
    }

    private static String col(String id, int idx, String fallback) {
        String[] r = find(id);
        return r != null ? r[idx] : fallback;
    }

    /** 中文标签；未知 id 原样返回（外部角色包里的自定义场景不该被吞掉） */
    public static String label(String id) { return col(id, 1, id); }

    /** 触发时机说明（设置页用） */
    public static String hint(String id) { return col(id, 2, ""); }

    /** 播放方式：loop / once */
    public static String mode(String id) { return col(id, 3, "once"); }

    /** 默认抽取方式：first / random / weighted */
    public static String defaultPick(String id) { return col(id, 4, "random"); }

    /**
     * 部位 → 场景（"点哪演哪"）。
     *
     * <p>★ 轮次㊱ 接上：{@link #TAP_HEAD} / {@link #TAP_BODY} / {@link #TAP_TAIL} 三个场景
     * 在清单里躺了很久却**没有任何出口** —— 点击处理只做了"识别部位 + 报一句台词"，
     * 从没把场景交给素材层（用户报「点击头部 / 点击尾部 / 点击身体这些动作选项无法触发」）。
     *
     * <p>映射放在**纯函数**里（而不是散在服务里）：点击处理、设置页清单、断言用的是同一份。
     *
     * @param part 部位 id（{@code FloatingService.detectPart} 的返回值：head / body / tail）
     * @return 对应场景；不认识的部位返回 null（调用方据此什么都不做）
     */
    public static String partScene(String part) {
        if ("head".equals(part)) return TAP_HEAD;
        if ("tail".equals(part)) return TAP_TAIL;
        if ("body".equals(part)) return TAP_BODY;
        return null;
    }

    /**
     * 老状态名 → 新场景名的兼容映射。
     * <p>改造前 {@code setAnimState()} 只传过 idle / turn / move，
     * 其中 {@code move} 现在叫 {@link #ROAM}；{@code drag} / {@code click} 保持同名。
     */
    public static String fromLegacyState(String st) {
        if (st == null) return IDLE;
        if ("move".equals(st)) return ROAM;
        if ("click".equals(st)) return CLICK;
        if ("drag".equals(st)) return DRAG;
        if ("turn".equals(st)) return TURN;
        if (isKnown(st)) return st;
        return IDLE;
    }
}
