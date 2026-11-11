# -*- coding: utf-8 -*-
"""反向验证：verify_keepalive.py 的断言**必须能抓到回潮**。

每一项故意破坏一次 → 断言必须变红 → 恢复 → 变绿。
★ 恢复放 finally；源码用二进制读写（保住行尾）；找不到锚点按"未验证"计（不是通过）。

覆盖保活链的每一条边界：
  1. 闹钟本身（Doze 也要能触发 / 间隔合理 / 取消得掉 / action 对得上）；
  2. 巡检决策（三个前提 / ★ 链式续约 / 服务活着时不瞎重启 / 只认自己的 action）；
  3. 开机自启（清单权限与两个 receiver 的 exported 语义）；
  4. ★★ **被拉起来之后不许再死** —— 轮次⑱ 那个真 bug 的两个方向：
     ① 干脆不调 `startForeground()`；② 调了但在 `ACTION_RESTART` 分支**之后**；
     外加 `isAlive` 生命周期与 `START_STICKY`（系统自己也会拉回来的那一半）；
  5. 设备脚本不许钉到用户真机上。

★ 这套件是纯 grep 级的（不编 Java），所以很快：每一项两次全量正向断言约 4 秒。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFY = os.path.join(HERE, 'verify_keepalive.py')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
KA = os.path.join(SRC, 'KeepAlive.java')
KR = os.path.join(SRC, 'KeepAliveReceiver.java')
BR = os.path.join(SRC, 'BootReceiver.java')
FS = os.path.join(SRC, 'FloatingService.java')
MF = r'D:\petport_lab\petbuild\AndroidManifest.xml'
UI = os.path.join(HERE, 'verify_keepalive_ui.py')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    orig = io.open(path, 'rb').read()
    ob = old.encode('utf-8')
    if ob not in orig:
        print('  [SKIP] %-58s （找不到锚点，按未验证计）' % label)
        return None
    try:
        io.open(path, 'wb').write(orig.replace(ob, new.encode('utf-8'), 1))
        bad = run_verify()
        io.open(path, 'wb').write(orig)
        good = run_verify()
    finally:
        io.open(path, 'wb').write(orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-58s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


print('== 反向验证：保活（看门狗 / 开机自启 / 被拉起后不许再死）==')
results = []

# ─────────────── 一、闹钟本身 ───────────────
results.append(break_and_check(
    'a. ★闹钟改回普通 set()（Doze 下几乎不触发 ⇒ 像"没生效"）',
    KA,
    'am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);',
    'am.set(AlarmManager.RTC_WAKEUP, at, pi);'))

results.append(break_and_check(
    'b. 默认巡检间隔改成 1 分钟（太密，耗电）',
    KA,
    'public static final long DEFAULT_INTERVAL_MS = 15 * 60 * 1000L;',
    'public static final long DEFAULT_INTERVAL_MS = 1 * 60 * 1000L;'))

results.append(break_and_check(
    'c. 心跳 action 常量改掉（闹钟与接收器对不上）',
    KA,
    'public static final String ACTION_TICK = "com.deepseek.pet.KEEPALIVE_TICK";',
    'public static final String ACTION_TICK = "com.deepseek.pet.KEEPALIVE_TICK_X";'))

results.append(break_and_check(
    'd. 取消看门狗换成另一个 PendingIntent（取消不掉）',
    KA,
    'if (am != null) am.cancel(tickIntent(ctx));',
    'if (am != null) am.cancel(null);'))

# ─────────────── 二、巡检决策 ───────────────
results.append(break_and_check(
    'e. ★去掉链式续约（闹钟响一次就断了）',
    KR,
    'KeepAlive.scheduleWatchdog(ctx, Prefs.keepAliveIntervalMs(ctx));',
    ';'))

results.append(break_and_check(
    'f. ★用户主动退出后照样复活（不尊重用户意愿）',
    KR,
    'if (shouldRun) {',
    'if (true) {'))

results.append(break_and_check(
    'g. 缺悬浮窗权限也照样拉起（起了个看不见的东西）',
    KR,
    '} else if (!KeepAlive.canShowOverlay(ctx)) {',
    '} else if (false) {'))

results.append(break_and_check(
    'h. 服务活着时也重启一遍（白闪一下）',
    KR,
    'Log.i(TAG, "桌宠仍在运行，无需干预");',
    'Log.i(TAG, "x");'))

results.append(break_and_check(
    'i. 巡检不再认 action（任何广播都当心跳）',
    KR,
    'if (intent == null || !KeepAlive.ACTION_TICK.equals(intent.getAction())) return;',
    'if (intent == null) return;'))

# ─────────────── 三、开机自启 ───────────────
results.append(break_and_check(
    'j. ★清单去掉 RECEIVE_BOOT_COMPLETED（开机收不到广播）',
    MF,
    '    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n',
    ''))

results.append(break_and_check(
    'k. ★心跳 receiver 改成 exported=true（别的应用能伪造心跳）',
    MF,
    '            android:name=".KeepAliveReceiver"\n'
    '            android:enabled="true"\n'
    '            android:exported="false">',
    '            android:name=".KeepAliveReceiver"\n'
    '            android:enabled="true"\n'
    '            android:exported="true">'))

results.append(break_and_check(
    'l. 开机 receiver 漏掉 BOOT_COMPLETED（自启失效）',
    MF,
    '                <action android:name="android.intent.action.BOOT_COMPLETED" />\n',
    ''))

results.append(break_and_check(
    'm. ★开机自启不再尊重"上次是主动退出"（重启后又自己冒出来）',
    BR,
    'if (!Prefs.petShouldRun(ctx)) {',
    'if (false) {'))

# ─────────────── 四、★ 被拉起来之后不许再死（轮次⑱ 的回归）───────────────
results.append(break_and_check(
    'n. ★★startForegroundSafely() 从 onStartCommand 删掉（⑱ 那个 bug 原样）',
    FS,
    '        startForegroundSafely();\n\n        // ---- App 前台/后台',
    '        // ---- App 前台/后台'))

results.append(break_and_check(
    'o. ★★把它挪到 ACTION_RESTART 分支之后（只有正常启动才转前台）',
    FS,
    '        startForegroundSafely();\n\n        // ---- App 前台/后台',
    '        if (intent != null && ACTION_RESTART.equals(intent.getAction())) '
    '{ return START_STICKY; }\n'
    '        startForegroundSafely();\n\n        // ---- App 前台/后台'))

results.append(break_and_check(
    'p. ★onDestroy 不再清 isAlive（看门狗以为还活着 ⇒ 永远不复活）',
    FS,
    '        isAlive = false;\n        super.onDestroy();',
    '        super.onDestroy();'))

results.append(break_and_check(
    'q. ★默认路径改回 START_NOT_STICKY（系统不再自己拉回来）',
    FS,
    '        return START_STICKY;\n    }\n\n    // ==================== 性能优化',
    '        return START_NOT_STICKY;\n    }\n\n    // ==================== 性能优化'))

results.append(break_and_check(
    'r. 用户主动退出那条路改成 START_STICKY（系统又把它拉回来）',
    FS,
    '            return START_NOT_STICKY;',
    '            return START_STICKY;'))

results.append(break_and_check(
    's. ★onCreate 不再排看门狗（装完就没有自愈）',
    FS,
    '            if (Prefs.keepAliveEnabled(this)) {\n'
    '                KeepAlive.scheduleWatchdog(this, Prefs.keepAliveIntervalMs(this));\n'
    '            }',
    '            if (false) {\n'
    '                KeepAlive.scheduleWatchdog(this, Prefs.keepAliveIntervalMs(this));\n'
    '            }'))

results.append(break_and_check(
    't. ★用户主动退出时不再取消看门狗（退出后还在唤醒）',
    FS,
    'try { KeepAlive.cancelWatchdog(this); } catch (Throwable ignore) {}',
    'try { } catch (Throwable ignore) {}'))

# ─────────────── 五、设备脚本 ───────────────
results.append(break_and_check(
    'u. ★设备脚本改成钉用户真机（会往用户手机上装包）',
    UI,
    'DEV = V.DEV',
    'DEV = "<设备序列号>"'))

print()
print('=' * 62)
n_ok = sum(1 for r in results if r)
n_skip = sum(1 for r in results if r is None)
n_all = len(results)
if n_skip:
    print('%d 项跳过（按未验证计）' % n_skip)
if n_ok != n_all:
    print('!! 只有 %d/%d 个破坏被抓到（说明有断言是空的）' % (n_ok, n_all))
    sys.exit(1)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (n_ok, n_all))
print('ALL REVERSE CHECKS PASSED')
