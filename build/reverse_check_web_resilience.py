# -*- coding: utf-8 -*-
"""反向验证：轮次㉙「429 韧性（缓存/退避/换源）」的断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 那条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 盯的是"请求又被打多"的所有入口：缓存不查、缓存不写、天气资料块不缓存、
  429 不冷却、冷却不检查、不给用户人话、wttr 兜底没了、wttr 地址又带上 cache-buster、
  时间戳又变随机。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
CLIENT = os.path.join(SRC, 'WebClient.java')
WEATHER = os.path.join(SRC, 'Weather.java')
VERIFY = os.path.join(HERE, 'verify_web_resilience.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def break_and_check(label, path, old, new):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-52s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-52s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：429 韧性（缓存 / 退避 / 换源）===')
print()

# ① 取数不再查缓存（等于回到"每问一次打一次"）
results.append(break_and_check(
    'A. fetchCached 不再查缓存',
    CLIENT,
    '        String hit = WebCache.get(cacheKey);',
    '        String hit = null;'))

# ② 取数不再写缓存（请求照样每次都发）
results.append(break_and_check(
    'B. fetchCached 不再写缓存',
    CLIENT,
    '        WebCache.put(cacheKey, text, ttlMs);',
    '        // WebCache.put(cacheKey, text, ttlMs);'))

# ③ 天气资料块不再缓存（连着问同一城市还是每次都打）
results.append(break_and_check(
    'C. 天气资料块不再缓存',
    CLIENT,
    '        String cachedBlock = WebCache.get(ckey);',
    '        String cachedBlock = null;'))

# ④ 天气成功后不再落缓存
results.append(break_and_check(
    'D. 天气成功后不再落缓存',
    CLIENT,
    '        WebCache.put(ckey, block, WebCache.WEATHER_TTL_MS);',
    '        // WebCache.put(ckey, block, WebCache.WEATHER_TTL_MS);'))

# ⑤ 429 不再记冷却（继续火上浇油）
results.append(break_and_check(
    'E. 429/403 不再记冷却',
    CLIENT,
    '                    WebCache.cool(host, wait);',
    '                    // WebCache.cool(host, wait);'))

# ⑥ 冷却期照样发请求
results.append(break_and_check(
    'F. 冷却期内照样发请求',
    CLIENT,
    '        if (left > 0) {',
    '        if (false) {'))

# ⑦ 429 不再给用户人话（退回"没查到"）
results.append(break_and_check(
    'G. 限流时不再告诉用户"对面限流了"',
    CLIENT,
    'throw new Exception("HTTP " + code + "（对面限流了，"',
    'throw new Exception("HTTP " + code + "（" + "" + "，"'))

# ⑧ wttr 兜底没了（中国天气网一限流，天气就整个挂）
results.append(break_and_check(
    'H. 去掉 wttr 兜底（只剩同一台主机的两路）',
    CLIENT,
    '            String en = Weather.enOf(city);',
    '            String en = "";'))

# ⑨ wttr 地址又带上 cache-buster（实测对面会 500）
results.append(break_and_check(
    'I. wttr 地址又带上 cache-buster（对面 500）',
    WEATHER,
    'return "https://wttr.in/" + encPath(cityEn) + "?format=j1";',
    'return "https://wttr.in/" + encPath(cityEn) + "?format=j1&_=" + ignoredMs;'))

# ⑩ 时间戳又变随机（把对面 CDN 缓存全废掉）
results.append(break_and_check(
    'J. 天气时间戳又变随机（不再是 5 分钟对齐）',
    CLIENT,
    '        long bucket = System.currentTimeMillis() / 300000L * 300000L;',
    '        long bucket = System.currentTimeMillis();'))

print()
print('=' * 62)
done = [r for r in results if r is not None]
skipped = len(results) - len(done)
caught = sum(1 for r in done if r)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (caught, len(done)))
if skipped:
    print('  跳过 %d 项（锚点未命中）' % skipped)
if caught != len(done) or skipped:
    sys.exit(1)
print('ALL REVERSE CHECKS PASSED')
