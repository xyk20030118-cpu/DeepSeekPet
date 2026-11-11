package com.deepseek.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 自绘工具箱 —— 把原型里的 CSS 效果翻译成 Android 绘制。
 *
 * <p>原型靠三条 CSS 能力撑起整个视觉，这里逐条对应：
 * <ul>
 *   <li>{@code box-shadow} 双色浮雕 → {@link EmbossDrawable}（预渲染到 Bitmap + BlurMaskFilter）</li>
 *   <li>{@code radial-gradient} Mesh 光斑 → {@link MeshDrawable}</li>
 *   <li>{@code backdrop-filter: blur} 玻璃 → {@link GlassDrawable}（半透明叠加 + 内高光 + 外阴影）</li>
 * </ul>
 *
 * <p><b>为什么把阴影预渲染成 Bitmap：</b>{@code BlurMaskFilter} 在硬件加速画布上
 * 不可靠，常见做法是给 View 设 {@code LAYER_TYPE_SOFTWARE} —— 但那会让**每一帧**
 * 都走软件绘制，列表滚动时掉帧。这里改成建 Drawable 时用一次性软件画布把
 * 阴影烤进 Bitmap，之后每帧只是贴图，走硬件加速。
 * 代价是换了颜色/尺寸要重建（已按尺寸缓存，见 {@link ShadowCache}）。
 */
final class UiKit {

    private UiKit() {}

    // ==================== 阴影缓存 ====================

    /**
     * 按「尺寸+圆角+配色+凹凸」缓存已烤好的阴影位图。
     * 设置页一屏几十个卡片，不缓存的话每次 invalidate 都要重新模糊一遍。
     */
    static final class ShadowCache {
        private Bitmap bmp;
        private int w = -1, h = -1, r = -1, key = 0;

        Bitmap get(int width, int height, int radius, int key, Painter p) {
            if (bmp != null && width == w && height == h && radius == r && key == this.key) {
                return bmp;
            }
            if (bmp != null) bmp.recycle();
            bmp = p.paint(width, height, radius);
            w = width; h = height; r = radius; this.key = key;
            return bmp;
        }
    }

    interface Painter {
        Bitmap paint(int width, int height, int radius);
    }

    // ==================== 新拟态浮雕 ====================

    /**
     * 浮雕阴影需要的内缩量（px）。
     *
     * <p><b>为什么必须内缩：</b>阴影要画在"形状本体之外"——偏移 + 模糊。
     * 但 Drawable 的边界就是 View 的边界，画到边界外会被直接裁掉。第一版没内缩，
     * 结果卡片的浮雕在左右两侧被切平，看起来像长了两只翅膀（截图确认）。
     *
     * <p><b>换算关系（容易错）：</b>CSS {@code box-shadow: 3px 3px 6px} 里的
     * 6px 是**模糊直径**，而 {@code BlurMaskFilter} 的 radius 是**模糊半径**。
     * 第一版直接把 6*density 当 radius 传，等于原型的两倍模糊 —— 内缩要 9dp，
     * 按钮被压得只剩 30dp 高。修正为 radius = 3dp（≈ CSS 6px 直径），
     * 内缩 = 偏移 3dp + 半径 3dp = 6dp。
     */
    static int embossInset(float density) {
        return embossOffset(density) + embossBlur(density);
    }

    /**
     * 当前引擎下**形状本体**相对 View 边界的内缩量（px）。
     *
     * <p><b>这是修「玻璃态下文字超出范围」的关键。</b>两套引擎的内缩量不同：
     * <ul>
     *   <li>新拟态：偏移 3dp + 模糊 3dp = 6dp（16px @420dpi）</li>
     *   <li>玻璃：偏移 3dp + 模糊 <b>6dp</b> = 9dp（24px）—— 见
     *       {@code renderGlass}，它的外阴影模糊半径是 6dp，比新拟态大一倍</li>
     * </ul>
     * 而 {@link #embossInset} 只返回新拟态那一套。布局层原先一律按它留白，
     * 于是玻璃引擎下"可见面板的左边缘"比布局以为的位置**再往内 8px** ——
     * 内容（尤其是直接放在卡片里的裸 TextView 和 SeekBar）就压到了面板边缘上，
     * 玻璃卡片还有一道可见描边，看起来就是"字超出了框"。
     *
     * <p>新拟态下这个偏差看不见：面板与背景同色，没有边缘可对照。
     * 这也解释了为什么这个毛病只有玻璃态才被报出来。
     */
    static int panelInset(Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        if (Tokens.skin(c) == Tokens.SKIN_GLASS) {
            return Math.max(2, Math.round(3 * d)) + Math.max(3, Math.round(6 * d));
        }
        return embossInset(d);
    }

