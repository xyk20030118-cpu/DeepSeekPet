package com.deepseek.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * 视频「预览 + 裁剪」界面 —— **所见即所得（WYSIWYG）**。
 *
 * <h3>交互模型（第三版，前两版都不对）</h3>
 * <pre>
 *   ┌──────────────┐
 *   │ 视频（在框后面自由平移/缩放）  │
 *   │   ┌────────┐  │
 *   │   │ 取景框  │  │   ← 框**固定**，形状 == 屏幕形状
 *   │   │(屏幕比例)│  │   ← 框里看到什么，屏幕上就显示什么
 *   │   └────────┘  │
 *   └──────────────┘
 * </pre>
 * <ul>
 *   <li><b>拖动</b> = 移动**视频**（不是移动框）</li>
 *   <li><b>双指缩放</b> = 放大/缩小**视频**（以两指中点为锚）</li>
 *   <li>框本身永远不动、永远是屏幕形状 —— 所以不存在"框选了 A、出来却是 B"</li>
 * </ul>
 *
 * <h3>★ 前两版为什么错（用户连报两轮"预览和实际不一样"）</h3>
 * 第一、二版是"在整段视频上画一个框"：
 * <pre>
 *   框画在完整视频上（比如框住 7 条色带）
 *     → 最终只显示框内那块，且要**再**按屏幕比例裁一次
 *     → 屏幕上只剩 5 条
 *   ⇒ 用户："我框住的是这一段，出来的却是别的！"
 * </pre>
 * 根因是那个模型要求用户在脑子里做两次换算（框→裁→再裁）。
 * 第三版把框固定成屏幕形状，视频在框后动 —— **框内 == 屏幕**，
 * 不需要任何换算，数学上也不可能不一致。
 *
 * <h3>为什么不用第三方裁剪库</h3>
 * 本工程没有依赖管理器（手写 javac，见 STATE.md），引库要手工拆 aar、合资源。
 * 而这里只需要"一个固定矩形 + 一层遮罩 + 平移缩放"，自己画更可控。
 */
final class VideoCropView extends View {

    /** 视频首帧（可能为 null —— 解码失败时仍要能用，只是没有底图） */
    private Bitmap frame;

    /**
     * 用于**变换数学**的尺寸。
     *
     * <p>★ 这是解码后**位图的实际尺寸**，不是视频的原始分辨率。
     * 踩过的坑：{@link #decodeFrame} 会把首帧等比缩小（4K 一帧 33MB，
     * 直接用会 OOM），而我一开始仍拿 {@code videoW/videoH}（原始 1920×1080）
     * 去算缩放和平移 —— 于是"以为在按 1920 宽摆放，实际位图只有 1260 宽"，
     * 画面比预期小一圈，**取景框底部露出了黑边**（截图里那条横贯的黑带）。
     *
     * <p>裁剪框存的是**归一化**值，与分辨率无关，所以用位图尺寸算变换、
     * 用归一化值存结果，两边天然一致。
     */
    private int videoW, videoH;

    /**
     * 取景框（**固定不动**）。形状 = 屏幕形状。
     *
     * <p>这是整个控件的"窗口"：框里的内容会 1:1 铺满屏幕。
     */
    private final RectF viewport = new RectF();

    /** 视频在视图坐标系里的缩放与平移：源像素 (sx,sy) → (ox + sx*s, oy + sy*s) */
    private float s = 1f, ox = 0f, oy = 0f;

    /**
     * 是否已经"摆好过"（applyCrop 或 centerDefault 至少跑过一次）。
     *
     * <p>★ 不能拿 `s > 0f` 当判据：{@code s} 初值就是 1f，恒大于 0，
     * 于是首次布局会被误判成"已经摆好"，走"保持当前画面"那条路，
     * 而那时的画面只是初始值 —— 算出来的 crop 是错的。
     */
    private boolean positioned = false;

    /** 保证取景框被视频完全覆盖的最小缩放（否则框里会露出空白） */
    private float minScale = 1f;

