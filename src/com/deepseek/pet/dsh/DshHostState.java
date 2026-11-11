package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * 本机宿主的运行状态（前台服务写、界面读）。
 *
 * <p>为什么要有这个类：宿主现在跑在 {@link DshHostService} 里，而界面随时可能被重建
 * （进出工作模式、旋转）。界面必须能<b>不重启宿主</b>就拿到"起来了吗、地址是什么"，
 * 所以把状态放在进程级的静态字段里，而不是塞在 Activity 里。
 *
 * <p>★ 只在同一个进程内有效：这不是跨进程通信，也刻意不做成跨进程 ——
 * 宿主和界面本来就在一个进程里，多一层 IPC 只会多一处不一致。
 */
public final class DshHostState {

    private static final int RING = 40;

    private DshHostState() { }

    /** 当前地址（空 = 没起来 / 还没打印）。 */
    private static volatile String url = "";

    /** 进程还活着吗（服务报告的）。 */
    private static volatile boolean running = false;

    /** 最近若干行输出（排查用）。 */
    private static final LinkedList<String> lines = new LinkedList<String>();

    /** 最后一次失败原因（没失败就是空）。 */
    private static volatile String lastError = "";

    static void setUrl(String u) {
        url = u == null ? "" : u;
    }

    static void setRunning(boolean r) {
        running = r;
        if (!r) url = "";
    }

    static void setError(String e) {
        lastError = e == null ? "" : e;
    }

    static void append(String line) {
        if (line == null) return;
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > RING) lines.remove(0);
        }
    }

    static void reset() {
        url = "";
        running = false;
        lastError = "";
        synchronized (lines) {
            lines.clear();
        }
    }

    /** 宿主地址（空字符串表示还没好）。 */
    public static String url() {
        return url;
    }

    /** 服务是否在跑。 */
    public static boolean running() {
        return running;
    }

    /** 最后一次失败原因。 */
    public static String lastError() {
        return lastError;
    }

    /** 最近输出的快照。 */
    public static List<String> recentLines() {
        synchronized (lines) {
            return new ArrayList<String>(lines);
        }
    }

    /** 一句话状态（界面直接用）。 */
    public static String summary() {
        if (running && url.length() > 0) return "本机宿主运行中";
        if (running) return "本机宿主启动中";
        if (lastError.length() > 0) return "本机宿主失败：" + lastError;
        return "本机宿主未运行";
    }
}
