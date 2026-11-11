# -*- coding: utf-8 -*-
"""保活断言：**桌宠被系统清掉后能不能自己回来**（STATE.md 待办 1 的收口）。

为什么单开一套：整条保活链（`KeepAlive` 闹钟 / `KeepAliveReceiver` 巡检 / `BootReceiver` 开机自启 /
`FloatingService` 被拉起后的前台化）在本轮之前**一条离线断言都没有** ——
而轮次⑱ 修过的那个真 bug 正好出在这里：`ACTION_RESTART` 分支漏掉 `startForeground()`，
服务被拉起来后 5 秒内没转前台 ⇒ 系统直接把进程杀掉 ⇒ **表现成"看门狗没拉回来"**
（用户报的就是这个症状）。所以本套件最值钱的一条是第四节的**顺序判据**。

  一、`KeepAlive`：闹钟用 Doze 也能触发的 `setAndAllowWhileIdle`；间隔在 5~60 分钟；取消走同一个 PI；
  二、`KeepAliveReceiver`：三个前提（看门狗开着 / 用户没主动退出 / 有悬浮窗权限）都判；
      ★ **链式续约**（`finally` 里重排下一次，否则闹钟响一次就断了）；
  三、`BootReceiver` + 清单：开机/更新广播齐全、`RECEIVE_BOOT_COMPLETED` 在、两个 receiver 的
      exported 语义正确（心跳**不能**被别的应用伪造）；
  四、`FloatingService`：★★ **`startForegroundSafely()` 必须在 `onStartCommand` 最前面**
      （早于 `ACTION_RESTART` 分支与任何 `return`）—— 这是轮次⑱ 那个真 bug 的回归判据；
      `isAlive` 生命周期正确（看门狗就是靠它判断"桌宠还在不在"）；返回 `START_STICKY`；
  五、产物：类与关键文案真的进了 dev APK 的 dex；
  六、设备脚本 `verify_keepalive_ui.py` 在、只认模拟器、`--selftest` 通过。

设备侧实测见 `build/verify_keepalive_ui.py`（三段隔离验证：服务停 / 进程被杀 / 用户主动退出）。
反向破坏见 reverse_check_keepalive.py。
"""
import io
from artifacts import dev_apk
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')
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
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。

    ★ 与 verify_character_switch / verify_action_import 是同一份"认字符串字面量"的实现：
      朴素版会在 `i.setType("image/*")` 这种**字符串里含 `/*`** 的地方误入块注释状态。
    """
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
        if c == '"' or c == "'":
            q = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                if ch == '\\' and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if ch == q:
                    break
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def method_body(src, sig):
    """截出某个方法的正文（同文件里方法按 4 空格缩进收尾）。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(sig))
    return src[i:j + 6] if j > 0 else src[i:]


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd or HERE, capture_output=True)
    out = r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')
    return r.returncode, out


KA = java('KeepAlive.java')
KR = java('KeepAliveReceiver.java')
BR = java('BootReceiver.java')
FS = java('FloatingService.java')
MF = read(MANIFEST)

# ═══════════════ 一、KeepAlive：闹钟本身 ═══════════════
print()
print('== 一、KeepAlive：闹钟怎么排（Doze 也要能触发）==')
check('KeepAlive 存在', bool(KA))
check('★ 用 setAndAllowWhileIdle（Doze 省电模式下也会触发）',
      'setAndAllowWhileIdle' in KA,
      '普通 set() 在 Doze 下会被推迟到几乎不触发 ⇒ 看门狗"看起来没生效"')
m = re.search(r'DEFAULT_INTERVAL_MS\s*=\s*(\d+)\s*\*\s*60\s*\*\s*1000', KA)
mins = int(m.group(1)) if m else -1
check('★ 默认巡检间隔在 5~60 分钟（太密耗电 / 太疏恢复慢）', 5 <= mins <= 60,
      '默认 %s 分钟' % (mins if mins > 0 else '没解析出来'))
check('心跳 action 常量是 com.deepseek.pet.KEEPALIVE_TICK',
      '"com.deepseek.pet.KEEPALIVE_TICK"' in KA)
check('★ 取消看门狗用**同一个 PendingIntent**（否则取消不掉、用户退出后还在唤醒）',
      'am.cancel(tickIntent(ctx))' in KA)
