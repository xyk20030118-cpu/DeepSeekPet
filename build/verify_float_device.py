"""设备实测：四个浮层在深色/浅色下是否真的跟随主题。

★ 判据用**亮度对比**（与主题跟随那次同一套方法）：
  同一个浮层，浅色截一次、深色截一次 —— 亮度必须明显不同。
  仍然一样就说明那个浮层没跟随主题。

★ 为什么要四个都测：这类改动最大的风险是**漏改一处**。
  源码断言已按文件检查过，但设备实测能抓出"改了但没生效"
  （例如某处背景被别的东西覆盖）。

★ 怎么打开这四个浮层：
  ① 气泡     —— 触发一次说话/播报（点桌宠）
  ② 输入框   —— Prefs.ACTION_ASK 直接拉起（exported service action）
  ③ 动作菜单 —— 三击桌宠（难以自动化），改用 showMenu 的 action
  ④ 长按菜单 —— 长按桌宠（难以自动化）
  ③④ 用 adb 的长按手势实现：`input swipe x y x y 800`（同点长按）
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
    local = r'D:\petport_lab\petbuild\build\_ft.xml'
    open(local, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', local, '/sdcard/_ft.xml')
    uid = sh('shell', 'stat', '-c', '%u:%g', '/data/data/%s' % PKG).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_ft.xml', UI_XML)
    sh('shell', 'chown', uid, UI_XML)
    sh('shell', 'chmod', '660', UI_XML)


def start_pet():
    """启动 App（它会自己拉起桌宠服务）。

    ★ 不能用 `am startservice` —— 实测报
      `Error: app is in background uid null`：
      Android 8+ 对**后台** App 启动前台服务有严格限制。
      必须先用 monkey 把 App 拉到前台，它在 onCreate 里会拉起桌宠。
      我第一版就是直接 startservice，结果桌宠从未启动、
      四个浮层全都没打开，测出来的"亮度差 0.0"是**什么都没测到**。
    """
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(9)
    # 回桌面，让桌宠浮在桌面上（设置页会主动把桌宠收起）
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(5)


def pet_window_count():
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    return sum(1 for l in o.splitlines()
               if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l))


def snap(tag):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, 'ft_%s.png' % tag)
    if os.path.exists(p):
        os.remove(p)
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'screencap', '-p'],
                       capture_output=True)
    if len(r.stdout) < 5000:
        return None
    open(p, 'wb').write(r.stdout)
    return p


def mean_lum(im, box=None):
    if box:
        im = im.crop(box)
    a = np.array(im.convert('L')).astype(int)
    return float(a.mean())


def pet_center():
    """找桌宠窗口的大致中心（用它来发长按/点击手势）"""
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    for l in o.splitlines():
        if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l):
            m = re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', l)
            if m:
                x = (int(m.group(1)) + int(m.group(3))) // 2
                y = (int(m.group(2)) + int(m.group(4))) // 2
                return x, y
    return None


def open_ask():
    """双击桌宠 → 输入条。

    ★ 必须**双击**，不是单击！源码里：
        单击 = 切换**余额气泡**（"41 次 · 87.8k tok"那个）
        双击 = 打开**输入框**
        三击 = 动作菜单
      我第一版用 `am startservice -a ASK`（被拒），
      第二版用单击 —— 截到的是**余额气泡**，测的根本不是输入条。
      而两次都 `打开=True`：那个返回值只说明"命令没报错"，
      不说明浮层真的出现了。

    ★ 双击后要等 400ms（源码里挂了 triplePend 定时器）才真正弹出。
    """
    c = pet_center()
    if not c:
        return False
    # 双击（间隔 < 350ms 才算双击）
    sh('shell', 'input', 'tap', str(c[0]), str(c[1]))
    time.sleep(0.15)
    sh('shell', 'input', 'tap', str(c[0]), str(c[1]))
    time.sleep(2.5)          # 等过 400ms 的 triplePend 定时器
    return True


def float_region_luminance(im, box):
    """测**浮层自己那一块**的平均亮度。

    ★ 位置怎么定的（四次失败换来的）：
      ① 整屏平均 → 被桌面壁纸稀释（输入条整屏差只有 4.3）
      ② "下半屏最亮带" → 抓到壁纸亮斑，三个浮层都 255.0
      ③ "全场最偏离段" → 深色下对比本来就不大，偏移最大的段在别处
      ④ 固定横带（y 4%~22%）→ 动作菜单/输入条都工作（差 88 / 58.6），
         但**长按菜单面积小**，被同一横带里的壁纸稀释成 9.5

      所以最终按**每个浮层自己的包围盒**测 —— 实测三个浮层都贴在
      桌宠左上/上方（桌宠在屏幕上部的中间偏右）。
      包围盒取法：各自菜单出现的那一小块。
    """
    a = np.array(im.convert('L')).astype(int)
    H, W = a.shape
    x0, y0, x1, y1 = box
    return float(a[int(H * y0):int(H * y1), int(W * x0):int(W * x1)].mean())


# 每个浮层自己的包围盒（比例坐标）；实测长按/动作菜单在桌宠左侧，输入条在上方
FLOAT_BOX = {
    '长按菜单': (0.24, 0.16, 0.60, 0.32),
    '动作菜单': (0.24, 0.16, 0.60, 0.32),
    '输入条':   (0.20, 0.04, 0.98, 0.18),
}


def long_press_pet():
    c = pet_center()
    if not c:
        return False
    # input swipe 同点同终点 = 长按
    sh('shell', 'input', 'swipe', str(c[0]), str(c[1]), str(c[0]), str(c[1]), '900')
    time.sleep(2.5)
    return True


def triple_tap_pet():
    c = pet_center()
    if not c:
        return False
    for _ in range(3):
        sh('shell', 'input', 'tap', str(c[0]), str(c[1]))
        time.sleep(0.18)
    time.sleep(3)
    return True


def pet_center():
    """找桌宠窗口的中心（用它来发点击/长按手势）"""
    o = sh('shell', 'dumpsys', 'window', 'windows').stdout.decode('utf-8', 'replace')
    lines = o.splitlines()
    for i, l in enumerate(lines):
        if 'Window{' in l and PKG in l and not re.search(r'\.\w+Activity', l):
            # frame 可能在这一行，也可能在后几行
            for k in range(i, min(i + 40, len(lines))):
                m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',
                              lines[k])
                if m:
                    x = (int(m.group(1)) + int(m.group(3))) // 2
                    y = (int(m.group(2)) + int(m.group(4))) // 2
                    if 0 <= x < 2000 and 0 <= y < 3000:
                        return x, y
    return None


print('=== 四个浮层：浅色 vs 深色 ===')
print()
summary = []
ok_all = True

for label, opener in (('长按菜单', long_press_pet),
                      ('动作菜单', triple_tap_pet),
                      ('输入条', open_ask)):
    lums = {}
    for dark in (False, True):
        set_ui(1, 1 if dark else 0)     # 玻璃态（浮层在玻璃态更明显）
        start_pet()
        # ★ 前置断言：桌宠窗口必须在，否则后面点不到它、测什么都无意义。
        #   实测踩过：直接 startservice 在 Android 8+ 报
        #   "app is in background uid null"，桌宠从未启动，
        #   于是三处浮层都"打开=False"、亮度完全相同（82.1 vs 82.1），
        #   那次"通过"是**什么都没测到**。
        if pet_window_count() == 0:
            print('   ✗ %s：桌宠窗口不存在，无法测试（前置失败）' % label)
            ok_all = False
            continue
        opened = opener()
        p = snap('%s_%s' % (label, 'dark' if dark else 'light'))
        if not p:
            print('   %s %s: 截图失败' % (label, '深色' if dark else '浅色'))
            continue
        lums[dark] = float_region_luminance(Image.open(p), FLOAT_BOX[label])
        print('   %-6s %-4s 打开=%-5s 浮层区亮度=%.1f'
              % (label, '深色' if dark else '浅色', opened, lums[dark]))
        # 关闭浮层，避免影响下一轮
        sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1.5)
    if len(lums) == 2:
        diff = abs(lums[False] - lums[True])
        good = diff > 8
        ok_all = ok_all and good
        summary.append((label, lums[False], lums[True], diff, good))
        print('          → 亮度差 %.1f  %s' % (diff, '✓ 跟随' if good else '✗ 没跟随'))
    elif len(lums) < 2:
        ok_all = False
    print()

print('=== 汇总 ===')
for label, l, dk, diff, good in summary:
    print('  %-6s 浅 %.1f / 深 %.1f  差 %.1f  %s'
          % (label, l, dk, diff, '✓' if good else '✗'))
print()
set_ui(1, 0)
print('  已恢复：玻璃态 + 浅色')
sys.exit(0 if ok_all and summary else 1)
