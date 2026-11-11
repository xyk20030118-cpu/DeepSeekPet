package com.deepseek.pet.dsh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.deepseek.pet.DshTaskCoordinator;

import java.io.File;

/**
 * 定时任务触发点 —— 闹钟响了就**交给协调器**跑一轮，然后重挂下一格。
 *
 * <p>★ 为什么不在广播里直接调 {@code DshBridge.chat}：广播有 10 秒执行上限，
 * 而且用户切页面 / 回到桌宠都不该打断任务。走 {@code DshTaskCoordinator}
 * 就是"任务独立于界面"那条纪律（Phase 1）的自然延伸。
 *
 * <p>★ 每次触发都先落盘 {@code lastRunAt / nextRunAt}：进程随时可能被杀，
 * 界面上的「上次 / 下次」必须来自磁盘，而不是内存里的一次性数字。
 */
public final class DshScheduleReceiver extends BroadcastReceiver {

    public static final String ACTION_RUN = "com.deepseek.pet.dsh.SCHEDULE_RUN";
    public static final String EXTRA_ID = "schedule_id";

    @Override public void onReceive(final Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!ACTION_RUN.equals(intent.getAction())) return;
        final String id = intent.getStringExtra(EXTRA_ID);
        if (id == null || id.length() == 0) return;
        final Context app = context.getApplicationContext();
        try {
            DshBridge.init(app);
            DshScheduleStore store = new DshScheduleStore(new File(DshBridge.baseDir(app), "schedules"));
            DshSchedule s = store.load(id);
            if (s == null || !s.enabled) return;
            long now = System.currentTimeMillis();
            // ★ 与上游一致：先记"投递事实"（deliveredAt + 目标时点），再排下一次
            s.recordDelivery(now, s.nextRunAt > 0 ? s.nextRunAt : now);
            s.nextRunAt = s.nextAfter(now);
            if (s.nextRunAt <= 0) {
                // 一次性规则投递完就不再排队（上游：一次性提醒投递后变"未运行"）
                s.enabled = false;
            }
            store.save(s);
            // 交给协调器（幂等：正在跑就排队，不会两条一起跑）
            DshTaskCoordinator.get(app).submit(s.prompt, s.sessionId());
            if (s.enabled) DshScheduleRunner.register(app, s);
        } catch (Throwable ignore) {
            // 广播里的异常绝不能外抛：那会变成"定时任务把 App 弄崩"
        }
    }
}
