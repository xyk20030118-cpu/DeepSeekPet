# -*- coding: utf-8 -*-
"""双轨的断言：主 App（targetSdk 34）+ 「DSH Linux 引擎」独立包（targetSdk 28）。

为什么要这一套：Android 10+ 只允许 targetSdk≤28 的包 exec 自己目录里的程序，
而 proot 必须这么做（实测三条对照见 docs/第二十版-proot落地-W^X实测.md）。
于是「完整 Linux」被隔到一个独立小包，主 App 通过回环 HTTP 问它要地址。

判据分四段：
  [A] 引擎包本身：targetSdk 必须是 28、载荷三步、patch 必须是 []、**不许出现 PROOT_NO_SECCOMP**；
  [B] 控制接口：只绑回环、只 GET /status、不提供任何写操作；
  [C] 主 App 接线：四级优先（远端 → 引擎 → 原生 → 提示）、running+url 才算成功、失败必须回落；
  [D] 打包与边界：两个包的产物都在、包可见性写在清单里、已知限制写进文档。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r'D:\petport_lab\petbuild'
ENG = r'D:\petport_lab\petengine'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
# ★ v8.1 起引擎源码已并进主 App（`DshEngine` 的注释写明了），真正跑的是这一份；
#   `D:\petport_lab\petengine` 只是迁移前的遗留树，判据不能只盯它（否则断言对着死代码）。
APP_ENGINE = os.path.join(DSH, 'linux', 'Engine.java')

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
    """去掉 // 与 /* */ 注释：判据只认活代码（注释里提到某个开关不算"用了它"）。"""
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


def engine_stop_body(text):
    """粗取 Engine.stop() 的方法体（用于验证"停的时候有没有收尸"）。"""
    i = text.find('synchronized void stop()')
    if i < 0:
        return ''
    j = text.find('{', i)
    k = text.find('\n    }', j)
    return text[j:k] if j >= 0 and k > j else ''

def method_body(text, signature):
    """粗取一个方法体：从签名后的第一个 { 到下一个缩进四格的 }。

    ★ 为什么需要它：判据如果只看 whole file 里"有没有 startLocalHost();"，
    别处（按钮、另一条分支）的同名调用会把这条判据喂饱 —— 反向项 E 当场抓到了这个假绿。
    """
    i = text.find(signature)
    if i < 0:
        return ''
    j = text.find('{', i)
    k = text.find('\n    }', j)
    return text[j:k] if j >= 0 and k > j else ''


EMAN = read(os.path.join(ENG, 'AndroidManifest.xml'))
ENGINE = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'Engine.java'))
EPROOT = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'EngineProot.java'))
CTRL = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'ControlServer.java'))
ESVC = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'EngineService.java'))

DENG = io.open(os.path.join(DSH, 'DshEngine.java'), encoding='utf-8', errors='replace').read() \
    if os.path.isfile(os.path.join(DSH, 'DshEngine.java')) else ''
ACT = read(os.path.join(SRC, 'DshHarnessActivity.java'))
NODE = read(os.path.join(DSH, 'DshNodeProcess.java'))
FALLBACK = method_body(ACT, 'private void fallBack(')
MAN = read(os.path.join(ROOT, 'AndroidManifest.xml'))
DOC = (read(r'D:\用户\DS\DSH移植\docs\第二十版-proot落地-W^X实测.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十二版-载荷下载器.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十三版-宿主常驻与三处真bug.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十四版-并存端口与开机自启.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十五版-收尾清理与只跑一条.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十六版-arm64验收一条命令.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十七版-等待可取消与arm64硬件阻塞.md')
       + read(r'D:\用户\DS\DSH移植\docs\第二十八版-arm64真机验收.md'))

# ==================== [A] 引擎包 ====================

print('=== [A] 引擎包（独立 App）：targetSdk 28 是硬前提 ===')
check('引擎源码在 D:\\petport_lab\\petengine', os.path.isdir(ENG))
check('★ targetSdkVersion 必须是 28（29+ 一律 execve 被拒，实测过）',
      'android:targetSdkVersion="28"' in EMAN)
