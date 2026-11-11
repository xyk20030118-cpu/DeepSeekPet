"""断言：四处浮层（气泡 / 输入框 / 动作菜单 / 长按菜单）必须跟随主题。

★ 用户要求「优化一下气泡以及输入框（双击）以及动作页面（三击）还有长按菜单界面
  同步 ui 风格」。

★ 为什么必须逐个检查"没有硬编码颜色"：
  这四处原先全是写死的浅色（白底 #F2FFFFFF、浅蓝渐变 #F5FAFF→#E9F2FC、
  淡蓝描边 #6BA3D8、深灰字 #222222）。「跟随主题」的脆弱点是
  **漏改一处就前功尽弃** —— 只要有一个浮层还写死白色，
  深色主题下那块就是刺眼的白，而且这种不一致在静态代码里很难发现
  （要同时看四个地方）。

★ 检查方式：读真实源码文本，**剥掉注释和字符串字面量后**统计颜色字面量。
  必须跳过字符串 —— 本工程的源码里有 `"#F2FFFFFF"` 这种**写在字符串里**的
  颜色（`Color.parseColor("#...")`），剥字符串会把真问题也剥掉；
  但注释里又大段引用了旧色值。
  所以：剥注释，保留字符串。
"""
import io
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
FS = r'%s\FloatingService.java' % SRC


def strip_comments(s):
    """剥注释，但**跳过字符串字面量**（否则 "image/*" 里的 /* 会被当成注释开头）。"""
    out = []
    i, n = 0, len(s)
    in_str = None
    while i < n:
        c = s[i]
        if in_str is not None:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(s[i + 1]); i += 2; continue
            if c == in_str:
                in_str = None
            i += 1; continue
        if c == '"' or c == "'":
            in_str = c; out.append(c); i += 1; continue
        if c == '/' and i + 1 < n:
            if s[i + 1] == '/':
                j = s.find('\n', i); i = n if j < 0 else j; continue
            if s[i + 1] == '*':
                j = s.find('*/', i + 2); i = n if j < 0 else j + 2; continue
        out.append(c); i += 1
    return ''.join(out)


raw = io.open(FS, encoding='utf-8-sig').read()
code = strip_comments(raw)

pass_n = fail_n = 0


def ok(cond, what):
    global pass_n, fail_n
    if cond:
        pass_n += 1
    else:
        fail_n += 1
        print('  FAIL: %s' % what)


def method_body(src, header_re, max_lines=260):
    """取出一个方法的**完整**方法体。

    ★ 不能用 `re.search(header + r'.*?\\n    \\}', ...)` ——
      非贪婪匹配会在方法内部**第一个**缩进 4 空格的 `}` 处停下，
      而那可能只是内部匿名类/嵌套块的结尾，于是"窗口"截得过短，
      真正要检查的行落在窗口外 → 断言误报失败。
      我第一版就这样误报了两条（代码其实是改对的）。

    这里改为：从方法头开始，按大括号配平找到真正的结尾，
    并限制最大行数防止跑飞。
    """
    m = re.search(header_re, src)
    if not m:
        return None
    i = src.find('{', m.end() - 1)
    if i < 0:
        return None
    depth = 0
    j = i
    n = len(src)
    while j < n:
        c = src[j]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[m.start(): j + 1]
        j += 1
    return src[m.start():]


print('=== 浮层主题同步 断言 ===')
print()

# ---- ① 四处浮层都改了 ----
print('[1] 四处浮层各自接上主题')

# 输入框（双击）
seg_ask = method_body(code, r'private void showAskBar\(\)')
ok(seg_ask is not None, 'showAskBar 可解析')
if seg_ask:
    b = seg_ask
    ok('floatPanelBg' in b, '输入框卡片必须用 floatPanelBg（主题底板）')
    ok('floatPrimaryBg' in b, '输入框的「发送」必须用 floatPrimaryBg')
    ok('floatSecondaryBg' in b, '输入框的「🎤」必须用 floatSecondaryBg')
    ok('T.text1' in b, '输入框正文必须用令牌文字色')
    ok('T.text3' in b, '输入框 hint 必须用令牌文字色')
    ok('0xFF222222' not in b and '0xFF999999' not in b,
       '输入框不得再写死 #222222 / #999999')
    ok('#F2FFFFFF' not in b, '输入框不得再写死 #F2FFFFFF')

