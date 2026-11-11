"""Prepare sources for build/cpidtest/run.bat.

Suites here:
  1. ChatProviderTest -- capability table (pure Java, copied as-is)
  2. BalanceTest      -- balance JSON path parsing + URL building

Both targets are pure Java by design:
  ChatProvider and ChatBalance deliberately import no android classes, so the
  offline suite compiles the REAL files -- no slicing, no stand-ins.
  (ApiClient itself cannot be loaded off-device, which is exactly why the
  balance logic was extracted into ChatBalance -- same reason VideoFitMath
  was extracted from VideoBackgroundView.)

org.json: the real implementation comes from build/toolchain/android.jar,
placed on the CLASSPATH (not bootclasspath) so a normal JDK can link it.
Testing the genuine parser matters -- a hand-written stub would only prove
the stub works.
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
GEN = os.path.join(HERE, 'gen', 'com', 'deepseek', 'pet')
TESTS = os.path.join(HERE, 'com', 'deepseek', 'pet')

# Pure-Java sources under test (copied verbatim)
# ★ 轮次75：ChatProvider 现在引用 LocalModelFeature（纯类）⇒ 必须一起喂进来，
#   否则离线编译会报"找不到符号"（本轮实测：cpidtest 直接红）。
PURE = ['ChatProvider.java', 'ChatBalance.java', 'BackNav.java', 'LocalModelFeature.java']
# Test classes
TEST_CLASSES = ['ChatProviderTest.java', 'BalanceTest.java', 'BackNavTest.java']


def read(path):
    with io.open(path, encoding='utf-8-sig') as f:
        return f.read()


def write(path, text):
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def main():
    os.makedirs(GEN, exist_ok=True)

    for name in PURE:
        src = os.path.join(SRC, name)
        text = read(src)
        # Guard: these files must stay android-free, otherwise the offline
        # suite silently becomes untestable (and someone would be tempted to
        # add a stub, which is how tests stop testing the real thing).
        if 'import android.' in text:
            raise SystemExit(
                'prepare: %s now imports android.* -- it must stay pure Java '
                'so it can be asserted offline. Move the Android-dependent '
                'part out instead.' % name)
        write(os.path.join(GEN, name), text)

    for name in TEST_CLASSES:
        write(os.path.join(GEN, name), read(os.path.join(TESTS, name)))

    print('prepared gen/ from %s (pure-Java sources: %s)'
          % (SRC, ', '.join(PURE)))
    return 0


if __name__ == '__main__':
    sys.exit(main())
