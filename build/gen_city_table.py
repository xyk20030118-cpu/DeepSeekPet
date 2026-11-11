# -*- coding: utf-8 -*-
"""从中国天气网的官方城市表生成 `src/com/deepseek/pet/WeatherCities.java`。

数据源：`http://j.i8tq.com/weather2020/search/city.js`（382 KB，含 3221 个 AREAID）
  var city_data = { "省": { "市": { "区县": {"AREAID": "101010100", "NAMECN": "…"} } } }

为什么要生成而不是手写：
  ★ 轮次㉗ 的教训 —— 用户问"北京天气"，搜索给回来的是**一堆天气门户的导航栏**，
  模型拿不到一个温度。天气必须走**结构化源**（`d1.weather.com.cn/sk_2d/<code>.html`），
  而那个源要的是 `101xxxxxx` 城市码 ⇒ 必须有一张"城市名 → 代码"的表。

生成策略（写进 Java 类的注释里，免得下次不知道为什么是这样）：
  * 省（depth1）→ 用它的**省会/首城**的码（"吉林"→吉林市/长春的第一个城市节点）；
    直辖市（北京/上海/天津/重庆）本身就是城市。
  * 市（depth2）→ 自己的节点若有 AREAID 就用它，否则取"名字相同的子节点"，再否则取第一个子节点。
  * 区县（depth3）→ 自己的 AREAID（让"义乌天气""海淀天气"也能查到）。
  * **同名只留一条**，优先级 depth1 > depth2 > depth3（"吉林"是省也是市 ⇒ 留省那条，
    指向省会的天气更符合"吉林天气"的直觉）；同层同名留**先出现**的。
  * 打包成若干段字符串常量（每段 < 1200 条），避免单个字符串字面量撞上
    class 文件常量池 65535 字节上限（中文在 modified-UTF-8 里一个字 3 字节）。

用法：
    python build/gen_city_table.py            # 抓 + 生成 + 打印统计
    python build/gen_city_table.py --check    # 只抓 + 统计，不写文件
"""
import io
import json
import os
import re
import sys
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'WeatherCities.java')
SRC = 'http://j.i8tq.com/weather2020/search/city.js'
UA = ('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/120 Mobile Safari/537.36')

# 每一段打包多少条（保守：1000 条中文键 ≈ 20~26 KB modified-UTF-8，远低于 64 KB）
CHUNK = 1000


def fetch():
    req = urllib.request.Request(SRC, headers={'User-Agent': UA, 'Referer': 'http://www.weather.com.cn/'})
    with urllib.request.urlopen(req, timeout=20) as r:
        txt = r.read(1024 * 1024).decode('utf-8', 'replace')
    i = txt.find('{')
    j = txt.rfind('}')
    return json.loads(txt[i:j + 1])


def pick(node):
    """一个城市/区县节点的码：自己有 AREAID 就用，否则找同名子节点，再否则第一个子节点"""
    if not isinstance(node, dict):
        return ''
    if node.get('AREAID'):
        return str(node['AREAID'])
    for k, v in node.items():
        if isinstance(v, dict) and v.get('AREAID'):
            return str(v['AREAID'])
    for k, v in node.items():
        if isinstance(v, dict):
            c = pick(v)
            if c:
                return c
    return ''


def build(data):
    table = {}

    def put(name, code, depth):
        name = (name or '').strip()
        if not name or not re.match(r'^\d{9}$', code or ''):
            return
        old = table.get(name)
        # 优先级：小的 depth 赢（省 > 市 > 区县）
        if old is None or depth < old[1]:
            table[name] = (code, depth)

    for prov, cities in data.items():
        if not isinstance(cities, dict):
            continue
        put(prov, pick(cities), 1)
        for city, areas in cities.items():
            if not isinstance(areas, dict):
                continue
            put(city, pick(areas), 2)
            for area, node in areas.items():
                if isinstance(node, dict) and node.get('AREAID'):
                    put(area, str(node['AREAID']), 3)
    return table


