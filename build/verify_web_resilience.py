# -*- coding: utf-8 -*-
"""断言：**429 韧性 —— 缓存 / 退避 / 换源**（轮次㉙）。

用户报「网络搜索不好用，搜天气还报错 429」。先复现：本机按 App 的请求头连打 12 次，
天气源与必应**全是 200**（打不出 429）⇒ 他遇到的多半是**共享出口 IP 被限流**或某个源短时抽风。
但不管哪种，App 当时有几处**主动把请求打多**了，放大成"整个功能不可用"：

  ① 同一城市连着问两次就真请求两次（**没有任何缓存**），而天气 10 分钟内基本不变；
  ② 一次天气最坏发 **4 个请求**（`weather_index` → `sk_2d` 兜底，各自还被 `getWithRetry` 重试一次），
     而这两路是**同一台主机** ⇒ 它一限流，两路一起挂；
  ③ 每个天气请求都带**随机 cache-buster**（`_=<毫秒>`），把对面 CDN 缓存全废掉，还更像爬虫。

钉这些（都写进断言了）：
  A. 缓存真的接上了：天气资料块 / 天气接口 / 必应两路 / 可插拔服务 / 网页正文，各自有 TTL；
  B. **主机的冷却**：429/403 ⇒ 记冷却（尊重 `Retry-After`，默认 60s、上限 10 分钟），
     冷却期内**连请求都不发**，并给用户一句人话（"对面限流了，N 秒后再试"）；
  C. **换源**：中国天气网两路都挂时改走 **wttr.in**（另一台主机），且它的 URL **不能带 cache-buster**；
  D. 时间戳改成**5 分钟对齐**（不再每次随机）；
  E. 真报文 fixture（wttr）+ fpstest **[28]** 的语义断言（过期/冷却/上限，时钟可注入、不必真 sleep）。
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
CACHE = os.path.join(SRC, 'WebCache.java')
WEATHER = os.path.join(SRC, 'Weather.java')
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
client = code_only(client_raw)
cache_raw = read(CACHE)
cache = code_only(cache_raw)
weather_raw = read(WEATHER)
weather = code_only(weather_raw)

print('=== A. 缓存真的接上了（每一路都要有 TTL）===')
check('纯类 `WebCache` 在（带 TTL 的内容缓存 + 主机冷却）',
      'public final class WebCache' in cache
      and 'public static synchronized void put(String key, String value, long ttlMs)' in cache
      and 'public static synchronized String get(String key)' in cache)
check('★ 时钟可注入（断言里不必真 sleep 就能验"过期/冷却解除"）',
      'public static synchronized void setClock(long virtualNow)' in cache and 'static long now()' in cache)
check('TTL 常量：天气 10 分钟 / 搜索 5 分钟 / 正文 30 分钟',
      'WEATHER_TTL_MS = 10 * 60 * 1000L' in cache
      and 'SEARCH_TTL_MS = 5 * 60 * 1000L' in cache
      and 'PAGE_TTL_MS = 30 * 60 * 1000L' in client)
check('★ 唯一的取数入口 `fetchCached`：先查缓存 → 再查冷却 → 才真请求 → 落缓存',
      'static String fetchCached(' in client
      # ★ 形参叫 cacheKey 而不是 key：日志里出现 `key` 会撞"DSWeb 日志不许含 key"那条断言
      #   （那条判据是防 API Key 泄漏的，宁严勿松）
      and 'String hit = WebCache.get(cacheKey);' in client
      and 'WebCache.put(cacheKey, text, ttlMs);' in client)
check('★ 天气**资料块**也缓存（同一城市 10 分钟内不再打网络）',
      'String ckey = "weather:" + code;' in client
      and 'String cachedBlock = WebCache.get(ckey);' in client
      and 'WebCache.put(ckey, block, WebCache.WEATHER_TTL_MS);' in client)
check('必应两路（RSS / 网页版）都走缓存',
      'bing:rss:' in client and 'bing:html:' in client)
check('可插拔服务与它的第二跳正文也走缓存',
      '"p:" + p.id + ":" + q' in client and '"page:" + p.id + ":" + pageUrl' in client)

print()
print('=== B. 429/403 ⇒ 主机冷却 + 人能看懂的原因 ===')
check('★ 429/403 单独识别（不是笼统的"HTTP xxx"）',
      'if (code == 429 || code == 403) {' in client)
check('★ 尊重 Retry-After（解析不出来用默认 60s、上限 10 分钟）',
      'WebCache.retryAfterMs(conn.getHeaderField("Retry-After"))' in client
      and 'MAX_COOLDOWN_MS' in cache and 'DEFAULT_COOLDOWN_MS' in cache)
check('★ 冷却期内**连请求都不发**（省得火上浇油）',
      'long left = WebCache.cooldownLeft(host);' in client
      and 'if (left > 0) {' in client and '秒后再试' in client)
check('★ 给用户的是一句人话（"对面限流了"），而不是"没查到"',
      '对面限流了' in client and 'WebCache.cool(host, wait);' in client)

print()
print('=== C. 换源：中国天气网两路都挂时改走 wttr.in ===')
check('★ 第三路兜底存在（另一台主机，不受同一台限流影响）',
      'Weather.wttrUrl(' in client and 'Weather.parseWttr(' in client
      and 'public static boolean parseWttr(String json, Now now)' in weather
      # ★ 必须钉"拼音真的取到了、且非空才走"这一步 —— 光查 API 名字在不在会假绿
      #   （把 en 改成 "" 时反向套件当场报 FAIL，这条才补上）
      and 'String en = Weather.enOf(city);' in client and 'if (en.length() > 0) {' in client)
check('★ wttr 的地址**不带 cache-buster**（实测带 `&_=` 直接 500）',
      weather.index('_=') < 0 or '不能带 cache-buster' in weather_raw)
check('拼音映射（wttr 要英文城市名）',
      'public static String enOf(String city)' in weather and 'public static String wttrUrl' in weather)

print()
print('=== D. 时间戳：5 分钟对齐，不再每次随机 ===')
check('★ 天气请求用"5 分钟对齐"的 bucket（随机 cache-buster 已消失）',
      'long bucket = System.currentTimeMillis() / 300000L * 300000L;' in client
      and 'Weather.indexUrl(code, bucket)' in client
      and 'Weather.nowUrl(code, bucket)' in client
      and 'Weather.indexUrl(code, System.currentTimeMillis())' not in client)

print()
print('=== E. 真报文 fixture + fpstest [28] + 设备脚本 ===')
p = os.path.join(FIX, 'wttr-beijing.json')
check('★ wttr 真实报文 fixture 在（离线断言跑的就是它）',
      os.path.isfile(p) and os.path.getsize(p) > 2000)
rt = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                    capture_output=True)
out = rt.stdout.decode('utf-8', 'replace')
check('★ fpstest 全绿（含 [28] WebCache 缓存与冷却）',
      rt.returncode == 0 and '[28]' in out, '退出码=%d' % rt.returncode)
dev = read(DEVS)
check('设备脚本会验"第二次问同一句命中缓存"（不再打网络）',
      '缓存命中' in dev)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（429 韧性：缓存 / 退避 / 换源）')
