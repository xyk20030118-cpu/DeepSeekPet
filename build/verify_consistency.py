"""验证「裁剪框内 == 屏幕内容」（预览与实景一致的**可测形式**）。

判据：读 prefs 里的 crop，换算成两个量比较 ——
  A) 框本身覆盖了源视频多宽（像素）
  B) 按 COVER 铺满后，屏幕上实际能看到源视频多宽（像素）
锁比例成功时 A ≈ B（差 <2%），这就是"框里是什么、屏幕就是什么"。

★ 为什么不用"crop 的像素比 == 屏幕比 2.222"当判据：
  锁比例的目标是**内容壳**的比例（内容壳比物理屏矮一条底部导航栏：
  2337 vs 2400 ⇒ 2.1639 vs 2.2222，差 2.7%），两者本来就不相等。
  真正的判据只能是上面那条"框 ≈ 屏幕可见区"。

用法: python build/verify_consistency.py
"""
import re
import subprocess
import sys
import time

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
XML = '/data/data/com.deepseek.pet/shared_prefs/deepseek_pet.xml'
FX, FY = 1080, 2337          # 内容壳尺寸（沉浸式下比屏幕矮 63px）
VW, VH = 1920, 1080
sys.stdout.reconfigure(encoding='utf-8', errors='replace')


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


def pref(k):
    o = sh('shell', 'cat', XML).stdout.decode('utf-8', 'replace')
    m = re.search(r'name="' + k + r'"[^>]*?(?:value="([^"]*)"|>([^<]*)<)', o)
    return (m.group(1) if m and m.group(1) is not None
            else (m.group(2) if m else None))


def analyze(crop, fit, view_wh=(FX, FY)):
    """返回 (框覆盖的源宽度, 屏幕可见源宽度)"""
    l, t, r, b = crop
    bw_src = (r - l) * VW
    bh_src = (b - t) * VH
    vw, vh = view_wh
    s = max(vw / bw_src, vh / bh_src) if fit == 1 else min(vw / bw_src, vh / bh_src)
    return bw_src, vw / s


def main():
    crop = [float(x) for x in (pref('app_video_bg_crop') or '0,0,1,1').split(',')]
    fit = int(pref('app_video_bg_fit') or 0)
    bw_src, vis_src = analyze(crop, fit)

    print('crop = %s   fit = %d' % ([round(v, 4) for v in crop], fit))
    print()
    print('  框覆盖的源宽度 = %.1f px' % bw_src)
    print('  屏幕可见源宽度 = %.1f px' % vis_src)
    err = abs(bw_src - vis_src) / bw_src * 100
    print('  偏差 = %.2f%%  ⇒ %s' % (
        err, 'OK 框内即屏幕内容（预览与实景一致）' if err < 2
        else '!! 仍不一致（铺满会裁掉框的一部分）'))

    r = ((crop[3] - crop[1]) * VH) / ((crop[2] - crop[0]) * VW)
    print()
    print('  框像素比 = %.4f   内容壳比例 = %.4f   物理屏比例 = %.4f' % (
        r, FY / FX, 2400 / 1080))
    return 0 if err < 2 else 1


if __name__ == '__main__':
    sys.exit(main())