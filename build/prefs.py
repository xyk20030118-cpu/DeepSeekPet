"""读设备上视频背景 / 皮肤相关的 pref（诊断用）。

★ 坑：本 App 的 prefs **分散在多个文件**里，不是一个：
    deepseek_pet.xml        —— 大部分设置（含 app_video_bg*）
    deepseek_pet_ui.xml     —— **皮肤与主题**（ui_skin / ui_theme）
    deepseek_pet_chat.xml   —— 聊天
    deepseek_pet_usage.xml  —— 用量
  一开始只读 deepseek_pet.xml，于是 ui_skin 永远显示"(未设置)"，
  让我误判成"用户没切到玻璃态、所以视频层没挂" —— 其实它好好的在另一个文件里。
  所以这里把目录下**所有** xml 都读一遍再查键。
"""
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DIR = '/data/data/com.deepseek.pet/shared_prefs'

out = subprocess.run([ADB, 'shell', 'cat', DIR + '/*.xml'], capture_output=True)
txt = out.stdout.decode('utf-8', 'replace')

KEYS = ('app_video_bg', 'app_video_bg_fit', 'app_video_bg_crop',
        'app_video_bg_dim', 'app_video_bg_sound', 'ui_skin', 'ui_theme')

for key in KEYS:
    # 精确匹配 name="key"（否则 app_video_bg 会匹配到 app_video_bg_fit）
    m = re.search(r'name="' + re.escape(key) + r'"[^>]*?(?:value="([^"]*)"|>([^<]*)<)', txt)
    if m:
        v = m.group(1) if m.group(1) is not None else m.group(2)
        print('  %-22s = %s' % (key, v))
    else:
        print('  %-22s = (未设置)' % key)