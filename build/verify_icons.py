# -*- coding: utf-8 -*-
"""设置页图标覆盖审计（轮次78 起）：**任何一行行首 emoji 都必须能解析出矢量图标**。

为什么需要它：`addButton/addToggle` 的图标是从行首 emoji 经 `VectorIcon.fromEmoji` 推出来的，
查不到就**空着不画**（`UiFactory.iconBadgeByName` 的既定行为：绝不退回彩色 emoji）。
于是"新加一行设置忘了登记图标"不会报错，只会静默少一个图标 —— 用户 2026-10-21 就是
看到「贴边形态 / 挂边角度 / 挂出深度」三行没有（或只有通用）图标才提的。

判据：
  ① 设置页每个 emoji 行首都能在 `fromEmoji` 里查到；
  ② 查到的名字必须在 `pathFor` 里有对应路径（否则会悄悄回退成 CHAT）；
  ③ 本轮新增的 10 枚图标必须同时在 `prototype.html` 的 `I{}` 里登记（否则重新生成会丢）。

跑法：python build/verify_icons.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "com", "deepseek", "pet")
MA = os.path.join(SRC, "MainActivity.java")
VI = os.path.join(SRC, "VectorIcon.java")
PROTO = r"D:\用户\DS\pet-ui-redesign\prototype.html"

NEW_ICONS = ["PEEK_STYLE", "PEEK_ANGLE", "PEEK_DEPTH", "OPACITY", "MUSIC",
             "SPEAKER", "WALK", "BARRIER", "LIST", "FLASK"]

fails = []
total = 0


def ok(name, cond, detail=""):
    global total
    total += 1
    if not cond:
        fails.append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + ((" :: " + detail) if detail else ""))


def glyph_key(raw):
    m = re.match(r"\\u([0-9A-Fa-f]{4})(\\u([0-9A-Fa-f]{4}))?", raw)
    if not m:
        return None
    hi = int(m.group(1), 16)
    if m.group(2) and 0xD800 <= hi <= 0xDBFF:
        return ("\\u%s\\u%s" % (m.group(1), m.group(3))).upper()
    if 0xD800 <= hi <= 0xDFFF:
        return None
    return ("\\u" + m.group(1)).upper()


def main():
    vi = io.open(VI, encoding="utf-8").read()
    ma = io.open(MA, encoding="utf-8").read()

    emoji_to_name = {}
    for m in re.finditer(r'if \(e\.equals\("((?:\\u[0-9A-Fa-f]{4})*)"\)\) return "(\w+)"', vi):
        if m.group(1):
            emoji_to_name[m.group(1).upper()] = m.group(2)
    names = set(re.findall(r'if \(n\.equals\("(\w+)"\)\) return', vi))

    print("[1] 映射表规模")
    ok("★ emoji → 图标名 至少 109 条", len(emoji_to_name) >= 109, str(len(emoji_to_name)))
    ok("★ 图标名表 至少 53 个", len(names) >= 53, str(len(names)))

    print("[2] 本轮新增的 10 枚图标齐全（且 pathFor 认）")
    for n in NEW_ICONS:
        ok("  %s：常量 + pathFor 都在" % n,
           ("static final String %s =" % n) in vi and n in names)

    print("[3] 设置页每一行都能解析出图标（这是用户 2026-10-21 报障的那类问题）")
    calls = re.findall(r'add(?:Button|Toggle)\(\s*[\w.]+\s*,\s*"((?:[^"\\]|\\.)*)"', ma)
    miss_by_glyph = {}
    unresolved = []
    for raw in calls:
        k = glyph_key(raw)
        if not k:
            continue
        nm = emoji_to_name.get(k)
        if nm is None:
            miss_by_glyph.setdefault(k, []).append(raw[:34])
        elif nm not in names:
            unresolved.append((k, nm))
    ok("★★★ 没有「缺图标」的设置行", not miss_by_glyph,
       "; ".join("%s x%d" % (k, len(v)) for k, v in miss_by_glyph.items()))
    ok("★★ 没有「名字在但路径缺失」的（会静默回退成 CHAT）", not unresolved,
       str(unresolved[:4]))

    print("[4] prototype.html 已同步登记（重新生成时不会丢）")
    if os.path.exists(PROTO):
        h = io.open(PROTO, encoding="utf-8", errors="replace").read()
        ok("★★ 10 枚新图标都登记在 I{} 里",
           all(n.lower().replace("_", "") in h for n in NEW_ICONS))
    else:
        ok("★ prototype.html 存在", False, PROTO)

    print()
    print("=" * 58)
    if fails:
        print("%d / %d 项失败" % (len(fails), total))
        for f in fails:
            print("  - " + f)
        sys.exit(1)
    print("ALL ICON CHECKS PASSED (%d 项)" % total)


if __name__ == "__main__":
    main()
