package com.deepseek.pet;

/**
 * {@link VideoFitMath} 的离线断言（纯 javac + java，不需要 Android）。
 *
 * <p>本工程的手写构建只编译 {@code src/**}{@code .java}，而
 * {@code build/vidtest} 是另一套独立的小测试：把
 * {@code VideoFitMath.java} 和 {@code Prefs.java} 之外的常量**复制**过来
 * 编译（见同目录下的 run.sh / 本仓库的构建说明），因此这里不能
 * import 任何 android 类。
 *
 * <p>为什么值得为几行数学单写一套测试：用户报的
 * 「视频导入之后无法正常显示，被裁切了」正是这段算错。而它在设备上
 * 只能靠"看截图猜"，非常不可靠 —— 有过一次把"卡片遮挡下的背景"
 * 当成视频画面、得出错误结论的经历。
 */
public class VideoFitTest {

    // 与 Prefs 里的常量保持一致（这里是独立编译的，取不到那些常量）。
    // ★ 没有 FIT_STRETCH —— 它已被删除，见 [3] 的说明。
    private static final int FIT_CONTAIN = 0;
    private static final int FIT_COVER = 1;

    private static int failed = 0, passed = 0;

    private static void ok(boolean cond, String what) {
        if (cond) {
            passed++;
        } else {
            failed++;
            System.out.println("  FAIL: " + what);
        }
    }

    /**
     * 裁区在视图上实际画在哪儿。
     *
     * <p>返回 {@code {sx, sy, left, top, width, height}}。
     *
     * <p>★ 这里踩过一次坑：不能把"裁区左上角"当成视频原点。
     * 裁区在视频坐标里是从 {@code cl*vw} 开始的，第一版 helper 漏掉了这个
     * 偏移，于是"裁区中心没落在屏幕中心"的假失败 —— 其实是断言算错了。
     */
    private static float[] drawn(int vw, int vh, int viewW, int viewH,
                                 int fit, float[] crop) {
        float[] t = VideoFitMath.compute(vw, vh, viewW, viewH, fit, crop);
        float cl = crop == null ? 0f : crop[0];
        float ct = crop == null ? 0f : crop[1];
        float cr = crop == null ? 1f : crop[2];
        float cb = crop == null ? 1f : crop[3];
        float cw = (cr - cl) * vw, ch = (cb - ct) * vh;
        float left = cl * vw * t[0] + t[2];
        float top = ct * vh * t[1] + t[3];
        return new float[]{t[0], t[1], left, top, cw * t[0], ch * t[1]};
    }

    private static void near(float a, float b, float tol, String what) {
        ok(Math.abs(a - b) <= tol, what + " (got " + a + ", want " + b + ")");
    }

    public static void main(String[] args) {
        // 用户的真实场景：1920x1080 横屏视频当 1080x2400 竖屏背景
        final int VW = 1920, VH = 1080, SW = 1080, SH = 2400;

        System.out.println("[1] 横屏视频 + 完整显示：必须完整可见、绝不变形");
        {
            float[] r = drawn(VW, VH, SW, SH, FIT_CONTAIN, null);
            near(r[0], r[1], 1e-3f, "等比（sx==sy），不得变形");
            ok(r[4] <= SW + 0.5f, "宽度不超出屏幕（整个画面看得见）");
            ok(r[5] <= SH + 0.5f, "高度不超出屏幕（整个画面看得见）");
            // 1920x1080 装进 1080x2400：受宽度限制，scale=0.5625，高=607.5
            near(r[5], 607.5f, 1f, "高度应为 1080 * (1080/1920) = 607.5");
            near(r[2], 0f, 0.5f, "水平居中（左边界为 0）");
            near(r[3], (SH - 607.5f) / 2f, 1f, "垂直居中（上下等宽黑边）");
            System.out.println("      绘制 " + r[4] + " x " + r[5]
                    + " @ (" + r[2] + ", " + r[3] + ")");
        }

        System.out.println("[2] 横屏视频 + 铺满：必须铺满且不变形（裁掉左右）");
        {
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, null);
            near(r[0], r[1], 1e-3f, "等比（sx==sy），不得变形");
            ok(r[4] >= SW - 0.5f, "宽度铺满屏幕");
            ok(r[5] >= SH - 0.5f, "高度铺满屏幕");
            // 竖屏下 cover 必然横向溢出（这正是"被裁掉"的部分）
            ok(r[4] > SW + 1f, "宽度溢出（左右被裁，符合铺满语义）");
            // 溢出部分必须**左右均分**（居中裁），不能偏向一边
            near(r[2], (SW - r[4]) / 2f, 0.5f, "横向溢出左右均分");
            System.out.println("      绘制 " + r[4] + " x " + r[5]
                    + " @ (" + r[2] + ", " + r[3] + ")");
        }

