package com.deepseek.pet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * 程序化绘制、会自己动的桌宠形象（内置形象）。
 *
 * 特性：
 *  - 常驻动画：呼吸浮动、眨眼、耳朵/身体轻摆、尾巴摇、脚下阴影
 *  - 状态动作：被戳(受惊抖动)、被拎起(举手挣扎+乱晃)、落地(压扁回弹)、说话(张嘴点头)、开心、难过
 *  - 通过 setMood()/poke()/setDragging() 由 FloatingService 驱动
 *
 * 当用户导入了自定义模型（图片/GIF/序列帧）时，由 FloatingService 直接给 ImageView
 * 设置 drawable，此时本 View 的 onDraw 会自动跳过绘制。
 */
public class PetView extends ImageView {

    private static final String INITIAL_IDLE = "待机呼吸休闲";  // 初始待机动作
    /** 时节动作在待机随机池里的权重倍数（1.0 = 与普通动作等权）。
     *  节日/时段动作只是"更容易被抽到"，不会独占待机。 */
    private static final double SEASONAL_WEIGHT = 2.2;
    private static final String ACT_IDLE_HOLDER = "idle";

    // ===== 状态 =====
    private boolean dragging = false;
    private long pokeStart = 0;      // 最近一次被戳的时间
    private long happyStart = 0;     // 最近一次开心
    private long sadStart = 0;
    private boolean talking = false;
    private long talkStart = 0;
    private float faceDir = 1f;      // 朝向：1=右 -1=左（拖动方向影响）
    private float dragVX = 0f;       // 拖动方向速度（-1..1）

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private long lastFrame = 0;

    /**
     * 上一帧实际画出的内容标识（动作名 + 帧号）。
     *
     * <p>★ 这是"按需重绘"的核心：模型包/Live2D 的动画是按
     * <b>经过时间取模</b>算当前帧的（见 {@link PetModel#draw}），
     * 所以**同一个帧号重复绘制是纯粹浪费** —— 画面完全一样。
     *
     * <p>优化前 {@code onDraw} 结尾无条件调用 {@code scheduleNextFrame()}，
     * 于是桌宠以约 60fps 一直重绘，**即使动画只有 8fps**（帧号没变也照画）。
     * 而桌宠是常驻悬浮窗，全天如此 ⇒ 用户反馈的"占用高、发热高"。
     *
     * <p>现在记录上次画的内容，帧号没变就不请求下一帧 ——
     * 动画该跳帧时自然会在下一次 onDraw 里跳到新帧，播放速度不受影响。
     */
    private String lastDrawnKey = null;

    /** 性能档位对应的最小重绘间隔（毫秒），0=不限帧。由 Service 注入 */
    private int frameDelayMs = 33;
    /** 是否暂停绘制（熄屏 / 被遮挡）。暂停时完全不请求下一帧 */
    private volatile boolean drawingPaused = false;

    /** 由 FloatingService 按 Prefs 的性能档位注入 */
    public void setFrameDelayMs(int ms) { this.frameDelayMs = Math.max(0, ms); }

    /**
     * 暂停/恢复绘制。
     *
     * <p>熄屏或被遮挡时桌宠**看不见**，此时继续跑动画是纯浪费 ——
     * 而且这是最常见的耗电场景（夜里充电、手机放口袋）。
     * 恢复时会自动重新请求一帧，所以不会出现"回来还是黑屏"。
     */
    public void setDrawingPaused(boolean paused) {
        if (drawingPaused == paused) return;
        drawingPaused = paused;
        if (!paused) {
            lastDrawnKey = null;      // 强制下一帧重画（恢复后先补一帧）
            postInvalidateOnAnimation();
        }
    }

    public boolean isDrawingPaused() { return drawingPaused; }

    /** 若设置了模型包，则绘制模型帧动画而不是内置形象 */
    private PetModel model = null;

    /** Live2D 模式（分层骨骼伪 Live2D）。由 FloatingService 按 Prefs 开启 */
    private Live2DEngine live2d = null;
    private String pendingAction = "idle";

    /** 内置素材包模式（animated WebP 动画包） */
    private PetAnimPack animPack = null;
    /** 场景层：场景 → 动作（见 ActionSystem）。素材层仍由 animPack 负责解码与缓存。 */
    private ActionSystem actions = null;
    /** 当前场景 id（见 SceneRegistry）。老名字 idle/turn/drag/click/move 仍可用（自动映射）。 */
    private String animPackState = SceneRegistry.IDLE;
    private boolean rotateIdle = false;        // 待机轮播：true 时 idle 状态随机轮播全部动画
    /** 时节动作提供者（由 Service 注入：返回节日/节气/时段专属动画名，或 null） */
    public interface SeasonalProvider { String get(); }
    private SeasonalProvider seasonalProvider = null;
    private boolean seasonalHit = false;       // 本次待机是否用了时节动作
    public void setSeasonalProvider(SeasonalProvider p) { seasonalProvider = p; }
    public boolean lastWasSeasonal() { return seasonalHit; }

