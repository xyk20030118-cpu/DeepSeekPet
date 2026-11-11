# -*- coding: utf-8 -*-
"""设备验证：P5「形象来源纳入角色模型」在**模拟器**上真的可用。

★ 轮次㉞：本脚本按轮次㉛ 的**新 UI** 重写。
  旧版驱动的是设置页上「🎨 形象来源：X ›」那一行与它下面那几行导入入口 ——
  轮次㉛ 把它们**全收进了「🎭 角色」→「🎨 形象（当前角色）」**，
  而且**一个预置角色都没有了**（没有角色时连"形象"这一项都不列）。
  ⇒ 旧版跑必红。那是**脚本过时**，不是产品 bug（旧版文件头就写着这句，见 STATE.md 待办 0m ④）。

为什么需要它：P5 改的是"这只桌宠长什么样"的取数路径（设置页 → 文件 → 悬浮窗装配），
源码级断言再多也证明不了"点了真的会变"。这里把它走成可复跑的脚本，**只看模拟器**
（用户真机不装包，见铁律）。

覆盖（页面文字 + 落盘文件 + logcat 三处交叉验证）：
  ① 清态后**一个角色都没有**：设置页那一行是引导语，角色中心**不列**「形象」；
  ② 「➕ 新增角色」建角色 A ⇒ 角色中心那一行变成「形象（当前角色）：内置角色包 ›」；
  ③ 形象清单：五种来源齐全、● 落在当前来源上、没素材的标「（未导入）」；
  ④ 点"还没导入"的那一项 ⇒ **只提示、不改设置**（连 appearance.json 都不生成）；
  ⑤ 在 A 上传图片 ⇒ 落进 **A 自己的** `appearance/<A>/image.png`，来源变 image；
  ⑥ ★ 按角色隔离：新增角色 B ⇒ 它还是「内置角色包」，且**没有** A 那份 image.png；
  ⑦ 在 B 上传图片 ⇒ 落 B 自己的目录（两个角色各一份，互不覆盖）；
  ⑧ ★「恢复默认形象」只清**当前角色**：B 的目录被清掉、A 那份**还在**；
  ⑨ 切回 A ⇒ 它的形象还在（两个角色互不串味）；
  ⑩ ★ 升级路径（真实场景）：**没有角色**时老的全局形象被搬进哨兵目录
     `appearance/builtin_default/`，旧位置的文件搬走（不会下次启动又搬一次）；
  ⑪ ★ 升级路径（界面可见的那半）：**已有角色但还没配过形象**时，
     老的全局形象搬进**那个角色**的目录，角色中心那一行跟着变「动图」。

★ 四个坑（都写进判据里了）：
  1. **非 BMP emoji 在 uiautomator 的 XML 里是数字实体**（`🎨` → `&#127912;`），
     判据一律只用中文部分（`形象（当前角色）`），别拿 emoji 去比；
  2. **对话框背后是设置页**：空态引导语里就带「新增角色」、设置页那一行带角色名 ——
     按"包含"匹配会点到背景上（表现为"点了没反应"）⇒ 判据一律用
     `verify_char_switch_ui.tap_new_item`（相对打开前的**差集**）；
  3. 改 pref 必须走**可靠手法**（force-stop → 改 XML → chown/chmod/restorecon → 复读校验），
     不能用 sed（键不存在时静默不替换）—— 统一走 `S.pref_set`；
  4. 迁移发生在 **FloatingService.onCreate**，所以必须冷启动（force-stop 后再 start），
     并且要在启动**前**清 logcat，否则读到的是上一轮的日志。

用法：
    python build/verify_appearance_ui.py --install     # 装 dist 里的开发版再跑
    python build/verify_appearance_ui.py               # 设备上已有包，直接跑
    python build/verify_appearance_ui.py --selftest    # 不连设备，只验本脚本的判读逻辑

★ 铁律：**永远只认 `-s emulator-5554`**（本机还接着用户的真机 <设备序列号>）。
"""
import os
import re
import sys
import time

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pf                            # noqa: E402  本地 pref 副本的擦除（防 Key 进源码包）
import verify_char_ui as V           # noqa: E402  设备驱动与判读工具
import verify_char_switch_ui as S    # noqa: E402  角色中心导航 / 造角色 / 可靠改 pref

