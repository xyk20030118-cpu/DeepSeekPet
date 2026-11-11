package com.deepseek.pet;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/**
 * 设置页的纵向容器 —— 会**自动把行塞进当前分组卡片**。
 *
 * <p><b>为什么需要这个类：</b>原型里一个分组 = 一张卡片，卡片内多行用细线分隔
 * （{@code .card > .row}）。第一版我做成「每行各自一张浮动卡」，一屏下来
 * 就是一堆各带浮雕光晕的胶囊，用户直接反馈"像一堆气泡，跟原型不一样"。
 *
 * <p>要改成正确结构，最直白的做法是挨个改 100+ 处
 * {@code box.addView(...)} 调用点。但那既容易漏（漏掉一行就掉出卡片、
 * 视觉上突然没有容器的托底），又是一次大范围无谓改动。
 *
 * <p>所以改成**在容器层面拦截**：{@link #addView(View)} 被重写，
 * 有当前分组就转交给分组卡片，没有才落到页面本身。调用点一行都不用动，
 * 而且不可能漏 —— 覆盖是结构性的，不是逐处补的。
 *
 * <p>注意只重写 {@code addView(View)} 单参形式：设置页里仅有的两处
 * 双参调用（{@code _skinRow.addView(_bNeu, _half)}）目标是嵌套子行，
 * 本来就不该进分组卡，它们落在别的 LinearLayout 上，不受影响。
 */
final class SettingsPage extends LinearLayout {

    /** 当前分组卡片；null 表示还没遇到 addSection */
    private UiFactory.Group current;
    private final Tokens.Set tokens;

    SettingsPage(Context c, Tokens.Set t) {
        super(c);
        this.tokens = t;
        setOrientation(VERTICAL);
    }

    /** 开一个新分组：小标题（卡外）+ 新卡片，之后的行都进这张卡 */
    void beginGroup(String title) {
        // 关键顺序：先把 current 置空，再放小标题。
        //
        // 因为 addView(View) 被重写了，只要 current 还指着上一张卡，
        // 这个标题就会被塞进**上一张卡里**（实测：新分组的标题跑到了上一组
        // 卡片底部，看起来像卡片的一部分）。
        current = null;
        if (title != null && title.length() > 0) {
            super.addView(UiFactory.sectionLabel(getContext(), tokens, title));
        }
        current = new UiFactory.Group(getContext(), tokens);
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiFactory.dp(getContext(), Tokens.CARD_GAP);
        current.card.setLayoutParams(lp);
        super.addView(current.card);
    }

    /**
     * 行落入当前分组卡；没有分组时落到页面。
     *
     * <p>只拦单参形式 —— 设置页里仅有的两处双参调用
     * （{@code _skinRow.addView(_bNeu, _half)}）目标是嵌套子行，
     * 本来就不该进分组卡，它们落在别的 LinearLayout 上，不受影响。
     */
    @Override
    public void addView(View child) {
        if (current != null) {
            current.addRow(child);
        } else {
            super.addView(child);
        }
    }

    /** 页面级元素（标题、返回按钮）必须绕过分组，直接进页面 */
    void addToPage(View v) {
        current = null;          // 页头之后的第一个 addSection 会重新开卡
        super.addView(v);
    }

    void addToPage(View v, ViewGroup.LayoutParams lp) {
        super.addView(v, lp);
    }

    void endGroup() {
        current = null;
    }
}
