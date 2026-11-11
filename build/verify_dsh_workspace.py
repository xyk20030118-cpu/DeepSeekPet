# -*- coding: utf-8 -*-
"""第十四版：**工作区（多工作区 + 左栏工作区树 + 会话标题/相对时间）** 的断言套件。

三段：
  [A] 工作区模型（纯类）：默认工作区沿用老路径、名字校验挡住路径穿越、独立会话目录、相对时间纯函数；
  [B] 接线：活动工作区 → 沙箱根 / 会话目录 / 每工作区会话指针 / 会话标题（session/title）；
  [C] 界面：左栏「工作区 → 会话」树、默认 3 条 + 「展开其余 N 个会话」、📁 面板切换/新建、
      任务在跑时拒绝切换、设置页也能切。

★ 铁律：只读源码，不改工程文件；行为（相对时间/名字校验/标题）由 build/dshtest 第 [14] 节真编真跑覆盖，
这里查"接线与口径"。禁止只看一句注释就判绿。
"""
import io
import os
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')

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


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只认活代码）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
        elif text.startswith('/*', i):
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        else:
            out.append(text[i])
            i += 1
    return ''.join(out)


def method_body(text, signature):
    i = text.find(signature)
    if i < 0:
        return ''
    depth = 0
    j = text.find('{', i)
    if j < 0:
        return ''
    k = j
    while k < len(text):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
        k += 1
    return text[j:]


WS = read(os.path.join(DSH, 'DshWorkspace.java'))
BRIDGE = read(os.path.join(DSH, 'DshBridge.java'))
SESSION = read(os.path.join(DSH, 'DshSession.java'))
FEATURE = read(os.path.join(DSH, 'DshFeature.java'))
WORK = read(os.path.join(SRC, 'DshWorkActivity.java'))
SETTINGS = read(os.path.join(SRC, 'DshSettingsActivity.java'))

WORK_CODE = strip_comments(WORK)
SETTINGS_CODE = strip_comments(SETTINGS)

# ==================== [A] 工作区模型（纯类） ====================

print('=== [A] 工作区模型：默认路径 / 名字校验 / 独立会话目录 / 相对时间 ===')
check('DshWorkspace.java 存在', len(WS) > 2000, '%d 字节' % len(WS))
check('★ 纯类：不 import android', 'import android' not in WS)
check('★ 纯类：活代码里不出现 android.',
      'android.' not in strip_comments(WS))
check('默认工作区沿用老路径 workspace/ + sessions/（老会话不迁移）',
      'new File(b, "workspace"), new File(b, "sessions")' in WS)
check('新建工作区落在 workspaces/<id>/', 'File root = new File(b, "workspaces");' in WS)
check('新工作区的会话目录独立：sessions/<id>/',
      'new File(new File(b, "sessions"), id)' in WS and
      'new File(new File(b, "sessions"), dir.getName())' in WS)
check('显示名单独存 name.txt（目录名只是安全 slug）', 'new File(dir, "name.txt")' in WS)

check('空名字被拒绝', 'if (n.length() == 0) return "名字不能为空";' in WS)
check('超长名字被拒绝', 'if (n.length() > MAX_NAME)' in WS)
check('★ 点开头被拒绝（隐藏目录 / 路径穿越）',
      'if (n.startsWith(".")) return "名字不能以点开头";' in WS)
check('★ 路径分隔符/通配符逐个拒绝',
      "c == '/' || c == '\\\\' || c == ':' || c == '*' || c == '?'" in WS
      and "c == '\"' || c == '<' || c == '>' || c == '|'" in WS)
check('★ 重名检查忽略大小写（含默认工作区）',
      'if (w.name.equalsIgnoreCase(n)) return "已有同名工作区：" + w.name;' in WS)
check('create 先校验再建目录（非法名不落盘）',
      'if (validate(b, raw) != null) return null;' in WS)
check('slug 冲突时自动换名（不会覆盖已有工作区）',
      'while (dir.exists() && n < 200)' in WS)
check('建目录后写 name.txt + 建会话目录',
      'writeName(dir, name);' in WS and
      'if (!sessions.isDirectory()) sessions.mkdirs();' in WS)

_rt = method_body(WS, 'public static String relativeTime(')
check('相对时间是纯函数（now 由调用方给）',
      'public static String relativeTime(long now, long then)' in WS
      and 'System.currentTimeMillis' not in _rt)
