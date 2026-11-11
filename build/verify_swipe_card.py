# -*- coding: utf-8 -*-
"""设备实测：用**真实最近任务界面**划掉桌宠 app 的卡片，确认桌宠不被杀。

为什么要单独测这一条：
  前一个脚本 verify_recents_hidden.py 里，桌宠正常运行时卡片本来就**已经不在**
  recents 里（那是功能生效），所以我在 dumpsys 里找不到可移除的任务 id，
  那条断言无从验证 —— 是**测试自身的缺陷**，不是功能缺陷。
  要真正测"划卡不杀桌宠"，必须在卡片**恰好存在**的窗口期操作：
  即 App 处于前台（卡片还在）时，打开任务页划掉它。

判据（钉语义，不钉存在性）：
  ① 操作前：卡片确实存在（否则这条测试是空转，必须报失败）
  ② 划卡后：桌宠悬浮窗仍在屏幕上（真在看屏幕，不看服务状态字符串）
  ③ 划卡后：FloatingService 仍存活
"""
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'

fails, results = [], []


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'replace')


def check(name, ok, detail):
    results.append((name, ok, detail))
    if not ok:
        fails.append(name)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ' :: ' + detail)


def pet_windows():
    out = sh('shell', 'dumpsys', 'window', 'windows')
    return sum(1 for b in out.split('Window{')
               if PKG in b and 'APPLICATION_OVERLAY' in b)


def app_task_id():
    """返回本 app 在 recents 里的 taskId（没有则 None）。"""
    out = sh('shell', 'dumpsys', 'activity', 'recents')
    cur = None
    for line in out.splitlines():
        m = re.match(r'\s*RecentTaskInfo #\d+:', line)
        if m:
            cur = None
            continue
        mt = re.search(r'taskId=(\d+)', line)
        if mt and cur is None:
            cur = mt.group(1)
        if PKG in line and cur:
            return cur
    return None


print('=== 1. 把 App 拉到前台（此时 recents 卡片必然存在）===')
sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(9)
tid = app_task_id()
check('操作前卡片确实存在（否则本测试空转）', tid is not None,
      'app taskId = %s' % tid)
before_win = pet_windows()
print('    操作前桌宠窗口数 = %d' % before_win)

print('=== 2. 打开最近任务界面，真划掉这张卡片 ===')
sh('shell', 'input', 'keyevent', 'KEYCODE_APP_SWITCH')
time.sleep(3)
# 从屏幕中线往上甩 —— 标准"划掉卡片"手势
sz = sh('shell', 'wm', 'size')
m = re.search(r'(\d+)x(\d+)', sz)
w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
cx = w // 2
sh('shell', 'input', 'swipe', str(cx), str(int(h * 0.55)),
   str(cx), str(int(h * 0.08)), '300')
time.sleep(3)
sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
time.sleep(6)

after_tid = app_task_id()
check('划卡后卡片已消失', after_tid is None,
      '划卡后 app taskId = %s' % after_tid)

print('=== 3. 桌宠是否还活着（看屏幕，不只看服务状态）===')
after_win = pet_windows()
check('划卡后桌宠悬浮窗仍在屏上', after_win > 0,
      '划卡前窗口 = %d，划卡后窗口 = %d' % (before_win, after_win))

svc = sh('shell', 'dumpsys', 'activity', 'services', PKG)
check('划卡后 FloatingService 仍存活', 'FloatingService' in svc,
      'dumpsys services 命中 = %s' % ('FloatingService' in svc))

print()
print('=' * 62)
for name, ok, detail in results:
    print(('PASS  ' if ok else 'FAIL  ') + name + ' :: ' + detail)
print('=' * 62)
if fails:
    print('结果：%d 项失败 -> %s' % (len(fails), fails))
    sys.exit(1)
print('结果：ALL CHECKS PASSED (%d 项)' % len(results))
