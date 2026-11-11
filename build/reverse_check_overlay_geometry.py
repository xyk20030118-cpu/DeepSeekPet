# -*- coding: utf-8 -*-
"""反向验证：悬浮窗几何断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 这条断言是空转。恢复放 finally，中途异常也不留破坏态。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
FLOATING = os.path.join(r'D:\petport_lab\petbuild\src\com\deepseek\pet', 'FloatingService.java')
VERIFY = os.path.join(HERE, 'verify_overlay_geometry.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def break_and_check(label, path, old, new):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-40s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-40s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：悬浮窗几何断言必须能抓到回潮 ===')
print()

# ① 桌宠本体漏设挖孔模式（用户报的那条）
results.append(break_and_check(
    'A. 桌宠窗口漏设挖孔模式',
    FLOATING,
    '        applyCutoutMode(params);\n',
    ''))

# ② 余额气泡漏设（浮层坐标相对桌宠，漏一个就错位）
results.append(break_and_check(
    'B. 余额气泡漏设挖孔模式',
    FLOATING,
    '            applyCutoutMode(balanceParams);   // 与桌宠同坐标系，否则横屏会错位\n',
    ''))

# ③ 改用 DEFAULT（等于没修，窗口照样被内缩）
results.append(break_and_check(
    'C. 退回 DEFAULT 模式',
    FLOATING,
    '.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;',
    '.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;'))

# ④ 去掉 SDK_INT 守卫（API 28 以下会崩）
#    ★ 锚点必须**唯一**：文件里别处也有 `SDK_INT >= 28`，
#      第一版只写那一行，破坏命中的是别的分支、断言当然还通过。
results.append(break_and_check(
    'D. 去掉 SDK_INT 守卫',
    FLOATING,
    '    private void applyCutoutMode(WindowManager.LayoutParams lp) {\n'
    '        if (lp == null) return;\n'
    '        if (Build.VERSION.SDK_INT >= 28) {',
    '    private void applyCutoutMode(WindowManager.LayoutParams lp) {\n'
    '        if (lp == null) return;\n'
    '        if (true) {'))

# ⑤ 旋转后不重算屏幕尺寸（横竖屏切换后位置悬空）
#    ★ 锚点要跟着代码走：轮次⑪ 起是**强制**刷新 `refreshRealSize(true)`
#      （屏幕尺寸加了 500ms 缓存，不强制的话缓存会把旋转吃掉）。
results.append(break_and_check(
    'E. 旋转后不重算屏幕尺寸',
    FLOATING,
    '        super.onConfigurationChanged(newConfig);\n        refreshRealSize(true);',
    '        super.onConfigurationChanged(newConfig);'))

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
