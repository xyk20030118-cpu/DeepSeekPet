# -*- coding: utf-8 -*-
"""设备验证：P1「场景动作可编辑」+ 轮次㉑「App 内添加动作」在**真机/模拟器**上真的可用。

为什么要这个脚本（STATE.md 待办 **0k**）：
    P1 与轮次㉑ 两轮的 UI **一次都没在设备上跑过** —— 断言再多也只是源码级。
    这里把两条路走成可复跑的脚本，**只看模拟器**（用户真机不装包）。

覆盖（全部用"页面文字 + 落盘文件 + logcat"三处交叉验证）：
    ① 设置页动作系统四行齐全（当前角色 / 场景与已绑动作 / 动作库 / 添加动作 / 全部恢复默认）
    ② 「➕ 添加动作」：相册多选 3 张（PNG + JPG + GIF）→ 选场景「点击」
       → 文件落进 `filesDir/scenes/<角色 id>/clips/`（**不是**角色包目录）
       → 覆盖层 JSON 里 `scenes.click` = 三个动作名（**追加**语义）
       → 日志 `DSPChar: 添加动作 …` ×3、`DSPBind: 添加动作已绑场景=click 新增=3`
    ③ 总览回显：「已自定义 1 个」+「● 点击：3 个（已自定义）」
    ④ 场景操作面：「已绑 3 个，已自定义」+ 五项（第 0 项 = 📥 导入动画到此场景）
    ⑤ 编辑绑定（多选）：勾选状态 = 刚加的三个（CheckedTextView 的 checked 属性）
    ⑥ ▶️ 预览：PNG 显示「静帧」、GIF 显示「动图循环」（0j 的 D 在设备上的样子）
    ⑦ 📏 设置权重：选 ×3 → **JSON 真的是 `{"id":"myact_a","weight":3}`**（P1 里最容易假功能的一条）
    ⑧ 场景内的 📥 导入动画到此场景：多选 1 张 → 追加绑到**这个**场景（权重保留）
    ⑨ 「恢复角色包默认」：**先弹确认框**（脚本必须点「恢复」）→ 覆盖里那条被删掉、文件保留

★ 踩过的两个坑（脚本层面的，不是产品的）：
    1. **非 BMP emoji 在 uiautomator 的 XML 里是数字实体**（`📥` → `&#128229;`），
       判据一律只用中文部分（`导入动画到此场景`），别拿 emoji 去比。
    2. **「恢复角色包默认 / 全部恢复」都会先弹确认框**：只点列表项不点「恢复」= 什么都没发生，
       而"JSON 里没有我预期的三个"这种弱断言照样会通过 —— 弱断言比没有断言更危险。

用法：
    python build/verify_char_ui.py --install     # 装 dist 里的开发版再跑
    python build/verify_char_ui.py               # 设备上已有包，直接跑
    python build/verify_char_ui.py --selftest    # 不连设备，只验本脚本的判读逻辑

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import json
import os
from artifacts import dev_apk
import re
import subprocess
import sys
import time

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
DEV = 'emulator-5554'
PKG = 'com.deepseek.pet'
DEV_APK = dev_apk()
MEDIA = r'D:\petport_lab\petbuild\build\_uitest\media'
# ★ 轮次㉛：一个预置角色都没有了（用户要求「不留任何角色，只靠导入」）⇒
#   这个脚本先自己建一个角色（见 create_character），覆盖层随之落在 scenes/<这个 id>.json。
CHAR_ID = 'uitest'
CLIPS_DIR = '/data/data/%s/files/scenes/%s/clips' % (PKG, CHAR_ID)
SCENE_JSON = '/data/data/%s/files/scenes/%s.json' % (PKG, CHAR_ID)

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

fails = []
skips = []
log = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    log.append((name, bool(ok), detail))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


def sh(*a, **kw):
    """所有 adb 调用都钉死模拟器 —— 用户真机就接在这台机器上。"""
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True, **kw)


def out(*a):
    return sh(*a).stdout.decode('utf-8', 'replace')


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/_d.xml')
    sh('pull', '/sdcard/_d.xml', r'D:\petport_lab\petbuild\build\_cu.xml')
    try:
        return open(r'D:\petport_lab\petbuild\build\_cu.xml', encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


def texts(x=None):
    x = x if x is not None else dump()
    return [t for t in dict.fromkeys(re.findall(r'text="([^"]{1,80})"', x)) if t]


def nodes(x=None):
    """(text, desc, cls, clickable, checked, x0,y0,x1,y1)"""
    for m in re.finditer(r'<node[^>]*?>', x if x is not None else dump()):
        s = m.group(0)

        def g(n):
            r = re.search(n + r'="([^"]*)"', s)
            return r.group(1) if r else ''
        b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', s)
        if not b:
            continue
        yield (g('text'), g('content-desc'), g('class').split('.')[-1],
               'clickable="true"' in s, g('checked'),
               int(b.group(1)), int(b.group(2)), int(b.group(3)), int(b.group(4)))


def tap(x, y):
    sh('shell', 'input', 'tap', str(x), str(y))


def swipe(x0, y0, x1, y1, ms=250):
    sh('shell', 'input', 'swipe', str(x0), str(y0), str(x1), str(y1), str(ms))


def long_press(x, y):
    sh('shell', 'input', 'swipe', str(x), str(y), str(x), str(y), '800')


def has_text(needle, x=None):
    return any(needle in t for t in texts(x))


def descs(x=None):
    return [d for _, d, _, _, _, _, _, _, _ in nodes(x) if d]


def wait_desc(needle, secs=12):
    t0 = time.time()
    while time.time() - t0 < secs:
        if any(needle == d for d in descs()):
            return True
        time.sleep(0.8)
    return False


def wait_text(needle, secs=12, x_pred=None):
    """轮询到某段文字出现（固定 sleep 会抢跑，见 STATE 第五节的四条）。"""
    t0 = time.time()
    while time.time() - t0 < secs:
        x = dump()
        if x_pred is None:
            if has_text(needle, x):
                return x
        elif any(x_pred(t) for t in texts(x)):
            return x
        time.sleep(0.8)
    return None


def tap_text_exact(text, pick_clickable=True):
    """按 **text 完全相等** 找节点并点它（取最内层可点祖先）。"""
    x = dump()
    target = None
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if t == text:
            target = (x0 + x1) // 2, (y0 + y1) // 2
            if ck and pick_clickable:
                break
    if target is None:
        return False
    tap(*target)
    return True


def tap_desc_contains(needle):
    x = dump()
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if d and needle in d:
            tap((x0 + x1) // 2, (y0 + y1) // 2)
            return True
    return False


def tap_row(text_needle):
    """点「文字 + ›」整行：先找到文字所在 y，再点该 y 上最内层可点容器。"""
    x = dump()
    ty = None
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if t and text_needle in t:
            ty = (y0 + y1) // 2
            break
    if ty is None:
        return False
    best = None
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if not ck or not (y0 <= ty <= y1):
            continue
        area = (x1 - x0) * (y1 - y0)
        if best is None or area < best[0]:
            best = (area, (x0 + x1) // 2, (y0 + y1) // 2)
    if not best:
        return False
    tap(best[1], best[2])
    return True


def tap_dialog_item(needle):
    """对话框 setItems 的一行：按文字（**包含**匹配，躲开非 BMP emoji 被 dump 转义）点中心。

    ★ uiautomator 的 XML 会把非 BMP 字符写成数字实体（`📥` → `&#128229;`），
      所以判据一律只用中文部分，不拿 emoji 去比。
    """
    x = dump()
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if t and needle in t and y0 > 150:
            tap((x0 + x1) // 2, (y0 + y1) // 2)
            return True
    return False


# ─────────────────────── 准备：测试图 / 清态 / 装机 ───────────────────────

def make_media():
    """生成 3 张测试图（PNG / JPG / GIF 各一）：静态图也要能当动作（0j 的 D）。"""
    from PIL import Image, ImageDraw
    os.makedirs(MEDIA, exist_ok=True)
    want = ['myact_a.png', 'myact_b.jpg', 'myact_c.gif']

    def frame(color, label):
        im = Image.new('RGBA', (240, 240), color)
        d = ImageDraw.Draw(im)
        d.ellipse([30, 30, 210, 210], fill=(255, 255, 255, 220))
        d.text((100, 110), label, fill=(0, 0, 0, 255))
        return im
    frame((240, 70, 70), 'A').save(os.path.join(MEDIA, 'myact_a.png'))
    frame((70, 140, 240), 'B').convert('RGB').save(os.path.join(MEDIA, 'myact_b.jpg'), quality=90)
    f1, f2 = frame((60, 200, 120), '1'), frame((230, 190, 60), '2')
    f1.save(os.path.join(MEDIA, 'myact_c.gif'), save_all=True, append_images=[f2],
            duration=250, loop=0)
    return want


def push_media(names):
    for n in names:
        sh('push', os.path.join(MEDIA, n), '/sdcard/Pictures/' + n)
        sh('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE',
           '-d', 'file:///sdcard/Pictures/' + n)
    time.sleep(2)


def reset_state():
    """清掉覆盖层 + 用户素材 + **导入过的角色 / 自定义菜单**，再重启 App —— 不用 pm clear（免得又弹 API Key 对话框）。

    ★ 为什么要连角色目录一起清（轮次㉚ 实测）：上一轮的设备脚本会留下
      `files/characters/p3test`，于是本节「没有为它造角色目录」那条检查**假红**
      （明明这一轮没造过任何角色）。设备脚本的规矩就是"能从全新安装态跑"。
    """
    sh('shell', 'rm', '-f', SCENE_JSON)
    sh('shell', 'rm', '-rf', CLIPS_DIR)
    sh('shell', 'rm', '-rf', '/data/data/%s/files/characters' % PKG)
    sh('shell', 'rm', '-rf', '/data/data/%s/files/menus' % PKG)
    sh('shell', 'rm', '-f', '/data/data/%s/files/_p3char.zip' % PKG)
    sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(3)
    # 全新安装态会弹「输入 API Key」（不可取消，点取消=退出）——按 STATE 的口径填假 Key
    x = dump()
    if has_text('输入', x) and has_text('API Key', x):
        for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
            if c == 'EditText':
                tap((x0 + x1) // 2, (y0 + y1) // 2)
                break
        for _ in range(40):
            sh('shell', 'input', 'keyevent', '67')       # DEL，清掉旧值
        sh('shell', 'input', 'text', 'sk-uitest-fake')
        tap_text_exact('保存并开始')
        time.sleep(3)
    # ★ 轮次㉛：一个预置角色都没有了（用户要求「不留任何角色，只靠导入」）⇒
    #   这一轮要用的角色得脚本自己建 —— 顺便把「新增角色」这条新路也验了。
    r = create_character('uitest')
    if r != 'ok':
        skip('新增一个测试角色', '创建失败：%s' % r)
    return r


def row_fully_visible(needle, x=None):
    """这一行是否**完整**在屏内。

    ★★ 被裁掉的行 bounds 是**反的**（`y0 > y1`）：实测「添加动作（从相册多选）」
      在动作系统那一段的底部时是 `(216, 2328, 943, 2143)`，于是 `tap_row` 按它算出来的
      y 落到了**底部导航**上 —— 表现成"点了没反应 / 相册没打开"。
      （轮次㉓ 记过这条教训，轮次㉚ 又踩了一次，所以这里显式判。）
    """
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if t and needle in t:
            return y1 > y0 and y1 <= 2140        # 2140 以上才是内容区（底栏从 2159 起）
    return False


def scroll_to_row(needle, down=True, tries=12):
    """把某一行滚到**完整可见**。

    ★ 滑动要**慢**（700ms）：250ms 的短促 swipe 会被 ScrollView 当成 fling，
      实际滚动距离每次都不一样（400px 的一步能滚出 900px），
      于是"再滚一点就到"的窗口被一跳而过 —— 轮次㉚ 实测：判据时红时绿。
    """
    for _ in range(tries):
        if row_fully_visible(needle):
            return True
        if down:
            swipe(540, 1500, 540, 1100, 700)
        else:
            swipe(540, 1100, 540, 1500, 700)
        time.sleep(0.6)
    return row_fully_visible(needle)


def scroll_any(needle, tries=10):
    """把某一行滚进屏：先看当前位置，再往下找，最后往上找。"""
    if row_fully_visible(needle):
        return True
    if scroll_to_row(needle, down=True, tries=6):
        return True
    return scroll_to_row(needle, down=False, tries=tries)


def hide_ime():
    """收起输入法（确认它真的弹着才发 BACK）。

    ★ 轮次㉚ 的教训：搜索框/名字输入框还聚焦时**软键盘盖住了对话框底部的按钮** ——
      点「创建 / 关闭 / 保存」其实是点在键盘上；后面 `scroll_to_row` 的滑动还会被
      Gboard 当成**手势输入**（实测往输入框里打进了 `by by by by …`）。
      BACK 会**先**被输入法吃掉（对话框不受影响），所以用它收键盘是安全的。
    """
    if 'mInputShown=true' in out('shell', 'dumpsys', 'input_method'):
        sh('shell', 'input', 'keyevent', '4')
        time.sleep(1.0)


def type_into_edittext(text):
    """把字打进当前对话框里唯一的 EditText（先清空，打完收键盘）。"""
    x = dump()
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if c == 'EditText':
            tap((x0 + x1) // 2, (y0 + y1) // 2)
            break
    else:
        return False
    time.sleep(0.8)
    for _ in range(30):
        sh('shell', 'input', 'keyevent', '67')          # DEL
    if text:
        sh('shell', 'input', 'text', text)
    time.sleep(1.0)
    hide_ime()
    return True


def goto_style_page():
    """到「形象与动作」二级页（**不要求**页面上已经有动作系统那几行 —— 没有角色时它们不显示）。"""
    x = dump()
    if has_text('形象与动作', x):           # 板块首页 ⇒ 点进去
        tap_row('形象与动作')
        time.sleep(2)
        return True
    # 可能已经在二级页里（App 会记住上次所在的二级页，这时点底栏「形象」是空操作）
    return scroll_any('角色：') or scroll_any('外观')


def create_character(name='uitest'):
    """新增一个角色（轮次㉛ 起**一个预置角色都没有**，设备脚本得先自己建一个）。

    ★ 这条路顺便就把"新增角色"验了：设置页「🎭 角色：…」→ 角色中心 → ➕ 新增角色… → 创建。
    """
    if not goto_style_page():
        return 'no-page'
    if not scroll_any('角色：'):
        return 'no-row'
    if not tap_row('角色：'):
        return 'no-tap'
    if not wait_text('角色（共', 10):
        return 'no-hub'
    if not tap_dialog_item('新增角色'):
        return 'no-new-item'
    if not wait_text('新增角色', 8):
        return 'no-dialog'
    if not type_into_edittext(name):
        return 'no-input'
    if not tap_text_exact('创建'):
        return 'no-create-btn'
    time.sleep(2.5)
    # 建完代码会停回角色中心（promptNewCharacter → showSettings + showCharacterHub）⇒ 关掉它
    tap_text_exact('关闭')
    time.sleep(1.0)
    return 'ok'


def goto_action_system():
    """底栏「形象」→「形象与动作」→ 滚到「动作系统」那一段。

    ★ 别等「动作系统」再滚：这个二级页一进去停在最上面（外观/大小/透明度），
      动作系统在很下面 —— 先滚，边滚边查（老坑：无条件滚会把目标滚过头）。
    ★ 判据从"文字在不在 dump 里"改成"**行完整可见**"：部分露出的行也算在 dump 里，
      但它按不出正确的点击坐标（见 row_fully_visible）。
    ★ 只等**最下面那一行**（「全部恢复角色包默认」）：动作系统这一段比一屏还高，
      要求首尾同时进屏会永远等不到（实测：等三行同时可见 = 脚本直接卡死返回 False）。
      最下面那行可见 ⇒ 它上面 1600px 内的场景/动作库/添加动作也都可见。
    """
    # ★ 三种起点都要认（轮次㉚：上一个设备脚本可能把 App 留在二级页里）：
    #   ① 已经在「形象与动作」二级页 ⇒ 直接滚过去（这时点底栏「形象」是**空操作**：
    #      `switchTab` 对"已在同一板块且不在根页"有守卫，等「形象与动作」那行永远等不到）；
    #   ② 在形象板块首页 ⇒ 点那一行；
    #   ③ 在别的板块 ⇒ 先点底栏「形象」。
    x = dump()
    if not has_text('形象与动作', x):
        if scroll_any('全部恢复角色包默认'):
            return True
        sh('shell', 'input', 'tap', '180', '2290')          # 底部导航「形象」
        time.sleep(1.5)
        if not wait_text('形象与动作', 8):
            skip('进入「形象与动作」', '首页没等到该行')
            return False
    tap_row('形象与动作')
    time.sleep(2)
    return scroll_to_row('全部恢复角色包默认')


# ─────────────────────── 相册多选（DocumentsUI）───────────────────────

def picker_select(prefix, names, multi=True):
    """在当前打开的选择器里：搜索 → (长按进多选) → 选 names → Select。"""
    if not wait_text('Images on', 12) and not wait_desc('Show roots', 4):
        return 'picker-not-open'
    # 搜索按钮在工具栏上，**只有 content-desc 没有 text** —— 必须按 desc 找
    if not wait_desc('Search', 8):
        return 'no-search'
    tap_desc_contains('Search')
    time.sleep(1.2)
    for _ in range(30):
        sh('shell', 'input', 'keyevent', '67')
    sh('shell', 'input', 'text', prefix)
    t0 = time.time()
    tiles = {}
    while time.time() - t0 < 14:
        tiles = {}
        for _, d, _, _, _, x0, y0, x1, y1 in nodes():
            if d and d.startswith(prefix) and ',' in d:
                tiles[d.split(',')[0]] = ((x0 + x1) // 2, (y0 + y1) // 2)
        if len(tiles) >= len(names):
            break
        time.sleep(0.8)
    if not tiles:
        return 'search-empty'
    order = [n for n in names if n in tiles]
    if not order:
        return 'name-mismatch: %s' % sorted(tiles)
    if len(order) < len(names):
        return 'only-%d-of-%d: %s' % (len(order), len(names), sorted(tiles))
    if multi and len(order) >= 2:
        long_press(*tiles[order[0]])
        if not wait_text('selected', 6, x_pred=lambda t: 'selected' in t):
            return 'no-selection-mode'
        for n in order[1:]:
            tap(*tiles[n])
            time.sleep(0.7)
        if not tap_text_exact('Select'):
            return 'no-select-button'
        time.sleep(2.5)
        return 'ok'
    tap(*tiles[order[0]])
    time.sleep(2.5)
    return 'single-ok'


# ─────────────────────── 主流程 ───────────────────────

def selftest():
    """不连设备：只验本脚本的判读逻辑（正例 + 反例）。"""
    print('== --selftest：本脚本的判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    sample = ('<node class="android.widget.CheckedTextView" text="myact_a" checked="true" '
              'clickable="false" bounds="[70,1093][1010,1219]" />')
    ns = list(nodes(sample))
    t('解析出 text/checked/bounds', ns and ns[0][0] == 'myact_a' and ns[0][4] == 'true'
      and ns[0][5] == 70 and ns[0][7] == 1010)
    t('checked=false 不会被当成勾选',
      list(nodes(sample.replace('checked="true"', 'checked="false"')))[0][4] == 'false')
    t('场景页文字判据（含"已自定义"）',
      '● 点击：3 个（已自定义）'.find('已自定义') > 0)
    t('覆盖层 JSON 判据', _json_has_click('{"scenes":{"click":["a","b","c"]}}', ['a', 'b', 'c']))
    t('覆盖层 JSON 反例（少了 c 就不算）', not _json_has_click('{"scenes":{"click":["a"]}}',
                                                             ['a', 'b', 'c']))
    t('权重判据：纯名字 = 1',
      _json_weight('{"scenes":{"click":["a","b"]}}', 'click', 'a') == 1)
    t('权重判据：{"id","weight"} 读得出 3',
      _json_weight('{"scenes":{"click":[{"id":"a","weight":3}]}}', 'click', 'a') == 3)
    t('权重判据：反例（写成 2 就不能算 3）',
      _json_weight('{"scenes":{"click":[{"id":"a","weight":2}]}}', 'click', 'a') != 3)
    t('"删掉这条覆盖"判据：没有该键 = None',
      _scene_key('{"scenes":{}}', 'click') is None)
    t('"删掉这条覆盖"反例：空数组不算删掉（P1 的反向项 m 就是钉这个）',
      _scene_key('{"scenes":{"click":[]}}', 'click') == [])
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


def _json_has_click(raw, ids):
    """判"覆盖层里 click 正好是这些动作"（顺序无关）。"""
    try:
        j = json.loads(raw)
    except Exception:
        return False
    got = j.get('scenes', {}).get('click', [])
    got = [g if isinstance(g, str) else g.get('id') for g in got]
    return sorted(got) == sorted(ids)


def read_scene_json():
    raw = out('shell', 'cat', SCENE_JSON).strip()
    return raw


def clips_listing():
    return out('shell', 'ls', '-1', CLIPS_DIR)


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(sh('install', '-r', DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
    if 'root' not in out('shell', 'id'):
        print('== adb root ==')
        sh('root')
        time.sleep(3)
        if 'root' not in out('shell', 'id'):
            print('!! 拿不到 root，读不了 filesDir（换 google_apis 镜像或先 adb root）')
            return 2

    names = make_media() if os.path.isdir(MEDIA) or True else []
    push_media(names)
    reset_state()

    print()
    print('== 一、设置页动作系统四行 ==')
    if not goto_action_system():
        return 1
    # ★ 「角色」在动作系统那一段的**最上面**，而「全部恢复角色包默认」在最下面 ——
    #   这一段比一屏还高，所以这两条判据要**分别滚到位再取**（轮次㉚ 实测：
    #   要求它们同时在屏内，脚本只会时红时绿）。
    #   ★ 轮次㉛：那一行的文案从「当前角色：」变成「角色：」（入口并成了角色中心）。
    scroll_to_row('角色：', down=False)
    check('「角色：…」行在（轮次㉛：全工程唯一的角色入口）',
          any(t.startswith('角色：') for t in texts(dump())))
    goto_action_system()
    x = dump()
    check('「场景与已绑动作」行在', has_text('场景与已绑动作', x))
    check('「动作库（查看 / 预览全部动作）」行在', has_text('动作库（查看 / 预览全部动作）', x))
    check('「添加动作（从相册多选）」行在（轮次㉑ 的 C）', has_text('添加动作（从相册多选）', x))
    check('「全部恢复角色包默认」行在', has_text('全部恢复角色包默认', x))
    check('初始态：覆盖层文件不存在（还没改过任何东西）', read_scene_json() == '')

    print()
    print('== 二、➕ 添加动作：相册多选 3 张（PNG+JPG+GIF）→ 绑到「点击」 ==')
    sh('logcat', '-c')
    if not tap_row('添加动作（从相册多选）'):
        skip('打开「添加动作」', '找不到该行')
        return 1
    r = picker_select('myact_', ['myact_a.png', 'myact_b.jpg', 'myact_c.gif'])
    check('相册里多选了 3 张（选择器返回 %s）' % r, r == 'ok')
    if r != 'ok':
        return 1
    if not wait_text('把 3 个动作加到哪', 12):
        check('弹出"加到哪个场景"对话框', False, '设备脚本点空或对话框没出现')
        return 1
    check('弹出"把 3 个动作加到哪？"', True)
    check('第一项是「只加入动作库（先不绑定）」（排在所有场景之前）', only_library_first())
    tap_dialog_item('点击')
    time.sleep(4)

    print()
    print('== 三、落盘与绑定（文件 + JSON + 日志三处交叉）==')
    ls = clips_listing()
    check('3 个文件都落进**用户素材目录** scenes/%s/clips/' % CHAR_ID,
          all(n in ls for n in names), ls.replace('\n', ' '))
    raw = read_scene_json()
    check('覆盖层 JSON 里 click = 三个动作名（追加进空场景）',
          _json_has_click(raw, ['myact_a', 'myact_b', 'myact_c']), raw.replace('\n', '')[:90])
    check('★ 覆盖层落在**这个角色**名下（scenes/%s.json）' % CHAR_ID, raw != '')
    check('★ 角色目录是脚本自己建的那一个（轮次㉛：不再有预置角色）',
          CHAR_ID in out('shell', 'ls', '/data/data/%s/files/characters' % PKG))
    lg = out('logcat', '-d')
    check('日志 DSPChar: 添加动作 ×3', lg.count('添加动作 myact_') >= 3,
          '命中 %d 条' % lg.count('添加动作 myact_'))
    check('日志 DSPBind: 添加动作已绑场景=click 新增=3',
          '添加动作已绑场景=click 新增=3' in lg)
    check('日志 PetAnimPack: 角色包 clips 目录补登记（新素材进了可播名单）',
          'clips 目录补登记' in lg)

    print()
    print('== 四、总览回显 ==')
    tap_row('场景与已绑动作')
    if not wait_text('场景与已绑动作（已自定义', 10):
        check('打开场景总览', False)
        return 1
    x = dump()
    check('总览标出"已自定义 1 个"', has_text('已自定义 1 个', x))
    check('「● 点击：3 个（已自定义）」', any(
        t.startswith('● 点击：3 个') and '已自定义' in t for t in texts(x)))

    print()
    print('== 五、场景操作面（五项，第 0 项是场景内导入）==')
    tap_dialog_item('点击')
    if not wait_text('（已绑 3 个，已自定义）', 10):
        check('打开「点击」场景操作面', False)
        return 1
    x = dump()
    items = [t for t in texts(x) if t and any(
        k in t for k in ('导入动画到此场景', '编辑绑定', '预览动作',
                         '设置权重', '恢复角色包默认'))]
    check('五项齐全且第 0 项是「📥 导入动画到此场景」',
          len(items) == 5 and '导入动画到此场景' in items[0], ' / '.join(items))

    print()
    print('== 六、编辑绑定（★ 轮次㉚ 起是可搜索多选，不再是平台的 setMultiChoiceItems）==')
    ensure_scene_ops('点击')
    tap_dialog_item('编辑绑定')
    if not wait_text('选择动作', 10):
        check('打开编辑绑定', False)
        return 1
    x = dump()
    # ★ 轮次㉚ 换的实现：行里是 CheckBox（不再是平台的 CheckedTextView），
    #   勾选数写在「只看已选（N / M）」上，列表上方还有排序按钮与计数行。
    check('勾选状态正确（「只看已选（3 / 4）」）',
          any(t.startswith('只看已选（3 / 4') for t in texts(x)),
          ' / '.join(t for t in texts(x) if '只看已选' in t))
    check('★ 排序按钮在（轮次㉚ 新增：点一下换一种排序）',
          any(t.startswith('排序：') for t in texts(x)),
          ' / '.join(t for t in texts(x) if t.startswith('排序')))
    check('计数行在（共 4 个动作）', has_text('共 4 个动作', x),
          ' / '.join(t for t in texts(x) if '个动作' in t))
    if tap_dialog_item('只看已选'):
        time.sleep(1.2)
        x = dump()
        check('★ 「只看已选」真的筛掉未勾选的（计数行改口成"显示 3 / 4"）',
              any('显示 3 / 4' in t for t in texts(x)),
              ' / '.join(t for t in texts(x) if '个' in t and '/' in t))
        tap_dialog_item('只看已选')
        time.sleep(1)
    else:
        check('「只看已选」可点', False)
    tap_text_exact('取消')
    time.sleep(1)

    print()
    print('== 七、▶️ 预览动作（静帧 vs 动图，两种都要能看）==')
    ensure_scene_ops('点击')
    tap_dialog_item('预览动作')
    if not wait_text('预览哪个动作', 10):
        check('多个绑定时先让用户挑一个', False)
    else:
        check('多个绑定时先让用户挑一个', True)
        tap_dialog_item('myact_a')
        if wait_text('预览：myact_a', 10):
            check('预览窗打开（myact_a）', True)
            x = dump()
            check('PNG 静态图按**静帧**显示（0j 的 D：静态图也是动作）',
                  any('静帧' in t for t in texts(x)),
                  ' / '.join(t for t in texts(x) if '静帧' in t or '动图' in t))
        else:
            check('预览窗打开（myact_a）', False)
        tap_text_exact('返回')
        time.sleep(1)
        ensure_scene_ops('点击')
        tap_dialog_item('预览动作')
        wait_text('预览哪个动作', 8)
        tap_dialog_item('myact_c')
        if wait_text('预览：myact_c', 10):
            x = dump()
            check('GIF 按**动图循环**显示',
                  any('动图循环' in t for t in texts(x)),
                  ' / '.join(t for t in texts(x) if '静帧' in t or '动图' in t))
        else:
            check('预览窗打开（myact_c）', False)
        tap_text_exact('关闭')
        time.sleep(1)

    print()
    print('== 八、📏 设置权重（★ P1 里最容易变成"假功能"的一条）==')
    ensure_scene_ops('点击')
    if not tap_dialog_item('设置权重'):
        skip('设置权重', '找不到该项')
    else:
        if not wait_text('权重（', 10):
            check('打开权重列表', False)
        else:
            check('权重列表列出各动作当前的 ×N', any(
                'myact_a' in t and '×' in t for t in texts(dump())))
            tap_dialog_item('myact_a')
            if wait_text('的权重', 10):
                check('进入档位选择（1/2/3/5/10）',
                      all(any(k in t for t in texts(dump())) for k in ('1（普通）', '10（最常出现）')))
                tap_dialog_item('3（更常出现）')
                time.sleep(2)
                raw = read_scene_json()
                check('★ 权重真的落盘（{"id":"myact_a","weight":3}），不是"点了没反应"',
                      _json_weight(raw, 'click', 'myact_a') == 3,
                      raw.replace('\n', '')[:150])
                check('回到列表能看见新的 ×3',
                      any('myact_a' in t and '×3' in t for t in texts(dump())),
                      ' / '.join(t for t in texts(dump()) if 'myact_a' in t))
            else:
                check('进入档位选择', False)
        tap_text_exact('返回')
        time.sleep(1)

    print()
    print('== 九、场景内「📥 导入动画到此场景」（追加，不改别的场景）==')
    ensure_scene_ops('点击')
    if not tap_dialog_item('导入动画到此场景'):
        skip('场景内导入', '找不到该行')
    else:
        r = picker_select('myact_', ['myact_a.png'], multi=False)
        check('场景内导入：相册选 1 张（%s）' % r, r in ('single-ok', 'ok'))
        if r in ('single-ok', 'ok'):
            # 这条路**不弹"加到哪个场景"**（场景在点进操作面时就记住了），直接落盘 + 绑定
            time.sleep(5)
            ls = clips_listing()
            raw = read_scene_json()
            check('同名再导入 → 自动改名 myact_a_2（不覆盖已存在的）',
                  'myact_a_2' in ls, ls.replace('\n', ' '))
            check('★ 追加绑定：click = 原来的三个 + 新的（一个都没丢）',
                  _json_has_click(raw, ['myact_a', 'myact_a_2', 'myact_b', 'myact_c']),
                  raw.replace('\n', '')[:140])
            check('★ 追加时保留了刚调好的权重（×3 没被抹成 ×1）',
                  _json_weight(raw, 'click', 'myact_a') == 3,
                  raw.replace('\n', '')[:150])
            check('导入完回到**这个**场景的操作面（"已绑 4 个"）',
                  wait_text('（已绑 4 个', 8) is not None)

    print()
    print('== 十、恢复角色包默认（★ 有"恢复/取消"确认框，脚本必须点确认）==')
    ensure_scene_ops('点击')
    if not tap_dialog_item('恢复角色包默认'):
        skip('恢复角色包默认', '找不到该项')
    else:
        if not wait_text('将恢复成角色包里写的那一套', 10):
            check('先弹确认框（不直接改）', False)
        else:
            check('先弹确认框（不直接改）', True)
            tap_text_exact('恢复')
            time.sleep(2.5)
            raw = read_scene_json()
            check('★ 覆盖里 click 这条**被删掉**（= 回角色包默认，不是写一份空数组）',
                  _scene_key(raw, 'click') is None, raw.replace('\n', '')[:120])
            check('★ 用户素材文件**保留**（删了就没法撤销）',
                  all(n in clips_listing() for n in names), clips_listing().replace('\n', ' '))
            tap_dialog_item('返回')
            time.sleep(1)
    tap_row('场景与已绑动作')
    if wait_text('场景与已绑动作（已自定义', 10):
        check('总览回显「○ 点击：0 个」（已不再自定义）',
              any(t.startswith('○ 点击：0') for t in texts(dump())),
              ' / '.join(t for t in texts(dump()) if '点击：' in t))
    tap_text_exact('关闭')
    sh('shell', 'input', 'keyevent', '4')
    time.sleep(1)

    print()
    print('=' * 62)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        if skips:
            print('（另有 %d 项跳过：%s）' % (len(skips), ' / '.join(skips)))
        return 1
    if skips:
        print('%d 项跳过（跳过按未验证计）：%s' % (len(skips), ' / '.join(skips)))
        return 1
    print('ALL DEVICE CHECKS PASSED（P1 场景编辑 + 轮次㉑ 添加动作，在 %s 上）' % DEV)
    return 0


def only_library_first():
    """「只加入动作库（先不绑定）」必须是场景清单里的**第一行**。

    ★ 判据用 y 比较（不要按数组顺序猜）：它要比「待机」那一行还靠上。
    """
    x = dump()
    y_lib = None
    y_idle = None
    for t, d, c, ck, chk, x0, y0, x1, y1 in nodes(x):
        if t and '只加入动作库' in t:
            y_lib = y0
        elif t == '待机' and y_idle is None:
            y_idle = y0
    return y_lib is not None and y_idle is not None and y_lib < y_idle


def ensure_scene_ops(label):
    """保证现在停在某个场景的操作面上（导入动画/编辑绑定/预览/权重/恢复）。

    ★ 对话框是叠着的：上一节点完「返回」会回到上一层而不是消失。
      所以先看目标项在不在，不在才重新走导航 —— 对着被挡住的设置页瞎点，
      表现出来就是"点了没反应"（本脚本第一版就这么错过一次）。
    ★ 另外 `showSettings()` 会把整页重建（滚动位置回到顶部）⇒ 点那一行之前
      先滚到它**完整可见**（否则点到的是别的控件，见 row_fully_visible）。
    """
    if has_text('导入动画到此场景'):
        return True
    scroll_any('场景与已绑动作')
    tap_row('场景与已绑动作')
    if not wait_text('场景与已绑动作（已自定义', 10):
        return False
    tap_dialog_item(label)
    return wait_text('（已绑', 10) is not None


def _scene_key(raw, scene):
    """覆盖层里某个场景的绑定（不存在返回 None）。"""
    try:
        return json.loads(raw).get('scenes', {}).get(scene)
    except Exception:
        return None


def _json_weight(raw, scene, clip):
    """覆盖层里某动作的权重（纯名字写法 = 1；找不到返回 0）。"""
    arr = _scene_key(raw, scene)
    if not isinstance(arr, list):
        return 0
    for e in arr:
        if isinstance(e, str) and e == clip:
            return 1
        if isinstance(e, dict) and e.get('id') == clip:
            return int(e.get('weight', 1))
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
