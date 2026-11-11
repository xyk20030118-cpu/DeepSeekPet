# -*- coding: utf-8 -*-
"""统一的产物路径解析 —— 别在各脚本里硬编码 `v6.1`。

<h3>为什么需要它</h3>
本工程的交付物文件名里带版本号（`DeepSeekPet_v6.5_rebuilt.apk`）。
以前六个**验证**脚本把这个号写死在代码里，升版本时 `build_win.py` /
`pack_delivery.py` 会自动跟随清单产出新名字，而这些验证脚本仍指着旧路径 ——
结果是"验证通过"其实验的是**上一版的包**，或者直接报"文件不存在"。
这正是 STATE.md 里反复提醒的"包里是 A、文件名写 B"那类脱节，
只不过这次脱节发生在验证环节（更隐蔽：它会假装绿灯）。

<h3>做法</h3>单一只源：全部从 `AndroidManifest.xml` 的 `versionName` 读，
与 `build_win.py::_manifest_version` 保持同一判据。
"""
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
# build/ 在工程根下一层；允许被拷到别处时用环境变量覆盖
DEV_ROOT = os.environ.get('DSP_DEV_ROOT', os.path.dirname(_HERE))
REL_ROOT = os.environ.get('DSP_REL_ROOT', r'D:\petbuild_release')
DELIVERY_DIR = os.environ.get('DSP_DELIVERY_DIR', r'D:\桌宠移植包')


def manifest_version(project_root=None):
    """读开发版工程清单里的 versionName。"""
    root = project_root or DEV_ROOT
    path = os.path.join(root, 'AndroidManifest.xml')
    with open(path, 'r', encoding='utf-8') as f:
        m = re.search(r'android:versionName="([^"]+)"', f.read())
    if not m:
        raise RuntimeError('读不到 versionName: %s' % path)
    return m.group(1)


def ver(project_root=None):
    """带 v 前缀的版本号，用于拼文件名：v6.5"""
    return 'v' + manifest_version(project_root)


def dev_apk(project_root=None):
    root = project_root or DEV_ROOT
    return os.path.join(root, 'dist', 'DeepSeekPet_%s_rebuilt.apk' % ver(root))


def rel_apk():
    return os.path.join(REL_ROOT, 'dist',
                        'DeepSeekPet_%s_release.apk' % ver())


def zip_paths():
    """三个交付 zip 的路径。"""
    v = ver()
    return [
        os.path.join(DELIVERY_DIR, 'DeepSeekPet_%s_开发版.zip' % v),
        os.path.join(DELIVERY_DIR, 'DeepSeekPet_%s_正式版.zip' % v),
        os.path.join(DELIVERY_DIR, 'DeepSeekPet_%s_项目移植包.zip' % v),
    ]


if __name__ == '__main__':
    print('version    =', manifest_version())
    print('dev apk    =', dev_apk())
    print('rel apk    =', rel_apk())
    for p in zip_paths():
        print('zip        =', p, '(存在)' if os.path.exists(p) else '(缺)')
