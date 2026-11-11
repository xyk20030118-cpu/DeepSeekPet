# -*- coding: utf-8 -*-
"""设备验证：**非吸附状态**下桌宠"可见内容"真的贴到了屏幕边（模拟器 pet411）。

用户报障（2026-10-08）：「宠物悬浮窗状态无法贴边靠边（非吸附状态）」——
拖到边上跟手能出去，**一松手弹回屏内**（内置包实测弹回 210px）。
根因与修法见 `build/verify_edge_snap.py` 的说明与 `ContentInset` 的类注释。

本脚本量的是**真值**，不看代码推断：
  ① 系统窗口表里的 `mAttrs=(x,y)` —— 桌宠窗口**请求**的坐标（app 的真实意图）；
  ② app 自己打出来的内容留白（`PetAnimPack: inset …` / `DSPet: imageInset …`）；
  ③ 截图相减：桌宠在左 / 在右两张图相减，静止的桌面互相抵消，
     只剩桌宠像素 ⇒ 看"最边上一列到底有没有桌宠"。

判据（两种形象来源各跑一遍）：
  · 松手后窗口 x 应当**挂出屏外**（左 x<0 / 右 x+winW>屏宽）——"窗口贴屏幕边"就是旧 bug；
  · 挂出屏外的量应当等于内容留白（左 x = -左留白，右 x = 屏宽-窗宽+右留白，±4px）；
  · 最边上一列（0~3 / 屏宽-4~屏宽-1）在桌宠所在的 y 带里**有桌宠像素**（≥20 行）；
  · 松手 2 秒后再量一次 x 不许变（旧 bug 就是"松手瞬间跳回 0"）。

用法：
    python build/verify_edge_snap_ui.py --selftest     # 不连设备，验判读逻辑
    python build/verify_edge_snap_ui.py --install      # 装 dist 里的开发版再跑
    python build/verify_edge_snap_ui.py                # 设备上已有包，直接跑

★ 前提：需要 adb root（改 appearance.json 与 pref）。生产版 ROM 上跑不了，
  脚本会明确报出来，不会假装通过。

★ 轮次㉞ 顺手修的两处（都不是产品 bug，是**这个脚本自己的**）：
  ① `current_char_dir()` 原来只看 `value="…"` **属性写法**，而 Android 写 string 用的是
     元素内容写法 ⇒ 永远回退成 `builtin_default`。当前角色是**用户角色**时
     （轮次㉛ 起很容易出现），appearance.json 会被写进哨兵目录、而 App 读的是
     `appearance/<当前角色>/` ⇒ 量出来还是内置形象，判据全错。现在走 `pf.get`（两种写法都认）
     并有 4 条离线断言钉住（含"不许回退成 builtin_default"）。
  ② 本脚本用 `pf.load/save`（会把设备 pref **整份 pull 到 `build/_prefs.xml`**）却**从不擦** ——
     而项目移植包会把整个 `build/` 打进去 ⇒ 用户的 API/搜索 Key 会跟着源码包发出去
     （轮次㉘ 抓到的真泄漏）。现在收工时 `pf.wipe_local()`（其它设备脚本早就都擦了）。
"""
import io
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import verify_char_ui as V            # noqa: E402  设备驱动与判读工具
import pf                             # noqa: E402  改 pref（带 owner/mode 还原）

DEV = V.DEV
PKG = V.PKG
FILES = '/data/data/%s/files' % PKG
PREFS_XML = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG
WORK = os.path.join(HERE, '_uitest')
LOCAL_IMG = os.path.join(WORK, 'edge_square.png')

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


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

