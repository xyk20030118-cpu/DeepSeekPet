# -*- coding: utf-8 -*-
"""设备验证：**三页跟随主题切换**（用量页 / 权限页 / 平台登录页）—— 轮次㊷ 扩写。

用户原话（轮次㊵）：「更改所有的三级菜单让所有的三级菜单都同步 app ui 跟随主题切换」。
轮次㊵ 做的是**弹窗**那层、㊶ 做的是**用量页**，轮次㊷ 收掉最后两页 ——
本脚本现在把**三页**都在真机（模拟器）上验一遍。

## 为什么必须有设备验证

源码级断言只能证明"不再写死颜色、都走令牌"。证明不了**屏幕上真的不是白页** ——
而这三页原来的病都是"深色下整页还是浅色/白色"。

## 判据（截图取色，不依赖肉眼）

每页在浅色与深色下**各截一次屏**，取**众数色**当页面底色，然后四条：

  ① 浅色底色 != **该页原来写死的那个颜色**
  ② 深色底色 != 该页原来写死的那个颜色
  ③ 深色底色 != 浅色底色（**真的跟随主题切换**）
  ④ 深色下三通道之和 < 330（**不是白页**；旧实现在这条上必红）

## 采样点：三页的版式不同，所以取色的位置也不同（★ 别照抄一套）

  * **用量页** —— **左边缘**（x=4/8/12）：卡片有 14dp 侧边距，左边缘那一列是纯页面底色；
  * **权限页** —— **整页网格**：它用 `SettingsPage`，卡片是**满宽**的 ⇒ 最左那几列落在
    卡片的浮雕余量/外阴影上（不是干净底色）⇒ 改成在页面内部撒网格取众数；
  * **平台登录页** —— **左右两条边**：中间被 WebView 占满（官方页面是白的，取众数会取到白），
    而 WebView 四周有 10dp 页面边距 ⇒ 只有最外侧那几列是纯页面底色。

★ 三套采样点都避开状态栏（y<300）与导航栏（y>2300）。
★ 玻璃态的底色是 Mesh 渐变（逐点不同）⇒ 取**众数**而不是某一点。
★ 铁律：**永远只认 `-s emulator-5554`**（绝不往用户真机装包 / 改设置）。

用法：
    python build/verify_page_theme_ui.py --install
    python build/verify_page_theme_ui.py
    python build/verify_page_theme_ui.py --selftest
"""
import collections
import os
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
K_THEME = 'ui_theme'        # -1 跟随系统 / 0 浅 / 1 深
SHOT = '/sdcard/_page_theme_shot.png'

# ★ 每页**原来写死的那个颜色** —— 判据就是"屏幕上不该再是它"
#   （用量页 0xFFF5F8FC / 权限页 0xFFF7F7FA / 登录页 Color.WHITE）
PAGES = [
    ('usage', '用量页', (0xF5, 0xF8, 0xFC)),
    ('perm', '权限页', (0xF7, 0xF7, 0xFA)),
    ('login', '平台登录页', (0xFF, 0xFF, 0xFF)),
]

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


# ═══════════════ 采样点（三页各一套，见模块注释）═══════════════

def left_points(w=1080, h=2400):
    """用量页：左边缘（卡片有 14dp 侧边距 ⇒ 左边缘是纯页面底色）。"""
    return [(x, y) for x in (4, 8, 12) for y in range(300, 2300, 100)]


def grid_points(w=1080, h=2400):
    """权限页：整页网格（它用 SettingsPage，卡片满宽 ⇒ 左边缘落在卡片阴影上）。"""
    return [(x, y) for x in range(24, w - 24, 72) for y in range(300, 2300, 120)]


def edge_points(w=1080, h=2400):
    """平台登录页：左右两条边（中间被 WebView 占满，而它四周有 10dp 页面边距）。"""
    xs = (4, 8, 12, w - 13, w - 9, w - 5)
    return [(x, y) for x in xs for y in range(300, 2300, 100)]


POINTS = {'usage': left_points, 'perm': grid_points, 'login': edge_points}


def dominant_color(img, pts):
    """取采样点的**众数**颜色（玻璃态底色是渐变 ⇒ 不能只看一个点）。"""
    cnt = collections.Counter()
    for x, y in pts:
        if 0 <= x < img.width and 0 <= y < img.height:
            cnt[img.getpixel((x, y))[:3]] += 1
    return cnt.most_common(1)[0][0] if cnt else None