check('未来时间按 0 处理（时钟回拨不显示负数）', 'if (d < 0) d = 0;' in _rt)
check('分钟档 / 小时档', 'return min + "分钟";' in _rt and 'return h + "小时";' in _rt)
check('天档 / 月档 / 年档',
      'return day + "天";' in _rt and 'return mon + "个月";' in _rt
      and '(mon / 12L) + "年"' in _rt)

# ==================== [B] 接线：路径 / 会话指针 / 标题 ====================

print()
print('=== [B] 接线：活动工作区 → 沙箱根 / 会话目录 / 每工作区会话指针 / 标题 ===')
check('DshFeature 有当前工作区键 KEY_WORKSPACE', 'KEY_WORKSPACE = "dsh_workspace"' in FEATURE)
check('DshBridge 暴露 baseDir（给 DshWorkspace 用）',
      'public static File baseDir(Context ctx)' in BRIDGE)
check('DshBridge 暴露 workspaces()（默认在前）',
      'public static List<DshWorkspace> workspaces(Context ctx)' in BRIDGE)
check('★ 工作区目录被删 ⇒ 自动回退默认（工作台不会打不开）',
      'if (DshWorkspace.find(baseDir(ctx), id) == null)' in BRIDGE
      and 'DshFeature.KEY_WORKSPACE, DshWorkspace.DEFAULT_ID' in BRIDGE)
check('★ 沙箱根跟着活动工作区走（不再写死 workspace/）',
      'File f = activeWorkspace(ctx).dir;' in method_body(BRIDGE, 'public static File workspaceDir('))
check('★ 会话目录跟着活动工作区走',
      'File f = activeWorkspace(ctx).sessions;' in method_body(BRIDGE, 'public static File sessionsDir('))
check('权限沙箱仍用 workspaceDir（唯一出口没变）',
      'new DshPolicy(mode(ctx), workspaceDir(ctx).getAbsolutePath())' in BRIDGE)
check('★ 每个工作区有自己的会话指针（切回来还是上次那个会话）',
      'return DshFeature.KEY_SESSION + (id.length() == 0 ? "" : "_" + id);' in BRIDGE)
check('setWorkspace 只写 pref，不删任何会话/文件',
      '.delete(' not in method_body(BRIDGE, 'public static void setWorkspace(') and
      '.delete(' not in method_body(BRIDGE, 'public static DshWorkspace createWorkspace('))
check('新建工作区走 DshWorkspace.create（校验只有一处）',
      'return DshWorkspace.create(baseDir(ctx), name);' in BRIDGE)
check('左栏点会话用 useSession（键跟着当前工作区）',
      'public static void useSession(Context ctx, String sessionId)' in BRIDGE
      and 'prefs(ctx).edit().putString(sessionKey(ctx), id).apply();' in BRIDGE)
check('★ 会话列表带标题 + 最后修改时间（左栏要显示「7小时」而不是条数）',
      'new String[]{id, titleOf(id, ev), String.valueOf(ev.size()),' in BRIDGE
      and 'String.valueOf(log.fileFor(id).lastModified())' in BRIDGE)
check('标题优先 session/title，老会话退回第一条用户消息',
      'DshEvent.SESSION_TITLE.equals(e.type)' in BRIDGE
      and 'DshEvent.USER_MESSAGE.equals(e.type)' in BRIDGE
      and BRIDGE.find('DshEvent.SESSION_TITLE') < BRIDGE.find('DshEvent.USER_MESSAGE'))
check('折叠的工作区只数条数、不加载事件',
      'public static int sessionCount(Context ctx, DshWorkspace ws)' in BRIDGE
      and 'new DshSessionLog(ws.sessions).listIds().size()' in BRIDGE)

_set_title = method_body(SESSION, 'public DshEvent setTitle(')
check('★ 标题只记一次（第二条消息不覆盖用户认得的名字）',
      'if (title != null && title.trim().length() > 0) return null;' in _set_title)
check('标题落成上游同名事件 session/title',
      'append(DshEvent.SESSION_TITLE' in _set_title)
check('★ 发消息时先起标题再写 user/message（顺序不能反）',
      'session.setTitle(userText.trim());' in BRIDGE and
      BRIDGE.find('session.setTitle(userText.trim());') <
      BRIDGE.find('session.userMessage(userText.trim());'))

# ==================== [C] 界面：工作区树 / 面板 / 忙碌保护 ====================

print()
print('=== [C] 界面：左栏工作区树 / 📁 面板 / 忙碌时拒绝切换 ===')
check('左栏建了工作区树容器 wsTree', 'wsTree = new LinearLayout(this);' in WORK)
check('抽屉里重填工作区树', 'fillWorkspaceTree();' in method_body(WORK, 'private FrameLayout buildDrawerLayer('))
_tree = method_body(WORK, 'private void fillWorkspaceTree()')
check('树是「工作区 → 会话」两级（先工作区名，再会话行）',
      '"📁 " + w.name' in _tree and 'sessionRow(r, r[0].equals(currentSession))' in _tree)
