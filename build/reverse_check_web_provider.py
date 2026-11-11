# -*- coding: utf-8 -*-
"""反向验证：轮次㉘「搜索服务可插拔」的断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 那条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 盯的是这一轮最容易前功尽弃的地方：
  配置驱动的六个要素缺一个、路径摊平退化、{q} 不转义、选中的服务根本没被解析、
  失败不退回必应、退回时不说一句、没 Key 也硬发、日志把 Key 打出来、
  自定义 JSON 不校验、默认搜索从免费必应变成要 Key 的、必应预设被改成需要 Key。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
CLIENT = os.path.join(SRC, 'WebClient.java')
PROVIDER = os.path.join(SRC, 'SearchProvider.java')
JSON = os.path.join(SRC, 'Json.java')
PREFS = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
VERIFY = os.path.join(HERE, 'verify_web_provider.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def break_and_check(label, path, old, new, count=1):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-54s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, count))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-54s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：搜索服务可插拔（任何 API 都能接）===')
print()

# ① 选中的服务根本没被解析（选了 Kimi 也永远走必应 ⇒ "可插拔"名存实亡）
results.append(break_and_check(
    'A. 不再解析"选中的搜索服务"（永远退回必应）',
    CLIENT,
    '            return SearchProvider.byId(id, Prefs.customProviders(ctx));',
    '            return null;'))

# ② provider 失败不再退回必应（换了家服务 = 搜不出来）
results.append(break_and_check(
    'B. provider 失败不再退回必应',
    CLIENT,
    '            if (custom && (hits == null || hits.isEmpty())) {',
    '            if (false) {'))

# ③ 退回必应时不再告诉用户（配错 Key 却看着"搜到了"）
results.append(break_and_check(
    'C. 退回必应时不再在资料里说明',
    CLIENT,
    '                note = "（提示：你选的搜索服务「" + prov.label + "」这次没成功"',
    '                note = "（提示：" + ""'))

# ④ {q} 塞进 JSON 体时不再转义（用户查询里一个引号就把请求弄坏）
results.append(break_and_check(
    'D. 请求体里的 {q} 不再做 JSON 转义',
    PROVIDER,
    'return body.replace("{q}", jsonEsc(query == null ? "" : query))',
    'return body.replace("{q}", query == null ? "" : query)'))

# ⑤ 路径摊平退化（chunks[].text 取不到段落 ⇒ 又只剩摘要）
results.append(break_and_check(
    'E. a[].b 摊平后不再往下走',
    JSON,
    'for (Object o : (List<?>) next) out.add(walk(o, rest));',
    'for (Object o : (List<?>) next) out.add(o);'))

# ⑥ 自定义服务不再覆盖内置（用户改了模板却没生效）
results.append(break_and_check(
    'F. 同 id 时自定义不再覆盖内置',
    PROVIDER,
    '        List<SearchProvider> custom = fromJsonArray(customJson);\n        out.addAll(custom);',
    '        List<SearchProvider> custom = fromJsonArray(customJson);\n        out.addAll(presets());'))

# ⑦ 需要 Key 而没填也硬发（用户看到的是莫名其妙的 401，而不是"去填 Key"）
results.append(break_and_check(
    'G. 没填 Key 也照样发请求',
    CLIENT,
    '        if (p.needsKey && (key == null || key.length() == 0)) {',
    '        if (false) {'))

# ⑧ 日志里把 Key 打出来（泄密）
results.append(break_and_check(
    'H. 日志里带上 Key（泄密）',
    CLIENT,
    '"DSWeb", "provider 抓正文失败 服务=" + p.id + " :: " + brief(t)',
    '"DSWeb", "provider 抓正文失败 服务=" + p.id + " key=" + key + " :: " + brief(t)'))

# ⑨ 自定义 JSON 不再校验（坏配置写进系统，之后每次都失败）
results.append(break_and_check(
    'I. 自定义服务保存前不再校验',
    MAIN,
    '                        if (list.isEmpty()) {',
    '                        if (false) {'))

# ⑨b 摊平只摊一层（返回对象列表，chunks[].text 就成了 null）
results.append(break_and_check(
    'L. 显式摊平不再走完剩下的路径（a[] 后直接返回）',
    JSON,
    '            for (Object o : (List<?>) next) out.add(walk(o, rest));\n            return out;',
    '            return next;'))

# ⑩ 默认搜索从"免费必应"变成"要 Key 的"（默认必须零配置能用）
results.append(break_and_check(
    'J. 默认搜索服务不再是免费必应',
    PREFS,
    'getString(KEY_SEARCH_PROVIDER, SearchProvider.BING)',
    'getString(KEY_SEARCH_PROVIDER, "kimi")'))

# ⑪ 必应预设被改成"需要 Key"（免费路没了）
results.append(break_and_check(
    'K. 必应预设变成需要 Key',
    PROVIDER,
    '\\"needs_key\\":\\"false\\",\\"endpoint\\":\\"bing://rss\\"',
    '\\"needs_key\\":\\"true\\",\\"endpoint\\":\\"bing://rss\\"'))

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
