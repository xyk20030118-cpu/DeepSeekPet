# -*- coding: utf-8 -*-
"""反向验证：把「工作模式 = 上游 Harness 本体」的关键判据改坏，确认 verify_harness_client.py 会红。

破坏项：
  A. 入口指回已删除的自造工作台（编译不过 / 判据变红）
  B. WebView 关掉 JS（上游是 SPA ⇒ 白屏）
  C. token 不再落 pref（每次刷新都 401）
  D. 清单里把 Harness 页注销
  E. 外部链接不拦截（WebView 变成万能浏览器）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
ENTRY = os.path.join(SRC, 'DshModeEntry.java')
HARNESS = os.path.join(SRC, 'DshHarnessActivity.java')
MAN = os.path.join(ROOT, 'AndroidManifest.xml')
DSH = os.path.join(SRC, 'dsh')
INSTALLER = os.path.join(DSH, 'DshHarnessInstaller.java')
PROOT = os.path.join(DSH, 'linux', 'EngineProot.java')
ENGINE = os.path.join(DSH, 'linux', 'Engine.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
VERIFY = os.path.join(ROOT, 'build', 'verify_harness_client.py')


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


print('=== 反向验证：Harness 客户端判据必须抓得住破坏 ===')
results = []
# ★ v8.0：入口改成了带独立任务标志的 Intent，锚点跟着走。
results.append(trial('A. 入口指回自造工作台', ENTRY,
                     'Intent i = new Intent(a, DshHarnessActivity.class);',
                     'Intent i = new Intent(a, DshWorkActivity.class);'))
results.append(trial('B. WebView 关掉 JS', HARNESS,
                     's.setJavaScriptEnabled(true);',
                     's.setJavaScriptEnabled(false);'))
results.append(trial('C. token 不再落 pref', HARNESS,
                     '.putString(DshFeature.KEY_HARNESS_TOKEN, tok)',
                     '.putString(DshFeature.KEY_HARNESS_TOKEN, "")'))
results.append(trial('D. 清单注销 Harness 页', MAN,
                     'android:name=".DshHarnessActivity"',
                     'android:name=".DshHarnessActivityGone"'))
results.append(trial('E. 外部链接不拦截', HARNESS,
                     'if (url != null && url.startsWith("http") && !sameHost(url)) {',
                     'if (false) {'))

results.append(trial('F. 下载后不校验 sha256', INSTALLER,
                     'if (!got.equalsIgnoreCase(it.sha256)) {',
                     'if (false) {'))
results.append(trial('G. 解压不挡路径穿越', INSTALLER,
                     'if (name.indexOf("..") >= 0 || name.startsWith("/") || name.indexOf(\':\') >= 0) {',
                     'if (false) {'))

results.append(trial('H. root 授权无限等待', PROOT,
                     'p.waitFor(ROOT_PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)',
                     'p.waitFor()'))
results.append(trial('I. root 失败仍用原 argv 重启', ENGINE,
                     'proot.runArgvNonRoot(c, argv, output)',
                     'proot.runArgv(c, argv, output)'))
results.append(trial('J. 普通回退仍绑定 root 目录', PROOT,
                     'if (!forceNonRoot && com.deepseek.pet.dsh.DshBridge.rootHostEnabled(c) && rootUsable(c)) {',
                     'if (com.deepseek.pet.dsh.DshBridge.rootHostEnabled(c) && rootUsable(c)) {'))
results.append(trial('K. root 开关不再重启宿主', MAIN,
                     'com.deepseek.pet.dsh.DshEngine.restart(MainActivity.this);',
                     '// no restart'))
results.append(trial('L. 端口又允许漂到 3095', ENGINE,
                     '    static final int PORT_ALT = 30801;',
                     '    static final int PORT_ALT = 3095;'))
results.append(trial('L2. 端口改回 3090（漂移）', ENGINE,
                     '    static final int PORT = 30800;',
                     '    static final int PORT = 3090;'))
results.append(trial('M. 30800 被占时不再清孤儿', ENGINE,
                     '                    int k = EngineProot.killLeftovers(c);',
                     '                    int k = 0;'))
results.append(trial('N. 复用又去读远端那格', ENGINE,
                     'getString(KEY_LAST_URL, "")',
                     'getString("dsh_harness_url", "")'))
results.append(trial('O. 保底端口不记住（会来回漂）', ENGINE,
                     '            rememberPort(PORT_ALT);',
                     '            // no remember'))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
if not bad and not skipped:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 0 项）' % len(results))
    sys.exit(0)
print('反向验证失败：%d 项没被抓到，%d 项锚点未命中' % (len(bad), len(skipped)))
sys.exit(1)
