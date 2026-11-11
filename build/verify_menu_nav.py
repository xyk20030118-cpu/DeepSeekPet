"""设备实测：两级动作菜单（截图判据 + 窗口坐标定位）。

★ 为什么不用 uiautomator 文本：
  实测 TYPE_APPLICATION_OVERLAY 窗口**不进 uiautomator dump** ——
  dump 只有桌面文字，菜单明明开着也被判成"没开"。
  我第一版就是这样误判的。这一版改用**截图区域比对** + dumpsys 窗口坐标。

导航链（用户的核心要求）：
  三击打开 → 一级「分类」
  点分类   → 二级「动作」（内容必须变化）
  点返回   → 回到一级（内容必须回到与一级一致）
  点动作   → 菜单关闭

★ 启动：必须 monkey 拉起 App（桌宠服务才会起），
  不能 am startservice（Android 8+ 报 background uid null）。
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
UI_XML = '/data/data/%s/shared_prefs/deepseek_pet_ui.xml' % PKG
OUT = r'D:\petport_lab\petbuild\build\_ui'
from PIL import Image
import numpy as np


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def density_scale():
    out = sh('shell', 'wm', 'density').stdout.decode('utf-8', 'replace')
    m = re.search(r'(\d+)\s*x\s*\d+\s*@\s*(\d+)dpi', out)
    if m:
        return int(m.group(2)) / 160.0
    m = re.search(r'(\d+)dpi', out)
    if m:
        return int(m.group(1)) / 160.0
    return 2.625


def set_ui(skin, theme):
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    body = sh('shell', 'cat', UI_XML).stdout.decode('utf-8', 'replace')
    if not body.strip():
        body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>"
    for k, v in (('ui_skin', skin), ('ui_theme', theme)):
        if 'name="%s"' % k in body:
            body = re.sub(r'<int name="%s"[^>]*/>' % k,
                          '<int name="%s" value="%d" />' % (k, v), body)
        else:
            body = body.replace('</map>',
                                '    <int name="%s" value="%d" />\n</map>' % (k, v))
    local = r'D:\petport_lab\petbuild\build\_vm.xml'
    open(local, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', local, '/sdcard/_vm.xml')
    uid = sh('shell', 'stat', '-c', '%u:%g', '/data/data/%s' % PKG).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_vm.xml', UI_XML)
    sh('shell', 'chown', uid, UI_XML)
    sh('shell', 'chmod', '660', UI_XML)


def start_pet():
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(9)
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(5)


def overlay_windows():
    """返回 [(x0,y0,x1,y1)]，本包所有非 Activity 窗口的 frame"""
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    lines = o.splitlines()
    out = []
    for i, l in enumerate(lines):
        if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l):
            for k in range(i, min(i + 45, len(lines))):
                m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', lines[k])
                if m:
                    out.append((int(m.group(1)), int(m.group(2)),
                                int(m.group(3)), int(m.group(4))))
                    break
    return out


def menu_frame(base_frames):
    """菜单 = 面积最大的 overlay（桌宠/气泡都更小）"""
    best = None
    for f in overlay_windows():
        if f in base_frames:
            continue
        area = (f[2] - f[0]) * (f[3] - f[1])
        if best is None or area > best[0]:
            best = (area, f)
    if best is None:
        # 退路：直接取面积最大的 overlay
        for f in overlay_windows():
            area = (f[2] - f[0]) * (f[3] - f[1])
            if best is None or area > best[0]:
                best = (area, f)
    return best[1] if best else None


def pet_center():
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    lines = o.splitlines()
    for i, l in enumerate(lines):
        if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l):
            for k in range(i, min(i + 45, len(lines))):
                m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', lines[k])
                if m:
                    x = (int(m.group(1)) + int(m.group(3))) // 2
                    y = (int(m.group(2)) + int(m.group(4))) // 2
                    if 0 < x < 2000 and 0 < y < 3000:
                        return x, y
    return None


def snap(tag):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, 'mn_%s.png' % tag)
    if os.path.exists(p):
        os.remove(p)
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'screencap', '-p'], capture_output=True)
    if len(r.stdout) < 5000:
        return None
    open(p, 'wb').write(r.stdout)
    return p


def panel_patch(path, frame):
    """截菜单区域的一小块（避开阴影边）"""
    im = Image.open(path).convert('L')
    a = np.array(im).astype(int)
    x0, y0, x1, y1 = frame
    x0 = max(0, x0 + 12); y0 = max(0, y0 + 12)
    x1 = min(a.shape[1], x1 - 12); y1 = min(a.shape[0], y1 - 12)
    if x1 - x0 < 20 or y1 - y0 < 20:
        return None
    return a[y0:y1, x0:x1]


def diff(a, b):
    if a is None or b is None or a.shape != b.shape:
        return 999.0
    return float(np.abs(a - b).mean())


def triple_tap():
    c = pet_center()
    if not c:
        return False
    for _ in range(3):
        sh('shell', 'input', 'tap', str(c[0]), str(c[1]))
        time.sleep(0.18)
    time.sleep(3.0)
    return True


ok = True

print('=== 两级动作菜单：截图判据 ===')
SKIN, THEME = 0, 0       # 新拟态 + 浅色（默认）
DSCALE = density_scale()
set_ui(SKIN, THEME)
start_pet()
base = set(overlay_windows())
print('   打开前 overlay 窗口 = %d' % len(base))

if not triple_tap():
    print('   ✗ 三击手势没发出')
    sys.exit(1)

f1 = menu_frame(base)
if f1 is None:
    print('   ✗ 找不到菜单窗口（没打开？）')
    sys.exit(1)
print('   菜单窗口 frame = %s' % (f1,))
p1 = snap('level1')
img1 = panel_patch(p1, f1)
if img1 is None:
    print('   ✗ 一级截图区域无效')
    sys.exit(1)

# 一级 = 点分类
fw = f1[2] - f1[0]
cx = f1[0] + fw // 2
# 面板内几何（dp，按 FloatingService 实际布局）：
#  body 上内边距 = panelInset + 8dp（新拟态 6+8=14dp；玻璃 9+8=17dp）
#  顶栏 = H_CONTROL = 48dp；返回文字中心 = 顶栏中心 = 上方 + 24dp
#  flatList 上 padding = 4dp；第一行中心 = 行高一半 = 24dp
#  => 第一行中心 = frame_top + bodyPadTopDp + 48dp + 4dp + 24dp
body_pad_top_dp = 14 if SKIN == 0 else 17
hdr_y = f1[1] + round(body_pad_top_dp * DSCALE) + round(24 * DSCALE)
row1_y = f1[1] + round(body_pad_top_dp * DSCALE) + round(76 * DSCALE)
sh('shell', 'input', 'tap', str(cx), str(row1_y))
time.sleep(2.5)
f2 = menu_frame(base)
p2 = snap('level2')
img2 = panel_patch(p2, f2 if f2 else f1)
d12 = diff(img1, img2)
print('   一级 → 二级: frame=%s 图像差异=%.2f' % (f2, d12))
if d12 <= 10:
    print('   ✗ 点分类后内容没变化（未进入二级）')
    ok = False
else:
    print('   ✓ 进入二级（内容已变化）')

# 顶栏 = 返回分类（顶栏左侧）
sh('shell', 'input', 'tap', str(f1[0] + 150), str(hdr_y))
time.sleep(2.5)
f3 = menu_frame(base)
if f3 is None:
    print('   ✗ 返回后菜单消失了')
    ok = False
else:
    p3 = snap('back_to_l1')
    img3 = panel_patch(p3, f3)
    d13 = diff(img1, img3)
    print('   返回 → 一级: frame=%s 与原一级差异=%.2f' % (f3, d13))
    if d13 > 10:
        print('   ✗ 返回后内容与一级不一致（没真正回到一级）')
        ok = False
    else:
        print('   ✓ 返回分类后回到一级（内容一致）')

# 点一个叶子动作 → 菜单应关闭
sh('shell', 'input', 'tap', str(cx), str(row1_y))
time.sleep(2.0)
f4 = menu_frame(base)
print('   再进二级后 frame = %s' % (f4,))
if f4 is None:
    print('   ✗ 二级没打开（点动作那步无法验证）')
else:
    leaf_y = f4[1] + round(body_pad_top_dp * DSCALE) + round(76 * DSCALE)
    sh('shell', 'input', 'tap', str(cx), str(leaf_y))
    time.sleep(3.5)
    current = overlay_windows()
    menu_still = any(f == f4 or f == f1 for f in current if f not in base)
    if menu_still:
        print('   ✗ 点动作后菜单仍开着 frame=%s' % (f4,))
        ok = False
    else:
        print('   ✓ 点动作后菜单已关闭')

print()
print('=== 结果 ===')
print('   %s' % ('全部通过 ✓' if ok else '有失败项 ✗'))
sys.exit(0 if ok else 1)
