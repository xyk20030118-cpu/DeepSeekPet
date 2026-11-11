package com.deepseek.pet;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

/**
 * 后台保活 / 看门狗。
 *
 * ── 关于「强行索要自启动权限」的实话 ─────────────────────────
 * 明确结论：Android 不存在能直接打开或强制开启厂商自启动开关的 API。
 * 厂商自启白名单是私有实现，只有各自 Settings 里的私有页面能改，
 * 那些页面既无文档也随时可能被 ROM 改掉，且通常要求用户手动点。
 * 因此任何声称「代码强开自启动」的做法都是假的。
 *
 * 真正有效的是三件事，本类负责把它们的强度拉满：
 *   ① 电池优化豁免 —— 唯一可编程、有系统弹窗的「向系统索要后台许可」；
 *   ② 厂商页直达 —— 用包名 extras 精准定位到本应用那一行，省掉用户瞎翻；
 *   ③ 看门狗自愈 —— 被系统清掉后，定时把自己拉回来（本类核心）。
 *
 * ③ 是「强」的实际来源：即便自启开关没开，只要用户允许过一次后台，
 * 看门狗也能在后续把桌宠复活。
 */
public final class KeepAlive {

    private static final String TAG = "DSPKeepAlive";
    public static final String ACTION_TICK = "com.deepseek.pet.KEEPALIVE_TICK";
    private static final int REQ_TICK = 0x5A01;

    /** 默认巡检间隔：15 分钟。太密会更耗电，太疏则恢复慢 */
    public static final long DEFAULT_INTERVAL_MS = 15 * 60 * 1000L;

    private KeepAlive() {}

    private static PendingIntent tickIntent(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveReceiver.class);
        i.setAction(ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ_TICK, i, flags);
    }

    /**
     * 安排看门狗。用 setAndAllowWhileIdle，Doze 省电模式下也能触发，
     * 这是保活的标准做法（普通 set 在 Doze 下会被推迟到几乎不触发）。
     */
    public static void scheduleWatchdog(Context ctx, long intervalMs) {
        try {
            if (intervalMs <= 0) intervalMs = DEFAULT_INTERVAL_MS;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = System.currentTimeMillis() + intervalMs;
            PendingIntent pi = tickIntent(ctx);
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
            Log.i(TAG, "看门狗已安排，下次巡检 " + (intervalMs / 1000) + "s 后");
        } catch (Throwable t) {
            Log.w(TAG, "安排看门狗失败: " + t);
        }
    }

    public static void cancelWatchdog(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.cancel(tickIntent(ctx));
            Log.i(TAG, "看门狗已取消");
        } catch (Throwable ignore) {}
    }

    /**
     * 把当前能给的后台许可状态汇总成一行，用于 UI 展示。
     */
    public static String statusLine(Context ctx) {
        boolean batt = AutoStartHelper.isIgnoringBatteryOptimizations(ctx);
        boolean alive = FloatingService.isAlive;
        return (batt ? "已豁免电池优化" : "未豁免电池优化")
                + " · 看门狗" + (Prefs.keepAliveEnabled(ctx) ? "已开启" : "未开启")
                + " · 桌宠" + (alive ? "运行中" : "未运行");
    }

    /** 悬浮窗权限是否具备（没有它启动也无意义） */
    public static boolean canShowOverlay(Context ctx) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return Build.VERSION.SDK_INT < 23;
        }
    }
}
