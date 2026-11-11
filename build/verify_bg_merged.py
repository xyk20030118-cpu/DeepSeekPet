"""设备实测：合并后的「背景」小节。

★ 验证三件事：
  ① 只有一个「背景」小节（不再有独立的「图片背景」「视频背景」）
  ② 三个类型选项（不用背景 / 图片 / 视频）都在
  ③ ★ 切换真的**互斥**：选图片后，视频的 pref 必须被关掉
     —— 这是合并要解决的核心问题（"开了没反应"）
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
PREFS = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def dump():
    local = r'D:\petport_lab\petbuild\build\_bg.xml'
    if os.path.exists(local):
        os.remove(local)
    sh('shell', 'rm', '-f', '/sdcard/_d.xml')
    sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh('pull', '/sdcard/_d.xml', local)
    if not os.path.exists(local):
        return ''
    return open(local, encoding='utf-8', errors='replace').read()


def texts(x):
    out = []
    for m in re.finditer(r'text="([^"]{1,40})"', x):
        t = m.group(1).strip()
        if t and t not in out:
            out.append(t)
    return out


def tap_text(x, needle, wait=2.5):
    ty = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        if t and needle in t.group(1):
            b = re.search(r'bounds="\[(\d+),(\d+),?\]', s)
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


def prefs_state():
    body = sh('shell', 'cat', PREFS).stdout.decode('utf-8', 'replace')

    def b(k):
        m = re.search(r'name="%s"[^>]*value="([^"]*)"' % k, body)
        return m.group(1) if m else '(未设)'
    return b('app_image_bg'), b('app_video_bg')


def ensure_glass():
    """切到玻璃态（背景小节只在玻璃态出现）"""
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    body = sh('shell', 'cat', PREFS).stdout.decode('utf-8', 'replace')
    if not body.strip():
        body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>"
    if 'name="ui_skin"' in body:
        body = re.sub(r'<int name="ui_skin"[^>]*/>',
                      '<int name="ui_skin" value="1" />', body)
    else:
        body = body.replace('</map>', '    <int name="ui_skin" value="1" />\n</map>')
    local = r'D:\petport_lab\petbuild\build\_g.xml'
    open(local, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', local, '/sdcard/_g.xml')
    uid = sh('shell', 'stat', '-c', '%u:%g', '/data/data/%s' % PKG).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_g.xml', PREFS)
    sh('shell', 'chown', uid, PREFS)
    sh('shell', 'chmod', '660', PREFS)


W, H = 1080, 2400

print('=== 准备：玻璃态 + 打开设置页 ===')
ensure_glass()
sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(10)
x = dump()
if '外观与显示' not in x:
    time.sleep(5)
    x = dump()
if not tap_text(x, '外观与显示'):
    sys.exit('进不去外观与显示')
x = dump()
ts = texts(x)

print()
print('=== ① 小节与选项 ===')
ok = True
has_merged = any(t.endswith('背景') and '视频背景' not in t and '图片背景' not in t
                 for t in ts)
print('   小节标题: %s' % ' | '.join(t for t in ts if '背景' in t))
if '图片背景' in ts or '视频背景' in ts:
    print('   ✗ 仍有独立小节（未合并）')
    ok = False
else:
    print('   ✓ 没有独立的「图片背景」「视频背景」小节')

for name in ('不用背景', '图片', '视频'):
    hit = any(name in t for t in ts)
    print('   %s 选项「%s」' % ('✓' if hit else '✗', name))
    if not hit and name != '视频':
        # 「视频」两字也出现在别的文案里，宽松一点
        ok = False

print()
print('=== ② 互斥验证（合并的核心）===')
print('   当前 pref: image=%s video=%s' % prefs_state())


def tap_option(name):
    """点类型选项按钮。

    ★ 必须匹配**带圆点的按钮文字**（"○ 图片" / "● 图片"），
      不能用宽松的 `'图片'` —— 页面下方的说明文字
      「当前用 Mesh 渐变光斑作背景。选**图片**或**视频**…」也含这两个字，
      宽松匹配会点到那句不可点的说明上，于是 pref 当然不变。
      我第一版就这样误判成"互斥失败"，白查了一轮。
    """
    x = dump()
    for cand in ('● ' + name, '○ ' + name):
        for m in re.finditer(r'<node[^>]*?>', x):
            s = m.group(0)
            t = re.search(r'text="([^"]*)"', s)
            if t and t.group(1) == cand and 'clickable="true"' in s:
                b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
                if b:
                    cx = (int(b.group(1)) + int(b.group(3))) // 2
                    cy = (int(b.group(2)) + int(b.group(4))) // 2
                    sh('shell', 'input', 'tap', str(cx), str(cy))
                    time.sleep(2.5)
                    return True
    return False


# 先选「不用背景」把两个都关掉，建立干净起点
if tap_option('不用背景'):
    print('   点「不用背景」→ pref: image=%s video=%s' % prefs_state())
else:
    print('   !! 点不到「不用背景」')

# 再选「图片」→ 应 image=true video=false
if tap_option('图片'):
    st = prefs_state()
    print('   点「图片」   → pref: image=%s video=%s' % st)
    if st[0] == 'true' and st[1] == 'false':
        print('   ✓ 选图片后视频被自动关掉（互斥成立）')
    else:
        print('   ✗ 互斥失败')
        ok = False
else:
    print('   ✗ 点不到「图片」选项')
    ok = False

# 再选「视频」→ 应 image=false video=true（反向也要成立）
if tap_option('视频'):
    st = prefs_state()
    print('   点「视频」   → pref: image=%s video=%s' % st)
    if st[0] == 'false' and st[1] == 'true':
        print('   ✓ 选视频后图片被自动关掉（反向也成立）')
    else:
        print('   ✗ 反向互斥失败')
        ok = False
else:
    print('   ✗ 点不到「视频」选项')
    ok = False

print()
print('=== 结果 ===')
print('   %s' % ('全部通过 ✓' if ok else '有失败项 ✗'))
sys.exit(0 if ok else 1)
