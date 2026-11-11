// dspllm_jni.cpp —— 本地 GGUF 推理引擎的 JNI 包装（轮次62 · 引擎层；轮次66 · 提速）
//
// ★ 为什么自己写 JNI、而不去找现成的 Android 绑定库：
//   ① 本工程是**自研构建链**（没有 Gradle / AAR / Maven），引第三方 AAR 得先把它拆开；
//   ② 我们只要四件事（加载 / 生成 / 卸载 / 查状态），
//      而桌宠气泡是**逐句出字**的 ⇒ "流式回调"是硬需求，不是可选装饰；
//   ③ 自己写 ⇒ 符号名、回调时机、错误话术全在自己手里，能被离线断言逐条钉住。
//
// ★★ 三个"编译期不报、运行时才炸"的坑，这里都提前堵上（每个都有对应断言）：
//   ① **Java 方法名与 JNI 函数名必须逐字对应** —— 拼错时 javac 不报、链接器不报，
//      要到真机第一次调用才抛 UnsatisfiedLinkError。断言套件把两边逐字比对。
//   ② **UTF-8 会被 token 切开** —— 一个汉字的 3 个字节可能落在相邻两个 token 里；
//      逐 token 直接转 String 会得到半个字（乱码）。
//      所以这里**攒够完整序列才回调**（`utf8_consume`），尾巴留到下一个 token。
//      桌宠的对话里全是中文 ⇒ 这不是理论问题，是必然会遇到的。
//   ③ **`NewStringUTF` 吃的是"修改版 UTF-8"**（补充平面字符要写成代理对），
//      把标准 UTF-8 直接喂进去，emoji 会变乱码。
//      所以这里一律**自己解成 UTF-16** 再 `NewString`。
//
// ★ 线程约定：回调发生在**调用 nativeGenerate 的那个线程**上（同步回调）。
//   JNIEnv 只在该线程该次调用内有效 —— 这是 JNI 的规定，
//   所以这里**绝不**自己起线程。调用方本来也必须放在后台线程里调，否则会卡 UI。
//
// ==================================================================
// ★★ 轮次66（提速）：本文件这一轮改了三件事，起因是**真机实测读数**：
//
//   基线（小米 2210132C / 骁龙 8 Gen 2 / Qwen2.5-1.5B-Instruct Q4_K_M / 旧的 armv8-a 纯 CPU 库）：
//     `本地生成完成：prompt 170 tok / 出字 8 tok / 首字 13416 ms / 共 69993 ms / 0.1 tok/s`
//   ⇒ prefill 79 ms/token、**decode 7000 ms/token**，而 decode 的耗时**与上下文长度无关**
//     （prompt 170~190 tok、已生成 0~16 tok 之间实测都是 ~7000 ms/token）
//     ⇒ 不是"每步重算提示词"，是**单步解码本身**慢。
//
//   ① **KV cache 复用**：以前每次 nativeGenerate 开头都 `llama_memory_clear`，
//      整个提示词（人格 + 事实 + 风格 + 历史）每句都从头预填充一遍。
//      现在按"与上一次的 token 序列的最长公共前缀"只算新增的那一段。
//   ② **GPU 卸载层数真的传进去**：以前 `mp.n_gpu_layers = 0` 写死，
//      把 Vulkan 后端编出来也永远不会被用（这是本轮最容易漏的一步）。
//      现在由 Java 侧按「⚡ GPU 加速」开关传进来，**默认开**；
//      本函数再自己探一次设备：**一个 GPU 设备都没有就如实报错**（开着 GPU 时）
//      或按纯 CPU 加载（关着时）—— 见 gpu_device_count。
//   ③ **测量点**：prefill 与 decode 分段计时、每 8 个 token 报一次滚动速度、
//      以及本进程自开始以来的 minor/major page fault 计数（读 /proc/self/stat）。
//      ★ 这几个数字是**唯一**能区分"算得慢"与"页被换出去了"的证据 ——
//      轮次66 的第一条纪律就是"没有读数的优化等于没做"。
// ==================================================================
// ★★★ 轮次69（开 GPU / 关 GPU —— 两态，并把"换不了模型"的真 bug 修掉）：
//
//   用户原话：**「把加速做成开GPU」**（上一轮他要的是「强制使用gpu加速」）。
//   轮次68 做成了**三态**（off / auto / force，默认 force）⇒ 用户嫌复杂，收回两态：
//     `off` 纯 CPU；
//     `on`  **真的用 GPU**：请求把全部层放上去；初始化 / 计算管线 / 卸载任何一步失败
//           ⇒ **不回落 CPU**，把原因（中文结论 + 原始错误串）写进 lastError
//           交给 Java 侧当面告诉用户，并给一个一键改成「关」的退路。
//   ★ "auto（失败静默回落 CPU）"这一档**整个去掉了**：用户两次都想消灭的正是
//     "以为在用 GPU、其实在慢慢用 CPU"（真机实测回落之后 0.15 tok/s ≈ 6.5 秒/字）。
//
//   ★★★ 本轮**修掉的真 bug（用户报的"模型无法更换其他的 gguf 模型"）**：
//     轮次68 有一个进程级粘性标记 `g_gpu_runtime_failed`：GPU 失败过一次之后，
//     force 分支里 `if (g_gpu_runtime_failed) { set_err(...); return false; }`
//     ⇒ **之后每一次加载（换模型、重新导入、甚至同一个模型再来一次）都直接失败**，
//     只有"用户手动改了 GPU 方式"才会清掉它。默认又是 force ⇒
//     用户一旦遇到那次（Adreno 740 上必然遇到的）计算管线失败，
//     **换任何一个 gguf 都只会得到同一句"以前已经失败过"** ——
//     界面上看起来就是"模型换不了"。
//   ⇒ 本轮把那个粘性标记**整个删掉**（该标识符在本文件里出现 0 次，有断言钉着）：
//     每次加载都是一次**新的尝试**，失败就如实报、成功就用 —— 退路自然就通了。
//     "不重复白等一次"这个收益不值得用它换掉"换不了模型"。
//
//   ★★★ 本文件里"开 GPU 不许回落"依然是**结构性**的，不是靠自觉：
//     ① 失败的唯一出口是 `gpu_required_failed_locked()`，它的函数体里
//        **没有** `do_load_locked`（没有"按纯 CPU 重载"这个动作）；
//     ② `nativeGenerate` 在**任何**回落代码之前 `return nullptr`
//        （而且本文件里**根本不存在**按 CPU 重载并重跑的那段代码）。
//     断言把这两条形状逐字钉住（见 build/verify_local_llm_speed.py 第十三节）。
//
//   ★★ 顺带保住的死锁纪律：`nativeLoad` 全程持有 g_lock，
//     所以失败的清理走 `*_locked` 版本（std::mutex 不可重入）。
// ==================================================================
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

// ★ 轮次67：读 `debug.dsp.adreno_subgroup` 这个系统属性（Adreno 回退的 A/B 开关）。
#include <sys/system_properties.h>
// ★ 轮次67：权重常驻（fadvise / mlock）
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>

#include "llama.h"
#include "ggml-backend.h"

#define DSP_LOG_TAG "DSPLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  DSP_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, DSP_LOG_TAG, __VA_ARGS__)

// ==================================================================
// ★★★ 轮次69：「⚡ GPU 加速」的两态 —— 与 Java 侧的约定
//
//   ★★ 这两个整数码**必须与 src/com/deepseek/.../GpuMode.java 里的
//      CODE_OFF / CODE_ON 一致**（断言把两边对起来查）。
//   为什么过 JNI 用整数而不是字符串：这里决定的是**行为**
//   （要不要 GPU、失败怎么办），而一个认不出的字符串**绝不能**变成"开"
//   —— 认不出当 off（静默用 CPU 是可恢复的，静默用 GPU 不是）。
//   ★ 轮次68 的 _AUTO(1) / _FORCE(2) 两个宏**已经删掉**：Java 侧不再可能传 2，
//     而 2 落到这里会被 normalize_gpu_mode 当成认不出 ⇒ off（安全的一侧）。
// ==================================================================
#define DSP_GPU_MODE_OFF   0
#define DSP_GPU_MODE_ON    1

// ==================================================================
// ★★★ 轮次71：「本地推理调优」—— 把"只能在 PC 上做的 A/B"搬进手机的设置页
//
//   背景（用户这几轮的原话一直是同一件事）：
//     「速度慢的离谱 用gpu加速」→「默认使用gpu加速」→「强制使用gpu加速」→「把加速做成开GPU」。
//   轮次66~69 把「⚡ GPU 加速」做成了设置页里看得见的开/关，可是真机上
//   Vulkan 的计算管线仍然被 Adreno 740 拒绝
//   （`vk::Device::createComputePipeline: ErrorUnknown`）——
//   而"到底哪个旋钮能治好它"**只能在用户自己的手机上试**：
//   开发机 `adb devices` 从头到尾是空的，这台 PC 上根本跑不了 arm64 的包。
//   ⇒ 轮次67 留的那个 A/B 开关用法是
//     `setprop debug.dsp.adreno_subgroup 1` + `am force-stop` ——
//     **必须连电脑、必须敲命令**，所以用户一直没能自己得出结论。
//
//   ★★ 本轮把这些旋钮全部做成设置页里可见、可点、会持久化、会写进日志的选项：
//     设置 → 陪伴 → 对话与人格 → 🤖 本地模型 → 🎛 本地推理调优（进阶 · A/B 用）
//   它们的**唯一定义处**是 Java 侧的 `src/com/deepseek/.../LocalTune.java`
//   （键名 / 取值域 / 默认值 / 文案都在那儿），这里只有一份**解析器**。
//   ★★★ 键名与取值在本文件里出现"第二遍"，这是"同一个事实抄两处"的经典坑 ⇒
//     `build/verify_local_llm_speed.py` 第十八节把**每一个键、每一个取值**两边对起来查，
//     改一边就红。
//
//   ★★★ 为什么是**一个字符串**过 JNI、而不是再塞 8 个 int：
//     这几个旋钮只在载入时读一次，而字符串有一个整数没有的好处 ——
//     它会被**原样打进 logcat**（下面那行「本次调优」）。
//     用户在真机上改了一个开关之后，那一行就是他"这次到底用的哪套参数"的现场证据，
//     也是他把结果发回来时我们唯一需要对上的东西。
//
//   ★★ 生效时机分两类（这一条**必须**让用户知道，否则他会以为"改了没效果"）：
//     · **后端级**（adreno / mmvq / fusion / async）：ggml 在**造 Vulkan 设备那一刻**
//       读环境变量（ggml-vulkan.cpp 的设备初始化段），而 Vulkan 设备在**一个进程里只造一次**
//       ⇒ 改完要**完全退出 App 再打开**才保证生效（只切模型没用）。
//     · **载入级**（flash / ubatch / mlock / loadmode）：
//       都是 `llama_context_params` / `llama_model_params` 的字段，**每次载入都重新读**
//       ⇒ 放掉内存里的模型、下一次载入就生效。
//   ★★ 认不出的值一律走默认 —— 解析器只在白名单里认，认不出就什么都不做
//     （= 与轮次70 的行为完全一致）。脏值绝不会把本地对话弄坏。
// ==================================================================

// 调优旋钮的取值（与 LocalTune.java 的常量一一对应）
enum {
    DSP_TUNE_ADRENO_AUTO   = 0,   // 走轮次67 的补丁（对 Qualcomm 关掉 subgroup 归约）
    DSP_TUNE_ADRENO_SUBGRP = 1,   // 强制保留 subgroup 归约（对照组）
    // ★ 轮次73：上游只对 NVIDIA / Intel 选「大工作组」，Qualcomm 永远是 SUBGROUP
    //   ⇒ HYBRID（子组归约 · 不用共享内存）那条 SPIR-V 在 Adreno 上从来没被选到过。
    //   本工程为此加了一个只在本工程里存在的环境变量 GGML_VK_ADRENO_HYBRID
    //   （补丁在 ggml-vulkan.cpp 的 ggml_vk_get_dequantize_mul_mat_vec 里）。
    DSP_TUNE_ADRENO_HYBRID = 2,
};
enum { DSP_TUNE_FLASH_AUTO = 0, DSP_TUNE_FLASH_ON = 1, DSP_TUNE_FLASH_OFF = 2 };
enum {
    DSP_TUNE_LOADMODE_MMAP  = 0,
    DSP_TUNE_LOADMODE_NONE  = 1,
    DSP_TUNE_LOADMODE_MLOCK = 2,
};
enum { DSP_TUNE_MMVQ_AUTO = 0, DSP_TUNE_MMVQ_OFF = 1, DSP_TUNE_MMVQ_FORCE = 2 };
// ★ 轮次73：设备级 fp16（GGML_VK_DISABLE_F16）
enum { DSP_TUNE_F16_AUTO = 0, DSP_TUNE_F16_OFF = 1 };

struct Tune {
    int  adreno   = DSP_TUNE_ADRENO_AUTO;
    int  flash    = DSP_TUNE_FLASH_AUTO;
    int  ubatch   = 512;
    bool mlock    = true;
    int  loadmode = DSP_TUNE_LOADMODE_MMAP;
    int  mmvq     = DSP_TUNE_MMVQ_AUTO;
    bool fusion   = true;
    bool async    = true;
    int  f16      = DSP_TUNE_F16_AUTO;   // ★ 轮次73
};

namespace {

// ------------------------------------------------------------------ 小工具

int clamp_int(int v, int lo, int hi, int dflt) {
    if (v <= 0) return dflt;      // 0 / 负数 = "用默认值"
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

// ------------------------------------------------------------------ 轮次71：调优串解析
//
// 格式 `k=v;k=v;…`（全部小写、顺序固定 —— 格式与取值域的**定义处**是
// src/com/deepseek/.../LocalTune.java，这里只是解析器）。

void str_trim(std::string & s) {
    size_t a = 0, b = s.size();
    while (a < b && (s[a] == ' ' || s[a] == '\t' || s[a] == '\r' || s[a] == '\n')) ++a;
    while (b > a && (s[b - 1] == ' ' || s[b - 1] == '\t' || s[b - 1] == '\r' || s[b - 1] == '\n')) --b;
    s = s.substr(a, b - a);
}

void str_lower(std::string & s) {
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] >= 'A' && s[i] <= 'Z') s[i] = (char) (s[i] + 32);
    }
}

// 取 `k=v` 串里 key 的值；没有这一项 / 串是空的 ⇒ 空串。
std::string tune_get(const std::string & s, const char * key) {
    const std::string want(key);
    size_t p = 0;
    while (p < s.size()) {
        size_t e = s.find(';', p);
        if (e == std::string::npos) e = s.size();
        const size_t eq = s.find('=', p);
        if (eq != std::string::npos && eq > p && eq < e) {
            std::string kk = s.substr(p, eq - p);
            std::string vv = s.substr(eq + 1, e - eq - 1);
            str_trim(kk);
            str_trim(vv);
            str_lower(kk);
            str_lower(vv);
            if (kk == want) return vv;
        }
        p = e + 1;
    }
    return std::string();
}

