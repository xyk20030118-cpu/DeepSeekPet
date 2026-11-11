# -*- coding: utf-8 -*-
"""反向验证：形象随角色包走的断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 这条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 盯的是"形象随包走"（待办 0m ①）最容易被回退的几处：
  导出不带形象、导入不落地、把形象图当成动作、包没提形象却去清对方的形象。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
EXP = os.path.join(SRC, 'CharacterExporter.java')
IMP = os.path.join(SRC, 'CharacterImporter.java')
PACK = os.path.join(SRC, 'PackFormat.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
VERIFY = os.path.join(HERE, 'verify_appearance_pack.py')
FPSTEST = os.path.join(HERE, 'fpstest', 'run.bat')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def run_fpstest():
    return subprocess.run(['cmd', '/c', FPSTEST], capture_output=True,
                          cwd=os.path.dirname(FPSTEST)).returncode


def break_and_check(label, path, old, new, runner=None):
    runner = runner or run_verify
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = runner()
        write(path, orig)
        good = runner()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：形象随角色包走 ===')
print()

# ① 导出不再带形象（回退到"发给别人没有脸"）
results.append(break_and_check(
    'A. 导出不带 appearance/',
    EXP,
    '            if (appearanceDir != null && appearanceDir.isDirectory()) {\n'
    '                int before = r.files;\n'
    '                putTree(zos, appearanceDir, PackFormat.APPEARANCE_DIR, r);\n'
    '                r.appearanceFiles = r.files - before;\n'
    '            }',
    '            if (false) { }'))

# ② 导出器不再传形象目录（形同没接上）
results.append(break_and_check(
    'B. 导出时不传当前角色的形象目录',
    EXP,
    '            File appearance = AppearanceStore.dirIn(ctx.getFilesDir(), ch.id);\n'
    '            return write(out, plan, src, pk, ch.dir, appearance, ch.id, ch.displayName(), r);',
    '            return write(out, plan, src, pk, ch.dir, null, ch.id, ch.displayName(), r);'))

# ③ ★ 导入器全树扫动作时不再跳过辅助目录（形象图会变成"动作"）
results.append(break_and_check(
    'C. 扫动作时不跳辅助目录（脸变成动作）',
    IMP,
    '                if (skipAux && PackFormat.isAuxDir(f.getName())) continue;',
    '                if (skipAux && "memes".equalsIgnoreCase(f.getName())) continue;'))

# ④ 包没带形象也去清对方的形象（"包没提形象就不改变现状"被回退）
results.append(break_and_check(
    'D. 包没带形象也去动对方的形象',
    IMP,
    '            if (filesDir != null && appSrc.isDirectory()) {',
    '            if (filesDir != null) {'))

# ⑤ 形象落到角色目录而不是覆盖层（运行期读不到 ⇒ 还是没脸）
results.append(break_and_check(
    'E. 形象落到角色目录（不是覆盖层）',
    IMP,
    '                File appDst = AppearanceStore.dirIn(filesDir, id);',
    '                File appDst = new File(dest, PackFormat.APPEARANCE_DIR);'))

# ⑥ 界面不再如实说"有没有带上形象"
results.append(break_and_check(
    'F. 导出提示不再说形象',
    MAIN,
    '                            msg += rr.hasAppearance()\n'
    '                                    ? "\\n已带上形象素材（" + rr.appearanceFiles + " 个文件），'
    '对方导入后看到的是同一张脸"\n'
    '                                    : "\\n这个角色没有自定义形象（对方会用内置形象）";',
    '                            msg += "";'))

# ⑦ 纯类判据失效（辅助目录清单被清空）
results.append(break_and_check(
    'G. isAuxDir 失效（清单为空）',
    PACK,
    '        String s = name.trim();\n'
    '        for (String d : AUX_DIRS) if (d.equalsIgnoreCase(s)) return true;\n'
    '        return false;',
    '        return false;',
    runner=run_fpstest))

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