def verdict(key, name, old, light, dark):
    """把两次取色变成判据（返回 (名字, 是否通过, 说明) 列表）——离线可验。"""
    out = []
    out.append(('%s ① 浅色不是旧的写死色' % name,
                light is not None and tuple(light) != tuple(old),
                '浅=%s 旧=%s' % (light, tuple(old))))
    out.append(('%s ② 深色不是旧的写死色' % name,
                dark is not None and tuple(dark) != tuple(old),
                '深=%s 旧=%s' % (dark, tuple(old))))
    out.append(('%s ③ 深色 ≠ 浅色（真的跟随主题）' % name,
                light is not None and dark is not None and tuple(light) != tuple(dark),
                '%s → %s' % (light, dark)))
    out.append(('%s ④ 深色下不是白页（三通道和 < 330）' % name,
                dark is not None and sum(dark) < 330,
                '深=%s 和=%s' % (dark, (sum(dark) if dark else None))))
    return out


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    # ① 旧实现：深浅都还是那个写死的色 ⇒ ②③④ 必红（**三页都要成立**）
    for key, name, old in PAGES:
        old_v = dict((n, p) for n, p, _d in verdict(key, name, old, old, old))
        t('★ 旧实现（%s：深浅都写死 %s）被判红' % (name, tuple(old)),
          not old_v['%s ② 深色不是旧的写死色' % name]
          and not old_v['%s ③ 深色 ≠ 浅色（真的跟随主题）' % name]
          and not old_v['%s ④ 深色下不是白页（三通道和 < 330）' % name])
    # ② 新实现：浅 (242,244,248) / 深 (26,27,30) —— 三页四条全过
    for key, name, old in PAGES:
        new = dict((n, p) for n, p, _d in verdict(
            key, name, old, (242, 244, 248), (26, 27, 30)))
        t('★ 新实现（%s：跟随主题）四条全过' % name, all(new.values()))
    # ③ 反例
    k0, n0, o0 = PAGES[0]
    t('反例：深色取到纯白被判红',
      not dict((n, p) for n, p, _d in verdict(
          k0, n0, o0, (242, 244, 248), (255, 255, 255)))[
              '%s ④ 深色下不是白页（三通道和 < 330）' % n0])
    t('反例：取不到颜色（None）不通过',
      not dict((n, p) for n, p, _d in verdict(k0, n0, o0, None, None))[
          '%s ③ 深色 ≠ 浅色（真的跟随主题）' % n0])
    # ④ 采样点
    for key, name, _old in PAGES:
        pts = POINTS[key]()
        t('%s 采样点数量够（众数有意义）' % name, len(pts) >= 30)
        t('%s 采样点避开状态栏/导航栏' % name,
          min(y for _x, y in pts) >= 300 and max(y for _x, y in pts) <= 2300)
    lp = edge_points()
    t('★ 登录页采样点含左右两条边（中间是 WebView，取众数会取到白）',
      min(x for x, _y in lp) <= 12 and max(x for x, _y in lp) >= 1080 - 13)
    t('★ 本脚本只认模拟器（不许往用户真机装包/改设置）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def restart_app(wait=6.0):
    """★ 每开一页都从**干净启动**开始：不靠 BACK 回退。

    为什么不省这一步：登录页的 WebView 会**吃掉 BACK**（`onBackPressed` 里
    `web.canGoBack()` 为真就先 goBack），于是"回到上一层"会静默失败、
    下一段的点击全落在错的地方（本项目在别处踩过同族坑）。
    """
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1.0)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(wait)


def open_usage_page():
    """陪伴 → 对话与人格 → 「查看官网用量 / 登录开放平台」那一行 → 用量页。"""
    restart_app()
    X.ensure_tab_root()
    for _ in range(2):
        if V.wait_text('陪伴', 8):
            V.tap_text_exact('陪伴')
            time.sleep(1.6)
    if not any('联网检索' in t for t in S.all_texts()):      # 已在「对话与人格」页就不用再进
        if not X.tap_row_safe('对话与人格'):
            return False
        time.sleep(2.0)
    # 入口行文案随登录态变：两种都试
    for needle in ('查看官网用量', '登录开放平台'):
        if X.tap_row_safe(needle):
            time.sleep(2.5)
            ts = S.all_texts()
            if any('连接开放平台' in t or '官网用量' in t for t in ts):
                return True
    return False