    /**
     * 让凸起件的外阴影**正好**落在 {@code insetPx} 之内所需的缩放（≤1）。
     *
     * <p>小控件（开关滑块、导航滑块）的可用余量是被所在轨道定死的：
     * 开关滑块两侧只有 3dp 的空隙，导航滑块两侧只有几 dp。若沿页面板尺度的
     * 阴影（新拟态 6dp、玻璃 9dp），投影就会摊到轨道圆角之外，糊成一圈灰
     * ——用户说的"开关按钮还是有一小部分阴影影响观感"。
     *
     * <p>把余量按比例缩放后，形状本体不变（仍是 26dp/胶囊），阴影刚好填满
     * 那点空隙，既保留浮雕读感又不出界。
     */
    static float shadowScaleFor(Context c, int insetPx) {
        int full = panelInset(c);
        if (full <= 0) return 1f;
        return Math.min(1f, insetPx / (float) full);
    }

    private static int embossOffset(float density) {
        return Math.max(2, Math.round(3 * density));
    }

    private static int embossBlur(float density) {
        return Math.max(2, Math.round(3 * density));
    }

    static final class EmbossDrawable extends Drawable {
        private final Tokens.Set t;
        private final int radiusPx;
        private final float density;
        private final boolean sunken;
        /** 槽内混入色；0 = 不混，直接用 surface */
        private final int tint;
        /** 填充色混入比例（0..1），只有 tint != 0 时有意义 */
        private final float amount;
        /**
         * 凸起本体的颜色；{@code 0} = 用 {@code t.surface}。
         *
         * <p>和 {@code tint} 是**两件事**：{@code tint} 是"凹陷槽里混进一点颜色"，
         * 这个是把凸起**本体整个换成另一种颜色**（开关"开"态的强调色滑块）。
         * 共用一个字段会让语义打架，所以分开。
         */
        private final int bodyColor;

        /**
         * 形状相对 View 边界的内缩量（px）。
         *
         * <p>{@code -1} = 用默认的 {@link #embossInset}，这是绝大多数控件的正确选择：
         * 卡片/按钮的尺寸是"面板尺寸"，要让出 6dp 给外阴影。
         *
         * <p><b>为什么必须能改小：</b>凹陷（sunken）的内阴影是画在形状**内部**的
         * （见 {@code renderEmboss} 里的 {@code drawInnerShadow}），所以凹陷**根本
         * 不需要外阴影的余量**。之前一律内缩 6dp，导致小控件被压得几乎看不见 ——
         * 滑块轨道声明高 14dp，内缩 6dp×2 后只剩 **2dp** 可见（"拖动按钮有 bug"
         * 里那条几乎消失的细槽就是这个）；开关轨道 52×32 只剩 40×20。
         */
        private final int insetPx;

        /**
         * 阴影缩放倍数（1 = 原型的 {@code 3px 3px 6px} 原尺寸）。
         *
         * <p><b>为什么需要它：</b>原型的新拟态阴影是按"面板"尺度定的
         * （偏移 3px、模糊直径 6px）。卡片很大，这个量正好；但放到
         * **小控件**上就太大了 —— 开关滑块只有 26dp，而它的投影偏移 3dp +
         * 模糊半径 3dp，摊开半径接近 6dp，等于滑块自身尺寸的四分之一，
         * 于是阴影从轨道圆角外面溢出来（用户截图里滑块周围那圈"影响到观感"
         * 的灰晕）。原型这里其实写的是 `--emboss`，但 CSS 的阴影半径同样
         * 是按 box 尺度看的，26px 的盒子里 6px 模糊依然是紧凑的；
         * Android 这边因为要给阴影预留内缩，视觉上被放大了。
         *
         * <p>取值 &lt;1 时缩小偏移与模糊，让阴影"贴着"控件，不再外溢。
         */
        private final float shadowScale;
        private final ShadowCache cache = new ShadowCache();

        EmbossDrawable(Tokens.Set t, int radiusDp, boolean sunken, float density) {
            this(t, radiusDp, sunken, density, 0, 0f, 0, -1, 1f);
        }

        EmbossDrawable(Tokens.Set t, int radiusDp, boolean sunken, float density,
                       int tint, float amount) {
            this(t, radiusDp, sunken, density, tint, amount, 0, -1, 1f);
        }

        EmbossDrawable(Tokens.Set t, int radiusDp, boolean sunken, float density,
                       int tint, float amount, int insetPx) {
            this(t, radiusDp, sunken, density, tint, amount, 0, insetPx, 1f);
        }

