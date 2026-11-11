# -*- coding: utf-8 -*-
"""断言：**动作列表的搜索 / 排序 / 拖拽**（轮次㉚，待办 0m ③）。

用户选的方向：动作库 / 场景页升级。原来的样子是——角色包一百多个动作，
动作库、场景改绑、自定义分类三处全是 `AlertDialog` 的纯滚动列表（`setItems` /
`setMultiChoiceItems`），找一个动作只能滚到底。

这一轮把三处换成**可搜索 + 可排序**的列表，另外给"顺序真的有用"的地方
（自定义动作菜单：分类顺序 + 分类内动作顺序 = 三击桌宠看到的顺序）加了**拖拽**。

钉这些（都写进断言了）：
  A. 纯类 `ClipList` 在，且**零 Android 依赖**（fpstest [29] 编的就是打进 APK 的那份）；
     搜索是"分词 AND + 分隔符无关"，排序**稳定**，拖拽的 `move/dropIndex` 语义明确；
  B. `ClipListPane` 三件套齐（搜索框 / 排序按钮 / 只看已选）+ 列表高度有界（对话框里 wrap_content 会 0 高）；
  C. `MainActivity` 三处**都换了**，且全工程不再有 `setMultiChoiceItems(`（老实现彻底退场）；
  D. `DragSortPane`：挡住 ScrollView 拦截、只改 translationY（不重排行视图 ⇒ 手势不会断）、顺序真的落盘；
  E. ★ **诚实边界**：场景绑定顺序**不给拖拽** —— 播放是 `Group.pick` 按权重**随机**抽，
     顺序不参与；那里给拖拽就是假功能。断言钉住"拖拽只出现在自定义菜单那两处"。

反向破坏见 reverse_check_clip_list.py（每条都实测过能变红）。
"""
import io
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释 —— 注释里提到某个模式不该让断言误判（也不该让它假绿）。"""
    out = []
    i, n, in_block = 0, len(src), False
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
            i = n if j < 0 else j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


def body(src, header):
    """截出某个方法的正文（方法在同一文件里都按 4 空格缩进，内层块更深）"""
    i = src.find(header)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(header))
    return src[i:j + 5] if j > 0 else src[i:]


def count_of(hay, needle):
    n, at = 0, 0
    while (at := hay.find(needle, at)) >= 0:
        n += 1
        at += len(needle)
    return n


clip_raw = read(os.path.join(SRC, 'ClipList.java'))
clip = code_only(clip_raw)
pane = code_only(read(os.path.join(SRC, 'ClipListPane.java')))
drag = code_only(read(os.path.join(SRC, 'DragSortPane.java')))
main = code_only(read(os.path.join(SRC, 'MainActivity.java')))
amc = code_only(read(os.path.join(SRC, 'ActionMenuConfig.java')))
feats = read(os.path.join(HERE, 'verify_features.py'))
alltests = read(os.path.join(HERE, 'run_all_tests.bat'))

print('=== A. 纯类 ClipList（搜索 / 排序 / 拖拽判据，零 Android 依赖）===')
check('纯类在，且是 final 工具类（无状态、纯静态）',
      'public final class ClipList' in clip and 'private ClipList() {}' in clip)
check('★ 零 Android 依赖（fpstest 编的就是打进 APK 的那份源码）',
      'import android' not in clip_raw and 'android.' not in clip)
check('★ 搜索是"分词 AND"（每个词都要命中，不是整串匹配）',
      'public static boolean matches(String id, String query)' in clip
      and 'for (String t : tk) if (n.indexOf(t) < 0) return false;' in clip)
check('★ 分隔符归一化（打 `点击回应3` 能命中 `点击回应-3`）',
      "static String norm(String s)" in clip
      and "ch == '-' || ch == '_' || ch == ' '" in clip)
check('排序模式 5 种 + 按钮循环切换',
      'public static final int SORT_COUNT = 5;' in clip
      and 'public static int nextSort(int mode)' in clip
      and 'public static String sortButtonText(int mode)' in clip)
check('★ 排序是**稳定**的（次级键 = 原下标，列表不会每次打开都换位置）',
      'Integer ia = order.get(a), ib = order.get(b);' in clip
      and 'return na - nb;' in clip)
check('★ 拖拽重排：`to` 是移动后的下标（remove + add(to)，不用调用方 ±1）',
      'public static List<String> move(List<String> ids, int from, int to)' in clip
      and 'String v = out.remove(from);' in clip and 'out.add(t, v);' in clip)
check('★ 落点判定按每行中线（y <= 中线算那一行 ⇒ 原地按一下不会被当成挪一格）',
      'public static int dropIndex(int[] rowMids, int y)' in clip
      and 'if (y <= rowMids[i]) return i;' in clip)
check('行文案 / 计数 / 勾选按原始顺序取（文件内容不随当时怎么排序而变）',
      'public static String rowLabel(String id, int boundCount)' in clip
      and 'public static String onlyPickedText(int picked, int total)' in clip
      and 'public static String countText(int total, int shown, String query)' in clip
      and 'public static List<String> pickedOf(List<String> ids, List<String> picked)' in clip)
check('★ 计数行区分三种情形（搜索命中 / 只看已选 / 全量）—— 否则用户以为动作丢了',
      'if (!q.isEmpty()) return "命中 "' in clip
      and 'if (shown != total) return "显示 "' in clip
      and '只看已选）";' in clip)

print()
print('=== B. ClipListPane：搜索框 + 排序 + 只看已选 ===')
check('搜索框在（提示文案是设备脚本要找的那句）',
      '搜索动作名' in pane and 'search.setHint(' in pane and 'search.addTextChangedListener(' in pane)
check('排序按钮点一下就换下一种（不弹二级对话框）',
      'sortBtn.setText(ClipList.sortButtonText(sortMode));' in pane
      and 'sortMode = ClipList.nextSort(sortMode);' in pane)
check('★ 「只看已选」只在多选里出现（单选没有"选"这回事）',
      'onlyPickedCb.setText(ClipList.onlyPickedText(picked.size(), all.size()));' in pane
      and 'if (multi) {' in pane and 'if (onlyPicked && multi) {' in pane)
check('计数行告诉用户"命中多少"（否则以为动作少了）',
      'countTv.setText(ClipList.countText(all.size(), shown.size(), query));' in pane)
check('过滤 + 排序都走纯类（判据只有一份，不在 UI 里重写一遍）',
      'List<String> filtered = ClipList.filter(all, query);' in pane
      and 'shown.addAll(ClipList.sorted(filtered, counts, sortMode));' in pane)
check('★ 列表高度有界（对话框里 ListView 用 wrap_content 会 0 高或撑破屏）',
      'heightPixels * 0.62' in pane and '520 * density' in pane)
check('单选回调 / 多选回调都接上了，勾选状态存在 pane 里（行会被回收，不能挂在行上）',
      'listener.onPick(cid);' in pane and 'listener.onToggle(cid, picked.contains(cid));' in pane
      and 'private final Set<String> picked = new LinkedHashSet<String>();' in pane)
check('保存按**原始顺序**取勾选（`pickedIds()` 走 ClipList.pickedOf）',
      'List<String> pickedIds() {' in pane
      and 'return ClipList.pickedOf(all, new ArrayList<String>(picked));' in pane)
check('编辑已有绑定时能预设勾选 / 权重',
      'void setPicked(List<String> ids)' in pane and 'void setWeights(List<Integer> w)' in pane)

print()
print('=== C. MainActivity：三处都换了，老实现彻底退场 ===')
check('★ 动作库：单选 ClipListPane + 点一行先关掉自己再预览（setItems 才会自动关）',
      'ClipListPane pane = new ClipListPane(this, uiT(), all, counts, false,' in main
      and 'previewClip(as, null, id);' in main and 'ref[0].dismiss();' in main)
check('★ 场景改绑：多选 ClipListPane（原来是 setMultiChoiceItems 的纯滚动）',
      'final ClipListPane pane = new ClipListPane(this, uiT(), all, counts, true, null);' in main
      and 'pane.setPicked(bound);' in main and 'pane.pickedIds()' in main)
check('★ 自定义分类：多选 ClipListPane + 拖动排序入口',
      'pane.setPicked(cur);' in main and '拖动排序' in main)
check('★ 三处动作列表 + 拖拽对话框都走 setView（自定义视图），不再用平台的 setItems 列表',
      '.setView(pane.view())' in body(main, 'private void showClipLibrary(final ActionSystem as) {')
      and '.setView(pane.view())' in body(main, 'private void editSceneBindings(')
      and '.setView(pane.view())' in body(main, 'private void editMenuCategory(')
      and count_of(main, '.setView(pane.view())') == 4,
      'setView(pane.view()) 出现 %d 次（3 个动作列表 + 1 个拖拽对话框）'
      % count_of(main, '.setView(pane.view())'))
check('★ 全工程不再有 setMultiChoiceItems（多选列表只此一家）',
      'setMultiChoiceItems(' not in main and 'setMultiChoiceItems(' not in pane)
check('★ 链级铁律仍然守住：setView 的对话框没有 setMessage（平台只画 message，会把内容吞掉）',
      'setMessage' not in body(main, 'private void showClipLibrary(final ActionSystem as) {')
      and 'setMessage' not in body(main, 'private void editSceneBindings(')
      and 'setMessage' not in body(main, 'private void editMenuCategory('))
check('保存后立刻 reloadPet（悬浮窗当场生效），改绑仍然保留已调好的权重',
      'Prefs.reloadPet(MainActivity.this);' in body(main, 'private void editSceneBindings(')
      and 'int old = as.weightOf(scene, id);' in body(main, 'private void editSceneBindings('))

print()
print('=== D. DragSortPane：拖拽真的能用、顺序真的落盘 ===')
check('拖拽列表视图在（ScrollView + LinearLayout，行不回收）',
      'final class DragSortPane' in drag and 'scroll = new ScrollView(ctx);' in drag
      and 'column.setOrientation(LinearLayout.VERTICAL);' in drag)
check('★ 挡住 ScrollView 的拦截（否则手势会被它拿走，只会收到 ACTION_CANCEL）',
      'scroll.requestDisallowInterceptTouchEvent(true);' in drag
      and 'scroll.requestDisallowInterceptTouchEvent(false);' in drag)
check('★ 拖动中只改 translationY（不重排行视图 ⇒ 正在接收触摸的 View 不会被回收/摘掉）',
      'dragRow.setTranslationY(rawY - downRawY);' in drag
      and 'dragRow.setAlpha(0.9f);' in drag)
check('落点与重排都走纯类（判据只有一份）',
      'int to = ClipList.dropIndex(rowMids(), y);' in drag
      and 'List<String> now = ClipList.move(items, dragFrom, dragTo);' in drag)
check('★ 收尾有"已经收过尾"的守卫（UP 之后还会再来一个 CANCEL，否则重排两次）',
      'if (dragRow == null && dragFrom < 0) return;' in drag)
check('★ 重建行 post 到下一帧（在触摸回调里 removeAllViews 会崩在 ViewGroup.dispatchDraw）',
      'column.post(new Runnable() {' in drag and 'rebuildRows();' in drag
      and 'column.removeAllViews();' in drag)
check('拖到边缘自动滚（一百来个动作才拖得到最后一行）',
      'private void autoScroll(float rawY)' in drag and 'scroll.scrollBy(0, -step);' in drag)
check('★ 内容没占满一屏时**不滚**（否则整列上移、落点漂一格：拖到第一行却停在第 2 行）',
      'if (column.getHeight() <= scroll.getHeight()) return;' in drag)
check('手柄带 contentDescription（设备脚本按它找行）',
      'handle.setContentDescription("drag_handle");' in drag)
check('★ 顺序真的落盘：分类内动作顺序 = 三击菜单顺序（setClips 保序写入）',
      'for (String n : clips) if (n != null && !n.isEmpty()) cs.put(n);' in amc
      and 'ActionMenuConfig.setClips(MainActivity.this, cid, label, order);' in main)
check('★ 分类顺序也能调（自定义分类排在三击菜单最前，顺序就是文件顺序）',
      'public static synchronized void reorderCategories(Context ctx, String charId, List<String> labels)' in amc
      and 'root.put("categories", out);' in amc
      and 'ActionMenuConfig.reorderCategories(MainActivity.this, cid, order);' in main
      and '调整分类顺序（拖动）' in main)
check('拖拽对话框只有一个入口（保存 / 取消），两处调用点',
      'private void showDragSortDialog(String title, java.util.List<String> items,' in main
      and count_of(main, 'showDragSortDialog("') == 2,
      '调用点 %d 处' % count_of(main, 'showDragSortDialog("'))

print()
print('=== E. ★ 诚实边界：场景绑定顺序不给拖拽（那里顺序不影响播放）===')
check('★ 改绑页里没有 DragSortPane —— 播放是 Group.pick 按权重**随机**抽，顺序不参与',
      'DragSortPane' not in body(main, 'private void editSceneBindings(')
      and 'DragSortPane' not in body(main, 'private void showClipLibrary('))
check('随机抽取这条事实本身没被改（拖拽不是靠"顺序播放"实现的）',
      'int t = r.nextInt(Math.max(1, totalWeight));'
      in code_only(read(os.path.join(SRC, 'PetAnimPack.java'))))

print()
print('=== F. fpstest [29] + 登记进全量套件 ===')
rt = subprocess.run(['cmd', '/c', 'run.bat'], cwd=os.path.join(HERE, 'fpstest'),
                    capture_output=True)
out = rt.stdout.decode('utf-8', 'replace')
check('★ fpstest 全绿且含 [29] ClipList（搜索/排序/拖拽的语义断言）',
      rt.returncode == 0 and '[29]' in out, '退出码=%d' % rt.returncode)
check('fpstest 编译了纯类 ClipList（否则 [29] 根本编不过）',
      'ClipList.java' in read(os.path.join(HERE, 'fpstest', 'run.bat')))
check('全量套件登记了正反两套',
      'verify_clip_list.py' in alltests and 'reverse_check_clip_list.py' in alltests)
check('verify_features 里有本轮的独有探针（否则"正式版落后"又会藏起来）',
      'ClipListPane' in feats and 'DragSortPane' in feats and '只看已选' in feats)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（动作列表：搜索 / 排序 / 拖拽）')
