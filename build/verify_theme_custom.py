# -*- coding: utf-8 -*-
"""自定义玻璃配色 + UI 透明度（轮次㊾ · P0）。

用户要求：「我们现在做自定义主题颜色（玻璃态）可以自定义界面ui颜色 用来搭配不同的背景
增加ui透明度选项」。方案逐条拍板：**只做玻璃态 / 分组调 / 三项透明度 / 文字自动重推 /
暂不做导入导出**。

## 分节

  一、注入点唯一（挂在 Tokens.of(Context) 上，100+ 调用点一处不改）+ 只做玻璃态
  二、★ **零变化承诺**：没自定义时逐字段不变（有断言的"承诺"，不是口头的）
  三、透明度三项 + 各自下限（照 Tokens 注释里那条实测：卡片 α 低于 .70 文字层级会塌）
  四、★ 文字**自动重推**＝在两套已调好的配色间切换（不发明新颜色）+ accentInk 对比度兜底
  五、界面：只在玻璃态出现 + 预设色板 + 档位与引擎下限一致
  六、产物：新代码真的进了 dev 包
  七、实时预览卡（用当前配色画真实控件）
  八、配色方案导出 / 导入
  九、配色体检读数
  十、★ Mesh 光斑可调（浓度/扩散/跟随主色）+ 体检口径升级为"光斑极值"（轮次55）

设备侧实测见 `_theme_probe`（本轮临时探针，结论抄进 STATE）：
  无自定义 ⇒ 0 行 DSTheme；自定义底色 #C81E1E ⇒ 页面像素 R-B 位移 **+83**（往红移）、
  卡片 `surface=#80` (50%)、`文字=浅`（暗底自动转浅字）。
反向破坏见 reverse_check_theme_custom.py。
"""
import io
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
from artifacts import dev_apk  # noqa: E402
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


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def method_body(src, sig):
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(sig))
    return src[i:j + 6] if j > 0 else src[i:]


TOK = java('Tokens.java')
THM = java('ThemeOverride.java')
MA = java('MainActivity.java')
apply_body = method_body(THM, 'public static boolean apply(Tokens.Set t, Context c)')

print('=== 一、注入点唯一 + 只做玻璃态 ===')
check('★★ 覆盖挂在 Tokens.of(Context) 上（全工程唯一令牌入口）',
      'ThemeOverride.apply(t, c);' in TOK)
check('★★ Context-free 的 of(int,int) 保持纯函数（不套覆盖，离线断言才可信）',
      'ThemeOverride' not in method_body(TOK, 'static Set of(int skin, int theme)'))
check('★★ 只做玻璃态：非玻璃态直接返回（新拟态表面同色、透明度无意义）',
      apply_body.find('Tokens.skin(c) != Tokens.SKIN_GLASS') > 0
      and apply_body.find('return false') > 0)
check('★ MainActivity 的令牌缓存 key 含"自定义修订号"（否则改完颜色页面还在用旧的 Set）',
      'ThemeOverride.revision(' in method_body(MA, 'private Tokens.Set uiT()'))
check('★ 存盘按"引擎×明暗"分槽位（glass_light / glass_dark）',
      '"glass_dark"' in THM and '"glass_light"' in THM
      and 'DIR = "theme"' in THM)

print()
print('=== 二、★ 零变化承诺：没自定义时逐字段不变 ===')
check('★★ apply 里未自定义时**先返回**（v == null || v.isEmpty()）',
      'if (v == null || v.isEmpty()) return false;' in apply_body)
check('★ isEmpty() 把"只选了 textTone=auto"也当没自定义',
      'public boolean isEmpty()' in THM and '"auto".equals(textTone)' in THM)
check('★ 读坏了/文件不存在 ⇒ 当"没自定义"（绝不让界面花掉）',
      'return new V();' in method_body(THM, 'public static V load(Context c)'))
check('★ 坏色值返回 null（不因为一行脏数据让界面变色）',
      'public static Integer colorOrNull(String s)' in THM and 'return null;' in THM)

