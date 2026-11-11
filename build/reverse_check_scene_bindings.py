# -*- coding: utf-8 -*-
"""反向验证：verify_scene_bindings.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。
★ 覆盖了 STATE.md 待办 0i 点名要抓的六条：① 运行期没应用覆盖 ② 恢复默认不清覆盖
  ③ 没按角色隔离 ④ loadForDisplay 不应用覆盖 ⑤ 权重又被丢掉 ⑥ 设置页退回只读。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_scene_bindings.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SB = os.path.join(SRC, 'SceneBindings.java')
ASYS = os.path.join(SRC, 'ActionSystem.java')
VIEW = os.path.join(SRC, 'PetView.java')
MAIN = os.path.join(SRC, 'MainActivity.java')


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


print('== 反向验证：P1 场景动作可编辑（覆盖层 / 权重 / 预览 / 动作库）')
results = []

results.append(break_and_check(
    'a. ★覆盖层在运行期没被应用（PetView 只 load 不 applyOverrides）',
    VIEW,
    '                actions.applyOverrides(SceneBindings.loadRoot(getContext(),',
    '                actions.overrideCount(); SceneBindings.loadRoot(getContext(),'))

results.append(break_and_check(
    'b. ★「恢复角色包默认」不清覆盖（改成读个数，什么也没删）',
    MAIN,
    'SceneBindings.clear(MainActivity.this, cid, scene);',
    'SceneBindings.overrideCount(MainActivity.this, cid);'))

results.append(break_and_check(
    'c. ★覆盖层没按角色隔离（落盘文件名写死 default）',
    SB,
    'ActionMenuConfig.sanitize(charId) + ".json"',
    'ActionMenuConfig.sanitize("default") + ".json"'))

results.append(break_and_check(
    'd. ★设置页装配不应用覆盖（改完看不见，恢复默认还会恢复错）',
    ASYS,
    '        try { as.applyOverrides(SceneBindings.loadRoot(ctx, cid)); } catch (Throwable ignore) {}\n',
    ''))

results.append(break_and_check(
    'e. ★权重又被丢掉（装配时硬编码权重 1）',
    ASYS,
    'g.add(new PetAnimPack.Clip(c.id, Math.max(1, c.weight), noMirror));',
    'g.add(new PetAnimPack.Clip(c.id, 1, noMirror));'))

results.append(break_and_check(
    'f. ★权重解析丢字段（读错 key，配了 weight 也读成 1）',
    ASYS,
    'Math.max(1, j.optInt("weight", 1))',
    'Math.max(1, j.optInt("weightX", 1))'))

results.append(break_and_check(
    'g. ★设置页退回只读（点场景不再进编辑器）',
    MAIN,
    '                        editScene(as, ids.get(which));',
    '                        android.widget.Toast.makeText(this, "只读", 0).show();'))

results.append(break_and_check(
    'h. 预览不再用素材层的 getDrawable（改成拿不到图）',
    MAIN,
    'pk.getDrawable(clipId)',
    '(Drawable) null'))

results.append(break_and_check(
    'i. 设置页的动作库入口被摘掉',
    MAIN,
    'public void onClick(View v) { showClipLibrary(); }',
    'public void onClick(View v) { showSceneOverview(); }'))

results.append(break_and_check(
    'j. ★调权重前不落覆盖（setWeight 静默无事发生 = 假功能）',
    MAIN,
    '        ensureOverride(as, scene);\n',
    ''))

results.append(break_and_check(
    'k. ★覆盖层变成"整套替换"（清空场景表 → 另外 17 个场景全丢）',
    ASYS,
    '        overridden.clear();\n        if (root == null) return;',
    '        overridden.clear();\n        scenes.clear();\n        if (root == null) return;'))

results.append(break_and_check(
    'l. 动作库不再并素材层名单（清空场景后动作从库里消失）',
    ASYS,
    'List<String> all = pack.allNames();',
    'List<String> all = null;'))

results.append(break_and_check(
    'm. ★「恢复默认」改成写空数组（场景变"未绑定"，不是回角色包默认）',
    SB,
    'obj.remove(scene);',
    'obj.put(scene, new org.json.JSONArray());'))

results.append(break_and_check(
    'n. 「全部恢复角色包默认」不调 clearAll',
    MAIN,
    'SceneBindings.clearAll(MainActivity.this, cid);',
    'SceneBindings.overrideCount(MainActivity.this, cid);'))

results.append(break_and_check(
    'o. 编辑绑定不再保留已设权重（改绑把 ×N 抹掉）',
    MAIN,
    # ★ 轮次㉚：改绑换成 ClipListPane 后，保存时遍历的是**勾选出来的 id**
    'new SceneBindings.Bind(id, old < 1 ? 1 : old)',
    'new SceneBindings.Bind(id, 1)'))

results.append(break_and_check(
    'p. 事件场景覆盖写错表（事件进 scenes，ActionSystem 就找不到）',
    SB,
    'String node = SceneRegistry.isEvent(scene) ? N_EVENTS : N_SCENES;',
    'String node = N_SCENES;'))

# ★★ P0 blocker（模拟器实测 3 次复现）：同一条 AlertDialog 链上 setMessage 会把 setItems 静默吞掉
results.append(break_and_check(
    'q. ★★场景操作面又加回 setMessage（列表会被平台吞掉，四个入口全消失）',
    MAIN,
    '                .setItems(acts, new DialogInterface.OnClickListener() {',
    '                .setMessage(SceneRegistry.hint(scene))\n'
    '                .setItems(acts, new DialogInterface.OnClickListener() {'))

results.append(break_and_check(
    'r. ★权重弹窗又加回 setMessage（权重档位列表消失）',
    MAIN,
    '                .setTitle("\\uD83D\\uDCCF 权重（" + SceneRegistry.label(scene)\n'
    '                        + "）：数值越大越常被抽到")',
    '                .setTitle("\\uD83D\\uDCCF 权重（" + SceneRegistry.label(scene) + "）")\n'
    '                .setMessage("权重只影响「随机抽取」的场景")'))

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
