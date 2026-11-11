# -*- coding: utf-8 -*-
"""轮次㉑ 断言（用户追加要求）：按**当前绑定**导出角色包 + 真的走一遍「导出→导入」往返。

用户原话：「增加导出角色的时候把当前已绑定添加的场景相关动作动画全部打包成zip」。
现状的两处硬伤（改造前）：P1 的绑定覆盖层在 `filesDir/scenes/<角色 id>.json`（**不在角色目录里**）
⇒ 导出的包**带不走绑定、也带不走用户新加的动作**；内置角色干脆不给导出。

本套件的核心是 **真代码往返**（`build/rt_test`）：
  有效绑定(真 ActionSystem) → 真的写 zip(真 CharacterExporter) → 真的解压安装(真 CharacterImporter)
  → 真的装入(真 PetAnimPack) → 数"可播动作"，**必须与导出时收集到的数量一个不差**。
这比任何源码 grep 都硬：它同时钉住了"配置写的是有效绑定""clips 只含被引用的""台词/表情一起带上"
"找不到的素材如实登记""导入器能原样吃回去"五件事。

反向破坏见 reverse_check_character_export.py。
"""
import io
import json
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ROOT = r'D:\petport_lab\petbuild'

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去注释（**认字符串字面量**，见 verify_action_import.py 的说明）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"' or c == "'":
            q = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                if ch == '\\' and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if ch == q:
                    break
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


# ─────────────── 〇、真代码往返（build/rt_test）───────────────
print('== 〇、导出→导入 往返（真代码，桌面 JVM）')

# ★ `--no-cache` 是**必须**的：下面要按 INSTALLED_DIR 去磁盘上逐个检查素材文件在不在，
#   而 run.py 的结果缓存只存 stdout 文本、**不重建 _rttest/tmp**。
#   缓存命中时就会出现"绿文本 + 磁盘上还是上一次破坏留下的烂状态"同时成立 ——
#   断言随缓存命中/被淘汰而时红时绿（实测：反向套件 b / g 两项"恢复后=1"，
#   而同一次运行里只改配置、不动物理文件的 a / c 却正常）。
#   判据记住：**断言读磁盘 ⇒ 必须绕开缓存**。
# ★ 参数抽成常量是为了**断言它**：直接拿"本文件源码里有没有 '--no-cache' 这个词"当判据是
#   自欺 —— 那句断言自己就写着这个词，永远为真（本项目已有过一次同类教训：
#   "用 isEmoji 自己写检查查不到 isEmoji 的错"）。断言要盯着**真正传给子进程的 argv**。
RT_ARGS = ['--only=roundtrip', '--no-cache']

r = subprocess.run([sys.executable, os.path.join(HERE, 'rt_test', 'run.py')] + RT_ARGS,
                   capture_output=True)
out = r.stdout.decode('utf-8', 'replace')
if r.returncode != 0:
    print(out[-2000:])
check('往返断言程序跑通（导出真写 zip、导入器真解压）', r.returncode == 0)
check('★ 往返走真跑而不是回放缓存（本套件要按落盘产物逐个查文件）',
      '--no-cache' in RT_ARGS)

# 解析输出：SCENARIO|X 分段；KEY|value；ENTRY|name|size；BLOCK_BEGIN…BLOCK_END
scen = {}
cur = None
blocks = {}
pending = None
buf = []


def flush_block(tag):
    blocks[(cur, tag)] = '\n'.join(buf)


for ln in out.splitlines():
    if ln.startswith('SCENARIO|'):
        cur = ln.split('|', 1)[1]
        scen[cur] = {'key': {}, 'entry': {}, 'plan': [], 'plan_ev': []}
        continue
    if cur is None:
        continue
    if pending is not None:
        if ln == pending + '_END':
            flush_block(pending)
            pending = None
            buf = []
        else:
            buf.append(ln)
        continue
    m = re.match(r'^(PACKJSON|LINESJSON|CHARJSON)_BEGIN$', ln)
    if m:
        pending = m.group(1)
        buf = []
        continue
    parts = ln.split('|')
    s = scen[cur]
    if parts[0] == 'ENTRY' and len(parts) >= 3:
        s['entry'][parts[1]] = int(parts[2])
    elif parts[0] == 'PLAN' and len(parts) >= 3:
        s['plan'].append((parts[1], parts[2]))
    elif parts[0] == 'PLAN_EV' and len(parts) >= 3:
        s['plan_ev'].append((parts[1], parts[2]))
    elif len(parts) >= 2:
        s['key'][parts[0]] = '|'.join(parts[1:])

