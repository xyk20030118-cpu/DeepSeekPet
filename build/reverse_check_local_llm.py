# -*- coding: utf-8 -*-
"""反向验证：**本地模型引擎层**的断言必须能抓到回潮（轮次62）。

每一项都**故意破坏一次**，确认 `verify_local_llm.py` 退出码变 1，再恢复确认回到 0。

## ★★ 本轮的破坏分两类，安全性天差地别

**甲类（改源文件）** —— 照老规矩：改前读原文、`finally` 里写回。
  适用：Java / JNI / assemble.py / run_all_tests.bat 这类文本。

**乙类（改二进制）** —— ★ 本轮**不碰工程里的真 .so**。
  做法是先把整个 `lib/arm64-v8a/` **拷一份到 %TEMP%**，在副本上改一个字节，
  再用环境变量 `DSP_LIB_DIR` 把断言**指过去**跑整套。
  好处：破坏与恢复都发生在临时目录里，**工程零风险** ——
  轮次61 那次"强杀反向套件 ⇒ `finally` 没跑到 ⇒ 破坏态留在盘上"的事故，
  在乙类上从根上不可能发生（顶多多一个 temp 目录）。

## 本轮最值得盯的几条

  * **D（e_machine）** —— 只改 2 个字节就能让"架构对不对"这条判据失效；
    而"看文件名/后缀"是**永远看不出架构**的。这条守的是本轮最核心的那个坑。
  * **E（UTF-8 攒尾巴）** —— 摘掉它，一个被 token 切开的汉字就会以乱码出到气泡上，
    而且是**中文对话必然遇到**（不是边角情况）。
  * **A/B/F（方法名）** —— 名字对不上时 javac 不报、链接器不报，
    要到真机第一次调用才炸；这三条就是为本轮最阴的坑立的。
  * **I（APK 与 lib/ 对齐）** —— 守住"改了 lib/ 忘了重编 APK"。
"""
import io
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
JNI_DIR = os.path.join(ROOT, 'jni')
REAL_LIBDIR = os.path.join(ROOT, 'lib', 'arm64-v8a')
VERIFY = os.path.join(HERE, 'verify_local_llm.py')

JAVA = os.path.join(SRC, 'LocalLlm.java')
CPP = os.path.join(JNI_DIR, 'dspllm_jni.cpp')
INFO = os.path.join(JNI_DIR, 'BUILD_INFO.txt')
ASM = os.path.join(HERE, 'assemble.py')
RUNBAT = os.path.join(HERE, 'run_all_tests.bat')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def readb(p):
    return io.open(p, 'rb').read()


def writeb(p, b):
    io.open(p, 'wb').write(b)


def run_verify(env_extra=None):
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    r = subprocess.run([sys.executable, VERIFY], capture_output=True, env=env)
    return r.returncode