    /** 目标比例（高/宽），由外面传进来 —— 必须是**内容壳**的比例 */
    private float targetAspect = 2400f / 1080f;

    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint framePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // 手势
    private float downX, downY;
    private float startOx, startOy, startS;
    private float lastSpan;

    VideoCropView(Context c) {
        super(c);
        dimPaint.setColor(0xB0000000);          // 框外压暗（比边框更暗，突出框内）
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(dp(2f));
        framePaint.setColor(0xFFFFFFFF);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(0x44FFFFFF);
        hintPaint.setColor(0xCCFFFFFF);
        hintPaint.setTextSize(dp(12f));
        hintPaint.setAntiAlias(true);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /**
     * 设置底图。
     *
     * <p>★ 变换数学一律用**位图自己的尺寸**（见 {@link #videoW} 的说明）——
     * 位图是缩过的小图，用视频原始分辨率会让画面摆错大小、露出黑边。
     * 归一化裁剪值与分辨率无关，所以这样存出来的结果依然正确。
     *
     * @param bm 视频首帧（可为 null —— 解码失败时仍要能用）
     */
    void setFrame(Bitmap bm) {
        this.frame = bm;
        if (bm != null && !bm.isRecycled()) {
            this.videoW = bm.getWidth();
            this.videoH = bm.getHeight();
        }
        // 换了底图 ⇒ 之前的变换失效，必须重新摆。
        // 不重置的话，layoutViewport 会走"保持当前画面"那条路，
        // 拿旧变换去算新位图的 crop —— 又是一次"位置不对"。
        this.positioned = false;
        requestLayout();
        invalidate();
    }

    /** 目标比例 = 内容壳的 高/宽。必须在 {@link #setCropRect} 之前调用。 */
    void setScreenAspect(float aspect) {
        if (aspect > 0f) this.targetAspect = aspect;
        requestLayout();
        invalidate();
    }

    /** 当前框内显示的源区域，归一化 0~1（这就是最终会铺满屏幕的内容） */
    float[] getCropRect() {
        if (videoW <= 0 || videoH <= 0 || s <= 0f) {
            return new float[]{0f, 0f, 1f, 1f};
        }
        float l = (viewport.left - ox) / s / videoW;
        float t = (viewport.top - oy) / s / videoH;
        float r = (viewport.right - ox) / s / videoW;
        float b = (viewport.bottom - oy) / s / videoH;
        float[] out = new float[]{clamp01(l), clamp01(t), clamp01(r), clamp01(b)};
        // ★ 诊断：把"框内实际显示的源区域"与视口尺寸打出来。
        //
        // 用途：拖动/缩放后，直接读 logcat 就能知道"框里到底是哪一块"，
        // 不必再从截图反推（我在那上面反复误判过）。
        // 同时打印视口宽高比与源区域宽高比 —— 两者不等就说明
        // "框内内容 ≠ 屏幕内容"，是本功能唯一需要保证的等式。
        String sig = java.util.Arrays.toString(out) + "/" + viewport.width()
                + "x" + viewport.height();
        if (!sig.equals(lastCropSig)) {
            lastCropSig = sig;
            float cw = (out[2] - out[0]) * videoW;
            float ch = (out[3] - out[1]) * videoH;
            float cropAspect = ch / Math.max(cw, 1f);
            float viewAspect = viewport.height() / Math.max(viewport.width(), 1f);
            android.util.Log.i("DSPCrop", "框内源区域=" + java.util.Arrays.toString(out)
                    + " 源像素=" + cw + "x" + ch
                    + " 可见源宽=" + cw
                    + " | 框比例=" + viewAspect + " 内容比例=" + cropAspect
                    + (Math.abs(viewAspect - cropAspect) < 0.02f ? " OK" : " !! 不一致")
                    + " scale=" + s + " ox=" + ox);
        }
        return out;
    }

    /** 上一次打印过的框内区域签名（见 getCropRect 的诊断日志） */
    private String lastCropSig;

    /**
     * 用已保存的裁剪框恢复（{@code null} = 没裁过 → 居中、尽可能大）。
     *
     * <p>把"框内显示的源区域"翻译成"视频该怎么摆"：
     * 让 {@code crop} 那块**正好落在取景框上**。
     *
     * <p>★ 可以在布局**之前**调用：那时 {@code viewport} 还是空的，
     * 算不了变换，所以先把值存进 {@link #pendingCrop}，
     * 等 {@code onSizeChanged} → {@link #layoutViewport} 时再应用。
     *
     * <p>踩过的坑：原来这里直接 `return`（因为没有 viewport 就什么都不做），
     * 而 {@code setCropRect} 恰恰是在 {@code setView} 之后、**布局之前**
     * 被调用的 —— 于是**存好的裁剪框被整个丢掉**，
     * {@code layoutViewport} 里"保留当前 crop"那条路拿到的是默认居中值。
     * 表现就是"预览里选的是 A 段，背景显示的是 B 段"（实测偏差约 2 条色带）。
     */
    void setCropRect(float[] r) {
        if (r != null && r.length == 4 && r[0] < r[2] && r[1] < r[3]) {
            pendingCrop = new float[]{r[0], r[1], r[2], r[3]};
        } else {
            pendingCrop = null;      // 没裁过 → 居中默认
        }
        if (viewport.width() > 0f) {
            applyPending();
            invalidate();
        }
        // viewport 还没有尺寸时什么都不做 —— 等 layoutViewport 消费 pendingCrop
    }

    /** 待应用的裁剪值（布局前调用 setCropRect 时暂存于此） */
    private float[] pendingCrop;

    /** 把 {@link #pendingCrop}（或默认居中）落到变换上 */
    private void applyPending() {
        if (pendingCrop != null) {
            applyCrop(pendingCrop);
        } else {
            centerDefault();
        }
    }

    /**
     * 让 crop 覆盖的区域**精确**落在取景框上。
     *
     * <h3>★ 为什么要先 conform 一次</h3>
     * 取景框是固定的屏幕形状（比例 = targetAspect）。若 crop 的比例与它
     * **不完全一致**，而这里又用 {@code max()} 去铺满，就会出现：
     * <pre>
     *   框里看到 576px 宽的内容（3 条色带）
     *     → 实际只记录 498px（2.6 条）—— 因为 max() 又裁掉了一点
     *   ⇒ 用户："框里有三条，实际只有两条半"；而且每开一次框就缩一点
     * </pre>
     * 先 {@link VideoFitMath#conformToAspect} 把 crop 校正到与取景框同比例，
     * 此时两个方向的缩放**相等**，{@code max()} 不再多裁，
     * <b>框内显示的内容 == 记录下来的 crop</b>，一一对应。
     */
    private void applyCrop(float[] r) {
        if (videoW <= 0 || videoH <= 0) return;

        // 归一化坐标下的"目标比例"：像素高/宽 == targetAspect
        //   (ch*videoH)/(cw*videoW) == targetAspect
        // conformToAspect 内部就是按这个式子校的。
        float[] c = new float[]{r[0], r[1], r[2], r[3]};
        VideoFitMath.conformToAspect(c, videoW, videoH, targetAspect);

        float cw = (c[2] - c[0]) * videoW;
        float ch = (c[3] - c[1]) * videoH;
        if (cw <= 0f || ch <= 0f) {
            centerDefault();
            return;
        }
        // 比例已一致 ⇒ 两个方向的缩放相同，不会多裁
        s = Math.max(viewport.width() / cw, viewport.height() / ch);
        float cx = (c[0] + c[2]) / 2f * videoW;
        float cy = (c[1] + c[3]) / 2f * videoH;
        ox = viewport.centerX() - cx * s;
        oy = viewport.centerY() - cy * s;
        clampTransform();
        // 注意：**不要**把 c 写回 pendingCrop。
        // 校正后 c 被精确摆到了取景框上，所以 getCropRect() 反算出来
        // 就等于 c —— 真状态由 getCropRect() 表达即可。
        // 若写回 pendingCrop，用户拖动画面后它仍是旧值，
        // 下一次布局会把画面**弹回**拖动前的位置。
        positioned = true;
    }

    /** 默认：居中，缩放到刚好覆盖取景框（此时框内 = 视频中间那条） */
    private void centerDefault() {
        if (videoW <= 0 || videoH <= 0) return;
        applyCrop(VideoFitMath.defaultCrop(videoW, videoH, targetAspect));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutViewport(w, h);
    }

    /**
     * 算出固定的取景框，并按当前变换（或默认）摆好视频。
     *
     * <p>★ 必须**幂等**：弹窗会经过不止一次布局（先按内容量一次、
     * 套进 ScrollView 后再量一次，尺寸会变）。
     * 这里每次都从"当前 crop"反推变换，所以量多少次结果都一样。
     */
    private void layoutViewport(int w, int h) {
        if (w <= 0 || h <= 0) return;

        // 取景框：在视图里取"屏幕形状、尽可能大"的矩形
        float fw = w;
        float fh = fw * targetAspect;
        if (fh > h) {
            fh = h;
            fw = fh / targetAspect;
        }
        float left = (w - fw) / 2f;
        float top = (h - fh) / 2f;
        viewport.set(left, top, left + fw, top + fh);

        if (videoW > 0 && videoH > 0) {
            // ★ 优先级：pendingCrop（用户存的/刚设的）> 保持当前画面 > 默认居中
            //
            // 为什么 pendingCrop 优先：`setCropRect` 是在**布局之前**被调用的
            // （MainActivity 先 setView 再布局），那时算不了变换，
            // 所以值暂存在 pendingCrop；这里必须**先消费它**，
            // 否则会被"保持当前画面"覆盖掉 —— 而首次布局时当前画面
            // 只是初始值（s=1），算出来的 crop 是错的。
            // 这正是"预览选了 A 段、背景显示 B 段"的根因。
            //
            // 顺序也要紧：`minScale` 必须先按新 viewport 更新，
            // 否则 applyCrop 里的 clampTransform 会用旧的 minScale 夹，
            // 把画面顶到错误位置。
            minScale = Math.max(viewport.width() / videoW,
                    viewport.height() / videoH);

            if (pendingCrop != null) {
                float[] c = pendingCrop;
                pendingCrop = null;      // 只消费一次
                applyCrop(c);
            } else if (positioned) {
                // 已经摆好过（布局尺寸变了，比如套进 ScrollView 后重新测量）
                // → 保持框里显示的还是同一块画面
                applyCrop(getCropRect());
            } else {
                centerDefault();
            }
        }
        invalidate();
    }

    /** 保证取景框被视频完全覆盖（框里永远不出现空白/黑边） */
    private void clampTransform() {
        if (videoW <= 0 || videoH <= 0) return;
        if (s < minScale) s = minScale;
        float vw = videoW * s, vh = videoH * s;
        // 视频必须完全盖住 viewport：
        //   左边界 ≤ 框左，右边界 ≥ 框右（宽不够时居中）
        if (vw <= viewport.width()) {
            ox = viewport.centerX() - vw / 2f;
        } else {
            if (ox > viewport.left) ox = viewport.left;
            if (ox + vw < viewport.right) ox = viewport.right - vw;
        }
        if (vh <= viewport.height()) {
            oy = viewport.centerY() - vh / 2f;
        } else {
            if (oy > viewport.top) oy = viewport.top;
            if (oy + vh < viewport.bottom) oy = viewport.bottom - vh;
        }
    }

    @Override protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        cv.drawColor(0xFF101014);

        // ① 视频（按当前变换画；超出部分自然被视图裁掉）
        if (frame != null && !frame.isRecycled()) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setScale(s, s);
            m.postTranslate(ox, oy);
            cv.drawBitmap(frame, m, null);
        }

