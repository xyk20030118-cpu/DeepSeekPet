package com.deepseek.pet;

/**
 * 天气问句的**识别、城市提取与结构化解析** —— <b>纯类，零 Android 依赖</b>。
 *
 * <h3>为什么天气要单独走一条路（轮次㉗ 实测抓到的根因）</h3>
 * 用户问「今天天气怎么样」，日志显示检索**成功**了（`augment 成功 q=今天天气怎么样 条数=5 源=rss 正文=true`），
 * 但复刻那条链路一看就知道模型为什么答不出来：
 * <pre>
 *   搜到的 5 条全是天气门户首页（中国天气网 / 2345 / 腾讯天气 / 好天气…），
 *   摘要写着「天气预报-中国天气网. 预警. 信息. 地图. 卫星. 全国. 北京. 上海…」，
 *   抓回来的第一条正文是**导航栏文字**（"预警 信息 地图 卫星 全国 北京 上海 天津…"），
 *   1190 字里**一个温度都没有** ⇒ 模型只能说"没查到"。
 * </pre>
 * 天气/气温这类问题**本来就不该靠网页摘要**：中国天气网有免 Key 的结构化接口
 * （{@code d1.weather.com.cn/weather_index/<城市码>.html}，~4.7 KB，实测 0.1~0.4 秒），
 * 里面有实况（温度/天气现象/风/湿度/气压/能见度/降雨/空气质量/今日限行/观测时间）
 * 和未来 40 天的高低温。只要把**城市码**拿到手（见 {@link WeatherCities}），
 * 就能给模型一段**有数**的资料，而不是一段导航栏。
 *
 * <h3>城市从哪来</h3>
 * 问句里找（最长匹配，{@link #cityIn}）→ 上一轮记住的城市（{@code Prefs.lastCity}）
 * → 都没有就**不联网、让桌宠反问一句"哪个城市"**（{@link #askCityText}）。
 * 反问比"瞎猜一个城市"或"答一段废话"都好 —— 用户要的是温度，不是道歉。
 */
public final class Weather {

    private Weather() {}

    /** 天气意图关键词（命中任意一个就算"在问天气"） */
    static final String[] ASK = {
            "天气", "气温", "温度", "多少度", "几度", "下雨", "降雨", "会不会下雨", "带伞", "雨伞",
            "冷不冷", "热不热", "冷吗", "热吗", "穿什么", "穿多", "穿衣", "降温", "升温",
            "雾霾", "空气质量", "空气怎么样", "空气好吗", "限行", "湿度", "紫外线", "台风",
            "下雪", "刮风", "风大",
    };

    /**
     * 英文天气意图（整词匹配）。
     *
     * <p>为什么要支持英文：① 用户真的会用英文问；② **设备脚本只能用 ASCII**
     * （`adb shell input text` 不支持中文）—— 没有英文入口，"天气这条链路在设备上跑通"
     * 就只能靠纯断言，而设备验证是本工程的硬要求。
     */
    static final String[] ASK_EN = {
            "weather", "temperature", "temp", "forecast", "rain", "raining", "snow", "snowing",
            "umbrella", "humidity", "humid", "windy", "aqi", "air quality", "how hot", "how cold",
            "degrees",
    };

    /**
     * 英文城市名（含拼音）→ 中文城市名。
     *
     * <p>表里只放**常见**城市（几十个）；查不到就按"没写城市"处理（反问一句），
     * 不会猜错。城市码本身走 {@link WeatherCities}（中文名 → 101xxxxxx）。
     */
    static final String[] ALIAS = {
            "beijing:北京", "shanghai:上海", "tianjin:天津", "chongqing:重庆",
            "guangzhou:广州", "shenzhen:深圳", "hangzhou:杭州", "nanjing:南京",
            "suzhou:苏州", "wuxi:无锡", "ningbo:宁波", "wenzhou:温州",
            "wuhan:武汉", "chengdu:成都", "xian:西安", "changsha:长沙",
            "zhengzhou:郑州", "jinan:济南", "qingdao:青岛", "dalian:大连",
            "shenyang:沈阳", "harbin:哈尔滨", "changchun:长春", "kunming:昆明",
            "guiyang:贵阳", "nanning:南宁", "fuzhou:福州", "xiamen:厦门",
            "hefei:合肥", "nanchang:南昌", "taiyuan:太原", "shijiazhuang:石家庄",
            "lanzhou:兰州", "urumqi:乌鲁木齐", "lhasa:拉萨", "hohhot:呼和浩特",
            "yinchuan:银川", "xining:西宁", "haikou:海口", "sanya:三亚",
            "hongkong:香港", "hong kong:香港", "macau:澳门", "macao:澳门",
            "taipei:台北", "foshan:佛山", "dongguan:东莞", "zhuhai:珠海", "yiwu:义乌",
    };

