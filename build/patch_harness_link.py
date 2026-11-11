# -*- coding: utf-8 -*-
"""把 harness 载荷里的 link() 换成"失败则 rename"。

背景（真机实测，2026-10）：
    Android 的 SELinux 禁止 untrusted_app 在 app 私有目录创建硬链接
    （`allow untrusted_app app_data_file:file ...` 里没有 `lnk_file create`）。
    实测：目录 777、文件 666、访客内假 root 仍然 `Permission denied`；
    同一套 proot 用 su(root) 跑则全 OK —— 差别只有"谁在跑"。

    上游 DSH 保存会话是"写 .tmp → link(tmp → final) → 删 tmp"的原子发布，
    所以在安卓上必然 EACCES。实测 rename 可行（同文件系统内同样原子）。

打的两处（`@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js`）：
    1. `publishCurrentExclusive()` —— 会话**后续保存**的发布
    2. `materializePosix()`       —— 会话**新建**时的 header+首批事件发布
并确保 `rename` 出现在 `node:fs/promises` 的 import 里。

用法（对载荷源码树，**打包前**跑一次）：
    python build/patch_harness_link.py <harness 树根> [<harness 树根> ...]
例：
    python build/patch_harness_link.py ^
        D:\\用户\\DS\\proot-work\\harness-linux-arm64 ^
        D:\\用户\\DS\\proot-work\\harness-linux
成功后再 tar + pack_engine_payload + make_offline_bundle + build_win。
"""
import io
import os
import sys

REL = os.path.join('node_modules', '@deepseek-ai', 'dsh-session-persistence-jsonl', 'lib', 'index.js')

IMP_OLD = ('import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, '
           'rm, stat, truncate } from "node:fs/promises";')
IMP_NEW = ('import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, '
           'rename, rm, stat, truncate } from "node:fs/promises";')

# 1) publishCurrentExclusive 的 link
OLD1 = ('\t\t/* v8 ignore next -- the filesystem error is already complete. */\n'
        '\t\tthrow error;\n'
        '\t}\n'
        '\tawait syncDirectory(dirname(currentPath), internals);\n'
        '\treturn true;\n'
        '}')
NEW1 = ('\t\t// AndroidSELinuxNoHardLink: app 私有目录里 link() 被系统拒绝'
        '（EACCES/EPERM/EOPNOTSUPP），回退成同文件系统内的 rename。\n'
        '\t\tconst code = error && error.code;\n'
        '\t\tif (code === "EACCES" || code === "EPERM" || code === "EOPNOTSUPP" || code === "ENOTSUP") {\n'
        '\t\t\tawait internals.fs.rename(staged, currentPath);\n'
        '\t\t\tawait syncDirectory(dirname(currentPath), internals);\n'
        '\t\t\treturn true;\n'
        '\t\t}\n'
        '\t\t/* v8 ignore next -- the filesystem error is already complete. */\n'
        '\t\tthrow error;\n'
        '\t}\n'
        '\tawait syncDirectory(dirname(currentPath), internals);\n'
        '\treturn true;\n'
        '}')

# 2) materializePosix 的 link
OLD2 = ('\t\tlet linked = false;\n'
        '\t\ttry {\n'
        '\t\t\tawait link(tmp, finalPath);\n'
        '\t\t\tlinked = true;\n'
        '\t\t} finally {\n'
        '\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */\n'
        '\t\t\tif (!linked) await rm(tmp, { force: true });\n'
        '\t\t}')
NEW2 = ('\t\tlet linked = false;\n'
        '\t\ttry {\n'
        '\t\t\tawait link(tmp, finalPath);\n'
        '\t\t\tlinked = true;\n'
        '\t\t} catch (error) {\n'
        '\t\t\t// AndroidSELinuxNoHardLink2: 同上报错原因，退回 rename（同文件系统内原子）。\n'
        '\t\t\tconst code = error && error.code;\n'
        '\t\t\tif (code === "EACCES" || code === "EPERM" || code === "EOPNOTSUPP" || code === "ENOTSUP") {\n'
        '\t\t\t\tawait rename(tmp, finalPath);\n'
        '\t\t\t\tlinked = true;\n'
        '\t\t\t} else {\n'
        '\t\t\t\tthrow error;\n'
        '\t\t\t}\n'
        '\t\t} finally {\n'
        '\t\t\t/* v8 ignore next -- link failure is the TOCTOU/IO race guarded above; not reachable in test */\n'
        '\t\t\tif (!linked) await rm(tmp, { force: true });\n'
        '\t\t}')


def patch_tree(root):
    p = os.path.join(root, REL)
    if not os.path.isfile(p):
        return 'missing ' + p
    s = io.open(p, encoding='utf-8', newline='').read()
    done = []

    if 'rename, rm, stat' not in s and IMP_OLD in s:
        s = s.replace(IMP_OLD, IMP_NEW, 1)
        done.append('import')

    if 'AndroidSELinuxNoHardLink2' not in s:
        if OLD2 in s:
            s = s.replace(OLD2, NEW2, 1)
            done.append('materializePosix')
        elif 'AndroidSELinuxNoHardLink2' not in s:
            return 'ANCHOR-MISS materializePosix'

    if 'AndroidSELinuxNoHardLink:' not in s:
        if OLD1 in s:
            s = s.replace(OLD1, NEW1, 1)
            done.append('publishCurrentExclusive')
        else:
            return 'ANCHOR-MISS publishCurrentExclusive'

    io.open(p, 'w', encoding='utf-8', newline='').write(s)
    return 'patched: ' + (', '.join(done) if done else 'already')


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    bad = 0
    for root in argv[1:]:
        r = patch_tree(root)
        print(os.path.basename(root), '->', r)
        if r.startswith('ANCHOR-MISS') or r.startswith('missing'):
            bad += 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
