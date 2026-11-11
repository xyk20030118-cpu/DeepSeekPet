package com.deepseek.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 设计令牌（Design Tokens）—— 从已批准的原型 1:1 移植。
 *
 * <p>原型是 HTML/CSS，用 4 套 CSS 变量组合：{@code data-skin} 新拟态/玻璃
 * × {@code data-theme} 浅色/深色。这个类把那 4 套值翻译成 Java 常量表，
 * 布局代码只读令牌、不写字面色值。
 *
 * <p><b>为什么不直接用 superellipse/Compose：</b>本工程是纯 framework、
 * 零 androidx（不能用 Compose，也不能用 Material 主题），视图全部由 Java
 * 代码构建。所以令牌表 + 自绘 Drawable 是唯一可行的路径。
 *
 * <p><b>对比度：</b>所有文字色都按 WCAG AA（正文 4.5:1 / 大字 3:1）挑过，
 * 并由 {@code tools/contrast.ps1} 在原型阶段验证通过。原型刻意**没有**照抄
 * 设计 skill 的色值 —— 那几个 skill 的 textTertiary 在浅色下只有 2.03:1、
 * warning 只有 1.79:1，直接抄会把可读性做坏。
 */
final class Tokens {

    private Tokens() {}

    // ==================== 引擎与主题 ====================

    /** 新拟态：surface == bg，形状只靠双向浮雕阴影表达 */
    static final int SKIN_NEUMORPH = 0;
    /** 玻璃态：半透明表面叠在 Mesh 光斑上，靠描边与内外阴影分层 */
    static final int SKIN_GLASS = 1;

    static final int THEME_LIGHT = 0;
    static final int THEME_DARK = 1;

    private static final String PREF = "deepseek_pet_ui";
    private static final String K_SKIN = "ui_skin";
    private static final String K_THEME = "ui_theme";
    private static final String K_BG_IMAGE = "ui_bg_image_enabled";

    static int skin(Context c) {
        return sp(c).getInt(K_SKIN, SKIN_NEUMORPH);
    }

    static void setSkin(Context c, int v) {
        sp(c).edit().putInt(K_SKIN, v).apply();
    }

    /** -1 = 跟随系统 */
    static int themeMode(Context c) {
        return sp(c).getInt(K_THEME, -1);
    }

    static void setThemeMode(Context c, int v) {
        sp(c).edit().putInt(K_THEME, v).apply();
    }

