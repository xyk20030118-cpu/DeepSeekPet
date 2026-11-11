# -*- coding: utf-8 -*-
"""设备验证：**贴边动作（dock 场景）在"用户真走的那条路"上会播**（轮次㊽）。

用户原话：「**贴边动作没有触发**」。

## 真凶（实测定位，不是猜）

`dock` 场景原来只挂在 `dockCollapse()`（把桌宠压成竖条，且要**长按菜单手动点**「贴边吸附」
—— 自动贴边默认是关的：`dockIdleMs()` 恒 0）。而用户平常的"贴边"是**拖到边缘松手吸附**
与**漫游撞到边缘自动贴边**，它们走的是 `snapToNearestEdge()` —— 那条路只滑窗口、**从不播动作**。
★★ 更隐蔽的是：那条路里"已经在边上就直接 `return`"的早退**在播动作之前**，
而拖动过程中窗口早已被 `clampToScreen()` 夹到 min/max ⇒ 连"挂上去"的机会都没有。

## 判据（本脚本测的是**拖动**那条路）

  ① 拖动后读到 `DSPDock 贴边动作 scene=dock …`；
  ② 读到 `PetRotate … play scene=dock id=…`（**真的切到 dock 场景**，不是只打了一行日志）；
  ③ 窗口确实贴在边上（内容边距贴到屏幕边）。

★ 必须先用**绑了 dock 场景**的测试角色：默认内置角色只有一个待机动作，
`playSceneOnce` 对没绑的场景**静默跳过**（那会让 ② 假红 —— 我第一版就踩到了）。
本脚本复用 `verify_scene_exits_ui` 的测试角色（它把 18 个场景全绑到内置那个动作上）。

用法：
    python build/verify_dock_action_ui.py --install
    python build/verify_dock_action_ui.py
    python build/verify_dock_action_ui.py --selftest
"""
import os
import re
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402
import verify_char_ui as V           # noqa: E402
import verify_scene_exits_ui as X    # noqa: E402
import verify_char_switch_ui as S    # noqa: E402

DEV = V.DEV
PKG = V.PKG

fails = []
skips = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

DOCK_LOG = re.compile(r'DSPDock\s*:\s*贴边动作 scene=dock 贴(左|右) (-?\d+)→(-?\d+)')


def dock_hit(log_text):
    """从日志里取**最后一次**贴边反馈 → (方向, 起点x, 终点x)；没有就 None。"""
    hit = None
    for line in (log_text or '').splitlines():
        m = DOCK_LOG.search(line)
        if m:
            hit = (m.group(1), int(m.group(2)), int(m.group(3)))
    return hit


def scene_dock_played(log_text):
    """真的切到 dock 场景了吗（只认 PetRotate 那条 —— 别把 DSPDock 自己那行算进来）。"""
    return any('PetRotate' in ln and 'play scene=dock' in ln
               for ln in (log_text or '').splitlines())


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    lg = ('10-12 15:41:39.409 1 1 I PetRotate: play scene=idle id=x\n'
          '10-12 15:41:39.428 1 1 I PetRotate: play scene=dock id=原地重力下蹲压缩\n'
          '10-12 15:41:39.428 1 1 I DSPDock : 贴边动作 scene=dock 贴左 -210→-210\n')
    t('解析出贴边反馈（方向/起点/终点）', dock_hit(lg) == ('左', -210, -210))
    t('★ 真的切到 dock 场景', scene_dock_played(lg))
    # ★★ 这条是防"假绿"：只打日志、没真的播场景时必须判否
    t('★★ 只有 DSPDock 那行、没有 PetRotate scene=dock ⇒ 判否（防假绿）',
      not scene_dock_played('10-12 15:41:39.428 I DSPDock : 贴边动作 scene=dock 贴左 -210→-210\n'))
    t('反例：没贴过 → None', dock_hit('10-1 1:1 I DSVoice: 别的') is None)
    t('取**最后一条**（同一次会话可能贴好几次）',
      dock_hit(lg + '10-12 15:41:50.000 I DSPDock : 贴边动作 scene=dock 贴右 661→661\n')
      == ('右', 661, 661))
    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-60:])
        time.sleep(2)
    V.sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)

    # ★ 测试角色：18 个场景全绑内置那个动作（其中就有 dock）—— 没绑 dock 的角色
    #   会让 playSceneOnce 静默跳过，②会假红（第一版踩到过）
    print('== 装绑了 dock 场景的测试角色 ==')
    src = X.write_test_character_files()
    ok = X.install_test_character(src)
    check('测试角色装好（含 dock 绑定）', ok)
    S.pref_set('current_character', X.CHAR_ID)
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(6)

    fr = X.show_pet()
    check('桌宠可见（拿到窗口矩形）', bool(fr), fr)
    if not fr:
        return finish()
    cx = (max(0, fr[0]) + min(1080, fr[2])) // 2
    cy = (fr[1] + fr[3]) // 2

    print()
    print('== 拖到左边缘松手（走 snapToNearestEdge）==')
    V.sh('shell', 'logcat', '-c')
    V.swipe(cx, cy, 20, cy, 800)
    time.sleep(2.5)
    log = V.out('shell', 'logcat', '-d', '-s', 'DSPDock:*', 'PetRotate:*')
    check('★★ 读到贴边反馈日志（DSPDock）', dock_hit(log) is not None, dock_hit(log))
    check('★★ 真的切到 dock 场景（PetRotate play scene=dock）', scene_dock_played(log))
    f2 = X.pet_frame()
    check('★ 窗口贴在左边（可见内容贴到屏幕边）', bool(f2) and f2[0] < 0, f2)

    print()
    print('== 拖到右边缘松手（反向也要播）==')
    # ★★ 脚本坑（第一版就踩了）：**每次拖动都要按"当前"窗口重算起点**。
    #   贴到左边后窗口变成 [-210,420]，若还拿最初算的 cx=315 当起点，
    #   手指只走了 745px（够不到右边 871px 的行程）⇒ 松手时窗口离右边还有 126px
    #   ⇒ `nearScreenEdge()` 为假 ⇒ **不吸附、不播动作**，报出来却像"产品 bug"。
    f_left = X.pet_frame()
    cx2 = (max(0, f_left[0]) + min(1080, f_left[2])) // 2
    V.sh('shell', 'logcat', '-c')
    V.swipe(cx2, cy, 1075, cy, 800)
    time.sleep(2.5)
    log2 = V.out('shell', 'logcat', '-d', '-s', 'DSPDock:*', 'PetRotate:*')
    check('★★ 贴右边也读到了贴边反馈', dock_hit(log2) is not None, dock_hit(log2))
    check('★ 贴右边也真的切了 dock 场景', scene_dock_played(log2))
    f_right = X.pet_frame()
    check('★ 窗口贴在右边', bool(f_right) and f_right[0] > 500, f_right)
    return finish()


def finish():
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
    print('ALL DOCK-ACTION DEVICE CHECKS PASSED（拖到边缘会播 dock 动作，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
