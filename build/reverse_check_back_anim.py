"""反向验证：预测式返回源码断言必须能抓到错误实现。

破坏项（都是**真实容易犯的错**）：
  A. 把动画几何改成与 pageSlide 不一致（松手时会跳）
  B. 去掉 onBackCancelled（取消后页面停在半位移）
  C. 把 34 的 API 挪出嵌套类（低版本类加载崩溃）
  D. 去掉清单 opt-in（系统不会把返回手势交给 App）
  E. 去掉 SDK 版本分流（33 上注册动画回调却收不到进度）
"""
import io
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
SCRIPT = r'D:\petport_lab\petbuild\build\verify_predictive_source.py'


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
            # ★ 跳过 = 没验证，必须算失败（见 reverse_check_theme 同一处说明）
            print('  ✗ %-46s 找不到片段 → 未验证（视为失败）' % name)
            return False
        if unique and n > 1:
            print('  ✗ %-46s 片段出现 %d 次 → 未验证（视为失败）' % (name, n))
            return False
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


print('=== 反向验证：预测式返回断言必须能抓到错误 ===')
print()
MA = os.path.join(SRC, 'MainActivity.java')
MAN = r'D:\petport_lab\petbuild\AndroidManifest.xml'
results = []

# A. 几何与 pageSlide 不一致（写死一个不同的值）
#    ★ 片段随重构更新过：MAX_SHIFT 现在引用 UiFactory.PAGE_SLIDE_FRACTION
#      （单一来源，防两处漂移），旧的 `= 1f / 3f` 已不存在。
#      断言 [5] 检查的正是这个引用关系，所以破坏点就是它。
results.append(trial(
    'A. 动画几何与 pageSlide 不一致',
    MA,
    'static final float MAX_SHIFT = UiFactory.PAGE_SLIDE_FRACTION;',
    'static final float MAX_SHIFT = 1f / 3f;'))

# B. 去掉 onBackCancelled（取消后停在半位移）
results.append(trial(
    'B. 去掉 onBackCancelled',
    MA,
    'public void onBackCancelled() {',
    'public void onBackCancelledDisabled() {'))

# C. 34 的 API 挪出嵌套类（破坏类加载隔离）
results.append(trial(
    'C. 动画回调类逃出 BackApi34',
    MA,
    'private static class BackApi34 {',
    'private static class BackApi34x {\n'
    '        static void probe() {\n'
    '            Class<?> c = android.window.OnBackAnimationCallback.class;\n'
    '        }\n'
    '    }\n'
    '    private static class BackApi34 {'))

# D. 去掉取消时的归位
results.append(trial(
    'D. 取消时不归位（停在半位移）',
    MA,
    'backAnimView.animate()\n'
    '                .translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)',
    'backAnimView.animate()\n'
    '                .alpha(1f)'))

rc, out = run()
print()
print('  恢复后退出码 = %d' % rc)
if rc != 0:
    for line in out.splitlines():
        if 'FAIL' in line:
            print('     ' + line.strip()[:100])

good = [r for r in results if r is not None]
print()
print('  %d/%d 个破坏被断言抓到（跳过按未验证计）'
      % (sum(1 for r in results if r), len(results)))
sys.exit(1 if (any(r is False for r in results) or rc != 0) else 0)
