# -*- coding: utf-8 -*-
"""复读交付包：源码包里必须带**本轮（与近几轮）新增的断言套件**与**修好的 run_all_tests.bat**。

★ 轮次⑯/⑲ 的教训：源码 zip 里的 .bat 曾经是坏的那一版（纯 LF），
  而"改完代码忘了重打包"这种事只能从 zip 里复读才发现 —— 所以每次都读一遍。
★ 轮次㉖ 的教训：这个脚本自己也曾**停在旧指纹上**（还在找 5a3e87cc… 那对值），
  于是它一直报"不是新的"却没人看。⇒ 判据改成**按语义查**：
  ① 近几轮的套件/源码文件在不在；② `.bat` 是不是 CRLF + 纯 ASCII；
  ③ `verify_zips.py` 是不是**现算** EXPECT（不再手抄常量）。

用法：每次 `pack_delivery.py`（或 `go_pack.py`）重打三个 zip 之后跑一次。
"""
import io
import re
import sys
import zipfile

# ★ 轮次㊼：源码包的路径不再硬编码版本号（升 7.5 时实测：写死 `…v7.0_项目移植包.zip`
#   会让这个脚本去查**上一版**的包，报"没打进套件"这种假红）—— 走 artifacts 的单一来源。
from artifacts import zip_paths as _zip_paths

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ZIP = _zip_paths()[2]
zf = zipfile.ZipFile(ZIP)
names = zf.namelist()

bad = []


def find(suffix):
    hit = [n for n in names if n.replace('\\', '/').endswith(suffix)]
    return hit[0] if hit else None


# ① 文件在不在：近三轮的产物（㉔ 贴边 / ㉕ 形象随包走 / ㉖ 联网检索）
FILES = [
    'src/com/deepseek/pet/ContentInset.java',
    'src/com/deepseek/pet/ContentInsetProbe.java',
    'src/com/deepseek/pet/WebSearch.java',
    'src/com/deepseek/pet/WebFetch.java',
    'src/com/deepseek/pet/WebPrompt.java',
    'src/com/deepseek/pet/WebClient.java',
    'build/verify_edge_snap.py',
    'build/reverse_check_edge_snap.py',
    'build/verify_appearance_pack.py',
    'build/reverse_check_appearance_pack.py',
    'src/com/deepseek/pet/Weather.java',
    'src/com/deepseek/pet/WeatherCities.java',
    'src/com/deepseek/pet/QueryClean.java',
    'src/com/deepseek/pet/Json.java',
    'src/com/deepseek/pet/SearchProvider.java',
    'build/verify_web_search.py',
    'build/reverse_check_web_search.py',
    'build/verify_web_smart.py',
    'build/reverse_check_web_smart.py',
    'build/verify_web_provider.py',
    'build/reverse_check_web_provider.py',
    'build/verify_web_weather.py',
    'build/verify_web_provider_ui.py',
    'build/gen_city_table.py',
    'build/probe_web.py',
    'build/fpstest/fixtures/bing-search.rss',
    'build/fpstest/fixtures/bing-search.html',
    'build/fpstest/fixtures/weather-index.jsonp',
    'build/fpstest/fixtures/kimi-search-pro.json',
    'build/fpstest/fixtures/kimi-fetch.json',
    'build/verify_zips.py',
    'build/run_all_tests.bat',
    'build/verify_features.py',
    'README.md',
    '角色包格式.md',
]
print('=== ① 文件在不在 ===')
for suffix in FILES:
    ok = find(suffix) is not None
    print('  %-52s %s' % (suffix, '在' if ok else '!! 缺'))
    if not ok:
        bad.append(suffix)