check('包名是 com.deepseek.pet.linux', 'package="com.deepseek.pet.linux"' in EMAN)
check('只有一个 Activity + 一个 exported 的 EngineService',
      EMAN.count('<activity') == 1 and '<service' in EMAN and 'android:exported="true"' in EMAN)
check('lib/ 里带了 proot 本体与 loader（两个 ABI）',
      all(os.path.isfile(os.path.join(ENG, 'lib', a, n))
          for a in ('x86_64', 'arm64-v8a') for n in ('libproot.so', 'libproot-loader.so')))
check('assets/prootlibs 两套 ABI 都带 libtalloc / libandroid-shmem',
      all(os.path.isfile(os.path.join(ENG, 'assets', 'prootlibs', a, n))
          for a in ('x86_64', 'arm64-v8a')
          for n in ('libtalloc.so.2', 'libandroid-shmem.so')))

print()
print('=== [A2] 引擎的安装与启动：三步载荷 + patch 必须是 [] ===')
check('★ 先解 Ubuntu 根，再解 Node，再解上游包树（顺序有依赖）',
      ENGINE.index('ubuntu-base.tar.gz') < ENGINE.index('node-linux-')
      and ENGINE.index('node-linux-') < ENGINE.index('harness-linux.tgz'))
check('装完必须真的能找到 /opt/node/bin/node 与上游 bin.js（不靠"解完就当成功"）',
      '/opt/node/bin/node' in ENGINE and '@deepseek-ai/dsh/lib/bin.js' in ENGINE)
check('★ 写的 cordis.patch.yml 就是 []（上游一行不改）',
      '"cordis.patch.yml"), "[]\\n");' in strip_comments(ENGINE))
check('DSH_HOME 用 guest 里的 /root/.dsh（不是宿主路径，否则原子写 ENOSYS）',
      'DSH_HOME=/root/.dsh' in ENGINE)
check('★ 不许出现 PROOT_NO_SECCOMP（设了它 proot 就不做 seccomp 加速，'
      'renameat2 变 ENOSYS、node 的 fs.rename 直接死）',
      'PROOT_NO_SECCOMP' not in strip_comments(EPROOT))
check('proot 参数含 -r / -0 / --kill-on-exit，并绑 /dev /proc /sys',
      '"-r"' in EPROOT and '"-0"' in EPROOT and '"--kill-on-exit"' in EPROOT
      and '"/dev"' in EPROOT and '"/proc"' in EPROOT and '"/sys"' in EPROOT)
check('resolv.conf 由 ConnectivityManager 取 DNS 后绑进 guest',
      'ConnectivityManager' in read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'Dns.java'))
      and ':/etc/resolv.conf' in EPROOT)
check('宿主地址用正则/前缀识别 dsh web 那一行',
      'http://127.0.0.1:' in ENGINE and 'dsh web' in ENGINE)
check('EngineService 是前台服务（切后台不中断）', 'startForeground(' in ESVC)

# ==================== [B] 控制接口 ====================

print()
print('=== [B] 控制接口：只绑回环、只读一条 ===')
check('★ 只绑 127.0.0.1（不能用 0.0.0.0）',
      'getByName("127.0.0.1")' in CTRL and '0.0.0.0' not in CTRL)
check('只认 GET /status 一条路由', '"GET /status"' in CTRL)
check('没有写操作（不处理 POST/PUT/DELETE）',
      not any(m in strip_comments(CTRL) for m in ('POST', 'PUT', 'DELETE', 'PATCH')))
check('端口是固定常量并对外公开（主 App 要按它问）',
      'PORT = 43190' in CTRL)

# ==================== [C] 主 App 接线 ====================

print()
print('=== [C] 主 App：四级优先 + 失败必须回落 ===')
check('引擎已并进本 App（不再是第二个包，也没有跨进程控制接口）',
      'import com.deepseek.pet.dsh.linux.Engine;' in DENG
      and 'import com.deepseek.pet.dsh.linux.EngineProot;' in DENG
      and 'EngineService.class.getName()' in DENG)
