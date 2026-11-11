# -*- coding: utf-8 -*-
"""设备验证：**点击音效**真的挂在戳桌宠上、开关真的管得住（轮次57）。

## 判据（★ 正反两面都要有证据）

  a) 设置页「表情与素材」里三行都在（点击音效开关 / 音效音色 / 音效音量）；
  b) 改音色 ⇒ 当场试听 ⇒ 日志 `DSPClick: play style=叮 …`；偏好真的落盘；
  c) 改音量 ⇒ 日志里 `vol=0.90`；偏好真的落盘；
  d) **戳桌宠**（PetView.poke）⇒ 出现 `DSPClick` 日志（正面：真的走到播放了）；
  e) ★★ **关掉开关**再戳 ⇒ **不再出现**新的 `DSPClick`（反面：开关真的管得住 ——
     只测"开着会响"的话，把开关写成空实现也能过）；
  f) 收尾把开关/音色/音量还原成默认（别把模拟器留在关着的状态）。

## ★ 诚实边界（写清楚，别让下一个窗口以为验过耳朵）

`DSPClick: play …` 只能证明：**PCM 合成成功 + AudioTrack 建起来且 play() 没抛异常**。
"到底好不好听 / 音量合不合适"**必须人耳**（模拟器也未必有音频输出）。
所以这里不写"音量真的变大了"这种没法自动判的话。

用法：
    python build/verify_click_sound_ui.py --install
    python build/verify_click_sound_ui.py
    python build/verify_click_sound_ui.py --selftest
"""
import os
import re
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402
import verify_char_ui as V           # noqa: E402
import verify_char_switch_ui as S    # noqa: E402
import verify_scene_exits_ui as X    # noqa: E402  （借它成熟的"戳桌宠"驱动）

DEV = V.DEV
PKG = V.PKG

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

CLICK_RE = re.compile(r'play style=(\S+)\s+vol=([0-9.]+)\s+len=(\d+)\s+ms=(\d+)')


def click_records(log_text):
    """从 logcat 里捞出所有 `DSPClick play …` → [(音色, 音量, 样本数, 毫秒)]"""
    out = []
    for line in (log_text or '').splitlines():
        if 'DSPClick' not in line or 'play style=' not in line:
            continue
        m = CLICK_RE.search(line)
        if m:
            out.append((m.group(1), float(m.group(2)), int(m.group(3)), int(m.group(4))))
    return out


def rows_present(texts):
    """设置页三行都在吗 → (开关, 音色, 音量)"""
    j = '\n'.join(texts or [])
    return ('点击音效' in j, '音效音色' in j, '音效音量' in j)


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    log = ('10-14 12:00:00.000 1 1 I DSPClick: play style=啵 vol=0.60 len=3969 ms=90\n'
           '10-14 12:00:01.000 1 1 I DSPClick: play style=叮 vol=0.90 len=11466 ms=260\n'
           '10-14 12:00:02.000 1 1 I SomeOther: 无关日志\n')
    recs = click_records(log)
    t('捞出两条播放记录', len(recs) == 2)
    t('音色/音量/样本数/毫秒都解出来了',
      recs[0] == ('啵', 0.60, 3969, 90) and recs[1] == ('叮', 0.90, 11466, 260))
    t('无关日志不算数', all(r[0] in ('啵', '叮', '哒') for r in recs))
    t('反例：没有播放日志就是空表', click_records('DSPClick: play 失败') == [])
    t('★ 设置页三行判据', rows_present(['🔊 点击音效（戳桌宠时响一声）', '🎵 音效音色：啵',
                                        '🔉 音效音量：中']) == (True, True, True))
    t('反例：缺音色行要判否', rows_present(['点击音效', '音效音量']) == (True, False, True))
    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def logs():
    return V.out('shell', 'logcat', '-d', '-s', 'DSPClick:I')


def open_memes_section():
    """跳到「表情与素材」（dsp_section=2）—— 比盲点导航可靠"""
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity', '--ei', 'dsp_section', '2')
    time.sleep(5.5)
    return any('表情与素材' in (t or '') for t in S.all_texts())


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