print()
print('=== 三、透明度三项 + 下限 ===')
check('★★ 卡片面下限 .50 / 弹窗面下限 .75（常量在，且 apply 里真的夹了）',
      'MIN_SURFACE = 0.50f' in THM and 'MIN_MODAL = 0.75f' in THM
      and 'clampA(v.alphaSurface, MIN_SURFACE, 1f)' in apply_body
      and 'clampA(v.alphaModal, MIN_MODAL, 1f)' in apply_body)
check('★ 底色透明度不限（0~100% 都允许）',
      'clampA(v.alphaBg, 0f, 1f)' in apply_body)
check('★★ 浓度只改 **alpha**，tint 的 RGB 保持内置（白 tint/深 tint 是实测定下来的）',
      't.surface = withAlpha(base.surface, aSurf);' in apply_body
      and 'withAlpha' in THM)
check('★ 高光/描边/凹陷面按同一个系数 k 跟着浓度走',
      'float k = (baseSurf <= 0f) ? 1f : (aSurf / baseSurf);' in apply_body
      and 'withAlpha(base.glassInner' in apply_body)

print()
print('=== 四、★ 文字自动重推 + accentInk 兜底 ===')
check('★★ 文字色来自**两套已调好的**内置配色（不发明新颜色）',
      'Tokens.Set tone = Tokens.of(Tokens.SKIN_GLASS,' in apply_body
      and 't.text1 = tone.text1;' in apply_body and 't.text2 = tone.text2;' in apply_body
      and 't.text3 = tone.text3;' in apply_body)
check('★ 自动档按**底色亮度**决定深字/浅字（阈值 0.45）',
      'preferDarkText(t.bg)' in apply_body and 'DARK_TEXT_MAX_LUM = 0.45' in THM
      and 'public static boolean preferDarkText(int bg)' in THM)
check('★★ accent 用用户选的，**只有小字 accentInk 做对比度兜底**',
      't.accent = accent;' in apply_body and 'ensureContrast(accent,' in apply_body)
check('★★ ensureContrast 两个方向都试、取更好的那个（艳底色单方向会白丢一档）',
      'for (int dir = 0; dir < 2; dir++)' in method_body(THM, 'public static int ensureContrast('))
check('★ 对比度用 WCAG 相对亮度（不是简单的灰度平均）',
      '0.2126 * r + 0.7152 * g + 0.0722 * b' in THM and 'public static double contrast(' in THM)

print()
print('=== 五、界面：只在玻璃态出现 + 色板/档位 ===')
check('★★ 两个入口只在玻璃态出现',
      'if (Tokens.skin(this) == Tokens.SKIN_GLASS) {' in MA
      and '自定义界面颜色…' in MA and 'UI 透明度…' in MA)
# ★★ 轮次㊿：用户要求「自定义颜色加一个调色卡 自己选颜色 而不是文字选项」——
#    上一版是 setItems 列出一串 `#1D5AC5` 文字，看不见颜色。现在必须是**看得见的调色卡**。
picker = method_body(MA, 'private void showColorPicker(final boolean isAccent)')
check('★★ 选色界面真的存在（showColorPicker）', bool(picker))
check('★★ 调色卡走 setView，**不再**用 setItems 列色值文字',
      'setView(root)' in picker and 'setItems(' not in picker)
check('★★ 色卡是**看得见的色块**（每格用实心圆角色块画出来）',
      'UiKit.solidFill(MainActivity.this, t, col, Tokens.R_CHIP)' in picker
      and 'SWATCHES' in MA and '6 列' not in picker)
check('★★ 能自己调任意颜色：HSV 三段滑块 + 实时预览（不是只有预设）',
      'android.graphics.Color.colorToHSV(start, hsv)' in picker
      and 'android.graphics.Color.HSVToColor(hsv)' in picker
      and 'hueBar.setMax(360)' in picker and 'satBar.setMax(100)' in picker
      and 'valBar.setMax(100)' in picker and '当前颜色' in picker)
