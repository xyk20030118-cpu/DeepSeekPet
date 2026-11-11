"""把当前页面的关键信息打出来：文字 + 可点元素坐标。

★ 用途：真机(1440x3200)和模拟器(1080x2400)尺寸不同，
  任何"按比例估算坐标"的做法都会漂。**只认 dump 里的实际 bounds**。

用法: python build/whereami.py [设备]
"""
import re
import subprocess
import sys

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = sys.argv[1] if len(sys.argv) > 1 else 'a94c3520'
sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


o = sh('shell', 'wm', 'size').stdout.decode('utf-8', 'replace')
print('屏幕: %s' % o.strip())

sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
sh('pull', '/sdcard/_d.xml', r'D:\petport_lab\petbuild\build\_phone.xml')
x = open(r'D:\petport_lab\petbuild\build\_phone.xml', encoding='utf-8', errors='replace').read()

print()
print('=== 页面文字 ===')
seen = []
for m in re.finditer(r'text="([^"]{1,60})"', x):
    t = m.group(1).strip()
    if t and t not in seen:
        seen.append(t)
print('  ' + ' | '.join(seen[:40]))

print()
print('=== 可点元素（文字 @ 中心坐标）===')
for m in re.finditer(r'<node[^>]*?>', x):
    s = m.group(0)
    if 'clickable="true"' not in s:
        continue
    t = re.search(r'text="([^"]*)"', s)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
    if not b:
        continue
    cx = (int(b.group(1)) + int(b.group(3))) // 2
    cy = (int(b.group(2)) + int(b.group(4))) // 2
    label = t.group(1).strip() if t else '(无文字)'
    if label or cx:
        print('  %-34s @ (%d, %d)  [%d,%d][%d,%d]'
              % (label[:32], cx, cy, int(b.group(1)), int(b.group(2)),
                 int(b.group(3)), int(b.group(4))))

print()
print('=== 是否含性能优化 ===')
for kw in ('性能优化', '高性能', '均衡', '省电', '自动停止动画'):
    print('  %-12s %s' % (kw, '✓' if kw in x else '✗'))
