# -*- coding: utf-8 -*-
"""打包前清掉 `build/` 下的**可重建测试产物**（待办挂了两轮的那条，轮次⑳ 手工清过一次）。

## 为什么需要它

项目移植包会把**整个 `build/`** 打进去 —— 这是**故意**的：工具链（`build/toolchain`）、
角色包原料（`build/charpack_src`）、106 个动作源文件（`model/webp`）都在里面，
拿到源码包的人才重建得出 APK 与角色包。

但 `build/` 里同时也堆着**每跑一轮就重建一次**的东西：
`_uitest`（设备脚本的测试媒体 + 测试角色包，实测 8 MB）、`__pycache__`、
`fpstest/out`（编译产物）、`_rttest/cache`（结果缓存）、设备 dump（`_cu.xml`）……
它们进源码包只是噪音：轮次⑳ 手工清省了 55 MB，轮次㊱/㊲ 又各手工清一次 8 MB
⇒ **手工清 = 一定会忘**，所以固化成脚本，并由 `pack_delivery.py` 在打包前自动调用。

## ★ 判据（哪些删、哪些**绝不**删）

删的只有一类：**跑一次脚本/编译就能重建的产物**。
下面这些看着像"临时文件"，但都是**物证或输入**，一律保留（写在这里，免得下一个人顺手清掉）：

| 保留 | 为什么 |
|---|---|
| `build/_*.log` 的**最近 5 轮** | **逐轮物证** —— 但只留最近 5 轮（见下面「日志保留策略」） |
| `build/monitor_shots/`（6 MB） | 监测条那几轮的**截图证据**（大清理时明确"一律保留"） |
| `build/toolchain/` + `toolchain-pc/`（60 MB） | 构建**要用**它（android.jar / d8 / aapt2），删了编不了 |
| `build/charpack_src/` + `model/webp/` | 角色包**原料**（重建默认角色包要用） |
| `build/fpstest/fixtures/` | fpstest 的**输入**（真实报文 fixture），不是产物 |
| `build/_rttest/shim/` + `build/cpidtest/` | 真跑 harness 的**源码与桩**，不是产物 |

## ★★ 日志保留策略（轮次58 用户要求"清理一下项目冗余文件"，由此固化）

**只留最近 5 个轮次的 `_*.log`，更早的自动删。**

为什么改这条（原来是"`_*.log` 一律保留"）：每轮跑全量套件 + 构建会留下 5~6 个日志，
攒到轮次54 已经有 **135 个 / 6.2 MB**；再跑几十轮就是几百个文件。
而这些日志的**读数已经逐条抄进 `STATE.md` 的轮次记录**（"日志 `前摇=… 摇源=动作`"这种），
日志本身是**可再生成的证据副本**，不是唯一事实来源。

★ 轮次号从文件名解析（`_r54_alltests.log` → 54）；认不出轮次号的（`_p1_*` / `_cu_*` 这类
早期的阶段日志）按"最老"处理。★ `_perf_drag_*.txt` 这类**不是 `_*.log`** 的输出不动
（`perf_drag.py` 还在写它）。

## 用法

    python build/clean_test_artifacts.py --dry-run   # 只看会删什么（不删任何东西）
    python build/clean_test_artifacts.py             # 真删，并打印释放了多少

★ `pack_delivery.py` 在打源码包之前会**自动调用**本脚本（第三个参数是工程根时）——
所以"忘了清"不会再发生；真要复核，直接看打包输出里那一行 `[clean]`。
"""
import fnmatch
import os
import re
import shutil
import sys

# ★★ 必须在 **import pf 之前**设：本脚本末尾要 `import pf` 去擦 pref 副本，
#   而那次 import 会在 `build/` 下**重新生成** `__pycache__` —— 清完又长出来，
#   打包复验当场抓到（第一次跑就是这么红的：包里剩 2 条 `__pycache__/pf.cpython-*.pyc`）。
sys.dont_write_bytecode = True

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)          # build/ 的上一级 = 工程根
sys.path.insert(0, HERE)

# (相对路径, 类型, 为什么可以删)
REMOVE = [
    ('build/_uitest',        'dir',  '设备脚本的测试媒体/测试角色包（跑一次就重建）'),
    ('build/__pycache__',    'dir',  'Python 字节码'),
    ('build/fpstest/out',    'dir',  'fpstest 编译产物（run.bat 会重编）'),
    ('build/_rttest/out',    'dir',  '真跑 harness 的编译产物'),
    ('build/_rttest/cache',  'dir',  '真跑 harness 的**结果缓存**（就是它导致过"时红时绿"）'),
    ('build/_rttest/tmp',    'dir',  '真跑 harness 的临时目录'),
    ('build/work_win',       'dir',  'Windows 构建中间产物'),
    ('build/out_win',        'dir',  'Windows 构建中间产物'),
    ('build/cpidtest/out',   'dir',  'cpidtest 的编译产物'),
    ('build/_cu.xml',        'file', 'uiautomator dump（每次设备脚本都重写）'),
    ('build/_d.xml',         'file', 'uiautomator dump'),
    ('build/_p.xml',         'file', 'pf.py 的 pref 中转文件'),
    ('build/_syntaxchk.args','file', '语法检查的 javac 参数文件'),
]
# 递归清掉任何位置的 __pycache__
PYCACHE_GLOB = '**/__pycache__'

