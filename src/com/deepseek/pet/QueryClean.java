package com.deepseek.pet;

/**
 * 把用户**原话**提炼成**搜索关键词** —— <b>纯类，零 Android 依赖</b>（fpstest 里做真断言）。
 *
 * <h3>为什么必须有它（轮次㉗ 实测抓到的根因）</h3>
 * 原来的实现把用户原话**原样**丢给必应。实测（2026-10-08，必应 RSS）：
 * <pre>
 *   帮我查一下今天北京天气怎么样啊      → 第一条「帮_百度百科」        （把"帮"当关键词）
 *   我想知道现在比特币一个多少钱人民币  → 第一条「我（汉语汉字）_百度百科」
 *   deepseek 最新版本是多少来着？      → 第一条「抖音怎么发视频」
 *   小米15多少钱现在                  → 第一条「小米商城首页」
 * </pre>
 * 把口语词去掉之后，同一个搜索源立刻能用：
 * <pre>
 *   北京 天气 今天      → 北京今天白天紫外线强…最高气温26℃
 *   DeepSeek 最新版本   → DeepSeek 2026 最新模型怎么选：V4…
 *   小米15 价格         → 小米15库存机暴跌…京东报价
 * </pre>
 * ⇒ <b>"很笨搜不到关键东西"有一半是我们自己没提炼查询，不是模型的错</b>。
 *
 * <h3>做法（刻意保守）</h3>
 * <ol>
 *   <li>先做少量**语义替换**（"多少钱"→"价格"、"什么时候"→"时间"、…）—— 这些是"问题词"，
 *       搜索时换成"资料词"命中率高得多；</li>
 *   <li>再删**纯口语/礼节**碎片（"帮我""请问""查一下""来着"…）；</li>
 *   <li>标点变空格、压掉多余空格、去重token、限制条数与长度；</li>
 *   <li>★ <b>时间词不删</b>（今天/明天/最新/现在）—— 它们正是"要新信息"的信号，删了反而变旧；</li>
 *   <li>★ 提炼后**太短/为空就退回原话**（宁可原样搜，也不要搜一个空词）；</li>
 *   <li>提炼后与原话相同 ⇒ 原样返回（调用方据此判断"这次提炼有没有起作用"）。</li>
 * </ol>
 */
public final class QueryClean {

    private QueryClean() {}

    /** 关键词最长多少字（必应对 2~6 个词的短语最准，太长它就乱匹配） */
    public static final int MAX_CHARS = 40;
    /** 最多几个 token */
    public static final int MAX_TOKENS = 6;
    /** 短于这个长度就不算"提炼成功"（退回原话） */
    public static final int MIN_CHARS = 2;

    /** 语义替换：问句里的"问题词" → 搜索时的"资料词"（按顺序替换，前面先匹配） */
    static final String[][] REPLACE = {
            {"多少钱", "价格"}, {"什么价格", "价格"}, {"卖多少钱", "价格"}, {"贵不贵", "价格"},
            {"什么时候", "时间"}, {"啥时候", "时间"}, {"哪一天", "时间"}, {"哪天", "时间"},
            {"几号", "时间"}, {"几点", "时间"},
            {"在哪里", "地点"}, {"在哪儿", "地点"}, {"在哪", "地点"}, {"什么地方", "地点"},
            {"怎么回事", "原因"}, {"为啥", "原因"}, {"为什么", "原因"},
            {"有多少", "数量"}, {"多少个", "数量"},
            {"是谁", "是谁"},
    };

    /**
     * 纯口语/礼节碎片（删掉它们不会丢信息）。
     * ★ 注意：**不含**时间词（今天/明天/最新/现在）与任何实体词 —— 那些是信息。
     */
    static final String[] DROP = {
            "帮我查一下", "帮我查查", "帮我查", "帮我搜", "帮我看看", "帮我", "帮忙",
            "麻烦你", "麻烦", "请问", "请帮我", "请",
            "我想知道", "我想问一下", "我想问", "我想了解", "我想", "我要知道", "我要问", "我要",
            "你能不能", "你可以", "你能", "可以帮我", "能不能", "可不可以",
            "查一下", "查一查", "查查", "查下", "搜一下", "搜一搜", "搜搜", "搜索",
            "看一下", "看一看", "看看", "告诉我", "跟我说", "给我讲讲", "讲讲",
            "是怎么回事", "怎么样啊", "怎么样", "怎么办", "咋整", "咋样",
            "是多少来着", "是多少", "是啥", "是哪个", "是什么", "有哪些",
            "来着", "的话", "一下", "一点", "一些", "嘛",
    };

