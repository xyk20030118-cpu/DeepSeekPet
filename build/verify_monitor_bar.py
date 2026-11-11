# -*- coding: utf-8 -*-
"""设备实测：系统监测条（八项指标 · 上/中/下三格 · 监测范围门控）。

钉的语义（不是"存在性"）：
  ① 设置页第 7 节真的有三格槽位行 + 三格实时预览 + **监测范围**入口
     （轮次⑨：把"全局"与"部分应用"集成成同一个入口，默认档 = 所有应用）
  ② 切到"仅选定"后出现勾选入口、空名单有明确提示；勾选页能进（有搜索框）
  ③ 门控真的生效：logcat `show=true` 且 `sel=` 与当前档位一致
  ④ 八项指标真的取到数（logcat detail 里 cpu/gpu/ramKb/temp/batt 不是 -1）
  ⑤ 展开态侧栏真的出现在桌宠**侧面**（按活窗口 frame 取样，看胶囊色）
  ⑥ 吸附态真的只显示中格一项（logcat `render strip` 的文案）

★ 悬浮窗不在 uiautomator dump 里 —— 所以 ③⑤⑥ 只能靠 logcat + 截图按坐标取样
  （本工程验证纪律：视觉结论必须量像素，不靠读代码推断）。
★ ★ "部分应用"档的**门控行为**（勾了才显示、没勾就不显示）是纯判据
  `SysMetrics.monitorVisible`，由 `build/fpstest` 第 [14] 节穷举断言；
  这里（设备侧）验的是**接线**：档位切换、名单写入、日志字段一致。
  真机上若要连门控一起验，需要 Shizuku 就绪 —— `--ui-only` 分支不做。
★ 设备自动探测：取第一个 device；可用 `$DSP_DEV` 或 argv[1] 指定。
★ 纯解析部分带 `--selftest`：没有设备也能验脚本自己的判读逻辑（防脚本空转）。

用法：
    python build\\verify_monitor_bar.py                # 用当前已装包测（真机，需 Shizuku）
    python build\\verify_monitor_bar.py --install      # 先装 dist 里的 dev 包
    python build\\verify_monitor_bar.py --ui-only --install
                                                       # 只验设置页/勾选页/长按菜单
                                                       # （模拟器上没有 Shizuku 时用这个）
    python build\\verify_monitor_bar.py --selftest     # 无设备，验判读逻辑
"""
import io
import os
import re
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk  # noqa: E402

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'
GAME = 'com.tencent.tmgp.sgame'          # 王者荣耀：用户机上装着的真实游戏
SHOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'monitor_shots')

DEV = os.environ.get('DSP_DEV', '').strip()
fails = []
results = []
# 间距滑块的两个端点（与 SysMetrics.MONITOR_GAP_MIN_DP / MAX_DP 一致；
# 源码那边有断言钉着，改一边会被抓到）
GAP_MIN_DP = -12.0
GAP_MAX_DP = 24.0


# ==================== 纯解析（可离线自测） ====================

def kv(line):
    """把 `k=v k=v` 形式的一行解析成 dict（值里不含空格）。"""
    out = {}
    for m in re.finditer(r'(\w+)=(\S+)', line or ''):
        out[m.group(1)] = m.group(2)
    return out


def last_line_with(log, needle):
    hit = None
    for line in log.splitlines():
        if needle in line:
            hit = line
    return hit


def parse_sync(log):
    """最后一条 `sync ...` → dict。"""
    return kv(last_line_with(log, 'sync want=') or '')


def parse_detail(log):
    """最后一条 `detail ...` → dict。"""
    return kv(last_line_with(log, 'detail layer=') or '')


def parse_onscreen(log, which):
    """最后一条 `onscreen <which> x=.. y=.. w=.. h=..` → dict。"""
    hit = None
    for line in log.splitlines():
        if 'onscreen ' + which in line:
            hit = line
    return kv(hit or '')


def parse_render(log, which):
    """最后一条 `render <which> top='..' mid='..' bottom='..'` → dict（含三格文案）。"""
    hit = None
    for line in log.splitlines():
        if 'render ' + which in line:
            hit = line
    if not hit:
        return {}
    out = {}
    for key in ('top', 'mid', 'bottom'):
        m = re.search(key + r"='([^']*)'", hit)
        if m:
            out[key] = m.group(1)
    out['_raw'] = hit
    return out


def metric_ok(d, key):
    """指标取到了数（不是 -1 / -- / 空）。"""
    v = d.get(key)
    if v is None or v in ('', '-1', '-1.0', '--'):
        return False
    try:
        return float(v) >= 0
    except ValueError:
        return False


def selftest():
    """无设备自测：拿真机日志样本喂给上面的解析函数。"""
    print('=== --selftest：判读逻辑自测（无需设备）===')
    # ★ 样本里放**两行** sync：第一行是"部分应用模式 + 已勾选"，
    #   第二行（最后一行）是默认的全局模式 —— parse_sync 取最后一条，
    #   所以两种字段组合都被判读函数过了一遍。
    sample = (
        "10-07 01:00:00.000 I/DSPFpsBadge( 1234): sync want=true bar=true ready=true "
        "acquired=true ready2=true show=true pkg=com.tencent.tmgp.sgame "
        "sel=true inList=true apps=1 dock=NORMAL "
        "fps=61.2 cpu=42.4 gpu=31.6 ramKb=5452595 temp=45.3 batt=87.0 pw=4.23 net=1258291.0 "
        "monRun=true refs=1 tickAge=579 sampAge=579\n"
        "10-07 01:00:00.050 I/DSPFpsBadge( 1234): sync want=true bar=true ready=true "
        "acquired=true ready2=true show=true pkg=com.tencent.tmgp.sgame "
        "sel=false inList=false apps=0 dock=NORMAL "
        "fps=61.2 cpu=42.4 gpu=31.6 ramKb=5452595 temp=45.3 batt=87.0 pw=4.23 net=1258291.0 "
        "monRun=true refs=1 tickAge=579 sampAge=579\n"
        "10-07 01:00:00.100 I/DSPFpsMon( 1234):   detail layer=X#1 active=true cands=1 tf=100 "
        "base=4 needFull=false audit=false layers=-1 cpu=42.4 gpu=31.6 ramKb=5452595 "
        "temp=45.3 batt=87.0 pw=4.23 net=1258291.0 execMs=40 totalMs=41 outLen=6000\n"
        "10-07 01:00:00.200 I/DSPFpsBadge( 1234): render side top='CPU 42%' mid='61 FPS' "
        "bottom='RAM 5.2G' vis=0 w=210 h=240\n"
        "10-07 01:00:00.300 I/DSPFpsBadge( 1234): onscreen side x=100 y=800 w=210 h=240 vis=0 "
        "rootX=0 rootY=700 rootW=420 rootH=457\n"
        "10-07 01:00:01.000 I/DSPFpsBadge( 1234): render strip top='42%' mid='61' "
        "bottom='5.2G' vis=0 w=105 h=231\n"
    )
    ok = True
    s = parse_sync(sample)
    ok &= (s.get('show') == 'true' and s.get('pkg') == GAME and s.get('apps') == '0')
    # ★ 轮次⑨ 新增两个字段：sel（范围是不是"仅选定"）、inList（前台包在不在名单里）。
    #   解析器要把它们读出来 —— 判读逻辑错了设备那一轮就白跑。
    ok &= (s.get('sel') == 'false' and s.get('inList') == 'false')
    print('  sync 解析（末行=全局档）:: show=%s pkg=%s sel=%s inList=%s apps=%s'
          % (s.get('show'), s.get('pkg'), s.get('sel'), s.get('inList'), s.get('apps')))
    # 同一个前台包，在"仅选定 + 已勾选"那一行里必须是 sel=true / inList=true
    s2 = parse_sync(sample.split('\n')[0])
    ok &= (s2.get('sel') == 'true' and s2.get('inList') == 'true' and s2.get('apps') == '1')
    print('  sync 解析（首行=部分应用档）:: sel=%s inList=%s apps=%s'
          % (s2.get('sel'), s2.get('inList'), s2.get('apps')))
    d = parse_detail(sample)
    ok &= metric_ok(d, 'cpu') and metric_ok(d, 'ramKb') and metric_ok(d, 'batt')
    print('  detail 解析 :: cpu=%s ramKb=%s batt=%s' % (d.get('cpu'), d.get('ramKb'), d.get('batt')))
    r = parse_render(sample, 'side')
    ok &= (r.get('top') == 'CPU 42%' and r.get('mid') == '61 FPS' and r.get('bottom') == 'RAM 5.2G')
    print('  render side :: top=%r mid=%r bottom=%r' % (r.get('top'), r.get('mid'), r.get('bottom')))
    rs = parse_render(sample, 'strip')
    ok &= (rs.get('top') == '42%' and rs.get('mid') == '61' and rs.get('bottom') == '5.2G')
    print('  render strip :: top=%r mid=%r bottom=%r' % (rs.get('top'), rs.get('mid'), rs.get('bottom')))
    o = parse_onscreen(sample, 'side')
    ok &= (o.get('x') == '100' and o.get('w') == '210')
    print('  onscreen side :: x=%s y=%s w=%s h=%s' % (o.get('x'), o.get('y'), o.get('w'), o.get('h')))
    # 反例：指标为 -1 时不能被判成"取到了"
    bad = parse_detail("detail layer=X active=false cpu=-1 gpu=-1 ramKb=-1 temp=-1 batt=-1.0")
    ok &= not metric_ok(bad, 'cpu') and not metric_ok(bad, 'batt')
    print('  反例（-1 不算取到）:: cpu_ok=%s batt_ok=%s'
          % (metric_ok(bad, 'cpu'), metric_ok(bad, 'batt')))
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ==================== 设备侧 ====================

