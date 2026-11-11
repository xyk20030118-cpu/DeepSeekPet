package com.deepseek.pet.dsh;

/**
 * 模式切换动画 —— 参数与文案的**唯一定义处**（纯类，可在 PC 上真跑）。
 *
 * <h3>为什么要一个专门的类</h3>
 * "切换动画"这种东西最容易散成三份：设置页一套文案、播放器一套 switch、pref 里一套数字。
 * 一处改了另外两处不知道，用户看到的就是"选了没效果"。所以这里只有一处：
 * <b>内置动画的类型表 + 时长 + 自定义视频的落点</b>，播放器只按 {@link #kind} 分派。
 *
 * <h3>内置四种 + 自定义视频</h3>
 * <ul>
 *   <li>{@link #FADE} 淡入淡出（默认，最不打扰）</li>
 *   <li>{@link #ZOOM} 缩放推入（从 0.88 涨到 1.0，像"进入"）</li>
 *   <li>{@link #SLIDE} 横向推入（工作模式从右推入，回陪伴从左推入）</li>
 *   <li>{@link #VIDEO} 自定义视频（用户导入的 mp4/webm，切换时全屏播一遍）</li>
 * </ul>
 *
 * <p>★ 视频时长是**双刃剑**：能说明"这是动画"，但每次切换都要等它 —— 所以播放器
 * 有硬上限（{@link #VIDEO_MAX_MS}）且**点一下就跳过**。上限写在这里而不是播放器里，
 * 因为设置页要拿它写说明文案。
 */
public final class DshTransition {

    private DshTransition() {}

    public static final int FADE = 0;
    public static final int ZOOM = 1;
    public static final int SLIDE = 2;
    public static final int VIDEO = 3;

    /** 全部可选类型（顺序 = 设置页里的顺序）。 */
    public static final int[] KINDS = {FADE, ZOOM, SLIDE, VIDEO};

    /** 内置动画时长（毫秒）。太慢会让"点一下切模式"变得拖沓。 */
    public static final int BUILTIN_MS = 320;

    /** 自定义视频的播放上限（超过就截断，避免一个 30 秒的视频把切换变成折磨）。 */
    public static final int VIDEO_MAX_MS = 2500;

    /** pref 键（与 {@link DshFeature#PREF_FILE} 同一个文件）。 */
    public static final String KEY_KIND = "dsh_transition_kind";
    public static final String KEY_MS = "dsh_transition_ms";

    /** 自定义视频落在哪（导入时复制成这个名字；扩展名由导入器保留在 meta 里）。 */
    public static final String VIDEO_DIR = "transition";
    public static final String VIDEO_NAME = "transition.mp4";

    /** 认不出的值一律回默认档（磁盘被改坏也不会崩）。 */
    public static int normalize(int kind) {
        for (int k : KINDS) {
            if (k == kind) return k;
        }
        return FADE;
    }

    public static boolean isValid(int kind) {
        for (int k : KINDS) {
            if (k == kind) return true;
        }
        return false;
    }

    /** 设置页里的一行字（带说明，用户不用猜）。 */
    public static String label(int kind) {
        switch (normalize(kind)) {
            case ZOOM:  return "缩放推入（0.88 → 1.0）";
            case SLIDE: return "横向推入（工作模式从右、陪伴模式从左）";
            case VIDEO: return "自定义视频（导入一段 mp4 / webm）";
            default:    return "淡入淡出（默认）";
        }
    }

    /** 一行摘要（设置页右侧那块小字）。 */
    public static String describe(int kind) {
        switch (normalize(kind)) {
            case ZOOM:
                return "切换时画面先小而暗、再涨到满屏，像「进入一个空间」。";
            case SLIDE:
                return "工作模式从右侧滑进来、回陪伴模式往左退回去 —— 方向本身说明你在往哪走。";
            case VIDEO:
                return "切换时全屏播一遍你导入的视频（最长 " + (VIDEO_MAX_MS / 1000.0)
                        + " 秒，点一下跳过）。适合放自己的转场/片头。";
            default:
                return "旧页面淡出、新页面淡入，最安静的一种（" + BUILTIN_MS + " 毫秒）。";
        }
    }

    /** 时长夹取（设置页理论上只给 120~800；磁盘上的脏值也兜住）。 */
    public static int clampMs(int ms) {
        if (ms <= 0) return BUILTIN_MS;
        if (ms < 120) return 120;
        if (ms > 800) return 800;
        return ms;
    }

    /** 格式化："320 ms"。 */
    public static String msLabel(int ms) {
        return clampMs(ms) + " ms";
    }

    /**
     * 切换方向 —— 只影响内置动画的方向感（VIDEO / FADE / ZOOM 不看方向）。
     *
     * @param toWorkMode true = 进工作模式（新页从右进），false = 回陪伴（往右退）
     * @return +1（向右）/ -1（向左）
     */
    public static int direction(boolean toWorkMode) {
        return toWorkMode ? 1 : -1;
    }

    /** 播放前要跟用户说的一句（无障碍/日志都用它，别在别处各写一份）。 */
    public static String announce(boolean toWorkMode, int kind) {
        String to = toWorkMode ? "进入工作模式" : "回到陪伴模式";
        return to + "（" + label(kind) + "）";
    }
}
