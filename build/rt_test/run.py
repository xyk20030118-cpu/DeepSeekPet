# -*- coding: utf-8 -*-
"""离线行为断言的驱动器（真代码，桌面 JVM）。两个主类：

  * ``RoundTrip``     —— 「导出 → 导入」往返（用户追加要求里最硬的一条）
  * ``BindingsCheck`` —— 用户覆盖层 + "把新动作追加进场景"的真实行为

用法:
    python build/rt_test/run.py                 # 两个都跑（默认）
    python build/rt_test/run.py --only=bindings # 只跑覆盖层那套（快）
    python build/rt_test/run.py --only=roundtrip
    python build/rt_test/run.py --show-log      # 把产品代码打的日志也吐出来
    python build/rt_test/run.py --no-cache      # 强制真跑（**断言要读落盘产物时必须加**）

为什么要这么绕（见 RoundTrip.java / BindingsCheck.java 的说明）：
  * 产品代码 import 了 android.*（Context / Log / Looper / Handler），
    桌面 JVM 上必须给替身 —— 编译期用真 android.jar（签名齐全、能编过），
    只在**运行期**把 build/rt_test/shim 放在 classpath 最前面。
  * org.json 在 android.jar 里也是 `throw new RuntimeException("Stub!")`，
    但工程里本来就有真的 json.jar（build/cpidtest/lib/json.jar）⇒ 运行期排在 android.jar 前面。
  * `Context.<init>` 本身就是 Stub（连子类都造不出来）、AssetManager 是 final 类
    ⇒ 凡是要 Context 的地方都走"离线入口"（PetAnimPack.loadOffline / SceneBindings.*In），
    它们与 Context 版本是**同一份实现**。

★ 结果缓存：这两个程序的输出只是"被编译的那几个源文件"的函数。
  缓存键 = 相关源文件的 md5，命中就直接回放上次的输出（不重编、不重跑）。
  没有它的话，反向套件里每一次"破坏→跑 verify"都要重编 70 个文件，整套测试要慢好几分钟。
"""
import hashlib
import os
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

ROOT = r'D:\petport_lab\petbuild'
HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(ROOT, 'build', '_rttest')
CACHE = os.path.join(WORK, 'cache')
JAVA_HOME = r'C:\Program Files\Microsoft\jdk-17.0.11.9-hotspot'
JAVAC = os.path.join(JAVA_HOME, 'bin', 'javac.exe')
JAVA = os.path.join(JAVA_HOME, 'bin', 'java.exe')
AJAR = os.path.join(ROOT, 'build', 'toolchain', 'android.jar')
JSONJAR = os.path.join(ROOT, 'build', 'cpidtest', 'lib', 'json.jar')
DEPS = ('sherpa-classes.jar', 'kotlin-stdlib-1.9.0.jar', 'shizuku-api.jar',
        'shizuku-provider.jar', 'shizuku-aidl.jar', 'shizuku-shared.jar')

SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
GEN = os.path.join(ROOT, 'gen')

# 参与编译/影响输出的源文件（改了就重跑；**故意不含 MainActivity** ——
# 它不参与这两个程序，于是"只改设置页文案"的破坏项可以直接命中缓存）
HASH_FILES = [
    os.path.join(SRC, 'RoundTrip' if False else 'CharacterExporter.java'),
    os.path.join(SRC, 'CharacterImporter.java'),
    os.path.join(SRC, 'CharacterStore.java'),
    os.path.join(SRC, 'PetAnimPack.java'),
    os.path.join(SRC, 'PetCharacter.java'),
    os.path.join(SRC, 'PetModel.java'),
    os.path.join(SRC, 'ActionSystem.java'),
    os.path.join(SRC, 'SceneBindings.java'),
    os.path.join(SRC, 'SceneRegistry.java'),
    os.path.join(SRC, 'ActionMenuConfig.java'),
    os.path.join(SRC, 'ClipNaming.java'),
    os.path.join(SRC, 'PackFormat.java'),
    os.path.join(SRC, 'CharPicker.java'),
    os.path.join(SRC, 'Appearance.java'),
    os.path.join(SRC, 'AppearanceStore.java'),
    os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'RoundTrip.java'),
    os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'BindingsCheck.java'),
    os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'AppearanceCheck.java'),
]
SHIMS = [os.path.join(HERE, 'shim', 'android', 'util', 'Log.java'),
         os.path.join(HERE, 'shim', 'android', 'os', 'Looper.java'),
         os.path.join(HERE, 'shim', 'android', 'os', 'Handler.java')]


def dec(b):
    for enc in ('utf-8', 'gbk'):
        try:
            return b.decode(enc)
        except Exception:
            pass
    return b.decode('utf-8', 'replace')


def run(cmd, desc):
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0:
        print('!! %s 失败:\n%s' % (desc, dec(r.stdout + r.stderr)[:3000]))
        sys.exit(2)
    return r


def cache_key(only):
    h = hashlib.md5()
    for p in HASH_FILES + SHIMS:
        h.update(os.path.basename(p).encode('utf-8'))
        try:
            h.update(open(p, 'rb').read())
        except Exception:
            h.update(b'<missing>')
    h.update(only.encode('utf-8'))
    return h.hexdigest()


