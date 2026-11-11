# -*- coding: utf-8 -*-
"""点击音效（轮次57）。

用户原话：「**新增点击音效**」。逐条拍板：**只戳桌宠**（单击头/身/尾）/ **运行时合成**
（不用音频素材）/ **默认开 + 可关可调**（音色 3 档、音量 3 档）。

## 为什么是"运行时合成"

工程里**一个音频文件都没有**（`assets/` 只有 webp/json，`res/` 只有图标与 xml）。
为一个 ~90ms 的"啵"引一个二进制素材，要连带处理交付包/角色包清单、授权声明，
而且断言只能验"文件在不在"。而**短音的波形本来就是纯函数**：
不用素材、不用联网、可离线断言、还能让用户选音色。

## 分节

  一、★ 音源 = 运行时合成（纯类 `ClickSound` + 伴生播放类 `ClickPlayer` 分开，纯类零 Android）
  二、★★ 波形本身（淡入淡出防"啪" / 衰减 / 三档音色互不相同 / 静音与夹紧）—— **fpstest 真跑**
  三、★ 触发点唯一，且就挂在"戳桌宠"那一刻
  四、★ 默认开 + 音色/音量可调 + 改完**当场试听**
  五、★ 播放失败一律静默（音效不许让桌宠崩或卡）+ 服务销毁释放音轨
  六、设备脚本（可复跑，且只认模拟器）
  七、产物：新代码真的进了 dev 包

反向破坏见 reverse_check_click_sound.py。
"""
import io
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
from artifacts import dev_apk  # noqa: E402
DEV_APK = dev_apk()

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def _audio_files(root):
    """递归找音频文件（断言"没引素材"用）"""
    out = []
    for dirpath, _dirs, files in os.walk(root):
        for f in files:
            if f.lower().endswith(('.wav', '.ogg', '.mp3', '.m4a', '.aac', '.flac')):
                out.append(os.path.join(dirpath, f))
    return out


def method_body(src, sig):
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(sig))
    return src[i:j + 6] if j > 0 else src[i:]


