#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Windows-native reproduction of build/build_pc.sh for DeepSeekPet.

Why this exists instead of just running build_pc.sh
---------------------------------------------------
The project's own build path is a POSIX shell script that needs `sh`, `unzip`,
`md5sum` and a Linux aapt2. This host has none of those: no `sh`, no `unzip`,
no WSL distro, and the bundled build/toolchain-pc/aapt2 is an ELF binary.
Rather than install a shell emulator, the same six stages are driven from
Python 3, and aapt2 comes from the Android SDK's build-tools (a real Windows
PE executable).

Python is used rather than PowerShell because the project path contains CJK
characters and Windows PowerShell 5.1 decodes BOM-less scripts as ANSI, which
corrupts non-ASCII literals.

Stage parity with build/build_pc.sh
-----------------------------------
  [1/6] aapt2 compile          res/ -> res.zip
  [2/6] aapt2 link             -> resbase.apk + R.java
  [3/6] javac                  src/ + gen/ -> classes
  [4/6] merge deps             sherpa + kotlin-stdlib, delete META-INF
  [5/6] d8                     -> classes.dex
  [6/6] assemble.py + apksigner
then reverse-verify the dex inside the APK matches the compiled one.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile

# The project path contains CJK characters. Windows PowerShell 5.1 converts
# arguments passed to native executables through the ANSI code page, so a CJK
# path arrives at python (and then at aapt2) as mojibake, and aapt2 fails with
# "failed to open directory". The path is therefore never passed on the command
# line: it is read from a UTF-8 sidecar file written by the caller, or taken
# from the default below.
HERE = os.path.dirname(os.path.abspath(__file__))
PATH_FILE = os.path.join(HERE, "project_path.txt")
REPO_ROOT = os.path.dirname(HERE)
DEFAULT_PROJECT = REPO_ROOT  # 默认就是本仓库根（脚本位于 <repo>/tools/）

SDK = (os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
       or r"D:\Android\Sdk")
BT = os.path.join(SDK, "build-tools", "36.0.0")
AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")

JAVAC = "javac"
JAVA = "java"


def log(msg):
    print(msg, flush=True)


def _manifest_version(project_root):
    """从 AndroidManifest.xml 读 versionName（产物文件名要用它）。

    ★ 为什么读明文清单而不是 APK：这一步发生在**编译之前**
      （要先用它决定 APK 的输出文件名），那时还没有 APK 可读。
      明文清单里 versionName 是普通属性，正则足够。

    读不到时返回 "unknown"，**不猜默认值** —— 猜一个 6.0 会让
    "文件名写 6.0、包里是 6.1"这种脱节更难发现。
    """
    path = os.path.join(project_root, "AndroidManifest.xml")
    try:
        with open(path, "r", encoding="utf-8") as f:
            m = re.search(r'android:versionName="([^"]+)"', f.read())
        return m.group(1) if m else "unknown"
    except OSError:
        return "unknown"


def run(cmd, cwd=None, desc=None):
    """Run a command, echoing it, and fail loudly with its real output."""
    if desc:
        log("==> " + desc)
    r = subprocess.run(cmd, cwd=cwd, capture_output=True)
    out = (r.stdout or b"").decode("utf-8", "replace")
    err = (r.stderr or b"").decode("utf-8", "replace")
    if out.strip():
        print(out.rstrip(), flush=True)
    if err.strip():
        print(err.rstrip(), flush=True)
    if r.returncode != 0:
        raise SystemExit("!! command failed (%d): %s" % (r.returncode, " ".join(cmd)))
    return out


