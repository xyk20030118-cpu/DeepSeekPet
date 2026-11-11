# -*- coding: utf-8 -*-
"""P0 骨架断言：动作系统剥离 / 角色模型 / 旧素材层已删 / 素材分离 / 版本 v7.0。

用户 2026-10-07 定案（详见 STATE.md 待办 0h）：把动作系统独立成「角色包」——
场景清单由代码给（`SceneRegistry`），每个场景挂什么动作由角色包给（`ActionSystem` + `pack.json` 2.0），
APK 只留 1 个默认待机动作，其余 106 个动作随「默认角色包.zip」分发。

★ 本套件里最重要的一条：**素材层必须认识 2.0 的 `scenes`**。
   实测踩过 —— 素材层原来只认 1.x 分组，2.0 配置下 `loaded=false`，
   整个动画包被判失败，`FloatingService` 随即把 `use_builtin_pack` **永久置 false**，
   表现为"桌宠直接不显示"。这条 bug 必须被断言钉住。

反向破坏见 reverse_check_character_skeleton.py。
"""
import glob
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ASSETS = r'D:\petport_lab\petbuild\assets'
MODEL = r'D:\petport_lab\petbuild\model'
MANIFEST = r'D:\petport_lab\petbuild\AndroidManifest.xml'

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。"""
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
            i = n if j < 0 else j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


scene = java('SceneRegistry.java')
asys = java('ActionSystem.java')
pack = java('PetAnimPack.java')
view = java('PetView.java')
svc = java('FloatingService.java')
prefs = java('Prefs.java')
store = java('CharacterStore.java')
pchar = java('PetCharacter.java')
main = java('MainActivity.java')
allsrc = '\n'.join(java(f) for f in os.listdir(SRC) if f.endswith('.java'))

# ─────────────── 一、场景清单（代码侧唯一真相）───────────────
print('== 一、SceneRegistry 场景清单')

check('18 个场景（15 状态 + 3 事件）',
      scene.count(',') > 0 and len(re.findall(r'\{(IDLE|CLICK|INTERACT|DRAG|ROAM|TURN|DOCK|POOL|HOUR|FESTIVAL|TAP_HEAD|TAP_BODY|TAP_TAIL|DOUBLE_TAP|LONG_PRESS),', scene)) == 15
      and len(re.findall(r'\{(EV_BALANCE|EV_WHISPER|EV_WORK),', scene)) == 3,
      '状态 %d / 事件 %d' % (len(re.findall(r'\{(IDLE|CLICK|INTERACT|DRAG|ROAM|TURN|DOCK|POOL|HOUR|FESTIVAL|TAP_HEAD|TAP_BODY|TAP_TAIL|DOUBLE_TAP|LONG_PRESS),', scene)),
                             len(re.findall(r'\{(EV_BALANCE|EV_WHISPER|EV_WORK),', scene))))
check('每个场景都有 label/hint/mode/pick（label/hint 中文字段）',
      scene.count('"loop"') + scene.count('"once"') >= 18 and scene.count('"random"') >= 14)
check('老状态名兼容：move→roam（其余同名保留）',
      'if ("move".equals(st)) return ROAM;' in scene and 'if ("click".equals(st)) return CLICK;' in scene)
check('随机池默认抽取方式是 weighted（"待机随机池"语义）',
      re.search(r'\{POOL,\s*"待机随机池",[^}]*"weighted"\}', scene) is not None)

# ─────────────── 二、ActionSystem（场景层）───────────────
print()
print('== 二、ActionSystem 场景层')

check('按配置自动分流：有 scenes 走 2.0，否则走 1.x 兼容',
      'optJSONObject("scenes") != null' in asys and 'loadV2(root)' in asys and 'loadLegacy()' in asys)
check('1.x 兼容映射走素材层给的两张表',
      'pack.legacySceneMap()' in asys and 'pack.eventMap()' in asys)
check('待机随机池**默认关闭**（待机只播那一个）',
      'private boolean poolEnabled = false;' in asys and 'setPoolEnabled' in asys)
check('只有开了池才把 pool 并进待机抽取（默认动作权重 ×3）',
      'poolEnabled)' in asys and '* 3' in asys.replace('3)', '3)').replace(' *3', ' * 3'))
check('事件场景走 events、其余走 scenes', 'isEvent(scene)' in asys and 'events.get(scene)' in asys)
check('对外接口齐全（pick/clipsOf/allClipIds/sceneIds/eventIds）',
      all(k in asys for k in ('public String pick(', 'public List<String> clipsOf(',
                              'public List<String> allClipIds(', 'public List<String> sceneIds(',
                              'public List<String> eventIds(')))

# ─────────────── 三、★ 素材层必须认 2.0（实测 bug 的钉子）───────────────
print()
print('== 三、素材层认识 2.0 配置（这条是实测 bug 的钉子）')

check('load() 读 2.0 的 scenes 节点', 'optJSONObject("scenes")' in pack)
check('scenes 里的动作进 sceneClips', 'sceneClips.add(' in pack)
check('sceneClips 参与"可播名单"（buildMaster）', 'addUniq(uniq, sceneClips)' in pack)
check('sceneClips 参与 loaded 判定（否则整包被判失败→桌宠不显示）',
      '!sceneClips.isEmpty()' in pack)
check('clips 数组两种写法都吃（简写数组 / {clips:[…]}）',
      'clipsArrayOf(' in pack and 'optJSONArray("clips")' in pack)
check('PetView 真的把配置交给场景层装配',
      'actions.load(animPack.configRoot())' in view and 'actions.attach(animPack)' in view)

# ─────────────── 四、场景化接线（含接上两个死分支）───────────────
print()
print('== 四、场景化接线')

i = view.find('private void playAnimPackState()')
j = view.find('\n    private ', i + 40)          # 取到下一个私有方法为止
body = view[i:j] if i >= 0 and j > i else ''
check('playAnimPackState 函数体取到了（切片非空）', len(body) > 200, 'len=%d' % len(body))
check('playAnimPackState 用场景层挑动作（actions.pick(scene)）', 'actions.pick(scene)' in body)
check('playAnimPackState 不再是硬编码 case "turn"/"drag"/"click" 的 switch',
      'animPack.pickTurn()' not in body and 'animPack.pickDrag()' not in body
      and 'animPack.pickClick()' not in body)
check('新增 setScene / setSceneTemp / playSceneOnce', all(
      k in view for k in ('public void setScene(', 'public void setSceneTemp(', 'public boolean playSceneOnce(')))
check('setAnimState 保留为兼容别名（转发 setScene）',
      'public void setAnimState(String st) {\n        setScene(st);' in view.replace('\r', ''))
check('★ 接上 click 死分支：poke() → playSceneOnce(CLICK)',
      re.search(r'public void poke\(\)\s*\{[^}]*playSceneOnce\(SceneRegistry\.CLICK', view, re.S) is not None)
check('★ 接上 drag 死分支：拖动中 setScene(DRAG)',
      'setScene(SceneRegistry.DRAG)' in svc)
check('PetView 内部把老状态名统一过一遍 SceneRegistry.fromLegacyState',
      'SceneRegistry.fromLegacyState(animPackState)' in view)

# ─────────────── 四点五、设置页 IA（v7.0 重排）───────────────
print()
print('== 四点五、设置页板块（主题与显示 / 形象与动作）')

check('第 0 个二级页已改名「主题与显示」（不再是"外观与显示"）',
      re.search(r'_GRP_NAMES\s*=\s*\{\s*"主题与显示"', main) is not None
      and '"外观与显示"' not in main)
_gi = main.find('_GRP_NAMES')
_gj = main.find('};', _gi)
_grp = main[_gi:_gj] if _gi >= 0 and _gj > _gi else ''
check('_GRP_NAMES 里新增了「形象与动作」', '"形象与动作"' in _grp)
check('首页入口行新增「形象与动作」（ROBOT 图标 + section 10）',
      re.search(r'\{"ROBOT",\s*"形象与动作",[^}]*"10"\}', main) is not None)
check('形象板块 = {0, 10, 2}（主题与显示 / 形象与动作 / 表情与素材）',
      re.search(r'\{0,\s*10,\s*2\},', main) is not None)
check('「外观」块归到 section 10',
      re.search(r'if \(settingsSection == 10\) \{\s*addSection\(box, "\\uD83C\\uDFA8 外观"\);', main) is not None)
check('★ 「角色」块归到 section 10（轮次㉛：形象入口收进它里面）',
      re.search(r'if \(settingsSection == 10\) \{\s*(?://[^\n]*\n\s*)*addSection\(box, "\\uD83C\\uDFAD 角色"\);',
                main) is not None)
check('★ 设置页不再有独立的「桌宠形象」块（用户要求删掉所有形象导入接口）',
      '桌宠形象' not in main)
check('动作系统三件套齐全（当前角色 / 待机随机开关 / 场景总览）',
      'animPoolEnabled' in main and 'setAnimPoolEnabled' in main
      and 'showSceneOverview' in main and '场景与已绑动作' in main)
check('场景总览走 ActionSystem 只读装配',
      'ActionSystem.loadForDisplay(' in main and 'loadForDisplay' in asys)

# ─────────────── 四点六、漫游（v7.0 恢复，默认开）───────────────
print()
print('== 四点六、漫游恢复（默认开 + 边缘自动贴边；★ 轮次70：「避开顶部」默认改成关）')

check('Prefs 四个漫游项齐全且默认值正确（开 / ★关 / 开 / 标准）',
      # ★★ 轮次70：`wander_avoid_top` 默认从 true 改成 **false** —— 它那条"开走前先把桌宠
      #   从顶部 1/3 滑下来"就是用户报的"凭空移动到中间"（依据见 Prefs 里那条注释的逐字原话）。
      'public static boolean wanderEnabled(Context ctx)' in prefs
      and 'sp.getBoolean(KEY_WANDER, true)' in prefs
      and 'sp.getBoolean(KEY_WANDER_AVOID_TOP, false)' in prefs
      and 'sp.getBoolean(KEY_WANDER_AUTODOCK, true)' in prefs
      and 'sp.getInt(KEY_WANDER_SPEED, 1)' in prefs)
check('★ 漫游开关真的从 pref 读（不再是恒 false 的"已取消"）',
      'wanderEnabled = Prefs.wanderEnabled(this);' in svc)
check('漫游/转向走场景名（SceneRegistry.ROAM / TURN）',
      # ★ 轮次60：漫游改用 **lockScene**（漫游期间别的场景抢不走它）——
      #   这条"按写法锚定"的判据必须跟着改（同一轮里第二次踩这个坑：见 verify_scene_exits 的出口表）
      'petImage.lockScene(SceneRegistry.ROAM)' in svc
      # ★ 轮次㊱：转向改成 playSceneOnce(TURN, turnMs) —— 时长取**动作自己的时长**
      #   （原来写死 setSceneTemp(TURN, 1600)，动作比它长就被掐断）
      and 'playSceneOnce(SceneRegistry.TURN, turnMs)' in svc
      and 'setAnimState("move")' not in svc
      and 'setAnimStateTemp("turn"' not in svc)
# ★★ 轮次70：'避开顶部 1/3' 从"漫游的**前置位移**"降级成"用户显式打开才生效的可选项"。
#   旧判据钉的是"有实现且在开走之前调用"（那条写法本身就是用户报的"凭空移动到中间"），
#   现在**反过来钉**：实现还在（开关不是摆设），但调用点被 `if (avoidTop)` 守着
#   ⇒ 默认路径里**没有任何前置位移**。
#   ★ 顺序断言仍要先判 find() >= 0（找不到返回 -1，-1 < 正数恒真 ⇒ 删掉反而"通过"）。
_p_guard = svc.find('if (avoidTop) wanderAvoidTopThird();')
_p_roam = svc.find('petImage.lockScene(SceneRegistry.ROAM)')
check('避开顶部 1/3：实现还在，但只在**用户显式打开**时才会前置移动（默认路径零前置位移）',
      'private void wanderAvoidTopThird()' in svc
      and 'return sp.getBoolean(KEY_WANDER_AVOID_TOP, false);' in prefs
      and _p_guard >= 0 and _p_roam >= 0 and _p_guard < _p_roam,
      'avoid@%d roam@%d' % (_p_guard, _p_roam))
check('走到边缘自动贴边（wanderDockOnArrive + 设置开关 + snapToNearestEdge）',
      'wanderDockOnArrive' in svc and 'Prefs.wanderAutoDock(' in svc
      # ★ 轮次㊱：这段逻辑从 stepWander 挪进了 walkFinish（"走到目标"与"动作播完"是两个时刻），
      #   而 walkFinish 是匿名 Runnable ⇒ 里面必须用 FloatingService.this 而不是 this。
      and 'snapToNearestEdge(); return;' in svc)
check('★ 走路算"有活动"（否则静止自动贴边会把正在散步的桌宠收成竖条）',
      'dockResetIdle();' in svc[svc.find('private void stepWander()'):svc.find('private void stepWander()') + 900])
check('★ 贴边（COLLAPSED）态：**自动**漫游不擅自展开它；**手动**漫游会先展开再走（轮次70）',
      'dockState == PetDockState.COLLAPSED' in svc
      and 'roamAfterExpand = true;' in svc
      and 'if (roamAfterExpand) {' in svc)
# ★ 实测崩溃的钉子：本服务会被 startForegroundService() 拉起（划卡自救 / 看门狗），
#   Android 8+ 要求 5 秒内 startForeground()，否则进程被杀（catch 不住）。
# 判据写成"onStartCommand 的第一条语句就是它" —— 不用 find() 互比顺序
# （find 找不到返回 -1，-1 < 正数恒真，这个坑本文件已经踩过三次）。
_oi = svc.find('public int onStartCommand')
_os = svc.find('private void startForegroundSafely()')
_after = svc[_oi:].split('\n', 1)[1] if _oi >= 0 else ''
_first = next((ln.strip() for ln in _after.split('\n') if ln.strip()), '')
check('★ onStartCommand 的第一条语句就是 startForegroundSafely()（否则划卡自救会被系统杀进程）',
      _os >= 0 and _first.startswith('startForegroundSafely();'), '实际首句: %s' % _first[:60])
check('划卡自救分支仍在（ACTION_RESTART → rebuildViews）',
      'ACTION_RESTART.equals(intent.getAction())' in svc and 'rebuildViews();' in svc)
check('设置页有漫游四项（开关 / 避开顶部 / 自动贴边 / 速度）',
      '"\\uD83D\\uDEB6 自动漫游"' in main
      # ★ 轮次70：文案改了（写明"漫游前先……"，好让用户知道打开它会先动一下）
      and '"\\uD83D\\uDEA7 漫游前先避开屏幕顶部 1/3"' in main
      and '"\\uD83D\\uDCCC 走到屏幕边缘时自动贴边"' in main
      and '"\\u26A1 漫游速度："' in main)

# ─────────────── 四点七、动作归类 / 角色包导入 / 自定义菜单（v7.0）───────────────
print()
print('== 四点七、动作归类 · 角色包导入 · 自定义动作菜单')

_sm_path = os.path.join(MODEL, 'scene_map.json')
check('归类表存在（model/scene_map.json）', os.path.isfile(_sm_path))
_sm = json.load(io.open(_sm_path, encoding='utf-8')) if os.path.isfile(_sm_path) else {}
_scene_ids = [k for k in _sm if k not in ('events', 'pool')]
_all = []
for k, v in _sm.items():
    _all.extend(v if k != 'events' else [n for arr in v.values() for n in arr])
_model_names = set(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(MODEL, 'webp', '*.webp')))
_model_names.add('待机呼吸休闲')
check('归类覆盖包内每个动作（%d 个，无重复无遗漏）' % len(_all),
      len(_all) == len(set(_all)) == len(_model_names) and set(_all) == _model_names,
      '归类 %d / 去重 %d / 素材 %d' % (len(_all), len(set(_all)), len(_model_names)))
check('17 个状态场景 + 3 个事件场景都有归类',
      len(_scene_ids) == 14 and set(_sm.get('events', {}).keys()) == {'balance', 'whisper', 'workStatus'},
      '状态场景 %d' % len(_scene_ids))
check('关键语义映射在（dock=重力下蹲压缩 / tap_tail=鲸鱼尾巴 / double_tap=深度思考）',
      '原地重力下蹲压缩' in _sm.get('dock', [])
      and '用鲸鱼尾巴拍打地面' in _sm.get('tap_tail', [])
      and '深度思考碎碎念' in _sm.get('double_tap', []))
check('打包脚本以 scene_map.json 为唯一真相', 'scene_map.json' in read(r'D:\petport_lab\petbuild\build\pack_character.py'))
check('角色包格式规范文档存在', os.path.isfile(r'D:\petport_lab\petbuild\角色包格式.md'))

# 角色包导入器
imp = java('CharacterImporter.java')
check('导入器存在且做路径穿越校验 / 体积上限',
      'safeName(' in imp and 'MAX_TOTAL_BYTES' in imp
      and 'n.startsWith("/")' in imp and 'n.contains("..")' in imp
      and 'n.indexOf(\'\\\\\') >= 0' in imp)
check('导入的上限容得下官方默认包（≥600MB）', '600L * 1024 * 1024' in imp)
check('导入入口：设置页按钮 + EXTRA_IMPORT_PACK（adb/文件管理器可直达）',
      'pickCharacterPack' in main and 'REQ_IMPORT_CHARACTER' in main
      and 'dsp_import_pack' in main and 'CharacterImporter.install(' in main)
check('导入完成后切角色并重建桌宠',
      'CharacterStore.setCurrentId(this, r.id)' in main and 'Prefs.reloadPet(this)' in main)

# 素材层按角色加载
check('素材层角色感知（角色包优先、内置兜底）',
      'charConfigFile()' in pack and 'charClipFile(' in pack and 'mergeV2(' in pack)
check('PetView 按当前角色加载', 'CharacterStore.current(getContext())' in view
      and '_ch != null ? _ch.dir : null' in view)

# 自定义动作菜单
amc = java('ActionMenuConfig.java')
check('自定义菜单数据层齐全（分类增删改 + 上限）',
      'addCategory' in amc and 'removeCategory' in amc and 'setClips' in amc
      and 'MAX_CATEGORIES' in amc and 'menus' in amc)
check('动作菜单接入自定义分类（且不重复出现在默认分类）',
      'ActionMenuConfig.categories(this, CharacterStore.currentId(this))' in svc
      and 'claimed.contains(n)' in svc)
check('设置页有自定义菜单编辑器（新建/进入/删除/拖拽排序 + 多选保存）',
      # ★ 轮次㉚：多选列表换成 ClipListPane（可搜索），另加"调整分类顺序"（拖拽）
      'showMenuCategoriesEditor' in main and 'promptNewMenuCategory' in main
      and 'promptReorderCategories(cid, labels)' in main
      and 'ActionMenuConfig.setClips(' in main
      and 'ActionMenuConfig.reorderCategories(' in main)

# ─────────────── 四点八、导出角色包 + 表情/性格按角色 ───────────────
print()
print('== 四点八、导出角色包 · 表情/性格按角色')

exp = java('CharacterExporter.java')
check('导出器存在且与导入器对称（产出标准角色包）',
      'public static Result export(' in exp and 'ZipOutputStream' in exp
      and 'anim/pack.json' in exp and 'anim/clips/' in exp)
# ★ 轮次㉑ 语义变更：导出改成"按**当前绑定**打包"（用户追加要求），于是
#   ① 内置角色**也能导出**（用户的追加动作让它不再是空包）——旧断言"内置角色不给导出"已作废；
#   ② 导出必须复用 ActionSystem 的"角色包 + 用户覆盖层"装配语义，而不是 walk 角色目录。
check('★ 导出按有效绑定（角色包 + 用户覆盖层），不是角色目录 walk',
      'as.applyOverrides(SceneBindings.loadRoot(ctx, ch.id))' in exp
      and 'public static Plan plan(ActionSystem as)' in exp and 'PackFormat.packJson(' in exp)
check('★ 内置角色也可以导出（旧拦截已删）；"没有任何改动"是**提示**不是拦截',
      '内置角色不能导出' not in exp and 'ch.dir == null' in exp
      and 'SceneBindings.overrideCount(this, ch.id)' in main and 'startExport(ch)' in main)
check('★ 解析不到素材的动作列进 missing（绝不静默丢）',
      # ★ 判据要精确到那一行：只写 `r.missing.add(id);` 的话，
      #   catch 分支里的同一句会让"解析失败就静默 continue"照样绿（反向项 y3 抓到的）。
      'if (f == null) { r.missing.add(id); continue; }' in exp)
check('导出入口：设置页按钮 + SAF 保存对话框 + REQ_EXPORT_CHARACTER',
      'exportCurrentCharacter' in main and 'ACTION_CREATE_DOCUMENT' in main
      and 'REQ_EXPORT_CHARACTER' in main)

mem = java('Memes.java')
check('表情按角色：角色包的 memes/ 优先，且缓存跟着角色走',
      'charMemeImage(' in mem and 'charMemeManifest(' in mem
      and 'cid.equals(cacheChar)' in mem
      and mem.count('cacheChar = cid;') == 1)   # 轮次㉜ 起只剩一条返回路径（没有 assets 兜底了）
check('性格按角色：角色包的 persona.md 优先',
      'new java.io.File(ch.dir, "persona.md")' in prefs)
# 钉**真实兜底代码**（注释会被 code_only 剥掉，所以不能拿注释当判据）
# ★ 轮次㉜：表情那两条 assets 兜底**已删**（APK 里不再内置表情）——
#   性格的兜底仍在，表情的兜底改成"角色包里有就有、没有就空"。
check('性格仍是兜底（预设 / 自定义文本）',
      'getString(KEY_PERSONA, "")' in prefs and 'DEFAULT_PERSONA' in prefs)
check('★ 表情不再有 assets 兜底（轮次㉜：内置表情已剔除出 APK）',
      'getAssets().open("memes.json")' not in mem
      and 'getAssets().open("memes/" + name + ".png")' not in mem)

# ─────────────── 五、角色模型 ───────────────
print()
print('== 五、角色模型（PetCharacter / CharacterStore）')

check('角色包目录约定齐全（character.json / anim/pack.json / clips / memes / persona.md）',
      all(k in pchar for k in ('F_CHARACTER', 'F_PACK', 'F_CLIPS', 'F_MEMES', 'F_PERSONA')))
check('内置角色 id = builtin:default（只读）', 'BUILTIN_ID = "builtin:default"' in pchar)
check('角色根目录 = filesDir/characters', 'new File(ctx.getFilesDir(), "characters")' in store)
check('当前角色默认内置 + 有 setCurrentId', 'KEY_CURRENT' in store and 'PetCharacter.BUILTIN_ID' in store)
chars = json.load(io.open(os.path.join(ASSETS, 'characters.json'), encoding='utf-8'))
check('assets/characters.json 登记了内置角色',
      any(c.get('id') == 'builtin:default' for c in chars.get('characters', [])))

# ─────────────── 六、旧素材层必须已删干净 ───────────────
print()
print('== 六、旧素材层（custom_webp 等）已停止使用')

legacy_hits = {k: allsrc.count(k) for k in
               ('externalWebpDir', 'externalAnimJson', 'externalLinesJson',
                'useExternalAssets', 'scanExternalClips', 'importAssetZip', 'handleAssetImport')
               if allsrc.count(k)}
check('旧素材层的读取/导入 API 已清零（7 个标识符）', not legacy_hits, '仍有: %s' % legacy_hits)
check('custom_webp 只出现在 Prefs 的清理入口里（不再被读取）',
      allsrc.count('custom_webp') <= 1 and 'custom_webp' in prefs,
      '出现 %d 次' % allsrc.count('custom_webp'))
check('替代入口存在：Prefs.legacyAssetCount / clearLegacyAssets',
      'legacyAssetCount' in prefs and 'clearLegacyAssets' in prefs)
check('设置页：旧导入实现已删净（对话框/实现/结果回调三处都没有）',
      'showImportAssetDialog' not in main and 'importAssetFile' not in main
      and 'importAssetZip' not in main and 'handleAssetImport' not in main)
check('设置页：旧版素材清理入口还在（轮次㉛ 挪到「维护」一节，文案随之改了）',
      '清理旧版素材（释放空间）' in main and 'legacyAssetCount' in main)

# ─────────────── 七、素材分离（防止误把素材搬回 APK）───────────────
print()
print('== 七、素材分离（APK 只带 1 个默认待机动作）')

apk_webp = glob.glob(os.path.join(ASSETS, 'webp', '*.webp'))
model_webp = glob.glob(os.path.join(MODEL, 'webp', '*.webp'))
check('APK 内置动作**只有 1 个**（实际 %d）' % len(apk_webp), len(apk_webp) == 1,
      ', '.join(os.path.basename(p) for p in apk_webp))
check('工程 model/webp 保留 105 个（素材没丢）', len(model_webp) == 105, '实际 %d' % len(model_webp))
check('默认角色包原料齐备（pack_full_1x.json + anim_lines.json）',
      os.path.isfile(os.path.join(MODEL, 'pack_full_1x.json'))
      and os.path.isfile(os.path.join(MODEL, 'anim_lines.json')))
check('默认角色包打包脚本存在', os.path.isfile(r'D:\petport_lab\petbuild\build\pack_character.py'))

# ★ 轮次㉜（用户要求「把当前内置的对话表情也剔除，打包在一起」）：
#   27 张对话表情 + 索引从 assets/ 挪到 build/charpack_src/（**不进 APK**），
#   随**角色包**分发（默认角色包里已经带着它们；导出角色包也会带上）。
MEME_SRC = os.path.join(os.path.dirname(ASSETS), 'build', 'charpack_src')
src_memes = glob.glob(os.path.join(MEME_SRC, 'memes', '*'))
check('APK 的 assets 里没有 memes 目录（表情已剔除）',
      not os.path.isdir(os.path.join(ASSETS, 'memes'))
      and not os.path.isfile(os.path.join(ASSETS, 'memes.json')))
check('素材源挪到 build/charpack_src/（27 张 + 索引，供重建默认角色包）',
      len(src_memes) == 27 and os.path.isfile(os.path.join(MEME_SRC, 'memes.json')),
      '实际 %d 张' % len(src_memes))
check('打包脚本读的是新位置（不再从 assets 拿表情）',
      'charpack_src' in read(r'D:\petport_lab\petbuild\build\pack_character.py')
      and "'assets', 'memes" not in read(r'D:\petport_lab\petbuild\build\pack_character.py'))
_memes = java('Memes.java')
check('表情只从**角色包**读（不再回退 assets/memes）',
      'ctx.getAssets().open("memes' not in _memes
      and 'charMemeManifest(ctx)' in _memes)
check('"包里的表情不可改删"这条语义仍在（isBuiltin → 角色包里的）',
      'public static boolean isBuiltin(Context ctx, String name)' in _memes
      and 'load(ctx).has(name)' in _memes)

# ─────────────── 八、版本号（★ 版本无关：从清单读，做自洽判据）───────────────
#
# ★ 这里原来是硬编码 'versionName="7.5"' / 'versionCode="75"' —— 升到 7.6 时整条假红，
#   而 run_all_tests.bat 遇错即停，害得后面 50 多套根本没跑到（轮次85 抓到的）。
#   判据改成"清单自洽 + 与内置角色索引一致"，以后升版本不用再改这里。
print()
print('== 八、版本号')

mf = read(MANIFEST)
_vn = re.search(r'android:versionName="([^"]+)"', mf)
_vc = re.search(r'android:versionCode="(\d+)"', mf)
_vn = _vn.group(1) if _vn else ''
_vc = _vc.group(1) if _vc else ''
check('清单 versionName 非空', bool(_vn), _vn)
check('清单 versionCode == 版本名去掉点（%s → %s）' % (_vn, _vn.replace('.', '')),
      _vc == _vn.replace('.', ''), 'versionCode=%s' % _vc)
_cj = read(os.path.join(ASSETS, 'characters.json'))
check('内置角色索引版本与清单一致（%s）' % _vn, ('"version": "%s"' % _vn) in _cj)

# ─────────────── 九、源码约定：一律 LF（铁律）───────────────
#
# ★ 为什么值得一条断言（2026-10-07 轮次㉑ 收尾时抓到）：某次编辑把 `Memes.java` 写成了
#   **整文件 CRLF**（548 个 CR），而它当时**没有任何脚本会报**：javac 照吃、dex 逐字节不变、
#   31 套断言全绿 —— 只有人工 `ReadAllBytes` 数 CR 才看得见。
#   铁律写着"源码统一 LF"，那就必须有断言钉住，否则下次还会静默跑偏。
# ★ 判据用**二进制**读（文本模式读会把 CRLF 归一化掉，等于什么都没测）。
print()
print('== 九、源码约定：LF')

src_files = sorted(glob.glob(os.path.join(r'D:\petport_lab\petbuild\src', '**', '*.java'), recursive=True))
crlf = [os.path.basename(p) for p in src_files if b'\r' in io.open(p, 'rb').read()]
check('src 下 %d 个 .java 全部是 LF（没有 CRLF 回潮）' % len(src_files),
      not crlf, 'CRLF 文件: %s' % crlf if crlf else '')
check('源码数量没被意外删掉（≥ 65）', len(src_files) >= 65, '实际 %d' % len(src_files))

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（P0 骨架：动作系统剥离 + 角色模型 + 素材分离 + v7.0）')
