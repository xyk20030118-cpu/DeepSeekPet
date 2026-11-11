package android.os;

/** ★ 测试替身（只在 build/rt_test 用，不进 APK）：直接把 Runnable 就地跑掉。 */
public class Handler {
    public Handler() {}
    public Handler(Looper looper) {}
    public boolean post(Runnable r) { if (r != null) r.run(); return true; }
    public boolean postDelayed(Runnable r, long delayMillis) { if (r != null) r.run(); return true; }
    public void removeCallbacks(Runnable r) {}
    public void removeCallbacksAndMessages(Object token) {}
}
