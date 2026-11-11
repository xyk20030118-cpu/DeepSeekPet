"""设备实测：余额/提示气泡随 UI 主题变色的定性验证。

验证逻辑：
  1. 切换主题（新拟态/玻璃 × 浅色/深色共 4 组）。
  2. 启动桌宠 → 回桌面 → 单击桌宠触发气泡。
  3. 通过 dumpsys 窗口坐标截取气泡内部，计算平均亮度。
  4. 同引擎下：浅色主题的平均亮度必须显著高于深色主题。

注意：本测试需要 adb root，否则无法写入 com.deepseek.pet 的 shared_prefs。
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
    m = re.search(r'\d+\s*x\s*\d+\s*@\s*(\d+)dpi', out)
    if m:
        return int(m.group(1)) / 160.0
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
        tag = 'name="%s"' % k
        if tag in body:
            body = re.sub(r'<int name="%s"[^>]*/>' % k,
                          '<int name="%s" value="%d" />' % (k, v), body)
        else:
            body = body.replace('</map>',
                                '    <int name="%s" value="%d" />\n</map>' % (k, v))
    local = os.path.join(OUT, '_bt_%d_%d.xml' % (skin, theme))
    open(local, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', local, '/sdcard/_bt.xml')
    uid = sh('shell', 'stat', '-c', '%u:%g', '/data/data/%s' % PKG).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_bt.xml', UI_XML)
    sh('shell', 'chown', uid, UI_XML)
    sh('shell', 'chmod', '660', UI_XML)


def start_pet():
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(9)
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(4)


def overlay_windows():
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    lines = o.splitlines()
    out = []
    for i, l in enumerate(lines):
        if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l):
            for k in range(i, min(i + 45, len(lines))):
                m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', lines[k])
                if m:
                    f = (int(m.group(1)), int(m.group(2)),
                         int(m.group(3)), int(m.group(4)))
                    out.append(f)
                    break
    return out


def pet_center():
    for f in overlay_windows():
        x = (f[0] + f[2]) // 2
        y = (f[1] + f[3]) // 2
        if 0 < x < 2000 and 0 < y < 3000:
            return x, y
    return None


def snap(path):
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'screencap', '-p'], capture_output=True)
    if len(r.stdout) < 5000:
        return None
    open(path, 'wb').write(r.stdout)
    return path


def bubble_frame(base_frames):
    candidates = []
    for f in overlay_windows():
        if f in base_frames:
            continue
        w = f[2] - f[0]
        h = f[3] - f[1]
        area = w * h
        # 气泡一般是中小浮层，排除菜单级尺寸和 tiny 噪点
        if 10000 < area < 400000:
            candidates.append((area, f))
    if not candidates:
        return None
    candidates.sort(reverse=True)
    return candidates[0][1]


def sample_interior(path, frame, inset=12):
    im = Image.open(path).convert('RGB')
    a = np.array(im)
    x0, y0, x1, y1 = frame
    x0 = max(0, x0 + inset)
    y0 = max(0, y0 + inset + 10)   # 再往下一点，躲开尖角/阴影
    x1 = min(a.shape[1], x1 - inset)
    y1 = min(a.shape[0], y1 - inset)
    if x1 <= x0 or y1 <= y0:
        return None
    patch = a[y0:y1, x0:x1]
    return patch


SKIN_NAMES = ['neumorph', 'glass']
THEME_NAMES = ['light', 'dark']
results = {}
ok = True

print('=== 气泡主题跟随：截图 + 亮度判据 ===')
for skin in (0, 1):
    for theme in (0, 1):
        set_ui(skin, theme)
        start_pet()
        base = set(overlay_windows())
        c = pet_center()
        tag = '%s_%s' % (SKIN_NAMES[skin], THEME_NAMES[theme])
        if not c:
            print('   ✗ %s: 取不到桌宠中心' % tag)
            ok = False
            continue
        # 单击触发气泡
        sh('shell', 'input', 'tap', str(c[0]), str(c[1]))
        time.sleep(2.0)
        f = bubble_frame(base)
        p = os.path.join(OUT, 'bubble_%s.png' % tag)
        snap(p)
        if f is None:
            # 尝试用截图里最大 overlay 兜底
            f = bubble_frame(set())
        print('   %s: pet=(%d,%d) bubble=%s' % (tag, c[0], c[1], f))
        if f is None:
            print('   ✗ %s: 未检测到气泡窗口' % tag)
            ok = False
            continue
        patch = sample_interior(p, f)
        if patch is None:
            print('   ✗ %s: 气泡区域无效' % tag)
            ok = False
            continue
        mean_rgb = patch.mean(axis=(0, 1))
        lum = 0.299 * mean_rgb[0] + 0.587 * mean_rgb[1] + 0.114 * mean_rgb[2]
        results[(skin, theme)] = lum
        print('   %s: 平均 RGB=(%.0f,%.0f,%.0f) 亮度=%.1f' %
              (tag, mean_rgb[0], mean_rgb[1], mean_rgb[2], lum))

print()
for skin in (0, 1):
    light = results.get((skin, 0))
    dark = results.get((skin, 1))
    if light is None or dark is None:
        continue
    if light <= dark:
        print('   ✗ %s: 浅色亮度(%.1f)未高于深色(%.1f)' % (SKIN_NAMES[skin], light, dark))
        ok = False
    else:
        print('   ✓ %s: 浅色亮度(%.1f) > 深色亮度(%.1f)' % (SKIN_NAMES[skin], light, dark))

print()
print('=== 结果 ===')
print('   %s' % ('全部通过 ✓' if ok else '有失败项 ✗'))
sys.exit(0 if ok else 1)
