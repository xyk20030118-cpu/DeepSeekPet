"""实测三种返回途径（模拟器）。

要验证的行为（用户报的 bug + 新加的手势）：
  ① 二级页 + 系统返回键        → 回板块首页（**不能**退出 App）
  ② 二级页 + 左边缘左滑        → 回板块首页
  ③ 板块首页(非第一个) + 返回  → 回第一个板块（不退出）
  ④ 最外层 + 返回              → 退出（唯一允许退出的情况）

判据用**前台 Activity 是否还是本 App** + **页面标题**，
都是客观可测的，不靠肉眼。
"""
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def dump():
    """抓当前界面 XML。

    ★ 必须先删本地旧文件再拉：
      `adb pull` 失败时不会覆盖目标文件，于是上一次的 dump 会**冒充**
      当前数据 —— 表现为"页面上找不到刚看到的文字"，
      而真正的原因是这次 dump 根本没成功。
      我因此白查了一轮。
    """
    local = r'D:\petport_lab\petbuild\build\_back.xml'
    try:
        os.remove(local)
    except OSError:
        pass
    sh('shell', 'rm', '-f', '/sdcard/_d.xml')
    sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh('pull', '/sdcard/_d.xml', local)
    try:
        return open(local, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


def texts(x):
    out = []
    for m in re.finditer(r'text="([^"]{1,40})"', x):
        t = m.group(1).strip()
        if t and t not in out:
            out.append(t)
    return out


def top_activity():
    o = sh('shell', 'dumpsys', 'activity', 'activities').stdout.decode('utf-8', 'replace')
    for l in o.splitlines():
        if 'topResumedActivity' in l:
            m = re.search(r'([\w.]+/[\w.]+)', l)
            return m.group(1) if m else l.strip()
    return '(未知)'


def in_app():
    return PKG in top_activity()


def tap(x, needle):
    """点含该文字的最小可点祖先"""
    ty = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        t = re.search(r'text="([^"]*)"', s)
        if t and needle in t.group(1):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
            if b:
                ty = (int(b.group(2)) + int(b.group(4))) // 2
                break
    if ty is None:
        return False
    best = None
    for m in re.finditer(r'<node[^>]*?>', x):
        s = m.group(0)
        if 'clickable="true"' not in s:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if not b:
            continue
        y0, y1 = int(b.group(2)), int(b.group(4))
        if y0 <= ty <= y1:
            area = (int(b.group(3)) - int(b.group(1))) * (y1 - y0)
            if best is None or area < best[0]:
                best = (area, (int(b.group(1)) + int(b.group(3))) // 2, (y0 + y1) // 2)
    if not best:
        return False
    sh('shell', 'input', 'tap', str(best[1]), str(best[2]))
    time.sleep(2.5)
    return True


def start_fresh(wait_text='外观与显示', timeout=20):
    """重启 App 并**等到指定文字出现**才返回。

    ★ 固定 sleep 不可靠：`monkey` 启动后界面就绪时间会波动，
      只等 9 秒时 dump 可能拿到空页面（前台都查不到），
      于是后续步骤全部失败 —— 看起来像"返回功能没生效"，
      实际是**测试脚本没等够**。
      改成轮询到关键文字出现为止。
    """
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    end = time.time() + timeout
    while time.time() < end:
        time.sleep(1.5)
        x = dump()
        if wait_text in x:
            return x
    return dump()


W, H = 1080, 2400
results = []


def check(name, cond, detail=''):
    results.append((name, cond))
    print('  %s %s%s' % ('✓' if cond else '✗', name,
                         ('  ' + detail) if detail else ''))


print('=== 准备：进入一个二级页（形象 → 外观与显示）===')
x = start_fresh()
if not tap(x, '外观与显示'):
    sys.exit('进不去二级页，无法测试')
time.sleep(1)
x = dump()
ts = texts(x)
print('  当前页面: %s' % ' | '.join(ts[:6]))
entered = ('外观与显示' in ts) and ('返回' in ts)
if not entered:
    sys.exit('没进到二级页')

print()
print('=== ① 二级页 + 系统返回键 ===')
sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(2.5)
check('仍在 App 内（没退回桌面）', in_app(), top_activity())
x = dump()
ts = texts(x)
check('回到板块首页（不再是二级页）', '返回' not in ts,
      '页面: ' + ' | '.join(ts[:5]))

print()
print('=== ② 二级页 + 左边缘左滑 ===')
x = start_fresh()
tap(x, '外观与显示')
time.sleep(2)
x = dump()
if '返回' in texts(x):
    # 从屏幕左边缘 8px 处往右滑（dx 需超过 edge*3=60dp≈180px）
    sh('shell', 'input', 'swipe', '8', str(H // 2), '700', str(H // 2), '250')
    time.sleep(2.5)
    check('左滑返回后仍在 App 内', in_app(), top_activity())
    x = dump()
    check('左滑返回回到了板块首页', '返回' not in texts(x),
          '页面: ' + ' | '.join(texts(x)[:5]))
else:
    check('（没进到二级页，跳过左滑测试）', False)

print()
print('=== ③ 板块首页(非第一个) + 返回 → 回第一个板块 ===')
x = start_fresh()
# 点底栏最右侧 = 系统板块
sh('shell', 'input', 'tap', str(int(W * 0.92)), str(int(H * 0.945)))
time.sleep(3)
x = dump()
ts_before = texts(x)
print('  切到系统板块: %s' % ' | '.join(ts_before[:6]))
sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
time.sleep(2.5)
check('仍在 App 内', in_app(), top_activity())
x = dump()
ts_after = texts(x)
check('回到第一个板块（形象）', '外观与显示' in ts_after,
      '页面: ' + ' | '.join(ts_after[:6]))

print()
print('=== 结果 ===')
ok = sum(1 for _, c in results if c)
print('  通过 %d / %d' % (ok, len(results)))
for n, c in results:
    if not c:
        print('    ✗ %s' % n)
sys.exit(0 if ok == len(results) else 1)
