# -*- coding: utf-8 -*-
"""安全地跑全量套件：跑前快照 src/，跑后对比；被中途打断导致源码被改坏时自动恢复。

为什么需要它（真实事故）：
    反向验证（reverse_check_*.py）的工作方式是"把源码改坏 → 确认断言变红 → 改回来"。
    恢复写在 finally 里，但**进程被强杀时 finally 不会执行** —— 于是源码留在一个被改坏的
    状态（实测：`as.bindsOf(scene)` 被替换成 `new java.util.ArrayList<...>()`，
    导致"添加动作会把整个场景的绑定抹掉"这个真 bug 写进了包里）。

用法：
    python build/run_tests_safe.py            # 跑 run_all_tests.bat，日志写 lab_all_tests_safe.log
    python build/run_tests_safe.py 我的日志.log
"""
import filecmp
import io
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, 'src')
SNAP = os.path.join(ROOT, 'build', '.src_snapshot')
BAT = os.path.join(HERE, 'run_all_tests.bat')


def snapshot():
    if os.path.isdir(SNAP):
        shutil.rmtree(SNAP, ignore_errors=True)
    shutil.copytree(SRC, SNAP)


def diff_files():
    """返回 (新增/改动, 丢失) 的文件相对路径列表。"""
    changed, missing = [], []
    for dirpath, _dirs, files in os.walk(SNAP):
        for f in files:
            sp = os.path.join(dirpath, f)
            rel = os.path.relpath(sp, SNAP)
            tp = os.path.join(SRC, rel)
            if not os.path.isfile(tp):
                missing.append(rel)
            elif not filecmp.cmp(sp, tp, shallow=False):
                changed.append(rel)
    for dirpath, _dirs, files in os.walk(SRC):
        for f in files:
            tp = os.path.join(dirpath, f)
            rel = os.path.relpath(tp, SRC)
            if not os.path.isfile(os.path.join(SNAP, rel)):
                changed.append(rel)
    return changed, missing


def restore(rel_list):
    for rel in rel_list:
        sp = os.path.join(SNAP, rel)
        tp = os.path.join(SRC, rel)
        if os.path.isfile(sp):
            os.makedirs(os.path.dirname(tp), exist_ok=True)
            shutil.copy2(sp, tp)


def main(argv):
    log = argv[1] if len(argv) > 1 else 'lab_all_tests_safe.log'
    snapshot()
    print('[safe] 已快照 src/ ->', SNAP)
    rc = subprocess.call(['cmd', '/c', os.path.basename(BAT) + ' > ' + log + ' 2>&1'], cwd=HERE)
    changed, missing = diff_files()
    if changed or missing:
        print('[safe] !! 套件跑完后源码与快照不一致（多半是被中途打断）：')
        for rel in changed:
            print('       改动:', rel)
        for rel in missing:
            print('       丢失:', rel)
        restore(changed + missing)
        print('[safe] 已按快照恢复，请**重跑一次**再信结果。')
        return 2
    print('[safe] 源码与快照一致（跑套件没有留下破坏），退出码 =', rc)
    return rc


if __name__ == '__main__':
    sys.exit(main(sys.argv))
