# -*- coding: utf-8 -*-
"""反向验证：**本地模型对话接线**的断言必须能抓到回潮（轮次64 第三轮）。

每一项都**故意破坏一次**，确认 `verify_local_llm_chat.py` 退出码变 1，再恢复确认回到 0。
★ 恢复放在 `finally` 里（中途异常也不能把源码留在被破坏的状态）。

## ★★ 跑这个套件之前先确认没有别的套件在跑

反向套件会**临时改源文件**。轮次61 的教训：并发跑（或中途强杀）时 `finally` 跑不到，
**破坏态会留在盘上**（那次丢过 `Appearance.resolve()` 的整段回退逻辑，
表现是下一轮 fpstest 莫名其妙报红）。⇒ 一次只跑一个套件。

## ★★ 本套件按**字节**改文件（不是文本模式），这是被现实逼出来的

前两轮的兄弟套件用 `io.open(..., 'w', newline='\\n')` 写回 —— 那会把 **CRLF 文件
悄悄改成 LF**（`run_all_tests.bat` 就是 CRLF，`check_pack_contents.py` 明写要求
`cr == lf`）。文本模式跑一趟 = 顺手把工程改坏一处，而且**没有判据会红**。
⇒ 这里一律 `rb`/`wb` + UTF-8 编码锚点：**改与还原都是字节精确的**。

## 本条最值得盯的五条

  * **A/B（分流）** —— `isLocal` 恒 false，或本地分支里长出 `Authorization`/`ApiClient`：
    现象是"选了本地模型却还在走网"或反过来，而界面上一切正常。
  * **D/E/F（绝不联网）** —— 闸门失效：用户以为"纯本地"，其实 `WebClient.augment`
    正在发 HTTP（这是本轮最不能出的错）。
  * **H/J（加载）** —— 不再复用已载入的模型（每句话重载一次，每句干等十几秒），
    或者换模型不卸（换了没效果，用的一直是内存里那个旧的）。
  * **Q（不静默）** —— 没导入模型时返回一个空 Reply：用户点了没反应，
    既没有引导也没有报错 —— 最难查的那种坏法。
  * **O/N（流式）** —— 回调里回头调引擎（自锁），或干脆不节流（每秒几十次 measure/layout）。

## 锚点纪律

所有 `old` 都取**稳定写法**（源码里的整行/整个表达式），
**绝不**用 md5 / 版本号 / 文件大小这类每轮会变的读数做锚点。
同一写法在多处出现时显式给 `count=99` **全部替换**，避免"只改到第一处、断言照样绿"的假红。
"""
import io
import os
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
VERIFY = os.path.join(HERE, 'verify_local_llm_chat.py')