def parse_attrs(text):
    """从 `dumpsys window windows` 文本里取桌宠窗口：(x, y, w, h)。

    ★ 为什么 x/y 必须取 `mAttrs={(x,y)`：那才是 app **请求**的坐标；
      `frame=[..][..]` 是系统摆完之后的框（wrap 窗口的宽高只能从 frame 取）。
    ★ 怎么认出"哪个窗口才是桌宠"：`LAYOUT_NO_LIMITS` **且没有** `NOT_TOUCHABLE`。
      只按"够高/够宽"挑会挑到**对话气泡** —— 气泡带图时会超过 250 高，
      而它的 x 会被夹在屏幕内（贴左时正好是 0！），于是量出"桌宠在 0"这种假数据
      （本脚本第一版就踩了：明明贴到了 -191，却报 0 并且判 FAIL）。
      气泡/余额条/挂牌全都是 `FLAG_NOT_TOUCHABLE`（不接收触摸），桌宠本体不是。
    """
    lines = (text or '').splitlines()
    for i, l in enumerate(lines):
        if 'Window{' not in l or PKG not in l:
            continue
        end = len(lines)
        for k in range(i + 1, len(lines)):
            if lines[k].lstrip().startswith('Window #'):
                end = k
                break
        blk = lines[i:end]
        if not any('APPLICATION_OVERLAY' in x for x in blk):
            continue
        if not any('LAYOUT_NO_LIMITS' in x for x in blk):
            continue
        if any('NOT_TOUCHABLE' in x for x in blk):
            continue          # 气泡 / 余额条 / 挂牌：不是桌宠本体
        px = py = w = h = None
        for x in blk:
            m = re.search(r'mAttrs=\{\((-?\d+),(-?\d+)\)', x)
            if m and px is None:
                px, py = int(m.group(1)), int(m.group(2))
            m2 = re.search(r'frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', x)
            if m2 and w is None:
                w = int(m2.group(3)) - int(m2.group(1))
                h = int(m2.group(4)) - int(m2.group(2))
        if px is not None and w and h and h >= 250 and w >= 400:
            return (px, py, w, h)
    return None


def parse_pack_inset(log_text):
    """`PetAnimPack: inset <id> 640x360 L=.. R=.. T=.. B=..` ⇒ (W,H,[L,R,T,B])，取最后一条。"""
    ms = re.findall(r'inset\s+\S+\s+(\d+)x(\d+)\s+L=([\d.]+)\s+R=([\d.]+)\s+T=([\d.]+)\s+B=([\d.]+)',
                    log_text or '')
    if not ms:
        return None
    w, h, l, r, t, b = ms[-1]
    return (int(w), int(h), [float(l), float(r), float(t), float(b)])


def parse_image_inset(log_text):
    """`DSPet: imageInset 200x200 L=.. R=..` ⇒ (W,H,[L,R,T,B])，取最后一条。"""
    ms = re.findall(r'imageInset\s+(\d+)x(\d+)\s+L=([\d.]+)\s+R=([\d.]+)', log_text or '')
    if not ms:
        return None
    w, h, l, r = ms[-1]
    return (int(w), int(h), [float(l), float(r), 0.0, 0.0])


def view_inset(bm_w, bm_h, alpha, view_w, view_h, fit_center=True):
    """画布空间留白 → 视口空间留白（与纯类 ContentInset.of 同口径）。

    ★ 设备脚本里重写一遍是**故意的**：它给的是"期望值"，用被测代码自己算期望值
      等于自己证明自己。这里的算式由 fpstest [21] 单独钉住。
    """
    if bm_w <= 0 or bm_h <= 0 or view_w <= 0 or view_h <= 0:
        return [0.0, 0.0, 0.0, 0.0]
    if fit_center:
        s = min(view_w / float(bm_w), view_h / float(bm_h))
        dw, dh = bm_w * s, bm_h * s
        ox, oy = (view_w - dw) / 2.0, (view_h - dh) / 2.0
    else:
        dw, dh, ox, oy = float(view_w), float(view_h), 0.0, 0.0

    def c01(v):
        return 0.0 if v < 0 else (1.0 if v > 1 else v)

    left = ox + c01(alpha[0]) * dw
    right = ox + dw - c01(alpha[1]) * dw
    top = oy + c01(alpha[2]) * dh
    bottom = oy + dh - c01(alpha[3]) * dh
    return [c01(left / view_w), c01((view_w - right) / view_w),
            c01(top / view_h), c01((view_h - bottom) / view_h)]


