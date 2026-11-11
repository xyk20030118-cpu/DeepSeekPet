# -*- coding: utf-8 -*-
"""P3 断言：角色切换 UI（待办 0h 的 P3）。

P0～㉑ 把"角色 = 形象＋动作＋台词＋表情＋性格（＋各自的绑定）"这一层做出来了，
但设置页那一行一直是**只读标签** —— 用户看得到当前角色，却**换不了**。P3 给它装入口：

  ① 那一行变成可点行（报告角色个数），点开是角色清单：`● 当前` / `○ 其他` / 内置标出来；
  ② 切角色 = 写 pref + `reloadPet`（运行期整套换掉）+ 刷新设置页；
  ③ 删角色 = 确认框 → 连**这个角色派生的用户数据**一起清（覆盖层 json / 用户素材 / 自定义菜单），
     而且"删的正好是当前角色"时**必须回内置**（否则 pref 指向不存在的目录）；
  ④ 内置角色**永远不能删**（它的素材在 APK assets 里，删了就没有兜底形象）。

★ 两处是"真跑"而不是 grep：
  * `build/fpstest` 第 [19] 节：`CharPicker` 是纯类，编的是打进 APK 的那一份，逐条验它的判据；
  * `build/rt_test --only=bindings`：删角色的"清素材"那一步在桌面 JVM 上真读写文件 ——
    **它抓出过一个真 bug**：删除路径调了会 `mkdirs()` 的 `clipsDirFor`，
    于是删完角色还在 `files/scenes/` 下留一个空的 `<id>/` 壳。

反向破坏见 reverse_check_character_switch.py。
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

    ★ 与 verify_action_import 用的是同一份"认字符串字面量"的实现：
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
print('== 一、CharPicker 纯类 + 离线真跑 ==')

cp = read(os.path.join(SRC, 'CharPicker.java'))
check('CharPicker 存在', 'public final class CharPicker' in cp)
check('★ CharPicker 零 Android 依赖（能进 fpstest 做真断言）',
      'import android' not in cp)
check('内置角色的"不可删"只有一处判据（deletable）',
      'PetCharacter.BUILTIN_ID.equals(id)' in cp)

fpstest_bat = read(os.path.join(HERE, 'fpstest', 'run.bat'))
check('fpstest 编译 CharPicker.java（否则纯类断言跑不到）',
      'CharPicker.java' in fpstest_bat and 'PetCharacter.java' in fpstest_bat)
check('fpstest 里有 [19] 节（角色切换/删除）',
      '[19]' in read(os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')))

rc, out = run(['cmd', '/c', os.path.join(HERE, 'fpstest', 'run.bat')])
sec19 = out[out.find('[19]'):] if '[19]' in out else ''
check('fpstest 全绿（含 [19] CharPicker 判据）',
      rc == 0 and 'ALL CHECKS PASSED' in out and 'ALL CHECKS PASSED' in out
      and '[FAIL]' not in sec19,
      'rc=%d, [19] 段失败=%d' % (rc, sec19.count('[FAIL]')))

print()
print('== 二、离线行为：删角色时"清素材"这一步 ==')
rc, out = run([sys.executable, os.path.join(HERE, 'rt_test', 'run.py'), '--only=bindings'])
check('rt_test bindings 跑通', rc == 0 and 'ALL CHECKS PASSED' in out)
for key in ('用户素材 clips/ 被清掉', '连空容器目录', '不会凭空造目录'):
    check('行为断言在：%s' % key, key in out)
check('★ 三条删除行为断言都 OK（不留空壳 / 不凭空造目录）',
      bool(re.search(r'BIND\|OK\|[^\n]*连空容器目录', out))
      and bool(re.search(r'BIND\|OK\|[^\n]*不会凭空造目录', out))
      and bool(re.search(r'BIND\|OK\|[^\n]*用户素材 clips/ 被清掉', out)))
check('没有 FAIL 的 BIND 断言', 'BIND|FAIL' not in out)

# ═══════════════ 三、设置页入口 ═══════════════
print()
print('== 三、设置页那一行：只读标签 → 可点行 ==')

ma = java('MainActivity.java')
check('★ 有可点行（addButton + CharPicker.currentRowLabel）',
      'addButton(box, "\\uD83C\\uDFAD " + CharPicker.currentRowLabel(' in ma)
check('★ 旧的"只读标签"已删（别再退回 addLabel）',
      'addLabel(box, "当前角色：" + curChar.displayName()' not in ma)
check('个数来自 CharacterStore.list（不是写死）',
      'CharacterStore.list(this).size()' in ma)
check('行里报出角色个数（共 N 个）', 'CharPicker.currentRowLabel(' in ma)
check('提示写清"每个角色自己的一整套"',
      '每个角色有自己的一整套动作、形象、台词、表情、性格与场景绑定' in ma)
check('提示写清"记忆仍是全局的"（用户 2026-10-07 的定案）',
      '记忆与 API Key 是全局的' in ma)
check('★ 一个角色都没有时给引导（不是一句"共 0 个"）',
      'CharPicker.emptyHint()' in ma)

print()
print('== 四、角色中心对话框（轮次㉛：全工程唯一的角色入口）==')

picker = body(ma, 'private void showCharacterHub()')
check('showCharacterHub 存在（旧的 showCharacterPicker 已退场）',
      bool(picker) and 'showCharacterPicker' not in ma)
check('清单来自 CharacterStore.list', 'CharacterStore.list(this)' in picker)
check('每行用 CharPicker.rowLabel（● 当前 / ○ 其他）',
      'CharPicker.rowLabel(' in picker)
check('标题报出个数（角色（共 N 个））', '角色（共 ' in picker)
check('★ 四个入口都在这一张清单里（新增 / 添加角色包 / 从电脑导入 / 删除）',
      all(k in picker for k in ('promptNewCharacter()', 'pickCharacterPack()',
                                'importFromComputer()', 'promptDeleteCharacter()'))
      # ★ 用户看到的是**清单里的那一行文案**：只钉住函数调用的话，
      #   把那一行删掉（调用还在别处）断言照样绿 —— 反向套件 n2 就是抓这个的。
      and '添加角色包（.zip）…' in picker and '新增角色…' in picker)
check('★ 形象与导出也收进来了（设置页上不再有第二处）',
      'showAppearancePicker()' in picker and 'exportCurrentCharacter()' in picker)
check('★ 一个角色都没有时只列前三项（形象/导出/删除没有对象，列出来只会点了报错）',
      'final boolean has = !list.isEmpty();' in picker and 'if (has) {' in picker)
check('★ 动作全走 setItems 分流（AlertDialog 同一位置只能有一个按钮）',
      'setNeutralButton' not in picker)

print()
print('== 四之二、新增角色（轮次㉛：可以新增角色）==')

nc = body(ma, 'private void promptNewCharacter()')
check('promptNewCharacter 存在', bool(nc))
check('★ 名字先校验（空 / 重名 / 太长都拦下来，否则用户以为点了没反应）',
      'CharPicker.nameError(' in nc)
check('★ 立刻落盘（CharacterStore.create）—— 用户选了"不用单独保存"',
      'CharacterStore.create(' in nc)
check('★ 建完让悬浮窗重载 + 设置页刷新（新角色当场生效）',
      'Prefs.reloadPet(' in nc and 'showSettings()' in nc)
check('★ 建完告诉用户下一步（加动作 / 设形象）', 'CharPicker.createdHint(' in nc)
check('建不出来时明确报错（不静默）', '创建失败' in nc)

print()
print('== 五、切角色真的生效 ==')

sw = body(ma, 'private void switchCharacter(final String id)')
check('switchCharacter 存在', bool(sw))
check('★ 写 pref（CharacterStore.setCurrentId）', 'CharacterStore.setCurrentId(' in sw)
check('★ 让悬浮窗整套重载（Prefs.reloadPet）', 'Prefs.reloadPet(' in sw)
check('★ 设置页跟着刷新（动作系统整段跟角色变）', 'showSettings()' in sw)
check('★ 点自己不算切换（CharPicker.switchNeeded，省一次重建）',
      'CharPicker.switchNeeded(' in sw)
check('打日志便于设备核对（DSPChar: 切换角色 ->）',
      '"DSPChar", "切换角色 -> "' in read(os.path.join(SRC, 'MainActivity.java')))

print()
print('== 六、删角色 ==')

prompt = body(ma, 'private void promptDeleteCharacter()')
check('promptDeleteCharacter 存在', bool(prompt))
check('★ 只列可删的角色（哨兵"没有角色"不进列表）', 'CharPicker.deletable(' in prompt)
check('删除行用 CharPicker.deleteRowLabel（轮次㉛ 起没有锁图标了：没有删不掉的角色）',
      'CharPicker.deleteRowLabel(' in prompt)
conf = body(ma, 'private void confirmDeleteCharacter(final PetCharacter ch)')
check('删除前有确认框', 'setPositiveButton("删除"' in conf)
check('确认文案来自 CharPicker.deleteConfirmMessage（写清删掉什么）',
      'CharPicker.deleteConfirmMessage(' in conf)
check('★ 删的正好是当前角色 ⇒ 额外 reloadPet（否则桌宠还停在已删角色的素材上）',
      'wasCurrent' in conf and conf.count('Prefs.reloadPet(') >= 1)

cs = java('CharacterStore.java')
dele = body(cs, 'public static boolean delete(Context ctx, String id) {')
check('CharacterStore.delete 存在', bool(dele))
check('★ 哨兵一律拒绝（CharPicker.deletable）', 'CharPicker.deletable(id)' in dele)
check('删该角色的场景覆盖层 json', 'SceneBindings.fileFor(ctx, id).delete()' in dele)
check('★ 用 SceneBindings.deleteUserClips（**不是**会 mkdirs 的 clipsDirFor）',
      'SceneBindings.deleteUserClips(ctx, id)' in dele
      and 'clipsDirFor' not in dele,
      '出现 clipsDirFor 就会留下空壳目录（实测过的 bug）')
check('删该角色的自定义动作菜单', 'ActionMenuConfig.deleteFor(ctx, id)' in dele)
check('★ 当前角色落到剩下的第一个（CharPicker.currentAfterDelete 的 3 参版本）',
      'CharPicker.currentAfterDelete(currentId(ctx), id, listIds(ctx))' in dele)
check('删除有日志（DSPChar: 删除角色 id=…）', '删除角色 id=' in dele)

print()
print('== 六之二、不再预置任何角色（轮次㉛：用户要求「不留任何角色，只靠导入」）==')
check('★ list 里不再塞内置角色',
      'out.add(builtin(ctx))' not in cs and 'public static List<PetCharacter> list(Context ctx)' in cs)
check('★ 没有角色时 current() 给一个"没有目录的合成壳"（不是 null、也不是内置角色）',
      'public static PetCharacter none()' in cs and 'return none();' in cs)
check('★ 素材层对"没有目录"仍是内置兜底（所以桌宠不会变成空白）',
      'public File dirOf() { return dir; }' in java('PetCharacter.java'))
check('★ 新增角色真的会建目录 + 写 character.json',
      'public static PetCharacter create(Context ctx, String name)' in cs
      and 'PetCharacter.F_CHARACTER' in body(cs, 'public static PetCharacter create('))
check('★ 写不出 character.json 就删掉半拉目录（不留"名字是 id、内容全空"的鬼角色）',
      'PetModel.deleteRecursive(dir);' in body(cs, 'public static PetCharacter create('))
check('角色 id 净化只有一份实现（CharPicker.sanitizeId，ActionMenuConfig 转发）',
      'public static String sanitizeId(String s)' in java('CharPicker.java')
      and 'return CharPicker.sanitizeId(s);' in java('ActionMenuConfig.java'))

sb = java('SceneBindings.java')
check('SceneBindings.clipsDirNoCreate 存在（删除路径专用的不创建版本）',
      'static File clipsDirNoCreate(' in sb)
duc = body(sb, 'static void deleteUserClipsIn(File filesDir, String charId) {')
check('deleteUserClipsIn 用 clipsDirNoCreate（不能再用 clipsDirIn）',
      'clipsDirNoCreate(' in duc and 'clipsDirIn(' not in duc)
check('deleteUserClipsIn 连空容器目录一起删', 'holder.delete()' in duc)

print()
print('== 七、按角色隔离：运行期每个消费方都看 currentId ==')
check('素材层按当前角色取（PetView.setAnimPack → CharacterStore.currentId）',
      'CharacterStore.currentId(' in java('PetView.java'))
check('场景编辑页按当前角色装配（loadForDisplay → CharacterStore.current）',
      'CharacterStore.current(' in java('ActionSystem.java'))
check('★ 表情按角色（Memes 的缓存跟着角色走：cacheChar）',
      'cacheChar' in java('Memes.java'))
check('覆盖层按角色取文件（SceneBindings.fileFor/charId）',
      'static File fileFor(Context ctx, String charId)' in sb)
check('自定义菜单按角色取文件（ActionMenuConfig.fileFor/charId）',
      'sanitize(charId)' in java('ActionMenuConfig.java'))

print()
print('== 八、设备脚本（可复跑，且只认模拟器）==')
for f in ('verify_char_ui.py', 'verify_char_switch_ui.py'):
    p = os.path.join(HERE, f)
    check('%s 存在' % f, os.path.isfile(p))
    if not os.path.isfile(p):
        continue
    s = read(p)
    if f == 'verify_char_ui.py':
        check('%s：钉死 emulator-5554（绝不往用户真机装包）' % f,
              "DEV = 'emulator-5554'" in s)
    else:
        check('%s：复用 verify_char_ui 的 DEV（同一台模拟器）' % f,
              'DEV = V.DEV' in s and 'import verify_char_ui' in s)
        # ★ 轮次㉞：改 pref 的临时副本**不许落在 `build/` 里** —— 那份 XML 是设备 pref 的
        #   **整份拷贝**（含用户的 API/搜索 Key），而项目移植包会把整个 `build/` 打进去
        #   （轮次㉘ 在 `build/_prefs.xml` 中过一次；轮次㉞ 本脚本的 `pref_set` 第一版
        #   又写出 `build/_uitest/_p3prefs.xml`）⇒ 现在必须走 `tempfile.mkstemp` 并即用即删。
        check('%s：pref 临时副本写系统临时目录（不落 build/，免得 Key 进源码包）' % f,
              'mkstemp' in s)
    rc, out = run([sys.executable, p, '--selftest'])
    check('%s --selftest 通过' % f, rc == 0 and 'SELFTEST PASSED' in out, 'rc=%d' % rc)

print()
print('== 九、产物：新代码真的进了 dev 包 ==')
check('dev APK 存在', os.path.isfile(DEV_APK))
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
    for s in ('CharPicker', '角色（共 ', '新增角色', '添加角色包', '删除角色 id=',
              'currentAfterDelete', 'sanitizeId', 'deleteUserClips', 'current_character'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（P3：角色切换 UI）')
