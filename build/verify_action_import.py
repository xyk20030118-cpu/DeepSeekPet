# -*- coding: utf-8 -*-
"""待办 0j 断言：A 自动合成 pack.json · B 文件名前缀当场景 · C App 内「添加动作」 · D 静态图当动作。

用户原话「角色动作包太麻烦了，有没有更简单的自定义导入方案」。施工单见 `STATE.md` 待办 **0j**。
本套件钉住四条边界：

  A. `anim/pack.json` **可选** —— 没有就按文件名现场合成 2.0 配置（`ClipNaming.synthesizePack`）；
  B. 文件名前缀决定场景（`ClipNaming.sceneOf`），且**前缀表派生自 `SceneRegistry` 的中文标签**
     （抄第二份中文名 = 新增场景时前缀永远进 pool，而且毫无报错）；
  C. 设置页「➕ 添加动作」：相册多选 → 选场景（可不选）→ 复制进当前角色 `anim/clips/`
     + 走 P1 的覆盖层 `SceneBindings` 绑定，**不重导整包**；
  D. `isClip()` 认 png/jpg/jpeg 静态图，素材层能把它当**单帧动作**播。

★ 本套件的核心手法：**把纯类编起来真跑**（不只是 grep 源码）。
  `ClipNaming` 与 `SceneRegistry` 零 Android 依赖，这里用 javac + java 直接调用它们，
  把 `synthesizePack()` 的输出交给 Python 的 `json.loads` 解析 —— 于是"合成出来的配置
  到底是不是合法 JSON、场景归对没有"是**行为级**断言，而不是字符串推断。
  （`build/fpstest` 第 [17] 节再用真函数穷举 18 个场景的前缀映射。）

反向破坏见 reverse_check_action_import.py。
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = r'D:\petport_lab\petbuild\src\com\deepseek\pet'
ROOT = r'D:\petport_lab\petbuild'
JAVA_HOME = r'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot'
TMP = os.path.join(HERE, '_actiontest')

fails = []


def check(name, ok, detail=''):
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + detail) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    """去掉 Java 注释（注释里提到某个模式不该让断言误判）。

    ★ 本套件用的是**认字符串字面量**的版本。其它套件那份朴素实现（只看 `//` 与 `/*`）
    会在 `i.setType("image/*")` 这种**字符串里含 `/*`** 的地方误入"块注释"状态，
    把后面成片的真代码一起吃掉 —— 于是断言时灵时不灵（本项目反复出现的"假红/假绿"来源之一）。
    实测：朴素版对 MainActivity 会丢掉 22% 的代码，`requestCode == REQ_ADD_ACTIONS`
    这种在 8000 行附近的锚点根本看不见。
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
    """截出某个方法的正文（同一文件里方法都按 4 空格缩进，内层块更深）"""
    i = src.find(header)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(header))
    return src[i:j + 5] if j > 0 else src[i:]