check('★ onCreate 的优先顺序：随包装 Linux → 配置远端 → 引擎 → 原生直跑 → 提示',
      # ★ 用**分支头**（含 if/else if）比较位置：只比裸方法名会被分支里的同名子串骗到
      #   （`DshEngine.installed(this)` 在我的 bundled 条件里就先出现过一次）。
      ACT.index('if (DshEngineBundled.bundled(this) && !DshEngine.installed(this)) {')
      < ACT.index('} else if (hasConfiguredUrl()) {')
      and ACT.index('} else if (hasConfiguredUrl()) {') < ACT.index('} else if (DshEngine.installed(this)) {')
      and ACT.index('} else if (DshEngine.installed(this)) {') < ACT.index('} else if (canRunLocalHost()) {'))
check('★ 只有在 running 且拿到 url 时才 load（不靠"服务起来了"就算成功）',
      'st.running()' in ACT and 'load(url)' in ACT)
check('★ 失败要如实回落：canRunLocalHost 就切原生，否则只说事实',
      'startLocalHost();' in FALLBACK and '这只包也没有可用的包树' in FALLBACK)
check('读状态是进程内直读（不再是 HTTP 轮询，也就没有超时这回事）',
      'DshEngine.status(DshHarnessActivity.this)' in ACT and 'serviceAlive' in DENG)
check('清单里有包可见性 <package>（Android 11+ 否则查不到引擎装没装）',
      'com.deepseek.pet.linux' in MAN and '<queries>' in MAN)
check('界面上有一句"为什么是另一个包"的说明（不糊弄用户）',
      'Android 10+ 只允许 targetSdk≤28' in ACT)

# ==================== [D] 产物与边界 ====================

print()
print('=== [C2] 载荷下载：manifest + sha256 + .part + 重试 ===')
DL = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'Downloader.java'))
EACT = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'EngineActivity.java'))
check('Downloader 真的在校验 sha256（算摘要 + 比对，缺一不可）',
      'MessageDigest.getInstance("SHA-256")' in DL
      and '!got.equalsIgnoreCase(sha256)' in strip_comments(DL))
check('★ 先写 .part，校验通过才 rename 成正式名（否则半截文件会被当成已下好）',
      '".part"' in DL and 'renameTo(dest)' in DL)
check('★ sha256 不符就删掉，不留坏文件',
      'part.delete()' in DL and 'sha256 不符' in DL)
check('清单从 <base>/manifest.json 取，url 支持相对与绝对',
      '"manifest.json"' in ENGINE and 'startsWith("http://")' in ENGINE)
check('★ 下载失败重试 3 次（移动网络/模拟器 NAT 都会偶发断流）',
      'for (int attempt = 1; attempt <= 3' in strip_comments(ENGINE))
check('记住上次用的服务器地址（source.txt），下次预填',
      'source.txt' in ENGINE and 'lastSource' in ENGINE)
check('界面有地址输入框 + 「下载并安装」按钮',
      'new EditText(this)' in EACT and '从上面的地址下载并安装' in EACT)
check('电脑端有对应的打包脚本（manifest.json + sha256 + bytes）',
      os.path.isfile(r'D:\petport_lab\petengine\tools\pack_engine_payload.py')
      and 'sha256' in read(r'D:\petport_lab\petengine\tools\pack_engine_payload.py'))

print()
print('=== [C3] 宿主常驻：住在前台服务里，界面进出不重启 ===')
HOST_SVC = read(os.path.join(DSH, 'DshHostService.java'))
HOST_ST = read(os.path.join(DSH, 'DshHostState.java'))
INS = read(os.path.join(DSH, 'DshHarnessInstaller.java'))
PACKER = read(os.path.join(ROOT, 'tools', 'pack_harness_bundle.py'))
check('有 DshHostService 且是真前台服务（startForeground）',
      'class DshHostService extends Service' in HOST_SVC and 'startForeground(' in HOST_SVC)
check('清单注册了它，且带 foregroundServiceType=specialUse（targetSdk 34 硬要求）',
      '.dsh.DshHostService' in MAN and 'foregroundServiceType="specialUse"' in MAN)
check('★ 界面用 startForegroundService 起宿主（不是自己起 node）',
      'startForegroundService(svc)' in ACT and 'node().startWeb' not in ACT)
check('★ onDestroy 不再停宿主（进出界面不重启宿主）',
      'node.stop()' not in method_body(ACT, 'protected void onDestroy'))