# 长按菜单
seg_lp = method_body(code, r'private void showLongPressBubble\(\)')
ok(seg_lp is not None, 'showLongPressBubble 可解析')
if seg_lp:
    b = seg_lp
    # ★ 面板底已取消（用户要求"取消底部气泡改为悬浮按钮"）：
    #   body 背景必须透明，两颗钮各自用 UiKit.raised 悬浮。
    #   menuPanelBg 本体仍保留（气泡等别处在用），只是长按菜单不再调用。
    ok('setBackground(null)' in b, '长按 body 背景必须透明（取消底部气泡）')
    # ★ 两行不做颜色区分：都用气泡同款（surface 底 + text1 字）。
    #   原来退出 danger 系、重启 accent 系 —— 用户要求同步气泡，已改。
    ok('T.surface, T.text1' in b, '两行必须用气泡同款（surface 底 + text1 字，不区分）')
    ok('softDanger' not in b and 'softAccent' not in b,
       '两行不得再用 softDanger/softAccent 跳色')
    # ★ 菜单按钮不得超过气泡范围（用户报障钉死的结构保证）：
    #   行根 MATCH_PARENT + 纵向 LinearLayout 给 EXACTLY，行从根上超不出面板；
    #   面板宽 Paint 实测 + 钳制；定位函数不得再 setLayoutParams 覆盖 body。
    #   必须钉 newMenuChip 方法体 —— 全文件搜 MATCH_PARENT 的话，
    #   改坏这一处也抓不到（别处还有 MATCH_PARENT，空转过）。
    seg_chip = method_body(code, r'private android\.widget\.LinearLayout newMenuChip\(')
    ok(seg_chip is not None, 'newMenuChip 可解析')
    if seg_chip:
        ok('MATCH_PARENT' in seg_chip, '长按行根必须是 MATCH_PARENT（父容器给 EXACTLY）')
        ok('VectorIcon.view' in seg_chip, '行图标必须走 VectorIcon（主题单色矢量）')
        ok('d * 3 * bscale' in seg_chip, '行间距必须等比（d*3*bscale）')
        # ★ 悬浮钮本体：UiKit.raised + pressFx 写在 newMenuChip 里，
        #   不在 showLongPressBubble —— 查错方法体会空转（刚空转过一次）。
        ok('UiKit.raised' in seg_chip, '悬浮钮必须用 UiKit.raised（与主体 UI 同表面）')
        ok('UiFactory.pressFx' in seg_chip, '悬浮钮必须有 pressFx 按压反馈')
    ok('longPressPanelW' not in code,
       '不得再用 longPressPanelW 存旧宽覆盖 body（溢出根因，已删除）')
    ok('measureText' in b, '面板宽必须 Paint 实测文字（不再量行 View 旧尺寸）')

# 气泡
seg_bub = method_body(code, r'private void showBubbleInternal\(')
ok(seg_bub is not None, 'showBubbleInternal 可解析')
if seg_bub:
    b = seg_bub
    ok('floatPanelBg' in b, '气泡主体必须用 floatPanelBg')
    ok('PointerView' in b, '气泡尖角必须用 PointerView（自绘，才能与底板同色）')
    ok('bubble_chat' not in b, '气泡不得再用 bubble_chat.xml（静态 XML 不懂主题）')
    ok('bubble_tail' not in b, '气泡尖角不得再用 bubble_tail.xml')
    ok('uiT().text1' in b, '气泡文字必须用令牌')