# ─────────────── 0. 把纯类编起来真跑（行为级断言）───────────────
HARNESS = r'''package com.deepseek.pet;

/** 只读探针：把纯类的真实输出打到 stdout，由 Python 解析断言。 */
public class ClipHarness {
    public static void main(String[] args) {
        java.io.PrintStream o = System.out;
        String[] files = {
            "待机-1.webp", "待机呼吸休闲.webp", "待机随机池-1.webp",
            "点击-1.webp", "点击头部-1.webp", "头-被戳到.png", "身-1.png", "尾-1.png",
            "互动-1.gif", "拖拽-1.webp", "漫游-1.webp", "转向-1.webp", "贴边-1.webp",
            "双击-1.webp", "长按-1.webp", "整点-报时.webp", "节日-春节.webp",
            "余额-分文不剩.webp", "碎碎念-发呆.webp", "工作-码字.webp",
            "小猫咪.png", "待-1.webp", "点-1.webp", "abc-1.webp", "anim/clips/点击-2.webp",
        };
        for (String f : files) o.println("SCENE\t" + f + "\t" + ClipNaming.sceneOf(f));
        for (String id : SceneRegistry.allIds()) {
            o.println("LABEL\t" + id + "\t" + ClipNaming.sceneOf(SceneRegistry.label(id) + "-测试.webp"));
        }
        o.println("PACK_BEGIN");
        o.print(ClipNaming.synthesizePack(new String[]{
                "待机呼吸休闲.webp", "点击-摸头.webp", "拖拽-挣扎.gif",
                "头-被戳到.png", "整点-报时.webp", "小猫咪.png"}));
        o.println("PACK_END");
        o.println("PACK2_BEGIN");
        o.print(ClipNaming.synthesizePack(new String[]{"点击-1.webp", "小猫咪.png"}));
        o.println("PACK2_END");
        o.println("EMPTY\t" + ClipNaming.synthesizePack(new String[0]).length()
                + "\t" + ClipNaming.synthesizePack(null).length());
        o.println("ISIMG\t" + ClipNaming.isImageName("a.webp")
                + "\t" + ClipNaming.isImageName("a.PNG")
                + "\t" + ClipNaming.isImageName("a.jpg")
                + "\t" + ClipNaming.isImageName("a.mp4")
                + "\t" + ClipNaming.isImageName("a.webp.txt"));
        o.println("UNIQ\t" + ClipNaming.uniqueId("猫", new String[0])
                + "\t" + ClipNaming.uniqueId("猫", new String[]{"猫"})
                + "\t" + ClipNaming.uniqueId("猫", new String[]{"猫", "猫_2"}));
        o.println("SAFE\t" + ClipNaming.safeClipId("我的 猫.png")
                + "\t" + ClipNaming.safeClipId("a:b*c.png")
                + "\t" + ClipNaming.safeClipId(""));
        o.println("EXT\t" + ClipNaming.extFor("x.png", null)
                + "\t" + ClipNaming.extFor("x", "image/jpeg")
                + "\t" + ClipNaming.extFor("x", "image/webp")
                + "\t" + ClipNaming.extFor("x", null));
    }
}
'''

RUN = {}


def run_harness():
    """编译并运行探针，返回 {key: 值} 与合成配置的 JSON。失败时返回 None。"""
    if RUN:
        return RUN.get('ok')
    ok = False
    try:
        pkg = os.path.join(TMP, 'com', 'deepseek', 'pet')
        shutil.rmtree(TMP, ignore_errors=True)
        os.makedirs(pkg)
        for f in ('ClipNaming.java', 'SceneRegistry.java'):
            shutil.copy2(os.path.join(SRC, f), os.path.join(pkg, f))
        io.open(os.path.join(pkg, 'ClipHarness.java'), 'w', encoding='utf-8').write(HARNESS)
        javac = os.path.join(JAVA_HOME, 'bin', 'javac.exe')
        javaexe = os.path.join(JAVA_HOME, 'bin', 'java.exe')
        out = os.path.join(TMP, 'out')
        os.makedirs(out)
        r = subprocess.run([javac, '-nowarn', '-encoding', 'UTF-8', '-d', out,
                            os.path.join(pkg, 'ClipNaming.java'),
                            os.path.join(pkg, 'SceneRegistry.java'),
                            os.path.join(pkg, 'ClipHarness.java')],
                           capture_output=True)
        if r.returncode != 0:
            RUN['err'] = 'javac 失败: ' + (r.stdout + r.stderr).decode('utf-8', 'replace')[:400]
            return False
        r = subprocess.run([javaexe, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                            '-cp', out, 'com.deepseek.pet.ClipHarness'],
                           capture_output=True)
        if r.returncode != 0:
            RUN['err'] = 'java 失败: ' + (r.stdout + r.stderr).decode('utf-8', 'replace')[:400]
            return False
        txt = r.stdout.decode('utf-8', 'replace')
        lines = {}
        pack_raw, pack2_raw = [], []
        bucket = None
        for ln in txt.splitlines():
            if ln == 'PACK_BEGIN':
                bucket = pack_raw
                continue
            if ln == 'PACK_END':
                bucket = None
                continue
            if ln == 'PACK2_BEGIN':
                bucket = pack2_raw
                continue
            if ln == 'PACK2_END':
                bucket = None
                continue
            if bucket is not None:
                bucket.append(ln)
                continue
            k, _, v = ln.partition('\t')
            lines.setdefault(k, []).append(v)
        RUN['lines'] = lines
        RUN['pack'] = '\n'.join(pack_raw)
        RUN['pack2'] = '\n'.join(pack2_raw)
        ok = True
    except Exception as e:                                  # noqa: BLE001
        RUN['err'] = '探针异常: %s' % e
    finally:
        shutil.rmtree(TMP, ignore_errors=True)
    RUN['ok'] = ok
    return ok


