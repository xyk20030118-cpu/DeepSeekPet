"""核对：手机上装的包，是否包含"性能优化"这段新代码。

★ 起因：真机上「运行控制」页看不到「性能优化」板块。
  最可能的原因是**装的是旧构建** —— 正式版 APK 是在做性能优化
  **之前**编的（pwsh-63），我后来只重编了开发版，忘了重编正式版。
  这正是 STATE.md 里记过的"改了代码忘了重打包"那类事故。

判据：dex 里是否含新增的中文字符串（Java 字符串常量在 dex 里是 MUTF-8）。
"""
import io
import os
import re
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk, rel_apk  # noqa: E402

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = sys.argv[1] if len(sys.argv) > 1 else 'a94c3520'
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

NEEDLES = ['性能优化', '熄屏 / 遮挡时自动停止动画', 'DSPPerf', 'perf_mode']


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def dex_of(apk_path_or_bytes):
    if isinstance(apk_path_or_bytes, bytes):
        z = zipfile.ZipFile(io.BytesIO(apk_path_or_bytes))
    else:
        z = zipfile.ZipFile(apk_path_or_bytes)
    return z.read('classes.dex')


print('=== 磁盘上的 APK ===')
for p in (dev_apk(), rel_apk()):
    try:
        d = dex_of(p)
    except Exception as e:
        print('  %s: %s' % (p, e))
        continue
    hit = [n for n in NEEDLES if n.encode('utf-8') in d]
    print('  %-52s %d 字节  含: %s' % (p.split('\\')[-1], len(d), hit or '（无）'))

print()
print('=== 手机上的 APK ===')
for pkg in ('com.deepseek.pet', 'com.doropet.release'):
    out = sh('shell', 'pm', 'path', pkg).stdout.decode('utf-8', 'replace').strip()
    if ':' not in out:
        print('  %s: 未安装' % pkg)
        continue
    path = out.split(':', 1)[1].strip()
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'cat', path], capture_output=True)
    data = r.stdout
    if len(data) < 1000:
        print('  %s: 读不到（无 root？）%d 字节' % (pkg, len(data)))
        continue
    try:
        d = dex_of(data)
    except Exception as e:
        print('  %s: %s' % (pkg, e))
        continue
    hit = [n for n in NEEDLES if n.encode('utf-8') in d]
    print('  %-20s %d 字节  含: %s' % (pkg, len(d), hit or '（无 ← 是旧构建！）'))
