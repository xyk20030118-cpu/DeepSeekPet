# -*- coding: utf-8 -*-
"""场景出口断言：**清单里的每个场景都必须有人触发**（轮次㊱）。

为什么单开一套（用户报障换来的一条结构性判据）：
    用户报「**有些动作选项无法触发** 比如点击头部点击尾部点击身体 贴边」。
    查下来根因是：`SceneRegistry` 里定义了场景、设置页也能选、角色包里也有素材，
    但 **`tap_head` / `tap_body` / `tap_tail` / `dock` / `double_tap` / `long_press` /
    `interact` / `hour` 八个场景全工程零引用** —— 没人把场景交给素材层。
    `SceneRegistry` 的类注释当年写过"场景变成数据之后，这些'有素材、没出口'的问题
    在结构上就不会再出现了"，**但那条承诺从来没有判据兜着**，所以又长回来了。

⇒ 本套件第一节就是那条判据：**把场景清单与"出口表"对起来，缺一个就红**。
   以后往 `SceneRegistry` 加场景而忘了接触发点，这里立刻报红（反向套件里专门有一项验它）。

第二节起是每个出口的细节（顺序、前提、时长），第三节是漫游时长那条用户报障的根因。
反向破坏见 reverse_check_scene_exits.py。
"""
import io
from artifacts import dev_apk
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DEV_APK = dev_apk()

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"' or c == "'":
            q = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                if ch == '\\' and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if ch == q:
                    break
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def method_body(src, sig):
    """截出某个方法的正文（同文件里方法按 4 空格缩进收尾）。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(sig))
    return src[i:j + 6] if j > 0 else src[i:]


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd or HERE, capture_output=True)
    out = r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')
    return r.returncode, out


REG = java('SceneRegistry.java')
SVC = java('FloatingService.java')
VIEW = java('PetView.java')
ACT = java('ActionSystem.java')

# ═══════════════ 一、★ 结构性判据：清单里每个场景都要有出口 ═══════════════
print()
print('== 一、★ 场景清单 × 出口表：缺一个就红 ==')

def scene_ids_from(src):
    """场景 id = 常量声明 ∪ TABLE/EVENTS 行首。

    ★ 两条路都要收：文档写的加场景方式是"在 TABLE 里加一行"（那行首可能是常量名，
      也可能有人直接写字面量）；而常量声明那条能抓到"加了常量却忘了进 TABLE"。
      只认其中一条，就会出现"加了场景但本套件看不见"的假绿。
    """
    consts = dict(re.findall(r'public static final String ([A-Z_]+) = "([A-Za-z_]+)";', src))
    ids = set(consts.values())
    for m in re.finditer(r'^\s*\{([A-Za-z_"]+)[^}]*\},\s*$', src, re.M):
        first = m.group(1)
        if first.startswith('"'):
            ids.add(first.strip('"'))
        elif first in consts:
            ids.add(consts[first])
    return sorted(ids)


# 场景 id 从 SceneRegistry 里解析（常量声明 + TABLE/EVENTS 行首，见函数说明）
scene_ids = scene_ids_from(REG)

# 出口表：场景 id → (出处文件, 必须出现的锚点, 人话说明)
#   ★ 加场景就要在这里加一行 —— 这正是本套件存在的意义。
EXITS = [
    ('idle',       'FloatingService.java', 'setScene(SceneRegistry.IDLE)',              '待机（漫游结束 / 停漫游时回去）'),
    ('click',      'PetView.java',         'playSceneOnce(SceneRegistry.CLICK',         '戳一下（poke）'),
    ('interact',   'FloatingService.java', 'playSceneOnceSafe(SceneRegistry.INTERACT',  '被连戳（≥3 次）'),
    ('drag',       'FloatingService.java', 'setScene(SceneRegistry.DRAG)',              '按住拖动中'),
    # ★ 轮次60：漫游改用 `lockScene`（漫游期间别的场景抢不走它）⇒ 这条"按写法锚定"的
    #   出口判据必须跟着改（工程记过的教训：迁移改了写法，锚在旧写法上的 lint 会当场假红）
    ('roam',       'FloatingService.java', 'lockScene(SceneRegistry.ROAM)',              '自动走动中（锁住不被抢）'),
    ('turn',       'FloatingService.java', 'peekSceneDurationMs(SceneRegistry.TURN',    '漫游时原地转向'),
    ('dock',       'FloatingService.java', 'playSceneOnceSafe(SceneRegistry.DOCK',      '吸附成竖条那一下'),
    ('pool',       'ActionSystem.java',    'SceneRegistry.POOL',                        '待机随机池（开关打开才并进 idle）'),
    ('hour',       'FloatingService.java', 'playSceneOnceSafe(SceneRegistry.HOUR',      '整点报时'),
    ('festival',   'PetView.java',         'SceneRegistry.FESTIVAL',                    '节日/时段命中'),
    ('tap_head',   'SceneRegistry.java',   'if ("head".equals(part)) return TAP_HEAD;',  '戳到头'),
    ('tap_body',   'SceneRegistry.java',   'if ("body".equals(part)) return TAP_BODY;',  '戳到身'),
    ('tap_tail',   'SceneRegistry.java',   'if ("tail".equals(part)) return TAP_TAIL;',  '戳到尾'),
    ('double_tap', 'FloatingService.java', 'playSceneOnceSafe(SceneRegistry.DOUBLE_TAP', '确认是双击时'),
    ('long_press', 'FloatingService.java', 'playSceneOnceSafe(SceneRegistry.LONG_PRESS', '长按弹菜单前'),
    ('balance',    'FloatingService.java', 'playNamedAnim(BALANCE_ANIMS[band]',          '余额档位变化'),
    ('whisper',    'FloatingService.java', 'playEventAnim("whisper")',                   '主动搭话'),
    ('workStatus', 'FloatingService.java', 'playEventAnim("workStatus")',                '前台=工作/办公'),
]

check('场景清单解析出来（18 个：15 状态 + 3 事件）', len(scene_ids) == 18, scene_ids)
table_ids = [r[0] for r in EXITS]
check('★ 出口表覆盖清单里的**每一个**场景（少一个就红）',
      sorted(table_ids) == scene_ids,
      '缺=%s 多=%s' % (sorted(set(scene_ids) - set(table_ids)),
                      sorted(set(table_ids) - set(scene_ids))))
check('出口表没有重复行', len(table_ids) == len(set(table_ids)))

_files = {}
for sid, f, needle, why in EXITS:
    if f not in _files:
        _files[f] = java(f) if f != 'SceneRegistry.java' else REG
    check('出口：%-10s %s（%s）' % (sid, why, f), needle in _files[f], needle)

# ═══════════════ 二、部位点击：点哪演哪 ═══════════════
print()
print('== 二、部位点击（用户报障的主项）==')
check('★ 部位 → 场景的映射是**纯函数**（设置页清单/点击/断言共用一份）',
      'public static String partScene(String part)' in REG)
check('★ 单击分支真的调它（识别部位之后就把场景交给素材层）',
      'playPartScene(part);' in SVC)
check('部位动作走 playSceneOnceSafe（角色包没绑就静默跳过，不崩不空转）',
      'private void playSceneOnceSafe(String scene, long fallbackMs)' in SVC
      and 'petImage.playSceneOnce(scene, fallbackMs);' in SVC)
check('部位识别仍是三段（上 32% 头 / 下 32% 尾 / 中间身）',
      'if (ratio < 0.32f) return "head";' in SVC and 'if (ratio > 0.68f) return "tail";' in SVC)
i_part = SVC.find('playPartScene(part);')
i_quip = SVC.find('petPartFeedback(part);')
check('顺序：先播部位动作、再报台词（两句都在单击分支里）', 0 <= i_part < i_quip,
      'playPartScene@%d petPartFeedback@%d' % (i_part, i_quip))

print()
print('== 三、连戳 / 双击 / 长按 / 整点 也要有动作 ==')
check('★ 连戳计数挪到**点击处理**里（原来只在 petTapTalk，纯本地档永远停在 0）',
      'tapStreak++' in SVC and 'tapStreak++' not in method_body(SVC, 'private void petTapTalk('))
check('被连戳（≥3）⇒ 互动场景', 'if (tapStreak >= 3) playSceneOnceSafe(SceneRegistry.INTERACT' in SVC)
check('双击确认后才播 double_tap（350ms 内还可能变三击）',
      'playSceneOnceSafe(SceneRegistry.DOUBLE_TAP, 1200);' in SVC)
i_dt = SVC.find('playSceneOnceSafe(SceneRegistry.DOUBLE_TAP')
i_ask = SVC.find('showAskBar();', i_dt)
check('double_tap 在 showAskBar() 之前播', 0 <= i_dt < i_ask)
check('长按弹菜单前播 long_press',
      'playSceneOnceSafe(SceneRegistry.LONG_PRESS, 1200);' in SVC)
i_lp = SVC.find('playSceneOnceSafe(SceneRegistry.LONG_PRESS')
i_menu = SVC.find('showLongPressBubble();', i_lp)
check('long_press 在 showLongPressBubble() 之前播', 0 <= i_lp < i_menu)
check('整点报时播 hour 场景', 'playSceneOnceSafe(SceneRegistry.HOUR, 2000);' in SVC)
i_hour = SVC.find('playSceneOnceSafe(SceneRegistry.HOUR')
i_chime = SVC.find('chimeBusy = true;')
check('hour 在 doHourChime 开头就播（不是等 API 回来才播）', 0 <= i_hour and i_chime > 0 and i_hour > i_chime)

print()
print('== 四、贴边（dock）：只在"真的收进去"那一下播 ==')
dock = method_body(SVC, 'private void dockCollapse(')
check('dockCollapse 里播 dock 场景', 'playSceneOnceSafe(SceneRegistry.DOCK, 1200)' in dock)
check('★ 放在 stopWander() **之后**（它会 setScene(IDLE)，先播就被盖掉）',
      dock.find('stopWander();') < dock.find('playSceneOnceSafe(SceneRegistry.DOCK')
      if 'stopWander();' in dock else False)
check('★ 只在 NORMAL→COLLAPSED 的转变时播（贴边态反复调不重播）',
      'final boolean wasCollapsed = (dockState == PetDockState.COLLAPSED);' in dock
      and 'if (!wasCollapsed) playSceneOnceSafe(SceneRegistry.DOCK' in dock)

# ═══════════════ 五、★ 漫游：走多久 = 漫游动作时长（用户报障的根因）═══════════════
print()
print('== 五、★ 漫游动作不许被掐断（"播放不完就切换了"）==')
tick = method_body(SVC, 'private final Runnable wanderTick')
step = method_body(SVC, 'private void stepWander(')
# ★ 轮次㊸：整段"开走"逻辑从 wanderTick 抽进了 roamOnce（手动漫游与自动漫游共用一份）
roam = method_body(SVC, 'private boolean roamOnce(boolean manual)')
check('★ 开走后量"**正在播的那个**漫游动作多长"（轮次㊸：不再 peek 一个、播另一个）',
      'petImage.currentClipDurationMs(3000)' in roam)
check('★ 走多久 = 动作时长（至少 1.2 秒；轮次㊻ 起再减去前后摇）',
      'long total = Math.max(1200, roamMs);' in roam
      and 'walkEndAtMs = clipStart + total - tail;' in roam)
check('★ 到位后**等动作播完（含后摇）**再回待机（rest = walkSceneEndAtMs - nowMs）',
      'long rest = walkSceneEndAtMs - nowMs;' in step
      and 'wanderHandler.postDelayed(walkFinish, rest);' in step)
check('walkFinish 里才回待机 + 存位置 + 按需自动贴边',
      'private final Runnable walkFinish' in SVC
      and 'petImage.setScene(SceneRegistry.IDLE);' in method_body(SVC, 'private final Runnable walkFinish')
      and 'Prefs.savePos(FloatingService.this, params.x, params.y);' in method_body(SVC, 'private final Runnable walkFinish'))
check('★ 转向也用**动作自己的时长**（不再写死 1600ms）',
      'peekSceneDurationMs(SceneRegistry.TURN, 1600)' in tick
      and 'playSceneOnce(SceneRegistry.TURN, turnMs)' in tick
      and 'setSceneTemp(SceneRegistry.TURN' not in SVC)
check('★ 每步间隔 80ms（原来是 40ms：25Hz 发窗口更新 = IPC，会把 UI 线程压卡）',
      'WANDER_STEP_MS = 80' in SVC and 'wanderHandler.postDelayed(wanderStep, WANDER_STEP_MS);' in step)
check('★ 每步位移按**时间**推进（轮次㊸：旧算法"剩余距离 ÷ 剩余步数 + ceil"会提前到位）',
      'Math.round((wanderTargetX - walkStartX)' in step
      and 'Math.ceil(Math.abs(d) / (double) stepsLeft)' not in step)
check('★ 速度档位改成"改距离"（时长由动作决定，所以"快"必须体现在距离上）',
      'Prefs.wanderSpeed(this)' in roam and 'int distBase =' in roam)
check('旧的"每步乘速度系数"实现已删（那正是掐断动作的那条路）', 'float mul =' not in step)
check('每步不再 new 一个 Runnable（高频路径少一点 GC 抖动）',
      'wanderHandler.postDelayed(new Runnable() {' not in step)

# ═══════════════ 六、产物：新代码真的进了 dev 包 ═══════════════
print()
print('== 六、产物：新代码真的进了 dev 包 ==')
check('dev APK 存在', os.path.isfile(DEV_APK))
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('partScene', 'playSceneOnceSafe', 'peekSceneDurationMs',
              # ★ 轮次㊻：这句日志换了措辞（"到位，等动作播完" → "到位，留后摇 … 等动作播完"）
              '到位，留后摇 ', '动作时长=', 'WANDER_STEP_MS',
              # ★ 轮次㊻：前后摇的两个偏好键也要真的进包
              'wander_lead_ms', 'wander_tail_ms'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

# ═══════════════ 七、设备脚本 ═══════════════
print()
print('== 七、设备脚本（可复跑，且只认模拟器）==')
ui = os.path.join(HERE, 'verify_scene_exits_ui.py')
check('verify_scene_exits_ui.py 存在', os.path.isfile(ui))
if os.path.isfile(ui):
    s = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          'DEV = V.DEV' in s and 'import verify_char_ui as V' in s)
    check('★ 验三个部位 + 贴边 + 漫游时长（都靠 PetRotate/DSPWander 日志）',
          'tap_head' in s and 'tap_tail' in s and 'tap_body' in s
          and 'dock' in s and 'DSPWander' in s)
    check('★ 也验"部位自定义台词"（三级回退：部位 → 点一下 → 内置）',
          '本地台词 scene=tap_head line=' in s and 'local_line_tap_head' in s
          and '本地台词 scene=click line=' in s)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_scene_exits_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)
# ★ 轮次㊽：专门测"贴边动作在**拖动到边缘**那条路上会播"的一份（用户报的正是这条路）
dk = os.path.join(HERE, 'verify_dock_action_ui.py')
check('verify_dock_action_ui.py 存在（贴边动作，轮次㊽）', os.path.isfile(dk))
if os.path.isfile(dk):
    ds = read(dk)
    check('★ 它钉死模拟器 + 用**绑了 dock 场景**的测试角色（没绑会让判据假红）',
          'DEV = V.DEV' in ds and 'write_test_character_files' in ds
          and 'install_test_character' in ds)
    check('★★ 它防"假绿"：只打日志、没真的切场景时必须判否',
          '防假绿' in ds and 'scene_dock_played' in ds)
    rc, out = run([sys.executable, dk, '--selftest'])
    check('verify_dock_action_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('== 八、★★ 漫游：三击菜单入口 + 方向 + 时长 + 落点（轮次㊸）==')
# ★ 用户原话：「宠物漫游动作方向不对，漫游完位置不对应，位置会闪现，
#   先增加主动漫游在三击菜单里。根据漫游方向调整动画方向，上下方向无需更改。」
# ① 入口：三击菜单一级列表顶部那个「主动漫游」
check('★★ 菜单里有「主动漫游」入口（常量 + 加进一级列表）',
      'ROAM_MENU_LABEL' in SVC and 'flatList.addView(roam, rlp)' in SVC)
check('★ 点它走的是与自动漫游**同一条**路径（roamOnce，不是各写一份）',
      'roamOnce(true)' in SVC and 'roamOnce(false)' in SVC)
roam_fn = method_body(SVC, 'private boolean roamOnce(boolean manual)')
check('★★ 手动漫游不受漫游总开关限制（roamOnce 里不查 wanderEnabled）',
      bool(roam_fn) and 'wanderEnabled' not in roam_fn)
# ② 方向：按**素材原生朝向**决定要不要水平镜像
play_scene = method_body(VIEW, 'private void playAnimPackState()')
check('★★ 镜像判据按素材原生朝向（内置包那个动作叫「原地左转奔跑」⇒ 原生朝左）',
      # ★ 必须**限定在 playAnimPackState 里**：轮次㊻ 起 setFaceDir 里也有同一段判据
      #   （换向时重算），全文搜的话把这里改回 `faceDir < 0` 照样绿（反向套件 v 项当场抓到）
      'BASE_FACE_DIR = -1f' in VIEW and '(faceDir != BASE_FACE_DIR)' in play_scene)
check('★ 换向时要当场重算镜像（同一场景连走两段、方向相反也得跟上）',
      '(faceDir != BASE_FACE_DIR)' in method_body(VIEW, 'public void setFaceDir(float d)'))
check('★★ 镜像绕**视图中心**翻，不绕素材 intrinsic 中心（否则往右跑会整体平移）',
      'animMatrix.setScale(-1f, 1f, getWidth() / 2f, 0f)' in VIEW
      and 'animMatrix.setScale(-1f, 1f, animSrc.centerX(), 0f)' not in VIEW)
check('★★ 镜像时内容左右边距要互换（贴边/钳制/气泡对位都吃它）',
      'if (in != null && animPackMirror && in.length >= 4)' in VIEW
      and 'return new float[]{ in[1], in[0], in[2], in[3] };' in VIEW)
check('★ 只有 turn / roam 参与镜像（其余场景一律不镜像）',
      'SceneRegistry.TURN.equals(scene) || SceneRegistry.ROAM.equals(scene)' in VIEW)
# ③ 时长：走多久 = **正在播的那个** clip 的时长（不许再 peek 一个、播另一个）
check('★★ 漫游时长取"正在播的那个 clip"（currentClipDurationMs）',
      'currentClipDurationMs' in VIEW and 'currentClipDurationMs(3000)' in SVC)
check('★★ 不再用 peekSceneDurationMs 定漫游时长（它自己 pick 一次、setScene 又 pick 一次）',
      'peekSceneDurationMs(SceneRegistry.ROAM' not in SVC)
# ④ 落点：按时间线性插值（旧算法"剩余距离 ÷ 剩余步数 + ceil"会提前到位、然后原地走完）
roam_step = method_body(SVC, 'private void stepWander()')
check('★★ 位移按时间线性插值（walkStartAtMs / walkStartX）',
      'walkStartAtMs' in roam_step and 'walkStartX' in roam_step
      and 'Math.round((wanderTargetX - walkStartX)' in roam_step)
check('★ 到位时精确落在目标上（旧算法允许差 6px）',
      'params.x = wanderTargetX;' in roam_step and 'Math.abs(d) <= 6' not in roam_step)
# ⑤ 三击判定不再依赖对话开关（关掉对话后菜单也要能打开）
check('★★ 三击待定与对话开关解耦（关掉对话后三击菜单仍能打开）',
      # ★ 必须查 `else if (doubleTap) {`：只查 `if (doubleTap) {` 的话，
      #   贴边竖条那条双击分支里也有同样的文本 ⇒ 破坏成 `doubleTap && chatEnabled` 照样绿
      #   （反向套件 y 项当场抓到）
      'else if (doubleTap) {' in SVC and 'final boolean canChat = Prefs.chatEnabled' in SVC)

# ═══════════════ 九、★★ 漫游前摇 / 后摇（轮次㊻）═══════════════
print()
print('== 九、★★ 漫游前摇 / 后摇（动画先播再起步、人停住后动画再收尾）==')
# ★ 用户原话：「动画播放逻辑不对 在漫游动作里面加一个漫游开始的延迟还有结束的延迟
#   用来控制动画前后摇的逻辑」。旧逻辑是"开播即走、走到即停"，与画面里的迈步完全错位。
PR = java('Prefs.java')
check('★★ 前摇 / 后摇两个偏好项都在（默认 1 秒、0~4 秒夹紧）',
      'KEY_WANDER_LEAD = "wander_lead_ms"' in PR and 'KEY_WANDER_TAIL = "wander_tail_ms"' in PR
      and PR.count('Math.min(4000,') >= 2
      and 'KEY_WANDER_LEAD, 1000' in PR and 'KEY_WANDER_TAIL, 1000' in PR)
check('★★ roamOnce 里算出三个时刻：开始移动 / 停止移动 / 动画播完',
      'walkStartAtMs = clipStart + lead;' in roam
      and 'walkEndAtMs = clipStart + total - tail;' in roam
      and 'walkSceneEndAtMs = clipStart + total;' in roam)
check('★★ 前后摇之和不得超过"动画时长 − 600ms"（超了按比例压，保证真的在走）',
      'long budget = Math.max(0, total - 600);' in roam
      and 'walkSceneEndAtMs' in SVC)
check('★★ stepWander 有**前摇分支**：到 walkStartAtMs 之前一步都不动',
      # ★★ 轮次70：这一支里**不许**再出现把 x 写回起点那一行 —— 那是整条漫游路径上
      #   唯一的**瞬时赋值**（开走前有谁动过 x 就会被瞬间拉回去 = "凭空一挪"）。
      'if (nowMs < walkStartAtMs) {' in roam_step
      and 'params.x = walkStartX' not in roam_step)
check('★★ 后摇等的是 walkSceneEndAtMs（动画播完），不是 walkEndAtMs（人停住那一刻）',
      'long rest = walkSceneEndAtMs - nowMs;' in roam_step)
check('★ 日志报出前摇/后摇（设备侧只认它）', '"ms 前摇="' in SVC and '"ms 后摇="' in SVC)
MA2 = java('MainActivity.java')
check('★ 设置页有两个可调项（漫游前摇 / 漫游后摇，都走 pickDelayMs）',
      '漫游前摇' in MA2 and '漫游后摇' in MA2
      and 'Prefs.setWanderLeadMs(MainActivity.this, Math.max(0, ms));' in MA2
      and 'Prefs.setWanderTailMs(MainActivity.this, Math.max(0, ms));' in MA2)

# ─────────── 十、★★ 按动作的前后摇 + 档位加密（轮次㊼）───────────
print()
print('== 十、★★ 按动作的前后摇（适配不同角色不同动画）+ 档位加密 ==')
# ★ 用户原话：「漫游前后摇时间给的选项多一点 并且可以单独设置每个动作的前后摇
#   用来适配不同角色不同动画的动作 版本号改为7.5」
RT = java('RoamTiming.java')
check('★★ 覆盖层是**纯类** RoamTiming（解析零 Android，可在 fpstest 里断言）',
      'public final class RoamTiming' in RT
      and 'public static LinkedHashMap<String, Timing> parse(' in RT)
check('★★ 按角色隔离存盘：filesDir/roam/<净化后的角色 id>.json',
      'DIR = "roam"' in RT and 'ActionMenuConfig.sanitize(charId)' in RT
      and 'fileIn(File filesDir' in RT)
check('★★ roamOnce 的解析顺序：本动作有覆盖 ⇒ 用它，否则全局值',
      'RoamTiming.pick(' in roam
      and 'if (ov != null) { lead = ov.lead; tail = ov.tail; byClip = true; }' in roam
      and 'petImage.currentClipId()' in roam)
check('★★ 取"正在播的动作名"的入口在（PetView.currentClipId）',
      'public String currentClipId()' in VIEW and 'return animPackCurrent;' in VIEW)
check('★ 日志能分辨这一趟用的是动作覆盖还是全局值（摇源=）', '摇源=' in SVC)
check('★★ 档位加密到 14 档（0.2 秒一档到 1 秒，之后 0.2/0.5 秒一档到 4 秒）',
      '-1, 0, 200, 400, 600, 800, 1000, 1200, 1500, 1800, 2000, 2500, 3000, 3500, 4000' in MA2
      and '"4 秒"' in MA2 and '"0.2 秒"' in MA2)
check('★ 设置页有「每个动作的前后摇」入口 + 全部恢复',
      '每个动作的前后摇' in MA2 and 'showRoamTimingPicker' in MA2
      and '全部恢复跟随全局' in MA2)

# ─────────── 十一、★★ 贴边动作要挂在"用户真走的那条路"上（轮次㊽）───────────
print()
print('== 十一、★★ 贴边动作（dock）：拖到边缘 / 漫游到边缘都要播 ==')
# ★ 用户原话：「**贴边动作没有触发**」。
#   实测真凶：dock 场景原来只挂在 `dockCollapse()`（贴成竖条，要长按菜单手动触发），
#   而用户平常的"贴边"是**拖到边缘松手** / **漫游撞到边缘自动贴边** —— 它们走
#   `snapToNearestEdge()`，那条路只滑窗口、从不播动作；★★ 而且那条路里
#   "已经在边上就直接 return" 的早退在播动作**之前** —— 拖动时窗口早就被 clamp 到边上 ⇒
#   连"挂上去"都没机会。
snap = method_body(SVC, 'private void snapToNearestEdge()')
check('★★ 贴边动作挂在 snapToNearestEdge（拖到边缘松手 / 漫游到边缘那条路）',
      'playDockFeedback(startX, targetX, minX);' in snap)
# ★★ 判据要**数个数**：只查"存在"的话，把早退分支里那次删掉、
#    留主路径那次照样绿（反向套件 z8 当场抓到）
check('★★ 贴边反馈要出现在**两处**（"已到边" + "滑到边"）',
      snap.count('playDockFeedback(startX, targetX, minX);') >= 2,
      '实得 %d 处' % snap.count('playDockFeedback(startX, targetX, minX);'))
check('★★ "已经在边上"那条**早退分支里也要播**（拖动时窗口已被 clamp 到边上）',
      snap.find('if (targetX == startX) {') >= 0
      and snap.find('playDockFeedback(startX, targetX, minX);')
          > snap.find('if (targetX == startX) {'))
check('★ 贴边反馈抽成 playDockFeedback（播 dock 场景 + 说台词 + 打日志）',
      'private void playDockFeedback(' in SVC
      and 'playSceneOnceSafe(SceneRegistry.DOCK, 1200)' in method_body(SVC, 'private void playDockFeedback(')
      and 'saySceneLine(SceneRegistry.DOCK)' in method_body(SVC, 'private void playDockFeedback(')
      and '"DSPDock"' in SVC)

print('== 十二、★★ 漫游：方向确定（贴哪边往另一边）+ 漫游动作优先（轮次60）==')
INSET = java('ContentInset.java')      # ★ 用 java()（已剥注释）—— 注释里提到某个模式不该让断言误判
# ★ 用户原话：「漫游动作优先触发因为主动漫游的时候触发了其他动作，导致漫游动作无法触发，
#   每次触发方向都是随机的，有概率触发不了，统一改成贴在哪边往另一边漫游」。
#   ① 方向随机 ⇒ 贴边时一半概率朝墙走 ⇒ 被 clamp 夹回原地 ⇒ `|tx-cur| < 40` 直接 return false
#      = 用户看到的"点了漫游没反应 / 有概率触发不了"；
#   ② 漫游期间**别的场景**（整点/碎碎念/工作状态/轮播/余额…）走 setSceneTemp ⇒ 无条件替换当前场景、
#      而且到点回的是 **IDLE** ⇒ 漫游动作当场死掉（人还在滑行）。
roam = method_body(SVC, 'private boolean roamOnce(boolean manual)')
check('★★ 方向**不再掷骰子**：改用纯类判据 ContentInset.roamDirAway(minX, maxX, cur)',
      'int dir = ContentInset.roamDirAway(minX, maxX, cur);' in roam
      and 'Math.random() < 0.5 ? -1 : 1' not in roam)
check('★★ 规则本身在**纯类**里（零 Android ⇒ fpstest 能真跑）+ fpstest 编了它并有 [32] 节',
      'public static int roamDirAway(int minX, int maxX, int cur)' in INSET
      and 'ContentInset.java' in read(os.path.join(HERE, 'fpstest', 'run.bat'))
      and '[32] ContentInset.roamDirAway' in read(
          os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')))
check('★★ 贴哪边就往另一边走（贴左⇒+1 / 贴右⇒-1 / 中间⇒空间更大的一侧）',
      'if (left <= EDGE_PX) return 1;' in INSET
      and 'if (right <= EDGE_PX) return -1;' in INSET
      and 'return (right >= left) ? 1 : -1;' in INSET)
check('★ 设备侧证据：日志里带方向（`dir=右/左`），否则"方向对不对"没法在设备上验',
      'dir=" + (dir > 0 ? "右" : "左")' in SVC)
# —— 漫游优先 ——
check('★★ 漫游用 **lockScene**（不是 setScene）：锁住期间别的场景抢不走它',
      'petImage.lockScene(SceneRegistry.ROAM);' in roam
      and 'petImage.setScene(SceneRegistry.ROAM);' not in roam)
INSET = java('ContentInset.java')      # ★ 用 java()（已剥注释）—— 注释里提到某个模式不该让断言误判
check('★★ 场景锁在 PetView 里（通用：锁住某场景，其余请求一律忽略）',
      'public void lockScene(String scene)' in VIEW and 'public void unlockScene()' in VIEW
      and 'private boolean blockedByLock(String scene)' in VIEW)
check('★★ 锁对 **setScene 与 setSceneTemp 两条路都生效**'
      '（临时场景才是真凶：它无条件替换、到点还回 IDLE）',
      method_body(VIEW, 'public void setScene(String scene)').count('blockedByLock(scene)') == 1
      and method_body(VIEW, 'public void setSceneTemp(final String scene, long ms)')
              .count('blockedByLock(scene)') == 1)
check('★★ 上锁时顺手取消"临时场景到点回待机"那个待办（否则它会把锁住的场景顶掉）',
      'if (tmpStateReset != null) { removeCallbacks(tmpStateReset); tmpStateReset = null; }'
      in method_body(VIEW, 'public void lockScene(String scene)'))
check('★★ **每个"漫游结束"的出口都解锁**（不解锁的话连"回待机"都会被锁挡住）'
      '：走完 / 被用户碰 / stepWander 早退 / 异常退出',
      'unlockRoamScene();' in method_body(SVC, 'private void stopWander()')
      and method_body(SVC, 'private void unlockRoamScene()').count('petImage.unlockScene()') == 1
      and SVC.count('unlockRoamScene();') >= 4,
      '实得 %d 处' % SVC.count('unlockRoamScene();'))
check('★ 设备侧证据：被挡下的场景会留一行日志（悬浮窗不进 dump，只能靠日志验）',
      '漫游优先：挡下场景 ' in VIEW and '"DSPWander"' in VIEW)
check('★ "没余地"的文案改了（方向确定后，几乎只会在屏幕窄到没余地时命中）',
      '屏幕太窄，没有可走的余地' in SVC and '这一侧已经贴到边了' not in SVC)

print('== 十三、★★ 手点动作优先级最高（轮次61）==')
# ★ 用户原话：「点动作的时候，有时候会被其他动作打断，更改一下逻辑，让当前触发动作优先级最高」。
#   真凶与漫游同源：`playAnimByName` 播的是**一次性 drawable**，而任何 `setScene*`
#   都会把它顶掉 ⇒ 整点/碎碎念/工作状态/轮播/余额一触发，手点的动作当场没了。
check('★★ 手点动作走 exclusive 变体（播放期间上锁）',
      'String id = petImage.playAnimByName(name, true);' in SVC
      and 'public String playAnimByName(String id, boolean exclusive)' in VIEW)
check('★★ 锁是"谁都不许抢"（blockedByLock 先判 actionLocked，再判场景锁）',
      'if (actionLocked) {' in method_body(VIEW, 'private boolean blockedByLock(String scene)')
      and 'public void lockAction(long ms)' in VIEW
      and 'public void unlockAction()' in VIEW)
_pb = method_body(VIEW, 'public String playAnimByName(String id, boolean exclusive)')
check('★★ 上锁要在**开始播之前**（上锁会取消"临时场景到点回待机"的待办，'
      '放后面那一下就把刚起的动作顶掉了）',
      # ★ 必须在**方法体内**比位置：`setImageDrawable` 在别的方法里也出现，
      #   拿全文 find() 比会跟到前面那一处（第一版就是这么假红的）
      _pb.find('lockAction(Math.max(400, ms));') >= 0
      and _pb.find('lockAction(Math.max(400, ms));') < _pb.find('setImageDrawable(d);'))
check('★★ 播完解锁（完成回调里），不靠定时器兜底',
      'if (ex) unlockAction();' in VIEW)
check('★★ **用户碰它立刻解锁**（锁只挡自动场景，不挡用户；否则点一下没反应）',
      'petImage.unlockAction();' in method_body(SVC, 'public boolean onTouch(View v, MotionEvent event)'))
check('★ 自动播的动画**不上锁**（余额档位 / 事件动画不该反过来锁住用户的操作）',
      'playAnimByName(id)' in SVC)
check('★ 设备侧证据：手点动作会留一行日志（悬浮窗不进 dump，只能靠日志验）',
      '手点动作 name=' in SVC and '已上锁' in SVC
      and '手点动作优先：挡下场景 ' in VIEW)

# ─────────── 十四、★★ 漫游就在"当前位置"开始（没有任何前置位移）（轮次70）───────────
print()
print('== 十四、★★ 漫游从当前位置开始：x / y 都不许有前置位移（轮次70）==')
# ★ 用户原话（依据，逐字）：「漫游的时候，无论主动还是被动，都会强制位移到中间然后开始漫游，
#   更改一下漫游逻辑在任何位置都可以漫游」；追问下确认：「就上下跑到中间了，
#   漫游就在桌宠当前位置开始漫游就行了」。
#   实测真凶：wanderAvoidTopThird()（把 y 从顶部 1/3 滑到 800 = 竖直 1/3 处，
#   1080×2400 的机器上就是用户看到的"凭空移动到中间"）。
roam = method_body(SVC, 'private boolean roamOnce(boolean manual)')
roam_step = method_body(SVC, 'private void stepWander()')
clamp_body = method_body(SVC, 'private void clampToScreen()')
unlock_body = method_body(SVC, 'private void unlockRoamScene()')
check('★★ 「避开顶部 1/3」默认**关**（漫游不再先把自己挪到竖直 1/3 处）',
      'return sp.getBoolean(KEY_WANDER_AVOID_TOP, false);' in PR)
check('★★ 那条前置位移只剩"用户显式打开"这一个例外（调用点被 if (avoidTop) 守住）',
      'if (avoidTop) wanderAvoidTopThird();' in roam
      and roam.count('wanderAvoidTopThird();') == 1)
check('★★ roamOnce 函数体里**不许写 x/y**（"前置位移 = 0"的结构性判据）',
      'params.x =' not in roam and 'params.y =' not in roam and 'ValueAnimator' not in roam)
check('★★ 触发那一刻的可达范围被**冻结**给整段漫游用（换 clip 不会再把窗口夹一遍）',
      'roamMinX = minX;' in roam and 'roamMaxX = maxX;' in roam and 'roamFrozenX = true;' in roam
      and 'if (roamFrozenX && roamMaxX >= roamMinX) { minX = roamMinX; maxX = roamMaxX; }' in clamp_body
      and 'roamFrozenX = false;' in unlock_body)
check('★★ 前摇里**没有**瞬时赋值（旧写法会把桌宠瞬间拉回起点）',
      'params.x = walkStartX' not in roam_step
      and 'if (nowMs < walkStartAtMs) {' in roam_step)
check('★★ 起点就是触发时读到的 x（walkStartX = cur，插值仍以它为基准）',
      'walkStartX = cur;' in roam
      and 'Math.round((wanderTargetX - walkStartX)' in roam_step)
check('★ 设备侧四行读数：触发 / 开走前 / 目标 / 到位（悬浮窗不进 dump，只能靠日志验）',
      '"触发 manual="' in SVC and '"开走前 x="' in SVC and '"到位 x="' in SVC
      and '"walk "' in SVC and '起点x=' in SVC and '目标x=' in SVC)
check('★ 竖条态（COLLAPSED）也能起漫游：先展开、展开完了再走（不从别处挪过来）',
      'roamAfterExpand = true;' in roam and 'dockExpand();' in roam
      and 'if (roamAfterExpand) {' in method_body(SVC, 'private void dockExpand()')
      and 'roamAfterExpand = false;' in method_body(SVC, 'private void dockCollapse(boolean fromIdle)'))
check('★ 设置页文案写明"打开后会先动一下"（默认行为的变更不许悄悄做）',
      '会先动一下' in MA2)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（场景出口全覆盖 + 漫游动作不被掐断）')
