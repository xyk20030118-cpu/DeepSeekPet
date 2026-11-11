# -*- coding: utf-8 -*-
"""反向验证：**自定义对话 API 修复**的断言必须能抓到回潮（轮次56）。

每一项都**故意破坏一次**，确认 `verify_custom_api.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里（中途异常也不能把源码留在被破坏的状态）。

本轮最值得盯的三条：
  * **A/B（明文放行）** —— 摘掉它，用户填对了局域网地址也**发不出去**，
    而界面上一切照旧（最难发现，也是用户报的"用不了"的真凶之一）；
  * **C（回环判据）** —— 认不出 127.0.0.1 就等于"用户还得自己猜为什么连不上"；
  * **J（强制参数）** —— 又发 thinking 就会把第三方网关打成 400。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
RES = r'D:\petport_lab\petbuild\res\xml'
VERIFY = os.path.join(HERE, 'verify_custom_api.py')

MAN = r'D:\petport_lab\petbuild\AndroidManifest.xml'
NSC = os.path.join(RES, 'network_security_config.xml')
API = os.path.join(SRC, 'ApiClient.java')
EP = os.path.join(SRC, 'Endpoint.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
RUNBAT = os.path.join(HERE, 'fpstest', 'run.bat')
DEVUI = os.path.join(HERE, 'verify_custom_api_ui.py')


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
        print('  [SKIP] %-52s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-52s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('== 反向验证：自定义对话 API（明文 / 回环 / 失败原因 / 测试连接 / 强制参数）==')
print()

# A. ★★ 清单里摘掉 networkSecurityConfig（targetSdk 34 默认禁明文 ⇒ 又发不出去）
results.append(break_and_check(
    'A. ★★摘掉 networkSecurityConfig（明文又被禁）',
    MAN,
    '        android:networkSecurityConfig="@xml/network_security_config"\n',
    ''))

# B. ★★ 策略文件改成"禁止明文"（文件在、但没放行）
results.append(break_and_check(
    'B. ★★策略文件改成禁止明文',
    NSC,
    'cleartextTrafficPermitted="true"',
    'cleartextTrafficPermitted="false"'))

# B2. ★ 顺手把证书校验也关了（安全口径被破坏）
results.append(break_and_check(
    'B2. ★策略文件把系统根证书也去掉',
    NSC,
    '<certificates src="system" />',
    ''))

# C. ★★ 回环不再识别（用户还得自己猜"为什么连不上"）
results.append(break_and_check(
    'C. ★★认不出 localhost / .localhost',
    EP,
    '        if ("localhost".equals(x) || x.endsWith(".localhost")) return true;',
    '        ;'))

# D. ★★ 建议文案不再解释"设备自己"
results.append(break_and_check(
    'D. ★★建议不再解释"设备自己"',
    EP,
    '这个地址指的是“**运行 App 的这台设备自己**”，不是你的电脑。',
    '这个地址连不上。'))

# E. ★★ fetchModels 不再走 httpGet（失败原因又丢了）
results.append(break_and_check(
    'E. ★★fetchModels 不再走 httpGet',
    API,
    '        HttpResult hr = httpGet(modelsUrl(), apiKey, 10000);',
    '        HttpResult hr = new HttpResult();'))

# F. ★★ describe() 不再"真因优先"（退化成笼统一句）
results.append(break_and_check(
    'F. ★★describe() 不再返回真因',
    API,
    '            if (error.length() > 0) return error;',
    '            if (error.length() >= 0) return "网络错误";'))

# G. ★★ 存地址不再先警告（回环地址照存，用户一头雾水）
results.append(break_and_check(
    'G. ★★存地址不再警告回环',
    MAIN,
    '                        String advice = Endpoint.advice(url);',
    '                        String advice = "";'))

# H. ★★ 「测试连接」入口被摘（用户看不到真实报错）
results.append(break_and_check(
    'H. ★★测试连接入口被摘',
    MAIN,
    '    private void runConnectionTest() {',
    '    private void runConnectionTestX() {'))

# I. ★ 模型失败弹窗不再带真因
results.append(break_and_check(
    'I. ★模型失败弹窗不再带真因',
    MAIN,
    '                            String why = ApiClient.modelsError();',
    '                            String why = "";'))

# J. ★★ 「强制参数」又开始发 DeepSeek 专有的 thinking（第三方网关会 400）
results.append(break_and_check(
    'J. ★★强制开关又发 thinking',
    API,
    '            if (ChatProvider.supports(prov, model, "think") == ChatProvider.OK) {',
    '            if (true) {'))

# K. ★ fpstest 不再编 Endpoint（"纯类"就没被真跑过）
results.append(break_and_check(
    'K. ★fpstest 不再编 Endpoint',
    RUNBAT,
    'Endpoint.java',
    ''))

# L. ★★ 设备脚本用回用户网关端口 7863（会把用户的模型连接弄断）
results.append(break_and_check(
    'L. ★★设备脚本用回 7863 端口',
    DEVUI,
    'PORT = 17911',
    'PORT = 7863'))

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
