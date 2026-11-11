# -*- coding: utf-8 -*-
"""反向验证：**玻璃态自定义配色 + UI 透明度**的断言必须能抓到回潮（轮次㊾）。

每一项都**故意破坏一次**，确认 `verify_theme_custom.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里（中途异常也不能把源码留在被破坏的状态）。

本轮特别值得盯的三条：
  * **A（注入点）** —— 摘掉它等于"自定义颜色完全没有生效"，而界面上一切照旧（最难发现）；
  * **C（零变化承诺）** —— 去掉"未自定义先返回"，默认态就可能被空覆盖动到；
  * **F（文字自动重推）** —— 不重推的话，用户把底色改深就会得到"深字叠深底"。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
VERIFY = os.path.join(HERE, 'verify_theme_custom.py')

TOK = os.path.join(SRC, 'Tokens.java')
THM = os.path.join(SRC, 'ThemeOverride.java')
MAIN = os.path.join(SRC, 'MainActivity.java')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-52s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-52s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('== 反向验证：玻璃态自定义配色 + UI 透明度 ==')
print()

# A. ★★ 注入点被摘（自定义颜色完全不生效，但界面一切照旧）
results.append(break_and_check(
    'A. ★★摘掉注入点（自定义完全不生效）',
    TOK,
    'try { ThemeOverride.apply(t, c); } catch (Throwable ignore) {}',
    ';'))

# B. ★★ Context-free 的 of(int,int) 也套了覆盖（离线断言/纯函数语义被破坏）
results.append(break_and_check(
    'B. ★★纯函数 of(int,int) 也套覆盖',
    TOK,
    '        else neumorphLight(t);\n        return t;',
    '        else neumorphLight(t);\n'
    '        ThemeOverride.apply(t, null);\n        return t;'))

# C. ★★ 零变化承诺被破（未自定义也往下走）
results.append(break_and_check(
    'C. ★★未自定义时不再提前返回（默认态可能被动到）',
    THM,
    'if (v == null || v.isEmpty()) return false;',
    'if (v == null) return false;'))

# D. ★★ 卡片下限被去掉（能调到 0%，文字层级必塌）
#    ★ 锚点必须**唯一**：`clampA(v.alphaSurface, MIN_SURFACE, 1f)` 在 toJson() 里也出现一次，
#      用裸串会 replace 到那边（第一版实测：破坏后仍绿 —— 因为 apply() 里那份没被改）。
results.append(break_and_check(
    'D. ★★卡片透明度下限被去掉',
    THM,
    '                    : clampA(v.alphaSurface, MIN_SURFACE, 1f);',
    '                    : v.alphaSurface;'))

# E. ★★ 浓度把 tint 的 RGB 也改了（白 tint/深 tint 是实测定下来的）
results.append(break_and_check(
    'E. ★★浓度改 alpha 时把 tint RGB 也换了',
    THM,
    't.surface = withAlpha(base.surface, aSurf);',
    't.surface = withAlpha(0xFFFFFFFF, aSurf);'))

# F. ★★ 文字不再自动重推（底色改深 ⇒ 深字叠深底）
results.append(break_and_check(
    'F. ★★文字不再自动重推',
    THM,
    't.text1 = tone.text1;',
    't.text1 = t.text1;'))

# G. ★★ accentInk 不再做对比度兜底（小字在自定义底色上可能不可读）
results.append(break_and_check(
    'G. ★★accentInk 不再兜底对比度',
    THM,
    't.accentInk = ensureContrast(accent, opaqueUnder(t), WANT_INK);',
    't.accentInk = accent;'))

# H. ★ 不再夹"只做玻璃态"（新拟态也被套上，界面会出怪效果）
results.append(break_and_check(
    'H. ★不再限制"只做玻璃态"',
    THM,
    'if (Tokens.skin(c) != Tokens.SKIN_GLASS) return false;',
    ';'))

# I. ★ 令牌缓存 key 不再含修订号（改完颜色页面还用旧的 Set）
results.append(break_and_check(
    'I. ★uiT() 缓存 key 不含自定义修订号',
    MAIN,
    'try { key = key * 1000000 + ThemeOverride.revision(this); } catch (Throwable ignore) {}',
    ';'))

# J. ★ 界面档位下限与引擎不一致（界面允许调到 30%）
results.append(break_and_check(
    'J. ★界面卡片档位允许调到 30%',
    MAIN,
    'A_SURFACE = { 1f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f }',
    'A_SURFACE = { 1f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.3f }'))

# K. ★★ 调色卡退回"一串色值文字"（用户明确否掉的那一版：
#    「自定义颜色加一个调色卡 自己选颜色 而不是文字选项」）
results.append(break_and_check(
    'K. ★★调色卡退回色值文字列表',
    MAIN,
    '                .setView(root)\n',
    '                .setItems(new String[0], null)\n'))

# K2. ★★ 色卡退回"没有无障碍描述"（设备断言再也数不出色卡；顺手丢掉无障碍）
results.append(break_and_check(
    'K2. ★★色卡不再带无障碍描述',
    MAIN,
    'sw.setContentDescription("色卡 " + SWATCHES[idx]);',
    ';'))

# K3. ★★ 种滑块退回"直接用同一份 hsv 连种三条"（第一版那个"饱和 0%/明度 6%"的 bug）
results.append(break_and_check(
    'K3. ★★种滑块退回自己冲掉自己的写法',
    MAIN,
    '        seedHsvBars(hueBar, satBar, valBar, hsv);\n',
    '        hueBar.setProgress(Math.round(hsv[0]));\n'))

# K4. ★★ 弹窗套弹窗不再先 dismiss（调色卡一闪即没、且没有异常日志）
results.append(break_and_check(
    'K4. ★★调色卡确定后不再先关自己',
    MAIN,
    '                        x.dismiss();   // 先关自己（x 就是本弹窗），否则下面重开的页会被连带关掉\n',
    ''))

# L. ★★ 取色只剩"平均色"（用户要的搭配自由度没了：亮/暗成对候选消失）
results.append(break_and_check(
    'L. ★★取色只剩平均色',
    THM,
    'out[2] = endAverage(lumCnt, lumR, lumG, lumB, want, true);',
    'out[2] = out[1];'))

# M. ★★ 取色不再跳过全透明像素（PNG 透明区会把颜色拉灰）
results.append(break_and_check(
    'M. ★★取色不再跳过全透明像素',
    THM,
    'if (Color.alpha(c) < 8) continue;',
    ';'))

# N. ★★ 取色后不再问"用在哪"（主色调会被当底色用）
results.append(break_and_check(
    'N. ★★取色后不再问"用在哪"',
    MAIN,
    'applyThemeColor(which == 1, hx);',
    'applyThemeColor(false, hx);'))

# P. ★★ 预览卡不再吃当前令牌（写死黑字 ⇒ 预览与真实界面脱节）
results.append(break_and_check(
    'P. ★★预览卡不再用当前令牌',
    MAIN,
    '        title.setTextColor(t.text1);',
    '        title.setTextColor(0xFF000000);'))

# Q. ★★ 改完不再重开这一页（预览不再"实时"）
results.append(break_and_check(
    'Q. ★★改完不再重开自定义页',
    MAIN,
    '        //   否则新开的这一页会被上一个弹窗的自动 dismiss 连带关掉。\n'
    '        showCustomTheme();\n',
    '        //   否则新开的这一页会被上一个弹窗的自动 dismiss 连带关掉。\n'))

# R. ★★ 文字颜色的子弹窗不再先 dismiss（新开的预览页会被连带关掉）
results.append(break_and_check(
    'R. ★★文字颜色弹窗不再先 dismiss',
    MAIN,
    'd.dismiss();\n'
    '                        ThemeOverride.V v = ThemeOverride.load(MainActivity.this);\n'
    '                        v.textTone = vals[which];',
    'ThemeOverride.V v = ThemeOverride.load(MainActivity.this);\n'
    '                        v.textTone = vals[which];'))

# S. ★★ 导出退化成"写空对象"（分享出去的是废文件）
results.append(break_and_check(
    'S. ★★导出退化成写空对象',
    THM,
    'return toJson(load(c)).toString();',
    'return "{}";'))

# T. ★★ 导入不再校验（坏文件也照套）
results.append(break_and_check(
    'T. ★★导入不再校验坏文件',
    THM,
    'return v.isEmpty() ? null : v;',
    'return v;'))

# U. ★★ 导入不再让用户确认（静默套用别人的配色）
results.append(break_and_check(
    'U. ★★导入不再让用户确认',
    MAIN,
    "                .setTitle(\"导入这份配色？\")",
    "                .setTitle(\"已导入\")"))

# V. ★★ 体检退回"只量底色"（光斑能调浓之后，这会让读数**假绿**：
#    调浓光斑、文字实际已经看不清，体检却还报达标）
results.append(break_and_check(
    'V. ★★体检退回只量底色（调浓光斑后假绿）',
    THM,
    'int[] ex = meshExtremes(t);',
    'int[] ex = { withAlpha(t.bg, 1f), withAlpha(t.bg, 1f) };'))

# W. ★★ 体检不再查"小字强调色"（最容易不达标的那一档被漏掉）
results.append(break_and_check(
    'W. ★★体检漏掉小字强调色',
    THM,
    'out.add(new Audit("小字强调色", worst(t.accentInk, underDark, underBright), 4.5));',
    ';'))

# ─────────── 轮次55：Mesh 光斑可调 ───────────

# X. ★★ 注入点被摘（浓度/扩散/跟随主色 全都不生效，但界面上一切照旧）
results.append(break_and_check(
    'X. ★★Mesh 覆盖不再注入令牌',
    THM,
    't.mesh = meshOf(base.mesh, v, accent);',
    't.mesh = base.mesh;'))

# Y. ★★ 跟随主色不生效（选了"跟随主色"却没反应）
results.append(break_and_check(
    'Y. ★★光斑色不跟随主色',
    THM,
    's[0] = a[0];',
    's[0] = s[0];'))

# Z. ★★ 扩散不生效（半径倍数被丢掉）
results.append(break_and_check(
    'Z. ★★光斑扩散不生效',
    THM,
    'new Tokens.Spot(b.cx, b.cy, b.r * sc,',
    'new Tokens.Spot(b.cx, b.cy, b.r,'))

# AA. ★★ 零变化承诺的 Mesh 那一半被破（三项都没设也重建一遍数组）
results.append(break_and_check(
    'AA. ★★三项没设时不再原样返回内置',
    THM,
    'if (s == 1f && sc == 1f && !follow) return base;',
    ';'))

# AB. ★★ 浓度不再只乘 alpha（把 tint 的 RGB 也换掉）
results.append(break_and_check(
    'AB. ★★浓度改 alpha 时把光斑色也换了',
    THM,
    'withAlpha(col, clampA(alphaOf(col) * s, 0f, 1f))',
    'withAlpha(0xFFFFFFFF, clampA(alphaOf(col) * s, 0f, 1f))'))

# AC. ★★ meshExtremes 不再按 RadialGradient 衰减叠加（每团都按满 α 叠 ⇒ 极值虚高）
results.append(break_and_check(
    'AC. ★★光斑极值不再按衰减叠加',
    THM,
    'alphaOf(s.color) * (1f - d / rad)',
    'alphaOf(s.color)'))

# AD. ★★ 体检不再取"更差"的那个（只量最亮底 ⇒ 浅色字叠暗光斑时漏判）
results.append(break_and_check(
    'AD. ★★体检不再取更差的读数',
    THM,
    'public static double worst(int fg, int underDark, int underBright) {\n'
    '        return Math.min(contrast(fg, underDark), contrast(fg, underBright));',
    'public static double worst(int fg, int underDark, int underBright) {\n'
    '        return contrast(fg, underBright);'))

# AE. ★ 预览卡不再垫 Mesh（光斑改动在预览里毫无反应）
results.append(break_and_check(
    'AE. ★预览卡不再垫 Mesh 背景',
    MAIN,
    'stage.setBackground(new UiKit.MeshDrawable(t));',
    ';'))

# AF. ★ 恢复默认漏掉 mesh（"恢复了但没全恢复"）
results.append(break_and_check(
    'AF. ★恢复默认漏掉 mesh',
    MAIN,
    'nv.meshStrength = null; nv.meshScale = null; nv.meshTint = null;',
    'nv.meshStrength = null;'))

# AG. ★ 导出丢掉 mesh（分享出去别人拿不到光斑设置）
results.append(break_and_check(
    'AG. ★导出丢掉 mesh',
    THM,
    'o.put(N_MESH, m);',
    ';'))

# AH. ★ DSTheme 日志不再报 mesh（设备侧失去唯一证据）
results.append(break_and_check(
    'AH. ★日志不再报 mesh 标签',
    THM,
    '" mesh=" + meshTag(v)',
    '""'))

# AI. ★★ 重开自定义页时不再关旧的这一层（每改一次叠一层：
#     实测改一次要点**两次**「关闭」才回得到设置页 —— 轮次55 设备脚本抓到的真 bug）
results.append(break_and_check(
    'AI. ★★重开前不再关旧的自定义页（叠层）',
    MAIN,
    'customThemeDlg.dismiss();',
    ';'))

# AJ. ★★ Mesh 那一层不再记引用（改完档位后它压在新页上面）
results.append(break_and_check(
    'AJ. ★★Mesh 那一层不再记引用',
    MAIN,
    'meshPickerDlg = UiFactory.dialog(this)',
    'UiFactory.dialog(this)'))

# AK. ★★ 内容不再套 ScrollView（行数一多，最后几行/按钮被挤出屏幕）
results.append(break_and_check(
    'AK. ★★内容不再套 ScrollView',
    MAIN,
    'scroll.addView(root);',
    ';'))

print()
print('=' * 62)
done = [r for r in results if r is not None]
skipped = len(results) - len(done)
caught = sum(1 for r in done if r)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (caught, len(done)))
if skipped:
    print('  跳过 %d 项（锚点未命中）' % skipped)
if caught != len(done) or skipped:
    sys.exit(1)
print('ALL REVERSE CHECKS PASSED')
