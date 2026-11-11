package com.deepseek.pet.dsh;

/**
 * <b>工作模式的排版尺寸</b> —— 从桌面端 DeepSeek Harness 的 CSS 里量出来的真值（纯类）。
 *
 * <h3>只放"尺寸"，不放"颜色"</h3>
 * ★★ 第 9 版改口（用户原话）：**「ui 主题同步 app，功能页面完全参考 deepseekharness」**。
 * 意思是两件事分开：
 * <ul>
 *   <li><b>配色 / 明暗 / 玻璃态 / 背景</b> → 一律用 App 自己的主题令牌（{@code Tokens.Set}），
 *       这样工作模式和陪伴模式看起来是**同一个 App**，用户自定配色、皮肤、视频背景都跟着生效；</li>
 *   <li><b>结构 / 字号 / 行高 / 圆角 / 间距</b> → 照桌面端 CSS 的真值，这部分才叫"复刻"。</li>
 * </ul>
 * 所以本类**没有 Palette**：颜色不在这里定义，谁要颜色就去 {@code Tokens.of(ctx)} 取。
 *
 * <h3>这些数从哪来（不是凭感觉调的）</h3>
 * {@code @deepseek-ai/dsh-client-ui-primitives/lib/*.module.css}：
 * 工具块 {@code padding:9px 14px}、字号 {@code 12px/18px}、正文行高 {@code 22px}、
 * 块间距 {@code margin:16px 0}、Markdown 段落行高 {@code 24px}、行内代码圆角 {@code 6px}。
 */
public final class DshDesk {

    private DshDesk() {}

    // ==================== 排版 ====================

    /** 正文 14sp / 行高 22（模块 CSS 的 {@code font-size:14px;line-height:22px}）。 */
    public static final float TEXT_BODY = 14f;
    /** 次要 12sp / 行高 18（设置行、工具块标题都是这一档）。 */
    public static final float TEXT_SMALL = 12f;
    /** 说明 11sp（桌面端 caption 一档）。 */
    public static final float TEXT_CAPTION = 11f;
    /** 段落行高 24（MarkdownText 模块的 {@code line-height:24px}）。 */
    public static final float LINE_PARAGRAPH = 24f;
    /** 工具块正文行高 22（ReadBlock / TerminalBlock 的 {@code --dsl-*-line-height:22px}）。 */
    public static final float LINE_BLOCK = 22f;

    // ==================== 圆角 ====================

    /** 卡片圆角 12（read/terminal 的 radius 档）。 */
    public static final int RADIUS_CARD = 12;
    /** 控件圆角 18（设置页 stepper / 胶囊）。 */
    public static final int RADIUS_CONTROL = 18;
    /** 气泡/方块的 20（themeCube）。 */
    public static final int RADIUS_CUBE = 20;
    /** 行内代码 6。 */
    public static final int RADIUS_INLINE = 6;

    // ==================== 间距 ====================

    /** 工具块外边距（{@code margin:16px 0}）。 */
    public static final int GAP_BLOCK = 16;
    /** 工具块内部行间距（{@code gap:12px}）。 */
    public static final int GAP_INNER = 12;
    /** 工具块 banner 内边距：上 9 / 左右 14（{@code padding:9px 14px}）。 */
    public static final int PAD_BANNER_V = 9;
    public static final int PAD_BANNER_H = 14;
    /** 正文区左右内边距 16。 */
    public static final int PAD_CONTENT = 16;

    /** 把 sp 值换成 px（纯类里不碰 Context，只做算术）。 */
    public static float sp(float spValue, float scaledDensity) {
        return spValue * scaledDensity;
    }
}
