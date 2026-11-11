# -*- coding: utf-8 -*-
"""本地 GGUF 模型部署 · **对话接线**（轮次64 第三轮）。

用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
第一轮做完**引擎**（llama.cpp 交叉编译 + JNI + `LocalLlm`），
第二轮做完**导入**（设置页挑 `.gguf` → 流式拷进 `filesDir/models/`），
这一轮把**对话接上**：桌宠真的能用手机上那个小模型说话，**而且一个 HTTP 都不发**。

## ★★ 为什么再开一个套件（第三份），而不是往前两份里加节

`verify_local_llm.py` 钉的是"**包里的二进制对不对**"（ELF 架构 / 导出符号 / 依赖链 / 页对齐）；
`verify_local_llm_import.py` 钉的是"**文件系统与导入交互对不对**"；
这一轮钉的是"**这句话到底交给谁去想**"——分流、加载常驻、不静默、流式。
三个平面完全不相交，混在一起的坏处（前一轮已经实测过）有两个：
① 反向套件要按"一条判据守一条性质"配破坏项，锚点会互相干扰
（实测：改 `run_all_tests.bat` 的编号总数会同时打到两轮的判据）；
② 一个套件红了，看名字说不清是**引擎**坏了、**导入**坏了、还是**接线**坏了。

## 分节

  一、★ 纯类 `LocalPrompt`：在 / **零 Android 依赖** / 被 fpstest 真编真跑（`[34]`）
      ★ 含一条"抄来的常量必须与引擎一致"的交叉判据
  二、★★ 分流：唯一判据是 `ChatRouter.isLocal`，**本地分支一个 HTTP 符号都没有**
  三、★★ 本地后端**绝不联网**（`webAllowed` 是唯一闸门；检索与记忆点提炼都不发）
  四、★★ 加载：第一次用时载一次、之后常驻；**换模型/删模型/同名重导才 unload**
  五、★★ 没导入模型时**不许静默**（引导文案 + 判据 + 如实说明"小模型慢、也更笨"）
  六、★★ 流式：sink 里**只做 append**；节流只有一处；不与"逐句气泡/朗读/配图"打架
  七、★★ 接线：6 个对话发起点全部走 ChatRouter，**没有一个漏回 ApiClient**
  八、★ ChatProvider 新增 `local` 条目 + 设置页如实显示与引导
  九、★★ 真的进了包 + 登记进全量套件（69 → 71）+ `verify_features` 探针

反向破坏见 reverse_check_local_llm_chat.py。

## ★ 与 fpstest 的分工（别搞混）

**行为**由 fpstest `[34]` 真编真跑（模板渲染 / 预算 / 裁历史 / 清回复 / 夹温度 / 节流）；
这个套件钉的是"**接线**"：分流是不是只在一处、那句话有没有被用上、有没有偷偷联网。
两边都要有 —— 只有前者，删掉一处调用它不会红；只有后者，"逻辑写错了"看不出来。
"""
import io
import os
import re
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
sys.path.insert(0, HERE)
from artifacts import dev_apk, REL_ROOT  # noqa: E402

DEV_APK = dev_apk()