    /** 解析出实际生效的明暗（把「跟随系统」算出来） */
    static int theme(Context c) {
        int m = themeMode(c);
        if (m == THEME_LIGHT || m == THEME_DARK) return m;
        int night = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES
                ? THEME_DARK : THEME_LIGHT;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ==================== 令牌集合 ====================

    /** 一套完整令牌。字段名对齐原型里的 CSS 变量。 */
    static final class Set {
        int bg, surface, surfaceSunken;
        int shadowLight, shadowDark;
        int text1, text2, text3;
        int accent, accentSoft;
        /**
         * 小字号（&lt;18sp）用的强调色。
         *
         * <p><b>为什么需要单独一个：</b>实机截图实测发现，玻璃·浅色下把
         * {@link #accent}（#1D5AC5）当 13sp 分组标题用时，叠在 Mesh 最亮处
         * （实测 #B0D3E7）只有 3.61:1 —— 达不到正文 AA。令牌表里算出来是
         * 5.52:1，是因为解析模型低估了光斑**叠加**后的峰值（相邻光斑会互相
         * 抬亮），而真机是叠加后的结果。
         *
         * <p>加大字号的按钮/链接可以继续用 accent；分组标题、数值标签这类
         * 小字一律用这个更深的值。
         */
        int accentInk;
        int success, warning, danger, dangerFill, onDanger;
        int softAccent, softSuccess, softWarning, softDanger;
        int hairline, scrim, edge, modal;
        int glassInner, glassOuter;
        int glassBorder;
        /** 新拟态下为 null；玻璃态下是 4~5 个径向光斑 */
        Spot[] mesh;
    }

    /** 一个径向渐变光斑：归一化位置 + 半径 + 颜色 */
    static final class Spot {
        final float cx, cy, r;
        final int color;
        Spot(float cx, float cy, float r, int color) {
            this.cx = cx; this.cy = cy; this.r = r; this.color = color;
        }
    }

    // 原型 :root 的几何常量（dp，不是 px —— 原型的 411px 视口正好等于 411dp）
    static final int R_CARD = 28;
    static final int R_DIALOG = 24;
    static final int R_BUTTON = 26;
    /** 胶囊（--r-pill）：返回键、分段控件、开关轨道 */
    static final int R_PILL = 999;
    static final int R_INPUT = 16;
    static final int R_CHIP = 14;
    static final int PAD_CARD = 16;
    static final int CARD_GAP = 12;
    static final int INNER_GAP = 12;
    /** 触摸目标下限。原型修掉了原先 50.9dp 开关和 27.4dp 触点的可达性问题 */
    static final int H_CONTROL = 48;
    static final int H_CHIP = 32;
    static final int SHADOW_ELEV = 6;

    static Set of(Context c) {
        Set t = of(skin(c), theme(c));
        // ★★ 轮次㊾：这里是**全工程唯一的令牌入口** —— 用户的「自定义玻璃配色 / UI 透明度」
        //   就挂在这一行上，所以 100+ 个页面/弹窗/浮层的调用点**一处都不用改**。
        //   ★ 未自定义时 `apply` 直接返回 false、令牌逐字段不变（"零变化"承诺，有断言钉着）。
        try { ThemeOverride.apply(t, c); } catch (Throwable ignore) {}
        return t;
    }

    /**
     * 给 Activity 选一个与当前明暗一致的**系统主题**。
     *
     * <h3>为什么需要它（用户要求"三个窗口跟随 App UI 变化"）</h3>
     * 本工程是纯 framework、零 AndroidX，页面的颜色全部由 Java 代码
     * 从 {@link Set} 令牌里取 —— 这部分换主题时自动生效。
     *
     * <p>但**系统自己画的东西**（AlertDialog 的确认框、输入法的选择菜单、
     * 长按弹出的文本菜单）用的是 <b>Activity 的主题</b>，不是我们的令牌。
     * 清单里挂的是 {@code Theme.Light.*} —— 于是深色模式下：
     * 页面是深色，一按「清空记忆」却弹出一个**白色**确认框，很突兀。
     *
     * <p>所以必须在 {@code onCreate} 里（**setContentView 之前**）
     * 用 {@code setTheme} 换成与明暗一致的 DeviceDefault 变体。
     * 本方法是**唯一一份**实现：轮次㊵ 把 {@code MainActivity} 里那份私有的同名方法
     * 合并了过来（两份逐字等价，改一处漏两处）；四个窗口 + 其余 Activity 全走这里。
     * ★ 它只管"系统控件别是白底"这一层；弹窗的**皮肤**（圆角 + modal 表面 + 文字配色）
     * 由 {@code UiFactory.dialog()} 在创建时套令牌，两层互补。
     *
     * @param a 目标 Activity（必须是 Activity，不是 Context）
     */
    static void applyPlatformTheme(android.app.Activity a) {
        try {
            if (theme(a) == THEME_DARK) {
                a.setTheme(android.R.style.Theme_DeviceDefault_NoActionBar);
            } else {
                a.setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar);
            }
        } catch (Throwable ignore) {
            // 取不到就保持清单里的主题，不影响功能
        }
    }

    static Set of(int skin, int theme) {
        Set t = new Set();
        if (skin == SKIN_GLASS && theme == THEME_DARK) glassDark(t);
        else if (skin == SKIN_GLASS) glassLight(t);
        else if (theme == THEME_DARK) neumorphDark(t);
        else neumorphLight(t);
        return t;
    }

    private static int c(String hex) {
        return Color.parseColor(hex);
    }

    private static int argb(int a, int r, int g, int b) {
        return Color.argb(a, r, g, b);
    }

