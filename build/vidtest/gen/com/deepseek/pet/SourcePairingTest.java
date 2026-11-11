package com.deepseek.pet;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 「下标 == 常量值」这类**隐式约定**的回归测试。
 *
 * <h3>为什么需要它</h3>
 * 曾经写出过这样的代码：
 * <pre>
 *   String[] names = {"铺满", "完整显示"};        // 下标 0 = 铺满
 *   for (int i = 0; i &lt; names.length; i++)
 *       Prefs.setVideoBgFit(ctx, i);              // 写入 0
 * </pre>
 * 而 {@code FIT_CONTAIN == 0}、{@code FIT_COVER == 1} ——
 * 于是用户点「铺满（推荐）」实际得到的是「完整显示（留黑边）」。
 * 这类 bug **编译不报错、数学测试也测不到**（数学收的是算好的常量），
 * 只有"文案与常量必须成对"这条约束能抓住它。
 *
 * <p>这个类直接**读源码文本**做检查。听起来笨，但正是它的价值：
 * 它检查的是"两个东西是不是相邻声明"这种**结构性**约束，
 * 而这类约束没法在运行时断言（运行起来时下标已经被用掉了）。
 */
public class SourcePairingTest {

    private static int failed = 0, passed = 0;

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static String read(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return null;
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), Charset.forName("UTF-8")));
        String line;
        while ((line = r.readLine()) != null) b.append(line).append('\n');
        r.close();
        return b.toString();
    }

    /**
     * 去掉注释后的源码。
     *
     * <p>★ 为什么必须去注释：本轮把 {@code FIT_STRETCH} 删掉时，各处**注释**
     * 里都留了"原先有个拉伸模式、它必然变形、已删除"的说明 —— 那是**有价值**
     * 的历史记录（防止后人再加回来）。但检查"代码里还有没有拉伸"时若连注释
     * 一起搜，就会把这些说明文字当成违规，把好注释逼成不敢写。
     *
     * <p>所以检查只针对**代码**：行注释、块注释、字符串字面量都抹掉。
     * 抹掉字面量是为了避免"UI 文案里出现了某关键词"被误判，
     * 不过 UI 文案本身也要检查（见 [D] 用另一种方式）。
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ').append(' ');
                        i += 2;
                        continue;
                    }
                    if (src.charAt(i) == '"') {
                        out.append(c);
                        i++;
                        break;
                    }
                    out.append(' ');
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    public static void main(String[] args) throws Exception {
        String srcRoot = args.length > 0 ? args[0] : ".." + File.separator + ".."
                + File.separator + "src" + File.separator + "com"
                + File.separator + "deepseek" + File.separator + "pet";
        String main = read(srcRoot + File.separator + "MainActivity.java");
        String prefs = read(srcRoot + File.separator + "Prefs.java");

        if (main == null || prefs == null) {
            System.out.println("找不到源文件（srcRoot=" + srcRoot + "）");
            System.exit(2);
        }

        // code = 去掉注释与字符串字面量后的源码，用于"代码里还有没有 X"的检查
        String mainCode = stripComments(main);
        String prefsCode = stripComments(prefs);

        System.out.println("[A] FIT_* 常量值必须是 CONTAIN=0 / COVER=1，且不得再有 STRETCH");
        ok(prefsCode.contains("FIT_CONTAIN = 0"), "FIT_CONTAIN 定义为 0");
        ok(prefsCode.contains("FIT_COVER = 1"), "FIT_COVER 定义为 1");
        ok(!prefsCode.contains("FIT_STRETCH"), "代码里已无 FIT_STRETCH（注释可保留说明）");
        ok(!mainCode.contains("FIT_STRETCH"), "MainActivity 代码不再引用 FIT_STRETCH");

        System.out.println("[B] 「画面铺法」选择器已撤销（框锁比例后两种铺法等价，留着会误导）");
        // 用户要求「完全按照裁剪框里面的截取」。裁剪框锁成屏幕比例后，
        // COVER 与 CONTAIN 的输出**恒等**（断言见 VideoFitTest [17]）——
        // 也就是说选择器选了看不出区别。所以撤掉，只保留一种行为。
        ok(!mainCode.contains("_fitModes"), "设置页已删除 _fitModes 选择器");
        ok(!mainCode.contains("fitModes"), "弹窗已删除 fitModes 单选组");
        ok(!mainCode.contains("RadioGroup"), "弹窗不再有 RadioGroup");
        ok(mainCode.contains("Prefs.setVideoBgFit(this, Prefs.FIT_COVER)"),
                "渲染路径写死 FIT_COVER（唯一行为）");
        // 反面样本：不得再用裸下标当模式
        ok(!mainCode.contains("Prefs.setVideoBgFit(MainActivity.this, _i)"),
                "没有用 _i 当下标直接写入 fit");
        ok(!mainCode.contains("Prefs.setVideoBgFit(MainActivity.this, i)"),
                "没有用 i 当下标直接写入 fit");

        System.out.println("[D] 用户可见文案里不得再有「拉伸」这个选项");
        // ★ 检查"字符串字面量形式的选项名"，而不是裸关键词。
        //
        // 原因：源码的**注释**里会（也应该）保留"原先有个「拉伸铺满」、
        // 它必然变形、已删除"这类说明 —— 那是防止后人再加回来的有价值记录。
        // 注释里用的是中文角括号「」，而真正的按钮文案用的是 ASCII 双引号，
        // 所以查带引号的形式就能精确区分"代码里的选项"与"注释里的说明"。
        ok(!main.contains("\"拉伸铺满\""), "没有以「拉伸铺满」为文案的按钮/单选项");
        ok(!main.contains("拉伸铺满\""), "没有以「拉伸铺满」结尾的文案");
        ok(!main.contains("\"拉伸"), "没有以「拉伸」开头的选项文案");
        ok(!main.contains("会变形）"), "没有「（会变形）」这样的选项标注");

        System.out.println("[E] 视频背景那几行用的 emoji 必须能在 VectorIcon 里查到");
        // ★ 为什么要测这个：UiFactory 会把行首 emoji 抽出来画成**单色矢量图标**；
        // 查不到映射时只画一个空的凹槽底片 —— 屏幕上就是一个"白色小方块"。
        // 我给「重新裁剪画面」用过 ✂️（U+2702），它不在映射表里，
        // 截图里那行左边就多了个白方块。这类问题编译不报错、功能也正常，
        // 只有"对着截图看"才会发现，所以钉成断言。
        String vec = read(srcRoot + File.separator + "VectorIcon.java");
        if (vec == null) {
            ok(false, "读不到 VectorIcon.java");
        } else {
            // 抽查视频背景实际用到的 emoji：都必须已映射
            // （🎬 U+1F3AC = CLAPPER，已映射；🎥 U+1F3A5 与 ✂ U+2702 都**未映射**，
            //   曾导致行首出现空白方块）
            String[][] checks = {
                {"\\uD83C\\uDFAC", "视频背景小标题/裁剪标题 用的 🎬"},
                {"\\uD83D\\uDDBC", "重新裁剪画面 用的 🖼"},
            };
            for (String[] c : checks) {
                ok(vec.contains(c[0]), c[1] + "（" + c[0] + "）已在 VectorIcon 映射表中");
            }
            // 明确禁止再用那两个未映射的
            ok(!main.contains("\"\\uD83C\\uDFA5 "), "没有用未映射的 🎥 作行首图标");
            ok(!main.contains("\"\\u2702\\uFE0F "), "没有用未映射的 ✂️ 作行首图标");
        }

        System.out.println();
        System.out.println(failed == 0
                ? ("全部通过：" + passed + " 项")
                : ("失败 " + failed + " 项 / 共 " + (passed + failed)));
        if (failed != 0) System.exit(1);
    }
}