// ★★ 白名单解析：**认不出就什么都不做**（= 与轮次70 的行为完全一致）。
//   注意这里**没有**"猜"：一个脏值不会变成"开了某个实验开关"。
Tune parse_tune(const std::string & raw) {
    Tune t;
    std::string v;

    v = tune_get(raw, "adreno");
    if (v == "subgroup")     t.adreno = DSP_TUNE_ADRENO_SUBGRP;
    else if (v == "hybrid")  t.adreno = DSP_TUNE_ADRENO_HYBRID;

    v = tune_get(raw, "f16");
    if (v == "off") t.f16 = DSP_TUNE_F16_OFF;

    v = tune_get(raw, "flash");
    if (v == "on")       t.flash = DSP_TUNE_FLASH_ON;
    else if (v == "off") t.flash = DSP_TUNE_FLASH_OFF;

    v = tune_get(raw, "ubatch");
    if (v == "128" || v == "256" || v == "512" || v == "1024") t.ubatch = atoi(v.c_str());

    v = tune_get(raw, "mlock");
    if (v == "off") t.mlock = false;

    v = tune_get(raw, "loadmode");
    if (v == "none")       t.loadmode = DSP_TUNE_LOADMODE_NONE;
    else if (v == "mlock") t.loadmode = DSP_TUNE_LOADMODE_MLOCK;

    v = tune_get(raw, "mmvq");
    if (v == "off")        t.mmvq = DSP_TUNE_MMVQ_OFF;
    else if (v == "force") t.mmvq = DSP_TUNE_MMVQ_FORCE;

    v = tune_get(raw, "fusion");
    if (v == "off") t.fusion = false;

    v = tune_get(raw, "async");
    if (v == "off") t.async = false;

    return t;
}

// 与 LocalTune.changedList 同一套格式：只列**与默认值不同**的项，`k=v, k=v`。
std::string tune_changed_list(const Tune & t) {
    std::string out;
    auto add = [&out](const char * k, const char * v) {
        if (!out.empty()) out += ", ";
        out += k;
        out += "=";
        out += v;
    };
    if (t.adreno == DSP_TUNE_ADRENO_SUBGRP) add("adreno", "subgroup");
    else if (t.adreno == DSP_TUNE_ADRENO_HYBRID) add("adreno", "hybrid");
    if (t.flash == DSP_TUNE_FLASH_ON)       add("flash", "on");
    else if (t.flash == DSP_TUNE_FLASH_OFF) add("flash", "off");
    if (t.ubatch != 512)                    add("ubatch", std::to_string(t.ubatch).c_str());
    if (!t.mlock)                           add("mlock", "off");
    if (t.loadmode == DSP_TUNE_LOADMODE_NONE)       add("loadmode", "none");
    else if (t.loadmode == DSP_TUNE_LOADMODE_MLOCK) add("loadmode", "mlock");
    if (t.mmvq == DSP_TUNE_MMVQ_OFF)        add("mmvq", "off");
    else if (t.mmvq == DSP_TUNE_MMVQ_FORCE) add("mmvq", "force");
    if (!t.fusion)                          add("fusion", "off");
    if (!t.async)                           add("async", "off");
    if (t.f16 == DSP_TUNE_F16_OFF)          add("f16", "off");
    return out;
}

const char * flash_name(int f) {
    if (f == DSP_TUNE_FLASH_ON)  return "on（强制开）";
    if (f == DSP_TUNE_FLASH_OFF) return "off（强制关）";
    return "auto（交给 llama.cpp）";
}

const char * loadmode_name(int m) {
    if (m == DSP_TUNE_LOADMODE_NONE)  return "none（不用 mmap）";
    if (m == DSP_TUNE_LOADMODE_MLOCK) return "mmap+mlock";
    return "mmap";
}

// ------------------------------------------------------------------ 轮次71：最近的后端错误尾巴
//
// ★★★ 为什么需要它（这是"自检报告能不能自己说明白"的关键）：
//   `compute pipeline creation failed for <着色器名>` 这句是 **ggml 自己**打的
//   （GGML_LOG_ERROR，见 ggml-vulkan.cpp），它**不进** g_err / lastError()。
//   而"是哪个着色器被 Adreno 拒绝"恰恰是下一轮最要紧的一条线索。
//   用户手机上没连电脑、看不到 logcat ⇒ 这里把最近几条 WARN/ERROR 存下来，
//   自检报告里一起交出去，用户**一键复制**就能把它发回来。
#define DSP_LOG_TAIL_N 8
std::string g_log_tail[DSP_LOG_TAIL_N];
int         g_log_tail_i = 0;      // 下一个要写的位置
int         g_log_tail_n = 0;      // 已经存了几条（最多 DSP_LOG_TAIL_N）
// ★★ 单独的锁：ggml 的日志可能从**后端线程**打出来，而主线程同时在读这份尾巴
//   （`nativeLastStats`）⇒ 没有锁的话 `std::string` 的赋值可能被撕成两半，
//   表现是报告里出现半句乱码、极端情况下崩在日志回调里（那是最糟的位置）。
//   ★ 它是一把**叶子锁**：持它的时候绝不回头去打别的锁（`log_tail_text` 里不写日志）
//   ⇒ 与 g_lock 之间不存在环路。
std::mutex  g_log_tail_lock;

void log_tail_push(const char * text) {
    if (text == nullptr) return;
    std::string s(text);
    // 一行一行地存（ggml 的日志里可能有换行）
    size_t p = 0;
    std::lock_guard<std::mutex> guard(g_log_tail_lock);
    while (p <= s.size()) {
        size_t e = s.find('\n', p);
        const bool last = (e == std::string::npos);
        std::string line = last ? s.substr(p) : s.substr(p, e - p);
        p = last ? s.size() + 1 : e + 1;
        str_trim(line);
        if (line.empty()) continue;
        g_log_tail[g_log_tail_i] = line;
        g_log_tail_i = (g_log_tail_i + 1) % DSP_LOG_TAIL_N;
        if (g_log_tail_n < DSP_LOG_TAIL_N) ++g_log_tail_n;
        if (last) break;
    }
}

// 从旧到新拼起来（自检报告里那一行）。
std::string log_tail_text() {
    std::lock_guard<std::mutex> guard(g_log_tail_lock);
    if (g_log_tail_n <= 0) return "（没有后端警告/错误）";
    std::string out;
    const int start = (g_log_tail_i - g_log_tail_n + DSP_LOG_TAIL_N) % DSP_LOG_TAIL_N;
    for (int i = 0; i < g_log_tail_n; ++i) {
        const std::string & line = g_log_tail[(start + i) % DSP_LOG_TAIL_N];
        if (line.empty()) continue;
        if (!out.empty()) out += " ｜ ";
        out += line;
    }
    return out.empty() ? std::string("（没有后端警告/错误）") : out;
}


//   为什么必须做：Android 会把 App 的 stdout/stderr 丢进 /dev/null，
//   而 ggml 的 Vulkan 后端是靠 GGML_LOG_* 报"找到了几个设备"的
//   （`ggml_vulkan: Found 1 Vulkan devices`）—— 不接这根线，
//   "GPU 到底用没用上"在真机上就**没有任何可观测证据**（只能靠猜，这条以前吃过亏）。
void dsp_log_callback(enum ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    // ★★ 轮次66 实测教训：把 ggml 的 DEBUG 也接进 logcat 会把缓冲区**冲爆** ——
    //   光 `llama_graph_n_input_tensors` 一行一张量，一次加载就几千行，
    //   结果**真正要看的那几行（后端设备 / offloaded N layers）被冲掉了**，
    //   我们在真机上第一次跑就是这么丢掉证据的。这里直接丢掉 DEBUG：
    //   INFO 才是我们要的（`ggml_vulkan: Found 1 Vulkan devices` 就是 INFO）。
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    // ★★ 轮次71：WARN / ERROR 级别的后端日志留一份尾巴（自检报告要把它一起交出去）——
    //   最关键的那句 `compute pipeline creation failed for <着色器名>` 就是 ERROR。
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) log_tail_push(text);
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR)      prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN)  prio = ANDROID_LOG_WARN;
    __android_log_write(prio, DSP_LOG_TAG, text);
}

// /proc/self/stat 里的 minor/major page fault 计数（第 10 / 12 个字段）。
// ★ 用它是为了区分两种"慢"：算得慢（fault 不涨）vs 页被换出去/被回收、
//   每一步都要从存储重新读回来（major fault 猛涨）。
//
// ★★★ 轮次67 修掉的解析 bug（**轮次66 就是它让这一路证据全灭的**）：
//   老代码从**最后一个 ')'** 之后开始"循环吃数字"，但 `)` 之后的**第一个字段是
//   `state` —— 一个字母**（R/S/D/Z…），不是数字 ⇒ 循环第一步就 `break`，
//   `got` 永远是 0 ⇒ 函数恒返回 false ⇒ minflt/majflt 恒为 **-1**。
//   症状：真机上那一行打印成 `页错误 minflt +-1 / majflt +-1`，
//   于是"到底是不是换页导致的慢"这一路**证据从来没拿到**（轮次66 记录了、没修）。
//   ⇒ 修法：**先把 state 这一个字符吃掉**，再开始吃数字。字段表（man proc_pid_stat）：
//     pid (comm) state ppid pgrp session tty_nr tpgid flags minflt cminflt majflt ...
//   吃掉 state 之后，数字下标：0=ppid 1=pgrp 2=session 3=tty_nr 4=tpgid 5=flags
//                            **6=minflt 7=cminflt 8=majflt**
bool read_self_faults(long & minflt, long & majflt) {
    minflt = majflt = -1;
    FILE * f = fopen("/proc/self/stat", "r");
    if (f == nullptr) return false;
    char buf[1024];
    const size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    if (n == 0) return false;
    buf[n] = '\0';
    // comm（第 2 字段）里可能有空格甚至 ')' ⇒ 从**最后一个** ')' 之后开始数字段
    char * p = strrchr(buf, ')');
    if (p == nullptr) return false;
    ++p;
    // ★ 第一步：吃掉 state（**一个字母**）—— 老代码漏掉的就是这一步
    while (*p == ' ' || *p == '\t') ++p;
    if (*p == '\0') return false;
    ++p;
    // 之后才是纯数字段：ppid pgrp session tty_nr tpgid flags minflt cminflt majflt ...
    long v[10];
    int got = 0;
    char * q = p;
    while (got < 10) {
        while (*q == ' ' || *q == '\t') ++q;
        if (*q == '\0') break;
        char * end = nullptr;
        const long val = strtol(q, &end, 10);
        if (end == q) break;                // 不是数字 ⇒ 停（别把后面字段读串）
        v[got++] = val;
        q = end;
    }
    if (got < 9) return false;
    minflt = v[6];
    majflt = v[8];
    return true;
}

// ★★★ 轮次67 新增：本进程的内存账（**这一轮定性的关键证据**）。
//   为什么必须加：轮次66 留下的核心疑点是"1 GB 权重根本没常驻内存"，
//   而那个推断当时**只有间接证据**（1.06 GB / 6.5 s ≈ 163 MB/s，像闪存读速）。
//   直接证据应该是：`/proc/self/status` 里的 **RssFile**（mmap 的模型文件
//   现在真正常驻多少）与 **VmSwap**（被换进 zram 多少）。
//   ★ 判据形状：模型 1.06 GB，若 `RssFile` 只有几十 MB ⇒ 权重**不在内存里**，
//     每吐一个 token 都要从闪存重读 ⇒ 这就把"慢"定性成"内存放不下"而不是"引擎算得慢"。
struct SelfMem {
    long rss_kb      = -1;   // VmRSS    进程常驻总量
    long rss_file_kb = -1;   // RssFile  **文件映射（模型）常驻的那部分** ← 本轮主角
    long rss_anon_kb = -1;   // RssAnon  匿名内存（KV cache / 堆）
    long swap_kb     = -1;   // VmSwap   被换出去的
};
bool read_self_mem(SelfMem & m) {
    FILE * f = fopen("/proc/self/status", "r");
    if (f == nullptr) return false;
    char line[256];
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, "VmRSS:", 6) == 0)           m.rss_kb      = strtol(line + 6, nullptr, 10);
        else if (strncmp(line, "RssFile:", 8) == 0)    m.rss_file_kb = strtol(line + 8, nullptr, 10);
        else if (strncmp(line, "RssAnon:", 8) == 0)    m.rss_anon_kb = strtol(line + 8, nullptr, 10);
        else if (strncmp(line, "VmSwap:", 7) == 0)     m.swap_kb     = strtol(line + 7, nullptr, 10);
    }
    fclose(f);
    return m.rss_kb >= 0;
}

// /proc/meminfo 里的 MemAvailable / SwapFree（**整机**还有多少余量）。
// ★ 与 SelfMem 配套：进程自己 RSS 很大但整机 MemAvailable 很小 ⇒ 系统正在回收它。
void read_sys_mem(long & avail_kb, long & swap_free_kb, long & swap_total_kb) {
    avail_kb = swap_free_kb = swap_total_kb = -1;
    FILE * f = fopen("/proc/meminfo", "r");
    if (f == nullptr) return;
    char line[256];
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, "MemAvailable:", 13) == 0)    avail_kb     = strtol(line + 13, nullptr, 10);
        else if (strncmp(line, "SwapFree:", 9) == 0)    swap_free_kb = strtol(line + 9, nullptr, 10);
        else if (strncmp(line, "SwapTotal:", 10) == 0)  swap_total_kb = strtol(line + 10, nullptr, 10);
    }
    fclose(f);
}

// 一行日志把上面两样打全（省得真机现场拼多条日志）。
void log_memory_line(const char * where, const SelfMem & m) {
    long avail = -1, swap_free = -1, swap_total = -1;
    read_sys_mem(avail, swap_free, swap_total);
    LOGI("内存读数[%s]：本进程 RSS %.0f MB（其中 模型文件 RssFile %.0f MB / 匿名 RssAnon %.0f MB / 已换出 VmSwap %.0f MB）"
         " · 整机 MemAvailable %.0f MB · zram Swap 已用 %.0f/%.0f MB",
         where, (double) m.rss_kb / 1024.0, (double) m.rss_file_kb / 1024.0,
         (double) m.rss_anon_kb / 1024.0, (double) m.swap_kb / 1024.0,
         (double) avail / 1024.0,
         (swap_total > 0 && swap_free >= 0) ? (double) (swap_total - swap_free) / 1024.0 : -1.0,
         (double) swap_total / 1024.0);
}

