package com.deepseek.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;

import java.io.File;

/**
 * 图片背景：把一张用户导入的图片铺在设置页背面的 Mesh 位置。
 *
 * <h3>★ 与 {@link VideoBackgroundView} 的关系</h3>
 * 两者是**同一套铺法的两个数据源**：
 * <ul>
 *   <li>铺法数学（等比缩放 + 取哪一块）完全复用 {@link VideoFitMath} ——
 *       那段是纯 Java、已有 192 项离线断言（{@code build/vidtest}），
 *       <b>不要在这里重写一遍算比例</b>，否则图片和视频的观感会不一致，
 *       而且新代码没有测试覆盖。</li>
 *   <li>差异只在"画面从哪来"：视频是 MediaPlayer 逐帧，
 *       图片是一次解码成 Bitmap。</li>
 * </ul>
 *
 * <h3>★ 为什么用 View + onDraw 而不是 ImageView</h3>
 * 需要"裁剪框 → 等比铺满"的自定义变换矩阵，{@code ImageView} 的
 * scaleType 表达不了"先按归一化裁剪框取一块、再等比缩放到铺满"。
 * 手绘一帧的成本对静态图可以忽略（而且图片只在尺寸/裁剪变化时重绘）。
 *
 * <h3>★ 内存</h3>
 * 用户可能选一张 4000×3000 的大图。直接整张解码 = 48MB 位图，
 * 低端机直接 OOM。所以：
 * <ol>
 *   <li>先只读边界（{@code inJustDecodeBounds}）拿到原图尺寸</li>
 *   <li>按目标显示尺寸算 {@code inSampleSize}，降采样解码</li>
 * </ol>
 * 这样无论用户选多大，解码后的位图都接近屏幕尺寸。
 */
final class ImageBackgroundView extends View {

    private static final String TAG = "DSPImageBg";

    private Bitmap bitmap;
    private File source;
    /** 图片原始尺寸（用来算裁剪与铺法） */
    private int imgW, imgH;
    /** 用户裁剪框（归一化）；null = 按屏幕比例居中取 */
    private float[] cropRect;
    private int fitMode = Prefs.FIT_COVER;

    private final Matrix matrix = new Matrix();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Rect srcRect = new Rect();
    /** 上次打印过的几何签名（避免每次重绘都刷日志） */
    private String lastLogged = null;

