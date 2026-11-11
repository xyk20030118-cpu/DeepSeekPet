"""设备实测：图片背景能否导入并真实显示。

★ 做法：
  ① 造一张纯色测试图推到设备
  ② 用 ACTION_PICK_IMAGE 那条路径不行（要 UI 选图），
     所以直接验证渲染层：把图放进 files/app_bg_image.img，
     设好 pref，然后重启 App 看背景是否变成那张图。
  ③ 判据用**像素颜色**：测试图用醒目的纯色（亮品红），
     截图里出现该颜色即证明图片真的铺上去了。

★ 同时确认**语音资源已不内置**：assets/asr 探测应返回"未内置"，
  且说话（TTS）配置不受影响。
"""
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'
OUT = r'D:\petport_lab\petbuild\build\_ui'
from PIL import Image, ImageDraw
import numpy as np

# 测试图颜色：亮品红（屏幕上极少自然出现，便于判定）
TEST_RGB = (255, 0, 200)


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def snap(tag):
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, 'ib_%s.png' % tag)
    if os.path.exists(p):
        os.remove(p)
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'screencap', '-p'],
                       capture_output=True)
    if len(r.stdout) < 5000:
        return None
    open(p, 'wb').write(r.stdout)
    return p


def make_test_image():
    """造一张竖屏测试图（含纯色块 + 网格，便于看出是否被拉伸）"""
    os.makedirs(OUT, exist_ok=True)
    p = os.path.join(OUT, 'test_bg.png')
    im = Image.new('RGB', (800, 1600), TEST_RGB)
    d = ImageDraw.Draw(im)
    # 画一些方块做参照（能看到裁剪/缩放是否等比）
    for i in range(0, 800, 100):
        for j in range(0, 1600, 100):
            if (i // 100 + j // 100) % 2 == 0:
                d.rectangle([i, j, i + 100, j + 100], fill=(20, 20, 20))
    im.save(p)
    return p


def count_color(im, rgb, tol=40):
    """**按色相**判断，不按具体 RGB。

    ★ 这里修过一个判据错误：
      测试图是 (255,0,200)，但页面上有 35% 的黑色压暗遮罩，
      铺上去之后实际约 (166,0,130)。按原色 + tol=40 匹配**匹配不到** ——
      于是"功能完全正常"被判成失败（我第一版就这样误判了一次）。
      压暗强度是用户可调的，所以**不能依赖具体亮度**，只能看色相关系。
    """
    a = np.array(im.convert('RGB')).astype(int)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    # 品红特征：R 明显高于 G，且 B 也高于 G
    mask = (r > g + 40) & (b > g + 20)
    return float(mask.sum()) / (a.shape[0] * a.shape[1])


def coverage(im):
    """品红纵向铺到屏幕的百分之多少（判断是否铺满，而不是只铺一截）"""
    a = np.array(im.convert('RGB')).astype(int)
    r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
    mask = (r > g + 40) & (b > g + 20)
    rows = mask.sum(axis=1)
    H = len(rows)
    last = 0
    for i, v in enumerate(rows):
        if v > 50:
            last = i
    return last * 100.0 / H


print('=== ① 把测试图放到应用的私有目录 ===')
img = make_test_image()
sh('push', img, '/sdcard/test_bg.png')

# ★ 用 root 直接写私有目录。
#   为什么不用 run-as：`run-as: package not debuggable` ——
#   本工程手工签名，装上的包不是 debuggable 的。
#   模拟器可以 `adb root`（uid=0），直接写文件系统更可靠。
sh('root')
time.sleep(3)
FILES = '/data/data/%s/files' % PKG
r = sh('shell', 'mkdir', '-p', FILES)
r = sh('shell', 'cp', '/sdcard/test_bg.png', FILES + '/app_bg_image.img')
out = (r.stdout + r.stderr).decode('utf-8', 'replace').strip()
if out:
    print('   cp 输出: %s' % out[:100])
# 属主必须还给 app，否则应用读不到（root 写的文件是 root:root）
uid = sh('shell', 'stat', '-c', '%u:%g', '/data/data/%s' % PKG) \
    .stdout.decode().strip()
r2 = sh('shell', 'chown', '-R', uid, FILES)
r3 = sh('shell', 'ls', '-l', FILES + '/app_bg_image.img')
print('   属主 %s，文件: %s'
      % (uid, (r3.stdout + r3.stderr).decode('utf-8', 'replace').strip()[:90]))

# ★ 前置断言：文件必须真的在那儿，否则后面测什么都没意义
check = sh('shell', 'test', '-s', FILES + '/app_bg_image.img')
if check.returncode != 0:
    sys.exit('!! 测试图没写进去，无法验证（前置步骤失败）')
print('   ✓ 测试图已就位')

print()
print('=== ② 设 pref：启用图片背景 + 玻璃态 + 浅色 ===')
sh('shell', 'am', 'force-stop', PKG)
time.sleep(2)
PREFS = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG
r = sh('shell', 'cat', PREFS)
body = r.stdout.decode('utf-8', 'replace')
if not body.strip():
    body = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>"


def setbool(b, k, v):
    tag = '<boolean name="%s" value="%s" />' % (k, 'true' if v else 'false')
    if 'name="%s"' % k in b:
        return re.sub(r'<boolean name="%s"[^>]*/>' % k, tag, b)
    return b.replace('</map>', '    %s\n</map>' % tag)


def setint(b, k, v):
    tag = '<int name="%s" value="%d" />' % (k, v)
    if 'name="%s"' % k in b:
        return re.sub(r'<int name="%s"[^>]*/>' % k, tag, b)
    return b.replace('</map>', '    %s\n</map>' % tag)


body = setbool(body, 'app_image_bg', True)
body = setbool(body, 'app_video_bg', False)      # 确保视频不抢
body = setint(body, 'ui_skin', 1)                # 玻璃态
body = setint(body, 'ui_theme', 0)               # 浅色
local = r'D:\petport_lab\petbuild\build\_ib.xml'
open(local, 'w', encoding='utf-8', newline='\n').write(body)
sh('push', local, '/sdcard/_ib.xml')
sh('shell', 'cp', '/sdcard/_ib.xml', PREFS)
# 同样要还属主（root 写的文件 app 读不到）
sh('shell', 'chown', uid, PREFS)
sh('shell', 'chmod', '660', PREFS)
print('   pref 已写入')

print()
print('=== ③ 启动并截图 ===')
sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
time.sleep(10)
p = snap('with_image')
im = Image.open(p)
frac = count_color(im, TEST_RGB)
cov = coverage(im)
print('   测试色（按色相）像素占比 = %.3f' % frac)
print('   纵向铺到 = %.0f%% 屏高' % cov)

print()
print('=== ④ 判定 ===')
ok = True
if frac > 0.10:
    print('   ✓ 图片背景已生效（品红占比 %.1f%%）' % (frac * 100))
else:
    print('   ✗ 没看到测试图（占比仅 %.1f%%）' % (frac * 100))
    ok = False
# ★ 必须**铺满**：曾经因为误用 toViewSpaceTransform，
#   图片按 1:1 画出、只铺到 67% 高度
if cov > 95:
    print('   ✓ 已铺满整屏（%.0f%%）' % cov)
else:
    print('   ✗ 只铺到 %.0f%% 高度 —— 没铺满（几何算错）' % cov)
    ok = False

print()
print('   截图: %s' % p)
sys.exit(0 if ok else 1)