// ------------------------------------------------------------------ 轮次71：最近一次的完整读数
//
// ★★ 前置声明：`gpu_mode_name` 与 `g_err` 的定义在下面（会话状态那一段），而报告要用它们。
const char * gpu_mode_name(int mode);
extern std::string g_err;
//
// ★★★ 这是「🧪 本地推理自检」的数据来源。
//
//   为什么必须把它从 logcat 里**搬到界面上**：真机是**用户自己的日常机**，
//   他多半不会连电脑、更不会去挂 logcat；而"这台机器的 GPU 到底行不行 / 哪个参数有用"
//   这件事**只有他的手机能回答**（开发机 `adb devices` 从头到尾是空的）。
//   ⇒ 把这一次跑的全部读数攒起来，由 `nativeLastStats()` 交回 Java 侧，
//     界面上一屏显示 + **一键复制** ⇒ 他能直接发回来。
//
//   ★ 数字一律**照实记**，读不到就是 -1（打印成 -1 而不是 0 —— 0 会被读成"真的没有"）。
struct LastRun {
    bool        attempted = false;
    bool        ok        = false;
    std::string stage;                      // 走到哪一步失败的："载入" / "生成"
    std::string tune_raw;                   // Java 侧传进来的原串（原样回显）
    Tune        tune;
    int         gpu_mode    = DSP_GPU_MODE_OFF;
    int         want_layers = 0;
    int         gpu_layers  = 0;
    int         n_gpu_dev   = 0;
    std::string dev_summary;
    std::string model_desc;
    double      model_mb = -1.0;
    long        load_ms  = -1;
    int         n_ctx = 0, threads = 0, n_batch = 0, n_ubatch = 0;
    int         n_prompt = 0, lcp = 0, n_new = 0, produced = 0;
    double      prefill_ms = -1.0, decode_ms = -1.0, ttft_ms = -1.0, total_ms = -1.0;
    SelfMem     mem_before, mem_after;
    bool        has_mem_before = false, has_mem_after = false;
    long        minf_pre = -1, majf_pre = -1, minf_dec = -1, majf_dec = -1;
    std::string err;
};
LastRun g_last;

void last_reset(const std::string & tune_raw) {
    g_last = LastRun();
    g_last.tune_raw = tune_raw;
    g_last.tune     = parse_tune(tune_raw);
    g_last.attempted = true;
}

void last_fail(const char * stage) {
    g_last.ok    = false;
    g_last.stage = stage;
    g_last.err   = g_err;
}

// ------------------------------------------------------------------ 轮次72：哨兵值不许当数字印
//
// ★★★ 真机实测抓到的报告 bug（25091RP04C / Adreno 830，GPU 档失败的那 6 组）：
//   报告里印出来的是
//     `prefill=8 tok / -1 ms (0.00 tok/s)`
//     `decode=0 tok / -1 ms (0.00 tok/s, -1 ms/token)`
//     `首字（TTFT）=-1 ms` / `总耗时=-1 ms` / `载入耗时=-1 ms`
//   `-1` 在这里是**"这一步根本没跑到"的哨兵值**，不是一个读数。
//   印成数字的后果是**读报告的人会从这一行读出一个不存在的读数**
//   （"-1 ms 的耗时"或"0 ms"），而这一屏正是要发给别人看的那一份。
//   ⇒ 没测到就写"未测到"，并且**连 tok/s 也不印**（`0.00 tok/s` 会被读成"跑了但极慢"，
//     而事实是**根本没跑**）。
//   ★ 判据是**结构性的**：整份报告里不许出现 `-1 ms` 这个子串（反向项钉着）。
std::string ms_text(double v) {
    if (v < 0.0) return "未测到（这一步没跑到）";
    char b[48];
    snprintf(b, sizeof(b), "%.0f ms", v);
    return std::string(b);
}

// tok/s 只在**真的测到耗时**时才印（见上面那条：0.00 tok/s 会被误读成"极慢"）。
std::string tok_s_text(int n, double ms) {
    if (ms <= 0.0) return std::string();
    char b[48];
    snprintf(b, sizeof(b), " (%.2f tok/s)", (double) n / (ms / 1000.0));
    return std::string(b);
}

// 页错误也一样：`-1` = 没采到 ⇒ 说"没采到"，不许印成 `+-1`。
std::string faults_text(long a, long b) {
    if (a < 0 || b < 0) return "（没采到）";
    char buf[64];
    snprintf(buf, sizeof(buf), "minflt +%ld / majflt +%ld", a, b);
    return std::string(buf);
}

// 一屏报告（★ 这是用户要复制回来的东西 ⇒ 每一行都得自解释、带单位）。
std::string build_last_report() {
    LastRun & r = g_last;
    char buf[1024];
    std::string s;
    if (!r.attempted) {
        // ★★ 这里**绝不能**放 emoji：这份文本要过 `NewStringUTF`，而它吃的是
        //   "修改版 UTF-8" —— 补充平面字符（emoji）在那边必须写成 CESU-8 的代理对，
        //   直接写标准 4 字节 UTF-8 会变成乱码（文件头 ★★③ 记的就是这个坑）。
        return "结果=还没跑过（先去「本地模型」面板里点一次「本地推理自检」）\n";
    }
    s += std::string("结果=") + (r.ok ? "成功" : "失败") + "\n";
    if (!r.ok) s += std::string("失败的步骤=") + (r.stage.empty() ? "?" : r.stage.c_str()) + "\n";

    // —— 用了什么
    s += std::string("GPU 档位=") + gpu_mode_name(r.gpu_mode) + "\n";
    s += "是否有 Vulkan GPU 设备=";
    s += (r.n_gpu_dev > 0 ? "有" : "没有");
    snprintf(buf, sizeof(buf), "（认出 %d 个）\n", r.n_gpu_dev);
    s += buf;
    s += std::string("后端设备清单=") + (r.dev_summary.empty() ? "（没有设备报上来）" : r.dev_summary.c_str()) + "\n";
    snprintf(buf, sizeof(buf), "请求卸载层=%d；最终交给 llama.cpp 的层=%d\n",
             r.want_layers, r.gpu_layers);
    s += buf;
    s += std::string("模型=") + (r.model_desc.empty() ? "（还没载入）" : r.model_desc.c_str());
    if (r.model_mb > 0) {
        snprintf(buf, sizeof(buf), " / %.1f MB", r.model_mb);
        s += buf;
    }
    s += "\n";
    snprintf(buf, sizeof(buf), "上下文=%d / 线程=%d / n_batch=%d / n_ubatch=%d\n",
             r.n_ctx, r.threads, r.n_batch, r.n_ubatch);
    s += buf;
    s += std::string("flash attention=") + flash_name(r.tune.flash)
       + " / 载入方式=" + loadmode_name(r.tune.loadmode)
       + (r.tune.mlock ? " / 权重锁内存=尝试" : " / 权重锁内存=关") + "\n";
    s += "调优（与本轮默认不同的项）=";
    {
        const std::string ch = tune_changed_list(r.tune);
        s += ch.empty() ? "（全部为默认值）" : ch;
    }
    s += "\n调优串=" + r.tune_raw + "\n";
    // ★ 后端级那四个旋钮是**造 Vulkan 设备时**读的（一个进程只造一次）⇒ 报告里必须提醒，
    //   否则"改了没效果"会被误读成"这个参数没用"。
    s += "载入耗时=" + (r.load_ms >= 0 ? ms_text((double) r.load_ms)
                                      : std::string("未测到（还没载入）")) + "\n";

    // —— 跑出来的数
    snprintf(buf, sizeof(buf), "prompt=%d tok（KV 复用命中 %d / 新算 %d）\n", r.n_prompt, r.lcp, r.n_new);
    s += buf;
    snprintf(buf, sizeof(buf), "prefill=%d tok / ", r.n_new);
    s += buf;
    s += ms_text(r.prefill_ms) + tok_s_text(r.n_new, r.prefill_ms) + "\n";
    snprintf(buf, sizeof(buf), "decode=%d tok / ", r.produced);
    s += buf;
    s += ms_text(r.decode_ms) + tok_s_text(r.produced, r.decode_ms);
    // ms/token 只在**真的生成了 token** 时才有意义（0 个 token 除不出每字耗时）。
    if (r.produced > 0 && r.decode_ms > 0.0) {
        snprintf(buf, sizeof(buf), ", %.0f ms/token", r.decode_ms / (double) r.produced);
        s += buf;
    }
    s += "\n";
    s += "首字（TTFT）=" + ms_text(r.ttft_ms) + "\n总耗时=" + ms_text(r.total_ms) + "\n";

    // —— 内存账（★ 判"权重在不在内存里"就靠这三样）
    if (r.has_mem_before) {
        long avail = -1, swap_free = -1, swap_total = -1;
        read_sys_mem(avail, swap_free, swap_total);
        snprintf(buf, sizeof(buf),
                 "内存[生成前]=RSS %.0f MB / RssFile %.0f MB / RssAnon %.0f MB / VmSwap %.0f MB"
                 " · MemAvailable %.0f MB · zram Swap 已用 %.0f/%.0f MB\n",
                 (double) r.mem_before.rss_kb / 1024.0, (double) r.mem_before.rss_file_kb / 1024.0,
                 (double) r.mem_before.rss_anon_kb / 1024.0, (double) r.mem_before.swap_kb / 1024.0,
                 (double) avail / 1024.0,
                 (swap_total > 0 && swap_free >= 0) ? (double) (swap_total - swap_free) / 1024.0 : -1.0,
                 (double) swap_total / 1024.0);
        s += buf;
    }
    if (r.has_mem_after) {
        snprintf(buf, sizeof(buf), "内存[生成后]=RSS %.0f MB / RssFile %.0f MB / RssAnon %.0f MB / VmSwap %.0f MB\n",
                 (double) r.mem_after.rss_kb / 1024.0, (double) r.mem_after.rss_file_kb / 1024.0,
                 (double) r.mem_after.rss_anon_kb / 1024.0, (double) r.mem_after.swap_kb / 1024.0);
        s += buf;
    }
    s += "页错误=prefill 段 " + faults_text(r.minf_pre, r.majf_pre)
       + " · decode 段 " + faults_text(r.minf_dec, r.majf_dec) + "\n";

    // —— 失败时的原始错误串（★ 含被驱动拒绝的那个**着色器名**）
    s += "最近的后端警告/错误=" + log_tail_text() + "\n";
    s += std::string("原始错误=") + (r.err.empty() ? "（无）" : r.err.c_str()) + "\n";

    // —— ★★ 机器可读的数值行（Java 侧只解析这几行做判定，不去猜中文）
    s += "\n--- 下面这几行是给程序读的，别删 ---\n";
    snprintf(buf, sizeof(buf), "数值.has_vulkan_gpu=%d\n", r.n_gpu_dev > 0 ? 1 : 0);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.offloaded_layers=%d\n", r.gpu_layers);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.prefill_tok_s=%.2f\n",
             r.prefill_ms > 0.0 ? (double) r.n_new / (r.prefill_ms / 1000.0) : 0.0);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.decode_tok_s=%.3f\n",
             r.decode_ms > 0.0 ? (double) r.produced / (r.decode_ms / 1000.0) : 0.0);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.decode_ms_per_token=%.0f\n",
             r.produced > 0 ? r.decode_ms / (double) r.produced : -1.0);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.ttft_ms=%.0f\n", r.ttft_ms);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.rss_file_mb=%.0f\n",
             r.has_mem_before ? (double) r.mem_before.rss_file_kb / 1024.0 : -1.0);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.vmswap_mb=%.0f\n",
             r.has_mem_before ? (double) r.mem_before.swap_kb / 1024.0 : -1.0);
    s += buf;
    {
        long avail = -1, sf = -1, st = -1;
        read_sys_mem(avail, sf, st);
        snprintf(buf, sizeof(buf), "数值.mem_available_mb=%.0f\n", (double) avail / 1024.0);
        s += buf;
    }
    snprintf(buf, sizeof(buf), "数值.model_mb=%.0f\n", r.model_mb);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.gpu_mode=%d\n", r.gpu_mode);
    s += buf;
    snprintf(buf, sizeof(buf), "数值.ok=%d\n", r.ok ? 1 : 0);
    s += buf;
    return s;
}

// ------------------------------------------------------------------ 权重常驻（轮次67）


// ★★★ 轮次67：把模型文件**主动读热**（posix_fadvise WILLNEED）+ **尝试锁进内存**（mlock）。
//
//   动机（轮次66 留下的核心疑点，本轮要正面回答的那个）：
//     decode 稳定 **6.5 s/token 且与上下文长度无关**，单 token 只跑出 ~0.4 GFLOP/s；
//     1.06 GB / 6.5 s ≈ **163 MB/s** —— 这个数量级**极像闪存/换页读速**，不像 LPDDR5X。
//     若成立 ⇒ 权重根本没常驻内存，**每吐一个字都要把 1 GB 权重重新捞一遍**，
//     那"算得慢"这个方向就找错了地方，唯一有效的是"别让内核把权重丢出去"。
//
//   ★ 为什么"我们自己 mmap 一份再 mlock"能锁住 llama.cpp 那份：
//     mlock 锁的是**页缓存里的物理页**（按 inode 共享），llama.cpp 自己 mmap 同一个文件
//     拿到的是**同一批页** ⇒ 我们锁住 = 它也锁住了，而且**不会多占一份内存**
//     （不是复制，是同一批页的第二个映射）。
//   ★ 失败一定是安全的：`mlock` 对整段是"要么全成、要么全不成，绝不部分锁"，
//     而 Android 给普通 App 的 `RLIMIT_MEMLOCK` 很小（常见 64 KB）
//     ⇒ 1 GB 必然一次性失败，失败就照常跑，不会有半锁状态。**成功/失败都打日志**。
//   ★ 诊断开关 `debug.dsp.mlock=0` 可关掉（默认**尝试**）；
//     真机上一旦发现 mlock 成功但进程被 LMK 杀，就用它关掉。
static void * g_pin_addr = nullptr;
static size_t g_pin_len  = 0;

static void release_pinned_model() {
    if (g_pin_addr != nullptr) {
        munlock(g_pin_addr, g_pin_len);
        munmap(g_pin_addr, g_pin_len);
        g_pin_addr = nullptr;
        g_pin_len  = 0;
    }
}

static void try_pin_model_file(const char * path, bool want_mlock) {
    release_pinned_model();
    if (path == nullptr || path[0] == '\0') return;

    // 诊断开关：debug.dsp.mlock=0 关掉（默认尝试）
    // ★★ 轮次71：现在**界面上的旋钮**（LocalTune 的 mlock=on/off）是主开关，
    //   系统属性退化成 PC 上的旁路 —— 两个都说"关"才关（任一个说关就关，宁可不动内存）。
    char prop[64];
    prop[0] = '\0';
    const bool prop_off = (__system_property_get("debug.dsp.mlock", prop) > 0 && prop[0] == '0');
    const bool enabled = want_mlock && !prop_off;
    if (!enabled) {
        LOGI("★★ 权重常驻：被关掉了（界面旋钮 mlock=%s / 系统属性 debug.dsp.mlock=%s）",
             want_mlock ? "尝试" : "关", prop_off ? "0" : "（没设）");
        return;
    }

    struct rlimit rl;
    rl.rlim_cur = rl.rlim_max = 0;
    if (getrlimit(RLIMIT_MEMLOCK, &rl) == 0) {
        LOGI("★★ 权重常驻：本进程 RLIMIT_MEMLOCK 软=%.1f MB 硬=%.1f MB",
             (double) rl.rlim_cur / 1048576.0, (double) rl.rlim_max / 1048576.0);
    }

    const int fd = open(path, O_RDONLY);
    if (fd < 0) { LOGE("★★ 权重常驻：打不开模型文件（errno=%d）", errno); return; }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        LOGE("★★ 权重常驻：fstat 失败（errno=%d）", errno);
        close(fd);
        return;
    }
    const size_t len = (size_t) st.st_size;

    // ① 主动预读：告诉内核"这段我马上要用"，让它先读进页缓存（便宜、无风险）。
    //    ★ 与 mlock 的区别：预读只是"提前读"，内存紧张时照样会被丢掉。
    const int adv = posix_fadvise(fd, 0, 0, POSIX_FADV_WILLNEED);
    LOGI("★★ 权重常驻：posix_fadvise(WILLNEED) %s（%.0f MB）", adv == 0 ? "已提交" : "失败",
         (double) len / 1048576.0);

    // ② 真正把页钉住
    void * addr = mmap(nullptr, len, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (addr == MAP_FAILED) {
        LOGE("★★ 权重常驻：mmap 失败（errno=%d）—— 不锁，照常跑", errno);
        return;
    }
    if (mlock(addr, len) != 0) {
        // ★ 这是**预期中最常见**的结果（Android 的锁内存配额很小）
        LOGE("★★ 权重常驻：mlock %.0f MB **失败**（errno=%d）⇒ 权重无法钉在内存里，"
             "内存紧张时仍会被回收/换出（这条就是 decode 慢的候选原因之一）",
             (double) len / 1048576.0, errno);
        munmap(addr, len);
        return;
    }
    g_pin_addr = addr;
    g_pin_len  = len;
    LOGI("★★★ 权重常驻：mlock **成功**，%.0f MB 已钉在内存里（本进程内不再被换出）",
         (double) len / 1048576.0);
}

