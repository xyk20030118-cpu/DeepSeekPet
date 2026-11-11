package com.deepseek.pet;

import java.util.Calendar;
import java.util.Locale;

/**
 * 时间感知模块：把「真实的日期 + 星期 + 时钟 + 时段」整理成注入给模型的上下文。
 *
 * 设计要点：
 *   1) 每次请求现取现算，绝不缓存，避免长时间后台后时间过期；
 *      宿主的 SharedPreferences 不存时间，防止进程被杀后读到旧值。
 *   2) 只依赖 java.util.Calendar / Locale（无第三方库），风格与其余类一致。
 *   3) 输出人话而非 JSON：模型对「今天是 9 月 26 日星期六，现在 16:40（下午）」
 *      这类自然表述最敏感，比 {"ts":...} 可靠得多。
 */
public final class TimeContext {

    private TimeContext() {}

    private static final String[] WEEK = {
            "日", "一", "二", "三", "四", "五", "六"
    };

    /** 当前时段（用于提示词与本地台词池） */
    public static String period() {
        int h = now().get(Calendar.HOUR_OF_DAY);
        if (h >= 5 && h < 9)   return "清晨";
        if (h >= 9 && h < 11)  return "上午";
        if (h >= 11 && h < 14) return "中午";
        if (h >= 14 && h < 18) return "下午";
        if (h >= 18 && h < 23) return "晚上";
        return "深夜";
    }

    /** 简短时刻文本，如「16:40」 */
    public static String clock() {
        Calendar c = now();
        return String.format(Locale.US, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /** 完整日期文本，如「2026 年 9 月 26 日 星期六」 */
    public static String dateText() {
        Calendar c = now();
        return String.format(Locale.US, "%d 年 %d 月 %d 日 星期%s",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), WEEK[c.get(Calendar.DAY_OF_WEEK) - 1]);
    }

    /** 走时钟：只到分钟的时间戳（历史条目比对用，避免秒级抖动导致误判） */
    public static long nowTruncatedToMinute() {
        return System.currentTimeMillis() / 60000L * 60000L;
    }

    /**
     * 请求注入用的时间提示词片段。永远非空。
     * 形如：
     *   【当前时间】2026 年 9 月 26 日 星期六，现在 16:40（下午）。
     *   若涉及时间差（多久没聊、几点了、早安晚安），请以该时间为准推算；
     *   不要编造别的日期或时刻。
     */
    public static String prompt() {
        // 时间只作为背景事实提供，不要求模型主动报时。
        // 仅在用户问到时间、或话题本身涉及时才引用，避免每句话都带时间/节日。
        return "【背景信息·不必主动提及】现在是 " + dateText() + " " + clock() + "（" + period() + "）。\n"
             + "这只是供你参考的背景，默认不要主动报时间、报日期或提节日；"
             + "只有当用户问到、或话题确实需要（约定、倒计时、作息关心）时，才自然引用。"
             + "若引用，必须以上述时间为准，不要编造其他日期或时刻。";
    }

    /**
     * 把某条历史消息的绝对时间戳转成相对描述，供模型理解「昨夜那件事」。
     * ts<=0（旧记录没有时间戳）返回 null，调用方跳过。
     */
    public static String relative(long ts) {
        if (ts <= 0) return null;
        long diff = System.currentTimeMillis() - ts;
        if (diff < 0) diff = 0;
        long min = diff / 60000L;
        if (min < 1)   return "刚刚";
        if (min < 60)  return min + " 分钟前";
        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        a.setTimeInMillis(ts);
        b.setTimeInMillis(System.currentTimeMillis());
        int dayDiff = dayIndex(b) - dayIndex(a);
        String hm = String.format(Locale.US, "%02d:%02d",
                a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE));
        if (dayDiff <= 0) return "今天 " + hm;
        if (dayDiff == 1) return "昨天 " + hm;
        if (dayDiff == 2) return "前天 " + hm;
        if (dayDiff < 7)  return dayDiff + " 天前 " + hm;
        return (a.get(Calendar.MONTH) + 1) + " 月 " + a.get(Calendar.DAY_OF_MONTH) + " 日 " + hm;
    }

    /**
     * 聊天节奏：距上次互动超过 20 分钟才返回描述，否则 null。
     *
     * <p>历史正文里不再给每条消息拼「（刚刚）」之后，模型失去了「隔了多久」的判断依据，
     * 也就没法自然地接「你终于回来啦」这类话。这里用一句话把间隔补回去：
     * 间隔短（<20 分钟，就是连续对话）直接返回 null，免得又退化成句句报时间。</p>
     */
    public static String gap(long ts) {
        if (ts <= 0) return null;
        long diff = System.currentTimeMillis() - ts;
        if (diff < 20 * 60_000L) return null;
        return relative(ts);
    }

