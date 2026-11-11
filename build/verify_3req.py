"""验证用户的三条要求：
  1) 导入不做任何修改（逐字节一致）
  2) 不额外裁剪
  3) 完全按裁剪框截取（框内内容 1:1 铺满屏幕）

① 用 md5 + 字节数比对设备上的 files/app_bg_video.mp4 与源文件。
②③ 用 prefs 里的 crop 推"框覆盖的源区间"，与"屏幕可见源区间"比较。
"""
import hashlib
import re
import subprocess
import sys

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'
XML = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG
LOCAL = r'D:\petport_lab\petbuild\build\bands.mp4'
SW, SH = 1080, 2337          # 内容壳
sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


def pref(k):
    o = sh('shell', 'cat', XML).stdout.decode('utf-8', 'replace')
    m = re.search(r'name="' + k + r'"[^>]*?(?:value="([^"]*)"|>([^<]*)<)', o)
    return (m.group(1) if m and m.group(1) is not None
            else (m.group(2) if m else None))


print('=== ① 导入是否改了文件？ ===')
dev_path = '/data/data/%s/files/app_bg_video.mp4' % PKG
size = sh('shell', 'stat', '-c', '%s', dev_path).stdout.decode().strip()
md5 = sh('shell', 'md5sum', dev_path).stdout.decode().split()
try:
    import os
    lsize = os.path.getsize(LOCAL)
    lmd5 = hashlib.md5(open(LOCAL, 'rb').read()).hexdigest()
except OSError:
    lsize, lmd5 = -1, '（本地文件已清理）'
print('  设备: %s 字节  md5=%s' % (size, md5[0] if md5 else '?'))
print('  源文件: %s 字节  md5=%s' % (lsize, lmd5))
if lsize > 0:
    print('  ⇒ %s' % ('OK 逐字节一致，导入零改动'
                     if str(lsize) == size and lmd5 == (md5[0] if md5 else '')
                     else '!! 不一致（被转码/压缩过）'))
else:
    print('  （本地源文件已清理，无法比对；导入路径本身是纯字节流拷贝，见 saveVideoBg）')

print()
print('=== ②③ 框内内容是否 1:1 铺满 ===')
crop = [float(x) for x in (pref('app_video_bg_crop') or '0,0,1,1').split(',')]
fit = int(pref('app_video_bg_fit') or 0)
VW, VH = 1920, 1080
l, t, r, b = crop
cw, ch = (r - l) * VW, (b - t) * VH
s = max(SW / cw, SH / ch) if fit == 1 else min(SW / cw, SH / ch)
print('  crop = %s  fit = %d' % ([round(v, 4) for v in crop], fit))
print('  框覆盖源: %.1f x %.1f px' % (cw, ch))
print('  缩放 %.4f ⇒ 框内内容铺开后 %.1f x %.1f' % (s, cw * s, ch * s))
print('  屏幕: %d x %d' % (SW, SH))
ok = abs(cw * s - SW) < 2 and abs(ch * s - SH) < 2
print('  ⇒ %s' % ('OK 框内内容恰好铺满，无二次裁切、无黑边、无变形'
                 if ok else '!! 有溢出或留边'))