def expected_x(screen_w, win_w, inset, side):
    """该侧"内容贴屏幕边"时窗口 x 应该是多少。"""
    if side == 'left':
        return -int(inset[0] * win_w)
    return screen_w - win_w + int(inset[1] * win_w)


def edge_bbox(a, b, y0, y1, lo, hi, thresh=40):
    """两张截图在桌宠 y 带里相减，返回 [lo,hi] 列范围内"有桌宠像素"的极值列。

    a = 桌宠在该侧的截图，b = 桌宠在**另一侧**的截图（当背景参照）。
    静止的桌面在两张图里一样 ⇒ 相减为 0，只剩桌宠（本机实测：桌宠不动时
    相隔 15s 的两张截图**零差异**，所以这个手法是干净的）。

    ★ 为什么用"极值列"而不是"数多少行有像素"：贴边靠的是 alpha **包围盒**，
      角色最外侧那几列往往只有几行不透明（实测内置包最左 4 列只有 8 行）
      ⇒ 用行数阈值会把"真的贴到了边"误判成失败。
    """
    import numpy as np
    y0 = max(0, y0)
    y1 = min(a.shape[0], y1)
    lo = max(0, lo)
    hi = min(a.shape[1] - 1, hi)
    if y1 <= y0 or hi < lo:
        return None
    d = np.abs(a[y0:y1, lo:hi + 1] - b[y0:y1, lo:hi + 1]).sum(axis=2)
    cols = np.where((d > thresh).sum(axis=0) >= 1)[0]
    if len(cols) == 0:
        return None
    return (int(cols.min()) + lo, int(cols.max()) + lo)


