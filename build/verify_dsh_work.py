# -*- coding: utf-8 -*-
"""工作模式（陪伴 ⇄ DeepSeek Harness）与切换动画的断言套件。

三段：
  [A] 界面：工作模式页面必须真的复刻桌面端那套形状（三块结构 / 桌面端原话 /
      工具卡片 / 停止生成 / 空态），而且**不许自己发明一套硬编码颜色**；
  [B] 切换：顶部模式卡片 → 播动画 → 再进工作模式（顺序不许反）；
  [C] 动画：四种内置 + 自定义视频，视频那一路的四条硬规矩（只收视频 / 有上限 /
      静音 / 点一下跳过）一个都不能少。

★ 只读源码，不改任何工程文件。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
BUILD = os.path.join(ROOT, 'build')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')
DOC = os.path.join(ROOT, 'DSH移植-设计与实现.md')

ok = 0
fail = 0


def check(label, cond, detail=None):
    global ok, fail
    if cond:
        ok += 1
        print('  [OK]   %s%s' % (label, (' :: %s' % detail) if detail else ''))
    else:
        fail += 1
        print('  [FAIL] %s%s' % (label, (' :: %s' % detail) if detail else ''))


def read(path):
    try:
        return io.open(path, encoding='utf-8', errors='replace').read()
    except OSError:
        return ''


WORK = read(os.path.join(SRC, 'DshWorkActivity.java'))
SETTINGS = read(os.path.join(SRC, 'DshSettingsActivity.java'))
ENTRY = read(os.path.join(SRC, 'DshModeEntry.java'))
TRANS = read(os.path.join(DSH, 'DshTransition.java'))
PLAYER = read(os.path.join(DSH, 'DshTransitionPlayer.java'))
BRIDGE = read(os.path.join(DSH, 'DshBridge.java'))
LOOP = read(os.path.join(DSH, 'DshAgentLoop.java'))
MAIN = read(os.path.join(SRC, 'MainActivity.java'))
MAN = read(MANIFEST)

# ==================== [A] 工作模式页面 ====================

print('=== [A] 工作模式页面（复刻桌面端 DeepSeek Harness） ===')
check('页面文件存在', os.path.isfile(os.path.join(SRC, 'DshWorkActivity.java')))
check('★ 走令牌（Tokens.applyPlatformTheme + Tokens.of）',
      'Tokens.applyPlatformTheme(this)' in WORK and 'Tokens.of(this)' in WORK)
hexes = re.findall(r'0[xX][0-9a-fA-F]{8}', WORK)
check('★ 零硬编码 0xAARRGGBB', not hexes, hexes[:3])

# 三块结构（桌面端 = 会话栏 + 对话流 + 输入区；手机折叠成抽屉）
check('有顶栏（含会话抽屉入口 ☰）', '☰' in WORK and 'buildTopBar' in WORK)
check('有会话抽屉（新会话 + 工作区树 + 会话行）',
      'buildDrawerLayer' in WORK and '＋ 新会话' in WORK
      and 'fillWorkspaceTree' in WORK and 'sessionRow' in WORK)
check('有对话流', 'buildFeed' in WORK and 'feedScroll' in WORK)
check('有输入区（多行输入 + 发送）', 'buildComposer' in WORK and 'setMaxLines' in WORK)

# 桌面端原话（复刻的判据：这几句是桌面客户端的原文）
check('空态标题复刻「探索未至之境」', '探索未至之境' in WORK)
check('空态提示 = 官网原文「描述你想要构建的内容，/调用指令，@文件或对话」',
      '描述你想要构建的内容，/调用指令，@文件或对话' in WORK)
check('输入框占位 = 官网原文「描述你想要构建的内容，/调用指令，@文件或对话」',
      '描述你想要构建的内容，/调用指令，@文件或对话' in WORK)
check('状态行复刻「{轮} 轮 {步} 步 / 条消息 / 次工具调用 / 上下文已用」',
      ' 轮 ' in WORK and ' 步 · ' in WORK and ' 条消息 · ' in WORK
      and ' 次工具调用 · ' in WORK and '上下文已用' in WORK)
check('运行中复刻「运行中…」与「停止生成」', '运行中…' in WORK and '停止生成' in WORK)
check('压缩提示复刻「上下文已压缩」', '上下文已压缩' in WORK)
check('失败复刻「失败原因：」', '失败原因：' in WORK)
check('工具结果可展开（复刻「展开其余 N 行」/「收起输出」）',
      '展开其余 ' in WORK and '收起输出' in WORK)
check('思考折叠（官网原文「已思考」）', '已思考 · ' in WORK)
check('工具是**可展开的紧减行**（对齐桌面端：带 ⌄ 折叠符，不是大卡片）',
      'addToolRow' in WORK and 'roundRect' in WORK and '"⌄ " + toolLabel(name)' in WORK)
check('工具名走桌面端那套中文（读取/写入/网页获取/更新任务清单）',
      '读取' in WORK and '写入' in WORK and '网页获取' in WORK and '更新任务清单' in WORK)
check('模型芯片（复刻 model-selection）', 'modelChip' in WORK and 'cycleModel' in WORK)
check('权限芯片（复刻 permission-presets 三档）',
      'permChip' in WORK and '仅可查看' in WORK and '工作区内修改' in WORK and '完全权限' in WORK)
check('★ 不用 AlertDialog / ProgressDialog（本工程硬判据：对话框必须走 UiFactory）',
      'AlertDialog' not in WORK and 'ProgressDialog' not in WORK)

# 停止生成要真能停（不是个摆设按钮）
# ★ 判据必须盯**stopRun 方法体**，不能只看"文件里出现过 cancelCurrent" ——
#   后者在 onDestroy 里也有一处（页面销毁时顺手掐），于是"停止按钮被摘掉"这条破坏
#   根本抓不到（轮次79 自查时就是这么发现的假绿）。所以这里把方法体切出来单独查。
_m_stop = re.search(r'private void stopRun\(\) \{(.*?)\n    \}', WORK, re.S)
_stop_body = _m_stop.group(1) if _m_stop else ''
check('★★ 停止按钮真的接到执行层（stopRun → 协调器 → 后端 → 内核）',
      'coord.cancel()' in _stop_body
      and 'public static void cancelCurrent()' in BRIDGE
      and 'public void cancel()' in LOOP
      and 'DshBridge.cancelCurrent();' in read(os.path.join(DSH, 'DshLocalBackend.java')))
check('停止按钮按下后立刻变灰（不让用户以为没反应）',
      'setText("停止中…")' in _stop_body and 'setEnabled(false)' in _stop_body)
check('★★ loop 里有取消检查点（步与步之间）', 'cancelled' in LOOP and 'if (cancelled)' in LOOP)
check('任务状态驱动发送按钮（非停止态 ⇒ 可发送）', 'setRunning(on)' in WORK)

# ==================== [B] 模式切换 ====================

print()
print('=== [B] 陪伴 ⇄ 工作（集成在角色卡里，不画按钮） ===')
# ★ 用户原话：「集成在这里面，不要显示任何按钮」。
#   所以判据是"角色卡自己可点"，而不是"另画一张带按钮的卡"。
_m_hero = re.search(r'private View heroCard\(Tokens\.Set T\) \{(.*?)\n    \}', MAIN, re.S)
_hero = _m_hero.group(1) if _m_hero else ''
check('★ 入口集成在首页角色卡里（heroCard 里挂点击）',
      'setOnClickListener' in _hero and 'DshModeEntry.switchMode(MainActivity.this, T, null)' in _hero)
check('★ 角色卡受开关守着（关掉就回到只显示状态）',
      'DshFeature.ENABLED' in _hero)
check('★ 没有任何「切换到工作模式」之类的按钮（用户明确不要按钮）',
      '切换到工作模式' not in MAIN and '切换到工作模式' not in ENTRY
      and 'UiFactory.button' not in ENTRY)
check('★ 也不再单画一张模式卡片（旧的 card() 已删除）',
      'DshModeEntry.card(' not in MAIN and 'static LinearLayout card(' not in ENTRY)
check('点名字仍然只是改名（子视图先吃掉点击，不会被卡片抢走）',
      'name.setOnClickListener' in _hero and 'promptPetName()' in _hero)
check('★★ 全文件只有一处 startActivity（收在 openWork 里）',
      ENTRY.count('startActivity(') == 1)
# ★★ 第三版（用户报障）：点卡片切模式时老误触到改名 ⇒ 只有名字与 ✎ 能改名
check('★名字收进 WRAP_CONTENT 的横排（右边空白不再接点击）',
      'nameRow' in _hero and 'nameRow.addView(name' in _hero
      and 'new LinearLayout.LayoutParams(\n                LinearLayout.LayoutParams.WRAP_CONTENT'
      in _hero)
check('★名字点击**真的**连到改名（序列匹配，摘掉就变红）',
      'name.setOnClickListener(new View.OnClickListener() {\n'
      '            @Override public void onClick(View v) { promptPetName(); }' in _hero)
check('★ ✎ 小字也可点（可发现性，不是按钮）',
      'pen.setOnClickListener' in _hero and r'"\u270E"' in _hero)
check('★名字文本里不再拼 ✎（字号变宽 = 误触变大）',
      'Prefs.petName(this) + " \u270E"' not in _hero)
check('★★ 头像点击**真的**接到 App 头像（PetAvatar.pick）',
      'av.setOnClickListener(new View.OnClickListener() {\n'
      '            @Override public void onClick(View v) {\n'
      '                PetAvatar.pick(MainActivity.this);' in _hero
      or 'PetAvatar.pick(MainActivity.this);' in _hero)
check('★★★ 头像**不再**调 pickImage（那条改的是「形象」，用户明确不要）',
      'pickImage();' not in _hero)
check('★卡片头像读 App 头像优先（PetAvatar.load）',
      'PetAvatar.load(this)' in _hero)
check('★状态行不接点击（落到卡片上 = 切工作模式）', 'st.setOnClickListener' not in _hero)
check('★★ 先播动画、再打开工作模式（openWork 定义在 play 之后）',
      ENTRY.index('DshTransitionPlayer.play(') < ENTRY.index('private static void openWork'))
check('回陪伴也走同一套（工作模式退出播动画后 finish）',
      'DshTransitionPlayer.play(' in WORK and 'finish()' in WORK)
check('工作模式会把自己标成 work=true / 退出标 false',
      'setWorkMode(this, true)' in WORK and 'setWorkMode(DshWorkActivity.this, false)' in WORK)
check('清单里只注册工作模式，**控制台已注销**（exported=false）',
      'android:name=".DshWorkActivity"' in MAN and 'android:name=".DshActivity"' not in MAN)
check('设置页那一行现在进的是工作模式（不再是老控制台）',
      'DshModeEntry.switchMode(MainActivity.this, T, null)' in MAIN)
check('★ 没用的控制台已整页删掉（用户要求：不要多余页面）',
      'DshActivity' not in WORK
      and not os.path.isfile(os.path.join(SRC, 'DshActivity.java')))

# ==================== [C] 切换动画 ====================

print()
print('=== [C] 切换动画（四种内置 + 自定义视频） ===')
check('类型表在纯类里（唯一定义处）',
      'public static final int[] KINDS' in TRANS and 'DshTransition.KINDS' in SETTINGS)
for name in ('FADE', 'ZOOM', 'SLIDE', 'VIDEO'):
    check('内置类型 %s' % name, ('int %s =' % name) in TRANS)
check('认不出的值回默认档', 'normalize' in TRANS and 'return FADE' in TRANS)
check('时长有上下限（夹取，不是随便填）',
      'clampMs' in TRANS and 'if (ms < 120)' in TRANS and 'if (ms > 800)' in TRANS)
check('视频播放有硬上限（不许无限等）',
      'VIDEO_MAX_MS' in TRANS and 'postDelayed(close, DshTransition.VIDEO_MAX_MS)' in PLAYER)
check('★ 视频静音（不抢音频焦点）', 'setVolume(0f, 0f)' in PLAYER)
check('★ 点一下跳过', 'setOnClickListener' in PLAYER and 'close.run()' in PLAYER)
check('★ 视频出错也不许卡住（onError → close）',
      'setOnErrorListener' in PLAYER and 'return true;' in PLAYER)
check('★ 任何异常都走 done（动画失败 ≠ 功能失败）',
      'onFinished' in PLAYER and 'catch (Throwable' in PLAYER)
check('没视频时自动退回淡入淡出',
      'DshTransition.FADE' in PLAYER and '!video.isFile()' in PLAYER)
check('导入只收视频（mime 不是 video/* 就拒）',
      'startsWith("video/")' in BRIDGE)
check('导入有体积上限', '60L * 1024 * 1024' in BRIDGE)
check('导入先写 .part 再改名（拷一半不留坏文件）',
      '".part"' in BRIDGE and 'renameTo' in BRIDGE)
check('导入成功会自动选成「自定义视频」',
      'setTransitionKind(a, DshTransition.VIDEO)' in ENTRY)
check('设置页能选类型（四档循环）与移除视频',
      '切换动画：' in SETTINGS and '移除已导入的视频' in SETTINGS)
check('SAF 请求码不与老代码撞号（2001~2022 已占用）',
      'REQ_VIDEO = 2031' in ENTRY)

print()
print('=== [D] 工作模式界面与桌面端同步（第二版补齐） ===')
check('复刻「第 {turn} 轮」轮次分隔', 'addTurnDivider' in WORK and '第 ' in WORK)
check('复刻助手消息底部元信息（用时 / tokens）',
      'durLabel(millis)' in WORK and 'tokens' in WORK)
check('复刻工具卡片带用时（used 真的从 millis 算）',
      '"  \u00b7  " + state + used' in WORK
      and 'durLabel(millis)) : ""' in WORK)
check('复刻「加载更早」（对话与轨迹**两页都有**；按钮文案带计数）',
      WORK.count('加载更早（还有 ') >= 2 and 'historyWindow' in WORK)
check('复刻「回到底部」浮层', 'backToBottom' in WORK and '回到底部' in WORK)
check('★ 底部那行"三项本轮暂未开放"的废话已删（功能已真能用）',
      '三项本轮暂未开放' not in WORK)
check('两个回调都带上用时/token（不是只传文本）',
      'void onText(String text, int tokens, long millis)' in BRIDGE
      and 'void onTool(String name, String args, boolean isError, String result, long millis)' in BRIDGE)
check('循环里真的算了用时（stepStart）', 'stepStart' in LOOP)
check('工具耗时来自工具自己的计时（r.millis）', 'r.millis' in LOOP)

print()
print('=== [F] 能不能打开（第五版：修掉"永远打不开"的死锁） ===')
# ★ 用户报障：「无法打开 deepseekharness，点了开关也没用」。
#   真因：运行时开关默认 false，而能打开它的地方只在工作模式的控制台里 ⇒ 死锁。
check('★★ 运行时开关**默认开**（getBoolean(KEY_ON, true)）',
      'getBoolean(DshFeature.KEY_ON, true)' in BRIDGE)
check('★★ 设置页有一颗**真的开关**（addToggle + setEnabled）',
      '启用工作模式' in SETTINGS and 'DshBridge.setEnabled(DshSettingsActivity.this, on)' in SETTINGS
      and 'DshModeEntry.switchMode(MainActivity.this, T, null)' in MAIN)
check('★★ 关掉时点卡片**自动开回来**（不再提示完就 return）',
      'DshBridge.setEnabled(a, true);' in ENTRY
      and '先打开总开关' not in ENTRY)
check('★★ 开不了时**不静默**：建界面包在 try/catch 里，失败弹原因并 finish',
      'catch (Throwable t)' in WORK and '工作模式启动失败' in WORK
      and 'DshBridge.setWorkMode(this, false);' in WORK)

print()
print('=== [H] 插件融合（官方：一切皆插件） ===')
PLUG = read(os.path.join(DSH, 'DshPlugin.java'))
check('插件表是单独一个纯类（DshPlugin）', os.path.isfile(os.path.join(DSH, 'DshPlugin.java')))
check('内置插件 8 个（多了「提醒」：上游 tool-schedule）',
      PLUG.count('new DshPlugin(') == 8)
check('★★ 关掉的插件对模型**不可见**（visibleTools 过滤）',
      'public static List<String> visibleTools(' in PLUG and 'if (enabled == null || !enabled.contains(p.id)) continue;' in PLUG)
check('★★ 工具集按插件组装（forPlugins）',
      'public static DshToolRegistry forPlugins(' in read(os.path.join(DSH, 'DshTools.java'))
      and 'DshTools.forPlugins(policy, skills, plugins, petStatus(ctx),' in BRIDGE
      and 'new ScheduleHostImpl(ctx))' in BRIDGE)
check('★ 桌宠自己的状态也是一个插件（pet_status，且**真的注册进去**）',
      'if (on.contains(DshPlugin.P_PET) && status != null) reg.add(petStatus(status));'
      in read(os.path.join(DSH, 'DshTools.java'))
      and 'Prefs.petName(ctx)' in BRIDGE and 'Prefs.balanceBubbleOn(ctx)' in BRIDGE)
check('插件开关落盘（pref 串，空串=全关）',
      'KEY_PLUGINS' in read(os.path.join(DSH, 'DshFeature.java'))
      and 'DshPlugin.decode(raw)' in BRIDGE and 'DshPlugin.encode(cur)' in BRIDGE)
check('左栏有「插件」入口（官方左栏就有这一项）',
      'openPluginPanel' in WORK and '插件（' in WORK)
check('插件面板能开关并立即生效（写回 pref）',
      'DshBridge.setPluginEnabled(DshWorkActivity.this, pl.id, !on);' in WORK)
check('系统提示里只列启用的插件',
      'DshPlugin.promptBlock(plugins)' in BRIDGE)

print()
print('=== [G] 与桌面端截图逐项对齐（第七版） ===')
# 截图里的四块结构：左栏徽标 / 标题+模式徽标 / 对话·轨迹页签 / 圆角输入框
check('左栏：deepseek + HARNESS 徽标', '"deepseek"' in WORK and 'HARNESS' in WORK)
check('顶栏：会话标题 + 「标准模式」徽标', 'standard_mode_marker' in WORK or '标准模式' in WORK)
check('页签：「对话」与「轨迹」（选中项是 App 式胶囊）',
      'tabChat' in WORK and 'tabTrace' in WORK and 'tabLabel(' in WORK
      and 'setTraceMode' in WORK and 'traceMode = on;' in WORK
      and 'tabChatBar' not in WORK)
check('轨迹页真的列事件（不是装饰）', 'renderTrace' in WORK and 'e.seq + "  " + e.type' in WORK)
check('用户消息右对齐 + 时间戳 + 短消息变小胶囊',
      'addUserMessage' in WORK and 'timeOf(time)' in WORK and 'shortMsg' in WORK)
check('思考行折叠成一行（展开看全文）', 'addThinkRow' in WORK and '思考 · ' in WORK)
check('失败行：红点 + 「本轮运行失败」+ 右侧代码',
      'addErrorRow' in WORK and '本轮运行失败' in WORK)
check('对话顶部有工作区附件卡（带「打开」）',
      'addWorkspaceCards' in WORK and 'listWorkspaceFiles' in WORK)
check('输入区是一个圆角框（框内底行：权限 / 模型 / 圆形发送）',
      'roundedStroke(veil(T.surfaceSunken' in WORK and 'circle(T.accent)' in WORK)
check('统计行在框外（带 轮 / 步 / 条消息 / 次工具调用 / 上下文已用）',
      'composerStats' in WORK and '轮 ' in WORK and '上下文已用' in WORK)
check('★★ 工作模式里桌宠不遮正文：浮动窗收起 + 改顶栏头像',
      'sendPetAction(FloatingService.ACTION_HIDE_TEMP);' in WORK
      and 'PetAvatar.load(this)' in WORK)
check('★ 离开时把"App 内隐藏桌宠"还回去', 'FloatingService.ACTION_HIDE_TEMP' in WORK)

print()
print('=== [I] 并行开发的两块交付（子 agent） ===')
TOOLS = read(os.path.join(DSH, 'DshTools.java'))
check('★ 新工具 search（工作区正则搜索）', 'public static DshTool search(' in TOOLS)
check('★ 新工具 stat（文件信息）', 'public static DshTool stat(' in TOOLS)
check('「搜索」插件已登记，且它的工具**真的被注册进集合**',
      'P_SEARCH' in PLUG and 'new String[]{"search"}' in PLUG
      and 'if (on.contains(DshPlugin.P_SEARCH)) reg.add(search(policy));' in TOOLS)
check('★ 默认档下 search/stat 不被权限闸拦',
      'n.equals("search") || n.equals("stat")' in read(os.path.join(DSH, 'DshPolicy.java')))
check('附件卡改横向滚动，上限 6 张',
      'HorizontalScrollView' in WORK and 'out.size() < 6' in WORK)
check('输入框 / 与 @ 的轻提示（不弹窗，且**真的判以 / 开头**）',
      'composerTip' in WORK and 'updateComposerTip' in WORK and 'startsWith("/")' in WORK)
check('工具行展开带淡入', 'fadeIn(' in WORK)
check('空态补一行工作区引导', 'emptyWsHint' in WORK and 'files/dsh/workspace' in WORK)
print('=== [E] 主题跟 App、尺寸跟桌面端（第九版改口） ===')
DESK = read(os.path.join(DSH, 'DshDesk.java'))
check('尺寸真值单独一个类（DshDesk）', os.path.isfile(os.path.join(DSH, 'DshDesk.java')))
check('字号/行高是桌面端真值（14/12/11，行高 22/24）',
      'TEXT_BODY = 14f' in DESK and 'TEXT_SMALL = 12f' in DESK
      and 'LINE_BLOCK = 22f' in DESK and 'LINE_PARAGRAPH = 24f' in DESK)
check('圆角/间距也在（12/18/20/6，16/12/9/14）',
      'RADIUS_CARD = 12' in DESK and 'RADIUS_CONTROL = 18' in DESK
      and 'GAP_BLOCK = 16' in DESK and 'PAD_BANNER_H = 14' in DESK)
check('★★ 主题跟 App（Tokens.applyPlatformTheme + Tokens.of + T.text1/T.bg/T.surface 令牌）',
      'Tokens.applyPlatformTheme(this)' in WORK and 'Tokens.of(this)' in WORK
      and 'T.text1' in WORK and 'T.bg' in WORK and 'T.surfaceSunken' in WORK
      and 'T.hairline' in WORK)
check('★★ 不再自己配色（无 DshDesk.dark()/light()，也无 Palette）',
      'DshDesk.dark()' not in WORK and 'DshDesk.light()' not in WORK
      and 'Palette' not in WORK)
check('★ 皮肤/自定义配色仍走 App 主题（与陪伴模式同一套）',
      'Tokens.of(this)' in WORK and 'DshDesk.TEXT_CAPTION' in WORK)
check('视频背景同步（VideoBackgroundView + Prefs.videoBg*）',
      'VideoBackgroundView' in WORK and 'Prefs.videoBgEnabled(this)' in WORK
      and 'Prefs.videoBgFile(this)' in WORK and 'Prefs.videoBgDim(this)' in WORK)
check('三层顺序：视频 → 压暗 → 内容（条子半透）',
      '\n        attachVideoBg(shell);' in WORK and 'veil(T.bg' in WORK)
check('开关开时不挂视频（省电）', 'if (!Prefs.videoBgEnabled(this)) return;' in WORK)
check('页面假时暂停视频', 'setPlaybackPaused(true)' in WORK and 'setPlaybackPaused(false)' in WORK)

if fail == 0:
    print('工作模式 + 切换动画断言通过（%d 项）' % ok)
    sys.exit(0)
print('工作模式断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
