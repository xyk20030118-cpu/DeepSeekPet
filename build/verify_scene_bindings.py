# -*- coding: utf-8 -*-
"""P1 断言：场景动作**可编辑** + 用户覆盖层 + 动作库 + 预览 + 权重真的生效。

用户 2026-10-07 的施工单见 `STATE.md` 待办 **0i**（P1）。要点：
  * 「有哪些场景」由代码固定（SceneRegistry），「每个场景挂哪些动作」默认由角色包决定，
    用户可以**按角色**覆盖其中任意几个场景（SceneBindings，`filesDir/scenes/<角色 id>.json`）；
  * 覆盖是**合并**上去的：只替换被改过的场景，其余场景照旧走角色包；
  * 「恢复角色包默认」= 删掉那条覆盖（不是写一份默认值快照）；
  * 设置页要能**改绑 / 看权重 / 预览 / 恢复默认**，外加一个**动作库**页；
  * `ActionSystem` 必须**保留 pack.json 里写的 weight**（改造前被丢掉，权重从来没生效过）。

★ 本套件最该钉住的两条（都出过或极易出错）：
  1. **覆盖层必须在运行期真的被应用**（`PetView.setAnimPack` 里 load 之后 applyOverrides）——
     只写文件不生效 = 用户改了没反应，且设置页自己看着是"改了"的（最难查的一类）;
  2. **权重不能再被丢掉**（`clipIdsOf` 旧实现只取名字）—— 丢了不会报错，只会"抽得不对"。

反向破坏见 reverse_check_scene_bindings.py（每条都实测过能变红）。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'

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


def body(src, header):
    """截出某个方法的正文（方法在同一文件里都按 4 空格缩进，内层块更深）"""
    i = src.find(header)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(header))
    return src[i:j + 5] if j > 0 else src[i:]


sb = java('SceneBindings.java')
asys = java('ActionSystem.java')
view = java('PetView.java')
main = java('MainActivity.java')
scene = java('SceneRegistry.java')
pack = java('PetAnimPack.java')

# ─────────────── 一、覆盖层按角色隔离 + API 齐全 ───────────────
print('== 一、SceneBindings 用户覆盖层')

check('覆盖层存在，且是 final 工具类（无状态、纯静态）',
      'public final class SceneBindings' in sb and 'private SceneBindings() {}' in sb)
check('存 filesDir/scenes/<角色 id>.json（按角色隔离）',
      # ★ 轮次㉑ 起路径只从一处派生（scenesRoot(filesDir)）：Context 版本与离线版本共用，
      #   判据也跟着看那个函数 —— 但"按角色隔离"这条语义没变。
      'new File(filesDir, DIR)' in sb and 'private static final String DIR = "scenes";' in sb
      and 'static File scenesRoot(File filesDir)' in sb)
check('★ 角色 id 净化复用 ActionMenuConfig.sanitize（不自己再写一份）',
      'ActionMenuConfig.sanitize(charId) + ".json"' in sb)
check('★ 隔离靠的是**传入的 charId**，不是写死的 "default"',
      'sanitize("default")' not in sb and 'sanitize(charId)' in sb)
check('对外接口齐全（读/写/删/计数）',
      all(k in sb for k in ('public static synchronized JSONObject loadRoot(',
                            'public static synchronized List<Bind> bindsOf(',
                            'public static synchronized List<String> clipsOf(',
                            'public static synchronized void setBinds(',
                            'public static synchronized void setClips(',
                            'public static synchronized void setWeight(',
                            'public static synchronized int weightOf(',
                            'public static synchronized void clear(',
                            'public static synchronized void clearAll(',
                            'public static synchronized boolean isOverridden(',
                            'public static synchronized int overrideCount(')))

# ─────────────── 二、文件格式（两种元素写法 + 事件走 events）───────────────
print()
print('== 二、覆盖文件格式')

check('元素两种写法都读（纯名字 / {"id":…,"weight":…}）',
      'if (o instanceof String)' in sb and 'out.add(new Bind(id, j.optInt("weight", 1)));' in sb)
check('也吃 {clips:[…]} 展开写法（与 pack.json 一致）',
      'optJSONArray("clips")' in sb)
check('权重写入：1 写纯名字、>1 写对象（文件才可读）',
      'arr.put(b.id);' in sb and 'j.put("weight", b.weight);' in sb and 'if (b.weight <= 1)' in sb)
check('权重有上下限（1..MAX_WEIGHT），不是原样落盘',
      'public static final int MAX_WEIGHT = 20;' in sb and 'public static int clampWeight(int w)' in sb
      and 'return Math.min(w, MAX_WEIGHT);' in sb)
check('★ 事件场景写进 events 节点（ActionSystem 是两张表）',
      'String node = SceneRegistry.isEvent(scene) ? N_EVENTS : N_SCENES;' in sb)
check('读的时候两张表都收（调用方不必关心写在哪个节点下）',
      'collect(root.optJSONObject(N_SCENES), out);' in sb
      and 'collect(root.optJSONObject(N_EVENTS), out);' in sb)

# ─────────────── 三、★ 恢复默认 = 删覆盖，不是写快照 ───────────────
print()
print('== 三、恢复角色包默认')

clear_b = body(sb, 'public static synchronized void clear(Context ctx, String charId, String scene) {')
check('★ 恢复默认**删掉**那条覆盖（写快照的话角色包更新后就变成旧值）',
      'obj.remove(scene);' in clear_b)
check('"全部恢复"= 写回空配置',
      'save(ctx, charId, emptyRoot());' in body(sb, 'public static synchronized void clearAll('))

# ─────────────── 四、★★ 权重不再被丢掉 ───────────────
print()
print('== 四、权重真的生效（P1 修的第一件事）')

check('★ 旧的"只取名字、丢权重"的实现已经删掉（clipIdsOf 不存在了）',
      'clipIdsOf' not in asys)
check('解析 clip 元素时把 weight 读出来',
      'Math.max(1, j.optInt("weight", 1))' in asys)
check('★ 装配场景时把 weight **带进 Group**（Group.pick 就是按它抽的）',
      'g.add(new PetAnimPack.Clip(c.id, Math.max(1, c.weight), noMirror));' in asys)
check('1.x 兼容映射走权重 1 的清单（老包不受影响）',
      'putScene(scenes, e.getKey(), plainClips(e.getValue()));' in asys
      and 'private List<PetAnimPack.Clip> plainClips(List<String> ids)' in asys)
check('两种 clips 写法都归到同一个解析器（不再有第二条丢权重的路）',
      'putScene(into, id, clipListOf(obj.opt(id)));' in asys)
check('设置页能读出权重（ActionSystem 暴露 bindsOf/weightOf）',
      'public List<PetAnimPack.Clip> bindsOf(String scene)' in asys
      and 'public int weightOf(String scene, String clipId)' in asys)

# ─────────────── 五、★ 覆盖层是"合并"，并且运行期真的应用 ───────────────
print()
print('== 五、覆盖层的合并语义与运行期接线')

apply_b = body(asys, 'public void applyOverrides(org.json.JSONObject root) {')
check('applyOverrides 存在，且读 scenes / events 两张表',
      'applyGroup(root.optJSONObject("scenes"), scenes);' in apply_b
      and 'applyGroup(root.optJSONObject("events"), events);' in apply_b)
check('★ 只替换"覆盖里出现的场景"：**不清空**整张场景表（否则另外 17 个场景会全丢）',
      'scenes.clear()' not in apply_b and 'events.clear()' not in apply_b)
check('未知场景 id 被忽略（设备上的脏数据不该改变装配）',
      'if (!SceneRegistry.isKnown(id)) continue;'
      in body(asys, 'private void applyGroup(org.json.JSONObject obj,'
                    ' Map<String, PetAnimPack.Group> into) {'))
check('覆盖被记下来（isOverridden / overrideCount 供设置页标"已自定义"）',
      'overridden.add(id);'
      in body(asys, 'private void applyGroup(org.json.JSONObject obj,'
                    ' Map<String, PetAnimPack.Group> into) {')
      and 'public int overrideCount()' in asys
      and 'public boolean isOverridden(String scene)' in asys)
check('★ 运行期接线：PetView 在 load 之后 applyOverrides（顺序反了会被角色包盖回）',
      view.find('actions.load(animPack.configRoot());') >= 0
      and view.find('actions.applyOverrides(SceneBindings.loadRoot(getContext(),') >= 0
      and view.find('actions.load(animPack.configRoot());')
      < view.find('actions.applyOverrides(SceneBindings.loadRoot(getContext(),'))
check('运行期覆盖按**当前角色**取（切角色 = 换一套绑定）',
      'CharacterStore.currentId(getContext())' in body(view, 'public void setAnimPack(boolean enable) {'))
disp_b = body(asys, 'public static ActionSystem loadForDisplay(android.content.Context ctx) {')
check('★ 设置页装配也应用覆盖（否则"改完看不见"，恢复默认还会恢复错）',
      'as.applyOverrides(SceneBindings.loadRoot(ctx, cid));' in disp_b)
check('设置页装配用**当前角色**的素材包（原来传 null 只读内置）',
      'p.load(ctx.getApplicationContext(), chDir);' in disp_b
      and 'CharacterStore.current(ctx)' in disp_b)
check('动作库并上素材层的全部可播名单（清空某场景后动作不会从库里消失）',
      'pack.allNames()' in body(asys, 'public List<String> allClipIds() {'))

# ─────────────── 六、★ 设置页：可编辑（不再是只读弹窗）───────────────
print()
print('== 六、设置页「场景与已绑动作」可编辑')

ov_b = body(main, 'private void showSceneOverview() {')
edit_b = body(main, 'private void editScene(final ActionSystem as, final String scene) {')
check('总览改成列表 + 点进去编辑（不再是"知道了"的只读弹窗）',
      'setItems(' in ov_b and 'editScene(as, ids.get(which));' in ov_b
      and 'setPositiveButton("知道了"' not in ov_b)
check('总览标题显示"已自定义 N 个场景"',
      '已自定义 " + as.overrideCount() + " 个' in ov_b)
check('每个场景一行：●/○ + 绑定个数 + 是否已自定义',
      'sceneRowText(as, cid, id)' in ov_b
      and '\\u25CF ' in body(main, 'private String sceneRowText('))
check('★ 场景内的四个动作走 setItems 分流（同一位置只能有一个按钮，两个 neutral 会互相覆盖）',
      'final String[] acts' in main
      and 'setItems(acts, new DialogInterface.OnClickListener()' in edit_b
      and all(k in main for k in ('\\u270F\\uFE0F 编辑绑定（多选）', '\\u25B6\\uFE0F 预览动作',
                                  '\\uD83D\\uDCCF 设置权重', '\\uD83D\\uDD04 恢复角色包默认')))
check('★ editScene 里一个 setNeutralButton 都没有（按钮位留给返回，动作全靠 setItems）',
      'setNeutralButton' not in edit_b)
check('四个分支都接上了',
      'editSceneBindings(as, scene);' in edit_b and 'previewScene(as, scene);' in edit_b
      and 'editSceneWeights(as, scene);' in edit_b and 'restoreSceneDefault(as, scene);' in edit_b)

# ─────────────── 六之二、★★ 链级 lint：setMessage 会静默吞掉 setItems ───────────────
print()
print('== 六之二、AlertDialog 链级 lint（平台会把列表静默吞掉）')

# ★ 这条是 P1 定版"界面看起来有、其实点不开"的永久钉子（模拟器实测 3 次复现）：
#   android.app.AlertController.setupContent() **只在 mMessage == null 时才安装 ListView**
#   ⇒ 同一条 Builder 链上一旦 setMessage(...)，setItems/setMultiChoiceItems 就被静默吞掉，
#   对话框照常弹出、只剩说明文本，列表与所有入口一起消失（源码里看着完全正常）。
#   ★ 必须扫**全工程**，不能只扫 editScene —— 以后别人在别处犯同样的错也要被拦住。
ALERT_ITEMS = ('setItems(', 'setMultiChoiceItems(', 'setSingleChoiceItems(')
chains = 0
violations = []
# ★★ 轮次㊵：所有弹窗都改从 `UiFactory.dialog(...)` 造（设计层唯一入口，负责套令牌主题）
#   ⇒ 这条 lint 的锚点必须**跟着走**。本轮就是这么被抓住的：锚点还写着
#   `new AlertDialog.Builder(`，于是它**扫到 0 条链**（业务代码里已经没有那个写法了），
#   而下面那条"不许空扫"的守卫当场报红 —— 判据悄悄变瞎比判据报错更危险。
ANCHORS = ('new AlertDialog.Builder(', 'UiFactory.dialog(')
for _root, _dirs, _files in os.walk(SRC):
    for _f in sorted(_files):
        if not _f.endswith('.java'):
            continue
        _p = os.path.join(_root, _f)
        _src = io.open(_p, encoding='utf-8', errors='replace').read()
        _i = 0
        while True:
            _hits = [(_src.find(_a, _i), _a) for _a in ANCHORS]
            _hits = [(k, _a) for k, _a in _hits if k >= 0]
            if not _hits:
                break
            _i, _anchor = min(_hits)
            _j = _src.find('.show()', _i)
            if _j < 0:
                _j = min(len(_src), _i + 4000)
            _chain = _src[_i:_j]
            chains += 1
            if any(k in _chain for k in ALERT_ITEMS) and 'setMessage(' in _chain:
                violations.append('%s:%d' % (_f, _src.count('\n', 0, _i) + 1))
            _i += len(_anchor)
check('★ 链级 lint：全工程没有一条 AlertDialog 链同时用 setMessage 与列表（平台只画 message）',
      not violations, '违规: %s' % violations)
check('lint 确实扫到了 Builder 链（不是空扫）', chains >= 40, '链 %d 条' % chains)
check('★ 场景操作面把"触发时机"并进 title（不再与 setItems 抢位置）',
      'SceneRegistry.hint(scene)' in body(main, 'private void editScene(')
      and 'setMessage' not in body(main, 'private void editScene(')
      and 'SceneRegistry.hint(scene)' not in body(main, 'private void editSceneWeights('))

# ─────────────── 七、改绑 / 权重 / 恢复 三条链路 ───────────────
print()
print('== 七、改绑 · 权重 · 恢复默认')

bind_b = body(main, 'private void editSceneBindings(final ActionSystem as, final String scene) {')
# ★ 轮次㉚ 起改绑不再是 `setMultiChoiceItems`（一百多个动作只能滚），
#   改成可搜索/可排序/可"只看已选"的 ClipListPane（multi=true）。
check('★ 改绑走可搜索列表（ClipListPane 多选），动作库来自本角色',
      'final ClipListPane pane = new ClipListPane(this, uiT(), all, counts, true, null);' in bind_b
      and 'as.allClipIds()' in bind_b
      and 'pane.setPicked(bound);' in bind_b and 'pane.pickedIds()' in bind_b)
check('★ 改绑页的"已绑定优先"按**本场景**算（counts = bound.contains(id) ? 1 : 0）',
      'counts[i] = bound.contains(id) ? 1 : 0;' in bind_b)
check('保存写进覆盖层 + 立刻 reloadPet（悬浮窗当场生效）',
      'SceneBindings.setBinds(MainActivity.this, cid, scene, binds);' in bind_b
      and 'Prefs.reloadPet(MainActivity.this);' in bind_b)
check('★ 改绑保留已设过的权重（不把用户调好的 ×N 抹掉）',
      # ★ 轮次㉚：改绑走 ClipListPane，保存时遍历的是**勾选出来的 id**（不再是全表下标）
      'int old = as.weightOf(scene, id);' in bind_b
      and 'new SceneBindings.Bind(id, old < 1 ? 1 : old)' in bind_b)
w_b = body(main, 'private void editSceneWeights(final ActionSystem as, final String scene) {')
check('★ 调权重前先把角色包当前绑定落成覆盖（否则 setWeight 静默失败 = 假功能）',
      'ensureOverride(as, scene);' in main and 'ensureOverride(as, scene);' in w_b)
pick_b = body(main, 'private void pickWeight(final ActionSystem as, final String scene, final String clipId) {')
check('权重档位真的写进覆盖层并回刷',
      'SceneBindings.setWeight(MainActivity.this' in pick_b and 'Prefs.reloadPet(MainActivity.this);' in pick_b)
ens_b = body(main, 'private void ensureOverride(ActionSystem as, String scene) {')
check('ensureOverride 只在"还没覆盖"时落一份（不覆盖用户已改的）',
      'if (SceneBindings.isOverridden(this, cid, scene)) return;' in ens_b
      and 'SceneBindings.setBinds(this, cid, scene, binds);' in ens_b)
rest_b = body(main, 'private void restoreSceneDefault(final ActionSystem as, final String scene) {')
check('★ 「恢复角色包默认」调 SceneBindings.clear（删覆盖），并立刻生效',
      'SceneBindings.clear(MainActivity.this, cid, scene);' in rest_b
      and 'Prefs.reloadPet(MainActivity.this);' in rest_b)
check('本来没改过的场景给出明确提示（不假装恢复成功）',
      'if (!SceneBindings.isOverridden(this, cid, scene))' in rest_b)
clearall_b = body(main, 'private void promptClearAllBindings() {')
check('「全部恢复角色包默认」走 clearAll',
      'SceneBindings.clearAll(MainActivity.this, cid);' in clearall_b)

# ─────────────── 八、预览（走 getDrawable，不绕后台服务）───────────────
print()
print('== 八、预览')

prev_b = body(main, 'private void previewClip(final ActionSystem as, final String scene, final String clipId) {')
check('★ 预览直接用 PetAnimPack.getDrawable（不必绕后台服务）',
      'pk.getDrawable(clipId)' in prev_b)
check('放进 ImageView 并显式 start（动图才会真的动）',
      'iv.setImageDrawable(dr);' in prev_b and 'aid.setRepeatCount(-1);' in prev_b
      and 'try { aid.start(); } catch (Throwable ignore) {}' in prev_b)
check('素材缺失时如实说明，而不是弹一个空白框',
      '找不到这个动作的素材' in prev_b)
check('单个动作直接预览、多个先让用户挑',
      'if (bound.size() == 1) { previewClip(as, scene, bound.get(0)); return; }' in main)
check('动作库里也能预览（scene 传 null 时有兜底文案）',
      # ★ 轮次㉚：动作库换成 ClipListPane（单选回调拿到的就是动作名，不再按下标取）
      'previewClip(as, null, id);' in main
      and '(scene == null) ? "动作库"' in main)

# ─────────────── 九、动作库页 + 设置页入口 ───────────────
print()
print('== 九、动作库')

lib_b = body(main, 'private void showClipLibrary(final ActionSystem as) {')
check('动作库列出全部动作并标出"已绑到几个场景"',
      # ★ 轮次㉚：计数在打开对话框时**预先算好**（列表会随搜索/排序重建，
      #   不能在渲染每一行时现算），所以判据看那句预先计算
      'as.allClipIds()' in lib_b and 'counts[i] = boundSceneCount(as, all.get(i));' in lib_b)
check('统计覆盖全部 18 个场景（含事件）',
      'for (String s : SceneRegistry.allIds()) if (as.clipsOf(s).contains(clipId)) n++;' in main)
check('★ 设置页有动作库入口（不是只能从总览的按钮进）',
      '{ showClipLibrary(); }' in main)
check('设置页有「全部恢复角色包默认」入口',
      'promptClearAllBindings();' in main)
check('设置页说明了改动的存放位置（按角色、不改角色包）',
      '改动按角色保存、存在本机，角色包文件本身不会被改动。' in main)
check('场景清单仍是代码侧唯一真相（P1 没有把场景表搬进数据）',
      'public static String[] ids()' in scene and 'private static final String[][] TABLE' in scene)

# ─────────────── 十、素材层接口没被改坏 ───────────────
print()
print('== 十、素材层（预览要用的那两个接口还在）')

check('PetAnimPack.getDrawable / clipDurationMs / allNames 仍是 public',
      'public Drawable getDrawable(String id)' in pack
      and 'public int clipDurationMs(String id, int defaultMs)' in pack
      and 'public java.util.List<String> allNames()' in pack)
check('Group 仍按权重抽（权重才有意义）',
      'int t = r.nextInt(Math.max(1, totalWeight));' in pack)

print()
if fails:
    print('!! %d 条断言失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（P1：场景动作可编辑 + 覆盖层 + 动作库 + 预览 + 权重）')