// ------------------------------------------------------------------ 后端 / 设备探测（轮次66）

// 这次加载时报出来的后端设备清单（给"GPU 到底有没有被用上"留下原始证据）。
std::string g_dev_summary;

// 数一遍**真正可用**的 GPU 设备。
// ★★ 这是"自动回退 CPU"的唯一判据：一个都不剩 ⇒ 把 n_gpu_layers 压回 0。
//   为什么不信 Java 侧传进来的数：设备有没有 GPU、驱动能不能起来，
//   只有 ggml 自己在 llama_backend_init() 之后才知道。
//
// ★★★ 轮次66 真机实测踩到的坑（**必须留着这段注释**）：
//   第一版这里写的是 `type == GGML_BACKEND_DEVICE_TYPE_GPU`，
//   结果在小米 13（Adreno 740）上判成"一个 GPU 设备都没有"，自动回落了 CPU ——
//   日志是：
//     `★★ 后端设备：Vulkan0(Adreno (TM) 740, 显存 11446/11446 MB); CPU(CPU, ...)
//       ⇒ **一个 GPU 设备都没有，自动回退 CPU**`
//   设备明明在清单里，却被数成 0 个。原因在 ggml-backend.h 的枚举：
//     CPU=0, **GPU=1（独立显存）**, **IGPU=2（集显，用主机内存）**, ACCEL=3, META=4
//   而**手机上的 GPU 全是集显**（Adreno / Mali 与 CPU 共享内存）
//   ⇒ 只认 `GPU` 就等于"在 Android 上永远认不出 GPU"，
//     而且是**静默**的：不会崩、不会报错，只是白编了 20 MB 的 Vulkan 库、
//     速度还是原来那样。这正是"编了 Vulkan 也白编"的第二层陷阱
//     （第一层是 n_gpu_layers 写死 0，第一层已经在 nativeLoad 里修了）。
//   ACCEL 不算：那是 BLAS / AMX 这类"配合 CPU 用"的加速器，不是拿来放层的。
bool device_is_gpu(ggml_backend_dev_t d) {
    const enum ggml_backend_dev_type t = ggml_backend_dev_type(d);
    return t == GGML_BACKEND_DEVICE_TYPE_GPU || t == GGML_BACKEND_DEVICE_TYPE_IGPU;
}

int gpu_device_count() {
    int n_gpu = 0;
    g_dev_summary.clear();
    const size_t cnt = ggml_backend_dev_count();
    char line[256];
    for (size_t i = 0; i < cnt; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (d == nullptr) continue;
        const char * nm = ggml_backend_dev_name(d);
        const char * de = ggml_backend_dev_description(d);
        const bool is_gpu = device_is_gpu(d);
        size_t free_b = 0, total_b = 0;
        ggml_backend_dev_memory(d, &free_b, &total_b);
        if (is_gpu) ++n_gpu;
        snprintf(line, sizeof(line), "%s%s(%s, 显存 %.0f/%.0f MB)",
                 g_dev_summary.empty() ? "" : "; ",
                 nm == nullptr ? "?" : nm,
                 de == nullptr ? "?" : de,
                 (double) free_b / 1048576.0, (double) total_b / 1048576.0);
        g_dev_summary += line;
    }
    if (g_dev_summary.empty()) g_dev_summary = "（没有任何后端设备报上来）";
    return n_gpu;
}

// ------------------------------------------------------------------ UTF-8 → UTF-16

// 把 [s, s+n) 里**完整的** UTF-8 序列解成 UTF-16 追加到 out。
// 返回"已完整消费的字节数" —— 不足一个完整序列的尾巴**不消费**，
// 由调用方留到下一个 token 再拼（这就是"token 切开汉字"的解法）。
// 非法字节吐 U+FFFD 并前进，绝不吐出半个字符。
size_t utf8_consume(const char * s, size_t n, std::vector<jchar> & out) {
    static const uint32_t MINV[5] = { 0u, 0u, 0x80u, 0x800u, 0x10000u };
    size_t i = 0;
    while (i < n) {
        const unsigned char c = (unsigned char) s[i];
        int need;
        uint32_t cp;
        if (c < 0x80u) {                    // ASCII：一字节一个字
            out.push_back((jchar) c);
            i += 1;
            continue;
        } else if ((c & 0xE0u) == 0xC0u) { need = 2; cp = (uint32_t) (c & 0x1Fu); }
        else if ((c & 0xF0u) == 0xE0u)    { need = 3; cp = (uint32_t) (c & 0x0Fu); }
        else if ((c & 0xF8u) == 0xF0u)    { need = 4; cp = (uint32_t) (c & 0x07u); }
        else {                              // 非法首字节（含 0x80~0xBF 的孤立续字节）
            out.push_back((jchar) 0xFFFD);
            i += 1;
            continue;
        }

        // ★ 关键的一行：尾巴不完整就**停下**，把字节留给下一个 token
        if (i + (size_t) need > n) break;

        bool bad = false;
        for (int k = 1; k < need; ++k) {
            const unsigned char cc = (unsigned char) s[i + (size_t) k];
            if ((cc & 0xC0u) != 0x80u) { bad = true; break; }
            cp = (cp << 6) | (uint32_t) (cc & 0x3Fu);
        }
        if (bad) {                          // 续字节坏了：吐替换符后只前进 1 字节重新对齐
            out.push_back((jchar) 0xFFFD);
            i += 1;
            continue;
        }
        // 过短编码（overlong）/ 代理区 / 超出 U+10FFFF 一律算坏
        if (cp < MINV[need] || cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) {
            out.push_back((jchar) 0xFFFD);
            i += (size_t) need;
            continue;
        }
        if (cp < 0x10000u) {
            out.push_back((jchar) cp);
        } else {                            // 补充平面 ⇒ UTF-16 代理对
            cp -= 0x10000u;
            out.push_back((jchar) (0xD800u + (cp >> 10)));
            out.push_back((jchar) (0xDC00u + (cp & 0x3FFu)));
        }
        i += (size_t) need;
    }
    return i;
}

// ------------------------------------------------------------------ UTF-16 → UTF-8

// GetStringChars 拿到的是 UTF-16；llama 的分词器要的是**标准 UTF-8**。
// （GetStringUTFChars 给的是"修改版 UTF-8"，emoji 会变成 6 字节代理对 ⇒ 不能直接用。）
std::string utf16_to_utf8(const jchar * s, jsize n) {
    std::string out;
    out.reserve((size_t) n * 3u + 1u);
    for (jsize i = 0; i < n; ++i) {
        uint32_t c = (uint32_t) s[i];
        if (c >= 0xD800u && c <= 0xDBFFu && i + 1 < n
                && s[i + 1] >= 0xDC00u && s[i + 1] <= 0xDFFFu) {
            c = 0x10000u + ((c - 0xD800u) << 10) + ((uint32_t) s[i + 1] - 0xDC00u);
            ++i;
        } else if (c >= 0xD800u && c <= 0xDFFFu) {
            c = 0xFFFDu;                    // 落单的代理：替换，绝不产出非法 UTF-8
        }
        if (c < 0x80u) {
            out.push_back((char) c);
        } else if (c < 0x800u) {
            out.push_back((char) (0xC0u | (c >> 6)));
            out.push_back((char) (0x80u | (c & 0x3Fu)));
        } else if (c < 0x10000u) {
            out.push_back((char) (0xE0u | (c >> 12)));
            out.push_back((char) (0x80u | ((c >> 6) & 0x3Fu)));
            out.push_back((char) (0x80u | (c & 0x3Fu)));
        } else {
            out.push_back((char) (0xF0u | (c >> 18)));
            out.push_back((char) (0x80u | ((c >> 12) & 0x3Fu)));
            out.push_back((char) (0x80u | ((c >> 6) & 0x3Fu)));
            out.push_back((char) (0x80u | (c & 0x3Fu)));
        }
    }
    return out;
}

// ------------------------------------------------------------------ 会话状态

std::mutex          g_lock;            // 串行化 load / unload / generate
std::atomic<bool>   g_ready{false};    // 给 isLoaded() 用（不加锁，免得被长生成卡住）
// ★ 卸载请求：unload() 先置位再等锁 ⇒ 正在跑的生成会**很快**看到它并退出，
//   而不是让调用方（往往是 UI 线程）傻等几秒。生成循环每轮都查一次。
std::atomic<bool>   g_cancel{false};
llama_model *       g_model = nullptr;
llama_context *     g_ctx   = nullptr;
const llama_vocab * g_vocab = nullptr;
int                 g_threads = 0;
int                 g_n_ctx   = 0;
int                 g_gpu_layers = 0;  // 这次加载**实际**请求的卸载层数（0 = 纯 CPU）
std::string         g_err;
// ★★★ 轮次69 删掉的**两个全局**：`g_model_path` 与 `g_load_batch`。
//   它们**只**服务于轮次66/68 那条"GPU 炸了 ⇒ 按纯 CPU 重载它并重跑一次"的回落路径
//   （整个函数 `auto_fallback_cpu_retry` 也一起删了）。
//   ★ 留着它们会让人以为"还有一条回落路径要喂参数" ⇒ 一并删掉，
//     "本文件里不存在按 CPU 重载"这条性质连**参数**都不存在（断言钉着）。

// ★★★ 轮次66 真机实测：**GPU 后端会在生成中途抛异常**。
//   原始证据（小米 13 / Adreno 740 / Qwen2.5-1.5B Q4_K_M）：
//     Abort message: 'terminating due to uncaught exception of type vk::UnknownError:
//                      vk::Device::createComputePipeline: ErrorUnknown'
//     backtrace: libdspllm.so(Java_..._nativeGenerate) ← libllama.so(llama_decode)
//                ← libggml-base.so(ggml_backend_sched_graph_compute_async)
//                ← libggml-vulkan.so ×6 帧
//   ⇒ ggml-vulkan 在 `createComputePipeline` 外面**没有** try/catch（同文件的
//     createShaderModule 有一层），异常一路穿过 llama_decode 变成 std::terminate
//     ⇒ **整个 App 进程 SIGABRT**。
//   ★ 我们不许把这个甩给用户（"开了 GPU 就闪退"是最糟的体验）⇒ 在 JNI 这一层兜住：
//     抓住 → 记下"这台机器的 GPU 后端用不了" → 卸载 → 按 0 层重载 → 把**这一次**生成
//     用 CPU 重跑一遍。用户只会觉得"慢"，不会看到闪退。
//   ★ 粘性（sticky）：一旦炸过，本进程内后续加载一律不再试 GPU
//     （否则每一句都要先炸一遍、白等一次）。
//   ★★ 轮次69：**那个"以前炸过就不许再试"的进程级粘性标记被整个删掉了**。
//     轮次66 引入它（本进程内不再重试 GPU，省掉每句都白等一次），轮次68 只让它对 auto 生效 ——
//     而它正是用户报的「模型无法更换其他的 gguf 模型」的根因：
//     默认 force 之下，一次 GPU 失败之后 `do_load_locked` 里那条
//     `if (g_gpu_runtime_failed) { set_err(...); return false; }` 会让**之后每一次加载**
//     （换模型、重新导入都算）直接失败，只有"用户手动改了 GPU 方式"才能清掉 ——
//     界面上就是"换不了模型"。
//   ⇒ 每次加载都是一次**新的尝试**：失败如实报、成功就用。
//     ★ 断言钉着"`g_gpu_runtime_failed` 在本文件里出现 0 次"（反向项把它加回来必须变红）。

// ★★★ 轮次69：**用户请求的** GPU 档位（DSP_GPU_MODE_OFF / _ON）。
//   ★ 只有两档，就是用户设置里那一个开关的取值本身（不再有"请求 X、实际生效 Y"这层错位）。
//   ★ 用 atomic：它在 nativeLoad（持锁）里写、在 nativeGenerate（不持锁）里读。
std::atomic<int>    g_gpu_mode{DSP_GPU_MODE_OFF};

// ★★ 轮次66：KV cache 复用。
//   g_cache_tokens = **现在真的躺在 KV cache 里**的那串 token（seq 0，按位置顺序）。
//   下一次生成拿新提示词的 token 串跟它比最长公共前缀，只把后面那段喂进去。
//   ★ 它必须跟 g_model / g_ctx 同生共死 —— session_free() 里一起清掉，
//     否则换模型之后会拿旧模型的 token 串去跟新模型的 KV cache 对齐（灾难）。
std::vector<llama_token> g_cache_tokens;

// ★ `llama_backend_init()` 只在**进程内第一次加载**时调一次，且**不**配 backend_free()。
//   头文件里 backend_free 的注释是 "currently only used for MPI" ——
//   手机上反复 init/free 只会平白引入"重复初始化"的风险，没有收益。
bool g_backend_inited = false;

// ★★★ 轮次74：**运行时硬件探测** —— 把"有没有 GPU、是哪个 GPU、显存多少"交给 Java 侧，
//   让 {@code GpuPlan} 去算 n_gpu_layers（用户要求：不用手动调参数）。
//   ★ 只枚举**后端设备**（ggml 的公开 API），不碰任何驱动私有接口；
//   ★ 第一次调用会 `llama_backend_init()`（与 do_load_locked 同一个入口，只做一次）。
//   ★★ 调用方必须是**后台线程**：Vulkan 设备枚举在部分机型上要几十到几百毫秒。
std::string device_info_text() {
    if (!g_backend_inited) {
        llama_log_set(dsp_log_callback, nullptr);
        llama_backend_init();
        g_backend_inited = true;
    }
    const size_t cnt = ggml_backend_dev_count();
    int n_gpu = 0;
    std::string name, backend;
    size_t vfree = 0, vtotal = 0;
    for (size_t i = 0; i < cnt; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        if (d == nullptr || !device_is_gpu(d)) continue;
        ++n_gpu;
        if (n_gpu > 1) continue;                 // 只报第一个（手机上通常只有一个）
        const char * nm = ggml_backend_dev_name(d);
        const char * de = ggml_backend_dev_description(d);
        backend = nm == nullptr ? "" : nm;
        // ★ 设备**名字**取 description：ggml 的 name 是 "Vulkan0"，description 才是
        //   "Adreno (TM) 830" —— 已知坏机型的判据（GpuPlan.knownBad）认的是后者。
        name = (de != nullptr && de[0] != '\0') ? de : backend;
        ggml_backend_dev_memory(d, &vfree, &vtotal);
    }
    char buf[512];
    snprintf(buf, sizeof(buf),
             "vulkan=%d;count=%d;name=%s;backend=%s;vram_free=%zu;vram_total=%zu;subgroup=0",
             n_gpu > 0 ? 1 : 0, n_gpu, name.c_str(), backend.c_str(), vfree, vtotal);
    LOGI("★★ 硬件探测：%s", buf);
    return std::string(buf);
}