check('每个工作区下面显示目录（参考图那一行路径）',
      'path.setText(w.dir.getAbsolutePath());' in _tree)
check('★ 默认只显示 3 条会话（参考图就是 3 条 + 展开其余）',
      'int show = wsExpanded ? rows.size() : Math.min(3, rows.size());' in _tree)
check('★ 「展开其余 N 个会话」是真折叠（点了才 wsExpanded = true）',
      '"展开其余 " + (rows.size() - 3) + " 个会话 ⌄"' in _tree
      and 'wsExpanded = true;' in _tree)
check('非当前工作区显示会话条数 + 「点一下切换」',
      '" 个会话" + " · 点一下切换"' in _tree or '" 个会话") + " · 点一下切换"' in _tree)
check('会话行：标题取自 row[1]，时间取 row[3]',
      'tt.setText(row[1]);' in WORK and 'ageLabel(when)' in WORK
      and 'Long.parseLong(row[3])' in WORK)
check('相对时间真的走 DshWorkspace.relativeTime',
      'DshWorkspace.relativeTime(System.currentTimeMillis(), ms)' in WORK)
check('点会话用 useSession 打开（不直接改 pref 键）',
      'DshBridge.useSession(DshWorkActivity.this, row[0]);' in WORK)

check('输入框上方 📁 chip 打开工作区面板',
      'wsChip = chip("📁 " + wsName + " ⌄"' in WORK
      and 'openWorkspaceSheet();' in WORK)
check('★ 旧的「多工作区本轮未支持」死文案已删（不再说假话）',
      '多工作区本轮未支持' not in WORK_CODE)
check('工作区面板有 ☑/☐ 与 ✓（参考图那个选择态）',
      '"☑ " ' in WORK or '(active ? "☑ " : "☐ ")' in WORK)
check('工作区面板有「＋ 新建工作区…」',
      '"＋ 新建工作区…"' in WORK)
_form = method_body(WORK, 'private void fillWorkspaceSheet()')
check('新建表单是页面内的（EditText + 创建 + 取消），不弹 AlertDialog',
      'wsNameInput = new EditText(this);' in _form and 'desk("创建", true)' in _form
      and 'desk("取消", false)' in _form)
check('★ 新建前先过 DshWorkspace.validate（非法名不落盘）',
      'String why = DshWorkspace.validate(DshBridge.baseDir(this), name);' in
      method_body(WORK, 'private void submitNewWorkspace()'))
check('★ 新建成功且空闲才切过去；有任务只提示',
      'if (isBusy()) {' in method_body(WORK, 'private void submitNewWorkspace()')
      and '等这轮跑完再切过去' in WORK)

_sw = method_body(WORK, 'private void switchWorkspace(')
check('★ 切换前检查忙碌（一轮对话中途换沙箱根会丢文件）',
      'if (isBusy()) {' in _sw and '先「停止生成」再切工作区' in _sw)
check('忙碌判定看协调器的活动任务（不只是页面自己的 running）',
      'return t != null && t.active();' in method_body(WORK, 'private boolean isBusy()'))
check('切换后清页面缓存的会话并重绘（新工作区的新会话）',
      'session = null;' in _sw and 'renderHistory();' in _sw)
check('切换后 chip 标签跟着变', 'wsChip.setText("📁 " + w.name + " ⌄");' in _sw)
check('切换不动任何会话文件（只换指针）', '.delete(' not in _sw)

check('设置页列出全部工作区（✓ = 当前）',
      'DshBridge.workspaces(this)' in SETTINGS and '(active ? "✓ " : "☐ ")' in SETTINGS)
check('设置页也能切工作区（走 DshBridge.setWorkspace）',
      'DshBridge.setWorkspace(DshSettingsActivity.this, ws.id);' in SETTINGS)
check('★ 设置页切换前也看 DshBridge.isRunning()（有任务不许换根）',
      'if (DshBridge.isRunning()) {' in SETTINGS)
check('两页都不使用 AlertDialog（项目硬约束）',
      'AlertDialog' not in WORK_CODE and 'AlertDialog' not in SETTINGS_CODE)

print()
if fail == 0:
    print('工作区断言通过（%d 项）' % ok)
    sys.exit(0)
print('工作区断言失败：ok=%d fail=%d' % (ok, fail))
sys.exit(1)
