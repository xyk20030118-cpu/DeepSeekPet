package com.deepseek.pet;

/**
 * 返回导航的离线断言（纯 Java，不依赖 Android）。
 *
 * <p>为什么必须有：用户报的 bug 原文是
 * 「如果用了返回手势 app会直接退回到桌面」——
 * 根因是 {@code MainActivity} 没有覆写返回逻辑，走系统默认 finish()。
 *
 * <p>修好之后，最容易再犯的错是**三个返回入口行为不一致**
 * （onBackPressed / API33 回调 / 左滑手势），表现为
 * "某种返回方式能退、另一种直接退出"。这些断言把层级规则钉死，
 * 三个入口都调同一份纯逻辑，所以规则对了就全对。
 */
public class BackNavTest {

    private static int pass = 0, fail = 0;

    private static void ok(boolean cond, String what) {
        if (cond) pass++;
        else { fail++; System.out.println("  FAIL: " + what); }
    }

    private static void eq(Object a, Object b, String what) {
        boolean c = (a == null) ? (b == null) : a.equals(b);
        ok(c, what + " (got " + a + ", want " + b + ")");
    }

    /** 本工程的板块 → 二级页映射（与 MainActivity.TAB_SECTIONS 一致） */
    private static final int[][] TAB_SECTIONS = {
            {0, 2},        // 形象：外观与显示、表情与素材
            {1, 6, 3},     // 陪伴：对话与人格、语音、记忆与 token
            {4, 5},        // 系统：运行控制、关于
    };

    private static int tabOfSection(int section) {
        for (int t = 0; t < TAB_SECTIONS.length; t++) {
            for (int s : TAB_SECTIONS[t]) if (s == section) return t;
        }
        return 0;
    }

    private static BackNav.Action back(int section, int tab) {
        return BackNav.back(section, tab,
                section >= 0 ? tabOfSection(section) : tab,
                TAB_SECTIONS.length);
    }

    public static void main(String[] args) {
        System.out.println("=== 返回导航断言 ===");

        System.out.println();
        System.out.println("[1] ★ 用户报的 bug：二级页按返回不能退出 App");
        {
            // 外观与显示（section=0，属形象板块 tab=0）
            BackNav.Action a = back(0, 0);
            ok(!a.exit, "二级页返回**不能**退出 App（这是用户报的 bug）");
            eq(a.section, -1, "二级页 → 回板块首页");
            eq(a.tab, 0, "回到所属板块");
            // 语音（section=6，属陪伴板块 tab=1）
            BackNav.Action b = back(6, 1);
            ok(!b.exit, "语音页返回不能退出");
            eq(b.section, -1, "语音页 → 板块首页");
            eq(b.tab, 1, "回到陪伴板块");
            System.out.println("      二级页返回均有内部落点 ✓");
        }

        System.out.println();
        System.out.println("[2] ★ 二级页要回**所属**板块，不是当前板块");
        {
            // 用户可能从别的板块跳进某个二级页（代码里确有这种路径），
            // 此时返回应回"它所属的板块"，否则会跳到不相干的地方。
            // 语音页(section=6)属陪伴(tab=1)，但当前 tab 假装是 0（形象）
            BackNav.Action a = back(6, 0);
            eq(a.tab, 1, "按所属板块回（陪伴），而不是当前 tab");
            eq(a.section, -1, "回的是板块首页");
        }

        System.out.println();
        System.out.println("[3] 板块首页：非第一个 → 回第一个（不退出）");
        {
            for (int t = 1; t < TAB_SECTIONS.length; t++) {
                BackNav.Action a = back(-1, t);
                ok(!a.exit, "板块 " + t + " 首页返回不退出");
                eq(a.tab, 0, "板块 " + t + " 首页 → 第一个板块");
                eq(a.section, -1, "回的是板块首页");
            }
            System.out.println("      板块 1、2 首页均可回退 ✓");
        }

        System.out.println();
        System.out.println("[4] ★ 最外层才退出（否则用户会\"怎么按都退不出去\"）");
        {
            BackNav.Action a = back(-1, 0);
            ok(a.exit, "第一个板块的首页 → 退出");
            // 这是唯一的退出条件
            int exits = 0;
            for (int t = 0; t < TAB_SECTIONS.length; t++) {
                for (int s = -1; s <= 6; s++) {
                    if (back(s, t).exit) exits++;
                }
            }
            eq(exits, 1, "全部 21 种状态下**只有 1 种**会退出");
        }

        System.out.println();
        System.out.println("[5] ★ canGoBack 必须与 back().exit 完全一致");
        {
            // 不一致的后果：边缘滑动手势在"其实退不出去"时被接管，
            // 于是用户从左边缘划一下 —— 什么都没发生（事件被吃掉）。
            for (int t = 0; t < TAB_SECTIONS.length; t++) {
                for (int s = -1; s <= 6; s++) {
                    boolean can = BackNav.canGoBack(s, t);
                    boolean willStay = !back(s, t).exit;
                    ok(can == willStay,
                       "canGoBack 与 back().exit 一致 (s=" + s + ",t=" + t + ")");
                }
            }
            System.out.println("      21 种状态全部一致 ✓");
        }

        System.out.println();
        System.out.println("[6] 每个二级页都必须有归属板块（否则返回会跳到 0）");
        {
            for (int s = 0; s <= 6; s++) {
                int owner = tabOfSection(s);
                boolean found = false;
                for (int[] secs : TAB_SECTIONS) {
                    for (int x : secs) if (x == s) found = true;
                }
                ok(found, "二级页 " + s + " 在 TAB_SECTIONS 里有归属（owner=" + owner + "）");
            }
        }

        System.out.println();
        System.out.println("[7] 返回链必须能一路退到最外层（不能有环/卡住）");
        {
            // 从任意状态模拟连续按返回，最终必须到 exit，且步数有界
            for (int t = 0; t < TAB_SECTIONS.length; t++) {
                for (int s = -1; s <= 6; s++) {
                    int curT = t, curS = s, steps = 0;
                    boolean exited = false;
                    while (steps < 10) {
                        BackNav.Action a = back(curS, curT);
                        if (a.exit) { exited = true; break; }
                        // 防止"退回到原地"造成死循环
                        if (a.tab == curT && a.section == curS) {
                            ok(false, "返回后状态没变（会卡住）s=" + s + " t=" + t);
                            break;
                        }
                        curT = a.tab; curS = a.section; steps++;
                    }
                    ok(exited, "从 (s=" + s + ",t=" + t + ") 连续返回能到退出");
                }
            }
            System.out.println("      21 条返回链均能终止 ✓");
        }

        System.out.println();
        System.out.println("全部通过：" + pass + " 项");
        if (fail > 0) {
            System.out.println("失败 " + fail + " 项");
            System.exit(1);
        }
    }
}
