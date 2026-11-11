# -*- coding: utf-8 -*-
"""断言：**搜索服务可插拔**（轮次㉘ —— 用户："不用 kimi 的搜索，要让任何 api 接入进来都能搜索"）。

这条要求把"搜索"从"写死必应（后来又加一条 Kimi）"变成**配置驱动**：
一个搜索服务 = 地址 + 方法 + 请求头 + 请求体模板 + 结果数组路径 + 各字段路径。
于是任何 API（Kimi / 智谱 / 博查 / Tavily / 自建 SearXNG / 用户自己的）都能接进来。

钉这些最容易回退的地方：
  A. 可插拔性本身（`SearchProvider` 的 6 要素、预设、用户自定义、**自定义可覆盖内置**）；
  B. `Json` 解析与路径（`a.b` / `a[0].b` / **`a[].b` 摊平** / 宽容注释）—— 没有它就没法"按路径取字段"；
  C. `WebClient` 接线：选非必应才走 provider；**失败退回必应并说明**；第二跳抓正文；
     需要 Key 而没填**不发请求**；★ 日志**绝不出现 Key**；
  D. 设置页：一行入口 + 选服务/填 Key/自定义三个对话框 + 自定义 JSON **保存前校验**；
  E. `Prefs`：默认仍是**免费必应**（不能因为加了可插拔就把默认变成"要 Key"）；
  F. 真实报文 fixture（Kimi 三份）+ fpstest 的 [26][27] 两节。

行为判据在 `build/fpstest` 第 [26][27] 节（跑真实报文，离线）；设备链路在
`build/verify_web_provider_ui.py`（模拟器 + 真 Key，从环境变量读，没设就跳过）。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
CLIENT = os.path.join(SRC, 'WebClient.java')
PROVIDER = os.path.join(SRC, 'SearchProvider.java')
JSON = os.path.join(SRC, 'Json.java')
PREFS = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
PROMPT = os.path.join(SRC, 'WebPrompt.java')
DEVS = os.path.join(HERE, 'verify_web_provider_ui.py')
FIX = os.path.join(HERE, 'fpstest', 'fixtures')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


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


prov_raw = read(PROVIDER)
json_raw = read(JSON)
client_raw = read(CLIENT)
main_raw = read(MAIN)
prov = code_only(prov_raw)
js = code_only(json_raw)
client = code_only(client_raw)
prefs = code_only(read(PREFS))
main = code_only(main_raw)
prompt = code_only(read(PROMPT))

print('=== A. 可插拔：一个搜索服务 = 六个要素（配置驱动，不是写死某家）===')
for field, why in (('endpoint', '地址'), ('method', '方法'), ('headers', '请求头'),
                   ('body', '请求体模板'), ('listPath', '结果数组路径'),
                   ('titlePath', '标题路径'), ('linkPath', '链接路径'),
                   ('snippetPath', '摘要路径')):
    check('配置里有「%s」（%s）' % (field, why), field in prov)
check('★ 路径可配置 ⇒ 任何形状的响应都能吃（content 路径可省）',
      'contentPath' in prov and 'bodyEndpoint' in prov and 'bodyField' in prov)
check('预设：必应（免费）/ Kimi（实测）/ SearXNG / Tavily 都在',
      all(('"id\\":\\"%s' % i) in prov_raw or ('id\":\"%s' % i) in prov_raw
          for i in ('bing', 'kimi', 'searxng', 'tavily')))
check('★ 必应预设**不需要 Key**（默认路必须零配置）',
      'needs_key\\":\\"false' in prov_raw or 'needs_key":"false' in prov_raw)
check('★ 同 id 时**自定义覆盖内置**（用户能改模板而不必动代码）',
      'out.addAll(custom);' in prov and 'if (!shadowed) out.add(p);' in prov)
check('未知 id 返回 null（调用方退回必应，而不是崩/空手）',
      'public static SearchProvider byId(String id, String customJson)' in prov)

print()
print('=== B. Json：解析 + 路径（没有它就没法"按路径取字段"）===')
check('自己写的极小解析器（纯类不能用 Android 的 org.json）',
      'public static Object parse(String s)' in js and 'public static Object parseOrNull' in js)
check('★ 路径支持 a.b / a[0].b / **a[].b 摊平**，且摊平后继续走完剩下的路径',
      'private static Object walk(Object cur, List<String> segs)' in js
      and 'out.add(walk(o, rest));' in js)
check('★ 数组上继续写名字会**隐式摊平**（results.title 等价 results[].title）',
      'if (cur instanceof List && idx < 0 && !flat)' in js)
check('宽容注释（// 与 /* */）—— 用户手写配置常见',
      "s.charAt(i + 1) == '/'" in js and 'indexOf("*/", i + 2)' in js)
check('★ {q}/{key} 塞进 JSON 体时**做转义**（引号/换行不能把 JSON 弄坏）',
      'jsonEsc(query == null ? "" : query)' in prov and 'jsonEsc(apiKey == null ? "" : apiKey)' in prov)
check('取不到返回 null / 不抛异常；坏 JSON 由 parseOrNull 吞掉',
      'return null;' in js and 'parseOrNull' in js)

print()
print('=== C. WebClient 接线 ===')
check('★ 选了非必应才走 provider（必应 = 免费内置路）',
      'static SearchProvider selectedProvider(Context ctx)' in client
      and 'SearchProvider.byId(id, Prefs.customProviders(ctx))' in client
      and '!SearchProvider.BING.equals(prov.id)' in client)
check('★ provider 失败 ⇒ **退回必应**（这个分支本身也要被钉住）',
      'if (custom && (hits == null || hits.isEmpty())) {' in client
      and 'hits = search(ctx, q, l);' in client)
check('★ provider 失败 ⇒ **退回必应**，并记一行日志',
      'provider 空手而归 ⇒ 退回必应' in client and 'hits = search(ctx, q, l);' in client)
check('★★ 退回必应时**要在资料里说明**（否则用户配错 Key 却看着"搜到了"）',
      '你选的搜索服务「' in client and 'WebPrompt.withHits(userMsg, hits, bodies, note)' in client)
check('第二跳：配了 body_endpoint 用它抓整页正文，否则用我们自己的剥标签',
      'providerBody(ctx, prov, hits.get(0).url, l)' in client and 'fetchText(ctx, hits.get(0).url)' in client)
check('★ 需要 Key 而没填 ⇒ **不发请求**，给一句人话',
      'if (p.needsKey && (key == null || key.length() == 0))' in client
      and '还没填 Key' in client)
check('请求组装走配置：GET 用 urlFor，POST 用 bodyFor+headersFor',
      'p.urlFor(q, key)' in client and 'p.bodyFor(q, key)' in client and 'p.headersFor(key)' in client)
# ★ 日志里不许出现 Key：把每条 DSWeb 日志**整句**取出来查（只看字符串字面量会漏掉
#   `+ key +` 这种拼接 —— 第一版就是这么假绿的）。
log_stmts = re.findall(r'"DSWeb"[^;]*;', client)
leaky = [s for s in log_stmts if re.search(r'\bkey\b', s)]
check('★★ 日志**绝不出现 Key**（Key 只在请求头里）',
      bool(log_stmts) and not leaky,
      '检查了 %d 条 DSWeb 日志整句' % len(log_stmts))
check('HTTP 层支持自定义头 + POST（不然接不了要鉴权的服务）',
      'static String httpSend(String method, String url, String body,' in client
      and 'sendWithRetry(' in client)

print()
print('=== D. 设置页：一行入口 + 三个对话框 + 保存前校验 ===')
check('设置页有「搜索服务」这一行（文案与断言共用一份）',
      'Prefs.searchProviderLabel()' in main and 'showSearchProviderDialog()' in main)
check('选择对话框：先选、按「选定」才落盘；需要 Key 却没填**直接把填写框弹出来**',
      'setSingleChoiceItems(labels, cur' in main and 'showSearchKeyDialog(p)' in main)
check('Key 对话框：按服务分开保存 + 显示去哪拿 Key',
      'Prefs.setSearchKey(MainActivity.this, p.id' in main and 'p.keyHint' in main)
check('★ 自定义服务：贴 JSON，**保存前先解析校验**，写错当场说原因',
      'showCustomProviderDialog()' in main
      and 'SearchProvider.fromJsonArray(asArray)' in main
      # ★ 必须是"校验失败就 return（不保存）"这个分支，光看字符串在不在会假绿
      and 'if (list.isEmpty()) {' in main
      and 'JSON 格式不对' in main and '没保存：' in main)
check('说明文字里没有 markdown 的 `**`（纯文本会原样显示星号）',
      (lambda m: bool(m) and '**' not in m.group(1))(
          re.search(r'addLabel\(box, "(换一家搜索服务[^;]*?)"\s*\)', main_raw, re.S)))

print()
print('=== E. Prefs：默认仍是免费必应 ===')
check('★ search_provider 默认 = bing（加了可插拔也不能把默认变成"要 Key"）',
      '"search_provider"' in prefs and 'getString(KEY_SEARCH_PROVIDER, SearchProvider.BING)' in prefs)
check('自定义模板与"按服务分槽的 Key"都有',
      '"search_custom_providers"' in prefs and '"search_key_" + providerId' in prefs)

print()
print('=== F. 真实报文 fixture + fpstest + 设备脚本 ===')
for n, least in (('kimi-search-pro.json', 2000), ('kimi-search-basic.json', 500),
                 ('kimi-fetch.json', 2000)):
    p = os.path.join(FIX, n)
    check('fixture 在：%s' % n, os.path.isfile(p) and os.path.getsize(p) >= least)
check('★ fixture 里**不含任何 Key**（不能把密钥带进仓库）',
      all('sk-' not in read(os.path.join(FIX, n))[:4000]
          for n in ('kimi-search-pro.json', 'kimi-search-basic.json', 'kimi-fetch.json')))
rt = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                    capture_output=True)
out = rt.stdout.decode('utf-8', 'replace')
check('★ fpstest 全绿（含 [26] Json 与 [27] SearchProvider 两节）',
      rt.returncode == 0 and '[26]' in out and '[27]' in out, '退出码=%d' % rt.returncode)
check('设备脚本在（真 Key 从环境变量读；没设就跳过并标注未验证）',
      os.path.isfile(DEVS) and 'KIMI_API_KEY' in read(DEVS) and '--selftest' in read(DEVS))

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（搜索服务可插拔：任何 API 都能接进来）')