DEV = V.DEV
PKG = V.PKG
FILES = '/data/data/%s/files' % PKG
APPEAR = FILES + '/appearance'
# ★ 哨兵目录名 = ActionMenuConfig.sanitize("builtin:default")：'：' 被换成 '_'。
#   "没有角色"时形象就落在这里（素材层对"没有目录"是内置兜底，但形象是**按 id** 存的）。
SENTINEL_DIR = APPEAR + '/builtin_default'
CHAR_A = 'appeara'
CHAR_B = 'appearb'
CHAR_C = 'appearc'
WORK = r'D:\petport_lab\petbuild\build\_uitest'

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

def parse_source(json_text):
    """从 appearance.json 文本里取 source（坏 json / 缺字段 ⇒ None）"""
    m = re.search(r'"source"\s*:\s*"([^"]*)"', json_text or '')
    return m.group(1) if m else None


def has_unimported_mark(row_text):
    return '（未导入）' in (row_text or '')


def hub_appearance_label(rows):
    """从角色中心那一行取来源短名：`形象（当前角色）：图片 ›` ⇒ `图片`（没有 ⇒ None）。"""
    for t in rows or []:
        if '形象（当前角色）：' in t:
            return t.split('形象（当前角色）：', 1)[1].replace('\u203A', '').strip()
    return None


def is_appearance_title(t):
    """形象清单的标题：`🎨 形象（角色：appeara）`（emoji 在 dump 里是数字实体）。"""
    return bool(t) and '形象（角色：' in t


def selftest():
    """不连设备：验本脚本自己的判读逻辑（正例 + 反例）。"""
    print('== --selftest：判读逻辑 ==')
    ok = True

    def t(name, cond):
        nonlocal ok
        print('  [%s] %s' % ('OK' if cond else 'FAIL', name))
        ok = ok and cond

    t('appearance.json 读 source',
      parse_source('{"version":1,"source":"image"}') == 'image')
    t('source 是 anim 也读得出', parse_source('{"source":"anim"}') == 'anim')
    t('空 / 坏 json 安全返回 None',
      parse_source('') is None and parse_source('{bad') is None and parse_source(None) is None)
    t('反例：缺 source 字段 ⇒ None', parse_source('{"version":1}') is None)

    t('角色中心那一行取出来源短名',
      hub_appearance_label(['\U0001F3A8 形象（当前角色）：内置角色包 \u203A']) == '内置角色包')
    t('带括号的来源名也取得对（Live2D 那种）',
      hub_appearance_label(['\U0001F3A8 形象（当前角色）：Live2D \u203A']) == 'Live2D')
    t('反例：清单里没有那一行 ⇒ None',
      hub_appearance_label(['\u25CF 内置角色包（当前）', '\u2795 新增角色…']) is None)

    t('「（未导入）」标记认得出',
      has_unimported_mark('\u25CB Live2D（未导入）')
      and not has_unimported_mark('\u25CF 内置角色包（当前）'))
    t('形象清单标题认得出（不看 emoji）',
      is_appearance_title('\U0001F3A8 形象（角色：appeara）')
      and not is_appearance_title('形象（当前角色）：内置角色包 \u203A'))
    t('★ 本脚本只认模拟器（不许往用户真机装包）', DEV == 'emulator-5554')
    print('SELFTEST ' + ('PASSED' if ok else 'FAILED'))
    return 0 if ok else 1


# ═══════════════ 设备操作 ═══════════════

def root_ok():
    if 'root' not in V.out('shell', 'id'):
        print('== adb root ==')
        V.sh('root')
        time.sleep(2)
    return 'root' in V.out('shell', 'id')


def ls(path):
    return V.out('shell', 'ls', path).strip()


def cat(path):
    return V.out('shell', 'cat', path)


def source_of(cid):
    """某个角色当前配置的来源（读不到 ⇒ None）"""
    return parse_source(cat('%s/%s/appearance.json' % (APPEAR, cid)))


def open_appearance_picker():
    """角色中心 → 「🎨 形象（当前角色）：…」→ 形象清单。返回 (是否打开, 打开前的文字集合)。"""
    ok, before = S.open_hub()
    if not ok:
        return False, before
    if not S.tap_new_item('形象（当前角色）', before):
        return False, before
    if not V.wait_text('形象（角色：', 10):
        return False, before
    return True, before


