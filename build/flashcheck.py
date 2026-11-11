"""录屏测"会重建的开关"有没有 alpha 闪。

「在气泡里配上表情包图片」这类开关**必须**重建（下级选项要显隐），
所以它一定会新建视图树。这里要量的是：重建过程里**有没有 alpha 淡入**
（那才是"闪"）。

判据：录 60fps，取内容区的逐帧亮度。若出现"先明显变暗再恢复"，
就是 alpha 闪；若只是某一帧内容换新（亮度基本连续），就没有闪。
"""
import glob
import os
import subprocess
import sys
import time
import numpy as np
from PIL import Image

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
FFMPEG = r'C:\ffmpeg\ffmpeg-7.1-essentials_build\bin\ffmpeg.exe'
OUT = r'D:\petport_lab\petbuild\build\_flash'
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


def run(x, y, label):
    for f in glob.glob(os.path.join(OUT, '*.png')):
        os.remove(f)
    os.makedirs(OUT, exist_ok=True)
    sh('shell', 'rm', '-f', '/sdcard/_fl.mp4')

    rec = subprocess.Popen(
        [ADB, 'shell', 'screenrecord', '--time-limit', '5',
         '--bit-rate', '12000000', '/sdcard/_fl.mp4'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1.5)
    sh('shell', 'input', 'tap', str(x), str(y))
    rec.wait(timeout=20)
    time.sleep(0.4)

    sh('pull', '/sdcard/_fl.mp4', r'D:\petport_lab\petbuild\build\_fl.mp4')
    subprocess.run([FFMPEG, '-y', '-i', r'D:\petport_lab\petbuild\build\_fl.mp4',
                    '-vf', 'fps=60', os.path.join(OUT, 'f%04d.png')],
                   capture_output=True)

    files = sorted(glob.glob(os.path.join(OUT, '*.png')))
    vals = []
    for p in files:
        a = np.array(Image.open(p).convert('RGB')).astype(int)
        vals.append((os.path.basename(p), a[350:2050].mean()))

    # 点按在录制开始 ~1.5s 后 = 第 90 帧附近
    win = vals[70:160]
    if not win:
        print('%s: 帧不足' % label)
        return
    cs = [v[1] for v in win]
    base = float(np.median(cs[:10]))
    mn = min(cs)
    print('%-26s 基线=%6.1f 最低=%6.1f 下探=%5.1f (%.1f%%)  -> %s' % (
        label, base, mn, base - mn, (base - mn) / base * 100,
        '有 alpha 闪' if (base - mn) > base * 0.06 else 'OK 无 alpha 闪'))


if __name__ == '__main__':
    run(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3])