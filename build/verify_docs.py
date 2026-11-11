# -*- coding: utf-8 -*-
"""交付文档与事实一致（轮次59）。

用户原话：「**继续**」⇒ 接手时按 `STATE.md` 挂着的欠账往下做。这份欠账我连挂了三轮：
**`交付说明.md` / `README.md` 里一个字都没提**自定义 API、自定义配色/透明度/Mesh 光斑、
点击音效 —— 而且里面的版本号（还写着 v7.0）、工程目录名（`v6.5_完整工程`）、
引用的 dex md5（`5425520f…`，实际早已是另一串）**全都过期了**。

## 为什么必须有个脚本盯着

本工程被"文档撒谎"咬过不止一次（`verify_features` 的假探针、`verify_zips` 抄死的 md5、
`AGENT_ONBOARDING.md` 里过期的版本号与体积）。规律是一样的：**手抄的事实一定会漂**。
所以这里把"文档里那些必须跟着工程走的数字/名字"变成判据 —— 漂了就红，红了当场改。

## 分节

  一、交付文档齐全且非空（五件套缺一件，交付包就少一份文档）
  二、★ 版本号一致：文档头部 == `AndroidManifest.xml`（versionName + versionCode）
  三、★ 产物名不许停在旧版本（`*_v6.5_完整工程` / `*_v7.0_rebuilt.apk` 这类）
  四、★★ 新功能必须写进交付文档（做了功能却没写 = 用户永远不知道）
  五、★ dex 指纹：README 引用的短 md5 == 现场读到的（防"古董 md5"）
  六、接手入口唯一：README 指向 `STATE.md`，且两份旧接手文档**不得存在**（防悬空引用）
  七、编码：每个 .md 都能按 UTF-8 解开（★ 不断言行尾 —— 源码才是 LF 约定，文档本来就是混的）
"""
import io
import os
import re
import sys
import hashlib
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import artifacts as A  # noqa: E402

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def doc(name):
    return read(os.path.join(ROOT, name))


MAN = doc('AndroidManifest.xml')
VER = A.manifest_version(ROOT)                     # 例如 "7.5"
CODE = re.search(r'android:versionCode="(\d+)"', MAN).group(1)
README = doc('README.md')
DELIVER = doc('交付说明.md')
BUILD = doc('BUILD_双版本.md')

# 交付包里会打进去的五件文档（与 pack_delivery.py 的 DOCS 同口径）
FIVE = ['交付说明.md', '第三方许可声明.md', 'README.md', 'BUILD_双版本.md', '角色包格式.md']

print('=== 一、交付文档齐全且非空 ===')
for f in FIVE:
    p = os.path.join(ROOT, f)
    check('%s 在且非空' % f, os.path.isfile(p) and os.path.getsize(p) > 500,
          '%d 字节' % (os.path.getsize(p) if os.path.isfile(p) else 0))
check('电脑端开发指南.md 在（README 指向它）', os.path.isfile(os.path.join(ROOT, '电脑端开发指南.md')))

print()
print('=== 二、★ 版本号一致（文档头部 == AndroidManifest）===')
check('★★ README 标题带当前版本与 versionCode（v%s / %s）' % (VER, CODE),
      ('v%s / versionCode %s）' % (VER, CODE)) in README
      or ('（v%s / versionCode %s）' % (VER, CODE)) in README,
      [l for l in README.splitlines()[:3]])
check('★★ README 头部两行写死 versionName/versionCode（%s / %s）' % (VER, CODE),
      ('versionName **%s**' % VER) in README and ('versionCode **%s**' % CODE) in README)
check('★★ 交付说明 头部版本与 versionCode 一致（v%s（versionCode %s））' % (VER, CODE),
      ('**版本**：v%s（versionCode %s）' % (VER, CODE)) in DELIVER,
      [l for l in DELIVER.splitlines()[:4]])
