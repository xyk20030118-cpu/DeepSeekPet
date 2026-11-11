package com.deepseek.pet;

/**
 * 返回导航的**纯逻辑**（不依赖 Android，可离线断言）。
 *
 * <h3>为什么要抽出来</h3>
 * 「返回上一级」这条规则有**三个**入口，必须给出完全一致的结果：
 * <ol>
 *   <li>{@code onBackPressed()}（老版本 / 未启用预测式返回）</li>
 *   <li>API 33+ 的 {@code OnBackInvokedCallback}（跟随系统手势）</li>
 *   <li>左边缘左滑手势</li>
 * </ol>
 * 三处各写一份判断的话，改了层级规则只改一处，就会出现
 * <b>"某种返回方式能退、另一种直接退出 App"</b> —— 这是最难复现的 bug 类型，
 * 因为用户只会说"有时候返回会闪退到桌面"。
 *
 * <p>所以把"当前在哪 → 返回到哪"做成纯函数，三个入口都调它。
 * 这也让边界条件（已在首页、已在第一个板块）可以被穷举断言。
 */
public final class BackNav {

    private BackNav() {}

    /** 返回动作的结果 */
    public static final class Action {
        /** 返回后应处的板块下标 */
        public int tab;
        /** 返回后应处的二级页；-1 表示板块首页 */
        public int section;
        /** true = 已在 App 最外层，应退出（交给系统） */
        public boolean exit;

        Action(int tab, int section, boolean exit) {
            this.tab = tab;
            this.section = section;
            this.exit = exit;
        }

        @Override public String toString() {
            return exit ? "EXIT" : "tab=" + tab + ",section=" + section;
        }
    }

    /**
     * 计算"按返回后应该到哪"。
     *
     * <h3>层级规则</h3>
     * <pre>
     *   二级页（section ≥ 0）   → 回**所属板块**首页
     *   板块首页（section &lt; 0） → 非第一个板块则回第一个板块
     *                            已在第一个板块 → 退出
     * </pre>
     *
     * @param section     当前二级页下标；-1 = 板块首页
     * @param tab         当前板块下标
     * @param sectionTab  该二级页所属的板块（由 tabOfSection 提供）
     * @param tabCount    板块总数
     */
    public static Action back(int section, int tab, int sectionTab, int tabCount) {
        if (section >= 0) {
            // 二级页 → 回所属板块首页。
            // 用 sectionTab 而不是当前 tab：用户可能从别的板块跳进来
            // （代码里确实有这种路径），回"所属板块"才符合直觉。
            return new Action(sectionTab, -1, false);
        }
        if (tab > 0) {
            // 板块首页但不是第一个 → 退到第一个板块
            return new Action(0, -1, false);
        }
        // 最外层 → 退出
        return new Action(tab, section, true);
    }

    /** 当前是否还能往回退（决定要不要接管边缘滑动手势） */
    public static boolean canGoBack(int section, int tab) {
        return section >= 0 || tab > 0;
    }
}
