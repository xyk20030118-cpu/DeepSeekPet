package com.deepseek.pet;

import android.content.Context;

import com.deepseek.pet.dsh.DshBackend;
import com.deepseek.pet.dsh.DshLocalBackend;
import com.deepseek.pet.dsh.DshTask;
import com.deepseek.pet.dsh.DshTaskStore;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>任务协调器</b> —— 方案 02 的「Task Coordinator」：让**任务的生命周期独立于 Activity**。
 *
 * <h3>它解决的三个现实问题</h3>
 * <ol>
 *   <li><b>离开工作台就取消任务</b>（改之前 `DshWorkActivity.onDestroy()` 里直接 `cancelCurrent()`）：
 *       现在执行归它管，页面只是"订阅者"；页面销毁只 detach，不碰任务。</li>
 *   <li><b>连点/重建重复提交</b>：任务有稳定 id，同一时刻只有一个活动任务；运行中再发只进队列
 *       （{@link #enqueue}），不会起第二个 agent。</li>
 *   <li><b>进程被杀后"到底跑没跑完"</b>：{@link DshTaskStore#recover()} 把未终结任务标成
 *       `UNKNOWN_AFTER_DISCONNECT`（显示「状态待确认」），**不自动重跑**。</li>
 * </ol>
 *
 * <h3>队列的持久化规则（明确写死，方案 03 要求）</h3>
 * 队列**不跨进程持久化**：进程被杀时队列清空，任务标"待确认"。
 * 理由：重启后按旧队列继续，等于把可能带副作用的操作**重放一遍**，风险大于收益。
 *
 * <p>★ 单例按进程；所有状态变更都在 {@code synchronized (this)} 里，UI 回调由调用方切线程。
 */
public final class DshTaskCoordinator {

    /** 订阅者（工作台是其中一个；将来桌宠气泡也可以订阅）。 */
    public interface Listener {
        /** 任务快照变化（含状态文字）；task 为 null 表示没有任务。 */
        void onTask(DshTask snapshot);

        /** 队列长度变化。 */
        void onQueue(int size);

        void onText(String text, int tokens, long millis);

        void onTool(String name, String args, boolean isError, String result, long millis);

        void onError(String message);
    }

    private static volatile DshTaskCoordinator instance;

    public static DshTaskCoordinator get(Context ctx) {
        if (instance == null) {
            synchronized (DshTaskCoordinator.class) {
                if (instance == null) instance = new DshTaskCoordinator(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context ctx;
    private final DshTaskStore store;
    private DshBackend backend;
    private DshTask current;
    private final List<String> queue = new ArrayList<String>();
    private final List<Listener> listeners = new ArrayList<Listener>();

    private DshTaskCoordinator(Context ctx) {
        this.ctx = ctx;
        this.store = new DshTaskStore(new File(ctx.getFilesDir(), "dsh/tasks"));
        this.backend = new DshLocalBackend(ctx);
        // ★ 启动即恢复：未终结的任务标"状态待确认"，绝不自动重跑
        this.current = store.recover();
    }

    // ==================== 订阅 ====================

    public void attach(Listener l) {
        if (l == null) return;
        synchronized (this) {
            if (!listeners.contains(l)) listeners.add(l);
        }
        // 立刻给一次当前快照，页面重建后不用等下一次事件
        Listener[] arr;
        synchronized (this) {
            arr = listeners.toArray(new Listener[0]);
        }
        for (Listener x : arr) {
            x.onTask(snapshot());
            x.onQueue(queue.size());
        }
    }

    public void detach(Listener l) {
        synchronized (this) {
            listeners.remove(l);
        }
    }

    /** 当前任务快照（拷贝一份，UI 改了也不影响内部状态）。 */
    public synchronized DshTask snapshot() {
        if (current == null) return null;
        DshTask c = new DshTask(current.id, current.sessionId, current.text, current.createdAt);
        c.state = current.state;
        c.result = current.result;
        c.error = current.error;
        c.steps = current.steps;
        c.updatedAt = current.updatedAt;
        return c;
    }

    public synchronized int queueSize() {
        return queue.size();
    }

    public synchronized boolean isRunning() {
        return current != null && current.active();
    }

    public DshBackend backend() {
        return backend;
    }

    /** 换后端（Phase 4 的入口；现在只有 local，留着这一行是为了"UI 不依赖具体后端"）。 */
    public synchronized void useBackend(DshBackend b) {
        if (b != null) backend = b;
    }

    // ==================== 提交 / 排队 ====================

    /**
     * 提交一条用户输入。
     *
     * @return true = 已开始执行；false = 已有任务在跑，**已排队**
     */
    public boolean submit(String text, String sessionId) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) return false;
        DshTask task;
        synchronized (this) {
            if (current != null && current.active()) {
                queue.add(t);
                int n = queue.size();
                dispatchQueue(n);
                return false;
            }
            task = new DshTask(DshTask.newId(), sessionId, t, System.currentTimeMillis());
            task.setState(DshTask.RUNNING);
            current = task;
            store.save(task);
        }
        dispatchTask();
        run(task);
        return true;
    }

    /** 运行中再发一条 → 进队列（方案的"排队发送"）。 */
    public boolean enqueue(String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() == 0) return false;
        synchronized (this) {
            if (current == null || !current.active()) return false;
            queue.add(t);
            int n = queue.size();
            dispatchQueue(n);
            return true;
        }
    }

    /** 停止当前任务：**传到执行层**（不是只改 UI）。 */
    public void cancel() {
        DshTask task;
        synchronized (this) {
            if (current == null || !current.active()) return;
            if (current.state == DshTask.CANCELLING) return;
            current.setState(DshTask.CANCELLING);
            task = current;
            store.save(task);
        }
        dispatchTask();
        if (backend != null) backend.cancel(task.id, null);
    }

    // ==================== 执行 ====================

    private void run(final DshTask task) {
        DshBackend.Request req = new DshBackend.Request(task.id, task.sessionId, task.text);
        backend.submit(req, new DshBackend.Callback() {
            @Override public void onRunning(int step) {
                synchronized (DshTaskCoordinator.this) {
                    if (current == null || !current.id.equals(task.id)) return;
                    if (step > 0) current.steps = step;
                }
                dispatchTask();
            }

            @Override public void onText(String text, int tokens, long millis) {
                Listener[] arr = listenersCopy();
                for (Listener l : arr) l.onText(text, tokens, millis);
            }

            @Override public void onTool(String name, String args, boolean isError,
                                         String result, long millis) {
                Listener[] arr = listenersCopy();
                for (Listener l : arr) l.onTool(name, args, isError, result, millis);
            }

            @Override public void onDone(boolean ok, String text, String error) {
                finish(task, ok, text, error);
            }
        });
    }

    private void finish(DshTask task, boolean ok, String text, String error) {
        String next = null;
        synchronized (this) {
            if (current == null || !current.id.equals(task.id)) return;
            boolean wasCancelling = current.state == DshTask.CANCELLING;
            current.result = text == null ? "" : text;
            current.error = error == null ? "" : error;
            if (wasCancelling || "已停止".equals(current.error)) {
                current.setState(DshTask.CANCELLED);
            } else {
                current.setState(ok ? DshTask.SUCCEEDED : DshTask.FAILED);
            }
            store.save(current);
            // 取消过的任务不再把队列倒出来（避免"点了停止又接着跑"）
            if (current.state != DshTask.CANCELLED && !queue.isEmpty()) {
                next = queue.remove(0);
            }
        }
        if (!ok && error != null && error.length() > 0) {
            Listener[] arr = listenersCopy();
            for (Listener l : arr) l.onError(error);
        }
        dispatchTask();
        dispatchQueue(queueSize());
        if (next != null) {
            // 队列里的下一条：新任务、同一会话
            submit(next, task.sessionId);
        }
    }

    // ==================== 内部：通知 ====================

    private Listener[] listenersCopy() {
        synchronized (this) {
            return listeners.toArray(new Listener[0]);
        }
    }

    private void dispatchTask() {
        DshTask snap = snapshot();
        for (Listener l : listenersCopy()) l.onTask(snap);
    }

    private void dispatchQueue(int n) {
        for (Listener l : listenersCopy()) l.onQueue(n);
    }
}