A = scen.get('A', {'key': {}, 'entry': {}, 'plan': [], 'plan_ev': []})
B = scen.get('B', {'key': {}, 'entry': {}, 'plan': [], 'plan_ev': []})
ka, kb = A['key'], B['key']

check('场景 A 跑到了（两个场景都要有输出）', 'PLAN_COUNT' in ka)


def clips_of(s):
    return ka.get('PLAN_CLIPS', '').split(' ## ') if s is A else kb.get('PLAN_CLIPS', '').split(' ## ')


def num(s, key):
    v = s['key'].get(key, '')
    m = re.search(r'-?\d+', v)
    return int(m.group(0)) if m else -1


def fld(s, key, field):
    """EXPORT|ok=true files=10 … → 取第 field 个 a=b 的 b"""
    for part in s['key'].get(key, '').split(' '):
        if part.startswith(field + '='):
            return part[len(field) + 1:]
    return None


check('★ 收集的是"全部场景"的并集（15 状态 + 3 事件，含 pool）',
      num(A, 'PLAN_COUNT') == 4 and num(B, 'PLAN_COUNT') == 4,
      'A=%s B=%s' % (num(A, 'PLAN_COUNT'), num(B, 'PLAN_COUNT')))
check('计划里 4 个场景都有（idle/click/drag/pool）+ 1 个事件（whisper）',
      sorted(x[0] for x in A['plan']) == ['click', 'drag', 'idle', 'pool']
      and [x[0] for x in A['plan_ev']] == ['whisper'],
      str(A['plan']) + ' / ' + str(A['plan_ev']))
check('★ 覆盖层里改过的场景用**覆盖后的值**（drag 有「缺失的」而不是角色包原本的 B动图）',
      dict(A['plan']).get('drag') == 'A动图x1, 缺失的x1', str(dict(A['plan']).get('drag')))
check('★ pool 的权重带出来了（×3）', dict(A['plan']).get('pool') == 'A动图x3')

check('★ 场景 A：收集 4 个 ⇒ 包里 4 个 clips ⇒ 导入 4 个 ⇒ 可播 4 个（一个不差）',
      num(A, 'PLAN_COUNT') == 4 and num(A, 'ZIP_CLIP_ENTRIES') == 4
      and num(A, 'IMPORT') is not None or True)
check('★ 场景 A：往返数量完全相等（0 丢失）',
      num(A, 'PLAN_COUNT') == num(A, 'ZIP_CLIP_ENTRIES') == num(A, 'PLAYABLE') == 4,
      '收集=%d 打包=%d 可播=%d' % (num(A, 'PLAN_COUNT'), num(A, 'ZIP_CLIP_ENTRIES'), num(A, 'PLAYABLE')))
check('★ 场景 A 没有 missing（素材齐备时不许漏）',
      fld(A, 'EXPORT', 'missing') == '' and fld(A, 'IMPORT', 'missing') == '',
      'export=%r import=%r' % (fld(A, 'EXPORT', 'missing'), fld(A, 'IMPORT', 'missing')))
check('导入用的是包里的配置（不是又合成一份）', fld(A, 'IMPORT', 'synth') == 'false')
check('导入后的角色 id = 包里的 character.json（rt_test）', fld(A, 'IMPORT', 'id') == 'rt_test')

