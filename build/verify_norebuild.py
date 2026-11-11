"""最终判定：点开关是否让**内容区**重建。

避开两个干扰源（都用实测数据确认过）：
  - 视频背景：它让**任何**区域持续变化（静置也变），所以底栏/背景带
    不能当判据。当前验证环境已关掉视频背景。
  - Toast：每次点开关都弹，位置随文案变，固定屏蔽不干净。

因此只比**内容区里、远离开关本体**的那部分（y 350..2050，x 0..800），
并且比较"点两次回到原状态"的前后（Toast 已消失）。
"""
import re
import subprocess
import sys
import time
import numpy as np
from PIL import Image

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


_seq = [0]


def shot(tag):
    # 文件名必须唯一：以前用固定名 _f_t1/_f_t2，stable() 内部循环会互相
    # 覆盖，导致比到的是别的开关留下的旧文件（表现为反复出现同一个
    # 23.703 的假差异）。
    _seq[0] += 1
    p = r'D:\petport_lab\petbuild\build\_f_%s_%03d.png' % (tag, _seq[0])
    sh('shell', 'screencap', '-p', '/sdcard/_f.png')
    sh('pull', '/sdcard/_f.png', p)
    return np.array(Image.open(p).convert('RGB')).astype(int)


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/_f.xml')
    sh('pull', '/sdcard/_f.xml', r'D:\petport_lab\petbuild\build\_f.xml')
    return open(r'D:\petport_lab\petbuild\build\_f.xml', encoding='utf-8').read()


def find_y(hint):
    x = dump()
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        if not t or hint not in t.group(1):
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if b:
            return (int(b.group(2)) + int(b.group(4))) // 2
    return None


# 判据区域：内容区左侧（避开开关本体的 x>820），且避开底栏
REGION = (slice(350, 2050), slice(0, 800))


def stable(tag, tries=6):
    """等到画面稳定（连续两帧几乎相同）再返回，避开动画/Toast 尾巴。"""
    prev = shot(tag)
    for _ in range(tries):
        time.sleep(0.6)
        cur = shot(tag)
        if np.abs(prev[REGION] - cur[REGION]).mean() < 0.15:
            return cur
        prev = cur
    return prev


def check(hint, sw_x=914):
    y = find_y(hint)
    if y is None:
        print('  %-20s 找不到' % hint)
        return
    a0 = stable('t0')
    sh('shell', 'input', 'tap', str(sw_x), str(y))
    time.sleep(1.0)
    a1 = stable('t1')
    sh('shell', 'input', 'tap', str(sw_x), str(y))
    time.sleep(1.0)
    a2 = stable('t2')

    d01 = float(np.abs(a0[REGION] - a1[REGION]).mean())
    d02 = float(np.abs(a0[REGION] - a2[REGION]).mean())
    print('  %-20s 翻转态与原始态差=%6.3f  复原后差=%6.3f  %s' % (
        hint, d01, d02,
        'OK 未重建' if d02 < 0.5 else '!! 内容变了'))


if __name__ == '__main__':
    for h in sys.argv[1:]:
        check(h)