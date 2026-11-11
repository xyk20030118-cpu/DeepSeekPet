package com.deepseek.pet;

/**
 * 内容边距（"角色可见内容"在窗口里占哪一块）的**纯计算** —— <b>零 Android 依赖</b>
 * （照 {@link FpsMath} / {@link Appearance} 的手法写，所以能在 {@code build/fpstest} 里做真断言）。
 *
 * <h3>它解决什么</h3>
 * 桌宠窗口是 240×135dp 的固定框，而里面的东西（动画包 clip / 上传的图片 / 动图）
 * <b>往往填不满这个框</b>：
 * <ul>
 *   <li>动画包 clip 的 PNG/WebP 画布四周有透明留白（本工程内置包实测左右各留 <b>33%</b>）；</li>
 *   <li>上传的图片大多是<b>方图</b>，而窗口是 16:9 ⇒ {@code FIT_CENTER} 等比缩放后左右各留一大块黑边
 *       （实测 630×354 的窗口里，方图只占中间 354×354 ⇒ 左右各空 138px）。</li>
 * </ul>
 * 所以"贴边"不能把<b>窗口</b>贴到屏幕边，必须把<b>内容</b>贴到屏幕边 ——
 * 也就是允许窗口挂出屏幕外一段 = 内容的留白宽度。
 *
 * <h3>为什么这里要再算一次（而不是直接用 alpha 包围盒）</h3>
 * 素材层给的 alpha 包围盒是**相对画布**的比例；而画面是画在<b>视口</b>里的，
 * 视口与画布的长宽比不一定相同（方图 vs 16:9）。中间差一个"等比缩放 + 居中留白"，
 * 不换算就会出现"贴边差 138px 却没人发现"这类错误。本类只做这一步换算。
 *
 * <h3>★ 铁律：已知的边距<b>不许</b>退回 null</h3>
 * 边距是**异步**算出来的（要解码 + 扫 alpha，不能在 UI 线程做）。旧代码在换动作时
 * 先把边距清成 {@code null}、再异步补上 —— 而"松手贴边"的判定就跑在这个空窗里，
 * 于是 {@code inset=null} 被当成"没有留白"⇒ 窗口不许挂出屏外 ⇒ 桌宠<b>永远贴不到边</b>
 * （2026-10-08 真机报障的根因）。所以换动作时用 {@link #keep} 保留上一个值：
 * 同一个角色包里各动作的构图几乎一样，沿用上一个值远好过当成 0。
 */
public final class ContentInset {

    private ContentInset() {}

    /** 空边距（内容铺满窗口） */
    public static final float[] NONE = {0f, 0f, 0f, 0f};

    /**
     * 把"画布空间的 alpha 包围盒"换算成"视口空间的内容边距"。
     *
     * @param bmW      画布宽（像素）
     * @param bmH      画布高（像素）
     * @param alpha    {L,R,T,B} 画布内的透明留白比例（0~1，来自 alpha 包围盒）
     * @param viewW    视口宽（窗口内容区宽）
     * @param viewH    视口高
     * @param fitCenter true = 等比缩放居中（{@code FIT_CENTER}）；false = 拉伸铺满（{@code FIT_XY}）
     * @return {L,R,T,B} 相对视口的留白比例（0~1）；参数不合法时返回 {@link #NONE}
     */
    public static float[] of(int bmW, int bmH, float[] alpha,
                             int viewW, int viewH, boolean fitCenter) {
        if (bmW <= 0 || bmH <= 0 || viewW <= 0 || viewH <= 0) return NONE;
        if (alpha == null || alpha.length < 4) return NONE;

        float drawW, drawH, offX, offY;
        if (fitCenter) {
            float s = Math.min(viewW / (float) bmW, viewH / (float) bmH);
            drawW = bmW * s;
            drawH = bmH * s;
            offX = (viewW - drawW) / 2f;
            offY = (viewH - drawH) / 2f;
        } else {
            drawW = viewW;
            drawH = viewH;
            offX = 0f;
            offY = 0f;
        }

        float left = offX + clamp01(alpha[0]) * drawW;
        float right = offX + drawW - clamp01(alpha[1]) * drawW;
        float top = offY + clamp01(alpha[2]) * drawH;
        float bottom = offY + drawH - clamp01(alpha[3]) * drawH;
        if (right < left) right = left;
        if (bottom < top) bottom = top;

        return new float[]{
                clamp01(left / viewW),
                clamp01((viewW - right) / viewW),
                clamp01(top / viewH),
                clamp01((viewH - bottom) / viewH)
        };
    }