# 动作菜单（三击）
#
# ★ 断言过时过一次（真失败被抓到）：菜单改成两级后，主题色不再由
#   buildFlatMenu 直接取（旧断言找 uiT().accent），而是走
#   newChip → UiFactory.button(this, Tokens.of(this), name, primary)。
#   断言必须跟着**实际的取色路径**走，否则要么误报失败、
#   要么在改成写死颜色时漏抓。
seg_act = method_body(code, r'private void buildFlatMenu\(')
ok(seg_act is not None, 'buildFlatMenu 可解析')
if seg_act:
    b = seg_act
    ok('newChip' in b, '动作菜单的行必须由 newChip 构建（主题化入口）')
    ok('0xFF2E6FC0' not in b, '动作菜单不得再写死 #2E6FC0')
    ok('setBackgroundResource' not in b, '动作菜单不得再用静态 XML 底板')
    ok('0xFF' not in b, '动作菜单内不得出现任何 0xFF 硬编码颜色')

seg_chip = method_body(code, r'private android\.widget\.TextView newChip\(final String name, float d,\s*float bscale,\s*boolean primary')
ok(seg_chip is not None, 'newChip(primary,listener) 可解析')
if seg_chip:
    b = seg_chip
    ok('UiFactory.button' in b,
       'newChip 必须走 UiFactory.button（主体 UI 的按钮组件）')
    ok('Tokens.of(this)' in b, 'newChip 必须传当前主题令牌')
    ok('H_CONTROL' in b, '菜单行必须满足 48dp 最小触点')

print('   四处都接上了主题令牌 ✓')

# ---- ⑤ 两级动作菜单（第二十四轮）----
print()
print('[5] 动作菜单两级导航')
seg_show = method_body(code, r'private void showActionBubble\(\)')
ok(seg_show is not None, 'showActionBubble 可解析')
if seg_show:
    b = seg_show
    ok('buildFlatMenu' in b, 'showActionBubble 必须装配菜单')
    ok('UiKit.card' in b, '菜单底板必须用 UiKit.card（主体卡片表面）')
# ★ 这里**不能**只查"返回分类"这几个字：它同时还出现在
#   setContentDescription("返回分类，当前：" + ...) 里 ——
#   只查子串的话，把按钮文案改掉也抓不到（反向验证 J 就是这样空转的）。
#   必须钉住"把文案设到按钮上"这一次具体调用。
ok(seg_act and 'setText("\u2039 返回分类")' in seg_act,
   '二级页必须把「返回分类」设到顶部按钮上（setText 调用）')
ok(seg_act and 'classifyAnims' in code, '分类来自 classifyAnims（复用既有分类）')
# 一级点击后只列本类动作；二级点击必须仍然播动作
seg_leaf = method_body(code, r'private android\.widget\.TextView newChip\(final String name, float d,\s*float bscale\)')
ok(seg_leaf is not None, 'newChip(叶子) 可解析')
if seg_leaf:
    ok('playActionByName' in seg_leaf, '叶子动作必须仍然调用 playActionByName')
    ok('hideActionBubble' in seg_leaf, '叶子动作必须先收起菜单')

# ---- ② 不得再有写死的浅色浮层 ----
print()
print('[2] 不得再有写死的浮层配色')
# 这些是原先四处浮层用过的具体色值，全部应已消失
BANNED = [
    ('0xFFF5FAFF', '长按面板渐变中色'),
    ('0xFFE9F2FC', '长按面板渐变底色'),
    ('0xFF6BA3D8', '浮层淡蓝描边'),
    ('0xFF1B3A6B', '浮层深蓝正文'),
    ('0xFFC2DCF5', '浮层内细描边'),
    ('0xFFFFEFEF', '退出行淡玫瑰'),
    ('0xFFE8F2FF', '重启行淡蓝'),
    ('0xFF2E6FC0', '动作菜单分组标题色'),
    ('0xFF274B7A', '气泡文字色'),
    ('0xFF3C4A5E', '余额次要行色'),
    ('0xFF5A7091', '余额用量行色'),
    ('0xFF333333', '语音条文字色'),
]
for hexv, why in BANNED:
    ok(hexv not in code, '%s（%s）不得再出现' % (hexv, why))
