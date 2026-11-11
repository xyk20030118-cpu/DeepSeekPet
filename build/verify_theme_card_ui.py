# -*- coding: utf-8 -*-
"""设备验证：**调色卡**真的能看见、能用（轮次㊿）。

用户原话：「自定义颜色加一个调色卡 自己选颜色 而不是文字选项」。

## 判据

  ① 点「主色调」后弹窗里出现调色卡（我加的小标题「色卡（点一下即选）」+
     「自己调（色相 / 饱和度 / 明度）」+「当前颜色 …」读数）；
  ② **初始色正确**：读数与当前主色一致（色相/饱和/明度都对得上，不是近黑）；
  ③ **点色卡能选中**：点第 11 格（红 `#B3261E`）后读数变成它；
  ④ **确定能落地**：日志出现 `accent=#ffb3261e`，且设置页那行显示 `主色 #B3261E`。

轮次55 追加（P2 最后一项 **Mesh 光斑可调**）：⑦ 走用户真走的那条路
（✨ Mesh 光斑 → 浓度 → 关/浓），**双向**验证 ——
  * **像素**：关 vs 浓，页面背景的 B−R 位移差 ≥ 25（光斑真的在渲染上变了）；
  * **读数**：体检那一行的读数**跟着变**（旧口径只量底色 ⇒ 这两个数会一模一样，
    这就是"体检口径升级为卡片叠光斑极值"的直接证据）；
  * 跟随主色 ⇒ 日志 `mesh=…/accent`；恢复默认 ⇒ **0 行 DSTheme**（零变化承诺）。

## 两个真坑（都在这里钉着，别再犯）

  * **弹窗套弹窗**：在列表弹窗的 item 回调里直接开下一个弹窗，会被它自己的 dismiss 连带关掉
    —— 症状是"调色卡一闪即没"且**没有任何异常日志**。修法是 `d.dismiss(); showX();`。
  * **种滑块**：`setProgress` 会触发回调**反向改写** hsv 数组，用同一份 hsv 连种三条滑块
    会自己冲掉自己（第一版症状：主色是蓝的，调色卡却显示"饱和 0% · 明度 6%"、预览近黑）。
    修法是先存局部量、种完再**从滑块回读**。

用法：
    python build/verify_theme_card_ui.py --install
    python build/verify_theme_card_ui.py
    python build/verify_theme_card_ui.py --selftest
"""
import os
import re
import sys
import time

sys.path.insert(0, r'D:\petport_lab\petbuild\build')
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

import pf                            # noqa: E402
import verify_char_ui as V           # noqa: E402
import verify_char_switch_ui as S    # noqa: E402
import verify_scene_exits_ui as X    # noqa: E402  （借它的 push_owned：改 files/ 要还原 owner）

DEV = V.DEV
PKG = V.PKG
UI_PREFS = '/data/data/%s/shared_prefs/deepseek_pet_ui.xml' % PKG
THEME_JSON = '/data/data/%s/files/theme/glass_light.json' % PKG
# 第 11 格（下标 10）＝ 红色，是调色卡 SWATCHES 里的固定一格
SWATCH_IDX = 10
SWATCH_HEX = '#B3261E'
SHOT = '/sdcard/_theme_card_shot.png'

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


# ═══════════════ 判读逻辑（纯函数，--selftest 离线验）═══════════════

CARD_RE = re.compile(r'当前颜色\s*#([0-9A-Fa-f]{6})\s*（色相\s*(\d+)°\s*·\s*饱和\s*(\d+)%\s*·\s*明度\s*(\d+)%）')


def card_open(texts):
    """调色卡开着吗（两个小标题 + 读数都在）"""
    j = '\n'.join(texts or [])
    return ('色卡（点一下即选）' in j and '自己调' in j and '当前颜色' in j)


def parse_card(texts):
    """→ (hex, h, s, v)；读不到就 None"""
    for t in (texts or []):
        m = CARD_RE.search(t or '')
        if m:
            return ('#' + m.group(1).upper(), int(m.group(2)), int(m.group(3)), int(m.group(4)))
    return None


BG_RE = re.compile(r'背景图(主色|平均色|亮色|暗色)\s*#([0-9A-Fa-f]{6})')


def audit_ratio(text):
    """「🩺 配色体检：6.1:1 ✓」→ 6.1；读不到返回 None"""
    m = re.search(r'配色体检[：:]\s*([0-9]+(?:\.[0-9]+)?)\s*:\s*1', text or '')
    return float(m.group(1)) if m else None