    /**
     * 场景切到"只有素材层才知道"的那两个时的回调（台词系统用）。
     *
     * <p>★ 轮次㊲：用户要求「台词选项同步动作选项」⇒ 18 个动作场景都要有台词出口。
     * 其中 {@code pool}（待机轮播换动作）与 {@code festival}（命中节日/时段）**只在 PetView 内部发生**
     * （服务层看不到"这一轮抽到了什么"）⇒ 由这里回调上去，服务层再说那句话。
     * 没写自定义台词时回调什么都不做（见 {@code FloatingService.saySceneLine}）。
     */
    public interface SceneLineSink { void onSceneLine(String scene); }
    private SceneLineSink sceneLineSink = null;
    public void setSceneLineSink(SceneLineSink s) { sceneLineSink = s; }
    private boolean actionPlaying = false;      // 正在播放"点击=随机动作"的一次性动画
    private boolean nextIsInitial = true;      // 待机循环：下一个是否播初始待机动作
    private final android.os.Handler rotH = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable rotTask;
    private String animPackCurrent = null;     // 当前正在播放的 clip id
    /** 当前 clip 的内容边界（画布尺寸 + 画布空间留白）；未算好保持上一个动作的值，绝不清空 */
    private volatile int packBmW = 0, packBmH = 0;
    private volatile float[] packAlpha = null;
    /** 当前形象是不是"内置动画包"那条路（决定边距该按 clip 还是按图片算） */
    private boolean usingAnimPack = false;
    /** 上传图片 / 动图的画布尺寸与 alpha 留白比例（画布空间） */
    private volatile int imageBmW = 0, imageBmH = 0;
    private volatile float[] imageAlpha = null;
    private boolean animPackMirror = false;    // 是否水平镜像（朝向）
    /** 镜像的那条日志只打一次（换回不镜像时复位）—— 设备侧要能核对"绕哪儿翻" */
    private boolean mirrorLogged = false;
    /**
     * ★ 轮次㊸：本角色包 move/turn 素材的**原生朝向**。
     *
     * <p>判据原来写的是 {@code faceDir < 0 ⇒ 镜像}（= 假设素材**原生朝右**）。但内置角色包里
     * 那个奔跑动作的名字就叫「原地<b>左</b>转奔跑」—— 素材原生朝**左**。假设反了 ⇒
     * **两个方向都反**：往左走时被镜像成朝右、往右走时保持朝左（用户报「漫游动作方向不对」）。
     *
     * <p>现在按"素材原生朝左 ⇒ 往右走才镜像"来算。★ 哪天换了原生朝右的角色包，
     * **只改这一个常量**就能整体反转（不用去翻 playAnimPackState 里的表达式）。
     */
    private static final float BASE_FACE_DIR = -1f;
    /** 动图是否被暂停（熄屏省电）；见 pauseAnimPack */
    private boolean animPackPaused = false;
    private final android.graphics.Matrix animMatrix = new android.graphics.Matrix();
    private final android.graphics.RectF animSrc = new android.graphics.RectF();

