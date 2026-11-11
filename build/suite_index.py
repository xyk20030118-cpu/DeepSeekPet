# -*- coding: utf-8 -*-
"""全量套件（build/run_all_tests.bat）编号自洽性 —— 唯一判据。

★ 为什么单独一个模块：这条判据原先在四个脚本里各写了一份，
  而且写的是**死数字**（`'/73' in bat`）。轮次79 把套件从 73 加到 76，
  四处同时变红 —— 那就是"手抄的常量会静默过期"的又一例。
  现在语义只有一处：所有标题的 n/N 必须同分母、末条序号 == 总数、总数不少于下限。

用法：
    from suite_index import consistent
    ok, total = consistent(open('run_all_tests.bat', encoding='ascii').read())
"""
import re

_HEAD = re.compile(r'=+ (\d+)/(\d+) ')


def consistent(bat_text, minimum=73):
    """返回 (是否自洽, 总数)。找不到任何标题时返回 (False, 0)。"""
    if not bat_text:
        return False, 0
    hdrs = [(int(a), int(b)) for a, b in _HEAD.findall(bat_text)]
    if not hdrs:
        return False, 0
    total = hdrs[0][1]
    ok = (all(b == total for _a, b in hdrs)
          and hdrs[-1][0] == total
          and total >= minimum)
    return ok, total