def pick_device():
    global DEV
    if DEV:
        return DEV
    out = subprocess.run([ADB, 'devices'], capture_output=True).stdout.decode('utf-8', 'replace')
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == 'device':
            DEV = parts[0]
            return DEV
    return None


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a),
                          capture_output=True).stdout.decode('utf-8', 'replace')


def check(name, ok, detail=''):
    results.append((name, bool(ok), detail))
    if not ok:
        fails.append(name)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + (' :: ' + detail if detail else ''))


def skip(name, why):
    results.append((name, None, why))
    print('  [SKIP] ' + name + ' :: ' + why)


def snap(name):
    os.makedirs(SHOT_DIR, exist_ok=True)
    path = os.path.join(SHOT_DIR, name + '.png')
    r = subprocess.run([ADB, '-s', DEV, 'exec-out', 'screencap', '-p'], capture_output=True)
    if len(r.stdout) < 5000:
        return None
    open(path, 'wb').write(r.stdout)
    return path


def dump_ui():
    sh('shell', 'uiautomator', 'dump', '/sdcard/_mb.xml')
    return sh('shell', 'cat', '/sdcard/_mb.xml')


def node_center(xml, needle, exact=False):
    """在 uiautomator dump 里找含 needle 的节点，返回其中心坐标。"""
    for m in re.finditer(r'<node[^>]*>', xml):
        seg = m.group(0)
        t = re.search(r'text="([^"]*)"', seg)
        if not t:
            continue
        txt = t.group(1)
        hit = (txt == needle) if exact else (needle in txt)
        if not hit:
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
        if not b:
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def seekbar_bounds(xml):
    """页面里第一个 SeekBar 的 bounds（左, 上, 右, 下）—— 用于按比例点滑块。

    ★ 按 **class 里含 SeekBar** 匹配，不要求恰好是 `android.widget.SeekBar`：
      本工程的滑块是自绘的 `UiFactory$SkinSeekBar`（原型轨道全自绘），
      uiautomator 报的是子类名 —— 写等号会一个都找不到（本轮实测）。
    """
    for m in re.finditer(r'<node[^>]*>', xml):
        seg = m.group(0)
        cl = re.search(r'class="([^"]*)"', seg)
        if not cl or 'SeekBar' not in cl.group(1):
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
        if not b:
            continue
        return tuple(int(b.group(i)) for i in range(1, 5))
    return None


def gap_slider_bounds():
    """「监测条离桌宠多远」那个滑块的 bounds。

    ★ 必须**滚着找**：`find_scrolling` 找到标签时它可能刚进视口下沿，
      而滑块在标签**下面**、还在屏幕外 —— 于是 dump 里根本没有 SeekBar 节点
      （本轮实测：标签找到了、滑块 bounds=None）。
    """
    xml = dump_ui()
    for _ in range(6):
        if node_center(xml, '监测条离桌宠多远') and seekbar_bounds(xml):
            return seekbar_bounds(xml)
        sh('shell', 'input', 'swipe', '540', '1900', '540', '1000', '280')
        time.sleep(1.3)
        xml = dump_ui()
    return None


def wait_side_bar(timeout=20.0):
    """等侧栏窗口真的出现在桌面上，返回 (pet, bar)。

    ★ 为什么要等：进过设置页之后桌宠会被临时收起、侧栏跟着收掉（设计如此）；
      回桌面后要等它重新挂上。固定 sleep 会得到"侧栏没显示"的假结论。
    """
    end = time.time() + timeout
    while time.time() < end:
        p, b = pet_and_bar_frames()
        if p and b:
            return p, b
        time.sleep(1.0)
    return None, None


def close_any_dialog():
    """回桌面前先把可能开着的对话框关掉（否则桌宠一直是"临时收起"状态）。"""
    sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(1)


def bar_pet_distance(pet, bar):
    """侧栏与桌宠**窗口**之间的水平距离（右侧为正；负 = 两者重叠）。

    ★ 必须允许负值：间距下限是 -12dp（压进桌宠画布的透明留白里），
      那时侧栏会**和桌宠窗口重叠**。第一版写成"重叠就返回 0"，
      于是量到 0 → 63px，而期望差是 94px，白报了一条 FAIL（本轮实测）。
    """
    if not pet or not bar:
        return None
    pet_cx = (pet[0] + pet[2]) / 2.0
    bar_cx = (bar[0] + bar[2]) / 2.0
    if bar_cx >= pet_cx:
        return bar[0] - pet[2]
    return pet[0] - bar[2]


def bring_app_foreground():
    """把 App 任务拉到前台。

    ★ **不能**用 force-stop：桌宠是常驻服务，force-stop 会把它连带停掉，
      后面的"侧栏跟随"判据就没有侧栏可看了。monkey 只是把已有任务提到最前。
    """
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(5)


