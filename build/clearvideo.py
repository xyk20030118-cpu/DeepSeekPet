"""清掉视频背景的全部 pref（模拟"从没选过视频"），用于验证默认值。"""
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'

# 停掉 App（否则它退出时会把内存里的旧值写回去）
subprocess.run([ADB, 'shell', 'am', 'force-stop', PKG], capture_output=True)
subprocess.run([ADB, 'shell', 'rm', '-f',
                '/data/data/%s/files/app_bg_video.mp4' % PKG], capture_output=True)
# 用 sed 逐条删除（PowerShell 传引号容易坏，所以走 Python 拼好再交给 adb）
for key in ('app_video_bg', 'app_video_bg_fit', 'app_video_bg_crop',
            'app_video_bg_dim', 'app_video_bg_sound'):
    cmd = ("sed -i '/name=\"%s\"/d' /data/data/%s/shared_prefs/deepseek_pet.xml"
           % (key, PKG))
    subprocess.run([ADB, 'shell', cmd], capture_output=True)
print('已清除视频背景相关 pref 与文件')