package com.deepseek.pet.dsh;

/**
 * 移植开关 —— 一个常量决定"这个功能存不存在于用户面前"。
 *
 * <p>★ 与轮次75 的 {@code LocalModelFeature} 同一套纪律：**默认关闭**。
 * 理由不是"怕"，而是这个功能会改变对话链路的形状（多出一层 agent loop 与工具调用），
 * 在它被真机验过之前，不能悄悄接管桌宠的每一句话。打开方式见设置页
 * 「🧠 DeepSeek Harness（工具型对话）」那一组，或把这里的 {@link #ENABLED} 改成 {@code true} 重编。
 *
 * <p>★ 关闭时的行为必须逐字等价于没有这个功能：不绘制入口、不注册工具、
 * 不发带 {@code tools} 的请求、不建会话目录。
 */
public final class DshFeature {

    private DshFeature() {}

    /** 功能总开关（编译期；关掉后所有入口与请求都不会出现）。 */
    public static final boolean ENABLED = true;

    /** 运行时开关（用户设置页里的开关，存在独立 pref 文件里）。 */
    public static final String PREF_FILE = "deepseek_pet_dsh";
    public static final String KEY_ON = "dsh_on";
    public static final String KEY_MODE = "dsh_mode";
    public static final String KEY_SESSION = "dsh_session";
    /** 当前工作区 id（"" = 默认工作区；见 {@link DshWorkspace}）。 */
    public static final String KEY_WORKSPACE = "dsh_workspace";
    public static final String KEY_MAX_STEPS = "dsh_max_steps";
    public static final String KEY_MODEL = "dsh_model";
    /** 当前模式：true = 工作模式（DeepSeek Harness 页面），false = 陪伴模式（原来的桌宠界面）。 */
    public static final String KEY_WORK_MODE = "dsh_work_mode";
    /** 启用的插件集（逗号分隔的 id 串；null = 从没设过 ⇒ 用默认全开）。 */
    public static final String KEY_PLUGINS = "dsh_plugins";
    /** Harness 宿主地址（"dsh web" 打印的那一行；默认 adb reverse 的 127.0.0.1:43129）。 */
    public static final String KEY_HARNESS_URL = "dsh_harness_url";
    /** 宿主 token（从上面那行 URL 的 token= 参数里取）。 */
    public static final String KEY_HARNESS_TOKEN = "dsh_harness_token";
    /**
     * **手机自己起的那个宿主**的地址（"dsh web" 打印的那一行）。
     * <p>★ 必须和 {@link #KEY_HARNESS_URL} 分开：那个是"用户配的远端地址"，
     * 这个是"本机宿主这次起的"。混在一起会导致第一次自启成功后就被当成
     * "用户配过远端"，下次进来不再自启 —— 实测踩到过一次。
     */
    public static final String KEY_HARNESS_LOCAL = "dsh_harness_local";

    /**
     * 「本机 Linux 已经准备过一次」的标记。
     *
     * <p>★ 为什么需要：一键流程里有"弹系统安装器"这一步，如果每次切出去再回来都重跑一遍，
     * 用户就会被反复要求"安装"（实测报障：切出去就又要装一次）。
     * 成功起来一次之后把这里置 1，以后只走"连上已在跑的宿主"，不再问任何东西。
     */
    public static final String KEY_DSH_PREPARED = "dsh_prepared";

    /**
     * 「工作模式」总开关（默认开）。
     *
     * <p>用户要求：关了之后就**打不开**工作模式 —— 顶部的切换入口点了也不动，
     * 只会提示去打开开关。注意它和 {@link #ENABLED}（整个 DSH 功能）不是一回事。
     */
    public static final String KEY_WORK_ENABLED = "dsh_work_enabled";

    /**
     * 是否**以 root 身份**运行工作模式的宿主（默认关）。
     *
     * <p>开了之后宿主进程以 uid=0 跑，访客里能看到手机全部目录（/ data /system /storage）。
     * 没 root 或拒绝授权时会自动回退到普通模式。
     */
    public static final String KEY_ROOT_HOST = "dsh_root_host";

    public static final int DEFAULT_MAX_STEPS = 6;

    /**
     * 构建标识 —— 排查「装的到底是哪个包」用。
     * <p>★ 第五版的教训：用户发来的截图里还是旧文案（「先打开总开关…」），
     * 而那句话在新包里**已经不存在** —— 但从截图上看不出来装的是新是旧。
     * 所以把标识直接写在界面上：设置行与工作模式顶栏都带它。
     * 每次改动靠近这个功能就加一号。
     */
    public static final String BUILD_TAG = "b8";

    /** 人话名（设置页与日志共用一处，别在两处各写一份）。 */
    public static final String TITLE = "🧠 DeepSeek Harness（工具型对话）";
    public static final String SUMMARY =
            "桌宠不再只会聊天：它能读你给的资料、抓网页、记待办，一步步把事做完（需要服务商支持工具调用）";
}
