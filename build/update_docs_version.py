# -*- coding: utf-8 -*-
"""把文档里**过期的版本号/产物文件名**批量更新为当前版本。

只改"命令/路径/产物名"这类**当前值**，绝不碰历史记录：
  - BUILD_双版本.md 第九节「版本演进记录」表格里的旧版本行保持原样
  - 各文档里"从 v5.36 起…"这类历史叙述保持原样
  - 交付说明.md 的 SHA256 指纹保持原样（那是当时那版的记录）

★ 本脚本原先把所有 `v6.1` **写死在替换表里**，于是升到 v6.5 时它自己就过期了
  （从 v5.6 找起，一个都匹配不上，静默"改了 0 行"）。现在改成：
  版本号一律从 `AndroidManifest.xml` 读（单一来源，见 artifacts.py），
  旧版本号用**扫描**得出，不再硬编码。
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from artifacts import manifest_version  # noqa: E402

ROOT = os.environ.get('DSP_DEV_ROOT', r'D:\petport_lab\petbuild')
NEW = manifest_version()

# 含历史记录的行出现下列关键词就跳过
HISTORY_MARKERS = ('版本演进记录', '从 v5.', '首次双版本分离', '| v5.', '| v6.', '| v7.')


def scan_old_versions():
    """扫出文档里出现过的版本号，取"当前之外"的那些作为旧值。"""
    seen = set()
    for fname in os.listdir(ROOT):
        if not fname.endswith('.md'):
            continue
        p = os.path.join(ROOT, fname)
        try:
            txt = io.open(p, encoding='utf-8').read()
        except Exception:
            continue
        for m in re.finditer(r'v(\d+\.\d+)', txt):
            seen.add(m.group(1))
    return sorted(v for v in seen if v != NEW)


def apply_code_and_model_rules(line, olds):
    """轮次85：把 versionCode / versionName / 语音模型名 的**当前值**写法也刷成新版本。

    ★ 只认**锚定形式**（表头 / 表格单元格 / 产物名），绝不裸替换 `versionCode <n>` ——
      第一次写宽了就出事：STATE.md 里 `versionCode 65`（历史记录）被改成了新号，
      回滚才修好。历史记录一律不许动。
    """
    DEV = int(NEW.replace('.', '')) if NEW and NEW.replace('.', '').isdigit() else 0
    REL = 1000 + DEV
    out = line
    for old in olds:
        oc = old.replace('.', '')
        if not oc.isdigit():
            continue
        oci = int(oc)
        # ① README 标题：（v7.6 / versionCode 75）
        out = re.sub(r'(（v%s\s*/\s*versionCode\s*)(\d+)(）)' % re.escape(NEW),
                     lambda m: m.group(1) + str(DEV) + m.group(3), out)
        # ② 交付说明表头：**版本**：v7.6（versionCode 75）
        out = re.sub(r'(\*\*版本\*\*：v%s（versionCode\s*)(\d+)(）)' % re.escape(NEW),
                     lambda m: m.group(1) + str(DEV) + m.group(3), out)
        # ③ 表格：| versionCode | 开发版 64 / 正式版 1064 |
        out = re.sub(r'(\|\s*versionCode\s*\|\s*开发版\s*)(\d+)(\s*/\s*正式版\s*)(\d+)(\s*\|)',
                     lambda m: m.group(1) + str(DEV) + m.group(3) + str(REL) + m.group(5), out)
        # ④ 表格：| versionCode | `64`（原值，逐版 +1） | `1064`（= 1000 + 开发版） |
        out = re.sub(r'(\|\s*versionCode\s*\|\s*`)(\d+)(`（原值，逐版 \+1）\s*\|\s*`)(\d+)(`（= 1000 \+ 开发版）)',
                     lambda m: m.group(1) + str(DEV) + m.group(3) + str(REL) + m.group(5), out)
        # ⑤ 表格：| versionName | `6.5` | `6.5`（两版同步） |
        out = re.sub(r'(\|\s*versionName\s*\|\s*`)(\d+\.\d+)(`\s*\|\s*`)(\d+\.\d+)(`（两版同步）)',
                     lambda m: m.group(1) + NEW + m.group(3) + NEW + m.group(5), out)
        # ⑥ 强调式：versionName **7.5**（README 第 4 行、交付说明）
        out = out.replace('versionName **%s**' % old, 'versionName **%s**' % NEW)
        # ⑦ 语音模型独立件名
        out = out.replace('语音模型_%s_' % old, '语音模型_%s_' % NEW)
    return out


def main():
    olds = scan_old_versions()
    print('当前版本 = v%s；扫描到的旧版本 = %s' % (NEW, olds))
    if not olds:
        print('  没有旧版本需要替换')
        return

    changed_files = 0
    for fname in os.listdir(ROOT):
        if not fname.endswith('.md'):
            continue
        p = os.path.join(ROOT, fname)
        try:
            lines = io.open(p, encoding='utf-8').read().split('\n')
        except Exception:
            continue
        n = 0
        for i, line in enumerate(lines):
            if any(k in line for k in HISTORY_MARKERS):
                continue
            orig = line
            for old in olds:
                line = line.replace('v' + old, 'v' + NEW)      # 产物/路径名
            # 表格里的裸版本号：「| versionName | 6.1 |」这类
            line = re.sub(r'(\|\s*versionName\s*\|\s*)(\d+\.\d+)(\s*\|)',
                          lambda m: m.group(1) + NEW + m.group(3), line)
            # PATCHED_VERSIONCODE_RULES（轮次85）：versionCode/versionName/语音模型名
            line = apply_code_and_model_rules(line, olds)
            if line != orig:
                lines[i] = line
                n += 1
        if n:
            io.open(p, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines))
            changed_files += 1
            print('  %-24s 改了 %d 行' % (fname, n))
    print('共改动 %d 个文件' % changed_files)


if __name__ == '__main__':
    main()
