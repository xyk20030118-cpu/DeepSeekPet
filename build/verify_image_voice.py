"""断言：图片背景 + 语音资源解绑。

★ 这个套件要守住两条**用户明确提出的约束**：
  ① 「加入图片背景导入功能（现在只能导入视频）」
     —— 图片背景必须真的存在、且走与视频同一套铺法数学
  ② 「把语音输入改为选择性下载资源（**不能影响宠物语音说话功能**）」
     —— 这是本套件最重要的一条：必须证明"听"和"说"是分开的，
        删掉语音输入资源**不会**碰到说话。

★ 为什么用源码断言而不是运行测试：
  这两条都是"结构性质"（哪些代码依赖哪些代码），
  静态检查比运行更快、更确定，也不需要在设备上真跑一遍。
"""
import io
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ASM = r'D:\petport_lab\petbuild\build\assemble.py'


def read(name):
    return io.open(r'%s\%s' % (SRC, name), encoding='utf-8-sig').read()


def strip_comments(s):
    """剥掉注释，但**必须跳过字符串字面量**。

    ★ 这里踩过一个真实的坑：朴素的
      {@code re.sub(r'/\\*.*?\\*/', '', s)} 遇到字符串里的 {@code "/*"}
      就会把它当成块注释开头 —— 而 {@code "image/*"}（选择的 MIME 类型）
      正好含它，于是从那里一直吃到后面某个 {@code */}，
      把大段**真实代码**删掉了。
      表现为"找不到 image/*、找不到 deleteModel"这类**假失败**。

    所以必须按字符扫描，跟踪"是否在字符串/字符字面量里"。
    """
    out = []
    i = 0
    n = len(s)
    in_str = None       # '"' 或 "'"
    while i < n:
        c = s[i]
        if in_str is not None:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(s[i + 1])
                i += 2
                continue
            if c == in_str:
                in_str = None
            i += 1
            continue
        # 不在字符串里
        if c == '"' or c == "'":
            in_str = c
            out.append(c)
            i += 1
            continue
        if c == '/' and i + 1 < n:
            if s[i + 1] == '/':
                j = s.find('\n', i)
                i = n if j < 0 else j
                continue
            if s[i + 1] == '*':
                j = s.find('*/', i + 2)
                i = n if j < 0 else j + 2
                continue
        out.append(c)
        i += 1
    return ''.join(out)


ma = read('MainActivity.java')
prefs = read('Prefs.java')
img = read('ImageBackgroundView.java')
asr = read('SherpaAsr.java')
voice = read('VoiceInputHelper.java')
tts = read('TtsEngine.java')
asm = io.open(ASM, encoding='utf-8-sig').read()

ma_c = strip_comments(ma)
prefs_c = strip_comments(prefs)
img_c = strip_comments(img)
asr_c = strip_comments(asr)
tts_c = strip_comments(tts)

pass_n = fail_n = 0


def ok(cond, what):
    global pass_n, fail_n
    if cond:
        pass_n += 1
    else:
        fail_n += 1
        print('  FAIL: %s' % what)


print('=== 图片 + 语音资源 断言 ===')
print()

# ============ ① 图片背景存在且实现完整 ============
print('[1] 图片背景导入')
ok('class ImageBackgroundView' in img_c, '必须有 ImageBackgroundView')
for m in ('setSource', 'setCrop', 'onDraw', 'decodeFile'):
    ok(m in img_c, 'ImageBackgroundView 必须有 %s' % m)
ok('imageBgFile' in prefs_c, 'Prefs 必须有 imageBgFile')
ok('imageBgEnabled' in prefs_c, 'Prefs 必须有 imageBgEnabled')
ok('imageBgDim' in prefs_c, 'Prefs 必须有 imageBgDim')
ok('imageBgCrop' in prefs_c, 'Prefs 必须有 imageBgCrop')
ok('pickImageBg' in ma_c, '必须有 pickImageBg（拉起选图器）')
ok('saveImageBg' in ma_c, '必须有 saveImageBg（拷进 filesDir）')
ok('REQ_PICK_IMAGE_BG' in ma_c, '必须有独立的 requestCode')
ok('image/*' in ma_c, '选择器 MIME 必须是 image/*')
ok('REQ_PICK_IMAGE_BG ==' in ma_c or 'REQ_PICK_IMAGE_BG &&' in ma_c,
   '必须有 onActivityResult 分支处理')