check('★ 点色卡会把滑块同步过去（选中态一致：三条一起种，不是只改一条）',
      'seedHsvBars(hueBar, satBar, valBar, pick);' in picker
      and all(x in method_body(MA, 'private void seedHsvBars(')
              for x in ('h.setProgress(hh);', 's.setProgress(ss);', 'v.setProgress(vv);')))
check('★ 手动输入 HEX 降级成辅助入口（neutral 键，不再是主路径）',
      '"手动输入色值"' in picker and 'askHex(isAccent)' in picker)
# ★★ 轮次㊿ 收尾：两个**实测踩出来的坑**必须有守卫，否则下次重构就会悄悄退回去
check('★★ 色卡带无障碍描述（也是设备断言的唯一锚点：uiautomator 会剔除没有描述的纯 View）',
      'sw.setContentDescription("色卡 " + SWATCHES[idx]);' in picker)
check('★★ 种滑块必须走 seedHsvBars：setProgress 会**反向改写** hsv，'
      '直接用同一份 hsv 连种三条会自己冲掉自己（第一版症状：蓝色主色显示成"饱和 0%/明度 6%"）',
      'seedHsvBars(hueBar, satBar, valBar, hsv);' in picker
      and 'seedHsvBars(hueBar, satBar, valBar, pick);' in picker
      and 'private void seedHsvBars(' in MA
      and 'hsv[0] = h.getProgress();' in method_body(MA, 'private void seedHsvBars('))
# ★ 轮次52：这一条原来的写法（查 showCustomTheme 里有 d.dismiss()）**随重构失效了** ——
#   自定义页从"条目列表"变成"自绘弹窗"后，dismiss 纪律搬到了每个子弹窗里，
#   由上面那条"6 个子弹窗全查"覆盖（这里不再重复一条会被重构打穿的断言）。
check('★ 三条恢复路径都在（主色调/底色各自恢复 + 这一套全部恢复）',
      '主色调恢复默认' in MA and '底色恢复默认' in MA and '这一套全部恢复默认' in MA)
check('★★ 界面透明度档位的下限与引擎一致（卡片最低 50% / 弹窗最低 75%）',
      'A_SURFACE = { 1f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f }' in MA
      and 'A_MODAL = { 1f, 0.95f, 0.9f, 0.85f, 0.8f, 0.75f }' in MA)

# ─────────── 六、从背景图取色（P1 · 轮次51）───────────
print()
print('== 六、★ 从背景图取色（"搭配不同背景"最直接的那一项）==')
pal = method_body(THM, 'public static int[] paletteFrom(int[] px)')
check('★★ 取色是**纯函数**（只吃像素数组，不碰 Context/Bitmap）⇒ 可被真值验证',
      'public static int[] paletteFrom(int[] px)' in THM and bool(pal))
check('★★ 给四个候选：主色 / 平均色 / 亮色 / 暗色（不替用户拍板）',
      'out[0] = (cnt[best] > 0)' in pal and 'out[1] = Color.argb(255,' in pal
      and 'out[2] = endAverage(' in pal and 'out[3] = endAverage(' in pal)
check('★ 全透明像素不参与（PNG 透明区不该把颜色拉灰）',
      'if (Color.alpha(c) < 8) continue;' in pal)
check('★ 亮/暗两端用**亮度直方图**累计（O(n)，不排序、不卡 UI）',
      'int lb = (int) (luminance(c) * 63.0);' in pal
      and 'private static int endAverage(' in THM and 'bright ? (63 - i) : i' in THM)
check('★★ 读图先缩小（原图可能几千万像素；取色只要"大致什么颜色"）',
      'while (max / sample > 128) sample *= 2;' in THM and 'getPixels(' in THM)
check('★ 没有图片背景时不瞎猜：返回 null 并引导去设背景；视频背景**明确说不支持**',
      'public static int[] paletteFromBackgroundImage(Context c)' in THM
      and 'return null;' in method_body(THM, 'public static int[] paletteFromBackgroundImage(Context c)')
      and '视频暂不支持取色' in MA)
