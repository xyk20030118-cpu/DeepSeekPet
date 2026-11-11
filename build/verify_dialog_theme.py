# -*- coding: utf-8 -*-
"""三级菜单（对话框）主题化断言：**所有弹窗都要同步 App UI 并跟随主题切换**。

用户原话（2026-10-11）：「**更改所有的三级菜单让所有的三级菜单都同步 app ui 跟随主题切换**」。

## 为什么单开一套

本工程是纯 framework、零 AndroidX：**页面**的颜色全由 Java 从 `Tokens.Set` 取 ⇒ 换皮肤/换明暗
自动生效。但**系统自绘的对话框**只认 **Activity 主题**，不认我们的令牌 ——
以前只靠 `Tokens.applyPlatformTheme()` 换了明暗变体，于是：
  * 明暗跟上了，**皮肤（新拟态 / 玻璃态）没跟上**（弹窗永远是平台那副样子）；
  * 5 个 Activity 连 `applyPlatformTheme` 都没调（那几页弹出的确认框在深色下是白的）。

所以本套件钉两件事：
  ① **结构**：业务代码里**一处原生写法都不许有**（`new AlertDialog.Builder(` / `new ProgressDialog(`）
     —— 漏一处就是"某个弹窗不跟随主题"，而设备上很难逐个点出来；
  ② **语义**：主题化真的套了令牌（圆角面板 + 标题/正文/按钮配色），而且**每次打开都重新取令牌**
     （换主题后新开的弹窗立刻是新皮肤）。

## 分节

  一、迁移完整性：原生写法归零 + 每个文件的调用点数（机械替换的账要能对上）
  二、设计层入口：`UiFactory.dialog` / `progress` 唯一入口 + `ThemedBuilder` 接管 `create()`
  三、主题化真的做了三件事：令牌面板 / 文字染色 / 设备可验的日志
  四、跟随主题切换：每次取令牌、皮肤与明暗都在、**所有 Activity 都 applyPlatformTheme**
  五、产物：新代码真的进了 dev 包
  六、设备脚本 `verify_dialog_theme_ui.py` 在、只认模拟器、`--selftest` 通过

设备侧实测（**切主题前后各开一次弹窗，比对日志里的 surface 色**）见 `verify_dialog_theme_ui.py`；
反向破坏见 `reverse_check_dialog_theme.py`。
"""
import io
from artifacts import dev_apk
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DEV_APK = dev_apk()

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def method_body(src, sig):
    """花括号配对取方法体（别用非贪婪正则：嵌套块会提前收口）。"""
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('{', i)
    if j < 0:
        return ''
    depth, k = 0, j
    while k < len(src):
        c = src[k]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return src[j + 1:k]
        k += 1
    return ''


