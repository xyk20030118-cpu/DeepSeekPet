# -*- coding: utf-8 -*-
"""纯本地 · 自定义发言 / 纯本地也能对话 / 性格预设可编辑 —— 源码与产物断言。

用户 2026-10-07 原话（拆成四条）：
  ①「互动对话冷却这个选项，如果选择纯本地此时对话功能无法使用，保留纯本地模式的对话功能」
  ②「添加互动时候桌宠发言」（澄清后：**做成开关，关闭=纯本地，打开=调用 api**）
  ③「纯本地增加自定义发言的选项，在纯本地模式的时候可以自定义各个场景的宠物说话发言」
  ④「增加性格预设的编辑功能，可随时修改当前预设下的性格提示词」

判据都钉**语义**（"这条路真的走本地台词"、"开关真的同一个 pref"），
不钉"源码里有这句话"。反向破坏见 reverse_check_local_lines.py。
"""
import io
import os
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
MANIFEST = r'D:\petport_lab\petbuild\AndroidManifest.xml'

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释，只留代码（注释里的反例说明不该让"不该出现 X"误报）。"""
    out = []
    i, n, in_block = 0, len(src), False
    while i < n:
        if in_block:
            j = src.find('*/', i)
            if j < 0:
                break
            i = j + 2
            in_block = False
            continue
        if src.startswith('/*', i):
            in_block = True
            i += 2
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


def method_body(src, sig):
    """取出某个方法的**花括号配对**出来的方法体。

    ★ 别用"非贪婪匹配到第一个 `\\n    }`"那种正则：方法里有嵌套块/匿名类时
      它会提前或延后收口，抓进来的其实是别的方法 —— 于是"这个方法里有没有 X"
      这类断言就开始说谎（本轮实测：把 editLocalLines 里的按钮算进了预设弹窗）。
    """
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('{', i)
    if j < 0:
        return ''
    depth, k = 0, j
    while k < len(src):
        c = src[k]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[j + 1:k]
        k += 1
    return ''


def dev_apk():
    sys.path.insert(0, HERE)
    from artifacts import dev_apk as f
    return f()


def run(cmd, cwd=None):
    """跑一个子进程并拿到 (退出码, 输出) —— 给"设备脚本 --selftest"那类判据用。"""
    import subprocess
    r = subprocess.run(cmd, cwd=cwd or HERE, capture_output=True)
    return (r.returncode,
            r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace'))


ll = read(os.path.join(SRC, 'LocalLines.java'))
llc = code_only(ll)
pf = read(os.path.join(SRC, 'Prefs.java'))
pfc = code_only(pf)
fs = code_only(read(os.path.join(SRC, 'FloatingService.java')))
ca = code_only(read(os.path.join(SRC, 'ChatActivity.java')))
ma = read(os.path.join(SRC, 'MainActivity.java'))
mac = code_only(ma)
reg = code_only(read(os.path.join(SRC, 'SceneRegistry.java')))
pv = code_only(read(os.path.join(SRC, 'PetView.java')))

print('=== 1. 场景表：与动作清单**一一对应** + 留空退回内置（LocalLines，纯类）===')
check('零 Android 依赖（能进 fpstest 的纯类断言）',
      'import android' not in llc)
# ★★ 轮次㊲（用户要求「对话台词选项同步动作选项（动作有那些选项 台词就有哪些选项）」）：
#   场景表**从 SceneRegistry 派生** ⇒ 两张表不可能再各说各话（加场景自动多一行）。
check('★★ 场景表 = 动作清单（派生自 SceneRegistry.allIds，不是手抄一份）',
      'public static final String[] IDS = SceneRegistry.allIds();' in llc)
check('★ 中文名也派生（labelsOf(IDS) ⇒ 改场景名只改一处）',
      'public static final String[] LABELS = labelsOf(IDS);' in llc)
check('场景 id 与中文名一一对应（长度断言搬到 fpstest 逐条验）',
      'IDS =' in llc and 'LABELS =' in llc)
# ★ 历史 prefs 键**必须保住**：改了字面量 = 用户写过的台词静默消失
for s in ('TAP', 'TAP_ANNOYED', 'LIFT', 'PROACTIVE'):
    check('历史键常量 %s 仍在（prefs 键后缀不能改）' % s, ('String %s =' % s) in llc)
check('★ 四个历史键各有映射（动作场景 id → 老键名）',
      all(k in llc for k in ('SceneRegistry.CLICK.equals(scene)) return TAP',
                               'SceneRegistry.INTERACT.equals(scene)) return TAP_ANNOYED',
                               'SceneRegistry.DRAG.equals(scene)) return LIFT',
                               'SceneRegistry.EV_WHISPER.equals(scene)) return PROACTIVE')))
check('★ 取 prefs 时走 keyOf（键名映射只在那一处做）',
      pfc.count('LocalLines.keyOf(scene)') == 2,      # 读 + 写**两处都要**（只改一处仍会丢台词）
      'keyOf 出现 %d 次' % pfc.count('LocalLines.keyOf(scene)'))
check('CHAT 场景仍是删除状态（打字入口只走 API）',
      'String CHAT =' not in llc and 'CHAT_BUILTIN' not in llc)
check('★ 双击 / 长按这次是**真会说话**才列出来的（不再是"写了不生效"的入口）',
      'saySceneLine(SceneRegistry.DOUBLE_TAP);' in fs
      and 'saySceneLine(SceneRegistry.LONG_PRESS);' in fs)
check('★ 部位 → 台词场景的映射在纯类里（head/body/tail）',
      'public static String sceneForPart(String part)' in llc
      and 'return SceneRegistry.TAP_HEAD;' in llc)
# ★ 光有映射不够：单击分支**必须真的用它**取台词（反向验证实测：把这一句换成直接
#   pickTapQuip()，上面那条断言照样绿 —— 那就成了"映射写了没人用"）。
check('★ 单击分支真的用部位映射取台词（不是都读"点一下"那一套）',
      'LocalLines.sceneForPart(part)' in fs)
# ★★ 三级回退（顺序即语义）：部位自定义 → 「点一下」自定义 → 内置池。
#   中间那一级是为了**不动老用户的数据**（他们早给"点一下"写过台词，不接这一级
#   就会"突然听不到"——那是回退，不是新功能）。判据必须钉住"真的读了第一级"。
check('★★ 部位自定义真的被读（三级回退的第一级）',
      'String mine = pickLocalLineOnly(scene);' in fs and 'if (mine != null) return mine;' in fs)
check('★★ 第二级：退回「点一下」的自定义（老用户的台词不会突然听不到）',
      # ★ 必须**计数**：同一句在 pickTapQuip() 里也有一份（那是给"认不出的部位 / 连戳"用的），
      #   只查 `in fs` 的话，把部位这条回退删掉照样绿 —— 反向验证实测抓到的假绿。
      fs.count('return pickLocalLine(SceneRegistry.CLICK, tapPoolByHour());') >= 2,
      '出现 %d 次（pickTapQuip + pickPartQuip 的回退）'
      % fs.count('return pickLocalLine(SceneRegistry.CLICK, tapPoolByHour());'))
# ★ 语义：解析必须**去重**（同一句写两遍会让出现概率翻倍，用户看不出原因）
check('解析去重（LinkedHashSet）', 'LinkedHashSet<String>' in llc)
check('解析丢掉空行', "if (line.length() == 0) continue;" in llc)
check('单句超长截断', 'line.substring(0, MAX_LINE_LEN)' in llc)

print()
print('=== 1.5 ★ 每个场景都要有**说话出口**（台词版的出口表）===')
# ★ 与 verify_scene_exits.py 的"动作出口表"是同一套思路：清单里的每个场景都必须有人触发。
#   没有出口的场景 = 用户写了台词却永远听不到（比"没有这个选项"更糟）。
LINE_EXITS = [
    ('idle',       fs, 'saySceneLine(SceneRegistry.IDLE)'),
    ('click',      fs, 'pickLocalLine(SceneRegistry.CLICK'),
    ('interact',   fs, 'pickLocalLine(SceneRegistry.INTERACT'),
    ('drag',       fs, 'pickLocalLine(SceneRegistry.DRAG'),
    ('roam',       fs, 'saySceneLine(SceneRegistry.ROAM)'),
    ('turn',       fs, 'saySceneLine(SceneRegistry.TURN)'),
    ('dock',       fs, 'pickLocalLine(SceneRegistry.DOCK'),
    ('pool',       pv, 'onSceneLine(SceneRegistry.POOL)'),
    ('hour',       fs, 'pickLocalLine(SceneRegistry.HOUR'),
    ('festival',   pv, 'onSceneLine(SceneRegistry.FESTIVAL)'),
    ('tap_head',   llc, 'return SceneRegistry.TAP_HEAD;'),
    ('tap_body',   llc, 'return SceneRegistry.TAP_BODY;'),
    ('tap_tail',   llc, 'return SceneRegistry.TAP_TAIL;'),
    ('double_tap', fs, 'saySceneLine(SceneRegistry.DOUBLE_TAP)'),
    ('long_press', fs, 'saySceneLine(SceneRegistry.LONG_PRESS)'),
    ('balance',    fs, 'saySceneLine(SceneRegistry.EV_BALANCE)'),
    ('whisper',    fs, 'pickLocalLine(SceneRegistry.EV_WHISPER'),
    ('workStatus', fs, 'saySceneLine(SceneRegistry.EV_WORK)'),
]
scene_ids = sorted(set(re.findall(r'public static final String [A-Z_]+ = "([A-Za-z_]+)";', reg)))
check('场景清单解析出来（18 个）', len(scene_ids) == 18, ' / '.join(scene_ids))
check('★ 说话出口表覆盖**每一个**场景（少一个就红）',
      sorted(r[0] for r in LINE_EXITS) == scene_ids,
      '缺=%s 多=%s' % (sorted(set(scene_ids) - set(r[0] for r in LINE_EXITS)),
                      sorted(set(r[0] for r in LINE_EXITS) - set(scene_ids))))
for sid, blob, needle in LINE_EXITS:
    check('说话出口：%-10s' % sid, needle in blob, needle)
check('★ 台词出口也走 PetView → 服务层的回调（pool/festival 只在素材层内部发生）',
      'public void setSceneLineSink(SceneLineSink s)' in pv
      and 'petImage.setSceneLineSink(' in fs)
# ★ 判据要**带上条件**：只查 `onSceneLine(POOL)` 这种子串，把条件改成 `else if (false)`
#   照样绿（反向验证实测）—— 那就成了"回调写了永远不触发"。
check('★ 素材层的两个回调**带真实条件**（不是 if(false) 那种摆设）',
      'else if (isIdle && rotateIdle && !actionPlaying) sceneLineSink.onSceneLine(SceneRegistry.POOL);' in pv
      and 'if (seasonalHit) sceneLineSink.onSceneLine(SceneRegistry.FESTIVAL);' in pv)

print()
print('=== 2. 自定义台词：自定义优先、留空用内置（FloatingService）===')
check('有统一的取词入口 pickLocalLine(scene, builtin)',
      'private String pickLocalLine(String scene, String[] builtin)' in fs)
# ★ 顺序语义：先读用户自定义、挑得到就用；挑不到才退回内置池
m = re.search(r'private String pickLocalLine\(String scene, String\[\] builtin\) \{(.{0,900})',
              fs, re.S)
_seg = m.group(1) if m else ''
# ★ 判据改成"**优先级是不是走那个纯函数**"：
#   原先比的是"两行代码谁先出现"，而反向验证证明它挡不住"自定义分支永远不返回"
#   （顺序没变、行为却变了）。优先级现在住在 LocalLines.pickWithCustom 里，
#   fpstest 用行为断言把它钉死；这里只钉"服务真的调它、没有自己另写一份"。
check('自定义优先走纯函数 pickWithCustom（单一来源，行为可断言）',
      bool(m) and 'LocalLines.pickWithCustom(custom, builtin, Math.random())' in _seg)
# ★ 轮次㊲：场景表改成动作清单之后，逐场景的"有没有接上自定义"由上面 1.5 的**出口表**统一钉
#   （那里覆盖 18 个场景；这里不再重复列一份手抄清单 —— 手抄清单正是上一轮漏掉 8 个场景的原因）。

print()
print('=== 3. 纯本地只管"互动反应"；**打字发消息的入口一律只走 API**（用户定稿口径）===')
# ★ 用户 2026-10-07 连续纠正两次，最终口径：
#   「纯本地模式…双击桌宠的聊天框还是本地 → 双击聊天框聊天改为**只走 api** 路线」
#   ⇒ 纯本地只作用于桌宠自己的互动反应（点/拖/长按/双击/贴边/整点/主动搭话）；
#     **凡是打字发消息的入口都只走 API**：双击桌宠弹出的输入条 + 完整对话窗口。
m2 = method_body(fs, 'private void askQuestion(final String question)')
check('定位到 askQuestion', bool(m2))
check('★ 双击桌宠的输入条**不读本地台词**（不得出现 pickLocalLine/LocalLines）',
      bool(m2) and 'pickLocalLine' not in m2 and 'LocalLines' not in m2,
      'pickLocalLine=%s LocalLines=%s'
      % ('pickLocalLine' in m2, 'LocalLines' in m2))
check('★ 输入条也不再看 localOnly()（纯本地不拦它）',
      bool(m2) and 'localOnly()' not in m2)
check('输入条缺"能说话的对话后端"时提示去配（而不是悄悄改成本地台词）',
      # ★★ 轮次64（对话接线）：这一条原来钉的是 `Prefs.hasApiKey(this)` 那句 ——
      #    现在判据统一走 `ChatRouter.ready()`：**在线后端要 Key，本地模型不要**。
      #    要守的性质没变，而且更强了：缺的是"后端"，出路有两条（填 Key / 选本地模型）。
      bool(m2) and '请先在 App 里设置 API Key' in m2
      and '本地模型' in m2 and 'chatBackendReady()' in m2)
m3 = method_body(ca, 'private void onSend()')
check('定位到 ChatActivity.onSend', bool(m3))
check('★ 聊天窗口不读本地台词（不得出现 LocalLines / interactLocalOnly）',
      bool(m3) and 'LocalLines' not in m3 and 'interactLocalOnly' not in m3,
      '出现 LocalLines=%s interactLocalOnly=%s'
      % ('LocalLines' in m3, 'interactLocalOnly' in m3))
check('★★ 聊天窗口走的是**那条唯一通路** ChatRouter（在线后端下它落到 ApiClient.chat）',
      # ★★ 轮次64：原来是直接 `ApiClient.chat(`。现在分流点在 ChatRouter，
      #    所以"API 是唯一通路"要换个说法守：**聊天窗口不许自己直连哪个后端**，
      #    必须走那条统一通路（本地后端下才可能不落到 ApiClient）。
      'ChatRouter.chat(' in ca and 'ApiClient.chat(' not in ca)
check('聊天窗口缺后端时提示去配（填 Key 或选本地模型）',
      '请先在 App 里设置 API Key' in ca and '本地模型' in ca)
# 反向：互动反应**必须**仍然是本地台词（别把纯本地整个删掉）
check('互动反应仍走本地台词（点一下 / 拖动 / 贴边 / 主动搭话 等）',
      all(s in fs for s in ('pickLocalLine(SceneRegistry.CLICK, tapPoolByHour())',
                            'pickLocalLine(SceneRegistry.INTERACT, annoyed)',
                            'pickLocalLine(SceneRegistry.DRAG, quips)',
                            'pickLocalLine(SceneRegistry.DOCK, pool)',
                            'pickLocalLine(SceneRegistry.EV_WHISPER, pool)')))

print()
print('=== 3.5 ★ 启动不强制填 Key：没 Key ⇒ 默认本地模式（用户 2026-10-11 要求）===')
# 用户原话：「把打开软件（没有输入 apikey 的情况下）弹出的 apikey 输入窗口取消掉，
#           不强制输入，默认本地模式」。
create = method_body(mac, 'protected void onCreate(')
check('定位到 MainActivity.onCreate', bool(create))
check('★★ 启动**不再**弹 API Key 窗（onCreate 里没有 showApiKeyDialog 调用）',
      bool(create) and 'showApiKeyDialog' not in create)
check('★★ 没 Key ⇒ 自动进纯本地模式（互动发言关）',
      'if (!Prefs.hasApiKey(this)) Prefs.setInteractSpeakEnabled(this, false);' in create)
check('★ onCreate 里**只有那一处** Key 判断（换形象那条路不再被挡）',
      create.count('Prefs.hasApiKey(this)') == 1,
      '出现 %d 次' % create.count('Prefs.hasApiKey(this)'))
check('★ 换形象（ACTION_PICK_IMAGE）照样能走（分支仍在）',
      'Prefs.ACTION_PICK_IMAGE.equals(getIntent().getAction())' in create)
check('★ 给一句**不打断**的提示（Toast）而不是弹窗',
      'maybeHintApiKeyOnce();' in create
      and 'private void maybeHintApiKeyOnce()' in mac
      and '还没填 API Key：先用本地模式陪你' in mac)
dlg = method_body(mac, 'private void showApiKeyDialog()')
check('★ Key 弹窗仍然存在（设置页「API Key」那条路要能填）', bool(dlg))
check('★★ 弹窗**可取消**了（不再 setCancelable(false)）',
      bool(dlg) and '.setCancelable(true)' in dlg and '.setCancelable(false)' not in dlg)
check('★★ 「取消」只是关掉，**不再 finish() 退出 App**',
      bool(dlg) and 'setNegativeButton("取消", null)' in dlg and 'finish()' not in dlg)
check('★ 保存按钮文案不再暗示"必须填完才能开始"（"保存并开始" → "保存"）',
      bool(dlg) and 'setPositiveButton("保存"' in dlg)

# ★ 设备脚本（启动路径上的 UX，源码级断言只能证"没人调弹窗"）
ui = os.path.join(HERE, 'verify_nokey_ui.py')
check('verify_nokey_ui.py 存在（无 Key 启动的设备验证）', os.path.isfile(ui))
if os.path.isfile(ui):
    us = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          'DEV = V.DEV' in us and 'import verify_char_ui as V' in us)
    check('★ 判据不能拿「API Key」当弹窗标志（设置页那一行也有它 ⇒ 假探针）',
          "'输入' in t and '的 API Key' in t" in us)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_nokey_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out, 'rc=%d' % rc)

print()
print('=== 4. 互动发言开关：关=纯本地，开=调用 API（用户第 ② 条）===')
check('开关读写接口存在',
      'public static boolean interactSpeakEnabled' in pfc
      and 'public static void setInteractSpeakEnabled' in pfc)
# ★ 语义：它跟「互动对话冷却」是**同一个轴**（-1 = 纯本地），不能各写一份状态
check('开关与冷却共用同一个 pref（不是两份状态）',
      bool(re.search(r'public static boolean interactSpeakEnabled\(Context ctx\) \{\s*'
                     r'return !interactLocalOnly\(ctx\);', pfc)))
check('关掉 = 写入纯本地档', 'setInteractCdMs(ctx, INTERACT_LOCAL_ONLY);' in pfc)
# ★ 语义：打开时**恢复上次的非纯本地值**，而不是硬回默认 —— 否则用户设的冷却被悄悄改掉
check('打开时恢复上次的冷却值（记住非纯本地档）',
      'KEY_INTERACT_SPEAK_LAST' in pfc
      and bool(re.search(r'long last = sp\.getLong\(KEY_INTERACT_SPEAK_LAST, '
                         r'INTERACT_CD_DEFAULT_MS\);', pfc)))
check('设置页有「互动时桌宠发言」开关行',
      '互动时桌宠发言' in ma and 'setInteractSpeakEnabled(MainActivity.this' in ma)

print()
print('=== 5. 逐场景编辑页（用户第 ③ 条）===')
check('新增二级页常量 SECTION_LOCAL_LINES = 9',
      bool(re.search(r'SECTION_LOCAL_LINES = 9;', mac)))
# ★ 判据只钉"本地发言是 _GRP_NAMES 里的一项"，**不要求它是最后一项** ——
#   否则以后再加二级页（v7.0 就加了「形象与动作」）就会误报。
check('_GRP_NAMES 含「本地发言」',
      bool(re.search(r'_GRP_NAMES\s*=\s*\{[^}]*"本地发言"', ma)))
# ★ v8.0：陪伴板块的索引数组多了第 11 节（工作模式），不再钉字面。
_tabm = re.search(r'TAB_SECTIONS\s*=\s*\{(.*?)\};\n', ma, re.S)
_rows = []
if _tabm:
    for _r in re.findall(r'\{([0-9,\s]+)\}', _tabm.group(1)):
        _rows.append(set(int(x) for x in _r.replace(" ", "").split(",") if x))
check('陪伴板块含第 9 节（本地发言）',
      any(9 in r for r in _rows))
check('设置页有入口行',
      '纯本地台词：逐场景自定义' in ma and 'settingsSection = SECTION_LOCAL_LINES;' in mac)
check('第 9 节按场景表逐行渲染',
      bool(re.search(r'for \(final String scene : LocalLines\.IDS\)', mac)))
check('每行显示当前摘要（用内置 / 自定义 N 句）',
      'LocalLines.summary(Prefs.localLines(this, scene))' in mac)
check('编辑弹窗保存回 prefs + 通知服务',
      'Prefs.setLocalLines(MainActivity.this, scene, ' in mac
      and 'Prefs.reloadPet(MainActivity.this);' in mac)
check('编辑弹窗提供「恢复内置」（清空 = 用内置）',
      '恢复内置' in ma and bool(re.search(r'setLocalLines\(MainActivity\.this, scene, ""\);', mac)))
# ★ 语义：这个弹窗只能有**一个** neutral 按钮（两个会互相覆盖，见第 6 节）
m4 = method_body(mac, 'private void editLocalLines(final String scene)')
check('编辑弹窗只有一个 neutral 按钮（两个会互相覆盖）',
      bool(m4) and m4.count('setNeutralButton(') == 1,
      '实测 %d 个' % m4.count('setNeutralButton('))
check('写入前校验场景 id（脏键写进去就清不掉了）',
      bool(re.search(r'public static void setLocalLines\(Context ctx, String scene, String raw\) '
                     r'\{\s*if \(!LocalLines\.isValid\(scene\)\) return;', pfc)))

print()
print('=== 6. 性格预设可编辑（用户第 ④ 条 —— 真根因是按钮被覆盖）===')
# ★ 根因：AlertDialog 同一位置只能有一个按钮，原来**两个 setNeutralButton**，
#   后一个（从聊天记录生成）把前一个（编辑当前）整个覆盖 ⇒ 入口永远点不到。
m5 = method_body(mac, 'private void showPersonaDialog()')
pb = m5
check('定位到 showPersonaDialog', bool(m5))
check('★ 预设弹窗里不再有 neutral 按钮（编辑当前改放进列表，不会再被覆盖）',
      bool(m5) and 'setNeutralButton' not in pb,
      '实测 neutral 按钮 %d 个' % (pb.count('setNeutralButton') if m5 else -1))
check('列表里有「编辑当前预设」入口',
      '编辑当前预设' in pb and 'editPreset(idx, ps.get(idx)[0], ps.get(idx)[1]);' in pb)
check('原来的「新建 / 从聊天记录 / 导入 skill」都还在（没被删功能）',
      all(k in pb for k in ('新建性格预设', 'pickChatLogFile()', 'pickSkillFile()')))
check('编辑弹窗仍支持改名称 + 提示词并落盘',
      'nameEt' in ma and 'contentEt' in ma and 'Prefs.savePresets(MainActivity.this, ps)' in ma)

print()
print('=== 7. 产物：新类/新字符串进 dex（防"源码改了没重编"）===')
apk = dev_apk()
check('APK 存在', os.path.exists(apk), apk)
if os.path.exists(apk):
    with zipfile.ZipFile(apk) as z:
        raw = z.read('classes.dex')
    for lit in ('用内置台词',
                '纯本地台词：逐场景自定义', '互动时桌宠发言', '编辑当前预设', '恢复内置',
                '点一下', '被连戳（烦了）', '贴边吸附', '主动搭话'):
        check('dex 含「%s」' % lit, lit.encode('utf-8') in raw)
    for sym in ('LocalLines', 'pickLocalLine', 'parseLines',
                'interactSpeakEnabled', 'setInteractSpeakEnabled',
                'setLocalLines', 'editLocalLines', 'SECTION_LOCAL_LINES'):
        check('dex 含符号 %s' % sym, sym.encode('ascii') in raw)

print()
print('=' * 62)
if fails:
    print('失败 %d 项：%s' % (len(fails), '；'.join(fails[:5])))
    sys.exit(1)
print('ALL CHECKS PASSED')
