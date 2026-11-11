package com.deepseek.pet;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;

/**
 * App 界面背景视频：循环播放用户选的一段视频，铺在页面最底层。
 *
 * <h3>为什么用 {@link TextureView} 而不是 {@link android.view.SurfaceView}</h3>
 * SurfaceView 在 API 24+ 上虽然是独立图层、合成开销低，但它是**独立于 View 树
 * 合成的**：它不吃 View 的 alpha、不吃 View 的变换，而且 z 序由 Surface 系统
 * 决定 —— 想在上面叠一层渐变/压暗遮罩、或让整块背景跟着页面翻页动画一起淡入，
 * 都会失效。
 *
 * <p>本项目的页面切换动画（{@code UiFactory.pageSlide/pageFade}）作用在
 * **内容区**上，而背景要**保持静止**；但「主题切换」「设置页重建」时又需要
 * 让整块背景参与淡入。TextureView 作为普通 View 参与合成，这些都能成立。
 * 代价是要多一次 GPU 拷贝 —— 对一块「本来就静止」的背景来说可以接受。
 *
 * <h3>为什么用 TextureView + MediaPlayer 而不是 ExoPlayer</h3>
 * 本工程**不打依赖**（手工 javac + aapt2 组装，没有 Gradle/AGP，见 STATE.md
 * 第二节），只编译 {@code src/} 下的源码。引入 ExoPlayer 就要把若干 aar 里的
 * classes.jar 拆出来喂给 javac，并手工合并资源与清单 —— 为一段循环背景视频
 * 引入这套构建复杂度不划算。MediaPlayer 的 loop 足够。
 *
 * <h3>生命周期</h3>
 * 视频**只在页面可见时播放**：{@link #onAttachedToWindow} 起播、
 * {@link #onDetachedFromWindow} 释放。设置页每次重建都会换掉整棵 View 树
 * （{@code setContentView} 重新调用），如果不在 detach 时释放，MediaPlayer
 * 会随着每次点开关而泄漏一个实例 —— 点几十次设置就崩了。
 */