    // ---- 新拟态 · 浅色 ----
    private static void neumorphLight(Set t) {
        t.bg = c("#F2F4F8");
        t.surface = c("#F2F4F8");          // 关键：与背景同色，形状只来自阴影
        t.surfaceSunken = c("#EDEFF4");
        t.shadowLight = c("#FFFFFF");
        t.shadowDark = c("#D1D9E6");
        t.text1 = c("#1A1B1E");
        t.text2 = c("#4F5052");
        t.text3 = c("#6A6B6D");
        t.accent = c("#1F5FD0");
        t.accentInk = c("#17489C");   // 小字用，见 Set.accentInk 注释
        t.accentSoft = c("#E8F4FF");
        t.success = c("#1B6E45");
        t.warning = c("#7A5300");
        t.danger = c("#B03030");
        t.dangerFill = c("#C43F3F");
        t.onDanger = c("#FFFFFF");
        t.softAccent = c("#E3ECFC");
        t.softSuccess = c("#DFF3E6");
        t.softWarning = c("#F7EBD6");
        t.softDanger = c("#F9E4E4");
        t.hairline = argb(20, 26, 27, 30);
        t.edge = c("#8A93A6");
        t.scrim = argb(107, 26, 27, 30);
        t.modal = t.bg;
        t.glassInner = 0;
        t.glassOuter = 0;
        t.glassBorder = 0;
        t.mesh = null;
    }

    // ---- 新拟态 · 深色 ----
    private static void neumorphDark(Set t) {
        t.bg = c("#1A1B1E");
        t.surface = c("#1A1B1E");
        t.surfaceSunken = c("#16171A");
        // 两色阴影的明度差要**和浅色同量级**，否则控件看不见。
        //
        // 新拟态的控件与背景同色（surface == bg），形状**完全**由这对阴影
        // 表达，所以"阴影与背景的明度差"就是这个主题下唯一的可见度来源。
        // 浅色是 Δ27.8（暗侧）/ Δ11.1（亮侧）；而深色原来是
        // #0D0E11(Δ13.0) / #26282C(Δ12.8)，暗侧只有浅色的一半不到，
        // 再经 BlurMaskFilter 一稀释，实机上按钮/卡片几乎看不出起伏 ——
        // 用户反馈的「深色模式按键有点不可见」正是它。
        //
        // 现在提到 #000000(Δ27.0) / #3A3E45(Δ29.5)，两侧都达到甚至超过
        // 浅色的水平。用**纯黑**做暗侧在深色新拟态里是标准做法
        // （浅色的暗侧也几乎是"背景压暗到很低"），比继续微调灰色更干脆。
        t.shadowLight = c("#3A3E45");
        t.shadowDark = c("#000000");
        t.text1 = c("#FFFFFF");
        t.text2 = c("#C7C7C7");
        t.text3 = c("#949494");
        t.accent = c("#82B1FF");
        t.accentInk = c("#82B1FF");   // 深色下 accent 本身就高对比
        t.accentSoft = c("#0A2A4A");
        t.success = c("#6EE7A0");
        t.warning = c("#FFCC80");
        t.danger = c("#FF9E9E");
        t.dangerFill = c("#C0392B");
        t.onDanger = c("#FFFFFF");
        t.softAccent = c("#16273F");
        t.softSuccess = c("#142A1E");
        t.softWarning = c("#2E2412");
        t.softDanger = c("#33191A");
        t.hairline = argb(18, 255, 255, 255);
        t.edge = c("#8C8C8C");
        t.scrim = argb(153, 0, 0, 0);
        t.modal = t.bg;
        t.glassInner = 0;
        t.glassOuter = 0;
        t.glassBorder = 0;
        t.mesh = null;
    }

