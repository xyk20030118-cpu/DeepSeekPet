# -*- coding: utf-8 -*-
"""B2 补齐：**手机自己跑上游宿主**的三（+2）个服务 —— `ctx.subprocess` / `ctx.sandbox` / `ctx.attachments`。

背景（第 18 版的起点）：上游 `dsh --profile web` 在手机里能启动，但 6 个条目 pending：
    bash-sandbox(要 subprocess+sandbox) / permission-presets(要 shell) / open-in-app(要 subprocess)
    session-controller(要 attachments+fileUploads) / file-upload(要 attachments) / ui-deliverables(级联)
根因是上游那三个 provider 都依赖原生模块（koffi / sharp / node-pty），Android 上不可用。
本套断言守的是：**我们换上去的三个实现，确实按契约实现了，而且诚实边界写清了**。

判据分五段：
  [A] 三个 provider 实现了消费者真正调用的那些方法（不是照基类乱猜）；
  [B] Android profile patch：原生依赖条目禁用 + 我们的三个条目 insert 进来；
  [C] App 侧接线：本机优先自启、`--no-open`、宿主输出落盘；
  [D] 打包链路：`pack_harness_bundle.py` 会把 Android profile 覆盖进 home.zip；
  [E] 诚实边界：read-only 在 shell 上 fail closed、没有缩放后端时明确拒绝。

★ 只读源码；实机证据是 AVD 截图 docs/shots/15~17 与 `files/dsh-node.log` 里那行
  `dsh web: http://127.0.0.1:3080/?token=…`。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
PLUG = os.path.join(ROOT, 'android-plugin')
AND = os.path.join(PLUG, 'android')

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


def read(path):
    try:
        return io.open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


SUB = read(os.path.join(AND, 'subprocess.mjs'))
SAN = read(os.path.join(AND, 'sandbox.mjs'))
ATT = read(os.path.join(AND, 'attachments.mjs'))
PATCH = read(os.path.join(PLUG, 'cordis.patch.yml'))
NODE = read(os.path.join(DSH, 'DshNodeProcess.java'))
HARNESS = read(os.path.join(SRC, 'DshHarnessActivity.java'))
PACKER = read(os.path.join(ROOT, 'tools', 'pack_harness_bundle.py'))
FEATURE = read(os.path.join(DSH, 'DshFeature.java'))

# ==================== [A] 三个 provider ====================

print('=== [A] 三个 provider 实现了消费者真正调用的方法 ===')
check('android/subprocess.mjs 存在', len(SUB) > 4000, '%d 字节' % len(SUB))
check('★ 它真的继承上游基类（服务名由基类给：subprocess）',
      'extends SubprocessRuntime' in SUB and "from '@deepseek-ai/dsh-subprocess'" in SUB)
check('实现了 spawn（bash 执行器唯一入口）', '  spawn(spec) {' in SUB)
check('实现了 resolveExecutable（open-in-app 用它找可执行文件）',
      'async resolveExecutable(command, env, signal)' in SUB)
check('★ 输出契约：有界内存 + 溢出落 spill 文件 + 增量 readFrom',
      'class OutputCollector' in SUB and 'readFrom(fromByte)' in SUB and 'spillPath' in SUB)
check('★ 取消：SIGTERM 先礼后兵（graceMs 后 SIGKILL），并回收进程组',
      "signalRange('SIGTERM')" in SUB and "if (!exited) signalRange('SIGKILL')" in SUB
      and 'process.kill(-pid, signal)' in SUB)
check('★ 没有 bash 时就落到 mksh（Android 事实，写在代码里）',
      '/system/bin/sh' in SUB and 'SHELL_SHIMS' in SUB)
check('PTY 明确不支持（不假装）',
      'async spawnTerminal()' in SUB and 'PTY' in SUB)

check('android/sandbox.mjs 存在', len(SAN) > 1500, '%d 字节' % len(SAN))
check('★ 继承 SandboxProvider（服务名由基类给：sandbox）',
      'extends SandboxProvider' in SAN and 'SandboxUnavailableError' in SAN)
check('★ confine 返回消费者要的四件套（argv/enforcement/denialSignatures/runnerFailureRules）',
      'denialSignatures' in SAN and 'runnerFailureRules' in SAN and 'enforcement' in SAN)
check('★ read-only 在 shell 上 fail closed（抛 SandboxUnavailableError，绝不静默放行）',
      "if (mode === 'read-only')" in SAN and 'throw new SandboxUnavailableError(' in SAN)
check('★ enforcement 如实标注是「Android 应用沙箱」，不冒充 workspace 级',
      "ENFORCEMENT = 'android-app-sandbox'" in SAN)

check('android/attachments.mjs 存在', len(ATT) > 6000, '%d 字节' % len(ATT))
check('★ 继承 AttachmentStore（服务名由基类给：attachments）',
      'extends AttachmentStore' in ATT and "from '@deepseek-ai/dsh-attachment'" in ATT)
check('实现了 imageLimits（基类批量准入靠它）', 'this.imageLimits = Object.freeze(' in ATT)
check('实现了 validateImage / saveImage / readImage / imageHostPath',
      'async validateImage(input)' in ATT and 'async saveImage(input)' in ATT
      and 'async readImage(ref, signal)' in ATT and 'imageHostPath(ref)' in ATT)
check('实现了 verbatim 文件四件套（fileUploads 直接挂在这上面）',
      'async saveFile(input)' in ATT and 'async saveFileStream(input)' in ATT
      and 'async *readFileStream(ref, signal)' in ATT and 'fileHostPath(ref)' in ATT)
check('★ 内容寻址：sha256 分桶落盘（objects/<xx>/<sha256>）',
      "'objects'" in ATT and 'sha256' in ATT and 'digest(data) !== sha256' in ATT)
check('★ 纯 JS 图片头解析（PNG/JPEG/GIF/WebP 都能读尺寸与透明）',
      'image/png' in ATT and 'image/jpeg' in ATT and 'image/gif' in ATT and 'image/webp' in ATT
      and 'function probeImage(' in ATT)
check('★ 没有缩放后端时明确拒绝（不存过大图让模型请求失败）',
      'ATTACHMENT_WRITE_FAILED' in ATT and '没有图像缩放后端' in ATT)
check('readImageRequest 超预算也明确拒绝（ATTACHMENT_PROJECTION_UNSUPPORTED）',
      "'ATTACHMENT_PROJECTION_UNSUPPORTED'" in ATT)

# ==================== [B] profile patch ====================

print()
print('=== [B] Android profile patch：原生依赖禁用 + 我们的条目 insert ===')
check('cordis.patch.yml 是**顶层数组**（第一条是 - id:）',
      PATCH.lstrip().startswith('- id:') or PATCH.lstrip().startswith('#'))
for eid in ('attachment-local', 'subprocess', 'sandbox'):
    check('原生依赖条目 %s 被禁用' % eid,
          ('- id: %s\n  disabled: true' % eid) in PATCH)
check('★ insert 了 subprocess-android', "- id: subprocess-android" in PATCH
      and "'./android/subprocess.mjs'" in PATCH)
check('★ insert 了 sandbox-android', "- id: sandbox-android" in PATCH
      and "'./android/sandbox.mjs'" in PATCH)
check('★ insert 了 attachments-android', "- id: attachments-android" in PATCH
      and "'./android/attachments.mjs'" in PATCH)
check('patch 里禁的是 entry id（不是包名）', '@deepseek-ai/dsh-subprocess-local' not in PATCH
      and '@deepseek-ai/dsh-sandbox-local' not in PATCH)

# ==================== [C] App 侧接线 ====================

print()
print('=== [C] App 侧：手机自己起宿主 ===')
check('★ startWeb 带 --no-open（否则上游去 spawn xdg-open 报 ENOENT）',
      'cmd.add("--no-open");' in NODE)
check('★ 宿主输出落盘（files/dsh-node.log），界面滚掉也能查',
      'logFile(' in NODE and 'dsh-node.log' in NODE)
check('启动前把依赖库解到私有目录并指 LD_LIBRARY_PATH',
      'ensureNativeLibs(' in NODE and 'LD_LIBRARY_PATH' in NODE)
check('★ LD_LIBRARY_PATH 里"解出来的依赖"排在 nativeLibraryDir 前面'
      '（lib/ 里还有本地模型的 libc++，排后面会让 node 加载错版本）',
      'env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath()\n'
      '                + ":" + c.getApplicationInfo().nativeLibraryDir);' in NODE)
check('★ 原生直跑仍是「没配地址 + 有 Node + 包树已装」那条分支（现在排在引擎之后）',
      'hasConfiguredUrl()' in HARNESS and 'canRunLocalHost()' in HARNESS
      and '} else if (canRunLocalHost()) {' in HARNESS)
check('用户配过地址就尊重用户的（走远端那台机器）',
      'String u = DshBridge.prefs(this).getString(DshFeature.KEY_HARNESS_URL, "");' in HARNESS)
check('★ 等宿主起来在后台线程（不压主线程；宿主本身跑在前台服务里）',
      '"host-wait"' in HARNESS and 'startForegroundService(svc)' in HARNESS)
check('★ provider 随 APK 走：每次启动把 assets/android-plugin 装进 profile',
      'ensureAndroidProfile(c, home);' in NODE and '"android-plugin"' in NODE)
check('★ APK 里确实带着那三个 mjs 与 patch',
      os.path.isfile(os.path.join(ROOT, 'assets', 'android-plugin', 'cordis.patch.yml'))
      and os.path.isfile(os.path.join(ROOT, 'assets', 'android-plugin', 'android', 'subprocess.mjs'))
      and os.path.isfile(os.path.join(ROOT, 'assets', 'android-plugin', 'android', 'sandbox.mjs'))
      and os.path.isfile(os.path.join(ROOT, 'assets', 'android-plugin', 'android', 'attachments.mjs')))
check('抓到 dsh web 地址就落盘并直接加载',
      'sink.onUrl' in NODE and 'load(url);' in HARNESS)
check('★ 本机地址与「用户配的远端地址」分开存'
      '（混在一起 ⇒ 自启成功一次之后，下次进来就再也不自启了）',
      'KEY_HARNESS_LOCAL' in FEATURE and 'KEY_HARNESS_LOCAL' in HARNESS
      and 'putString(DshFeature.KEY_HARNESS_URL, shortUrl(url))' not in HARNESS)
check('↻ 重载的是"真正在用的那个地址"（本机优先）',
      'load(activeUrl());' in HARNESS and 'private String activeUrl()' in HARNESS)

# ==================== [D] 打包链路 ====================

print()
print('=== [D] 打包：provider 不再靠手推 ===')
check('pack_harness_bundle.py 有 --android-profile', '--android-profile' in PACKER)
check('★ 默认指向工程里的 android-plugin', 'DEFAULT_ANDROID_PROFILE' in PACKER
      and "os.path.join(os.path.dirname(HERE), \"android-plugin\")" in PACKER)
check('★ 覆盖到 profiles/web（profile 才认得 cordis.patch.yml 与 ./android/*.mjs）',
      'os.path.join(tmp_root, "profiles", "web")' in PACKER)
check('profile 根的两件必备文件缺失时会补（package.json / cordis.yml）',
      '"patchReload": "startup"' in PACKER and 'io.open(root, "w"' in PACKER)

# ==================== [E] 诚实边界 ====================

print()
print('=== [E] 诚实边界（写进代码，不只是写在文档里） ===')
check('subprocess 头注释交代：没有 systemd scope / Job Object，只有进程组',
      'systemd' in SUB and '进程组' in SUB)
check('sandbox 头注释交代：Android 沙箱粒度是 App 私有目录，不是 workspace',
      'untrusted_app' in SAN and 'App 私有目录' in SAN)
check('attachments 头注释交代：sharp/libvips 不可用 ⇒ 不缩图',
      'libvips' in ATT and 'sharp' in ATT)

print()
if fail == 0:
    print('Android 三服务断言通过（%d 项）' % ok)
    sys.exit(0)
print('Android 三服务断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