def open_permission_page():
    """系统 → 运行控制 → 「权限与后台保活引导」那一行 → 权限页。"""
    restart_app()
    X.ensure_tab_root()
    if not V.wait_text('系统', 8):
        return False
    V.tap_text_exact('系统')
    time.sleep(1.8)
    if not X.tap_row_safe('运行控制'):
        return False
    time.sleep(2.2)
    if not X.tap_row_safe('权限与后台保活引导'):
        return False
    time.sleep(2.5)
    ts = S.all_texts()
    return any('为什么桌宠会' in t for t in ts)


def open_login_page():
    """用量页（**未登录态**）→ 「去登录」→ 平台登录页 → 关掉授权说明框。

    ★★ 判据必须看**那个框**，不能看页面标题（本轮实测抓到的脚本 bug）：
      平台登录页一进来就弹《登录授权说明》，而 `uiautomator dump` **只 dump 顶层窗口**
      —— 框弹出时页面自己的文字（"登录 DeepSeek 开放平台"）**根本不在 dump 里**，
      按页面标题判会永远 False（表现成"没进去"，其实早进去了）。
    ★ 授权说明框是 `setCancelable(false)`、且「不同意」= `finish()`
      ⇒ 想留在这一页只能点「同意并继续」（点它才开始 loadUrl）。
    """
    if not open_usage_page():
        return False
    if not X.tap_row_safe('去登录'):
        return False
    time.sleep(2.5)
    if not any('登录授权说明' in t for t in S.all_texts()):
        return False
    V.tap_text_exact('同意并继续')
    time.sleep(3.0)
    # 关掉框之后才看得见页面本身
    return any('登录 DeepSeek 开放平台' in t for t in S.all_texts())


OPENER = {'usage': open_usage_page, 'perm': open_permission_page,
          'login': open_login_page}


def page_bg(pts):
    """截屏 → 采样点的众数色。"""
    from PIL import Image
    V.sh('shell', 'screencap', '-p', SHOT)
    local = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), '_page_theme_shot.png')
    V.sh('pull', SHOT, local)
    try:
        img = Image.open(local).convert('RGB')
        return dominant_color(img, pts)
    finally:
        try:
            os.remove(local)
        except OSError:
            pass


def measure(key, name):
    """打开该页并取底色；导航失败返回 None（并如实报红）。"""
    if not OPENER[key]():
        check('打开%s' % name, False, '（没进去，看导航文案是否变了）')
        return None
    c = page_bg(POINTS[key]())
    check('%s 底色取到' % name, c is not None, c)
    return c


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能改主题 pref')
        return 1

    theme0 = S.pref_in(UI_PREFS, K_THEME)
    theme_restore = theme0 if theme0 is not None else '-1'
    got = {}
    for theme, tag in (('0', '浅色'), ('1', '深色')):
        print()
        print('== %s下依次开三页取底色（ui_theme %s → %s）==' % (tag, theme0, theme))
        check('切%s写进 prefs' % tag, S.pref_set_in(UI_PREFS, K_THEME, theme))
        for key, name, _old in PAGES:
            got[(key, theme)] = measure(key, name)

    print()
    print('== 判据（★ 旧实现在 ②③④ 上必红）==')
    for key, name, old in PAGES:
        for nm, ok, detail in verdict(key, name, old,
                                      got.get((key, '0')), got.get((key, '1'))):
            check(nm, ok, detail)

    print()
    print('== 收尾：恢复原来的明暗 ==')
    S.pref_set_in(UI_PREFS, K_THEME, theme_restore)
    check('明暗已恢复', S.pref_in(UI_PREFS, K_THEME) == theme_restore,
          '%s=%s' % (K_THEME, S.pref_in(UI_PREFS, K_THEME)))
    restart_app()
    return finish()


def finish():
    try:
        V.sh('shell', 'rm', '-f', SHOT)
    except Exception:
        pass
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
    print('ALL PAGE-THEME DEVICE CHECKS PASSED（三页跟随明暗，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
