# -*- coding: utf-8 -*-
"""设备验证：**桌宠被系统回收后，看门狗真的能把它拉回来**（待办 1 的收口）。

为什么需要它（STATE.md 待办 1）：
    「**启动大型游戏后桌宠服务被杀**（keep-alive 看门狗没拉回来）」是 2026-10-06 就记下的老遗留，
    之后**一直没人验过**（这条链只有"代码看着对"）。而轮次⑱ 修过一个**同族真 bug**：
    `ACTION_RESTART` 分支漏掉 `startForeground()` ⇒ 服务被拉起来后 5 秒内没转前台 ⇒
    系统直接把进程杀掉（logcat 是 `Process com.deepseek.pet (pid …) has died`）——
    **看起来就是"看门狗没拉回来"**。v7.0 起 `onStartCommand` 的**所有**路径开头都调
    `startForegroundSafely()`。⇒ 现在要回答的是「这条链在设备上到底通不通」，不是"代码看着对"。

本脚本把它拆成**互相隔离**的三段实测（每段只动一个变量）：

  一、前置：桌宠真的在跑（进程 + 服务 `startRequested=true` + 悬浮窗 ≥1 个）+ 两个 pref 是开的；
  二、看门狗闹钟**真的挂在系统里**（`dumpsys alarm` 里的 `com.deepseek.pet.KEEPALIVE_TICK`）；
  三、★ **服务停但进程活**（`am stopservice`）—— 隔离掉"系统自动重启进程"这条干扰路径，
      再**手动投一次闹钟**（`am broadcast … KEEPALIVE_TICK`，等价于 15 分钟到点）：
      断言日志三行齐 + 服务真的回来 + **链式续约**（又排了下一次巡检）；
  四、★ **进程被杀**（`kill -9`，等价于 LMK 回收）—— ★ 实测（Android 36）：**系统自己就把
      sticky 服务重启了**（`stopIfKilled=false` = START_STICKY，旧 pid → 新 pid 只要 1~3 秒），
      所以这一段验的是「① 闹钟不随进程消失（这是看门狗能兜底的前提）② 自愈之后**没有再死**
      ③ 看门狗看到服务活着时**正确地什么都不做**」；
  五、★ **尊重用户主动退出**：`pet_should_run=false` ⇒ 服务停 + 投闹钟 ⇒ **不许**复活
      （日志"用户已主动退出桌宠，不自动恢复"）；
  六、收尾：pref 与桌宠恢复成"运行中"。

★ 诚实边界（别把模拟当实测）：
  * `am stopservice` 与 `kill -9` 都是**模拟**系统回收，不是真的内存压力回收；
  * 真机「大型游戏抢内存」的场景本脚本复现不了 —— 但"服务不在了 ⇒ 看门狗能不能拉回来"
    走的是**同一段代码**，这个因果链在这里可以验；
  * 看门狗默认 **15 分钟**一巡（可调 5~60 分钟）⇒ 真机上最坏要等 15 分钟才恢复；
    本脚本用**手动投递**把这段等待压掉 —— 投递与闹钟到点走的是**同一个接收器**，不改变结论；
  * 本脚本**不改产品代码**，也不代表真机上的厂商 ROM 行为（自启白名单/省电策略各有差异）。

用法：
    python build/verify_keepalive_ui.py --install     # 装 dist 里的开发版再跑
    python build/verify_keepalive_ui.py               # 设备上已有包，直接跑
    python build/verify_keepalive_ui.py --selftest    # 不连设备，只验本脚本的判读逻辑

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import os
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402  本地 pref 副本的擦除（防 Key 进源码包）
import verify_char_ui as V           # noqa: E402  设备驱动与判读工具
import verify_char_switch_ui as S    # noqa: E402  角色中心导航 / 可靠改 pref

DEV = V.DEV
PKG = V.PKG
TICK_ACTION = 'com.deepseek.pet.KEEPALIVE_TICK'
TICK_COMPONENT = PKG + '/.KeepAliveReceiver'

fails = []
skips = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def watchdog_alarms(dump_text):
    """`dumpsys alarm` 里看门狗闹钟的**条数**（0 = 没挂）。

    ★ 设备上实测的真实形状（2026-10-11，Android 36）：
        RTC_WAKEUP #8: Alarm{4c8a942 type 0 origWhen … com.deepseek.pet}
          tag=*walarm*:com.deepseek.pet.KEEPALIVE_TICK
          operation=PendingIntent{… broadcastIntent}}
      ⇒ 认 `KEEPALIVE_TICK` 这个 tag（比认包名稳：包名在整段 dump 里到处都有）。
    """
    return sum(1 for l in (dump_text or '').splitlines() if 'KEEPALIVE_TICK' in l)


def overlay_window_lines(dump_text, pkg=PKG):
    """本包的**悬浮窗**行（排掉 MainActivity 自己的窗口）。

    ★ 设备上实测的形状：`Window #6 Window{7f0b71a u0 com.deepseek.pet}:`
      （服务自己的 overlay 窗口不带组件名；MainActivity 的带 `/MainActivity` ⇒ 按它排掉）。
    """
    return [l.strip() for l in (dump_text or '').splitlines()
            if 'Window{' in l and pkg in l and 'MainActivity' not in l]


def service_running_in(dump_text, pkg=PKG):
    """服务是否处于"被请求启动"状态（`dumpsys activity services <pkg>`）。

    ★ 只看有没有 `FloatingService` 不够：停掉之后 ServiceRecord 还会在 dump 里留一会儿，
      必须看 `startRequested=true`（设备上实测：跑着的时候是 true）。
    """
    d = dump_text or ''
    return ('FloatingService' in d) and ('startRequested=true' in d)


def has_tick_logs(lg):
    """看门狗"发现服务不在 ⇒ 拉回来"三行日志齐不齐（返回命中的行）。"""
    want = ('巡检: 看门狗=true 应运行=true 服务活着=false',
            '检测到桌宠已停止，尝试恢复',
            '已请求恢复桌宠服务')
    return [w for w in want if w in (lg or '')]


def looks_dead_after_start(lg):
    """服务被拉起来后**又死了**的特征（轮次⑱ 那个真 bug 的表现）。

    ★ 三种真实文案都要认（第一种是系统杀进程，后两种是 Android 8+ 的"没在 5 秒内转前台"）：
      ① `Process com.deepseek.pet (pid …) has died`；
      ② `android.app.ForegroundServiceDidNotStartInTimeException`（异常类名，**中间没有空格**）；
      ③ `Bringing down service while still waiting for startForeground`（ActivityManager 侧的说法）。
      （第一版只写了 `did not start in time`，被 `--selftest` 当场抓出来 —— 判据写错 = 假绿灯。）
    """
    d = (lg or '')
    return (('has died' in d)
            or ('ForegroundServiceDidNotStartInTime' in d)
            or ('did not then call Service.startForeground' in d)
            or ('waiting for startForeground' in d))


def selftest():
    """不连设备：验本脚本自己的判读逻辑（正例 + 反例）。"""
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    alarm_dump = ('  RTC_WAKEUP #8: Alarm{4c8a942 type 0 origWhen 1791721209157 '
                  'whenElapsed 961618 com.deepseek.pet}\n'
                  '    tag=*walarm*:com.deepseek.pet.KEEPALIVE_TICK\n'
                  '    operation=PendingIntent{80e5d53: PendingIntentRecord{fbf3690 '
                  'com.deepseek.pet broadcastIntent}}\n'
                  '  RTC_WAKEUP #9: Alarm{111 type 0 com.android.providers.calendar}\n'
                  '    tag=*walarm*:com.android.providers.calendar.intent.SOMETHING\n')
    t('看门狗闹钟数得出来（正好 1 条）', watchdog_alarms(alarm_dump) == 1)
    t('反例：别的应用的闹钟不算', watchdog_alarms(alarm_dump.replace('KEEPALIVE_TICK', 'X')) == 0)
    t('反例：空 dump 安全', watchdog_alarms('') == 0 and watchdog_alarms(None) == 0)

    win_dump = ('  Window #6 Window{7f0b71a u0 com.deepseek.pet}:\n'
                '    mOwnerUid=10241 package=com.deepseek.pet appop=SYSTEM_ALERT_WINDOW\n'
                '  Window #7 Window{5784e09 u0 com.deepseek.pet}:\n'
                '  Window #10 Window{be9814e u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n')
    t('数悬浮窗（2 个 overlay，MainActivity 那个不算）', len(overlay_window_lines(win_dump)) == 2)
    t('反例：只剩 MainActivity ⇒ 0（桌宠不在了）',
      len(overlay_window_lines(
          '  Window #10 Window{x u0 com.deepseek.pet/com.deepseek.pet.MainActivity}:\n')) == 0)
    t('反例：别的包不算',
      len(overlay_window_lines('  Window #1 Window{x u0 com.other.app}:\n')) == 0)

    svc_live = '  * ServiceRecord{d3db15f u0 com.deepseek.pet/.FloatingService c:com.deepseek.pet}\n' \
               '    startRequested=true delayedStop=false stopIfKilled=false\n'
    svc_dead = '  * ServiceRecord{d3db15f u0 com.deepseek.pet/.FloatingService c:com.deepseek.pet}\n' \
               '    startRequested=false delayedStop=false stopIfKilled=true\n'
    t('服务在跑：FloatingService + startRequested=true', service_running_in(svc_live))
    t('★ 反例：记录还在但 startRequested=false ⇒ 不算在跑（停掉后 dump 会留一会儿）',
      not service_running_in(svc_dead))
    t('反例：别的服务不算', not service_running_in('* ServiceRecord{x OtherService}\n'))

    good = ('10-11 12:30:00.001 I DSPKeepAlive: 巡检: 看门狗=true 应运行=true 服务活着=false\n'
            '10-11 12:30:00.002 W DSPKeepAlive: 检测到桌宠已停止，尝试恢复\n'
            '10-11 12:30:00.010 I DSPKeepAlive: 已请求恢复桌宠服务\n')
    t('三行日志齐（发现不在 ⇒ 拉回来）', len(has_tick_logs(good)) == 3)
    t('反例：少一行就不算齐', len(has_tick_logs(good.replace('已请求恢复桌宠服务', 'x'))) == 2)
    t('反例：服务活着时不该有"尝试恢复"',
      len(has_tick_logs('巡检: 看门狗=true 应运行=true 服务活着=true\n')) == 0)

    t('认得出"拉起来又死了"（has died）', looks_dead_after_start('I ActivityManager: Process '
                                                              'com.deepseek.pet has died'))
    t('认得出 ForegroundServiceDidNotStartInTimeException',
      looks_dead_after_start('E AndroidRuntime: android.app.ForegroundServiceDidNotStartInTime'))
    t('反例：正常日志不算死', not looks_dead_after_start(good))

    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def pid():
    return V.out('shell', 'pidof', PKG).strip()


def windows():
    return overlay_window_lines(V.out('shell', 'dumpsys', 'window', 'windows'))


def service_running():
    return service_running_in(V.out('shell', 'dumpsys', 'activity', 'services', PKG))


def alarms():
    return watchdog_alarms(V.out('shell', 'dumpsys', 'alarm'))


def pet_up():
    """桌宠"看起来在跑"的三个判据同时成立。"""
    return bool(pid()) and service_running() and len(windows()) >= 1


def tick():
    """投一次看门狗心跳（= 闹钟到点）。

    ★ 必须带 `--include-stopped-packages`：`am force-stop` 会把应用置成 stopped 态，
      而 stopped 的应用**收不到广播**（显式广播也要这个 flag 才保证送达）。
      不带的话第五节会"看起来没复活"，其实是广播压根没送到（假绿/假红都危险）。
    """
    V.sh('shell', 'am', 'broadcast', '--include-stopped-packages',
         '-a', TICK_ACTION, '-n', TICK_COMPONENT)


def wait_for(cond, secs, step=1.0):
    t0 = time.time()
    while time.time() - t0 < secs:
        if cond():
            return True
        time.sleep(step)
    return False


def logcat():
    return V.out('logcat', '-d')


def start_app():
    """启动 App（顺带清掉 stopped 态）→ 等桌宠起来。"""
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    return wait_for(pet_up, 30)


# ═══════════════ 主流程 ═══════════════

def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        print('== adb root ==')
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 拿不到 root（要看 logcat / kill 进程）—— 拒绝假装通过')
        return 1

    print()
    print('== 一、前置：桌宠在跑 + 两个 pref 是开的 ==')
    # 先恢复"用户没主动退出"（上一轮可能留了 false）
    S.pref_set('pet_should_run', 'true')
    if not start_app():
        check('桌宠起来了（进程 + 服务 + 悬浮窗）', False,
              'pid=%r svc=%s win=%d' % (pid(), service_running(), len(windows())))
        return finish()
    check('桌宠在跑（进程 %s / 服务 startRequested / 悬浮窗 %d 个）'
          % (pid(), len(windows())), pet_up())
    check('keep_alive 开关是开的（默认即开，没被关掉）',
          S.pref('keep_alive_enabled') in (None, 'true'),
          'keep_alive_enabled=%s' % S.pref('keep_alive_enabled'))
    check('pet_should_run 是 true（上次不是"用户主动退出"）',
          S.pref('pet_should_run') in (None, 'true'),
          'pet_should_run=%s' % S.pref('pet_should_run'))

    print()
    print('== 二、看门狗闹钟真的挂在系统里 ==')
    n = alarms()
    check('★ dumpsys alarm 里有 KEEPALIVE_TICK（闹钟已安排）', n >= 1, '命中 %d 条' % n)
    lg = logcat()
    check('日志：看门狗已安排（下次巡检 N 秒后）', '看门狗已安排' in lg,
          [l for l in lg.splitlines() if '看门狗已安排' in l][-1:])

    print()
    print('== 三、★ 服务停但进程活（隔离变量）⇒ 投一次闹钟，看门狗该拉回来 ==')
    V.sh('shell', 'am', 'stopservice', '-n', PKG + '/.FloatingService')
    time.sleep(3)
    check('服务已停（startRequested=false）', not service_running(),
          'svc=%s win=%d' % (service_running(), len(windows())))
    check('悬浮窗也收了（桌宠从屏幕上消失）', len(windows()) == 0, 'win=%d' % len(windows()))
    check('★ 进程**还在**（这一段只动"服务"，不动进程）', bool(pid()), 'pid=%s' % pid())
    V.sh('logcat', '-c')
    tick()
    time.sleep(5)
    lg = logcat()
    hits = has_tick_logs(lg)
    check('★ 看门狗日志三行齐（发现服务不在 ⇒ 请求恢复）', len(hits) == 3, hits)
    check('★ 服务真的回来了（startRequested + 悬浮窗）', wait_for(pet_up, 20),
          'pid=%r svc=%s win=%d' % (pid(), service_running(), len(windows())))
    check('★ 拉起来之后**没有再死**（无 has died / did not start in time）',
          not looks_dead_after_start(lg))
    check('★ 链式续约：又排了下一次巡检（闹钟还在）', alarms() >= 1, '闹钟 %d 条' % alarms())

    print()
    print('== 四、★ 进程被杀（等价 LMK 回收）⇒ 系统会先自愈，看门狗是第二道 ==')
    p = pid()
    if not p:
        skip('进程被杀那一段', '桌宠没在跑（上一段没恢复成功）')
    else:
        V.sh('shell', 'kill', '-9', p)
        time.sleep(1.5)
        gone_seen = (pid() == '')
        back = wait_for(lambda: bool(pid()), 10)
        newp = pid()
        # ★★ 实测（2026-10-11，Android 36）：`kill -9` 之后**系统自己就把 sticky 服务重启了**
        #   （旧 pid 3911 → 1~3 秒内变成新 pid 4075，`dumpsys activity services` 里
        #   `stopIfKilled=false` 就是 START_STICKY 的语义）。
        #   ⇒ 判据**不能**写"进程一定空"——那是对 Android 的误解（本脚本第一版就这么假红了）。
        check('★ 进程被杀后系统自己拉回来了（START_STICKY 自愈）%s'
              % ('，新 pid=%s（旧 %s）' % (newp, p) if newp != p else ''),
              back and newp != '',
              'kill 后观测到空窗=%s / 现在 pid=%r' % (gone_seen, newp))
        check('★ 服务与悬浮窗也跟着回来（桌宠回到屏幕上）', wait_for(pet_up, 20),
              'pid=%r svc=%s win=%d' % (pid(), service_running(), len(windows())))
        check('★ 闹钟仍在系统里（**进程死了闹钟不丢**，这是看门狗能兜底的前提）',
              alarms() >= 1, '闹钟 %d 条' % alarms())
        V.sh('logcat', '-c')
        tick()
        time.sleep(4)
        lg = logcat()
        if service_running():
            # 系统已经自愈 ⇒ 看门狗这一轮该"正确地什么都不做"
            check('★ 看门狗巡检看到服务活着 ⇒ 正确地"无需干预"（不瞎重启）',
                  '桌宠仍在运行，无需干预' in lg,
                  [l for l in lg.splitlines() if 'DSPKeepAlive' in l][-2:])
        else:
            # 系统没自愈 ⇒ 看门狗必须兜住
            check('★ 系统没自愈 ⇒ 看门狗兜住（三行日志齐）', len(has_tick_logs(lg)) == 3,
                  has_tick_logs(lg))
            check('★ 投一次闹钟后桌宠回来了', wait_for(pet_up, 20),
                  'pid=%r svc=%s win=%d' % (pid(), service_running(), len(windows())))
        check('★ 全程没有再死（无 has died / 没在 5 秒内转前台）',
              not looks_dead_after_start(logcat()),
              [l for l in logcat().splitlines() if 'has died' in l][:1])

    print()
    print('== 五、★ 尊重用户主动退出：不该复活 ==')
    S.pref_set('pet_should_run', 'false')       # ★ 这一步会 force-stop（顺带把进程与闹钟清掉）
    time.sleep(2)
    V.sh('logcat', '-c')
    tick()
    time.sleep(6)
    lg = logcat()
    check('★ 日志：用户已主动退出桌宠，不自动恢复',
          '用户已主动退出桌宠，不自动恢复' in lg,
          [l for l in lg.splitlines() if 'DSPKeepAlive' in l][-3:])
    check('★ 确实没复活（服务没起来 / 没有悬浮窗）',
          not service_running() and len(windows()) == 0,
          'svc=%s win=%d' % (service_running(), len(windows())))

    print()
    print('== 六、收尾：恢复"用户没退出"并把桌宠拉起来 ==')
    ok_pref = S.pref_set('pet_should_run', 'true')
    check('pet_should_run 已恢复 true', ok_pref, 'pet_should_run=%s' % S.pref('pet_should_run'))
    check('桌宠已重新起来', start_app(),
          'pid=%r svc=%s win=%d' % (pid(), service_running(), len(windows())))
    return finish()


def finish():
    # ★ 设备脚本用完必须擦掉本地 pref 副本（含用户 Key）：
    #   项目移植包会把整个 build/ 打进去（轮次㉘/㉞ 各中过一次）。
    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails or skips:
        if fails:
            print('%d 项失败：' % len(fails))
            for f in fails:
                print('   - ' + f)
        if skips:
            print('%d 项跳过（按未验证计）：%s' % (len(skips), ' / '.join(skips)))
        return 1
    print('ALL KEEPALIVE DEVICE CHECKS PASSED（看门狗自愈，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
