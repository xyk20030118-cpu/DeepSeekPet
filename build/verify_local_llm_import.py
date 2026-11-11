# -*- coding: utf-8 -*-
"""本地 GGUF 模型部署 · **导入层**（轮次62 第二轮）。

用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
第一轮做完**引擎**（llama.cpp 交叉编译 + JNI + `LocalLlm`）；
这一轮做**导入**：设置页入口 → 系统文件选择器挑 `.gguf` → 流式拷进 `filesDir/models/`
→ 校验 / 进度 / 可取消 / 可删 / 可切换。对话接线是第三轮。

## ★★ 为什么另开一个套件，而不是往 `verify_local_llm.py` 里加一节

`verify_local_llm.py` 已经是**八节**、且每一条都在钉"**包里的东西对不对**"
（ELF 架构 / 导出符号 / 依赖链 / 页对齐）。导入层要钉的东西**完全在另一个平面上**：
文件系统、ContentResolver、线程、进度框、确认框、纯函数。
混在一起会有两个具体坏处：
  ① 反向套件要按"一条判据守一条性质"来配破坏项 —— 两轮的破坏项放在一个文件里，
     锚点会互相干扰（本轮实测：改 `run_all_tests.bat` 的编号总数会同时打到两轮的判据）；
  ② 一个套件红了，看名字（`verify_local_llm`）说不清是**引擎**坏了还是**导入**坏了。
⇒ 单独一份，各自的反向套件也各自一份。

## 分节

  一、★ 纯类 `ModelFile` 在 / **零 Android 依赖** / 被 fpstest 真编真跑（`[33]`）
  二、★★ GGUF 身份：magic 是**硬门槛**（不是 GGUF 当场拒），头部字段读得对
  三、★★ 文件名净化：路径 / 非法字符 / 全点 / 前导点 / 补扩展名 / 限长（按字符，防 ENAMETOOLONG）
  四、★★ 体积与空间：人类可读（Locale.US 固定小数点）、余量、脏输入判"不够"
  五、★★ 落盘：`filesDir/models/`、**usableSpace**、`.part` 半拉文件、当前模型的概念
  六、★★ 拷贝：ContentResolver **流式** + **后台线程** + 禁"整读进 byte[]" + 取消 + 拷完对账
  七、★★ 设置页入口与操作面：一行 › / 导入 / 切换 / 删除（说清删的是什么多大）/ 进度可取消
  八、★ 不违反第一轮的调用纪律（导入路径不 load/generate；unload 只在后台线程）
  九、★★ 真的进了包 + 登记进全量套件（67 → 69）+ `verify_features` 探针

反向破坏见 reverse_check_local_llm_import.py。

## ★ 与 fpstest 的分工（别搞混）

**行为**由 fpstest `[33]` 真编真跑（造出真的 GGUF 字节、真的调 `ModelFile`）；
这个套件钉的是"**接线**"：那句话有没有被用上、那段文案还在不在、那条写法有没有被换掉。
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

MODEL_FILE = os.path.join(SRC, 'ModelFile.java')
MODEL_STORE = os.path.join(SRC, 'ModelStore.java')
MODEL_IMPORTER = os.path.join(SRC, 'ModelImporter.java')
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
    """去掉注释，但**保留字符串字面量**。

    ★ 两道要求都要满足，所以不能直接用现成的 `code_only`：
      · 用户可见文案（「导入 .gguf 文件…」）必须能被搜到 ⇒ 不能把字符串抹掉；
      · 注释里提到同一句话**不算数** ⇒ 功能删了、只留一句注释也必须红。
    """
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


# 三份新文件的"无注释但保留字符串"视图（UI 文案要在这上面搜）
MF = strip_comments(read(MODEL_FILE)) if os.path.isfile(MODEL_FILE) else ''
MS = strip_comments(read(MODEL_STORE)) if os.path.isfile(MODEL_STORE) else ''
MI = strip_comments(read(MODEL_IMPORTER)) if os.path.isfile(MODEL_IMPORTER) else ''
MA = strip_comments(read(MAIN)) if os.path.isfile(MAIN) else ''
PR = strip_comments(read(PREFS)) if os.path.isfile(PREFS) else ''


# ================================================================ 一、纯类

print('=== 一、★ 纯类 ModelFile：在 / 零 Android 依赖 / 被 fpstest 真编真跑 ===')
check('src/com/deepseek/pet/ModelFile.java 存在', os.path.isfile(MODEL_FILE))
check('★ ModelFile 里有 GGUF magic 常量', '"GGUF"' in MF)
check('★ 落盘目录名与半拉后缀都收在纯类里（改一处就够）',
      '"models"' in MF and '".part"' in MF)

# ★★ 这条是结构性的：fpstest 用**光秃秃的 javac** 编 ModelFile，
#    一旦它 import android.* 就当场编不过 ⇒ "零 Android 依赖"不是靠人看出来的。
bad_imp = [l for l in MF.splitlines() if l.strip().startswith('import ') and 'android' in l]
check('★★ ModelFile **零 Android 依赖**（只用 java.nio.charset / java.util.Locale）',
      not bad_imp and 'android' not in MF, bad_imp or '出现 android 字样')

if os.path.isfile(FPSTEST_BAT):
    bat = read(FPSTEST_BAT)
    check('★★ fpstest 的 run.bat 真的编了这一份 ModelFile（编的必须与打进 APK 的同源）',
          '..\\..\\src\\com\\deepseek\\pet\\ModelFile.java' in bat)
else:
    check('fpstest/run.bat 存在', False)

FT = read(FPSTEST_JAVA) if os.path.isfile(FPSTEST_JAVA) else ''
check('★★ FpsTest 有 [33] 这一节（纯类被**真跑**，不是只被 grep）',
      '[33] ModelFile' in FT)
check('★★ FpsTest 自己造真 GGUF 字节（magic/version/tensorCount/kvCount 全小端）',
      "b.write('G'); b.write('G'); b.write('U'); b.write('F');" in FT
      and 'putLe32(b, version);' in FT and 'putLe64(b, tensors);' in FT)


# ================================================================ 二、GGUF 身份

print()
print('=== 二、★★ GGUF 身份：magic 是硬门槛（不是 GGUF 当场拒），头部字段读得对 ===')
# ★★ 本套件最值钱的一条：它守的是"用户随手点了个 zip/mp4，一路绿灯收下"这种坏法 ——
#    那种坏法要到真机加载时才炸，而用户早就以为"导入成功了"。
check('★★ magic 判定就在那 4 个字节上（不看扩展名 / 不看 MIME）',
      "head[0] == 'G' && head[1] == 'G' && head[2] == 'U' && head[3] == 'F'" in MF)
check('★★ 判定的入口是纯函数 isGguf(byte[])（导入侧直接调它）',
      'public static boolean isGguf(byte[] head)' in MF
      and 'ModelFile.isGguf(buf)' in MI)
check('★ 版本 / 张量个数 / KV 条数各有一个读头部的纯函数',
      'public static int ggufVersion(byte[] head)' in MF
      and 'public static long ggufTensorCount(byte[] head)' in MF
      and 'public static long ggufKvCount(byte[] head)' in MF)
check('★ 小端读取（写成大端就读不出真值 —— fpstest 造的是小端字节）',
      'leU32(head, 4)' in MF and 'leU64(head, 8)' in MF)
check('★ 只在**已有头部缓冲**里找 general.name（读不动就收工，绝不回头去读文件后面）',
      'public static String ggufName(byte[] head)' in MF
      and 'if (sz < 0) return null;' in MF
      and '"general.name".equals(key)' in MF)
check('★★ 头部缓冲有上限（64 KB）且与文件大小无关 —— 不是"把整个模型读进来判类型"',
      'HEAD_PEEK = 64 * 1024' in MF and 'HEADER_MIN = 24' in MF)

# fpstest 里那几条行为断言（真跑）—— 每一条都对应上面一个函数
for probe, label in (
        ('ModelFile.isGguf(ok3)', '真 GGUF ⇒ true'),
        ('!ModelFile.isGguf(zip)', 'zip 头 PK\\x03\\x04 ⇒ false'),
        ('ModelFile.ggufVersion(ok3) == 3', '读出格式版本 3'),
        ('ModelFile.ggufTensorCount(ok3) == 291L', '读出张量个数'),
        ('"Qwen2.5-0.5B-Instruct".equals(ModelFile.ggufName(ok3))', '读出 general.name'),
):
    check('★★ fpstest 真跑：%s' % label, probe in FT)


# ================================================================ 三、文件名净化

print()
print('=== 三、★★ 文件名净化（SAF 给的名字不能直接当路径用）===')
check('★★ 只取最后一段路径（带 / 或 \\ 的名字可能指到目录外面）',
      "int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\\\'));" in MF
      and 'if (cut >= 0) s = s.substring(cut + 1);' in MF)
check('★★ 全是点的名字判 null（"." / ".." 是目录，不是文件名）',
      'if (s.length() == 0 || isAllDots(s)) return null;' in MF)
check('★★ 前导点被削掉（点开头 = 隐藏文件，用户会以为"没导入"）',
      "while (i < s.length() && s.charAt(i) == '.') i++;" in MF)
check('★ 控制字符与非法字符都处理了（: / \\ * ? " < > | 换成下划线）',
      'String ILLEGAL = ' in MF and "b.append(ILLEGAL.indexOf(c) >= 0 ? '_' : c);" in MF
      and 'if (c < 0x20 || c == 0x7F) continue;' in MF)
check('★★ 限长按**字符**（CJK 一字 3 字节 ⇒ 64 字符最多 197 字节 < 255 的硬上限）',
      'MAX_BASE_CHARS = 64' in MF and 's.substring(0, MAX_BASE_CHARS) + EXT' in MF)
check('★ 没扩展名就补 .gguf（magic 才是真判据，名字只是给人看）',
      'if (!s.toLowerCase(Locale.US).endsWith(EXT)) s = s + EXT;' in MF)
check('★ 净化失败返回 null（调用方必须拒绝，不能拿空名字当文件名）',
      'if (raw == null) return null;' in MF)
for probe, label in (
        ('"model.gguf".equals(ModelFile.sanitizeName("/sdcard/Download/model.gguf"))', '带路径只留末段'),
        ('"x.gguf".equals(ModelFile.sanitizeName("..\\\\evil\\\\x.gguf"))', '反斜杠同理'),
        ('ModelFile.sanitizeName("..") == null', '".." 被拒'),
        ('"a_b_c_d.gguf".equals(ModelFile.sanitizeName("a:b*c?d.gguf"))', '非法字符换下划线'),
        ('"hidden.gguf".equals(ModelFile.sanitizeName(".hidden"))', '前导点被削'),
        ('"noext.gguf".equals(ModelFile.sanitizeName("noext"))', '补扩展名'),
        ('longName.length() == ModelFile.MAX_BASE_CHARS + 5', '超长名字被截'),
        ('utf8Len(longName) < 255', '截完仍在 255 字节内'),
):
    check('★★ fpstest 真跑：%s' % label, probe in FT)


# ================================================================ 四、体积与空间

print()
print('=== 四、★★ 体积与空间（动手之前先算清楚）===')
check('★★ 体积文案固定 Locale.US 的小数点（默认 locale 在部分地区会写成 398,5）',
      'String.format(Locale.US, "%.1f", v)' in MF)
check('★★ 空间检查留余量（文件的 10% 与 64 MB 取大者）—— 卡在最后几 MB 失败最亏',
      'RESERVE_MIN = 64L * 1024 * 1024' in MF
      and 'if (extra < RESERVE_MIN) extra = RESERVE_MIN;' in MF)
check('★★ 脏输入（可用空间读不到 / 为 0）判"不够"，不硬开',
      'if (freeBytes <= 0) return false;' in MF)
check('★ 空间不足时给的话术把三个数都摆出来（不让用户猜）',
      'public static String spaceHint(long freeBytes, long needBytes)' in MF
      and '"空间不够：这个文件 "' in MF)
check('★★ 导入侧**动手之前**先查空间（拷到 90% 才失败 = 白等十分钟）',
      'ModelFile.enoughSpace(free, sizeHint)' in MI
      and 'ModelFile.spaceHint(free, sizeHint)' in MI)
for probe, label in (
        ('"398.5 MB".equals(ModelFile.humanSize(417857536L))', '人类可读体积'),
        ('java.util.Locale.setDefault(java.util.Locale.GERMANY)', '德语环境真跑一遍'),
        ('ModelFile.requiredFree(400 * MB) == 464 * MB', '400 MB 要 464 MB'),
        ('!ModelFile.enoughSpace(ModelFile.requiredFree(400 * MB) - 1, 400 * MB)', '少 1 字节就拒'),
        ('!ModelFile.enoughSpace(0, 100)', '可用空间 0 判不够'),
):
    check('★★ fpstest 真跑：%s' % label, probe in FT)


# ================================================================ 五、落盘

print()
print('=== 五、★★ 落盘：filesDir/models/ + 半拉文件 + "当前用哪个" ===')
check('★★ 落在**应用私有** filesDir/models/（卸载自动清；不是 cacheDir —— 那会被系统清掉）',
      'new File(ctx.getFilesDir(), ModelFile.DIR)' in MS)
check('★★ 可用空间用 getUsableSpace()（getFreeSpace 会把系统保留块算成可用 ⇒ "够"然后写一半失败）',
      'getUsableSpace()' in MS and 'getFreeSpace()' not in MS)
check('★★ 半拉文件（*.part）不算模型、且会被清掉（否则用户看到两个几乎同名的文件）',
      'ModelFile.isPartName(f.getName())' in MS
      and 'public static int cleanStaleParts(Context ctx)' in MS
      and 'ModelStore.cleanStaleParts(ctx);' in MI)
check('★★ 先写 .part、**成功才改名**（失败/取消/被杀都不会留下"看起来正常"的残模型）',
      'ModelFile.partName(name)' in MI and 'part.renameTo(target)' in MI)
check('★★ "当前用哪个"记在偏好里（只记文件名不记绝对路径 —— 路径会随重装失效）',
      '"local_llm_model"' in PR
      and 'public static String getLocalModel(Context ctx)' in PR
      and 'public static void setLocalModel(Context ctx, String fileName)' in PR)
check('★★ 当前模型的文件不在时清掉选择（否则界面显示一个不存在的模型，加载时才炸）',
      'if (!f.isFile()) {' in MS and 'Prefs.setLocalModel(ctx, null);' in MS)
check('★ 删当前那个时把选择一起清掉', 'ModelFile.sameName(Prefs.getLocalModel(ctx), name)' in MS)
check('★ 允许并存多个，但只有一个"当前"（面板/切换列表都按这个来）',
      'public static java.util.List<File> list' in MS or 'public static List<File> list' in MS)


# ================================================================ 六、拷贝

print()
print('=== 六、★★ 拷贝：ContentResolver 流式 + 后台线程 + 可取消 + 对账 ===')
check('★★ 用 ContentResolver 的 openInputStream（SAF 的 Uri 没有真实文件路径）',
      'getContentResolver().openInputStream(' in MI)
check('★★ 拷贝在**后台线程**上（几百 MB 顺序 IO 放主线程 = 界面冻住 + ANR）',
      'Thread t = new Thread(work, "dspllm-import");' in MI and 't.start();' in MI)
# ★★ 这条是"低内存/大文件"的核心：任何"整读进 byte[]"都等于把整个模型塞进堆里
BANNED = ['readAllBytes', 'toByteArray', 'ByteArrayOutputStream', 'readFully', 'Files.readAll']
left = [b for b in BANNED if b in MI]
check('★★ 拷贝**没有**"一次整读进 byte[]"的写法（%s）' % ', '.join(BANNED), not left, left)
check('★★ 读法是**定长缓冲循环**（256 KB 一块，与文件多大无关）',
      'public static final int CHUNK = 256 * 1024;' in MI
      and 'final byte[] buf = new byte[CHUNK];' in MI
      and 'while ((n = in.read(buf)) > 0) {' in MI)
check('★★ 第一块就验 magic（不是 GGUF 当场拒，不让一个 zip 拷完十分钟再报错）',
      'if (n < 4 || !ModelFile.isGguf(buf)) {' in MI)
check('★★ 可取消：volatile 标志 + 拷贝循环**每一块**都看它一眼',
      'private volatile boolean cancelled = false;' in MI
      and 'if (job.isCancelled()) {' in MI)
check('★★ 失败/取消一律清掉半拉文件（留着既占空间，又让人以为"导入过了"）',
      'deleteQuietly(part);' in MI and MI.count('deleteQuietly(part);') >= 3,
      '出现 %d 次' % MI.count('deleteQuietly(part);'))
check('★★ 拷完对账：与系统给的 SIZE 不一致就算失败（截断的模型比没模型更难查）',
      'if (sizeHint > 0 && copied != sizeHint) {' in MI)
check('★ 进度回调自带百分比与字节数（界面既能画条也能显示"已拷 X"）',
      'void onProgress(int percent, long copied, long total);' in MI
      and 'lis.onProgress(pct, copied, sizeHint)' in MI)
# ★★ 大小未知时**按块**回调 = 每 256 KB 一次 runOnUiThread 改进度框文案（1 GB ≈ 4000 次
#    ⇒ 每秒两百次 TextView 重排），界面会明显发顿 ⇒ 必须攒够一段再报。
check('★★ 大小未知时进度也**攒够 2 MB 才报一次**（按块报会把 UI 线程刷死）',
      'REPORT_STEP_BYTES = 2L * 1024 * 1024' in MI
      and 'if (copied - lastBytes >= REPORT_STEP_BYTES) {' in MI)
check('★ 大小问不到时退化成"不确定进度"，并且**不做**对账（不拿假大小误判）',
      'public static long sizeOf(Context ctx, Uri uri)' in MI and 'return -1;' in MI)
check('★ 名字问不到时退化成"本地模型.gguf"（不能因为没名字就放弃导入）',
      '"本地模型.gguf"' in MA)
check('★ 异常转成用户能懂的一句话（别把类名与内存地址丢给用户）',
      'private static String shortReason(Throwable t)' in MI and '"存储空间写满了"' in MI)


# ================================================================ 七、设置页

print()
print('=== 七、★★ 设置页入口与操作面（导入 / 切换 / 删除 / 进度 / 确认框）===')
# ★ 结构判据：这一组必须落在「对话与人格」(settingsSection == 1) 这一页里，
#   而且要在这一页的**尾部**（本地模型是"另一个对话后端"，紧跟服务商/模型那组之后）。
i_s1 = MA.find('if (settingsSection == 1) {')
i_lm = MA.find('本地模型（手机本机跑，不联网）')
i_s3 = MA.find('if (settingsSection == 3) {')
check('★★ 本地模型这一组落在「对话与人格」(section 1) 里，且在服务商那组之后',
      i_s1 >= 0 and i_lm > i_s1 and i_s3 > i_lm, '%d < %d < %d' % (i_s1, i_lm, i_s3))
check('★★ 分组标题写明"本机跑、不联网"（用户一眼知道这条链不花钱）',
      '本地模型（手机本机跑，不联网）' in MA)
check('★★ 入口那一行：「🤖 本地模型：<未导入|名字 · 体积> ›」',
      '"\\uD83E\\uDD16 本地模型：" + localModelSummary() + " \\u203A"' in MA
      and 'private String localModelSummary()' in MA
      and 'ModelFile.summary(cur, sz, ModelStore.list(this).size())' in MA)
check('★★ 点开是操作面 showLocalModelPanel()',
      'showLocalModelPanel();' in MA and 'private void showLocalModelPanel()' in MA)
check('★★ 导入入口：📥 导入 .gguf 文件…',
      '\\uD83D\\uDCE5 导入 .gguf 文件…' in MA)
check('★★ 切换入口：🔄 切换当前模型（已导入 N 个）… + 单选列表 + 当前打勾',
      '\\uD83D\\uDD04 切换当前模型（已导入 ' in MA
      and 'private void showModelPicker()' in MA
      and 'setSingleChoiceItems(labels, checked' in MA
      and 'private void switchLocalModel(final String name)' in MA)
check('★★ 删除入口：🗑 删除本地模型（<当前那个>）…',
      '\\uD83D\\uDDD1 删除本地模型（' in MA)
check('★★ 删除**确认框说清删的是什么、多大**，并说明"当前模型删了要重新导入"',
      'private void confirmDeleteModel(final String name)' in MA
      and 'ModelFile.humanSize(sz)' in MA
      and '★ 它正是当前使用的模型，删完需要重新导入才能用本地模型。' in MA
      and '.setPositiveButton("删除"' in MA
      and 'private void deleteModelNow(final String name)' in MA)
check('★★ 文件选择器用 ACTION_OPEN_DOCUMENT（长期可读的文档 Uri，不是 GET_CONTENT）',
      'Intent.ACTION_OPEN_DOCUMENT' in MA
      and 'Intent.ACTION_OPEN_DOCUMENT' in func_body(MA, 'private void startGgufPick()'))
check('★ 不靠 MIME 过滤（手机上的 .gguf 大多报成 octet-stream ⇒ 只按它过滤会让用户"看不到文件"）',
      'i.setType("*/*");' in MA and 'EXTRA_MIME_TYPES' in MA)
check('★ 请求码 2022 且 onActivityResult 里有分支（★ 撞号的教训写在常量注释里）',
      'REQ_PICK_GGUF = 2022' in MA and 'requestCode == REQ_PICK_GGUF' in MA)
check('★★ 进度框走既有入口 UiFactory.progress + **横向进度条**',
      'UiFactory.progress(this)' in func_body(MA, 'private void runModelImport(')
      and 'pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);' in MA)
check('★★ **可取消**：取消按钮与返回键都发停止信号（holder[0].cancel()）',
      'holder[0].cancel();' in MA and 'setOnCancelListener' in MA
      and 'pd.setCanceledOnTouchOutside(false);' in MA)
check('★ 进度回调在后台线程 ⇒ 回 UI 线程再动控件（runOnUiThread）',
      'runOnUiThread(new Runnable() { @Override public void run() {' in MA
      and 'private void runModelImport(Uri uri, String name, long size)' in MA)
check('★ 同名覆盖要先问（默默覆盖会毁掉用户上次导入的模型）',
      '已有同名模型' in MA and '覆盖导入' in MA)
check('★ 导完自动设为当前模型（用户刚挑的，意图明确；只有一个时"当前"必须是它）',
      'ModelStore.setCurrent(MainActivity.this, nm);' in MA)
check('★ 设备侧日志标记 DSPModel（悬浮窗/弹窗不在 uiautomator dump 里，只能靠日志）',
      'DSPModel' in MA and 'private static final String TAG = "DSPModel";' in MS)


# ================================================================ 八、调用纪律

print()
print('=== 八、★ 不违反第一轮交代的调用纪律 ===')
check('★★ 导入层**完全不碰 LocalLlm**（不 load / 不 generate / 不在拷贝回调里 unload）',
      'LocalLlm' not in MI)
check('★★ 导入路径**不加载模型**（load 是同步阻塞的，第一轮明说要在第一次真正要用时调）',
      'LocalLlm.load(' not in func_body(MA, 'private void runModelImport('))
check('★★ unload 只在**后台线程**上调（它会先发停止信号再等锁；万一正在生成，主线程等它就是卡一下）',
      'LocalLlm.unload()' in func_body(MA, 'private void deleteModelNow(')
      and 'LocalLlm.unload()' in func_body(MA, 'private void switchLocalModel(')
      and 'new Thread(new Runnable() { @Override public void run() {' in MA)


# ================================================================ 九、进包 + 登记

print()
print('=== 九、★★ 真的进了包 + 登记进全量套件 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('ModelFile', 'ModelStore', 'ModelImporter', 'DSPModel',
              '导入 .gguf 文件', '删除本地模型', '切换当前模型',
              '这不是 GGUF 模型文件', '本机可用空间', 'local_llm_model'):
        check('★★ dex 含 %s' % s, s.encode('utf-8') in dex)
    # ★ 反向对照：探针不能"什么都能搜到" —— 一个绝不存在的串必须查不到
    check('★ 探针本身有区分力（一个绝不存在的串必须查不到）',
          b'sanity_never_used_placeholder_zz' not in dex)
else:
    check('dev APK 可读', False)

if os.path.isfile(RUNBAT):
    bat = read(RUNBAT)
    check('★★ 本套件登记进全量套件 run_all_tests.bat（没登记 = 从来不会跑）',
          'verify_local_llm_import.py' in bat)
    check('★★ 反向套件也登记了', 'reverse_check_local_llm_import.py' in bat)
    import suite_index as _si
    check('★ 全量套件编号自洽（同分母 + 末条==总数）',
          _si.consistent(bat)[0], '%s 套' % _si.consistent(bat)[1])
    check('★ 上一轮那两条还在（别把引擎层的套件挤掉了）',
          'verify_local_llm.py' in bat and 'reverse_check_local_llm.py' in bat)

if os.path.isfile(FEATURES):
    fv = read(FEATURES)
    # ★★ 判据只能查**带引号的整条元组名**：本轮第一版写成
    #    `"'X'" in fv or 'X' in fv` —— 后面那半段只是个前缀，
    #    把锚点改成 `X + 'X'`（即"探针名被改坏了"）时它照样命中 ⇒ **假绿**。
    #    反向套件 AF 项当场把这条抓出来（这正是反向验证的价值）。
    check('★★ verify_features.py 加了本轮的探针（真机上"看不到功能"= 忘了重编某个包）',
          "'本地模型：设置页组标题'" in fv)
    check('★ 探针挑的是**本轮才有的独有串**（不是 models 这种到处都有的词）',
          "'导入 .gguf 文件'" in fv and "'ModelFile'" in fv)
else:
    check('verify_features.py 存在', False)

# ★ 正式版工程是 make_variant.py 生成的：换包名之后这两份新类也必须跟着走
if os.path.isdir(REL_ROOT):
    _mf = os.path.join(REL_ROOT, 'src', 'com', 'doropet', 'release', 'ModelFile.java')
    check('★★ 正式版工程里也有 ModelFile（换包名不会漏 —— 它们是 .java，跟着 make_variant 走）',
          os.path.isfile(_mf))
else:
    print('  （%s 不存在，正式版端到端检查跳过）' % REL_ROOT)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（本地模型导入层：GGUF 硬门槛 / 流式后台拷贝 / 可取消 / 可删可切 / 进包）')
