# -*- coding: utf-8 -*-
"""反向验证：**点击音效**的断言必须能抓到回潮（轮次57）。

每一项都**故意破坏一次**，确认 `verify_click_sound.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 finally 里（中途异常也不能把源码留在被破坏的状态）。

本轮最值得盯的四条：
  * **A（淡入淡出）** —— 摘掉就"啪"一声（用户听得出、波形上也看得见）；
  * **E（触发点）** —— 从 `poke()` 摘掉 = 整个功能不存在，而设置页一切照旧；
  * **F（默认开）** —— 改成默认关，用户装完**听不到任何声音**却以为功能坏了；
  * **K（反向对照）** —— 设备脚本少了"关掉后不许响"，把开关写成空实现也能过。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
VERIFY = os.path.join(HERE, 'verify_click_sound.py')

CS = os.path.join(SRC, 'ClickSound.java')
CP = os.path.join(SRC, 'ClickPlayer.java')
PV = os.path.join(SRC, 'PetView.java')
PR = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
FS = os.path.join(SRC, 'FloatingService.java')
RUNBAT = os.path.join(HERE, 'fpstest', 'run.bat')
DEVUI = os.path.join(HERE, 'verify_click_sound_ui.py')


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
print('== 反向验证：点击音效（波形 / 触发点 / 开关 / 试听 / 失败静默）==')
print()

# A. ★★ 淡入被摘（起手那一跳会"啪"一声）
results.append(break_and_check(
    'A. ★★摘掉起手淡入',
    CS,
    'if (i < FADE_IN) env *= (i + 1) / (double) FADE_IN;',
    ';'))

# A2. ★★ 淡出被摘（收尾截断在非零值，同样"啪"）
results.append(break_and_check(
    'A2. ★★摘掉收尾淡出',
    CS,
    'if (rem < FADE_OUT) env *= rem / (double) FADE_OUT;',
    ';'))

# B. ★★ 音量 0 不再走"全零静音"分支
results.append(break_and_check(
    'B. ★★音量 0 不再当静音处理',
    CS,
    'if (n <= 0 || vol <= 0f) return out;',
    'if (n <= 0) return out;'))

# C. ★★ 采样不再夹在 int16（脏音量会把喇叭搞爆）
results.append(break_and_check(
    'C. ★★采样不再夹紧',
    CS,
    'if (v > 1.0) v = 1.0; else if (v < -1.0) v = -1.0;',
    ';'))

# D. ★★ 非法音色不再兜底（数组下标就在它后面）
results.append(break_and_check(
    'D. ★★非法音色不再兜底',
    CS,
    'if (style < 0 || style >= STYLE_COUNT) return STYLE_POP;',
    'return style;'))

# E. ★★ 触发点从 poke() 摘掉（整个功能不存在，界面照旧）
results.append(break_and_check(
    'E. ★★戳桌宠不再触发音效',
    PV,
    '        ClickPlayer.playFromPrefs(getContext());\n',
    ''))

# F. ★★ 默认改成"关"（装完听不到，用户以为坏了）
results.append(break_and_check(
    'F. ★★点击音效默认改成关',
    PR,
    'getBoolean(KEY_CLICK_SOUND, true)',
    'getBoolean(KEY_CLICK_SOUND, false)'))

# G. ★★ 音色/音量改完不再试听（声音设置没法调）
results.append(break_and_check(
    'G. ★★改音色不再当场试听',
    MAIN,
    'ClickPlayer.play(which, Prefs.clickSoundVolume(MainActivity.this));',
    ';'))

# H. ★ 播放失败不再打日志（设备脚本再也看不见"到底有没有走到播放"）
results.append(break_and_check(
    'H. ★播放失败不再打日志',
    CP,
    'android.util.Log.w("DSPClick"',
    'android.util.Log.w("DSPClickX"'))

# I. ★ 服务销毁不再释放音轨
results.append(break_and_check(
    'I. ★服务销毁不再释放音轨',
    FS,
    'try { ClickPlayer.releaseAll(); } catch (Throwable ignore) {}',
    ';'))

# J. ★ fpstest 不再编纯类（波形就没被真跑过）
results.append(break_and_check(
    'J. ★fpstest 不再编 ClickSound',
    RUNBAT,
    'ClickSound.java',
    ''))

# K. ★★ 设备脚本丢掉"关掉后不许响"的反向对照
results.append(break_and_check(
    'K. ★★设备脚本丢掉反向对照',
    DEVUI,
    "S.pref_set('click_sound_on', 'false')",
    "S.pref_set('click_sound_on', 'true')"))

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
