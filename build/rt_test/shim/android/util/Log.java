package android.util;

/**
 * ★ 测试替身（只在 build/rt_test 的离线往返断言里使用，**不进 APK**）。
 *
 * <p>为什么需要它：SDK 的 {@code android.jar} 里每个方法体都是
 * {@code throw new RuntimeException("Stub!")}，而素材层/导入器一路上都会打日志。
 * 断言要在桌面 JVM 上跑真代码，就得让 {@code Log} 变成"真的能调"。
 * 编译期用的是真 android.jar（签名齐全），只在**运行期**把这个类放在 classpath 最前面。
 */
public final class Log {
    private Log() {}

    public static int v(String tag, String msg) { return p("V", tag, msg, null); }
    public static int d(String tag, String msg) { return p("D", tag, msg, null); }
    public static int i(String tag, String msg) { return p("I", tag, msg, null); }
    public static int w(String tag, String msg) { return p("W", tag, msg, null); }
    public static int e(String tag, String msg) { return p("E", tag, msg, null); }
    public static int v(String tag, String msg, Throwable t) { return p("V", tag, msg, t); }
    public static int d(String tag, String msg, Throwable t) { return p("D", tag, msg, t); }
    public static int i(String tag, String msg, Throwable t) { return p("I", tag, msg, t); }
    public static int w(String tag, String msg, Throwable t) { return p("W", tag, msg, t); }
    public static int e(String tag, String msg, Throwable t) { return p("E", tag, msg, t); }
    public static String getStackTraceString(Throwable t) { return String.valueOf(t); }
    public static boolean isLoggable(String tag, int level) { return false; }
    public static int println(int level, String tag, String msg) { return p("P", tag, msg, null); }

    private static int p(String lv, String tag, String msg, Throwable t) {
        System.err.println("[LOG " + lv + "] " + tag + ": " + msg + (t == null ? "" : (" / " + t)));
        return 0;
    }
}