// 两档的名字与归一（认不出的值一律 OFF —— 见文件上方那两个宏的注释）。
int normalize_gpu_mode(int raw) {
    if (raw == DSP_GPU_MODE_ON) return DSP_GPU_MODE_ON;
    return DSP_GPU_MODE_OFF;
}

const char * gpu_mode_name(int mode) {
    if (mode == DSP_GPU_MODE_ON) return "on（开：真的用 GPU，失败不回落 CPU）";
    return "off（关：纯 CPU）";
}

void set_err(const std::string & msg) {
    g_err = msg;
    LOGE("%s", msg.c_str());
}

void session_free() {
    // ★ 先放 ctx 再放 model：model 是 ctx 的父对象，反过来放就是 use-after-free
    if (g_ctx != nullptr)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model != nullptr) { llama_model_free(g_model); g_model = nullptr; }
    // ★ 轮次67：模型放掉之后，我们钉住的那份映射也必须一起放（否则白占一份地址空间，
    //   而且下一次加载会再 mmap 一次）。顺序无所谓 —— 它和 llama 的映射互不依赖。
    release_pinned_model();
    g_vocab = nullptr;
    g_cache_tokens.clear();               // ★ 轮次66：KV 没了，账本也要跟着清
    g_ready.store(false);
}

// ==================================================================
// ★★★ 轮次69：**开 GPU 失败的唯一出口**（"不回落"这件事的结构性保证）
//
//   ★★★ 这个函数体里**没有** `do_load_locked` ——
//   也就是说任何走到这里的路径都**不可能**"悄悄按纯 CPU 重载然后再跑一遍"。
//   "开 GPU 不许回落 CPU"这条性质就靠这个形状成立，断言钉在它上面。
//
//   ★ 为什么仍然要 session_free()：GPU 失败之后 Vulkan 设备处于"半死"状态，
//     把模型继续留在内存里，下一次 load / generate 撞上去就是另一种崩
//     （轮次66 的教训：清理路径**自己**也必须兜异常，否则等于没有清理）。
//   ★ 这是"收拾现场"，不是"回落" —— 它**不再加载任何东西**。
//   ★★★ 而且它**不记任何"以后不许再试"的状态**（轮次68 的粘性标记已删除）——
//     下一次 load 照样会真的再试一次 GPU：失败就再报一次，成功就用。
//     那正是"卸载 / 删除 / 换模型 / 切回在线"这四条退路能走通的原因。
//   ★★ 调用者**必须已经持有 g_lock**（std::mutex 不可重入）：
//      nativeLoad 全程持锁；nativeGenerate 走 gpu_required_failed_safe() 里那层锁。
// ==================================================================
void gpu_required_failed_locked(const char * what, const char * raw) {
    try {
        session_free();
    } catch (...) {
        // ★ 半死状态下 llama_free 也可能抛 ⇒ 兜住，并把指针主动置空，
        //   免得下一次加载撞上 use-after-free / double-free（那才是真的崩）。
        g_ctx = nullptr;
        g_model = nullptr;
        g_vocab = nullptr;
        g_ready.store(false);
        LOGE("★★ 开 GPU 失败之后清理现场时又抛了异常（已兜住，指针已置空）");
    }
    std::string msg = std::string("开 GPU 失败（已按你的设置不回落 CPU）：") + what;
    msg += "。原始错误：";
    msg += (raw == nullptr || raw[0] == '\0') ? "(没有错误串，见上一条 DSPLlm 日志)" : raw;
    msg += "。要改成「关」（纯 CPU）："
           "设置 → 陪伴 → 对话与人格 → 本地模型 → GPU 加速。"
           "★ 卸载 / 删除 / 换一个模型 / 切回在线服务商这几条也都不受影响。";
    set_err(msg);
}

// nativeGenerate 那一路不持锁 ⇒ 用这个包一层（清理路径本身也不许把异常带出去）。
void gpu_required_failed_safe(const char * what, const char * raw) {
    try {
        std::lock_guard<std::mutex> guard(g_lock);
        gpu_required_failed_locked(what, raw);
    } catch (...) {
        set_err("开 GPU 失败（已按你的设置不回落 CPU），"
                "而且清理现场时又抛了异常（已兜住 —— 没有回落 CPU）。");
    }
}

// ------------------------------------------------------------------ 流式回调

struct Sink {
    JNIEnv *            env = nullptr;
    jobject             cb  = nullptr;   // null = 只要整段文本，不要流式
    jmethodID           mid = nullptr;
    std::vector<char>   pending;         // 没凑齐的 UTF-8 尾巴（最多 3 字节）
    std::vector<jchar>  full;            // 累计的 UTF-16 全文（整段返回值就用它）
    bool                stopped = false; // 回调要求停止
    bool                aborted = false; // 回调抛异常 / 建 jstring 失败
};

// 把一段 token 字节推进回调：攒够完整字符才回调，尾巴留着。
// 返回 false 表示"别再往下生成了"（停止或异常）。
bool sink_push(Sink & st, const char * s, size_t n) {
    if (n == 0) return true;
    st.pending.insert(st.pending.end(), s, s + n);

    std::vector<jchar> u16;
    const size_t used = utf8_consume(st.pending.data(), st.pending.size(), u16);
    if (used > 0) st.pending.erase(st.pending.begin(), st.pending.begin() + (long) used);
    if (u16.empty()) return true;        // 还在攒（这个 token 只给了半个字）

    st.full.insert(st.full.end(), u16.begin(), u16.end());
    if (st.cb == nullptr) return true;   // 没要流式：只累计

    jstring js = st.env->NewString(u16.data(), (jsize) u16.size());
    if (js == nullptr) { st.aborted = true; return false; }
    const jboolean keep = st.env->CallBooleanMethod(st.cb, st.mid, js);
    st.env->DeleteLocalRef(js);
    if (st.env->ExceptionCheck()) {      // 回调里抛了 —— 清掉并停止，绝不把异常带出 JNI
        st.env->ExceptionDescribe();
        st.env->ExceptionClear();
        st.aborted = true;
        return false;
    }
    if (keep != JNI_TRUE) { st.stopped = true; return false; }
    return true;
}

// 生成结束时把最后那点"半个字"补一个替换符吐出去（并计入全文，
// 保证"回调拼起来"与"返回值"永远一致）。
void sink_flush(Sink & st) {
    if (st.pending.empty()) return;
    st.pending.clear();
    st.full.push_back((jchar) 0xFFFD);
    if (st.cb == nullptr) return;
    jstring js = st.env->NewString(&st.full.back(), 1);
    if (js == nullptr) { st.aborted = true; return; }
    st.env->CallBooleanMethod(st.cb, st.mid, js);
    st.env->DeleteLocalRef(js);
    if (st.env->ExceptionCheck()) {
        st.env->ExceptionDescribe();
        st.env->ExceptionClear();
        st.aborted = true;
    }
}

}  // namespace

// ==================================================================
// 以下六个函数名必须与 LocalLlm.java 里的 native 声明**逐字对应**。
// 拼错不会在编译期报错 —— 断言套件把两边逐字比对，正是为了防这个。
// ==================================================================

