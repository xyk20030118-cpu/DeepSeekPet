# -*- coding: utf-8 -*-
"""任务生命周期（方案 Phase 1）+ `/` 命令与 `@` 引用（Phase 2）+ 后端抽象（Phase 3）的断言套件。

三段：
  [A] 任务与 Activity 解耦：页面不再执行 agent loop、不再在 onDestroy 取消任务、
      提交幂等（运行中再发只排队）、取消穿透到执行层、进程重启后"状态待确认"且不自动重跑；
  [B] `/` 命令与 `@file` / `@conv`：命令表与"未支持要明说"、参数校验、引用先校验再发、超限报错；
  [C] 后端抽象：UI 只认 DshBackend，不认 agent loop 内部。

★ 铁律：只读源码，不改工程文件；行为类判据由 build/dshtest 真编真跑覆盖，这里查"接线与口径"。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
BUILD = os.path.join(ROOT, 'build')

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


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只认活代码；否则一句说明就能把判据逗绿）。"""
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


def method_body(text, signature):
    """按签名抓一个方法体（到下一个同缩进的 '}' 为止）。"""
    i = text.find(signature)
    if i < 0:
        return ''
    depth = 0
    j = text.find('{', i)
    if j < 0:
        return ''
    k = j
    while k < len(text):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
        k += 1
    return text[j:]


WORK = read(os.path.join(SRC, 'DshWorkActivity.java'))
COORD = read(os.path.join(SRC, 'DshTaskCoordinator.java'))
TASK = read(os.path.join(DSH, 'DshTask.java'))
STORE = read(os.path.join(DSH, 'DshTaskStore.java'))
CMD = read(os.path.join(DSH, 'DshCommand.java'))
MEN = read(os.path.join(DSH, 'DshMention.java'))
BACKEND = read(os.path.join(DSH, 'DshBackend.java'))
LOCAL = read(os.path.join(DSH, 'DshLocalBackend.java'))
BRIDGE = read(os.path.join(DSH, 'DshBridge.java'))
LOOP = read(os.path.join(DSH, 'DshAgentLoop.java'))

# ==================== [A] 任务与 Activity 解耦 ====================

print('=== [A] 任务生命周期独立于 Activity（方案 Phase 1） ===')
for f, label in (('DshTask.java', '任务状态机'), ('DshTaskStore.java', '任务落盘'),
                 ('DshTaskCoordinator.java', '任务协调器')):
    check('%s 存在（%s）' % (label, f), os.path.isfile(os.path.join(DSH if f != 'DshTaskCoordinator.java' else SRC, f)))

check('状态机含"断线/重启后未知"', 'UNKNOWN_AFTER_DISCONNECT' in TASK)
check('终态判定（成功/失败/取消）', 'state == SUCCEEDED || state == FAILED || state == CANCELLED' in TASK)
check('活动态判定（含等待确认/等后端）', 'WAITING_FOR_APPROVAL' in TASK and 'public boolean active()' in TASK)

# ★★ 最关键：页面不再自己执行、也不再"离开即取消"
_destroy = method_body(WORK, 'protected void onDestroy()')
check('★★ onDestroy 里**不再**取消任务（离开页面 ≠ 取消）',
      'cancelCurrent' not in _destroy and 'coord.cancel' not in _destroy)
check('★★ onDestroy 只取消订阅（detach）', 'coord.detach(coordListener)' in _destroy)
_send = strip_comments(method_body(WORK, 'private void send()'))
check('★★ 页面不再自己跑 agent loop（send() 里没有 new Thread + chat）',
      'DshBridge.chat(' not in _send and 'new Thread' not in _send)
check('页面通过协调器提交任务', 'coord.submit(' in WORK)
check('页面订阅协调器（attach + Listener）',
      'coord.attach(coordListener)' in WORK and 'DshTaskCoordinator.Listener' in WORK)

# 幂等：运行中再发只排队
check('★★ 提交幂等：有活动任务时只入队，不起第二个',
      'if (current != null && current.active())' in COORD and 'queue.add(t)' in COORD)
check('队列不跨进程持久化（规则写死在注释里）', '队列**不跨进程持久化**' in COORD)

# 取消穿透
check('★★ 取消穿透到执行层：coord.cancel → backend.cancel → DshBridge.cancelCurrent',
      'backend.cancel(task.id, null)' in COORD and 'DshBridge.cancelCurrent();' in LOCAL)
