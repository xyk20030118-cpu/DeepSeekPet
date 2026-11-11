# -*- coding: utf-8 -*-
"""设备验证：**自定义对话 API 到底通不通**（轮次56）。

用户报「自定义对话api无法正常使用」「模型就获取不到」，地址是
`http://127.0.0.1:7863/v1`（他本机的 AI 网关）。真凶两条：
① 设备上的 127.0.0.1 = **设备自己**；② **明文 HTTP 被平台默认挡掉**（App 侧硬 bug）。

## 判据（★ 双向，而且两边都要有**服务端**物证）

  a) `http://10.0.2.2:<port>/v1`（模拟器里的宿主机）⇒ 必须：
     · 本脚本自起的 mock **收到** `GET /v1/models`（服务端物证）；
     · 界面「测试连接」说**通了**、并报出模型个数。
  b) `http://127.0.0.1:<port>/v1`（回环）⇒ 必须：
     · mock **收不到**任何新请求；
     · 界面把原因说清楚（含"设备自己"与三条出路），而不是"可能是网络问题"；
     · 地址那一行**被动**带 ⚠。

## ★★ 端口纪律：绝不用 7863

7863 是**用户本机的 AI 网关**（他自己和 agent 都在用）。第一版复现脚本就是绑了它，
绑定失败直接空跑；万一绑上了，会把用户的模型连接一起弄断。所以这里写死 17911，
并在 `verify_custom_api.py` 里有一条断言钉着"这个脚本里不许出现 7863"。

用法：
    python build/verify_custom_api_ui.py --install
    python build/verify_custom_api_ui.py
    python build/verify_custom_api_ui.py --selftest
"""
import json
import os
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402
import verify_char_ui as V           # noqa: E402
import verify_char_switch_ui as S    # noqa: E402

DEV = V.DEV
PKG = V.PKG
PORT = 17911                      # ★ 不是 7863（见文件头）
HOST_ALIAS = '10.0.2.2'           # 模拟器里指向宿主机
HITS = []                         # mock 收到的请求（服务端物证）

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


# ═══════════════ mock（进程内起，自带服务端物证）═══════════════

class Mock(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *a):
        pass

    def _send(self, code, obj):
        body = json.dumps(obj).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        HITS.append(('GET', self.path))
        if self.path.rstrip('/').endswith('/models'):
            self._send(200, {'object': 'list', 'data': [
                {'id': 'mock-model-a'}, {'id': 'mock-model-b'}]})
        else:
            self._send(404, {'error': {'message': 'no such path'}})


