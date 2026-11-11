# -*- coding: utf-8 -*-
"""把「Linux 引擎 APK + 三件载荷」塞进主 App 的 assets，做成**一个全离线 APK**。

用户视角：装一个包，不需要电脑、不需要服务器、不需要联网。
实现：assets/engine/ 下放引擎 APK 与载荷；引擎通过 ContentProvider 直接读（见 EnginePayloads）。

用法：
    python build\\make_offline_bundle.py --abi arm64 --payloads D:\\...\\engine-www-arm64 ^
        --engine-apk D:\\...\\DshLinuxEngine_v1.0.apk
    python build\\make_offline_bundle.py --clean      # 清掉 assets/engine（回到"下载版"）
然后正常 python build_win.py：APP 会把 assets/engine 一起打进 APK。
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, 'assets', 'engine')


def sha256_file(p):
    h = hashlib.sha256()
    with io.open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--abi', choices=['arm64', 'x64'], default='arm64')
    ap.add_argument('--payloads', default='', help='pack_engine_payload.py 产出的目录')
    ap.add_argument('--engine-apk', default='', help='DshLinuxEngine_v1.0.apk')
    ap.add_argument('--clean', action='store_true', help='清掉 assets/engine')
    a = ap.parse_args()

    if a.clean:
        if os.path.isdir(ASSETS):
            shutil.rmtree(ASSETS)
        print('已清空 %s（回到"从服务器下载载荷"的版本）' % ASSETS)
        return

    if not a.payloads:
        sys.exit('!! 需要 --payloads（或用 --clean）')
    mf_path = os.path.join(a.payloads, 'manifest.json')
    if not os.path.isfile(mf_path):
        sys.exit('!! 载荷目录里没有 manifest.json：%s' % a.payloads)
    if a.engine_apk and not os.path.isfile(a.engine_apk):
        sys.exit('!! 找不到引擎 APK：%s' % a.engine_apk)

    if os.path.isdir(ASSETS):
        shutil.rmtree(ASSETS)
    os.makedirs(ASSETS)

    # 1) 引擎 APK —— v8.1 起**不再需要**（Linux 引擎已并进主 App），这里只放载荷。
    if os.environ.get('DSP_BUNDLE_ENGINE_APK') == '1':
        shutil.copy2(a.engine_apk, os.path.join(ASSETS, 'DshLinuxEngine.apk'))

    # 2) 三件载荷（名字必须与引擎约定一致：manifest 里写的就是这三个文件名）
    src = json.load(io.open(mf_path, encoding='utf-8'))
    files = []
    for it in src['files']:
        name = it['name']
        p = os.path.join(a.payloads, name)
        if not os.path.isfile(p):
            sys.exit('!! 缺载荷：%s' % p)
        shutil.copy2(p, os.path.join(ASSETS, name))
        files.append({
            'name': name,
            'url': name,                      # 相对名：引擎会拼成 content://…/<name>
            'sha256': sha256_file(p),
            'bytes': os.path.getsize(p),
        })
        print('  内嵌 %-26s %10d B' % (name, files[-1]['bytes']))

    # 3) manifest（引擎读它）
    out = {'version': src.get('version', '0.1.7-rc.2'), 'abi': a.abi, 'files': files}
    io.open(os.path.join(ASSETS, 'manifest.json'), 'w', encoding='utf-8', newline='').write(
        json.dumps(out, ensure_ascii=False, indent=2))

    total = sum(f['bytes'] for f in files)
    print('\nassets/engine 就绪：%d 个文件，合计 %.1f MB（ABI=%s）' % (len(files) + 1, total / 1048576.0, a.abi))
    print('接着跑 python build_win.py，出来的就是"全离线"APK。')


if __name__ == '__main__':
    main()