def selftest():
    """不连设备：验本脚本自己的判读逻辑（正例 + 反例）。"""
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    dump = """
  Window #6 Window{abc u0 com.deepseek.pet}:
    mAttrs={(662,532)(wrapxwrap) ty=APPLICATION_OVERLAY
      fl=NOT_FOCUSABLE NOT_TOUCHABLE LAYOUT_NO_LIMITS HARDWARE_ACCELERATED
    Frames: frame=[0,300][468,600] last=[0,300][468,600]
  Window #7 Window{def u0 com.deepseek.pet}:
    mAttrs={(-210,736)(wrapxwrap) ty=APPLICATION_OVERLAY
      fl=NOT_FOCUSABLE LAYOUT_NO_LIMITS HARDWARE_ACCELERATED
    Frames: frame=[0,864][630,1218] last=[0,864][630,1218]
"""
    t('取到桌宠窗口（跳过带 NOT_TOUCHABLE 的气泡）', parse_attrs(dump) == (-210, 736, 630, 354))
    t('★ x 取 mAttrs（-210），宽高取 frame（630x354）', parse_attrs(dump)[0] == -210)
    t('★ 气泡比桌宠"高"也不许被当成桌宠（它的 x 会被夹在屏幕内 ⇒ 量出假的 0）',
      parse_attrs(dump)[0] != 662)
    t('反例：没有本包窗口 ⇒ None',
      parse_attrs('Window #1 Window{x u0 com.other}:\n  ty=APPLICATION_OVERLAY\n') is None)
    t('反例：没有 LAYOUT_NO_LIMITS 的 overlay 不算桌宠',
      parse_attrs('Window #6 Window{x u0 com.deepseek.pet}:\n'
                  '  mAttrs={(0,0)(wrapxwrap) ty=APPLICATION_OVERLAY\n'
                  '  frame=[0,0][630,354]\n') is None)

    t('读动画包留白（含画布尺寸）',
      parse_pack_inset('I PetAnimPack: inset 待机呼吸休闲 640x360 '
                       'L=0.334375 R=0.3359375 T=0.17777778 B=0.08611111') is not None)
    t('★ 取的是**最后一条**（换过动作要以最新的为准）',
      parse_pack_inset('inset a 640x360 L=0.1 R=0.1 T=0.0 B=0.0\n'
                       'inset b 640x360 L=0.2 R=0.2 T=0.0 B=0.0')[2][0] == 0.2)
    t('反例：没有 inset 行 ⇒ None', parse_pack_inset('no inset here') is None)
    t('读上传图片留白', parse_image_inset('I DSPet: imageInset 200x200 L=0.05 R=0.05')[2][0] == 0.05)
    t('反例：坏行安全返回 None', parse_image_inset('imageInset oops') is None)

    # 内置包实测值：640×360 画布 → 630×354 视口（长宽比几乎相同）
    ins = view_inset(640, 360, [0.334375, 0.3359375, 0.17777778, 0.08611111], 630, 354, True)
    t('内置包左留白 ≈ 0.334（210px）', abs(ins[0] - 0.3344) < 0.002)
    t('★ 内置包"贴左"的窗口 x = -210', expected_x(1080, 630, ins, 'left') == -210)
    t('★ 内置包"贴右"的窗口 x = 661', expected_x(1080, 630, ins, 'right') == 661)

    # 方图（200×200）在 16:9 视口里：左右各留 (630-354)/2 = 138px
    sq = view_inset(200, 200, [0.0, 0.0, 0.0, 0.0], 630, 354, True)
    t('★ 方图左右各留 138px（等比缩放的居中留白）', round(sq[0] * 630) == 138)
    t('★ 方图"贴左"的窗口 x = -138', expected_x(1080, 630, sq, 'left') == -138)
    t('反例：拉伸铺满（FIT_XY）没有居中留白',
      round(view_inset(200, 200, [0.0, 0.0, 0.0, 0.0], 630, 354, False)[0] * 630) == 0)

    import numpy as np
    band = np.zeros((354, 1080, 3), dtype=int)
    other = np.zeros((354, 1080, 3), dtype=int)
    band[100:200, 0:4] = 200          # 左边一列有桌宠
    band[100:200, 1076:1080] = 200    # 右边一列也有
    t('边缘像素判据：左边极值列 = 0', edge_bbox(band, other, 0, 354, 0, 400) == (0, 3))
    t('边缘像素判据：右边极值列 = 1079',
      edge_bbox(band, other, 0, 354, 700, 1079) == (1076, 1079))
    t('反例：两张图一样（没量到桌宠）⇒ None', edge_bbox(other, other, 0, 354, 0, 400) is None)

    # ★ 轮次㉞：当前角色怎么读（原来只看 value= 属性写法 ⇒ 永远回退成 builtin_default）
    t('当前角色：元素内容写法（Android 真实写法，设备上实测过）',
      char_dir_from_pref('<string name="current_character">uitest</string>') == 'uitest')
    t('当前角色：属性写法也认',
      char_dir_from_pref('<string name="current_character" value="p3test" />') == 'p3test')
    t('当前角色：没有这个键 ⇒ 哨兵目录（不是空串）',
      char_dir_from_pref('<map><string name="other">x</string></map>') == 'builtin_default')
    t('★ 当前角色是用户角色时**不许**回退成 builtin_default（本轮修的就是这个）',
      char_dir_from_pref('<string name="current_character">appeara</string>') != 'builtin_default')

    t('★ 只认模拟器（不许往用户真机装包/改 pref）', DEV == 'emulator-5554')

    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def root_ok():
    if 'root' not in V.out('shell', 'id'):
        print('== 尝试 adb root ==')
        V.out('root')
        time.sleep(3)
    return 'root' in V.out('shell', 'id')


def char_dir_from_pref(body):
    """从 prefs XML 文本算形象目录名（纯函数，便于 `--selftest` 离线验）。

    ★ 轮次㉞ 修：原来只看 `value="…"` **属性写法**，而 **Android 写 string 用的是元素内容写法**
      （`<string name="current_character">uitest</string>` —— 设备上实测确认）⇒ 永远读到 None、
      永远回退成 `builtin_default`。当前角色是**用户角色**时（轮次㉛ 起很容易出现：不再有
      "切回内置"那一行），本脚本就把 appearance.json 写进**哨兵**目录，而 App 读的是
      `appearance/<当前角色>/` ⇒ 量出来的还是内置形象，判据全错（而且错得很像"产品 bug"）。
      `pf.get` 两种写法都认（它自己的注释里记着同一个坑的代价）。
    """
    cid = pf.get(body, 'current_character') or 'builtin:default'
    return cid.replace(':', '_')


