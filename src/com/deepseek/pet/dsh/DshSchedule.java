package com.deepseek.pet.dsh;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 定时任务模型 —— 按上游仓库 {@code deepseek-ai/deepseek-harness}
 * {@code packages/schedule/schedule/src/types.ts} + {@code domain.ts} 的**真实规格**移植。
 *
 * <h3>六种规则（上游 kind 名一个不改）</h3>
 * <pre>
 * after   一次性：N 秒后（正整数秒）
 * at      一次性：某个绝对时刻（本机存 UTC 毫秒）
 * every   固定速率：每 N 秒（**最小 60 秒**，上游 MIN_EVERY_INTERVAL_SECONDS = 60）
 * daily   每天本地时间 HH:MM:SS + IANA 时区
 * weekly  ISO 星期 1..7 + 本地时间 + IANA 时区
 * cron    五段 cron（分 时 日 月 周）+ 显式 IANA 时区
 * </pre>
 *
 * <h3>夏令时（照抄上游口径）</h3>
 * 不存在的本地时间（春季跳过的那个钟点）**跳过这一天**；重叠（秋季重复的钟点）取**较早**的那次。
 * 这里用 {@link Calendar} + {@link TimeZone} 实现（{@code java.time} 在 minSdk 24 上没有，也不引 desugar）。
 *
 * <p>纯类：零 Android 依赖 —— 闹钟接线在 {@link DshScheduleRunner}。
 */
public final class DshSchedule {

    /** 固定间隔下限：与仓库 master 的 {@code MIN_EVERY_INTERVAL_SECONDS = 60} 一致。 */
    public static final long MIN_EVERY_SECONDS = 60L;
    /** 间隔上限（本机加的保护：再大就不该用手机闹钟了）。 */
    public static final long MAX_EVERY_SECONDS = 7L * 24L * 3600L;
    /** 标题上限：与上游 {@code MAX_TITLE_LENGTH = 120} 一致。 */
    public static final int MAX_TITLE = 120;
    /** 指令上限（本机口径；上游没有硬上限，但手机输入框要有）。 */
    public static final int MAX_PROMPT = 500;
    /** 投递记录最多留几条（上游是配置项，这里是固定值，够界面用）。 */
    public static final int MAX_DELIVERY = 20;
    /** 往后找下一个时点的搜索上限：上游是 400 年（cron 永不可能），手机上给 400 天就够。 */
    public static final int HORIZON_DAYS = 400;

    public static final String KIND_AFTER = "after";
    public static final String KIND_AT = "at";
    public static final String KIND_EVERY = "every";
    public static final String KIND_DAILY = "daily";
    public static final String KIND_WEEKLY = "weekly";
    public static final String KIND_CRON = "cron";

    public final String id;
    public final long createdAt;
    /** 必填标题（上游 title 必填、≤120）。 */
    public String title;
    public String prompt;
    public String kind;
    /** every：间隔秒。 */
    public long everySeconds;
    /** at / after：绝对时刻（毫秒）。 */
    public long atMillis;
    /** daily / weekly：本地钟点 "HH:MM:SS"。 */
    public String time;
    /** weekly：ISO 星期（1=周一 … 7=周日），升序去重。 */
    public int[] weekdays = new int[0];
    /** daily / weekly / cron：IANA 时区（例如 Asia/Shanghai）。 */
    public String timeZone;
    /** cron：规范化后的五段表达式。 */
    public String cron;
    public boolean enabled;
    public long lastRunAt;
    public long nextRunAt;
    /** 投递记录：{deliveredAt, targetAt}，最多 {@link #MAX_DELIVERY} 条（上游是有界历史）。 */
    public final List<long[]> deliveries = new ArrayList<long[]>();

    public DshSchedule(String id, String title, String prompt, String kind, long createdAt) {
        this.id = (id == null || id.trim().length() == 0) ? newId() : id.trim();
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.title = (title == null ? "" : title.trim());
        this.prompt = prompt == null ? "" : prompt.trim();
        this.kind = normalizeKind(kind);
        this.timeZone = TimeZone.getDefault().getID();
        this.enabled = true;
    }

