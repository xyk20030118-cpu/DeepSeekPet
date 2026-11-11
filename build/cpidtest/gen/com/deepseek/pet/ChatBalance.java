package com.deepseek.pet;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 余额取值的纯逻辑（不 import 任何 android 类）。
 *
 * <h3>为什么单独抽出来</h3>
 * 各家的余额 JSON 结构完全不同，取值靠"{@code a.b[0].c}"形式的路径字符串。
 * 这段解析**最容易写错，而且错了不会报错** —— 只会显示"无余额数据"，
 * 用户完全无法自查是哪一环出的问题。
 *
 * <p>所以把它抽成纯 Java：不依赖 Android 就能离线断言
 * （与 {@code VideoFitMath} 同样的理由 —— 那段几何数学当初也是
 * 因为"算错了只能靠肉眼"才被抽出来的，见 STATE.md）。
 *
 * <p>{@code org.json} 不在 android 包里、而是独立实现，
 * 离线测试直接用 toolchain 的真实现，测的就是线上跑的那份代码。
 */
public final class ChatBalance {

    private ChatBalance() {}

    /**
     * 按 {@code a.b.c} / {@code a[0].b} 形式的路径从 JSON 取值。
     *
     * @return 数值；取不到返回 {@link Double#NaN}（**不是 0** ——
     *         把"取不到"当成 0 会让界面显示"余额 ¥0.00"，
     *         用户会以为钱没了）
     */
    public static double pickNumber(JSONObject root, String path) {
        if (root == null || path == null || path.length() == 0) return Double.NaN;
        try {
            Object cur = root;
            for (String seg : path.split("\\.")) {
                String key = seg;
                Integer idx = null;
                int lb = seg.indexOf('[');
                if (lb >= 0 && seg.endsWith("]")) {
                    key = seg.substring(0, lb);
                    idx = Integer.valueOf(seg.substring(lb + 1, seg.length() - 1));
                }
                if (!(cur instanceof JSONObject)) return Double.NaN;
                cur = ((JSONObject) cur).opt(key);
                if (idx != null) {
                    if (!(cur instanceof JSONArray)) return Double.NaN;
                    cur = ((JSONArray) cur).opt(idx.intValue());
                }
                if (cur == null) return Double.NaN;
            }
            if (cur instanceof Number) return ((Number) cur).doubleValue();
            // 有些服务商返回字符串数字（SiliconFlow 的 totalBalance 就是字符串）
            String s = String.valueOf(cur).trim();
            if (s.length() == 0) return Double.NaN;   // 空串不能当 0
            return Double.parseDouble(s);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    /**
     * 判断响应体里是否带**业务错误**（HTTP 200 但业务失败）。
     *
     * <h3>★ 为什么必须查这个</h3>
     * 实测智谱的 biz 端点返回：
     * <pre>
     *   HTTP 200
     *   {"code":401,"msg":"令牌已过期或验证不正确","success":false}
     * </pre>
     * 只看 HTTP 状态码会把它当成"成功但没数据"，
     * 于是界面显示"无余额数据" —— 而真实原因是 **Key 无效**。
     * 两者对用户的含义完全不同（一个要换 Key，一个可能只是没充值）。
     *
     * <p>判据（任一成立即视为业务错误）：
     * <ul>
     *   <li>{@code success} 明确为 false</li>
     *   <li>{@code code} 存在且不是成功码（非 0 / 非 200 / 非 20000）</li>
     *   <li>{@code status} 明确为 false</li>
     * </ul>
     * 刻意不把"缺字段"当错误 —— 各家字段名不同，缺了只是没这个信息。
     */
    public static boolean hasBusinessError(JSONObject json) {
        if (json == null) return false;
        try {
            if (json.has("success") && !json.optBoolean("success", true)) return true;
            if (json.has("status") && !json.optBoolean("status", true)) {
                // 注意：Kimi 的 status=true 表示成功；false 才是错
                return true;
            }
            if (json.has("code")) {
                Object c = json.opt("code");
                if (c instanceof Number) {
                    long v = ((Number) c).longValue();
                    // 0 = Kimi 成功；200 = 通用成功；20000 = SiliconFlow 成功
                    if (v != 0 && v != 200 && v != 20000) return true;
                } else if (c != null) {
                    String s = String.valueOf(c).trim();
                    // 有的服务商把 code 写成字符串
                    if (s.length() > 0 && !"0".equals(s) && !"200".equals(s)
                            && !"20000".equals(s)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    /** 取第一个非空的字符串字段（用于把服务端的错误消息透给用户） */
    public static String firstNonEmpty(JSONObject json, String... keys) {
        if (json == null || keys == null) return "";
        for (String k : keys) {
            try {
                Object v = json.opt(k);
                if (v == null) continue;
                if (v instanceof JSONObject) {
                    // 形如 {"error":{"message":"..."}}
                    Object m = ((JSONObject) v).opt("message");
                    if (m != null) {
                        String s = String.valueOf(m).trim();
                        if (s.length() > 0) return s;
                    }
                    continue;
                }
                String s = String.valueOf(v).trim();
                if (s.length() > 0 && !"null".equals(s)) return s;
            } catch (Exception ignore) {}
        }
        return "";
    }

    /**
     * 把 baseUrl 归一化成"接口根"（去掉可能被粘进来的端点路径）。
     *
     * <h3>★ 这一步修的是一个真实故障（用户报 HTTP 404）</h3>
     * 用户可以把 API 地址填成**完整端点**，例如
     * {@code https://api.moonshot.cn/v1/chat/completions} —— 而且这样做
     * 对话是**能用**的（{@code ApiClient.chatUrl()} 见到该后缀会原样返回）。
     *
     * <p>但余额拼接若直接用这个 baseUrl，就会得到
     * <pre>
     *   https://api.moonshot.cn/v1/chat/completions/users/me/balance
     * </pre>
     * 服务端返回 **404**，界面显示"余额获取失败：HTTP 404"，
     * 而用户完全看不出是自己多填了一段路径。
     *
     * <p>所以先剥掉已知的端点后缀，得到 {@code https://api.moonshot.cn/v1}。
     */
    static String normalizeBase(String baseUrl) {
        if (baseUrl == null) return null;
        String b = baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // 由长到短剥，避免先剥短后缀把长后缀弄残
        String[] suffixes = {
                "/chat/completions",
                "/completions",
                "/user/balance",
                "/users/me/balance",
                "/user/info",
        };
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String s : suffixes) {
                if (b.endsWith(s)) {
                    b = b.substring(0, b.length() - s.length());
                    while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
                    changed = true;
                }
            }
        }
        return b;
    }

    /**
     * 余额地址的拼接规则。
     *
     * <p>两条分支都有存在的必要：
     * <ul>
     *   <li><b>相对路径</b>（{@code /users/me/balance}）→ 拼在 baseUrl 之后。
     *       Kimi 的 {@code .ai} 与 {@code .com} 平台 Key **互不通用**，
     *       所以必须跟着用户填的 baseUrl 走，不能写死域名。</li>
     *   <li><b>绝对路径</b>（{@code /api/biz/...}）→ 只取 baseUrl 的
     *       scheme+host，替换掉原路径。智谱的余额走 biz 网关，
     *       与对话用的 {@code /api/paas/v4} 不是同一层。</li>
     * </ul>
     *
     * @param baseUrl 当前基地址（如 {@code https://api.moonshot.cn/v1}，
     *                也容忍 {@code .../v1/chat/completions} 这种完整端点写法）
     * @param path    接口路径
     * @return 完整地址；参数非法返回 null
     */
    public static String buildUrl(String baseUrl, String path) {
        if (path == null || path.length() == 0) return null;
        if (baseUrl == null || baseUrl.length() == 0) return null;
        if (path.startsWith("/api/")) {
            try {
                java.net.URL u = new java.net.URL(normalizeBase(baseUrl));
                return u.getProtocol() + "://" + u.getHost()
                        + (u.getPort() > 0 ? ":" + u.getPort() : "") + path;
            } catch (Exception ex) {
                return normalizeBase(baseUrl) + path;
            }
        }
        return normalizeBase(baseUrl) + path;
    }
}
