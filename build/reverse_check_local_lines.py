# -*- coding: utf-8 -*-
"""反向验证：纯本地发言 / 纯本地对话 / 预设编辑的断言**必须能抓到回潮**。

一个"永远返回 OK"的断言和"真的在守护"的断言，从输出上看一模一样。
所以每一项都**故意破坏一次**，确认 verify_local_lines.py 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里：中途异常也不能把源码留在被破坏的状态。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
VERIFY = os.path.join(HERE, 'verify_local_lines.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def run_fpstest():
    """跑 fpstest（行为级断言）。"""
    bat = os.path.join(HERE, 'fpstest', 'run.bat')
    r = subprocess.run(['cmd', '/c', bat], capture_output=True)
    return r.returncode


def break_and_check_fps(label, path, old, new):
    """破坏后要求 **fpstest** 变红（行为级判据，源码断言抓不到的那类）。"""
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_fpstest()
        write(path, orig)
        good = run_fpstest()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s fpstest 破坏后=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


def break_and_check(label, path, old, new):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []

print('=== 反向验证：纯本地发言 / 纯本地对话 / 预设编辑 ===')
print()

# ① ★ 双击桌宠的输入条又变成"本地台词"（用户定稿口径否掉的做法：打字入口只走 API）
#   ★ 锚点跟着轮次64 的分流改造走：那句 `!Prefs.hasApiKey(this)` 已经换成
#     `!chatBackendReady()`（在线要 Key、本地模型不要）。**性质没变**：
#     打字入口不许悄悄改成本地台词。
results.append(break_and_check(
    'A. 双击桌宠的输入条又走本地台词',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (!chatBackendReady()) {',
    '        if (localOnly()) {\n'
    '            showBubble(pickLocalLine(LocalLines.PROACTIVE, null), 5000);\n'
    '            return;\n'
    '        }\n'
    '        if (!chatBackendReady()) {'))

# ② ★ 给聊天框加回"本地分支"（用户明确否掉的做法：聊天框只能走 AI）
results.append(break_and_check(
    'B. 聊天框又变成读本地台词',
    os.path.join(SRC, 'ChatActivity.java'),
    '        if (!ChatRouter.ready(this)) {',
    '        if (Prefs.interactLocalOnly(this)) {\n'
    '            say("assistant", LocalLines.pick(LocalLines.CHAT_BUILTIN, 0.5), false);\n'
    '            return;\n'
    '        }\n'
    '        if (!ChatRouter.ready(this)) {'))

# ②b. ★★ 聊天窗口绕过唯一通路、自己直连某个后端（"API 是唯一通路"这条就废了）
results.append(break_and_check(
    'B2. 聊天窗口绕过 ChatRouter 直连后端',
    os.path.join(SRC, 'ChatActivity.java'),
    'ChatRouter.chat(',
    'ApiClient.chat('))

# ③ ★ 服务不再走"自定义优先"那个纯函数（自己在外面另挑内置）
results.append(break_and_check(
    'C. 服务绕过 pickWithCustom（自定义白写）',
    os.path.join(SRC, 'FloatingService.java'),
    'line = LocalLines.pickWithCustom(custom, builtin, Math.random());',
    'line = LocalLines.pick(builtin, Math.random());'))

# ④ 解析不去重（同一句写两遍 → 出现概率翻倍，看不出原因）
results.append(break_and_check(
    'D. 台词解析不去重',
    os.path.join(SRC, 'LocalLines.java'),
    'java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();',
    'java.util.List<String> out = new java.util.ArrayList<String>();'))

# ⑤ 开关打开时不恢复上次冷却（把用户设的 10 秒悄悄改成默认 2 秒）
results.append(break_and_check(
    'E. 开关打开时不恢复上次冷却值',
    os.path.join(SRC, 'Prefs.java'),
    'long last = sp.getLong(KEY_INTERACT_SPEAK_LAST, INTERACT_CD_DEFAULT_MS);',
    'long last = INTERACT_CD_DEFAULT_MS;'))

# ⑥ 场景校验去掉（脏 id 也能写 prefs，永远清不掉）
results.append(break_and_check(
    'F. setLocalLines 不校验场景 id',
    os.path.join(SRC, 'Prefs.java'),
    '        if (!LocalLines.isValid(scene)) return;      // 脏键写进去就再也清不掉了',
    ''))

# ⑦ 预设弹窗里又加回一个 neutral 按钮（"编辑当前"会再次被覆盖 —— 用户报的原样）
results.append(break_and_check(
    'G. 预设弹窗又用 neutral 按钮（编辑当前会被覆盖）',
    os.path.join(SRC, 'MainActivity.java'),
    '                .setNegativeButton("关闭", null)\n'
    '                .show();\n'
    '    }\n'
    '\n'
    '    /** 新建/编辑一个性格预设 */',
    '                .setNeutralButton("编辑当前", null)\n'
    '                .setNegativeButton("关闭", null)\n'
    '                .show();\n'
    '    }\n'
    '\n'
    '    /** 新建/编辑一个性格预设 */'))

# ⑧ 逐场景页不再按场景表渲染（只剩一行，等于功能没了）
results.append(break_and_check(
    'H. 逐场景页不再逐场景渲染',
    os.path.join(SRC, 'MainActivity.java'),
    'for (final String scene : LocalLines.IDS) {',
    'for (final String scene : new String[]{LocalLines.TAP}) {'))

# ⑨ ★ 纯函数里的优先级被破坏（"用户写的永远轮不到"）。
#    ★ 这一类**源码顺序断言抓不到**，只能靠行为断言 —— 所以这里跑 fpstest 而不是 verify。
results.append(break_and_check_fps(
    'I. pickWithCustom 优先级反了（内置优先）',
    os.path.join(SRC, 'LocalLines.java'),
    '        String c = pick(custom, r01);\n'
    '        return c != null ? c : pick(builtin, r01);',
    '        String b = pick(builtin, r01);\n'
    '        return b != null ? b : pick(custom, r01);'))

# ═══════════════ 轮次㊲：台词选项同步动作选项（用户要求）═══════════════
# ⑩ ★★ 场景表不再从动作清单派生（手抄一份 = 两张表各说各话，用户就是被这个坑到的）
results.append(break_and_check(
    'J. ★★场景表不再派生自动作清单（退回手抄一份）',
    os.path.join(SRC, 'LocalLines.java'),
    'public static final String[] IDS = SceneRegistry.allIds();',
    'public static final String[] IDS = {"tap", "dock"};'))

# ⑪ ★ 中文名不再派生（改场景名要改两处 ⇒ 迟早对不上）
results.append(break_and_check(
    'K. ★中文名不再派生（改场景名要改两处）',
    os.path.join(SRC, 'LocalLines.java'),
    'public static final String[] LABELS = labelsOf(IDS);',
    'public static final String[] LABELS = {"点一下", "贴边吸附"};'))

# ⑫ ★ 键名映射丢了（直接拿动作 id 当 prefs 键 ⇒ 用户写过的台词静默消失）
results.append(break_and_check(
    'L. ★历史 prefs 键映射丢了（老台词静默消失）',
    os.path.join(SRC, 'LocalLines.java'),
    '        if (SceneRegistry.CLICK.equals(scene)) return TAP;',
    '        if (false) return TAP;'))

# ⑬ ★ 取 prefs 时不走 keyOf（同上，只是换一处实现）
results.append(break_and_check(
    'M. ★取 prefs 时不走 keyOf',
    os.path.join(SRC, 'Prefs.java'),
    'KEY_LOCAL_LINE_PREFIX + LocalLines.keyOf(scene)',
    'KEY_LOCAL_LINE_PREFIX + scene'))

# ⑭ ★ 部位映射写错（戳头说成"戳尾"的话）
results.append(break_and_check(
    'N. ★部位 → 台词场景映射写错（戳头说成戳尾）',
    os.path.join(SRC, 'LocalLines.java'),
    'if ("head".equals(part)) return SceneRegistry.TAP_HEAD;',
    'if ("head".equals(part)) return SceneRegistry.TAP_TAIL;'))

# ⑮ ★ 部位台词不再读自己的自定义槽（退回"都读点一下那一套" —— 用户报的原样）
results.append(break_and_check(
    'O. ★部位台词不再读自己的自定义槽（第一级被短路）',
    os.path.join(SRC, 'FloatingService.java'),
    '        String mine = pickLocalLineOnly(scene);',
    '        String mine = null;'))

# ⑮b ★ 三级回退的**中间一级**被删（老用户写过的"点一下"台词突然听不到 = 回退）
results.append(break_and_check(
    'O2. ★三级回退丢掉中间一级（老用户台词突然听不到）',
    os.path.join(SRC, 'FloatingService.java'),
    '        return pickLocalLine(SceneRegistry.CLICK, tapPoolByHour());',
    '        return LocalLines.pick(tapPoolByHour(), Math.random());'))

# ⑯ ★ 某个场景的说话出口被删（写了台词却永远听不到）
results.append(break_and_check(
    'P. ★某个场景的说话出口被删（漫游）',
    os.path.join(SRC, 'FloatingService.java'),
    'saySceneLine(SceneRegistry.ROAM);',
    ';'))

# ⑰ ★ pool/festival 的回调被删（这两个场景只有素材层知道，不回调就永远不说话）
results.append(break_and_check(
    'Q. ★素材层的台词回调被删（pool / festival 永远不说话）',
    os.path.join(SRC, 'PetView.java'),
    'else if (isIdle && rotateIdle && !actionPlaying) sceneLineSink.onSceneLine(SceneRegistry.POOL);',
    'else if (false) sceneLineSink.onSceneLine(SceneRegistry.POOL);'))

# ═══════════════ 轮次㊴：启动不强制填 Key（用户要求"不强制输入，默认本地模式"）═══════════════
# ⑱ ★★ 启动又弹 Key 窗（用户报的原样：没 Key 连设置页都进不去）
results.append(break_and_check(
    'R. ★★启动又弹 API Key 窗（没 Key 进不去）',
    os.path.join(SRC, 'MainActivity.java'),
    '        maybeHintApiKeyOnce();',
    '        showApiKeyDialog();\n        maybeHintApiKeyOnce();'))

# ⑲ ★ 没 Key 时不再自动进纯本地（会去调 API ⇒ 一直失败）
results.append(break_and_check(
    'S. ★没 Key 时不再自动进纯本地',
    os.path.join(SRC, 'MainActivity.java'),
    '        if (!Prefs.hasApiKey(this)) Prefs.setInteractSpeakEnabled(this, false);',
    '        ;'))

# ⑳ ★ 弹窗又变成"不可取消"
results.append(break_and_check(
    'T. ★Key 弹窗又不可取消',
    os.path.join(SRC, 'MainActivity.java'),
    '                .setCancelable(true)',
    '                .setCancelable(false)'))

# ㉑ ★ 点「取消」又退出 App
results.append(break_and_check(
    'U. ★点「取消」又 finish() 退出 App',
    os.path.join(SRC, 'MainActivity.java'),
    '                .setNegativeButton("取消", null)',
    '                .setNegativeButton("取消", new DialogInterface.OnClickListener() {\n'
    '                    @Override public void onClick(DialogInterface d, int w) { finish(); }\n'
    '                })'))

# ㉒ ★ 换形象又被 Key 挡住（过度门控回潮）
results.append(break_and_check(
    'V. ★换形象又被 Key 挡住（过度门控回潮）',
    os.path.join(SRC, 'MainActivity.java'),
    '        if (Prefs.ACTION_PICK_IMAGE.equals(getIntent().getAction())) {',
    '        if (Prefs.ACTION_PICK_IMAGE.equals(getIntent().getAction())) {\n'
    '            if (!Prefs.hasApiKey(this)) { showApiKeyDialog(); return; }'))

print()
print('=' * 62)
done = [r for r in results if r is not None]
skipped = len(results) - len(done)
caught = sum(1 for r in done if r)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (caught, len(done)))
if skipped:
    print('  跳过 %d 项（锚点未命中）' % skipped)
if caught != len(done) or skipped:
    sys.exit(1)
print('ALL REVERSE CHECKS PASSED')