check('服务里"已在跑就不重启"（只补状态）',
      'node != null && node.running()' in HOST_SVC)
check('状态用进程级持有类（界面重建也能拿到地址）',
      'public static String url()' in HOST_ST and 'public static boolean running()' in HOST_ST)
check('界面能停宿主（有停止入口，不是只能杀进程）',
      '停止本机宿主' in ACT and 'ACTION_STOP' in HOST_SVC)
check('★ 安装器把清单里的相对 url 和 base 拼起来（否则 MalformedURLException: no protocol）',
      'startsWith("http://")' in INS and 'join(base, rel)' in INS)
check('★ 打包器不再搬桌面的 profiles（桌面独有的 bundle 会让宿主启动失败）',
      'keep_list = ("skills", "settings.yaml") if a.android_profile' in PACKER)
check('★ 打包器的暂存目录每次清空（否则上一次的 profiles 会粘进 home.zip）',
      '_shutil.rmtree(tmp_root)' in PACKER)
check('★ Android profile 的 package.json 由我们强制重写（不继承桌面那份）',
      'io.open(pkg, "w"' in PACKER)

print()
print('=== [C4] 两条轨道并存：端口分开 + 引擎开机自启 ===')
BOOT = read(os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'BootReceiver.java'))
check('★ 原生直跑用 3081（不是上游默认 3080，避免和引擎抢端口）',
      'return 3081;' in NODE)
check('★ 原生直跑把 --port 传给上游（官方参数，不是我们打的补丁）',
      'cmd.add("--port");' in NODE and 'String.valueOf(port())' in NODE)
LIVE_ENGINE = read(APP_ENGINE)
check('★ 引擎显式用 30800（固定端口 ⇒ 网页配置不丢）',
      # 主 App 这一份是 `--port ` + `append(port)`（petengine 那份才是 String.valueOf(PORT)）
      'PORT = 30800' in LIVE_ENGINE
      and '--port ' in LIVE_ENGINE and '.append(port)' in LIVE_ENGINE)
check('★ 端口候选只剩 30800 / 30801（不许 3090.. 与内核随机）',
      # 只查活代码：注释里还留着"旧逻辑 3080→3090"的历史说明
      'PORT = 30800' in strip_comments(LIVE_ENGINE)
      and 'PORT_ALT = 30801' in strip_comments(LIVE_ENGINE)
      and '3090' not in strip_comments(LIVE_ENGINE))
check('引擎注册了开机自启接收器（BOOT_COMPLETED + LOCKED_BOOT_COMPLETED）',
      '.BootReceiver' in EMAN and 'BOOT_COMPLETED' in EMAN)
check('清单里有 RECEIVE_BOOT_COMPLETED', 'RECEIVE_BOOT_COMPLETED' in EMAN)
check('★ 开机只在载荷装好时才拉（没装好就别在开机最忙时解 800 MB）',
      'if (!ready) return;' in strip_comments(BOOT))
check('★ 前台服务被拦就退回普通 startService（Android 12+ 开机限制）',
      'tryStart(context, true)' in BOOT and 'tryStart(context, false)' in BOOT)
check('开机结果落 boot.log（失败也要能查）', 'boot.log' in BOOT)
check('★ 引擎起之前清掉孤儿 proot（系统杀进程会留下它占着 30800 ⇒ EADDRINUSE）',
      'EngineProot.killLeftovers(c);   // 起之前：清孤儿' in ENGINE
      and 'static int killLeftovers' in EPROOT)
check('★ 主 App 把引擎报的 detail 翻到界面上（首次装载荷要几分钟，不能只显示"正在启动"）',
      'st.detail.length() > 0 ? st.detail : st.summary()' in ACT and 'Linux 引擎：' in ACT)

print()
print('=== [C5] 收尾：磁盘 / 只跑一条 / 停就停干净 ===')
check('★ 装好之后清压缩包（省 ~150 MB），且删之前确认真装好了',
      'cleanupPayloads(c)' in ENGINE and 'EngineProot.available(c)' in ENGINE)
check('清理只删三类载荷，source.txt 留着', 'ubuntu-base.tar.gz' in ENGINE
      and 'harness-linux.tgz' in ENGINE and 'startsWith("node-linux-")' in ENGINE)
