package com.deepseek.pet;

/**
 * 视频「取哪一块 + 怎么铺」的**纯数学**部分。
 *
 * <h3>为什么要单独抽一个不依赖 Android 的类</h3>
 * 这段逻辑原先写在 {@code VideoBackgroundView.applyAspect()} 里，直接用
 * {@code android.graphics.Matrix}。结果是**没法单元测试** —— 本工程的
 * 离线测试套件（{@code build/ttstest}）是纯 javac + java，没有 Android
 * 运行时，凡是 import 了 android.* 的类都加载不了。
 *
 * <p>而"铺法算对没有"恰恰是最该被测试盯住的地方：用户报的
 * 「视频导入之后无法正常显示，被裁切了」就是这段算错。
 * 所以把数学抽到这里（**不 import 任何 android 类**），
 * 由 {@code build/vidtest} 直接断言：
 * <ul>
 *   <li>完整显示 / 铺满：{@code sx == sy}（**等比、绝不变形**）</li>
 *   <li>完整显示：裁区**整个**都装得下（{@code cw*sx <= viewW} 且 {@code ch*sy <= viewH}）</li>
 *   <li>铺满：裁区**必须溢出或恰好铺满**（{@code cw*sx >= viewW} 且 {@code ch*sy >= viewH}）</li>
 *   <li>拉伸：两个方向都**恰好**铺满（不溢出、不留边）</li>
 * </ul>
 */
final class VideoFitMath {

    private VideoFitMath() {
    }

    /**
     * 返回 {@code {scaleX, scaleY, tx, ty}}：视频坐标 × scale + t = 视图坐标。
     *
     * <h3>★ 铁律：绝不变形</h3>
     * 用户明确要求「裁剪视频不要做任何拉伸变形，只保留裁剪部分」。
     * 所以这里的 {@code scaleX} 与 {@code scaleY} **永远相等** ——
     * 无论哪种模式，视频都只做**等比**缩放。区别只在于"取大一点还是小一点"：
     * <ul>
     *   <li>{@code FIT_COVER}（默认）：取 {@code max} ⇒ 短边铺满、长边溢出被裁。
     *       画面填满屏幕且不失真。</li>
     *   <li>{@code FIT_CONTAIN}：取 {@code min} ⇒ 整个裁区都装得下，边上留黑。</li>
     * </ul>
     *
     * <p>早先还有一个 {@code FIT_STRETCH}（两轴各自铺满），它**必然会变形**，
     * 已按用户要求**彻底删除**。不要以"用户可能想要填满"为理由加回来 ——
     * 那正是被否掉的东西。想"填满"就用 {@code FIT_COVER}：既填满又不变形。
     *
     * <p>{@link #compute} 里仍保留对未知 {@code fit} 值的兜底（走 CONTAIN 分支），
     * 这样即使 prefs 里存了历史遗留的 {@code 2}，也只会退化成"留黑边"，
     * 绝不会退化成"拉伸变形"。
     */
    static float[] compute(int vw, int vh, int viewW, int viewH, int fit, float[] crop) {
        float cl = 0f, ct = 0f, cr = 1f, cb = 1f;
        if (crop != null && crop.length == 4) {
            cl = crop[0]; ct = crop[1]; cr = crop[2]; cb = crop[3];
        }
        float cw = (cr - cl) * vw;      // 裁区宽（视频像素）
        float ch = (cb - ct) * vh;      // 裁区高
        if (cw <= 0f || ch <= 0f) return new float[]{1f, 1f, 0f, 0f};

        // 两个分支只在"取 max 还是 min"上不同，**缩放值两个轴共用一个** ——
        // 这就是"绝不变形"的实现方式（不是"注意到别写错"，而是结构上不可能写错）。
        float sx, sy;
        if (fit == 1) {
            float s = Math.max((float) viewW / cw, (float) viewH / ch);
            sx = s;
            sy = s;
        } else {
            // CONTAIN，以及任何未知值（含历史遗留的 STRETCH=2）都走这里
            float s = Math.min((float) viewW / cw, (float) viewH / ch);
            sx = s;
            sy = s;
        }

        // 让"裁区中心"落到"视图中心"。
        //
        // 两种铺法共用这一句：CONTAIN 时把画面居中（两边等宽留黑），
        // COVER 时把溢出的部分**均分到两侧**（不偏向一边）。
        float boxCx = (cl + cr) / 2f * vw;
        float boxCy = (ct + cb) / 2f * vh;
        float tx = viewW / 2f - boxCx * sx;
        float ty = viewH / 2f - boxCy * sy;
        return new float[]{sx, sy, tx, ty};
    }

