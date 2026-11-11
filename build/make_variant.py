#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DeepSeekPet 构建变体生成器
==========================

用法:
    python3 build/make_variant.py <源工程> <目标工程> <release|dev>

作用:
    从开发版工程生成一个「正式发布版」工程副本，自动完成：
      1. AndroidManifest.xml  包名 com.deepseek.pet -> com.doropet.release
                              版本号 versionCode -> 1000+原值（正式版单独递增）
                              label    -> DeepSeek桌宠·正式版
                              meta-data dsp.variant -> 正式版
      2. 全部 .java 源码内 "com.deepseek.pet" -> "com.doropet.release"
      3. 源码目录 src/com/deepseek/pet -> src/com/doropet/release
      4. 把 assets/reward_qr.png（赞赏码）打进正式版
         —— 开发版不含该图，关于页会自动隐藏赞赏区块

为什么用独立包名:
    正式版与开发版可以同时安装在同一台手机上，互不覆盖、各自保留数据。
    独立包名必须配独立签名（见 build/4_variant_release_linux.sh）。

排除: dist/ build/out/ build/work/ —— 中间产物，由构建脚本重新生成。
"""
import io, os, re, shutil, sys

DEV_PKG = "com.deepseek.pet"
REL_PKG = "com.doropet.release"
DEV_PKG_PATH = "com/deepseek/pet"
REL_PKG_PATH = "com/doropet/release"

SKIP_DIRS = {"dist", "out", "work", ".git"}


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    src, dst, mode = sys.argv[1], sys.argv[2], sys.argv[3]
    if mode not in ("release", "dev"):
        sys.exit("mode 必须是 release 或 dev")
    src = os.path.abspath(src)
    dst = os.path.abspath(dst)
    if os.path.exists(dst):
        shutil.rmtree(dst)

    # ---------- 1. 复制工程（跳过中间产物） ----------
    def ignore(d, names):
        # build/ 下的编译中间产物、PC 工具链暂存一律不带进正式版工程
        if os.path.basename(d) == "build":
            out = []
            for n in names:
                if n in ("toolchain-pc",) or n.startswith("out") or n.startswith("work"):
                    out.append(n)
            return out
        # 仅在工程根跳过 dist/ 等
        if os.path.abspath(d) == src:
            return [n for n in names if n in SKIP_DIRS or n.startswith("out") or n.startswith("work")]
        return []
    shutil.copytree(src, dst, ignore=ignore,
                    ignore_dangling_symlinks=True)
    print("  已复制工程 -> %s" % dst)

    # ---------- 2. 源码目录搬包 ----------
    old_dir = os.path.join(dst, "src", DEV_PKG_PATH)
    new_dir = os.path.join(dst, "src", REL_PKG_PATH)
    os.makedirs(os.path.dirname(new_dir), exist_ok=True)
    shutil.move(old_dir, new_dir)
    # 清掉搬走后留下的空目录
    d = os.path.join(dst, "src")
    for root, dirs, files in os.walk(d, topdown=False):
        if not os.listdir(root):
            os.rmdir(root)
    print("  源码目录 %s -> %s" % (DEV_PKG_PATH, REL_PKG_PATH))

    # ---------- 3. 源码内包名替换 ----------
    n_files = 0
    n_hits = 0
    for root, dirs, files in os.walk(os.path.join(dst, "src")):
        for f in files:
            if not f.endswith(".java"):
                continue
            p = os.path.join(root, f)
            t = io.open(p, encoding="utf-8").read()
            c = t.count(DEV_PKG)
            if c:
                t = t.replace(DEV_PKG, REL_PKG)
                io.open(p, "w", encoding="utf-8").write(t)
                n_files += 1
                n_hits += c
    print("  %d 个源文件内 %d 处包名已替换" % (n_files, n_hits))

    # ---------- 4. R.java 也搬包（阶段①会重新生成，这里保持一致） ----------
    r_old = os.path.join(dst, "gen", DEV_PKG_PATH)
    if os.path.isdir(r_old):
        r_new = os.path.join(dst, "gen", REL_PKG_PATH)
        os.makedirs(os.path.dirname(r_new), exist_ok=True)
        shutil.move(r_old, r_new)
        for f in os.listdir(r_new):
            p = os.path.join(r_new, f)
            t = io.open(p, encoding="utf-8").read().replace(DEV_PKG, REL_PKG)
            io.open(p, "w", encoding="utf-8").write(t)
        print("  gen/R.java 已搬包")

    # ---------- 5. 清单 ----------
    mf = os.path.join(dst, "AndroidManifest.xml")
    m = io.open(mf, encoding="utf-8").read()
    assert 'package="%s"' % DEV_PKG in m

    # ★ 全局替换：不仅是 package 属性，组件 action（如 <intent-filter> 里的
    #   "com.deepseek.pet.KEEPALIVE_TICK"）也必须跟着变，否则正式版里
    #   注册的 action 与代码发送的不一致，广播收不到、功能静默失效。
    m = m.replace(DEV_PKG, REL_PKG)

    # 版本号：正式版 = 1000 + 开发版 versionCode，便于两条线各自递增
    def bump_code(mo):
        return "android:versionCode=\"%d\"" % (1000 + int(mo.group(1)))
    m = re.sub(r'android:versionCode="(\d+)"', bump_code, m, count=1)

    m = m.replace('android:label="DeepSeek桌宠"',
                  'android:label="DeepSeek桌宠·正式版"', 1)

    # 版本类型元数据
    if 'dsp.variant' in m:
        m = re.sub(r'(android:name="dsp\.variant"\s*\n\s*android:value=")[^"]*(")',
                   r'\g<1>正式版\g<2>', m)
    else:
        anchor = '        android:extractNativeLibs="true">\n'
        m = m.replace(anchor, anchor + '''
        <meta-data
            android:name="dsp.variant"
            android:value="正式版" />
''', 1)

    io.open(mf, "w", encoding="utf-8").write(m)
    print("  清单: package=%s" % REL_PKG)

    # ---------- 6. 赞赏码 ----------
    # 查找顺序：环境变量 -> 工程内 build/release_src/ -> 设备遗留路径 -> 相邻目录
    #   正式版才会带赞赏码；开发版刻意不含（关于页据此隐藏赞赏区块）。
    #   放进工程内 build/release_src/ 后，电脑端也能独立构建正式版。
    qr_cands = [
        os.environ.get("DSP_REWARD_QR", ""),
        os.path.join(src, "build", "release_src", "reward_qr.png"),
        "/data/local/tmp/release_src/reward_qr.png",
        os.path.join(os.path.dirname(src), "release_src", "reward_qr.png"),
    ]
    qr_src = next((c for c in qr_cands if c and os.path.isfile(c)), None)
    qr_dst = os.path.join(dst, "assets", "reward_qr.png")
    if qr_src:
        os.makedirs(os.path.dirname(qr_dst), exist_ok=True)
        shutil.copy2(qr_src, qr_dst)
        print("  赞赏码已打入: assets/reward_qr.png (%d B, 源 %s)"
              % (os.path.getsize(qr_dst), qr_src))
    else:
        print("  !! 未找到赞赏码 reward_qr.png，正式版将不含赞赏码")
        print("     已查找: %s" % " | ".join([c for c in qr_cands if c]))

    # ---------- 7. 自检 ----------
    bad = []
    for root, dirs, files in os.walk(os.path.join(dst, "src")):
        for f in files:
            if f.endswith(".java"):
                if DEV_PKG in io.open(os.path.join(root, f), encoding="utf-8").read():
                    bad.append(f)
    mm = io.open(mf, encoding="utf-8").read()
    print("\n  自检:")
    print("    源码残留旧包名: %s" % (bad if bad else "无"))
    print("    清单 package  : %s" % re.search(r'package="([^"]+)"', mm).group(1))
    print("    清单 versionCode: %s" % re.search(r'versionCode="(\d+)"', mm).group(1))
    print("    dsp.variant   : %s" % (re.search(r'dsp\.variant"\s*\n\s*android:value="([^"]*)"', mm).group(1)
                                      if 'dsp.variant' in mm else "(无)"))
    print("    赞赏码         : %s" % ("有" if os.path.isfile(qr_dst) else "无"))
    if bad:
        sys.exit("!! 源码仍有旧包名残留")


if __name__ == "__main__":
    main()