check('canShowOverlay 用 Settings.canDrawOverlays（没有悬浮窗权限启动也没意义）',
      'canDrawOverlays' in KA)

# ═══════════════ 二、KeepAliveReceiver：巡检决策 ═══════════════
print()
print('== 二、KeepAliveReceiver：巡检该复活谁、不该复活谁 ==')
check('先认 action（不是我们的心跳就返回）', 'KeepAlive.ACTION_TICK.equals' in KR)
check('三个前提都判：看门狗开着 / 用户没退出 / 有悬浮窗权限',
      'Prefs.keepAliveEnabled(ctx)' in KR and 'Prefs.petShouldRun(ctx)' in KR
      and 'canShowOverlay' in KR)
check('★ 用户主动退出后**不许**复活（在 else 分支里，不是死代码）',
      KR.find('} else {') >= 0 and KR.find('用户已主动退出桌宠，不自动恢复') > KR.find('} else {'),
      'else@%d 文案@%d' % (KR.find('} else {'), KR.find('用户已主动退出桌宠，不自动恢复')))
check('★ 复活条件真的用了 shouldRun（不是写死的 true）', 'if (shouldRun) {' in KR)
check('★ 权限不足那条路真的判了 canShowOverlay（不是写死的 false）',
      'if (!KeepAlive.canShowOverlay(ctx)) {' in KR)
check('缺悬浮窗权限时不复活（并说明原因）', '缺少悬浮窗权限，无法恢复' in KR)
check('服务活着时**不瞎重启**（只记一句"无需干预"）', '无需干预' in KR)
check('用 startForegroundService 拉起（API 26+ 分支）',
      'startForegroundService' in KR and 'Build.VERSION.SDK_INT >= 26' in KR)
i_fin = KR.find('} finally {')
i_sched = KR.find('KeepAlive.scheduleWatchdog')
check('★★ 链式续约：在 finally 里重排下一次巡检（否则闹钟响一次就断了）',
      i_fin >= 0 and i_sched > i_fin,
      'finally@%d scheduleWatchdog@%d' % (i_fin, i_sched))
check('★ 续约条件与复活条件一致（看门狗开着 **且** 用户没退出）',
      'Prefs.keepAliveEnabled(ctx) && Prefs.petShouldRun(ctx)' in KR)

# ═══════════════ 三、BootReceiver + 清单 ═══════════════
print()
print('== 三、BootReceiver + 清单：开机/更新后也能回来 ==')
check('认 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED', 'ACTION_BOOT_COMPLETED' in BR
      and 'LOCKED_BOOT_COMPLETED' in BR)
check('认 MY_PACKAGE_REPLACED（更新安装后系统会杀进程，顺势拉回来）',
      'ACTION_MY_PACKAGE_REPLACED' in BR)
check('★ 尊重"上次是主动退出"（重启后不许自己冒出来）',
      'Prefs.petShouldRun(ctx)' in BR and '上次是主动退出状态，不自动启动' in BR)
check('没有悬浮窗权限就跳过（并说明）', 'canDrawOverlays' in BR and '缺少悬浮窗权限' in BR)
check('用 ACTION_RESTART 走同一条恢复路径', 'FloatingService.ACTION_RESTART' in BR)

check('清单声明 RECEIVE_BOOT_COMPLETED', 'android.permission.RECEIVE_BOOT_COMPLETED' in MF)
check('清单注册 BootReceiver（BOOT_COMPLETED 过滤器 + exported=true）',
      '.BootReceiver' in MF and 'android.intent.action.BOOT_COMPLETED' in MF
      and re.search(r'\.BootReceiver"[^>]*?android:exported="true"', MF, re.S) is not None)
check('★ 清单注册 KeepAliveReceiver 且 **exported=false**（心跳不能被别的应用伪造）',
      '.KeepAliveReceiver' in MF and 'com.deepseek.pet.KEEPALIVE_TICK' in MF
      and re.search(r'\.KeepAliveReceiver"[^>]*?android:exported="false"', MF, re.S) is not None)
check('FloatingService 声明了 foregroundServiceType（Android 14+ 前台服务必须有类型）',
      'android:foregroundServiceType' in MF)

