# -*- coding: utf-8 -*-
"""反向验证：把「工作模式 + 切换动画」的关键判据一个个改坏，确认断言真的会红。

破坏项：
  A. 工作模式页面不调 applyPlatformTheme（深色下系统控件白底）
  B. 工作模式页面写死一个颜色
  C. 「停止生成」不再接内核（按了没反应 = 用户以为坏了）
  D. 切换动画改成「先 startActivity 再播动画」（顺序反了 ⇒ 白屏闪）
  E. 视频播放去掉时长上限（一个 30 秒的视频会把切换变成折磨）
  F. 导入不再校验 mime（拿任意文件当动画 ⇒ 黑屏）
  G. 清单里摘掉工作模式 Activity（点了切模式就崩）

★ 与其它反向套件同一条纪律：脚本会**临时改源码再恢复**，只能后台跑、不许中途强杀
  （被强杀会把源码留在改坏的状态 —— 本工程真发生过一次）。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DSH = os.path.join(SRC, 'dsh')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')
VERIFY = os.path.join(ROOT, 'build', 'verify_dsh_work.py')

WORK = os.path.join(SRC, 'DshWorkActivity.java')
ENTRY = os.path.join(SRC, 'DshModeEntry.java')
PLAYER = os.path.join(DSH, 'DshTransitionPlayer.java')
BRIDGE = os.path.join(DSH, 'DshBridge.java')


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    raw = (r.stdout or b'') + (r.stderr or b'')
    try:
        out = raw.decode('utf-8')
    except UnicodeDecodeError:
        out = raw.decode('gbk', 'replace')
    return r.returncode, out


def trial(name, path, old, new):
    if not os.path.isfile(path):
        print('  !! %-46s 文件不存在，跳过' % name)
        return None
    s = io.open(path, encoding='utf-8').read()
    n = s.count(old)
    if n == 0:
        print('  !! %-46s 找不到片段（重构过？），跳过' % name)
        return None
    if n > 1:
        print('  !! %-46s 片段出现 %d 次（不唯一），跳过以免改错位置' % (name, n))
        return None
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
        code, out = run_verify()
        red = (code != 0) or ('[FAIL]' in out)
        why = [ln.strip() for ln in out.splitlines() if '[FAIL]' in ln]
        if red:
            print('  [OK]   %-46s 断言变红（%s）' % (name, (why[0][:56] if why else '退出码 %d' % code)))
            return True
        print('  [FAIL] %-46s 改坏了却没红 —— 断言是空转的！' % name)
        return False
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：工作模式 + 切换动画的断言必须抓得住破坏 ===')
results = []
results.append(trial('A. 工作模式不调 applyPlatformTheme', WORK,
                     'Tokens.applyPlatformTheme(this);', ';'))
results.append(trial('B. 工作模式里写死颜色', WORK,
                     '        FrameLayout layer = new FrameLayout(this);\n'
                     '        layer.setBackgroundColor(T.scrim);\n'
                     '        layer.setOnClickListener(new View.OnClickListener() {\n'
                     '            @Override public void onClick(View v) { toggleDrawer(false); }',
                     '        FrameLayout layer = new FrameLayout(this);\n'
                     '        layer.setBackgroundColor(0x66000000);\n'
                     '        layer.setOnClickListener(new View.OnClickListener() {\n'
                     '            @Override public void onClick(View v) { toggleDrawer(false); }'))
results.append(trial('C. 「停止生成」不再接执行层', WORK,
                     '        if (coord != null) coord.cancel();',
                     '        /* 停止生成被摘掉了 */'))
results.append(trial('D. 绕过动画直接 startActivity', ENTRY,
                     '        DshBridge.setWorkMode(a, true);\n'
                     '        DshTransitionPlayer.play(',
                     '        a.startActivity(new Intent(a, DshWorkActivity.class));\n'
                     '        DshBridge.setWorkMode(a, true);\n'
                     '        DshTransitionPlayer.play('))
results.append(trial('E. 视频去掉时长上限', PLAYER,
                     '        ui.postDelayed(close, DshTransition.VIDEO_MAX_MS);',
                     '        // 上限被摘掉了：视频播多久就等多久'))
results.append(trial('F. 导入不再校验 mime', BRIDGE,
                     '        if (mime != null && mime.length() > 0 && !mime.startsWith("video/")) {',
                     '        if (false) {'))
results.append(trial('G. 清单摘掉工作模式 Activity', MANIFEST,
                     '        <activity\n            android:name=".DshWorkActivity"',
                     '        <activity\n            android:name=".DshWorkActivityGone"'))
# ★ 用户明确要求"集成在角色卡里、不要按钮"：把卡片上的点击摘掉，就等于把入口弄没了。
results.append(trial('H. 角色卡不再可点（入口消失）', os.path.join(SRC, 'MainActivity.java'),
                     '        if (com.deepseek.pet.dsh.DshFeature.ENABLED) {\n'
                     '            card.setClickable(true);',
                     '        if (false) {\n'
                     '            card.setClickable(true);'))
# 第三版（用户报障点卡片切模式老误触改名）：名字收进 nameRow、头像可换图。
# 这两条各摘一个，验证"改名只在名字上""头像能换形象"真的被钉住。
results.append(trial('I. 头像不再可点（换 App 头像入口消失）', os.path.join(SRC, 'MainActivity.java'),
                     'PetAvatar.pick(MainActivity.this);',
                     '/* 头像不可点了 */'))
results.append(trial('J. 名字不再可点（改名入口消失）', os.path.join(SRC, 'MainActivity.java'),
                     '        name.setOnClickListener(new View.OnClickListener() {\n'
                     '            @Override public void onClick(View v) { promptPetName(); }',
                     '        name.setOnClickListener(null);\n'
                     '        if (false) new View.OnClickListener() {\n'
                     '            @Override public void onClick(View v) { promptPetName(); }'))
results.append(trial('去掉「加载更早」', os.path.join(SRC, 'DshWorkActivity.java'),
                     '            Button more = desk("加载更早（还有 " + from + " 条）", false);\n'
                     '            more.setTextSize(DshDesk.TEXT_CAPTION);\n'
                     '            more.setOnClickListener(new View.OnClickListener() {\n'
                     '                @Override public void onClick(View v) {\n'
                     '                    historyWindow += 200;\n'
                     '                    renderHistory();\n'
                     '                }\n'
                     '            });\n'
                     '            LinearLayout wrap = new LinearLayout(this);\n'
                     '            wrap.setGravity(Gravity.CENTER);\n'
                     '            wrap.setPadding(0, dp(2), 0, dp(6));',
                     '            Button more = desk("加载更少", false);\n'
                     '            more.setTextSize(DshDesk.TEXT_CAPTION);\n'
                     '            more.setOnClickListener(new View.OnClickListener() {\n'
                     '                @Override public void onClick(View v) {\n'
                     '                    historyWindow += 200;\n'
                     '                    renderHistory();\n'
                     '                }\n'
                     '            });\n'
                     '            LinearLayout wrap = new LinearLayout(this);\n'
                     '            wrap.setGravity(Gravity.CENTER);\n'
                     '            wrap.setPadding(0, dp(2), 0, dp(6));'))
results.append(trial('L. 工具卡片不再显示用时', os.path.join(SRC, 'DshWorkActivity.java'),
                     '        String used = millis > 0 ? ("  \u00b7  " + durLabel(millis)) : "";',
                     '        String used = "";'))

results.append(trial('M. 头像又接回 pickImage（误改形象）', os.path.join(SRC, 'MainActivity.java'),
                     'PetAvatar.pick(MainActivity.this);',
                     'pickImage();'))
results.append(trial('N. 工作模式又去自己配色（写死桌面色，不跟 App 主题）',
                     os.path.join(SRC, 'DshWorkActivity.java'),
                     '        titleView.setTextColor(T.text1);',
                     '        titleView.setTextColor(0xFFF9FAFB);'))
results.append(trial('O. 去掉视频背景（不同步 App）', os.path.join(SRC, 'DshWorkActivity.java'),
                     '        attachVideoBg(shell);',
                     '        // attachVideoBg(shell);'))

results.append(trial('P. 运行时开关改回默认关（又打不开）', os.path.join(DSH, 'DshBridge.java'),
                     'return prefs(ctx).getBoolean(DshFeature.KEY_ON, true);',
                     'return prefs(ctx).getBoolean(DshFeature.KEY_ON, false);'))
results.append(trial('Q. 工作模式设置页把开关摘掉', os.path.join(SRC, 'DshSettingsActivity.java'),
                     '                DshBridge.setEnabled(DshSettingsActivity.this, on);',
                     '                /* 开关被摘掉了 */'))
results.append(trial('R. 关掉时又改回"提示完就 return"', ENTRY,
                     '            DshBridge.setEnabled(a, true);',
                     '            Toast.makeText(a, "先打开总开关", Toast.LENGTH_LONG).show(); return;'))

results.append(trial('S. 工具行变回不可展开的一行', os.path.join(SRC, 'DshWorkActivity.java'),
                     'title.setText("⌄ " + toolLabel(name)',
                     'title.setText("" + toolLabel(name)'))
results.append(trial('T. 桌宠头像不再显示在顶栏', os.path.join(SRC, 'DshWorkActivity.java'),
                     '            android.graphics.Bitmap bm = PetAvatar.load(this);',
                     '            android.graphics.Bitmap bm = null;'))
results.append(trial('U. 轨迹页签点了不生效', os.path.join(SRC, 'DshWorkActivity.java'),
                     '        traceMode = on;',
                     '        traceMode = false;'))

results.append(trial('V. 工具集不按插件组装（一律全给）', os.path.join(DSH, 'DshBridge.java'),
                     '        DshToolRegistry tools = DshTools.forPlugins(policy, skills, plugins, petStatus(ctx),\n'
                     '                new ScheduleHostImpl(ctx));',
                     '        DshToolRegistry tools = DshTools.standard(policy, skills);'))
results.append(trial('W. 插件开关不写回 pref', os.path.join(SRC, 'DshWorkActivity.java'),
                     '                    DshBridge.setPluginEnabled(DshWorkActivity.this, pl.id, !on);',
                     '                    /* 开关不写回 */'))
results.append(trial('X. 去掉桌宠自己的 pet_status 工具', os.path.join(DSH, 'DshTools.java'),
                     '        if (on.contains(DshPlugin.P_PET) && status != null) reg.add(petStatus(status));',
                     '        if (false) reg.add(petStatus(status));'))

results.append(trial('Y. 「搜索」插件不注册工具（装了也没用）', os.path.join(DSH, 'DshTools.java'),
                     '        if (on.contains(DshPlugin.P_SEARCH)) reg.add(search(policy));',
                     '        if (false) reg.add(search(policy));'))
results.append(trial('Z. 输入框 / 提示失效', os.path.join(SRC, 'DshWorkActivity.java'),
                     'startsWith("/")',
                     'startsWith("##")'))

bad = [r for r in results if r is False]
skipped = [r for r in results if r is None]
print()
# ★ 跳过的按**未验证**计（本工程的老规矩：没被抓到的破坏不能算"通过"——
#   否则一次重构让锚点全部失效，这套件会静默变绿）。
if not bad and not skipped:
    print('反向验证通过：%d 项破坏全部被抓到（跳过 0 项）' % len(results))
    sys.exit(0)
print('反向验证失败：%d 项破坏没被抓到，%d 项锚点未命中（按未验证计）'
      % (len(bad), len(skipped)))
sys.exit(1)