    /**
     * 把一条归一化裁框**校正成目标宽高比**（保持中心、保持尽量大、不出视频边界）。
     *
     * <h3>★ 为什么必须有这一步（用户第三轮反馈的根因）</h3>
     * 裁剪框若允许**自由比例**，就会和最终画面不一致：
     * <pre>
     *   框拉得很宽（红橙黄绿青全在框里）
     *     → 「铺满」只取框**正中间**那块、再裁成屏幕比例
     *     → 背景上只剩框中心的一小条（青/蓝）
     *   ⇒ 用户："我框住的是这一段，出来的却是别的！"
     * </pre>
     * 把框**锁成屏幕比例**后，{@code FIT_COVER} 的缩放两轴必然相等
     * （见 {@link #compute} 的推导），于是
     * <b>框内内容 == 屏幕内容</b>，预览与实景在数学上不可能不一致。
     *
     * <p>做法：先按当前框算出"若要满足目标比例、该缩哪一边"，
     * 然后以框心为锚点等比调整到刚好符合，最后夹回 [0,1]。
     * 若因夹取导致比例又偏了，就再缩一次（最多两轮就收敛）。
     *
     * @param norm         {@code [l,t,r,b]}，会被就地修改
     * @param vw,vh        视频宽高（像素）
     * @param screenAspect 目标比例 = 高/宽
     */
    static void conformToAspect(float[] norm, int vw, int vh, float screenAspect) {
        if (norm == null || norm.length != 4) return;
        if (vw <= 0 || vh <= 0 || screenAspect <= 0f) return;

        float bw = norm[2] - norm[0];
        float bh = norm[3] - norm[1];
        if (bw <= 0f || bh <= 0f) return;

        // 目标：归一化坐标下 bh/bw == screenAspect * vw / vh
        // （因为像素高/宽 = (bh*vh)/(bw*vw) 要等于 screenAspect）
        final float k = screenAspect * vw / vh;

        for (int iter = 0; iter < 2; iter++) {
            bw = norm[2] - norm[0];
            bh = norm[3] - norm[1];
            if (bw <= 0f || bh <= 0f) return;

            float cx = (norm[0] + norm[2]) / 2f;
            float cy = (norm[1] + norm[3]) / 2f;

            float cur = bh / bw;
            if (Math.abs(cur - k) < 1e-4f) break;

            if (cur > k) {
                // 太高 → 压高度
                bh = bw * k;
            } else {
                // 太宽 → 压宽度
                bw = bh / k;
            }

            // 不能超出视频
            if (bw > 1f) { bw = 1f; bh = bw * k; }
            if (bh > 1f) { bh = 1f; bw = bh / k; }

            // 以原中心为中心，再夹进 [0,1]
            float l = cx - bw / 2f, t = cy - bh / 2f;
            if (l < 0f) l = 0f;
            if (t < 0f) t = 0f;
            if (l + bw > 1f) l = 1f - bw;
            if (t + bh > 1f) t = 1f - bh;
            norm[0] = l; norm[1] = t; norm[2] = l + bw; norm[3] = t + bh;
        }
    }

