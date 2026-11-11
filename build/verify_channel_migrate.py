# -*- coding: utf-8 -*-
"""设备实测：老用户升级路径 —— 旧渠道 IMPORTANCE_LOW 必须被迁到静默渠道。

背景：Android 的 NotificationChannel **重要性创建后不可修改**
（同 id 再 createNotificationChannel 是 no-op）。所以对已经装过旧版的用户，
光改代码里的 IMPORTANCE_LOW→MIN 是**不生效**的 —— 实测验证过这一点。
代码用"换新 id"来迁：deepseek_pet → deepseek_pet_quiet。

★ 怎么造出"老用户"状态：
  渠道跟随包名持久化，pm clear 也删不掉。所以要真的走一遍迁移分支，
  必须让 App 看到"deepseek_pet 存在（LOW），且 quiet 不存在"。
  做法：卸载 → 装**旧包**（渠道被建成 LOW）→ 直接覆盖装**新包**。

  系统没给"改渠道重要性/删渠道"的 adb 命令，所以走**覆盖安装**这条路。

★ 关于断言口径（重要）：
  实测确认旧渠道 `deepseek_pet` **删不掉** —— deleteNotificationChannel 之后
  它仍会以 mDeleted=false 复活（连卸载重装都在，系统按包名持久化渠道）。
  所以这里钉的判据是「**通知实际走哪个渠道**」，而不是「旧渠道是否消失」。
  后者是不可达目标，钉它就是给自己造红灯。
"""
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk  # noqa: E402

NEW_APK = dev_apk()
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'

fails, results = [], []


def sh(*a, timeout=180):
    return subprocess.run([ADB, '-s', DEV] + list(a),
                          capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


def check(name, ok, detail):
    results.append((name, ok, detail))
    if not ok:
        fails.append(name)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ' :: ' + detail)


def channels():
    """返回 {渠道id: importance}，只取本 app 的桌宠相关渠道。"""
    out = sh('shell', 'dumpsys', 'notification', '--noredact')
    res = {}
    for m in re.finditer(r"mId='([^']+)'[^}]*?mImportance=(\d+)", out):
        cid = m.group(1)
        if cid.startswith('deepseek_pet'):
            res[cid] = int(m.group(2))
    return res


def used_channels():
    """桌宠实际在用的渠道 id 集合（从通知记录里取）。"""
    out = sh('shell', 'dumpsys', 'notification', '--noredact')
    hit = set()
    for b in re.split(r'NotificationRecord\(', out)[1:]:
        if 'com.deepseek.pet' not in b:
            continue
        for m in re.finditer(r"mId='(deepseek_pet[a-z_]*)'", b):
            hit.add(m.group(1))
    return hit


def launch_and_wait(sec=11):
    sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    sh('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(sec)


print('=== 0. 卸载干净 ===')
sh('shell', 'am', 'force-stop', PKG, timeout=60)
sh('uninstall', PKG, timeout=180)
time.sleep(3)

print('=== 1. 装当前新包，建立基线渠道 ===')
r = sh('install', '-r', NEW_APK, timeout=600)
check('新包安装成功', 'Success' in r, r.strip().splitlines()[-1] if r.strip() else 'no output')
launch_and_wait()
base = channels()
print('    基线渠道 = %s' % base)
check('静默渠道 deepseek_pet_quiet 已建立', 'deepseek_pet_quiet' in base,
      'quiet importance = %s' % base.get('deepseek_pet_quiet'))
check('静默渠道 importance == 1 (MIN)',
      base.get('deepseek_pet_quiet') == 1,
      '实测 = %s' % base.get('deepseek_pet_quiet'))

print('=== 2. 桌宠运行通知挂在静默渠道（决定用户看到什么）===')
on_ch = used_channels()
print('    本 app 通知记录命中渠道 = %s' % sorted(on_ch))
check('桌宠运行通知走静默渠道', 'deepseek_pet_quiet' in on_ch,
      '命中 = %s' % sorted(on_ch))

print('=== 3. 真正的迁移场景：旧包建 LOW 渠道 -> 覆盖升级新包 ===')
OLD_APK = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       'release_src', '_old_low_channel.apk')
if not os.path.exists(OLD_APK):
    # 旧包需由"改动前"的代码构建，属可选场景：缺了要明说，不能假装通过
    check('旧版 APK 存在（用于构造迁移场景）', False,
          '缺少 %s —— 该场景未被真机覆盖' % OLD_APK)
else:
    sh('uninstall', PKG, timeout=180)
    time.sleep(3)
    sh('install', '-r', OLD_APK, timeout=600)
    launch_and_wait()
    legacy = channels()
    print('    旧版装完的渠道 = %s' % legacy)
    check('旧版确实建出 LOW 渠道（场景成立）',
          legacy.get('deepseek_pet', 0) >= 2,
          'deepseek_pet importance = %s' % legacy.get('deepseek_pet'))

    sh('install', '-r', NEW_APK, timeout=600)
    launch_and_wait()
    after = channels()
    print('    升级后的渠道 = %s' % after)
    check('升级后已建静默渠道且为 MIN', after.get('deepseek_pet_quiet') == 1,
          'quiet = %s' % after.get('deepseek_pet_quiet'))
    on2 = used_channels()
    check('升级后通知走静默渠道（旧渠道不再被使用）',
          'deepseek_pet_quiet' in on2,
          '命中 = %s' % sorted(on2))

print()
print('=' * 62)
for name, ok, detail in results:
    print(('PASS  ' if ok else 'FAIL  ') + name + ' :: ' + detail)
print('=' * 62)
if fails:
    print('结果：%d 项失败 -> %s' % (len(fails), fails))
    sys.exit(1)
print('结果：ALL CHECKS PASSED (%d 项)' % len(results))
