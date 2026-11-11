package com.deepseek.pet.dsh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * 本机宿主的前台服务（原生直跑那条路）。
 *
 * <h3>为什么必须改成服务</h3>
 * 之前宿主是 Activity 里起的 {@link DshNodeProcess} —— **退出工作模式、切图、旋转重建都会把它杀掉**，
 * 于是"用着好好的宿主突然没了"。改成前台服务之后：界面进出只是重新连上同一个地址，
 * 宿主继续跑，通知栏里能看见它在跑。
 *
 * <h3>与「Linux 引擎」的分工</h3>
 * <ul>
 *   <li>本服务 = 原生直跑（APK 自带 Node + 包树），离线可用，但没有 bash；</li>
 *   <li>{@code com.deepseek.pet.linux} 那个独立包 = 完整 Linux（proot + Ubuntu），
 *       宿主天然就在前台服务里。</li>
 * </ul>
 * 两条都由界面按"远端 → 引擎 → 原生"的顺序挑一条，起不来就往下落。
 */
public class DshHostService extends Service {

    /** 启动宿主。 */
    public static final String ACTION_START = "com.deepseek.pet.dsh.host.START";

    /** 停止宿主。 */
    public static final String ACTION_STOP = "com.deepseek.pet.dsh.host.STOP";

    private static final String CHANNEL = "dsh-host";
    private static final int NOTI_ID = 7301;

    private DshNodeProcess node;

    @Override public void onCreate() {
        super.onCreate();
        startForegroundSafely("正在启动本机宿主…");
        DshHostState.reset();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : String.valueOf(intent.getAction());
        if (ACTION_STOP.equals(action)) {
            stopHost();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (node != null && node.running()) {
            // 已经在跑：只把状态补回通知，不重启（界面重进时就会走这条）
            DshHostState.setRunning(true);
            return START_STICKY;
        }
        startHost();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        // 服务被系统回收时不要把宿主留下（START_STICKY 会重新拉起服务，届时再起宿主）
        stopHost();
        super.onDestroy();
    }

    // ==================== 起停 ====================

    private void startHost() {
        final DshHarnessInstaller ins = new DshHarnessInstaller(getFilesDir());
        if (!ins.isInstalled()) {
            DshHostState.setError("还没装包树");
            updateNotification("本机宿主未运行（还没装包树）");
            stopSelf();
            return;
        }
        if (!DshNodeProcess.available(this)) {
            DshHostState.setError("这只包里没有 Node 运行时");
            updateNotification("本机宿主未运行（没有 Node 运行时）");
            stopSelf();
            return;
        }
        File cli = ins.cliEntry();
        File home = ins.homeDir();
        node = new DshNodeProcess();
        final boolean ok = node.startWeb(this, cli, home, new DshNodeProcess.Sink() {
            @Override public void onLine(String line) {
                DshHostState.append(line);
            }

            @Override public void onUrl(String url) {
                DshHostState.setUrl(url);
                DshHostState.setRunning(true);
                DshHostState.setError("");
                updateNotification("宿主运行中：" + shortUrl(url));
            }

            @Override public void onExit(int code) {
                DshHostState.setRunning(false);
                DshHostState.setError("宿主退出（code=" + code + "）");
                updateNotification("本机宿主已退出（code=" + code + "）");
            }
        });
        if (!ok) {
            DshHostState.setError("启动失败");
            updateNotification("本机宿主启动失败");
        } else {
            DshHostState.setRunning(true);
        }
    }

    private void stopHost() {
        if (node != null) {
            node.stop();
            node = null;
        }
        DshHostState.setRunning(false);
    }

    private static String shortUrl(String u) {
        if (u == null) return "";
        int q = u.indexOf('?');
        return q > 0 ? u.substring(0, q) : u;
    }

    // ==================== 通知 ====================

    private String ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "本机宿主",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return CHANNEL;
    }

    private void startForegroundSafely(String text) {
        try {
            String chId = ensureChannel();
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, chId)
                    : new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
            b.setContentTitle("DeepSeek Harness")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setWhen(0);
            startForeground(NOTI_ID, b.build());
        } catch (Throwable ignore) {
            // 通知被关掉也不该影响宿主：服务照样跑
        }
    }

    private void updateNotification(String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String chId = ensureChannel();
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, chId)
                    : new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
            b.setContentTitle("DeepSeek Harness")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setWhen(0);
            nm.notify(NOTI_ID, b.build());
        } catch (Throwable ignore) {
        }
    }
}