def mesh_delta_ok(delta, need=25):
    """光斑"关"与"浓"的页面 B−R 位移差够不够大（够大 ⇒ 光斑真的在渲染上变了）

    ★ 为什么用 **B−R 的位移**、而不是"蓝通道绝对占优"：内置浅色底色是暖白
      （#F3EFEA，B−R = −9），光斑是蓝/青/紫 —— 判据要量的是**相对位移**，
      要求"绝对更蓝"会在某些底色下误判（轮次㊾ 的探针就栽在这上面）。
    """
    return delta is not None and delta >= need


def bg_candidates(texts):
    """「从背景图取色」里列出的候选 → [(角色, #RRGGBB)]（按界面顺序）"""
    out = []
    for t in (texts or []):
        m = BG_RE.search(t or '')
        if m:
            out.append((m.group(1), '#' + m.group(2).upper()))
    return out


def hex_close(a, b, tol=3):
    """允许 ±tol/通道（滑块是 0~100 整数档，回算必有小误差）"""
    try:
        pa = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
        pb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    except Exception:
        return False
    return all(abs(x - y) <= tol for x, y in zip(pa, pb))


def swatch_nodes(nodes):
    """调色卡的色块 —— 按**无障碍描述**认（`色卡 #RRGGBB`）。

    ★★ 为什么不能按"类名=View + 方形"猜：uiautomator 会**剔除没有文字/描述的纯 View**，
      第一版就是这么数出 0 格的。所以色块带上了 contentDescription（顺带也是无障碍改进）。
    """
    out = []
    for t, d, _c, _ck, _chk, x0, y0, x1, y1 in nodes:
        if not d or '色卡' not in d:
            continue
        m = re.search(r'#[0-9A-Fa-f]{6}', d)
        out.append(((y0, x0), (x0 + x1) // 2, (y0 + y1) // 2,
                    m.group(0).upper() if m else ''))
    out.sort()
    return out


