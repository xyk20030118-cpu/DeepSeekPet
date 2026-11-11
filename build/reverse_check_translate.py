"""反向验证：纯平移断言必须能抓到"翻页感回潮"。

破坏项（都是真实会发生的改动）：
  A. pageSlide 加回缩放（0.94）→ 就是翻页感
  B. pageSlide 加回淡入（alpha 0→1）
  C. 返回跟随加回缩放插值
  D. MIN_SCALE 改回 0.94
  E. MAX_SHIFT 写死数值（不再引用单一来源）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SCRIPT = r'D:\petport_lab\petbuild\build\verify_predictive_source.py'


def run():
    r = subprocess.run([sys.executable, SCRIPT], capture_output=True)
    return r.returncode, r.stdout.decode('utf-8', 'replace')


def trial(name, path, old, new, unique=True):
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        s = io.open(path, encoding='utf-8-sig').read()
        n = s.count(old)
        if n == 0:
            print('  !! %-46s 找不到片段，跳过' % name)
            return None
        if unique and n > 1:
            print('  !! %-46s 片段出现 %d 次，跳过' % (name, n))
            return None
        io.open(path, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, out = run()
        caught = (rc != 0)
        print('  %s %-46s 退出码=%d' % ('✓' if caught else '✗', name, rc))
        if not caught:
            print('       （没抓到 → 这条断言是空转的）')
        return caught
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：纯平移断言必须能抓到翻页感回潮 ===')
print()
UF = os.path.join(SRC, 'UiFactory.java')
MA = os.path.join(SRC, 'MainActivity.java')
results = []

# A. pageSlide 加回缩放
results.append(trial(
    'A. pageSlide 加回缩放（翻页感）',
    UF,
    '        v.setScaleX(1f);\n        v.setScaleY(1f);',
    '        v.setScaleX(0.94f);\n        v.setScaleY(0.94f);'))

# B. pageSlide 加回淡入
results.append(trial(
    'B. pageSlide 加回淡入',
    UF,
    '        v.setAlpha(1f);\n        v.setTranslationX(from);',
    '        v.setAlpha(0f);\n        v.setTranslationX(from);'))

# C. 返回跟随加回缩放插值
results.append(trial(
    'C. 返回跟随加回缩放插值',
    MA,
    '        backAnimView.setScaleX(BackApi34.MIN_SCALE);\n'
    '        backAnimView.setScaleY(BackApi34.MIN_SCALE);',
    '        float s = 1f - progress * 0.06f;\n'
    '        backAnimView.setScaleX(s);\n'
    '        backAnimView.setScaleY(s);'))

# D. MIN_SCALE 改回 0.94
results.append(trial(
    'D. MIN_SCALE 改回 0.94',
    MA,
    'static final float MIN_SCALE = 1f;',
    'static final float MIN_SCALE = 0.94f;'))

# E. MAX_SHIFT 写死数值（脱离单一来源）
results.append(trial(
    'E. MAX_SHIFT 写死 1/3 屏宽',
    MA,
    'static final float MAX_SHIFT = UiFactory.PAGE_SLIDE_FRACTION;',
    'static final float MAX_SHIFT = 1f / 3f;'))

rc, out = run()
print()
print('  恢复后退出码 = %d' % rc)
if rc != 0:
    for line in out.splitlines():
        if 'FAIL' in line:
            print('     ' + line.strip()[:100])

good = [r for r in results if r is not None]
print()
print('  %d/%d 个破坏被断言抓到' % (sum(1 for r in good if r), len(good)))
sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
