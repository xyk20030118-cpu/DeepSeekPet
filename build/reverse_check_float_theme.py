"""反向验证：浮层主题断言必须能抓到"漏改一处"和"回潮"。

★ 这类改动的最大风险是**漏改一处**：四处浮层只要有一处还写死浅色，
  深色主题下那块就是刺眼的白，而这种不一致在静态代码里很难发现
  （要同时看四个地方）。所以每个破坏都针对"某处的某一段"。
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
FS = r'D:\petport_lab\petbuild\src\com\deepseek\pet\FloatingService.java'
SCRIPT = r'D:\petport_lab\petbuild\build\verify_float_theme.py'


def run():
    r = subprocess.run([sys.executable, SCRIPT], capture_output=True)
    return r.returncode, r.stdout.decode('utf-8', 'replace')


def trial(name, old, new, unique=True):
    bak = FS + '.rv'
    shutil.copy2(FS, bak)
    try:
        s = io.open(FS, encoding='utf-8-sig').read()
        n = s.count(old)
        if n == 0:
            # ★ 跳过 = **没验证**，必须算失败。
            #   旧版返回 None，而收尾只统计非 None 的项，
            #   于是"9 条里跳过 3 条"会显示成"6/6 全抓到" ——
            #   这是个会骗人的计数：真实的覆盖只有 6/9。
            #   片段找不到通常意味着代码重构过、测试没跟上，
            #   那正是最需要被指出来的状态。
            print('  ✗ %-46s 找不到片段 → 未验证（视为失败）' % name)
            return False
        if unique and n > 1:
            print('  ✗ %-46s 片段出现 %d 次 → 未验证（视为失败）' % (name, n))
            return False
        io.open(FS, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, out = run()
        caught = (rc != 0)
        print('  %s %-46s 退出码=%d' % ('✓' if caught else '✗', name, rc))
        if not caught:
            print('       （没抓到 → 这条断言是空转的）')
        return caught
    finally:
        shutil.copy2(bak, FS)
        os.remove(bak)


print('=== 反向验证：浮层主题断言必须能抓到回潮 ===')
print()
results = []

# A. 输入框底改回写死白
results.append(trial(
    'A. 输入框底改回写死白',
    'card.setBackground(floatPanelBg(FLOAT_RADIUS_DP));\n        Tokens.Set T = uiT();',
    'card.setBackground(roundRect(Color.parseColor("#F2FFFFFF"), d * 16, 0));\n'
    '        Tokens.Set T = uiT();'))

# B. 输入框文字改回写死
results.append(trial(
    'B. 输入框文字改回写死',
    'askInput.setTextColor(T.text1);          // 原来是写死 #222222',
    'askInput.setTextColor(0xFF222222);'))

# C. 气泡用回静态 XML
#    片段必须带上下文：floatPanelBg(THEME_RADIUS_DP) 现在出现 2 次
#    （创建块 + 主题刷新块），只写这一行会被 unique 检查判为"未验证"。
results.append(trial(
    'C. 气泡用回静态 XML',
    'bubbleBody.setBackground(floatPanelBg(THEME_RADIUS_DP));\n'
    '            // 主体内边距要大，确保文字永远不会溢出云朵以外',
    'bubbleBody.setBackgroundResource(R.drawable.bubble_chat);\n'
    '            // 主体内边距要大，确保文字永远不会溢出云朵以外'))

# D. 气泡尖角用回静态 XML
results.append(trial(
    'D. 气泡尖角用回静态 XML',
    'bubbleTail = new PointerView(this, panelFillColor(), panelEdgeColor(), d);',
    'bubbleTail = new View(this);\n'
    '            bubbleTail.setBackgroundResource(R.drawable.bubble_tail);'))

# E. 动作菜单用回静态 XML（菜单已改用 UiKit.card 主体卡片表面）
results.append(trial(
    'E. 动作菜单用回静态 XML',
    'body.setBackground(UiKit.card(this, Tokens.of(this), Tokens.R_DIALOG));\n'
    '            int panelPad = UiKit.panelInset(this) + UiFactory.dp(this, 8);',
    'body.setBackgroundResource(R.drawable.bubble_menu);\n'
    '            int panelPad = UiKit.panelInset(this) + UiFactory.dp(this, 8);'))

# F. 动作菜单行改回写死颜色
#    主题色现在由 newChip → UiFactory.button 取，所以破坏点在这条调用上。
results.append(trial(
    'F. 动作菜单行改回写死颜色',
    'android.widget.TextView tv = UiFactory.button(this, Tokens.of(this), name, primary);',
    'android.widget.TextView tv = UiFactory.button(this, Tokens.of(this), name, primary);\n'
    '        tv.setTextColor(0xFF2E6FC0);'))

# G. 长按行改回写死色（两行已统一气泡同款，任改一行即回潮）
results.append(trial(
    'G. 长按「退出」行改回写死',
    'T.surface, T.text1, new View.OnClickListener() {\n'
    '            @Override public void onClick(View v) { exitPetWithFarewell(); }',
    '0xFFFFEFEF, 0xFF9E3232, new View.OnClickListener() {\n'
    '            @Override public void onClick(View v) { exitPetWithFarewell(); }'))

# H. menuPanelBg 不转发（自己写一份渐变）
results.append(trial(
    'H. menuPanelBg 自己写渐变',
    '    private android.graphics.drawable.Drawable menuPanelBg(float d) {\n'
    '        return floatPanelBg(THEME_RADIUS_DP);\n    }',
    '    private android.graphics.drawable.Drawable menuPanelBg(float d) {\n'
    '        GradientDrawable g = new GradientDrawable();\n'
    '        g.setColor(0xFFF5FAFF);\n'
    '        return g;\n    }'))

# I. 余额气泡用回静态 XML
results.append(trial(
    'I. 余额气泡用回静态 XML',
    'text.setBackground(floatPanelBg(THEME_RADIUS_DP));',
    'text.setBackgroundResource(R.drawable.bubble_balance);'))

# J. ★ 二级页去掉「返回分类」入口（用户会退不回一级）
results.append(trial(
    'J. 二级页去掉返回分类入口',
    'back.setText("‹ 返回分类");',
    'back.setText("动作");'))

# K. ★ 叶子动作不再播放（点动作没反应）
results.append(trial(
    'K. 叶子动作不再播放',
    '                hideActionBubble();\n                playActionByName(name);',
    '                hideActionBubble();'))

# L. ★ 菜单底板不再用主体卡片表面
results.append(trial(
    'L. 菜单底板不再用 UiKit.card',
    '            body.setBackground(UiKit.card(this, Tokens.of(this), Tokens.R_DIALOG));',
    '            body.setBackground(floatInputBg());'))

# M. ★ 长按行根改回 WRAP_CONTENT（行不再被 EXACTLY 约束，会溢出面板）
results.append(trial(
    'M. 长按行根改回 WRAP_CONTENT',
    '        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(\n'
    '                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);\n'
    '        lp.topMargin = lp.bottomMargin = (int) (d * 3 * bscale);',
    '        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(\n'
    '                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);\n'
    '        lp.topMargin = lp.bottomMargin = (int) (d * 3 * bscale);'))

# ============================================================ 轮次76：余额气泡总开关
# ★ 用户原话「增加气泡显示的开关，可以关闭，不显示余额气泡」——
#   这一组守的是"总开关真的能拦住每一条显示路径、并且真的停掉轮询"。
results.append(trial(
    'N. 显示路径绕开总开关（关了还能弹出来）',
    'if (show && !Prefs.balanceBubbleOn(this)) show = false;',
    'if (false) show = false;'))

results.append(trial(
    'O. 总开关关掉了、轮询还在跑（白白发请求）',
    'if (!Prefs.balanceBubbleOn(FloatingService.this)) return;',
    'if (false) return;'))

results.append(trial(
    'P. 重建悬浮窗时不看总开关（重启后气泡又冒出来）',
    'isVisible = Prefs.balanceBubbleOn(this) && Prefs.showBalance(this);',
    'isVisible = Prefs.showBalance(this);'))

rc, out = run()
print()
print('  恢复后退出码 = %d' % rc)
if rc != 0:
    for line in out.splitlines():
        if 'FAIL' in line:
            print('     ' + line.strip()[:100])

good = [r for r in results if r is not None]
print()
# ★ 分母用**全部** trial 数，不用"跑起来的条数" ——
#   否则跳过 3 条会显示成 "6/6 全抓到"，掩盖真实的覆盖缺口。
print('  %d/%d 个破坏被断言抓到（跳过按未验证计）'
      % (sum(1 for r in results if r), len(results)))
sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