final class VideoBackgroundView extends TextureView
        implements TextureView.SurfaceTextureListener, MediaPlayer.OnPreparedListener {

    private MediaPlayer player;
    private Surface surface;
    private File source;
    private boolean sound = false;
    /** 记录「已经起播」，避免重复 prepare 同一个文件 */
    private boolean prepared;

    /**
     * 续播位置（毫秒）。
     *
     * <p><b>为什么需要它：</b>设置页每点一次开关就 {@code setContentView} 重建整棵树，
     * 视频视图会被 detach 再 attach —— 这必然销毁并重建 SurfaceTexture，
     * 于是 MediaPlayer 也得重来一遍。不做处理的话，用户每点一个开关，
     * 背景视频就**从第一帧重新开始**，闪一下、并且永远只看到开头几秒。
     *
     * <p>所以释放前记下进度，重新 prepare 后 seek 回去。位置能跨重建保留，
     * 靠的是**视图实例被 Activity 缓存复用**（见 MainActivity.wrapWithVideoBackground）——
     * 若每次都 new 一个新的，这个字段也随新实例归零，等于没做。
     */
    private int resumePos = 0;

    /**
     * 视频原始宽高（已按旋转元数据校正过，见 {@link #readRotation}）。
     *
     * <p>用来算"铺满屏幕且不变形"的变换 —— 见 {@link #applyAspect}。
     */
    private int videoW = 0, videoH = 0;

    /**
     * 视频的旋转元数据（度，0/90/180/270）。
     *
     * <p><b>为什么必须读它：</b>手机拍的视频常常是"横向编码 + 旋转标记"
     * （竖着拿手机拍，但码流本身是 1920×1080，只写了个 rotate=90）。
     * {@link MediaPlayer#getVideoWidth()} 返回的是**未旋转**的尺寸，
     * 直接拿它算宽高比，遇到这类视频会把宽高算反 —— 结果是画面被拉成
     * 又高又窄，正是最容易被当成"视频背景坏了"的那种现象。
     */
    private int rotation = 0;

    /**
     * 当前正在播放的**内容指纹**（大小 + 修改时间），在 {@link #setSource} 里
     * 随文件一起记下来。
     *
     * <p><b>为什么必须单独存这两个数，而不能在比较时现读：</b>
     * {@code Prefs.videoBgFile()} 永远返回**同一个路径**
     * （{@code filesDir/app_bg_video.mp4}），所以 {@code source} 和传入的 {@code f}
     * 指向的是同一个文件 —— 在比较时调 {@code source.length()} 读到的是
     * **磁盘上的当前值**，而不是"当初开始播时的值"。用户换了视频之后，
     * 两个 length 依然相等，于是被判成"同一个视频、不用重载"，
     * 结果新选的片永远不显示、播放器一直放着旧内容
     * （实测现象：换成纯色循环片后，屏幕上仍是上一段的彩色竖条）。
     *
     * <p>把指纹在 {@code setSource} 里存成字段，比较的才是
     * 「现在播的还是不是当初那一段」，而不是「这个路径当前指向什么」。
     */
    private long sourceStamp = -1L;

    /**
     * 填充方式（{@code Prefs.FIT_*}），以及用户裁出的归一化矩形
     * （{@code [left, top, right, bottom]}，null = 不裁）。
     *
     * <p>这两个值由 {@link #setFit} 从外面推进来，而不是在这里读 Prefs ——
     * 视图不该依赖 Prefs（裁剪预览界面里也复用一个同类的显示逻辑，
     * 但那时并没有"已保存的 pref"可读）。
     */
    private int fitMode = Prefs.FIT_CONTAIN;
    private float[] cropRect = null;

    public VideoBackgroundView(Context c) {
        super(c);
        init();
    }

    public VideoBackgroundView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        setSurfaceTextureListener(this);
        // 不设置 keepScreenOn —— 背景视频不该让屏幕常亮。
    }

    /**
     * 设置视频源并开始播放。
     *
     * <p>可以在 attach 之前或之后调用：{@link TextureView} 的 surface 是
     * 异步就绪的，两边都到齐了才真正起播（见 {@link #maybeStart}）。
     */
    void setSource(File f, boolean withSound) {
        // ★★ 判"是不是同一个视频"**不能只看路径**。
        //
        // 所有背景视频都存成同一个文件名（app_bg_video.mp4，见
        // Prefs.videoBgFile），所以用户「更换背景视频」时路径**完全没变**。
        // 更要命的是：{@code source} 和 {@code f} 是同一个 File 对象，
        // 若在这里现读 {@code source.length()}，拿到的是**磁盘当前值** ——
        // 换完视频后两边必然相等，于是被判成"没换、不用重载"，
        // 新视频永远不显示（实测：换成纯色循环片，屏幕上还是上一段的竖条）。
        //
        // 正确做法：用**开始播那一刻记下的指纹**比较
        // （见 {@link #sourceStamp}）。
        long stamp = (f == null ? -1L : (f.length() * 31L + f.lastModified()));
        File s = this.source;
        boolean sameSource = (s != null && f != null
                && s.getAbsolutePath().equals(f.getAbsolutePath())
                && sourceStamp == stamp);

        // ★★ 同源且播放器还活着 → **什么都不重建**。
        //
        // 这是用户反馈「切换页面的时候会闪、背景不加载」的根治。
        //
        // 机理：设置页每次重建都会调到这里，而这里原先**无条件**
        // releasePlayer() —— 于是每切一次页面/点一个开关，视频就重新
        // prepareAsync 解码一次，解码完成前画面是**黑的**（就是那一"闪"）。
        // 长视频解码更慢，于是看起来像"背景根本没加载出来"。
        //
        // 同一个文件本来就没有重新解码的理由：播放器继续放着就是了。
        // 只有音量可能变，就地 setVolume 即可。
        if (sameSource && player != null) {
            setSound(withSound);
            // 尺寸/变换可能因为布局变化而过期，补算一次（纯变换，不重解码）。
            applyAspect();
            return;
        }

        // ★ 只放播放器，**不要**放 surface。surface 是 TextureView 给的，
        // 它的 onSurfaceTextureAvailable 只会回调一次；在这里把 surface 放掉，
        // 之后换了新视频就再也拿不回来（不会再触发 available），画面永远不出现。
        // 只有在纹理真的销毁/视图 detach 时才连 surface 一起放。
        releasePlayer();
        // 换了内容就必须把续播位置归零：否则会把**新**视频 seek 到
        // 上一段视频的进度上（比如上一段放到 40 秒、新片段只有 10 秒，
        // 结果一开播就已经接近结尾）。内容相同才保留位置。
        if (!sameSource) resumePos = 0;
        this.source = f;
        this.sourceStamp = stamp;   // 与 source 一起记，供下次比较（见字段注释）
        this.sound = withSound;
        // 换了源就重读旋转标记（见 readRotation 的说明）。
        if (!sameSource) readRotation();
        maybeStart();
    }

    /** 背景视频默认静音；开启声音会盖住桌宠的语音朗读，所以默认关 */
    void setSound(boolean withSound) {
        this.sound = withSound;
        if (player != null) {
            try {
                player.setVolume(withSound ? 1f : 0f, withSound ? 1f : 0f);
            } catch (Throwable ignore) {}
        }
    }

    void stopPlayback() {
        releasePlayer();
    }

    // ==================== SurfaceTextureListener ====================

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        // 先放掉可能还握着旧 surface 的播放器，再换新 surface。
        // 顺序反了的话，旧播放器仍指向已释放的 surface，继续解码会崩在原生层。
        releasePlayer();
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignore) {}
        }
        surface = new Surface(st);
        maybeStart();
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        // 尺寸变了（旋转屏幕、或首次布局完成）宽高比要重算，否则画面会被拉伸。
        applyAspect();
    }

    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        // 返回 true = 由我们负责释放 SurfaceTexture；这里先把自己的 Surface 放掉，
        // 否则 MediaPlayer 还握着它，纹理销毁后继续解码会崩。
        releaseAll();
        return true;
    }

    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}

    @Override public void onPrepared(MediaPlayer mp) {
        // ★ 必须确认还是**当前**这个播放器：prepareAsync 是异步的，
        // 期间用户可能又选了一个视频（setSource 会 releasePlayer 并新建）。
        // 若此时旧回调才回来，不加判断就会去 start 一个已被 release 的实例，
        // 并且 catch 里会把**新的**那个播放器一起释放掉。
        if (mp != player) return;
        prepared = true;
        try {
            // 记下真实尺寸 —— 必须在 onPrepared 之后才有效。
            videoW = mp.getVideoWidth();
            videoH = mp.getVideoHeight();
            applyAspect();
            // 续播：重建整棵树后从上次的位置接着放，而不是回到第一帧。
            if (resumePos > 0) {
                mp.seekTo(resumePos);
            }
            // ★ 尊重"希望暂停"的意愿：onPause 可能发生在 prepareAsync
            //   完成**之前**，那时 pause 无从谈起。若不在这里判断，
            //   这个 start() 会把用户已经离开的页面又拉起来解码
            //   （实测按 HOME 后仍占 18.2% CPU）。
            if (wantedPaused) {
                mp.pause();
                // pause 后仍需定位到续播点，否则恢复时会从 0 开始
                if (resumePos > 0) mp.seekTo(resumePos);
            } else {
                mp.start();
            }
        } catch (Throwable t) {
            if (mp == player) releasePlayer();
        }
    }

    // ==================== 内部 ====================

    /**
     * 按当前填充方式把视频画面铺到视图上。
     *
     * <p><b>为什么必须做：</b>{@link TextureView} 默认把画面**拉伸**到视图大小。
     * 手机屏是 1080×2400（0.45），而常见视频是 16:9（1.78）—— 直接铺满会把
     * 画面横向压扁成原来的四分之一，人脸都认不出来。这是"视频背景"最典型的
     * 翻车方式。
     *
     * <p><b>★ 不再死写 center-crop。</b>原先只有"取较大缩放 → 短边铺满、
     * 长边裁掉"这一种做法，用户反馈「视频导入之后无法正常显示，被裁切了」——
     * 横屏视频当竖屏背景时左右会被切掉一大截，而且用户**不知道被裁的是哪部分**。
     * 现在按 {@link #fitMode} 分三种：
     * <ul>
     *   <li>{@code FIT_CONTAIN}（默认）—— 完整显示、比例不变、边上留黑。
     *       取 {@code min} 缩放，保证整个画面都在视图内。</li>
     *   <li>{@code FIT_COVER} —— 铺满、比例不变、超出部分裁掉。取 {@code max}。</li>
     *   <li>{@code FIT_STRETCH} —— 拉伸铺满，不保持比例（会变形，但没黑边）。</li>
     * </ul>
     *
     * <p><b>裁剪框怎么参与：</b>裁剪框是"视频坐标系里的一个矩形"
     * （归一化 {@link #cropRect}）。做法是让**框**去适配目标区域，
     * 于是框外的内容自然落在视图之外被裁掉。这样三种模式共用同一套
     * "先取出框、再决定框怎么铺"的逻辑，不必各写一遍。
     *
     * <p>旋转元数据（{@link #rotation}）要参与计算：手机竖拍的视频常是
     * "横向编码 + rotate 标记"，{@code getVideoWidth()} 拿到的是未旋转尺寸，
     * 拿它算比例会把宽高比算反。
     */
    private void applyAspect() {
        if (videoW <= 0 || videoH <= 0) return;
        int vw = videoW, vh = videoH;
        if (rotation == 90 || rotation == 270) {
            // 旋转 90/270 度 = 宽高互换
            vw = videoH;
            vh = videoW;
        }
        int viewW = getWidth(), viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // ★ 没存过裁剪框时，**不能**按"整段视频"处理 ——
        // 用户要的是「按屏幕大小纵向裁切，截取视频中的一段就好」。
        // 所以在渲染层也兜一次默认：按屏幕比例取居中的一条竖条。
        //
        // 为什么这里要兜而不是只靠裁剪界面写入：
        //   ① 老版本升级上来的用户 prefs 里没有 crop；
        //   ② 万一某个入口漏了写 crop，也不该退化成一屏黑边。
        // 用屏幕比例（而不是视图比例）保持一致：视图是铺满整个窗口的，
        // 两者数值上相同，但语义上"屏幕比例"才是用户看到的那块。
        float[] crop = cropRect;
        if (crop == null) {
            float screenAspect = (float) viewH / viewW;
            crop = VideoFitMath.defaultCrop(vw, vh, screenAspect);
        } else {
            // ★ 把裁剪框校正成**目标比例**再渲染。
            //
            // 为什么渲染层也要做这一步（而不是只靠裁剪界面写入正确的值）：
            //   ① 老版本存下来的 crop 是自由比例的（那时还没锁比例），
            //      若照原样渲染，铺满时只显示框中心 ⇒ 用户看到的和
            //      当初框选的不是同一块；
            //   ② 裁剪界面之外也可能有人写这个 pref。
            // 校正后铺满的缩放两轴相等 ⇒ 框内内容 == 屏幕内容，
            // 这是"预览与实景一致"的**数学保证**（见 VideoFitMath.conformToAspect）。
            //
            // ★ 目标比例用 viewW/viewH（即本视图自己的比例），
            // 因为视频就是铺在这个视图上的；而裁剪界面传进来的比例
            // 必须与之相同（MainActivity 取的是同一个内容壳的尺寸）。
            // 用临时数组，不改 cropRect 字段本身 —— 字段保存"用户存的原始值"。
            float screenAspect = (float) viewH / viewW;
            float[] c = new float[]{crop[0], crop[1], crop[2], crop[3]};
            VideoFitMath.conformToAspect(c, vw, vh, screenAspect);
            crop = c;
        }

        // 数学全部委托给 VideoFitMath —— 那里是纯 Java（不 import android），
        // 所以能被 build/vidtest 离线断言。这段逻辑以前写在这里，
        // 结果"铺法算对没有"只能靠肉眼看截图，而它恰恰出过 bug
        // （用户报的"被裁切了"）。
        float[] t = VideoFitMath.compute(vw, vh, viewW, viewH, fitMode, crop);
        // ★ 必须换算到**视图空间**再喂给 setTransform ——
        // TextureView 默认把视频拉伸铺满视图，矩阵是叠在那之上的。
        // 直接把源像素空间的 scale/offset 传进去会叠两层缩放，画面整体偏移
        // （实测：屏幕左边缘显示的是第 7 条色带，而意图是第 4 条）。
        // 推导与实测对照见 VideoFitMath.toViewSpaceTransform 的注释。
        float[] vt = VideoFitMath.toViewSpaceTransform(t, vw, vh, viewW, viewH);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(vt[0], vt[1]);
        m.postTranslate(vt[2], vt[3]);
        setTransform(m);

        // ★ 诊断日志：把"算出来的变换"和"算它的输入"打出来。
        //
        // 为什么要留这一行：这个功能的 bug 全都出在"几何算错"上，
        // 而它在设备上**没法打断点**，只能靠截图反推 ——
        // 我为此反复测量、反复误判（把卡片边缘、压暗外圈、状态栏文字
        // 都当成过视频画面）。有了这行，`adb logcat -s DSPVideo` 就能
        // 直接看到权威数值，不必猜像素。
        //
        // 只在值变化时打印，避免每次布局都刷屏。
        String sig = vw + "x" + vh + "/" + viewW + "x" + viewH
                + "/" + fitMode + "/" + java.util.Arrays.toString(crop);
        if (!sig.equals(lastLogged)) {
            lastLogged = sig;
            // 同时打印"换算后的矩阵"和"它对应的可见源区间"——
            // 后者才是能直接和截图对照的量。
            float rx = (float) viewW / vw;
            float srcL = (0f - vt[2]) / (vt[0] * rx);
            float srcR = (viewW - vt[2]) / (vt[0] * rx);
            android.util.Log.i("DSPVideo", "fit=" + fitMode
                    + " crop=" + java.util.Arrays.toString(crop)
                    + " video=" + vw + "x" + vh
                    + " view=" + viewW + "x" + viewH
                    + " => 源空间 scale=" + t[0] + " tx=" + t[2]
                    + " | 视图空间 matrix=" + vt[0] + "," + vt[2]
                    + " | 可见源 x=" + srcL + ".." + srcR);
        }
    }

    /** 上一次打印过的变换签名（见 applyAspect 的诊断日志） */
    private String lastLogged;

    /**
     * 设置填充方式与裁剪区域（{@code null} = 不裁），立即重算变换。
     *
     * <p>这是纯几何变换 —— **不重新解码、不重建播放器**，所以设置页里
     * 调一下就能实时看到效果，也不会打断播放进度。
     */
    void setFit(int fit, float[] crop) {
        this.fitMode = fit;
        this.cropRect = crop;
        applyAspect();
    }

    /**
     * 读视频的旋转元数据。
     *
     * <p>只有 API 17+ 才有 {@code MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION}，
     * 而 minSdk 是 24，所以直接读；但整段仍包在 try 里 —— 元数据解析依赖
     * 具体封装格式，坏文件会抛异常，而背景视频不该把设置页带崩。
     */
    private void readRotation() {
        rotation = 0;
        if (source == null || !source.exists()) return;
        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(source.getAbsolutePath());
            String r = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (r != null) rotation = Integer.parseInt(r.trim());
        } catch (Throwable ignore) {
            rotation = 0;
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Throwable ignore) {}
            }
        }
    }

    /** surface 与 source 都就绪时才 prepare —— 任一先到都能正确起播 */
    private void maybeStart() {
        if (surface == null || source == null || prepared) return;
        if (!source.exists()) return;
        try {
            player = new MediaPlayer();
            player.setSurface(surface);
            // 背景视频**必须**循环：播完停住会让整块背景突然变黑。
            player.setLooping(true);
            // 静音播放不加 AudioAttributes 的 USAGE_MEDIA，避免进入媒体音量通道
            // （会跟桌宠 TTS 抢音量、也会在锁屏显示媒体控制条）。
            player.setVolume(sound ? 1f : 0f, sound ? 1f : 0f);
            player.setOnPreparedListener(this);
            player.setDataSource(source.getAbsolutePath());
            player.prepareAsync();
        } catch (Throwable t) {
            // 背景是装饰性的：解码失败绝不能把整个设置页带崩。
            releasePlayer();
        }
    }

    /**
     * 暂停 / 恢复播放。
     *
     * <h3>★ 这是实测出来的最大优化点</h3>
     * 熄屏后桌宠**完全不可见**，但实测 RenderThread 仍占 8.5%、进程合计 12.5%
     * —— 一晚上(8 小时)白白烧电。视频背景是发热的主要来源
     * （开 19.2% / 关 0.0%，实测对照）。
     *
     * <p>用 {@code pause()} 而不是释放播放器：暂停保留已解码的缓冲与
     * surface，恢复时**立刻**从原位置继续，不会黑一下。
     * 只有在整块卸载时才 release（见 onDetachedFromWindow）。
     */
    void setPlaybackPaused(boolean paused) {
        // ★ 记住状态，而不是"当场判断要不要 pause"。
        //
        // 为什么必须记：`prepareAsync()` 是异步的 —— onPause 时播放器
        // 可能**还没 prepared**，此时任何 pause 都无从谈起；等 onPrepared
        // 回调到达时它会自动 `start()`，于是后台又开始解码。
        // 这正是实测"按 HOME 后仍占 18.2%"的原因（我第一版就是这么漏的）。
        // 现在把意愿存下来，onPrepared 里据此决定要不要起播。
        wantedPaused = paused;
        if (player == null || !prepared) return;
        try {
            if (paused) {
                if (player.isPlaying()) player.pause();
            } else {
                if (!player.isPlaying()) player.start();
            }
        } catch (Throwable ignore) {
            // 背景是装饰性的，任何异常都不能影响主流程
        }
    }

    /** 用户/生命周期表达的"希望暂停"意愿；onPrepared 会参考它 */
    private volatile boolean wantedPaused = false;

    private void releasePlayer() {
        // 释放前记下播放位置，供重建后续播（见 resumePos 注释）。
        if (player != null && prepared) {
            try {
                int p = player.getCurrentPosition();
                if (p > 0) resumePos = p;            } catch (Throwable ignore) {}
        }
        prepared = false;
        if (player != null) {
            try {
                if (player.isPlaying()) player.stop();
            } catch (Throwable ignore) {}
            try {
                player.release();
            } catch (Throwable ignore) {}
            player = null;
        }
    }

    /** 连 surface 一起放：只在纹理销毁 / 视图 detach 时用（那时 surface 已作废） */
    private void releaseAll() {
        releasePlayer();
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignore) {}
            surface = null;
        }
    }

    @Override protected void onDetachedFromWindow() {
        // ★ 必须释放。设置页每点一次开关就重建整棵 View 树，不释放就是
        // 每次泄漏一个 MediaPlayer（原生资源，不受 Java 堆管）。
        // 这里连 surface 一起放 —— 视图已脱离窗口，之后要么被复用（会重新
        // 走 available 回调）、要么被丢弃，两种情况下旧 surface 都不能留。
        releaseAll();
        super.onDetachedFromWindow();
    }

    /**
     * 布局完成后再算一次比例。
     *
     * <p>{@link #onPrepared} 触发时视图可能还没被测量（宽高为 0），
     * 那时 {@link #applyAspect} 会直接返回；等到真正布局出来必须补算一次，
     * 否则画面就停在 TextureView 默认的"拉伸"状态。
     */
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyAspect();
    }
}
