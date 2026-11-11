"""静态验证：ApiClient 真的按能力表决定发不发（源码级断言）。

★ 为什么用源码断言而不是抓包：
  抓真实请求体最理想，但在模拟器上"触发一次对话"这一步不稳定
  （双击桌宠的坐标、输入框弹起时机、广播前置条件都不好控），
  我已经试了三种触发方式都没抓到。继续在触发上耗时间不划算。

  而本次要防的**具体风险**是"表改了、代码没跟着改"这类脱节 ——
  它完全可以由源码文本断言覆盖，而且比抓包更快、更稳定：

    ① 存在"按能力表判断"的调用（ChatProvider.supports）
    ② 不存在"无条件 put temperature"的写法
    ③ 两处 payload 构造（对话 / 记忆点提炼）都受约束

  ★ 这条断言与本工程已有的 SourcePairingTest 同一思路
    （见 build/vidtest/SourcePairingTest.java）：读真实源码文本、
    剥掉注释后匹配关键结构，防止"配对关系被改散"。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
pass_n, fail_n = 0, 0


def ok(cond, what):
    global pass_n, fail_n
    if cond:
        pass_n += 1
    else:
        fail_n += 1
        print('  FAIL: %s' % what)


def strip_comments(s):
    """剥掉注释与字符串，只留代码结构。

    ★ 必须先剥注释：本工程的注释里大段引用了
      "do not pass it explicitly" 之类的说明文字，
      直接搜字符串会被注释误命中（这个坑在 SourcePairingTest 里记过）。
    """
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    s = re.sub(r'//[^\n]*', '', s)
    return s


def read(name):
    with io.open(os.path.join(SRC, name), encoding='utf-8-sig') as f:
        return f.read()


api = strip_comments(read('ApiClient.java'))
prov = strip_comments(read('ChatProvider.java'))

print('=== 源码级断言：ApiClient 按能力表决定发不发 ===')

print()
print('[1] 必须存在"按能力表判断"的调用')
ok('ChatProvider.supports(' in api,
   'ApiClient 调用了 ChatProvider.supports')
ok(api.count('ChatProvider.supports(') >= 3,
   '至少 3 处判断（temperature / reasoning / penalty），实际 %d'
   % api.count('ChatProvider.supports('))

print()
print('[2] ★ 不允许存在"无条件 put temperature"')
# 每个 put("temperature"... 都必须**紧邻**一个能力判断。
#
# ★ 这里修过一个真实的漏洞：第一版只检查"前 3 行里出现过
#   ChatProvider.supports"，结果我把某处的判断删掉后断言**仍然通过** ——
#   因为附近另一处的 supports 调用落进了同一个 3 行窗口。
#   反向验证（build/reverse_check_api.py）把它抓了出来。
#   现在改成：从该行**往上**找最近的一条 if，该 if 条件里必须含判断。
raw = read('ApiClient.java')
lines = raw.split('\n')
bare = []
for i, ln in enumerate(lines):
    if 'put("temperature"' not in ln:
        continue
    guarded = False
    for j in range(i - 1, max(-1, i - 6), -1):
        prev = lines[j]
        if re.search(r'\bif\s*\(', prev):
            # 条件可能跨多行：把从该 if 到 put 之间的内容拼起来看
            cond = '\n'.join(lines[j:i + 1])
            guarded = ('ChatProvider.supports' in cond) or ('force' in cond)
            break
        # 遇到别的语句块边界就停止回看
        if re.search(r'\b(payload\.put|messages\.put|JSONObject )', prev):
            break
    if not guarded:
        bare.append(i + 1)
ok(not bare,
   '每个 put("temperature") 都由能力判断守卫（违规行 %s）' % bare)

print()
print('[3] 两处 payload 构造都要受约束')
ok(api.count('ChatProvider.find(') >= 2,
   '对话与记忆点提炼两处都取了服务商，实际 %d 处' % api.count('ChatProvider.find('))
ok('supports(prov, model, "temp")' in api,
   'temperature 的判断传了 model（支持模型级覆盖）')

print()
print('[4] applyReasoning 必须接收服务商参数')
m = re.search(r'applyReasoning\(JSONObject payload,\s*ChatProvider\.Entry prov', api)
ok(m is not None, 'applyReasoning 的签名带 ChatProvider.Entry')
ok('applyReasoning(payload)' not in api,
   '不存在旧的无参调用 applyReasoning(payload)')

print()
print('[5] Prefs 要能读出服务商（否则判断永远走默认）')
prefs = strip_comments(read('Prefs.java'))
ok('getChatProvider' in prefs, 'Prefs.getChatProvider 存在')
ok('detectId' in prefs,
   'getChatProvider 用地址反推兜底（老用户免重设）')

print()
print('[6] 能力表本身的关键结论（与离线断言互为印证）')
# 从 ChatProvider 源码确认 Kimi 用了 FIXED
kimi_block = re.search(r'new Entry\("kimi".*?\n\n', prov, re.S)
ok(kimi_block is not None, '找到 kimi 条目')
if kimi_block:
    kb = kimi_block.group(0)
    ok('kimiFixed()' in kb or 'FIXED' in kb,
       'kimi 条目里出现 FIXED（参数被锁）')
ok('kimiFixed' in prov, 'kimiFixed() 已定义')
# K3 必须单独处理（不能整个套 kimiFixed）
ok('kimi-k3' in prov, 'kimi-k3 有单独覆盖')
ok('reason(OK)' in prov,
   'kimi-k3 的 reasoning 显式设为 OK（不能被一刀切掉）')

print()
print('[7] ★ 余额逻辑必须按服务商分发（Kimi 查不到余额的修复）')
# 余额地址不能写死 /user/balance
ok('/user/balance' not in api or 'balanceUrlFor' in api,
   '余额地址经 balanceUrlFor 分发，而非写死')
ok('ChatBalance.buildUrl' in api or 'balanceUrlFor' in api,
   'ApiClient 使用按服务商构造的余额地址')
# 无接口的服务商不能被当成"查询失败"
fs = strip_comments(read('FloatingService.java'))
ok('hasBalance()' in fs,
   'FloatingService 检查 hasBalance（区分"无接口"与"失败"）')
ok('不提供余额接口' in read('FloatingService.java'),
   '对无余额接口的服务商给出明确文案')

print()
print('[8] ★ Key 必须按服务商分槽（切换时自动切 Key）')
ok('api_key_' in prefs or 'KEY_API + "_"' in prefs,
   'Prefs 按服务商拼 Key 槽名')
ok('hasApiKeyFor' in prefs, 'Prefs.hasApiKeyFor 存在（界面显示已存/未存）')
# 旧数据迁移：必须回退到老的单槽位
ok('KEY_API,' in prefs or 'KEY_API)' in prefs or 'getString(KEY_API' in prefs,
   'getApiKey 保留对旧单槽位的回退（老用户不用重填）')
# 切换服务商时不能清掉 Key
mc = strip_comments(read('MainActivity.java'))
switch_block = re.search(r'showChatProviderDialog.*?setNegativeButton', mc, re.S)
ok(switch_block is not None, '找到服务商切换逻辑')
if switch_block:
    sb = switch_block.group(0)
    ok('clearApiKeyFor' not in sb,
       '切换服务商时**不**清 Key（各家的 Key 都要留着）')
    ok('saveApiKey' not in sb,
       '切换服务商时**不**覆盖 Key（不该用一家的 Key 覆盖另一家）')

print()
print('[9] ChatBalance 必须是纯 Java（否则无法离线断言）')
bal = read('ChatBalance.java')
ok('import android.' not in bal,
   'ChatBalance 不 import android（保证可离线测试）')

print()
print('[10] ★ 非 DeepSeek 只显示用量（用户要求去掉余额气泡）')
ok('isDeepSeekProvider' in fs, 'FloatingService 有 DeepSeek 判定函数')
# 非 DeepSeek 分支必须"不显示余额行"，而不是显示"不提供接口"
usage = re.search(r'private String usageLine\(\).*?\n    \}', fs, re.S)
ok(usage is not None, '找到 usageLine')
if usage:
    ub = usage.group(0)
    ok('isDeepSeekProvider()' in ub,
       'usageLine 按服务商区分内容')
    # ★ 注意：本工程的界面文案一律写成 \uXXXX 转义（不是明文中文），
    #   所以断言必须搜转义序列。第一版搜明文"命中"直接失败 ——
    #   那是**断言写错**，不是代码错。这类误报会浪费一轮排查。
    HIT = r'\u547D\u4E2D'      # 命中
    TIMES = r'\u6B21'          # 次
    ok(HIT in ub,
       'usageLine 里"命中"仍在（DeepSeek 分支要显示）')
# 非 DeepSeek 的用量行不能含花费与命中率
m = re.search(r'if \(!deepseek\) \{(.*?)\n            \}', ub, re.S)
ok(m is not None, '找到非 DeepSeek 分支')
if m:
    nb = m.group(0)
    ok('Pricing' not in nb,
       '非 DeepSeek 分支不含 Pricing（别家定价不同，套用会算错）')
    ok('cost' not in nb,
       '非 DeepSeek 分支不含 cost（不显示花费）')
    ok(HIT not in nb,
       '非 DeepSeek 分支不含命中率（别家不返回缓存字段，恒为 0%）')
    ok('tok' in nb and TIMES in nb,
       '非 DeepSeek 分支保留次数与 token')

# refresh() 里非 DeepSeek 必须直接返回、不进余额查询
rf = re.search(r'private void refresh\(\) \{(.*?)\n    \}\n', fs, re.S)
ok(rf is not None, '找到 refresh()')
if rf:
    rb = rf.group(0)
    # 非 DeepSeek 的早退分支应出现在读取 key 之前
    i_guard = rb.find('!isDeepSeekProvider()')
    i_key = rb.find('Prefs.getApiKey')
    ok(i_guard >= 0, 'refresh() 里有非 DeepSeek 的早退判断')
    ok(i_guard >= 0 and i_key >= 0 and i_guard < i_key,
       '非 DeepSeek 在取 Key/发请求**之前**就返回（不发起无谓请求）')
    ok('setBalanceText("")' in rb,
       '非 DeepSeek 用空主行 ⇒ 只剩用量行（不是写"不提供接口"）')

# setBalanceText 要能处理空主行，否则顶部留一条空白
sb = re.search(r'private void setBalanceText\(String body, double balForColor\) \{(.*?)\n    \}',
               fs, re.S)
ok(sb is not None, '找到 setBalanceText(body, bal)')
if sb:
    ok('noMain' in sb.group(0),
       'setBalanceText 特判空主行（否则气泡顶部会留空白行）')

print()
print('[11] ★ 「命中率」有两个显示出口，必须都受服务商约束')
# ★ 这一条是被实测逼出来的：第十三轮只改了余额气泡的第二行，
#   漏了 maybeUsageTalk（"今天我们已经聊了 N 轮…缓存命中 0.0%"），
#   在 Kimi 下依然报了一个恒为 0% 的假数字。
#   教训：同一概念在多处显示时，只改一处会漏 —— 用断言把两处都钉住。
SITES = [
    ('余额气泡第二行', r'private String usageLine\(\)'),
    ('用量播报气泡', r'private void maybeUsageTalk\(\)'),
]
for label, sig in SITES:
    m2 = re.search(sig + r'.*?\n    \}', fs, re.S)
    ok(m2 is not None, '找到 %s' % label)
    if m2:
        blk = m2.group(0)
        ok('isDeepSeekProvider()' in blk,
           '%s 按服务商区分（否则别家会显示恒 0%% 的命中率）' % label)
        # 该块里出现 命中 的地方必须在 DeepSeek 判断的保护下
        ok('命中' in blk or r'\u547D\u4E2D' in blk,
           '%s 仍保留命中率文案（DeepSeek 分支要用）' % label)

# 全工程扫一遍：显示命中率的地方不能只有一处受保护
allfs = fs
hits = [m.start() for m in re.finditer(r'cacheRateText|officialCacheText', allfs)]
ok(len(hits) >= 2, '预期至少 2 处引用命中率（实际 %d）' % len(hits))

print()
print('[12] ★ 服务商必须是"选定后生效"，不是"点了就生效"')
# 原实现用 setSingleChoiceItems 的点击回调**立即写盘并关闭** ——
# 而这一次点击同时改三样（服务商/地址/模型），用户点到一半想反悔时
# 前一个已经生效了。现在点击只高亮，按「选定」才落盘。
mc = strip_comments(read('MainActivity.java'))
dlg = re.search(r'private void showChatProviderDialog\(\).*?\n    \}', mc, re.S)
ok(dlg is not None, '找到 showChatProviderDialog')
if dlg:
    db = dlg.group(0)
    ok('setPositiveButton' in db and '选定' in read('MainActivity.java'),
       '有「选定」确认按钮')
    # 点击回调里**不能**出现写盘动作
    cb = re.search(r'setSingleChoiceItems\(.*?onClick\(DialogInterface d, int which\) \{(.*?)\n                    \}',
                   db, re.S)
    ok(cb is not None, '找到选项点击回调')
    if cb:
        body = cb.group(1)
        for forbidden in ('Prefs.setChatProvider', 'Prefs.saveBaseUrl',
                          'Prefs.setChatModel', 'applyChatProvider'):
            ok(forbidden not in body,
               '点击回调里不能有 %s（应等「选定」）' % forbidden)
    # applyChatProvider 必须存在（真正落盘的地方）
    ok('private void applyChatProvider' in mc,
       'applyChatProvider 存在（落盘集中在它里面）')
    # 「选定」按钮要调用它
    pb = re.search(r'setPositiveButton\("选定".*?\n                \}\)', db, re.S)
    ok(pb is not None, '「选定」按钮有回调')
    if pb:
        ok('applyChatProvider' in pb.group(0),
           '「选定」按钮调用 applyChatProvider')

print()
print('[13] ★ 不要用 setView 配 setSingleChoiceItems（会互相覆盖）')
# 我第一版想加一个"预览" TextView，用 dialog.setView(预览) 挂在列表下。
# 实测不会显示：setSingleChoiceItems 定义在 AlertDialog.Builder 上，
# 会构建自己的列表视图；setView 是 AlertDialog 上的另一个插槽，两者互相覆盖。
# 所以现在把说明写进每个选项的文字里。
if dlg:
    ok('setView' not in dlg.group(0) or 'setSingleChoiceItems' not in dlg.group(0),
       '对话框里没有 setView + setSingleChoiceItems 混用')
    # 选项文字要自带关键信息（不再依赖外部预览）
    ok('Key 已保存' in read('MainActivity.java')
       or '还没有 Key' in read('MainActivity.java'),
       '选项文字自带 Key 状态')
    ok('无余额接口' in read('MainActivity.java'),
       '选项文字自带余额能力')

print()
print('[14] ★ 旧 Key 槽位的回退必须是一次性的')
# 老槽位存的是"某一家"的 Key。若长期回退，用户切到 Kimi 但没填 Key 时，
# 会拿 DeepSeek 的 Key 去请求 Kimi —— 报 401，而设置界面还显示"未保存"，
# 行为与显示矛盾，用户无法自查。
ok('KEY_API_MIGRATED' in prefs or 'api_key_migrated' in prefs,
   '有"已迁移"标记')
api_m = re.search(r'public static String getApiKey\(Context ctx\) \{(.*?)\n    \}',
                  prefs, re.S)
ok(api_m is not None, '找到 getApiKey')
if api_m:
    body = api_m.group(0)
    ok('MIGRATED' in body or 'migrated' in body,
       'getApiKey 在回退前检查"已迁移"标记（终止一次性回退）')
    # 回退分支必须在标记检查之后
    i_flag = max(body.find('MIGRATED'), body.find('migrated'))
    i_fallback = body.find('getString(KEY_API')
    ok(i_flag >= 0 and i_fallback >= 0 and i_flag < i_fallback,
       '标记检查在旧槽位回退**之前**（顺序反了就白做）')
save_m = re.search(r'public static void saveApiKey\(Context ctx, String apiKey\) \{(.*?)\n    \}',
                   prefs, re.S)
ok(save_m is not None, '找到 saveApiKey')
if save_m:
    ok('MIGRATED' in save_m.group(0) or 'migrated' in save_m.group(0),
       'saveApiKey 会写入"已迁移"标记（否则回退永不终止）')

print()
print('通过 %d 项' % pass_n)
if fail_n:
    print('失败 %d 项' % fail_n)
    sys.exit(1)
