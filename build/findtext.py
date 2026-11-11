"""找一个文字节点的 bounds；也可以直接点它（`--tap`）。

诊断/精确点击用。★ 为什么要按 class 过滤：对话框里的**说明文字**也含
「应用」二字（"直接点「应用」即可"），只按文字匹配会点到那行不可点的
TextView，表现为"点了应用但没反应"。
"""
import re
import subprocess
import sys

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from drive import dump

needle = sys.argv[1]
cls_filter = None
do_tap = False
for a in sys.argv[2:]:
    if a == '--tap':
        do_tap = True
    elif a.startswith('--class='):
        cls_filter = a.split('=', 1)[1]

x = dump()
found = []
for m in re.finditer(r'<node[^>]*?>', x):
    s = m.group(0)

    def g(n):
        r = re.search(n + r'="([^"]*)"', s)
        return r.group(1) if r else ''

    if needle not in g('text'):
        continue
    cls = g('class').split('.')[-1]
    if cls_filter and cls_filter != cls:
        continue
    found.append((cls, g('bounds'), g('clickable')))
    print('%-24s %-26s clickable=%s class=%s' % (
        repr(g('text'))[:24], g('bounds'), g('clickable'), cls))

if do_tap and found:
    b = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', found[0][1])
    cx = (int(b.group(1)) + int(b.group(3))) // 2
    cy = (int(b.group(2)) + int(b.group(4))) // 2
    subprocess.run([r'D:\Android\Sdk\platform-tools\adb.exe',
                    'shell', 'input', 'tap', str(cx), str(cy)],
                   capture_output=True)
    print('已点 (%d, %d)' % (cx, cy))
elif do_tap:
    print('没找到可点的目标')