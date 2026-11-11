# -*- coding: utf-8 -*-
"""贴边断言：**非吸附态**下桌宠的"可见内容"必须能贴到屏幕边。

背景（2026-10-08 用户报障「宠物悬浮窗状态无法贴边靠边（非吸附状态）」）：
  拖到屏幕边上时桌宠明明能跟手滑出去，**一松手就被拉回屏内 210px**。
  真根因不是"贴边算法写错"，而是：
    ① 松手时会 `setAnimState("idle")` ⇒ 换 clip ⇒ 旧代码把**内容留白清成 null**
       （异步再补），而紧接着的"要不要吸附 / 吸到哪"就跑了这个空窗里，
       于是 `inset=null` 被当成"没有留白" ⇒ 窗口不许挂出屏外 ⇒ 永远差 210px；
    ② 留白只有内置动画包算过 —— 上传的**方图**在 16:9 窗口里左右各留 138px，
       这 138px 从来没被算进"内容"，所以图片形象 100% 贴不到边；
    ③ 漫游那条路按"视口留白"算范围，走到边上同样差一截（"到边缘自动贴边"也判不出来）。

本套件钉的是**语义**（不是"源码里有这句话"）：
  A. 已知的留白不许退回 null（换动作必须命中缓存同步生效 / 算不出来就保留旧值）；
  B. 留白必须覆盖全部形象来源，并且换算到**视口空间**（画布≠视口时要补居中留白）；
  C. "窗口 x 可达范围"全工程**只有一份**判据（拖动钳制/松手吸附/边缘热区/漫游共用）。

设备侧的真值由 `build/verify_edge_snap_ui.py` 在模拟器上量（mAttrs + 边缘像素）。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

BUILD = r'D:\petport_lab\petbuild\build'
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
FLOATING = os.path.join(SRC, 'FloatingService.java')
PETVIEW = os.path.join(SRC, 'PetView.java')
PACK = os.path.join(SRC, 'PetAnimPack.java')
PURE = os.path.join(SRC, 'ContentInset.java')
PROBE = os.path.join(SRC, 'ContentInsetProbe.java')
DEV = os.path.join(BUILD, 'verify_edge_snap_ui.py')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(s):
    """剥掉注释（只留代码与字符串）—— 断言不许被注释里的字样骗过。"""
    out, i, n, blk = [], 0, len(s), False
    while i < n:
        if blk:
            j = s.find('*/', i)
            if j < 0:
                break
            i = j + 2
            blk = False
            continue
        if s.startswith('/*', i):
            blk = True
            i += 2
            continue
        if s.startswith('//', i):
            j = s.find('\n', i)
            if j < 0:
                break
            i = j
            continue
        if s[i] == '"':
            out.append(s[i]); i += 1
            while i < n and s[i] != '"':
                if s[i] == '\\':
                    out.append(s[i]); i += 1
                if i < n:
                    out.append(s[i]); i += 1
            if i < n:
                out.append(s[i]); i += 1
            continue
        out.append(s[i]); i += 1
    return ''.join(out)


def method_body(src, sig_regex):
    """取一个方法的**方法体**（按大括号配平），找不到返回 None。

    ★ 为什么要配平而不是非贪婪正则：方法体里还有 if/for 的花括号，
      非贪婪正则会在大括号没配平时截断，断言就变成"半截代码里找关键词"。
    """
    m = re.search(sig_regex, src)
    if not m:
        return None
    i = src.find('{', m.end() - 1)
    if i < 0:
        return None
    depth, j = 0, i
    while j < len(src):
        if src[j] == '{':
            depth += 1
        elif src[j] == '}':
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    return None


floating_raw = read(FLOATING)
petview_raw = read(PETVIEW)
pack_raw = read(PACK)
pure_raw = read(PURE)
probe_raw = read(PROBE)

floating = code_only(floating_raw)
petview = code_only(petview_raw)
pack = code_only(pack_raw)
pure = code_only(pure_raw)
probe = code_only(probe_raw)

print('=== A. 内容留白不许被清成 null（这就是"松手弹回屏内"的根因）===')

check('纯类 ContentInset 存在', os.path.exists(PURE))
check('★ PetView 里不再有「先清空留白、异步再补」的旧写法',
      'curInset' not in petview,
      'curInset 残留 %d 处' % petview.count('curInset'))

n_apply = len(re.findall(r'applyInsetFor\(', petview))
check('★ 四条换动作路径都走统一的留白刷新（applyInsetFor 至少 4 处调用 + 1 处定义）',
      n_apply >= 5, '出现 %d 次' % n_apply)

apply_body = method_body(petview, r'private void applyInsetFor\(String cid\)')
check('applyInsetFor 方法体取到', apply_body is not None)
if apply_body:
    i_cache = apply_body.find('cachedClipInset')
    i_thread = apply_body.find('new Thread')
    check('★ 命中缓存时**同步**生效（贴边判定跑不到空窗里）',
          i_cache >= 0 and i_thread >= 0 and i_cache < i_thread,
          'cachedClipInset@%d new Thread@%d' % (i_cache, i_thread))
    check('★ 异步算完用 ContentInset.keep 覆盖（算不出来就保留旧值，绝不清空）',
          'ContentInset.keep(' in apply_body)

check('动画包暴露"只查缓存、不解码"的口子（UI 线程可用）',
      'cachedClipInset' in pack and 'insetCache.get(id)' in pack)
check('动画包量留白时**连画布尺寸一起**返回（不然换算不了居中留白）',
      'Measured' in pack and 'measureClip' in pack)

print()
print('=== B. 留白要覆盖所有形象来源，并换算到视口空间 ===')

check('量留白的工具类存在且会扫 alpha 包围盒',
      os.path.exists(PROBE) and 'alphaBBox' in probe and 'ALPHA_THRESHOLD' in probe)
check('工具类能直接量"图片文件的第一帧"（动图那条路要用）',
      'ofFile(' in probe and 'decodeBitmap' in probe)
check('★ 扫 alpha 的阈值只有一份（动画包与图片共用，免得两套口径）',
      'ContentInsetProbe.alphaBBox' in pack and 'ALPHA_THRESHOLD' not in pack)

n_probe = len(re.findall(r'probeImageInset\(', floating))
check('★ 上传图片 / 上传动图两条路都去量留白（probeImageInset 至少 2 处调用 + 1 处定义）',
      n_probe >= 3, '出现 %d 次' % n_probe)
probe_body = method_body(floating, r'private void probeImageInset\(final File file, final Bitmap bm\)')
check('量留白在**后台线程**里做（UI 线程解码会掉帧）',
      probe_body is not None and 'new Thread' in probe_body
      and ('ofBitmap' in probe_body or 'ofFile' in probe_body))
check('量完回到 UI 线程只认**当前那个** PetView（重建后不许写到旧 view 上）',
      probe_body is not None and 'petImage != view' in probe_body)

cc_body = method_body(petview, r'public float\[\] currentContentInset\(\)')
check('currentContentInset 方法体取到', cc_body is not None)
if cc_body:
    check('★ 动画包与上传图片走**同一个**换算入口（不再一条算一条不算）',
          cc_body.count('mappedInset(') >= 2)
map_body = method_body(petview, r'private float\[\] mappedInset\(int bmW, int bmH, float\[\] alpha\)')
check('★ 画布→视口的换算交给纯类 ContentInset.of（有 fpstest 断言）',
      map_body is not None and 'ContentInset.of(' in map_body)
check('★ 换算要看 scaleType（FIT_XY 拉伸时没有居中留白）',
      map_body is not None and 'FIT_CENTER' in map_body)
check('画布尺寸 / 视口尺寸不合法时返回 null（不许算出发疯的窗口位置）',
      map_body is not None and 'bmW <= 0' in map_body and 'vw <= 0' in map_body)

check('★ 模型包 / Live2D 要清掉"图片留白"（它们自己铺满，没有留白）',
      petview.count('clearImageInset()') >= 3)
check('切回动画包时也要清掉图片留白',
      'usingAnimPack = true' in petview and 'clearImageInset()' in petview)

print()
print('=== C. "窗口 x 可达范围"全工程只有一份判据 ===')

check('纯类里有 xRange（拖动/吸附/热区/漫游共用同一套算法）',
      'public static int[] xRange(' in pure)
range_body = method_body(pure, r'public static int\[\] xRange\(int screenW, int winW, float\[\] inset\)')
check('★ xRange 的语义：内容可见边缘贴屏幕边（minX = -左留白，maxX = 屏宽-窗宽+右留白）',
      range_body is not None and 'int minX = -inL;' in range_body
      and 'int maxX = screenW - winW + inR;' in range_body)
check('xRange 用 keep 过的可用留白（null / 脏值当"无留白"，不许抛异常）',
      range_body is not None and 'usable(inset)' in range_body)

helper_body = method_body(floating, r'private int\[\] edgeRangeX\(\)')
check('FloatingService 有唯一的 edgeRangeX()，内部走纯类',
      helper_body is not None and 'ContentInset.xRange(' in helper_body)

check('★ 拖动钳制用 edgeRangeX()',
      method_body(floating, r'private void clampToScreen\(\)') is not None
      and 'edgeRangeX()' in method_body(floating, r'private void clampToScreen\(\)'))
near_body = method_body(floating, r'private boolean nearScreenEdge\(\)')
check('★ 边缘热区用 edgeRangeX()（不再自己算一遍留白）',
      near_body is not None and 'edgeRangeX()' in near_body
      and 'insetOrZero()' not in near_body)
snap_body = method_body(floating, r'private void snapToNearestEdge\(\)')
# ★ 轮次84：吸附**不能**用 edgeRangeX() —— 挂边期间它被放宽了（左右各加一个身位），
#   吸附到"比真实边更外"的位置后 peekBegin 会判"不在边上"而放弃（用户报"有概率挂不上"）。
#   正确口径：用未放宽的 ContentInset.xRange(screenW(), petWinWidth(), insetOrZero())。
check('★ 松手吸附用未放宽的 ContentInset.xRange（不能用会放宽的 edgeRangeX）',
      snap_body is not None
      and 'ContentInset.xRange(screenW(), petWinWidth(), insetOrZero())' in snap_body
      and 'edgeRangeX();' not in snap_body)
# ★ 轮次㊸：漫游那段逻辑从 wanderTick 抽进了 roamOnce（手动/自动共用一份）⇒ 判据跟着走
wander_body = method_body(floating, r'private boolean roamOnce\(boolean manual\)')
check('★ 漫游范围用 edgeRangeX()（旧写法按视口留白算 ⇒ 走到边上还差 210px）',
      wander_body is not None and 'edgeRangeX()' in wander_body)
check('★ 漫游不再用 getResources() 的宽（要跟 screenW() 同一套坐标系）',
      wander_body is not None and 'dm.widthPixels - winW' not in wander_body
      and 'sideGap' not in wander_body)
check('旧写法 sideGap 已从工程里消失',
      'sideGap' not in floating)

print()
print('=== D. 设备脚本（量真值的那一份）===')
check('设备脚本存在', os.path.exists(DEV))
if os.path.exists(DEV):
    dev = read(DEV)
    check('设备脚本读 mAttrs（app 请求的 x）而不是只看 frame=', 'mAttrs' in dev)
    check('设备脚本会量"内容离屏幕边还有多远"（像素判据）', 'screencap' in dev)
    check('设备脚本带 --selftest（不连设备也能验判读逻辑）', '--selftest' in dev)
    check('设备脚本覆盖**两种**形象来源（内置动画包 + 上传图片）',
          'builtin' in dev and 'image' in dev)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED')
