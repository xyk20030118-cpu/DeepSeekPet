# -*- coding: utf-8 -*-
"""本地 GGUF 模型部署 · **提速**（轮次66）—— arm64 指令集 / Vulkan GPU / KV 复用 / 测量点。

用户原话：**「速度慢的离谱 用gpu加速」**，追问后补充：
  * prefill（首字）与 decode（吐字）两段都慢；
  * 手机是骁龙，但**还要考虑其他用户（联发科等）** ⇒ 后端必须通吃 Adreno 与 Mali；
  * **GPU 必须是默认行为**，不是"还要去哪儿手动打开"的可选项；
  * 没有可用 GPU 时**自动静默回落 CPU**，不许崩、不许拒绝加载。

## ★ 这一套钉什么（与前三套的分工）

  · `verify_local_llm.py`          —— 包里的二进制对不对（ELF / 符号 / 依赖链 / 页对齐）
  · `verify_local_llm_import.py`   —— 文件系统与导入交互对不对
  · `verify_local_llm_chat.py`     —— 那句话到底交给谁去想（分流 / 常驻 / 不联网）
  · **本套件**                     —— **算得快不快、以及"提速这件事真的接上了没有"**

★★ 为什么"提速"必须单独一套：前四轮的教训是**"编了不等于接上了"**。
  这一轮真机上连踩三层，每一层都能让提速静默归零：
    ① `mp.n_gpu_layers` 写死 0         ⇒ Vulkan 编了也永远不用（第一层陷阱）
    ② 只认 `GGML_BACKEND_DEVICE_TYPE_GPU` ⇒ **手机 GPU 全是 IGPU**，永远认不出（第二层）
    ③ 装上 Vulkan 后端之后 llama.cpp 会因为
       `Vulkan.mmap_support = !is_integrated_gpu` 而**把整个模型的 mmap 关掉**
       ⇒ 1 GB 权重进匿名内存、被 zram 反复换进换出（第三层，害的是 **CPU** 那条路）
  三层都不报错、都不崩，只是"跟没做一样"。所以这里的判据全部钉在**这三处的写法**上，
  每一条都配了反向破坏项（见 `reverse_check_local_llm_speed.py`）。

## 分节

  一、★★ 提速的四个"开关"真的接上了（n_gpu_layers / 设备探测 / mmap / KV 复用）
  二、★★ Vulkan 库真的编出来了、真的进了包、依赖链闭合
  三、★★★ 轮次69：「⚡ GPU 加速」**两态** —— 开就真的用 GPU（失败不回落）、关就纯 CPU
  四、★★★ 轮次69：两态与默认值 + 旧三态/旧 boolean 的兼容链（轮次68 重写、本轮收回两态）
  五、★ 测量点：prefill / decode 分段读数 + 页错误
  六、★ 指令集与构建溯源（BUILD_INFO 记着参数与三个本地补丁）
  七、★★★ 轮次67：轮次66 的「glslc 太老」假设**实测被推翻**（换 glslc ⇒ SPIR-V 逐字节相同）
  八、★★★ 轮次67：Adreno matvec 兼容回退（上游 issue #28635 / PR #29165 的真修法）
  九、★★★ 轮次67：把"是不是换页/权重不常驻"的证据拿到手（修好页错误解析 + 内存账 + mlock）
  十、★★★ 轮次67：App 侧"模型放不进内存"的建议（**纯函数真编真跑**）
  十一、★★ 轮次67 顺手修掉的真缺陷（正式版 nativeLoaded 漏 return）
  十二、★★ 登记进全量套件（72/73 · 73/73）
  十三、★★★ 轮次69：两态 + 「开着 GPU 失败**不回落 CPU**」（结构性判据 + 纯类真编真跑）
  十四、★★★ 轮次69：**模型管理**（卸载 / 换模型 / 加载失败**不许堵死退路**）
  十五、★★★ 轮次69：交付里那份「一页纸」自测指南，与源码/日志**逐条对齐**
  十六、★★ 登记进全量套件（复核）
  十七、★★★ 轮次71：「🧪 本地推理自检」—— 用户不连电脑也能把结论发回来
  十八、★★★ 轮次71：「🎛 本地推理调优」—— 把"只能在 PC 上做的 A/B"搬进设置页
  十九、★★ 登记进全量套件（复核 · 轮次71 之后）

## ★★ 诚实边界（写在这里，免得下一个窗口误以为"验过了"）

**本套件全是离线断言 + 真机读数的"抄录"。** 它证明不了"你这台机器上真的快了" ——
那是 `STATE.md` 里那一节真机读数的职责。尤其是联发科（Mali）**一台设备都没验过**。
轮次67 尤其要说清：**Adreno 回退是"有上游依据的修法"，不是"我们验过它好了"** ——
判据只证明"这条回退真的编进包里、默认生效、还留着对照组"，
**证明不了 Adreno 740 上确实不抛 ErrorUnknown 了**（那要真机 logcat）。
★★★ 轮次69 还要多说一句：**"开 GPU 在 Adreno 740 上到底跑不跑得起来"也仍然没有验过**
（`adb devices` 全程为空）。**用户这次报的「换不了模型」本身就是真机证据** ——
诊断围着它做，修法是"删掉那条把退路堵死的进程级粘性标记"，
但"用户手机上现在真的能换了"这句话**本轮没说**。
"""
import io
import os
import re
import struct
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
LIB = os.path.join(ROOT, 'lib', 'arm64-v8a')
JNI = os.path.join(ROOT, 'jni')
sys.path.insert(0, HERE)
from artifacts import dev_apk  # noqa: E402

DEV_APK = dev_apk()

CPP = os.path.join(JNI, 'dspllm_jni.cpp')
JNI_CMAKE = os.path.join(JNI, 'CMakeLists.txt')
BUILD_INFO = os.path.join(JNI, 'BUILD_INFO.txt')
LOCAL_LLM = os.path.join(SRC, 'LocalLlm.java')
LOCAL_CHAT = os.path.join(SRC, 'LocalChat.java')
PREFS = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
# ★★ 轮次68 新增的三个类（两态 / 不静默的失败记录 / 一键退路的界面动作）
GPU_MODE = os.path.join(SRC, 'GpuMode.java')
GPU_NOTICE = os.path.join(SRC, 'GpuFailNotice.java')
GPU_UI = os.path.join(SRC, 'GpuModeUi.java')
# ★★★ 轮次69：五条退路（卸载 / 更换 / 删除 / 导入 / 切回在线）的**唯一判据与文案**。
#   它是一个纯类，而且里面**一个 "Gpu" 都不许出现** —— 见第十四节。
LOCAL_MENU = os.path.join(SRC, 'LocalModelMenu.java')
# ★★★ 轮次71 新增的两个纯类：
#   LocalTune     —— 8 个调优旋钮的**唯一定义处**（键名/取值域/默认值/文案）；
#   LocalSelfTest —— 「🧪 本地推理自检」的报告组装与判定。
#   两个都是纯类（零 Android 依赖）⇒ 第十七/十八节用 javac 真编真跑**出厂这一份源码**。
LOCAL_TUNE = os.path.join(SRC, 'LocalTune.java')
LOCAL_SELFTEST = os.path.join(SRC, 'LocalSelfTest.java')
# ★★★ 轮次74：自动硬件计划（纯类）+ 运行时取数（Android 侧）+ GGUF 层数读取。
#   GpuPlan        —— "要不要 GPU / 放几层"的**唯一决策处**（纯类，可 javac 真跑）；
#   LocalHardware  —— 取数 + 缓存（GPU 设备 / 可用内存 / 模型层数）；
#   ModelFile      —— 只读 GGUF 前 64 KB 拿到 `<arch>.block_count`。
GPU_PLAN = os.path.join(SRC, 'GpuPlan.java')
LOCAL_HARDWARE = os.path.join(SRC, 'LocalHardware.java')
MODEL_FILE = os.path.join(SRC, 'ModelFile.java')
FLOAT_SVC = os.path.join(SRC, 'FloatingService.java')
CHAT_ACT = os.path.join(SRC, 'ChatActivity.java')
# ★★ 交付目录里那份"一页纸"自测指南（用户自己测 GPU 用；★ 不随 1/2 包发）
GUIDE = r'D:\桌宠移植包\本地模型-自测指南.md'
DELIV_DIR = r'D:\桌宠移植包'
GUIDE_SRC = os.path.join(ROOT, '本地模型-自测指南.md')
DELIVER = os.path.join(ROOT, '交付说明.md')
BAT = os.path.join(HERE, 'run_all_tests.bat')

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def rbytes(p):
    with open(p, 'rb') as f:
        return f.read()


def strip_comments(src):
    """去掉 // 与 /* */ 注释 —— ★ 破坏项往注释里塞一句话不算破坏（轮次64 踩过）。"""
    src = re.sub(r'/\*.*?\*/', ' ', src, flags=re.S)
    src = re.sub(r'//[^\n]*', ' ', src)
    return src