// ★ 轮次66：加载逻辑抽成 do_load_locked —— 让"GPU 后端炸了 ⇒ 按纯 CPU 重载一次"
//   这条回落路径能复用它（见本文件 nativeGenerate 外面那圈 try/catch）。
//   ★ 调用者必须**已经持有** g_lock。
static bool do_load_locked(const std::string & path, int jthreads, int jctx, int jbatch,
                           int jgpu_layers, int jgpu_mode, const std::string & tune_raw) {
    session_free();                       // 幂等：重复 load 先把旧的放掉
    g_cancel.store(false);                // 新一轮加载 ⇒ 清掉上一轮的卸载请求
    // ★★ 轮次71：这次自检/载入的读数从零开始记（自检报告就是读它）。
    last_reset(tune_raw);
    const Tune tune = g_last.tune;

    // ★★★ 轮次71：**后端级旋钮**（adreno / mmvq / fusion / async）——
    //   这四个是 ggml 在**造 Vulkan 设备那一刻**用 getenv 读走的
    //   （ggml-vulkan.cpp 的设备初始化段：`device->mmvq_mode` / `disable_fusion` /
    //   `GGML_VK_DISABLE_ASYNC`；subgroup 那条在 ggml_vk_load_shaders 里），
    //   而 Vulkan 设备**一个进程里只造一次** ⇒ 改完要完全退出 App 再打开才保证生效。
    //   ★ 这里一律用 setenv + unsetenv 成对写：默认档位就把变量**清掉**，
    //     免得同一个进程里先设了 1、用户又改回默认，变量还留着（那就是"改了没效果"）。
    {
        // ① Adreno 归约：界面旋钮优先；旋钮是默认（auto）时才看 PC 上的系统属性
        //    （轮次67 留下的那条 setprop 通路**保留**，两条路都还能用）。
        char prop[64];
        prop[0] = '\0';
        const bool prop_set = (__system_property_get("debug.dsp.adreno_subgroup", prop) > 0 && prop[0] != '\0');
        if (tune.adreno == DSP_TUNE_ADRENO_SUBGRP) {
            unsetenv("GGML_VK_ADRENO_HYBRID");
            setenv("GGML_VK_ADRENO_SUBGROUP", "1", 1);
            LOGI("★★ 调优：Adreno 归约＝强制 subgroup（对照组的旋钮）⇒ GGML_VK_ADRENO_SUBGROUP=1");
        } else if (tune.adreno == DSP_TUNE_ADRENO_HYBRID) {
            // ★★★ 轮次73：第三档（上游的 HYBRID = 子组归约、不用共享内存）。
            //   两个变量必须一起设：GGML_VK_ADRENO_SUBGROUP=1 让 use_subgroups16 保持为真，
            //   GGML_VK_ADRENO_HYBRID=1 让 dmmv_wg 提到 LARGE ⇒ reduc16 落到 HYBRID 那条 SPIR-V。
            setenv("GGML_VK_ADRENO_SUBGROUP", "1", 1);
            setenv("GGML_VK_ADRENO_HYBRID", "1", 1);
            LOGI("★★ 调优：Adreno 归约＝第三档（子组归约 · 不用共享内存 / HYBRID）"
                 "⇒ GGML_VK_ADRENO_SUBGROUP=1 + GGML_VK_ADRENO_HYBRID=1"
                 "（★ 这条 SPIR-V 在 Adreno 上从来没被选到过 —— 轮次73 的对照）");
        } else if (prop_set) {
            unsetenv("GGML_VK_ADRENO_HYBRID");
            setenv("GGML_VK_ADRENO_SUBGROUP", prop[0] == '1' ? "1" : "0", 1);
            LOGI("★★ 诊断开关生效：debug.dsp.adreno_subgroup=%s ⇒ GGML_VK_ADRENO_SUBGROUP=%s"
                 "（旋钮是默认的「自动」，所以以系统属性为准）",
                 prop, prop[0] == '1' ? "1（强制 subgroup 归约）" : "0（Adreno 回退）");
        } else {
            unsetenv("GGML_VK_ADRENO_SUBGROUP");
            unsetenv("GGML_VK_ADRENO_HYBRID");
            LOGI("★★ 调优：Adreno 归约＝自动（走轮次67 的补丁：对 Qualcomm 关掉 subgroup 归约）");
        }

        // ② Vulkan 量化 mat-vec（MMVQ）—— ★ 最值得试的一档：
        //    上游被 Adreno 拒绝的那个着色器正是 mul_mat_vec 系列，关掉 MMVQ 就直接绕开它
        //    （**这是我们的推测，没在真机上验过**）。
        if (tune.mmvq == DSP_TUNE_MMVQ_OFF) {
            unsetenv("GGML_VK_FORCE_MMVQ");
            setenv("GGML_VK_DISABLE_MMVQ", "1", 1);
            LOGI("★★ 调优：Vulkan MMVQ＝关（绕开 mul_mat_vec 那条路；推测，未在真机验证）");
        } else if (tune.mmvq == DSP_TUNE_MMVQ_FORCE) {
            unsetenv("GGML_VK_DISABLE_MMVQ");
            setenv("GGML_VK_FORCE_MMVQ", "1", 1);
            LOGI("★★ 调优：Vulkan MMVQ＝强制开（对照组）");
        } else {
            unsetenv("GGML_VK_DISABLE_MMVQ");
            unsetenv("GGML_VK_FORCE_MMVQ");
        }

        // ③ 算子融合 / ④ 异步提交
        if (tune.fusion) unsetenv("GGML_VK_DISABLE_FUSION");
        else             setenv("GGML_VK_DISABLE_FUSION", "1", 1);
        if (tune.async) unsetenv("GGML_VK_DISABLE_ASYNC");
        else            setenv("GGML_VK_DISABLE_ASYNC", "1", 1);
        LOGI("★★ 调优：算子融合＝%s / 异步提交＝%s",
             tune.fusion ? "开" : "关", tune.async ? "开" : "关");

        // ⑤ ★ 轮次73：设备级 fp16（GGML_VK_DISABLE_F16）—— 影响很多着色器的编译分支。
        if (tune.f16 == DSP_TUNE_F16_OFF) {
            setenv("GGML_VK_DISABLE_F16", "1", 1);
            LOGI("★★ 调优：Vulkan 半精度（fp16）＝关（GGML_VK_DISABLE_F16=1；全部走 f32）");
        } else {
            unsetenv("GGML_VK_DISABLE_F16");
            LOGI("★★ 调优：Vulkan 半精度（fp16）＝自动（交给 ggml 按设备能力决定）");
        }
        // ★★ 必须让用户知道这一条，否则"改了没效果"会被误读成"这个参数没用"。
        if (tune.adreno != DSP_TUNE_ADRENO_AUTO || tune.mmvq != DSP_TUNE_MMVQ_AUTO
                || !tune.fusion || !tune.async || tune.f16 != DSP_TUNE_F16_AUTO) {
            LOGI("★★★ 注意：Adreno 归约 / MMVQ / 算子融合 / 异步提交 / fp16 这五个是**造 Vulkan 设备时**"
                 "读进去的，而 Vulkan 设备在一个进程里只造一次 ⇒ "
                 "这次载入**不一定**能反映你刚改的值；要**完全退出 App 再打开**才保证生效。");
        }
    }

    // ★★★ 轮次67 的 Adreno matvec 兼容回退 —— **轮次71 把它的 A/B 开关搬进设置页了**。
    //   ggml-vulkan 里的本地补丁默认对 Qualcomm（vendorID 0x5143）关掉 subgroup 归约、
    //   退回 shared-memory 归约 —— 因为 subgroup 归约的 dequant mul_mat_vec 管线在 Adreno 上
    //   会让 `createComputePipeline` 抛 VK_ERROR_UNKNOWN（上游 issue #28635 / PR #29165，
    //   本工程真机症状逐字吻合：offload 成功之后第一次 llama_decode 就抛）。
    //   ★★ 轮次67 当时只能靠 `setprop debug.dsp.adreno_subgroup` + `am force-stop` 做对照，
    //      **用户必须连电脑** ⇒ 他一直没能自己得出"这条补丁到底有没有用"的结论。
    //   ★★★ 轮次71 起：这个开关是设置页里的
    //      「🎛 本地推理调优 → Adreno 的归约方式」（自动 / 强制 subgroup 归约），
    //      而 setprop 那条**老通路保留**（PC 上批量测还能用）——
    //      两条路的优先级与日志都写在上面那段「后端级旋钮」里，这里不再重复设一遍
    //      （★ 同一个环境变量在两处 setenv 就会出现"哪一处生效"说不清的问题）。
    //   ★ 已知边界（与上面那段「后端级旋钮」的提示是同一件事）：ggml 只在**设备初始化**时
    //      读这个环境变量，而 Vulkan 设备在进程内是复用的 ⇒ 改完要让 App
    //      **重新起一个进程**（`am force-stop` 再打开，不会丢数据）才保证生效。
    //      不要读成"改一行属性就当场切换"。

    if (!g_backend_inited) {
        // ★ 必须在 backend_init **之前**装回调：后端注册那一段就已经在打日志了
        //   （`ggml_vulkan: Found N Vulkan devices` 就是那时候出来的）。
        llama_log_set(dsp_log_callback, nullptr);
        llama_backend_init();
        g_backend_inited = true;
    }

    // ---- ★★★ 轮次69：GPU 卸载层数 —— **两态分流**（开 / 关）
    //   ★★★ 这里是本轮的核心：`on` 与 `off` 在**失败时**的行为完全相反 ——
    //     `on`  没有任何可用 GPU 就把原因**直接交回去**（不压回 0 层、不照常可用）；
    //     `off` 才是纯 CPU（而且显式只把 CPU 设备交给 llama.cpp，见下面第三坑）。
    //   ★★ 轮次69 把"以前炸过就不再试"那条粘性判据删掉了（它是"换不了模型"的根因）：
    //     这里**没有任何**"跳过重试"的分支，每次加载都是真试一次。
    const int gpu_mode = normalize_gpu_mode(jgpu_mode);
    const bool on_gpu  = (gpu_mode == DSP_GPU_MODE_ON);

    int want_gpu = (int) jgpu_layers;                // <=0 = 用户关了 GPU（或 Java 侧没给）
    if (!on_gpu) want_gpu = 0;

    const int n_gpu_dev = gpu_device_count();
    // ★★ 轮次71：把这一次的设备清单与层数记进读数（自检报告要用）。
    g_last.gpu_mode    = gpu_mode;
    g_last.want_layers = (int) jgpu_layers;
    g_last.n_gpu_dev   = n_gpu_dev;
    g_last.dev_summary = g_dev_summary;
    int gpu_layers = 0;
    if (on_gpu) {
        // ★★★ 开：**真的用 GPU**。下面这条不满足就把原因**直接交回去** ——
        //   不压回 0 层、不重载、不"照常可用"。这就是"开"的全部代价与好处。
        if (n_gpu_dev <= 0) {
            set_err("开 GPU 失败（已按你的设置不回落 CPU）："
                    "这台机器上一个可用的 GPU（Vulkan）设备都没有"
                    "（后端设备清单见上一条 DSPLlm 日志）。"
                    "要改成「关」（纯 CPU）："
                    "设置 → 陪伴 → 对话与人格 → 本地模型 → GPU 加速。"
                    "★ 卸载 / 删除 / 换一个模型 / 切回在线服务商这几条也都不受影响。");
            last_fail("载入（探 GPU 设备）");
            return false;
        }
        // ★ Java 侧"开"时传的就是 GPU_LAYERS_ALL；这里再兜一次"没给"的情形，
        //   因为"开"的语义是"全放 GPU"，0 层与它自相矛盾。
        gpu_layers = (want_gpu > 0) ? want_gpu : 999;
        LOGI("★★ 后端设备：%s ⇒ 开 GPU：认出 %d 个 GPU 设备，按 %d 层卸载"
             "（失败就不回落 CPU，会把原因报给你）",
             g_dev_summary.c_str(), n_gpu_dev, gpu_layers);
    } else {
        gpu_layers = 0;
        LOGI("★★ 后端设备：%s ⇒ GPU 加速＝关（纯 CPU）", g_dev_summary.c_str());
    }
    LOGI("系统信息：%s", llama_print_system_info() == nullptr ? "?" : llama_print_system_info());

    llama_model_params mp = llama_model_default_params();
    // ★★ 轮次66 改的就是这一行：原来是写死的 `mp.n_gpu_layers = 0;`
    //    ⇒ 就算把 Vulkan 后端编出来，层也永远留在 CPU 上（GPU 编了白编）。
    mp.n_gpu_layers = gpu_layers;

    // ★★★ 轮次66 真机实测揪出来的**第三个**坑（比前两个更隐蔽，而且它害的是 CPU 那条路）：
    //
    //   llama.cpp 在 `llama_model_base::load_tensors()` 里这么决定要不要 mmap
    //   （src/llama-model.cpp:1576）：
    //       if (ml.use_mmap && params.load_mode == LLAMA_LOAD_MODE_AUTO) {
    //           for (dev : devices) if (!props.caps.mmap_support) { ml.use_mmap = false; break; }
    //       }
    //   而 **Vulkan 上报的 mmap_support 是 `!ctx->is_integrated_gpu`**
    //   （ggml/src/ggml-vulkan/ggml-vulkan.cpp:15425）——
    //   手机 GPU 全是集显 ⇒ **false** ⇒ 只要 Vulkan 后端被注册进 devices，
    //   **整个模型就不 mmap 了**（日志里那行 `load_mode = none` 就是它），
    //   1 GB 权重被 read() 进**匿名内存**，在 Android 上直接进 zram 反复换进换出。
    //   ★ 实测代价：换页带宽 ~100 MB/s 量级 ⇒ decode ~7 s/token。**这跟 GPU 一点关系都没有，
    //     是"装了 Vulkan 后端以后 CPU 那条路反而更慢"**（基线那版没有 Vulkan 后端，
    //     走的是 mmap；新包一旦带上 libggml-vulkan.so 就会踩这个）。
    //   ⇒ 显式写 `LLAMA_LOAD_MODE_MMAP`（**不是** AUTO）：AUTO 才会去做那段设备能力降级，
    //     写死 MMAP 就绕开它，权重照旧 mmap 到文件、页是干净的、可以随时丢、
    //     由内核按需读回来，而不是挤在匿名内存里被压进 zram。
    //   ★ 代价与边界：GPU 卸载时权重仍会被拷到显存（那一步走的是 ggml 自己的 buffer，
    //     与这里的 load_mode 无关）；这条只影响"主机侧那份权重怎么放"。
    //   ★★ 轮次71：这一行现在是**调优旋钮「权重载入方式」**的默认档（mmap）。
    //     另外两档是刻意的**对照组**：`none`（不用 mmap ⇒ 就是上面那个坑的复现，
    //     用来证明"慢是内存不是引擎"）、`mlock`（MMAP_MLOCK）。
    if (tune.loadmode == DSP_TUNE_LOADMODE_NONE) {
        mp.load_mode = LLAMA_LOAD_MODE_NONE;
        LOGI("★★ 调优：载入方式＝**不用 mmap**（对照组：权重进匿名内存，8 GB 机上多半被压进 zram）");
    } else if (tune.loadmode == DSP_TUNE_LOADMODE_MLOCK) {
        mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
        LOGI("★★ 调优：载入方式＝mmap + 请内核别换出去（MMAP_MLOCK）");
    } else {
        mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    }

    // ★★★ 轮次66 真机实测揪出来的**第四个**坑（和第三个连在一起，害的同样是 CPU 那条路）：
    //   把 `n_gpu_layers` 设成 0 **并不等于"不用 Vulkan"**。
    //   实测（n_gpu_layers=0 的那两次加载）日志是：
    //       llama_prepare_model_devices: using device Vulkan0 (Adreno (TM) 740)
    //       load_tensors:  Vulkan_Host model buffer size =   877.31 MiB
    //   ⇒ 1 GB 权重落进了 **Vulkan 的 host-mapped buffer**，而不是 CPU 自己的 host buffer，
    //     等于"关掉 GPU"之后 CPU 这条路还在绕 Vulkan 的显存管理器走一圈。
    //   llama.h 给了正解：`llama_model_params.devices`（NULL 结尾的设备表）——
    //   gpu_layers==0 时**显式只列 CPU**，Vulkan 就完全不参与这次加载。
    //   ★ 这条与上面 load_mode 那条是一对：两个都改，才算真的回到"纯 CPU"。
    static ggml_backend_dev_t s_cpu_only[2] = { nullptr, nullptr };
    if (gpu_layers > 0) {
        mp.devices = nullptr;                 // NULL = 用全部设备（含 GPU）
    } else {
        s_cpu_only[0] = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
        s_cpu_only[1] = nullptr;
        mp.devices = (s_cpu_only[0] == nullptr) ? nullptr : s_cpu_only;
        LOGI("★★ 纯 CPU 模式：显式只把 CPU 设备交给 llama.cpp"
             "（不让 Vulkan 当模型设备 —— 否则权重会落进 Vulkan_Host buffer）");
    }

    const long t_load0 = (long) llama_time_us();
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (g_model == nullptr) {
        set_err("模型加载失败（文件损坏 / 量化格式不被支持）：" + path);
        last_fail("载入（llama_model_load_from_file）");
        return false;
    }
    g_vocab = llama_model_get_vocab(g_model);

    // ★★★ 轮次67：模型一加载完就尝试"钉住"它（见文件上方 try_pin_model_file 的注释）。
    //   位置很关键：必须在 llama_model_load_from_file **之后**（文件已经存在且大小已知），
    //   在 llama_init_from_model **之前**（KV cache 还没吃内存，mlock 成功率最高）。
    //   ★★ 轮次71：要不要试由**界面上的旋钮**（权重锁内存）决定 —— 见 try_pin_model_file。
    try_pin_model_file(path.c_str(), tune.mlock);

    const int threads = clamp_int((int) jthreads, 1, 16, 4);
    int ctx_size = clamp_int((int) jctx, 128, 32768, 2048);
    int batch    = clamp_int((int) jbatch, 32, 4096, 512);
    if (batch > ctx_size) batch = ctx_size;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t) ctx_size;
    cp.n_threads       = threads;
    cp.n_threads_batch = threads;
    // ★ cp.n_batch 在下面（n_ubatch 那一段之后）才赋值 —— 因为"用户选了更大的 ubatch"
    //   会把 n_batch 一起抬上去，这里先写一遍就会被那一行覆盖（别在这里写）。
    // ★★★ 轮次71：n_ubatch 现在是**调优旋钮**（128 / 256 / 512 / 1024，默认 512）。
    //   本轮之前这一行写死 `(batch < 512 ? batch : 512)` —— 512 这一档与它**逐字等价**，
    //   所以"用户什么都不动"时行为完全不变（有断言钉着这条等价）。
    //   ★★★ 但光有"夹到 n_batch"是不够的：Java 侧传进来的 n_batch 默认就是 512
    //   （`LocalLlm.DEFAULT_BATCH`），用户选了 **1024** 之后会被静默夹回 512 ——
    //   那一档就成了**点了没反应的假旋钮**（本工程最恨的那类缺陷）。
    //   ⇒ 用户要的 ubatch 比 n_batch 大（且没超过 ctx）时，**把 n_batch 一起抬上去**：
    //     n_batch 是"一次 llama_decode 最多收多少 token"，预填充循环本来就是按
    //     `llama_n_batch()` 切块喂的 ⇒ 抬上去 = 预填充一次喂更多（这正是那一档的意图）。
    //   ★ 只有"ctx 比 ubatch 还小"这种自相矛盾的组合才仍然夹回来。
    int ubatch = tune.ubatch;
    if (ubatch > batch && ubatch <= ctx_size) batch = ubatch;
    if (ubatch > batch) ubatch = batch;
    if (ubatch < 1) ubatch = 1;
    cp.n_batch = (uint32_t) batch;      // ★ 可能在上面被抬过，这里要重写一遍
    cp.n_ubatch = (uint32_t) ubatch;

    // ★★ 轮次71：flash attention 旋钮（auto / 强制开 / 强制关）。
    //   ★ 这一条**有上游依据**：llama_context_params.flash_attn_type，
    //     默认就是 LLAMA_FLASH_ATTN_TYPE_AUTO（= 本轮之前的行为）。
    //   ★ 为什么值得做成开关：flash attention 那几个着色器也在被驱动拒绝的名单里
    //     （真机日志里失败的那个名字要看 `compute pipeline creation failed for <名字>`），
    //     而"关掉它能不能救回来"只有用户的手机能回答。
    if (tune.flash == DSP_TUNE_FLASH_ON) {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
    } else if (tune.flash == DSP_TUNE_FLASH_OFF) {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    } else {
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    }

    g_ctx = llama_init_from_model(g_model, cp);
    if (g_ctx == nullptr) {
        set_err("上下文创建失败（内存不足？试试更小的 ctx）");
        last_fail("载入（llama_init_from_model）");
        session_free();
        return false;
    }

    g_threads = threads;
    g_n_ctx   = (int) llama_n_ctx(g_ctx);
    g_gpu_layers = gpu_layers;
    g_ready.store(true);

    char desc[256];
    desc[0] = '\0';
    llama_model_desc(g_model, desc, sizeof(desc));
    LOGI("本地模型已加载：%s", desc);
    // ★★ 轮次66：这一行就是"n_gpu_layers 真的传了"的**设备侧判据** ——
    //   断言只能证明源码里写了，证明不了真机上 ggml 收到的是几层。
    // ★★ 轮次68 建立 / 轮次69 两态：再把"用户请求的 **档位**"一起打出来 ——
    //   开 / 关 的行为差异全靠它，真机上（以及用户自测时）这一行
    //   就是"我到底选的是哪一档"的唯一现场证据。
    LOGI("参数 %.1fM / 体积 %.1f MB / ctx=%d / threads=%d / batch=%d / 请求卸载层=%d / 请求线程=%d"
         " / GPU 档位=%s / 耗时 %ld ms",
         (double) llama_model_n_params(g_model) / 1e6,
         (double) llama_model_size(g_model) / 1048576.0,
         g_n_ctx, g_threads, batch, g_gpu_layers, threads,
         gpu_mode_name(gpu_mode),
         ((long) llama_time_us() - t_load0) / 1000L);
    // ★★★ 轮次71：**本次调优**（用户在设置页里动过的旋钮）—— 这是"我这次到底用的哪套参数"
    //   在真机上唯一的现场证据（自检报告里也是同一份）。全部默认时它明说"全部为默认值"，
    //   于是"改了没效果"和"根本没改上"能一眼分开。
    {
        const std::string ch = tune_changed_list(tune);
        LOGI("本次调优（与本轮默认不同的项）：%s ｜ 完整串：%s",
             ch.empty() ? "（全部为默认值，= 与上一版完全一样的行为）" : ch.c_str(),
             tune_raw.empty() ? "（空 = 全部默认）" : tune_raw.c_str());
    }
    LOGI("生效参数：flash attention=%s / n_ubatch=%u / 载入方式=%s / 权重锁内存=%s",
         flash_name(tune.flash), (unsigned) cp.n_ubatch, loadmode_name(tune.loadmode),
         tune.mlock ? "尝试" : "关");
    // ★★ 轮次71：把载入这一段也记进读数（自检报告要用）。
    g_last.gpu_layers = gpu_layers;
    g_last.model_desc = desc;
    g_last.model_mb   = (double) llama_model_size(g_model) / 1048576.0;
    g_last.load_ms    = ((long) llama_time_us() - t_load0) / 1000L;
    g_last.n_ctx      = g_n_ctx;
    g_last.threads    = g_threads;
    g_last.n_batch    = batch;
    g_last.n_ubatch   = (int) cp.n_ubatch;
    g_last.ok         = true;
    g_last.stage      = "载入完成";
    g_last.err.clear();
    // ★★ 轮次67：加载完成时就把内存账打出来 —— 这是"权重有没有常驻内存"的**第一现场**
    //   （加载刚做完，如果 RssFile 远小于模型体积，说明是 mmap 了但没读进来）。
    SelfMem mem_loaded;
    if (read_self_mem(mem_loaded)) log_memory_line("模型加载完成", mem_loaded);
    return true;
}