    /** 中国天气网的天气现象代码 → 文字（接口里 fc/fa/fb 给的是代码） */
    static final String[] CODE = {
            "晴", "多云", "阴", "阵雨", "雷阵雨", "雷阵雨伴有冰雹", "雨夹雪", "小雨", "中雨", "大雨",
            "暴雨", "大暴雨", "特大暴雨", "阵雪", "小雪", "中雪", "大雪", "暴雪", "雾", "冻雨",
            "沙尘暴", "小到中雨", "中到大雨", "大到暴雨", "暴雨到大暴雨", "大暴雨到特大暴雨",
            "小到中雪", "中到大雪", "大到暴雪", "浮尘", "扬沙", "强沙尘暴",
    };

    /** 数据源（结构化，免 Key）；{@code code} 是 101xxxxxx 城市码 */
    public static String indexUrl(String code, long ms) {
        return "https://d1.weather.com.cn/weather_index/" + code + ".html?_=" + ms;
    }

    /** 兜底源：只有实况，比 index 更小 */
    public static String nowUrl(String code, long ms) {
        return "https://d1.weather.com.cn/sk_2d/" + code + ".html?_=" + ms;
    }

    /** 这两个接口要 Referer（不带会被拦） */
    public static final String REFERER = "http://www.weather.com.cn/";

    /**
     * 第三路兜底：**wttr.in**（轮次㉙ 加的）。
     *
     * <p>为什么还要一路：用户报过 429（对面限流），而中国天气网的 index/sk_2d 是**同一台主机**
     * —— 它一限流，两路一起挂。wttr.in 在另一台机器上，实测 200/1.8s，用**拼音城市名**就能查
     * （我们的英文别名表里正好有）。
     *
     * <p>★ 注意：wttr.in **不能带 cache-buster**（实测 `&_=<时间戳>` 直接 500）；
     * 反正我们本地有缓存（{@link WebCache}），不需要它。
     */
    public static String wttrUrl(String cityEn, long ignoredMs) {
        return "https://wttr.in/" + encPath(cityEn) + "?format=j1";
    }

