# -*- coding: utf-8 -*-
"""断言：**形象随角色包导出/导入**（待办 0m ①）。

背景（用户 2026-10-08 定下的下一步）：
  P5 把形象收进了**覆盖层** `filesDir/appearance/<角色 id>/`，而导出器只打角色目录
  ⇒ **把包发给别人，对方拿到的角色没有脸**（他用的是自己上一次的形象或内置）。
  本轮把形象整棵搬进包里的 `appearance/`，导入时再搬回覆盖层。

本套件钉四件最容易错的事：
  A. 导出：形象**真的**进了包（目录名与运行期同名同结构，路径只认 AppearanceStore）；
  B. 导入：形象落回**覆盖层**（不是角色目录），且**包里没有就别动对方已有的形象**；
  C. ★ 辅助目录不许被当成动作：导入器全树扫动作时必须跳过 `appearance/`
     （漏了 → 用户会在动作库里看到自己的脸，往返断言的"动作数一个不差"也会红）；
  D. 界面/文档如实说（"已带上形象" / "这个角色没有自定义形象"）。

行为判据用 `build/rt_test --only=roundtrip`（**真代码 + 真文件**：导出真的写 zip、
导入真的解压落盘，且形象装进一个**全新的** files 目录 ⇒ 证明"脸"是包带过来的）。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
EXP = os.path.join(SRC, 'CharacterExporter.java')
IMP = os.path.join(SRC, 'CharacterImporter.java')
PACK = os.path.join(SRC, 'PackFormat.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
DOC = os.path.join(ROOT, '角色包格式.md')
RT_ARGS = ['--only=roundtrip', '--no-cache']

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(s):
    """剥掉注释（只留代码与字符串）—— 断言不许被注释里的字样骗过。"""
    out, i, n, blk = [], 0, len(s), False
    while i < n:
        if blk:
            j = s.find('*/', i)
            if j < 0:
                break
            i = j + 2
            blk = False
            continue
        if s.startswith('/*', i):
            blk = True
            i += 2
            continue
        if s.startswith('//', i):
            j = s.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        if s[i] == '"':
            out.append(s[i]); i += 1
            while i < n and s[i] != '"':
                if s[i] == '\\':
                    out.append(s[i]); i += 1
                if i < n:
                    out.append(s[i]); i += 1
            if i < n:
                out.append(s[i]); i += 1
            continue
        out.append(s[i]); i += 1
    return ''.join(out)


exp = code_only(read(EXP))
imp = code_only(read(IMP))
pack = code_only(read(PACK))
main = code_only(read(MAIN))

print('=== A. 导出：形象真的进了包 ===')
check('结果里有"带走了几个形象文件"',
      'public int appearanceFiles;' in exp and 'public boolean hasAppearance()' in exp)
check('★ 写包入口多了一个形象目录参数（离线往返断言走同一个入口）',
      re.search(r'public static Result write\(OutputStream out, Plan plan, ClipSource src, '
                r'PetAnimPack pk,\s*File charDir, File appearanceDir,', exp) is not None)
check('★ 包内目录名走常量 PackFormat.APPEARANCE_DIR（不写字面量 "appearance"）',
      'PackFormat.APPEARANCE_DIR, r)' in exp and '"appearance"' not in exp)
check('形象文件数如实统计（= 写包前后的文件数差）',
      'r.appearanceFiles = r.files - before;' in exp)
check('★ 形象目录只认 AppearanceStore.dirIn（"形象在哪"只有一处真相）',
      'AppearanceStore.dirIn(ctx.getFilesDir(), ch.id)' in exp
      and 'new File(ctx.getFilesDir(), "appearance")' not in exp)
check('★ 形象素材也守 600 MB 上限（Live2D 模型包比表情大得多）',
      'private static boolean overCap(Result r)' in exp
      and 'if (overCap(r)) return;' in exp
      and exp.count('overCap(r)') >= 4)

print()
print('=== B. 导入：形象落回覆盖层 ===')
check('安装核心多了一个 filesDir 参数（形象覆盖层的根）',
      re.search(r'public static Result installInto\(File tmpBase, File charRoot, File filesDir,\s*'
                r'InputStream in, boolean dryRun\)', imp) is not None)
check('Context 版把 filesDir 一起转发（离线断言跑的是同一份代码）',
      'return installInto(ctx.getFilesDir(), CharacterStore.root(ctx),\n'
      '                ctx.getFilesDir(), in, dryRun);' in read(IMP))
check('★ 只有包里**真有** appearance/ 才动对方的形象（包没提形象就不改变现状）',
      'if (filesDir != null && appSrc.isDirectory()) {' in imp)
check('★ 落点走 AppearanceStore.dirIn（与运行期读的是同一个路径/同一套净化）',
      'File appDst = AppearanceStore.dirIn(filesDir, id);' in imp)
check('覆盖同名角色时先清掉旧形象（不然会新旧素材混在一起）',
      'PetModel.deleteRecursive(appDst);' in imp)
check('结果里如实记下"带了形象/几个文件"',
      'public boolean appearance;' in imp and 'public int appearanceFiles;' in imp
      and 'r.appearanceFiles = countFiles(appSrc);' in imp)

print()
print('=== C. ★ 辅助目录不许被当成动作 ===')
check('包内目录判据集中在 PackFormat（memes / appearance 一份清单）',
      'public static final String APPEARANCE_DIR = "appearance";' in pack
      and 'public static final String[] AUX_DIRS = {"memes", APPEARANCE_DIR};' in pack
      and 'public static boolean isAuxDir(String name)' in pack)
check('★ 导入器全树扫动作时走 isAuxDir（不再硬编码 "memes"）',
      'if (skipAux && PackFormat.isAuxDir(f.getName())) continue;' in imp
      and '"memes".equalsIgnoreCase' not in imp)
check('参数名也跟着改（skipMemes → skipAux，避免"名字说只跳表情"的误导）',
      'boolean skipAux' in imp and 'skipMemes' not in imp)

print()
print('=== D. 界面 / 文档如实说 ===')
check('★ 导出结果告诉用户"带上了形象 / 没有自定义形象"（两种都说）',
      '已带上形象素材（' in main and '这个角色没有自定义形象' in main)
check('导入结果告诉用户"含形象素材"',
      '，含形象素材（' in main)
check('日志里带 appear=（设备侧靠它判"形象有没有跟着走"）',
      '" appear=" + rr.appearanceFiles' in main and '" appear=" + (r != null ? r.appearanceFiles : -1)' in main)
doc = read(DOC)
check('★ 角色包格式.md 写了 appearance/ 这一节',
      'appearance/' in doc and '形象' in doc)
check('文档说清"形象随包走"与"没带形象时的行为"',
      ('随包' in doc or '跟着走' in doc or '一起导出' in doc) and '内置' in doc)

print()
print('=== E. 行为：真代码 + 真文件的往返（rt_test）===')
r = subprocess.run([sys.executable, os.path.join(HERE, 'rt_test', 'run.py')] + RT_ARGS,
                   capture_output=True)
out = r.stdout.decode('utf-8', 'replace')
if r.returncode != 0:
    check('rt_test 往返跑通', False, r.stderr.decode('utf-8', 'replace')[-400:])
else:
    scen = {}
    cur = None
    for ln in out.splitlines():
        if ln.startswith('SCENARIO|'):
            cur = ln.split('|', 1)[1]
            scen[cur] = {'key': {}, 'entry': {}}
            continue
        if cur is None:
            continue
        parts = ln.split('|')
        if parts[0] == 'ENTRY' and len(parts) >= 3:
            scen[cur]['entry'][parts[1]] = int(parts[2])
        elif len(parts) >= 2:
            scen[cur]['key'][parts[0]] = '|'.join(parts[1:])

    for name in ('A', 'B'):
        s = scen.get(name, {'key': {}, 'entry': {}})
        k, e = s['key'], s['entry']

        def fld(key, field):
            for part in k.get(key, '').split(' '):
                if part.startswith(field + '='):
                    return part[len(field) + 1:]
            return None

        check('%s：导出报告里"带了 2 个形象文件"' % name, fld('EXPORT', 'appear') == '2',
              'appear=%s' % fld('EXPORT', 'appear'))
        check('%s：包里真有 appearance/appearance.json 与 appearance/image.png' % name,
              'appearance/appearance.json' in e and 'appearance/image.png' in e,
              str(sorted(e.keys())))
        check('%s：★ 形象图**没有**被算成动作（clips 数不变）' % name,
              fld('EXPORT', 'clips') in ('4', '3') and 'appearance/image.png' not in
              [x for x in e if x.startswith('anim/clips/')],
              'clips=%s' % fld('EXPORT', 'clips'))
        ap = k.get('APPEAR', '')
        check('%s：★ 导入后形象落在**覆盖层**（全新 files 目录里），来源=image、素材字节一致' % name,
              'flag=true' in ap and 'files=2' in ap and 'json=true' in ap
              and 'image=true' in ap and 'src=image' in ap
              and 'payload=face-png-payload' in ap,
              ap)
        check('%s：★ 导入后**动作数一个不差**（形象图没混进动作）' % name,
              'ok=true' in k.get('IMPORT', '')
              and ('clips=4' in k.get('IMPORT', '') if name == 'A' else 'clips=3' in k.get('IMPORT', '')),
              k.get('IMPORT', ''))

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（形象随角色包导出/导入）')
