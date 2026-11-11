# -*- coding: utf-8 -*-
"""设备验证：**动作列表的搜索 / 排序 / 拖拽**在模拟器上真的能用（轮次㉚）。

★ 轮次㉞：按轮次㉛ 的**新 UI** 补齐（轮次㉛ 的欠账清单当时只列了两份，漏了这一份）。
  旧版读的是 `menus/builtin_default.json`，而轮次㉛ 起**不再预置任何角色**、
  自定义动作菜单也改成**按角色**存（`ActionMenuConfig.fileFor(ctx, charId)`
  → `menus/<当前角色 id>.json`）⇒ 旧路径恒为空，第四节/第五节那三条
  "顺序真的落盘"的断言**必红**（**脚本过时，不是产品 bug** —— 实测就是这三条红，
  其余全绿）。现在菜单 JSON 跟着 `V.reset_state()` 建出来的角色走（`V.CHAR_ID`）。

能验什么 / 不能验什么（说清楚，别假装）：
  ✅ 动作库：搜索框真的筛（命中 N / M + 行真的少了）、排序按钮真的换序（名称 ↑/↓ 的行顺序变了）；
  ✅ 改绑多选：「只看已选」真的把没勾的筛掉（计数行改口成"显示 N / M"）；
  ✅ 拖拽：自定义动作菜单里把最后一个动作拖到第一行 → **落盘的 JSON 顺序真的变了**
     （这个顺序就是三击桌宠菜单里的显示顺序，所以是功能而不是装饰）；
  ✅ 分类顺序同理（分类拖一下，JSON 里 categories 的顺序变了）。
  ❌ 不能验：一百多个真实动作下的滚动手感（模拟器上只有内置 1 个 + 本脚本造的 3 个）；
     真实角色包的搜索命中率（那要用户手机上的角色包）。

用法：
    python build/verify_clip_list_ui.py --selftest   # 不连设备，只验本脚本的判读逻辑
    python build/verify_clip_list_ui.py --install    # 装 dist 里的开发版再跑
"""
import json
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_char_ui as V          # noqa: E402  设备驱动（dump / nodes / tap / swipe / 导航）
import pf                           # noqa: E402  改 pref（带 owner/mode 还原）

PKG = V.PKG
MENUS_DIR = '/data/data/%s/files/menus' % PKG
# ★ 轮次㉛ 起自定义动作菜单**按角色**存（`ActionMenuConfig.fileFor(ctx, charId)`）：
#   `V.reset_state()` 会先清空角色目录、再建一个 `V.CHAR_ID`（= uitest）的角色并切过去，
#   所以菜单 JSON 就是 `menus/uitest.json` —— 不再是 `builtin_default.json`
#   （那个名字是"内置角色"时代的哨兵 id；轮次㉛ 起没有内置角色，那个文件永远不会出现）。
MENU_JSON = MENUS_DIR + '/%s.json' % V.CHAR_ID

fails = []
skips = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


# ─────────────────────── 判读（可离线自测） ───────────────────────

CHROME = ('排序：', '共 ', '命中 ', '显示 ', '只看已选', '搜索动作名', '拖动', '按住左侧')

# 「形象与动作」页上动作系统那一段的几行 —— 任一行在屏上就说明我们在这一页
# ★ 轮次㉛ 起那一行的文案从「当前角色：」变成「角色：」（入口并成了角色中心）。
ACTION_ROWS = ('场景与已绑动作', '动作库（查看', '添加动作（从相册多选）',
               '自定义动作菜单', '角色：')


def row_texts(x=None):
    """当前动作列表里的**行**（按 y 从上到下）。

    ★ 判据要排掉对话框自己的零件（搜索框/排序按钮/计数行/标题/提示）——
      它们也是 TextView，混进来会让"第几行是哪个动作"全错。
    """
    items = []
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
        if not t or c == 'EditText':
            continue
        if any(t.startswith(k) or k in t for k in CHROME):
            continue
        if '动作库' in t or '选择动作' in t or '调整' in t:
            continue
        items.append((y0, t))
    items.sort()
    return [t for _, t in items]