def start_mock():
    srv = ThreadingHTTPServer(('0.0.0.0', PORT), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def probe_ok(texts):
    """「测试连接」说通了（含 ✓ 通了 + 模型个数）"""
    j = '\n'.join(texts or [])
    return ('通了' in j) and re.search(r'读到\s*(\d+)\s*个模型', j) is not None


def probe_models(texts):
    """「测试连接」报出的模型个数；读不到返回 -1"""
    j = '\n'.join(texts or [])
    m = re.search(r'读到\s*(\d+)\s*个模型', j)
    return int(m.group(1)) if m else -1


def probe_advice(texts):
    """回环地址必须给出"地址本身就有问题"+ 可操作解释"""
    j = '\n'.join(texts or [])
    return ('地址本身就有问题' in j) and ('设备自己' in j) and ('10.0.2.2' in j)


def addr_warned(texts):
    """地址那一行被动带 ⚠"""
    return any(('API 地址' in (t or '')) and ('可能是设备自己' in (t or '')) for t in (texts or []))


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    good = ['🧪 测试连接', '地址：http://10.0.2.2:17911/v1',
            '结果：HTTP 200，读到 2 个模型', '✓ 通了 —— 可以点「模型」那一行去选。']
    t('认出"通了"', probe_ok(good) and probe_models(good) == 2)
    t('反例：只说"结果：HTTP 200"不算通（没有模型列表）',
      not probe_ok(['结果：HTTP 200，读到 0 个模型']))
    loop = ['结果：ConnectException：Connection refused',
            '⚠ 地址本身就有问题：', '这个地址指的是“**运行 App 的这台设备自己**”',
            '· 模拟器里要连**宿主机**，请用 http://10.0.2.2:端口/v1；']
    t('回环：认出"地址本身就有问题"+ 解释', probe_advice(loop))
    t('反例：正常地址不带那条建议', not probe_advice(['结果：HTTP 200，读到 2 个模型']))
    t('★ 地址行的被动警告', addr_warned(['🔗 API 地址：http://127.0.0.1:1/v1  ⚠ 可能是设备自己']))
    t('反例：正常地址不加警告', not addr_warned(['🔗 API 地址：http://10.0.2.2:1/v1']))
    t('★★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    t('★★ 端口不是用户本机的网关端口 7863', PORT != 7863)
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def tap_row(needle, tries=14):
    """滚着找一行再点（设置页是 ScrollView；小步滚，别把目标滚过头）"""
    for _ in range(tries):
        for t, _d, _c, _ck, _chk, x0, y0, x1, y1 in V.nodes():
            if t and needle in t:
                cy = (y0 + y1) // 2
                if 150 < cy < 2150 and (y1 - y0) > 20:
                    V.tap((x0 + x1) // 2, cy)
                    return True
        V.sh('shell', 'input', 'swipe', '540', '1600', '540', '1200', '250')
        time.sleep(0.9)
    return False


def open_chat_section():
    """跳到「对话与人格」（dsp_section=1）—— 比盲点导航可靠"""
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity', '--ei', 'dsp_section', '1')
    time.sleep(5.5)
    return any('对话与人格' in (t or '') for t in S.all_texts())


def set_url(url):
    for k, v in (('api_base_url', url), ('chat_provider', 'custom'),
                 ('api_key_custom', 'sk-mock-test'), ('api_key_migrated', 'true'),
                 ('chat_model', 'mock-model-a')):
        S.pref_set(k, v)


def dismiss_probe():
    """关掉「测试连接」的结果弹窗。

    ★ 必须关掉再查设置页那一行：`uiautomator dump` **只 dump 顶层窗口**，
      弹窗一开，设置页的行就不在 dump 里了（轮次㊷ 的老教训 —— 第一版就因此假红）。
    """
    for t, _d, _c, _ck, _chk, x0, y0, x1, y1 in V.nodes():
        if t == '知道了':
            V.tap((x0 + x1) // 2, (y0 + y1) // 2)
            return True
    return False


def run_probe():
    """点「测试连接」并把弹窗里的文字全收回来"""
    if not open_chat_section():
        return None
    if not tap_row('测试连接'):
        return None
    time.sleep(6)
    return S.all_texts()


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-40:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能改 pref（换 google_apis 镜像或先 adb root）')
        return 1

    srv = start_mock()
    print('== 进程内 mock 已起在 0.0.0.0:%d（★ 不是 7863）==' % PORT)
    V.sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    try:
        # ── a) 宿主机别名：必须通，且**服务端**要收到请求 ──
        print()
        print('== a) http://%s:%d/v1（模拟器里的宿主机）=='
              % (HOST_ALIAS, PORT))
        set_url('http://%s:%d/v1' % (HOST_ALIAS, PORT))
        before = len(HITS)
        ts = run_probe()
        check('★★ 「测试连接」把 mock 的模型数报出来了（读到 2 个）',
              ts is not None and probe_ok(ts) and probe_models(ts) == 2,
              (ts or [])[:3])
        check('★★ **服务端物证**：mock 真的收到了 GET /v1/models（明文 HTTP 真的发出去了）',
              any(m == 'GET' and p.rstrip('/').endswith('/models') for m, p in HITS[before:]),
              HITS[before:])

        # ── b) 回环：必须说清楚原因，且请求发不出去 ──
        print()
        print('== b) http://127.0.0.1:%d/v1（设备自己 —— 用户报的就是这种）==' % PORT)
        set_url('http://127.0.0.1:%d/v1' % PORT)
        before2 = len(HITS)
        ts2 = run_probe()
        check('★★ 回环地址：界面把原因说清楚（"地址本身就有问题" + 设备自己 + 10.0.2.2）',
              ts2 is not None and probe_advice(ts2),
              [t[:60] for t in (ts2 or []) if '设备自己' in (t or '')][:1])
        check('★★ 而且**不是**"通了"（不能把连不上报成通）',
              ts2 is not None and not probe_ok(ts2))
        check('★★ 服务端物证：mock **没有**收到新请求（请求根本没离开设备）',
              len(HITS) == before2, HITS[before2:])
        # ★ 先关掉结果弹窗 —— 只 dump 顶层窗口，弹窗开着时设置页的行根本不在 dump 里
        dismiss_probe()
        time.sleep(1.6)
        check('★ 地址那一行**被动**带 ⚠（不点进去也看得见）',
              addr_warned(S.all_texts()))

        # ── c) 清态：把地址还回官方，别把模拟器留在"连不上"的状态 ──
        S.pref_set('api_base_url', 'https://api.deepseek.com')
        S.pref_set('chat_provider', '')
    finally:
        try:
            srv.shutdown()
        except Exception:
            pass

    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    print('ALL CUSTOM-API DEVICE CHECKS PASSED'
          '（明文 HTTP 真的发得出去 / 回环被认出并说清原因 / 两边都有服务端物证）')
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