def current_char_dir():
    """当前角色的形象目录名（`builtin:default` ⇒ `builtin_default`）。"""
    return char_dir_from_pref(V.out('shell', 'cat', PREFS_XML))


def push_owned(local, remote, ref_dir):
    """推进 App 私有目录并还原 owner/mode。

    ★ 必须这么干：直接 `adb push` 进去的文件 owner 是 root/u0_a0，
      而 App 是普通 uid —— 读不到就会**静默**用回内置形象，
      表现成"我明明设了图片来源，量出来还是内置包的留白"（本脚本第一版就踩了：
      用 `sh -c "printf … > file"` 写出来是个 0 字节文件，量了半天全是错的）。
    """
    V.sh('push', local, remote)
    owner = V.out('shell', 'stat', '-c', '%U:%G', ref_dir).strip()
    if owner:
        V.sh('shell', 'chown', owner, remote)
    V.sh('shell', 'chmod', '600', remote)
    V.sh('shell', 'restorecon', remote)
    return V.out('shell', 'cat', remote).strip() != ''


def set_appearance(source):
    """写当前角色的 appearance.json（并确保 image.png 存在）。"""
    d = '%s/appearance/%s' % (FILES, current_char_dir())
    V.sh('shell', 'mkdir', '-p', d)
    if not os.path.isdir(WORK):
        os.makedirs(WORK)
    json_local = os.path.join(WORK, '_appearance_%s.json' % source)
    with open(json_local, 'w', encoding='utf-8', newline='\n') as f:
        f.write('{"version":1,"source":"%s"}' % source)
    if source == 'image':
        make_square_png(LOCAL_IMG)
        push_owned(LOCAL_IMG, d + '/image.png', FILES)
    return push_owned(json_local, d + '/appearance.json', FILES)