    // ---- 玻璃 · 浅色 ----
    // 注意 surface 的 alpha 必须是 .70：低于此值 text-2 与 text-3 会塌成
    // 几乎相同的灰（#4D4D4F vs #4C4D4F），层级感消失。这是原型阶段
    // 用「类型层级检查」抓到的坑。
    private static void glassLight(Set t) {
        t.bg = c("#F3EFEA");
        t.surface = argb(179, 255, 255, 255);   // rgba(255,255,255,.70)
        t.surfaceSunken = argb(15, 7, 10, 20);  // rgba(7,10,20,.06)
        t.shadowLight = argb(191, 255, 255, 255);
        t.shadowDark = argb(51, 115, 125, 153);
        t.text1 = c("#1A1B1E");
        t.text2 = c("#4F5052");
        // 小字（分组标题 12sp 粗体、提示文字）要过正文 AA。
        // 原值 #5F6062 在 Mesh 峰值上是 3.99:1；改 #55565A 后仍有 4.32:1 ——
        // 实测分组标题的背景正好落在 Mesh 较亮的那一块（#AEC9EC），
        // 所以还得再深一档到 4.82:1。
        t.text3 = c("#4E4F53");
        t.accent = c("#1D5AC5");
        t.accentInk = c("#17489C");   // 实测 Mesh 峰值上 4.01 -> 5.45:1
        t.accentSoft = argb(36, 29, 90, 197);
        t.success = c("#1B6D44");
        t.warning = c("#7A5300");
        t.danger = c("#B03030");
        t.dangerFill = c("#B3261E");
        t.onDanger = c("#FFFFFF");
        t.softAccent = c("#E4EBFA");
        t.softSuccess = c("#E1F0E6");
        t.softWarning = c("#F6EDDC");
        t.softDanger = c("#F7E6E6");
        t.hairline = argb(31, 26, 27, 30);
        t.scrim = argb(97, 26, 27, 30);
        t.modal = argb(240, 255, 255, 255);
        t.glassInner = argb(128, 255, 255, 255);
        t.glassOuter = argb(51, 115, 125, 153);
        t.glassBorder = argb(36, 26, 27, 30);
        t.mesh = new Spot[]{
            new Spot(0.18f, 0.22f, 0.62f, argb(115, 90, 160, 240)),
            new Spot(0.86f, 0.15f, 0.60f, argb(102, 156, 138, 230)),
            new Spot(0.28f, 0.90f, 0.62f, argb(107, 79, 184, 201)),
            new Spot(0.90f, 0.84f, 0.60f, argb(97, 126, 134, 224)),
            new Spot(0.52f, 0.46f, 0.82f, argb(79, 127, 192, 232)),
        };
    }

    // ---- 玻璃 · 深色 ----
    // 深色下 tint 必须是**深色**（rgba(10,14,24,.55)）。若按直觉给白色 tint，
    // 白卡叠在明亮光斑上会变成 #90ACE1，白字只有约 2.4:1 —— 这是 skill 里的
    // 真实缺陷，原型刻意避开了。
    private static void glassDark(Set t) {
        t.bg = c("#0F1320");
        t.surface = argb(140, 10, 14, 24);      // rgba(10,14,24,.55)
        t.surfaceSunken = argb(71, 0, 0, 0);
        t.shadowLight = argb(31, 255, 255, 255);
        t.shadowDark = argb(71, 0, 0, 0);
        t.text1 = c("#FFFFFF");
        t.text2 = c("#C7C7C7");
        // 13sp 提示文字要过正文 AA。原值 #A4A4A4 在深色 Mesh 亮处
        // 只有约 3.6:1，提亮一档后 4.9:1。
        t.text3 = c("#C0C0C0");
        t.accent = c("#82B1FF");
        t.accentInk = c("#AFCCFF");   // 小字用，深色 Mesh 上再提亮一档
        t.accentSoft = argb(56, 130, 177, 255);
        t.success = c("#6EE7A0");
        t.warning = c("#FFCC80");
        t.danger = c("#FF9E9E");
        t.dangerFill = c("#B3261E");
        t.onDanger = c("#FFFFFF");
        t.softAccent = argb(56, 130, 177, 255);
        t.softSuccess = argb(56, 110, 231, 160);
        t.softWarning = argb(56, 255, 204, 128);
        t.softDanger = argb(56, 255, 158, 158);
        t.hairline = argb(36, 255, 255, 255);
        t.scrim = argb(158, 5, 7, 12);
        t.modal = argb(219, 13, 18, 30);
        t.glassInner = argb(115, 255, 255, 255);
        t.glassOuter = argb(71, 0, 0, 0);
        t.glassBorder = argb(51, 255, 255, 255);
        t.mesh = new Spot[]{
            new Spot(0.20f, 0.25f, 0.55f, argb(140, 74, 110, 170)),
            new Spot(0.84f, 0.16f, 0.52f, argb(115, 120, 96, 150)),
            new Spot(0.30f, 0.90f, 0.55f, argb(102, 60, 120, 120)),
            new Spot(0.90f, 0.82f, 0.55f, argb(107, 90, 84, 140)),
        };
    }
}
