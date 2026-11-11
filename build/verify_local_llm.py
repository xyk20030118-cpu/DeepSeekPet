# -*- coding: utf-8 -*-
"""本地 GGUF 模型部署 · **引擎层**（轮次62）。

用户原话：「**新增模型本地部署，在手机本地一键导入小参数gguf模型然后让桌宠纯本地对ai**」。
本轮只做**引擎**：把 llama.cpp 交叉编译成 arm64 的原生库 + 一层 Java API + JNI 包装，
再用**离线断言**证明它真的进了包。导入、对话接线在后面的轮次。

## ★★ 为什么"离线断言"就是本轮的全部验证

包只有 `arm64-v8a`，而模拟器 `pet411` 是 **x86_64** ⇒ 装了也起不来。
所以本功能**无法自动设备实测**（用户已知情，改由他接真机验）。
能自动验的只有"包里的东西对不对"，而这一轮的坑恰好**全在"包里的东西"上**：

  * 编错了架构（x86_64 的库塞进 arm64 的包）—— 只看文件名**完全看不出来**；
  * 漏拷了一个依赖（`libggml-cpu.so` 忘了）—— 编译、打包、装包**全都不报错**，
    要到真机 `dlopen` 才炸；
  * **Java 的 native 方法名与 JNI 函数名拼错一个字母** —— javac 不报、链接器不报，
    要到真机第一次调用才抛 `UnsatisfiedLinkError`；
  * 忘了 strip ⇒ 包白胖 49 MB；
  * 忘了重编 APK ⇒ `lib/` 里明明是新的，包里还是旧的。

⇒ 这一节**逐条**把这些钉住。

## 分节

  一、★ 六个 .so 齐了，且**依赖链按 ELF 的 DT_NEEDED 逐条落地**（防"漏拷一个"）
  二、★★ ELF 身份：**读 `e_machine == 0xB7`**（aarch64），不看文件名/后缀
  三、★★ 导出符号齐：llama 层 / ggml 层 / JNI 层（**纯 Python 解 .dynsym**，不依赖 NDK）
  四、★★ Java ↔ JNI 方法名**三方一一对应**（Java 源码 / JNI 源码 / 编出来的 .so）
  五、★★ 流式回调真的是流式（且**半个汉字不会外泄**）
  六、★ 体积：strip 过了（别把 54 MB 的调试版塞进包）
  七、★★ 真的进了 APK：解包数一遍 + 页对齐 + 与 lib/ 逐个对齐 + dex 里有这个类
  八、★ 溯源：镜像 / commit / NDK 都记着；两个套件都登记进全量套件

反向破坏见 reverse_check_local_llm.py。

## 两个探针模式（给反向套件做**单元级**破坏用）

    python verify_local_llm.py --probe-elf <某个 .so>
    python verify_local_llm.py --probe-symbols <某个 .so> <sym1,sym2,...>

★ 有了它们，反向套件就能在 **%TEMP% 里的副本**上改一个字节再验
（改 `e_machine`、把 `.dynstr` 里的符号名抹掉），
**完全不用碰工程里的真 .so** —— 这正是轮次61 那次事故的反面教材：
反向套件改源文件、被强杀、`finally` 没跑到，破坏态留在盘上。

★ 环境变量 `DSP_LIB_DIR` 可以把"原生库目录"整个指走（默认工程下的 `lib/arm64-v8a`）。
  反向套件用它把断言指到 `%TEMP%` 的**副本**上做破坏 —— 真库全程只读。
"""
import hashlib
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
JNI_DIR = os.path.join(ROOT, 'jni')
# ★ 可被环境变量指走：反向套件会**拷一份** lib/ 到 %TEMP% 再改一个字节，
#   用 DSP_LIB_DIR 把断言指过去 ⇒ 破坏与验证都在副本上做，
#   工程里的真 .so **一个字节都不会被动到**
#   （轮次61 那次事故就是反向套件改源文件、被强杀、finally 没跑到，破坏态留在盘上）。
LIBDIR = os.environ.get('DSP_LIB_DIR', os.path.join(ROOT, 'lib', 'arm64-v8a'))
ANDROID_NDK_ROOT = r'D:\Android\Sdk\ndk'
from artifacts import dev_apk, REL_ROOT  # noqa: E402

DEV_APK = dev_apk()

# ---------------------------------------------------------------- 本轮新增的原生库
# 依赖链：libdspllm.so → libllama.so → {libggml.so, libggml-cpu.so, libggml-base.so}
#         → libc++_shared.so
# ★★ 轮次66：多了 **libggml-vulkan.so**（Vulkan/GPU 后端，内嵌 1119 个 SPIR-V ≈19 MB）。
#    它自己多一条 `libvulkan.so` 的依赖 —— 那是 **Android 系统提供**的，不打进包（见 SYSTEM_LIBS）。
NEW_LIBS = ['libllama.so', 'libggml.so', 'libggml-cpu.so', 'libggml-base.so',
            'libggml-vulkan.so',
            'libdspllm.so', 'libc++_shared.so']