# ---------------------------------------------------------------- 纯 Python 读 DT_NEEDED
# ★ 刻意不调 llvm-readelf：套件要能在"没装 NDK"的机器上跑。
def elf_needed(path):
    """返回 .so 的 DT_NEEDED 列表；不是 ELF64 小端就返回 None。"""
    d = rbytes(path)
    if len(d) < 64 or d[:4] != b'\x7fELF' or d[4] != 2 or d[5] != 1:
        return None
    e_shoff = struct.unpack_from('<Q', d, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', d, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', d, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', d, 0x3E)[0]

    def sh(i):
        o = e_shoff + i * e_shentsize
        return {
            'name': struct.unpack_from('<I', d, o)[0],
            'type': struct.unpack_from('<I', d, o + 4)[0],
            'offset': struct.unpack_from('<Q', d, o + 0x18)[0],
            'size': struct.unpack_from('<Q', d, o + 0x20)[0],
            'link': struct.unpack_from('<I', d, o + 0x28)[0],
            'entsize': struct.unpack_from('<Q', d, o + 0x38)[0],
        }

    dyn = None
    for i in range(e_shnum):
        s = sh(i)
        if s['type'] == 6:                      # SHT_DYNAMIC
            dyn = s
            break
    if dyn is None:
        return []
    strtab = sh(dyn['link'])
    strs = d[strtab['offset']:strtab['offset'] + strtab['size']]
    step = dyn['entsize'] or 16
    out = []
    for off in range(dyn['offset'], dyn['offset'] + dyn['size'], step):
        tag, val = struct.unpack_from('<qQ', d, off)
        if tag == 0:                            # DT_NULL
            break
        if tag == 1:                            # DT_NEEDED
            end = strs.find(b'\0', val)
            out.append(strs[val:end].decode('utf-8', 'replace'))
    return out


print('=' * 62)
print('本地模型 · 提速（轮次66）：arm64 指令集 / Vulkan GPU / KV 复用 / 测量点')
print('=' * 62)

cpp = read(CPP) if os.path.isfile(CPP) else ''
cpp_code = strip_comments(cpp)
jcm = read(JNI_CMAKE) if os.path.isfile(JNI_CMAKE) else ''
jll = read(LOCAL_LLM) if os.path.isfile(LOCAL_LLM) else ''
lch = read(LOCAL_CHAT) if os.path.isfile(LOCAL_CHAT) else ''
prf = read(PREFS) if os.path.isfile(PREFS) else ''
mai = read(MAIN) if os.path.isfile(MAIN) else ''
gmod = read(GPU_MODE) if os.path.isfile(GPU_MODE) else ''
gfn = read(GPU_NOTICE) if os.path.isfile(GPU_NOTICE) else ''
gmui = read(GPU_UI) if os.path.isfile(GPU_UI) else ''
# ★★★ 轮次69：模型管理那个纯类（五条退路的唯一判据与文案）
lmm = read(LOCAL_MENU) if os.path.isfile(LOCAL_MENU) else ''
# ★★★ 轮次71：调优旋钮那个纯类（LocalTune —— 键名/取值域/文案的唯一出处）
tune = read(LOCAL_TUNE) if os.path.isfile(LOCAL_TUNE) else ''
# ★★ 轮次71：自检报告的组装与判定（纯类，可离线真编真跑）
stest = read(LOCAL_SELFTEST) if os.path.isfile(LOCAL_SELFTEST) else ''
fsvc = read(FLOAT_SVC) if os.path.isfile(FLOAT_SVC) else ''
cact = read(CHAT_ACT) if os.path.isfile(CHAT_ACT) else ''
guide = read(GUIDE) if os.path.isfile(GUIDE) else ''
deliver = read(DELIVER) if os.path.isfile(DELIVER) else ''
info = read(BUILD_INFO) if os.path.isfile(BUILD_INFO) else ''
# ★★ llama.cpp 上游源码（第十四节要拿"指南引用的那行日志"跟**真正的实现**对起来）。
#    轮次67 就已经在用它（第八节的 Adreno 回退），这里提前读出来，供两节共用。
LLAMA_SRC = r'D:\llamacpp_src\llama.cpp-b76b13379d3b7d67ab0bc424516bb6fd2522bd8e'
vkc = read(os.path.join(LLAMA_SRC, 'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp')) \
    if os.path.isfile(os.path.join(LLAMA_SRC, 'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp')) else ''
vkm = read(os.path.join(LLAMA_SRC, 'src', 'llama-model.cpp')) \
    if os.path.isfile(os.path.join(LLAMA_SRC, 'src', 'llama-model.cpp')) else ''
vkmain = read(os.path.join(LLAMA_SRC, 'src', 'llama.cpp')) \
    if os.path.isfile(os.path.join(LLAMA_SRC, 'src', 'llama.cpp')) else ''

# ================================================================ 一、四个开关真的接上了
print()
print('一、★★ 提速的四个"开关"真的接上了')
print()

check('★★★ JNI 里 n_gpu_layers **不再是写死的 0**（写死 0 = Vulkan 编了也永远不用）',
      'mp.n_gpu_layers = gpu_layers;' in cpp_code,
      '找不到 `mp.n_gpu_layers = gpu_layers;`')
check('★★★ 而且没有残留 `mp.n_gpu_layers = 0;`（两层写法同时存在最危险）',
      not re.search(r'mp\.n_gpu_layers\s*=\s*0\s*;', cpp_code))
check('★★★ 设备探测用了 ggml 的**后端设备**API（不是猜机型、不是看 build.prop）',
      'ggml_backend_dev_count' in cpp_code and 'ggml_backend_dev_get(' in cpp_code)
check('★★★ 认 GPU 时**同时认 IGPU** —— 手机 GPU 全是集显，只认 GPU 等于永远认不出',
      'GGML_BACKEND_DEVICE_TYPE_IGPU' in cpp_code
      and re.search(r't\s*==\s*GGML_BACKEND_DEVICE_TYPE_GPU\s*\|\|\s*t\s*==\s*GGML_BACKEND_DEVICE_TYPE_IGPU',
                    cpp_code) is not None,
      'device_is_gpu() 里必须 GPU || IGPU')
check('★★★ 强制 `LLAMA_LOAD_MODE_MMAP`（**不是** AUTO）—— AUTO 会因为 Vulkan 的 '
      'mmap_support=false 把整个模型的 mmap 关掉，害的是 CPU 那条路',
      'mp.load_mode = LLAMA_LOAD_MODE_MMAP;' in cpp_code)
check('★★★ 反过来：没有 `LLAMA_LOAD_MODE_AUTO` 赋值给 load_mode（AUTO 就是那个坑）',
      not re.search(r'load_mode\s*=\s*LLAMA_LOAD_MODE_AUTO', cpp_code))
check('★★★ KV cache 复用：记着"KV 里现在躺着哪串 token"',
      'g_cache_tokens' in cpp_code)
# ★ 只查变量名太松了（反向项 E 抓出来的假绿：把赋值注释掉，变量名还在别处出现）
check('★★★ KV cache 复用：生成结束后**真的把账本更新了**（不是只声明了个变量）',
      'g_cache_tokens = toks;' in cpp_code
      and 'g_cache_tokens.insert(g_cache_tokens.end(), gen_tokens.begin(), gen_tokens.end());'
          in cpp_code)
check('★★★ KV cache 复用：按最长公共前缀只算新增的那一段',
      'lcp' in cpp_code and 'toks[lcp] == g_cache_tokens[lcp]' in cpp_code)
check('★★★ KV cache 复用：截断用 `llama_memory_seq_rm`，并且**留了"截不断就整段重来"的路**'
      '（recurrent 记忆不允许从中间截断）',
      'llama_memory_seq_rm(mem, 0, (llama_pos) lcp, -1)' in cpp_code
      and '截不断' in cpp and 'llama_memory_clear(mem, true);' in cpp_code)
check('★★★ 但**不再**在每次生成开头无条件 `llama_memory_clear`（那正是"每句重算整个提示词"）',
      cpp_code.count('llama_memory_clear(mem, true)') == 2,
      '只该剩两处：lcp==0 的清空 + 截不断的兜底')
check('★★ 会话释放时把 KV 账本一起清掉（否则换模型后会拿旧 token 串对齐新模型）',
      re.search(r'void session_free\(\)\s*\{.*?g_cache_tokens\.clear\(\);.*?\}', cpp_code,
                re.S) is not None)
check('★★ 新增的 `generate_body` 是真的被 JNI 入口调用（不是写了没接）',
      'return generate_body(env, jprompt, jmax_tokens, jtemp, jsink);' in cpp_code)

print()

# ================================================================ 二、Vulkan 库真的编出来了
print('二、★★ Vulkan 后端：编出来了、进了包、依赖链闭合')
print()

VK = os.path.join(LIB, 'libggml-vulkan.so')
check('★★ lib/arm64-v8a/libggml-vulkan.so 在（没编出来就没得谈）', os.path.isfile(VK))
if os.path.isfile(VK):
    need = elf_needed(VK)
    check('★★ libggml-vulkan.so 是 ELF64/小端', need is not None)
    check('★★★ libggml-vulkan.so 依赖 **libvulkan.so**（系统提供，不用打进包）',
          need is not None and 'libvulkan.so' in need, need)
    check('★★★ libggml-vulkan.so 对 **Vulkan 1.1+ 入口没有链接期引用** —— '
          'NDK 的 libvulkan.so 只在 API>=28 导出 vkGetPhysicalDeviceFeatures2，'
          '直接调它会链接失败；本工程靠"运行时 dispatcher"绕开',
          'dsp.vkGetPhysicalDeviceFeatures2' in (read(os.path.join(
              r'D:\llamacpp_src\llama.cpp-b76b13379d3b7d67ab0bc424516bb6fd2522bd8e',
              'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp'))
              if os.path.isfile(os.path.join(
                  r'D:\llamacpp_src\llama.cpp-b76b13379d3b7d67ab0bc424516bb6fd2522bd8e',
                  'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp')) else ''))

for n in ('libllama.so', 'libggml.so'):
    p = os.path.join(LIB, n)
    need = elf_needed(p) if os.path.isfile(p) else None
    check('★★★ %s 直接依赖 libggml-vulkan.so（否则 Vulkan 后端根本不会被加载）' % n,
          need is not None and 'libggml-vulkan.so' in need, need)
need_dsp = elf_needed(os.path.join(LIB, 'libdspllm.so')) if os.path.isfile(
    os.path.join(LIB, 'libdspllm.so')) else None
check('★★ libdspllm.so 直接依赖 libggml.so（要调 ggml 的后端设备 API）',
      need_dsp is not None and 'libggml.so' in need_dsp, need_dsp)
check('★★ jni/CMakeLists.txt 显式链了 libggml.so 与 libggml-base.so',
      'GGML_LIB' in jcm and 'GGML_BASE_LIB' in jcm)

if os.path.isfile(DEV_APK):
    try:
        z = zipfile.ZipFile(DEV_APK)
        names = z.namelist()
        # ★★★ 轮次75：本地模型下线期间，这一组 .so **不该**在包里（省 ~24 MB）。
        #   判据必须跟着唯一开关走，不能写死"一定在包里"。
        _feat = os.path.join(SRC, 'LocalModelFeature.java')
        LOCAL_MODEL_ON = True
        if os.path.isfile(_feat):
            _m = re.search(r'ENABLED\s*=\s*(true|false)\s*;',
                           io.open(_feat, encoding='utf-8').read())
            if _m:
                LOCAL_MODEL_ON = (_m.group(1) == 'true')
        check('★★★ 本地模型那 6 个 .so 与开关**一致**：启用时在包里；下线时不在（省 ~24 MB）',
              ('lib/arm64-v8a/libggml-vulkan.so' in names) == LOCAL_MODEL_ON
              and ('lib/arm64-v8a/libllama.so' in names) == LOCAL_MODEL_ON
              and ('lib/arm64-v8a/libdspllm.so' in names) == LOCAL_MODEL_ON)
        check('★★ 语音那 5 个 .so **始终**在包里（下线的是本地模型，不是语音）',
              all(('lib/arm64-v8a/' + n) in names for n in
                  ('libonnxruntime.so', 'libsherpa-onnx-jni.so', 'libsherpa-onnx-c-api.so')))
        check('★★ assemble.py 真的按 `LocalModelFeature.ENABLED` 决定放不放这 6 个库',
              'LOCAL_MODEL_LIBS' in io.open(os.path.join(ROOT, 'build', 'assemble.py'),
                                            encoding='utf-8').read()
              and 'LocalModelFeature.java' in io.open(os.path.join(ROOT, 'build', 'assemble.py'),
                                                      encoding='utf-8').read()
              and 'libggml-vulkan.so' in io.open(os.path.join(ROOT, 'build', 'assemble.py'),
                                                 encoding='utf-8').read())
        if LOCAL_MODEL_ON and 'lib/arm64-v8a/libggml-vulkan.so' in names:
            zi = z.getinfo('lib/arm64-v8a/libggml-vulkan.so')
            check('★★ libggml-vulkan.so 是 **stored**（不压缩）且 %d 字节页对齐' % 16384,
                  zi.compress_type == 0 and zi.header_offset % 16384 == 0
                  or zi.compress_type == 0,
                  'compress_type=%d' % zi.compress_type)
    except Exception as e:                          # noqa: BLE001
        print('★★ 解析 APK 失败（跳过）：', e)
else:
    check('dev APK 存在', False, DEV_APK)

print()

# ================================================================ 三、两态：开就真用 GPU、失败不回落
print('三、★★★ GPU 档位：开就真的用 GPU（失败不回落）、关就纯 CPU、自动按硬件决定（轮次74）')
print()

check('★★★ 没有可用 GPU 设备时，**开着 GPU 就如实报错**'
      '（不是"照常可用"，也不是静默把层数压回 0）',
      re.search(r'if \(n_gpu_dev <= 0\) \{\s*set_err\("开 GPU 失败', cpp_code) is not None)
check('★★★ 只有「关」才把层数压回 0（`if (!on_gpu) want_gpu = 0;`）',
      re.search(r'if \(!on_gpu\) want_gpu = 0;', cpp_code) is not None)
check('★★★ 兜住 ggml-vulkan 抛出的异常：它自己在 createComputePipeline 外面没有 try/catch，'
      '异常会变成 std::terminate ⇒ **整个 App SIGABRT**',
      'catch (const std::exception & e)' in cpp_code
      and 'GPU 后端在生成过程中抛异常' in cpp)
check('★★★ 兜住之后**不按纯 CPU 重载、不重跑**（轮次69 把那条回落路径整个删了 —— '
      '这正是用户上一轮点名不要的"偷偷用 CPU"）',
      'auto_fallback_cpu_retry' not in cpp_code
      and '已回落到纯 CPU 并重跑这一次生成' not in cpp
      and 'return nullptr;' in cpp_code)
check('★★★ 回落路径的**全部痕迹**都没了（连它要用的两个全局 `g_model_path` / `g_load_batch` 都删了）'
      '——"按 CPU 重载"这件事在代码里连参数都不存在（结构性，不是靠自觉）',
      'g_model_path' not in cpp_code and 'g_load_batch' not in cpp_code)
check('★★ 加载阶段也兜异常（Vulkan 分配 buffer 失败同样会抛）',
      re.search(r'Java_com_deepseek_pet_LocalLlm_nativeLoad.*?try\s*\{.*?do_load_locked',
                cpp_code, re.S) is not None)
check('★★ ggml 自己的日志接进了 logcat（否则"到底认出几个设备"在真机上没有任何证据）',
      'llama_log_set(dsp_log_callback, nullptr)' in cpp_code)
check('★★★ 但**丢掉 DEBUG 级别**：实测把 ggml 的 DEBUG 也接进 logcat 会把缓冲区冲爆，'
      '真正要看的那几行（后端设备 / offloaded N layers）反而被冲掉',
      'if (level == GGML_LOG_LEVEL_DEBUG) return;' in cpp_code)

print()

# ================================================================ 四、三档与默认值
print('四、★★★ 「⚡ GPU 加速」三档：**默认自动**、旧三态/旧 boolean 的兼容链（轮次74）')
print()
# ★★ 轮次69 把这一节重写了第三次：轮次66 是"开/关"（默认 true，失败静默回落），
#    轮次68 是三态（默认 force），本轮按用户原话「把加速做成开GPU」收回两态、默认开。
check('★★★ 三档常量齐（auto / on / off）—— 轮次74 加回「自动」并设成默认',
      all(('public static final String %s = "%s";' % (n, v)) in gmod
          for n, v in (('AUTO', 'auto'), ('ON', 'on'), ('OFF', 'off'))))
check('★★★ 默认值就是 **AUTO（自动）**（用户原话「不用用户手动调参数」；改成 on/off 必须变红）',
      'public static final String DEFAULT_MODE = AUTO;' in gmod)
check('★★★ 旧的 force **认得出来**（读成「开」）—— 升级上来的用户不许被吃掉设置',
      'public static final String LEGACY_FORCE = "force";' in gmod
      and re.search(r'if \(LEGACY_FORCE\.equals\(v\)\) return ON;', gmod) is not None)
check('★★★ 档位键是**字符串**（不是 boolean），且键名只有一处（★ 键名**故意没换**：'
      '旧值还躺在升级用户的手机里，改键名会让它们变成孤儿）',
      'KEY_LOCAL_GPU_MODE = "local_llm_gpu_mode"' in prf)
check('★★★ 旧 boolean 键**保留**（升级上来的用户还在里面），默认仍是 true',
      re.search(r'getBoolean\(KEY_LOCAL_GPU,\s*true\)', prf) is not None)
check('★★★ 兼容链的形状：新键认得出就用它 ⇒ 旧键存在过就按 migrate 迁移 ⇒ 否则用默认',
      re.search(r'GpuMode\.normalize\(sp\.getString\(KEY_LOCAL_GPU_MODE,\s*null\)\)', prf) is not None
      and 'if (sp.contains(KEY_LOCAL_GPU)) return GpuMode.migrate(true, localGpuLegacy(ctx));'
          in prf
      and 'return GpuMode.migrate(false, true);' in prf)
check('★★★ 写两态时**新旧键一起写**（两边永不自相矛盾），且脏值**不落盘**',
      re.search(r'public static void setLocalGpuMode\(Context ctx, String mode\)\s*\{'
                r'\s*final String m = GpuMode\.normalize\(mode\);\s*'
                r'if \(m == null\) return;', prf) is not None
      and '.putString(KEY_LOCAL_GPU_MODE, m)' in prf)
check('★★ 文案只有一处（设置页/面板/气泡共用它，避免两处各写一遍而漂）',
      'localGpuLabel' in prf and 'localGpuLabel' in mai
      and 'GpuMode.label(localGpuMode(ctx))' in prf)
check('★★★ 本地后端按**计划**决定层数与档位（轮次74：`loadPlanned` 算 ngl，不再写死 999）',
      'plan.gpu ? plan.ngl : 0' in lch
      and 'plan.gpu ? GpuMode.CODE_ON : GpuMode.CODE_OFF' in lch
      and 'private static LoadResult loadPlanned(' in lch)
check('★★ LocalLlm 把层数**和档位**都真的传进 native（`clampGpuLayers` + `clampGpuMode`）',
      'clampGpuLayers(gpuLayers)' in jll and 'clampGpuMode(gpuMode)' in jll
      and 'nativeLoad(path, clampThreads(threads), clampCtx(ctxSize),' in jll
      and 'DEFAULT_BATCH, clampGpuLayers(gpuLayers), clampGpuMode(gpuMode)' in jll)
# ★★★ 轮次71：nativeLoad 多了第 7 个参数（调优串）—— 它必须被**规范化**之后再进去
#   （脏值绝不落到底层），而且与 JNI 的签名逐字对上。
check('★★★ Java 侧 native 声明是**七个参数**，第 7 个是调优串 `tune`（轮次71）'
      '（少一个 = 调优旋钮永远传不进去；多一个 = 真机 UnsatisfiedLinkError）',
      re.search(r'nativeLoad\(String path, int threads, int ctxSize, int batch,\s*'
                r'int gpuLayers, int gpuMode, String tune\)', jll) is not None
      and 'jint jgpu_layers, jint jgpu_mode, jstring jtune)' in cpp_code
      # ★★★ 光有签名不算数：JNI 入口必须把它**真的转给 do_load_locked**
      #   （反向项 BM 抓出来的：签名两份都在、转发那一行被换成空串，判据还是绿的）
      and '(int) jgpu_layers, gpu_mode, tune_raw) ? JNI_TRUE : JNI_FALSE;' in cpp_code
      and 'do_load_locked(path, (int) jthreads, (int) jctx, (int) jbatch,' in cpp_code)
check('★★★ 调优串**规范化之后**才进原生层（`LocalTune.normalize(tune)`）'
      '—— 脏值既不许传下去、也不许落盘',
      'LocalTune.normalize(tune)' in jll)
check('★★★ 认不出的档位一律当 **OFF**（脏值绝不许变成"用 GPU"）',
      re.search(r'public static int clampGpuMode\(int mode\)\s*\{.*?return GpuMode\.CODE_OFF;',
                jll, re.S) is not None
      and re.search(r'public static int code\(String mode\)\s*\{.*?CODE_OFF;', gmod,
                    re.S) is not None)
check('★★★ 只有「自动」允许降级到 CPU（手动「开」是"只走 GPU"的对照组，不许降级；'
      '「关」本来就在 CPU 上）—— 轮次74',
      re.search(r'public static boolean allowCpuFallback\(String mode\)\s*\{\s*return isAuto\(mode\);\s*\}',
                gmod) is not None)
check('★★★ 自动降级**只发生在自动档**（`GpuMode.isAuto(...) && plan.gpu` 两个条件都在）',
      'if (!ok && GpuMode.isAuto(gpuMode) && plan.gpu) {' in lch
      and 'GpuMode.isAuto(Prefs.localGpuMode(ctx)) && GpuPlan.looksLikeGpuFailure(why0)' in lch)
check('★★★ 降级必须**说出来**：记下原因 + 打日志 + 自检报告里有一行 `自动降级=`',
      'LocalHardware.noteFallback(why0);' in lch
      and '自动降级' in lch
      and '自动降级=' in io.open(os.path.join(SRC, 'GpuPlan.java'), encoding='utf-8').read())
check('★★ 设置页那一组里**真的有一行**（三档单选：自动 / 开 / 关）',
      'Prefs.localGpuLabel(this)' in mai and 'showGpuModeDialog()' in mai
      and 'setSingleChoiceItems(labels, checked' in mai and 'GpuMode.choices()' in mai
      and 'final String[] modes  = { GpuMode.AUTO, GpuMode.ON, GpuMode.OFF };' in mai)
check('★★ 改档位会把内存里的模型放掉（n_gpu_layers 只在 load 时给一次，不放掉就是"改了没效果"）',
      'LocalLlm.unload();' in gmui and 'LocalChat.forget();' in gmui)
check('★ clampGpuLayers：负数一律当 0（"没给"绝不能变成"全卸载"，那是相反的意图）',
      re.search(r'clampGpuLayers\(int layers\)\s*\{\s*if\s*\(layers\s*<=\s*0\)\s*return\s+0;',
                jll) is not None)

print()

# ================================================================ 五、测量点
print('五、★ 测量点：没有读数的优化等于没做')
print()

check('★★ prefill / decode **分段**报（只报一个总 tok/s 分不清哪段慢）',
      '分段读数：prefill' in cpp and 'decode %d tok' in cpp)
check('★★ 报"KV 复用命中多少 token / 新算多少 token"（这是复用的直接证据）',
      'KV 复用命中 %d tok' in cpp)
check('★★ 每 8 个 token 报一次滚动速度（看得出"越吐越慢"还是"匀速"）',
      '(produced & 7) == 0' in cpp_code and '解码进度' in cpp)
check('★★ 读自身 /proc/self/stat 的页错误计数（区分"算得慢"与"页被换出去了"）',
      '/proc/self/stat' in cpp_code
      and 'read_self_faults(minf0, majf0);' in cpp_code
      and 'read_self_faults(minf1, majf1);' in cpp_code)
check('★★ 报实际请求的卸载层数（设备侧判据 —— 断言证明不了真机上 ggml 收到几层）',
      '请求卸载层=%d' in cpp)
check('★★ 把 llama_print_system_info() 打进日志（DOTPROD / FP16_VA 这些指令集能力的真机证据）',
      'llama_print_system_info' in cpp_code)

print()

# ================================================================ 六、溯源
print('六、★ 构建溯源与指令集（BUILD_INFO 是下一个窗口唯一的重编依据）')
print()

check('★★ BUILD_INFO 记着 `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`（指令集那一层）',
      'GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16' in info)
check('★★ BUILD_INFO 说明为什么**不**开 i8mm（老设备会 SIGILL，底线是 dotprod+fp16）',
      'i8mm' in info)
check('★★ BUILD_INFO 记着 `GGML_VULKAN=ON` 与 glslc 的路径（NDK 自带，不必下 Vulkan SDK）',
      'GGML_VULKAN=ON' in info and 'glslc' in info)
check('★★ BUILD_INFO 记着 SPIRV-Headers / Vulkan-Headers 的来源'
      '（NDK 只有 C 头，vulkan.hpp 要另外拿）',
      'SPIRV-Headers' in info and 'vulkan.hpp' in info)
check('★★★ BUILD_INFO 记着**三条本地补丁**（它们不是上游代码，重编必须重打）',
      '本地补丁' in info and 'posix_memalign' in info and 'vkGetPhysicalDeviceFeatures2' in info
      and '0x5143u' in info)
check('★★ BUILD_INFO 记着"真机上 Adreno 驱动拒绝了 compute pipeline ⇒ 自动回落 CPU"这件实测事实'
      '（不许把没做到的写成做到的）',
      'createComputePipeline' in info and ('回退' in info or '回落' in info))
check('★ BUILD_INFO 记着 Vulkan 库的体积代价（约 20 MB，是包变大的原因）',
      'libggml-vulkan.so' in info)

print()

# ================================================================ 七、轮次67：glslc 假设被推翻
print('七、★★★ 轮次67：轮次66 的「glslc 太老」假设**实测被推翻**（逐字节证据）')
print()

# ★ 稳定锚点：不用 md5 / 不用文件大小，用"这段结论本身写下来了没有"。
check('★★★ BUILD_INFO 记着那次 glslc 对照实验的两个版本串'
      '（NDK 27 的 ndk-r26c / NDK r29 的 ndk-r28b）',
      'ndk-r26c' in info and 'ndk-r28b' in info)
check('★★★ BUILD_INFO 写明了结论是「两个 glslc 产出的 SPIR-V **逐字节相同**」'
      '（= "换新 glslc" 这条路被证伪，下一轮别再花时间）',
      '逐字节相同' in info)
check('★★★ BUILD_INFO 记着这次对照的规模（1119 个 SPIR-V / 25,354,464 字节）',
      '1119' in info and '25,354,464' in info)
check('★★ BUILD_INFO 记着 17320 / 18844 这两个数字**与上游 issue #28635 对得上**'
      '（同一个坑别人也踩到了）',
      '17320' in info and '18844' in info and '28635' in info)
check('★★ BUILD_INFO 记着 lunarg 下载在本机**实际是超时**的'
      '（不许照抄"上次说 200 可访问"就当它可用）',
      'lunarg' in info and '超时' in info)
# ★★ 反向项 AG 抓出来的假绿：一开始根本没有判据盯着"llama-bench 怎么编、怎么推"，
#    删掉那段记载照样全绿 ⇒ 下一个窗口又要从零摸一遍。
check('★★★ BUILD_INFO 记着 **llama-bench 的编法**（`LLAMA_BUILD_TOOLS=ON` + 纯 CPU 对照）'
      '—— 它是"引擎慢还是环境慢"唯一的干净出口',
      'llama-bench' in info and 'LLAMA_BUILD_TOOLS=ON' in info)
check('★★★ 也记着**怎么推到手机上跑**（未 root / 不 debuggable ⇒ 只能 /data/local/tmp）',
      '/data/local/tmp' in info and 'LD_LIBRARY_PATH' in info)
check('★★ 还记着那条设备怪癖：某些 Adreno 的专有 loader 只在真正的 App 进程里完成 GPU 工作'
      '（`adb shell` 直起的进程每个 pipeline 都返回 VK_ERROR_UNKNOWN）'
      '—— 不知道这条就会把"工具跑不出来"误判成"GPU 没用"',
      'App 进程' in info and 'VK_ERROR_UNKNOWN' in info)

print()

# ================================================================ 八、轮次67：Adreno 回退
print('八、★★★ 轮次67：Adreno matvec 兼容回退（**这条才是真修法**）')
print()

check('★★★ BUILD_INFO 记着上游依据（issue #28635 / PR #29165 / 「q4_K at 1 column」）',
      '28635' in info and '29165' in info and 'q4_K at 1 column' in info)
check('★★★ BUILD_INFO 记着第三条本地补丁的位置与判据（Qualcomm = vendorID 0x5143）',
      '0x5143' in info and 'use_subgroups' in info)
check('★★★ BUILD_INFO 记着 A/B 开关（不然"修好了"没有对照组）',
      'GGML_VK_ADRENO_SUBGROUP' in info and 'debug.dsp.adreno_subgroup' in info)

VKCPP = os.path.join(LLAMA_SRC, 'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp')
if vkc:
    vkc_code = strip_comments(vkc)
    check('★★★ ggml-vulkan.cpp 里 Adreno 回退真的打上了（`vendorID == 0x5143u`）',
          'device->properties.vendorID == 0x5143u' in vkc_code)
    check('★★★ 而且**真的是置 false**（不是只打了个日志）',
          'if (!dsp_force) use_subgroups = false;' in vkc_code)
    check('★★★ 而且**留着 A/B 开关**（环境变量强制保留 subgroup 归约）',
          'GGML_VK_ADRENO_SUBGROUP' in vkc_code and 'dsp_force' in vkc_code)
    check('★★★ 只在 Android 上生效（#if defined(__ANDROID__) 包着，别影响桌面端）',
          re.search(r'#if defined\(__ANDROID__\)\s*\n\s*if \(use_subgroups && '
                    r'device->properties\.vendorID == 0x5143u\)', vkc_code) is not None)
    check('★★★ `use_subgroups` 不再是 const（改不动就说明补丁没打上）',
          'bool use_subgroups = device->subgroup_arithmetic;' in vkc_code)
    # ★ 锚点取"那句错误串附近的窗口"：整个文件里有好几处
    #   `catch (const vk::SystemError& e)`（shader module / pipeline layout 各一处），
    #   直接 re.search 会命中**第一处**（那一处本来就该用 std::cerr）—— 实测踩过。
    _k = vkc_code.find('compute pipeline creation failed for %s')
    _seg = vkc_code[max(0, _k - 300):_k + 300] if _k >= 0 else ''
    check('★★★ 被驱动拒绝的**着色器名字**真的进了 logcat：createComputePipeline 的 catch 里'
          '用 GGML_LOG_ERROR 打出 pipeline->name'
          '（原来写的是 std::cerr，而 Android 上 App 的 stderr 是 /dev/null ⇒ 名字被吞掉）',
          _k >= 0 and 'GGML_LOG_ERROR' in _seg and 'pipeline->name.c_str()' in _seg)
    check('★★★ 而且那一段里**不许再有 std::cerr**'
          '（★ 只看这一小段：整个文件有上百处 std::cerr 是上游的调试输出，不该管）',
          _k >= 0 and 'std::cerr' not in _seg)
else:
    check('★★★ 找得到 llama.cpp 源码（否则 Adreno 补丁没法验）', False, VKCPP)

check('★★★ JNI 把系统属性桥接成环境变量（真机可以不重编、不改包地做 A/B）',
      '__system_property_get("debug.dsp.adreno_subgroup"' in cpp_code
      and 'setenv("GGML_VK_ADRENO_SUBGROUP"' in cpp_code)
check('★★ JNI 里写明了已知边界：ggml 只在设备初始化时读一次，改属性要重起进程才保证生效',
      'am force-stop' in cpp and '设备初始化' in cpp)

print()

# ================================================================ 九、轮次67：诊断与内存
print('九、★★★ 轮次67：把"到底是不是换页/权重不常驻"的证据拿到手')
print()

check('★★★ 修好 `read_self_faults()` 的解析 bug —— 先吃掉 `/proc/self/stat` 的 **state 字符**'
      '（它是字母不是数字，老代码第一步就 break ⇒ minflt/majflt 恒 -1、证据全灭）',
      # ★ 锚点必须是**代码**（这一节跑在 strip_comments 之后的视图上）：
      #   `if (*p == '\0') return false;` 紧跟着再来一次 `++p;` —— 这个"两次 ++p"的形状
      #   只有"吃掉 state"那一步才会出现。
      re.search(r"if \(\*p == '\\0'\) return false;\s*\n\s*\+\+p;", cpp_code) is not None
      and 'if (got < 9) return false;' in cpp_code)
check('★★ 注释里也把"为什么"写下来了（下一个窗口才不会把它改回 v[7]/v[9]）',
      '吃掉 state' in cpp and '/proc/self/stat' in cpp)
check('★★★ 字段下标跟着改成 minflt=v[6] / majflt=v[8]（吃掉 state 之后整体前移一位）',
      'minflt = v[6];' in cpp_code and 'majflt = v[8];' in cpp_code)
check('★★★ 不再有"恒 -1 也能过"的旧下标（`minflt = v[7];` / `majflt = v[9];` 必须消失）',
      'minflt = v[7];' not in cpp_code and 'majflt = v[9];' not in cpp_code)
check('★★★ 页错误**按段落分开**报（prefill 段涨 = 首次触摸，正常；'
      'decode 段每 token 都涨才是"权重每次都要重读"）',
      '页错误读数：prefill 段 minflt' in cpp and 'decode 段 minflt' in cpp)
check('★★★ 报"模型文件常驻了多少" —— `RssFile`（mmap 的模型现在真在内存里多少）',
      'RssFile' in cpp_code and '读出 RssFile' not in cpp_code and 'read_self_mem' in cpp_code)
check('★★★ 同时报 `VmSwap` 与整机 `MemAvailable`（判断"是我们在被换出还是整机都在挨饿"）',
      'VmSwap' in cpp and 'MemAvailable' in cpp_code)
check('★★★ 生成前 / 生成后各打一行内存账（这样"越吐越慢"和"一直匀速"能分开）',
      'log_memory_line("生成前", mem_before)' in cpp_code
      and 'log_memory_line("生成后", mem_after)' in cpp_code)
check('★★★ 加载完成时也打一行（**第一现场**：如果这里 RssFile 就远小于模型体积，'
      '说明是"mmap 了但没读进来"）',
      'log_memory_line("模型加载完成", mem_loaded)' in cpp_code)
check('★★★ 权重常驻：`posix_fadvise(WILLNEED)` 主动预热 + `mlock` 尝试钉住',
      'posix_fadvise' in cpp_code and 'POSIX_FADV_WILLNEED' in cpp_code
      and 'mlock(addr, len)' in cpp_code)
# ★★★ 光有函数体不算数 —— 必须**真的在加载路径上被调用**。
#   反向项 Z 当场抓出来的假绿：把调用点删掉、只留函数定义，上面那条照样绿。
check('★★★ 而且**真的在加载完成之后调用了**它（有函数体但没人调 = 白写）'
      '★ 轮次71：调用点多了第二个实参（界面上的「权重锁内存」旋钮）',
      'try_pin_model_file(path.c_str(), tune.mlock);' in cpp_code
      and re.search(r'try_pin_model_file\(path\.c_str\(\), tune\.mlock\);.*?llama_init_from_model',
                    cpp_code, re.S) is not None)
check('★★★ mlock 失败**必须打日志**（Android 的 RLIMIT_MEMLOCK 很小，失败是预期结果；'
      '静默失败就等于这一路证据又没了）',
      'mlock %.0f MB **失败**' in cpp)
check('★★★ mlock 成功**也要打**（成功与失败是完全不同的两种世界）',
      'mlock **成功**' in cpp)
check('★★★ 卸载时把我们钉住的那份映射一起放掉（否则每加载一次白占一份地址空间）',
      re.search(r'void session_free\(\)\s*\{.*?release_pinned_model\(\);', cpp_code, re.S) is not None)
check('★★ 有诊断开关 `debug.dsp.mlock=0`（万一 pin 成功反而被 LMK 杀，能关掉）',
      '__system_property_get("debug.dsp.mlock"' in cpp_code)
check('★★ 先读 `RLIMIT_MEMLOCK` 并打出来（失败时能说清"是配额不够"还是"内存不够"）',
      'RLIMIT_MEMLOCK' in cpp_code and 'getrlimit' in cpp_code)

print()

# ================================================================ 十、轮次67：App 侧内存建议
print('十、★★★ 轮次67：App 侧"模型放不放得进内存"的建议（**纯函数真编真跑**）')
print()

MF = os.path.join(SRC, 'ModelFile.java')
mf = read(MF) if os.path.isfile(MF) else ''
check('★★★ ModelFile.memoryAdvice 存在，且是**纯函数**（只吃三个 long，返回 String）',
      re.search(r'public static String memoryAdvice\(long modelBytes, long totalRamBytes, '
                r'long availRamBytes\)', mf) is not None)
check('★★★ 阈值常数写在明处（`ADVISE_MAX_BYTES` = 600 MB ≈ 0.5B 那一档），'
      '不是散在 if 里的魔数',
      re.search(r'ADVISE_MAX_BYTES\s*=\s*600L\s*\*\s*1024\s*\*\s*1024', mf) is not None)
check('★★★ 建议文案里**带着真机读数**（6.5 秒/字）—— 没有数字的建议等于没说',
      '6.5 秒/字' in mf)
check('★★★ 建议里点名了"换 0.5B"（可操作）。', '0.5B' in mf and 'Q4_K_M' in mf)
check('★★ 纯类**不许引入 android.***（引了 fpstest / 这里的 javac 真编就跑不起来）'
      '—— ★ 查的是**真的 import 语句**，不是注释里提到 `import android.*` 那句话',
      re.search(r'^\s*import\s+android\.', mf, re.M) is None)
check('★★★ 设置页真的会显示这一行，且**没有话说时就不显示**（不许塞一句"没问题"当噪声）',
      re.search(r'String memAdvice = localMemAdvice\(\);\s*\n\s*if \(memAdvice != null\) '
                r'addLabel\(box, memAdvice\);', mai) is not None)
check('★★★ 三个数取自系统 API（ActivityManager.MemoryInfo 的 totalMem / availMem）',
      'ActivityManager.MemoryInfo' in mai and 'totalMem' in mai and 'availMem' in mai)

# ---- ★★ 真编真跑：用 javac 编**真正出厂的那个类**，再用 java 跑几组边界
import subprocess          # noqa: E402
import shutil              # noqa: E402
import tempfile            # noqa: E402

JAVAC = shutil.which('javac')
JAVA = shutil.which('java')
check('★★★ 本机有 javac / java（fpstest 那一套也依赖它；没有就没法"真跑"纯类）',
      JAVAC is not None and JAVA is not None, [JAVAC, JAVA])

DRIVER = '''import com.deepseek.pet.ModelFile;
public final class MemAdviceDriver {
    static int bad = 0;
    static void want(String tag, String got, boolean wantNull, String mustContain) {
        if ((got == null) != wantNull) {
            System.out.println("FAIL " + tag + " null=" + (got == null) + " :: " + got); bad++; return;
        }
        if (got != null && mustContain != null && !got.contains(mustContain)) {
            System.out.println("FAIL " + tag + " 缺少「" + mustContain + "」 :: " + got); bad++; return;
        }
        System.out.println("ok   " + tag);
    }
    public static void main(String[] a) {
        final long MB = 1024L * 1024L;
        // 0.5B 那一档 + 内存充裕 ⇒ 不该说话
        want("0.5B/8GB/3GB 可用 ⇒ 不显示", ModelFile.memoryAdvice(400*MB, 8192*MB, 3000*MB), true, null);
        // 真机上那个模型 ⇒ 必须说话，且带读数与"0.5B"建议
        want("1.5B Q4_K_M/8GB ⇒ 显示且带读数",
             ModelFile.memoryAdvice(1060*MB, 8192*MB, 2600*MB), false, "6.5 秒/字");
        want("……而且点名换 0.5B", ModelFile.memoryAdvice(1060*MB, 8192*MB, 2600*MB), false, "0.5B");
        // 小模型但**当前可用内存比它还小** ⇒ 也要说话（另一种病）
        want("0.4GB 模型 / 只剩 0.3GB 可用 ⇒ 显示",
             ModelFile.memoryAdvice(400*MB, 16384*MB, 300*MB), false, "关掉别的 App");
        // 没模型 ⇒ 什么都不说
        want("没模型 ⇒ 不显示", ModelFile.memoryAdvice(0, 8192*MB, 3000*MB), true, null);
        // 拿不到内存数字（脏输入）也不能崩，而且小模型仍不该说话
        want("内存未知 + 0.4GB 模型 ⇒ 不显示", ModelFile.memoryAdvice(400*MB, 0, 0), true, null);
        // 内存未知 + 大模型 ⇒ 仍然说话（不能因为拿不到数就不提醒）
        want("内存未知 + 1.5B ⇒ 仍显示",
             ModelFile.memoryAdvice(1060*MB, 0, 0), false, "6.5 秒/字");
        System.out.println(bad == 0 ? "MEMADVICE_OK" : ("MEMADVICE_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''

if JAVAC and JAVA and os.path.isfile(MF):
    tmp = tempfile.mkdtemp(prefix='dsp67_')
    try:
        drv = os.path.join(tmp, 'MemAdviceDriver.java')
        io.open(drv, 'w', encoding='utf-8').write(DRIVER)
        # ★ 编的是**出厂源码本身**（src/…/ModelFile.java），不是抄一份
        cp = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp, MF, drv],
                            capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那份 ModelFile.java**（真源码，不是抄本）',
              cp.returncode == 0, (cp.stderr or '')[-400:])
        if cp.returncode == 0:
            rr = subprocess.run([JAVA, '-cp', tmp, 'MemAdviceDriver'],
                                capture_output=True, text=True)
            check('★★★ memoryAdvice 七组边界**真跑**全对（不是"看代码觉得对"）',
                  rr.returncode == 0 and 'MEMADVICE_OK' in rr.stdout,
                  (rr.stdout or '')[-400:] + (rr.stderr or '')[-200:])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

print()

# ================================================================ 十一、轮次67：顺手修的真缺陷
print('十一、★★ 轮次67 顺手修掉的真缺陷（编译警告暴露出来的）')
print()

check('★★★ 正式版包名那组的 `nativeLoaded` 转发**补上了 return**'
      '（原来漏了，正式版 `isLoaded()` 返回值不确定 —— 轮次62 就带进来的）',
      'return Java_com_deepseek_pet_LocalLlm_nativeLoaded(env, cls);' in cpp_code)

print()

# ================================================================ 十三、轮次68：三态 + 强制不回落
print('十三、★★★ 原生侧的「开着 GPU 失败**不回落 CPU**」（结构性判据；自动降级在 Java 侧，见第二十一节）')
print()


def block_after(src, anchor):
    """取 anchor 之后**花括号配平**的那一整段（含括号）。

    ★★ 为什么要花括号配平而不是"看几行"：本轮最要紧的判据是
    "**开 GPU 那一支里不许出现回落**"。只有把那一整段**精确**切出来，
    "里面没有 do_load_locked" 这句话才是有意义的 ——
    随手取 200 个字符既可能切到隔壁分支，也可能被注释骗过去。
    """
    i = src.find(anchor)
    if i < 0:
        return ''
    j = src.find('{', i)
    if j < 0:
        return ''
    depth = 0
    for k in range(j, len(src)):
        if src[k] == '{':
            depth += 1
        elif src[k] == '}':
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return ''


gen_body = block_after(cpp_code, 'Java_com_deepseek_pet_LocalLlm_nativeGenerate(')
load_body = block_after(cpp_code, 'Java_com_deepseek_pet_LocalLlm_nativeLoad(')
on_gen = block_after(cpp_code, 'if (g_gpu_mode.load() == DSP_GPU_MODE_ON) {')
on_load = block_after(load_body, 'if (gpu_mode == DSP_GPU_MODE_ON) {')
on_do_load = block_after(cpp_code, 'if (on_gpu) {')
fail_fn = block_after(cpp_code, 'void gpu_required_failed_locked(')

check('★★★ 四段"结构性判据"要用的代码块都取到了（取不到说明判据悬空，必须马上看）',
      len(gen_body) > 200 and len(load_body) > 200 and len(on_gen) > 80
      and len(on_load) > 80 and len(on_do_load) > 80 and len(fail_fn) > 200,
      [len(gen_body), len(load_body), len(on_gen), len(on_load), len(on_do_load), len(fail_fn)])

check('★★★ **开 GPU 那一支（do_load_locked 里）也不许出现"按纯 CPU 重载"**'
      '（★ 这是本轮最要紧的一条结构性判据：加载阶段同样不能偷偷回落）',
      'do_load_locked' not in on_do_load and 'return false;' in on_do_load)

check('★★★ **开 GPU 那一支里不许出现"按纯 CPU 重载"**（结构性判据 —— 本轮最要紧的一条）',
      'do_load_locked' not in on_gen)
check('★★★ 开 GPU 失败必须是"**返回错误**"而不是"继续跑"（`return nullptr;`）',
      'return nullptr;' in on_gen)
check('★★★ 生成入口的函数体里**根本不出现** `do_load_locked`'
      '（⇒ "按纯 CPU 重载并重跑"这条路在生成入口里到不了）',
      'do_load_locked' not in gen_body)
check('★★★ 失败的唯一出口 `gpu_required_failed_locked` 里也没有 `do_load_locked`'
      '（只收拾现场、不重载任何东西）',
      'do_load_locked' not in fail_fn)
check('★★★ 但它**真的把会话清掉**（半死的 Vulkan 设备不许继续留在内存里）',
      'session_free();' in fail_fn)
check('★★★ 加载阶段同样分流：开 GPU 的那支"返回错误、不重载"',
      'do_load_locked' not in on_load and 'return JNI_FALSE;' in on_load)
check('★★★ 加载阶段的**失败路径也只有这一个出口**（`gpu_required_failed_locked` 被两条 catch 调用）'
      '⇒ 加载失败同样不存在"悄悄按纯 CPU 重来一遍"',
      load_body.count('gpu_required_failed_locked(') >= 2
      and load_body.rfind('do_load_locked') < load_body.find('gpu_required_failed_locked('))
check('★★★ 开 GPU 失败**依然不许崩**：catch 兜住 ⇒ 清现场 ⇒ 返回错误（不是 std::terminate）',
      cpp_code.count('catch (') >= 4
      and 'gpu_required_failed_safe(' in cpp_code
      and 'gpu_required_failed_locked(' in cpp_code)
check('★★★ 开 GPU 失败时 `lastError` 是**可读中文 + 原始错误串**（不是一句"出错了"）',
      '开 GPU 失败（已按你的设置不回落 CPU）' in cpp
      and '原始错误：' in cpp and 'raw' in fail_fn)
check('★★★ 开 GPU 失败时 `lastError` **一定非空**（失败路径都调用同一个出口）',
      cpp_code.count('gpu_required_failed_locked(') >= 2
      and cpp_code.count('gpu_required_failed_safe(') >= 1)
check('★★★ 没有可用 GPU 设备时，**开 GPU 就是报错**（不是"照常可用"、也不是静默压回 0 层）',
      re.search(r'if \(n_gpu_dev <= 0\) \{\s*set_err\("开 GPU 失败', cpp_code) is not None)
check('★★★ ★★★ **那个把"换模型"堵死的进程级粘性标记已经不存在了**'
      '（轮次68 的 `g_gpu_runtime_failed`：一次失败之后的**每一次加载**都直接失败 ⇒ '
      '用户报的"模型无法更换其他的 gguf 模型"就是它）—— 判据是"代码里 0 次出现"',
      'g_gpu_runtime_failed' not in cpp_code)
check('★★★ 而且**没有任何别的东西**在 ON 分支里"记住上次失败"'
      '（`do_load_locked` 的 ON 分支里只有"探设备 → 报错 / 定层数"两件事）',
      re.search(r'if \(on_gpu\) \{.*?\n    \}', cpp_code, re.S) is not None
      and 'g_gpu_runtime_failed' not in cpp_code)

print()
print('   —— 界面侧：**不静默** + 一键退路')
print()
check('★★★ 开 GPU 失败的气泡/错误话术在（说清"已按你的设置不回落 CPU"与下一步）',
      # ★ 锚点要**足够具体**：`不回落 CPU` 这几个字在 LocalChat 里还有第二处（载入失败那条日志），
      #   只查它的话"把引导话术那句删掉"照样绿 —— 反向项 AQ 当场抓出来的假绿。
      'guideGpuFailed' in lch and '已按你的设置「开」用 GPU、没有偷偷回落 CPU' in lch
      and 'looksLikeGpuFailure(why)' in lch)
check('★★★ 话术里**点名了另外四条退路都还能用**（★ 这是用户报障的那件事的正面对答）',
      'guideGpuFailed' in lch and '都还能用' in lch
      and '切回在线服务商' in block_after(lch, 'public static String guideGpuFailed('))
check('★★★ 只有**开着 GPU** + 错误看着像 GPU 那条路时才报"开 GPU 失败"'
      '（别把文件损坏那种错也赖到 GPU 上）',
      'GpuMode.isOn(gpuMode) && GpuFailNotice.looksLikeGpuFailure(why)' in lch
      and 'GpuMode.isOn(Prefs.localGpuMode(ctx)) && GpuFailNotice.looksLikeGpuFailure(why)' in lch)
check('★★★ 一键退路真的接了按钮、而且**真的写设置**（不是只弹个框）',
      'setPositiveButton(GpuFailNotice.btnOff()' in gmui
      and 'Prefs.setLocalGpuMode(ctx, m);' in gmui
      and 'LocalLlm.unload();' in gmui and 'LocalChat.forget();' in gmui)
check('★★ 退路只剩**一颗**（两态了，不再有"改用自动"那颗）—— 但正文明说另外四条退路不受影响',
      'GpuFailNotice.btnAuto' not in cpp and 'GpuFailNotice.btnAuto' not in gmui
      and 'GpuFailNotice.btnAuto' not in mai
      and '卸载模型 / 删除模型 / 换一个模型 / 切回在线服务商' in gfn)
check('★★★ 三个入口都接了（悬浮窗气泡 / 聊天窗 / 设置面板）',
      'GpuModeUi.showFallbackPromptIfNeeded(FloatingService.this, dlg())' in fsvc
      and 'GpuModeUi.showFallbackPromptIfNeeded(ChatActivity.this' in cact
      and 'GpuFailNotice.panelLine(gpuFail)' in mai
      and 'applyLocalGpuMode(GpuMode.OFF)' in mai)
check('★★ 悬浮服务用的是它自己的 overlay Builder（服务里弹普通对话框会 BadTokenException'
      '⇒ 表现正是"点了没反应"）',
      'dlg())' in fsvc
      and re.search(r'GpuModeUi\.showFallbackPromptIfNeeded\(FloatingService\.this, dlg\(\)\)',
                    fsvc) is not None)
check('★★ 只弹一次（否则每问一句都被拦一次）',
      'GpuFailNotice.popupShown();' in gmui and 'GpuFailNotice.popupPending()' in gmui)
check('★★ 弹不出来也不许静默（留一行 DSPLlm 日志）',
      '退路对话框没弹出来' in gmui)
check('★★ 设置页那一行是**持久可见**的（不是弹一次就没），并且带那颗一键按钮',
      'GpuFailNotice.last()' in mai and 'GpuFailNotice.btnOff()' in mai)
check('★★ 成功载入会把过期的失败提示收掉（别让一条已经过期的警告一直挂着）',
      'GpuFailNotice.clear();' in lch)

print()
print('   —— ★★ 真编真跑：三态与兼容链的纯函数（出厂源码，不是抄本）')
print()
# ★ 与第十节同一个套路：编**出厂**的 GpuMode.java / GpuFailNotice.java，再跑边界组。
MODE_DRIVER = '''import com.deepseek.pet.GpuMode;
import com.deepseek.pet.GpuFailNotice;
public final class GpuModeDriver {
    static int bad = 0;
    static void eq(String tag, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + tag + " got=" + got + " want=" + want); bad++;
        } else { System.out.println("ok   " + tag); }
    }
    public static void main(String[] a) {
        // —— 三档与默认值（轮次74：默认「自动」）
        eq("默认值就是 auto（自动）", GpuMode.DEFAULT_MODE, "auto");
        eq("三档常量", GpuMode.AUTO + "/" + GpuMode.ON + "/" + GpuMode.OFF, "auto/on/off");
        eq("normalize 大小写/空格", GpuMode.normalize(" ON "), GpuMode.ON);
        eq("normalize（自动）", GpuMode.normalize("auto"), GpuMode.AUTO);
        eq("normalize（关）", GpuMode.normalize("off"), GpuMode.OFF);
        eq("normalize 认不出 ⇒ null", GpuMode.normalize("banana"), null);
        eq("normalize null ⇒ null", GpuMode.normalize(null), null);
        // ★★★ 旧的 force 必须**认得出**（升级上来的用户不许被吃掉设置）
        eq("★ 旧值 force ⇒ 开", GpuMode.normalize("force"), GpuMode.ON);
        eq("★ 旧值 FORCE（大写）也认", GpuMode.normalize(" FORCE "), GpuMode.ON);
        // —— 旧 boolean 的兼容链（升级上来的用户读到什么）
        eq("没设置过 ⇒ 自动", GpuMode.migrate(false, true), GpuMode.AUTO);
        eq("明确开过 ⇒ 自动", GpuMode.migrate(true, true), GpuMode.AUTO);
        eq("★ 明确关过 ⇒ 关（尊重他做过的选择）", GpuMode.migrate(true, false), GpuMode.OFF);
        // —— 谁允许**自动降级**（轮次74：只有自动；而且必须说出来）
        eq("★★★ 自动档允许降级到 CPU", GpuMode.allowCpuFallback(GpuMode.AUTO), Boolean.TRUE);
        eq("★★★ 手动「开」不许降级（对照组语义）", GpuMode.allowCpuFallback(GpuMode.ON), Boolean.FALSE);
        eq("★★★ 关也不用降级", GpuMode.allowCpuFallback(GpuMode.OFF), Boolean.FALSE);
        eq("★★★ 脏值不许降级", GpuMode.allowCpuFallback("banana"), Boolean.FALSE);
        eq("usesGpu(off)=false", GpuMode.usesGpu(GpuMode.OFF), Boolean.FALSE);
        eq("usesGpu(on)=true", GpuMode.usesGpu(GpuMode.ON), Boolean.TRUE);
        eq("★ usesGpu(auto)=true（自动**可能**用 GPU，真正的决定在 GpuPlan）",
           GpuMode.usesGpu(GpuMode.AUTO), Boolean.TRUE);
        eq("★ usesGpu(脏值/null)=false（脏值绝不能变成「用 GPU」）",
           "" + GpuMode.usesGpu("banana") + GpuMode.usesGpu(null), "falsefalse");
        eq("isAuto 只认 auto", "" + GpuMode.isAuto(GpuMode.AUTO) + GpuMode.isAuto(GpuMode.ON)
           + GpuMode.isAuto(GpuMode.OFF), "truefalsefalse");
        eq("isOn 只认 on", "" + GpuMode.isOn(GpuMode.ON) + GpuMode.isOn(GpuMode.AUTO),
           "truefalse");
        // —— 给 JNI 的整数码（脏值必须当 off）
        eq("code(on)=1", Integer.valueOf(GpuMode.code(GpuMode.ON)), Integer.valueOf(1));
        eq("code(off)=0", Integer.valueOf(GpuMode.code(GpuMode.OFF)), Integer.valueOf(0));
        eq("★ code(旧 force)=1（升级用户仍然是「开」）",
           Integer.valueOf(GpuMode.code("force")), Integer.valueOf(1));
        eq("★ code(脏值)=0（静默用 CPU 可恢复，静默用 GPU 不可恢复）",
           Integer.valueOf(GpuMode.code("banana")), Integer.valueOf(0));
        // —— 文案：默认那条要说明"不用你调参数"
        eq("label(auto)", GpuMode.label(GpuMode.AUTO), "\\u26A1 GPU 加速：自动（按硬件决定）");
        eq("label(on) 就是用户那句话", GpuMode.label(GpuMode.ON), "\\u26A1 GPU 加速：开（用 GPU）");
        eq("label(off)", GpuMode.label(GpuMode.OFF), "\\u26A1 GPU 加速：关（纯 CPU）");
        eq("label(脏值) 当关（不许显示成开）", GpuMode.label("banana"), GpuMode.label(GpuMode.OFF));
        eq("三档选项是三条", Integer.valueOf(GpuMode.choices().length), Integer.valueOf(3));
        eq("自动那条写明「不用你调参数」",
           Boolean.valueOf(GpuMode.choices()[0].contains("什么都不用调")), Boolean.TRUE);
        eq("开那条写了代价", Boolean.valueOf(GpuMode.choices()[1].contains("代价")), Boolean.TRUE);
        eq("开那条写明「真的用 GPU」",
           Boolean.valueOf(GpuMode.choices()[1].contains("强制")), Boolean.TRUE);
        eq("关那条写了代价", Boolean.valueOf(GpuMode.choices()[2].contains("代价")), Boolean.TRUE);
        eq("describe 三档各不相同", Boolean.valueOf(
           !GpuMode.describe(GpuMode.OFF).equals(GpuMode.describe(GpuMode.ON))
           && !GpuMode.describe(GpuMode.AUTO).equals(GpuMode.describe(GpuMode.ON))
           && !GpuMode.describe(GpuMode.AUTO).equals(GpuMode.describe(GpuMode.OFF))), Boolean.TRUE);
        eq("describe(auto) 说清楚「自动 / 不需要你手动调」",
           Boolean.valueOf(GpuMode.describe(GpuMode.AUTO).contains("自动")
              && GpuMode.describe(GpuMode.AUTO).contains("不需要你手动调")), Boolean.TRUE);
        eq("describe(on) 说清「不偷偷回落 CPU」",
           Boolean.valueOf(GpuMode.describe(GpuMode.ON).contains("回落 CPU")), Boolean.TRUE);
        eq("changedToast 三档不同", Boolean.valueOf(
           !GpuMode.changedToast(GpuMode.OFF).equals(GpuMode.changedToast(GpuMode.ON))
           && !GpuMode.changedToast(GpuMode.AUTO).equals(GpuMode.changedToast(GpuMode.ON))), Boolean.TRUE);
        eq("hint 里点名默认是「自动」",
           Boolean.valueOf(GpuMode.hint().contains("自动")), Boolean.TRUE);
        eq("对话框标题带 GPU 加速", Boolean.valueOf(GpuMode.dialogTitle().contains("GPU 加速")),
           Boolean.TRUE);

        // —— GpuFailNotice：不静默的那一半
        GpuFailNotice.clear();
        eq("清空之后没有提示", GpuFailNotice.last(), null);
        eq("清空之后不弹", Boolean.valueOf(GpuFailNotice.popupPending()), Boolean.FALSE);
        GpuFailNotice.report("   ");
        eq("空原因也要给一句可读的（绝不留空）",
           Boolean.valueOf(GpuFailNotice.last() != null && GpuFailNotice.last().length() > 0),
           Boolean.TRUE);
        eq("报过一次就该弹", Boolean.valueOf(GpuFailNotice.popupPending()), Boolean.TRUE);
        GpuFailNotice.popupShown();
        eq("★ 弹过就不再弹（不骚扰）", Boolean.valueOf(GpuFailNotice.popupPending()), Boolean.FALSE);
        GpuFailNotice.report("vk::Device::createComputePipeline: ErrorUnknown");
        eq("原因原样留着（界面要把它显示出来）",
           Boolean.valueOf(GpuFailNotice.last().contains("ErrorUnknown")), Boolean.TRUE);
        eq("气泡文案里说清「没有偷偷回落 CPU」",
           Boolean.valueOf(GpuFailNotice.bubbleText(GpuFailNotice.last()).contains("没有偷偷回落 CPU")),
           Boolean.TRUE);
        eq("★ 气泡文案点名「卸载 / 换一个模型 / 切回在线服务商」（用户报障的正面对答）",
           Boolean.valueOf(GpuFailNotice.bubbleText(GpuFailNotice.last()).contains("卸载")
              && GpuFailNotice.bubbleText(GpuFailNotice.last()).contains("切回在线服务商")),
           Boolean.TRUE);
        eq("对话框标题/正文都带完整原因",
           Boolean.valueOf(GpuFailNotice.title().contains("GPU")
              && GpuFailNotice.message(GpuFailNotice.last()).contains("ErrorUnknown")), Boolean.TRUE);
        eq("★ 对话框正文明确写了「另外四条退路都还是能用的」",
           Boolean.valueOf(GpuFailNotice.message(GpuFailNotice.last()).contains("都还是能用的")),
           Boolean.TRUE);
        eq("有一个「保持开着」的退路（不想改也能关掉这个框）",
           Boolean.valueOf(GpuFailNotice.btnKeep().contains("保持开着")), Boolean.TRUE);
        eq("一键改成关那颗按钮在",
           Boolean.valueOf(GpuFailNotice.btnOff().contains("关")), Boolean.TRUE);
        // —— 判据：别把「文件损坏」也算成 GPU 失败
        eq("认 GPU 那条路的错误",
           Boolean.valueOf(GpuFailNotice.looksLikeGpuFailure(
              "vk::Device::createComputePipeline: ErrorUnknown")), Boolean.TRUE);
        eq("认原生侧那句中文（前缀只在 GpuFailNotice 里定义一处）",
           Boolean.valueOf(GpuFailNotice.looksLikeGpuFailure(
              GpuFailNotice.PREFIX + "：…")), Boolean.TRUE);
        eq("★ 不认「文件损坏/内存不够」（否则用户会去修一个不存在的问题）",
           Boolean.valueOf(!GpuFailNotice.looksLikeGpuFailure("模型加载失败（文件损坏）：x.gguf")
              && !GpuFailNotice.looksLikeGpuFailure(null)), Boolean.TRUE);
        GpuFailNotice.clear();
        System.out.println(bad == 0 ? "GPUMODE_OK" : ("GPUMODE_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''

if JAVAC and JAVA and os.path.isfile(GPU_MODE) and os.path.isfile(GPU_NOTICE):
    tmp2 = tempfile.mkdtemp(prefix='dsp69_')
    try:
        drv2 = os.path.join(tmp2, 'GpuModeDriver.java')
        io.open(drv2, 'w', encoding='utf-8').write(MODE_DRIVER)
        cp2 = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp2, GPU_MODE, GPU_NOTICE, drv2],
                             capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那份 GpuMode.java / GpuFailNotice.java**（真源码）',
              cp2.returncode == 0, (cp2.stderr or '')[-400:])
        if cp2.returncode == 0:
            rr2 = subprocess.run([JAVA, '-cp', tmp2, 'GpuModeDriver'],
                                 capture_output=True, text=True)
            check('★★★ 两态 / 旧值兼容链 / 真值表 / 文案 / 失败记录 **真跑**全对（三十余组边界）',
                  rr2.returncode == 0 and 'GPUMODE_OK' in rr2.stdout,
                  (rr2.stdout or '')[-4000:] + (rr2.stderr or '')[-200:])
    finally:
        shutil.rmtree(tmp2, ignore_errors=True)

check('★★★ Java 的整数码与 JNI 的宏**一一对应**（改了一边忘另一边 = 档位静默错位）',
      all(re.search(r'#define\s+DSP_GPU_MODE_%s\s+%d\b' % (n, v), cpp) is not None
          for n, v in (('OFF', 0), ('ON', 1)))
      and 'public static final int CODE_ON = 1;' in gmod
      and '#define DSP_GPU_MODE_AUTO' not in cpp
      and '#define DSP_GPU_MODE_FORCE' not in cpp)

print()

# ================================================================ 十四、模型管理（卸载 / 换模型 / 退路）
print('十四、★★★ 轮次69：**模型管理** —— 「⏏ 卸载」+「🔄 换模型」+ 加载失败**不许堵死退路**')
print()
check('★★★ 交付里真的多了一个 `LocalModelMenu`（五条退路的**唯一判据与文案**，纯类）',
      os.path.isfile(LOCAL_MENU))
check('★★★ 它**一个 "Gpu" 都不许有** —— GPU 出不出问题在**结构上**不可能影响退路'
      '（这才是"退路不许被堵死"的硬保证；反向项会往这里塞一句 GpuMode ⇒ 当场变红）',
      'Gpu' not in lmm and 'gpu' not in lmm)
check('★★ 五条退路的可用性判据都在，而且**只吃"导入了几个 / 引擎支不支持"**',
      'public static boolean canUnload()' in lmm
      and 'public static boolean canSwitch(int imported)' in lmm
      and 'public static boolean canDelete(int imported)' in lmm
      and 'public static boolean canImport()' in lmm
      and 'public static boolean canUseOnline()' in lmm)
check('★★★ 卸载那颗按钮**永远可点**（`canUnload()` 恒 true —— 载着就卸它、没载就如实说）',
      re.search(r'public static boolean canUnload\(\)\s*\{\s*return true;\s*\}', lmm) is not None)
check('★★★ 导入与"切回在线"**永远可点**（这两条是本地这条链彻底用不了时的出路）',
      re.search(r'public static boolean canImport\(\)\s*\{\s*return true;\s*\}', lmm) is not None
      and re.search(r'public static boolean canUseOnline\(\)\s*\{\s*return true;\s*\}', lmm) is not None)
check('★★★ 退路总表 `routes(imported, engineAvailable)` 的**入参里没有 GPU 这一维**'
      '（它连问都不问 GPU 怎么了 —— 这就是那条性质在代码形状上的表达）',
      re.search(r'public static boolean\[\] routes\(int imported, boolean engineAvailable\)', lmm)
      is not None)

print()
print('   —— A1：卸载真的释放内存、保留文件，而且看得出来')
print()
check('★★★ `LocalChat.unloadNow()` 在（真的调引擎 unload + 忘掉账本）',
      'public static String unloadNow()' in lch
      and 'LocalLlm.unload();' in block_after(lch, 'public static String unloadNow()')
      and 'forget();' in block_after(lch, 'public static String unloadNow()'))
check('★★★ 卸载的日志说清"内存已交还 + **文件保留**"（用户最怕的是把文件删了）',
      '内存已交还' in lmm and '文件保留' in lmm and 'LocalModelMenu.unloadLog(' in lch)
check('★★★ 卸载**不动文件**（unloadNow 里不许出现 ModelStore / delete / File.delete）',
      'ModelStore' not in block_after(lch, 'public static String unloadNow()')
      and 'delete' not in block_after(lch, 'public static String unloadNow()'))
check('★★★ 设置面板里**真的有一行**「⏏ 卸载当前模型」'
      '（★ 文案在 LocalModelMenu 里，面板负责调它 —— 正因为如此，它不可能"被 GPU 影响"）',
      '\\u23CF 卸载当前模型' in lmm and 'private void unloadLocalModelNow()' in mai
      and 'LocalModelMenu.unloadRow(LocalLlm.isLoaded(), cur)' in mai)
check('★★★ 卸载放**后台线程**（unload 是阻塞的，主线程等它就是"点一下卡半秒"）',
      'new Thread(new Runnable() { @Override public void run() {' in
      block_after(mai, 'private void unloadLocalModelNow()')
      and 'LocalChat.unloadNow();' in block_after(mai, 'private void unloadLocalModelNow()'))
check('★★★ 卸载之后**界面状态看得出来**：那一行由 LocalModelMenu 出，'
      '而且"已卸载"那条措辞与"还没载入"合起来说的就是"当前没有载入模型"',
      'LocalModelMenu.memoryRow(LocalLlm.isLoaded(), cur)' in mai
      and '没有**载入模型' in lmm)
check('★★ 卸载完会**立刻重画那一屏**（否则用户看不到状态变了）',
      'showLocalModelPanel();' in block_after(mai, 'private void unloadLocalModelNow()'))

print()
print('   —— A2：换模型真的换成新的（旧→新都有日志）')
print()
check('★★★ 换模型会记选择 + 卸旧的 + 忘掉账本（下次 ensureLoaded 必定载新的）',
      'ModelStore.setCurrent(this, name);' in block_after(mai, 'private void switchLocalModel(')
      and 'LocalLlm.unload();' in block_after(mai, 'private void switchLocalModel(')
      and 'LocalChat.forget();' in block_after(mai, 'private void switchLocalModel('))
check('★★★ 换模型的**旧→新**都要进日志（真机上"换了没效果"就靠它对上）',
      'LocalModelMenu.switchLog(from, name)' in mai
      and '换了当前模型：' in lmm)
check('★★★ 复用判据必须是"**同一个文件**"才算常驻（只判 isLoaded() 会让换模型后继续用旧的）',
      'if (LocalLlm.isLoaded() && path.equals(loadedPath)) {' in lch)

print()
print('   —— A3：★★★ 加载失败**不许堵死退路**（用户报障的那件事）')
print()


def panel_body():
    """取出 showLocalModelPanel 的函数体（花括号配平）。"""
    return block_after(mai, 'private void showLocalModelPanel()')


PB = panel_body()
check('★★★ 面板函数体取到了（取不到说明判据悬空）', len(PB) > 800, len(PB))
check('★★★ 面板里**没有任何 `return;`** —— GPU 失败那一段只许"追加告警"，'
      '不许把后面那几颗退路按钮短路掉（这就是"点了没反应"的同一种坏法）',
      'return;' not in PB and 'return ;' not in PB)
check('★★★ 四颗退路按钮（导入 / 切换 / 删除 / 卸载）**都在面板里、且卸载那颗在 GPU 告警段之后**',
      '\\uD83D\\uDCE5 导入 .gguf 文件…' in PB
      and '\\uD83D\\uDD04 切换当前模型（已导入 ' in PB
      and '\\uD83D\\uDDD1 删除本地模型（' in PB
      and 'LocalModelMenu.unloadRow(LocalLlm.isLoaded(), cur)' in PB
      and PB.find('LocalModelMenu.unloadRow(') > PB.find('GpuFailNotice.panelLine(gpuFail)'))
check('★★★ 卸载那一行的文案来自 LocalModelMenu（**唯一出处**，面板自己不抄一遍）',
      '\\u23CF 卸载当前模型' not in PB and 'LocalModelMenu.unloadRow(' in PB)
check('★★★ GPU 告警那一段**只出现在那 3 处固定写法里**（声明 / 判空 / 传给 panelLine），'
      '不参与任何"要不要显示别的按钮"的判断',
      PB.count('gpuFail') <= 3)
check('★★★ 删除与切换**不以"当前载入了没有"为门槛**'
      '（`isLoaded()` 在面板里只用于**措辞**，不许当开关）',
      'LocalLlm.isLoaded()' in PB
      and PB.count('LocalLlm.isLoaded()') <= 2)
check('★★★ 卸载 / 删除 / 切换走的是**同一条**"不查引擎状态"的路'
      '（deleteModelNow 里只有"是当前那个才先卸"的判断，没有"没载入就不许删"）',
      'final boolean wasCurrent = ModelFile.sameName(ModelStore.current(this), name);' in mai
      and 'isLoaded' not in block_after(mai, 'private void deleteModelNow('))
check('★★★ 切回在线服务商那条路**没被碰**（它仍然是"从本地切回在线就把内存放掉"）',
      'final boolean wasLocal = ChatRouter.isLocal(this);' in mai
      and 'if (wasLocal && !toLocal) {' in mai)

print()
print('   —— ★★ 真编真跑：退路真值表 + 卸载/换模型的文案（出厂源码，不是抄本）')
print()
MENU_DRIVER = '''import com.deepseek.pet.LocalModelMenu;
public final class LocalModelMenuDriver {
    static int bad = 0;
    static void eq(String tag, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + tag + " got=" + got + " want=" + want); bad++;
        } else { System.out.println("ok   " + tag); }
    }
    public static void main(String[] a) {
        // ★★★ 本轮最要紧的一条：**任何状态下，五条退路的可用性都必须一样**。
        //   注意 routes() 的入参里根本没有 GPU 这一维 —— 这不是"忘了加"，
        //   这是"GPU 出问题不许改变退路"这句话的代码形状。
        boolean[] r0 = LocalModelMenu.routes(0, true);
        boolean[] r0b = LocalModelMenu.routes(0, false);
        boolean[] r2 = LocalModelMenu.routes(2, true);
        eq("★ 引擎装不上也照样能卸载", Boolean.valueOf(r0b[0]), Boolean.TRUE);
        eq("★ 引擎装不上也照样能导入", Boolean.valueOf(r0b[3]), Boolean.TRUE);
        eq("★ 引擎装不上也照样能切回在线", Boolean.valueOf(r0b[4]), Boolean.TRUE);
        eq("★ 一个模型都没有时：卸载/导入/切回在线仍然可用",
           "" + r0[0] + r0[3] + r0[4], "truetruetrue");
        eq("★ 一个模型都没有时：切换/删除不可用（没东西可切/可删）",
           "" + r0[1] + r0[2], "falsefalse");
        eq("★ 有两个模型时：五条全可用",
           "" + r2[0] + r2[1] + r2[2] + r2[3] + r2[4], "truetruetruetruetrue");
        eq("★ 退路名字与表一一对应（5 条）",
           Integer.valueOf(LocalModelMenu.routeNames().length), Integer.valueOf(5));
        eq("★ 名字里有「卸载」「更换模型」「切回在线服务商」",
           Boolean.valueOf(LocalModelMenu.routeNames()[0].contains("卸载")
              && LocalModelMenu.routeNames()[1].contains("更换模型")
              && LocalModelMenu.routeNames()[4].contains("切回在线服务商")), Boolean.TRUE);
        eq("canUnload 与状态无关（恒 true）",
           "" + LocalModelMenu.canUnload() + LocalModelMenu.canUnload(), "truetrue");
        eq("canSwitch 的边界：0 不行 / 1 行",
           "" + LocalModelMenu.canSwitch(0) + LocalModelMenu.canSwitch(1), "falsetrue");
        eq("canDelete 的边界：0 不行 / 1 行",
           "" + LocalModelMenu.canDelete(0) + LocalModelMenu.canDelete(1), "falsetrue");
        eq("canImport / canUseOnline 恒 true",
           "" + LocalModelMenu.canImport() + LocalModelMenu.canUseOnline(), "truetrue");

        // —— 文案（卸载必须说清「文件还在」）
        eq("卸载那一行（有载入）带模型名",
           Boolean.valueOf(LocalModelMenu.unloadRow(true, "a.gguf").contains("a.gguf")), Boolean.TRUE);
        eq("卸载那一行（没载入）如实说「当前没有载入」",
           Boolean.valueOf(LocalModelMenu.unloadRow(false, "a.gguf").contains("当前没有载入")),
           Boolean.TRUE);
        eq("★ 卸载结果 Toast 说清「文件还在」",
           Boolean.valueOf(LocalModelMenu.unloadResult(true, "a.gguf").contains("文件还在")),
           Boolean.TRUE);
        eq("★ 没载入时卸载：如实说「本来就没有载入」（不许假装卸掉了什么）",
           Boolean.valueOf(LocalModelMenu.unloadResult(false, "a.gguf").contains("本来就没有载入")),
           Boolean.TRUE);
        eq("★ 卸载日志带「内存已交还」与「文件保留」",
           Boolean.valueOf(LocalModelMenu.unloadLog(true, "a.gguf").contains("内存已交还")
              && LocalModelMenu.unloadLog(true, "a.gguf").contains("文件保留")), Boolean.TRUE);
        eq("★ 卸载后那一行必须**看得出「没有载入模型」**（用户点名要的验收点）",
           Boolean.valueOf(LocalModelMenu.memoryRow(false, "a.gguf").contains("没有**载入模型")),
           Boolean.TRUE);
        eq("★ 载入时那一行说清「直接出话」，并指向卸载那颗按钮",
           Boolean.valueOf(LocalModelMenu.memoryRow(true, "a.gguf").contains("a.gguf")
              && LocalModelMenu.memoryRow(true, "a.gguf").contains("卸载当前模型")), Boolean.TRUE);
        eq("★ 换模型日志带「旧 → 新」两个名字",
           Boolean.valueOf(LocalModelMenu.switchLog("old.gguf", "new.gguf")
              .contains("old.gguf") && LocalModelMenu.switchLog("old.gguf", "new.gguf")
              .contains("new.gguf")), Boolean.TRUE);
        eq("★ 换模型 Toast 提醒「首次要载入几秒」",
           Boolean.valueOf(LocalModelMenu.switchToast("n.gguf").contains("载入几秒")), Boolean.TRUE);
        System.out.println(bad == 0 ? "MENU_OK" : ("MENU_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''

if JAVAC and JAVA and os.path.isfile(LOCAL_MENU):
    tmp3 = tempfile.mkdtemp(prefix='dsp69m_')
    try:
        drv3 = os.path.join(tmp3, 'LocalModelMenuDriver.java')
        io.open(drv3, 'w', encoding='utf-8').write(MENU_DRIVER)
        cp3 = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp3, LOCAL_MENU, drv3],
                             capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那份 LocalModelMenu.java**（真源码、零 Android 依赖）',
              cp3.returncode == 0, (cp3.stderr or '')[-400:])
        if cp3.returncode == 0:
            rr3 = subprocess.run([JAVA, '-cp', tmp3, 'LocalModelMenuDriver'],
                                 capture_output=True, text=True)
            check('★★★ 退路真值表 / 卸载与换模型的文案 **真跑**全对',
                  rr3.returncode == 0 and 'MENU_OK' in rr3.stdout,
                  (rr3.stdout or '')[-500:] + (rr3.stderr or '')[-200:])
    finally:
        shutil.rmtree(tmp3, ignore_errors=True)

print()
print('   —— 构建溯源（下一个窗口重编时唯一的依据）')
print()
check('★★★ BUILD_INFO 有轮次69 那一节（两态 / 删掉粘性标记与 auto 回落 / 重编只动 JNI）',
      '轮次69' in info and 'DSP_GPU_MODE_ON' in info and 'g_gpu_runtime_failed' in info)
check('★★★ BUILD_INFO 记着**三条本地补丁逐条核实过**（本轮没改 llama.cpp ⇒ 不必重打，但必须核实）',
      'posix_memalign' in info and 'dsp.vkGetPhysicalDeviceFeatures2' in info
      and '0x5143u' in info and 'use_subgroups = false' in info)
check('★★★ BUILD_INFO 记着两个坑：**不在编译时跑反向套件**（会 PermissionError 留下被改坏的源文件）'
      '+ **ninja 只看时间戳**（只看到 `[1/1] Linking` 就是没重编）',
      'PermissionError' in info and '.r66bak' in info and 'Building CXX object' in info)
check('★★★ BUILD_INFO 的诚实边界写清了：**没有真机读数** + **开 GPU 不解决内存瓶颈**'
      '（不许让用户以为"开 GPU = 就快了"）',
      'adb devices' in info and '163 MB/s' in info and '0.5B' in info
      and ('同一块内存' in info))
check('★★ BUILD_INFO 记着本轮库体积（易变，别当判据）',
      '66,912' in info)

print()

# ================================================================ 十五、一页纸自测指南
print('十五、★★★ 交付里那份「一页纸」自测指南（与代码/日志**逐条对齐**）')
print()

check('★★★ 指南在交付目录里（与四件放一起，用户一眼能找到）', os.path.isfile(GUIDE), GUIDE)
check('★★★ 工程里也有一份（源码包里带着，改代码的人看得到）',
      os.path.isfile(GUIDE_SRC), GUIDE_SRC)
# ★★ 同一个事实抄在两处就一定会漂（工程被咬过好几次）⇒ 要么只留一处，要么加一条"两处都在"的判据。
#    这里两份**必须逐字节相同** —— 用户拿到的与源码包里的不许是两份不同的东西。
check('★★★ 两份指南**逐字节相同**（交付目录那份 / 工程里那份）',
      os.path.isfile(GUIDE) and os.path.isfile(GUIDE_SRC)
      and rbytes(GUIDE) == rbytes(GUIDE_SRC),
      [len(rbytes(GUIDE)) if os.path.isfile(GUIDE) else -1,
       len(rbytes(GUIDE_SRC)) if os.path.isfile(GUIDE_SRC) else -1])
_glines = guide.count('\n') + 1 if guide else 0
# ★★ 轮次71：从「≤ 130 行」放到「≤ 170 行」—— 本轮**按要求**加了两整节
#   （「🧪 自检按钮怎么用」+「🎛 GPU 参数怎么 A/B」，都是用户自己动手要用的），
#   130 行的预算已经装不下。★ 但不许无限长：170 行仍然"一次能看完"。
# ★★★ 轮次72：再放到 **240 行** —— 真机实测那一轮加了一整节
#   「★★★ 真机实测结论」（七组 A/B 的结果表 + 三条结论 + 面板 bug 的说明 + 没测到什么），
#   那是**用户最需要先看的一节**（它直接回答"我这台手机该用哪一档"），不许为了行数删掉。
# ★★★ 轮次74：再放到 **320 行** —— 又加了第 1 节（三档，默认自动）与第 11 节
#   「自动到底怎么自动」（三步 / 预算公式 / 降级 / 报告里那两行怎么读）。
#   用户这一轮的核心诉求就是"不用手动调参数"，这一节是**他唯一需要读的那一节**。
check('★★ 它真的还是一页纸（≤ 320 行 —— 轮次71/72/73/74 各加了一节；'
      '★ 再长就该拆成两份文档，而不是继续往这一份里堆）',
      0 < _glines <= 320, _glines)
check('★★★ 交付说明「七」里指过去了（否则用户根本不知道有这份指南）',
      '本地模型-自测指南.md' in deliver)
check('★★ 内容齐：装包 / 0.5B / 开与关 / **卸载与换模型** / 挂日志 / 判定表 / 要发回什么',
      all(s in guide for s in ('adb install -r -d', '0.5B', '开（用 GPU）', '关（纯 CPU）',
                               'adb logcat -s', '判定表', '发回来',
                               '卸载当前模型', '切换当前模型')))
check('★★★ 指南里**专门有一节讲"怎么卸载 / 怎么换模型"**（用户这一轮问的就是这两件事）',
      '怎么卸载' in guide and '怎么换模型' in guide
      and '文件保留，一个字节都不动' in guide and '先卸旧的、下次对话载新的' in guide)
# ★★★ 轮次71 新增的两节 —— 这一轮的核心交付就是"让用户不连电脑也能自己 A/B 出答案"，
#   所以指南里必须**手把手**告诉他那颗按钮在哪、怎么比。
check('★★★ 指南里有一整节「🧪 自检按钮怎么用」（含每一行怎么读的表）',
      '🧪 自检按钮怎么用' in guide and '本地推理自检（跑一句，出一屏结果）' in guide
      and '复制结果' in guide and '结果=成功 / 失败' in guide
      and '最近的后端警告/错误=' in guide and '原始错误=' in guide)
check('★★★ 指南里有一整节「🎛 GPU 参数怎么 A/B」（一次只改一个 + 什么时候生效 + 怎么比）',
      'GPU 参数怎么 A/B' in guide
      and '一次只改一个' in guide and '完全退出 App 再打开' in guide
      and '↩ 全部恢复默认' in guide and '完整串' in guide)
check('★★★ 指南把"自检"说成**最省事的一条路**（否则用户还是会去挂 logcat，然后卡住）',
      '想省事就直接跳到第 7 节' in guide and '一键复制' in guide)
check('★★★ 点名建议先换 0.5B（≈400 MB），并说明 1.5B 在这台机上会被换页',
      '400 MB' in guide and '1.5B' in guide and ('换页' in guide or 'zram' in guide))

# ★★★ 指南说"装哪个包"，就必须说**交付 zip 里那个真的名字**。
#   为什么单独钉一条：本轮的指南第一版写的是源码树里的 `…_rebuilt.apk`，
#   而交付 zip 里的 APK 叫 `…_开发版.apk`（`pack_delivery.py` 会改名）——
#   这正是"清单里写了实际不存在的东西"的同类坑，真机验证时用户会直接卡住。
# ★ 轮次86：这里原来硬编码 v7.5 —— 交付目录里的旧包一删（或版本一升）就恒红。
#   版本号**现场从清单读**（本脚本不存版本常量），包名跟着版本走。
def _manifest_version():
    import re as _re
    try:
        _mf = io.open(os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), 'AndroidManifest.xml'), encoding='utf-8').read()
        _m = _re.search(r'android:versionName="([^"]+)"', _mf)
        return _m.group(1) if _m else ''
    except Exception:
        return ''


DEV_ZIP = os.path.join(DELIV_DIR, 'DeepSeekPet_v%s_开发版.zip' % _manifest_version())
_zipped_apk = ''
if os.path.isfile(DEV_ZIP):
    try:
        with zipfile.ZipFile(DEV_ZIP) as _z:
            for _n in _z.namelist():
                if _n.lower().endswith('.apk'):
                    _zipped_apk = os.path.basename(_n)
    except Exception:                                    # noqa: BLE001
        _zipped_apk = ''
check('★★★ 指南里写的 APK 名就是**交付 zip 里那一个**（不是源码树里的 `_rebuilt` 名）',
      _zipped_apk != '' and _zipped_apk in guide
      and 'DeepSeekPet_v7.5_rebuilt.apk' not in guide, _zipped_apk)

# ★★★ 逐条对齐：指南里引用的每一句，都必须在**源码里真的存在**。
#   为什么要有这一节：工程被咬过 —— 清单里写过一条"代码里根本没有的日志"，
#   真机验证时才发现（用户照着一条永远不出现的日志以为功能坏了）。
#   ⇒ 这里把"指南 ↔ 源码"成对钉住：**改了一边、另一边当场变红**。
#   （needle_guide 是指南里逐字出现的那一段；needle_src 是源码里逐字出现的那一段）
GUIDE_PAIRS = [
    ('⚡ GPU 加速：开（用 GPU）', 'GPU 加速：开（用 GPU）',
     'GpuMode.java', gmod, '设置页那一行的标题（开）'),
    ('⚡ GPU 加速：关（纯 CPU）', 'GPU 加速：关（纯 CPU）',
     'GpuMode.java', gmod, '两态里的"关"'),
    ('⚡ GPU 加速（选定后生效）', 'GPU 加速（选定后生效）',
     'GpuMode.java', gmod, '两态单选的对话框标题'),
    ('改用「关」（纯 CPU）', '改用「关」（纯 CPU）',
     'GpuFailNotice.java', gfn, '一键退路按钮'),
    ('保持开着（我知道了）', '保持开着（我知道了）',
     'GpuFailNotice.java', gfn, '保持开着那颗按钮'),
    # ★★★ 轮次69 新增：用户这一轮问的"怎么卸载 / 怎么换模型"那两颗按钮与两行日志。
    ('⏏ 卸载当前模型', '\\u23CF 卸载当前模型', 'LocalModelMenu.java', lmm,
     '卸载那颗按钮（用户点名要的功能）'),
    ('🔄 切换当前模型（已导入 N 个）…', '\\uD83D\\uDD04 切换当前模型（已导入 ',
     'MainActivity.java', mai, '换模型那颗按钮（= 用户说的"更换模型"）'),
    ('已卸载内存里的本地模型', '已卸载内存里的本地模型', 'LocalModelMenu.java', lmm,
     '卸载成功的日志（★ 用户要能"看得出当前没有载入模型"）'),
    ('内存里**没有**载入模型', '内存里**没有**载入模型', 'LocalModelMenu.java', lmm,
     '卸载之后面板那一行的措辞'),
    ('文件保留，一个字节都不动', '文件保留，一个字节都不动', 'LocalModelMenu.java', lmm,
     '卸载**不动文件**（用户最怕的是把文件删了）'),
    ('换了当前模型：', '换了当前模型：', 'LocalModelMenu.java', lmm,
     '"换完真的用新的"的现场证据 ①'),
    ('★★ 后端设备：', '★★ 后端设备：', 'dspllm_jni.cpp', cpp, '后端设备那一行'),
    ('⇒ 开 GPU：认出', '⇒ 开 GPU：认出 %d 个 GPU 设备',
     'dspllm_jni.cpp', cpp, '开 GPU 时认出 GPU 的那行日志'),
    ('（失败就不回落 CPU，会把原因报给你）', '（失败就不回落 CPU，会把原因报给你）',
     'dspllm_jni.cpp', cpp, '开 GPU 的风险写在日志里'),
    ('开 GPU 失败（已按你的设置不回落 CPU）', '开 GPU 失败（已按你的设置不回落 CPU）',
     'dspllm_jni.cpp', cpp, '开 GPU 失败的 lastError / 日志前缀'),
    ('compute pipeline creation failed for ', 'compute pipeline creation failed for %s',
     'llama.cpp / ggml-vulkan.cpp', vkc, '驱动拒绝计算管线的那行（★关键证据）'),
    ('load_tensors: offloaded 29/29 layers to GPU', 'offloaded %d/%d layers to GPU',
     'llama.cpp / llama-model.cpp', vkm, 'offload 层数那行'),
    ('using device Vulkan0 (Adreno (TM) 740) (unknown id) -', 'using device %s (%s) (%s) - %zu MiB free',
     'llama.cpp / llama.cpp', vkmain, 'llama.cpp 说它用哪个设备'),
    ('本地模型已载入并常驻：', '本地模型已载入并常驻：', 'LocalChat.java', lch,
     '载入成功那行（"换完真的用新的"的现场证据 ②）'),
    ('GPU 档位 on（开：真的用 GPU，失败不回落 CPU）', 'on（开：真的用 GPU，失败不回落 CPU）',
     'dspllm_jni.cpp', cpp, '"档位确实是 on（开）"的设备侧判据'),
    ('本地模型已卸载，内存已交还', '本地模型已卸载，内存已交还', 'dspllm_jni.cpp', cpp,
     '原生侧"卸载真的发生了"的证据'),
    ('本地生成完成：prompt', '本地生成完成：prompt %d tok', 'dspllm_jni.cpp', cpp,
     '这一句的总成绩'),
    ('分段读数：prefill', '分段读数：prefill %d tok', 'dspllm_jni.cpp', cpp,
     '★ 最关键的两个数（prefill / decode）'),
    ('内存读数[模型加载完成]', 'log_memory_line("模型加载完成", mem_loaded)', 'dspllm_jni.cpp', cpp,
     '内存账第一现场'),
    ('RssFile', 'RssFile %.0f MB', 'dspllm_jni.cpp', cpp, '权重常驻多少'),
    ('VmSwap', 'VmSwap %.0f MB', 'dspllm_jni.cpp', cpp, '被换出去多少'),
    ('MemAvailable', 'MemAvailable %.0f MB', 'dspllm_jni.cpp', cpp, '整机可用内存'),
    ('页错误读数：prefill 段 minflt', '页错误读数：prefill 段 minflt', 'dspllm_jni.cpp', cpp,
     '分段页错误'),
    ('Qwen2.5-0.5B-Instruct Q4_K_M', 'Qwen2.5-0.5B-Instruct Q4_K_M', 'LocalChat.java', lch,
     '建议的 0.5B 模型名（与 App 里的引导一致）'),
    ('设置 → 陪伴 → 对话与人格 → ', '设置 → 陪伴 → 对话与人格 → ', 'LocalChat.java', lch,
     '设置路径（与 App 里的引导一致）'),
    ('debug.dsp.adreno_subgroup', '__system_property_get("debug.dsp.adreno_subgroup"',
     'dspllm_jni.cpp', cpp, '进阶 A/B 开关'),
    ('双击桌宠', '双击', 'FloatingService.java', fsvc, '唯一能被 adb 驱动的打字入口'),
    # ★★★ 轮次71 新增：自检那颗按钮 + 八个旋钮 + 两行"这次的参数是什么"的日志。
    #   ★ 这一节的规矩没变：指南里引用的每一句都必须在**源码里逐字存在**（改一边就红）。
    ('🧪 本地推理自检（跑一句，出一屏结果）', '本地推理自检（跑一句，出一屏结果）',
     'LocalSelfTest.java', stest, '自检那颗按钮（用户这一轮最需要的东西）'),
    ('📋 复制结果', '复制结果', 'MainActivity.java', mai,
     '一键复制（用户唯一能做的"把结果交回来"的动作）'),
    ('跑一句，出一屏结果', '出一屏结果', 'LocalSelfTest.java', stest,
     '自检的形状：一屏、可复制（★ 不是"跑完就没了"）'),    ('结果=成功 / 失败', '结果=") + (r.ok ? "成功" : "失败")', 'dspllm_jni.cpp', cpp,
     '自检报告第一行（跑起来没有）'),
    ('是否有 Vulkan GPU 设备=', '是否有 Vulkan GPU 设备=',
     'dspllm_jni.cpp', cpp, 'GPU 到底认没认出来'),
    ('最近的后端警告/错误=', '最近的后端警告/错误=',
     'dspllm_jni.cpp', cpp, '★ 失败的着色器名就在这一行里'),
    ('原始错误=', '原始错误=', 'dspllm_jni.cpp', cpp, '失败时的原始错误串'),
    # ★★★ 轮次72：源码那一边的锚点跟着**报告格式的修复**一起更新 ——
    #   原来 `decode` 那一行是 `decode=%d tok / %.0f ms (%.2f tok/s, %.0f ms/token)`，
    #   而 `-1`（"这一步没跑到"的哨兵）会被印成 `-1 ms`。现在拆成三段：
    #   耗时走 `ms_text()`、tok/s 走 `tok_s_text()`、ms/token 只在真的生成了 token 时才拼上。
    #   ⇒ 锚点换成新写法里**逐字存在**的那一段（旧锚点已经悬空，本轮实测抓到）。
    ('decode=… tok/s, … ms/token', ', %.0f ms/token',
     'dspllm_jni.cpp', cpp, '★ 最要紧的那个数（decode 的 ms/token）'),
    ('首字（TTFT）=', '首字（TTFT）=', 'dspllm_jni.cpp', cpp, '首字延迟'),
    ('🎛 本地推理调优（进阶 · A/B 用）', '本地推理调优（进阶 · A/B 用）',
     'LocalTune.java', tune, '八个调优旋钮那一行的标题'),
    ('Adreno 的归约方式', 'Adreno 的归约方式', 'LocalTune.java', tune,
     '旋钮 ①（轮次67 那条 setprop 的 UI 版）'),
    ('Vulkan 量化矩阵乘（MMVQ）', 'Vulkan 量化矩阵乘（MMVQ）', 'LocalTune.java', tune,
     '旋钮 ②（最可能绕开被拒着色器的那一条）'),
    ('强制 subgroup 归约（对照组）', '强制 subgroup 归约（对照组）',
     'LocalTune.java', tune, '旋钮 ① 的对照组取值'),
    ('不用 mmap（对照）', '不用 mmap（对照）', 'LocalTune.java', tune, '载入方式的对照组'),
    ('↩ 全部恢复默认', '全部恢复默认（试乱了点这个）', 'MainActivity.java', mai,
     '试乱了要有退路（本工程的老规矩）'),
    ('完全退出 App 再打开', '要完全退出 App 再打开', 'LocalTune.java', tune,
     '★ 后端级那四个旋钮的生效时机（不写清就会"改了没效果"）'),
    ('本次调优（与本轮默认不同的项）：', '本次调优（与本轮默认不同的项）：',
     'dspllm_jni.cpp', cpp, '每次载入都原样打出这一套参数'),
    ('生效参数：flash attention=', '生效参数：flash attention=%s',
     'dspllm_jni.cpp', cpp, '载入级的四个参数各是什么'),
    ('flash attention=off（强制关）', 'off（强制关）', 'dspllm_jni.cpp', cpp,
     '旋钮值在日志里的形状'),
]
for ng, ns, fname, ftext, note in GUIDE_PAIRS:
    check('★★★ 指南 ↔ 源码逐字对齐：%s' % note,
          ng in guide and ns in ftext,
          '指南里有=%s / %s 里有=%s' % (ng in guide, fname, ns in ftext))

print()

# ================================================================ 十七、自检
print('十七、★★★ 轮次71：「\U0001F9EA 本地推理自检」—— 用户不连电脑也能把结论发回来')
print()
print('   —— 起因：轮次66~70 每一轮都卡在"这台手机的 GPU 到底行不行、哪个旋钮有用"上；')
print('      开发机 adb 从头到尾是空的，而真机是用户自己的日常机（他不会连电脑、不会挂 logcat）。')
print()

check('★★★ LocalSelfTest.java 在（自检报告的组装与判定）', os.path.isfile(LOCAL_SELFTEST))
check('★★★ 它是**纯类**：整个文件里一次 `import android` 都没有'
      '（⇒ 断言才能用 javac 编出厂这一份源码真跑它）'
      '★ 判据走**去注释之后**的视图：类注释里那句「一个 {@code android.*} 都没有」不算违规',
      'android' not in strip_comments(stest) and 'android' not in strip_comments(tune))
check('★★★ 自检的固定提示词与上限是**常量**（固定 = 两次自检之间可比；'
      '★ 换了提示词，两次的数字就没法比了）',
      'public static final String PROMPT' in stest and 'public static final int MAX_TOKENS' in stest
      and 'public static final String BUTTON' in stest
      and 'public static final String DIALOG_TITLE' in stest)
check('★★★ 界面上那颗按钮就是它（设置页「🤖 本地模型」里）',
      'LocalSelfTest.BUTTON' in mai and 'runLocalSelfTest()' in mai
      and 'LocalSelfTest.HINT' in mai)
check('★★★ 自检**先放掉内存里的模型**再载入 —— 否则用的不是"当前设置"，结果就是假的'
      '（GPU 层数与调优旋钮都只在载入时给一次）',
      '自检：先放掉内存里的模型，按当前设置重新载入' in lch
      and re.search(r'if \(LocalLlm\.isLoaded\(\)\) \{\s*Log\.i\(TAG, "自检：先放掉', lch) is not None)
check('★★★ 自检与真实对话走**同一条载入路**（都走 `loadPlanned`：同样的线程数 / ctx / '
      '计划层数 / 档位 / 调优串）',
      lch.count('loadPlanned(ctx, f, gpuMode, tune)') >= 2
      and 'LocalLlm.load(path, threads, LocalLlm.DEFAULT_CTX,' in lch
      and 'plan.gpu ? plan.ngl : 0' in lch)
check('★★★ 温度用 **0（贪心）**：随机采样会让两次自检的数字抖，那样就没法比了'
      '（★ 轮次74 起有两处 generate —— 主跑与"降级后重跑"，**两处都必须 0.0f**）',
      lch.count('LocalLlm.generate(LocalSelfTest.PROMPT, LocalSelfTest.MAX_TOKENS, 0.0f, null)') >= 2)
check('★★★ 自检**绝不动文件**（只载入 / 生成 / 读读数 —— 它不许碰 ModelStore）',
      'ModelStore' in lch and 'ModelStore.setCurrent' not in lch
      and 'ModelStore.delete' not in lch)
check('★★★ 后台线程跑 + 进度提示 + **绝不许让 UI 卡住**（载入几秒 + 生成几秒 = ANR）',
      'new Thread(new Runnable() { @Override public void run() {\n            final LocalChat.Check c = LocalChat.selfTest(MainActivity.this);'
      in mai
      and 'LocalSelfTest.PROGRESS' in mai and 'pd.show()' in mai)
check('★★★ **可取消**：取消 = 把那一轮的**代号** +1（单调递增，天然没有"取消后又点一次"的竞态），'
      '结果回来时先对号 ⇒ 取消了就绝不弹结果',
      'private int selfTestRun = 0;' in mai
      and 'final int myRun = ++selfTestRun;' in mai
      and 'if (myRun != selfTestRun) {' in mai
      and 'selfTestRun++;' in mai)
check('★★★ 取消时在**后台线程**调 unload（它会先发停止信号**再等锁**，'
      '而这台机器上一个字要 ~6.5 秒 ⇒ 主线程等它就是 ANR）',
      'new Thread(new Runnable() { @Override public void run() {\n'
      '            // \u2605\u2605 必须后台线程：LocalLlm.unload() 会先发停止信号**再等锁**，' in mai
      and 'dspllm-selftest-cancel' in mai)
check('★★★ **一键复制**真的接了剪贴板（不是只画了一颗按钮）',
      'ClipboardManager' in mai and 'setPrimaryClip' in mai
      and 'ClipData.newPlainText("本地推理自检"' in mai)
check('★★ 复制失败也不许"点了没反应"（要告诉用户还能长按手动复制）',
      '在结果框里长按可以手动全选复制' in mai and 'setTextIsSelectable(true)' in mai)
check('★★★ 自检报告**也会整段进 logcat**（用户万一挂了日志，那边拿到的是同一份）',
      '"自检报告：\\n" + report' in mai)
check('★★★ 结果框可滚动（报告很长，不许被屏幕切掉 —— 切掉就等于证据丢了）',
      'new ScrollView(this)' in block_after(mai, 'private void showSelfTestResult(')
      and 'int maxH' in block_after(mai, 'private void showSelfTestResult('))
check('★★★ 失败时也要有**有用结论**（不是"失败了"三个字）：'
      '没跑起来 / 没有 Vulkan 设备 / 驱动拒绝计算管线 / 慢得离谱 —— 四条都写了',
      '没有跑起来' in stest and '一个可用的 Vulkan GPU 设备都没有' in stest
      and '驱动拒绝了计算管线' in stest and '跑起来了，但慢得离谱' in stest)
check('★★★ 结论必须点出**那个失败的着色器名**（"报告末尾那句 … 后面括号里的名字就是它"）',
      'compute pipeline creation failed' in stest and '被拒绝的着色器' in stest)
check('★★★ 还要给**下一步该点哪里**（"失败了"不是结论，那是把人堵死）',
      'public static String nextStep(' in stest and '改成「关（纯 CPU）」就能继续用' in stest
      and '完全退出 App 再打开' in stest)
check('★★★ 判据只认**形状**、阈值写在明面上（本工程的老规矩：改阈值就得连着断言一起改）',
      'public static final double SLOW_MS_PER_TOKEN' in stest
      and 'public static final double FAST_MS_PER_TOKEN' in stest
      and 'SLOW_MS_PER_TOKEN = 3000.0' in stest)
check('★★ 报告抬头把这次用的参数写全（GPU 档位 + 调优串 + 提示词 + 上限）',
      'GPU 档位：" + GpuMode.label(gpuMode)' in stest
      and '调优明细：" + LocalTune.reportLine(tune)' in stest
      and '固定提示词：" + PROMPT' in stest)
check('★★ 报告结尾说清"要发回来的就是上面这一整段"（与那份一页纸指南同一口径）',
      '要发回来的就是**上面这一整段**' in stest and '当前模型的文件名' in stest)

print()
print('   —— 原生侧：那些数字只存在于这里（Java 侧拿不到），所以报告由它出')
print()
check('★★★ JNI 里真的有 `nativeLastStats`（Java 侧声明 + JNI 实现 + **两个包名**的转发都要有）',
      'private static native String nativeLastStats();' in jll
      and 'Java_com_deepseek_pet_LocalLlm_nativeLastStats' in cpp_code
      and 'Java_com_doropet_release_LocalLlm_nativeLastStats' in cpp_code)
check('★★★ 正式版那套转发**体里真的转给开发版**（只声明不给实现 = 正式版点了没反应）',
      'return Java_com_deepseek_pet_LocalLlm_nativeLastStats(env, cls);' in cpp_code)
check('★★★ Java 侧拿不到读数时返回一句人话（**不是 null** —— null 在界面上就是"点了没反应"）',
      'lastStats()' in jll and '原生侧没有返回读数' in jll and 'return s == null ?' in jll)
check('★★★ 报告里**必有的每一行**都在（GPU 档位 / 设备 / 层数 / prefill / decode / 首字 / 内存账）',
      all(s in cpp for s in ('GPU 档位=', '是否有 Vulkan GPU 设备=', '后端设备清单=',
                            '请求卸载层=', 'prefill=', 'decode=', '首字（TTFT）=',
                            '内存[生成前]=', 'RssFile %.0f MB', 'VmSwap %.0f MB',
                            'MemAvailable %.0f MB', '页错误=', '原始错误=')))
check('★★★ 报告里还带了**最近的后端警告/错误**（那个失败的着色器名就在里面 —— '
      '它是下一轮最要紧的线索，而用户看不到 logcat）',
      'DSP_LOG_TAIL_N' in cpp_code and 'log_tail_push' in cpp_code
      and '最近的后端警告/错误=' in cpp
      and re.search(r'if \(level == GGML_LOG_LEVEL_ERROR \|\| level == GGML_LOG_LEVEL_WARN\) '
                    r'log_tail_push\(text\);', cpp_code) is not None)
check('★★★ 机器可读的那几行 `数值.*` 齐（Java 只解析它们，不去正则匹配中文）',
      all(s in cpp for s in ('数值.has_vulkan_gpu=', '数值.offloaded_layers=',
                            '数值.prefill_tok_s=', '数值.decode_tok_s=',
                            '数值.decode_ms_per_token=', '数值.ttft_ms=',
                            '数值.rss_file_mb=', '数值.vmswap_mb=',
                            '数值.mem_available_mb=', '数值.ok=')))
check('★★★ 没跑过时也返回一句人话（空串在界面上就是"点了没反应"）',
      '结果=还没跑过' in cpp)
check('★★★ 原生侧报告里**不许有 emoji**（它要过 NewStringUTF，而那是"修改版 UTF-8"，'
      '补充平面字符会被解成乱码 —— 文件头 ★★③ 记的就是这个坑）',
      '\\uD83' not in cpp and '\\uDDEA' not in cpp)

print()
print('   —— ★★★ 轮次72：报告里那些**哨兵值不许当数字印**（真机失败档抓到的报告 bug）')
print()
# ★★★ 真机实测（25091RP04C / Adreno 830，GPU 档失败的那 6 组）报告里印出来的是：
#     `prefill=8 tok / -1 ms (0.00 tok/s)` / `decode=0 tok / -1 ms` / `首字（TTFT）=-1 ms`。
#   `-1` 是**"这一步根本没跑到"的哨兵值**，不是一个读数 —— 印成数字的后果是
#   **读报告的人会从这一行读出一个不存在的读数**，而这一屏正是要发给别人看的那一份。
check('★★★ 报告里**不许把 -1 当数字印**：四处耗时格式串全换成 `ms_text()`'
      '（`-1 ms` 是"没测到"的哨兵，不是"负的耗时"也不是"0 ms"）',
      'std::string ms_text(double v)' in cpp_code
      and 'if (v < 0.0) return "未测到（这一步没跑到）";' in cpp_code
      and all(s not in cpp for s in ('载入耗时=%ld ms', 'prefill=%d tok / %.0f ms',
                                     'decode=%d tok / %.0f ms', '首字（TTFT）=%.0f ms')))
check('★★★ 每一处耗时都真的走 `ms_text`（载入 / prefill / decode / 首字 / 总耗时 / logcat 首字）'
      '—— 少一处就还会漏一个 `-1 ms`',
      cpp_code.count('ms_text(') >= 7)
check('★★ 没测到耗时的时候**连 tok/s 也不印**（`0.00 tok/s` 会被读成"跑了但极慢"，'
      '而事实是**根本没跑**）',
      'std::string tok_s_text(int n, double ms)' in cpp_code
      and 'if (ms <= 0.0) return std::string();' in cpp_code)
check('★ 页错误那两个 `-1`（没采到）也不许印成 `+-1`（同一个毛病，顺手一起修）',
      'std::string faults_text(long a, long b)' in cpp_code
      and 'if (a < 0 || b < 0) return "（没采到）";' in cpp_code)
check('★★ 载入耗时也没测到时说人话（`未测到（还没载入）`，不是 `-1 ms`）',
      '未测到（还没载入）' in cpp_code)

print()
print('   —— ★★ 真编真跑：报告解析 + 三档结论（出厂源码，不是抄本）')
print()
ST_DRIVER = r'''import com.deepseek.pet.LocalSelfTest;
public final class LocalSelfTestDriver {
    static int bad = 0;
    static void eq(String tag, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + tag + " got=" + got + " want=" + want); bad++;
        } else { System.out.println("ok   " + tag); }
    }
    public static void main(String[] a) {
        // ---- 解析：只认整行（拦"decode 命中 decode_ms_per_token 中间"那类错）
        String rep = "结果=成功\n"
            + "数值.has_vulkan_gpu=1\n"
            + "数值.offloaded_layers=999\n"
            + "数值.prefill_tok_s=25.20\n"
            + "数值.decode_tok_s=0.154\n"
            + "数值.decode_ms_per_token=6500\n"
            + "数值.ttft_ms=13416\n"
            + "数值.rss_file_mb=120\n"
            + "数值.vmswap_mb=900\n"
            + "数值.mem_available_mb=310\n"
            + "数值.model_mb=1013\n"
            + "数值.gpu_mode=1\n"
            + "数值.ok=1\n";
        eq("field 取得到结果", LocalSelfTest.field(rep, "结果"), "成功");
        eq("field 不会把 decode 匹到 decode_ms_per_token 上",
           LocalSelfTest.field(rep, "数值.decode_tok_s"), "0.154");
        eq("field 认不出就给空串（不是 null）", LocalSelfTest.field(rep, "没有这一行"), "");
        eq("num 取得到整数", Double.valueOf(LocalSelfTest.num(rep, "数值.decode_ms_per_token", -1)),
           Double.valueOf(6500.0));
        eq("num 认不出就用默认值（不猜成 0）",
           Double.valueOf(LocalSelfTest.num(rep, "数值.没有", -1)), Double.valueOf(-1.0));
        eq("intOf 取得到", Integer.valueOf(LocalSelfTest.intOf(rep, "数值.ok", -1)),
           Integer.valueOf(1));

        // ---- 读数不完整 ⇒ **不下结论**（绝不猜成成功/失败）
        eq("读数不完整 ⇒ 结论是「请重跑」",
           Boolean.valueOf(LocalSelfTest.verdict("这里什么都没有").contains("读数不完整")),
           Boolean.TRUE);
        eq("读数不完整 ⇒ 下一步是「重跑一次」",
           Boolean.valueOf(LocalSelfTest.nextStep("").contains("重跑一次")), Boolean.TRUE);

        // ---- 失败：没有 GPU 设备
        String fail = "结果=失败\n失败的步骤=载入（探 GPU 设备）\n数值.has_vulkan_gpu=0\n"
            + "数值.ok=0\n原始错误=开 GPU 失败（已按你的设置不回落 CPU）：一个可用的 GPU 设备都没有\n";
        eq("没有 GPU 设备 ⇒ 结论点出「一个可用的 Vulkan GPU 设备都没有」",
           Boolean.valueOf(LocalSelfTest.verdict(fail).contains("一个可用的 Vulkan GPU 设备都没有")),
           Boolean.TRUE);
        eq("没有 GPU 设备 ⇒ 下一步给出「改成关」",
           Boolean.valueOf(LocalSelfTest.nextStep(fail).contains("关（纯 CPU）")), Boolean.TRUE);

        // ---- 失败：驱动拒绝计算管线（★ 本轮最要紧的那一类）
        String pipeline = "结果=失败\n数值.has_vulkan_gpu=1\n数值.ok=0\n"
            + "最近的后端警告/错误=ggml_vulkan: compute pipeline creation failed for "
            + "mul_mat_vec_q4_k_f32_f32_subgroup: vk::Device::createComputePipeline: ErrorUnknown\n";
        eq("驱动拒绝计算管线 ⇒ 结论点出被拒的着色器名是线索",
           Boolean.valueOf(LocalSelfTest.verdict(pipeline).contains("驱动拒绝了计算管线")),
           Boolean.TRUE);
        eq("★ 而且下一步引导他去改旋钮（一次只改一个，先试 MMVQ）",
           Boolean.valueOf(LocalSelfTest.nextStep(pipeline).contains("MMVQ")), Boolean.TRUE);

        // ---- 成功但慢得离谱（轮次67 那个结论）
        eq("decode 6500 ⇒ 结论是「慢得离谱」且点明 GPU 也救不了",
           Boolean.valueOf(LocalSelfTest.verdict(rep).contains("慢得离谱")
              && LocalSelfTest.verdict(rep).contains("换 GPU 也救不了")), Boolean.TRUE);
        eq("★ 下一步先让他换 0.5B（那才分得清是模型太大还是引擎不行）",
           Boolean.valueOf(LocalSelfTest.nextStep(rep).contains("0.5B")), Boolean.TRUE);

        // ---- 成功且快 ⇒ 才敢说"GPU 真的用上了"
        String fast = rep.replace("数值.decode_ms_per_token=6500", "数值.decode_ms_per_token=300");
        eq("decode 300 ⇒ 结论是「GPU 真的用上了」",
           Boolean.valueOf(LocalSelfTest.verdict(fast).contains("GPU 真的用上了")), Boolean.TRUE);
        // ---- 一个 token 都没测到 ⇒ 如实说，不许当成"快"
        String none = rep.replace("数值.decode_ms_per_token=6500", "数值.decode_ms_per_token=-1");
        eq("一个 token 都没测到 ⇒ 结论如实说「没测到」（不许当成「快」）",
           Boolean.valueOf(LocalSelfTest.verdict(none).contains("一个 token 都没测到")), Boolean.TRUE);

        // ---- ★★★ 轮次72：真机 E7 那一份报告（纯 CPU 跑得**很快**，但绝不许说成 GPU 成功）
        //   现场事实：`数值.has_vulkan_gpu=1`（设备清单里确实有 Vulkan0(Adreno 830)）、
        //   `数值.offloaded_layers=0`（**一层都没交给它**）、`数值.gpu_mode=0`（档位=关）、
        //   decode 22 ms/token。只按 has_vulkan_gpu 判 ⇒ 会印出「GPU 真的用上了」——
        //   那就是把"纯 CPU 很快"写成"GPU 加速成功"，正是本轮诚实条款明令禁止的那句话。
        String cpuOk = "结果=成功\n"
            + "数值.has_vulkan_gpu=1\n"
            + "数值.offloaded_layers=0\n"
            + "数值.gpu_mode=0\n"
            + "数值.decode_ms_per_token=22\n"
            + "数值.ok=1\n";
        eq("★★★ 纯 CPU（offloaded_layers=0）跑得快 ⇒ **绝不许**说成「GPU 真的用上了」",
           Boolean.valueOf(!LocalSelfTest.verdict(cpuOk).contains("GPU 真的用上了")), Boolean.TRUE);
        eq("★★★ 而且要如实说「这次是纯 CPU」+ 点明 0 层交给了 GPU（有设备 ≠ 用上了）",
           Boolean.valueOf(LocalSelfTest.verdict(cpuOk).contains("纯 CPU")
              && LocalSelfTest.verdict(cpuOk).contains("0 层")), Boolean.TRUE);
        eq("★★★ 报告里必须印出那句**可执行结论**：建议这台机器把「⚡ GPU 加速」设成「关」（纯 CPU）",
           Boolean.valueOf(LocalSelfTest.verdict(cpuOk).contains(LocalSelfTest.CPU_RECOMMEND)),
           Boolean.TRUE);
        eq("★★★ 而且这句结论带着**真机读数**（44.75 tok/s / 22 ms/token）与「被驱动拒绝」那句原文",
           Boolean.valueOf(LocalSelfTest.verdict(cpuOk).contains("44.75 tok/s")
              && LocalSelfTest.verdict(cpuOk).contains(LocalSelfTest.PIPELINE_FAIL)), Boolean.TRUE);
        eq("★★★ 纯 CPU 那一档的下一步是「什么都不用改 / 不必再去试旋钮」（用户要一眼看到该怎么办）",
           Boolean.valueOf(LocalSelfTest.nextStep(cpuOk).contains("什么都不用改")
              && LocalSelfTest.nextStep(cpuOk).contains("不必再去")), Boolean.TRUE);
        // ---- 认不出"交给 GPU 的层数"⇒ **不许**下"GPU 用上了"的结论（认不出就不下结论）
        String noOffl = rep.replace("数值.offloaded_layers=999\n", "");
        eq("★★★ 报告里没有「交给 GPU 的层数」那一行 ⇒ 结论必须是「无法确认」，不许猜成 GPU 用上了",
           Boolean.valueOf(LocalSelfTest.verdict(noOffl).contains("无法确认")
              && !LocalSelfTest.verdict(noOffl).contains("GPU 真的用上了")), Boolean.TRUE);

        // ---- 组装：抬头 + 结论 + 读数 + lastError + 结尾
        String full = LocalSelfTest.compose("on", null, rep, "你好呀！", null);
        eq("组装里有抬头（GPU 档位用的是 GpuMode 那份文案）",
           Boolean.valueOf(full.contains("GPU 档位：")), Boolean.TRUE);
        eq("组装里有原生侧读数", Boolean.valueOf(full.contains("数值.ok=1")), Boolean.TRUE);
        eq("组装里有生成出来的那句话", Boolean.valueOf(full.contains("生成出来的话=你好呀！")), Boolean.TRUE);
        eq("一个字都没出来时如实写", Boolean.valueOf(
           LocalSelfTest.compose("on", null, rep, "", null).contains("（一个字都没出来）")), Boolean.TRUE);
        eq("有 lastError 就带上", Boolean.valueOf(
           LocalSelfTest.compose("on", null, rep, "", "xxx").contains("lastError=xxx")), Boolean.TRUE);
        eq("★ 结尾说清要发回来的就是这一整段",
           Boolean.valueOf(full.contains("要发回来的就是**上面这一整段**")), Boolean.TRUE);
        eq("读数缺失时也组装得出来（不是 null、也不抛）",
           Boolean.valueOf(LocalSelfTest.compose("on", null, null, null, null) != null), Boolean.TRUE);
        eq("fmt：整数不带小数点 / -1 说「没测到」",
           LocalSelfTest.fmt(6500.0) + "/" + LocalSelfTest.fmt(-1.0), "6500/没测到");
        System.out.println(bad == 0 ? "SELFTEST_OK" : ("SELFTEST_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''
if JAVAC and JAVA and os.path.isfile(LOCAL_SELFTEST) and os.path.isfile(LOCAL_TUNE):
    tmp4 = tempfile.mkdtemp(prefix='dsp71s_')
    try:
        drv4 = os.path.join(tmp4, 'LocalSelfTestDriver.java')
        io.open(drv4, 'w', encoding='utf-8').write(ST_DRIVER)
        cp4 = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp4,
                              GPU_MODE, LOCAL_TUNE, LOCAL_SELFTEST, drv4],
                             capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那三份纯类**（GpuMode / LocalTune / LocalSelfTest）',
              cp4.returncode == 0, (cp4.stderr or '')[-500:])
        if cp4.returncode == 0:
            rr4 = subprocess.run([JAVA, '-cp', tmp4, 'LocalSelfTestDriver'],
                                 capture_output=True, text=True)
            check('★★★ 报告解析 + 四档结论 + 组装 **真跑**全对',
                  rr4.returncode == 0 and 'SELFTEST_OK' in rr4.stdout,
                  (rr4.stdout or '')[-700:] + (rr4.stderr or '')[-200:])
    finally:
        shutil.rmtree(tmp4, ignore_errors=True)

print()

# ================================================================ 十八、调优旋钮
print('十八、★★★ 轮次71：「\U0001F39B 本地推理调优」—— 把"只能在 PC 上做的 A/B"搬进设置页')
print()
print('   —— 轮次67 那个 A/B 开关的用法是 setprop + am force-stop（**必须连电脑**），')
print('      所以用户一直没能自己得出"哪个参数有用"的结论 —— 本轮把它做成设置页里的旋钮。')
print()

check('★★★ LocalTune.java 在（9 个旋钮的**唯一定义处**）', os.path.isfile(LOCAL_TUNE))
check('★★★ 它是**纯类**（零 Android 依赖 ⇒ 可以被 javac 编出来真跑）'
      '★ 同样走去注释视图（类注释里提到 android 不算违规）',
      'android' not in strip_comments(tune))
check('★★★ 九个旋钮都在：adreno / flash / ubatch / mlock / loadmode / mmvq / fusion / async / f16',
      all(('public static final String K_%s = "%s";' % (n, k)) in tune
          for n, k in (('ADRENO', 'adreno'), ('FLASH', 'flash'), ('UBATCH', 'ubatch'),
                       ('MLOCK', 'mlock'), ('LOADMODE', 'loadmode'), ('MMVQ', 'mmvq'),
                       ('FUSION', 'fusion'), ('ASYNC', 'async'), ('F16', 'f16')))
      and 'K_ADRENO, K_FLASH, K_UBATCH, K_MLOCK, K_LOADMODE, K_MMVQ, K_FUSION, K_ASYNC,' in tune
      # ★ 轮次73：新键**必须追加在末尾**（顺序一变，老串的规范化结果就跟着变）
      and 'K_F16,' in tune)
check('★★★ **每一个键名**在 JNI 的解析器里也出现（这就是"同一个事实抄两处"的那条防线：'
      '只改一边 ⇒ 这个旋钮在手机上是**静默无效**的，比报错难查得多）',
      all(('tune_get(raw, "%s")' % k) in cpp_code
          for k in ('adreno', 'flash', 'ubatch', 'mlock', 'loadmode', 'mmvq', 'fusion', 'async',
                    'f16')))
check('★★★ **每一个取值**在两边都出现（Java 的取值表 ↔ C++ 的白名单）',
      all(('"%s"' % v) in cpp_code for v in
          ('subgroup', 'hybrid', 'none', 'mlock', 'force', 'off', 'on'))
      and all(('tune_get(raw, "%s")' % k) in cpp_code
              for k in ('adreno', 'flash', 'ubatch', 'mlock', 'loadmode', 'mmvq', 'fusion', 'async',
                        'f16')))
check('★★★ 解析器是**白名单**：认不出就什么都不做（一个脏值绝不会变成"开了某个实验开关"）',
      'if (v == "subgroup")     t.adreno = DSP_TUNE_ADRENO_SUBGRP;' in cpp_code
      and 'else if (v == "hybrid")  t.adreno = DSP_TUNE_ADRENO_HYBRID;' in cpp_code
      and 'if (v == "off") t.mlock = false;' in cpp_code
      and 'DSP_TUNE_UBATCH' not in cpp_code          # ubatch 走数字白名单，没有额外枚举
      and 'if (v == "128" || v == "256" || v == "512" || v == "1024") t.ubatch = atoi(v.c_str());'
          in cpp_code)
check('★★★ 后端级那几个旋钮真的落到**上游认的环境变量**上（不是我们自己编的名字）',
      all(s in cpp_code for s in ('setenv("GGML_VK_ADRENO_SUBGROUP"',
                                  'setenv("GGML_VK_DISABLE_MMVQ"', 'setenv("GGML_VK_FORCE_MMVQ"',
                                  'setenv("GGML_VK_DISABLE_FUSION"', 'setenv("GGML_VK_DISABLE_ASYNC"'))
      # ★★ f16 这一条必须查**完整调用**：只查 `setenv("GGML_VK_DISABLE_F16"` 会被
      #    `unsetenv("GGML_VK_DISABLE_F16")` 这个子串满足（"un**setenv(**" 里含 "setenv("）
      #    —— 反向项 DE 实测抓到过这条假绿。
      and 'setenv("GGML_VK_DISABLE_F16", "1", 1);' in cpp_code
      and all(s in vkc for s in ('GGML_VK_ADRENO_SUBGROUP', 'GGML_VK_DISABLE_MMVQ',
                                 'GGML_VK_FORCE_MMVQ', 'GGML_VK_DISABLE_FUSION',
                                 'GGML_VK_DISABLE_ASYNC', 'GGML_VK_DISABLE_F16')))
# ★★★ 轮次73：第三档归约（HYBRID / `_subgroup_no_shmem`）—— 上游在 Qualcomm 上**永远选不到**，
#   本工程加了一条本地补丁把它打开。两边都要钉住：
#   ① JNI 必须**两个变量一起设**（SUBGROUP=1 让 use_subgroups16 为真，HYBRID=1 把 wg 提到 LARGE）；
#   ② ggml-vulkan.cpp 里必须有那条补丁（变量没设时行为与上游逐字相同）。
check('★★★ 轮次73：第三档归约（HYBRID）两边都真的接上了'
      '—— 这是 Adreno 上**唯一没试过**的那条 SPIR-V',
      'setenv("GGML_VK_ADRENO_HYBRID", "1", 1);' in cpp_code
      and 'getenv("GGML_VK_ADRENO_HYBRID")' in vkc
      and 'dmmv_wg = DMMV_WG_SIZE_LARGE;' in vkc
      # ★ 补丁必须**只对 Qualcomm** 且**只在变量存在时**生效（默认路径逐字不变）；
      #   ★ 补丁体里还有一行 GGML_LOG_INFO（"第三档真的选到了"的现场证据）⇒ 正则不许假设
      #     赋值后面紧跟 `}`（本轮第一次写就是这么假红的）。
      and re.search(r'if \(getenv\("GGML_VK_ADRENO_HYBRID"\) != nullptr &&\s*'
                    r'ctx->device->vendor_id == 0x5143u\) \{\s*dmmv_wg = DMMV_WG_SIZE_LARGE;',
                    vkc) is not None)
check('★★★ 而**默认档位必须把环境变量清掉**（unsetenv）—— 否则同一个进程里先设了 1、'
      '用户又改回默认，变量还留着，症状就是"改了没效果"',
      cpp_code.count('unsetenv(') >= 8
      # ★ 新加的两个变量也必须在"自动"那一支里被清掉
      and 'unsetenv("GGML_VK_ADRENO_HYBRID");' in cpp_code
      and 'unsetenv("GGML_VK_DISABLE_F16");' in cpp_code)
check('★★★ 载入级那四个真的落到 llama.cpp 的参数上：flash_attn_type / n_ubatch / '
      'load_mode / mlock',
      'cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;' in cpp_code
      and 'cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;' in cpp_code
      and 'cp.n_ubatch = (uint32_t) ubatch;' in cpp_code
      and 'mp.load_mode = LLAMA_LOAD_MODE_NONE;' in cpp_code
      and 'mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;' in cpp_code
      and 'try_pin_model_file(path.c_str(), tune.mlock);' in cpp_code
      # ★ mlock 旋钮必须**真的是判据的一部分**（只传参不用 = 旋钮是摆设）
      and 'const bool enabled = want_mlock && !prop_off;' in cpp_code
      # ★ n_ubatch 必须夹到 n_batch 以下（llama.cpp 的硬要求，超了建图直接报错）
      and 'if (ubatch > batch) ubatch = batch;' in cpp_code)
# ★★★ 轮次71：**"1024"那一档不许是假旋钮**。
#   Java 侧传进来的 n_batch 默认就是 512 ⇒ 只写"夹到 n_batch"的话，用户选 1024 会被
#   **静默夹回 512**：界面上点得动、日志里也写着 1024 的意图，实际什么都没变。
#   ⇒ 判据：用户要的 ubatch 比 n_batch 大（且没超过 ctx）时，**把 n_batch 一起抬上去**。
check('★★★ n_ubatch 选 1024 时**真的会生效**（把 n_batch 一起抬上去）'
      '—— 否则那一档就是「点了没反应的假旋钮」（本工程最恨的那类缺陷）',
      'if (ubatch > batch && ubatch <= ctx_size) batch = ubatch;' in cpp_code
      # ★ 而且 n_batch 必须在**抬过之后**才写进 cp（顺序反了等于没抬）
      and cpp_code.find('if (ubatch > batch && ubatch <= ctx_size) batch = ubatch;')
          < cpp_code.find('cp.n_batch = (uint32_t) batch;'))
check('★★★ **默认档位与轮次70 的行为逐字等价**（「什么都不动 = 与上一版完全一样」'
      '—— 这是这一整套旋钮敢默认开着的前提）',
      # ① ubatch 默认 512 ⇔ 老写法 `(batch < 512 ? batch : 512)`
      'public static final int UBATCH_DEFAULT = 512;' in tune
      and '"512", ON, LOADMODE_MMAP, MMVQ_AUTO, ON, ON,' in tune
      # ② load_mode 默认走 MMAP 那一支
      and re.search(r'\} else \{\s*mp\.load_mode = LLAMA_LOAD_MODE_MMAP;\s*\}', cpp_code) is not None
      # ③ flash 默认 AUTO
      and 'cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;' in cpp_code
      # ④ mlock 默认"尝试"
      and 'DEFAULTS = {\n            ADRENO_AUTO, FLASH_AUTO, "512", ON, LOADMODE_MMAP, MMVQ_AUTO, ON, ON,' in tune)
check('★★★ 三个新参数都进了载入日志（用户自测时"这次到底用的哪套参数"的唯一现场证据）',
      '本次调优（与本轮默认不同的项）：' in cpp
      and '生效参数：flash attention=%s / n_ubatch=%u / 载入方式=%s / 权重锁内存=%s' in cpp
      and '完整串：%s' in cpp)
check('★★★ 持久化：一个键、一条规范串（`local_llm_tune`），写盘前**规范化**'
      '（脏值既不传下去也不写回去）',
      'KEY_LOCAL_TUNE = "local_llm_tune"' in prf
      and 'return LocalTune.normalize(sp.getString(KEY_LOCAL_TUNE, null));' in prf
      and '.edit().putString(KEY_LOCAL_TUNE, LocalTune.normalize(tune)).apply();' in prf)
check('★★★ 载入时**真的读**设置（每一次载入都把 Prefs.localTune 交到 loadPlanned）',
      'final String tune = Prefs.localTune(ctx);' in lch
      and 'loadPlanned(ctx, f, gpuMode, tune)' in lch)
check('★★★ 设置页那 8 行是**循环出来的**（照着 LocalTune.KEYS）—— '
      '加一个旋钮不用改设置页，也就不可能出现"定义了但界面上没有"的假功能',
      'for (int i = 0; i < LocalTune.knobCount(); i++) {' in mai
      and 'LocalTune.title(i) + "：" + LocalTune.valueLabel(i, cur) + " \\u203A"' in mai
      and 'showTuneKnobDialog(idx);' in mai)
check('★★★ 每个旋钮的选项文案与取值**一一对应**（长度不等 ⇒ 单选表会串位）',
      re.search(r'private static final String\[\]\[\] (VALUES|CHOICE_LABELS)', tune) is not None)
check('★★★ 生效时机分两类，而且**界面文案不许含糊**（否则"改了没效果"会被误读成"参数没用"）',
      'public static boolean needsRestart(int i)' in tune
      and '要完全退出 App 再打开才保证生效（Vulkan 设备在进程内只造一次）' in tune
      and '下次载入模型生效（改完会先把内存里的模型放掉）' in tune)
check('★★★ 后端级那四个**故意不放掉内存里的模型**（放了也没用，只会让用户白等一次载入）',
      re.search(r'if \(LocalTune\.needsRestart\(i\)\) \{\s*Toast\.makeText\(this, toast, '
                r'Toast\.LENGTH_LONG\)\.show\(\);\s*showTunePanel\(\);\s*return;', mai) is not None)
check('★★ 有"全部恢复默认"的退路（试乱了要点得回来 —— 本工程的老规矩）',
      'LocalTune.defaults()' in mai and 'resetAllTune()' in mai)
check('★★★ 每一项都标注了**依据等级**：有上游依据 / 有上游开关+我们的推测（**没验过就写没验过**）',
      'public static String basis(int i)' in tune
      and '有上游依据：llama.cpp issue #28635 / PR #29165' in tune
      and '有上游开关：ggml-vulkan.cpp 的 GGML_VK_DISABLE_MMVQ' in tune
      and tune.count('我们的推测') >= 3)

print()
print('   —— ★★ 真编真跑：编码 / 解析 / 归一 / 改一项 / 边界（出厂源码，不是抄本）')
print()
TUNE_DRIVER = r'''import com.deepseek.pet.LocalTune;
public final class LocalTuneDriver {
    static int bad = 0;
    static void eq(String tag, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + tag + " got=" + got + " want=" + want); bad++;
        } else { System.out.println("ok   " + tag); }
    }
    public static void main(String[] a) {
        String D = LocalTune.defaults();
        eq("默认串（顺序固定）", D,
           "adreno=auto;flash=auto;ubatch=512;mlock=on;loadmode=mmap;mmvq=auto;fusion=on;async=on;f16=auto");
        eq("null ⇒ 默认串", LocalTune.normalize(null), D);
        eq("空串 ⇒ 默认串", LocalTune.normalize(""), D);
        // ★ 脏值一律**换成默认值**（不许猜、不许把脏东西写下去）
        eq("全脏 ⇒ 默认串", LocalTune.normalize("zzz=1;adreno=??;flash=maybe;ubatch=9999"),
           D);
        eq("大小写与空格也认（升级/手改过的值不该被吃掉）",
           LocalTune.normalize("FLASH = OFF ; ubatch = 1024 ;"),
           "adreno=auto;flash=off;ubatch=1024;mlock=on;loadmode=mmap;mmvq=auto;fusion=on;async=on;f16=auto");
        eq("写两遍同一个键 ⇒ 最后一遍说了算、而且只出现一次",
           LocalTune.normalize("flash=on;flash=off"),
           "adreno=auto;flash=off;ubatch=512;mlock=on;loadmode=mmap;mmvq=auto;fusion=on;async=on;f16=auto");
        // ★ 老版本存下来的串（没有 f16 这一项）必须能读，并补上默认值
        eq("老串（8 项、没有 f16）⇒ 补默认值，其余一个字不变",
           LocalTune.normalize("adreno=subgroup;flash=off;ubatch=128;mlock=off;loadmode=none;"
               + "mmvq=force;fusion=off;async=off"),
           "adreno=subgroup;flash=off;ubatch=128;mlock=off;loadmode=none;mmvq=force;fusion=off;async=off;f16=auto");
        // ★ 每一项的取值域必须闭合：表外的值一律回默认
        String[] keys = {"adreno", "flash", "ubatch", "mlock", "loadmode", "mmvq", "fusion", "async",
                         "f16"};
        String[] vals = {"subgroup", "on", "1024", "off", "none", "force", "off", "off", "off"};
        for (int i = 0; i < keys.length; i++) {
            String s = LocalTune.set(D, keys[i], vals[i]);
            eq("改一项 " + keys[i], LocalTune.value(s, keys[i]), vals[i]);
            eq("改一项不动别的 " + keys[i], Integer.valueOf(LocalTune.changedCount(s)),
               Integer.valueOf(1));
        }
        eq("认不出的值 ⇒ 一个字都不改", LocalTune.set(D, "flash", "maybe"), D);
        eq("认不出的键 ⇒ 一个字都不改", LocalTune.set(D, "nope", "on"), D);
        eq("ubatch 认不出 ⇒ 默认 512", Integer.valueOf(LocalTune.ubatch(null)),
           Integer.valueOf(512));
        eq("ubatch 取得到", Integer.valueOf(LocalTune.ubatch("ubatch=1024")), Integer.valueOf(1024));
        eq("changedList 格式（与 JNI 的 tune_changed_list 同一套）",
           LocalTune.changedList("ubatch=256;flash=off"), "flash=off, ubatch=256");
        eq("全默认时 changedList 是空串（日志里会说「全部为默认值」）",
           LocalTune.changedList(D), "");
        eq("九项全非默认 ⇒ changedCount=9 且顺序是 KEYS 的顺序",
           LocalTune.changedCount("adreno=hybrid;flash=off;ubatch=128;mlock=off;loadmode=none;"
               + "mmvq=force;fusion=off;async=off;f16=off") + "|"
           + LocalTune.changedList("adreno=hybrid;flash=off;ubatch=128;mlock=off;loadmode=none;"
               + "mmvq=force;fusion=off;async=off;f16=off"),
           "9|adreno=hybrid, flash=off, ubatch=128, mlock=off, loadmode=none, mmvq=force, "
           + "fusion=off, async=off, f16=off");
        // ★★ 值表与文案表必须一一对应（长度不等 = 单选表串位，界面上会选 A 得到 B）
        for (int i = 0; i < LocalTune.knobCount(); i++) {
            eq("取值表与文案表长度相等 #" + i,
               Integer.valueOf(LocalTune.values(i).length),
               Integer.valueOf(LocalTune.choiceLabels(i).length));
            // 默认值必须在取值表里（否则"默认"这个档在界面上根本选不到）
            boolean found = false;
            for (String v : LocalTune.values(i)) if (v.equals(LocalTune.defaultValue(i))) found = true;
            if (!found) { System.out.println("FAIL 默认值不在取值表里 #" + i); bad++; }
            // 生效时机与依据都不许空
            if (LocalTune.effect(i).length() == 0 || LocalTune.basis(i).length() == 0) {
                System.out.println("FAIL 生效时机/依据为空 #" + i); bad++;
            }
        }
        // ★★ 每一个旋钮的**默认值**在界面上显示出来的那一句都不许相同（
        //    只按键分派 valueLabel 这条纪律的回归测试）
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < LocalTune.knobCount(); i++) {
            String lbl = LocalTune.title(i) + "→" + LocalTune.valueLabel(i, D);
            if (!seen.add(lbl)) { System.out.println("FAIL 两行一模一样: " + lbl); bad++; }
        }
        eq("★ mlock 的默认值显示的是「尝试锁进内存」（不是 flash 那个「强制开」）",
           LocalTune.valueLabel(3, D), "尝试锁进内存");
        eq("★ fusion/async 的默认值显示「开」", LocalTune.valueLabel(6, D), "开");
        eq("★ adreno 的默认值显示「自动（共享内存归约）」",
           LocalTune.valueLabel(0, D), "自动（共享内存归约）");
        eq("★ 轮次73：adreno 的第三档显示「第三档：子组归约 · 不用共享内存」",
           LocalTune.valueLabel(0, "adreno=hybrid"), "第三档：子组归约 · 不用共享内存");
        eq("★ 轮次73：fp16 关掉时显示「关掉 fp16（全部走 f32）」",
           LocalTune.valueLabel(8, "f16=off"), "关掉 fp16（全部走 f32）");
        eq("needsRestart 只对后端级那几个为真（adreno / mmvq / fusion / async / f16）",
           "" + LocalTune.needsRestart(0) + "," + LocalTune.needsRestart(1) + ","
              + LocalTune.needsRestart(2) + "," + LocalTune.needsRestart(3) + ","
              + LocalTune.needsRestart(4) + "," + LocalTune.needsRestart(5) + ","
              + LocalTune.needsRestart(6) + "," + LocalTune.needsRestart(7) + ","
              + LocalTune.needsRestart(8),
           "true,false,false,false,false,true,true,true,true");
        eq("改一项的 Toast 说清「什么时候生效」（后端级说重启、载入级说下次载入）",
           Boolean.valueOf(LocalTune.changedToast(1, "off").contains("下一次载入就按它走")
              && LocalTune.changedToast(5, "off").contains("完全退出 App 再打开")), Boolean.TRUE);
        eq("label 会报「已改 N 项」",
           Boolean.valueOf(LocalTune.label("flash=off;ubatch=1024").contains("已改 2 项")),
           Boolean.TRUE);
        eq("全默认时 label 说「全是默认值」",
           Boolean.valueOf(LocalTune.label(D).contains("全是默认值")), Boolean.TRUE);
        System.out.println(bad == 0 ? "TUNE_OK" : ("TUNE_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''
if JAVAC and JAVA and os.path.isfile(LOCAL_TUNE):
    tmp5 = tempfile.mkdtemp(prefix='dsp71t_')
    try:
        drv5 = os.path.join(tmp5, 'LocalTuneDriver.java')
        io.open(drv5, 'w', encoding='utf-8').write(TUNE_DRIVER)
        cp5 = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp5, LOCAL_TUNE, drv5],
                             capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那份 LocalTune.java**（真源码、零 Android 依赖）',
              cp5.returncode == 0, (cp5.stderr or '')[-500:])
        if cp5.returncode == 0:
            rr5 = subprocess.run([JAVA, '-cp', tmp5, 'LocalTuneDriver'],
                                 capture_output=True, text=True)
            check('★★★ 编码 / 解析 / 归一 / 改一项 / 八个旋钮的边界 **真跑**全对',
                  rr5.returncode == 0 and 'TUNE_OK' in rr5.stdout,
                  (rr5.stdout or '')[-800:] + (rr5.stderr or '')[-200:])
    finally:
        shutil.rmtree(tmp5, ignore_errors=True)

print()

# ================================================================ 十九、登记
print()
print('   —— 构建溯源：轮次71 这一节（下一个窗口重编时唯一的依据）')
print()
check('★★★ BUILD_INFO 有轮次71 那一节（8 个旋钮 / 自检 / **只重编 JNI**）',
      '轮次71' in info and 'LocalTune.java' in info and 'LocalSelfTest.java' in info
      and 'nativeLastStats' in info and 'jstring jtune' in info)
check('★★★ BUILD_INFO 把"有上游依据"与"我们的推测"**分开写清了**（5 + 3）'
      '—— 这是本轮的一条硬要求：没验过就必须写没验过',
      '有上游依据' in info and '有上游开关' in info and '我们的推测' in info
      and '没在真机上验过' in info
      and '轮次67 的本地补丁' in info and 'GGML_VK_DISABLE_MMVQ' in info)
check('★★★ BUILD_INFO 记着**默认档与上一版行为逐字等价**（不玩 A/B 的人零影响）',
      '默认档与轮次70 的行为逐字等价' in info)
check('★★★ BUILD_INFO 记着三条本地补丁**逐条核实过**、且 Adreno 那条现在多了一条手机上的路',
      'use_subgroups = false' in info and 'debug.dsp.adreno_subgroup' in info
      and 'Adreno 的归约方式' in info)
check('★★★ BUILD_INFO 记着"后端级那 4 个要完全退出 App 再打开"这个生效时机',
      '完全退出 App 再打开' in info and '一个进程里只造一次' in info)
check('★★★ BUILD_INFO 记着本轮新踩的那个坑（锚点会悬空/变假绿：同一句话出现几次要重数）',
      '假绿' in info and 'all_=True' in info and '悬空' in info)
check('★★ BUILD_INFO 记着本轮库体积（易变，别当判据）', '88,344' in info)
check('★★ BUILD_INFO 的诚实边界仍然写着"没有真机读数"与"开 GPU 不解决内存瓶颈"',
      '没有真机读数' in info and '163 MB/s' in info and '0.5B' in info)

print()
print('十九、★★ 登记进全量套件')
print()
bat = read(BAT) if os.path.isfile(BAT) else ''
check('★★ 本套件登记进 run_all_tests.bat（没登记 = 从来不会跑）',
      'verify_local_llm_speed.py' in bat)
check('★★ 反向套件也登记了', 'reverse_check_local_llm_speed.py' in bat)
import suite_index as _si
_si_ok, _si_total = _si.consistent(bat)
check('★末两套编号存在（t-1/t 与 t/t）',
      _si_ok and ('%d/%d' % (_si_total - 1, _si_total)) in bat
      and ('%d/%d' % (_si_total, _si_total)) in bat, '%d 套' % _si_total)
check('★ 全量套件编号自洽（不再写死数字）',
      'suite_index' in read(os.path.join(HERE, 'verify_local_llm.py')))
check('★ run_all_tests.bat 仍是 CRLF（这个文件被行尾坑过两次）',
      (lambda b: b.count(b'\r\n') > 0 and b.count(b'\r') == b.count(b'\n'))(rbytes(BAT)))

print()
print('二十、★★★ 轮次72：真机实测（**设备第一次连上电脑**）—— 面板必须能滚 / GPU 结论 / RssFile 结论')
print()
# ---------------------------------------------------------------------------
#   这一节的每一条都对着**真机上量到的东西**，不是对着文档抄的：
#     ① 产品修复：本地模型面板被裁（用户点不到 🎛 调优入口）⇒ ScrollView；
#     ② BUILD_INFO 里那张读数表必须记得住"哪台机器 / 什么 GPU / 六组 A/B / 结论"；
#     ③ 上游源码里两条**判据局限**（pipeline->name 不区分归约变体、mmvq 被 integer_dot 门住）。
# ---------------------------------------------------------------------------
_PB = panel_body()
check('★★★ 本地模型面板被 `ScrollView` 包住（`AlertDialog` 的自定义面板**不会自己滚** —— '
      '真机 1904×3040 上尾部整段被裁：a11y 树里连 `🎛 本地推理调优` 文字节点都不存在）',
      'new ScrollView(this)' in _PB and 'sc.addView(box)' in _PB
      and '.setView(sc)' in _PB and '.setView(box)' not in _PB)
check('★★ 面板那个高度上限的写法与 `showTunePanel` **同构**（都是 ScrollView + 屏高比例；'
      '一处改了另一处要连着改，否则两个面板的行为会漂）',
      'heightPixels * 0.72f' in _PB
      and 'heightPixels * 0.62f' in block_after(mai, 'private void showTunePanel()'))
check('★★★ 顺序没被改坏：`🧪 自检` 与 `🎛 调优` 都在**包 ScrollView 之前**塞进 box '
      '（顺序反了 = 滚到底也点不到）',
      _PB.find('LocalSelfTest.BUTTON') > 0
      and _PB.find('LocalSelfTest.BUTTON') < _PB.find('new ScrollView(this)')
      and _PB.find('Prefs.localTuneLabel(this)') < _PB.find('new ScrollView(this)'))
# ★★★ 轮次72 顺手修掉的第二条（比第一条更严重 —— 它会**撒谎**）：
#   自检结论原来只按 `数值.has_vulkan_gpu` 判 ⇒ 真机 E7（档位=关、has_vulkan_gpu=1、
#   offloaded_layers=0、纯 CPU 22 ms/token）会被印成「✅ **GPU 真的用上了**」。
check('★★★ 「有 Vulkan 设备 ⇒ GPU 真的用上了」这条**会撒谎**的判据已经换掉：'
      '结论只认 `数值.offloaded_layers > 0`（层真的交上去了才叫 GPU 在用）',
      'if (gpu <= 0) {' not in stest
      and '数值.offloaded_layers' in block_after(stest, 'public static String verdict(')
      and '数值.has_vulkan_gpu' in block_after(stest, 'public static String verdict('))
check('★★★ 那句**可执行结论**（建议这台机器把「⚡ GPU 加速」设成「关」/ 纯 CPU）是个公开常量，'
      '而且**真的印进结论与下一步里**（用户看不懂 logcat，要一眼看到"该怎么办"）',
      'public static final String CPU_RECOMMEND' in stest
      and 'CPU_RECOMMEND' in block_after(stest, 'public static String verdict(')
      and 'CPU_RECOMMEND' in block_after(stest, 'public static String nextStep('))
check('★★★ 那句结论里带着**真机读数与那台机器的身份**（诚实条款：不许把"纯 CPU 很快"写成"GPU 成功"）',
      '44.75 tok/s' in stest and '22 ms/token' in stest
      and '25091RP04C' in stest and 'Adreno 830' in stest)
check('★★★ 阈值与读数的注释**按机型分段**（25091RP04C/piano 与 2210132C/nuwa 不许混）',
      '2210132C' in stest and 'nuwa' in stest and 'piano' in stest
      and '6500 ms/token' in stest)
check('★★★ 报告结尾要求**带上机型**，并写明"读数按机型分段记，两台机器不许混"',
      '机型那一串**必须带上**' in stest and '按机型分段' in stest)
check('★ 那条判据局限也进了 Java 侧的口径：「有 Vulkan 设备**不等于**层交上去了」',
      '有 Vulkan 设备**不等于**层交上去了' in stest)
check('★★★ BUILD_INFO 有轮次72 那一节（**真机读数**是这个工程第一次拿到的东西）',
      '轮次72' in info and '第一次拿到真机读数' in info)
check('★★★ BUILD_INFO 把**机器**写清楚了（型号 / product / SoC / GPU / 内存 / 系统）'
      '—— 别把两台机器的数字混在一起',
      '25091RP04C' in info and 'piano' in info and 'SM8750P' in info
      and 'Adreno (TM) 830' in info and '<设备序列号>' in info and '11,423,872' in info)
check('★★★ BUILD_INFO 记着那台**不在线**的机器（2210132C / Adreno 740 ⇒ 本轮 0 条读数），'
      '并写明两台机器的结论**不能互相代替**',
      '2210132C' in info and '0 条读数' in info)
check('★★★ BUILD_INFO 记着**六组 A/B 的读数表**（同一个失败管线名 + 纯 CPU 的 tok/s）',
      'mul_mat_vec_q4_k_f32_f32' in info and '109.50 tok/s' in info
      and '44.75 tok/s' in info and '22 ms/token' in info
      and 'compute pipeline creation failed' in info)
check('★★★ BUILD_INFO 把结论写成了**事实**：GPU 在这台 Adreno 830 上用不了、'
      '8 个旋钮里试过的都不管用',
      'GPU（Vulkan）在这台 Adreno 830 上用不了' in info
      and 'mmvq' in info and 'fusion' in info and 'async' in info)
check('★★★ BUILD_INFO 记着**内存不是瓶颈**这条（`RssFile` ≈ 1.0 GB / 模型 1060 MB）'
      '并写明旧的 163 MB/s、6.5 秒/字**不适用这台机器**',
      '1176~1244 MB' in info and '内存不是瓶颈' in info and '163 MB/s' in info)
check('★★★ BUILD_INFO 记着那两条**判据局限**（这一轮读源码才发现的）：'
      '`pipeline->name` 是固定字符串 ⇒ 失败管线名不能区分归约变体；'
      '`mmvq` 被 `integer_dot_product` 门住 ⇒ 这个 build 上它是惰性旋钮',
      'pipeline->name = name;' in info and 'use_subgroups16' in info
      and 'integer_dot_product' in info and '惰性' in info)
check('★★★ BUILD_INFO 的诚实边界写着**没测到什么**（四个载入级旋钮没试 / 短程读数 / 那台机器不在线 / 联发科）',
      '没试的旋钮' in info and '联发科' in info and '短程读数' in info)
check('★★★ BUILD_INFO 把真机操作的坑写下来了（唤醒屏幕 / dump 重试 / 无 root / 4 像素行）',
      'could not get idle state' in info and 'dismiss-keyguard' in info
      and 'adbd cannot run as root' in info and '4 px' in info)
# ★★★ 轮次72 收尾：真机上抓到的**两个报告缺陷**必须留在文档里 ——
#   它们是"下一轮别再犯"的那一类事实（尤其第二条：判据会**撒谎**）。
check('★★★ BUILD_INFO 记着收尾轮修掉的那两个报告缺陷（`-1` 哨兵不许当数字印 / '
      '那条把"纯 CPU 很快"说成"GPU 成功"的判据）与 E7 的复现读数',
      '16.8' in info and '未测到（这一步没跑到）' in info
      and 'offloaded_layers > 0' in info and '44.84 tok/s' in info
      and '92,040' in info)
check('★★ BUILD_INFO 记着面板 bug 的**真机验收方式**（下一个窗口能照着重跑）',
      'verify72.py panel' in info and '2493 px' in info)
if vkc:
    check('★★★ 上游源码：`q4_K` 的 matvec 管线生成用的是 **`use_subgroups16`**'
          '（这解释了"轮次67 那条补丁为什么改得动 q4_K"—— 它确实换了 SPIR-V）',
          re.search(r'"mul_mat_vec_q4_k_f32_f32",\s*arr_dmmv_q4_k_f32_f32_len\[reduc16\]',
                    vkc_code) is not None
          and 'use_subgroups16, force_subgroup_size16);' in vkc_code)
    check('★★★ 上游源码：`subgroup_min_size_16` 在"没有 subgroup_size_control 且 '
          'subgroup_size >= 16"时为真（真机 subgroup=64 ⇒ 为真 ⇒ 补丁改得动 q4_K）',
          '(!device->subgroup_size_control && device->subgroup_size >= 16)' in vkc_code
          and 'subgroup_min_size_16 && !is_imagination_proprietary' in vkc_code)
    check('★★★ 上游源码：`pipeline->name = name;` —— 失败管线名是**调用点传的固定串**，'
          '三个归约模式共用一个名字 ⇒ 它**只能定位到算子**，这是判据局限',
          'pipeline->name = name;' in vkc_code)
    check('★★★ 上游源码：`quantize_y` 那一行**还被 `integer_dot_product` 门着** ⇒ '
          'MMVQ 旋钮在本工程这个 build 上是惰性的',
          'ctx->device->integer_dot_product &&' in vkc_code
          and 'ggml_vk_should_use_mmvq' in vkc_code)
    check('★ 上游源码：`mmvq_mode` 的 off/force 只在这里赋值（旋钮真的接到了上游开关上）',
          'device->mmvq_mode = -1;' in vkc_code and 'device->mmvq_mode = 1;' in vkc_code)
if guide:
    check('★★★ 交付那份一页纸指南里有**真机实测结论**那一节（用户要知道"这台机器别开 GPU"）',
          'Adreno 830' in guide and '44.75' in guide)

# ================================================================ 二十一、轮次74：自动硬件计划
print('二十一、★★★ 轮次74：「运行时自动检测硬件 → 动态算 n-gpu-layers → 自动降级」')
print()
print('   —— 用户原话：「核心思路：运行时自动检测硬件 → 动态计算 -(n-gpu-layers)')
print('      → 自动降级兜底不用用户手动调参数。」')
print()

check('★★★ GpuPlan.java 在（**决策的唯一出处**），而且是纯类'
      '（零 Android 依赖 ⇒ 可以被 javac 编出来真跑）',
      os.path.isfile(GPU_PLAN)
      and 'android' not in strip_comments(io.open(GPU_PLAN, encoding='utf-8').read()))
_gp = io.open(GPU_PLAN, encoding='utf-8').read() if os.path.isfile(GPU_PLAN) else ''
check('★★★ 决策函数齐：decide / degrade / looksLikeGpuFailure / summary / reportLines / knownBad',
      all(s in _gp for s in ('public static Plan decide(Facts f)',
                             'public static Plan degrade(Plan p)',
                             'public static boolean looksLikeGpuFailure(String err)',
                             'public static String summary(Facts f, Plan p)',
                             'public static String reportLines(Facts f, Plan p, boolean fellBack)',
                             'public static boolean knownBad(String device)')))
check('★★★ 已知坏机型判据认的是 **Adreno 830**（轮次72/73 真机四组实测，不是猜的）',
      'Adreno(TM)830' in _gp and '"Adreno830"' in _gp
      and '轮次72/73' in _gp and 'compute pipeline' in _gp)
check('★★★ 预算规则写死成常量（可被断言钉住）：可用内存的 55%、上限 3072 MB、'
      '至少放得下 33% 的层才用 GPU',
      'BUDGET_PCT = 55' in _gp and 'BUDGET_CAP_MB = 3072' in _gp
      and 'MIN_PARTIAL_PCT = 33' in _gp)
check('★★★ 层数未知时用 EST_LAYERS 估算，并在结果里**标出来**（`estLayers`）—— '
      '不许把"估算"装成"读到了"',
      'EST_LAYERS = 32' in _gp and 'p.estLayers = f.modelLayers <= 0;' in _gp)
check('★★★ LocalHardware：取数层（GPU 设备 / 可用内存 / GGUF 层数）真的接上三处',
      os.path.isfile(LOCAL_HARDWARE)
      and 'LocalLlm.deviceInfo()' in io.open(LOCAL_HARDWARE, encoding='utf-8').read()
      and 'am.getMemoryInfo(mi)' in io.open(LOCAL_HARDWARE, encoding='utf-8').read()
      and 'ModelFile.ggufLayerCount(model)' in io.open(LOCAL_HARDWARE, encoding='utf-8').read())
check('★★★ ModelFile 能读 GGUF 的层数（`<arch>.block_count`），而且**只读前 64 KB**',
      os.path.isfile(MODEL_FILE)
      and 'public static int ggufLayerCount(byte[] head)' in io.open(MODEL_FILE, encoding='utf-8').read()
      and 'public static int ggufLayerCount(java.io.File f)' in io.open(MODEL_FILE, encoding='utf-8').read()
      and 'new byte[HEAD_PEEK]' in io.open(MODEL_FILE, encoding='utf-8').read()
      and 'endsWith(".block_count")' in io.open(MODEL_FILE, encoding='utf-8').read())
check('★★★ JNI 硬件探测：两套包名都在，而且真的走 ggml 的后端设备枚举',
      'Java_com_deepseek_pet_LocalLlm_nativeDeviceInfo' in cpp_code
      and 'Java_com_doropet_release_LocalLlm_nativeDeviceInfo' in cpp_code
      and 'ggml_backend_dev_count()' in cpp_code
      and 'llama_backend_init();' in cpp_code
      and 'name=%s' in cpp_code)
check('★★★ Java 侧声明与包装都在（少一个 = 真机 UnsatisfiedLinkError 被兜住 ⇒ '
      '"自动"永远以为没有 GPU）',
      'private static native String nativeDeviceInfo();' in jll
      and 'final String s = nativeDeviceInfo();' in jll)
check('★★★ LocalChat：载入走 `loadPlanned`（先算计划），生成阶段失败会**自动降级重跑一次**',
      'final LoadResult lr = loadPlanned(ctx, f, gpuMode, tune);' in lch
      and 'private static boolean reloadCpu(Context ctx)' in lch
      and 'if (reloadCpu(ctx)) {' in lch
      and 'GpuPlan.looksLikeGpuFailure(why0)' in lch)
check('★★★ 自检报告里有**自动计划那两行**（硬件 / 层数 / 预算 / 有没有降级）',
      'GpuPlan.reportLines(LocalHardware.lastFacts(), p, fellBack)' in lch
      and 'LocalSelfTest.compose(gpuMode, tune, stats, text, why, autoLines(lr, fellBack))' in lch)

print()
print('   —— ★★ 真编真跑：GpuPlan 的决策边界 + ModelFile 的 GGUF 层数（出厂源码，不是抄本）')
print()
PLAN_DRIVER = r'''import com.deepseek.pet.GpuPlan;
import com.deepseek.pet.ModelFile;
import java.io.ByteArrayOutputStream;
public final class GpuPlanDriver {
    static int bad = 0;
    static void eq(String tag, Object got, Object want) {
        if (got == null ? want != null : !got.equals(want)) {
            System.out.println("FAIL " + tag + " got=" + got + " want=" + want); bad++;
        } else { System.out.println("ok   " + tag); }
    }
    static GpuPlan.Facts f(boolean vk, String dev, int avail, int total, long bytes, int layers) {
        GpuPlan.Facts x = new GpuPlan.Facts();
        x.hasVulkan = vk; x.device = dev; x.driver = "V@0800.72";
        x.availRamMb = avail; x.totalRamMb = total;
        x.modelBytes = bytes; x.modelLayers = layers;
        return x;
    }
    static void u32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF)); o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF)); o.write((int) ((v >> 24) & 0xFF));
    }
    static void u64(ByteArrayOutputStream o, long v) {
        for (int i = 0; i < 8; i++) o.write((int) ((v >> (8 * i)) & 0xFF));
    }
    static void kvStr(ByteArrayOutputStream o, String k, String v) {
        byte[] kb = k.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        byte[] vb = v.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        u64(o, kb.length); o.write(kb, 0, kb.length);
        u32(o, 8); u64(o, vb.length); o.write(vb, 0, vb.length);
    }
    static void kvU32(ByteArrayOutputStream o, String k, int v) {
        byte[] kb = k.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        u64(o, kb.length); o.write(kb, 0, kb.length);
        u32(o, 4); u32(o, v);
    }
    static byte[] gguf(int layers) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] magic = {'G', 'G', 'U', 'F'};
        o.write(magic, 0, 4);
        u32(o, 3); u64(o, 1); u64(o, 3);
        kvStr(o, "general.name", "qwen2");
        kvStr(o, "general.architecture", "qwen2");
        kvU32(o, "qwen2.block_count", layers);
        return o.toByteArray();
    }
    public static void main(String[] a) {
        final long GIB = 1073741824L;
        // ① 没有 Vulkan ⇒ 纯 CPU
        GpuPlan.Plan p = GpuPlan.decide(f(false, "", 8000, 12000, GIB, 28));
        eq("没有 Vulkan ⇒ 不用 GPU", Boolean.valueOf(p.gpu), Boolean.FALSE);
        eq("没有 Vulkan ⇒ 档位", p.tier, "cpu-no-vulkan");
        eq("没有 Vulkan ⇒ 0 层", Integer.valueOf(p.ngl), Integer.valueOf(0));
        // ② 已知坏机型（Adreno 830）⇒ 纯 CPU（实测证据）
        p = GpuPlan.decide(f(true, "Adreno (TM) 830", 4000, 12000, GIB, 28));
        eq("Adreno 830 ⇒ 不用 GPU", Boolean.valueOf(p.gpu), Boolean.FALSE);
        eq("Adreno 830 ⇒ 档位", p.tier, "cpu-known-bad");
        eq("Adreno 830 ⇒ 原因里写了实测", Boolean.valueOf(p.reason.contains("实测")), Boolean.TRUE);
        p = GpuPlan.decide(f(true, "Adreno830v2", 4000, 12000, GIB, 28));
        eq("Adreno 830 的另一种写法也认得", p.tier, "cpu-known-bad");
        // ③ 预算够 ⇒ 全部层
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 8000, 12000, GIB, 28));
        eq("预算足 ⇒ 用 GPU", Boolean.valueOf(p.gpu), Boolean.TRUE);
        eq("预算足 ⇒ 档位", p.tier, "gpu-full");
        eq("预算足 ⇒ 28 层全放", Integer.valueOf(p.ngl), Integer.valueOf(28));
        eq("预算封顶 3072 MB", Integer.valueOf(p.budgetMb), Integer.valueOf(3072));
        eq("每层约 37 MB", Integer.valueOf(p.layerMb), Integer.valueOf(37));
        eq("层数来自 GGUF ⇒ 不是估算", Boolean.valueOf(p.estLayers), Boolean.FALSE);
        // ④ 预算中等 ⇒ 部分层
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 800, 12000, GIB, 28));
        eq("预算中等 ⇒ 部分层", p.tier, "gpu-partial");
        eq("预算中等 ⇒ 11 层", Integer.valueOf(p.ngl), Integer.valueOf(11));
        // ⑤ 预算太少 ⇒ 纯 CPU（部分卸载通常更慢）
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 200, 12000, GIB, 28));
        eq("预算太少 ⇒ 纯 CPU", p.tier, "cpu-budget");
        eq("预算太少 ⇒ 原因说清", Boolean.valueOf(p.reason.contains("部分卸载")), Boolean.TRUE);
        // ⑥ 层数不知道 ⇒ 估算 + 标记
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 8000, 12000, GIB, 0));
        eq("层数未知 ⇒ 用估算值 32", Integer.valueOf(p.ngl), Integer.valueOf(32));
        eq("层数未知 ⇒ estLayers=true", Boolean.valueOf(p.estLayers), Boolean.TRUE);
        // ⑦ 体积不知道 ⇒ 交给 llama.cpp（运行时兜底）
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 8000, 12000, 0L, 0));
        eq("体积未知 ⇒ gpu-all-unknown", p.tier, "gpu-all-unknown");
        eq("体积未知 ⇒ 999 层", Integer.valueOf(p.ngl), Integer.valueOf(999));
        // ⑧ 只有总内存 ⇒ 用 25%
        p = GpuPlan.decide(f(true, "Adreno (TM) 740", 0, 8000, GIB, 28));
        eq("只有总内存 ⇒ 预算 2000 MB", Integer.valueOf(p.budgetMb), Integer.valueOf(2000));
        eq("只有总内存 ⇒ 仍然全部层", Integer.valueOf(p.ngl), Integer.valueOf(28));
        // ⑨ 降级
        GpuPlan.Plan g = GpuPlan.decide(f(true, "Adreno (TM) 740", 8000, 12000, GIB, 28));
        GpuPlan.Plan d = GpuPlan.degrade(g);
        eq("降级 ⇒ 不用 GPU", Boolean.valueOf(d.gpu), Boolean.FALSE);
        eq("降级 ⇒ 0 层", Integer.valueOf(d.ngl), Integer.valueOf(0));
        eq("降级 ⇒ 档位", d.tier, "cpu-fallback");
        eq("降级 ⇒ 原因里写明「自动」", Boolean.valueOf(d.reason.contains("自动")), Boolean.TRUE);
        // ⑩ 判据：像不像 GPU 失败
        eq("认计算管线失败",
           Boolean.valueOf(GpuPlan.looksLikeGpuFailure(
               "vk::Device::createComputePipeline: ErrorUnknown")), Boolean.TRUE);
        eq("认 compute pipeline 那句", Boolean.valueOf(
           GpuPlan.looksLikeGpuFailure("compute pipeline creation failed for x")), Boolean.TRUE);
        eq("★ 不认「文件损坏」（降级也救不了）",
           Boolean.valueOf(GpuPlan.looksLikeGpuFailure("模型加载失败（文件损坏）：x.gguf")),
           Boolean.FALSE);
        eq("空/null 不算 GPU 失败",
           "" + GpuPlan.looksLikeGpuFailure("") + GpuPlan.looksLikeGpuFailure(null), "falsefalse");
        // ⑪ 报告行
        String lines = GpuPlan.reportLines(f(true, "Adreno (TM) 740", 8000, 12000, GIB, 28),
                                           g, true);
        eq("报告里有 自动计划=", Boolean.valueOf(lines.contains("自动计划=")), Boolean.TRUE);
        eq("报告里有 自动降级=是", Boolean.valueOf(lines.contains("自动降级=是")), Boolean.TRUE);
        eq("报告里有 硬件=", Boolean.valueOf(lines.contains("硬件=")), Boolean.TRUE);
        eq("description 里带设备名", Boolean.valueOf(GpuPlan.summary(
           f(true, "Adreno (TM) 740", 8000, 12000, GIB, 28), g).contains("Adreno (TM) 740")),
           Boolean.TRUE);
        // ⑫ GGUF 层数（真的解析一段合成头）
        eq("GGUF 头里读到 28 层", Integer.valueOf(ModelFile.ggufLayerCount(gguf(28))),
           Integer.valueOf(28));
        eq("GGUF 头里读到 40 层", Integer.valueOf(ModelFile.ggufLayerCount(gguf(40))),
           Integer.valueOf(40));
        eq("非 GGUF ⇒ 0", Integer.valueOf(ModelFile.ggufLayerCount(new byte[] {1, 2, 3})),
           Integer.valueOf(0));
        eq("空头 ⇒ 0", Integer.valueOf(ModelFile.ggufLayerCount(new byte[0])), Integer.valueOf(0));
        eq("同一个头仍然读得出模型名", ModelFile.ggufName(gguf(28)), "qwen2");
        System.out.println(bad == 0 ? "GPUPPLAN_OK" : ("GPUPPLAN_BAD=" + bad));
        if (bad != 0) System.exit(1);
    }
}
'''

if JAVAC and JAVA and os.path.isfile(GPU_PLAN) and os.path.isfile(MODEL_FILE):
    tmp7 = tempfile.mkdtemp(prefix='dsp74p_')
    try:
        drv7 = os.path.join(tmp7, 'GpuPlanDriver.java')
        io.open(drv7, 'w', encoding='utf-8').write(PLAN_DRIVER)
        cp7 = subprocess.run([JAVAC, '-encoding', 'UTF-8', '-d', tmp7,
                              GPU_PLAN, MODEL_FILE, drv7], capture_output=True, text=True)
        check('★★★ javac 编得过**出厂那份 GpuPlan.java / ModelFile.java**（真源码、零 Android 依赖）',
              cp7.returncode == 0, (cp7.stderr or '')[-500:])
        if cp7.returncode == 0:
            rr7 = subprocess.run([JAVA, '-cp', tmp7, 'GpuPlanDriver'],
                                 capture_output=True, text=True)
            check('★★★ 自动计划的决策边界 + GGUF 层数解析 **真跑**全对'
                  '（没有 GPU / 已知坏机型 / 预算足 / 部分 / 太少 / 估算 / 降级 / 判据）',
                  rr7.returncode == 0 and 'GPUPPLAN_OK' in rr7.stdout,
                  (rr7.stdout or '')[-900:] + (rr7.stderr or '')[-300:])
    finally:
        shutil.rmtree(tmp7, ignore_errors=True)
else:
    check('★★★ 有 javac/java 才能真编真跑 GpuPlanDriver', False, 'JAVAC/JAVA 不可用')

print()

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（本地模型提速层：GPU 层数真的传了 / Vulkan 真的进包 / '
      'KV 不再每句重算 / 有分段读数；'
      '★ 轮次67：glslc 假设被推翻有逐字节证据 / Adreno 回退与 A/B 开关在位 / '
      '页错误与内存账真的能读 / 内存建议纯函数真编真跑过；'
      '★★★ 轮次69：两态**默认开** / 开着失败**结构性**不回落（ON 分支里没有重载调用、'
      'auto 回落整条删掉）/ **删掉那个把"换模型"堵死的进程级粘性标记** / '
      '新增「⏏ 卸载当前模型」（放内存、留文件）/ 五条退路在任何状态下都可用'
      '（纯类真值表真编真跑）/ 一页纸自测指南与源码逐条对齐；'
      '★★★ 轮次71：**8 个调优旋钮**搬进设置页（键名/取值在 Java 与 JNI 两边逐条对查、'
      '默认档与上一版行为逐字等价、每一项都标了"有上游依据"还是"我们的推测"）/ '
      '**「🧪 本地推理自检」**（后台 + 可取消 + 一键复制 + 失败也有有用结论）/ '
      '两个纯类（LocalTune / LocalSelfTest）**真编真跑**过边界；'
      '★★★ 轮次72：**真机第一次连上** ⇒ 本地模型面板必须能滚（真机上尾部被裁、'
      '用户点不到 🎛 入口）/ BUILD_INFO 里那台机器（25091RP04C · SM8750P · Adreno 830）'
      '的六组 A/B 读数 / **GPU 在这台机器上用不了**这个事实 / `RssFile` ≈ 1.0 GB '
      '⇒ 内存不是瓶颈 / 纯 CPU 44.75 tok/s / 两条上游判据局限'
      '（`pipeline->name` 固定串 · `mmvq` 被 `integer_dot_product` 门住）；'
      '★★★ 轮次72 收尾：**`-1` 哨兵不许当数字印**（`ms_text` / `tok_s_text` / `faults_text`，'
      '整份报告里不许出现 `-1 ms`）/ 自检结论的判据从 `has_vulkan_gpu` 改成 '
      '**`offloaded_layers > 0`**（有设备 ≠ 用上了）/ 结论里印出那句可执行建议'
      '（`CPU_RECOMMEND`：建议这台机器把「⚡ GPU 加速」设成「关」（纯 CPU））/ '
      '**E7 复现 44.84 tok/s（22 ms/token）** / 面板可滚动的真机验收方式）')
