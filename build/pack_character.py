#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打「默认角色包」—— 把移出 APK 的动作套组打成一个可导入/可分享的角色包。

用法:
    python build/pack_character.py [输出目录] [工程根]

为什么需要它
    用户 2026-10-07 决定：**内置动作不随 APK 走**（APK 从 340MB 瘦到 ~35MB），
    但素材不能丢 —— 106 个 animated WebP 挪到 `model/webp/`，由本脚本打成
    `DeepSeekPet_默认角色包_v<版本>.zip`，随三个交付 zip 一起分发。
    它同时是**角色包格式的第一个样例**：以后用户导出的角色包与它同构。

角色包结构（标准包，P0 定案）:
    character.json       角色元信息（名称/作者/版本）
    anim/pack.json       动作系统 2.0：场景 → 动作
    anim/lines.json      动作 → 台词
    anim/clips/*.webp    动作素材
    memes/memes.json     表情包索引
    memes/*.png          表情图
    persona.md           性格提示词（可选；有 model/persona_default.md 时带上）

场景映射（把老的 1.x 分组搬进新场景，一个不丢）:
    idle(1) + click(5) + drag(1) + roam(3) + turn(1) + pool(59) + festival(21) + events(15) = 106
"""
import io
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..'))

FULL_1X = os.path.join(ROOT, 'model', 'pack_full_1x.json')     # 完整 1.x 配置
LINES = os.path.join(ROOT, 'model', 'anim_lines.json')         # 完整台词
CLIP_DIRS = [os.path.join(ROOT, 'model', 'webp'),              # 移出 APK 的 105 个
             os.path.join(ROOT, 'assets', 'webp')]             # APK 里留的那 1 个
MEMES_JSON = os.path.join(ROOT, 'build', 'charpack_src', 'memes.json')
MEMES_DIR = os.path.join(ROOT, 'build', 'charpack_src', 'memes')
PERSONA = os.path.join(ROOT, 'model', 'persona_default.md')
MANIFEST = os.path.join(ROOT, 'AndroidManifest.xml')

EVENTS = ['balance', 'whisper', 'workStatus']


def version():
    import re
    m = re.search(r'android:versionName="([^"]+)"', io.open(MANIFEST, encoding='utf-8').read())
    return m.group(1) if m else '0.0'


def load_1x():
    return json.load(io.open(FULL_1X, encoding='utf-8'))


SCENE_MAP = os.path.join(ROOT, 'model', 'scene_map.json')


def scene_map(cfg):
    """106 个动作 → 18 个场景的分类表。

    以 `model/scene_map.json` 为**唯一真相**（人工逐条归类：拖拽/互动/漫游/贴边/整点/
    节日/部位点击/双击/长按/余额档位…都绑上了对应动画）。
    文件缺失时退回老的"按分组粗分"逻辑，保证脚本永远能跑。
    """
    if os.path.isfile(SCENE_MAP):
        m = json.load(io.open(SCENE_MAP, encoding='utf-8'))
        out = {k: list(v) for k, v in m.items() if k != 'events' and k != 'pool'}
        out['pool'] = list(m.get('pool', []))
        for k in ('interact', 'dock', 'hour', 'tap_head', 'tap_body', 'tap_tail',
                  'double_tap', 'long_press', 'idle', 'click', 'drag', 'roam',
                  'turn', 'festival', 'pool'):
            out.setdefault(k, [])
        return out
    # —— 兜底：老逻辑（按 1.x 分组粗分）——
    festival, pool = [], []
    for c in cfg.get('categories', []):
        (festival if c.get('id') == '时节' else pool).extend(c.get('actions', []))
    moves = [a.get('name') or a.get('id') for a in cfg.get('moves', {}).get('actions', [])
             if isinstance(a, dict)]
    return {
        'idle': list(cfg.get('idle', [])), 'click': list(cfg.get('clicks', [])),
        'interact': [], 'drag': list(cfg.get('drag', [])), 'roam': moves,
        'turn': list(cfg.get('turn', [])), 'dock': [], 'pool': pool, 'hour': [],
        'festival': festival, 'tap_head': [], 'tap_body': [], 'tap_tail': [],
        'double_tap': [], 'long_press': [],
    }


def events_map(cfg):
    """事件场景：余额档位 / 碎碎念 / 工作状态（同样以 scene_map.json 为准）"""
    if os.path.isfile(SCENE_MAP):
        m = json.load(io.open(SCENE_MAP, encoding='utf-8'))
        ev = m.get('events') or {}
        return {g: list(ev.get(g, [])) for g in EVENTS}
    return {g: list(cfg.get('events', {}).get(g, [])) for g in EVENTS}


def clip_path(name):
    for d in CLIP_DIRS:
        for ext in ('.webp', '.gif'):
            p = os.path.join(d, name + ext)
            if os.path.isfile(p):
                return p
    return None


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else r'D:\桌宠移植包'
    os.makedirs(outdir, exist_ok=True)
    v = version()
    cfg = load_1x()
    scenes = scene_map(cfg)

    missing = []
    for sc, names in scenes.items():
        for n in names:
            if clip_path(n) is None:
                missing.append('%s/%s' % (sc, n))
    ev = events_map(cfg)
    for g in EVENTS:
        for n in ev.get(g, []):
            if clip_path(n) is None:
                missing.append('events.%s/%s' % (g, n))
    if missing:
        sys.exit('!! 缺素材文件：%s' % missing)

    total = sum(len(v2) for v2 in scenes.values()) + sum(len(ev.get(g, [])) for g in EVENTS)

    out = os.path.join(outdir, 'DeepSeekPet_默认角色包_v%s.zip' % v)
    print('==> %s' % out)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, allowZip64=True) as z:
        # character.json
        z.writestr('character.json', json.dumps({
            'id': 'default',
            'name': '默认角色（%d 个动作）' % total,
            'author': 'DeepSeekPet',
            'version': v,
            'note': '把 APK 里移出的动作套组原样打包；导入后即可恢复全部动作。'
        }, ensure_ascii=False, indent=1))

        # anim/pack.json（2.0）
        pack = {
            'version': 2,
            'scenes': {k: {'clips': list(v2)} for k, v2 in scenes.items()},
            'events': {g: list(ev.get(g, [])) for g in EVENTS},
        }
        z.writestr('anim/pack.json', json.dumps(pack, ensure_ascii=False, indent=1))

        # anim/lines.json（只带本角色用到的动作）
        lines = json.load(io.open(LINES, encoding='utf-8'))
        used = set()
        for names in scenes.values():
            used.update(names)
        for g in EVENTS:
            used.update(ev.get(g, []))
        z.writestr('anim/lines.json', json.dumps(
            {k: val for k, val in lines.items() if k in used}, ensure_ascii=False, indent=1))

        # anim/clips/*
        nclip = 0
        for name in sorted(used):
            p = clip_path(name)
            z.write(p, 'anim/clips/' + os.path.basename(p))
            nclip += 1

        # memes/
        nmeme = 0
        if os.path.isfile(MEMES_JSON):
            z.write(MEMES_JSON, 'memes/memes.json')
            if os.path.isdir(MEMES_DIR):
                for f in sorted(os.listdir(MEMES_DIR)):
                    fp = os.path.join(MEMES_DIR, f)
                    if os.path.isfile(fp):
                        z.write(fp, 'memes/' + f)
                        nmeme += 1

        # persona.md（可选）
        if os.path.isfile(PERSONA):
            z.write(PERSONA, 'persona.md')

    print('    角色动作 %d 个 / 素材文件 %d / 表情 %d / %.1f MB'
          % (total, nclip, nmeme, os.path.getsize(out) / 1048576.0))
    per = {k: len(v2) for k, v2 in scenes.items() if v2}
    per.update({'events.' + g: len(ev.get(g, [])) for g in EVENTS if ev.get(g)})
    print('    分组：' + '  '.join('%s=%d' % kv for kv in sorted(per.items())))


if __name__ == '__main__':
    main()
