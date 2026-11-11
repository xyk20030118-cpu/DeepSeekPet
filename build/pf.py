"""可靠地设置/读取 App 的 pref。

★ 为什么要"先 force-stop 再改文件"：
  App 在退出时会把内存里的 SharedPreferences 回写磁盘，
  直接改 XML 会被它覆盖（STATE.md 记过这个坑）。

★ 为什么不用 `sed`：
  键**不存在**时 `sed s|...|...|` 静默不替换，看起来"执行成功"但什么都没改 ——
  我刚刚就被这个坑了：三次 A/B 跑的都是同一个默认值，
  却显示成"三个档位 CPU 一样"，差点得出错误结论。

用法:
    python build/pf.py get <键> [键...]
    python build/pf.py set <键> <值>        # 值为 true/false 时写 boolean，整数写 int
"""
import re
import subprocess
import sys

ADB = r'D:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.deepseek.pet'
XML = '/data/data/%s/shared_prefs/deepseek_pet.xml' % PKG
LOCAL = r'D:\petport_lab\petbuild\build\_prefs.xml'
sys.stdout.reconfigure(encoding='utf-8', errors='replace')

# ★★ 必须钉死设备：用户真机就接在这台机器上（2026-10-08 实测）。
#   不钉 `-s` 时，两台设备同时在线 ⇒ adb 直接报 "more than one device"，
#   而本模块的调用方**只看返回文本**、不看退出码 ⇒ 所有读写静默失败：
#   表现成"开关设了却没生效 / 复读是空的"，极易误判成产品 bug。
#   （轮次㉖ 联网检索的设备验证第一遍就是这么被骗的。）
DEV = 'emulator-5554'


def sh(*a):
    return subprocess.run([ADB, '-s', DEV] + list(a), capture_output=True)


def stop():
    sh('shell', 'am', 'force-stop', PKG)
    # 等进程真的死掉，否则回写还会发生
    for _ in range(20):
        if not sh('shell', 'pidof', PKG).stdout.decode().strip():
            return True
        import time
        time.sleep(0.5)
    return False


def load():
    sh('shell', 'cp', XML, '/sdcard/_p.xml')
    sh('pull', '/sdcard/_p.xml', LOCAL)
    try:
        return open(LOCAL, encoding='utf-8').read()
    except OSError:
        return None


def save(body):
    open(LOCAL, 'w', encoding='utf-8', newline='\n').write(body)
    sh('push', LOCAL, '/sdcard/_p.xml')
    # 先取出原文件的 owner/mode，改完再原样恢复 ——
    # 绝不能像第一版那样硬写 u0_a219：不同设备/重装后 uid 会变，
    # 写错 owner 会导致 App **读不到自己的设置**（表现为"改完没生效"，
    # 而且很难查）。
    #
    # ★ toybox 的 stat 一次只认一个格式符：写 `-c '%U:%G %a'` 会把
    #   "%a" 当成**文件名**（报 "No such file or directory"）。
    #   必须分两次调用。
    owner = sh('shell', 'stat', '-c', '%U:%G', XML).stdout.decode().strip()
    mode = sh('shell', 'stat', '-c', '%a', XML).stdout.decode().strip()
    sh('shell', 'cp', '/sdcard/_p.xml', XML)
    if owner and mode:
        sh('shell', 'chown', owner, XML)
        sh('shell', 'chmod', mode, XML)
    else:
        print('  !! 取不到原 owner/mode (owner=%r mode=%r)，谨慎' % (owner, mode))
    # ★ SELinux 上下文也要还原：cp 出来的新文件若带了调用方的类别（shell/root），
    #   应用读自己的 pref 会**静默失败**（读不到就全用默认值 —— 表现成"设了没生效"）。
    sh('shell', 'restorecon', XML)


def wipe_local():
    """把本地暂存副本擦成空壳。

    ★★ 为什么要专门有这一步（轮次㉘ 抓到的**真泄漏**）：
    `load()` 会把设备上的 pref 文件整份 pull 到 `build/_prefs.xml`；而交付的
    **项目移植包会把整个 build/ 打进去** ⇒ 用户填过的 API/搜索 Key 会跟着源码包发出去。
    本轮就是这么中的：设备验证把 Kimi Key 写进 pref → 拉到 `_prefs.xml` → 进了源码 zip。
    ⇒ ① 设备脚本用完必须 `wipe_local()`；② 打包前清 `_prefs.xml`；
       ③ `check_pack_contents.py` 里加**密钥扫描**（进去就报红）。
    """
    try:
        open(LOCAL, 'w', encoding='utf-8', newline='\n').write(
            '<!-- 已擦除（见 pf.wipe_local 的说明）：这里曾经是设备 pref 的副本，可能含用户的 Key -->\n')
    except OSError:
        pass


def get(body, key):
    """读一个 pref 的值。

    ★ **Android 写 string 用的是元素内容写法，不是 value 属性**：
          <string name="chat_provider">zhipu</string>
      而 int/boolean 用属性：<int name="x" value="5" />
      两种写法都要认。

      踩过的坑：本函数原来只看 `value="..."`，于是 **App 自己写出来的
      所有 string 都被读成 None** —— 表现为"我明明设过却显示未设置"，
      极易误判成"设置没生效"，进而去改根本没问题的代码。
    """
    # 1) 属性写法：<type name="k" value="v" />
    m = re.search(
        r'<(int|boolean|string|float|long|set) name="%s"[^>]*?value="([^"]*)"'
        % re.escape(key), body)
    if m:
        return m.group(2)
    # 2) 元素内容写法：<string name="k">v</string>
    m = re.search(
        r'<(int|boolean|string|float|long|set) name="%s"[^>]*>(.*?)</\1>'
        % re.escape(key), body, re.S)
    if m:
        return m.group(2)
    # 3) 空元素内容：<string name="k"></string>
    m = re.search(
        r'<(int|boolean|string|float|long|set) name="%s"[^>]*/>' % re.escape(key),
        body)
    if m:
        return ''
    return None