// ==================================================================
// ★★ 轮次66：nativeLoad 的 JNI 入口（薄壳）
//   外面这圈 try/catch 是给"加载阶段就炸"准备的（Vulkan 分配 buffer 失败也会抛）。
//   ★★★ 轮次71：多了第 7 个参数 `jtune`（调优串，见 LocalTune.java）。
//     为什么加参数而不是塞进 SharedPreferences 由原生侧自己读：
//     原生侧**没有** Context，读不到 SharedPreferences；而"当前这套参数"
//     必须与这一次 load **同时**确定（用户可能刚改完就点了自检）。
// ==================================================================
extern "C" JNIEXPORT jboolean JNICALL
Java_com_deepseek_pet_LocalLlm_nativeLoad(JNIEnv * env, jclass, jstring jpath,
                                          jint jthreads, jint jctx, jint jbatch,
                                          jint jgpu_layers, jint jgpu_mode, jstring jtune) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_err.clear();

    if (jpath == nullptr) { set_err("模型路径为空"); return JNI_FALSE; }
    const char * cpath = env->GetStringUTFChars(jpath, nullptr);
    if (cpath == nullptr) { set_err("读不出模型路径"); return JNI_FALSE; }
    const std::string path(cpath);
    env->ReleaseStringUTFChars(jpath, cpath);
    if (path.empty()) { set_err("模型路径为空"); return JNI_FALSE; }

    // ★★★ 轮次71：调优串（认不出的键/值一律走默认 —— 见 parse_tune 的注释）。
    std::string tune_raw;
    if (jtune != nullptr) {
        const char * ctune = env->GetStringUTFChars(jtune, nullptr);
        if (ctune != nullptr) {
            tune_raw.assign(ctune);
            env->ReleaseStringUTFChars(jtune, ctune);
        }
    }

    // ★★★ 轮次69：档位由 Java 侧的 Prefs.localGpuMode + GpuMode.code 决定（两态：off / on）。
    const int gpu_mode = normalize_gpu_mode((int) jgpu_mode);
    // ★★★ 轮次69：这里**没有**"用户改了方式就清掉粘性标记"那段代码了 ——
    //   因为那个粘性标记本身被删掉了（它是"换不了模型"的根因，见文件上方那段注释）。
    //   现在每一次 nativeLoad 都是一次**全新的尝试**：GPU 该试就真的试。
    g_gpu_mode.store(gpu_mode);

    try {
        return do_load_locked(path, (int) jthreads, (int) jctx, (int) jbatch,
                              (int) jgpu_layers, gpu_mode, tune_raw) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & e) {
        LOGE("★★ 模型加载时后端抛异常并已被兜住：%s", e.what());
        // ★★★ 轮次69：**按档位分流** —— "开"不回落，把原因直接返回给 Java 侧。
        //   ★ 两条 catch 都走到 gpu_required_failed_locked()（唯一出口，函数体里没有 do_load_locked）
        //     ⇒ 加载阶段同样不存在"悄悄按纯 CPU 重来一遍"这条路径。
        if (gpu_mode == DSP_GPU_MODE_ON) {
            gpu_required_failed_locked("加载模型时 Vulkan 后端抛出异常", e.what());
            last_fail("载入（Vulkan 后端抛异常）");
            return JNI_FALSE;
        }
        set_err(std::string("纯 CPU 加载失败（GPU 加速＝关）：") + e.what());
        last_fail("载入（纯 CPU）");
        return JNI_FALSE;
    } catch (...) {
        LOGE("★★ 模型加载时后端抛出未知异常并已被兜住");
        if (gpu_mode == DSP_GPU_MODE_ON) {
            gpu_required_failed_locked("加载模型时 Vulkan 后端抛出未知异常", nullptr);
            last_fail("载入（Vulkan 后端抛未知异常）");
            return JNI_FALSE;
        }
        set_err("纯 CPU 加载失败（GPU 加速＝关）：未知异常");
        last_fail("载入（纯 CPU，未知异常）");
        return JNI_FALSE;
    }
}

