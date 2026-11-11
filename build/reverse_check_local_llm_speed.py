# -*- coding: utf-8 -*-
"""反向破坏检查：本地模型**提速层**（轮次66 建立 · 轮次67 扩到 Adreno/内存 ·
轮次68 三态/强制 · 轮次69 两态 + 模型管理（卸载/换模型/退路不许被堵死）·
**轮次71 调优旋钮 + 本地推理自检**）。

每条判据配一个破坏项：**把这条性质改坏 ⇒ verify_local_llm_speed.py 必须当场变红。**
一条恒真的判据比没有判据更糟 —— 它会让下一个窗口以为"这块有人守着"。

## ★★ 三条纪律（都是血换来的）

1. **按字节读写**（`rb`/`wb`）。文本模式会把 CRLF 读成 LF，
   改回去就把 `run_all_tests.bat` 的行尾**静默改坏** —— 轮次64 实测过这件事，
   而且当时**没有任何判据会红**。
2. **`finally` 一定还原** + 启动时先扫一遍遗留的 `.r66bak`（万一上次被强杀）。
   ★ 所以这个脚本**绝不能被中途强杀**（全量套件里跑，别用带超时的前台命令）。
   ★★ 轮次67：破坏项**打到了工程树外面的 llama.cpp 源码**上
   （`D:\\llamacpp_src\\...\\ggml-vulkan.cpp` 里那条 Adreno 回退），
   所以 `recover()` 的扫描目录里加上了它 —— 否则被强杀会留下一个**被改坏的 llama.cpp 源码**，
   下一轮重编会静默带上一个假补丁（比一个红的断言难查得多）。
   ★★ 轮次68：交付目录（`D:\\桌宠移植包`）也进了扫描表 ——
   有几条破坏项打在那份"一页纸自测指南"上。
3. **一条判据在注释里也出现过时，破坏项要 `all_=True`**（只换第一处常常换到注释上，
   等于没破坏 —— 轮次66 的 D 项就是这么抓出来的假绿）。

## ★★★ 破坏项会随产品改口径而**失效**，必须每轮自查

  · 轮次66 的 H（"默认开 ⇒ 改成 false"）在轮次68 之后失去区分力 ⇒ 换成"force ⇒ auto"；
  · 轮次68 的 H（"force ⇒ auto"）与 D（"没有 GPU 设备 ⇒ 回退 CPU"那行日志）、
    G（auto 的回落重载）、AL（删掉 auto 回落）、AN/AO（粘性标记）在轮次69 之后
    **全部失去区分力**（三态没了、auto 回落整条删了、粘性标记也删了）
    ⇒ 本轮**逐条换掉**：
      D  → "把「关」那一支的层数压回 0 删掉"
      G  → "把『开 GPU 失败』那句如实报错整句换掉"（all_=True）
      AL → **"把那个把'换模型'堵死的粘性标记加回来"**（★ 本轮最要紧的一条破坏项）
      AN → "往退路那个纯类里塞一句 GPU 判断"
      AO → "换模型不卸旧的"
**通则：判据的前提变了，破坏项也要跟着变 —— 否则它就从"守卫"变成"摆设"。**

## ★★★ 轮次71 新增的一整组（BM ~ CG）

  本轮的两件交付各有一组守卫，每一条都配了破坏项：

  * **8 个调优旋钮真的从界面走到原生层**：
    BM（JNI 入口摘掉 tune）/ BN（对话后端不传 tune）/ BO（Prefs 不做规范化）/
    BP（ubatch 白名单放宽）/ BQ（默认值不再与上一版等价）/ BR（删掉"本次调优"日志）/
    BS（后端级也跟着放模型）/ BT（needsRestart 说 Adreno 不用重启）/
    BU（mlock 旋钮成摆设）/ CC（默认档不清环境变量）/ CF（flash 旋钮失效）/
    CG（n_ubatch 不夹到 n_batch 以下）
  * **「🧪 本地推理自检」真的能在用户手机上跑出结论**：
    BV（改成主线程跑）/ BW（不先放掉旧模型）/ BX（随机温度）/
    BY（复制不接剪贴板）/ BZ（取消后照样弹结果）/ CA（删掉后端错误尾巴）/
    CB（删掉数值行）/ CD、CE（指南里那两节被删）

破坏项锚点全部是**稳定写法**（不用 md5 / 版本号 / 文件大小）。

## ★★★ 轮次72 新增的一整组（CM ~ DB）

  轮次72 是**真机第一次连上电脑**的那一轮，所以这一组守的是**真机上量到的东西**：

  * **产品 bug**：本地模型面板被裁 ⇒ 必须能滚
    CM（把 ScrollView 摘掉）/ DB（高度上限从 0.72 屏高改成 1.0）；
  * **报告缺陷 ①**：`-1` 是"这一步没跑到"的哨兵，不许当数字印
    CU（`ms_text` 的哨兵分支废掉）/ CV（没测到也印 tok/s）/ CW（页错误印成 `+-1`）；
  * **报告缺陷 ②（★ 本轮最要紧的一条：它会撒谎）**：自检结论原来只按 `has_vulkan_gpu` 判
    ⇒ 真机 E7（档位=关、has_vulkan_gpu=1、**offloaded_layers=0**、纯 CPU 22 ms/token）
    会被印成「✅ **GPU 真的用上了**」—— 那就是把"纯 CPU 很快"写成"GPU 加速成功"。
    CX（判据换回 `has_vulkan_gpu`）/ CY（把那句"建议用关/纯 CPU"摘掉）；
  * **按机型分段**：CZ（删掉 product 名 ⇒ 两台机器的读数又能混）/ DA（删掉"必须带机型"）；
  * **文档事实**：CN ~ CT（BUILD_INFO 的轮次72 节 / 44.75 tok/s / 失败管线名 /
    `pipeline->name` 判据局限 / RssFile 结论 / 诚实边界 / 指南那一节）。
"""
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
LIB = os.path.join(ROOT, 'lib', 'arm64-v8a')
JNI = os.path.join(ROOT, 'jni')

