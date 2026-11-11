# -*- coding: utf-8 -*-
"""反向验证：把 DSH 移植的关键判据一个个**改坏**，确认断言真的会红。

★ 本工程的老规矩：**没被反向验过的断言不算断言**。
  写这套的时候我已经吃过一次亏 —— 「工具不许自己拼路径」那条判据最初写成
  `policy.resolve` 出现 >= 5 次，实际只有 4 次，于是它一直是红的，
  而我看日志时只扫了"FAIL"关键字，差点把它当成"脚本慢"忽略过去。
  这条正好说明：断言本身也要被验。

破坏项：
  A. 去掉 DshPolicy.resolve 的越界判断        → 路径穿越该被抓到
  B. 去掉工具注册表的权限闸                    → 只读档拒绝写该被抓到
  C. 把压缩的"整块丢弃"改成一次只丢一条        → 配对完整性该被抓到
  D. 把 loop 的步数循环拆掉                    → 撞上限分支该被抓到
  E. 给纯类加一行 android import               → 纯类零依赖该被抓到
  F. 去掉 assistant 的 tool_calls 编码         → 请求体编码该被抓到
  G. 把事件名 tool/result 改一个字             → "与上游同名"该被抓到
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
BUILD = os.path.join(ROOT, 'build')
DSH = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'dsh')
VERIFY = os.path.join(BUILD, 'verify_dsh_core.py')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    raw = (r.stdout or b'') + (r.stderr or b'')
    try:
        out = raw.decode('utf-8')
    except UnicodeDecodeError:
        out = raw.decode('gbk', 'replace')
    return r.returncode, out


def trial(name, fname, old, new, expect_red=True):
    path = os.path.join(DSH, fname)
    if not os.path.isfile(path):
        print('  !! %-44s 文件不存在，跳过' % name)
        return None
    s = io.open(path, encoding='utf-8').read()
    n = s.count(old)
    if n == 0:
        print('  !! %-44s 找不到片段（重构过？），跳过' % name)
        return None
    if n > 1:
        print('  !! %-44s 片段出现 %d 次（不唯一），跳过以免改错位置' % (name, n))
        return None
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
        code, out = run_verify()
        red = (code != 0) or ('[FAIL]' in out)
        why = [ln.strip() for ln in out.splitlines() if '[FAIL]' in ln]
        if red == expect_red:
            print('  [OK]   %-44s 断言变红（%s）' % (name, (why[0][:60] if why else '退出码 %d' % code)))
            return True
        print('  [FAIL] %-44s 改坏了却没红 —— 断言是空转的！' % name)
        return False
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：DSH 移植断言必须抓得住破坏 ===')
results = []
results.append(trial('A. 去掉路径越界判断', 'DshPolicy.java',
                     '        if (root.length() > 0 && !c.equals(root) && !c.startsWith(root + "/")) {',
                     '        if (false) {'))
results.append(trial('B. 去掉工具权限闸', 'DshToolRegistry.java',
                     '        if (policy != null && !policy.allows(name)) {',
                     '        if (false) {'))
results.append(trial('C. 压缩不再整块丢（配对会被拆散）', 'DshCompaction.java',
                     '        int callCount = first.calls.size();',
                     '        int callCount = 1;'))
results.append(trial('D. 拆掉步数循环上限', 'DshAgentLoop.java',
                     '            for (int i = 0; i < Math.max(1, cfg.maxSteps); i++) {',
                     '            for (int i = 0; i < Math.max(1, cfg.maxSteps) - 1; i++) {'))
results.append(trial('E. 纯类里混进 android 依赖', 'DshPolicy.java',
                     'public final class DshPolicy {',
                     'import android.content.Context;\n\npublic final class DshPolicy {'))
results.append(trial('F. 去掉 tool_calls 编码', 'DshWire.java',
                     '                    o.put("tool_calls", cs);',
                     '                    // o.put("tool_calls", cs);'))
results.append(trial('G. 事件名改一个字', 'DshEvent.java',
                     'public static final String TOOL_RESULT = "tool/result";',
                     'public static final String TOOL_RESULT = "tool/resultx";'))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
if not bad:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 %d 项）' % (len(results) - len(skipped), len(skipped)))
    sys.exit(0)
print('反向验证失败：%d 项破坏没被抓到' % len(bad))
sys.exit(1)
