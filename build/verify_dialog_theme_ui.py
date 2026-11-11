# -*- coding: utf-8 -*-
"""设备验证：**三级菜单（对话框）跟随 App UI 与主题切换**（轮次㊵ · 用户 2026-10-11 要求）。

用户原话：「**更改所有的三级菜单让所有的三级菜单都同步 app ui 跟随主题切换**」。

## 为什么必须有设备验证

源码级断言只能证明"所有弹窗都从 `UiFactory.dialog` 造、themeDialog 里套了令牌"，
证明不了**真的生效**：对话框是系统自绘的，`setBackgroundDrawable` / `findViewById(alertTitle)`
都可能被系统忽略（不同 ROM 的内部 id 与布局都不一样）。

## 判据（两层，都不依赖"看截图"）

① **日志**：`DSDialog` 那行会报 `skin= / theme= / surface= / accent= / bg=`。
   ★ 其中 `bg=` 是**窗口背景 drawable 的类名** —— 这是"面板真被换成我们的 drawable"的
   **直接证据**（应为 `InsetDrawable`）。
   ★★ 为什么不用截图像素：**新拟态下 `surface == bg` 是设计如此**（形状只靠浮雕阴影表达），
   截图取色根本分不出弹窗与页面；玻璃态又是半透明叠 Mesh，取到的色不等于令牌值。
   所以这里判"状态"，不判"像素"。
② **切主题前后各开一次**：同一行入口、同一个弹窗，
   * 改 `ui_theme`（明暗）⇒ 日志里的 `theme=` 与 `surface=` 都变；
   * 改 `ui_skin`（新拟态 / 玻璃态）⇒ `skin=` 与 `surface=` 都变。
   —— 这才是"**跟随**主题切换"的证据（只看一次日志证明不了"跟随"）。

## 分节

  一、基线：记录当前皮肤/明暗，开一次弹窗读日志
  二、切**明暗**：`ui_theme` 取反 → 重启 → 同一个弹窗 ⇒ theme 与 surface 都变
  三、切**皮肤**：`ui_skin` 取反 → 重启 → 同一个弹窗 ⇒ skin 与 surface 都变
  四、收尾：恢复原来的皮肤/明暗 + 重启（不然后面的设备脚本/用户界面会停在我改的状态）

★ 导航工具**复用** `verify_scene_exits_ui` 的 `ensure_tab_root` / `tap_row_safe`
（"App 记住二级页 ⇒ 点底栏是空操作"、"行只有文字在屏内 ⇒ 点到了导航栏"这些坑那边都踩过并写明了），
本脚本不再抄一份。
★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机）。

用法：
    python build/verify_dialog_theme_ui.py --install
    python build/verify_dialog_theme_ui.py
    python build/verify_dialog_theme_ui.py --selftest
"""
import re
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402  本地 pref 副本的擦除（防 Key 进源码包）
import verify_char_ui as V           # noqa: E402  设备驱动
import verify_char_switch_ui as S    # noqa: E402  可靠改 pref / 设备清理
import verify_scene_exits_ui as X    # noqa: E402  导航工具（不抄第二份）

DEV = V.DEV
PKG = V.PKG
UI_PREFS = '/data/data/%s/shared_prefs/deepseek_pet_ui.xml' % PKG
K_SKIN = 'ui_skin'        # 0 新拟态 / 1 玻璃态
K_THEME = 'ui_theme'      # -1 跟随系统 / 0 浅 / 1 深
ROW = '互动对话冷却：'      # 一个**无害**的入口（点开是选择框，退掉即可）

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


def sys_night():
    """系统当前是不是深色。

    ★ 用于 `ui_theme=-1`（跟随系统）时把**原始 pref** 解析成"实际生效的明暗" ——
      日志里打的是解析后的值（`Tokens.theme`），两边口径必须一致，否则假红（见 main 第一节）。
    """
    try:
        out = V.out('shell', 'cmd', 'uimode', 'night')
    except Exception:
        return False
    return 'yes' in (out or '').lower()


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

LOG_RE = re.compile(r'三级菜单套用令牌 skin=(-?\d+) theme=(-?\d+) '
                    r'surface=#([0-9a-fA-F]+) accent=#([0-9a-fA-F]+) bg=(\S+)')


