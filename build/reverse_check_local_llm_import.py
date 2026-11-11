# -*- coding: utf-8 -*-
"""反向验证：**本地模型导入层**的断言必须能抓到回潮（轮次62 第二轮）。

每一项都**故意破坏一次**，确认 `verify_local_llm_import.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 `finally` 里（中途异常也不能把源码留在被破坏的状态）。

## ★★ 跑这个套件之前先确认没有别的套件在跑

反向套件会**临时改源文件**。轮次61 的教训：并发跑（或中途强杀）时 `finally` 跑不到，
**破坏态会留在盘上**（那次丢过 `Appearance.resolve()` 的整段回退逻辑，
表现是下一轮 fpstest 莫名其妙报红）。⇒ 一次只跑一个套件。

## 本条最值得盯的四条

  * **A（GGUF magic）** —— 改成永远 true：任何 zip/mp4 都会被当模型收下，
    而界面上一切正常（"导入成功"），要到加载时才炸。这是本轮最核心的那条。
  * **L（整读进内存）** —— 把 256 KB 定长缓冲换成"按文件大小分配"：
    1 GB 的模型直接 OOM，而且是在**用户已经等了半分钟**之后才 OOM。
  * **K（后台线程）** —— 改成在调用线程上跑：界面冻住几分钟、系统可能直接 ANR。
  * **X/W/S（接线）** —— 入口行 / 分组标题 / 文件选择器写法没了，
    功能还在但**用户点不到**（这类"静默消失"最难发现）。

## 锚点纪律

所有 `old` 都取**稳定写法**（源码里的整行/整个表达式），
**绝不**用 md5 / 版本号 / 文件大小这类每轮会变的读数做锚点
（轮次61 的 `reverse_check_docs.py` 就因为这个每轮变的值而整体报红）。
另外：Java 里同一个写法可能在多处出现（如 `deleteQuietly(part);`），
那种情况显式给 `count=99` **全部替换**，避免"只改到第一处、断言照样绿"的假红。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
VERIFY = os.path.join(HERE, 'verify_local_llm_import.py')

MODEL_FILE = os.path.join(SRC, 'ModelFile.java')
MODEL_STORE = os.path.join(SRC, 'ModelStore.java')
MODEL_IMPORTER = os.path.join(SRC, 'ModelImporter.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
PREFS = os.path.join(SRC, 'Prefs.java')
FPSTEST_BAT = os.path.join(HERE, 'fpstest', 'run.bat')
FPSTEST_JAVA = os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')
RUNBAT = os.path.join(HERE, 'run_all_tests.bat')
FEATURES = os.path.join(HERE, 'verify_features.py')


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def readb(p):
    return io.open(p, 'rb').read()


def writeb(p, b):
    io.open(p, 'wb').write(b)


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new, count=1):
    """改源文件 → 断言必须红 → 写回 → 断言必须绿。

    ★★ 轮次64 实测：这里**必须按字节**改文件。
    原来的写法是 `read()`（文本模式，把 CRLF 读成 LF）+ `write(..., newline='\\n')`
    —— 跑一趟就把被改文件的**行尾悄悄改成 LF**。`run_all_tests.bat` 是 **CRLF**
    （它自己的头注释与 `check_pack_contents.py` 都这么要求），于是**每跑一次全量套件
    就被改坏一次**，且没有任何判据会红（本轮实测踩到：把 .bat 修回 CRLF 后，
    跑完一轮全量套件又被本套件改回 LF ⇒ 源码包里的 .bat 一直不合格）。
    ⇒ 改成 `rb`/`wb` + UTF-8 编码锚点：改与还原都**字节精确**。
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
print('== 反向验证：本地模型导入层（GGUF 门槛 / 流式后台拷贝 / 可取消 / 入口接线）==')
print()

# ---------------------------------------------------------------- 一、GGUF 身份（纯类）
# A. ★★★ magic 判定改成永远为真：任何文件都被当模型收下，界面上一切正常
results.append(break_and_check(
    'A. ★★★GGUF magic 判定失效（永远收下）',
    MODEL_FILE,
    "head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F'",
    'true'))

