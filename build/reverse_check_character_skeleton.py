# -*- coding: utf-8 -*-
"""反向验证：verify_character_skeleton.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_character_skeleton.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SCENE = os.path.join(SRC, 'SceneRegistry.java')
ASYS = os.path.join(SRC, 'ActionSystem.java')
PACK = os.path.join(SRC, 'PetAnimPack.java')
VIEW = os.path.join(SRC, 'PetView.java')
SVC = os.path.join(SRC, 'FloatingService.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
MANIFEST = r'D:\petport_lab\petbuild\AndroidManifest.xml'
PREFS = os.path.join(SRC, 'Prefs.java')
IMPORTER = os.path.join(SRC, 'CharacterImporter.java')
SCENE_MAP = r'D:\petport_lab\petbuild\model\scene_map.json'
EXPORTER = os.path.join(SRC, 'CharacterExporter.java')
MEMES = os.path.join(SRC, 'Memes.java')
ASSETS_WEBP = r'D:\petport_lab\petbuild\assets\webp'
MODEL_WEBP = r'D:\petport_lab\petbuild\model\webp'


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    orig = io.open(path, 'rb').read()
    ob = old.encode('utf-8')
    if ob not in orig:
        print('  [SKIP] %-50s （找不到锚点，按未验证计）' % label)
        return None
    try:
        io.open(path, 'wb').write(orig.replace(ob, new.encode('utf-8'), 1))
        bad = run_verify()
        io.open(path, 'wb').write(orig)
        good = run_verify()
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-50s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：P0 骨架断言')
results = []

results.append(break_and_check(
    'a. 场景清单少一个场景（随机池）',
    SCENE, '{POOL,       "待机随机池",', '{POOLXX,     "待机随机池",'))
results.append(break_and_check(
    'b. 2.0/1.x 分流锚点失效',
    ASYS, 'optJSONObject("scenes") != null', 'optJSONObject("scenesOff") != null'))
results.append(break_and_check(
    'c. 待机随机池默认被打开（待机又变随机）',
    ASYS, 'private boolean poolEnabled = false;', 'private boolean poolEnabled = true;'))
results.append(break_and_check(
    'd. ★素材层不再读 2.0 的 scenes（实测 bug 复现）',
    PACK, 'JSONObject  sc = root.optJSONObject("scenes");',
    'JSONObject  sc = root.optJSONObject("scenesOff");'))
results.append(break_and_check(
    'e. sceneClips 不参与 loaded 判定（整包会被判失败）',
    PACK, '|| !sceneClips.isEmpty();', ';'))
results.append(break_and_check(
    'f. 场景层被绕过（回退到直接挑 idle）',
    VIEW, 'String id = (actions != null) ? actions.pick(scene) : null;',
    'String id = animPack.pickIdle().id;'))
results.append(break_and_check(
    'g. click 死分支又断掉（poke 不再走 click 场景）',
    VIEW, 'playSceneOnce(SceneRegistry.CLICK, 1200);', ''))
results.append(break_and_check(
    'h. drag 死分支又断掉（拖动不再走 drag 场景）',
    SVC, 'petImage.setScene(SceneRegistry.DRAG)', 'petImage.setScene(SceneRegistry.IDLE)'))
results.append(break_and_check(
    'i. 旧素材层被引用回来（custom_webp 读取）',
    PACK, 'private JSONArray clipsArrayOf(Object o) {',
    'private java.io.File legacyDir() { return Prefs.externalWebpDir(ctx); }\n'
    '    private JSONArray clipsArrayOf(Object o) {'))
results.append(break_and_check(
    'j. 设置页旧版素材清理入口被删（升级上来的用户没法释放空间）',
    MAIN, '清理旧版素材（释放空间）', '清理素材XX'))
results.append(break_and_check(
    'm. 新二级页「形象与动作」从 _GRP_NAMES 里被摘掉',
    # ★ v8.0：_GRP_NAMES 末尾多了第 11 项「工作模式」，锚点跟着改。
    MAIN, '"本地发言",\n            "形象与动作", "工作模式"};', '"本地发言"};'))
results.append(break_and_check(
    'n. 形象板块表漏掉新页（回到 {0,2}）',
    MAIN, '{0, 10, 2},            // 形象：主题与显示、形象与动作、表情与素材',
    '{0, 2},                // 形象：主题与显示、表情与素材'))
results.append(break_and_check(
    'o. 漫游开关又退回"已取消"（不读 pref）',
    SVC, 'wanderEnabled = Prefs.wanderEnabled(this);', 'wanderEnabled = false;'))
results.append(break_and_check(
    'p. 走路不再算活动（会被静止自动贴边打断）',
    SVC, '            dockResetIdle();\n            long nowMs = System.currentTimeMillis();',
    '            long nowMs = System.currentTimeMillis();'))
results.append(break_and_check(
    'q. ★★避开顶部 1/3 又变成"默认就有前置位移"（轮次70：桌宠漫游前凭空挪一下）',
    SVC, '        if (avoidTop) wanderAvoidTopThird();', '        wanderAvoidTopThird();'))
results.append(break_and_check(
    'r. 漫游默认值改成关',
    PREFS, 'return sp.getBoolean(KEY_WANDER, true);', 'return sp.getBoolean(KEY_WANDER, false);'))
results.append(break_and_check(
    's. 设置页漫游开关被删',
    MAIN, '"\\uD83D\\uDEB6 自动漫游"', '"\\uD83D\\uDEB6 漫游XX"'))
results.append(break_and_check(
    't. ★onStartCommand 不再前置 startForeground（划卡自救必崩）',
    SVC, '        startForegroundSafely();\n\n        // ---- App 前台/后台', '        // ---- App 前台/后台'))
results.append(break_and_check(
    'u. 归类表漏一个动作（拖拽那个）',
    SCENE_MAP, '"被鼠标拖拽悬空反馈"', '"被鼠标拖拽悬空反馈X"'))
results.append(break_and_check(
    'v. 导入器不再校验路径穿越',
    IMPORTER, 'if (n.contains("..")) return false;', 'if (false) return false;'))
results.append(break_and_check(
    'w. 动作菜单不再读自定义分类',
    SVC, 'ActionMenuConfig.categories(this, CharacterStore.currentId(this))',
    'ActionMenuConfig.categories(this, "off")'))
results.append(break_and_check(
    'x. PetView 不再按角色加载（退回只看内置）',
    VIEW, '_ch != null ? _ch.dir : null', 'null'))
results.append(break_and_check(
    'y. ★导出器又拦死内置角色（本轮的语义变更被回退）',
    EXPORTER,
    '        Result r = new Result();\n',
    '        Result r = new Result();\n'
    '        if (ch == null || ch.dir == null) { r.error = "内置角色不能导出"; return r; }\n'))
results.append(break_and_check(
    'y2. ★导出不叠用户覆盖层（用角色包原样配置，带不走用户的改动）',
    EXPORTER, 'as.applyOverrides(SceneBindings.loadRoot(ctx, ch.id));', ';'))
results.append(break_and_check(
    'y3. ★找不到的素材被静默丢掉（不进 missing）',
    EXPORTER, 'if (f == null) { r.missing.add(id); continue; }', 'if (f == null) { continue; }'))
results.append(break_and_check(
    'z. 表情缓存不再跟角色走（切角色后还是旧表情）',
    MEMES, 'cacheChar = cid;', 'cacheChar = null;'))
results.append(break_and_check(
    'aa. 性格不再读角色包的 persona.md',
    PREFS, 'new java.io.File(ch.dir, "persona.md")', 'new java.io.File(ch.dir, "persona_x.md")'))
# ★ 轮次85：锚点不许硬编码版本号（升到 7.6 时这一项直接 SKIP ⇒ 按未验证计即失败）
_vn_now = '7.6'
try:
    import re as _re
    _m = _re.search(r'android:versionName="([^"]+)"',
                    io.open(MANIFEST, encoding='utf-8').read())
    if _m:
        _vn_now = _m.group(1)
except Exception:
    pass
results.append(break_and_check(
    'k. 版本号与 versionCode 不自洽（%s → 6.5）' % _vn_now,
    MANIFEST, 'android:versionName="%s"' % _vn_now, 'android:versionName="6.5"'))
# ★ 只把**第一个**换行**变成 CRLF（replace 的 count=1）—— 一行 CR 就足以让"源码 LF"变红。
#   实测过：整文件 CRLF 时 javac 照吃、dex 逐字节不变、其它 30 套断言全绿，只有这条看得见。
results.append(break_and_check(
    'bb. ★源码行尾被写成 CRLF（破坏"源码统一 LF"铁律）',
    MEMES, '\n', '\r\n'))

# l. 把素材搬回 APK（复制一个动作进 assets/webp）——文件级破坏，必须清理干净
src_clip = None
for f in sorted(os.listdir(MODEL_WEBP)):
    if f.endswith('.webp'):
        src_clip = os.path.join(MODEL_WEBP, f)
        break
dst_clip = os.path.join(ASSETS_WEBP, '__reverse_tmp__.webp') if src_clip else None
if src_clip:
    try:
        shutil.copyfile(src_clip, dst_clip)
        bad = run_verify()
        os.remove(dst_clip)
        good = run_verify()
    finally:
        if os.path.exists(dst_clip):
            os.remove(dst_clip)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-50s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', 'l. 素材又被搬回 APK（≠1 个）', bad, good))
    results.append(ok)
else:
    print('  [SKIP] l. 素材又被搬回 APK —— model/webp 里没有可复制的素材')
    results.append(None)

print()
print('=' * 62)
bad = [r for r in results if r is False]
skip = [r for r in results if r is None]
if bad or skip:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skip)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
