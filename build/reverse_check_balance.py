"""反向验证：故意破坏源码，确认 cpidtest 的断言真的会失败。

★ 为什么必须做（本工程已吃过一次亏）：
  「一条永远通过的断言比没有更糟」—— 它给的是虚假安全感。
  上一轮我写的 ApiClient 源码断言就是空转的：删掉能力判断后**仍然全绿**
  （原因：检查窗口太宽，附近的另一处判断把漏洞遮住了）。
  见 build/reverse_check_api.py 的注释。

  所以每次新增断言，都要验证它在**错误代码**上会失败。

破坏项（每次只改一处，改完立刻恢复）：
  A. 把 Kimi 的 temperature 从 FIXED 改成 OK      → ChatProviderTest 该失败
  B. 把 SiliconFlow 的余额字段名写错              → BalanceTest 该失败
  C. 让 ChatBalance 把空字符串当 0                → BalanceTest 该失败
  D. 让 buildUrl 不处理末尾斜杠                    → BalanceTest 该失败
"""
import io
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
PROV = os.path.join(SRC, 'ChatProvider.java')
BAL = os.path.join(SRC, 'ChatBalance.java')


def run_suite():
    r = subprocess.run(['cmd', '/c', 'run.bat'],
                       cwd=r'D:\petport_lab\petbuild\build\cpidtest',
                       capture_output=True)
    out = (r.stdout + r.stderr).decode('utf-8', 'replace')
    # ★ 计数正则要宽松：中文经 cmd 管道传回来会变成乱码
    #   （UTF-8 的"全部通过"到 Python 这边可能已经不是原字节），
    #   严格匹配会得到 0 项，看起来像"测试没跑"。
    #   退出码才是权威判据；计数只用于展示。
    nums = [int(m) for m in re.findall(r'(\d+)\s*\u9879', out)]
    if not nums:
        nums = [int(m) for m in re.findall(r'(\d+)\s*(?:\?|\uFFFD)+\s*$',
                                           out, re.M)]
    ok_n = sum(nums)
    # 真实失败判据：非零退出码，或输出里出现 FAIL
    failed = (r.returncode != 0) or ('FAIL' in out)
    return r.returncode, ok_n, failed, out


def trial(name, path, old, new, should_break=True):
    """改一处 → 跑 → 恢复，报告断言是否抓到"""
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        s = io.open(path, encoding='utf-8-sig').read()
        if old not in s:
            print('  !! %s: 找不到要替换的片段，跳过' % name)
            return None
        io.open(path, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, ok_n, failed, out = run_suite()
        caught = failed
        mark = '✓' if caught == should_break else '✗'
        print('  %s %-46s 退出码=%d 失败=%s' % (mark, name, rc, failed))
        if not caught and should_break:
            for line in out.splitlines():
                if 'FAIL' in line:
                    print('       ' + line.strip()[:100])
        return caught == should_break
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：断言必须能抓到错误代码 ===')
print()
results = []

# A. Kimi 温度能力被改回"可发" → 用户报的 400 会复发
results.append(trial(
    'A. Kimi temperature 改回 OK（400 会复发）',
    PROV,
    '.over("kimi-k2", kimiFixed())',
    '.over("kimi-k2", general())'))

# B. 余额字段名写错 → 余额永远显示不出来
results.append(trial(
    'B. SiliconFlow 余额字段名写错',
    PROV,
    'balance("/user/info", "data.totalBalance", "CNY")',
    'balance("/user/info", "data.wrongField", "CNY")'))

# C. 空字符串当 0 → 界面显示"余额 ¥0.00"
results.append(trial(
    'C. 空字符串被当成 0',
    BAL,
    'if (s.length() == 0) return Double.NaN;   // 空串不能当 0\n            return Double.parseDouble(s);',
    'if (s.length() == 0) return 0.0;\n            return Double.parseDouble(s);'))

# D. 不剥后缀 → 用户填完整端点时拼出 404（用户截图里的那个故障）
results.append(trial(
    'D. normalizeBase 不剥端点后缀（完整端点写法 → 404）',
    BAL,
    '        String[] suffixes = {\n                "/chat/completions",',
    '        String[] suffixes = {\n                "/__never_matches__",'))

# E. 不检查业务错误 → 智谱 Key 无效被误报成"无余额数据"
results.append(trial(
    'E. hasBusinessError 恒为 false（智谱 401 被误报成无数据）',
    BAL,
    '    public static boolean hasBusinessError(JSONObject json) {\n        if (json == null) return false;',
    '    public static boolean hasBusinessError(JSONObject json) {\n        if (json != null) return false;'))

# F. 不透出服务端错误消息 → 用户只看到"接口返回错误"而不知原因
results.append(trial(
    'F. firstNonEmpty 取不出消息（错误原因丢失）',
    BAL,
    '                String s = String.valueOf(v).trim();\n                if (s.length() > 0 && !"null".equals(s)) return s;',
    '                String s = String.valueOf(v).trim();\n                if (false) return s;'))

# 恢复后再跑一次，确认一切正常
rc, ok_n, failed, out = run_suite()
print()
print('  恢复后：退出码 %d，失败=%s' % (rc, failed))
if failed:
    for line in out.splitlines():
        if 'FAIL' in line or 'error' in line.lower():
            print('       ' + line.strip()[:100])

good = [r for r in results if r]
print()
print('  %d/%d 个破坏被断言抓到' % (sum(1 for r in good if r), len(good)))
bad = [r for r in results if r is False]
sys.exit(1 if (bad or failed) else 0)
