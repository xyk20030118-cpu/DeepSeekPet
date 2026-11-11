# dspllm JNI 的构建脚本（轮次62 · 本地模型引擎层）
#
# ★ 完整复现命令（含取源码 / 装 NDK / strip / 进工程）见同目录 **BUILD_INFO.txt** ——
#   照着那份做就行，别在这里现推参数。
#
# 用法：
#   cmake -S jni -B <构建目录> -G Ninja \
#     "-DCMAKE_TOOLCHAIN_FILE=<NDK>/build/cmake/android.toolchain.cmake" \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
#     -DCMAKE_BUILD_TYPE=Release \
#     -DLLAMA_SRC_DIR=<llama.cpp 源码目录> \
#     -DLLAMA_BUILD_DIR=<llama.cpp 构建目录>
#
# ★ 为什么 -DANDROID_STL=c++_shared 而不是 c++_static：
#   本工程一次要装 4 个 llama/ggml 的 .so，NDK 文档明说**多个 shared library 时
#   不支持各自静态链 libc++**（每个库一份运行时，跨库的 C++ 对象会打架）。
#   代价是要多带一个 ~1MB 的 libc++_shared.so，相对 5MB 的推理库可以忽略。
cmake_minimum_required(VERSION 3.14)
project(dspllm CXX)

if(NOT LLAMA_SRC_DIR)
    message(FATAL_ERROR "缺少 -DLLAMA_SRC_DIR=<llama.cpp 源码目录>")
endif()
if(NOT LLAMA_BUILD_DIR)
    message(FATAL_ERROR "缺少 -DLLAMA_BUILD_DIR=<llama.cpp 构建目录>")
endif()

set(LLAMA_LIB "${LLAMA_BUILD_DIR}/bin/libllama.so")
if(NOT EXISTS "${LLAMA_LIB}")
    message(FATAL_ERROR "找不到 ${LLAMA_LIB} —— 先把 llama.cpp 交叉编译出来")
endif()

# ★★ 轮次66 新增两个直接依赖：JNI 要用 ggml 的后端设备 API 来判"到底有没有 GPU 可用"
#   （这是"没有 Vulkan 设备就自动回退 CPU"的唯一判据）。
#   ★ 符号在哪个库是**实测**出来的，别凭感觉写：
#     `ggml_backend_dev_count` / `ggml_backend_dev_get` 在 **libggml.so**；
#     `ggml_backend_dev_type` / `_name` / `_description` / `_memory` 在 **libggml-base.so**。
#     只链 libllama.so 会在链接期报 undefined（--no-undefined 是开着的）。
#   ★ 两个库本来就已经在 lib/arm64-v8a/ 里、也已经被打包（assemble.py 通配收），
#     所以多这两条 DT_NEEDED **不会**新增任何要打进包的文件。
set(GGML_LIB      "${LLAMA_BUILD_DIR}/bin/libggml.so")
set(GGML_BASE_LIB "${LLAMA_BUILD_DIR}/bin/libggml-base.so")
foreach(_l "${GGML_LIB}" "${GGML_BASE_LIB}")
    if(NOT EXISTS "${_l}")
        message(FATAL_ERROR "找不到 ${_l} —— 先把 llama.cpp 交叉编译出来")
    endif()
endforeach()

add_library(dspllm SHARED dspllm_jni.cpp)
target_compile_features(dspllm PRIVATE cxx_std_17)
target_include_directories(dspllm PRIVATE
        "${LLAMA_SRC_DIR}/include"
        "${LLAMA_SRC_DIR}/ggml/include")

# log = __android_log_print（DSPLlm 日志是设备侧唯一的证据来源）
target_link_libraries(dspllm PRIVATE "${LLAMA_LIB}" "${GGML_LIB}" "${GGML_BASE_LIB}" log)

# ★ 只导出 JNI 那几个符号：隐藏其余一切。
#   好处有两个 —— ① 体积更小；② 断言套件可以**精确**比对
#   "Java 的 native 方法" 与 "本库导出的 Java_com_deepseek_pet_LocalLlm_*" 一一对应。
set_target_properties(dspllm PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)

# ★ --no-undefined：漏链一个库要在**构建时**炸，而不是真机上 dlopen 才炸
target_link_options(dspllm PRIVATE "-Wl,--no-undefined")
