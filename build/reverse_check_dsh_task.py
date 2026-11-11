# -*- coding: utf-8 -*-
"""反向验证：把 Phase 1~3 的关键判据一个个改坏，确认 verify_dsh_task.py 真的会红。

破坏项：
  A. onDestroy 又把任务取消了（"离开页面 = 取消"，方案最想修的那个 bug 回潮）
  B. 提交不再幂等（有活动任务也直接新建 → 连点会起两个 agent）
  C. 重启恢复不再标"状态待确认"（装懂，可能重跑有副作用操作）
  D. 未知命令不再报错（假装执行了）
  E. @file 不再校验存在（坏引用塞给模型）
  F. UI 又自己 new Thread 跑 chat（UI 直接执行 agent loop）
  G. 能力清单不再来自插件（UI 看到的工具与实际不符）

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
WORK = os.path.join(SRC, 'DshWorkActivity.java')
COORD = os.path.join(SRC, 'DshTaskCoordinator.java')
STORE = os.path.join(DSH, 'DshTaskStore.java')
CMD = os.path.join(DSH, 'DshCommand.java')
MEN = os.path.join(DSH, 'DshMention.java')
LOCAL = os.path.join(DSH, 'DshLocalBackend.java')
VERIFY = os.path.join(ROOT, 'build', 'verify_dsh_task.py')


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


print('=== 反向验证：任务 / 命令 / 后端 的判据必须抓得住破坏 ===')
results = []
results.append(trial('A. onDestroy 又取消任务（离开即取消）', WORK,
                     '        if (coord != null && coordListener != null) coord.detach(coordListener);',
                     '        if (coord != null && coordListener != null) { coord.detach(coordListener); coord.cancel(); }'))
results.append(trial('B. 提交不再幂等（连点起两个）', COORD,
                     '            if (current != null && current.active()) {',
                     '            if (false) {'))
results.append(trial('C. 重启恢复不标"待确认"', STORE,
                     '            t.setState(DshTask.UNKNOWN_AFTER_DISCONNECT);',
                     '            t.setState(t.state);'))
results.append(trial('D. 未知命令不报错', CMD,
                     '            p.error = "未知命令：/" + p.name + "（输入 /help 看全部）";',
                     '            p.error = "";'))
results.append(trial('E. @file 不校验存在', MEN,
                     '            if (!f.exists()) {',
                     '            if (false) {'))
results.append(trial('F. UI 又自己跑 agent loop', WORK,
                     '        boolean started = coord.submit(injected, DshBridge.currentSessionId(this));',
                     '        new Thread(new Runnable() { @Override public void run() { DshBridge.chat(DshWorkActivity.this, injected, null); } }).start();\n'
                     '        boolean started = coord.submit(injected, DshBridge.currentSessionId(this));'))
results.append(trial('G. 能力清单不再来自插件', LOCAL,
                     '        cap.tools.addAll(DshPlugin.visibleTools(on));',
                     '        cap.tools.size();'))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
if not bad and not skipped:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 0 项）' % len(results))
    sys.exit(0)
print('反向验证失败：%d 项没被抓到，%d 项锚点未命中（按未验证计）' % (len(bad), len(skipped)))
sys.exit(1)
