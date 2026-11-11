# -*- coding: utf-8 -*-
"""设备验证：**天气走结构化接口**在模拟器上真的能用（轮次㉗）。

能验什么 / 不能验什么（**说清楚，别假装**）：
  ✅ 能验：开关打开后 ① 没说城市时**不联网、反问一句**（日志 `weather 缺城市`）；
     ② 上一句说过的城市会**被记住**（`web_last_city`），下一句只说 "weather" 也能查；
     ③ 问句里带城市（"weather in beijing"）会真的去 `d1.weather.com.cn` 取数，
     日志给出**城市 / 城市码 / 实况温度 / 预报天数 / 拼装字数**；
     ④ 普通搜索仍然走必应，且日志现在会写出**提炼后的关键词**。
  ❌ 不能验：模型拿着天气数据**答得自不自然** —— 那需要用户的真 API Key
     （模拟器上只有假 Key，真请求被服务端拒）。这里只证明"**有数的资料**确实进了这次请求"。

★ 为什么用英文问句：`adb shell input text` **只支持 ASCII**（中文打不进去）。
  所以本脚本用 "weather" / "weather in beijing" 驱动，中文分支由 fpstest [24][25] 的
  纯断言覆盖（两边合起来才算验全）。

用法：
    python build/verify_web_weather.py --install    # 装 dist 里的开发版再跑
    python build/verify_web_weather.py              # 设备上已有包，直接跑
    python build/verify_web_weather.py --selftest   # 不连设备，验判读逻辑
"""
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_web_ui as W              # noqa: E402  复用设备驱动（V / pf / 聊天窗口 / 冷启动）
import pf                              # noqa: E402