def poke_pet():
    """回桌面 → 确保桌宠**真的在** → 戳一下身子中间。

    ★★ `S.pref_set` 是"force-stop → 改 XML → 复读校验"⇒ 改完开关 App 已经被杀了、
       桌宠窗口也没了，这时戳它**什么都不会发生**。
       第一版就是这么"假绿"的：反面那节"没日志"其实是"根本没宠物"可戳
       —— 而那条恰恰是整轮最关键的判据（开关到底管不管得住）。
       所以这里先确认窗口在，不在就重新拉起并等它回来。
    """
    for _ in range(3):
        if X.pet_frame() is not None:
            break
        V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
        time.sleep(2.0)
        X.wait_pet_frame(20)
    if not X.show_pet():
        return False
    time.sleep(0.8)
    return X.tap_part(0.5)


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-40:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能改 pref')
        return 1
    V.sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')

    # 前置：默认态（开关开、啵、中）
    S.pref_set('click_sound_on', 'true')
    S.pref_set('click_sound_style', '0')
    S.pref_set('click_sound_volume', '0.6')

    try:
        # ── a) 设置页三行 ──
        print()
        print('== a) 设置页三行 ==')
        check('★ 进到「表情与素材」', open_memes_section())
        got = rows_present(S.all_texts())
        check('★★ 三行都在（开关 / 音色 / 音量）', got == (True, True, True),
              [t[:34] for t in S.all_texts() if '音效' in (t or '') or '点击' in (t or '')])

        # ── b) 改音色 ⇒ 当场试听 ──
        print()
        print('== b) 音色：改一档就试听 ==')
        V.sh('shell', 'logcat', '-c')
        check('★ 点「音效音色」', tap_row('音效音色'))
        time.sleep(1.8)
        check('★ 选「叮」', tap_row('叮', tries=3))
        time.sleep(2.4)
        recs = click_records(logs())
        check('★★ 当场试听：日志里出现「叮」（设置页试听这条链是通的）',
              any(r[0] == '叮' for r in recs), recs)
        check('★ 偏好真的落盘（style=2）', S.pref('click_sound_style') == '2',
              S.pref('click_sound_style'))

        # ── c) 改音量 ──
        print()
        print('== c) 音量：改一档也试听 ==')
        V.sh('shell', 'logcat', '-c')
        check('★ 点「音效音量」', tap_row('音效音量'))
        time.sleep(1.8)
        check('★ 选「响」', tap_row('响', tries=3))
        time.sleep(2.4)
        recs = click_records(logs())
        check('★★ 试听日志里的音量是 0.90', any(abs(r[1] - 0.90) < 0.001 for r in recs), recs)
        check('★ 偏好真的落盘（0.9）',
              S.pref('click_sound_volume') in ('0.9', '0.90'), S.pref('click_sound_volume'))

        # ── d) 戳桌宠 ⇒ 响（正面） ──
        print()
        print('== d) 戳桌宠：正面 ==')
        V.sh('shell', 'logcat', '-c')
        check('★ 戳到桌宠了', poke_pet())
        time.sleep(2.5)
        recs = click_records(logs())
        check('★★ 戳桌宠真的走到播放（日志 play …）—— 触发点挂在 poke() 上', len(recs) >= 1, recs)

        # ── e) 关掉开关再戳 ⇒ 不许响（反面） ──
        print()
        print('== e) 关掉开关：反面（把开关写成空实现也能过第一条，所以必须有这一条）==')
        S.pref_set('click_sound_on', 'false')
        V.sh('shell', 'logcat', '-c')
        check('★ 再戳一次桌宠', poke_pet())
        time.sleep(2.5)
        off_recs = click_records(logs())
        check('★★ 关掉后**一次都不许响**', len(off_recs) == 0, off_recs)

    finally:
        # 收尾：还原默认（别把模拟器留在"关着"的状态）
        S.pref_set('click_sound_on', 'true')
        S.pref_set('click_sound_style', '0')
        S.pref_set('click_sound_volume', '0.6')

    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    print('ALL CLICK-SOUND DEVICE CHECKS PASSED'
          '（三行都在 / 改档即试听 / 偏好落盘 / 戳桌宠真的走到播放 / 关掉后一声不响）')
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