def run(cmd):
    r = subprocess.run(cmd, cwd=HERE, capture_output=True)
    return (r.returncode,
            r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace'))


JAVA = {fn: read(os.path.join(SRC, fn)) for fn in sorted(os.listdir(SRC)) if fn.endswith('.java')}
CODE = {fn: code_only(s) for fn, s in JAVA.items()}
uf = CODE['UiFactory.java']
tk = CODE['Tokens.java']

print('=== 一、迁移完整性：业务代码里一处原生写法都不许有 ===')
# ★★ 本套件最值钱的一条：漏一处 = 某个弹窗不跟随主题，而设备上很难逐个点出来
RAW_AD = re.compile(r'new AlertDialog\.Builder\(')
RAW_PD = re.compile(r'new ProgressDialog\(')
left = []
for fn, s in CODE.items():
    for m in list(RAW_AD.finditer(s)) + list(RAW_PD.finditer(s)):
        line = s[:m.start()].count('\n') + 1
        left.append('%s:%d' % (fn, line))
check('★★ 没有原生 new AlertDialog.Builder( / new ProgressDialog(（注释不算）',
      not left, left[:6])
# 机械替换的账要对得上（漏一个文件就是"那个页面不跟随"）
EXPECT = [('MainActivity.java', 'UiFactory.dialog(', 89),
          ('MainActivity.java', 'UiFactory.progress(', 8),
          ('FloatingService.java', 'UiFactory.dialog(', 2),
          ('HistoryActivity.java', 'UiFactory.dialog(', 1),
          ('PermissionActivity.java', 'UiFactory.dialog(', 1),
          ('PlatformLoginActivity.java', 'UiFactory.dialog(', 1)]
for fn, needle, n in EXPECT:
    got = CODE[fn].count(needle)
    check('%-26s %-22s ≥ %d（实得 %d）' % (fn, needle, n, got), got >= n)

print()
print('=== 二、设计层入口：唯一入口 + 接管 create() ===')
check('★ 有唯一入口 UiFactory.dialog(Context)',
      'static AlertDialog.Builder dialog(Context c)' in uf)
check('★ 有 UiFactory.progress(Context)（进度框同样是系统自绘）',
      'static ProgressDialog progress(Context c)' in uf)
# ★ progress() 内部必须是**全限定**构造：迁移脚本会把 `new ProgressDialog(` 一律换掉，
#   写成短名的话这一行会被换掉 ⇒ progress() 自己调自己（无限递归）
pg = method_body(uf, 'static ProgressDialog progress(Context c)')
check('★★ progress() 内部用全限定构造（否则会被迁移脚本改成自我递归）',
      'new android.app.ProgressDialog(c)' in pg and 'UiFactory.progress(' not in pg)
check('★ ThemedBuilder 继承 AlertDialog.Builder', 'extends AlertDialog.Builder' in uf)
tb = method_body(uf, 'private static final class ThemedBuilder extends AlertDialog.Builder')
check('★★ ThemedBuilder 接管 create()（有 8 处调用点直接用 .create()，只包 show() 会漏）',
      'public AlertDialog create()' in tb and 'themeDialog(d, ctx, items, choice)' in tb)
# ★ 轮次㊸：列表内容要在 Builder 里**记下来**，create() 之后才换得掉系统那种行
check('★ ThemedBuilder 记下列表内容（setItems / 单选 / 多选六种重载都记）',
      'items = a; choice = false;' in tb and 'items = a; choice = true;' in tb
      and tb.count('items = ctx.getResources().getTextArray(itemsId)') >= 3)

print()
print('=== 三、主题化真的做了三件事（面板 / 文字 / 日志）===')
td = method_body(uf, 'static void themeDialog(Dialog d, Context c, CharSequence[] items, boolean choice)')
tdn = re.sub(r'\s+', '', td)          # 去掉空白：判据要盯**整条调用**，不能只盯几个符号名
check('定位到 themeDialog', bool(td))
check('★ ① 窗口背景换成令牌面板（圆角 R_DIALOG + **modal** 浮层色 + 描边）',
      # ★ 反向验证实测：只查 `setBackgroundDrawable`/`Tokens.R_DIALOG` 这些**符号名**，
      #   把整条语句改成 `if (false) w.setBackgroundDrawable(...)` 照样绿
      #   ⇒ 必须钉**整条调用**（去空白后比对）
      'w.setBackgroundDrawable(newInsetDrawable(solid(c,t,Tokens.R_DIALOG,t.modal,'
      't.glassBorder),' in tdn)
# ★★ 反向验证实测的第二层：把整条语句包进 `if (false)`（形同删除）时，上面那条**子串判据照样绿**
#   （`if(false)` 只是前缀，不影响子串匹配）⇒ 再钉一条"不许有死分支"。
check('★★ themeDialog 里没有 if (false) 之类的死分支（形同删除）',
      'if(false)' not in tdn and 'if(true)' not in tdn)
# ★★ 同一个概念不许有两份实现：MainActivity 原来那套 applyDialogSkin/tintDialogText
#   在 onShow 里手动套色，比 create() 晚 ⇒ 会把设计层那个带内边距的圆角面板**覆盖**掉
check('★★ 弹窗套色只有一处实现（老的 applyDialogSkin / tintDialogText 已删）',
      not [fn for fn, s in CODE.items() if 'applyDialogSkin' in s or 'tintDialogText' in s],
      [fn for fn, s in CODE.items() if 'applyDialogSkin' in s or 'tintDialogText' in s])
check('★ 标题两个 id 都染（alertTitle 是新平台的、title 是老平台的）',
      'androidId(c, "alertTitle")' in td and 'android.R.id.title' in td)
check('★ ① 外面留了内边距（内容不顶到圆角上）', 'InsetDrawable' in td)
check('★ ② 标题染色（走 getIdentifier 找 alertTitle；轮次㊸ 起先取到变量再染）',
      'd.findViewById(androidId(c, "alertTitle"))' in td
      and 'tint(titleV, t.text1, true)' in td)
check('★ ② 正文与三个按钮染色（用公开的 android.R.id.button1/2/3 与 message）',
      'android.R.id.message' in td and 'android.R.id.button1' in td
      and 'android.R.id.button2' in td and 'android.R.id.button3' in td)
check('★★ alertTitle **不在**公开 android.R.id 里 ⇒ 必须用 getIdentifier 解析',
      'getIdentifier(name, "id", "android")' in uf and 'android.R.id.alertTitle' not in uf)
check('★ ③ 日志（对话框不在 uiautomator dump 里 ⇒ 设备侧只能靠它）',
      '"DSDialog"' in uf and '三级菜单套用令牌' in uf
      and 'surface=#' in uf and 'accent=#' in uf)
check('★ 主题化失败不许影响功能（全程 try/catch）',
      'catch (Throwable ignore)' in td)

print()
print('=== 四、跟随主题切换（每次取令牌 + 全部 Activity 换主题）===')
check('★★ themeDialog **每次**都重新取令牌（换主题后新开的弹窗立刻是新皮肤）',
      'Tokens.of(c)' in td)
check('★ 令牌里皮肤与明暗都在（新拟态/玻璃态 × 浅/深）',
      'SKIN_GLASS' in tk and 'SKIN_NEUMORPH' in tk and 'THEME_DARK' in tk)
acts = [fn for fn in CODE if fn.endswith('Activity.java')]
missing = [fn for fn in acts if 'Tokens.applyPlatformTheme(this)' not in CODE[fn]]
check('★★ 所有 Activity 都调 applyPlatformTheme（系统自绘的东西只认 Activity 主题）',
      not missing, missing)
# ★ 顺序：setTheme 必须在 setContentView 之前，否则不生效
order_bad = []
for fn in acts:
    s = CODE[fn]
    i_theme = s.find('Tokens.applyPlatformTheme(this)')
    i_cv = s.find('setContentView(')
    if i_theme < 0 or i_cv < 0 or i_theme > i_cv:
        order_bad.append(fn)
check('★ applyPlatformTheme 都在 setContentView **之前**（setTheme 的硬性要求）',
      not order_bad, order_bad)

print()
print('=== 五、产物：新代码真的进了 dev 包 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for probe in ('DSDialog', '三级菜单套用令牌', 'ThemedBuilder', 'themeDialog',
                  # ★ dex 里的类型描述符用**斜杠**（Landroid/app/ProgressDialog;），
                  #   写成点号永远匹配不到（第一版就是这么假绿的）
                  'android/app/ProgressDialog'):
        check('dex 含 %s' % probe, probe.encode('utf-8') in dex)

print()
print('=== 六、设备脚本（切主题前后比对 surface 色的那一份）===')
ui = os.path.join(HERE, 'verify_dialog_theme_ui.py')
check('verify_dialog_theme_ui.py 存在', os.path.isfile(ui))
if os.path.isfile(ui):
    us = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          'DEV = V.DEV' in us and 'import verify_char_ui as V' in us)
    check('★ 用「切主题 → 重开弹窗 → 比对 surface」证明跟随切换（不是只看一次日志）',
          'surface' in us and 'ui_theme' in us and 'ui_skin' in us)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_dialog_theme_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('=== 七、★★ 同步 UI（不只是颜色）：行 / 按钮 / 标题正文都换成设计层的件 ===')