def break_and_check(label, path, old, new, count=1):
    """甲类：改源文件 → 断言必须红 → 写回 → 断言必须绿。

    ★★ 轮次64 实测：这里**必须按字节**改文件。
    原来的写法是 `read()`（文本模式，会把 CRLF 读成 LF）+ `write(..., newline='\\n')`
    —— 跑一趟就把被改的那个文件的**行尾悄悄改成 LF**。
    `run_all_tests.bat` 是 **CRLF**（它自己的头注释和 `check_pack_contents.py` 都这么要求），
    于是**每跑一次全量套件，那个文件就被改坏一次**，而没有任何判据会红
    （实测：本轮把 .bat 修回 CRLF 后，跑完一轮全量套件又被这两份反向套件改回 LF）。
    ⇒ 改成 `rb`/`wb` + UTF-8 编码锚点：改与还原都是**字节精确**的。
    """
    ob, nb = old.encode('utf-8'), new.encode('utf-8')
    orig = readb(path)
    if ob not in orig:
        print('  [SKIP] %-56s （找不到锚点，按未验证计）' % label)
        return None
    try:
        writeb(path, orig.replace(ob, nb, count))
        bad = run_verify()
        writeb(path, orig)
        good = run_verify()
    finally:
        writeb(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-56s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('== 反向验证：本地模型引擎层（ELF 架构 / 导出符号 / 方法名一一对应 / 流式回调 / 进包）==')
print()

# ---------------------------------------------------------------- 甲类：源码改名
# A. ★★ Java 的 native 方法名拼错一个字母（两边一起错，像真的手滑那样）
results.append(break_and_check(
    'A. ★★Java native 方法名拼错（nativeLoad→nativeLaod）',
    JAVA, 'nativeLoad', 'nativeLaod', count=99))

# B. ★★ JNI 函数名拼错（JNI 那边的名字必须跟着包名/类名走）
results.append(break_and_check(
    'B. ★★JNI 函数名拼错（nativeGenerate 少个 e）',
    CPP, 'Java_com_deepseek_pet_LocalLlm_nativeGenerate',
    'Java_com_deepseek_pet_LocalLlm_nativeGenrate'))

# C. ★★ 包名/类名编错（改 Java 的 package 会与 JNI 符号前缀脱节）
results.append(break_and_check(
    'C. ★★Java 的包名改掉（与 JNI 符号前缀脱节）',
    JAVA, 'package com.deepseek.pet;', 'package com.deepseek.pett;'))

# D. ★★ 回调方法名两边不一致（JNI 查 onToken，Java 那边叫 onTokenX）
results.append(break_and_check(
    'D. ★★JNI 查的回调名与 Java 不一致',
    CPP, '"onToken"', '"onTokenX"'))

# E. ★★★ 摘掉"攒够完整 UTF-8 才回调"那一行（半个汉字就会出到气泡上）
results.append(break_and_check(
    'E. ★★★摘掉 UTF-8 尾巴暂存（汉字会被切开出乱码）',
    CPP, 'if (i + (size_t) need > n) break;', ';'))

# F. ★★ 流式回调改成 NewStringUTF（emoji/4 字节字符会乱码）
results.append(break_and_check(
    'F. ★★流式回调改用 NewStringUTF',
    CPP, 'st.env->NewString(u16.data()', 'st.env->NewStringUTF(u16.data()'))

# G. ★★ 生成循环不再逐个 token 推给回调（气泡就变成"憋完再一次性出"）
results.append(break_and_check(
    'G. ★★生成循环不再推流（摘掉 sink_push）',
    CPP, 'if (!sink_push(st, piece, (size_t) np)) break;', ';'))

# H. ★★ 卸载不再先发停止信号（正在生成时卸载会卡住 UI 线程）
results.append(break_and_check(
    'H. ★★卸载不再先发停止信号',
    CPP, '    g_cancel.store(true);\n', ''))

# H2. ★★ 把 llama_sampler_accept 加回来（sample 已经 accept 过了 ⇒ 双记）
#     ★ 这条是"照官方示例抄"最容易抄错的地方：官方 sample 用法旁边就写着 accept，
#       但那个 accept 是给**手动 apply 采样链**的人用的；用 sample() 就不该再 accept。
results.append(break_and_check(
    'H2. ★★把 llama_sampler_accept 加回来（双记）',
    CPP, '        if (llama_vocab_is_eog(g_vocab, id)) break;',
    '        llama_sampler_accept(smpl, id);\n        if (llama_vocab_is_eog(g_vocab, id)) break;'))

# I. ★★ assemble.py 不再是"通配收 .so"（改成白名单就等于新库白加）
#    ★ 锚点取 `x.endswith(".so")]`（带中括号）—— assemble.py 里 `endswith(".so")`
#      出现两次，另一处是"页对齐判定"；只锚前半段会打到**错的那一处**，
#      断言照样绿 ⇒ 反向项自己先假红，白验一轮（本轮第一次跑就是这样）。
results.append(break_and_check(
    'I. ★★assemble.py 不再通配收 lib 下的 .so',
    ASM, "x.endswith('.so')]", "x.endswith('.so.bak')]"))

# J. ★ BUILD_INFO 里的 commit 被抹掉（下一个窗口就没法复现了）
#    ★ 必须替换**全部**出现处：commit 在文件里出现两次（正文一行 + 下载 URL 里一次），
#      只换第一处的话 URL 里还留着一个 40 位 sha，断言照样绿。
results.append(break_and_check(
    'J. ★BUILD_INFO 丢掉 commit（全部出现处）',
    INFO, 'b76b13379d3b7d67ab0bc424516bb6fd2522bd8e', 'master', count=99))

# K. ★★ 本套件从全量套件里摘掉（摘了就等于从来不跑）
results.append(break_and_check(
    'K. ★★run_all_tests.bat 摘掉 verify_local_llm',
    RUNBAT, 'verify_local_llm.py', 'verify_local_llm_typo.py'))

print()
print('== 乙类：在 %TEMP% 的**副本**上破坏二进制（工程真库全程只读）==')

# ---------------------------------------------------------------- 乙类：副本 + DSP_LIB_DIR
TMP = tempfile.mkdtemp(prefix='r62_libmut_')
COPY = os.path.join(TMP, 'arm64-v8a')
try:
    shutil.copytree(REAL_LIBDIR, COPY)
    ENV = {'DSP_LIB_DIR': COPY}
    print('  副本目录：%s' % COPY)

    # L. 对照：副本**没被动过**时必须绿 —— 否则下面两条"红"毫无意义（可能是探针/环境坏了）
    good_copy = run_verify(ENV)
    print('  [%s] %-56s 副本原样=%d' % ('OK' if good_copy == 0 else 'FAIL',
                                       'L. ☆对照：副本原样时断言必须绿', good_copy))
    results.append(good_copy == 0)

    # M. ★★ 只改 ELF 头里 e_machine 那 2 个字节：0xB7(aarch64) → 0x3E(x86_64)
    #    "只看文件名/后缀"永远发现不了这件事 —— 这条就是本判据存在的全部理由。
    LLA = os.path.join(COPY, 'libllama.so')
    raw = bytearray(open(LLA, 'rb').read())
    assert raw[:4] == b'\x7fELF', '副本不是 ELF？'
    old_machine = struct.unpack_from('<H', raw, 18)[0]
    struct.pack_into('<H', raw, 18, 0x3E)
    open(LLA, 'wb').write(bytes(raw))
    new_machine = struct.unpack_from('<H', open(LLA, 'rb').read(), 18)[0]
    bad = run_verify(ENV)
    ok = (old_machine == 0xB7 and new_machine == 0x3E and bad == 1)
    print('  [%s] %-56s e_machine 0x%02X→0x%02X，断言=%d'
          % ('OK' if ok else 'FAIL', 'M. ★★只改 e_machine 两字节（架构谎报）',
             old_machine, new_machine, bad))
    results.append(ok)

    # 把 e_machine 改回来，再破坏 .dynstr 里的一个符号名
    raw = bytearray(open(LLA, 'rb').read())
    struct.pack_into('<H', raw, 18, 0xB7)
    open(LLA, 'wb').write(bytes(raw))
    mid = run_verify(ENV)
    print('  [%s] %-56s 改回后=%d' % ('OK' if mid == 0 else 'FAIL',
                                     'N. ☆对照：e_machine 改回后重新变绿', mid))
    results.append(mid == 0)

    # O. ★★ 抹掉 .dynstr 里的 "llama_decode" ⇒ 导出符号这条必须红
    raw = bytearray(open(LLA, 'rb').read())
    hit = raw.find(b'llama_decode\x00')
    if hit < 0:
        print('  [SKIP] %-56s （副本 .dynstr 里找不到 llama_decode）' % 'O. ★★抹掉导出符号名')
        results.append(None)
    else:
        raw[hit:hit + len('llama_decode')] = b'x' * len('llama_decode')
        open(LLA, 'wb').write(bytes(raw))
        bad = run_verify(ENV)
        ok = (bad == 1)
        print('  [%s] %-56s 断言=%d' % ('OK' if ok else 'FAIL',
                                       'O. ★★抹掉导出符号 llama_decode', bad))
        results.append(ok)
finally:
    # ★ 临时副本一定要清掉（它占了 ~32 MB；而且留在那儿会让下一个窗口困惑）
    shutil.rmtree(TMP, ignore_errors=True)
    print('  副本已清理：%s' % (not os.path.isdir(TMP)))

# P. ☆ 收尾自检：工程里的真库有没有被动过（乙类全程只读，这里必须绿）
results.append(run_verify() == 0)
print('  [%s] %-56s' % ('OK' if results[-1] else 'FAIL',
                        'P. ☆收尾：工程真库未被改动，断言仍绿'))

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