        // 用户第五轮要求：「裁剪视频不要做任何拉伸变形，只保留裁剪部分」
        // 于是 FIT_STRETCH 被删除。这里把"绝不变形"升级成**全局不变量**：
        // 不是"某两个模式恰好等比"，而是"任何输入、任何模式都必须等比"。
        System.out.println("[3] ★ 铁律：任何模式、任何输入都绝不变形（sx == sy）");
        {
            int[][] videos = {{1920, 1080}, {1080, 1920}, {1080, 2400},
                              {3840, 2160}, {640, 480}, {1000, 1000}};
            float[][] crops = {null,
                               {0f, 0f, 1f, 1f},
                               {0.25f, 0.25f, 0.75f, 0.75f},
                               {0.373f, 0f, 0.627f, 1f},      // 屏幕比例竖条
                               {0f, 0f, 0.1f, 0.9f}};         // 极窄的条
            // 含历史遗留值 2（旧 STRETCH）—— 也必须不变形
            int[] fits = {FIT_CONTAIN, FIT_COVER, 2, 7, -1};
            int checked = 0;
            for (int[] v : videos) {
                for (float[] c : crops) {
                    for (int fit : fits) {
                        float[] r = VideoFitMath.compute(v[0], v[1], SW, SH, fit, c);
                        if (Math.abs(r[0] - r[1]) > 1e-3f) {
                            ok(false, "视频 " + v[0] + "x" + v[1] + " fit=" + fit
                                    + " crop=" + java.util.Arrays.toString(c)
                                    + " 出现 sx=" + r[0] + " != sy=" + r[1]);
                        }
                        checked++;
                    }
                }
            }
            ok(true, "覆盖 " + checked + " 种组合，全部等比");
            System.out.println("      组合数 " + checked + "，全部 sx==sy");
        }

        System.out.println("[3b] 未知/历史 fit 值必须退化成 CONTAIN，不得退化成拉伸");
        {
            float[] a = VideoFitMath.compute(VW, VH, SW, SH, 2, null);   // 旧 STRETCH 值
            float[] b = VideoFitMath.compute(VW, VH, SW, SH, FIT_CONTAIN, null);
            near(a[0], b[0], 1e-6f, "fit=2 与 CONTAIN 同缩放");
            near(a[1], b[1], 1e-6f, "fit=2 与 CONTAIN 同缩放(y)");
        }

        System.out.println("[4] 两种铺法都必须居中（不偏移）");
        {
            for (int fit : new int[]{FIT_CONTAIN, FIT_COVER}) {
                float[] r = drawn(VW, VH, SW, SH, fit, null);
                near(r[2] + r[4] / 2f, SW / 2f, 0.5f, "fit=" + fit + " 水平居中");
                near(r[3] + r[5] / 2f, SH / 2f, 0.5f, "fit=" + fit + " 垂直居中");
            }
        }