# ② run_all_tests.bat 的格式（CRLF + 纯 ASCII）与套件号
print()
print('=== ② run_all_tests.bat ===')
n = find('build/run_all_tests.bat')
if n:
    data = zf.read(n)
    cr, lf = data.count(b'\r'), data.count(b'\n')
    nonascii = sum(1 for b in data if b > 127)
    ok = (cr == lf and nonascii == 0)
    print('  CR=%d LF=%d NonASCII=%d  %s' % (cr, lf, nonascii, 'OK' if ok else '!! 不合格'))
    if not ok:
        bad.append('run_all_tests.bat 格式')
    # ★ 套件号**不许写死**（第一版盯的是 `/41`，本轮加到 43 套它当场报"没跟上" ——
    #   又一个"手抄的常量会静默过期"）。改成按语义查：所有标题的 `n/N` 必须同分母，
    #   且**最后一个标题的序号 == 总数**（少写一条就会露馅）。
    hdrs = [(int(a), int(b)) for a, b in re.findall(rb'=+ (\d+)/(\d+) ', data)]
    total = hdrs[0][1] if hdrs else 0
    ok_num = bool(hdrs) and all(b == total for _, b in hdrs) and hdrs[-1][0] == total
    print('  套件编号自洽（%d 套，最后一条=%d）: %s'
          % (total, hdrs[-1][0] if hdrs else 0, 'OK' if ok_num else '!! 不一致'))
    if not ok_num:
        bad.append('run_all_tests.bat 套件号')
    for s in (b'verify_web_search.py', b'reverse_check_web_search.py',
              b'verify_edge_snap.py', b'verify_appearance_pack.py',
              b'verify_web_smart.py', b'reverse_check_web_smart.py'):
        print('  引用 %-30s %s' % (s.decode(), 'OK' if s in data else '!! 缺'))
        if s not in data:
            bad.append(s.decode())

# ③ verify_zips.py 的期望值必须是"现算"，不许手抄
print()
print('=== ③ verify_zips.py 的判据 ===')
n = find('build/verify_zips.py')
if n:
    data = zf.read(n)
    checks = [
        ('导入 artifacts 的 dev_apk/rel_apk',
         b'from artifacts import' in data and b'dev_apk()' in data and b'rel_apk()' in data),
        ('现场读 classes.dex 求 md5', b'def dex_md5_of' in data),
        ('EXPECT 由 APK 现算', b"EXPECT = {" in data and b'dex_md5_of(' in data),
        ('没有残留的手抄 32 位 md5 常量',
         not re.search(rb"'[0-9a-f]{32}'", data)),
    ]
    for label, ok in checks:
        print('  %-34s %s' % (label, 'OK' if ok else '!! 不合格'))
        if not ok:
            bad.append('verify_zips: ' + label)

# ④ verify_features.py 里本轮 8 条联网探针
print()
print('=== ④ verify_features.py 的联网探针 ===')
n = find('build/verify_features.py')
if n:
    data = zf.read(n)
    for w in ('web_search_on', 'shouldSearch', 'cn.bing.com/search?q=',
              'format=rss', 'DSWeb', '联网检索中'):
        ok = w.encode('utf-8') in data
        print('  %-24s %s' % (w, 'OK' if ok else '!! 缺'))
        if not ok:
            bad.append('verify_features: ' + w)

# ⑤ ★★ 密钥扫描：源码包里**不许出现任何 Key**
# ------------------------------------------------------------
# 轮次㉘ 的真实泄漏：设备验证把用户的搜索 Key 写进 pref → `pf.py` 把它 pull 到
# `build/_prefs.xml` → **源码 zip 会把整个 build/ 打进去** ⇒ Key 跟着交付包发出去了。
# ⇒ 这里做两道：① 扫全包（只扫小文本条目，避免把 300 MB 的模型读进来）；
#              ② **任何** pref 副本（`*prefs.xml` / `shared_prefs/`）都必须已被清掉 ——
#                 轮次㉞ 又中了一次同类：新写的 `pref_set` 把设备 pref 整份拷到
#                 `build/_uitest/_p3prefs.xml`（里面有真 Key）。判据**不看 key 形状**，
#                 因为搜索服务是配置驱动的，Token 可以是任意串。
print()
print('=== ⑤ 密钥扫描（交付包里不许有 Key）===')
SECRET = re.compile(rb'sk-[A-Za-z0-9_\-]{16,}')
leaks = []
scanned = 0
for e in zf.infolist():
    if e.file_size > 2 * 1024 * 1024 or e.file_size == 0:
        continue
    name = e.filename.replace('\\', '/')
    if not name.endswith(('.xml', '.json', '.txt', '.md', '.py', '.java', '.bat', '.log')):
        continue
    try:
        blob = zf.read(e)
    except Exception:
        continue
    scanned += 1
    m = SECRET.search(blob)
    if m:
        leaks.append(name)
