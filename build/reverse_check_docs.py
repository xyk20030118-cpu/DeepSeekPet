# -*- coding: utf-8 -*-
"""反向验证：**交付文档与事实一致**的判据必须能抓到漂移（轮次59）。

每一项都**故意破坏一次**，确认 `verify_docs.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里（中途异常也不能把文档留在被破坏的状态）。

本轮的判据是"防文档撒谎"，所以破坏方式就是**让文档撒谎**：
版本号退回上一版、产物名写旧、把某个新功能的段落删掉、把 md5 换成古董、
把接手入口指回已删的旧文档、让旧文档复活。
"""
import io
import hashlib
import os
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
VERIFY = os.path.join(HERE, 'verify_docs.py')
sys.path.insert(0, HERE)
from artifacts import dev_apk, rel_apk  # noqa: E402


def _dex8(apk):
    with zipfile.ZipFile(apk) as z:
        return hashlib.md5(z.read('classes.dex')).hexdigest()[:8]


# ★★ 轮次61：F 项的锚点**不能写死 md5** —— 那串每轮换包都会变，
#   写死的话下一次就会"找不到锚点 ⇒ 跳过 ⇒ 按未验证计" ⇒ 整个反向套件红
#   （本轮实测踩到：锚在 `34dc148d…` 上，而包早已换成 `e9e366d6…`）。
#   ⇒ 现在**运行时从刚编的 APK 现算**，锚点永远成立。
CUR_MD5_ROW = 'dev `%s…` / rel `%s…`' % (_dex8(dev_apk()), _dex8(rel_apk()))

README = os.path.join(ROOT, 'README.md')
DELIVER = os.path.join(ROOT, '交付说明.md')
BUILD = os.path.join(ROOT, 'BUILD_双版本.md')
OLD_DOC = os.path.join(ROOT, 'AGENT_ONBOARDING.md')


def read(p):
    return io.open(p, encoding='utf-8', newline='').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


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
print('== 反向验证：交付文档与事实一致（版本 / 产物名 / 功能 / 指纹 / 入口）==')
print()

# A. ★★ README 标题的版本号退回上一版（文档说 v7.0，实物是 v7.5）
# ★ 轮次86：从清单读当前版本，别再硬编码 v7.5（升版本后会 SKIP ⇒ 按未验证计即失败）
_CUR = '7.6'
try:
    import re as _re
    _mf = io.open(os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8').read()
    _m = _re.search(r'android:versionName="([^"]+)"', _mf)
    if _m:
        _CUR = _m.group(1)
except Exception:
    pass
_CURCODE = _CUR.replace('.', '')
_OLDC = '7.0'
_OLDCODE = '70'

results.append(break_and_check(
    'A. ★★README 标题版本退回 v%s' % _OLDC,
    README,
    '# DeepSeekPet 完整工程（v%s / versionCode %s）' % (_CUR, _CURCODE),
    '# DeepSeekPet 完整工程（v%s / versionCode %s）' % (_OLDC, _OLDCODE)))

# B. ★★ 交付说明头部版本退回
results.append(break_and_check(
    'B. ★★交付说明头部版本退回 v%s' % _OLDC,
    DELIVER,
    '**版本**：v%s（versionCode %s）' % (_CUR, _CURCODE),
    '**版本**：v%s（versionCode %s）' % (_OLDC, _OLDCODE)))

# C. ★ 版本历史表里把当前版本那一行删掉（等于"这一版没记录"）
results.append(break_and_check(
    'C. ★版本历史表删掉**当前**版本行（v%s）' % _CUR,
    BUILD,
    '| v%s | %s | %d%s |' % (_CUR, _CURCODE, 1000 + int(_CURCODE), ''),
    '| vX | %s | %d |' % (_CURCODE, 1000 + int(_CURCODE))))

# D. ★ 产物名写旧（用户照着找不到文件）
results.append(break_and_check(
    'D. ★产物名写回旧版本',
    README,
    'DeepSeekPet_v%s_rebuilt.apk' % _CUR,
    'DeepSeekPet_v%s_rebuilt.apk' % _OLDC))

# E. ★★ 把新功能的段落删掉（做了功能却没写 = 用户永远不知道）
results.append(break_and_check(
    'E. ★★交付说明删掉「点击音效」整节',
    DELIVER,
    '### 3. 点击音效（「形象 → 表情与素材」）',
    '### 3. （本节暂缺）'))

# F. ★★ md5 换成古董值（README 里那串早就不对了）
#    ★ 锚点用**运行时现算的当前值**（见文件头 CUR_MD5_ROW 的注释）
results.append(break_and_check(
    'F. ★★README 的 dex md5 换成古董值',
    README,
    CUR_MD5_ROW,
    'dev `5425520f…` / rel `d8361279…`'))

# G. ★★ 接手入口指回已删的旧文档
results.append(break_and_check(
    'G. ★★接手入口不再说是"唯一"文档',
    README,
    '唯一的接手文档',
    '一份文档'))

# H. ★ README 的文档索引行改掉（不再给 STATE.md 那一行）
results.append(break_and_check(
    'H. ★文档索引不给 STATE.md 行',
    README,
    '| `STATE.md` |',
    '| `XXX.md` |'))

# I. ★★ 让两份旧接手文档"复活"（留着就是悬空引用）
try:
    write(OLD_DOC, '# 旧文档（反向验证临时造出来）\n')
    bad = run_verify()
finally:
    try:
        os.remove(OLD_DOC)
    except OSError:
        pass
good = run_verify()
ok = (bad == 1 and good == 0)
print('  [%s] %-52s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', 'I. ★★旧接手文档复活', bad, good))
results.append(ok)

