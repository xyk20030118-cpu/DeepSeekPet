package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.List;

/**
 * 后端抽象 —— 方案 02 里那张图中间那层（`Local backend | Remote backend`）。
 *
 * <h3>为什么先做接口、只实现 Local</h3>
 * 方案 06 把"电脑端后端"排到 Phase 4，但也明确要求 Phase 3 先"定义最小接口、包装现有本地核心、
 * 保持行为不变"。所以这里只定**调用形状**，`DshLocalBackend` 是唯一实现；
 * Phase 4 的 RemoteBackend 将来接同一套 `submit/query/cancel + 能力清单 + 健康状态`，
 * UI 一行都不用改。
 *
 * <h3>★ 三条设计取舍（写清楚，免得后面有人"顺手改坏"）</h3>
 * <ol>
 *   <li><b>任务 id 由调用方给</b>（{@link Request#taskId}）：这样"重试/查询/取消"永远指同一个任务，
 *       而不是每次 submit 生成新 id —— 幂等与"断线先查"都靠它；</li>
 *   <li><b>能力清单是数据不是开关</b>（{@link Capabilities}）：UI 只读它，不能靠一个开关伪造能力
 *       （方案 05 的原话：「不能只用一个开关表达 Root 已开启」）；</li>
 *   <li><b>回调只报事实</b>：{@code onDone(ok, text, error)} 不吞错、不把失败说成完成。</li>
 * </ol>
 */
public interface DshBackend {

    /** 后端标识："local" / "desktop"。 */
    String getBackendId();

    /** 现在能不能用（含"未配对""连不上"这类原因）。 */
    Status getStatus();

    /** 这个后端支持什么（工具 + 特性）。UI 只读，不能伪造。 */
    Capabilities getCapabilities();

    /** 提交一个任务（同一个 taskId 重复提交**不应**重复执行 —— 由实现保证幂等）。 */
    void submit(Request request, Callback callback);

    /** 查询任务状态（断线后先查、不要盲目重提）。 */
    void query(String taskId, Callback callback);

    /** 请求取消（必须传到真实执行层，不能只改 UI 状态）。 */
    void cancel(String taskId, Callback callback);

    // ==================== 数据形状 ====================

    /** 后端健康状态。 */
    final class Status {
        public final boolean ready;
        public final String detail;

        public Status(boolean ready, String detail) {
            this.ready = ready;
            this.detail = detail == null ? "" : detail;
        }

        public static Status ok() { return new Status(true, ""); }

        public static Status notReady(String why) { return new Status(false, why); }

        @Override public String toString() { return ready ? "READY" : ("NOT_READY:" + detail); }
    }

    /** 能力清单（方案 05：能力 ≠ 授权 ≠ 审批，这里只描述"这个后端能做到什么"）。 */
    final class Capabilities {
        public final List<String> tools = new ArrayList<String>();
        public final List<String> features = new ArrayList<String>();
        /** 是不是远端（远端要额外标注"内容不可信、需鉴权"）。 */
        public final boolean remote;

        public Capabilities(boolean remote) {
            this.remote = remote;
        }

        @Override public String toString() {
            return (remote ? "remote" : "local") + " tools=" + tools.size() + " features=" + features.size();
        }
    }

    /** 一次提交。 */
    final class Request {
        public final String taskId;
        public final String sessionId;
        public final String text;

        public Request(String taskId, String sessionId, String text) {
            this.taskId = taskId == null ? "" : taskId;
            this.sessionId = sessionId == null ? "" : sessionId;
            this.text = text == null ? "" : text;
        }
    }

    /** 执行过程中的事实回调。 */
    interface Callback {
        void onRunning(int step);

        void onText(String text, int tokens, long millis);

        void onTool(String name, String args, boolean isError, String result, long millis);

        /** 结束（成功/失败都走这里，失败必须带 error）。 */
        void onDone(boolean ok, String text, String error);
    }
}