# ★★ 逐轮日志：只留最近 N 个轮次（见文件头「日志保留策略」）
KEEP_LOG_ROUNDS = 5
LOG_ROUND_RE = re.compile(r'^_r(\d+)')


def stale_round_logs():
    """→ [(路径, 大小, 轮次号)]：**该删**的逐轮日志（更早的轮次）。

    ★ 轮次号从文件名解析（`_r54_alltests.log` → 54）；认不出的（`_p1_*` / `_cu_*` 这类
      早期阶段日志）算 0 —— 它们本来就最老。
    """
    logs = []
    for f in os.listdir(HERE):
        if not (f.startswith('_') and f.endswith('.log')):
            continue                       # 只管 `_*.log`（`_perf_drag_*.txt` 不动）
        m = LOG_ROUND_RE.match(f)
        logs.append((f, int(m.group(1)) if m else 0))
    rounds = sorted({r for _f, r in logs}, reverse=True)[:KEEP_LOG_ROUNDS]
    keep = set(rounds)
    out = []
    for f, r in logs:
        if r in keep:
            continue
        p = os.path.join(HERE, f)
        out.append((p, size_of(p), r))
    return out, sorted(keep, reverse=True)


def human(n):
    return '%.2f MB' % (n / 1048576.0) if n >= 1048576 else '%.1f KB' % (n / 1024.0)


def size_of(path):
    if os.path.isfile(path):
        return os.path.getsize(path)
    total = 0
    for dp, _dn, fn in os.walk(path):
        for f in fn:
            try:
                total += os.path.getsize(os.path.join(dp, f))
            except OSError:
                pass
    return total


def main():
    dry = '--dry-run' in sys.argv
    print('== 清理 build/ 下的可重建测试产物（%s）==' % ('干跑，不删' if dry else '真删'))
    freed = 0
    removed = 0
    for rel, kind, why in REMOVE:
        p = os.path.join(ROOT, rel.replace('/', os.sep))
        if not (os.path.isdir(p) if kind == 'dir' else os.path.isfile(p)):
            continue
        sz = size_of(p)
        print('  - %-24s %9s  %s' % (rel, human(sz), why))
        if not dry:
            try:
                shutil.rmtree(p) if kind == 'dir' else os.remove(p)
            except OSError as e:
                print('    !! 删不掉：%s' % e)
                continue
        freed += sz
        removed += 1
    # 递归 __pycache__
    for dp, dn, _fn in list(os.walk(ROOT)):
        for d in list(dn):
            if fnmatch.fnmatch(d, '__pycache__'):
                p = os.path.join(dp, d)
                rel = os.path.relpath(p, ROOT).replace('\\', '/')
                if any(rel == r[0] for r in REMOVE):
                    continue
                sz = size_of(p)
                print('  - %-24s %9s  %s' % (rel, human(sz), 'Python 字节码（递归）'))
                if not dry:
                    try:
                        shutil.rmtree(p)
                    except OSError as e:
                        print('    !! 删不掉：%s' % e)
                        continue
                freed += sz
                removed += 1
    # ★★ 逐轮日志：只留最近 KEEP_LOG_ROUNDS 个轮次（轮次58 固化；见文件头）
    stale, kept = stale_round_logs()
    if stale:
        tot = sum(s for _p, s, _r in stale)
        print('  - %-24s %9s  %s' % ('build/_*.log（%d 个旧轮次）' % len(stale), human(tot),
                                    '逐轮日志：只留最近 %d 轮，保留 %s'
                                    % (KEEP_LOG_ROUNDS, '/'.join('r%d' % r for r in kept))))
        if not dry:
            for p, _s, _r in stale:
                try:
                    os.remove(p)
                except OSError as e:
                    print('    !! 删不掉 %s：%s' % (os.path.basename(p), e))
                    tot -= _s
        freed += tot
        removed += 1
    # ★ 设备 pref 的本地副本：**擦成占位**（不是删文件）——
    #   项目移植包会把 build/ 打进去，里面绝不能有用户的 Key（轮次㉘ 的真泄漏）。
    try:
        import pf
        pf.wipe_local()
        print('  - %-24s %9s  %s' % ('build/_prefs.xml', '已擦除', '设备 pref 本地副本（防 Key 进源码包）'))
    except Exception as e:
        print('  !! 擦除 _prefs.xml 失败：%s' % e)
    print('== %s：%d 项，%s ==' % ('将释放' if dry else '已释放', removed, human(freed)))
    print('（保留：最近 %d 轮的 _*.log 物证 / monitor_shots 截图证据 / toolchain* / '
          'charpack_src / model 原料）' % KEEP_LOG_ROUNDS)
    return 0


if __name__ == '__main__':
    sys.exit(main())
