# -*- coding: utf-8 -*-
"""通用：把 AXML 清单的 versionCode / versionName 原地改成新值。
   要求新旧 versionName 长度一致（字符串池原地替换，不动任何偏移）。"""
import struct, sys

SRC  = sys.argv[1]
DST  = sys.argv[2]
NEW_CODE = int(sys.argv[3])
NEW_NAME = sys.argv[4]

b = bytearray(open(SRC, 'rb').read())

def parse_string_pool(b, off):
    typ, hsz, csz = struct.unpack_from('<HHI', b, off)
    assert typ == 0x0001, "不是字符串池"
    scount, stcount, flags, sstart, ststart = struct.unpack_from('<IIIII', b, off + 8)
    utf8 = (flags & (1 << 8)) != 0
    offs = struct.unpack_from('<%dI' % scount, b, off + 28)
    base = off + 28 + scount * 4 + stcount * 4
    return offs, base, utf8, scount

def spool_str(b, offs, base, i):
    p = base + offs[i]
    n = struct.unpack_from('<H', b, p)[0]
    p += 2
    if n & 0x8000:
        n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', b, p)[0]
        p += 2
    return p, n

offs, sbase, utf8, scount = parse_string_pool(b, 8)
assert not utf8, "本工具只处理 UTF-16 字符串池"

# ---- 找旧的 versionName 字符串 ----
old_name = None
chunks = []
off = 8
while off < len(b):
    t, hs, cs = struct.unpack_from('<HHI', b, off)
    chunks.append((off, t, hs, cs))
    off += cs

vc_off = None
for (co, t, hs, cs) in chunks:
    if t != 0x0102:
        continue
    name_i = struct.unpack_from('<I', b, co + 20)[0]
    # 只认 <manifest>
    p, n = spool_str(b, offs, sbase, name_i)
    if b[p:p + n * 2].decode('utf-16-le') != 'manifest':
        continue
    astart = struct.unpack_from('<H', b, co + 24)[0]
    acount = struct.unpack_from('<H', b, co + 28)[0]
    abase = co + 16 + astart
    for i in range(acount):
        ao = abase + i * 20
        anm = struct.unpack_from('<I', b, ao + 4)[0]
        if anm == 0xFFFFFFFF:
            continue
        p2, n2 = spool_str(b, offs, sbase, anm)
        aname = b[p2:p2 + n2 * 2].decode('utf-16-le', 'replace')
        if aname == 'versionCode':
            vc_off = ao + 16
        elif aname == 'versionName':
            str_i = struct.unpack_from('<I', b, ao + 16)[0]
            p3, n3 = spool_str(b, offs, sbase, str_i)
            old_name = b[p3:p3 + n3 * 2].decode('utf-16-le', 'replace')
            assert n3 == len(NEW_NAME), \
                "versionName 长度必须一致: '%s'(%d) vs '%s'(%d)" % (old_name, n3, NEW_NAME, len(NEW_NAME))
            b[p3:p3 + n3 * 2] = NEW_NAME.encode('utf-16-le')
assert vc_off is not None, "没找到 versionCode"

cur_code = struct.unpack_from('<I', b, vc_off)[0]
struct.pack_into('<I', b, vc_off, NEW_CODE)
print("versionCode %d -> %d" % (cur_code, NEW_CODE))
print("versionName '%s' -> '%s'" % (old_name, NEW_NAME))

open(DST, 'wb').write(bytes(b))
print("saved %s (%d bytes)" % (DST, len(b)))
