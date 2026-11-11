# -*- coding: utf-8 -*-
"""反向验证：把「提醒/自动化（一比一版）」的关键判据改坏，确认 verify_dsh_jobs.py 真的会红。

破坏项：
  A. 间隔下限被拿掉（可以设 1 秒）
  B. 夏令时重叠不再取较早（回拨那一小时会晚一小时才跑）
  C. 关掉的定时任务照样挂着闹钟
  D. 广播里直接跑 chat（绕开协调器：切页面就断、撞广播 10 秒上限）
  E. 本地后端把 sessionId 丢掉（定时结果写进用户正在看的会话）
  F. 左栏「任务」入口没了
  G. 定时广播被导出（外部 App 能伪造触发）
  H. 模型看不到提醒工具（插件登记了但没注册进工具集）
  I. cron 范围校验失效（30-10 这种非法区间被接受）

★ 本脚本会临时改源码再恢复：**只能后台跑、不许中途强杀**。
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
SCHED = os.path.join(DSH, 'DshSchedule.java')
TOOLS = os.path.join(DSH, 'DshTools.java')
RUNNER = os.path.join(DSH, 'DshScheduleRunner.java')
RECV = os.path.join(DSH, 'DshScheduleReceiver.java')
LOCAL = os.path.join(DSH, 'DshLocalBackend.java')
WORK = os.path.join(SRC, 'DshWorkActivity.java')
MAN = os.path.join(ROOT, 'AndroidManifest.xml')
VERIFY = os.path.join(ROOT, 'build', 'verify_dsh_jobs.py')


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


print('=== 反向验证：提醒 / 自动化（一比一版）的判据必须抓得住破坏 ===')
results = []
results.append(trial('A. 间隔下限被拿掉', SCHED,
                     '        if (seconds < MIN_EVERY_SECONDS) {',
                     '        if (seconds < 1) {'))
results.append(trial('B. 夏令时重叠不取较早', SCHED,
                     '            long earlier = t - 3600_000L;',
                     '            long earlier = t;'))
results.append(trial('C. 关掉的定时任务照样挂闹钟', RUNNER,
                     '        if (!s.enabled) {',
                     '        if (false) {'))
results.append(trial('D. 广播里直接跑 chat（绕开协调器）', RECV,
                     '            DshTaskCoordinator.get(app).submit(s.prompt, s.sessionId());',
                     '            DshBridge.chat(app, s.prompt, null);'))
results.append(trial('E. 本地后端丢掉 sessionId', LOCAL,
                     'DshBridge.chatIn(ctx, text, sessionId, new DshBridge.Callback() {',
                     'DshBridge.chatIn(ctx, text, "", new DshBridge.Callback() {'))
results.append(trial('F. 左栏「任务」入口没了', WORK,
                     '"🗂 任务（" + DshBridge.taskStore(this).recent(200).size()',
                     '"🗂 任务"'))
results.append(trial('G. 定时广播被导出', MAN,
                     '            android:name=".dsh.DshScheduleReceiver"\n'
                     '            android:enabled="true"\n'
                     '            android:exported="false">',
                     '            android:name=".dsh.DshScheduleReceiver"\n'
                     '            android:enabled="true"\n'
                     '            android:exported="true">'))
results.append(trial('H. 模型看不到提醒工具', TOOLS,
                     '            for (DshTool t : scheduleTools(host)) reg.add(t);',
                     '            for (DshTool t : scheduleTools(host)) { }'))
results.append(trial('I. cron 范围校验失效', SCHED,
                     '                if (from < lo || to > hi || from > to) return false;',
                     '                if (false) return false;'))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
if not bad and not skipped:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 0 项）' % len(results))
    sys.exit(0)
print('反向验证失败：%d 项没被抓到，%d 项锚点未命中（按未验证计）' % (len(bad), len(skipped)))
sys.exit(1)