        // ② 框外压暗：四条矩形拼出"整屏减框"（比 clipPath 更稳）
        float l = viewport.left, t = viewport.top;
        float r = viewport.right, b = viewport.bottom;
        cv.drawRect(0, 0, getWidth(), t, dimPaint);
        cv.drawRect(0, b, getWidth(), getHeight(), dimPaint);
        cv.drawRect(0, t, l, b, dimPaint);
        cv.drawRect(r, t, getWidth(), b, dimPaint);

        // ③ 三分构图线（帮助判断构图，也是"这里是取景框"的视觉暗示）
        float w3 = (r - l) / 3f, h3 = (b - t) / 3f;
        for (int i = 1; i <= 2; i++) {
            cv.drawLine(l + w3 * i, t, l + w3 * i, b, gridPaint);
            cv.drawLine(l, t + h3 * i, r, t + h3 * i, gridPaint);
        }

        // ④ 框边
        cv.drawRect(l, t, r, b, framePaint);

        // ⑤ 提示（画在框外的暗区上，不遮画面）
        cv.drawText("框内即屏幕效果", dp(10f), t - dp(8f), hintPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX(); downY = e.getY();
                startOx = ox; startOy = oy; startS = s;
                lastSpan = 0f;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() >= 2) {
                    lastSpan = span(e);
                    startS = s;
                    startOx = ox; startOy = oy;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (e.getPointerCount() >= 2) {
                    float sp = span(e);
                    if (lastSpan > 0f && sp > 0f) {
                        // 以两指中点为锚缩放：手底下的画面不动，跟手
                        float mx = (e.getX(0) + e.getX(1)) / 2f;
                        float my = (e.getY(0) + e.getY(1)) / 2f;
                        float ns = startS * (sp / lastSpan);
                        if (ns < minScale) ns = minScale;
                        // 保持锚点下的源坐标不变
                        float sx = (mx - ox) / s;
                        float sy = (my - oy) / s;
                        s = ns;
                        ox = mx - sx * s;
                        oy = my - sy * s;
                        clampTransform();
                        invalidate();
                    }
                } else {
                    ox = startOx + (e.getX() - downX);
                    oy = startOy + (e.getY() - downY);
                    clampTransform();
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 抬掉一指：退化成拖动，以剩下那指重新起算，
                // 否则松手瞬间画面会"跳"一下
                startOx = ox; startOy = oy;
                downX = e.getX(); downY = e.getY();
                lastSpan = 0f;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastSpan = 0f;
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    private static float span(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1);
        float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * 把视频首帧解码出来（等比缩小，避免大图占内存）。
     *
     * <p>用 {@code MediaMetadataRetriever} 而不是 MediaPlayer：我们只要**一帧**
     * 静态图，而 MediaPlayer 是流式播放器，拿首帧还得等 prepared 回调。
     */
    static Bitmap decodeFrame(String path, int maxW, int maxH) {
        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(path);
            Bitmap bm = mmr.getFrameAtTime(0,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bm == null) return null;

            // ★ 显式按旋转标记摆正，**不假设**平台已经处理过。
            // getFrameAtTime 返回的位图是否已应用 rotate 标记，在各版本/各
            // 编码上并不一致（横拍竖拍的手机视频尤其容易踩到）。
            // 不摆正的后果：预览是横的、实际背景是竖的，用户又会看到
            // "预览和实际不一样"。自己转一次是确定的。
            String rs = null;
            try {
                rs = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            } catch (Throwable ignore) {}
            int rot = 0;
            if (rs != null) {
                try { rot = Integer.parseInt(rs.trim()); } catch (Throwable ignore) {}
            }
            if (rot == 90 || rot == 270) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                Bitmap rotated = Bitmap.createBitmap(bm, 0, 0,
                        bm.getWidth(), bm.getHeight(), m, true);
                if (rotated != bm) bm.recycle();
                bm = rotated;
            }

            float sc = Math.min((float) maxW / bm.getWidth(),
                    (float) maxH / bm.getHeight());
            if (sc < 1f) {
                Bitmap small = Bitmap.createScaledBitmap(bm,
                        Math.max(1, (int) (bm.getWidth() * sc)),
                        Math.max(1, (int) (bm.getHeight() * sc)), true);
                if (small != bm) bm.recycle();
                bm = small;
            }
            return bm;
        } catch (Throwable t) {
            return null;   // 解码失败不该让裁剪界面打不开
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignore) {}
            }
        }
    }
}