# J. ★★ 把本轮新增的功能关键词从 README 里抹掉（"本地模型"整段没写 = 用户永远不知道）
#    ★★ 轮次67 修正：这条原来只替换 README 里**唯一那一处** `llama.cpp`，注释还写着
#      "实测 README 里只有 1 处"。轮次67 在 README 里又提到了 `llama.cpp`（上游 issue 号
#      那一句），**于是只换一处就换不干净，判据照样绿 = 假绿**（本轮反向套件当场抓到）。
#      ⇒ 现在**把 README 里所有 `llama.cpp` 全部抹掉**（换了多少处还要打出来）。
#    ★ 这一段自己读写文件（没能复用 break_and_check，因为它只替换第一处）。
_orig_readme = read(README)
if 'llama.cpp' not in _orig_readme:
    print('  [SKIP] %-52s （找不到锚点，按未验证计）' % 'J. ★★README 里抹掉 llama.cpp 关键词')
    results.append(None)
else:
    _n = _orig_readme.count('llama.cpp')
    try:
        write(README, _orig_readme.replace('llama.cpp', 'llamaXcpp'))
        bad = run_verify()
        write(README, _orig_readme)
        good = run_verify()
    finally:
        write(README, _orig_readme)
    _ok = (bad == 1 and good == 0)
    print('  [%s] %-52s 破坏后=%d 恢复后=%d（抹掉 %d 处）'
          % ('OK' if _ok else 'FAIL', 'J. ★★README 里抹掉 llama.cpp 关键词', bad, good, _n))
    results.append(_ok)

# K. ★★★ 轮次65（收尾）：把"**一次都没在真机上跑过**"含糊成"验过了" ——
#    这正是本轮的铁律（文档不许撒谎）。本功能只有 arm64 包、模拟器是 x86_64，
#    离线断言全绿**不等于**设备上能用；写成"已实测"就是**最严重**的那种文档撒谎。
results.append(break_and_check(
    'K. ★★★交付说明把"没在真机上跑过"写成"跑过了"',
    DELIVER,
    '一次都没有在真机上跑过',
    '已经在真机上完整跑过一遍'))

# L. ★★ 两份清单漂了（交付说明那份漏了"不静默"那条判据 ⇒ 用户不知道该看什么）
#    ★ 锚点取 `DSPChatRoute 本地后端没能出话` —— 它在**两份清单**里各出现一次
#      （STATE.md 顶部 / 交付说明「七、5」），这里只改交付说明那一处。
results.append(break_and_check(
    'L. ★★交付说明的清单漏掉"不静默"判据',
    DELIVER,
    'DSPChatRoute 本地后端没能出话',
    'DSPChatRoute（无）'))

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
