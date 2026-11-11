"""在模拟器上驱动 App：选背景视频 + 采样背景色。

用 Python 而不是 PowerShell：本项目路径含中文，且这里要拼
`duration 00:12` 这类字符串，PowerShell 的反引号转义太容易出错
（已踩坑：`"...$tag`b.xml"` 里的 `` `b `` 被解释成退格，路径变成非法字符）。
"""
import re
import subprocess
import sys
import time
import numpy as np
from PIL import Image

# 控制台默认是 GBK，而页面文字里有「›」等字符，直接 print 会
# UnicodeEncodeError 中断脚本。强制 UTF-8 输出。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DUMP = r'D:\petport_lab\petbuild\build\_dump.xml'


def sh(*args):
    return subprocess.run([ADB] + list(args), capture_output=True)


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh('pull', '/sdcard/_d.xml', DUMP)
    return open(DUMP, encoding='utf-8').read()


def tap_text(needle, exact=False):
    """点第一个 text 含 needle 的节点。返回 True 表示点到了。"""
    x = dump()
    pat = r'text="%s%s%s"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    if exact:
        m = re.search(pat % ('', re.escape(needle), ''), x)
    else:
        m = re.search(pat % ('[^"]*', re.escape(needle), '[^"]*'), x)
    if not m:
        return False
    g = m.groups()
    cx, cy = (int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2
    sh('shell', 'input', 'tap', str(cx), str(cy))
    return True


def tap_desc(pattern):
    """点 content-desc 匹配 pattern 的节点（相册里的视频缩略图）。"""
    x = dump()
    m = re.search(r'content-desc="' + pattern + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if not m:
        return False
    g = m.groups()
    cx, cy = (int(g[0]) + int(g[2])) // 2, (int(g[1]) + int(g[3])) // 2
    sh('shell', 'input', 'tap', str(cx), str(cy))
    return True


def goto_appearance():
    sh('shell', 'input', 'tap', '180', '2290')      # 底部「形象」
    time.sleep(2)
    tap_text('外观与显示')
    time.sleep(3)


def pick_video(seconds):
    """走「更换背景视频」→ PhotoPicker → 选指定时长的片子。"""
    if not tap_text('更换背景视频'):
        return 'no-button'
    time.sleep(5)
    if tap_desc(r'[^"]*duration 00:%02d[^"]*' % seconds):
        time.sleep(9)
        return 'ok'
    x = dump()
    cands = re.findall(r'content-desc="([^"]*duration[^"]*)"', x)
    return 'not-found: %s' % cands


def probe(n=6, gap=1.0, tag='probe'):
    """采样背景带，输出 RGB 与彩度。"""
    print('== %s ==' % tag)
    for i in range(n):
        sh('shell', 'screencap', '-p', '/sdcard/_p.png')
        sh('pull', '/sdcard/_p.png', r'D:\petport_lab\petbuild\build\_p.png')
        a = np.array(Image.open(r'D:\petport_lab\petbuild\build\_p.png').convert('RGB')).astype(int)
        seg = a[150:460, 300:1060].reshape(-1, 3)
        sat = seg.max(axis=1) - seg.min(axis=1)
        keep = seg[sat >= np.percentile(sat, 70)]
        m = keep.mean(axis=0)
        print('  t=%4.1fs  RGB=(%3.0f,%3.0f,%3.0f)  彩度=%5.1f' % (
            i * gap, m[0], m[1], m[2], sat.mean()))
        if i < n - 1:
            time.sleep(gap)


if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'goto':
        goto_appearance()
    elif cmd == 'pick':
        print(pick_video(int(sys.argv[2])))
    elif cmd == 'probe':
        probe(int(sys.argv[2]) if len(sys.argv) > 2 else 6,
              float(sys.argv[3]) if len(sys.argv) > 3 else 1.0,
              sys.argv[4] if len(sys.argv) > 4 else 'probe')
    elif cmd == 'tap':
        print(tap_text(sys.argv[2], exact=(len(sys.argv) > 3 and sys.argv[3] == 'exact')))
    elif cmd == 'who':
        x = dump()
        print('--- 当前页面文字 ---')
        for t in dict.fromkeys(re.findall(r'text="([^"]{1,40})"', x)):
            print('  ' + t)
    elif cmd == 'descs':
        x = dump()
        print('--- content-desc ---')
        for d in dict.fromkeys(re.findall(r'content-desc="([^"]*)"', x)):
            if d:
                print('  ' + d)
    elif cmd == 'taprow':
        """按 y 坐标点整行中间（x 取 540）。用于「文字 + ›」的可点行。"""
        sh('shell', 'input', 'tap', '540', sys.argv[2])
        print('tapped row y=%s' % sys.argv[2])
    elif cmd == 'pickdur':
        """在当前（已打开的）相册里点指定时长的视频。"""
        sec = int(sys.argv[2])
        if tap_desc(r'[^"]*duration 00:%02d[^"]*' % sec):
            print('picked %02ds' % sec)
            time.sleep(9)
        else:
            x = dump()
            print('not found; have: %s' %
                  re.findall(r'duration (00:\d+)', x))
    elif cmd == 'rows':
        """列出所有可点行及其文字，便于按"整行"而不是按文字点。"""
        x = dump()
        for m in re.finditer(r'<node[^>]*?>', x):
            s = m.group(0)
            if 'clickable="true"' not in s:
                continue
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            t = re.search(r'text="([^"]*)"', s)
            c = re.search(r'class="([^"]*)"', s)
            if b:
                print('  %-14s %-26s %s' % (
                    (c.group(1).split('.')[-1] if c else '?'), b.group(0),
                    repr(t.group(1)) if t else ''))