check('★ 版本历史表里有当前版本这一行（v%s）' % VER,
      re.search(r'^\| v%s \|' % re.escape(VER), BUILD, re.M) is not None)

print()
print('=== 三、★ 产物名不许停在旧版本 ===')
# 这些是"当前产物"的名字：写旧了会让用户照着找不到文件
STALE = ['DeepSeekPet_v6.5_完整工程', 'DeepSeekPet_v7.0_完整工程',
         'DeepSeekPet_v7.0_rebuilt.apk', 'DeepSeekPet_v7.0_release.apk',
         'DeepSeekPet_默认角色包_v7.0.zip']
bad = []
for f in ('README.md', '交付说明.md', 'BUILD_双版本.md'):
    s = doc(f)
    for t in STALE:
        if t in s:
            bad.append('%s: %s' % (f, t))
check('★ 三份文档里都没有旧版产物名（%d 个模式）' % len(STALE), not bad, bad)
check('★ 当前产物名至少在交付说明里出现一次（v%s）' % VER,
      ('DeepSeekPet_默认角色包_v%s.zip' % VER) in DELIVER
      and ('DeepSeekPet_v%s_rebuilt.apk' % VER) in (README + DELIVER + BUILD))

print()
print('=== 四、★★ 新功能必须写进交付文档 ===')
# 轮次㊾~57 加的用户可见功能：做了却没写 = 用户永远不知道（这份欠账连挂了三轮）
FEATS = ['点击音效', '自定义界面颜色', 'UI 透明度', 'Mesh 光斑', '配色体检',
         '测试连接', '自定义（OpenAI',
         # ★ 2026-10-15 轮次64：本地 GGUF 模型（引擎 + 导入 + **对话接线**）。
         #   上一轮（轮次62 收尾）把这条留给了"收尾轮" —— 本轮补上，不留"功能做了文档没写"的欠账。
         #   四个词分别在两份文档里都有（README / 交付说明），且都是**用户能看懂的那个层面**：
         #   「本地模型」= 功能名，「GGUF / llama.cpp」= 要用户自己准备什么模型、底层是什么，
         #   「不联网」= 用户最关心的那个性质。
         '本地模型', 'GGUF', 'llama.cpp', '不联网']
for f, s in (('README.md', README), ('交付说明.md', DELIVER)):
    miss = [k for k in FEATS if k not in s]
    check('★★ %s 覆盖了全部 %d 项新功能' % (f, len(FEATS)), not miss, miss)

print()
print('=== 四½、★★★ 轮次65（收尾）的交付物：真机验清单 + 诚实边界 ===')
# ★★ 轮次65 收尾：这个功能**一次都没在真机上跑过**（APK 只有 arm64-v8a，
#    本机模拟器 pet411 是 x86_64）⇒ "离线断言全绿"**不等于**"能用"。
#    所以本轮的交付物里有两样是**不能丢**的：
#      ① **真机验清单**（21 条，用户照着做就是这个功能唯一的验收方式）；
#      ② **诚实边界**段（★ 绝不许把"没验过"含糊成"验过"）。
#    ★ STATE.md 是**不进交付包**的（pack_delivery.py 的 SKIP_FILES_ROOT），
#      所以清单在两处各有一份 —— 这里把**同一批锚点**在两处都查一遍，
#      防的是"改一处忘了另一处"（同一份内容抄两遍必然会漂，这是本工程的老毛病）。
STATE = doc('STATE.md')
ANCHORS = ['21 条',
           'adb logcat -s DSPModel DSPLlm DSPLocalChat DSPChatRoute DSVoice',
           '这不是 GGUF 模型文件',
           'DSPLocalChat 本地模型已载入并常驻',
           'DSPChatRoute 本地后端没能出话',
           '本机推理不联网、不需要 API Key']
check('★★★ 交付说明里有「本地模型真机验清单」（本功能唯一的验收方式；丢了用户没法验）',
      '真机验清单」（21 条' in DELIVER and 'DSPLocalChat' in DELIVER)
