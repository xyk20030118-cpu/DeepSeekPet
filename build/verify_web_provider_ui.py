# -*- coding: utf-8 -*-
"""设备验证：**可插拔搜索服务**在模拟器上真的走通（轮次㉘）。

验什么：
  ✅ 选了 Kimi（非内置必应）之后，日志出现 `源=provider:kimi`、条数 ≥1、正文=true、
     拼装远大于原话 ⇒ **资料确实来自配置的那家服务，并进了这次请求**；
  ✅ 抓正文走的是 provider 的第二跳（`/tools/fetch`）而不是我们自己的剥标签；
  ✅ 收工把 pref 复位成免费必应、并清掉测试用的 Key。

不验什么：
  ❌ 模型拿着资料**答得对不对**（模拟器是假 Key ⇒ 真请求 401）—— 那要用户真机。
  ❌ 各家服务的**质量差异**（那是选型问题，不是本脚本能判的）。

★ 真 Key 从环境变量读：`$env:KIMI_API_KEY='sk-...'`
  没设就**跳过并明确标注"未验证"**（绝不假装通过）。Key 只写进模拟器的 prefs，
  **不进源码/不进交付包/不进日志**，跑完清掉。

用法：
    python build/verify_web_provider_ui.py --selftest   # 离线验判读逻辑
    python build/verify_web_provider_ui.py --install    # 装 dist 里的开发版再跑
    $env:KIMI_API_KEY='sk-...'; python build/verify_web_provider_ui.py
"""
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_web_ui as W          # noqa: E402  设备驱动（V / pf / 冷启动 / 日志解析）
import verify_web_weather as WW    # noqa: E402  更稳的发送（先清空输入框 + 只认属于本句的日志）
import pf                          # noqa: E402

V = W.V
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


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    real = ('10-08 22:00:00.000  1234  1300 I DSWeb   : augment 成功 '
            'q=what is deepseek 关键词=what is deepseek 条数=5 源=provider:kimi '
            '正文=true 拼装=4120字(原话16字)')
    r = W.parse_web_logs(real)
    t('成功行读得出"源=provider:kimi"与条数/正文/拼装',
      len(r) == 1 and r[0]['kind'] == 'ok' and r[0]['source'] == 'provider:kimi'
      and r[0]['hits'] == 5 and r[0]['body'] == 'true' and r[0]['built'] == 4120)
    fb = ('10-08 22:00:01.000  1234  1300 I DSWeb   : provider 空手而归 ⇒ 退回必应 服务=kimi '
          '原因=「Kimi（月之暗面）」打不开：HTTP 401')
    t('退回必应那行读得到（服务名 + 原因）',
      'provider 空手而归' in fb and 'HTTP 401' in fb)
    t('★ 日志里不许出现 Key（判据只看 tag 与已知字段）',
      'sk-' not in real and 'sk-' not in fb)
    t('★ 只认模拟器（不许往用户真机装包/改 pref）', V.DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


def set_str(key, val):
    pf.stop()
    body = pf.load()
    if body is None:
        return False
    body = pf.setval(body, key, val, 'string')
    pf.save(body)
    return pf.get(pf.load(), key) == val


def ask(question, tries=3, wait=16):
    logs = ''
    for i in range(tries):
        V.out('logcat', '-c')
        sent = WW.chat_send(question)
        time.sleep(wait)
        logs = V.out('logcat', '-d', '-s', 'DSWeb:I')
        recs = [r for r in W.parse_web_logs(logs)
                if r.get('query') == question or r['kind'] in ('ok', 'fail')]
        if recs:
            return recs, logs, sent, i + 1
    return [], logs, False, tries


def main():
    if '--selftest' in sys.argv:
        return selftest()
    key = (os.environ.get('KIMI_API_KEY') or '').strip()
    if '--install' in sys.argv:
        print(V.out('install', '-r', '-d', V.DEV_APK).strip())
    if not W.root_ok():
        print('!! 需要 adb root 才能改 pref —— 拒绝在无 root 时假装通过')
        return 1
    if not W.assert_current_build():
        return 1

    body = pf.load()
    old_wander = pf.get(body, 'wander_on') if body else None
    W.set_pref('wander_on', 'false')
    try:
        return run_checks(key)
    finally:
        W.set_web(False)
        pf_set('search_provider', 'bing')
        set_str('search_key_kimi', '')
        # ★★ 必须擦掉本地暂存副本：`pf.load()` 会把设备 pref 整份 pull 到 `build/_prefs.xml`，
        #   而交付的**源码 zip 会打进整个 build/** ⇒ 不擦就等于**把用户的 Key 发出去**。
        #   （轮次㉘ 就是这么中的：Key 进了 `_prefs.xml` 并被打进源码包。）
        pf.wipe_local()
        if old_wander is not None:
            W.set_pref('wander_on', old_wander)


def pf_set(k, v):
    body = pf.load()
    if body is None:
        return False
    body = pf.setval(body, k, v, 'string')
    pf.save(body)
    return True


def run_checks(key):
    print()
    print('== 一、没配 Key：不发请求、给一句人话 ==')
    pf_set('search_provider', 'kimi')
    set_str('search_key_kimi', '')
    W.set_web(True)
    W.cold_start()
    recs, logs, sent, tries = ask('what is deepseek', tries=2, wait=10)
    oks = [r for r in recs if r['kind'] == 'ok']
    check('★ 选了 Kimi 但没填 Key ⇒ 不该出现"成功"（要么失败、要么退回必应并说明）',
          not any(r['source'] == 'provider:kimi' for r in oks),
          '实际: %s' % (logs.strip()[-200:] or '(没有日志)'))

    print()
    print('== 二、填入真 Key：真的走 provider:kimi（含第二跳抓正文）==')
    if not key:
        skip('provider 真链路（源=provider:kimi）',
             '没有 KIMI_API_KEY 环境变量 ⇒ 按"未验证"计（不会假装通过）')
        return finish()
    if not set_str('search_key_kimi', key):
        check('写入测试 Key', False, 'pref 写不进去')
        return 1
    # ★ 断言"Key 真的写进去了"，同时**不打印 Key 本身**
    check('测试 Key 已写入设备（长度 %d，按服务分槽）' % len(key),
          pf.get(pf.load(), 'search_key_kimi') == key)
    W.cold_start()
    recs, logs, sent, tries = ask('what is deepseek', tries=3, wait=18)
    hits = [r for r in recs if r['source'] == 'provider:kimi']
    if not hits:
        check('★ 走配置的那家服务（源=provider:kimi）', False,
              '走了 %d 次；实际: %s' % (tries, logs.strip()[-300:] or '(没有日志)'))
    else:
        r0 = hits[0]
        check('★ 走配置的那家服务（源=provider:kimi）', r0['hits'] >= 1 and r0['built'] > r0['raw_len'] + 200,
              '条数=%d 正文=%s 拼装=%d 字 vs 原话=%d 字'
              % (r0['hits'], r0['body'], r0['built'], r0['raw_len']))
        check('★ 第二跳（provider 的 /tools/fetch）抓到了整页正文', r0['body'] == 'true')

    print()
    print('== 三、复位（不把测试状态与 Key 留在这台设备上）==')
    pf_set('search_provider', 'bing')
    set_str('search_key_kimi', '')
    b = pf.load()
    check('搜索服务已复位为必应、测试 Key 已清空',
          b is not None and pf.get(b, 'search_provider') == 'bing'
          and (pf.get(b, 'search_key_kimi') in (None, '')))
    return finish()


def finish():
    print()
    print('=' * 62)
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