    static String encPath(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20");
        } catch (Throwable t) {
            return "";
        }
    }

    /** 中文城市名 → 英文/拼音（英文别名表的反向查；查不到返回 ""） */
    public static String enOf(String city) {
        if (city == null || city.length() == 0) return "";
        for (String pair : ALIAS) {
            int colon = pair.indexOf(':');
            if (colon > 0 && pair.substring(colon + 1).equals(city)) {
                return pair.substring(0, colon);
            }
        }
        return "";
    }

    /**
     * 解析 wttr.in 的 {@code format=j1} 响应（只有实况，没有预报）。
     *
     * <p>字段：{@code current_condition[0].temp_C / weatherDesc[0].value / humidity /
     * windspeedKmph / winddir16Point}，城市名在 {@code nearest_area[0].areaName[0].value}。
     */
    public static boolean parseWttr(String json, Now now) {
        if (json == null || now == null) return false;
        Object root = Json.parseOrNull(json);
        if (root == null) return false;
        String temp = Json.text(root, "current_condition[0].temp_C");
        if (temp.length() == 0) return false;
        now.temp = temp;
        now.weather = Json.text(root, "current_condition[0].weatherDesc[0].value");
        String kmph = Json.text(root, "current_condition[0].windspeedKmph");
        now.ws = kmph.length() > 0 ? (kmph + "km/h") : "";
        now.wd = Json.text(root, "current_condition[0].winddir16Point");
        String hum = Json.text(root, "current_condition[0].humidity");
        now.sd = hum.length() > 0 ? (hum + "%") : "";
        String city = Json.text(root, "nearest_area[0].areaName[0].value");
        now.city = city.length() > 0 ? city : now.city;
        now.date = "（来自 wttr.in）";
        return now.ok();
    }

    // ==================== 意图与城市 ====================

    /** 这句话是不是在问天气（中文关键词 或 英文整词） */
    public static boolean isAsk(String msg) {
        if (msg == null) return false;
        String t = msg.trim();
        if (t.length() == 0) return false;
        for (String k : ASK) {
            if (t.indexOf(k) >= 0) return true;
        }
        String low = t.toLowerCase();
        for (String w : ASK_EN) {
            if (hasWord(low, w)) return true;
        }
        return false;
    }

    /** 整词匹配（[a-z0-9] 算词字符；"show" 里的 "ho" 不算命中 "how"） */
    static boolean hasWord(String low, String w) {
        if (low == null || w == null || w.length() == 0) return false;
        int i = low.indexOf(w);
        while (i >= 0) {
            boolean leftOk = (i == 0) || !isWordChar(low.charAt(i - 1));
            int j = i + w.length();
            boolean rightOk = (j >= low.length()) || !isWordChar(low.charAt(j));
            if (leftOk && rightOk) return true;
            i = low.indexOf(w, i + 1);
        }
        return false;
    }

    static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    /**
     * 从问句里找城市名（**最长匹配优先**；查不到返回 ""）。
     * <p>先按中文查城市码表（最长优先是为了让"石家庄"赢过"石"、"乌鲁木齐"赢过"乌鲁"，
     * 长度上限 7 覆盖"阿坝藏族羌族"这类）；再按英文别名查（"weather in beijing"）。
     */
    public static String cityIn(String msg) {
        if (msg == null) return "";
        String t = msg.trim();
        int n = t.length();
        int maxLen = Math.min(7, n);
        for (int len = maxLen; len >= 2; len--) {
            for (int i = 0; i + len <= n; i++) {
                String cand = t.substring(i, i + len);
                if (WeatherCities.code(cand).length() > 0) return cand;
            }
        }
        String low = t.toLowerCase();
        String best = "";
        for (String pair : ALIAS) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            String en = pair.substring(0, colon);
            if (en.length() <= best.length()) continue;
            if (hasWord(low, en)) best = en;
        }
        if (best.length() == 0) return "";
        for (String pair : ALIAS) {
            int colon = pair.indexOf(':');
            if (colon > 0 && pair.substring(0, colon).equals(best)) return pair.substring(colon + 1);
        }
        return "";
    }

    /** 城市名 → 城市码（查不到 ""） */
    public static String code(String city) {
        return WeatherCities.code(city);
    }

    // ==================== 解析（无第三方 JSON 库，纯文本取字段）====================

    /** 实况（来自 {@code var dataSK}） */
    public static final class Now {
        public String city = "", temp = "", weather = "", wd = "", ws = "", sd = "";
        public String qy = "", njd = "", rain = "", rain24h = "", aqi = "", limit = "";
        public String time = "", date = "";

        public boolean ok() {
            return city.length() > 0 && temp.length() > 0;
        }
    }

    /** 一天的预报（来自 {@code var fc} 的 {@code f[]}） */
    public static final class Day {
        public String label = "", date = "", high = "", low = "";
        public String windDay = "", windNight = "", levelDay = "", levelNight = "";
        public String codeDay = "", codeNight = "";

        /** "多云转晴" / "多云" / "晴" */
        public String text() {
            String a = codeText(codeDay);
            String b = codeText(codeNight);
            if (a.length() == 0 && b.length() == 0) return "";
            if (b.length() == 0 || a.equals(b)) return a;
            return a + "转" + b;
        }

        /** "11~26℃" */
        public String range() {
            if (high.length() == 0 || low.length() == 0) return "";
            return low + "~" + high + "℃";
        }
    }

    /** 天气现象代码 → 文字（"01" → 多云） */
    public static String codeText(String code) {
        if (code == null) return "";
        String d = digits(code);
        if (d.length() == 0) return "";
        int v;
        try { v = Integer.parseInt(d); } catch (Throwable t) { return ""; }
        if (v >= 0 && v < CODE.length) return CODE[v];
        if (v == 53) return "霾";
        return "";
    }

    /**
     * 解析 {@code weather_index} 的整段响应。
     *
     * @param jsonp 响应原文（里面有 {@code var cityDZ/dataSK/fc/alarmDZ}）
     * @param now   出参：实况（可为 null）
     * @param days  出参：预报（按接口顺序，第 0 天通常是"今天"；可为 null）
     * @return 解析出任何可用数据了吗
     */
    public static boolean parse(String jsonp, Now now, java.util.List<Day> days) {
        if (jsonp == null || jsonp.length() == 0) return false;
        boolean any = false;
        String sk = obj(jsonp, "dataSK");
        if (sk.length() > 0 && now != null) {
            now.city = str(sk, "cityname");
            now.temp = str(sk, "temp");
            now.weather = str(sk, "weather");
            now.wd = str(sk, "WD");
            now.ws = str(sk, "WS");
            now.sd = str(sk, "SD");
            now.qy = str(sk, "qy");
            now.njd = str(sk, "njd");
            now.rain = str(sk, "rain");
            now.rain24h = str(sk, "rain24h");
            now.aqi = str(sk, "aqi");
            now.limit = str(sk, "limitnumber");
            now.time = str(sk, "time");
            now.date = str(sk, "date");
            if (now.ok()) any = true;
        }
        String fc = obj(jsonp, "fc");
        String arr = arr(fc, "f");
        if (arr.length() > 0 && days != null) {
            for (String one : objects(arr)) {
                Day d = new Day();
                d.label = str(one, "fj");
                d.date = str(one, "fi");
                d.high = num(str(one, "fc"));
                d.low = num(str(one, "fd"));
                d.windDay = str(one, "fe");
                d.windNight = str(one, "ff");
                d.levelDay = str(one, "fg");
                d.levelNight = str(one, "fh");
                d.codeDay = str(one, "fa");
                d.codeNight = str(one, "fb");
                if (d.high.length() > 0 || d.codeDay.length() > 0) {
                    days.add(d);
                    any = true;
                }
            }
        }
        // cityDZ 只在 fc 完全没解析出来时用来补"今天"的天气文字
        if (days != null && days.isEmpty()) {
            String dz = obj(jsonp, "cityDZ");
            String w = str(dz, "weather");
            if (w.length() > 0) {
                Day d = new Day();
                d.label = "今天";
                d.codeDay = "";
                d.high = "";
                d.low = num(str(dz, "tempn"));
                if (d.low.length() > 0) d.low = d.low + "℃";
                days.add(d);
                any = true;
            }
        }
        return any;
    }

    /** 只有实况的响应（{@code sk_2d}）：{@code var dataSK={...}} */
    public static boolean parseNow(String jsonp, Now now) {
        return parse(jsonp, now, null);
    }

    // ==================== 拼给模型的资料块 ====================

    /** 结构化天气资料块（模型看到的是**数字**，不是导航栏） */
    public static String block(String city, Now now, java.util.List<Day> days) {
        StringBuilder b = new StringBuilder();
        b.append("【天气资料（结构化接口，中国天气网 d1.weather.com.cn，App 直接抓的数）】\n");
        String name = (now != null && now.city.length() > 0) ? now.city : city;
        b.append("城市：").append(name).append('\n');
        if (now != null && now.ok()) {
            b.append("实况");
            if (now.date.length() > 0 || now.time.length() > 0) {
                b.append("（").append(now.date).append(' ').append(now.time).append("）");
            }
            b.append("：").append(now.temp).append("℃");
            if (now.weather.length() > 0) b.append(' ').append(now.weather);
            if (now.wd.length() > 0 || now.ws.length() > 0) {
                b.append("；风：").append(now.wd).append(' ').append(now.ws);
            }
            if (now.sd.length() > 0) b.append("；湿度 ").append(now.sd);
            if (now.qy.length() > 0) b.append("；气压 ").append(now.qy).append("hPa");
            if (now.njd.length() > 0) b.append("；能见度 ").append(now.njd);
            if (now.rain.length() > 0) {
                b.append("；降雨 ").append(now.rain).append("mm");
                if (now.rain24h.length() > 0) b.append("（24小时 ").append(now.rain24h).append("mm）");
            }
            if (now.aqi.length() > 0) b.append("；空气质量 ").append(now.aqi);
            if (now.limit.length() > 0) b.append("；今日限行 ").append(now.limit);
            b.append('\n');
        }
        if (days != null) {
            int n = 0;
            for (Day d : days) {
                if (d == null || n >= 3) break;
                String label = d.label.length() > 0 ? d.label : ("第" + (n + 1) + "天");
                b.append(label);
                if (d.date.length() > 0) b.append("（").append(d.date).append("）");
                b.append("：");
                String txt = d.text();
                if (txt.length() > 0) b.append(txt).append('，');
                String r = d.range();
                if (r.length() > 0) b.append(r);
                if (d.windDay.length() > 0 || d.levelDay.length() > 0) {
                    b.append("，").append(d.windDay).append(' ').append(d.levelDay);
                }
                b.append('\n');
                n++;
            }
        }
        b.append("【资料结束】");
        return b.toString();
    }

    /** 没写城市时给模型的话：**反问一句**，别瞎猜、也别拿旧知识编 */
    public static String askCityText(String userMsg) {
        return "【联网检索：缺少城市】用户像是在问天气，但话里没有城市，App 也没有记住他上次问的城市。\n"
                + "请用一句很短的话反问他「是哪个城市」（例如：「哪个城市呀？说了我马上帮你看～」），"
                + "**不要**随便挑一个城市回答，也**不要**凭你已有的知识编一份天气。\n"
                + "用户的问题是：" + (userMsg == null ? "" : userMsg.trim());
    }

    /** 接口打不开时的兜底话术（与搜索失败一样：如实说没查到） */
    public static String failText(String userMsg, String reason) {
        StringBuilder b = new StringBuilder();
        b.append("【天气接口没取到数据】App 直接查了中国天气网的结构化接口，但没成功");
        if (reason != null && reason.trim().length() > 0) b.append("（").append(reason.trim()).append("）");
        b.append("。请如实告诉用户这次没查到天气，可以凭已有知识回答但要说明不是刚查的，不要编造温度。\n");
        b.append("用户的问题是：").append(userMsg == null ? "" : userMsg.trim());
        return b.toString();
    }

    // ==================== 文本小工具 ====================

    /**
     * 取 {@code var name} / {@code "name"} 后面的 JSON 对象（大括号配对；找不到返回 ""）。
     *
     * <p>★ 名字必须按**词边界**匹配：真实报文里 {@code "fctime"} 含子串 {@code fc}，
     * 早期版本用裸 {@code indexOf("fc")} 就抓到了 fctime，于是"今天/明天"整段解析不出来
     * （fpstest [25] 的"预报天数"当场报红 —— 这就是断言跑在真实报文上的价值）。
     */
    public static String obj(String s, String name) {
        if (s == null) return "";
        int at = indexOfName(s, name);
        if (at < 0) return "";
        int open = s.indexOf('{', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(open, i + 1);
            }
        }
        return "";
    }

    /** 找 {@code "name"}（JSON 键）或 {@code var name =}（JS 变量），要求两侧是词边界 */
    static int indexOfName(String s, String name) {
        String quoted = '"' + name + '"';
        int i = s.indexOf(quoted);
        int j = 0;
        while (true) {
            j = s.indexOf(name, j);
            if (j < 0) break;
            boolean leftOk = (j == 0) || !isNameChar(s.charAt(j - 1));
            int e = j + name.length();
            boolean rightOk = (e >= s.length()) || !isNameChar(s.charAt(e));
            if (leftOk && rightOk) return j;
            j = e;
        }
        return i;   // 词边界没找到就退回"引号键"的命中位置（可能为 -1）
    }

    static boolean isNameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_';
    }

    /** 取 {@code "key":[...]} 的数组原文（方括号配对；找不到返回 ""） */
    public static String arr(String s, String key) {
        if (s == null) return "";
        int at = s.indexOf('"' + key + '"');
        if (at < 0) return "";
        int open = s.indexOf('[', at);
        if (open < 0) return "";
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return s.substring(open, i + 1);
            }
        }
        return "";
    }

    /** 把数组拆成元素原文（只处理对象数组，够用） */
    public static java.util.List<String> objects(String arr) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (arr == null) return out;
        int depth = 0, start = -1;
        for (int i = 0; i < arr.length(); i++) {
            char c = arr.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) out.add(arr.substring(start, i + 1));
            }
        }
        return out;
    }

    /** 取 {@code "key":"value"} 的值（也认数字/布尔；找不到返回 ""） */
    public static String str(String obj, String key) {
        if (obj == null || obj.length() == 0) return "";
        String k = '"' + key + '"';
        int at = obj.indexOf(k);
        if (at < 0) return "";
        int colon = obj.indexOf(':', at + k.length());
        if (colon < 0) return "";
        int i = colon + 1;
        while (i < obj.length() && obj.charAt(i) == ' ') i++;
        if (i >= obj.length()) return "";
        char c = obj.charAt(i);
        if (c == '"') {
            int end = obj.indexOf('"', i + 1);
            if (end < 0) return "";
            return unescape(obj.substring(i + 1, end)).trim();
        }
        int end = i;
        while (end < obj.length() && ",}]".indexOf(obj.charAt(end)) < 0) end++;
        return obj.substring(i, end).trim();
    }

    static String unescape(String s) {
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", " ");
    }

    /** 只留数字与小数点（"16.7" 保留；"999" 这种占位由调用方忽略） */
    static String num(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') b.append(c);
        }
        return b.toString();
    }

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