        EmbossDrawable(Tokens.Set t, int radiusDp, boolean sunken, float density,
                       int tint, float amount, int bodyColor, int insetPx) {
            this(t, radiusDp, sunken, density, tint, amount, bodyColor, insetPx, 1f);
        }

        EmbossDrawable(Tokens.Set t, int radiusDp, boolean sunken, float density,
                       int tint, float amount, int bodyColor, int insetPx,
                       float shadowScale) {
            this.t = t;
            this.density = density;
            this.radiusPx = Math.round(radiusDp * density);
            this.sunken = sunken;
            this.tint = tint;
            this.amount = amount;
            this.bodyColor = bodyColor;
            this.insetPx = insetPx;
            this.shadowScale = shadowScale;
        }

        @Override public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            int w = Math.max(1, (int) b.width());
            int h = Math.max(1, (int) b.height());
            final int px = radiusPx;
            final Tokens.Set tt = t;
            final boolean sn = sunken;
            final float d = density;
            final int tn = tint;
            final float am = amount;
            final int ins = insetPx;
            final int bc = bodyColor;
            final float ss = shadowScale;

            // tint、bodyColor 与 inset 都要参与缓存 key，否则：
            //   * tint/bodyColor 变了会命中同一张位图、颜色不变
            //     （"关→开"看不出切换）
            //   * inset 变了会命中同一张位图、形状大小不对
            Bitmap bmp = cache.get(w, h, px, (tt.bg * 31 + tt.shadowDark * 7
                    + tt.shadowLight * 13 + (sn ? 1 : 0) + tn * 3
                    + Math.round(am * 1000) + ins * 17 + bc * 5
                    + Math.round(ss * 100)), new Painter() {
                @Override public Bitmap paint(int width, int height, int r) {
                    return renderEmboss(width, height, r, tt, sn, d, tn, am, bc, ins, ss);
                }
            });
            if (bmp != null) canvas.drawBitmap(bmp, b.left, b.top, null);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 把浮雕阴影烤进位图。
     *
     * <p>形状从边界内缩 {@link #embossInset}，给偏移和模糊留出空间，
     * 这样阴影才不会被 View 边界裁掉。
     */
    private static Bitmap renderEmboss(int w, int h, int r, Tokens.Set t,
                                       boolean sunken, float density) {
        return renderEmboss(w, h, r, t, sunken, density, 0, 0f, 0, -1);
    }

    /**
     * 把浮雕阴影烤进位图（带可选的槽内填充色）。
     *
     * <p>{@code tint}/{@code amount} 非零时，凹陷槽里的底色改用
     * {@code mix(surface, tint, amount)} —— 开关"开"态就是靠它
     * 表达"坑里有颜色"，而不是整块变蓝。
     *
     * <p>{@code bodyColor} 非零时，**凸起**本体的颜色换成它（开关"开"态的
     * 强调色滑块）；为 0 则用 {@code t.surface}。
     *
     * <p>{@code insetPx >= 0} 时用它替代默认内缩。**凹陷（sunken）应当传 0**：
     * 凹陷的内阴影画在形状内部，不需要外部余量；沿用 6dp 内缩会把小控件的
     * 可见面积吃掉大半 —— 14dp 高的滑块轨道会只剩 2dp（几乎看不见），
     * 52×32 的开关轨道会缩成 40×20（这正是"开关大小不一样"的来源之一）。
     */
    private static Bitmap renderEmboss(int w, int h, int r, Tokens.Set t,
                                       boolean sunken, float density,
                                       int tint, float amount, int bodyColor,
                                       int insetPx) {
        return renderEmboss(w, h, r, t, sunken, density, tint, amount, bodyColor,
                insetPx, 1f);
    }

    /**
     * 把浮雕阴影烤进位图（最终实现）。
     *
     * <p>{@code shadowScale} 缩放**外阴影**的偏移与模糊（1 = 原型原尺寸），
     * 给开关滑块、滑块珠子这类小控件用 —— 它们的投影按面板尺度算会溢出错
     * 所在轨道的圆角。凹陷（sunken）的内阴影不受它影响：内阴影画在形状内部，
     * 本来就不会外溢，缩放它只会让凹陷变浅、失去"坑"的读感。
     */
    private static Bitmap renderEmboss(int w, int h, int r, Tokens.Set t,
                                       boolean sunken, float density,
                                       int tint, float amount, int bodyColor,
                                       int insetPx, float shadowScale) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);

        // 原型的 3px 偏移 / 6px 模糊直径，按密度换算
        final int off = Math.max(1, Math.round(embossOffset(density) * shadowScale));
        final int blur = Math.max(1, Math.round(embossBlur(density) * shadowScale));

