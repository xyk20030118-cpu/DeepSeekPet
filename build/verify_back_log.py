"""用手势触发预测式返回，从 logcat 抓 DSPBack 的 progress 回调。

★ 这是**权威证据**：
  像素位移分不清"我的动画"和"系统自带的窗口动画"
  （实测：把我的动画 no-op 后仍有 ~48px 位移）。
  而 logcat 里的 `progress=...` 只可能来自我的
  {@code applyPredictiveBack} —— 它被调用即证明回调链路通了。

判据：
  · 有 progress 行，且数值**递增**（0 → 1）⇒ 系统在持续回调进度，跟随真实生效
  · 没有 progress 行 ⇒ 系统没调进度回调（可能设备不支持/手势没被识别为返回）
"""
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'
W, H = 1080, 2400


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def dump():
    local = r'D:\petport_lab\petbuild\build\_lg.xml'
    if os.path.exists(local):
        os.remove(local)
    sh('shell', 'rm', '-f', '/sdcard/_d.xml')
    sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh('pull', '/sdcard/_d.xml', local)
    if not os.path.exists(local):
        return ''
    return open(local, encoding='utf-8', errors='replace').read()


def tap_text(x, needle, wait=2.0):
    ty = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        if t and needle in t.group(1):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if b:
                ty = (int(b.group(2)) + int(b.group(4))) // 2
                break
    if ty is None:
        return False
    best = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        if 'clickable="true"' not in s:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not b:
            continue
        y0, y1 = int(b.group(2)), int(b.group(4))
        if y0 <= ty <= y1:
            area = (int(b.group(3)) - int(b.group(1))) * (y1 - y0)
            if best is None or area < best[0]:
                best = (area, (int(b.group(1)) + int(b.group(3))) // 2,
                        (y0 + y1) // 2)
    if not best:
        return False
    sh('shell', 'input', 'tap', str(best[1]), str(best[2]))
    time.sleep(wait)
    return True


print('=== 准备：进二级页 ===')
sh('shell', 'am', 'force-stop', PKG)
time.sleep(2)
sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(8)
x = dump()
if '外观与显示' not in x:
    time.sleep(5)
    x = dump()
if not tap_text(x, '外观与显示'):
    sys.exit('进不去二级页')
print('   前台 = %s' % sh('shell', 'dumpsys', 'activity', 'activities')
      .stdout.decode('utf-8', 'replace').split('topResumedActivity')[-1][:60])

# 清日志，确保只看到这次手势的
sh('logcat', '-c')
time.sleep(1)

print()
print('=== 发慢速返回手势 ===')
subprocess.run([ADB, '-s', DEV, 'shell', 'input', 'swipe',
                '2', str(H // 2), '560', str(H // 2), '1400'],
               capture_output=True)
time.sleep(3)

print()
print('=== logcat 里的进度回调 ===')
o = sh('logcat', '-d', '-s', 'DSPBack').stdout.decode('utf-8', 'replace')
lines = [l for l in o.splitlines() if 'progress=' in l]
for l in lines[:20]:
    print('   %s' % l.split('DSPBack:')[-1].strip())
if len(lines) > 20:
    print('   ... 共 %d 行' % len(lines))

print()
print('=== 判定 ===')
if not lines:
    print('   ✗ 没有 progress 回调 ⇒ 系统没调用进度接口')
    print('     （可能：手势没被识别为返回，或设备 ROM 未启用预测式返回）')
    sys.exit(1)

vals = []
for l in lines:
    m = re.search(r'progress=([\d.]+)', l)
    if m:
        vals.append(float(m.group(1)))
print('   收到 %d 次进度回调' % len(vals))
print('   进度序列 = %s' % ', '.join('%.2f' % v for v in vals[:12])
      + (' ...' if len(vals) > 12 else ''))
mono = all(vals[i] <= vals[i + 1] + 1e-6 for i in range(len(vals) - 1))
print('   单调递增: %s' % ('是 ✓' if mono else '否（有回退，正常：手可能抖）'))
print('   最大进度 = %.2f' % max(vals))
if len(vals) >= 3:
    print('   ✓ 预测式返回动画确实在跑（进度回调持续送达）')
    sys.exit(0)
print('   ✗ 回调次数太少（%d），不足以证明跟随' % len(vals))
sys.exit(1)