    private static int dayIndex(Calendar c) {
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR);
    }


    // ==================== 扩展：节日 / 节气 / 时段引导 / 整点报时 ====================

    /** 一句话交代"现在是什么时候"，供戳一下/主动搭话的 cue 复用 */
    public static String sentence() {
        return dateText() + "，现在 " + clock() + "（" + period() + "）";
    }

    /** 今天是不是周末 */
    public static boolean isWeekend() {
        int w = now().get(Calendar.DAY_OF_WEEK);
        return w == Calendar.SATURDAY || w == Calendar.SUNDAY;
    }

    /**
     * 今日节日/节气名；没有则返回 null。
     * 公历节日按「月-日」硬编码（覆盖常见中节日）；
     * 农历节日用近似公历日期（每年浮动 1~2 天，够桌宠提示用）。
     */
    public static String festival() {
        Calendar c = now();
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        String key = m + "-" + d;

        // ---- 公历固定节日 ----
        if ("1-1".equals(key))   return "元旦";
        if ("2-14".equals(key))  return "情人节";
        if ("3-8".equals(key))   return "妇女节";
        if ("3-12".equals(key))  return "植树节";
        if ("4-1".equals(key))   return "愚人节";
        if ("5-1".equals(key))   return "劳动节";
        if ("5-4".equals(key))   return "青年节";
        if ("6-1".equals(key))   return "儿童节";
        if ("7-1".equals(key))   return "建党节";
        if ("8-1".equals(key))   return "建军节";
        if ("9-10".equals(key))  return "教师节";
        if ("10-1".equals(key))  return "国庆节";
        if ("12-24".equals(key)) return "平安夜";
        if ("12-25".equals(key)) return "圣诞节";

        // ---- 农历节日：用近似公历日期（2026 前后） ----
        // 春节 / 元宵 / 端午 / 七夕 / 中元 / 中秋 / 重阳 / 腊八 / 小年 / 除夕
        if (m == 2 && d >= 17 && d <= 18) return "春节";
        if (m == 3 && d == 4)             return "元宵节";
        if (m == 6 && d >= 19 && d <= 20) return "端午节";
        if (m == 8 && d >= 19 && d <= 20) return "七夕";
        if (m == 8 && d >= 27 && d <= 28) return "中元节";
        if (m == 9 && d >= 25 && d <= 26) return "中秋节";
        if (m == 10 && d >= 18 && d <= 19) return "重阳节";
        if (m == 1 && d >= 26 && d <= 27) return "腊八节";
        if (m == 2 && d >= 10 && d <= 11) return "小年";
        if (m == 2 && d == 16)            return "除夕";

        // ---- 二十四节气：近似日期表（每月 2 个，浮动 ±1 天） ----
        String[] jie = {
            "小寒", "大寒", "立春", "雨水", "惊蛰", "春分",
            "清明", "谷雨", "立夏", "小满", "芒种", "夏至",
            "小暑", "大暑", "立秋", "处暑", "白露", "秋分",
            "寒露", "霜降", "立冬", "小雪", "大雪", "冬至"
        };
        int[] jd = {
             5, 20,  4, 19,  5, 20,
             5, 20,  5, 21,  6, 21,
             7, 23,  7, 23,  7, 23,
             8, 23,  7, 22,  7, 22
        };
        int idx = (m - 1) * 2;
        for (int i = 0; i < 2; i++) {
            if (Math.abs(d - jd[idx + i]) <= 1) return jie[idx + i];
        }
        return null;
    }

    /**
     * 主动搭话的"时间口吻"要求：早问好、深夜劝睡、午间关心等。
     * 返回给模型的一句自然语言指令（永远非空）。
     */
    public static String greetingHint() {
        int h = now().get(Calendar.HOUR_OF_DAY);
        String f = festival();
        StringBuilder sb = new StringBuilder();
        // 语气提示改为"可选项"：给模型一个倾向，但不强制它每次问候/报时段。
        if (h >= 5 && h < 9) {
            sb.append("当前是清晨，语气可以慵懒一点；如果合适，顺口提一句早。");
        } else if (h >= 9 && h < 11) {
            sb.append("当前是上午，语气轻快。");
        } else if (h >= 11 && h < 14) {
            sb.append("当前是中午，如果合适可以顺口问一句吃了没。");
        } else if (h >= 14 && h < 18) {
            sb.append("当前是下午，语气平常。");
        } else if (h >= 18 && h < 20) {
            sb.append("当前是傍晚，语气放松；如果合适可以顺口问一句今天累不累。");
        } else if (h >= 20 && h < 23) {
            sb.append("当前是晚上，语气松弛。");
        } else {
            sb.append("当前是深夜，语气该软一点、带点心疼；可以偶尔提醒一句早点休息，"
                    + "但不要每次都催睡。");
        }
        // 节日/周末：只作为一个"偶尔可用"的话题，禁止每次都往这上面扯
        if (f != null) {
            sb.append("今天是「" + f + "」，但只在合适的时候偶尔提一下就好，"
                    + "不必每次都拿节日开头，也不要反复重复同一句祝福。");
        }
        if (isWeekend()) {
            sb.append("今天是周末，语气可以更放松一点、别催主人工作。");
        }
        sb.append("整体上，不要每句话都围绕时间、时段或节日展开，保持日常聊天的自然感。");
        return sb.toString();
    }

    /** 整点报时：把"现在几点"说成一句人话，如「已经晚上 10 点啦」 */
    public static String hourChime() {
        Calendar c = now();
        int h = c.get(Calendar.HOUR_OF_DAY);
        String p = period();
        String num;
        switch (h) {
            case 0:  num = "零点"; break;
            case 12: num = "十二点"; break;
            default: {
                int h12 = h % 12;
                num = String.valueOf(h12) + "点";
            }
        }
        return "现在是" + p + num + "（整点，" + clock() + "）";
    }

    /** 整点报时的引导语（给模型的短指令） */
    public static String chimeHint() {
        return "现在是" + clock() + "，整点到了。" + greetingHint()
             + "\n请用一句自然的报时短句提醒主人，可以顺带说说此刻该做什么（喝水/休息/吃饭/睡觉）。";
    }

    private static Calendar now() {
        return Calendar.getInstance();
    }
}
