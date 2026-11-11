# -*- coding: utf-8 -*-
"""反向验证：把「手机自己跑宿主」的关键判据改坏，确认 verify_android_providers.py 会红。

破坏项（每一项都是真会让机器变坏的改法，不是改注释）：
  A. subprocess 只发 SIGTERM、不再补 SIGKILL（进程赖着不退）
  B. sandbox 把 read-only 放行（吞掉 fail closed —— 静默不受限执行）
  C. attachments 不再校验读回的摘要（存坏了也当好的用）
  D. profile patch 少 insert 一个 provider（宿主启动时那条 pending 又回来了）
  E. startWeb 不带 --no-open（每次启动都去 spawn 不存在的 xdg-open）
  F. 去掉「本机优先」判断（手机明明能自己跑，却还去连电脑）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
AND = os.path.join(ROOT, 'android-plugin', 'android')
PATCH = os.path.join(ROOT, 'android-plugin', 'cordis.patch.yml')
NODE = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'dsh', 'DshNodeProcess.java')
HARNESS = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'DshHarnessActivity.java')
VERIFY = os.path.join(ROOT, 'build', 'verify_android_providers.py')


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


print('=== 反向验证：Android 三服务的判据必须抓得住破坏 ===')
results = []

results.append(trial('A. subprocess 不再补 SIGKILL', os.path.join(AND, 'subprocess.mjs'),
                     "if (!exited) signalRange('SIGKILL')",
                     "if (!exited) signalRange('SIGTERM')"))

results.append(trial('B. sandbox 把 read-only 放行', os.path.join(AND, 'sandbox.mjs'),
                     "if (mode === 'read-only') {",
                     "if (mode === 'read-only-never') {"))

results.append(trial('C. attachments 不校验读回摘要', os.path.join(AND, 'attachments.mjs'),
                     "if (digest(data) !== sha256) throw new AttachmentError('Stored attachment failed integrity verification.', 'ATTACHMENT_CORRUPT')",
                     "if (false) throw new AttachmentError('Stored attachment failed integrity verification.', 'ATTACHMENT_CORRUPT')"))

results.append(trial('D. patch 少 insert sandbox-android', PATCH,
                     "    - id: sandbox-android\n      name: './android/sandbox.mjs'\n",
                     ""))

results.append(trial('E. startWeb 不带 --no-open', NODE,
                     'cmd.add("--no-open");',
                     'cmd.add("--open");'))

results.append(trial('F. 去掉本机优先判断', HARNESS,
                     'if (!hasConfiguredUrl() && canRunLocalHost()) {',
                     'if (false) {'))

results.append(trial('G. 不再把 provider 装进 profile', NODE,
                     'ensureAndroidProfile(c, home);',
                     'ensureAndroidProfileX(c, home);'))

results.append(trial('H. 本机地址又写回「远端」那格', HARNESS,
                     '.putString(DshFeature.KEY_HARNESS_LOCAL, url)',
                     '.putString(DshFeature.KEY_HARNESS_URL, shortUrl(url))'))

done = [r for r in results if r is not None]
bad = [r for r in done if not r]
print()
if not bad and done:
    print('反向验证通过：%d/%d 项破坏都被抓到' % (len(done), len(done)))
    sys.exit(0)
print('反向验证失败：%d 项没有被抓到' % len(bad))
sys.exit(1)