# B. ★★ 不再只取最后一段路径（带 .. 的名字可能指到目录外面）
results.append(break_and_check(
    'B. ★★文件名不再只取最后一段路径',
    MODEL_FILE,
    "int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\\\'));",
    'int cut = -1;'))

# C. ★★ 全是点的名字不再被拒（"." / ".." 会被当文件名）
results.append(break_and_check(
    'C. ★★"." / ".." 不再被拒',
    MODEL_FILE,
    'if (s.length() == 0 || isAllDots(s)) return null;',
    'if (s.length() == 0) return null;'))

# D. ★★ 前导点不再削（"点开头 = 隐藏文件"，用户会以为没导入）
results.append(break_and_check(
    'D. ★★不再削前导点（文件会被隐藏）',
    MODEL_FILE,
    "while (i < s.length() && s.charAt(i) == '.') i++;",
    ';'))

# E. ★★ 体积文案不再固定 Locale（德语环境下会显示 398,5）
results.append(break_and_check(
    'E. ★★体积文案不再固定 Locale.US',
    MODEL_FILE,
    'String.format(Locale.US, "%.1f", v)',
    'String.format("%.1f", v)'))

# F. ★★ 限长不再截主体（中文长名字会 ENAMETOOLONG 落盘失败）
results.append(break_and_check(
    'F. ★★超长名字不再截断',
    MODEL_FILE,
    's = s.substring(0, MAX_BASE_CHARS) + EXT;',
    ';'))

# G. ★★ 空间检查不再留余量（卡在最后几 MB 失败）
results.append(break_and_check(
    'G. ★★空间检查不再留余量',
    MODEL_FILE,
    'if (extra < RESERVE_MIN) extra = RESERVE_MIN;',
    ';'))

# ---------------------------------------------------------------- 二、落盘（ModelStore）
# H. ★★ 落盘不再用 filesDir（换成 cacheDir ⇒ 系统空间紧张时会被清掉，模型莫名消失）
results.append(break_and_check(
    'H. ★★落盘位置改成 cacheDir',
    MODEL_STORE,
    'new File(ctx.getFilesDir(), ModelFile.DIR)',
    'new File(ctx.getCacheDir(), ModelFile.DIR)'))

# I. ★★ 可用空间不再用 getUsableSpace（getFreeSpace 会把系统保留块算成可用 ⇒ "够"然后写一半失败）
#    ★ 必须 count=99（全部替换）：`getUsableSpace()` 在 ModelStore 里出现**两次**
#      —— 一次在类注释里、一次在代码里；只换第一处会打到注释上，
#      代码没变 ⇒ 断言照样绿 ⇒ 这一项变成"假通过"（本轮第一遍跑就是这么漏的）。
results.append(break_and_check(
    'I. ★★可用空间改用 getFreeSpace',
    MODEL_STORE,
    'getUsableSpace()',
    'getFreeSpace()',
    count=99))

# J. ★★ 半拉文件不再清（盘上会一直留着几百 MB 的 .part）
results.append(break_and_check(
    'J. ★★半拉文件（.part）不再清',
    MODEL_STORE,
    'if (f.isFile() && ModelFile.isPartName(f.getName()) && f.delete())',
    'if (false && f.isFile() && f.delete())'))

# K. ★★ "当前模型的文件不在就清掉选择"失效（界面显示一个不存在的模型）
results.append(break_and_check(
    'K. ★★当前模型失效后不再清掉选择',
    MODEL_STORE,
    'if (!f.isFile()) {',
    'if (false) {'))

# ---------------------------------------------------------------- 三、拷贝（ModelImporter）
# L. ★★★ 定长缓冲改成"按文件大小分配" = 一次整读进内存 ⇒ 大模型必 OOM
results.append(break_and_check(
    'L. ★★★拷贝改成按文件大小整读（OOM）',
    MODEL_IMPORTER,
    'final byte[] buf = new byte[CHUNK];',
    'final byte[] buf = new byte[sizeHint > 0 ? (int) sizeHint : CHUNK];'))

# M. ★★★ 拷贝挪回调用线程（界面冻住 + ANR）
results.append(break_and_check(
    'M. ★★★拷贝不再起后台线程',
    MODEL_IMPORTER,
    't.start();',
    'work.run();'))