check('★★ 候选色也是**看得见的色块**（不是色值文字）—— 点一下即选',
      '背景图" + label + "\\u3000" + ThemeOverride.hex(col)' in MA
      and 'UiKit.solidFill(this, t, col, Tokens.R_CHIP), Tokens.R_CHIP, 0,' in MA
      and '从背景图取色（点一下即选）' in MA)
check('★★ 取到的颜色要问"用在哪"（背景底色 / 主色调），且映射正确',
      'askWhereToApply' in MA and '"背景底色", "主色调"' in MA
      and 'applyThemeColor(which == 1, hx);' in MA)
check('★ 入口加在自定义界面页里（不再多一个二级入口）',
      '从背景图取色…（搭配你现在的背景）' in MA)
check('★ 设备侧证据：覆盖生效时打一行 DSTheme（含最终取值与对比度）',
      '"DSTheme"' in THM and '自定义配色生效' in THM and 'accentInk对比=' in THM)

# ─────────── 七、实时预览卡 + dismiss 纪律（P1 · 轮次52）───────────
print()
print('== 七、★ 实时预览卡（用当前配色画真实控件）==')
page = method_body(MA, 'private void showCustomTheme()')
pv = method_body(MA, 'private View buildThemePreview(Tokens.Set t)')
check('★★ 自定义页改成**自绘弹窗**（顶部预览卡 + 操作行），不再是条目列表',
      'buildThemePreview' in page and 'setView(scroll)' in page and 'setItems(' not in page)
check('★★ 预览卡用**当前令牌**画真控件：玻璃卡片 + 三档文字 + 主/次按钮 + 状态色条',
      'UiKit.card(this, t, Tokens.R_CARD)' in pv
      and 't.text1' in pv and 't.text2' in pv and 't.text3' in pv
      and 'UiFactory.button(this, t, "主按钮", true)' in pv
      and 't.softAccent' in pv and 't.danger' in pv)
check('★★ 改完**立刻重开这一页** ⇒ 预览是"实时"的（不用退出去对照）',
      'showCustomTheme();' in method_body(MA, 'private void afterThemeCustomChanged()'))
# ★★ 正因为上面那条，**每条**改配色的路径都必须"先关自己再开下一个"，
#    否则新开的预览页会被上一个弹窗的自动 dismiss 连带关掉（**无异常日志**那种失败）
bad = []
# ★★ 按方法要求**各自的条数**（不是一律 ≥1）：调色卡有"确定 + 手动输入"两条出口，
#    只删掉其中一条时"≥1"照样绿 —— 反向套件 K4 当场抓到
for sig, need in (('private void pickAlpha(final int kind)', 1),
                  ('private void pickTextTone()', 1),
                  ('private void showAlphaPicker()', 1),
                  ('private void askHex(final boolean isAccent)', 1),
                  ('private void showColorPicker(final boolean isAccent)', 2),
                  # 背景取色：行回调 1 处；两个"没背景图/读不出"的早退弹窗后面不再开东西 ⇒ 不需要
                  ('private void pickFromBackgroundImage()', 1)):
    body = method_body(MA, sig)
    got = (body.count('d.dismiss();') + body.count('x.dismiss();')
           + body.count('bgHolder[0].dismiss();'))
    if got < need:
        bad.append('%s(%d<%d)' % (sig.split('(')[0].split()[-1], got, need))
check('★★ 改配色的每条路径都"先关自己再开下一个"（6 个子弹窗、各自条数都对）',
      not bad, bad)
# ★★ 轮次55 设备实测抓到的两个真问题（都写在这两条上）：
#    ① **这一页自己会叠层** —— "改完重开"时旧的那一层从没被 dismiss，
#       实测改一次要点**两次**「关闭」才回得到设置页；
#    ② 行数到 10 行后，最后两行与「关闭」按钮在 1080x2400 上被挤出可视区（点了没反应）。
check('★★ 重开这一页前先把**旧的这一层**关掉（否则每改一次叠一层：'
      '实测改一次要点两次「关闭」才回设置页）',
      'private android.app.Dialog customThemeDlg;' in MA
      and 'customThemeDlg.dismiss();' in page and 'customThemeDlg = null;' in page
      and 'customThemeDlg = UiFactory.dialog(this)' in page)
