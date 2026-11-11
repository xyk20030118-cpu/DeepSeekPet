"""Prepare the offline test sources for build/vidtest/run.bat.

Why this exists (and why it copies instead of testing in place):
  VideoFitMath references Prefs.FIT_* constants, but Prefs is an Android
  class (it imports android.content.Context), so it cannot be loaded
  without an Android runtime. The offline suite therefore compiles a copy
  of VideoFitMath with those constants substituted by literals.

  The real source file under src/ is never modified -- what gets asserted
  is exactly the math that ships in the APK.

Note: this is a separate file rather than an inline `python -c` inside
run.bat because the one-liner version became unreadable once it had to
copy three inputs, and cmd quoting made it fragile.
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.abspath(os.path.join(HERE, '..', '..', 'src', 'com', 'deepseek', 'pet'))
GEN = os.path.join(HERE, 'gen', 'com', 'deepseek', 'pet')


def read(path):
    with io.open(path, encoding='utf-8-sig') as f:
        return f.read()


def write(path, text):
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def main():
    os.makedirs(GEN, exist_ok=True)

    # VideoFitMath: substitute the Prefs constants with their literal values.
    math = read(os.path.join(SRC, 'VideoFitMath.java'))
    math = (math.replace('Prefs.FIT_STRETCH', '2')
                .replace('Prefs.FIT_COVER', '1')
                .replace('Prefs.FIT_CONTAIN', '0'))
    write(os.path.join(GEN, 'VideoFitMath.java'), math)

    # The two test classes are copied verbatim. SourcePairingTest takes the
    # src path as an argument (see run.bat) so it reads the REAL sources.
    for name in ('VideoFitTest.java', 'SourcePairingTest.java'):
        write(os.path.join(GEN, name), read(os.path.join(HERE, 'com', 'deepseek', 'pet', name)))

    print('prepared gen/ from %s' % SRC)
    return 0


if __name__ == '__main__':
    sys.exit(main())