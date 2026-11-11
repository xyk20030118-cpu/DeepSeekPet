import hashlib
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import zip_paths, dev_apk, rel_apk, ver  # noqa: E402

ZIPS = zip_paths()

# 期望的 dex md5：★ **从磁盘上刚编出来的两个 APK 现算**，不再手抄常量。
#
# 以前这里是一对人工粘贴的值，注释写着"每次重编两个 APK 后都要更新这里"。
# 轮次㉖ 收尾时发现它已经骗过一次：当时 EXPECT 里的 `e3152cf6…` / `65d7db70…`
# **恰好等于三个 zip 里 APK 的 dex**，却与磁盘上任何一次编译产物都不相等
# （全工程搜这两个值，只有这一行提到）⇒ 脚本一路绿灯，而 zip 里装的是**上一轮**的 APK。
# 教训：**判据不许从被检查的东西里抄**（抄了就永远绿）。
# 现在 EXPECT 现场读 dev/rel 两个 APK 的 classes.dex 求 md5，
# 语义变成"zip 里的 APK 与磁盘上刚编的 APK 是同一个" —— 这正是本脚本唯一要说的事；
# "是不是最新版本"仍由 `verify_features.py` 的探针清单负责（它读 dex 里的字符串）。
def dex_md5_of(apk_path):
    if not os.path.isfile(apk_path):
        raise SystemExit('!! 读不到 APK（先编它）: %s' % apk_path)
    with zipfile.ZipFile(apk_path) as z:
        return hashlib.md5(z.read('classes.dex')).hexdigest()


EXPECT = {
    'com.deepseek.pet': dex_md5_of(dev_apk()),
    'com.doropet.release': dex_md5_of(rel_apk()),
}

print('磁盘上刚编的 dex（本脚本的判据，现算）:')
for _k, _v in EXPECT.items():
    print('   %-20s %s' % (_k, _v))
print()

# 语音大模型独立分发：开发版/正式版 zip 里必须带 ./sensevoice.int8.onnx（>200MB）；
# 项目移植包不带它（源码包里 model/ 目录本来就有，不重复）。
MODEL_ARC = './sensevoice.int8.onnx'
MODEL_MIN = 200 * 1024 * 1024

# ★★ 轮次㉝（用户要求「把角色包和交付打包在一起」）：**角色包也必须随 1/2 包一起交付**。
#   理由：轮次㉜ 把 106 个动作与 27 张对话表情**全部剔出了 APK** ⇒ 没有角色包，
#   用户装了 App 也只有 1 个兜底待机动作（不能说话配图）。
#   项目移植包不带它 —— 那里面有它的全部原料（build/charpack_src/ + model/webp/）。
#   ★ 条目名从**版本号**推（与 pack_delivery.py 同一判据），不手抄。
CHARPACK_ARC = './DeepSeekPet_默认角色包_%s.zip' % ver()
CHARPACK_MIN = 300 * 1024 * 1024

bad_zips = []
for z in ZIPS:
    if not os.path.exists(z):
        print('缺失:', z)
        bad_zips.append(z)
        continue
    zf = zipfile.ZipFile(z)
    names = zf.namelist()
    print('== %s' % os.path.basename(z))
    print('   条目 %d，大小 %.1f MB' % (len(names), os.path.getsize(z) / 1048576))

    # 条目名必须带 ./ 前缀
    bad = [n for n in names if not n.startswith('./')]
    print('   条目名带 ./ 前缀: %s' % ('OK' if not bad else '!! %d 条不带' % len(bad)))
    if bad:
        bad_zips.append(z)

    # ★ 只有开发版/正式版包才查独立模型：项目移植包里模型以 ./model/ 路径存在，
    #   不是 ./sensevoice.int8.onnx —— 一律查会误报（空转过一次）。
    is_apk_zip = any(n.lower().endswith('.apk') and '/dist/' not in n and '/build/' not in n
                     for n in names)
    if is_apk_zip:
        # ★★ 轮次60（用户要求「角色包跟交付包还有语音模型**每次分开发**」）：
        #   1/2 包现在**只装 APK + 文档五件套** ⇒ 这两样**必须不在包里**
        #   （还在包里 = 仍然捆着，用户改一行代码就要重下 476 MB）。
        #   ★ 它们作为**独立交付件**存在与否，在下面统一查（每件只查一次）。
        for arc, what in ((MODEL_ARC, '语音模型'), (CHARPACK_ARC, '角色包')):
            if arc in names:
                print('   !! %s 还捆在 1/2 包里（%s）—— 轮次60 起要求分开交付' % (what, arc))
                bad_zips.append(z)
            else:
                print('   %s 不在 1/2 包里: OK（已独立分发）' % what)

    for n in names:
        # ★ 只查顶层交付 APK：移植包里万一混进 build 中间产物 apk（如 work_win），
        #   它的 dex 是旧的，会误报"不认识"。
        if n.lower().endswith('.apk') and n.count('/') <= 1:
            info = zf.getinfo(n)
            data = zf.read(n)
            # APK 内的 classes.dex 才是判定版本的依据
            import io
            inner = zipfile.ZipFile(io.BytesIO(data))
            dex = inner.read('classes.dex')
            md5 = hashlib.md5(dex).hexdigest()
            # ★ 判据是"与磁盘上刚编的 APK 的 dex 逐字节相同"，不是"看着像新的"
            #   （轮次⑯ 的教训：两边一起旧也算一致 ⇒ 所以 EXPECT 必须来自**编译产物**）
            hit = [k for k, v in EXPECT.items() if v == md5]
            print('   %s' % n)
            print('      %8.1f MB  dex md5=%s  %s' % (
                len(data) / 1048576, md5,
                'OK 与刚编的 APK 一致 (%s)' % hit[0] if hit
                else '?? 这个 dex 不是本机刚编出来的那个（zip 旧了 / 拿错包）'))
            if not hit:
                bad_zips.append(z)
            # ★ 瘦身铁律：APK 里不得再有大模型（>200MB 的 onnx）
            big = [m for m in inner.namelist()
                   if m.endswith('.onnx')
                   and inner.getinfo(m).file_size > MODEL_MIN]
            if big:
                print('      !! APK 里仍有大模型: %s' % big)
                bad_zips.append(z)
            else:
                print('      APK 内无大模型（瘦身） OK')
    print()

# ★★ 轮次60：三件独立交付物 —— 交付包之外，角色包与语音模型必须**各自成件**在交付目录里
print('=== 独立交付件（轮次60 起：角色包 / 语音模型 分开给）===')
DELIV = os.path.dirname(ZIPS[0])
_sep = [('角色包', CHARPACK_ARC[2:], CHARPACK_MIN),
        ('语音模型', None, MODEL_MIN)]
for what, name, minsz in _sep:
    if name is None:
        # 语音模型文件名带版本号：DeepSeekPet_语音模型_v<版本>_sensevoice.int8.onnx
        cand = [f for f in os.listdir(DELIV)
                if f.startswith('DeepSeekPet_语音模型_') and f.endswith('.onnx')]
        p = os.path.join(DELIV, cand[0]) if cand else None
    else:
        p = os.path.join(DELIV, name)
    if p and os.path.isfile(p) and os.path.getsize(p) > minsz:
        print('   %s: %s  %.1f MB  OK 独立成件' % (
            what, os.path.basename(p), os.path.getsize(p) / 1048576))
    else:
        print('   !! %s 缺（或太小）：%s' % (what, p))
        bad_zips.append('独立件:' + what)
print()

if bad_zips:
    print('!! %d 个包有问题' % len(set(bad_zips)))
    sys.exit(1)
print('==> 三个包全部 OK')