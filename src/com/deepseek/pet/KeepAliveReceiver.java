package com.deepseek.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 看门狗心跳接收器：桌宠被系统清掉后，把它拉回来。
 *
 * 这是「保活」的实际生效点 —— 解决用户反馈的核心痛点：
 * 明明开了自启动，过一阵桌宠还是没了。
 * 原因通常是系统在中途把进程回收了，而厂商自启只管「开机那一次」。
 * 看门狗负责在运行期间持续巡检并自愈。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "DSPKeepAlive";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !KeepAlive.ACTION_TICK.equals(intent.getAction())) return;
        try {
            boolean shouldRun = Prefs.petShouldRun(ctx);
            boolean enabled = Prefs.keepAliveEnabled(ctx);

            Log.i(TAG, "巡检: 看门狗=" + enabled + " 应运行=" + shouldRun
                    + " 服务活着=" + FloatingService.isAlive);

            if (!enabled) {
                Log.i(TAG, "看门狗已被用户关闭，不再安排");
                return;
            }

            if (shouldRun) {
                if (FloatingService.isAlive) {
                    Log.i(TAG, "桌宠仍在运行，无需干预");
                } else if (!KeepAlive.canShowOverlay(ctx)) {
                    Log.i(TAG, "缺少悬浮窗权限，无法恢复");
                } else {
                    // 被系统清掉了 —— 拉回来
                    Log.w(TAG, "检测到桌宠已停止，尝试恢复");
                    Intent svc = new Intent(ctx, FloatingService.class);
                    svc.setAction(FloatingService.ACTION_RESTART);
                    try {
                        if (Build.VERSION.SDK_INT >= 26) {
                            ctx.startForegroundService(svc);
                        } else {
                            ctx.startService(svc);
                        }
                        Log.i(TAG, "已请求恢复桌宠服务");
                    } catch (Throwable t) {
                        // 后台启动前台服务受限时会抛异常，属正常，下轮再试
                        Log.w(TAG, "恢复失败（系统限制后台启动）: " + t);
                    }
                }
            } else {
                Log.i(TAG, "用户已主动退出桌宠，不自动恢复");
            }
        } catch (Throwable t) {
            Log.w(TAG, "巡检异常: " + t);
        } finally {
            // 链式续约：保证看门狗不断。
            // 但用户主动退出后就不再排班 —— 否则会每轮无谓唤醒一次，白耗电。
            try {
                if (Prefs.keepAliveEnabled(ctx) && Prefs.petShouldRun(ctx)) {
                    KeepAlive.scheduleWatchdog(ctx, Prefs.keepAliveIntervalMs(ctx));
                } else {
                    Log.i(TAG, "无需续约（看门狗关闭或用户已退出），看门狗就此停止");
                }
            } catch (Throwable ignore) {}
        }
    }
}