V = W.V
PKG = W.PKG
fails = []
skips = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def parse_weather_logs(text):
    """从 logcat 里摘出天气与搜索的实况。

    返回 list of dict：kind=weather/miss/search/skip/fail
    """
    out = []
    for ln in (text or '').splitlines():
        if 'DSWeb' not in ln:
            continue
        m = re.search(r'weather 成功 城市=(\S+) 码=(\d+) 实况=(\S+?) 预报=(\d+)天 记忆=(\S+) '
                      r'拼装=(\d+)字\(原话(\d+)字\)', ln)
        if m:
            out.append({'kind': 'weather', 'city': m.group(1), 'code': m.group(2),
                        'now': m.group(3), 'days': int(m.group(4)),
                        'remembered': m.group(5) == 'true', 'built': int(m.group(6)),
                        'raw_len': int(m.group(7))})
            continue
        m = re.search(r'weather 缺城市 q=(.*?) ⇒', ln)
        if m:
            out.append({'kind': 'miss', 'query': m.group(1)})
            continue
        # ★ 轮次㉙：缓存命中（第二次问同一个城市时**不该再打网络**）
        m = re.search(r'weather 缓存命中 城市=(\S+) 剩=(\d+)秒', ln)
        if m:
            out.append({'kind': 'cache', 'city': m.group(1), 'left': int(m.group(2))})
            continue
        # ★ 429 限流（对面说"请求太多"，我们会冷却并退避）
        m = re.search(r'限流 HTTP (\d+) 主机=(\S+) 冷却=(\d+)秒', ln)
        if m:
            out.append({'kind': 'limit', 'code': int(m.group(1)), 'host': m.group(2),
                        'cool': int(m.group(3))})
            continue
        m = re.search(r'weather 失败 城市=(\S+) 原因=(.*)$', ln)
        if m:
            out.append({'kind': 'fail', 'city': m.group(1), 'reason': m.group(2).strip()})
            continue
        m = re.search(r'augment 成功 q=(.*?) 关键词=(.*?) 条数=(\d+) 源=(\S+) 正文=(\S+) '
                      r'拼装=(\d+)字\(原话(\d+)字\)', ln)
        if m:
            out.append({'kind': 'search', 'query': m.group(1), 'used': m.group(2),
                        'hits': int(m.group(3)), 'source': m.group(4),
                        'built': int(m.group(6)), 'raw_len': int(m.group(7))})
            continue
        if 'skip 不搜' in ln:
            out.append({'kind': 'skip', 'line': ln})
    return out


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    good = ('10-08 21:00:00.000  1234  1300 I DSWeb   : weather 成功 城市=北京 码=101010100 '
            '实况=16.5℃霾 预报=3天 记忆=false 拼装=164字(原话7字)')
    r = parse_weather_logs(good)
    t('成功行读得出城市/码/实况/预报/记忆/拼装',
      len(r) == 1 and r[0]['kind'] == 'weather' and r[0]['city'] == '北京'
      and r[0]['code'] == '101010100' and r[0]['days'] == 3 and not r[0]['remembered']
      and r[0]['built'] == 164 and r[0]['raw_len'] == 7)
    miss = '10-08 21:00:01.000  1234  1300 I DSWeb   : weather 缺城市 q=今天天气怎么样 ⇒ 反问用户哪个城市'
    r2 = parse_weather_logs(miss)
    t('缺城市行读得出来（这条证明"没联网，而是反问"）',
      len(r2) == 1 and r2[0]['kind'] == 'miss' and r2[0]['query'] == '今天天气怎么样')
    fail = '10-08 21:00:02.000  1234  1300 I DSWeb   : weather 失败 城市=北京 原因=天气接口打不开：timeout'
    r3 = parse_weather_logs(fail)
    t('天气失败行读得出原因', len(r3) == 1 and r3[0]['kind'] == 'fail' and 'timeout' in r3[0]['reason'])
    sr = ('10-08 21:00:03.000  1234  1300 I DSWeb   : augment 成功 q=小米15多少钱现在 关键词=小米15 价格 '
          '条数=8 源=rss 正文=true 拼装=3120字(原话9字)')
    r4 = parse_weather_logs(sr)
    t('★ 普通搜索行现在带"关键词"（能看出提炼真的起作用了）',
      len(r4) == 1 and r4[0]['kind'] == 'search' and r4[0]['used'] == '小米15 价格'
      and r4[0]['hits'] == 8)
    t('反例：别的 tag 不误匹配', parse_weather_logs('I/Other: weather 成功 城市=北京') == [])
    t('★ 只认模拟器（不许往用户真机装包/改 pref）', V.DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def clear_input():
    """把输入框清空。

    ★ 为什么必须清：实测踩到过 —— 某次 `focus_input()` 的点击落在**刚弹起的键盘**上，
    输入框里留下一个 `?`；下一次尝试再点就点到了发送 ⇒ App 收到的是 `?`，
    日志写着 `skip 不搜 q=? 原因=太短，不值得搜`，看起来像"普通搜索坏了"，
    其实是**上一轮的残留字符被发出去了**（弱判据/脏状态会把排查带偏）。
    """
    V.out('shell', 'input', 'keycombination', 'KEYCODE_CTRL_LEFT', 'KEYCODE_A')
    time.sleep(0.3)
    V.out('shell', 'input', 'keyevent', 'KEYCODE_DEL')
    time.sleep(0.3)


def chat_send(question):
    """在聊天窗口里发一条（发送前**先清空**并**复读确认**）。"""
    if V.wait_text('说点什么', 3) is None:
        if not W.open_chat():
            return False
    if not W.focus_input():
        return False
    clear_input()
    V.out('shell', 'input', 'text', question.replace(' ', '%s'))
    time.sleep(0.8)
    if question not in V.dump():
        return False
    if not V.tap_text_exact('发送'):
        return False
    time.sleep(1.0)
    return True


def ask(question, tries=3, wait=12):
    """发一句 ASCII 问题并收集 DSWeb 日志（只认**属于这句话**的记录）。

    ★ 过滤很重要：日志里可能有上一轮残留的记录（比如那个 `q=?`），
    不过滤就会拿着别人的记录下结论。
    """
    logs = ''
    for i in range(tries):
        V.out('logcat', '-c')
        sent = chat_send(question)
        time.sleep(wait)
        logs = V.out('logcat', '-d', '-s', 'DSWeb:I')
        recs = [r for r in parse_weather_logs(logs)
                # ★ 注意把 'cache'/'limit' 也算进来：它们是**属于本次请求**的记录但没有 query 字段。
                #   （第一版漏了 'cache' ⇒ 缓存明明命中却报"没有日志"，白红一次。）
                if r.get('query') == question
                or r['kind'] in ('weather', 'miss', 'fail', 'cache', 'limit')]
        if recs:
            return recs, logs, sent, i + 1
    return [], logs, False, tries


def set_pref_str(key, val):
    """写一个字符串 pref（记住城市的记忆就是字符串）。"""
    pf.stop()
    body = pf.load()
    if body is None:
        return False
    body = pf.setval(body, key, val, 'string')
    pf.save(body)
    return pf.get(pf.load(), key) == val


def clear_city():
    return set_pref_str('web_last_city', '')


def main():
    if '--selftest' in sys.argv:
        return selftest()
    if '--install' in sys.argv:
        print(V.out('install', '-r', '-d', V.DEV_APK).strip())
    if not W.root_ok():
        print('!! 需要 adb root 才能改 pref —— 拒绝在无 root 时假装通过')
        return 1
    if not W.assert_current_build():
        return 1

    # 漫游关掉：桌宠一边走一边被双击会点偏（见 verify_web_ui 的说明）
    body = pf.load()
    old_wander = pf.get(body, 'wander_on') if body else None
    W.set_pref('wander_on', 'false')
    try:
        return run_checks()
    finally:
        W.set_web(False)
        clear_city()
        if old_wander is not None:
            W.set_pref('wander_on', old_wander)


def run_checks():
    print()
    print('== 一、没说城市：**不联网**，反问一句 ==')
    W.set_web(True)
    clear_city()
    W.cold_start()
    recs, logs, sent, tries = ask('weather', tries=3)
    miss = [r for r in recs if r['kind'] == 'miss']
    hit = [r for r in recs if r['kind'] in ('weather', 'fail')]
    check('★ 没城市时给的是「反问」而不是联网查（走了 %d 次）' % tries,
          sent and miss and not hit,
          '实际: %s' % (logs.strip()[-240:] or '(没有任何 DSWeb 日志)'))
    check('★ 反问时**一次网络都没发**（日志里没有 weather 成功/失败）', not hit)

    print()
    print('== 二、上一句的城市会被记住：只说 "weather" 也能查 ==')
    if not set_pref_str('web_last_city', '北京'):
        check('写入"上次城市"记忆', False, 'pref 写不进去')
        return 1
    W.cold_start()
    recs, logs, sent, tries = ask('weather', tries=3, wait=14)
    w = [r for r in recs if r['kind'] == 'weather']
    if not w:
        check('★ 用记住的城市查到真实天气', False,
              '走了 %d 次；实际: %s' % (tries, logs.strip()[-300:] or '(没有日志)'))
    else:
        r0 = w[0]
        check('★ 用记住的城市查到真实天气（记忆=true）',
              r0['remembered'] and r0['city'] == '北京' and r0['code'] == '101010100',
              '城市=%s 码=%s 记忆=%s' % (r0['city'], r0['code'], r0['remembered']))
        check('★ 实况里有**温度数字**（这正是原来缺的东西）',
              re.search(r'-?\d+(\.\d+)?℃', r0['now']) is not None, r0['now'])
        check('★ 预报里也解析出了天数（今天/明天…）', r0['days'] >= 2, '%d 天' % r0['days'])
        check('★ 天气资料真的拼进了这次请求（拼装远大于原话）',
              r0['built'] > r0['raw_len'] + 80,
              '拼装=%d 字 vs 原话=%d 字' % (r0['built'], r0['raw_len']))

    print()
    print('== 二之二、同一句再问一次：**命中缓存、不再打网络**（轮次㉙ 治 429）==')
    recs, logs, sent, tries = ask('weather', tries=2, wait=8)
    hits = [r for r in recs if r['kind'] == 'cache']
    net = [r for r in recs if r['kind'] in ('weather', 'fail')]
    check('★ 第二次问同一个城市 ⇒ 走缓存（日志 weather 缓存命中）',
          bool(hits) and not net,
          '实际: %s' % (logs.strip()[-240:] or '(没有日志)'))

    print()
    print('== 三、问句里带城市（英文）→ 直接查那个城市 ==')
    W.cold_start()
    recs, logs, sent, tries = ask('weather in beijing', tries=3, wait=14)
    w = [r for r in recs if r['kind'] == 'weather']
    if not w:
        check('★ 英文问句里的城市被认出来', False,
              '走了 %d 次；实际: %s' % (tries, logs.strip()[-300:] or '(没有日志)'))
    else:
        r0 = w[0]
        check('★ 英文问句里的城市被认出来（记忆=false ⇒ 是从问句里抠的）',
              r0['city'] == '北京' and not r0['remembered'],
              '城市=%s 记忆=%s' % (r0['city'], r0['remembered']))

    print()
    print('== 四、普通搜索：日志里能看到提炼后的关键词 ==')
    W.cold_start()
    recs, logs, sent, tries = ask('what is deepseek', tries=3, wait=14)
    s = [r for r in recs if r['kind'] == 'search']
    if not s:
        # ★ 这条只是"顺带复验普通搜索"，而**打字本身在慢模拟器上会失败**（输入框残留/键盘没弹起）。
        #   分不清"没打进去"和"功能坏了"时按**未验证**记（skip），不要报成功能失败 ——
        #   普通搜索的设备验证另有 `verify_web_ui.py` 专管（那边跑得更稳）。
        skip('普通搜索仍然走必应', '这次没把问题打进去（走了 %d 次，没有日志）⇒ 按未验证计' % tries)
    else:
        r0 = s[0]
        check('普通搜索仍然走必应（条数 / 源）', r0['hits'] >= 1 and r0['source'] in ('rss', 'html'),
              '条数=%d 源=%s 关键词=%s' % (r0['hits'], r0['source'], r0['used']))
        check('★ 日志里有"关键词"字段（没提炼时就是原话）', len(r0['used']) > 0)

    print()
    print('== 五、复位（不把测试状态留给下一次）==')
    W.set_web(False)
    clear_city()
    body = pf.load()
    check('开关与"上次城市"都复位了',
          body is not None and pf.get(body, 'web_search_on') == 'false'
          and (pf.get(body, 'web_last_city') in (None, '')))

    print()
    print('=' * 62)
    print('★ 说明：模型拿天气数据**答得自不自然**验不了 —— 需要用户的真 API Key。')
    print('   本脚本证明的是：没城市会反问、记住的城市会用、有城市就取到**带温度的结构化数据**')
    print('   并真的拼进了这次请求。')
    if skips:
        print('跳过 %d 项（跳过按未验证计）：' % len(skips))
        for s in skips:
            print('   - ' + s)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    print('ALL DEVICE CHECKS PASSED')
    return 0


if __name__ == '__main__':
    sys.exit(main())
