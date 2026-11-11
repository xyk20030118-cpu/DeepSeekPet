# -*- coding: utf-8 -*-
"""DSH（DeepSeek Harness）内核移植 —— 断言套件。

分三段，缺一不可：
  [A] 真编真跑：build/dshtest 里的纯 Java 断言全过（agent loop 的五条分支、
      工具协议、沙箱闸、事件日志回放、压缩策略）—— 这是"能跑"的证据；
  [B] 源码结构：这个移植**必须**满足的形状（纯类零 Android 依赖、
      网络出口只有 ApiClient、事件名与上游同名、权限闸只有一处……）——
      这是"移植得像"的证据；
  [C] 文档：设计与诚实边界在工程里留下痕迹，下一个接手窗口不用重新推导。

★ 铁律：本脚本只读源码、只在 build/dshtest/out 里编译，**不改任何工程文件**。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
BUILD = os.path.join(ROOT, 'build')
DSHTEST = os.path.join(BUILD, 'dshtest')
DOC = os.path.join(ROOT, 'DSH移植-设计与实现.md')

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


def has(path, *needles):
    t = read(path)
    return all(n in t for n in needles)


# ==================== [A] 真编真跑 ====================

print('=== [A] DSH 内核真编真跑（javac + java，无 Android） ===')
run_bat = os.path.join(DSHTEST, 'run.bat')
check('dshtest/run.bat 存在', os.path.isfile(run_bat))

r = subprocess.run(['cmd', '/c', 'run.bat'], cwd=DSHTEST, capture_output=True)
raw = (r.stdout or b'') + (r.stderr or b'')
try:
    out = raw.decode('utf-8')
except UnicodeDecodeError:
    out = raw.decode('gbk', 'replace')

m = re.search(r'DSH-CORE OK \((\d+) 项\)', out)
n_ok = int(m.group(1)) if m else 0
check('纯 Java 断言全过', r.returncode == 0 and m is not None,
      ('%d 项' % n_ok) if m else ('退出码 %d' % r.returncode))
check('断言条数不少于 120（覆盖不是摆设）', n_ok >= 120, '%d 项' % n_ok)

# 关键分支必须出现在输出里（光看"全过"不够：万一套件被砍成 3 条也会"全过"）
for label in ('无工具：成功', '工具报错不毁掉整轮', '撞上限', '异常被兜住并给出原因',
              '★ 路径穿越被挡住', '★ 只读档拒绝写', '★ tool_calls 与 tool 结果数量仍成对',
              '★ 坏行被跳过', '★ arguments 给对象时也能收下'):
    check('分支被真跑到：%s' % label, label in out)

# ==================== [B] 源码结构 ====================

print()
print('=== [B] 移植形状（源码断言） ===')

PURE = ['DshJson', 'DshEvent', 'DshWire', 'DshToolSpec', 'DshTool', 'DshPolicy',
        'DshToolRegistry', 'DshFs', 'DshSkill', 'DshSession', 'DshSessionLog',
        'DshMeter', 'DshCompaction', 'DshPrompt', 'DshAgentLoop', 'DshTools', 'DshFeature']
GLUE = ['DshBridge']

missing = [n for n in PURE + GLUE if not os.path.isfile(os.path.join(DSH, n + '.java'))]
check('内核文件齐全（%d 个）' % (len(PURE) + len(GLUE)), not missing, ','.join(missing) or 'all present')

# ★ 核心判据：纯类不许 import android.*（否则 build/dshtest 编不过，也就没法真跑）
bad_pure = []
for n in PURE:
    t = read(os.path.join(DSH, n + '.java'))
    if re.search(r'^\s*import\s+android', t, re.M) or 'android.' in t.replace('// ', ''):
        # 注释里提到 android 是允许的（说明性文字），只查 import 与显式引用
        if re.search(r'^\s*import\s+android', t, re.M) or re.search(r'\bandroid\.(content|app|graphics|view|os|util)\.', t):
            bad_pure.append(n)
check('★ 纯类零 Android 依赖（%d 个）' % len(PURE), not bad_pure, ','.join(bad_pure) or 'clean')

# 网络出口只有一处：ApiClient.chatUrl()
bridge = read(os.path.join(DSH, 'DshBridge.java'))
check('★ 胶水层复用 ApiClient.chatUrl()（不另起一套地址）', 'ApiClient.chatUrl()' in bridge)
check('胶水层没有硬编码 api.deepseek.com', 'api.deepseek.com' not in bridge)
check('胶水层用用户已填的 Key（Prefs.getApiKey）', 'Prefs.getApiKey' in bridge)
check('胶水层用用户选的模型（Prefs.getChatModel）', 'Prefs.getChatModel' in bridge)

# 开关纪律（与轮次75 LocalModelFeature 同款）
feat = read(os.path.join(DSH, 'DshFeature.java'))
check('唯一编译期开关 DshFeature.ENABLED 存在', 'ENABLED' in feat)
check('开关带运行时 pref（不是只有常量）', 'KEY_ON' in feat and 'dsh_on' in feat)
check('★ 关闭时外部入口全部为假：enabled() 同时看常量与 pref',
      'DshFeature.ENABLED) return false' in bridge and 'KEY_ON' in feat)

# 事件名与上游同名（抽查；这是"能互通"的合同）
ev = read(os.path.join(DSH, 'DshEvent.java'))
for name in ('turn/start', 'step/start', 'assistant/message', 'tool/call', 'tool/result',
             'sandbox/mode', 'approval/policy', 'request/header', 'user/message'):
    check('事件名与上游同名：%s' % name, ('"%s"' % name) in ev)

# 消息 content part 的形状（tool-call / tool-result / reasoning）
ses = read(os.path.join(DSH, 'DshSession.java'))
check('assistant 消息含 reasoning part', '"reasoning"' in ses)
check('assistant 消息含 tool-call part', '"tool-call"' in ses)
check('tool/result 事件含 tool-result part', '"tool-result"' in ses)
check('tool/result 事件带 isError', '"isError"' in ses)

# 权限闸只有一处
pol = read(os.path.join(DSH, 'DshPolicy.java'))
reg = read(os.path.join(DSH, 'DshToolRegistry.java'))
check('★ 路径闸只有一处（DshPolicy.resolve）', 'public String resolve(' in pol)
check('★ 执行前统一过权限闸', 'policy.allows(name)' in reg)
check('三档命名与上游一致', 'read-only' in pol and 'workspace-write' in pol and 'danger-full-access' in pol)
check('工具实现不许自己拼 File(相对路径) —— 路径参数的四个工具都走 policy.resolve',
      read(os.path.join(DSH, 'DshTools.java')).count('policy.resolve(') >= 4)

# 工具集
tools = read(os.path.join(DSH, 'DshTools.java'))
for t in ('"list"', '"read"', '"write"', '"web_fetch"', '"skill"', '"todo"', '"now"'):
    check('内置工具：%s' % t, t in tools)

# agent loop 的两个保险丝 + 事件落盘
loop = read(os.path.join(DSH, 'DshAgentLoop.java'))
check('maxSteps 保险丝', 'maxSteps' in loop and 'stoppedByLimit' in loop)
check('maxTokens 上限', 'maxTokens' in loop)
check('工具调用与结果都落盘成事件', 'session.toolCall(' in loop and 'session.toolResult(' in loop)
check('任何异常路径都补 turn/end', 'TURN_END.equals(last)' in loop)

# 日志与压缩
log = read(os.path.join(DSH, 'DshSessionLog.java'))
check('会话日志追加式落盘', 'appendLine' in log or 'append(' in log)
check('坏行跳过（进程被杀不留死会话）', 'DshEvent.fromJsonLine(line)' in log and 'if (e != null)' in log)
comp = read(os.path.join(DSH, 'DshCompaction.java'))
check('压缩先剪工具结果、后丢老消息', 'TOOL_HEAD_CHARS' in comp and 'blockEnd' in comp)
check('压缩不动 system', '"system".equals(msgs.get(sysEnd).role)' in comp)

# 别把 harness 硬塞进老的对话链路（纪律：分流点唯一）
check('没有改 ApiClient（老链路零改动）',
      'dsh' not in read(os.path.join(SRC, 'ApiClient.java')).lower())
check('没有改 ChatRouter（分流点仍只有一处）',
      'dsh' not in read(os.path.join(SRC, 'ChatRouter.java')).lower())

# ==================== [C] 文档 ====================

print()
print('=== [C] 文档与诚实边界 ===')
check('设计与实现文档存在', os.path.isfile(DOC))
doc = read(DOC)
check('文档写清"契约移植"而不是"塞 Node"', '契约移植' in doc)
check('文档写明上游版本', '0.1.5' in doc)
check('★ 文档有诚实边界一节', '诚实边界' in doc)
check('★ 诚实边界包含"没有真机验证"', '没有真机验证' in doc)
check('文档记录事件模型的事实来源（不是猜的）', 'session.v3' in doc)

print()
if fail == 0:
    print('DSH 移植断言通过：%d 项全绿（真编真跑 %d 条）' % (ok, n_ok))
    sys.exit(0)
print('DSH 移植断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
