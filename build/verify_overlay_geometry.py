# -*- coding: utf-8 -*-
"""悬浮窗几何断言 —— 横屏挖孔区（用户报的横屏 bug）。

钉的语义（不是"存在性"）：
  · **每一个** TYPE_APPLICATION_OVERLAY 窗口都必须设 layoutInDisplayCutoutMode
    —— 漏一个就会出现"这个浮层贴边对了、那个错位"的诡异现象，
      因为它们的坐标都相对桌宠窗口算。
  · 必须设成 ALWAYS（默认 DEFAULT 会让窗口被挖孔内缩）
  · 必须有 SDK_INT 守卫（ALWAYS 是 API 28+）

背景（真机现象）：
  横屏时左边一条黑边、右边一块不可见触摸区；桌宠贴右会"陷进去"、
  贴左贴不到最左边。根因是窗口被挖孔内缩 144px，而 screenW() 走
  getRealMetrics() 返回含挖孔的整屏宽 —— 两套坐标差一条挖孔宽。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
FLOATING = os.path.join(SRC, 'FloatingService.java')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


src = io.open(FLOATING, encoding='utf-8', errors='replace').read()


def code_only(s):
    out, i, n, blk = [], 0, len(s), False
    while i < n:
        if blk:
            j = s.find('*/', i)
            if j < 0:
                break
            i = j + 2
            blk = False
            continue
        if s.startswith('/*', i):
            blk = True
            i += 2
            continue
        if s.startswith('//', i):
            j = s.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        if s[i] == '"':
            out.append(s[i]); i += 1
            while i < n and s[i] != '"':
                if s[i] == '\\':
                    out.append(s[i]); i += 1
                if i < n:
                    out.append(s[i]); i += 1
            if i < n:
                out.append(s[i]); i += 1
            continue
        out.append(s[i]); i += 1
    return ''.join(out)


c = code_only(src)

print('=== 1. 每个悬浮窗都必须设挖孔模式 ===')
# 找出所有 TYPE_APPLICATION_OVERLAY 的 LayoutParams 变量名
vars_ = set(re.findall(r'(\w+)\s*=\s*new WindowManager\.LayoutParams\(', c))
overlay_vars = set()
for v in vars_:
    m = re.search(re.escape(v) + r'\s*=\s*new WindowManager\.LayoutParams\((.*?)\);', c, re.S)
    if m and 'TYPE_APPLICATION_OVERLAY' in m.group(1):
        overlay_vars.add(v)
print('    悬浮窗变量: %s' % sorted(overlay_vars))
check('识别到多个悬浮窗', len(overlay_vars) >= 5, '共 %d 个' % len(overlay_vars))

missing = []
for v in sorted(overlay_vars):
    if ('applyCutoutMode(%s)' % v) not in c:
        missing.append(v)
check('每个悬浮窗都调了 applyCutoutMode', not missing,
      ('漏了: %s' % missing) if missing else '共 %d 个全覆盖' % len(overlay_vars))

print()
print('=== 2. 必须是 ALWAYS（DEFAULT 会被挖孔内缩）===')
check('用 LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS',
      'LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS' in c)
check('没有误用 DEFAULT / NEVER',
      'LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT' not in c
      and 'LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER' not in c)

print()
print('=== 3. SDK_INT 守卫（ALWAYS 是 API 28+）===')
m = re.search(r'private void applyCutoutMode\(WindowManager\.LayoutParams lp\)\s*\{(.*?)\n    \}',
              c, re.S)
check('applyCutoutMode 方法存在', bool(m))
if m:
    body = m.group(1)
    check('有 SDK_INT >= 28 守卫', 'SDK_INT >= 28' in body)
    check('设的是 layoutInDisplayCutoutMode', 'layoutInDisplayCutoutMode' in body)
    check('有 try/catch 兜底', 'catch' in body)

print()
print('=== 4. 贴边/钳制用的屏幕宽必须与窗口坐标系一致 ===')
# 语义：screenW() 来自 getRealMetrics（含挖孔），所以窗口也必须允许覆盖挖孔
check('screenW 来自 getRealMetrics', 'getRealMetrics' in c)
check('clampToScreen 用 screenW()', bool(re.search(
    r'private void clampToScreen\(\).*?int screenW = screenW\(\);', c, re.S)))
check('dockEdgeX 用 screenW()', bool(re.search(
    r'private int dockEdgeX\(.*?\).*?int sw = screenW\(\);', c, re.S)))
# 旋转后要重算（否则横竖屏切换后位置悬空）
# ★ 必须在**方法体内**检查：第一版正则写成
#   `onConfigurationChanged.*?refreshRealSize\(\)`（DOTALL+非贪婪），
#   它会一路匹配到文件后面任意一处 refreshRealSize —— 断言形同虚设。
m2 = re.search(r'onConfigurationChanged\(android\.content\.res\.Configuration newConfig\)'
               r'\s*\{(.{0,400})', c, re.S)
# ★ 轮次⑪：屏幕尺寸加了 500ms 缓存（拖动每帧不再查 Display），所以旋转这里
#   必须走**强制**刷新 `refreshRealSize(true)` —— 断言跟着改成"强制刷新"，
#   而不是原来的"任意无参调用"（那样会把"缓存把旋转吃掉"放过去）。
check('onConfigurationChanged 里强制刷新真实屏幕尺寸',
      bool(m2) and 'refreshRealSize(true)' in m2.group(1),
      ('方法体开头: ' + m2.group(1).strip().replace('\n', ' ')[:60]) if m2 else '没匹配到')

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED')
