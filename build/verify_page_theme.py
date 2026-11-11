# -*- coding: utf-8 -*-
"""页面主题化断言：**最后一个旧观感页面（用量页）+ 静态气泡资源清退**（轮次㊶）。

## 为什么单开一套

用户 2026-10-11 的要求是「所有三级菜单都同步 app ui 跟随主题切换」——弹窗那层在轮次㊵ 做完了。
但同一族缺口还剩两处，都在**页面**这一层：

  ① `UsageActivity`（用量页）是**最后一个没接设计层**的页面：整页底色写死 `0xFFF5F8FC`、
     卡片用 `R.drawable.bubble_chat`（**静态 XML**：写死白→浅蓝渐变）⇒
     深色模式 / 玻璃皮肤下**整页还是白的**；
  ② 那批静态气泡 drawable 里，有 6 个**零引用**（`bubble_bg`/`bubble_menu`/`bubble_fin`/
     `bubble_dots`/`bubble_balance`/`bubble_tail`）—— 死重量，而且**会诱导下一个人"复用"**
     （用量页就是这么中招的：`// 复用统一气泡主题`）。

## 分节

  一、用量页接设计层：令牌 / `decoratePage` / `card` / `button` / 窗口上色 / 语义角色
  二、用量页**零硬编码颜色**（原来 24 处 `0xAARRGGBB`）
  三、静态气泡 drawable 已清退（res 里没有、Java 与 XML 都没有引用）
  四、死布局 `float_layout.xml` 已删（它曾是 `bubble_bg` 的最后一个引用）
  五、产物：新代码真的进了 dev 包
  六、设备脚本 `verify_page_theme_ui.py` 在、只认模拟器、`--selftest` 通过

轮次㊷ 追加（**收掉这条线上最后两页**：权限页 + 平台登录页）：

  七、权限页接设计层：`SettingsPage` 分组容器 / 令牌 / 语义角色 / 零硬编码颜色
  八、平台登录页接设计层：`decoratePage` / `card` / `button` / 零硬编码颜色
  九、★★ **结构性判据**：7 个面向用户的 Activity **活代码里零 `0xAARRGGBB` 字面量**
      （两个调试页 `Vp9ProbeActivity` / `FpsProbeActivity` 明示排除），
      且都调了 `applyPlatformTheme` 与 `Tokens.of`

设备侧实测（**深浅两种主题各截一次屏，比对页面底色**）见 `verify_page_theme_ui.py`；
反向破坏见 `reverse_check_page_theme.py`。
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
RES = os.path.join(ROOT, 'res')
DEV_APK = dev_apk()
# ★ 旧实现写死的浅色 —— 设备脚本与这里共用同一个值（"不许再出现它"）
OLD_HARDCODED_BG = 'f5f8fc'

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
    """去掉 Java 注释（注释里提到旧写法不该让断言误判）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def run(cmd):
    r = subprocess.run(cmd, cwd=HERE, capture_output=True)
    return (r.returncode,
            r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace'))


JAVA = {fn: read(os.path.join(SRC, fn)) for fn in sorted(os.listdir(SRC)) if fn.endswith('.java')}
CODE = {fn: code_only(s) for fn, s in JAVA.items()}
ua = CODE['UsageActivity.java']

print('=== 一、用量页接了设计层（最后一个旧观感页面）===')
check('★ 用 Tokens.of 取令牌（皮肤 + 明暗）', 'T = Tokens.of(this);' in ua)
check('★ 整页背景走 decoratePage（与设置页同一套 Mesh）',
      'UiFactory.decoratePage(box, this, T)' in ua)
check('★ 卡片走 UiFactory.card（新拟态浮雕面 / 玻璃面）', 'UiFactory.card(this, T)' in ua)
check('★ 按钮走 UiFactory.button（胶囊 + 令牌色）', 'UiFactory.button(this, T' in ua)
check('★ 分组标题走 sectionLabel', 'UiFactory.sectionLabel(this, T, title)' in ua)
check('★ 窗口自身也上色（否则深色下边缘露白）',
      'getWindow().setBackgroundDrawable(new ColorDrawable(T.bg))' in ua)
check('★★ 文字色走**语义角色**（换主题不用改 24 个调用点）',
      'private int colorOf(int role)' in ua and 'ROLE_DANGER' in ua
      and 'T.text1' in ua and 'T.text2' in ua and 'T.text3' in ua)

print()
print('=== 二、用量页零硬编码颜色（原来 24 处）===')
hexes = re.findall(r'0[xX][0-9a-fA-F]{8}', ua)
check('★★ 没有 0xAARRGGBB 硬编码色（含原来的 0xFFF5F8FC）', not hexes, hexes[:6])
check('★ 不再 setBackgroundColor / setBackgroundResource',
      'setBackgroundColor(' not in ua and 'setBackgroundResource(' not in ua)
check('★ 也不再 Color.WHITE / Color.BLACK 这类直给', 'Color.WHITE' not in ua and 'Color.BLACK' not in ua)

print()
print('=== 三、静态气泡 drawable 已清退 ===')
draw_dir = os.path.join(RES, 'drawable')
alive = sorted(f for f in os.listdir(draw_dir)) if os.path.isdir(draw_dir) else []
bubbles = [f for f in alive if f.startswith('bubble_')]
check('★★ res/drawable 里没有 bubble_*.xml（7 个全删）', not bubbles, bubbles)
# ★★ 关键是"不许再被引用"：光删文件不够，Java 或**布局 XML** 里留一句引用就编不过
#    （本轮就是这么被编译抓住的：res/layout/float_layout.xml 还在引 bubble_bg）
java_refs = [fn for fn, s in CODE.items() if 'R.drawable.bubble_' in s]
check('★★ Java 里没有 R.drawable.bubble_ 活引用（注释不算）', not java_refs, java_refs)
xml_refs = []
for dp, _dn, fn in os.walk(RES):
    for f in fn:
        if not f.endswith('.xml'):
            continue
        p = os.path.join(dp, f)
        s = read(p)
        if '@drawable/bubble_' in s:
            xml_refs.append(os.path.relpath(p, ROOT))
check('★★ 布局/资源 XML 里也没有 @drawable/bubble_ 引用', not xml_refs, xml_refs)

print()
print('=== 四、死布局 float_layout.xml 已删 ===')
dead_layout = os.path.join(RES, 'layout', 'float_layout.xml')
check('res/layout/float_layout.xml 已删（它曾是 bubble_bg 的最后一个引用）',
      not os.path.isfile(dead_layout))
check('★ 它本来就没有任何引用（浮窗是代码里搭的）',
      not [fn for fn, s in CODE.items() if 'R.layout.float_layout' in s])
layout_dir = os.path.join(RES, 'layout')
left = sorted(os.listdir(layout_dir)) if os.path.isdir(layout_dir) else []
check('res/layout 现在是空的（没有活布局）', not left, left)

print()
print('=== 五、产物：新代码真的进了 dev 包 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    # ★ 探针要挑**本轮独有**的字符串：旧包（轮次㊶ 之前）里必须找不到。
    for probe in ('登录一次就能读到官网的真实用量',
                  '数据来自 platform.deepseek.com（与官网口径一致）',
                  'colorOf',
                  # 轮次㊷：权限页 / 平台登录页的独有文案
                  '每一项都写明「为什么需要」和「不给会怎样」',
                  '④½ 后台自愈看门狗 ★最强保障',
                  '登录 DeepSeek 开放平台'):
        check('dex 含 %s' % probe, probe.encode('utf-8') in dex)
    # ★ 旧写死的浅色不该再以"页面底色"的形式出现；这里只保证它**不在** UsageActivity 的字符串里
    check('★ 旧的写死浅色没有以字符串形式回到代码里', 'F5F8FC' not in ua)

print()
print('=== 六、设备脚本（深浅各截一次屏比对底色的那一份）===')
ui = os.path.join(HERE, 'verify_page_theme_ui.py')
check('verify_page_theme_ui.py 存在', os.path.isfile(ui))
if os.path.isfile(ui):
    us = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          'DEV = V.DEV' in us and 'import verify_char_ui as V' in us)
    check('★ 判据是"深浅两色不同 + 不等于**该页**旧的写死色"（不是只看一次截图）',
          'def verdict(' in us and 'ui_theme' in us)
    # ★ 三页各有**自己**的旧写死色：用量页 0xFFF5F8FC / 权限页 0xFFF7F7FA / 登录页 白
    check('★ 三页都在（用量 / 权限 / 平台登录），且各带自己的旧写死色',
          all(k in us for k in ("'usage'", "'perm'", "'login'"))
          and '0xF5, 0xF8, 0xFC' in us and '0xF7, 0xF7, 0xFA' in us
          and '0xFF, 0xFF, 0xFF' in us)
    # ★ 版式不同 ⇒ 取色位置必须不同（登录页中间是 WebView，只采左右两条边）
    check('★ 三页各有一套采样点（左边缘 / 网格 / 左右两条边）',
          'def left_points(' in us and 'def grid_points(' in us and 'def edge_points(' in us)
    check('★ 登录页采左右两条边（否则会取到 WebView 的白）', 'w - 13' in us)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_page_theme_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

# ════════════════════════════════════════════════════════════════
# 轮次㊷：收掉"跟随主题"这条线上最后两页（权限页 + 平台登录页）
# ════════════════════════════════════════════════════════════════

pa = CODE['PermissionActivity.java']
pl = CODE['PlatformLoginActivity.java']

print()
print('=== 七、权限页接设计层（与设置页同一套分组结构）===')
check('★ 用 Tokens.of 取令牌（皮肤 + 明暗）', 'T = Tokens.of(this);' in pa)
check('★ 整页背景走 decoratePage（与设置页同一套 Mesh）',
      'UiFactory.decoratePage(page, this, T)' in pa)
check('★ 页容器是 SettingsPage（分组小标题 + 分组卡片，与设置页**同一套**）',
      'new SettingsPage(this, T)' in pa and 'page.beginGroup(' in pa)
check('★ 页头走 UiFactory.header', 'UiFactory.header(this, T' in pa)
check('★ 开关走 toggleRow（裸 CheckBox 触点只有 ~50×27dp，低于可达性下限）',
      'UiFactory.toggleRow(this, T' in pa)
check('★ 入口行走 actionRow', 'UiFactory.actionRow(this, T' in pa)
check('★ 状态行走**单色图标**（不再 ✅❌ 彩色 emoji）',
      'UiFactory.iconBadgeByName(this, T' in pa)
check('★ "手动路径"走 UiKit.sunken 凹槽（原来是 0x11000000 写死底）',
      'UiKit.sunken(this, T' in pa)
check('★ 主按钮走 UiFactory.button', 'UiFactory.button(this, T' in pa)
check('★ 窗口自身也上色（否则深色下边缘露白）',
      'getWindow().setBackgroundDrawable(new ColorDrawable(T.bg))' in pa)
check('★★ 文字色走**语义角色**（换主题不用改 10 个调用点）',
      'private int colorOf(int role)' in pa and 'ROLE_DANGER' in pa
      and 'T.text1' in pa and 'T.text2' in pa and 'T.text3' in pa)
pa_hexes = re.findall(r'0[xX][0-9a-fA-F]{8}', pa)
check('★★ 权限页活代码零硬编码色（原来 10 处文字色 + 底色 + 分隔线）',
      not pa_hexes, pa_hexes[:6])
check('★ 不再 setBackgroundColor / setBackgroundResource',
      'setBackgroundColor(' not in pa and 'setBackgroundResource(' not in pa)
# ★ 判据要管**两种写法**：`new CheckBox(` 与 `new android.widget.CheckBox(`。
#   第一版只查前者 —— 反向套件（J）当场抓到：把破坏写成后一种时断言**放行**了。
raw_cb = re.findall(r'new\s+(?:android\.widget\.)?CheckBox\s*\(', pa)
raw_btn = re.findall(r'new\s+(?:android\.widget\.)?Button\s*\(', pa)
check('★ 不再裸 CheckBox（两种写法都管）', not raw_cb, raw_cb[:3])
check('★ 不再裸 Button（两种写法都管）', not raw_btn, raw_btn[:3])
check('★ 也不再 Color.WHITE / Color.BLACK 这类直给',
      'Color.WHITE' not in pa and 'Color.BLACK' not in pa)

print()
print('=== 八、平台登录页接设计层（顶栏 + 状态条走卡片）===')
check('★ 用 Tokens.of 取令牌', 'T = Tokens.of(this);' in pl)
check('★ 整页背景走 decoratePage', 'UiFactory.decoratePage(root, this, T)' in pl)
check('★ 顶栏与状态条都走 UiFactory.card',
      pl.count('UiFactory.card(this, T)') >= 2,
      '实得 %d 处' % pl.count('UiFactory.card(this, T)'))
check('★ 关闭键走 UiFactory.button', 'UiFactory.button(this, T' in pl)
check('★ 窗口自身也上色', 'getWindow().setBackgroundDrawable(new ColorDrawable(T.bg))' in pl)
check('★ WebView 四周留页面边距（主题背景在边缘可见，也是设备取色的前提）',
      'wlp.setMargins(' in pl)
pl_hexes = re.findall(r'0[xX][0-9a-fA-F]{8}', pl)
check('★★ 登录页活代码零硬编码色（原来根布局白 + 顶栏 + 状态条 + 文字）',
      not pl_hexes, pl_hexes[:6])
check('★ 不再 setBackgroundColor / Color.WHITE',
      'setBackgroundColor(' not in pl and 'Color.WHITE' not in pl)

print()
print('=== 九、★★ 结构性判据：面向用户的 Activity 活代码里零硬编码颜色 ===')
# ★ 为什么是"结构性"：前八节钉的是**这两个文件**；这条钉的是**这一类**
#   —— 以后新增页面只要写死一个颜色，套件立刻报红，不依赖谁记得改断言。
# ★ 两个调试页（Vp9Probe / FpsProbe）明示排除：它们只在 adb 下手动拉起看画面，
#   不面向用户（见 STATE.md 的诚实边界）。
DEBUG_PAGES = ('Vp9ProbeActivity.java', 'FpsProbeActivity.java')
acts = sorted(fn for fn in CODE if fn.endswith('Activity.java') and fn not in DEBUG_PAGES)
# ★ 8 = 原来的 7 个 + DshActivity（轮次79 DSH 移植带的 harness 控制台）。
#   数量是**故意钉死**的：多一个面向用户的页面就要有人重新确认"它也得走令牌"，
#   而不是悄悄绕过去。新增页面时改这里 + 在反向套件里补一条破坏项。
check('★ 面向用户的 Activity 正好 8 个（两个调试页明示排除；第十七版删了自造工作台、多了 DshHarnessActivity）', len(acts) == 8, acts)
for fn in acts:
    hexes = re.findall(r'0[xX][0-9a-fA-F]{8}', CODE[fn])
    check('%-26s 活代码里零 0xAARRGGBB' % fn, not hexes, hexes[:4])
bad_theme = [fn for fn in acts if 'Tokens.applyPlatformTheme(this)' not in CODE[fn]]
check('★★ %d/%d 都调了 applyPlatformTheme（系统自绘控件跟随明暗）' % (len(acts), len(acts)),
      not bad_theme, bad_theme)
bad_tok = [fn for fn in acts if 'Tokens.of(' not in CODE[fn]]
check('★★ %d/%d 都从 Tokens.of 取令牌' % (len(acts), len(acts)), not bad_tok, bad_tok)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（用量页 + 权限页 + 平台登录页接设计层；静态气泡清退）')
