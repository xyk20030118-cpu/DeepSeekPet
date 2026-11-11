package com.deepseek.pet.dsh;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 定时任务的闹钟接线 —— 把 {@link DshSchedule} 挂到 AlarmManager 上。
 *
 * <h3>为什么用"一次性闹钟 + 每次重挂"而不是 setRepeating</h3>
 * <ul>
 *   <li>{@code setRepeating} 在 Doze 下会被推迟甚至攒在一起，时间会越漂越远；</li>
 *   <li>我们要的是"固定格子"（03:00 / 03:05 / 03:10），所以每次触发后按
 *       {@link DshSchedule#nextAfter} 重算下一格再挂 —— 与上游 dsh-schedule 的算术一致；</li>
 *   <li>Android 12+ 的精确闹钟要权限，这里用 {@code setAndAllowWhileIdle}（不精确但在 Doze 里也能响），
 *       对一个"每 5 分钟跑一次 agent"的功能足够，且不需要任何新权限。</li>
 * </ul>
 *
 * <p>★ 闹钟**不跨重启**：手机重启后要重新挂。工作台打开时会 {@link #syncAll} 一次，
 * 所以"重启后打开一次工作台"就能恢复；这一点在界面上明说，不假装闹钟能自己活过来。
 */
public final class DshScheduleRunner {

    private DshScheduleRunner() {}

    /** 把一条定时任务挂上（enabled=false 就撤掉）。 */
    public static void register(Context ctx, DshSchedule s) {
        if (ctx == null || s == null) return;
        AlarmManager am = alarm(ctx);
        if (am == null) return;
        PendingIntent pi = pi(ctx, s.id);
        if (!s.enabled) {
            try { am.cancel(pi); } catch (Throwable ignore) { }
            return;
        }
        long at = s.nextRunAt > 0
                ? s.nextRunAt
                : s.nextAfter(System.currentTimeMillis());
        if (at <= 0) return;   // 一次性且已过期：没有下一次可挂
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable ignore) {
            // 挂不上不炸：列表里"下次运行"仍然显示，用户点「立即跑一次」还能用
        }
    }

    public static void cancel(Context ctx, String id) {
        if (ctx == null || id == null) return;
        AlarmManager am = alarm(ctx);
        if (am == null) return;
        try { am.cancel(pi(ctx, id)); } catch (Throwable ignore) { }
    }

    /** 全部重挂（工作台打开时调；手机重启后靠这一次恢复）。 */
    public static void syncAll(Context ctx) {
        if (ctx == null) return;
        long now = System.currentTimeMillis();
        for (DshSchedule s : DshBridge.schedules(ctx)) {
            if (!s.enabled) continue;
            // ★ 与上游一致：重启后**补最近错过的那一次**（不追补全部历史次数），
            //   一次性规则投递完就不再排；重复规则重算下一次。
            if (s.nextRunAt > 0 && s.nextRunAt <= now) {
                s.recordDelivery(now, s.nextRunAt);
                s.nextRunAt = s.nextAfter(now);
                if (s.nextRunAt <= 0) s.enabled = false;
                DshBridge.saveSchedule(ctx, s);
                com.deepseek.pet.DshTaskCoordinator.get(ctx).submit(s.prompt, s.sessionId());
                continue;
            }
            register(ctx, s);
        }
    }

    private static AlarmManager alarm(Context ctx) {
        return (AlarmManager) ctx.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent pi(Context ctx, String id) {
        Intent i = new Intent(ctx, DshScheduleReceiver.class);
        i.setAction(DshScheduleReceiver.ACTION_RUN);
        i.putExtra(DshScheduleReceiver.EXTRA_ID, id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx.getApplicationContext(),
                id == null ? 0 : id.hashCode(), i, flags);
    }
}
