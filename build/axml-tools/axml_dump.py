# -*- coding: utf-8 -*-
import struct, sys

def parse_string_pool(b, off):
    typ, hsz, csz = struct.unpack_from('<HHI', b, off)
    assert typ == 0x0001
    scount, stcount, flags, sstart, ststart = struct.unpack_from('<IIIII', b, off + 8)
    utf8 = (flags & (1 << 8)) != 0
    offs = struct.unpack_from('<%dI' % scount, b, off + 28)
    base = off + 28 + scount * 4 + stcount * 4
    out = []
    for o in offs:
        if o == 0xFFFFFFFF:
            out.append(None); continue
        p = base + o
        if utf8:
            n = b[p]; p += 1
            if n & 0x80: p += 1
            m = b[p]; p += 1
            if m & 0x80:
                m = ((m & 0x7f) << 8) | b[p]; p += 1
            out.append(b[p:p + m].decode('utf-8', 'replace'))
        else:
            n = struct.unpack_from('<H', b, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', b, p)[0]; p += 2
            out.append(b[p:p + n * 2].decode('utf-16-le', 'replace'))
    return out

path = sys.argv[1]
b = open(path, 'rb').read()
print("file size", len(b))
root_sz = struct.unpack_from('<I', b, 4)[0]
print("root chunk size field =", root_sz)
strs = parse_string_pool(b, 8)
off = 8
steps = 0
while off < len(b):
    steps += 1
    if steps > 500:
        print("!! LOOP GUARD at off=%d" % off); break
    t, hs, cs = struct.unpack_from('<HHI', b, off)
    print("  off=%5d type=0x%04x hdsize=%d chunksize=%d" % (off, t, hs, cs))
    if cs <= 0:
        print("  !! zero size, abort"); break
    if t == 0x0102:
        name_i = struct.unpack_from('<I', b, off + 20)[0]
        astart = struct.unpack_from('<H', b, off + 24)[0]
        acount = struct.unpack_from('<H', b, off + 28)[0]
        abase = off + 16 + astart
        parts = []
        for i in range(acount):
            ao = abase + i * 20
            ans, anm, araw = struct.unpack_from('<III', b, ao)
            tsz, tres, tdt = struct.unpack_from('<HBB', b, ao + 12)
            tdo = ao + 16
            an = strs[anm] if anm != 0xFFFFFFFF and anm < len(strs) else '?'
            if tdt == 0x03:
                v = strs[struct.unpack_from('<I', b, tdo)[0]]
            elif tdt == 0x12:
                v = bool(struct.unpack_from('<I', b, tdo)[0])
            elif tdt == 0x10:
                v = hex(struct.unpack_from('<I', b, tdo)[0])
            else:
                v = hex(tdt)
            if an in ('name', 'foregroundServiceType', 'exported', 'noHistory', 'versionCode', 'versionName', 'minSdkVersion', 'targetSdkVersion'):
                parts.append("%s=%r" % (an, v))
        print("       <%s %s>" % (strs[name_i], " ".join(parts)))
    elif t == 0x0103:
        print("       </>")
    off += cs
print("done, final off =", off)