LOCAL_PROMPT = os.path.join(SRC, 'LocalPrompt.java')
LOCAL_CHAT = os.path.join(SRC, 'LocalChat.java')
CHAT_ROUTER = os.path.join(SRC, 'ChatRouter.java')
CHAT_PROVIDER = os.path.join(SRC, 'ChatProvider.java')
LOCAL_LLM = os.path.join(SRC, 'LocalLlm.java')
API_CLIENT = os.path.join(SRC, 'ApiClient.java')
FLOATING = os.path.join(SRC, 'FloatingService.java')
CHAT_ACT = os.path.join(SRC, 'ChatActivity.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
PREFS = os.path.join(SRC, 'Prefs.java')
FPSTEST_BAT = os.path.join(HERE, 'fpstest', 'run.bat')
FPSTEST_JAVA = os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')
RUNBAT = os.path.join(HERE, 'run_all_tests.bat')
FEATURES = os.path.join(HERE, 'verify_features.py')

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def strip_comments(src):
    """去掉注释，但**保留字符串字面量**（用户可见文案必须能被搜到，注释里提到不算数）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c in '"\'':
            q, j = c, i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == q:
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def func_body(text, sig):
    i = text.find(sig)
    if i < 0:
        return ''
    j = text.find('{', i)
    if j < 0:
        return ''
    depth = 0
    for k in range(j, len(text)):
        if text[k] == '{':
            depth += 1
        elif text[k] == '}':
            depth -= 1
            if depth == 0:
                return text[j:k + 1]
    return text[j:]


LP = strip_comments(read(LOCAL_PROMPT)) if os.path.isfile(LOCAL_PROMPT) else ''
LC = strip_comments(read(LOCAL_CHAT)) if os.path.isfile(LOCAL_CHAT) else ''
CR = strip_comments(read(CHAT_ROUTER)) if os.path.isfile(CHAT_ROUTER) else ''
CP = strip_comments(read(CHAT_PROVIDER)) if os.path.isfile(CHAT_PROVIDER) else ''
LLM = strip_comments(read(LOCAL_LLM)) if os.path.isfile(LOCAL_LLM) else ''
API = strip_comments(read(API_CLIENT)) if os.path.isfile(API_CLIENT) else ''
FS = strip_comments(read(FLOATING)) if os.path.isfile(FLOATING) else ''
CA = strip_comments(read(CHAT_ACT)) if os.path.isfile(CHAT_ACT) else ''
MA = strip_comments(read(MAIN)) if os.path.isfile(MAIN) else ''


# ================================================================ 一、纯类

print('=== 一、★ 纯类 LocalPrompt：在 / 零 Android 依赖 / 被 fpstest 真编真跑 ===')
check('src/com/deepseek/pet/LocalPrompt.java 存在', os.path.isfile(LOCAL_PROMPT))
bad_imp = [l for l in LP.splitlines() if l.strip().startswith('import ') and 'android' in l]
check('★★ LocalPrompt **零 Android 依赖**（只用 java.util）', not bad_imp, bad_imp or '引了 android.*')
# ★★ 这一条是 fpstest **真编**抓出来的：纯类里引 LocalLlm 会让光秃秃的 javac 编不过
#    （原因是 LocalLlm 引了 android.util.Log）。所以纯类必须自带常量，不能借引擎的。
check('★★ LocalPrompt 不引用 LocalLlm（纯类借 Android 类 = fpstest 当场编不过）',
      'LocalLlm' not in LP)
check('★ 上下文/输出上限的默认值写在纯类自己身上',
      'public static final int DEF_CTX = 2048;' in LP
      and 'public static final int DEF_OUT = 256;' in LP)
check('★★ 抄来的常量必须与引擎**一致**（两处数值分叉 = 预算算错，静默出问题）',
      'public static final int DEFAULT_CTX = 2048;' in LLM
      and 'public static final int DEFAULT_MAX_TOKENS = 256;' in LLM)
check('★ 流式节流间隔是一个具名常量（不是散落的魔法数）',
      'public static final long FLUSH_MS = 250;' in LP)
check('★ 四种对话模板都在（ChatML / Llama-3 / Gemma / 纯文本兜底）',
      all(('public static final String T_%s = ' % k) in LP
          for k in ('CHATML', 'LLAMA3', 'GEMMA', 'PLAIN')))

if os.path.isfile(FPSTEST_BAT):
    bat = read(FPSTEST_BAT)
    check('★★ fpstest 的 run.bat 真的编了这一份 LocalPrompt（编的与打进 APK 的同源）',
          '..\\..\\src\\com\\deepseek\\pet\\LocalPrompt.java' in bat)
else:
    check('fpstest/run.bat 存在', False)

FT = read(FPSTEST_JAVA) if os.path.isfile(FPSTEST_JAVA) else ''
check('★★ FpsTest 有 [34] 这一节（纯类被**真跑**，不是只被 grep）',
      '[34] LocalPrompt' in FT)
check('★★ fpstest 真造消息序列真渲染（不是只断言常量）',
      'LocalPrompt.render(LocalPrompt.T_CHATML, ms)' in FT
      and 'LocalPrompt.messages(' in FT
      and 'LocalPrompt.trimHistory(h, 10)' in FT)
# ★ 这一条防的是"把 [34] 里的断言改成恒真"——那样"真跑"就变成了"跑了个寂寞"。
check('★★ [34] 的断言真在验行为（模板 / 清理 / 夹温度 / 节流各至少一条）',
      'LocalPrompt.cleanReply(' in FT and 'LocalPrompt.tempFor(1.3) == 1.0f' in FT
      and 'LocalPrompt.shouldFlush(1000, 1250, 250)' in FT
      and 'LocalPrompt.estimateTokens("你好世界") == 4' in FT)


# ================================================================ 二、分流

print()
print('=== 二、★★ 分流：唯一判据在 ChatRouter；本地分支一个 HTTP 符号都没有 ===')
check('src/com/deepseek/pet/ChatRouter.java 存在', os.path.isfile(CHAT_ROUTER))
check('★★ 后端 id 常量 `local` 定义在能力表里（不是散在各处的字符串字面量）',
      'public static final String LOCAL_ID = "local";' in CP)
check('★★ 分流判据就是一句：当前服务商 == LOCAL_ID',
      'ChatProvider.LOCAL_ID.equals(Prefs.getChatProvider(ctx))' in CR)
check('★★ isLocal 本体里没有 ApiClient（分流判据不许沾 HTTP）',
      'ApiClient' not in func_body(CR, 'public static boolean isLocal('))
check('★★ 「有没有能说话的后端」也是同一个判据：本地不要 Key，在线要',
      'if (isLocal(ctx)) return true;' in func_body(CR, 'public static boolean ready(')
      and 'return Prefs.hasApiKey(ctx);' in func_body(CR, 'public static boolean ready('))

# ================================================================ 轮次75：入口下线
print()
print('=== 轮次75、★★★ 「本地模型部署」入口下线（代码/数据保留，一个开关恢复）===')
FEATURE = os.path.join(SRC, 'LocalModelFeature.java')
_check_feature = io.open(FEATURE, encoding='utf-8').read() if os.path.isfile(FEATURE) else ''
_check_prefs = strip_comments(read(PREFS)) if os.path.isfile(PREFS) else ''
check('★★★ LocalModelFeature.java 在，而且是纯类（零 Android 依赖）',
      os.path.isfile(FEATURE)
      and 'android' not in re.sub(r'//.*|/\*.*?\*/', '', _check_feature, flags=re.S))
check('★★★ 开关就是 false（这一版从界面下线）；改成 true 必须能恢复',
      'public static final boolean ENABLED = false;' in _check_feature)
check('★★★ 服务商列表里**不再放**「本地模型」这一项（包在 `if (LocalModelFeature.ENABLED)` 里）',
      'if (LocalModelFeature.ENABLED) {' in CP
      and 'ALL.add(new Entry(LOCAL_ID, "本地模型（手机本机跑，不联网）",' in CP
      and CP.find('if (LocalModelFeature.ENABLED) {') < CP.find('ALL.add(new Entry(LOCAL_ID,'))
check('★★★ 历史选择过 local 的用户**读取时**映射回在线，而且**不写盘**（恢复时原样回来）',
      'return LocalModelFeature.resolveProvider(v.trim());' in _check_prefs
      and 'if (ChatProvider.LOCAL_ID.equals(storedId)) return FALLBACK_PROVIDER_ID;'
          in _check_feature)
check('★★★ 设置页那一组「🤖 本地模型」不再绘制（包在同一个开关里）',
      'if (LocalModelFeature.ENABLED) {' in MA
      and 'addSection(box, "\\uD83E\\uDD16 本地模型（手机本机跑，不联网）");' in MA)
check('★★ 面板方法本身**保留**（没有删代码 —— 用户说的是"后续再加上"）',
      'private void showLocalModelPanel()' in MA)

# ★★★ 本轮最关键的结构性判据：本地那一侧**根本没有能力**发 HTTP。
#     不是"我们写了 if 不走网络"，而是"HTTP 的符号在这里一个都不存在"。
HTTP_SYMBOLS = ('ApiClient', 'Authorization', 'HttpURLConnection', 'chatUrl',
                'apiKey', 'getApiKey', 'Bearer', 'max_tokens', 'Content-Type')
found = [s for s in HTTP_SYMBOLS if s in LC]
check('★★★ LocalChat 里**一个 HTTP 符号都没有**（连 apiKey 参数都不接）', not found, found)
found2 = [s for s in ('ApiClient.chat', 'ApiClient.chatFull', 'Authorization',
                      'HttpURLConnection', 'chatUrl')
          if s in func_body(CR, 'private static ApiClient.ChatResult local(')]
check('★★ ChatRouter 的**本地分支本体**里没有在线那条链的调用', not found2, found2)
check('★★ 本地分支把「上行 apiKey」也去掉了（本地不需要 Key，接了就是接线错了）',
      'apiKey' not in func_body(CR, 'private static ApiClient.ChatResult local('))
check('★★ ChatRouter 只是个**适配器**：三个在线方法各调一次，不许有别的花样',
      CR.count('ApiClient.chat(') == 1 and CR.count('ApiClient.chatReactive(') == 1
      and CR.count('ApiClient.chatProactive(') == 1,
      '%d / %d / %d' % (CR.count('ApiClient.chat('), CR.count('ApiClient.chatReactive('),
                        CR.count('ApiClient.chatProactive(')))
check('★★ 本地结果转成调用方在用的 ChatResult（适配在唯一一处）',
      'final LocalChat.Reply lr = LocalChat.answer(' in CR
      and 'final ApiClient.ChatResult r = new ApiClient.ChatResult();' in CR)
check('★★ 本机推理**不计用量/费用**（usageParsed 保持 false，别让用户以为花了钱）',
      'r.usageParsed = false;' in func_body(CR, 'private static ApiClient.ChatResult local('))
check('★★★ ApiClient（在线那条链）里**没有任何本地推理的痕迹** —— HTTP 只讲 HTTP',
      'LocalChat' not in API and 'LocalLlm' not in API and 'ChatRouter' not in API)
# ★ 判据不能"什么都能搜到"：一个绝不存在的串必须查不到
check('★ 判据本身有区分力（一个绝不存在的类名必须查不到）',
      'LocalChatZZNeverExists' not in API and 'LocalChatZZNeverExists' not in LC)


# ================================================================ 三、绝不联网

print()
print('=== 三、★★ 本地后端绝不联网（webAllowed 是唯一闸门）===')
check('★★ 闸门在分流点里，就一句：本地 ⇒ 不许检索',
      'public static boolean webAllowed(android.content.Context ctx) {' in CR
      and 'return !isLocal(ctx);' in func_body(CR, 'public static boolean webAllowed('))
check('★★ 悬浮窗「问一句」：联网检索被 webAllowed 挡在门外',
      'final boolean webOn = ChatRouter.webAllowed(FloatingService.this)\n'
      '                        && Prefs.webSearch(FloatingService.this);' in FS)
check('★★ 检索调用本身也绑在 webOn 上（不是"先搜了再丢掉"）',
      'final String sendText = webOn\n'
      '                        ? WebClient.augment(FloatingService.this, question) : question;' in FS
      and 'final WebClient.Last web = webOn ? WebClient.lastRun() : null;' in FS)
check('★★ 聊天窗口同样：webAllowed 挡住了联网检索',
      'final boolean webOn = ChatRouter.webAllowed(ChatActivity.this)\n'
      '                        && Prefs.webSearch(ChatActivity.this);' in CA
      and 'final String sendText = webOn ? WebClient.augment(ChatActivity.this, text) : text;' in CA)
check('★★ 两处「联网检索」入口各只有一处，且都在闸门之下（没有漏网的第二次调用）',
      FS.count('WebClient.augment(') == 1 and CA.count('WebClient.augment(') == 1,
      '%d / %d' % (FS.count('WebClient.augment('), CA.count('WebClient.augment(')))
# ★ 记忆点提炼（MemoryFacts）走的是**在线**链：本地后端下必须跳过，
#   否则每轮对话都会白起一个必然失败（没 Key）的 HTTP 请求。
check('★★ 本地后端跳过「记忆点提炼」（它走在线链，没有 Key 只会白发请求）',
      'if (!ChatRouter.isLocal(FloatingService.this)) {' in FS
      and FS.count('MemoryFacts.maybeUpdateAsync(') == 1)
check('★★ 聊天窗口也一样跳过', 'if (!ChatRouter.isLocal(ChatActivity.this)) {' in CA
      and CA.count('MemoryFacts.maybeUpdateAsync(') == 1)
check('★ 用户主动点「整理记忆点」时不是静默失败，而是**当面说清**本地干不了这个',
      '整理记忆点要用在线服务商（本机小模型干这个又慢又差）' in FS)


# ================================================================ 四、加载策略

print()
print('=== 四、★★ 加载：第一次用时载一次、之后常驻；换模型才 unload ===')
check('src/com/deepseek/pet/LocalChat.java 存在', os.path.isfile(LOCAL_CHAT))
check('★★ 记住"内存里载的是哪个文件"（isLoaded() 只说载没载，说不出是哪一个）',
      'private static String loadedPath = null;' in LC
      and 'public static String loadedPath() { return loadedPath; }' in LC)
check('★★ 需要载入的判据：没载 || 载的不是当前这个文件（不读盘、不建图，UI 可以随时问）',
      'if (!LocalLlm.isLoaded()) return true;' in func_body(LC, 'public static boolean needsLoad(')
      and 'return !f.getAbsolutePath().equals(loadedPath);'
          in func_body(LC, 'public static boolean needsLoad('))
check('★★ 已经载着**同一个**文件 ⇒ 直接复用（这就是"常驻"，不是每句话都重载）',
      'if (LocalLlm.isLoaded() && path.equals(loadedPath)) {' in LC)
check('★★ 载着**别的**文件 ⇒ 先卸再载（换了模型就必须换内存里那个）',
      'LocalLlm.unload();' in func_body(LC, 'public static Reply ensureLoaded(')
      and LC.find('LocalLlm.unload();') < LC.find('LocalLlm.load(path'))
check('★★ 载入要串行化（用户连点两次 / 主动搭话撞上问一句 = 两个线程同时载）',
      'private static final Object LOAD_LOCK = new Object();' in LC
      and 'synchronized (LOAD_LOCK) {' in LC)
check('★★★ 全工程**只有 LocalChat 这个后端**会载模型（UI 层一个都没有 —— 载入是同步阻塞的）'
      '★ 轮次71：1 → 2（ensureLoaded + selfTest）；★★ 轮次74：2 → 3'
      '（自动降级要求"再按纯 CPU 载一次"）—— 所以这条判据必须把**三个调用点逐字钉住**，'
      '只放宽数字会变成假绿',
      LC.count('LocalLlm.load(') == 3
      and 'LocalLlm.load(' not in FS and 'LocalLlm.load(' not in CA
      and 'LocalLlm.load(path, threads, LocalLlm.DEFAULT_CTX,' in func_body(LC, 'private static LoadResult loadPlanned(')
      and 'LocalLlm.load(path, threads, LocalLlm.DEFAULT_CTX, 0, GpuMode.CODE_OFF, tune);'
          in func_body(LC, 'private static LoadResult loadPlanned(')
      and 'LocalLlm.load(f.getAbsolutePath(), LocalLlm.defaultThreads(),'
          in func_body(LC, 'private static boolean reloadCpu('))
check('★★ 载入成功才记 loadedPath（失败还记着的话，下次会拿一个没载的模型去生成）',
      'loadedPath = null;\n                final String why = lr.why;' in LC
      and 'loadedPath = path;' in LC
      and 'if (ok) loadedPath = f.getAbsolutePath();' in LC)
check('★★ 首次载入有**明确等待反馈**（几百 MB 的 mmap，没有反馈就是静默卡住）',
      'public static String loadingHint()' in LC
      and '正在把本地模型载入内存' in LC
      and 'public static String loadHintIfNeeded(android.content.Context ctx)' in CR)
check('★ 两个"打字发消息"的入口都把它显示出来了',
      'hintLocalLoadingIfNeeded();' in FS
      and 'final String loadHint = ChatRouter.loadHintIfNeeded(this);' in CA
      and 'typing.setText(loadHint);' in CA)
check('★ 戳/拖 / 主动搭话也会先给等待反馈（第一次戳一下干等十几秒 = 像死了）',
      FS.count('hintLocalLoadingIfNeeded();') >= 4, '实际 %d 处' % FS.count('hintLocalLoadingIfNeeded();'))
check('★★ 换模型 / 删模型：后台线程里 unload + 忘掉"载着谁"',
      'LocalChat.forget();' in func_body(MA, 'private void switchLocalModel(final String name)')
      and 'LocalChat.forget();' in func_body(MA, 'private void deleteModelNow(final String name)')
      and 'try { LocalLlm.unload(); } catch (Throwable ignore) {}' in MA)
check('★★ **同名重导**也要卸：路径没变而盘上已是新内容，只按路径判断会用内存里的旧模型',
      'try { LocalLlm.unload(); } catch (Throwable ignore) {}\n'
      '                            LocalChat.forget();\n'
      '                        }}, "dspllm-import").start();'
      in func_body(MA, 'private void runModelImport('))
check('★★ 从「本地模型」切回在线服务商时把内存里那几百 MB 放掉',
      'final boolean wasLocal = ChatRouter.isLocal(this);' in MA
      and 'if (wasLocal && !toLocal) {' in MA
      and 'try { LocalLlm.unload(); } catch (Throwable ignore) {}' in MA)
check('★ 设置页把"内存里载没载"也说出来（用户要知道第一次对话会慢）'
      '（★ 轮次69：这一行的文案搬进了 LocalModelMenu —— 卸载之后必须看得出'
      '"当前没有载入模型"）',
      'LocalLlm.isLoaded()' in MA and '内存里' in MA
      and 'LocalModelMenu.memoryRow(LocalLlm.isLoaded(), cur)' in MA)
check('★★★ 轮次69：**「⏏ 卸载当前模型」真的接了**（引擎 unload + 忘账本 + 后台线程）',
      'private void unloadLocalModelNow()' in MA
      and 'LocalChat.unloadNow();' in MA
      and 'public static String unloadNow()' in LC
      and 'LocalLlm.unload();' in LC)


# ================================================================ 五、不许静默失败

print()
print('=== 五、★★ 没导入模型时不许静默（引导 + 判据 + 如实说明"慢、也更笨"）===')
check('★★ 判据就是 ModelStore.currentFile(ctx) == null ⇒ 明确的引导语',
      'final File f = ModelStore.currentFile(ctx);\n'
      '        if (f == null) return Reply.fail(guideNoModel());' in LC)
# ★★ 这两句必须落在 **guideNoModel 的方法体里**，不能只看全文：
#    `设置 → 陪伴 → 对话与人格` 在 `guideEngineMissing()` 里也出现了一次 ——
#    第一版判据只查全文，于是**把 guideNoModel 里那句改掉它照样绿**（假绿）。
#    反向套件的 U 项当场把这条抓出来了（反向验证的价值就在这）。
_GUIDE = func_body(LC, 'public static String guideNoModel()')
check('★★ 引导里写清了**去哪一步点哪个按钮**',
      'public static String guideNoModel()' in LC
      and '设置 → 陪伴 → 对话与人格' in _GUIDE
      and '导入 .gguf 文件' in _GUIDE)
check('★★★ 如实说明代价（不写这句，用户会拿它跟云端比然后以为功能坏了）',
      '慢一些、也更笨' in LC and '几百 MB' in LC)
check('★ 原生库装不上时也有话说（不是抛异常 / 不是静默）',
      'public static String guideEngineMissing()' in LC and 'arm64 手机' in LC)
check('★ 载入失败的话术给出可做的下一步（重导 / 换小的 / 切回在线）',
      'public static String guideLoadFailed(String why)' in LC
      and '重新导入，或切回在线服务商' in LC)
check('★ 一个字都没生成出来时也不是空回复（小模型偶尔会这样）',
      '本地模型一个字都没生成出来' in LC)
check('★★ 设置页也当面说清"选了本地模型但还没导入"',
      '已选本地模型，但还没导入' in MA)
check('★ 能力表那一行的说明不是"有 N 项参数由服务端固定"（本地没有服务端）',
      '本机推理：不联网、不需要 API Key、不产生费用' in CP
      and 'if (entry != null && LOCAL_ID.equals(entry.id)) {' in CP)
check('★ 没导入时用户看到的错误是**引导语**（不是"网络错误"这种牛头不对马嘴的话）',
      'showBubble("⚠️ " + (res == null ? "没有拿到回复" : res.error), 8000);' in FS
      and 'say("assistant", "（出错了：" + (res == null ? "没有拿到回复" : res.error) + "）", true);' in CA)
check('★ API Key 那一行在本地后端下写「本地模型不需要」（否则用户以为还差一步）',
      '"本地模型不需要"' in MA)
check('★ 没 Key 也不是死路：提示里给了"去选本地模型"这条出路',
      '或到「服务商」里选「\\uD83E\\uDD16 本地模型」' in CA)


# ================================================================ 六、流式

print()
print('=== 六、★★ 流式：sink 里只做 append；节流一处；不与连发/朗读/配图打架 ===')
sink_body = func_body(LC, 'public static LocalLlm.TokenSink throttledSink(')
check('★★ 攒字只有一处实现（两个界面共用），不是各写一遍',
      'acc.append(piece);' in sink_body)
check('★★★ 回调里**不许**再调引擎（同一把锁 = 自锁，第一轮的纪律）',
      'LocalLlm.generate(' not in sink_body and 'LocalLlm.load(' not in sink_body
      and 'LocalLlm.unload(' not in sink_body and 'throttledSink(' not in sink_body)
check('★★ 节流：按 FLUSH_MS 决定要不要刷（一个 token 刷一次 = 每秒几十次 measure/layout）',
      'LocalPrompt.shouldFlush(lastMs, now, LocalPrompt.FLUSH_MS)' in sink_body
      and 'public static boolean shouldFlush(long lastMs, long nowMs, long intervalMs)' in LP)
check('★★ sink 一律返回 true（"用户一说话就闭嘴"是调用方的事，不该藏在节流器里）',
      'return true;' in sink_body and 'return false;' not in sink_body)
check('★★ 气泡：生成期间走**低层** showBubbleInternal（不切句、不朗读、不配图）',
      'showBubbleInternal(prefix + soFar, 0, null);' in func_body(FS, 'private LocalLlm.TokenSink bubbleSink('))
check('★★ 生成期间**不碰**连发/朗读（否则半句话会被排队、被念出来）',
      'cancelReplySplit' not in func_body(FS, 'private LocalLlm.TokenSink bubbleSink(')
      and 'speakOwn' not in func_body(FS, 'private LocalLlm.TokenSink bubbleSink('))
check('★★ 生成**结束后**才把完整句子交给原来的连发/朗读那条链（老流程一行没改）',
      'showPetSaySplit("\U0001F4AC ", res.reply, resolveMeme(res.memeName), 12000);' in FS)
check('★ 「问一句」在本地后端下真的挂了 sink（在线链是 stream:false ⇒ 传 null）',
      'final LocalLlm.TokenSink sink = ChatRouter.isLocal(FloatingService.this)\n'
      '                        ? bubbleSink("\\uD83D\\uDCAC ") : null;' in FS)
check('★★ 聊天窗口：逐段把已经生成的部分画到"正在思考"那个气泡里',
      'LocalChat.throttledSink(new LocalChat.PieceSink() {' in CA
      and 'typing.setText(soFar);' in CA)
check('★ 聊天窗口只传 sink、不自己攒字（攒字/节流只有一处）',
      'StringBuilder' not in CA)


# ================================================================ 七、接线

print()
print('=== 七、★★ 接线：6 个对话发起点全部走 ChatRouter，没有一个漏回 ApiClient ===')
check('★★ 悬浮窗 4 条线（问一句 / 启动问候 / 继续话题 / 戳拖 / 主动搭话）都改了',
      FS.count('ChatRouter.chat(') == 3
      and FS.count('ChatRouter.chatReactive(') == 1
      and FS.count('ChatRouter.chatProactive(') == 1,
      'chat=%d reactive=%d proactive=%d' % (FS.count('ChatRouter.chat('),
                                           FS.count('ChatRouter.chatReactive('),
                                           FS.count('ChatRouter.chatProactive(')))
check('★★ 聊天窗口也改了', CA.count('ChatRouter.chat(') == 1)
check('★★★ 两个界面里**再没有**直接调 ApiClient 的对话方法（漏一处 = 那一处永远不本地）',
      'ApiClient.chat(' not in FS and 'ApiClient.chatReactive(' not in FS
      and 'ApiClient.chatProactive(' not in FS and 'ApiClient.chatFull(' not in FS
      and 'ApiClient.chat(' not in CA and 'ApiClient.chatFull(' not in CA)
check('★★ 人格蒸馏仍走在线链（那是"上万字文档"任务，不是对话；小模型干不了）',
      MA.count('ApiClient.chatFull(') == 1)
check('★ 分流之后发起点的写法一致（可读：同一行能看出"这句话交给谁"）',
      'ChatRouter.chat(FloatingService.this, apiKey, model, persona, ctx, cue, null);' in FS
      and 'ChatRouter.chat(\n                        FloatingService.this, apiKey, model, persona,'
          ' factsPrompt, mem, sendText, sink);' in FS)
check('★ 聊天窗口调用点带上了 sink（这是唯一"逐字出话"的窗口）',
      'ChatRouter.chat(ChatActivity.this, apiKey, model, persona,\n'
      '                                factsPrompt, snap, sendText, sink);' in CA)


# ================================================================ 八、能力表与设置页

print()
print('=== 八、★ ChatProvider 新增 local 条目 + 设置页如实显示与引导 ===')
check('★★ 能力表里有「本地模型（手机本机跑，不联网）」这一条',
      '"本地模型（手机本机跑，不联网）"' in CP
      and 'ALL.add(new Entry(LOCAL_ID, "本地模型（手机本机跑，不联网）",' in CP)
check('★ 放在**最前面**（它是不花钱不联网的那个答案，最容易被忽略）',
      CP.find('ALL.add(new Entry(LOCAL_ID,') > 0
      and CP.find('ALL.add(new Entry(LOCAL_ID,') < CP.find('ALL.add(new Entry("deepseek",'))
check('★ 参数能力一律 FIXED（本机推理没有"能不能发参数"这回事）',
      'new Settings().temp(FIXED).reason(FIXED).think(FIXED).penal(FIXED).topp(FIXED)'
      in CP)
check('★★ 选它时**不动地址/模型**（那是"在线那家"的设置，切回去还要用）',
      'if (toLocal) {' in func_body(MA, 'private void applyChatProvider(')
      and func_body(MA, 'private void applyChatProvider(').find('if (toLocal) {')
          < func_body(MA, 'private void applyChatProvider(').find('Prefs.saveBaseUrl(this, e.baseUrl);'))
check('★ 选定它的提示说清三件事：导没导模型 / 不联网不要 Key / 小模型更笨',
      '本机推理不联网、不需要 API Key' in MA
      and '本机小模型慢一些、也更笨' in MA
      and '还没导入模型：点「\\uD83E\\uDD16 本地模型」' in MA)
check('★★ 服务商列表里它**不显示"还没有 Key"**，而是显示模型导没导',
      '纯本机跑，不联网 · ' in MA and '本机模型已就绪' in MA)
check('★★ 本地后端下「模型」那一行指向本机模型，点它去本地模型面板（不是去拉在线 /models）',
      '? "本机 · " + localModelSummary() : Prefs.getChatModel(this)' in MA
      and 'if (ChatRouter.isLocal(MainActivity.this)) showLocalModelPanel();' in MA)
check('★ 本地模型面板里当面说清"这个模型有没有被用上"',
      '对话后端：目前还是在线服务商' in MA and '就是本机这个模型' in MA)


# ================================================================ 九、进包 + 登记

print()
print('=== 九、★★ 真的进了包 + 登记进全量套件（69 → 71）+ verify_features 探针 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('ChatRouter', 'LocalChat', 'LocalPrompt',
              'DSPChatRoute', 'DSPLocalChat',
              '本地模型（手机本机跑，不联网）', '本地还没导入模型',
              '正在把本地模型载入内存', '本地模型不需要',
              '本机小模型模式', '慢一些、也更笨', '<|im_start|>', 'webAllowed'):
        check('★★ dex 含 %s' % s, s.encode('utf-8') in dex)
    check('★ 探针本身有区分力（一个绝不存在的串必须查不到）',
          b'sanity_never_used_placeholder_zz' not in dex)
else:
    check('dev APK 可读', False)

if os.path.isfile(RUNBAT):
    bat = read(RUNBAT)
    check('★★ 本套件登记进全量套件 run_all_tests.bat（没登记 = 从来不会跑）',
          'verify_local_llm_chat.py' in bat)
    check('★★ 反向套件也登记了', 'reverse_check_local_llm_chat.py' in bat)
    import suite_index as _si
    check('★★ 全量套件编号自洽（同分母 + 末条==总数，自洽即通过）',
          _si.consistent(bat)[0], '%d 套' % _si.consistent(bat)[1])
    check('★ 前两轮的套件都还在（别把引擎层/导入层挤掉）',
          'verify_local_llm.py' in bat and 'verify_local_llm_import.py' in bat
          and 'reverse_check_local_llm.py' in bat
          and 'reverse_check_local_llm_import.py' in bat)
    # ★★ 轮次65 收尾复核：这里原来写的是 `'68/73' in bat and '69/73' in bat`
    #    —— 而本轮（对话接线）**自己的**编号是 **70/73 与 73/73**，
    #    68/73 与 69/73 是**上一轮（导入层）**的编号（它们当然在 bat 里）
    #    ⇒ 判据的说明写着"本轮的编号段在最后"，实际查的是别人的编号，
    #    **恒真、零区分力**（把本轮的编号改坏它也照样绿）。
    #    反向项 AK 专门破坏 70/73 来钉住它。
    # ★ 轮次79：这里原来写死 `'70/73' in bat and '73/73' in bat` —— 套件加到 76 套后立刻变红。
    #   改成**语义判据**：末两条必须是 (t-1)/t 与 t/t，t 由 suite_index 从 bat 里现算。
    check('★ 本轮的编号段在最后（末两条 = (t-1)/t 与 t/t）',
          _si.consistent(bat)[0]
          and ('%d/%d' % (_si.consistent(bat)[1] - 1, _si.consistent(bat)[1])) in bat
          and ('%d/%d' % (_si.consistent(bat)[1], _si.consistent(bat)[1])) in bat)

if os.path.isfile(FEATURES):
    fv = read(FEATURES)
    check('★★ verify_features.py 加了本轮的探针（真机上"看不到功能"= 忘了重编某个包）',
          "'本地模型：分流点类'" in fv and "'本地模型：本机对话后端类'" in fv
          and "'本地模型：没模型时的引导'" in fv)
    check('★ 探针挑的是**本轮才有的独有串**（不是"模型"这种到处都有的词）',
          "'DSPLocalChat'" in fv and "'webAllowed'" in fv)
    check('★ 第二轮那些探针没被挤掉', "'本地模型：导入入口'" in fv)
else:
    check('verify_features.py 存在', False)

# ★ 正式版工程是 make_variant.py 生成的：换包名之后这三份新类也必须跟着走
if os.path.isdir(REL_ROOT):
    for n in ('LocalPrompt', 'LocalChat', 'ChatRouter'):
        check('★★ 正式版工程里也有 %s（.java 跟着 make_variant 走）' % n,
              os.path.isfile(os.path.join(REL_ROOT, 'src', 'com', 'doropet', 'release',
                                          n + '.java')))
else:
    print('  （%s 不存在，正式版端到端检查跳过）' % REL_ROOT)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（本地模型对话接线：分流 / 不联网 / 常驻加载 / 引导 / 流式 / 进包）')
