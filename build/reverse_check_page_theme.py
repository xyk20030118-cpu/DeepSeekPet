# -*- coding: utf-8 -*-
"""反向验证：**页面主题化（用量页 + 权限页 + 平台登录页 + 静态气泡清退）**的断言必须能抓到回潮。

一个"永远返回 OK"的断言和"真的在守护"的断言，从输出上看一模一样。
所以每一项都**故意破坏一次**，确认 `verify_page_theme.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里：中途异常也不能把源码留在被破坏的状态。

★ 本轮（轮次㊷）特别值得盯的是**最后四条**：
  * L / M 打的是**结构性判据**（第九节）—— 破坏点故意落在**别的**文件上
    （MainActivity 里加一个写死颜色、ChatActivity 不再 applyPlatformTheme），
    用来证明那条判据管的是"这一类"而不是"这两个文件"；
  * N / O 打的是平台登录页（WebView 页）的两条最基本要求。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
RES = r'D:\petport_lab\petbuild\res'
VERIFY = os.path.join(HERE, 'verify_page_theme.py')

UA = os.path.join(SRC, 'UsageActivity.java')
MAIN = os.path.join(SRC, 'MainActivity.java')


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


def break_new_file(label, path, content, cleanup):
    """造一个新文件来破坏（比如"静态气泡 drawable 又回来了"）。

    ★ 每次都要确保父目录在：上一个破坏的 cleanup 会把**空目录**也收掉
      （`res/layout` 现在本来就该是空的），否则下一个破坏会 FileNotFoundError。
    """
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
            f.write(content)
        bad = run_verify()
        cleanup()
        good = run_verify()
    finally:
        cleanup()
    ok = (bad == 1 and good == 0)
    print('  [%s] %-46s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []

print('=== 反向验证：页面主题化（用量页 + 静态气泡清退）===')
print()

# ① ★★ 用量页又出现写死的颜色（结构性判据存在的理由）
results.append(break_and_check(
    'A. ★★用量页又出现写死颜色 0xFFF5F8FC',
    UA,
    '        getWindow().setBackgroundDrawable(new ColorDrawable(T.bg));',
    '        getWindow().setBackgroundDrawable(new ColorDrawable(0xFFF5F8FC));'))

# ② ★ 用量页不再走 decoratePage（整页又变系统底色）
results.append(break_and_check(
    'B. ★用量页不再 decoratePage',
    UA,
    'UiFactory.decoratePage(box, this, T);',
    ';'))

# ③ ★ 卡片又退回静态 drawable 写法
results.append(break_and_check(
    'C. ★卡片又用 setBackgroundResource（退回静态图）',
    UA,
    '        LinearLayout c = UiFactory.card(this, T);',
    '        LinearLayout c = new LinearLayout(this);\n'
    '        c.setBackgroundResource(android.R.drawable.dialog_frame);'))

# ④ ★ 文字色不再走语义角色（又逐个传颜色）
results.append(break_and_check(
    'D. ★文字色不再走语义角色',
    UA,
    '    private int colorOf(int role) {',
    '    private int colorOf(int roleUnused) {\n'
    '        if (true) return 0xFF1B3A6B;'))

# ⑤ ★★ 静态气泡 drawable 又回来了（文件 + 引用）
BUB = os.path.join(RES, 'drawable', 'bubble_bg.xml')
results.append(break_new_file(
    'E. ★★静态气泡 drawable 又回来了',
    BUB,
    '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <solid android:color="#FFFFFF" />\n'
    '</shape>\n',
    lambda: os.path.isfile(BUB) and os.remove(BUB)))

# ⑥ ★★ 布局 XML 里又引用静态气泡（只删 drawable 不够 —— 本轮真实翻车点）
LAY = os.path.join(RES, 'layout')
os.makedirs(LAY, exist_ok=True)
FAKE = os.path.join(LAY, '_r41_probe_layout.xml')


def _cleanup_layout():
    if os.path.isfile(FAKE):
        os.remove(FAKE)
    try:
        if not os.listdir(LAY):
            os.rmdir(LAY)
    except OSError:
        pass


results.append(break_new_file(
    'F. ★★布局 XML 里又引用 @drawable/bubble_chat',
    FAKE,
    '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:layout_width="wrap_content" android:layout_height="wrap_content"\n'
    '    android:background="@drawable/bubble_chat" />\n',
    _cleanup_layout))

# ⑦ ★ 死布局又回来（它曾是 bubble_bg 的最后一个引用）
results.append(break_new_file(
    'G. ★死布局 float_layout.xml 又回来',
    os.path.join(LAY, 'float_layout.xml'),
    '<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"\n'
    '    android:layout_width="wrap_content" android:layout_height="wrap_content">\n'
    '    <TextView android:layout_width="wrap_content"\n'
    '        android:layout_height="wrap_content"\n'
    '        android:background="@drawable/bubble_bg" />\n'
    '</LinearLayout>\n',
    lambda: os.path.isfile(os.path.join(LAY, 'float_layout.xml'))
    and os.remove(os.path.join(LAY, 'float_layout.xml'))))

# ════════════════════════════════════════════════════════════════
# 轮次㊷：最后两页（权限页 + 平台登录页）+ **结构性判据**
# ════════════════════════════════════════════════════════════════

PA = os.path.join(SRC, 'PermissionActivity.java')
PL = os.path.join(SRC, 'PlatformLoginActivity.java')
CHAT = os.path.join(SRC, 'ChatActivity.java')

# ⑧ ★★ 权限页又出现写死颜色
results.append(break_and_check(
    'H. ★★权限页又出现写死颜色 0xFF444444',
    PA,
    '        tv.setTextColor(colorOf(role));',
    '        tv.setTextColor(0xFF444444);'))

# ⑨ ★ 权限页不再走 decoratePage（整页又变系统底色）
results.append(break_and_check(
    'I. ★权限页不再 decoratePage',
    PA,
    'UiFactory.decoratePage(page, this, T);',
    ';'))

# ⑩ ★ 权限页开关退回裸 CheckBox（可达性回潮）
results.append(break_and_check(
    'J. ★权限页开关退回裸 CheckBox',
    PA,
    'page.addView(UiFactory.toggleRow(this, T, "开启后台自愈看门狗", kaOn,',
    'page.addView(new android.widget.CheckBox(this),\n'
    '                UiFactory.toggleRow(this, T, "开启后台自愈看门狗", kaOn,'))

# ⑪ ★ 权限页文字色不再走语义角色
results.append(break_and_check(
    'K. ★权限页文字色不再走语义角色',
    PA,
    '    private int colorOf(int role) {',
    '    private int colorOf(int roleUnused) {\n'
    '        if (true) return 0xFF444444;'))

# ⑫ ★★ 结构性判据：给**别的** Activity 加一个写死颜色（活代码里）
results.append(break_and_check(
    'L. ★★MainActivity 里新增写死颜色（结构性判据）',
    MAIN,
    '        Tokens.applyPlatformTheme(this);',
    '        Tokens.applyPlatformTheme(this);\n'
    '        int _r42ProbeColor = 0xFF123456;'))

# ⑬ ★★ 结构性判据：某个 Activity 不再调 applyPlatformTheme
results.append(break_and_check(
    'M. ★★ChatActivity 不再 applyPlatformTheme',
    CHAT,
    'Tokens.applyPlatformTheme(this);',
    ';'))

# ⑭ ★ 平台登录页又写死白色
results.append(break_and_check(
    'N. ★登录页又写死 Color.WHITE',
    PL,
    '        UiFactory.decoratePage(root, this, T);',
    '        root.setBackgroundColor(Color.WHITE);'))

# ⑮ ★ 平台登录页不再走 decoratePage
results.append(break_and_check(
    'O. ★登录页不再 decoratePage',
    PL,
    'UiFactory.decoratePage(root, this, T);',
    ';'))

# ⑯ ★★ 结构性判据（轮次79 新增）：**再冒出来一个新的面向用户页面**，而且它写死颜色。
#   为什么要这一条：轮次79 把断言里的"正好 7 个 Activity"改成了 8 个（多了 DshActivity），
#   改数字最容易改出"假绿" —— 如果判据只剩数量、或只剩"盯这两个文件"，新页面就能绕过去。
#   这一条同时压两件事：① 数量被钉死（9 个 ⇒ 红）；② 新页面里写死 0xAARRGGBB ⇒ 红。
PROBE = os.path.join(SRC, '_R79ProbeActivity.java')
results.append(break_new_file(
    'P. ★★ 新冒出来的 Activity 写死颜色（数量 + 零硬编码）',
    PROBE,
    'package com.deepseek.pet;\n\n'
    'public class _R79ProbeActivity extends android.app.Activity {\n'
    '    int probe() { return 0xFF123456; }\n'
    '}\n',
    lambda: os.path.isfile(PROBE) and os.remove(PROBE)))

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