# ★ 用户原话（轮次㊸）：「**三级菜单并没有同步 ui，只同步了主题颜色**」——这一节就是钉这件事。
check('★★ 列表行换成我们自己的适配器（行首徽章 + 15sp 文字 + 选中标记）',
      'class DialogRowAdapter extends android.widget.BaseAdapter' in uf
      and 'new DialogRowAdapter(c, t, items, choice, lv)' in uf)
check('★ 行适配器复用卡片那套行（row / 徽章 / takeLeadingEmoji / stripEmoji）',
      'LinearLayout row = row(ctx, t);' in uf
      and 'iconBadgeByName(ctx, t, iconName, glyph)' in uf
      and 'takeLeadingEmoji(box)' in uf and 'stripEmoji(box[0])' in uf)
check('★ 单选/多选把选中态画出来（读 ListView 的 isItemChecked）',
      'lv.isItemChecked(pos)' in uf)
sdb = method_body(uf, 'private static void styleDialogButton(')
check('★★ 按钮换成我们的胶囊（pressable + R_PILL + 44dp 触底 + 去掉 AllCaps）',
      # ★★ 判据必须**限定在 styleDialogButton 里**：这些文本 button() 里也有，
      #    全文搜的话把 styleDialogButton 掏空照样绿（反向套件 N 项当场抓到）
      'b.setBackground(pressable(c, t, Tokens.R_PILL, primary))' in sdb
      and 'b.setMinHeight(dp(c, 44))' in sdb and 'b.setAllCaps(false)' in sdb,
      'styleDialogButton 长度 %d' % len(sdb))
check('★ 标题 / 正文走设计层的字号与行距（原来是系统字号）',
      'tv.setTextSize(19)' in tdn and 'tv.setTextSize(15)' in tdn)
