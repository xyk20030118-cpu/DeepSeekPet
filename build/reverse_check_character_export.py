# -*- coding: utf-8 -*-
"""反向验证：verify_character_export.py 的断言**必须能抓到回潮**。

每项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。
★ 覆盖用户追加要求点名的坑：导出用角色包原配置 / 漏用户新加的动作 / 漏覆盖里新增的场景 /
  漏事件场景 / 权重没写 / 找不到的文件被静默丢掉 / 内置角色仍被拦死 / 往返断言被架空。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_character_export.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
EXP = os.path.join(SRC, 'CharacterExporter.java')
IMP = os.path.join(SRC, 'CharacterImporter.java')
PKF = os.path.join(SRC, 'PackFormat.java')
MAIN = os.path.join(SRC, 'MainActivity.java')


def run_verify(verbose=False):
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    if verbose and r.returncode != 0:
        for ln in r.stdout.decode('utf-8', 'replace').splitlines():
            if 'FAIL' in ln or '失败' in ln:
                print('        ' + ln.strip())
    return r.returncode


def break_and_check(label, path, old, new):
    orig = io.open(path, 'rb').read()
    ob = old.encode('utf-8')
    if ob not in orig:
        print('  [SKIP] %-58s （找不到锚点，按未验证计）' % label)
        return None
    try:
        io.open(path, 'wb').write(orig.replace(ob, new.encode('utf-8'), 1))
        bad = run_verify()
        io.open(path, 'wb').write(orig)
        good = run_verify(verbose=True)
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-58s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：按当前绑定导出 + 导出→导入往返')
results = []

results.append(break_and_check(
    'a. ★导出不叠用户覆盖层（用角色包原样配置，带不走用户的改动）',
    EXP, 'as.applyOverrides(SceneBindings.loadRoot(ctx, ch.id));', ';'))

results.append(break_and_check(
    'b. ★导出漏掉事件场景（whisper / balance / workStatus 不进包）',
    EXP, 'for (String id : SceneRegistry.eventIds()) addGroup(p.events, id, as.bindsOf(id), p.clipIds);',
    ';'))

results.append(break_and_check(
    'c. ★导出漏掉「待机随机池」（用户说"全部打包"）',
    EXP, 'for (String id : SceneRegistry.ids()) addGroup(p.scenes, id, as.bindsOf(id), p.clipIds);',
    'for (String id : SceneRegistry.ids()) if (!SceneRegistry.POOL.equals(id)) '
    'addGroup(p.scenes, id, as.bindsOf(id), p.clipIds);'))

results.append(break_and_check(
    'd. ★素材解析优先级反了（角色包盖住用户新加的同名动作）',
    EXP,
    '                    SceneBindings.clipsDirFor(ctx, ch.id),\n'
    '                    ch.dir == null ? null : new File(ch.dir, PetCharacter.F_CLIPS)));',
    '                    ch.dir == null ? null : new File(ch.dir, PetCharacter.F_CLIPS),\n'
    '                    SceneBindings.clipsDirFor(ctx, ch.id)));'))

results.append(break_and_check(
    'e. ★找不到的素材被静默丢掉（不进 missing）',
    EXP, 'if (f == null) { r.missing.add(id); continue; }', 'if (f == null) { continue; }'))

results.append(break_and_check(
    'f. ★不写 anim/lines.json（台词被丢掉）',
    EXP, 'if (lines != null) putText(zos, "anim/lines.json", lines, r);', ';'))

results.append(break_and_check(
    'g. ★clips 条目名写错目录（导入器找不到这些动作）',
    EXP, '"anim/clips/" + id + "." + f.ext', '"clips/" + id + "." + f.ext'))

results.append(break_and_check(
    'h. ★台词不按「被包含动作」收集（把包里所有台词都带上）',
    EXP, 'for (String id : plan.clipIds) {\n                    List<String> ls = pk.linesOf(id);',
    'for (String id : pk.lineIds()) {\n                    List<String> ls = pk.linesOf(id);'))

results.append(break_and_check(
    'i. ★表情包不再带上（memes/ 丢失）',
    EXP, 'putTree(zos, new File(charDir, "memes"), "memes", r);', ';'))

results.append(break_and_check(
    'j. ★character.json 总是现造（不再复制角色目录里的那份）',
    EXP, 'if (meta != null && meta.isFile() && meta.length() > 0) {', 'if (false) {'))

results.append(break_and_check(
    'k. ★权重没写进 pack.json（×3 变 ×1）',
    PKF, 'if (it.weight <= 1) {', 'if (true) {'))

results.append(break_and_check(
    'l. ★导出不再守 600 MB 上限（可能导出一个自己都导不回来的包）',
    EXP,
    '        if (r.bytes > MAX_TOTAL_BYTES) {\n'
    '            if (r.error.isEmpty()) r.error = "导出内容超过 600 MB 上限（导入器会拒绝）";\n'
    '            return true;\n'
    '        }\n'
    '        return false;',
    '        return false;'))

results.append(break_and_check(
    'm. ★内置角色又被拦死（本轮的语义变更被回退）',
    EXP, '        Result r = new Result();\n        if (ch == null || out == null) {',
    '        Result r = new Result();\n'
    '        if (ch != null && ch.dir == null) { r.error = "内置角色不能导出"; return r; }\n'
    '        if (ch == null || out == null) {'))

results.append(break_and_check(
    'n. ★导入器核心不再被 Context 版本转发（往返断言被架空）',
    IMP, 'return installInto(ctx.getFilesDir(), CharacterStore.root(ctx),\n'
         '                ctx.getFilesDir(), in, dryRun);',
    'return installInto(ctx.getFilesDir(), ctx.getFilesDir(),\n'
    '                ctx.getFilesDir(), in, dryRun);'))

results.append(break_and_check(
    'o. ★设置页不再判断"有没有改动"（每次导出都弹提示）',
    MAIN, 'n = SceneBindings.overrideCount(this, ch.id) + SceneBindings.userClipIds(this, ch.id).size();',
    'n = 0;'))

results.append(break_and_check(
    'p. ★导出结果不再提示"找不到素材的动作"（用户以为全都导出了）',
    MAIN, 'if (!rr.missing.isEmpty()) {', 'if (false) {'))

results.append(break_and_check(
    'q. ★往返断言改回"回放缓存"（断言要读落盘产物 ⇒ 会时红时绿）',
    VERIFY, "RT_ARGS = ['--only=roundtrip', '--no-cache']",
    "RT_ARGS = ['--only=roundtrip']"))

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
