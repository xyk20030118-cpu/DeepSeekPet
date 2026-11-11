# -*- coding: utf-8 -*-
"""反向验证：游戏助手的断言**必须能抓到回潮**。

为什么必须有这个：
  一个"永远返回 OK"的断言和"真的在守护"的断言，从输出上看一模一样。
  所以每一项都要**故意破坏一次**，确认 verify 脚本退出码变成 1，
  再恢复确认回到 0。抓不到 = 这条断言是空转。

★ 恢复放在 finally 里：中途异常也不能把源码留在被破坏的状态。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
MANIFEST = r'D:\petport_lab\petbuild\AndroidManifest.xml'
BUILD_WIN = os.path.abspath(os.path.join(HERE, '..', '..', '用户', 'DS',
                                         'pet-ui-redesign', 'tools', 'build_win.py'))
VERIFY = os.path.join(HERE, 'verify_game_assistant.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='\n').write(s)


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new):
    """把 old 换成 new，确认 verify 失败，再恢复确认通过。"""
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-42s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-42s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []

print('=== 反向验证：游戏助手断言必须能抓到回潮 ===')
print()

# ① provider authority 写错（provider 自己会校验，写错会静默失效）
results.append(break_and_check(
    'A. provider authority 写错',
    MANIFEST,
    'android:authorities="com.deepseek.pet.shizuku"',
    'android:authorities="com.deepseek.pet.wrong"'))

# ② 依赖名单少一个 jar（编译过、运行期 NoClassDefFoundError）
results.append(break_and_check(
    'B. DEPS_JARS 去掉 shizuku-aidl',
    BUILD_WIN,
    '        "shizuku-aidl.jar",\n',
    ''))

# ③ 依赖名单退回"两份硬编码"（只改一处的老坑）
results.append(break_and_check(
    'C. classpath 退回硬编码双份名单',
    BUILD_WIN,
    'cp = os.pathsep.join(os.path.join(P, "deps", j) for j in DEPS_JARS)',
    'cp = os.pathsep.join([os.path.join(P, "deps", "sherpa-classes.jar")])'))

# ④ FpsMath 去掉静默期过滤（会算出偏低的假 FPS）
results.append(break_and_check(
    'D. FpsMath 去掉静默期阈值比较',
    os.path.join(SRC, 'FpsMath.java'),
    'if (gapMs > 0 && gapMs < LIVE_GAP_MS) {',
    'if (gapMs > 0) {'))

# ⑤ FpsMath 不再跳过全零空槽
results.append(break_and_check(
    'E. FpsMath 不跳空槽',
    os.path.join(SRC, 'FpsMath.java'),
    'if (d == 0 && a == 0 && r == 0) continue;',
    ''))

# ⑥ 手写 Binder 退回继承 IBinder（编译期就会炸的类型坑）
results.append(break_and_check(
    'F. IFpsUserService 改回继承 IBinder',
    os.path.join(SRC, 'IFpsUserService.java'),
    'public interface IFpsUserService extends IInterface {',
    'public interface IFpsUserService extends IBinder {'))

# ⑦ 探针退回 asInterface（绕过编解码，测试变空转）
results.append(break_and_check(
    'G. 探针退回 asInterface（空转）',
    os.path.join(SRC, 'FpsProbeActivity.java'),
    'new IFpsUserService.Proxy(impl.asBinder())',
    'IFpsUserService.Stub.asInterface(impl)'))

# ⑧ 第 7 节从「陪伴」板块摘掉（入口消失）
#    ★ 锚点跟着代码走：轮次⑩ 陪伴板块变成 {1, 6, 3, 7, 8}（多了第 8 节勾选页），
#      这条锚点没同步过一次 —— 表现为 [SKIP] 找不到锚点，而"跳过按未验证计"会
#      让整个套件退出码 1（这机制正是为了逼人回来修锚点）。
results.append(break_and_check(
    'H. 陪伴板块摘掉第 7 节',
    os.path.join(SRC, 'MainActivity.java'),
    # ★ v8.0：陪伴板块多了第 11 节（工作模式），锚点跟着改。
    '{11, 1, 6, 3, 7, 8, 9},',
    '{11, 1, 6, 3},'))

# ⑨ 离开设置页不释放采样（引用计数不降，采样永远不停 → 后台白耗电）
#    锚点必须**唯一**：这里用"removeTaskFromRecents 紧跟 stopGameFpsSampling"这一整段，
#    只写 stopGameFpsSampling() 会命中 onDestroy 里的那一处。
results.append(break_and_check(
    'I. onPause 不释放采样',
    os.path.join(SRC, 'MainActivity.java'),
    # ★ v8.0：这句被包进了“工作模式不移除”的 if 里，缩进/换行都变了；
    #   锚点取不带换行的一行，避免受行尾影响。
    '            removeTaskFromRecents();',
    '            // 的不释放采样（反向验证用）'))

# ⑩ 游戏助手默认开启（不该默认开 —— 需要装 Shizuku）
results.append(break_and_check(
    'J. 游戏助手默认改成开启',
    os.path.join(SRC, 'Prefs.java'),
    'return sp.getBoolean(KEY_GAME_ASSIST, false);',
    'return sp.getBoolean(KEY_GAME_ASSIST, true);'))

# ⑪ 绑定失败被静默吞掉（真机排查时最要命的回潮）
#    ★ 破坏必须**真实**：第一版我写成 `if (false) bindError = ...`，
#      子串还在，断言当然仍通过 —— 那不是断言弱，是我模拟的回退不真实。
#      真实回退是"有人把 catch 改回静默吞掉"。
results.append(break_and_check(
    'K. bind 异常退回静默 catch',
    os.path.join(SRC, 'ShizukuBridge.java'),
    '        } catch (Throwable t) {\n'
    '            bindState = BindState.FAILED;\n'
    '            bindError = t.getClass().getSimpleName()\n'
    '                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));\n'
    '            Log.e(TAG, "bindUserService 抛异常", t);\n'
    '            notifyBind();\n'
    '        }',
    '        } catch (Throwable ignore) {\n        }'))

# ⑫ 实时口径退回"累计平均"（会显示一个永不变化的数字）
#    ★ 这是新架构下真实会犯的错：averageFPS 是自 -enable 起的累计值，
#      实测连续三次 dump 都是同一个数（62.500）。直接用它就没有"实时"可言。
# ⑫ 选层退回"帧数最大"（真机上会选中早已死掉的旧层，计数冻结 → 帧率永远 -1）
results.append(break_and_check(
    'L. 选层退回"帧数最大"（死层 bug）',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '            if (d > bestDelta) { bestDelta = d; best = st; }',
    '            if (best == null || st.totalFrames > best.totalFrames) best = st;'))

# ⑯ 前台退回 UsageStats（异步落库有延迟 → 切应用后读数跟着上一个应用走）
results.append(break_and_check(
    'P. 前台退回 UsageStats（有延迟）',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '        String pkg = parseForegroundPkg(fgPart);',
    '        String pkg = null;'))

# ⑬ 监测条不再顶替竖条（用户明确要求的核心行为）
results.append(break_and_check(
    'M. 吸附态监测条不顶替竖条',
    os.path.join(SRC, 'FloatingService.java'),
    '            if (monitorTakeStripSlot()) {',
    '            if (false) {'))

# ⑭ 命中判定不认监测条（会拖不动，等于"没同步竖条的操作"）
results.append(break_and_check(
    'N. dockHitStrip 不认监测条',
    os.path.join(SRC, 'FloatingService.java'),
    '            if (dockState == PetDockState.COLLAPSED && monitorBar != null\n'
    '                    && monitorBar.getVisibility() == View.VISIBLE) {\n'
    '                target = monitorBar;\n'
    '            }',
    ''))

# ⑮ onPause 改回全局 stop（会把悬浮窗的挂牌一起停掉）
results.append(break_and_check(
    'O. onPause 改回全局 stop',
    os.path.join(SRC, 'MainActivity.java'),
    '        stopGameFpsSampling();\n    }',
    '        GameFpsMonitor.stop();\n    }'))

# ⑰ ★ 本次真机 bug 的原样回潮：无条件 release。
#    条件 acquire + 无条件 release ⇒ "打开 App 再退出"就把悬浮窗那一份引用
#    抵消掉 ⇒ 采样线程被停 ⇒ 挂牌数字冻在最后一帧（用户报的"卡住不动"）。
results.append(break_and_check(
    'Q. 退回无条件 release（真机 bug 原样）',
    os.path.join(SRC, 'MainActivity.java'),
    '        if (gameFpsAcquired) {\n'
    '            gameFpsAcquired = false;\n'
    '            GameFpsMonitor.release();\n'
    '        }',
    '        GameFpsMonitor.release();'))

# ⑱ 反过来：无条件 acquire（计数虚高 ⇒ 退出时还不回去，采样永远停不下来）
results.append(break_and_check(
    'R. 退回无条件 acquire（计数虚高）',
    os.path.join(SRC, 'MainActivity.java'),
    '        if (!gameFpsAcquired) {\n'
    '            gameFpsAcquired = true;\n'
    '            GameFpsMonitor.acquire(this);\n'
    '        }',
    '        gameFpsAcquired = true;\n'
    '        GameFpsMonitor.acquire(this);'))

# ⑲ 采样器不再防"多还一份"（防线②失效，任何调用方的账歪都能停掉采样）
results.append(break_and_check(
    'S. release 去掉"计数为 0 则忽略"',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '        if (refs <= 0) {\n'
    '            android.util.Log.i(TAG, "release 被忽略：引用计数已是 0（调用方多还了一份）");\n'
    '            return;\n'
    '        }\n',
    ''))

# ⑳ 悬浮窗自愈①失效（持有引用但采样停了也不管，只能等用户点开游戏助手）
results.append(break_and_check(
    'T. 去掉"采样已停"自愈',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (fpsAcquired && want && ready && !GameFpsMonitor.isRunning()) {',
    '        if (false) {'))

# ㉑ 悬浮窗自愈②失效（线程卡在阻塞读上也不再重启）
results.append(break_and_check(
    'U. 去掉"线程卡死"重启',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (fpsAcquired && want && ready && GameFpsMonitor.isRunning()\n'
    '                && tickAge > FPS_TICK_STALE_MS) {',
    '        if (false) {'))

# ㉒ 监测条退回"只认曾经拿到过数据"（采样停了数字照样挂着不动 = 用户看到的卡住）
results.append(break_and_check(
    'V. 监测条不看新鲜度（旧数字骗人）',
    os.path.join(SRC, 'FloatingService.java'),
    '        final boolean fresh = monitorFresh();',
    '        final boolean fresh = monitorReady;'))

# ㉓ exec 去掉超时判定（残缺输出被当成正常结果拿去解析）
results.append(break_and_check(
    'W. exec 超时后仍返回残缺输出',
    os.path.join(SRC, 'ShizukuBridge.java'),
    '            if (killed.get()) return null;',
    ''))

# ㉔ 采样循环丢掉线程代号（restart 后卡死的旧线程会接着跑 ⇒ 两条采样线程）
results.append(break_and_check(
    'X. 采样循环丢掉线程代号',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    'while (running && myGen == generation) {',
    'while (running) {'))

# ㉕（原 BB「门控不查游戏名单」已删除）
#   用户 2026-10-07 要求把白名单改回"全部打开 / 关闭"，白名单连同这条破坏项一起撤除；
#   现在开关语义由 verify 里的"开关 = 总开关 ∧ 监测条显示开关"钉住。

# ㉖（原 CC「侧栏不看桌宠位置」已删除）
#   那段判据在轮次⑥后半段被抽成纯函数 SysMetrics.sideFor/sideBarX，
#   锚点自然失效 —— 同样的语义现在由 II（把 <= 改成 >=）覆盖，
#   另加 fpstest [13] 的 11 项行为断言。留着会是"跳过"，而跳过按未验证计。

# ㉗ 槽位读取不校验（跨版本残留的未知 id 会渲染成空白格，且没有任何报错）
results.append(break_and_check(
    'DD. 槽位读取不校验合法性',
    os.path.join(SRC, 'Prefs.java'),
    '        String v = sp.getString(monitorSlotKey(slot), def);\n'
    '        return SysMetrics.isValid(v) ? v : def;',
    '        return sp.getString(monitorSlotKey(slot), def);'))

# ㉘ 采样里的指标不落到界面状态（八项永远显示 -- 或旧值）
results.append(break_and_check(
    'EE. 指标不写进界面状态',
    os.path.join(SRC, 'FloatingService.java'),
    '                        if (m != null) monitorSnap = m;',
    ''))

# ㉙ 藏桌宠时不收侧栏（独立窗口会孤零零挂在设置页上）
results.append(break_and_check(
    'FF. 藏桌宠时不收侧栏',
    os.path.join(SRC, 'SysMetrics.java'),
    '        if (!gateOpen || petHidden) return BAR_NONE;',
    '        if (!gateOpen) return BAR_NONE;'))

# ㉚ 巡检不补侧栏定位（首帧没量出宽高就永远按估算值错位）
results.append(break_and_check(
    'GG. 巡检不补侧栏定位',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (monitorSideShowing) positionMonitorSide();',
    ''))

# ㉛ 监测条没数据也顶替竖条（屏幕上是条 "--" 的怪条，且与普通竖条语义打架）
results.append(break_and_check(
    'HH. 没数据也顶替竖条',
    os.path.join(SRC, 'SysMetrics.java'),
    '        if (collapsed) return (everData && hasMid) ? BAR_STRIP : BAR_NONE;',
    '        if (collapsed) return hasMid ? BAR_STRIP : BAR_NONE;'))

# ㉜ 侧栏选边判据反了（桌宠靠左却挂到左边 → 顶出屏幕/挡手势）
results.append(break_and_check(
    'II. 侧栏选边判据反了',
    os.path.join(SRC, 'SysMetrics.java'),
    'return petCenter <= screenW / 2 ? SIDE_RIGHT : SIDE_LEFT;',
    'return petCenter >= screenW / 2 ? SIDE_RIGHT : SIDE_LEFT;'))

# ㉝ ★ 吸附态不看"中格有没有设"（中格设成"不显示"时仍画一条空竖条）
#    用户 2026-10-07 修订：吸附时只显示中格 —— 中格没设就该退回普通竖条。
results.append(break_and_check(
    'OO. 吸附态不看中格有没有设',
    os.path.join(SRC, 'SysMetrics.java'),
    '        if (collapsed) return (everData && hasMid) ? BAR_STRIP : BAR_NONE;',
    '        if (collapsed) return everData ? BAR_STRIP : BAR_NONE;'))

# ㉞ 换会话不清差值基准（拿上一会话的计数当基准 ⇒ CPU/网络算出荒谬值）
results.append(break_and_check(
    'KK. 换会话不清指标基准',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '        metricsAcc.reset();',
    ''))

# ㉟ 差值状态不喂进累计器（八项里 CPU/GPU/网络永远是 -1）
results.append(break_and_check(
    'LL. 指标输出不喂进累计器',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '            metricsAcc.update(metPart, android.os.SystemClock.uptimeMillis(), s);',
    ''))

# ㊱ 形态判据不看"有没有槽位"（三格全设成不显示时仍画一条空胶囊）
results.append(break_and_check(
    'NN. 三格全空也画空胶囊',
    os.path.join(SRC, 'SysMetrics.java'),
    '        return hasAny ? BAR_SIDE : BAR_NONE;',
    '        return BAR_SIDE;'))

# ㊲ ★ 跟手收放退回"只改 dockStrip 可见性"（真机 bug 原样：
#     监测条占槽位时，拉过半它还挂着、拉回来又和普通竖条叠在一起）
results.append(break_and_check(
    'PP. 跟手只改 dockStrip（槽位叠两条）',
    os.path.join(SRC, 'FloatingService.java'),
    '        try { dockSlotShow(p <= 0.5f); } catch (Throwable ignore) {}',
    '        try { if (dockStrip != null) dockStrip.setVisibility('
    'p <= 0.5f ? View.VISIBLE : View.GONE); } catch (Throwable ignore) {}'))

# ㊳ 展开时"先收槽位再切态"（monitorRender 以为还在吸附态 → 把监测条按竖条形态摆回来）
results.append(break_and_check(
    'QQ. 展开时先收槽位再切态',
    os.path.join(SRC, 'FloatingService.java'),
    '        dockState = PetDockState.EXPANDED;\n'
    '        // 展开即收竖条（窗口滑出过程中不再需要位置指示）\n'
    '        dockHideStrip();',
    '        dockHideStrip();\n'
    '        dockState = PetDockState.EXPANDED;'))

# ㉕ ★ 图层表满了不清（真机"读数永久冻住"的原样回潮：活层进不了表，计数全冻结）
results.append(break_and_check(
    'Y. 图层表满了不清（永久冻住）',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '                clearLayerTable();',
    '                // clearLayerTable();'))

# ㉖ 清表后不作废基准（旧层名已随记录消失，留着会算出荒谬差值）
results.append(break_and_check(
    'Z. 清表后不作废基准',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    '        layerBaseline.clear();\n'
    '        lastActiveLayer = null;\n'
    '        fastMiss = 0;',
    '        fastMiss = 0;'))

# ㉗ 去掉定期全量审计（稳态 grep 快路径永远看不到整张表 ⇒ 发现不了表满）
results.append(break_and_check(
    'AA. 去掉定期全量审计',
    os.path.join(SRC, 'GameFpsMonitor.java'),
    'if (needFull || audit) {',
    'if (needFull) {'))

# ㊴ ★ 监测范围判据反转（把"默认全局"翻成"默认只显示选定应用"）
#     —— 这是本轮新逻辑里最危险的一种错法：不改任何界面文案，
#     用户升级后监测条**哪儿都不显示**（名单是空的），看着像 bug 而不是设置。
results.append(break_and_check(
    'RR. 监测范围判据反了（默认变"仅选定"）',
    os.path.join(SRC, 'SysMetrics.java'),
    'if (!onlySelected) return true;',
    'if (onlySelected) return true;'))

# ㊵ ★ 空名单/null 名单被当成"全部"（部分应用模式形同虚设：没勾也全显示）
results.append(break_and_check(
    'SS. 空名单被当成「全部」',
    os.path.join(SRC, 'SysMetrics.java'),
    'return list != null && list.contains(pkg);',
    'return list == null || list.contains(pkg);'))

# ㊶ ★ 部分应用模式不判"知不知道前台是谁"（还没采样时拿上一轮的包名去判 ⇒ 乱显示）
results.append(break_and_check(
    'TT. 部分应用模式不判前台包名是否已知',
    os.path.join(SRC, 'SysMetrics.java'),
    'if (pkg == null || pkg.length() == 0) return false;',
    'if (pkg == null) pkg = "";'))

# ㊷ ★ 勾选只改界面、不写 prefs（重启就丢，用户会以为"勾了没用"）
results.append(break_and_check(
    'UU. 勾选不写 prefs（只改界面）',
    os.path.join(SRC, 'MainActivity.java'),
    'Prefs.setMonitorApp(MainActivity.this, pkg, checked);',
    ''))

# ㊸ ★ 勾选整页重建（每点一个应用滚动位置回顶部 —— "点一下像重新加载"的老毛病）
results.append(break_and_check(
    'VV. 勾选后整页重建（滚动位置丢失）',
    os.path.join(SRC, 'MainActivity.java'),
    '                    if (countTv != null) {\n'
    '                        int c = Prefs.monitorAppCount(MainActivity.this);\n'
    '                        countTv.setText(c == 0\n'
    '                                ? "\u5df2\u9009 0 \u4e2a\uff08\u7a7a\u540d\u5355 \u21d2 '
    '\u76d1\u6d4b\u6761\u4e0d\u663e\u793a\uff09"\n'
    '                                : "\u5df2\u9009 " + c + " \u4e2a\u5e94\u7528");\n'
    '                    }',
    '                    showSettings();'))

# ㊹ ★ "部分应用"档下不出现勾选入口（用户切了档却无处可勾）
results.append(break_and_check(
    'WW. 仅选定档不显示勾选入口',
    os.path.join(SRC, 'MainActivity.java'),
    'if (monOnlySel) {',
    'if (false) {'))

# ㊺ ★ 吸附态"隐藏监测条"后不交还槽位（用户报障原样：整条竖条直接消失）
results.append(break_and_check(
    'XX. 监测条收掉后不交还槽位（竖条消失）',
    os.path.join(SRC, 'FloatingService.java'),
    '            applyStripSlotOwner(false);',
    ''))

# ㊻ ★ 竖条宽度退回写死（用户报障原样：`5.2G` 这类文本被硬截半个字符）
results.append(break_and_check(
    'YY. 竖条宽度退回写死 20dp（文本被截）',
    os.path.join(SRC, 'FloatingService.java'),
    'int w = (int) (d * stripWidthDpFor(midText) + 0.5f);',
    'int w = (int) (d * MONITOR_STRIP_W_DP + 0.5f);'))

# ㊼ ★ 露出宽度不看内容（只加宽竖条不加露出 ⇒ 多出来的部分被屏幕边缘切掉）
results.append(break_and_check(
    'ZZ. 露出宽度退回固定 26dp',
    os.path.join(SRC, 'FloatingService.java'),
    'if (monitorWantsStripSlot()) {',
    'if (false) {'))

# ㊽ ★ 清单去掉 <queries>（Android 11+ 包可见性 ⇒ "读不到完整的应用列表"）
results.append(break_and_check(
    'AAA. 清单去掉 <queries>（应用列表不全）',
    MANIFEST,
    '    <queries>\n',
    '    <!-- no queries -->\n'))

# ㊾ ★ 侧栏不再跟随桌宠（用户报障原样："监测条距离桌宠太远…要实时跟随"）
results.append(break_and_check(
    'BBB. 侧栏不跟随桌宠',
    os.path.join(SRC, 'FloatingService.java'),
    '            windowManager.updateViewLayout(root, params);\n'
    '        }\n'
    '        followMonitorSide();',
    '            windowManager.updateViewLayout(root, params);\n'
    '        }'))

# ㊿ ★ 拖动每帧重复查真实屏幕尺寸（卡顿来源之一）
results.append(break_and_check(
    'CCC. 真实屏幕尺寸不再缓存（每帧查 Display）',
    os.path.join(SRC, 'FloatingService.java'),
    'if (!force && realScreenW > 0 && realScreenH > 0',
    'if (false && realScreenW > 0 && realScreenH > 0'))

# 51 ★ 隐藏的余额气泡仍每帧发窗口更新（白送的跨进程布局）
results.append(break_and_check(
    'DDD. 余额气泡隐藏时仍每帧重算',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (balRoot == null || balRoot.getVisibility() != View.VISIBLE) return;',
    ''))

# 52 ★ 侧栏位置没变也发窗口更新（拖动每帧一次 IPC）
results.append(break_and_check(
    'EEE. 侧栏未变也发窗口更新',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (x == lastMonSideX && y == lastMonSideY) return;',
    ''))

# 53 ★ 间距退回写死 6dp（"可调节"整个失效）
results.append(break_and_check(
    'FFF. 间距写死 6dp（可调节失效）',
    os.path.join(SRC, 'FloatingService.java'),
    'SysMetrics.sideBarX(pr[0], pr[2], sw, w, monitorGapPx(dm))',
    'SysMetrics.sideBarX(pr[0], pr[2], sw, w, (int) (dm.density * 6))'))

# 54 ★ 滑块退回 reloadPet（拖动中重建整套视图：又卡又闪）
results.append(break_and_check(
    'GGG. 滑块退回重建视图（卡+闪）',
    os.path.join(SRC, 'MainActivity.java'),
    'Prefs.repositionPet(MainActivity.this);',
    'Prefs.reloadPet(MainActivity.this);'))

# 55 ★ 滑块只 new 出来、不进布局（本轮真实出现过的 bug：界面上没有滑块）
results.append(break_and_check(
    'HHH. 间距滑块不进布局（用户看不到滑块）',
    os.path.join(SRC, 'MainActivity.java'),
    '                box.addView(gapBar);\n',
    ''))

# 56 ★ 跟随时每帧都发窗口更新（软渲染/弱机上自己就是掉帧源）
results.append(break_and_check(
    'III. 跟随不节流（每帧发窗口更新）',
    os.path.join(SRC, 'FloatingService.java'),
    'if (now - monitorFollowAtMs < MONITOR_FOLLOW_MIN_MS) return;',
    ''))

# 55b ★ 长按菜单里那两项又变成"无条件显示"（用户 2026-10-07 要求：关掉游戏助手就不显示）
results.append(break_and_check(
    'JJJ. 长按菜单两项不再受游戏助手门控',
    os.path.join(SRC, 'FloatingService.java'),
    '        if (assistOn) {\n'
    '            longPressBody.addView(newMenuChip("CHART", barOn ? "隐藏监测条" : "显示监测条",',
    '        if (true) {\n'
    '            longPressBody.addView(newMenuChip("CHART", barOn ? "隐藏监测条" : "显示监测条",'))

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
