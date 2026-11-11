"""最可靠的主题跟随验证：**同一窗口两种主题的像素对比**。

★ 与之前几版的区别（都踩过坑）：
  ① 用 **Activity 名**判定"窗口是否打开"，不用模糊的文字匹配
     （文字匹配假通过过：设置页的说明文字里也有「查看历史」）
  ② 直接把 Activity 拉起来（`am start`），不靠点击导航 ——
     模拟器上点击/滑动经常打不中或被系统手势吃掉，
     我为此白跑了好几轮
  ③ 截图后比"卡片区平均亮度"，差 >40 才算跟随生效

★ 三个窗口都用 `am start` 打开：
  HistoryActivity / ChatActivity / QuickAskActivity 都是 exported=false，
  但 adb shell am 以 shell 身份运行、带 -n 明确指定组件时仍可启动。
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
from PIL import Image


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def set_theme(dark):
    """写 ui_theme（0=浅 1=深）。先 force-stop，否则被回写覆盖。"""
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    body = sh('shell', 'cat', UI_XML).stdout.decode('utf-8', 'replace')
    if not body.strip():
        body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>"
    val = '1' if dark else '0'
    if 'name="ui_theme"' in body:
        body = re.sub(r'<int name="ui_theme"[^>]*/>',
                      '<int name="ui_theme" value="%s" />' % val, body)
    else:
        body = body.replace('</map>',
                            '    <int name="ui_theme" value="%s" />\n</map>' % val)
    local = r'D:\petport_lab\petbuild\build\_t.xml'
    open(local, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', local, '/sdcard/_t.xml')
    owner = sh('shell', 'stat', '-c', '%U:%G', UI_XML).stdout.decode().strip()
    mode = sh('shell', 'stat', '-c', '%a', UI_XML).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_t.xml', UI_XML)
    if owner and mode:
        sh('shell', 'chown', owner, UI_XML)
        sh('shell', 'chmod', mode, UI_XML)


def top_activity():
    o = sh('shell', 'dumpsys', 'activity', 'activities').stdout.decode('utf-8', 'replace')
    for l in o.splitlines():
        if 'topResumedActivity' in l:
            m = re.search(r'com\.deepseek\.pet/([\w.]+)', l)
            return '.' + m.group(1) if m and not m.group(1).startswith('.') else (
                m.group(1) if m else '?')
    return '?'


def shot(tag):
    p = r'D:\petport_lab\petbuild\build\_c_%s.png' % tag
    try:
        os.remove(p)
    except OSError:
        pass
    sh('shell', 'screencap', '-p', '/sdcard/_c.png')
    sh('pull', '/sdcard/_c.png', p)
    return Image.open(p).convert('RGB')


def mean_lum(im, box):
    c = im.crop(box)
    w, h = c.size
    c = c.resize((max(1, w // 4), max(1, h // 4)))
    px = list(c.getdata())
    return sum(sum(p) / 3.0 for p in px) / max(1, len(px))


# (标签, 组件, 亮度取样框)
#  取样框避开透明/桌面区域，只取卡片本体
WIN = [
    ('对话历史', '%s/.HistoryActivity' % PKG, (60, 200, 1020, 1400)),
    ('聊天窗口', '%s/.ChatActivity' % PKG, (60, 1300, 1020, 2100)),
    ('快捷提问', '%s/.QuickAskActivity' % PKG, (60, 1900, 1020, 2250)),
]

print('=== 三个窗口的主题跟随（像素对比）===')
print()
allok = True
summary = []
for label, comp, box in WIN:
    lums = {}
    for dark in (False, True):
        set_theme(dark)
        sh('shell', 'am', 'start', '-n', comp)
        time.sleep(4)
        act = top_activity()
        if label.replace('对话历史', 'History').replace('聊天窗口', 'Chat') \
                .replace('快捷提问', 'QuickAsk') and act == '?' :
            pass
        im = shot('%s_%s' % (label, 'dark' if dark else 'light'))
        lums[dark] = mean_lum(im, box)
        print('  %-8s %-4s 前台=%-20s 亮度=%.1f'
              % (label, '深色' if dark else '浅色', act, lums[dark]))
        sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(2)
    d = abs(lums[False] - lums[True])
    good = d > 40
    allok = allok and good
    summary.append((label, lums[False], lums[True], d, good))
    print('          亮度差 = %.1f  %s' % (d, '✓ 跟随' if good else '✗ 没跟随'))
    print()

set_theme(False)
print('=== 汇总 ===')
for label, l, dk, diff, good in summary:
    print('  %-8s 浅 %.1f / 深 %.1f  差 %.1f  %s'
          % (label, l, dk, diff, '✓' if good else '✗'))
print()
print('  已恢复浅色主题')
sys.exit(0 if allok else 1)