check('★★ 「✨ Mesh 光斑」那一层也要一起关（否则它压在新页上面，关掉新页才看见它）',
      'private android.app.Dialog meshPickerDlg;' in MA
      and 'meshPickerDlg.dismiss();' in page
      and 'meshPickerDlg = UiFactory.dialog(this)' in method_body(MA, 'private void showMeshPicker()'))
check('★★ 内容套 ScrollView（行数一多，最后几行/按钮会被挤出屏幕 ⇒ 有行点不到）',
      'ScrollView scroll = new ScrollView(this);' in page and 'scroll.addView(root);' in page)

# ─────────── 八、配色方案导出 / 导入（P1 · 轮次53）───────────
print()
print('== 八、★ 配色方案导出 / 导入（能像角色包那样分享）==')
check('★★ 导出的是**用户真正改过的项**（{@code toJson} 只写非 null 字段）'
      '⇒ 别人导入后继承他那边的内置配色，只覆盖你改过的部分',
      'public static String exportText(Context c)' in THM
      and 'return toJson(load(c)).toString();' in THM)
check('★★ 导入有**校验闸**：不是配色方案就直说（坏文件返回 null，绝不默默套一个空方案）',
      'public static V fromJsonText(String text)' in THM
      and 'return v.isEmpty() ? null : v;' in THM
      and 's.length() > 64 * 1024' in THM)
check('★★ 导入前把"里面是什么"摆给用户看，确认后才落盘（别人的文件，有权先看清）',
      'public static String describe(V v)' in THM
      and '导入这份配色？' in MA and '只会覆盖它写了的部分' in MA
      and 'ThemeOverride.save(MainActivity.this, v);' in MA)
check('★ 导出走 SAF 的 ACTION_CREATE_DOCUMENT（不要求任何存储权限）',
      'new Intent(Intent.ACTION_CREATE_DOCUMENT)' in MA and '"application/json"' in MA
      and 'ThemeOverride.suggestedFileName(this)' in MA)
check('★ 导入两条路：系统文件选择器 + **从电脑导入**'
      '（扫外部私有目录的 .json —— 与角色包同款，也让这条链路能被设备脚本验证）',
      'new Intent(Intent.ACTION_OPEN_DOCUMENT)' in MA
      and 'private void importThemeFromComputer()' in MA and '.endsWith(".json")' in MA)
check('★ onActivityResult 两条分支都在（导出写 Uri / 导入读 Uri 再走确认）',
      'requestCode == REQ_EXPORT_THEME' in MA and 'requestCode == REQ_IMPORT_THEME' in MA
      and 'ThemeOverride.exportText(this).getBytes("UTF-8")' in MA
      and 'confirmImportedTheme(text, "来自所选文件");' in MA)

# ─────────── 九、配色体检读数（P2 · 轮次54）───────────
print()
print('== 九、★ 配色体检读数（把"到底看不看得清"摆到用户眼前）==')
check('★★ 体检量的口径是"**卡片叠光斑极值**"（半透明卡片先与最暗/最亮那团光斑合成，'
      '再取更差的读数 —— 光斑能调浓之后，只量底色会给出**假绿**）',
      'public static int composite(int fg, int bg)' in THM
      and 'int[] ex = meshExtremes(t);' in THM
      and 'composite(t.surface, ex[0])' in THM and 'composite(t.surface, ex[1])' in THM)
check('★★ contrast 也先合成再算（半透明色不再被当成实色，读数才准）',
      'if (Color.alpha(b) < 255) ob = composite(b, oa);' in method_body(THM, 'public static double contrast(int a, int b)')
      and 'if (Color.alpha(a) < 255) oa = composite(a, ob);' in method_body(THM, 'public static double contrast(int a, int b)'))