def make_square_png(path):
    """造一张**方图**（200×200，中间一个不透明圆）—— 方图在 16:9 窗口里左右各留 138px，
    这正是"上传图片贴不到边"那一半 bug 的复现条件。"""
    if os.path.exists(path):
        return path
    if not os.path.isdir(WORK):
        os.makedirs(WORK)
    try:
        from PIL import Image, ImageDraw
    except Exception:
        return None
    im = Image.new('RGBA', (200, 200), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    dr.ellipse((30, 10, 170, 190), fill=(240, 120, 60, 255))
    im.save(path)
    return path


def cold_start():
    V.out('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    V.out('shell', 'am', 'force-stop', PKG)
    time.sleep(2)
    V.out('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(6)
    # 全新安装态会弹「输入 API Key」（setCancelable(false)，取消=finish），先关掉
    xml = V.dump()
    if '保存并开始' in xml:
        V.tap_text_exact('sk-xxxxxxxx')
        V.out('shell', 'input', 'text', 'sk-edge-probe')
        time.sleep(1)
        V.out('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(1)
    V.out('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(4)


def window_now():
    return parse_attrs(V.out('shell', 'dumpsys', 'window', 'windows'))


def wait_window(secs=15):
    for _ in range(secs * 2):
        w = window_now()
        if w:
            return w
        time.sleep(0.5)
    return None


def screen_w():
    m = re.search(r'(\d+)x(\d+)', V.out('shell', 'wm', 'size'))
    return int(m.group(1)) if m else 1080


def shot(tag):
    V.out('shell', 'screencap', '-p', '/sdcard/_edge.png')
    local = os.path.join(WORK, '_edge_%s.png' % tag)
    V.out('pull', '/sdcard/_edge.png', local)
    return local


def drag_to_edge(w, side, sw):
    """把桌宠拖到某一侧屏幕外，返回松手 2 秒后的窗口。"""
    x0 = w[0] + w[2] // 2
    y0 = w[1] + w[3] // 2
    tx = -300 if side == 'left' else sw + 300
    V.swipe(x0, y0, tx, y0, 900)
    time.sleep(2.5)
    return window_now()


def run_source(source):
    """跑一种形象来源：贴左 + 贴右，量 x / 期望值 / 边缘像素。"""
    print()
    print('== 形象来源 = %s ==' % source)
    if not set_appearance(source):
        check('%s：写 appearance.json' % source, False, '写不进去（root？）')
        return
    # ★ 清日志必须在**启动之前**：内容留白是启动时算的（且只在 cache miss 时打日志），
    #   启动后再清就把唯一那行证据删掉了 —— 本脚本第一版就踩了这个坑。
    V.out('logcat', '-c')
    cold_start()
    w0 = wait_window()
    if not w0:
        check('%s：找到桌宠窗口' % source, False, '等 15s 没等到')
        return
    check('%s：找到桌宠窗口' % source, True, 'mAttrs=%s' % (w0,))
    sw = screen_w()

    x = {}          # 松手那一刻的窗口
    x_later = {}    # 松手 2 秒后再量（旧 bug 就是在这 2 秒里跳回屏内的）
    shots = {}
    for side in ('left', 'right'):
        cur = window_now() or w0
        x[side] = drag_to_edge(cur, side, sw)
        time.sleep(2.0)
        x_later[side] = window_now()
        # 拖拽会让桌宠说一句（气泡是独立窗口，会干扰"两图相减"），等它自己收掉再截图
        time.sleep(13)
        shots[side] = shot(side)

    logs = V.out('logcat', '-d', '-s', 'PetAnimPack:I', 'DSPet:I')
    m = parse_pack_inset(logs) if source == 'builtin' else parse_image_inset(logs)
    if not m:
        # ★ 这不是"跳过"而是**真失败**：没有内容留白 ⇒ 窗口不许挂出屏外 ⇒ 贴不到边，
        #   正是用户报的那个 bug。跳过按未验证计，绝不能报成 PASSED。
        check('%s：拿到内容留白日志' % source, False,
              '日志里没有 inset 行（看看是不是把 logcat 清了、或素材量不出留白）')
        return
    bm_w, bm_h, alpha = m
    ins = view_inset(bm_w, bm_h, alpha, w0[2], w0[3], True)
    print('    留白：画布 %dx%d %s ⇒ 视口 %s（窗口 %dx%d）'
          % (bm_w, bm_h, alpha, [round(v, 4) for v in ins], w0[2], w0[3]))

    wl, wr = x['left'], x['right']
    exp_l = expected_x(sw, w0[2], ins, 'left')
    exp_r = expected_x(sw, w0[2], ins, 'right')

    try:
        from PIL import Image
        import numpy as np
    except Exception as e:
        skip('%s：边缘像素判据' % source, '没装 PIL/numpy: %s' % e)
        bbox_left = bbox_right = None
    else:
        a = np.array(Image.open(shots['left']).convert('RGB')).astype(int)
        b = np.array(Image.open(shots['right']).convert('RGB')).astype(int)
        y0, y1 = w0[1], w0[1] + w0[3]
        # 只看"另一侧不可能有桌宠"的那段列，避免把两个位置的桌宠混在一起：
        #   贴左图里右半屏是干净的参照，贴右图里左半屏是干净的参照
        inl_px = int(ins[0] * w0[2])
        inr_px = int(ins[1] * w0[2])
        split_r = (wr[0] + inl_px) if wr else sw        # 贴右时内容的左缘
        split_l = (wl[0] + w0[2] - inr_px) if wl else 0  # 贴左时内容的右缘
        bbox_left = edge_bbox(a, b, y0, y1, 0, max(0, split_r - 8))
        bbox_right = edge_bbox(b, a, y0, y1, min(sw - 1, split_l + 8), sw - 1)

    check('%s：贴左时窗口挂出屏外（x<0）' % source, wl is not None and wl[0] < 0,
          'x=%s' % (wl[0] if wl else None))
    check('%s：贴左的 x 等于「内容左缘贴屏幕左缘」（期望 %d）' % (source, exp_l),
          wl is not None and abs(wl[0] - exp_l) <= 4, 'x=%s' % (wl[0] if wl else None))
    check('%s：贴右时窗口挂出屏外（x+winW>屏宽）' % source,
          wr is not None and wr[0] + wr[2] > sw,
          'x=%s winW=%s' % (wr[0] if wr else None, w0[2]))
    check('%s：贴右的 x 等于「内容右缘贴屏幕右缘」（期望 %d）' % (source, exp_r),
          wr is not None and abs(wr[0] - exp_r) <= 4, 'x=%s' % (wr[0] if wr else None))
    check('%s：★ 松手 2 秒后不回弹（贴左）' % source,
          x_later['left'] is not None and wl is not None
          and abs(x_later['left'][0] - wl[0]) <= 4,
          '松手=%s 2秒后=%s' % (wl[0] if wl else None,
                               x_later['left'][0] if x_later['left'] else None))
    check('%s：★ 松手 2 秒后不回弹（贴右）' % source,
          x_later['right'] is not None and wr is not None
          and abs(x_later['right'][0] - wr[0]) <= 4,
          '松手=%s 2秒后=%s' % (wr[0] if wr else None,
                               x_later['right'][0] if x_later['right'] else None))
    if bbox_left is not None:
        check('%s：★ 像素上「最左可见内容」就在屏幕最左缘（≤2px）' % source,
              bbox_left[0] <= 2, '实测最左内容列 = %d' % bbox_left[0])
    if bbox_right is not None:
        check('%s：★ 像素上「最右可见内容」就在屏幕最右缘（≥屏宽-3）' % source,
              bbox_right[1] >= sw - 3, '实测最右内容列 = %d（屏宽 %d）' % (bbox_right[1], sw))


def main():
    if '--selftest' in sys.argv:
        return selftest()
    if '--install' in sys.argv:
        print(V.out('install', '-r', '-d', V.DEV_APK).strip())
    if not root_ok():
        print('!! 需要 adb root 才能改 appearance.json / pref —— 本脚本拒绝在无 root 时假装通过')
        return 1

    # 漫游要关掉：否则桌宠自己走开，量到的位置不是我们摆的
    body = pf.load()
    if body is None:
        print('!! 读不到 pref，退出')
        return 1
    old_wander = pf.get(body, 'wander_on')
    old_snap = pf.get(body, 'edge_snap')
    pf.stop()
    pf.save(pf.setval(body, 'wander_on', 'false', 'boolean'))
    body = pf.load()
    pf.save(pf.setval(body, 'edge_snap', 'true', 'boolean'))
    try:
        for src in ('builtin', 'image'):
            run_source(src)
    finally:
        body = pf.load()
        if body is not None:
            if old_wander is not None:
                body = pf.setval(body, 'wander_on', old_wander, 'boolean')
            if old_snap is not None:
                body = pf.setval(body, 'edge_snap', old_snap, 'boolean')
            pf.save(body)
        # ★ 轮次㉞：本脚本用的是 `pf.load/save`（会把设备 pref **整份 pull 到 build/_prefs.xml**），
        #   而项目移植包会把整个 `build/` 打进去 ⇒ 用完必须擦掉，否则用户的 API/搜索 Key
        #   会跟着源码包发出去（轮次㉘ 抓到的真泄漏）。其它设备脚本早就都擦了，只有这一份漏了。
        pf.wipe_local()

    print()
    print('=' * 62)
    if skips:
        print('跳过 %d 项（跳过按未验证计）：' % len(skips))
        for s in skips:
            print('   - ' + s)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    print('ALL DEVICE CHECKS PASSED')
    return 0


if __name__ == '__main__':
    sys.exit(main())
