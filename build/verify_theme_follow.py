"""断言：三个对话相关窗口必须跟随 App 主题，不能有硬编码颜色。

★ 为什么必须守着（这就是用户报的问题）：
  这三个窗口原先是一整套写死的颜色（`Color.WHITE`、`#5B8DEF`、`#F0F0F3`），
  而且清单里挂的是系统主题 `@android:style/Theme.Light.NoTitleBar`。
  于是不管你选玻璃态/新拟态、浅色/深色，它们永远是同一个浅蓝样式。

  「跟随主题」这件事的脆弱点在于：**漏改一处就前功尽弃** ——
  只要有一个 widget 还写死白色，深色模式下那块就是刺眼的白。
  所以逐个文件检查"没有硬编码颜色"，而不是只看"有没有引用令牌"。

★ 检查方式：读真实源码文本，剥掉注释后统计硬编码颜色字面量。
  必须剥注释 —— 本工程的注释里大段引用了旧色值做说明
  （例如 HistoryActivity 的类注释就写了 `Color.WHITE`、`#5B8DEF`），
  不剥会把注释误判成代码。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'

# 三个"跟随主题"的目标窗口
TARGETS = ['HistoryActivity.java', 'ChatActivity.java', 'QuickAskActivity.java']

# 允许的例外：Color.TRANSPARENT（这是"不画颜色"，与主题无关）
ALLOW = ['Color.TRANSPARENT']


def strip_comments(s):
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s


def strip_strings(s):
    """把字符串字面量也去掉？

    不能去 —— 硬编码颜色正是**写在字符串里**的（Color.parseColor("#FFFFFF")）。
    所以这里刻意保留字符串，只剥注释。
    """
    return s


pass_n = 0
fail_n = 0


def ok(cond, what):
    global pass_n, fail_n
    if cond:
        pass_n += 1
    else:
        fail_n += 1
        print('  FAIL: %s' % what)


print('=== 三个对话窗口的主题跟随断言 ===')
print()
for name in TARGETS:
    path = os.path.join(SRC, name)
    s = strip_comments(io.open(path, encoding='utf-8-sig').read())
    print('[%s]' % name)

    # ① 不得有硬编码颜色字面量
    lits = re.findall(r'Color\.parseColor\("#[0-9A-Fa-f]{3,8}"\)', s)
    lits += re.findall(r'Color\.(WHITE|BLACK)\b', s)
    real = [x for x in lits if x not in ALLOW]
    ok(not real, '%s 不应有硬编码颜色（发现 %d 处: %s）'
       % (name, len(real), real[:4]))
    if not real:
        print('   硬编码颜色: 0 处 ✓')

    # ② 必须引用主题令牌
    toks = re.findall(r'\bT\.(bg|surface|surfaceSunken|accent|accentInk|'
                      r'accentSoft|text1|text2|text3|hairline|softDanger|'
                      r'dangerFill|danger|modal|edge)\b', s)
    ok(len(toks) >= 3, '%s 应引用足够多的主题令牌（实际 %d 处）'
       % (name, len(toks)))
    print('   主题令牌引用: %d 处 ✓' % len(toks))

    # ③ 必须初始化 T（否则 T 为 null，运行时 NPE）
    ok('Tokens.of(this)' in s, '%s 必须初始化 T = Tokens.of(this)' % name)

    # ④ 必须应用平台主题（让系统对话框也跟着明暗）
    ok('Tokens.applyPlatformTheme(this)' in s,
       '%s 必须调用 Tokens.applyPlatformTheme(this)' % name)
    print('   平台主题 + 令牌初始化 ✓')
    print()

print('=== 公共方法 ===')
tok = strip_comments(io.open(os.path.join(SRC, 'Tokens.java'),
                             encoding='utf-8-sig').read())
ok('static void applyPlatformTheme(android.app.Activity a)' in tok,
   'Tokens.applyPlatformTheme 存在（三个窗口共用一份实现）')
ok('setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)' in tok
   and 'setTheme(android.R.style.Theme_DeviceDefault_Light_NoActionBar)' in tok,
   '深浅两种 DeviceDefault 主题都选了')

print()
print('通过 %d 项' % pass_n)
if fail_n:
    print('失败 %d 项' % fail_n)
    sys.exit(1)