print('   选图 / 存储 / 渲染 / 设置项 齐全 ✓')

# ============ ② 图片复用视频的铺法数学（不要重写一遍）============
print()
print('[2] 图片复用 VideoFitMath（单一来源）')
ok('VideoFitMath.compute' in img_c, '图片铺法必须调用 VideoFitMath.compute')
ok('VideoFitMath.conformToAspect' in img_c, '图片裁框必须用 conformToAspect 校正')
ok('VideoFitMath.defaultCrop' in img_c, '图片必须有 defaultCrop 兜底')
ok('Matrix' in img_c, '用 Matrix 应用变换')
# ★★ 必须**不得**调用 toViewSpaceTransform。
#   那个函数是专为 TextureView 写的（它默认把纹理拉伸铺满视图，
#   setTransform 是叠在那层默认拉伸之上的，所以要先除掉一次缩放比）。
#   本类是裸画位图，没有那层默认拉伸 —— 照抄会让缩放被多除一次，
#   实测后果：图片按 1:1 画出，800x1600 的图只铺到 1080x2400 屏幕的 67%，
#   下面留一大片空白。这是本轮真实踩到的 bug。
ok('toViewSpaceTransform' not in img_c,
   'ImageBackgroundView **不得**用 toViewSpaceTransform（那是 TextureView 专用的，'
   '裸画位图会只铺 67%）')
print('   图片与视频共用数学层，但用对坐标空间 ✓')

# ============ ③ 大图不 OOM ============
print()
print('[3] 大图不 OOM（降采样）')
# ★ 断言的是 **= true**，不是"这个词出现过"：
#   破坏把 true 改成 false 时，词还在 —— 只查词会空转（我第一版就空转了）。
ok('inJustDecodeBounds = true' in img_c,
   '必须先用 inJustDecodeBounds=true 只读边界（否则大图 OOM）')
ok('inSampleSize' in img_c, '必须设置 inSampleSize')
ok('BitmapFactory.decodeFile' in img_c, '必须走 BitmapFactory 解码')
print('   先读边界 → 降采样解码 ✓')

# ============ ④ ★ 说话 vs 听：必须彻底分开 ============
print()
print('[4] ★★ 说话（TTS）与 听（ASR）必须互不依赖')
# TtsEngine 不得引用任何 ASR/sherpa 的东西
bad_in_tts = [k for k in ('SherpaAsr', 'sherpa', 'onnx', 'VoiceInputHelper',
                          'sensevoice', 'asr')
              if k in tts_c]
ok(not bad_in_tts,
   'TtsEngine 不得依赖语音识别（发现: %s）' % bad_in_tts)
print('   TtsEngine 不引用任何 ASR/onnx 符号 ✓')

# 删模型不得碰 TTS
dm = re.search(r'public static synchronized boolean deleteModel\(Context c\).*?\n    \}',
               asr_c, re.S)
ok(dm is not None, 'deleteModel 可解析')
if dm:
    body = dm.group(0)
    bad = [k for k in ('TtsEngine', 'tts', 'speak') if k in body]
    ok(not bad, 'deleteModel 不得触碰说话相关代码（发现: %s）' % bad)
    for f in ('modelFile', 'tokensFile', 'vadFile'):
        ok(f in body, 'deleteModel 必须删掉 %s' % f)
print('   deleteModel 只删 ASR 三件套，不碰 TTS ✓')

# TTS 是云端（不依赖本地模型）
ok('HttpURLConnection' in tts_c or 'URL(' in tts_c,
   'TtsEngine 必须是云端合成（用网络）')
ok('MediaPlayer' in tts_c, 'TtsEngine 必须用 MediaPlayer 播放')
print('   TtsEngine 是云端合成 + MediaPlayer（不依赖本地模型）✓')

