package com.deepseek.pet.dsh;

import android.content.Context;

import com.deepseek.pet.ApiClient;

/**
 * 手机本地后端 —— 把**现有**的 agent 内核包成 {@link DshBackend}，行为保持不变。
 *
 * <h3>这一层做什么、不做什么</h3>
 * <ul>
 *   <li>做什么：把一次 {@code submit} 翻译成"跑一轮 {@link DshBridge#chat}"，
 *       并把内核的回调翻译成 {@link DshBackend.Callback}；</li>
 *   <li>不做什么：**不碰会话格式、不改工具集、不改提示词** —— 那些还是
 *       {@code DshBridge} / {@code DshAgentLoop} 的事。这样 Phase 3 的风险最小：
 *       旧行为一条都没有变，只是多了一层"谁来调它"。</li>
 * </ul>
 *
 * <p>★ 线程：{@link #submit} **自己开线程**跑（阻塞调用），调用方（Coordinator）不用管线程。
 * <p>★ 取消：{@link #cancel} 走 {@code DshBridge.cancelCurrent()} —— 它作用在**正在跑的那一轮**上
 * （内核在 step 之间生效；正在等的 HTTP 请求要等它回来，这点在内核里已写明）。
 */
public final class DshLocalBackend implements DshBackend {

    private final Context ctx;

    public DshLocalBackend(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override public String getBackendId() {
        return "local";
    }

    @Override public Status getStatus() {
        if (!DshBridge.enabled(ctx)) return Status.notReady("Harness 总开关未开");
        String key = com.deepseek.pet.Prefs.getApiKey(ctx);
        if (key == null || key.trim().length() == 0) return Status.notReady("还没填 API Key");
        return Status.ok();
    }

    @Override public Capabilities getCapabilities() {
        Capabilities cap = new Capabilities(false);
        java.util.Set<String> on = DshBridge.plugins(ctx);
        cap.tools.addAll(DshPlugin.visibleTools(on));
        cap.features.add("sessions");
        cap.features.add("tools");
        cap.features.add("skills");
        cap.features.add("compaction");
        cap.features.add("cancel");
        return cap;
    }

    @Override public void submit(Request request, final Callback callback) {
        final String text = request == null ? "" : request.text;
        // ★ 会话路由：协调器给的 sessionId 必须传下去（定时任务有自己的会话，
        //   不能因为"用户此刻在看别的会话"就把结果写进用户那条里）。
        final String sessionId = request == null ? "" : request.sessionId;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean[] done = new boolean[1];
                DshAgentLoop.Result r = DshBridge.chatIn(ctx, text, sessionId, new DshBridge.Callback() {
                    @Override public void onText(String t, int tokens, long millis) {
                        if (callback != null) callback.onText(t, tokens, millis);
                    }

                    @Override public void onTool(String name, String args, boolean isError,
                                                 String result, long millis) {
                        if (callback != null) callback.onTool(name, args, isError, result, millis);
                    }

                    @Override public void onDone(DshAgentLoop.Result result) {
                        done[0] = true;
                        if (callback != null) {
                            callback.onDone(result != null && result.ok,
                                    result == null ? "" : result.text,
                                    result == null ? "" : result.error);
                        }
                    }

                    @Override public void onError(String message) {
                        // 内核的 onError 只是"报告"，收尾仍会走 onDone；这里不重复通知
                        if (callback != null) callback.onRunning(-1);
                    }
                });
                if (!done[0] && callback != null) {
                    // 极端情况（chat 提前返回没回调）：也要给一个明确的结束，不能挂住。
                    // ★ 用内核自己给的原因（比如"还没填 API Key"），别把它盖成一句含糊的废话。
                    String why = (r != null && r.error != null && r.error.length() > 0)
                            ? r.error : "执行提前结束，没有拿到结果";
                    callback.onDone(false, r == null ? "" : r.text, why);
                }
            }
        }, "dsh-local-backend").start();
    }

    @Override public void query(String taskId, Callback callback) {
        // 本地后端是"同进程同步执行"：能查到的就是 DshBridge 里那一轮在不在跑
        if (callback != null) {
            callback.onRunning(DshBridge.isRunning() ? 0 : -1);
        }
    }

    @Override public void cancel(String taskId, Callback callback) {
        DshBridge.cancelCurrent();
        if (callback != null) callback.onRunning(-1);
    }
}