print('== 〇、把纯类编起来真跑（ClipNaming / SceneRegistry）')
harness_ok = run_harness()
check('纯类探针编译并运行成功（ClipNaming 零 Android 依赖才做得到）', harness_ok,
      RUN.get('err', ''))

L = RUN.get('lines', {})
scenes = {}
for ln in L.get('SCENE', []):
    f, _, s = ln.partition('\t')
    scenes[f] = s

print()
print('== 一、B：文件名前缀 → 场景（真函数结果）')

check('待机-1 → idle', scenes.get('待机-1.webp') == 'idle', scenes.get('待机-1.webp'))
check('待机呼吸休闲 → idle（真实默认素材名，没连字符也认）',
      scenes.get('待机呼吸休闲.webp') == 'idle', scenes.get('待机呼吸休闲.webp'))
check('★ 最长优先：待机随机池-1 → pool（不被「待机」抢走）',
      scenes.get('待机随机池-1.webp') == 'pool', scenes.get('待机随机池-1.webp'))
check('★ 最长优先：点击头部-1 → tap_head（不被「点击」抢走）',
      scenes.get('点击头部-1.webp') == 'tap_head', scenes.get('点击头部-1.webp'))
check('点击-1 → click / 互动-1 → interact / 拖拽-1 → drag',
      scenes.get('点击-1.webp') == 'click' and scenes.get('互动-1.gif') == 'interact'
      and scenes.get('拖拽-1.webp') == 'drag')
check('漫游/转向/贴边/双击/长按 各归各位',
      scenes.get('漫游-1.webp') == 'roam' and scenes.get('转向-1.webp') == 'turn'
      and scenes.get('贴边-1.webp') == 'dock' and scenes.get('双击-1.webp') == 'double_tap'
      and scenes.get('长按-1.webp') == 'long_press')
check('头-/身-/尾- → tap_head / tap_body / tap_tail（简称走"唯一包含"）',
      scenes.get('头-被戳到.png') == 'tap_head' and scenes.get('身-1.png') == 'tap_body'
      and scenes.get('尾-1.png') == 'tap_tail')
check('整点/节日/余额/工作/碎碎念 → 对应场景（含事件场景）',
      scenes.get('整点-报时.webp') == 'hour' and scenes.get('节日-春节.webp') == 'festival'
      and scenes.get('余额-分文不剩.webp') == 'balance'
      and scenes.get('工作-码字.webp') == 'workStatus'
      and scenes.get('碎碎念-发呆.webp') == 'whisper')
check('不带前缀 → pool', scenes.get('小猫咪.png') == 'pool' and scenes.get('abc-1.webp') == 'pool')
check('★ 简称"唯一才认"：待-1 有歧义 ⇒ pool（不猜）',
      scenes.get('待-1.webp') == 'pool', scenes.get('待-1.webp'))
# ★ 这一条是**判据的判据**：待-1 那个用例其实分不出"唯一才认"与"取最后一个匹配"
#   （「待机随机池」正好排在「待机」后面、也正好是 pool）。点-1 有 5 个候选、
#   最后一个匹配是 tap_tail ⇒ 只有真的实现了"唯一才认"才会得 pool。
#   （反向项 i 就是靠这条才抓得到的 —— 第一版没有它，破坏后 verify 依然是绿的。）
check('★★ 简称"唯一才认"（判别用例）：点-1 有 5 个候选 ⇒ pool，而不是最后一个匹配',
      scenes.get('点-1.webp') == 'pool', scenes.get('点-1.webp'))