    public PetView(Context c) { super(c); init(); }
    public PetView(Context c, AttributeSet a) { super(c, a); init(); }
    public PetView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        setScaleType(ScaleType.FIT_CENTER);
    }

    /** 是否应用了自定义 drawable（图片/动图）。有则本 View 不绘制。 */
    private boolean hasExternalDrawable() {
        return getDrawable() != null;
    }

    /** 设置序列帧模型包（导入模型）。传 null 恢复内置形象。 */
    public void setModel(PetModel m) {
        this.model = m;
        if (m != null) { usingAnimPack = false; clearImageInset(); }   // 模型包自己铺满，没有留白
        lastDrawnKey = null;      // 换了形象 ⇒ 强制重画（见 lastDrawnKey 的说明）
        invalidate();
    }
    public boolean hasModel() { return model != null && model.isValid(); }

    /** 启用/停用内置素材包（webp 动画）。enable=true 时惰性加载。 */
    public void setAnimPack(boolean enable) {
        if (enable) {
            if (animPack == null) {
                animPack = new PetAnimPack();
                // ★ v7.0：按**当前角色**加载（用户角色包 → 它的 anim/pack.json 与 clips/；
                //   内置角色 → APK 的 assets）。见 CharacterStore。
                PetCharacter _ch = CharacterStore.current(getContext());
                animPack.load(getContext().getApplicationContext(),
                        _ch != null ? _ch.dir : null);
                if (animPack.isFailed()) { animPack = null; }
            }
            if (animPack != null) {
                // ★ 场景层：动作系统与素材层分离（见 ActionSystem 的说明）
                if (actions == null) actions = new ActionSystem();
                actions.attach(animPack);
                actions.load(animPack.configRoot());
                // ★ P1：角色包配置装完之后，再叠**用户覆盖层**
                //   （filesDir/scenes/<角色 id>.json，存的是"用户在设置页改过的场景"）。
                //   顺序不能反：applyOverrides 只替换覆盖里出现的场景，反了就会被角色包盖回去。
                //   按 CharacterStore.currentId 取 ⇒ 切角色 = 换一套绑定，天然隔离。
                try {
                    actions.applyOverrides(SceneBindings.loadRoot(getContext(),
                            CharacterStore.currentId(getContext())));
                } catch (Throwable ignore) {}
                try { actions.setPoolEnabled(Prefs.animPoolEnabled(getContext())); } catch (Throwable ignore) {}
                setModel(null); setLive2D(false);
                // ★ 切到动画包：边距改由 clip 的 alpha 包围盒决定，图片那份作废
                usingAnimPack = true;
                clearImageInset();
                animPackState = SceneRegistry.IDLE;
                playAnimPackState();
            }
        } else {
            if (animPack != null) { animPack.recycle(); animPack = null; }
            actions = null;
            usingAnimPack = false;
        }
        invalidate();
    }

    public boolean isAnimPack() { return animPack != null && animPack.isReady(); }

    /**
     * 暂停内置动图（AnimatedImageDrawable）。
     *
     * <p>熄屏时调用。{@code AnimatedImageDrawable} 由系统驱动解码，
     * **不受** {@code scheduleNextFrame} 控制 —— 实测熄屏后它的解码线程
     * （线程名 {@code AnimatedImageTh}）仍占约 2.4% CPU。
     * 所以必须显式停它。
     */
    public void pauseAnimPack() {
        animPackPaused = true;
        try {
            if (animPack != null) {
                // 停掉"起播/轮播"的定时器，否则它会把动画重新拉起来
                if (rotTask != null) { rotH.removeCallbacks(rotTask); rotTask = null; }
                if (tmpStateReset != null) { removeCallbacks(tmpStateReset); tmpStateReset = null; }
                // ★ 用 stopAllAndHold 而不是 stopAll —— 后者会被
                //   AnimatedImageDrawable 的 onAnimationEnd 回调立刻重启，
                //   实测停播后解码线程仍占 2.6%。
                animPack.stopAllAndHold();
            }
        } catch (Throwable ignore) {}
    }

    /** 恢复内置动图（熄屏转亮屏 / 用户交互时）。重新起播当前状态对应的动画。 */
    public void resumeAnimPack() {
        if (!animPackPaused) return;
        animPackPaused = false;
        try {
            if (animPack != null) {
                animPack.resumeAfterStop(null);
                playAnimPackState();
            }
        } catch (Throwable ignore) {}
    }

    public boolean isAnimPackPaused() { return animPackPaused; }

    // ———— 关于"省电档能否压低内置动画帧率"的实测结论 ————
    //
    // ★ 记录两个**被实测否掉的设计**，免得以后有人再走一遍：
    //
    //   ① "间歇播放"（播 450ms / 停 450ms）
    //      实测反而更费：高性能 20.6% / 均衡 20.5% / 省电 24.6~28.9%。
    //      原因：playAnimPackState() 每次都会**重新挑选 clip 并重新起播解码**，
    //      反复 start/stop = 反复解码，比连续播放贵。
    //
    //   ② "闲置 12s 后冻结"（本意：用户不管时才省）
    //      实测同样无效果（均衡 23.1/24.6% vs 省电 23.1/22.4%）。
    //      原因：内置动画会随时间轮播（时节动作/rotateIdle），
    //      每次状态变化都会走 setAnimState → 解冻 → 重新计时，
    //      于是"冻不住"。而且停掉动图本身也只省 2.8%（实测），
    //      大头是 RenderThread 的**合成**开销，不是解码。
    //
    //   结论：内置动画（AnimatedImageDrawable）无法靠应用层压帧率。
    //   真正有效且已确认的省电手段是"看不见时就整体停掉"：
    //     熄屏 → 窗口 GONE + stopAllAndHold + 停视频（实测降 98%）
    //     切后台 → 停视频（实测降 29%）
    //   这两条由 Prefs.perfAuto 控制，见 FloatingService.screenReceiver。
    //
    //   三个档位仍然保留，但它们的作用点是**模型包 / Live2D**
    //   （那两条路径由我们自己重绘，限帧与按需判重都真正生效）。
    public boolean isDraggingPublic() { return dragging; }

    /** 外部设置朝向（漫游\/行走用）：1=右 -1=左 */
    public void setFaceDir(float d) {
        float nd = d < 0 ? -1f : 1f;
        if (nd == faceDir) return;
        faceDir = nd;
        // ★ 轮次㊺：方向敏感的场面（turn / roam）里换向要**当场重算镜像**。
        //   否则"同一个场景连走两段、方向相反"时，第二段会沿用第一段的镜像状态 ——
        //   因为 `setScene` 对**同一个场景**会早退，不会重新走 playAnimPackState()。
        try {
            if (animPack != null && animPackCurrent != null
                    && (SceneRegistry.TURN.equals(animPackState)
                        || SceneRegistry.ROAM.equals(animPackState))) {
                PetAnimPack.Clip c = animPack.clipByName(animPackCurrent);
                animPackMirror = (c != null) && (faceDir != BASE_FACE_DIR) && !c.noMirror;
                android.util.Log.i("PetRotate", "换向重算 dir=" + faceDir
                        + " mirror=" + animPackMirror + " clip=" + animPackCurrent);
            }
        } catch (Throwable ignore) {}
        invalidate();
    }


    /** 切场景（旧名 idle/turn/drag/click/move 仍兼容，自动映射到新场景 id） */
    public void setAnimState(String st) {
        setScene(st);
    }

    /** 切场景（见 {@link SceneRegistry}）。名字不认识时退回待机。 */
    public void setScene(String scene) {
        if (animPack == null || scene == null) return;
        if (blockedByLock(scene)) return;
        String s = SceneRegistry.fromLegacyState(scene);
        if (s.equals(SceneRegistry.fromLegacyState(animPackState))) return;
        animPackState = s;
        playAnimPackState();
    }

    // ==================== 场景锁（轮次60：漫游优先）====================
    //
    // 用户原话：「漫游动作优先触发，因为主动漫游的时候触发了其他动作，导致漫游动作无法触发」。
    // ★ 真凶：`setSceneTemp()` 是**无条件替换**当前场景的，而且到点回的是 **IDLE**。
    //   漫游期间只要有**别的**场景被触发（整点报时 / 碎碎念 / 工作状态 / 轮播 / 余额…），
    //   漫游动作当场被换掉；等那个临时场景到点，回的是 IDLE 而不是漫游
    //   ⇒ 人还在滑行、动作已经不是漫游了（用户看到的就是"漫游动作无法触发"）。
    // ★ 这里做成**通用场景锁**（PetView 不认识"漫游"这个业务概念，只认"锁住某个场景"）：
    //   锁住期间，**除锁定的那个场景外**的切场景请求一律忽略（含临时场景的到点复位）。
    // ★ 用户碰桌宠那条路不受影响：`ACTION_DOWN` 会先 `stopWander()`，而它会解锁（见 FloatingService）。

    /** 被锁住的场景 id；null = 没锁 */
    private String lockedScene = null;

    /**
     * **手点动作锁**（轮次61）：true 时**任何**场景切换都被挡。
     *
     * <p>用户原话：「**点动作的时候，有时候会被其他动作打断，更改一下逻辑，
     * 让当前触发动作优先级最高**」。
     *
     * <p>★ 真凶与漫游那次**同源**：`setSceneTemp()` 无条件替换当前场景，而
     * {@link #playAnimByName} 播的是**一次性 drawable** ⇒ 整点 / 碎碎念 / 工作状态 /
     * 轮播 / 余额 这些**自动**场景一触发，用户手点的那个动作当场被换掉。
     *
     * <p>★ 与 {@link #lockScene} 的区别：那个锁"只认某一个场景"（漫游用），
     * 这个锁**谁都不许抢**（手点动作期间）。
     * ★ **用户碰桌宠会立刻解锁**（见 FloatingService 的 `ACTION_DOWN`）——
     * 用户永远优先于锁：锁是拿来挡**自动**场景的，不是拿来挡用户的。
     */
    private boolean actionLocked = false;
    private Runnable actionUnlock;

    /** 为一次"用户手点的动作"上锁（ms 后自动解锁；播完也会解锁，见 {@link #playAnimByName}） */
    public void lockAction(long ms) {
        actionLocked = true;
        if (actionUnlock != null) removeCallbacks(actionUnlock);
        // ★ 顺手取消"临时场景到点回待机"那个待办 —— 它会把正在播的手点动作顶掉
        if (tmpStateReset != null) { removeCallbacks(tmpStateReset); tmpStateReset = null; }
        actionUnlock = new Runnable() {
            @Override public void run() {
                actionLocked = false;
                actionUnlock = null;
            }
        };
        postDelayed(actionUnlock, Math.max(200, ms));
    }

    /** 解锁手点动作（播完 / 用户碰它时调） */
    public void unlockAction() {
        actionLocked = false;
        if (actionUnlock != null) {
            removeCallbacks(actionUnlock);
            actionUnlock = null;
        }
    }

    /** 现在锁着吗（设备断言/日志用） */
    public boolean isActionLocked() { return actionLocked; }

    /**
     * 锁住某个场景（漫游用）：锁住期间别的场景**抢不走**它。
     * <p>会顺手取消"临时场景到点回待机"那个待办 —— 否则上一条临时场景的复位会把锁住的场景顶掉。
     */
    public void lockScene(String scene) {
        if (animPack == null || scene == null) return;
        lockedScene = SceneRegistry.fromLegacyState(scene);
        if (tmpStateReset != null) { removeCallbacks(tmpStateReset); tmpStateReset = null; }
        animPackState = lockedScene;
        playAnimPackState();
    }

    /** 解锁（漫游结束 / 被用户打断时调）。**不**改当前场景，由调用方决定回哪个。 */
    public void unlockScene() {
        lockedScene = null;
    }

    /** 现在锁着吗（设备断言/日志用） */
    public boolean isSceneLocked() { return lockedScene != null; }

    /** 这个切场景请求是不是被锁挡下了 */
    private boolean blockedByLock(String scene) {
        // ★★ 轮次61：手点动作期间**谁都不许抢**（用户要求"当前触发动作优先级最高"）
        if (actionLocked) {
            android.util.Log.i("DSPAnim", "手点动作优先：挡下场景 " + scene);
            return true;
        }
        if (lockedScene == null) return false;
        if (lockedScene.equals(SceneRegistry.fromLegacyState(scene))) return false;
        // ★ 设备侧唯一证据：漫游期间"别的场景想抢、被挡下"会留下这一行
        //   （悬浮窗不进 uiautomator dump，这类行为只能靠日志验 —— 工程既有手法）
        android.util.Log.i("DSPWander", "漫游优先：挡下场景 " + scene + "（锁=" + lockedScene + "）");
        return true;
    }

    private Runnable tmpStateReset;
    /** 临时场景：播放 ms 毫秒后自动回待机。用于 click/turn/dock 等瞬时动作。 */
    public void setAnimStateTemp(final String st, long ms) {
        setSceneTemp(st, ms);
    }

    /** 临时场景（见 {@link #setScene}） */
    public void setSceneTemp(final String scene, long ms) {
        if (animPack == null || scene == null) return;
        if (blockedByLock(scene)) return;
        if (tmpStateReset != null) removeCallbacks(tmpStateReset);
        animPackState = SceneRegistry.fromLegacyState(scene);
        playAnimPackState();
        tmpStateReset = new Runnable() {
            @Override public void run() {
                if (animPack == null) return;
                animPackState = SceneRegistry.IDLE;
                playAnimPackState();
            }
        };
        postDelayed(tmpStateReset, Math.max(200, ms));
    }

    /**
     * 播一次某个场景的动作（一次性），时长取该动作自己的时长。
     * <p>给"点击/转向/整点/双击"这类瞬时反应用：场景里没绑动作时**什么都不做**（静默跳过），
     * 调用方不需要关心角色包里有没有配这个场景。
     */
    public boolean playSceneOnce(String scene, long fallbackMs) {
        if (animPack == null || !animPack.isReady() || actions == null) return false;
        String id = actions.pick(scene);
        if (id == null) return false;
        long ms = fallbackMs;
        try { ms = animPack.clipDurationMs(id, (int) fallbackMs); } catch (Throwable ignore) {}
        setSceneTemp(scene, Math.max(400, ms));
        return true;
    }

    /**
     * 看一眼某个场景会挑到**多长**的动作（毫秒；**不切状态、不播**）。
     *
     * <p>★ 轮次㊱：漫游需要"走多久才够这个动作播完"这个数，而它必须在**开走之前**就知道
     * （走起来再问就晚了 —— 那时动作已经在播了）。没绑这个场景 / 量不出来 ⇒ 返回 {@code fallbackMs}。
     *
     * <p>★ 与 {@link #playSceneOnce} 各自 pick 一次：两次可能抽到不同动作（时长略有差异），
     * 这是**故意**的 —— 为了一个时长而把"抽哪个"钉死，会让漫游每次走都播同一个动作。
     */
    public long peekSceneDurationMs(String scene, long fallbackMs) {
        if (animPack == null || !animPack.isReady() || actions == null) return fallbackMs;
        try {
            String id = actions.pick(scene);
            if (id == null) return fallbackMs;
            return Math.max(400, animPack.clipDurationMs(id, (int) fallbackMs));
        } catch (Throwable ignore) {
            return fallbackMs;
        }
    }

    /**
     * **正在播的那个** clip 的时长（ms）。
     *
     * <p>★ 轮次㊸：漫游必须用它，**不能用 {@link #peekSceneDurationMs}** ——
     * 那个方法自己 {@code pick} 一次、随后的 {@code setScene} 又 {@code pick} 一次，
     * 两次**可能抽到不同动作**（它的注释里写着"这是故意的"，那对"只取个时长"没问题，
     * 对"走多久必须等于播多久"就是错的）。
     *
     * <p>后果实测（用户报「漫游完位置不对应，位置会闪现」）：走 3 秒而动作有 6.5 秒 ⇒
     * 动作被**从中间掐掉**，而「原地左转奔跑」中段的内容中心偏了 **12.5%**（≈54px）⇒
     * 切回待机那一刻角色横向**跳一下**。现在"走多久 = 正在播的这个动作的完整时长"。
     */
    public long currentClipDurationMs(long fallbackMs) {
        if (animPack == null || !animPack.isReady() || animPackCurrent == null) return fallbackMs;
        try {
            return Math.max(400, animPack.clipDurationMs(animPackCurrent, (int) fallbackMs));
        } catch (Throwable ignore) {
            return fallbackMs;
        }
    }

    /**
     * **正在播的那个** clip 的名字（没有就 null）。
     *
     * <p>★ 轮次㊼：漫游的"按动作前后摇"要靠它 —— 开播之后才知道这一趟抽到的是哪个动作，
     * 再按动作名去查用户覆盖层（{@link RoamTiming}），查不到才回退全局值。
     */
    public String currentClipId() {
        return animPackCurrent;
    }

    /** 根据当前状态挑一个 clip 并切换 drawable。 */
    private void playAnimPackState() {
        if (animPack == null) return;
        String scene = SceneRegistry.fromLegacyState(animPackState);
        seasonalHit = false;
        // 待机时若命中节日/时段，优先走 festival 场景（老逻辑是"给该动作加权"，
        // 现在改成显式场景：绑了才播，没绑就退回待机）
        if (SceneRegistry.IDLE.equals(scene) && rotateIdle && !actionPlaying && !nextIsInitial
                && seasonalProvider != null && actions != null) {
            String sid = null;
            try { sid = seasonalProvider.get(); } catch (Exception ignore) {}
            if (sid != null && actions.clipsOf(SceneRegistry.FESTIVAL).contains(sid)) {
                scene = SceneRegistry.FESTIVAL;
                seasonalHit = true;
            }
        }
        String id = (actions != null) ? actions.pick(scene) : null;
        if (id == null) return;
        PetAnimPack.Clip c = animPack.clipByName(id);
        if (c == null) return;
        android.graphics.drawable.Drawable d = animPack.getDrawable(c.id);
        if (d == null) return;
        animPackCurrent = c.id;
        android.util.Log.i("PetRotate", "play scene=" + scene + " id=" + c.id
                + " nextInit=" + nextIsInitial + " actPlay=" + actionPlaying);
        // ★ 轮次㊸：镜像判据按**素材原生朝向**算（见 BASE_FACE_DIR）。
        //   往"原生朝向的反方向"走时才镜像；noMirror 的 clip 永不镜像。
        animPackMirror = (SceneRegistry.TURN.equals(scene) || SceneRegistry.ROAM.equals(scene))
                ? (faceDir != BASE_FACE_DIR) && !c.noMirror : false;
        // ★ 设备侧只能靠日志验朝向（悬浮窗不进 uiautomator dump）：
        //   `mirror=` 是"这一帧到底有没有被水平翻转"的唯一直接证据。
        android.util.Log.i("PetRotate", "face dir=" + faceDir + " base=" + BASE_FACE_DIR
                + " mirror=" + animPackMirror + " clip=" + c.id + " noMirror=" + c.noMirror);
        setImageDrawable(d);
        animPack.startDrawable(d);
        invalidate();
        // 内容边距（贴边与气泡对位都要它）：命中缓存就同步生效，未命中才异步算
        applyInsetFor(c.id);
        boolean isIdle = "idle".equals(animPackState);
        if (isIdle && rotateIdle && !actionPlaying) {
            boolean playedInitial = (c.id != null && c.id.equals(INITIAL_IDLE));
            nextIsInitial = !playedInitial;   // 交替：初始->随机->初始->…
            scheduleRotate();
        } else {
            cancelRotate();
        }
        // ★ 轮次㊲：台词选项同步动作选项 —— 这两个场景只在 PetView 内部发生，回调给服务层说话。
        //   ① festival：这一轮待机用了节日/时段动作；② pool：待机轮播换了一个动作。
        if (sceneLineSink != null) {
            try {
                if (seasonalHit) sceneLineSink.onSceneLine(SceneRegistry.FESTIVAL);
                else if (isIdle && rotateIdle && !actionPlaying) sceneLineSink.onSceneLine(SceneRegistry.POOL);
            } catch (Throwable ignore) {}
        }
    }

    /** 开启/关闭待机轮播（true：idle 状态在全部动画里随机轮播，每个完整播完再换） */
    public void setRotateIdle(boolean on) {
        if (rotateIdle == on) return;
        rotateIdle = on;
        if (on) {
            if ("idle".equals(animPackState)) { playAnimPackState(); }
        } else {
            cancelRotate();
        }
    }
    public boolean isRotateIdle() { return rotateIdle; }

    /** 按当前动画完整轮播时长，安排切到下一个 */
    private void scheduleRotate() {
        cancelRotate();
        if (animPack == null || animPackCurrent == null) return;
        int dur = animPack.clipDurationMs(animPackCurrent, 3500);
        if (dur < 800) dur = 800;
        if (dur > 15000) dur = 15000;
        // 末尾多停留一点点，避免衔接太急
        boolean curIsInitial = (INITIAL_IDLE.equals(animPackCurrent));
        rotTask = new Runnable() {
            @Override public void run() {
                if (!rotateIdle || animPack == null) return;
                if (!"idle".equals(animPackState)) return;
                playAnimPackState();
            }
        };
        // 降低待机轮播频率：初始动作停留更久，随机动作也额外停顿，整体更安静
        long extra = curIsInitial ? 18000L : 12000L;   // 统一节奏，时节动作不额外停留
        rotH.postDelayed(rotTask, dur + extra);
    }
    private void cancelRotate() {
        if (rotTask != null) { rotH.removeCallbacks(rotTask); rotTask = null; }
    }
    /** 取指定动画名对应的台词（转发到 PetAnimPack） */
    public String animLine(String id) { return animPack != null ? animPack.lineFor(id) : null; }
    /** 全部动画名（供动作选择气泡） */
    public java.util.List<String> animAllNames() { return animPack != null ? animPack.allNames() : new java.util.ArrayList<String>(); }

    /** 全部分类名（供选择器展示） */
    public java.util.List<String> animCategories() { return animPack != null ? animPack.categoryIds() : new java.util.ArrayList<String>(); }
    /** 指定分类下的动画名 */
    public java.util.List<String> animIdsOf(String cat) { return animPack != null ? animPack.idsOfCategory(cat) : new java.util.ArrayList<String>(); }
    /** 全部动画名（供选择器展示） */
    public java.util.List<String> animAllIds() {
        return animPack != null ? animPack.allIds() : new java.util.ArrayList<String>();
    }

    /**
     * 事件组里随机取一个动画名（balance / whisper / workStatus），没有则 null。
     * <p>事件动画（余额档位 / 主动搭话碎碎念 / 工作场景）**按需触发**，不进待机随机池。
     */
    public String animRandomEventName(String group) {
        return animPack != null ? animPack.pickEventName(group) : null;
    }

    /** 事件组名清单（供断言与菜单展示） */
    public java.util.List<String> animEventGroups() {
        return animPack != null ? animPack.eventGroupIds() : new java.util.ArrayList<String>();
    }

    /** 点击选择器后：播放指定动画一次，播完回到待机循环 */
    public void playAnimById(String id) {
        if (animPack == null || id == null || !animPack.isReady()) return;
        cancelRotate();
        android.graphics.drawable.Drawable d = animPack.getDrawable(id);
        if (d == null) return;
        actionPlaying = true;
        nextIsInitial = true;
        animPackState = "idle";
        animPackCurrent = id;
        animPackMirror = false;
        setImageDrawable(d);
        applyInsetFor(id);
        invalidate();
        animPack.startDrawableOnce(d, new Runnable() {
            @Override public void run() {
                actionPlaying = false;
                if (rotateIdle) playAnimPackState();
            }
        });
    }

    /**
     * 点击时调用：从全部动画里随机挑一个，播放一次（不循环），并返回其动画名。
     * 播完自动回到待机循环（回到初始待机动作）。
     */
    public String playRandomActionOnce() {
        if (animPack == null || !animPack.isReady()) return null;
        cancelRotate();
        PetAnimPack.Clip c = animPack.pickRotate();
        if (c == null) return null;
        android.graphics.drawable.Drawable d = animPack.getDrawable(c.id);
        if (d == null) return c.id;
        actionPlaying = true;
        nextIsInitial = true;   // 点击动作播完后，待机从初始动作重新开始
        animPackState = "idle";
        animPackCurrent = c.id;
        android.util.Log.i("PetRotate", "play state=" + animPackState + " id=" + c.id + " nextInit=" + nextIsInitial + " actPlay=" + actionPlaying);
        animPackMirror = false;
        setImageDrawable(d);
        applyInsetFor(c.id);
        invalidate();
        animPack.startDrawableOnce(d, new Runnable() {
            @Override public void run() {
                actionPlaying = false;
                if (rotateIdle) playAnimPackState();   // 回到待机循环
            }
        });
        return c.id;
    }

    /** 播放指定动画一次（不循环），并返回该动画名；播完回到待机循环 */
    public String playAnimByName(String id) {
        return playAnimByName(id, false);
    }

    /**
     * 播放指定动画一次；{@code exclusive=true} 表示**这是用户手点的动作**。
     *
     * <p>★★ 轮次61（用户原话：「点动作的时候，有时候会被其他动作打断，更改一下逻辑，
     * 让当前触发动作优先级最高」）：手点的动作在**播放期间上锁**
     * （{@link #lockAction}）⇒ 整点/碎碎念/工作状态/轮播/余额这些**自动**场景抢不走它；
     * 播完（或用户碰它）立刻解锁。
     * <p>★ 自动播的动画（余额档位 / 事件动画）走 {@code exclusive=false}：
     * 它们不该反过来锁住用户的操作。
     */
    public String playAnimByName(String id, boolean exclusive) {
        if (animPack == null || !animPack.isReady() || id == null) return null;
        PetAnimPack.Clip c = animPack.clipByName(id);
        if (c == null) return null;
        android.graphics.drawable.Drawable d = animPack.getDrawable(c.id);
        if (d == null) return c.id;
        cancelRotate();
        actionPlaying = true;
        nextIsInitial = true;
        animPackState = "idle";
        animPackCurrent = c.id;
        animPackMirror = false;
        // ★★ 上锁要在**开始播之前**：上锁会取消"临时场景到点回待机"的待办，
        //   放后面的话那一下已经把刚起的动作顶掉了。
        if (exclusive) {
            long ms = 3000;
            try { ms = animPack.clipDurationMs(c.id, 3000); } catch (Throwable ignore) {}
            lockAction(Math.max(400, ms));
        }
        setImageDrawable(d);
        applyInsetFor(c.id);
        invalidate();
        final boolean ex = exclusive;
        animPack.startDrawableOnce(d, new Runnable() {
            @Override public void run() {
                actionPlaying = false;
                if (ex) unlockAction();              // ★ 播完解锁（用户碰它也会解锁）
                if (rotateIdle) playAnimPackState();  // 回到待机循环
            }
        });
        return c.id;
    }

    /**
     * 当前形象"可见内容"在窗口里的留白比例 {L,R,T,B}（相对**视口**宽/高，0~1）；不知道时返回 null。
     *
     * <p>贴边（{@code FloatingService.edgeRangeX}）与气泡对位都用它：窗口要挂出屏幕外多少、
     * 气泡该离桌宠多远，全都由"内容离窗口边有多远"决定。
     *
     * <h3>★ 三条容易写错的地方</h3>
     * <ol>
     *   <li><b>动画包</b>：素材量出来的是**画布空间**（clip 画布多大、里面留白多少），
     *       而 clip 画布长宽比不一定等于视口长宽比（角色包文档推荐竖构图 500×556，
     *       视口却是 16:9）⇒ 必须过 {@link ContentInset#of} 换算，
     *       否则漏掉"等比缩放居中"那 156px。</li>
     *   <li><b>上传的图片 / 动图</b>：同理（方图最常见），漏掉的是左右各 138px。</li>
     *   <li><b>模型包 / Live2D</b>：自己按视口大小铺满绘制，没有留白 ⇒ 返回 null（= 无留白）。</li>
     * </ol>
     * 三条路都走同一个 {@link #mappedInset}，就是为了不再出现"这条算了那条没算"。
     */
    public float[] currentContentInset() {
        float[] in = usingAnimPack ? mappedInset(packBmW, packBmH, packAlpha)
                                   : mappedInset(imageBmW, imageBmH, imageAlpha);
        // ★★ 轮次㊻：**镜像时左右边距要互换** —— 画面被水平翻转，"内容左边缘"对应的
        //   其实是素材的右留白。不换的话，贴边范围（edgeRangeX）/ 拖动钳制 / 余额气泡对位
        //   全都按**没翻过**的边距算 ⇒ 镜像那一侧（往右跑）还会差一点点
        //   （素材左右留白不等时最大差 ~8px；用户报的"位置对不上"里就有这一份）。
        if (in != null && animPackMirror && in.length >= 4) {
            return new float[]{ in[1], in[0], in[2], in[3] };
        }
        return in;
    }

    /** 画布空间的留白 → 视口空间的留白（{@link ContentInset#of}）；量不出来返回 null */
    private float[] mappedInset(int bmW, int bmH, float[] alpha) {
        int vw = getWidth(), vh = getHeight();
        if (bmW <= 0 || bmH <= 0 || vw <= 0 || vh <= 0 || !ContentInset.usable(alpha)) return null;
        return ContentInset.of(bmW, bmH, alpha, vw, vh, getScaleType() == ScaleType.FIT_CENTER);
    }

    /**
     * 换动作后的边距刷新（内置动画包那条路）。
     *
     * <p>★ 这里**不许**写成 {@code curInset = null; 异步再补}（旧写法）——
     * 空窗期正好盖住"松手贴边"的判定，于是贴边永远失败（2026-10-08 报障的根因）。
     * 现在：命中缓存 ⇒ 同步生效；未命中 ⇒ 异步算，算完用
     * {@link ContentInset#keep} 覆盖（算不出来就保留上一个动作的值，绝不清空）。
     */
    private void applyInsetFor(String cid) {
        final PetAnimPack pk = animPack;
        if (pk == null || cid == null) return;
        ContentInsetProbe.Measured cached = pk.cachedClipInset(cid);
        if (cached != null) {
            packBmW = cached.bmW; packBmH = cached.bmH; packAlpha = cached.alpha;
            return;
        }
        final String want = cid;
        new Thread(new Runnable() {
            @Override public void run() {
                final ContentInsetProbe.Measured m = pk.measureClip(want);
                post(new Runnable() { @Override public void run() {
                    if (!want.equals(animPackCurrent)) return;
                    if (m != null) {
                        packBmW = m.bmW; packBmH = m.bmH;
                        packAlpha = ContentInset.keep(packAlpha, m.alpha);
                    }
                    // m == null（量不出来）：保留上一个动作的边距，绝不清空
                }});
            }
        }, "pet-inset").start();
    }

    /**
     * 上传图片 / 动图的留白（由 {@code FloatingService} 在后台量好后回调；量不出传 null）。
     *
     * <p>为什么不让本类自己去解码：素材是 {@code File}（动图）或已解码的 {@code Bitmap}，
     * 解码/扫 alpha 都不能在 UI 线程做，量完再回调最省事，也让"谁负责解码"只有一处。
     */
    public void setImageInset(int bmW, int bmH, float[] alpha) {
        if (bmW <= 0 || bmH <= 0 || !ContentInset.usable(alpha)) {
            imageBmW = 0; imageBmH = 0; imageAlpha = null;
        } else {
            imageBmW = bmW; imageBmH = bmH; imageAlpha = alpha;
        }
        invalidate();
    }

    /** 清掉"上传图片/动图"的留白（切回动画包 / 模型包 / Live2D 时调） */
    public void clearImageInset() {
        imageBmW = 0; imageBmH = 0; imageAlpha = null;
    }

    // ===== 外部驱动接口（FloatingService 调用）=====
    /** 启用/停用 Live2D 分层形象。enable=true 时惰性加载素材。 */
    public void setLive2D(boolean enable) {
        if (enable) {
            if (live2d == null) {
                live2d = new Live2DEngine();
                live2d.load(getContext());
                if (live2d.isFailed()) { live2d = null; }
            }
            if (live2d != null) {
                setImageDrawable(null); setModel(null);
                // Live2D 自己按视口铺满 ⇒ 没有留白，边距作废
                usingAnimPack = false;
                clearImageInset();
            }
        } else {
            if (live2d != null) { live2d.recycle(); live2d = null; }
        }
        invalidate();
    }

    /**
     * 启用导入的 Live2D 模型包（目录内 meta.json + 分层 PNG）。
     * dir=null 或加载失败 → 返回 false，调用方可回退内置。
     */
    public boolean setLive2DFromDir(java.io.File dir) {
        if (live2d != null) { live2d.recycle(); live2d = null; }
        if (dir == null) { invalidate(); return false; }
        Live2DEngine e = new Live2DEngine();
        e.loadFromDir(getContext(), dir);
        if (e.isFailed()) { invalidate(); return false; }
        live2d = e;
        setImageDrawable(null);
        setModel(null);
        usingAnimPack = false;
        clearImageInset();
        invalidate();
        return true;
    }

    public boolean isLive2D() { return live2d != null && live2d.isReady(); }

    public void poke() {
        pokeStart = SystemClock.uptimeMillis();
        if (live2d != null) live2d.poke();
        // ★ 轮次57：戳桌宠响一声（开关/音色/音量都在 Prefs；关掉时这一行直接返回）。
        //   放在最前面：手感反馈要"立刻"，不能等动作或说话准备好。
        ClickPlayer.playFromPrefs(getContext());
        // ★ 点击 → click 场景。
        //   改造前 `case "click"` 分支存在、`pickClick()` 也在，但**全工程没有任何地方
        //   设置过 click 状态** ⇒ 那 5 个"点击回应"动作只能靠随机轮播撞上。
        //   现在点击真的走 click 场景；角色包没绑这个场景时静默跳过。
        playSceneOnce(SceneRegistry.CLICK, 1200);
        invalidate();
    }
    public void setDragging(boolean d, float vx) {
        dragging = d; dragVX = vx;
        if (d && Math.abs(vx) > 0.05f) { faceDir = vx < 0 ? -1f : 1f; }
        if (!d) dragVX = 0f;
        if (live2d != null) live2d.setDragging(d, vx);
        invalidate();
    }
    public void setTalking(boolean t) {
        talking = t;
        if (t) talkStart = SystemClock.uptimeMillis();
        if (live2d != null) live2d.setTalking(t);
        invalidate();
    }
    public void reactHappy() { happyStart = SystemClock.uptimeMillis(); invalidate(); }
    public void reactSad() { sadStart = SystemClock.uptimeMillis(); invalidate(); }

    /** 由外部（FloatingService）在模型模式下显式指定动作名 */
    public void playAction(String act) {
        pendingAction = act;
        // 换动作 ⇒ 下一帧内容一定是新的，清掉判重标记确保立刻画出来。
        // 不清的话，若新旧动作恰好算到同一个 key（如都停在 idx=0），
        // 这一帧会被跳过，表现为"点了没反应"，直到下一次帧号变化才补上。
        lastDrawnKey = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // ★ 暂停时不画（熄屏 / 被遮挡）。见 setDrawingPaused 的说明。
        if (drawingPaused) return;

        if (live2d != null) {
            live2d.update();
            live2d.draw(canvas, getWidth(), getHeight());
            // Live2D 是连续插值动画（不是按帧号跳），没有"同一帧"可判，
            // 所以只能按档位限帧。见 Prefs.perfMode 的说明。
            scheduleNextFrame(null);
            return;
        }
        if (animPack != null && getDrawable() != null) {
            if (animPackMirror) {
                // ★★ 轮次㊺：镜像要绕**视图自己的水平中心**翻，**不能**绕 drawable 的 intrinsic 中心。
                //
                //   为什么（用户报障的原话：「**右边向左边跑的时候正常，左边向右边跑的时候位置对不上
                //   会闪现**」—— 只有镜像那一侧才出问题）：
                //   `setScaleType(FIT_CENTER)` 下画面是**居中**画在视图里的，所以画面的中心在
                //   `getWidth()/2`；而 `dr.getIntrinsicWidth()/2`（素材画布的中心）只有在
                //   "素材宽 == 视图宽"时才恰好相等。一旦不等（素材画布比视图宽/窄，或换了别的
                //   角色包 —— 内置包是 640×360 画布、视图是 240dp×2.625=630px），
                //   绕 intrinsic 中心翻会让画面**整体平移** `2 × (intrinsic中心 − 视图中心)`：
                //   桌宠在"开走那一刻"横向跳一下（用户看到的"闪现"），整段漫游里位置也一直偏着
                //   （用户看到的"位置对不上"）。往左跑不镜像 ⇒ 一点都看不出来。
                //
                //   ★ 这条也是"为什么我在模拟器上几乎复现不出来"的原因：内置包 640 画布 vs 630 视图
                //     只差 10px；画布差得越多，跳得越狠（换个 1080 宽的角色包就是几百 px）。
                animMatrix.setScale(-1f, 1f, getWidth() / 2f, 0f);
                // ★ 设备侧的证据：把"绕哪儿翻"和视图/素材的宽度一起打出来 ——
                //   判据是 **pivot 必须等于 viewW/2**（而不是 intrinsicW/2）。
                if (!mirrorLogged) {
                    mirrorLogged = true;
                    android.graphics.drawable.Drawable d2 = getDrawable();
                    android.util.Log.i("PetRotate", "mirror pivot=" + (getWidth() / 2f)
                            + " viewW=" + getWidth() + " intrinsicW="
                            + (d2 == null ? -1 : d2.getIntrinsicWidth())
                            + " clip=" + animPackCurrent);
                }
                int save = canvas.save();
                canvas.concat(animMatrix);
                super.onDraw(canvas);
                canvas.restoreToCount(save);
            } else {
                mirrorLogged = false;
                super.onDraw(canvas);
            }
            // animPack 用 AnimatedImageDrawable，**由系统自己驱动帧**，
            // 不需要我们请求下一帧（原来这里也没请求）。
            return;
        }
        if (hasModel()) {
            model.setAction(resolveModelAction());
            // ★ 按需重绘的**第一道闸**：内容没变就整帧不画。
            //
            //   放在 model.draw() **之前**判断 —— 这才是真正的省。
            //   如果先画再判重，等于每个 vsync 都白画一次（原来就是这样，
            //   8fps 的动画被当成 60fps 渲染，约 5~7 倍无用功）。
            //
            //   动画帧号按"经过时间"推进，所以跳过绘制**不会**让动画变慢，
            //   只是不再重复画同一帧。
            //
            //   key 前面带上 "M:"（model）前缀：切到别的渲染模式时
            //   不可能与旧 key 撞上，避免"换了形象却沿用旧判重值、
            //   第一帧被跳过"。这是比"在每个 setter 里手动清标记"更稳的做法
            //   —— 后者只要漏一个入口就会出问题。
            String key = model.frameKey();
            if (key != null) key = "M:" + key;
            if (key != null && key.equals(lastDrawnKey)) {
                // 内容没变：精确睡到"下一帧该出现"的时刻，不空转
                if (frameDelayMs > 0) {
                    postInvalidateDelayed(frameDelayMs);
                } else {
                    postInvalidateDelayed(Math.max(1L, model.msToNextFrame()));
                }
                return;
            }
            lastDrawnKey = key;
            model.draw(canvas);
            // ★ 第二道闸：档位限帧。高性能档直接安排下一帧；
            //   其他档用"下一帧还有多久"调度，避免主线程被反复唤醒。
            if (frameDelayMs > 0) {
                postInvalidateDelayed(Math.max(
                        frameDelayMs, Math.max(1L, model.msToNextFrame())));
            } else {
                scheduleNextFrame(null);
            }
            return;
        }
        if (hasExternalDrawable()) {
            super.onDraw(canvas);
            return;
        }
        drawPlaceholder(canvas);
    }

    /** 未导入任何形象时的占位绘制：虚线圆角框 + 提示文字 */
    private void drawPlaceholder(Canvas cv) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float pad = getWidth() * 0.06f;
        p.reset();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, getWidth() * 0.012f));
        p.setColor(0x66FFFFFF);
        android.graphics.PathEffect dash =
                new android.graphics.DashPathEffect(new float[]{getWidth() * 0.04f, getWidth() * 0.03f}, 0f);
        p.setPathEffect(dash);
        android.graphics.RectF r = new android.graphics.RectF(
                pad, pad, getWidth() - pad, getHeight() - pad);
        cv.drawRoundRect(r, getWidth() * 0.12f, getWidth() * 0.12f, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x99FFFFFF);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(10f, getWidth() * 0.10f));
        cv.drawText("未导入形象", getWidth() / 2f, getHeight() / 2f + p.getTextSize() * 0.35f, p);
    }

    /** 根据交互状态选择模型动作名 */
    private String resolveModelAction() {
        long now = SystemClock.uptimeMillis();
        // 拖动时不再播放“被拎起”的 drag 动作，保持 idle
        if (dragging) return PetModel.ACT_IDLE;
        if (decay(now, pokeStart, 520) > 0.05f) return PetModel.ACT_TAP;
        if (talking) return PetModel.ACT_TALK;
        if (decay(now, happyStart, 1600) > 0.05f) return PetModel.ACT_HAPPY;
        if (decay(now, sadStart, 1800) > 0.05f) return PetModel.ACT_SAD;
        if (pendingAction != null && !ACT_IDLE_HOLDER.equals(pendingAction)) {
            return pendingAction;
        }
        return PetModel.ACT_IDLE;
    }

    /**
     * 请求下一帧 —— 用于 Live2D（连续插值、无"同一帧"可判）与高性能档。
     *
     * <p>模型包走的是 {@code onDraw} 里"内容判重 + 精确调度"那条路
     * （见那里的注释）；这里只处理"内容每次都不同"的情况。
     *
     * @param contentKey 预留参数；Live2D 传 {@code null}
     */
    private void scheduleNextFrame(String contentKey) {
        long now = SystemClock.uptimeMillis();
        int minGap = frameDelayMs;          // 0 = 不限帧
        if (minGap <= 0) {
            // 高性能档：沿用原来的"尽快画"策略
            if (now - lastFrame >= 16) {
                lastFrame = now;
                postInvalidateOnAnimation();
            } else {
                postInvalidateDelayed(16);
            }
            return;
        }
        long since = now - lastFrame;
        if (since >= minGap) {
            lastFrame = now;
            postInvalidateOnAnimation();
        } else {
            postInvalidateDelayed(minGap - since);
        }
    }

    // ===================== 角色绘制 =====================

    private float decay(long now, long start, long dur) {
        if (start == 0) return 0f;
        long dt = now - start;
        if (dt >= dur) return 0f;
        float x = 1f - dt / (float) dur;
        return x * x; // ease-out
    }
}
