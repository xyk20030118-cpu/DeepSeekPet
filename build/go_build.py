# -*- coding: utf-8 -*-
"""Build launcher (CJK path via runpy; pwsh mangles CJK argv).

Usage: python go_build.py [dev|release]
"""
import os
import runpy
import sys

SCRIPT = '\u0044\u003a\\\u7528\u6237\\DS\\pet-ui-redesign\\tools\\build_win.py'
variant = sys.argv[1] if len(sys.argv) > 1 else 'dev'
if variant == 'release':
    os.environ['DSP_VARIANT'] = 'release'
else:
    os.environ.pop('DSP_VARIANT', None)
os.environ.pop('DSP_BUNDLE_ASR', None)  # 瘦身默认：不内置语音模型
runpy.run_path(SCRIPT, run_name='__main__')
