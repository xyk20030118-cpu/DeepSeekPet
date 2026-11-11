# -*- coding: utf-8 -*-
"""反向验证：把「工作区（第十四版）」的关键判据一个个改坏，确认 verify_dsh_workspace.py 真的会红。

破坏项：
  A. 名字允许点开头（隐藏目录 / 路径穿越回潮）
  B. 会话目录脱离工作区（多工作区又串会话）
  C. 会话标题每次都覆盖（用户认得的名字被第二条消息改掉）
  D. 任务在跑时也允许切工作区（一轮对话换沙箱根 ⇒ 可能丢文件）
  E. 左栏一次列出全部会话（「展开其余 N 个会话」的折叠是假的）
  F. 会话指针不分工作区（切回来不是上次那个会话）
  G. 发消息时不记 session/title（左栏又变回一串 id）

★ 本脚本会临时改源码再恢复：**只能后台跑、不许中途强杀**（本工程踩过这个坑）。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
WS = os.path.join(DSH, 'DshWorkspace.java')
BRIDGE = os.path.join(DSH, 'DshBridge.java')
SESSION = os.path.join(DSH, 'DshSession.java')
WORK = os.path.join(SRC, 'DshWorkActivity.java')
VERIFY = os.path.join(ROOT, 'build', 'verify_dsh_workspace.py')


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
    n = s.count(old)
    if n == 0:
        print('  !! %-46s 找不到片段（重构过？），跳过' % name)
        return None
    if n > 1:
        print('  !! %-46s 片段出现 %d 次（不唯一），跳过' % (name, n))
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
        print('  [FAIL] %-46s 改坏了却没红 —— 断言是空转的！' % name)
        return False
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：工作区 的判据必须抓得住破坏 ===')
results = []
results.append(trial('A. 名字允许点开头（路径穿越）', WS,
                     '        if (n.startsWith(".")) return "名字不能以点开头";\n',
                     ''))
results.append(trial('B. 会话目录脱离工作区（又串会话）', BRIDGE,
                     '        File f = activeWorkspace(ctx).sessions;',
                     '        File f = DshWorkspace.defaultWs(baseDir(ctx)).sessions;'))
results.append(trial('C. 标题每次都覆盖', SESSION,
                     '        if (title != null && title.trim().length() > 0) return null;',
                     '        if (false) return null;'))
results.append(trial('D. 任务在跑时也允许切工作区', WORK,
                     '        if (isBusy()) {\n'
                     '            toast("还有任务在跑：先「停止生成」再切工作区");\n'
                     '            return;\n'
                     '        }',
                     '        if (false) {\n'
                     '            toast("还有任务在跑：先「停止生成」再切工作区");\n'
                     '            return;\n'
                     '        }'))
results.append(trial('E. 左栏一次列出全部会话（折叠是假的）', WORK,
                     '                int show = wsExpanded ? rows.size() : Math.min(3, rows.size());',
                     '                int show = rows.size();'))
results.append(trial('F. 会话指针不分工作区', BRIDGE,
                     '        return DshFeature.KEY_SESSION + (id.length() == 0 ? "" : "_" + id);',
                     '        return DshFeature.KEY_SESSION;'))
results.append(trial('G. 发消息时不记 session/title', BRIDGE,
                     '            session.setTitle(userText.trim());\n',
                     ''))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
if not bad and not skipped:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 0 项）' % len(results))
    sys.exit(0)
print('反向验证失败：%d 项没被抓到，%d 项锚点未命中（按未验证计）' % (len(bad), len(skipped)))
sys.exit(1)