CS = java('ClickSound.java')
CP = java('ClickPlayer.java')
PV = java('PetView.java')
PR = java('Prefs.java')
MA = java('MainActivity.java')
FS = java('FloatingService.java')
FPS = read(os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java'))
RUNBAT = read(os.path.join(HERE, 'fpstest', 'run.bat'))

print('=== 一、★ 音源 = 运行时合成（纯类 + 伴生播放类分开）===')
check('★★ 波形在**纯类** ClickSound 里（零 Android 依赖 ⇒ fpstest 能真跑）',
      'public final class ClickSound' in CS and 'android' not in CS)
check('★★ 播放那半边在伴生的 ClickPlayer 里（AudioTrack 只能上设备）',
      'public final class ClickPlayer' in CP and 'android.media.AudioTrack' in CP)
check('★ 全工程**没有**新增任何音频素材（assets/res 里不许出现 wav/ogg/mp3）',
      not [p for p in _audio_files(os.path.join(ROOT, 'assets'))]
      and not [p for p in _audio_files(os.path.join(ROOT, 'res'))])
check('★ fpstest 真的编了纯类、并且有 [31] 一节',
      'ClickSound.java' in RUNBAT and '[31] ClickSound' in FPS)

print()
print('=== 二、★★ 波形本身（fpstest 真跑）===')
pcm = method_body(CS, 'public static short[] pcm(int style, float volume)')
check('★★ 淡入淡出都在（起手那一跳听不见、收尾正好落在 0 —— 否则会"啪"一声）',
      'FADE_IN' in CS and 'FADE_OUT' in CS
      and 'if (i < FADE_IN) env *= (i + 1) / (double) FADE_IN;' in pcm
      and 'if (rem < FADE_OUT) env *= rem / (double) FADE_OUT;' in pcm)
check('★★ 指数衰减包络（短促音的自然形状），三档音色各有自己的参数',
      'Math.exp(-t / 0.020)' in pcm and 'Math.exp(-t / 0.006)' in pcm
      and 'Math.exp(-t / 0.090)' in pcm and 'Math.exp(-t / 0.060)' in pcm)
check('★ "啵"的听感来自音高下滑（900→380Hz），不是随手一个正弦',
      '380.0 + (900.0 - 380.0) * Math.exp(-t / 0.012)' in pcm)
check('★★ 音量 0 = 全零静音（**不是不生成**：长度与形状照旧）',
      'if (n <= 0 || vol <= 0f) return out;' in pcm)
check('★★ 音量夹在 0~1、采样夹在 int16（脏输入不许把喇叭搞爆）',
      'public static float clamp01(float v)' in CS
      and 'if (v > 1.0) v = 1.0; else if (v < -1.0) v = -1.0;' in pcm)
check('★ 非法音色被夹到默认档（数组下标就在它后面）',
      'public static int styleIndex(int style)' in CS
      and 'if (style < 0 || style >= STYLE_COUNT) return STYLE_POP;' in CS)
check('★ 时长/音色名/音量名都是纯函数（设置页那一行显示要用）',
      'public static int durationMs(int style)' in CS
      and 'public static String styleName(int style)' in CS
      and 'public static String volumeName(float v)' in CS)

print()
print('=== 三、★ 触发点：只挂在"戳桌宠"那一刻 ===')
poke = method_body(PV, 'public void poke()')
check('★★ 挂在 PetView.poke()（单击头/身/尾那一刻，与 click 场景同一个时机）',
      'ClickPlayer.playFromPrefs(getContext());' in poke)
check('★★ 放在最前面（手感反馈要"立刻"，不能等动作或说话准备好）',
      poke.find('ClickPlayer.playFromPrefs') < poke.find('playSceneOnce'))
check('★★ 唯一入口：桌宠那边只调 playFromPrefs（直接调 play 就会绕过开关）',
      'public static boolean playFromPrefs(android.content.Context ctx)' in CP
      and PV.count('ClickPlayer.playFromPrefs') == 1 and PV.count('ClickPlayer.play(') == 0)
check('★ 开关关掉时"一行就返回"（不在 poke 里散落判断）',
      'if (!Prefs.clickSoundOn(ctx)) return false;' in CP)

print()
print('=== 四、★ 默认开 + 音色/音量可调 + 当场试听 ===')
check('★★ 默认**开**（用户要的就是这个功能；默认关他还要自己翻设置才听得到）',
      'getBoolean(KEY_CLICK_SOUND, true)' in PR)
check('★ 三个偏好键都在（开关 / 音色 / 音量）',
      'KEY_CLICK_SOUND = "click_sound_on"' in PR
      and 'KEY_CLICK_STYLE = "click_sound_style"' in PR
      and 'KEY_CLICK_VOLUME = "click_sound_volume"' in PR)
check('★ 写入也走 clamp/styleIndex（脏值进不去）',
      'putInt(KEY_CLICK_STYLE, ClickSound.styleIndex(style))' in PR
      and 'putFloat(KEY_CLICK_VOLUME, ClickSound.clamp01(v))' in PR)
check('★★ 设置页三行都在（开关 / 音色 / 音量）',
      '点击音效（戳桌宠时响一声）' in MA
      and '"\\uD83C\\uDFB5 音效音色：" + ClickSound.styleName(' in MA
      and '"\\uD83D\\uDD09 音效音量：" + ClickSound.volumeName(' in MA)
check('★★ 改完**当场试听**（声音这种设置"调完还得退出去戳一下"等于没法调）',
      'private void pickClickStyle()' in MA and 'private void pickClickVolume()' in MA
      and method_body(MA, 'private void pickClickStyle()').count('ClickPlayer.play(') == 1
      and method_body(MA, 'private void pickClickVolume()').count('ClickPlayer.play(') == 1
      and '（点一下即试听）' in MA)
check('★ 打开开关时也响一声（让用户立刻确认"就是它"）',
      'if (checked) ClickPlayer.play(' in MA)

print()
print('=== 五、★ 播放失败静默 + 释放音轨 ===')
check('★★ 播放全程 try/catch（设备没音频输出 / 被独占 / 建不起音轨都不许让桌宠崩）',
      '} catch (Throwable t) {' in CP and 'return false;' in CP)
check('★ 失败只打一行日志（不弹窗、不 Toast —— 音效失败不该打扰用户）',
      'Log.w("DSPClick"' in CP)
check('★★ 设备侧证据：成功时打 style/vol/len（设备脚本按它判"真的走到播放了"）',
      '"DSPClick"' in CP and 'play style=' in CP and 'vol=' in CP and 'len=' in CP)
check('★ 用提示音用途（不抢媒体音频焦点 ⇒ 不打断用户在听的音乐）',
      'USAGE_ASSISTANCE_SONIFICATION' in CP)
check('★ MODE_STATIC 复用音轨（省掉每次点击都新建/释放原生资源）',
      'MODE_STATIC' in CP and 'reloadStaticData()' in CP)
check('★ 服务销毁时释放缓存音轨（别把原生资源漏在那儿）',
      'public static void releaseAll()' in CP
      and 'ClickPlayer.releaseAll()' in method_body(FS, 'public void onDestroy()'))

print()
print('=== 六、设备脚本（可复跑，且只认模拟器）===')
DEV_SCRIPT = os.path.join(HERE, 'verify_click_sound_ui.py')
check('verify_click_sound_ui.py 存在', os.path.isfile(DEV_SCRIPT))
if os.path.isfile(DEV_SCRIPT):
    ds = read(DEV_SCRIPT)
    check('★ 它钉死模拟器 + 复用现成的"戳桌宠"驱动（不自己重写一套窗口几何）',
          'DEV = V.DEV' in ds and 'verify_scene_exits_ui' in ds)
    check('★★ 它防"只看源码不看屏幕"：按 dump 里的文字判设置页三行在不在',
          'S.all_texts()' in ds and '音效音色' in ds)
    check('★★ 它有**反向对照**：关掉开关后戳桌宠**不许**再出现 DSPClick 日志',
          "S.pref_set('click_sound_on', 'false')" in ds
          and "len(off_recs) == 0" in ds)
    rc, out = (lambda r: (r.returncode, r.stdout.decode('utf-8', 'replace')))(
        subprocess.run([sys.executable, DEV_SCRIPT, '--selftest'], capture_output=True))
    check('verify_click_sound_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('=== 七、产物：新代码真的进了 dev 包 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('ClickSound', 'ClickPlayer', 'DSPClick', '点击音效（戳桌宠时响一声）',
              '音效音色', '音效音量'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（点击音效：运行时合成 / 波形防啪 / 触发点 / 开关与试听 / 失败静默）')