CPP = os.path.join(JNI, 'dspllm_jni.cpp')
INFO = os.path.join(JNI, 'BUILD_INFO.txt')
LOCAL_LLM = os.path.join(SRC, 'LocalLlm.java')
LOCAL_CHAT = os.path.join(SRC, 'LocalChat.java')
PREFS = os.path.join(SRC, 'Prefs.java')
MAIN = os.path.join(SRC, 'MainActivity.java')
BAT = os.path.join(HERE, 'run_all_tests.bat')
VK = os.path.join(LIB, 'libggml-vulkan.so')
VERIFY = os.path.join(HERE, 'verify_local_llm_speed.py')
MF = os.path.join(SRC, 'ModelFile.java')
# ★★ 轮次68 新增：三态 / 失败记录 / 一键退路的界面动作 / 一页纸自测指南
GPU_MODE = os.path.join(SRC, 'GpuMode.java')
GPU_NOTICE = os.path.join(SRC, 'GpuFailNotice.java')
GPU_UI = os.path.join(SRC, 'GpuModeUi.java')
# ★★★ 轮次69 新增：模型管理那个纯类（五条退路的唯一判据与文案）
LOCAL_MENU = os.path.join(SRC, 'LocalModelMenu.java')
# ★★★ 轮次71 新增：调优旋钮那个纯类（8 个旋钮的唯一定义处）
LOCAL_TUNE = os.path.join(SRC, 'LocalTune.java')
# ★★★ 轮次72：自检的判定与文案全在这一个纯类里（本轮在这里修掉了那条**会撒谎**的判据）
LOCAL_SELFTEST = os.path.join(SRC, 'LocalSelfTest.java')
# ★★★ 轮次74：自动硬件计划（纯类）+ GGUF 层数读取（纯函数）
GPU_PLAN = os.path.join(SRC, 'GpuPlan.java')
MODEL_FILE = os.path.join(SRC, 'ModelFile.java')
FLOAT_SVC = os.path.join(SRC, 'FloatingService.java')
DELIVER = os.path.join(ROOT, '交付说明.md')
DELIV_DIR = r'D:\桌宠移植包'
GUIDE = os.path.join(DELIV_DIR, '本地模型-自测指南.md')

# ★★★ 版本号**现读清单**，不许写死。
#   2026-10-23（轮次79）全量套件抓到的真缺陷：BI 那条破坏项的锚点写死 `v7.5`，
#   而文档早在轮次77/78 就统一改成了 v7.6 ⇒ 锚点悬空、这条反向项一直是"未验证"，
#   只是之前没人重跑这一套。写死版本号 = 下一次换版本时它静默失效。
def _manifest_version():
    try:
        t = open(os.path.join(ROOT, 'AndroidManifest.xml'), encoding='utf-8', errors='replace').read()
        m = re.search(r'android:versionName="([^"]+)"', t)
        return m.group(1) if m else ''
    except OSError:
        return ''


VER = _manifest_version()
if not VER:
    raise SystemExit('读不到 AndroidManifest.xml 的 versionName —— 这条判据不许在"不知道版本"的情况下跑')
# ★★ 轮次68：工程里那份指南（与交付目录那份必须**逐字相同** —— 有判据钉着）
GUIDE_SRC_LOCAL = os.path.join(ROOT, '本地模型-自测指南.md')
VKCPP = os.path.join(r'D:\llamacpp_src\llama.cpp-b76b13379d3b7d67ab0bc424516bb6fd2522bd8e',
                     'ggml', 'src', 'ggml-vulkan', 'ggml-vulkan.cpp')

BAK = '.r66bak'


def recover():
    """上次被强杀留下的备份 → 先还原，再开工。"""
    # ★ 轮次67：llama.cpp 的源码目录**也要扫** —— Q~U 这几个破坏项打在
    #   D:\llamacpp_src\...\ggml-vulkan.cpp 上，不扫它的话中途被强杀就留下一个
    #   "被改坏的 llama.cpp 源码"，下一轮重编会静默带上一个假补丁。
    # ★★ 轮次68：交付目录**也要扫** —— 有几条破坏项打在
    #   `D:\桌宠移植包\本地模型-自测指南.md` 上（"指南与源码逐条对齐"那一节的另一半）。
    dirs = [JNI, SRC, HERE, LIB, os.path.dirname(VKCPP), DELIV_DIR]
    for d in dirs:
        if not d or not os.path.isdir(d):
            continue
        for n in os.listdir(d):
            if n.endswith(BAK):
                orig = os.path.join(d, n[:-len(BAK)])
                os.replace(os.path.join(d, n), orig)
                print('  （还原了上次残留的备份：%s）' % orig)


def patch(path, old, new, all_=False):
    """按字节替换（old/new 都是 bytes）。返回是否命中。

    ★ `all_=True` 表示**替换全部**：同一个说法常常在"注释里讲一遍 + 代码里写一遍"
    各出现一次，只换第一处（注释那处）等于没改 —— D 项就是这么被抓成假绿的。
    """
    with open(path, 'rb') as f:
        d = f.read()
    if old not in d:
        return False
    with open(path, 'wb') as f:
        f.write(d.replace(old, new) if all_ else d.replace(old, new, 1))
    return True


def run_verify():
    r = subprocess.run([sys.executable, VERIFY], capture_output=True)
    return r.returncode != 0, r.stdout.decode('utf-8', 'replace')