check('带目录不影响（anim/clips/点击-2.webp → click）',
      scenes.get('anim/clips/点击-2.webp') == 'click', scenes.get('anim/clips/点击-2.webp'))

label_rows = [ln.split('\t') for ln in L.get('LABEL', [])]
bad_label = [r for r in label_rows if len(r) == 2 and r[0] != r[1]]
check('★ 18 个场景的中文名前缀都命中它自己（= 前缀表派生自 SceneRegistry 标签）',
      len(label_rows) == 18 and not bad_label,
      '命中 %d/18%s' % (len(label_rows) - len(bad_label),
                       ('，反例 %s' % bad_label[:3]) if bad_label else ''))
check('★★ ClipNaming 的**代码里没有任何中文场景名字面量**（抄一份前缀表就会漂）',
      re.search(r'[\u4e00-\u9fff]', java('ClipNaming.java')) is None,
      (re.search(r'[\u4e00-\u9fff]', java('ClipNaming.java')) or ''))
check('前缀表是运行时从 SceneRegistry.label(id) 取的',
      'SceneRegistry.label(id)' in java('ClipNaming.java'))

print()
print('== 二、A：自动合成 2.0 配置（输出交给 json.loads 解析）')

pack = None
pack_err = ''
try:
    pack = json.loads(RUN.get('pack', ''))
except Exception as e:                                       # noqa: BLE001
    pack_err = str(e)
check('★ 合成结果**是合法 JSON**（不是拼字符串拼出来的残次品）', pack is not None, pack_err)
if pack is not None:
    sc = pack.get('scenes') or {}
    check('version = 2 且只有 scenes 节点（2.0 形状）',
          pack.get('version') == 2 and 'scenes' in pack)
    check('场景集合正确（idle/click/drag/tap_head/hour/pool，没有多余空场景）',
          set(sc.keys()) == {'idle', 'click', 'drag', 'tap_head', 'hour', 'pool'},
          str(sorted(sc.keys())))
    check('idle = [待机呼吸休闲]（文件名含「待机」的那个）', sc.get('idle') == ['待机呼吸休闲'])
    check('click = [点击-摸头] / drag = [拖拽-挣扎]',
          sc.get('click') == ['点击-摸头'] and sc.get('drag') == ['拖拽-挣扎'])
    check('tap_head = [头-被戳到] / hour = [整点-报时]',
          sc.get('tap_head') == ['头-被戳到'] and sc.get('hour') == ['整点-报时'])
    check('★ 有前缀的只进自己的场景，pool 里只有没前缀的那个',
          sc.get('pool') == ['小猫咪'], str(sc.get('pool')))
    check('配置里存的是**不带扩展名**的动作名（素材层按 名字.扩展名 去找文件）',
          all(os.path.splitext(n)[1].lower() not in
              ('.webp', '.gif', '.png', '.jpg', '.jpeg')
              for arr in sc.values() for n in arr))

pack2 = None
try:
    pack2 = json.loads(RUN.get('pack2', ''))
except Exception:                                            # noqa: BLE001
    pass
check('★ 一个都不含「待机」时 idle 兜底取第一个文件（桌宠总要站着）',
      (pack2 or {}).get('scenes', {}).get('idle') == ['点击-1'],
      str((pack2 or {}).get('scenes', {}).get('idle')))
check('空清单 ⇒ 空串（调用方按"包里没有动作文件"报错）',
      L.get('EMPTY', [''])[0] == '0\t0', str(L.get('EMPTY')))

isimg = (L.get('ISIMG', [''])[0] or '').split('\t')
check('认得 webp/PNG/jpg（大小写不敏感），不认 mp4 与骗人的双扩展名',
      isimg[:5] == ['true', 'true', 'true', 'false', 'false'], str(isimg))
uniq = (L.get('UNIQ', [''])[0] or '').split('\t')
check('uniqueId：没占用用原名，重名加 _2 / _3（绝不覆盖已有动作）',
      uniq[:3] == ['猫', '猫_2', '猫_3'], str(uniq))