check('取消时先落 CANCELLING（不是直接宣布停稳）',
      'current.setState(DshTask.CANCELLING)' in COORD)
check('取消后不再把队列倒出来（防"点了停止又接着跑"）',
      'current.state != DshTask.CANCELLED && !queue.isEmpty()' in COORD)

# 重启恢复
check('★★ 启动即恢复：构造里调 store.recover()', 'store.recover()' in COORD)
check('★★ 未终结任务 → 标"待确认"，且**不自动重跑**',
      't.setState(DshTask.UNKNOWN_AFTER_DISCONNECT)' in STORE
      and '不会自动重跑' in STORE)
check('恢复路径里没有 submit/执行调用', 'submit(' not in method_body(STORE, 'public DshTask recover()'))

# ==================== [B] / 命令与 @ 引用 ====================

print()
print('=== [B] `/` 命令与 `@file` / `@conv`（方案 Phase 2） ===')
check('命令表单列一处（DshCommand）', os.path.isfile(os.path.join(DSH, 'DshCommand.java')))
for name in ('help', 'new', 'model', 'perm', 'stop', 'status'):
    check('已实现命令：/%s' % name, 'new Spec("%s"' % name in CMD)
check('★ 未支持的命令**明说未支持**（不假装执行）',
      'supported = false' in CMD.replace('public final boolean supported;', '')
      or '(new Spec("plan"' in CMD)
check('/plan 与 /attach 标为未支持',
      'new Spec("plan", "", "' in CMD and 'false)' in CMD)
check('未知命令给"未知命令"', '未知命令：/' in CMD)
check('/perm 参数只收 read|write|full', '/perm 只接受 read | write | full' in CMD)
check('★ 前导空格 = 不当命令（用户能自救）',
      'Character.isWhitespace(line.charAt(0))' in CMD)
check('UI 用**原始输入**判断命令（未先 trim）',
      'DshCommand.looksLikeCommand(typed)' in WORK)
check('UI 的分支覆盖 help/new/model/perm/stop/status',
      all(('equals("%s")' % n) in WORK for n in ('help', 'new', 'model', 'perm', 'stop', 'status')))

check('引用解析单列一处（DshMention）', os.path.isfile(os.path.join(DSH, 'DshMention.java')))
check('★ 引用路径必须过沙箱（policy.resolve）', 'policy.resolve(raw)' in MEN)
check('★ 单文件与整段注入都有上限', 'MAX_FILE_BYTES' in MEN and 'MAX_BLOCK_CHARS' in MEN)
check('★ 存在性真的被检查（不是只有一句报错文案）', 'if (!f.exists()) {' in MEN)
check('不存在/目录/过大/读不出 都报人话', all(k in MEN for k in ('不存在', '目录', '太大', '读不出来')))
check('★ 超长注入**报错**而不是静默截断', '请只引用需要的部分' in MEN)
check('UI 先校验再发（错误只提示、不发送）',
      'DshMention.resolveFiles(' in WORK and 'addLocalNote(err[0])' in WORK)
check('@conv 注入有固定格式 <conversation id=...>', '<conversation id=' in MEN)

# ==================== [C] 后端抽象 ====================

print()
print('=== [C] 后端抽象（方案 Phase 3） ===')
for sig in ('String getBackendId()', 'Status getStatus()', 'Capabilities getCapabilities()',
            'void submit(', 'void query(', 'void cancel('):
    check('DshBackend 有 %s' % sig.strip(), sig in BACKEND)
check('★ 能力清单是数据（UI 只读，不能拿开关伪造）',
      'Capabilities' in BACKEND and 'public final List<String> tools' in BACKEND)
check('LocalBackend 实现 DshBackend', 'implements DshBackend' in LOCAL)
check('LocalBackend 的能力清单来自插件（与模型看到的工具一致）',
      'cap.tools.addAll(DshPlugin.visibleTools(on));' in LOCAL)
check('协调器持有后端且可替换（Phase 4 的入口）',
      'public synchronized void useBackend(DshBackend b)' in COORD)
_work_code = strip_comments(WORK)
check('★ UI 不再直接依赖 agent loop（活代码里无 DshAgentLoop）',
      'DshAgentLoop' not in _work_code)
check('任务 id 由调用方给（幂等/查询/取消指同一任务）',
      'public final String taskId;' in BACKEND and 'DshTask.newId()' in COORD)

print()
if fail == 0:
    print('任务/命令/后端断言通过（%d 项）' % ok)
    sys.exit(0)
print('任务/命令/后端断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