        // 内缩，让阴影有地方画。凹陷控件允许用更小的内缩（通常 0）。
        final int ins = (insetPx >= 0) ? insetPx : (off + blur);
        RectF shape = new RectF(ins, ins, w - ins, h - ins);
        if (shape.width() < 2 || shape.height() < 2) {
            shape = new RectF(0, 0, w, h);   // 太小就退化成画满
        }

        if (!sunken) {
            // 外部双向阴影：先画两张模糊的偏移形状，再把本体盖上去
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
            p.setColor(t.shadowDark);
            cv.drawRoundRect(new RectF(shape.left + off, shape.top + off,
                    shape.right + off, shape.bottom + off), r, r, p);
            p.setColor(t.shadowLight);
            cv.drawRoundRect(new RectF(shape.left - off, shape.top - off,
                    shape.right - off, shape.bottom - off), r, r, p);

            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setColor(bodyColor != 0 ? bodyColor : t.surface);
            cv.drawRoundRect(shape, r, r, body);
        } else {
            // 凹陷：本体填底色，内阴影另用遮罩合成。
            //
            // **这里曾经是"凹陷方案整体失效"的根源。** 旧实现用 DST_OUT
            // 擦掉 shape，注释写的是"只留内部"，但 DST_OUT 擦掉的正是遮罩上
            // 已画的形状，留下的是 shape **外部** —— 于是：
            //   ① 槽内一个像素的内阴影都没有，凹陷只剩一块平色块；
            //   ② 保留的阴影落在圆角之外，而遮罩位图是**方的**，
            //      于是把方形边界"染"了出来（徽章角上那块直角亮斑、
            //      开关轨道圆角外的白边、滑轨只剩 1px 硬线，都是它）。
            // 换成内阴影合成后两点同时消失。
            //
            // 底色用 **surface**（与卡片同色），不是 surfaceSunken。
            // 原型里所有 deboss 控件（.ico / .sw i / .seg / 滑轨槽）都是
            // `background: var(--surface)` —— 凹陷**完全**由双向内阴影表达；
            // surface-sunken 只用在 .avatar（且它不带 deboss）。填 surfaceSunken
            // 会凭空多出一块比卡片深的板子（实测 (237,239,244) vs 卡片
            // (242,244,248)），那正是红框里"方形色板"观感的另一半成因。
            Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
            body.setColor(tint != 0 && amount > 0f
                    ? mix(t.surface, tint, amount) : t.surface);
            cv.drawRoundRect(shape, r, r, body);

            // 两个方向必须分两次合成：DST_OUT 会擦掉 dst 上已有的内容，
            // 所以不能在同一张遮罩上连续画两次 —— 一次一块，画完即弃。
            //
            // 顺序对齐原型。CSS 是
            // `inset 3px 3px 6px dark, inset -3px -3px 6px light`，
            // 列表中**靠前者压在上面**，所以暗侧后画。
            Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mask);
            // 光从左上打来：凹槽的右下内壁受光（亮），左上内壁背光（暗）。
            drawInnerShadow(mc, shape, r, t.shadowLight, -off, -off, blur);
            cv.drawBitmap(mask, 0, 0, null);
            mask.eraseColor(0);
            drawInnerShadow(mc, shape, r, t.shadowDark, off, off, blur);
            cv.drawBitmap(mask, 0, 0, null);
            mask.recycle();

