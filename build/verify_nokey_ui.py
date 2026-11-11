# -*- coding: utf-8 -*-
"""设备验证：**没有 API Key 时启动不再拦人、默认本地模式**（轮次㊴ · 用户 2026-10-11 要求）。

用户原话：「**把打开软件（没有输入 apikey 的情况下）弹出的 apikey 输入窗口取消掉，
不强制输入，默认本地模式**」。

为什么必须有设备验证：
    这是**启动路径**上的 UX。源码级断言只能证明"onCreate 里没人调 `showApiKeyDialog`"，
    证明不了"启动后设置页真的正常出现、pref 真的落成纯本地、桌宠照样在跑"。

覆盖：
  一、把**当前服务商**的 Key 清掉（先存原值）→ 重启 ⇒
     ① **不弹** Key 输入窗（判据：没有「输入 … 的 API Key」那一行）；
     ② 设置页**正常渲染**（能看到底部「陪伴」）；
     ③ pref 真的落成**纯本地**（`interact_cd_ms` = **-1**）；
     ④ 日志有「无 Key：已进本地模式并提示（不再弹强制输入窗）」——
        Toast **不在 uiautomator dump 里**，设备侧只能靠日志（工程既有手法）；
     ⑤ 桌宠照样在跑（本地模式只改"说话走哪条路"，不影响桌宠本身）。
  二、★ 换形象（`ACTION_PICK_IMAGE`，悬浮菜单那条路）**不再被 Key 挡住**：
      没 Key 时 `am start -a com.deepseek.pet.PICK_IMAGE` ⇒ 应当直接进相册选择。
  三、收尾：把 Key 写回去 + 重启（恢复原状态；不然后面的设备脚本会受影响）。

诚实边界：
  * 只清**当前服务商**那一槽（`api_key_<provider>`）。`api_key_migrated=true` 时
    `Prefs.getApiKey` **不会**回退到旧槽位 ⇒ 清掉那一槽就是真的"没有 Key"
    （这也是脚本要先断言这一点的原因）；
  * 相册选择器在模拟器上是 PhotoPicker，真机 HyperOS 可能不同 —— 本条只验"**不再被 Key 拦**"；
  * 真机手感仍是长期欠账。

用法：
    python build/verify_nokey_ui.py --install
    python build/verify_nokey_ui.py
    python build/verify_nokey_ui.py --selftest

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import re
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402  本地 pref 副本的擦除（防 Key 进源码包）
import verify_char_ui as V           # noqa: E402  设备驱动与判读工具
import verify_char_switch_ui as S    # noqa: E402  可靠改 pref / 设备清理

DEV = V.DEV
PKG = V.PKG
LOCAL_ONLY = '-1'                    # Prefs.INTERACT_LOCAL_ONLY：interact_cd_ms = -1 = 纯本地

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

def key_dialog_shown(texts):
    """是否弹了「输入 <服务商> 的 API Key」那个窗。

    ★ 判据不能用"含 API Key"：设置页上本来就有一行「API Key（DeepSeek）：已保存 ›」，
      拿它当判据会**永远为真**（假探针）。对话框标题的独有形状是「输入 … 的 API Key」。
    """
    for t in texts or []:
        if '输入' in t and '的 API Key' in t:
            return True
    return False


def overlay_windows(dump_text, pkg=PKG):
    """本包在屏上的**悬浮窗**数量（桌宠本体那个；排掉 MainActivity 自己的窗口）。"""
    n = 0
    for line in (dump_text or '').splitlines():
        if 'Window{' in line and pkg in line and 'MainActivity' not in line:
            n += 1
    return n


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    t('认得出「输入 … 的 API Key」弹窗',
      key_dialog_shown(['输入 DeepSeek 的 API Key', '保存', '取消']))
    t('★ 反例：设置页那一行「API Key（DeepSeek）：已保存」**不算**弹窗（否则假探针）',
      not key_dialog_shown(['API Key（DeepSeek）：已保存 ›', '对话与人格', '陪伴']))
    t('反例：普通页面不算', not key_dialog_shown(['陪伴', '形象', '系统']))
    t('空输入安全', not key_dialog_shown(None) and not key_dialog_shown([]))

    dump = ('  Window #6 Window{7f0b71a u0 com.deepseek.pet}:\n'
            '    package=com.deepseek.pet appop=SYSTEM_ALERT_WINDOW\n'
            '  Window #10 Window{be9814e u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n')
    t('数悬浮窗（排掉 MainActivity）', overlay_windows(dump) == 1)
    t('反例：只剩 MainActivity ⇒ 0',
      overlay_windows('  Window #10 Window{x u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n') == 0)
    t('反例：别的包不算', overlay_windows('  Window #1 Window{x u0 com.other}\n') == 0)

    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def texts():
    return S.all_texts()


def logcat():
    return V.out('logcat', '-d')


def restart_app(wait=6.0):
    V.sh('shell', 'am', 'force-stop', PKG)
    V.sh('shell', 'logcat', '-c')
    time.sleep(1.0)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(wait)


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能读 filesDir / prefs')
        return 1

    prov = (S.pref('chat_provider') or 'deepseek').strip()
    key_name = 'api_key_' + prov
    saved = S.pref(key_name) or ''
    print('== 一、清掉「%s」的 Key（原值 %s）后重启 ==' % (prov, (saved[:6] + '…') if saved else '(空)'))
    check('前置：当前服务商那一槽**本来有** Key（没有就谈不上"清掉"）', len(saved) > 0,
          'chat_provider=%s %s=%s' % (prov, key_name, ('有' if saved else '空')))
    check('前置：已进入"按服务商分槽"模式（api_key_migrated=true ⇒ 清掉就是真的没有）',
          S.pref('api_key_migrated') == 'true', S.pref('api_key_migrated'))
    if not saved:
        skip('无 Key 启动', '本来就没 Key（先把 Key 填上再跑这一节）')
        return finish()

    S.pref_set(key_name, '')
    check('Key 已清空（%s 现在为空）' % key_name, (S.pref(key_name) or '') == '',
          '%s=%r' % (key_name, S.pref(key_name)))
    restart_app(6.0)

    ts = texts()
    check('★★ 启动**没有**弹「输入 … 的 API Key」窗', not key_dialog_shown(ts),
          [t for t in ts if 'API Key' in t][:2])
    check('★ 设置页正常进来了（看得到底部「陪伴」）', any(t == '陪伴' for t in ts),
          ts[:6])
    check('★★ pref 落成**纯本地**（interact_cd_ms = %s）' % LOCAL_ONLY,
          S.pref('interact_cd_ms') == LOCAL_ONLY,
          'interact_cd_ms=%s（interact_cd_ms_last=%s）'
          % (S.pref('interact_cd_ms'), S.pref('interact_cd_ms_last')))
    lg = logcat()
    check('★ 日志：无 Key 时给了不打断的提示（Toast 进不了 dump，只能靠日志）',
          '无 Key：已进本地模式并提示' in lg,
          [l for l in lg.splitlines() if 'DSPKey' in l][-2:])
    check('★ 桌宠照样在跑（本地模式不影响桌宠本身）',
          overlay_windows(V.out('shell', 'dumpsys', 'window', 'windows')) >= 1,
          '悬浮窗 %d 个' % overlay_windows(V.out('shell', 'dumpsys', 'window', 'windows')))

    print()
    print('== 二、★ 换形象（ACTION_PICK_IMAGE）不再被 Key 挡住 ==')
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1.0)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity',
         '-a', 'com.deepseek.pet.PICK_IMAGE')
    picked = False
    t0 = time.time()
    while time.time() - t0 < 15:
        ts = texts()
        if any(t in ('Photos', 'Albums') for t in ts):
            picked = True
            break
        if key_dialog_shown(ts):
            break
        time.sleep(1.0)
    ts = texts()
    check('★★ 没 Key 时点「换形象」直接进相册（而不是弹 Key 窗）',
          picked and not key_dialog_shown(ts),
          ('进了相册' if picked else '没进相册') + ' / 弹 Key 窗=%s' % key_dialog_shown(ts))
    for _ in range(3):                     # 关掉选择器（BACK 可能被吃一次）
        V.sh('shell', 'input', 'keyevent', '4')
        time.sleep(0.6)

    print()
    print('== 三、收尾：把 Key 写回去（不然后面的设备脚本会受影响）==')
    S.pref_set(key_name, saved)
    check('Key 已恢复（%s）' % key_name, (S.pref(key_name) or '') == saved)
    restart_app(6.0)
    ts = texts()
    check('恢复后：有 Key 也不再弹窗（弹窗只从设置页手动进）', not key_dialog_shown(ts))
    check('恢复后：设置页正常（看得到「陪伴」）', any(t == '陪伴' for t in ts), ts[:6])
    return finish()


def finish():
    # ★ 设备脚本用完必须擦掉本地 pref 副本（含用户 Key）：项目移植包会把整个 build/ 打进去
    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails or skips:
        if fails:
            print('%d 项失败：' % len(fails))
            for f in fails:
                print('   - ' + f)
        if skips:
            print('%d 项跳过（按未验证计）：%s' % (len(skips), ' / '.join(skips)))
        return 1
    print('ALL NO-KEY DEVICE CHECKS PASSED（没 Key 也能进 + 默认本地模式，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
