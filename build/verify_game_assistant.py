# -*- coding: utf-8 -*-
"""游戏助手（Shizuku 帧率）断言 —— 源码级 + APK 级。

钉的是**语义**不是存在性（本工程踩过"只查文案导致空转"的坑）：
  · provider authority 必须**恰好等于** 包名 + ".shizuku"（provider 自己会校验，
    写错不会报错、只会静默拿不到 binder）
  · 构建脚本的依赖名单必须**单一来源**且被 classpath 与 merge **两处**引用
    （分两份写的话，只改一处 = 编译过但运行期 NoClassDefFoundError）
  · FpsMath 的静默期阈值必须**真的用在比较里**，不能只是声明
  · 探针必须**强制走 Proxy**（用 asBinder 而不是 asInterface），
    否则编解码根本没被测到
"""
import io
import os
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk  # noqa: E402

SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
DEPS = r'D:\petport_lab\petbuild\deps'
MANIFEST = r'D:\petport_lab\petbuild\AndroidManifest.xml'
BUILD_WIN = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                         '..', '..', '用户', 'DS', 'pet-ui-redesign', 'tools', 'build_win.py')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def method_body(src, sig):
    """取某个方法**花括号配对**出来的方法体（别用非贪婪正则：嵌套块会骗人）。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('{', i)
    if j < 0:
        return ''
    depth, k = 0, j
    while k < len(src):
        c = src[k]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[j + 1:k]
        k += 1
    return ''


def code_only(src):
    """去掉 Java 注释，只留代码。

    ★ 为什么必须这么做（本轮实测踩到）：
      我在注释里写了"不能用 newProcess""第一版写成 extends IBinder"
      "不能用 Stub.asInterface" —— 这些**反例说明**本身就让
      "不该出现 X" 这类断言误报。断言要钉代码，不能钉注释。
    """
    out = []
    i = 0
    n = len(src)
    in_block = False
    while i < n:
        if in_block:
            j = src.find('*/', i)
            if j < 0:
                break
            i = j + 2
            in_block = False
            continue
        if src.startswith('/*', i):
            in_block = True
            i += 2
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        if src[i] == '"':                      # 跳过字符串字面量
            out.append(src[i])
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    out.append(src[i])
                    i += 1
                if i < n:
                    out.append(src[i])
                    i += 1
            if i < n:
                out.append(src[i])
                i += 1
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


print('=== 1. deps/ 里的 4 个 Shizuku jar ===')
EXPECT_JARS = ['shizuku-api.jar', 'shizuku-provider.jar',
               'shizuku-aidl.jar', 'shizuku-shared.jar']
for j in EXPECT_JARS:
    p = os.path.join(DEPS, j)
    ok = os.path.exists(p)
    check('存在 %s' % j, ok, ('%d B' % os.path.getsize(p)) if ok else '缺失')

print()
print('=== 2. 构建脚本：依赖名单单一来源且两处引用 ===')
bw = read(BUILD_WIN)
check('定义了 DEPS_JARS 元组', 'DEPS_JARS = (' in bw)
# 语义：必须同时用于 classpath 和 merge，不能只加一处
uses_cp = re.search(r'cp = os\.pathsep\.join\(os\.path\.join\(P, "deps", j\) for j in DEPS_JARS\)', bw)
check('classpath 用 DEPS_JARS 生成', bool(uses_cp))
check('merge 循环用 DEPS_JARS', 'for jar in DEPS_JARS:' in bw)
# 反例：不该再出现硬编码的双份名单
check('没有残留的硬编码双份名单',
      '("sherpa-classes.jar", "kotlin-stdlib-1.9.0.jar")' not in bw)
for j in EXPECT_JARS:
    check('DEPS_JARS 含 %s' % j, ('"%s"' % j) in bw)

print()
print('=== 3. 清单：权限 / provider / service ===')
mf = read(MANIFEST)
pkg = re.search(r'package="([^"]+)"', mf).group(1)
check('声明 API_V23 权限', 'moe.shizuku.manager.permission.API_V23' in mf)
check('声明 V3_SUPPORT meta-data', 'moe.shizuku.client.V3_SUPPORT' in mf)
check('声明 ShizukuProvider', 'rikka.shizuku.ShizukuProvider' in mf)
check('声明 FpsUserService', 'android:name=".FpsUserService"' in mf)

# ★ 语义：authority 必须恰好是 包名 + .shizuku
auth = re.search(r'android:authorities="([^"]+)"', mf)
want = pkg + '.shizuku'
check('authority == 包名+.shizuku', bool(auth) and auth.group(1) == want,
      '实测 %s，期望 %s' % (auth.group(1) if auth else '无', want))

# provider 自己校验这两个属性，写错会抛异常
prov = re.search(r'<provider\b[^>]*ShizukuProvider[^>]*/>', mf, re.S)
check('provider 段存在', bool(prov))
if prov:
    seg = prov.group(0)
    check('provider multiprocess=false', 'android:multiprocess="false"' in seg)
    check('provider exported=true', 'android:exported="true"' in seg)

print()
print('=== 4. FpsMath：静默期阈值必须真的用在比较里 ===')
fm = read(os.path.join(SRC, 'FpsMath.java'))
check('声明 LIVE_GAP_MS', 'LIVE_GAP_MS' in fm)
# 语义：阈值要参与 gap 比较，不能只声明
check('阈值参与 gap 比较', bool(re.search(r'gapMs\s*<\s*LIVE_GAP_MS', fm)))
check('用 actualPresentTime（第二列）', 'a <= 0' in fm or 'actual' in fm.lower())
check('跳过全零空槽', 'd == 0 && a == 0 && r == 0' in fm)
check('容忍 BOM', '\\uFEFF' in fm)
check('findLayer 排除 ActivityRecord/InputSink',
      'ActivityRecord' in fm and 'InputSink' in fm)

print()
print('=== 5. GameFpsMonitor 复用 FpsMath（没有另写一份算法）===')
gm = read(os.path.join(SRC, 'GameFpsMonitor.java'))
gmc0 = code_only(gm)
# ★ 口径在真机联调后从 --latency 换成了 timestats（Android 17 上 --latency 已失效）
check('调用 FpsMath.parseTimeStats', 'FpsMath.parseTimeStats' in gmc0)
check('调用 FpsMath.filterLayers', 'FpsMath.filterLayers' in gmc0)
check('用 totalFrames 增量算实时帧率',
      bool(re.search(r'totalFrames - prev\[0\]\) \* 1000f / dt', gmc0)))
check('启动时启用 timestats', '--timestats -enable' in gmc0)
# ★★ 真机实测的两条关键修正：
#   ① 不能"选帧数最大的层"（那是死掉的旧层，计数冻结）→ 必须选"正在增长的层"
check('选"正在增长"的层（不是帧数最大）',
      'pickGrowing' in gmc0 and 'filterLayers' in gmc0)
#   语义级：判据必须是"增量最大"，而不是"帧数最大"
check('选层判据是增量最大',
      bool(re.search(r'long d = st\.totalFrames - prev\[0\];\s*if \(d > bestDelta\)',
                     gmc0)))
#   ② 前台应用不能用 UsageStats（异步落库有延迟）→ 用 dumpsys 即时真值
#      语义级：钉"被调用"，不是"方法存在"（存在但不用等于没修）
check('前台用 dumpsys 即时真值',
      bool(re.search(r'String pkg = parseForegroundPkg\(', gmc0))
      and 'topResumedActivity' in gmc0)
#   ③ 完整 dump 有 1.1MB，稳态要 grep 缩小
check('稳态用 grep 缩小 dump', 'grep -F -A60' in gmc0)
#   ④ 快路径的层名只能来自"真的看到在涨"的层
check('快路径层名只认在涨的层',
      bool(re.search(r'if \(active != null\) \{\s*lastActiveLayer = active\.layer;', gmc0)))
# ★ 语义：必须支持**多监听者**（悬浮窗与设置页同时用）。
#   第一版是"单个可替换回调"，服务在跑时设置页一打开就会把服务顶掉。
check('多监听者（互不顶掉）',
      'CopyOnWriteArrayList<Callback>' in gmc0 and 'addListener' in gmc0
      and 'removeListener' in gmc0)
# 引用计数：谁需要谁 acquire，计数归零才真停
check('引用计数生命周期', 'public static synchronized void acquire' in gmc0
      and 'public static synchronized void release' in gmc0)

print()
print('=== 6. ShizukuBridge：newProcess 主路径 + UserService 回退 ===')
sb = read(os.path.join(SRC, 'ShizukuBridge.java'))
sbc0 = code_only(sb)
# ★ 真机联调结论：ShizukuX 的 UserService 有 bug（双服务端 → token 失配），
#   所以主路径改成直接调 IShizukuService.newProcess（公开包装被移除但服务端方法还在）
check('主路径调 IShizukuService.newProcess', 'svc.newProcess(' in sbc0)
check('取 Shizuku.getBinder()', 'Shizuku.getBinder()' in sbc0)
check('保留 UserService 回退路径', 'bindUserService' in sbc0)
check('sticky 监听（避免错过已到达的 binder）',
      'addBinderReceivedListenerSticky' in sbc0)
check('四态状态机齐全',
      all(s in sbc0 for s in ('NOT_INSTALLED', 'NOT_RUNNING', 'NOT_GRANTED', 'READY')))
# 语义：主路径不需要绑定，ready() 不能要求 userService
check('ready() 不要求已绑定（主路径无需绑定）',
      bool(re.search(r'public static boolean ready\(\)\s*\{\s*return state == State\.READY;', sbc0)))

print()
print('=== 7. 手写 Binder：接口继承 IInterface（不是 IBinder）===')
ib = read(os.path.join(SRC, 'IFpsUserService.java'))
ibc = code_only(ib)
check('继承 IInterface', 'extends IInterface' in ibc)
check('没有继承 IBinder', 'extends IBinder' not in ibc)
check('onTransact 调 writeNoException', 'writeNoException' in ibc)
check('Proxy 读结果前 readException', 'readException' in ibc)
check('两个事务码不同',
      'TRANSACTION_exec = 1' in ibc and 'TRANSACTION_execWithErr = 2' in ibc)

print()
print('=== 8. 探针必须强制走 Proxy（否则编解码没被测）===')
pa = code_only(read(os.path.join(SRC, 'FpsProbeActivity.java')))
check('用 asBinder() 构造 Proxy', 'new IFpsUserService.Proxy(impl.asBinder())' in pa)
# 语义：不能退化成 asInterface（本地对象会被直接返回，绕过编解码）
check('没有用 asInterface 绕过编解码', 'asInterface' not in pa)

print()
print('=== 9. 设置页接线：第 7 节归「陪伴」板块 ===')
ma = read(os.path.join(SRC, 'MainActivity.java'))
check('_GRP_NAMES 含「游戏助手」「选择应用」「本地发言」（轮次⑩/⑫ 新增的两页）',
      bool(re.search(r'_GRP_NAMES\s*=\s*\{[^}]*"游戏助手"\s*,\s*"选择应用"\s*,\s*"本地发言"',
                     ma)))
check('第 9 节常量 = 9（本地发言）',
      bool(re.search(r'SECTION_LOCAL_LINES\s*=\s*9\s*;', ma)))
# ★ v8.0：陪伴板块的索引数组多了第 11 节（工作模式）。
#   不再钉死字面顺序，改成“解析成集合再比较”：
#   既保证 7/8/9 在里面，也把新加的 11 一并钉住。
_tabm = re.search(r'TAB_SECTIONS\s*=\s*\{(.*?)\};\n', ma, re.S)
_rows = []
if _tabm:
    for _r in re.findall(r'\{([0-9,\s]+)\}', _tabm.group(1)):
        _rows.append(set(int(x) for x in _r.replace(" ", "").split(",") if x))
check('陪伴板块含 1/6/3/7/8/9/11 节',
      any({1, 6, 3, 7, 8, 9, 11} <= r for r in _rows))
check('有 section==7 内容块', 'settingsSection == 7' in ma)
check('有第 8 节内容块（选择监测应用）',
      'settingsSection == SECTION_MONITOR_APPS' in ma)
# ★ 语义：必须**紧跟在 onPause 的 removeTaskFromRecents() 之后**。
#   第一版我写成 `'GameFpsMonitor.stop()' in ma` —— 那太弱：
#   onDestroy 和 section 分支里也各有一处，删掉 onPause 那处照样通过（空转）。
#   这里要求两行之间距离很近，钉住"就是离开页面这条路"。
check('离开页面（onPause）只注销自己',
      bool(re.search(r'removeTaskFromRecents\(\);.{0,260}?stopGameFpsSampling\(\);',
                     ma, re.S)))
# ★ 不能调全局 stop()：悬浮窗可能也在用，那会把桌宠上的监测条一起停掉
check('onPause 不调全局 stop()',
      not re.search(r'removeTaskFromRecents\(\);.{0,260}?GameFpsMonitor\.stop\(\);',
                    ma, re.S))

# ★ 界面文案不能带 markdown：本工程 474 处 ** 全在 Javadoc 里，
#   UI 字符串一处都没有 —— TextView 会原样显示星号。
#   我第一版在说明文字里写了 "**真实渲染帧率**"，实测界面上就是两个星号。
m7 = re.search(r'if \(settingsSection == 7\) \{(.*?)\n        \}\n', ma, re.S)
if m7:
    seg = m7.group(1)
    strings = re.findall(r'"((?:[^"\\]|\\.)*)"', seg)
    bad = [s for s in strings if '**' in s]
    check('游戏助手界面文案无 markdown 星号', not bad,
          ('发现: %s' % bad[:2]) if bad else '共检查 %d 条文案' % len(strings))
else:
    check('能定位到 section==7 内容块', False, '正则没匹配到')

print()
print('=== 12. 诊断能力：失败必须可见（真机排查靠这个）===')
sbc = code_only(read(os.path.join(SRC, 'ShizukuBridge.java')))
check('有 BindState 四态',
      all(s in sbc for s in ('IDLE', 'BINDING', 'BOUND', 'FAILED')))
check('bind() 记录异常而不是吞掉',
      'bindError = t.getClass().getSimpleName()' in sbc)
check('有绑定超时判定', 'BIND_TIMEOUT_MS' in sbc)
check('有 logcat TAG', 'DSPShizuku' in sbc)
# 语义：bindUserService 外面不能再是"静默 catch"
mb = re.search(r'Shizuku\.bindUserService\(args, conn\);(.{0,220})', sbc, re.S)
check('bindUserService 后不是静默 catch',
      bool(mb) and 'catch (Throwable ignore)' not in mb.group(1),
      '紧随其后: ' + (mb.group(1).strip().replace('\n', ' ')[:60] if mb else '无'))

gmc = code_only(read(os.path.join(SRC, 'GameFpsMonitor.java')))
# ★ 判据只看 Shizuku 状态：主路径 newProcess 不需要绑定，
#   第一版要求"必须已绑定"才采样，于是在绑定不可靠的设备上读数永远出不来。
check('读数只看 Shizuku 状态、不被绑定状态挡住',
      'ShizukuBridge.state() != ShizukuBridge.State.READY' in gmc
      and 'BindState.BINDING' not in gmc)

pc = code_only(read(os.path.join(SRC, 'FpsProbeActivity.java')))
check('探针逐环诊断（C1..C7）', all(('C%d' % i) in pc for i in range(1, 8)))
# ★ 判据必须同时接受 shell(2000) 与 root(0)：用户真机跑的是 KernelSU 方案，
#   第一版只认 2000，把正常的 root 链路误报成 FAIL。
check('探针身份判据含 root 与 shell',
      'uid=0(' in pc and 'uid=2000(' in pc)

print()
print('=== 13. 系统监测条（吸附态顶替竖条 / 展开态挂侧面）===')
fs = code_only(read(os.path.join(SRC, 'FloatingService.java')))
check('吸附态三格存在且随 container 走',
      'private LinearLayout monitorBar' in fs
      and bool(re.search(r'private TextView monitorCellTop,\s*monitorCellMid,\s*'
                         r'monitorCellBottom;', fs))
      and 'container.addView(monitorBar)' in fs)
check('展开态是独立悬浮窗（不动桌宠窗口几何）',
      'private LinearLayout monitorSideRoot' in fs
      and bool(re.search(r'private void monitorSideShow\(', fs))
      and 'TYPE_APPLICATION_OVERLAY' in fs)
check('吸附态摆位：顶替竖条槽位（窄条）',
      bool(re.search(r'private boolean monitorTakeStripSlot', fs)))
check('dockShowStrip 优先让监测条顶替',
      bool(re.search(r'private void dockShowStrip\(\)\s*\{.{0,300}?monitorTakeStripSlot\(\)',
                     fs, re.S)))
# ★ 用户明确要求"同步竖条的操作"：命中判定必须认监测条，否则拖不动它
check('dockHitStrip 命中判定认监测条',
      bool(re.search(r'private boolean dockHitStrip\(float rawX, float rawY\)\s*\{.{0,600}?monitorBar',
                     fs, re.S)))
check('展开后监测条换成侧栏形态（不是消失）',
      bool(re.search(r'private void dockHideStrip\(\)\s*\{.{0,400}?monitorRender\(\)',
                     fs, re.S)))
# ★ 侧栏必须按桌宠位置选边：偏左挂右、偏右挂左（用户明确要求"取决于位置"）
# ★ 侧栏必须按桌宠位置选边：偏左挂右、偏右挂左（用户明确要求"取决于位置"）
#   判据已抽成纯函数 SysMetrics.sideBarX/sideBarY —— 桌面 JVM 上有 11 项行为断言
#   （见 fpstest [13]），这里只钉"确实调了那个纯函数"，不重复钉逻辑。
check('侧栏选边/夹紧走纯函数',
      'int x = SysMetrics.sideBarX(' in fs and 'int y = SysMetrics.sideBarY(' in fs)
fmc0 = code_only(read(os.path.join(SRC, 'SysMetrics.java')))
pfc0 = code_only(read(os.path.join(SRC, 'Prefs.java')))
check('纯函数里真的按桌宠中心选边',
      'petCenter <= screenW / 2 ? SIDE_RIGHT : SIDE_LEFT' in fmc0)
# ★ 吸附态只看"中格"：竖条上只显示一个数据（用户 2026-10-07 修订）
check('吸附态只看中格（竖条单值）',
      'return (everData && hasMid) ? BAR_STRIP : BAR_NONE;' in fmc0)
# ★ 展开态看"有没有任意一格"
check('展开态看有没有任意一格',
      'return hasAny ? BAR_SIDE : BAR_NONE;' in fmc0)
check('侧栏独立窗口在销毁/藏桌宠时收掉',
      fs.count('monitorHideSide()') >= 4,
      '实测 %d 处' % fs.count('monitorHideSide()'))
# ★ 藏桌宠（进 App 设置页 / 划卡隐藏）时侧栏必须跟着消失：它是独立窗口，
#   不跟着桌宠窗口走；而采样回调每秒都会调 monitorRender。
#   判据已抽成纯函数 SysMetrics.barForm（fpstest [16] 有 11 项行为断言）。
check('藏桌宠时监测条一起收掉',
      'if (!gateOpen || petHidden) return BAR_NONE;' in fmc0)
check('形态判据走纯函数 barForm',
      'SysMetrics.barForm(' in fs and 'SysMetrics.BAR_STRIP' in fs)
# ★ 吸附态渲染只让中格可见（另外两格 GONE）—— 这是"还原单值竖条"的落点
check('吸附态只让中格可见',
      bool(re.search(r'cells\[i\]\.setVisibility\(i == 1 \? View\.VISIBLE : View\.GONE\);', fs)))
# ★ 轮次⑩ 修订（用户报障"数据显示不完整 / 增大显示面积"）：宽度**不再写死**，
#   改成按中格文本实测宽度算（下限 20dp 只是"细条观感"的下限）。
#   所以这里钉的是"下限仍然存在 + 实际宽度走纯函数"，而不是"等于 20dp"。
check('吸附竖条有最小宽度下限（20dp 细条观感）',
      'MONITOR_STRIP_W_DP = SysMetrics.STRIP_MIN_W_DP' in fs)
check('实际宽度走纯函数 stripWidthDp',
      'SysMetrics.stripWidthDp(' in fs and 'stripWidthDpFor(midText)' in fs)
# ★ 侧栏第一次 addView 后可能还没量出宽高（定位会退化成估算值），巡检要补一次
#   注意：code_only() 会**删掉注释**，所以这里不能把注释写进正则
check('巡检补一次侧栏定位',
      bool(re.search(r'monitorRender\(\);\s*if \(monitorSideShowing\) positionMonitorSide\(\);',
                     fs)))
# ==================== 跟手收放时"槽位归谁"（真机 bug）====================
# 用户报："长按拖出吸附竖条、不松手再拉回，竖条会消失或形态异常"。
# 根因：跟手过半/回落时**只改 dockStrip 的可见性**，而吸附槽位可能是**监测条**占着的
#   （顶替成功时 dockStrip 本来就是 GONE）：
#     · 拉过半 → 只 GONE dockStrip ⇒ 监测条还挂在槽位上（形态异常）；
#     · 拉回来 → 又把 dockStrip 点亮 ⇒ 普通竖条与监测条**叠在同一槽位**。
#   现在收敛成唯一入口 dockSlotShow（按"槽位归谁"决定显隐哪一个）。
check('跟手收放走唯一入口 dockSlotShow（不再直接改 dockStrip）',
      'dockSlotShow(p <= 0.5f);' in fs
      and 'dockStrip.setVisibility(p > 0.5f' not in fs)
check('dockSlotShow 收的时候两个都收',
      bool(re.search(r'private void dockSlotShow\(boolean show\) \{\s*'
                     r'if \(!show\) \{\s*'
                     r'if \(dockStrip != null\) dockStrip\.setVisibility\(View\.GONE\);\s*'
                     r'if \(monitorBar != null\) monitorBar\.setVisibility\(View\.GONE\);', fs)))
check('dockShowStrip 记下"槽位归谁"',
      'dockSlotOwnerIsMonitor = true;' in fs and 'dockSlotOwnerIsMonitor = false;' in fs)
check('dockHideStrip 收槽位时两个都收（不只是 dockStrip）',
      bool(re.search(r'private void dockHideStrip\(\) \{\s*dockSlotShow\(false\);', fs)))
# ★ 展开时必须**先切态再收槽位**：dockHideStrip 内部会按 dockState 决定监测条形态，
#   顺序反了它会以为还在吸附态，把监测条按竖条形态又摆回来（展开中挂着一条 = 形态异常）
#   注意：code_only() 删注释，正则里不能带注释文字
check('dockExpand 先切态再收槽位',
      bool(re.search(r'dockState = PetDockState\.EXPANDED;\s*dockHideStrip\(\);', fs)))
check('展开动画结束补一次侧栏定位',
      bool(re.search(r'positionBalance\(\);\s*positionMonitorSide\(\);', fs)))
check('销毁时成对释放采样', 'fpsAcquired' in fs and 'GameFpsMonitor.release()' in fs)
check('设置页开关通知服务', 'Prefs.reloadPet' in code_only(ma))

print()
print('=== 13b. 监测范围：全局 / 仅选定应用（两者集成在同一个入口）===')
# 历史：那个「只在游戏前台时显示」开关**只有 setter、全工程零读取点**，点了没效果；
# 后来改成"勾选应用白名单"（轮次⑥）；用户 2026-10-07 要求撤成"全部打开 / 关闭"（轮次⑦）；
# ★ 用户 2026-10-08 又要求"全局打开或者部分应用打开，把这俩集成在一起去" ——
#   所以现在**两种模式都在**，默认全局。下个窗口别只看其中一条决定就改回去。
check('门控委托给纯判据 SysMetrics.monitorVisible（单一来源）',
      bool(re.search(r'return SysMetrics\.monitorVisible\(\s*'
                     r'Prefs\.gameAssistant\(this\),\s*'
                     r'Prefs\.monitorBar\(this\),\s*'
                     r'Prefs\.monitorOnlySelected\(this\),\s*'
                     r'monitorPkg,\s*'
                     r'Prefs\.monitorApps\(this\)\);', fs)),
      '正则没匹配到 monitorVisible 调用')
check('门控不再自己写"两开关"判断（判据搬家后不留旧副本）',
      'if (!Prefs.gameAssistant(this)) return false;' not in fs)
check('设置页有「监测范围」二选入口',
      'pickMonitorScope();' in ma and '监测范围：' in ma)
# ★ 判据本体的三道闸门（行为断言在 build/fpstest 第 [14] 节；
#   这里钉源码结构，配合 reverse_check 保证它们**不可能被悄悄翻掉**）
sm = code_only(read(os.path.join(SRC, 'SysMetrics.java')))
check('判据默认档 = 全局（!onlySelected 直接放行）',
      bool(re.search(r'if \(!onlySelected\) return true;', sm)))
check('部分应用模式：还不知道前台是谁时不显示（不猜）',
      bool(re.search(r'if \(pkg == null \|\| pkg\.length\(\) == 0\) return false;', sm)))
check('部分应用模式：名单为空/null 都不当成"全部"',
      bool(re.search(r'return list != null && list\.contains\(pkg\);', sm)))
check('两档文案齐备（所有应用 / 仅在选定的应用里显示）',
      '所有应用' in ma and '仅在选定的应用里显示' in ma)
check('空名单的后果写进界面（否则用户看着像 bug）',
      '名单现在是空的' in ma and '不会在任何应用里显示' in ma)
check('勾选入口只在"仅选定"档出现',
      bool(re.search(r'if \(monOnlySel\) \{.{0,1200}?选择应用（已选', ma, re.S)))
check('勾选真的写进 prefs（不是只改界面）',
      'Prefs.setMonitorApp(MainActivity.this, pkg, checked)' in ma)
check('搜索框走 appMatches（应用名或包名都能搜）',
      'SysMetrics.appMatches(query, labelOf(ri), pkg)' in ma)
# ★ 语义：勾选不能整页重建 —— 否则每点一个应用滚动位置就回顶部
#   （本工程在"点开关像重新加载"那一轮已经吃过这个亏）。
check('勾选后就地改计数、不整页重建',
      bool(re.search(r'Prefs\.setMonitorApp\(MainActivity\.this, pkg, checked\);'
                     r'.{0,600}?countTv\.setText', ma, re.S)))
check('列表只取有启动入口的应用，且排除自己',
      'CATEGORY_LAUNCHER' in ma and 'getPackageName().equals(pkg)' in ma)
check('旧的死开关已从界面撤掉',
      'setGameAssistantAutoOnly(MainActivity' not in ma)
check('设置页仍明说"关闭 = 全部不显示"',
      '关闭 = 全部不显示' in ma)

print()
print('=== 13g. 游戏助手图标 = 手柄（用户 2026-10-07 要求）===')
vi = code_only(read(os.path.join(SRC, 'VectorIcon.java')))
check('VectorIcon 里有 GAMEPAD 图标（手写、原型 I{} 已同步登记）',
      'String GAMEPAD =' in vi)
# 语义：🎮 必须映射到 GAMEPAD（而不是原来的 PLAY）。
# 判据用「同一行里既有 DFAE 又有 GAMEPAD」——比在多层转义里拼字符串稳得多。
check('🎮 映射到 GAMEPAD',
      any(('DFAE' in ln and 'GAMEPAD' in ln) for ln in vi.splitlines()))
check('设置页第 7 节导航图标用手柄（_navIcon 与首页行表**两处**都要改）',
      bool(re.search(r'case 7: return "gamepad";', code_only(ma)))
      and bool(re.search(r'\{"GAMEPAD", "游戏助手"', ma)))

print()
print('=== 13f. 长按菜单：监测条两项只在游戏助手打开时出现（用户 2026-10-07 要求）===')
# 用户原话：「长按菜单的隐藏监测条和显示项只有打开了游戏助手再显示，
#            关闭游戏助手时不显示这两个选项」
_lp = method_body(fs, 'private void showLongPressBubble()')
check('定位到长按菜单构建函数', len(_lp) > 500, '长度 %d' % len(_lp))
_i_guard = _lp.find('if (assistOn) {')
_i_chart = _lp.find('"隐藏监测条"')
_i_items = _lp.find('"显示项…"')
check('★ 两项都在 assistOn 守卫**里面**（关掉游戏助手就不显示）',
      _i_guard >= 0 and _i_chart > _i_guard and _i_items > _i_guard,
      'guard@%d chart@%d items@%d' % (_i_guard, _i_chart, _i_items))
check('守卫读的是「游戏助手」总开关',
      'Prefs.gameAssistant(this)' in _lp)
# 语义：量宽也要跟着条件走，否则功能关着时面板会白宽一截
check('面板量宽也只在开着时把那两行算进去',
      bool(re.search(r'if \(assistOn\) \{\s*tw = Math\.max\(tw, pp\.measureText\("隐藏监测条"\)\);',
                     _lp)))
check('菜单项数进日志（悬浮窗不在 dump 里，设备侧只能靠它）',
      'longpress chips=' in _lp)

print()
print('=== 13d. 吸附态：槽位交还 / 竖条按内容加宽 / 包可见性 ===')
# 用户报障（2026-10-07，三条）：
#   ① "读取不到完整的应用列表"
#   ② "吸附贴边时候数据显示不完整""打开性能监测的时候增大显示面积"
#   ③ "在吸附状态下选择隐藏监测条，吸附竖条会直接消失"
check('清单声明 <queries>（Android 11+ 包可见性；不加只查得到自己）',
      '<queries>' in mf and 'android.intent.category.LAUNCHER' in mf)
check('槽位占用双向交接的唯一入口 applyStripSlotOwner',
      'private void applyStripSlotOwner(boolean monitorWants)' in fs)
# ③ 的修复：监测条收掉时**必须**把槽位交还，否则槽位空了 ⇒ 竖条直接消失
check('监测条消失时把槽位交还普通竖条（③）',
      bool(re.search(r'monitorBar\.setVisibility\(View\.GONE\);\s*'
                     r'monitorHideSide\(\);.{0,400}?applyStripSlotOwner\(false\);', fs, re.S)))
# 反向：监测条接管时收掉普通竖条（否则同一槽位叠两条）
check('监测条接管槽位时收掉普通竖条（反向叠两条）',
      bool(re.search(r'monitorBar\.bringToFront\(\);.{0,200}?applyStripSlotOwner\(true\);',
                     fs, re.S)))
# ② 的修复：宽度按内容算 + 露出宽度跟着走（只加宽竖条不加露出 = 被屏幕切掉）
check('竖条宽度按内容算（不再写死 20dp）',
      'SysMetrics.stripWidthDp(' in fs and 'stripWidthDpFor(midText)' in fs)
check('露出宽度跟着竖条宽度走（②：否则多出来的被屏幕切掉）',
      bool(re.search(r'private int dockPeekPx\(\)\s*\{.{0,700}?monitorWantsStripSlot\(\)',
                     fs, re.S)))
check('"要不要占槽位"判据唯一来源 monitorWantsStripSlot',
      'private boolean monitorWantsStripSlot()' in fs
      and bool(re.search(r'if \(!monitorWantsStripSlot\(\)\) return false;', fs)))
check('竖条文案单一来源（渲染与量宽共用）',
      'String midText = monitorMidText();' in fs and 'private String monitorMidText()' in fs)

print()
print('=== 13e. 拖动流畅度 + 监测条实时跟随 + 可调间距 ===')
# 用户报障（2026-10-07）："移动桌宠的时候太卡顿" +
#   "监测条距离桌宠太远，检测条改为就在桌宠旁边跟随桌宠位置实时移动" +
#   "加一个可调节的选项"
# ① 拖动每帧的开销：原来每帧两次 getRealMetrics、每帧给隐藏的余额气泡发窗口更新、
#    位置没变也照发 root 的布局请求 —— 三条都收敛掉。
check('真实屏幕尺寸带缓存（拖动每帧不再重复查 Display）',
      'REAL_SIZE_TTL_MS' in fs
      and bool(re.search(r'if \(!force && realScreenW > 0 && realScreenH > 0\s*'
                         r'&& \(now - realSizeAtMs\) < REAL_SIZE_TTL_MS\)', fs)))
check('旋转/折叠走强制刷新（缓存不会把旋转吃掉）',
      'refreshRealSize(true)' in fs)
check('桌宠窗口参数没变就不发窗口更新',
      bool(re.search(r'boolean same = params\.x == lastLpX && params\.y == lastLpY', fs))
      and bool(re.search(r'if \(!same\) \{', fs)))
check('余额气泡隐藏时不再每帧重算 + 发窗口更新',
      bool(re.search(r'if \(balRoot == null \|\| balRoot\.getVisibility\(\) != View\.VISIBLE\)'
                     r' return;', fs)))
check('侧栏位置没变就不发窗口更新',
      bool(re.search(r'if \(x == lastMonSideX && y == lastMonSideY\) return;', fs)))
# ② 实时跟随：挂在 applyRootLayout 里（所有移动路径共用同一个入口）
check('跟随挂在 applyRootLayout 内（拖动/跟手/回弹全都跟）',
      bool(re.search(r'windowManager\.updateViewLayout\(root, params\);'
                     r'.{0,60}?\}\s*followMonitorSide\(\);', fs, re.S)))
check('followMonitorSide 只在侧栏显示时动手',
      bool(re.search(r'private void followMonitorSide\(\) \{\s*'
                     r'if \(!monitorSideShowing\) return;', fs)))
# ★ 跟随必须**节流**：拖动时每帧发一次悬浮窗更新，在软渲染/弱机上自己就是掉帧源
#   （模拟器实测：掉帧率 12% → 80%）。80ms ≈ 12Hz，肉眼看不出与逐帧的差别。
check('跟随有节流（不是每帧都发窗口更新）',
      'MONITOR_FOLLOW_MIN_MS' in fs
      and bool(re.search(r'now - monitorFollowAtMs < MONITOR_FOLLOW_MIN_MS', fs)))
check('跟随坐标进日志（悬浮窗不在 dump 里，设备上只能靠它）',
      'logMonitorSideBox' in fs and 'gapPx=' in fs)
# ③ 可调间距：来自 prefs、带缓存、走轻量重排 action
check('侧栏间距来自 prefs（不再写死 6dp）',
      bool(re.search(r'SysMetrics\.sideBarX\(pr\[0\], pr\[2\], sw, w, monitorGapPx\(dm\)\)', fs)))
check('间距值读出即夹取（跨版本脏值/NaN 都兜住）',
      'SysMetrics.clampMonitorGapDp' in pfc0)
check('间距带缓存（拖动每帧不读 prefs）',
      'private int monitorGapPx(android.util.DisplayMetrics dm)' in fs)
check('轻量重排 action（不重建整套视图）',
      'ACTION_REPOSITION' in fs and 'public static void repositionPet' in pfc0)
check('间距滑块在设置页（用户可调）',
      'gapBar' in ma and 'gapText(' in ma and 'setMonitorGapDp' in ma)
check('滑块拖动只发轻量 reposition（两处：节流 + 松手补发）',
      ma.count('Prefs.repositionPet(MainActivity.this);') >= 2,
      '实测 %d 处' % ma.count('Prefs.repositionPet(MainActivity.this);'))
# ★ 语义（不是存在性）：滑块**真的进了布局**。第一版只 new 出来 + 挂监听、
#   忘了 box.addView —— 界面上只有一行"当前：6 dp"，看起来像句说明，
#   而设备脚本按 SeekBar 找怎么也找不到（本轮实测）。
check('间距滑块真的加进布局（不是只 new 出来）',
      'box.addView(gapBar);' in ma)

print()
print('=== 13c. 回到页面必须重启采样（真机 bug 的修复）===')
# 真机实测：授权时 Shizuku 弹权限框 → 我们 onPause → stop()；
# 回到页面若不重启，读数永远冻在"未就绪"。
check('onResume 重启采样',
      bool(re.search(r'awaitingResult = false;.{0,300}?startGameFpsSampling\(', ma, re.S)))
check('读数控件存成字段（onResume 才能写）',
      'private TextView gameFpsTv' in ma and 'gameFpsTv = addLabelTv' in ma)
check('重建时清掉旧控件引用',
      bool(re.search(r'private void showSettings\(\)\s*\{.{0,300}?gameFpsTv = null;', ma, re.S)))

print()
print('=== 10. Prefs 键 ===')
pf = read(os.path.join(SRC, 'Prefs.java'))
check('game_assistant 键', '"game_assistant"' in pf)
check('默认关闭（要装 Shizuku，不该默认开）',
      bool(re.search(r'getBoolean\(KEY_GAME_ASSIST,\s*false\)', pf)))
# ---- 监测条：开关 / 三个槽位 ----
check('监测条开关键', '"monitor_bar"' in pf)
check('三个槽位键', all(k in pf for k in ('"monitor_slot_top"', '"monitor_slot_mid"',
                                          '"monitor_slot_bottom"')))
# ★ 语义：槽位读出来必须过 isValid，否则跨版本残留的未知 id 会渲染成空白格（无报错）
check('槽位读取校验合法性',
      bool(re.search(r'String v = sp\.getString\(monitorSlotKey\(slot\), def\);\s*'
                     r'return SysMetrics\.isValid\(v\) \? v : def;', pf)))
# ---- 监测范围（全局 / 仅选定应用）----
check('监测范围键存在，且默认 = 全局（false）',
      '"monitor_only_selected"' in pf
      and bool(re.search(r'getBoolean\(KEY_MONITOR_SCOPE,\s*false\)', pf)))
check('应用名单键存在', '"monitor_apps"' in pf)
# ★ 语义（错了不报错、只是"改了没生效"或偶发错乱）：
#   SharedPreferences 返回的 StringSet **不允许修改**，写回也必须是新集合。
check('名单读出即拷贝成 HashSet（读出的实例不能直接用）',
      bool(re.search(r'getStringSet\(KEY_MONITOR_APPS, null\);'
                     r'\s*java\.util\.Set<String> out = new java\.util\.HashSet<String>\(\);', pf)))
check('名单写入前也拷贝（不复用读出的实例）',
      bool(re.search(r'java\.util\.Set<String> set = monitorApps\(ctx\);(.{0,200}?)'
                     r'putStringSet\(KEY_MONITOR_APPS, set\)', pf, re.S)))
check('空包名不写进名单（脏数据会永远删不掉）',
      bool(re.search(r'if \(pkg == null \|\| pkg\.length\(\) == 0\) return;', pf)))
check('槽位默认值 上=CPU 中=FPS 下=RAM',
      bool(re.search(r'return slot == 0 \? SysMetrics\.CPU : \(slot == 1 \? SysMetrics\.FPS '
                     r': SysMetrics\.RAM\);', pf)))

print()
print('=== 11. APK 内含 Shizuku 类与清单项 ===')
apk = dev_apk()
check('APK 存在', os.path.exists(apk), apk)
if os.path.exists(apk):
    with zipfile.ZipFile(apk) as z:
        dex = z.read('classes.dex')
    txt = dex.decode('latin-1')
    for n in ('rikka/shizuku/Shizuku', 'ShizukuProvider',
              'IFpsUserService', 'FpsUserService', 'FpsMath',
              'GameFpsMonitor', 'ShizukuBridge'):
        check('dex 含 %s' % n, n in txt)

print()
print('=== 14. ★ 引用计数严格配对（真机 bug：读数卡住不动）===')
# 用户真机反馈："帧率监测容易失效卡住不动，点开游戏助手的选项才恢复正常，
# 一退出就又卡住"。根因：Activity **条件 acquire + 无条件 release**
# （onPause / onDestroy 各还一次），于是"打开 App 再退出"这条最普通的路径
# 会把悬浮窗那一份引用抵消掉 ⇒ 计数归零 ⇒ 采样线程被停 ⇒ 挂牌数字冻在
# 最后一帧。下面每一条都钉住这条链路上的一道闸门。
mac = code_only(ma)
check('本页有 acquire 记账标志位', 'private boolean gameFpsAcquired = false;' in mac)
# 语义①：acquire 必须在 !gameFpsAcquired 分支里（不能无条件加计数）
check('acquire 只在未持有时加计数',
      bool(re.search(r'if \(!gameFpsAcquired\) \{\s*gameFpsAcquired = true;\s*'
                     r'GameFpsMonitor\.acquire\(this\);', mac)))
# 语义②：release 必须被 gameFpsAcquired 包住（不能无条件还）
check('release 只在持有过时才还',
      bool(re.search(r'private void stopGameFpsSampling\(\)\s*\{.{0,400}?'
                     r'if \(gameFpsAcquired\) \{\s*gameFpsAcquired = false;\s*'
                     r'GameFpsMonitor\.release\(\);', mac, re.S)))
# 语义③：本类里 release 只允许出现一次 —— 就是上面那个配对出口。
#   多出来的任何一处都必然是"没借就还"，也就是本次真机 bug 的原样。
check('MainActivity 里 release 只有配对出口一处',
      mac.count('GameFpsMonitor.release();') == 1,
      '实测 %d 处' % mac.count('GameFpsMonitor.release();'))
# 语义④：onDestroy 也必须走配对方法（第一版它自己也 release 一次）
check('onDestroy 也走配对释放',
      bool(re.search(r'stopGameFpsSampling\(\);\s*if \(shizukuUi != null\)', mac)))
# 语义⑤：重建后不再需要读数（切走板块 / 关掉开关）要当场还回去，
#   否则引用会挂到 onPause 才释放 —— 关了开关后台还在每秒跑 shell。
check('重建后不再需要读数就当场释放',
      bool(re.search(r'if \(settingsSection != 7 \|\| !Prefs\.gameAssistant\(this\)\) '
                     r'stopGameFpsSampling\(\);', mac)))

print()
print('=== 15. 采样器自身的三道防线 ===')
# ① release 多还一份不能停掉采样（防线：把"停掉全世界"降级成"无害空操作"）
check('release 在计数为 0 时忽略（多还无害）',
      bool(re.search(r'public static synchronized void release\(\) \{\s*'
                     r'if \(refs <= 0\) \{', gmc)))
mrel = re.search(r'public static synchronized void release\(\) \{(.*?)\n    \}',
                 gmc, re.S)
check('忽略分支在 refs-- 之前（顺序反了就白写）',
      bool(mrel) and mrel.group(1).find('refs <= 0') < mrel.group(1).find('refs--'),
      ('忽略在 %d，refs-- 在 %d' % (mrel.group(1).find('refs <= 0'),
                                    mrel.group(1).find('refs--'))) if mrel else '没定位到')
# ② 重启线程不能动账本（stop() 才清零，它会把别的使用者一起踢掉）
mrs = re.search(r'void restart\(Context ctx\) \{(.*?)\n    \}', gmc, re.S)
check('有 restart() 且不清零引用计数',
      bool(mrs) and 'refs = 0' not in mrs.group(1))
check('stop() 仍强制清零（服务销毁时用）',
      bool(re.search(r'public static synchronized void stop\(\) \{\s*refs = 0;\s*'
                     r'stopWorker\(\);', gmc)))
# ③ 心跳：每跑完一轮打点，"心跳停了"才可能被外部发现（线程卡在阻塞读上）
check('每轮采样打心跳', 'lastTickMs = android.os.SystemClock.uptimeMillis();' in gmc)
check('对外暴露心跳/读数年龄',
      'millisSinceLastTick' in gmc and 'millisSinceLastSample' in gmc)
# ④ 新会话必须作废旧读数：否则新监听者会被 addListener 回放过期数字
check('新会话作废旧读数',
      bool(re.search(r'lastSample = null;\s*lastSampleMs = 0;', gmc)))
# ⑤ 换线程后旧线程必须自己退出：restart() 会把 running 重新置真，
#    卡死的旧线程一旦从阻塞读返回，看到 running==true 就会接着跑 ⇒ 两条采样线程。
check('换线程有代号比对（旧线程自己退出）',
      'final int myGen = ++generation;' in gmc
      and bool(re.search(r'while \(running && myGen == generation\)', gmc)))

print()
print('=== 16. 悬浮窗自愈 + 过期读数不冒充正常值 ===')
# 自愈①：我们还持有引用、采样却停了（账被别的调用方抵消）⇒ 当场补一次
check('巡检发现"持有引用但采样已停"就补 acquire',
      bool(re.search(r'if \(fpsAcquired && want && ready && '
                     r'!GameFpsMonitor\.isRunning\(\)\) \{.{0,220}?'
                     r'GameFpsMonitor\.acquire\(this\);', fs, re.S)))
# 自愈②：线程还在但长时间跑不完一轮 = 卡在阻塞读上 ⇒ 重启线程（不动账本）
check('巡检发现线程卡死就重启',
      bool(re.search(r'if \(fpsAcquired && want && ready && GameFpsMonitor\.isRunning\(\)'
                     r'.{0,80}?tickAge > FPS_TICK_STALE_MS\) \{.{0,220}?'
                     r'GameFpsMonitor\.restart\(this\);', fs, re.S)))
# 监测条的新鲜度判据必须来自"最近一次成功采样"，不能只看"曾经拿到过"
# （monitorReady 一旦置真就再也不变假 ⇒ 采样停了数字照样挂着不动）
check('监测条文案的新鲜度判据来自"最近采样时刻"',
      bool(re.search(r'final boolean fresh = monitorFresh\(\);', fs)))
check('新鲜度判据内部用 millisSinceLastSample',
      bool(re.search(r'private boolean monitorFresh\(\) \{.{0,240}?'
                     r'millisSinceLastSample\(\)', fs, re.S)))
# ★ 轮次⑩ 重构：中格文案收敛成单一来源 monitorMidText()（渲染与"量宽"必须拿到
#   同一段字，否则会出现"按 A 算宽度、渲染 B"这种诡异错位）。
check('过期读数显示占位符（吸附单值 + 侧栏三行都要）',
      'String midText = monitorMidText();' in fs
      and 'SysMetrics.shortText(ids[1], monitorSnap) : "--"' in fs
      and 'chips[i].setText(fresh ? SysMetrics.fullText' in fs
      and 'monitorFresh()' in fs)
check('过期监测条仍占槽位（显示 --，不退回竖条）',
      'return (everData && hasMid) ? BAR_STRIP : BAR_NONE;' in fmc0)
# ★ 语义：判据必须是"曾经有数据"（everData）而不是"最近 5 秒有数据"——
#   用后者会与 monitorRender 打架：过期时这边退回普通竖条、那边又把监测条置可见，
#   同一个槽位叠两条东西（模拟器实测撞到）。
check('占槽位判据用"曾经有数据"而非"最近有数据"',
      bool(re.search(r'if \(collapsed\) return \(everData && hasMid\) \? BAR_STRIP : BAR_NONE;',
                     fmc0)))
check('巡检日志带采样器状态（真机排查靠它）',
      'monRun=' in fs and 'refs=' in fs and 'tickAge=' in fs and 'sampAge=' in fs)
check('巡检日志带八项指标（真机核对取数）',
      all(k in fs for k in ('cpu=', 'gpu=', 'ramKb=', 'temp=', 'batt=', 'pw=', 'net=')))

print()
print('=== 16b. ★ 八项指标采集（与帧率同一批命令取回）===')
# ★ 为什么必须同批：每个指标各起一次 newProcess 的话，每秒就是 8 个 shell 进程。
check('指标段与帧率同一条命令',
      bool(re.search(r'private static String buildSampleCmd\(String knownLayer\)', gmc))
      and 'MARK_CPU' in gmc and 'MARK_GPU' in gmc and 'MARK_NET' in gmc
      and 'MARK_TMP' in gmc)
check('各段有独立标记（解析器不会互相误吃）',
      all(('public static final String MARK_%s' % k) in fmc0
          for k in ('CPU', 'GPU', 'NET', 'TMP')))
check('图层段在指标段之前截断',
      bool(re.search(r'String layerPart = \(mi >= 0\) \? slice\(out, mi \+ MARK\.length\(\), mc\)', gmc)))
# ★ 解析在纯类（SysMetrics），采样器只负责"喂进去" —— 两处都要查，
#   只查一边的话"搬到纯类"这种重构会让断言假失败/假通过
check('CPU 用 /proc/stat 差值（解析在纯类）',
      'parseProcStat' in fmc0 and 'cpuPercent' in fmc0 and 'metricsAcc.update(' in gmc)
check('GPU 用 kgsl gpubusy 差值',
      'gpubusy' in gmc and 'parseGpuBusy' in fmc0 and 'gpuPercent' in fmc0)
check('网络用 /proc/net/dev 差值',
      '/proc/net/dev' in gmc and 'parseNetBytes' in fmc0 and 'netBytesPerSec' in fmc0)
check('温度用 thermal_zone（取 CPU 区最高）',
      'thermal_zone' in gmc and 'pickTempC' in fmc0)
check('RAM / 电池 / 功耗走 Java API（不需要 shell）',
      'getMemoryInfo' in gmc and 'ACTION_BATTERY_CHANGED' in gmc
      and 'BATTERY_PROPERTY_CURRENT_NOW' in gmc and 'powerW' in gmc)
# ★ 语义：新会话要清差值基准，否则跨会话算 CPU/网络差值会得出荒谬值
check('会话开始清指标基准', 'metricsAcc.reset();' in gmc)
# ★ 语义：差值状态与分段解析收在**纯类**里（桌面 JVM 有 15 项断言，见 fpstest [15]），
#   GameFpsMonitor 只负责"喂进去"
check('差值状态收在纯类里',
      'SysMetrics.Accumulator' in gmc and 'metricsAcc.update(' in gmc)
# ★ 语义：分段标记**单一来源**（以前拼命令一份、解析一份，改一处就静默错位）
check('分段标记单一来源',
      'MARK_CPU = SysMetrics.MARK_CPU' in gmc
      and 'public static final String MARK_CPU' in fmc0
      and 'public static final String MARK_TMP' in fmc0)
# ★ 语义：FPS 的 -1 是"静止"（有效读数），其它指标的 -1 是"没取到"→ 显示 --
check('静止显示 0、取不到显示 --',
      bool(re.search(r'if \(FPS\.equals\(id\)\)   return s\.fps < 0f \? "0" :', code_only(
          read(os.path.join(SRC, 'SysMetrics.java'))))))
check('设置页有三格选择入口', 'addSlotRow' in ma and 'pickMonitorSlot' in ma)
# ★ 语义：回调里必须**真的把指标存下来**，否则八项永远显示 -- 或旧值
#   （这条是被反向验证抓出来的：原先只查了"有 onSample"，删掉赋值照样通过）
check('采样回调把指标写进界面状态',
      bool(re.search(r'SysMetrics\.Snapshot m\) \{.{0,240}?if \(m != null\) monitorSnap = m;',
                     fs, re.S)))
check('设置页有三格实时预览', 'renderMonitorPreview' in ma)
check('长按菜单有监测条开关与显示项入口',
      '隐藏监测条' in fs and '显示监测条' in fs and '显示项…' in fs)
check('显示项入口直达第 7 节',
      bool(re.search(r'private void openSettingsSection\(int section\)', fs))
      and 'MainActivity.EXTRA_SECTION' in fs
      and bool(re.search(r'private boolean applyJumpSection\(Intent intent\)', ma))
      and bool(re.search(r'protected void onNewIntent\(Intent intent\)', ma)))

print()
print('=== 17. exec 必须有超时（否则采样线程永久卡住）===')
# ★ 真机教训：readAll() 是阻塞读，shell 进程不退出就永远不返回 ——
#   采样线程停在这一次调用上，既无回调也无异常，表现正是"卡住不动"。
sbc2 = code_only(read(os.path.join(SRC, 'ShizukuBridge.java')))
check('有超时常量', 'EXEC_TIMEOUT_MS' in sbc2)
check('看门狗超时后 destroy 进程',
      bool(re.search(r'Thread\.sleep\(EXEC_TIMEOUT_MS\).{0,400}?p\.destroy\(\);',
                     sbc2, re.S)))
check('超时的残缺输出判为失败（不能拿去解析出假帧率）',
      'if (killed.get()) return null;' in sbc2)
check('回退路径也受超时约束', 't.join(EXEC_TIMEOUT_MS)' in sbc2)

print()
print('=== 18. APK 里必须含本轮修复（防"源码改了没重编"）===')
# ★ 本工程踩过"验证通过其实验的是上一版的包"（STATE.md 反复强调的脱节）。
#   所以这里不只查源码，直接把**修复引入的字符串/符号**钉进 dex：
#   源码改了但没重编 APK 时这一节会红 —— 那正是要提醒的事（重编即可）。
if os.path.exists(apk):
    with zipfile.ZipFile(apk) as z:
        raw = z.read('classes.dex')
    for lit in ('采样已停但本服务仍持有引用', 'release 被忽略',
                '命令超时 ', '采样线程卡住 ',
                '隐藏监测条', '显示监测条', '显示项…',
                # 轮次⑨（监测范围集成）：源码改名/漏编时这几条先红
                '监测范围：', '选择应用（已选 ', '仅在选定的应用里显示',
                '搜索应用名或包名', '没有匹配的应用。'):
        check('dex 含「%s」' % lit, lit.encode('utf-8') in raw)
    for sym in ('gameFpsAcquired', 'millisSinceLastSample', 'EXEC_TIMEOUT_MS',
                'layerTableNeedsClear', 'clearLayerTable', 'countLayers',
                'SysMetrics', 'parseProcStat', 'parseGpuBusy', 'parseNetBytes',
                'pickTempC', 'powerW', 'monitorTakeStripSlot', 'monitorSideRoot',
                'sideBarX', 'sideBarY', 'barForm',
                'monitorVisible', 'appMatches', 'monitorOnlySelected',
                'monitorApps', 'clearMonitorApps', 'fillMonitorAppList',
                'launcherApps', 'pickMonitorScope',
                # 轮次⑩ 下半场（用户报障三条：应用列表不全 / 竖条显示不完整 /
                # 吸附态隐藏监测条后竖条消失）
                'applyStripSlotOwner', 'monitorWantsStripSlot', 'stripWidthDp',
                'stripWidthDpFor', 'monitorMidText',
                # 轮次⑪ 下半场（拖动卡顿 + 监测条实时跟随 + 可调间距）
                'followMonitorSide', 'lastLpX', 'lastMonSideX', 'REAL_SIZE_TTL_MS',
                'clampMonitorGapDp', 'monitorGapPx', 'ACTION_REPOSITION',
                'repositionPet', 'logMonitorSideBox', 'gapText', 'lastGapApply'):
        check('dex 含符号 %s' % sym, sym.encode('ascii') in raw)
else:
    check('APK 存在（否则无法核对修复是否真的进了包）', False, apk)

print()
print('=== 19. ★ 图层表满了必须清（"读数永久冻住"的真根因）===')
# 真机实测（Xiaomi 2210132C / Android 17）：
#   dumpsys SurfaceFlinger --timestats -dump | grep -c '^layerName = '  → 201
#   而**当前前台 Activity 的活层根本不在表里**，表里全是我们 App 历史 Activity
#   的死层（#998/#1117/#2879，计数冻结在 2848/2445/2185）⇒ 所有候选层都不涨
#   ⇒ 帧率永远 -1 ⇒ 界面永远"静止 / 0 FPS"，**划屏也不涨、不会自愈**。
#   实测 -clear 之后表里只剩 4 条、活层立刻出现并开始涨（106 → 476 帧）。
fmc = code_only(read(os.path.join(SRC, 'FpsMath.java')))
check('FpsMath 有图层表上限常量', 'LAYER_TABLE_CAP = 200' in fmc)
mthr = re.search(r'LAYER_TABLE_CLEAR_AT = (\d+)', fmc)
check('清理阈值低于上限（留余量，别等满了）',
      bool(mthr) and int(mthr.group(1)) < 200,
      ('阈值 %s' % mthr.group(1)) if mthr else '没找到阈值')
# 语义：数层只认行首，与 `grep -c '^layerName = '` 同口径
check('数层只认行首（同 grep ^layerName 口径）',
      bool(re.search(r"charAt\(i - 1\) == '\\n'", fmc)))
# 语义：GameFpsMonitor 必须**真的调用**判据、执行 clear、并作废基准
check('采样时判定图层表是否该清', 'FpsMath.layerTableNeedsClear(' in gmc)
check('判定用的是完整 dump（不是 grep 过的那一段）',
      bool(re.search(r'String full = ShizukuBridge\.exec\("dumpsys SurfaceFlinger '
                     r'--timestats -dump"\);\s*lastFullAuditMs', gmc)))
check('该清就真的清（不是只记日志）',
      bool(re.search(r'if \(FpsMath\.layerTableNeedsClear\(full\)\) \{.{0,400}?'
                     r'clearLayerTable\(\);', gmc, re.S)))
check('清理命令是 --timestats -clear',
      'dumpsys SurfaceFlinger --timestats -clear' in gmc)
mcl = re.search(r'private static void clearLayerTable\(\) \{(.*?)\n    \}', gmc, re.S)
check('清表后作废基准 + 快路径层名（层名随记录一起消失）',
      bool(mcl) and 'layerBaseline.clear()' in mcl.group(1)
      and 'lastActiveLayer = null' in mcl.group(1))
# 语义：稳态走 grep 快路径看不到整张表，必须有**定期全量审计**
check('有定期全量审计（否则稳态永远发现不了表满）',
      'FULL_AUDIT_MS' in gmc
      and bool(re.search(r'if \(needFull \|\| audit\)', gmc)))
check('会话开始时审计时刻归零（进页面即自愈）',
      'lastFullAuditMs = 0;' in gmc)
check('诊断日志带图层条数（真机一眼看出表满没满）', 'layers=' in gmc)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED')
