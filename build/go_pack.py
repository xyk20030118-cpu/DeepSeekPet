# -*- coding: utf-8 -*-
"""Run pack_delivery.py with CJK paths handled internally."""
import os
import runpy
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import dev_apk, rel_apk  # noqa: E402

TOOLS = 'D:\\用户\\DS\\pet-ui-redesign\\tools'
OUT = 'D:\\桌宠移植包'
dev = dev_apk()
rel = rel_apk()
src = r'D:\petport_lab\petbuild'
sys.argv = ['pack_delivery.py', dev, rel, OUT, src]
runpy.run_path(os.path.join(TOOLS, 'pack_delivery.py'), run_name='__main__')