# ★★ Android 36 实测：create() 那一刻按钮**还没建出来**（日志里 btn1=null）
#    ⇒ 必须有"首帧之前补齐"的第二条路，否则按钮样式等于没套
check('★★ 首帧之前补齐按钮样式（create() 时按钮还不存在）',
      # ★ 查**整条调用**（带括号）：只查符号名的话，改个名 `addOnPreDrawListenerX`
      #   照样命中子串 ⇒ 假绿（反向套件 O 项当场抓到）
      'addOnPreDrawListener(' in tdn and 'restyleIfSystemDefault(' in uf)
check('★★ 补齐时**不覆盖**调用点有意改过的背景（危险键的实心红）',
      'contains("Gradient")' in uf)
check('★ 只换 adapter、不碰 item 点击监听（行为零变化）',
      'setOnItemClickListener' not in uf)
check('★ 日志报出行数 / 适配器 / 按钮形状（设备侧唯一证据）',
      'rows=' in uf and 'adapter=' in uf and '按钮补齐' not in uf and '首帧补齐' in uf)

print()
print('=== 八、★★ 行与按钮都要在**首帧之前**补（create() 时那套还不是最终显示的那套）===')
# ★★ 轮次㊹ 的**真凶**：上一版把行样式放在 create() 里，而那时 getListView() 拿到的
#    不是最终显示的那个 ListView（AlertController 到 show() 阶段才装内容，
#    和 btn1=null 同一个根因）⇒ **行样式等于没生效**。
#    设备实测（同一个「选择对话服务商」弹窗）：
#      旧包 行是 CheckedTextView、文字左边界 65px、行首没有图标；
#      修好后 行是 TextView、左边界 234px、中央带强调色像素 0 → 702。
check('★★ 行样式抽成 styleDialogRows()（能被两个时机各调一次）',
      'private static String styleDialogRows(' in uf
      and 'String adapterName = styleDialogRows(d, c, t, items, choice);' in td)
check('★★ 首帧之前的回调里**也**补一次行样式（只补按钮会漏掉行）',
      'String ad = styleDialogRows(fd, cc, tt, items, choice);' in td)
check('★★ 首帧那条日志要报行数/适配器（否则设备侧分不清是哪一次生效）',
      '"首帧补齐 btn1="' in td and 'adapter=" + ad' in td)
check('★ 系统的列表 selector 换成透明（否则系统色高亮会盖在我们的行上）',
      'setSelector' in uf and 'Color.TRANSPARENT' in uf)

print()
print('=== 九、★★ 行本身：每行都有图标 + 两行式文案（"还是最普通的样子"的正解）===')
# ★ 用户点名的「选择对话服务商」：选项是"名字 + \n + Key 状态"，**开头没有 emoji**，
#   而上一版的徽章只在文案以 emoji 开头时才画 ⇒ 一行徽章都没有，看着就是纯文字列表。
dra = method_body(uf, 'private static final class DialogRowAdapter extends android.widget.BaseAdapter')
check('★★ 每一行都给行首徽章（不再 if (glyph != null) 才画）',
      # ★ 必须**限定在行适配器里**：actionRow / toggleRow 里那两句
      #   `if (glyph != null) row.addView(iconBadge(` 是**对的**（那里就该没有 emoji 就不画），
      #   全文搜会误判。
      'row.addView(iconBadgeByName(ctx, t, iconName, glyph));' in dra
      and 'if (glyph != null) row.addView(iconBadge(' not in dra)
check('★★ 没有 emoji 的行按**关键词**兜底给图标（dialogRowIcon）',
      'private static String dialogRowIcon(String s)' in uf
      and 'dialogRowIcon(label)' in uf and 'return "KEY";' in uf)
check('★ 关键词表要克制（命中不了就返回 null ⇒ 只留凹槽底片）',
      'return null;' in method_body(uf, 'private static String dialogRowIcon(String s)'))
check('★★ 带 \\n 的选项拆成**两行**（主行 text1 / 副行 text3）',
      "label.indexOf('\\n')" in uf and 'col.setOrientation(LinearLayout.VERTICAL)' in uf
      and 'sv.setTextColor(t.text3)' in uf)
check('★ 行自带按压反馈（selector 已透明，反馈改由行承担）',
      'row.setBackground(rowPressable(ctx, t, Tokens.R_CARD))' in uf)
check('★ 系统标题下面那条分隔线藏掉（它不跟随令牌）',
      '"titleDivider"' in tdn and 'setVisibility(View.GONE)' in tdn)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（三级菜单：全部走设计层 + 跟随皮肤与明暗）')
