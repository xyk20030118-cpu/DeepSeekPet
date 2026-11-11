package com.deepseek.pet;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Shizuku 接入封装 —— 状态判定、授权申请、UserService 绑定、命令执行。
 *
 * <h3>为什么不直接用 {@code Shizuku.newProcess}</h3>
 * 该 API 在 Shizuku 13.x 已从公开 API 移除（用 javap 打 13.1.5 的 api jar 实测确认，
 * 只剩 {@code bindUserService/peekUserService/attachUserService}）。
 * 官方替代路径是 <b>UserService</b>：让 Shizuku 以 shell 身份拉起我们自己的服务类，
 * 那个类里的 {@code Runtime.exec()} 天然就是 shell 权限。
 *
 * <h3>状态机</h3>
 * <pre>
 *   NOT_INSTALLED —— 没装 Shizuku App（给出安装引导）
 *   NOT_RUNNING   —— 装了但服务没起来（提示去 Shizuku 里启动/激活）
 *   NOT_GRANTED   —— 服务在跑但本应用未授权（弹授权框）
 *   READY         —— 可执行命令
 * </pre>
 * 桌宠本体不依赖本类，任何状态都不影响桌宠运行 —— 游戏助手只是可选增强。
 */
public final class ShizukuBridge {

    private ShizukuBridge() {}

    /** 日志 TAG：真机排查用 `adb logcat -s DSPShizuku:*`。 */
    public static final String TAG = "DSPShizuku";

    /** Shizuku 管理器的包名（实测来自 provider 常量池 MANAGER_APPLICATION_ID）。 */
    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    /** 授权请求码。 */
    public static final int REQ_SHIZUKU = 4001;

    public enum State {
        NOT_INSTALLED,   // 没装
        NOT_RUNNING,     // 装了没跑
        NOT_GRANTED,     // 跑了没授权
        READY            // 可用
    }

    /** 状态变化回调（UI 用）。 */
    public interface Listener {
        void onStateChanged(State state);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static volatile State state = State.NOT_INSTALLED;
    private static IFpsUserService userService;
    private static boolean bound = false;
    private static boolean hooked = false;

    // ==================== 状态 ====================