safe = (L.get('SAFE', [''])[0] or '').split('\t')
check('safeClipId：留中文与空格、剥非法字符、空名返回空串',
      safe[:3] == ['我的 猫', 'abc', ''], str(safe))
ext = (L.get('EXT', [''])[0] or '').split('\t')
check('extFor：按扩展名 → MIME → png 兜底',
      ext[:4] == ['png', 'jpg', 'webp', 'png'], str(ext))

print()
print('== 三、A：导入器（缺 pack.json 不再报错）')

imp = java('CharacterImporter.java')
check('★ 缺 anim/pack.json 时走合成（不再 return error）',
      'ClipNaming.synthesizePack(' in imp and 'writeText(dst, json)' in imp
      and 'r.synthesized = true;' in imp)
check('★ 旧的硬报错那句话已经删掉（"包里没有 anim/pack.json…"）',
      '包里没有 anim/pack.json' not in imp)
check('合成结果写进包的 anim/pack.json（角色包从此自带配置，导出也是完整的）',
      'new File(tmp, "anim/pack.json")' in imp)
check('动作都读不出来时才报错，且提示里列出认得的扩展名',
      'ClipNaming.extList()' in imp and '包里没有可用的动作文件' in imp)
check('★ 扫动作文件先看 anim/clips/，全树扫时**跳过辅助目录**（表情图/形象图也是 png）',
      # ★ 轮次㉕：判据从"硬编码 memes"改成纯类 PackFormat.isAuxDir（清单 = memes + appearance）。
      #   钉的是**语义**（先看标准目录 + 走统一判据），不是某一行字面量。
      'collectClipFiles(' in imp and 'PackFormat.isAuxDir(f.getName())' in imp
      and '"memes".equalsIgnoreCase' not in imp
      and 'File clips = new File(tmp, "anim/clips");' in imp)
check('Result 带 synthesized / synScenes（界面要能提示"自动归到 N 个场景"）',
      'public boolean synthesized;' in imp and 'public int synScenes;' in imp)
check('路径穿越校验与 600 MB 上限没被碰坏（既有铁律）',
      'n.startsWith("/")' in imp and 'n.contains("..")' in imp and '600L * 1024 * 1024' in imp)

print()
print('== 四、D：isClip 认静态图（相册里选的 PNG/JPG 不能白选）')

check('★ isClip 委托纯类 ClipNaming.isImageName（扩展名清单只有一份）',
      'return ClipNaming.isImageName(n);' in imp
      and 'public static final String[] IMAGE_EXTS = {"webp", "gif", "png", "jpg", "jpeg"};'
      in java('ClipNaming.java'))
check('旧的"只认 .webp/.gif"实现已删（不能被还原）',
      'endsWith(".webp") || l.endsWith(".gif")' not in imp)

print()
print('== 五、D：素材层能播静态图 + 认识目录里新加的动作')

packj = java('PetAnimPack.java')
check('★ readClipBytes 挨个试全部图片扩展名（PNG/JPG 也找得到）',
      'for (String e : ClipNaming.IMAGE_EXTS) if (!exts.contains(e)) exts.add(e);' in packj)
check('assets 回退也遍历扩展名（内置角色一样认静态图）',
      'ctx.getAssets().open(DIR + id + "." + e)' in packj)
check('★ scanCharClips 存在，并在 buildAllClips() **之前**调用',
      'private void scanCharClips()' in packj and 'scanCharClips();' in packj
      # ★ 两个 find 都必须 >= 0 再比大小：find 找不到返回 -1，而 -1 < 正数恒为真
      and packj.find('scanCharClips();') >= 0 and packj.find('buildAllClips();') >= 0
      and packj.find('scanCharClips();') < packj.find('buildAllClips();'),
      'call@%d firstBuild@%d' % (packj.find('scanCharClips();'), packj.find('buildAllClips();')))
check('目录扫描只认图片（非图片文件不会被当动作）',
      'ClipNaming.isImageName(nm)' in body(packj, 'private int scanClipDir(File d) {'))
