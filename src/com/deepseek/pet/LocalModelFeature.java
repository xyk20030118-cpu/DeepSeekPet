package com.deepseek.pet;

/**
 * ★★★ 轮次75：「本地模型部署」这个功能的**总开关**（纯类，零 Android 依赖）。
 *
 * <p>用户原话：<b>「先去掉本地模型部署这个接口 保留项目内容后续再加上」</b>。
 * ⇒ 本轮把**用户可见的入口全部下线**，但 **代码 / 资源 / 已导入的模型文件 / prefs 一个都不删**。
 *
 * <h3>★ 怎么"下线"的（三处，都在这里被引用）</h3>
 * <ol>
 *   <li>{@link ChatProvider} 的列表里**不放**「本地模型」这一项 ⇒ 服务商选择器看不到它；</li>
 *   <li>{@link Prefs#getChatProvider} 把"历史选择过 local"的用户**在读取时**映射回
 *       {@code deepseek}（★ **不改写磁盘上的值** —— 恢复时用户原来的选择原样回来）；</li>
 *   <li>{@link MainActivity} 里「🤖 本地模型」那一组不再绘制（面板方法本身保留）。</li>
 * </ol>
 * 其余一切（`LocalChat` / `LocalLlm` / JNI / `GpuPlan` / `LocalModelMenu` / 自检 / 调优）**原样保留**，
 * 只是从界面上够不着。
 *
 * <h3>★★ 怎么恢复</h3>
 * 把 {@link #ENABLED} 改成 {@code true}，重编 dev/rel 两个包即可 ——
 * 不需要改任何别的地方（有断言钉着"开关只在这一处"）。
 *
 * <h3>★ 为什么用一个常量而不是删代码</h3>
 * 删掉容易，加回来要重新接线、重新跑二十多套断言；而用户说的是"后续再加上"。
 * 一个常量 + 三条引用，把"下线"变成**可逆**的，而且反向项能钉住"下线期间真的看不到"。
 */
public final class LocalModelFeature {

    /**
     * ★★★ 是否启用「本地模型部署 / 本地对话」功能。
     *
     * <p>{@code false} = 界面入口全部下线（当前）；{@code true} = 恢复。
     * ★ 只改这一处，别的地方一律读它。
     */
    public static final boolean ENABLED = false;

    /** 下线期间给文档 / 日志用的一句实话（不含任何 UI 承诺）。 */
    public static final String OFFLINE_NOTE =
            "本地模型功能当前**从界面下线**（代码、资源、已导入的模型文件与设置都保留；"
            + "把 LocalModelFeature.ENABLED 改成 true 并重编即可恢复）。";

    /** 历史选择过「本地模型」的用户，读取时落到哪个在线服务商。 */
    public static final String FALLBACK_PROVIDER_ID = "deepseek";

    private LocalModelFeature() {}

    /** 功能是否可见（UI 与 provider 列表都问它）。 */
    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * 把一个历史 provider id 映射成"当前可用的"。
     *
     * <p>★ 只做**读取时**映射，绝不写盘：恢复功能时用户原来的选择原样回来。
     */
    public static String resolveProvider(String storedId) {
        if (ENABLED) return storedId;
        if (ChatProvider.LOCAL_ID.equals(storedId)) return FALLBACK_PROVIDER_ID;
        return storedId;
    }
}
