# -*- coding: utf-8 -*-
"""拖动桌宠的流畅度 A/B 测量（同一环境前后对比，不做绝对值引用）。

用法：
    python build\\perf_drag.py before
    python build\\perf_drag.py after

为什么这么测：
  ★ 本工程 STATE.md 里记过一条教训 —— **模拟器上的绝对 CPU% 不可信**
    （软渲染 + 宿主机内存压力），只有"同环境交替 A/B 的增量"才可信。
    所以这里不比绝对值，只比同一条脚本化拖动在改动前后的 `dumpsys gfxinfo` 数字。

判据取两处：
  ① `Janky frames: N (x%)`  —— 掉帧比例（主判据）
  ② `Total frames rendered` —— 同样时长内**画了多少帧**（越多说明越跟手）
"""
import io
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'
DEV = os.environ.get('DSP_DEV', '').strip() or 'emulator-5554'


def sh(*a):
    r = subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)
    return r.stdout.decode('utf-8', 'replace')


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else 'run'
    sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(6)
    # 关掉 API Key 对话框（没有 Key 时首启会弹），再回桌面让桌宠露出来
    x = sh('shell', 'uiautomator', 'dump', '/sdcard/_p.xml')
    xml = sh('shell', 'cat', '/sdcard/_p.xml')
    if '保存并开始' in xml and '陪伴' not in xml:
        m = re.search(r'text="sk-xxxxxxxx"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
        if m:
            cx = (int(m.group(1)) + int(m.group(3))) // 2
            cy = (int(m.group(2)) + int(m.group(4))) // 2
            sh('shell', 'input', 'tap', str(cx), str(cy))
            time.sleep(1)
            sh('shell', 'input', 'text', 'sk-perf-probe')
            time.sleep(1)
            x2 = sh('shell', 'cat', '/sdcard/_p.xml')
            m2 = re.search(r'text="保存并开始"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x2)
            if m2:
                sh('shell', 'input', 'tap',
                   str((int(m2.group(1)) + int(m2.group(3))) // 2),
                   str((int(m2.group(2)) + int(m2.group(4))) // 2))
                time.sleep(3)
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(4)

    size = sh('shell', 'wm', 'size')
    m = re.search(r'(\d+)x(\d+)', size)
    sw, shh = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    sh('shell', 'dumpsys', 'gfxinfo', PKG, 'reset')
    time.sleep(0.5)
    # 脚本化拖动：左右各来回两趟，每趟 1.2 秒（够密的事件流才能看出卡顿）
    y = int(shh * 0.55)
    for _ in range(2):
        sh('shell', 'input', 'swipe', str(int(sw * 0.30)), str(y),
           str(int(sw * 0.70)), str(y), '1200')
        sh('shell', 'input', 'swipe', str(int(sw * 0.70)), str(y),
           str(int(sw * 0.30)), str(y), '1200')
    time.sleep(1.5)
    info = sh('shell', 'dumpsys', 'gfxinfo', PKG)
    tot = re.search(r'Total frames rendered:\s*(\d+)', info)
    jank = re.search(r'Janky frames:\s*(\d+)\s*\(([\d.]+)%\)', info)
    p50 = re.search(r'50th percentile:\s*(\d+)ms', info)
    p90 = re.search(r'90th percentile:\s*(\d+)ms', info)
    p99 = re.search(r'99th percentile:\s*(\d+)ms', info)
    line = '[%s] 总帧=%s 掉帧=%s(%s%%) p50=%sms p90=%sms p99=%sms' % (
        tag,
        tot.group(1) if tot else '?',
        jank.group(1) if jank else '?',
        jank.group(2) if jank else '?',
        p50.group(1) if p50 else '?',
        p90.group(1) if p90 else '?',
        p99.group(1) if p99 else '?')
    print(line)
    io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         '_perf_drag_%s.txt' % tag), 'w', encoding='utf-8').write(line + '\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
