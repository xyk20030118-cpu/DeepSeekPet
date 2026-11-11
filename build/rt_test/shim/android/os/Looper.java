package android.os;

/** ★ 测试替身（只在 build/rt_test 用，不进 APK）：桌面 JVM 上没有 Android 主循环。 */
public final class Looper {
    private static final Looper MAIN = new Looper();
    private Looper() {}
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return MAIN; }
    public Thread getThread() { return Thread.currentThread(); }
    public void quit() {}
    public void quitSafely() {}
}