print('  扫了 %d 个小文本条目' % scanned)
if leaks:
    print('  !! 发现疑似密钥: %s' % leaks[:8])
    bad.append('源码包里有密钥: %s' % leaks[:3])
else:
    print('  没有 sk- 形式的密钥 OK')
n = find('build/_prefs.xml')
print('  build/_prefs.xml（设备 pref 副本）: %s' % ('**还在包里** —— 必须清掉' if n else '不在包里 OK'))
if n:
    bad.append('_prefs.xml 不该进源码包')
# ★ 轮次㉞：上面那条只看**这一个文件名**，而设备脚本的临时 pref 副本还可能落在别处 ——
#   本轮就抓到一次实例：`build/_uitest/_p3prefs.xml`（新写的 `pref_set` 把设备 pref
#   整份拷到了 build/ 下），里面有**用户的真 Key**。所以判据改成"**任何** pref 副本都不许进包"，
#   而且**不看 key 的形状**：上面那条 SECRET 正则只认 `sk-` 开头，
#   而搜索服务/自建 API 的 Token 完全可能不长这样（配置驱动，用户可以填任意串）。
pref_copies = []
for e in zf.infolist():
    nm = e.filename.replace('\\', '/')
    if nm.rsplit('/', 1)[-1].endswith('prefs.xml') or '/shared_prefs/' in nm:
        pref_copies.append(nm)
print('  任何 *prefs.xml / shared_prefs/（设备 pref 副本）: %s'
      % (('**还在包里** —— 必须清掉: %s' % pref_copies[:4]) if pref_copies else '不在包里 OK'))
if pref_copies:
    bad.append('pref 副本不该进源码包: %s' % pref_copies[:3])

# ★★ 轮次㊳：源码包里**不许有可重建的测试产物**。
#   待办里"把打包后清 build/ 测试产物固化成脚本"挂了两轮（轮次⑳ 手工清省 55 MB、
#   轮次㊱/㊲ 又各手工清 8 MB）—— 现在清理脚本已由 `pack_delivery.py` 自动调用，
#   这里再钉一条判据：**万一清理没跑（或又有新目录忘了登记），打包复验当场报红**。
#   ★ 判据只列"跑一次就重建"的产物；`_*.log`（逐轮物证）/`monitor_shots`（截图证据）/
#     `toolchain*`/`charpack_src`/`model`（原料）**必须留着**，不在下面的清单里。
JUNK = ('/__pycache__/', '/fpstest/out/', '/_rttest/out/', '/_rttest/cache/', '/_rttest/tmp/',
        '/_uitest/', '/work_win/', '/out_win/', '/cpidtest/out/')
junk = []
for e in zf.infolist():
    nm = e.filename.replace('\\', '/')
    if nm == './':
        continue
    for j in JUNK:
        if j in nm:
            junk.append(nm)
            break
print('  可重建的测试产物（__pycache__ / fpstest.out / _rttest.{out,cache,tmp} / _uitest / …）: %s'
      % (('**还在包里** —— 打包前该跑 `build/clean_test_artifacts.py`: %s' % junk[:4])
         if junk else '不在包里 OK'))
if junk:
    bad.append('测试产物不该进源码包（%d 条）: %s' % (len(junk), junk[:3]))

print()
if bad:
    print('!! %d 项不合格: %s' % (len(bad), bad))
    sys.exit(1)
print('==> 源码包复读全部 OK')