"""断言：预测式返回动画的源码级正确性。

★ 为什么需要（这一轮踩过的坑）：
  ① 光注册 `OnBackInvokedCallback` **没有跟随动画** —— 那是"能返回"，
     不是"跟随手势"。必须有 `OnBackAnimationCallback` + `onBackProgressed`。
  ② 用了 API 34 的类型就必须有**类加载隔离**（独立嵌套类）+ 版本判断，
     否则 API 33 以下的机器在**类加载阶段**就 NoClassDefFoundError。
  ③ 动画几何必须与 `UiFactory.pageSlide` 一致（1/3 屏宽 + 0.94 缩放），
     否则松手瞬间画面会跳。
  ④ 三个回调必须齐全：started / progressed / cancelled。
     少了 cancelled，用户中途取消后页面会**停在半位移状态**。

这些都无法在离线单测里覆盖（依赖 Android），所以做源码断言。
"""
import io
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
MA = r'D:\petport_lab\petbuild\src\com\deepseek\pet\MainActivity.java'
UF = r'D:\petport_lab\petbuild\src\com\deepseek\pet\UiFactory.java'
MAN = r'D:\petport_lab\petbuild\AndroidManifest.xml'

ma = io.open(MA, encoding='utf-8-sig').read()
uf = io.open(UF, encoding='utf-8-sig').read()
man = io.open(MAN, encoding='utf-8-sig').read()

def strip_comments(s):
    """剥掉注释再检查「类名是否出现在某处」。

    ★ 必须剥：BackApi34 的 javadoc 里就写了
      {@code OnBackAnimationCallback} 这个类名做说明。
      不剥注释会把"注释里提到"误判成"代码里用了" ——
      我第一版就这样误报了一次。
      注释不影响类加载，只有**代码**里的引用才有意义。
    """
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s


ma_code = strip_comments(ma)
uf_code = strip_comments(uf)

pass_n = fail_n = 0


def ok(cond, what):
    global pass_n, fail_n
    if cond:
        pass_n += 1
    else:
        fail_n += 1
        print('  FAIL: %s' % what)


print('=== 预测式返回动画断言 ===')
print()

# ① 清单 opt-in（没有它系统根本不会把返回手势交给 App）
print('[1] 清单 opt-in')
ok('android:enableOnBackInvokedCallback="true"' in man,
   '清单必须声明 enableOnBackInvokedCallback="true"')
print('   enableOnBackInvokedCallback ✓')

# ② 动画回调类与三个方法
print()
print('[2] 动画回调（跟随手势的核心）')
ok('OnBackAnimationCallback' in ma_code,
   '必须用 OnBackAnimationCallback（不是只有 OnBackInvokedCallback）')
for m in ('onBackStarted', 'onBackProgressed', 'onBackCancelled', 'onBackInvoked'):
    ok((m + '(') in ma, '必须实现 %s' % m)
print('   OnBackAnimationCallback + started/progressed/cancelled/invoked ✓')

# ③ 进度真的被用来驱动位移
print()
print('[3] 进度驱动位移')
ok('getProgress()' in ma_code, '必须读取 BackEvent.getProgress()')
ok('setTranslationX' in ma_code, '必须按进度 setTranslationX')
ok('setScaleX' in ma_code and 'setScaleY' in ma_code, '必须按进度缩放')
print('   getProgress → translationX/scale ✓')

# ④ 类加载隔离：34 的 API 必须在独立嵌套类里 + 有版本判断
print()
print('[4] 类加载隔离（否则低版本崩溃）')
ok('class BackApi34' in ma_code, 'API 34 的调用必须在独立嵌套类 BackApi34 里')
ok('SDK_INT >= 34' in ma_code, '必须有 SDK_INT >= 34 判断')
ok('SDK_INT < 33' in ma_code, '必须有 SDK_INT < 33 的兜底判断')
# 34 的 API 不得出现在嵌套类以外的地方（**剥注释后**判断）
m34 = re.search(r'private static class BackApi34\s*\{.*?\n    \}', ma_code, re.S)
ok(m34 is not None, 'BackApi34 类定义可解析')
if m34:
    outside = ma_code[:m34.start()] + ma_code[m34.end():]
    ok('OnBackAnimationCallback' not in outside,
       'OnBackAnimationCallback 不得出现在 BackApi34 之外（会破坏类加载隔离）')
    ok('BackEvent' not in outside,
       'BackEvent 不得出现在 BackApi34 之外')
