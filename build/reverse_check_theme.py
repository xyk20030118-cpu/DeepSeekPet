"""反向验证：故意把硬编码颜色加回去，确认断言抓得到。

★ 为什么单独写一个：主题跟随的脆弱点是"漏改一处就前功尽弃"，
  而反向验证恰能证明这条断言不是空转。
  本工程已经栽过一次"空转断言"（见 reverse_check_api.py 的说明），
  所以新增断言必须验。

破坏项：
  A. HistoryActivity 写回一个白色 → 硬编码颜色断言该失败
  B. ChatActivity 删掉 applyPlatformTheme → 平台主题断言该失败
  C. QuickAskActivity 删掉 Tokens.of(this) → 令牌初始化断言该失败
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SCRIPT = r'D:\petport_lab\petbuild\build\verify_theme_follow.py'


def run():
    r = subprocess.run([sys.executable, SCRIPT], capture_output=True)
    return r.returncode, r.stdout.decode('utf-8', 'replace')


def trial(name, fname, old, new):
    path = os.path.join(SRC, fname)
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        s = io.open(path, encoding='utf-8-sig').read()
        n = s.count(old)
        if n == 0:
            # ★ 跳过 = 没验证，必须算失败（旧版返回 None，
            #   收尾只统计非 None 的项，于是"跳过了"会显示成"全抓到"）
            print('  ✗ %-44s 找不到片段 → 未验证（视为失败）' % name)
            return False
        if n > 1:
            print('  ✗ %-44s 片段出现 %d 次 → 未验证（视为失败）' % (name, n))
            return False
        io.open(path, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, out = run()
        caught = (rc != 0)
        print('  %s %-44s 退出码=%d' % ('✓' if caught else '✗', name, rc))
        if not caught:
            print('       （断言没抓到 → 这条是空转的）')
        return caught
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：主题跟随断言必须能抓到回潮 ===')
print()
results = []

# A. 把硬编码颜色写回去（复现用户最初报的问题）
#    ★ 片段随重构更新过：HistoryActivity 整页背景现在走
#      UiFactory.decoratePage，旧的 card.setBackgroundColor(T.bg) 已不存在。
#      改用气泡文字色作为破坏点 —— 硬编码颜色同样会被断言 [1] 抓到。
results.append(trial(
    'A. History 写回硬编码颜色',
    'HistoryActivity.java',
    'tv.setTextColor(T.text1);',
    'tv.setTextColor(Color.WHITE);'))

# B. 删掉平台主题应用（系统对话框会变白）
results.append(trial(
    'B. Chat 删掉 applyPlatformTheme',
    'ChatActivity.java',
    '        Tokens.applyPlatformTheme(this);\n', ''))

# C. 删掉令牌初始化（T 为 null → 运行时 NPE）
results.append(trial(
    'C. QuickAsk 删掉 Tokens.of(this)',
    'QuickAskActivity.java',
    '        T = Tokens.of(this);\n', ''))

rc, out = run()
print()
print('  恢复后：退出码 %d' % rc)
if rc != 0:
    for line in out.splitlines():
        if 'FAIL' in line:
            print('     ' + line.strip()[:100])

good = [r for r in results if r is not None]
print()
print('  %d/%d 个破坏被断言抓到（跳过按未验证计）'
      % (sum(1 for r in results if r), len(results)))
sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