# (标签, 文件, old, new)   new=None 表示"删掉这个文件（改名）"
CASES = [
    ('A. ★★★ 把 `mp.n_gpu_layers = gpu_layers;` 改回写死的 0'
     '（= 回到"Vulkan 编了也永远不用"）',
     CPP, b'mp.n_gpu_layers = gpu_layers;', b'mp.n_gpu_layers = 0;'),

    ('B. ★★★ 把 device_is_gpu 里的 IGPU 那一支删掉'
     '（= 手机 GPU 又变成"一个都认不出来"）',
     CPP, b' || t == GGML_BACKEND_DEVICE_TYPE_IGPU', b''),

    ('C. ★★★ 把 `mp.load_mode = LLAMA_LOAD_MODE_MMAP;` 改成 AUTO'
     '（= 权重又被塞进匿名内存、被 zram 换进换出）',
     CPP, b'mp.load_mode = LLAMA_LOAD_MODE_MMAP;', b'mp.load_mode = LLAMA_LOAD_MODE_AUTO;'),

    ('D. ★★★ 把「关」那一支的"层数压回 0"删掉'
     '（= 关着 GPU 也照样把全部层交给 Vulkan —— 用户以为关了其实没关）',
     CPP, b'if (!on_gpu) want_gpu = 0;', b'if (false) want_gpu = 0;'),

    ('E. ★★★ 把 KV 账本清掉（= 又变成每句重算整个提示词）',
     CPP, b'g_cache_tokens = toks;', b'// g_cache_tokens = toks;'),

    ('F. ★★★ 去掉 nativeGenerate 的异常兜底（= Adreno 上直接 SIGABRT 闪退）',
     CPP, 'LOGE("★★ GPU 后端在生成过程中抛异常，已被兜住（没有闪退）：%s", e.what());\n'
          '        // ★★★ 开 GPU：**不重载、不重跑** —— 把原因交给 Java 侧（它会念给用户听）。'
          .encode('utf-8'),
     b'throw;'),

    ('G. ★★★ 把"开 GPU 失败"那句如实报错整句换掉（= 没有 GPU 设备时不再当面报错，'
     '用户又变成"以为在用 GPU、其实在慢慢用 CPU"）'
     '（★ 必须**全部**替换：这句话在 set_err 与注释里各出现一次）',
     CPP, '开 GPU 失败（已按你的设置不回落 CPU）'.encode('utf-8'), b'xx', True),

    # ★★★ 轮次74：默认档位必须是 **自动**（用户原话「不用用户手动调参数」）。
    ('H. ★★★ 把 GPU 档位的**默认值**从「自动」改回「关」'
     '（用户口径：默认必须是自动；改完"不用你调参数"这四个字就成了摆设）',
     GPU_MODE, b'public static final String DEFAULT_MODE = AUTO;',
     b'public static final String DEFAULT_MODE = OFF;'),

    # ★★ 轮次74：这一条换成"把**计划算出来的层数**写死 0" —— 自动档的核心就是"层数由计划给"。
    ('I. ★★ 让本地后端不再按**计划**决定层数（= 自动档变成装饰品，永远 0 层）',
     LOCAL_CHAT, b'plan.gpu ? plan.ngl : 0', b'0'),

    ('J. ★★ 去掉 ggml DEBUG 日志过滤（= logcat 被冲爆，证据又丢了）',
     CPP, b'if (level == GGML_LOG_LEVEL_DEBUG) return;', b';'),

    ('K. ★★ 去掉 prefill/decode 分段读数（= 又变成"只有一个总 tok/s"）',
     CPP, '分段读数：prefill'.encode('utf-8'), b'xx'),

    ('L. ★★ 去掉页错误采样（= 分不清"算得慢"还是"页被换出去了"）',
     CPP, b'read_self_faults(minf0, majf0);', b'minf0 = minf1 = 0;'),

    ('M. ★★★ 把 libggml-vulkan.so 从 lib/ 里删掉（= Vulkan 根本没进包）',
     VK, None, None),

    ('N. ★★ 把 BUILD_INFO 里"两个本地补丁"的记载删掉'
     '（= 下一个窗口重编必失败）'
     '（★ 全部替换：轮次67 之后这个说法在 BUILD_INFO 里出现了多次，'
     '只换第一处等于没破坏 —— 本轮的 N 项就是被自己抓出来的假绿）',
     INFO, '本地补丁'.encode('utf-8'), b'xx', True),

    ('O. ★★ 把 BUILD_INFO 里 Vulkan 的真实结论删掉（= 文档开始撒谎）'
     '（★ 同样必须**全部**替换：这个符号在 BUILD_INFO 里出现两次）',
     INFO, b'createComputePipeline', b'xx', True),

    ('P. ★★ 从 run_all_tests.bat 里去掉本套件的登记（= 以后永远不会跑）',
     BAT, b'verify_local_llm_speed.py', b'x'),

    # ---------------------------------------------------------------- 轮次67 新增
    # ★★ 纪律：破坏项锚点必须是**稳定的写法**（不用 md5 / 版本号 / 文件大小），
    #    而且必须打在**真代码**上 —— 打在注释上等于没破坏。
    ('Q. ★★★ 把 Adreno 的 matvec 回退**打开**（`use_subgroups = false` → true）'
     '（= 又回到"q4_K 1 列就让驱动拒绝 pipeline"的老路）',
     VKCPP, b'if (!dsp_force) use_subgroups = false;', b'if (!dsp_force) use_subgroups = true;'),

    ('R. ★★★ 把 Adreno 判据放宽成"所有厂商"（= 拿别的 GPU 当小白鼠；'
     '上游 PR 只敢对特定驱动开，我们只敢对 Qualcomm 开）',
     VKCPP, b'if (use_subgroups && device->properties.vendorID == 0x5143u) {',
     b'if (use_subgroups) {'),

    ('S. ★★★ 把 Adreno 回退整个删掉（= 这条真修法根本不存在，'
     'Adreno 上就只剩"开 GPU 直接报错"这一种结果）',
     VKCPP, b'if (!dsp_force) use_subgroups = false;', b'/* gone */'),

    ('T. ★★ 把 A/B 开关删掉（= 以后没法在同一台机器上做对照，'
     '"修好了"再也证明不了）',
     VKCPP, b'GGML_VK_ADRENO_SUBGROUP', b'GGML_VK_XX', True),

    ('U. ★★★ 把"失败着色器名字"打回 stderr（= Android 上名字又被吞掉，'
     '下一轮真机又是一句没有名字的 ErrorUnknown）',
     VKCPP, b'GGML_LOG_ERROR("ggml_vulkan: compute pipeline creation failed for %s: %s\\n",',
     b'fprintf(stderr, "ggml_vulkan: compute pipeline creation failed for %s: %s\\n",'),

    ('V. ★★★ 把系统属性桥接删掉（= 真机上没法切换对照组，'
     'JNI 里那条 A/B 通路是假的）',
     CPP, b'__system_property_get("debug.dsp.adreno_subgroup"', b'__system_property_get("debug.dsp.xx"'),

    ('W. ★★★ 把页错误解析改回老写法：不先吃掉 state 字符（= minflt/majflt 又恒为 -1，'
     '整条"是不是换页"的证据又没了）',
     CPP, b"if (*p == '\\0') return false;\n    ++p;", b'if (*p == \'\\0\') return false;'),

    ('X. ★★★ 把字段下标改回吃掉 state 之前的 v[7]/v[9]（= 数字对不上，'
     '报出来的页错误是一列错位的数据 —— 比没有还糟）',
     CPP, b'minflt = v[6];', b'minflt = v[7];'),

    ('Y. ★★★ 把"模型文件常驻了多少"（RssFile）那一路删掉'
     '（= 又只剩间接推断，拿不到"权重到底在不在内存里"的直接证据）'
     '（★ 全部替换：这个符号在代码与日志串里各出现一次）',
     CPP, b'RssFile', b'RssXx', True),

    ('Z. ★★★ 把权重常驻（fadvise + mlock）整个删掉（= 又只剩"猜"）'
     '★ 轮次71：调用点多了第二个实参（界面上的「权重锁内存」旋钮），锚点跟着改',
     CPP, b'try_pin_model_file(path.c_str(), tune.mlock);', b'/* no pin */'),

    ('AA. ★★★ mlock 失败时改成静默（= 真机上"到底锁没锁上"看不见，'
     '这一路证据又没了）',
     CPP, 'mlock %.0f MB **失败**'.encode('utf-8'), b'mlock xx'),

    ('AB. ★★★ 把内存建议的阈值改成"永远不会提醒"（= 那一行等于不存在）',
     MF, b'ADVISE_MAX_BYTES = 600L * 1024 * 1024;', b'ADVISE_MAX_BYTES = Long.MAX_VALUE;'),

    ('AC. ★★★ 把设置页那一行改成"永远显示"（= 对 0.5B 用户也喊"换 0.5B"，'
     '变成噪声、用户就不看了）',
     MAIN, b'if (memAdvice != null) addLabel(box, memAdvice);', b'addLabel(box, memAdvice);'),

    ('AD. ★★ 把"内存建议里要带真机读数"删掉（= 没有数字的建议等于没说）'
     '（★ 全部替换：注释里也提过这个读数）',
     MF, '6.5 秒/字'.encode('utf-8'), '很慢'.encode('utf-8'), True),
    ('AE. ★★★ 把正式版 `nativeLoaded` 的 return 再删掉'
     '（= 正式版 isLoaded() 返回值不确定，轮次62 那个老缺陷复活）',
     CPP, b'return Java_com_deepseek_pet_LocalLlm_nativeLoaded(env, cls);',
     b'Java_com_deepseek_pet_LocalLlm_nativeLoaded(env, cls);'),

    ('AF. ★★ 把 BUILD_INFO 里"glslc 假设被推翻"那段结论删掉'
     '（= 下一个窗口又会去装 Vulkan SDK 白花一天）'
     '（★ 全部替换：11.1 与 11.6 各出现一次）',
     INFO, '逐字节相同'.encode('utf-8'), b'xx', True),

    ('AG. ★★ 把 BUILD_INFO 里 llama-bench 的编法/推送方式删掉'
     '（= 下一轮又要从零摸一遍怎么量 CPU 基线）',
     INFO, b'/data/local/tmp', b'xx', True),

    # ---------------------------------------------------------------- 轮次69 新增
    # ★★★ 本组最要紧的一条：**"开 GPU"不许回落 CPU**。
    #   破坏方式 = 往 ON 那一支里塞一个"按纯 CPU 重载"的调用 ——
    #   结构性判据（"ON 那一整段里没有 do_load_locked"）必须当场变红。
    ('AH. ★★★ 往 ON 那一支里塞一个"按纯 CPU 重载"（= 开 GPU 又变成静默回落，'
     '正是用户两次都点名不要的那件事）',
     CPP, 'gpu_required_failed_safe("生成过程中 Vulkan 后端抛出异常", e.what());'.encode('utf-8'),
     'gpu_required_failed_safe("生成过程中 Vulkan 后端抛出异常", e.what());'
     ' do_load_locked("", 4, 2048, 512, 0, DSP_GPU_MODE_OFF);'.encode('utf-8')),

    ('AI. ★★★ 让 ON 那一支**不再返回错误**（= 开 GPU 失败之后继续往下走）'
     '★ 轮次71：中间多了一行 last_fail（自检读数），锚点跟着改',
     CPP, 'gpu_required_failed_safe("生成过程中 Vulkan 后端抛出异常", e.what());\n'
          '            last_fail("生成（Vulkan 后端抛异常）");\n'
          '            return nullptr;'.encode('utf-8'),
     'gpu_required_failed_safe("生成过程中 Vulkan 后端抛出异常", e.what());'.encode('utf-8')),

    ('AJ. ★★★ 反过来：往 `do_load_locked` 的 **ON 分支**里塞一个"按纯 CPU 重载"'
     '（= 加载阶段也能偷偷回落 CPU）',
     CPP, b'gpu_layers = (want_gpu > 0) ? want_gpu : 999;',
     b'do_load_locked("", 0, 0, 0, 0, DSP_GPU_MODE_OFF);'
     b' gpu_layers = (want_gpu > 0) ? want_gpu : 999;'),

    ('AK. ★★★ 让"一键退路"**只弹框、不写设置**（= 点了没反应 —— 用户点名的坏法）',
     GPU_UI, b'Prefs.setLocalGpuMode(ctx, m);', b';'),

    # ★★★ 这一条就是用户报障的**根因本身**：把那个进程级粘性标记加回加载路径。
    #   它一旦回来，一次 GPU 失败之后"换任何一个 gguf"都只会得到同一句失败。
    ('AL. ★★★ **把"以前失败过就不许再试"那个粘性标记加回来**'
     '（= 用户报的"模型无法更换其他的 gguf 模型"原地复活：一次失败之后每次都直接失败）',
     CPP, b'int want_gpu = (int) jgpu_layers;',
     b'if (g_gpu_runtime_failed) { set_err("xx"); return false; }\n'
     b'    int want_gpu = (int) jgpu_layers;'),

    ('AM. ★★★ 开 GPU 失败之后**不清现场**（半死的 Vulkan 会话留在内存里 = 下一次撞上去就崩）',
     CPP, 'try {\n        session_free();\n    } catch (...) {\n        // ★ 半死状态下 llama_free 也可能抛'.encode('utf-8'),
     'try {\n        ;\n    } catch (...) {\n        // ★ 半死状态下 llama_free 也可能抛'.encode('utf-8')),

    # ★★★ 本轮新增的核心：**模型管理**（卸载 / 换模型 / 退路不许被堵死）
    ('AN. ★★★ 让 GPU 出问题**又能影响退路**（往那个纯类里塞一句 GPU 判断）'
     '—— "五条退路不许被堵死"这条性质就靠"它一个 Gpu 都不许有"钉着',
     LOCAL_MENU, b'public static boolean canUnload() { return true; }',
     b'public static boolean canUnload() { return !GpuMode.isOn(null); }'),

    ('AO. ★★★ 把卸载那颗按钮改成**永远不可点**（= 用户点名要的"卸载功能"变成摆设）',
     LOCAL_MENU, b'public static boolean canUnload() { return true; }',
     b'public static boolean canUnload() { return false; }'),

    ('AP. ★★★ 在面板里加一句"GPU 失败就直接返回"'
     '（= 后面的卸载/切换/删除全都不显示 —— 正是"点了没反应"的同一种坏法）',
     MAIN, b'final String gpuFail = GpuFailNotice.last();',
     b'final String gpuFail = GpuFailNotice.last();\n        if (gpuFail != null) return;'),

    ('AQ. ★★★ 卸载的日志/文案里不再说"文件保留"'
     '（= 用户会以为卸载把文件删了，不敢点）',
     LOCAL_MENU, '内存已交还、★ 文件保留，一个字节都不动，'.encode('utf-8'),
     '内存已交还，'.encode('utf-8')),

    ('AR. ★★★ 点「⏏ 卸载当前模型」**只清账本、不真卸**'
     '（= 内存根本没放掉，用户白点一下）',
     LOCAL_CHAT, b'\n            LocalLlm.unload();\n        } catch (Throwable t) {',
     b'\n            ;\n        } catch (Throwable t) {'),

    ('AS. ★★★ 换模型**不卸旧的**（= 换了也没效果，下次对话还用内存里那个旧的）',
     MAIN, 'try { LocalLlm.unload(); } catch (Throwable ignore) {}\n'
           '            LocalChat.forget();          // ★ 忘掉"内存里载着谁"（轮次64）'
           .encode('utf-8'),
     b';'),

    ('AT. ★★★ 把"复用的前提是**同一个文件**"放宽成只看 isLoaded()'
     '（= 换了模型之后继续拿旧模型回答 —— "换了没效果"最难查的那一类）',
     LOCAL_CHAT, b'if (LocalLlm.isLoaded() && path.equals(loadedPath)) {',
     b'if (LocalLlm.isLoaded()) {'),

    ('AU. ★★★ 认不出的 GPU 档位当成"开"（= 一个脏字符串就能让本地对话直接不可用，'
     '而且用户不知道为什么）',
     LOCAL_LLM, b'        if (mode == GpuMode.CODE_ON) return GpuMode.CODE_ON;\n'
                b'        return GpuMode.CODE_OFF;',
     b'        return GpuMode.CODE_ON;'),

    ('AV. ★★★ 引导文案里不再说"没有偷偷回落 CPU"（= 用户不知道这一句为什么没答上来，'
     '以为是卡住了）',
     LOCAL_CHAT,
     '「开」用 GPU、没有偷偷回落 CPU'.encode('utf-8'),
     '失败'.encode('utf-8')),

    ('AW. ★★ 两态选项里不写"代价"（= 用户在点之前不知道"开"的代价）'
     '（★ 全部替换：同一句话在 describe 与 choices 里各出现一次，'
     '只换第一处会换到 describe 上 ⇒ 假绿，本轮 AW 就是这么被抓出来的）',
     GPU_MODE, '★ 代价：这台机器的 GPU 用不了时'.encode('utf-8'),
     '★ 注意：'.encode('utf-8'), True),

    ('AX. ★★★ 把「关」那一档的常量值改掉（= 用户再也回不到纯 CPU，'
     '"用不了 GPU 就彻底不能用"）',
     GPU_MODE, b'public static final String OFF = "off";',
     b'public static final String OFF = "off-x";'),

    ('AY. ★★★ 指南与源码**漂了**（把指南里"驱动拒绝计算管线"那行改掉）'
     '—— 指南里引用的日志必须与源码逐字一致，改一边就必须红',
     GUIDE, b'compute pipeline creation failed for ', b'xx'),

    ('AZ. ★★★ 指南从交付目录里消失（= 用户自己没法测 GPU，'
     '本轮"交给用户自测"这条交付就落空了）',
     GUIDE, None, None),

    ('BA. ★★ 交付说明里不再指向那份自测指南（= 用户根本不知道有这东西）',
     DELIVER, '本地模型-自测指南.md'.encode('utf-8'), b'xx', True),

    ('BB. ★★★ 两份指南**漂了**（只改工程里那一份 = 用户拿到的与源码包里的不是同一份；'
     '工程被"同一个事实抄两处就一定会漂"咬过）',
     GUIDE_SRC_LOCAL, '判定表'.encode('utf-8'), '判定xx'.encode('utf-8')),

    ('BC. ★★★ 指南里把用户这一轮问的那一节**删掉**（= 用户还是不知道该怎么卸载/换模型）',
     GUIDE, '怎么卸载 / 怎么换模型'.encode('utf-8'), 'xx'.encode('utf-8')),

    ('BD. ★★ 把 BUILD_INFO 里轮次69 那一节的宏记法删掉'
     '（= 下一个窗口不知道 Java/JNI 之间靠哪两个码对接）'
     '（★ 全部替换：这个符号在宏定义与说明里各出现一次）',
     INFO, b'DSP_GPU_MODE_ON', b'xx', True),
    ('BE. ★★ 把 BUILD_INFO 里"三条本地补丁逐条核实过"的记载删掉'
     '（= 下一个窗口重编前不会去核实，可能带着假补丁出包）'
     '（★ 全部替换：这个说法在 11.2 与 12.2 各出现一次 —— 只换第一处 = 假绿）',
     INFO, b'use_subgroups = false', b'xx', True),
    ('BF. ★★★ 把 BUILD_INFO 里"不在编译时跑反向套件"这条教训删掉'
     '（= 下一个窗口再踩一次：脚本被 PermissionError 打断、留下被改坏的源文件）'
     '（★ 全部替换：这句话在 12.4 与 13.4 各出现一次 —— 只换第一处 = 假绿，'
     '本轮 BF 就是被自己这么抓出来的）',
     INFO, b'PermissionError', b'xx', True),
    ('BG. ★★★ 把 BUILD_INFO 里"开 GPU 不解决内存瓶颈"的诚实边界删掉'
     '（= 用户会以为"开 GPU = 就快了"）'
     '（★ 全部替换：这句话在 12.5 与 13.5 各出现一次）',
     INFO, b'163 MB/s', b'xx', True),
    ('BH. ★ 把 BUILD_INFO 里本轮库体积的记载删掉（易变，但下一个窗口要能对得上）',
     INFO, b'66,912', b'xx', True),

    ('BI. ★★★ 指南里把"装哪个 APK"写成源码树里的名字（交付 zip 里那个叫 `_开发版.apk`）'
     '—— 用户照着敲会直接卡住，正是"清单里写了实际不存在的东西"那类坑',
     GUIDE, ('DeepSeekPet_v%s_开发版.apk' % VER).encode('utf-8'),
     ('DeepSeekPet_v%s_rebuilt.apk' % VER).encode('utf-8')),

    ('BJ. ★★★ 把旧 boolean 的兼容语义改坏（"明确关过"也当成默认开）'
     '—— 升级上来的用户明明关过 GPU，却被他这一轮悄悄打开',
     GPU_MODE, b'if (legacyPresent && !legacyOn) return OFF;', b'return DEFAULT_MODE;'),
    ('BK. ★★★ 把 Prefs 的兼容链整条剪掉（旧键存在过也不迁移了 ⇒ 老用户直接吃新默认）',
     PREFS, b'if (sp.contains(KEY_LOCAL_GPU)) return GpuMode.migrate(true, localGpuLegacy(ctx));',
     b'return GpuMode.migrate(false, true);'),
    ('BL. ★★ 把设置页的两态选项换成空表（= 用户点进去什么也选不了，两态只存在于代码里）',
     MAIN, b'GpuMode.choices();', b'new String[0];'),

    # ---------------------------------------------------------------- 轮次71 新增
    # ★★★ 这一组守的是本轮的两件交付：
    #   ① 「🎛 本地推理调优」那 8 个旋钮**真的**从界面走到原生层（不是画着好看的）；
    #   ② 「🧪 本地推理自检」**真的**能在用户手机上跑出结论（后台/可取消/可复制/失败也有用）。
    #   ★ 破坏项锚点全部是**稳定写法**（不用 md5 / 版本号 / 文件大小）。
    ('BM. ★★★ 把调优串从 JNI 入口上摘掉（nativeLoad 少一个参数）'
     '（= 8 个旋钮在界面上点得动、写进了设置，却**永远传不到原生层** —— '
     '"改了没效果"里最难查的那一种）'
     '★ all_=True：开发版与正式版**两套签名**都要摘掉，只摘一套的话另一套还留着那句话'
     '（判据是"两边都有"），会变成假绿。',
     CPP, b'jint jgpu_layers, jint jgpu_mode, jstring jtune)',
     b'jint jgpu_layers, jint jgpu_mode)', True),

    ('BN. ★★★ 让对话后端**不再读**调优设置（每次载入都塞默认调优串）'
     '（= 用户在设置页改了半天，实际载入时用的还是默认参数）'
     '★ all_=True：`loadPlanned(ctx, f, gpuMode, tune)` 在 LocalChat 里出现两次'
     '（ensureLoaded 与 selfTest），只换第一处 = 假绿。',
     LOCAL_CHAT, b'loadPlanned(ctx, f, gpuMode, tune)',
     b'loadPlanned(ctx, f, gpuMode, LocalTune.defaults())', True),

    ('BO. ★★★ 把 Prefs 的**规范化**去掉（原样读那条串）'
     '（= 一个脏值就能直接落到原生层 —— 而"认不出走默认"正是这套旋钮的安全底线）',
     PREFS, b'return LocalTune.normalize(sp.getString(KEY_LOCAL_TUNE, null));',
     b'return sp.getString(KEY_LOCAL_TUNE, null);'),

    ('BP. ★★★ 把 ubatch 的白名单放宽成"随便什么值都要"'
     '（= 一个脏值/超大值能直接喂进 llama.cpp，症状是建图失败或内存炸）',
     CPP, b'if (v == "128" || v == "256" || v == "512" || v == "1024") t.ubatch = atoi(v.c_str());',
     b'if (!v.empty()) t.ubatch = atoi(v.c_str());'),

    ('BQ. ★★★ 把 ubatch 的默认值从 512 改成 128'
     '（= "什么都不动 = 与上一版完全一样"这条承诺当场失效：不玩 A/B 的用户被悄悄改了行为）',
     LOCAL_TUNE, b'ADRENO_AUTO, FLASH_AUTO, "512", ON, LOADMODE_MMAP, MMVQ_AUTO, ON, ON,',
     b'ADRENO_AUTO, FLASH_AUTO, "128", ON, LOADMODE_MMAP, MMVQ_AUTO, ON, ON,'),

    ('BR. ★★★ 把"本次调优"那行日志删掉'
     '（= 真机上"这次到底用的哪套参数"又没有证据了 —— 自检报告里也就没有可对的东西）',
     CPP, '本次调优（与本轮默认不同的项）：'.encode('utf-8'), b'xx', True),

    ('BS. ★★★ 让后端级那四个旋钮也去放掉内存里的模型'
     '（= 放了也没用（Vulkan 设备一个进程只造一次），用户还白等一次载入 —— '
     '这正是"改了没效果"的第二种来源）',
     MAIN, b'if (LocalTune.needsRestart(i)) {', b'if (false) {'),

    ('BT. ★★★ 让 needsRestart 对 Adreno 那一项也说"不用重启"'
     '（= 用户改完直接载入，ggml 早就把旧值读走了 ⇒ 他会得出"这个参数没用"的错误结论）',
     LOCAL_TUNE, b'return K_ADRENO.equals(key(i)) || K_MMVQ.equals(key(i))',
     b'return false || K_MMVQ.equals(key(i))'),

    ('BU. ★★★ 去掉 mlock 旋钮的实际作用（判据里不看 want_mlock）'
     '（= 那个旋钮变成摆设：关掉它权重照样被尝试锁进内存）',
     CPP, b'const bool enabled = want_mlock && !prop_off;', b'const bool enabled = !prop_off;'),

    ('BV. ★★★ 把「🧪 本地推理自检」改成**在主线程**跑'
     '（= 载入几秒 + 生成几秒，界面直接 ANR —— 用户看到的是"点了就卡死"）',
     MAIN, 'new Thread(new Runnable() { @Override public void run() {\n'
           '            final LocalChat.Check c = LocalChat.selfTest(MainActivity.this);'
           .encode('utf-8'),
     'final LocalChat.Check c = LocalChat.selfTest(MainActivity.this);'.encode('utf-8')),

    ('BW. ★★★ 自检**不先放掉**内存里的模型'
     '（= 用的是上一次载入时的旧参数，自检结果就是假的 —— 比没有自检更糟）',
     LOCAL_CHAT, '自检：先放掉内存里的模型，按当前设置重新载入'.encode('utf-8'), b'xx'),

    ('BX. ★★★ 自检改用随机温度'
     '（= 两次自检的数字会抖，用户就没法"改一个旋钮比一次"了）'
     '★ all_=True：轮次74 起自检有**两次** generate（降级后会重跑）—— 只改第一处 = 假绿。',
     LOCAL_CHAT, b'LocalLlm.generate(LocalSelfTest.PROMPT, LocalSelfTest.MAX_TOKENS, 0.0f, null)',
     b'LocalLlm.generate(LocalSelfTest.PROMPT, LocalSelfTest.MAX_TOKENS, 0.7f, null)', True),

    ('BY. ★★★ 「📋 复制结果」不接剪贴板（= 用户拿不到结果，整颗按钮白做）',
     MAIN, 'cm.setPrimaryClip(android.content.ClipData.newPlainText("本地推理自检", text));'
     .encode('utf-8'),
     b';'),

    ('BZ. ★★★ 取消之后**照样弹结果**（= 用户明明取消了，几秒后又跳出一屏结果）',
     MAIN, b'if (myRun != selfTestRun) {', b'if (false) {'),

    ('CA. ★★★ 报告里删掉「最近的后端警告/错误」那一行'
     '（= **失败的着色器名**就没了 —— 那正是下一轮最要紧的线索）',
     CPP, 's += "最近的后端警告/错误=" + log_tail_text() + "\\n";'.encode('utf-8'), b's += "";'),

    ('CB. ★★★ 报告里删掉 `数值.*` 那几行（= Java 侧没法判定了，结论只能瞎猜）',
     CPP, '数值.decode_ms_per_token='.encode('utf-8'), b'xx'),

    ('CC. ★★★ 默认档位**不清环境变量**（只 setenv 不 unsetenv）'
     '（= 同一个进程里先设了 1、用户又改回默认，变量还留着 ⇒ "改了没效果"）',
     CPP, b'unsetenv(', b'__no_unset(', True),

    ('CD. ★★★ 指南里把「🎛 GPU 参数怎么 A/B」那一整节删掉'
     '（= 用户还是不会自己试参数，这一轮的交付就落空了）',
     GUIDE, 'GPU 参数怎么 A/B'.encode('utf-8'), b'xx'),

    ('CE. ★★★ 指南里把「🧪 自检按钮怎么用」那一整节删掉'
     '（= 用户不知道有那颗按钮 —— 他只会去挂 logcat，然后卡住）',
     GUIDE, '🧪 自检按钮怎么用'.encode('utf-8'), b'xx'),

    ('CF. ★★★ 让 flash attention 旋钮失效（"强制开"那一支删掉）'
     '（= 旋钮点得动，实际永远走 auto）',
     CPP, b'cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;',
     b'cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;'),

    ('CG. ★★★ 让 n_ubatch 不再夹到 n_batch 以下'
     '（= 用户选了 1024 而 batch 只有 512 时，llama.cpp 建图直接报错 —— '
     '一个旋钮把本地对话弄坏）',
     CPP, b'if (ubatch > batch) ubatch = batch;', b';'),

    ('CH. ★★★ 把 BUILD_INFO 里轮次71 那一节删掉'
     '（= 下一个窗口既不知道有 8 个旋钮、也不知道自检报告从哪来）'
     '（★ 全部替换：这个说法在标题与正文里各出现一次）',
     INFO, '轮次71'.encode('utf-8'), b'xx', True),

    ('CI. ★★★ 把 BUILD_INFO 里"哪些是推测、没验过"的标注删掉'
     '（= 文档开始把我们的推测说成有依据的东西 —— 这正是本工程最恨的那种撒谎）'
     '（★ 全部替换：这句话在表格与边界里各出现一次）',
     INFO, '我们的推测'.encode('utf-8'), b'xx', True),

    ('CJ. ★★ 把 BUILD_INFO 里本轮库体积的记载删掉（下一个窗口要对得上）',
     INFO, b'88,344', b'xx', True),

    ('CK. ★★★ 把 BUILD_INFO 里那个新坑（锚点会悬空 / 假绿）的教训删掉'
     '（= 下一个窗口再踩一次：新增代码之后不重数"同一句话出现几次"）'
     '（★ 全部替换：这段话在 14.4 里出现多次）',
     INFO, b'all_=True', b'xx', True),

    ('CL. ★★★ 让 n_ubatch 的 1024 那一档变成**假旋钮**'
     '（去掉"把 n_batch 一起抬上去"那一行 ⇒ 用户选 1024 会被静默夹回 512：'
     '界面点得动、日志写着意图，实际什么都没变）',
     CPP, b'if (ubatch > batch && ubatch <= ctx_size) batch = ubatch;', b';'),

    # ★★★ 轮次72（真机实测那一轮）：产品侧只有一处改动（面板必须能滚），
    #     剩下的是"文档里的真机事实"必须留得住 —— 那些事实是整轮最贵的产出。
    ('CM. ★★★ 把本地模型面板的 `ScrollView` 摘掉（改回 `.setView(box)`）'
     '（= 真机上尾部整段又被裁掉，用户**点不到** 🎛 调优入口 —— 这正是轮次72 抓到并修掉的 bug）',
     MAIN, b'.setView(sc)', b'.setView(box)'),

    ('CN. ★★★ 把 BUILD_INFO 里轮次72 那一整节删掉'
     '（= 下一个窗口不知道这台机器 GPU 用不了、又会去猜参数）'
     '（★ 全部替换：节标题与正文里都提到轮次72）',
     INFO, '轮次72'.encode('utf-8'), b'xx', True),

    ('CO. ★★★ 把 BUILD_INFO 里的真机读数（纯 CPU 44.75 tok/s）删掉'
     '（= "关 GPU 反而快得多"这个结论就没证据了）'
     '（★ 全部替换：读数表与结论段各出现一次）',
     INFO, '44.75 tok/s'.encode('utf-8'), b'xx', True),

    ('CP. ★★★ 把 BUILD_INFO 里"六组 A/B 的失败管线名"删掉'
     '（= 下一个窗口不知道该看哪个算子）',
     INFO, 'mul_mat_vec_q4_k_f32_f32'.encode('utf-8'), b'xx', True),

    ('CQ. ★★★ 把 BUILD_INFO 里那条**判据局限**删掉（`pipeline->name` 是固定串）'
     '（= 下一个窗口继续把"失败管线名"当成"着色器变体"的证据 —— 这正是本轮纠正的过度解读）',
     INFO, b'pipeline->name = name;', b'xx'),

    ('CR. ★★★ 把 BUILD_INFO 里"内存不是瓶颈 / RssFile ≈ 1.0 GB"那条删掉'
     '（= 旧的 163 MB/s、6.5 秒/字 又会被当成这台机器的结论）',
     INFO, '内存不是瓶颈'.encode('utf-8'), b'xx'),

    ('CS. ★★★ 把 BUILD_INFO 的**诚实边界**（没试的旋钮 / 那台机器不在线 / 联发科）删掉'
     '（= 文档开始把"这台机器 GPU 用不了"含糊成"GPU 已全面可用"）',
     INFO, '没试的旋钮'.encode('utf-8'), b'xx'),

    ('CT. ★★★ 把一页纸指南里那一节"真机实测结论"删掉'
     '（= 用户不知道"这台手机别开 GPU"、也不知道纯 CPU 就有 44.75 tok/s）',
     GUIDE, 'Adreno 830'.encode('utf-8'), b'xx'),

    # ★★★ 轮次72 的第二组：真机实测**顺手抓到的两个报告缺陷**必须留得住 ——
    #   ① 失败档把 `-1`（"这一步没跑到"的哨兵）当数字印成 `-1 ms`；
    #   ② 自检结论只按 `has_vulkan_gpu` 判 ⇒ 把"纯 CPU 很快"印成「GPU 真的用上了」（**会撒谎**）。
    #   ★ 锚点全部是稳定写法（不用 md5 / 版本号 / 文件大小）。
    ('CU. ★★★ 把 `ms_text` 的哨兵分支废掉（`-1` 又被当数字印成 `-1 ms`）'
     '（= 读报告的人从这一行读出一个**不存在的读数**，而这一屏正是要发给别人看的那一份）',
     CPP, 'if (v < 0.0) return "未测到（这一步没跑到）";'.encode('utf-8'),
     'if (false) return "";'.encode('utf-8')),

    ('CV. ★★ 让"没测到耗时就不印 tok/s"失效（= 又出现 `0.00 tok/s`，'
     '被读成"跑了但极慢"，而事实是**根本没跑**）',
     CPP, b'if (ms <= 0.0) return std::string();', b'if (false) return std::string();'),

    ('CW. ★ 让页错误的"没采到"失效（= 又印成 `+-1`，同一个毛病）',
     CPP, 'if (a < 0 || b < 0) return "（没采到）";'.encode('utf-8'),
     'if (false) return "";'.encode('utf-8')),

    ('CX. ★★★ **本轮最要紧的一条**：把自检结论的判据从 `数值.offloaded_layers` '
     '换回 `数值.has_vulkan_gpu`（= 真机 E7 那一份报告又会被印成「✅ GPU 真的用上了」，'
     '而它其实是**纯 CPU**、0 层交给 GPU —— 那就是把"纯 CPU 很快"写成"GPU 加速成功"）'
     '★ all_=True：这个键名在 verdict 与 nextStep 里各出现两次，只换一处等于没换。',
     LOCAL_SELFTEST, '数值.offloaded_layers'.encode('utf-8'),
     '数值.has_vulkan_gpu'.encode('utf-8'), True),

    ('CY. ★★★ 把 `CPU_RECOMMEND` 从结论里摘掉'
     '（= 用户又只剩"跑起来了"四个字，看不到"我该怎么办" —— 他看不懂 logcat）',
     LOCAL_SELFTEST, b"sb.append('\\n').append(CPU_RECOMMEND)",
     b"sb.append('\\n').append(\"\")"),

    ('CZ. ★★★ 把"按机型分段"里那台机器的 product 名删掉'
     '（= 两台机器（25091RP04C/piano 与 2210132C/nuwa）的读数又可以混着说了 —— '
     '本工程真踩过：把 8 GB 那台的 6.5 秒/字 当成另一台的结论）',
     LOCAL_SELFTEST, b'2210132C / nuwa', b'2210132C'),

    ('DA. ★★ 把报告结尾"必须带上机型"那句删掉'
     '（= 用户发回来的读数没有机型，按机型分段这条纪律就落不了地）',
     LOCAL_SELFTEST, '机型那一串**必须带上**'.encode('utf-8'), b'xx'),

    ('DB. ★★★ 把本地模型面板那个高度上限从 0.72 屏高改成 1.0'
     '（= 面板又比对话框高，尾部整段被裁 —— 真机上就是这么丢掉 🎛 入口的）',
     MAIN, b'heightPixels * 0.72f', b'heightPixels * 1.0f'),

    # ★★★ 轮次73：第三档归约（HYBRID / `_subgroup_no_shmem`）+ 设备级 fp16 ——
    #   这一组守的是"Adreno 上唯一没试过的那条 SPIR-V 真的选得到"。
    ('DC. ★★★ 把 ggml 里那条 HYBRID 选择补丁删掉'
     '（= 第三档归约在 Qualcomm 上又变成**永远选不到**：界面上点得动、日志里写着 hybrid，'
     '实际走的还是默认那条 —— 这一轮最重要的那个对照就作废了）',
     VKCPP, b'if (getenv("GGML_VK_ADRENO_HYBRID") != nullptr && '
            b'ctx->device->vendor_id == 0x5143u) {',
     b'if (false) {'),

    ('DD. ★★★ 让 JNI 只设 SUBGROUP、不设 HYBRID'
     '（= `use_subgroups16` 为真但 `dmmv_wg` 还是 SUBGROUP ⇒ 选到的仍是第二档，'
     '用户会得出"第三档也没用"的错误结论）',
     CPP, b'setenv("GGML_VK_ADRENO_HYBRID", "1", 1);', b';'),

    ('DE. ★★★ 让 fp16 旋钮变成假旋钮（"关"也照样 unsetenv）'
     '（= 界面上选得动、日志里写着关，实际设备仍然用 fp16 —— 本工程最恨的那类缺陷）',
     CPP, b'setenv("GGML_VK_DISABLE_F16", "1", 1);', b'unsetenv("GGML_VK_DISABLE_F16");'),

    ('DF. ★★★ 把新键 `f16` 插到 KEYS 最前面（不是末尾）'
     '（= 老版本存下来的调优串规范化之后会**变样**，磁盘上的"同一套参数"每次读都不同；'
     '而且 KEYS 变成 10 项、取值表还是 9 行 ⇒ 当场越界 —— 向后兼容这条承诺失效）',
     LOCAL_TUNE, b'            K_ADRENO, K_FLASH, K_UBATCH, K_MLOCK, K_LOADMODE, K_MMVQ, K_FUSION, K_ASYNC,',
     b'            K_F16, K_ADRENO, K_FLASH, K_UBATCH, K_MLOCK, K_LOADMODE, K_MMVQ, K_FUSION, K_ASYNC,'),

    # ★★★ 轮次74：自动硬件计划 —— 这一组守的是"自动真的会做决定、会降级、会读 GGUF"。
    ('DG. ★★★ 让已知坏机型判据失效（Adreno 830 又会被自动选成 GPU）'
     '（= 这台机器每次载入都白试一次 GPU、每次都要等失败 —— 用户看到的就是"自动怎么这么慢"）',
     GPU_PLAN, b'return d.contains("Adreno(TM)830") || d.contains("Adreno830") || d.contains("Adreno830v2");',
     b'return false;'),

    ('DH. ★★★ 让 degrade 不再真的降级（把 gpu 原样带过去）'
     '（= "自动降级兜底"这句话当场失效：GPU 失败之后还是 GPU 计划）',
     GPU_PLAN, b'q.gpu = false;', b'q.gpu = p != null && p.gpu;'),

    ('DI. ★★★ 把自动降级的**档位条件**删掉（手动「开」也会偷偷降级）'
     '（= "开"不再是"只走 GPU"的对照组，A/B 就没法做了；而且这违背用户点「开」时的承诺）',
     LOCAL_CHAT, b'if (!ok && GpuMode.isAuto(gpuMode) && plan.gpu) {', b'if (false) {'),

    ('DJ. ★★★ 让 GGUF 层数读取永远失败（层数全靠估算）'
     '（= "动态算 n-gpu-layers"退化成"按 32 层猜" —— 1.5B 与 7B 会被算成同一种比例）',
     MODEL_FILE, b'if (key.endsWith(".block_count") || "block_count".equals(key)) {',
     b'if (false) {'),
]

