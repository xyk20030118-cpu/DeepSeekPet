# -*- coding: utf-8 -*-
"""设备实测：桌宠运行时，后台不显示 app 任务卡片（最近任务）+ 通知静默 + 划卡不杀。

钉的语义（不是"存在性"）：
  ① 桌宠真的在跑（悬浮窗真在屏幕上 —— 用 dumpsys window 数窗口，不看服务状态）
  ② 最近任务里**没有**本 app 的卡片（dumpsys activity recents 无 RecentTaskInfo）
  ③ 通知渠道 importance 已降到 1（IMPORTANCE_MIN）
  ④ 用户离开 App 后卡片消失（这才是"后台不显示"的实际场景）
  ⑤ 划掉卡片后桌宠仍活着（onTaskRemoved 重新拉起）

★ 为什么必须走 monkey 拉起 App：
  实测 `am start-foreground-service` 报 `Requires permission not exported`，
  而 Android 8+ 后台 App 启动前台服务受限。必须先把 App 拉到前台，
  它在 onCreate 里自己拉起桌宠服务。见 verify_float_device.py 的同款教训。
"""
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'

fails = []
results = []


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'replace')


def check(name, ok, detail):
    results.append((name, ok, detail))
    if not ok:
        fails.append(name)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ' :: ' + detail)


def pet_windows():
    """数桌宠的悬浮窗（TYPE_APPLICATION_OVERLAY）。"""
    out = sh('shell', 'dumpsys', 'window', 'windows')
    n = 0
    for blk in out.split('Window{'):
        if PKG in blk and 'APPLICATION_OVERLAY' in blk:
            n += 1
    return n


def recents_has_pkg():
    """最近任务里是否有本包的任务；返回 (有无, 条数)。"""
    out = sh('shell', 'dumpsys', 'activity', 'recents')
    hits = [b for b in out.split('RecentTaskInfo #') if PKG in b]
    return (len(hits) > 0, len(hits))


def channel_importance():
    out = sh('shell', 'dumpsys', 'notification', '--noredact')
    imps = {}
    for m in re.finditer(r'NotificationChannel\{[^}]*?mId=\'([^\']+)\'[^}]*?mImportance=(\d+)', out):
        imps[m.group(1)] = int(m.group(2))
    return imps


def bring_pet_up():
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(9)


print('=== 0. 重置：停掉 App，清干净 ===')
sh('shell', 'am', 'force-stop', PKG)
time.sleep(3)
# ★ 必须先确保悬浮窗权限：全新安装后 appops 是 default（不是 allow），
# 桌宠会**合法地**起不来，于是"悬浮窗为 0"被误报成功能失败
# —— 本轮就踩过一次：卸载重装后忘了给权限，三条断言全红。
sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
sh('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
time.sleep(2)
appops = sh('shell', 'appops', 'get', PKG, 'SYSTEM_ALERT_WINDOW')
check('前置：悬浮窗权限已授予（否则本测试不成立）',
      'allow' in appops, appops.strip())

print('=== 1. 拉起 App（它会自己起桌宠）===')
bring_pet_up()
nw = pet_windows()
check('桌宠悬浮窗真的在屏上', nw > 0, 'APPLICATION_OVERLAY 窗口数 = %d' % nw)

print('=== 2. 服务态 ===')
svc = sh('shell', 'dumpsys', 'activity', 'services', PKG)
alive = 'FloatingService' in svc
check('FloatingService 存活', alive, 'dumpsys services 命中 = %s' % alive)

print('=== 3. 通知渠道 importance（要求 = 1 / MIN）===')
imps = channel_importance()
pet_ch = {k: v for k, v in imps.items() if 'deepseek' in k.lower() or 'pet' in k.lower()}
check('桌宠通知渠道存在', len(pet_ch) > 0, '渠道 = %s' % pet_ch)
check('importance 已降到 1(MIN)',
      any(v == 1 for v in pet_ch.values()),
      '实测 importance = %s' % sorted(pet_ch.values()))

print('=== 4. 离开 App（回桌面）后，最近任务里应无卡片 ===')
sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(6)
has, cnt = recents_has_pkg()
check('回桌面后最近任务无本 app 卡片', not has,
      'recents 命中条数 = %d' % cnt)

print('=== 5. 桌宠离开 App 后仍在跑 ===')
nw2 = pet_windows()
check('回桌面后桌宠悬浮窗仍在', nw2 > 0, 'APPLICATION_OVERLAY 窗口数 = %d' % nw2)

print('=== 6. 划卡不杀桌宠 ===')
# ★ 这一条**不能**在这里测：桌宠正常运行时卡片本来就已不在 recents 里
# （那正是功能生效的结果），dumpsys 里找不到可移除的任务 id，
# 断言就变成空转 —— 第一版我这么写，实测报"未找到任务 id"。
# 真正的划卡测试必须在"卡片恰好存在"的窗口期（App 在前台）操作，
# 已拆到独立的 verify_swipe_card.py（实测 ALL CHECKS PASSED）。
print('    [跳过] 已拆分到 verify_swipe_card.py（需要卡片存在的窗口期）')

print()
print('=' * 62)
for name, ok, detail in results:
    print(('PASS  ' if ok else 'FAIL  ') + name + ' :: ' + detail)
print('=' * 62)
if fails:
    print('结果：%d 项失败 -> %s' % (len(fails), fails))
    sys.exit(1)
print('结果：ALL CHECKS PASSED (%d 项)' % len(results))