def infer_tag(val, explicit=None):
    """推断该用哪种 XML 标签。

    ★ 这里修过一个**静默数据丢失**的 bug（代价：一轮排查）：
      原实现是 `'boolean' if val in ('true','false') else 'int'` ——
      于是**字符串值**（如 chat_provider="kimi"）被写成
      `<int name="chat_provider" value="kimi" />`。

      这种"类型是 int 但值不是数字"的条目：
        - 我的验证正则只看 value，**报告成功**（假通过）；
        - 但 App 一启动，SharedPreferences 解析时判定非法，
          **直接丢弃该键并重写文件**。
      结果：chat_provider / api_base_url 消失，而正确写成 string 的
      chat_model / api_key 活了下来 —— 现象自相矛盾，极难定位。

      现在：只有**纯数字**才写 int，其余一律 string。
    """
    if explicit:
        return explicit
    if val in ('true', 'false'):
        return 'boolean'
    if re.fullmatch(r'-?\d+', val):
        return 'int'
    if re.fullmatch(r'-?\d+\.\d+', val):
        return 'float'
    return 'string'


def setval(body, key, val, explicit_tag=None):
    """存在就改、不存在就新增（放在 </map> 前）。

    ★ **string 必须写成元素内容写法**：`<string name="k">v</string>`
      而不是 `<string name="k" value="v" />`。

      实测教训：Android 的 SharedPreferences **只认元素内容写法**。
      用属性写法写进去的 string，App 一启动就被判定非法并**丢弃该键**
      （连值都不剩），而我的验证只看"写进去了"⇒ 报告成功。
      现象是"设了 API 地址，重启后设置没了"—— 极难定位。

      int/boolean 仍用 value 属性（Android 自己就是这么写的）。

    ★ 保留原条目的**标签类型**：类型写错同样会被丢弃。
    """
    m = re.search(r'<(int|boolean|string|float|long|set) name="%s"' % re.escape(key),
                  body)
    if m and not explicit_tag:
        tag = m.group(1)          # 已有条目：沿用它的类型
    else:
        tag = infer_tag(val, explicit_tag)

    if tag == 'string':
        # 元素内容写法（Android 原生）
        new = '<string name="%s">%s</string>' % (key, val)
    else:
        new = '<%s name="%s" value="%s" />' % (tag, key, val)

    # 匹配"从标签头到第一个 />"，把该条目整体换成规范写法。
    # 注意要匹配**任意**标签类型与**两种写法**：原条目可能写法不同。
    pat = (r'<(?:int|boolean|string|float|long|set) name="%s"'
           r'(?:[^>]*/>|[^>]*>.*?</(?:int|boolean|string|float|long|set)>)'
           % re.escape(key))
    if re.search(pat, body, re.S):
        body = re.sub(pat, new, body, count=1, flags=re.S)
    else:
        body = body.replace('</map>', '    ' + new + '\n</map>')

    # ★ 清理孤立的 `/>`（sed 时代留下的 `value="1" /> />`）。
    body = re.sub(r'(/>)[ \t]*/>[ \t]*(?=\r?\n)', r'\1', body)
    return body


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    cmd = sys.argv[1]
    if cmd == 'get':
        b = load()
        if b is None:
            sys.exit('读不到 pref 文件（adb root 掉了吗？）')
        for k in sys.argv[2:]:
            print('%s = %s' % (k, get(b, k)))
        return
    if cmd == 'set':
        key, val = sys.argv[2], sys.argv[3]
        if not stop():
            sys.exit('App 没能停下来，拒绝改 pref（会被回写覆盖）')
        b = load()
        if b is None:
            sys.exit('读不到 pref 文件')
        before = get(b, key)
        b = setval(b, key, val)
        # ★ 写盘前先验证是合法 XML。
        #   写坏过：留下 `value="2" /> />` 这种无效结构，
        #   导致 App 解析整个 prefs 失败、**所有设置**悄悄回默认值 ——
        #   而表面上"写入成功"了，极难排查。宁可在这一步就失败。
        import xml.etree.ElementTree as ET
        try:
            ET.fromstring(b)
        except ET.ParseError as e:
            sys.exit('!! 生成的不是合法 XML（拒绝写入）: %s' % e)
        # ★ 还要校验"类型与值自洽"。
        #   只校验 XML 合法性是不够的：`<int value="kimi" />` 是合法 XML，
        #   但 App 的 SharedPreferences 会判定非法并**丢弃该键**
        #   （见 infer_tag 的说明 —— 这个坑让两个键静默消失，查了一轮）。
        for tm in re.finditer(
                r'<(int|float|long) name="([^"]*)"[^>]*value="([^"]*)"', b):
            t, k2, v = tm.group(1), tm.group(2), tm.group(3)
            num_ok = (re.fullmatch(r'-?\d+', v) if t in ('int', 'long')
                      else re.fullmatch(r'-?\d+(\.\d+)?', v))
            if not num_ok:
                sys.exit('!! 类型与值不符：<%s name="%s" value="%s"> '
                         '—— App 会丢弃这个键（拒绝写入）' % (t, k2, v))
        save(b)
        after = get(load(), key)
        print('%s: %s -> %s' % (key, before, after))
        if after != val:
            sys.exit('!! 写入没生效')
        return
    sys.exit(__doc__)


if __name__ == '__main__':
    main()