# N. ★★ 第一块不再验 magic（zip 也会被拷完十分钟）
results.append(break_and_check(
    'N. ★★第一块不再验 GGUF magic',
    MODEL_IMPORTER,
    'if (n < 4 || !ModelFile.isGguf(buf)) {',
    'if (n < 4) {'))

# O. ★★ 取消标志不再看（点取消要等整个文件拷完）
results.append(break_and_check(
    'O. ★★拷贝循环不再看取消标志',
    MODEL_IMPORTER,
    'if (job.isCancelled()) {',
    'if (false) {',
    count=99))

# P. ★★ 失败不再清理半拉文件（既占空间，又让人以为"导入过了"）
results.append(break_and_check(
    'P. ★★失败/取消不再清理半拉文件',
    MODEL_IMPORTER,
    'deleteQuietly(part);',
    '',
    count=99))

# Q. ★★ 拷完不再对账（截断的模型会被当成好的收下）
results.append(break_and_check(
    'Q. ★★拷完不再与系统给的大小对账',
    MODEL_IMPORTER,
    'if (sizeHint > 0 && copied != sizeHint) {',
    'if (false) {'))

# R. ★★ 空间检查不再在动手之前做（拷到 90% 才失败）
results.append(break_and_check(
    'R. ★★动手前不再查可用空间',
    MODEL_IMPORTER,
    'if (sizeHint > 0 && !ModelFile.enoughSpace(free, sizeHint)) {',
    'if (false) {'))

# ---------------------------------------------------------------- 四、偏好（Prefs）
# S. ★★ "当前用哪个"的键改了（老用户的选择丢失 = 界面回到"未导入"）
results.append(break_and_check(
    'S. ★★当前模型偏好的键被改',
    PREFS,
    '"local_llm_model"',
    '"local_llm_model_renamed"'))

# ---------------------------------------------------------------- 五、界面接线（MainActivity）
# T. ★★ 分组标题不再写明"本机跑、不联网"
results.append(break_and_check(
    'T. ★★分组标题丢了"本机跑不联网"',
    MAIN,
    '本地模型（手机本机跑，不联网）',
    '本地模型'))

# U. ★★ 文件选择器改用 GET_CONTENT（拿不到长期可读的文档 Uri）
#    ★ 锚点必须**连到只有 startGgufPick 才有的那一行**（`application/gguf`）：
#      `MainActivity` 里 `new Intent(Intent.ACTION_OPEN_DOCUMENT)` 有 5 处
#      （配色导入导出 / 动画导入 / …），只写这一小段就会打到**别人那一处** ——
#      我这段没动，断言照样绿（本轮第一遍跑就是这么漏的第二项）。
OPEN_DOC_OLD = ('new Intent(Intent.ACTION_OPEN_DOCUMENT);\n'
                '        i.addCategory(Intent.CATEGORY_OPENABLE);\n'
                '        i.setType("*/*");\n'
                '        i.putExtra(Intent.EXTRA_MIME_TYPES,\n'
                '                new String[]{"application/octet-stream", "application/gguf", "*/*"});')
OPEN_DOC_NEW = OPEN_DOC_OLD.replace('Intent.ACTION_OPEN_DOCUMENT', 'Intent.ACTION_GET_CONTENT')
results.append(break_and_check(
    'U. ★★文件选择器改用 GET_CONTENT',
    MAIN, OPEN_DOC_OLD, OPEN_DOC_NEW))

# V. ★★ 进度框不再是横向进度条（用户看不到拷贝进展）
results.append(break_and_check(
    'V. ★★进度框不再是横向进度条',
    MAIN,
    'pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);',
    ';'))

# W. ★★ 取消不再发停止信号（进度框停不下来）
results.append(break_and_check(
    'W. ★★取消不再发停止信号',
    MAIN,
    'holder[0].cancel();',
    ';',
    count=99))

# X. ★★ 删除确认框不再说清"删的是当前模型"（用户不知道删完要重新导入）
results.append(break_and_check(
    'X. ★★删除确认框丢掉"当前模型"提示',
    MAIN,
    '★ 它正是当前使用的模型，删完需要重新导入才能用本地模型。',
    '删掉就没了。'))

