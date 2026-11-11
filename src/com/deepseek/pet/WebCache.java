package com.deepseek.pet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 联网检索的**短期缓存 + 主机冷却** —— <b>纯类，零 Android 依赖</b>（fpstest 里做真断言）。
 *
 * <h3>为什么需要它（轮次㉙，用户报「搜天气还报错 429」）</h3>
 * 429 = 对面说"你请求太多了"。我们自己有几处**主动把请求打多**了：
 * <ol>
 *   <li>同一个城市连着问两次天气，就真的去请求两次（没有任何缓存）——
 *       而天气 10 分钟内基本不变；</li>
 *   <li>一次天气查询最坏会发 <b>4 个请求</b>：`weather_index` 失败 → `sk_2d` 兜底，
 *       而这两个各自还会被 `getWithRetry` 重试一次；</li>
 *   <li>每个天气请求都带一个**随机 cache-buster**（`_=<毫秒>`），把对面的 CDN 缓存全废掉。</li>
 * </ol>
 * ⇒ 加一层很土的缓存与退避就够治本：**同样的东西短时间只取一次**；**被限流的主机先别再打**。
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li>{@link #get}/{@link #put}：按 key 存一段文本（天气资料块、搜索结果 JSON…），带 TTL；</li>
 *   <li>{@link #cool}/{@link #cooldownLeft}：某个**主机**被 429/403 后进入冷却，
 *       冷却期内直接跳过（连请求都不发），并给用户一句人话；</li>
 *   <li>★ 时钟**可注入**（{@link #setClock}）：断言里不必真的 sleep 就能验"过期"与"冷却解除"；</li>
 *   <li>容量有上限（默认 48 条，按写入时间淘汰最旧的），不许无限长大。</li>
 * </ul>
 */
public final class WebCache {

    private WebCache() {}

    /** 天气缓存多久：10 分钟（实况够用，也不会让用户觉得"数据是旧的"） */
    public static final long WEATHER_TTL_MS = 10 * 60 * 1000L;
    /** 搜索结果缓存多久：5 分钟（同一句话连着问不必重新搜） */
    public static final long SEARCH_TTL_MS = 5 * 60 * 1000L;
    /** 默认冷却时长（对面没给 Retry-After 时）：60 秒 */
    public static final long DEFAULT_COOLDOWN_MS = 60 * 1000L;
    /** 冷却最长多久（对面给的 Retry-After 再大也要封顶，免得"永久不可用"） */
    public static final long MAX_COOLDOWN_MS = 10 * 60 * 1000L;
    /** 最多存多少条 */
    public static final int MAX_ENTRIES = 48;

    private static final class Entry {
        final String value;
        final long expireAt;
        final long putAt;

        Entry(String v, long expireAt, long putAt) {
            this.value = v;
            this.expireAt = expireAt;
            this.putAt = putAt;
        }
    }

    private static final Map<String, Entry> CACHE = new HashMap<String, Entry>();
    private static final Map<String, Long> COOLDOWN = new HashMap<String, Long>();
    private static long clockSkew = 0L;      // 断言用：setClock 设的"虚拟时间"

    /** 当前时间（毫秒）；断言里可用 {@link #setClock} 拨快，不必真等 */
    static long now() {
        return System.currentTimeMillis() + clockSkew;
    }

    /** 只给断言用：把"现在"拨到某个绝对时间（传 0 恢复真实时钟） */
    public static synchronized void setClock(long virtualNow) {
        clockSkew = virtualNow == 0 ? 0L : (virtualNow - System.currentTimeMillis());
    }

    // ==================== 内容缓存 ====================

    public static synchronized void put(String key, String value, long ttlMs) {
        if (key == null || key.length() == 0 || value == null) return;
        long t = now();
        CACHE.put(key, new Entry(value, t + Math.max(1, ttlMs), t));
        if (CACHE.size() > MAX_ENTRIES) evictOldest();
    }

    /** 命中且没过期 ⇒ 返回内容；否则返回 null 并把过期条目清掉 */
    public static synchronized String get(String key) {
        if (key == null) return null;
        Entry e = CACHE.get(key);
        if (e == null) return null;
        if (now() >= e.expireAt) {
            CACHE.remove(key);
            return null;
        }
        return e.value;
    }

    /** 还剩多久过期（0 = 没有/已过期）；日志与界面提示用 */
    public static synchronized long ttlLeft(String key) {
        Entry e = CACHE.get(key);
        if (e == null) return 0L;
        long left = e.expireAt - now();
        return left > 0 ? left : 0L;
    }

    public static synchronized void clear() {
        CACHE.clear();
        COOLDOWN.clear();
    }

    public static synchronized int size() {
        return CACHE.size();
    }

    private static void evictOldest() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
            if (e.getValue().putAt < oldest) {
                oldest = e.getValue().putAt;
                oldestKey = e.getKey();
            }
        }
        if (oldestKey != null) CACHE.remove(oldestKey);
    }

    // ==================== 主机冷却（429 / 403 之后别再打）====================

    /**
     * 把某个主机放进冷却。
     *
     * @param retryAfterMs 对面 {@code Retry-After} 换算出的毫秒；{@code <=0} 用默认 60s
     */
    public static synchronized void cool(String host, long retryAfterMs) {
        if (host == null || host.length() == 0) return;
        long ms = retryAfterMs > 0 ? retryAfterMs : DEFAULT_COOLDOWN_MS;
        if (ms > MAX_COOLDOWN_MS) ms = MAX_COOLDOWN_MS;
        long until = now() + ms;
        Long cur = COOLDOWN.get(host);
        if (cur == null || until > cur) COOLDOWN.put(host, until);
    }

    /** 还要等多久（0 = 没在冷却） */
    public static synchronized long cooldownLeft(String host) {
        if (host == null) return 0L;
        Long until = COOLDOWN.get(host);
        if (until == null) return 0L;
        long left = until - now();
        if (left <= 0) {
            COOLDOWN.remove(host);
            return 0L;
        }
        return left;
    }

    /** 现在处于冷却中的主机（日志用） */
    public static synchronized List<String> cooling() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, Long> e : COOLDOWN.entrySet()) {
            if (e.getValue() - now() > 0) out.add(e.getKey());
        }
        return out;
    }

    /** 从 URL 里取主机（拿不到就返回空串 —— 那就不参与冷却判定） */
    public static String hostOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        if (i < 0) return "";
        int start = i + 3;
        int end = url.length();
        for (int k = start; k < url.length(); k++) {
            char c = url.charAt(k);
            if (c == '/' || c == '?' || c == '#') { end = k; break; }
        }
        return url.substring(start, end);
    }

    /**
     * 把 {@code Retry-After} 头解析成毫秒。
     * <p>标准允许两种写法：**秒数**（"120"）或 HTTP 日期；我们只认秒数，
     * 认不出就返回 0（调用方用默认冷却）。
     */
    public static long retryAfterMs(String header) {
        if (header == null) return 0L;
        String t = header.trim();
        if (t.length() == 0) return 0L;
        try {
            long sec = Long.parseLong(t);
            if (sec < 0) return 0L;
            if (sec > MAX_COOLDOWN_MS / 1000) sec = MAX_COOLDOWN_MS / 1000;
            return sec * 1000L;
        } catch (Throwable ignore) {
            return 0L;
        }
    }
}