        System.out.println("[5] 用户裁出的框要被尊重（框内内容铺满，框外不考虑）");
        {
            // 裁视频中央那块：(0.25,0.25)-(0.75,0.75) => 960x540
            float[] crop = {0.25f, 0.25f, 0.75f, 0.75f};
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, crop);
            near(r[0], r[1], 1e-3f, "等比不变形");
            ok(r[4] >= SW - 0.5f && r[5] >= SH - 0.5f, "裁区铺满屏幕");
            // 裁区中心 = 视频中心，所以铺满后仍应居中
            near(r[2] + r[4] / 2f, SW / 2f, 0.5f, "裁区居中");
        }

        System.out.println("[6] 裁左上角时，横向必须偏移（不能仍居中）");
        {
            float[] crop = {0f, 0f, 0.5f, 0.5f};   // 左上四分之一 => 960x540
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, crop);
            // 裁区左边界在视频原点，铺满后要把它拉到屏幕左边界；
            // 因为横向溢出 ⇒ 左边界为负（向左平移）
            ok(r[2] < -1f, "横向向左平移（把裁区左边界拉到屏幕外，居中裁）");
            // ★ 纵向**不该**偏移：960x540 在竖屏下是"宽受限"，
            //   铺满时纵向恰好溢出，居中即 0。第一版断言要求 ty<0，
            //   那是错的 —— 修正为"恰好居中"。
            near(r[3], (SH - r[5]) / 2f, 0.5f, "纵向仍居中");
            System.out.println("      left/top = " + r[2] + " / " + r[3]
                    + "  绘制 " + r[4] + " x " + r[5]);
        }

        System.out.println("[7] 退化输入不得崩、不得 NaN");
        {
            ok(noNaN(VideoFitMath.compute(VW, VH, SW, SH, FIT_CONTAIN, null)), "正常输入无 NaN");
            ok(noNaN(VideoFitMath.compute(VW, VH, SW, SH, FIT_CONTAIN,
                    new float[]{0.5f, 0.5f, 0.5f, 0.5f})), "零面积裁区无 NaN");
            ok(noNaN(VideoFitMath.compute(0, 0, SW, SH, FIT_COVER, null)), "视频尺寸为 0 无 NaN");
        }

        // 用户第四轮的明确要求：「视频背景按照屏幕大小纵向裁切，
        // 不要横屏，截取视频中的一段就好，不用完整平铺」
        final float ASPECT = SH / (float) SW;      // 2400/1080 ≈ 2.222

        System.out.println("[8] 默认裁框 = 按屏幕比例的居中竖条（横屏片）");
        {
            float[] c = VideoFitMath.defaultCrop(VW, VH, ASPECT);
            // 横屏片要竖条 ⇒ 必然占满高度、只取中间一条
            float bw = c[2] - c[0], bh = c[3] - c[1];
            near(bh, 1f, 1e-3f, "占满整个高度（截取一段，不留黑边）");
            ok(bw < 0.5f, "宽度只取中间一条（横屏片当竖屏背景必须裁）");
            // 裁出来的比例必须等于屏幕比例
            float got = (bh * VH) / (bw * VW);
            near(got, ASPECT, 0.01f, "裁出的宽高比 == 屏幕比例");
            // 居中
            near(c[0], (1f - bw) / 2f, 1e-3f, "水平居中");
            near(c[1], 0f, 1e-3f, "垂直居中（占满高度时为 0）");
            System.out.println("      竖条 l/t/r/b = " + c[0] + "," + c[1]
                    + "," + c[2] + "," + c[3] + "  (宽 " + bw + ")");
        }

        System.out.println("[9] 默认裁框 + 铺满 ⇒ 不变形且刚好铺满（用户要的效果）");
        {
            float[] c = VideoFitMath.defaultCrop(VW, VH, ASPECT);
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, c);
            near(r[0], r[1], 1e-3f, "等比（绝不变形）");
            near(r[4], SW, 1.0f, "宽度恰好铺满（无黑边）");
            near(r[5], SH, 1.0f, "高度恰好铺满（无黑边）");
            System.out.println("      绘制 " + r[4] + " x " + r[5]
                    + " @ (" + r[2] + ", " + r[3] + ")");
        }

        System.out.println("[10] 竖屏视频：默认裁框应是整段（本来就不用裁）");
        {
            // 1080x2400 的竖屏片，比例与屏幕相近
            float[] c = VideoFitMath.defaultCrop(1080, 2400, ASPECT);
            near(c[0], 0f, 1e-3f, "左边界 0");
            near(c[1], 0f, 1e-3f, "上边界 0");
            near(c[2], 1f, 1e-3f, "右边界 1（竖屏片无需横向裁）");
            near(c[3], 1f, 1e-3f, "下边界 1");
        }

        System.out.println("[11] 默认裁框必须始终落在视频内（多种比例）");
        {
            int[][] videos = {{1920, 1080}, {1080, 1920}, {3840, 2160},
                              {1080, 2400}, {800, 600}, {1000, 1000}};
            for (int[] v : videos) {
                for (float asp : new float[]{2400f / 1080f, 1920f / 1080f, 1f}) {
                    float[] c = VideoFitMath.defaultCrop(v[0], v[1], asp);
                    boolean in = c[0] >= -1e-4f && c[1] >= -1e-4f
                            && c[2] <= 1f + 1e-4f && c[3] <= 1f + 1e-4f
                            && c[0] < c[2] && c[1] < c[3];
                    ok(in, "视频 " + v[0] + "x" + v[1] + " 比例 " + asp
                            + " 的裁框在范围内");
                }
            }
        }

        // ★ 用户第三轮反馈的根因：裁剪框是自由比例时，
        //   「铺满」只取框中心那一块 ⇒ 框里看到的 ≠ 实际显示的。
        //   锁成屏幕比例后，框内内容 === 屏幕内容（下面 [12][13] 断言这一点）。
        System.out.println("[12] conformToAspect：任意自由框都能被锁成屏幕比例");
        {
            int[][] videos = {{1920, 1080}, {1080, 1920}, {1000, 1000}, {3840, 2160}};
            // 各种"用户可能拉出来的"畸形框
            float[][] freeBoxes = {
                {0f, 0f, 1f, 1f},           // 整帧（横屏下比例不对）
                {0f, 0.3f, 1f, 0.7f},       // 很扁
                {0.3f, 0f, 0.7f, 1f},       // 很窄
                {0.1f, 0.1f, 0.9f, 0.2f},   // 极扁
                {0.45f, 0f, 0.55f, 1f},     // 极窄
                {0f, 0f, 0.2f, 0.2f},       // 左上小方块
            };
            for (int[] v : videos) {
                for (float[] box : freeBoxes) {
                    float[] c = box.clone();
                    VideoFitMath.conformToAspect(c, v[0], v[1], ASPECT);
                    float bw = c[2] - c[0], bh = c[3] - c[1];
                    // ① 比例正确
                    float got = (bh * v[1]) / (bw * v[0]);
                    ok(Math.abs(got - ASPECT) < 0.02f,
                            "视频 " + v[0] + "x" + v[1] + " 框 "
                                    + java.util.Arrays.toString(box)
                                    + " 校正后比例 " + got + " 应 ≈ " + ASPECT);
                    // ② 仍在视频内
                    ok(c[0] >= -1e-3f && c[1] >= -1e-3f
                                    && c[2] <= 1f + 1e-3f && c[3] <= 1f + 1e-3f,
                            "校正后仍在视频内 " + java.util.Arrays.toString(c));
                    // ③ 面积不能变成 0
                    ok(bw > 0.01f && bh > 0.01f,
                            "校正后面积非零，实际 " + bw + "x" + bh);
                }
            }
        }

        System.out.println("[13] ★ 锁比例后：框内内容 === 屏幕内容（1:1 对应）");
        {
            // 用户实际会遇到的：1920x1080 片，屏比例 2.222
            float[] box = {0f, 0f, 1f, 1f};
            VideoFitMath.conformToAspect(box, VW, VH, ASPECT);
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, box);
            // 锁了比例 ⇒ 缩放恰好铺满、没有溢出 ⇒ 框里每一像素都在屏幕上
            near(r[4], SW, 1.0f, "宽度恰好等于屏幕宽（无溢出、无裁切）");
            near(r[5], SH, 1.0f, "高度恰好等于屏幕高");
            System.out.println("      校正后框 " + java.util.Arrays.toString(box)
                    + " → 绘制 " + r[4] + " x " + r[5]);
        }

        System.out.println("[14] 未锁比例时确实会不一致（说明这一步不能省）");
        {
            // 故意用一个"很扁"的框（宽 1、高 0.2）+ COVER。
            // 扁框在竖屏下，COVER 取 max 缩放 ⇒ **横向**大幅溢出，
            // 也就是说屏幕上只看到框中心那一小条 —— 框里两侧的内容
            // 根本进不了屏幕。这正是用户看到"预览和实际不一样"的机理。
            //
            // ★ 我第一版把这条断言写成了"纵向溢出"，是错的：
            //   竖屏下扁框必然横向溢出（宽/高比 5 vs 屏幕 0.45）。
            //   断言失败先怀疑断言 —— 这里就是。
            float[] wide = {0f, 0.4f, 1f, 0.6f};
            float[] r = drawn(VW, VH, SW, SH, FIT_COVER, wide);
            ok(r[4] > SW + 1f, "扁框铺满时**横向**溢出（只显示框中心一条）");
            ok(Math.abs(r[5] - SH) < 1.5f, "纵向恰好铺满");
            System.out.println("      扁框 → 绘制 " + r[4] + " x " + r[5]
                    + "（宽溢出 " + (r[4] - SW) + "px = 框两侧被裁掉）");

            // 锁比例之后就刚好铺满、不再溢出 ⇒ 框内内容 1:1 进屏幕
            float[] fixed = wide.clone();
            VideoFitMath.conformToAspect(fixed, VW, VH, ASPECT);
            float[] r2 = drawn(VW, VH, SW, SH, FIT_COVER, fixed);
            near(r2[4], SW, 1.5f, "锁比例后宽度刚好铺满");
            near(r2[5], SH, 1.5f, "锁比例后高度刚好铺满");
            System.out.println("      锁比例后 → 绘制 " + r2[4] + " x " + r2[5]
                    + "（无溢出 ⇒ 框内就是屏幕内容）");
        }

        System.out.println("[15] ★ 实测抓到的那个值必须被校正（回归）");
        {
            // 设备上实测到 crop = 0.2549..0.5299 + COVER，像素比只有 2.045，
            // 而屏幕比例是 2.222 —— 也就是说"锁比例"当时没生效。
            // 把这个真实值钉成回归用例：校正后必须 == 屏幕比例。
            float[] norm = {0.25492272f, 0.0f, 0.52993405f, 1.0f};
            VideoFitMath.conformToAspect(norm, VW, VH, ASPECT);
            float bw = (norm[2] - norm[0]) * VW;
            float bh = (norm[3] - norm[1]) * VH;
            float got = bh / bw;
            near(got, ASPECT, 0.02f * ASPECT, "校正后像素比 == 屏幕比例");
            // 占满高度时，宽度必须落在"由 k 反推"的那个值上（k = aspect*vw/vh）
            float expectBw = VW * VH / (ASPECT * VW);   // = vh/aspect
            near(bw, expectBw, 1.5f, "占满高度时宽度 = vh/aspect");
            System.out.println("      " + java.util.Arrays.toString(norm)
                    + "  →  像素比 " + got + "，宽 " + bw
                    + "（应为 " + expectBw + "）");
        }

        System.out.println("[16] 校正必须收敛（全宽/全高的极端框）");
        {
            float[][] extremes = {
                {0f, 0f, 1f, 1f}, {0f, 0f, 0.01f, 1f}, {0f, 0f, 1f, 0.01f},
                {0.99f, 0f, 1f, 1f}, {0f, 0.99f, 1f, 1f},
            };
            for (float[] e : extremes) {
                float[] c = e.clone();
                VideoFitMath.conformToAspect(c, VW, VH, ASPECT);
                float bw = c[2] - c[0], bh = c[3] - c[1];
                ok(bw > 0f && bh > 0f, "极端框 " + java.util.Arrays.toString(e)
                        + " 校正后面积非零");
                ok(c[0] >= -1e-3f && c[1] >= -1e-3f
                                && c[2] <= 1f + 1e-3f && c[3] <= 1f + 1e-3f,
                        "极端框校正后仍在视频内");
            }
        }

        System.out.println("[17] ★ 框锁成屏幕比例后，COVER 与 CONTAIN 完全等价");
        {
            // 用户要求「完全按照裁剪框里面的截取」——框内内容 1:1 进屏幕。
            // 框一旦锁成屏幕比例，W/cw == H/ch，于是
            //   COVER  = max(W/cw, H/ch) = W/cw
            //   CONTAIN= min(W/cw, H/ch) = W/cw
            // 两者恒等 ⇒ 再给用户一个"铺法"选择只会误导（选了看不出区别）。
            int[][] videos = {{1920, 1080}, {1080, 2400}, {3840, 2160}, {1000, 1000}};
            for (int[] v : videos) {
                float[] box = VideoFitMath.defaultCrop(v[0], v[1], ASPECT);
                float[] a = VideoFitMath.compute(v[0], v[1], SW, SH, FIT_COVER, box);
                float[] b = VideoFitMath.compute(v[0], v[1], SW, SH, FIT_CONTAIN, box);
                near(a[0], b[0], 1e-4f, "视频 " + v[0] + "x" + v[1] + " 两模式同缩放");
                near(a[2], b[2], 1e-3f, "同平移 tx");
                near(a[3], b[3], 1e-3f, "同平移 ty");
                // 且都恰好铺满：无溢出、无留边
                float cw = (box[2] - box[0]) * v[0], ch = (box[3] - box[1]) * v[1];
                near(cw * a[0], SW, 1.5f, "框内容宽度恰好等于屏幕宽");
                near(ch * a[1], SH, 1.5f, "框内容高度恰好等于屏幕高");
            }
            System.out.println("      4 种视频尺寸下两模式输出完全一致，且都恰好铺满");
        }

        System.out.println("[18] ★ 「框内显示的内容」必须等于「记录下来给渲染用的 crop」");
        {
            // 这是用户第三轮反馈的核心：框里有几条、屏幕上就该有几条。
            //
            // 机理：VideoCropView 把 crop 摆到固定取景框上，然后用
            // getCropRect() 反算"实际显示的区域"存进 prefs。
            // 若摆放时用的缩放与反算时用的缩放不一致（比如摆放用 max()
            // 铺满、而 crop 比例又和取景框不相等），两者就会出现偏差 ——
            // 表现是"框里 3 条、实际 2.6 条"，而且每开一次框缩一点。
            //
            // 正确做法：摆放前先把 crop 校正到取景框比例
            // （applyCrop 里调 conformToAspect），此时两轴缩放相等，
            // 反算结果与进来时**完全相同**。
            //
            // 这里用纯数学复现"摆放到反算"的往返：
            //   取景框 1080x2337（比例 2.164），crop 是各种自由比例
            for (float[] box : new float[][]{
                    {0.2f, 0.0f, 0.8f, 1.0f},        // 偏宽
                    {0.4f, 0.0f, 0.6f, 1.0f},        // 很窄
                    {0.0f, 0.0f, 1.0f, 0.3f},        // 很扁
                    {0.3f, 0.2f, 0.7f, 0.8f},        // 居中块
            }) {
                float[] c = box.clone();
                VideoFitMath.conformToAspect(c, VW, VH, ASPECT);
                // 摆放：比例已一致 ⇒ 两轴缩放相等
                float cw = (c[2] - c[0]) * VW;
                float ch = (c[3] - c[1]) * VH;
                float sc = Math.max(SW / cw, SH / ch);
                // 反算：屏幕可见区 → 源坐标
                float visW = SW / sc, visH = SH / sc;
                float cx = (c[0] + c[2]) / 2f * VW;
                float cy = (c[1] + c[3]) / 2f * VH;
                float gl = (cx - visW / 2f) / VW;
                float gr = (cx + visW / 2f) / VW;
                float gt = (cy - visH / 2f) / VH;
                float gb = (cy + visH / 2f) / VH;
                // 往返一致
                near(gl, c[0], 0.005f, "往返左边界一致 " + java.util.Arrays.toString(box));
                near(gr, c[2], 0.005f, "往返右边界一致");
                near(gt, c[1], 0.005f, "往返上边界一致");
                near(gb, c[3], 0.005f, "往返下边界一致");
                // 且可见区确实等于取景框大小（不多裁）
                //
                // ★ 注意单位：visW/visH 是**源像素**宽度，不是屏幕像素。
                //   我第一版写成 near(visW, SW) 显然是量纲错了。
                //   正确判据是"crop 像素宽 × 缩放 == 屏幕宽"。
                near(cw * sc, SW, 1.5f, "屏幕横向恰好铺满（不多裁）");
                near(ch * sc, SH, 1.5f, "屏幕纵向恰好铺满");
            }
            System.out.println("      4 种自由比例框，往返映射全部一致");
        }

        System.out.println("[19] ★ setTransform 必须用「视图空间」矩阵（用户两轮反馈的根因）");
        {
            // TextureView 默认把视频**拉伸铺满视图**，setTransform 的矩阵
            // 作用在这个"已拉伸"的坐标系上。若直接把源像素空间的
            // scale/offset 传进去，等于叠了两层缩放 —— 画面整体偏移。
            //
            // 实测对照（1920x1080 片 / 1080x2337 视图，crop 0.3969..0.6566）：
            //   源像素空间推算：源 762 落在屏幕 x=0
            //   视图空间推算  ：源 1354 落在屏幕 x=0
            //   屏幕实测      ：源 1344 落在屏幕 x=0   ← 视图空间才对
            final int VW2 = 1920, VH2 = 1080, SW2 = 1080, SH2 = 2337;
            float[] crop = {0.39689034f, 0.00052926f, 0.6565638f, 0.9994707f};
            float[] t = VideoFitMath.compute(VW2, VH2, SW2, SH2, FIT_COVER, crop);
            float[] v = VideoFitMath.toViewSpaceTransform(t, VW2, VH2, SW2, SH2);

            // 默认拉伸比：源像素 → 视图像素
            float rx = (float) SW2 / VW2;

            // 求"屏幕 x=0 处显示的是源视频的哪个 x"。
            //
            // setTransform 的矩阵作用在**视图坐标系**（视频已被默认拉伸铺满），
            // 所以：  源 x = M⁻¹(屏幕x) / rx
            //   M⁻¹(p) = (p - tx) / sx
            //   ⇒ 源 x = (p - tx) / (sx * rx)
            float raw0 = (0f - t[2]) / (t[0] * rx);       // 不转换（旧代码）
            float fix0 = (0f - v[2]) / (v[0] * rx);       // 转换后（新代码）

            // ① 旧代码复现实测到的错误值（1344 左右 = 屏幕左边缘是第 7 条色带）
            near(raw0, 1344f, 40f,
                    "不转换时 屏幕x=0 ↔ 源 x≈1344（复现实测的错位）");
            // ② 新代码得到"意图"的值（crop 左边界 = 0.3969*1920 = 762）
            near(fix0, 762f, 15f,
                    "转换后 屏幕x=0 ↔ 源 x≈762（框的左边界）");

            System.out.println("      不转换: 源 " + raw0 + "（实测 1344 → 就是它错位）");
            System.out.println("      转换后: 源 " + fix0 + "（意图 762 → 正确）");
        }

        System.out.println();
        System.out.println(failed == 0
                ? ("全部通过：" + passed + " 项")
                : ("失败 " + failed + " 项 / 共 " + (passed + failed)));
        if (failed != 0) System.exit(1);
    }

    private static boolean noNaN(float[] a) {
        for (float v : a) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return false;
        }
        return true;
    }
}