    /**
     * 把"源像素 → 视图像素"的变换**换算成 TextureView.setTransform 能用的矩阵**。
     *
     * <h3>★ 这是一个真实的、代价很高的 bug（用户连报两轮"预览和实际不一样"）</h3>
     * 我一直以为 {@code setTransform} 的矩阵作用在**视频像素坐标系**里，
     * 于是直接把手算的 {@code scale/offset} 传了进去。实际上：
     * <pre>
     *   TextureView 默认把视频**拉伸铺满整个视图**
     *     ⇒ 先把源像素映射到视图像素（x * viewW/vw）
     *     ⇒ setTransform 的矩阵作用在**这个视图坐标系**上（再叠加一层）
     *   两层叠加的结果 = 画面被放大了约 (viewW/vw) 倍，且整体偏移
     * </pre>
     *
     * <p><b>实测证据（带编号的色带视频 + 日志）</b>：
     * 日志说"源 x=762 应落在屏幕 x=0"，但屏幕上实际显示的是第 7~9 条色带
     * （源 1344..1920）。按"视图空间"重新推算是源 x=1354 落在屏幕 x=0 ——
     * <b>与实测 1344 只差 0.7%</b>，而按"像素空间"推算差 76%。
     *
     * <p><b>换算推导</b>（记 {@code dx = viewW/vw}，即默认拉伸比）：
     * <pre>
     *   期望：view = (x - cropLeft*vw) * s        （s = 源像素→视图像素的缩放）
     *   默认：viewDefault = x * dx
     *   设矩阵 M 作用在 viewDefault 上：
     *        M.sx * x*dx + M.tx = (x - cropLeft*vw) * s
     *   ⇒ M.sx = s * vw / viewW = s / dx
     *   ⇒ M.tx = -cropLeft*vw*s
     * </pre>
     * 也就是说：**把缩放除以默认拉伸比、平移保持不变**。
     *
     * @param t  {@link #compute} 的输出（源像素空间）
     * @return  可直接喂给 {@code TextureView.setTransform} 的 {sx,sy,tx,ty}
     */
    static float[] toViewSpaceTransform(float[] t, int vw, int vh, int viewW, int viewH) {
        if (vw <= 0 || vh <= 0 || viewW <= 0 || viewH <= 0) {
            return new float[]{1f, 1f, 0f, 0f};
        }
        // 默认拉伸比：源像素 → 视图像素
        float dx = (float) viewW / vw;
        float dy = (float) viewH / vh;
        return new float[]{t[0] / dx, t[1] / dy, t[2], t[3]};
    }

    /**
     * 默认取景框：**按屏幕比例、居中、尽可能大的一条竖条**（归一化）。
     *
     * <h3>为什么默认要裁，而不是"完整显示整段视频"</h3>
     * 曾经把默认设成"整段视频 + 完整显示（留黑边）"，想法是"什么都不点也
     * 不丢画面"。但用户看到后明确要求：
     * 「视频背景按照屏幕大小纵向裁切，不要横屏，截取视频中的一段就好，
     *   不用完整平铺」。
     *
     * <p>也就是说 —— 横屏视频配竖屏手机，正确做法不是把整段缩到中间
     * 留两条黑边（那样背景上下都是黑的，很难看），而是
     * **裁出一条和屏幕同比例的竖条去铺满屏幕**。至于裁哪一条，由用户在
     * 裁剪界面拖动/缩放决定；这个函数给的是那个"起点"。
     *
     * <p>算法：想要 {@code (bh*vh) / (bw*vw) == screenAspect}。
     * 先试"占满整个宽度"，若算出来的高度超过视频（横屏片必然如此），
     * 就改成"占满整个高度"，反推宽度。保证结果一定落在视频内。
     *
     * @param screenAspect 屏幕的 **高/宽** 比（如 2400/1080 ≈ 2.222）
     * @return 归一化 {@code {l, t, r, b}}
     */
    static float[] defaultCrop(int vw, int vh, float screenAspect) {
        if (vw <= 0 || vh <= 0 || screenAspect <= 0f) {
            return new float[]{0f, 0f, 1f, 1f};
        }
        float bw = 1f;                                              // 先试整宽
        float bh = bw * vw * screenAspect / vh;
        if (bh > 1f) {                                              // 太高 → 改整高
            bh = 1f;
            bw = bh * vh / (vw * screenAspect);
        }
        float l = (1f - bw) / 2f;
        float t = (1f - bh) / 2f;
        return new float[]{l, t, l + bw, t + bh};
    }
}