def selftest():
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    good = ['选择主色调', '当前颜色\u3000#1D5BC4\u3000（色相 218° · 饱和 85% · 明度 77%）',
            '色卡（点一下即选）', '自己调（色相 / 饱和度 / 明度）', '确定', '取消']
    t('认出调色卡开着', card_open(good))
    t('解析出颜色与 HSV', parse_card(good) == ('#1D5BC4', 218, 85, 77))
    t('容差比较：#1D5BC4 ≈ #1D5AC5（滑块取整误差）', hex_close('#1D5BC4', '#1D5AC5'))
    t('反例：差太多不算相等', not hex_close('#0F0F0F', '#1D5AC5'))
    t('反例：只有标题、没有读数 ⇒ 不算开着',
      not card_open(['色卡（点一下即选）', '自己调（色相 / 饱和度 / 明度）']))
    t('反例：旧版"色值文字列表"⇒ 不算开着',
      not card_open(['主色调：#1D5AC5', '背景底色：#F3EFEA', '手动输入色值…']))
    # ★ 第一版那个 bug 的症状：近黑、饱和 0% —— 必须能被判否
    t('★★ 第一版 bug 症状（近黑 + 饱和 0%）判否',
      not hex_close(parse_card(['当前颜色\u3000#0F0F0F\u3000（色相 218° · 饱和 0% · 明度 6%）'])[0],
                    '#1D5AC5'))
    fake = [('', '色卡 #B3261E', 'android.view.View', True, None, 100, 100, 194, 194),
            ('', '', 'android.widget.SeekBar', True, None, 40, 600, 1000, 660),
            ('', '', 'android.view.View', False, None, 40, 60, 990, 130)]
    t('★ 只认带"色卡 #RRGGBB"描述的节点（SeekBar/没描述的都排除）',
      len(swatch_nodes(fake)) == 1)
    # 从背景图取色的候选解析（P1）
    t('解析出四个背景图候选', bg_candidates([
        '背景图主色\u3000#1E88E5', '背景图平均色\u3000#1E88E5',
        '背景图亮色\u3000#1E88E5', '背景图暗色\u3000#1E88E5']) == [
        ('主色', '#1E88E5'), ('平均色', '#1E88E5'), ('亮色', '#1E88E5'), ('暗色', '#1E88E5')])
    t('反例：没取色时是空表', bg_candidates(['自定义界面颜色…（默认）']) == [])
    # 轮次55：体检读数解析 + Mesh 位移判据
    t('解析体检读数（达标）', audit_ratio('\U0001FA7A 配色体检：6.1:1 \u2713') == 6.1)
    t('解析体检读数（不达标）',
      audit_ratio('\U0001FA7A 配色体检：1.0:1 \u26A0 3 项不达标') == 1.0)
    t('反例：没有体检行 ⇒ None', audit_ratio('自定义界面颜色…（默认）') is None)
    t('★ Mesh 位移差够大 ⇒ 判定"光斑真的变了"', mesh_delta_ok(55) and not mesh_delta_ok(3))
    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def page_tint():
    """截屏 → 页面**最外圈**采样 → 返回 (平均 B−R, 平均色)。

    ★ 为什么只采最外圈：设置页的卡片与蓝色主按钮都在内圈，采进来会把"主色"当成"光斑"
      （主按钮的 B−R 有 +150 量级，会把光斑信号整个淹掉）。页面背景（Mesh）只在
      页面内边距那一圈露出来。
    ★ 为什么用**平均值**而不是"最蓝的那一点"：光斑是渐变，单点会受某个角落的杂色影响；
      整圈平均才是"这片背景整体偏蓝多少"。实测（1080x2400、内置浅色玻璃）：
      内置 Mesh ≈ +52，浓度=浓 明显更高，浓度=关 接近 0。
    """
    from PIL import Image
    V.sh('shell', 'screencap', '-p', SHOT)
    local = os.path.join(os.environ.get('TEMP', r'C:\Windows\Temp'), '_mesh_shot.png')
    V.sh('pull', SHOT, local)
    try:
        img = Image.open(local).convert('RGB')
        w, h = img.size
        rs = gs = bs = n = 0
        for x in (4, 9, w - 6, w - 11):
            for i in range(12, 90):
                r, g, b = img.getpixel((x, int(h * i / 100.0)))
                rs += r; gs += g; bs += b; n += 1
        if n == 0:
            return None, None
        return ((bs - rs) / float(n), (rs // n, gs // n, bs // n))
    finally:
        try:
            os.remove(local)
        except OSError:
            pass


_SCREEN_H = []


def screen_h():
    """屏幕高度（tap 的越界判据要用真值：`关闭` 按钮的中心正好在 2200，
    写死 2200 会把它判成"越界"⇒ 点了没反应，第一版就栽在这上面）"""
    if not _SCREEN_H:
        m = re.search(r'(\d+)x(\d+)', V.out('shell', 'wm', 'size') or '')
        _SCREEN_H.append(int(m.group(2)) if m else 2400)
    return _SCREEN_H[0]


def screen_w():
    m = re.search(r'(\d+)x(\d+)', V.out('shell', 'wm', 'size') or '')
    return int(m.group(1)) if m else 1080


def tap_row(needle, tries=5):
    """点弹窗里的一行；**够不着就滚着找**。

    ★ 为什么需要它：自定义页套了 ScrollView（轮次55 修"最后几行被挤出屏幕"），
      靠后的行（`↺ 这一套全部恢复默认`）要滚动才看得见 —— 直接 tap_text 会点到
      屏幕外的坐标、**什么都不会发生**（第一版就是这么"看着点了其实没点"）。
    ★ 只点**完整可见**的行（`高度 > 20`）：被裁掉的那一行在 dump 里只剩 1px，
      点它等于点空白。
    """
    for _ in range(tries):
        for t, _d, _c, _ck, _chk, x0, y0, x1, y1 in V.nodes():
            if t and needle in t:
                cy = (y0 + y1) // 2
                if 120 < cy < screen_h() - 130 and (y1 - y0) > 20:
                    V.tap((x0 + x1) // 2, cy)
                    return True
        V.sh('shell', 'input', 'swipe', str(screen_w() // 2), '1800',
             str(screen_w() // 2), '1150', '300')
        time.sleep(1.2)
    return False


def tap_text(needle, exact=False):
    for t, _d, _c, _ck, _chk, x0, y0, x1, y1 in V.nodes():
        if not t:
            continue
        if (t == needle) if exact else (needle in t):
            ty = (y0 + y1) // 2
            if 120 < ty < screen_h() - 20:
                V.tap((x0 + x1) // 2, ty)
                return True
    return False


def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-40:])
        time.sleep(2)
    V.sh('shell', 'appops', 'set', PKG, 'SYSTEM_ALERT_WINDOW', 'allow')
    if 'root' not in V.out('shell', 'id'):
        V.sh('root')
        time.sleep(3)

    # 前置：只做玻璃态；清掉旧覆盖，保证入口是"默认"、初始色＝内置主色
    S.pref_set_in(UI_PREFS, 'ui_skin', '1')
    S.pref_set_in(UI_PREFS, 'ui_theme', '0')
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'rm', '-f', THEME_JSON)
    V.sh('shell', 'logcat', '-c')
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(6)

    for _ in range(3):
        ts = S.all_texts()
        if any('主题与显示' in t for t in ts):
            break
        if not tap_text('返回', exact=True):
            break
        time.sleep(1.4)
    check('★ 进到设置根（能看到「主题与显示」）',
          any('主题与显示' in t for t in S.all_texts()))
    check('★ 点开「主题与显示」', tap_text('主题与显示'))
    time.sleep(2.2)
    check('★★ 玻璃态下「自定义界面颜色」入口在',
          any('自定义界面颜色' in t for t in S.all_texts()))

    check('★★ 点开「自定义界面颜色」', tap_text('自定义界面颜色'))
    time.sleep(2.5)
    ts0 = S.all_texts()
    check('★ 页面列出主色调/底色/文字颜色', any('主色调' in t for t in ts0))
    # ★★ 轮次52：**实时预览卡**必须在（它是"所见即所得"的关键）
    need = ['预览（用当前配色画的真实控件，改完立刻变）', '标题示例', '正文示例',
            '次级提示', '主按钮', '次按钮']
    miss = [x for x in need if not any(x in (t or '') for t in ts0)]
    check('★★ 顶部有实时预览卡（标题/正文/次级 + 主次按钮都用当前配色画）', not miss, miss)

    check('★★ 点「主色调」弹出调色卡（这一步以前会被 dismiss 坑吃掉）', tap_text('主色调'))
    time.sleep(3.0)
    ts = S.all_texts()
    cur = parse_card(ts)
    check('★★ 调色卡真的渲染出来了（不是一串色值文字）', card_open(ts), cur)
    check('★★ 初始色＝当前主色（不是近黑/饱和 0 那个 bug 症状）',
          cur is not None and hex_close(cur[0], '#1D5AC5') and cur[2] > 50,
          cur)

    # ③ 点那一格红色的色卡
    sw = swatch_nodes(V.nodes())
    check('★★ 色卡是**看得见的色块**（dump 里数得出方形可点节点）', len(sw) >= 20,
          '实得 %d 格' % len(sw))
    target = [x for x in sw if x[3] == SWATCH_HEX]
    if target:
        _, sx, sy, _shx = target[0]
        V.tap(sx, sy)
        time.sleep(2.0)
        cur2 = parse_card(S.all_texts())
        check('★★ 点色卡即选中（读数变成那一格的颜色）',
              cur2 is not None and hex_close(cur2[0], SWATCH_HEX, 6), cur2)
    else:
        check('★★ 点色卡即选中（读数变成那一格的颜色）', False,
              '没找到 %s 那一格' % SWATCH_HEX)

    # ④ 确定 → 落地
    check('★ 点「确定」', tap_text('确定', exact=True))
    time.sleep(2.5)
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    check('★★ 自定义主色真的生效（DSTheme 日志里有 accent）',
          ('accent=#ff' + SWATCH_HEX[1:].lower()) in log.lower(),
          [l.split('DSTheme: ')[-1][:80] for l in log.splitlines() if 'DSTheme' in l][-1:]
          if 'DSTheme' in log else '无日志')
    ts = S.all_texts()
    check('★ 设置页那行显示"主色 #B3261E"', any(SWATCH_HEX.upper() in (t or '') for t in ts))
    # ★★ 轮次52：改完颜色**这一页应当自己回来**（= 预览是实时的），且行里已显示新主色
    ts2 = S.all_texts()
    check('★★ 改完颜色后自定义页自动重开（预览"实时"刷新）',
          any('预览（用当前配色画的真实控件，改完立刻变）' in (t or '') for t in ts2),
          [t[:20] for t in ts2[:6]])
    check('★ 重开后那一行已显示新主色',
          any(('主色调' in (t or '') and SWATCH_HEX.upper() in (t or '')) for t in ts2))

    # ④ ★★ 从背景图取色（P1）：塞一张**纯色**图当背景 ⇒ 四个候选都该是它
    print()
    print('== 从背景图取色（塞一张纯色图：纯色 ⇒ 主色/平均色/亮色/暗色 都该是它）==')
    try:
        from PIL import Image
        bg_local = os.path.join(os.environ['TEMP'], '_bg_solid.png')
        Image.new('RGB', (240, 240), (30, 136, 229)).save(bg_local)   # #1E88E5
        V.sh('shell', 'am', 'force-stop', PKG)
        time.sleep(1)
        X.push_owned(bg_local, '/data/data/%s/files/app_bg_image.img' % PKG)
        S.pref_set('app_image_bg', 'true')
        S.pref_set_in(UI_PREFS, 'ui_skin', '1')
        V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
        time.sleep(5)
        for _ in range(3):
            ts = S.all_texts()
            if any('主题与显示' in t for t in ts):
                break
            if not tap_text('返回', exact=True):
                break
            time.sleep(1.4)
        tap_text('主题与显示')
        time.sleep(2.2)
        tap_text('自定义界面颜色')
        time.sleep(2.5)
        check('★ 自定义页里有「从背景图取色」入口', tap_text('从背景图取色'))
        time.sleep(2.5)
        cand = bg_candidates(S.all_texts())
        check('★★ 给出四个候选（主色/平均色/亮色/暗色）', len(cand) == 4,
              '实得 %d 个：%s' % (len(cand), cand))
        check('★★ 纯色背景图 ⇒ 四个候选都等于那张图的颜色（解码→取像素→取色→界面 全链路）',
              len(cand) == 4 and all(hex_close(hx, '#1E88E5', 6) for _r, hx in cand),
              cand)
    except ImportError:
        check('★ PIL 可用（生成纯色测试图）', False, '缺 PIL')

    # ⑤ ★★ 配色方案导入（P1 · 轮次53）：走"从电脑导入"这条路（文件选择器没法自动化）
    print()
    print('== 导入配色方案（走"从电脑导入"）+ 顺带验校验闸 ==')
    EXT = '/storage/emulated/0/Android/data/%s/files' % PKG
    good = os.path.join(os.environ['TEMP'], 'DeepSeekPet配色-测试.json')
    bad = os.path.join(os.environ['TEMP'], 'not-a-theme.json')
    with open(good, 'w', encoding='utf-8') as f:
        f.write('{"version":1,"accent":"#B3261E","bg":"#1E88E5",'
                '"alpha":{"surface":0.6},"textTone":"dark"}')
    with open(bad, 'w', encoding='utf-8') as f:
        f.write('{"hello":1}')
    V.sh('shell', 'mkdir', '-p', EXT)
    for p in (good, bad):
        V.sh('push', p, EXT + '/' + os.path.basename(p))
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1)
    V.sh('shell', 'logcat', '-c')
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(5)
    for _ in range(3):
        ts = S.all_texts()
        if any('主题与显示' in t for t in ts):
            break
        if not tap_text('返回', exact=True):
            break
        time.sleep(1.4)
    tap_text('主题与显示')
    time.sleep(2.2)
    tap_text('自定义界面颜色')
    time.sleep(2.5)

    # ⑤-a 坏文件：必须被校验闸挡下（"这个文件不是配色方案"）
    tap_text('导入配色…')
    time.sleep(1.8)
    tap_text('从电脑导入')
    time.sleep(1.8)
    check('★ 「从电脑导入」列出了刚推进去的文件',
          any('not-a-theme' in (t or '') for t in S.all_texts()),
          [t[:22] for t in S.all_texts() if '.json' in (t or '')])
    tap_text('not-a-theme')
    time.sleep(2.0)
    ts = S.all_texts()
    check('★★ 坏文件被校验闸挡下（明说"不是配色方案"，没有静默套用）',
          any('不是配色方案' in (t or '') for t in ts),
          [t[:26] for t in ts[:5]])
    tap_text('知道了')
    time.sleep(1.5)

    # ⑤-b 好文件：确认框要摆出"里面是什么"，应用后 DSTheme 日志要对上
    if any('预览（用当前配色画的真实控件，改完立刻变）' in (t or '') for t in S.all_texts()):
        pass
    else:
        tap_text('自定义界面颜色')
        time.sleep(2.2)
    tap_text('导入配色…')
    time.sleep(1.8)
    tap_text('从电脑导入')
    time.sleep(1.8)
    tap_text('测试')
    time.sleep(2.2)
    ts = S.all_texts()
    check('★★ 确认框先摆出"里面是什么"（主色调/底色/透明度/文字）',
          any('主色调 #B3261E' in (t or '') for t in ts)
          and any('背景底色 #1E88E5' in (t or '') for t in ts),
          [t[:30] for t in ts if '#' in (t or '')][:4])
    check('★ 点「应用」', tap_text('应用', exact=True))
    time.sleep(2.5)
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    tail = [l.split('DSTheme: ')[-1] for l in log.splitlines() if 'DSTheme' in l]
    check('★★ 导入的配色真的生效（日志里 accent/bg 都是文件里的值）',
          any('accent=#ffb3261e' in t.lower() for t in tail)
          and any('bg=#ff1e88e5' in t.lower() for t in tail),
          tail[-1][:90] if tail else '无日志')
    ts = S.all_texts()
    check('★ 页面那行显示导入来的主色 #B3261E',
          any('#B3261E' in (t or '') for t in ts))
    # 清理推进去的测试文件
    V.sh('shell', 'rm', '-f', EXT + '/DeepSeekPet配色-测试.json', EXT + '/not-a-theme.json')

    # ⑥ ★★ 配色体检（P2 · 轮次54）：双向验证 —— 内置配色应当全达标；白字白底必须报不达标
    print()
    print('== 配色体检（双向：内置全达标 / 白字白底必报不达标）==')
    def audit_row():
        for t in S.all_texts():
            if t and '配色体检' in t:
                return t
        return None

    # a) 先清掉覆盖 ⇒ 回到内置玻璃浅色 ⇒ 应当全达标
    if any('预览（用当前配色画的真实控件，改完立刻变）' in (t or '') for t in S.all_texts()):
        pass
    else:
        tap_text('自定义界面颜色')
        time.sleep(2.2)
    # ★ 轮次55：这一行现在在列表末尾、要滚动才够得着 —— 原来直接 tap_text 会
    #   **静默失败**（覆盖层还在），而下面那条"全达标"照样绿（上一节的配色恰好也达标）
    #   ⇒ 一条假绿。改成 tap_row 并**把结果也断言上**。
    check('★★ 点「这一套全部恢复默认」真的点到了（末尾行要滚动）',
          tap_row('这一套全部恢复默认'))
    time.sleep(2.8)
    row = audit_row()
    check('★★ 内置配色：体检读数在、且**全达标**（有 ✓、没有"不达标"）',
          row is not None and '\u2713' in row and '不达标' not in row, row)

    # b) 塞一份"白字白底"的方案（对比度 1:1）⇒ 必须报不达标
    white = os.path.join(os.environ['TEMP'], 'white-on-white.json')
    with open(white, 'w', encoding='utf-8') as f:
        f.write('{"version":1,"bg":"#FFFFFF","textTone":"light"}')
    V.sh('push', white, EXT + '/white-on-white.json')
    time.sleep(0.5)
    if not any('预览（用当前配色画的真实控件，改完立刻变）' in (t or '') for t in S.all_texts()):
        tap_text('自定义界面颜色')
        time.sleep(2.2)
    tap_text('导入配色…')
    time.sleep(1.8)
    tap_text('从电脑导入')
    time.sleep(1.8)
    tap_text('white-on-white')
    time.sleep(2.0)
    tap_text('应用', exact=True)
    time.sleep(2.8)
    row2 = audit_row()
    check('★★ 白字白底：体检行立刻报"不达标"（读数真的在量，不是装饰）',
          row2 is not None and '不达标' in row2, row2)
    tap_text('配色体检')
    time.sleep(2.2)
    ts = S.all_texts()
    # ★ 报告是**一个多行 TextView**（不是每行一个节点）⇒ 判据要数 ⚠ 的**出现次数**，
    #   按"节点数"数会误判（第一版就是这么错的）
    joined = '\n'.join(t or '' for t in ts)
    check('★★ 体检报告逐条列出、且有不达标项（⚠ ≥3 条）', joined.count('\u26A0') >= 3,
          '⚠ 出现 %d 次' % joined.count('\u26A0'))
    check('★ 报告给了**可操作**的建议（调透明度 / 换底色，而不是只报错）',
          '建议把卡片透明度调高' in joined)
    V.sh('shell', 'rm', '-f', EXT + '/white-on-white.json')

    # ⑦ ★★ Mesh 光斑可调（P2 · 轮次55）
    #
    # 双向判据：
    #   ① **像素**：光斑"关"vs"浓"，页面背景的 B−R 位移差要够大（光斑真的画出来了）；
    #   ② **读数**：体检那一行的读数必须**跟着变** —— 这正是本轮把口径从"卡片叠底色"
    #      升级成"卡片叠**光斑极值**"的证据（旧口径下这两个数会一模一样）。
    print()
    print('== Mesh 光斑（浓度 关/浓：像素位移 + 体检读数跟着变）==')
    tap_text('知道了')          # 关掉上一节的体检报告
    time.sleep(1.4)
    # ★★ 前置：把上一节塞进去的**图片背景**关掉 —— 图片/视频背景会把 Mesh 整个顶掉
    #    （渲染层的判据是 `Prefs.activeBgKind()`：有背景就 `root.setBackground(null)`，
    #     没背景才铺 Mesh）。不关的话"光斑关/浓"量到的是同一张图、读数一模一样
    #    （第一版就是这么假红过一次：关=52 / 浓=52）。
    #    ★★ 注意 `pref_set` 是"force-stop → 改 XML → 复读校验"⇒ **App 会被杀掉**，
    #       所以下面必须重新拉起并一路导航回自定义页（第一版忘了这步：
    #       后面所有 tap 都点在桌面上，最后量到的是桌面壁纸）。
    S.pref_set('app_image_bg', 'false')
    S.pref_set('app_video_bg', 'false')
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(6)
    for _ in range(3):
        if any('主题与显示' in (t or '') for t in S.all_texts()):
            break
        if not tap_text('返回', exact=True):
            break
        time.sleep(1.4)
    tap_text('主题与显示')
    time.sleep(2.2)
    tap_text('自定义界面颜色')
    time.sleep(2.5)
    # ★ 这一行在列表末尾，要**滚动**才够得着（见 tap_row 的注释）
    check('★ 清掉上一节的覆盖（滚到最后一行点「这一套全部恢复默认」）',
          tap_row('这一套全部恢复默认'))
    time.sleep(2.8)
    check('★ 自定义页有「✨ Mesh 光斑」入口行',
          any('Mesh 光斑' in (t or '') for t in S.all_texts()),
          [t[:24] for t in S.all_texts() if '光斑' in (t or '')])

    def mesh_row():
        """自定义页那一行的原文（例：`✨ Mesh 光斑：浓 · 跟随主色`）。

        ★ 必须按**整行前缀**判，不能用 `'关' in 页面文字` —— 页面上还有「关闭」按钮，
          那种写法在"浓度=关"这一步会**假绿**（弱断言，反向套件的教训）。
        """
        for t in S.all_texts():
            if t and 'Mesh 光斑：' in t:
                return t
        return None

    check('★ 内置态那一行显示"标准"', 'Mesh 光斑：标准' in (mesh_row() or ''), mesh_row())

    def open_custom_page():
        if not any('预览（用当前配色画的真实控件，改完立刻变）' in (t or '')
                   for t in S.all_texts()):
            tap_text('自定义界面颜色')
            time.sleep(2.2)

    def measure(tag):
        """关掉自定义页 → 量干净的页面背景 → 再回到自定义页（继续下一步）

        ★ 必须**关掉弹窗**再量：弹窗的 dim 会把页面压暗（实测内置 Mesh 的 B−R
          从 +52 掉到 +21），信号被吃掉一大半。
        """
        ok = tap_text('关闭', exact=True)
        time.sleep(1.8)
        d = page_tint()
        open_custom_page()
        return d, ok

    def set_mesh(kind_label, value_label):
        """走**用户真走的那条路**：✨ Mesh 光斑 → 某一组 → 某个档位

        ★ 档位一律用 `exact=True`：`浓` 这个字在**父弹窗那一行**里也有
          （"光斑浓度：关（越**浓**越花…）"），子串匹配会点回父弹窗那一行
          ⇒ 看着"点了"，其实什么都没改（第一版就是这么假红的）。
        """
        tap_text('Mesh 光斑：')       # 用带冒号的行首锚点，不会误中弹窗标题
        time.sleep(1.8)
        if not tap_text(kind_label):
            return False
        time.sleep(1.8)
        ok = tap_text(value_label, exact=True)
        time.sleep(2.6)
        return ok

    base_t, base_c = measure('标准')
    check('★ 内置 Mesh 下量到页面背景', base_t is not None,
          'B−R=%s %s' % (base_t, base_c))

    # a) 浓度 = 关
    check('★ 点「Mesh 光斑」→「光斑浓度」→「关」', set_mesh('光斑浓度', '关（不要光斑）'))
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    check('★★ 浓度"关"真的生效（日志 mesh=0.00x1.00/builtin）',
          'mesh=0.00x1.00/builtin' in log,
          [l.split('DSTheme: ')[-1][-60:] for l in log.splitlines() if 'DSTheme' in l][-1:]
          if 'DSTheme' in log else '无日志')
    check('★ 那一行显示"关"', 'Mesh 光斑：关' in (mesh_row() or ''), mesh_row())
    off_row = audit_row()
    (off_t, off_c), closed = measure('关')
    check('★ 点「关闭」能关掉这一页（叠层 bug 修好后一次就够）', closed)

    # b) 浓度 = 浓
    check('★ 点「Mesh 光斑」→「光斑浓度」→「浓」', set_mesh('光斑浓度', '浓'))
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    check('★★ 浓度"浓"真的生效（日志 mesh=1.40x1.00/builtin）',
          'mesh=1.40x1.00/builtin' in log,
          [l.split('DSTheme: ')[-1][-60:] for l in log.splitlines() if 'DSTheme' in l][-1:]
          if 'DSTheme' in log else '无日志')
    thick_row = audit_row()
    (thick_t, thick_c), _ = measure('浓')

    delta = None if (off_t is None or thick_t is None) else (thick_t - off_t)
    check('★★ 光斑"关"vs"浓"：页面背景的平均 B−R 位移差 ≥ 25'
          '（光斑真的在渲染上变了，不是只有一行文字）',
          mesh_delta_ok(delta),
          '关=%s %s / 浓=%s %s' % (off_t, off_c, thick_t, thick_c))

    # c) 读数必须跟着变 —— 这是"体检口径升级"的直接证据
    r_off, r_thick = audit_ratio(off_row), audit_ratio(thick_row)
    check('★★ 体检读数**跟着光斑变**（旧口径只量底色 ⇒ 这两个数会一模一样）',
          r_off is not None and r_thick is not None and r_thick < r_off,
          '关=%s / 浓=%s' % (off_row, thick_row))
    check('★★ 内置配色在"光斑极值"这个更严的口径下**依然全达标**',
          off_row is not None and '\u2713' in off_row and '不达标' not in off_row, off_row)

    # d) 光斑颜色跟随主色
    tap_text('Mesh 光斑：')
    time.sleep(1.8)
    tap_text('光斑颜色')
    time.sleep(1.8)
    check('★ 点「光斑颜色」→「跟随主色」', tap_text('跟随主色', exact=True))
    time.sleep(2.6)
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    check('★★ 跟随主色真的生效（日志 mesh=…/accent）',
          'mesh=1.40x1.00/accent' in log,
          [l.split('DSTheme: ')[-1][-60:] for l in log.splitlines() if 'DSTheme' in l][-1:]
          if 'DSTheme' in log else '无日志')
    check('★ 那一行显示"跟随主色"',
          '· 跟随主色' in (mesh_row() or ''), mesh_row())

    # e) 全部恢复默认 → 回到内置
    #    ★ 先清 logcat：**恢复默认后 apply 会提前返回 ⇒ 一行 DSTheme 都不该有**
    #      （这正是"零变化承诺"的设备侧证据；用 `-d` 不清的话会读到上面几步的旧日志）
    V.sh('shell', 'logcat', '-c')
    tap_text('Mesh 光斑：')
    time.sleep(1.8)
    check('★ 点「↺ 全部恢复默认」', tap_text('全部恢复默认'))
    time.sleep(2.8)
    log = V.out('shell', 'logcat', '-d', '-s', 'DSTheme:*')
    check('★★ 恢复默认后**不再**打"自定义配色生效"（覆盖层为空 ⇒ apply 提前返回，0 行日志）',
          '自定义配色生效' not in log,
          [l.split('DSTheme: ')[-1][-60:] for l in log.splitlines() if 'DSTheme' in l][:2]
          if 'DSTheme' in log else '0 行（符合预期）')
    check('★ 那一行回到"标准"', 'Mesh 光斑：标准' in (mesh_row() or ''), mesh_row())

    # f) ★★ 叠层回归（轮次55 设备实测抓到的真 bug）：
    #    改完档位后"一次「关闭」"就该回到设置页 —— 修之前要点**两次**
    #    （旧的那一层自定义页从没被 dismiss，一直压在新页下面）。
    closed = tap_text('关闭', exact=True)
    time.sleep(1.8)
    ts = S.all_texts()
    check('★★ 改完档位后一次「关闭」就回到设置页（不再叠层）',
          closed and not any('预览（用当前配色画的真实控件' in (t or '') for t in ts)
          and any('主题与显示' in (t or '') for t in ts),
          [t[:20] for t in ts[:4]])

    S.cleanup_device_temps()
    pf.wipe_local()
    print()
    print('=' * 62)
    if fails:
        print('%d 项失败：' % len(fails))
        for f in fails:
            print('   - ' + f)
        return 1
    print('ALL COLOR-CARD DEVICE CHECKS PASSED'
          '（调色卡 / 初始色 / 24 格色卡 / 点卡即选 / 确定落地 / 预览卡 / 改完自动重开 /'
          ' 从背景图取色 / 导入配色+校验闸 / 配色体检双向 / Mesh 光斑双向）')
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