// ★ 轮次66：真正的生成实现（**自己拿 g_lock**）。
//   外面那层 `Java_..._nativeGenerate` 负责兜住 GPU 后端的异常并回落 CPU。
static jstring generate_body(JNIEnv * env, jstring jprompt,
                             jint jmax_tokens, jfloat jtemp, jobject jsink) {
    std::lock_guard<std::mutex> guard(g_lock);
    g_err.clear();

    if (g_model == nullptr || g_ctx == nullptr || g_vocab == nullptr) {
        set_err("还没加载模型");
        last_fail("生成（还没加载模型）");
        return nullptr;
    }
    if (jprompt == nullptr) { set_err("提示词为空"); last_fail("生成（提示词为空）"); return nullptr; }

    // ---- 提示词：UTF-16 → 标准 UTF-8（不能用 GetStringUTFChars，见文件头 ★★③）
    const jchar * utext = env->GetStringChars(jprompt, nullptr);
    if (utext == nullptr) { set_err("读不出提示词"); return nullptr; }
    const jsize ulen = env->GetStringLength(jprompt);
    const std::string prompt = utf16_to_utf8(utext, ulen);
    env->ReleaseStringChars(jprompt, utext);
    if (prompt.empty()) { set_err("提示词为空"); return nullptr; }

    // ---- 回调
    Sink st;
    st.env = env;
    st.cb  = jsink;
    if (jsink != nullptr) {
        jclass cbCls = env->GetObjectClass(jsink);
        if (cbCls == nullptr) { set_err("回调对象无效"); return nullptr; }
        st.mid = env->GetMethodID(cbCls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cbCls);
        if (st.mid == nullptr) {
            env->ExceptionClear();
            set_err("回调接口不对：需要 boolean onToken(String)");
            return nullptr;
        }
    }

    // ---- 分词
    std::vector<llama_token> toks((size_t) prompt.size() + 8u);
    int32_t n_tok = llama_tokenize(g_vocab, prompt.data(), (int32_t) prompt.size(),
                                   toks.data(), (int32_t) toks.size(), true, true);
    if (n_tok < 0) {                       // 负数 = 需要这么大
        toks.resize((size_t) (-n_tok) + 8u);
        n_tok = llama_tokenize(g_vocab, prompt.data(), (int32_t) prompt.size(),
                               toks.data(), (int32_t) toks.size(), true, true);
    }
    if (n_tok <= 0) { set_err("分词失败或提示词为空"); last_fail("生成（分词）"); return nullptr; }
    toks.resize((size_t) n_tok);

    // ==================================================================
    // ★★ 轮次66：KV cache 复用（"prerefill 只算新增的那一段"）
    //
    // 以前这里是无条件 `llama_memory_clear(...)` ⇒ 每一句话都要把
    // 系统段（人格 + 事实 + 风格）+ 全部历史重新预填充一遍。
    // 现在：跟"上一次真的躺在 KV 里的那串 token"比最长公共前缀（LCP），
    // 只把 LCP 之后的新 token 喂进去。
    //
    // ★ 为什么要留一条"删不掉就整段重来"的路：llama.cpp 的记忆有
    //   recurrent / hybrid 两类（Mamba、RWKV 那种），它们**不允许**从中间截断
    //   ⇒ `llama_memory_seq_rm` 会返回 false。那时必须老老实实整段重算，
    //   否则就是"KV 里留着一半旧状态、模型答非所问"这种最难查的软故障。
    // ==================================================================
    llama_memory_t mem = llama_get_memory(g_ctx);
    size_t lcp = 0;
    while (lcp < toks.size() && lcp < g_cache_tokens.size()
            && toks[lcp] == g_cache_tokens[lcp]) ++lcp;

    // ★ 至少要留 1 个 token 要算：全命中的话最后那个 token 的 logits 没有新算，
    //   采样会拿到上一次残留的 logits（症状：回答跟这次的问题没关系）。
    if (lcp >= toks.size()) lcp = toks.size() - 1;

    bool kv_ok;
    if (lcp == 0) {
        llama_memory_clear(mem, true);
        kv_ok = true;
    } else if (lcp >= g_cache_tokens.size()) {
        kv_ok = true;                       // KV 里正好就是这段前缀，什么都不用删
    } else {
        kv_ok = llama_memory_seq_rm(mem, 0, (llama_pos) lcp, -1);
        if (!kv_ok) {
            llama_memory_clear(mem, true);  // 截不断（recurrent 记忆）⇒ 整段重来
            lcp = 0;
        }
    }
    (void) kv_ok;
    const int n_new = (int) toks.size() - (int) lcp;

    long minf0 = -1, majf0 = -1, minf1 = -1, majf1 = -1, minf2 = -1, majf2 = -1;
    read_self_faults(minf0, majf0);
    // ★★ 轮次67：生成**开始前**的内存账 —— 这是"权重到底在不在内存里"的判据基线。
    //   模型 1.06 GB，如果这里的 RssFile 只有几十 MB，那它一开始就不在内存里。
    SelfMem mem_before;
    read_self_mem(mem_before);
    log_memory_line("生成前", mem_before);
    // ★★ 轮次71：生成这一段也记进读数（自检报告要用同一份数字）。
    g_last.has_mem_before = true;
    g_last.mem_before     = mem_before;
    g_last.stage          = "生成中";
    g_last.err.clear();

    const int n_batch = (int) llama_n_batch(g_ctx);
    const long t0 = (long) llama_time_us();
    long t_first = 0;
    long t_prefill_done = 0;
    // ★★ 轮次71：整数与 KV 账本先记下来（下面失败时也要能在报告里看出"算到哪一步"）。
    g_last.n_prompt = (int) toks.size();
    g_last.lcp      = (int) lcp;
    g_last.n_new    = n_new;
    g_last.n_batch  = n_batch;

    // ---- 预填充（按 n_batch 切块喂，长提示词也不会超批）
    //      ★ 轮次66：只喂 LCP 之后的新 token
    for (int i = (int) lcp; i < (int) toks.size(); i += n_batch) {
        const int k = ((int) toks.size() - i < n_batch) ? ((int) toks.size() - i) : n_batch;
        llama_batch b = llama_batch_get_one(toks.data() + i, k);
        const int32_t rc = llama_decode(g_ctx, b);
        if (rc != 0) {
            set_err("预填充失败（decode rc=" + std::to_string(rc) + "，ctx 可能太小）");
            last_fail("生成（预填充 llama_decode）");
            return nullptr;
        }
    }
    t_prefill_done = (long) llama_time_us();
    read_self_faults(minf1, majf1);

    // ---- 采样链：temp<=0 走贪心（确定性，便于人工复现），否则 top_k/top_p/min_p/temp/dist
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (smpl == nullptr) { set_err("采样器创建失败"); last_fail("生成（建采样链）"); return nullptr; }
    if (jtemp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(jtemp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // ---- 生成循环
    int max_tokens = clamp_int((int) jmax_tokens, 1, 8192, 256);
    int produced = 0;
    char piece[512];
    std::vector<llama_token> gen_tokens;   // ★ 轮次66：记下真的进了 KV 的那些生成 token
    for (int i = 0; i < max_tokens; ++i) {
        if (g_cancel.load()) break;                      // 有人要求卸载 ⇒ 立刻收手
        llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        // ★★ 这里**故意不写** llama_sampler_accept(smpl, id)：
        //    llama_sampler_sample **自己已经 accept 过了**
        //    （llama-sampler.cpp:960，两条返回路径都 accept）。
        //    多 accept 一次对**现在**这条链恰好无害（greedy / top_k/top_p/min_p/temp/dist，
        //    dist 的 accept 在非 backend 事务下直接 return），
        //    但**一旦以后加上 penalties / grammar 就会双记** ——
        //    重复惩罚算两遍、语法状态前进两步，症状是"输出有点怪"，最难查的那一类。
        //    ⇒ verify_local_llm.py 有一条断言专门盯着"别再把它加回来"。
        if (llama_vocab_is_eog(g_vocab, id)) break;      // 遇到结束符

        const int np = llama_token_to_piece(g_vocab, id, piece, (int32_t) sizeof(piece), 0, false);
        if (np > 0) {
            if (t_first == 0) t_first = (long) llama_time_us();
            ++produced;
            if (!sink_push(st, piece, (size_t) np)) break;   // 用户要求停 / 回调抛异常
        }
        gen_tokens.push_back(id);
        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(g_ctx, b) != 0) break;
        // ★ 轮次66 的测量点：每 8 个 token 报一次滚动速度。
        //   "整体 tok/s" 会被首字那段糊住，看不出"越吐越慢"还是"一直匀速"。
        if ((produced & 7) == 0 && produced > 0) {
            const double dms = (double) (llama_time_us() - t_prefill_done) / 1000.0;
            LOGI("解码进度：%d tok / 解码段 %.0f ms（%.1f ms/token）", produced, dms,
                 dms / (double) produced);
        }
    }
    sink_flush(st);
    llama_sampler_free(smpl);
    read_self_faults(minf2, majf2);
    SelfMem mem_after;
    read_self_mem(mem_after);
    log_memory_line("生成后", mem_after);

    // ---- 记下"现在 KV 里躺着哪串 token"，给下一次复用（★ 生成完一起算进去）
    g_cache_tokens = toks;
    g_cache_tokens.insert(g_cache_tokens.end(), gen_tokens.begin(), gen_tokens.end());

    const long elapsed_us = (long) llama_time_us() - t0;
    const double secs = (double) elapsed_us / 1e6;
    const double prefill_ms = (double) (t_prefill_done - t0) / 1000.0;
    const double decode_ms = (double) (t_prefill_done > 0 ? (llama_time_us() - t_prefill_done) : 0) / 1000.0;
    // ★ 首字延迟（TTFT）单独记：桌宠气泡的"反应快不快"全看它，
    //   而 tok/s 高但首字慢，用户体感一样是"卡"。
    const double ttft_ms = t_first > 0 ? (double) (t_first - t0) / 1000.0 : -1.0;
    LOGI("本地生成完成：prompt %d tok / KV 复用命中 %d tok / 新算 %d tok / 出字 %d tok / 首字 %s / 共 %.0f ms / %.1f tok/s%s%s",
         n_tok, (int) lcp, n_new, produced, ms_text(ttft_ms).c_str(), secs * 1000.0,
         secs > 0.0 ? (double) produced / secs : 0.0,
         st.stopped ? " / 被回调打断" : "",
         st.aborted ? " / 回调异常" : "");
    // ★★ 轮次66：prefill / decode 两段分开报 —— 用户的原话是"两段都慢"，
    //   只报一个总 tok/s 就分不清是哪一段，也就没法证明这一轮到底治好了哪个。
    LOGI("分段读数：prefill %d tok / %.0f ms (%.1f tok/s) · decode %d tok / %.0f ms (%.2f tok/s, %.0f ms/token)",
         n_new, prefill_ms, prefill_ms > 0.0 ? (double) n_new / (prefill_ms / 1000.0) : 0.0,
         produced, decode_ms,
         decode_ms > 0.0 ? (double) produced / (decode_ms / 1000.0) : 0.0,
         produced > 0 ? decode_ms / (double) produced : 0.0);
    // ★★★ 轮次67：把页错误读数**按段落分开**报，并且把"模型是否常驻内存"一起报。
    //   为什么必须分段：只有 prefill 段涨 majflt 是"首次触摸"（正常，一次性），
    //   而 **decode 段每出一个 token 都涨 majflt** 才是"权重每次都要从闪存重读"
    //   —— 这两件事的性质完全不同，糊在一起就分不出结论（轮次66 就是这么糊掉的）。
    //   ★ 判据形状（认这个形状，不认绝对数）：decode_ms/produced ≈ 6500 ms 且
    //     decode 段 majflt 增量 ≈ 模型大小/4KB（≈26 万）⇒ 权重每 token 全量重读。
    LOGI("页错误读数：prefill 段 minflt +%ld / majflt +%ld · decode 段 minflt +%ld / majflt +%ld"
         " · 模型文件常驻 RssFile %.0f→%.0f MB（模型本体 %.0f MB）",
         (minf0 >= 0 && minf1 >= 0) ? (minf1 - minf0) : -1L,
         (majf0 >= 0 && majf1 >= 0) ? (majf1 - majf0) : -1L,
         (minf1 >= 0 && minf2 >= 0) ? (minf2 - minf1) : -1L,
         (majf1 >= 0 && majf2 >= 0) ? (majf2 - majf1) : -1L,
         (double) mem_before.rss_file_kb / 1024.0, (double) mem_after.rss_file_kb / 1024.0,
         g_model != nullptr ? (double) llama_model_size(g_model) / 1048576.0 : -1.0);
    LOGI("本次配置：threads %d / n_ctx %d / n_batch %d / 卸载层 %d / GPU 设备 %d",
         g_threads, g_n_ctx, n_batch, g_gpu_layers, gpu_device_count());

    // ★★★ 轮次71：把这一次的全部读数记下（`nativeLastStats()` 就是把它交回 Java 侧，
    //   界面上一屏显示 + 一键复制 ⇒ 用户不连电脑也能把结论发回来）。
    g_last.produced   = produced;
    g_last.prefill_ms = prefill_ms;
    g_last.decode_ms  = decode_ms;
    g_last.ttft_ms    = ttft_ms;
    g_last.total_ms   = secs * 1000.0;
    g_last.has_mem_after = true;
    g_last.mem_after  = mem_after;
    g_last.minf_pre   = (minf0 >= 0 && minf1 >= 0) ? (minf1 - minf0) : -1L;
    g_last.majf_pre   = (majf0 >= 0 && majf1 >= 0) ? (majf1 - majf0) : -1L;
    g_last.minf_dec   = (minf1 >= 0 && minf2 >= 0) ? (minf2 - minf1) : -1L;
    g_last.majf_dec   = (majf1 >= 0 && majf2 >= 0) ? (majf2 - majf1) : -1L;
    g_last.ok         = true;
    g_last.stage      = "生成完成";
    g_last.err.clear();

    // ★ 有 token 产出但一个字都没凑出来（例如模型只吐了控制符），
    //   返回空串而不是 null —— null 在 Java 侧是"真出错"，语义要分清。
    return env->NewString(st.full.data(), (jsize) st.full.size());
}

// ==================================================================
// ★★★ 轮次69：nativeGenerate 的 JNI 入口 —— GPU 后端的"防闪退"兜底（**按档位分流**）
//
// ggml-vulkan 在 `createComputePipeline` 外面**没有** try/catch
// （同文件的 createShaderModule 有），驱动一拒绝就抛 vk::UnknownError，
// 异常穿过 llama_decode 直接变成 std::terminate ⇒ **App 进程 SIGABRT**
// （真机原始证据见文件上方那段注释）。
//
// ★ 轮次66 的口径是"GPU 默认开、失败自动静默回落 CPU、**不许崩**" —— 于是一律回落。
// ★★ 轮次68 的口径是"按用户选的方式分流"，其中 auto 仍然回落。
// ★★★ 轮次69 的口径：**只有两档，"回落 CPU 再重跑一遍"这段代码整个删掉了**
//   · `on`  ⇒ **不回落、不重载** —— 把"失败原因"如实返回给 Java 侧，
//     由它写进 lastError 并当面告诉用户（气泡 + 一键改成「关」的退路）；
//   · `off` ⇒ 本来就在纯 CPU 上跑（走到这里的异常不是 GPU 那条路，如实报错即可）。
//   ★ "不许崩"这条**两档都成立**：catch 兜住 → 清现场 → 返回错误，
//     一次都不会走到 std::terminate。
//   ★★ "开 GPU 不许回落"是**结构性**的：本函数体里**不出现** do_load_locked，
//     而且**本文件里已经没有任何**"按纯 CPU 重载 + 重跑"的函数（轮次68 的
//     `auto_fallback_cpu_retry` 连同 auto 那一档一起删掉了）。
// ==================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_deepseek_pet_LocalLlm_nativeGenerate(JNIEnv * env, jclass, jstring jprompt,
                                              jint jmax_tokens, jfloat jtemp, jobject jsink) {
    try {
        return generate_body(env, jprompt, jmax_tokens, jtemp, jsink);
    } catch (const std::exception & e) {
        LOGE("★★ GPU 后端在生成过程中抛异常，已被兜住（没有闪退）：%s", e.what());
        // ★★★ 开 GPU：**不重载、不重跑** —— 把原因交给 Java 侧（它会念给用户听）。
        if (g_gpu_mode.load() == DSP_GPU_MODE_ON) {
            gpu_required_failed_safe("生成过程中 Vulkan 后端抛出异常", e.what());
            last_fail("生成（Vulkan 后端抛异常）");
            return nullptr;
        }
        set_err(std::string("生成过程中抛出异常（GPU 加速＝关，纯 CPU）：") + e.what());
        last_fail("生成（纯 CPU，抛异常）");
        return nullptr;
    } catch (...) {
        LOGE("★★ GPU 后端在生成过程中抛出未知异常，已被兜住（没有闪退）");
        if (g_gpu_mode.load() == DSP_GPU_MODE_ON) {
            gpu_required_failed_safe("生成过程中 Vulkan 后端抛出未知异常", nullptr);
            last_fail("生成（Vulkan 后端抛未知异常）");
            return nullptr;
        }
        set_err("生成过程中抛出未知异常（GPU 加速＝关，纯 CPU）");
        last_fail("生成（纯 CPU，未知异常）");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_deepseek_pet_LocalLlm_nativeUnload(JNIEnv *, jclass) {
    // ★ 先置"取消"再等锁：正在跑的那次生成下一轮就会退出，
    //   调用方不会为了等一个不懂事的循环而卡住 UI。
    g_cancel.store(true);
    std::lock_guard<std::mutex> guard(g_lock);
    session_free();
    LOGI("本地模型已卸载，内存已交还");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_deepseek_pet_LocalLlm_nativeLoaded(JNIEnv *, jclass) {
    return g_ready.load() ? JNI_TRUE : JNI_FALSE;   // 不加锁：别被长生成卡住
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deepseek_pet_LocalLlm_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_version());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_deepseek_pet_LocalLlm_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(g_err.c_str());
}

// ==================================================================
// ★★★ 轮次71：`nativeLastStats()` —— 「🧪 本地推理自检」那一屏的数据来源。
//
//   为什么需要它（而不是让 Java 去猜）：自检要报的那几个数字
//   （prefill tok/s / decode tok/s / 首字 ms / RssFile / VmSwap / MemAvailable /
//   后端设备名 / 卸载层数 / **失败的着色器名**）**全部只存在于原生侧** ——
//   Java 侧拿不到，logcat 用户也不会去挂。⇒ 这里把它们整理成一段多行文本交回去，
//   界面上一屏显示 + 一键复制，用户直接发回来就行。
//
//   ★ 它**不跑推理**（自检的流程是 Java 侧编排的：
//     载入 → 生成一句固定的话 → 读这份读数）。所以这里只是"把已经记下的东西打出来"。
//   ★ 没跑过时也返回一句人话（不是空串）——空串在界面上就是"点了没反应"。
//   ★★ 报告格式（含 `数值.*` 那几行机器可读的）由 build_last_report() 负责；
//     Java 侧的 LocalSelfTest 只解析那几行，不去正则匹配中文。
// ==================================================================
extern "C" JNIEXPORT jstring JNICALL
Java_com_deepseek_pet_LocalLlm_nativeLastStats(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    const std::string report = build_last_report();
    return env->NewStringUTF(report.c_str());
}

// ★★★ 轮次74：硬件探测（Java 侧的 GpuPlan 用它算 n_gpu_layers）。
//   ★ 它**不载模型、不生成**，只枚举后端设备；第一次调用会初始化后端（几十~几百毫秒）。
//   ★★ 调用方必须是后台线程（Java 侧 LocalHardware 的注释里也写了这一条）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_deepseek_pet_LocalLlm_nativeDeviceInfo(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> guard(g_lock);
    const std::string info = device_info_text();
    return env->NewStringUTF(info.c_str());
}

// ==================================================================
// ★★ 正式版包名那一组：**必须存在**，否则正式版静默失效
//
// `build/make_variant.py` 生成正式版工程时，会把**所有 .java 里的**
// `com.deepseek.pet` 换成 `com.doropet.release`（源码目录也一起搬）。
// 于是正式版里这个类叫 `com.doropet.release.LocalLlm`，
// ART 会去找 `Java_com_doropet_release_LocalLlm_nativeLoad`。
//
// ★ 而**整包只编出一个 libdspllm.so**（`lib/arm64-v8a/` 里的东西开发版/正式版共用），
//   所以这个 .so 必须**同时**回答两套名字 —— 只留开发版那套的话，
//   正式版每次调用都抛 UnsatisfiedLinkError，而 Java 侧把 Throwable 兜住了
//   ⇒ **功能静默不工作**，属于最难查的那种坏法。
//   （这条是轮次62 收尾时才发现的：先编了正式版，才想起包名会被换掉。）
//
// 实现只有一份：这 7 个函数只是把调用转给上面那 7 个。
// ★ 常量 `com.doropet.release` 与 make_variant.py 里的 `REL_PKG` 是**同一件事**，
//   verify_local_llm.py 会把两处对起来查（改了一处没改另一处就红）。
// ★★ 轮次71：`nativeLoad` 多了一个 `jtune` 参数、并且多了一个 `nativeLastStats` ——
//   **两套包名的声明都要跟着改**：只改开发版那套的话，正式版会静默失效
//   （UnsatisfiedLinkError 被 Java 侧的 Throwable 兜住 ⇒ 功能不工作、也不报错）。
// ==================================================================

extern "C" JNIEXPORT jboolean JNICALL
Java_com_doropet_release_LocalLlm_nativeLoad(JNIEnv * env, jclass cls, jstring jpath,
                                             jint jthreads, jint jctx, jint jbatch,
                                             jint jgpu_layers, jint jgpu_mode, jstring jtune) {
    return Java_com_deepseek_pet_LocalLlm_nativeLoad(env, cls, jpath, jthreads, jctx, jbatch,
                                                     jgpu_layers, jgpu_mode, jtune);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_doropet_release_LocalLlm_nativeGenerate(JNIEnv * env, jclass cls, jstring jprompt,
                                                 jint jmax, jfloat jtemp, jobject jsink) {
    return Java_com_deepseek_pet_LocalLlm_nativeGenerate(env, cls, jprompt, jmax, jtemp, jsink);
}

extern "C" JNIEXPORT void JNICALL
Java_com_doropet_release_LocalLlm_nativeUnload(JNIEnv * env, jclass cls) {
    Java_com_deepseek_pet_LocalLlm_nativeUnload(env, cls);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_doropet_release_LocalLlm_nativeLoaded(JNIEnv * env, jclass cls) {
    // ★★★ 轮次67 修掉的**真缺陷**：这里原来漏了 `return`（只有这一处，另外 5 个转发都有）。
    //   编译期只是一条 `-Wreturn-type` 警告，运行时是**返回值不确定** ⇒
    //   正式版（com.doropet.release）里 `LocalLlm.isLoaded()` 可能随机返回 true/false。
    //   症状很难查：正式版有时"以为模型已经加载了"、有时"以为没加载"。
    //   是轮次62 就带进来的老问题（`D:\petbuild_release` 里那份 6 轮前的副本同样缺 return），
    //   本轮构建时才被编译警告暴露出来。
    return Java_com_deepseek_pet_LocalLlm_nativeLoaded(env, cls);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_doropet_release_LocalLlm_nativeVersion(JNIEnv * env, jclass cls) {
    return Java_com_deepseek_pet_LocalLlm_nativeVersion(env, cls);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_doropet_release_LocalLlm_nativeLastError(JNIEnv * env, jclass cls) {
    return Java_com_deepseek_pet_LocalLlm_nativeLastError(env, cls);
}

// ★★★ 轮次71 新增（正式版那一套）：**少了这个，正式版里「🧪 本地推理自检」就是一个
//   点了没反应的按钮** —— JTune/自检报告都靠它。转发体一行都不能省。
extern "C" JNIEXPORT jstring JNICALL
Java_com_doropet_release_LocalLlm_nativeLastStats(JNIEnv * env, jclass cls) {
    return Java_com_deepseek_pet_LocalLlm_nativeLastStats(env, cls);
}

// ★★★ 轮次74 新增（正式版那一套）：硬件探测。**少了这个，正式版的自动档就永远
//   以为"没有 Vulkan 设备"** ⇒ 直接纯 CPU（不报错、只是"自动"永远选 CPU）。
extern "C" JNIEXPORT jstring JNICALL
Java_com_doropet_release_LocalLlm_nativeDeviceInfo(JNIEnv * env, jclass cls) {
    return Java_com_deepseek_pet_LocalLlm_nativeDeviceInfo(env, cls);
}