    ImageBackgroundView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        // 背景视图不吃任何触摸：它在页面内容下面，抢事件会让设置页点不动
        setClickable(false);
        setFocusable(false);
    }

    /**
     * 换图。
     *
     * <p>同一个文件重复调用是**空操作**（只比路径）：设置页每次重建都会
     * 调它，若每次都重新解码，拖一下滑块就闪一下、还白耗 CPU。
     * 这也是 {@code VideoBackgroundView.setSource} 的同一套约定。
     */
    void setSource(File f) {
        if (f == null) {
            clear();
            return;
        }
        if (source != null && source.getAbsolutePath().equals(f.getAbsolutePath())
                && bitmap != null && !bitmap.isRecycled()) {
            return;   // 同一张图，已经在显示
        }
        clear();
        source = f;
        if (!f.isFile()) {
            Log.w(TAG, "图片背景文件不存在: " + f);
            return;
        }
        load();
        invalidate();
    }

    /** 设置裁剪框与铺法（纯几何，不重新解码） */
    void setCrop(float[] crop, int fit) {
        this.cropRect = crop;
        this.fitMode = fit;
        invalidate();
    }

    private void clear() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
        bitmap = null;
        imgW = 0;
        imgH = 0;
        source = null;
    }

    /**
     * 降采样解码。
     *
     * <p>先用 {@code inJustDecodeBounds} 只读尺寸（不分配像素内存），
     * 再按需要的倍数解码 —— 用户选 4000×3000 也不会 OOM。
     */
    private void load() {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getAbsolutePath(), o);
            imgW = o.outWidth;
            imgH = o.outHeight;
            if (imgW <= 0 || imgH <= 0) {
                Log.w(TAG, "图片解码失败（尺寸为 0）: " + source);
                return;
            }

            // 目标：解码后的图不小于屏幕（否则铺满时会糊），
            // 但也不远超屏幕（否则白占内存）。inSampleSize 只能取 2 的幂。
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int need = Math.max(1, Math.min(imgW / Math.max(1, screenW),
                                             imgH / Math.max(1, screenH)));
            BitmapFactory.Options d = new BitmapFactory.Options();
            // 屏幕是 2x/3x 密度，位图按"逻辑像素"取会糊，所以放开到 2 倍
            d.inSampleSize = Math.max(1, Integer.highestOneBit(need));
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), d);
            if (bitmap == null) {
                Log.w(TAG, "图片解码返回 null: " + source);
                return;
            }
            Log.i(TAG, "图片已解码 " + imgW + "x" + imgH
                    + " -> " + bitmap.getWidth() + "x" + bitmap.getHeight()
                    + " sample=" + d.inSampleSize);
        } catch (Throwable t) {
            // 解码失败不能崩设置页 —— 背景是装饰性的
            Log.w(TAG, "图片加载失败: " + t);
            bitmap = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || bitmap.isRecycled()) return;
        int viewW = getWidth(), viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // 用**解码后**的实际尺寸算（不是原始尺寸）：裁剪框是归一化的，
        // 两者等价，但用实际尺寸能避免下采样后取样点偏移。
        int bw = bitmap.getWidth(), bh = bitmap.getHeight();

        // 没存过裁剪框 → 按屏幕比例居中取一条，与视频背景同一套兜底
        float[] crop = cropRect;
        if (crop == null) {
            crop = VideoFitMath.defaultCrop(bw, bh, (float) viewH / viewW);
        } else {
            // 校正成目标比例再铺：保证"预览里框到的 == 屏幕上看到的"
            float[] c = new float[]{crop[0], crop[1], crop[2], crop[3]};
            VideoFitMath.conformToAspect(c, bw, bh, (float) viewH / viewW);
            crop = c;
        }

        // ★ 铺法数学委托给 VideoFitMath（纯 Java、已充分断言）。
        //
        // ★★ 但**不要**再调 toViewSpaceTransform —— 这里踩过一个真实的坑：
        //   那个函数是**专为 TextureView 写的**。TextureView 默认会把视频
        //   拉伸铺满整个视图，它的 setTransform 矩阵是**叠加在那层默认拉伸之上**的，
        //   所以要先"把缩放除以默认拉伸比"把坐标空间换回去
        //   （见 VideoFitMath.toViewSpaceTransform 的推导）。
        //
        //   而本类是**裸画位图**（canvas.drawBitmap + matrix），
        //   没有那层默认拉伸 —— 矩阵直接作用在**源像素空间**。
        //   照搬调用会让缩放被多除一次 dx/dy，实测后果是：
        //   图片按 1:1 像素画出，只铺到屏幕上方 67%（800x1600 的图铺在 1080x2400 的屏上），
        //   下面留一大片空白。
        //
        //   同一份数学在不同渲染管线下语义不同 —— 不能盲抄调用方式。
        float[] t = VideoFitMath.compute(bw, bh, viewW, viewH, fitMode, crop);

        // 源矩形 = 裁剪框映射到位图坐标
        int l = Math.round(crop[0] * bw);
        int tp = Math.round(crop[1] * bh);
        int r = Math.round(crop[2] * bw);
        int b = Math.round(crop[3] * bh);
        if (r - l < 1) r = l + 1;
        if (b - tp < 1) b = tp + 1;
        srcRect.set(l, tp, r, b);

        // t = {scaleX, scaleY, tx, ty}：源像素 × scale + t = 视图像素
        matrix.reset();
        matrix.postTranslate(-srcRect.left, -srcRect.top);   // 先把裁区左上角移到原点
        matrix.postScale(t[0], t[1]);                        // 再等比缩放到目标尺寸
        matrix.postTranslate(t[2], t[3]);                    // 再平移到视图位置
        canvas.drawBitmap(bitmap, matrix, paint);

        // ★ 诊断日志：几何算错时靠它定位，不必猜像素
        //   （与 VideoBackgroundView 的 DSPVideo 同一套做法）
        String sig = bw + "x" + bh + "/" + viewW + "x" + viewH + "/" + fitMode;
        if (!sig.equals(lastLogged)) {
            lastLogged = sig;
            Log.i(TAG, "fit=" + fitMode + " bitmap=" + bw + "x" + bh
                    + " view=" + viewW + "x" + viewH
                    + " crop=[" + crop[0] + "," + crop[1] + "," + crop[2] + "," + crop[3] + "]"
                    + " scale=" + t[0] + "," + t[1]
                    + " t=" + t[2] + "," + t[3]
                    + " src=" + srcRect.left + "," + srcRect.top
                    + "," + srcRect.right + "," + srcRect.bottom);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 离开窗口就释放位图：一整张全屏位图（约 8~16MB）不该常驻。
        // 但**保留** source，下次 attach 会重新解码 —— 见 onAttachedToWindow。
        clear();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (source != null && bitmap == null) {
            load();
            invalidate();
        }
    }

    /** 供诊断日志用 */
    String describe() {
        return "img=" + (bitmap == null ? "none"
                : bitmap.getWidth() + "x" + bitmap.getHeight())
                + " src=" + (source == null ? "none" : source.getName());
    }
}
