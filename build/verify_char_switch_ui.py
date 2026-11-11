# -*- coding: utf-8 -*-
"""设备验证：P3「角色切换 UI」+ 轮次㉛「角色中心」在**真机/模拟器**上真的可用（只看模拟器）。

★ 轮次㉞：本脚本按轮次㉛ 的**新 UI** 重写。
  旧版驱动的是设置页上「🎭 当前角色：…」与「🗑 删除角色…」两行 —— 轮次㉛ 把它们
  连同"内置角色"一起收进了「🎭 角色」→ **角色中心** ⇒ 旧版跑必红。
  那是**脚本过时**，不是产品 bug（旧版文件头就写着这句，见 STATE.md 待办 0m ④）。

为什么单独一套（接 `verify_char_ui.py` 之后）：
    P3 的入口是**切角色**，而"切了到底有没有换一整套"只有在设备上才看得出来：
    pref 写没写、悬浮窗有没有重载、**按角色隔离的绑定/素材有没有串味**。
    断言（verify_character_switch.py）只能钉源码与纯函数，串味这类问题必须真跑。

★ 轮次㉛ 起**一个预置角色都没有**（用户要求「不留任何角色，只靠导入」）⇒
  这一套自己造两个角色：① 导入一个自己的角色包（`EXTRA_IMPORT_PACK`）；
  ② 走「➕ 新增角色」现建一个。两个都在角色中心里，人人平等（都能切/删）。

★ 角色包怎么塞进设备（本脚本的关键决定）：
    走 `EXTRA_IMPORT_PACK` + **把 zip 推进 App 自己的 filesDir**。
    * 直接推 `/sdcard/Download/x.zip` 再让 App 按路径读 —— **必失败**：
      实测 `FileNotFoundException: EACCES`（Android 11+ 分区存储，App 按路径读不到 Download 里的普通文件）。
    * 走 SAF 选文件当然行（那条路在轮次⑳-4 已实测 `import done ok=true id=default clips=106`），
      但它要驱动 DocumentsUI 的抽屉+搜索，脆得多，而 P3 没改导入这段。
    * 推进 filesDir 的文件：SELinux 类别是对的（`app_data_file:s0:c241,...`）、0644 可读 ⇒ App 读得到
      （「从电脑导入」当初就是为这条 FUSE 限制做的）。

顺序（每一步都为下一步造出可判定的状态）：
    ① 导入一个自己的角色包 → 顺带钉住"**导入后自动切到新角色**"（P2 的既有行为）
    ② 走角色中心「➕ 新增角色」建第二个角色 → 立刻落盘 + 切过去
    ③ 设置页那一行报出「角色：X（共 N 个）」，角色中心标题报「角色（共 N 个）」
    ④ 清单：● 当前 / ○ 其他；切到某个角色 → pref + 日志 + 那一行都跟着变
    ⑤ ★ 隔离：各自加的动作落各自的 `scenes/<id>.json`，互不串味（逐字符比对）
    ⑥ 删除角色：**没有"内置不能删"这回事**（轮次㉛ 起），两个都在可删清单里；
       删的正好是当前角色 ⇒ **落到剩下的第一个**
    ⑦ ★ 新语义：删掉**最后一个**角色 ⇒ 回到"没有角色"（哨兵 + 引导语 + 中心只剩三项）

用法：
    python build/verify_char_switch_ui.py --install
    python build/verify_char_switch_ui.py
    python build/verify_char_switch_ui.py --selftest     # 不连设备，只验本脚本的判读逻辑

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import os
import re
import shutil
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                      # noqa: E402  改 pref 的可靠手法（setval/get）
import verify_char_ui as V     # noqa: E402  复用它的设备驱动与判读逻辑

DEV = V.DEV
PKG = V.PKG
WORK = r'D:\petport_lab\petbuild\build\_uitest'
FILES = '/data/data/%s/files' % PKG
CHARS = FILES + '/characters'
SCENES = FILES + '/scenes'
PREFS_XML = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG
REMOTE_PREFS = '/sdcard/_p3prefs.xml'
NEW_ID = 'p3test'
NEW_NAME = '测试角色'
# ★ 第二个角色由脚本**打字**建出来，所以名字必须 ASCII：
#   `adb shell input text` 只支持 ASCII（中文名打不进去，见 STATE 第五节的设备脚本四条）。
NEW2_NAME = 'uitest2'
NEW2_ID = 'uitest2'
REMOTE_ZIP = FILES + '/_p3char.zip'
# 旧版 appearance 脚本用的 pref 副本名（轮次㉓～㉞ 之前）—— 轮次㉞ 起两边都走 S.pref_set，
# 这个名字已经没人写了，但设备上可能还留着旧的一份（里面是**用户 pref 的整份拷贝**）。
LEGACY_REMOTE_PREFS = '/sdcard/_p5prefs.xml'

fails = []
skips = []


def check(name, ok, detail=''):
    # detail 允许传 list（日志行切片很方便）—— 统一转成字符串，别让 print 自己炸
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def skip(name, why):
    print('  [SKIP] ' + name + ' :: ' + why)
    skips.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def parse_pref(xml, name):
    """从 SharedPreferences 的 XML 文本里取一个键（纯函数，便于 --selftest 离线验）。

    ★ 两种写法都要认：`value="v"` 形式与 `<string name="k">v</string>` 形式。
    """
    m = re.search(r'name="' + re.escape(name) + r'"[^>]*?value="([^"]*)"', xml or '')
    if m:
        return m.group(1)
    m = re.search(r'<string name="' + re.escape(name) + r'">([^<]*)</string>', xml or '')
    return m.group(1) if m else None


def char_row_of(rows):
    """设置页那一行（`角色：… ›`）—— 在全部文字里挑出来。"""
    for t in rows:
        if t.startswith('角色：'):
            return t
    return ''


def char_row_name(row):
    """`角色：uitest2（共 2 个）` ⇒ `uitest2`（空态那一行 ⇒ None）。"""
    m = re.search(r'^角色：(.+?)（共 \d+ 个）', row or '')
    return m.group(1) if m else None


def char_row_count(row):
    """`角色：X（共 N 个）` ⇒ N（空态那一行 ⇒ None）。"""
    m = re.search(r'（共 (\d+) 个）', row or '')
    return int(m.group(1)) if m else None


def is_empty_row(row):
    """空态那一行（轮次㉛：一个角色都没有时设置页给的是引导语，不是角色名）。"""
    return bool(row) and row.startswith('角色：还没有')


def is_role_row(t):
    """角色中心里的一行角色（● 当前 / ○ 其他）。"""
    return bool(t) and (t.startswith('\u25CF ') or t.startswith('\u25CB '))


def is_delete_row(t, name):
    """删除清单里的一行：带角色名，且**不是**背景里设置页那一行（`角色：…（共 N 个）`）。"""
    return bool(t) and name in t and not t.startswith('角色：')


def selftest():
    """不连设备：验本脚本自己的判读逻辑（正例 + 反例）。"""
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    t('pref 解析：value= 形式',
      parse_pref('<string name="current_character" value="p3test" />',
                 'current_character') == 'p3test')
    t('pref 解析：<string> 文本形式',
      parse_pref('<string name="current_character">builtin:default</string>',
                 'current_character') == 'builtin:default')
    t('pref 解析：别的键不会被误取',
      parse_pref('<string name="other" value="x" />', 'current_character') is None)
    t('pref 解析：空输入安全', parse_pref(None, 'x') is None and parse_pref('', 'x') is None)

    t('那一行：取名字与个数',
      char_row_name('角色：uitest2（共 2 个）') == 'uitest2'
      and char_row_count('角色：uitest2（共 2 个）') == 2)
    t('那一行：空态是引导语（不是角色名）',
      is_empty_row('角色：还没有（点这里新增或导入）')
      and char_row_name('角色：还没有（点这里新增或导入）') is None
      and char_row_count('角色：还没有（点这里新增或导入）') is None)
    t('那一行：只有真那一行才认（说明文字不算）',
      char_row_of(['切换 / 新增 / 添加角色包 / 形象 / 导出 / 删除，都在这一行里。',
                   '角色：测试角色（共 1 个）']) == '角色：测试角色（共 1 个）'
      and char_row_of(['外观', '大小']) == '')

    t('角色行：● 当前 / ○ 其他都认',
      is_role_row('\u25CF uitest2（当前）') and is_role_row('\u25CB 测试角色'))
    t('角色行：反例（设置页那一行不是角色行）',
      not is_role_row('角色：uitest2（共 2 个）') and not is_role_row(''))
    t('删除行：带名字的才算',
      is_delete_row('\U0001F5D1 uitest2', 'uitest2')
      and not is_delete_row('\U0001F5D1 p3test', 'uitest2'))
    t('★ 删除行：**背景里设置页那一行不算**（名字一样也不行）',
      not is_delete_row('角色：uitest2（共 2 个）', 'uitest2'))
    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备读写小工具 ═══════════════

def pref(name):
    return parse_pref(V.out('shell', 'cat', PREFS_XML), name)


def pref_set(key, val):
    """可靠改 pref（主 prefs 文件）：force-stop → 改 XML → 还原 owner/mode/SELinux → **复读校验**。

    ★ 与 `verify_appearance_ui` 曾经各写一份，现在**只有这一份**（那边 import S 复用）：
      不用 sed（键不存在时静默不替换）、不硬写 uid（重装后 uid 会变，
      写错 owner 会让 App 读不到自己的设置，表现成"改完没生效"）。

    ★★ 临时副本写在**系统临时目录**、而且**用完立刻删**（轮次㉞ 抓到的真泄漏）：
      这份 XML 是设备 pref 的**整份拷贝**（含用户的 API Key / 搜索 Key），
      而项目移植包会把**整个 `build/`** 打进去 ⇒ 副本一旦落在 `build/` 下就跟着源码包发出去
      （轮次㉘ 在 `build/_prefs.xml` 上中过一次；轮次㉞ 本函数第一版又把 `build/_uitest/_p3prefs.xml`
      写了出来，里面是**真 Key** —— 是收尾核对时抓到的，不是交付后才发现的）。
    """
    return pref_set_in(PREFS_XML, key, val)


def pref_set_in(path, key, val, owner_from=None):
    """同 {@link #pref_set}，但可指定 prefs 文件。

    ★ 轮次㊵ 加：**主题令牌**存在另一个文件（`deepseek_pet_ui.xml` 的 `ui_skin` / `ui_theme`），
      验证"三级菜单跟随主题切换"必须能改它。泛化而不是再抄一份 ——
      上面那段 owner/mode/SELinux/临时文件纪律抄第二遍必然会漏一条。

    ★★ 文件**不存在**时要能自己合成（轮次㊵ 实测踩到）：SharedPreferences 的文件是
      **首次写入才建**的 —— 用户没改过皮肤/主题时 `deepseek_pet_ui.xml` 根本不在，
      于是 `cat` 空、函数直接返回 False，表现为"pref 写不进去"。
      这时合成一个空 `<map>` 再写键；owner/mode 从**同一 App 的另一个 prefs 文件**抄
      （写错 owner 会让 App 读不到自己的设置，表现成"改完没生效"）。
    """
    V.sh('shell', 'am', 'force-stop', PKG)
    for _ in range(20):
        if not V.out('shell', 'pidof', PKG).strip():
            break
        time.sleep(0.5)
    body = V.out('shell', 'cat', path)
    fresh = not body.strip()
    if fresh:
        body = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                "<map>\n</map>\n")
    new = pf.setval(body, key, val)
    try:
        ET.fromstring(new)
    except ET.ParseError as e:
        print('  !! 生成的不是合法 XML，拒绝写入: %s' % e)
        return False
    fd, local = tempfile.mkstemp(prefix='dsp_uitest_prefs_', suffix='.xml')
    os.close(fd)
    try:
        with open(local, 'w', encoding='utf-8', newline='\n') as f:
            f.write(new)
        owner = V.out('shell', 'stat', '-c', '%U:%G', path).strip()
        mode = V.out('shell', 'stat', '-c', '%a', path).strip()
        if not owner or not mode:                 # 新文件：抄同一个 App 的另一个 prefs 文件
            src = owner_from or PREFS_XML
            owner = owner or V.out('shell', 'stat', '-c', '%U:%G', src).strip()
            mode = mode or V.out('shell', 'stat', '-c', '%a', src).strip()
        V.sh('push', local, REMOTE_PREFS)
        V.sh('shell', 'cp', REMOTE_PREFS, path)
        if owner and mode:
            V.sh('shell', 'chown', owner, path)
            V.sh('shell', 'chmod', mode, path)
        V.sh('shell', 'restorecon', path)
        return pf.get(V.out('shell', 'cat', path), key) == val
    finally:
        try:
            os.remove(local)
        except OSError:
            pass


def pref_in(path, key):
    """读**指定** prefs 文件里的一个键（同 pref()，但可换文件）。"""
    return parse_pref(V.out('shell', 'cat', path), key)


def all_texts():
    """dump 里**全部** text（不限长度）。

    ★ 不能用 `V.texts()`：它的正则把 text 截到 **80 字符**（`text="([^"]{1,80})"`），
      而对话框正文是长句 ⇒ **整段都取不到**，表现成"确认框没弹出来"
      （其实弹了、也点了、效果也对，只是探针看不见 —— ㉓ 的教训）。
    """
    return [t for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes() if t]


def has_text_long(needle):
    return any(needle in t for t in all_texts())


def wait_text_long(needle, secs=10):
    t0 = time.time()
    while time.time() - t0 < secs:
        if has_text_long(needle):
            return True
        time.sleep(0.8)
    return False


def scene_json_of(cid):
    return V.out('shell', 'cat', '%s/%s.json' % (SCENES, cid)).strip()


def ls(path):
    return V.out('shell', 'ls', path)


# ═══════════════ 对话框：只认"新出现的那一行" ═══════════════

def dialog_baseline():
    """打开对话框**之前**的全屏文字集合。"""
    return set(all_texts())


def new_texts(before):
    """对话框里**新出现**的文字（= 对话框自己的行；背景设置页那些被排除）。"""
    return [t for t in all_texts() if t not in before]


def tap_new_item(needle, before):
    """点**对话框里新出现**的一行（返回是否点到）。

    ★ 为什么不用 `verify_char_ui.tap_dialog_item`（它按"包含"匹配任意节点）：
      对话框背后是设置页，而设置页上就有空态引导语「还没有角色。先「➕ 新增角色」…」
      （一个角色都没有时**必显示**）与「角色：X（共 N 个）」那一行 ——
      按包含匹配会**点到背景上**，表现出来是"点了没反应"，
      然后下游断言全红。这种假红最难查，所以判据改成"相对打开前的**差集**"。
    """
    want = [t for t in new_texts(before) if needle in t]
    if not want:
        return False
    target = want[0]
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes():
        if t == target:
            V.tap((x0 + x1) // 2, (y0 + y1) // 2)
            return True
    return False


def tap_delete_role(name):
    """删除清单里点某个角色（背景里设置页那一行**不算**）。"""
    for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes():
        if is_delete_row(t, name) and y0 > 150:
            V.tap((x0 + x1) // 2, (y0 + y1) // 2)
            return True
    return False


def cleanup_device_temps():
    """清掉设备上的临时文件（角色包 zip + **pref 副本**）。

    ★ pref 副本是**用户 pref 的整份拷贝**（含 API/搜索 Key）—— 留在 sdcard 上没必要。
      放在 `finish()` 里调（而不是 main 末尾），这样**中途失败退出**也会清干净；
      连旧版脚本留下的 `_p5prefs.xml` 一起收掉。
    """
    for p in (REMOTE_ZIP, REMOTE_PREFS, LEGACY_REMOTE_PREFS):
        V.sh('shell', 'rm', '-f', p)


def close_dialog():
    V.sh('shell', 'input', 'keyevent', '4')
    time.sleep(1.0)


# ═══════════════ 角色中心 ═══════════════

def goto_char_row():
    """到「形象与动作」二级页，并把「角色：」那一行滚到**完整可见**。

    ★ 三种起点都要认（App 会记住上次所在的二级页，上一个设备脚本也可能把它留在别处）：
      ① 已经在这个二级页里 ⇒ 直接滚（这时点底栏「形象」是**空操作**）；
      ② 在形象板块首页 ⇒ 点「形象与动作」那一行；
      ③ 在别的板块 ⇒ 先点底栏「形象」。
    """
    for _ in range(3):
        if V.row_fully_visible('角色：'):
            return True
        if V.scroll_any('角色：', tries=6):
            return True
        x = V.dump()
        if V.has_text('形象与动作', x):
            V.tap_row('形象与动作')
            time.sleep(2.0)
            continue
        V.sh('shell', 'input', 'tap', '180', '2290')        # 底栏「形象」
        time.sleep(1.5)
        if V.wait_text('形象与动作', 8):
            V.tap_row('形象与动作')
            time.sleep(2.0)
            continue
    return V.row_fully_visible('角色：')


def open_hub():
    """开角色中心（轮次㉛ 起**唯一**的角色入口）。返回 (是否打开, 打开前的文字集合)。"""
    if not goto_char_row():
        return False, set()
    before = dialog_baseline()
    if not V.tap_row('角色：'):
        return False, before
    if not V.wait_text('角色（共', 10):
        return False, before
    return True, before


def pick_character(name_part):
    """开角色中心 → 点某个角色（返回是否点到）。"""
    ok, before = open_hub()
    if not ok:
        return False
    return tap_new_item(name_part, before)


def create_character(name):
    """走「角色：」→ 角色中心 → ➕ 新增角色… → 创建。返回 'ok' 或失败原因。

    ★ 轮次㉛ 起角色得**自己建**（没有预置角色），这条路顺便就把「新增角色」验了。
    """
    ok, before = open_hub()
    if not ok:
        return 'no-hub'
    if not tap_new_item('新增角色', before):
        return 'no-new-item'
    if not V.wait_text('创建', 8):
        return 'no-dialog'
    if not V.type_into_edittext(name):
        return 'no-input'
    if not V.tap_text_exact('创建'):
        return 'no-create-btn'
    time.sleep(2.5)
    # 建完代码停在角色中心（promptNewCharacter → showSettings + showCharacterHub）⇒ 关掉它
    close_dialog()
    return 'ok'


def delete_character(name):
    """角色中心 → 🗑 删除角色… → 点这个角色 → 确认「删除」。返回 (是否确认, 说明)。"""
    ok, before = open_hub()
    if not ok:
        return False, 'no-hub'
    if not tap_new_item('删除角色', before):
        return False, 'no-del-item'
    if not wait_text_long('删除哪个角色', 10):
        return False, 'no-del-list'
    if not tap_delete_role(name):
        return False, 'no-target-row'
    if not wait_text_long('都会被删掉', 10):
        return False, 'no-confirm'
    V.tap_text_exact('删除')
    time.sleep(3)
    return True, 'ok'


# ═══════════════ 造角色包 / 导入 / 添加动作 ═══════════════

def make_pack():
    """造一个最小但自洽的角色包：一个静态动作当待机 + 包内元信息。"""
    from PIL import Image, ImageDraw
    src = os.path.join(WORK, 'p3pack')
    if os.path.isdir(src):
        shutil.rmtree(src)
    os.makedirs(os.path.join(src, 'anim', 'clips'))
    im = Image.new('RGBA', (200, 200), (90, 200, 160, 255))
    ImageDraw.Draw(im).ellipse([30, 30, 170, 170], fill=(255, 255, 255, 230))
    im.save(os.path.join(src, 'anim', 'clips', '我的待机.png'))
    with open(os.path.join(src, 'character.json'), 'w', encoding='utf-8') as f:
        f.write('{"id": "%s", "name": "%s", "author": "uitest", "version": "1.0"}\n'
                % (NEW_ID, NEW_NAME))
    with open(os.path.join(src, 'anim', 'pack.json'), 'w', encoding='utf-8') as f:
        f.write('{"version": 2, "scenes": {"idle": ["我的待机"]}, "events": {}}\n')
    zpath = os.path.join(WORK, 'p3char.zip')
    if os.path.exists(zpath):
        os.remove(zpath)
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        for root, _dn, fn in os.walk(src):
            for f in fn:
                full = os.path.join(root, f)
                z.write(full, os.path.relpath(full, src).replace('\\', '/'))
    return zpath


def import_pack(zpath):
    """推进 filesDir + 冷启动带 EXTRA_IMPORT_PACK（App 自己的目录它读得到）。"""
    V.sh('push', zpath, REMOTE_ZIP)
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity', '--es',
         'dsp_import_pack', REMOTE_ZIP)
    t0 = time.time()
    while time.time() - t0 < 45:
        lg = V.out('logcat', '-d')
        # ★ 判据里**不要带 tag**：logcat 会把 tag 右边补空格（`I DSPChar : import done …`），
        #   写 `'DSPChar: import done'` 永远匹配不上（本脚本第一版就这么空等了 45 秒）。
        if 'import done' in lg:
            return True, lg
        time.sleep(1.5)
    return False, V.out('logcat', '-d')


def reset_clean():
    """清态：**一个角色都不留**（设备脚本的规矩是"能从全新安装态跑"）。

    ★ 不能用 `V.reset_state()`：它（轮次㉛ 起）会顺手建一个 `uitest` 角色，
      那样「共 2 个」这类**计数**断言全错 —— 明明这一轮没造过它。
    """
    V.sh('shell', 'rm', '-rf', CHARS)
    V.sh('shell', 'rm', '-rf', SCENES)
    V.sh('shell', 'rm', '-rf', FILES + '/menus')
    V.sh('shell', 'rm', '-rf', FILES + '/appearance')
    V.sh('shell', 'rm', '-f', REMOTE_ZIP)
    # ★ 角色目录删了而 pref 还指着旧 id ⇒ 先复位到哨兵：
    #   否则"当前角色"是个悬空 id（hub 里不会打 ●、形象路径也会算到一个不存在的角色上）。
    pref_set('current_character', 'builtin:default')
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(3)
    x = V.dump()
    if V.has_text('输入', x) and V.has_text('API Key', x):
        for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes(x):
            if c == 'EditText':
                V.tap((x0 + x1) // 2, (y0 + y1) // 2)
                break
        for _ in range(40):
            V.sh('shell', 'input', 'keyevent', '67')       # DEL，清掉旧值
        V.sh('shell', 'input', 'text', 'sk-uitest-fake')
        V.tap_text_exact('保存并开始')
        time.sleep(3)
    V.sh('shell', 'input', 'tap', '180', '2290')           # 底栏回「形象」，位置可复现
    time.sleep(1.5)


def add_action(images, scene_label):
    """走「➕ 添加动作（从相册多选）」：相册多选（或单选）→ 选场景。返回 (是否成功, 说明)。"""
    if not V.scroll_any('添加动作（从相册多选）'):
        return False, '找不到入口行'
    before = dialog_baseline()
    if not V.tap_row('添加动作（从相册多选）'):
        return False, '点不动入口行'
    r = V.picker_select('myact_', images, multi=(len(images) > 1))
    if r not in ('single-ok', 'ok'):
        return False, '选择器返回 %s' % r
    if not V.wait_text('把 %d 个动作加到哪' % len(images), 12):
        return False, '没弹"加到哪个场景"'
    # ★ 场景那一行也要用"差集"判据：入口下面那句说明里就有「待机 / 点击 / 拖拽」，
    #   按"包含"匹配会点到背景上（老版 tap_dialog_item 的坑，见 tap_new_item 的说明）。
    if not tap_new_item(scene_label, before):
        return False, '点不到场景「%s」' % scene_label
    time.sleep(4)
    return True, 'ok'


# ═══════════════ 主流程 ═══════════════

def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)
    if 'root' not in V.out('shell', 'id'):
        print('!! 需要 root 才能读 filesDir / prefs')
        return 2

    names = V.make_media()
    V.push_media(names)
    reset_clean()
    check('清态后一个角色都没有（角色目录为空）',
          V.out('shell', 'ls', CHARS).strip() == '',
          'ls characters = %r' % V.out('shell', 'ls', CHARS).strip())
    zpath = make_pack()

    print()
    print('== 一、导入自己的角色包（filesDir + EXTRA_IMPORT_PACK）==')
    V.sh('logcat', '-c')
    ok, lg = import_pack(zpath)
    m = re.search(r'import done ok=(\w+) id=(\S+) clips=(\d+)', lg)
    check('导入成功（%s）' % (m.group(0) if m else '日志里没有 import done'), ok and bool(m))
    if not ok or not m:
        return finish()
    check('id = 包里的 character.json（%s）' % NEW_ID, m.group(2) == NEW_ID)
    check('动作数 = 1（包里就一个静态图）', m.group(3) == '1', m.group(3))
    check('角色目录落盘 characters/%s/anim/clips/' % NEW_ID,
          '我的待机.png' in V.out('shell', 'ls',
                                  '%s/%s/anim/clips' % (CHARS, NEW_ID)))
    check('★ 导入后**自动切到新角色**（P2 既有行为，别被回归掉）',
          pref('current_character') == NEW_ID, 'current_character=%s' % pref('current_character'))

    print()
    print('== 二、角色中心「➕ 新增角色」建第二个角色 ==')
    r = create_character(NEW2_NAME)
    check('走「角色：」→ 角色中心 → ➕ 新增角色 → 创建（%s）' % r, r == 'ok')
    if r != 'ok':
        return finish()
    time.sleep(2)
    check('★ 建完**立刻切成当前角色**（用户要的流程：建完就往里加东西）',
          pref('current_character') == NEW2_ID, 'current_character=%s' % pref('current_character'))
    check('新角色目录 + character.json 落盘',
          'character.json' in V.out('shell', 'ls', '%s/%s' % (CHARS, NEW2_ID)),
          V.out('shell', 'ls', '%s/%s' % (CHARS, NEW2_ID)).replace('\n', ' '))
    check('日志：新增角色 id=%s' % NEW2_ID, ('新增角色 id=' + NEW2_ID) in V.out('logcat', '-d'))

    print()
    print('== 三、设置页那一行 + 角色中心（个数与 ● 当前）==')
    if not goto_char_row():
        skip('打开动作系统', '滚不到「角色：」那一行')
        return finish()
    row = char_row_of(all_texts())
    check('「角色：…」行在（轮次㉛：全工程唯一的角色入口）', bool(row), row)
    check('行里报出角色个数（共 2 个）', char_row_count(row) == 2, row)
    check('此刻显示的是刚建的角色名', char_row_name(row) == NEW2_NAME, row)
    ok, before = open_hub()
    if not ok:
        check('打开角色中心', False)
        return finish()
    ts = new_texts(before)
    check('中心标题报出「角色（共 2 个）」', any('角色（共 2 个）' in t for t in ts),
          ' / '.join(t for t in ts if '角色（共' in t))
    items = [t for t in ts if is_role_row(t)]
    check('清单列出两个角色', len(items) == 2, ' / '.join(items))
    check('当前那个用 ● 标出', any(t.startswith('\u25CF ') and NEW2_NAME in t for t in items),
          ' / '.join(items))
    check('另一个用 ○ 标出', any(t.startswith('\u25CB ') and NEW_NAME in t for t in items),
          ' / '.join(items))
    check('★ 有角色时中心才有「形象 / 导出 / 删除」三项',
          all(any(k in t for t in ts)
              for k in ('形象（当前角色）', '导出当前角色', '删除角色')),
          ' / '.join(ts))
    close_dialog()

    print()
    print('== 四、在第二个角色上加动作 ==')
    V.sh('logcat', '-c')
    ok, why = add_action(['myact_a.png'], '点击')
    check('第二个角色上加动作成功（%s）' % why, ok)
    if not ok:
        return finish()
    check('落进**它自己**的 scenes/%s.json' % NEW2_ID, 'myact_a' in scene_json_of(NEW2_ID),
          scene_json_of(NEW2_ID).replace('\n', '')[:110])
    check('用户素材落 scenes/%s/clips/' % NEW2_ID,
          'myact_a.png' in V.out('shell', 'ls', '%s/%s/clips' % (SCENES, NEW2_ID)),
          V.out('shell', 'ls', '%s/%s/clips' % (SCENES, NEW2_ID)).replace('\n', ' '))

    print()
    print('== 五、★ 按角色隔离：第一个角色的覆盖层里没有它 ==')
    check('★ 第一个角色（%s）没有 myact_a（没串味）' % NEW_ID,
          'myact_a' not in scene_json_of(NEW_ID),
          scene_json_of(NEW_ID).replace('\n', '')[:110] or '(还没有这个文件)')

    print()
    print('== 六、切回第一个角色 → 两边互不干扰 ==')
    if not pick_character(NEW_NAME):
        check('打开清单并点第一个角色', False)
    else:
        time.sleep(4)
        check('pref current_character = %s' % NEW_ID, pref('current_character') == NEW_ID,
              'current_character=%s' % pref('current_character'))
        lg = V.out('logcat', '-d')
        check('日志记录切换（DSPChar: 切换角色 -> %s）' % NEW_ID,
              ('切换角色 -> ' + NEW_ID) in lg)
        if not goto_char_row():
            skip('切完那一行', '滚不到「角色：」')
        else:
            check('★ 设置页那一行跟着变成第一个角色名',
                  char_row_name(char_row_of(all_texts())) == NEW_NAME,
                  char_row_of(all_texts()))
        before_json = scene_json_of(NEW2_ID)
        V.sh('logcat', '-c')
        ok, why = add_action(['myact_b.jpg'], '点击')
        check('第一个角色上加动作成功（%s）' % why, ok)
        if ok:
            check('★ 落进**第一个角色**的 scenes/%s.json' % NEW_ID,
                  'myact_b' in scene_json_of(NEW_ID),
                  scene_json_of(NEW_ID).replace('\n', '')[:110])
            after_json = scene_json_of(NEW2_ID)
            check('★ 第二个角色那份**没被碰**（内容逐字符相同）',
                  after_json == before_json and 'myact_b' not in after_json,
                  after_json.replace('\n', '')[:110])

    print()
    print('== 七、删除角色（角色中心 → 🗑 删除角色…）==')
    # 先切到第二个角色，这样还能顺带验"删的正好是当前角色时**落到剩下的第一个**"
    if not pick_character(NEW2_NAME):
        skip('切到第二个角色', '角色清单里点不到')
    time.sleep(3)
    ok, before = open_hub()
    if not ok:
        skip('删除角色', '打不开角色中心')
    elif not tap_new_item('删除角色', before):
        skip('删除角色入口', '中心里点不到「删除角色…」')
    elif not wait_text_long('删除哪个角色', 10):
        check('弹出可删角色清单', False)
    else:
        ts = new_texts(before)
        check('★ 轮次㉛ 起没有"内置不能删"：两个角色**都在**可删清单里',
              any(is_delete_row(t, NEW2_NAME) for t in ts)
              and any(is_delete_row(t, NEW_NAME) for t in ts),
              ' / '.join(t for t in ts if NEW_NAME in t or NEW2_NAME in t))
        V.sh('logcat', '-c')
        tap_delete_role(NEW2_NAME)
        if not wait_text_long('都会被删掉', 10):
            check('先弹确认框', False)
        else:
            check('先弹确认框（写清删掉什么）', True)
            V.tap_text_exact('删除')
            time.sleep(3)
            lg = V.out('logcat', '-d')
            check('日志：删除角色 id=%s' % NEW2_ID, ('删除角色 id=' + NEW2_ID) in lg)
            check('★ 删的正好是当前角色 ⇒ 落到剩下的第一个（%s）' % NEW_ID,
                  pref('current_character') == NEW_ID,
                  'current_character=%s' % pref('current_character'))
            check('★ 角色目录被清掉', NEW2_ID not in V.out('shell', 'ls', CHARS),
                  V.out('shell', 'ls', CHARS).replace('\n', ' '))
            scenes_ls = V.out('shell', 'ls', '-R', SCENES)
            check('★ 这个角色的覆盖层 / 用户素材也清掉（不留孤儿）',
                  NEW2_ID not in scenes_ls, scenes_ls.replace('\n', ' ')[:160])
            check('★ 另一个角色那份改动还在（myact_b）',
                  'myact_b' in scene_json_of(NEW_ID))

    print()
    print('== 八、★ 删掉**最后一个**角色 ⇒ 回到"没有角色"（轮次㉛ 新语义）==')
    if not goto_char_row():
        skip('最后一个角色的那一行', '滚不到「角色：」')
    else:
        check('删完还剩一个：那一行报「共 1 个」',
              char_row_count(char_row_of(all_texts())) == 1, char_row_of(all_texts()))
    ok, why = delete_character(NEW_NAME)
    check('删掉最后一个角色（%s）' % why, ok)
    if ok:
        time.sleep(2)
        check('★ 当前角色回到哨兵（没有角色，素材层用内置兜底）',
              pref('current_character') == 'builtin:default',
              'current_character=%s' % pref('current_character'))
        check('★ 角色目录空了', NEW_ID not in V.out('shell', 'ls', CHARS),
              V.out('shell', 'ls', CHARS).replace('\n', ' '))
        if not goto_char_row():
            skip('空态那一行', '滚不到「角色：」')
        else:
            row = char_row_of(all_texts())
            check('★ 那一行变成引导语（不再是角色名）', is_empty_row(row), row)
        ok, before = open_hub()
        if not ok:
            check('空态也能打开角色中心', False)
        else:
            ts = new_texts(before)
            check('中心标题报「角色（共 0 个）」',
                  any('角色（共 0 个）' in t for t in ts),
                  ' / '.join(t for t in ts if '角色（共' in t))
            check('★ 一个角色都没有时**只列三项**（新增 / 添加角色包 / 从电脑导入）',
                  all(any(k in t for t in ts)
                      for k in ('新增角色', '添加角色包', '从电脑导入')),
                  ' / '.join(ts))
            check('★ 没有角色就不列「形象 / 导出 / 删除」（点了只会报错）',
                  not any(('形象（当前角色）' in t or '导出当前角色' in t or '删除角色' in t)
                          for t in ts),
                  ' / '.join(ts))
            close_dialog()
        check('★ 动作系统那一段也收起了（没有角色就没有动作可组织）',
              not has_text_long('添加动作（从相册多选）'))

    V.sh('shell', 'rm', '-f', REMOTE_ZIP)
    return finish()


def finish():
    # ★ 设备上的临时文件（角色包 zip + pref 副本）**所有退出路径**都要清 ——
    #   pref 副本里有用户的 Key（见 cleanup_device_temps 的说明）。
    cleanup_device_temps()
    # ★ 设备脚本用完必须擦掉本地 pref 副本：项目移植包会把整个 build/ 打进去，
    #   里面若有用户的 Key 就会跟着源码包发出去（轮次㉘ 抓到的真泄漏）。
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails or skips:
        if fails:
            print('%d 项失败：' % len(fails))
            for f in fails:
                print('   - ' + f)
        if skips:
            print('%d 项跳过（按未验证计）：%s' % (len(skips), ' / '.join(skips)))
        return 1
    print('ALL P3 DEVICE CHECKS PASSED（角色切换 UI + 角色中心，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
