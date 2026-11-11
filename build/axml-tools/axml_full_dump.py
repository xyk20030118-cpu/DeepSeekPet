#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AXML 完整解析器：打印元素树 + 全部属性（含名称与值）。

用法: python3 axml_full_dump.py <manifest.axml>
"""
import struct, sys

RES_NULL = 0x0000
RES_STRING = 0x0003
RES_REFERENCE = 0x0001
RES_ATTRIBUTE = 0x0002
RES_FLOAT = 0x0004
RES_INT_DEC = 0x0010
RES_INT_HEX = 0x0011
RES_INT_BOOLEAN = 0x0012


def parse_string_pool(buf, off):
    hdr = struct.unpack_from('<HHI', buf, off)
    hdsize, strcount, stylecount = hdr[0], struct.unpack_from('<I', buf, off + 8)[0], \
        struct.unpack_from('<I', buf, off + 12)[0]
    offsets = struct.unpack_from('<%dI' % strcount, buf, off + 28)
    data_off = off + struct.unpack_from('<I', buf, off + 20)[0]
    utf8 = (struct.unpack_from('<I', buf, off + 16)[0] & (1 << 8)) != 0
    out = []
    for o in offsets:
        p = data_off + o
        if utf8:
            # u8 长度（若高位为1则是双字节长度）
            n = buf[p]
            q = p + 1
            if n & 0x80:
                n = ((n & 0x7f) << 8) | buf[p + 1]
                q = p + 2
            if n == 0:
                out.append('')
                continue
            raw = buf[q:q + n]
            # 去掉末尾 0 与 UTF-8 长度前缀
            try:
                s = raw.decode('utf-8')
            except Exception:
                s = raw.decode('utf-8', 'replace')
            out.append(s.rstrip('\x00'))
        else:
            n = struct.unpack_from('<H', buf, p)[0]
            raw = buf[p + 2:p + 2 + n * 2]
            out.append(raw.decode('utf-16le').rstrip('\x00'))
    return out


def main(path):
    buf = open(path, 'rb').read()
    print("file size %d" % len(buf))
    total = struct.unpack_from('<I', buf, 4)[0]
    strings = []
    off = 8
    # 先找字符串池
    while off < total:
        t, hs, cs = struct.unpack_from('<HHI', buf, off)
        if t == 0x0001:
            strings = parse_string_pool(buf, off)
        off += cs
    print("字符串池 %d 项" % len(strings))

    def S(i):
        return strings[i] if 0 <= i < len(strings) else '<%d>' % i

    def fmt_attr_value(vtype, data):
        vtype = buf[p + 15]
        if vtype == RES_STRING:
            return "'%s'" % S(data)
        if vtype == RES_INT_BOOLEAN:
            return 'True' if data else 'False'
        if vtype == RES_INT_DEC:
            return "%d" % data
        if vtype == RES_INT_HEX:
            return "0x%x" % data
        if vtype == RES_REFERENCE:
            return "@ref/0x%08x" % data
        if vtype == RES_ATTRIBUTE:
            return "?attr/0x%08x" % data
        if vtype == RES_FLOAT:
            return repr(struct.unpack('<f', struct.pack('<I', data))[0])
        return "vtype=%d data=%d" % (vtype, data)

    off = 8
    depth = 0
    while off < total:
        t, hs, cs = struct.unpack_from('<HHI', buf, off)
        if cs == 0:
            break
        if t == 0x0102:  # START_ELEMENT
            ns, name = struct.unpack_from('<iI', buf, off + 16)
            attr_count = struct.unpack_from('<H', buf, off + 28)[0]
            attr_start = struct.unpack_from('<H', buf, off + 24)[0]
            # 元素名
            print("  " * depth + "<%s" % S(name))
            base = off + 16 + attr_start
            for i in range(attr_count):
                p = base + i * 20
                a_ns, a_name, a_raw, a_vt, a_data = struct.unpack_from('<iiiII', buf, p)
                # 属性名前缀
                if a_ns >= 0:
                    nm = "%s:%s" % (S(a_ns).rsplit('/', 1)[-1] if 'android' in S(a_ns) else 'x', S(a_name))
                else:
                    nm = S(a_name)
                print("  " * depth + "    %s=%s" % (nm, fmt_attr_value(a_vt, a_data)))
            print("  " * depth + ">")
            depth += 1
        elif t == 0x0103:  # END_ELEMENT
            depth -= 1
            ns, name = struct.unpack_from('<iI', buf, off + 16)
            print("  " * depth + "</%s>" % S(name))
        off += cs
    print("done, final off = %d" % off)


if __name__ == '__main__':
    main(sys.argv[1])