    /** 句尾语气词（只在**结尾**剥掉，句中不动） */
    static final String TRAIL = "吗呢啊呀吧么哦噢啦了地得的";

    /**
     * 原话 → 搜索关键词。
     * <p>返回**非空**字符串：提炼失败（太短/没变化）时退回原话（去掉首尾空白）。
     */
    public static String keywords(String msg) {
        String raw = msg == null ? "" : msg.trim();
        if (raw.length() == 0) return "";
        String t = raw;
        for (String[] pair : REPLACE) {
            if (t.indexOf(pair[0]) >= 0) t = t.replace(pair[0], pair[1]);
        }
        for (String d : DROP) {
            if (t.indexOf(d) >= 0) t = t.replace(d, " ");
        }
        t = toSpaces(t);
        t = trimParticles(t);
        t = dedupe(t);
        t = limit(t);
        String out = t.trim();
        if (out.length() < MIN_CHARS) return raw;
        return out;
    }

    /** 这次提炼有没有起作用（给日志与界面用） */
    public static boolean changed(String msg) {
        String raw = msg == null ? "" : msg.trim();
        return !raw.equals(keywords(msg));
    }

    /** 标点/空白 → 单个空格；顺带把全角空格、常见标点处理掉 */
    static String toSpaces(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '　' || isPunct(c)) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return collapse(b.toString());
    }

    /** 标点（中英文都算；★ 不把 `%` `℃` `-` `+` 当标点 —— 它们是数据） */
    static boolean isPunct(char c) {
        if (c < 128) {
            if (c == '%' || c == '-' || c == '+' || c == '#' || c == '&') return false;
            return !(Character.isLetterOrDigit(c));
        }
        String P = "，。！？；：“”‘’（）【】《》、·—…～／　";
        return P.indexOf(c) >= 0 || c == '？' || c == '！' || c == '，' || c == '。';
    }

    static String collapse(String s) {
        StringBuilder b = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ') {
                if (!sp && b.length() > 0) b.append(' ');
                sp = true;
            } else {
                b.append(c);
                sp = false;
            }
        }
        return b.toString().trim();
    }

    /** 剥掉句尾的语气词（"今天天气啊" → "今天天气"） */
    static String trimParticles(String s) {
        String t = s;
        while (t.length() > 0 && TRAIL.indexOf(t.charAt(t.length() - 1)) >= 0) {
            t = t.substring(0, t.length() - 1).trim();
        }
        return t;
    }

    /** 去掉重复 token（"北京 北京 天气" → "北京 天气"） */
    static String dedupe(String s) {
        String[] parts = s.split(" +");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.length() == 0) continue;
            boolean dup = false;
            for (int j = 0; j < i; j++) {
                if (parts[j].equals(p)) { dup = true; break; }
            }
            if (dup) continue;
            if (b.length() > 0) b.append(' ');
            b.append(p);
        }
        return b.toString();
    }

    /** 限制 token 数与长度（必应对长句会乱匹配） */
    static String limit(String s) {
        String[] parts = s.split(" +");
        StringBuilder b = new StringBuilder();
        int used = 0;
        for (int i = 0; i < parts.length && used < MAX_TOKENS; i++) {
            String p = parts[i];
            if (p.length() == 0) continue;
            if (b.length() > 0 && b.length() + 1 + p.length() > MAX_CHARS) break;
            if (b.length() > 0) b.append(' ');
            b.append(p);
            used++;
        }
        String out = b.toString();
        if (out.length() == 0) out = s;
        // ★ 没有空格的长串（中文一句话常见）也要硬截 —— 否则"一句 120 字"会整个发给必应，
        //   它就开始按单字乱匹配（这正是"很笨"的成因之一）。
        if (out.length() > MAX_CHARS) out = out.substring(0, MAX_CHARS);
        return out;
    }
}