def main():
    check = '--check' in sys.argv
    data = fetch()
    table = build(data)
    lv = {1: 0, 2: 0, 3: 0}
    for _, d in table.values():
        lv[d] += 1
    print('城市表：%d 条（省 %d / 市 %d / 区县 %d）' % (len(table), lv[1], lv[2], lv[3]))
    for probe in ('北京', '上海', '深圳', '杭州', '乌鲁木齐', '拉萨', '义乌', '海淀', '吉林', '香港'):
        print('   %-6s -> %s' % (probe, table.get(probe, ('(无)', 0))[0]))
    if check:
        return 0

    items = sorted(table.items(), key=lambda kv: (kv[1][1], kv[0]))
    chunks = [items[i:i + CHUNK] for i in range(0, len(items), CHUNK)]
    lines = []
    for c in chunks:
        body = ','.join('%s:%s' % (n, v[0]) for n, v in c)
        lines.append('            "%s",' % body)

    src = u'''package com.deepseek.pet;

import java.util.HashMap;
import java.util.Map;

/**
 * 城市名 → 中国天气网城市码（{@code 101xxxxxx}）—— <b>纯类，零 Android 依赖</b>。
 *
 * <p>★ <b>本文件由 {@code build/gen_city_table.py} 生成，不要手改</b>：
 * 数据来自中国天气网的官方城市表 {@code http://j.i8tq.com/weather2020/search/city.js}
 * （3221 个 AREAID）。生成规则见那个脚本的头注释。
 *
 * <h3>为什么需要它（轮次㉗ 的根因）</h3>
 * 用户问"今天天气怎么样"，原来的实现拿**原话**去必应搜，搜索结果全是天气门户首页
 * （中国天气网 / 2345 / 腾讯天气），抓回来第一条正文是**导航栏文字**（"预警 信息 地图 卫星 全国…"）
 * —— 一个温度都没有 ⇒ 模型只能说"没查到"。天气这类问题必须走**结构化源**：
 * {@code http://d1.weather.com.cn/sk_2d/<code>.html}（实况 JSONP，~370 字节）
 * ＋ {@code .../weather_index/<code>.html}（今天/明天高低温），两者都要城市码。
 *
 * <p>表里共 %d 条：省 %d / 市 %d / 区县 %d。同名只留一条（省 &gt; 市 &gt; 区县），
 * 所以"吉林"指向吉林市、"义乌""海淀"这类县级/区级也能命中。
 */
public final class WeatherCities {

    private WeatherCities() {}

    /** 打包的城市表（每段 %d 条；分段是为了不撞 class 常量池 65535 字节上限） */
    private static final String[] PACKED = {
%s
    };

    private static Map<String, String> MAP;

    /** 查城市码；查不到返回 ""（调用方据此决定"要不要反问用户是哪个城市"） */
    public static String code(String city) {
        if (city == null) return "";
        String key = norm(city);
        if (key.length() == 0) return "";
        String v = map().get(key);
        return v == null ? "" : v;
    }

    /** 表里有多少个城市（断言用） */
    public static int size() {
        return map().size();
    }

    /**
     * 归一化：去空白/标点，并**去掉行政级别后缀**（"北京市"→"北京"、"朝阳区"→"朝阳"、
     * "阿坝藏族羌族自治州"→"阿坝藏族羌族"）。查表时原文与归一化各试一次。
     */
    static String norm(String s) {
        String t = s.trim();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || c == '　') continue;
            b.append(c);
        }
        String r = b.toString();
        String[] SUF = {"特别行政区", "自治区", "自治州", "自治县", "地区", "盟",
                        "省", "市", "区", "县"};
        for (String suf : SUF) {
            if (r.length() > suf.length() && r.endsWith(suf)) return r.substring(0, r.length() - suf.length());
        }
        return r;
    }

    private static synchronized Map<String, String> map() {
        if (MAP != null) return MAP;
        Map<String, String> m = new HashMap<String, String>();
        for (String pack : PACKED) {
            int from = 0;
            while (from < pack.length()) {
                int comma = pack.indexOf(',', from);
                if (comma < 0) comma = pack.length();
                String one = pack.substring(from, comma);
                int colon = one.lastIndexOf(':');
                if (colon > 0) {
                    m.put(one.substring(0, colon), one.substring(colon + 1));
                }
                from = comma + 1;
            }
        }
        MAP = m;
        return MAP;
    }
}
''' % (len(table), lv[1], lv[2], lv[3], CHUNK, '\n'.join(lines))

    with io.open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(src)
    print('已写入 %s（%d 字节，%d 段）' % (OUT, len(src.encode('utf-8')), len(chunks)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