check('★ 大图保护 capDecodeSize 接进了**两个**解码回调（相册照片可能上千万像素）',
      packj.count('capDecodeSize(dec, info);') == 2 and 'MAX_DECODE_EDGE' in packj)

print()
print('== 六、C：App 内「添加动作」（相册多选 → 场景 → 落盘 + 绑定）')

main = java('MainActivity.java')
store = java('CharacterStore.java')
check('REQ_ADD_ACTIONS = 2015（★ 与本文件其它 REQ_* 不撞号）',
      re.search(r'REQ_ADD_ACTIONS = 2015;', main) is not None
      and len(re.findall(r'private static final int REQ_[A-Z_]+ = (\d+);', main))
      == len(set(re.findall(r'private static final int REQ_[A-Z_]+ = (\d+);', main))))
check('★ SAF 多选：ACTION_OPEN_DOCUMENT + EXTRA_ALLOW_MULTIPLE + 显式 MIME 列表',
      # ★ 三个判据都必须落在 pickActionImages() **方法体**里：
      #   EXTRA_ALLOW_MULTIPLE 在「批量导入表情」那里也有一处，只查全文件会假绿。
      all(k in body(main, 'private void pickActionImages() {') for k in
          ('Intent.ACTION_OPEN_DOCUMENT', 'Intent.EXTRA_ALLOW_MULTIPLE', '"image/webp"',
           'Intent.CATEGORY_OPENABLE')))
check('设置页有「添加动作」入口按钮（点击进相册）',
      '\\u2795 添加动作（从相册多选）' in main
      and 'public void onClick(View v) { pickActionImages(); }' in main)
check('动作库页也有同一个入口（setNeutralButton，不与"关闭"抢按钮位）',
      'setNeutralButton("\\u2795 添加动作"' in main)
check('onActivityResult 接了 REQ_ADD_ACTIONS', 'requestCode == REQ_ADD_ACTIONS' in main)
check('多选清单走 getClipData（单选走 getData 兜底）+ 名字取自文件名',
      'data.getClipData()' in body(main, 'private void handleAddActions(Intent data) {')
      and 'queryDisplayName(' in body(main, 'private void handleAddActions(Intent data) {'))
check('目标场景可"先不选"（只进动作库）', '只加入动作库（先不绑定）' in main)
check('★ 落盘：复制进用户素材目录 + 用 uniqueId 防覆盖',
      'CharacterStore.addClipFromUri(' in main
      and 'ClipNaming.uniqueId(base, taken.toArray(new String[0]))' in store
      and 'File dir = SceneBindings.clipsDirFor(ctx, ch.id);' in store
      and 'clipIds(ch)' in body(store, 'public static String addClipFromUri('))
check('★ 绑定走 P1 覆盖层（不重导整包、不动角色包文件）',
      'SceneBindings.setBinds(this, cid, scene, binds);' in body(
          main, 'private void bindActionsToScene(String scene, List<String> added) {'))
check('★★ 绑定是**追加**：先读该场景当前生效的绑定再并进新动作（直接 setClips 会把整场抹掉）',
      'for (PetAnimPack.Clip c : as.bindsOf(scene))' in body(
          main, 'private void bindActionsToScene(String scene, List<String> added) {'))
check('保存后立刻 reloadPet（悬浮窗当场生效）',
      'Prefs.reloadPet(MainActivity.this);' in body(
          main, 'private void saveActions(final List<Uri> uris, final List<String> names,'
                ' final String scene) {'))
check('★ 内置角色也能加动作：素材落在**用户覆盖层**，不为它造假角色目录',
      'SceneBindings.clipsDirFor(ctx, ch.id)' in store
      and 'ensureWritable' not in store and 'ensureWritable' not in main
      and 'String cid = ActionMenuConfig.sanitize(PetCharacter.BUILTIN_ID);' not in store)
check('★ 角色 id 用 ch.id（内置角色 = builtin:default ⇒ 目录 scenes/builtin_default/clips）',
      'SceneBindings.userClipIds(ctx, ch.id)' in store)