            // 这里原本还有一道 off/2 宽的 hairline 内描边。删掉：
            // 原型的 `.ico`/轨道只有 `--deboss`，没有 border；而实测那道
            // 描边在徽章四边留下了 3px 的硬线（像素取样 (220,222,226)，
            // 正是 argb(20,26,27,30) 叠在槽底上的结果），
            // 是"方形色块"观感的来源之一。内阴影本身已经足够定义边缘。
        }
        return out;
    }

    /**
     * 把一个方向的**内阴影**画进 {@code dst}（凹陷的核心）。
     *
     * <p>CSS 的 {@code inset dx dy blur} 等价于
     * 「形状 ∖ 平移后的形状 → 模糊 → 裁回形状」：阴影只出现在凹槽**内侧**，
     * 越靠边越深。用「先填满形状、再用模糊的平移形状 {@code DST_OUT}」实现，
     * 天然满足这个描述 ——
     * <ul>
     *   <li>填充保证槽**外**一个像素都不染（这是方形硬边的解药）；</li>
     *   <li>模糊的擦除给出柔和的**内**边缘，而不是旧实现那样只有一个硬直角。</li>
     * </ul>
     *
     * <p>因此真内阴影**不需要**给形状留内缩：阴影全部落在形状内部。
     * 调用方传 {@code insetPx = 0} 是正确的，别再退回 {@code off + blur}
     * —— 那会把 14dp 的滑轨压成 2dp。
     */
    private static void drawInnerShadow(Canvas dst, RectF shape, float r,
                                        int color, float dx, float dy, float blur) {
        if (Color.alpha(color) == 0) return;
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        dst.drawRoundRect(shape, r, r, fill);

        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
        cut.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        cut.setColor(0xFF000000);
        dst.drawRoundRect(new RectF(shape.left + dx, shape.top + dy,
                shape.right + dx, shape.bottom + dy), r, r, cut);
    }

    // ==================== 玻璃 ====================

    /** 玻璃面板：半透明叠加 + 渐变描边 + 顶部内高光。 */
    static final class GlassDrawable extends Drawable {
        private final Tokens.Set t;
        private final int radiusPx;
        private final float density;
        /** 形状相对 View 边界的内缩量（px） */
        private final int insetPx;
        /** 凹陷（槽/输入框）：不画外投影，只画内阴影 */
        private final boolean sunken;
        /** 本体颜色覆盖；{@code 0} = 用 {@code t.surface} */
        private final int bodyColor;
        /** 外阴影缩放倍数（见 {@link EmbossDrawable#shadowScale}） */
        float shadowScale = 1f;
        private final ShadowCache cache = new ShadowCache();

        GlassDrawable(Tokens.Set t, int radiusDp, float density) {
            this(t, radiusDp, density, 0, -1, false);
        }

        GlassDrawable(Tokens.Set t, int radiusDp, float density,
                      int insetPx, boolean sunken) {
            this(t, radiusDp, density, 0, insetPx, sunken);
        }

        GlassDrawable(Tokens.Set t, int radiusDp, float density,
                      int bodyColor, int insetPx, boolean sunken) {
            this.t = t;
            this.density = density;
            this.radiusPx = Math.round(radiusDp * density);
            this.insetPx = insetPx;
            this.sunken = sunken;
            this.bodyColor = bodyColor;
        }

        @Override public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            int w = Math.max(1, (int) b.width());
            int h = Math.max(1, (int) b.height());
            final Tokens.Set tt = t;
            final float d = density;
            final int ins = insetPx;
            final boolean sn = sunken;
            final int bc = bodyColor;
            final float ss = shadowScale;
            Bitmap bmp = cache.get(w, h, radiusPx,
                    tt.surface * 31 + tt.glassOuter * 7 + (tt.modal & 0xFFFFFF)
                    + ins * 17 + (sn ? 1 : 0) + bc * 5 + Math.round(ss * 100),
                    new Painter() {
                @Override public Bitmap paint(int width, int height, int r) {
                    return renderGlass(width, height, r, tt, d, bc, ins, sn, ss);
                }
            });
            if (bmp != null) canvas.drawBitmap(bmp, b.left, b.top, null);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private static Bitmap renderGlass(int w, int h, int r, Tokens.Set t, float density,
                                      int bodyColor, int insetPx, boolean sunken) {
        return renderGlass(w, h, r, t, density, bodyColor, insetPx, sunken, 1f);
    }

    private static Bitmap renderGlass(int w, int h, int r, Tokens.Set t, float density,
                                      int bodyColor, int insetPx, boolean sunken,
                                      float shadowScale) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        final int one = Math.max(1, Math.round(density));

        // 内缩一个阴影余量再画。
        //
        // **这是修「玻璃态的边边角角会有暗色阴影」的关键。**
        // 原来 shape = (0,0,w,h)，贴着 View 边界；外阴影是 BlurMaskFilter
        // 画的同一个矩形，模糊会把阴影糊到圆角**外面**去 ——
        // 圆角之外本应透出背景，结果变成一圈比背景暗的方角影子
        // （用户截图里卡片四角那几块灰暗就是它）。
        // 内缩 off+blur 后阴影有地方摊开，圆角外回到透明。
        final int off = Math.max(2, Math.round(3 * density));
        final int blur = Math.max(3, Math.round(6 * density));
        // 外阴影可缩小（小控件用）；内阴影不缩 —— 它在形状内部，缩放只会变浅。
        final int shOff = Math.max(1, Math.round(off * shadowScale));
        final int shBlur = Math.max(1, Math.round(blur * shadowScale));
        final int ins = (insetPx >= 0) ? insetPx : (off + blur);
        RectF shape = new RectF(ins, ins, w - ins, h - ins);
        if (shape.width() < 2 || shape.height() < 2) {
            shape = new RectF(0, 0, w, h);   // 太小就退化成画满
        }

        // 外阴影。**凹陷控件不画**：槽是"陷进去"的，没有外投影，
        // 画了会在槽周围多出一圈暗边（玻璃引擎下尤其明显）。
        if (!sunken && t.glassOuter != 0) {
            Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
            sh.setMaskFilter(new BlurMaskFilter(shBlur, BlurMaskFilter.Blur.NORMAL));
            sh.setColor(t.glassOuter);
            cv.drawRoundRect(shape, r, r, sh);
        }

        // 半透明本体
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(bodyColor != 0 ? bodyColor : t.surface);
        cv.drawRoundRect(shape, r, r, body);

        if (sunken) {
            // 凹陷：内阴影必须画在**本体之上**。
            //
            // 旧实现在本体之前合成，而且用的是 DST_OUT 擦掉 shape ——
            // 两个错误叠加：留下的是形状**外部**的一圈黑晕（不是内阴影），
            // 而且随后被本体盖住大半，只在圆角外露出来。用户看到的
            // "圆角控件背后垫着一块长方形浅色板"就是这圈黑晕。
            //
            // 真内阴影全部落在形状内部，不需要给形状留内缩。
            // 原型玻璃的 deboss 是 `inset 0 2px 6px`：只有竖直方向。
            Bitmap mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mask);
            drawInnerShadow(mc, shape, r, t.shadowDark, 0f, off, blur);
            cv.drawBitmap(mask, 0, 0, null);
            mask.recycle();
        }

        // 顶部内高光：1px 亮线，模拟玻璃上缘反光
        Paint hi = new Paint(Paint.ANTI_ALIAS_FLAG);
        hi.setStyle(Paint.Style.STROKE);
        hi.setStrokeWidth(one);
        hi.setShader(new LinearGradient(0, shape.top, 0, shape.bottom,
                t.glassInner, 0x00FFFFFF, Shader.TileMode.CLAMP));
        RectF in = new RectF(shape);
        in.inset(one / 2f, one / 2f);
        // 描边/高光是**沿轮廓描**的：矩形内缩了 one/2，半径也要同步减掉，
        // 否则描边的圆角比本体更紧，两套 r 并排就能看出来（用户反馈）。
        final float strokeR = Math.max(0f, r - one / 2f);
        cv.drawRoundRect(in, strokeR, strokeR, hi);

        // 描边
        Paint bd = new Paint(Paint.ANTI_ALIAS_FLAG);
        bd.setStyle(Paint.Style.STROKE);
        bd.setStrokeWidth(one);
        bd.setColor(t.glassBorder);
        cv.drawRoundRect(in, strokeR, strokeR, bd);
        return out;
    }

    // ==================== Mesh 背景 ====================

    /**
     * Mesh 渐变光斑背景 —— 玻璃引擎的招牌，也是原型 {@code --mesh} 的直译。
     * 直接用 {@link RadialGradient} 画，硬件加速友好，不需要烤位图。
     */
    static final class MeshDrawable extends Drawable {
        private final Tokens.Set t;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        MeshDrawable(Tokens.Set t) {
            this.t = t;
            p.setColor(t.bg);
        }

        @Override public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            canvas.drawRect(b, p);   // 底色
            if (t.mesh == null) return;
            float w = b.width(), h = b.height();
            // radius 是与 max(w,h) 的相对值：CSS 的 transparent 55% 指渐变半径
            float base = Math.max(w, h);
            for (Tokens.Spot s : t.mesh) {
                float cx = b.left + s.cx * w;
                float cy = b.top + s.cy * h;
                float rad = Math.max(1f, s.r * base);
                p.setShader(new RadialGradient(cx, cy, rad,
                        s.color, 0x00000000, Shader.TileMode.CLAMP));
                canvas.drawCircle(cx, cy, rad, p);
            }
            p.setShader(null);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
    }

    // ==================== 工厂 ====================

    /**
     * 按比例混合两个颜色（{@code amount=0} 全取 base，{@code =1} 全取 over）。
     *
     * <p>开关的"开"态要的是"底色里透出一点强调色"，不是一个新色号，
     * 所以按比例混合比直接指定颜色更能适应 4 套引擎×主题。
     */
    static int mix(int base, int over, float amount) {
        float a = Math.max(0f, Math.min(1f, amount));
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or_ = (over >> 16) & 0xFF, og = (over >> 8) & 0xFF, ob = over & 0xFF;
        int r = Math.round(br + (or_ - br) * a);
        int g = Math.round(bg + (og - bg) * a);
        int b = Math.round(bb + (ob - bb) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** 卡片背景：按引擎分发 */
    static Drawable card(Context c, Tokens.Set t, int radiusDp) {
        float d = c.getResources().getDisplayMetrics().density;
        if (Tokens.skin(c) == Tokens.SKIN_GLASS) return new GlassDrawable(t, radiusDp, d);
        return new EmbossDrawable(t, radiusDp, false, d);
    }

    /**
     * 凸起件：几何与阴影逻辑同 {@link #card}，只是**本体颜色可换**。
     *
     * <p>为开关滑块而加。"开"= 强调色本体，"关"= surface 本体，两者走
     * **同一条渲染路径、同一个 inset**，所以可见尺寸必然相同 —— 这正是
     * 之前"开/关大小不一样"（用户报过的「开启和关闭的大小不一样」）的根治办法：
     * 旧实现一边用 {@code solidFill}（画满 bounds）、一边用 {@code card}
     * （内缩 off+blur），两者种类不同、可见尺寸差 8dp。
     *
     * <p>{@code insetPx} 必须显式传：新拟态的外阴影余量是 {@code off+blur}，
     * 玻璃的默认余量更大，各用各的默认值会让同尺寸 View 在两套引擎下
     * 渲染出不同大小的珠子。
     */
    static Drawable raised(Context c, Tokens.Set t, int radiusDp,
                           int bodyColor, int insetPx) {
        return raised(c, t, radiusDp, bodyColor, insetPx, 1f);
    }

    /**
     * 凸起件（小规格阴影版）。
     *
     * <p>{@code shadowScale} &lt; 1 时把外阴影缩小，用于**尺寸接近阴影半径**
     * 的小控件（开关滑块 26dp、滑块珠子 32dp）：原尺寸下投影会从所在轨道的
     * 圆角外溢出去，看上去像控件周围糊了一圈灰。卡片/按钮尺寸远大于阴影，
     * 继续用 1。
     */
    static Drawable raised(Context c, Tokens.Set t, int radiusDp,
                           int bodyColor, int insetPx, float shadowScale) {
        float d = c.getResources().getDisplayMetrics().density;
        if (Tokens.skin(c) == Tokens.SKIN_GLASS) {
            GlassDrawable g = new GlassDrawable(t, radiusDp, d, bodyColor, insetPx, false);
            g.shadowScale = shadowScale;
            return g;
        }
        return new EmbossDrawable(t, radiusDp, false, d, 0, 0f, bodyColor, insetPx,
                shadowScale);
    }

    /**
     * 给任意 Drawable 套一圈 1dp 描边（形状与圆角与内层一致）。
     *
     * <p><b>这是用户明确要求的偏离。</b>原型只给 {@code .card} / {@code .modal}
     * / {@code .pill} 描边，按钮（{@code .btn-ghost} / {@code .btn-primary}）
     * 是 {@code box-shadow} 无边框。但用户在玻璃态浅色下看到分段按钮的观感
     * 不满意，明确要求"请加入描边效果" —— 所以这里按用户要求加，
     * 而不是按原型。描边只加在按钮这一层，卡片/行不受影响。
     *
     * <p>用包装而不是改两个引擎的渲染：引擎位图是**按尺寸缓存**的，
     * 给它们各加一个 stroke 分支会让缓存 key 多一维、且描边要重烤位图；
     * 描边是纯几何，实时画一圈 stroke 更省，也不需要动缓存。
     */
    static Drawable stroked(Context c, Tokens.Set t, final Drawable inner,
                            final int radiusDp, final int insetPx,
                            final int strokeColor) {
        final int rp = dp(c, radiusDp);
        final int ins = Math.max(0, insetPx);
        final float stroke = Math.max(1f, c.getResources().getDisplayMetrics().density);
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override public void draw(Canvas canvas) {
                inner.setBounds(getBounds());
                inner.draw(canvas);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(stroke);
                p.setColor(strokeColor);
                // **必须按内层形状的内缩量走**。描边要贴在"看得见的那个形状"的
                // 边缘上，而内层 Drawable 把形状从 View 边界内缩了 ins（新拟态
                // 6dp / 玻璃 9dp）。按 View 边界画会得到一圈浮在按钮外面的
                // 独立轮廓 —— 实测就是这样：按钮里外各一个圈。
                RectF b = new RectF(getBounds());
                b.inset(ins + stroke / 2f, ins + stroke / 2f);
                // **r 角必须一起缩**（用户报的「描边同步边框的 r 角」）。
                // 只缩矩形不缩半径，描边的圆角就比形状本体"更紧"：同样的
                // r 画在一个更小的矩形上，弧长比例变了，看起来就是两套 r。
                // 胶囊（r=999 会被 clamp 成半高）恰好掩盖了这个问题，
                // 所以只有非胶囊形状看得出来。正确做法是半径同步减去内缩量。
                final float rr = Math.max(0f, rp - (ins + stroke / 2f));
                canvas.drawRoundRect(b, rr, rr, p);
            }

            @Override public void setAlpha(int a) {}
            @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
            @Override public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }

    /**
     * 凹陷控件（输入框 / 滑轨槽）。
     *
     * <p><b>内缩 0</b>：凹陷的内阴影画在形状内部，不需要外部余量。
     * 之前沿用凸起的 6dp 内缩，把 14dp 高的滑轨槽压成 2dp 可见高度
     * （"拖动按钮有 bug"里那条几乎消失的细槽）；开关轨道 52×32 也被
     * 缩成 40×20，这是"开关开/关大小不一样"的来源之一。
     */
    static Drawable sunken(Context c, Tokens.Set t, int radiusDp) {
        return sunken(c, t, radiusDp, 0);
    }

    /**
     * 凹陷控件（可指定内缩）。
     *
     * <p>默认内缩 0 是对的：内阴影画在形状内部，不需要外部余量。
     * 但**按钮的按下态**要传与常态相同的内缩 —— 常态是
     * {@link #card}（内缩 off+blur），按下态若用 0 内缩，形状会比常态
     * 大出一整圈，按下时按钮"胀开"，和常态不是同一个形。
     */
    static Drawable sunken(Context c, Tokens.Set t, int radiusDp, int insetPx) {
        float d = c.getResources().getDisplayMetrics().density;
        if (Tokens.skin(c) == Tokens.SKIN_GLASS) {
            return new GlassDrawable(t, radiusDp, d, 0, insetPx, true);
        }
        return new EmbossDrawable(t, radiusDp, true, d, 0, 0f, 0, insetPx);
    }

    /**
     * 凹陷 + 淡色填充（开关的"开"态轨道）。
     *
     * <p>新拟态的开与关不能只靠"滑块在左还是在右"来表达 —— 用户反馈
     * 「不符合实际效果」。这里让轨道保持凹陷（它本来就是面板上的坑），
     * 只在开启时给槽内填一层低饱和的强调色，形成"坑里有颜色"的观感；
     * 关闭时就是纯凹槽。配合滑块的凸起/点亮，状态一眼可辨。
     *
     * <p>{@code amount} 是强调色的混入比例。取值要在**两套引擎下都够醒目**：
     * 0.20 时浅色新拟态几乎看不出（截图确认），0.62 时又和滑块糊成一个蓝胶囊。
     * 现在 0.42 + 滑块纯强调色，靠明度差把"槽"和"珠"分开。
     *
     * <p>内缩 0，理由同 {@link #sunken}。
     */
    static Drawable sunkenTinted(Context c, Tokens.Set t, int radiusDp,
                                 int tint, float amount) {
        float d = c.getResources().getDisplayMetrics().density;
        if (Tokens.skin(c) == Tokens.SKIN_GLASS) {
            // 玻璃引擎下没有"槽内填充"这个概念：玻璃是半透明的，
            // 混入的颜色会被 Mesh 透出来。用实心玻璃 + 强调色描边表达"开"。
            return new GlassDrawable(t, radiusDp, d, 0, true);
        }
        return new EmbossDrawable(t, radiusDp, true, d, tint, amount, 0);
    }

    /** 把 Mesh 背景铺到整个 Activity 根布局上 */
    static void applyAppBackground(View root, Tokens.Set t) {
        root.setBackground(new MeshDrawable(t));
    }

    /**
     * 纯色圆角填充。
     *
     * <p>用于必须"一眼看出是危险操作"的按钮 —— 原型 dangerDialog 里
     * 确认键是实心红，不是红字。浮雕/玻璃在这里都不合适：破坏性动作
     * 不该长得像普通卡片。
     */
    static Drawable solidFill(Context c, Tokens.Set t, int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(color);
        return g;
    }

    /**
     * 圆形裁剪的位图（首页 hero 卡的头像用，原型 {@code .avatar}）。
     *
     * <p>为什么需要它：原型头像是圆的（56dp 圆），直接放 Bitmap 是方的。
     * 纯 framework 下没有现成的圆形 ImageView，用 BitmapShader + DRAW 画一个。
     */
    static Drawable circleBitmap(Context c, android.graphics.Bitmap src, float sizeDp) {
        int px = dp(c, sizeDp);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                px, px, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(out);
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.BitmapShader(
                src, android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP));
        // 按短边等比放大到铺满圆形，避免非方形图被拉伸
        float scale = (float) px / Math.min(src.getWidth(), src.getHeight());
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(scale, scale);
        // 居中
        m.postTranslate((px - src.getWidth() * scale) / 2f,
                        (px - src.getHeight() * scale) / 2f);
        p.getShader().setLocalMatrix(m);
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p);
        return new android.graphics.drawable.BitmapDrawable(c.getResources(), out);
    }

    // ==================== dp 工具 ====================

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