def build_and_run(only, show_log):
    os.makedirs(WORK, exist_ok=True)
    deps = ';'.join(os.path.join(ROOT, 'deps', d) for d in DEPS)
    cp_compile = ';'.join([AJAR, JSONJAR, deps])

    shim_out = os.path.join(WORK, 'shim')
    os.makedirs(shim_out, exist_ok=True)
    run([JAVAC, '-nowarn', '-encoding', 'UTF-8', '-d', shim_out] + SHIMS, 'javac(shim)')

    out = os.path.join(WORK, 'out')
    os.makedirs(out, exist_ok=True)
    # 只编这两个程序需要的那几棵树（其余由 -sourcepath 按需拉进来），比全量 70 个文件快得多
    # ★ 注意：-sourcepath 的根是**包目录**（.../src/com/deepseek/pet），
    #   所以隐式编译找不到同包的新文件 —— 新增依赖必须显式列进来
    #   （轮次㉔ 加了 ContentInset/ContentInsetProbe，PetAnimPack 引用它们 ⇒ 不列就报
    #    "程序包 ContentInsetProbe 不存在"）。
    feeds = [os.path.join(SRC, f) for f in
             ('CharacterExporter.java', 'CharacterImporter.java', 'CharacterStore.java',
              'PetAnimPack.java', 'ActionSystem.java', 'SceneBindings.java', 'ClipNaming.java',
              'PackFormat.java', 'SceneRegistry.java', 'ActionMenuConfig.java',
              'PetCharacter.java', 'PetModel.java', 'CharPicker.java',
              'Appearance.java', 'AppearanceStore.java',
              'ContentInset.java', 'ContentInsetProbe.java')]
    feeds += [os.path.join(GEN, 'com', 'deepseek', 'pet', 'R.java')]
    feeds += [os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'RoundTrip.java'),
              os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'BindingsCheck.java'),
              os.path.join(HERE, 'harness', 'com', 'deepseek', 'pet', 'AppearanceCheck.java')]
    run([JAVAC, '-nowarn', '-encoding', 'UTF-8', '-sourcepath', os.pathsep.join([SRC, GEN]),
         '-cp', cp_compile, '-d', out] + feeds, 'javac(harness+src)')

    cp_run = ';'.join([shim_out, out, JSONJAR, AJAR, deps])
    tmp = os.path.join(WORK, 'tmp')
    text = ''
    if only in ('all', 'roundtrip'):
        r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                            '-Dsun.stderr.encoding=UTF-8', '-cp', cp_run,
                            'com.deepseek.pet.RoundTrip', tmp,
                            os.path.join(ROOT, 'assets', 'webp'),
                            os.path.join(ROOT, 'assets', 'pet_anim.json'),
                            os.path.join(ROOT, 'assets', 'anim_lines.json')], capture_output=True)
        text += dec(r.stdout)
        if r.returncode != 0:
            print('!! RoundTrip 退出码 %d\n%s' % (r.returncode, dec(r.stderr)[-4000:]))
            sys.exit(1)
        if show_log and r.stderr:
            text += dec(r.stderr)
    if only in ('all', 'bindings'):
        r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                            '-Dsun.stderr.encoding=UTF-8', '-cp', cp_run,
                            'com.deepseek.pet.BindingsCheck', tmp], capture_output=True)
        text += dec(r.stdout)
        if r.returncode != 0:
            print('!! BindingsCheck 退出码 %d\n%s' % (r.returncode, dec(r.stderr)[-4000:]))
            sys.exit(1)
        if show_log and r.stderr:
            text += dec(r.stderr)
    if only in ('all', 'appearance'):
        r = subprocess.run([JAVA, '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8',
                            '-Dsun.stderr.encoding=UTF-8', '-cp', cp_run,
                            'com.deepseek.pet.AppearanceCheck', tmp], capture_output=True)
        text += dec(r.stdout)
        if r.returncode != 0:
            print('!! AppearanceCheck 退出码 %d\n%s' % (r.returncode, dec(r.stderr)[-4000:]))
            sys.exit(1)
        if show_log and r.stderr:
            text += dec(r.stderr)
    return text


def main():
    only = 'all'
    no_cache = False
    for a in sys.argv[1:]:
        if a.startswith('--only='):
            only = a.split('=', 1)[1]
        elif a == '--no-cache':
            no_cache = True
    show_log = '--show-log' in sys.argv
    os.makedirs(CACHE, exist_ok=True)
    key = cache_key(only)
    cached = os.path.join(CACHE, '%s.txt' % key)
    # ★ `--no-cache`：给"要读**落盘产物**"的调用方用（verify_character_export 会去
    #   `_rttest/tmp/sA|sB/characters/...` 里逐个检查素材文件在不在）。
    #   缓存只存**stdout 文本**、不重建 tmp 目录 —— 于是"回放绿文本 + 磁盘上还是上一次
    #   破坏留下的烂状态"会同时成立，断言就会**时红时绿**（实测：反向套件里 b / g 两项
    #   "恢复后=1"，而同一次运行里只改配置不动物理文件的 a / c 却正常）。
    #   判据：**断言读磁盘 ⇒ 必须绕开缓存**。
    if os.path.isfile(cached) and not show_log and not no_cache:
        sys.stdout.write(open(cached, encoding='utf-8').read())
        return 0
    text = build_and_run(only, show_log)
    try:
        with open(cached, 'w', encoding='utf-8') as f:
            f.write(text)
        # 缓存目录只留最近 40 份，别让 build/ 悄悄长胖
        fs = sorted((os.path.join(CACHE, x) for x in os.listdir(CACHE)),
                    key=os.path.getmtime)
        for p in fs[:-40]:
            try:
                os.remove(p)
            except Exception:
                pass
    except Exception:
        pass
    sys.stdout.write(text)
    return 0


if __name__ == '__main__':
    sys.exit(main())