check('★★★ 交付说明里有「诚实边界」段（★ 不许把"没验过"含糊成"验过"）',
      '诚实边界' in DELIVER
      and '一次都没有在真机上跑过' in DELIVER
      and '没有"测试连接 / 自检"入口' in DELIVER
      and '不读 GGUF 里的' in DELIVER
      and '本地对话不计用量' in DELIVER)
check('★★ 两份清单不许漂（STATE.md 顶部 vs 交付说明「七、5」—— 改一处必须改另一处）',
      all(a in DELIVER and a in STATE for a in ANCHORS),
      [a for a in ANCHORS if not (a in DELIVER and a in STATE)])

print()
print('=== 五、★ dex 指纹不许是古董 ===')


def dex8(apk):
    try:
        with zipfile.ZipFile(apk) as z:
            return hashlib.md5(z.read('classes.dex')).hexdigest()[:8]
    except Exception:
        return ''


dev8, rel8 = dex8(A.dev_apk()), dex8(A.rel_apk())
check('★ 两个 APK 都读得到 dex 指纹', len(dev8) == 8 and len(rel8) == 8, '%s / %s' % (dev8, rel8))
quoted = re.findall(r'`([0-9a-f]{8})…`', README)
check('★★ README 引用的短 md5 就是现场这两个（dev %s / rel %s）' % (dev8, rel8),
      dev8 in quoted and rel8 in quoted, quoted)
# ★★★ 轮次71 补硬的一条判据（反向项 F 抓出来的假绿）：
#   上面那条只要求"这两个 8 位 md5 **在 README 里出现过**" —— 而 README 里
#   **每轮都会新写一段流水账**，那段流水账又会把当前 md5 再写一遍
#   ⇒ 反向项把"当前值那一行"换成古董值之后，流水账那处**照样满足**上面那条 ⇒ 假绿。
#   ⇒ 这里再钉一条：**那一行必须逐字以 `dev \`X…\` / rel \`Y…\`` 的形式存在**
#     （也就是"唯一那个声明当前指纹的地方"，不是随便哪儿提一句）。
check('★★★ README 里那**一行**（dev `%s…` / rel `%s…`）逐字存在 —— '
      '不许靠别处顺手提一句来满足上面那条（那就是假绿）' % (dev8, rel8),
      ('dev `%s…` / rel `%s…`' % (dev8, rel8)) in README)
check('★ README 说明了"每轮换包都会变、以现场读数为准"（否则下个人会以为是常量）',
      '每轮换包都会变' in README)

print()
print('=== 六、接手入口唯一 ===')
check('★★ README 明确指向 STATE.md 作为唯一接手文档',
      'STATE.md' in README and '唯一的接手文档' in README)
check('★★ 两份旧接手文档**不得存在**（留着就是悬空引用）',
      not os.path.isfile(os.path.join(ROOT, 'AGENT_ONBOARDING.md'))
      and not os.path.isfile(os.path.join(ROOT, 'SESSION_HANDOFF.md')))
check('★ README 的文档索引里给的是 STATE.md 那一行（不是旧文档）',
      re.search(r'^\| `STATE\.md` \|', README, re.M) is not None)

print()
print('=== 七、编码：每个 .md 都能按 UTF-8 解开 ===')
docs = sorted(f for f in os.listdir(ROOT) if f.endswith('.md'))
broken = []
for f in docs:
    try:
        io.open(os.path.join(ROOT, f), encoding='utf-8').read()
    except Exception as e:
        broken.append('%s: %s' % (f, e))
check('★ %d 份 .md 全部 UTF-8 可解（★ 不断言行尾：源码才是 LF 约定，文档本来就是混的）'
      % len(docs), not broken, broken)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（交付文档与事实一致：版本 / 产物名 / 新功能 / 指纹 / 接手入口 / 编码）')
