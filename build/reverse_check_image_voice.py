"""反向验证：图片/语音断言必须能抓到错误。

破坏项（都是真实可能犯的错）：
  A. TtsEngine 引用语音识别 → 破坏"说话不依赖听"
  B. deleteModel 里顺手碰 TTS → 删资源会伤到说话
  C. 代码里写死"没内置"为错误（不再区分）
  D. 图片背景自算缩放（不复用 VideoFitMath）
  E. 大图直接解码（去掉 inJustDecodeBounds）→ 会 OOM
  F. assemble.py 改回默认内置 228MB
  G. 设置页去掉"不影响说话"的说明
  M. 设置页去掉文件导入入口（删了装不回来）
  N. 导入阈值与判定脱节（各写一个 200L）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SCRIPT = r'D:\petport_lab\petbuild\build\verify_image_voice.py'


def run():
    r = subprocess.run([sys.executable, SCRIPT], capture_output=True)
    return r.returncode, r.stdout.decode('utf-8', 'replace')


def trial(name, path, old, new, unique=True):
    bak = path + '.rv'
    shutil.copy2(path, bak)
    try:
        s = io.open(path, encoding='utf-8-sig').read()
        n = s.count(old)
        if n == 0:
            print('  !! %-46s 找不到片段，跳过' % name)
            return None
        if unique and n > 1:
            print('  !! %-46s 片段出现 %d 次，跳过' % (name, n))
            return None
        io.open(path, 'w', encoding='utf-8', newline='\n').write(
            s.replace(old, new, 1))
        rc, out = run()
        caught = (rc != 0)
        print('  %s %-46s 退出码=%d' % ('✓' if caught else '✗', name, rc))
        if not caught:
            print('       （没抓到 → 这条断言是空转的）')
        return caught
    finally:
        shutil.copy2(bak, path)
        os.remove(bak)


print('=== 反向验证：图片/语音断言必须能抓到错误 ===')
print()
results = []

# A. TtsEngine 引用语音识别（破坏"说话不依赖听"）
results.append(trial(
    'A. TtsEngine 引用 ASR（说话依赖听）',
    os.path.join(SRC, 'TtsEngine.java'),
    'public final class TtsEngine {',
    'public final class TtsEngine {\n'
    '    private SherpaAsr asrProbe;   // 违规：说话不该依赖语音识别'))

# B. deleteModel 顺手碰 TTS
results.append(trial(
    'B. deleteModel 里碰 TTS',
    os.path.join(SRC, 'SherpaAsr.java'),
    '        releaseLoaded();\n        Log.i(TAG,',
    '        TtsEngine ttsProbe = null;\n        releaseLoaded();\n        Log.i(TAG,'))

# C. 不再区分"没内置"与"真出错"
results.append(trial(
    'C. 把"没内置"当成错误',
    os.path.join(SRC, 'SherpaAsr.java'),
    '                lastReason = REASON_NOT_BUNDLED;',
    '                lastReason = REASON_IO;'))

# D. 图片自算缩放（不复用 VideoFitMath）
results.append(trial(
    'D. 图片自算缩放（不复用数学层）',
    os.path.join(SRC, 'ImageBackgroundView.java'),
    '        float[] t = VideoFitMath.compute(bw, bh, viewW, viewH, fitMode, crop);',
    '        float[] t = new float[]{1f, 1f, 0f, 0f};   // 违规：自己算'))

# E. 去掉降采样探测（会 OOM）
results.append(trial(
    'E. 去掉 inJustDecodeBounds（大图 OOM）',
    os.path.join(SRC, 'ImageBackgroundView.java'),
    '            o.inJustDecodeBounds = true;',
    '            o.inJustDecodeBounds = false;'))

# H. ★ 误用 toViewSpaceTransform（真实 bug：只铺 67%）
results.append(trial(
    'H. 误用 toViewSpaceTransform（只铺 67%）',
    os.path.join(SRC, 'ImageBackgroundView.java'),
    '        float[] t = VideoFitMath.compute(bw, bh, viewW, viewH, fitMode, crop);',
    '        float[] t = VideoFitMath.toViewSpaceTransform(\n'
    '                VideoFitMath.compute(bw, bh, viewW, viewH, fitMode, crop),\n'
    '                bw, bh, viewW, viewH);'))

# I. ★ 恢复成两个独立小节（破坏"合并"）
results.append(trial(
    'I. 拆回两个独立背景小节',
    os.path.join(SRC, 'MainActivity.java'),
    '            addSection(box, "\\uD83D\\uDDBC 背景");',
    '            addSection(box, "\\uD83D\\uDDBC 图片背景");'))

# J. ★ setBgKind 只写一个开关（破坏互斥，会退回"两个都开着"）
results.append(trial(
    'J. setBgKind 只写一个开关（破坏互斥）',
    os.path.join(SRC, 'Prefs.java'),
    '                .putBoolean(KEY_IMAGE_BG, img)\n'
    '                .putBoolean(KEY_VIDEO_BG, vid)',
    '                .putBoolean(KEY_IMAGE_BG, img)'))

# K. ★ 选完图片不走 setBgKind（会与视频同时开着）
results.append(trial(
    'K. 选完图片不走 setBgKind',
    os.path.join(SRC, 'MainActivity.java'),
    '                Prefs.setBgKind(this, Prefs.BG_IMAGE);',
    '                Prefs.setImageBgEnabled(this, true);'))

# L. ★ 恢复"谁优先"开关（类型已唯一，它没意义了）
results.append(trial(
    'L. 恢复「有视频时优先用图片」开关',
    os.path.join(SRC, 'MainActivity.java'),
    '            if (_hasImg && _hasVid) {',
    '            addToggle(box, "有视频时优先用图片（关掉则优先用视频）", true, null);\n'
    '            if (_hasImg && _hasVid) {'))

# F. assemble.py 改回默认内置
results.append(trial(
    'F. 默认改回内置 228MB 模型',
    r'D:\petport_lab\petbuild\build\assemble.py',
    'BUNDLE_ASR = os.environ.get("DSP_BUNDLE_ASR", "") == "1"',
    'BUNDLE_ASR = True'))

# M. 设置页去掉文件导入入口（删了装不回来）
# ★ 破坏方式必须是**整行删除**：只改文案的话，REQ_PICK_ASR_MODEL /
#   importModelFile 等符号断言还在，套件照样通过（空转过一次）。
#   反向验证只做字符串匹配不编译，所以删一行不会引入编译问题。
results.append(trial(
    'M. 设置页去掉文件导入入口',
    os.path.join(SRC, 'MainActivity.java'),
    '            addButton(box, "\\uD83D\\uDCE5 从文件导入语音模型（约 229 MB）",\n',
    ''))

# N. 导入阈值与判定脱节（isInstalled 另写一个字面量）
results.append(trial(
    'N. isInstalled 另写阈值（与导入脱节）',
    os.path.join(SRC, 'SherpaAsr.java'),
    'return modelFile(c).isFile() && modelFile(c).length() > MODEL_MIN_BYTES',
    'return modelFile(c).isFile() && modelFile(c).length() > 200L * 1024 * 1024'))

# G. 去掉"不影响说话"的说明
results.append(trial(
    'G. 删除框不再说明不影响说话',
    os.path.join(SRC, 'MainActivity.java'),
    '                                    + "★ 只影响「语音输入」（用嘴说话打字）。\\n"',
    '                                    + "\\n"'))

rc, out = run()
print()
print('  恢复后退出码 = %d' % rc)
if rc != 0:
    for line in out.splitlines():
        if 'FAIL' in line:
            print('     ' + line.strip()[:100])

good = [r for r in results if r is not None]
print()
print('  %d/%d 个破坏被断言抓到' % (sum(1 for r in good if r), len(good)))
sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
