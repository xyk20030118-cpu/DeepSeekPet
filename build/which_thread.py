"""找出桌宠进程里**哪个线程**在吃 CPU。

★ 为什么必须做这一步：用户报"占用高、发热高"，但我先前的假设
  （模型包 60fps 重绘）**站不住** —— 设备上桌宠走的是内置 webp 路径
  （`custom_webp` 目录是空的），那条路由系统的 AnimatedImageDrawable
  驱动，根本不会调用我改的 `scheduleNextFrame`。
  那 20% CPU 是**别的东西**。

  按 STATE.md 的纪律：先测量，别猜。
  线程名能直接告诉我消费方是渲染、网络、AI、还是保活心跳。

做法：读 /proc/<pid>/task/*/stat 两次，取差值算每个线程的 CPU%。
"""
import re
import subprocess
import sys
import time

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'
sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HZ = 100.0          # 内核 USER_HZ，Android 通用 100


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


def pid():
    return sh('shell', 'pidof', PKG).stdout.decode().strip()


def read_threads(p):
    """返回 {tid: (comm, utime+stime)}"""
    out = {}
    names = sh('shell', 'ls', '/proc/%s/task' % p).stdout.decode('utf-8', 'replace')
    for tid in names.split():
        if not tid.isdigit():
            continue
        st = sh('shell', 'cat', '/proc/%s/task/%s/stat' % (p, tid)).stdout.decode('utf-8', 'replace')
        m = re.match(r'(\d+) \((.*?)\) (.*)', st.strip())
        if not m:
            continue
        comm = m.group(2)
        rest = m.group(3).split()
        # stat 的第 14/15 项是 utime/stime（字段 1-based，去掉前面的 pid/comm/state）
        try:
            utime, stime = int(rest[11]), int(rest[12])
        except (IndexError, ValueError):
            continue
        out[tid] = (comm, utime + stime)
    return out


def main():
    secs = float(sys.argv[1]) if len(sys.argv) > 1 else 10
    p = pid()
    if not p:
        sys.exit('桌宠没在跑')
    print('pid = %s，采样 %.0f 秒…' % (p, secs))

    a = read_threads(p)
    time.sleep(secs)
    b = read_threads(p)

    rows = []
    for tid, (comm, t1) in b.items():
        if tid not in a:
            continue
        dt = t1 - a[tid][1]
        pct = dt / HZ / secs * 100.0
        rows.append((pct, comm, tid))
    rows.sort(reverse=True)

    print()
    print('  %-7s  %-22s %s' % ('CPU%', '线程名', 'tid'))
    total = 0.0
    for pct, comm, tid in rows:
        if pct < 0.15:
            continue
        print('  %6.1f%%  %-22s %s' % (pct, comm, tid))
        total += pct
    print('  ' + '-' * 40)
    print('  %6.1f%%  （合计）' % total)
    print()
    print('  ★ Java 线程名可读（如 RenderThread / mqt_js / OkHttp / Timer）;')
    print('    纯数字或 "Binder:xxx" 是系统/原生线程。')


if __name__ == '__main__':
    main()