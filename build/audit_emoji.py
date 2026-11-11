"""审计：MainActivity 里所有「行首 emoji」是否都能在 VectorIcon 里查到映射。

背景：UiFactory 把行首 emoji 抽出来画成**单色矢量图标**；查不到就只画一个
空的凹槽底片 —— 屏幕上是个「白色小方块」（tofu）。
这类问题编译不报错、功能也正常，只有盯着截图才会发现
（已经踩了两次：SCISSORS U+2702 和 MOVIE-CAMERA U+1F3A5，都不在映射表里）。

用法: python build/audit_emoji.py
"""
import io
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
MAIN = r'D:\petport_lab\petbuild\src\com\deepseek\pet\MainActivity.java'
VEC = r'D:\petport_lab\petbuild\src\com\deepseek\pet\VectorIcon.java'

main = io.open(MAIN, encoding='utf-8').read()
vec = io.open(VEC, encoding='utf-8').read()

# 行首 emoji 在源码里是 "\uXXXX ..." 或 "\uXXXX\uXXXX ..." 的转义形式
pat = re.compile(r'"((?:\\u[0-9A-Fa-f]{4}){1,2})\s')
found = {}
for m in pat.finditer(main):
    esc = m.group(1)
    found.setdefault(esc, main.count('\n', 0, m.start()) + 1)


def mapped(esc):
    """按 VectorIcon.fromEmoji 的**真实行为**判断。

    ★ 第一版忘了这一步，于是 U+2699 U+FE0F 被判成「未映射」——
      而 fromEmoji 会先去掉 U+FE0F（变体选择符）与 U+200D（零宽连接）
      再查表，所以 U+2699 才是真正的查表键。
      不照实现写检查，会报一堆假失败。
    """
    e = esc.replace('\\uFE0F', '').replace('\\u200D', '').strip()
    return (not e) or (e in vec)


# 这些字符**不是行首徽章**，只是按钮文案里的"已选中/未选中"标记
# （● U+25CF / ○ U+25CB），不走 VectorIcon 查表，所以允许未映射。
ALLOW = {'\\u25CF', '\\u25CB'}

print('共 %d 个不同的行首 emoji 转义' % len(found))
miss = []
for esc, line in sorted(found.items(), key=lambda kv: kv[1]):
    ok = mapped(esc) or esc in ALLOW
    if not ok:
        miss.append((esc, line))
    mark = ('已映射' if mapped(esc)
            else ('跳过（选项标记，非徽章）' if esc in ALLOW else '!! 未映射'))
    print('  L%-5d %-22s %s' % (line, esc, mark))

print()
if miss:
    print('!! 未映射（会在界面上显示为空白方块）：')
    for esc, line in miss:
        print('    L%d  %s' % (line, esc))
    sys.exit(1)
print('OK 全部已映射')