"""底栏/页面导航 helper —— 供其他脚本复用。

★ 教训：PowerShell 里内联 Python 总会因引号转义出错（已经踩了四五次）。
  任何超过两行的 Python 都写进文件。
"""
import re
import subprocess
import sys

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'


def sh(dev, *a):
    return subprocess.run([ADB, '-s', dev] + list(a), capture_output=True)


def dump(dev, out=r'D:\petport_lab\petbuild\build\_nav.xml'):
    sh(dev, 'shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh(dev, 'pull', '/sdcard/_d.xml', out)
    try:
        return open(out, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


def nodes(x):
    """产出每个 node 的 (text, clickable, x0,y0,x1,y1)"""
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', s)
        if not b:
            continue
        yield (t.group(1) if t else '',
               'clickable="true"' in s,
               int(b.group(1)), int(b.group(2)), int(b.group(3)), int(b.group(4)))


def screen(dev):
    o = sh(dev, 'shell', 'wm', 'size').stdout.decode('utf-8', 'replace')
    m = re.search(r'(\d+)x(\d+)', o)
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def tap_text(dev, needle):
    """点含该文字的最小可点祖先"""
    x = dump(dev)
    ty = None
    for txt, ck, x0, y0, x1, y1 in nodes(x):
        if needle in txt:
            ty = (y0 + y1) // 2
            break
    if ty is None:
        return False
    best = None
    for txt, ck, x0, y0, x1, y1 in nodes(x):
        if not ck:
            continue
        if y0 <= ty <= y1:
            area = (x1 - x0) * (y1 - y0)
            if best is None or area < best[0]:
                best = (area, (x0 + x1) // 2, (y0 + y1) // 2)
    if not best:
        return False
    sh(dev, 'shell', 'input', 'tap', str(best[1]), str(best[2]))
    return True


if __name__ == '__main__':
    dev = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
    x = dump(dev)
    W, H = screen(dev)
    print('屏幕 %dx%d' % (W, H))
    print()
    print('=== 底部区域（y > 70%% 高度）的可点元素 ===')
    for txt, ck, x0, y0, x1, y1 in nodes(x):
        if ck and y0 > H * 0.70:
            print('   text=%-16s [%d,%d][%d,%d]' % (txt[:16], x0, y0, x1, y1))