# ============ ⑤ 瘦身形态：小文件恒内置，大模型独立分发 ============
print()
print('[5] 小文件随包、大模型走交付 zip 独立分发')
ok('BUNDLE_ASR' in asm, 'assemble.py 必须有 BUNDLE_ASR 开关')
ok('DSP_BUNDLE_ASR' in asm, '开关应可由环境变量控制（方便以后打完整包）')
# ★ 断言**默认值**，不是"这些词出现过"：
#   破坏把默认改成内置时两个词都还在 —— 只查词会空转（我第一版就空转了）。
ok('BUNDLE_ASR = os.environ.get("DSP_BUNDLE_ASR", "") == "1"' in asm,
   '默认必须是不内置大模型（只有显式设 DSP_BUNDLE_ASR=1 才打完整包）')
# ★ 小文件恒内置：ASR_ASSETS 初始表里必须有 vad + tokens，
#   大模型只在 BUNDLE_ASR 为真时才追加
ok('silero_vad.onnx' in asm and 'tokens.txt' in asm,
   '小文件（vad + tokens）必须恒内置')
ok('if BUNDLE_ASR:' in asm and 'sensevoice.int8.onnx' in asm,
   '大模型只在 BUNDLE_ASR 为真时追加')
print('   小文件恒内置 0.9MB，大模型默认走独立分发 ✓')

# ============ ⑥ 「没内置」不能当成错误 ============
print()
print('[6] 没内置 = 正常状态（不是错误）')
ok('REASON_NOT_BUNDLED' in asr_c, '必须有 REASON_NOT_BUNDLED 常量')
ok('bundledInAssets' in asr_c, '必须先探测 assets 里有没有内置')
# ★ 关键：断言的是"**赋值时用的是 NOT_BUNDLED**"，不是"这个词出现过"。
#   只查词的话，破坏把 REASON_NOT_BUNDLED 改成 REASON_IO 也抓不到
#   （常量定义还在）—— 我第一版就这样空转过一次。
ok('lastReason = REASON_NOT_BUNDLED' in asr_c,
   '探测到没内置时，lastReason 必须赋成 NOT_BUNDLED（不是 IO 错误）')
ok('REASON_NOT_BUNDLED.equals' in voice,
   '语音输入失败时要区分"没装资源"与"真出错"')
ok('桌宠说话不受影响' in voice or '不受影响' in voice,
   '提示语要说明"不影响说话"')
ok('statusText' in asr_c, '必须有给用户看的状态说明')
ok('isUsable' in asr_c, '必须有 isUsable 供 UI 判断')
print('   区分"未安装"与"出错"，并说明不影响说话 ✓')

# ============ ⑦ 设置页要有删除 / 文件导入入口 ============
print()
print('[7] 设置页可删除、也可从文件导入语音模型')
ok('删除语音输入资源' in ma, '设置页必须有删除按钮')
ok('SherpaAsr.deleteModel' in ma_c, '删除按钮必须调 deleteModel')
ok('installedBytes' in ma_c, '设置页要显示占用大小')
ok('语音输入资源' in ma, '必须有独立小节')
# ★ 断言**具体那句文案**，不用"片段里含说话"这种宽松判断 ——
#   窗口开大一点，删掉这句后别处仍有"说话"二字，就抓不到了（空转过一次）。
ok('桌宠**说话**用的是云端语音合成' in ma,
   '删除确认框必须明确写"说话用的是云端语音合成"')
ok('只影响「语音输入」' in ma, '删除确认框必须写明"只影响语音输入"')
# ★ 必须钉**按钮创建行**，不能只查文案出现：
#   对话框标题里也有"从文件导入语音模型"六个字 —— 只查文案的话，
#   删掉按钮、标题还在，断言照样通过（M 项空转过一次）。
ok('addButton(box, "\\uD83D\\uDCE5 从文件导入语音模型' in ma,
   '设置页必须有从文件导入按钮')
