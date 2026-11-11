"""反向验证：故意破坏源码，确认断言真的能抓到（证明断言不是空转）。

★ 为什么必须做这一步：
  一个"永远通过"的断言毫无价值。我在这个工程里已经吃过一次亏 ——
  写过一条 A/B 测试，两种配置结果完全一样，后来才发现是测试本身
  根本没生效（sed 静默失败）。
  所以新增断言后，必须确认它**在错误代码上会失败**。

破坏项：
  A. 去掉 temperature 的能力判断      → 源码断言该失败
  B. 把"非 DeepSeek 早退"前移条件改坏 → 该失败
  C. 让非 DeepSeek 分支也显示命中率   → 该失败
"""
import io
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
API = os.path.join(SRC, 'ApiClient.java')
FS = os.path.join(SRC, 'FloatingService.java')
MC = os.path.join(SRC, 'MainActivity.java')
BN = os.path.join(SRC, 'BackNav.java')


def run_assertions():
    """跑**全部**断言（源码级 + cpidtest 离线套件）。

    ★ 这里修过一个盲区：原实现只跑 verify_api_source.py，
      于是针对 BackNav 的破坏（G/H/I）**全都没抓到** ——
      因为 BackNav 的断言在 cpidtest/BackNavTest.java 里，
      根本不在这个函数跑的范围内。
      表现为"3 个破坏全部漏抓"，看起来像断言空转，
      实际是**反向验证的范围没覆盖到那些断言**。

      现在两处都跑：任何一套失败都算"抓到了"。
    """
    r1 = subprocess.run([sys.executable, r'D:\petport_lab\petbuild\build\verify_api_source.py'],
                        capture_output=True)
    out1 = r1.stdout.decode('utf-8', 'replace')

    r2 = subprocess.run(['cmd', '/c', 'run.bat'],
                        cwd=r'D:\petport_lab\petbuild\build\cpidtest',
                        capture_output=True)
    out2 = (r2.stdout + r2.stderr).decode('utf-8', 'replace')

    out = out1 + '\n' + out2
    failed = (r1.returncode != 0) or (r2.returncode != 0) or ('FAIL' in out)
    m = re.search(r'通过 (\d+) 项', out1)
    ok_n = int(m.group(1)) if m else 0
    fail_n = 1 if failed else 0
    return (1 if failed else 0), ok_n, fail_n, out


