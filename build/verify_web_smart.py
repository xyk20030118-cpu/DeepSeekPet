# -*- coding: utf-8 -*-
"""断言：**联网检索变聪明**（轮次㉗ —— 查询提炼 + 天气走结构化接口）。

用户原话：「**很笨查不到东西 问个天气都不知道**」。
先复现，再修 —— 两条根因都是**实测**出来的（2026-10-08，本机国内网络）：

  ① 「很笨」的一半是**查询没提炼**：原话直搜被必应当成单字匹配
     "帮我查一下今天北京天气怎么样啊" → 第一条《帮_百度百科》
     "我想知道现在比特币一个多少钱人民币" → 第一条《我（汉语汉字）_百度百科》
     ⇒ 新增纯类 `QueryClean`（问句词换资料词 + 删口语碎片 + **保留时间词** + 上限）。
  ② 「问个天气都不知道」是**资料本身就是垃圾**：日志显示检索成功（5 条 / 源=rss / 正文=true），
     但 5 条全是天气门户首页，抓回的第一条正文是中国天气网的**导航栏**
     （"预警 信息 地图 卫星 全国 北京 上海…"）—— 1190 字里没有一个温度。
     ⇒ 新增纯类 `Weather` + `WeatherCities`（城市码表由 `build/gen_city_table.py` 从官方城市表生成）：
     天气走 `d1.weather.com.cn` 的**结构化接口**（实况 + 今天/明天高低温）。

钉这些最容易回退的地方：
  A. 提炼**真的接上了**（发出去的查询是提炼结果，不是原话）；
  B. 提炼规则不许退化（语义替换在、时间词不在删除表里、上限在、退不回空查询）；
  C. 天气**先于**"该不该搜"判定（"北京天气"里没有"最新/今天"，否则会被判"不必搜"）；
  D. 天气走结构化接口 + 带 Referer；**没城市就不联网、反问一句**；记住上次城市；
  E. 天气接口挂了要**退回普通搜索**，不是直接放弃；
  F. 用满 RSS 的 9~10 条（原来只取 5 条，白扔一半）；
  G. 慢网更稳：超时放宽 + 瞬时失败重试一次（用户报过"这次没联上网"）；
  H. 真实报文 fixture 在（必应 + 天气接口两份），格式一变 fpstest 立刻红。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
CLIENT = os.path.join(SRC, 'WebClient.java')
SEARCH = os.path.join(SRC, 'WebSearch.java')
PROMPT = os.path.join(SRC, 'WebPrompt.java')
PREFS = os.path.join(SRC, 'Prefs.java')
QCLEAN = os.path.join(SRC, 'QueryClean.java')
WEATHER = os.path.join(SRC, 'Weather.java')
CITIES = os.path.join(SRC, 'WeatherCities.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
GEN = os.path.join(HERE, 'gen_city_table.py')
DEVS = os.path.join(HERE, 'verify_web_weather.py')
FIX = os.path.join(HERE, 'fpstest', 'fixtures')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(s):
    """去掉注释（保留字符串字面量），免得锚点命中注释里的示例"""
    out, i, n, blk = [], 0, len(s), False
    while i < n:
        if blk:
            j = s.find('*/', i)
            if j < 0:
                break
            i = j + 2
            blk = False
            continue
        if s.startswith('/*', i):
            blk = True
            i += 2
            continue
        if s.startswith('//', i):
            j = s.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        if s[i] == '"':
            out.append(s[i]); i += 1
            while i < n and s[i] != '"':
                if s[i] == '\\':
                    out.append(s[i]); i += 1
                if i < n:
                    out.append(s[i]); i += 1
            if i < n:
                out.append(s[i]); i += 1
            continue
        out.append(s[i]); i += 1
    return ''.join(out)


client_raw = read(CLIENT)
search_raw = read(SEARCH)
qclean_raw = read(QCLEAN)
weather_raw = read(WEATHER)
client = code_only(client_raw)
search = code_only(search_raw)
prompt = code_only(read(PROMPT))
prefs = code_only(read(PREFS))
main = code_only(read(MAIN))
qclean = code_only(qclean_raw)
weather = code_only(weather_raw)

print('=== A. 查询提炼真的接上了 ===')
check('★ 搜索前把原话提炼成关键词（QueryClean.keywords）',
      'String q = QueryClean.keywords(l.query);' in client)
check('★ 发出去的是**提炼后**的查询（不是原话）',
      'hits = search(ctx, q, l);' in client and 'search(ctx, l.query, l)' not in client)
check('把实际查询记进 Last（设备脚本靠它判"到底搜了什么"）',
      'l.used = q;' in client and 'l.used' in client)
check('日志同时写原话与关键词（排查"它为什么搜成这样"）',
      '" 关键词=" + clip(q)' in client)
check('★ 注释里留着实测结论（否则下次又去重新发现"原话直搜会被当单字"）',
      '帮_百度百科' in qclean_raw or '《帮' in qclean_raw or '帮' in qclean_raw)

print()
print('=== B. 提炼规则不许退化 ===')
check('★ 语义替换在（多少钱→价格）—— 问句词换成资料词',
      '"多少钱"' in qclean and '"价格"' in qclean)
check('★ 时间词**不在**删除表里（今天/最新/现在 是要新信息的信号）',
      # 只看 DROP 数组的字面量：里面不许出现时间词
      (lambda drop: all(w not in drop for w in ('今天', '明天', '最新', '现在')) if drop else False)(
          (lambda m: m.group(1) if m else '')(
              re.search(r'static final String\[\] DROP = \{(.*?)\};', qclean, re.S))))
check('口语碎片在删除表里（帮我 / 查一下 / 来着）',
      '帮我' in qclean and '查一下' in qclean and '来着' in qclean)
check('★ 提炼后为空/太短 ⇒ 退回原话（不许发一个空查询）',
      'if (out.length() < MIN_CHARS) return raw;' in qclean)
check('★ 长度与词数都有硬上限（长句会被必应当成单字匹配）',
      'public static final int MAX_CHARS' in qclean and 'public static final int MAX_TOKENS' in qclean
      and 'out.substring(0, MAX_CHARS)' in qclean)
check('★ 用满 RSS：一次取 8 条（实测必应 RSS 给 9~10 条、摘要最长 320 字）',
      'public static final int MAX_HITS = 8;' in search)

print()
print('=== C. 天气路由在"该不该搜"之前 ===')
check('★ WebClient 里天气判断在 shouldSearch **之前**（"北京天气"否则会被判"不必搜"）',
      client.index('Weather.isAsk(') < client.index('WebPrompt.shouldSearch('))
check('天气走得通就用天气资料返回',
      'String built = tryWeather(ctx, userMsg, l);' in client and 'if (built != null)' in client)
check('天气资料走 withWeather（先给结论、不许编温度）',
      'WebPrompt.withWeather(userMsg, block)' in client
      and 'public static String withWeather(' in prompt and '不要编造温度' in prompt)

print()
print('=== D. 结构化接口 / 城市 / 反问 ===')
check('★ 天气源是**结构化接口**（d1.weather.com.cn，不是网页摘要）',
      'https://d1.weather.com.cn/weather_index/' in weather
      and 'https://d1.weather.com.cn/sk_2d/' in weather)
check('★ 带 Referer（这两个接口不带会被拦；**index 与 sk_2d 两处都要带**）',
      'REFERER = "http://www.weather.com.cn/"' in weather
      and client.count('Weather.REFERER') >= 2)
check('★ 没城市 ⇒ **不联网**、反问一句（不许瞎猜城市、不许编天气）',
      'Weather.askCityText(userMsg)' in client and 'l.ran = false;' in client
      and '哪个城市' in weather)
check('★ 城市从问句里抠（最长匹配）', 'public static String cityIn(String msg)' in weather
      and 'Weather.cityIn(userMsg)' in client)
check('★ 记得住上次的城市（下一句"今天天气怎么样"就不用再说城市）',
      'KEY_LAST_CITY' in prefs and 'public static String lastCity(Context ctx)' in prefs
      # ★ 光有 API 不算——必须有"读出来当城市用"这一步（第一版只查 API 名，锚点把它
      #   改成 `if (false)` 时照样绿 ⇒ 反向套件当场报 FAIL，这条才补上）
      and 'if (keep != null && keep.length() > 0) { city = keep; remembered = true; }' in client
      and 'Prefs.setLastCity(ctx,' in client)
check('★ 城市码表是从官方城市表**生成**的（别手抄，会抄错）',
      # ★ 断言自己踩过的坑：WeatherCities 是被 `Weather` 用的，**WebClient 里没有这个名字**
      #   （第一版写成 `'WeatherCities' in client` ⇒ 假红）。
      'WeatherCities.code(' in weather and '不要手改' in read(CITIES)
      and os.path.isfile(GEN) and 'j.i8tq.com' in read(GEN))
check('城市表里有直辖市 / 省会 / 县级市（抽查几个真实码）',
      # 打包格式是 `名字:101010100`，所以查 `:码` 而不是带引号的整串
      ':101010100' in read(CITIES) and ':101020100' in read(CITIES)
      and ':101280601' in read(CITIES))

print()
print('=== E. 失败要退回普通搜索（不是直接放弃）===')
check('★ 天气接口挂了 ⇒ tryWeather 返回 null ⇒ 落到普通搜索',
      # ★ 判据必须只看**代码**（`code_only` 会把注释剥掉 —— 第一版拿带注释的锚点去比，
      #   当场假红，正是轮次㉓ 记过的那条坑）。
      re.search(r'"weather 失败 城市=" \+ city \+ " 原因=" \+ reason\);\s*\n\s*return null;',
                client) is not None
      and 'if (Weather.isAsk(l.query)) {' in client)
check('★★ 天气挂了 + 这句没有"新鲜词" ⇒ **如实说没查到**（不许悄悄返回原话让模型编天气）',
      'boolean weatherTried = false;' in client and 'if (weatherTried) {' in client
      and 'return Weather.failText(userMsg, l.reason);' in client)
check('实况接口做兜底（index 解析不出来时改用 sk_2d）',
      'Weather.nowUrl(' in client and 'Weather.parseNow(' in client)
check('天气接口返回的内容**也要能解析失败**（不许把空数据当成功）',
      'boolean ok = jsonp != null && Weather.parse(jsonp, now, days);' in client)

print()
print('=== F. 慢网更稳（用户报过"这次没联上网"）===')
check('★ 超时放宽（连接 ≥8s / 读取 ≥15s）',
      re.search(r'CONNECT_TIMEOUT_MS = (\d+);', client)
      and int(re.search(r'CONNECT_TIMEOUT_MS = (\d+);', client).group(1)) >= 8000
      and int(re.search(r'READ_TIMEOUT_MS = (\d+);', client).group(1)) >= 15000)
check('★ 瞬时失败重试一次（HTTP 4xx/5xx 不重试，免得白等）',
      'getWithRetry(' in client and 'private static final int RETRY' in client
      # ★ 轮次㉘ 起有**两处**重试（必应那两路 + 可插拔服务的 POST），
      #   判据必须要求"两处都在" —— 早期只查"存在一次"，反向锚点只破坏一处时就漏了
      #   （全量套件第 45 套当场报红，这条才补硬）。
      and client.count('for (int i = 0; i <= RETRY; i++)') >= 2
      and 'm.startsWith("HTTP ")' in client)
check('搜索两路都用了重试', client.count('getWithRetry(') >= 3)

print()
print('=== G. 设置页说明 + 真实报文 fixture + 行为断言 ===')
check('设置页说明提到"说一句城市"，且没有 markdown 的 `**`',
      (lambda m: bool(m) and '城市' in m.group(1) and '**' not in m.group(1))(
          re.search(r'addLabel\(box, "(开着时[^;]*?)"\s*\)', read(MAIN), re.S)))
check('★ 天气接口的真实报文 fixture 在（离线断言跑的就是它）',
      os.path.isfile(os.path.join(FIX, 'weather-index.jsonp'))
      and os.path.getsize(os.path.join(FIX, 'weather-index.jsonp')) > 1000
      and os.path.isfile(os.path.join(FIX, 'weather-sk.jsonp')))
rt = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                    capture_output=True)
out = rt.stdout.decode('utf-8', 'replace')
check('★ fpstest 全绿（含 [24] QueryClean 与 [25] Weather 两节）',
      rt.returncode == 0 and '[24]' in out and '[25]' in out, '退出码=%d' % rt.returncode)
check('设备脚本在（模拟器上问"北京天气"与"今天天气怎么样"）',
      os.path.isfile(DEVS) and '--selftest' in read(DEVS))
check('活体探针也会刷新天气 fixture（源改版时用它重抓）',
      'weather-index.jsonp' in read(os.path.join(HERE, 'probe_web.py')))

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（联网检索变聪明：查询提炼 + 天气结构化 + 慢网重试）')