print('   BackApi34 隔离 + 版本判断 ✓')

# ⑤ ★ 纯平移契约（用户要求「动画采用平移不用翻页了用平移」）
print()
print('[5] 纯平移：不得有缩放/淡入')
ok('PAGE_SLIDE_FRACTION' in uf_code,
   'UiFactory 必须暴露 PAGE_SLIDE_FRACTION（单一来源，供返回动画引用）')
ok('PAGE_SLIDE_FRACTION' in ma_code,
   '返回动画的 MAX_SHIFT 必须引用 PAGE_SLIDE_FRACTION（不写死数值）')
ok('MAX_SHIFT = UiFactory.PAGE_SLIDE_FRACTION' in ma_code,
   'MAX_SHIFT 必须直接等于 PAGE_SLIDE_FRACTION')

# pageSlide：不得再出现缩放/淡入
ms = re.search(r'static void pageSlide\(View v, boolean forward\).*?\n    \}',
               uf_code, re.S)
ok(ms is not None, 'pageSlide 可解析')
if ms:
    body = ms.group(0)
    ok('setScaleX(1f)' in body and 'setScaleY(1f)' in body,
       'pageSlide 必须把缩放钉在 1（纯平移）')
    ok('.scaleX(' not in body and '.scaleX(0' not in body,
       'pageSlide 的动画**不得**再改 scaleX（那就是翻页感）')
    ok('setAlpha(1f)' in body, 'pageSlide 必须把透明度钉在 1')
    ok('.alpha(0' not in body and 'setAlpha(0f)' not in body,
       'pageSlide 不得再用淡入（0→1 也是翻页感）')
    ok('translationX' in body, 'pageSlide 必须有 translationX（平移本体）')

# 返回动画：不得再出现缩放/淡入
ma_apply = re.search(r'private void applyPredictiveBack\(float progress\).*?\n    \}',
                     ma_code, re.S)
ok(ma_apply is not None, 'applyPredictiveBack 可解析')
if ma_apply:
    body = ma_apply.group(0)
    ok('setTranslationX' in body, '返回跟随必须有 setTranslationX')
    ok('setScaleX(BackApi34.MIN_SCALE)' in body,
       '返回跟随必须把缩放钉在 MIN_SCALE(=1)')
    ok('progress * 0.4f' not in body and '1f - progress' not in body.replace(
        'progress * screenW', ''),
       '返回跟随不得再有"越拖越淡/越小"的插值')
ok('MIN_SCALE = 1f' in ma_code, 'MIN_SCALE 必须是 1f（纯平移、不缩放）')
print('   pageSlide 与返回跟随都只动 translationX ✓')

# ⑥ 三个动画阶段都有实现，且取消时会归位
print()
print('[6] 动画三阶段')
for fn in ('beginPredictiveBack', 'applyPredictiveBack', 'cancelPredictiveBack',
           'finishPredictiveBack'):
    ok((fn + '(') in ma_code, '必须有 %s' % fn)
# 取消必须归位（否则停在半位移）
mc = re.search(r'private void cancelPredictiveBack\(\).*?\n    \}', ma_code, re.S)
ok(mc is not None, 'cancelPredictiveBack 可解析')
if mc:
    body = mc.group(0)
    ok('translationX(0f)' in body, '取消时必须把 translationX 归零')
    ok('scaleX(1f)' in body, '取消时必须把 scaleX 复位')
print('   begin/apply/cancel/finish 齐全，取消会归位 ✓')

# ⑦ 注册路径按版本分流
print()
print('[7] 注册分流')
ok('BackApi34.register' in ma_code, '34+ 走带动画的注册')
ok('BackApi33.register' in ma_code, '33 走纯回调注册')
print('   34+ → BackApi34，33 → BackApi33 ✓')

print()
print('通过 %d 项' % pass_n)
if fail_n:
    print('失败 %d 项' % fail_n)
    sys.exit(1)
