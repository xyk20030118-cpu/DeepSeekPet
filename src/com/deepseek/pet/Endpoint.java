package com.deepseek.pet;

/**
 * 「用户自己填的对话 API 地址」的**纯判据**（轮次56）。
 *
 * <h3>为什么值得单独一个类</h3>
 * 用户报「自定义对话 API 无法正常使用，可能是接口格式兼容性问题」。
 * 实测下来**第一个真凶不是格式，而是地址本身连不到**：
 * <pre>
 *   http://127.0.0.1:7863/v1      ← 他本机 AI 网关的地址，电脑上的 agent 用得好好的
 * </pre>
 * 但 App 跑在**手机/模拟器**上，那里的 {@code 127.0.0.1} 是**设备自己** ——
 * 手机里并没有他的网关，于是连接被拒（或先被明文策略挡下）。
 * 这类错**用户自己极难看出来**：同一个地址在电脑上明明是通的。
 * 所以必须由 App 主动识别、并把「该填什么」直接说清楚。
 *
 * <h3>纯函数（不碰 Android）</h3>
 * 只用 {@code java.net.URI} 与字符串 ⇒ {@code fpstest} 能真跑，不必上设备。
 */
public final class Endpoint {

    private Endpoint() {}

    /** 从地址里取主机名（小写）；取不到返回 ""。容忍没写 scheme、带端口、带 IPv6 字面量 */
    public static String host(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0) return "";
        try {
            java.net.URI uri = new java.net.URI(u);
            String h = uri.getHost();
            if (h != null && h.length() > 0) {
                // ★ IPv6 字面量：URI.getHost() 会**带方括号**返回（RFC 2732）——
                //   fpstest [30] 当场抓到过：`[::1]` 与 `::1` 不相等，回环就认不出来了。
                if (h.startsWith("[") && h.endsWith("]") && h.length() > 2) {
                    h = h.substring(1, h.length() - 1);
                }
                return h.toLowerCase();
            }
        } catch (Throwable ignore) {
            // 落到下面的手工解析（用户可能填了没有 scheme 的串）
        }
        String s = u;
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int q = s.indexOf('?');
        if (q >= 0) s = s.substring(0, q);
        int at = s.lastIndexOf('@');           // 带 user:pass@host 的写法
        if (at >= 0) s = s.substring(at + 1);
        if (s.startsWith("[")) {               // IPv6 字面量 [::1]:8080
            int close = s.indexOf(']');
            if (close > 0) return s.substring(1, close).toLowerCase();
        }
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.toLowerCase();
    }

    /** 回环地址：这些地址指的是"运行 App 的那台设备自己"，不是你的电脑 */
    public static boolean isLoopbackHost(String h) {
        if (h == null) return false;
        String x = h.trim().toLowerCase();
        if (x.length() == 0) return false;
        if ("localhost".equals(x) || x.endsWith(".localhost")) return true;
        if ("::1".equals(x) || "0:0:0:0:0:0:0:1".equals(x)) return true;
        if (x.startsWith("127.")) {            // 整个 127/8 都是回环
            String[] p = x.split("\\.");
            return p.length == 4;
        }
        return false;
    }

    public static boolean isLoopback(String url) { return isLoopbackHost(host(url)); }

    /** {@code 0.0.0.0} / {@code ::} 是"监听所有网卡"的写法，**不是能连的地址** */
    public static boolean isWildcardHost(String h) {
        if (h == null) return false;
        String x = h.trim().toLowerCase();
        return "0.0.0.0".equals(x) || "::".equals(x) || "*".equals(x);
    }

    public static boolean isWildcard(String url) { return isWildcardHost(host(url)); }

    /**
     * 这个地址有没有"一看就连不上"的问题；没有返回 {@code ""}。
     *
     * <p>返回的是**给用户看的原话**（不是错误码）：他填的地址在电脑上是通的，
     * 所以只报"连接失败"只会让他更困惑 —— 必须解释"设备上的 127.0.0.1 是谁"。
     */
    public static String advice(String url) {
        String h = host(url);
        if (isLoopbackHost(h)) {
            return "这个地址指的是“**运行 App 的这台设备自己**”，不是你的电脑。\n\n"
                 + "· 手机 / 平板上填 127.0.0.1 = 手机自己 —— 手机里没有你的服务，必然连不上；\n"
                 + "· 模拟器里要连**宿主机**，请用 http://10.0.2.2:端口/v1；\n"
                 + "· 手机连电脑：填电脑的**局域网 IP**（如 http://192.168.1.5:7863/v1），"
                 + "并确认服务监听的是 0.0.0.0（只监听 127.0.0.1 的话外部一律连不上）；\n"
                 + "· 真机用 USB 连着电脑时，也可以在电脑上执行 "
                 + "`adb reverse tcp:7863 tcp:7863`，那样 127.0.0.1 才真的能通。";
        }
        if (isWildcardHost(h)) {
            return "0.0.0.0 是“监听所有网卡”的写法，**不是能连接的地址**。\n\n"
                 + "请填服务所在电脑的**局域网 IP**（如 http://192.168.1.5:7863/v1）。";
        }
        return "";
    }

    /** 有没有问题（{@link #advice} 的非空判断，方便调用点写条件） */
    public static boolean suspicious(String url) { return advice(url).length() > 0; }

    /** 把地址归一化：去空白、去尾部斜杠（与 {@code ApiClient.setBaseUrl} 同一口径） */
    public static String normalize(String url) {
        if (url == null) return "";
        String b = url.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
