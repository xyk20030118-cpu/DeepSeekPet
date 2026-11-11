"""验证 APK 清单里的关键属性、以及 dex 里的关键类。

★ 这个脚本存在的理由（两个，都是踩过的坑）：

  ① `android:enableOnBackInvokedCallback` 是"跟随系统手势"的**开关**。
     它若缺失，系统不会把返回手势交给 App，而是默认 finish() ——
     正是用户报的"用返回手势直接退回桌面"。
     而它写在明文清单里、经 aapt2 编译成二进制 AXML，
     **任何一步出问题都不会报错**，只会静默失效。所以要守着。

  ② ★ 编码：AXML 字符串池用 **UTF-16LE**，不是 UTF-8。
     我第一版探针只搜 UTF-8，于是连 `allowBackup`、`extractNativeLibs`
     这些**一直在清单里**的属性都报"缺失"，还据此得出
     "属性没进 APK、要改构建流程"的错误结论。
     ⇒ 探针搜错编码会让你去改本来正确的代码。两种编码都必须查。
"""
import os
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk, rel_apk  # noqa: E402

APKS = [
    ('开发版', dev_apk()),
    ('正式版', rel_apk()),
]

# (期望位置, 名字)
#   清单 = 必须在二进制 AndroidManifest.xml 里
#   dex  = 必须在 classes.dex 里
NEEDLES = [
    ('清单', 'enableOnBackInvokedCallback'),   # 跟随系统手势的开关
    ('清单', 'allowBackup'),                   # 对照项：已知一直在
    ('清单', 'dsp.variant'),                   # 双版本标记
    # ★ 轮次56：用户自填的对话 API 可能是 http://（本机网关 / 局域网自建服务），
    #   而 targetSdk 34 起**系统默认禁止明文 HTTP** ⇒ 这两个属性缺一个，
    #   用户填了完全正确的局域网地址也只会拿到 "CLEARTEXT … not permitted"，
    #   而且**编译期不会报任何错**（正是本脚本要守的那类静默失效）。
    ('清单', 'networkSecurityConfig'),
    ('清单', 'usesCleartextTraffic'),
    ('dex', 'OnBackInvokedCallback'),          # API33 预测式返回
    ('dex', 'BackNav'),                        # 返回层级规则
    ('dex', 'navigateBack'),                   # 三入口共用的实现
]


def has(buf, name):
    """UTF-16LE 与 UTF-8 都查（AXML 用前者，故绝不能只查一种）"""
    if not buf:
        return False
    return (name.encode('utf-8') in buf) or (name.encode('utf-16-le') in buf)


bad = False
for label, path in APKS:
    print('=== %s ===' % label)
    if not os.path.exists(path):
        print('   不存在: %s' % path)
        bad = True
        continue
    with zipfile.ZipFile(path) as z:
        ax = z.read('AndroidManifest.xml')
        try:
            dex = z.read('classes.dex')
        except KeyError:
            dex = b''
    for where, n in NEEDLES:
        buf = ax if where == '清单' else dex
        hit = has(buf, n)
        if not hit:
            bad = True
        print('   [%s] %-30s %s' % (where, n, '✓' if hit else '✗ 缺失!'))
    print()

print('⇒ %s' % ('关键属性与类都在 ✓' if not bad else '!! 有缺失'))
sys.exit(1 if bad else 0)
