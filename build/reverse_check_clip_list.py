# -*- coding: utf-8 -*-
"""反向验证：轮次㉚「动作列表：搜索 / 排序 / 拖拽」的断言必须能抓到回潮。

每一项都**故意破坏一次**，确认 verify 退出码变 1，再恢复确认回 0。
抓不到 = 那条断言是空转。恢复放 finally，中途异常也不留破坏态。

★ 盯的是三件事：
  ① 搜索/排序/拖拽的**语义**又被改回"整串匹配 / 不稳定 / 差一格"；
  ② 三处动作列表**退回** AlertDialog 的纯滚动 setItems/setMultiChoiceItems；
  ③ 拖拽变成假功能（顺序不落盘、或被 ScrollView 抢走手势、或干脆给"顺序没用"的场景绑定也加上）。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
CLIP = os.path.join(SRC, 'ClipList.java')
PANE = os.path.join(SRC, 'ClipListPane.java')
DRAG = os.path.join(SRC, 'DragSortPane.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
AMC = os.path.join(SRC, 'ActionMenuConfig.java')
FPSTEST_BAT = os.path.join(HERE, 'fpstest', 'run.bat')
FEATURES = os.path.join(HERE, 'verify_features.py')
VERIFY = os.path.join(HERE, 'verify_clip_list.py')


def read(p):
    # ★ newline='' —— 原样保留 CRLF。本套件会碰 run.bat，而它**必须**是 CRLF：
    #   用默认的通用换行读进来会把 \r\n 折成 \n，恢复时就把整个 .bat 写成纯 LF
    #   （cmd 会因此吞行、报一片假 FAIL —— run_all_tests.bat 顶部专门写了这个坑）。
    return io.open(p, encoding='utf-8', errors='replace', newline='').read()


def write(p, s):
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


def run_verify():
    return subprocess.run([sys.executable, VERIFY], capture_output=True).returncode


def break_and_check(label, path, old, new):
    orig = read(path)
    if old not in orig:
        print('  [SKIP] %-56s （找不到锚点，按未验证计）' % label)
        return None
    try:
        write(path, orig.replace(old, new, 1))
        bad = run_verify()
        write(path, orig)
        good = run_verify()
    finally:
        write(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-56s 破坏后退出码=%d 恢复后=%d'
          % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('=== 反向验证：动作列表（搜索 / 排序 / 拖拽）===')
print()

# ── ① 搜索语义 ──
results.append(break_and_check(
    'A. 搜索不再分词 AND（退回整串匹配 ⇒ 搜"回应 3"搜不到）',
    CLIP,
    'for (String t : tk) if (n.indexOf(t) < 0) return false;',
    'for (String t : tk) if (n.indexOf(query) < 0) return false;'))

results.append(break_and_check(
    'B. 分隔符不再归一化（打 `点击回应3` 又找不到 `点击回应-3`）',
    CLIP,
    "if (ch == '-' || ch == '_' || ch == ' ' || ch == '\\u3000' || ch == '\\u00B7'",
    "if (false"))

# ── ② 排序语义 ──
results.append(break_and_check(
    'C. 排序不再稳定（同分元素每次打开都可能换位置）',
    CLIP,
    'Integer ia = order.get(a), ib = order.get(b);',
    'Integer ia = Integer.valueOf(0), ib = Integer.valueOf(0);'))

results.append(break_and_check(
    'D. 排序模式不再循环（按钮点了没反应）',
    CLIP,
    'public static int nextSort(int mode) {',
    'public static int nextSortDisabled(int mode) {'))

# ── ③ 拖拽语义 ──
results.append(break_and_check(
    'E. 拖拽重排差一格（经典的 to-1 错误）',
    CLIP,
    'out.add(t, v);',
    'out.add(t > 0 ? t - 1 : 0, v);'))

results.append(break_and_check(
    'F. 落点判定退回 `<`（原地按一下被当成往下挪一格）',
    CLIP,
    'if (y <= rowMids[i]) return i;',
    'if (y < rowMids[i]) return i;'))

# ── ④ 列表视图（搜索 / 只看已选 / 高度）──
results.append(break_and_check(
    'G. 搜索框被摘掉（又只能滚）',
    PANE,
    '搜索动作名',
    '搜索'))

results.append(break_and_check(
    'H. 「只看已选」被摘掉（勾了几十个没法回头核对）',
    PANE,
    'onlyPickedCb.setText(ClipList.onlyPickedText(picked.size(), all.size()));',
    'onlyPickedCb.setText("");'))

results.append(break_and_check(
    'I. 列表高度不再有界（对话框里 0 高或撑破屏）',
    PANE,
    'heightPixels * 0.62',
    'heightPixels * 0'))

# ── ⑤ MainActivity：三处退回老实现 ──
results.append(break_and_check(
    'J. 动作库点一行不再预览（单选回调断了）',
    MAIN,
    'previewClip(as, null, id);',
    'previewClip(as, null, all.get(0));'))

results.append(break_and_check(
    'K. ★ 改绑退回 setMultiChoiceItems（一百多个动作只能滚）',
    MAIN,
    '        pane.setPicked(bound);',
    '        setMultiChoiceItems(new String[0], null, null);\n        pane.setPicked(bound);'))

results.append(break_and_check(
    'L. ★ 改绑不再保留已调好的权重（保存时一律重置成 1）',
    MAIN,
    'int old = as.weightOf(scene, id);',
    'int old = 1;'))

# ── ⑥ DragSortPane：拖拽不能是假功能 ──
results.append(break_and_check(
    'M. ★ 不再挡 ScrollView 拦截（手势被抢走 ⇒ 拖了没反应）',
    DRAG,
    'scroll.requestDisallowInterceptTouchEvent(true);',
    'scroll.requestDisallowInterceptTouchEvent(false);'))

results.append(break_and_check(
    'N. ★ 拖动改成重排行视图（正在接收触摸的 View 被回收 ⇒ 手势断）',
    DRAG,
    'dragRow.setTranslationY(rawY - downRawY);',
    'dragRow.setY(rawY - downRawY);'))

results.append(break_and_check(
    'O. ★ 拖完不落盘（顺序存不下来 = 白拖）',
    MAIN,
    'ActionMenuConfig.setClips(MainActivity.this, cid, label, order);',
    'ActionMenuConfig.setClips(MainActivity.this, cid, label, pick);'))

results.append(break_and_check(
    'P. 分类顺序入口被摘（分类拖不动）',
    MAIN,
    'ActionMenuConfig.reorderCategories(MainActivity.this, cid, order);',
    'ActionMenuConfig.count(MainActivity.this, cid);'))

results.append(break_and_check(
    'Q. ★ 给"顺序没用"的场景绑定也加上拖拽（假功能回潮）',
    MAIN,
    '        pane.setPicked(bound);',
    '        DragSortPane fake = null;\n        pane.setPicked(bound);'))

# ── ⑦ 测试基建本身 ──
results.append(break_and_check(
    'W. ★ 内容没占满一屏也自动滚（落点整体漂一格）',
    DRAG,
    '        if (column.getHeight() <= scroll.getHeight()) return;',
    ''))

results.append(break_and_check(
    'U. ★ 去掉"已经收过尾"的守卫（UP→CANCEL 会把重排做两遍）',
    DRAG,
    'if (dragRow == null && dragFrom < 0) return;        // 已经收过尾（UP→CANCEL 的第二次）',
    ''))

results.append(break_and_check(
    'V. ★ 又在触摸回调里直接重建行（实测崩在 ViewGroup.dispatchDraw）',
    DRAG,
    '        column.post(new Runnable() {',
    '        if (true) {'))

results.append(break_and_check(
    'T. ★「只看已选」时计数行又写"共 N 个"（用户以为动作丢了）',
    CLIP,
    'if (shown != total) return "显示 " + shown + " / " + total + " 个（只看已选）";',
    ''))

results.append(break_and_check(
    'R. fpstest 不再编译纯类 ClipList（[29] 整节消失）',
    FPSTEST_BAT,
    '..\\..\\src\\com\\deepseek\\pet\\ClipList.java',
    ''))

results.append(break_and_check(
    'S. verify_features 里本轮的探针被摘（"正式版落后"又会藏起来）',
    FEATURES,
    "    ('动作列表：可搜索列表视图', 'ClipListPane'),",
    ''))

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
