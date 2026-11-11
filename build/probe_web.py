# -*- coding: utf-8 -*-
"""活体探针：**刷新**联网检索的 fixture，并报告搜索源现在长什么样。

为什么要有它：
  搜索结果页是**别人的**格式。`build/fpstest/fixtures/bing-search.{rss,html}` 是从真实响应
  抓下来存下的样本 —— 断言跑在它们上面，格式一变套件就红。
  但"红了"之后还得有人能**重新抓一份**、看清差在哪。这个脚本就是那只手。

用法：
    python build/probe_web.py            # 抓 + 报告 + 覆盖 fixture
    python build/probe_web.py --dry      # 只报告，不覆盖
    python build/probe_web.py --query 天气

★ 跑完记得再跑一次 fpstest：fixture 换了，[23] 节就是拿新报文在验解析器。
"""
import io
import os
import re
import subprocess
import sys
import urllib.parse
import urllib.request

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
FIX = os.path.join(HERE, 'fpstest', 'fixtures')
UA = ('Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) '
      'Chrome/120 Mobile Safari/537.36')


def get(url, limit=512 * 1024):
    req = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept-Language': 'zh-CN,zh;q=0.9',
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    })
    with urllib.request.urlopen(req, timeout=20) as r:
        data = r.read(limit)
        return r.status, r.headers.get('Content-Type', ''), data


def main():
    dry = '--dry' in sys.argv
    q = 'DeepSeek是什么'
    if '--query' in sys.argv:
        q = sys.argv[sys.argv.index('--query') + 1]
    enc = urllib.parse.quote_plus(q)

    rss_url = 'https://cn.bing.com/search?q=%s&format=rss&setlang=zh-CN' % enc
    html_url = 'https://cn.bing.com/search?q=%s&setlang=zh-CN' % enc

    print('== 探测（query=%s）==' % q)
    ok_any = False
    for tag, url, name in (('rss', rss_url, 'bing-search.rss'),
                           ('html', html_url, 'bing-search.html')):
        try:
            code, ctype, data = get(url)
        except Exception as e:
            print('  [FAIL] %-4s %s' % (tag, e))
            continue
        text = data.decode('utf-8', 'replace')
        if name.endswith('.rss'):
            items = text.count('<item>')
            good = items >= 3
            print('  [%s] rss  HTTP %s  %d 字节  item=%d  %s'
                  % ('OK' if good else 'FAIL', code, len(data), items, ctype))
            if not good:
                print('        RSS 里没有 <item> —— 必应可能下架了 format=rss，'
                      '看兜底的网页版还能不能用')
        else:
            blocks = text.count('class="b_algo"')
            good = blocks >= 3
            print('  [%s] html HTTP %s  %d 字节  b_algo=%d  %s'
                  % ('OK' if good else 'FAIL', code, len(data), blocks, ctype))
        # 网页版太大，只留前 3 块（截出来的字节仍是原文）
        if name.endswith('.html') and blocks >= 3:
            head = ('<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">'
                    '<title>%s - 搜索</title></head><body><ol id="b_results">' % q)
            parts = text.split('class="b_algo"')
            chunks = []
            for blk in parts[1:4]:
                cut = blk.find('<li ', 1)
                chunks.append('class="b_algo"' + (blk[:cut] if cut > 0 else blk))
            text = head + '<li ' + '</li><li '.join(chunks) + '</li></ol></body></html>'
            data = text.encode('utf-8')
        ok_any = ok_any and True if 'good' not in dir() else ok_any
        if good and not dry:
            os.makedirs(FIX, exist_ok=True)
            with io.open(os.path.join(FIX, name), 'w', encoding='utf-8', newline='\n') as f:
                f.write(text)
            print('        -> 已写入 fixtures/%s（%d 字节）' % (name, len(data)))

    print()
    # ★ 轮次㉗：天气走结构化接口（d1.weather.com.cn），fixture 也要一起刷新 ——
    #   断言跑在真实报文上，对面字段一变套件立刻红。
    print('== 天气结构化接口（d1.weather.com.cn，北京 101010100）==')
    hdr = {'User-Agent': UA, 'Accept-Language': 'zh-CN,zh;q=0.9',
           'Referer': 'http://www.weather.com.cn/'}
    for tag, url, name, need in (
        ('index', 'https://d1.weather.com.cn/weather_index/101010100.html?_=%d' % int(time.time() * 1000),
         'weather-index.jsonp', ('dataSK', '"fc"', 'temp')),
        ('sk_2d', 'https://d1.weather.com.cn/sk_2d/101010100.html?_=%d' % int(time.time() * 1000),
         'weather-sk.jsonp', ('dataSK', 'temp', 'cityname')),
    ):
        try:
            req = urllib.request.Request(url, headers=hdr)
            with urllib.request.urlopen(req, timeout=15) as r:
                data = r.read(256 * 1024)
            text = data.decode('utf-8', 'replace')
            ok = all(k in text for k in need)
            print('  [%s] %-7s %6d 字节  含关键字段=%s' % ('OK' if ok else 'FAIL', tag, len(data), ok))
            if ok and not dry:
                os.makedirs(FIX, exist_ok=True)
                with io.open(os.path.join(FIX, name), 'w', encoding='utf-8', newline='\n') as f:
                    f.write(text)
                print('        -> 已写入 fixtures/%s' % name)
        except Exception as e:
            print('  [FAIL] %-7s %s' % (tag, e))

    print()
    if dry:
        print('== --dry：没有覆盖 fixture ==')
    else:
        print('== fixture 已刷新；**接着跑一遍 fpstest**（[23] 节就是拿新报文验解析器）==')
        r = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                           capture_output=True)
        out = r.stdout.decode('utf-8', 'replace')
        tail = [l for l in out.splitlines() if 'FAIL' in l or 'PASSED' in l]
        print('   fpstest:', ' / '.join(tail[-3:]) or ('退出码 %d' % r.returncode))
        return r.returncode
    return 0


if __name__ == '__main__':
    sys.exit(main())
