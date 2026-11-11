# -*- coding: utf-8 -*-
"""设备验证：**联网检索**在模拟器上真的能用（待办 0d 路线 C）。

能验什么 / 不能验什么（**说清楚，别假装**）：
  ✅ 能验：开关打开后，从"问一句"这条真实入口发出去的问题**真的联网搜到了**
     （日志 DSWeb：条数 / 源 / 是否抓到正文 / 拼装后多少字）；
     开关关着**一条网络请求都不发**（零开销）；寒暄闲聊**不搜**（省流量）。
  ❌ 不能验：模型拿着资料**答得对不对** —— 那需要用户的真 API Key（模拟器上只有假 Key，
     真请求会被服务端拒），**留给用户真机**。这里只证明"资料确实进了这次请求"（拼装字数）。

用法：
    python build/verify_web_ui.py --install     # 装 dist 里的开发版再跑
    python build/verify_web_ui.py               # 设备上已有包，直接跑
    python build/verify_web_ui.py --selftest    # 不连设备，验判读逻辑
"""
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_char_ui as V            # noqa: E402  设备驱动与判读工具
import pf                             # noqa: E402  改 pref（带 owner/mode 还原）

DEV = V.DEV
PKG = V.PKG
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

def parse_web_logs(text):
    """从 logcat 里摘出 DSWeb 的检索实况。

    返回 list of dict：kind=ok/fail/none, query, hits, source, body, built, raw_len
    """
    out = []
    for ln in (text or '').splitlines():
        if 'DSWeb' not in ln:
            continue
        m = re.search(r'augment 成功 q=(.*?) 条数=(\d+) 源=(\S+) 正文=(\S+) 拼装=(\d+)字\(原话(\d+)字\)', ln)
        if m:
            out.append({'kind': 'ok', 'query': m.group(1), 'hits': int(m.group(2)),
                        'source': m.group(3), 'body': m.group(4),
                        'built': int(m.group(5)), 'raw_len': int(m.group(6))})
            continue
        m = re.search(r'augment 失败 q=(.*?) 原因=(.*)$', ln)
        if m:
            out.append({'kind': 'fail', 'query': m.group(1), 'reason': m.group(2).strip()})
            continue
        m = re.search(r'skip 不搜 q=(.*?) 原因=(.*)$', ln)
        if m:
            out.append({'kind': 'skip', 'query': m.group(1), 'reason': m.group(2).strip()})
    return out


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    real = ('10-08 12:00:00.000  1234  1300 I DSWeb   : augment 成功 q=今天有什么新闻 '
            '条数=5 源=rss 正文=true 拼装=2860字(原话7字)')
    r = parse_web_logs(real)
    t('成功行读得出条数/源/正文/拼装字数',
      len(r) == 1 and r[0]['kind'] == 'ok' and r[0]['hits'] == 5 and r[0]['source'] == 'rss'
      and r[0]['body'] == 'true' and r[0]['built'] == 2860 and r[0]['raw_len'] == 7)
    fail = '10-08 12:00:01.000  1234  1300 I DSWeb   : augment 失败 q=今天天气 原因=必应 RSS 打不开：timeout'
    r2 = parse_web_logs(fail)
    t('失败行读得出原因', len(r2) == 1 and r2[0]['kind'] == 'fail' and 'timeout' in r2[0]['reason'])
    t('反例：没有 DSWeb 行 ⇒ 空表（用来断言"根本没联网"）',
      parse_web_logs('I/DSWander: 无关日志') == [])
    t('反例：英文/别的 tag 不误匹配', parse_web_logs('I/Other: augment 成功 q=x 条数=1') == [])
    t('★ 只认模拟器（不许往用户真机改 pref/装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def root_ok():
    if 'root' not in V.out('shell', 'id'):
        V.out('root')
        time.sleep(3)
    return 'root' in V.out('shell', 'id')


def set_pref(key, val):
    """改一个 pref（force-stop → 改 XML → 还原 owner/mode —— 见 pf.py 的说明）。"""
    pf.stop()
    body = pf.load()
    if body is None:
        return False
    body = pf.setval(body, key, val, 'boolean')
    pf.save(body)
    body = pf.load()
    return pf.get(body, key) == val


def set_web(on):
    """改「联网检索」开关。"""
    return set_pref('web_search_on', 'true' if on else 'false')


def pet_pos():
    """桌宠窗口中心（悬浮窗不进 uiautomator dump，只能从 dumpsys 取）。"""
    out = V.out('shell', 'dumpsys', 'window', 'windows')
    lines = out.splitlines()
    for i, l in enumerate(lines):
        if 'Window{' not in l or PKG not in l:
            continue
        end = len(lines)
        for k in range(i + 1, len(lines)):
            if lines[k].lstrip().startswith('Window #'):
                end = k
                break
        blk = lines[i:end]
        if not any('APPLICATION_OVERLAY' in x for x in blk):
            continue
        if any('NOT_TOUCHABLE' in x for x in blk):
            continue
        px = py = w = h = None
        for x in blk:
            m = re.search(r'mAttrs=\{\((-?\d+),(-?\d+)\)', x)
            if m and px is None:
                px, py = int(m.group(1)), int(m.group(2))
            m2 = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', x)
            if m2 and w is None:
                w = int(m2.group(3)) - int(m2.group(1))
                h = int(m2.group(4)) - int(m2.group(2))
        if px is not None and w and h and h >= 250 and w >= 400:
            return (px + w // 2, py + h // 2)
    return None


def ime_shown():
    return 'mInputShown=true' in V.out('shell', 'dumpsys', 'input_method')


def ensure_tab_root():
    """确保停在**板块首页**（不是某个二级页）。

    ★ 实测坑：上一节进过「对话与人格」二级页，App 会**记住**那一页；
      下次启动直接落在那里，而那时点底部「陪伴」是空操作
      （`switchTab` 对"已在同一板块且不在根页"有守卫）⇒ 后面找「记忆与省 token」永远找不到。
      判据用二级页上的「返回」按钮（首页没有它）。
    """
    for _ in range(3):
        xml = V.dump()
        if '返回' not in xml:
            return True
        if not V.tap_text_exact('返回'):
            break
        time.sleep(1.5)
    return '返回' not in V.dump()


def open_chat():
    """打开「完整对话窗口」：陪伴 → 记忆与省 token → 查看对话历史 → 继续对话。

    ★ 为什么不用桌宠的**悬浮窗输入条**：那条路要"双击桌宠 → 等软键盘弹起 → 打字"，
      而输入条是 {@code TYPE_APPLICATION_OVERLAY} —— **不在 uiautomator dump 里**，
      文字有没有打进去、有没有提交，脚本看不见，只能靠"日志有没有出现"反推。
      实测就是这么翻车的：十次里总有几次键盘没弹起来，`input text` 打空、回车交了空串，
      表现成"寒暄不搜这条功能坏了"（其实只是没打进去）。
      聊天窗口是**普通 Activity**：输入框与发送按钮都能 dump 到，打进去的字还能**复读**再发。
    ★ 路径记清楚：「查看对话历史」在**记忆与省 token** 那一页（不在对话与人格页，
      两页都试过才发现 —— 原来照着"历史"两个字猜，白跑一轮）。
    """
    if V.wait_text('和小深聊天', 2) is not None or V.wait_text('说点什么', 2) is not None:
        return True                       # 已经在聊天页
    V.out('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    ensure_tab_root()
    if V.wait_text('陪伴', 8):
        V.tap_text_exact('陪伴')
        time.sleep(2)
    if V.wait_text('记忆与省 token', 8):
        V.tap_row('记忆与省 token')
        time.sleep(2)
    if not V.wait_text('查看对话历史', 8):
        return False
    V.tap_row('查看对话历史')
    time.sleep(2)
    if not V.wait_text('继续对话', 8):
        return False
    V.tap_row('继续对话')
    time.sleep(2)
    return V.wait_text('说点什么', 8) is not None


def focus_input():
    """点聊天输入框，并确认它**真的聚焦了**（输入法弹起来）。

    ★ 不能用 `V.tap_row('说点什么')`：它挑"该 y 上最小的**可点**容器"，
      而 EditText 在 uiautomator dump 里常常是 `clickable=false`
      ⇒ 点到的是**外层输入条**，EditText 根本没聚焦，`input text` 打给空气 ——
      表现成"字没进去"，再被当成"联网不搜"（本轮又踩一次，记在这儿）。
      判据必须落到"输入法真的弹起来了"（`mInputShown=true`）。
    """
    x = V.dump()
    m = re.search(r'text="说点什么[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if not m:
        return False
    cx = (int(m.group(1)) + int(m.group(3))) // 2
    cy = (int(m.group(2)) + int(m.group(4))) // 2
    V.tap(cx, cy)
    for _ in range(12):
        if ime_shown():
            return True
        time.sleep(0.4)
    return False


def chat_send(question):
    """在对话窗口里发一条消息；返回是否**确认发出**（发之前复读过输入框内容）。

    ★ question 必须 ASCII：`adb shell input text` 不支持中文。
      中文分支由 fpstest 的纯断言覆盖（WebPrompt.shouldSearch 的中文关键词）。
    """
    if V.wait_text('说点什么', 3) is None:
        if not open_chat():
            return False
    if not focus_input():
        return False
    V.out('shell', 'input', 'text', question.replace(' ', '%s'))
    time.sleep(0.8)
    # ★ 复读：dump 里必须看得到这段文字，否则就是没打进去 —— 那就重来，
    #   绝不能把"没打进去"当成"功能没生效"（这正是悬浮窗那条路的老毛病）。
    if question not in V.dump():
        return False
    # 发送按钮：软键盘弹起来时输入条会被顶上去，所以**每次都重新 dump 定位**
    # （不能缓存坐标 —— 实测键盘弹出前后差了好几百像素）。
    if not V.tap_text_exact('发送'):
        return False
    time.sleep(1.0)
    return True


def ask_and_collect(question, tries=3, wait=10):
    """发一个问题并收集 DSWeb 日志；没打进/没走到联网判定就重来。"""
    logs = ''
    sent = False
    for i in range(tries):
        V.out('logcat', '-c')
        sent = chat_send(question)
        time.sleep(wait)
        logs = V.out('logcat', '-d', '-s', 'DSWeb:I')
        recs = parse_web_logs(logs)
        if recs:
            return recs, logs, sent, i + 1
    return [], logs, sent, tries


def sh_home():
    V.out('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    V.out('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(2.5)


def cold_start():
    V.out('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    V.out('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    V.out('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(6)
    xml = V.dump()
    if '保存并开始' in xml:
        V.tap_text_exact('sk-xxxxxxxx')
        V.out('shell', 'input', 'text', 'sk-web-probe')
        time.sleep(1)
        V.out('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1)
    sh_home()


def dex_md5(local_apk):
    import hashlib
    import zipfile
    try:
        with zipfile.ZipFile(local_apk) as z:
            return hashlib.md5(z.read('classes.dex')).hexdigest()
    except Exception:
        return None


def installed_dex(tmp=r'D:\petport_lab\petbuild\build\_uitest\_installed.apk'):
    """设备上**当前装着**的那个包的 dex md5（需要 root 才能读 /data/app）。"""
    path = V.out('shell', 'pm', 'path', PKG).strip().replace('package:', '')
    if not path:
        return None
    os.makedirs(os.path.dirname(tmp), exist_ok=True)
    V.out('shell', 'cp', path, '/sdcard/_inst.apk')
    V.out('pull', '/sdcard/_inst.apk', tmp)
    return dex_md5(tmp)


def assert_current_build():
    """★ 先确认"设备上装的就是 dist 里那个包"，再**提醒**"dist 是不是比源码旧"。

    本工程反复吃过"测试跑的是旧 APK"的亏（改了代码忘了重编 / 忘了重装）。
    联网检索这一轮又踩了一次：加了 "skip 不搜" 日志后没重编，
    于是"寒暄不搜"这条断言一直红 —— 看着像功能坏了，其实是包旧了。
    ⇒ 两道判据：① dist 与设备 dex **必须相同**（不同就 --install）；
      ② 源码若比 dist 里的包**新**，打一条醒目告警（不强判失败：
         反向套件会故意改源码再写回，mtime 必然变新，那是正常的）。
    """
    want = dex_md5(V.DEV_APK)
    got = installed_dex()
    check('★ 设备上装的就是 dist 里的开发版（不然测的是旧包）',
          want is not None and got is not None and want == got,
          'dist=%s 设备=%s（差一个就加 --install 重跑）' % (want, got))
    try:
        import glob
        apk_t = os.path.getmtime(V.DEV_APK)
        newer = [os.path.basename(p) for p in glob.glob(os.path.join(
            r'D:\petport_lab\petbuild\src\com\deepseek\pet', '*.java')) if os.path.getmtime(p) > apk_t]
        if newer:
            print('  [警告] 有 %d 个源码文件比 dist 里的 APK 新 ⇒ **可能忘了重编**：%s'
                  % (len(newer), ', '.join(newer[:6])))
            print('         （反向套件会碰源码 mtime，属正常；但要是刚改过产品代码，先重编再跑）')
    except Exception:
        pass
    return want == got


def main():
    if '--selftest' in sys.argv:
        return selftest()
    if '--install' in sys.argv:
        print(V.out('install', '-r', '-d', V.DEV_APK).strip())
    if not root_ok():
        print('!! 需要 adb root 才能改「联网检索」开关 —— 拒绝在无 root 时假装通过')
        return 1
    if not assert_current_build():
        return 1

    # ★ 漫游必须关掉：桌宠一边走一边被双击，常常点在它刚才的位置上
    #   （第一次跑就是这么丢的 —— "hello" 三次都没走到联网判定，而同一个流程
    #    "what is deepseek" 一次就成功，看着像功能时好时坏）。
    body = pf.load()
    old_wander = pf.get(body, 'wander_on') if body else None
    set_pref('wander_on', 'false')

    try:
        return run_checks(old_wander)
    finally:
        set_pref('web_search_on', 'false')
        if old_wander is not None:
            set_pref('wander_on', old_wander)


def run_checks(old_wander):
    print()
    print('== 一、设置页有联网检索开关，且默认是关的 ==')
    if not set_web(False):
        check('复位开关', False, '写不进 pref')
        return 1
    cold_start()
    V.out('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(4)
    # 设置页是"底部三板块 + 二级页"：联网检索在「陪伴 → 对话与人格」里
    # （TAB_SECTIONS[1] 含第 1 节）。所以要先导航，不能直接在新首页找那一行。
    if V.wait_text('陪伴', 8):
        V.tap_text_exact('陪伴')
        time.sleep(2)
    if V.wait_text('对话与人格', 8):
        V.tap_row('对话与人格')
        time.sleep(2)
    xml = V.dump()
    has_row = '联网检索' in xml
    check('★ 设置页能看到「联网检索」这一行（陪伴 → 对话与人格）', has_row,
          '没有就去看看 Prefs.webSearchLabel / addToggle 有没有接上')
    V.out('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)

    print()
    print('== 二、开关**关着**：一条网络请求都不发（零开销）==')
    cold_start()
    recs, logs, sent, tries = ask_and_collect('what is deepseek', tries=2)
    check('★ 关着时没有任何检索日志（连"要不要搜"都不进入）',
          sent and not any(r['kind'] in ('ok', 'fail') for r in recs),
          '发出=%s 走了 %d 次；实际: %s' % (sent, tries, logs.strip()[-160:] or '(空)'))

    print()
    print('== 三、开关**开着** + 寒暄：不搜（省流量）==')
    if not set_web(True):
        check('打开联网检索开关', False, 'pref 写不进去')
        return 1
    cold_start()
    recs, logs, sent, tries = ask_and_collect('hello', tries=3)
    check('★ 寒暄闲聊**走到了联网判定**、但决定不搜（省流量）',
          sent and any(r['kind'] == 'skip' for r in recs)
          and not any(r['kind'] in ('ok', 'fail') for r in recs),
          '发出=%s 走了 %d 次；实际: %s' % (sent, tries, logs.strip()[-160:] or '(没有任何 DSWeb 日志)'))

    print()
    print('== 四、开关**开着** + 问最新的事：真的联网搜到并拼进请求 ==')
    recs, logs, sent, tries = ask_and_collect('what is deepseek', tries=3, wait=14)
    oks = [r for r in recs if r['kind'] == 'ok']
    if not oks:
        check('★ 联网检索成功（条数 / 源 / 正文）', False,
              '走了 %d 次；实际: %s' % (tries, logs.strip()[-300:] or '(没有任何 DSWeb 日志)'))
    else:
        r0 = oks[0]
        check('★ 联网检索成功（条数 / 源 / 正文）', r0['hits'] >= 1 and r0['source'] in ('rss', 'html'),
              '走了 %d 次；条数=%d 源=%s 正文=%s' % (tries, r0['hits'], r0['source'], r0['body']))
        check('★ 资料真的拼进了这次请求（拼装后远大于原话）',
              r0['built'] > r0['raw_len'] + 200,
              '拼装=%d 字 vs 原话=%d 字' % (r0['built'], r0['raw_len']))

    print()
    print('== 五、把开关复位（不把测试状态留给下一次）==')
    set_web(False)
    body = pf.load()
    check('开关已复位为关', body is not None and pf.get(body, 'web_search_on') == 'false')

    print()
    print('=' * 62)
    print('★ 说明：模型**答得对不对**验不了 —— 那需要用户的真 API Key（模拟器上只有假 Key，')
    print('   真请求会被服务端拒）。本脚本证明的是：搜索/抓正文/拼装这条链路在设备上真的通了。')
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
