# -*- coding: utf-8 -*-
"""反向验证：**三级菜单主题化**的断言必须能抓到回潮。

一个"永远返回 OK"的断言和"真的在守护"的断言，从输出上看一模一样。
所以每一项都**故意破坏一次**，确认 `verify_dialog_theme.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里：中途异常也不能把源码留在被破坏的状态。

★ 本套件特别值得盯的是**前两条**：那两条判据（"业务代码里零原生写法"、"弹窗套色只有一处实现"）
是**结构性**的 —— 回潮方式恰恰是"新写一个弹窗时随手 new 一个 AlertDialog.Builder"，
而设备上要发现它得把 94 个弹窗逐个点开。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
VERIFY = os.path.join(HERE, 'verify_dialog_theme.py')

MAIN = os.path.join(SRC, 'MainActivity.java')
UF = os.path.join(SRC, 'UiFactory.java')
TOK = os.path.join(SRC, 'Tokens.java')
CHAT = os.path.join(SRC, 'ChatActivity.java')


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
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []

print('=== 反向验证：三级菜单（对话框）主题化 ===')
print()

# ① ★★ 新写一个弹窗时又用原生写法（结构性判据存在的理由）
results.append(break_and_check(
    'A. ★★又冒出一个原生 AlertDialog.Builder',
    MAIN,
    'UiFactory.dialog(this)\n                .setTitle(title)',
    'new AlertDialog.Builder(this)\n                .setTitle(title)'))

# ② ★ 进度框又用原生写法
results.append(break_and_check(
    'B. ★进度框又用原生 new ProgressDialog',
    MAIN,
    'ProgressDialog pd = UiFactory.progress(this);',
    'ProgressDialog pd = new ProgressDialog(this);'))

# ③ ★★ progress() 内部改回短名（迁移脚本会把这一行换成自我递归）
results.append(break_and_check(
    'C. ★★progress() 内部改回短名（自我递归）',
    UF,
    'android.app.ProgressDialog pd = new android.app.ProgressDialog(c);',
    'ProgressDialog pd = new ProgressDialog(c);'))

# ④ ★★ ThemedBuilder 不再接管 create()（8 处调用点直接用 .create()）
results.append(break_and_check(
    'D. ★★ThemedBuilder 不再接管 create()',
    UF,
    '        @Override public AlertDialog create() {\n'
    '            AlertDialog d = super.create();\n'
    '            themeDialog(d, ctx, items, choice);\n'
    '            return d;\n'
    '        }',
    '        @Override public AlertDialog create() {\n'
    '            return super.create();\n'
    '        }'))

# ⑤ ★ 面板不再换成令牌 drawable
results.append(break_and_check(
    'E. ★面板不再换成令牌 drawable（还是系统 9-patch）',
    UF,
    '                w.setBackgroundDrawable(new InsetDrawable(',
    '                if (false) w.setBackgroundDrawable(new InsetDrawable('))

# ⑥ ★ 标题/按钮不再染色（★ 轮次㊸ 改了写法：标题先取到变量再染，按钮改走 styleDialogButton）
results.append(break_and_check(
    'F. ★标题不再染色',
    UF,
    'tint(titleV, t.text1, true);',
    ';'))
results.append(break_and_check(
    'G. ★按钮不再套样式（胶囊）',
    UF,
    'b.setBackground(pressable(c, t, Tokens.R_PILL, primary));\n'
    '        } catch (Throwable ignore) {}',
    '        } catch (Throwable ignore) {}'))

# ⑦ ★ 写死不公开的 id（编译不过的写法 —— 说明"必须用 getIdentifier"）
results.append(break_and_check(
    'H. ★alertTitle 改成写死 android.R.id.alertTitle',
    UF,
    'd.findViewById(androidId(c, "alertTitle"))',
    'd.findViewById(android.R.id.alertTitle)'))

# ⑧ ★★ 令牌不再"每次取"（写死一套 ⇒ 换主题后弹窗还是旧皮肤）
#    ★ 第一版破坏写成 `t = t;`（行为没变）⇒ 断言当然绿：**破坏本身要真的成立**才算验证
results.append(break_and_check(
    'I. ★★themeDialog 把令牌写死（换主题不生效）',
    UF,
    '            Tokens.Set t = Tokens.of(c);',
    '            Tokens.Set t = Tokens.of(Tokens.SKIN_NEUMORPH, Tokens.THEME_LIGHT);'))

# ⑨ ★ 某个 Activity 不再换系统主题（深色下那页的确认框又是白的）
results.append(break_and_check(
    'J. ★ChatActivity 不再 applyPlatformTheme',
    CHAT,
    '        Tokens.applyPlatformTheme(this);',
    '        ;'))


# ⑩ ★ 换主题挪到 setContentView 之后（setTheme 失效）
#    ★ 这一条要**两步**（先摘掉顶部那次、再插到 setContentView 之后），
#      单次 replace 表达不了 ⇒ 单独写一个破坏函数
def break_order(label, path, anchor_cv):
    orig = read(path)
    call = '        Tokens.applyPlatformTheme(this);\n'
    if call not in orig or anchor_cv not in orig:
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        s = orig.replace(call, '', 1)
        s = s.replace(anchor_cv, anchor_cv + call, 1)
        write(path, s)
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results.append(break_order(
    'K. ★applyPlatformTheme 挪到 setContentView 之后',
    os.path.join(SRC, 'UsageActivity.java'),
    '        setContentView(scroll);\n'))

# ⑪ ★★ 老的那套弹窗套色又回来（两处实现会打架：onShow 比 create() 晚）
results.append(break_and_check(
    'L. ★★老的 applyDialogSkin 又回来（会盖掉新面板）',
    MAIN,
    '    /**\n     * 危险操作二次确认对话框。',
    '    private void applyDialogSkin(AlertDialog dlg, Tokens.Set T) { }\n\n'
    '    /**\n     * 危险操作二次确认对话框。'))

# ════════════════════════════════════════════════════════════════
# 轮次㊸：**同步 UI**（不只是颜色）—— 行 / 按钮 / 首帧补齐 / 危险键守卫
# ════════════════════════════════════════════════════════════════

# ⑫ ★★ 列表行退回系统适配器（贴边纯文字行又回来了）
results.append(break_and_check(
    'M. ★★列表行退回系统适配器（不再换行样式）',
    UF,
    'lv.setAdapter(new DialogRowAdapter(c, t, items, choice, lv));',
    ';'))

# ⑬ ★★ 按钮不再套我们的胶囊（退回系统扁平按钮）
results.append(break_and_check(
    'N. ★★按钮不再套胶囊（退回系统扁平按钮）',
    UF,
    '            b.setBackground(pressable(c, t, Tokens.R_PILL, primary));\n'
    '        } catch (Throwable ignore) {}',
    '        } catch (Throwable ignore) {}'))

# ⑭ ★★ 首帧之前补齐按钮样式的那条路没了
#    （Android 36 实测：create() 时按钮还没建出来 ⇒ 少了它等于按钮样式没套）
results.append(break_and_check(
    'O. ★★去掉首帧补齐（create() 时按钮还不存在）',
    UF,
    'addOnPreDrawListener',
    'addOnPreDrawListenerX'))

# ⑮ ★★ 去掉"别覆盖调用点有意改过的背景"的守卫（危险键的实心红会被胶囊盖掉）
results.append(break_and_check(
    'P. ★★去掉危险键守卫（红键被胶囊覆盖）',
    UF,
    'if (bg != null && bg.getClass().getSimpleName().contains("Gradient")) return;',
    ';'))

# ════════════════════════════════════════════════════════════════
# 轮次㊹：**行样式必须在"首帧之前"补**（create() 时那套不是最终显示的）+
#         每行都要有图标 + 两行式文案
# ════════════════════════════════════════════════════════════════

# ⑯ ★★ 首帧之前不再补行样式 —— 这正是"三级菜单 UI 没变"的真凶
results.append(break_and_check(
    'Q. ★★首帧之前不再补行样式（UI 没变那个 bug）',
    UF,
    'String ad = styleDialogRows(fd, cc, tt, items, choice);',
    'String ad = "none";'))

# ⑰ ★★ 徽章又退回"只在文案以 emoji 开头时才画"
results.append(break_and_check(
    'R. ★★徽章退回只在有 emoji 时才画',
    UF,
    'row.addView(iconBadgeByName(ctx, t, iconName, glyph));',
    'if (glyph != null) row.addView(iconBadge(ctx, t, glyph));'))

# ⑱ ★★ 关键词兜底图标被删（服务商那种纯文字选项又只剩空底片）
results.append(break_and_check(
    'S. ★★没有 emoji 的行不再兜底给图标',
    UF,
    'if (iconName == null) iconName = dialogRowIcon(label);',
    ';'))

# ⑲ ★★ 两行式文案退回一整段（"名字 + \n + 说明"又挤成一行）
results.append(break_and_check(
    'T. ★★两行式文案退回一整段',
    UF,
    "int nl = label.indexOf('\\n');",
    'int nl = -1;'))

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