# parseColor 的白底
ok('parseColor("#F2FFFFFF")' not in code,
   '不得再用 parseColor("#F2FFFFFF") 白底')
ok('bubble_menu' not in code, '动作菜单不得再用 bubble_menu.xml')
ok('bubble_balance' not in code, '余额气泡不得再用 bubble_balance.xml')

print('   12 组历史硬编码色值全部清除 ✓')

# ---- ③ 主题助手本身 ----
print()
print('[3] 主题助手')
for fn in ('floatPanelBg', 'floatInputBg', 'floatPrimaryBg', 'floatSecondaryBg',
           'uiT', 'panelFillColor', 'panelEdgeColor'):
    ok((fn + '(') in code, '必须有 %s' % fn)
ok('Tokens.of(this)' in code, 'uiT() 必须取当前令牌')
# menuPanelBg 必须转发到 floatPanelBg（单一来源）
mp = re.search(r'private android\.graphics\.drawable\.Drawable menuPanelBg\(float d\).*?\n    \}',
               code, re.S)
ok(mp is not None, 'menuPanelBg 可解析')
if mp:
    ok('floatPanelBg' in mp.group(0), 'menuPanelBg 必须转发到 floatPanelBg（不各写一份）')
print('   助手齐全且单一来源 ✓')

# ---- ⑤ 轮次76：余额气泡总开关 ----
print()
print('[5] 余额气泡总开关（用户：可以关闭、不显示余额气泡）')
_MAIN = r'%s\MainActivity.java' % SRC
_PREFS = r'%s\Prefs.java' % SRC
main = strip_comments(io.open(_MAIN, encoding='utf-8-sig').read())
prefs = strip_comments(io.open(_PREFS, encoding='utf-8-sig').read())

ok('KEY_BALANCE_BUBBLE_ON = "balance_bubble_on"' in prefs,
   'Prefs 用**独立的键**（不复用 show_balance —— 那个是"此刻显不显示"）')
ok('sp.getBoolean(KEY_BALANCE_BUBBLE_ON, true)' in prefs,
   '默认 true（什么都没动的人零影响）')
ok('addToggle(box, "\\uD83D\\uDCB0 显示余额气泡"' in main,
   '设置页里有这颗开关')
ok('FloatingService.ACTION_BALANCE_VIS' in main,
   '改开关会**立刻**通知悬浮窗（不用等重启）')
ok('Prefs.balanceBubbleOn(this) && Prefs.showBalance(this)' in code,
   '重建悬浮窗时总开关也参与判断')
ok('if (show && !Prefs.balanceBubbleOn(this)) show = false;' in code,
   '任何路径都不许越过总开关把气泡显示出来')
ok('Prefs.balanceBubbleOn(FloatingService.this)' in code,
   '单击桌宠也要看总开关（关掉时不再切换）')
ok('if (!Prefs.balanceBubbleOn(this)) return;' in code,
   'refresh() 在关掉时**不发请求**')
ok('if (!Prefs.balanceBubbleOn(FloatingService.this)) return;' in code,
   '轮询在关掉时**不再排下一拍**')
ok('private void applyBalanceBubbleSetting()' in code,
   '有"立刻生效"的落点（隐藏/恢复 + 停/启轮询）')
print('   总开关：设置项 / 立刻生效 / 三条门控都在 ✓')

# ---- ④ 说话/识别等既有能力不能被误伤 ----
print()
print('[4] 既有能力未受影响')
ok('FloatingService.ACTION_HIDE_TEMP' in code or 'ACTION_HIDE_TEMP' in code,
   '桌宠显隐动作仍在')
ok('showBubbleInternal' in code, '气泡主路径仍在')
ok('onMicClick' in code, '语音输入仍在')

print()
print('通过 %d 项' % pass_n)
if fail_n:
    print('失败 %d 项' % fail_n)
    sys.exit(1)