check('clipIds 列已有动作名（去扩展名、去重）',
      'public static List<String> clipIds(PetCharacter ch)' in store
      and 'ClipNaming.baseNameOf(f.getName())' in store)
check('导入完成时会提示"自动归到 N 个场景"（用户要知道少写一步也能用）',
      'if (r.synthesized) {' in main and '已按文件名自动归到 ' in main)

print()
print('== 七、D：文档与代码对齐（「角色包格式.md」）')

doc = read(os.path.join(ROOT, '角色包格式.md'))
check('★ 体积上限与代码一致（600 MB，不再是 200 MB）',
      '600 MB' in doc and '200 MB' not in doc)
check('★ 第六节第 2 条按 A 实现（pack.json 可以没有，而不是"必须有"）',
      '**至少要有一个动作文件**' in doc and '`anim/pack.json` **可以没有**' in doc)
check('静态图（png/jpg）写进素材格式表', '.png / *.jpg' in doc or 'PNG / JPG 也可以' in doc)
check('新增 3.4 节说明"文件名前缀就是场景"', '### 3.4' in doc and '文件名前缀' in doc)
check('新增第九节说明手机端「➕ 添加动作」', '## 九、' in doc and '添加动作' in doc)
check('极简包说明已更新（只放 anim/clips/* 也能导入）',
      '只放 `anim/clips/*` 就能导入' in doc)

print()
print('== 八、场景内「📥 导入动画到此场景」+ 用户素材层（用户追加要求）')

bind = subprocess.run([sys.executable, os.path.join(HERE, 'rt_test', 'run.py'), '--only=bindings'],
                      capture_output=True)
bout = bind.stdout.decode('utf-8', 'replace')
blines = [ln for ln in bout.splitlines() if ln.startswith('BIND|')]
check('覆盖层行为断言程序跑通（真读写 + 真追加）',
      bind.returncode == 0 and len(blines) >= 12, '%d 条' % len(blines))
check('★ 行为断言：追加保留已有权重（×3 不被抹成 ×1）',
      any('追加保留已有权重' in ln and '|OK|' in ln for ln in blines),
      '; '.join(l for l in blines if 'FAIL' in l))
check('★ 行为断言：事件场景写进 events 节点', any('事件场景写进 events 节点' in ln and '|OK|' in ln for ln in blines))
check('★ 行为断言：覆盖按角色隔离', any('覆盖按角色隔离' in ln and '|OK|' in ln for ln in blines))
check('★ 行为断言：用户素材目录 = filesDir/scenes/<净化 id>/clips',
      any('用户素材目录 = filesDir/scenes' in ln and '|OK|' in ln for ln in blines))
check('★ 行为断言：没改过的场景返回 null（回退角色包）',
      any('没改过的场景返回 null' in ln and '|OK|' in ln for ln in blines))

acts_b = body(main, 'private void editScene(final ActionSystem as, final String scene) {')
check('★ 场景操作面第一项就是「📥 导入动画到此场景」（5 项，原 4 项一个不少）',
      '\\uD83D\\uDCE5 导入动画到此场景' in acts_b
      and acts_b.index('\\uD83D\\uDCE5 导入动画到此场景')
      < acts_b.index('\\u270F\\uFE0F 编辑绑定（多选）')
      and all(k in acts_b for k in ('编辑绑定（多选）', '预览动作', '设置权重', '恢复角色包默认')))
check('分发：第 0 项走 pickSceneImport(scene)，其余四项保持原顺序',
      'if (which == 0) pickSceneImport(scene);' in acts_b
      and 'else if (which == 1) editSceneBindings(as, scene);' in acts_b
      and 'else if (which == 3) editSceneWeights(as, scene);' in acts_b)
pick_s = body(main, 'private void pickSceneImport(final String scene) {')
check('场景导入也是 SAF 多选（与「添加动作」同一条路）',
      all(k in pick_s for k in ('Intent.ACTION_OPEN_DOCUMENT', 'Intent.EXTRA_ALLOW_MULTIPLE',
                               '"image/webp"', 'REQ_IMPORT_SCENE_CLIPS')))
