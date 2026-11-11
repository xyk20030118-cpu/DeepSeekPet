"""实测：页面切换是**纯平移**（无缩放/淡入）—— 用日志判据。

★ 为什么不用截图：
  `input tap` + `screencap` 的往返延迟远超 260ms 动画时长，
  截图抓不到中间帧。实测抓到的两帧面积比 0.991、位移 1.0px ——
  完全静止，那种"通过"是**空转**的（我第一版就是这样误判的）。

★ 日志判据（确定的事实）：
  pageSlide 在赋值后打一行 DSPAnim，含 from / scale / alpha。
  纯平移必须满足：
    · |from| == 屏宽（整屏平移，不是 1/3）
    · scale == 1.0（不缩放）
    · alpha == 1.0（不淡入）
    · forward=true 时 from > 0（从右侧进），false 时 from < 0（从左侧进）
"""
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def dump():
    local = r'D:\petport_lab\petbuild\build\_pt.xml'
    import os
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


sh('shell', 'am', 'force-stop', PKG)
time.sleep(2)
sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(9)
x = dump()
if '外观与显示' not in x:
    time.sleep(5)
    x = dump()

sh('logcat', '-c')
time.sleep(1)

print('=== 触发两次转场（进二级页 → 返回）===')
print('   进二级页:', tap_text(x, '外观与显示', wait=2.5))
sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(2.5)

o = sh('logcat', '-d', '-s', 'DSPAnim').stdout.decode('utf-8', 'replace')
lines = [l for l in o.splitlines() if 'pageSlide' in l]
print()
print('=== DSPAnim 日志 ===')
for l in lines:
    print('   %s' % l.split('DSPAnim:')[-1].strip())

print()
print('=== 判定 ===')
if not lines:
    print('   ✗ 没有任何 pageSlide 日志 → 转场没走 pageSlide，无法验证')
    sys.exit(1)

ok_all = True
for l in lines:
    body = l.split('DSPAnim:')[-1].strip()
    fwd = re.search(r'forward=(\w+)', body)
    frm = re.search(r'from=(-?[\d.]+)', body)
    sc = re.search(r'scale=([\d.]+)/([\d.]+)', body)
    al = re.search(r'alpha=([\d.]+)', body)
    sw = re.search(r'screenW=(\d+)', body)
    if not (fwd and frm and sc and al and sw):
        print('   ✗ 日志格式不符: %s' % body)
        ok_all = False
        continue
    direction = (fwd.group(1) == 'true')
    fromv = float(frm.group(1))
    s1, s2 = float(sc.group(1)), float(sc.group(2))
    a = float(al.group(1))
    screenw = float(sw.group(1))

    checks = [
        (abs(abs(fromv) - screenw) < 1.0,
         '位移应等于整屏宽（%.0f vs %.0f）' % (abs(fromv), screenw)),
        (fromv > 0 if direction else fromv < 0,
         '方向：forward=%s 时 from 应%s' % (direction, '>0' if direction else '<0')),
        (s1 == 1.0 and s2 == 1.0, 'scale 必须恒为 1.0（当前 %s/%s）' % (s1, s2)),
        (a == 1.0, 'alpha 必须恒为 1.0（当前 %s）' % a),
    ]
    for cond, why in checks:
        if not cond:
            print('   ✗ %s' % why)
            ok_all = False

if ok_all:
    print('   ✓ 全部转场都是纯平移：|from|=整屏宽、scale=1、alpha=1')
    print('   ✓ 方向正确（进二级页 from>0，返回 from<0）')
sys.exit(0 if ok_all else 1)
