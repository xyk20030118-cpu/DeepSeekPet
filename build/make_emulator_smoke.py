# -*- coding: utf-8 -*-
"""给 x86_64 模拟器出一个"能装上去看看界面"的 smoke 包（剔掉 lib/ 再重新签名）。

为什么要它：主包的 `lib/` 只有 arm64-v8a，而手上的 AVD 是 x86_64，
直接装会 `INSTALL_FAILED_NO_MATCHING_ABIS`。剔掉 `lib/` 之后 UI / 页面 / 交互都能跑，
只是本地推理（JNI 的 GGUF 与 sherpa 那批 .so）在模拟器上不可用。

用法：
    python build\\make_emulator_smoke.py
产物：
    dist\\DeepSeekPet_<ver>_emulator-smoke.apk（用 debug.keystore 签）
"""
import io
import os
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(ROOT, 'dist')
TOOL = os.path.join(ROOT, 'build', 'toolchain')
JAVA = os.environ.get('JAVA_HOME', '') and os.path.join(os.environ['JAVA_HOME'], 'bin', 'java.exe')
if not JAVA or not os.path.isfile(JAVA):
    JAVA = 'java'
SIGNER = os.path.join(TOOL, 'apksigner.jar')
KS = os.path.join(ROOT, 'debug.keystore')

VER = 'v8.0'
SRC = os.path.join(DIST, 'DeepSeekPet_%s_rebuilt.apk' % VER)
OUT = os.path.join(DIST, 'DeepSeekPet_%s_emulator-smoke.apk' % VER)
RAW = os.path.join(ROOT, 'build', 'work_win', 'smoke-raw.apk')


def main():
    for f in (SRC, SIGNER, KS):
        if not os.path.isfile(f):
            raise SystemExit('missing: ' + f)
    os.makedirs(os.path.dirname(RAW), exist_ok=True)   # 套件清理会删掉 work_win，先保证目录在
    if os.path.exists(RAW):
        os.remove(RAW)
    with zipfile.ZipFile(SRC) as z, zipfile.ZipFile(RAW, 'w', zipfile.ZIP_DEFLATED) as o:
        for it in z.infolist():
            n = it.filename
            if n.startswith('lib/'):
                continue
            up = n.upper()
            if n.startswith('META-INF/') and up.endswith(('.SF', '.RSA', '.DSA', '.MF')):
                continue
            o.writestr(it, z.read(n))
    if os.path.exists(OUT):
        os.remove(OUT)
    subprocess.run([JAVA, '-jar', SIGNER, 'sign',
                    '--ks', KS, '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
                    '--ks-key-alias', 'androiddebugkey', '--out', OUT, RAW], check=True)
    print('OK -> %s (%.1f MB)' % (OUT, os.path.getsize(OUT) / 1048576.0))


if __name__ == '__main__':
    main()
