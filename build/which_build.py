"""对比"设备上实际安装的 APK"与"最新构建的 APK"的 dex md5。

为什么要这样查：这个功能的 bug 跨了很多轮，用户截图里的现象
可能来自**旧构建**（还没装上修复版）。先确认版本，
否则会在"已经是好的"代码上继续找不存在的问题。
"""
import hashlib
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


# 找安装路径
out = sh('shell', 'pm', 'path', PKG).stdout.decode('utf-8', 'replace').strip()
print('安装路径: %s' % out)
path = out.split(':', 1)[1].strip() if ':' in out else ''
if not path:
    print('取不到安装路径')
    sys.exit(1)

# 用 **exec-out** 取原始字节。
#
# ★ 不能用 `adb shell cat`：adb 的 shell 通道会做换行转换（CRLF），
#   把 597MB 的 zip 破坏掉，zipfile 报 "Bad magic number for central directory"。
#   exec-out 是二进制通道，不做任何转换。
r = subprocess.run([ADB, 'exec-out', 'cat', path], capture_output=True)
data = r.stdout
print('设备 APK 大小 = %d 字节' % len(data))
if len(data) < 1000:
    print('读不到内容（可能没 root）')
    sys.exit(1)

import io
z = zipfile.ZipFile(io.BytesIO(data))
dev_dex = hashlib.md5(z.read('classes.dex')).hexdigest()
print('设备 dex = %s' % dev_dex)

z2 = zipfile.ZipFile(r'D:\petport_lab\petbuild\dist\DeepSeekPet_v6.0_rebuilt.apk')
new_dex = hashlib.md5(z2.read('classes.dex')).hexdigest()
print('最新 dex = %s' % new_dex)
print()
print('⇒ %s' % ('设备已是最新构建' if dev_dex == new_dex
                else '!! 设备装的是**旧**构建 —— 截图里的现象可能已经修好了'))

# 顺便报告设备上 APK 里是否含新加的诊断 tag
try:
    dex_bytes = z.read('classes.dex')
    print()
    print('设备 dex 含 DSPCrop 诊断: %s' % (b'DSPCrop' in dex_bytes))
    print('设备 dex 含 DSPVideo 诊断: %s' % (b'DSPVideo' in dex_bytes))
except Exception as e:
    print(e)