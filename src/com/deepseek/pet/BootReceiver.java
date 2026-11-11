package com.deepseek.pet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启。
 *
 * Android 从没允许应用「绕过系统直接把自己拉起来」：
 * BOOT_COMPLETED 只是给应用一个「系统起来了」的通知，
 * 真正决定能不能起来的是厂商 ROM 的自启动白名单。
 * 所以本接收器的价值是：只要用户给了白名单，开机就能自动把桌宠唤回；
 * 没给白名单时什么都不会发生（系统不发这个广播给被禁的应用）。
 *
 * 尊重用户意愿：只有用户上次是「开着桌宠」的状态才自动拉起。
 * 用户主动「退出桌宠」后不会在重启后突然又冒出来。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DSPBoot";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();

        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
        boolean isUpdate = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!isBoot && !isUpdate) return;

        try {
            // ★ 开机时顺便把本机 Linux 宿主拉起来（引擎已并进本 App）：
            //   以前这条是靠"引擎包自己的 BootReceiver"，合并后只能有**一个** BOOT_COMPLETED
            //   接收器（两个的话，反向项"删掉 BOOT_COMPLETED"就抓不到，等于断言是空的）。
            try {
                if (com.deepseek.pet.dsh.DshEngine.installed(ctx)) {
                    com.deepseek.pet.dsh.DshEngine.start(ctx);
                }
            } catch (Throwable ignore) {
            }
            // 更新安装后系统会杀进程，这里顺势把桌宠拉回来
            if (isUpdate) {
                Log.i(TAG, "应用已更新，尝试恢复桌宠");
            } else {
                Log.i(TAG, "开机广播 " + action + "，检查是否需要自启");
            }

            if (!Prefs.petShouldRun(ctx)) {
                Log.i(TAG, "上次是主动退出状态，不自动启动");
                return;
            }
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                Log.i(TAG, "缺少悬浮窗权限，跳过（启动也没有意义）");
                return;
            }

            Intent svc = new Intent(ctx, FloatingService.class);
            svc.setAction(FloatingService.ACTION_RESTART);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(svc);
            } else {
                ctx.startService(svc);
            }
            Log.i(TAG, "已请求启动桌宠服务");
        } catch (Throwable t) {
            // 后台启动受限时系统会抛异常，属于正常情况，不crash
            Log.w(TAG, "自启动失败（多半是系统后台限制）: " + t);
        }
    }
}
