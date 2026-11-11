package com.deepseek.pet;

/**
 * 桌宠贴边状态机（主工程 FloatingService 用）。
 *
 * <ul>
 *   <li>NORMAL —— 常规桌宠（完整 PetView，可拖动/点击/长按菜单）。</li>
 *   <li>COLLAPSED —— 贴边吸附态（窗口大部藏出屏、仅留细条；
 *       原形象压扁+半透明+暂停动画，吸附态只认向内滑动+长按）。</li>
 *   <li>EXPANDED —— 从边缘滑出展开态（视觉=原始桌宠，行为=NORMAL，
 *       用于区分"刚滑出来"，接 idle 自动回贴）。</li>
 * </ul>
 */
public enum PetDockState {
    NORMAL,
    COLLAPSED,
    EXPANDED;

    /** 吸附边。 */
    public enum Edge { LEFT, RIGHT }
}
