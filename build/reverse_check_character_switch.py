# -*- coding: utf-8 -*-
"""反向验证：verify_character_switch.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。

覆盖 P3（角色切换 UI）四条边界：
  1. 那一行是**可点入口**（不是只读标签），清单标出当前角色、内置不可删；
  2. 切角色真的生效（写 pref + reloadPet + 刷新页面）；点自己不算切换；
  3. 删角色连**派生的用户数据**一起清（覆盖层 json / 用户素材 / 自定义菜单），
     而且"删的正好是当前角色"必须回内置；
  4. 删除路径**不许**用会 mkdirs 的 clipsDirFor（留空壳目录的实测 bug）。

★ 注意本套件的开销：每一项都要真跑一遍 fpstest + rt_test（约几十秒），这是"行为级断言"的代价，
  也是它值钱的地方 —— 纯 grep 的断言抓不到"删除路径调错 API"这种回潮。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_character_switch.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
CP = os.path.join(SRC, 'CharPicker.java')
STORE = os.path.join(SRC, 'CharacterStore.java')
SB = os.path.join(SRC, 'SceneBindings.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
BAT = os.path.join(HERE, 'fpstest', 'run.bat')
UI = os.path.join(HERE, 'verify_char_ui.py')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    orig = io.open(path, 'rb').read()
    ob = old.encode('utf-8')
    if ob not in orig:
        print('  [SKIP] %-56s （找不到锚点，按未验证计）' % label)
        return None
    try:
        io.open(path, 'wb').write(orig.replace(ob, new.encode('utf-8'), 1))
        bad = run_verify()
        io.open(path, 'wb').write(orig)
        good = run_verify()
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-56s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：P3 角色切换 UI ==')
results = []

# ─────────────── 一、纯类判据（fpstest [19] 真跑）───────────────
results.append(break_and_check(
    'a. ★哨兵（没有角色）也能被删（deletable 不再挡它）',
    CP,
    'return id != null && !id.trim().isEmpty() && !PetCharacter.BUILTIN_ID.equals(id);',
    'return id != null && !id.trim().isEmpty();'))

results.append(break_and_check(
    'b. ★删的正好是当前角色却不落到剩下的角色（pref 指向不存在的目录）',
    CP,
    '        if (remaining != null) {',
    '        if (false) {'))

results.append(break_and_check(
    'c. 点自己也算切换（白 reloadPet + 整页重建）',
    CP,
    'return !targetId.equals(currentId);',
    'return true;'))

results.append(break_and_check(
    'd. 清单不再标出当前角色（● 没了）',
    CP,
    'return (current ? "\\u25CF " : "\\u25CB ") + clean(name)',
    'return "\\u25CB " + clean(name)'))

results.append(break_and_check(
    'e. ★新增角色的 id 不再避重（同名会覆盖掉别人的角色目录）',
    CP,
    '        if (taken == null || !taken.contains(base)) return base;',
    '        if (true) return base;'))

results.append(break_and_check(
    'f. 删除列表里又给角色加锁图标（用户以为删不掉）',
    CP,
    'return "\\uD83D\\uDDD1 " + clean(name);',
    'return "\\uD83D\\uDD12 " + clean(name) + "（不能删）";'))

# ─────────────── 二、删除路径的两条行为断言（rt_test）───────────────
results.append(break_and_check(
    'g. ★删除路径又用会 mkdirs 的 deleteUserClips 之外的写法（留空壳目录）',
    STORE,
    'try { SceneBindings.deleteUserClips(ctx, id); } catch (Throwable ignore) {}',
    'try { PetModel.deleteRecursive(SceneBindings.clipsDirFor(ctx, id)); } '
    'catch (Throwable ignore) {}'))

results.append(break_and_check(
    'h. ★清素材改用 clipsDirIn（会凭空把目录建出来）',
    SB,
    'File clips = clipsDirNoCreate(filesDir, charId);',
    'File clips = clipsDirIn(filesDir, charId);'))

results.append(break_and_check(
    'i. 清素材只删 clips/、不删空容器目录',
    SB,
    '            if (holder != null && holder.isDirectory() && (rest == null || rest.length == 0)) {\n'
    '                holder.delete();\n'
    '            }\n',
    ''))

# ─────────────── 三、切换是否真的生效（源码接线）───────────────
results.append(break_and_check(
    'j. ★切角色不写 pref（切完还是原来那个角色）',
    MAIN,
    'CharacterStore.setCurrentId(this, id);',
    ';'))

results.append(break_and_check(
    'k. ★切角色不重载悬浮窗（界面显示换了、桌宠没换）',
    MAIN,
    'Prefs.reloadPet(this);\n        Toast.makeText(this, "已切换到「" + c.displayName() + "」"',
    'Toast.makeText(this, "已切换到「" + c.displayName() + "」"'))

results.append(break_and_check(
    'l. 点自己不再早退（白重建一次）',
    MAIN,
    'if (!CharPicker.switchNeeded(cur, id)) {',
    'if (false) {'))

# ─────────────── 四、入口与删除接线 ───────────────
results.append(break_and_check(
    'm. ★设置页那一行退回"只读标签"（用户再也换不了角色）',
    MAIN,
    'addButton(box, "\\uD83C\\uDFAD " + CharPicker.currentRowLabel(curChar.displayName(), charTotal)\n'
    '                        + " \\u203A",\n'
    '                new View.OnClickListener() {\n'
    '            @Override public void onClick(View v) { showCharacterHub(); }\n'
    '        });',
    'addLabel(box, "角色：" + curChar.displayName());'))

results.append(break_and_check(
    'n. 删除入口不再过滤哨兵（"没有角色"也会列进去）',
    MAIN,
    'if (!CharPicker.deletable(c.id)) continue;',
    'if (false) continue;'))

results.append(break_and_check(
    'n2. ★角色中心不再提供"添加角色包"（用户没别的路可导入了）',
    MAIN,
    'acts.add("\\uD83D\\uDCE5 添加角色包（.zip）…");',
    'acts.add("（这条路被拿掉了）");'))

results.append(break_and_check(
    'n3. ★新增角色不再校验名字（重名/空名直接建目录）',
    MAIN,
    'String err = CharPicker.nameError(names, name);',
    'String err = null;'))

results.append(break_and_check(
    'o. 删角色前不再确认（"删除"挪到取消位 = 点确认不会删）',
    MAIN,
    # ★ 锚点必须**唯一**：MainActivity 里还有两处别处的 `.setPositiveButton("删除", …)`
    #   （2951 / 3447 行的其它删除确认）。只写那一句的话，`replace(…, 1)` 会打到**别人身上**，
    #   这一项就形同虚设（本项第一次跑就是"破坏后=0"，看起来像断言无能，其实是锚点撞车）。
    #   所以把上一行的 setMessage 一起带上，锁死到 confirmDeleteCharacter 里那一处。
    '.setMessage(CharPicker.deleteConfirmMessage(ch.displayName()))\n'
    '                .setPositiveButton("删除", new DialogInterface.OnClickListener() {',
    '.setMessage(CharPicker.deleteConfirmMessage(ch.displayName()))\n'
    '                .setNegativeButton("删除", new DialogInterface.OnClickListener() {'))

results.append(break_and_check(
    'p. 删的正好是当前角色时不重载（桌宠还停在已删角色的素材上）',
    MAIN,
    'if (wasCurrent) Prefs.reloadPet(MainActivity.this);',
    ';'))

results.append(break_and_check(
    'q. ★删角色不清场景覆盖层（留下孤儿 json）',
    STORE,
    'try { SceneBindings.fileFor(ctx, id).delete(); } catch (Throwable ignore) {}',
    ''))

results.append(break_and_check(
    'r. 删角色不清自定义动作菜单（留下孤儿配置）',
    STORE,
    'try { ActionMenuConfig.deleteFor(ctx, id); } catch (Throwable ignore) {}',
    ''))

results.append(break_and_check(
    's. 删角色不再拒绝哨兵（"没有角色"也能删）',
    STORE,
    'if (!CharPicker.deletable(id)) return false;',
    ''))

results.append(break_and_check(
    's2. ★current() 在没有角色时返回 null（几十处 .displayName() 会集体 NPE）',
    STORE,
    'if (PetCharacter.BUILTIN_ID.equals(id)) return none();',
    'if (PetCharacter.BUILTIN_ID.equals(id)) return null;'))

results.append(break_and_check(
    's3. ★新增角色写不出 character.json 却留着半拉目录（列表里多一个鬼角色）',
    STORE,
    '            PetModel.deleteRecursive(dir);\n            return null;',
    '            return null;'))

results.append(break_and_check(
    's4. ★新增角色不写 character.json（名字变成目录 id）',
    STORE,
    'new java.io.FileOutputStream(new File(dir, PetCharacter.F_CHARACTER));',
    'new java.io.FileOutputStream(new File(dir, "x.tmp"));'))

# ─────────────── 五、可测性本身也要被钉住 ───────────────
results.append(break_and_check(
    't. ★fpstest 不再编译 CharPicker（纯类断言被架空，只剩 grep）',
    BAT,
    '..\\..\\src\\com\\deepseek\\pet\\CharPicker.java ',
    ''))

results.append(break_and_check(
    'u. ★设备脚本改成钉用户真机（会往用户手机上装包）',
    UI,
    "DEV = 'emulator-5554'",
    "DEV = '<设备序列号>'"))

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