check('★ 目标场景记在字段里（onActivityResult 只剩 Intent）',
      'pendingSceneImport = scene;' in pick_s and 'private String pendingSceneImport;' in main)
check('onActivityResult 接了 REQ_IMPORT_SCENE_CLIPS',
      'if (requestCode == REQ_IMPORT_SCENE_CLIPS && resultCode == RESULT_OK && data != null) {' in main
      and 'handleSceneImport(data, pendingSceneImport);' in main)
hi = body(main, 'private void handleSceneImport(Intent data, final String scene) {')
check('★ 落盘 + 绑到**这个场景**（复制进用户素材目录，再追加绑定）',
      'CharacterStore.addClipFromUri(MainActivity.this, ch, uris.get(i),' in hi
      and 'if (!added.isEmpty()) bindActionsToScene(scene, added);' in hi)
check('★ 结束要 reloadPet + 回到该场景操作面（否则"已绑 N 个"不变，像没成功）',
      hi.find('Prefs.reloadPet(MainActivity.this);') >= 0
      and hi.find('editScene(loadSceneEditor(), scene);') >= 0
      and hi.find('Prefs.reloadPet(MainActivity.this);') < hi.find('editScene(loadSceneEditor(), scene);'))
check('★ 如实汇报：成功 N / 跳过 M（原因）/ 重名改存为 _2',
      '跳过 " + ffail + " 个（读不到文件）' in hi and '重名已改存为' in hi)
check('★ 重名判断并上"当前有效配置"的动作名（不会静默盖掉角色包的同名动作）',
      'taken = loadSceneEditor().allClipIds();' in hi
      and 'names.get(i), mime, taken);' in hi
      and 'if (alsoTaken != null) for (String n : alsoTaken) {' in store)
check('★ 存储位置只有一处来源：SceneBindings.clipsDirFor（CharacterStore 与素材层共用）',
      'SceneBindings.clipsDirFor(ctx, ch.id)' in store
      and 'SceneBindings.clipsDirIn(filesRoot, charId)' in packj)
rb = body(packj, 'private byte[] readClipBytes(String id, String ext) {')
check('★ 素材层认得用户素材目录，且**优先于**角色包（同名以用户为准）',
      rb.find('userClipFile(id, e)') >= 0 and rb.find('charClipFile(id, e)') >= 0
      and rb.find('userClipFile(id, e)') < rb.find('charClipFile(id, e)'),
      'user@%d char@%d' % (rb.find('userClipFile(id, e)'), rb.find('charClipFile(id, e)')))
check('★ 目录扫描也扫用户素材目录（"只进动作库"的图才在动作库里看得见）',
      'File ud = userClipDir();' in body(packj, 'private void scanCharClips() {')
      and 'n += scanClipDir(ud);' in body(packj, 'private void scanCharClips() {'))
check('★ 追加绑定走 SceneBindings.appendBinds（保留权重的纯函数）',
      'SceneBindings.appendBinds(cur, added)' in body(
          main, 'private void bindActionsToScene(String scene, List<String> added) {'))
check('doc：角色包格式.md 写清楚用户素材目录与优先级',
      'filesDir/scenes/<角色 id>/clips/' in doc and '优先级高于角色包' in doc)

print()
print('== 九、纯类断言已挂进 fpstest（否则 A/B 只有 grep，没有真断言）')

fpstest_bat = read(os.path.join(HERE, 'fpstest', 'run.bat'))
check('fpstest 编译 ClipNaming.java + SceneRegistry.java',
      'ClipNaming.java' in fpstest_bat and 'SceneRegistry.java' in fpstest_bat)
fps = read(os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java'))
check('fpstest 里有第 [17] 节（ClipNaming 动作文件名 / 自动合成配置）',
      '[17] ClipNaming' in fps and 'ClipNaming.synthesizePack(' in fps
      and 'ClipNaming.sceneOf(' in fps)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（0j：A 自动合成配置 + B 文件名前缀 + C App 内添加动作 + D 静态图）')