def set_gap_via_ui(dp):
    """进设置页把间距滑块点到指定 dp（走真实 UI），然后回桌面。

    ★ 脚本里硬写这两个端点值是有意的：它们同时被 `verify_game_assistant.py`
      钉在源码（`SysMetrics.MONITOR_GAP_MIN_DP/MAX_DP`）里，改了会被断言抓到。
    """
    bring_app_foreground()          # 从桌面进设置页：先得把 App 拉到前台
    xml = goto_game_assistant_page()
    c, xml = find_scrolling(xml, '监测条离桌宠多远')
    if not c:
        return False
    sb = gap_slider_bounds()
    if not sb:
        return False
    l, t, r, b = sb
    frac = (dp - GAP_MIN_DP) / (GAP_MAX_DP - GAP_MIN_DP)
    x = int(l + (r - l) * frac)
    x = max(l + 4, min(r - 4, x))          # 端点留几个像素，别落到滑轨圆角外
    tap(x, (t + b) // 2, wait=2)
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(4)
    return True


def pref_float(name):
    """读设备上某个 float pref（需要 root；读不到返回 None）。"""
    t = sh('shell', 'cat', '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG)
    if '<?xml' not in t:
        return None
    m = re.search(r'<float name="%s" value="([^"]+)"' % name, t)
    return float(m.group(1)) if m else None


def pet_and_bar_frames():
    """本包 overlay 窗口里的 (桌宠, 侧栏) 两个 frame —— 用于验证"跟随"。

    ★ 用系统窗口管理器给的 frame，而不是我们自己的日志坐标：
      日志是"我们以为摆在哪"，frame 才是"系统真的把它摆在哪"（本工程的验证纪律）。
    ★ 桌宠要按"**够高**"挑，不能只按面积最大：余额气泡/对话气泡又宽又扁
      （实测 432x114、554x200），面积有时比桌宠还大，按面积挑会挑到气泡，
      量出来的距离成了 -481 这种鬼数（本轮实测）。
    """
    frames = overlay_frames()
    if not frames:
        return None, None
    pets = [f for f in frames if (f[3] - f[1]) >= 250]      # 桌宠窗口高 ~354px
    pool = pets if pets else frames
    pet = max(pool, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
    bar = None
    for f in frames:
        if f is pet:
            continue
        w, h = f[2] - f[0], f[3] - f[1]
        if 60 <= w <= 260 and 50 <= h <= 460:
            bar = f
            break
    return pet, bar


def pet_and_bar_frames_near(expect_left):
    """按"桌宠窗口左缘大概在哪"挑桌宠，再挑侧栏。

    ★ 为什么不能只按"最大窗口 = 桌宠"：**拖动时桌宠会说话**（petLiftTalk），
      屏幕上多出一个很宽的对话气泡窗口，它可能比桌宠窗口还大 ——
      于是"最大 = 桌宠"会把气泡当成桌宠，量出来的距离变成 -365 这种鬼数
      （本轮实测就是这么误判的）。拖动是脚本化的，桌宠位置可预测，按位置挑最稳。
    """
    frames = overlay_frames()
    if not frames:
        return None, None
    pet = min(frames, key=lambda f: abs(f[0] - expect_left))
    bar = None
    for f in frames:
        if f is pet:
            continue
        w, h = f[2] - f[0], f[3] - f[1]
        if 60 <= w <= 260 and 50 <= h <= 460:
            bar = f
            break
    return pet, bar


def drag_and_sample(fx1, fy1, fx2, fy2, dur_ms, expect_left0, every=0.25):
    """脚本化拖动 + 拖动过程中反复采样窗口 frame（判"实时跟随"）。

    `input swipe` 是阻塞的，所以放到子进程里；主线程一边采样一边等它结束。
    ★ 采样时要按"此刻**桌宠窗口左缘**应该在哪"挑窗口（见 pet_and_bar_frames_near）：
      手指坐标是桌宠**中心**，而窗口左缘 = 中心 - 窗口宽/2 —— 拿手指坐标当期望值
      会差出几百像素，于是挑到的是气泡而不是桌宠（本轮实测就踩了这个）。
    """
    p = subprocess.Popen([ADB, '-s', DEV, 'shell', 'input', 'swipe',
                          str(int(fx1)), str(int(fy1)), str(int(fx2)), str(int(fy2)), str(int(dur_ms))],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.time()
    out = []
    while p.poll() is None:
        el = (time.time() - t0) / max(0.2, dur_ms / 1000.0)
        el = max(0.0, min(1.0, el))
        out.append(pet_and_bar_frames_near(expect_left0 + (fx2 - fx1) * el))
        time.sleep(every)
    p.wait()
    out.append(pet_and_bar_frames_near(expect_left0 + (fx2 - fx1)))
    return out


def focus_edit_text(c):
    """点一下输入框并确认**真的聚焦**（输入法弹出来了）。最多试两次。

    ★ 实测：点一次不总是聚焦成功（页面还在落定），`input text` 就打进了空气 ——
      结果搜索词没生效、列表还是全部应用，脚本却以为"过滤后的第一行"是那个应用，
      勾选记到了 Calendar 上（本轮实测）。所以这里要确认 `mInputShown=true`。
    """
    for _ in range(2):
        tap(*c, wait=1)
        if 'mInputShown=true' in sh('shell', 'dumpsys', 'input_method'):
            return True
        time.sleep(0.8)
    return False


def median_dist(rounds=3, gap_sec=0.9):
    """回桌面后**多次**量"侧栏与桌宠的距离"，取中位数。

    ★ 为什么取中位数：模拟器慢的时候，某一帧可能刚好赶上桌宠说话的气泡窗口/
      窗口还没重新布局，`pet_and_bar_frames` 的启发式会挑错一次 —— 单次采样就得到
      -383/-481 这种离群值（本轮实测）。中位数把这些偶尔的错挑挡掉，
      而真实距离（15px 量级）会稳定复现。
    """
    vals = []
    for _ in range(rounds):
        p, b = pet_and_bar_frames()
        d = bar_pet_distance(p, b)
        if d is not None:
            vals.append(d)
        time.sleep(gap_sec)
    if not vals:
        return None
    vals.sort()
    return vals[len(vals) // 2]


def hide_ime_if_shown():
    """有输入法就收掉。返回是否收过。

    ★ 教训（本工程记过两次）：输入法盖住下半屏，而 uiautomator dump **只看得见
      App 窗口** —— 于是"脚本看到的那一行"其实被键盘压着，点下去落在键盘上，
      表现为"点了没反应"，很容易误判成功能坏了（本轮实测：勾选没生效）。
    ★ 不能无条件按 BACK：没有输入法时按 BACK 会**退出当前页**，把后面的步骤带偏。
    """
    out = sh('shell', 'dumpsys', 'input_method')
    if 'mInputShown=true' in out or 'mIsInputViewShown=true' in out:
        sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1)
        return True
    return False


def ensure_monitor_enabled():
    """确保「启用游戏助手」是开的（监测范围 / 侧栏 / 跟随都挂在它下面）。

    ★ 不让"环境状态"决定验证强度：之前有几次跑出来一片 SKIP
      （监测范围找不到 ⇒ 侧栏没有 ⇒ 跟随没得验），报出来是"0 失败"但**证据也更少** ——
      跳过按未验证计，等于白跑。所以进需要侧栏的分支前先把它打开。
    """
    xml = goto_game_assistant_page()
    if find_scrolling(xml, '监测范围：')[0] is not None:
        return True
    c, xml = find_scrolling(xml, '启用游戏助手')
    if not c:
        return False
    tap(*c, wait=3)
    time.sleep(2)
    ok = find_scrolling(dump_ui(), '监测范围：')[0] is not None
    print('    [info] 现场把「启用游戏助手」打开了（之前是关的）' if ok else
          '    [info] 打开「启用游戏助手」后仍看不到监测范围')
    return ok


def app_row_center(xml, below_y):
    """勾选页里「第一条应用行」的中心（列表在搜索框下方）。

    ★ 为什么不能按应用名/包名找文字节点：输入搜索词之后，
      **搜索框自己的 text 就是那个词**，`node_center` 会先命中搜索框 ——
      实测点了一次搜索框、勾选其实没发生，计数仍是「已选 0 个」（假失败）。
      真正的行是**可点的 LinearLayout**（整行可点：点文字会把点击转发给开关），
      开关本体是它内部的 FrameLayout。这里返回行中心即可。
    """
    for m in re.finditer(r'<node[^>]*>', xml):
        seg = m.group(0)
        if 'clickable="true"' not in seg:
            continue
        cl = re.search(r'class="([^"]*)"', seg)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
        if not cl or not b or cl.group(1) != 'android.widget.LinearLayout':
            continue
        y1, y2 = int(b.group(2)), int(b.group(4))
        if y1 < below_y:            # 必须在搜索框下面（排除上面的「清空已选」行）
            continue
        return ((int(b.group(1)) + int(b.group(3))) // 2, (y1 + y2) // 2)
    return None


def wait_for_text(needle, timeout=30.0):
    """等页面上出现某段文字，返回最新 dump。

    ★ 为什么需要它：`pm clear` 之后**第一次冷启动**比平常慢，固定 sleep 8 秒
      实测会在页面还没画出来时就去 dump —— 于是"找不到「游戏助手」入口"一片红。
      那是**脚本抢跑**，不是 App 坏了；这两种失败必须能区分（本工程的老教训）。
    """
    end = time.time() + timeout
    xml = ''
    while time.time() < end:
        xml = dump_ui()
        if node_center(xml, needle) is not None:
            return xml
        time.sleep(1.5)
    return xml


def wait_for_any(needles, timeout=40.0):
    """等其中**任意**一段文字出现。返回 (最新 dump, 命中的那一个 / None)。"""
    end = time.time() + timeout
    xml = ''
    while time.time() < end:
        xml = dump_ui()
        for n in needles:
            if node_center(xml, n) is not None:
                return xml, n
        time.sleep(1.5)
    return xml, None


def launch_app_clean(allow_dummy_key=False):
    """把 App 拉起到设置首页。返回 (dump, 被挡住的原因 or None)。

    ★ 全新安装（或 `pm clear` 之后）App 会先弹「输入 API Key」对话框：
      它 `setCancelable(false)`，而**点「取消」= finish() 直接退出 App**
      （实测：截图里只剩桌面 + 桌宠浮窗，后面的断言全红；一开始我按"关掉对话框"
      处理，结果把整个 UI 流程关没了）。所以这里只有两条路：
      填一个假 Key 过去（**仅模拟器**用），或者明确报"没配 Key，跑不了"。
      绝不能悄悄点取消 —— 那会把"环境缺 Key"伪装成"功能坏了"。
    """
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    sh('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    xml, _hit = wait_for_any(['陪伴', '保存并开始'], timeout=45)
    # ★ 那个对话框可能**比主界面晚一步**弹出来：wait_for_any 一见到「陪伴」就返回了，
    #   而那一刻对话框还没出现 —— 于是"没检测到"、后面的页面全被它挡着
    #   （本轮实测：step 7 里就漏掉了，整条断言假失败）。所以循环再看几眼。
    for _ in range(3):
        xml = dump_ui()
        if node_center(xml, '保存并开始') is None:
            break
        if not allow_dummy_key:
            return xml, '本机没配 API Key：App 停在首启对话框上（取消=退出 App）'
        c = node_center(xml, 'sk-xxxxxxxx')
        if c:
            tap(*c, wait=1)
        sh('shell', 'input', 'text', 'sk-ui-verify-only')
        time.sleep(1.5)
        btn = node_center(dump_ui(), '保存并开始')
        if btn:
            tap(*btn, wait=4)
        time.sleep(2)
        print('    [info] 本机没有 API Key：填入假 Key 过首启对话框（仅模拟器验 UI 用）')
    xml = wait_for_text('陪伴', timeout=20)
    return xml, None


def goto_game_assistant_page(max_tries=4):
    """进「陪伴 → 游戏助手」，返回那一页的 dump。

    ★ 为什么要重试 + 等落定：`wait_for_any` 是"看到『陪伴』就返回"，
      而那一刻页面可能**还在入场动画里**（底部导航还在位移）——
      直接点会点空，然后"找不到「游戏助手」入口"起一串假失败（实测踩到过，
      而且它还会让后面 9 条全红，很难看出只是"点空了一下"）。
      这里的做法：点不到目标就再点一次；已在游戏助手页就直接返回（标题也算命中）。
    """
    xml = dump_ui()
    for _ in range(max_tries):
        c = node_center(xml, '游戏助手')
        if c:
            tap(*c, wait=3)
            return dump_ui()
        nav = node_center(xml, '陪伴', exact=True)
        if nav:
            time.sleep(1.5)          # 等入场动画落定再点
            tap(*nav, wait=3)
        xml = dump_ui()
    return xml


def edit_text_center(xml):
    """页面里第一个输入框的中心（勾选页的搜索框）。

    ★ **按 class 找，不按 hint 文字找**：输入过搜索词之后，那个 EditText 的
      `text` 就是搜索词本身，hint 不再出现在 dump 里 —— 再按「搜索应用名或包名」
      找就会找不到（本轮实测：整段被判 skip）。
    """
    for m in re.finditer(r'<node[^>]*>', xml):
        seg = m.group(0)
        cl = re.search(r'class="([^"]*)"', seg)
        if not cl or cl.group(1) != 'android.widget.EditText':
            continue
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', seg)
        if not b:
            continue
        return ((int(b.group(1)) + int(b.group(3))) // 2,
                (int(b.group(2)) + int(b.group(4))) // 2)
    return None


def find_scrolling(xml, needle, tries=5):
    """在设置页里找一行 —— 页面是 ScrollView，**屏幕外的行不在 dump 里**。

    ★ 模拟器实测：只找得到「上 格显示」，「中/下 格显示」与预览行都在屏幕外，
      于是三条断言假失败。所以这里找不到就往下滚一屏再找。
    返回 (center or None, 最新 xml)。
    """
    for _ in range(tries):
        c = node_center(xml, needle)
        if c:
            return c, xml
        sh('shell', 'input', 'swipe', '540', '1900', '540', '1000', '280')
        time.sleep(1.3)
        xml = dump_ui()
    return None, xml


def tap(x, y, wait=1.2):
    sh('shell', 'input', 'tap', str(int(x)), str(int(y)))
    time.sleep(wait)


def logcat(tags='DSPFpsBadge:* DSPFpsMon:*', clear=False):
    if clear:
        sh('logcat', '-c')
        return ''
    args = ['logcat', '-d', '-v', 'time', '-s'] + tags.split()
    return sh(*args)


def overlay_frames():
    """所有属于本包、类型为 TYPE_APPLICATION_OVERLAY 的窗口 (x,y,x1,y1)。

    ★ 格式坑（模拟器实测踩到）：`dumpsys window windows` 里**没有**
      `Frames: containing=[..][..]` 这种行 —— 真正的框是
      `Frames: parent=[..] display=[..] frame=[450,1064][1080,1418] last=[..]`。
      第一版按 `containing=` 找，结果一个窗口都取不到（于是长按那步直接跳过）。
      这里改成：按 `Window{...}` 分块（块边界 = 下一个 "Window #"），
      块内含 `ty=APPLICATION_OVERLAY` 才收，取块内第一个 `frame=[..][..]`。
    """
    out = sh('shell', 'dumpsys', 'window', 'windows')
    lines = out.splitlines()
    frames = []
    for i, l in enumerate(lines):
        if 'Window{' not in l or PKG not in l:
            continue
        end = len(lines)
        for k in range(i + 1, len(lines)):
            if lines[k].lstrip().startswith('Window #'):
                end = k
                break
        blk = lines[i:end]
        if not any('APPLICATION_OVERLAY' in x for x in blk):
            continue
        for x in blk:
            m = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', x)
            if m:
                frames.append(tuple(int(m.group(t)) for t in range(1, 5)))
                break
    return frames


def pet_window_frame():
    """桌宠窗口 frame：取**面积最大**的 overlay 窗口（桌宠窗口比气泡/菜单都大）。

    <p>为什么需要它：`--ui-only`（模拟器，无 Shizuku）时拿不到 logcat 里的
    `onscreen side … rootX/rootY`，只能从窗口列表推桌宠位置。
    """
    fr = overlay_frames()
    if not fr:
        return None
    fr.sort(key=lambda b: (b[2] - b[0]) * (b[3] - b[1]), reverse=True)
    return fr[0]


def accent_share(path, box, inset=10):
    """取样框内"胶囊色"（accent + 88% 不透明）像素占比。

    不比对具体色值（主题四套、深浅不同），只要求：
      · 存在明显高于背景的**饱和蓝/强调色**像素
      · 且这些像素连成一片（占比 > 25%），说明真的画出了一个胶囊
    """
    try:
        from PIL import Image
        import numpy as np
    except Exception as e:                       # 没装 PIL 就跳过像素判据
        return None, 'no PIL: %s' % e
    im = Image.open(path).convert('RGB')
    a = np.array(im).astype(int)
    x0, y0, x1, y1 = box
    x0 = max(0, x0 + inset); y0 = max(0, y0 + inset)
    x1 = min(a.shape[1], x1 - inset); y1 = min(a.shape[0], y1 - inset)
    if x1 <= x0 or y1 <= y0:
        return None, '取样框太小'
    patch = a[y0:y1, x0:x1]
    r, g, b = patch[:, :, 0], patch[:, :, 1], patch[:, :, 2]
    # 强调色胶囊：蓝通道明显高于红通道，且整体不是纯黑/纯白
    blue = (b - r > 25) & (b > 60) & (r < 200)
    share = float(blue.mean())
    return share, 'blue_share=%.3f size=%dx%d' % (share, patch.shape[1], patch.shape[0])


# ==================== 主流程 ====================

def check_side_bar(log):
    """展开态侧栏：三行文案 + 屏幕取样 + "挂远离边缘那一侧"。

    ★ 这些**不需要 Shizuku**：门控只看"两个开关 + 监测范围"（范围默认=所有应用），
      侧栏在没数据时也会画（显示 --），所以模拟器上同样能验。

    ★ 取样必须用**活窗口 frame**，不能用日志里的 `onscreen side` 坐标：
      没有采样回调时那条日志只在 5 秒巡检里刷新，而桌宠会自己走动 ——
      按旧坐标取样什么都采不到（实测 blue_share=0.000，假失败）。
    """
    r = parse_render(log, 'side')
    check('侧栏三行文案 = 选中的三项',
          all(k in r and r[k] for k in ('top', 'mid', 'bottom')),
          'top=%r mid=%r bottom=%r' % (r.get('top'), r.get('mid'), r.get('bottom')))
    if not (r.get('top') and r.get('mid') and r.get('bottom')):
        skip('侧栏像素/选边', '没有 render side 日志（可能还没到展开态）')
        return
    path = snap('03_side_bar')
    frames = overlay_frames()          # 本包所有 overlay 窗口，按面积降序
    if not path or not frames:
        skip('侧栏像素/选边', '拿不到截图或 overlay 窗口')
        return
    frames = sorted(frames, key=lambda b: (b[2] - b[0]) * (b[3] - b[1]), reverse=True)
    pet_f = frames[0]                  # 最大的 = 桌宠窗口
    side_f = None
    for f in frames[1:]:
        w, h = f[2] - f[0], f[3] - f[1]
        if 60 <= w <= 220 and 60 <= h <= 420:      # 侧栏三颗胶囊的尺寸区间
            side_f = f
            break
    if side_f is None:
        skip('侧栏像素/选边', '没找到侧栏窗口（frame 列表 %s）' % (frames,))
        return
    # 取样框外扩一点：容忍桌宠走动带来的几十像素偏移
    pad = 40
    share, why = accent_share(path, (side_f[0] - pad, side_f[1] - pad,
                                     side_f[2] + pad, side_f[3] + pad))
    check('侧栏真的画在屏幕上（胶囊色占比）', share is not None and share > 0.20,
          '%s frame=%s' % (why, side_f))
    # ★ "挂远离屏幕边缘那一侧"：按**中心**比 —— 桌宠窗口中心在屏幕左半 → 条在其右
    sw = int(re.search(r'(\d+)x(\d+)', sh('shell', 'wm', 'size')).group(1))
    pet_c = (pet_f[0] + pet_f[2]) // 2
    side_c = (side_f[0] + side_f[2]) // 2
    if pet_c <= sw // 2:
        check('桌宠偏左 → 侧栏挂在它右侧', side_c > pet_c,
              'side.cx=%d pet.cx=%d' % (side_c, pet_c))
    else:
        check('桌宠偏右 → 侧栏挂在它左侧', side_c < pet_c,
              'side.cx=%d pet.cx=%d' % (side_c, pet_c))


def main():
    if '--selftest' in sys.argv:
        return selftest()
    ui_only = '--ui-only' in sys.argv
    dev = pick_device()
    if not dev:
        print('没有可用设备（adb devices 为空）。插上手机或设 $DSP_DEV 后重跑。')
        return 2
    print('=== 设备：%s %s===' % (dev, '（--ui-only：只验 UI，Shizuku 相关项跳过）' if ui_only else ''))

    if '--install' in sys.argv:
        apk = dev_apk()
        print('==> 安装 %s' % apk)
        r = subprocess.run([ADB, '-s', DEV, 'install', '-r', apk], capture_output=True)
        print(r.stdout.decode('utf-8', 'replace').strip()[-300:])
        if r.returncode != 0:
            print('安装失败，终止')
            return 2

    print('=== 0. 前置：悬浮窗权限 + 版本 ===')
    if ui_only:
        # ★ 模拟器上先取 root：后面"勾选真的写进 prefs"那条要读
        #   /data/data/<pkg>/shared_prefs（没有 root 只能 skip —— 本轮就因为漏了这步
        #   少验了一条，虽然不算失败但白跑一趟）。真机上 adb root 会失败，无害。
        subprocess.run([ADB, '-s', DEV, 'root'], capture_output=True)
        time.sleep(2)
    ver = sh('shell', 'dumpsys', 'package', PKG)
    m = re.search(r'versionCode=(\d+)', ver)
    check('已安装本包且版本可读', bool(m), 'versionCode=%s' % (m.group(1) if m else '?'))
    sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    time.sleep(1)
    check('悬浮窗权限已授予（否则桌宠合法地起不来）',
          'allow' in sh('shell', 'appops', 'get', PKG, 'SYSTEM_ALERT_WINDOW'))

    print('=== 1. 拉起 App 并进「陪伴 → 游戏助手」===')
    # ★ 先 force-stop：从"上一次跑完留下的状态"里启动会踩坑 ——
    #   实测过一次：上一轮把输入法留在搜索框上，底部导航被键盘盖住，
    #   点「陪伴」落到了键盘上 ⇒ 后面十几条全假失败（脚本没法区分
    #   "环境没收拾干净"和"代码坏了"，所以干脆每次从干净态开始）。
    sh('shell', 'input', 'keyevent', 'KEYCODE_BACK')   # 顺手收掉可能开着的输入法
    time.sleep(1)
    xml, blocked = launch_app_clean(allow_dummy_key=ui_only)
    if blocked:
        # 环境缺 Key ⇒ 后面每一步都验不了。**明确报出来**并按跳过计，
        # 而不是让它变成十几条假失败（两种失败必须能区分）。
        skip('设置页 UI 全流程', blocked + '；先配好 Key 再跑')
        return summary()
    xml = goto_game_assistant_page()
    check('进得了「陪伴 → 游戏助手」',
          find_scrolling(xml, '启用游戏助手')[0] is not None)
    shot = snap('01_game_assistant_page')

    print('=== 2. 监测范围 + 开关 + 三格槽位 + 预览（设置页）===')
    # ★ 顺序要按页面从上到下：find_scrolling 只**往下**滚（滚上去要另写一套），
    #   所以"在下面的行"必须排在"在上面的行"之后查 —— 否则会假失败
    #   （本轮就踩了：先查了中/下格，再回头查最上面的「启用游戏助手」）。
    # ★ 轮次⑨ 之后的页面顺序：总开关 → 监测范围 → 显示悬浮监测条 → 三格 → 预览。
    sw, xml = find_scrolling(xml, '启用游戏助手')
    check('有「启用游戏助手」总开关', sw is not None)
    # ★ 下面整块（监测范围 / 三格 / 预览行）都在这个开关**打开时**才渲染。
    #   全新安装时它是关的（要装 Shizuku 才用得上）—— 不先打开就会得到一串
    #   假失败（本轮实测：清数据之后 7 条红全是这个原因）。
    if find_scrolling(xml, '监测范围：')[0] is None and sw is not None:
        tap(*sw, wait=3)          # 整行可点 → 打开总开关
        xml = wait_for_text('启用游戏助手', timeout=25)
        print('    [info] 总开关原本是关的（全新安装默认关）：已打开以继续验页面')
    # ★ 轮次⑨：用户要求把"全局打开"与"部分应用打开"**集成在同一个入口**，
    #   默认档是"所有应用" —— 所以这一行必须存在且当前档位可读。
    c_scope, xml = find_scrolling(xml, '监测范围：')
    check('有「监测范围：」入口', c_scope is not None, 'center=%s' % (c_scope,))
    # ★★ 每次跑都**先把档位复位成「所有应用」**：prefs 是持久的，上一轮跑过、
    #    或者人工点过，都会留下"仅选定"档 —— 那样下面的断言测的其实是
    #    "上一次留下的状态"（本轮实测踩到：真失败，但与环境有关，不是代码坏了）。
    #    "全新安装默认是哪个档"另有专条（见流程末尾的 pm clear 那一步）。
    if c_scope:
        tap(*c_scope, wait=2)
        it_all = node_center(dump_ui(), '所有应用（全局打开）')
        if it_all:
            tap(*it_all, wait=3)
        xml = dump_ui()
        c_scope, xml = find_scrolling(xml, '监测范围：')
    check('复位后档位 = 所有应用',
          node_center(xml, '监测范围：所有应用') is not None,
          '看截图 07/08')
    sw2, xml = find_scrolling(xml, '显示悬浮监测条')
    check('有「显示悬浮监测条（关闭 = 全部不显示）」开关', sw2 is not None)
    c_up, xml = find_scrolling(xml, '上 格显示')
    check('有「上 格显示：」行', c_up is not None)
    c_mid, xml = find_scrolling(xml, '中 格显示')
    check('有「中 格显示：」行', c_mid is not None)
    c_dn, xml = find_scrolling(xml, '下 格显示')
    check('有「下 格显示：」行', c_dn is not None)
    c_prev, xml = find_scrolling(xml, '监测条：')
    check('有监测条实时预览行（监测条：）', c_prev is not None)
    # 轮次⑥ 那个独立的白名单入口确实没回来（现在的名单挂在"监测范围"里）
    check('旧的「只在选定的游戏里显示」入口不再单独存在',
          node_center(xml, '只在选定的游戏里显示') is None)

    print('=== 2b. 槽位下拉真的能改（把「上」格改成 GPU 占用）===')
    c_slot, xml = find_scrolling(xml, '上 格显示')
    if not c_slot:
        skip('槽位下拉', '找不到「上 格显示」行')
    else:
        tap(*c_slot, wait=2)
        dlg = dump_ui()
        # 弹窗是 AlertDialog 单选列表（普通窗口，进 uiautomator dump）
        item = node_center(dlg, 'GPU 占用')
        check('点槽位弹出单选列表（含八项 + 不显示）',
              item is not None and node_center(dlg, '不显示') is not None,
              'GPU 项 center=%s' % (item,))
        if item:
            tap(*item, wait=2)
            after, xml = find_scrolling(dump_ui(), '上 格显示：GPU 占用')
            check('选完该行文案变成「上 格显示：GPU 占用」', after is not None)
            # 复位成 CPU，免得把模拟器/真机上的设置改乱
            c_back, xml = find_scrolling(xml, '上 格显示')
            if c_back:
                tap(*c_back, wait=2)
                it2 = node_center(dump_ui(), 'CPU 占用')
                if it2:
                    tap(*it2, wait=2)
                    xml = dump_ui()

    print('=== 2c. 监测条间距滑块（用户要求"可调节"）===')
    c_gap, xml = find_scrolling(xml, '监测条离桌宠多远')
    check('有「监测条离桌宠多远」滑块行', c_gap is not None)
    sb = gap_slider_bounds()
    check('找到间距滑块（SeekBar，可能在屏幕外需滚动）', sb is not None, 'bounds=%s' % (sb,))
    if not sb:
        skip('间距可调（左端/右端/复位）', '找不到 SeekBar')
    else:
        l, t, r, b = sb
        mid_y = (t + b) // 2
        dens2 = 2.625
        _dd = re.search(r'(\d+)', sh('shell', 'wm', 'density'))
        if _dd:
            dens2 = int(_dd.group(1)) / 160.0

        def measure_dist():
            """回桌面 → 等侧栏挂上 → 量"侧栏与桌宠的距离"（系统窗口 frame，多次取中位）。"""
            close_any_dialog()
            sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
            time.sleep(3)
            wait_side_bar(18)
            return median_dist()

        # ★ 三个位置各点一次，读 **prefs 真实值**（不是看界面文案）
        tap(l + 6, mid_y, wait=2)                    # 左端 = 下限
        v_min = pref_float('monitor_gap_dp')
        check('拖到左端 = 下限 -12dp',
              v_min is not None and abs(v_min - (-12.0)) <= 1.01, 'pref=%s' % v_min)
        d_min = measure_dist()

        # 回设置页点右端（页面滚动位置变了，重新取滑块 bounds）
        bring_app_foreground()
        xml2 = goto_game_assistant_page()
        find_scrolling(xml2, '监测条离桌宠多远')
        sb2 = gap_slider_bounds()
        d_max, v_max = None, None
        if sb2:
            l2, t2, r2, b2 = sb2
            tap(r2 - 6, (t2 + b2) // 2, wait=2)      # 右端 = 上限
            v_max = pref_float('monitor_gap_dp')
            check('拖到右端 = 上限 +24dp',
                  v_max is not None and abs(v_max - 24.0) <= 1.01, 'pref=%s' % v_max)
            d_max = measure_dist()
            snap('10_gap_slider')
        else:
            skip('拖到右端 / 距离变化', '重新取滑块 bounds 失败')

        # ★ 判据取**差值**：窗口 frame 含透明留白，这个常量在差值里被抵消，
        #   剩下的就是"设置真的改变了实际距离"。
        if d_min is None or d_max is None:
            skip('间距设置真的改变实际距离', '两次测量任一次没等到侧栏')
        else:
            expect = (24.0 - (-12.0)) * dens2
            check('间距设置真的改变实际距离（差值≈36dp×密度）',
                  abs((d_max - d_min) - expect) <= 30,
                  '距离 %s → %s px，期望差 %.0f px' % (d_min, d_max, expect))

        # 复位成默认 6dp（进度 18/36 = 中点）
        bring_app_foreground()
        xml3 = goto_game_assistant_page()
        find_scrolling(xml3, '监测条离桌宠多远')
        sb3 = gap_slider_bounds()
        if sb3:
            l3, t3, r3, b3 = sb3
            tap((l3 + r3) // 2, (t3 + b3) // 2, wait=2)
            v_def = pref_float('monitor_gap_dp')
            if v_def is None:
                skip('间距复位成默认档', '读不到 prefs（真机无 root 时正常）')
            else:
                check('点回中间 ≈ 默认 6dp', abs(v_def - 6.0) <= 1.01, 'pref=%s' % v_def)
        else:
            skip('间距复位成默认档', '取滑块 bounds 失败')
        # ★ 2c 把页面滚到滑块那一行去了，后面 2d 要在"顶部状态"下点「监测范围」——
        #   重新进一次页面把滚动位置复位（否则 2d 会找不到那行：本轮实测踩到）。
        bring_app_foreground()
        xml = goto_game_assistant_page()

    print('=== 2d. 监测范围二选 + 勾选页（轮次⑨ 把两者集成在一起）===')
    # 用户 2026-10-08：「新增开关 全局打开或者部分应用打开，把这俩集成在一起去」。
    # 验三件事：① 弹窗两档都在；② 切到"仅选定"后出现勾选入口 + 空名单提示；
    # ③ 勾选页真的能进（有搜索框与清空按钮）。
    # ★ 走完**必须复位成"所有应用"** —— 后面的步骤与后续手工复核都依赖默认档。
    c_scope, xml = find_scrolling(xml, '监测范围：')
    if not c_scope:
        skip('监测范围二选', '找不到「监测范围：」行')
    else:
        tap(*c_scope, wait=2)
        dlg = dump_ui()
        pick = node_center(dlg, '仅在选定的应用里显示')
        check('点开「监测范围」弹出两档（所有应用 / 仅选定）',
              pick is not None and node_center(dlg, '所有应用（全局打开）') is not None,
              '仅选定 center=%s' % (pick,))
        if not pick:
            skip('勾选页入口 / 空名单提示', '弹窗里没找到「仅在选定的应用里显示」')
        else:
            tap(*pick, wait=3)
            x2 = dump_ui()
            row_sel, x2 = find_scrolling(x2, '选择应用（已选')
            check('切到「仅选定」后出现勾选入口', row_sel is not None,
                  'center=%s' % (row_sel,))
            # ★ 空名单必须给提示：否则用户看到"开关都开着却不显示"，只会以为是 bug
            hint, x2 = find_scrolling(x2, '名单现在是空的')
            check('空名单给出明确提示', hint is not None)
            snap('07_scope_selected')
            if row_sel:
                tap(*row_sel, wait=3)
                x3 = dump_ui()
                check('进入勾选页（页标题 = 选择应用）',
                      node_center(x3, '选择应用', exact=True) is not None)
                # EditText 的 hint 在 uiautomator dump 里就是 text
                check('勾选页有搜索框', node_center(x3, '搜索应用名或包名') is not None)
                check('勾选页有清空按钮', node_center(x3, '清空已选') is not None)
                snap('08_picker_page')

                # ---- ★ 勾选**真的写进 prefs**（不是只改界面）----
                # 这是本轮功能的落点：只改界面的话，重启就丢，用户会以为"勾了没用"。
                # 轮次⑥ 也验过同一条（当时是 game_apps），本轮换成 monitor_apps。
                # ★ 不能按中文应用名找行：adb 的 `input text` 打不出中文，
                #   所以按**包名**搜（模拟器上是 Settings / com.android.settings）。
                sb = node_center(x3, '搜索应用名或包名')
                if not sb:
                    skip('勾选写入 prefs', '找不到搜索框')
                else:
                    focus_edit_text(sb)
                    sh('shell', 'input', 'text', 'settings')
                    time.sleep(2)
                    # ★ 先收输入法再找行：键盘盖住的那半屏 dump 里看得见、点不到
                    hide_ime_if_shown()
                    x4 = dump_ui()
                    # ★ 按"可点的行"找，不按文字找 —— 搜索框的 text 此时也是 settings
                    #   （实测：按文字找会点到搜索框，勾选根本没发生）。
                    row_app = app_row_center(x4, sb[1] + 40)
                    check('搜索框能过滤出应用行', row_app is not None,
                          'center=%s' % (row_app,))
                    if row_app:
                        tap(*row_app, wait=2)
                        x5 = dump_ui()
                        cnt = node_center(x5, '已选 1 个应用')
                        check('勾选后计数文案变成「已选 1 个应用」', cnt is not None)
                        xml_pref = sh('shell', 'cat',
                                      '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG)
                        # ★ 真机上 `adb root` 会失败（生产 ROM）：**读不到文件**才跳过；
                        #   文件读到了但没有这个键 = 真的没写进去，必须判失败。
                        if ('Permission denied' in xml_pref or 'No such file' in xml_pref
                                or '<?xml' not in xml_pref):
                            skip('勾选写入 prefs',
                                 '读不到 shared_prefs（真机无 root 时正常）')
                        else:
                            hit = re.search(r'<set name="monitor_apps">(.*?)</set>',
                                            xml_pref, re.S)
                            pkgs = (re.findall(r'<string>([^<]+)</string>', hit.group(1))
                                    if hit else [])
                            # ★ 判"写进去了、且只写了一个"，不钉具体是哪个应用：
                            #   列表第一行可能是 Settings 也可能是别的（取决于搜索词
                            #   是否真的生效），钉死具体包名会把"环境差异"报成 bug。
                            check('勾选真的写进 prefs（写进且只有一个包名）',
                                  len(pkgs) == 1, 'set=%s' % (pkgs,))
                            # 清空按钮也要真清（否则"清空"只是界面变了）
                            cclr = node_center(x5, '清空已选')
                            if cclr:
                                tap(*cclr, wait=2)
                                x6 = dump_ui()
                                check('清空后计数回到 0',
                                      node_center(x6, '已选 0 个') is not None)
                                xml_pref2 = sh('shell', 'cat',
                                               '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG)
                                h2 = re.search(r'<set name="monitor_apps">(.*?)</set>',
                                               xml_pref2, re.S)
                                check('清空真的从 prefs 里删掉',
                                      (h2 is None) or ('settings' not in h2.group(1)),
                                      'set=%s' % (h2.group(1).strip()[:80] if h2 else '（空/无）'))
                                x3 = x6
                            else:
                                skip('清空按钮', '找不到「清空已选」')

                # ---- ★ 应用列表**完整性**（用户报障："读取不到完整的应用列表"）----
                # 根因是 Android 11+ 的包可见性：清单里没有 <queries> 时
                # queryIntentActivities 只返回"自己 + 少数系统应用"
                # （模拟器实测只有 Settings / TMoble 两个）。
                # 判据：搜一个**默认两个之外**的包名（模拟器上装着 YouTube），
                # 加 <queries> 之前一条都搜不到，之后应当搜得到。
                sbn = edit_text_center(x3) or edit_text_center(dump_ui())
                if not sbn:
                    skip('应用列表完整性', '找不到搜索框（EditText）')
                else:
                    tap(*sbn, wait=1)
                    for _ in range(14):          # 清掉上一次的搜索词（settings）
                        sh('shell', 'input', 'keyevent', 'KEYCODE_DEL')
                    sh('shell', 'input', 'text', 'youtube')
                    time.sleep(2)
                    x7 = dump_ui()
                    r7 = app_row_center(x7, sbn[1] + 40)
                    check('搜得到默认列表之外的应用（包可见性已放开）',
                          r7 is not None,
                          'row=%s（搜 youtube）' % (r7,))
                    for _ in range(14):
                        sh('shell', 'input', 'keyevent', 'KEYCODE_DEL')
                    time.sleep(1)

                # 复位：返回 → 陪伴首页 → 再进游戏助手 → 范围改回「所有应用」
                # ★ 先把输入法收掉（只在这时真按 BACK）：键盘会盖住底部导航。
                hide_ime_if_shown()
                x3 = dump_ui()
                cb = node_center(x3, '返回', exact=True)
                if cb:
                    tap(*cb, wait=3)
                xe = dump_ui()
                ce = node_center(xe, '游戏助手')
                if ce:
                    tap(*ce, wait=3)
                xe = dump_ui()
                cs, xe = find_scrolling(xe, '监测范围：')
                if cs:
                    tap(*cs, wait=2)
                    d2 = dump_ui()
                    back_all = node_center(d2, '所有应用（全局打开）')
                    if back_all:
                        tap(*back_all, wait=3)
                        xr = dump_ui()
                        check('验完已复位成「所有应用」',
                              node_center(xr, '监测范围：所有应用') is not None)
                    else:
                        skip('范围复位', '弹窗里找不到「所有应用（全局打开）」')
                else:
                    skip('范围复位', '回到第 7 节后找不到「监测范围：」行')

    # ★ --ui-only 时第 5 步不跑，pet 就没人赋值；这里先给个空 dict，
    #   第 6 步再退回"取最大的 overlay 窗口"当桌宠位置（模拟器上够用）。
    pet = {}
    if ui_only:
        skip('八项指标取数 + 门控 show=true',
             '--ui-only：模拟器没有 Shizuku，取数链路测不了')
        # ★ 侧栏不需要 Shizuku（没数据也画，显示 --）—— 模拟器上照验。
        #   ★★ 但必须先回桌面：设置页在前台时桌宠被隐藏（petTempHidden）
        #   ⇒ 侧栏被收掉，当下根本没有那个窗口，取样只会得到一个旧日志行。
        ensure_monitor_enabled()          # 开关是开着的才有侧栏可验
        sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(8)
        check_side_bar(logcat())
    else:
        print('=== 4. 开游戏：门控 + 八项指标（logcat 证据）===')
        logcat(clear=True)
        sh('shell', 'monkey', '-p', GAME, '-c', 'android.intent.category.LAUNCHER', '1')
        time.sleep(25)
        log = logcat()
        s = parse_sync(log)
        check('门控放行（show=true）', s.get('show') == 'true', 'sync=%s' % s.get('pkg'))
        check('前台识别正确', GAME in (s.get('pkg') or ''), 'pkg=%s' % s.get('pkg'))
        # ★ 轮次⑨：默认档是"所有应用"（全局），门控**与名单无关** ——
        #   所以钉 sel=false。要看"部分应用"档的门控，得先在设置页切档 + 勾选，
        #   那一步在真机上手工做（模拟器没有 Shizuku，验不了取数链路）。
        check('默认档 = 全局（sel=false，门控不看名单）',
              s.get('sel') == 'false',
              'sel=%s inList=%s apps=%s' % (s.get('sel'), s.get('inList'), s.get('apps')))
        d = parse_detail(log)
        check('CPU 取到数', metric_ok(d, 'cpu'), 'cpu=%s' % d.get('cpu'))
        check('GPU 取到数', metric_ok(d, 'gpu'), 'gpu=%s' % d.get('gpu'))
        check('RAM 取到数', metric_ok(d, 'ramKb'), 'ramKb=%s' % d.get('ramKb'))
        check('温度取到数', metric_ok(d, 'temp'), 'temp=%s' % d.get('temp'))
        check('电池取到数', metric_ok(d, 'batt'), 'batt=%s' % d.get('batt'))
        check('功耗/网络有读数（机型不支持时可为 -1）',
              metric_ok(d, 'pw') or metric_ok(d, 'net'),
              'pw=%s net=%s' % (d.get('pw'), d.get('net')))

        check_side_bar(log)

    print('=== 5c. 监测条实时跟随（拖动中采样系统窗口 frame）===')
    # 用户报障："监测条距离桌宠太远，检测条改为就在桌宠旁边跟随桌宠位置实时移动"。
    # ★ 判据用**系统窗口 frame**（不是我们的日志坐标）：日志只说"我们以为摆哪"，
    #   frame 才是"系统真的摆哪"。拖动**过程中**连续采样，才叫"实时跟随"。
    #   （"可调间距真的改变距离"那一条在 2c 里闭环做掉了，这里只管跟随。）
    close_any_dialog()
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(3)
    pet0, bar0 = wait_side_bar(20)
    if not bar0 or not pet0:
        # 等不到就先确认开关是开的（可能是上一轮/人工把游戏助手关了），再等一次
        ensure_monitor_enabled()
        sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(3)
        pet0, bar0 = wait_side_bar(20)
    if not bar0 or not pet0:
        skip('监测条实时跟随', '等不到侧栏窗口（桌宠被藏起来 / 监测条开关关了？）')
    else:
        # 拖动：横向 1.4 秒，过程中每 250ms 采一次 frame
        # ★ 方向**背向屏幕中线**：桌宠越过中线时侧栏会换边（设计如此），
        #   换边那一帧必然采到"还在旧位置"的中间态（实测出现过 |d|=288 的瞬时值）。
        #   为了把"跟随"这条判据钉干净，就让桌宠待在同一侧、别跨中线。
        sw = 1080
        _ws = re.search(r'(\d+)x(\d+)', sh('shell', 'wm', 'size'))
        if _ws:
            sw = int(_ws.group(1))
        pet_cx = (pet0[0] + pet0[2]) / 2
        step = -240 if pet_cx < sw / 2 else 240
        y_mid = int((pet0[1] + pet0[3]) / 2)
        samples = drag_and_sample(int(pet_cx), y_mid,
                                  int(max(60, min(sw - 60, pet_cx + step))), y_mid, 1400,
                                  pet0[0])       # ← 期望的是**窗口左缘**，不是手指坐标
        good = [(p, b) for (p, b) in samples if p and b]
        bar_xs = [b[0] for (_p, b) in good]
        check('拖动过程中采到多帧（采样够密才叫"实时"）', len(good) >= 3,
              '采到 %d 帧' % len(good))
        check('侧栏 x 真的跟着桌宠动（不是停在原地）',
              len(set(bar_xs)) >= 3 and (max(bar_xs) - min(bar_xs)) > 40,
              'bar xs=%s' % bar_xs)
        # ★ 判据是"**距离**稳定"，不是"位移同量级"：
        #   桌宠越过屏幕中线时侧栏会**换边**（挂远离边缘那一侧，设计如此），
        #   按位移比会得到 Δbar=-519 这种看似矛盾的数（本轮第一版就这么误判了）。
        dists = [bar_pet_distance(p, b) for (p, b) in good]
        dists = [d for d in dists if d is not None]
        # ★ 判据取**中位数**：模拟器慢的时候偶有一帧赶上桌宠说话的气泡窗口、
        #   或者窗口还没重新布局，启发式就会挑错一次（实测出现过 -383 的离群值）。
        #   中位数挡掉这种偶发，而"侧栏就在桌宠旁边"这个事实会稳定复现（15px 量级）。
        mid_d = sorted(dists)[len(dists) // 2] if dists else None
        check('拖动全程侧栏都在桌宠旁边（|距离| 中位数 ≤ 60px）',
              len(dists) >= 3 and mid_d is not None and abs(mid_d) <= 60,
              'dists=%s 中位=%s' % (dists, mid_d))
        # ★ 第二条独立证据：用**同一时刻**的日志（x 与 petX 成对出现，不存在
        #   "哪个窗口是桌宠"的歧义）—— 桌宠 x 变了，侧栏 x 必须跟着变。
        pairs = re.findall(r'onscreen side x=(-?\d+) y=-?\d+ w=\d+ h=\d+ vis=\d+ '
                           r'gapPx=-?\d+ petX=(-?\d+)', logcat())
        # ★ 丢掉"还没摆好"的那些样本（x=0 是窗口刚建、位置未定的初值），
        #   否则它会和后面的值构成一个方向相反的巨大差值，把同向判据压成 FAIL。
        pairs = [p for p in pairs if int(p[0]) > 0 and int(p[1]) > -9000]
        if len(pairs) >= 3:
            xs = [int(a) for a, _b in pairs[-4:]]
            pxs = [int(b) for _a, b in pairs[-4:]]
            # ★ 逐步比方向，**不是**首尾相比：桌宠先右后左时，首尾净位移可能接近 0
            #   甚至反向，而每一步其实都跟得好好的（本轮实测就是这样假失败了一次）。
            bad = 0
            for i in range(1, len(pxs)):
                dpx, dxs = pxs[i] - pxs[i - 1], xs[i] - xs[i - 1]
                if dpx != 0 and dxs != 0 and (dpx > 0) != (dxs > 0):
                    bad += 1
            # ★ 允许 1 步反向：日志是 500ms 节流的，窗口里**第一条**常常是拖动开始前
            #   留下的旧位置（那一刻侧栏还没开始跟）—— 拿它当基准会得到一次假反向。
            #   判据落在"至少有 2 步严格同向"上，这才是"跟着动"的证据。
            check('日志里侧栏逐步跟着桌宠同向（≥2 步同向、至多 1 步反向）',
                  bad <= 1 and (len(pxs) - 1 - bad) >= 2,
                  'side x=%s petX=%s 反向步数=%d' % (xs, pxs, bad))
        else:
            skip('日志同向判据', 'onscreen side 采样不足（跟随路径没跑？）')

    print('=== 6. 吸附态三格（需要贴边吸附一次）===')
    sh('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(5)
    base = overlay_frames()
    # 长按桌宠：优先用 logcat 里的桌宠窗口坐标；--ui-only（无采样日志）时
    # 退回"面积最大的 overlay 窗口"（模拟器上就是桌宠本体）。
    try:
        rx = int(pet.get('rootX', '0')); ry = int(pet.get('rootY', '0'))
        rw = int(pet.get('rootW', '0')); rh = int(pet.get('rootH', '0'))
    except Exception:
        rx = ry = rw = rh = 0
    if rw <= 0 or rh <= 0:
        f0 = pet_window_frame()
        if f0:
            rx, ry, rw, rh = f0[0], f0[1], f0[2] - f0[0], f0[3] - f0[1]
            print('    [info] 用最大 overlay 窗口当桌宠：%s' % ((rx, ry, rw, rh),))
    if rw > 0 and rh > 0:
        cx, cy = rx + rw // 2, ry + rh // 2
        sh('shell', 'input', 'swipe', str(cx), str(cy), str(cx), str(cy), '900')
        time.sleep(2)
        now = overlay_frames()
        new = [f for f in now if f not in base]
        check('长按弹出了菜单窗口', len(new) > 0, '新增 overlay 窗口 %d 个' % len(new))
        snap('04_long_press_menu')
        # ★ 长按菜单**不进 uiautomator dump**（是 TYPE_APPLICATION_OVERLAY 窗口），
        #   所以"菜单里有那两项"只能靠人看截图确认；脚本只断言"菜单窗口出现了"。
        #   ★★ 默认**不点菜单**：菜单是竖排 5 颗 chip（退出/重启/贴边吸附/隐藏监测条/
        #      显示项…），按窗口 frame 估的坐标一旦偏一格就会点到「退出桌宠」把桌宠关掉
        #      （本轮在模拟器上真踩了：估到第一颗，桌宠直接退了）。
        #      要验吸附态请显式加 `--dock`，并且**只在有数据（Shizuku 就绪）的设备上**用。
        if '--dock' in sys.argv and not ui_only:
            if new:
                # 菜单是竖排等高的 5 颗 chip；取"最高的那个新窗口"当菜单
                # （气泡窗口又宽又扁，菜单又窄又高 —— 按高度取最稳）
                f = sorted(new, key=lambda b: b[3] - b[1])[-1]
                n = 5
                chip_y = f[1] + int((f[3] - f[1]) * 2.5 / n)
                tap((f[0] + f[2]) // 2, chip_y, wait=3)
                log2 = logcat()
                rs = parse_render(log2, 'strip')
                if rs:
                    # ★ 用户 2026-10-07 修订：吸附贴边时**只显示一个数据**（中格），
                    #   所以这里钉的是"中格有值、另外两格为空"，不是三格都有值。
                    check('吸附态只显示中格一个数据',
                          bool(rs.get('mid')) and not rs.get('top') and not rs.get('bottom'),
                          'top=%r mid=%r bottom=%r' % (rs.get('top'), rs.get('mid'), rs.get('bottom')))
                    o2 = parse_onscreen(log2, 'strip')
                    if o2.get('x'):
                        p2 = snap('05_docked_strip')
                        share2, why2 = accent_share(
                            p2, (int(o2['x']), int(o2['y']),
                                 int(o2['x']) + int(o2['w']), int(o2['y']) + int(o2['h']))) \
                            if p2 else (None, 'no shot')
                        check('吸附态单值竖条真的画在竖条槽位',
                              share2 is not None and share2 > 0.25, why2)
                        # ★★ 复现用户报的 bug：**不松手**拉出去再拉回来，
                        #    槽位不该消失、也不该叠两条（普通竖条 + 监测条）。
                        #    用 input motionevent 拼多段手势（Android 11+ 才有）；
                        #    不可用就 skip —— 这步是"尽力而为"，不是判据主力。
                        try:
                            sw = int(re.search(r'(\d+)x(\d+)',
                                               sh('shell', 'wm', 'size')).group(1))
                            cx = int(o2['x']) + int(o2['w']) // 2
                            cy = int(o2['y']) + int(o2['h']) // 2
                            inward = 1 if cx < sw // 2 else -1     # 贴左边→向内是 +x
                            sh('shell', 'input', 'motionevent', 'DOWN', str(cx), str(cy))
                            for k in (1, 2, 3, 4):                 # 拉出去（过半）
                                sh('shell', 'input', 'motionevent', 'MOVE',
                                   str(cx + inward * 40 * k), str(cy))
                            for k in (3, 2, 1, 0):                 # 不松手再拉回来
                                sh('shell', 'input', 'motionevent', 'MOVE',
                                   str(cx + inward * 40 * k), str(cy))
                            sh('shell', 'input', 'motionevent', 'UP', str(cx), str(cy))
                            time.sleep(2)
                            rs3 = parse_render(logcat(), 'strip')
                            check('不松手拉出去再拉回来：槽位仍在且只有一项',
                                  bool(rs3) and bool(rs3.get('mid'))
                                  and not rs3.get('top') and not rs3.get('bottom'),
                                  'render strip mid=%r' % (rs3.get('mid'),))
                            snap('06_swipe_out_back')
                        except Exception as e:
                            skip('拉出去再拉回来', 'motionevent 不可用: %s' % e)
                else:
                    skip('吸附态单值竖条', '--dock：点了菜单但没进吸附态（见 04 截图）')
        else:
            skip('吸附态单值竖条（--dock 才点菜单）',
                 '默认不盲点菜单（可能点到「退出桌宠」）；菜单两项请看 04_long_press_menu.png')
    else:
        skip('长按菜单 / 吸附态三格', '拿不到桌宠窗口坐标')

    # ★ 全新安装的默认档 —— 只有模拟器（--ui-only）才做，因为它会 `pm clear`
    #   把 App 数据全清掉（真机上那是用户的设置，不能这么干）。
    #   放在**最后**：前面的桌宠/侧栏取样都跑完了，清数据不影响已取得的证据。
    if ui_only:
        print('=== 7. 全新安装的默认档（清数据后重看，仅模拟器）===')
        sh('shell', 'pm', 'clear', PKG)
        time.sleep(2)
        sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
        time.sleep(1)
        x = launch_app_clean(allow_dummy_key=True)[0]
        x = goto_game_assistant_page()          # 自带重试：入场动画期间点击会点空
        # ★ 新装时"启用游戏助手"默认是**关**的（要装 Shizuku 才能用），
        #   而监测范围整块都在那个开关下面 —— 不先打开它，「监测范围」这一行
        #   根本不存在（本轮第一版就栽在这里：把"按设计不显示"当成了失败）。
        cg, x = find_scrolling(x, '启用游戏助手')
        if cg:
            tap(*cg, wait=3)
        x = dump_ui()
        rg, x = find_scrolling(x, '监测范围：')
        check('清数据后默认档 = 所有应用（新装用户看到的）',
              rg is not None and node_center(x, '监测范围：所有应用') is not None)
        # 默认档就应该是空的名单（"仅选定"才有名单，而它现在没被选中）
        xp = sh('shell', 'cat', '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG)
        if '<?xml' in xp:
            check('新装用户没有残留名单（monitor_apps 不该存在）',
                  'monitor_apps' not in xp)
        else:
            skip('新装无残留名单', '读不到 shared_prefs')
        snap('09_fresh_default_scope')

    return summary()


def summary():
    """打印汇总并给出退出码（被主流程末尾和"环境缺 Key"的提前退出共用）"""
    print()
    print('=' * 62)
    for name, ok, detail in results:
        tag = 'SKIP  ' if ok is None else ('PASS  ' if ok else 'FAIL  ')
        print(tag + name + (' :: ' + detail if detail else ''))
    print('=' * 62)
    done = [r for r in results if r[1] is not None]
    skipped = len(results) - len(done)
    print('结果：%d 项通过 / %d 项失败 / %d 项跳过' %
          (sum(1 for r in done if r[1]), len(fails), skipped))
    print('截图目录：%s' % SHOT_DIR)
    return 1 if fails else 0


if __name__ == '__main__':
    sys.exit(main())
