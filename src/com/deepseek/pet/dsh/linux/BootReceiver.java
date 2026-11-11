package com.deepseek.pet.dsh.linux;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 开机自启：把引擎拉起来，让宿主在重启后自己回来。
 *
 * <h3>为什么只在"装好了"才拉起</h3>
 * 载荷没装／装一半就开机自启，只会让系统在开机最忙的时候白解一遍 800 MB，
 * 然后必然失败，还会留下半截状态。所以这里先问 {@link EngineProot#available}。
 *
 * <h3>诚实边界</h3>
 * <ul>
 *   <li>部分厂商 ROM（省电策略/自启管理）会拦 {@code BOOT_COMPLETED}，被拦了就是被拦了，
 *       这里没有对抗手段 —— 主 App 打开工作模式时仍然会去拉起引擎，所以功能不会因此失去，
 *       只是"重启后立刻可用"这一点不保证。</li>
 *   <li>只处理 {@code BOOT_COMPLETED}；{@code QUICKBOOT_POWERON} 之类的厂商广播不在范围里。</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            return;
        }
        boolean ready = EngineProot.available(context);
        log(context, "boot: action=" + action + " ready=" + ready
                + " rootfs=" + EngineProot.rootfs(context).getAbsolutePath());
        if (!ready) return;   // 没装好就别在开机时瞎折腾

        // ★ Android 12+ 起，从 BOOT_COMPLETED 里启动"带类型的前台服务"是受限的
        //   （specialUse 不在开机允许的清单里），会抛 ForegroundServiceStartNotAllowedException。
        //   所以：先按前台服务起；被拦了就退回普通 startService（Boot 广播有短暂的
        //   后台启动宽限窗口），两条都失败就如实记下来，交给主 App 打开工作模式时再拉。
        String first = tryStart(context, true);
        if (!"ok".equals(first)) {
            String second = tryStart(context, false);
            log(context, "boot: fgs=" + first + " svc=" + second);
        } else {
            log(context, "boot: fgs=ok");
        }
    }

    private static String tryStart(Context context, boolean foreground) {
        try {
            Intent svc = new Intent(context, EngineService.class);
            if (foreground && Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
            else context.startService(svc);
            return "ok";
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 把结果写进 App 私有目录的 boot.log（没有日志就只能靠猜）。 */
    private static void log(Context c, String line) {
        try {
            java.io.File f = new java.io.File(c.getFilesDir(), "boot.log");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.util.Date().toString() + " " + line + "\n");
            w.close();
        } catch (Throwable ignore) {
        }
    }
}
