package com.deepseek.pet.dsh.linux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 前台服务：让宿主在切后台后继续跑，并对外提供回环控制接口。
 *
 * <p>主 App 的用法：显式 {@code startForegroundService(Intent(ctx, EngineService.class))}
 * —— 不带任何参数，服务自己"装（幂等）→ 起宿主 → 报告地址"。
 */
public class EngineService extends Service {

    private static final String CHANNEL = "dsh-linux-engine";

    private static Engine engine;
    private static ControlServer control;

    /** 给同进程的界面/控制接口用。 */
    public static synchronized Engine engine(Context c) {
        if (engine == null) engine = new Engine(c.getApplicationContext());
        return engine;
    }

    static synchronized ControlServer control(Context c) {
        if (control == null) control = new ControlServer(engine(c));
        return control;
    }

    @Override public void onCreate() {
        super.onCreate();
        startForeground(1, notification("准备中…"));
        // ★ 不在这里 start()：onStartCommand 才知道这次要不要先下载（可能带 source）。
        //   以前在这里起会导致"先报一次失败再开始下载"，状态一闪一闪的。
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Engine e = engine(this);
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if ("stop".equals(action)) {
            e.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // 可选：由拉起方直接带下载源（主 App / 自动化验收用），省掉在界面里手填
        // ★ 载荷就在本 App 的 assets 里：没装过就先从包里解，装过就直接起。
        //   （并进主 App 之后没有"第二个包"、也不需要 ContentProvider / 系统安装器。）
        if (!EngineProot.available(this)) {
            new Thread(new Runnable() {
                @Override public void run() {
                    Engine e2 = engine(EngineService.this);
                    if (e2.download("asset://engine")) e2.start();
                }
            }, "engine-from-assets").start();
            return START_STICKY;
        }
        String source = intent == null ? null : intent.getStringExtra("source");
        if (source != null && source.trim().length() > 0 && !EngineProot.available(this)) {
            final String s = source.trim();
            new Thread(new Runnable() {
                @Override public void run() {
                    // 带了源就是要"装好并跑起来"：下载+解包成功之后接着起宿主。
                    // 只装不起的话，拉起方还得再来一次，容易漏（自动化验收第一版就漏了）。
                    if (e.download(s)) e.start();
                }
            }, "engine-download").start();
            return START_STICKY;
        }
        e.start();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        Engine e = engine(this);
        e.stop();
        if (control != null) control.stop();
        super.onDestroy();
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "DSH Linux 引擎",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("DSH Linux 引擎")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .build();
    }
}