check('★ 停引擎时收尸（proot 的子进程会活下来占着端口）',
      'EngineProot.killLeftovers(c)' in engine_stop_body(ENGINE))
check('★ 收尸要认 guest 路径（node 的 cmdline 是 /opt/node/bin/node，不是宿主路径）',
      '"/opt/harness/node_modules"' in EPROOT and '"/opt/node/bin/node"' in EPROOT)
check('★ 选引擎时停原生宿主（默认只跑一条轨道）',
      'stopLocalHostQuiet();' in method_body(ACT, 'private void startEngineAndWait()'))
check('★ 选原生直跑时停引擎', 'DshEngine.stop(this);' in strip_comments(method_body(ACT, 'private void startLocalHost()')))
check('界面能直接停引擎（省内存）', '停止 Linux 引擎' in ACT)

print()
print('=== [C7] 等待引擎时的进度与取消 ===')
check('★ 等引擎时显示不确定进度条（首次装载荷要几分钟，不能只转圈不说话）',
      'bar.setIndeterminate(true)' in ACT and 'bar.setVisibility(View.VISIBLE)' in ACT)
check('★ 等待可取消，且取消的只是"这一次等待"（引擎继续在后台装/起）',
      'if (engineWaitCancelled) {' in strip_comments(ACT) and '引擎仍在后台继续装/起' in ACT)
check('取消/成功后把进度条收掉（不留个假进度条杵在那）',
      ACT.count('bar.setIndeterminate(false)') >= 2)
check('界面有「取消等待」入口', '取消等待' in ACT)

print()
print('=== [C6] 端到端验收脚本（插上手机就是一条命令，arm64 也一样）===')
E2E = read(r'D:\用户\DS\DSH移植\tools\e2e_engine.py')
check('有 e2e_engine.py', len(E2E) > 3000, '%d 字节' % len(E2E))
check('★ 先核对设备 ABI 再跑（arm64/x64 不能拿错载荷）',
      'ro.product.cpu.abi' in E2E and 'want in abi' in E2E)
check('★ 用 cookie jar 跟 303（上游是 token → Set-Cookie 两段式鉴权，裸 GET 永远 401）',
      'http.cookiejar.CookieJar()' in E2E and 'HTTPCookieProcessor' in E2E)
check('★ 经 adb forward 访问设备里的宿主（不是拿设备地址在本机乱撞）',
      'local_port' in E2E and 'forward' in E2E)
check('判据含 HTTP 200 与响应体像界面', 'HTTP 200' in E2E and '<html' in E2E)
check('证据落盘（截图 + engine.log + boot.log）',
      'screencap' in E2E and 'engine.log' in E2E and 'boot.log' in E2E)
check('支持 --fresh 走完整"下载→解包→起宿主"', '"--fresh"' in E2E and 'pm clear' in E2E)

print()
print('=== [D] 产物与诚实边界 ===')
check('引擎 APK 产物在（dist/DshLinuxEngine_v1.0.apk）',
      os.path.isfile(os.path.join(ENG, 'dist', 'DshLinuxEngine_v1.0.apk')))
check('文档写明了 PROOT_NO_SECCOMP 这个坑', 'PROOT_NO_SECCOMP' in DOC)
check('文档写明了 mv -n 的已知限制', 'mv -n' in DOC)
check('文档写明了下载器、以及 10.0.2.2 截断这个模拟器坑',
      ('下载' in DOC and '10.0.2.2' in DOC))
check('引擎按 ABI 选 node 载荷（x86_64 ⇒ x64，否则 arm64）',
      'endsWith("x86_64") ? "x64" : "arm64"' in strip_comments(ENGINE))
check('arm64 载荷有静态校验脚本（没真机时至少证明东西是对的）',
      os.path.isfile(r'D:\petport_lab\petengine\tools\verify_arm64_payload.py'))
check('文档写明了 arm64 只做静态校验、没有真机', 'arm64' in DOC and '真机' in DOC)

print()
if fail == 0:
    print('双轨断言通过（%d 项）' % ok)
    sys.exit(0)
print('双轨断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
