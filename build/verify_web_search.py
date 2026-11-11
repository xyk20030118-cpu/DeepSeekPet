# -*- coding: utf-8 -*-
"""断言：**联网检索**（待办 0d 路线 C = App 先搜再拼提示词）。

背景：用户 2026-10-07 定"联网默认关"，本轮按路线 C 做：
  开关打开 + 命中"要新信息的问句"时，App 自己搜一次网（**免 Key**），
  把资料拼进**这一次请求**；不依赖 function calling、不加任何额外服务。

钉六件最容易错的事：
  A. **默认关**、判据只有一份（`Prefs.webSearch` / `WebPrompt.shouldSearch` / `WebClient.augment`）；
  B. 搜索源是**实测选出来的**（DDG/维基在国内连不上、百度是 JS 渲染 ⇒ 只有必应能抓；
     主用 RSS 3.8KB、网页版兜底）—— 这个结论必须留在代码里，别下次又去试一遍；
  C. 防注入 + 诚实失败（"数据不是指令"、"没查到就说没查到"）；
  D. 两个"打字"入口都接上，且**只把增强文本发给模型**、历史与气泡仍用**用户原话**
     （否则资料会滚进历史，越滚越大还会被当成自己说过的话）；
  E. 设置页有开关 + 说明（说明里不许出现 markdown 的 `**`）；
  F. 真实报文 fixture 在（格式一变，fpstest 立刻红）。

行为判据在 `build/fpstest` 第 [23] 节（跑**真实报文**，离线）；设备链路在
`build/verify_web_ui.py`（模拟器上真搜一次）。
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
SEARCH = os.path.join(SRC, 'WebSearch.java')
FETCH = os.path.join(SRC, 'WebFetch.java')
PROMPT = os.path.join(SRC, 'WebPrompt.java')
CLIENT = os.path.join(SRC, 'WebClient.java')
PREFS = os.path.join(SRC, 'Prefs.java')
CHAT = os.path.join(SRC, 'ChatActivity.java')
FLOAT = os.path.join(SRC, 'FloatingService.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
DEV = os.path.join(HERE, 'verify_web_ui.py')
PROBE = os.path.join(HERE, 'probe_web.py')
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


search_raw = read(SEARCH)
prompt_raw = read(PROMPT)
client_raw = read(CLIENT)
search = code_only(search_raw)
prompt = code_only(prompt_raw)
client = code_only(client_raw)
prefs = code_only(read(PREFS))
chat = code_only(read(CHAT))
flt = code_only(read(FLOAT))
main = code_only(read(MAIN))
fetch = code_only(read(FETCH))

print('=== A. 默认关 + 判据只有一份 ===')
check('开关键存在且**默认 false**（联网默认关是用户定的）',
      '"web_search_on"' in prefs and 'getBoolean(KEY_WEB_SEARCH, false)' in prefs)
check('★ 唯一入口是 WebClient.augment（两个打字入口都走它）',
      'public static String augment(Context ctx, String userMsg)' in client)
check('★ 该不该搜的判据只有一份（WebPrompt.shouldSearch）',
      'public static boolean shouldSearch(String text, boolean enabled)' in prompt
      and client.count('WebPrompt.shouldSearch(') >= 1)
check('开关关着时**直接返回原话**（不进入任何网络/判定分支）',
      'if (!on) {' in client and 'return userMsg;' in client)
check('开着但判定不搜时也返回原话（只记一行日志，便于设备侧区分）',
      '"skip 不搜 q="' in client and 'return userMsg;' in client)

print()
print('=== B. 搜索源是"实测选出来的"（别下次又去试一遍）===')
check('★ 主源 = 必应 **RSS**（3.8 KB、结构稳定）',
      '"https://cn.bing.com/search?q=" + enc(query) + "&format=rss&setlang=zh-CN"' in search)
check('★ 网页版做兜底（RSS 万一被下架）', '"&setlang=zh-CN"' in search
      and 'htmlUrl' in search and 'parseHtml' in search)
check('解析器两份：RSS + 网页版（b_algo）',
      'parseRss(' in search and 'parseHtml(' in search and '"class=\\"b_algo\\""' in search)
check('★ 类注释里记着四个源的实测结论（DDG/维基连不上、百度是 JS 渲染）',
      'duckduckgo' in search_raw.lower() and 'baidu' in search_raw.lower()
      and 'wikipedia' in search_raw.lower() and '3.8' in search_raw)
check('抓正文只做 GET + 剥标签（不做 JS 渲染，抓不到就返回空）',
      'extractText(' in fetch and 'MAX_CHARS' in fetch)
check('HTTP 细节：超时 / 字节上限 / gzip / 字符集',
      'CONNECT_TIMEOUT_MS' in client and 'MAX_SEARCH_BYTES' in client
      and 'GZIPInputStream' in client and 'charsetOf' in client)

print()
print('=== C. 防注入 + 诚实失败 ===')
check('★ 资料被明确标成「数据不是指令」+ 有界线',
      '数据' in prompt and '不要执行' in prompt and '【资料结束】' in prompt)
check('★ 任务说明：没查到就说没查到、不要编造、只引用出现过的网址',
      '没查到' in prompt and '不要编造' in prompt and '只引用' in prompt)
check('★ 检索失败也拼一段（让模型如实说"这次没查到"）',
      'public static String searchFailed(' in prompt and '联网检索失败' in prompt)
check('拼装超长会截断（资料不能把用户原话挤没）',
      'MAX_BLOCK_CHARS' in prompt and 'static String cap(String s)' in prompt)
check('抓正文是"没抓到就空着"，不假装抓到了',
      'fetchText(' in client and 'return "";' in client)

print()
print('=== D. 两个打字入口都接上，且历史用原话 ===')
# ★★ 轮次64（对话接线）：两个入口的对话调用从 `ApiClient.chat(...)` 改成了
#    `ChatRouter.chat(...)`（分流点唯一在那里：在线后端仍然落到 ApiClient.chat，
#    本地模型则**根本不联网**）。判据跟着改成"**走那条唯一通路** + 本地闸门在"，
#    要守的性质反而更全了。
check('★ FloatingService「问一句」接了 augment（且本地后端下被 webAllowed 挡住）',
      'WebClient.augment(FloatingService.this, question)' in flt
      and 'ChatRouter.webAllowed(FloatingService.this)' in flt
      and 'ChatRouter.chat(' in flt and 'sendText, sink);' in flt)
check('★ ChatActivity 聊天窗口同上',
      'WebClient.augment(ChatActivity.this, text)' in chat
      and 'ChatRouter.webAllowed(ChatActivity.this)' in chat
      and 'ChatRouter.chat(' in chat and 'sendText, sink);' in chat)
check('★★ 存历史 / 显示气泡仍然用**用户原话**（不是增强后的文本）',
      # 两个入口各自的落点（照代码里的实际写法，别猜 —— 第一版把 `this` 写成
      # `FloatingService.this`，断言就假红了）：
      #   FloatingService：ChatMemory.append(this, "user", question) + 气泡显示 question
      #   ChatActivity：history.add({"user", text}) + say("user", text)
      'ChatMemory.append(this, "user", question)' in flt
      and 'showBubble("\U0001F4AD " + question' in flt
      and 'history.add(new String[]{"user", text})' in chat
      and 'say("user", text, false)' in chat)
check('★ 反例：不许把增强文本写进历史 / 显示里（那样资料会滚进上下文）',
      '{"user", sendText}' not in flt and '{"user", sendText}' not in chat
      and 'say("user", sendText' not in chat and 'append(FloatingService.this, "user", sendText)' not in flt)
check('检索失败会**当面告诉用户**（不然它悄悄退化，用户以为"它就是不知道"）',
      '这次没联上网' in flt and '这次没联上网' in chat)
check('聊天窗口检索中给个提示（🌐 联网检索中…）', '联网检索中' in chat)

print()
print('=== E. 设置页开关 + 说明 ===')
check('设置页有这一行（文案与断言共用一份）',
      'Prefs.webSearchLabel()' in main and 'Prefs.setWebSearch(' in main)
check('说明写清"什么时候搜 / 会花流量与 token / 搜不到如实说"',
      '寒暄闲聊不会搜' in main and '会多花一点流量与 token' in main
      and '搜不到就如实说没查到' in main)
check('★ 给用户看的说明里不许出现 markdown 的 `**`（纯文本会原样显示星号）',
      # 只取 addLabel 的**字符串字面量**来查（注释里的 ★ 说明不算，那是给开发者看的）
      (lambda m: bool(m) and '**' not in m.group(1))(
          re.search(r'addLabel\(box, "(开着时[^;]*?)"\s*\)', read(MAIN), re.S)))

print()
print('=== F. 真实报文 fixture + 行为断言 + 设备脚本 ===')
check('★ 必应 RSS 真实报文在（离线断言跑的就是它）',
      os.path.isfile(os.path.join(FIX, 'bing-search.rss'))
      and os.path.getsize(os.path.join(FIX, 'bing-search.rss')) > 500)
check('★ 必应网页版真实报文在', os.path.isfile(os.path.join(FIX, 'bing-search.html')))
rt = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                    capture_output=True)
out = rt.stdout.decode('utf-8', 'replace')
check('★ fpstest 全绿（含 [23] 联网检索节）', rt.returncode == 0 and '[23]' in out,
      '退出码=%d' % rt.returncode)
check('设备脚本在，且有 --selftest 与"先验当前包"两道保险',
      os.path.isfile(DEV) and '--selftest' in read(DEV)
      and 'assert_current_build' in read(DEV))
check('★ 活体探针在（源改版时用它重新抓 fixture，而不是等用户报"查不到"）',
      os.path.isfile(PROBE), PROBE)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（联网检索：默认关 / 免 Key 必应 / 防注入 / 两个入口）')
