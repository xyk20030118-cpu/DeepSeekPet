# -*- coding: utf-8 -*-
"""动态表情包（轮次80）判据：GIF / 动图 WebP 要能**上传并按真实类型落盘**、**在气泡与列表里真的动**。

为什么需要它（三个旧的硬伤，都是从源码里读出来的）：
  ① `Memes.addCustom()` 原来**永远写 `<name>.png`** —— 上传 GIF 会变成"后缀 png、内容是 GIF"
     的畸形文件，随后解码必然失败 ⇒ 动态表情根本不可能用；
  ② `openImage()` 的自定义目录回退**只找 `.png`**；
  ③ 气泡走 `BitmapFactory.decodeStream` ⇒ 只可能是静态首帧。

判据（源码级，因为 Memes 依赖 android.* 进不了 fpstest 的纯类套件）：
  ① 落盘按扩展名推断：`extByHeader` 认 GIF87a/GIF89a、RIFF…WEBP、PNG、JPEG 文件头；
  ② `addCustom` 里**不许再出现** `name + ".png"` 这种固定后缀；
  ③ `openDrawable` 必须用 `ImageDecoder` + `AnimatedImageDrawable` + `REPEAT_INFINITE`；
  ④ 气泡与表情管理页缩略图都必须走 `openDrawable`（不许再只有 BitmapFactory）；
  ⑤ 隐藏气泡时要有 `stopIfAnimated`（不停会后台烧 CPU）；
  ⑥ 动图有独立体积上限（`MAX_ANIMATED_BYTES`）。

真机验证（pet411，见 R77 交付清单）：推一张 3 帧 GIF ⇒
  · logcat `DSMeme: 动态表情播放: 测试动图 (测试动图.gif)`
  · 列表里两张相隔 300ms 的截图在缩略图区域差 **21609 像素** ⇒ 真的在动。

跑法：python build/verify_memes_animated.py
"""
import io
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "com", "deepseek", "pet")
MEMES = io.open(os.path.join(SRC, "Memes.java"), encoding="utf-8").read()
FS = io.open(os.path.join(SRC, "FloatingService.java"), encoding="utf-8").read()
MA = io.open(os.path.join(SRC, "MainActivity.java"), encoding="utf-8").read()

fails, total = [], 0


def ok(name, cond, detail=""):
    global total
    total += 1
    if not cond:
        fails.append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + ((" :: " + detail) if detail else ""))


def main():
    print("[1] 落盘按真实类型（上传动态表情的前提）")
    ok("★★ extByHeader 认出 GIF87a/GIF89a", "'G' && head[1] == 'I' && head[2] == 'F'" in MEMES)
    ok("★★ extByHeader 认出 RIFF…WEBP", "head[8] == 'W' && head[9] == 'E'" in MEMES)
    ok("★★ extByHeader 认出 PNG/JPEG", "0xFF) == 0x89" in MEMES and "0xD8" in MEMES)
    ok("★★★ addCustom 不再把后缀写死成 .png",
       'name + ".png"' not in MEMES, "还有固定 .png")
    ok("★★ addCustom 用 name + ext 落盘", 'name + ext' in MEMES)
    ok("★★★ json 缺失时也要走目录扫描兜底（原来提前 return，丢进去的 GIF 看不见）",
       "if (!f.exists()) return withDiskFallback(ctx, o);" in MEMES)
    ok("★★ 目录扫描接受全部图片扩展名", "String fext = extOfName(fn);" in MEMES)
    ok("★★ rename/删除/判重都不再假定 .png",
       'name + ".png"' not in MEMES and 't + ".png"' not in MEMES
       and "findCustomFile(ctx, base)" in MEMES)
    ok("★ 扩展名表统一成 IMG_EXTS", "IMG_EXTS" in MEMES and MEMES.count("IMG_EXTS") >= 3)

    print("[2] 播放真动图（不是首帧）")
    ok("★★★ 有 openDrawable 且用 ImageDecoder", "openDrawable" in MEMES and "ImageDecoder" in MEMES)
    ok("★★★ 解出 AnimatedImageDrawable 并无限循环",
       "AnimatedImageDrawable" in MEMES and "REPEAT_INFINITE" in MEMES)
    ok("★★ 动图会 start()", "a.start()" in MEMES)
    ok("★★ API<28 老实退回首帧并有日志（不假装能播）",
       "本机 Android < 8.0" in MEMES)

    print("[3] 接线：气泡 + 列表缩略图")
    ok("★★★ 气泡表情改走 openDrawable", "Memes.openDrawable(this, memeName, 0)" in FS)
    ok("★★ 气泡隐藏时停动画（否则后台烧 CPU）", "Memes.stopIfAnimated" in FS)
    ok("★★ 表情管理页缩略图也走 openDrawable", "Memes.openDrawable(this, it.name, 224)" in MA)

    print("[4] 体积上限与判定辅助")
    ok("★★ 动图独立上限（12 MB）", "MAX_ANIMATED_BYTES" in MEMES and "12L * 1024 * 1024" in MEMES)
    ok("★★ 静态表情仍守 8 MB", "MAX_STATIC_BYTES" in MEMES)
    ok("★ isAnimatedName 判 .gif/.webp", "isAnimatedName" in MEMES)

    print("[5] ⑩ 短视频表情（mp4 / webm）")
    ok("★★★ IMG_EXTS 含 mp4/webm", '".mp4"' in MEMES and '".webm"' in MEMES)
    ok("★★★ extByHeader 认 ftyp（mp4 家族）", "head[4] == 'f' && head[5] == 't'" in MEMES)
    ok("★★★ extByHeader 认 RIFF…WEBM", 'return ".webm"' in MEMES)
    ok("★★ 有 isVideoName 判定", "public static boolean isVideoName" in MEMES)
    ok("★★ 视频单独上限 32MB", "MAX_VIDEO_BYTES" in MEMES and "32L * 1024 * 1024" in MEMES)
    ok("★★ 视频取首帧（列表缩略图/老系统兜底）", "videoFrame(" in MEMES
       and "MediaMetadataRetriever" in MEMES)
    ok("★★ 视频尺寸用 MediaMetadataRetriever 读",
       "METADATA_KEY_VIDEO_WIDTH" in MEMES)
    ok("★★★ 气泡有 VideoView 并与图片互斥", "bubbleMemeVideo" in FS
       and "stopBubbleMemeVideo" in FS and "显示视频表情" in FS)
    ok("★★ 视频静音循环（表情包不该突然出声）",
       "mp.setLooping(true)" in FS and "mp.setVolume(0f, 0f)" in FS)

    print()
    print("=" * 60)
    if fails:
        print("%d / %d 项失败" % (len(fails), total))
        for f in fails:
            print("  - " + f)
        sys.exit(1)
    print("ALL ANIMATED-MEME CHECKS PASSED (%d 项)" % total)


if __name__ == "__main__":
    main()
