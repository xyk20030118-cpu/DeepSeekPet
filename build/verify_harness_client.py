# -*- coding: utf-8 -*-
"""第十七版：**工作模式 = 上游 Harness 本体**（删掉自造界面）的断言。

判据分三段：
  [A] 自造的工作台**确实被删掉**（文件不在了、清单注销了、源码里不再有自造文案）；
  [B] 新工作模式是**一层 WebView 外壳**：JS/DOM storage、只认同 host 跳转、地址与 token 落 pref；
  [C] 入口唯一：角色卡 → Harness 页（只有一处 startActivity），设置页那行也指向它。

★ 只读源码；真机表现由 AVD 截图与人工验收（docs/shots/13）。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
MANIFEST = io.open(os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8').read()
DSH = os.path.join(SRC, 'dsh')

ok = 0
fail = 0


def check(label, cond, detail=None):
    global ok, fail
    if cond:
        ok += 1
        print('  [OK]   %s%s' % (label, (' :: %s' % detail) if detail else ''))
    else:
        fail += 1
        print('  [FAIL] %s%s' % (label, (' :: %s' % detail) if detail else ''))


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只认活代码）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def read(path):
    try:
        return io.open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


HARNESS = read(os.path.join(SRC, 'DshHarnessActivity.java'))
FLOAT = read(os.path.join(SRC, 'FloatingService.java'))
ENTRY = read(os.path.join(SRC, 'DshModeEntry.java'))
MAIN = read(os.path.join(SRC, 'MainActivity.java'))
PROOT = read(os.path.join(SRC, 'dsh', 'linux', 'EngineProot.java'))
ENGINE = read(os.path.join(SRC, 'dsh', 'linux', 'Engine.java'))
FEATURE = read(os.path.join(DSH, 'DshFeature.java'))
MAN = read(os.path.join(ROOT, 'AndroidManifest.xml'))

# ==================== [A] 自造工作台已删除 ====================

print('=== [A] 自造的工作台确实被删掉 ===')
check('DshWorkActivity.java 不在了',
      not os.path.isfile(os.path.join(SRC, 'DshWorkActivity.java')))
check('DshSettingsActivity.java 不在了',
      not os.path.isfile(os.path.join(SRC, 'DshSettingsActivity.java')))
check('清单里没有 .DshWorkActivity', '.DshWorkActivity' not in MAN)
check('清单里没有 .DshSettingsActivity', '.DshSettingsActivity' not in MAN)
check('源码里不再有自造空态文案（探索未至之境）',
      '探索未至之境' not in read(os.path.join(SRC, 'DshHarnessActivity.java')))
check('源码里不再有自造的「＋ 新会话」工作台',
      '＋ 新会话' not in read(os.path.join(SRC, 'DshHarnessActivity.java')))

# ==================== [B] 新工作模式 = 一层 WebView 外壳 ====================

print()
print('=== [B] 新工作模式：WebView 装上游页面 ===')
check('DshHarnessActivity.java 存在', len(HARNESS) > 3000, '%d 字节' % len(HARNESS))
_har = MAN[MAN.find('.DshHarnessActivity'):MAN.find('.DshHarnessActivity') + 200]
check('清单里注册了 .DshHarnessActivity（exported=false）',
      'android:name=".DshHarnessActivity"' in MAN and 'android:exported="false"' in _har)
check('用 WebView（不是自绘界面）', 'new WebView(this)' in HARNESS)
check('JS 与 DOM storage 都开着（上游是 SPA）',
      'setJavaScriptEnabled(true)' in HARNESS and 'setDomStorageEnabled(true)' in HARNESS)
check('★ 只认同 host 内跳转，其余交给系统浏览器',
      'sameHost(url)' in HARNESS and 'Intent.ACTION_VIEW' in HARNESS)
check('★ 地址与 token 落 pref（重启还在）',
      'KEY_HARNESS_URL' in FEATURE and 'KEY_HARNESS_TOKEN' in FEATURE
      and 'putString(DshFeature.KEY_HARNESS_URL' in HARNESS
      and 'putString(DshFeature.KEY_HARNESS_TOKEN, tok)' in HARNESS)
check('token 从 URL 的 token= 参数里剥出来单独存',
      'raw.indexOf("token=")' in HARNESS and 'tok = raw.substring(i + 6);' in HARNESS)
check('默认地址是 adb reverse 的 127.0.0.1:43129',
      'return "http://127.0.0.1:43129/";' in HARNESS)
check('不弹 AlertDialog（项目硬约束）', 'AlertDialog' not in strip_comments(HARNESS))
check('离开工作模式时把桌宠还回去 / 工作时收起',
      'FloatingService.ACTION_HIDE_TEMP' in HARNESS and 'FloatingService.ACTION_SHOW_TEMP' in HARNESS)

# ==================== [C] 入口唯一 ====================

print()
print('=== [C] 入口：角色卡 → Harness 页 ===')
# ★ v8.0：入口不再是裸 startActivity —— 工作模式要**独立成任务**
#   （否则最近任务里没卡片、且会跟着主界面一起被移除）。
# ★ 用户要求：右上角那颗按钮**可拖动**，且位置要记住。
# ★ 用户要求：工作模式要能**请求访问公共目录**。
ALLFILES_OK = ('ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION' in MAIN
               and 'ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION' in MAIN)
check('★ 设置页有「访问所有文件」申请入口（并有保底跳转）', ALLFILES_OK)
check('★ 工作模式菜单里也有申请入口（没授权时才出现）',
      '允许访问所有文件' in HARNESS and 'storageOk()' in HARNESS
      and 'ensureStorage()' in HARNESS)
# ★ 三层文件访问：ADB（/data/local/tmp）+ root（/data、/system、/storage），且 root 可关
check('★ 访客里挂了 ADB / root 目录，且 root 走 su 包装',
      '/data/local/tmp:/mnt/tmp' in PROOT
      and '/mnt/data' in PROOT and '/mnt/storagevol' in PROOT
      and 'rootHostEnabled' in PROOT and '"su"' in PROOT)

# root 是可选项，但不能因为授权/启动失败把工作模式一起弄挂。
check('★ root 授权检测有超时、不缓存失败、必须 uid=0 且退出码为 0',
      'p.waitFor(ROOT_PROBE_TIMEOUT_MS' in PROOT
      and 'p.exitValue() == 0 && sb.indexOf("uid=0") >= 0' in PROOT
      and 'if (rootGranted) return true;' in PROOT
      and 'private static Boolean rootOk' not in PROOT)
check('★ root 失败后明确普通回退，不能复用特权 bind',
      'runArgvNonRoot' in PROOT and '!forceNonRoot &&' in PROOT
      and 'usedRoot ? suLaunch(cmd) : cmd' in PROOT
      and 'proot.runArgvNonRoot(c, argv, output)' in ENGINE
      and 'rootFallback = true' in ENGINE)
check('★ root 子进程由 su 回收；不开 root 就绝不申请 root',
      # ★ 真机教训（轮次140）：App 侧枚举看不到 root 进程（hidepid），
      #   所以 root 清理必须整段在 su 里做（killLeftoversViaSu）。
      'killLeftoversViaSu' in PROOT
      and 'DshBridge.rootHostEnabled(c) && rootUsable(c)' in PROOT
      and 'if (wasRoot && procContext != null && rootUsable(procContext))' in PROOT
      and 'killLeftoversViaSu(procContext);' in PROOT)
check('★ 切换 root 开关立即重启宿主（不是仍用旧身份）',
      'DshBridge.setRootHostEnabled(MainActivity.this, on);' in MAIN
      and 'DshEngine.restart(MainActivity.this);' in MAIN
      and 'stop(c);\n        return start(c);' in read(os.path.join(DSH, 'DshEngine.java')))

# ★ 用户要求（对齐参考 app）：Harness 要能**浏览手机公共目录**。
PHONEFS_OK = ('/storage/emulated/0:/sdcard' in PROOT
              and '/storage/emulated/0:/home/dsh/storage' in PROOT)
# ★ 用户要求：“每次都能打开”。三层保障必须在：
#   ① 复用已在跑的宿主（不抢端口）；② 被占先清孤儿再试；③ 失败自动重试。
PORTFIX_OK = ('tryReuseRunning' in ENGINE and 'HttpURLConnection' in ENGINE
              and 'canBind' in ENGINE and 'append(port)' in ENGINE)
check('★ 宿主端口：先复用 → 清孤儿重试 → 再重试（保证每次都能打开）', PORTFIX_OK)
# ★★ 用户报障：“端口不固定，每次都要重新配置模型”。
#   机理：WebView 的 localStorage 按「协议+域名+端口」隔离 ⇒ 端口一变，
#   网页里配好的模型/API Key 就整片消失。所以端口必须**固定 30800**。
check('★★ 端口固定 30800（不许 3090.. / 内核随机；保底才用 30801）',
      # ★ 只看活代码：注释里保留着"旧逻辑 3080→3090→…"的历史说明，
      #   直接查全文会把注释当成代码（假红，本轮踩过一次）。
      'PORT = 30800' in strip_comments(ENGINE)
      and 'PORT_ALT = 30801' in strip_comments(ENGINE)
      and '3090' not in strip_comments(ENGINE)
      and 'PORT_CANDIDATES' not in strip_comments(ENGINE))
check('★★ 保底端口只在清不掉时启用，且会被记住（不会来回漂）',
      # 锚点要认「记住的是保底端口」本身：只查 rememberPort 会被
      # rememberPort(PORT) 喂饱（反向项 O 当场抓到）。
      'pickFallbackPort' in ENGINE and 'rememberPort(PORT_ALT)' in ENGINE
      and '网页配置需重配一次，之后不会再变' in ENGINE)
check('★★ 30800 被占先清孤儿再试（清不掉才保底换端口）',
      # ★ 锚点必须唯一：`EngineProot.killLeftovers(c)` 在启动循环里还有一处，
      #   只查它会假绿（反向项 M 当场抓到）。这里认「赋给 int k 并紧跟那句中文字」。
      'int k = EngineProot.killLeftovers(c);' in ENGINE
      and '30800 被占，清掉残留 proot ' in ENGINE
      and '端口 30800/30801 都被其他程序占用' in ENGINE
      and '固定端口，网页配置不会再丢' in ENGINE)
# ★ 复用优先于杀进程：复用 ⇒ 端口不变 ⇒ 网页配置不丢。
check('★★ 复用读的是引擎自己那一格（以前读错键 ⇒ 永远命中不了）',
      'KEY_LAST_URL = "dsh_engine_last_url"' in ENGINE
      and 'getString(KEY_LAST_URL, "")' in ENGINE
      and 'getString("dsh_harness_url", "")' not in ENGINE
      and 'rememberUrl(url, token);' in ENGINE)

check('★ 手机存储同时挂在 /sdcard 与家目录 storage（官方选项开始从家目录浏览）',
      PHONEFS_OK)
check('★ 设置页有「手机文件访问」弹窗（含管理权限与复制路径）',
      '手机文件访问' in MAIN and '复制存储路径' in MAIN
      and '/storage/emulated/0' in MAIN)

check('★ 工作模式右上角按钮可拖动（并记住位置）',
      'setOnTouchListener' in HARNESS and 'dsh_gear_x' in HARNESS
      and 'dsh_gear_y' in HARNESS)
# ★ 用户要求：长按桌宠菜单里要有「进入工作模式」，且**开关关着就不显示**。
check('★ 长按菜单含「进入工作模式」且受总开关控制',
      '进入工作模式' in FLOAT
      and 'DshBridge.workEnabled(this)' in FLOAT
      and FLOAT.find('进入工作模式') < FLOAT.find('if (canWork) list.add'))

# ★ v8.0：用户报“长按进工作模式会开好几个后台”。现在改成
#   **单实例 + 单任务**：清单 launchMode=singleTask + 独立 taskAffinity，
#   启动只用 NEW_TASK|SINGLE_TOP（**不再** NEW_DOCUMENT/MULTIPLE_TASK，否则每点一次多一张卡片）。
check('★ 入口指向 Harness 页，且只维护一个实例/一个后台任务',
      'new Intent(a, DshHarnessActivity.class)' in ENTRY
      and 'FLAG_ACTIVITY_SINGLE_TOP' in ENTRY
      and 'FLAG_ACTIVITY_NEW_DOCUMENT' not in ENTRY
      and 'android:launchMode="singleTask"' in MANIFEST
      and 'com.deepseek.pet.work' in MANIFEST)
check('★ 整个入口文件只有一处 startActivity（动画播完才跳）',
      ENTRY.count('startActivity(') == 1)
check('设置页那一行也指向 Harness',
      'startActivity(new Intent(MainActivity.this, DshHarnessActivity.class));' in MAIN)
check('设置行文案不再是「工作模式设置」自造页',
      '"🔗 Harness 地址 / 配对 ›"' in MAIN)

# ==================== [D] 本地下载 / 配置 ====================

print()
print('=== [D] 本地下载安装（不依赖电脑） ===')
INS = read(os.path.join(DSH, 'DshHarnessInstaller.java'))
check('DshHarnessInstaller.java 存在', len(INS) > 4000, '%d 字节' % len(INS))
check('★ 下载后先校验 sha256 再解压',
      'String got = sha256(zip);' in INS and '!got.equalsIgnoreCase(it.sha256)' in INS)
check('★ 解压挡路径穿越（../ 与绝对路径都拒）',
      "name.indexOf(\"..\") >= 0" in INS and "name.startsWith(\"/\")" in INS
      and '压缩包越界' in INS)
check('装完必须能找到上游 CLI（bin.js）',
      'if (!cliEntry().isFile())' in INS)
check('配置：写 DSH_HOME 的 settings.yaml（服务商/模型）',
      'public void writeSettings(' in INS and 'settings.yaml' in INS and 'agent-default-model' in INS)
check('装好的标记落盘（版本号可回读）',
      'installed.json' in INS and 'public String installedVersion()' in INS)
check('界面有「下载并配置」按钮与状态行',
      '下载并配置 Harness 包树' in HARNESS and 'installStatus' in HARNESS
      and 'new DshHarnessInstaller(getFilesDir())' in HARNESS)
check('下载在后台线程（不卡 UI）', '"harness-install"' in HARNESS)
check('★ 界面上明说：Node 运行时随 APK 走、包树才下载',
      'Node 运行时随 APK 走' in HARNESS)
check('电脑端打包脚本存在（manifest + 两个 zip + sha256）',
      os.path.isfile(os.path.join(ROOT, 'tools', 'pack_harness_bundle.py')))

print()
if fail == 0:
    print('Harness 客户端断言通过（%d 项）' % ok)
    sys.exit(0)
print('Harness 客户端断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
