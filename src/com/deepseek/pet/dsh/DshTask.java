package com.deepseek.pet.dsh;

import java.util.Map;

/**
 * 一个**任务**的状态机 —— 方案里「任务生命周期独立于 Activity」的那块基石（纯类）。
 *
 * <h3>为什么需要它</h3>
 * 现在的执行发生在工作台页面里（`DshWorkActivity.send()` 里开线程跑 agent loop），
 * 页面一销毁就跟着断 —— 方案把这条列为第一个要修的问题。任务的"存在"必须比页面长，
 * 所以先有一个能落盘、能复原、能表达"不确定"的状态对象。
 *
 * <h3>状态（对齐方案 02 的建议，含"断线/重启后未知"）</h3>
 * {@code CREATED → QUEUED → RUNNING → (SUCCEEDED | FAILED | CANCELLED)}
 * 另有 {@code CANCELLING}（已请求停止、还没停稳）与 {@code UNKNOWN_AFTER_DISCONNECT}
 * （进程被杀/后端断连导致"到底跑没跑完"不知道 —— **这种情况绝不自动重跑**）。
 *
 * <p>★ 纯类：不碰 Android，能被 PC 上的 javac 真编真跑（`build/dshtest` 会验状态机边界）。
 */
public final class DshTask {

    public static final int CREATED = 0;
    public static final int QUEUED = 1;
    public static final int RUNNING = 2;
    public static final int WAITING_FOR_APPROVAL = 3;
    public static final int WAITING_FOR_BACKEND = 4;
    public static final int CANCELLING = 5;
    public static final int SUCCEEDED = 6;
    public static final int FAILED = 7;
    public static final int CANCELLED = 8;
    public static final int UNKNOWN_AFTER_DISCONNECT = 9;

    private static final String[] NAMES = {
            "CREATED", "QUEUED", "RUNNING", "WAITING_FOR_APPROVAL", "WAITING_FOR_BACKEND",
            "CANCELLING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN_AFTER_DISCONNECT"
    };

    public final String id;
    public final String sessionId;
    public final long createdAt;
    public int state;
    public String text = "";
    public String result = "";
    public String error = "";
    public int steps;
    public long updatedAt;

    public DshTask(String id, String sessionId, String text, long now) {
        this.id = id == null || id.length() == 0 ? newId() : id;
        this.sessionId = sessionId == null ? "" : sessionId;
        this.text = text == null ? "" : text;
        this.createdAt = now <= 0 ? System.currentTimeMillis() : now;
        this.updatedAt = this.createdAt;
        this.state = CREATED;
    }

    /** 任务 id：时间在前（文件列表天然按时间排）。 */
    public static String newId() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US);
        return "t-" + f.format(new java.util.Date());
    }

    public static int parse(String name) {
        if (name == null) return CREATED;
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name.trim())) return i;
        }
        return CREATED;
    }

    public static String name(int state) {
        if (state < 0 || state >= NAMES.length) return NAMES[CREATED];
        return NAMES[state];
    }

    /** 终态：不会再变（成功/失败/取消）。 */
    public boolean terminal() {
        return state == SUCCEEDED || state == FAILED || state == CANCELLED;
    }

    /** 是否"还在跑"（含等待确认/等后端）。 */
    public boolean active() {
        return state == CREATED || state == QUEUED || state == RUNNING
                || state == WAITING_FOR_APPROVAL || state == WAITING_FOR_BACKEND
                || state == CANCELLING;
    }

    /** 给人看的一句话（工作台状态条 / 桌宠气泡都用它，别在别处各写一份）。 */
    public String label() {
        switch (state) {
            case QUEUED: return "排队中";
            case RUNNING: return "运行中";
            case WAITING_FOR_APPROVAL: return "等待确认";
            case WAITING_FOR_BACKEND: return "等待电脑端";
            case CANCELLING: return "正在停止";
            case SUCCEEDED: return "已完成";
            case FAILED: return "失败";
            case CANCELLED: return "已取消";
            case UNKNOWN_AFTER_DISCONNECT: return "状态待确认";
            default: return "已创建";
        }
    }

    /** 转到某状态（一律刷新 updatedAt；非法值夹回 CREATED）。 */
    public void setState(int s) {
        this.state = (s < 0 || s >= NAMES.length) ? CREATED : s;
        this.updatedAt = System.currentTimeMillis();
    }

    public Map<String, Object> toMap() {
        return DshJson.map(
                "id", id,
                "sessionId", sessionId,
                "state", name(state),
                "text", text,
                "result", result,
                "error", error,
                "steps", Integer.valueOf(steps),
                "createdAt", Long.valueOf(createdAt),
                "updatedAt", Long.valueOf(updatedAt));
    }

    @SuppressWarnings("unchecked")
    public static DshTask fromMap(Map<String, Object> m) {
        if (m == null) return null;
        long created = num(m.get("createdAt"), 0);
        DshTask t = new DshTask(str(m.get("id")), str(m.get("sessionId")), str(m.get("text")), created);
        t.state = parse(str(m.get("state")));
        t.result = str(m.get("result"));
        t.error = str(m.get("error"));
        t.steps = (int) num(m.get("steps"), 0);
        t.updatedAt = num(m.get("updatedAt"), created);
        return t;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static long num(Object o, long dflt) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o instanceof String) {
            try { return Long.parseLong(((String) o).trim()); } catch (NumberFormatException ignore) { }
        }
        return dflt;
    }

    @Override public String toString() {
        return id + "[" + name(state) + "]";
    }
}
