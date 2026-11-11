# -*- coding: utf-8 -*-
"""反向验证：把「双轨」的关键判据改坏，确认 verify_dual_track.py 会红。

破坏项（每一项都是真会让功能坏掉的改法）：
  A. 引擎包 targetSdk 改成 34（proot 立刻不能 exec）
  B. 把 PROOT_NO_SECCOMP=1 加回活代码（node 的 fs.rename 会 ENOSYS，宿主起不来）
  C. cordis.patch.yml 不再写 []（变成禁用条目 ⇒ 上游被改动）
  D. 主 App 不再查引擎（完整 Linux 那条直接没有了）
  E. 失败时不回落原生直跑（用户只看到"没起来"）
  F. 控制接口绑 0.0.0.0（把宿主暴露给同网段）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r'D:\petport_lab\petbuild'
ENG = r'D:\petport_lab\petengine'
EMAN = os.path.join(ENG, 'AndroidManifest.xml')
ENGINE = os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'Engine.java')
EPROOT = os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'EngineProot.java')
CTRL = os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'ControlServer.java')
DL = os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'Downloader.java')
INS_ = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'dsh', 'DshHarnessInstaller.java')
PACKER = os.path.join(ROOT, 'tools', 'pack_harness_bundle.py')
NP = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'dsh', 'DshNodeProcess.java')
BOOT = os.path.join(ENG, 'src', 'com', 'deepseek', 'pet', 'linux', 'BootReceiver.java')
ACT = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'DshHarnessActivity.java')
VERIFY = os.path.join(ROOT, 'build', 'verify_dual_track.py')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    raw = (r.stdout or b'') + (r.stderr or b'')
    try:
        out = raw.decode('utf-8')
    except UnicodeDecodeError:
        out = raw.decode('gbk', 'replace')
    return r.returncode, out


def trial(name, path, old, new):
    if not os.path.isfile(path):
        print('  !! %-46s 文件不存在，跳过' % name)
        return None
    s = io.open(path, encoding='utf-8').read()
    if s.count(old) != 1:
        print('  !! %-46s 片段出现 %d 次，跳过' % (name, s.count(old)))
        return None
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
        code, out = run_verify()
        red = (code != 0) or ('[FAIL]' in out)
        why = [ln.strip() for ln in out.splitlines() if '[FAIL]' in ln]
        if red:
            print('  [OK]   %-46s 断言变红（%s）' % (name, (why[0][:56] if why else '退出码 %d' % code)))
            return True
        print('  [FAIL] %-46s 改坏了却没红' % name)
        return False
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：双轨判据必须抓得住破坏 ===')
results = []

results.append(trial('A. 引擎 targetSdk 改回 34', EMAN,
                     'android:targetSdkVersion="28"',
                     'android:targetSdkVersion="34"'))

results.append(trial('B. 加回 PROOT_NO_SECCOMP=1', EPROOT,
                     '        env.put("PROOT_TMP_DIR"',
                     '        env.put("PROOT_NO_SECCOMP", "1");\n        env.put("PROOT_TMP_DIR"'))

results.append(trial('C. patch 不再写 []', ENGINE,
                     '"cordis.patch.yml"), "[]\\n");',
                     '"cordis.patch.yml"), "- id: subprocess\\n  disabled: true\\n");'))

results.append(trial('D. 主 App 不再查引擎', ACT,
                     '} else if (DshEngine.installed(this)) {',
                     '} else if (false) {'))

results.append(trial('E. 失败时不再回落原生', ACT,
                     '），改用原生直跑");\n                    startLocalHost();',
                     '），改用原生直跑");'))

results.append(trial('F. 控制接口绑 0.0.0.0', CTRL,
                     'getByName("127.0.0.1")',
                     'getByName("0.0.0.0")'))

results.append(trial('G. 不校验 sha256', DL,
                     'if (!got.equalsIgnoreCase(sha256)) {',
                     'if (false) {'))

results.append(trial('H. 不用 .part（直接写目标）', DL,
                     'File part = new File(dest.getAbsolutePath() + ".part");',
                     'File part = dest;'))

results.append(trial('I. 界面退出时又去停宿主', ACT,
                     'DshBridge.setWorkMode(this, false);',
                     'DshBridge.setWorkMode(this, false);\n        if (node != null) node.stop();'))

results.append(trial('J. 安装器不拼相对 url', INS_,
                     ': join(base, rel);',
                     ': rel;'))

results.append(trial('K. 打包器又搬桌面 profiles', PACKER,
                     'keep_list = ("skills", "settings.yaml") if a.android_profile',
                     'keep_list = ("profiles", "skills", "settings.yaml")'))

results.append(trial('L. 原生直跑改回抢 3080', NP,
                     'return 3081;',
                     'return 3080;'))

results.append(trial('M. 开机不查载荷就拉起', BOOT,
                     'if (!ready) return;',
                     'if (false) return;'))

results.append(trial('N. 起引擎前不清孤儿 proot', ENGINE,
                     'int killed = EngineProot.killLeftovers(c);   // 起之前：清孤儿',
                     'int killed = 0;'))

results.append(trial('O. 停引擎时不收尸', ENGINE,
                     'int killed = EngineProot.killLeftovers(c);   // 停之后再收一次',
                     'int killed = 0;'))

results.append(trial('P. 收尸只按 rootfs 路径匹配', EPROOT,
                     '"/opt/harness/node_modules",',
                     '"/nope",'))

results.append(trial('Q. 选原生直跑时不停引擎', ACT,
                     'DshEngine.stop(this);',
                     '// DshEngine.stop(this);'))

results.append(trial('R. 等待不可取消', ACT,
                     'if (engineWaitCancelled) {',
                     'if (false) {'))

done = [r for r in results if r is not None]
bad = [r for r in done if not r]
print()
if not bad and done:
    print('反向验证通过：%d/%d 项破坏都被抓到' % (len(done), len(done)))
    sys.exit(0)
print('反向验证失败：%d 项没有被抓到' % len(bad))
sys.exit(1)