def parse_themed(log_text):
    """从 logcat 里取**最后一条**主题化记录 → dict（没有就 None）。

    ★ 取最后一条：同一个弹窗可能被开过多次，只有最新那次代表"当前主题下"的状态。
    """
    hit = None
    for line in (log_text or '').splitlines():
        m = LOG_RE.search(line)
        if m:
            hit = {'skin': int(m.group(1)), 'theme': int(m.group(2)),
                   'surface': m.group(3).lower(), 'accent': m.group(4).lower(),
                   'bg': m.group(5)}
    return hit


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    lg = ('10-11 16:00:00.000 1 1 I DSDialog: 三级菜单套用令牌 skin=0 theme=0 '
          'surface=#f2f3f7 accent=#1d5ac5 bg=InsetDrawable\n'
          '10-11 16:00:05.000 1 1 I DSDialog: 三级菜单套用令牌 skin=1 theme=1 '
          'surface=#22303c accent=#8ab4f8 bg=InsetDrawable\n')
    t('取到**最后一条**（代表当前主题）', parse_themed(lg)['theme'] == 1)
    t('skin / surface / accent / bg 都解析出来',
      parse_themed(lg) == {'skin': 1, 'theme': 1, 'surface': '22303c',
                           'accent': '8ab4f8', 'bg': 'InsetDrawable'})
    t('反例：没有日志 → None', parse_themed('') is None and parse_themed(None) is None)
    t('反例：别的日志不算', parse_themed('I PetRotate: play scene=idle') is None)
    t('反例：bg 缺失时不算一条完整记录',
      parse_themed('三级菜单套用令牌 skin=0 theme=0 surface=#fff accent=#000') is None)
    # ★ 轮次㊹：首帧补齐那条
    _ff = ('10-11 16:00:01.000 1 1 I DSDialog: 首帧补齐 btn1=StateListDrawable btn1MinH=116 '
           'btn2=StateListDrawable rows=9 adapter=DialogRowAdapter')
    t('首帧补齐：解析出 adapter / rows',
      (parse_firstframe(_ff) or {}).get('adapter') == 'DialogRowAdapter'
      and (parse_firstframe(_ff) or {}).get('rows') == 9)
    t('首帧补齐：btn1MinH 解析成整数', (parse_firstframe(_ff) or {}).get('btn1MinH') == 116)
    t('首帧补齐：没有就 None', parse_firstframe('10-1 1:1 I DSDialog: 别的') is None)
    t('★ 本脚本只认模拟器（不许往用户真机装包/改设置）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def restart_app(wait=6.0):
    V.sh('shell', 'am', 'force-stop', PKG)
    V.sh('shell', 'logcat', '-c')
    time.sleep(1.0)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(wait)


FIRSTFRAME_RE = re.compile(
    r'首帧补齐 btn1=(\S+) btn1MinH=(-?\d+) btn2=(\S+) rows=(\d+) adapter=(\S+)')
# ★ 最近一次开弹窗的原始日志（main 里要用它验"首帧补齐"那条）
LAST_LOG = ''


def parse_firstframe(log_text):
    """取**最后一条**"首帧补齐"记录 → dict（没有就 None）。

    ★★ 为什么必须有这条判据（轮次㊹）：`create()` 那一刻拿到的 ListView / 按钮
    **都不是最终显示的那套**（AlertController 到 show() 阶段才装内容）⇒
    只在 create() 里做样式的版本**看起来像做了、其实没生效**。
    用户就是因此说「三级菜单 ui 还是没有变」。所以"真的换上了"只能在**这条**日志里认。
    """
    hit = None
    for line in (log_text or '').splitlines():
        m = FIRSTFRAME_RE.search(line)
        if m:
            hit = {'btn1': m.group(1), 'btn1MinH': int(m.group(2)),
                   'btn2': m.group(3), 'rows': int(m.group(4)), 'adapter': m.group(5)}
    return hit


def open_dialog_and_read():
    """进「陪伴 → 对话与人格」→ 点开「互动对话冷却」弹窗 → 读 DSDialog 日志。"""
    global LAST_LOG
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(3.0)
    X.ensure_tab_root()
    for _ in range(2):
        if V.wait_text('陪伴', 8):
            V.tap_text_exact('陪伴')
            time.sleep(1.6)
    if not any('联网检索' in t for t in S.all_texts()):      # 已在那一页就不用再进
        if not X.tap_row_safe('对话与人格'):
            return None
        time.sleep(2.0)
    V.sh('shell', 'logcat', '-c')
    if not X.tap_row_safe(ROW):
        return None
    time.sleep(2.5)
    LAST_LOG = V.out('logcat', '-d')
    return parse_themed(LAST_LOG)


def dismiss():
    for _ in range(2):
        V.sh('shell', 'input', 'keyevent', '4')
        time.sleep(0.5)


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能读/改 prefs 与 token 文件')
        return 1

    skin0 = S.pref_in(UI_PREFS, K_SKIN)
    theme0 = S.pref_in(UI_PREFS, K_THEME)
    print('== 一、基线：当前 ui_skin=%s ui_theme=%s ==' % (skin0, theme0))
    base = open_dialog_and_read()
    dismiss()
    if not base:
        check('打开一个三级菜单并读到主题化日志', False, '（没读到 DSDialog 那行）')
        return finish()
    check('★★ 打开弹窗时套用了令牌（读到 DSDialog 日志）', True, base)
    check('★★ 面板真的被换成我们的 drawable（bg=InsetDrawable，不是系统 9-patch）',
          base['bg'] == 'InsetDrawable', base['bg'])
    # ★★ 轮次㊹：`create()` 里那套不是最终显示的那套 ⇒ 只有"首帧补齐"这条能证明真的换上了
    ff = parse_firstframe(LAST_LOG)
    check('★★ 读到"首帧补齐"那条日志（行/按钮真的补上了）', ff is not None, ff)
    if ff:
        check('★★ 行适配器是我们的 DialogRowAdapter（行真的换掉了，不是系统 CheckedTextView）',
              ff['adapter'] == 'DialogRowAdapter' and ff['rows'] > 0, ff)
    # ★★ 这条判据修过一次（轮次㊸）：原来拿日志里**已经解析过**的 theme 去比 pref 的**原始值**，
    #    而 pref 允许 `-1 = 跟随系统` ⇒ 设备处于浅色时日志是 `theme=0`、pref 是 `-1`，
    #    两边永远对不上（**假红**：产品没问题，是判据自己错）。
    #    现在把 -1 解析成"当前系统明暗"再比（与 Tokens.theme 的口径一致）。
    exp_theme = theme0 if theme0 in ('0', '1') else ('1' if sys_night() else '0')
    check('★ 日志里的 skin/theme 与当前设置一致（-1=跟随系统，按系统明暗解析）',
          str(base['skin']) == (skin0 if skin0 is not None else '0')
          and str(base['theme']) == exp_theme,
          'base=%s / pref skin=%s theme=%s（解析后 %s）' % (base, skin0, theme0, exp_theme))

    print()
    print('== 二、切**明暗**：ui_theme 取反 → 重启 → 同一个弹窗 ==')
    t_new = '0' if str(base['theme']) == '1' else '1'
    check('切明暗写进 prefs', S.pref_set_in(UI_PREFS, K_THEME, t_new),
          '%s=%s' % (K_THEME, S.pref_in(UI_PREFS, K_THEME)))
    restart_app()
    dark = open_dialog_and_read()
    dismiss()
    check('★ 读到新主题下的日志', bool(dark), dark)
    if dark:
        check('★★ theme 跟着切了', str(dark['theme']) == t_new,
              '期望 %s 实得 %s' % (t_new, dark['theme']))
        check('★★ surface 也跟着变了（换主题后新开的弹窗立刻是新配色）',
              dark['surface'] != base['surface'],
              '%s → %s' % (base['surface'], dark['surface']))
        check('★ 面板仍然是我们的 drawable', dark['bg'] == 'InsetDrawable', dark['bg'])

    print()
    print('== 三、切**皮肤**：ui_skin 取反 → 重启 → 同一个弹窗 ==')
    s_new = '0' if str(base['skin']) == '1' else '1'
    check('切皮肤写进 prefs', S.pref_set_in(UI_PREFS, K_SKIN, s_new),
          '%s=%s' % (K_SKIN, S.pref_in(UI_PREFS, K_SKIN)))
    restart_app()
    glass = open_dialog_and_read()
    dismiss()
    check('★ 读到新皮肤下的日志', bool(glass), glass)
    if glass:
        check('★★ skin 跟着切了', str(glass['skin']) == s_new,
              '期望 %s 实得 %s' % (s_new, glass['skin']))
        check('★★ surface 也跟着变了（新拟态 ↔ 玻璃态）',
              glass['surface'] != base['surface'],
              '%s → %s' % (base['surface'], glass['surface']))

    print()
    print('== 四、收尾：恢复原来的皮肤/明暗 ==')
    # ★ 原本可能**根本没有** deepseek_pet_ui.xml（SharedPreferences 首次写入才建文件）
    #   ⇒ 那时"恢复"要写成**默认值**（skin=0 新拟态 / theme=-1 跟随系统），
    #   否则会把 App 卡在我改的那套主题上（用户下次打开会莫名其妙变深色）。
    skin_restore = skin0 if skin0 is not None else '0'
    theme_restore = theme0 if theme0 is not None else '-1'
    if skin0 is None or theme0 is None:
        print('   （原本没有这个 prefs 文件 ⇒ 恢复成默认值 skin=0 / theme=-1 跟随系统）')
    S.pref_set_in(UI_PREFS, K_SKIN, skin_restore)
    S.pref_set_in(UI_PREFS, K_THEME, theme_restore)
    check('皮肤已恢复', S.pref_in(UI_PREFS, K_SKIN) == skin_restore,
          '%s=%s' % (K_SKIN, S.pref_in(UI_PREFS, K_SKIN)))
    check('明暗已恢复', S.pref_in(UI_PREFS, K_THEME) == theme_restore,
          '%s=%s' % (K_THEME, S.pref_in(UI_PREFS, K_THEME)))
    restart_app()
    back = open_dialog_and_read()
    dismiss()
    check('恢复后：弹窗仍然是主题化的（bg=InsetDrawable）',
          bool(back) and back['bg'] == 'InsetDrawable', back)
    if back and base:
        check('恢复后：surface 回到基线（证明这几步真的是主题在起作用）',
              back['surface'] == base['surface'],
              '%s → %s' % (base['surface'], back['surface']))
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
    print('ALL DIALOG-THEME DEVICE CHECKS PASSED（三级菜单跟随皮肤与明暗，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