    public static State state() {
        return state;
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void setState(State s) {
        if (state == s) return;
        state = s;
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) {
                    try { l.onStateChanged(state); } catch (Throwable ignore) {}
                }
            }
        });
    }

    /** Shizuku App 是否已安装。 */
    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 重新判定状态。可随时调用（幂等）。
     *
     * <p>顺序很重要：先看装没装，再看服务活没活，最后看授权 ——
     * 否则"没装"会被误报成"没授权"，给出的引导文案就是错的。
     */
    public static State refresh(Context ctx) {
        if (!isInstalled(ctx)) {
            setState(State.NOT_INSTALLED);
            return state;
        }
        boolean alive;
        try {
            alive = Shizuku.pingBinder();
        } catch (Throwable t) {
            alive = false;
        }
        if (!alive) {
            setState(State.NOT_RUNNING);
            return state;
        }
        int perm;
        try {
            perm = Shizuku.checkSelfPermission();
        } catch (Throwable t) {
            perm = PackageManager.PERMISSION_DENIED;
        }
        if (perm != PackageManager.PERMISSION_GRANTED) {
            setState(State.NOT_GRANTED);
            return state;
        }
        setState(State.READY);
        return state;
    }

    /**
     * 挂上 Shizuku 的各类监听（只需一次）。
     *
     * <p>{@code addBinderReceivedListenerSticky} 而不是 {@code addBinderReceivedListener}：
     * 后者只在"之后"收到 binder 时回调，若 binder 早就到了就永远不触发 ——
     * 表现为"Shizuku 明明在跑，界面却一直显示未就绪"。
     */
    public static void hook(final Context ctx) {
        if (hooked) return;
        hooked = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(new Shizuku.OnBinderReceivedListener() {
                @Override public void onBinderReceived() {
                    refresh(ctx);
                }
            });
            Shizuku.addBinderDeadListener(new Shizuku.OnBinderDeadListener() {
                @Override public void onBinderDead() {
                    userService = null;
                    setState(State.NOT_RUNNING);
                }
            });
            Shizuku.addRequestPermissionResultListener(
                    new Shizuku.OnRequestPermissionResultListener() {
                        @Override public void onRequestPermissionResult(int code, int grantResult) {
                            if (code != REQ_SHIZUKU) return;
                            refresh(ctx);
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                bind(ctx);
                            }
                        }
                    });
        } catch (Throwable ignore) {
            // 没装 Shizuku 时挂监听会抛，属正常
        }
    }

    /** 申请授权（必须在 Activity 上下文里调，Shizuku 要弹系统框）。 */
    public static void request() {
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable ignore) {
        }
    }

    // ==================== UserService ====================

    /**
     * 取数服务的连接状态。
     *
     * <h3>★ 为什么要单独跟踪（而不是只看 state==READY）</h3>
     * {@code state==READY} 只说明"Shizuku 服务在跑且已授权"，
     * 但**我们自己的 UserService 是异步绑定的**，绑定完成前
     * {@code userService} 仍是 null。真机实测出现过这种自相矛盾的界面：
     * 上面写「Shizuku 状态：已就绪」，下面写「实时帧率：—（Shizuku 未就绪）」——
     * 两句都对，只是说的不是同一件事（一个说 Shizuku，一个说取数服务）。
     * 所以必须把这两件事分开呈现，否则用户和我都会误判。
     */
    public enum BindState {
        IDLE,      // 还没开始绑
        BINDING,   // 已发起，等 onServiceConnected
        BOUND,     // 已连上，可执行命令
        FAILED     // 绑定失败（见 bindError）
    }

    private static volatile BindState bindState = BindState.IDLE;
    private static volatile String bindError = "";
    private static volatile long bindStartedAt = 0L;

    /**
     * 绑定尝试序号 —— 用来给进程名后缀加后缀，逼 Shizuku 起**全新进程**。
     *
     * <h3>★ 为什么需要它（真机实测）</h3>
     * 用户设备上装的是 **ShizukuX**（root 改版）。实测绑定**不稳定**：
     * 同一个 versionCode 下，一次成功（900ms）、下一次就 8 秒超时。设备日志：
     * <pre>
     *   ShizukuServiceStarter: starting service ...FpsUserService...
     *   ShizukuX:UserServiceManager: attachUserService: unable to find token
     *       ... (stale or duplicate attach; ignoring)
     * </pre>
     * 即：服务进程起来了、也回传了 binder，但服务端**找不到那个 token**。
     * 现象是复用了已存在的旧服务进程（设备上累积了 6 个 root 的
     * {@code com.deepseek.pet:fps} 孤儿进程，adb 的 pkill 权限不足杀不掉），
     * 旧进程带着**上一轮的 token** 回来，自然匹配不上。
     *
     * <p>对策：每次重试换一个进程名后缀 ⇒ 服务端找不到可复用的旧进程，
     * 只能起新的，新进程带的是本轮 token。这是绕过该改版行为的最小改动。
     */
    private static volatile int bindAttempt = 0;

    private static String processSuffix() {
        int n = bindAttempt;
        return n == 0 ? "fps" : ("fps" + n);
    }

    /** 绑定超时（毫秒）：超过就判失败，不能让界面永远停在"连接中"。 */
    private static final long BIND_TIMEOUT_MS = 8000;

    public static BindState bindState() {
        // 超时判定放在读的时候做，省一个定时器
        if (bindState == BindState.BINDING
                && System.currentTimeMillis() - bindStartedAt > BIND_TIMEOUT_MS) {
            bindState = BindState.FAILED;
            bindError = "连接超时（" + (BIND_TIMEOUT_MS / 1000) + "s 未回调）";
            Log.w(TAG, "bindUserService 超时");
        }
        return bindState;
    }

    public static String bindError() {
        return bindError;
    }

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "onServiceConnected binder=" + binder);
            userService = IFpsUserService.Stub.asInterface(binder);
            bound = true;
            bindState = BindState.BOUND;
            bindError = "";
            notifyBind();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected");
            userService = null;
            bound = false;
            bindState = BindState.FAILED;
            bindError = "取数服务被断开";
            notifyBind();
        }
    };

    /** 绑定状态变化也要通知 UI（与 Shizuku 状态分开，见 BindState 的说明）。 */
    private static void notifyBind() {
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) {
                    try { l.onStateChanged(state); } catch (Throwable ignore) {}
                }
            }
        });
    }

    /**
     * 绑定 UserService（幂等）。绑定成功后 {@link #exec} 才可用。
     *
     * <p>★ 这里**不再静默吞异常**：第一版写成 {@code catch (Throwable ignore) {}}，
     * 结果真机上"状态已就绪但读数说未就绪"，完全查不出是哪一步失败。
     * 现在所有异常都记进 {@link #bindError} 并打到 logcat（TAG=DSPShizuku）。
     *
     * <p>{@code daemon(false)}：非守护模式，Shizuku 侧进程会随最后一个绑定者解绑而结束。
     * 游戏助手是"按需查看"，不需要常驻守护进程 —— 常驻会一直占着 shell 进程。
     */
    public static void bind(Context ctx) {
        // ★ 正在连接中就不要重复发起：每次 bindUserService 都会生成新 token，
        //   重复发起会让服务端上一条 token 失效，服务进程带着旧 token 回来时
        //   就匹配不上（真机日志里的 "unable to find token"）。
        if (bindState() == BindState.BINDING) {
            Log.i(TAG, "已在连接中，跳过重复 bind");
            return;
        }
        if (bindState() == BindState.BOUND && userService != null) return;
        if (state != State.READY) {
            Log.w(TAG, "bind 跳过：Shizuku 状态不是 READY，而是 " + state);
            return;
        }
        try {
            ComponentName cn = new ComponentName(
                    ctx.getPackageName(), FpsUserService.class.getName());
            int ver = serviceVersion(ctx);
            String suffix = processSuffix();
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
                    .daemon(false)
                    .processNameSuffix(suffix)
                    .debuggable(false)
                    .version(ver);
            bindState = BindState.BINDING;
            bindStartedAt = System.currentTimeMillis();
            bindError = "";
            Log.i(TAG, "bindUserService -> " + cn.flattenToShortString()
                    + " process=:" + suffix
                    + " serviceVersion=" + ver
                    + " 服务端版本=" + safeVersion());
            Shizuku.bindUserService(args, conn);
        } catch (Throwable t) {
            bindState = BindState.FAILED;
            bindError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
            Log.e(TAG, "bindUserService 抛异常", t);
            notifyBind();
        }
    }

    /**
     * 绑定超时后重试一次 —— 换进程名后缀，逼 Shizuku 起全新进程。
     *
     * <p>只在"超时"时重试；"已连接"或"Shizuku 未就绪"都不重试。
     * 由 UI 在采样发现 FAILED 时调用（见 {@code MainActivity}）。
     */
    public static void retryIfTimedOut(Context ctx) {
        if (bindState() != BindState.FAILED) return;
        if (bindAttempt >= 3) return;          // 别无限重试
        bindAttempt++;
        Log.i(TAG, "绑定失败，换进程名重试 #" + bindAttempt);
        bind(ctx);
    }

    /**
     * UserService 的版本号 —— **必须用应用的 versionCode**。
     *
     * <h3>★ 为什么不能用写死的 1（真机实测的坑）</h3>
     * 第一版写死 {@code .version(1)}。真机上第一次绑定成功（1200ms），
     * 之后重装 APK 再绑定就**一直超时**，并且设备上累积了
     * <b>3 个残留的 {@code com.deepseek.pet:fps} root 进程</b>（adb 的 pkill
     * 都杀不掉，权限不足）。Shizuku 日志给出关键行：
     * <pre>
     *   ShizukuX:UserServiceManager: attachUserService: unable to find token
     *   ... (stale or duplicate attach; ignoring)
     * </pre>
     * 即：服务进程起来了、也回传了 binder，但服务端找不到那个 token，于是忽略
     * —— 客户端永远等不到 {@code onServiceConnected}。
     * 根因是 Shizuku 用 {@code version} 判断"服务是否变了"来决定要不要重启旧进程；
     * 写死 1 时它认为没变，旧进程就赖着不走，与新 attach 互相干扰。
     *
     * <p>用 versionCode 后：应用一升级 → 版本号变 → Shizuku 主动清掉旧服务进程，
     * 不会再累积残留。这也是 Shizuku 官方示例的写法。
     */
    private static int serviceVersion(Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 1;
        }
    }

    private static String safeVersion() {
        try {
            return "v" + Shizuku.getVersion() + " preV11=" + Shizuku.isPreV11();
        } catch (Throwable t) {
            return "?";
        }
    }

    public static void unbind(Context ctx) {
        if (!bound) return;
        try {
            ComponentName cn = new ComponentName(
                    ctx.getPackageName(), FpsUserService.class.getName());
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(cn)
                    .daemon(false)
                    .processNameSuffix("fps")
                    .debuggable(false)
                    .version(serviceVersion(ctx));   // 必须与 bind 时一致
            Shizuku.unbindUserService(args, conn, true);
        } catch (Throwable ignore) {
        }
        userService = null;
        bound = false;
        bindState = BindState.IDLE;
    }

    /**
     * 现在能不能取数。
     *
     * <p>★ 判据只看 {@code state}，**不再要求 UserService 已绑定**：
     * 主路径是 {@code IShizukuService.newProcess}（见 {@link #exec}），
     * 它不需要绑定任何东西。UserService 只是回退路径。
     * 第一版把 {@code userService != null} 也当成必要条件，于是在
     * 绑定不可靠的设备上（ShizukuX 双服务端 bug）读数永远出不来。
     */
    public static boolean ready() {
        return state == State.READY;
    }

    /**
     * 以提权身份执行命令。
     *
     * <h3>★ 主路径：直接调 {@code IShizukuService.newProcess}</h3>
     * 公开 API 的 {@code Shizuku.newProcess} 包装在 13.x 被移除了，
     * 但**服务端方法仍在**（javap 实测 {@code IShizukuService} 的 aidl）：
     * <pre>
     *   IRemoteProcess newProcess(String[] cmd, String[] env, String dir)
     * </pre>
     * 于是可以自己拿 {@code Shizuku.getBinder()} 转成 {@code IShizukuService}
     * 直接调用，**完全绕开 UserService**。
     *
     * <p>为什么必须绕：用户真机装的是 ShizukuX（root 改版），它有**两个
     * {@code shizuku_plus_server} 进程**（22667 / 22711）。服务记录建在其中一个，
     * attach 却路由到另一个，于是日志里出现"自己刚创建的 token 自己找不到"：
     * <pre>
     *   PID 22667: New service record ... (7a3c2d23-...)
     *   PID 22711: attachUserService: unable to find token 7a3c2d23-...
     * </pre>
     * 这是**该改版的 bug**，不是我们代码的问题。UserService 路径因此时通时不通，
     * 还会在设备上累积 root 的 {@code :fps} 孤儿进程（adb 权限不足杀不掉）。
     * {@code newProcess} 不落任何常驻进程，天然没有这些问题。
     *
     * <p>失败时回退到 UserService 路径（官方 Shizuku 上它工作正常）。
     *
     * @return stdout；不可用或失败返回 null（调用方据此降级，不要抛给 UI）
     */
    public static String exec(String cmd) {
        String out = execViaNewProcess(cmd);
        if (out != null) return out;
        // 回退：UserService 路径
        final IFpsUserService s = userService;
        if (s == null) return null;
        // ★ 回退路径也要有超时：它是一次同步 Binder 调用，对面（我们自己的
        //   FpsUserService）若卡在 Runtime.exec 的读取上，这里同样会**永久阻塞**，
        //   采样线程一停，挂牌数字就冻住（见 EXEC_TIMEOUT_MS 的说明）。
        final String[] box = new String[1];
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try { box[0] = s.exec(cmd); } catch (Throwable ignore) {}
            }
        }, "fps-exec-user");
        t.setDaemon(true);
        t.start();
        try { t.join(EXEC_TIMEOUT_MS); } catch (InterruptedException ignore) {}
        if (t.isAlive()) {
            Log.w(TAG, "UserService exec 超时 " + EXEC_TIMEOUT_MS + "ms，放弃本次取数");
            return null;
        }
        return box[0];
    }

    /**
     * 单条命令的最长等待（毫秒）。超过就杀掉 shell 进程并判失败。
     *
     * <h3>★ 为什么必须有超时（真机 bug 的教训）</h3>
     * {@code readAll()} 是**阻塞**读，读的是 shell 进程的 stdout —— 只要那个
     * 进程不退出（dumpsys 偶发卡死、系统负载高、被 SELinux 挡住半途），
     * 读取就永远不返回：采样线程停在这一次调用上，**既没有回调、也没有异常**，
     * 表现就是用户反馈的"帧率监测卡住不动"（线程还活着，从外面看不出问题）。
     * 有了看门狗，最坏情况也只是这一轮取数失败，下一轮照常。
     */
    private static final long EXEC_TIMEOUT_MS = 5000;

    /** 走 {@code IShizukuService.newProcess} 直接跑命令。 */
    private static String execViaNewProcess(String cmd) {
        IRemoteProcess proc = null;
        final java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean killed =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        // fd 用数组持有：看门狗要能在 destroy() 之后补一刀关掉它，
        // 确保阻塞在 read() 上的线程一定被唤醒（否则线程仍会永久卡住）。
        final android.os.ParcelFileDescriptor[] fdHolder =
                new android.os.ParcelFileDescriptor[1];
        Thread killer = null;
        try {
            android.os.IBinder b = Shizuku.getBinder();
            if (b == null) return null;
            IShizukuService svc = IShizukuService.Stub.asInterface(b);
            if (svc == null) return null;
            // ★ 必须用 sh -c 包一层：直接传字符串数组不走 shell，管道/引号全失效
            proc = svc.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            if (proc == null) return null;

            android.os.ParcelFileDescriptor fd = proc.getInputStream();
            if (fd == null) return null;
            fdHolder[0] = fd;

            final IRemoteProcess p = proc;
            killer = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(EXEC_TIMEOUT_MS); } catch (InterruptedException e) { return; }
                    if (finished.get()) return;
                    killed.set(true);
                    Log.w(TAG, "命令超时 " + EXEC_TIMEOUT_MS + "ms，强杀 shell 进程");
                    try { p.destroy(); } catch (Throwable ignore) {}
                    try { if (fdHolder[0] != null) fdHolder[0].close(); } catch (Throwable ignore) {}
                }
            }, "fps-exec-watchdog");
            killer.setDaemon(true);
            killer.start();

            java.io.InputStream in =
                    new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd);
            String out = readAll(in);
            try { proc.waitFor(); } catch (Throwable ignore) {}
            // ★ 超时后的输出是**残缺的**：宁可当失败（UI 报"读取帧数据失败"），
            //   也不能把半截 dump 拿去解析 —— 那会算出一个假的帧率。
            if (killed.get()) return null;
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "newProcess 路径失败，将回退 UserService: " + t);
            return null;
        } finally {
            finished.set(true);
            if (killer != null) killer.interrupt();
            if (proc != null) {
                try { proc.destroy(); } catch (Throwable ignore) {}
            }
        }
    }

    private static String readAll(java.io.InputStream in) throws java.io.IOException {
        if (in == null) return null;
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        in.close();
        return sb.toString();
    }

    /** 面向用户的引导文案。 */
    public static String hint(Context ctx) {
        switch (state) {
            case NOT_INSTALLED:
                return "未安装 Shizuku。装好并启动后，这里就能显示游戏实时帧率。";
            case NOT_RUNNING:
                return "Shizuku 未运行。请打开 Shizuku 并按提示启动服务"
                        + "（重启手机后需要重新激活）。";
            case NOT_GRANTED:
                return "已安装 Shizuku，点下方按钮授权即可读取帧率。";
            default:
                // ★ READY 只说明 Shizuku 侧就绪，取数服务是另一件事（异步绑定）。
                //   真机上出现过"状态已就绪、读数却说未就绪"的矛盾界面，
                //   所以这里把取数服务的状态一并说出来。
                switch (bindState()) {
                    case BINDING:
                        return "Shizuku 已就绪，正在连接取数服务…";
                    case BOUND:
                        return "已就绪，正在读取帧率。";
                    case FAILED:
                        return "Shizuku 已就绪，但取数服务连接失败："
                                + (bindError.isEmpty() ? "未知原因" : bindError);
                    default:
                        return "Shizuku 已就绪。点下方按钮连接取数服务。";
                }
        }
    }
}