    /** 便捷重载：直接用原始像素的包围盒（{minX,minY,maxX,maxY}，含端点） */
    public static float[] ofPixels(int bmW, int bmH, int minX, int minY, int maxX, int maxY,
                                   int viewW, int viewH, boolean fitCenter) {
        if (bmW <= 0 || bmH <= 0 || maxX < minX || maxY < minY) return NONE;
        float[] a = {
                minX / (float) bmW,
                (bmW - 1 - maxX) / (float) bmW,
                minY / (float) bmH,
                (bmH - 1 - maxY) / (float) bmH
        };
        return of(bmW, bmH, a, viewW, viewH, fitCenter);
    }

    /**
     * 换动作时的新边距：新值可用就用新值，否则**保留旧值**（绝不用 null 覆盖已知值）。
     * <p>见类注释末尾的铁律 —— 这条是"贴不到边"那个 bug 的正解。
     */
    public static float[] keep(float[] prev, float[] next) {
        return usable(next) ? next : prev;
    }

    /** 这个边距能不能用（非 null、四个分量都有限且非负） */
    public static boolean usable(float[] in) {
        if (in == null || in.length < 4) return false;
        for (int i = 0; i < 4; i++) {
            float v = in[i];
            if (Float.isNaN(v) || Float.isInfinite(v) || v < 0f) return false;
        }
        return true;
    }

    /**
     * 窗口 x 的可达范围 {minX, maxX}：让**内容可见边缘**正好贴到物理屏幕边缘。
     *
     * <p>这是"贴边"的唯一判据 —— 拖动钳制 / 松手吸附 / 边缘热区 / 漫游范围四处共用它，
     * 各写一份必然漂移（漫游那条曾经就只按"视口留白"算，于是走到边上还差一大截）。
     */
    public static int[] xRange(int screenW, int winW, float[] inset) {
        float[] in = usable(inset) ? inset : NONE;
        int inL = (int) (clamp01(in[0]) * winW);
        int inR = (int) (clamp01(in[1]) * winW);
        int minX = -inL;
        int maxX = screenW - winW + inR;
        if (maxX < minX) maxX = minX;
        return new int[]{minX, maxX};
    }

    /**
     * 「贴边」判定用的近边阈值（px）：离边界这么近就算"已经贴在那边了"。
     * <p>与漫游"太近就不走"的判据同一个量级 —— 那点距离本来也不构成"一段漫游"。
     */
    public static final int EDGE_PX = 40;

    /**
     * 这一段漫游往哪边走：{@code +1} = 向右，{@code -1} = 向左（轮次60）。
     *
     * <h3>★ 为什么不掷骰子（用户原话）</h3>
     * 「每次触发方向都是随机的，**有概率触发不了**，统一改成**贴在哪边往另一边漫游**」。
     * 旧写法是 {@code dir = Math.random() < 0.5 ? -1 : 1}：贴着左边时有一半概率朝**墙**走，
     * 目标被 {@code clamp} 夹回原地 ⇒ 调用方判"太近，这一段不走" ⇒ 用户点了漫游**没反应**
     * （三击菜单的「主动漫游」尤其明显：桌宠通常就贴在边上）。
     *
     * <h3>规则（确定：同样输入永远同样结果 ⇒ 可断言）</h3>
     * <ol>
     *   <li><b>贴哪边就往另一边走</b>：贴左（离左界 ≤ {@link #EDGE_PX}）⇒ 向右；贴右 ⇒ 向左；</li>
     *   <li>不在边上（停在屏幕中间）⇒ 走向**空间更大**的那一侧；</li>
     *   <li>左右一样大（正中间）⇒ 向右（取定值，不留随机）。</li>
     * </ol>
     */
    public static int roamDirAway(int minX, int maxX, int cur) {
        if (maxX < minX) { int t = minX; minX = maxX; maxX = t; }
        int left = cur - minX;
        int right = maxX - cur;
        if (left <= EDGE_PX) return 1;                 // 贴左 ⇒ 往右
        if (right <= EDGE_PX) return -1;               // 贴右 ⇒ 往左
        return (right >= left) ? 1 : -1;               // 中间 ⇒ 走向空间更大的一侧
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }
}
