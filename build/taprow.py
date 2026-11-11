"""定位「重新裁剪画面」这类可点行，并点它。

为什么单独写：这类行是"文字 + ›"结构，文字节点 clickable=false，
可点的是**父 LinearLayout**。而父的 bounds 需要通过子节点反查 ——
直接按估算的 y 点会打到别的行（已经错过两次）。

做法：先在 xml 里找含目标文字的节点，拿到它的 y 中心，
再在同一个 xml 里找"包含这个 y 且 clickable=true 的 LinearLayout"。
"""
import re
import subprocess
import sys
import time

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
from drive import dump


def tap_row_with_text(needle):
    x = dump()
    target_y = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        if t and needle in t.group(1):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if b:
                target_y = (int(b.group(2)) + int(b.group(4))) // 2
                break
    if target_y is None:
        print('找不到文字:', needle)
        return False

    # 找包含该 y 的可点容器（取最内层：bounds 最小的那个）
    best = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        if 'clickable="true"' not in s:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not b:
            continue
        y0, y1 = int(b.group(2)), int(b.group(4))
        if y0 <= target_y <= y1:
            area = (int(b.group(3)) - int(b.group(1))) * (y1 - y0)
            if best is None or area < best[0]:
                best = (area, y0, y1)

    if best is None:
        print('找不到可点容器（文字 y=%d）' % target_y)
        return False
    _, y0, y1 = best
    cy = (y0 + y1) // 2
    subprocess.run([ADB, 'shell', 'input', 'tap', '540', str(cy)], capture_output=True)
    print('点了 y=%d（行 %d..%d，文字在 %d）' % (cy, y0, y1, target_y))
    time.sleep(3)
    return True


if __name__ == '__main__':
    tap_row_with_text(sys.argv[1])