# Y. ★★ 导入入口那行没了（功能还在但用户点不到）
results.append(break_and_check(
    'Y. ★★导入入口文案被改掉',
    MAIN,
    '\\uD83D\\uDCE5 导入 .gguf 文件…',
    '\\uD83D\\uDCE5 导入模型…',
    # ★★ 轮次64 实测：这句文案**不止一处**了（轮次64 新增的"还没导入模型"引导里
    #    也点了同一个按钮）。只换第一处的话，引导里那句还在 ⇒ 判据照样绿 ⇒ **假红**。
    #    ⇒ 全部替换（这正是"同一写法出现在多处时给 count=99"这条纪律的由来）。
    count=99))

# Z. ★★ 删模型时不再后台 unload（正在生成时会把主线程卡住）
results.append(break_and_check(
    'Z. ★★删模型时不再卸载已载入的模型',
    MAIN,
    'LocalLlm.unload()',
    ';',
    count=99))

# ---------------------------------------------------------------- 六、纯类真跑 + 登记
# AA. ★★ fpstest 不再编 ModelFile（纯类就没被真跑过）
results.append(break_and_check(
    'AA. ★★fpstest 不再编 ModelFile',
    FPSTEST_BAT,
    r'..\..\src\com\deepseek\pet\ModelFile.java',
    ''))

# AB. ★★ fpstest 里 GGUF 判定那条断言失效（"真跑"变成"跑了个恒真"）
results.append(break_and_check(
    'AB. ★★fpstest 的 GGUF 判定断言改成恒真',
    FPSTEST_JAVA,
    'ModelFile.isGguf(ok3)',
    'true'))

# AC. ★★ fpstest 不再切到德语环境（Locale.US 那条判据就失去意义）
results.append(break_and_check(
    'AC. ★★fpstest 不再验证其他 locale 下的小数点',
    FPSTEST_JAVA,
    'java.util.Locale.setDefault(java.util.Locale.GERMANY);',
    'java.util.Locale.getDefault();'))

# AD. ★★ 本套件从全量套件里摘掉（摘了就等于从来不跑）
results.append(break_and_check(
    'AD. ★★run_all_tests.bat 摘掉本套件',
    RUNBAT,
    'verify_local_llm_import.py',
    'verify_local_llm_import_typo.py'))

# AE. ★★ 全量套件的编号总数没跟着改（说明"加了两个套件"这件事没落地）
#    ★★ 锚点**按 bat 里的当前总数现场算**，不钉死某一轮的数字 ——
#      轮次66 就是被这个坑咬了一次：还写着 /71，而当时总数已经是 /73
#      ⇒ "找不到锚点 ⇒ 跳过 ⇒ 红"（全量套件跑到 66/73 才发现）。
#      这次索性连注释里那句"不能钉死"也真的落到代码上。
_BAT_TXT = open(RUNBAT, encoding='utf-8', errors='replace').read()
_M = re.search(r'echo =+ (\d+)/(\d+) ', _BAT_TXT)
TOTAL_NOW = _M.group(2) if _M else '73'
results.append(break_and_check(
    'AE. ★★全量套件编号总数没改（/%s → /67）' % TOTAL_NOW,
    RUNBAT,
    '/%s ' % TOTAL_NOW,
    '/67 ',
    count=99))

# AF. ★★ 交付验证的探针没跟上前端（真机上"看不到功能"就没人发现）
results.append(break_and_check(
    'AF. ★★verify_features 没加本轮的探针',
    FEATURES,
    '本地模型：设置页组标题',
    '本地模型：设置页组标题X'))

# AG. ★★ 大小未知时进度又变回"按块报"（1 GB ≈ 4000 次 runOnUiThread 改进度框文案 ⇒ 界面发顿）
results.append(break_and_check(
    'AG. ★★大小未知时进度按块回调（刷死 UI）',
    MODEL_IMPORTER,
    'if (copied - lastBytes >= REPORT_STEP_BYTES) {',
    'if (true) {'))

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
