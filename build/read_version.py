"""从 APK 的二进制 AndroidManifest.xml 里读出 versionCode / versionName。

为什么需要它：交付包的文件名里带版本号（`DeepSeekPet_v6.1_正式版.zip`），
而如果这个号**硬编码在打包脚本里**，就必然出现"包里是 6.1、文件名写 6.0"
这类脱节 —— 每次升版本都得记得手改脚本。

改成从 APK 读：文件名跟随**实际打出来的包**，不可能不一致。
升级时只改 AndroidManifest.xml 一处即可。

★ 偏移量全部照抄 build/axml-tools/axml_set_version.py ——
那个脚本是本工程**已经在用**的（改版本号就靠它），偏移经过实测。
我自己第一版按"通用 AXML 结构"推的偏移是错的（把字符串池头当成 8 字节、
把 attr 起点算错），直接 IndexError。
**同一工程里已经验证过的解析逻辑，不要重新发明。**
"""
import struct
import sys
import zipfile


def parse_string_pool(b, off):
    typ, _hsz, _csz = struct.unpack_from('<HHI', b, off)
    assert typ == 0x0001, "不是字符串池"
    scount, stcount, flags, _sstart, _ststart = struct.unpack_from('<IIIII', b, off + 8)
    utf8 = (flags & (1 << 8)) != 0
    offs = struct.unpack_from('<%dI' % scount, b, off + 28)
    base = off + 28 + scount * 4 + stcount * 4
    return offs, base, utf8, scount


def sget(b, offs, base, i):
    """返回第 i 个字符串的 (数据起点, 长度)。UTF-16 / UTF-8 的长度前缀格式相同。"""
    p = base + offs[i]
    n = struct.unpack_from('<H', b, p)[0]
    p += 2
    if n & 0x8000:
        n = ((n & 0x7FFF) << 16) | struct.unpack_from('<H', b, p)[0]
        p += 2
    return p, n


def read_versions(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        b = z.read('AndroidManifest.xml')

    offs, sbase, utf8, scount = parse_string_pool(b, 8)

    def tostr(i):
        p, n = sget(b, offs, sbase, i)
        if utf8:
            return b[p:p + n].decode('utf-8', 'replace')
        return b[p:p + n * 2].decode('utf-16-le', 'replace')

    ver_code, ver_name = None, None
    off = 8
    while off + 8 <= len(b):
        t, _hs, cs = struct.unpack_from('<HHI', b, off)
        if cs <= 0:
            break
        if t == 0x0102:                      # START_ELEMENT
            name_i = struct.unpack_from('<I', b, off + 20)[0]
            if tostr(name_i) == 'manifest':
                astart = struct.unpack_from('<H', b, off + 24)[0]
                acount = struct.unpack_from('<H', b, off + 28)[0]
                abase = off + 16 + astart
                for i in range(acount):
                    ao = abase + i * 20
                    anm = struct.unpack_from('<I', b, ao + 4)[0]
                    if anm == 0xFFFFFFFF:
                        continue
                    aname = tostr(anm)
                    if aname == 'versionCode':
                        ver_code = struct.unpack_from('<I', b, ao + 16)[0]
                    elif aname == 'versionName':
                        ver_name = tostr(struct.unpack_from('<I', b, ao + 16)[0])
        off += cs
    return ver_code, ver_name


if __name__ == '__main__':
    for p in sys.argv[1:]:
        c, n = read_versions(p)
        print('%s : versionCode=%s versionName=%s' % (p, c, n))