def close_appearance():
    """关掉形象清单（BACK），免得后面的点击都打在对话框上。"""
    V.sh('shell', 'input', 'keyevent', '4')
    time.sleep(1.0)


def tap_appearance_item(needle, before):
    """点形象清单里的一行（同样只认**新出现**的那些行）。"""
    return S.tap_new_item(needle, before)


def photo_picker_select_photo():
    """在 **Photo Picker** 里选第一张**静态照片**。

    ★★ 这是本脚本第二次跑才发现的坑（既有设备脚本没踩到，因为它们走的是
      `ACTION_OPEN_DOCUMENT`）：`pickImage()` 用的是 `ACTION_GET_CONTENT` + `image/*`，
      而 **Android 13+ 会把这类请求转给系统的 Photo Picker**（"Photos / Albums" 那套 UI），
      **不是** DocumentsUI 的 "Images on …"。所以 `verify_char_ui.picker_select`
      （只认 `Images on` / `Show roots`）在这里永远返回 `picker-not-open`。

    ★ 只挑 content-desc 以 `Photo taken on` 开头的图块，**跳过 `GIF taken on`**：
      GIF 会被判成动图来源（`source=anim`），而本节要验的是"静态图片落进这个角色的目录"。
    """
    if not V.wait_text('Photos', 12) and not V.wait_desc('Albums', 6):
        return 'picker-not-open'
    # 首次会有一张一次性说明条：「… will only have access to the photos you select」+ Dismiss
    if V.has_text('Dismiss'):
        V.tap_text_exact('Dismiss')
        time.sleep(1.2)
    t0 = time.time()
    while time.time() - t0 < 15:
        for t, d, c, ck, chk, x0, y0, x1, y1 in V.nodes():
            if d.startswith('Photo taken on'):
                V.tap((x0 + x1) // 2, (y0 + y1) // 2)
                time.sleep(3)
                return 'ok'
        time.sleep(0.8)
    return 'no-photo-tile'


def close_picker_if_open():
    """兜底：选择器/对话框还开着就按 BACK 关掉（否则后面所有点击都打在它身上）。"""
    for _ in range(3):
        ds = V.descs()
        if any(d == 'Show roots' for d in ds) or any(d.startswith('Photo taken on') for d in ds):
            V.sh('shell', 'input', 'keyevent', '4')
            time.sleep(1.2)
            continue
        return


def upload_photo_to_current():
    """在当前角色的「形象」里走一遍：上传图片 → 相册选 1 张静态图。返回说明字符串。"""
    ok, before = open_appearance_picker()
    if not ok:
        return 'no-picker'
    if not tap_appearance_item('上传图片', before):
        return 'no-upload-item'
    r = photo_picker_select_photo()
    time.sleep(5)
    return r


def push_into_files(local, remote):
    """把一个文件推进 App 私有目录并还原 owner（迁移用的"老全局形象"）。"""
    V.sh('push', local, remote)
    owner = V.out('shell', 'stat', '-c', '%U:%G', FILES).strip()
    if owner:
        V.sh('shell', 'chown', owner, remote)
    V.sh('shell', 'chmod', '600', remote)
    V.sh('shell', 'restorecon', remote)
    return V.out('shell', 'ls', remote).strip() != ''


def cold_start():
    """冷启动（迁移发生在 FloatingService.onCreate，必须真的重启进程）。

    ★ 两道保险（第二次跑就是在这里翻车的）：
      ① **先唤醒 + 解锁**：模拟器闲置一段时间后会灭屏、App 被系统收掉回到桌面，
         `am start` 在那种状态下可能起不来 —— 而后面所有点击都落在桌面上，
         报出来的却是"那一行找不到"（排查方向被带偏）；
      ② **起来之后轮询确认进程与顶层窗口**，没起来就再拉一次（最多 3 次）。
         不确认的话，"App 没起来"会被下游断言说成"UI 变了"。
    """
    V.sh('shell', 'input', 'keyevent', '224')       # KEYCODE_WAKEUP
    V.sh('shell', 'wm', 'dismiss-keyguard')
    V.sh('shell', 'am', 'force-stop', PKG)
    time.sleep(1.5)
    V.sh('shell', 'logcat', '-c')
    for _ in range(3):
        V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
        for _ in range(20):
            time.sleep(0.8)
            if V.out('shell', 'pidof', PKG).strip():
                if 'com.deepseek.pet/com.deepseek.pet.MainActivity' in V.out(
                        'shell', 'dumpsys', 'window', 'windows'):
                    time.sleep(2)
                    return True
        time.sleep(1.5)
    return False


def logcat():
    return V.out('logcat', '-d')


def clear_appearance():
    V.sh('shell', 'rm', '-rf', APPEAR)
    V.sh('shell', 'rm', '-f', FILES + '/custom_pet.png')
    V.sh('shell', 'rm', '-f', FILES + '/custom_pet_anim.gif')
    V.sh('shell', 'rm', '-rf', FILES + '/custom_pet_model')
    V.sh('shell', 'rm', '-rf', FILES + '/custom_l2d_model')


def make_legacy_image():
    from PIL import Image, ImageDraw
    os.makedirs(WORK, exist_ok=True)
    path = os.path.join(WORK, 'p5legacy.png')
    im = Image.new('RGBA', (200, 200), (240, 120, 60, 255))
    ImageDraw.Draw(im).ellipse([30, 30, 170, 170], fill=(255, 255, 255, 230))
    im.save(path)
    return path


def make_legacy_anim():
    from PIL import Image, ImageDraw
    os.makedirs(WORK, exist_ok=True)
    path = os.path.join(WORK, 'p5legacy.gif')

    def frame(color, label):
        im = Image.new('RGBA', (120, 120), color)
        ImageDraw.Draw(im).ellipse([20, 20, 100, 100], fill=(255, 255, 255, 220))
        return im
    f1, f2 = frame((60, 200, 120), '1'), frame((230, 190, 60), '2')
    f1.save(path, save_all=True, append_images=[f2], duration=250, loop=0)
    return path


# ═══════════════ 主流程 ═══════════════

def main():
    if '--install' in sys.argv:
        print('== 装开发版 ==')
        print(V.sh('install', '-r', V.DEV_APK).stdout.decode('utf-8', 'replace').strip()[-80:])
        time.sleep(2)

    if not root_ok():
        print('!! 拿不到 root，读不了 filesDir（换 google_apis 镜像或先 adb root）')
        return 1

    print()
    print('== 一、准备：造测试图 + 清态（形象目录 / 老全局形象 / 角色清空）==')
    names = V.make_media()
    V.push_media(names)
    # ★ 先起一次 App，确保 pref 文件存在（pref_set 要基于它改；全新安装时还没有这个文件）
    V.sh('shell', 'am', 'start', '-n', PKG + '/.MainActivity')
    time.sleep(3)
    V.sh('shell', 'rm', '-rf', FILES + '/characters')
    V.sh('shell', 'rm', '-rf', FILES + '/scenes')
    clear_appearance()
    ok_pref = S.pref_set('current_character', 'builtin:default')
    S.pref_set('use_custom_image', 'false')
    S.pref_set('use_custom_anim', 'false')
    S.pref_set('use_custom_model', 'false')
    S.pref_set('use_custom_l2d', 'false')
    ok_cold = cold_start()
    check('App 冷启动成功（进程 + 顶层窗口都在）', ok_cold)
    check('清态后没有任何形象目录', ls(APPEAR) == '', 'ls appearance = %r' % ls(APPEAR))
    check('当前角色回到哨兵（一个角色都没有）', ok_pref, 'current_character=%s'
          % S.pref('current_character'))

    print()
    print('== 二、★ 空态：一个角色都没有时**没有**形象入口（轮次㉛ 语义）==')
    if not S.goto_char_row():
        skip('空态那一行', '进不了「形象与动作」页 / 滚不到「角色：」')
        return finish()
    row = S.char_row_of(S.all_texts())
    check('★ 设置页那一行是引导语（不是角色名）', S.is_empty_row(row), row)
    ok, before = S.open_hub()
    if not ok:
        check('空态也能打开角色中心', False)
        return finish()
    ts = S.new_texts(before)
    check('中心标题报「角色（共 0 个）」', any('角色（共 0 个）' in t for t in ts),
          ' / '.join(t for t in ts if '角色（共' in t))
    check('★ 不列「形象（当前角色）」（没有角色就谈不上形象）',
          hub_appearance_label(ts) is None, ' / '.join(ts))
    S.close_dialog()

    print()
    print('== 三、新增角色 A ⇒ 角色中心那一行才有形象入口 ==')
    r = S.create_character(CHAR_A)
    check('走「角色：」→ 角色中心 → ➕ 新增角色 → 创建（%s）' % r, r == 'ok')
    if r != 'ok':
        return finish()
    time.sleep(2)
    check('建完立刻切成当前角色', S.pref('current_character') == CHAR_A,
          'current_character=%s' % S.pref('current_character'))
    ok, before = S.open_hub()
    if not ok:
        check('打开角色中心', False)
        return finish()
    ts = S.new_texts(before)
    check('★ 中心那一行是「形象（当前角色）：内置角色包 ›」',
          hub_appearance_label(ts) == '内置角色包', hub_appearance_label(ts))

    print()
    print('== 四、形象清单：五种来源 + ● 当前 + 未导入标记 ==')
    if not S.tap_new_item('形象（当前角色）', before):
        check('点开形象清单', False)
        return finish()
    if not V.wait_text('形象（角色：', 10):
        check('弹开形象清单', False)
        return finish()
    ts = S.new_texts(before)
    check('标题报出当前角色（形象（角色：%s））' % CHAR_A,
          any(is_appearance_title(t) and CHAR_A in t for t in ts),
          ' / '.join(t for t in ts if '形象（角色：' in t))
    for label in ('内置角色包', '图片', '动图', '模型包', 'Live2D'):
        check('清单里有「%s」' % label,
              any(t.startswith('\u25CF ' + label) or t.startswith('\u25CB ' + label)
                  for t in ts),
              ' / '.join(t for t in ts if label in t))
    cur_rows = [t for t in ts if t.startswith('\u25CF ')]
    check('★ ● 落在「内置角色包」上（当前来源只有一个）',
          len(cur_rows) == 1 and '内置角色包' in cur_rows[0],
          '● 行=%s' % cur_rows)
    unimported = [t for t in ts if has_unimported_mark(t)]
    check('★ 四种没素材的都标「（未导入）」（列出来但不隐藏）',
          len(unimported) == 4, '未导入行=%s' % unimported)

    print()
    print('== 五、点"还没导入"的那一项 ⇒ 只提示、不改设置 ==')
    V.sh('logcat', '-c')
    if not tap_appearance_item('图片（未导入）', before) and not tap_appearance_item('图片', before):
        skip('点未导入项', '清单里点不到「图片」')
    else:
        time.sleep(2)
        lg = logcat()
        check('给了"先去导入"的提示（日志里没有切换记录）',
              '形象来源 ->' not in lg)
        check('★ 设置没被改动（连 appearance.json 都没生成）',
              source_of(CHAR_A) is None and ls('%s/%s' % (APPEAR, CHAR_A)) == '',
              'appearance.json source=%s / ls=%r' % (source_of(CHAR_A), ls('%s/%s' % (APPEAR, CHAR_A))))
    close_appearance()

    print()
    print('== 六、在角色 A 上传图片 ⇒ 落进**它自己**的目录 ==')
    r = upload_photo_to_current()
    check('相册选 1 张静态照片（%s）' % r, r == 'ok')
    if r != 'ok':
        close_picker_if_open()
        return finish()
    check('★ 素材落到 appearance/%s/image.png' % CHAR_A,
          'image.png' in ls('%s/%s' % (APPEAR, CHAR_A)),
          'ls=%r' % ls('%s/%s' % (APPEAR, CHAR_A)))
    check('★ appearance.json 里 source=image', source_of(CHAR_A) == 'image',
          cat('%s/%s/appearance.json' % (APPEAR, CHAR_A)).strip())
    ok, before = S.open_hub()
    if not ok:
        skip('上传后角色中心那一行', '打不开角色中心')
    else:
        check('★ 中心那一行跟着变「图片」', hub_appearance_label(S.new_texts(before)) == '图片',
              hub_appearance_label(S.new_texts(before)))
        S.close_dialog()

    print()
    print('== 七、★ 按角色隔离：新增角色 B ==')
    r = S.create_character(CHAR_B)
    check('新增角色 B（%s）' % r, r == 'ok')
    if r != 'ok':
        return finish()
    time.sleep(2)
    ok, before = S.open_hub()
    if not ok:
        check('打开角色中心', False)
        return finish()
    check('★ B 还是「内置角色包」（没继承 A 的图片）',
          hub_appearance_label(S.new_texts(before)) == '内置角色包',
          hub_appearance_label(S.new_texts(before)))
    S.close_dialog()
    check('★ B 自己的形象目录里没有 A 那份 image.png',
          'image.png' not in ls('%s/%s' % (APPEAR, CHAR_B)),
          'ls %s=%r' % (CHAR_B, ls('%s/%s' % (APPEAR, CHAR_B))))
    check('（A 那份还在）', 'image.png' in ls('%s/%s' % (APPEAR, CHAR_A)))

    print()
    print('== 八、在角色 B 上传图片 ⇒ 两个角色各一份，互不覆盖 ==')
    r = upload_photo_to_current()
    check('相册选 1 张静态照片（%s）' % r, r == 'ok')
    if r != 'ok':
        close_picker_if_open()
        return finish()
    check('★ 落进 B 自己的目录', 'image.png' in ls('%s/%s' % (APPEAR, CHAR_B)),
          'ls=%r' % ls('%s/%s' % (APPEAR, CHAR_B)))
    check('★ B 的 source=image', source_of(CHAR_B) == 'image',
          cat('%s/%s/appearance.json' % (APPEAR, CHAR_B)).strip())
    check('★ A 那份没被覆盖（两个角色各一份）',
          'image.png' in ls('%s/%s' % (APPEAR, CHAR_A)))

    print()
    print('== 九、★「恢复默认形象」只清**当前角色** ==')
    close_picker_if_open()      # 上一节若没选成功，选择器还开着 —— 后面的点击全会打在它身上
    ok, before = open_appearance_picker()
    if not ok:
        skip('恢复默认形象', '点不开形象清单')
    elif not tap_appearance_item('恢复默认形象', before):
        skip('恢复默认形象', '清单里点不到「恢复默认形象」')
    else:
        check('先弹确认框（不直接清）', S.wait_text_long('其他角色的形象不受影响', 10))
        if V.tap_text_exact('恢复'):
            time.sleep(3)
            check('★ B 的形象目录被清掉', ls('%s/%s' % (APPEAR, CHAR_B)) == '',
                  'ls %s=%r' % (CHAR_B, ls('%s/%s' % (APPEAR, CHAR_B))))
            check('★ A 那份**没被牵连**（只清当前角色）',
                  'image.png' in ls('%s/%s' % (APPEAR, CHAR_A)),
                  'ls %s=%r' % (CHAR_A, ls('%s/%s' % (APPEAR, CHAR_A))))
            ok, before = S.open_hub()
            if ok:
                check('B 那一行回「内置角色包」',
                      hub_appearance_label(S.new_texts(before)) == '内置角色包',
                      hub_appearance_label(S.new_texts(before)))
                S.close_dialog()

    print()
    print('== 十、切回角色 A ⇒ 它的形象还在（互不串味）==')
    if S.pick_character(CHAR_A):
        time.sleep(3)
        check('pref 回到 A', S.pref('current_character') == CHAR_A,
              'current_character=%s' % S.pref('current_character'))
        ok, before = S.open_hub()
        if not ok:
            skip('切回 A 后那一行', '打不开角色中心')
        else:
            check('★ A 仍是「图片」', hub_appearance_label(S.new_texts(before)) == '图片',
                  hub_appearance_label(S.new_texts(before)))
            S.close_dialog()
        check('★ 装配日志确认用的是 A + image',
              ('applyPetDrawable char=' + CHAR_A) in logcat() and 'mode=image' in logcat(),
              [l for l in logcat().splitlines() if 'applyPetDrawable' in l][-1:])
    else:
        skip('切回角色 A', '角色清单里点不到')

    print()
    print('== 十一、★ 升级路径（真实场景）：没有角色时老形象落到哨兵目录 ==')
    S.pref_set('current_character', 'builtin:default')
    clear_appearance()
    ok_push = push_into_files(make_legacy_image(), FILES + '/custom_pet.png')
    ok_pref = S.pref_set('use_custom_image', 'true')
    check('前置：老全局形象已就位（files/custom_pet.png + use_custom_image=true）',
          ok_push and ok_pref, 'push=%s pref=%s' % (ok_push, ok_pref))
    if not cold_start():
        check('冷启动（迁移那一次）', False)
        return finish()
    lg = logcat()
    check('★ 迁移日志（迁移老形象 -> char=builtin:default）',
          '迁移老形象 -> char=builtin:default' in lg,
          [l for l in lg.splitlines() if '迁移老形象' in l][:1])
    check('★ 素材落到 files/appearance/builtin_default/image.png',
          'image.png' in ls(SENTINEL_DIR), 'ls=%r' % ls(SENTINEL_DIR))
    check('★ appearance.json 里 source=image', source_of('builtin_default') == 'image',
          cat(SENTINEL_DIR + '/appearance.json').strip())
    check('★ 旧位置的文件已经搬走（不会下次启动又搬一次）',
          V.out('shell', 'ls', FILES + '/custom_pet.png').strip() == '')
    check('★ 装配日志：用的就是这个（没有角色的）id + image',
          ('applyPetDrawable char=builtin:default' in lg and 'mode=image' in lg),
          [l for l in lg.splitlines() if 'applyPetDrawable' in l][-1:])

    print()
    print('== 十二、★ 升级路径（界面可见的那半）：已有角色时老形象落进**那个角色** ==')
    r = S.create_character(CHAR_C)
    check('新增角色 C（%s）' % r, r == 'ok')
    if r != 'ok':
        return finish()
    time.sleep(2)
    ok_push = push_into_files(make_legacy_anim(), FILES + '/custom_pet_anim.gif')
    ok_pref = S.pref_set('use_custom_anim', 'true')
    check('前置：老全局动图已就位（files/custom_pet_anim.gif + use_custom_anim=true）',
          ok_push and ok_pref, 'push=%s pref=%s' % (ok_push, ok_pref))
    if not cold_start():
        check('冷启动（迁移那一次）', False)
        return finish()
    lg = logcat()
    check('★ 迁移日志（迁移老形象 -> char=%s）' % CHAR_C,
          ('迁移老形象 -> char=' + CHAR_C) in lg,
          [l for l in lg.splitlines() if '迁移老形象' in l][:1])
    check('★ 素材落进**这个角色**的目录（不是哨兵那份）',
          'anim.gif' in ls('%s/%s' % (APPEAR, CHAR_C)),
          'ls=%r' % ls('%s/%s' % (APPEAR, CHAR_C)))
    check('★ source=anim', source_of(CHAR_C) == 'anim',
          cat('%s/%s/appearance.json' % (APPEAR, CHAR_C)).strip())
    ok, before = S.open_hub()
    if not ok:
        skip('迁移后角色中心那一行', '打不开角色中心')
    else:
        check('★ 中心那一行跟着变「动图」（界面这一半也验到了）',
              hub_appearance_label(S.new_texts(before)) == '动图',
              hub_appearance_label(S.new_texts(before)))
        S.close_dialog()
    check('（哨兵那份 image.png 不受影响）', 'image.png' in ls(SENTINEL_DIR))

    return finish()


def finish():
    # ★ 设备上的临时文件（pref 副本 —— 含用户的 Key）所有退出路径都要清
    #   （本脚本改 pref 走 `S.pref_set`，副本落在 /sdcard）。
    S.cleanup_device_temps()
    # ★ 设备脚本用完必须擦掉本地 pref 副本：项目移植包会把整个 build/ 打进去，
    #   里面若有用户的 Key 就会跟着源码包发出去（轮次㉘ 抓到的真泄漏）。
    pf.wipe_local()
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
    print('ALL DEVICE CHECKS PASSED（P5 形象来源按角色 + 角色中心，在 %s 上）' % DEV)
    return 0


if __name__ == '__main__':
    if '--selftest' in sys.argv:
        sys.exit(selftest())
    sys.exit(main())
