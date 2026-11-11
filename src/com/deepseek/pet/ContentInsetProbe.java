package com.deepseek.pet;

import android.graphics.Bitmap;
import android.os.Build;

import java.io.File;

/**
 * 从**素材**里量"内容边距"（角色可见内容在画布里的留白）—— 需要解码 + 扫 alpha，
 * 所以调用方<b>必须放在后台线程</b>（UI 线程解码一张 640×360 动图会掉帧）。
 *
 * <p>为什么要有它：贴边（{@link ContentInset}）需要"内容占画布哪一块"这个数，
 * 而它只认<b>像素</b>（alpha 包围盒），不认配置。原来只有内置动画包
 * （{@link PetAnimPack#measureClip}）算了这个数，于是
 * <b>上传图片 / 动图</b>这几种形象永远拿不到边距 ⇒ 窗口不许挂出屏外 ⇒
 * 桌宠贴着屏幕边停下来却差着一大块（2026-10-08 报障的另一半）。
 *
 * <p>量出来的东西是"**画布空间**"的（画布尺寸 + 留白比例），换算到视口空间要过
 * {@link ContentInset#of} —— 换算那部分是纯的，有 {@code build/fpstest} 断言。
 */
public final class ContentInsetProbe {

    private ContentInsetProbe() {}

    /** alpha 阈值：与动画包的边距扫描同口径（25/255 ≈ 10%） */
    public static final int ALPHA_THRESHOLD = 24;

    /** 扫描步长：隔一个像素扫一次就够（包围盒不需要逐像素精度） */
    private static final int STEP = 2;

    /** 量出来的结果：画布尺寸 + 画布空间的留白比例 */
    public static final class Measured {
        public final int bmW, bmH;
        public final float[] alpha;      // {L,R,T,B}，画布空间

        public Measured(int bmW, int bmH, float[] alpha) {
            this.bmW = bmW;
            this.bmH = bmH;
            this.alpha = alpha;
        }
    }

    /**
     * 扫 alpha 包围盒。
     *
     * @return {minX, minY, maxX, maxY}（含端点，画布像素坐标）；整张全透明或失败返回 null
     */
    public static int[] alphaBBox(Bitmap bm) {
        if (bm == null) return null;
        try {
            int w = bm.getWidth(), h = bm.getHeight();
            if (w <= 0 || h <= 0) return null;
            int minX = w, minY = h, maxX = -1, maxY = -1;
            int[] row = new int[w];
            for (int y = 0; y < h; y += STEP) {
                bm.getPixels(row, 0, w, 0, y, w, 1);
                for (int x = 0; x < w; x += STEP) {
                    int a = (row[x] >>> 24) & 0xFF;
                    if (a > ALPHA_THRESHOLD) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0 || maxY < 0) return null;
            return new int[]{minX, minY, maxX, maxY};
        } catch (Throwable t) {
            return null;
        }
    }

    /** 量一张已经在手里的位图（**不回收**它 —— 调用方还要拿它当形象画） */
    public static Measured ofBitmap(Bitmap bm) {
        if (bm == null) return null;
        int[] bb = alphaBBox(bm);
        if (bb == null) return null;
        int w = bm.getWidth(), h = bm.getHeight();
        return new Measured(w, h, new float[]{
                bb[0] / (float) w,
                (w - 1 - bb[2]) / (float) w,
                bb[1] / (float) h,
                (h - 1 - bb[3]) / (float) h
        });
    }

    /**
     * 量一个图片文件（PNG / JPG / GIF / 动态 WebP）的**第一帧**。
     *
     * @param maxPx 解码上限（超过就等比缩到这么大再扫 —— 只看留白比例，不需要原分辨率）
     * @return null = 解不出来（调用方就当"没有留白"，退回旧行为，不会崩）
     */
    public static Measured ofFile(File f, int maxPx) {
        if (f == null || !f.exists() || Build.VERSION.SDK_INT < 28) return null;
        Bitmap bm = null;
        try {
            final int cap = maxPx > 0 ? maxPx : 1080;
            android.graphics.ImageDecoder.Source src =
                    android.graphics.ImageDecoder.createSource(f);
            bm = android.graphics.ImageDecoder.decodeBitmap(src,
                    new android.graphics.ImageDecoder.OnHeaderDecodedListener() {
                        @Override public void onHeaderDecoded(
                                android.graphics.ImageDecoder dec,
                                android.graphics.ImageDecoder.ImageInfo info,
                                android.graphics.ImageDecoder.Source source) {
                            dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
                            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                            int m = Math.max(w, h);
                            if (m > cap) {
                                float r = cap / (float) m;
                                dec.setTargetSize(Math.max(1, (int) (w * r)),
                                        Math.max(1, (int) (h * r)));
                            }
                        }
                    });
            return ofBitmap(bm);
        } catch (Throwable t) {
            return null;
        } finally {
            // 这张是我们自己解的，扫完即回收（ofBitmap 已经把比例抄出来了）
            if (bm != null) {
                try { bm.recycle(); } catch (Throwable ignore) {}
            }
        }
    }
}
