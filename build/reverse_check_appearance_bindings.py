# -*- coding: utf-8 -*-
"""反向验证：verify_appearance_bindings.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。

覆盖 P5（形象来源纳入角色模型）五条边界：
  1. 纯类判据：取值合法化、素材缺失回退内置、"关掉一个来源 ≠ 关掉全部"；
  2. 存储层：**读路径不建目录**（轮次㈡ 的空壳 bug）、按角色隔离、恢复默认不留空壳；
  3. 升级路径：老全局形象**搬**进角色目录、幂等、旧口径是内置就不搬；
  4. 接线：Prefs 门面（四个旧访问器 → 当前角色）、迁移赶在 buildViews 之前、
     设置页那一行可点、恢复默认只清当前角色、删角色连形象一起清；
  5. 可测性本身：fpstest 编纯类、rt_test 真跑 AppearanceCheck。

★ 注意本套件的开销：每一项都要真跑一遍 fpstest + rt_test（几十秒），这是"行为级断言"的代价，
  也是它值钱的地方 —— 纯 grep 抓不到"读路径顺手 mkdirs"这种回潮。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_appearance_bindings.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
AP = os.path.join(SRC, 'Appearance.java')
STORE = os.path.join(SRC, 'AppearanceStore.java')
PF = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
FS = os.path.join(SRC, 'FloatingService.java')
CS = os.path.join(SRC, 'CharacterStore.java')
BAT = os.path.join(HERE, 'fpstest', 'run.bat')
RT = os.path.join(HERE, 'rt_test', 'run.py')


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


print('== 反向验证：P5 形象来源纳入角色模型 ==')
results = []

# ─────────────── 一、纯类判据（fpstest [20] 真跑）───────────────
results.append(break_and_check(
    'a. ★脏值不再回内置（normalize 放行未知来源 ⇒ 调用方可能拿到 null）',
    AP,
    'return valid(src) ? src : DEFAULT;',
    'return src;'))

results.append(break_and_check(
    'b. ★素材缺失也不回退（选过"我的图片"、文件没了 ⇒ 留一块空白）',
    AP,
    'return available(s, hasImage, hasAnim, hasModel, hasL2d) ? s : DEFAULT;',
    'return s;'))

results.append(break_and_check(
    'c. ★关掉一个来源就回内置（老 bug：谁后写谁赢，"传了动图还是旧图片"）',
    AP,
    'return cur.equals(off) ? DEFAULT : cur;',
    'return DEFAULT;'))

results.append(break_and_check(
    'd. 清单不再标出当前来源（● 没了）',
    AP,
    'sb.append(current ? "\\u25CF " : "\\u25CB ");',
    'sb.append("\\u25CB ");'))

# ─────────────── 二、存储层：读路径 / 隔离 / 恢复默认 ───────────────
results.append(break_and_check(
    'e. ★读路径顺手 mkdirs（files/appearance 下会攒一堆空角色目录）',
    STORE,
    'return new File(rootIn(filesDir), ActionMenuConfig.sanitize(charId));',
    'File d = new File(rootIn(filesDir), ActionMenuConfig.sanitize(charId));\n'
    '        d.mkdirs();\n'
    '        return d;'))

results.append(break_and_check(
    'f. ★形象不再按角色隔离（所有角色共用一份）',
    STORE,
    'return new File(rootIn(filesDir), ActionMenuConfig.sanitize(charId));',
    'return new File(rootIn(filesDir), "shared");'))

results.append(break_and_check(
    'g. ★恢复默认形象不清 json（"恢复"点了没反应）',
    STORE,
    'try { jsonIn(filesDir, charId).delete(); } catch (Throwable ignore) {}',
    ''))

results.append(break_and_check(
    'h. 恢复默认形象不收空壳目录（留下一个空目录）',
    STORE,
    '        try {\n'
    '            File d = dirIn(filesDir, charId);\n'
    '            File[] rest = d.listFiles();\n'
    '            if (d.isDirectory() && (rest == null || rest.length == 0)) d.delete();\n'
    '        } catch (Throwable ignore) {}\n',
    ''))

# ─────────────── 三、升级路径：迁移 ───────────────
results.append(break_and_check(
    'i. ★迁移不再幂等（"恢复默认形象"之后重启又被搬回来）',
    STORE,
    'if (configuredIn(filesDir, charId)) return 0;',
    ''))

results.append(break_and_check(
    'j. ★旧口径是内置也照搬（凭空复活用户没启用的形象）',
    STORE,
    'if (Appearance.BUILTIN.equals(Appearance.normalize(legacySource))) return 0;',
    ''))

# ─────────────── 四、Prefs 门面 ───────────────
results.append(break_and_check(
    'k. ★petMode 不再看当前角色（回到"读全局开关"）',
    PF,
    'String mode = AppearanceStore.modeFor(ctx);',
    'String mode = Appearance.BUILTIN;'))

results.append(break_and_check(
    'l. ★图片路径退回全局（切角色后形象不跟着走 —— P5 要修的就是这个）',
    PF,
    'return AppearanceStore.ensureImageCurrent(ctx);',
    'return new File(ctx.getFilesDir(), "custom_pet.png");'))

results.append(break_and_check(
    'm. ★关掉一个来源改成"一律回内置"（顺序又变得重要）',
    PF,
    'else AppearanceStore.disableFor(ctx, Appearance.IMAGE);',
    'else AppearanceStore.setSourceFor(ctx, Appearance.BUILTIN);'))

# ─────────────── 五、运行期接线 ───────────────
results.append(break_and_check(
    'n. ★迁移挪到 buildViews() 之后（先渲染一次"没有形象"）',
    FS,
    '        try { Prefs.migrateLegacyAppearance(this); } catch (Throwable ignore) {}\n'
    '        buildViews();',
    '        buildViews();\n'
    '        try { Prefs.migrateLegacyAppearance(this); } catch (Throwable ignore) {}'))

results.append(break_and_check(
    'o. ★装配日志丢掉角色 id（设备上没法核对用的是谁的形象）',
    FS,
    '"applyPetDrawable char=" + CharacterStore.currentId(this)',
    '"applyPetDrawable"'))

# ─────────────── 六、形象入口（轮次㉛：收进「角色」）───────────────
results.append(break_and_check(
    'p. ★形象清单里的导入动作被摘掉（用户再也导不进形象）',
    MAIN,
    'rows.add("\\uD83E\\uDDE9 导入 Live2D 模型（.model3.json / .moc3 / 分层PNG）…");',
    'rows.add("（这条路被拿掉了）");'))

results.append(break_and_check(
    'q. ★恢复默认形象改成清全局（把别的角色的形象也一起清了）',
    MAIN,
    'AppearanceStore.clearFor(MainActivity.this, ch.id);',
    'Prefs.setUseCustomImage(MainActivity.this, false);'))

results.append(break_and_check(
    'r. ★Live2D 可用判据又用会建目录的 Prefs.customL2DDir',
    MAIN,
    'if (Appearance.L2D.equals(src))   return AppearanceStore.hasL2d(this);',
    'if (Appearance.L2D.equals(src))   return new java.io.File('
    'Prefs.customL2DDir(MainActivity.this), "meta.json").isFile();'))

results.append(break_and_check(
    'v. ★没有角色时形象清单不再拒绝（会给"没有角色"的壳设形象）',
    MAIN,
    'if (ch.dir == null) {',
    'if (false) {'))

# ─────────────── 七、删角色连带清形象 ───────────────
results.append(break_and_check(
    's. ★删角色不清这个角色的形象（删完再导入同名角色会继承上一份脸）',
    CS,
    'try { AppearanceStore.deleteFor(ctx, id); } catch (Throwable ignore) {}',
    ''))

# ─────────────── 八、可测性本身也要被钉住 ───────────────
results.append(break_and_check(
    't. ★fpstest 不再编译 Appearance（纯类断言被架空，只剩 grep）',
    BAT,
    '..\\..\\src\\com\\deepseek\\pet\\Appearance.java ',
    ''))

results.append(break_and_check(
    'u. ★rt_test 不再跑 AppearanceCheck（行为断言被架空）',
    RT,
    "    if only in ('all', 'appearance'):",
    "    if only in ('all',):"))

results.append(break_and_check(
    'v. ★没有角色时形象清单不再拒绝（会给"没有角色"的壳设形象）',
    MAIN,
    'if (ch.dir == null) {',
    'if (false) {'))

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
