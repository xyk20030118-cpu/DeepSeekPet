# -*- coding: utf-8 -*-
"""反向验证：轮次㉗「联网检索变聪明」的断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 那条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 盯的是这一轮最容易前功尽弃的几处：
  提炼没接上（发出去的还是原话）、时间词被误删、天气判断挪到 shouldSearch 之后、
  Referer 掉了、没城市时瞎猜一个城市、"上一句说过的城市"忘了、天气挂了不退回搜索、
  条数又缩回 5 条、超时又收紧、重试被摘掉。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
CLIENT = os.path.join(SRC, 'WebClient.java')
SEARCH = os.path.join(SRC, 'WebSearch.java')
QCLEAN = os.path.join(SRC, 'QueryClean.java')
WEATHER = os.path.join(SRC, 'Weather.java')
PREFS = os.path.join(SRC, 'Prefs.java')
VERIFY = os.path.join(HERE, 'verify_web_smart.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def break_and_check(label, path, old, new, count=1):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-52s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, count))
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
print('=== 反向验证：联网检索变聪明（查询提炼 / 天气结构化）===')
print()

# ① 提炼没接上（发出去还是原话 ⇒ "很笨"原地复活）
results.append(break_and_check(
    'A. 搜索不再用提炼后的关键词（退回原话）',
    CLIENT,
    '            hits = search(ctx, q, l);',
    '            hits = search(ctx, l.query, l);'))

# ② 时间词被误删（"今天/最新"删了 ⇒ 搜到的是旧资料）
results.append(break_and_check(
    'B. 删除表里混进时间词（把"今天"当口水话删掉）',
    QCLEAN,
    '            "查一下", "查一查", "查查", "查下", "搜一下", "搜一搜", "搜搜", "搜索",',
    '            "查一下", "查一查", "查查", "查下", "搜一下", "搜一搜", "搜搜", "搜索", "今天",'))

# ③ 提炼不出东西时发空查询（必应直接返回垃圾）
results.append(break_and_check(
    'C. 提炼太短时不再退回原话（发空查询）',
    QCLEAN,
    '        if (out.length() < MIN_CHARS) return raw;',
    '        if (false) return raw;'))

# ④ 天气判断挪到 shouldSearch 之后（"北京天气"被判"不必搜" ⇒ 又答不出）
results.append(break_and_check(
    'D. 天气判断挪到"该不该搜"之后',
    CLIENT,
    '        if (Weather.isAsk(l.query)) {',
    '        if (false) {'))

# ⑤ Referer 掉了（中国天气网会拦）★ 两处都要破坏，只改一处断言不该判绿
#    ★ 轮次㉙ 起这两处改走 `fetchCached(..., Weather.REFERER, null, "GET", null, ...)`，
#      锚点同步改成新写法（旧写法找不到 ⇒ 那一轮全量套件报"跳过 1 项"= 未验证）。
results.append(break_and_check(
    'E. 天气接口不再带 Referer',
    CLIENT,
    'MAX_INDEX_BYTES, Weather.REFERER, null, "GET", null,',
    'MAX_INDEX_BYTES, null, null, "GET", null,',
    count=2))

# ⑥ 没城市时瞎猜一个城市（用户问"今天天气怎么样"却收到北京的天气）
results.append(break_and_check(
    'F. 没城市时不再反问，改成随便挑一个城市',
    CLIENT,
    '            return Weather.askCityText(userMsg);',
    '            return Weather.block("北京", new Weather.Now(), new java.util.ArrayList<Weather.Day>());'))

# ⑦ 忘了上次的城市（第二句"今天天气怎么样"又变成反问）
results.append(break_and_check(
    'G. 不再记住上次问的城市',
    CLIENT,
    '                if (keep != null && keep.length() > 0) { city = keep; remembered = true; }',
    '                if (false) { city = keep; remembered = true; }'))

# ⑧ 天气接口挂了不退回搜索（用户只收到"没查到"，而其实搜一下就有）
results.append(break_and_check(
    'H. 天气接口失败后不再退回普通搜索',
    CLIENT,
    '            return null;        // 天气没走通 ⇒ 交回调用方',
    '            return Weather.failText(userMsg, reason);'))

# ⑧b ★ 诚实漏洞：天气挂了 + 这句没有"新鲜词"时，不许**悄悄返回原话**（模型会拿旧知识编天气）
results.append(break_and_check(
    'H2. 天气挂了又判"不必搜"时不再如实说没查到',
    CLIENT,
    '            if (weatherTried) {',
    '            if (false) {'))

# ⑨ 条数又缩回 5 条（白扔 RSS 的一半）
results.append(break_and_check(
    'I. 搜索条数缩回 5 条（扔掉 RSS 的一半）',
    SEARCH,
    '    public static final int MAX_HITS = 8;',
    '    public static final int MAX_HITS = 5;'))

# ⑩ 超时又收紧（慢网下"这次没联上网"复发）
results.append(break_and_check(
    'J. 连接超时又收紧到 6 秒',
    CLIENT,
    '    private static final int CONNECT_TIMEOUT_MS = 8000;',
    '    private static final int CONNECT_TIMEOUT_MS = 6000;'))

# ⑪ 重试被摘掉（慢网下"这次没联上网"复发）
results.append(break_and_check(
    'K. 瞬时失败不再重试',
    CLIENT,
    '        for (int i = 0; i <= RETRY; i++) {',
    '        for (int i = 0; i <= 0; i++) {'))

# ⑫ 天气资料改回"网页摘要"那套（模型又拿到导航栏）
results.append(break_and_check(
    'L. 天气不再用结构化资料块（改回普通搜索资料）',
    CLIENT,
    '        String built = WebPrompt.withWeather(userMsg, block);',
    '        String built = WebPrompt.searchFailed(userMsg, block);'))

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
