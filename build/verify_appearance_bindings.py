# -*- coding: utf-8 -*-
"""P5 断言：形象来源纳入角色模型（待办 0h 的 P5）。

P0～㉒ 把"角色 = 动作＋台词＋表情＋性格＋各自的绑定"做完了，但**这只桌宠长什么样**
一直是全局的：四个 pref（`use_custom_image` / `use_custom_anim` / `use_custom_model` /
`use_custom_l2d`）+ 四个固定路径（`filesDir/custom_pet.png` 等）。于是"切了角色，
形象还是上一个角色的脸" —— 角色系统缺了最直观的那一半。

本轮把形象收进 `filesDir/appearance/<角色 id>/`，每个角色一份：

  ① 五种来源：内置角色包 / 上传图片 / 上传动图 / 序列帧模型包 / Live2D 模型包；
  ② **素材缺失就回退内置**（选过"我的图片"但文件被清理 ⇒ 回内置，不留一块空白）；
  ③ **关掉一个来源 ≠ 关掉全部**（老代码"先开 anim 再把 image 置 false"的顺序 bug）；
  ④ 设置页那一行报出当前角色的形象来源，点开可换（● 当前 / ○ 其他 / 未导入的标出来）；
  ⑤ 「恢复默认形象」只重置**当前角色**；删角色连 `appearance/<id>/` 一起清；
  ⑥ 升级路径：老的全局形象**搬**进当前角色的目录（幂等，不把用户的脸弄丢）。

★ 两处是"真跑"而不是 grep：
  * `build/fpstest` 第 [20] 节：`Appearance` 是纯类，编的是打进 APK 的那一份，逐条验判据；
  * `build/rt_test --only=appearance`：`AppearanceCheck` 在桌面 JVM 上**真读写文件** ——
    它钉的是"读路径不建目录 / 按角色隔离 / 关掉≠关掉全部 / 迁移幂等 / 不留空壳"，
    这些在设备上只能一个个点着看。

反向破坏见 reverse_check_appearance_bindings.py。
"""
import io
from artifacts import dev_apk
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
DEV_APK = dev_apk()

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。

    ★ 与 verify_character_switch / verify_action_import 用的是同一份"认字符串字面量"的实现：
      朴素版会在 `i.setType("image/*")` 这种**字符串里含 `/*`** 的地方误入块注释状态，
      把后面成片的真代码一起吃掉（本项目"假红/假绿"的老来源）。
    """
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"' or c == "'":
            q = c
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                if ch == '\\' and i + 1 < n:
                    out.append(src[i + 1])
                    i += 2
                    continue
                i += 1
                if ch == q:
                    break
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def raw(name):
    return read(os.path.join(SRC, name))


def body(src, header):
    """截出某个方法的正文（同文件里方法按 4 空格缩进，内层块更深）"""
    i = src.find(header)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(header))
    return src[i:j + 5] if j > 0 else src[i:]


def run(cmd, cwd=None):
    r = subprocess.run(cmd, cwd=cwd or HERE, capture_output=True)
    out = r.stdout.decode('utf-8', 'replace') + r.stderr.decode('utf-8', 'replace')
    return r.returncode, out


# ═══════════════ 一、纯类与"能真跑" ═══════════════
print('== 一、Appearance 纯类 + 离线真跑 ==')

ap = read(os.path.join(SRC, 'Appearance.java'))
check('Appearance 存在', 'public final class Appearance' in ap)
check('★ Appearance 零 Android 依赖（能进 fpstest 做真断言）',
      'import android' not in ap)
check('★ 来源 id 就是 FloatingService 的 mode 值（少一层映射就少一处漂移）',
      '"custom_l2d"' in ap and 'L2D = "custom_l2d"' in ap
      and 'MODEL = "model"' in ap and 'ANIM = "anim"' in ap
      and 'IMAGE = "image"' in ap and 'BUILTIN = "builtin"' in ap)
check('★ 只有五种来源（内置伪 Live2D 不进清单 —— 它没有素材、也从没被启用）',
      code_only(ap).count('public static final String BUILTIN') == 1
      and 'use_live2d' not in code_only(ap) and 'useLive2D' not in code_only(ap),
      '类注释里会解释这个死键，所以这里只看代码（去掉注释）')
check('判据集中：合法化 / 回退 / 关掉一个来源 / 清单行',
      all(k in ap for k in ('public static String normalize(', 'public static String resolve(',
                            'public static String afterDisable(', 'public static String rowLabel(')))

fpstest_bat = read(os.path.join(HERE, 'fpstest', 'run.bat'))
check('fpstest 编译 Appearance.java（否则纯类断言跑不到）',
      'Appearance.java' in fpstest_bat)
check('fpstest 里有 [20] 节（形象来源）',
      '[20]' in read(os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')))

rc, out = run(['cmd', '/c', os.path.join(HERE, 'fpstest', 'run.bat')])
sec20 = out[out.find('[20]'):] if '[20]' in out else ''
check('fpstest 全绿（含 [20] Appearance 判据）',
      rc == 0 and 'ALL CHECKS PASSED' in out and '[FAIL]' not in sec20,
      'rc=%d, [20] 段失败=%d' % (rc, sec20.count('[FAIL]')))

print()
print('== 二、离线行为：形象按角色落盘（真读写文件）==')
rc, out = run([sys.executable, os.path.join(HERE, 'rt_test', 'run.py'),
               '--only=appearance', '--no-cache'])
check('rt_test appearance 跑通', rc == 0 and 'ALL CHECKS PASSED' in out, 'rc=%d' % rc)
for key in ('★ 读一遍来源/素材（设置页每次刷新都会调）不建目录',
            '★ 两个角色各用各的（A=image / B=anim）',
            '★ 关掉**不是当前**的那个 ⇒ B 还在用动图（顺序无关）',
            '★ 恢复默认：不留空壳目录',
            '★ 老形象搬进当前角色的形象目录（四样都搬）',
            '★ 幂等：再迁一次什么都不做',
            '★ 已经配置过形象 ⇒ 迁移跳过（不覆盖用户现在的选择）'):
    check('行为断言在：%s' % key, key in out)
check('没有 FAIL 的 APPEAR 断言', 'APPEAR|FAIL' not in out)
check('★ 全部 APPEAR 断言都 OK', len(re.findall(r'APPEAR\|OK\|', out)) >= 25,
      'OK 条数=%d' % len(re.findall(r'APPEAR\|OK\|', out)))

# ═══════════════ 三、存储层：路径约定 + 读路径不建目录 ═══════════════
print()
print('== 三、AppearanceStore：按角色隔离的落点 ==')

st = java('AppearanceStore.java')
check('AppearanceStore 存在', 'public final class AppearanceStore' in st)
check('★ 落点 = filesDir/appearance/<净化后的角色 id>（内置角色一视同仁）',
      '"appearance"' in st and 'ActionMenuConfig.sanitize(charId)' in st)
check('★ 净化复用 ActionMenuConfig.sanitize（不另抄一份，抄了就会漂）',
      'ActionMenuConfig.sanitize(' in st)
d_in = body(st, 'static File dirIn(File filesDir, String charId) {')
check('★ dirIn **只算路径不建目录**（读/删路径上建目录会留空壳，轮次㈡ 踩过）',
      bool(d_in) and 'mkdirs' not in d_in, d_in.replace('\n', ' '))
e_in = body(st, 'static File ensureDirIn(File filesDir, String charId) {')
check('★ 建目录只有 ensureDirIn 一个入口', bool(e_in) and 'mkdirs' in e_in)
check('四种素材的落点都在角色目录里（image.png / anim.gif / model/ / l2d/）',
      all(k in st for k in ('N_IMAGE = "image.png"', 'N_ANIM = "anim.gif"',
                            'D_MODEL = "model"', 'D_L2D = "l2d"')))
check('Live2D 的可用判据 = meta.json 在（与旧的 useCustomL2D 同一口径）',
      'new File(l2dDirIn(filesDir, charId), "meta.json").isFile()' in st)
check('★ 读来源时脏值/坏 json 一律回内置（绝不返回 null）',
      'Appearance.normalize(j.optString("source", Appearance.DEFAULT))' in st
      and 'catch (Throwable ignore)' in st)
check('★ 恢复默认形象 = 删素材 + 删 json + 收掉空壳目录',
      all(k in body(st, 'static void clearIn(File filesDir, String charId) {')
          for k in ('imageIn(filesDir, charId).delete()', 'animIn(filesDir, charId).delete()',
                    'jsonIn(filesDir, charId).delete()', 'd.delete()')))
check('★ 删角色用的 deleteIn 走 dirIn（不建目录）',
      'PetModel.deleteRecursive(dirIn(filesDir, charId))' in st)
check('★ 迁移：老全局素材**搬**进角色目录（不是复制，否则下次启动又被当成有老形象）',
      'static int migrateLegacyIn(' in st and 'moveFile(' in st and 'moveDir(' in st)
check('★ 迁移幂等：已配置过就跳过 + 旧口径是内置就一样都不搬',
      'if (configuredIn(filesDir, charId)) return 0;' in st
      and 'if (Appearance.BUILTIN.equals(Appearance.normalize(legacySource))) return 0;' in st)

# ═══════════════ 四、Prefs 门面：老调用点自动变"按角色" ═══════════════
print()
print('== 四、Prefs：四个旧访问器变成"当前角色的门面" ==')

pf = java('Prefs.java')
check('★ 图片/动图/模型/Live2D 路径都指向当前角色',
      'return AppearanceStore.ensureImageCurrent(ctx);' in pf
      and 'return AppearanceStore.ensureAnimCurrent(ctx);' in pf
      and 'return AppearanceStore.ensureModelDirCurrent(ctx);' in pf
      and 'return AppearanceStore.ensureL2dDirCurrent(ctx);' in pf)
check('★ petMode = 当前角色的来源（不再是"读四个全局开关"）',
      'String mode = AppearanceStore.modeFor(ctx);' in pf)
check('★ useCustomImage/isCustomAnim/useCustomModel/useCustomL2D 都按来源判',
      'Appearance.IMAGE.equals(AppearanceStore.modeFor(ctx))' in pf
      and 'Appearance.ANIM.equals(AppearanceStore.modeFor(ctx))' in pf
      and 'Appearance.MODEL.equals(AppearanceStore.modeFor(ctx))' in pf
      and 'Appearance.L2D.equals(AppearanceStore.modeFor(ctx))' in pf)
check('★ 开关 setter 走"切到这个来源 / 关掉这个来源"（不再各写一个全局 pref）',
      'if (use) AppearanceStore.setSourceFor(ctx, Appearance.IMAGE);' in pf
      and 'else AppearanceStore.disableFor(ctx, Appearance.IMAGE);' in pf
      and 'AppearanceStore.setSourceFor(ctx, Appearance.L2D);' in pf)
check('★ 关掉一个来源 ≠ 关掉全部（判据在 Appearance.afterDisable）',
      'Appearance.afterDisable(' in java('AppearanceStore.java'))
check('hasCustomPet = 当前角色在用自定义形象（内置不算）',
      'Appearance.isCustom(AppearanceStore.modeFor(ctx))' in pf)
check('★ 旧口径只留给迁移用（legacyPetMode 读那四个全局开关）',
      'public static String legacyPetMode(Context ctx) {' in pf
      and 'KEY_USE_CUSTOM_L2D, false' in pf)
check('★ 迁移入口：搬完清掉四个旧开关（它们已不再被读取）',
      'public static int migrateLegacyAppearance(Context ctx) {' in pf
      and '.putBoolean(KEY_USE_CUSTOM, false)' in pf)
check('★ useLive2D 仍是遗留死键（assets/live2d 不存在，保留读取但不当来源）',
      '遗留死键' in read(os.path.join(SRC, 'Prefs.java')))

# ═══════════════ 五、运行期：悬浮窗按当前角色装配 ═══════════════
print()
print('== 五、运行期：悬浮窗取的是当前角色的形象 ==')

fs = java('FloatingService.java')
# ★ 只能在 onCreate 里比先后：全文件里 `buildViews();` 的第一个匹配是
#   `rebuildViews();` 的**子串**（差一个 re），直接 find 会得到"迁移在它后面"的假红。
oncreate = body(fs, 'public void onCreate() {')
mig = oncreate.find('Prefs.migrateLegacyAppearance(this)')
bv = oncreate.find('buildViews();')
check('★ 迁移在 buildViews() **之前**（否则会先用"没有形象"渲染一次）',
      mig >= 0 and bv >= 0 and mig < bv, 'migrate=%d buildViews=%d' % (mig, bv))
check('★ 装配日志带上角色 id（设备上能核对"到底用的谁的形象"）',
      '"applyPetDrawable char=" + CharacterStore.currentId(this)' in fs)
check('装配仍按 Prefs.petMode（五种来源的分支都在）',
      all(k in fs for k in ('"custom_l2d".equals(mode)', '"model".equals(mode)',
                            '"anim".equals(mode)', '"image".equals(mode)',
                            '"builtin".equals(mode)')))
check('★ 装配取的是 Prefs 的门面（于是自动按角色，不需要 13 处各写一遍）',
      'Prefs.customL2DDir(this)' in fs and 'Prefs.customModelDir(this)' in fs
      and 'Prefs.customAnimFile(this)' in fs and 'Prefs.customImageFile(this)' in fs)

# ═══════════════ 六、形象入口：收进「角色」（轮次㉛）═══════════════
print()
print('== 六、形象入口收进「角色」（设置页上不再有独立的形象行）==')

ma = java('MainActivity.java')
ma_raw = raw('MainActivity.java')
check('★ 设置页不再有独立的形象行（用户要求"删除所有形象导入接口"）',
      'Appearance.sourceRowLabel(' not in ma)
check('★ 也不再有那五行独立入口（上传图片 / 导入模型 / 导入 Live2D / 启用 L2D / 恢复默认形象）',
      all(('addButton(box, "%s' % k) not in ma_raw and ('addToggle(box, "%s' % k) not in ma_raw
          for k in ('\uD83D\uDCF7 上传图片', '\U0001F9F1 导入桌宠模型',
                    '\U0001F9E9 导入 Live2D', '\U0001F9E9 使用已导入的 Live2D',
                    '\U0001F5BC\uFE0F 恢复默认形象'))
      and 'l2dRowRef' not in ma)
check('★ 唯一的入口是「🎭 角色」那一行',
      'CharPicker.currentRowLabel(' in ma and 'showCharacterHub()' in ma)

picker = body(ma, 'private void showAppearancePicker() {')
check('showAppearancePicker 存在', bool(picker))
check('清单来自 Appearance.ALL（五种来源，不写死）', 'Appearance.ALL' in picker)
check('每行用 Appearance.rowLabel（● 当前 / ○ 其他 / 未导入标出来）',
      'Appearance.rowLabel(' in picker)
check('标题报出当前角色', '形象（角色：' in picker)
check('★ 四个导入动作收进了清单（上传图片 / 序列帧包 / Live2D / 恢复默认）',
      all(k in picker for k in ('pickImage()', 'showImportModelDialog()',
                                'showImportL2DDialog()', 'confirmResetAppearance()'))
      # ★ 还要钉住**用户看到的那几行文案**：只钉函数调用的话，
      #   把清单里那几行删掉（调用还在）断言照样绿 —— 反向套件 p 就是抓这个的。
      and all(k in picker for k in ('上传图片 / GIF 动图…', '导入序列帧模型包',
                                    '导入 Live2D 模型', '恢复默认形象')))
check('★ 没有角色时明确拒绝（先新增/导入一个角色）',
      'ch.dir == null' in picker and '先新增或导入一个角色' in picker)
check('★ 恢复默认不再是 neutral（动作全走 setItems，同一位置只有一个按钮）',
      'setNeutralButton' not in picker and 'setNegativeButton("关闭"' in picker)

pick = body(ma, 'private void pickAppearance(String src) {')
check('pickAppearance 存在', bool(pick))
check('★ 没素材只提示、**不动**当前设置', 'Appearance.missingHint(src)' in pick
      and 'return;' in pick)
check('★ 选中后写盘（按角色）', 'AppearanceStore.setSourceFor(this, src)' in pick)
check('★ 让悬浮窗重载、且**不整页重建**（整页重建 = 用户看到"页面重新加载"）',
      'Prefs.reloadPet(this)' in pick and 'showSettings()' not in pick)
check('打日志便于设备核对（DSPAppear: 形象来源 ->）', '"DSPAppear", "形象来源 -> "' in ma_raw)

rst = body(ma, 'private void confirmResetAppearance() {')
check('confirmResetAppearance 存在', bool(rst))
check('★ 只清**当前角色**（AppearanceStore.clearFor(ch.id)）',
      'AppearanceStore.clearFor(MainActivity.this, ch.id)' in rst)
check('★ 确认文案来自 Appearance.resetConfirmMessage（写清只影响这个角色）',
      'Appearance.resetConfirmMessage(' in rst)
check('★ 旧的「恢复默认角色」按钮已删（它会连带删掉全局文件）',
      '🖼️ 恢复默认角色' not in ma_raw)
check('★ 旧的"清全局素材"整段已删（PetModel.deleteRecursive(Prefs.customModelDir(...))）',
      'PetModel.deleteRecursive(Prefs.customModelDir(MainActivity.this))' not in ma)
check('★ Live2D 可用判据仍是不建目录的那一个（AppearanceStore.hasL2d）',
      'AppearanceStore.hasL2d(this)' in ma
      and 'new java.io.File(Prefs.customL2DDir(MainActivity.this), "meta.json")' not in ma)

# ★ 轮次㉛：旧的"刷那一行"整套已随设置页那一行一起删掉 —— 现在清单每次打开都重新读
check('★ 旧的"就地刷那一行"整套已删（appearanceRowRef / refreshAppearanceRow）',
      'appearanceRowRef' not in ma and 'refreshAppearanceRow' not in ma)
check('★ actionRow 也挂 TAG_LABEL（否则按钮行没法就地改文案）',
      'row.setTag(TAG_LABEL, tv);' in java('UiFactory.java'))
check('addButton 返回这一行（调用方能拿到引用）',
      'private View addButton(LinearLayout parent, String text, View.OnClickListener l) {' in ma)

# ═══════════════ 七、删角色连带清形象 ═══════════════
print()
print('== 七、删角色：连这个角色的形象一起清 ==')

cs = java('CharacterStore.java')
dele = body(cs, 'public static boolean delete(Context ctx, String id) {')
check('★ CharacterStore.delete 里清 appearance/<id>/',
      'AppearanceStore.deleteFor(ctx, id)' in dele)
check('（内置角色照样拒绝，见 CharPicker.deletable）', 'CharPicker.deletable(id)' in dele)

# ═══════════════ 八、产物：新代码真的进了 dev 包 ═══════════════
print()
print('== 八、产物：新代码真的进了 dev 包 ==')
check('dev APK 存在', os.path.isfile(DEV_APK))
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('Appearance', 'AppearanceStore', 'DSPAppear', '形象来源：',
              'appearance.json', '还没有给这个角色传过图片'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

# ═══════════════ 九、设备脚本（可复跑，且只认模拟器）═══════════════
print()
print('== 九、设备脚本（可复跑，且只认模拟器）==')
ui = os.path.join(HERE, 'verify_appearance_ui.py')
check('verify_appearance_ui.py 存在', os.path.isfile(ui))
if os.path.isfile(ui):
    s = read(ui)
    check('★ 钉死 emulator-5554（绝不往用户真机装包）',
          "DEV = V.DEV" in s and 'import verify_char_ui as V' in s)
    check('★ 覆盖升级路径（老全局形象迁移）与按角色隔离',
          '迁移老形象 -> char=' in s and 'image.png' in s
          and 'import verify_char_switch_ui as S' in s)
    # ★ 轮次㉞：可靠改 pref 的**唯一实现**搬到了 `verify_char_switch_ui.S.pref_set`
    #   （原来两个设备脚本各写一份）⇒ 判据跟着改：本脚本只能**复用**，手法本身在那边钉。
    check('★ 改 pref 一律走 S.pref_set（不再各写一份）',
          'S.pref_set(' in s and 'pf.setval' not in s)
    sw = os.path.join(HERE, 'verify_char_switch_ui.py')
    sws = read(sw) if os.path.isfile(sw) else ''
    check('★ 可靠手法本身：force-stop → 改 XML → 还原 owner/SELinux → 复读校验',
          all(k in sws for k in ("'force-stop'", 'pf.setval', "'chown'", "'stat'",
                                 "'restorecon'", 'pf.get(')))
    # ★ 轮次㉞ 的新教训：对话框背后就是设置页，而设置页上有同名文字
    #   （空态引导语里带「新增角色」、那一行带角色名）⇒ 必须按"相对打开前的**差集**"点。
    check('★ 对话框一律用「差集」判据点（防点到背景设置页）',
          'def tap_new_item(' in sws and 'S.tap_new_item(' in s)
    rc, out = run([sys.executable, ui, '--selftest'])
    check('verify_appearance_ui --selftest 通过',
          rc == 0 and 'SELFTEST PASSED' in out, 'rc=%d' % rc)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（P5：形象来源纳入角色模型）')