print('=' * 62)
print('反向破坏检查：本地模型提速层（每条都必须让 verify_local_llm_speed.py 变红）')
print('=' * 62)
print()

# 先确认基线是绿的（否则后面全是假红）
recover()
ok, _ = run_verify()
if ok:
    print('  [FAIL] 基线就是红的 —— 先修好 verify_local_llm_speed.py 再跑破坏项')
    sys.exit(1)
print('  [OK]   基线：verify_local_llm_speed.py 是绿的')
print()

passed = 0
failed = []
for case in CASES:
    label, path, old, new = case[0], case[1], case[2], case[3]
    all_ = (len(case) > 4 and case[4])
    if not os.path.exists(path):
        failed.append(label + '（文件不存在：%s）' % path)
        print('  [FAIL] ' + label + ' —— 文件不存在')
        continue
    b = path + BAK
    try:
        shutil.copyfile(path, b)
        if new is None:
            os.remove(path)                       # 破坏 = 文件消失
        else:
            if not patch(path, old, new, all_):
                failed.append(label + '（锚点没找到，判据可能已经悬空）')
                print('  [FAIL] ' + label + ' —— **锚点没找到**（判据悬空了，赶紧去看）')
                continue
        red, _ = run_verify()
        if red:
            passed += 1
            print('  [OK]   ' + label)
        else:
            failed.append(label + '（改坏了还是绿的 = 假绿）')
            print('  [FAIL] ' + label + ' —— **改坏了还是绿的**')
    finally:
        if os.path.exists(b):
            os.replace(b, path)                   # ★ 字节精确还原
        elif new is None and os.path.exists(path + BAK):
            os.replace(path + BAK, path)

print()
print('=' * 62)
if failed:
    print('%d 项失败：' % len(failed))
    for f in failed:
        print('   - ' + f)
    sys.exit(1)
print('ALL %d REVERSE CHECKS PASSED（本地模型提速层）' % passed)
