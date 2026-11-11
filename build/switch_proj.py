# -*- coding: utf-8 -*-
"""Switch build target without BOM."""
import sys
path = sys.argv[1] if len(sys.argv) > 1 else 'D:\\petbuild'
target = 'D:\\用户\\DS\\pet-ui-redesign\\tools\\project_path.txt'
with open(target, 'wb') as f:
    f.write(path.encode('utf-8'))
    f.write(b'\n')
print('project path ->', path)
