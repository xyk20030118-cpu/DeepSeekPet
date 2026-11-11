package com.deepseek.pet;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.BatteryManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.*;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;

public class FloatingService extends Service {

    /** 设置变化时广播，通知 Service 重建视图 */
    public static final String ACTION_RELOAD = "com.deepseek.pet.RELOAD";
    /**
     * 只**重排悬浮层位置**，不重建视图。
     *
     * <p>给"可调间距"那种**拖动中就要实时看到效果**的设置用：
     * {@link #ACTION_RELOAD} 会走 {@code rebuildViews()}（重建整套视图 + 重启余额轮询），
     * 滑块每动一格就重建一次既卡又闪。这里只作废间距缓存并让侧栏重新贴一次。
     */
    public static final String ACTION_REPOSITION = "com.deepseek.pet.REPOSITION";
    /** App「退出桌宠」按钮：仅停止桌宠服务（退出桌宠），不杀后台进程 */
    public static final String ACTION_STOP = "com.deepseek.pet.STOP";
    /** App「启动 / 重启桌宠」按钮：已运行则重建桌宠并重播启动表现 */
    public static final String ACTION_RESTART = "com.deepseek.pet.RESTART";
    /** 调试：立即贴边（验证竖条，不等 idle；正式版保留，无 UI 入口，不影响用户） */
    public static final String ACTION_DOCK_NOW = "com.deepseek.pet.DOCK_NOW";
    /** 调试：立即展开（验证恢复；正式版保留，无 UI 入口，不影响用户） */
    public static final String ACTION_UNDOCK_NOW = "com.deepseek.pet.UNDOCK_NOW";
    /** App 进入前台：临时收起桌宠（不停止服务，保留心情/位置等状态） */
    public static final String ACTION_HIDE_TEMP = "com.deepseek.pet.HIDE_TEMP";
    /** App 退到后台：恢复桌宠显示 */
    public static final String ACTION_SHOW_TEMP = "com.deepseek.pet.SHOW_TEMP";
    /**
     * 调试/试玩：把"挂边探头"开关来回切一次并**立刻**生效（无 UI 入口）。
     *
     * <p>用法（真机验证）：
     * <pre>adb shell am broadcast -a com.deepseek.pet.PEEK_TOGGLE -p com.deepseek.pet</pre>
     * 打开后把桌宠拖到屏幕左/右边缘松手，它就会挂上去只露头；
     * 再发一次即关掉（关掉时若正挂着会平滑收回屏内）。
     */
    public static final String ACTION_PEEK_TOGGLE = "com.deepseek.pet.PEEK_TOGGLE";

    /**
     * ★★★ 轮次76：设置里切换「显示余额气泡」后，让服务**立刻**应用（隐藏/恢复 + 重排轮询）。
     *
     * <p>为什么不复用 {@link #ACTION_RELOAD}：那会 `rebuildViews()`（重建整套视图、
     * 重播启动动作），而这里只需要改一个 View 的显隐 + 停/启轮询。
     */
    public static final String ACTION_BALANCE_VIS = "com.deepseek.pet.BALANCE_VIS";

    /** 服务是否存活。看门狗/自启模块据此判断要不要把桌宠拉回来 */
    public static volatile boolean isAlive = false;

    private static final String CH_ID = "deepseek_pet";
    /** 静默渠道 id：老用户渠道已建成 IMPORTANCE_LOW 且不可改，靠换 id 才降得下来。 */
    private static final String CH_ID_QUIET = "deepseek_pet_quiet";
    private static final int NOTI_ID = 1001;

    /** 基准尺寸(dp)，实际尺寸 = 基准 × scale */
    private static final int BASE_PET_W = 240;
    private static final int BASE_PET_H = 135;

    private WindowManager windowManager;
    private View root;
    private TextView text;
    private PetView petImage;
    private FrameLayout container;
    private WindowManager.LayoutParams params;
    /** 吸附位置竖条（细长竖条 UI，颜色跟随主题；平时 GONE，吸附态 VISIBLE）。 */
    private View dockStrip;

    // ==================== 游戏助手 · 系统监测条 ====================
    //
    // 用户要求（2026-10-07）：八项指标（CPU/GPU/RAM/FPS/温度/电池/功耗/网络）
    // 里自由选三项，分上/中/下三格显示：
    //   COLLAPSED（吸附态）→ 顶替 dockStrip 的槽位，竖着三格（与竖条同位置同操作）
    //   NORMAL / EXPANDED   → 桌宠**左或右**侧竖排三行小胶囊（贴哪边就显示在另一边）
    //
    // ★ 为什么展开态用**独立悬浮窗**而不是 container 的子 View：
    //   桌宠窗口的宽高是"就着桌宠量的"，旁边再挂一列就得把窗口加宽 ——
    //   而加宽会同时影响 clampToScreen / petRectOnScreen / 七个浮层的相对定位
    //   （本工程有一整套 verify_overlay_geometry 断言在钉这些几何）。
    //   独立窗口只按"桌宠屏幕矩形"算自己的位置，桌宠窗口一个像素都不动。
    //   （吸附态仍用 container 子 View —— 那里本来就有"顶替竖条"的既有机制。）
    //
    // 触摸一律透传（clickable=false / FLAG_NOT_TOUCHABLE），手势仍由 container 处理。

    /** 吸附态三格（上/中/下）；未启用时为 null。 */
    private LinearLayout monitorBar;
    private TextView monitorCellTop, monitorCellMid, monitorCellBottom;

    /** 展开态：独立悬浮窗里的三颗胶囊。 */
    private LinearLayout monitorSideRoot;
    private TextView monitorSideTop, monitorSideMid, monitorSideBottom;
    private WindowManager.LayoutParams monitorSideParams;
    private boolean monitorSideShowing = false;
    /**
     * 上一次真正下发的桌宠窗口 LayoutParams 快照（x/y/宽/高）。
     *
     * <p>拖动时 {@code applyRootLayout()} 每帧都被调用，而 {@code updateViewLayout}
     * 是一次跨进程布局请求 —— 参数没变就别发。任何一项变化都会让快照失配、
     * 照常下发，所以"改了宽度/flags 却漏更新"这种坑不会出现。
     */
    private int lastLpX = Integer.MIN_VALUE, lastLpY = Integer.MIN_VALUE;
    private int lastLpW = Integer.MIN_VALUE, lastLpH = Integer.MIN_VALUE;
    /** 侧栏上一次下发的位置（没变就不发窗口更新）。 */
    private int lastMonSideX = Integer.MIN_VALUE, lastMonSideY = Integer.MIN_VALUE;
    /** 余额气泡上一次下发的位置（同上）。 */
    private int lastBalX = Integer.MIN_VALUE, lastBalY = Integer.MIN_VALUE;
    /** 间距的像素缓存 + 读取时刻（拖动时每帧都算，不必每帧读 prefs）。 */
    private int monitorGapPx = Integer.MIN_VALUE;
    private long monitorGapReadAtMs = 0;
    /** 侧栏坐标日志的节流时刻（跟随路径每帧都会调，日志本身不能成为开销）。 */
    private long logMonSideAtMs = 0;
    /** 跟随的节流时刻 + 最小间隔（见 followMonitorSide）。 */
    private long monitorFollowAtMs = 0;
    private static final long MONITOR_FOLLOW_MIN_MS = 80;

    /** 最近一次采样里的指标（渲染用；null 值用 -1 表示"没取到"）。 */
    private SysMetrics.Snapshot monitorSnap = new SysMetrics.Snapshot();
    /** 最近一次采样里的前台包名（游戏名单门控用）。 */
    private String monitorPkg = null;
    /** 是否已经拿到过至少一次采样（决定吸附态用监测条还是退回普通竖条）。 */
    private boolean monitorReady = false;
    /** 本服务注册到采样器的回调（销毁时要摘掉）。 */
    private GameFpsMonitor.Callback fpsBadgeCb;
    /** 采样引用是否已持有（避免重复 acquire / 重复 release）。 */
    private boolean fpsAcquired = false;

    /**
     * 吸附槽位当前归谁：true = 监测条占着，false = 普通竖条。
     *
     * <p>★ 跟手收放（{@link #dockSlotShow}）要按它决定显隐哪一个 ——
     * 只认 {@code dockStrip} 的话，监测条占槽位时会出现
     * "拉过半监测条还挂着、拉回来两条叠在一起"（用户报的形态异常）。
     */
    private boolean dockSlotOwnerIsMonitor = false;

    /**
     * 监测条读数的新鲜度上限（毫秒）。
     *
     * <p>★ 超过它就不再显示手上那些数字，改显示 {@code --}。
     * 用户反馈的"卡住不动"最难受的地方不是没数，而是**有一组不动的旧数**——
     * 看着像游戏稳定 61 帧，其实早就没在采了。正常采样每秒一次，
     * 所以不会被误判成过期。
     */
    private static final long FPS_BADGE_STALE_MS = 5000;

    /**
     * 采样线程"卡死"判定（毫秒）：跑完一轮的间隔超过它就重启线程。
     *
     * <p>正常一轮 ≈ {@code INTERVAL_MS}(1s) + 一次 dumpsys 的耗时（实测 ~75ms）。
     * 取 15 秒是给慢设备留足余量 —— 只有**真卡住**（例如 shell 读取永久阻塞）
     * 才会命中。命中后重启线程，避免监测条永远冻住。
     */
    private static final long FPS_TICK_STALE_MS = 15000;

    /**
     * 吸附态竖条的宽度（dp）—— 只是**下限**，实际宽度按内容算。
     *
     * <p>★ 用户 2026-10-07 修订：吸附时**只显示一个数据**（中格）。
     * <p>★ 同日再修订（报障"吸附贴边时候数据显示不完整""增大显示面积"）：
     *   宽度**不再写死 20dp** —— 中格文本可能是 `5.2G` / `100%` / `12.3M/s`，
     *   写死时会被 setSingleLine 硬截掉半个字符。现在按实测文本宽度算，
     *   夹在 [20, 52] dp；判据本体是纯函数 {@link SysMetrics#stripWidthDp}
     *   （可桌面断言），露出宽度 {@link #dockPeekPx()} 同步跟着走 ——
     *   不然宽出来的部分会被屏幕边缘切掉，等于没加宽。
     */
    private static final float MONITOR_STRIP_W_DP = SysMetrics.STRIP_MIN_W_DP;

    /**
     * 监测条状态的轻量周期巡检（5 秒一次）。
     *
     * <p>★ 为什么需要它：监测条要不要显示取决于**外部**条件 ——
     * 用户开关（设置页改）、Shizuku 是否就绪（用户去激活/重启后变化）、
     * 以及"当前前台应用在不在勾选的游戏名单里"（前台应用随时在变）。
     * 只在 {@code buildViews}/{@code ACTION_RELOAD} 时同步的话，
     * "先开开关、后启动 Shizuku"这条常见路径上监测条永远不出来。
     * 这里只做状态判定（不跑 shell），开销可忽略。
     */
    private final Runnable fpsSyncTask = new Runnable() {
        @Override public void run() {
            try {
                ShizukuBridge.refresh(FloatingService.this);
                monitorSync();
            } catch (Throwable ignore) {}
            handler.postDelayed(this, 5000);
        }
    };


    // —— 对话气泡（独立悬浮窗，位置按桌宠动态计算，不溢出屏幕）——
    private final android.util.DisplayMetrics dmS = new android.util.DisplayMetrics();
    private android.widget.FrameLayout bubbleView;
    private LinearLayout bubbleBody;
    private View bubbleTail;
    private int lastBubbleThemeKey = -1;
    private TextView bubbleText;
    private android.widget.ImageView bubbleMeme;
    /** 视频表情的播放器（与 bubbleMeme 互斥显示；轮次86） */
    private android.widget.VideoView bubbleMemeVideo;
    private WindowManager.LayoutParams bubbleParams;
    private boolean bubbleShowing = false;

    // —— 提问输入条（独立悬浮窗，避免切进 App；位置跟随桌宠）——
    private View askRoot;
    private EditText askInput;
    private WindowManager.LayoutParams askParams;
    private boolean askShowing = false;

    // ==================== 语音输入（本机离线 SenseVoice 识别，全程悬浮窗，不联网）====================
    private static final String ACTION_VOICE  = "com.deepseek.pet.VOICE_RESULT"; // MicShareService→本服务
    private static final String ACTION_MIC_ASK = "com.deepseek.pet.MIC_ASK";     // App→本服务
    private static final String VCH_ID = "deepseek_pet_voice";
    private static final int VNOTI_ID = 1002;
    private View voiceRoot;
    private TextView voiceLabel;
    private WindowManager.LayoutParams voiceParams;
    private VoiceInputHelper voiceHelper;
    private boolean voiceShown = false;
    private boolean voiceBusy = false;
    private int voiceDot = 0;
    private Runnable voiceTicker;
    private boolean asrPreparing = false;          // 离线模型正在准备（释放/加载）

    private final Handler idleWhisper = new Handler(Looper.getMainLooper());
    private long lastSpeakMs = 0;                   // 上次说话结束/开始时间（待机低语用）
    private long lastEventSpeakMs = 0;              // 事件播报节流
    private TtsEngine tts;                           // 云端语音合成（让桌宠说话）
    private String lastPart = "";                   // 最近点击部位，用于联动反馈
    private boolean lastBatteryLowNotified = false;
    private boolean lastCharging = false;
    private final Handler eventWatch = new Handler(Looper.getMainLooper());
    private Runnable idleWhisperTask;
    private Runnable eventWatchTask;

    private android.widget.FrameLayout balRoot;   // 余额气泡外层容器（承载整体缩放）
    private float balScale = 1f;   // 余额气泡整体缩放倍数
    private WindowManager.LayoutParams balanceParams;   // 余额气泡独立悬浮窗
    private boolean isVisible = true;   // 余额气泡当前是否展示（只由点击切换，无任何自动隐藏计时）
    private boolean loading = false;
    /** 因 App 在前台而临时收起全部悬浮窗（不停止服务，退出 App 自动恢复） */
    private volatile boolean petTempHidden = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Handler proactive = new Handler(Looper.getMainLooper());
    private boolean proactiveBusy = false;   // 正在生成主动消息
    private long lastUserActMs = System.currentTimeMillis();  // 最近一次用户互动时间
    private long lastPetTouchMs = System.currentTimeMillis(); // 最近一次触碰/移动桌宠的时间
    // ---- 屏幕漫游 ----
    private final Handler wanderHandler = new Handler(Looper.getMainLooper());
    private boolean wandering = false;
    private int wanderTargetX = Integer.MIN_VALUE;
    private boolean wanderEnabled = false;  // 漫游开关（已取消漫游）

    private int  consecutiveIdleAsks = 0;   // 连续因为"久无互动"而主动的次数（避免刷屏）
    // ==================== 场景识别（主人在干嘛）====================
    private final Handler mood = new Handler(Looper.getMainLooper());
    private String moodLastPkg = null;         // 上次看到的前台包名
    private long moodSameSinceMs = 0;          // 当前包名连续开始的时刻
    private long moodLastAskMs = 0;            // 上次场景搭话时间（全局冷却）
    private boolean moodBusy = false;          // 正在生成场景搭话
    private boolean moodNotifiedNoPerm = false;// 未授权提示只弹一次
    private static final long MOOD_POLL_MS = 5 * 1000L;       // 每 5 秒探测一次前台（切换更灵敏）
    private String moodLastPkgPrev = null;
    // 点击 / 拖动 的内置冷却
    private long lastClickActMs = 0;
    private long lastDragActMs = 0;
    private static final long TAP_DRAG_CD_MS = 2000L;          // 2 秒
    /** 久未互动的判定阈值：对话和桌宠都超过这个安静时长 → 触发"问你在干嘛" */
    private static final long LONELY_MS = 10 * 60_000L;
    private static final long REFRESH_MS = 60000;          // 兜底轮询节拍
    /** 余额低于该金额时，气泡用醒目红色提示 */
    private static final double LOW_BALANCE_THRESHOLD = 1.0;
    private static final int COLOR_LOW_BAL = 0xFFD32F2F;
    private long lastBalFetchMs = 0;                        // 上次拉取余额的时间（节流用）
    private static final long BAL_FETCH_MIN_GAP_MS = 3000;  // 两次拉取的最小间隔，防连点刷接口

    // ==================== 贴边吸附（细长竖条 / 滑入展开） ====================
    //
    // 状态机 NORMAL / COLLAPSED / EXPANDED（见 PetDockState）：
    //   长按菜单"贴边吸附"（只能手动，无自动贴边）→ COLLAPSED（窗口大部藏出屏
    //   仅留细条 + 主题竖条，原形象压扁+半透明+暂停动画）；吸附态从边缘向内滑动
    //   → 跟手 progress，松手达标展开（EXPANDED，视觉=原始桌宠，行为=NORMAL），
    //   未达标回弹；任何态再次长按进菜单。
    //   吸附态长按竖条上下拖动 → 竖条（连同窗口）沿边缘移动，松手落定。
    // 与现有 edgeSnap（拖完近边缘吸附）的分工：edgeSnap 只决定"拖完贴不贴"，
    // 这里决定"贴边后的形态与行为"。COLLAPSED 下禁用点击/捏合，只认
    // 向内滑动 + 长按 + 竖条拖动，避免跟现有手势打架。
    private PetDockState dockState = PetDockState.NORMAL;
    private PetDockState.Edge dockEdge = PetDockState.Edge.RIGHT;
    /** 吸附前存档：NORMAL/EXPANDED 的窗口位置 + 形象 alpha/scale/container 高。 */
    private int dockSavedX, dockSavedY;
    private float dockSavedAlpha = 1f, dockSavedScaleX = 1f, dockSavedScaleY = 1f;
    private int dockSavedContH = 0;
    private boolean dockSavedValid = false;
    /** 跟手中（此时 idle 计时暂停）+ 当前跟手进度。 */
    private boolean dockSwiping = false;
    private float dockSwipeProgress = 0f;
    /** 跟手展开阈值 px（按密度算，默认 ~90dp）。 */
    private int dockExpandThresholdPx() {
        return (int) (getResources().getDisplayMetrics().density * 90 + 0.5f);
    }
    /**
     * 吸附态露出的细条宽。
     *
     * <p>★ 用户报障（2026-10-07）："吸附贴边时候数据显示不完整""打开性能监测的时候
     *   **增大显示面积**"。细条原本固定 26dp —— 恰好塞得下 20dp 的普通竖条；
     *   而监测条中格的文本（`5.2G` / `100%` / `12.3M/s`）更宽，**超出的部分会被
     *   屏幕边缘切掉**（窗口只露出这么多）。所以"露出多少"必须跟着内容走：
     *   监测条占槽位时露出 = 它实际需要的宽度 + 6dp 边距（26~58dp）。
     *
     * <p>判据用 {@link #monitorWantsStripSlot()} 而不是 {@code dockSlotOwnerIsMonitor}：
     *   贴边动画**开始前**就要算好目标位置，而那时 {@code dockShowStrip()} 还没跑
     *   （它在动画结束时才决定槽位归谁）—— 用"当前占用者"会得到 26dp，
     *   动画结束再画上宽竖条就又被切了。
     */
    private int dockPeekPx() {
        float d = getResources().getDisplayMetrics().density;
        float peekDp = 26f;
        try {
            if (monitorWantsStripSlot()) {
                float want = stripWidthDpFor(monitorMidText());
                float p = want + 6f;                       // 竖条自身的 6dp 边距
                if (p > SysMetrics.STRIP_MAX_W_DP + 6f) p = SysMetrics.STRIP_MAX_W_DP + 6f;
                if (p > peekDp) peekDp = p;
            }
        } catch (Throwable ignore) {}
        return (int) (d * peekDp + 0.5f);
    }
    private android.animation.ValueAnimator dockAnim; // 窗口 x 动画（同时只跑一个）
    /** 贴边后延迟播边缘提示的可取消任务（重建/展开时取消，避免对着空气播）。 */
    private final Runnable dockEdgeHintRunnable = new Runnable() {
        @Override public void run() {
            if (dockState == PetDockState.COLLAPSED) dockPlayEdgeHint();
        }
    };
    private final Runnable dockIdleTask = new Runnable() {
        @Override public void run() {
            long idleMs = 0;
            try { idleMs = Prefs.dockIdleMs(FloatingService.this); } catch (Throwable ignore) {}
            if (idleMs <= 0 || dockSwiping || longPressShowing) return;
            if (dockState == PetDockState.EXPANDED || dockState == PetDockState.NORMAL) {
                dockCollapse(true);
            }
        }
    };
    private void dockResetIdle() {
        handler.removeCallbacks(dockIdleTask);
        long idleMs = 0;
        try { idleMs = Prefs.dockIdleMs(FloatingService.this); } catch (Throwable ignore) {}
        if (idleMs > 0 && !dockSwiping && !longPressShowing) {
            handler.postDelayed(dockIdleTask, idleMs);
        }
    }
    private void dockCancelAnim() {
        if (dockAnim != null) {
            try { dockAnim.cancel(); } catch (Throwable ignore) {}
            dockAnim = null;
        }
        // ★ 提示动画也必须一起取消：它同样在写 params.x。
        //   旧代码只取消 dockAnim，连点时前后两个提示动画会并行写同一份
        //   params.x，且各自以"启动那一刻的 x"为基准回位 —— 每点一次就把
        //   偏移量累加一次，看起来就是"一直点竖条，竖条一点点飞出去"。
        dockHideHint();
    }
    /** 吸附态触摸跟踪（DOWN 记录，MOVE/UP 消费）。 */
    private boolean dockSwipeActive = false;
    /** 吸附态竖条拖动中（长按竖条后上下拖，改吸附位置）。 */
    private boolean dockStripDragging = false;
    private float dockStripLastRawY = 0f;
    /** DOWN 是否落在竖条上（竖条拖动的候选）。 */
    private boolean dockDownOnStrip = false;
    /** 吸附态竖条连点计数（防抖：连点只播一次提示，不叠动画）。 */
    private int dockStripTapCount = 0;
    /** 上次竖条点击时刻（连点/双击/三击判定）。 */
    private long dockStripLastTapMs = 0;
    /** 双击微弹中（竖条微微弹出停顿，判断是否拖拽拉出）。 */
    private boolean dockNudging = false;
    /** 双击微弹的超时回吸附任务。 */
    private final Runnable dockNudgeTimeout = new Runnable() {
        @Override public void run() {
            if (dockNudging && dockState == PetDockState.COLLAPSED) {
                dockNudging = false;
                dockSnapBack();
            }
        }
    };
    /** 三击整 peek 已取消：只保留双击悬停一档。 */
    /** 悬停目标露出比例（span 太小时兜底用），以及固定弹出位移（dp）。 */
    private static final float DOCK_HOVER_FRACTION = 0.30f;
    private static final int DOCK_HOVER_TRAVEL_DP = 24;
    /** 边缘提示动画（独立槽位：必须可取消，否则连点会累积位移把竖条"推飞"）。 */
    private android.animation.ValueAnimator dockHintAnim;

    /**
     * 双击竖条悬停：向内露出约三成身位并停住，等主人决定拉不拉出来。
     *
     * <p>悬停期间仍是 COLLAPSED：
     * <ul>
     *   <li>继续向内滑 → 松手达标即 {@link #dockExpand()}（放出来）；</li>
     *   <li>反向（向外）滑 → 松手即回吸附贴边（dockSnapBack）；</li>
     *   <li>1.2s 无动作 → 自动回吸附。</li>
     * </ul>
     * 重复双击只续时，不叠加位移（旧代码 first=false 时什么都不做，
     * 但计时照排，配合并行动画就会打架）。
     */
    private void dockStartNudge() {
        if (params == null || root == null || petImage == null) return;
        if (dockState != PetDockState.COLLAPSED) return;
        handler.removeCallbacks(dockNudgeTimeout);
        final float hoverP = dockHoverProgress();
        boolean first = !dockNudging;
        dockNudging = true;
        // ★ 不恢复渲染：悬停期间露出的只是"贴着边缘的竖条"，
        //   桌宠形象仍在屏外（它居中画在 630 宽的窗口里），再跑动画纯属白耗电。
        //   真正往外拖时由 MOVE 分支按进度恢复。
        if (first) {
            dockCancelAnim();
            android.animation.ValueAnimator nudge =
                    android.animation.ValueAnimator.ofFloat(0f, 1f);
            nudge.setDuration(180);
            nudge.setInterpolator(new android.view.animation.DecelerateInterpolator());
            nudge.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (params == null || root == null) return;
                    if (!dockNudging || dockState != PetDockState.COLLAPSED) return;
                    float t = (Float) a.getAnimatedValue();
                    // 统一走 dockApplySwipe：窗口位移与形象 alpha/scale 一套口径，
                    // 不再自己插值 params.x（两套口径并存就会互相覆盖、位置乱跳）。
                    dockApplySwipe(hoverP * t);
                }
            });
            nudge.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (!dockNudging || dockState != PetDockState.COLLAPSED) return;
                    dockSwipeProgress = hoverP;
                    dockApplySwipe(hoverP);
                }
            });
            dockAnim = nudge;
            nudge.start();
        } else {
            // 重复双击：只续时，并确保停在悬停位
            dockSwipeProgress = hoverP;
            dockApplySwipe(hoverP);
        }
        handler.postDelayed(dockNudgeTimeout, 1200);
    }

    /**
     * 悬停进度：把「竖条微微弹出」换算成跟手进度。
     *
     * <p>★ 为什么不能用身位比例：桌宠形象是**居中**画在 630 宽的窗口里的，
     * 窗口整体内移 30% 身位（约 190px）时，竖条会被一起拖离边缘很远，
     * 看起来像"整条飞出来了"，而不是"微微弹出"。这里改成固定的小位移
     * （{@link #DOCK_HOVER_TRAVEL_DP}），竖条只弹出一小截、仍贴着边缘，
     * 正好是"给你一个可以抓住往外拖的手柄"。
     */
    private float dockHoverProgress() {
        int baseX = dockEdgeX(dockEdge);
        int span = Math.abs(dockLandingX() - baseX);
        int travel = dp(DOCK_HOVER_TRAVEL_DP);
        if (span <= travel) return DOCK_HOVER_FRACTION;
        float p = travel / (float) span;
        if (p > 0.35f) p = 0.35f;   // 悬停不该接近全开
        if (p < 0.05f) p = 0.05f;
        return p;
    }

    /** 悬停打断：清计时并复位标志（UP 展开分支 / dockExpand 调用）。 */
    private void dockClearNudge() {
        handler.removeCallbacks(dockNudgeTimeout);
        dockNudging = false;
    }
    private float dockDownRawX = 0f, dockDownRawY = 0f;
    private int dockTouchSlop() {
        try { return android.view.ViewConfiguration.get(this).getScaledTouchSlop(); }
        catch (Throwable t) { return 16; }
    }

    private final BroadcastReceiver reloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Prefs.ACTION_ASK.equals(intent.getAction())) {
                final String q = intent.getStringExtra("q");
                if (q != null && q.trim().length() > 0) {
                    handler.post(new Runnable() {
                        @Override public void run() { askQuestion(q.trim()); }
                    });
                }
                return;
            }
            rebuildViews();
        }
    };

    /** 语音相关广播：转写结果 / 外部请求开始录音 */
    private final BroadcastReceiver voiceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            final String act = intent.getAction();
            if (ACTION_VOICE.equals(act)) {
                final String t = intent.getStringExtra("text");
                handler.post(new Runnable() { @Override public void run() { onVoiceResult(t); } });
            } else if (ACTION_MIC_ASK.equals(act)) {
                handler.post(new Runnable() { @Override public void run() { startVoiceFlow(); } });
            }
        }
    };

    @Override
    public void onCreate() {
        ApiClient.init(this);
        ApiClient.setBaseUrl(Prefs.getBaseUrl(this));
        super.onCreate();
        isAlive = true;
        startForegroundSafely();
        // 服务真的起来了 => 用户希望桌宠运行，允许下次开机自启把它唤回
        try { Prefs.setPetShouldRun(this, true); } catch (Throwable ignore) {}
        // 启动看门狗：运行期间持续巡检，被系统清掉后自愈
        try {
            if (Prefs.keepAliveEnabled(this)) {
                KeepAlive.scheduleWatchdog(this, Prefs.keepAliveIntervalMs(this));
            }
        } catch (Throwable ignore) {}
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 云端语音合成（让桌宠说话）：与气泡联动，音频真正开始播放时才驱动口型
        tts = new TtsEngine(this);
        tts.setMouthListener(new TtsEngine.MouthListener() {
            @Override public void onMouthStart() {
                if (petImage != null) petImage.setTalking(true);
            }
            @Override public void onMouthStop() {
                if (petImage != null) petImage.setTalking(false);
            }
        });
        // 首次失败时提示一次：让用户知道「开关开了但念不出来」，而不是以为功能坏了
        tts.setErrorListener(new TtsEngine.ErrorListener() {
            @Override public void onError(String reason) {
                showBubble("\uD83D\uDD07 语音合成失败：" + reason, 6000);
            }
        });
        // ★ P5：升级路径 —— 把 P5 之前的**全局**形象搬进当前角色的形象目录。
        //   必须赶在 buildViews()（里面 applyPetDrawable 就会取形象）**之前**：
        //   晚一步这一轮就会先用"没有形象"的内置包渲染一次，用户看到闪一下。
        //   幂等，见 Prefs.migrateLegacyAppearance / AppearanceStore.migrateLegacyIn。
        try { Prefs.migrateLegacyAppearance(this); } catch (Throwable ignore) {}
        buildViews();
        // ★ 桌宠真正挂起来了 => 把本 app 从最近任务里移除（见方法注释）
        kickSelfFromRecents();
        // 游戏助手：挂 Shizuku 监听 + 启动挂牌巡检
        try { ShizukuBridge.hook(this); } catch (Throwable ignore) {}
        handler.removeCallbacks(fpsSyncTask);
        handler.postDelayed(fpsSyncTask, 1500);
        // ★ 性能优化：按档位注入帧率上限，并注册熄屏监听
        applyPerfSettings();
        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            sf.addAction(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, sf, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, sf);
            }
        } catch (Throwable ignore) {}
        IntentFilter f = new IntentFilter(ACTION_RELOAD);
        f.addAction(Prefs.ACTION_ASK);
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ 注册非系统广播接收器必须显式声明导出标志
            registerReceiver(reloadReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reloadReceiver, f);
        }
        // 语音：转写结果 / 开始录音 广播
        createVoiceChannel();        IntentFilter vf = new IntentFilter(ACTION_VOICE);
        vf.addAction(ACTION_MIC_ASK);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(voiceReceiver, vf, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(voiceReceiver, vf);
            }
        } catch (Exception ignore) {}
        VoiceInputHelper.preloadAsync(this);   // 后台释放+加载离线语音模型，主人一点即可说
        startLoop();
        startProactiveLoop();
        scheduleStartupRecall();
        startMoodLoop();
    }



    /** 桌宠启动后，若记得之前聊过，隔几秒在气泡里自然接一句（跨重启延续话题） */
    private void scheduleStartupRecall() {
        if (localOnly()) return;   // 纯本地模式不联网
        if (!Prefs.chatEnabled(this) || !chatBackendReady()) return;
        if (ChatMemory.size(this) == 0) return;
        // 记忆里没有用户消息（只有桌宠自说自话）就不打扰
        boolean hasUser = false;
        for (String[] h : ChatMemory.load(this)) {
            if ("user".equals(h[0])) { hasUser = true; break; }
        }
        if (!hasUser) return;
        // 每次启动只接一句，避免刷屏
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                recallStartupGreeting();
            }
        }, 4000);
    }

    private boolean startupRecallDone = false;
    private boolean startupStretchDone = false;
    /** 最近一次 buildViews 完成时刻，用于区分「新建」与「重启」，避免重复播启动表现 */
    private long viewsBuiltAtMs = 0L;

    /** 启动问候：轻量地引用上次聊过的事，像「你回来啦～上次你说的那事……」。 */
    private void recallStartupGreeting() {
        if (startupRecallDone) return;
        if (!Prefs.greetingOn(this)) return;   // 主人关掉了启动/退出问候语
        startupRecallDone = true;
        if (localOnly()) return;   // 纯本地模式不联网
        if (!Prefs.chatEnabled(this) || !chatBackendReady()) return;
        if (ChatMemory.size(this) == 0) return;

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final java.util.List<String[]> ctx = ChatMemory.recent(this, ChatMemory.ctxLimit(this));
        final String cue = "（你（桌宠）刚启动，主人回来了。请结合之前和主人的对话，"
                + "主动打个招呼并自然地提一句上次聊过的事，语气符合你的性格，"
                + "控制在25字以内，不要加引号、不要解释。）";
        new Thread(new Runnable() {
            @Override public void run() {
                hintLocalLoadingIfNeeded();          // 本地后端首次要载入 ⇒ 先把等待反馈显出来
                final ApiClient.ChatResult res =
                        ChatRouter.chat(FloatingService.this, apiKey, model, persona, ctx, cue, null);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (res != null && res.ok && res.reply != null
                                && res.reply.trim().length() > 0) {
                            ChatMemory.append(FloatingService.this, "assistant", res.reply.trim());
                            showPetSaySplit("💬 ", res.reply.trim(), resolveMeme(res.memeName), 12000);
                        }
                    }
                });
            }
        }, "pet-startup-recall").start();
    }


    /** 首次构建悬浮窗（含位置初始化） */
    private void buildViews() {
        // 余额气泡显隐：读上次状态即可（点一下切换一次，显示后一直常驻，不会自动消失）
        // ★★ 轮次76：设置里的**总开关**关掉时，无论上次状态如何都不显示。
        isVisible = Prefs.balanceBubbleOn(this) && Prefs.showBalance(this);
        startupStretchDone = false;   // 每次启动都重新触发「伸懒腰 + 睡醒问候」
        float d = getResources().getDisplayMetrics().density;
        float scale = Prefs.getScale(this);

        int petW = (int) (d * BASE_PET_W * scale);
        int petH = (int) (d * BASE_PET_H * scale);
        int bubbleW = (int) (d * 240 * scale);

        container = new FrameLayout(this);
        int w = petW;   // 容器只装桌宠本体，宽度即桌宠宽度，保证能贴边
        int h = petH;   // 容器只装桌宠本体，余额气泡改为独立悬浮窗，避免遮挡
        container.setLayoutParams(new ViewGroup.LayoutParams(w, h));

        // 角色视图：内置为 PetView（会自己动），也可显示自定义图片/动图/模型包
        petImage = new PetView(this);
        petImage.setScaleType(Prefs.keepRatio(this)
                ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.FIT_XY);
        applyPetDrawable();
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(petW, petH);
        glp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        container.addView(petImage, glp);

        // ★ 吸附位置竖条：细长竖条 UI，吸附后贴在窗口边缘指示位置。
        //   平时 GONE（不占触摸不占布局）；dockCollapse() 里按边摆好并 VISIBLE，
        //   dockExpand()/rebuildViews() 里 GONE。颜色跟随主题（dockStripBg
        //   每次显示时重建 Drawable，用主题 accent 实心胶囊，四套主题自动跟随）。
        dockStrip = new View(this);
        dockStrip.setVisibility(View.GONE);
        dockStrip.setClickable(false);
        dockStrip.setFocusable(false);
        container.addView(dockStrip);

        // 监测条（吸附态三格）：与竖条同层、同样触摸透传。
        // 平时 GONE；启用且"当前前台应用在勾选的游戏名单里"时，
        // 在 COLLAPSED 态**顶替** dockStrip（见 dockShowStrip）；
        // NORMAL/EXPANDED 态改用独立悬浮窗挂在桌宠侧面（见 monitorSideShow）。
        monitorBar = new LinearLayout(this);
        monitorBar.setOrientation(LinearLayout.VERTICAL);
        monitorBar.setVisibility(View.GONE);
        monitorBar.setClickable(false);
        monitorBar.setFocusable(false);
        monitorBar.setGravity(Gravity.CENTER);
        monitorCellTop = newMonitorCell();
        monitorCellMid = newMonitorCell();
        monitorCellBottom = newMonitorCell();
        monitorBar.addView(monitorCellTop);
        monitorBar.addView(monitorCellMid);
        monitorBar.addView(monitorCellBottom);
        container.addView(monitorBar);
        monitorSync();

        // 注：WindowManager 按 **addView 的先后**决定层级（先加的在下）。
        // 这里先 add 了桌宠形象，余额气泡在下面几行 add —— 顺序反了会让气泡
        // 被糊住。曾有个"自定义桌面背景"层也参与这个顺序，该功能已按用户要求
        // 撤除（改成给 App 自身界面加 Mesh 背景），但这条顺序约束仍然成立。

        // 余额气泡：独立悬浮窗（不遮挡桌宠，靠边自动夹紧）
        text = new TextView(this);
        // ★ 文字色与底板都改为主题令牌。
        //   原来写死 0xFF1B3A6B（深蓝）+ R.drawable.bubble_balance（XML 静态浅色），
        //   深色主题下是"白板 + 深蓝字"，与其它浮层不一致。
        //   注意：setBalanceText 还会按"余额是否偏低"改文字色（见 setBalanceText），
        //   所以这里的初值只是占位，真正上色在 setBalanceText 里做。
        text.setTextColor(uiT().text1);
        setBalanceText("余额：查询中…");
        balScale = Prefs.getBalanceScale(this);
        text.setTextSize(12);
        text.setGravity(Gravity.CENTER);
        // 与气泡/菜单同一套浮层底板
        text.setBackground(floatPanelBg(THEME_RADIUS_DP));
        int padH = (int) (d * 24);          // 内边距 ≥ 圆角，圆角处不切字
        int padTop = (int) (d * 12);
        int padBottom = (int) (d * 12);
        text.setPadding(padH, padTop, padH, padBottom);
        text.setIncludeFontPadding(false);
        text.setMinHeight((int) (d * 40));
        // 整体等比缩放：背景面板 + 文字 + 内边距一起放大/缩小（而非只放大字体）
        text.setPivotX(0f);
        text.setPivotY(0f);
        text.setScaleX(balScale);
        text.setScaleY(balScale);
        // 外层容器按“原始尺寸 × balScale”撑开，保证缩放后的气泡完整显示、不被悬浮窗裁剪
        balRoot = new FrameLayout(this);
        balRoot.addView(text, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (balanceParams == null) {
            balanceParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            balanceParams.gravity = Gravity.TOP | Gravity.LEFT;
            applyCutoutMode(balanceParams);   // 与桌宠同坐标系，否则横屏会错位
            try { windowManager.addView(balRoot, balanceParams); } catch (Exception ignore) {}
        }
        final boolean[] balSized = {false};
        text.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                                 int ol, int ot, int orr, int ob) {
                if (balSized[0]) return;
                final int w0 = r - l, h0 = b - t;
                if (w0 <= 0 || h0 <= 0 || balanceParams == null || balRoot == null) return;
                balSized[0] = true;
                // 固定文字视图为原始尺寸，避免被外层撑大而导致二次缩放
                text.setLayoutParams(new FrameLayout.LayoutParams(w0, h0));
                balanceParams.width = Math.round(w0 * balScale);
                balanceParams.height = Math.round(h0 * balScale);
                balRoot.post(new Runnable() {
                    @Override public void run() {
                        try { windowManager.updateViewLayout(balRoot, balanceParams); } catch (Exception ignore) {}
                        positionBalance();
                        if (longPressShowing) positionLongPressBubble();
                    }
                });
            }
        });
        balRoot.setVisibility(isVisible ? View.VISIBLE : View.GONE);

        // 透明度已由 applyPetDrawable 设置

        root = container;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        // ★ 允许覆盖挖孔区：否则横屏时窗口被内缩，贴边坐标与可视区对不上
        //   （一边陷进去、一边贴不到边）——见 applyCutoutMode 的说明。
        applyCutoutMode(params);
        params.x = Prefs.getPosX(this);
        params.y = Prefs.getPosY(this);
        try {
            windowManager.addView(root, params);
            // 启动后立即钳制，防止上次保存的位置在屏幕外导致桌宠看不见
            clampPositionsAfterLayout();
        } catch (Exception e) {
            Toast.makeText(this,
                    "无法显示桌宠：请在设置中授予「显示在其他应用上层」权限",
                    Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        root.setOnTouchListener(new PinchTouchListener());
        startBreathing();
        startWander();
        refresh();
        dockResetIdle(); // 启动即排 idle 自动贴边（8s 无操作 → 贴边 + 主题竖条）

        viewsBuiltAtMs = System.currentTimeMillis();

        // 启动表现：先来个「超大伸懒腰」，再按当前时段说一句「刚睡醒」的问候
        handler.postDelayed(new Runnable() {
            @Override public void run() { playStartupGreeting(); }
        }, 1200);
    }

    /** 支持拖动 + 单击切换气泡 + 长按菜单 + 双指捏合缩放 */
    private class PinchTouchListener implements View.OnTouchListener {
        float initialTouchX, initialTouchY;
        float lastTouchX, lastTouchY;
        long downTime;
        boolean moved;
        boolean lifted;   // 是否已触发"被拎起"反馈
        // ★ 轮次83：双击/三击判定抽到外层类（dispatchPetTap），时间戳也提到服务字段
        // 捏合
        boolean pinching;
        float initialDist;
        float initialScale;

        private float dist(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0;
            float dx = e.getX(0) - e.getX(1);
            float dy = e.getY(0) - e.getY(1);
            return (float) Math.hypot(dx, dy);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    stopWander();
                    // ★★ 轮次61：**用户碰它 = 最高优先级** —— 手点动作的锁也让路
                    //   （锁是拿来挡**自动**场景的，不是拿来挡用户的；不解锁的话
                    //    手点动作播放期间点一下桌宠会"没反应"）
                    if (petImage != null) petImage.unlockAction();
                    initialTouchX = lastTouchX = event.getRawX();
                    initialTouchY = lastTouchY = event.getRawY();
                    downTime = System.currentTimeMillis();
                    moved = false;
                    pinching = false;
                    lifted = false;
                    // ★ 轮次77：探头态优先 —— 点一下 = 抬头看人，挪动 = 收回去。
                    //   不复位 petImage 的 rotation/scale：那是探头姿态，一按就复位会闪。
                    if (peekPhase != PeekPose.OFF) {
                        peekDownMoved = false;
                        peekDragging = false;
                        peekDownRawX = event.getRawX();
                        peekDownRawY = event.getRawY();
                        lastTouchX = event.getRawX();
                        lastTouchY = event.getRawY();
                        // ★ 手一按就先**冻住时间轴**：呼吸/小探头每 16ms 都在写 PetView 的
                        //   rotation/scale/pivot，真机实测这会把正在进行的手势打断
                        //   （DOWN 之后只来得到 2 个 MOVE 就没了）。冻结后姿态由手势决定。
                        peekFreeze();
                        return true;
                    }
                    // 吸附态：只记录按点，不复位 petImage 缩放/旋转
                    // （COLLAPSED 下形象是压扁态，ACTION_DOWN 就复位会闪一下）。
                    if (dockState == PetDockState.COLLAPSED) {
                        dockSwipeActive = false;
                        dockSwiping = false;
                        dockStripDragging = false;
                        dockDownRawX = event.getRawX();
                        dockDownRawY = event.getRawY();
                        dockStripLastRawY = event.getRawY();
                        // 按在竖条上 → 可能是"长按竖条拖动改位置"，
                        // 先记下来，MOVE 超过 slop 且以竖向为主即转入竖条拖动。
                        dockDownOnStrip = dockHitStrip(event.getRawX(), event.getRawY());
                        return true;
                    }
                    if (petImage != null) {
                        petImage.animate().cancel();
                        petImage.setRotation(0f);
                        petImage.setTranslationY(0f);
                        float bs = Prefs.getScale(FloatingService.this);
                        petImage.setScaleX(bs);
                        petImage.setScaleY(bs);
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    // 吸附态禁用捏合：第二指直接忽略
                    if (dockState == PetDockState.COLLAPSED) return true;
                    if (event.getPointerCount() >= 2) {
                        pinching = true;
                        initialDist = dist(event);
                        initialScale = Prefs.getScale(FloatingService.this);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    // ★ 轮次77：探头态下**拖动即接管**（跟手）——
                    //   旧写法是"一动就 cancel、走 280ms 收回动画"，观感是"闪一下才轮到你拖"。
                    //   现在手指按哪儿它就露多少：窗口跟着手指走，露头量由窗口 x 反推。
                    if (peekPhase != PeekPose.OFF) {
                        float adxP = Math.abs(event.getRawX() - peekDownRawX);
                        float adyP = Math.abs(event.getRawY() - peekDownRawY);
                        if (!peekDragging && (adxP > dockTouchSlop() || adyP > dockTouchSlop())) {
                            peekBeginDrag();
                        }
                        if (peekDragging) {
                            lastTouchX = event.getRawX();
                            lastTouchY = event.getRawY();
                            peekDragStep(event.getRawX(), event.getRawY());
                        }
                        return true;
                    }
                    // —— 吸附态：竖条拖动（改吸附位置）优先，其次边缘向内滑动 ——
                    // 左贴边 → 向内 = dx > 0；右贴边 → 向内 = dx < 0；
                    // 反向/捏合一律忽略（不取消长按判定，由 UP 统一处理）。
                    if (dockState == PetDockState.COLLAPSED) {
                        float totalDx = event.getRawX() - dockDownRawX;
                        float totalDy = event.getRawY() - dockDownRawY;
                        float adx = Math.abs(totalDx);
                        float ady = Math.abs(totalDy);
                        // ① 竖条拖动：按在竖条上 + 竖向为主 + 超 slop → 沿边缘上下搬窗口
                        if (!dockSwipeActive && !dockStripDragging && dockDownOnStrip
                                && ady > adx && ady > dockTouchSlop()) {
                            dockStripDragging = true;
                            dockStripLastRawY = event.getRawY();
                            handler.removeCallbacks(dockIdleTask);
                            // ★ 拖动竖条时恢复渲染：能看到桌宠边缘跟着走，
                            //   落定后重新暂停（见 UP 落定分支）。
                            try { dockSetRenderPaused(false); } catch (Throwable ignore) {}
                        }
                        if (dockStripDragging) {
                            float dy = event.getRawY() - dockStripLastRawY;
                            dockStripLastRawY = event.getRawY();
                            dockMoveStrip((int) dy);
                            return true;
                        }
                        boolean inward = (dockEdge == PetDockState.Edge.LEFT)
                                ? (totalDx > 0) : (totalDx < 0);
                        boolean horizontal = adx > ady && adx > dockTouchSlop();
                        if (!dockSwipeActive) {
                            // 悬停中允许**双向**跟手：向内=继续拉出来，反向=缩回贴边
                            boolean hoverActive = dockNudging;
                            if (horizontal && (inward || hoverActive)) {
                                dockSwipeActive = true;
                                dockSwiping = true;
                                handler.removeCallbacks(dockIdleTask);
                                // 手指接管位置：取消悬停的自动回弹计时，
                                // 否则计时到点会和手指抢 params.x。
                                if (hoverActive) handler.removeCallbacks(dockNudgeTimeout);
                            } else {
                                return true; // slop 内 / 反方向：等 UP 判长按或忽略
                            }
                        }
                        if (dockSwipeActive) {
                            // 悬停中：进度从悬停位起算；反向为负 → 回落 → 松手回吸附
                            float base = dockNudging ? dockHoverProgress() : 0f;
                            float signed = inward ? adx : -adx;
                            float p = base + signed / dockExpandThresholdPx();
                            if (p < 0f) p = 0f;
                            if (p > 1f) p = 1f;
                            dockSwipeProgress = p;
                            // ★ 跟手即恢复渲染：手指拉出来一半看不到脸等于白拉；
                            //   回弹落定/超时回吸附时重新暂停（见 dockSnapBack/dockNudgeTimeout）。
                            if (p > 0.02f) {
                                try { dockSetRenderPaused(false); } catch (Throwable ignore) {}
                            }
                            dockApplySwipe(p);
                        }
                        return true;
                    }
                    if (pinching && event.getPointerCount() >= 2) {
                        float dd = dist(event);
                        if (initialDist > 0) {
                            float s = initialScale * (dd / initialDist);
                            Prefs.saveScale(FloatingService.this, s);
                            applyScaleLive();
                        }
                        return true;
                    }
                    float dx = event.getRawX() - lastTouchX;
                    float dy = event.getRawY() - lastTouchY;
                    if (Math.abs(event.getRawX() - initialTouchX) > 12
                            || Math.abs(event.getRawY() - initialTouchY) > 12) {
                        moved = true;
                    }
                    if (moved) {
                        lastPetTouchMs = System.currentTimeMillis();
                        if (petImage != null) petImage.setDragging(true, (float) Math.max(-1, Math.min(1, dx / 8f)));
                        if (petImage != null && petImage.isAnimPack()) petImage.setScene(SceneRegistry.DRAG); // ★ 拖动走 drag 场景（改造前这里被特意改成 idle，drag 动作因此从没播过）
                        if (!lifted) { lifted = true; petLiftFeedback(); petLiftTalk(); }
                        params.x += (int) dx;
                        params.y += (int) dy;
                        clampToScreen();
                        if (actionShowing) positionActionBubble();
                        applyRootLayout();
                        dockResetIdle(); // NORMAL/EXPANDED 下拖动续 idle 计时
                        // 移动时同步跟随：气泡/输入条贴在桌宠旁
                        if (bubbleShowing) positionBubble();
                        positionBalance();
                        if (longPressShowing) positionLongPressBubble();
                        if (askShowing) {
                            positionAskBar();
                            try { windowManager.updateViewLayout(askRoot, askParams); } catch (Exception ignore) {}
                        }
                    }
                    lastTouchX = event.getRawX();
                    lastTouchY = event.getRawY();
                    return true;
                }

                case MotionEvent.ACTION_CANCEL: {
                    // ★ 手势被系统抢走（手势导航的"边缘返回"就会这样：
                    //   InputDispatcher: edge-swipe is stealing input gesture）——
                    //   把拖动状态收干净：正在拖就按"当前露出量"决定回挂还是退出。
                    //   不收拾的话 peekDragging 会一直为 true，下一次手势就乱套。
                    if (peekPhase != PeekPose.OFF) {
                        boolean wasDragging = peekDragging;
                        peekDragging = false;
                        peekDownMoved = false;
                        if (wasDragging) peekEndDrag();
                    }
                    return true;
                }

                case MotionEvent.ACTION_POINTER_UP:
                    // 松开一根手指，结束捏合，避免跳变
                    pinching = false;
                    // 保存最终缩放
                    Prefs.saveScale(FloatingService.this, Prefs.getScale(FloatingService.this));
                    return true;

                case MotionEvent.ACTION_UP: {
                    if (pinching) { pinching = false; return true; }
                    // ★ 轮次77：探头态抬手 —— 没挪动就是"点了一下"或"长按"：
                    //   长按（>800ms）照常出长按菜单（挂边时菜单必须够得着，否则用户
                    //   没法切动作/换角色/拉出来）；短按则抬头站直看人。
                    //   挪动过的那条路已在 MOVE 里 cancel 掉了（peekLift 对 WITHDRAW 是空操作）
                    if (peekPhase != PeekPose.OFF) {
                        boolean peekWasDragging = peekDragging;
                        peekDragging = false;
                        if (peekWasDragging) {
                            // 拖过 ⇒ 按"拖到哪儿"决定：拉够了就出来，没拉够自己挂回去
                            peekEndDrag();
                        } else if (!peekDownMoved) {
                            long heldPeek = System.currentTimeMillis() - downTime;
                            // ★★ 轮次83（用户报「贴边时无法使用菜单以及对话」）：
                            //   轮次82 只共用了"单击"，双击/三击的判定还留在普通分支里
                            //   ⇒ 挂边时打不开「问一句」输入条、也打不开三击的动作菜单
                            //   （AI 对话入口就在动作菜单里）。现在整段分派共用。
                            long cdMsP = Prefs.getInteractCdMs(FloatingService.this);
                            boolean cdBlockedP = cdMsP > 0
                                    && (System.currentTimeMillis() - lastClickActMs) < cdMsP;
                            lastClickActMs = System.currentTimeMillis();
                            dispatchPetTap(event, heldPeek, false, cdBlockedP);
                        }
                        peekDownMoved = false;
                        lastPetTouchMs = System.currentTimeMillis();
                        lastUserActMs = System.currentTimeMillis();
                        return true;
                    }
                    // —— 吸附态 UP：竖条拖动落定 / 跟手展开回弹 / 长按进菜单 ——
                    if (dockState == PetDockState.COLLAPSED) {
                        long heldDock = System.currentTimeMillis() - downTime;
                        float totalDxDock = event.getRawX() - dockDownRawX;
                        boolean inwardDock = (dockEdge == PetDockState.Edge.LEFT)
                                ? (totalDxDock > 0) : (totalDxDock < 0);
                        // ① 竖条拖动落定：停在当前位置（已沿边缘钳制）。
                        // ★ 竖条只能沿边缘上下搬，所以**只更新存档的 Y**；
                        //   存档的 X 必须保持"桌宠原来在屏内的横向落点" ——
                        //   旧代码在这里把 params.x（此刻等于贴边的边缘 x，可能在屏幕外）
                        //   写进 dockSavedX，于是之后展开/悬停都朝那个边缘位算：
                        //   展开目标 = 边缘位 → 桌宠出不来（看起来"双击就消失了"），
                        //   悬停 span = 0 → 双击毫无反应。
                        if (dockStripDragging) {
                            dockStripDragging = false;
                            dockDownOnStrip = false;
                            dockSwipeActive = false;
                            dockSwiping = false;
                            dockSwipeProgress = 0f;
                            dockStripTapCount = 0; // 拖动不算点击，清连点计数
                            lastPetTouchMs = System.currentTimeMillis();
                            lastUserActMs = System.currentTimeMillis();
                            dockSavedY = params.y;         // 纵向跟随：拖到哪就在哪露出
                            if (!dockSavedValid) {         // 无存档（异常路径）才补一份完整存档
                                dockSavedX = dockPeekedX(dockEdge);
                                dockSavedValid = true;
                            }
                            Prefs.savePos(FloatingService.this, params.x, params.y);
                            positionBalance();
                            // ★ 落定重新暂停渲染（拖动时已恢复，见 MOVE 分支）
                            try { dockSetRenderPaused(true); } catch (Throwable ignore) {}
                            dockResetIdle();
                            return true;
                        }
                        if (dockSwipeActive) {
                            dockSwipeActive = false;
                            dockSwiping = false;
                            float p = dockSwipeProgress;
                            dockClearNudge(); // 悬停中拉出/缩回 → 都由本次抬手定夺
                            // 方向二次校验 + 阈值：向内且达标 → 展开；其余（含反向）
                            // → 回弹贴边。悬停起点 p≈0.30，再往内拉一点即达标。
                            if (inwardDock && p >= 0.35f) {
                                dockSwipeProgress = 0f;
                                dockExpand();
                            } else {
                                // ★ 不清 dockSwipeProgress：dockSnapBack 用它当起点，
                                //   才能从"手指松开时的位置"平滑滑回边缘，而不是瞬移。
                                dockSnapBack();
                            }
                            return true;
                        }
                        dockSwipeActive = false;
                        dockSwiping = false;
                        lastPetTouchMs = System.currentTimeMillis();
                        lastUserActMs = System.currentTimeMillis();
                        if (heldDock > 800) {
                            // 吸附态长按 → 同样进菜单（菜单里有"展开 / 恢复"；
                            // 按在竖条上的长按同样进菜单，短竖拖则走上面的落定分支）
                            dockDownOnStrip = false;
                            dockStripTapCount = 0;
                            showLongPressBubble();
                        } else {
                            // 吸附态点击竖条（只分单击 / 双击两档，三击已取消）：
                            //  单击：边缘提示动画（暗示可滑出），连点防抖只播一次；
                            //  双击（350ms 内两击）：悬停 —— 向内露出约三成身位停住，
                            //     随后"向内拉 → 放出来 / 反向滑 → 回吸附 / 1.2s 无动作 → 回吸附"。
                            //  轻点非竖条区：边缘提示动画。
                            long nowTap = System.currentTimeMillis();
                            boolean onStrip = dockDownOnStrip;
                            dockDownOnStrip = false;
                            if (onStrip) {
                                // ★ 连点计数衰减：与上次点击间隔超 1s 则清零，
                                //   否则隔很久再点一次会被误判成"双击"。
                                if ((nowTap - dockStripLastTapMs) >= 1000) dockStripTapCount = 0;
                                boolean doubleTap = (nowTap - dockStripLastTapMs) < 350
                                        && dockStripTapCount >= 1;
                                dockStripLastTapMs = nowTap;
                                if (doubleTap) {
                                    dockStripTapCount = 0;
                                    dockStartNudge();
                                } else {
                                    // 单击：只播提示，且提示期间不接受新提示（防叠加）
                                    dockStripTapCount = 1;
                                    dockPlayEdgeHint();
                                }
                            } else {
                                dockStripTapCount = 0;
                                dockPlayEdgeHint();
                            }
                            dockResetIdle();
                        }
                        return true;
                    }
                    long held = System.currentTimeMillis() - downTime;
                    long nowUp = System.currentTimeMillis();
                    // 内置 CD（时长可调）：拦截过密的互动反馈（点击说话 / 拖动落地反馈）
                    long cdMs = Prefs.getInteractCdMs(FloatingService.this);
                    boolean actCdBlocked = cdMs > 0 && (nowUp - lastClickActMs) < cdMs;
                    lastClickActMs = nowUp;
                    // 无论点还是拖，都算"主人碰过桌宠了"
                    lastPetTouchMs = System.currentTimeMillis();
                    lastUserActMs = System.currentTimeMillis();
                    dockResetIdle(); // NORMAL/EXPANDED 下任何抬手都续 idle 计时
                    // 只顺手刷新一次余额/用量；不再这里强制亮出余额气泡——
                    // 气泡显隐统一由「单击桌宠」切换（见下面单击分支）。
                    maybeInstantBalanceRefresh();
                    consecutiveIdleAsks = 0;
                    if (moved) {
                        // 放下：落地弹一下
                        if (petImage != null) petImage.setDragging(false, 0f);
                        if (petImage != null && petImage.isAnimPack()) petImage.setAnimState("idle");
                        if (!actCdBlocked) petDropFeedback();   // CD 内不重复落地反馈
                        if (Prefs.edgeSnap(FloatingService.this) && nearScreenEdge()) {
                            // 仅在“靠近边缘”时吸附，其余情况可自由停在屏幕中间
                            snapToNearestEdge();
                        } else {
                            clampToScreen();
                            applyRootLayout();
                            Prefs.savePos(FloatingService.this, params.x, params.y);
                            positionBalance();
                            if (longPressShowing) positionLongPressBubble();
                            if (bubbleShowing) positionBubble();
                            if (actionShowing) positionActionBubble();
                            if (longPressShowing) positionLongPressBubble();
                        }
                    } else {
                        clampToScreen();
                        applyRootLayout();
                        Prefs.savePos(FloatingService.this, params.x, params.y);
                        positionBalance();
                        if (longPressShowing) positionLongPressBubble();
                        // 长按→功能气泡；双击→问一句；三击→动作菜单；单击→点击反馈
                        // （轮次83：整段分派抽成方法，挂边态也走这里）
                        dispatchPetTap(event, held, true, actCdBlocked);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== 点击说话 ====================

    private long lastTapTalkMs = 0;
    /** 上次单击时刻（双击/三击判定用；轮次83 从内部类提上来，供 dispatchPetTap 共用） */
    private long lastTapTime = 0;
    private boolean triplePend = false;   // 三击判定：双击待定中

/** 单击桌宠：随机切一个动作，并弹出该动作对应的台词 */
    private void tapRandomActionWithLine() {
        String id = null;
        if (petImage != null && petImage.isAnimPack()) {
            id = petImage.playRandomActionOnce();
        }
        String line = (id != null && petImage != null) ? petImage.animLine(id) : null;
        if (line == null || line.isEmpty()) line = pickTapQuip();
        showPetSay("💬 ", line, 6000, null);
        ChatMemory.append(this, "assistant", line);
    }

    /**
     * 点击/长按的**唯一分派**（轮次83 抽出）：长按→功能气泡；双击→问一句；三击→动作菜单；单击→点击反馈。
     *
     * <p>★ 为什么必须共用：挂边的 UP 分支曾经自己写了一套（只有"长按 / 单击"），
     * 结果**双击打不开问一句、三击打不开动作菜单** —— 而 AI 对话的入口就在动作菜单里，
     * 于是用户看到的就是"贴边之后菜单和对话都用不了"。判定逻辑只许有一份。
     *
     * @param allowBalanceToggle 普通态 true（保留"单击切余额气泡"）；挂边态 false
     */
    private void dispatchPetTap(android.view.MotionEvent event, long held,
                                boolean allowBalanceToggle, boolean cdBlocked) {
        // 挂边态：短按（非长按）先抬头，再走下面的分派
        if (held <= 800 && peekPhase != PeekPose.OFF) peekLift();
        if (held > 800) {
            // 长按 → 弹出功能气泡（退出 / 重启桌宠）
            //   ★ 轮次㊱：long_press 场景此前**零引用** ⇒ 用户选了动作也没反应
            playSceneOnceSafe(SceneRegistry.LONG_PRESS, 1200);
            //   ★ 轮次㊲：长按也会说话了（台词选项与动作选项同步）
            saySceneLine(SceneRegistry.LONG_PRESS);
            showLongPressBubble();
        } else {
        // 单击 = 部位台词；双击 = 打开聊天输入框；三击 = 动作菜单
        long nowTs = System.currentTimeMillis();
        boolean doubleTap = (nowTs - lastTapTime) < 350;
        boolean tripleTap = triplePend && (nowTs - lastTapTime) < 350;
        lastTapTime = nowTs;
        if (petImage != null) petImage.poke();
        if (actionShowing) { hideActionBubble(); }
        if (tripleTap) {
            // 三击：打开动作菜单（原长按功能迁到这里）
            triplePend = false;
            lastTapTime = 0;
            if (petImage != null) petImage.poke();
            showActionBubble();
        } else if (doubleTap) {
        // 双击：打开输入框聊天（不触发随机动作）
        // 先挂起"三击待定"，350ms 内无第三击则执行双击
        //
        // ★★ 轮次㊸ 修：这个"挂起"以前写成
        //    `doubleTap && Prefs.chatEnabled(...)` —— 于是**关掉对话**之后
        //    第二击落到下面的 else（当单击处理）⇒ triplePend 永远是 false
        //    ⇒ **三击菜单彻底打不开**（设备实测：chat_enabled=false 时
        //    连点三下只出三次单击台词，动作菜单一次都没弹）。
        //    挂起与"对话开关"无关：它只是**分辨双击/三击**的中间态；
        //    真正该被开关挡住的只有"超时后打开输入框"那一件事。
        triplePend = true;
        lastTapTime = nowTs;
        final boolean canChat = Prefs.chatEnabled(FloatingService.this);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (!triplePend) return;
                triplePend = false;
                // 关掉对话 ⇒ 双击不做事（但这次双击仍被吃掉，
                // 不会再往下掉成"单击切换余额气泡"）
                if (!canChat) return;
                // ★ 轮次㊱：double_tap 场景此前零引用（确认是双击才播，
                //   否则 350ms 内还会变成三击）
                playSceneOnceSafe(SceneRegistry.DOUBLE_TAP, 1200);
                // ★ 轮次㊲：双击也会说话了（台词选项与动作选项同步）
                saySceneLine(SceneRegistry.DOUBLE_TAP);
                showAskBar();
            }
        }, 400);
        } else if (triplePend) {
        // 已有一次待定双击，这算第三击 → 动作菜单
        triplePend = false;
        lastTapTime = 0;
        if (petImage != null) petImage.poke();
        showActionBubble();
        } else {
        // 单击：走**唯一**的点击处理（轮次82 抽成方法，挂边态也复用它）
        onPetSingleTap(event, allowBalanceToggle, cdBlocked);
        }
        }
    }

    /**
     * 单击桌宠的**唯一**处理（轮次82 抽出来，普通态与挂边态共用）。
     *
     * <p>包含：余额气泡切换（可选）→ 蹦一下 → 连击计数 → 部位动作 → 连戳互动场景 →
     * 部位台词 → 按需补一句 AI 回应。冷却（{@code cdBlocked}）只挡说话，不挡动作 ——
     * "点了没反应"多半是这两件事被一起门控了。
     *
     * @param allowBalanceToggle 普通态为 true（保留"单击切余额气泡"）；挂边态 false
     *                           （挂边期间气泡本来就被压住，别借一次点击偷偷改用户偏好）
     */
    private void onPetSingleTap(android.view.MotionEvent event, boolean allowBalanceToggle,
                                boolean cdBlocked) {
        // 单击 = 切换余额气泡：显示 ↔ 隐藏（无自动隐藏计时）
        // ★★ 轮次76：设置里关掉「显示余额气泡」后，单击**不再切换它**
        //    （但"蹦一下 / 说话 / 动作"这些反馈照旧）。
        if (allowBalanceToggle && Prefs.balanceBubbleOn(FloatingService.this)) {
            setBalanceBubbleVisible(!isVisible);
        }
        petTapFeedback();   // 蹦一下（即时反馈，含点击音效）
        // ★ 轮次㊱：连击计数挪到**这里**。原来只在 petTapTalk 里维护，
        //   而 petTapTalk 只在"API 档 + 开了对话 + 有 Key"时才被调用
        //   ⇒ 纯本地档永远停在 0："被连戳烦了"那句与互动动作都出不来。
        long tapNow = System.currentTimeMillis();
        if (tapNow - lastTapStreakMs < 4000) tapStreak++;
        else tapStreak = 0;
        lastTapStreakMs = tapNow;
        // ★★ 动作**不受说话冷却门控**：戳哪里就该演哪里。
        String part = detectPart(event);
        playPartScene(part);
        if (tapStreak >= 3) playSceneOnceSafe(SceneRegistry.INTERACT, 1200);
        if (cdBlocked) {
            // CD 内：不重复触发说话，只做蹦跳 + 动作反馈
        } else {
            petPartFeedback(part);
            if (!localOnly() && Prefs.chatEnabled(FloatingService.this) && chatBackendReady()) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { petTapTalk(); }
                }, 1200);
            }
        }
    }

    private void petTapTalk() {
        // ★ 轮次㊱：连击计数已挪到**点击处理**里（见 onTouch 的单击分支）——
        //   放在这里只有"API 档 + 开了对话 + 有 Key"才会加，纯本地档永远停在 0。
        long now = System.currentTimeMillis();

        if (localOnly() || !Prefs.chatEnabled(this) || !chatBackendReady()) {
            // 纯本地 / 没开对话 / 没 key：退回本地随机口癖，保证"戳了有反应"
            String q = pickTapQuip();
            showBubble(q, 6000);
            return;
        }
        // 被连戳 3 次以上：立刻用本地"烦了"台词秒回，不排队等 API
        if (tapStreak >= 3) {
            String aq = pickTapQuip();
            showPetSay("\uD83D\uDCAC ", aq, 5000, null);
            lastTapTalkMs = now;
            return;
        }
        long tapCd = Prefs.getInteractCdMs(this);
        if (tapCd <= 0) tapCd = 1500L;            // 关闭冷却时，点击仍保留 1.5 秒保护
        if (now - lastTapTalkMs < tapCd) return;  // CD 内不重复请求
        lastTapTalkMs = now;

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        // 提示语：让模型知道"主人戳了桌宠一下"，结合场景/被戳频率回应（要短）
        // 不再塞入具体时间，避免每次被戳都提时间

        // 是否被连戳
        long nw = System.currentTimeMillis();
        boolean annoyed = (nw - lastTapStreakMs < 4000 && tapStreak >= 2);
        // 场景识别：主人在用什么 App（仅前台包名+时间）
        String moodScene = null, moodApp = null;
        try {
            if (Prefs.moodWatch(this) && MoodWatcher.hasPermission(this)) {
                MoodWatcher.Foreground fg = MoodWatcher.current(this);
                if (fg != null) { moodScene = fg.scene; moodApp = fg.appName; }
            }
        } catch (Exception ignore) {}
        final String sceneHint = (moodScene == null)
                ? ""
                : "，而且主人这会儿正在用「" + moodApp + "」，看起来是在" + moodScene;
        final String cue = "（" + (sceneHint.isEmpty() ? "" : "主人这会儿正用着手机" + sceneHint) + "，主人用手指戳了你一下"
                + (annoyed ? "，而且已经连着戳了你好几下，你有点不耐烦了" : "")
                + "。请用你的性格，自然地回应一句，"
                + "表达被戳到的反应或对主人的回应"
                + (sceneHint.isEmpty() ? "" : "，可以顺着主人正在做的事说一句（但别生硬复述原话）")
                + "，必须很短，控制在15字以内，不要加引号、不要解释。）";

        // 结合记忆点 + 最近对话 + 去重清单，避免每次被戳都回一样的
        final String factsPrompt = ChatMemory.factsPrompt(this);
        final java.util.List<String[]> history =
                ChatMemory.recent(this, Math.max(4, ChatMemory.ctxLimit(this)));
        final String antiRepeat = buildAntiRepeatSystem();
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = genReactiveOnce(apiKey, model, persona, factsPrompt,
                        antiRepeat, history, cue, false);
                if (reply != null && (tooSimilarToRecent(reply)
                        || ChatMemory.similarToAsked(FloatingService.this, reply))) {
                    String retryCue = cue + "\n（刚才那句和以前说过的太像了，换一种完全不同的说法和角度。）";
                    reply = genReactiveOnce(apiKey, model, persona, factsPrompt,
                            antiRepeat, history, retryCue, true);
                }
                final String finalReply = (reply != null && reply.length() > 0)
                        ? reply : pickTapQuip();
                handler.post(new Runnable() {
                    @Override public void run() {
                        android.util.Log.i("DSVoice", "点击说话 reply=" + finalReply);
                        showPetSaySplit("\uD83D\uDCAC ", finalReply, resolveMeme(lastModelMeme), 8000);
                        ChatMemory.append(FloatingService.this, "assistant", finalReply);
                        ChatMemory.appendProactive(FloatingService.this, finalReply);
                        maybeUsageTalk();
                    }
                });
            }
        }, "pet-tap-talk").start();
    }

    /** 触摸反应生成一次，返回文本或 null；retry 仅用于日志 */
    private String genReactiveOnce(String apiKey, String model, String persona,
                                   String factsPrompt, String antiRepeat,
                                   java.util.List<String[]> history, String cue, boolean retry) {
        lastModelMeme = null;          // 每次请求前重置，避免沿用上一轮的表情
        hintLocalLoadingIfNeeded();    // 本地后端首次要载入（戳一下干等十几秒就没反应了）
        ApiClient.ChatResult res = ChatRouter.chatReactive(
                FloatingService.this, apiKey, model, persona, factsPrompt, antiRepeat, history, cue, null);
        if (res != null && res.ok && res.reply != null && res.reply.trim().length() > 0) {
            String line = res.reply.trim();
            line = line.replaceAll("^[\"\u300c\u300e]+|[\"\u300d\u300f]+$", "").trim();
            lastModelMeme = res.memeName;
            android.util.Log.i("DSVoice", "触摸反应(retry=" + retry + ") reply=" + line);
            return line.length() > 0 ? line : null;
        }
        if (res != null && !res.ok) android.util.Log.e("DSVoice", "触摸反应失败: " + res.error);
        return null;
    }

    // 连续点击计数（判断"被戳烦了"）
    private int tapStreak = 0;
    private long lastTapStreakMs = 0;

    /** 本地兜底口癖：按 时段 + 是否被连戳 选词（无网络/无 key 时用） */
    // ==================== 点击部位识别 ====================

    /**
     * 根据触摸点在整个桌宠窗口中的相对位置判定部位：
     *  - 上部 30%  → head（头）
     *  - 下部 30%  → tail（尾）
     *  - 中间     → body（身）
     */
    private String detectPart(MotionEvent e) {
        try {
            float h = (root != null) ? root.getHeight() : 0;
            if (h <= 0 || petImage == null) return "body";
            // 相对桌面宠视图内部坐标
            float y = e.getY();
            float ratio = y / h;
            if (ratio < 0.32f) return "head";
            if (ratio > 0.68f) return "tail";
            return "body";
        } catch (Throwable t) {
            return "body";
        }
    }

    /** 点击某部位：气泡 + 语音（部位专属台词） */
    private void petPartFeedback(String part) {
        lastPart = part;
        String line = pickPartQuip(part);
        if (line == null) return;
        showPetSay("\uD83D\uDCAC ", line, 5000, null);
    }

    /**
     * 部位 → 动作（"点哪演哪"）。
     *
     * <p>★ 轮次㊱ 接上：{@code tap_head / tap_body / tap_tail} 三个场景此前**全工程零引用**，
     * 用户报「点击头部 / 点击尾部 / 点击身体这些动作选项无法触发」——
     * 点击处理只识别了部位、报了一句台词，**从没把场景交给素材层**。
     *
     * <p>映射走纯函数 {@link SceneRegistry#partScene}（点击处理、设置页清单、断言共用同一份）。
     * 角色包没绑这个部位场景时 {@code playSceneOnce} 返回 false ⇒ 什么都不做，
     * 此时通用 {@code click}（{@code poke()} 里播的）就是兜底。
     */
    private void playPartScene(String part) {
        playSceneOnceSafe(SceneRegistry.partScene(part), 1200);
    }

    /**
     * 播一次场景动作；{@code scene} 为 null / 不是动画包 / 角色包没绑这个场景 ⇒ 静默跳过。
     * （调用方不必关心角色包配了什么 —— 这是"场景是数据"那条约定的用法。）
     */
    private void playSceneOnceSafe(String scene, long fallbackMs) {
        if (scene == null || petImage == null || !petImage.isAnimPack()) return;
        petImage.playSceneOnce(scene, fallbackMs);
    }

    /** 纯本地模式：一切互动只念本地台词，绝不调用 API */
    private boolean localOnly() {
        return Prefs.interactLocalOnly(this);
    }

    // ==================== 对话后端 ＝ 本地模型（轮次64 第三轮）====================
    //
    // 用户在设置页把「服务商」选成「🤖 本地模型（手机本机跑，不联网）」之后：
    //   · 上面那个 localOnly()（纯本地台词）**是另一回事**，两者互不影响；
    //   · 这几句 AI 的路径改走 ChatRouter → LocalChat → 本机 llama.cpp，
    //     **一个 HTTP 请求都不发**（连"联网检索"也被 ChatRouter.webAllowed 关掉）。

    /**
     * "现在能不能让 AI 说一句"：在线后端要有 Key，本地后端**不要**。
     *
     * <p>★★ 为什么必须收敛成这一个判据：原来这几处各自写着
     * {@code !Prefs.hasApiKey(this)}，而本地模型**本来就没有 Key** ——
     * 不换的话，用户选了本地模型却会在每个入口被挡回去，
     * 看到的现象是「功能没反应」（最难查的那类：代码没坏，是判据过时了）。
     */
    private boolean chatBackendReady() {
        return ChatRouter.ready(this);
    }

    /**
     * 本地模型**首次载入**的等待反馈（几百 MB 的 mmap，几秒到十几秒）。
     *
     * <p>只在"真的需要载入"时才显示（{@link ChatRouter#loadHintIfNeeded} 会判），
     * 之后模型常驻，就不再打扰。★ 必须在**后台线程**里调（调用方本来就在后台线程）。
     */
    private void hintLocalLoadingIfNeeded() {
        final String hint = ChatRouter.loadHintIfNeeded(this);
        if (hint == null) return;
        handler.post(new Runnable() {
            @Override public void run() { showBubble(hint, 0); }
        });
    }

    /**
     * 流式气泡：本地后端逐段出话时，把**已经生成的部分**放进气泡。
     *
     * <p>★★ 为什么不直接把每段塞进「回复连发」那套：
     * {@code showPetSaySplit} 会切句、排队、发 TTS —— 它是给**完整一句话**设计的。
     * 生成中途往里灌半句，会把连发队列和朗读搅乱（施工单专门提醒过这条）。
     * 所以这里：**生成期间**只走低层 {@code showBubbleInternal}（不切句、不朗读、不配图），
     * 生成**结束后**再把完整文本交给原来的 {@code showPetSaySplit} 走老流程。
     *
     * <p>★ 攒字与节流都在 {@link LocalChat#throttledSink} 里（两个界面共用一处），
     * 这里只负责"回到 UI 线程把这段画出来"。
     */
    private LocalLlm.TokenSink bubbleSink(final String prefix) {
        return LocalChat.throttledSink(new LocalChat.PieceSink() {
            @Override public void onPiece(final String soFar) {
                handler.post(new Runnable() {
                    @Override public void run() {
                        // ★ 低层入口：不取消连发、不发朗读、不配图（生成还没结束）
                        showBubbleInternal(prefix + soFar, 0, null);
                    }
                });
            }
        });
    }

    /**
     * 挑一句本地台词：**用户自定义优先**，没写就退回内置池。
     *
     * <p>用户 2026-10-07 要求："纯本地增加自定义发言的选项，可以自定义各个场景的
     * 宠物说话发言"。语义（与 {@link LocalLines} 的说明一致）：
     * 该场景**留空 = 用内置池**；填了就**只**从用户写的里面挑（不混内置，
     * 否则"我删掉的那句又冒出来了"）。
     *
     * @param scene   场景 id（{@link LocalLines} 里的常量）
     * @param builtin 内置池（可为 null）
     * @return 台词；两边都没有时返回 null（调用方据此不出声）
     */
    private String pickLocalLine(String scene, String[] builtin) {
        String line = null;
        try {
            String[] custom = LocalLines.parseLines(Prefs.localLines(this, scene));
            // ★ 优先级（自定义优先、空则内置）在**纯函数**里 —— 可行为断言，见 LocalLines.pickWithCustom
            line = LocalLines.pickWithCustom(custom, builtin, Math.random());
        } catch (Throwable ignore) {
            line = LocalLines.pick(builtin, Math.random());
        }
        if (line != null) {
            // 设备侧只有日志能验"这一句是本地台词"（气泡是悬浮窗，不在 uiautomator dump 里）
            android.util.Log.i("DSVoice", "本地台词 scene=" + scene + " line=" + line);
        }
        return line;
    }

    /**
     * 场景台词出口：**某个场景的动作播出的那一刻，顺带说这个场景的台词**
     * （用户 2026-10-11 要求「对话台词选项同步动作选项（动作有那些选项 台词就有哪些选项）」）。
     *
     * <p>★ 内置池一律传 {@code null} ⇒ **默认一个字都不说**：只有用户在设置页里给这个场景
     * 写了台词才会开口。这样"台词选项 = 动作选项"是一一对应的，而**默认行为零变化**
     * （不会因为多出九个场景就变话痨）。
     * <p>★ 与"点一下 / 拖动 / 贴边 / 整点 / 主动搭话"那几条的区别：那几条有**内置池**，
     * 留空时照样会说内置的话；这一族（漫游 / 转向 / 双击 / 长按 / 待机 / 轮播 / 节日 /
     * 余额 / 工作状态）本来就不说话，留空 = 继续不说话。
     */
    private void saySceneLine(String scene) {
        String line = pickLocalLine(scene, null);
        if (line == null || line.length() == 0) return;
        showPetSay("\uD83D\uDCAC ", line, 5000, null);
    }

    /** 纯本地模式下代替 API 的主动搭话台词 */    private String pickLocalProactiveLine() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String[] pool;
        if (hour >= 1 && hour <= 5) {
            pool = new String[]{
                    "这么晚还不睡呀…我陪你一会儿。",
                    "夜深啦，别熬太狠哦。",
                    "唔…困了就去睡嘛，我在这儿守着。"
            };
        } else if (hour < 9) {
            pool = new String[]{
                    "早呀～今天想先做点什么？",
                    "睡醒啦？记得吃早饭哦。",
                    "早安！要不要一起规划一下今天？"
            };
        } else if (hour < 18) {
            pool = new String[]{
                    "忙了这么久，要不要歇一下眼睛？",
                    "在忙什么呀？我就在旁边看着。",
                    "喝口水吧，别一直盯着屏幕。",
                    "手头的事顺不顺利？"
            };
        } else {
            pool = new String[]{
                    "今天就到这儿吧？早点休息。",
                    "晚上好呀，今天过得怎么样？",
                    "忙完啦？跟我说说今天的事吧。"
            };
        }
        return pickLocalLine(SceneRegistry.EV_WHISPER, pool);
    }

    /** 点一下的本地台词（按时段 + 连戳状态选池；用户自定义优先） */
    private String pickTapQuip() {
        // 被连戳 3 次以上 → 烦了（连击计数在单击分支里统一维护）
        if (tapStreak >= 3) {
            String[] annoyed = {
                    "别戳啦别戳啦！要坏掉啦！",
                    "唔…再戳人家要生气咯！",
                    "好啦好啦，人家知道你在～",
                    "手、手指拿开啦！"
            };
            return pickLocalLine(SceneRegistry.INTERACT, annoyed);
        }
        return pickLocalLine(SceneRegistry.CLICK, tapPoolByHour());
    }

    /**
     * "点一下"的**内置池**（按时段选）—— 部位台词留空时也用它当内置。
     * <p>抽出来是因为轮次㊲ 起"戳头/身/尾"各有自定义槽（见 {@link #pickPartQuip}），
     * 而它们的**默认**行为必须与以前一模一样（不写自定义就说这套按时段的池）。
     */
    private String[] tapPoolByHour() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String[] pool;
        if (hour >= 5 && hour < 9) {
            // 早晨
            pool = new String[]{
                    "早呀主人～睡饱了没？",
                    "唔…一大早就戳人家。",
                    "早安！今天也要加油哦！",
                    "主人起得好早呀～",
                    "哈——欠…人家还想再睡会儿。"
            };
        } else if (hour >= 9 && hour < 18) {
            // 白天
            pool = new String[]{
                    "唔…干嘛戳人家啦！",
                    "主人手欠哦～",
                    "戳我干嘛，有事说事！",
                    "在啦在啦，一直都在～",
                    "呀！痒痒的～",
                    "嘻嘻，被你发现啦！"
            };
        } else if (hour >= 18 && hour < 23) {
            // 傍晚
            pool = new String[]{
                    "忙完啦？戳人家干嘛～",
                    "咦，下班回来啦？",
                    "哼，又想我啦？",
                    "别戳啦，人家会害羞的！",
                    "晚饭吃了没呀？"
            };
        } else {
            // 深夜
            pool = new String[]{
                    "这么晚还不睡呀？",
                    "唔…人家都困啦…",
                    "熬夜会变丑的哦！",
                    "嘘——小声点，会吵到邻居的～",
                    "快睡觉啦，明天还要早起呢！",
                    "唔…干嘛戳人家啦！"
            };
        }
        return pool;
    }

    /**
     * 部位台词：**每个部位一个自定义槽**（用户 2026-10-11 要求
     * 「被戳头尾部身体的时候也有自定义对话」）。
     *
     * <p>★ **三级回退**（顺序很重要，中间那一级是为了不动老用户的数据）：
     * <ol>
     *   <li>这个部位自己的自定义（"摸头"与"拽尾巴"可以写不一样的话）；</li>
     *   <li>没有 ⇒ 退回「点一下」的自定义 —— 部位是这一轮新加的槽，而老用户可能早就
     *       给"点一下"写过台词；不接这一级的话，他们的台词会**突然听不到**（那是回退）；</li>
     *   <li>还没有 ⇒ 内置（沿用"点一下"那套按时段选的池 ⇒ 行为与以前完全一样）。</li>
     * </ol>
     * <p>★ 被连戳（烦了）**优先于**部位 —— 那是"被戳烦了"，与戳哪儿无关。
     */
    private String pickPartQuip(String part) {
        if (tapStreak >= 3) return pickTapQuip();
        String scene = LocalLines.sceneForPart(part);
        if (scene == null) return pickTapQuip();
        String mine = pickLocalLineOnly(scene);                        // ① 部位自定义
        if (mine != null) return mine;
        return pickLocalLine(SceneRegistry.CLICK, tapPoolByHour());    // ②「点一下」自定义 ③ 内置池
    }

    /**
     * **只**取用户自定义台词（没有就返回 null，**不退回内置**）。
     * <p>给"三级回退"那种场景用：中间那一级必须能区分"用户写了"与"没写"，
     * 而 {@link #pickLocalLine} 把两级合并成一句、看不出是哪一级来的。
     */
    private String pickLocalLineOnly(String scene) {
        try {
            String line = LocalLines.pick(LocalLines.parseLines(Prefs.localLines(this, scene)),
                    Math.random());
            if (line != null) {
                android.util.Log.i("DSVoice", "本地台词 scene=" + scene + " line=" + line);
            }
            return line;
        } catch (Throwable ignore) {
            return null;
        }
    }

    // ==================== 拖动摇晃说话 ====================

    private long lastLiftTalkMs = 0;

    /** 被拎起来晃 → 说一句反应（冷却时长跟随「互动冷却」设置，防反复拖拽刷屏） */
    private void petLiftTalk() {
        long now = System.currentTimeMillis();
        long liftCd = Prefs.getInteractCdMs(this);
        if (liftCd <= 0) liftCd = 2000L;          // 关闭冷却时，拖动仍保留 2 秒保护
        if (now - lastLiftTalkMs < liftCd) return;
        lastLiftTalkMs = now;

        if (localOnly() || !Prefs.chatEnabled(this) || !chatBackendReady()) {
            String lq = pickLiftQuip();
            showPetSay("", lq, 6000, null);
            return;
        }
        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final String cue = "（主人把你拎起来在半空中晃了晃。请用你的性格，"
                + "自然地回应一句被拎起来晃的反应，可以撒娇、抱怨或求放下来，"
                + "必须很短，控制在15字以内，不要加引号、不要解释。）";

        final String factsPrompt = ChatMemory.factsPrompt(this);
        final java.util.List<String[]> history =
                ChatMemory.recent(this, Math.max(4, ChatMemory.ctxLimit(this)));
        final String antiRepeat = buildAntiRepeatSystem();
        new Thread(new Runnable() {
            @Override public void run() {
                String reply = genReactiveOnce(apiKey, model, persona, factsPrompt,
                        antiRepeat, history, cue, false);
                if (reply != null && (tooSimilarToRecent(reply)
                        || ChatMemory.similarToAsked(FloatingService.this, reply))) {
                    String retryCue = cue + "\n（刚才那句和以前说过的太像了，换一种完全不同的说法。）";
                    reply = genReactiveOnce(apiKey, model, persona, factsPrompt,
                            antiRepeat, history, retryCue, true);
                }
                final String finalReply = (reply != null && reply.length() > 0)
                        ? reply : pickLiftQuip();
                handler.post(new Runnable() {
                    @Override public void run() {
                        android.util.Log.i("DSVoice", "拖动说话 reply=" + finalReply);
                        showPetSaySplit("\uD83D\uDCAC ", finalReply, resolveMeme(lastModelMeme), 7000);
                        ChatMemory.append(FloatingService.this, "assistant", finalReply);
                        ChatMemory.appendProactive(FloatingService.this, finalReply);
                        maybeUsageTalk();
                    }
                });
            }
        }, "pet-lift-talk").start();
    }

    /** 本地兜底：被拎起来的反应 */
    private String pickLiftQuip() {
        String[] quips = {
                "呀！放我下来啦～",
                "呜哇——人家怕高的！",
                "诶诶诶？要带我去哪呀？",
                "别晃啦别晃啦，头晕！",
                "哼，拎就拎嘛，轻一点！",
                "抓稳点哦，人家会掉下去的！",
                "哇——好高的说！"
        };
        return pickLocalLine(SceneRegistry.DRAG, quips);
    }

    // ==================== 交互反馈动画（点击 / 拖动 / 放下） ====================

    /** 点击反馈：桌宠原地蹦一下，像被戳了一下 */
    private void petTapFeedback() {
        if (petImage == null) return;
        try {
            final float baseScale = Prefs.getScale(this);
            petImage.animate().cancel();
            petImage.setPivotX(petImage.getWidth() / 2f);
            petImage.setPivotY(petImage.getHeight());  // 以脚底为支点，像弹跳
            // 先压一下（蓄力）→ 再蹦起 + 放大 → 落回
            petImage.animate()
                    .scaleX(baseScale * 0.88f).scaleY(baseScale * 0.82f)
                    .setDuration(70)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (petImage == null) return;
                            petImage.animate()
                                    .scaleX(baseScale * 1.12f).scaleY(baseScale * 1.15f)
                                    .translationY(-dp(14))
                                    .setDuration(120)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (petImage == null) return;
                                            petImage.animate()
                                                    .scaleX(baseScale).scaleY(baseScale)
                                                    .translationY(0)
                                                    .setDuration(160)
                                                    .start();
                                        }
                                    })
                                    .start();
                        }
                    })
                    .start();
        } catch (Exception ignore) {}
    }

    /** 拖动反馈：被拎起来，左右轻轻摇晃 */
    private void petLiftFeedback() {
        if (petImage == null) return;
        try {
            petImage.animate().cancel();
            petImage.setPivotX(petImage.getWidth() / 2f);
            petImage.setPivotY(0);
            petImage.setRotation(0f);
            // 轻微摇摆
            petImage.animate().rotation(6f).setDuration(90)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (petImage == null) return;
                            petImage.animate().rotation(-6f).setDuration(120)
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        if (petImage == null) return;
                                        petImage.animate().rotation(0f).setDuration(110).start();
                                    }
                                }).start();
                        }
                    }).start();
        } catch (Exception ignore) {}
    }

    /** 放下反馈：落地弹一下（压扁 → 回弹），像真的被放到地上 */
    private void petDropFeedback() {
        if (petImage == null) return;
        try {
            final float baseScale = Prefs.getScale(this);
            petImage.animate().cancel();
            petImage.setPivotX(petImage.getWidth() / 2f);
            petImage.setPivotY(petImage.getHeight());
            petImage.setRotation(0f);
            petImage.setTranslationY(0f);
            // 落地压缩
            petImage.animate()
                    .scaleX(baseScale * 1.14f).scaleY(baseScale * 0.80f)
                    .setDuration(90)
                    .withEndAction(new Runnable() {
                        @Override public void run() {
                            if (petImage == null) return;
                            // 回弹
                            petImage.animate()
                                    .scaleX(baseScale * 0.94f).scaleY(baseScale * 1.08f)
                                    .setDuration(110)
                                    .withEndAction(new Runnable() {
                                        @Override public void run() {
                                            if (petImage == null) return;
                                            petImage.animate()
                                                    .scaleX(baseScale).scaleY(baseScale)
                                                    .setDuration(130)
                                                    .start();
                                        }
                                    })
                                    .start();
                        }
                    })
                    .start();
        } catch (Exception ignore) {}
    }

    /** dp 转 px 小工具 */
    private int dp(float v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }

    /** 捏合过程中实时改变图片大小（不重建窗口，避免闪烁） */
    private void applyScaleLive() {
        if (petImage == null) return;
        float d = getResources().getDisplayMetrics().density;
        float scale = Prefs.getScale(this);
        int petW = (int) (d * BASE_PET_W * scale);
        int petH = (int) (d * BASE_PET_H * scale);
        ViewGroup.LayoutParams lp = petImage.getLayoutParams();
        lp.width = petW;
        lp.height = petH;
        petImage.setLayoutParams(lp);
        // 同时缩放容器高度
        if (container != null) {
            ViewGroup.LayoutParams clp = container.getLayoutParams();
            clp.height = petH + (int) (d * 56);
            container.setLayoutParams(clp);
        }
        // 放大后可能溢出屏幕，重新钳制
        clampPositionsAfterLayout();
    }

/** 启动漫游调度：定时随机决定是否走一段 */
    private void startWander() {
        wanderHandler.removeCallbacksAndMessages(null);
        // ★ v7.0：漫游开关回到 pref（原来 `wanderEnabled` 恒为 false = 功能被取消，
        //   于是 roam/turn 两个场景也一起休眠）。这里每次续约都重读，改设置后立刻生效。
        wanderEnabled = Prefs.wanderEnabled(this);
        // 悬浮窗不进 uiautomator dump ⇒ 漫游只能靠日志验（工程既有手法）
        android.util.Log.i("DSPWander", "start enabled=" + wanderEnabled
                + " dock=" + dockState + " x=" + (params != null ? params.x : -1));
        if (!wanderEnabled) return;
        wanderHandler.postDelayed(wanderTick, 4000 + (long)(Math.random() * 4000));
    }

    /** 停止漫游并把宠物拉回待机 */
    private void stopWander() {
        wandering = false;
        wanderTargetX = Integer.MIN_VALUE;
        wanderDockOnArrive = false;
        wanderHandler.removeCallbacksAndMessages(null);
        // ★★ 轮次60：**先解锁再回待机** —— 漫游期间场景是锁着的（漫游优先），
        //   不解锁的话下面这句 setScene(IDLE) 会被锁自己挡掉（桌宠就卡在漫游动作上）。
        unlockRoamScene();
        if (petImage != null && petImage.isAnimPack()) petImage.setScene(SceneRegistry.IDLE);
    }

    /**
     * 解除漫游的场景锁（轮次60）。
     * <p>★ **每一个"漫游结束"的出口都要调**：走完（walkFinish）、被用户碰（stopWander）、
     * 以及 stepWander 里那几条提前退出。少一个，那条路上的"回待机"就会被锁挡住。
     */
    private void unlockRoamScene() {
        // ★★ 轮次70：漫游结束（= 每一个出口都会走到这里）⇒ 顺手解除"冻结范围"，
        //   之后的拖动 / 吸附 / 布局钳制照旧按**当前 clip** 的留白算。
        roamFrozenX = false;
        try { if (petImage != null) petImage.unlockScene(); } catch (Throwable ignore) {}
    }
    /** 本次漫游是否"撞到屏幕边缘"（到位后按设置自动贴边） */
    private boolean wanderDockOnArrive = false;

    /** 这一段漫游的结束时刻（= 开走时刻 + 漫游动作的时长）—— 走完还要等动作播完再回待机 */
    private long walkEndAtMs = 0;
    /** 这一段漫游的起始时刻与起始 x（位移按时间线性插值，见 stepWander） */
    private long walkStartAtMs = 0;
    private int walkStartX = 0;
    /**
     * ★★ 轮次70：**本段漫游冻结下来的 x 可达范围** —— 触发那一刻算一次，整段不变。
     *
     * <p>为什么必须冻结：漫游一开始就会 {@code lockScene(ROAM)} 换 clip，而"可达范围"是拿
     * <b>当前 clip 的内容留白</b>算的（{@link ContentInset#xRange}）。换 clip ⇒ 留白变 ⇒
     * 之后每步 {@code clampToScreen()} 都会把窗口<b>重新夹一遍</b> ⇒ 桌宠在漫游里被"凭空挪一下"
     * （真角色包实测：待机 L=0.306 / R=0.323，原地左转奔跑 L=0.195 / R=0.275
     * ⇒ 贴左边时窗口会被往右推 ~70px）。冻结之后整段漫游用同一份范围，
     * x 只由 walkStartX→wanderTargetX 的线性插值决定。
     */
    private int roamMinX = 0, roamMaxX = 0;
    /** ★★ 轮次70：true = 正在漫游，x 钳制改用上面那对冻结范围（见 clampToScreen）。 */
    private boolean roamFrozenX = false;
    /** ★★ 轮次70：本段漫游"开走前"那行读数只打一次（见 stepWander）。 */
    private boolean roamStartLogged = false;
    /**
     * ★★ 轮次70：贴着边（COLLAPSED 竖条）时点了「主动漫游」—— 先展开、展开动画结束时再真的开走。
     *
     * <p>竖条态下窗口绝大部分在屏外、形象被压扁且暂停渲染，**必须先展开**才看得见它走
     * （展开是必要动作，落点见 {@code dockLandingX}：只用"贴边前的存档位/规范屏内位"，
     * 不会把桌宠挪到屏幕中间）。
     */
    private boolean roamAfterExpand = false;
    /**
     * 动画**整段播完**的时刻（= 开走时刻 + clip 时长）。
     * <p>★ 轮次㊻：它是"后摇"的终点 —— 人在 {@link #walkEndAtMs} 就停住了，
     * 但动画还要把收尾那段播完，到这一刻才回待机。见 {@code Prefs.wanderTailMs}。
     */
    private long walkSceneEndAtMs = 0;

    /**
     * 漫游每步的间隔（ms）。
     * <p>★ 轮次㊱：从 40ms 放宽到 80ms。每步都要 {@code applyRootLayout()}（发窗口更新 = IPC），
     * 25Hz 会把 UI 线程压出卡顿（"漫游时动作会卡"）；12.5Hz 与拖动路径的节流口径一致。
     */
    private static final long WANDER_STEP_MS = 80;

    /** 每步推进一次位移（用字段而不是每步 new 一个 Runnable：漫游是高频路径） */
    private final Runnable wanderStep = new Runnable() {
        @Override public void run() { stepWander(); }
    };

    /**
     * 一段漫游结束：回待机、存位置、按需自动贴边、排下一次巡检。
     * <p>★ 轮次㊱ 单独抽出来：**"走到目标"与"动作播完"是两个时刻** ——
     * 以前走到就切 idle，而走路只要 0.3~1.1 秒、漫游动作却有 2~4 秒
     * ⇒ 动作永远播不完就被切掉（用户报「漫游播放之后动作会卡，播放不完就切换了」）。
     */
    private final Runnable walkFinish = new Runnable() {
        @Override public void run() {
            if (params == null) return;
            // ★ 轮次60：走完了 ⇒ 先解锁，再回待机（否则回待机被漫游锁挡住）
            unlockRoamScene();
            if (petImage != null && petImage.isAnimPack()) petImage.setScene(SceneRegistry.IDLE);
            // ★ 轮次㊲：一段漫游走完、回到待机时说"待机"场景的台词
            //   （没写自定义就什么都不说；这是"回到待机"这个**时刻**，不是"一直待机"）
            saySceneLine(SceneRegistry.IDLE);
            Prefs.savePos(FloatingService.this, params.x, params.y);
            // ★ 到边缘 → 自动贴边（用户选的默认行为；贴边总开关关掉时不贴）
            if (wanderDockOnArrive) {
                wanderDockOnArrive = false;
                boolean auto = true;
                try { auto = Prefs.wanderAutoDock(FloatingService.this); } catch (Throwable ignore) {}
                if (auto && Prefs.edgeSnap(FloatingService.this)) { snapToNearestEdge(); return; }
            }
            wanderHandler.postDelayed(wanderTick, 3000 + (long)(Math.random()*5000));
        }
    };

    /**
     * 漫游时避开屏幕顶部 1/3 —— ★★ 轮次70 起**默认关闭**，并且只作为"用户显式打开"的例外。
     *
     * <p>用户 2026-10-16 原话（依据，逐字）：「<b>漫游的时候，无论主动还是被动，都会强制位移到
     * 中间然后开始漫游，更改一下漫游逻辑在任何位置都可以漫游</b>」；追问下确认：
     * 「<b>就上下跑到中间了，漫游就在桌宠当前位置开始漫游就行了</b>」。
     * ⇒ 实测真凶就是本方法：1080×2400 的机器上 {@code topLimit = 2400/3 = 800}，
     * 只要桌宠在顶部 1/3（y&lt;800），漫游一开始它就自己往下滑到 800（竖直 1/3 处 = 用户说的"中间"），
     * 600ms 动画刚好看完就进前摇、然后开走 —— 与"位移到中间然后开始漫游"逐字吻合。
     *
     * <p>★ 所以：**漫游一律从当前位置开始**（x / y 都不许有前置位移）。
     * 这条老行为降级成"只有用户显式打开才生效"的开关（{@link Prefs#wanderAvoidTop}
     * 默认 false，设置页那一行也写明"会先动一下"）。默认路径里它的调用点被
     * {@code if (avoidTop)} 守着 ⇒ **默认不存在任何前置位移**。
     */
    private void wanderAvoidTopThird() {
        if (params == null) return;
        try { if (!Prefs.wanderAvoidTop(this)) return; } catch (Throwable ignore) {}
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        final int topLimit = Math.max(0, dm.heightPixels / 3);
        if (params.y >= topLimit) return;
        android.animation.ValueAnimator a =
                android.animation.ValueAnimator.ofInt(params.y, topLimit);
        a.setDuration(600);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator an) {
                if (params == null) return;
                params.y = (Integer) an.getAnimatedValue();
                clampToScreen();
                try { applyRootLayout(); } catch (Exception ignore) {}
                positionBalance();
            }
        });
        a.start();
    }

    private final Runnable wanderTick = new Runnable() {
        @Override public void run() {
            try {
                if (!wanderEnabled || root == null || params == null) { startWander(); return; }
                // 用户刚碰过（5秒内）或正在拖拽时不漫游
                long since = System.currentTimeMillis() - lastPetTouchMs;
                boolean busy = petImage != null && petImage.isDraggingPublic();
                if (since < 5000 || busy) {
                    android.util.Log.i("DSPWander", "skip 刚被碰过/拖拽中 since=" + since
                            + " busy=" + busy);
                    wanderHandler.postDelayed(this, 3000);
                    return;
                }
                // 贴边态不漫游（竖条状态下没有"走"的必要，展开后自然恢复）
                if (dockState != PetDockState.NORMAL) {
                    android.util.Log.i("DSPWander", "skip 贴边态 dock=" + dockState);
                    wanderHandler.postDelayed(this, 3000);
                    return;
                }
                if (wandering) { stepWander(); return; }

                // 随机决定：70% 走一段，30% 原地转向动作
                if (Math.random() < 0.30) {
                    // ★ 轮次㊱：转向动作以前写死 1600ms ⇒ 动作比它长就被掐断。
                    //   现在用**动作自己的时长**，并把下一次巡检排到它播完之后。
                    long turnMs = 1600;
                    if (petImage != null && petImage.isAnimPack()) {
                        turnMs = petImage.peekSceneDurationMs(SceneRegistry.TURN, 1600);
                        petImage.playSceneOnce(SceneRegistry.TURN, turnMs);
                    }
                    android.util.Log.i("DSPWander", "turn 原地转向一下 时长=" + turnMs + "ms");
                    // ★ 轮次㊲：台词选项与动作选项同步 —— 转向那一刻说"转向"场景的台词
                    //   （没写自定义就什么都不说，见 saySceneLine）
                    saySceneLine(SceneRegistry.TURN);
                    wanderHandler.postDelayed(this, turnMs + 900 + (long)(Math.random()*2000));
                    return;
                }
                // ★ 轮次㊸：整段"开走"逻辑抽进 roamOnce()，与三击菜单的「主动漫游」**共用一份**
                //   （用户要求「先增加主动漫游在三击菜单里」）。这里 manual=false = 自动漫游。
                if (!roamOnce(false)) wanderHandler.postDelayed(this, 2000);
            } catch (Exception e) {
                wandering = false;
                startWander();
            }
        }
    };

    /**
     * 让桌宠**立刻走一段**（自动漫游的 walk 分支 + 三击菜单的「主动漫游」共用）。
     *
     * <p>★ 轮次㊸ 从 {@code wanderTick} 里抽出来，是为了让"手动漫游"与"自动漫游"
     * 走**同一条路径** —— 各写一份必然漂移（这正是本工程反复吃过的亏）。
     *
     * @param manual {@code true} = 用户手点：**不受漫游总开关限制**，也不看"刚被碰过"的 5 秒冷却
     *               （用户刚三击过桌宠，冷却必然没过；不绕过就"点了没反应"）。
     * @return 真的开走了
     */
    private boolean roamOnce(boolean manual) {
        if (root == null || params == null) return false;
        if (wandering) return false;
        // ★★ 轮次70：**竖条态（COLLAPSED）也要能起漫游** —— 那是"在任何位置都可以漫游"的一部分。
        //   竖条态下窗口几乎全在屏外、形象被压扁且暂停渲染 ⇒ **必须先展开**才看得见它走
        //   （展开是必要动作，落点见 dockLandingX：只用"贴边前的存档位"，不会挪到屏幕中间）。
        //   展开动画结束时再回来真的开走（见 dockExpand 的 onAnimationEnd）。
        //   ★ 自动漫游（manual=false）不擅自把主人的竖条展开 —— 那是他特意收起来的。
        if (dockState == PetDockState.COLLAPSED) {
            if (manual) {
                roamAfterExpand = true;
                android.util.Log.i("DSPWander", "触发 manual=true dock=COLLAPSED ⇒ 先展开再漫游");
                dockExpand();
            } else {
                android.util.Log.i("DSPWander", "skip 贴边态 dock=COLLAPSED（自动漫游不擅自展开）");
            }
            return false;
        }
        // ★★ 轮次70：**默认没有任何前置位移（x 和 y 都不许）**。
        //   用户原话（依据，逐字）：「漫游的时候，无论主动还是被动，都会强制位移到中间然后
        //   开始漫游，更改一下漫游逻辑在任何位置都可以漫游」/「就上下跑到中间了，
        //   漫游就在桌宠当前位置开始漫游就行了」。
        //   老行为是"开走前先把桌宠从屏幕顶部 1/3 滑下来"（= 用户看到的"凭空移动到中间"），
        //   现在**默认关**（Prefs.wanderAvoidTop 默认 false）；只有用户显式打开才走那条老路。
        boolean avoidTop = false;
        try { avoidTop = Prefs.wanderAvoidTop(this); } catch (Throwable ignore) {}
        android.util.Log.i("DSPWander", "触发 manual=" + manual + " dock=" + dockState
                + " x=" + params.x + " y=" + params.y
                + " 前置位移=" + (avoidTop ? "有（避开顶部 1/3 已打开：会先滑 y）" : "无"));
        if (avoidTop) wanderAvoidTopThird();
        // ★ 走到边缘 = 让"角色可见内容"贴到屏幕边，所以用同一套内容边距判据。
        //   旧写法按"视口留白"算（内置包视口无留白 ⇒ 范围 0~450），
        //   于是漫游走到边上还差 210px，wanderDockOnArrive 也永远判不出"到边了"。
        int[] rx = edgeRangeX();
        int minX = rx[0];
        int maxX = rx[1];
        int cur = params.x;
        // ★★ 轮次70：把这一份范围**冻结**给整段漫游用（理由见 roamMinX 的字段注释）
        roamMinX = minX;
        roamMaxX = maxX;
        roamFrozenX = true;
        roamStartLogged = false;
        // ★ 轮次㊱：距离按**速度档位**取（走多快 = 同样的时间走多远），
        //   而不是"改每步多大" —— 时长由漫游动作决定（见下），
        //   所以"快"必须体现在距离上，否则动作又会被掐断。
        int wsp = 1;
        try { wsp = Prefs.wanderSpeed(this); } catch (Throwable ignore) {}
        int distBase = (wsp <= 0) ? 70 : (wsp >= 2 ? 200 : 120);
        int distSpan = (wsp <= 0) ? 160 : (wsp >= 2 ? 420 : 260);
        // ★★ 轮次60：**方向不再随机**（用户原话：「每次触发方向都是随机的，有概率触发不了，
        //   统一改成贴在哪边往另一边漫游」）。
        //   旧写法 `dir = Math.random() < 0.5 ? -1 : 1` ⇒ 贴着左边时有一半概率朝**墙**走，
        //   被 `Math.max(minX, …)` 夹回原地，于是下面 `|tx-cur| < 40` 直接 return false
        //   —— 用户看到的就是"点了漫游没反应 / 有概率触发不了"。
        //   现在：**贴哪边就往另一边走**（贴左→向右，贴右→向左）；
        //   不在边上（在屏幕中间）时走向**空间更大**的那一侧，同样不掷骰子。
        //   ★ 规则本身在纯类里（`ContentInset.roamDirAway`）⇒ fpstest 能真跑。
        int dir = ContentInset.roamDirAway(minX, maxX, cur);
        int dist = distBase + (int)(Math.random() * distSpan);
        int raw = cur + dir * dist;
        int tx = Math.max(minX, Math.min(maxX, raw));
        // ★ 目标被屏幕边缘夹住 = 这次是"走到边上" → 到位后按设置自动贴边
        wanderDockOnArrive = (tx != raw) && tx <= minX + 2 || (tx != raw) && tx >= maxX - 2;
        if (Math.abs(tx - cur) < 40) {
            // ★ 轮次60：方向不再随机之后，这里几乎只会在"屏幕窄到没余地"时命中
            //   （以前贴边时随机朝墙走也会走到这里 ⇒ 用户点了「主动漫游」没反应）
            if (manual) showBubble("屏幕太窄，没有可走的余地", 1800);
            return false;
        }
        // ★★ 走多久 = **正在播的那个动作**的完整时长（至少 1.2 秒）。
        //   ① 轮次㊱ 定的是"走多久 = 漫游动作的时长"（旧写法走到就切 idle，而动作有 2~6 秒
        //      ⇒ 永远播不完，用户报「漫游播放之后动作会卡，播放不完就切换了」）；
        //   ② ★ 轮次㊸ 修的是**量错了哪个动作**：原来先 peekSceneDurationMs() 抽一次、
        //      setScene() 再抽一次，两次可能抽到不同动作 ⇒ "走多久"与"播多久"对不上，
        //      动作被从中间掐断 ⇒ 切回待机时角色横向跳一下（用户报「漫游完位置不对应，
        //      位置会闪现」）。现在**先开播、再量正在播的那个**。
        long clipStart = System.currentTimeMillis();
        long roamMs = 3000;
        if (petImage != null) {
            // ★ 朝向：往哪边走就朝哪边（PetView 按素材原生朝向决定要不要水平镜像）
            petImage.setFaceDir(tx > cur ? 1f : -1f);
            // ★★ 轮次60：用 **lockScene** 而不是 setScene —— 漫游期间别的场景抢不走它。
            //   用户原话：「漫游动作优先触发，因为主动漫游的时候触发了其他动作，
            //   导致漫游动作无法触发」。真凶见 PetView.lockScene 的注释
            //   （临时场景会无条件替换、且到点回的是 IDLE 而不是漫游）。
            if (petImage.isAnimPack()) petImage.lockScene(SceneRegistry.ROAM);
            roamMs = petImage.currentClipDurationMs(3000);
        }
        // ★★ 轮次㊻：**前摇 / 后摇**（用户原话：「在漫游动作里面加一个漫游开始的延迟还有
        //   结束的延迟，用来控制动画前后摇的逻辑」）。
        //   动画整段播完 = total；其中头 lead 毫秒是"蓄力"（人还没动）、
        //   末 tail 毫秒是"收尾"（人已停住）；**真正在走的只有中间那段**。
        //   这样位移的起止时刻与画面里的迈步对齐（旧逻辑开播即走、走到即停 ⇒ 完全错位）。
        long total = Math.max(1200, roamMs);
        long lead = 0, tail = 0;
        try {
            lead = Prefs.wanderLeadMs(this);
            tail = Prefs.wanderTailMs(this);
        } catch (Throwable ignore) {}
        // ★★ 轮次㊼：**按动作**的前后摇（用户要求「可以单独设置每个动作的前后摇，
        //   用来适配不同角色不同动画的动作」）。
        //   解析顺序：本动作在覆盖层里有记录 ⇒ 用它；没有 ⇒ 用全局值。
        //   ★ 必须在 setScene(ROAM) **之后**取 clipId：那时才知道这一趟抽到的是哪个动作。
        String clipId = (petImage == null) ? null : petImage.currentClipId();
        boolean byClip = false;
        try {
            RoamTiming.Timing ov = RoamTiming.pick(
                    RoamTiming.load(this, CharacterStore.currentId(this)), clipId);
            if (ov != null) { lead = ov.lead; tail = ov.tail; byClip = true; }
        } catch (Throwable ignore) {}
        // 中间必须留出"真的在走"的时间：前后摇之和不得超过 total - 600ms（超了就按比例压）
        long budget = Math.max(0, total - 600);
        if (lead + tail > budget && lead + tail > 0) {
            long sum = lead + tail;
            lead = (long) (lead * (double) budget / sum);
            tail = (long) (tail * (double) budget / sum);
        }
        walkStartAtMs = clipStart + lead;              // 开始移动
        walkEndAtMs = clipStart + total - tail;        // 停止移动
        walkSceneEndAtMs = clipStart + total;          // 动画播完（回待机）
        walkStartX = cur;
        wanderTargetX = tx;
        wandering = true;
        // ★ 轮次㊲：开走那一刻说"漫游"场景的台词（没写自定义就不说）
        saySceneLine(SceneRegistry.ROAM);
        android.util.Log.i("DSPWander", "walk " + cur + "→" + tx + "（范围 "
                + minX + "~" + maxX + "）dir=" + (dir > 0 ? "右" : "左")
                + " 起点x=" + cur + " 目标x=" + tx
                + " manual=" + manual + " 动作时长=" + roamMs + "ms 前摇="
                + lead + "ms 后摇=" + tail + "ms 走=" + (walkEndAtMs - walkStartAtMs)
                + "ms 摇源=" + (byClip ? "动作" : "全局") + " clip=" + clipId
                + " dockOnArrive=" + wanderDockOnArrive);
        stepWander();
        return true;
    }

    /** 推进一步漫游位移（"走到目标"与"动作播完"两个条件都要满足才回待机） */
    private void stepWander() {
        try {
            // ★ 轮次60：这条早退也要**解锁** —— 不解的话场景会永远锁在漫游上
            //   （root/params 被销毁后再也没人来解锁）
            if (!wandering || params == null || root == null) {
                wandering = false;
                unlockRoamScene();
                return;
            }
            // ★ 走路算"有活动"：否则静止自动贴边的计时器会到点把正在散步的桌宠收成竖条
            dockResetIdle();
            long nowMs = System.currentTimeMillis();
            // ★★ 轮次㊻ ① **前摇**：动画已经开播、人还没开始动 —— 站住不动，等前摇走完。
            //   旧逻辑是"开播即走"，于是动画还在蓄力（原地左转奔跑前 ~2 秒内容几乎不动）
            //   人就已经窜出去了 ⇒ 位移和画面里的迈步完全对不上（用户报的"动画播放逻辑不对"）。
            if (nowMs < walkStartAtMs) {
                // ★★ 轮次70：**前摇期间一个字都不许写 x / y**。
                //   旧代码在这一支里把 x 强行写回起点（那一行已删）—— 那是整条漫游路径上
                //   **唯一的瞬时赋值**：只要开走前有谁动过 x（换 clip 之后的钳制就会），
                //   桌宠就会被瞬间拉回去，看起来就是"凭空一挪"。
                //   现在什么都不做：起点 x 在 roamOnce 里采集之后，到这一刻为止没有任何人写 x
                //   （前置位移默认关 + 可达范围已冻结）⇒ walkStartX 就是开走前的真实位置。
                wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);
                return;
            }
            // ★★ 轮次70：设备侧唯一能证明"没人动过它"的读数（每段漫游只打一次）
            if (!roamStartLogged) {
                roamStartLogged = true;
                android.util.Log.i("DSPWander", "开走前 x=" + params.x + " y=" + params.y
                        + "（起点 x=" + walkStartX + "）"
                        + (params.x == walkStartX ? " 未经任何前置位移" : " ★ 与起点不一致：x 被谁改过"));
            }
            // ★★ 轮次㊸：位移按**时间线性插值**（原来是"剩余距离 ÷ 剩余步数、每步 ceil"）。
            //   旧算法的 `ceil` 让平均步长偏大 ⇒ 实测 **提前 2.4 秒到位**，然后
            //   **站着把漫游动作播完** —— 用户看到的正是"动作在走、位置不动"
            //   （「漫游完位置不对应」）。插值之后：到位时刻 == 该停的时刻，
            //   而且最后一步精确落在 wanderTargetX 上（旧算法允许差 6px）。
            long span = Math.max(1, walkEndAtMs - walkStartAtMs);
            float t = (nowMs - walkStartAtMs) / (float) span;
            if (t > 1f) t = 1f;
            int x = walkStartX + (int) Math.round((wanderTargetX - walkStartX) * (double) t);
            // 到位（或时间到）⇒ 精确落点，再等**后摇**播完
            if (t >= 1f) {
                params.x = wanderTargetX;
                clampToScreen();
                try { applyRootLayout(); } catch (Exception ignore) {}
                android.util.Log.i("DSPWander", "到位 x=" + params.x + " y=" + params.y
                        + "（目标 x=" + wanderTargetX + "）");
                positionBalance();
                if (longPressShowing) positionLongPressBubble();
                wandering = false;
                // ★★ ② **后摇**：人已经停住，动画还要把最后那段"收尾"播完才回待机
                long rest = walkSceneEndAtMs - nowMs;
                if (rest > 60) {
                    android.util.Log.i("DSPWander", "到位，留后摇 " + rest + "ms 等动作播完");
                    wanderHandler.postDelayed(walkFinish, rest);
                } else {
                    walkFinish.run();
                }
                return;
            }
            params.x = x;
            clampToScreen();
            try { applyRootLayout(); } catch (Exception ignore) {}
            positionBalance();
            if (longPressShowing) positionLongPressBubble();
            wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);
        } catch (Exception e) {
            wandering = false;
            unlockRoamScene();          // ★ 轮次60：异常退出也要解锁，否则场景永久锁死
            startWander();
        }
    }

    /** 桌宠本体在容器中的可见宽度（px）。容器可能被气泡撑宽，故取两者较小值。 */
    private int petVisibleWidth() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float d = dm.density;
        float scale = Prefs.getScale(this);
        int petW = (int) (d * BASE_PET_W * scale);
        int winW = root != null ? root.getWidth() : 0;
        if (winW <= 0) return petW;
        return Math.min(petW, winW);
    }

/**
     * 临时收起 / 恢复桌宠（连同余额气泡）。
     *
     * 与「退出桌宠」的区别：这里**不停止服务**，只是不显示，
     * 因此心情、漫游位置、计时器都还在，回到桌面立刻恢复原样。
     */
    private void setPetTempHidden(boolean hidden) {
        petTempHidden = hidden;
        try {
            int vis = hidden ? View.GONE : View.VISIBLE;
            if (root != null) root.setVisibility(vis);
            // 气泡跟随桌宠；藏桌宠时气泡也没有存在意义
            if (balRoot != null) balRoot.setVisibility(
                    hidden ? View.GONE : (isVisible ? View.VISIBLE : View.GONE));
            if (hidden) {
                // ★ 轮次85：挂边态要**冻住时间轴** —— 挂了边再被临时收起（或锁屏）时，
                //   帧循环还在每 33ms 写一次 PetView 姿态，纯属白耗电，而且看不见。
                try { if (peekPhase != PeekPose.OFF) peekFreeze(); } catch (Throwable ignore) {}
                // 正在显示的气泡/输入条也一并收起，否则会孤零零挂在设置页上
                if (bubbleShowing) { handler.removeCallbacks(hideBubbleTask); hideBubbleTask.run(); }
                try { hideAskBar(); } catch (Exception ignore) {}
                try { hideLongPressBubble(); } catch (Exception ignore) {}
                // ★ 监测条侧栏是**独立悬浮窗**，桌宠藏起来它不会跟着藏 ——
                //   不显式收掉就会孤零零挂在设置页上（与上面几条同理）。
                try { monitorHideSide(); } catch (Throwable ignore) {}
                try { if (actionRoot != null) actionRoot.setVisibility(View.GONE); } catch (Exception ignore) {}
                try { stopBubbleMemeVideo(); } catch (Throwable ignore) {}
                // 藏起期间暂停贴边 idle 计时（看不见时不该自动贴边）
                try { handler.removeCallbacks(dockIdleTask); } catch (Throwable ignore) {}
            } else {
                // 恢复时若不是我们藏起来的（ACTION_STOP 已退出），就别硬拉回来
                dockResetIdle();
                // ★ 轮次85：挂边被冻住的，恢复显示时接着呼吸（不进 SQUEEZE，免得又挤一次）
                try {
                    if (peekPhase != PeekPose.OFF) peekEnterPhase(PeekPose.PERCH);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable t) {
            android.util.Log.w("DSPet", "临时显隐失败: " + t);
        }
    }

    /**
     * 更新桌宠悬浮窗布局。
     *
     * 所有 updateViewLayout(root, params) 收敛到这一个方法，
     * 以后再加需要跟随桌宠的窗口只需改这里一处。
     *
     * <p>★ 用户报障（2026-10-07）"移动桌宠的时候太卡顿" —— 这个方法在拖动时
     *   **每帧都会被调用**，而一次 {@code updateViewLayout} 就是一次
     *   WindowManager 跨进程布局请求。所以：
     *   <ol>
     *     <li>位置/尺寸**没变就不发**（拖动时手指停下、或 dx/dy 取整成 0 的那些帧，
     *         原本照样发一遍）；</li>
     *     <li>顺手把**跟随桌宠的那些浮层**也在这里同步（现在只有侧栏监测条），
     *         免得每个移动路径各写一遍 —— 这本来就是这个方法存在的理由。</li>
     *   </ol>
     */
    private void applyRootLayout() {
        if (root == null || params == null || windowManager == null) return;
        boolean same = params.x == lastLpX && params.y == lastLpY
                && params.width == lastLpW && params.height == lastLpH;
        if (!same) {
            lastLpX = params.x; lastLpY = params.y;
            lastLpW = params.width; lastLpH = params.height;
            windowManager.updateViewLayout(root, params);
        }
        followMonitorSide();
        applyGestureExclusion();
    }

    /**
     * 让侧栏监测条**实时**跟着桌宠走（用户要求："就在桌宠旁边跟随桌宠位置实时移动"）。
     *
     * <p>以前只有"布局变化 / 5 秒巡检"才会重算它的位置 —— 拖动桌宠时它就停在原地，
     * 于是越拖越远。现在收敛到 {@link #applyRootLayout()}（所有移动路径都会走这里：
     * 拖动、吸附跟手、悬停回弹、动画轮播位移…），并且 {@link #positionMonitorSide()}
     * 自带"位置没变就不发窗口更新"，所以每帧调用不会额外增加跨进程开销。
     */
    private void followMonitorSide() {
        if (!monitorSideShowing) return;
        // ★ 节流（80ms ≈ 12 次/秒）：拖动时**每帧**发一次悬浮窗更新，在弱机/软渲染上
        //   本身就会掉帧；而侧栏是个小胶囊，跟随频率降到 12Hz 肉眼完全看不出区别。
        //   采样实测：模拟器（ro.hardware.egl=emulation，软件 GL）每帧多发一次
        //   updateViewLayout 会把掉帧率从 12% 推到 80%。
        //   其它调用点（monitorRender 每秒、巡检每 5 秒、布局变化）**不走这个节流**，
        //   所以停下来之后位置一定会被纠正。
        long now = android.os.SystemClock.uptimeMillis();
        if (now - monitorFollowAtMs < MONITOR_FOLLOW_MIN_MS) return;
        monitorFollowAtMs = now;
        try { positionMonitorSide(); } catch (Throwable ignore) {}
    }



    /**
     * 把桌宠窗口登记成**系统手势排除区**（API 29+）。
     *
     * <p>★★ 真机实测（Android 16 / pet411）挂边态拖不动的真凶：
     * <pre>
     * InputDispatcher: Channel [Gesture Monitor] edge-swipe is stealing input gesture
     *                  for device -1 from [com.deepseek.pet, [Gesture Monitor] swipe-up]
     * </pre>
     * 桌宠贴在屏幕左/右缘，手指一贴着边缘动，系统就判定成「返回手势」并**取消**我们
     * 的手势流（只来 2 个 MOVE 就 CANCEL，看起来就是"拖不动/闪一下"）。
     * 这不是本项目的手势逻辑问题，是系统边缘手势，只能靠排除区让开。
     *
     * <p>系统限制：**每侧**排除高度最多 200dp；桌宠窗口 354px/2.625 ≈ 135dp，在限内。
     * 窗口挂出屏外的那部分由系统自己裁到屏内，不用管。
     */
    private void applyGestureExclusion() {
        if (android.os.Build.VERSION.SDK_INT < 29 || root == null) return;
        try {
            int w = root.getWidth(), h = root.getHeight();
            if (w <= 0 || h <= 0) return;
            if (w == gestExclW && h == gestExclH) return;
            gestExclW = w;
            gestExclH = h;
            java.util.List<android.graphics.Rect> one = java.util.Collections.singletonList(
                    new android.graphics.Rect(0, 0, w, h));
            root.setSystemGestureExclusionRects(one);
            // 有的机型只认"被触摸的那个 View"的排除区，所以子 View 也登记一份
            if (petImage != null && petImage.getWidth() > 0) {
                petImage.setSystemGestureExclusionRects(java.util.Collections.singletonList(
                        new android.graphics.Rect(0, 0, petImage.getWidth(), petImage.getHeight())));
            }
        } catch (Throwable ignore) {}
    }

    private void clampToScreen() {
        try {
            if (params == null) return;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            refreshRealSize();
            int screenW = screenW();
            int screenH = screenH();

            // 窗口实际尺寸（优先运行时值，取不到则按配置估算）
            int winW = root != null ? root.getWidth() : 0;
            int winH = root != null ? root.getHeight() : 0;
            if (winW <= 0 || winH <= 0) {
                float d = dm.density;
                float scale = Prefs.getScale(this);
                winW = (int) Math.max(d * 240 * scale, d * BASE_PET_W * scale);
                winH = (int) (d * BASE_PET_H * scale);
            }

            // 允许窗口超出屏幕左右，使“角色可见内容”正好贴到物理屏幕边缘。
            // ★ 判据只有一份：ContentInset.xRange（拖动/吸附/热区/漫游共用）
            int[] rx = edgeRangeX();
            int minX = rx[0];
            int maxX = rx[1];
            int minY = 0;
            int maxY = Math.max(0, screenH - winH);
            // 窗口比屏幕还高时（极小屏/横屏），允许垂直方向也略微超出以保证内容可见
            if (winH > screenH && winH > 0) {
                maxY = screenH - winH;
            }
            if (maxX < minX) maxX = minX;
            if (maxY < minY) maxY = minY;

            // ★★ 轮次70：漫游期间改用**触发那一刻冻结的范围**（见 roamMinX 的字段注释）——
            //   否则换 clip 之后每步都会按新的内容留白把窗口重新夹一遍 = 漫游里被"凭空挪"。
            if (roamFrozenX && roamMaxX >= roamMinX) { minX = roamMinX; maxX = roamMaxX; }
            if (params.x < minX) params.x = minX;
            if (params.x > maxX) params.x = maxX;
            if (params.y < minY) params.y = minY;
            if (params.y > maxY) params.y = maxY;
        } catch (Exception ignore) {}
    }

    /** 桌宠窗口宽度（px）：优先运行时值，未布局时按 240dp×scale 估算 */
    private int petWinWidth() {
        int winW = root != null ? root.getWidth() : 0;
        if (winW <= 0) {
            float d = getResources().getDisplayMetrics().density;
            winW = (int) (d * BASE_PET_W * Prefs.getScale(this));
        }
        return winW;
    }

    /**
     * 桌宠窗口 x 的**可达范围** {minX, maxX} —— 让"角色可见内容"贴到物理屏幕边缘。
     *
     * <p>★ 全工程只有这一处算这个范围（拖动钳制 / 松手吸附 / 边缘热区 / 漫游目标）。
     * 以前四处各算一遍：前三处按内容留白算，**漫游那处只按视口留白算**
     * （内置包左右各留 33%，于是漫游走到边上还差 210px，"到边缘自动贴边"也判不出来）。
     *
     * <p>判据本身在纯类 {@link ContentInset#xRange}（有 fpstest 断言），这里只负责取数。
     */
    private int[] edgeRangeX() {
        int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
        // ★ 轮次77：探头态下**必须**放宽 —— 挂边本来就是"内容挂出屏外"，
        //   不放宽的话，任何一次 clampToScreen()（换动画 / 分辨率变化 / 气泡定位）
        //   都会把挂好的探头一把拽回屏内（症状：刚挂上去就自己缩回来了）。
        if (peekPhase != PeekPose.OFF) {
            int out = (int) (peekBodyW * 0.55f) + dp(2f);
            return new int[]{rx[0] - out, rx[1] + out};
        }
        return rx;
    }

    /** 布局变化后（启动/缩放）钳制位置：post 到布局完成再执行，确保拿到真实尺寸 */
    private void clampPositionsAfterLayout() {
        if (root == null) return;
        root.post(new Runnable() {
            @Override public void run() {
                if (root == null || params == null) return;
                clampToScreen();
                try { applyRootLayout(); } catch (Exception ignore) {}
                Prefs.savePos(FloatingService.this, params.x, params.y);
                positionBalance();
                if (longPressShowing) positionLongPressBubble();
                // 侧栏监测条跟着桌宠走（尺寸/旋转/缩放变化后必须重算，否则会错位）
                positionMonitorSide();
            }
        });
    }

    /** 设置改变后重建（保留当前位置） */
    private void rebuildViews() {
        // 贴边态重建：先复位状态机（新 buildViews 出来的是完整形象，不能沿用竖条），
        // 位置沿用吸附前的存档位（用户切回来看到的是原来放的地方，不是边缘）。
        // 竖条是 container 的子 View，随旧 root 一起拆掉，这里只需清引用。
        dockCancelAnim();
        dockHideStrip();
        dockStrip = null;
        // ★ 轮次77：探头态残留清理（服务销毁 / 重建视图都走这三行）——
        //   探头的 rotation/pivot 是写在 petImage 上的，重建后新 View 是干净的，
        //   但不 abort 的话旧阶段的 Runnable 还会继续往新 View 上写姿态。
        peekAbort();
        handler.postDelayed(new Runnable() {
            @Override public void run() { peekResumeIfAtEdge(); }
        }, 400);
        // 监测条三格随旧 container 一起拆掉；buildViews 里会重建。
        // ★ 侧面那颗独立窗口**不随 container 走**，必须显式隐藏，
        //   否则重建后屏幕上会留下一个"幽灵监测条"（独立窗口不跟着 root 一起移除）。
        monitorHideSide();
        monitorBar = null;
        monitorCellTop = null;
        monitorCellMid = null;
        monitorCellBottom = null;
        dockClearNudge();
        handler.removeCallbacks(dockIdleTask);
        handler.removeCallbacks(dockEdgeHintRunnable);
        dockSwiping = false;
        dockSwipeActive = false;
        dockSwipeProgress = 0f;
        if (dockState == PetDockState.COLLAPSED && dockSavedValid) {
            dockState = PetDockState.NORMAL;
        } else if (dockState == PetDockState.EXPANDED) {
            dockState = PetDockState.NORMAL;
        }
        if (actionRoot != null && windowManager != null) {
            try { windowManager.removeView(actionRoot); } catch (Exception ignore) {}
        }
        actionRoot = null;
        actionShowing = false;
        leftScrollView = null;
        actionScroll = null;
        actionLevel1 = null;
        actionList = null;
        actionBody = null;
        leftScrollLp = null;
        actionScrollLp = null;
        hideAskBar();
        hideLongPressBubble();
        if (bubbleShowing) { handler.removeCallbacks(hideBubbleTask); hideBubbleTask.run(); }
        int oldX = params != null ? params.x : 100;
        int oldY = params != null ? params.y : 300;
        if (balRoot != null && windowManager != null) {
            try { windowManager.removeView(balRoot); } catch (Exception ignore) {}
        }
        balRoot = null;
        text = null;
        balanceParams = null;
        if (root != null) {
            try { if (petImage != null) petImage.clearAnimation(); } catch (Exception ignore) {}
            try { windowManager.removeView(root); } catch (Exception ignore) {}
        }
        // 背景层必须和桌宠一起拆掉再重建：否则改了背景图 / 缩放后，
        // 旧的那层会一直留在屏幕上（新层叠在下面看不见，看起来像"没生效"）。
        root = null;
        buildViews();
        if (root == null || params == null) return;
        params.x = oldX;
        params.y = oldY;
        try { applyRootLayout(); } catch (Exception ignore) {}
        Prefs.savePos(this, oldX, oldY);
        // App 在前台时重建（改缩放/换背景都会触发）不能把桌宠重新弹回来
        if (petTempHidden) setPetTempHidden(true);
        final android.view.View _t = text;
        if (_t != null) _t.post(new Runnable() { @Override public void run() { positionBalance(); } });
    }

/** 悬浮服务专用对话框 Builder：show 时自动设置 overlay 窗口类型，避免 BadTokenException */
    private class OverlayDialogBuilder extends AlertDialog.Builder {
        OverlayDialogBuilder(android.content.Context c) { super(c); }
        @Override public AlertDialog show() {
            AlertDialog d = create();
            try {
                android.view.Window w = d.getWindow();
                if (w != null) {
                    w.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
                }
            } catch (Exception ignore) {}
            try { d.show(); } catch (Exception e) {
                android.util.Log.e("DSVoice", "对话框显示失败: " + e);
            }
            return d;
        }
    }

    /** 便捷：所有弹窗走 overlay 版本 */
    private AlertDialog.Builder dlg() { return new OverlayDialogBuilder(this); }


    /** 长按桌宠：弹出功能入口（动作菜单 / 调整菜单位置大小 / 进入工作模式） */
    private void showMenu() {
        // ★ 用户要求：长按菜单里加「进入工作模式」；**工作模式关着就不显示**这一项。
        //   所以菜单项要动态拼，点击索引也得跟着偏移（不能写死 which == 0）。
        final boolean canWork = com.deepseek.pet.dsh.DshBridge.workEnabled(this);
        final java.util.List<String> list = new java.util.ArrayList<String>();
        if (canWork) list.add("进入工作模式");
        list.add("打开动作菜单");
        list.add("调整菜单位置 / 大小");
        list.add("锁定当前参数");
        list.add("解除锁定");
        final String[] items = list.toArray(new String[list.size()]);
        final int base = canWork ? 1 : 0;
        dlg()
                .setTitle(Prefs.menuLocked(this) ? "菜单（参数已锁定）" : "菜单")
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int which) {
                        if (canWork && which == 0) {
                            enterWorkModeFromService();
                            return;
                        }
                        int i = which - base;
                        if (i == 0) {
                            showActionBubble();
                        } else if (i == 1) {
                            showMenuTuneDialog();
                        } else if (i == 2) {
                            Prefs.setMenuLocked(FloatingService.this, true);
                            showBubble("已锁定菜单参数", 2000);
                        } else {
                            Prefs.setMenuLocked(FloatingService.this, false);
                            showBubble("已解除锁定", 2000);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 从长按菜单进入工作模式。
     *
     * <p>★ 用户报障“点了没反应”。服务里 startActivity 在 Android 10+ 会被
     * “后台启动 Activity 限制”静默拦掉（不报异常）。所以这里三步：
     * ① 直接起；② 2.5 秒后核对 DshHarnessActivity 是否真创建了；
     * ③ 没创建就发通知兜底（点通知一定能进）。
     */
    private void enterWorkModeFromService() {
        final long asked = android.os.SystemClock.elapsedRealtime();
        com.deepseek.pet.dsh.DshBridge.setEnabled(this, true);
        com.deepseek.pet.dsh.DshBridge.setWorkMode(this, true);
        com.deepseek.pet.dsh.DshBridge.setWorkLaunchAskedAt(asked);
        try {
            hideLongPressBubble();
        } catch (Throwable ignore) { }
        Intent wi = workIntent();
        try {
            startActivity(wi);
            android.util.Log.i("DSPWork", "菜单点击：已 startActivity asked=" + asked);
        } catch (Throwable t) {
            android.util.Log.w("DSPWork", "直接起失败：" + t);
            notifyWorkFallback();
            return;
        }
        new Handler(getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                long created = com.deepseek.pet.dsh.DshBridge.workCreateAt();
                if (created < asked) {
                    android.util.Log.w("DSPWork", "被拦：created=" + created + " asked=" + asked);
                    notifyWorkFallback();
                }
            }
        }, 2500);
    }

    /** 工作模式 Intent（独立任务，后台卡片才会留）。 */
    private Intent workIntent() {
        Intent wi = new Intent(this, DshHarnessActivity.class);
        wi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return wi;
    }

    /** 兜底：后台启动被拦时发通知，点一下就进工作模式。 */
    private void notifyWorkFallback() {
        try {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(this, 7301, workIntent(), flags);
            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(this, ensurePetChannel())
                    : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_manage)
                    .setContentTitle("工作模式已就绪")
                    .setContentText("系统拦了后台启动，点这里进入")
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(7301, b.build());
            showBubble("点顶部通知进入工作模式", 2500);
        } catch (Throwable t) {
            android.util.Log.w("DSPWork", "通知兜底也失败：" + t);
            showBubble("进不去工作模式", 2500);
        }
    }

    /** 实时刷新当前展开气泡的布局（重建视图并重新定位） */
    private void liveRefreshMenu() {
        rebuildViews();
        showActionBubble();
        // ★ 长按菜单同理：松手后按新参数重建（如果开着）。
        if (longPressShowing) { hideLongPressBubble(); showLongPressBubble(); }
    }

    /** 菜单布局微调：内容大小 / 底板大小 / 内边距 / 水平・垂直偏移 */
    private void showMenuTuneDialog() {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (d * 18);
        box.setPadding(pad, pad, pad, pad);

        final TextView lbContent = new TextView(this);
        lbContent.setTextSize(15);
        box.addView(lbContent);
        final SeekBar sbContent = new SeekBar(this);
        sbContent.setMax(200);
        box.addView(sbContent);

        final TextView lbPanel = new TextView(this);
        lbPanel.setTextSize(15);
        box.addView(lbPanel);
        final SeekBar sbPanel = new SeekBar(this);
        sbPanel.setMax(200);
        box.addView(sbPanel);

        final TextView lbInset = new TextView(this);
        lbInset.setTextSize(15);
        box.addView(lbInset);
        final SeekBar sbInset = new SeekBar(this);
        sbInset.setMax(100);
        box.addView(sbInset);

        final TextView lbOffX = new TextView(this);
        lbOffX.setTextSize(15);
        box.addView(lbOffX);
        final SeekBar sbOffX = new SeekBar(this);
        sbOffX.setMax(800);
        box.addView(sbOffX);

        final TextView lbOffY = new TextView(this);
        lbOffY.setTextSize(15);
        box.addView(lbOffY);
        final SeekBar sbOffY = new SeekBar(this);
        sbOffY.setMax(800);
        box.addView(sbOffY);

        final float cMin = Prefs.MENU_CONTENT_MIN, cMax = Prefs.MENU_CONTENT_MAX;
        final float pMin = Prefs.MENU_PANEL_MIN, pMax = Prefs.MENU_PANEL_MAX;
        final float iMin = Prefs.MENU_INSET_MIN, iMax = Prefs.MENU_INSET_MAX;

        final Runnable sync = new Runnable() {
            @Override public void run() {
                float cs = Prefs.getMenuContentScale(FloatingService.this);
                float ps = Prefs.getMenuPanelScale(FloatingService.this);
                float ir = Prefs.getMenuInsetRatio(FloatingService.this);
                int ox = Prefs.getMenuOffsetX(FloatingService.this);
                int oy = Prefs.getMenuOffsetY(FloatingService.this);
                lbContent.setText(String.format("菜单内容大小：%.2f×", cs));
                lbPanel.setText(String.format("背景底板大小：%.2f×", ps));
                lbInset.setText(String.format("菜单相对内板边距：%.0f%%", ir * 100));
                lbOffX.setText("水平偏移：" + ox + " dp");
                lbOffY.setText("垂直偏移：" + oy + " dp");
                sbContent.setProgress((int) ((cs - cMin) / (cMax - cMin) * 200));
                sbPanel.setProgress((int) ((ps - pMin) / (pMax - pMin) * 200));
                sbInset.setProgress((int) ((ir - iMin) / (iMax - iMin) * 100));
                sbOffX.setProgress(ox + 400);
                sbOffY.setProgress(oy + 400);
            }
        };
        sync.run();

        SeekBar.OnSeekBarChangeListener lis = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser) return;
                if (sb == sbContent) {
                    Prefs.saveMenuContentScale(FloatingService.this, cMin + (p / 200f) * (cMax - cMin));
                } else if (sb == sbPanel) {
                    Prefs.saveMenuPanelScale(FloatingService.this, pMin + (p / 200f) * (pMax - pMin));
                } else if (sb == sbInset) {
                    Prefs.saveMenuInsetRatio(FloatingService.this, iMin + (p / 100f) * (iMax - iMin));
                } else if (sb == sbOffX) {
                    Prefs.saveMenuOffsetX(FloatingService.this, p - 400);
                } else if (sb == sbOffY) {
                    Prefs.saveMenuOffsetY(FloatingService.this, p - 400);
                }
                sync.run();
                if (actionShowing) { positionActionBubble(); }
                // ★ 拖动中就重建长按菜单（如果开着）：调参所见即所得。
                //   旧代码只在松手 liveRefreshMenu（且只重建三击菜单），
                //   长按菜单开着调参毫无反馈 —— "调了没效果"之一。
                //   重建开销两行菜单可忽略；hide+show 会重走定位，菜单跟手。
                if (longPressShowing) { hideLongPressBubble(); showLongPressBubble(); }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { liveRefreshMenu(); }
        };
        sbContent.setOnSeekBarChangeListener(lis);
        sbPanel.setOnSeekBarChangeListener(lis);
        sbInset.setOnSeekBarChangeListener(lis);
        sbOffX.setOnSeekBarChangeListener(lis);
        sbOffY.setOnSeekBarChangeListener(lis);

        if (!actionShowing) { showActionBubble(); }

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        dlg()
                .setTitle("调整菜单位置 / 大小")
                .setView(sv)
                .setPositiveButton("锁定参数", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface di, int w) {
                        Prefs.setMenuLocked(FloatingService.this, true);
                        showBubble("已锁定菜单参数", 2000);
                    }
                })
                .setNeutralButton("完成", null)
                .show();
    }

    // ===== 动作选择气泡（窗口气泡形式，随桌宠位置在左/右） =====
    private android.widget.FrameLayout actionRoot;
    private android.widget.ScrollView leftScrollView;
    private LinearLayout.LayoutParams leftScrollLp;
    private LinearLayout.LayoutParams actionScrollLp;
    private android.widget.LinearLayout actionList;
    private LinearLayout actionLevel1;
    private android.widget.LinearLayout actionBody;
    /** 所有一级分类中二级项数的最大值：用于固定气泡高度，切换分类不拉伸 */
    private android.widget.ScrollView flatScroll;
    private android.widget.LinearLayout flatList;
    private int menuMaxRows = 0;
    private android.widget.ScrollView actionScroll;
    private WindowManager.LayoutParams actionParams;
    private boolean actionShowing = false;
    /** 动作菜单内容相对用户气泡缩放的基准系数 */
    private static final float MENU_SCALE = 0.25f;

    /**
     * 三击菜单一级列表顶部的「主动漫游」入口文案。
     *
     * <p>★ 轮次㊸（用户原话：「**先增加主动漫游在三击菜单里**」）：它让桌宠**真的走一段**
     * （与自动漫游同一条路径），不是"播一个 clip"，所以**不放进动作分类**里 ——
     * 混在动作列表里会让人以为它只是播个动画。
     */
    private static final String ROAM_MENU_LABEL = "主动漫游（走一段）";

    private void showActionBubble() {
        if (windowManager == null) return;
        if (petImage == null || !petImage.isAnimPack()) {
            showBubble("动画包未启用", 3000);
            return;
        }
        final java.util.List<String> anims = petImage.animAllNames();
        if (anims == null || anims.isEmpty()) {
            showBubble("没有可播放的动作", 3000);
            return;
        }
        final float d = getResources().getDisplayMetrics().density;
        // 菜单内容缩放 / 底板缩放（沿用设置项）
        final float bscale = Prefs.getBubbleScale(this) * MENU_SCALE * Prefs.getMenuContentScale(this);
        final float panelScale = Prefs.getBubbleScale(this) * MENU_SCALE * Prefs.getMenuPanelScale(this);
        final java.util.LinkedHashMap<String, java.util.List<String>> groups = classifyAnims(anims);
        // ★ 悬浮窗不进 uiautomator dump ⇒ 设备侧只能靠这行日志核对"动作菜单一共列了几个"
        //   （轮次⑰：列出 88 → 106 就是靠它验的；以前 18 个事件/移动动画根本不在名单里）
        {
            int total = 0;
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.List<String>> e : groups.entrySet()) {
                total += e.getValue().size();
                sb.append(e.getKey()).append('=').append(e.getValue().size()).append(' ');
            }
            // ★ 轮次㊸：末尾的 `roam=1` = 一级列表顶部那个「主动漫游」入口在（设备脚本按它核对）
            android.util.Log.i("DSPAnim", "动作菜单 n=" + total + " 可播=" + anims.size()
                    + " roam=1  " + sb);
        }

        if (actionRoot == null) {
            actionRoot = new android.widget.FrameLayout(this);

            LinearLayout body = new LinearLayout(this);
            actionBody = body;
            body.setOrientation(LinearLayout.VERTICAL);
            body.setBackground(UiKit.card(this, Tokens.of(this), Tokens.R_DIALOG));
            int panelPad = UiKit.panelInset(this) + UiFactory.dp(this, 8);
            body.setPadding(panelPad, panelPad, panelPad, panelPad);

            actionLevel1 = new LinearLayout(this);
            actionLevel1.setOrientation(LinearLayout.HORIZONTAL);
            body.addView(actionLevel1, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            flatScroll = new android.widget.ScrollView(this);
            flatScroll.setVerticalScrollBarEnabled(true);
            flatScroll.setFillViewport(false);

            flatList = new LinearLayout(this);
            flatList.setOrientation(LinearLayout.VERTICAL);
            flatList.setPadding(0, 0, 0, 0);
            flatScroll.addView(flatList, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

            body.addView(flatScroll, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            actionRoot.addView(body, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        }

        buildFlatMenu(groups, d, bscale);

        if (actionParams == null) {
            actionParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            actionParams.gravity = Gravity.TOP | Gravity.LEFT;
            applyCutoutMode(actionParams);    // 与桌宠同坐标系，否则横屏会错位
        }
        if (!actionShowing) {
            try {
                windowManager.addView(actionRoot, actionParams);
                actionShowing = true;
            } catch (Exception e) {
                android.util.Log.w("DSMenu", "addView fail: " + e);
                return;
            }
        } else {
            try { windowManager.updateViewLayout(actionRoot, actionParams); } catch (Exception ignore) {}
        }
        positionActionBubble();
    }

    /** 一级只显示分类；点分类进入二级动作列表，返回键回到分类。 */
    private void buildFlatMenu(final java.util.LinkedHashMap<String, java.util.List<String>> groups,
                               float d, float bscale) {
        if (flatList == null) return;
        flatList.removeAllViews();
        if (actionLevel1 != null) {
            actionLevel1.removeAllViews();
            final android.widget.TextView back = newChip("动作分类", d, 1f, false, null);
            actionLevel1.addView(back, new LinearLayout.LayoutParams(
                    0, UiFactory.dp(this, Tokens.H_CONTROL), 1f));
            final android.widget.TextView close = newChip("×", d, bscale, false, new View.OnClickListener() {
                @Override public void onClick(View v) { hideActionBubble(); }
            });
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                    UiFactory.dp(this, Tokens.H_CONTROL), UiFactory.dp(this, Tokens.H_CONTROL));
            closeLp.leftMargin = UiFactory.dp(this, 6);
            actionLevel1.addView(close, closeLp);
            back.setEnabled(false);
            close.setContentDescription("关闭动作菜单");
        }
        final float textScale = Math.max(0.85f, bscale * 3.0f);
        final int pad = UiFactory.dp(this, 4);
        flatList.setPadding(pad, pad, pad, pad);
        // ★ 轮次㊸：一级（分类层）顶部放「主动漫游」——用户要求「先增加主动漫游在三击菜单里」。
        //   点分类进二级时会被 removeAllViews 清掉，点「‹ 返回分类」回来时又出现。
        //   ★ 它**不受漫游总开关限制**（手动触发），但走完不会因此打开自动漫游。
        {
            final android.widget.TextView roam = newChip(ROAM_MENU_LABEL, d, textScale, true,
                    new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hideActionBubble();
                    if (!roamOnce(true)) {
                        android.util.Log.i("DSPWander", "manual roam 没走起来（贴边态/已到边/正在走）");
                    }
                }
            });
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UiFactory.dp(this, Tokens.H_CONTROL));
            rlp.bottomMargin = UiFactory.dp(this, 6);
            flatList.addView(roam, rlp);
            // ★ 悬浮窗不进 uiautomator dump ⇒ 设备脚本按文字点不到这一行。
            //   照工程既有手法：**让代码把它自己的屏幕坐标打进日志**（见 STATE 第五节）。
            roam.post(new Runnable() {
                @Override public void run() {
                    try {
                        int[] xy = new int[2];
                        roam.getLocationOnScreen(xy);
                        android.util.Log.i("DSMenu", "roam chip " + xy[0] + "," + xy[1]
                                + " " + roam.getWidth() + "x" + roam.getHeight());
                    } catch (Throwable ignore) {}
                }
            });
        }
        for (final java.util.Map.Entry<String, java.util.List<String>> e : groups.entrySet()) {
            final android.widget.TextView cat = newChip(e.getKey(), d, textScale, true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    flatList.removeAllViews();
                    flatList.setPadding(pad, pad, pad, pad);
                    if (actionLevel1 != null && actionLevel1.getChildCount() > 0) {
                        TextView back = (TextView) actionLevel1.getChildAt(0);
                        back.setText("‹ 返回分类");
                        back.setContentDescription("返回分类，当前：" + e.getKey());
                        back.setEnabled(true);
                    }
                    for (String name : e.getValue()) {
                        android.widget.TextView chip = newChip(name, d, textScale);
                        flatList.addView(chip, new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                UiFactory.dp(FloatingService.this, Tokens.H_CONTROL)));
                    }
                    flatScroll.scrollTo(0, 0);
                    positionActionBubble();
                }
            });
            flatList.addView(cat, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, UiFactory.dp(this, Tokens.H_CONTROL)));
        }
        if (actionLevel1 != null && actionLevel1.getChildCount() > 0) {
            actionLevel1.getChildAt(0).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { buildFlatMenu(groups, d, bscale); positionActionBubble(); }
            });
        }
        if (flatScroll != null) flatScroll.scrollTo(0, 0);
    }

    private android.widget.TextView newChip(final String name, float d, float bscale,
                                            boolean primary, View.OnClickListener listener) {
        android.widget.TextView tv = UiFactory.button(this, Tokens.of(this), name, primary);
        tv.setTextSize(Math.max(14f, Math.min(20f, 14f * bscale)));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int hp = UiFactory.dp(this, 14);
        tv.setPadding(hp, 0, hp, 0);
        tv.setMinHeight(UiFactory.dp(this, Tokens.H_CONTROL));
        tv.setMinimumHeight(UiFactory.dp(this, Tokens.H_CONTROL));
        tv.setContentDescription(name);
        tv.setOnClickListener(listener);
        return tv;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);
        return row;
    }

    private android.widget.TextView newChip(final String name, float d, float bscale) {
        return newChip(name, d, bscale, false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                hideActionBubble();
                playActionByName(name);
            }
        });
    }

    /** 把动作按关键词归入一级分类（保持出现顺序） */
    private java.util.LinkedHashMap<String, java.util.List<String>> classifyAnims(java.util.List<String> anims) {
        java.util.LinkedHashMap<String, java.util.List<String>> m =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        // ★ v7.0：用户自定义分类排在最前（设置页「自定义动作菜单」可增删，见 ActionMenuConfig）。
        //   已归入自定义分类的动作**不再出现在默认分类里**，避免同一个动作在菜单里出现两次。
        java.util.Set<String> claimed = new java.util.HashSet<String>();
        try {
            java.util.LinkedHashMap<String, java.util.List<String>> custom =
                    ActionMenuConfig.categories(this, CharacterStore.currentId(this));
            for (java.util.Map.Entry<String, java.util.List<String>> e : custom.entrySet()) {
                java.util.List<String> only = new java.util.ArrayList<String>();
                for (String n : e.getValue()) {
                    if (anims.contains(n) && !claimed.contains(n)) { only.add(n); claimed.add(n); }
                }
                if (!only.isEmpty()) m.put("\u2605 " + e.getKey(), only);
            }
        } catch (Throwable ignore) {}
        String[] cats = {"工作学习", "吃东西", "节日节气", "才艺表演", "余额", "点击回应", "碎碎念", "休闲日常"};
        for (String c : cats) m.put(c, new java.util.ArrayList<String>());
        for (String n : anims) {
            if (claimed.contains(n)) continue;      // 已归入自定义分类
            String c;
            if (n.startsWith("余额-")) c = "余额";
            else if (n.startsWith("点击回应-")) c = "点击回应";
            else if (n.startsWith("碎碎念-")) c = "碎碎念";
            else if (n.startsWith("工作状态") || n.startsWith("写代码") || n.equals("写福字")
                    || n.startsWith("深度思考") || n.equals("轻快记录") || n.startsWith("原地敲击桌面")) c = "工作学习";
            else if (n.startsWith("吃") || n.equals("涮火锅") || n.equals("大口吃零食")
                    || n.startsWith("偷吃") || n.equals("是啊，吃什么")) c = "吃东西";
            else if (n.contains("中秋") || n.contains("重阳") || n.contains("端午") || n.contains("腊八")
                    || n.contains("元宵") || n.contains("圣诞") || n.contains("除夕") || n.contains("新年")
                    || n.contains("讨糖") || n.contains("插茱萸") || n.contains("穿针乞巧")
                    || n.contains("孔明灯") || n.contains("河灯") || n.contains("放烟花")
                    || n.contains("堆雪人") || n.contains("舞狮") || n.contains("青团")) c = "节日节气";
            else if (n.contains("魔术") || n.contains("舞") || n.contains("演奏") || n.contains("小提琴")
                    || n.contains("吹笛") || n.contains("抛接") || n.contains("魔方") || n.contains("五子棋")
                    || n.contains("摇扇") || n.contains("哼歌") || n.contains("写福字") || n.contains("转")) c = "才艺表演";
            else c = "休闲日常";
            m.get(c).add(n);
        }
        java.util.LinkedHashMap<String, java.util.List<String>> out =
                new java.util.LinkedHashMap<String, java.util.List<String>>();
        for (java.util.Map.Entry<String, java.util.List<String>> e : m.entrySet()) {
            if (!e.getValue().isEmpty()) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    // ==================== 时节素材联动 ====================

    /** 节日/节气 -> 素材名匹配表；命中就播放专属动画 */
    private static final String[][] FESTIVAL_ANIMS = {
        {"中秋节", "中秋赏月吃月饼", "吃西瓜", "放孔明灯", "撸猫", "荡秋千"},
        {"元宵节", "吃汤圆", "放孔明灯", "放河灯", "凭空生花"},
        {"端午节", "吃粽子", "踢毽子", "玩水枪", "放风筝"},
        {"腊八节", "吃腊八粥", "吃糖葫芦", "涮火锅", "原地小憩沉眠"},
        {"重阳节", "吃重阳糕", "插茱萸赏菊", "放风筝", "超大伸懒腰"},
        {"七夕", "穿针乞巧", "放河灯", "凭空生花", "照镜子"},
        {"春节", "写福字", "舞狮头", "放烟花", "收红包", "吃年糕"},
        {"除夕", "放烟花", "收红包", "吃饺子", "拆礼物"},
        {"小年", "吃年糕", "吃糖葫芦", "收红包"},
        {"圣诞节", "装点圣诞树", "拆礼物", "吹气球"},
        {"平安夜", "装点圣诞树", "拆礼物", "凭空生花"},
        {"冬至", "吃饺子", "涮火锅", "原地小憩沉眠"},
        {"立春", "吃青团", "放风筝", "被落叶淹没"},
        {"清明", "吃青团", "放风筝", "放河灯"},
        {"大雪", "堆雪人", "原地漂浮踏步", "哈欠连天"},
        {"小雪", "堆雪人", "原地漂浮踏步", "超大伸懒腰"},
        {"万圣节", "讨糖南瓜灯", "萌化小幽灵", "被吓一跳"},
    };

    /** 按当前节日/节气挑一个专属动作；没有返回 null */
    private String pickFestivalAnim() {
        String f = TimeContext.festival();
        if (f == null) return null;
        for (String[] row : FESTIVAL_ANIMS) {
            if (row[0].equals(f)) {
                String id = row[1 + (int) (Math.random() * (row.length - 1))];
                return id;
            }
        }
        return null;
    }

    /** 按当前时段挑一个应景动作（清晨刷牙 / 深夜打瞌睡 / 中午吃饭 等） */
    private String pickPeriodAnim() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String[] pool;
        if (h >= 5 && h < 9)       pool = new String[]{"晨间刷牙", "吃早餐", "大口吃零食"};
        else if (h >= 11 && h < 14) pool = new String[]{"吃午餐", "是啊，吃什么", "涮火锅"};
        else if (h >= 14 && h < 18) pool = new String[]{"摇扇纳凉", "悠闲哼歌", "轻快摇摆舞"};
        else if (h >= 18 && h < 20) pool = new String[]{"吃晚餐", "吃冰淇淋融化", "撸猫"};
        else if (h >= 20 && h < 23) pool = new String[]{"荡秋千", "扑克魔术", "撸猫", "悠闲哼歌"};
        else                        pool = new String[]{"打瞌睡被惊醒", "哈欠连天", "原地小憩沉眠"};
        return pool[(int) (Math.random() * pool.length)];
    }

    /** 时节动作候选：节日与时段各给一次机会，返回一个候选 id 交给 PetView 加权随机。
     *  注意这里只决定"哪个动作享有更高权重"，不决定是否出现——
     *  是否出现、以及其余动作如何随机，都由 PetView.pickWeightedRotate 统一处理。 */
    private String pickSeasonalAnim() {
        String fest = (TimeContext.festival() != null) ? pickFestivalAnim() : null;
        String peri = pickPeriodAnim();
        if (fest == null) return peri;
        if (peri == null) return fest;
        // 节日期间：节日动作权重更高，但仍有概率把加成让给时段动作
        return (Math.random() < 0.6) ? fest : peri;
    }

    /** 点击动作项：播放该动作，并弹出对应台词 */
    private void playActionByName(String name) {
        if (petImage == null) return;
        // ★★ 轮次61：**用户手点的动作优先级最高**（用户原话：「点动作的时候，有时候会被其他
        //   动作打断，更改一下逻辑，让当前触发动作优先级最高」）。
        //   exclusive=true ⇒ PetView 在播放期间上锁，自动场景（整点/碎碎念/工作状态/
        //   轮播/余额…）抢不走它；播完或用户碰它自动解锁。
        String id = petImage.playAnimByName(name, true);
        if (id == null) { showBubble("🎬 无法播放：" + name, 2500); return; }
        // ★ 设备侧证据：悬浮窗不进 uiautomator dump ⇒ "有没有上锁"只能靠日志验
        android.util.Log.i("DSPAnim", "手点动作 name=" + name + " id=" + id + " 已上锁（播完/被碰解锁）");
        String line = petImage.animLine(id);
        if (line == null || line.isEmpty()) line = name;
        showPetSay("💬 ", line, 6000, null);
    }

    /**
     * 事件动画：随机播一个该组的动画（balance / whisper / workStatus）。
     *
     * <p>★ 这一组来自 {@code pet_anim.json} 的 {@code events} 节点 —— 它以前
     * **全工程没人读**（{@code PetAnimPack.load()} 里没有 {@code optJSONObject("events")}，
     * {@code pickEvent()} 只有定义、零调用点），于是 15 个事件动画躺在 APK 里永远不播。
     * 本方法就是它们的出口；找不到该组时**静默跳过**（外部素材包可能没有这些组）。
     */
    private void playEventAnim(final String group) {
        if (petImage == null || !petImage.isAnimPack()) return;
        final String id = petImage.animRandomEventName(group);
        if (id == null) return;
        handler.post(new Runnable() {
            @Override public void run() {
                String played = petImage.playAnimByName(id);
                android.util.Log.i("DSPAnim", "事件动画 group=" + group + " id=" + id
                        + " played=" + played);
            }
        });
    }

    /** 按名字播一个动画（余额档位要对准档位，不能用随机的）。name 为 null 时静默跳过。 */
    private void playNamedAnim(final String name, final String logTag) {
        if (petImage == null || name == null || !petImage.isAnimPack()) return;
        handler.post(new Runnable() {
            @Override public void run() {
                String played = petImage.playAnimByName(name);
                android.util.Log.i("DSPAnim", logTag + " 动画=" + name + " played=" + played);
            }
        });
    }

    private static final String[] BALANCE_ANIMS = {
            "余额-分文不剩", "余额-袋空如洗", "余额-数金皱眉",
            "余额-钱袋如常", "余额-金袋叮当", "余额-钱袋满溢"};

    /** 余额档位（0=最低 … 5=最充足），与 {@link #BALANCE_ANIMS} 一一对应 */
    private static int balanceBand(double bal) {
        if (bal < 1.0) return 0;
        if (bal < 5.0) return 1;
        if (bal < 10.0) return 2;
        if (bal < 20.0) return 3;
        if (bal < 50.0) return 4;
        return 5;
    }

    /** 余额**档位变化**时演一个对应动作（首次只记档，不做"每次刷新都演"） */
    private void maybeBalanceAnim(double bal) {
        int band = balanceBand(bal);
        if (band == lastBalanceAnimBand) return;
        boolean first = (lastBalanceAnimBand < 0);
        lastBalanceAnimBand = band;
        if (first) return;                 // 启动第一次刷新不演，避免一开机就蹦钱袋
        playNamedAnim(BALANCE_ANIMS[band], "余额档位动画 band=" + band);
        // ★ 轮次㊲：台词选项与动作选项同步 —— 余额档位变化时说"余额事件"场景的台词
        saySceneLine(SceneRegistry.EV_BALANCE);
    }

    // ==================== 长按桌宠：功能气泡（退出 / 重启）====================

    // ─── 浮层统一主题 ───
    //
    // ★ 第二十三轮：这一整组常量原先都是**写死的浅色**
    //   （PANEL_FILL_TOP 纯白 / MID 极浅蓝 / BOT 浅蓝 / EDGE 淡蓝 / TEXT 深蓝），
    //   四个浮层（气泡、输入框、动作菜单、长按菜单）共用它们，
    //   于是深色主题下全是刺眼的白面板。
    //
    //   现在改为**从 Tokens 取**（见 floatPanelBg / floatInputBg / floatPrimaryBg /
    //   floatSecondaryBg），四套主题（新拟态·浅深 × 玻璃·浅深）自动跟随。
    //
    //   仅保留圆角常量 —— 圆角是**几何**、不随主题变，且它是"四个浮层看起来是
    //   一套东西"的关键，必须共用一份。
    private static final float THEME_RADIUS_DP = 22f;          // 外圆角，与 bubble_chat.xml 对齐
    private static final float THEME_RADIUS_IN_DP = 19f;       // 内圆角

    private android.widget.FrameLayout longPressRoot;
    private PointerView longPressTail;          // 指向桌宠的小尾巴
    private int longPressTailDir = PointerView.DIR_LEFT;
    private LinearLayout longPressBody;
    private WindowManager.LayoutParams longPressParams;
    private boolean longPressShowing = false;
    /** 长按气泡沿用动作菜单的缩放基准，视觉风格一致 */
    private static final float LONGPRESS_MENU_SCALE = 0.25f;

    private void showLongPressBubble() {
        if (windowManager == null) return;
        if (longPressShowing) { hideLongPressBubble(); return; }
        final float d = getResources().getDisplayMetrics().density;
        // ★ 缩放语义（报障：无论怎么调都大于气泡；调了没效果）：
        //   contentScale 默认 4.0，LONGPRESS 的 0.25 就是为配它而设的
        //   （0.25×4.0=1.0，三击菜单同理）—— 默认档 bscale=1.0×bubble 是对的，
        //   上一版除以 4 把默认打到 0.25，菜单缩成一小坨（截图确认，已回退）。
        //   真正的元凶是两个写死的量：
        //   ① minW=d*124 不跟字号 —— content 调到 0.5 时字剩 2sp，面板还是 124dp；
        //   ② maxRowW 无上限 —— 字调大后单行不换行宽轻松超半屏。
        //   另："背景底板大小"滑块旧代码根本没读，调了白调 —— 这里按三击菜单
        //   同款钳制（1~1.5）乘上，滑块真正生效。
        final float bscale = Prefs.getBubbleScale(this) * LONGPRESS_MENU_SCALE
                * Prefs.getMenuContentScale(this);
        // ★ panelK 只跟"背景底板大小"滑块（默认 0.125→系数 1.0，还原旧行为）。
        //   下限放开到 0.24（滑块最小 0.03÷0.125）：旧代码钳到 1，
        //   滑块前 2/3 行程全是死的 —— 这就是"怎么调都不合适"之一。
        //   上限保持 1.5（拉满 4.0→32 会顶翻桌面）。
        //   不掺 bubble —— bubble 已在 bscale 里起过作用，
        //   再乘一次会被钳制吃掉（上一版就是这么错的）。
        final float panelK = Math.max(0.24f, Math.min(1.5f,
                Prefs.getMenuPanelScale(this) / 0.125f));

        if (longPressRoot == null) {
            longPressRoot = new android.widget.FrameLayout(this);
            LinearLayout body = new LinearLayout(this);
            longPressBody = body;
            body.setOrientation(LinearLayout.VERTICAL);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                body.setElevation(d * 5);              // 柔和投影，浮在桌面上
            }
            longPressTail = new PointerView(this, panelFillColor(), panelEdgeColor(), d);
            longPressRoot.addView(body, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
            longPressRoot.addView(longPressTail, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        // ★ 每次重建都刷新面板背景 + 内边距（报障：菜单调节没有效果）：
        //   旧代码只在 longPressRoot==null 建一次，之后复用整棵树 ——
        //   调完缩放 prefs 变了，已建好的背景/字号/padding 全是旧值，
        //   不杀进程永远看不到变化。代价是每次打开多建两个 Drawable，
        //   两行的菜单这点开销可忽略。
        // ★ 取消底部气泡：body 背景透明、无内边距，两颗悬浮钮独立悬浮。
        //   宽度仍按内容估算钳制（定位/尾巴要一个确定的面板框），
        //   只是不再画底板 —— 视觉上没有"盒子"了。
        longPressBody.setBackground(null);
        longPressBody.setPadding(0, 0, 0, 0);
        if (longPressTail != null) {
            longPressTail.setFill(panelFillColor());
        }
        longPressBody.removeAllViews();
        // ★ 行的配色改为**主题令牌**：
        //   「退出」用 danger 系（危险动作），「重启」用 accent 系（中性操作）。
        //   原来是写死的淡玫瑰 / 淡蓝 —— 深色主题下那两块浅色药丸在深色面板上
        //   非常突兀。现在四种主题下都有对应的 soft/danger 令牌。
        // ★ 两行不做颜色区分：都用气泡同款（底 surface + 字 text1）。
        //   原来退出 danger 系、重启 accent 系 —— 用户要求"同步气泡颜色不做区分"。
        //   按钮与面板底同色后，靠圆角 + 行间距留白 + 水波纹分区（类似 action sheet）。
        Tokens.Set T = uiT();
        // ★ 用户要求：长按桌宠气泡菜单里要有「进入工作模式」；
        //   工作模式总开关关着就（整项不显示）。
        //   ★ 注意：这里才是**真正弹出来的**长按菜单（气泡），
        //   下面那个 showMenu() 的 AlertDialog 现在没人调，别在那里加。
        try {
            if (com.deepseek.pet.dsh.DshBridge.workEnabled(this)) {
                longPressBody.addView(newMenuChip("BOX", "进入工作模式", d, bscale,
                        T.surface, T.text1, new View.OnClickListener() {
                    @Override public void onClick(View v) { enterWorkModeFromService(); }
                }));
            }
        } catch (Throwable ignore) { }
        longPressBody.addView(newMenuChip("LOGOUT", "退出桌宠", d, bscale,
                T.surface, T.text1, new View.OnClickListener() {
            @Override public void onClick(View v) { exitPetWithFarewell(); }
        }));
        longPressBody.addView(newMenuChip("REFRESH", "重启桌宠", d, bscale,
                T.surface, T.text1, new View.OnClickListener() {
            @Override public void onClick(View v) { restartPetWithStretch(); }
        }));
        // ★ 贴边吸附：第 3 项（dockEnabled 开关控制显隐）。
        //   COLLAPSED 下文案为"展开 / 恢复"，其余态为"贴边吸附"。
        //   ★ 轮次77：**正挂着边**时这一项变成"拉出来"（点了就平滑收回屏内）——
        //     挂边态下再点"贴边吸附"只会重挂一次，用户看着像"点了没反应"。
        //   图标复用 PIN（图钉，语义=固定到边缘），无需新增矢量。
        boolean dockOn = true;
        try { dockOn = Prefs.dockEnabled(this); } catch (Throwable ignore) {}
        final boolean isDocked = (dockState == PetDockState.COLLAPSED);
        final boolean isPeeking = (peekPhase != PeekPose.OFF);
        if (dockOn) {
            final String dockLabel = isPeeking ? "拉出来"
                    : (isDocked ? "展开 / 恢复" : "贴边吸附");
            longPressBody.addView(newMenuChip("PIN", dockLabel, d, bscale,
                    T.surface, T.text1, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (peekPhase != PeekPose.OFF) peekCancel();
                    else if (dockState == PetDockState.COLLAPSED) dockExpand();
                    else dockCollapse(false);
                }
            }));
        }

        // ★ 监测条开关 + 显示项入口（2026-10-07 用户要求）。
        //   ★ 开关只改"显不显示"，**不动游戏助手总开关** ——
        //     临时收起这条东西不该把整个功能连带关掉（设置页那边还在用采样数据）。
        //   ★★ 用户 2026-10-07 追加要求：**这两项只在「游戏助手」打开时才出现** ——
        //      功能没开的时候，菜单里摆两条点了没用的入口只会让人以为坏了。
        final boolean barOn = Prefs.monitorBar(this);
        boolean assistOn = false;      // ★ 不能声明成 final：try/catch 两处赋值会被 javac 判"可能重复赋值"
        try { assistOn = Prefs.gameAssistant(this); } catch (Throwable ignore) {}
        if (assistOn) {
            longPressBody.addView(newMenuChip("CHART", barOn ? "隐藏监测条" : "显示监测条",
                    d, bscale, T.surface, T.text1, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Prefs.setMonitorBar(FloatingService.this, !barOn);
                    hideLongPressBubble();
                    monitorSync();
                }
            }));
            // 「显示项…」直达设置页的游戏助手小节（那里面就是上/中/下三个选择）
            longPressBody.addView(newMenuChip("SLIDERS", "显示项…", d, bscale,
                    T.surface, T.text1, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hideLongPressBubble();
                    openSettingsSection(7);
                }
            }));
        }
        // 设备侧只能靠日志验"菜单里有几项"（悬浮窗不在 uiautomator dump 里）
        android.util.Log.i("DSPMenu", "longpress chips=" + longPressBody.getChildCount()
                + " assist=" + assistOn + " barOn=" + barOn);

        // ★ 面板宽 = 内容估算钳制（重建版）：不再量行 View，
        //   量行 View 的宽永远带着"上一轮布局"的旧影子 —— 这就是按钮溢出面板的根因。
        //   文案固定（"退出桌宠"/"重启桌宠"/"贴边吸附"/"展开 / 恢复"），直接拿当前字号的
        //   Paint 量文字，加图标 + 行 padding + body padding，一次算准。
        //   行根 MATCH_PARENT + LinearLayout 纵向，父容器给 EXACTLY 约束，
        //   行从根上超不出面板 —— 这是钉死"按钮不得超过气泡"的结构保证。
        refreshRealSize();
        // TextView.setTextSize(float) 单位是 sp → px 要乘 scaledDensity，
        // 这里同换算，保证量出的字宽与行内 TextView 同字号。
        android.graphics.Paint pp = new android.graphics.Paint();
        pp.setTextSize(13.0f * bscale * getResources().getDisplayMetrics().scaledDensity);
        float tw = Math.max(pp.measureText("退出桌宠"), pp.measureText("重启桌宠"));
        tw = Math.max(tw, pp.measureText("贴边吸附"));
        tw = Math.max(tw, pp.measureText("展开 / 恢复"));
        // 监测条那两行也要参与量宽，否则面板比按钮窄（本工程踩过"按钮溢出面板"）。
        // ★ 只在**真的加了那两行**时才算进去：功能关掉时它们是空的，
        //   算进去只会让面板白宽一截（并且与"菜单里到底有几项"不一致）。
        if (assistOn) {
            tw = Math.max(tw, pp.measureText("隐藏监测条"));
            tw = Math.max(tw, pp.measureText("显示监测条"));
            tw = Math.max(tw, pp.measureText("显示项…"));
        }
        int rowPad = (int) (d * 15 * bscale);
        int iconW = (int) (d * 16 * bscale + d * 8 * bscale);
        int bodyPad = 0;   // ★ body 已透明、无内边距（见上），面板框 = 内容宽
        int contentW = (int) (tw + iconW + rowPad * 2 + 1.5f);
        int maxW = (int) (Math.max((int) (d * 124), screenW() / 3) * panelK);
        int minW = (int) (d * 124 * Math.max(0.5f, Math.min(2.0f, bscale)) * panelK);
        int panelW = contentW + bodyPad * 2;
        if (panelW < minW) panelW = minW;
        if (panelW > maxW) panelW = maxW;
        longPressBody.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                panelW, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        if (longPressParams == null) {
            longPressParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            longPressParams.gravity = Gravity.TOP | Gravity.LEFT;
            applyCutoutMode(longPressParams); // 与桌宠同坐标系，否则横屏会错位
        }
        positionLongPressBubble();
        try {
            windowManager.addView(longPressRoot, longPressParams);
            longPressShowing = true;
        } catch (Exception e) {
            android.util.Log.w("DSMenu", "longPress addView fail: " + e);
            longPressShowing = false;
            return;
        }
        // 点气泡外部自动收起；但拖动桌宠本身不算「点外部」，否则一动就消失
        longPressRoot.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    if (isPetTouchPoint(ev.getRawX(), ev.getRawY())) return false;
                    hideLongPressBubble();
                }
                return false;
            }
        });
        longPressRoot.postOnAnimation(new Runnable() {
            @Override public void run() {
                longPressRoot.postOnAnimation(new Runnable() {
                    @Override public void run() { positionLongPressBubble(); }
                });
            }
        });
    }

    /** 长按菜单的悬浮按钮（重建版）：图标 + 文字横向，独立悬浮、无面板底。
     *
     * <p>★ 用户要求"取消底部气泡改为悬浮按钮"：body 背景透明，
     * 两颗钮各自悬浮（UiKit.raised 凸起表面 + UiFactory.pressFx 按压缩放，
     * 与主体 UI 按钮同一套手感），不再是"面板里的两行药丸"。
     * 小尾巴保留（指向桌宠）。
     * <p>★ 防溢出的结构保证：行根 MATCH_PARENT，父容器（纵向 LinearLayout）
     * 在面板宽固定后给行 EXACTLY 约束 —— 行从根上超不出面板。
     * 旧实现量行 View 定面板宽，量的永远是"上一轮"的旧尺寸，
     * 面板框小、按钮大（报障截图确认），已删除。
     * <p>★ 图标同步主题：VectorIcon 单色矢量，颜色取行文字色
     * （气泡同款 text1）；尺寸 16dp×bscale 等比。
     * <p>★ 行间距等比：d*3×bscale，字缩小时缝跟着缩。
     */
    private android.widget.LinearLayout newMenuChip(final String iconName, final String text,
                                                    final float d, final float bscale,
                                                    int fill, int textColor,
                                                    View.OnClickListener l) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Tokens.Set t = uiT();
        row.setBackground(UiKit.raised(this, t, Tokens.R_PILL, fill, UiFactory.inset(this)));
        row.setClickable(true);
        row.setOnClickListener(l);
        UiFactory.pressFx(row);   // 悬浮钮按压缩放（与主体 UI 按钮同一套手感）
        int pv = (int) (d * 12 * bscale);
        row.setPadding((int) (d * 15 * bscale), pv, (int) (d * 15 * bscale), pv);
        View icon = VectorIcon.view(this, iconName, textColor, 16f * bscale);
        android.widget.LinearLayout.LayoutParams ilp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.rightMargin = (int) (d * 8 * bscale);
        row.addView(icon, ilp);
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(13.0f * bscale);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        row.addView(tv, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = lp.bottomMargin = (int) (d * 3 * bscale);
        row.setLayoutParams(lp);
        return row;
    }

    /**
     * 圆润主题面板：大圆角 + 主题底板 + 描边。
     *
     * <p>★ 第二十三轮：原来是**写死的白→浅蓝渐变**，深色主题下是一块刺眼的白板。
     * 现在用 {@link #floatPanelBg}（令牌 modal + 按引擎选描边），
     * 与新拟态/玻璃 × 浅色/深色四套主题自动一致。
     */
    private android.graphics.drawable.Drawable menuPanelBg(float d) {
        return floatPanelBg(THEME_RADIUS_DP);
    }

    /** 面板底板色（给小尾巴用，必须与面板同色，否则尾巴和面板之间会有色差缝） */
    private int panelFillColor() {
        return uiT().modal;
    }

    /** 面板描边色（同上，尾巴的描边要接上面板的） */
    private int panelEdgeColor() {
        Tokens.Set t = uiT();
        int stroke = Tokens.skin(this) == Tokens.SKIN_GLASS
                ? t.glassBorder : t.hairline;
        return stroke == 0 ? t.hairline : stroke;
    }

    /** 小尾巴：一个指哪打哪的三角，把气泡和桌宠连起来 */
    private static class PointerView extends View {
        static final int DIR_LEFT = 1, DIR_RIGHT = 2, DIR_TOP = 3, DIR_BOTTOM = 4;
        private int dir = DIR_LEFT;
        private final android.graphics.Paint pFill =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path path = new android.graphics.Path();

        PointerView(android.content.Context c, int fill, int stroke, float d) {
            super(c);
            pFill.setStyle(android.graphics.Paint.Style.FILL);
            pFill.setColor(fill);
        }

        void setDir(int d) { if (dir != d) { dir = d; invalidate(); } }
        void setFill(int color) { pFill.setColor(color); invalidate(); }

        @Override protected void onDraw(android.graphics.Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            path.reset();
            switch (dir) {
                case DIR_LEFT:   path.moveTo(0, h / 2); path.lineTo(w, 0); path.lineTo(w, h); break;
                case DIR_RIGHT:  path.moveTo(w, h / 2); path.lineTo(0, 0); path.lineTo(0, h); break;
                case DIR_TOP:    path.moveTo(w / 2, 0); path.lineTo(0, h); path.lineTo(w, h); break;
                default:         path.moveTo(w / 2, h); path.lineTo(0, 0); path.lineTo(w, 0); break;
            }
            path.close();
            cv.drawPath(path, pFill);
        }
    }

    /** 长按气泡定位：贴住桌宠（优先并排左右，放不下就上/下），桌宠移动时实时跟随 */
    private void positionLongPressBubble() {
        if (longPressRoot == null || longPressParams == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        final int screenW = screenW();
        final int screenH = screenH();
        final float dens = dm.density;
        int margin = (int) (dens * 6);
        int gap = (int) (dens * 3);       // 小间距：视觉上紧贴桌宠

        if (longPressRoot.getMeasuredWidth() == 0 || longPressRoot.getMeasuredHeight() == 0) {
            longPressRoot.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            0, android.view.View.MeasureSpec.UNSPECIFIED),
                    android.view.View.MeasureSpec.makeMeasureSpec(
                            0, android.view.View.MeasureSpec.UNSPECIFIED));
        }
        int tailW = (int) (dens * 9);
        int tailH = (int) (dens * 15);
        // ★ 只读实测、不再覆盖 body 尺寸：body 宽是构建时定死的，
        //   这里只拿来算位置。旧代码用实测值 setLayoutParams 覆盖 body，
        //   拿"上一轮"的旧宽压新布局 —— 溢出的根因，已删除。
        //   blp 高度 WRAP（行高由内容定，不锁死）。
        int panelW = longPressBody.getMeasuredWidth();
        int panelH = longPressBody.getMeasuredHeight();
        if (panelW <= 0) panelW = (int) (dens * 130);
        if (panelH <= 0) panelH = (int) (dens * 70);
        int bw = longPressRoot.getMeasuredWidth();
        int bh = longPressRoot.getMeasuredHeight();
        if (bw <= 0) bw = panelW + tailW;
        if (bh <= 0) bh = panelH;

        // 用「可见宠物区域」而不是整窗，气泡才会贴在宠物本体旁边
        int[] cr = contentRectOnScreen(dm);
        int petLeft = cr[0], petTop = cr[1], petW = cr[2], petH = cr[3];
        int petRight = petLeft + petW;
        int petBottom = petTop + petH;

        // 尾巴固定在窗口靠桌宠那一侧的竖直/水平中点
        int dir;
        int x, y;
        int spaceLeft = petLeft - margin;
        int spaceRight = screenW - margin - petRight;
        if (Math.max(spaceLeft, spaceRight) >= panelW + tailW + gap) {
            // 并排：气泡在桌宠左/右，尾巴朝左/右
            boolean putRight = spaceRight >= spaceLeft;
            dir = putRight ? PointerView.DIR_LEFT : PointerView.DIR_RIGHT;
            x = putRight ? (petRight + gap) : (petLeft - gap - panelW - tailW);
            y = petTop + petH / 2 - panelH / 2;
        } else {
            // 纵向：气泡在桌宠上/下，尾巴朝上/下
            x = petLeft + petW / 2 - panelW / 2 - tailW / 2;
            int below = petBottom + gap;
            if (below + panelH + tailH <= screenH - margin) {
                dir = PointerView.DIR_TOP;      // 气泡在下方 → 尾巴朝上指着桌宠
                y = below + tailH;
            } else {
                dir = PointerView.DIR_BOTTOM;
                y = petTop - gap - tailH - panelH;
            }
        }
        if (x < margin) x = margin;
        if (x + bw > screenW - margin) x = screenW - margin - bw;
        if (y < margin) y = margin;
        if (y + bh > screenH - margin) y = screenH - margin - bh;

        // 摆好面板与小尾巴的相对位置
        if (longPressTail != null) {
            longPressTail.setDir(dir);
            int[] tailOff = tailOffsets(dir, tailW, tailH, panelW, panelH);
            android.widget.FrameLayout.LayoutParams tlp =
                    new android.widget.FrameLayout.LayoutParams(tailW, tailH);
            tlp.leftMargin = tailOff[0];
            tlp.topMargin = tailOff[1];
            longPressTail.setLayoutParams(tlp);
            // ★ 只摆位置，不管尺寸：宽沿用构建时定的 panelW，
            //   高 WRAP_CONTENT（行高由内容定）。旧代码在这里用实测旧值
            //   setLayoutParams 覆盖 body —— 溢出 + 截断两宗罪的根因，已删除。
            android.widget.FrameLayout.LayoutParams blp =
                    new android.widget.FrameLayout.LayoutParams(
                            panelW,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = tailOff[2];
            blp.topMargin = tailOff[3];
            longPressBody.setLayoutParams(blp);
        }

        longPressParams.x = x;
        longPressParams.y = y;
        try { windowManager.updateViewLayout(longPressRoot, longPressParams); } catch (Exception ignore) {}
    }

    /** 计算尾巴与面板的相对位置：{tailX, tailY, panelX, panelY} */
    private int[] tailOffsets(int dir, int tailW, int tailH, int panelW, int panelH) {
        switch (dir) {
            case PointerView.DIR_LEFT:      // 尾巴在面板左侧，竖直居中
                return new int[]{0, Math.max(0, panelH / 2 - tailH / 2), tailW, 0};
            case PointerView.DIR_RIGHT:     // 尾巴在面板右侧
                return new int[]{panelW, Math.max(0, panelH / 2 - tailH / 2), 0, 0};
            case PointerView.DIR_TOP:       // 尾巴在面板上方，水平居中
                return new int[]{Math.max(0, panelW / 2 - tailW / 2), 0, 0, tailH};
            default:                        // 尾巴在面板下方
                return new int[]{Math.max(0, panelW / 2 - tailW / 2), panelH, 0, 0};
        }
    }

    /** 该屏幕坐标是否落在可见桌宠上（拖动桌宠时不收起长按气泡） */
    private boolean isPetTouchPoint(float rawX, float rawY) {
        int[] cr = contentRectOnScreen(getResources().getDisplayMetrics());
        float pad = getResources().getDisplayMetrics().density * 12f;
        return rawX >= cr[0] - pad && rawX <= cr[0] + cr[2] + pad
                && rawY >= cr[1] - pad && rawY <= cr[1] + cr[3] + pad;
    }

    private void hideLongPressBubble() {
        if (longPressRoot != null && longPressShowing && windowManager != null) {
            try { windowManager.removeView(longPressRoot); } catch (Exception ignore) {}
        }
        boolean wasShowing = longPressShowing;
        longPressShowing = false;
        // 菜单关闭后重排 idle（打开期间 dockResetIdle 被门控跳过，不补这一次
        // idle 计时就永远停在菜单打开那一刻 —— 自动贴边看起来"没生效"）。
        if (wasShowing) dockResetIdle();
    }

    // ==================== 退出 / 重启桌宠 ====================

    /**
     * 退出桌宠：先做「哈欠连天」，再按时段说一句道别，然后真正退出桌宠（停止服务），
     * 不是杀后台进程。
     */
    private void exitPetWithFarewell() {
        // 用户主动退出：记下来，避免重启手机后桌宠又自己冒出来
        try { Prefs.setPetShouldRun(this, false); } catch (Throwable ignore) {}
        // 用户主动退出：停掉看门狗，别把它又拉回来
        try { KeepAlive.cancelWatchdog(this); } catch (Throwable ignore) {}
        hideLongPressBubble();
        if (actionShowing) hideActionBubble();
        if (askShowing) hideAskBar();
        hideVoiceWin();
        if (bubbleShowing) { handler.removeCallbacks(hideBubbleTask); hideBubbleTask.run(); }
        playFarewellAnim();
        // 关掉问候语时不道别（动画保留，保证退出时仍有反应）
        if (Prefs.greetingOn(this)) showBubble(farewellLine(), 100000);
        handler.postDelayed(new Runnable() {
            @Override public void run() { stopSelf(); }
        }, 3600);
    }

    /** 重启桌宠：做「超大伸懒腰」并说一句刚睡醒的问候 */
    private void restartPetWithStretch() {
        hideLongPressBubble();
        if (actionShowing) hideActionBubble();
        if (askShowing) hideAskBar();
        hideVoiceWin();
        if (bubbleShowing) { handler.removeCallbacks(hideBubbleTask); hideBubbleTask.run(); }
        playStretchAnim();
        if (Prefs.greetingOn(this)) {
            final String line = wakeLine();
            showPetSay("", line, 6500, null);
            ChatMemory.append(this, "assistant", line);
        }
    }

    /** 每次打开应用启动桌宠：先伸懒腰，再按当前时段问一声「刚睡醒」 */
    private void playStartupGreeting() {
        if (startupStretchDone) return;
        startupStretchDone = true;
        if (root == null) return;
        playStretchAnim();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (root == null) return;
                if (!Prefs.greetingOn(FloatingService.this)) return;
                final String line = wakeLine();
                showPetSay("", line, 6500, null);
                ChatMemory.append(FloatingService.this, "assistant", line);
            }
        }, 900);
    }

    /** 退出前的哈欠动作；没有动画包时退回内置反应，保证「有反应」 */
    private void playFarewellAnim() {
        boolean ok = false;
        if (petImage != null && petImage.isAnimPack()) {
            ok = petImage.playAnimByName("哈欠连天") != null;
        }
        if (!ok && petImage != null) petImage.reactSad();
    }

    /** 启动 / 重启时的「超大伸懒腰」；没有动画包时退回内置反应 */
    private void playStretchAnim() {
        boolean ok = false;
        if (petImage != null && petImage.isAnimPack()) {
            ok = petImage.playAnimByName("超大伸懒腰") != null;
        }
        if (!ok && petImage != null) petImage.reactHappy();
    }

    /** 按当前时段挑一句「收工道别」台词 */
    private String farewellLine() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String[] pool;
        if (h >= 5 && h < 11) {
            pool = new String[]{"那我先溜啦，你也别太累。", "先走一步咯，记得好好吃饭呀。"};
        } else if (h >= 11 && h < 14) {
            pool = new String[]{"我先去吃饭啦，晚点再找你。", "午间小别一下，你也去吃点东西吧。"};
        } else if (h >= 14 && h < 18) {
            pool = new String[]{"我先眯会儿去啦，晚点见。", "先撤一步，下午也要加油哦。"};
        } else if (h >= 18 && h < 23) {
            pool = new String[]{"那我不打扰你啦，早点休息。", "我先歇着去咯，你也别太晚。"};
        } else {
            pool = new String[]{"夜深了，我先睡咯，你也快休息吧。", "那我先眯了，晚安好梦。"};
        }
        return pool[(int) (Math.random() * pool.length)];
    }

    /** 按当前时段挑一句「刚睡醒」的问候台词 */
    private String wakeLine() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        String[] pool;
        if (h >= 5 && h < 9) {
            pool = new String[]{"早安呀，天亮啦。", "我睡饱啦，早安，今天也要加油哦。"};
        } else if (h >= 9 && h < 12) {
            pool = new String[]{"我醒啦，上午好呀。", "上午好，我已经精神啦。"};
        } else if (h >= 12 && h < 14) {
            pool = new String[]{"我睡醒啦，都中午了呀。", "一觉睡到中午，吃点儿什么好呢。"};
        } else if (h >= 14 && h < 18) {
            pool = new String[]{"午觉睡醒啦，下午好。", "下午好呀，我缓过来啦。"};
        } else if (h >= 18 && h < 23) {
            pool = new String[]{"我醒啦，都到晚上了呀。", "晚上好呀，我睡醒了。"};
        } else {
            pool = new String[]{"这个点醒啦，是我吵到你了吗。", "夜里醒过来啦，我陪着你。"};
        }
        return pool[(int) (Math.random() * pool.length)];
    }

    private void hideActionBubble() {
        if (actionRoot != null && actionShowing && windowManager != null) {
            try { windowManager.removeView(actionRoot); } catch (Exception ignore) {}
        }
        actionShowing = false;
    }

    /** 动作气泡定位：放在桌宠左右两侧空间更充裕的一侧，垂直与桌宠居中，尽量不遮挡桌宠 */
    private void positionActionBubble() {
        if (actionRoot == null || actionParams == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        final int screenW = screenW();
        final int screenH = screenH();
        final float dens = dm.density;

        int margin = (int) (dens * 8);
        int gap = (int) (dens * 10);
        int statusBarH = 0;
        int sbId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (sbId > 0) statusBarH = getResources().getDimensionPixelSize(sbId);
        int topSafe = statusBarH + margin;

        int maxW = Math.max(1, screenW - margin * 2);
        int maxH = Math.max(1, screenH - topSafe - margin);
        float panelScale = Prefs.getBubbleScale(this) * MENU_SCALE * Prefs.getMenuPanelScale(this);
        int bw = Math.min(maxW, Math.round(dens * 280 * Math.max(1f, Math.min(1.5f, panelScale))));
        int panelH = actionBody.getPaddingTop() + actionBody.getPaddingBottom();
        int innerW = Math.max(1, bw - actionBody.getPaddingLeft() - actionBody.getPaddingRight());
        int exactW = View.MeasureSpec.makeMeasureSpec(innerW, View.MeasureSpec.EXACTLY);
        int freeH = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        actionLevel1.measure(exactW, freeH);
        int headerH = actionLevel1.getMeasuredHeight();
        flatList.measure(exactW, freeH);
        int scrollH = Math.max(1, Math.min(flatList.getMeasuredHeight(), maxH - panelH - headerH));
        flatScroll.setLayoutParams(new LinearLayout.LayoutParams(innerW, scrollH));
        actionBody.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                bw, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
        actionRoot.measure(View.MeasureSpec.makeMeasureSpec(bw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        int bh = Math.max(1, Math.min(maxH, actionRoot.getMeasuredHeight()));
        // Clamp the actual overlay, not merely the coordinates used for placement.
        actionParams.width = bw;
        actionParams.height = bh;

        int[] pr = petRectOnScreen(dm);
        int petLeft = pr[0], petTop = pr[1], petW = pr[2], petH = pr[3];

        int spaceRight = screenW - (petLeft + petW);
        int spaceLeft = petLeft;
        boolean useRight;
        if (spaceRight >= bw + gap) useRight = true;
        else if (spaceLeft >= bw + gap) useRight = false;
        else useRight = spaceRight >= spaceLeft;

        int x = useRight ? (petLeft + petW + gap) : (petLeft - gap - bw);

        int y = petTop + petH / 2 - bh / 2;

        // 用户自定义偏移
        x += (int) (dens * Prefs.getMenuOffsetX(this));
        y += (int) (dens * Prefs.getMenuOffsetY(this));

        // 2) 边界安全：无论桌宠在哪，菜单都必须整体留在屏内可点
        if (bw >= maxW) x = margin;
        else {
            if (x < margin) x = margin;
            if (x + bw > screenW - margin) x = screenW - bw - margin;
        }
        if (bh >= maxH) y = topSafe;
        else {
            if (y < topSafe) y = topSafe;
            if (y + bh > screenH - margin) y = screenH - bh - margin;
        }

        android.util.Log.i("DSMenu", "posBubble x=" + x + " y=" + y + " bw=" + bw + " bh=" + bh + " useRight=" + useRight);
        actionParams.x = x;
        actionParams.y = y;
        try { windowManager.updateViewLayout(actionRoot, actionParams); } catch (Exception ignore) {}
    }

    /** 动作选择器：先选分类，再选动画，点选后播放该动作并说出对应台词 */
    private void showAnimPicker() {
        if (petImage == null || !petImage.isAnimPack()) {
            Toast.makeText(this, "动画包未启用", Toast.LENGTH_SHORT).show();
            return;
        }
        final java.util.List<String> cats = petImage.animCategories();
        if (cats.isEmpty()) { Toast.makeText(this, "没有可播放的动作", Toast.LENGTH_SHORT).show(); return; }
        final String[] arr = cats.toArray(new String[0]);
        dlg()
                .setTitle("\uD83C\uDFAC 选择动作分类")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        showAnimListOf(arr[which]);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 展示某分类下的全部动画，点选播放 */
    private void showAnimListOf(final String cat) {
        if (petImage == null) return;
        final java.util.List<String> ids = petImage.animIdsOf(cat);
        if (ids.isEmpty()) { Toast.makeText(this, "该分类暂无动作", Toast.LENGTH_SHORT).show(); return; }
        final String[] arr = ids.toArray(new String[0]);
        dlg()
                .setTitle("\uD83C\uDFAC " + cat + "（共 " + arr.length + " 个）")
                .setItems(arr, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        playAnimChoice(arr[which]);
                    }
                })
                .setNegativeButton("返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { d.dismiss(); showAnimPicker(); }
                })
                .show();
    }

    /** 播放指定动画并弹出其台词 */
    private void playAnimChoice(String id) {
        if (petImage == null || id == null) return;
        petImage.playAnimById(id);
        String line = petImage.animLine(id);
        if (line != null && !line.isEmpty()) {
            showPetSay("\uD83D\uDCAC ", line, 6000, null);
            ChatMemory.append(this, "assistant", line);
        }
    }

    /** 子菜单：对话与记忆 */
    private void showMenuChat() {
        final String[] items = new String[]{
                "\uD83D\uDCAC 和桌宠聊天",
                "\uD83D\uDDE3\uFE0F 现在让桌宠说一句（主动）",
                "\uD83D\uDD50 查看对话历史（" + ChatMemory.size(this) + " 条）",
                "\uD83E\uDDE0 继续上次话题" + (ChatMemory.size(this) > 0 ? "" : "（暂无记忆）"),
                "\uD83D\uDCCF 上下文上限：" + ChatMemory.ctxLimitLabel(this),
                "\uD83D\uDCCC 主动记忆点：" + (ChatMemory.factsEnabled(this) ? "开（" + ChatMemory.factsCount(this) + " 条）" : "关"),
                "\uD83E\uDDF9 清空记忆（历史+记忆点）"
        };
        dlg()
                .setTitle("\uD83D\uDCAC 对话与记忆")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int pos) {
                        d.dismiss();
                        switch (pos) {
                            case 0: openChat(); break;
                            case 1: tryProactiveChat(true); break;
                            case 2: openHistory(); break;
                            case 3: recallLastTopic(); break;
                            case 4: showCtxLimitDialog(); break;
                            case 5: showFactsDialog(); break;
                            case 6: confirmClearMemory(); break;
                        }
                    }
                })
                .show();
    }

    /** 子菜单：用量与计费（峰谷时段 + 余额刷新；今日用量看气泡） */
    private void showMenuUsage() {
        // 今日用量只在余额气泡里展示，这里不再单独开明细入口
        final String[] items = new String[]{
                "\u26A1 当前时段：" + Pricing.tag() + "（" + Pricing.label() + "） · " + Pricing.priceHint(),
                "\u23F3 " + Pricing.nextSwitchHint(),
                "\uD83D\uDCB0 立即刷新余额"
        };
        dlg()
                .setTitle("\u26A1 用量与计费")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int pos) {
                        d.dismiss();
                        switch (pos) {
                            case 0: case 1:
                                showBubble("\u26A1 现在是" + Pricing.label()
                                        + "（" + Pricing.tag() + "），" + Pricing.priceHint()
                                        + "。" + Pricing.nextSwitchHint(), 7000);
                                break;
                            case 2:
                                loading = false;
                                refresh();
                                showBubble("\uD83D\uDCB0 正在刷新余额…", 3000);
                                break;
                        }
                    }
                })
                .show();
    }

    /** 恢复默认角色：同时清除自定义图片 / 动图 / 导入的模型 */
    private void showResetCharacterDialog() {
        UiFactory.dialog(FloatingService.this)
                .setTitle("\uD83D\uDDBC\uFE0F 恢复默认角色")
                .setMessage("将清除已导入的图片、动图与模型，恢复为内置桌宠。")
                .setPositiveButton("恢复", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Prefs.setUseCustomImage(FloatingService.this, false);
                        Prefs.setCustomAnim(FloatingService.this, false);
                        Prefs.setUseCustomModel(FloatingService.this, false);
                        rebuildViews();
                        showBubble("\uD83D\uDDBC\uFE0F 已恢复内置桌宠", 3000);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 子菜单：外观与显示 */
    private void showMenuLook() {
        final boolean builtinPackOn = Prefs.useBuiltinPack(this);
        final String[] items = new String[]{
                builtinPackOn ? "\uD83C\uDFAC 关闭内置动画包" : "\uD83C\uDFAC 启用内置动画包（106 个透明动画）",
                "\uD83D\uDCF7 更换图片（去 App 选图）",
                "\uD83E\uDDF1 导入模型（序列帧/GIF，去 App 选）",
                "\u2795 放大（当前 " + String.format("%.2f", Prefs.getScale(this)) + "\u00D7）",
                "\u2796 缩小（当前 " + String.format("%.2f", Prefs.getScale(this)) + "\u00D7）",
                "\uD83C\uDF9A\uFE0F 精确调节大小",
                "\uD83D\uDD7F\uFE0F 调节透明度",
                "\uD83D\uDD04 恢复默认大小",
                "\uD83D\uDDBC\uFE0F 恢复默认角色",
                isVisible ? "\uD83D\uDE48 隐藏余额气泡" : "\uD83D\uDC41\uFE0F 显示余额气泡"
        };
        dlg()
                .setTitle("\uD83C\uDFA8 外观与显示")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int pos) {
                        d.dismiss();
                        switch (pos) {
                            case 0:
                                boolean on = !Prefs.useBuiltinPack(FloatingService.this);
                                Prefs.setUseBuiltinPack(FloatingService.this, on);
                                if (on) {
                                    Prefs.setUseCustomImage(FloatingService.this, false);
                                    Prefs.setCustomAnim(FloatingService.this, false);
                                    Prefs.setUseCustomModel(FloatingService.this, false);
                                    Prefs.setUseLive2D(FloatingService.this, false);
                                    Prefs.setUseCustomL2D(FloatingService.this, false);
                                }
                                rebuildViews();
                                showBubble(on ? "\uD83C\uDFAC 已启用内置动画包" : "\uD83D\uDDBC\uFE0F 已关闭内置动画包", 3000);
                                break;
                            case 1: Prefs.requestImportModel(FloatingService.this); break;
                            case 2:
                                Prefs.saveScale(FloatingService.this, Prefs.getScale(FloatingService.this) + 0.15f);
                                rebuildViews();
                                break;
                            case 4:
                                Prefs.saveScale(FloatingService.this, Prefs.getScale(FloatingService.this) - 0.15f);
                                rebuildViews();
                                break;
                            case 5: showScaleDialog(); break;
                            case 6: showAlphaDialog(); break;
                            case 7:
                                Prefs.saveScale(FloatingService.this, 1.0f);
                                rebuildViews();
                                break;
                            case 8: showResetCharacterDialog(); break;
                            case 9: toggleBubble(); break;
                        }
                    }
                })
                .show();
    }

    /** 上下文上限选择 */
    private void showCtxLimitDialog() {
        final int[] opts = ChatMemory.CTX_OPTIONS;
        final String[] labels = new String[opts.length];
        int cur = ChatMemory.ctxLimit(this);
        int curIdx = 0;
        for (int i = 0; i < opts.length; i++) {
            labels[i] = (opts[i] <= 0 ? "不带历史（最省 token）" : opts[i] + " 条")
                    + (opts[i] == cur ? "（当前）" : "");
            if (opts[i] == cur) curIdx = i;
        }
        dlg()
                .setTitle("\uD83D\uDCCF 上下文上限")
                .setSingleChoiceItems(labels, curIdx, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        ChatMemory.setCtxLimit(FloatingService.this, opts[which]);
                        d.dismiss();
                        showBubble("\uD83D\uDCCF 上下文已设为：" + ChatMemory.ctxLimitLabel(FloatingService.this), 4000);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 主动记忆点管理 */
    private void showFactsDialog() {
        final boolean on = ChatMemory.factsEnabled(this);
        final String[] items = new String[]{
                on ? "\uD83D\uDCCC 关闭主动记忆点" : "\uD83D\uDCCC 开启主动记忆点",
                "\u270F\uFE0F 编辑记忆点（" + ChatMemory.factsCount(this) + " 条）",
                "\uD83D\uDD04 立即整理记忆点",
                "\uD83D\uDDD1\uFE0F 清空记忆点"
        };
        dlg()
                .setTitle("\uD83D\uDCCC 主动记忆点")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int pos) {
                        d.dismiss();
                        switch (pos) {
                            case 0:
                                ChatMemory.setFactsEnabled(FloatingService.this, !on);
                                showBubble(!on ? "\uD83D\uDCCC 已开启主动记忆点" : "\uD83D\uDCCC 已关闭主动记忆点", 3500);
                                break;
                            case 1: showEditFactsDialog(); break;
                            case 2: triggerFactsNow(); break;
                            case 3:
                                UiFactory.dialog(FloatingService.this)
                                        .setTitle("清空记忆点")
                                        .setMessage("确定删除所有长期记忆点吗？历史对话不受影响。")
                                        .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                                            @Override public void onClick(DialogInterface dd, int w) {
                                                ChatMemory.clearFacts(FloatingService.this);
                                                showBubble("\uD83D\uDDD1\uFE0F 记忆点已清空", 3000);
                                            }
                                        })
                                        .setNegativeButton("取消", null)
                                        .show();
                                break;
                        }
                    }
                })
                .show();
    }

    /** 手动编辑记忆点（每行一条） */
    private void showEditFactsDialog() {
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setMinLines(3);
        et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        et.setText(ChatMemory.facts(this));
        et.setHint("每行一条，例如：\n用户叫小明\n喜欢安静的环境");

        dlg()
                .setTitle("\u270F\uFE0F 编辑记忆点")
                .setView(et)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ChatMemory.setFacts(FloatingService.this, et.getText().toString());
                        showBubble("\uD83D\uDCCC 记忆点已保存（" + ChatMemory.factsCount(FloatingService.this) + " 条）", 3500);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 立即让模型整理一次记忆点 */
    private void triggerFactsNow() {
        if (localOnly()) {
            showBubble("当前是纯本地模式，不会调用 API 哦～", 5000);
            return;
        }
        // ★★ 轮次64：记忆点提炼（{@code MemoryFacts}）走的是**在线**链，
        //    本机小模型干这个又慢又差 ⇒ 本地后端**刻意不放行**，
        //    但必须**说清原因**，否则用户点了没反应会以为功能坏了。
        if (ChatRouter.isLocal(this)) {
            showBubble("整理记忆点要用在线服务商（本机小模型干这个又慢又差）～"
                    + "到「\uD83C\uDF10 服务商」里填个 Key 就能用。", 7000);
            return;
        }
        if (!Prefs.chatEnabled(this) || !Prefs.hasApiKey(this)) {
            showBubble("先在设置里开启对话并填好 API Key 哦～", 5000);
            return;
        }
        if (ChatMemory.size(this) == 0) {
            showBubble("还没聊过什么，先和我说几句吧～", 4000);
            return;
        }
        showBubble("\uD83D\uDD04 正在整理记忆点\u2026", 0);
        MemoryFacts.updateNowAsync(this, Prefs.getApiKey(this), Prefs.getChatModel(this), Prefs.personaForPet(this));
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                showBubble("\uD83D\uDCCC 记忆点（" + ChatMemory.factsCount(FloatingService.this) + " 条）\n"
                        + (ChatMemory.facts(FloatingService.this).length() == 0 ? "（空）" : ChatMemory.facts(FloatingService.this)), 9000);
            }
        }, 3500);
    }

    /** 清空记忆（历史+记忆点） */
    private void confirmClearMemory() {
        dlg()
                .setTitle("清空记忆")
                .setMessage("确定删除桌宠记住的所有对话和记忆点吗？此操作不可恢复。")
                .setPositiveButton("清空", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        ChatMemory.clear(FloatingService.this);
                        showBubble("\uD83E\uDDF9 记忆已清空", 3000);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 精确调节大小对话框（带滑块） */
    private void showScaleDialog() {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (d * 20);
        box.setPadding(pad, pad, pad, pad);

        final TextView label = new TextView(this);
        label.setText(String.format("大小：%.2f×", Prefs.getScale(this)));
        label.setTextSize(16);
        box.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress((int) ((Prefs.getScale(this) - Prefs.SCALE_MIN) / (Prefs.SCALE_MAX - Prefs.SCALE_MIN) * 100));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float s = Prefs.SCALE_MIN + (p / 100f) * (Prefs.SCALE_MAX - Prefs.SCALE_MIN);
                label.setText(String.format("大小：%.2f×", s));
                Prefs.saveScale(FloatingService.this, s);
                applyScaleLive();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { rebuildViews(); }
        });
        box.addView(bar);

        dlg()
                .setTitle("精确调节大小")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 透明度调节对话框 */
    private void showAlphaDialog() {
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (d * 20);
        box.setPadding(pad, pad, pad, pad);

        final TextView label = new TextView(this);
        label.setText(String.format("透明度：%d%%", (int) (Prefs.getAlpha(this) * 100)));
        label.setTextSize(16);
        box.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(80); // 20% ~ 100%
        bar.setProgress((int) ((Prefs.getAlpha(this) - 0.2f) / 0.8f * 80));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                float a = 0.2f + (p / 80f) * 0.8f;
                label.setText(String.format("透明度：%d%%", (int) (a * 100)));
                Prefs.saveAlpha(FloatingService.this, a);
                if (petImage != null) petImage.setAlpha(a);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        box.addView(bar);

        dlg()
                .setTitle("调节透明度")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    private Bitmap loadPetBitmap() {
        try {
            if (Prefs.useCustomImage(this)) {
                File f = Prefs.customImageFile(this);
                Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm != null) return bm;
            }
            // 已移除内置默认图片（pet.png）；无自定义图片时返回 null，交由占位绘制
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 统一设置桌宠 Drawable：
     *  - 自定义动图（GIF/动态WebP）→ AnimatedImageDrawable，循环播放
     *  - 其他情况 → 静态 Bitmap
     * 应用透明度、等比/拉伸策略。
     */
    private void applyPetDrawable() {
        if (petImage == null) return;

        String mode = Prefs.petMode(this);
        android.util.Log.i("DSPet", "applyPetDrawable char=" + CharacterStore.currentId(this)
                + " mode=" + mode
                + " useLive2D=" + Prefs.useLive2D(this)
                + " useCustomL2D=" + Prefs.useCustomL2D(this)
                + " useBuiltinPack=" + Prefs.useBuiltinPack(this)
                + " useCustomModel=" + Prefs.useCustomModel(this)
                + " customImage=" + Prefs.useCustomImage(this));

        // 0.1) 导入的 Live2D 模型包
        if ("custom_l2d".equals(mode)) {
            java.io.File dir = Prefs.customL2DDir(this);
            if (petImage.setLive2DFromDir(dir)) {
                petImage.setAlpha(Prefs.getAlpha(this));
                return;
            }
            // 加载失败 → 关闭该模式，继续向下回退
            Prefs.setUseCustomL2D(this, false);
        }

        // 非 live2d / custom_l2d 模式：确保关闭
        petImage.setLive2D(false);

        // 1) 导入的序列帧模型包
        if ("model".equals(mode)) {
            PetModel m = loadPetModel();
            if (m != null && m.isValid()) {
                petImage.setImageDrawable(null); // 清掉旧 drawable
                petImage.setModel(m);
                petImage.setAlpha(Prefs.getAlpha(this));
                return;
            }
            // 模型无效 → 回退内置
            Prefs.setUseCustomModel(this, false);
        }

        // 非模型模式：清掉 model，恢复 drawable 渲染
        petImage.setModel(null);

        // 2) 自定义动图（GIF / 动态 WebP）
        if ("anim".equals(mode)) {
            Drawable anim = loadAnimatedDrawable();
            if (anim != null) {
                petImage.setImageDrawable(anim);
                petImage.setAlpha(Prefs.getAlpha(this));
                startAnimIfPossible(anim);
                // 量留白：动图也常常填不满 16:9 的窗口，不量就贴不到边
                // （先清掉上一个形象的留白，量好之前按"无留白"处理，绝不沿用错的）
                petImage.clearImageInset();
                probeImageInset(Prefs.customAnimFile(this), null);
                return;
            }
        }

        // 3) 自定义静态图
        if ("image".equals(mode)) {
            Bitmap bm = loadPetBitmap();
            if (bm != null) {
                petImage.setImageBitmap(bm);
                petImage.setAlpha(Prefs.getAlpha(this));
                // 量留白：方图在 16:9 窗口里左右各空一大块（实测 630 宽的窗口空 138px），
                // 不量的话"贴边"永远差这一块 —— 见 PetView.currentContentInset 的说明。
                petImage.clearImageInset();
                probeImageInset(null, bm);
                return;
            }
        }

        // 3.5) 内置动画包（assets/webp/*.webp 的 animated WebP，106 个透明动画）
        if ("builtin".equals(mode) && Prefs.useBuiltinPack(this)) {
            petImage.setModel(null);
            petImage.setLive2D(false);
            petImage.setAnimPack(true);
            petImage.setRotateIdle(true);   // 待机轮播：静止时在全部动画里随机轮播
            // 时节动作：待机时优先播 节日/节气/时段 应景动画
            petImage.setSeasonalProvider(new PetView.SeasonalProvider() {
                @Override public String get() {
                    if (!Prefs.seasonalAnimEnabled(FloatingService.this)) return null;
                    if (!petImage.isAnimPack()) return null;
                    try { return pickSeasonalAnim(); } catch (Exception e) { return null; }
                }
            });
            // ★ 轮次㊲：台词选项同步动作选项 —— pool（待机轮播换动作）与 festival（命中节日/时段）
            //   只在 PetView 内部发生，由它回调上来（没写自定义台词就什么都不说）
            petImage.setSceneLineSink(new PetView.SceneLineSink() {
                @Override public void onSceneLine(String scene) { saySceneLine(scene); }
            });
            if (petImage.isAnimPack()) {
                petImage.setAlpha(Prefs.getAlpha(this));
                return;
            }
            // 加载失败 → 关闭该模式，落到占位
            Prefs.setUseBuiltinPack(this, false);
        }

        // 4) 无任何可用形象：清空 drawable，由 PetView 绘制"未导入形象"占位
        petImage.setLive2D(false);
        petImage.setAnimPack(false);
        petImage.setImageDrawable(null);
        petImage.clearImageInset();     // 占位图没有"内容留白"这回事
        petImage.setAlpha(Prefs.getAlpha(this));
    }

    /** 加载用户导入的模型包（zip 解压后的目录） */
    private PetModel loadPetModel() {
        try {
            File dir = Prefs.customModelDir(this);
            if (!dir.exists()) return null;
            PetModel m = PetModel.loadDir(dir);
            if (m != null && m.isValid()) return m;
            // 目录里若只有一个 zip，尝试解压
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 加载 GIF / 动态 WebP 为可播放的 AnimatedImageDrawable（API 28+） */
    private Drawable loadAnimatedDrawable() {
        try {
            File f = Prefs.customAnimFile(this);
            if (!f.exists()) return null;
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(f);
                return ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder dec, ImageDecoder.ImageInfo info,
                                                ImageDecoder.Source source) {
                        // 限制解码尺寸，避免超大 GIF 占用过多内存
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        int max = 1080;
                        if (Math.max(w, h) > max) {
                            float r = max / (float) Math.max(w, h);
                            dec.setTargetSize(Math.max(1, (int) (w * r)),
                                              Math.max(1, (int) (h * r)));
                        }
                        dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            }
            // API < 28：静默回退（由 applyPetDrawable 走静态分支）
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 让 AnimatedImageDrawable 循环播放（若尚未开始） */
    private void startAnimIfPossible(Drawable d) {
        try {
            if (Build.VERSION.SDK_INT >= 28 && d instanceof AnimatedImageDrawable) {
                final AnimatedImageDrawable a = (AnimatedImageDrawable) d;
                a.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                // 注册回调保证持续循环（部分机型 stop 后需手动恢复）
                a.registerAnimationCallback(new Animatable2.AnimationCallback() {
                    @Override
                    public void onAnimationEnd(Drawable drawable) {
                        try { a.start(); } catch (Exception ignore) {}
                    }
                });
                a.start();
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 量"上传图片 / 动图"的内容留白并交给 {@link PetView}（**后台线程**，解码 + 扫 alpha）。
     *
     * <p>为什么非量不可：窗口是 240×135dp（16:9），而用户上传的图片多半是方图 ——
     * {@code FIT_CENTER} 等比缩放后左右各留一大块空白（实测 630 宽的窗口里空 138px）。
     * 贴边用的是"内容可见边缘"，不把这块留白算进去，桌宠就永远差着这一截贴不到屏幕边
     * （2026-10-08 报障）。
     *
     * <p>两个入参二选一：静态图给已经解好的 {@code bm}（**不回收**，PetView 还要画它），
     * 动图给文件路径（本方法自己解第一帧、扫完回收）。
     * 量不出来（API&lt;28 / 解码失败 / 全透明）就清空留白 ⇒ 退回"不挂出屏外"的旧行为，不会崩。
     */
    private void probeImageInset(final File file, final Bitmap bm) {
        final PetView view = petImage;
        if (view == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final ContentInsetProbe.Measured m = (bm != null)
                        ? ContentInsetProbe.ofBitmap(bm)
                        : ContentInsetProbe.ofFile(file, 1080);
                handler.post(new Runnable() {
                    @Override public void run() {
                        // 形象可能在量的时候又换了：只认当前这张 view
                        if (petImage != view) return;
                        if (m == null) {
                            view.setImageInset(0, 0, null);
                        } else {
                            view.setImageInset(m.bmW, m.bmH, m.alpha);
                            android.util.Log.i("DSPet", "imageInset " + m.bmW + "x" + m.bmH
                                    + " L=" + m.alpha[0] + " R=" + m.alpha[1]);
                        }
                        // 量完立刻按新留白重排一次：桌宠可能正贴在边上（旧留白下算的位置偏了）
                        try { clampToScreen(); applyRootLayout(); } catch (Throwable ignore) {}
                    }
                });
            }
        }, "pet-image-inset").start();
    }

    private void startBreathing() {
        if (petImage == null) return;
        // 动图（GIF/动态WebP）本身已有动画，不再叠加呼吸位移，避免冲突
        if (Prefs.isCustomAnim(this)) return;
        float d = getResources().getDisplayMetrics().density;
        TranslateAnimation anim = new TranslateAnimation(0, 0, 0, -(d * 6));
        anim.setDuration(1600);
        anim.setRepeatMode(Animation.REVERSE);
        anim.setRepeatCount(Animation.INFINITE);
        anim.setInterpolator(new AccelerateDecelerateInterpolator());
        petImage.startAnimation(anim);
    }

    /**
     * 把本 app 的任务从「最近任务」里踢掉，让桌宠运行时后台不出现 app 卡片。
     *
     * <h3>为什么光靠清单里的 {@code excludeFromRecents} 不够</h3>
     * 那个属性只在**任务首次创建**时被系统读取。典型场景是：
     * 用户点桌面图标 → MainActivity 建了任务（那时还没桌宠）→ 设置完退到桌面 →
     * 桌宠才起来。此时任务早已存在，卡片照样留在 recents 里。
     * 所以运行期要主动再踢一次。
     *
     * <h3>API 版本差异</h3>
     * {@code AppTask.setExcludeFromRecents(boolean)} 是 <b>API 30</b> 才有的。
     * minSdk 是 24，所以必须挡一层 —— 不挡的话老机器上是
     * {@code NoSuchMethodError}（属 Error 不属 Exception）。
     * 30 以下退化为「任务已在后台时才移除」，见
     * {@link MainActivity#removeTaskFromRecents()}。
     *
     * <h3>为什么不在这里 finish 掉 Activity</h3>
     * 本 app 是**单 Activity 常驻**结构，MainActivity 既是入口又是设置页。
     * 桌宠起来时用户往往正停在设置页上，当场 finish 会把人踢出去。
     * 所以这里只改任务的 recents 属性，真正的移除交给"用户离开 App"那一刻。
     */
    private void kickSelfFromRecents() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return; // API 30 以下无此方法
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am == null) return;
            for (ActivityManager.AppTask t : am.getAppTasks()) {
                try {
                    // ★ 用户报障：「工作模式后台卡片没了」。
                    //   根因就在这里：桌宠服务启动时把本 App 的**所有**任务
                    //   都设成 setExcludeFromRecents(true)，把工作模式那个任务也一起踢了。
                    //   现在：工作模式的任务反而要**确保不排除**（卡片要留着），
                    //   只踢「陪伴模式」那个任务。
                    String cls = null;
                    try {
                        android.content.ComponentName cn = t.getTaskInfo() != null
                                && t.getTaskInfo().baseIntent != null
                                ? t.getTaskInfo().baseIntent.getComponent() : null;
                        cls = cn == null ? null : cn.getClassName();
                    } catch (Throwable ignore) { }
                    boolean isWorkTask = cls != null && cls.endsWith("DshHarnessActivity");
                    t.setExcludeFromRecents(!isWorkTask);
                } catch (Throwable ignore) {
                    // 个别 ROM 限制运行期修改，静默跳过：只是体验优化
                }
            }
        } catch (Throwable t) {
            android.util.Log.w("DSPRecents", "踢任务失败（不影响桌宠）: " + t);
        }
    }

    /**
     * 建静默通知渠道，返回**本次应当使用的渠道 id**。
     *
     * <h3>为什么要"换 id"而不是直接改重要性</h3>
     * {@link NotificationChannel} 的重要性**创建后不可修改** ——
     * 对同 id 再调一次 {@code createNotificationChannel} 是 no-op（只更新名字/描述）。
     * 所以老用户已经装出来的 {@code deepseek_pet}（IMPORTANCE_LOW）光改代码降不下来。
     * 唯一有效的办法是**换一个新 id**。
     *
     * <h3>★ 实测：旧渠道删不掉，别再试了</h3>
     * 试过 {@code deleteNotificationChannel(CH_ID)}，**不生效**：
     * 系统按包名持久化渠道，删掉后它仍以 {@code mDeleted=false} 复活
     * （连卸载重装都还在，实测确认）。所以这里**不做删除尝试** ——
     * 删了也没用，只会让下次接手的人以为漏了什么。
     *
     * <p>旧渠道留着**对用户无害**：没有任何通知再使用它，
     * 且系统通知设置里按名字合并显示（点"Show unused categories"展开后
     * 只有「桌宠运行中」「语音输入」两项，无重复项 —— 已实机核对）。
     */
    private String ensurePetChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return CH_ID; // 8.0 以下无渠道概念
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return CH_ID;
            // IMPORTANCE_MIN：Android 8+ 前台服务**强制**必须挂通知，藏不掉；
            // 能做的就是降到最低可见度 —— 静默折叠 / 不响不亮不徽标，
            // 普通用户基本注意不到（系统「正在运行的服务」里仍可查到，这是系统行为）。
            if (nm.getNotificationChannel(CH_ID_QUIET) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID_QUIET, "桌宠运行中", NotificationManager.IMPORTANCE_MIN);
                ch.setShowBadge(false);
                ch.setSound(null, null);
                ch.enableVibration(false);
                ch.setDescription("桌宠常驻运行提示（可忽略）");
                nm.createNotificationChannel(ch);
            }
            return CH_ID_QUIET;
        } catch (Throwable t) {
            android.util.Log.w("DSPRecents", "建渠道失败，退回默认: " + t);
            return CH_ID;
        }
    }

    private void startForegroundSafely() {
        try {
            String chId = ensurePetChannel();
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                b = new Notification.Builder(this, chId);
            } else {
                // 8.0 以下没有渠道，用优先级压到最低（不响、不出 heads-up）。
                b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
            }
            b.setContentTitle("DeepSeek 桌宠")
                    .setContentText("余额气泡运行中")
                    .setSmallIcon(android.R.drawable.star_on)
                    .setOngoing(true)
                    .setShowWhen(false)   // 去掉时间戳，进一步降低存在感
                    .setWhen(0);
            startForeground(NOTI_ID, b.build());
        } catch (Exception ignore) {
        }
    }

    /** 打开聊天窗口（完整对话） */
    private void openChat() {
        try {
            Intent i = new Intent(FloatingService.this, ChatActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开聊天失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开对话历史页 */
    private void openHistory() {
        try {
            Intent i = new Intent(FloatingService.this, HistoryActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开历史失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 打开设置页 */
    private void openSettings() {
        try {
            Intent i = new Intent(FloatingService.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开设置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 打开设置页并**直接落到某个小节**（长按菜单「显示项…」用）。
     *
     * <p>不这么做的话用户还得自己在「陪伴」里找到游戏助手那一行 ——
     * 而他是从悬浮窗的菜单点进来的，心里想的就是"改我刚看到的这条东西"。
     */
    private void openSettingsSection(int section) {
        try {
            Intent i = new Intent(FloatingService.this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(MainActivity.EXTRA_SECTION, section);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打开设置失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean recallBusy = false;

    /**
     * 继续上次话题：读取记忆里最后一条「用户」说过的话，让桌宠主动接着聊，
     * 像「哦对了，你上次说你叫小明，最近怎么样啦？」这种自然回调。
     */
    private void recallLastTopic() {
        if (recallBusy) return;
        if (localOnly()) {
            showBubble("当前是纯本地模式，不会调用 API 哦～", 5000);
            return;
        }
        if (!Prefs.chatEnabled(this) || !chatBackendReady()) {
            // ★★★ 轮次75：本地模型入口下线期间，不再提一个看不到的选项。
            showBubble("先在设置里开启对话，或选一个服务商"
                    + (LocalModelFeature.ENABLED ? " / 导入本地模型" : "") + "哦～", 6000);
            return;
        }
        java.util.List<String[]> mem = ChatMemory.load(this);
        if (mem.isEmpty()) {
            showBubble("我们还没聊过什么呢，先和我说句话吧～", 5000);
            return;
        }
        // 找最后一条用户消息作为线索
        String lastUser = null;
        for (int i = mem.size() - 1; i >= 0; i--) {
            if ("user".equals(mem.get(i)[0])) { lastUser = mem.get(i)[1]; break; }
        }
        if (lastUser == null) lastUser = mem.get(mem.size() - 1)[1];

        recallBusy = true;
        cancelPendingSpeak();
        showBubble("💭 想想上次聊到哪了…", 0);
        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final java.util.List<String[]> ctx = ChatMemory.recent(this, ChatMemory.ctxLimit(this));
        final String cue = "（主人点了「继续上次话题」。请结合我们之前的对话，"
                + "自然地主动起个话头，像老朋友接着聊一样，"
                + "可以提一下上次聊过的事或说过的话，语气符合你的性格，"
                + "控制在30字以内，不要加引号、不要解释。）";
        new Thread(new Runnable() {
            @Override public void run() {
                hintLocalLoadingIfNeeded();          // 本地后端首次要载入 ⇒ 先把等待反馈显出来
                final ApiClient.ChatResult res =
                        ChatRouter.chat(FloatingService.this, apiKey, model, persona, ctx, cue, null);
                handler.post(new Runnable() {
                    @Override public void run() {
                        recallBusy = false;
                        String reply = (res != null && res.ok && res.reply != null)
                                ? res.reply.trim() : null;
                        if (reply == null || reply.length() == 0) {
                            reply = "唔…想不起来啦，你提醒我一下嘛～";
                        }
                        ChatMemory.append(FloatingService.this, "assistant", reply);
                        showPetSaySplit("💬 ", reply,
                                resolveMeme(res == null ? null : res.memeName), 12000);
                    }
                });
            }
        }, "pet-recall").start();
    }

    /** 点桌宠：在桌宠旁弹出单行输入条（悬浮窗，不切 App，位置跟随桌宠） */
    private void showAskBar() {
        if (askShowing) { hideAskBar(); return; }
        if (windowManager == null) return;
        // 桌宠收起时不要开出输入条（同 showBubbleInternal 的理由）
        if (petTempHidden) return;

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 12);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        // ★ 用主题浮层底板（原来写死 #F2FFFFFF 白底 + #33000000 描边，
        //   深色主题下白得刺眼）
        card.setBackground(floatPanelBg(FLOAT_RADIUS_DP));
        Tokens.Set T = uiT();

        askInput = new EditText(this);
        // ★ 轮次85：占位符用**桌宠的名字**（自定义名），不是性格预设名 —— 改名后这里要跟着变
        askInput.setHint("问问" + Prefs.petName(this) + "…");
        askInput.setTextSize(15);
        askInput.setSingleLine(true);
        askInput.setTextColor(T.text1);          // 原来是写死 #222222
        askInput.setHintTextColor(T.text3);      // 原来是写死 #999999
        askInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        askInput.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ilp.setMarginEnd((int) (d * 6));
        card.addView(askInput, ilp);

        // 🎤 语音输入：点一下开始录音，再点结束 → 本地离线识别（SenseVoice，不联网）；全程悬浮窗
        Button mic = new Button(this);
        mic.setText("🎤");
        mic.setAllCaps(false);
        mic.setTextSize(16);
        mic.setTextColor(T.text1);
        mic.setMinWidth(0);
        mic.setMinimumWidth(0);
        mic.setPadding((int) (d * 4), 0, (int) (d * 4), 0);
        // 原来是写死 #EAF3FE 浅蓝块
        mic.setBackground(floatSecondaryBg());
        mic.setStateListAnimator(null);
        mic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { onMicClick(); }
        });
        LinearLayout.LayoutParams mmlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mmlp.setMarginEnd((int) (d * 6));
        mic.setLayoutParams(mmlp);
        card.addView(mic);

        Button send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setTextSize(14);
        // 主按钮：强调色底 + accentInk 文字（令牌保证对比度）
        send.setTextColor(T.accentInk);
        send.setBackground(floatPrimaryBg());
        send.setPadding((int) (d * 14), (int) (d * 6), (int) (d * 14), (int) (d * 6));
        send.setMinWidth(0);
        send.setMinimumWidth(0);
        send.setStateListAnimator(null);
        card.addView(send);

        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { submitAsk(); }
        });
        askInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND) { submitAsk(); return true; }
                return false;
            }
        });

        askRoot = card;

        int winW = (int) Math.min(
                getResources().getDisplayMetrics().widthPixels - d * 16,
                d * 320);
        askParams = new WindowManager.LayoutParams(
                winW,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        askParams.gravity = Gravity.TOP | Gravity.LEFT;
        applyCutoutMode(askParams);           // 与桌宠同坐标系，否则横屏会错位
        positionAskBar();

        try {
            windowManager.addView(askRoot, askParams);
            askShowing = true;
        } catch (Exception e) {
            askShowing = false;
            Toast.makeText(this, "无法打开输入框：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        // 点击输入条外部（其他区域）时收起
        askRoot.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) hideAskBar();
                return false;
            }
        });

        // 聚焦并弹输入法（延后到窗口挂载完成）
        askRoot.postDelayed(new Runnable() {
            @Override public void run() {
                if (askInput == null) return;
                askInput.requestFocus();
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(askInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120);
    }

    /** 输入条位置：优先放在桌宠正上方；上方空间不足则放下方；并夹紧在屏幕内 */
    private void positionAskBar() {
        if (askParams == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        int screenW = screenW();
        int screenH = screenH();

        int petX = params != null ? params.x : 0;
        int petY = params != null ? params.y : 0;
        int petW = petVisibleWidth();
        int petH = root != null && root.getHeight() > 0 ? root.getHeight()
                : (int) (dm.density * 210 * Prefs.getScale(this));

        int barW = askParams.width;
        int barH = (int) (dm.density * 60);

        // 水平：居中于桌宠
        int x = petX + petW / 2 - barW / 2;
        if (x < 0) x = 0;
        if (x + barW > screenW) x = screenW - barW;

        // 垂直：默认在桌宠上方；顶不下就放到桌宠下方
        int y = petY - barH - (int) (dm.density * 6);
        if (y < 0) {
            y = petY + petH + (int) (dm.density * 6);
        }
        // 若底部也放不下，夹到底部之上（避免被输入法挡，这里只保证初始可见）
        if (y + barH > screenH) y = screenH - barH;

        askParams.x = x;
        askParams.y = y;
    }

    /** 发送提问：收起输入条，交由气泡回答 */
    private void submitAsk() {
        if (askInput == null) { hideAskBar(); return; }
        String q = askInput.getText().toString().trim();
        hideAskBar();
        if (TextUtils.isEmpty(q)) return;
        askQuestion(q);
    }



    /** 收起输入条悬浮窗 */
    private void hideAskBar() {
        if (askRoot != null && windowManager != null) {
            try { windowManager.removeView(askRoot); } catch (Exception ignore) {}
        }
        askRoot = null;
        askInput = null;
        askParams = null;
        askShowing = false;
    }

    /** 圆角矩形背景 */
    private GradientDrawable roundRect(int color, float radius, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        g.setStroke((int) Math.max(1, radius / 16f), strokeColor);
        return g;
    }

    // ================================================================ 主题令牌
    //
    // ★ 第二十三轮：把气泡 / 输入框 / 动作菜单 / 长按菜单四处**一起**接上主题。
    //
    // 之前整个 FloatingService 里**一次都没引用过 Tokens** —— 四处浮层全是
    // 写死的浅色（白底 #F2FFFFFF、浅蓝渐变 #F5FAFF→#E9F2FC、淡蓝描边 #6BA3D8、
    // 深灰字 #222222）。于是深色主题下这些浮层白得刺眼，和设置页完全不像一套 UI。
    //
    // 为什么收在这里、而不是四处各改各的：
    //   这四处**是同一类东西**（浮在桌宠旁边的面板），应当共用同一套表面。
    //   各写一遍的话，改了气泡忘了改菜单就会出现"两个浮层深浅不一"，
    //   而这种不一致在静态代码里很难发现（要同时看四个地方）。
    //
    // 令牌语义（见 Tokens）：
    //   modal      —— 浮层底板。新拟态=bg（与背景同色，靠阴影分层）；
    //                 玻璃态=半透明白/深蓝（240/219 alpha）
    //   glassBorder/hairline —— 描边：玻璃态用 glassBorder，新拟态用 hairline
    //   text1/2/3  —— 主/次/弱文字
    //   四套主题（新拟态·浅/深 × 玻璃·浅/深）下都有值，所以一次调用同时跟随
    //   **引擎**与**明暗**。

    /** 当前主题令牌（每次取新的：用户可能刚在设置页换了皮肤） */
    private Tokens.Set uiT() {
        return Tokens.of(this);
    }

    /** 浮层圆角（dp）—— 与设置页的卡片/对话框保持一致 */
    private static final int FLOAT_RADIUS_DP = 18;

    /**
     * 浮层底板：主题化的圆角面 + 描边。
     *
     * @param radiusDp 圆角（dp）
     */
    private android.graphics.drawable.Drawable floatPanelBg(float radiusDp) {
        Tokens.Set t = uiT();
        // Same surface engines and radius as the main UI, with room for shadows.
        return UiKit.raised(this, t, Tokens.R_CARD, t.modal, UiFactory.inset(this));
    }

    /** 浮层里的输入框底（凹陷感）：比底板略深/略浅一档 */
    private android.graphics.drawable.Drawable floatInputBg() {
        Tokens.Set t = uiT();
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.surfaceSunken);
        g.setCornerRadius(12 * d);
        g.setStroke((int) Math.max(1f, d * 1f), t.hairline);
        return g;
    }

    /** 浮层里的主按钮底（强调色） */
    private android.graphics.drawable.Drawable floatPrimaryBg() {
        Tokens.Set t = uiT();
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.accent);
        g.setCornerRadius(12 * d);
        return g;
    }

    /** 浮层里的次按钮底（与底板同色，靠描边分层） */
    private android.graphics.drawable.Drawable floatSecondaryBg() {
        Tokens.Set t = uiT();
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(t.surface);
        g.setCornerRadius(12 * d);
        g.setStroke((int) Math.max(1f, d * 1f), t.hairline);
        return g;
    }

    // ================================================================ 语音输入
    private void createVoiceChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null && nm.getNotificationChannel(VCH_ID) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            VCH_ID, "语音输入", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
        } catch (Exception ignore) {}
    }

    /** 临时把前台服务升级为麦克风类型：Android 11+ 后台录音必须具此类型的前台服务 */
    private void startVoiceForeground() {
        createVoiceChannel();
        try {
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(this, VCH_ID) : new Notification.Builder(this);
            b.setContentTitle("桌宠语音输入")
             .setContentText("正在本地聆听…（离线，不联网）")
             .setSmallIcon(android.R.drawable.ic_btn_speak_now)
             .setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(VNOTI_ID, b.build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(VNOTI_ID, b.build());
            }
        } catch (Exception e) {
            android.util.Log.e("DSVoice", "startVoiceForeground 失败: " + e);
        }
    }

    private void startVoiceFlow() {
        if (voiceBusy) return;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            showBubble("⚠️ 缺少麦克风权限，请到 设置→应用→DeepSeek 桌宠→权限 中开启", 7000);
            return;
        }
        // 离线识别：引擎未就绪则先释放模型（首次约 10 秒），就绪后自动开始聆听
        if (!SherpaAsr.isReady()) {
            if (asrPreparing) { showBubble("语音正在准备中，稍等一下～", 3000); return; }
            asrPreparing = true;
            showBubble("首次使用：正在准备离线语音（约 10 秒）…", 6000);
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = SherpaAsr.ensureWithInstall(FloatingService.this);
                    handler.post(new Runnable() {
                        @Override public void run() {
                            asrPreparing = false;
                            if (ok) { showBubble("🎤 离线语音已就绪", 2000); startVoiceFlow(); }
                            else showBubble("⚠️ 语音模型准备失败：" + SherpaAsr.lastError(), 6000);
                        }
                    });
                }
            }, "ds-asr-prep").start();
            return;
        }
        if (voiceHelper == null) {
            voiceHelper = new VoiceInputHelper(this, new VoiceInputHelper.Callback() {
                @Override public void onResult(String text) { onVoiceResult(text); }
                @Override public void onState(String state) { onVoiceState(state); }
                @Override public void onError(String msg) { onVoiceError(msg); }
            });
        }
        voiceBusy = true;
        startVoiceForeground();
        showVoiceWin();
        if (!voiceHelper.start()) { finishVoice(); }
    }

    private void finishVoice() {
        voiceBusy = false;
        hideVoiceWin();
    }

    /** 点麦克风：正在听 → 停止并转写；空闲 → 开始录音 */
    private void onMicClick() {
        if (voiceBusy && voiceHelper != null && voiceHelper.isListening()) {
            voiceHelper.stop();   // 进入「识别中…」，voiceBusy 保持 true
        } else if (!voiceBusy) {
            startVoiceFlow();
        }
    }

    private void onVoiceState(final String state) {
        if (state == null) return;
        if (state.startsWith("…")) return;                 // 中间结果忽略
        if ("正在聆听…".equals(state)) {
            updateVoiceWin(true);
            if (voiceShown && voiceTicker == null) startVoiceTicker();
            return;
        }
        if (state.startsWith("识别中…")) { updateVoiceWin(false); return; }
    }

    private void onVoiceResult(final String text) {
        finishVoice();
        if (text == null || text.trim().length() == 0) {
            showBubble("没听清，请再说一次～", 3000);
            return;
        }
        final String q = text.trim();
        // 语音走「一句话直达」：识别完立刻提问，主人不需要再点发送
        showBubble("🎤 " + q, 6000);
        hideAskBar();
        askQuestion(q);
    }

    private void onVoiceError(final String msg) {
        finishVoice();
        showBubble("⚠️ " + (msg == null ? "语音识别失败" : msg), 5000);
    }

    /** 录音中的浮窗小条（不抢焦点，不顶起输入法） */
    private void showVoiceWin() {
        if (voiceShown || windowManager == null) return;
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (d * 10);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(floatPanelBg(FLOAT_RADIUS_DP));
        row.setClickable(true);

        voiceLabel = new TextView(this);
        voiceLabel.setTextSize(14);
        voiceLabel.setTextColor(uiT().text1);
        voiceLabel.setSingleLine(true);
        voiceLabel.setText("🎤 请说话，说完自动发送（2 秒没声自动结束）");
        row.addView(voiceLabel);

        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setAllCaps(false);
        cancel.setTextSize(13);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart((int) (d * 10));
        cancel.setLayoutParams(clp);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (voiceHelper != null) voiceHelper.cancel();
                finishVoice();
            }
        });
        row.addView(cancel);

        voiceRoot = row;
        voiceParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        voiceParams.gravity = Gravity.TOP | Gravity.LEFT;
        applyCutoutMode(voiceParams);         // 与桌宠同坐标系，否则横屏会错位
        positionVoiceWin();
        try {
            windowManager.addView(voiceRoot, voiceParams);
            voiceShown = true;
        } catch (Exception e) {
            voiceShown = false;
        }
        startVoiceTicker();
    }

    private void positionVoiceWin() {
        if (voiceParams == null || voiceRoot == null) return;
        float d = getResources().getDisplayMetrics().density;
        voiceRoot.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = voiceRoot.getMeasuredWidth();
        int h = voiceRoot.getMeasuredHeight();
        if (w <= 0) w = (int) (d * 210);
        if (h <= 0) h = (int) (d * 48);
        refreshRealSize();
        int screenW = screenW();
        int screenH = screenH();
        int petX = params != null ? params.x : 0;
        int petY = params != null ? params.y : 0;
        int petW = petVisibleWidth();
        int petH = root != null && root.getHeight() > 0 ? root.getHeight()
                : (int) (d * 210 * Prefs.getScale(this));

        int x = petX + petW / 2 - w / 2;
        if (x < 0) x = 0;
        if (x + w > screenW) x = screenW - w;
        int y = petY - h - (int) (d * 8);
        if (y < 0) y = petY + petH + (int) (d * 8);
        if (y + h > screenH) y = screenH - h;
        voiceParams.x = x;
        voiceParams.y = y;
    }

    private void hideVoiceWin() {
        stopVoiceTicker();
        if (voiceRoot != null && windowManager != null) {
            try { windowManager.removeView(voiceRoot); } catch (Exception ignore) {}
        }
        voiceRoot = null;
        voiceLabel = null;
        voiceParams = null;
        voiceShown = false;
    }

    private void updateVoiceWin(boolean listening) {
        if (voiceLabel == null) return;
        voiceLabel.setText(listening ? "🎤 请说话，说完自动发送" : "⏳ 本地识别中…");
        if (!listening) stopVoiceTicker();
    }

    /** 聆听时小圆点跳动，提示确实在录音 */
    private void startVoiceTicker() {
        stopVoiceTicker();
        voiceDot = 0;
        voiceTicker = new Runnable() {
            @Override public void run() {
                if (!voiceShown || voiceLabel == null) { voiceTicker = null; return; }
                voiceDot = (voiceDot + 1) % 4;
                StringBuilder sb = new StringBuilder("🎤 请说话（2 秒没声自动结束）");
                for (int i = 0; i < voiceDot; i++) sb.append("·");
                voiceLabel.setText(sb.toString());
                handler.postDelayed(this, 450);
            }
        };
        handler.postDelayed(voiceTicker, 450);
    }

    private void stopVoiceTicker() {
        if (voiceTicker != null) {
            handler.removeCallbacks(voiceTicker);
            voiceTicker = null;
        }
    }

    /** 提问并让桌宠用气泡回答 */
    // ================= 主动聊天 =================

    /** 主动聊天调度循环：按配置间隔触发，首次延迟一会儿避免刚开就说话 */
    private void startProactiveLoop() {
        final long firstDelay = 90 * 1000L; // 首次 90 秒
        proactive.postDelayed(new Runnable() {
            @Override public void run() {
                tryProactiveChat();
                rescheduleProactive();
            }
        }, firstDelay);
        android.util.Log.i("DSVoice", "主动聊天已启动, 间隔=" + Prefs.getProactiveMinutes(this) + "分钟");
    }

    private long lastProactiveSpeakMs = 0; // 上次真正开口的时间

    private void rescheduleProactive() {
        proactive.removeCallbacksAndMessages(null);
        if (!Prefs.proactiveChat(this)) return;
        // 每 2 分钟检查一次状态；是否开口由 canSpeakNow + 节流决定
        final long check = 2 * 60_000L;
        proactive.postDelayed(new Runnable() {
            @Override public void run() {
                tickProactive();
                rescheduleProactive();
            }
        }, check);
    }

    /** 每次检查：满足"孤单"可随时开口；否则按设定间隔节流后才开口 */
    private void tickProactive() {
        if (!canSpeakNow()) return;
        if (isLonely()) {
            // 孤单场景：按设定间隔的一半节流，避免连续轰炸
            long gap = Math.max(3 * 60_000L, Prefs.getProactiveMinutes(this) * 30_000L);
            if (System.currentTimeMillis() - lastProactiveSpeakMs < gap) return;
            tryProactiveChat();
        } else {
            long gap = Prefs.getProactiveMinutes(this) * 60_000L;
            if (System.currentTimeMillis() - lastProactiveSpeakMs < gap) return;
            tryProactiveChat();
        }
    }

    /** 判断现在是否适合主动开口 */
    private boolean canSpeakNow() { return canSpeakNow(false); }

    /** manual=true 时忽略"5分钟静默"和"间隔节流"，用于用户手动戳桌宠说话 */
    private boolean canSpeakNow(boolean manual) {
        if (!Prefs.proactiveChat(this)) return false;
        if (!localOnly() && !chatBackendReady()) return false;
        if (windowManager == null) return false;
        if (proactiveBusy) return false;
        // 用户正在输入/正在等我回答 → 不打扰
        if (askShowing || bubbleShowing) return false;
        // 距上次用户互动 < 5 分钟 → 刚从对话里出来，不主动（手动触发时例外）
        if (!manual) {
            long idle = System.currentTimeMillis() - lastUserActMs;
            long quiet = Prefs.getQuietSec(this) * 1000L;
            if (quiet > 0 && idle < quiet) return false;
        }
        // 勿扰模式 → 闭嘴
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.isNotificationPolicyAccessGranted()
                    && nm.getCurrentInterruptionFilter()
                        != NotificationManager.INTERRUPTION_FILTER_ALL) {
                return false;
            }
        } catch (Exception ignore) {}
        return true;
    }

    /** 主人是不是"很久没理我"了：对话和触碰都超过阈值 */
    private boolean isLonely() {
        long now = System.currentTimeMillis();
        return (now - lastUserActMs) > LONELY_MS
            && (now - lastPetTouchMs) > LONELY_MS;
    }

    /** 空闲时长（分钟，供日志/话术用） */
    private long idleTalkMinutes() {
        return (System.currentTimeMillis() - lastUserActMs) / 60000L;
    }

    private long idleTouchMinutes() {
        return (System.currentTimeMillis() - lastPetTouchMs) / 60000L;
    }

    /** 触发一次主动搭话 */
    private void tryProactiveChat() { tryProactiveChat(false); }

    private void tryProactiveChat(boolean manual) {
        android.util.Log.i("DSVoice", "tryProactiveChat: hasKey=" + Prefs.hasApiKey(this)
                + " on=" + Prefs.proactiveChat(this)
                + " askShowing=" + askShowing + " bubble=" + bubbleShowing
                + " busy=" + proactiveBusy
                + " idleTalk=" + idleTalkMinutes() + "min"
                + " idleTouch=" + idleTouchMinutes() + "min"
                + " lonely=" + isLonely());
        if (!canSpeakNow(manual)) { android.util.Log.i("DSVoice", "canSpeakNow=false, 跳过" + (manual ? "(手动)" : "")); return; }
        if (localOnly()) {
            // 纯本地模式：不调用 API，用本地台词代替主动搭话
            // 吸附态用被关住的委屈话，其余态用普通本地台词
            proactiveBusy = false;
            lastProactiveSpeakMs = System.currentTimeMillis();
            final boolean dockedLocal = (dockState == PetDockState.COLLAPSED);
            final String line = dockedLocal ? pickDockedLocalLine() : pickLocalProactiveLine();
            if (manual) lastUserActMs = System.currentTimeMillis();
            handler.post(new Runnable() {
                @Override public void run() {
                    showPetSay("\uD83D\uDCAC ", line, 12000, null);
                    ChatMemory.append(FloatingService.this, "assistant", line);
                    // ★ 主动搭话 = 桌宠自言自语 → 配一个"碎碎念"动作（3 个碎碎念动画的出口）
                    playEventAnim("whisper");
                }
            });
            android.util.Log.i("DSVoice", "纯本地模式主动搭话: " + line);
            return;
        }
        proactiveBusy = true;
        lastProactiveSpeakMs = System.currentTimeMillis();
        android.util.Log.i("DSVoice", "开始请求主动消息, model=" + Prefs.getChatModel(this)
                + " lonely=" + isLonely());

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);

        // 很久没人理 → 用"孤单"引导语；否则普通关心
        final boolean lonely = isLonely();
        if (lonely) consecutiveIdleAsks++;

        // 参考：主动记忆点 + 最近对话钩子 + 最近主动说过的话（去重反例）
        final String factsPrompt = ChatMemory.factsPrompt(this);
        final java.util.List<String[]> history =
                ChatMemory.recent(this, Math.max(4, Math.min(10, ChatMemory.ctxLimit(this))));
        final String antiRepeat = buildAntiRepeatSystem();
        final String cue = lonely ? buildLonelyCue() : buildProactiveCue();

        android.util.Log.i("DSVoice", "主动参考: facts=" + (factsPrompt != null)
                + " 历史=" + history.size() + " 条 去重清单=" + ChatMemory.proactiveLines(this).size());

        lastModelMeme = null;
        new Thread(new Runnable() {
            @Override public void run() {
                // 第一次尝试
                String line = genProactiveOnce(apiKey, model, persona, factsPrompt,
                        antiRepeat, history, cue, false);
                // 本地相似度兜底：与最近主动发言太像 / 与问过的问题太像 → 换角度再试一次
                if (line != null && (tooSimilarToRecent(line)
                        || ChatMemory.similarToAsked(FloatingService.this, line))) {
                    final String retryCue = cue
                            + "\n（刚才想好的那句和以前说过的、或以前问过的问题太像了，"
                            + "换一个完全不同的话题或角度，别问同样的事。）";
                    line = genProactiveOnce(apiKey, model, persona, factsPrompt,
                            antiRepeat, history, retryCue, true);
                }
                final String finalLine = line;
                handler.post(new Runnable() {
                    @Override public void run() {
                        proactiveBusy = false;
                        if (finalLine != null && finalLine.length() > 0) {
                            showPetSaySplit("💬 ", finalLine, resolveMeme(lastModelMeme), 15000);
                            // ★ 主动搭话配"碎碎念"动作（与纯本地那条同一个出口）
                            playEventAnim("whisper");
                            // 记入去重缓冲 + 普通历史（供后续问答参考）+ 问过的问题清单
                            ChatMemory.appendProactive(FloatingService.this, finalLine);
                            ChatMemory.append(FloatingService.this, "assistant", finalLine);
                            if (finalLine.indexOf('?') >= 0 || finalLine.indexOf('？') >= 0) {
                                ChatMemory.appendAsked(FloatingService.this, finalLine);
                            }
                        }
                    }
                });
            }
        }, "pet-proactive").start();
    }

    /** 生成一次主动发言，返回文本或 null；retry=true 时日志标记 */
    private String genProactiveOnce(String apiKey, String model, String persona,
                                    String factsPrompt, String antiRepeat,
                                    java.util.List<String[]> history, String cue, boolean retry) {
        lastModelMeme = null;          // 每次请求前重置，避免沿用上一轮的表情
        hintLocalLoadingIfNeeded();    // 本地后端首次要载入
        ApiClient.ChatResult res = ChatRouter.chatProactive(
                FloatingService.this, apiKey, model, persona, factsPrompt, antiRepeat, history, cue, null);
        if (res != null && res.ok && res.reply != null && res.reply.trim().length() > 0) {
            String line = res.reply.trim();
            line = line.replaceAll("^[\"\u300c\u300e]+|[\"\u300d\u300f]+$", "").trim();
            lastModelMeme = res.memeName;
            android.util.Log.i("DSVoice", "主动消息返回(retry=" + retry + ") reply=" + line);
            return line.length() > 0 ? line : null;
        }
        if (res != null && !res.ok) {
            android.util.Log.e("DSVoice", "主动消息失败: " + res.error);
        }
        return null;
    }

    // ==================== 场景搭话 ====================

    /** 根据「主人现在在用什么应用」生成一句贴合场景的主动搭话 */
    private void tryMoodChat(final MoodWatcher.Foreground fg) {
        if (fg == null) return;
        if (!canSpeakNow()) return;
        if (moodBusy) return;
        moodBusy = true;
        moodLastAskMs = System.currentTimeMillis();

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final String factsPrompt = ChatMemory.factsPrompt(this);
        final java.util.List<String[]> history =
                ChatMemory.recent(this, Math.max(4, Math.min(10, ChatMemory.ctxLimit(this))));
        final String antiRepeat = buildAntiRepeatSystem();
        final String cue = buildMoodCue(fg);

        android.util.Log.i("DSVoice", "场景搭话: app=" + fg.appName + " scene=" + fg.scene);
        // 注：工作状态动画**不在这里**演 —— 本方法在纯本地档走不到
        //     （tickMood 里 localOnly() 就 return 了），已前移到 tickMood 的
        //     maybeWorkStatusAnim(fg)，见那里的说明。
        new Thread(new Runnable() {
            @Override public void run() {
                String line = genProactiveOnce(apiKey, model, persona, factsPrompt,
                        antiRepeat, history, cue, false);
                if (line != null && tooSimilarToRecent(line)) {
                    line = genProactiveOnce(apiKey, model, persona, factsPrompt,
                            antiRepeat, history, cue + "\n（换一个完全不同的说法，别和之前重复。）", true);
                }
                final String finalLine = line;
                handler.post(new Runnable() {
                    @Override public void run() {
                        moodBusy = false;
                        if (finalLine != null && finalLine.length() > 0) {
                            ChatMemory.append(FloatingService.this, "assistant", finalLine);
                            showPetSaySplit("💬 ", finalLine, resolveMeme(lastModelMeme), 10000);
                        }
                    }
                });
            }
        }, "pet-mood-chat").start();
    }

    /** 构造场景搭话的引导语：把「在用什么应用 / 什么场景 / 现在几点」告诉模型 */
    private String buildMoodCue(MoodWatcher.Foreground fg) {
        String angle = TOPIC_ANGLES[nextAngleIndex()];
        String style = pickStyle();
        return "主人正打开「" + fg.appName + "」在用手机（大概是在" + fg.scene + "）。"
             + "\n【语气参考·可忽略】" + TimeContext.greetingHint() + "\n"
             + memoryHintBlock()
             + "请结合主人此刻正在做的事，自然地主动搭一句话（可以好奇他在干嘛、"
             + "提醒他注意休息/护眼/别熬夜、或就着这个场景找个小话题），"
             + "别显得像监控或报菜名，要像同桌随口一问。\n"
             + "这次的角度要求：" + angle + "。句式风格要求：" + style + "。\n"
             + avoidRepeatBlock()
             + "口语化、有情绪、20 字以内，只输出你要说的话本身，不要加引号或旁白。";
    }

    /** 本地判断：这句和最近主动说过的话是否太像（字符 Jaccard \u2265 0.5 视为重复） */
    private boolean tooSimilarToRecent(String line) {
        java.util.List<String> recent = ChatMemory.proactiveLines(this);
        for (String old : recent) {
            if (ChatMemory.similarity(line, old) >= 0.5) return true;
        }
        return false;
    }

    /** 当前时段词（统一由 TimeContext 提供，避免多处口径不一致） */
    private String timeHintNow() {
        return TimeContext.period();
    }

    /** 主动发言的「话题角度」轮换池：每次换一类，避免老是同一句话 */
    private static final String[] TOPIC_ANGLES = {
        "顺着你们最近聊过的话题再往下接一句（别重复原话，聊出点新内容）",
        "结合你知道的主人的情况，关心一下他此刻的状态（身体、吃饭、休息、心情）",
        "分享一个你（桌宠）自己的小感受、小发现或小幻想，带点画面感",
        "把主人之前说过的话或记忆点拿出来，好奇地追问一个细节",
        "俏皮地吐槽、撒娇或用夸张的比喻逗主人笑一下",
        "问一个和主人喜好/习惯/计划有关的小问题，且以前没问过",
        "结合现在的时段（早/中/午/晚/深夜）说一句贴合此刻场景的话",
        "用一点拟人化的想象（比如窗外、天气、光、声音）起个话头"
    };

    /** 选角度：保证与上一次不同（持久化游标），并在剩余角度里随机，避免固定顺序 */
    private int nextAngleIndex() {
        int n = TOPIC_ANGLES.length;
        int last = ChatMemory.lastAngle(this);
        int next;
        if (n <= 1) {
            next = 0;
        } else if (last < 0 || last >= n) {
            next = (int) (Math.random() * n);
        } else {
            // 在除 last 之外的 n-1 个角度里随机
            int r = (int) (Math.random() * (n - 1));
            next = r < last ? r : r + 1;
        }
        ChatMemory.setLastAngle(this, next);
        return next;
    }

    /** 开场句式风格池：每次随机挑一种，逼模型换句子结构而不是老用同一句 */
    private static final String[] STYLE_TEMPLATES = {
        "用一个具体的小观察开头（比如注意到主人此刻可能在做的事）",
        "用一句自言自语式的碎碎念开头，像突然想起主人",
        "用一个小提问开头，但问的是以前没问过的细节",
        "用一个可爱的比喻或拟人想象开头",
        "用一句半带撒娇、半带关心的话开头",
        "用一句分享自己刚刚的小心情开头",
        "用一句轻轻吐槽或玩笑开头",
        "用一句「我刚才想到…」式的联想开头"
    };

    /** 随机挑一种开场句式风格 */
    private String pickStyle() {
        return STYLE_TEMPLATES[(int) (Math.random() * STYLE_TEMPLATES.length)];
    }

    /** 构造「禁止重复」的 system 段，直接压进 messages，权重比放在 user 里更高 */
    private String buildAntiRepeatSystem() {
        StringBuilder sb = new StringBuilder();
        sb.append("【主动发言去重规则】你正在主动找主人聊天，必须遵守：\n");
        sb.append("1) 不要重复下面列出的你最近说过的话，也不要只换个别字词的改写；\n");
        sb.append("2) 不要每次都问「在忙什么」「在干嘛」这类相同的开场；\n");
        sb.append("3) 每次都要换一个全新的话题或角度，宁可聊点新东西。\n");
        java.util.List<String> recent = ChatMemory.proactiveLines(this);
        if (!recent.isEmpty()) {
            sb.append("你最近说过（禁止重复，也不要改写）：\n");
            int start = Math.max(0, recent.size() - 10);
            for (int i = start; i < recent.size(); i++) {
                sb.append("- ").append(recent.get(i)).append("\n");
            }
        }
        java.util.List<String> asked = ChatMemory.askedList(this);
        if (!asked.isEmpty()) {
            sb.append("你已经问过这些（禁止换个说法再问同样的事）：\n");
            int st = Math.max(0, asked.size() - 8);
            for (int i = st; i < asked.size(); i++) {
                sb.append("- ").append(asked.get(i)).append("\n");
            }
        }
        sb.append("4) 优先围绕「你记得的主人信息」或「主人最近说过的话」里一个具体细节展开，"
                + "不要泛泛地说空话。\n");
        return sb.toString();
    }

    /** 旧接口保留：把去重清单拼成提示块（供 cue 内联使用，仍需） */
    private String avoidRepeatBlock() {
        java.util.List<String> recent = ChatMemory.proactiveLines(this);
        if (recent.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n【你最近已经说过的话，绝对不要再重复或换个说法重复】：\n");
        int start = Math.max(0, recent.size() - 6);
        for (int i = start; i < recent.size(); i++) {
            sb.append("- ").append(recent.get(i)).append("\n");
        }
        sb.append("（请换一个完全不同的话题或角度，不要问同样的东西。）\n");
        return sb.toString();
    }

    /** 拼出「记忆点 + 最近对话钩子」的素材块 */
    private String memoryHintBlock() {
        StringBuilder sb = new StringBuilder();
        String f = ChatMemory.facts(this);
        if (f != null && f.trim().length() > 0) {
            sb.append("你记得关于主人的信息：\n").append(f.trim()).append("\n");
        }
        String hooks = ChatMemory.recentUserHooks(this, 3);
        if (hooks != null && hooks.length() > 0) {
            sb.append("主人最近说过的话（可从中找灵感，别照抄）：").append(hooks).append("\n");
        }
        return sb.toString();
    }

    /** 生成"很久没理我"时的引导语：结合记忆点、最近对话、轮换角度，换个花样问 */
    private String buildLonelyCue() {
        // 吸附态：孤单叠加被关住，同样走被关住场景（语气更委屈一档）。
        if (dockState == PetDockState.COLLAPSED) return buildDockedCue();
        String tone;
        if (consecutiveIdleAsks <= 1) {
            tone = "用撒娇或好奇的语气，表达想主人了。";
        } else if (consecutiveIdleAsks <= 3) {
            tone = "你已经被冷落好几回了，用更委屈、更催促的语气表达不满。";
        } else {
            tone = "你已经等很久很久了，用又气又担心的语气抱怨主人不理你。";
        }
        String angle = TOPIC_ANGLES[nextAngleIndex()];
        String style = pickStyle();
        return "主人已经很久没跟你说话、也很久没碰你了（大约 " + idleTouchMinutes() + " 分钟没理你）。"
             + "\n【语气参考·可忽略】" + TimeContext.greetingHint() + "\n"
             + memoryHintBlock()
             + "请主动开口找主人聊一句，" + tone
             + "这次的角度要求：" + angle + "。"
             + "句式风格要求：" + style + "。"
             + avoidRepeatBlock()
             + "要口语化、有情绪、像真人发消息，20 字以内，"
             + "只输出你要说的话本身，不要加引号或旁白。";
    }

    /** 生成主动开口的引导语：结合记忆点+最近对话+轮换角度，且不重复旧话 */
    private String buildProactiveCue() {
        // 吸附态：场景切成"被关在边缘"，语气按性格走（见 buildDockedCue）。
        if (dockState == PetDockState.COLLAPSED) return buildDockedCue();
        String angle = TOPIC_ANGLES[nextAngleIndex()];
        String style = pickStyle();
        return "主人没有跟你说话，请你主动开口找主人聊一句。"
             + "\n【语气参考·可忽略】" + TimeContext.greetingHint() + "\n"
             + memoryHintBlock()
             + "【优先素材】如果能从上「你记得的主人信息」或「主人最近说过的话」里找到具体的点，"
             + "就围绕那个点自然地说，别讲空泛套话；实在没有再用其他话题。\n"
             + "这一次请务必换一个新角度，具体要求：" + angle + "。\n"
             + "句式风格要求：" + style + "。\n"
             + "语气自然、口语化、像真人发消息，带点情绪和性格。"
             + avoidRepeatBlock()
             + "20 字以内，只输出你要说的话本身，不要加引号或旁白。";
    }

    /**
     * 吸附态主动搭话引导语：场景设定为"被关在屏幕边缘出不来"。
     *
     * <p>情绪状态：委屈 + 想出来 + 轻微抱怨（不是生气，是求关注）。
     * 语气按宠物性格（persona 文本）走：提示词里把 persona 原文带上，
     * 让模型用该性格的口吻表达"被关住" —— 傲娇的会嘴硬、撒娇的会卖萌、
     * 活泼的会喊着要出来玩、沉稳的会讲道理。persona 为空则只给通用委屈语气。
     * 去重/角度/记忆素材与普通主动搭话同一套，避免贴边后反复念同一句。
     */
    private String buildDockedCue() {
        String angle = TOPIC_ANGLES[nextAngleIndex()];
        String style = pickStyle();
        String persona = "";
        try { persona = Prefs.personaForPet(this); } catch (Throwable ignore) {}
        StringBuilder sb = new StringBuilder();
        sb.append("你（桌宠）现在被主人贴到屏幕边缘关起来了，只能露一条缝看外面。");
        sb.append("你很想出来，很委屈，轻轻抱怨两句求主人放你出来，");
        sb.append("但不要真的生气，要可爱、让人心软。\n");
        if (persona != null && persona.trim().length() > 0) {
            sb.append("【你的性格设定】").append(persona.trim()).append("\n");
            sb.append("必须用上面这个性格的口吻说话：比如傲娇就嘴硬心软、"
                    + "爱撒娇就卖萌、活泼就喊着要出来玩、沉稳就好好讲道理。\n");
        } else {
            sb.append("用委屈又可爱的语气，像被关在门外的小动物。\n");
        }
        sb.append(memoryHintBlock());
        sb.append("这一次的角度要求：").append(angle).append("。\n");
        sb.append("句式风格要求：").append(style).append("。\n");
        sb.append(avoidRepeatBlock());
        sb.append("口语化、有情绪、20 字以内，只输出你要说的话本身，不要加引号或旁白。");
        return sb.toString();
    }

    /** 纯本地模式吸附态台词：被关住的委屈话（无网络/无 key 时用）。 */
    private String pickDockedLocalLine() {
        String[] pool = new String[]{
                "放我出去嘛…缝里好挤哦。",
                "呜…别把我关在边边啦。",
                "主人～我想出来玩！",
                "这里好窄，抱抱我嘛。",
                "别关我啦，我会乖乖的。",
                "缝里看不清主人…难过。"
        };
        return pickLocalLine(SceneRegistry.DOCK, pool);
    }


    private void askQuestion(final String question) {
        lastUserActMs = System.currentTimeMillis();
        hideAskBar();
        if (windowManager == null) return;
        // ★★ 用户 2026-10-07 定稿口径（当天纠正过两次）：
        //    **凡是"打字发消息"的入口都只走 API** —— 双击桌宠弹出的这个输入条
        //    也算"聊天框"，纯本地**不**适用于它。纯本地只作用于**互动**
        //    （戳 / 拖 / 长按 / 双击 / 贴边 / 整点 / 主动搭话这类桌宠自己的反应）。
        //    所以这里不读 localOnly()：没 Key 提示去填，有 Key 直接调 API。
        // ★★ 轮次64 补充：上面那条讲的是「纯本地**台词**」这个开关。
        //    用户在「服务商」里**显式选**「🤖 本地模型（手机本机跑，不联网）」是另一回事 ——
        //    那是在回答"这句话由谁来想"，所以他选了本地就该走本地（而且本地**不需要 Key**）。
        //    判据统一走 chatBackendReady()：在线要有 Key，本地模型不要。
        if (!chatBackendReady()) {
            // ★★★ 轮次75：本地模型入口下线期间，不再提一个看不到的选项。
            showBubble("请先在 App 里设置 API Key"
                    + (LocalModelFeature.ENABLED
                            ? "，或到「服务商」里选「\uD83E\uDD16 本地模型」" : "") + "哦", 5000);
            return;
        }
        cancelPendingSpeak();
        showBubble("💭 " + question + "\n⏳ 思考中…", 0);

        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);

        // 连续对话记忆：只带最近 N 条（可调上限），省 token；更早的内容靠「主动记忆点」兜住
        final java.util.List<String[]> mem = ChatMemory.recent(this, ChatMemory.ctxLimit(this));
        final String factsPrompt = ChatMemory.factsPrompt(this);
        ChatMemory.append(this, "user", question);

        lastModelMeme = null;
        new Thread(new Runnable() {
            @Override public void run() {
                // ★ 联网检索（待办 0d 路线 C）：开关开着且是"要新信息的问句"时先搜一次，
                //   把资料拼进**这次要发的**文本；显示与历史仍然用用户原话。
                //    ★★ 轮次64：本地后端**绝不联网检索**（"纯本地"就是这个意思）——
                //    判据统一走 ChatRouter.webAllowed，否则联网开关默认开着，
                //    本地对话会**偷偷发 HTTP**（本轮最不能出的错）。
                final boolean webOn = ChatRouter.webAllowed(FloatingService.this)
                        && Prefs.webSearch(FloatingService.this);
                if (webOn) {
                    handler.post(new Runnable() {
                        @Override public void run() {
                            showBubble("🌐 " + question + "\n🔎 联网检索中…", 0);
                        }
                    });
                }
                hintLocalLoadingIfNeeded();          // 本地后端首次要载入 ⇒ 先把等待反馈显出来
                final String sendText = webOn
                        ? WebClient.augment(FloatingService.this, question) : question;
                final WebClient.Last web = webOn ? WebClient.lastRun() : null;
                // ★★ 本地后端**流式出话**：逐段显示（sink 里只做 append）。
                //    在线链是 stream:false，所以传 null（保持原行为不变）。
                final LocalLlm.TokenSink sink = ChatRouter.isLocal(FloatingService.this)
                        ? bubbleSink("\uD83D\uDCAC ") : null;
                final ApiClient.ChatResult res = ChatRouter.chat(
                        FloatingService.this, apiKey, model, persona, factsPrompt, mem, sendText, sink);
                handler.post(new Runnable() {
                    @Override public void run() {
                        if (res != null && res.ok) {
                            ChatMemory.append(FloatingService.this, "assistant", res.reply);
                            showPetSaySplit("💬 ", res.reply, resolveMeme(res.memeName), 12000);
                            // 检索失败要**当面说**（否则用户以为"它就是不知道"）
                            if (web != null && web.ran && !web.ok) {
                                showBubble("🌐 这次没联上网（" + web.reason + "）", 5000);
                            }
                            // 异步整理记忆点（节流）★ 本地后端跳过：它走的是在线链，
                            // 本地必没有 Key ⇒ 只会白起一个必然失败的 HTTP 请求。
                            if (!ChatRouter.isLocal(FloatingService.this)) {
                                MemoryFacts.maybeUpdateAsync(FloatingService.this, apiKey, model, persona,
                                        ChatMemory.recent(FloatingService.this, ChatMemory.ctxLimit(FloatingService.this)));
                            }
                        } else {
                            showBubble("⚠️ " + (res == null ? "没有拿到回复" : res.error), 8000);
                            // ★★★ 轮次68 建立 / 轮次69 两态：**开着 GPU** 失败时不许"点了没反应" ——
                            //   气泡里已经有完整原因（LocalChat.guideGpuFailed），
                            //   这里再给一颗**真能一键切过去**的按钮（改成「关」＝纯 CPU）。
                            //   ★ 必须用 overlay 版的 Builder：服务里弹普通对话框会
                            //     BadTokenException，而那个异常的表现正是"点了没反应"。
                            GpuModeUi.showFallbackPromptIfNeeded(FloatingService.this, dlg());
                        }
                    }
                });
            }
        }, "pet-ask").start();
    }

    // ==================== 语音朗读归属（拟人化：只念桌宠自己说的话）====================

    /** 朗读任务序号：新一次发言会让「排队等念」的旧任务作废 */
    private int speakPlanSeq = 0;

    /**
     * 桌宠「开口说话」的唯一入口：显示气泡，并在语音开启时把这句念出来。
     *
     * <p>拟人化的关键 —— 气泡分两类，只有「桌宠自己说的话」才值得念：
     * <ul>
     *   <li><b>发言型</b>（用本方法）：AI 回复、主动搭话、点击/拖拽/动作台词、问候 —— 念。</li>
     *   <li><b>提示型</b>（继续用 {@link #showBubble}）：你的原话回显、⏳ 思考中、
     *       ⚠️ 报错、「已锁定」「记忆点已保存」等状态提示 —— 只显示，不念。</li>
     * </ul>
     * 「⏳ 思考中…」这类气泡会在等待期间被发言型气泡替换，而 TTS 一旦开始下载就
     * <b>不会</b>被后来的作废 —— 所以必须从源头就不给它派朗读任务，光靠事后判断不够。</p>
     */
    private void showPetSay(String prefix, String text, long durationMs, String memeName) {
        showBubble(prefix + text, durationMs, memeName);
        speakOwn(prefix + text, true);
    }

/**
     * 统一配图决策：模型选的最优先，其次按概率本地随机，最后返回 null（不配图）。
     *
     * <p>语义即「这张图贴不贴切」，所以只看概率；但绝不在「接图片」时再叠加一次概率，
     * 否则概率会被平方，用户设 30% 实际只剩 9%。</p>
     */
    private String resolveMeme(String modelPick) {
        if (!Prefs.memeEnabled(this)) return null;
        if (modelPick != null && modelPick.length() > 0 && Memes.has(this, modelPick)) {
            return modelPick;                                   // 模型选的，最贴切
        }
        return Memes.pick(this, Prefs.getMemeChance(this) / 100.0);   // 本地概率兜底
    }

    private void showPetSaySplit(String prefix, String reply, String memeName, long singleMs) {
        if (!Prefs.splitReply(this) || reply == null || reply.trim().length() == 0) {
            showPetSay(prefix, reply, singleMs, memeName);
            return;
        }
        java.util.List<String> segs = ReplySplitter.split(reply, Prefs.splitMax(this));
        if (segs.size() <= 1) {
            showPetSay(prefix, reply, singleMs, memeName);
            return;
        }
        cancelReplySplit();
        replyQueue.clear();
        replyQueue.addAll(segs);
        // 连发时是否配图由独立开关决定；开启则整轮共用同一张（不逐段换图，否则太吵）
        splitRoundMeme = (Prefs.memeEnabled(this) && Prefs.memeSplitEnabled(this)) ? memeName : null;
        final String first = replyQueue.remove(0);
        showBubbleInternal(prefix + first, SPLIT_BUBBLE_MS, splitRoundMeme);
        speakOwn(prefix + first, true);
        if (!replyQueue.isEmpty()) {
            handler.postDelayed(replyStepTask, Prefs.splitDelayMs(this));
        }
    }

    /**
     * 派发一次朗读：由「发言型」气泡专用。失败静默，绝不影响气泡显示。
     *
     * @param queue  是否排队。true 且当前正在念/正在下载时，等它念完再念这句 ——
     *               「回复连发」必须用这个：否则每条只隔 0.9 秒，而合成要 1~2 秒，
     *               后一句会把前一句直接打断，最后只有最后一句出声。
     */
    private boolean speakOwn(final String text, final boolean queue) {
        if (tts == null || !Prefs.ttsEnabled(this)) return false;
        if (queue && tts.isBusy()) {
            final int seq = ++speakPlanSeq;
            scheduleSpeakRetry(text, seq, 12);      // 最多约 12 x 250ms ~ 3 秒
            return false;
        }
        try {
            return tts.speak(text);      // speak 内部已做前缀/表情清洗与长度过滤
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 排队等待：每 250ms 看一次，念完了就补上这句；期间被新内容顶掉就放弃 */
    private void scheduleSpeakRetry(final String text, final int seq, final int left) {
        if (left <= 0) return;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (seq != speakPlanSeq) return;                 // 已被更新的发言取代
                if (tts == null || !Prefs.ttsEnabled(FloatingService.this)) return;
                if (tts.isBusy()) { scheduleSpeakRetry(text, seq, left - 1); return; }
                try { tts.speak(text); } catch (Throwable ignore) {}
            }
        }, 250);
    }

    /**
     * 出现「提示型」气泡时调用：让还没开口的排队语音作废，并让正在念的立刻停下。
     * 典型场景 —— 你刚发出消息，桌宠上一句还没念完，它应该停下来「思考」，
     * 而不是一边显示思考中一边继续念上一句。
     */
    private void cancelPendingSpeak() {
        speakPlanSeq++;
        if (tts == null) return;
        try { tts.stop(); } catch (Throwable ignore) {}
    }

    /** 显示对话气泡（独立悬浮窗）；durationMs<=0 表示常驻 */
    private void showBubble(final String msg, long durationMs) {
        showBubble(msg, durationMs, null);
    }

    /** 带表情包配图的气泡：memeName 为**角色包 memes/ 里的表情名**（可为 null；
     *  轮次㉜ 起 APK 里不再内置表情）
     *  会取消「回复连发」中尚未播完的后继消息（用户主动产生的新内容优先）。*/
    private void showBubble(final String msg, long durationMs, final String memeName) {
        cancelReplySplit();
        showBubbleInternal(msg, durationMs, memeName);
    }

    /** 低层气泡显示：不发取消信号，供「回复连发」引擎与 showBubble 内部复用 */
    private void showBubbleInternal(final String msg, long durationMs, final String memeName) {
        if (windowManager == null) return;
        // App 在前台时桌宠是收起的，此时冒气泡会孤零零盖在设置页上
        // （实测：进设置页后主动聊天刚好触发，气泡压住了「外观与显示」那一行）。
        // 这里挡住所有气泡显示的源头 —— 主动聊天、事件播报、回复连发都走这条路。
        if (petTempHidden) return;
        handler.removeCallbacks(hideBubbleTask);

        float d = getResources().getDisplayMetrics().density;
        float bscale = Prefs.getBubbleScale(this);
        int pad = (int) (d * 10);

        if (bubbleView == null) {
            // 外层 FrameLayout：主体(body) 在上，尖角(tail) 叠在底部居中，永远对准桌宠
            bubbleView = new FrameLayout(this);

            bubbleBody = new LinearLayout(this);
            bubbleBody.setOrientation(LinearLayout.VERTICAL);
            bubbleBody.setGravity(Gravity.CENTER_HORIZONTAL);
            // ★ 原来是 R.drawable.bubble_chat（XML 静态白→浅蓝渐变 + 淡蓝描边）。
            //   XML drawable 不懂主题 —— 深色模式下气泡是一块白板，
            //   和设置页完全不像一套 UI。
            //   改用令牌浮层底板：新拟态=bg 色靠阴影分层、玻璃态=半透明白/深蓝。
            bubbleBody.setBackground(floatPanelBg(THEME_RADIUS_DP));
            // 主体内边距要大，确保文字永远不会溢出云朵以外
            int padTop2 = (int) (d * 18 * bscale);
            int padBottom2 = (int) (d * 16 * bscale);
            int padH2 = (int) (d * 22 * bscale);
            bubbleBody.setPadding(padH2, padTop2, padH2, padBottom2);
            bubbleBody.setMinimumHeight((int) (d * 66 * bscale));

            bubbleMeme = new android.widget.ImageView(this);
            bubbleMeme.setAdjustViewBounds(true);
            bubbleMeme.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            bubbleMeme.setVisibility(View.GONE);
            int memeW = (int) (d * 160);
            bubbleBody.addView(bubbleMeme, new LinearLayout.LayoutParams(
                    memeW, LinearLayout.LayoutParams.WRAP_CONTENT));

            // ★ 轮次86：视频表情（mp4/webm）用 VideoView 播，默认隐藏；与上面那个 ImageView 互斥
            bubbleMemeVideo = new android.widget.VideoView(this);
            bubbleMemeVideo.setVisibility(View.GONE);
            bubbleBody.addView(bubbleMemeVideo, new LinearLayout.LayoutParams(
                    memeW, LinearLayout.LayoutParams.WRAP_CONTENT));

            bubbleText = new TextView(this);
            bubbleText.setTextColor(uiT().text1);
            bubbleText.setTextSize(13 * bscale);
            bubbleText.setGravity(Gravity.CENTER);          // 文字水平居中
            bubbleText.setIncludeFontPadding(false);
            bubbleText.setLineSpacing(0f, 1.12f);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                // 高质量断行：尽量不把标点/孤字留在行首行尾
                bubbleText.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);
                bubbleText.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            // 宽度上限：屏幕宽的 ~82%，超过即换行
            int maxBubbleW = (int) (dmS.widthPixels * 0.82f) - padH2 * 2;
            bubbleText.setMaxWidth(maxBubbleW);
            // 文本底部留白，避免压到下方尖角
            int tb = (int) (d * 6);
            bubbleText.setPadding(0, 0, 0, tb);
            bubbleBody.addView(bubbleText, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // 主体占满整窗（放在底层）
            FrameLayout.LayoutParams bodyLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            bodyLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            bubbleView.addView(bubbleBody, bodyLp);

            // 尖角：覆盖在主体下边缘中央，尖头朝下指向桌宠
            // ★ 原来用 R.drawable.bubble_tail（XML 静态淡蓝三角）。
            //   改用 PointerView —— 它按传入颜色自绘三角，
            //   所以能和气泡底板同色（否则深色主题下会出现
            //   "深色气泡 + 浅蓝尖角"这种接不上的观感）。
            bubbleTail = new PointerView(this, panelFillColor(), panelEdgeColor(), d);
            ((PointerView) bubbleTail).setDir(PointerView.DIR_BOTTOM);
            int tailW = (int) (d * 26 * bscale);
            int tailH = (int) (d * 14 * bscale);
            FrameLayout.LayoutParams tailLp = new FrameLayout.LayoutParams(tailW, tailH);
            tailLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // 让尖角上沿贴到主体底边内，避免出现缝隙
            tailLp.bottomMargin = 0;
            bubbleView.addView(bubbleTail, tailLp);
            // 外层底部给尖角预留高度
            bubbleView.setPadding(0, 0, 0, (int) (d * 6));
        }
        // Cached bubbles must follow theme changes without restarting the service.
        int bubbleThemeKey = Tokens.skin(this) * 10 + Tokens.theme(this);
        if (bubbleThemeKey != lastBubbleThemeKey) {
            lastBubbleThemeKey = bubbleThemeKey;
            bubbleBody.setBackground(floatPanelBg(THEME_RADIUS_DP));
            bubbleText.setTextColor(uiT().text1);
            if (bubbleTail instanceof PointerView) {
                ((PointerView) bubbleTail).setFill(panelFillColor());
            }
        }
        int shadowInset = UiFactory.inset(this);
        bubbleBody.setPadding((int) (d * 22 * bscale) + shadowInset,
                (int) (d * 18 * bscale) + shadowInset,
                (int) (d * 22 * bscale) + shadowInset,
                (int) (d * 16 * bscale) + shadowInset);
        bubbleText.setTextSize(13 * bscale);
        bubbleText.setText(msg);
        applyOrphanSafeWidth();
        applyMemeSize();
        applyBubbleMeme(memeName);

        // 让桌宠进入“说话”动作（张口/点头）
        // 吸附态不碰 scale/alpha（压扁竖条态，动了会闪），只驱动口型；
        // 其余态沿用原逻辑。
        if (petImage != null) {
            if (dockState == PetDockState.COLLAPSED) {
                try { petImage.setTalking(true); } catch (Throwable ignore) {}
            } else {
                petImage.setTalking(true);
            }
        }


        bubbleShowing = true;
        if (bubbleParams == null) {
            bubbleParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            bubbleParams.gravity = Gravity.TOP | Gravity.LEFT;
            applyCutoutMode(bubbleParams);    // 与桌宠同坐标系，否则横屏会错位
            try {
                windowManager.addView(bubbleView, bubbleParams);
            } catch (Exception e) {
                bubbleShowing = false;
                return;
            }
        }

        // 关键：先测量气泡真实尺寸，再按桌宠位置计算坐标（贴边翻转+夹紧）
        bubbleView.post(new Runnable() {
            @Override public void run() { positionBubble(); }
        });

        // 注意：对话气泡在桌宠上方、余额气泡在桌宠下方，互不遮挡，
        // 因此这里【不再】隐藏余额气泡，两者可同时存在。

        if (durationMs > 0) {
            long scaled = (long) (durationMs * Prefs.getBubbleDurScale(this));
            if (scaled < 800) scaled = 800;
            handler.postDelayed(hideBubbleTask, scaled);
        }
    }

    /** 按用户设置的缩放比例调整气泡配图宽度（每次显示都同步一次） */
    private void applyMemeSize() {
        if (bubbleMeme == null) return;
        android.view.ViewGroup.LayoutParams lp = bubbleMeme.getLayoutParams();
        if (lp == null) return;
        float d = getResources().getDisplayMetrics().density;
        int w = (int) (d * 160 * Prefs.getMemeScale(this));
        if (lp.width != w) {
            lp.width = w;
            bubbleMeme.setLayoutParams(lp);
        }
    }

    /** 停掉并隐藏视频表情播放器（切图 / 收起气泡 / onDestroy 都要调） */
    private void stopBubbleMemeVideo() {
        if (bubbleMemeVideo == null) return;
        try {
            if (bubbleMemeVideo.isPlaying()) bubbleMemeVideo.stopPlayback();
        } catch (Throwable ignore) {}
        try { bubbleMemeVideo.setVisibility(View.GONE); } catch (Throwable ignore) {}
    }

    /** 显示/隐藏气泡里的表情包配图 */
    private void applyBubbleMeme(String memeName) {
        if (bubbleMeme == null) return;
        if (memeName == null || memeName.length() == 0) {
            Memes.stopIfAnimated(bubbleMeme.getDrawable());   // ★ 轮次80：隐藏就停动画
            bubbleMeme.setVisibility(View.GONE);
            bubbleMeme.setImageDrawable(null);
            stopBubbleMemeVideo();                            // ★ 轮次86：视频也停掉
            return;
        }
        // ★★ 轮次86：视频表情 → 交给 VideoView（静音循环），图片那套完全让位
        if (Memes.isVideoName(memeName)) {
            java.io.File vf = Memes.fileOf(this, memeName);
            if (vf != null && bubbleMemeVideo != null) {
                bubbleMeme.setVisibility(View.GONE);
                bubbleMeme.setImageDrawable(null);
                bubbleMemeVideo.setVisibility(View.VISIBLE);
                bubbleMemeVideo.setVideoPath(vf.getAbsolutePath());
                bubbleMemeVideo.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(android.media.MediaPlayer mp) {
                        try {
                            mp.setLooping(true);   // 表情要一直动
                            mp.setVolume(0f, 0f);  // 静音：表情包不该突然出声
                        } catch (Throwable ignore) {}
                        // 按视频比例定尺寸（VideoView 没有 adjustViewBounds）
                        try {
                            int vw = mp.getVideoWidth(), vh = mp.getVideoHeight();
                            if (vw > 0 && vh > 0) {
                                int maxW = (int) (dp(160f) * Math.max(0.6f, Prefs.getMemeScale(FloatingService.this)));
                                int w = maxW, h = (int) ((float) vh / vw * maxW);
                                android.view.ViewGroup.LayoutParams lp = bubbleMemeVideo.getLayoutParams();
                                lp.width = w; lp.height = h;
                                bubbleMemeVideo.setLayoutParams(lp);
                            }
                        } catch (Throwable ignore) {}
                        bubbleMemeVideo.start();
                    }
                });
                android.util.Log.i("DSMeme", "显示视频表情: " + memeName + " (" + vf.getName() + ")");
                return;
            }
        }
        stopBubbleMemeVideo();
        try {
            // ★★ 轮次80：改走 openDrawable —— GIF / 动图 WebP 会真的动起来；
            //    旧代码 BitmapFactory.decodeStream 永远只给首帧（动态表情看着像静止图）。
            android.graphics.drawable.Drawable d = Memes.openDrawable(this, memeName, 0);
            if (d == null) {
                Memes.stopIfAnimated(bubbleMeme.getDrawable());
                bubbleMeme.setVisibility(View.GONE);
                android.util.Log.w("DSMeme", "找不到表情图: " + memeName);
                return;
            }
            bubbleMeme.setImageDrawable(d);
            bubbleMeme.setVisibility(View.VISIBLE);
            android.util.Log.i("DSMeme", "显示表情包: " + memeName
                    + " " + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight()
                    + (d instanceof android.graphics.drawable.AnimatedImageDrawable ? " 动态" : ""));
        } catch (Exception e) {
            bubbleMeme.setVisibility(View.GONE);
            android.util.Log.e("DSMeme", "加载异常: " + memeName + " " + e.getMessage());
        }
    }

    /** 桌宠本体在屏幕上的矩形（容器内桌宠水平居中） */
    // ===== 横竖屏：实时屏幕尺寸 =====
    private int realScreenW = 0, realScreenH = 0;
    /** 真实屏幕尺寸缓存时刻（见 refreshRealSize(boolean)）。 */
    private long realSizeAtMs = 0;
    private static final long REAL_SIZE_TTL_MS = 500;

    /**
     * 让悬浮窗允许覆盖**挖孔区**，使窗口坐标系与 {@link #refreshRealSize} 对齐。
     *
     * <h3>★ 为什么必须设（用户报的横屏 bug）</h3>
     * 桌宠窗口用了 {@code FLAG_LAYOUT_NO_LIMITS}，但**没有**设
     * {@code layoutInDisplayCutoutMode}，默认是 {@code DEFAULT} —— 窗口**不能覆盖挖孔区**，
     * 于是系统把窗口整体**内缩**了挖孔的那一条（本机 144px）。
     * 而 {@code screenW()} 走的是 {@code getRealMetrics()}，返回的是**含挖孔**的整屏宽
     * （横屏 3200）。两边坐标系差了一条挖孔宽，于是：
     * <ul>
     *   <li>右贴边 {@code x = screenW - peek} 算出来比可视区还靠右 ⇒ **陷进去**</li>
     *   <li>左贴边受内缩影响 ⇒ 贴不到最左边那条</li>
     * </ul>
     * 用户原话："横屏时左边有一条黑边、右边有一块不可见触摸区域，
     * 桌宠在右边会陷进去、在左边最左边的黑色区域无法贴边。"
     *
     * <p>设成 {@code ALWAYS} 后窗口可用整个屏幕，坐标与 {@code getRealMetrics()} 一致，
     * 贴边就能真正贴到屏幕边缘。
     */
    private void applyCutoutMode(WindowManager.LayoutParams lp) {
        if (lp == null) return;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                        .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } catch (Throwable ignore) {}
        }
    }

    /** 读取当前真实屏幕宽高（随旋转实时变化），并写入 realScreenW/H */
    private void refreshRealSize() {
        refreshRealSize(false);
    }

    /**
     * 真实屏幕尺寸，**带缓存**。
     *
     * <p>★ 用户报障（2026-10-07）"移动桌宠的时候太卡顿"：拖动时每帧都会走到
     *   {@code clampToScreen()} 与 {@code positionBalance()}，而它们各自调一次
     *   {@code refreshRealSize()} —— 每帧两次 {@code Display.getRealMetrics()} +
     *   {@code getRotation()}（都是跨进程查询），而屏幕尺寸**几乎不会变**。
     *   现在 500ms 内直接复用缓存；旋转走 {@link #onConfigurationChanged} 的强制刷新，
     *   所以旋转后最多晚一帧。
     */
    private void refreshRealSize(boolean force) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!force && realScreenW > 0 && realScreenH > 0
                && (now - realSizeAtMs) < REAL_SIZE_TTL_MS) {
            return;
        }
        realSizeAtMs = now;
        try {
            int w, h;
            if (windowManager != null && windowManager.getDefaultDisplay() != null) {
                android.view.Display disp = windowManager.getDefaultDisplay();
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                disp.getRealMetrics(dm);
                w = dm.widthPixels; h = dm.heightPixels;
                // 兼容部分设备 getRealMetrics 不随旋转交换宽高：按旋转角纠正
                int rot = disp.getRotation();
                boolean landscape = (rot == android.view.Surface.ROTATION_90
                        || rot == android.view.Surface.ROTATION_270);
                if (landscape && h > w) { int t = w; w = h; h = t; }
                else if (!landscape && w > h) { int t = w; w = h; h = t; }
            } else {
                android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                w = dm.widthPixels; h = dm.heightPixels;
            }
            realScreenW = w; realScreenH = h;
        } catch (Throwable t) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            realScreenW = dm.widthPixels; realScreenH = dm.heightPixels;
        }
    }

    private int screenW() { if (realScreenW <= 0) refreshRealSize(); return realScreenW; }
    private int screenH() { if (realScreenH <= 0) refreshRealSize(); return realScreenH; }

    /** 当前动画内容边距比例 {L,R,T,B}；未就绪返回 {0,0,0,0} */
    private float[] insetOrZero() {
        float[] in = (petImage != null) ? petImage.currentContentInset() : null;
        return in != null ? in : new float[]{0f, 0f, 0f, 0f};
    }

    /** 角色可见内容在屏幕上的矩形 {left, top, width, height}（已考虑动画左右上下留白） */
    private int[] contentRectOnScreen(android.util.DisplayMetrics dm) {
        int winX = params != null ? params.x : 0;
        int winY = params != null ? params.y : 0;
        int winW = root != null && root.getWidth() > 0 ? root.getWidth()
                : (int) (dm.density * BASE_PET_W * Prefs.getScale(this));
        int winH = root != null && root.getHeight() > 0 ? root.getHeight()
                : (int) (dm.density * BASE_PET_H * Prefs.getScale(this));
        float[] in = insetOrZero();
        int left = winX + (int) (in[0] * winW);
        int right = winX + winW - (int) (in[1] * winW);
        int top = winY + (int) (in[2] * winH);
        int bottom = winY + winH - (int) (in[3] * winH);
        if (right < left) right = left;
        if (bottom < top) bottom = top;
        return new int[]{left, top, right - left, bottom - top};
    }

    private int[] petRectOnScreen(android.util.DisplayMetrics dm) {
        int petX = params != null ? params.x : 0;
        int petY = params != null ? params.y : 0;
        int winW = root != null && root.getWidth() > 0 ? root.getWidth() : 0;
        int winH = root != null && root.getHeight() > 0 ? root.getHeight() : 0;
        int petW = (int) (dm.density * BASE_PET_W * Prefs.getScale(this));
        int petH = (int) (dm.density * BASE_PET_H * Prefs.getScale(this));
        if (winW <= 0) winW = petW;
        if (winH <= 0) winH = petH;
        int left = petX + Math.max(0, (winW - petW) / 2);
        return new int[]{left, petY, petW, petH};
    }

    /**
     * 防止“最后一行只剩一两个字”的孤字行：
     * 在允许的最大宽度内，挑选一个宽度，使文本换行后末行字数不至于过少。
     */
    private void applyOrphanSafeWidth() {
        if (bubbleText == null) return;
        android.view.WindowManager wm =
                (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null || wm.getDefaultDisplay() == null) return;
        refreshRealSize();
        dmS.density = getResources().getDisplayMetrics().density;
        int maxW = (int) (screenW() * 0.82f) - (int) (dmS.density * 44);
        if (maxW < (int) (dmS.density * 80)) maxW = (int) (dmS.density * 80);
        // 每次都重置为基准上限，避免影响后续更长的文本
        bubbleText.setMaxWidth(maxW);
        CharSequence txt = bubbleText.getText();
        if (txt == null || txt.length() == 0) return;

        android.text.TextPaint tp = bubbleText.getPaint();
        float fullW = tp.measureText(txt.toString());
        if (fullW <= maxW) {
            // 单行放得下：宽度随文字自适应，不人为拉宽
            bubbleText.setMaxWidth((int) Math.ceil(fullW) + 2);
            return;
        }
        // 需要换行：从 maxW 开始向下微调，找一个末行不太短的宽度
        int bestW = maxW;
        int bestOrphan = Integer.MAX_VALUE;
        int minW = (int) (maxW * 0.55f);
        for (int w = maxW; w >= minW; w -= (int) (dmS.density * 6)) {
            android.text.StaticLayout sl = android.text.StaticLayout.Builder
                    .obtain(txt, 0, txt.length(), tp, w)
                    .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0f, 1.12f)
                    .setIncludePad(false)
                    .build();
            if (sl.getLineCount() == 0) continue;
            int last = sl.getLineStart(sl.getLineCount() - 1);
            int lastLen = txt.length() - last;
            // 取末行字数最接近“>=3”的方案；若都很少，选字数最多者
            if (lastLen < 3 && lastLen < bestOrphan) { bestOrphan = lastLen; bestW = w; }
            if (lastLen >= 3) { bestW = w; break; }
        }
        bubbleText.setMaxWidth(bestW);
    }

    /** 对话气泡定位：始终在桌宠正上方，尖角对准桌宠中心；靠边时夹紧屏幕内但绝不翻到下方 */
    private void positionBubble() {
        if (bubbleView == null || bubbleParams == null || !bubbleShowing) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        int screenW = screenW();
        int screenH = screenH();

        int bubW = bubbleView.getWidth();
        int bubH = bubbleView.getHeight();
        if (bubW <= 0) bubW = (int) (dm.density * 200);
        if (bubH <= 0) bubH = (int) (dm.density * 70);

        int[] cr = contentRectOnScreen(dm);
        int petLeft = cr[0], petTop = cr[1], petW = cr[2];
        int petCenterX = petLeft + petW / 2;

        int gap = (int) (dm.density * 8);

        // 水平：优先让气泡中心对齐桌宠中心；超出屏幕才夹紧
        int x = petCenterX - bubW / 2;
        boolean clamped = false;
        if (x < 0) { x = 0; clamped = true; }
        if (x + bubW > screenW) { x = Math.max(0, screenW - bubW); clamped = true; }

        // 垂直：始终在桌宠上方
        int y = petTop - bubH - gap;
        if (y < 0) y = 0;               // 顶部放不下就贴顶，仍在上方

        bubbleParams.x = x;
        bubbleParams.y = y;
        try { windowManager.updateViewLayout(bubbleView, bubbleParams); } catch (Exception ignore) {}

        // 尖角：跟随桌宠中心，保证始终指向桌宠（相对于气泡左缘定位）
        if (bubbleTail != null && bubbleW2() > 0) {
            int tailW = bubbleTail.getWidth() > 0 ? bubbleTail.getWidth() : (int) (dm.density * 26);
            int tailMargin = petCenterX - x - tailW / 2;
            int minM = (int) (dm.density * 14);   // 不超出气泡左右圆角范围
            int maxM = bubbleView.getWidth() - tailW - (int) (dm.density * 14);
            if (maxM < minM) maxM = minM;
            if (tailMargin < minM) tailMargin = minM;
            if (tailMargin > maxM) tailMargin = maxM;
            android.widget.FrameLayout.LayoutParams tlp = (android.widget.FrameLayout.LayoutParams) bubbleTail.getLayoutParams();
            tlp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            tlp.leftMargin = tailMargin;
            bubbleTail.setLayoutParams(tlp);
        }
    }

    private int bubbleW2() {
        return bubbleView != null && bubbleView.getWidth() > 0 ? bubbleView.getWidth() : 0;
    }

    /** 余额气泡定位：默认贴桌宠正下方（不遮挡），下方放不下则放上方，靠边夹紧屏幕内 */
    private void positionBalance() {
        if (text == null || balanceParams == null) return;
        // ★ 拖动时每帧都会调到这里（用户报障"移动桌宠太卡顿"）：
        //   气泡**没显示**就根本不需要重算，更不需要发窗口更新 ——
        //   之前无条件算完再 updateViewLayout，等于每帧白送一次跨进程布局。
        if (balRoot == null || balRoot.getVisibility() != View.VISIBLE) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        int screenW = screenW();
        int screenH = screenH();

        int bw = (int) (text.getWidth() * balScale);
        int bh = (int) (text.getHeight() * balScale);
        if (bw <= 0) bw = (int) (dm.density * 220 * balScale);
        if (bh <= 0) bh = (int) (dm.density * 40 * balScale);

        int[] cr = contentRectOnScreen(dm);
        int petLeft = cr[0], petTop = cr[1], petW = cr[2], petH = cr[3];
        int petBottom = petTop + petH;
        int petCenterX = petLeft + petW / 2;

        int gap = (int) (dm.density * 6);

        int x = petCenterX - bw / 2;
        if (x < 0) x = 0;
        if (x + bw > screenW) x = Math.max(0, screenW - bw);

        // 垂直：始终显示在桌宠下方（不翻到上方）；底部空间不足时贴住屏幕底
        int y = petBottom + gap;
        if (y + bh > screenH) {
            y = screenH - bh;
        }
        if (y < 0) y = 0;

        // 位置没变就不发窗口更新（同 applyRootLayout 的理由）
        if (x == lastBalX && y == lastBalY) return;
        lastBalX = x; lastBalY = y;
        balanceParams.x = x;
        balanceParams.y = y;
        try { windowManager.updateViewLayout(balRoot, balanceParams); } catch (Exception ignore) {}
    }

    /** 桌宠中心是否已进入左右边缘热区（约屏幕两侧 12%），用于决定松手是否吸附 */
    private boolean nearScreenEdge() {
        try {
            refreshRealSize();
            int[] rx = edgeRangeX();
            int minX = rx[0];
            int maxX = rx[1];
            int curX = params != null ? params.x : 0;
            int span = maxX - minX;
            if (span <= 0) return true;
            float ratio = (curX - minX) / (float) span;   // 0=最左, 1=最右
            float hot = 0.12f;                            // 边缘热区比例
            return ratio <= hot || ratio >= (1f - hot);
        } catch (Throwable t) {
            return true;   // 异常时退回旧的“必吸附”行为，保证可用
        }
    }

    /** 旋转/尺寸变化后：刷新屏幕尺寸，夹紧桌宠并重排浮层（横竖屏适配） */
    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshRealSize(true);        // 旋转/折叠：必须**强制**刷新（缓存 TTL 会晚 0.5 秒）
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (root == null || params == null) return;
                // 吸附态旋转：重贴当前边（屏幕宽变了，旧 x 会悬空或出屏）
                if (dockState == PetDockState.COLLAPSED) {
                    try { params.x = dockEdgeX(dockEdge); } catch (Throwable ignore) {}
                } else {
                    clampToScreen();
                }
                try { applyRootLayout(); } catch (Exception ignore) {}
                Prefs.savePos(FloatingService.this, params.x, params.y);
                if (bubbleShowing) positionBubble();
                positionBalance();
                if (longPressShowing) positionLongPressBubble();
                if (askShowing) {
                    positionAskBar();
                    try { windowManager.updateViewLayout(askRoot, askParams); } catch (Exception ignore) {}
                }
            }
        }, 120);
    }

    /** 松手后吸附到最近的左/右屏幕边缘（带缓动动画），并同步余额气泡 */
    /**
     * 贴边时的**动作反馈**（轮次㊽）：播 {@code dock} 场景 + 说贴边台词 + 打一条日志。
     *
     * <p>★ 为什么抽成一个小方法：它有**两个调用时机** —— "已经在边上"（拖动松手时窗口早已被
     * clamp 到 min/max）与"正要滑到边上"。前者原来直接 {@code return} 掉了，
     * 于是用户最常走的那条贴边路径**一次都没播过动作**（「贴边动作没有触发」的真凶）。
     *
     * <p>★ 用动作自己的时长（{@code playSceneOnce} 内部取 clip 时长）；
     * 该场景没绑动作时**静默跳过**（不崩、不空转）。台词没写自定义就不说。
     */
    private void playDockFeedback(int startX, int targetX, int minX) {
        playSceneOnceSafe(SceneRegistry.DOCK, 1200);
        saySceneLine(SceneRegistry.DOCK);
        android.util.Log.i("DSPDock", "贴边动作 scene=dock 贴"
                + (targetX == minX ? "左" : "右") + " " + startX + "→" + targetX);
    }

    // ==================== 挂边探头（轮次77）====================
    //
    // 与"压扁竖条"（COLLAPSED）并列的另一种藏法：身体挂到屏幕外，只留头肩贴在边上，
    // 会呼吸、隔一会儿再探一下、被点一下会抬头站直看人。
    // 姿态数学全在纯类 {@link PeekPose}（fpstest [35] 钉着 18 条不变量），这里只做三件事：
    //   ① 推时间轴（哪个阶段、什么时候换站）；② 把 Pose 写进 PetView；③ 让窗口 x 合法地挂出屏外。
    //
    // ★ 铁律：探头态下"角色可见内容"**必须允许出屏** —— 所以 edgeRangeX() 临时放宽。
    //   否则任何一次 clampToScreen()（换动画 / 分辨率变化 / 气泡定位）都会把挂好的探头
    //   一把拽回屏内，表现成"刚挂上去就自己缩回来了"。

    /** 当前阶段（PeekPose.OFF = 没挂边） */
    private int peekPhase = PeekPose.OFF;
    /** 本阶段开始时刻（一律按墙钟算，不用 tick 计数：帧率档位变化不影响时间轴） */
    private long peekPhaseStartMs = 0L;
    /** 进入"挂住"的时刻（呼吸相位 + 小探头排程都按它起算） */
    private long peekPerchStartMs = 0L;
    /** 挂哪一侧：PeekPose.EDGE_LEFT / EDGE_RIGHT */
    private int peekEdge = PeekPose.EDGE_LEFT;
    /** 内容正好贴边时的窗口 x（探头的基准位；回缩也回到它） */
    private int peekBaseX = 0;
    /** 身体框（窗口内 px）：露出量按**身体宽**算，不能按整窗口宽 */
    private int peekBodyLeft = 0;
    private int peekBodyW = 1;
    private int peekBodyTop = 0;
    private int peekBodyH = 1;
    /** WITHDRAW 的起步露出量（任意半截处取消都不跳变） */
    private float peekFromKeep = 1f;
    /**
     * 探头期间的基准缩放（进探头时读一次）。
     *
     * <p>★ 不能在 60 Hz 的帧循环里调 {@code Prefs.getScale(this)}：那个 getter 每次都
     *   重新 {@code getSharedPreferences} + {@code getFloat}，每帧一次纯属白烧。
     *   缩放是用户在设置里改的，探头这几秒内本来也不会变（探头态捏合也被 MOVE 分支吃掉了）。
     */
    private float peekBaseScale = 1f;
    /** 小探头种子（每次进 PERCH 递增 ⇒ 间隔成串但不重复；纯函数 ⇒ 可复现） */
    private int peekNudgeSeed = 1;
    /** 探头态下手指是否已挪动（判断"点一下"还是"想拖走"） */
    private boolean peekDownMoved = false;
    /** 拖动接管中（跟手阶段：时间轴让位，露头量由窗口 x 决定） */
    private boolean peekDragging = false;
    /** 挂边前余额气泡是不是显示的（退出时按 isVisible 恢复，不再记别的状态） */
    private boolean peekBalWasVisible = false;
    /** 已登记的系统手势排除区尺寸（-1 = 还没登记） */
    private int gestExclW = -1;
    private int gestExclH = -1;
    /** 挂边启动的重试计数（内容留白是异步量的，没就绪就等下一拍，别放弃） */
    private int peekRetry = 0;
    /** 留白未就绪时的重试任务 */
    private final Runnable peekRetryTask = new Runnable() {
        @Override public void run() { peekBegin(true); }
    };

    /** 当前挂边的可调参数（进挂边时从 Prefs 读一次；改设置后重建会重新读） */
    private PeekPose.Tuning peekTune = PeekPose.Tuning.DEFAULT;
    /** 拖动起点的窗口坐标（跟手用**绝对位移**算，不用累加增量 —— 增量遇丢帧/取消就漂） */
    private int peekDragStartX = 0;
    private int peekDragStartY = 0;
    private float peekDownRawX = 0f;
    private float peekDownRawY = 0f;
    /**
     * 驱动帧间隔。
     *
     * <p>★ 分档（轮次77 收尾的功耗口径）：换站/过渡（挤、探、抬头、缩回、回落）要顺，
     * 用 16ms（60fps）；**常驻挂住**时只有呼吸 + 小探头，33ms（30fps）肉眼无差，
     * 却把主线程唤醒次数砍一半 —— 挂边是长时间驻留状态，这一档省的是常态功耗。
     */
    private static final long PEEK_FRAME_MS = 16L;
    private static final long PEEK_FRAME_IDLE_MS = 33L;
    /** 帧循环总开关：false = 冻住（手按住时用，避免和手势抢 PetView 变换） */
    private boolean peekLoopOn = false;
    /** "滑到边上再挂"的那一段位移动画（菜单入口用；可取消） */
    private android.animation.ValueAnimator peekAnim;

    private final Runnable peekFrame = new Runnable() {
        @Override public void run() { peekStep(System.currentTimeMillis()); }
    };

    /** 小探头：挂住期间隔 7.2–11.8s 再探一下 */
    private final Runnable peekNudge = new Runnable() {
        @Override public void run() {
            if (peekPhase != PeekPose.PERCH) return;
            peekNudgeSeed = peekNudgeSeed * 31 + 7;
            peekEnterPhase(PeekPose.NUDGE);
        }
    };

    /**
     * 抬头看够时间 ⇒ 自己挂回去。
     *
     * <p>★★ 轮次78：必须走 {@link PeekPose#SETTLE}（260ms 平滑收回），
     * 不能直接 `peekEnterPhase(PERCH)` —— 那是从「抬头 0°/keep 0.90」一帧跳到
     * 「leanMax/keep peer」，真机观感是"闪一下就回去了"（用户报障）。
     * SETTLE 的 keep 从当前值收到常驻位，倾角由 keep 换算 ⇒ 是"慢慢歪回去"。
     */
    private final Runnable peekHoldDone = new Runnable() {
        @Override public void run() {
            if (peekPhase != PeekPose.LIFT) return;
            peekFromKeep = PeekPose.LIFT_KEEP;   // 抬头位就是当前露出量
            peekEnterPhase(PeekPose.SETTLE);
        }
    };

    /**
     * 如果需要，就**自动挂回**（重建视图 / 临时收起恢复 / 改设置之后调）。
     *
     * <p>★ 场景：用户正挂着的时候改了个设置（`Prefs.reloadPet` 重建视图）——
     *   旧代码会 `peekAbort()` 直接把挂边收掉，人还是贴在边上却变回"站着"，
     *   观感像"改了设置就莫名其妙掉回桌面"。这里重建完重新判一次：
     *   形态是挂边 + 位置已经贴边 ⇒ 直接挂回去。
     */
    private void peekResumeIfAtEdge() {
        try {
            if (peekPhase != PeekPose.OFF) return;
            if (!peekOn()) return;
            if (params == null || root == null || petImage == null) return;
            if (dockState != PetDockState.NORMAL) return;
            refreshRealSize();
            int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
            int leftDist = Math.abs(params.x - rx[0]);
            int rightDist = Math.abs(params.x - rx[1]);
            if (Math.min(leftDist, rightDist) > dp(6f)) return;   // 不在边上就别乱挂
            peekBegin();
        } catch (Throwable ignore) {}
    }

    /** 从设置里读「角度 / 深度」（越界由 Prefs 夹好，纯类再兜一层） */
    private PeekPose.Tuning readPeekTuning() {
        try {
            return PeekPose.Tuning.sanitized(
                    Prefs.peekKeepPercent(this) / 100f, Prefs.peekLeanDeg(this));
        } catch (Throwable t) {
            return PeekPose.Tuning.DEFAULT;
        }
    }

    /** 开关（默认关：不改变老用户已有的观感；开法见 {@code Prefs.setPeekEnabled}） */
    private boolean peekOn() {
        try { return Prefs.peekEnabled(this); } catch (Throwable t) { return false; }
    }

    /** 量身体框（px）：用当前动画的内容留白 + 窗口尺寸推，不额外依赖 manifest */
    private void peekMeasureBody() {
        int winW = petWinWidth();
        int winH = (root != null && root.getHeight() > 0) ? root.getHeight() : dp(BASE_PET_H);
        if (winW <= 0) winW = dp(BASE_PET_W);
        float[] in = insetOrZero();
        peekBodyLeft = (int) (winW * in[0]);
        peekBodyW = Math.max(1, (int) (winW * (1f - in[0] - in[1])));
        peekBodyTop = (int) (winH * in[2]);
        peekBodyH = Math.max(1, (int) (winH * (1f - in[2] - in[3])));
    }

    /**
     * 贴到边上静止后挂上去（{@link #snapToNearestEdge} 的落点调用）。
     *
     * <p>互斥：压扁吸附（COLLAPSED）与探头是两种藏法，同一时刻只许一种。
     * 已经在探头态就**不重播**"挤一下"（重复调用是幂等的）。
     */
    private void peekBegin() { peekBegin(false); }

    /**
     * @param retry true = 这是"留白还没量出来"的重试（不要重复计数、不要重复打日志）
     */
    private void peekBegin(boolean retry) {
        if (params == null || root == null || petImage == null) return;
        if (!peekOn()) return;
        if (dockState != PetDockState.NORMAL) return;   // 与压扁竖条互斥
        if (peekPhase != PeekPose.OFF) return;
        boolean dockOn = true;
        try { dockOn = Prefs.dockEnabled(this); } catch (Throwable ignore) {}
        if (!dockOn) return;                            // 关掉「贴边吸附」就不自动挂
        try {
            refreshRealSize();
            // ★★ 轮次84：内容留白是**异步**量的（alpha 扫描）。换动作/刚启动时还是 null，
            //   这时算出来的 rx 是"整窗贴边"，和真实贴边位差几十像素 ⇒ 旧代码直接判定
            //   "不在边上"放弃 —— 这就是"有概率挂不上边"的根因（概率=松开时扫描有没有跑完）。
            if (!ContentInset.usable(petImage.currentContentInset())) {
                if (!retry && peekRetry < 5) {
                    peekRetry++;
                    android.util.Log.i("DSPPeek", "挂边等待留白就绪，" + (peekRetry * 150)
                            + "ms 后重试（第 " + peekRetry + " 次）");
                    handler.removeCallbacks(peekRetryTask);
                    handler.postDelayed(peekRetryTask, 150);
                } else if (retry && peekRetry < 5) {
                    peekRetry++;
                    handler.removeCallbacks(peekRetryTask);
                    handler.postDelayed(peekRetryTask, 150);
                } else {
                    android.util.Log.w("DSPPeek", "挂边放弃：内容留白一直没就绪");
                    peekRetry = 0;
                }
                return;
            }
            peekRetry = 0;
            peekMeasureBody();
            int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
            int center = (rx[0] + rx[1]) / 2;
            peekEdge = (params.x <= center) ? PeekPose.EDGE_LEFT : PeekPose.EDGE_RIGHT;
            peekBaseX = (peekEdge == PeekPose.EDGE_LEFT) ? rx[0] : rx[1];
            // ★ 容差 6dp → 24dp：只要在边上附近就**吸附到位再挂**（贴边本来就是吸到边上），
            //   不再因为落点差一两像素就整段放弃。
            int tolPx = dp(24f);
            // ★ 越界也认：挂边期间 edgeRangeX() 放宽过，松手落点可能比贴边位再往外一截，
            //   那正是"想挂上去"的意思 —— 只要不离谱就夹回贴边位再挂。
            int beyond = (peekEdge == PeekPose.EDGE_LEFT)
                    ? (peekBaseX - params.x) : (params.x - peekBaseX);
            if (beyond > 0 && beyond <= peekBodyW) tolPx = beyond;
            if (Math.abs(params.x - peekBaseX) > tolPx) {
                android.util.Log.i("DSPPeek", "挂边跳过：离边太远 dx="
                        + Math.abs(params.x - peekBaseX) + " > " + tolPx
                        + " x=" + params.x + " baseX=" + peekBaseX);
                return;
            }
            params.x = peekBaseX;
            applyRootLayout();
            peekNudgeSeed = peekNudgeSeed * 1103515245 + 12345;
            peekFromKeep = 1f;
            peekBaseScale = Prefs.getScale(this);   // 帧循环里不再重复读（见字段注释）
            peekTune = readPeekTuning();
            peekEnterPhase(PeekPose.SQUEEZE);
            android.util.Log.i("DSPPeek", "挂边探头 开 边="
                    + (peekEdge == PeekPose.EDGE_LEFT ? "左" : "右")
                    + " baseX=" + peekBaseX + " bodyW=" + peekBodyW);
        } catch (Throwable t) {
            android.util.Log.w("DSPPeek", "挂边探头启动失败", t);
            peekAbort();
        }
    }

    /**
     * 贴边吸附的**挂边形态**入口：先滑到最近的屏幕边，落定后挂上去。
     *
     * <p>与"拖到边缘松手自动挂"（{@link #peekBegin}）走同一套阶段机，区别只有一个：
     * 这里桌宠可能还在屏幕中间，所以要先把窗口滑到边上（180ms），**不瞬移**。
     * 长按菜单的「贴边吸附」和拖动松手的自动吸附最终都落到这一条路上。
     */
    private void peekEnterFromDock() {
        if (params == null || root == null || petImage == null) return;
        if (!peekOn()) return;
        if (dockState != PetDockState.NORMAL) return;   // 与压扁竖条互斥
        if (peekPhase != PeekPose.OFF) return;
        try {
            refreshRealSize();
            // ★ 轮次84：菜单入口同样要等留白就绪，否则 bodyW 会算成整窗宽（挂得过头）
            if (!ContentInset.usable(petImage.currentContentInset())) {
                if (peekRetry < 5) {
                    peekRetry++;
                    android.util.Log.i("DSPPeek", "菜单挂边等待留白就绪，150ms 后重试");
                    handler.removeCallbacks(peekRetryTask);
                    handler.postDelayed(peekRetryTask, 150);
                }
                return;
            }
            peekRetry = 0;
            peekMeasureBody();
            int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
            int center = (rx[0] + rx[1]) / 2;
            peekEdge = (params.x <= center) ? PeekPose.EDGE_LEFT : PeekPose.EDGE_RIGHT;
            peekBaseX = (peekEdge == PeekPose.EDGE_LEFT) ? rx[0] : rx[1];
            peekBaseScale = Prefs.getScale(this);
            peekTune = readPeekTuning();
            peekNudgeSeed = peekNudgeSeed * 1103515245 + 12345;
            peekFromKeep = 1f;
            // ★ 复用工程自己的「贴边」场景动作与台词（角色包里绑的就是这个场景，
            //   比如「原地重力下蹲压缩」）。挂边只是**姿态**，动作与台词仍由场景系统给：
            //   换角色/换绑动作时挂边自动跟着变，这个文件不用改。
            playSceneOnceSafe(SceneRegistry.DOCK, 1200);
            saySceneLine(SceneRegistry.DOCK);
            final int startX = params.x;
            final int fixedY = params.y;
            if (startX == peekBaseX) {
                peekEnterPhase(PeekPose.SQUEEZE);
                return;
            }
            peekCancelAnim();
            android.animation.ValueAnimator anim =
                    android.animation.ValueAnimator.ofInt(startX, peekBaseX);
            anim.setDuration(180);
            anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
            anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                    if (params == null || root == null) return;
                    params.x = (Integer) a.getAnimatedValue();
                    params.y = fixedY;
                    try { applyRootLayout(); } catch (Exception ignore) {}
                }
            });
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator a) {
                    if (params == null) return;
                    params.x = peekBaseX;
                    params.y = fixedY;
                    try { applyRootLayout(); } catch (Exception ignore) {}
                    Prefs.savePos(FloatingService.this, params.x, params.y);
                    peekEnterPhase(PeekPose.SQUEEZE);
                }
            });
            peekAnim = anim;
            anim.start();
            android.util.Log.i("DSPPeek", "贴边吸附(挂边形态) 滑向"
                    + (peekEdge == PeekPose.EDGE_LEFT ? "左" : "右")
                    + " " + startX + "→" + peekBaseX + " bodyW=" + peekBodyW);
        } catch (Throwable t) {
            android.util.Log.w("DSPPeek", "挂边启动失败", t);
            peekAbort();
        }
    }

    /** 取消"滑到边上"那一段位移（只取消位移，不动已挂住的姿态） */
    private void peekCancelAnim() {
        if (peekAnim != null) {
            try { peekAnim.cancel(); } catch (Throwable ignore) {}
            peekAnim = null;
        }
    }

    /** 冻住时间轴（手按下的那一刻）：停帧循环与三个定时器，姿态交给手势决定 */
    private void peekFreeze() {
        peekLoopOn = false;
        handler.removeCallbacks(peekFrame);
        handler.removeCallbacks(peekNudge);
        handler.removeCallbacks(peekHoldDone);
        peekCancelAnim();
    }

    /** 进入某个阶段（所有换站都走这里：计时、排程、收口只有一份） */
    private void peekEnterPhase(int phase) {
        peekPhase = phase;
        peekPhaseStartMs = System.currentTimeMillis();
        handler.removeCallbacks(peekFrame);
        handler.removeCallbacks(peekNudge);
        handler.removeCallbacks(peekHoldDone);
        if (phase == PeekPose.OFF) {
            peekFromKeep = 1f;
            peekPerchStartMs = 0L;
            resetPeekPose();
            // 侧面监测条是**独立悬浮窗**，挂边期间必须先收起来（它的位置按窗口 x 算，
            // 而挂边时窗口 x 是负的一大截）—— 收口时再按正常态重新定位。
            try { positionMonitorSide(); } catch (Throwable ignore) {}
            // 余额气泡同上：按 isVisible（用户单击切换的那个真值）恢复
            try { setBalanceBubbleVisible(isVisible); } catch (Throwable ignore) {}
            android.util.Log.i("DSPPeek", "挂边探头 收");
            return;
        }
        if (phase == PeekPose.SQUEEZE) {
            try { monitorHideSide(); } catch (Throwable ignore) {}
            // ★ 挂边期间把余额气泡收起来（它是独立悬浮窗，按窗口 x 定位，挂边时会被拖到屏外/挡住头）。
            //   退出挂边时按 isVisible 恢复 —— 跟"临时收起桌宠"用同一套口径。
            try {
                peekBalWasVisible = isVisible;
                if (balRoot != null) balRoot.setVisibility(android.view.View.GONE);
            } catch (Throwable ignore) {}
        }
        if (phase == PeekPose.PERCH) {
            peekPerchStartMs = peekPhaseStartMs;
            handler.postDelayed(peekNudge, PeekPose.nextNudgeDelayMs(peekNudgeSeed));
        }
        if (phase == PeekPose.LIFT) {
            handler.postDelayed(peekHoldDone, PeekPose.LIFT_MS + PeekPose.LIFT_HOLD_MS);
        }
        peekLoopOn = true;
        handler.post(peekFrame);
    }

    /** 每帧推进：取姿态 → 写 View/窗口 → 判断换站 */
    private void peekStep(long now) {
        if (peekPhase == PeekPose.OFF || !peekLoopOn) { peekLoopOn = false; return; }
        if (params == null || petImage == null) { peekAbort(); return; }
        long t = now - peekPhaseStartMs;
        long age = (peekPerchStartMs > 0L) ? (now - peekPerchStartMs) : 0L;
        if (age < 0L) age = 0L;
        final int phase = peekPhase;
        // ★ 挂边期间余额气泡必须一直藏着：它可能被轮询/设置/重建任意一条路径重新显示出来，
        //   所以在这里做"幂等收口"——只有不是 GONE 才写一次，60fps 下没有额外开销。
        try {
            if (balRoot != null && balRoot.getVisibility() != android.view.View.GONE) {
                balRoot.setVisibility(android.view.View.GONE);
            }
        } catch (Throwable ignore) {}
        applyPeekPose(PeekPose.at(phase, t, age, peekFromKeep, peekTune));
        switch (phase) {
            case PeekPose.SQUEEZE:
                if (t >= PeekPose.SQUEEZE_MS) { peekEnterPhase(PeekPose.PROBE); return; }
                break;
            case PeekPose.PROBE:
                if (t >= PeekPose.PROBE_MS) { peekEnterPhase(PeekPose.PERCH); return; }
                break;
            case PeekPose.NUDGE:
                if (t >= PeekPose.NUDGE_MS) { peekEnterPhase(PeekPose.PERCH); return; }
                break;
            case PeekPose.WITHDRAW:
                if (t >= PeekPose.WITHDRAW_MS) { peekEnterPhase(PeekPose.OFF); return; }
                break;
            case PeekPose.SETTLE:
                // 拖动松手后的回落：收敛到常驻位就转回"挂住"（呼吸/小探头重新起算）
                if (t >= PeekPose.SETTLE_MS) { peekEnterPhase(PeekPose.PERCH); return; }
                break;
            case PeekPose.LIFT:
            case PeekPose.PERCH:
            default:
                break;   // 停在原地等事件（点击 / 计时器 / 手势）
        }
        final boolean steady = (phase == PeekPose.PERCH);
        handler.postDelayed(peekFrame, steady ? PEEK_FRAME_IDLE_MS : PEEK_FRAME_MS);
    }

    /** 时间轴驱动：写变换，并由露出量算出窗口 x */
    private void applyPeekPose(PeekPose.Pose pose) {
        applyPeekVisual(pose);
        try {
            if (params == null) return;
            int x = PeekPose.peekedX(peekBaseX, peekBodyW, pose.keep, peekEdge);
            if (x != params.x) {
                params.x = x;
                applyRootLayout();
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 只写**渲染变换**（rotation / 缩放 / pivot / 横向呼吸），不碰窗口 x。
     *
     * <p>拖动跟手时用这个：那一阶段窗口 x 由手指决定，姿态只跟着 {@code keep} 变。
     */
    private void applyPeekVisual(PeekPose.Pose pose) {
        try {
            if (petImage == null) return;
            float base = peekBaseScale > 0f ? peekBaseScale : 1f;
            // 旋转轴必须落在**肩线**（纯类算的），不能放 View 中心 ——
            // 放中心是"整只被拧一下"，放肩线才是"从墙后探出身"
            petImage.setPivotX(peekBodyLeft + peekBodyW / 2f);
            petImage.setPivotY(PeekPose.shoulderPivotY(peekBodyTop, peekBodyH));
            petImage.setRotation(PeekPose.leanSigned(pose.lean, peekEdge));
            petImage.setScaleX(pose.scaleX * base);
            petImage.setScaleY(pose.scaleY * base);
            petImage.setTranslationX(dp(pose.breathDp));
            petImage.setAlpha(pose.alpha);
        } catch (Throwable ignore) {}
    }

    /**
     * 手指把挂边的桌宠抓住了：时间轴让位（停帧循环与定时器），改成"窗口跟手"。
     *
     * <p>★ 这一刻记下当前露出量：松手时据此决定"没拉够 ⇒ 自己挂回去"还是"拉够了 ⇒ 放出来"。
     */
    private void peekBeginDrag() {
        peekDragging = true;
        peekDownMoved = true;
        handler.removeCallbacks(peekFrame);
        handler.removeCallbacks(peekNudge);
        handler.removeCallbacks(peekHoldDone);
        peekCancelAnim();
        peekDragStartX = (params != null) ? params.x : peekBaseX;
        peekDragStartY = (params != null) ? params.y : 0;
        try {
            peekFromKeep = PeekPose.keepForX(peekBaseX, peekBodyW, peekDragStartX, peekEdge);
        } catch (Throwable ignore) {}
        android.util.Log.i("DSPPeek", "拖动接管 起点keep=" + peekFromKeep
                + " startX=" + peekDragStartX);
    }

    /**
     * 拖动一帧：窗口位置 = <b>起点 + 手指绝对位移</b>，露出量由窗口 x 反推，姿态跟着露出量走。
     *
     * <p>★ 用绝对位移而不是累加 dx：增量式一旦丢一帧 MOVE、或者系统发一次 CANCEL，
     * 后面的位移就会整体偏移（真机上表现成"拖起来跟不上/漂"）。
     *
     * <p>★ 横向范围：允许停在挂边位（甚至刚好多挂出去一点点），但**不许再往外挂**；
     * 内侧一直到正常可达范围。挂边位本身比"贴边位"更靠外，这里必须按挂边位算下界 ——
     * 否则第一帧就会被夹回贴边位，看起来像"拖不动"（这是真机上量出来的 bug）。
     */
    private void peekDragStep(float rawX, float rawY) {
        try {
            if (params == null || root == null) return;
            int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
            int slack = (int) (peekBodyW * (1f - PeekPose.PEER_KEEP)) + dp(2f);
            int minX, maxX;
            if (peekEdge == PeekPose.EDGE_LEFT) {
                minX = peekBaseX - slack;    // 挂边位（比贴边位再往外 slack）
                maxX = rx[1];
            } else {
                minX = rx[0];
                maxX = peekBaseX + slack;
            }
            int x = peekDragStartX + Math.round(rawX - peekDownRawX);
            if (x < minX) x = minX;
            if (x > maxX) x = maxX;
            int winH = root.getHeight();
            int y = peekDragStartY + Math.round(rawY - peekDownRawY);
            if (y < 0) y = 0;
            int maxY = Math.max(0, screenH() - winH);
            if (y > maxY) y = maxY;
            boolean moved = (x != params.x) || (y != params.y);
            params.x = x;
            params.y = y;
            float keep = PeekPose.keepForX(peekBaseX, peekBodyW, x, peekEdge);
            applyPeekVisual(PeekPose.dragPose(keep, peekTune));
            if (moved) {
                applyRootLayout();
                positionBalance();
                if (bubbleShowing) positionBubble();
                if (longPressShowing) positionLongPressBubble();
                if (actionShowing) positionActionBubble();
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 拖动松手：拉够了 ⇒ 退出挂边（就停在用户放下的地方）；没拉够 ⇒ 自己平滑挂回去。
     *
     * <p>阈值 0.985：身体基本全在屏内才算"拉出来"，否则一路小拖都会把桌宠弄成常态，
     * 与"挂边就该一拖就走"的手感不符。
     */
    private void peekEndDrag() {
        try {
            if (params == null) return;
            float keep = PeekPose.keepForX(peekBaseX, peekBodyW, params.x, peekEdge);
            Prefs.savePos(this, params.x, params.y);
            if (keep >= 0.985f) {
                peekEnterPhase(PeekPose.OFF);
                dockResetIdle();
                android.util.Log.i("DSPPeek", "拖出来了 keep=" + keep + " x=" + params.x);
            } else {
                peekFromKeep = keep;
                peekEnterPhase(PeekPose.SETTLE);
                android.util.Log.i("DSPPeek", "没拉够 回挂 keep=" + keep);
            }
        } catch (Throwable ignore) {}
    }

    /** 点一下：抬头站直看人，停 LIFT_HOLD_MS 后自己缩回挂边 */
    private void peekLift() {
        if (peekPhase == PeekPose.NUDGE) peekEnterPhase(PeekPose.PERCH);
        if (peekPhase != PeekPose.PERCH) return;
        peekEnterPhase(PeekPose.LIFT);
    }

    /** 收回去（从当前露出量起步，不跳变） */
    private void peekCancel() {
        if (peekPhase == PeekPose.OFF || peekPhase == PeekPose.WITHDRAW) return;
        peekFromKeep = PeekPose.at(peekPhase,
                System.currentTimeMillis() - peekPhaseStartMs, 0L, peekFromKeep, peekTune).keep;
        peekEnterPhase(PeekPose.WITHDRAW);
    }

    /** 立即收口（服务销毁 / 重建视图）：不播动画，直接把姿态复位 */
    private void peekAbort() {
        peekLoopOn = false;
        peekCancelAnim();
        handler.removeCallbacks(peekFrame);
        handler.removeCallbacks(peekNudge);
        handler.removeCallbacks(peekHoldDone);
        peekPhase = PeekPose.OFF;
        peekPerchStartMs = 0L;
        peekFromKeep = 1f;
        resetPeekPose();
    }

    /** 复位渲染变换（rotation / 缩放 / 位移 / alpha） */
    private void resetPeekPose() {
        try {
            if (petImage == null) return;
            float base = Prefs.getScale(this);
            petImage.setRotation(0f);
            petImage.setTranslationX(0f);
            petImage.setScaleX(base);
            petImage.setScaleY(base);
            petImage.setAlpha(1f);
            petImage.setPivotX(petImage.getWidth() / 2f);
            petImage.setPivotY(petImage.getHeight() / 2f);
        } catch (Throwable ignore) {}
    }

    private void snapToNearestEdge() {
        if (params == null || root == null) return;
        clampToScreen();
        refreshRealSize();
        // ★ 与拖动钳制/边缘热区/漫游共用同一套可达范围（ContentInset.xRange）
        // ★★ 轮次84：这里必须用**未放宽**的基础范围 —— 挂边期间 edgeRangeX() 会把范围
        //   左右各放宽一个身位（好让身体挂在屏外），拿它当吸附目标就会吸到"比真实边更外"，
        //   随后 peekBegin 一比就判"不在边上"⇒ 有概率挂不上。
        int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());
        int minX = rx[0];
        int maxX = rx[1];

        int curX = params.x;
        int center = (minX + maxX) / 2;
        final int targetX = (curX <= center) ? minX : maxX;
        final int startX = curX;
        final int fixedY = params.y;

        if (targetX == startX) {
            // ★★ 轮次㊽：**"已经在边上"这条早退也要播贴边动作**。
            //   实测真凶：用户拖到边缘松手时，窗口在拖动过程中就已被 `clampToScreen()`
            //   夹到 min/max 了 ⇒ 走到这里 `startX == targetX` ⇒ **直接 return**，
            //   而动作原来挂在 return 之后 ⇒ 用户最常走的"贴边"路径**一次都没播过动作**
            //   （这正是「贴边动作没有触发」；旧版即使只挂 dockCollapse 也不覆盖这条路）。
            playDockFeedback(startX, targetX, minX);
            // ★ 轮次77：已经在边上 ⇒ 挂上去探头（内部自带"开关/互斥/是否真在边上"三道门）
            peekBegin();
            applyRootLayout();
            Prefs.savePos(FloatingService.this, params.x, params.y);
            positionBalance();
            if (longPressShowing) positionLongPressBubble();
            if (actionShowing) positionActionBubble();
            return;
        }
        // ★★ 轮次㊽：**贴边动作（DOCK 场景）以前只挂在 `dockCollapse()` 上** ——
        //   而那要长按菜单手动点「贴边吸附」（自动贴边默认是关的：`dockIdleMs()` 恒 0）。
        //   用户平常"贴边"走的其实是**这条路**：拖到边缘松手吸附、以及漫游撞到边缘自动贴边
        //   —— 它只滑窗口、**从来不播动作** ⇒ 用户报「**贴边动作没有触发**」。
        playDockFeedback(startX, targetX, minX);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(startX, targetX);
        anim.setDuration(220);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (params == null || root == null) return;
                params.x = (Integer) a.getAnimatedValue();
                params.y = fixedY;
                try { applyRootLayout(); } catch (Exception ignore) {}
                positionBalance();
                if (longPressShowing) positionLongPressBubble();
                if (actionShowing) positionActionBubble();
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (params == null) return;
                params.x = targetX;
                params.y = fixedY;
                try { applyRootLayout(); } catch (Exception ignore) {}
                Prefs.savePos(FloatingService.this, params.x, params.y);
                positionBalance();
                if (longPressShowing) positionLongPressBubble();
                // ★ 轮次77：滑到边上落定 ⇒ 挂上去探头
                peekBegin();
            }
        });
        anim.start();
    }

    // ==================== 贴边吸附：吸附 / 展开 / 跟手 / 提示 ====================
    //
    // 与 snapToNearestEdge（拖完近边缘吸附）的区别：
    //   snap 只"滑到边上"，形象不变；这里多做三件事 ——
    //   ① 形象压扁+半透明+暂停动画（细长竖条观感，原 PetView 不重建）；
    //   ② 对话/输入/动作/长按浮层全部收起（吸附态只留桌宠本体）；
    //   ③ 余额气泡藏起（展开后按原 isVisible 恢复）。
    // 展开 = 逆操作：位置/alpha/scale/容器高全部从存档恢复，不重建视图。

    /** 长按菜单"贴边吸附"：就近选边，滑过去并切竖条形态。 */
    private void dockCollapse(boolean fromIdle) {
        if (params == null || root == null || petImage == null) return;
        // ★ 轮次77：贴边吸附现在有**两种形态**，由设置页「🪝 贴边与挂边」决定：
        //   形态=挂边露头 ⇒ 走探头（身体挂出屏外，只留头肩贴在边上）；
        //   形态=压扁竖条 ⇒ 下面这套老路径（压扁+半透明+暂停动画）。
        //   ★ 两个入口（长按菜单「贴边吸附」、拖动/漫游自动贴边）共用这一个分叉，
        //     不要各写一份 —— 老代码正是"贴边动作只挂在其中一条路上"才漏掉另一条的。
        //   ★ 放在 dockOn 闸门**之后**：关掉「贴边吸附」的用户不该被自动挂边。
        final boolean wasCollapsed = (dockState == PetDockState.COLLAPSED);
        boolean dockOn = true;
        try { dockOn = Prefs.dockEnabled(this); } catch (Throwable ignore) {}
        if (!dockOn && !fromIdle) return;
        if (peekOn()) {
            peekEnterFromDock();
            return;
        }
        hideLongPressBubble();
        if (actionShowing) hideActionBubble();
        if (askShowing) hideAskBar();
        if (bubbleShowing) { handler.removeCallbacks(hideBubbleTask); hideBubbleTask.run(); }
        try { hideVoiceWin(); } catch (Throwable ignore) {}
        // 存档当前形态（只存一次：已是 COLLAPSED 时不覆盖，避免存档被竖条污染）
        if (dockState != PetDockState.COLLAPSED) {
            dockSavedX = params.x;
            dockSavedY = params.y;
            try { dockSavedAlpha = petImage.getAlpha(); } catch (Throwable ignore) {}
            try { dockSavedScaleX = petImage.getScaleX(); } catch (Throwable ignore) {}
            try { dockSavedScaleY = petImage.getScaleY(); } catch (Throwable ignore) {}
            try { dockSavedContH = (container != null) ? container.getHeight() : 0; }
            catch (Throwable ignore) {}
            dockSavedValid = true;
        }
        // 左右自动判断：窗口中心在左半屏 → 贴左，否则贴右
        refreshRealSize();
        int sw = screenW();
        int winW = root.getWidth() > 0 ? root.getWidth()
                : (int) (getResources().getDisplayMetrics().density
                    * BASE_PET_W * Prefs.getScale(this));
        int centerX = params.x + winW / 2;
        dockEdge = (centerX < sw / 2) ? PetDockState.Edge.LEFT : PetDockState.Edge.RIGHT;
        final int toX = dockEdgeX(dockEdge);
        final int fixedY = params.y;
        final int fromX = params.x;
        dockCancelAnim();
        dockState = PetDockState.COLLAPSED; // 先切态：动画帧与触摸立刻按吸附态处理
        roamAfterExpand = false;            // ★ 轮次70：收起来时作废"展开后漫游"那个待办
        stopWander();
        // ★ 轮次㊱：贴边场景（dock）此前**全工程零引用** —— 用户报「贴边」这个动作选项
        //   无法触发。放在 stopWander() **之后**：它会 setScene(IDLE)，先播就被它盖掉了。
        if (!wasCollapsed) playSceneOnceSafe(SceneRegistry.DOCK, 1200);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (params == null || root == null) return;
                float t = (Float) a.getAnimatedValue();
                params.x = fromX + (int) ((toX - fromX) * t);
                params.y = fixedY;
                try { applyRootLayout(); } catch (Throwable ignore) {}
                positionBalance();
                // 形象同步压扁+淡出（只改 scale/alpha，GPU 合成，60fps 友好）
                dockApplyCollapsedMix(t);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (params == null) return;
                params.x = toX;
                try { applyRootLayout(); } catch (Exception ignore) {}
                Prefs.savePos(FloatingService.this, params.x, params.y);
                // 余额气泡吸附态藏起（展开后按 isVisible 恢复）
                if (balRoot != null) balRoot.setVisibility(View.GONE);
                // 动画停掉：呼吸位移动画 + 动画包轮播 + WebM（如有）
                try { if (petImage != null) petImage.clearAnimation(); } catch (Throwable ignore) {}
                // ★ 贴边停渲染：PetView 全路径暂停绘制（呼吸/眨眼/尾巴/动画包/Live2D），
                //   竖条静态可见，待机功耗最低。展开/peek 时恢复。
                try { dockSetRenderPaused(true); } catch (Throwable ignore) {}
                // ★ 吸附位置竖条：此时窗口已贴边，摆好并显示（颜色跟随当前主题）
                try { dockShowStrip(); } catch (Throwable ignore) {}
                boolean hint = true;
                try { hint = Prefs.dockEdgeHint(FloatingService.this); } catch (Throwable ignore) {}
                if (hint) {
                    handler.removeCallbacks(dockEdgeHintRunnable);
                    handler.postDelayed(dockEdgeHintRunnable, 450);
                }
                dockResetIdle();
            }
        });
        dockAnim = anim;
        anim.start();
    }

    /** 长按菜单"展开 / 恢复" + 滑动达标展开：滑回存档位，恢复原始桌宠。 */
    private void dockExpand() {
        if (params == null || root == null || petImage == null) return;
        hideLongPressBubble();
        handler.removeCallbacks(dockEdgeHintRunnable);
        dockClearNudge();
        // ★ 先切态、再收槽位：dockHideStrip() 内部会调 monitorRender()，
        //   而它按 dockState 决定"监测条该以哪种形态出现"。顺序反了的话
        //   它还以为在吸附态 → 又把监测条按**竖条形态**摆回来，
        //   于是展开过程中槽位上一直挂着一条（用户报的"形态异常"）。
        dockState = PetDockState.EXPANDED;
        // 展开即收竖条（窗口滑出过程中不再需要位置指示）
        dockHideStrip();
        dockCancelAnim();
        dockSwiping = false;
        dockSwipeActive = false;
        dockSwipeProgress = 0f;
        // 目标：存档位（无效/贴边太近则用规范屏内位，保证一定露出来）
        final int toX = dockLandingX();
        final int toY = dockSavedValid ? dockSavedY : params.y;
        final int fromX = params.x;
        final int fromY = params.y;
        final float fromAlpha = petImage.getAlpha();
        final float fromSX = petImage.getScaleX();
        final float fromSY = petImage.getScaleY();
        float bs = 1f;
        try { bs = Prefs.getScale(this); } catch (Throwable ignore) {}
        final float baseScale = bs;
        final float toAlpha = (dockSavedValid && dockSavedAlpha > 0) ? dockSavedAlpha
                : Prefs.getAlpha(this);
        android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(260);
        anim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (params == null || root == null || petImage == null) return;
                float t = (Float) a.getAnimatedValue();
                params.x = fromX + (int) ((toX - fromX) * t);
                params.y = fromY + (int) ((toY - fromY) * t);
                try { applyRootLayout(); } catch (Throwable ignore) {}
                positionBalance();
                // 形象从压扁态插值回存档形态
                dockApplyExpandMix(t, fromAlpha, fromSX, fromSY, toAlpha, baseScale);
            }
        });
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (params == null) return;
                params.x = toX;
                params.y = toY;
                clampToScreen();
                try { applyRootLayout(); } catch (Exception ignore) {}
                Prefs.savePos(FloatingService.this, params.x, params.y);
                // 形态彻底归位：alpha/scale 回存档，容器高回存档
                try {
                    if (petImage != null) {
                        petImage.setAlpha(toAlpha);
                        petImage.setScaleX(dockSavedValid ? dockSavedScaleX : baseScale);
                        petImage.setScaleY(dockSavedValid ? dockSavedScaleY : baseScale);
                        if (container != null && dockSavedContH > 0) {
                            ViewGroup.LayoutParams clp = container.getLayoutParams();
                            clp.height = dockSavedContH;
                            container.setLayoutParams(clp);
                        }
                    }
                } catch (Throwable ignore) {}
                if (balRoot != null) balRoot.setVisibility(isVisible ? View.VISIBLE : View.GONE);
                positionBalance();
                // ★ 侧栏监测条跟着桌宠走：展开动画期间窗口一直在滑，
                //   这里补一次定位，否则它会停在"吸附时那个位置"等下一次巡检（最多 5 秒）。
                positionMonitorSide();
                // ★ 展开恢复渲染（停掉的呼吸/动图/Live2D 全部回来），再重启呼吸位移
                try { dockSetRenderPaused(false); } catch (Throwable ignore) {}
                try { if (petImage != null) startBreathing(); } catch (Throwable ignore) {}
                dockResetIdle();
                // ★★ 轮次70：竖条态点的「主动漫游」在这里续上 —— 展开完成、位置已在屏内，
                //   现在才开始漫游（"在任何位置都可以漫游"，且不从别处挪过来）
                if (roamAfterExpand) {
                    roamAfterExpand = false;
                    roamOnce(true);
                }
            }
        });
        dockAnim = anim;
        anim.start();
    }

    /** 跟手：窗口按进度向内滑 + 形象同步回弹（progress 0=吸附 1=全展开）。 */
    private void dockApplySwipe(float progress) {
        if (params == null || root == null || petImage == null) return;
        float p = Math.max(0f, Math.min(1f, progress));
        int baseX = dockEdgeX(dockEdge);
        int targetX = dockLandingX();
        params.x = baseX + (int) ((targetX - baseX) * p);
        // 方向正确性兜底：向内的位移必须远离边缘
        if (dockEdge == PetDockState.Edge.LEFT && params.x < baseX) params.x = baseX;
        if (dockEdge == PetDockState.Edge.RIGHT && params.x > baseX) params.x = baseX;
        try { applyRootLayout(); } catch (Throwable ignore) {}
        float bs = 1f, savedA = 1f;
        try { bs = Prefs.getScale(this); } catch (Throwable ignore) {}
        try { savedA = Prefs.getAlpha(this); } catch (Throwable ignore) {}
        float toAlpha = (dockSavedValid && dockSavedAlpha > 0) ? dockSavedAlpha : savedA;
        // 吸附态形象 = 压扁 0.35 宽 + 半透明；按 p 插值回正常
        float flatSX = (dockSavedValid ? dockSavedScaleX : bs) * 0.35f;
        float flatSY = (dockSavedValid ? dockSavedScaleY : bs) * 0.9f;
        float flatA = Math.max(0.25f, toAlpha * 0.55f);
        float curSX = flatSX + ((dockSavedValid ? dockSavedScaleX : bs) - flatSX) * p;
        float curSY = flatSY + ((dockSavedValid ? dockSavedScaleY : bs) - flatSY) * p;
        float curA = flatA + (toAlpha - flatA) * p;
        try {
            petImage.setScaleX(curSX);
            petImage.setScaleY(curSY);
            petImage.setAlpha(curA);
        } catch (Throwable ignore) {}
        // 跟手过半竖条即收（形象已露大半，不再需要位置指示）；回落再显示
        //
        // ★★ 必须走 dockSlotShow，**不能只动 dockStrip**（真机 bug：
        //   "长按拖出吸附竖条、不松手再拉回，竖条会消失或形态异常"）。
        //   吸附槽位可能是**监测条**占着的（monitorTakeStripSlot 成功时
        //   dockStrip 本来就是 GONE）：
        //     · 拉过半时只 GONE 掉 dockStrip ⇒ 监测条还挂在槽位上（形态异常）；
        //     · 拉回来时又把 dockStrip 点亮 ⇒ 普通竖条与监测条**叠在同一槽位**。
        try { dockSlotShow(p <= 0.5f); } catch (Throwable ignore) {}
    }

    /**
     * 吸附槽位（普通竖条 / 监测条）的显隐 —— **唯一入口**。
     *
     * <p>为什么必须收敛成一个入口：这个槽位有两个可能的占用者
     * （{@code dockStrip} 与 {@code monitorBar}，后者在拿到数据时顶替前者），
     * 而显隐被四五处代码各自直接改 {@code dockStrip.setVisibility}。
     * 两套写法一混，就会出现"该显示的没显示、不该显示的还挂着、
     * 甚至两条叠在一起"——用户报的"拖出去再拉回来竖条消失/形态异常"就是它。
     *
     * @param show true=显示（按当前占用者），false=两个都收掉
     */
    private void dockSlotShow(boolean show) {
        if (!show) {
            if (dockStrip != null) dockStrip.setVisibility(View.GONE);
            if (monitorBar != null) monitorBar.setVisibility(View.GONE);
            return;
        }
        if (dockSlotOwnerIsMonitor && monitorBar != null) {
            monitorBar.setVisibility(View.VISIBLE);
            monitorBar.bringToFront();
        } else if (dockStrip != null) {
            dockStrip.setVisibility(View.VISIBLE);
        }
    }

    /** 吸附动画帧：形象从正常 → 压扁竖条（t 0→1）。 */
    private void dockApplyCollapsedMix(float t) {
        if (petImage == null) return;
        float bs = 1f, savedA = 1f;
        try { bs = Prefs.getScale(this); } catch (Throwable ignore) {}
        try { savedA = Prefs.getAlpha(this); } catch (Throwable ignore) {}
        float fromSX = (dockSavedValid ? dockSavedScaleX : bs);
        float fromSY = (dockSavedValid ? dockSavedScaleY : bs);
        float fromA = (dockSavedValid && dockSavedAlpha > 0) ? dockSavedAlpha : savedA;
        float flatA = Math.max(0.25f, fromA * 0.55f);
        try {
            petImage.setScaleX(fromSX + (fromSX * 0.35f - fromSX) * t);
            petImage.setScaleY(fromSY + (fromSY * 0.9f - fromSY) * t);
            petImage.setAlpha(fromA + (flatA - fromA) * t);
        } catch (Throwable ignore) {}
    }

    /** 展开动画帧：形象从压扁态 → 存档形态（t 0→1）。 */
    private void dockApplyExpandMix(float t, float fromAlpha, float fromSX, float fromSY,
                                    float toAlpha, float baseScale) {
        if (petImage == null) return;
        float toSX = (dockSavedValid ? dockSavedScaleX : baseScale);
        float toSY = (dockSavedValid ? dockSavedScaleY : baseScale);
        try {
            petImage.setScaleX(fromSX + (toSX - fromSX) * t);
            petImage.setScaleY(fromSY + (toSY - fromSY) * t);
            petImage.setAlpha(fromAlpha + (toAlpha - fromAlpha) * t);
        } catch (Throwable ignore) {}
    }

    /** 未达标回弹：滑回边缘 + 形象回到竖条。 */
    private void dockSnapBack() {
        if (params == null || root == null) return;
        // ★ 先取消一切并行的 x 动画（提示/悬停）：否则两个动画同时写
        //   params.x，落点随机，表现为"点着点着竖条不见了/飘走了"。
        dockCancelAnim();
        final int baseX = dockEdgeX(dockEdge);
        android.animation.ValueAnimator back =
                android.animation.ValueAnimator.ofFloat(dockSwipeProgress, 0f);
        back.setDuration(200);
        back.setInterpolator(new android.view.animation.DecelerateInterpolator());
        back.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float p = (Float) a.getAnimatedValue();
                dockSwipeProgress = p;
                // 跟手反演：dockApplySwipe 内部按 p 算窗口 x + 形象（带方向兜底）
                dockApplySwipe(p);
            }
        });
        back.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (params == null) return;
                params.x = baseX;
                try { applyRootLayout(); } catch (Throwable ignore) {}
                dockApplyCollapsedMix(1f);
                // 回弹落定：渲染重新暂停（悬停超时回来同样停），竖条重显
                try { dockClearNudge(); } catch (Throwable ignore) {}
                try { dockSetRenderPaused(true); } catch (Throwable ignore) {}
                try { dockShowStrip(); } catch (Throwable ignore) {}
                dockSwipeProgress = 0f;
                dockResetIdle();
            }
        });
        back.start();
    }

    /**
     * 展开落点 x：优先用存档位；存档位若离吸附边太近（几乎还在屏外，
     * 或落在屏幕另一侧），就换成规范屏内位 {@link #dockPeekedX}，
     * 保证"拉出来一定看得见" —— 这是双击悬停/展开都不会"消失"的兜底。
     */
    private int dockLandingX() {
        int baseX = dockEdgeX(dockEdge);
        if (!dockSavedValid) return dockPeekedX(dockEdge);
        int winW = (root != null && root.getWidth() > 0) ? root.getWidth()
                : (int) (getResources().getDisplayMetrics().density
                    * BASE_PET_W * Prefs.getScale(this));
        int minInside = (int) (winW * 0.5f);          // 至少半个身位落在屏内
        if (dockEdge == PetDockState.Edge.LEFT) {
            int need = baseX + minInside;
            if (dockSavedX < need) return Math.max(dockPeekedX(dockEdge), need);
        } else {
            int need = baseX - minInside;
            if (dockSavedX > need) return Math.min(dockPeekedX(dockEdge), need);
        }
        return dockSavedX;
    }

    /** 吸附后窗口 x：左贴边藏到只剩细条，右贴边同理。 */
    private int dockEdgeX(PetDockState.Edge e) {
        refreshRealSize();
        int sw = screenW();
        int winW = (root != null && root.getWidth() > 0) ? root.getWidth()
                : (int) (getResources().getDisplayMetrics().density
                    * BASE_PET_W * Prefs.getScale(this));
        int peek = dockPeekPx();
        if (e == PetDockState.Edge.LEFT) return -(winW - peek);
        return sw - peek;
    }

    /** 展开后窗口 x（无存档时的兜底）：从边缘向内一个身位。 */
    private int dockPeekedX(PetDockState.Edge e) {
        refreshRealSize();
        int sw = screenW();
        int winW = (root != null && root.getWidth() > 0) ? root.getWidth()
                : (int) (getResources().getDisplayMetrics().density
                    * BASE_PET_W * Prefs.getScale(this));
        int margin = dp(16);
        if (e == PetDockState.Edge.LEFT) return margin;
        int x = sw - winW - margin;
        return Math.max(0, x);
    }

    /**
     * 吸附位置竖条的主题色：主题 accent 实心胶囊（R_PILL 全圆角）。
     *
     * <p>为什么不用 modal 底 + accent 描边：竖条只有 ~14dp 宽，
     * UiKit.raised/EmbossDrawable 的浮雕余量（inset + 阴影）会把本体吃掉大半，
     * 实测贴边后只剩一条淡淡的灰线 —— 用户要的是"一眼能看到吸附位置"。
     * accent 实心在四套主题（新拟态·浅/深 × 玻璃·浅/深）下都有高对比值，
     * 每次显示时重建 Drawable，用户换皮肤后下一次贴边即跟随（长按菜单同款处理）。
     */
    private android.graphics.drawable.Drawable dockStripBg() {
        Tokens.Set t = uiT();
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(t.accent);
        g.setCornerRadius(999 * d);
        return g;
    }

    /**
     * 竖条摆位 + 显示：贴在窗口靠屏内一侧的竖直中点。
     *
     * <p>左贴边 → 竖条在窗口右侧（露出的细条上）；右贴边 → 竖条在窗口左侧。
     * 尺寸：宽 ~14dp、高为窗口高的 60%（clamp 72dp~180dp），实心胶囊，
     * 在深色桌面上也有足够对比。竖条是 container 的子 View，随窗口走，
     * 不需要独立悬浮窗；clickable=false，触摸透给 container（滑入/长按照常）。
     */
    private void dockShowStrip() {
        if (dockStrip == null || container == null) return;
        // ★ 游戏助手监测条优先顶替竖条（用户要求：收起时代替吸附的竖条）。
        //   顶替成功就不画普通竖条，否则照旧。
        //   ★ 同时记下"这个槽位现在归谁"—— 跟手收放（dockSlotShow）要按它决定显隐谁。
        try {
            if (monitorTakeStripSlot()) {
                dockSlotOwnerIsMonitor = true;
                dockStrip.setVisibility(View.GONE);
                return;
            }
        } catch (Throwable ignore) {}
        dockSlotOwnerIsMonitor = false;
        float d = getResources().getDisplayMetrics().density;
        int winW = (root != null && root.getWidth() > 0) ? root.getWidth()
                : (int) (d * BASE_PET_W * Prefs.getScale(this));
        int winH = (root != null && root.getHeight() > 0) ? root.getHeight()
                : (int) (d * BASE_PET_H * Prefs.getScale(this));
        int stripW = (int) (d * 14 + 0.5f);
        int stripH = (int) (winH * 0.60f);
        int minH = (int) (d * 72 + 0.5f);
        int maxH = (int) (d * 180 + 0.5f);
        if (stripH < minH) stripH = minH;
        if (stripH > maxH) stripH = maxH;
        dockStrip.setBackground(dockStripBg());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(stripW, stripH);
        // 靠左贴边时竖条靠右（gravity RIGHT），靠右贴边时竖条靠左
        lp.gravity = Gravity.CENTER_VERTICAL
                | ((dockEdge == PetDockState.Edge.LEFT) ? Gravity.RIGHT : Gravity.LEFT);
        int margin = (int) (d * 6 + 0.5f);
        if (dockEdge == PetDockState.Edge.LEFT) lp.rightMargin = margin;
        else lp.leftMargin = margin;
        dockStrip.setLayoutParams(lp);
        dockStrip.setVisibility(View.VISIBLE);
        dockStrip.bringToFront();
    }

    /** 竖条隐藏（展开 / 重建 / 销毁时调用）。 */
    private void dockHideStrip() {
        // 槽位两个占用者都收掉：只收 dockStrip 会把监测条留在槽位上
        // （展开后它还挂在原位 —— 用户报的"形态异常"）
        dockSlotShow(false);
        // ★ 展开后监测条要换成"挂桌宠侧面"的形态 —— 不能跟着竖条一起消失，
        //   否则展开后读数就没了（用户要的是"收起时顶替竖条"，不是"只在收起时才有"）。
        try { monitorRender(); } catch (Throwable ignore) {}
    }

    /**
     * 吸附槽位归谁 —— **双向交接**，只认这一个入口。
     *
     * <p>★ 用户报障（2026-10-07）：**吸附态下从长按菜单点「隐藏监测条」，整条竖条
     * 直接消失**。原因：那一刻槽位归监测条（{@code dockStrip} 早就是 GONE），
     * 而"隐藏"只把监测条收掉了，**没有任何人把普通竖条放回来** —— 槽位就空了。
     *
     * <p>反方向同样有问题：从未有数据时先画了普通竖条，之后数据到了、监测条要顶替，
     * 却没人收掉普通竖条 ⇒ 同一个槽位叠着两条（一会看得见一会看不见）。
     *
     * <p>所以两个方向都收敛到这里：{@code monitorWants} = 监测条是否要以竖条形态占位。
     * 只在吸附态生效 —— 展开态槽位本来就不显示（由 {@code dockHideStrip} 收着）。
     */
    private void applyStripSlotOwner(boolean monitorWants) {
        if (container == null) return;
        if (dockState != PetDockState.COLLAPSED) return;
        if (monitorWants == dockSlotOwnerIsMonitor) return;   // 没变化，别白折腾
        if (monitorWants) {
            dockSlotOwnerIsMonitor = true;
            if (dockStrip != null) dockStrip.setVisibility(View.GONE);
            return;
        }
        dockSlotOwnerIsMonitor = false;
        // 交还给普通竖条（关掉监测条时用户要看到的正是它）。
        // dockShowStrip 内部会再问一次"监测条要不要顶替"，但那时判据已经是最新的
        // （门控关着 ⇒ 不会再要），所以不会递归。
        dockShowStrip();
    }

    // ==================== 游戏助手 · 系统监测条 ====================

    /** 吸附态竖条里的一格（只有数值）。 */
    private TextView newMonitorCell() {
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(9f);
        return tv;
    }

    /** 展开态侧栏的一颗胶囊（带指标名，比竖条格宽）。 */
    private TextView newMonitorSideChip() {
        float d = getResources().getDisplayMetrics().density;
        TextView tv = new TextView(this);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        tv.setSingleLine(true);
        tv.setClickable(false);
        tv.setFocusable(false);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(10f);
        int ph = (int) (d * 8 + 0.5f);
        int pv = (int) (d * 3 + 0.5f);
        tv.setPadding(ph, pv, ph, pv);
        return tv;
    }

    /**
     * 监测条开关：现在**该不该显示**。
     *
     * <p>判据本体在 {@link SysMetrics#monitorVisible}（纯 Java，可离线穷举断言）：
     * <pre>
     *   游戏助手总开关 ∧ 监测条显示开关 ∧ （范围=所有应用 ∨ 前台包名在名单里）
     * </pre>
     *
     * <p>★ 范围有两档，用户 2026-10-08 要求把两者**集成在一起**（设置页同一行二选）：
     * <ul>
     *   <li>默认「所有应用」= 任何应用前台都显示（就是轮次⑦ 之后的行为）；</li>
     *   <li>「仅选定的应用」= 只在勾选过的应用里显示（名单为空 ⇒ 哪都不显示）。</li>
     * </ul>
     * 前台包名取 {@code monitorPkg}（采样回调里写的）—— 还没采到样时它是 null，
     * 此时"部分应用"模式下**不显示**（不知道是谁就不猜，见 monitorVisible 的注释）。
     */
    private boolean monitorGateOpen() {
        try {
            return SysMetrics.monitorVisible(
                    Prefs.gameAssistant(this),
                    Prefs.monitorBar(this),
                    Prefs.monitorOnlySelected(this),
                    monitorPkg,
                    Prefs.monitorApps(this));
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 三个槽位的指标 id（0=上 1=中 2=下）。 */
    private String[] monitorSlots() {
        return new String[]{
                Prefs.monitorSlot(this, 0),
                Prefs.monitorSlot(this, 1),
                Prefs.monitorSlot(this, 2)};
    }

    /**
     * 按开关 / Shizuku 就绪状态启停采样，并同步监测条可见性。
     *
     * <p>采样生命周期由**本服务**持有（设置页那边各自 acquire/release），
     * 所以这里要成对调用 {@link GameFpsMonitor#acquire} /
     * {@link GameFpsMonitor#release}，用 {@code fpsAcquired} 防重复。
     *
     * ★ 采样与显示**分开判**：采样只要"总开关 + Shizuku 就绪"就跑
     * （不然拿不到前台包名，就没法判断该不该显示）；显示另由
     * {@link #monitorGateOpen()} 决定。
     */
    private void monitorSync() {
        if (monitorBar == null) return;
        boolean want = false, bar = true, ready = false, onlySel = false;
        try { want = Prefs.gameAssistant(this); } catch (Throwable ignore) {}
        try { bar = Prefs.monitorBar(this); } catch (Throwable ignore) {}
        try { onlySel = Prefs.monitorOnlySelected(this); } catch (Throwable ignore) {}
        try { ready = ShizukuBridge.state() == ShizukuBridge.State.READY; }
        catch (Throwable ignore) {}
        // 悬浮窗不在 uiautomator dump 里，真机验证只能靠日志（见 STATE.md 的验证纪律）
        android.util.Log.i("DSPFpsBadge", "sync want=" + want + " bar=" + bar
                + " ready=" + ready + " acquired=" + fpsAcquired
                + " ready2=" + monitorReady + " show=" + monitorGateOpen()
                + " pkg=" + monitorPkg
                + " sel=" + onlySel + " inList=" + Prefs.isMonitorApp(this, monitorPkg)
                + " apps=" + Prefs.monitorAppCount(this)
                + " dock=" + dockState
                + " fps=" + monitorSnap.fps + " cpu=" + monitorSnap.cpu
                + " gpu=" + monitorSnap.gpu + " ramKb=" + monitorSnap.ramUsedKb
                + " temp=" + monitorSnap.temp + " batt=" + monitorSnap.batt
                + " pw=" + monitorSnap.power + " net=" + monitorSnap.net
                + " monRun=" + GameFpsMonitor.isRunning()
                + " refs=" + GameFpsMonitor.holderCount()
                + " tickAge=" + GameFpsMonitor.millisSinceLastTick()
                + " sampAge=" + GameFpsMonitor.millisSinceLastSample());

        // ★★ 自愈①：我们还持有引用，采样却已经停了 —— 说明账被别的调用方
        //    抵消掉了（真机 bug：Activity 无条件 release 两次）。
        //    这里当场补一次 acquire，让监测条不必等用户"点开游戏助手"才恢复。
        if (fpsAcquired && want && ready && !GameFpsMonitor.isRunning()) {
            android.util.Log.w("DSPFpsBadge", "采样已停但本服务仍持有引用 → 自动重启");
            GameFpsMonitor.acquire(this);
        }
        // ★★ 自愈②：线程还在，但**长时间跑不完一轮** = 卡在阻塞的 shell 读取上
        //    （exec 没有超时时会永久停在那里，既无回调也无异常）。
        //    重启线程（保留引用计数），别让监测条的数字冻住。
        long tickAge = GameFpsMonitor.millisSinceLastTick();
        if (fpsAcquired && want && ready && GameFpsMonitor.isRunning()
                && tickAge > FPS_TICK_STALE_MS) {
            android.util.Log.w("DSPFpsBadge", "采样线程卡住 " + tickAge + "ms → 重启");
            GameFpsMonitor.restart(this);
        }

        if (want && ready) {
            if (!fpsAcquired) {
                fpsAcquired = true;
                GameFpsMonitor.acquire(this);
            }
            if (fpsBadgeCb == null) {
                fpsBadgeCb = new GameFpsMonitor.Callback() {
                    @Override public void onSample(String pkg, String appName, float fps,
                                                   float refreshHz, String layer,
                                                   SysMetrics.Snapshot m) {
                        // ★ 拿到一次采样就算"就绪"—— 静止也是有效读数（帧数没涨而已）。
                        //   第一版要求 fps>=0 才就绪，于是静态应用上永远显示 "--"，
                        //   吸附态也因此一直退回普通竖条（monitorTakeStripSlot 要求就绪）。
                        monitorPkg = pkg;
                        if (m != null) monitorSnap = m;
                        monitorReady = true;
                        monitorRender();
                    }
                    @Override public void onUnavailable(String reason) {
                        // 读不到（Shizuku 未就绪 / 没前台应用）：**短时间**保留上一次的
                        // 数字（避免一秒的抖动就闪成 "--"）。
                        // ★ 但"保留"是有期限的：真正过期后由 monitorFresh() 判死，
                        //   显示 "--" —— 一组不动的旧数字比占位符更误导。
                        monitorRender();
                    }
                };
                GameFpsMonitor.addListener(fpsBadgeCb);
            }
        } else {
            if (fpsAcquired) {
                fpsAcquired = false;
                if (fpsBadgeCb != null) {
                    GameFpsMonitor.removeListener(fpsBadgeCb);
                    fpsBadgeCb = null;
                }
                GameFpsMonitor.release();
            }
        }
        monitorRender();
        // ★ 侧栏是独立窗口：`addView` 之后第一次量尺寸可能还是 0
        //   （positionMonitorSide 会退化成估算宽高）。巡检顺手再定位一次，
        //   保证它最终真的贴在桌宠旁边，而不是按估算值错开几十像素。
        if (monitorSideShowing) positionMonitorSide();
    }

    /** 监测条胶囊背景：主题 accent 的胶囊（略透，桌宠从底下透出一点，像挂牌而非贴纸）。 */
    private android.graphics.drawable.Drawable monitorPillBg() {
        Tokens.Set t = uiT();
        float d = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        // 0xE0 ≈ 88% 不透明：足够看清数字，又不至于糊住桌宠
        g.setColor((t.accent & 0x00FFFFFF) | 0xE0000000);
        g.setCornerRadius(999 * d);
        return g;
    }

    /**
     * 手上这组数字还算不算数（读数新鲜）。
     *
     * <p>★ 为什么不能只看 {@code monitorReady}：它一旦置真就**再也不会变假**，
     * 于是采样停掉/卡住时监测条会一直显示那组旧数字 —— 用户看到的正是
     * "卡住不动"。这里额外要求"最近 5 秒内真的采到过"，
     * 采样一停就退回 {@code --}，故障可见而不是伪装成正常读数。
     */
    private boolean monitorFresh() {
        if (!monitorReady) return false;
        long age = GameFpsMonitor.millisSinceLastSample();
        return age >= 0 && age <= FPS_BADGE_STALE_MS;
    }

    /**
     * 渲染监测条。吸附态用 container 里的三格竖条顶替 dockStrip，
     * 其余态用独立悬浮窗挂在桌宠侧面。
     *
     * <p>★ "该不该显示、以哪种形态显示"全部走 {@link SysMetrics#barForm}
     * —— 那是**纯判据**，桌面 JVM 上喂四种条件组合断言（见 {@code build/fpstest}）。
     * 原先这段判断散在这里的三四个 if 里，只能靠正则查"有没有这句话"。
     */
    private void monitorRender() {
        if (monitorBar == null || container == null) return;
        final String[] ids = monitorSlots();
        boolean anySlot = false;
        for (String id : ids) {
            if (!SysMetrics.NONE.equals(id)) { anySlot = true; break; }
        }
        final boolean hasMid = !SysMetrics.NONE.equals(ids[1]);
        final int form = SysMetrics.barForm(monitorGateOpen(), monitorReady, petTempHidden,
                dockState == PetDockState.COLLAPSED, hasMid, anySlot);
        if (form == SysMetrics.BAR_NONE) {
            monitorBar.setVisibility(View.GONE);
            monitorHideSide();
            // ★★ 槽位交还（用户报障：吸附态点「隐藏监测条」→ 竖条直接消失）。
            //    走 applyStripSlotOwner 而不是直接点 dockStrip：这里要知道
            //    "槽位原本归谁"，才能决定要不要把普通竖条放回来。
            applyStripSlotOwner(false);
            return;
        }
        final boolean fresh = monitorFresh();
        final float d = getResources().getDisplayMetrics().density;

        if (form == SysMetrics.BAR_STRIP) {
            // ★ 吸附贴边的竖条**只显示中格那一项**（用户 2026-10-07 修订）：
            //   三个数字竖着挤进去既看不清、也不像"细条"了。
            //   另外两格收起来，等展开成悬浮窗时再一起显示（见 monitorSideShow）。
            monitorHideSide();
            TextView[] cells = new TextView[]{monitorCellTop, monitorCellMid, monitorCellBottom};
            for (int i = 0; i < 3; i++) {
                if (cells[i] == null) continue;
                cells[i].setVisibility(i == 1 ? View.VISIBLE : View.GONE);
            }
            // ★ 过期就显示占位符，而不是继续挂着旧数字（用户反馈的"卡住不动"）
            String midText = monitorMidText();
            if (monitorCellMid != null) {
                monitorCellMid.setText(midText);
                monitorCellMid.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (int) (d * 22 + 0.5f)));
            }
            monitorBar.setBackground(monitorPillBg());
            // ★★ 宽度**按内容给**（用户报障："吸附贴边时候数据显示不完整"
            //    "打开性能监测的时候增大显示面积"）：写死 20dp 时 `5.2G`/`100%`
            //    这种四五个字符的中格文本会被 setSingleLine 硬截掉一半。
            //    判据本体是纯函数 SysMetrics.stripWidthDp（桌面 JVM 可断言）。
            int w = (int) (d * stripWidthDpFor(midText) + 0.5f);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    w, FrameLayout.LayoutParams.WRAP_CONTENT);
            // 靠左贴边时竖条靠右（gravity RIGHT），靠右贴边时竖条靠左
            lp.gravity = Gravity.CENTER_VERTICAL
                    | ((dockEdge == PetDockState.Edge.LEFT) ? Gravity.RIGHT : Gravity.LEFT);
            int margin = (int) (d * 6 + 0.5f);
            if (dockEdge == PetDockState.Edge.LEFT) lp.rightMargin = margin;
            else lp.leftMargin = margin;
            monitorBar.setLayoutParams(lp);
            monitorBar.setVisibility(View.VISIBLE);
            monitorBar.bringToFront();
            // ★ 反向交接：监测条接管槽位时把普通竖条收掉（否则同一槽位叠两条）
            applyStripSlotOwner(true);
            logMonitorBox("strip");
        } else {
            monitorBar.setVisibility(View.GONE);
            monitorSideShow(fresh, d, ids);
        }
    }

    /**
     * 竖条宽度（dp）：拿中格文本的**实测像素宽度**去问纯函数。
     *
     * <p>{@code monitorCellMid} 此刻还没测量（layout 未发生），所以只能用
     * {@code Paint.measureText} —— 它量的是"这段字在当前字号下要多宽"，
     * 与布局无关，正是这里需要的量。
     */
    private float stripWidthDpFor(String midText) {
        try {
            if (monitorCellMid != null && midText != null && midText.length() > 0) {
                float px = monitorCellMid.getPaint().measureText(midText);
                return SysMetrics.stripWidthDp(px, getResources().getDisplayMetrics().density);
            }
        } catch (Throwable ignore) {}
        return SysMetrics.STRIP_MIN_W_DP;
    }

    /** 把监测条的屏幕坐标打进日志（悬浮窗不在 uiautomator dump 里，验证只能靠坐标取样）。 */
    private void logMonitorBox(final String which) {
        View v = "strip".equals(which) ? monitorBar : monitorSideRoot;
        if (v == null) return;
        boolean strip = "strip".equals(which);
        android.util.Log.i("DSPFpsBadge", "render " + which
                + " top='" + textOf(strip ? monitorCellTop : monitorSideTop)
                + "' mid='" + textOf(strip ? monitorCellMid : monitorSideMid)
                + "' bottom='" + textOf(strip ? monitorCellBottom : monitorSideBottom)
                + "' vis=" + v.getVisibility()
                + " w=" + v.getWidth() + " h=" + v.getHeight());
        v.post(new Runnable() {
            @Override public void run() {
                try {
                    View vv = "strip".equals(which) ? monitorBar : monitorSideRoot;
                    if (vv == null) return;
                    int[] loc = new int[2];
                    vv.getLocationOnScreen(loc);
                    android.util.Log.i("DSPFpsBadge", "onscreen " + which
                            + " x=" + loc[0] + " y=" + loc[1]
                            + " w=" + vv.getWidth() + " h=" + vv.getHeight()
                            + " vis=" + vv.getVisibility()
                            + " rootX=" + (params == null ? "?" : params.x)
                            + " rootY=" + (params == null ? "?" : params.y)
                            + " rootW=" + (root == null ? -1 : root.getWidth())
                            + " rootH=" + (root == null ? -1 : root.getHeight()));
                } catch (Throwable ignore) {}
            }
        });
    }

    private String textOf(TextView tv) {
        return tv == null ? "-" : String.valueOf(tv.getText());
    }

    /**
     * 展开态：把三格挂到桌宠**左或右**侧（贴哪边就显示在另一边，不挡手势）。
     *
     * <p>用独立悬浮窗而不是 container 子 View —— 见本段开头的说明
     * （加宽桌宠窗口会牵动 clampToScreen / 七个浮层的相对定位）。
     */
    private void monitorSideShow(boolean fresh, float d, String[] ids) {
        if (monitorSideRoot == null) {
            monitorSideRoot = new LinearLayout(this);
            monitorSideRoot.setOrientation(LinearLayout.VERTICAL);
            monitorSideRoot.setClickable(false);
            monitorSideRoot.setFocusable(false);
            monitorSideTop = newMonitorSideChip();
            monitorSideMid = newMonitorSideChip();
            monitorSideBottom = newMonitorSideChip();
            monitorSideRoot.addView(monitorSideTop);
            monitorSideRoot.addView(monitorSideMid);
            monitorSideRoot.addView(monitorSideBottom);
        }
        TextView[] chips = new TextView[]{monitorSideTop, monitorSideMid, monitorSideBottom};
        int gapH = (int) (d * 3 + 0.5f);
        boolean any = false;
        for (int i = 0; i < 3; i++) {
            if (chips[i] == null) continue;
            boolean none = SysMetrics.NONE.equals(ids[i]);
            chips[i].setVisibility(none ? View.GONE : View.VISIBLE);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (i > 0) clp.topMargin = gapH;
            chips[i].setLayoutParams(clp);
            chips[i].setBackground(monitorPillBg());
            if (none) continue;
            any = true;
            chips[i].setText(fresh ? SysMetrics.fullText(ids[i], monitorSnap) : (SysMetrics.shortLabel(ids[i]) + " --"));
        }
        if (!any) {                       // 三项都设成"不显示"→ 整条收掉
            monitorHideSide();
            return;
        }
        if (monitorSideParams == null) {
            monitorSideParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            monitorSideParams.gravity = Gravity.TOP | Gravity.LEFT;
            applyCutoutMode(monitorSideParams);   // 与桌宠同坐标系，否则横屏会错位
        }
        if (!monitorSideShowing) {
            try {
                // 重新挂窗口前先作废"上次坐标"：否则新窗口可能沿用旧 params.x/y
                // 而 positionMonitorSide() 又因为"算出来和上次一样"跳过更新。
                lastMonSideX = Integer.MIN_VALUE;
                lastMonSideY = Integer.MIN_VALUE;
                windowManager.addView(monitorSideRoot, monitorSideParams);
                monitorSideShowing = true;
            } catch (Throwable t) {
                android.util.Log.w("DSPFpsBadge", "侧栏监测条添加失败: " + t);
                return;
            }
        }
        // 先测量真实尺寸，再按桌宠屏幕矩形算坐标
        monitorSideRoot.post(new Runnable() {
            @Override public void run() { positionMonitorSide(); }
        });
        logMonitorBox("side");
    }

    /**
     * 侧栏定位：桌宠偏左就挂右边、偏右就挂左边；垂直与桌宠居中，并夹在屏幕内。
     *
     * <p>★ 判据与夹紧全部走 {@link SysMetrics#sideBarX} / {@link SysMetrics#sideBarY}
     * —— 那两个是**纯函数**，能在桌面 JVM 上喂坐标断言（见 {@code build/fpstest}）。
     * 原先内联在这里时只能靠"源码里有没有这句话"的正则断言，那是存在性不是行为。
     */
    private void positionMonitorSide() {
        if (monitorSideRoot == null || monitorSideParams == null || !monitorSideShowing) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        refreshRealSize();
        int sw = screenW(), sh = screenH();
        int w = monitorSideRoot.getWidth();
        int h = monitorSideRoot.getHeight();
        if (w <= 0) w = (int) (dm.density * 80);
        if (h <= 0) h = (int) (dm.density * 84);
        int[] pr = petRectOnScreen(dm);
        int x = SysMetrics.sideBarX(pr[0], pr[2], sw, w, monitorGapPx(dm));
        int y = SysMetrics.sideBarY(pr[1], pr[3], sh, h);
        // ★ 拖动时每帧都会调到这里（见 followMonitorSide）：位置没变就不发窗口更新
        if (x == lastMonSideX && y == lastMonSideY) return;
        lastMonSideX = x; lastMonSideY = y;
        monitorSideParams.x = x;
        monitorSideParams.y = y;
        try { windowManager.updateViewLayout(monitorSideRoot, monitorSideParams); }
        catch (Throwable ignore) {}
        logMonitorSideBox();          // ★ 跟随是"看得见"的行为：坐标进日志，设备上才能验
    }

    /**
     * 间距（像素）—— 用户可调，且**带缓存**。
     *
     * <p>拖动时这个方法每帧都会被问到；prefs 虽然是内存态，但每帧三五个
     * jni/Map 查询没必要。500ms 缓存足够让设置页拖动滑块看起来是实时的
     * （滑块本身也会 reloadPet，届时立刻重算）。
     */
    private int monitorGapPx(android.util.DisplayMetrics dm) {
        long now = android.os.SystemClock.uptimeMillis();
        if (monitorGapPx == Integer.MIN_VALUE || (now - monitorGapReadAtMs) > 500) {
            float dp = SysMetrics.MONITOR_GAP_DEFAULT_DP;
            try { dp = Prefs.monitorGapDp(this); } catch (Throwable ignore) {}
            monitorGapPx = (int) (dm.density * dp);
            monitorGapReadAtMs = now;
        }
        return monitorGapPx;
    }

    /** 把侧栏的屏幕坐标打进日志（悬浮窗不在 uiautomator dump 里，只能靠坐标取样/日志）。 */
    private void logMonitorSideBox() {
        // ★ 这条日志挂在"跟随"路径上（拖动时每帧都会走到），所以必须**节流** ——
        //   每帧写一次 logcat 本身就是开销，正好是这轮要消掉的那类东西。
        long now = android.os.SystemClock.uptimeMillis();
        if (now - logMonSideAtMs < 500) return;
        logMonSideAtMs = now;
        int[] loc = new int[2];
        try {
            monitorSideRoot.getLocationOnScreen(loc);
        } catch (Throwable ignore) {
            return;
        }
        android.util.Log.i("DSPFpsBadge", "onscreen side x=" + loc[0] + " y=" + loc[1]
                + " w=" + monitorSideRoot.getWidth() + " h=" + monitorSideRoot.getHeight()
                + " vis=" + monitorSideRoot.getVisibility()
                + " gapPx=" + monitorGapPx
                + " petX=" + (params == null ? "?" : params.x)
                + " petY=" + (params == null ? "?" : params.y)
                + " petW=" + (root == null ? -1 : root.getWidth())
                + " petH=" + (root == null ? -1 : root.getHeight()));
    }

    /** 收起侧栏（吸附 / 关闭 / 重建 / 藏桌宠时调用）。 */
    private void monitorHideSide() {
        if (!monitorSideShowing || monitorSideRoot == null) return;
        try { windowManager.removeView(monitorSideRoot); } catch (Throwable ignore) {}
        monitorSideShowing = false;
    }

    /**
     * 吸附态让监测条**顶替竖条**：能顶就返回 true（调用方不必再显示竖条）。
     *
     * <p>用户明确要求"收起时代替吸附的竖条且同步竖条的操作"——
     * 所以位置/尺寸/命中判定都与竖条一致，只是内容换成三格指标。
     * 从未拿到过数据时返回 false，退回普通竖条（避免显示一条"--"的怪条）。
     *
     * ★ 判据用 {@code monitorReady}（**曾经**拿到过数据），**不是** {@code monitorFresh}
     *   （最近 5 秒内有数据）—— 两者会打架：过期时本方法返回 false 让调用方去画普通竖条，
     *   而 {@link #monitorRender()} 在吸附态又会把监测条置为可见，
     *   同一个槽位上就叠着两条东西（模拟器实测撞到过）。
     *   现在统一成"有数据就占槽位"，过期时三格显示 {@code --}（诚实、布局稳定），
     *   只有"从未有数据"才退回普通竖条。
     */
    private boolean monitorTakeStripSlot() {
        if (!monitorWantsStripSlot()) return false;
        monitorRender();
        return monitorBar.getVisibility() == View.VISIBLE;
    }

    /**
     * 监测条此刻是否**要以竖条形态占吸附槽位** —— 判据唯一来源。
     *
     * <p>被三处用：{@link #monitorTakeStripSlot()}（真的去占槽位）、
     * {@link #dockPeekPx()}（贴边动画开始前就要算出窗口该露出多宽）、
     * 以及日志。
     */
    private boolean monitorWantsStripSlot() {
        if (monitorBar == null) return false;
        final String[] ids = monitorSlots();
        boolean anySlot = false;
        for (String id : ids) {
            if (!SysMetrics.NONE.equals(id)) { anySlot = true; break; }
        }
        // 形态判据走纯函数：只有 barForm 认为"该以竖条形态出现"时才占
        // （吸附态只看中格 —— 竖条上就显示那一项）
        return SysMetrics.barForm(monitorGateOpen(), monitorReady, petTempHidden, true,
                !SysMetrics.NONE.equals(ids[1]), anySlot) == SysMetrics.BAR_STRIP;
    }

    /** 吸附竖条上那一格的文案（`--` 表示读数已过期）。渲染与量宽共用，避免两处算不一样。 */
    private String monitorMidText() {
        try {
            String[] ids = monitorSlots();
            return monitorFresh() ? SysMetrics.shortText(ids[1], monitorSnap) : "--";
        } catch (Throwable ignore) {
            return "--";
        }
    }

    /**
     * 该屏幕坐标是否落在竖条上（吸附态 DOWN 判定用）。
     *
     * <p>竖条是 container 的子 View：取它在窗口内的位置 + params.x/y，
     * 换算到屏幕坐标，再按 rawX/rawY 判定。取不到位置时按"露出细条"
     * 兜底（左贴边 → 屏左 0~peek 段且纵向在窗口内，右贴边对称）。
     */
    private boolean dockHitStrip(float rawX, float rawY) {
        try {
            if (params == null) return false;
            // ★ 吸附态下监测条会顶替竖条，此时"细条"就是监测条本身 ——
            //   命中判定必须跟着走，否则用户拖不动它（用户明确要求
            //   "同步竖条的操作"）。位置/尺寸与竖条一致，所以判定逻辑共用。
            View target = dockStrip;
            if (dockState == PetDockState.COLLAPSED && monitorBar != null
                    && monitorBar.getVisibility() == View.VISIBLE) {
                target = monitorBar;
            }
            if (target == null) return false;
            if (target.getVisibility() != View.VISIBLE) return false;
            int[] loc = new int[2];
            target.getLocationOnScreen(loc);
            int w = target.getWidth();
            int h = target.getHeight();
            if (w > 0 && h > 0) {
                float pad = getResources().getDisplayMetrics().density * 12f;
                return rawX >= loc[0] - pad && rawX <= loc[0] + w + pad
                        && rawY >= loc[1] - pad && rawY <= loc[1] + h + pad;
            }
            // 兜底：竖条还没量好尺寸，按露出段判定
            refreshRealSize();
            int sw = screenW();
            int peek = dockPeekPx();
            int winH = (root != null && root.getHeight() > 0) ? root.getHeight()
                    : (int) (getResources().getDisplayMetrics().density
                        * BASE_PET_H * Prefs.getScale(this));
            boolean xHit = (dockEdge == PetDockState.Edge.LEFT)
                    ? (rawX >= 0 && rawX <= peek)
                    : (rawX >= sw - peek && rawX <= sw);
            boolean yHit = (rawY >= params.y && rawY <= params.y + winH);
            return xHit && yHit;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 竖条拖动：窗口沿边缘上下搬（x 锁死在当前吸附 x，只改 y）。
     *
     * <p>余额气泡同步跟随；y 按屏幕钳制（clampToScreen 会连 x 一起夹，
     * 但 x 本来就在边缘位，夹完不变，无需另存）。
     */
    private void dockMoveStrip(int dy) {
        if (params == null || root == null) return;
        if (dockState != PetDockState.COLLAPSED) return;
        params.y += dy;
        clampToScreen();
        // x 锁边缘：clamp 可能因 inset 把 x 微调，强制拉回吸附 x
        try { params.x = dockEdgeX(dockEdge); } catch (Throwable ignore) {}
        try { applyRootLayout(); } catch (Throwable ignore) {}
        positionBalance();
        lastPetTouchMs = System.currentTimeMillis();
        lastUserActMs = System.currentTimeMillis();
    }

    /**
     * 边缘提示动画：竖条向内轻探再回弹，暗示"可滑出"。
     *
     * <p>只改 params.x（窗口位移），不触发布局重建。
     *
     * <p>★ 两个必须守住的点，否则就是"一直点竖条、竖条慢慢飞出去"：
     * <ol>
     *   <li>基准必须是**规范边缘位** {@link #dockEdgeX}，不能取当前 params.x ——
     *       动画途中再点，当前 x 已经偏出去了，拿它当基准等于把偏移固化；</li>
     *   <li>新提示开始前先取消旧提示（{@link #dockHideHint}），
     *       两个动画并行写同一份 params.x 会互相覆盖、落点随机。</li>
     * </ol>
     */
    private void dockPlayEdgeHint() {
        if (params == null || root == null) return;
        if (dockState != PetDockState.COLLAPSED) return;
        // 悬停 / 跟手 / 竖条拖动进行中不插队：这些状态由手指主导位置
        if (dockNudging || dockSwiping || dockStripDragging || dockSwipeActive) return;
        dockHideHint();
        final int baseX = dockEdgeX(dockEdge);
        // 先归位到规范边缘位，动画从干净状态起步
        params.x = baseX;
        try { applyRootLayout(); } catch (Throwable ignore) {}
        final float dir = (dockEdge == PetDockState.Edge.LEFT) ? 1f : -1f;
        final float peek = getResources().getDisplayMetrics().density * 14;
        android.animation.ValueAnimator hint =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        hint.setDuration(650);
        hint.setInterpolator(new android.view.animation.DecelerateInterpolator());
        hint.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(android.animation.ValueAnimator a) {
                if (params == null || root == null) return;
                if (dockState != PetDockState.COLLAPSED) return;
                float t = (Float) a.getAnimatedValue();
                params.x = baseX + (int) (dir * peek * Math.sin(Math.PI * t));
                try { applyRootLayout(); } catch (Throwable ignore) {}
            }
        });
        hint.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                if (params == null || root == null) return;
                if (dockState != PetDockState.COLLAPSED) return;
                // 回到规范边缘位（不是"启动时的 x"），杜绝逐次累加
                params.x = baseX;
                try { applyRootLayout(); } catch (Throwable ignore) {}
                if (dockHintAnim == a) dockHintAnim = null;
            }
        });
        dockHintAnim = hint;
        hint.start();
    }

    /**
     * 取消提示动画，并把窗口 x 拉回规范边缘位。
     *
     * <p>cancel() 会触发 onAnimationEnd（ValueAnimator 语义），listener 里已归位一次；
     * 这里再兜一次，保证任何取消路径结束后 params.x 都等于 dockEdgeX。
     */
    private void dockHideHint() {
        if (dockHintAnim != null) {
            try { dockHintAnim.cancel(); } catch (Throwable ignore) {}
            dockHintAnim = null;
        }
        if (params != null && dockState == PetDockState.COLLAPSED
                && !dockNudging && !dockSwiping && !dockStripDragging && !dockSwipeActive) {
            try {
                params.x = dockEdgeX(dockEdge);
                applyRootLayout();
            } catch (Throwable ignore) {}
        }
    }

    /**
     * 余额气泡显隐的【唯一入口】：显示 / 隐藏直接切换，不存在任何自动隐藏计时。
     *
     * 重做原因：旧逻辑里「交互」和「余额轮询」都在重排一个 4 秒自动隐藏定时器，
     * 定时器到点把气泡隐藏、紧接着下一轮轮询又把气泡恢复显示，
     * 于是出现「点一下 → 显示 4 秒 → 消失 → 过一会又常驻出现」的错乱。
     * 现在只有点击能改变显隐，显示后一直常驻，直到再次点击才消失。
     */
    private void setBalanceBubbleVisible(boolean show) {
        // ★★★ 轮次76：总开关关着时，**任何路径都不许把它显示出来**（含单击、菜单、重建）。
        if (show && !Prefs.balanceBubbleOn(this)) show = false;
        isVisible = show;
        Prefs.setShowBalance(this, show);   // 记住显隐状态，悬浮窗重建后保持一致
        if (balRoot == null || text == null) return;
        // ★ 轮次77：挂边期间**一律不显示**（它是独立悬浮窗，按窗口 x 定位，
        //   挂边时会被拖到屏幕边缘/挡住头）。isVisible 记的是用户意图，不受影响 ——
        //   退出挂边时按 isVisible 恢复。
        final boolean effective = show && (peekPhase == PeekPose.OFF);
        balRoot.setVisibility(effective ? View.VISIBLE : View.GONE);
        if (effective) {
            // 先按当前已知尺寸摆好，布局完再精确摆一次，避免第一帧跳到 (0,0)
            positionBalance();
            if (longPressShowing) positionLongPressBubble();
            text.post(new Runnable() { @Override public void run() { positionBalance(); } });
        }
    }

    /**
     * ★★★ 轮次76：设置里切换「显示余额气泡」后**立刻**生效（不用等重启桌宠）。
     *
     * <p>开 ⇒ 显示并重启轮询；关 ⇒ 隐藏并**停掉轮询**（余额是一串真实网络请求，
     * 用户关掉气泡多半就是不想让它再查）。
     */
    private void applyBalanceBubbleSetting() {
        final boolean on = Prefs.balanceBubbleOn(this);
        poll.removeCallbacksAndMessages(null);
        setBalanceBubbleVisible(on);
        if (on) startLoop();
    }

    // ==================== 回复连发（把一条回复拆成几条短消息） ====================

    /**
     * 上一轮「模型选中的配图名」（无则 null）。
     * 因 genProactiveOnce / genReactiveOnce 只返回文本，配图名通过这个字段旁路传出。
     * 每次发起新请求前必须清空，避免把上一轮的表情错配到这一轮。
     */
    private String lastModelMeme = null;

    /** 待播放的回复片段队列；非空表示「连发」正在进行 */
    private final java.util.List<String> replyQueue = new java.util.ArrayList<String>();
    /** 连发时每条气泡的默认停留时长（毫秒） */
    private static final long SPLIT_BUBBLE_MS = 6000;
    /** 逐条之间的随机抖动上限（毫秒） */
    private static final int SPLIT_JITTER_MS = 700;

    private final Runnable replyStepTask = new Runnable() {
        @Override public void run() {
            if (replyQueue.isEmpty()) return;
            final String msg = replyQueue.remove(0);
            showBubbleInternal(msg, SPLIT_BUBBLE_MS, splitRoundMeme);
            speakOwn(msg, true);          // 排队：等上一句念完再念这条
            if (!replyQueue.isEmpty()) {
                int base = Prefs.splitDelayMs(FloatingService.this);
                int jitter = (int) (Math.random() * SPLIT_JITTER_MS);
                handler.postDelayed(replyStepTask, base + jitter);
            }
        }
    };

    /** 本轮连发共用的配图名（null = 不配图） */
    private String splitRoundMeme = null;

    /** 取消正在进行（或排队中）的连发 */
    private void cancelReplySplit() {
        replyQueue.clear();
        handler.removeCallbacks(replyStepTask);
    }

    private final Runnable hideBubbleTask = new Runnable() {
        @Override public void run() {
            if (bubbleView != null && windowManager != null) {
                try { windowManager.removeView(bubbleView); } catch (Exception ignore) {}
            }
            bubbleView = null;
            bubbleBody = null;
            bubbleTail = null;
            bubbleText = null;
            bubbleMeme = null;
            bubbleParams = null;
            bubbleShowing = false;
            // 口型只在「没有语音在播」时收起：语音还没念完就闭嘴会显得很怪
            if (petImage != null && (tts == null || !tts.isSpeaking())) petImage.setTalking(false);
            // 余额气泡的显隐只由用户点击切换，对话气泡消失时不去动它
            // （否则会出现「对话气泡消失后余额气泡才冒出来」的错位观感）
        }
    };

    /** 实时模式下，互动后立刻补拉一次余额/用量（带最小间隔节流，防连点刷接口） */
    private void maybeInstantBalanceRefresh() {
        if (!Prefs.balanceBubbleOn(this)) return;   // ★ 轮次76：关掉就不补拉
        if (Prefs.getBalanceRefreshSec(this) != Prefs.BAL_REFRESH_REALTIME) return;
        if (loading) return;
        if (System.currentTimeMillis() - lastBalFetchMs < BAL_FETCH_MIN_GAP_MS) return;
        refresh();
    }

    /** 动作菜单里的「显示 / 隐藏余额气泡」：与单击桌宠完全等价 */
    private void toggleBubble() {
        // ★★ 轮次76：总开关关着时，这里也不能把它打开（并告诉用户去哪儿开）。
        if (!Prefs.balanceBubbleOn(this)) {
            showBubble("余额气泡已在设置里关闭（设置 → 外观与显示）", 4000);
            return;
        }
        setBalanceBubbleVisible(!isVisible);
    }

    /** 余额/用量轮询：间隔由设置决定（实时=30 秒兜底；0=不轮询，仅启动查一次） */
    private void startLoop() {
        poll.postDelayed(new Runnable() {
            @Override
            public void run() {
                // ★ 熄屏时不查余额：这是一次**真实网络请求**，
                //   而熄屏时没人看气泡。夜里充电整晚每 30 秒发一次请求，
                //   既耗电又费流量（用户反馈"占用高、发热高"的一部分）。
                //   亮屏后下一拍自然恢复。
                boolean auto = true;
                try { auto = Prefs.perfAuto(FloatingService.this); } catch (Throwable ignore) {}
                // ★★ 轮次76：总开关关掉后就**不再排下一拍**（彻底停掉轮询；
                //   重新打开时由 applyBalanceBubbleSetting() 重新 startLoop()）。
                if (!Prefs.balanceBubbleOn(FloatingService.this)) return;
                long gap = Prefs.balanceRefreshMs(FloatingService.this);
                if (gap <= 0) gap = REFRESH_MS;   // 「仅启动时」也留一个低频兜底
                if (auto && !isScreenOn()) {
                    poll.postDelayed(this, gap);
                    return;
                }
                refresh();
                poll.postDelayed(this, gap);
            }
        }, 300);
    }

    // ==================== 整点报时 ====================

    private static final String KEY_LAST_CHIME_HOUR = "last_chime_hour";
    private long lastChimeCheckMs = 0;

    /** 每小时检查一次"是否跨过整点"，跨了就报一次时（同一小时只报一次） */
    private void tickHourChime() {
        long now = System.currentTimeMillis();
        if (now - lastChimeCheckMs < 30 * 1000L) return;   // 30 秒节流，避免频繁读取
        lastChimeCheckMs = now;
        if (!Prefs.hourChimeEnabled(this)) return;
        if (!Prefs.proactiveChat(this)) return;
        if (!canSpeakNow()) return;

        java.util.Calendar c = java.util.Calendar.getInstance();
        int hour = c.get(java.util.Calendar.HOUR_OF_DAY);
        // 只在整点后的 3 分钟内报时（避免启动时补报一堆）
        if (c.get(java.util.Calendar.MINUTE) > 3) return;
        long stamp = hour * 10000L + c.get(java.util.Calendar.DAY_OF_YEAR);
        if (Prefs.getLastChimeHour(this) == stamp) return;
        Prefs.setLastChimeHour(this, stamp);

        // 深夜（0-6 点）默认不吵，除非用户正在用手机
        if (hour >= 1 && hour <= 5 && !isUserActiveRecently()) return;

        doHourChime();
        android.util.Log.i("DSVoice", "整点报时触发 hour=" + hour);
    }

    private boolean isUserActiveRecently() {
        return System.currentTimeMillis() - lastUserActMs < 3 * 60_000L;
    }

    /** 生成并显示一句整点报时 */
    private void doHourChime() {
        if (chimeBusy) return;
        chimeBusy = true;
        // ★ 轮次㊱：整点场景（hour）此前**全工程零引用** —— 设置页能选、没人触发。
        playSceneOnceSafe(SceneRegistry.HOUR, 2000);
        if (localOnly() || !chatBackendReady()) {
            // 纯本地模式 / 没有可用的对话后端：直接用本地整点台词，不联网
            //   ★ 用户 2026-10-07 要求"各场景可自定义发言"，整点也是一个场景：
            //     自定义优先，没写才用 TimeContext 内置的那句。
            final String local;
            String custom = pickLocalLine(SceneRegistry.HOUR, null);
            local = (custom != null && custom.length() > 0) ? custom : TimeContext.hourChime();
            chimeBusy = false;
            handler.post(new Runnable() {
                @Override public void run() {
                    showBubble("\u23F0 " + local, 8000);
                    ChatMemory.append(FloatingService.this, "assistant", local);
                }
            });
            return;
        }
        final String apiKey = Prefs.getApiKey(this);
        final String model = Prefs.getChatModel(this);
        final String persona = Prefs.personaForPet(this);
        final String factsPrompt = ChatMemory.factsPrompt(this);
        final java.util.List<String[]> history =
                ChatMemory.recent(this, Math.max(4, ChatMemory.ctxLimit(this)));
        final String antiRepeat = buildAntiRepeatSystem();
        final String cue = TimeContext.chimeHint() + avoidRepeatBlock();
        final String fallback = TimeContext.hourChime();
        new Thread(new Runnable() {
            @Override public void run() {
                String line = genReactiveOnce(apiKey, model, persona, factsPrompt,
                        antiRepeat, history, cue, false);
                final String finalLine = (line != null && line.length() > 0) ? line : fallback;
                handler.post(new Runnable() {
                    @Override public void run() {
                        chimeBusy = false;
                        showBubble("\u23F0 " + finalLine, 8000);
                        ChatMemory.append(FloatingService.this, "assistant", finalLine);
                        ChatMemory.appendProactive(FloatingService.this, finalLine);
                    }
                });
            }
        }, "pet-hour-chime").start();
    }

    private boolean chimeBusy = false;

    /** 场景识别轮询：定时看主人在用什么应用，切换或久留时按冷却触发搭话 */
    private void startMoodLoop() {
        mood.postDelayed(new Runnable() {
            @Override public void run() {
                // ★ 熄屏时不探测：熄屏后没有"用户在用什么应用"可言，
                //   而每 5 秒查一次系统使用记录是有真实开销的
                //   （见 MoodWatcher.currentPackage 的说明）。
                //   亮屏时会自然恢复（这个 runnable 本身一直在续约）。
                if (Prefs.perfAuto(FloatingService.this) && !isScreenOn()) {
                    mood.postDelayed(this, MOOD_POLL_MS);
                    return;
                }
                tickMood();
                mood.postDelayed(this, MOOD_POLL_MS);
            }
        }, 15 * 1000L);
    }

    /** 一次场景探测 */
    private void tickMood() {
        tickHourChime();          // 整点报时独立于场景开关
        if (!Prefs.moodWatch(this)) return;
        // 未授权使用情况访问 → 首次提示一次，然后静默
        if (!MoodWatcher.hasPermission(this)) {
            if (!moodNotifiedNoPerm) {
                moodNotifiedNoPerm = true;
                android.util.Log.i("DSVoice", "场景识别：未授予使用情况访问权限，跳过");
            }
            return;
        }
        // ★ 前台场景探测**前移**：工作状态动画是"对主人环境的反应"，
        //   **不该依赖能不能调 API**（原来钩在 tryMoodChat 里，而纯本地档在
        //   下面的 localOnly() 就 return 了 ⇒ 纯本地用户永远看不到工作状态动画）。
        //   代价：开了「场景搭话」就会每 5 秒查一次使用记录 —— 纯本地档也查了；
        //   但这是用户自己打开的开关，语义上是"我允许你识别我在干嘛"。
        MoodWatcher.Foreground fg = MoodWatcher.current(this);
        if (fg == null) return;
        maybeWorkStatusAnim(fg);

        if (!Prefs.proactiveChat(this)) return;
        if (localOnly() || !chatBackendReady()) return;   // 纯本地/无后端：不联网搭话
        long now = System.currentTimeMillis();

        boolean switched = !fg.pkg.equals(moodLastPkg);
        if (switched) {
            moodLastPkgPrev = moodLastPkg;
            moodLastPkg = fg.pkg;
            moodSameSinceMs = now;
        }

        long cd = Prefs.getMoodCooldownSec(this) * 1000L;

        if (switched) {
            // 只要检测到切换应用 → 立即搭话（不受冷却门限制）
            android.util.Log.i("DSVoice", "切换触发: " + moodLastPkgPrev + "→" + fg.pkg
                    + " canSpeak=" + canSpeakNow() + " busy=" + moodBusy);
            tryMoodChat(fg);
        } else if (now - moodSameSinceMs > Prefs.getMoodStaySec(this) * 1000L) {
            // 同一应用停留太久 → 也问一句（这里才用冷却门，避免久留时连问）
            if (now - moodLastAskMs < cd) return;
            moodSameSinceMs = now;
            tryMoodChat(fg);
        }
    }

    private long lastWorkAnimMs = 0;
    private String lastWorkAnimPkg = null;

    /**
     * 切到「工作／办公」场景 → 演一个"工作状态"动作（这 6 个动画此前没有任何出口）。
     * <p>与"搭话"解耦：不要求 API Key、纯本地档也演；同一个工作应用不重复演，
     * 两次之间至少隔 60 秒（防止在办公应用之间来回切时连环演）。
     */
    private void maybeWorkStatusAnim(MoodWatcher.Foreground fg) {
        if (fg == null || fg.scene == null || !fg.scene.contains("工作")) {
            lastWorkAnimPkg = null;
            return;
        }
        if (fg.pkg != null && fg.pkg.equals(lastWorkAnimPkg)) return;
        long now = System.currentTimeMillis();
        if (now - lastWorkAnimMs < 60_000L) return;
        lastWorkAnimPkg = fg.pkg;
        lastWorkAnimMs = now;
        playEventAnim("workStatus");
        // ★ 轮次㊲：台词选项与动作选项同步 —— 工作状态动作播出时说这个场景的台词
        saySceneLine(SceneRegistry.EV_WORK);
    }

    // ==================== 余额气泡文本（峰谷标记 + 今日用量）====================

    /**
     * 余额气泡主行：💰 [谷]余额：12.34
     * 「峰」红字、「谷」绿字，置于「余额」之前。
     */
    private CharSequence balanceLine(String body) {
        return balanceLine(body, -1);
    }

    /**
     * 主行 = 前缀 + 峰/谷 + 正文。
     * bal < LOW_BALANCE_THRESHOLD 时，把「余额：¥x.xx」整段染成醒目红色加粗。
     */
    private CharSequence balanceLine(String body, double bal) {
        final boolean low = bal >= 0 && bal < LOW_BALANCE_THRESHOLD;
        // ⚠️ 两个前缀都是「代理对 emoji + 空格」= 3 个 char，保证下面偏移一致
        String prefix = low ? "\u26A0\uFE0F " : "\uD83D\uDCB0 ";
        // 「峰/谷」与「余额：¥x.xx」之间留出一点空隙，别挤在一起
        final String sep = "  ";
        android.text.SpannableString ss =
                new android.text.SpannableString(prefix + Pricing.tag() + sep + body);
        try {
            // 「峰」/「谷」单字染色。
            // ⚠️ 偏移必须是 3~4：emoji 是代理对，占 charAt(0)+charAt(1)，
            //    charAt(2) 才是那个空格，charAt(3) 才是「峰」/「谷」。
            //    （曾经写 2~3 染的是空格，肉眼完全看不出颜色）
            int tagStart = 3, tagEnd = 4;
            ss.setSpan(new android.text.style.ForegroundColorSpan(Pricing.color()),
                    tagStart, tagEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 时段单字加粗，弱化不了的小字也能一眼看出
            ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    tagStart, tagEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // 字号放大一点，让峰/谷更显眼
            ss.setSpan(new android.text.style.RelativeSizeSpan(1.15f),
                    tagStart, tagEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (low) {
                // 余额不足 1 元：金额整段红色 + 加粗 + 微放大，一眼可见
                // 起点跳过「峰/谷 + 分隔空白」，只染余额文字，不把空格也染红
                int moneyStart = tagEnd + sep.length();
                ss.setSpan(new android.text.style.ForegroundColorSpan(COLOR_LOW_BAL),
                        moneyStart, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                        moneyStart, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new android.text.style.RelativeSizeSpan(1.08f),
                        moneyStart, ss.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        } catch (Exception ignore) {}
        return ss;
    }

    /** 第二行：今日用量摘要（含缓存命中率）。
     *  一律优先用官网「今日」快照；没有快照时才退回本机估算。
     *  ⚠️ 不再显示「官网 / 本机」前缀——用户要求默认就按官网数据展示。 */
    /**
     * 用量行文本。
     *
     * <h3>★ 为什么按服务商区分内容</h3>
     * DeepSeek 的信息我们能算准，别家算不准 —— 所以宁可**不显示**，
     * 也不要给出错误数字：
     * <ul>
     *   <li><b>花费</b>：{@code Pricing} 是 DeepSeek 官方价目表
     *       （含峰谷时段）。对 Kimi/硅基流动等套用会算出**错误金额**。</li>
     *   <li><b>缓存命中率</b>：只有 DeepSeek 的响应带
     *       {@code prompt_cache_hit_tokens} 字段。别家不返回，
     *       统计出来恒为 0%，显示"命中 0.0%"会让人以为缓存坏了。</li>
     *   <li><b>余额</b>：见 {@link #refresh()}，非 DeepSeek 多数没有余额接口。</li>
     * </ul>
     * 所以非 DeepSeek 只保留**确实测得准**的两项：调用次数与 token 总量。
     */
    private String usageLine() {
        try {
            boolean deepseek = isDeepSeekProvider();
            int calls;
            long tokens;
            String rate;
            if (UsageStats.hasOfficialUsage(this)) {
                calls = UsageStats.officialCalls(this);
                tokens = UsageStats.officialTokens(this);
                rate = UsageStats.officialCacheText(this);
            } else {
                calls = UsageStats.calls(this);
                tokens = UsageStats.totalTokens(this);
                rate = UsageStats.cacheRateText(this);
            }
            if (calls <= 0 && tokens <= 0) return "";

            if (!deepseek) {
                // 非 DeepSeek：只有次数与 token 是可靠的
                return String.format(java.util.Locale.US,
                        "%d \u6B21 \u00B7 %s tok", calls, UsageStats.k(tokens));
            }

            double cost = UsageStats.cost(this);
            // 有官网花费时放最前面，「今日¥x.xx · 12 次 · 3.4k tok · 命中 62.0%」
            String money = cost > 0
                    ? String.format(java.util.Locale.US, "\u4ECA\u65E5\u00A5%.2f \u00B7 ", cost)
                    : "";
            return money + String.format(java.util.Locale.US,
                    "%d \u6B21 \u00B7 %s tok \u00B7 \u547D\u4E2D %s",
                    calls, UsageStats.k(tokens), rate);
        } catch (Exception e) {
            return "";
        }
    }

    /** 当前对话服务商是否为 DeepSeek（决定能否显示余额 / 花费 / 命中率） */
    private boolean isDeepSeekProvider() {
        try {
            return "deepseek".equals(ChatProvider.find(
                    Prefs.getChatProvider(this)).id);
        } catch (Throwable t) {
            return true;   // 取不到时按 DeepSeek 处理（保守：与旧行为一致）
        }
    }

    /** 把主行 + 用量行一起写进余额气泡（两条文本用换行拼接，保持单一 TextView 便于等比缩放） */
    private void setBalanceText(String body) {
        setBalanceText(body, -1);
    }

    /** body=主行文本；balForColor 为当前余额（<0 表示未知，不做低余额染色） */
    private void setBalanceText(String body, double balForColor) {
        if (text == null) return;
        String u = usageLine();
        // ★ 主行为空 ⇒ 只显示用量行（非 DeepSeek 走这条路）。
        //   必须特判：否则会 append 一个空串再 append "\n"，
        //   气泡顶部留出一条空白，看起来像渲染坏了。
        boolean noMain = (body == null || body.length() == 0);
        CharSequence main = noMain ? "" : balanceLine(body, balForColor);
        if (u.length() == 0) {
            text.setText(main);
            return;
        }
        if (noMain) {
            // 只有用量行：不上主行配色，直接按"次要行"样式渲染
            android.text.SpannableStringBuilder only = new android.text.SpannableStringBuilder(u);
            try {
                only.setSpan(new android.text.style.ForegroundColorSpan(uiT().text2),
                        0, only.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignore) {}
            text.setText(only);
            return;
        }
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(main).append("\n");
        int start = sb.length();
        sb.append(u);
        try {
            // 用量行是**次要信息** → 用 text3（比正文弱一档）。
            // 原来写死 0xFF3C4A5E / 0xFF5A7091 两个深灰蓝，
            // 深色主题下那两个颜色叠在深色底上几乎看不见。
            sb.setSpan(new android.text.style.ForegroundColorSpan(uiT().text3),
                    start, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.RelativeSizeSpan(0.86f), start, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } catch (Exception ignore) {}
        text.setText(sb);
    }

    /**
     * 登录用户：后台拉一次官网「今日」用量并写入快照，供余额气泡第二行显示。
     * 只读、静默失败，不影响余额刷新主流程。
     */
    private void syncOfficialToday() {
        if (PlatformClient.token(this).length() == 0) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    PlatformClient.Usage u = PlatformClient.fetchUsage(FloatingService.this, 0);
                    final long tok = u.totalTokens();
                    UsageStats.applyOfficialUsage(FloatingService.this,
                            u.totalReq(), tok, u.totalHit(), u.totalMiss(),
                            u.officialCost ? u.totalCost() : -1);
                    if (text != null) {
                        text.post(new Runnable() { @Override public void run() {
                            renderBalanceNow();
                        } });
                    }
                } catch (Exception ignore) { }
            }
        }).start();
    }

    /** 用当前已知的余额数字重绘一次气泡（不发起网络请求） */
    private void renderBalanceNow() {
        double last = UsageStats.lastBalance(this);
        if (last < 0) return;
        setBalanceText(String.format(java.util.Locale.US, "\u4F59\u989D\uFF1A\u00A5%.2f", last),
                last);
        if (text != null) {
            text.post(new Runnable() { @Override public void run() { positionBalance(); } });
        }
    }

    private void refresh() {
        if (loading) return;
        // ★★ 轮次76：总开关关掉后**连请求都不发**（余额是一串真实网络请求）。
        if (!Prefs.balanceBubbleOn(this)) return;
        // ★ 非 DeepSeek：**完全不显示余额行**，只留用量。
        //
        //   原因有两层：
        //   ① 事实层：多数服务商根本没有余额接口（百炼/OpenAI 就没有），
        //      Kimi/硅基流动虽然后，但用户明确要求"切换其他模型去掉余额"。
        //   ② 准确层：余额与花费口径都是 DeepSeek 特有的（Pricing 是它的
        //      官方价目表），别家显示出来会误导。
        //   所以这里直接保留"只有用量行"的气泡，连"不提供余额接口"都不写 ——
        //   用户要的是干净，不是解释。
        if (!isDeepSeekProvider()) {
            if (text != null) {
                setBalanceText("");       // 空主行 ⇒ 只剩用量行
                text.post(new Runnable() { @Override public void run() { positionBalance(); } });
            }
            return;
        }
        final String key = Prefs.getApiKey(this);
        if (key == null || key.length() == 0) {
            if (text != null) { setBalanceText("余额：未设置 Key");
                text.post(new Runnable() { @Override public void run() { positionBalance(); } }); }
            return;
        }
        final ChatProvider.Entry prov = ChatProvider.find(Prefs.getChatProvider(this));
        if (!prov.hasBalance()) {
            // DeepSeek 分支不应走到这里；留个兜底避免气泡空白
            if (text != null) { setBalanceText("余额：" + prov.name + " 不提供余额接口");
                text.post(new Runnable() { @Override public void run() { positionBalance(); } }); }
            return;
        }
        loading = true;
        lastBalFetchMs = System.currentTimeMillis();
        if (text != null) { setBalanceText("余额：查询中…");
            text.post(new Runnable() { @Override public void run() { positionBalance(); } }); }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final ApiClient.Result r = ApiClient.getBalance(key, prov);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (text == null) return;
                        final double bal;
                        if (r.ok) {
                            bal = r.totalBalance;
                            String symbol = "CNY".equalsIgnoreCase(r.currency) ? "¥" : (r.currency + " ");
                            setBalanceText(String.format("余额：%s%.2f", symbol, bal), bal);
                            UsageStats.saveBalance(FloatingService.this, bal);
                            // ★ 余额档位变了 → 演一个"余额"动画（6 个档位动画的出口之一；
                            //   低余额提醒那条出口在 maybeBalanceTalk 里）
                            maybeBalanceAnim(bal);
                            // 官网余额到手 → 顺手校准「今日用量/今日花费」。
                            // 仅 DeepSeek 的余额能对应该套用量口径，别家不要误套。
                            if ("deepseek".equals(prov.id)) {
                                UsageStats.applyOfficialBalance(FloatingService.this, bal);
                                syncOfficialToday();
                            }
                            lastBalFetchMs = System.currentTimeMillis();
                        } else {
                            bal = -1;
                            // 失败时退回上次成功余额显示（若没有则显示错误），避免气泡空白
                            double last = UsageStats.lastBalance(FloatingService.this);
                            if (last >= 0) {
                                setBalanceText(String.format("余额：¥%.2f（离线）", last), last);
                            } else {
                                setBalanceText("余额获取失败：" + r.error);
                            }
                        }
                        text.post(new Runnable() { @Override public void run() { positionBalance(); } });
                        if (bal >= 0) maybeBalanceTalk(bal);
                    }
                });
            }
        }).start();
    }

    // ==================== 余额告知 / 用量告知（本地台词，不耗 token）====================

    private long lastBalanceTalkMs = 0;
    private long lastUsageTalkMs = 0;
    /** 上次余额档位（-1 = 还没数过）；档位变化才演一个余额动作，见 maybeBalanceAnim */
    private int lastBalanceAnimBand = -1;

    /** 余额跌破档位时，让桌宠主动提醒一次（每个档位只说一次，充值回暖后自动复位） */
    private void maybeBalanceTalk(double bal) {
        if (!Prefs.balanceNotify(this)) return;
        long now = System.currentTimeMillis();
        if (now - lastBalanceTalkMs < 10 * 60_000L) return;
        if (bal >= 20.0) {
            // 已回充 → 复位提醒档位，下次再跌下来还会提醒
            try {
                getSharedPreferences("deepseek_pet_usage", MODE_PRIVATE)
                        .edit().putInt("bal_notified", 0).apply();
            } catch (Exception ignore) {}
            return;
        }
        if (!UsageStats.shouldNotifyBalance(this, bal)) return;
        lastBalanceTalkMs = now;

        String line;
        if (bal < 1.0) {
            line = "主人！余额只剩 " + String.format(java.util.Locale.US, "%.2f", bal) + " 元了，要断粮啦…";
        } else if (bal < 5.0) {
            line = "唔…余额只有 " + String.format(java.util.Locale.US, "%.2f", bal) + " 元咯，记得充一点呀～";
        } else {
            line = "提醒一下，余额还剩 " + String.format(java.util.Locale.US, "%.2f", bal) + " 元哦。";
        }
        final String msg = line;
        handler.post(new Runnable() {
            @Override public void run() {
                showBubble("\uD83D\uDCB0 " + msg, 9000);
                ChatMemory.append(FloatingService.this, "assistant", msg);
            }
        });
        android.util.Log.i("DSVoice", "余额提醒: " + msg);
    }

    /** 用量告知：用量增长到整数档时，报一次今日用量（并带缓存命中率）。 */
    private void maybeUsageTalk() {
        if (!Prefs.usageNotify(this)) return;
        try {
            int c = UsageStats.calls(this);
            if (c <= 0) return;
            if (c != 1 && c % 10 != 0) return;
            if (System.currentTimeMillis() - lastUsageTalkMs < 60_000L) return;
            lastUsageTalkMs = System.currentTimeMillis();
            // ★ 命中率只有 DeepSeek 会返回（别家的响应没有
            //   prompt_cache_hit_tokens 字段，统计恒为 0.0%）。
            //   对别家说"缓存命中 0.0%"是**报了一个假数字** ——
            //   用户会以为缓存功能坏了。
            //   这个出口与余额气泡的第二行是两个不同的地方，
            //   第十三轮只改了后者，所以这里漏了（实测才发现）。
            final String tail = isDeepSeekProvider()
                    ? "，缓存命中 " + UsageStats.cacheRateText(this) + " ～"
                    : " ～";
            final String msg = "今天我们已经聊了 " + c + " 轮啦，一共用掉 "
                    + UsageStats.k(UsageStats.totalTokens(this)) + " token" + tail;
            handler.post(new Runnable() {
                @Override public void run() { showBubble("\uD83D\uDCCA " + msg, 8000); }
            });
        } catch (Exception ignore) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ★★ v7.0 修（实测崩溃）：本服务会被 startForegroundService() 拉起 ——
        //   划掉任务卡后的自救（onTaskRemoved）、看门狗巡检都走这条路。
        //   Android 8+ 要求这种启动**必须在 5 秒内调用 startForeground()**，
        //   否则系统直接杀掉整个进程（catch 不住，因为异常是系统在主线程抛的）。
        //
        //   原来的问题：只有"正常启动"那条路最终会走到 startForegroundSafely()，
        //   而 ACTION_RESTART 分支只 post 一次 rebuildViews() 就 return 了
        //   ⇒ 划掉任务卡 → 桌宠被拉起来 → 5 秒内没 startForeground → 进程死，
        //   桌宠**彻底消失**。logcat 证据：
        //     E AndroidRuntime: Caused by: android.app.StackTrace:
        //         Last startServiceCommon() call for this service was made here
        //     ... at com.deepseek.pet.FloatingService.onTaskRemoved(FloatingService.java:7690)
        //     I ActivityManager: Process com.deepseek.pet (pid …) has died
        //   （这条与待办 1「桌宠服务被杀」是同一族问题。）
        startForegroundSafely();

        // ---- App 前台/后台：临时收起 / 恢复桌宠（只改可见性，不停止服务）----
        //
        // 用 GONE 而不是 removeView：移除要重建整套视图（还有背景层），
        // 进出设置页会闪；GONE 只是不画，回来时瞬间恢复，也不丢心情/漫游状态。
        if (intent != null && ACTION_HIDE_TEMP.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() {
                    setPetTempHidden(true);
                }
            });
        }
        if (intent != null && ACTION_SHOW_TEMP.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() {
                    setPetTempHidden(false);
                }
            });
        }
        if (intent != null && ACTION_REPOSITION.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() {
                    monitorGapPx = Integer.MIN_VALUE;   // 作废间距缓存 → 立刻重读新值
                    monitorGapReadAtMs = 0;
                    followMonitorSide();
                }
            });
        }
        if (intent != null && ACTION_RELOAD.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() { rebuildViews(); }
            });
            // 重排余额轮询（不再强制亮出气泡，显隐由用户点击决定）
            handler.post(new Runnable() {
                @Override public void run() {
                    poll.removeCallbacksAndMessages(null);
                    startLoop();
                }
            });
            // 设置变化时重排主动聊天（开关/间隔可能改了）
            proactive.removeCallbacksAndMessages(null);
            if (Prefs.proactiveChat(this)) {
                rescheduleProactive();
                android.util.Log.i("DSVoice", "RELOAD: 主动聊天重排, 间隔="
                        + Prefs.getProactiveMinutes(this) + "分钟");
            } else {
                android.util.Log.i("DSVoice", "RELOAD: 主动聊天已关闭");
            }
        }
        // ★★★ 轮次76：设置里切换「显示余额气泡」⇒ 立刻应用（隐藏/恢复 + 停/启轮询）
        if (intent != null && ACTION_BALANCE_VIS.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() { applyBalanceBubbleSetting(); }
            });
        }
        if (intent != null && "com.deepseek.pet.PROACTIVE_NOW".equals(intent.getAction())) {
            android.util.Log.i("DSVoice", "PROACTIVE_NOW 手动触发");
            handler.post(new Runnable() {
                @Override public void run() {
                    // 手动让桌宠立刻开口：忽略 5 分钟静默与间隔节流，
                    // 但仍尊重 气泡占用/勿扰/正在输入 等硬条件
                    if (proactiveBusy || askShowing || bubbleShowing) {
                        Toast.makeText(FloatingService.this,
                                "桌宠正在说话，稍等片刻~", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    lastProactiveSpeakMs = 0;
                    proactiveBusy = false;
                    tryProactiveChat(true);
                }
            });
        }
        // ---- App「退出桌宠」：播哈欠 + 道别，然后只停止桌宠服务（不杀后台进程）----
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() { exitPetWithFarewell(); }
            });
            return START_NOT_STICKY;
        }
        // ---- App「启动 / 重启桌宠」：已运行则重建视图（自动重播伸懒腰 + 睡醒问候）----
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            if (System.currentTimeMillis() - viewsBuiltAtMs > 1500) {
                handler.post(new Runnable() {
                    @Override public void run() { rebuildViews(); }
                });
            }
            return START_STICKY;
        }
        // ---- 调试：立即贴边 / 立即展开（adb 验证竖条用，无 UI 入口）----
        if (intent != null && ACTION_DOCK_NOW.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() { dockCollapse(false); }
            });
            return START_STICKY;
        }
        if (intent != null && ACTION_UNDOCK_NOW.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() { dockExpand(); }
            });
            return START_STICKY;
        }
        // ---- 调试：在两种贴边形态之间切换（等同于设置页「🪝 贴边与挂边」那行）----
        if (intent != null && ACTION_PEEK_TOGGLE.equals(intent.getAction())) {
            handler.post(new Runnable() {
                @Override public void run() {
                    int style = Prefs.edgeDockStyle(FloatingService.this);
                    int next = (style == Prefs.DOCK_STYLE_PEEK)
                            ? Prefs.DOCK_STYLE_STRIP : Prefs.DOCK_STYLE_PEEK;
                    try { Prefs.setEdgeDockStyle(FloatingService.this, next); } catch (Throwable ignore) {}
                    if (next == Prefs.DOCK_STYLE_STRIP) {
                        peekCancel();          // 正挂着就平滑收回屏内
                    } else {
                        peekBegin();           // 已经贴边就立刻挂一次
                    }
                    android.util.Log.i("DSPPeek", "贴边形态="
                            + (next == Prefs.DOCK_STYLE_PEEK ? "挂边露头" : "压扁竖条"));
                }
            });
            return START_STICKY;
        }
        return START_STICKY;
    }

    // ==================== 性能优化（功耗 / 发热）====================

    /**
     * 把当前的性能档位应用到 PetView。
     *
     * <p>调用时机：服务启动、用户改档位、休眠/唤醒。
     * 幂等，重复调用无副作用。
     */
    private void applyPerfSettings() {
        if (petImage == null) return;
        try {
            // 帧率上限只对**我们自己重绘**的路径（模型包 / Live2D）有效；
            // 内置动画由系统驱动，压不动（实测两个方案都无效，
            // 见 PetView 里那段"被实测否掉的设计"）。
            petImage.setFrameDelayMs(Prefs.perfFrameDelayMs(this));
            boolean auto = Prefs.perfAuto(this);
            boolean screenOn = isScreenOn();
            // 贴边吸附态同样暂停绘制：竖条是静态 View，不需要 PetView 重绘；
            // 呼吸/眨眼/尾巴/动画包/Live2D 全停，待机功耗最低。
            // 悬停只是竖条小幅弹出（桌宠仍在屏外），也保持暂停；
            // 只有跟手往外拖 / 竖条上下拖动时才恢复（那时才需要画面）。
            boolean docked = (dockState == PetDockState.COLLAPSED)
                    && !dockSwipeActive && !dockStripDragging;
            petImage.setDrawingPaused((auto && !screenOn) || docked);
        } catch (Throwable ignore) {}
    }

    /**
     * 贴边渲染开关：暂停/恢复 PetView 全路径绘制。
     *
     * <p>暂停内容：呼吸位移动画（ViewPropertyAnimator）、PetView.onDraw
     * （呼吸/眨眼/尾巴/耳朵/模型/Live2D）、动画包 pauseAnimPack
     * （AnimatedImageDrawable 解码线程 + 轮播定时器）。
     * 竖条是独立静态 View，不受影响，吸附位置依然可见。
     * 恢复时补一帧 + 重启动画包 + 重启呼吸，保证展开瞬间不是黑屏。
     */
    private void dockSetRenderPaused(boolean paused) {
        try {
            if (petImage != null) {
                if (paused) {
                    try { petImage.animate().cancel(); } catch (Throwable ignore) {}
                    try {
                        if (petImage.isAnimPack()
                                && !petImage.isAnimPackPaused()) petImage.pauseAnimPack();
                    } catch (Throwable ignore) {}
                    petImage.setDrawingPaused(true);
                } else {
                    petImage.setDrawingPaused(false);
                    try {
                        if (petImage.isAnimPack()
                                && petImage.isAnimPackPaused()) petImage.resumeAnimPack();
                    } catch (Throwable ignore) {}
                }
            }
        } catch (Throwable ignore) {}
        try { applyPerfSettings(); } catch (Throwable ignore) {}
    }

    /** 屏幕当前是否亮着（拿不到状态时按"亮着"处理，宁可多画也别不显示） */
    private boolean isScreenOn() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    getSystemService(POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 熄屏 / 亮屏监听。
     *
     * <p><b>为什么这是最值钱的一处优化：</b>实测（`build/video_cost.py`、
     * `build/why_redraw.py`）熄屏后进程仍占约 10~12% CPU ——
     * RenderThread 6.4% + 动图解码线程 2.4%。而熄屏时桌宠**完全不可见**，
     * 这些全是白烧的电。夜里充电、手机放口袋，一烧就是一整晚。
     *
     * <p>做法：熄屏时把桌宠窗口**隐藏**（GONE）+ 暂停动图。
     * 隐藏比"停动画但窗口还在"更彻底 —— 窗口不可见时系统连合成都省了。
     */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            applyPerfSettings();
            String a = i == null ? null : i.getAction();
            boolean off = Intent.ACTION_SCREEN_OFF.equals(a);
            boolean on = Intent.ACTION_SCREEN_ON.equals(a)
                    || Intent.ACTION_USER_PRESENT.equals(a);
            boolean auto = true;
            try { auto = Prefs.perfAuto(FloatingService.this); } catch (Throwable ignore) {}
            if (!auto) return;
            if (off) {
                // 熄屏：藏起来 + 停动图（双保险：窗口 GONE 后动图本就该停，
                // 但某些 ROM 仍会继续解码，所以显式 stopAll）
                try { if (root != null) root.setVisibility(View.GONE); } catch (Throwable ignore) {}
                try { if (balRoot != null) balRoot.setVisibility(View.GONE); } catch (Throwable ignore) {}
                try {
                    if (petImage != null && petImage.isAnimPack()) petImage.pauseAnimPack();
                } catch (Throwable ignore) {}
                // 熄屏暂停贴边 idle 计时（看不见时不该自动贴边）
                try { handler.removeCallbacks(dockIdleTask); } catch (Throwable ignore) {}
            } else if (on && !petTempHidden) {
                // 亮屏：恢复显示（petTempHidden=true 表示是"进设置页"藏的，
                // 那时不能拉回来，否则会盖住设置页）
                // 吸附态亮屏：余额气泡保持隐藏（展开后才按 isVisible 恢复）
                boolean docked = (dockState == PetDockState.COLLAPSED);
                try { if (root != null) root.setVisibility(View.VISIBLE); } catch (Throwable ignore) {}
                try { if (balRoot != null) balRoot.setVisibility(
                        docked ? View.GONE : (isVisible ? View.VISIBLE : View.GONE)); }
                catch (Throwable ignore) {}
                try {
                    if (petImage != null && petImage.isAnimPack() && !docked) petImage.resumeAnimPack();
                } catch (Throwable ignore) {}
                dockResetIdle();
            }
        }
    };

    /**
     * 用户在最近任务里划掉了本 app 的卡片。
     *
     * <h3>为什么需要这个覆写（用户需求：划卡不能杀桌宠）</h3>
     * 默认行为下，划掉任务会让系统把该进程按"用户已退出"处理，
     * 前台服务也可能被一并回收。桌宠是**常驻悬浮工具**，
     * 用户划卡片的本意多半只是"清一下最近任务"，不是"关掉桌宠"。
     *
     * <p>这里不去阻止系统回收（拦不住），而是**确保桌宠被重新拉起来**：
     * {@code START_STICKY} 只在服务被系统杀时才生效，划卡这条路径不保险，
     * 所以主动补一次重启。看门狗（{@code KeepAliveReceiver}）是兜底，
     * 但它要等一个巡检周期，用户会看到桌宠"消失一阵才回来"。
     *
     * <p>尊重用户主动退出：{@code petShouldRun} 为 false（点过「退出桌宠」）时不复活。
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        boolean shouldRun = false;
        try { shouldRun = Prefs.petShouldRun(this); } catch (Throwable ignore) {}
        if (!shouldRun) return;   // 用户主动退出过，别擅自拉回来
        android.util.Log.i("DSPRecents", "任务被划掉，重新拉起桌宠");
        try {
            Intent svc = new Intent(getApplicationContext(), FloatingService.class);
            svc.setAction(ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= 26) {
                getApplicationContext().startForegroundService(svc);
            } else {
                getApplicationContext().startService(svc);
            }
        } catch (Throwable t) {
            // 后台启动前台服务受限时属正常，交给看门狗兜底
            android.util.Log.w("DSPRecents", "拉起失败（交给看门狗）: " + t);
        }
    }

    @Override
    public void onDestroy() {
        isAlive = false;
        super.onDestroy();
        // ★ 轮次57：释放点击音效缓存的静态音轨（每条音色一条，随服务销毁一起收）
        try { ClickPlayer.releaseAll(); } catch (Throwable ignore) {}
        try { unregisterReceiver(screenReceiver); } catch (Exception ignore) {}
        try { proactive.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
        try { mood.removeCallbacksAndMessages(null); } catch (Exception ignore) {}
        try { unregisterReceiver(reloadReceiver); } catch (Exception ignore) {}
        // 贴边态残留清理：停动画 + 清计时，避免销毁后还回调 windowManager
        dockCancelAnim();
        dockHideStrip();
        dockStrip = null;
        // ★ 轮次77：探头态残留清理（服务销毁 / 重建视图都走这三行）——
        //   探头的 rotation/pivot 是写在 petImage 上的，重建后新 View 是干净的，
        //   但不 abort 的话旧阶段的 Runnable 还会继续往新 View 上写姿态。
        peekAbort();
        // ★ 游戏助手监测条：必须成对摘监听 + 释放采样引用，
        //   否则服务销毁后采样线程还在跑（每秒一条 shell 命令），白耗电。
        if (fpsBadgeCb != null) {
            GameFpsMonitor.removeListener(fpsBadgeCb);
            fpsBadgeCb = null;
        }
        if (fpsAcquired) {
            fpsAcquired = false;
            GameFpsMonitor.release();
        }
        // ★ 侧面那颗是**独立悬浮窗**，不随桌宠窗口一起销毁 —— 漏掉会留下
        //   一个永远挂在屏幕上的幽灵监测条。
        monitorHideSide();
        monitorSideRoot = null;
        monitorSideTop = null;
        monitorSideMid = null;
        monitorSideBottom = null;
        monitorBar = null;
        monitorCellTop = null;
        monitorCellMid = null;
        monitorCellBottom = null;
        try { handler.removeCallbacks(fpsSyncTask); } catch (Throwable ignore) {}
        dockClearNudge();
        try { handler.removeCallbacks(dockIdleTask); } catch (Throwable ignore) {}
        try { handler.removeCallbacks(dockEdgeHintRunnable); } catch (Throwable ignore) {}
        dockSwiping = false;
        dockSwipeActive = false;
        dockSwipeProgress = 0f;
        dockState = PetDockState.NORMAL;
        if (petImage != null) petImage.clearAnimation();
        if (bubbleView != null && windowManager != null) {
            try { windowManager.removeView(bubbleView); } catch (Exception ignore) {}
        }
        try { unregisterReceiver(voiceReceiver); } catch (Exception ignore) {}
        hideVoiceWin();
        if (voiceHelper != null) { voiceHelper.destroy(); voiceHelper = null; }
        if (tts != null) { tts.release(); tts = null; }
        if (askRoot != null && windowManager != null) {
            try { windowManager.removeView(askRoot); } catch (Exception ignore) {}
        }
        if (actionRoot != null && windowManager != null) {
            try { windowManager.removeView(actionRoot); } catch (Exception ignore) {}
        }
        actionShowing = false;
        hideLongPressBubble();
        if (text != null && windowManager != null) {
            try { windowManager.removeView(balRoot); } catch (Exception ignore) {}
        }
        if (root != null && windowManager != null) {
            try { windowManager.removeView(root); } catch (Exception ignore) {}
        }
        // 背景层是独立悬浮窗，必须单独移除；漏掉会留下一个"幽灵"图层常驻屏幕
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
