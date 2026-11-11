#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DeepSeekPet v5.6 组装器 —— 从「资源基线 APK + 工程源」产出可安装 APK。

用法:
    python3 assemble.py <工程根> <资源基线.apk> <classes.dex> <输出.apk>

压缩规则（严格照搬原版 v97 APK，保证体积/运行行为一致）:
    *.arsc        -> stored（必须，且必须是第一个条目）
    *.so          -> stored + 4096 字节对齐（页对齐，安装时装 lib 需要）
    *.onnx / *.txt-> stored
    其余           -> deflate（.webp .png .json .xml .dex .mf 等）

自研 ZIP writer：可精确控制 extra 字段实现页对齐
（zipalign 是 x86-64 ELF，在 arm64 设备上跑不了）。
"""
import zipfile, zlib, struct, os, sys, re, glob

PROJ = sys.argv[1]          # 工程根（含 res/ assets/ model/ lib/）
BASE = sys.argv[2]          # 资源基线 APK（含 resources.arsc + res/* + AndroidManifest.xml）
DEX  = sys.argv[3]
DST  = sys.argv[4]

LIBS   = os.path.join(PROJ, "lib/arm64-v8a")
# ★ 多 ABI：除了 arm64-v8a，还把 lib/<abi>/（如 x86_64 的 Node 运行时）一起打进包，
#   否则模拟器（x86_64）装不上、也没法验证 "手机自己跑宿主"。
ABI_DIRS = [("arm64-v8a", LIBS)] + [
    (d, os.path.join(PROJ, "lib", d))
    for d in sorted(os.listdir(os.path.join(PROJ, "lib")))
    if d != "arm64-v8a" and os.path.isdir(os.path.join(PROJ, "lib", d))
]
MODEL  = os.path.join(PROJ, "model")
ASSETS = os.path.join(PROJ, "assets")

# ---------------------------------------------------------------- 语音模型打包开关
#
# ★ 小文件（tokens.txt 0.3MB + silero_vad.onnx 0.6MB）**恒内置**；
#   大模型 sensevoice.int8.onnx（228MB）**默认不内置**，而是作为独立文件
#   随交付 zip 一起分发（用户要求「做成分开的，一起打入压缩包；
#   需要的人就导入文件，不需要就删除」）。
#
#   分发形态：
#     APK（~340MB 瘦身）: assets/asr/tokens.txt + assets/asr/silero_vad.onnx
#     交付 zip 追加      : sensevoice.int8.onnx（独立条目，不在 APK 里）
#     App 内            : 设置页「从文件导入」→ files/asr/ → 离线识别
#
#   想打完整内置包：设环境变量 DSP_BUNDLE_ASR=1。
BUNDLE_ASR = os.environ.get("DSP_BUNDLE_ASR", "") == "1"

# 小文件恒内置（0.9MB，不占体积也有 VAD/词表兜底）
ASR_ASSETS = [(os.path.join(MODEL, "silero_vad.onnx"),      "assets/asr/silero_vad.onnx"),
              (os.path.join(MODEL, "tokens.txt"),           "assets/asr/tokens.txt")]
# 大模型只在显式要求时才打进 assets/asr/
if BUNDLE_ASR:
    ASR_ASSETS = [(os.path.join(MODEL, "sensevoice.int8.onnx"), "assets/asr/sensevoice.int8.onnx")] \
        + ASR_ASSETS

# 基线中要丢弃的条目（v5.6 已移除旧的 vosk 语音方案；assets 一律由工程源提供）
SKIP_PREFIXES = ("assets/",)

# ★★★ 轮次75：native 库改成 **deflate 压缩**（原来 STORE 不压缩）。
#   依据：Manifest 里 `android:extractNativeLibs="true"` ⇒ 安装时会把 .so 解压到
#   `/data/app/.../lib/`，**压缩的 .so 是标准做法**（页面里的对齐只对
#   "直接从 APK mmap" 有意义，而本工程走的是解压路径）。
#   ★ 收益实测：语音那 6 个 .so 26.97 MB → 10.03 MB，**APK 再省 ~17 MB**
#     （32.2 → ~15.2 MB）。
#   ★ 代价（诚实写在这里）：安装时多一次解压（一次性、几秒），
#     装机后磁盘上会同时有 APK 与解压出来的 lib（占用变大）——换下载体积。
STORE_EXT = (".onnx", ".txt")     # 这两个不压缩（.so 现在走 deflate）
ALIGN     = 4096
DOS_DATE  = ((2026 - 1980) << 9) | (9 << 5) | 28
DOS_TIME  = (11 << 11) | (30 << 5)


def fixname(n):
    """还原被 cp437 误解码的 UTF-8 条目名（中文素材名必需）。"""
    if n.isascii():
        return n, False
    try:
        return n.encode('cp437').decode('utf-8'), True
    except Exception:
        return n, True


class AlignedZip:
    def __init__(self, path):
        self.f = open(path, 'wb')
        self.cd = []
        self.n = 0

    def add(self, name, data, force_store=None):
        real, isu = fixname(name)
        nb = real.encode('utf-8')
        flags = 0x800 if isu else 0
        low = real.lower()
        store = low.endswith(STORE_EXT) if force_store is None else force_store
        method = 0 if store else 8
        crc = zlib.crc32(data) & 0xffffffff
        if store:
            payload, csize, usize = data, len(data), len(data)
        else:
            co = zlib.compressobj(6, zlib.DEFLATED, -15)
            payload = co.compress(data) + co.flush()
            csize, usize = len(payload), len(data)
        off = self.f.tell()
        extra = b''
        if method == 0 and low.endswith(".so"):
            # 让文件数据起始地址落在 4096 边界
            base = off + 30 + len(nb)
            pad = (ALIGN - (base % ALIGN)) % ALIGN
            if pad:
                if pad < 4:            # extra 至少 4 字节，且保持 ALIGN 整数倍
                    pad += ALIGN
                extra = b'\x00' * pad
        self.f.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, 20, flags, method,
                                 DOS_TIME, DOS_DATE, crc, csize, usize,
                                 len(nb), len(extra)))
        self.f.write(nb)
        self.f.write(extra)
        self.f.write(payload)
        self.cd.append((nb, flags, method, crc, csize, usize, off, extra))
        self.n += 1

    def close(self):
        cd_off = self.f.tell()
        for (nb, flags, method, crc, csize, usize, off, extra) in self.cd:
            self.f.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, 20, 20, flags,
                                     method, DOS_TIME, DOS_DATE, crc, csize, usize,
                                     len(nb), len(extra), 0, 0, 0, 0, off))
            self.f.write(nb)
            self.f.write(extra)
        cd_size = self.f.tell() - cd_off
        self.f.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, self.n, self.n,
                                 cd_size, cd_off, 0))
        self.f.close()


def main():
    for label, p in (("资源基线", BASE), ("dex", DEX), ("lib 目录", LIBS), ("model 目录", MODEL)):
        if not os.path.exists(p):
            sys.exit("缺少必需输入 [%s]: %s" % (label, p))

    zin = zipfile.ZipFile(BASE)
    z = AlignedZip(DST)

    # ---- 1) 基线条目（resources.arsc 必须最先且 stored）----
    if "resources.arsc" in zin.namelist():
        z.add("resources.arsc", zin.read("resources.arsc"), force_store=True)
    skipped = 0
    for it in zin.infolist():
        n = it.filename
        if n == "resources.arsc":
            continue
        if n.startswith("assets/"):
            skipped += 1
            continue
        z.add(n, zin.read(n))
    print("  基线条目已写入（跳过 assets/ %d 项，改由工程源提供）" % skipped)

    # ---- 2) 工程资产：动画 webp / memes / 3 个 json ----
    if os.path.isdir(ASSETS):
        cnt = 0
        for dp, dn, fn in os.walk(ASSETS):
            dn.sort()
            for f in sorted(fn):
                p = os.path.join(dp, f)
                rel = "assets/" + os.path.relpath(p, ASSETS).replace(os.sep, "/")
                z.add(rel, open(p, "rb").read())
                cnt += 1
        print("  已写入 assets/ 共 %d 个文件" % cnt)
    else:
        sys.exit("缺少 assets/ 目录: %s" % ASSETS)

    # ---- 3) 离线语音模型（三个）----
    for src, name in ASR_ASSETS:
        if not os.path.exists(src):
            sys.exit("缺少语音模型: %s" % src)
        data = open(src, "rb").read()
        z.add(name, data)
        print("  + %s  %.1f MB" % (name, len(data) / 1048576.0))

    # ---- 4) native 库（stored + 页对齐）----
    #
    # ★★★ 轮次75：本地模型功能下线期间，把**它专用的 6 个 .so** 排除出包
    #   （本轮用户要求"缩小安装包体积"）。开关只有一个：`LocalModelFeature.ENABLED`。
    #   ★ 为什么安全：`LocalLlm` 的 static 块 `catch (Throwable)` ⇒ 库不在也只会
    #     `libOk=false`（调用方按其分话术），而入口已经隐藏，够不到它。
    #   ★ 恢复功能时把开关改 true 重编即可 —— 不需要动这个文件。
    #   ★ 压缩后约省 24 MB（libggml-vulkan 19 MB 是最大的一块）。
    LOCAL_MODEL_LIBS = (
        "libdspllm.so", "libllama.so", "libggml.so",
        "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so",
    )
    # ★ 正式版生成树把包目录改成了 `src/com/doropet/release/` ⇒ **不能写死**
    #   `src/com/deepseek/pet/...`（本轮实测：正式版因此漏掉了这个判断，包没瘦下来）。
    feature_java = None
    _cands = glob.glob(os.path.join(PROJ, "src", "*", "*", "*", "LocalModelFeature.java"))
    if _cands:
        feature_java = _cands[0]
    local_model_on = True
    if feature_java and os.path.exists(feature_java):
        src = open(feature_java, encoding="utf-8").read()
        m = re.search(r"ENABLED\s*=\s*(true|false)\s*;", src)
        if m:
            local_model_on = (m.group(1) == "true")
    sos = []
    _by_abi = dict(ABI_DIRS)
    for _abi, _dir in ABI_DIRS:
        if os.path.isdir(_dir):
            sos.extend([(_abi, x) for x in sorted(os.listdir(_dir)) if x.endswith('.so')])
    dropped = []
    if not local_model_on:
        kept = []
        for _a, so in sos:
            if so in LOCAL_MODEL_LIBS and _a == "arm64-v8a":
                dropped.append(so)
            else:
                kept.append((_a, so))
        sos = kept
    if not sos:
        sys.exit("lib/ 下没有 .so")
    for _a, so in sos:
        data = open(os.path.join(_by_abi[_a], so), "rb").read()
        z.add("lib/" + _a + "/" + so, data)
        print("  + lib/%s/%s  %.1f MB" % (_a, so, len(data) / 1048576.0))
    if dropped:
        saved = sum(os.path.getsize(os.path.join(LIBS, x)) for x in dropped) / 1048576.0
        # ★ 这行只用 ASCII：assemble.py 在 Windows 上按 GBK 打印，
        #   带 "⇒" 会 UnicodeEncodeError（本轮实测第一次就炸在这里）。
        print("  - local model disabled -> skip %d .so (saved %.1f MB): %s"
              % (len(dropped), saved, ", ".join(dropped)))

    # ---- 5) dex ----
    z.add("classes.dex", open(DEX, "rb").read())

    z.close()
    zin.close()
    print("  OK -> %s  (%.1f MB)" % (DST, os.path.getsize(DST) / 1048576.0))


if __name__ == "__main__":
    main()