# 工程原有的（语音那条链）—— 只是用来算"本轮到底让包胖了多少"
OLD_LIBS = ['libonnxruntime.so', 'libonnxruntime4j_jni.so', 'libsherpa-onnx-c-api.so',
            'libsherpa-onnx-cxx-api.so', 'libsherpa-onnx-jni.so']

# Android 系统自己提供的，不用打进包
# ★★ 轮次66：加了 **libvulkan.so**（Vulkan 加载器；Android 7.0/API 24 起就在系统里）。
#    不加这一条的话"递归闭合"会误报"断在 libvulkan.so"（实测就是这么红的）。
SYSTEM_LIBS = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
               'libz.so', 'libstdc++.so', 'libvulkan.so'}

EM_AARCH64 = 0xB7
EM_X86_64 = 0x3E
ET_DYN = 3
ALIGN = 4096

# 这几条是从 **libllama.so 真的导出**里挑的（本轮实测），
# 覆盖"初始化 / 加载模型 / 分词 / 解码 / 采样 / 取词表 / 释放"整条链。
LLAMA_SYMBOLS = [
    'llama_backend_init',            # 后端初始化
    'llama_backend_free',
    'llama_load_model_from_file',    # ★ 施工单点名要的（现版本已 DEPRECATED 但**仍导出**）
    'llama_model_load_from_file',    # 现版本正牌入口（本 JNI 用的是它）
    'llama_init_from_model',
    'llama_model_default_params',
    'llama_context_default_params',
    'llama_sampler_chain_default_params',
    'llama_tokenize',
    'llama_token_to_piece',
    'llama_decode',
    'llama_batch_get_one',
    'llama_sampler_chain_init',
    'llama_sampler_chain_add',
    'llama_sampler_init_greedy',
    'llama_sampler_init_temp',
    'llama_sampler_init_top_k',
    'llama_sampler_init_top_p',
    'llama_sampler_init_min_p',
    'llama_sampler_init_dist',
    'llama_sampler_sample',
    'llama_sampler_accept',
    'llama_sampler_free',
    'llama_model_get_vocab',
    'llama_vocab_is_eog',
    'llama_get_memory',
    'llama_memory_clear',
    'llama_model_free',
    'llama_free',
    'llama_n_ctx',
    'llama_n_batch',
    'llama_model_desc',
    'llama_model_size',
    'llama_model_n_params',
    'llama_version',
]
GGML_SYMBOLS = {
    'libggml.so': ['ggml_backend_load_all', 'ggml_backend_reg_count', 'ggml_backend_dev_count'],
    'libggml-base.so': ['ggml_init', 'ggml_free', 'ggml_new_tensor_1d', 'ggml_time_us'],
    'libggml-cpu.so': ['ggml_backend_cpu_init', 'ggml_graph_compute'],
}

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


# ★★ 正式版的包名由 build/make_variant.py 决定（它会把所有 .java 里的包名换掉）。
#    这里**从那个脚本里读**，不在本文件里另写一份 ——
#    两处各写一份的话，哪天改了 REL_PKG 而忘了同步 JNI，正式版就会静默失灵。
MAKER_PATH = os.path.join(HERE, 'make_variant.py')
MAKER = read(MAKER_PATH) if os.path.isfile(MAKER_PATH) else ''
_m = re.search(r'^REL_PKG\s*=\s*"([^"]+)"', MAKER, re.M)
REL_PKG = _m.group(1) if _m else 'com.doropet.release'
REL_PKG_PATH = REL_PKG.replace('.', '/')


# ================================================================ 纯 Python ELF 解析
# ★ 刻意**不**调 llvm-nm / readelf：套件要能在"没装 NDK"的机器上照样跑，
#   而且"读 e_machine"这件事本身就该由断言自己读，不能外包给别的工具的解释。

def elf_header(path):
    """读 ELF 头。返回 None 表示根本不是 ELF。"""
    with open(path, 'rb') as f:
        h = f.read(64)
    if len(h) < 64 or h[:4] != b'\x7fELF':
        return None
    return {
        'ei_class': h[4],                                  # 2 = ELF64
        'ei_data': h[5],                                   # 1 = 小端
        'e_type': struct.unpack_from('<H', h, 16)[0],
        'e_machine': struct.unpack_from('<H', h, 18)[0],   # ★ 架构就在这个 2 字节里
    }


def _elf_sections(data):
    if len(data) < 64 or data[:4] != b'\x7fELF' or data[4] != 2 or data[5] != 1:
        return []
    e_shoff = struct.unpack_from('<Q', data, 0x28)[0]
    e_shentsize = struct.unpack_from('<H', data, 0x3A)[0]
    e_shnum = struct.unpack_from('<H', data, 0x3C)[0]
    e_shstrndx = struct.unpack_from('<H', data, 0x3E)[0]
    if e_shoff == 0 or e_shnum == 0 or e_shstrndx >= e_shnum:
        return []
    raw = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        (name_off, typ, _flags, _addr, off, size, link, _info, _align, entsize) = \
            struct.unpack_from('<IIQQQQIIQQ', data, o)
        raw.append({'name_off': name_off, 'type': typ, 'off': off, 'size': size,
                    'link': link, 'entsize': entsize})
    shstr = raw[e_shstrndx]
    for s in raw:
        b = data[shstr['off'] + s['name_off']:]
        z = b.find(b'\0')
        s['name'] = b[:z].decode('latin-1') if z >= 0 else ''
    return raw