ok('REQ_PICK_ASR_MODEL' in ma_c, '导入必须有独立 requestCode（不得与其它码撞号）')
ok('SherpaAsr.importModelFile' in ma_c, '导入必须调 importModelFile（拷贝+校验+落盘）')
ok('正在导入语音模型' in ma, '导入时必须有进度提示（228MB 拷贝阻塞，放后台线程）')
print('   删除入口 + 文件导入入口 + 占用显示 + 不影响说话的说明 ✓')

# ============ ⑦之二 importModelFile 本体语义 ============
print()
print('[7b] 文件导入必须校验体积 + 原子落盘')
ok('MODEL_MIN_BYTES' in asr_c, '必须有 MODEL_MIN_BYTES 常量（阈值单一来源）')
ok('MODEL_MIN_BYTES = 200L * 1024 * 1024' in asr_c,
   '阈值必须是 200MB（与 sensevoice.int8.onnx 228MB 对应）')
# ★ isInstalled 必须引用同一常量，而不是各写一个 200L ——
#   否则改阈值时两处脱节（"导入时嫌小、判定又嫌大"）。
ok('modelFile(c).length() > MODEL_MIN_BYTES' in asr_c,
   'isInstalled 必须引用 MODEL_MIN_BYTES（与导入同源）')
ok('MODEL_NAME + ".tmp"' in asr_c, '导入必须先写 .tmp 再改名（原子落盘）')
ok('public static synchronized String importModelFile' in asr_c,
   'importModelFile 必须同步 + 返回人话错误（调用方直接展示）')
ok('REQ_PICK_ASR_MODEL' in ma_c and 'importModelFile' in ma_c,
   'onActivityResult 必须有 REQ_PICK_ASR_MODEL 分支调导入')
print('   阈值同源 + 原子落盘 + 人话错误 ✓')

# ============ ⑧ 图片/视频选项已合并（第二十二轮）============
print()
print('[8] 图片与视频选项合并为一个类型选择器')
# 必须只有一个 "背景" 小节，不再是两个
ok('addSection(box, "\\uD83D\\uDDBC 背景")' in ma,
   '必须只有一个合并后的「背景」小节')
ok('addSection(box, "\\uD83D\\uDDBC 图片背景")' not in ma,
   '不应再有独立的「图片背景」小节')
ok('addSection(box, "\\uD83C\\uDFAC 视频背景")' not in ma,
   '不应再有独立的「视频背景」小节')
# 三选一
ok('BG_NONE' in prefs_c and 'BG_IMAGE' in prefs_c and 'BG_VIDEO' in prefs_c,
   'Prefs 必须有 BG_NONE / BG_IMAGE / BG_VIDEO 三个常量')
ok('setBgKind' in prefs_c, 'Prefs 必须提供 setBgKind（原子三选一）')
ok('不用背景' in ma and '"图片"' in ma and '"视频"' in ma,
   '设置页必须有「不用背景 / 图片 / 视频」三个选项')
# ★ 互斥必须由 Prefs 一处保证
sb = re.search(r'public static void setBgKind\(Context ctx, String kind\).*?\n    \}',
               prefs_c, re.S)
ok(sb is not None, 'setBgKind 可解析')
if sb:
    body = sb.group(0)
    ok('putBoolean(KEY_IMAGE_BG, img)' in body
       and 'putBoolean(KEY_VIDEO_BG, vid)' in body,
       'setBgKind 必须在**同一次 edit** 里同时写两个开关（否则可能出现都开/都关）')
# 导入路径必须也走 setBgKind（否则会退回"两个都开着"）
ok('Prefs.setBgKind(this, Prefs.BG_IMAGE)' in ma,
   '选完图片必须走 setBgKind（顺带关掉视频）')
ok('Prefs.setBgKind(MainActivity.this, Prefs.BG_VIDEO)' in ma,
   '视频裁剪完成后必须走 setBgKind（顺带关掉图片）')
# 旧的"谁优先"开关应已从 UI 移除
ok('有视频时优先用图片' not in ma,
   'UI 里不应再有「有视频时优先用图片」开关（类型已唯一确定）')
print('   单一小节 + 三选一 + 互斥收在一处 ✓')

print()
print('通过 %d 项' % pass_n)
if fail_n:
    print('失败 %d 项' % fail_n)
    sys.exit(1)