def my_order(x=None):
    """行里属于本脚本造的那三个动作，按屏幕顺序返回（去掉「　（未绑定）」后缀）。"""
    out = []
    for t in row_texts(x):
        name = re.split(r'[\u3000\s]', t)[0]
        if name.startswith('myact_'):
            out.append(name)
    return out


def count_line(x=None):
    for t in V.texts(x):
        if t.startswith('命中 ') or t.startswith('显示 ') or t.startswith('共 '):
            return t
    return ''


def menu_clips(raw, label):
    """自定义菜单 JSON 里某个分类的动作顺序（读不出来返回 None）。"""
    try:
        cats = json.loads(raw).get('categories', [])
    except Exception:
        return None
    for c in cats:
        if c.get('label') == label:
            return list(c.get('clips', []))
    return None


def menu_labels(raw):
    try:
        return [c.get('label') for c in json.loads(raw).get('categories', [])]
    except Exception:
        return []


def selftest():
    print('== --selftest：本脚本的判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    sample = (
        '<node class="android.widget.TextView" text="排序：原序" bounds="[42,700][400,780]" />'
        '<node class="android.widget.TextView" text="共 4 个动作" bounds="[42,800][400,860]" />'
        '<node class="android.widget.EditText" text="" bounds="[42,600][1000,690]" />'
        '<node class="android.widget.TextView" text="myact_c\u3000（未绑定）" bounds="[70,1000][900,1090]" />'
        '<node class="android.widget.TextView" text="myact_a\u3000（未绑定）" bounds="[70,900][900,990]" />'
        '<node class="android.widget.TextView" text="待机\u3000（未绑定）" bounds="[70,1100][900,1190]" />')
    t('行判据只留动作行（排序按钮/计数行/搜索框都被排掉）',
      row_texts(sample) == ['myact_a\u3000（未绑定）', 'myact_c\u3000（未绑定）',
                            '待机\u3000（未绑定）'])
    t('★ 行顺序按 y 排（不是按 dump 里的出现顺序）',
      my_order(sample) == ['myact_a', 'myact_c'])
    t('计数行读得出（命中 / 显示 / 共 三种）',
      count_line(sample) == '共 4 个动作'
      and count_line(sample.replace('共 4 个动作', '命中 1 / 4 个（搜索：myact_a）'))
      == '命中 1 / 4 个（搜索：myact_a）')
    raw = ('{"version":1,"categories":[{"label":"uitest","clips":["myact_c","myact_a"]},'
           '{"label":"uitest2","clips":[]}]}')
    t('菜单 JSON：分类内顺序读得出（拖拽的判据就是它）',
      menu_clips(raw, 'uitest') == ['myact_c', 'myact_a'])
    t('菜单 JSON：分类顺序读得出（分类拖拽的判据）',
      menu_labels(raw) == ['uitest', 'uitest2'])
    t('菜单 JSON 反例：顺序不同就不算通过',
      menu_clips(raw, 'uitest') != ['myact_a', 'myact_c'])
    t('读不出来返回 None（不抛异常）', menu_clips('不是 json', 'uitest') is None)
    t('★ 只认模拟器（不许往用户真机装包/改文件）', V.DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ─────────────────────── 设备操作 ───────────────────────

def dialog_open(x=None):
    """有没有对话框盖在上面。

    ★ 判据用**只在对话框里出现的零件**（排序按钮 / 只看已选 / 拖动提示 / 新建分类），
      不要用"屏幕上有没有某个标题" —— uiautomator 会把被对话框盖住的页面也 dump 出来，
      于是"对话框还在"与"已经在设置页"看起来一模一样（轮次㉚ 实测踩到：
      上一节的对话框没关掉，下一节点"设置页那一行"其实是点在对话框上）。
    """
    x = x if x is not None else V.dump()
    return any(V.has_text(k, x) for k in
               ('排序：', '只看已选', '按住左侧', '＋ 新建分类', '拖动排序'))


def close_dialogs(max_tries=3):
    """把叠着的对话框都关掉（BACK 关最上面那个）。段落之间必调。"""
    for _ in range(max_tries):
        if not dialog_open():
            return True
        V.sh('shell', 'input', 'keyevent', '4')
        time.sleep(1.2)
    return not dialog_open()


def row_visible(needle, x=None):
    """这一行是否**完整**在屏内。

    ★★ 被裁掉的行 bounds 是**反的**（`y0 > y1`，实测 `添加动作（从相册多选）`
      是 `(216, 2328, 943, 2143)`）—— 这时按它算出来的 y 会落到**别的控件**上：
      本脚本第一版就是这样点到了底部导航「形象」，表现为"点了没反应 / 相册没打开"。
      （轮次㉓ 的设备脚本也踩过同一个坑，这里按那条教训判。）
    """
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
        if t and needle in t:
            return y1 > y0 and y1 <= 2140        # 2140 以上才是内容区（底栏从 2159 起）
    return False


def scroll_to_row(needle, tries=10):
    """滚到某一行完整可见为止（边滚边查；无条件滚会把目标滚过头）。

    ★ 滑动要**慢**（700ms）：250ms 的短促 swipe 会被 ScrollView 当成 fling，
      实际滚动距离每次都不一样（实测 400px 的一步能滚出 900px），
      "再滚一点就到"的窗口会被一跳而过。
    """
    for _ in range(tries):
        if row_visible(needle):
            return True
        V.swipe(540, 1500, 540, 1100, 700)
        time.sleep(0.6)
    return row_visible(needle)


def tap_settings_row(needle):
    if not scroll_to_row(needle):
        return False
    return V.tap_row(needle)


def hide_ime():
    """收起输入法（确认它真的弹着才发 BACK）。

    ★★ 为什么必须收（本脚本第一版实测翻车）：
      搜索框还聚焦时**软键盘盖住了对话框底部的按钮** —— 点「关闭 / 保存 / 拖动排序」
      其实是点在键盘上（对话框根本没关）；更糟的是后面 `scroll_to_row` 的滑动
      被 Gboard 当成**手势输入**，往搜索框里打进了 `by by by by by by by by`，
      于是"关闭对话框"这一步静默失败，第四节整段跟着崩。
      BACK 会**先**被输入法吃掉（对话框不受影响），所以用它收键盘是安全的。
    """
    if 'mInputShown=true' in V.out('shell', 'dumpsys', 'input_method'):
        V.sh('shell', 'input', 'keyevent', '4')
        time.sleep(1.0)


def type_search(text):
    """把查询词打进对话框里的搜索框（先清空，打完收键盘）。"""
    x = V.dump()
    target = None
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
        if c == 'EditText':
            target = ((x0 + x1) // 2, (y0 + y1) // 2)
            break
    if target is None:
        return False
    V.tap(*target)
    time.sleep(0.8)
    for _ in range(30):
        V.sh('shell', 'input', 'keyevent', '67')      # DEL
    if text:
        V.sh('shell', 'input', 'text', text)
    time.sleep(1.2)
    hide_ime()
    return True


def _swipe_handle(from_id, to_id):
    """按住 from 行的 ≡ 手柄，拖到 to 行的**上沿**。

    ★ 拖到"上沿"而不是"中线"：落点判定是 `y <= 该行中线算这一行`，
      拖到中线附近时插值误差正好会差一格；拖到上沿两个方向都不会歧义。
    """
    x = V.dump()
    y_from = y_to = None
    handles = []
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
        if t == from_id:
            y_from = (y0 + y1) // 2
        if t == to_id:
            y_to = y0 + 8
        if d == 'drag_handle':
            handles.append(((x0 + x1) // 2, (y0 + y1) // 2, y0, y1))
    if y_from is None or y_to is None or not handles:
        return False
    hx, hy = handles[0][0], handles[0][1]
    for cx, cy, y0, y1 in handles:
        if y0 <= y_from <= y1:          # 与 from 行同一行的那个手柄
            hx, hy = cx, cy
            break
    V.swipe(hx, hy, hx, y_to, 1400)
    time.sleep(1.5)
    return True


def drag_row(from_id, to_id, tries=3, expect_first=None):
    """拖到**屏幕上的顺序真的变了**为止（可选：要求它落到第一行）。

    ★ 为什么要重试（轮次㉚ 实测）：对话框刚弹出时布局/窗口还在入场动画里，
      这时算出来的手柄坐标可能落在行外，第一把会**静默落空**
      （`input swipe` 照样"成功"，只是没碰到手柄）——
      表现成"拖了、也保存了，但顺序没变"。
    """
    before = row_texts()
    for _ in range(tries):
        if not _swipe_handle(from_id, to_id):
            return False
        now = row_texts()
        if now != before and (expect_first is None or my_order()[:1] == [expect_first]):
            return True
        time.sleep(0.8)
    return row_texts() != before


def open_drag_dialog(item_needle='拖动排序'):
    """打开「拖动排序」对话框并等它**落定**（入场动画没走完就别去抓手柄）。"""
    V.tap_dialog_item(item_needle)
    if not V.wait_text('按住左侧', 10):
        return False
    time.sleep(1.5)
    return True


def read_menu_json():
    return V.out('shell', 'cat', MENU_JSON).strip()


def ensure_action_system():
    """保证现在就在「形象与动作」这一页的**动作系统那一段**。

    ★ 三种情况都要认（本脚本先后在这三种上各栽过一次）：
      ① 已经在动作系统那一段 ⇒ 直接干；
      ② 在板块**首页**（有「形象与动作」这一行）⇒ 走正常导航；
      ③ 已经在二级页、但滚在别处（App 会**记住上次所在的二级页**，
         `reset_state` 重启后直接落在这里，而这时点底栏「形象」是空操作
         —— `switchTab` 对"已在同一板块且不在根页"有守卫）⇒ 往下滚去找。
    ★ 判据要认**好几行**：uiautomator 只 dump 屏上渲染出来的东西，
      滚到哪儿决定了哪几行"存在"。
    """
    x = V.dump()
    if any(V.has_text(k, x) for k in ACTION_ROWS):
        return True
    if V.has_text('形象与动作', x):
        return V.goto_action_system()
    for k in ('场景与已绑动作', '动作库（查看', '自定义动作菜单'):
        if scroll_to_row(k):
            return True
    return V.goto_action_system()


def open_library():
    """设置页 → 形象与动作 → 动作库。"""
    tap_settings_row('动作库（查看 / 预览全部动作）')
    return V.wait_text('个动作', 10) is not None


def open_menu_editor():
    tap_settings_row('自定义动作菜单')
    return V.wait_text('自定义动作菜单（', 10) is not None


def new_category(name):
    """在菜单编辑器里新建一个分类 → 落在该分类的挑动作对话框上。"""
    if not V.tap_dialog_item('新建分类'):
        return False
    time.sleep(1.2)
    x = V.dump()
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
        if c == 'EditText':
            V.tap((x0 + x1) // 2, (y0 + y1) // 2)
            break
    for _ in range(30):
        V.sh('shell', 'input', 'keyevent', '67')
    V.sh('shell', 'input', 'text', name)
    time.sleep(1)
    hide_ime()                      # ★ 不收键盘就会点到键盘上（见 hide_ime 的说明）
    V.tap_text_exact('创建')
    return V.wait_text('只看已选', 10) is not None


def add_three_actions():
    """造 3 个 ASCII 名字的动作（`adb input text` 只支持 ASCII ⇒ 中文搜不了）。"""
    names = V.make_media()
    V.push_media(names)
    if not tap_settings_row('添加动作（从相册多选）'):
        return 'no-row'
    time.sleep(3)                       # 第一次冷启 DocumentsUI 会慢一拍
    r = V.picker_select('myact_', ['myact_a.png', 'myact_b.jpg', 'myact_c.gif'])
    if r != 'ok':
        return 'picker:' + r
    if not V.wait_text('把 3 个动作加到哪', 12):
        return 'no-dialog'
    V.tap_dialog_item('只加入动作库')
    time.sleep(4)
    return 'ok'


# ─────────────────────── 主流程 ───────────────────────

def main():
    if '--selftest' in sys.argv:
        return selftest()
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
    if 'root' not in V.out('shell', 'id'):
        print('== adb root ==')
        V.sh('root')
        time.sleep(3)
        if 'root' not in V.out('shell', 'id'):
            print('!! 拿不到 root，读不了 menus JSON（拒绝假装通过）')
            return 1

    V.sh('shell', 'rm', '-rf', MENUS_DIR)
    V.reset_state()
    if not ensure_action_system():
        print('!! 进不了「形象与动作 → 动作系统」，后面的检查无从谈起')
        return 1

    print()
    print('== 一、准备：造 3 个动作（只进动作库，不绑场景）==')
    r = add_three_actions()
    check('相册多选 3 张 → 只加入动作库（%s）' % r, r == 'ok')
    if r != 'ok':
        return 1
    ensure_action_system()

    print()
    print('== 二、动作库：搜索真的筛（不是只改了文案）==')
    if not open_library():
        check('打开动作库', False)
        return 1
    x = V.dump()
    check('动作库显示全部动作（共 4 个：内置 1 + 造的 3）',
          count_line(x) == '共 4 个动作', count_line(x))
    check('★ 排序按钮在（轮次㉚ 新增）',
          any(t.startswith('排序：') for t in V.texts(x)),
          ' / '.join(t for t in V.texts(x) if t.startswith('排序')))
    check('列表里有刚造的三个动作', sorted(my_order(x)) == ['myact_a', 'myact_b', 'myact_c'],
          my_order(x))

    if not type_search('myact'):
        check('搜索框可输入', False)
    else:
        x = V.dump()
        check('★ 搜 `myact` ⇒ 命中 3 / 4（计数行）',
              count_line(x).startswith('命中 3 / 4'), count_line(x))
        check('★ 行真的只剩这 3 个（不是只改了计数）',
              sorted(my_order(x)) == ['myact_a', 'myact_b', 'myact_c'], my_order(x))
    if type_search('myact_a'):
        x = V.dump()
        check('★ 搜 `myact_a` ⇒ 命中 1 / 4，且行只剩 myact_a',
              count_line(x).startswith('命中 1 / 4') and my_order(x) == ['myact_a'],
              '%s / %s' % (count_line(x), my_order(x)))
    if type_search('zzzz'):
        x = V.dump()
        check('★ 搜不存在的词 ⇒ 命中 0 / 4 且一行都没有（不假装搜到）',
              count_line(x).startswith('命中 0 / 4') and my_order(x) == [],
              '%s / %s' % (count_line(x), my_order(x)))

    print()
    print('== 三、动作库：排序真的换序（名称 ↑ / ↓ 的行顺序）==')
    type_search('')
    x = V.dump()
    before = my_order(x)
    check('清空搜索后回到全部（共 4 个动作）', count_line(x) == '共 4 个动作', count_line(x))
    V.tap_dialog_item('排序：')
    time.sleep(1.2)
    x = V.dump()
    asc = my_order(x)
    check('★ 点一下排序 ⇒ 变成「名称 ↑」', any(t.startswith('排序：名称 ↑') for t in V.texts(x)),
          ' / '.join(t for t in V.texts(x) if t.startswith('排序')))
    check('★ 名称 ↑ 后三个动作是按名字升序（myact_a < myact_b < myact_c）',
          asc == ['myact_a', 'myact_b', 'myact_c'], '%s（之前 %s）' % (asc, before))
    V.tap_dialog_item('排序：')
    time.sleep(1.2)
    x = V.dump()
    check('★ 再点一下 ⇒ 名称 ↓，顺序真的反过来',
          any(t.startswith('排序：名称 ↓') for t in V.texts(x))
          and my_order(x) == ['myact_c', 'myact_b', 'myact_a'],
          ' / '.join(t for t in V.texts(x) if t.startswith('排序')) + ' :: ' + str(my_order(x)))
    V.tap_text_exact('关闭')
    time.sleep(1)
    check('动作库对话框已关掉（不然下一节会点在它上面）', close_dialogs())

    print()
    print('== 四、拖拽：自定义动作菜单的顺序真的落盘 ==')
    close_dialogs()
    ensure_action_system()
    if not open_menu_editor():
        check('打开自定义动作菜单', False)
        return 1
    if not new_category('uitest'):
        check('新建分类 uitest', False)
        return 1
    type_search('myact')
    x = V.dump()
    check('分类里搜索同样生效（命中 3 / 4）', count_line(x).startswith('命中 3 / 4'), count_line(x))
    for a in ('myact_a', 'myact_b', 'myact_c'):
        V.tap_dialog_item(a)
        time.sleep(0.6)
    x = V.dump()
    check('★ 勾选状态可见（只看已选 3 / 4）',
          any(t.startswith('只看已选（3 / 4') for t in V.texts(x)),
          ' / '.join(t for t in V.texts(x) if '只看已选' in t))
    if not open_drag_dialog():
        check('打开「拖动排序」对话框', False)
        return 1
    check('拖拽对话框列出了勾选的动作', sorted(my_order(V.dump())) == ['myact_a', 'myact_b', 'myact_c'],
          my_order(V.dump()))
    if not drag_row('myact_c', 'myact_a', expect_first='myact_c'):
        check('找到 ≡ 手柄并拖动', False)
    else:
        x = V.dump()
        check('★ 拖完屏幕上的顺序真的变了（myact_c 到了最上面）',
              my_order(x)[:1] == ['myact_c'], my_order(x))
        V.tap_text_exact('保存')
        time.sleep(2)
        raw = read_menu_json()
        check('★ 顺序真的落盘（JSON：myact_c 在第一个）',
              menu_clips(raw, 'uitest') == ['myact_c', 'myact_a', 'myact_b'],
              'JSON: %s' % (menu_clips(raw, 'uitest'),))

    print()
    print('== 五、拖拽：分类顺序也能调 ==')
    close_dialogs()
    # ★ 第四节保存后，代码自己回到了分类列表（editMenuCategory 的拖拽回调里
    #   调了 showMenuCategoriesEditor）—— 这时**别再点一次入口**：
    #   对话框是模态的，点入口只会点到遮罩上把对话框关掉。
    if not V.has_text('自定义动作菜单（'):
        open_menu_editor()
    if not new_category('uitest2'):
        skip('分类顺序', '第二个分类没建出来')
    else:
        V.tap_text_exact('取消')
        time.sleep(1.5)
        open_menu_editor()
        check('两个分类都在', sorted(menu_labels(read_menu_json())) == ['uitest', 'uitest2'],
              menu_labels(read_menu_json()))
        V.tap_dialog_item('调整分类顺序')
        if not V.wait_text('按住左侧', 10):
            check('打开「调整分类顺序」', False)
        else:
            time.sleep(1.5)                 # 等入场动画落定（见 drag_row 的说明）
            if not drag_row('uitest2', 'uitest', expect_first='uitest2'):
                check('找到分类行的 ≡ 手柄并拖动', False)
            else:
                V.tap_text_exact('保存')
                time.sleep(2)
                check('★ 分类顺序落盘（uitest2 在前）',
                      menu_labels(read_menu_json())[:2] == ['uitest2', 'uitest'],
                      menu_labels(read_menu_json()))

    print()
    print('== 六、收工：把测试状态清掉 ==')
    V.sh('shell', 'rm', '-rf', MENUS_DIR)
    V.reset_state()
    check('测试用的分类配置已清掉', read_menu_json() == '', read_menu_json()[:60])
    # ★ 没碰 pref，但仍按 pf 的规矩擦掉本地副本（交付的源码包里会打进整个 build/）
    pf.wipe_local()

    print()
    print('=' * 62)
    if skips:
        print('跳过 %d 项（跳过按未验证计）：%s' % (len(skips), ' / '.join(skips)))
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    if skips:
        return 1
    print('ALL DEVICE CHECKS PASSED（动作列表：搜索 / 排序 / 拖拽，在 %s 上）' % V.DEV)
    return 0


if __name__ == '__main__':
    sys.exit(main())