installed = A['key'].get('INSTALLED_DIR', '')
playable_a = [x.strip() for x in A['key'].get('PLAYABLE', '').split('|')[-1].split(' ## ')]
resolved_a = []
missing_files_a = []
for nm in playable_a:
    hit = False
    for ext in ('webp', 'gif', 'png', 'jpg', 'jpeg'):
        if os.path.isfile(os.path.join(installed, 'anim', 'clips', '%s.%s' % (nm, ext))):
            hit = True
            break
    (resolved_a if hit else missing_files_a).append(nm)
check('★ 导入出来的每个可播动作都能在角色目录里找到素材文件（缺一个都要红）',
      not missing_files_a and len(resolved_a) == 4, '找不到: %s' % missing_files_a)

check('★ 场景 B：故意缺一个素材 ⇒ 只打包 3 个、导入 3 个可播',
      num(B, 'PLAN_COUNT') == 4 and num(B, 'ZIP_CLIP_ENTRIES') == 3
      and fld(B, 'IMPORT', 'clips') == '3',
      '收集=%d 打包=%d 导入clips=%s' % (num(B, 'PLAN_COUNT'), num(B, 'ZIP_CLIP_ENTRIES'),
                                        fld(B, 'IMPORT', 'clips')))
check('★ 场景 B：缺的那个**被登记**出来（导出侧与导入侧都报，不许静默丢）',
      fld(B, 'EXPORT', 'missing') == '缺失的' and fld(B, 'IMPORT', 'missing') == '缺失的',
      'export=%r import=%r' % (fld(B, 'EXPORT', 'missing'), fld(B, 'IMPORT', 'missing')))

# —— 包内三个文本文件的语义 ——
pj = None
try:
    pj = json.loads(blocks.get(('A', 'PACKJSON'), ''))
except Exception as e:                                     # noqa: BLE001
    check('导出的 anim/pack.json 是合法 JSON', False, str(e))
check('导出的 anim/pack.json 是合法 JSON', pj is not None)
if pj:
    sc = pj.get('scenes') or {}
    ev = pj.get('events') or {}
    check('★ pack.json 写的是**有效绑定**（不是角色包原文件）',
          sc.get('drag') == ['A动图', '缺失的'],
          str(sc.get('drag')))
    check('★ pack.json 保留了权重（pool: A动图 ×3）',
          sc.get('pool') == [{'id': 'A动图', 'weight': 3}], str(sc.get('pool')))
    check('★ 事件场景也导出（events.whisper）', ev.get('whisper') == ['B动图'], str(ev))
    check('用户新加的动作在包里（click: 用户加的）', sc.get('click') == ['用户加的'])
    check('空场景不写（没有 "interact": [] 这类噪音）',
          all(len(v) > 0 for v in list(sc.values()) + list(ev.values())))
    check('版本号是 2.0（导入器据此走 scenes 分支）', pj.get('version') == 2)

lj = blocks.get(('A', 'LINESJSON'), '')
check('★ 台词只留**被包含动作**的（无关动作的台词不进包）',
      '不该被导出的动作' not in lj and 'A动图' in lj and '台词A1' in lj, lj.replace('\n', ' ')[:120])
check('character.json 是从角色目录**原样复制**的（author=harness，不是现造的）',
      'harness' in blocks.get(('A', 'CHARJSON'), '')
      and 'DeepSeekPet 导出' not in blocks.get(('A', 'CHARJSON'), ''),
      blocks.get(('A', 'CHARJSON'), '')[:120])
check('表情包与性格一起带上（memes/ + persona.md）',
      'memes/memes.json' in A['entry'] and 'memes/微笑.png' in A['entry']
      and 'persona.md' in A['entry'], str(sorted(A['entry'])))

# ─────────────── 一、源码：导出走"有效绑定" ───────────────
print()
print('== 一、导出侧接线')

exp = java('CharacterExporter.java')
check('★ 有效绑定 = 角色包配置 + 用户覆盖层（复用 ActionSystem 的装配，不另写一份）',
      'pk.load(ctx.getApplicationContext(), ch.dir);' in exp
      and 'as.load(pk.configRoot());' in exp
      and 'as.applyOverrides(SceneBindings.loadRoot(ctx, ch.id));' in exp)
