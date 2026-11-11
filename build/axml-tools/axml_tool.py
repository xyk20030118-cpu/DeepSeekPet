# -*- coding: utf-8 -*-
import struct

def parse_string_pool(b, off):
    typ, hsz, csz = struct.unpack_from('<HHI', b, off)
    assert typ == 0x0001, "not string pool: 0x%04x"%typ
    scount, stcount, flags, sstart, ststart = struct.unpack_from('<IIIII', b, off+8)
    utf8 = (flags & (1<<8)) != 0
    offs = struct.unpack_from('<%dI'%scount, b, off+28)
    base = off + 28 + scount*4 + stcount*4
    out=[]
    for o in offs:
        if o == 0xFFFFFFFF:
            out.append(None); continue
        p = base+o
        if utf8:
            n=b[p]; p+=1
            if n & 0x80: p+=1
            m=b[p]; p+=1
            if m & 0x80:
                m=((m & 0x7f)<<8)|b[p]; p+=1
            out.append(b[p:p+m].decode('utf-8','replace'))
        else:
            n=struct.unpack_from('<H',b,p)[0]; p+=2
            if n & 0x8000:
                n=((n & 0x7fff)<<16)|struct.unpack_from('<H',b,p)[0]; p+=2
            out.append(b[p:p+n*2].decode('utf-16-le','replace'))
    return out

def elements(b):
    off=8
    strings=None
    res=[]
    while off < len(b):
        t,hs,cs = struct.unpack_from('<HHI', b, off)
        if t==0x0001:
            strings=parse_string_pool(b,off)
        elif t==0x0102:
            ns,name,astart,asize,acount = struct.unpack_from('<IIHHH', b, off+16)
            abase = off+16+astart
            attrs=[]
            for i in range(acount):
                ao = abase+i*asize
                ans,anm,araw = struct.unpack_from('<III', b, ao)
                tsz,tres,tdt = struct.unpack_from('<HBB', b, ao+12)
                attrs.append((ao,ans,anm,araw,tdt,ao+16))
            res.append((off,name,attrs,strings))
        off += cs
    return res