    public static String normalizeKind(String kind) {
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.US);
        if (KIND_AT.equals(k) || KIND_EVERY.equals(k) || KIND_DAILY.equals(k)
                || KIND_WEEKLY.equals(k) || KIND_CRON.equals(k)) {
            return k;
        }
        return KIND_AFTER;   // 缺省/不认识 ⇒ 一次性（老数据只有 every，见 fromMap）
    }

    // ==================== 校验（照上游口径，话说清楚） ====================

    public static String validateTitle(String title) {
        String t = title == null ? "" : title.trim();
        if (t.length() == 0) return "标题不能为空（上游 title 必填）";
        if (t.length() > MAX_TITLE) return "标题最多 " + MAX_TITLE + " 字";
        return null;
    }

    public static String validatePrompt(String prompt) {
        String p = prompt == null ? "" : prompt.trim();
        if (p.length() == 0) return "要写清楚每次跑什么";
        if (p.length() > MAX_PROMPT) return "指令最多 " + MAX_PROMPT + " 字";
        return null;
    }

    /** 固定间隔：上游最小 60 秒。 */
    public static String validateEvery(long seconds) {
        if (seconds < MIN_EVERY_SECONDS) {
            return "固定间隔最少 " + MIN_EVERY_SECONDS + " 秒（上游 MIN_EVERY_INTERVAL_SECONDS）";
        }
        if (seconds > MAX_EVERY_SECONDS) return "间隔最多 7 天";
        return null;
    }

    /** 本地钟点 "HH:MM" 或 "HH:MM:SS" ⇒ 规范化成 "HH:MM:SS"；非法返回 null。 */
    public static String normalizeClock(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) return null;
        String[] parts = s.split(":");
        if (parts.length < 2 || parts.length > 3) return null;
        int h, m, sec = 0;
        try {
            h = Integer.parseInt(parts[0].trim());
            m = Integer.parseInt(parts[1].trim());
            if (parts.length == 3) sec = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (h < 0 || h > 23 || m < 0 || m > 59 || sec < 0 || sec > 59) return null;
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec);
    }

    /** 每周的星期集合：1..7（ISO），去重升序。 */
    public static int[] normalizeWeekdays(int[] raw) {
        if (raw == null || raw.length == 0) return new int[0];
        java.util.TreeSet<Integer> set = new java.util.TreeSet<Integer>();
        for (int d : raw) {
            if (d >= 1 && d <= 7) set.add(d);
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer d : set) out[i++] = d;
        return out;
    }

    /** 时区必须能被识别（不认就拒，别默默用本机时区）。 */
    public static String validateTimeZone(String tz) {
        if (tz == null || tz.trim().length() == 0) return "时区不能为空";
        String id = TimeZone.getTimeZone(tz.trim()).getID();
        if (id == null || id.length() == 0 || "GMT".equals(id) && !tz.trim().startsWith("GMT")) {
            return "不认识的时区：" + tz;
        }
        return null;
    }

    // ==================== 下一个时点 ====================

    /**
     * 从现在算下一个该跑的时点（{@code now} 严格之后）。
     *
     * <p>一次性规则（after/at）返回的就是那个绝对时刻；已经过期返回 0（表示"不该再排"）。
     */
    public long nextAfter(long now) {
        if (KIND_EVERY.equals(kind)) {
            long interval = Math.max(1, everySeconds) * 1000L;
            long base = lastRunAt > 0 ? nextRunAt > 0 ? nextRunAt : createdAt + interval
                                      : createdAt + interval;
            long t = base;
            if (t <= now) {
                long k = (now - t) / interval + 1;
                t = t + k * interval;
            }
            return t;
        }
        if (KIND_DAILY.equals(kind)) {
            return nextWallClock(now, time, timeZone, null);
        }
        if (KIND_WEEKLY.equals(kind)) {
            return nextWallClock(now, time, timeZone, weekdays);
        }
        if (KIND_CRON.equals(kind)) {
            return nextCron(now, cron, timeZone);
        }
        return atMillis > now ? atMillis : 0;
    }

    /**
     * 每 N 天/每周的墙上时间：**不存在的本地钟点跳过、重叠取较早**。
     *
     * @param weekdays null/空 = 每天都算；否则只算这些 ISO 星期
     */
    public static long nextWallClock(long now, String clock, String tz, int[] weekdays) {
        String c = normalizeClock(clock);
        if (c == null) return 0;
        String[] p = c.split(":");
        int hh = Integer.parseInt(p[0]), mm = Integer.parseInt(p[1]), ss = Integer.parseInt(p[2]);
        TimeZone zone = TimeZone.getTimeZone(tz == null || tz.trim().length() == 0
                ? TimeZone.getDefault().getID() : tz.trim());
        Calendar cal = Calendar.getInstance(zone, Locale.US);
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        for (int day = 0; day <= HORIZON_DAYS; day++) {
            Calendar cand = (Calendar) cal.clone();
            cand.add(Calendar.DAY_OF_MONTH, day);
            if (weekdays != null && weekdays.length > 0 && !hasWeekday(weekdays, isoDow(cand))) {
                continue;
            }
            Calendar at = (Calendar) cand.clone();
            at.set(Calendar.HOUR_OF_DAY, hh);
            at.set(Calendar.MINUTE, mm);
            at.set(Calendar.SECOND, ss);
            at.set(Calendar.MILLISECOND, 0);
            // ★ 夏令时"不存在的钟点"：Calendar 会把 02:30 挪成 03:30 / 01:30，
            //   回读字段就能发现 —— 上游口径是**这一天跳过**。
            if (at.get(Calendar.HOUR_OF_DAY) != hh || at.get(Calendar.MINUTE) != mm
                    || at.get(Calendar.SECOND) != ss) {
                continue;
            }
            long t = at.getTimeInMillis();
            // ★ 秋季回拨时同一个钟点出现两次：上游口径是**取较早**那次。
            //   java.util.Calendar 默认给较晚（标准时）那次 ⇒ 往回试一小时。
            long earlier = t - 3600_000L;
            if (earlier > now && sameWallClock(earlier, zone, hh, mm, ss)) {
                t = earlier;
            }
            if (t > now) return t;
        }
        return 0;
    }

    /** 五段 cron（分 时 日 月 周）的下一个匹配时刻。 */
    public static long nextCron(long now, String expr, String tz) {
        Cron c = Cron.parse(expr);
        if (c == null) return 0;
        TimeZone zone = TimeZone.getTimeZone(tz == null || tz.trim().length() == 0
                ? TimeZone.getDefault().getID() : tz.trim());
        Calendar cal = Calendar.getInstance(zone, Locale.US);
        cal.setTimeInMillis(now);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MINUTE, 1);
        long limit = now + (long) HORIZON_DAYS * 24L * 3600L * 1000L;
        while (cal.getTimeInMillis() <= limit) {
            if (c.matches(cal)) return cal.getTimeInMillis();
            cal.add(Calendar.MINUTE, 1);
        }
        return 0;
    }

    /** 某个毫秒时刻在给定时区里是不是正好是 hh:mm:ss（用来识别夏令时重叠的两次）。 */
    static boolean sameWallClock(long ms, TimeZone zone, int hh, int mm, int ss) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) == hh && c.get(Calendar.MINUTE) == mm
                && c.get(Calendar.SECOND) == ss;
    }

    private static boolean hasWeekday(int[] days, int iso) {
        for (int d : days) if (d == iso) return true;
        return false;
    }

    /** Calendar 的 DOW：1=周日 … 7=周六 ⇒ ISO 1=周一 … 7=周日。 */
    static int isoDow(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }

    // ==================== 投递记录（有界，照上游） ====================

    /** 记一次投递；超过上限就把最老的挤掉。 */
    public void recordDelivery(long deliveredAt, long targetAt) {
        deliveries.add(new long[]{deliveredAt, targetAt});
        while (deliveries.size() > MAX_DELIVERY) deliveries.remove(0);
        lastRunAt = deliveredAt;
    }

    // ==================== 人话 ====================

    public String frequencyLabel() {
        if (KIND_EVERY.equals(kind)) {
            long m = everySeconds / 60L;
            if (everySeconds % 60L != 0) return "每 " + everySeconds + " 秒";
            if (m < 60) return "每 " + m + " 分钟";
            if (m % 60L == 0) return "每 " + (m / 60) + " 小时";
            return "每 " + m + " 分钟";
        }
        if (KIND_DAILY.equals(kind)) return "每天 " + shortClock() + "（" + shortZone() + "）";
        if (KIND_WEEKLY.equals(kind)) return "每" + weekLabel() + " " + shortClock() + "（" + shortZone() + "）";
        if (KIND_CRON.equals(kind)) return "cron " + cron + "（" + shortZone() + "）";
        if (KIND_AT.equals(kind)) return "仅一次（定时）";
        return "仅一次（N 秒后）";
    }

    private String shortClock() {
        String c = normalizeClock(time);
        return c == null ? "--:--" : c.substring(0, 5);
    }

    private String weekLabel() {
        if (weekdays == null || weekdays.length == 0) return "周";
        if (weekdays.length == 7) return "天";
        StringBuilder sb = new StringBuilder();
        String[] nm = {"一", "二", "三", "四", "五", "六", "日"};
        for (int d : weekdays) sb.append(nm[Math.max(0, Math.min(6, d - 1))]);
        return "周 " + sb;
    }

    private String shortZone() {
        if (timeZone == null) return "本机时区";
        return timeZone.replace("Asia/Shanghai", "上海").replace("Asia/Tokyo", "东京")
                .replace("UTC", "UTC");
    }

    public static String newId() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        String tail = Integer.toHexString((int) (Math.random() * 0xffff) | 0x1000).substring(1);
        return "sch-" + f.format(new java.util.Date()) + "-" + tail;
    }

    /** 每次跑都写进这个会话（左栏看得见历史）。 */
    public String sessionId() {
        return "sched-" + id;
    }

    // ==================== 落盘形状 ====================

    public Map<String, Object> toMap() {
        List<Object> hist = new ArrayList<Object>();
        for (long[] d : deliveries) {
            hist.add(DshJson.map("deliveredAt", Long.valueOf(d[0]), "targetAt", Long.valueOf(d[1])));
        }
        Map<String, Object> m = DshJson.map(
                "id", id,
                "kind", kind,
                "title", title,
                "prompt", prompt,
                "enabled", Boolean.valueOf(enabled),
                "createdAt", Long.valueOf(createdAt),
                "lastRunAt", Long.valueOf(lastRunAt),
                "nextRunAt", Long.valueOf(nextRunAt));
        if (KIND_EVERY.equals(kind)) m.put("everySeconds", Long.valueOf(everySeconds));
        if (KIND_AT.equals(kind) || KIND_AFTER.equals(kind)) m.put("atMillis", Long.valueOf(atMillis));
        if (time != null) m.put("time", time);
        if (timeZone != null) m.put("timeZone", timeZone);
        if (cron != null) m.put("cron", cron);
        if (weekdays != null && weekdays.length > 0) {
            List<Object> list = new ArrayList<Object>();
            for (int d : weekdays) list.add(Integer.valueOf(d));
            m.put("weekdays", list);
        }
        if (!hist.isEmpty()) m.put("deliveries", hist);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static DshSchedule fromMap(Map<String, Object> m) {
        if (m == null || m.get("id") == null) return null;
        String prompt = str(m.get("prompt"));
        // ★ 老数据（第十五版）只有 everySeconds、没有 kind / title ⇒ 当 every 读，标题由指令派生
        String kind = m.get("kind") == null
                ? (m.get("everySeconds") != null ? KIND_EVERY : KIND_AFTER)
                : normalizeKind(str(m.get("kind")));
        String title = str(m.get("title"));
        DshSchedule s = new DshSchedule(str(m.get("id")),
                title.length() > 0 ? title : deriveTitle(prompt), prompt, kind,
                longOf(m.get("createdAt"), 0));
        s.everySeconds = longOf(m.get("everySeconds"), MIN_EVERY_SECONDS);
        s.atMillis = longOf(m.get("atMillis"), 0);
        s.time = m.get("time") == null ? null : normalizeClock(str(m.get("time")));
        String tz = str(m.get("timeZone"));
        s.timeZone = tz.length() > 0 ? tz : TimeZone.getDefault().getID();
        s.cron = m.get("cron") == null ? null : str(m.get("cron"));
        Object wd = m.get("weekdays");
        if (wd instanceof List) {
            int[] arr = new int[((List<?>) wd).size()];
            int i = 0;
            for (Object o : (List<?>) wd) arr[i++] = (int) longOf(o, 0);
            s.weekdays = normalizeWeekdays(arr);
        }
        Object en = m.get("enabled");
        s.enabled = !(en instanceof Boolean) || ((Boolean) en).booleanValue();
        s.lastRunAt = longOf(m.get("lastRunAt"), 0);
        long next = longOf(m.get("nextRunAt"), 0);
        s.nextRunAt = next > 0 ? next : s.nextAfter(System.currentTimeMillis());
        Object hist = m.get("deliveries");
        if (hist instanceof List) {
            for (Object o : (List<?>) hist) {
                if (!(o instanceof Map)) continue;
                Map<?, ?> d = (Map<?, ?>) o;
                s.deliveries.add(new long[]{longOf(d.get("deliveredAt"), 0), longOf(d.get("targetAt"), 0)});
            }
            while (s.deliveries.size() > MAX_DELIVERY) s.deliveries.remove(0);
        }
        return s;
    }

    /** 标题缺省时从指令派生（前 24 字）。 */
    public static String deriveTitle(String prompt) {
        String p = prompt == null ? "" : prompt.trim().replace('\n', ' ');
        return p.length() <= 24 ? p : p.substring(0, 24) + "…";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static long longOf(Object o, long dflt) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o == null) return dflt;
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // ==================== 五段 cron（Vixie 语义） ====================

    /** 解析后的五段表达式：分 时 日 月 周。周段 0/7=周日。 */
    public static final class Cron {
        final boolean[] min = new boolean[60];
        final boolean[] hour = new boolean[24];
        final boolean[] dom = new boolean[32];
        final boolean[] mon = new boolean[13];
        final boolean[] dow = new boolean[8];
        final String canonical;

        private Cron(String canonical) {
            this.canonical = canonical;
        }

        public String canonical() {
            return canonical;
        }

        /** 解析失败返回 null（调用方给用户一句人话）。 */
        public static Cron parse(String expr) {
            if (expr == null) return null;
            String[] f = expr.trim().replaceAll("\\s+", " ").split(" ");
            if (f.length != 5) return null;
            Cron c = new Cron(expr.trim().replaceAll("\\s+", " "));
            if (!fill(c.min, f[0], 0, 59)) return null;
            if (!fill(c.hour, f[1], 0, 23)) return null;
            if (!fill(c.dom, f[2], 1, 31)) return null;
            if (!fill(c.mon, f[3], 1, 12)) return null;
            if (!fill(c.dow, f[4], 0, 7)) return null;
            return c;
        }

        private static boolean fill(boolean[] set, String field, int lo, int hi) {
            for (String part : field.split(",")) {
                String p = part.trim();
                if (p.length() == 0) return false;
                int step = 1;
                int slash = p.indexOf('/');
                if (slash >= 0) {
                    try {
                        step = Integer.parseInt(p.substring(slash + 1));
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    if (step <= 0) return false;
                    p = p.substring(0, slash);
                }
                int from, to;
                if ("*".equals(p)) {
                    from = lo;
                    to = hi;
                } else if (p.indexOf('-') > 0) {
                    String[] r = p.split("-", 2);
                    try {
                        from = Integer.parseInt(r[0]);
                        to = Integer.parseInt(r[1]);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                } else {
                    try {
                        from = Integer.parseInt(p);
                    } catch (NumberFormatException e) {
                        return false;
                    }
                    to = from;
                }
                if (from < lo || to > hi || from > to) return false;
                for (int v = from; v <= to; v += step) set[v] = true;
            }
            return true;
        }

        public boolean matches(Calendar cal) {
            int m = cal.get(Calendar.MINUTE);
            int h = cal.get(Calendar.HOUR_OF_DAY);
            int d = cal.get(Calendar.DAY_OF_MONTH);
            int mo = cal.get(Calendar.MONTH) + 1;
            int w = cal.get(Calendar.DAY_OF_WEEK);   // 1=周日 … 7=周六
            int wVixie = w == Calendar.SUNDAY ? 0 : w - 1;   // 0=周日 … 6=周六
            if (!min[m] || !hour[h] || !mon[mo]) return false;
            boolean domStar = allOf(this.dom, 1, 31);
            boolean dowStar = allOf(this.dow, 0, 7);
            boolean domOk = this.dom[d];
            boolean dowOk = this.dow[wVixie] || (wVixie == 0 && this.dow[7]);
            if (domStar && dowStar) return true;
            // Vixie：日/周两段都受限时取**或**，只有一段受限就只看那一段
            if (!domStar && !dowStar) return domOk || dowOk;
            return domStar ? dowOk : domOk;
        }

        private static boolean allOf(boolean[] set, int lo, int hi) {
            for (int i = lo; i <= hi; i++) if (!set[i]) return false;
            return true;
        }
    }
}
