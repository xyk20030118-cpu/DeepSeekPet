# -*- coding: utf-8 -*-
"""反向验证：贴边断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 这条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 本套件盯的是 2026-10-08 那个真 bug（"非吸附态贴不到边"）：
  ① 换动作把内容留白清成 null（异步再补）⇒ 松手贴边判定读到空值；
  ② 上传图片的居中留白没人算 ⇒ 图片形象 100% 贴不到边；
  ③ 漫游按"视口留白"算范围 ⇒ 走到边上还差 210px。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
PETVIEW = os.path.join(SRC, 'PetView.java')
FLOATING = os.path.join(SRC, 'FloatingService.java')
PURE = os.path.join(SRC, 'ContentInset.java')
VERIFY = os.path.join(HERE, 'verify_edge_snap.py')
FPSTEST = os.path.join(HERE, 'fpstest', 'run.bat')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def run_fpstest():
    return subprocess.run(['cmd', '/c', FPSTEST], capture_output=True,
                          cwd=os.path.dirname(FPSTEST)).returncode


def break_and_check(label, path, old, new, runner=None):
    runner = runner or run_verify
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-46s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = runner()
        write(path, orig)
        good = runner()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：贴边断言必须能抓到回潮 ===')
print()

# ① 根因回潮：换动作时把留白清空（旧写法），贴边判定又会读到空值
results.append(break_and_check(
    'A. 换动作把内容留白清成 null（旧 bug）',
    PETVIEW,
    '        ContentInsetProbe.Measured cached = pk.cachedClipInset(cid);\n'
    '        if (cached != null) {\n'
    '            packBmW = cached.bmW; packBmH = cached.bmH; packAlpha = cached.alpha;\n'
    '            return;\n'
    '        }',
    '        packBmW = 0; packBmH = 0; packAlpha = null;'))

# ② 异步算完直接覆盖（算不出来时会把已知留白清掉）
results.append(break_and_check(
    'B. 异步结果不 keep（算不出来就清空）',
    PETVIEW,
    '                        packAlpha = ContentInset.keep(packAlpha, m.alpha);',
    '                        packAlpha = m.alpha;'))

# ③ 上传图片不做"画布→视口"换算（方图那 138px 又丢了）
results.append(break_and_check(
    'C. 图片留白不做视口换算',
    PETVIEW,
    '        return ContentInset.of(bmW, bmH, alpha, vw, vh, '
    'getScaleType() == ScaleType.FIT_CENTER);',
    '        return alpha;'))

# ④ 上传图片那条路根本不量留白
results.append(break_and_check(
    'D. 静态图不量留白',
    FLOATING,
    '                petImage.clearImageInset();\n'
    '                probeImageInset(null, bm);',
    '                petImage.clearImageInset();'))

# ⑤ 边缘热区自己算一套（不再共用判据）
results.append(break_and_check(
    'E. 边缘热区自己算留白',
    FLOATING,
    '            int[] rx = edgeRangeX();\n'
    '            int minX = rx[0];\n'
    '            int maxX = rx[1];\n'
    '            int curX = params != null ? params.x : 0;',
    '            int[] rx = ContentInset.xRange(screenW(), petWinWidth(), insetOrZero());\n'
    '            int minX = rx[0];\n'
    '            int maxX = rx[1];\n'
    '            int curX = params != null ? params.x : 0;'))

# ⑥ 漫游回到"视口留白"那套算法（★ 轮次㊸：这段逻辑搬进了 roamOnce，缩进 16→8 空格）
results.append(break_and_check(
    'F. 漫游按视口留白算范围（旧写法）',
    FLOATING,
    '        int[] rx = edgeRangeX();\n'
    '        int minX = rx[0];\n'
    '        int maxX = rx[1];\n'
    '        int cur = params.x;',
    '        int sideGap = 0;\n'
    '        int minX = -sideGap;\n'
    '                int maxX = Math.max(minX, 1080 - 630 + sideGap);\n'
    '                int cur = params.x;'))

# ⑦ 纯类语义退回"窗口贴屏幕边"（等于没修）
results.append(break_and_check(
    'G. xRange 退回"窗口贴屏幕边"',
    PURE,
    '        int minX = -inL;',
    '        int minX = 0;'))

# ⑧ 纯类 keep 失效（null 会覆盖已知留白）
results.append(break_and_check(
    'H. ContentInset.keep 直接返回新值',
    PURE,
    '        return usable(next) ? next : prev;',
    '        return next;',
    runner=run_fpstest))

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