def trial(name, path, old, new, unique=True):
    """改一处 → 跑断言 → 恢复。

    ★ `unique=True` 时会先确认 old 在文件里**只出现一次**。
      这是被一个假失败逼出来的：我给 F 用的破坏片段
      （`.setNegativeButton("取消", null)\\n.show();\\n}`）
      在 MainActivity 里出现了 **23 次** —— `replace(...,1)` 改到了
      `showBaseUrlDialog`（第一个），目标方法根本没动，
      于是断言"没抓到"，看起来像断言空转。
      **真正的问题在测试脚本，不在断言。**
      测错的破坏比不测更误导。
    """
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        s = io.open(path, encoding='utf-8-sig').read()
        n = s.count(old)
        if n == 0:
            print('  !! %-46s 找不到替换片段（重构过？），跳过' % name)
            return None
        if unique and n > 1:
            print('  !! %-46s 片段出现 %d 次（不唯一），跳过以免改错位置'
                  % (name, n))
            return None
        io.open(path, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, ok_n, fail_n, out = run_assertions()
        caught = (rc != 0 and fail_n > 0)
        print('  %s %-46s 失败=%d' % ('✓' if caught else '✗', name, fail_n))
        if not caught:
            print('       （断言没抓到 → 要么断言空转，要么破坏没落到目标上）')
        return caught
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


# 兼容旧调用名
def run():
    return run_assertions()


if __name__ == '__main__':
    print('=== 反向验证：断言必须能抓到错误代码 ===')
    print()
    results = []

    # A. 去掉 temperature 的能力判断
    results.append(trial(
        'A. 去掉 temperature 能力判断',
        API,
        'if (force || ChatProvider.supports(prov, model, "temp") == ChatProvider.OK) {\n'
        '                payload.put("temperature", temperature);\n'
        '            }',
        'payload.put("temperature", temperature);'))

    # B. 非 DeepSeek 的早退判断被删掉 → 会去查余额
    results.append(trial(
        'B. 删掉"非 DeepSeek 早退"',
        FS,
        '        if (!isDeepSeekProvider()) {',
        '        if (false) {'))

    # C. 非 DeepSeek 分支加回命中率 → 会显示恒 0% 的假数据
    results.append(trial(
        'C. 非 DeepSeek 也显示命中率',
        FS,
        '"%d \\u6B21 \\u00B7 %s tok", calls, UsageStats.k(tokens));',
        '"%d \\u6B21 \\u00B7 %s tok \\u00B7 \\u547D\\u4E2D %s", calls, '
        'UsageStats.k(tokens), rate);'))

    # D. ★ 用量播报气泡漏掉服务商判断（本轮真实发生的漏改）
    results.append(trial(
        'D. 用量播报漏判服务商（恒 0% 命中率复现）',
        FS,
        'final String tail = isDeepSeekProvider()',
        'final String tail = true'))

    # E. ★ 把服务商改回"点了就生效"（本次要防的回归）
    results.append(trial(
        'E. 服务商改回"点了就生效"',
        MC,
        '                        chosen[0] = which;',
        '                        chosen[0] = which;\n'
        '                        Prefs.setChatProvider(MainActivity.this, all.get(which).id);'))

    # F. ★ 用 setView 挂预览（会与列表互相覆盖，预览不显示）
    #    片段必须**限定在本方法内**，否则会改到别的对话框上
    #    （该写法在文件里出现 23 次 —— 见 trial 的 unique 说明）。
    results.append(trial(
        'F. 对话框混用 setView（预览其实不显示）',
        MC,
        '                .setPositiveButton("选定", new DialogInterface.OnClickListener() {\n'
        '                    @Override public void onClick(DialogInterface d, int w) {\n'
        '                        int idx = chosen[0];',
        '                .setView(new android.widget.TextView(MainActivity.this))\n'
        '                .setPositiveButton("选定", new DialogInterface.OnClickListener() {\n'
        '                    @Override public void onClick(DialogInterface d, int w) {\n'
        '                        int idx = chosen[0];'))

    # G. ★ 二级页返回直接退出 App（用户报的原始 bug 复现）
    results.append(trial(
        'G. 二级页返回直接退出（复现用户报的 bug）',
        BN,
        '        if (section >= 0) {\n'
        '            // 二级页 → 回所属板块首页。',
        '        if (false) {\n'
        '            // 二级页 → 回所属板块首页。'))

    # H. ★ canGoBack 与 back().exit 不一致（边缘滑动会被空接管）
    results.append(trial(
        'H. canGoBack 与 exit 不一致（边缘划一下没反应）',
        BN,
        '        return section >= 0 || tab > 0;',
        '        return section >= 0 || tab >= 0;'))

    # I. ★ 二级页回"当前板块"而不是"所属板块"
    results.append(trial(
        'I. 二级页回当前板块（应回所属板块）',
        BN,
        '            return new Action(sectionTab, -1, false);',
        '            return new Action(tab, -1, false);'))

    rc, ok_n, fail_n, out = run_assertions()
    print()
    print('  恢复后：通过 %d 项，失败 %d 项，退出码 %d' % (ok_n, fail_n, rc))
    if rc != 0:
        for line in out.splitlines():
            if 'FAIL' in line:
                print('     ' + line.strip()[:100])

    good = [r for r in results if r is not None]
    print()
    print('  %d/%d 个破坏被断言抓到' % (sum(1 for r in good if r), len(good)))
    sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