def elf_dyn_symbols(path):
    """**已定义**的动态符号名集合（.dynsym 里 st_shndx != 0 的）。"""
    data = open(path, 'rb').read()
    by = {s['name']: s for s in _elf_sections(data)}
    dsym, dstr = by.get('.dynsym'), by.get('.dynstr')
    if not dsym or not dstr or dsym['entsize'] == 0:
        return set()
    out = set()
    for i in range(dsym['size'] // dsym['entsize']):
        o = dsym['off'] + i * dsym['entsize']
        st_name = struct.unpack_from('<I', data, o)[0]
        st_shndx = struct.unpack_from('<H', data, o + 6)[0]
        if st_name == 0 or st_shndx == 0:
            continue
        b = data[dstr['off'] + st_name:]
        z = b.find(b'\0')
        if z > 0:
            out.add(b[:z].decode('latin-1'))
    return out


def elf_needed(path):
    """DT_NEEDED 列表（这个 .so 运行时到底要谁）。"""
    data = open(path, 'rb').read()
    by = {s['name']: s for s in _elf_sections(data)}
    dyn, dstr = by.get('.dynamic'), by.get('.dynstr')
    if not dyn or not dstr:
        return []
    out = []
    for i in range(dyn['size'] // 16):
        tag, val = struct.unpack_from('<qQ', data, dyn['off'] + i * 16)
        if tag == 0:
            break
        if tag == 1:
            b = data[dstr['off'] + val:]
            z = b.find(b'\0')
            out.append(b[:z].decode('latin-1'))
    return out


def elf_section_names(path):
    return [s['name'] for s in _elf_sections(open(path, 'rb').read())]


# ================================================================ APK 侧

def apk_lib_entries(apk, prefix='lib/arm64-v8a/'):
    """APK 里 lib/arm64-v8a/ 下的条目 —— 顺便把**局部头**读出来算数据起始偏移，
    用来验 `assemble.py` 的 4096 页对齐（安装时 mmap 要页对齐，否则白装）。"""
    out = []
    with zipfile.ZipFile(apk) as z, open(apk, 'rb') as f:
        for zi in z.infolist():
            if not zi.filename.startswith(prefix):
                continue
            f.seek(zi.header_offset)
            lh = f.read(30)
            (_sig, _ver, _flags, method, _t, _d, _crc, _cs, _us, nlen, elen) = \
                struct.unpack('<IHHHHHIIIHH', lh)
            out.append({
                'name': zi.filename,
                'base': zi.filename[len(prefix):],
                'method': method,                       # 0 = stored
                'size': zi.file_size,
                'data_off': zi.header_offset + 30 + nlen + elen,
            })
    return out


# ================================================================ 源码侧

def strip_comments_and_strings(src):
    """把注释与字符串字面量抹掉 —— 否则文档里写的 `nativeXxx(...)` 会被当成声明。"""
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
            out.append(q + q)
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


# ================================================================ 探针模式（给反向套件）

def _probe_elf(path):
    h = elf_header(path)
    ok = (h is not None and h['ei_class'] == 2 and h['ei_data'] == 1
          and h['e_type'] == ET_DYN and h['e_machine'] == EM_AARCH64)
    print('probe-elf %s -> %s  %s' % (os.path.basename(path), h, 'AARCH64 OK' if ok else 'NOT aarch64'))
    return 0 if ok else 1


def _probe_symbols(path, want):
    have = elf_dyn_symbols(path)
    missing = [w for w in want if w not in have]
    print('probe-symbols %s -> 导出 %d 个；缺 %d 个%s'
          % (os.path.basename(path), len(have), len(missing),
             ('：' + ', '.join(missing)) if missing else ''))
    return 0 if not missing else 1


if len(sys.argv) > 2 and sys.argv[1] == '--probe-elf':
    sys.exit(_probe_elf(sys.argv[2]))
if len(sys.argv) > 3 and sys.argv[1] == '--probe-symbols':
    sys.exit(_probe_symbols(sys.argv[2], [s for s in sys.argv[3].split(',') if s]))


# ================================================================ 一、库齐了 + 依赖闭合

print('=== 一、★ 六个原生库齐了，依赖链按 DT_NEEDED 逐条落地 ===')
present = {}
for n in NEW_LIBS:
    p = os.path.join(LIBDIR, n)
    present[n] = p
    check('lib/arm64-v8a/%s 存在' % n, os.path.isfile(p),
          ('%.0f KB' % (os.path.getsize(p) / 1024.0)) if os.path.isfile(p) else '缺')

if all(os.path.isfile(p) for p in present.values()):
    print()
    for n in NEW_LIBS:
        needed = elf_needed(present[n])
        local = [x for x in needed if x not in SYSTEM_LIBS]
        missing = [x for x in local if not os.path.isfile(os.path.join(LIBDIR, x))]
        check('★★ %s 的直接依赖都在 lib/ 里（%s）' % (n, ', '.join(local) or '无'),
              not missing, ('缺 ' + ', '.join(missing)) if missing else '')

    print()
    # ★★ 这条是"漏拷一个 .so"的最终保险：把整条链**递归**走一遍，
    #    任何一个 DT_NEEDED 在 lib/ 里找不到 ⇒ 真机 dlopen 必炸，而编译/打包全都不报错。
    seen, queue, broken = set(), list(NEW_LIBS), []
    while queue:
        cur = queue.pop()
        if cur in seen:
            continue
        seen.add(cur)
        p = os.path.join(LIBDIR, cur)
        if not os.path.isfile(p):
            broken.append(cur)
            continue
        for d in elf_needed(p):
            if d not in SYSTEM_LIBS:
                queue.append(d)
    check('★★ 依赖链**递归**闭合：六个库里出现的每个非系统 DT_NEEDED 都在 lib/ 里',
          not broken, ('断在 ' + ', '.join(broken)) if broken else ('闭合于 %d 个库' % len(seen)))
    check('★ libc++_shared.so 在（多个 shared library 时 NDK 规定必须用它，不能各自静态链）',
          'libc++_shared.so' in seen)
else:
    print('  （有库缺失，依赖检查跳过）')


# ================================================================ 二、ELF 身份

print()
print('=== 二、★★ ELF 身份：读 e_machine，不看文件名 ===')
for n in NEW_LIBS:
    p = present[n]
    if not os.path.isfile(p):
        continue
    h = elf_header(p)
    check('★★ %s 是 ELF64/小端/ET_DYN' % n,
          h is not None and h['ei_class'] == 2 and h['ei_data'] == 1 and h['e_type'] == ET_DYN,
          h)
    check('★★ %s 的 e_machine == 0x%02X（AArch64）—— **模拟器 x86_64 因此跑不了它**' % (n, EM_AARCH64),
          h is not None and h['e_machine'] == EM_AARCH64,
          ('实际 0x%02X' % h['e_machine']) if h else '不是 ELF')

# 反向套件靠这两个探针做**单元级**破坏（在 %TEMP% 的副本上改 e_machine）
_probe = os.path.join(LIBDIR, 'libllama.so')
if os.path.isfile(_probe):
    check('★ 探针自检：--probe-elf 认得出**真的** aarch64 库（否则反向项全是假绿）',
          _probe_elf(_probe) == 0)


# ================================================================ 三、导出符号

print()
print('=== 三、★★ 导出符号齐（纯 Python 解 .dynsym，不依赖 NDK）===')
if os.path.isfile(present['libllama.so']):
    syms = elf_dyn_symbols(present['libllama.so'])
    miss = [s for s in LLAMA_SYMBOLS if s not in syms]
    check('★★ libllama.so 导出 llama 核心 API %d 条，缺 %d 条' % (len(LLAMA_SYMBOLS), len(miss)),
          not miss, ('缺 ' + ', '.join(miss)) if miss else '')
    check('★ 施工单点名的 llama_load_model_from_file 在（现版本虽 DEPRECATED 但仍导出）',
          'llama_load_model_from_file' in syms)
    check('★ 现版本正牌入口 llama_model_load_from_file 也在（JNI 实际调的是它）',
          'llama_model_load_from_file' in syms)
    check('★ 探针自检：--probe-symbols 对真库返回 0',
          _probe_symbols(present['libllama.so'], LLAMA_SYMBOLS) == 0)

for lib, want in GGML_SYMBOLS.items():
    p = present[lib]
    if not os.path.isfile(p):
        continue
    syms = elf_dyn_symbols(p)
    miss = [s for s in want if s not in syms]
    check('★★ %s 导出 ggml 核心符号 %d 条，缺 %d 条' % (lib, len(want), len(miss)),
          not miss, ('缺 ' + ', '.join(miss)) if miss else '')


# ================================================================ 四、Java ↔ JNI 一一对应

print()
print('=== 四、★★ Java ↔ JNI 方法名三方一一对应 ===')
JAVA_PATH = os.path.join(SRC, 'LocalLlm.java')
CPP_PATH = os.path.join(JNI_DIR, 'dspllm_jni.cpp')
check('src/com/deepseek/pet/LocalLlm.java 存在', os.path.isfile(JAVA_PATH))
check('jni/dspllm_jni.cpp 存在', os.path.isfile(CPP_PATH))

java_native, jni_dev, jni_rel, so_dev, so_rel = set(), set(), set(), set(), set()
if os.path.isfile(JAVA_PATH) and os.path.isfile(CPP_PATH):
    java_raw = read(JAVA_PATH)
    cpp_raw = read(CPP_PATH)
    java_code = strip_comments_and_strings(java_raw)
    cpp_code = strip_comments_and_strings(cpp_raw)

    # ★★ 两套包名。为什么必须两套：整包只编出**一个** libdspllm.so
    #    （lib/ 是开发版/正式版共用的），而 JNI 的名字修饰把包名编进了符号名，
    #    正式版工程又被 make_variant.py 换成了别的包名 ⇒ 一个 .so 得回答两套名字。
    DEV_MANGLED = 'Java_com_deepseek_pet_LocalLlm_'
    REL_MANGLED = 'Java_' + REL_PKG.replace('.', '_') + '_LocalLlm_'
    so_syms = elf_dyn_symbols(present['libdspllm.so'])
    java_jsyms = [s for s in so_syms if s.startswith('Java_')]

    java_native = set(re.findall(r'\bnative\s+[A-Za-z_][\w.<>\[\]]*\s+([A-Za-z_]\w*)\s*\(', java_code))
    jni_dev = set(re.findall(r'\b' + DEV_MANGLED + r'([A-Za-z_]\w*)\s*\(', cpp_code))
    jni_rel = set(re.findall(r'\b' + REL_MANGLED + r'([A-Za-z_]\w*)\s*\(', cpp_code))
    so_dev = set(s[len(DEV_MANGLED):] for s in java_jsyms if s.startswith(DEV_MANGLED))
    so_rel = set(s[len(REL_MANGLED):] for s in java_jsyms if s.startswith(REL_MANGLED))

    check('★★ Java 侧 native 声明数 = %d（不该是 0 —— 正则/类名漂了就会红）'
          '★ 轮次71：6 → 7（nativeLastStats）；★★ 轮次74：7 → 8（nativeDeviceInfo，'
          '自动硬件探测）' % len(java_native),
          len(java_native) == 8, sorted(java_native))
    check('★★ [开发版] Java 的每个 native 在 JNI 里都有同名函数（少一个 = 真机 UnsatisfiedLinkError）',
          java_native <= jni_dev, sorted(java_native - jni_dev))
    check('★★ [开发版] JNI 里没有 Java 侧不存在的多余函数（多一个 = 拼错了名字，那个 native 其实没实现）',
          jni_dev <= java_native, sorted(jni_dev - java_native))
    check('★★ [开发版] libdspllm.so **编出来**的导出与两边都一致（防"源码对了但漏重编"）',
          so_dev == java_native == jni_dev,
          'so=%s java=%s jni=%s' % (sorted(so_dev), sorted(java_native), sorted(jni_dev)))

    # ★★ 正式版那一套：这是轮次62 收尾才发现的坑（先编了正式版才想起包名会被换掉）。
    #    正式版少了这一套 ⇒ 每次调用抛 UnsatisfiedLinkError，而 Java 侧兜住了 Throwable
    #    ⇒ 功能**静默不工作**，最难查的坏法。
    check('★★ [正式版] JNI 源码里也给 %s 留了一套转发（否则正式版静默失效）' % REL_PKG,
          jni_rel == java_native, sorted(java_native - jni_rel))
    check('★★ [正式版] libdspllm.so 真的导出了那一套（不只是源码里写了）',
          so_rel == java_native, 'so=%s' % sorted(so_rel))
    check('★★ 两套前缀的常量与 make_variant.py 的 REL_PKG 是同一个（改一处没改另一处就会红）',
          REL_PKG in MAKER and REL_MANGLED in cpp_code)
    check('★ libdspllm.so 的 Java_ 符号总数 == 8(开发版) + 8(正式版)（hidden 可见性仍然生效）'
          '★ 轮次71：12 → 14；★★ 轮次74：14 → 16（nativeDeviceInfo 两套都要）',
          len(java_jsyms) == 16, len(java_jsyms))
    check('★★ 包名/类名编进符号里了：Java_com_deepseek_pet_LocalLlm_*（改包名必须同时改这两边）',
          'package com.deepseek.pet;' in java_code
          and all(s.startswith(DEV_MANGLED) or s.startswith(REL_MANGLED) for s in java_jsyms))
else:
    print('  （源码缺失，跳过）')

# 施工单要求的那几个 Java 侧 API 一个都不能少
if os.path.isfile(JAVA_PATH):
    jraw = read(JAVA_PATH)
    check('★★ Java API 齐：load / generate / unload / isLoaded',
          all(s in jraw for s in ('public static boolean load(String path, int threads, int ctxSize)',
                                  'public static String generate(String prompt, int maxTokens, float temp, TokenSink sink)',
                                  'public static void unload()',
                                  'public static boolean isLoaded()')))
    check('★ load 的三个参数就是施工单说的 (path, threads, ctx)',
          'nativeLoad(path, clampThreads(threads), clampCtx(ctxSize),' in jraw)


# ================================================================ 五、流式回调

print()
print('=== 五、★★ 流式回调真的流式 + 半个汉字不外泄 ===')
if os.path.isfile(CPP_PATH) and os.path.isfile(JAVA_PATH):
    cpp_raw = read(CPP_PATH)
    cpp_code = strip_comments_and_strings(cpp_raw)

    sink_push = func_body(cpp_code, 'bool sink_push(')
    utf8_consume = func_body(cpp_code, 'size_t utf8_consume(')
    # ★★ 轮次66：生成逻辑被抽成了 `generate_body`（JNI 入口那层现在只做
    #    "兜住 GPU 后端的异常 + 回落 CPU"）⇒ 这里要跟着改看它的函数体。
    #    不改的话这一节会全红（实测：全量套件跑到 66/73 就是断在这里）。
    gen_body = func_body(cpp_code, 'jstring generate_body(')

    check('★★ 生成循环里**逐个 token** 往回调推（不是攒完一次性返回 —— 气泡要逐句出）',
          'sink_push(st, piece' in gen_body)
    check('★★ 回调接口两边签名一致：Java `boolean onToken(String)` ↔ JNI `(Ljava/lang/String;)Z`',
          'boolean onToken(String piece);' in read(JAVA_PATH)
          and '(Ljava/lang/String;)Z' in cpp_raw and '"onToken"' in cpp_raw)
    check('★★ JNI 用 CallBooleanMethod（返回值 false = 用户要求停止，桌宠"一说话就闭嘴"靠它）',
          'CallBooleanMethod' in sink_push and 'stopped = true' in sink_push)
    check('★★★ 攒够**完整 UTF-8 序列**才回调：不完整的尾巴留着，下一个 token 再拼'
          '（否则一个汉字被 token 切开就出乱码 —— 中文对话必然遇到）',
          'if (i + (size_t) need > n) break;' in utf8_consume
          and 'pending.erase' in sink_push and 'pending.insert' in sink_push)
    check('★★ 用的是 NewString(UTF-16)，**不是** NewStringUTF —— 后者吃"修改版 UTF-8"，emoji 会乱码',
          'NewString(' in sink_push and 'NewStringUTF(' not in sink_push
          and 'NewString(st.full.data()' in gen_body)
    check('★★ 提示词走 GetStringChars + 自己转标准 UTF-8（GetStringUTFChars 给的是修改版 UTF-8）',
          'GetStringChars' in gen_body and 'utf16_to_utf8(utext, ulen)' in gen_body)
    check('★ 收尾把最后那点"半个字"补一个替换符吐出去（回调拼起来 == 返回值）',
          'void sink_flush(' in cpp_code and 'sink_flush(st);' in gen_body)
    check('★ 回调抛异常不许带出 JNI（ExceptionCheck + ExceptionClear，否则桌宠直接崩）',
          'ExceptionCheck()' in sink_push and 'ExceptionClear()' in sink_push)
    check('★★ 不自己起线程、不 AttachCurrentThread —— 回调严格发生在调用 generate 的那个线程上'
          '（JNIEnv 的规定；起线程就会踩 use-after-free）',
          'AttachCurrentThread' not in cpp_code and 'std::thread' not in cpp_code)
    check('★ 卸载先发停止信号再等锁（否则正在生成时卸载会把 UI 线程卡住几秒）',
          'g_cancel.store(true);' in func_body(cpp_code, 'Java_com_deepseek_pet_LocalLlm_nativeUnload(')
          and 'g_cancel.load()' in gen_body)
    check('★ isLoaded 不加锁（长生成进行中也要能立刻查到状态）',
          'g_ready.load()' in func_body(cpp_code, 'Java_com_deepseek_pet_LocalLlm_nativeLoaded('))
    check('★ Java 侧 generate 是**阻塞**语义且明说了要在后台线程调（写在 javadoc 里）',
          '同步阻塞' in read(JAVA_PATH) and '后台线程' in read(JAVA_PATH))
    # ★★ 这条是"照着官方示例抄"会抄错的地方：llama_sampler_sample **自己已经 accept 过了**
    #    （llama-sampler.cpp:960，两条返回路径都 accept），再 accept 一次就是**双记**。
    #    对现在这条链（greedy/top_k/top_p/min_p/temp/dist）恰好无害，
    #    但将来加 penalties / grammar 就会"重复惩罚算两遍"，症状极难查。
    check('★★ 生成循环里没有多写的 llama_sampler_accept（sample 已经 accept 过了，写两次是双记）',
          'llama_sampler_sample' in gen_body and 'llama_sampler_accept' not in gen_body)
else:
    print('  （源码缺失，跳过）')


# ================================================================ 六、体积

print()
print('=== 六、★ 体积：strip 过了，没把调试版塞进包 ===')
new_bytes = 0
for n in NEW_LIBS:
    p = present[n]
    if not os.path.isfile(p):
        continue
    new_bytes += os.path.getsize(p)
    dbg = [s for s in elf_section_names(p) if s.startswith('.debug_')]
    check('★ %s 没有 .debug_* 段（strip 生效）' % n, not dbg, dbg)

old_bytes = sum(os.path.getsize(os.path.join(LIBDIR, n)) for n in OLD_LIBS
                if os.path.isfile(os.path.join(LIBDIR, n)))
check('★★ 本轮新增原生库合计 %.2f MB（未 strip 会是 ~86 MB；上限按 32 MB 卡 —— '
      '★ 轮次66 那 19 MB 是 Vulkan 后端内嵌的 1119 个 SPIR-V 着色器，'
      'strip 只去得掉调试信息，这部分是"GPU 加速"的门票）'
      % (new_bytes / 1048576.0), 0 < new_bytes < 32 * 1048576)
print('  原有语音那 5 个 .so：%.2f MB；lib/ 合计：%.2f MB'
      % (old_bytes / 1048576.0, (old_bytes + new_bytes) / 1048576.0))


# ================================================================ 七、进包

print()
print('=== 七、★★ 真的进了 APK（解包数一遍 + 页对齐 + 与 lib/ 对齐）===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    entries = apk_lib_entries(DEV_APK)
    by_base = {e['base']: e for e in entries}
    print('  APK 里 lib/arm64-v8a/ 共 %d 个 .so' % len(entries))

    # ★★★ 轮次75：本地模型入口下线期间，这 6 个 .so **不在包里**（省 24.5 MB）。
    #   判据要跟着**唯一开关**走（`LocalModelFeature.ENABLED`），不能写死"一定在包里"。
    LOCAL_MODEL_LIBS = {
        'libdspllm.so', 'libllama.so', 'libggml.so',
        'libggml-base.so', 'libggml-cpu.so', 'libggml-vulkan.so',
    }
    LOCAL_MODEL_ON = True
    for _c in (os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet', 'LocalModelFeature.java'),
               os.path.join(ROOT, 'src', 'com', 'doropet', 'release', 'LocalModelFeature.java')):
        if os.path.isfile(_c):
            _m = re.search(r'ENABLED\s*=\s*(true|false)\s*;',
                           io.open(_c, encoding='utf-8').read())
            if _m:
                LOCAL_MODEL_ON = (_m.group(1) == 'true')
            break

    for n in NEW_LIBS:
        e = by_base.get(n)
        disk = os.path.getsize(present[n]) if os.path.isfile(present[n]) else -1
        if (not LOCAL_MODEL_ON) and n in LOCAL_MODEL_LIBS:
            check('★★ 本地模型下线期间 %s **不在包里**（省体积；库仍在 lib/ 里，恢复时自动回包）' % n,
                  e is None, ('包里是 %s' % e['size']) if e else '')
            continue
        check('★★ APK 里有 %s，且大小与 lib/ 里一致（%d 字节）' % (n, disk),
              e is not None and e['size'] == disk,
              ('包里是 %s' % (e['size'] if e else '没有')))

    # ★★ 这条抓"忘了重编 APK"：lib/ 里有的，包里必须一个不差地有
    #   （★ 下线期间排除那 6 个 —— 它们**故意**不在包里）。
    disk_sos = sorted(f for f in os.listdir(LIBDIR)
                      if f.endswith('.so') and (LOCAL_MODEL_ON or f not in LOCAL_MODEL_LIBS))
    apk_sos = sorted(by_base)
    check('★★ APK 的 .so 清单与 lib/arm64-v8a/ **逐个对齐**（防"改了 lib/ 忘了重编 APK"）',
          disk_sos == apk_sos,
          '只在盘上：%s / 只在包里：%s' % (sorted(set(disk_sos) - set(apk_sos)),
                                        sorted(set(apk_sos) - set(disk_sos))))

    # ★★★ 轮次75：native 库改成 deflate（APK 32.2 → 15.2 MB）。两种打法都必须成立：
    #   ① stored ⇒ 必须页对齐（直接从 APK mmap）；
    #   ② deflate ⇒ **Manifest 必须是 extractNativeLibs="true"**（安装时解压），否则装不上。
    stored = [e for e in entries if e['method'] == 0]
    deflated = [e for e in entries if e['method'] == 8]
    check('★ 每个 .so 要么 stored、要么 deflate（没有别的压缩方式）',
          len(stored) + len(deflated) == len(entries),
          [(e['base'], e['method']) for e in entries if e['method'] not in (0, 8)])
    if deflated:
        check('★★★ 压缩了 .so ⇒ Manifest 必须 `extractNativeLibs="true"`（否则安装时装不上）',
              'android:extractNativeLibs="true"' in io.open(
                  os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8').read())
    bad_align = [(e['base'], e['data_off'] % ALIGN) for e in stored
                 if e['data_off'] % ALIGN != 0]
    check('★★ stored 的 .so 数据起始偏移都 %d 字节对齐（mmap 要页对齐）' % ALIGN,
          not bad_align, bad_align)

    apk_lib_bytes = sum(e['size'] for e in entries)
    print('  APK 里 native 库合计 %.2f MB（其中本轮新增 %.2f MB）'
          % (apk_lib_bytes / 1048576.0, new_bytes / 1048576.0))

    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('LocalLlm', 'TokenSink', 'nativeLoad', 'nativeGenerate', 'onToken',
              'DSPLlm', 'libdspllm'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)
    check('★ dex 里带上了要装载的库名 dspllm（System.loadLibrary("dspllm")）',
          b'dspllm' in dex)

# assemble.py 是"通配收"还是"白名单"—— 白名单的话加了库不生效
ASM = os.path.join(HERE, 'assemble.py')
if os.path.isfile(ASM):
    a = read(ASM)
    # ★ 锚点必须精确到**收集那一行**：`endswith(".so")` 在 assemble.py 里出现两次，
    #   另一处是"对齐判定"（line 92）—— 只锚 `endswith(".so")` 会被那一行喂饱，
    #   于是"通配收"被改成白名单也照样绿（本轮反向验证当场抓到过这个假绿）。
    check('★★ assemble.py 是**通配收** lib/ 下所有 .so（不是白名单 —— 白名单就得改脚本）',
          "sorted(os.listdir(_dir)) if x.endswith('.so')" in a)
    check('★ assemble.py 对 .so 走 stored + %d 页对齐' % ALIGN,
          'ALIGN     = 4096' in a and 'low.endswith(".so")' in a)


# ================================================================ 八、溯源 + 登记

print()
print('=== 八、★ 溯源与登记 ===')
INFO = os.path.join(JNI_DIR, 'BUILD_INFO.txt')
check('jni/BUILD_INFO.txt 存在（下一个窗口靠它重编）', os.path.isfile(INFO))
if os.path.isfile(INFO):
    info = read(INFO)
    m_sha = re.search(r'\b([0-9a-f]{40})\b', info)
    m_zipsha = re.search(r'\b([0-9a-f]{64})\b', info)
    check('★★ 记着 llama.cpp 的 commit（40 位 sha）', m_sha is not None,
          m_sha.group(1) if m_sha else '没找到')
    check('★★ 记着源码包 sha256（64 位 sha）—— 验"下的就是这一份"', m_zipsha is not None,
          m_zipsha.group(1) if m_zipsha else '没找到')
    check('★ 记着实际走通的下载路线 ghproxy.net（GitHub 直连不通）', 'ghproxy.net' in info)
    check('★★ 也记着"gitee 镜像下整包没走通"这件事（否则下一个窗口会白试一遍）',
          'gitee' in info and ('下载墙' in info or 'archive' in info))
    m_ndk = re.search(r'NDK \*\*([0-9][0-9.]*)\*\*', info) or re.search(r'ndk;([0-9][0-9.]*)', info)
    check('★ 记着 NDK 版本', m_ndk is not None, m_ndk.group(1) if m_ndk else '没找到')
    if m_ndk and os.path.isdir(ANDROID_NDK_ROOT):
        check('★★ 记的那个 NDK 版本**真的装在盘上**（%s）' % m_ndk.group(1),
              os.path.isdir(os.path.join(ANDROID_NDK_ROOT, m_ndk.group(1))))
    check('★ 记着必须 strip（否则包白胖 49 MB）', 'strip' in info.lower())
    check('★ 记着六个 .so 一个都不能少（依赖链）', 'libggml-cpu.so' in info and 'libc++_shared.so' in info)

RUNBAT = os.path.join(HERE, 'run_all_tests.bat')
if os.path.isfile(RUNBAT):
    bat = read(RUNBAT)
    check('★★ 本套件登记进全量套件 run_all_tests.bat（没登记 = 从来不会跑）',
          'verify_local_llm.py' in bat)
    check('★★ 反向套件也登记了', 'reverse_check_local_llm.py' in bat)
    # ★ 这里原来写死 `'/73' in bat` —— 轮次79 把套件从 73 加到 76，它立刻变红。
    #   写死数字就是"手抄的常量会静默过期"的又一例，所以改成**按语义查**
    #   （判据统一在 build/suite_index.py：同分母 + 末条==总数 + 不少于下限）。
    import suite_index as _si
    _si_ok, _si_total = _si.consistent(bat)
    check('★ 全量套件编号自洽（同分母 + 末条==总数，且不少于 73 套）',
          _si_ok, '%d 套' % _si_total)

check('★ jni/CMakeLists.txt 在（JNI 的构建脚本，不是一次性的手工命令）',
      os.path.isfile(os.path.join(JNI_DIR, 'CMakeLists.txt')))

# ★★ 端到端：正式版工程是 make_variant.py 生成的，它的包名必须真的被换过 ——
#    换了包名就得靠 JNI 里那套转发顶着（见上面第四节的说明）。
if os.path.isdir(REL_ROOT):
    _rel_java = os.path.join(REL_ROOT, 'src', REL_PKG_PATH, 'LocalLlm.java')
    check('★★ 生成的正式版工程里这个类确实在 %s 包下（path=%s）' % (REL_PKG, REL_PKG_PATH),
          os.path.isfile(_rel_java)
          and ('package %s;' % REL_PKG) in read(_rel_java))
    check('★★ 正式版工程里已经没有残留的开发版包名',
          os.path.isfile(_rel_java) and 'com.deepseek.pet' not in read(_rel_java))
else:
    print('  （%s 不存在，正式版端到端检查跳过）' % REL_ROOT)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（本地模型引擎层：aarch64 ELF / 导出符号 / Java↔JNI 一一对应 / 流式回调 / 进包与页对齐）')