LOCAL_PROMPT = os.path.join(SRC, 'LocalPrompt.java')
LOCAL_CHAT = os.path.join(SRC, 'LocalChat.java')
CHAT_ROUTER = os.path.join(SRC, 'ChatRouter.java')
CHAT_PROVIDER = os.path.join(SRC, 'ChatProvider.java')
API_CLIENT = os.path.join(SRC, 'ApiClient.java')
FLOATING = os.path.join(SRC, 'FloatingService.java')
CHAT_ACT = os.path.join(SRC, 'ChatActivity.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
FPSTEST_BAT = os.path.join(HERE, 'fpstest', 'run.bat')
FPSTEST_JAVA = os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')
RUNBAT = os.path.join(HERE, 'run_all_tests.bat')
FEATURES = os.path.join(HERE, 'verify_features.py')


def readb(p):
    return io.open(p, 'rb').read()


def writeb(p, b):
    io.open(p, 'wb').write(b)


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode


def break_and_check(label, path, old, new, count=1):
    """改源文件 → 断言必须红 → 写回 → 断言必须绿。★ 全程按**字节**。"""
    ob, nb = old.encode('utf-8'), new.encode('utf-8')
    orig = readb(path)
    if ob not in orig:
        print('  [SKIP] %-54s （找不到锚点，按未验证计）' % label)
        return None
    try:
        writeb(path, orig.replace(ob, nb, count))
        bad = run_verify()
        writeb(path, orig)
        good = run_verify()
    finally:
        writeb(path, orig)
    ok = (bad == 1 and good == 0)
    print('  [%s] %-54s 破坏后=%d 恢复后=%d' % ('OK' if ok else 'FAIL', label, bad, good))
    return ok


results = []
print('== 反向验证：本地模型对话接线（分流 / 不联网 / 常驻加载 / 引导 / 流式）==')
print()

# ---------------------------------------------------------------- 一、分流
# A. ★★★ 分流判据失效（恒 false ⇒ 本地后端被当成在线后端，拿一个不存在的 Key 去发 HTTP）
results.append(break_and_check(
    'A. ★★★分流判据失效（isLocal 恒 false）',
    CHAT_ROUTER,
    'return ChatProvider.LOCAL_ID.equals(Prefs.getChatProvider(ctx));',
    'return false;'))

# B. ★★★ 本地分支里长出 HTTP（"顺手"在本地后端里回退到在线接口 + 带上 Authorization）
results.append(break_and_check(
    'B. ★★★本地分支里长出 Authorization/ApiClient',
    LOCAL_CHAT,
    'if (!LocalLlm.isAvailable()) { r.error = guideEngineMissing(); return r; }',
    'if (!LocalLlm.isAvailable()) { r.error = "Authorization: Bearer " '
    '+ ApiClient.getBaseUrl(); return r; }'))

# C. ★★ 本地分支接上了 API Key（说明分流接错了：本地根本不该有 Key 这个概念）
results.append(break_and_check(
    'C. ★★本地分支接上了 apiKey 参数',
    LOCAL_CHAT,
    'public static Reply answer(Context ctx, String persona, String facts, String antiRepeat,',
    'public static Reply answer(Context ctx, String apiKey, String persona, String facts, '
    'String antiRepeat,'))

# D. ★★★ 把本地推理硬塞进 ApiClient（施工单第一条红线）
#    ★★ 注意：**不能**用注释来破坏 —— 断言是在 `strip_comments()` 之后的视图上做的，
#       插一句注释等于没插（第一版就是这样的假绿，本套件自己抓出来的）。
#       这里插的是一个**真的字段声明**（编译得过的代码）。
results.append(break_and_check(
    'D. ★★★把本地推理塞进 ApiClient',
    API_CLIENT,
    '    /** 兼容旧调用：不带记忆点 */',
    '    /** 兼容旧调用：不带记忆点 */\n'
    '    private static LocalChat.Reply localFallbackShim;'))

# E. ★★ 分流点没了：能力表里那条 "local" 被改名
results.append(break_and_check(
    'E. ★★能力表里 local 条目 id 被改',
    CHAT_PROVIDER,
    'public static final String LOCAL_ID = "local";',
    'public static final String LOCAL_ID = "localX";'))

# F. ★★ 本地对话计入用量/费用（用户会以为花了钱，而本机推理一分钱不花）
results.append(break_and_check(
    'F. ★★本地对话计入用量（usageParsed=true）',
    CHAT_ROUTER,
    'r.usageParsed = false;',
    'r.usageParsed = true;'))

# ---------------------------------------------------------------- 二、绝不联网
# G. ★★★ 闸门恒 true：本地后端偷偷联网检索
results.append(break_and_check(
    'G. ★★★不联网闸门失效（webAllowed 恒 true）',
    CHAT_ROUTER,
    'return !isLocal(ctx);',
    'return true;'))

# H. ★★★ 聊天窗口绕过闸门直接检索
results.append(break_and_check(
    'H. ★★★聊天窗口的检索绕过闸门',
    CHAT_ACT,
    'final boolean webOn = ChatRouter.webAllowed(ChatActivity.this)\n'
    '                        && Prefs.webSearch(ChatActivity.this);',
    'final boolean webOn = Prefs.webSearch(ChatActivity.this);'))

# I. ★★★ 悬浮窗「问一句」绕过闸门
results.append(break_and_check(
    'I. ★★★悬浮窗问一句的检索绕过闸门',
    FLOATING,
    'final boolean webOn = ChatRouter.webAllowed(FloatingService.this)\n'
    '                        && Prefs.webSearch(FloatingService.this);',
    'final boolean webOn = Prefs.webSearch(FloatingService.this);'))

# J. ★★ 本地后端仍然去跑「记忆点提炼」（走在线链：白发一个必然失败的请求）
results.append(break_and_check(
    'J. ★★本地后端仍去提炼记忆点',
    FLOATING,
    'if (!ChatRouter.isLocal(FloatingService.this)) {',
    'if (true) {'))

# K. ★★ 聊天窗口同样不再跳过记忆点提炼
results.append(break_and_check(
    'K. ★★聊天窗口不再跳过记忆点提炼',
    CHAT_ACT,
    'if (!ChatRouter.isLocal(ChatActivity.this)) {',
    'if (true) {'))

# ---------------------------------------------------------------- 三、加载策略
# L. ★★ 不再复用已载入的模型（每句话都重载：每句干等十几秒）
results.append(break_and_check(
    'L. ★★不再复用已载入的模型（每句重载）',
    LOCAL_CHAT,
    'if (LocalLlm.isLoaded() && path.equals(loadedPath)) {',
    'if (false) {'))

# M. ★★★ 不再比较"载的是哪个文件"（换了模型还用内存里那个旧的 ⇒ "换了没效果"）
results.append(break_and_check(
    'M. ★★★换模型后不再判断载的是哪个文件',
    LOCAL_CHAT,
    'return !f.getAbsolutePath().equals(loadedPath);',
    'return !LocalLlm.isLoaded();'))

# N. ★★ 换了模型不卸旧的
results.append(break_and_check(
    'N. ★★换了模型不卸内存里的旧模型',
    LOCAL_CHAT,
    '                try { LocalLlm.unload(); } catch (Throwable ignore) {}',
    '                // unload 被反向验证摘掉了'))

# O. ★★ 载入失败也把 loadedPath 记上（下次拿一个没载的模型去生成）
#    ★ 轮次74：锚点跟着 loadPlanned 改写（失败原因现在是 `lr.why`）。
results.append(break_and_check(
    'O. ★★载入失败仍记 loadedPath',
    LOCAL_CHAT,
    '                loadedPath = null;\n                final String why = lr.why;',
    '                final String why = lr.why;'))

# P. ★★ 换/删/重导模型时不再"忘掉载着谁"
results.append(break_and_check(
    'P. ★★换/删/重导模型时不再 forget',
    MAIN,
    'LocalChat.forget();',
    'LocalChat.forgetX();',
    count=99))

# Q. ★★ 同名重导不再卸（路径没变而盘上已是新内容 ⇒ 一直用内存里的旧模型）
results.append(break_and_check(
    'Q. ★★同名重导不再卸旧模型',
    MAIN,
    'try { LocalLlm.unload(); } catch (Throwable ignore) {}\n'
    '                            LocalChat.forget();\n'
    '                        }}, "dspllm-import").start();',
    '                        }}, "dspllm-import").start();'))

# R. ★★ 从本地切回在线时不放内存（白占几百 MB）
results.append(break_and_check(
    'R. ★★从本地切回在线时不释放内存',
    MAIN,
    'if (wasLocal && !toLocal) {',
    'if (false) {'))

# S. ★ 首次载入不再给等待反馈（几百 MB 的 mmap，用户看到的是"卡住了"）
results.append(break_and_check(
    'S. ★首次载入不再给等待反馈',
    CHAT_ACT,
    'final String loadHint = ChatRouter.loadHintIfNeeded(this);',
    'final String loadHint = null;'))

# ---------------------------------------------------------------- 四、不许静默失败
# T. ★★★ 没导入模型时返回空的 Reply（既不引导也不报错 = 静默失败）
results.append(break_and_check(
    'T. ★★★没导入模型时静默失败',
    LOCAL_CHAT,
    'if (f == null) return Reply.fail(guideNoModel());',
    'if (f == null) return new Reply();'))

# U. ★★ 引导里不再说清"去哪一步点哪个按钮"
results.append(break_and_check(
    'U. ★★引导里不再说清去哪一步导入',
    LOCAL_CHAT,
    '设置 → 陪伴 → 对话与人格',
    '某个设置页'))

# V. ★★★ 不再如实说明"小模型慢、也更笨"（用户会拿它跟云端比，然后以为坏了）
results.append(break_and_check(
    'V. ★★★不再说明小模型的代价',
    LOCAL_CHAT,
    '慢一些、也更笨',
    '非常好用'))

# W. ★★ 设置页不再提示"已选本地模型但还没导入"
results.append(break_and_check(
    'W. ★★设置页不再提示还没导入模型',
    MAIN,
    '已选本地模型，但还没导入',
    '已选本地模型'))

# X. ★ 本机推理不再声明"不联网、不需要 Key、不产生费用"
results.append(break_and_check(
    'X. ★能力表不再声明本机推理的代价与好处',
    CHAT_PROVIDER,
    '本机推理：不联网、不需要 API Key、不产生费用',
    '本机推理'))

# ---------------------------------------------------------------- 五、流式
# Y. ★★ 气泡流式改成往"连发/朗读"里灌半句（切句与 TTS 全乱）
results.append(break_and_check(
    'Y. ★★气泡流式往连发/朗读里灌半句',
    FLOATING,
    'showBubbleInternal(prefix + soFar, 0, null);',
    'showPetSaySplit(prefix + soFar, soFar, null, 8000);'))

# Z. ★★ 本地后端不再挂 sink（流式消失，退化成"等整段"）
results.append(break_and_check(
    'Z. ★★本地后端不再挂流式 sink',
    FLOATING,
    '? bubbleSink("\\uD83D\\uDCAC ") : null;',
    '? null : null;'))

# AA. ★★ 不再节流（一个 token 刷一次 = 每秒几十次 measure/layout）
results.append(break_and_check(
    'AA. ★★流式不再节流（每 token 刷一次）',
    LOCAL_CHAT,
    'if (!LocalPrompt.shouldFlush(lastMs, now, LocalPrompt.FLUSH_MS)) return true;',
    'if (false) return true;'))

# AB. ★★★ 回调里回头调引擎（同一把锁 = 自锁）
results.append(break_and_check(
    'AB. ★★★流式回调里回头调引擎（自锁）',
    LOCAL_CHAT,
    'acc.append(piece);',
    'acc.append(piece); LocalLlm.generate(piece, 1, 0.8f, null);'))

# AC. ★★ 聊天窗口不再逐段画（"正在思考"一直停在那里）
results.append(break_and_check(
    'AC. ★★聊天窗口不再逐段出话',
    CHAT_ACT,
    'typing.setText(soFar);',
    'typing.setText("…");'))

# ---------------------------------------------------------------- 六、纯类与 fpstest
# AD. ★★ fpstest 不再编 LocalPrompt（纯类没被真跑过）
results.append(break_and_check(
    'AD. ★★fpstest 不再编 LocalPrompt',
    FPSTEST_BAT,
    r'..\..\src\com\deepseek\pet\LocalPrompt.java',
    ''))

# AE. ★★ [34] 那一节被摘掉（纯类再也没被真跑）
results.append(break_and_check(
    'AE. ★★fpstest 的 [34] LocalPrompt 节被摘掉',
    FPSTEST_JAVA,
    'System.out.println("[34] LocalPrompt 本地小模型的提示词与清理（轮次64 第三轮：对话接线）");',
    'System.out.println("[35] LocalPrompt 本地小模型");'))

# AF. ★★ 把 [34] 里的断言改成恒真（"真跑"变成"跑了个寂寞"）
results.append(break_and_check(
    'AF. ★★[34] 的温度断言改成恒真',
    FPSTEST_JAVA,
    'LocalPrompt.tempFor(1.3) == 1.0f',
    'true'))

# AG. ★★ 纯类里引回引擎（光秃秃的 javac 编不过 ⇒ "零 Android 依赖"这条判据失效）
results.append(break_and_check(
    'AG. ★★纯类里引回 LocalLlm',
    LOCAL_PROMPT,
    'public static final int DEF_CTX = 2048;',
    'public static final int DEF_CTX = LocalLlm.DEFAULT_CTX;'))

# ---------------------------------------------------------------- 七、登记
# AH. ★★ 本套件从全量套件里摘掉（摘了就等于从来不跑）
results.append(break_and_check(
    'AH. ★★run_all_tests.bat 摘掉本套件',
    RUNBAT,
    'verify_local_llm_chat.py',
    'verify_local_llm_chat_typo.py'))

# AI. ★★ 全量套件编号总数没跟着改（说明"加了两个套件"这件事没落地）
#    ★★ 锚点**按 bat 里的当前总数现场算**，不钉死某一轮的数字 ——
#      轮次66 被这个坑咬了一次（还写着 /71，而总数已是 /73 ⇒ 找不到锚点 ⇒ 红）。
_BAT_TXT = open(RUNBAT, encoding='utf-8', errors='replace').read()
_M = re.search(r'echo =+ (\d+)/(\d+) ', _BAT_TXT)
TOTAL_NOW = _M.group(2) if _M else '73'
results.append(break_and_check(
    'AI. ★★全量套件编号总数没改（/%s → /67）' % TOTAL_NOW,
    RUNBAT,
    '/%s ' % TOTAL_NOW,
    '/67 ',
    count=99))

# AJ. ★★ 交付验证的探针没跟上（真机上"看不到功能"就没人发现）
results.append(break_and_check(
    'AJ. ★★verify_features 没加本轮的探针',
    FEATURES,
    "'本地模型：分流点类'",
    "'本地模型：分流点类X'"))

# AK. ★★ 本轮自己的编号段被改坏（判据原来查的是**上一轮**的 68/73 与 69/73，
#     恒真 ⇒ 把 70/73 改成 69/73 时它照样绿 —— 轮次65 收尾复核抓出的假绿，已修）。
# ★ 轮次79：套件从 73 加到 76，这条判据改成"末两条 = (t-1)/t 与 t/t"（t 由 run_all_tests.bat 现算），
#   锚点也跟着换成**末条**：把它改小 1 就等于"末条序号 != 总数"（写成死数字的话这里又会过期）。
results.append(break_and_check(
    'AK. ★★本轮编号段被改坏（末条 %s/%s → 序号-1）' % (TOTAL_NOW, TOTAL_NOW),
    RUNBAT,
    '%s/%s' % (TOTAL_NOW, TOTAL_NOW),
    '%d/%s' % (max(1, int(TOTAL_NOW) - 1), TOTAL_NOW)))

# AL. ★★★ 轮次71：让 **UI 层自己去载模型**（把一处 LocalLlm.load 塞进悬浮服务）
#    —— "载入是同步阻塞的，UI 不许自己载"这条性质就靠它守着。
#    ★ 轮次71 那条判据从"LocalChat 里只有 1 处"改成"只有 2 处、且那两处就是这两处"，
#      所以这条破坏项必须**打在产品代码上**（塞进 FS）才真正区分得开：
#      只改判据的数字（1→2）会让它变成"数字对了就行"，而这次改成逐字钉两个调用点之后
#      FS/CA 那半边仍然能抓到这一条。
results.append(break_and_check(
    'AL. ★★★UI 层自己去载模型（悬浮服务里塞一处 LocalLlm.load）',
    FLOATING,
    'private void hintLocalLoadingIfNeeded() {',
    'private void hintLocalLoadingIfNeeded() {\n        LocalLlm.load("x", 1, 1);'))

# ================================================================ 轮次75：入口下线
# ★★★ 用户原话「先去掉本地模型部署这个接口 保留项目内容后续再加上」——
#   这一组守的是"下线真的生效、而且是可逆的（代码没删）"。
results.append(break_and_check(
    'AM. ★★★把开关打开（界面入口又冒出来）',
    os.path.join(SRC, 'LocalModelFeature.java'),
    'public static final boolean ENABLED = false;',
    'public static final boolean ENABLED = true;'))

results.append(break_and_check(
    'AN. ★★★服务商列表不再受开关控制（本地模型又出现在选择器里）',
    os.path.join(SRC, 'ChatProvider.java'),
    'if (LocalModelFeature.ENABLED) {',
    'if (true) {'))

results.append(break_and_check(
    'AO. ★★★历史选择 local 的用户不再回退到在线（会卡在一个用不了的后端）',
    os.path.join(SRC, 'Prefs.java'),
    'return LocalModelFeature.resolveProvider(v.trim());',
    'return v.trim();'))

print()
print('=' * 62)
done = [r for r in results if r is not None]
skipped = len(results) - len(done)
caught = sum(1 for r in done if r)
print('%d/%d 个破坏被断言抓到（跳过按未验证计）' % (caught, len(done)))
if skipped:
    print('  跳过 %d 项（锚点未命中）' % skipped)
if caught != len(done) or skipped:
    sys.exit(1)
print('ALL REVERSE CHECKS PASSED')
