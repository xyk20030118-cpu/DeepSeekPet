# -*- coding: utf-8 -*-
"""反向验证：verify_scene_exits.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。

覆盖两件事：
  1. **场景出口**（用户报「有些动作选项无法触发」）：部位点击 / 连戳 / 双击 / 长按 / 整点 / 贴边；
     ★ 还有一项是这套件存在的理由：**往场景清单里加一行却不接触发点 ⇒ 必须变红**
     （`SceneRegistry` 的类注释当年承诺过"结构上不会再出现"，但没有判据兜着，于是又长回来了）；
  2. **漫游时长**（用户报「漫游播放之后动作会卡，播放不完就切换了」）：
     走多久由动作时长决定、到位后等动作播完、每步 80ms、转向也用动作时长。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_scene_exits.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
REG = os.path.join(SRC, 'SceneRegistry.java')
SVC = os.path.join(SRC, 'FloatingService.java')
UI = os.path.join(HERE, 'verify_scene_exits_ui.py')


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


print('== 反向验证：场景出口 + 漫游时长 ==')
results = []

# ─────────────── 一、★ 结构性：加场景不接触发点 ───────────────
results.append(break_and_check(
    'a. ★★往场景清单加一行却忘了接触发点（本套件存在的理由）',
    REG,
    '            {POOL,       "待机随机池",  "默认关闭；开启后待机会随机换动作",     "loop", "weighted"},',
    '            {POOL,       "待机随机池",  "默认关闭；开启后待机会随机换动作",     "loop", "weighted"},\n'
    '            {"brand_new_scene", "新场景", "忘了接触发点", "once", "random"},'))

# ─────────────── 二、部位点击 ───────────────
results.append(break_and_check(
    'b. ★单击不再把部位交给素材层（用户报的原样）',
    SVC,
    'playPartScene(part);',
    ';'))

results.append(break_and_check(
    'c. ★部位映射写错（戳头演成"戳身"）',
    REG,
    'if ("head".equals(part)) return TAP_HEAD;',
    'if ("head".equals(part)) return TAP_BODY;'))

results.append(break_and_check(
    'd. ★某个部位映射直接返回 null（那一项又点不出来）',
    REG,
    'if ("tail".equals(part)) return TAP_TAIL;',
    'if ("tail".equals(part)) return null;'))

results.append(break_and_check(
    'e. 部位动作不再走"没绑就静默跳过"的入口（改成直接 playSceneOnce）',
    SVC,
    '        petImage.playSceneOnce(scene, fallbackMs);',
    '        petImage.playSceneOnce(scene + "x", fallbackMs);'))

# ─────────────── 三、连戳 / 双击 / 长按 / 整点 / 贴边 ───────────────
results.append(break_and_check(
    'f. ★连戳不再出互动动作',
    SVC,
    'if (tapStreak >= 3) playSceneOnceSafe(SceneRegistry.INTERACT, 1200);',
    'if (false) playSceneOnceSafe(SceneRegistry.INTERACT, 1200);'))

results.append(break_and_check(
    'g. ★连击计数挪回 petTapTalk（纯本地档永远停在 0）',
    SVC,
    # ★ 锚点跟着轮次64 的分流改造走：那句 `!Prefs.hasApiKey(this)` 已换成
    #   `!chatBackendReady()`（在线要 Key、本地模型不要）。要守的性质没变。
    '        long now = System.currentTimeMillis();\n\n'
    '        if (localOnly() || !Prefs.chatEnabled(this) || !chatBackendReady()) {',
    '        long now = System.currentTimeMillis();\n'
    '        if (now - lastTapStreakMs < 4000) tapStreak++;\n'
    '        else tapStreak = 0;\n'
    '        lastTapStreakMs = now;\n\n'
    '        if (localOnly() || !Prefs.chatEnabled(this) || !chatBackendReady()) {'))

results.append(break_and_check(
    'h. ★双击不再出动作',
    SVC,
    'playSceneOnceSafe(SceneRegistry.DOUBLE_TAP, 1200);',
    ';'))

results.append(break_and_check(
    'i. ★长按不再出动作',
    SVC,
    'playSceneOnceSafe(SceneRegistry.LONG_PRESS, 1200);',
    ';'))

results.append(break_and_check(
    'j. ★整点报时不再出动作',
    SVC,
    'playSceneOnceSafe(SceneRegistry.HOUR, 2000);',
    ';'))

results.append(break_and_check(
    'k. ★贴边不再出动作',
    SVC,
    'if (!wasCollapsed) playSceneOnceSafe(SceneRegistry.DOCK, 1200);',
    'if (false) playSceneOnceSafe(SceneRegistry.DOCK, 1200);'))

results.append(break_and_check(
    'l. ★贴边动作播在 stopWander() 之前（被它的 setScene(IDLE) 盖掉）',
    SVC,
    '        stopWander();\n'
    '        // ★ 轮次㊱：贴边场景（dock）此前**全工程零引用** —— 用户报「贴边」这个动作选项\n'
    '        //   无法触发。放在 stopWander() **之后**：它会 setScene(IDLE)，先播就被它盖掉了。\n'
    '        if (!wasCollapsed) playSceneOnceSafe(SceneRegistry.DOCK, 1200);',
    '        if (!wasCollapsed) playSceneOnceSafe(SceneRegistry.DOCK, 1200);\n'
    '        stopWander();'))

# ─────────────── 四、漫游时长（用户报障的根因）───────────────
results.append(break_and_check(
    'm. ★★到位就切 idle（不等动作播完/后摇 —— 用户报的原样）',
    SVC,
    '                long rest = walkSceneEndAtMs - nowMs;\n',
    '                long rest = 0;\n'))

results.append(break_and_check(
    'n. ★★走多久不再看动作时长（退回固定值）',
    SVC,
    '            roamMs = petImage.currentClipDurationMs(3000);',
    '            roamMs = 3000;'))

results.append(break_and_check(
    'o. ★每步间隔退回 40ms（25Hz 发窗口更新 ⇒ 动作卡）',
    SVC,
    'private static final long WANDER_STEP_MS = 80;',
    'private static final long WANDER_STEP_MS = 40;'))

results.append(break_and_check(
    'p. ★转向又写死 1600ms（比动作短就被掐断）',
    SVC,
    'turnMs = petImage.peekSceneDurationMs(SceneRegistry.TURN, 1600);',
    'turnMs = 1600;'))

results.append(break_and_check(
    'q. ★★到位不再精确落点（又留 6px 误差）',
    SVC,
    '                params.x = wanderTargetX;',
    '                params.x = wanderTargetX - 6;'))

results.append(break_and_check(
    'r. 每步又 new 一个 Runnable（高频路径的 GC 抖动）',
    SVC,
    'wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);',
    'wanderHandler.postDelayed(new Runnable() { public void run() { stepWander(); } }, WANDER_STEP_MS);'))

# ─────────────── 五、设备脚本 ───────────────
results.append(break_and_check(
    's. ★设备脚本改成钉用户真机（会往用户手机上装包）',
    UI,
    'DEV = V.DEV',
    'DEV = "<设备序列号>"'))

# ─────────────── 六、★ 轮次㊸：漫游入口 / 方向 / 时长 / 落点 ───────────────
SVCJ = os.path.join(SRC, 'FloatingService.java')
VIEWJ = os.path.join(SRC, 'PetView.java')

# t. ★★ 「主动漫游」入口从三击菜单里消失（用户要求的那一条）
results.append(break_and_check(
    't. ★★「主动漫游」入口从菜单里消失',
    SVCJ,
    'flatList.addView(roam, rlp);',
    ';'))

# u. ★★ 手动漫游不再走与自动漫游同一条路径（各写一份 ⇒ 必然漂移）
results.append(break_and_check(
    'u. ★★手动漫游不再复用 roamOnce（各写一份）',
    SVCJ,
    'if (!roamOnce(false)) wanderHandler.postDelayed(this, 2000);',
    'wanderHandler.postDelayed(this, 2000);'))

# v. ★★ 镜像判据又回到"假设素材原生朝右"（两个方向都反）
results.append(break_and_check(
    'v. ★★镜像判据又假设素材原生朝右',
    VIEWJ,
    '(faceDir != BASE_FACE_DIR) && !c.noMirror',
    '(faceDir < 0) && !c.noMirror'))

# w. ★★ 漫游时长又改回 peek（抽一个、播另一个 ⇒ 动作被从中间掐断）
results.append(break_and_check(
    'w. ★★漫游时长又用 peekSceneDurationMs（会掐断动作）',
    SVCJ,
    'roamMs = petImage.currentClipDurationMs(3000);',
    'roamMs = petImage.peekSceneDurationMs(SceneRegistry.ROAM, 3000);'))

# x. ★★ 位移又改回"剩余距离 ÷ 剩余步数"（提前到位 ⇒ 位置不对应）
results.append(break_and_check(
    'x. ★★位移又按"剩余距离 ÷ 剩余步数"',
    SVCJ,
    'int x = walkStartX + (int) Math.round((wanderTargetX - walkStartX) * (double) t);',
    'int stepsLeft = Math.max(1, (int)((walkEndAtMs - nowMs) / WANDER_STEP_MS));\n'
    '            int x = params.x + (int) Math.signum(wanderTargetX - params.x)\n'
    '                    * Math.max(1, (int) Math.ceil(Math.abs(wanderTargetX - params.x)\n'
    '                            / (double) stepsLeft));'))

# y. ★★ 三击待定又绑回对话开关（关掉对话后三击菜单打不开）
results.append(break_and_check(
    'y. ★★三击待定又绑回对话开关',
    SVCJ,
    '} else if (doubleTap) {',
    '} else if (doubleTap && Prefs.chatEnabled(FloatingService.this)) {'))

# ─────────────── 七、★★ 轮次㊻：前摇 / 后摇 ───────────────
MAINJ = os.path.join(SRC, 'MainActivity.java')

# z1. ★★ 前摇被去掉（动画一开播人就窜出去 —— 就是用户报的"动画播放逻辑不对"）
results.append(break_and_check(
    'z1. ★★去掉漫游前摇（开播即走）',
    SVC,
    'walkStartAtMs = clipStart + lead;',
    'walkStartAtMs = clipStart;'))

# z2. ★★ 后摇改回"人一停就回待机"（动画的收尾被掐掉）
results.append(break_and_check(
    'z2. ★★去掉漫游后摇（走到就回待机）',
    SVC,
    'walkEndAtMs = clipStart + total - tail;',
    'walkEndAtMs = clipStart + total;'))

# z3. ★ 设置页的两个可调项被删
results.append(break_and_check(
    'z3. ★漫游前摇设置项被删',
    MAINJ,
    'Prefs.setWanderLeadMs(MainActivity.this, Math.max(0, ms));',
    ';'))

# z4. ★★ 镜像又绕 drawable 的 intrinsic 中心翻（往右跑时整幅画面会平移）
results.append(break_and_check(
    'z4. ★★镜像又绕素材 intrinsic 中心翻',
    VIEWJ,
    'animMatrix.setScale(-1f, 1f, getWidth() / 2f, 0f);',
    'animMatrix.setScale(-1f, 1f, getDrawable().getIntrinsicWidth() / 2f, 0f);'))

# z5. ★★ 镜像时不再互换左右边距（贴边/钳制/气泡按没翻过的边距算）
results.append(break_and_check(
    'z5. ★★镜像时不再互换内容边距',
    VIEWJ,
    'return new float[]{ in[1], in[0], in[2], in[3] };',
    'return in;'))

# z6. ★★ 按动作的覆盖层被忽略（用户单独设的前后摇不生效，静默回退全局）
results.append(break_and_check(
    'z6. ★★按动作的前后摇覆盖被忽略',
    SVC,
    'if (ov != null) { lead = ov.lead; tail = ov.tail; byClip = true; }',
    'if (false) { lead = ov.lead; tail = ov.tail; byClip = true; }'))

# z7. ★★ 档位退回 6 档（用户要求"选项多一点"）
results.append(break_and_check(
    'z7. ★★前后摇档位退回 6 档',
    os.path.join(SRC, 'MainActivity.java'),
    '-1, 0, 200, 400, 600, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000',
    '-1, 0, 500, 1000, 1500, 2000, 3000'))

# z8. ★★ 贴边动作从 snapToNearestEdge 上摘掉（用户走的那条路又不播了）
results.append(break_and_check(
    'z8. ★★贴边动作不再挂在 snapToNearestEdge 上',
    SVC,
    'playDockFeedback(startX, targetX, minX);',
    ';'))

# z9. ★★ 贴边反馈里不再播场景（只打日志、动作不播）
results.append(break_and_check(
    'z9. ★★贴边反馈不再真的播 dock 场景',
    SVC,
    'private void playDockFeedback(int startX, int targetX, int minX) {\n'
    '        playSceneOnceSafe(SceneRegistry.DOCK, 1200);',
    'private void playDockFeedback(int startX, int targetX, int minX) {'))

# z10. ★★ 漫游方向退回"掷骰子"（贴边时一半概率朝墙走 ⇒ 点了没反应）
results.append(break_and_check(
    'z10. ★★漫游方向退回随机',
    SVC,
    'int dir = ContentInset.roamDirAway(minX, maxX, cur);',
    'int dir = Math.random() < 0.5 ? -1 : 1;'))

# z11. ★★ 纯类里的方向规则被改成"永远往右"（贴右边时就朝墙了）
results.append(break_and_check(
    'z11. ★★方向规则不再看贴哪边',
    os.path.join(SRC, 'ContentInset.java'),
    'if (right <= EDGE_PX) return -1;               // 贴右 ⇒ 往左',
    ''))

# z12. ★★ 漫游不再上锁（别的场景又能把它顶掉 = 用户报的那个 bug 回来了）
results.append(break_and_check(
    'z12. ★★漫游不再锁场景',
    SVC,
    'if (petImage.isAnimPack()) petImage.lockScene(SceneRegistry.ROAM);',
    'if (petImage.isAnimPack()) petImage.setScene(SceneRegistry.ROAM);'))

# z13. ★★ 少一处解锁（stepWander 早退那条路 ⇒ 场景永久锁死）
results.append(break_and_check(
    'z13. ★★stepWander 早退不再解锁',
    SVC,
    '            if (!wandering || params == null || root == null) {\n'
    '                wandering = false;\n'
    '                unlockRoamScene();\n'
    '                return;\n'
    '            }',
    '            if (!wandering || params == null || root == null) { wandering = false; return; }'))

# z14. ★★ 场景锁对"临时场景"不生效（真凶就是临时场景：无条件替换 + 到点回 IDLE）
results.append(break_and_check(
    'z14. ★★锁对临时场景不生效',
    os.path.join(SRC, 'PetView.java'),
    '    public void setSceneTemp(final String scene, long ms) {\n'
    '        if (animPack == null || scene == null) return;\n'
    '        if (blockedByLock(scene)) return;',
    '    public void setSceneTemp(final String scene, long ms) {\n'
    '        if (animPack == null || scene == null) return;'))

# z15. ★★ 手点动作不再上锁（又被自动场景打断 = 用户报的那个 bug 回来了）
results.append(break_and_check(
    'z15. ★★手点动作不再上锁',
    SVC,
    'String id = petImage.playAnimByName(name, true);',
    'String id = petImage.playAnimByName(name);'))

# z16. ★★ 用户碰桌宠不再解锁（手点动作播放期间点一下会"没反应"）
results.append(break_and_check(
    'z16. ★★用户碰桌宠不再解锁',
    SVC,
    '                    if (petImage != null) petImage.unlockAction();',
    '                    ;'))

# z17. ★★ 锁不再挡场景（blockedByLock 不看 actionLocked）
results.append(break_and_check(
    'z17. ★★锁不再挡场景',
    os.path.join(SRC, 'PetView.java'),
    '        if (actionLocked) {',
    '        if (false) {'))

# z18. ★★ 播完不解锁（要等定时器兜底 ⇒ 长动作后面会被莫名挡住一段时间）
results.append(break_and_check(
    'z18. ★★播完不解锁',
    os.path.join(SRC, 'PetView.java'),
    '                if (ex) unlockAction();',
    '                ;'))

# z19. ★★ 轮次70：前置位移又变成"默认就挪"（用户报的"凭空移动到中间"）
results.append(break_and_check(
    'z19. ★★避开顶部又变回默认开（漫游前先挪一下）',
    os.path.join(SRC, 'Prefs.java'),
    'return sp.getBoolean(KEY_WANDER_AVOID_TOP, false);',
    'return sp.getBoolean(KEY_WANDER_AVOID_TOP, true);'))

# z20. ★★ 轮次70：前摇里又把 x 瞬间拉回起点（唯一那处瞬时赋值回潮）
results.append(break_and_check(
    'z20. ★★前摇里又出现瞬时赋值（把 x 写回起点）',
    SVC,
    '                wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);\n'
    '                return;\n'
    '            }',
    '                if (params.x != walkStartX) { params.x = walkStartX; }\n'
    '                wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);\n'
    '                return;\n'
    '            }'))

# z21. ★★ 轮次70：漫游钳制不再用冻结范围（换 clip 会把窗口重新夹一遍 = 凭空挪）
results.append(break_and_check(
    'z21. ★★漫游期间不再用冻结范围钳制',
    SVC,
    'if (roamFrozenX && roamMaxX >= roamMinX) { minX = roamMinX; maxX = roamMaxX; }',
    'if (false && roamMaxX >= roamMinX) { minX = roamMinX; maxX = roamMaxX; }'))

# z22. ★★ 轮次70：竖条态又不先展开（"任何位置都能漫游"被回退）
results.append(break_and_check(
    'z22. ★★竖条态不再先展开再漫游',
    SVC, '                roamAfterExpand = true;', '                roamAfterExpand = false;'))

# z23. ★★ 轮次70：展开完了不续上漫游（点了没反应）
results.append(break_and_check(
    'z23. ★★展开完成后不再续上漫游',
    SVC, '                if (roamAfterExpand) {', '                if (false) {'))

# z24. ★★ 轮次70：开走前那行读数被删（设备上再也看不出有没有被挪）
results.append(break_and_check(
    'z24. ★★"开走前"读数被换掉',
    SVC, '"开走前 x="', '"开走前X="'))

print()
print('=' * 62)
n_ok = sum(1 for r in results if r)
n_skip = sum(1 for r in results if r is None)
n_all = len(results)
if n_skip:
    print('%d 项跳过（按未验证计）' % n_skip)
if n_ok != n_all:
    print('!! 只有 %d/%d 个破坏被抓到（说明有断言是空的）' % (n_ok, n_all))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (n_ok, n_all))
print('ALL REVERSE CHECKS PASSED')
