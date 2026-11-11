# -*- coding: utf-8 -*-
"""设备验证：**场景动作真的会被触发**（轮次㊱：用户报「有些动作选项无法触发」+「漫游动作播不完就切」）。

为什么需要它：
    源码级断言只能证明"有人调了 playSceneOnce"，证明不了**点下去真的出那个动作**。
    这一轮的两个报障都在"触发链"上，所以必须在设备上走一遍真手势 + 真日志。

★ 关键设计：**造一个只绑"内置动作"的极小角色包**（不推 312 MB 的正式包）
    APK 里保留着 1 个内置动作「待机呼吸休闲」（轮次⑱ 起 assets 只留它一个兜底）。
    本脚本造一个几十字节的 pack.json，把 tap_head / tap_body / tap_tail / dock / roam / turn / click
    全绑到它 ⇒ 导入只要 1 秒，而**动作时长是真的**（真 animated WebP，约 2.9 秒），
    所以第四节的"漫游时长"量到的是真值，不是 fallback。

覆盖：
  一、准备：导入测试角色包 + 冷启动 + 找到桌宠窗口；
  二、★ **部位点击**：头 / 身 / 尾各戳一下 ⇒ `PetRotate: play scene=tap_head|tap_body|tap_tail`；
  三、★ **贴边**：`DOCK_NOW`（调试入口）⇒ `play scene=dock`，且窗口收成竖条；
  四、★ **漫游时长**：开漫游 → 等一次 walk ⇒ 日志给出"动作时长=N ms"，
      而 `scene=roam` 到下一次 `scene=idle` 的间隔必须 ≥ N（旧实现 0.3~1.1 秒就切了）；
  五、收尾：把漫游开关与角色恢复原状。

诚实边界：
  * 测试包绑的是**内置那一个动作**（不是正式角色包里的 106 个）—— 验的是**触发链**，
    "正式包里这些场景确实有各自的动作"由离线套件 `verify_scene_exits.py` 的出口表 + 归类表钉住；
  * 漫游是随机的（70% 走 / 30% 转向）⇒ 本节最多等 60 秒抓一次 walk，抓不到按"跳过"计（不算通过）；
  * 模拟器验的是 AOSP 行为，真机手感仍是长期欠账。

★ 两个必须记住的坑（都踩过）：
  1. **App 在前台时桌宠是"临时收起"的**（`setPetTempHidden`，只改可见性、窗口还在）
     ⇒ 脚本 `am start` 之后直接按窗口坐标戳，点在一个不可见的窗口上，**什么都不会发生**
     （第一版就是这么假红的）。所以戳之前一定要 `input keyevent 3` 回桌面；
  2. **窗口矩形要按 `appop=SYSTEM_ALERT_WINDOW` 认，并排掉全屏**：刚启动那一瞬 overlay
     还没量出尺寸，frame 会是整屏；按整屏比例戳，三下全落在桌宠外面。

用法：
    python build/verify_scene_exits_ui.py --install
    python build/verify_scene_exits_ui.py
    python build/verify_scene_exits_ui.py --selftest

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import os
import re
import shutil
import sys
import time
import zipfile

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402  本地 pref 副本的擦除（防 Key 进源码包）
import verify_char_ui as V           # noqa: E402  设备驱动与判读工具
import verify_char_switch_ui as S    # noqa: E402  角色中心导航 / 可靠改 pref / 设备清理
import verify_edge_snap_ui as E      # noqa: E402  窗口几何（mAttrs/frame）与"拖到边上"

DEV = V.DEV
PKG = V.PKG
FILES = '/data/data/%s/files' % PKG
WORK = r'D:\petport_lab\petbuild\build\_uitest'
CHAR_ID = 'sceneexit'
CHAR_NAME = '场景出口测试'
# APK 里唯一的内置动作（轮次⑱ 起 assets 只留它一个兜底）—— 真 animated WebP，时长是真的
BUILTIN_CLIP = '待机呼吸休闲'
BUILTIN_WEBP = r'D:\petport_lab\petbuild\assets\webp\待机呼吸休闲.webp'
SCENES = ('idle', 'click', 'tap_head', 'tap_body', 'tap_tail', 'dock', 'roam', 'turn')

fails = []
skips = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def pet_frame_in(dump_text, pkg=PKG, screen=(1080, 2400)):
    """从 `dumpsys window windows` 里取**桌宠悬浮窗**的屏幕矩形 (x0,y0,x1,y1)。

    ★ 三条判据（都是实测换来的）：
      ① 头部带本包、且**不是** `/MainActivity`（Activity 的窗口也在同一份 dump 里）；
      ② 窗口块里有 `appop=SYSTEM_ALERT_WINDOW` —— 桌宠悬浮窗有、Activity 没有（`appop=NONE`）；
      ③ **排掉全屏**：刚启动那一瞬 overlay 还没量出尺寸，frame 会是整屏（实测踩到，
         于是按比例戳的三下全落在桌宠外面 ⇒ 三个部位场景"一个都没播"的假红）。
      气泡/监测条那种矮窗口也排掉（高度 < 200px），剩下最高的那个就是桌宠。
    """
    cands = []
    for b in re.split(r'\n(?=  Window #)', dump_text or ''):
        head = b.split('\n', 1)[0]
        if pkg not in head or 'MainActivity' in head:
            continue
        if 'appop=SYSTEM_ALERT_WINDOW' not in b:
            continue
        m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', b)
        if not m:
            continue
        r = tuple(int(g) for g in m.groups())
        w, h = r[2] - r[0], r[3] - r[1]
        if w <= 0 or h < 200:
            continue
        if w >= screen[0] and h >= screen[1]:
            continue
        cands.append((h, r))
    return max(cands)[1] if cands else None


def scene_timeline(log_text):
    """日志里每次"切到某场景"的时刻：[(毫秒(当日), 场景, 动作 id)]。

    ★ 认的是 `PetRotate: play scene=<scene> id=<id>` —— 那是素材层唯一的切场景日志
      （设备脚本判断"动作到底有没有播"一直用它）。
    """
    out = []
    pat = re.compile(r'(\d\d):(\d\d):(\d\d)\.(\d\d\d).*PetRotate:\s*play scene=(\S+) id=(\S+)')
    for line in (log_text or '').splitlines():
        m = pat.search(line)
        if not m:
            continue
        ms = ((int(m.group(1)) * 60 + int(m.group(2))) * 60 + int(m.group(3))) * 1000 + int(m.group(4))
        out.append((ms, m.group(5), m.group(6)))
    return out


def scenes_played(log_text, scene):
    return [t for t in scene_timeline(log_text) if t[1] == scene]


def roam_durations(log_text):
    """DSPWander 里每段 walk 的 (动作时长ms, 计划走ms) —— 新日志格式：`动作时长=Nms 走=Mms`。"""
    out = []
    for line in (log_text or '').splitlines():
        m = re.search(r'DSPWander:\s*walk .*动作时长=(\d+)ms 走=(\d+)ms', line)
        if m:
            out.append((int(m.group(1)), int(m.group(2))))
    return out


def roam_hold_ms(log_text):
    """`scene=roam` → 紧接着那次 `scene=idle` 的实际间隔（毫秒）；量不到返回 None。

    ★ 这就是用户报障的那条：旧实现走路只要 0.3~1.1 秒就切 idle，
      而漫游动作有 2~4 秒 ⇒ 动作**播不完就被切**。
    """
    tl = scene_timeline(log_text)
    for i, (ms, sc, _cid) in enumerate(tl):
        if sc != 'roam':
            continue
        for ms2, sc2, _cid2 in tl[i + 1:]:
            if sc2 == 'idle':
                return ms2 - ms
            if sc2 == 'roam':
                break
    return None


def selftest():
    """不连设备：验本脚本自己的判读逻辑（正例 + 反例）。"""
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    dump = ('  Window #6 Window{7f0b71a u0 com.deepseek.pet}:\n'
            '    mOwnerUid=10241 package=com.deepseek.pet appop=SYSTEM_ALERT_WINDOW\n'
            '    mAttrs={(-210,736)(wrapxwrap) ty=APPLICATION_OVERLAY\n'
            '    Frames: parent=[0,0][1080,2400] display=[0,0][1080,2400] '
            'frame=[-210,736][420,1090] last=[-210,736][420,1090]\n'
            '  Window #7 Window{aaa u0 com.deepseek.pet}:\n'
            '    mOwnerUid=10241 package=com.deepseek.pet appop=SYSTEM_ALERT_WINDOW\n'
            '    Frames: frame=[300,700][900,900] last=[300,700][900,900]\n'
            '  Window #8 Window{bbb u0 com.deepseek.pet}:\n'
            '    mOwnerUid=10241 package=com.deepseek.pet appop=SYSTEM_ALERT_WINDOW\n'
            '    Frames: frame=[0,0][1080,2400] last=[0,0][1080,2400]\n'
            '  Window #10 Window{be9814e u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n'
            '    mOwnerUid=10241 package=com.deepseek.pet appop=NONE\n'
            '    Frames: frame=[0,0][1080,2400] last=[0,0][1080,2400]\n')
    t('桌宠窗口矩形（按 appop 认，排掉全屏与矮气泡）', pet_frame_in(dump) == (-210, 736, 420, 1090))
    t('反例：只有 MainActivity ⇒ None',
      pet_frame_in('  Window #10 Window{x u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n'
                   '    appop=NONE\n    Frames: frame=[0,0][1080,2400]\n') is None)
    t('★ 反例：只有"还没量出尺寸"的全屏 overlay ⇒ None（否则会按比例戳到桌宠外面）',
      pet_frame_in('  Window #6 Window{x u0 com.deepseek.pet}:\n'
                   '    appop=SYSTEM_ALERT_WINDOW\n    Frames: frame=[0,0][1080,2400]\n') is None)
    t('反例：别的包不算', pet_frame_in('  Window #1 Window{x u0 com.other}:\n'
                                  '    appop=SYSTEM_ALERT_WINDOW\n'
                                  '    Frames: frame=[0,0][630,354]\n') is None)

    lg = ('10-11 13:00:00.100  1  1 I PetRotate: play scene=tap_head id=待机呼吸休闲\n'
          '10-11 13:00:03.600  1  1 I PetRotate: play scene=tap_body id=待机呼吸休闲\n'
          '10-11 13:00:07.000  1  1 I PetRotate: play scene=roam id=待机呼吸休闲\n'
          '10-11 13:00:09.900  1  1 I PetRotate: play scene=idle id=待机呼吸休闲\n'
          '10-11 13:00:12.000  1  1 I DSPWander: walk 100→300（范围 -210~661）动作时长=2900ms 走=2900ms dockOnArrive=false\n')
    t('时间线解析出 4 条切场景', len(scene_timeline(lg)) == 4)
    t('按场景取得到（tap_head 一次）', len(scenes_played(lg, 'tap_head')) == 1)
    t('反例：没播过的场景是空表', scenes_played(lg, 'dock') == [])
    t('walk 日志解析出（动作时长 2900 / 走 2900）', roam_durations(lg) == [(2900, 2900)])
    t('★ roam→idle 的实际间隔量得出来（2900ms）', roam_hold_ms(lg) == 2900)
    t('★ 反例：旧实现的"0.6 秒就切"必须量成 600（会被判成掐断）',
      roam_hold_ms('10-11 13:00:07.000 I PetRotate: play scene=roam id=x\n'
                   '10-11 13:00:07.600 I PetRotate: play scene=idle id=x\n') == 600)
    t('反例：没有 roam ⇒ None',
      roam_hold_ms('10-11 13:00:07.000 I PetRotate: play scene=idle id=x\n') is None)

    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def make_test_pack():
    """造一个**只装那一个内置动作**的极小角色包（约 2.7 MB，不推 312 MB 的正式包）。

    ★ 必须带**真动作文件**：导入器会拒"包里没有可用的动作文件"（`clips=0`）。
      带的是 APK 里那个内置的 `待机呼吸休闲.webp`（真 animated WebP，约 2.9 秒）
      ⇒ 第四节量到的"动作时长"是真值，不是 fallback。
    """
    src = os.path.join(WORK, 'scenepack')
    if os.path.isdir(src):
        shutil.rmtree(src)
    os.makedirs(os.path.join(src, 'anim', 'clips'))
    shutil.copyfile(BUILTIN_WEBP, os.path.join(src, 'anim', 'clips', BUILTIN_CLIP + '.webp'))
    with open(os.path.join(src, 'character.json'), 'w', encoding='utf-8') as f:
        f.write('{"id": "%s", "name": "%s", "author": "uitest", "version": "1.0"}\n'
                % (CHAR_ID, CHAR_NAME))
    scenes = ', '.join('"%s": ["%s"]' % (s, BUILTIN_CLIP) for s in SCENES)
    with open(os.path.join(src, 'anim', 'pack.json'), 'w', encoding='utf-8') as f:
        f.write('{"version": 2, "scenes": {%s}, "events": {}}\n' % scenes)
    zpath = os.path.join(WORK, 'scenepack.zip')
    if os.path.exists(zpath):
        os.remove(zpath)
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _dn, fn in os.walk(src):
            for f in fn:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, src).replace('\\', '/'))
    return zpath


def write_test_character_files():
    """在本地造出测试角色的三份文件（character.json / anim/pack.json / 一个真动作）。"""
    src = os.path.join(WORK, 'scenepack')
    if os.path.isdir(src):
        shutil.rmtree(src)
    os.makedirs(os.path.join(src, 'anim', 'clips'))
    shutil.copyfile(BUILTIN_WEBP, os.path.join(src, 'anim', 'clips', BUILTIN_CLIP + '.webp'))
    with open(os.path.join(src, 'character.json'), 'w', encoding='utf-8') as f:
        f.write('{"id": "%s", "name": "%s", "author": "uitest", "version": "1.0"}\n'
                % (CHAR_ID, CHAR_NAME))
    scenes = ', '.join('"%s": ["%s"]' % (s, BUILTIN_CLIP) for s in SCENES)
    with open(os.path.join(src, 'anim', 'pack.json'), 'w', encoding='utf-8') as f:
        f.write('{"version": 2, "scenes": {%s}, "events": {}}\n' % scenes)
    return src


def push_owned(local, remote, ref_dir=FILES):
    """推进 App 私有目录并还原 owner / mode / SELinux 上下文（照 verify_appearance_ui 的手法）。

    ★ 直接 `adb push` 进去的文件 owner 是 root，App（普通 uid）**读不到**，
      而读不到时素材层会**静默**回退内置 ⇒ 表现成"场景绑了却不出动作"，极难查。
    """
    V.sh('push', local, remote)
    owner = V.out('shell', 'stat', '-c', '%U:%G', ref_dir).strip()
    if owner:
        V.sh('shell', 'chown', owner, remote)
    V.sh('shell', 'chmod', '600', remote)
    V.sh('shell', 'restorecon', remote)
    return V.out('shell', 'stat', '-c', '%s', remote).strip() == str(os.path.getsize(local))


def install_test_character(src):
    """**直接把角色目录落盘**（不走导入器）。

    ★ 为什么不用 `EXTRA_IMPORT_PACK`：本节要验的是"场景触发链"，不是导入器；
      而导入那条路在设备脚本里踩过坑（`am start --es dsp_import_pack` 偶发读不到刚推的包，
      App 报"包里没有可用的动作文件"，其实是文件没到位）—— 那是**脚本环境**问题，
      不该混进这一轮的判据里。导入器本身由 `verify_char_switch_ui.py` / `verify_char_ui.py` 覆盖。
    """
    remote = '%s/characters/%s' % (FILES, CHAR_ID)
    V.sh('shell', 'rm', '-rf', remote)
    V.sh('shell', 'mkdir', '-p', remote + '/anim/clips')
    owner = V.out('shell', 'stat', '-c', '%U:%G', FILES).strip()
    if owner:
        V.sh('shell', 'chown', owner, remote)
        V.sh('shell', 'chown', owner, remote + '/anim')
        V.sh('shell', 'chown', owner, remote + '/anim/clips')
    ok = True
    for rel in ('character.json', 'anim/pack.json', 'anim/clips/%s.webp' % BUILTIN_CLIP):
        ok = push_owned(os.path.join(src, rel.replace('/', os.sep)), remote + '/' + rel) and ok
    return ok


def pet_frame():
    return pet_frame_in(V.out('shell', 'dumpsys', 'window', 'windows'))


def wait_pet_frame(secs=20):
    t0 = time.time()
    while time.time() - t0 < secs:
        f = pet_frame()
        if f:
            return f
        time.sleep(1.0)
    return None


def tap_part(frac):
    """按桌宠窗口高度的比例戳一下（0.2=头 / 0.5=身 / 0.8=尾）。

    ★ x 取**可见部分**的中心：贴边时窗口有几百 px 挂在屏外，直接取 (x0+x1)/2 会点到屏幕外。
    """
    f = pet_frame()
    if not f:
        return False
    x0, y0, x1, y1 = f
    sw = E.screen_w()
    x = (max(0, x0) + min(sw, x1)) // 2
    y = y0 + int((y1 - y0) * frac)
    V.tap(x, y)
    return True


def visible_w(f):
    """窗口在屏幕上**真正露出来**的宽度（贴边后只剩一条缝）。"""
    if not f:
        return 0
    sw = E.screen_w()
    return max(0, min(sw, f[2]) - max(0, f[0]))


def show_pet():
    """回到桌面，让桌宠**可见**（★ 关键，见文件头那个坑）。

    返回桌面后桌宠从"临时收起"恢复显示；再等它真的进窗口表。
    """
    V.sh('shell', 'input', 'keyevent', '3')       # KEYCODE_HOME
    time.sleep(2.0)
    return wait_pet_frame(20)


def dock_now():
    """用**调试入口**让桌宠立刻贴边（`FloatingService:641` 的 ACTION_DOCK_NOW，无 UI 入口）。"""
    V.sh('shell', 'am', 'startservice', '-n', PKG + '/.FloatingService',
         '-a', 'com.deepseek.pet.DOCK_NOW')


def undock_now():
    V.sh('shell', 'am', 'startservice', '-n', PKG + '/.FloatingService',
         '-a', 'com.deepseek.pet.UNDOCK_NOW')


def logcat():
    return V.out('logcat', '-d')


def ensure_tab_root():
    """确保停在**板块首页**（不是某个二级页）。

    ★ 实测坑（`verify_web_ui` 记过一半，本轮补齐另一半）：
      ① App 会**记住上次的二级页** ⇒ 下次启动直接落在那里，而那时点底栏「陪伴」是**空操作**
         （`switchTab` 对"已在同一板块且不在根页"有守卫）；
      ② ★★ 判据 `'返回' in dump` **只有在页面滚到顶部时才成立** ——
         二级页的「返回」在页面最上面，而 `uiautomator` 只 dump **屏上渲染出来的**节点。
         我第一版没先滚到顶，于是"滚在下面的二级页"被误判成板块首页 ⇒
         后面找板块上的行永远找不到（点底栏又是空操作）⇒ 整条导航静默失败。
    """
    # ① 先滚回顶部（最多 6 下），否则下面那条判据会撒谎
    for _ in range(6):
        V.swipe(540, 1000, 540, 1700, 500)
        time.sleep(0.35)
    # ② 有「返回」就按它回板块首页（可能要多按一级）
    for _ in range(3):
        if '返回' not in V.dump():
            return True
        if not V.tap_text_exact('返回'):
            break
        time.sleep(1.5)
    return '返回' not in V.dump()


def row_tap_point(needle, x=None):
    """找那一行并算出**可点容器**的中心 `(x, y)`；找不到返回 None。

    ★ 为什么不用 `V.tap_row`：它按"该 y 上最小的可点容器"取中心，而那一行可能
      **只有文字在屏内**、容器本身被裁在底部导航栏下面 ⇒ 中心落到导航栏上，
      表现成"点了没反应"（与 STATE.md 里"裁掉的行 bounds 是反的"是同一族问题）。
    """
    x = x if x is not None else V.dump()
    for t, _d, _c, _ck, _chk, _x0, y0, _x1, y1 in V.nodes(x):
        if not t or needle not in t:
            continue
        ty = (y0 + y1) // 2
        best = None
        for _t2, _d2, _c2, ck2, _chk2, a0, b0, a1, b1 in V.nodes(x):
            if not ck2 or not (b0 <= ty <= b1):
                continue
            area = (a1 - a0) * (b1 - b0)
            if best is None or area < best[0]:
                best = (area, (a0 + a1) // 2, (b0 + b1) // 2)
        return (best[1], best[2]) if best else None
    return None


def tap_row_safe(needle, tries=8):
    """滚到那一行并点它：**要求可点容器中心落在内容区**（约 120~2100px）。

    ★ 方向要按"行在内容区的哪一侧"决定 —— 第一版写死"往下滚"，
      于是那一行在屏内偏上时越滚越远、永远点不到（实测 10 次全失败）。
    """
    for _ in range(tries):
        p = row_tap_point(needle)
        if p and 120 < p[1] < 2100:
            V.tap(p[0], p[1])
            return True
        if p and p[1] <= 120:
            V.swipe(540, 1200, 540, 1700, 500)      # 行在太上面 ⇒ 往上滚把它拉下来
        else:
            V.swipe(540, 1500, 540, 1100, 500)      # 行在太下面 / 还没出现 ⇒ 往下滚
        time.sleep(0.6)
    return False


def goto_local_lines_page():
    """进「陪伴 → 对话与人格 → 纯本地台词：逐场景自定义…」这一页（**带后置条件**）。

    ★ 入口链是**二级页套二级页**（`SECTION_LOCAL_LINES = 9` 只能从二级页里进）：
      每一步都要先回板块首页、再滚到那一行、再点 —— 它不在首屏。
    ★ 踩过的三个坑（都在函数体里标了）：
      ① 按 `MainActivity` 里的**过期注释**写成「记忆与省 token」——
         那一行其实在 `settingsSection == 1`（对话与人格）里（注释已顺手改对）；
      ② App 记住上次的二级页 ⇒ 点底栏是空操作（先 `ensure_tab_root`）；
      ③ **点完必须能看见下一篇的标志**才算成功：不然后面所有断言都在错的页面上跑。
    """
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(3.5)
    ensure_tab_root()                      # 坑②：先回板块首页，否则点底栏是空操作
    for _ in range(2):                     # 切「陪伴」板块（点两次：第一次可能还在动画里）
        if V.wait_text('陪伴', 8):
            V.tap_text_exact('陪伴')
            time.sleep(1.8)
    # 坑①：台词那行在「对话与人格」二级页里；已经在那一页就不用再进（判据：联网检索）
    if not any('联网检索' in t for t in S.all_texts()):
        if not tap_row_safe('对话与人格'):
            return False
        time.sleep(2.2)
    # ★ 不预先 `scroll_any` 卡一道"必须完整可见"的门：`scroll_any` 只给 6 次下拉、
    #   而且判据是"整行都在屏内"，实测这一行**滚不到那个状态**就直接被判失败 ——
    #   而 `tap_row_safe` 自己会按方向滚 + 重试（实测它能点开）。
    # ★ 判据要用**完整行文案**：`纯本地台词` 不是唯一子串 —— 上面那行开关写的是
    #   「互动时桌宠发言（关 = 纯本地台词，开 = 调用 API）」，先被匹配到 ⇒
    #   "点这一行"实际点到了那个**开关**（实测 x=922 正是开关的位置），页面纹丝不动。
    line_row = '纯本地台词：逐场景'
    for _ in range(3):
        if tap_row_safe(line_row):
            time.sleep(2.5)
            if V.has_text('逐场景'):
                return True
    return False


def collect_line_rows():
    """把台词页上的每一行都收齐（页面比一屏高 ⇒ 边滚边收）。

    行文案形如 `点击头部：用内置台词` / `点一下：自定义 2 句：xxx…`（行首 emoji 被徽章吃掉）。
    """
    seen = []
    for _ in range(14):
        for t in S.all_texts():
            if ('：用内置台词' in t or '：自定义' in t) and t not in seen:
                seen.append(t)
        V.swipe(540, 1600, 540, 900, 600)
        time.sleep(0.7)
    return seen


def wait_log(needle, secs=15):
    t0 = time.time()
    while time.time() - t0 < secs:
        if needle in V.out('logcat', '-d'):
            return True
        time.sleep(1.0)
    return False


# ═══════════════ 主流程 ═══════════════

def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能读 filesDir / prefs')
        return 1

    print()
    print('== 一、准备：把测试角色（只绑内置动作）直接落盘，并切成当前角色 ==')
    S.pref_set('wander_on', 'false')          # 先关漫游，免得它插进来搅乱前两节
    S.pref_set('edge_snap', 'true')
    # ★ 关掉启动问候：它会立刻弹一个气泡窗口盖在桌宠上，按比例戳的三下会打在气泡上
    S.pref_set('greeting_on', 'false')
    src = write_test_character_files()
    check('测试角色落盘 %s/characters/%s/（含 1 个真动作，已还原 owner/SELinux）'
          % (FILES, CHAR_ID), install_test_character(src))
    S.pref_set('current_character', CHAR_ID)
    V.sh('shell', 'am', 'force-stop', PKG)
    V.sh('shell', 'logcat', '-c')
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    f = wait_pet_frame(30)
    check('桌宠窗口在（%s）' % (f,), f is not None)
    if not f:
        return finish()
    check('★ 当前角色就是测试角色（装配日志 char=%s）' % CHAR_ID,
          wait_log('applyPetDrawable char=' + CHAR_ID, 20),
          [l for l in logcat().splitlines() if 'applyPetDrawable' in l][-1:])
    # ★★ 必须回桌面再戳：App 在前台时桌宠被**临时收起**（窗口还在、但不可见），
    #    点击落在它身上不会有任何反应（第一版就是这么假红的）。
    check('★ 回桌面后桌宠可见（App 前台时它是收起的）', show_pet() is not None)

    print()
    print('== 二、★ 部位点击：头 / 身 / 尾 各戳一下 ==')
    V.sh('logcat', '-c')
    # ★ 每下之间隔 6 秒：双击判定窗口 350ms、三击还要再等 400ms（戳太快会变成双击/三击，
    #   后面的点击就打到输入条上了）；而且部位反馈会弹一个 5 秒的气泡窗口，间隔太短会打在气泡上。
    for frac, want, label in ((0.2, 'tap_head', '头'), (0.5, 'tap_body', '身'), (0.8, 'tap_tail', '尾')):
        if not tap_part(frac):
            skip('戳%s' % label, '取不到桌宠窗口')
            continue
        time.sleep(6)
        n = len(scenes_played(logcat(), want))
        check('★ 戳%s ⇒ 播了 %s（累计 %d 次）' % (label, want, n), n >= 1,
              '窗内该场景次数=%d' % n)
    lg = logcat()
    got = [s for s in ('tap_head', 'tap_body', 'tap_tail') if scenes_played(lg, s)]
    check('★ 三个部位场景**都**出现了（用户报的"无法触发"）', len(got) == 3, got)

    print()
    print('== 三、★ 贴边：DOCK_NOW ⇒ dock 场景 + 窗口收成竖条 ==')
    V.sh('logcat', '-c')
    before_w = visible_w(pet_frame())
    dock_now()
    time.sleep(3)
    lg = logcat()
    check('★ 播了 dock 场景', len(scenes_played(lg, 'dock')) >= 1,
          [l for l in lg.splitlines() if 'PetRotate' in l][-3:])
    after_w = visible_w(pet_frame())
    check('★ 收成竖条：屏幕上露出的宽度明显变小（%d → %d px）' % (before_w, after_w),
          before_w > 200 and after_w < before_w // 2,
          '贴边前可见宽=%d 贴边后=%d' % (before_w, after_w))
    undock_now()
    time.sleep(2)

    print()
    print('== 四、★ 漫游时长：走多久 = 漫游动作多久（动作不许被掐断）==')
    S.pref_set('wander_on', 'true')
    # pref_set 会 force-stop ⇒ 重新把桌宠拉起来，并回桌面（前台时它是收起的）
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    if not wait_pet_frame(30):
        check('漫游前桌宠窗口在', False)
        return finish()
    show_pet()
    V.sh('logcat', '-c')
    print('  （漫游是随机的：70% 走一段 / 30% 原地转向，最多等 60 秒抓一次 walk）')
    t0 = time.time()
    hold = None
    while time.time() - t0 < 60:
        lg = logcat()
        if roam_durations(lg) and roam_hold_ms(lg) is not None:
            hold = roam_hold_ms(lg)
            break
        time.sleep(3)
    lg = logcat()
    durs = roam_durations(lg)
    if not durs:
        skip('漫游时长', '60 秒内没抓到一次 walk（随机性，重跑一次即可）')
    else:
        roam_ms, walk_ms = durs[-1]
        check('日志给出漫游动作时长（%d ms）与计划走时长（%d ms）' % (roam_ms, walk_ms),
              roam_ms >= 400 and walk_ms >= 1200, durs[-1])
        check('★ 计划走时长 ≥ 动作时长（走完正好播完）', walk_ms >= roam_ms - 50,
              '走=%d 动作=%d' % (walk_ms, roam_ms))
        if hold is None:
            skip('实测 roam→idle 间隔', '抓到 walk 但还没等到切回 idle')
        else:
            check('★★ 实测：roam 场景**至少保持了动作时长**（没被掐断）', hold >= roam_ms - 300,
                  '实测 %d ms / 动作 %d ms' % (hold, roam_ms))

    print()
    print('== 五、★ 部位自定义台词：按部位生效 + 三级回退 ==')
    # ★ 用户 2026-10-11：「被戳头尾部身体的时候也有自定义对话」⇒ 每个部位一个自定义槽。
    #   三级回退：① 部位自定义 → ② 「点一下」自定义（老用户写过的）→ ③ 内置池。
    #   这里先**清空**三个部位 + "点一下"，再只写"头部"与"点一下"两处，
    #   于是：戳头应命中①、戳尾应命中②（这两条正好把回退链整条验证了）。
    HEAD_LINE = 'HEADTEST-AAA'
    CLICK_LINE = 'CLICKTEST-BBB'
    for k in ('local_line_tap', 'local_line_tap_head', 'local_line_tap_body', 'local_line_tap_tail'):
        S.pref_set(k, '')
    S.pref_set('local_line_tap_head', HEAD_LINE)
    S.pref_set('local_line_tap', CLICK_LINE)
    check('前置：只给「点击头部」与「点一下」写了自定义（其余留空）',
          S.pref('local_line_tap_head') == HEAD_LINE and S.pref('local_line_tap') == CLICK_LINE)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    if not wait_pet_frame(30):
        check('台词验证前桌宠窗口在', False)
        return finish()
    show_pet()
    V.sh('logcat', '-c')
    tap_part(0.2)                      # 戳头
    time.sleep(5)
    lg = logcat()
    check('★ 戳头 ⇒ 说「头部」的自定义（%s）' % HEAD_LINE,
          ('本地台词 scene=tap_head line=' + HEAD_LINE) in lg,
          [l for l in lg.splitlines() if '本地台词' in l][-3:])
    V.sh('logcat', '-c')
    tap_part(0.8)                      # 戳尾（没写部位自定义）
    time.sleep(5)
    lg = logcat()
    check('★ 戳尾 ⇒ 回退到「点一下」的自定义（第二级：老用户的台词不会突然听不到）',
          ('本地台词 scene=click line=' + CLICK_LINE) in lg,
          [l for l in lg.splitlines() if '本地台词' in l][-3:])
    check('★ 戳尾**没有**误用头部的自定义（部位之间不串味）',
          ('scene=tap_tail line=' + HEAD_LINE) not in lg)

    print()
    print('== 六、★ 台词页 UI：清单真的跟动作清单一一对应（18 行）==')
    # ★ 轮次㊲ 收口时自己留的缺口：离线断言了"清单派生自动作清单 + 每行都有出口"，
    #   设备上只验了"戳头/尾说话"，**没人看过那一页真的渲染出 18 行**。
    #   入口：陪伴 → 记忆与省 token → 「纯本地台词：逐场景自定义…」
    if not goto_local_lines_page():
        check('进得了「纯本地台词 · 逐场景」页', False)
    else:
        check('进得了「纯本地台词 · 逐场景」页', True)
        ts = S.all_texts()
        check('★ 页面说明写着「共 18 个」（跟着动作清单走，不是手抄）',
              any('共 18 个' in t for t in ts),
              [t for t in ts if '一一对应' in t][:1])
        rows = collect_line_rows()
        names = [r.split('：', 1)[0] for r in rows]
        check('★ 台词行数 = 动作场景数（18 行，边滚边收）', len(rows) == 18,
              '实测 %d 行：%s' % (len(rows), ' / '.join(names)))
        # 这些行是这一轮/上一轮才补上的：以前根本没有（清单只有手抄的六个）
        for want in ('点击头部', '点击身体', '点击尾巴', '漫游', '转向',
                     '双击', '长按', '余额事件', '碎碎念', '工作状态'):
            check('★ 台词页有「%s」这一行' % want, want in names, ' / '.join(names))

    print()
    print('== 七、收尾：恢复漫游开关与测试台词 ==')
    for k in ('local_line_tap', 'local_line_tap_head'):
        S.pref_set(k, '')
    check('测试台词已清掉（留空 = 用内置）',
          S.pref('local_line_tap_head') in (None, '')
          and S.pref('local_line_tap') in (None, ''))
    S.pref_set('wander_on', 'true')
    check('漫游开关恢复 true', S.pref('wander_on') in (None, 'true'),
          'wander_on=%s' % S.pref('wander_on'))
    return finish()


def finish():
    # ★ 设备脚本用完必须擦掉本地 pref 副本（含用户 Key）与设备上的临时文件：
    #   项目移植包会把整个 build/ 打进去（轮次㉘/㉞ 各中过一次）。
    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails or skips:
        if fails:
            print('%d 项失败：' % len(fails))
            for f in fails:
                print('   - ' + f)
        if skips:
            print('%d 项跳过（按未验证计）：%s' % (len(skips), ' / '.join(skips)))
        return 1
    print('ALL SCENE-EXIT DEVICE CHECKS PASSED（场景出口 + 漫游时长，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