def md5(path):
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    # Resolve the project root without trusting the command line (see note at
    # the top). Precedence: UTF-8 sidecar file, then the built-in default.
    if os.path.isfile(PATH_FILE):
        with open(PATH_FILE, encoding="utf-8") as f:
            P = f.read().strip()
    elif len(sys.argv) >= 2:
        P = sys.argv[1]
    else:
        P = DEFAULT_PROJECT
    P = os.path.abspath(P)

    # aapt2/javac/d8 all need a console encoding that can represent the CJK
    # paths; without this they print mojibake and some tools mis-handle it.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")

    if not os.path.isfile(os.path.join(P, "AndroidManifest.xml")):
        raise SystemExit("not a project root: " + P)
    if not os.path.isfile(AAPT2):
        raise SystemExit("aapt2 not found: " + AAPT2)

    T = os.path.join(P, "build", "toolchain")
    JAR = os.path.join(T, "android.jar")
    D8 = os.path.join(T, "d8.jar")
    SIGNER = os.path.join(T, "apksigner.jar")
    # 变体选择：正式版用 release.keystore，口令/别名与开发版不同。
    #
    # 这一步原先写死 debug.keystore —— 于是不管把工程切到哪个变体，
    # 产物都用 debug 签名，正式版包根本出不来（实测签名 DN 恒为
    # CN=Android Debug，dist/ 里也不生成 release 包）。
    # 现在由 DSP_VARIANT=release 决定，并把别名/口令/产物名一起带上。
    # 产物名里的版本号**从 AndroidManifest.xml 读**，不写死。
    #
    # 为什么：以前这里写的是 "DeepSeekPet_v6.0_rebuilt.apk"，
    # 升级到 6.1 时改了清单、忘了改这里 —— 于是 `dist/` 里的文件名
    # 与实际 versionName 不一致，打包和交付都容易拿错包。
    # 现在读清单：升级只需改 AndroidManifest.xml 一处。
    VER = _manifest_version(P)

    if os.environ.get("DSP_VARIANT", "").strip().lower() == "release":
        # ★ 发布仓库不带签名库，也不带口令：正式版口令我以前写死在源码里（那是自己一个人用的工程），
        #   公开之后必须改成环境变量传入。
        KS = os.path.join(P, "release.keystore")
        if not (os.environ.get("DSP_KS_PASS") and os.environ.get("DSP_KEY_ALIAS")):
            raise SystemExit("正式版构建需要环境变量：DSP_KS_PASS / DSP_KEY_PASS / DSP_KEY_ALIAS")
        KS_PASS = "pass:" + os.environ["DSP_KS_PASS"]
        KEY_PASS = "pass:" + os.environ.get("DSP_KEY_PASS", os.environ["DSP_KS_PASS"])
        KEY_ALIAS = os.environ["DSP_KEY_ALIAS"]
        APK_NAME = "DeepSeekPet_v%s_release.apk" % VER
    else:
        KS = os.path.join(P, "debug.keystore")
        KS_PASS = "pass:android"
        KEY_PASS = "pass:android"
        KEY_ALIAS = "androiddebugkey"
        APK_NAME = "DeepSeekPet_v%s_rebuilt.apk" % VER

    for f in (JAR, D8, SIGNER, KS):
        if not os.path.isfile(f):
            raise SystemExit("missing required file: " + f)

    O = os.path.join(P, "build", "out_win")
    W = os.path.join(P, "build", "work_win")
    DIST = os.path.join(P, "dist")
    for d in (O, W):
        shutil.rmtree(d, ignore_errors=True)
    for d in (O, os.path.join(W, "cls"), os.path.join(W, "all"),
              os.path.join(W, "dex"), DIST):
        os.makedirs(d, exist_ok=True)

    log("==> 工程根: " + P)
    log("==> aapt2 : " + AAPT2)
    log(run([AAPT2, "version"]).strip())

    # ---------- [1/6] resources ----------
    res_zip = os.path.join(O, "res.zip")
    run([AAPT2, "compile", "--dir", os.path.join(P, "res"), "-o", res_zip],
        desc="[1/6] aapt2 compile (res/ -> res.zip)")

    # ---------- [2/6] link ----------
    resbase = os.path.join(O, "resbase.apk")
    gen = os.path.join(O, "gen")
    os.makedirs(gen, exist_ok=True)
    run([AAPT2, "link",
         "-o", resbase,
         "-I", JAR,
         "--manifest", os.path.join(P, "AndroidManifest.xml"),
         "-R", res_zip,
         "--auto-add-overlay",
         "--java", gen,
         "--min-sdk-version", "24",
         "--target-sdk-version", "34"],
        desc="[2/6] aapt2 link (-> resbase.apk + R.java)")

    # ---------- [3/6] javac ----------
    # File lists go through an @argfile: 39 sources plus generated R.java can
    # exceed the Windows command-line limit once paths are absolute.
    srcs = []
    for root in (os.path.join(P, "src"), gen):
        for dp, _dn, fn in os.walk(root):
            for name in fn:
                if name.endswith(".java"):
                    srcs.append(os.path.join(dp, name))
    if not srcs:
        raise SystemExit("no .java sources found")
    argfile = os.path.join(W, "javac.args")
    # javac reads @argfiles with its own tokenizer, where a backslash starts an
    # escape sequence - "D:\petbuild\src\..." collapses to "D:petbuildsrc...".
    # Forward slashes are accepted on Windows and survive the argfile intact.
    with open(argfile, "w", encoding="utf-8") as f:
        f.write("\n".join('"%s"' % s.replace("\\", "/") for s in srcs))

    # deps/ 里的第三方 jar —— **单一来源**。
    # 以前 classpath 和下面的 merge 各写一份名单，加依赖时只改一处就会出问题
    # （编译过、但运行期 NoClassDefFoundError）。现在两处都引用这个元组。
    DEPS_JARS = (
        "sherpa-classes.jar",
        "kotlin-stdlib-1.9.0.jar",
        # Shizuku（免 root 提权，用于游戏助手读前台应用帧率）
        "shizuku-api.jar",
        "shizuku-provider.jar",
        "shizuku-aidl.jar",
        "shizuku-shared.jar",
    )
    cp = os.pathsep.join(os.path.join(P, "deps", j) for j in DEPS_JARS)
    # -bootclasspath carries android.jar, exactly as build/build_pc.sh does.
    # Without it every android.* / org.json import fails to resolve.
    out = run([JAVAC, "-nowarn", "-source", "8", "-target", "8",
               "-encoding", "UTF-8",
               "-bootclasspath", JAR,
               "-classpath", cp,
               "-d", os.path.join(W, "cls"),
               "@" + argfile],
              cwd=P, desc="[3/6] javac (%d sources)" % len(srcs))
    log("    javac ok")

    # ---------- [4/6] merge deps ----------
    # META-INF must go: the kotlin stdlib is a multi-release jar and leaving it
    # in produces duplicate class entries that d8 rejects.
    log("==> [4/6] merge deps (%d jars)" % len(DEPS_JARS))
    all_dir = os.path.join(W, "all")
    shutil.copytree(os.path.join(W, "cls"), all_dir, dirs_exist_ok=True)
    for jar in DEPS_JARS:
        with zipfile.ZipFile(os.path.join(P, "deps", jar)) as z:
            z.extractall(all_dir)
    shutil.rmtree(os.path.join(all_dir, "META-INF"), ignore_errors=True)

    classes = []
    for dp, _dn, fn in os.walk(all_dir):
        for name in fn:
            if name.endswith(".class"):
                classes.append(os.path.join(dp, name))
    log("    合计 %d 个 class" % len(classes))

    listfile = os.path.join(W, "list.txt")
    # Same argfile escaping concern as javac: d8's @list parsing also treats a
    # backslash as an escape, so forward slashes are used here too.
    with open(listfile, "w", encoding="utf-8") as f:
        f.write("\n".join(c.replace("\\", "/") for c in classes))

    # ---------- [5/6] d8 ----------
    dexdir = os.path.join(W, "dex")
    run([JAVA, "-Xmx4g", "-cp", D8, "com.android.tools.r8.D8",
         "--min-api", "24", "--lib", JAR,
         "--output", dexdir, "@" + listfile],
        desc="[5/6] d8 转 dex")
    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.isfile(dex):
        raise SystemExit("d8 produced no classes.dex")
    log("    classes.dex = %.2f MB" % (os.path.getsize(dex) / 1048576.0))
    log("    md5 " + md5(dex))

    # ---------- [6/6] assemble + sign ----------
    unsigned = os.path.join(W, "unsigned.apk")
    run([sys.executable, os.path.join(P, "build", "assemble.py"),
         P, resbase, dex, unsigned],
        desc="[6/6] assemble.py")

    apk = os.path.join(DIST, APK_NAME)
    if os.path.exists(apk):
        os.remove(apk)
    run([JAVA, "-jar", SIGNER, "sign",
         "--ks", KS,
         "--ks-pass", KS_PASS,
         "--key-pass", KEY_PASS,
         "--ks-key-alias", KEY_ALIAS,
         "--out", apk, unsigned],
        desc="[6/6] apksigner")

    # ---------- reverse check ----------
    log("==> 反向校验")
    chk = os.path.join(W, "chk")
    os.makedirs(chk, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        z.extract("classes.dex", chk)
    a = md5(os.path.join(chk, "classes.dex"))
    b = md5(dex)
    if a != b:
        raise SystemExit("!! dex 不一致: %s vs %s" % (a, b))
    log("    OK   APK 内 dex 与编译产物一致")

    log("==> 产物核验")
    size = os.path.getsize(apk)
    log("    %s  (%.1f MB)" % (apk, size / 1048576.0))
    ok = run([JAVA, "-jar", SIGNER, "verify", "--print-certs", apk])
    for line in ok.splitlines():
        if "Signer #1 certificate" in line:
            log("    " + line.strip())

    # ---------- 文档指纹自愈 ----------
    #
    # README 里那行短 md5（dev ... / rel ...）是 verify_docs 用来防“文档撒谎”的锚。
    # 它跟着 classes.dex 走，**每次重建都会变**。我手工同步忘过三次，
    # 每次都让套件变红、又要多跑一轮。所以在这里直接写回去：
    # 只改刚刚构建的那个槽位（dev/rel），另一个保持原样。
    try:
        import re as _re
        _readme = os.path.join(P, "README.md")
        if os.path.isfile(_readme):
            with zipfile.ZipFile(apk) as _z:
                _cur = hashlib.md5(_z.read("classes.dex")).hexdigest()[:8]
            with open(_readme, encoding="utf-8", newline="") as _f:
                _txt = _f.read()
            _m = _re.search("dev `([0-9a-f]{8})" + "\u2026" + "` / rel `([0-9a-f]{8})" + "\u2026" + "`", _txt)
            if _m:
                _dev, _rel = _m.group(1), _m.group(2)
                if os.environ.get("DSP_VARIANT", "").strip().lower() == "release":
                    _rel = _cur
                else:
                    _dev = _cur
                _new = "dev `" + _dev + "\u2026` / rel `" + _rel + "\u2026`"
                if _new != _m.group(0):
                    _txt = _txt.replace(_m.group(0), _new, 1)
                    with open(_readme, "w", encoding="utf-8", newline="") as _f:
                        _f.write(_txt)
                    log("    README 指纹已同步: " + _new)
    except Exception as _e:
        log("    ⚠ README 指纹同步失败（不影响产物）: %s" % _e)

    log("")
    log("==> 完成")
    return 0


if __name__ == "__main__":
    sys.exit(main())
