package com.deepseek.pet;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.List;

/**
 * 主动记忆点维护：主对话成功后，异步调用一次轻量模型调用，
 * 把「已有记忆点 + 最近对话」整理成新的精简事实清单并落盘。
 *
 * 设计目标：把 token 消耗从「随历史线性增长」变成「近似恒定」——
 *   - 历史只带最近 N 条（N 可调）；
 *   - 记忆点始终是一小段固定上限的摘要；
 *   - 整理调用 max_tokens=200，且做了节流（默认每 3 轮才整理一次）。
 */
public class MemoryFacts {

    private static final String TAG = "DSMemFacts";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 摘要整理节流：每隔多少轮对话才触发一次（避免每轮都多花一次调用） */
    private static final int UPDATE_EVERY_TURNS = 3;
    /** 连续失败几次后暂时不再尝试（避免网络差时反复打接口） */
    private static int consecutiveFails = 0;
    private static final int MAX_FAILS = 3;

    /**
     * 在主对话成功后调用。会自行判断是否该整理、并起后台线程。
     *
     * @param recentSnapshot 本轮用于参考的最近往来（含刚才这轮）
     */
    public static void maybeUpdateAsync(final Context ctx, final String apiKey,
                                        final String model, final String persona,
                                        final List<String[]> recentSnapshot) {
        if (ctx == null) return;
        if (!ChatMemory.enabled(ctx) || !ChatMemory.factsEnabled(ctx)) return;
        if (apiKey == null || apiKey.trim().length() == 0) return;
        if (consecutiveFails >= MAX_FAILS) return;

        // 节流：按 history 条数粗略判断轮次
        int turns = ChatMemory.size(ctx) / 2;
        if (turns <= 0) return;
        if (turns % UPDATE_EVERY_TURNS != 0) return;

        final String appCtx = ctx.getApplicationContext() instanceof Context
                ? ctx.getApplicationContext().getPackageName() : null;
        final Context app = ctx.getApplicationContext();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String old = ChatMemory.facts(app);
                    final String out = ApiClient.extractFacts(apiKey, model, persona, old, recentSnapshot);
                    if (out == null) {
                        consecutiveFails++;
                        return;
                    }
                    consecutiveFails = 0;
                    ChatMemory.setFacts(app, out);
                    Log.i(TAG, "facts updated: " + out);
                } catch (Exception e) {
                    consecutiveFails++;
                }
            }
        }, "pet-facts-update").start();
    }

    /** 手动触发一次整理（设置页按钮用），忽略节流 */
    public static void updateNowAsync(final Context ctx, final String apiKey,
                                      final String model, final String persona) {
        if (ctx == null) return;
        if (apiKey == null || apiKey.trim().length() == 0) return;
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String old = ChatMemory.facts(app);
                    final List<String[]> recent = ChatMemory.load(app);
                    int from = Math.max(0, recent.size() - Math.max(6, ChatMemory.ctxLimit(app)));
                    final String out = ApiClient.extractFacts(apiKey, model, persona, old,
                            new java.util.ArrayList<>(recent.subList(from, recent.size())));
                    if (out != null) {
                        consecutiveFails = 0;
                        ChatMemory.setFacts(app, out);
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                android.widget.Toast.makeText(app,
                                        "记忆点已更新（" + ChatMemory.factsCount(app) + " 条）",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        MAIN.post(new Runnable() {
                            @Override public void run() {
                                android.widget.Toast.makeText(app, "整理失败，请检查网络/Key",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (Exception ignore) { }
            }
        }, "pet-facts-now").start();
    }
}