check('★★ 逐条查：正文/次级/三级/小字强调（4.5:1）+ 主色/危险色（3:1）',
      'new Audit("正文"' in THM and 'new Audit("次级文字"' in THM
      and 'new Audit("三级提示 12sp"' in THM and 'new Audit("小字强调色"' in THM
      and 'new Audit("主色（按钮/大字号）"' in THM and 'new Audit("危险色"' in THM
      and 'public static final class Audit' in THM)
check('★ 阈值照 WCAG AA（正文 4.5 / 大字与 UI 部件 3.0）—— 换了口径写法后这条锚点也要跟着改',
      'new Audit("正文", worst(t.text1, underDark, underBright), 4.5)' in THM
      and 'new Audit("主色（按钮/大字号）", worst(t.accent, underDark, underBright), 3.0)' in THM)
check('★ 设置页那行直接显示读数（不用点进去才知道）',
      '配色体检：' in MA and 'ThemeOverride.auditSummary(t)' in MA)
check('★ 体检报告逐条列 + 不达标时给**可操作**的建议（不是只报错）',
      'public static String auditReport(Tokens.Set t)' in THM
      and '建议把卡片透明度调高' in THM and '文字颜色会自动跟着重推' in THM)

print()
print('== 十、★ Mesh 光斑可调（浓度 / 扩散 / 跟随主色）+ 体检口径升级为"光斑极值" ==')
mesh = method_body(THM, 'public static Tokens.Spot[] meshOf(Tokens.Spot[] base, V v, int accent)')
rt = method_body(THM, 'public static int retint(int spot, int accent)')
ex = method_body(THM, 'public static int[] meshExtremes(Tokens.Set t)')
aud = method_body(THM, 'public static java.util.List<Audit> audit(Tokens.Set t)')
check('★★ 三组档位都在，形态与「UI 透明度」同一套两级 setItems（不另发明一套交互）',
      'MESH_LEVELS = { 0f, 0.6f, 1f, 1.4f }' in MA
      and 'MESH_SCALES = { 0.6f, 1f, 1.4f }' in MA
      and 'private void showMeshPicker()' in MA and 'private void pickMesh(final int kind)' in MA
      and '"builtin", "accent"' in MA)
check('★★ 纯函数 meshOf：只动 α / 半径 / 颜色，**位置与光斑个数一个都不动**'
      '（逐光斑可调与"分组调、不暴露每个令牌"的边界冲突）',
      'public static Tokens.Spot[] meshOf(Tokens.Spot[] base, V v, int accent)' in THM
      and 'new Tokens.Spot(b.cx, b.cy, b.r * sc,' in mesh
      and bool(mesh))
check('★★ 三项都没设时**原样返回内置数组**（零变化承诺的 Mesh 那一半）',
      'if (s == 1f && sc == 1f && !follow) return base;' in mesh)
check('★★ 浓度只乘 **alpha**（tint 的 RGB 不动，与卡片浓度同一条纪律）+ 上限夹住',
      'withAlpha(col, clampA(alphaOf(col) * s, 0f, 1f))' in mesh
      and 'MAX_MESH = 1.5f' in THM and 'clampA(v.meshStrength, 0f, MAX_MESH)' in mesh
      and 'MIN_MESH_SCALE = 0.6f' in THM and 'MAX_MESH_SCALE = 1.4f' in THM)
check('★★ 跟随主色 = 取主色的**色相/饱和度**、留光斑自己的**明度**'
      '（把几团都染成同一个色号会让 Mesh 塌成一块平板）',
      'public static int retint(int spot, int accent)' in THM
      and 's[0] = a[0];' in rt and 's[1] = a[1];' in rt
      and 'android.graphics.Color.HSVToColor(android.graphics.Color.alpha(spot), s)' in rt)
check('★★ 注入点在 apply 里、且在 accent 之后（跟随主色要用最终生效的那个 accent）',
      't.mesh = meshOf(base.mesh, v, accent);' in apply_body
      and apply_body.find('t.mesh = meshOf(') > apply_body.find('int accent = (v.accent != null)'))
