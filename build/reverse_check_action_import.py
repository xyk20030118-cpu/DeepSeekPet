# -*- coding: utf-8 -*-
"""反向验证：verify_action_import.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。
★ 覆盖施工单 0j 点名的四条：**A** 自动合成配置 / **B** 文件名前缀派生自 SceneRegistry /
  **C** App 内添加动作（多选 → 落盘 → 覆盖层追加绑定）/ **D** 静态图当动作 + 文档对齐。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_action_import.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ROOT = r'D:\petport_lab\petbuild'
IMP = os.path.join(SRC, 'CharacterImporter.java')
CLIP = os.path.join(SRC, 'ClipNaming.java')
PACK = os.path.join(SRC, 'PetAnimPack.java')
STORE = os.path.join(SRC, 'CharacterStore.java')
SB = os.path.join(SRC, 'SceneBindings.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
DOC = os.path.join(ROOT, '角色包格式.md')
BAT = os.path.join(HERE, 'fpstest', 'run.bat')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
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
        good = run_verify()
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-58s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：0j 角色包导入简化（A 自动合成 / B 前缀 / C 添加动作 / D 静态图）')
results = []

# ─────────────── A：自动合成 pack.json（导入器）───────────────
results.append(break_and_check(
    'a. ★缺 pack.json 又硬报错（合成分支被短路）',
    IMP,
    '            File pack = firstFile(tmp, "anim/pack.json", "pack.json");\n',
    '            File pack = firstFile(tmp, "anim/pack.json", "pack.json");\n'
    '            if (pack == null) { r.error = "包里没有 anim/pack.json（见「角色包格式.md」）"; return r; }\n'))

results.append(break_and_check(
    'b. ★合成了但没写盘（角色目录里永远没有 pack.json）',
    IMP,
    '                writeText(dst, json);\n',
    ''))

results.append(break_and_check(
    'c. 合成被短路成空串（写下去的是空文件）',
    IMP,
    'String json = ClipNaming.synthesizePack(found.toArray(new String[0]));',
    'String json = "";'))

results.append(break_and_check(
    'd. 全树扫动作文件时不跳过辅助目录（表情图/形象图变成动作）',
    IMP,
    '                if (skipAux && PackFormat.isAuxDir(f.getName())) continue;\n',
    '                if (false) continue;\n'))

# ─────────────── D：isClip 认静态图 ───────────────
results.append(break_and_check(
    'e. ★isClip 又只认 .webp/.gif（相册里选的 PNG/JPG 白选）',
    IMP,
    '        return ClipNaming.isImageName(n);',
    '        String l = n.toLowerCase();\n'
    '        return l.endsWith(".webp") || l.endsWith(".gif");'))

results.append(break_and_check(
    'f. 扩展名清单缩回两种（png/jpg 不再被认）',
    CLIP,
    '    public static final String[] IMAGE_EXTS = {"webp", "gif", "png", "jpg", "jpeg"};',
    '    public static final String[] IMAGE_EXTS = {"webp", "gif"};'))

# ─────────────── B：前缀表派生自 SceneRegistry ───────────────
results.append(break_and_check(
    'g. ★前缀表抄成硬编码中文名（不再从 SceneRegistry 派生）',
    CLIP,
    '        String base = baseNameOf(fileName);\n',
    '        String base = baseNameOf(fileName);\n'
    '        if (base.startsWith("待机")) return SceneRegistry.IDLE;\n'))

results.append(break_and_check(
    'h. ★「最长优先」改反（点击头部-1 被「点击」抢走）',
    CLIP,
    '            if (base.startsWith(label) && label.length() > bestLen) {',
    '            if (base.startsWith(label) && bestLen < 0) {'))

results.append(break_and_check(
    'i. ★「唯一才认」改成先到先得（点-1 被猜成 tap_tail）',
    CLIP,
    '            if (label != null && !label.isEmpty() && label.contains(token)) { hit = id; n++; }\n'
    '        }\n        return n == 1 ? hit : null;',
    '            if (label != null && !label.isEmpty() && label.contains(token)) { hit = id; n++; }\n'
    '        }\n        return hit;'))

results.append(break_and_check(
    'i2. ★「唯一才认」在前缀那半边失效（点-1 被猜成 tap_tail）',
    CLIP,
    '            if (label != null && !label.isEmpty() && label.startsWith(token)) { hit = id; n++; }\n'
    '        }\n        return n == 1 ? hit : null;',
    '            if (label != null && !label.isEmpty() && label.startsWith(token)) { hit = id; n++; }\n'
    '        }\n        return hit;'))

results.append(break_and_check(
    'j. ★idle 兜底没了（一个都不含「待机」时合成空配置）',
    CLIP,
    '        if (idleId == null) idleId = order.get(0);',
    '        if (idleId == null) return "";'))

results.append(break_and_check(
    'k. ★前缀命中的文件被塞进 pool（待机随机池会抽到点击/拖拽）',
    CLIP,
    '                String scene = sceneOf(f);',
    '                String scene = SceneRegistry.POOL;'))

# ─────────────── D：素材层播静态图 + 认识新加的动作 ───────────────
results.append(break_and_check(
    'l. readClipBytes 不再挨个试扩展名（PNG/JPG 找不到）',
    PACK,
    '        for (String e : ClipNaming.IMAGE_EXTS) if (!exts.contains(e)) exts.add(e);\n',
    ''))

results.append(break_and_check(
    'm. assets 回退写死 webp（内置角色侧的静态图找不到）',
    PACK,
    '                InputStream is = ctx.getAssets().open(DIR + id + "." + e);',
    '                InputStream is = ctx.getAssets().open(DIR + id + ".webp");'))

results.append(break_and_check(
    'n. ★不再扫角色包 clips 目录（「只进动作库」的图永远看不见）',
    PACK,
    '            scanCharClips();\n\n',
    ''))

results.append(break_and_check(
    'o. ★目录扫描不滤扩展名（peter_notes.txt 也当成动作）',
    PACK,
    '            if (!ClipNaming.isImageName(nm)) continue;',
    '            if (false) continue;'))

results.append(break_and_check(
    'p. 扫描挪到 buildAllClips() 之后（名字进不了可播名单）',
    PACK,
    '            scanCharClips();\n\n            buildAllClips();',
    '            buildAllClips();\n\n            scanCharClips();'))

results.append(break_and_check(
    'q. 大图保护只接了一个解码回调（另一半还是会 OOM）',
    PACK,
    '                        capDecodeSize(dec, info);\n',
    ''))

# ─────────────── C：App 内「添加动作」───────────────
results.append(break_and_check(
    'r. ★SAF 多选被去掉（只能单选）',
    MAIN,
    # ★ 锚点必须**整行**（以 \n + 恰好 12 个空格开头）：只写
    #   '            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);' 会同时匹配到
    #   「批量导入表情」那处 20 空格缩进的行（12 空格是它的子串！），
    #   于是破坏的是别人、断言看着是绿的 —— 反向项 r 第一版就是这么假绿的。
    '            i.setType("image/*");\n'
    '            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);\n',
    '            i.setType("image/*");\n'))

results.append(break_and_check(
    's. MIME 列表去掉 webp（相册里动图变灰选不到）',
    MAIN,
    '                    "image/webp", "image/gif", "image/png", "image/jpeg"});',
    '                    "image/gif", "image/png", "image/jpeg"});'))

results.append(break_and_check(
    't. ★设置页入口按钮改成去动作库（添加动作入口消失）',
    MAIN,
    'public void onClick(View v) { pickActionImages(); }',
    'public void onClick(View v) { showClipLibrary(); }'))

results.append(break_and_check(
    'u. onActivityResult 不再接 REQ_ADD_ACTIONS（选完图没反应）',
    MAIN,
    '        if (requestCode == REQ_ADD_ACTIONS && resultCode == RESULT_OK && data != null) {\n'
    '            handleAddActions(data);\n            return;\n        }\n',
    ''))

results.append(break_and_check(
    'v. 「只加入动作库」选项被去掉（逼用户当场选场景）',
    MAIN,
    '        items[0] = "只加入动作库（先不绑定）";',
    '        items[0] = "请选择场景";'))

results.append(break_and_check(
    'w. ★★绑定改成"整场替换"（角色包原有绑定被抹掉）',
    MAIN,
    '            for (PetAnimPack.Clip c : as.bindsOf(scene)) {',
    '            for (PetAnimPack.Clip c : new java.util.ArrayList<PetAnimPack.Clip>()) {'))

results.append(break_and_check(
    'x. ★用户素材目录又写回角色目录（内置角色就没有可写目录了）',
    STORE,
    '            File dir = SceneBindings.clipsDirFor(ctx, ch.id);',
    '            File dir = new File(ch.dir, PetCharacter.F_CLIPS);'))

results.append(break_and_check(
    'y. 落盘不防重名（同名动作互相覆盖）',
    STORE,
    '            String id = ClipNaming.uniqueId(base, taken.toArray(new String[0]));',
    '            String id = base;'))

results.append(break_and_check(
    'z. 落盘目录写错（不写进 clips）',
    SB,
    '        File clips = new File(new File(scenesRoot(filesDir), ActionMenuConfig.sanitize(charId)), "clips");',
    '        File clips = new File(new File(scenesRoot(filesDir), ActionMenuConfig.sanitize(charId)), "nope");'))

# ─────────────── 用户追加要求：场景内导入动画（功能 1）───────────────
results.append(break_and_check(
    'e2. ★场景操作面第一项不再是「导入动画到此场景」',
    MAIN,
    '                "\\uD83D\\uDCE5 导入动画到此场景",\n',
    ''))

results.append(break_and_check(
    'e3. ★第 0 项不再走 pickSceneImport（入口点了没反应）',
    MAIN,
    '                        if (which == 0) pickSceneImport(scene);',
    '                        if (which == 0) editSceneBindings(as, scene);'))

results.append(break_and_check(
    'e4. ★onActivityResult 不接场景导入（选完图什么都不发生）',
    MAIN,
    '        if (requestCode == REQ_IMPORT_SCENE_CLIPS && resultCode == RESULT_OK && data != null) {\n'
    '            handleSceneImport(data, pendingSceneImport);\n            return;\n        }\n',
    ''))

results.append(break_and_check(
    'e5. ★场景导入只落盘、不绑到那个场景（导入后"已绑 N 个"不变）',
    MAIN,
    '            if (!added.isEmpty()) bindActionsToScene(scene, added);',
    '            if (false) bindActionsToScene(scene, added);'))

results.append(break_and_check(
    'e6. ★场景导入后不刷新（桌宠不重载、界面停在旧数据上）',
    MAIN,
    '                Prefs.reloadPet(MainActivity.this);\n                editScene(loadSceneEditor(), scene);',
    '                editScene(loadSceneEditor(), scene);'))

results.append(break_and_check(
    'e7. ★目标场景没记住（导入落到 null 场景上）',
    MAIN, '        pendingSceneImport = scene;', '        pendingSceneImport = null;'))

results.append(break_and_check(
    'e8. ★重名判断不并上"当前有效配置"（会静默盖掉角色包的同名动作）',
    STORE, '            if (alsoTaken != null) for (String n : alsoTaken) {',
    '            if (false) for (String n : alsoTaken) {'))

results.append(break_and_check(
    'e9. ★素材层不再优先用户素材目录（用户加的同名动作被角色包盖住）',
    PACK, 'userClipFile(id, e)', 'charClipFile(id, e)'))

results.append(break_and_check(
    'e10. ★追加绑定变成"整套替换"（用户调好的权重全丢）',
    SB, '        if (cur != null) {\n            for (Bind b : cur) if (b != null',
    '        if (false) {\n            for (Bind b : cur) if (b != null'))

results.append(break_and_check(
    'aa. 导入完成不再提示"自动归到 N 个场景"',
    MAIN,
    '        if (r.synthesized) {',
    '        if (false) {'))

# ─────────────── D：文档对齐 + 纯类断言接线 ───────────────
results.append(break_and_check(
    'bb. 文档把 600 MB 写回 200 MB（连自家默认包都超标）',
    DOC,
    '整包 ≤ 600 MB',
    '整包 ≤ 200 MB'))

results.append(break_and_check(
    'cc. 文档第六节又写回"必须有 anim/pack.json"',
    DOC,
    '**至少要有一个动作文件**',
    '必须有 `anim/pack.json`'))

results.append(break_and_check(
    'dd. ★fpstest 不再编译 ClipNaming（A/B 只剩 grep，没有真断言）',
    BAT,
    ' ..\\..\\src\\com\\deepseek\\pet\\ClipNaming.java',
    ''))

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
