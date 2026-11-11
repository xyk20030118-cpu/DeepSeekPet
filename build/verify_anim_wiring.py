# -*- coding: utf-8 -*-
"""动画接线：106 个动画全部"可播"—— 素材三方一致 + 四个触发出口真的接上。

用户 2026-10-07 追问「怎么我数了只有88个动作」暴露的问题：
  APK 里有 106 个 animated WebP，但 `PetAnimPack.buildAllClips()` 只把
  idle 1 + turn 1 + drag 1 + clicks 5 + categories 80 = **88** 装进播放池；
  另外 18 个（events 15 + moves.actions 3）**在包里但程序永远不播**：
    · `pet_anim.json` 的 `events` 节点**全工程没人读**（load() 里没有
      optJSONObject("events")，pickEvent() 只有定义、零调用点）；
    · `moves.actions` 的写法是 {"name":"螃蟹走路"}，而加载器按
      {"id":…, "actions":[…]} 读 ⇒ optJSONArray("actions")=null ⇒ 空组。

本轮把加载器和四个出口接上（余额档位 / 主动搭话碎碎念 / 工作场景 / 走路），
并把 18 个并入"全部可播"名单（动作菜单与设置页选择器都能选到 106 个）。

判据钉**语义**：
  · 事件动画**不得进轮播池**（否则余额动画会在待机随机乱蹦）；
  · 余额 6 档的名字必须**与素材里的 events.balance 完全一致**
    （档位表写在代码里，最容易和素材各自漂移）；
  · 每个 webp 都必须能被某个组引用到（新增动画忘登记 = 静默不播）。

反向破坏见 reverse_check_anim_wiring.py。
"""
import glob
import io
import json
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ASSETS = r'D:\petport_lab\petbuild\assets'

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。"""
    out = []
    i, n, in_block = 0, len(src), False
    while i < n:
        if in_block:
            j = src.find('*/', i)
            if j < 0:
                break
            i = j + 2
            in_block = False
            continue
        if src.startswith('/*', i):
            in_block = True
            i += 2
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        out.append(src[i])
        i += 1
    return ''.join(out)


MODEL = r'D:\petport_lab\petbuild\model'
pack = json.load(io.open(os.path.join(ASSETS, 'pet_anim.json'), encoding='utf-8'))
# v7.0：完整的 106 个动作已移出 APK，随「默认角色包」分发；这里读角色包原料做对账
_full_path = os.path.join(MODEL, 'pack_full_1x.json')
full = json.load(io.open(_full_path, encoding='utf-8')) if os.path.isfile(_full_path) else {}
def _refs(cfg):
    out = set()
    for k in ('idle', 'turn', 'drag', 'clicks'):
        out.update(cfg.get(k, []) or [])
    for a in (cfg.get('moves') or {}).get('actions', []) or []:
        if isinstance(a, dict):
            n = a.get('name') or a.get('id')
            if n: out.add(n)
    for c in cfg.get('categories', []) or []:
        out.update(c.get('actions', []) or [])
    for g, arr in (cfg.get('events') or {}).items():
        out.update(arr or [])
    return out

lines = json.load(io.open(os.path.join(ASSETS, 'anim_lines.json'), encoding='utf-8'))
webp = set(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ASSETS, 'webp', '*.webp')))

pk = code_only(read(os.path.join(SRC, 'PetAnimPack.java')))
pv = code_only(read(os.path.join(SRC, 'PetView.java')))
fs = code_only(read(os.path.join(SRC, 'FloatingService.java')))

# ─────────────────── 一、素材三方一致（106 个文件都被引用、都有台词）───────────────────
print('== 一、素材一致性（APK 侧 + 默认角色包侧）')

apk_webp = set(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(ASSETS, 'webp', '*.webp')))
apk_ref = set(pack.get('idle', []) or [])
for sc in (pack.get('scenes') or {}).values():
    if isinstance(sc, dict): apk_ref.update(sc.get('clips', []) or [])
    elif isinstance(sc, list): apk_ref.update(sc)

# APK 里只应留极少数动作（v7.0 只留 1 个默认待机动作）
check('APK 内置动作已被移出（只剩 %d 个）' % len(apk_webp), len(apk_webp) <= 2,
      '还有 %d 个：%s' % (len(apk_webp), sorted(apk_webp)[:6]))
check('APK 内每个 webp 都被 pet_anim.json 引用', not (apk_webp - apk_ref),
      ('未被引用: %s' % sorted(apk_webp - apk_ref)) if (apk_webp - apk_ref) else '')
check('pet_anim.json 是 2.0（scenes 格式）', 'scenes' in pack and pack.get('version') == 2)

# 默认角色包侧：model/webp 的 106 个必须都被 1.x 完整配置引用、都有台词
model_webp = set(os.path.basename(p)[:-5] for p in glob.glob(os.path.join(MODEL, 'webp', '*.webp')))
full_ref = _refs(full)
check('默认角色包素材齐全（model/webp = %d 个）' % len(model_webp),
      len(model_webp) == 105, '实际 %d' % len(model_webp))
missing_ref = sorted((model_webp | apk_webp) - full_ref)
check('角色包里每个动作都被完整配置引用（共 %d 个）' % len(model_webp | apk_webp),
      not missing_ref, ('未被引用: %s' % missing_ref[:6]) if missing_ref else '')

full_lines = os.path.join(MODEL, 'anim_lines.json')
fl = json.load(io.open(full_lines, encoding='utf-8')) if os.path.isfile(full_lines) else {}
no_line_full = sorted((model_webp | apk_webp) - set(fl))
check('角色包里每个动作都配了台词（%d 条）' % len(fl), not no_line_full,
      ('没台词: %s' % no_line_full[:6]) if no_line_full else '')

asset_lines = json.load(io.open(os.path.join(ASSETS, 'anim_lines.json'), encoding='utf-8'))
no_line_apk = sorted(apk_webp - set(asset_lines))
check('APK 内每个动作都配了台词', not no_line_apk,
      ('没台词: %s' % no_line_apk) if no_line_apk else '')

check('events 组名 = balance / whisper / workStatus（角色包侧）',
      set((full.get('events') or {}).keys()) == {'balance', 'whisper', 'workStatus'},
      '实际: %s' % sorted((full.get('events') or {}).keys()))

# ─────────────────── 二、加载器：events 真的被读、moves 单动画写法被兼容 ───────────────────
print()
print('== 二、PetAnimPack 加载器')

# ★ 断言必须**限定在 load() 作用域内**：mergeAnimConfig 里也有 optJSONObject("events")
#   （外部素材包合并用），不限定作用域的话"加载器不读 events"也照样绿
#   —— 这条正是被 reverse_check 的 A 项抓出来的。
li = pk.find('public void load(Context c)')
lj = pk.find('private void readClipArray', li)
load_body = pk[li:lj] if (li >= 0 and lj > li) else ''

check('load() 读 events 节点（optJSONObject("events")）',
      'optJSONObject("events")' in load_body)
check('events 结果进了 eventGroups', 'eventGroups.put(' in load_body)
check('moves.actions 兼容 {"name": …} 单动画写法',
      'a.optString("name"' in pk or "a.optString(\"name\"" in pk)
check('moves 单动画进了 moveClips', 'moveClips.add(' in pk)
check('pickMove 会用 moveClips（走路动画真的被用到）',
      'moveClips.get(rnd.nextInt(' in pk)

# ─────────────────── 三、语义边界：事件/移动不进随机轮播池 ───────────────────
print()
print('== 三、语义边界（事件动画不得混进待机随机池）')

# buildAllClips() 的**并入语句**里不得出现事件组 / 移动动画。
# ★ 只扫"并入语句"（含 addUniq / allClips.addAll 的行），不扫整个函数体 ——
#   函数末尾那行 Log.i 允许提到 eventGroups/moveClips 做诊断。
# ★ 也不能用 `for \(Group g : ([^)]+)\)` 这种正则：`moveGroups.values()` 自带括号，
#   `[^)]+` 会静默匹配不上 ⇒ 注入 `for (Group g : eventGroups.values()) …` 反而看不见
#   （这条断言被 reverse_check 的 E 项抓过两次，第一次误报、第二次漏报）。
i = pk.find('private void buildAllClips()')
j = pk.find('private void buildMaster()')
body = pk[i:j] if (i >= 0 and j > i) else ''
pool_stmts = [ln.strip() for ln in body.split('\n')
              if ('addUniq(' in ln or 'allClips.addAll' in ln)]
pool_text = '\n'.join(pool_stmts)
check('轮播池的并入语句里没有事件组（eventGroups）',
      bool(pool_stmts) and 'eventGroups' not in pool_text)
check('轮播池的并入语句里没有移动动画（moveClips）',
      bool(pool_stmts) and 'moveClips' not in pool_text)
check('轮播池的并入语句确实并了 idle/turn/drag/clicks/moveDefault + 组',
      all(k in pool_text for k in ('idle', 'turn', 'drag', 'clicks',
                                   'moveDefault', 'moveGroups', 'categories')),
      '并入语句: %d 行' % len(pool_stmts))
check('buildMaster() 把 allClips + 事件组 + 移动都并进来',
      i >= 0 and j > i and 'addUniq(uniq, allClips)' in pk[j:]
      and 'eventGroups.values()' in pk[j:] and 'addUniq(uniq, moveClips)' in pk[j:])

# ─────────────────── 四、全部可播名单 & 按名播放 ───────────────────
print()
print('== 四、动作菜单 / 选择器能看到全部动画')

check('allNames() 返回全部可播名单（不是只返回轮播池）',
      'public java.util.List<String> allNames() { return allNames; }' in pk)
check('clipByName() 走 master 查找表（含事件/移动）',
      'return master.get(id);' in pk)
check('设置页选择器分类含事件组（eventLabel）',
      'eventLabel(k)' in pk and 'eventLabel(e.getKey()).equals(cat)' in pk)
check('"移动" 分类可列出 moveClips', '"移动".equals(cat)' in pk)

# ─────────────────── 五、四个触发出口 ───────────────────
print()
print('== 五、触发出口（FloatingService / PetView）')

check('PetView 有事件动画透传 animRandomEventName',
      'animRandomEventName' in pv and 'pickEventName(' in pv)

whisper_hooks = fs.count('playEventAnim("whisper")')
check('碎碎念出口（主动搭话）≥2 处：纯本地 + API',
      whisper_hooks >= 2, '实际 %d 处' % whisper_hooks)

check('工作状态出口：场景=工作／办公 时播 workStatus',
      'playEventAnim("workStatus")' in fs and 'contains("工作")' in fs)

# ★ 语义：工作状态动画**不能挂在 API 门控后面**。
#   第一版把它放在 tryMoodChat 里，而 tickMood 中 `if (localOnly() || !hasApiKey) return;`
#   在它之前 ⇒ 纯本地档永远看不到工作状态动画（钩子形同不存在）。
ti = fs.find('private void tickMood()')
tj = fs.find('private void maybeWorkStatusAnim')
tick_body = fs[ti:tj] if (ti >= 0 and tj > ti) else ''
pos_hook = tick_body.find('maybeWorkStatusAnim(fg)')
pos_api = tick_body.find('if (localOnly()')
check('工作状态出口在 tickMood 里、且在 localOnly/API 门控之前（纯本地档也演）',
      0 <= pos_hook < pos_api if pos_api >= 0 else pos_hook >= 0,
      'hook@%d apiGate@%d' % (pos_hook, pos_api))
check('工作状态出口与"搭话"解耦（不依赖 hasApiKey）',
      'hasApiKey' not in (fs[tj:fs.find('private void maybeWorkStatusAnim', tj + 10)]
                          if tj >= 0 else ''))

check('余额出口：余额刷新成功分支调用 maybeBalanceAnim(bal)',
      'maybeBalanceAnim(bal);' in fs)
check('余额出口：首次刷新只记档不出演（避免一启动就蹦钱袋）',
      'if (first) return;' in fs and 'lastBalanceAnimBand' in fs)

# 档位名字必须与素材里的 events.balance 完全一致（最容易漂移的地方）
bal_asset = list((full.get('events') or {}).get('balance', []))
i = fs.find('BALANCE_ANIMS')
j = fs.find('};', i)
code_txt = fs[i:j] if i >= 0 else ''
bal_code = [n for n in bal_asset if ('"%s"' % n) in code_txt]
check('代码里的余额档位名与默认角色包 events.balance 一一对应（%d 个）' % len(bal_asset),
      len(bal_code) == len(bal_asset) == 6,
      '素材 %d / 代码命中 %d' % (len(bal_asset), len(bal_code)))

check('出口都走 playAnimByName（真正落到桌宠身上）',
      'petImage.playAnimByName(' in fs and 'animRandomEventName(group)' in fs)

# ─────────────────── 汇总 ───────────────────
print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（动画接线：106 个全部可播）')