check('★★ isEmpty 把"没设 mesh"也算空（只设了内置色相 = 等于没设）',
      'meshStrength == null && meshScale == null' in THM
      and '"builtin".equals(meshTint)' in THM)
check('★★ 体检口径升级：最暗/最亮两处各算一遍、取**更差**的那个（深色字怕亮底、浅色字怕暗底）',
      'int[] ex = meshExtremes(t);' in aud
      and 'final int underDark = composite(t.surface, ex[0]);' in aud
      and 'final int underBright = composite(t.surface, ex[1]);' in aud
      and 'worst(t.text1, underDark, underBright)' in aud
      and 'public static double worst(int fg, int underDark, int underBright)' in THM)
check('★★ meshExtremes 与渲染层**同一套数学**：RadialGradient 的线性衰减 α·(1−d/r)',
      'alphaOf(s.color) * (1f - d / rad)' in ex and 'if (d >= rad) continue;' in ex
      and 'public static int[] meshExtremes(Tokens.Set t)' in THM)
check('★★ "取更差"的语义真的落在 worst() 里（Math.min 两处都算，不是只看最亮底）',
      'return Math.min(contrast(fg, underDark), contrast(fg, underBright));' in THM)
check('★ 设备侧证据：DSTheme 日志带 mesh 标签（界面文字只能证明界面，证明不了渲染）',
      '" mesh=" + meshTag(v)' in apply_body and 'public static String meshTag(V v)' in THM)
check('★ 预览卡垫在自己的 Mesh 背景上（否则浓度/扩散在预览里毫无反应）',
      'stage.setBackground(new UiKit.MeshDrawable(t));' in pv and 'stage.addView(card);' in pv)
check('★ 导出/导入带上 mesh（分享配色时别把光斑设置丢了）',
      'o.put(N_MESH, m);' in THM and 'JSONObject m = o.optJSONObject(N_MESH);' in THM
      and 'Mesh 光斑：浓度 ' in THM)
check('★ 恢复默认把三项一起清掉（留一项 = "恢复了但没全恢复"）',
      'nv.meshStrength = null; nv.meshScale = null; nv.meshTint = null;' in MA)
check('★ 自定义页那一行显示当前档位（例：`✨ Mesh 光斑：浓 · 跟随主色`）',
      '"\\u2728 Mesh 光斑：" + meshSummary(v)' in MA and 'private static String meshSummary(' in MA)

print()
print('=== 十一、设备脚本（可复跑，且只认模拟器）===')
CARD = os.path.join(HERE, 'verify_theme_card_ui.py')
check('verify_theme_card_ui.py 存在（调色卡，轮次㊿）', os.path.isfile(CARD))
if os.path.isfile(CARD):
    cs = read(CARD)
    check('★ 它钉死模拟器 + 自己驱动到调色卡（不靠手点）',
          'DEV = V.DEV' in cs and '自定义界面颜色' in cs and '主色调' in cs)
    check('★★ 它防"只看源码不看屏幕"：按**屏幕上的文字**判调色卡在不在',
          '色卡（点一下即选）' in cs and 'parse_card' in cs)
    check('★★ 它钉住两个实测坑（弹窗套弹窗 / setProgress 反写 hsv）',
          'dismiss' in cs and 'setProgress' in cs and '饱和 0%' in cs)
    rc, out = (lambda r: (r.returncode, r.stdout.decode('utf-8', 'replace')))(
        subprocess.run([sys.executable, CARD, '--selftest'], capture_output=True))
    check('verify_theme_card_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('=== 十二、产物：新代码真的进了 dev 包 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('ThemeOverride', 'DSTheme', '自定义配色生效', 'glass_light', 'glass_dark',
              '自定义界面颜色', 'UI 透明度', 'accentInk对比=', 'Mesh 光斑', 'mesh='):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（玻璃态自定义配色 + UI 透明度 + Mesh 光斑：引擎/下限/文字重推/界面）')