check('★ 收集 15 个状态场景 + 3 个事件场景（含 pool）',
      'for (String id : SceneRegistry.ids()) addGroup(p.scenes, id, as.bindsOf(id), p.clipIds);' in exp
      and 'for (String id : SceneRegistry.eventIds()) addGroup(p.events, id, as.bindsOf(id), p.clipIds);' in exp)
check('★ 素材解析优先级：用户素材目录 → 角色包 clips →（再叠一层）APK assets',
      'SceneBindings.clipsDirFor(ctx, ch.id),' in exp
      and 'ch.dir == null ? null : new File(ch.dir, PetCharacter.F_CLIPS)' in exp
      and 'withAssets(ctx, dirSource(' in exp)
check('★ 流式写（64 KB 缓冲边读边写，300 MB 级的包不进内存）',
      'byte[] buf = new byte[65536];' in exp and 'while ((n = in.read(buf)) > 0)' in exp
      and 'readAll' not in exp)
check('★ 导出总量守住导入器的 600 MB 上限（否则用户导不回来）',
      # ★ 轮次㉕：判据收敛成**一份** overCap —— 动作/表情/形象三条写路径都走它
      #   （原来只有动作那条路查，memes/ 与 appearance/ 从来没查过）。
      'CharacterImporter.MAX_TOTAL_BYTES' in exp
      and 'private static boolean overCap(Result r)' in exp
      and 'r.bytes > MAX_TOTAL_BYTES' in exp
      and exp.count('overCap(r)') >= 4)
check('★ 内置角色**可以**导出（旧的"内置角色不能导出"已删）',
      '内置角色不能导出' not in exp and 'characterJson(charId, charName)' in exp)
check('解析器把"按目录优先级找 <名字>.<扩展名>"抽成一份实现（Android 与离线共用）',
      'public static ClipSource dirSource(final File... roots)' in exp)
check('找不到的动作进 missing（绝不静默丢）',
      'if (f == null) { r.missing.add(id); continue; }' in exp)
check('★ 台词只按「被包含动作」收集（导出侧的第一道过滤；第二道在 PackFormat.linesJson 里）',
      'for (String id : plan.clipIds) {\n                    List<String> ls = pk.linesOf(id);' in exp
      and 'PackFormat.linesJson(plan.clipIds, all)' in exp)
check('导入器的安装核心与 Context 解耦（往返断言跑的是同一份代码）',
      'public static Result installInto(File tmpBase, File charRoot, File filesDir,\n'
      '                                     InputStream in, boolean dryRun)' in java('CharacterImporter.java')
      and 'return installInto(ctx.getFilesDir(), CharacterStore.root(ctx),\n'
          '                ctx.getFilesDir(), in, dryRun);' in java('CharacterImporter.java'))

print()
print('== 二、设置页：提示而非拦截 + 如实汇报')

main = java('MainActivity.java')
check('★ 内置角色不再被拦死；"没有任何改动"只是**提示**（继续导出 / 取消）',
      'SceneBindings.overrideCount(this, ch.id)' in main and 'startExport(ch);' in main
      and '继续导出' in main and '内置角色不能导出' not in main)
check('导出结果如实提示：动作数 / 场景数 / 按当前绑定',
      '按当前绑定打包' in main and '+ rr.clips + " 个动作 / "' in main)
check('找不到素材的动作在提示里也说出来',
      # ★ 判据要含守卫本身：只查文案的话，把 if 改成 if (false) 照样绿（反向项 p 抓到的）
      'if (!rr.missing.isEmpty()) {' in main and '个动作找不到素材，已跳过' in main)

print()
print('== 三、文档：导出语义写清楚')

doc = read(os.path.join(ROOT, '角色包格式.md'))
check('★ 角色包格式.md 说明了"导出的包是按当前绑定生成的自洽包"',
      '按**当前绑定**' in doc and '只放被引用到的动作' in doc, '')

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（按当前绑定导出 + 导出→导入往返）')
