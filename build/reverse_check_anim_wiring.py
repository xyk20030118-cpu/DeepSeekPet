# -*- coding: utf-8 -*-
"""反向验证：verify_anim_wiring.py 的断言**必须能抓到回潮**。

一个"永远返回 OK"的断言和"真的在守护"的断言，从输出上看一模一样。
所以每一项都**故意破坏一次**，确认 verify_anim_wiring.py 退出码变 1，再恢复确认回到 0。

★ 恢复放在 finally 里：中途异常也不能把源码/素材留在被破坏的状态。
★ 用**二进制**读写：Java 行尾（有的文件是 CRLF）与 JSON 排版必须逐字节还原。
★ 找不到锚点 = 按"未验证"计（不是通过）—— 源码改了措辞就必须同步改这里。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_anim_wiring.py')
PACK = r'D:\petport_lab\petbuild\src\com\deepseek\pet\PetAnimPack.java'
FS = r'D:\petport_lab\petbuild\src\com\deepseek\pet\FloatingService.java'
ANIM_JSON = r'D:\petport_lab\petbuild\assets\pet_anim.json'
LINES_JSON = r'D:\petport_lab\petbuild\assets\anim_lines.json'
FULL_1X = r'D:\petport_lab\petbuild\model\pack_full_1x.json'   # 默认角色包的完整配置（106 个动作）


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    orig = io.open(path, 'rb').read()
    ob = old.encode('utf-8')
    if ob not in orig:
        print('  [SKIP] %-52s （找不到锚点，按未验证计）' % label)
        return False
    try:
        io.open(path, 'wb').write(orig.replace(ob, new.encode('utf-8'), 1))
        bad = run_verify()
        io.open(path, 'wb').write(orig)
        good = run_verify()
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-52s 破坏后=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：动画接线断言')
results = []

# ── 加载器（PetAnimPack）──
results.append(break_and_check(
    'A. load() 不再读 events 节点',
    PACK, 'root.optJSONObject("events")', 'root.optJSONObject("events_off")'))
results.append(break_and_check(
    'B. moves 的 {"name":…} 单动画分支被删',
    PACK, 'a.optString("id", a.optString("name", null))', 'a.optString("id", null)'))
results.append(break_and_check(
    'C. clipByName 退回"只查轮播池"',
    PACK, 'return master.get(id);', 'return null;'))
results.append(break_and_check(
    'D. pickMove 不再用 moveClips',
    PACK, 'if (!moveClips.isEmpty()) return moveClips.get(rnd.nextInt(moveClips.size()));', ''))
results.append(break_and_check(
    'E. ★语义边界：事件动画被并进待机轮播池',
    PACK, 'addUniq(uniq, moveDefault);',
    'addUniq(uniq, moveDefault);\n        for (Group g : eventGroups.values()) addUniq(uniq, g.clips);'))
results.append(break_and_check(
    'F. allNames() 退回只返回轮播池',
    PACK, 'public java.util.List<String> allNames() { return allNames; }',
    'public java.util.List<String> allNames() { return allIds(); }'))

# ── 触发出口（FloatingService）──
results.append(break_and_check(
    'G. 主动搭话的碎碎念出口被删（少一处）',
    FS, 'playEventAnim("whisper");', ''))
results.append(break_and_check(
    'H. 工作场景的 workStatus 出口被删',
    FS, 'playEventAnim("workStatus")', 'playEventAnim("workStatusOff")'))
results.append(break_and_check(
    'I. 余额刷新不再调用 maybeBalanceAnim',
    FS, 'maybeBalanceAnim(bal);', ''))
results.append(break_and_check(
    'J. 余额档位名与素材漂移（钱袋满溢→改名）',
    FS, '"余额-钱袋满溢"', '"余额-钱袋满溢X"'))

# ── 素材一致性 ──
results.append(break_and_check(
    'K. APK 精简配置漏登记那唯一的动作（待机呼吸休闲）',
    ANIM_JSON, '"待机呼吸休闲"', '"待机呼吸休闲_off"'))
results.append(break_and_check(
    'L. APK 精简台词少一个动作的台词（待机呼吸休闲）',
    LINES_JSON, '"待机呼吸休闲":', '"待机呼吸休闲_off":'))
results.append(break_and_check(
    'M. 默认角色包完整配置漏登记一个动作（三球抛接）',
    FULL_1X, '"三球抛接"', '"三球抛接_off"'))
results.append(break_and_check(
    'N. APK 精简配置不再是 2.0（去掉 scenes 节点）',
    ANIM_JSON, '"scenes"', '"_scenes"'))

print()
print('=' * 62)
skipped = [r for r in results if r is None]
bad = [r for r in results if r is False]
if bad or skipped:
    print('%d 项没被抓到 / %d 项跳过（跳过按未验证计）' % (len(bad), len(skipped)))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (len(results), len(results)))
print('ALL REVERSE CHECKS PASSED')