# ═══════════════ 四、FloatingService：被拉起来之后不许再死 ═══════════════
print()
print('== 四、FloatingService：★ 轮次⑱ 那个真 bug 的回归判据 ==')
osc = method_body(FS, 'public int onStartCommand(')
i_sf = osc.find('startForegroundSafely();')
i_restart = osc.find('ACTION_RESTART.equals(intent.getAction())')
i_ret = osc.find('return START_')
check('onStartCommand 里调了 startForegroundSafely()', i_sf >= 0,
      'onStartCommand 正文 %d 字符' % len(osc))
check('★★ 它在**最前面**：早于 ACTION_RESTART 分支',
      i_sf >= 0 and i_restart > 0 and i_sf < i_restart,
      'startForegroundSafely@%d ACTION_RESTART@%d' % (i_sf, i_restart))
check('★★ 也早于**任何** return（每条启动路径都要满足"5 秒内转前台"）',
      i_sf >= 0 and i_ret > 0 and i_sf < i_ret,
      'startForegroundSafely@%d 第一个 return START_@%d' % (i_sf, i_ret))
check('★ isAlive 在 onCreate 置 true（看门狗就是靠它判断桌宠在不在）',
      re.search(r'isAlive = true;', FS) is not None)
check('★ isAlive 在 onDestroy 置 false（否则停了服务看门狗还以为活着 ⇒ 永远不复活）',
      'isAlive = false;' in method_body(FS, 'public void onDestroy('))
i_last = osc.rfind('return START_')
last_ret = osc[i_last:i_last + 26] if i_last >= 0 else ''
check('★ 默认路径返回 START_STICKY（系统自己也会把服务拉回来，设备上实测 1~3 秒）',
      last_ret.startswith('return START_STICKY;'), '最后一条 return = %r' % last_ret)
i_stop = osc.find('ACTION_STOP.equals')
i_stop_ret = osc.find('return START_', i_stop) if i_stop >= 0 else -1
stop_ret = osc[i_stop_ret:i_stop_ret + 30] if i_stop_ret >= 0 else ''
check('用户主动退出那条路返回 START_NOT_STICKY（别让系统又把它拉回来）',
      stop_ret.startswith('return START_NOT_STICKY;'), '退出分支 return = %r' % stop_ret)
i_ka = FS.find('if (Prefs.keepAliveEnabled(this))')
i_sched2 = FS.find('KeepAlive.scheduleWatchdog(this, Prefs.keepAliveIntervalMs(this))')
check('onCreate 里按开关排看门狗', i_ka >= 0 and i_sched2 > i_ka,
      'keepAliveEnabled@%d scheduleWatchdog@%d' % (i_ka, i_sched2))
ex = method_body(FS, 'private void exitPetWithFarewell(')
check('★ 用户主动退出：记 shouldRun=false **且**取消看门狗（两件都要做）',
      'setPetShouldRun(this, false)' in ex and 'KeepAlive.cancelWatchdog' in ex)

# ═══════════════ 五、产物：真的进了 dev 包 ═══════════════
print()
print('== 五、产物：新代码真的进了 dev 包 ==')
check('dev APK 存在', os.path.isfile(DEV_APK))
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('KeepAlive', 'KeepAliveReceiver', 'BootReceiver', 'DSPKeepAlive',
              'KEEPALIVE_TICK', '看门狗已安排', '检测到桌宠已停止，尝试恢复',
              '用户已主动退出桌宠，不自动恢复', '缺少悬浮窗权限，无法恢复',
              '无需干预', 'scheduleWatchdog', 'cancelWatchdog', 'DEFAULT_INTERVAL_MS',
              'petShouldRun', 'canShowOverlay', 'startForegroundSafely'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

# ═══════════════ 六、设备脚本 ═══════════════
print()
print('== 六、设备脚本（可复跑，且只认模拟器）==')
ui = os.path.join(HERE, 'verify_keepalive_ui.py')
check('verify_keepalive_ui.py 存在', os.path.isfile(ui))
if os.path.isfile(ui):
    s = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          'DEV = V.DEV' in s and 'import verify_char_ui as V' in s)
    check('★ 三段隔离都在（服务停 / 进程被杀 / 用户主动退出）',
          'stopservice' in s and 'kill' in s and 'pet_should_run' in s)
    check('★ 广播带 --include-stopped-packages（force-stop 后收不到广播会假红）',
          '--include-stopped-packages' in s)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_keepalive_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（保活：看门狗 / 开机自启 / 被拉起后不许再死）')
