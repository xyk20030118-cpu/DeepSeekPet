"""核实 APK 瘦身形态正确，且**说话相关的东西一个都没少**。

★ 为什么不能只看体积：
  体积变小也可能是因为漏打了别的资产。必须逐项确认：
    ① assets/asr/ 下**只有小文件**（tokens.txt + silero_vad.onnx，0.9MB），
       **没有**大模型 sensevoice.int8.onnx（228MB 那个走交付 zip 独立分发）
    ② 但**说话**（TTS）所需的东西一个都没少：
       - dex 里的 TtsEngine / TtsCodec / TtsProvider
       - 语音相关 native 库是否还在（若有）
    ③ 其它资产（webp 动画、json）数量与之前一致
"""
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')
sys.path.insert(0, __file__.rsplit('\\', 1)[0])
from artifacts import dev_apk  # noqa: E402
APK = dev_apk()

with zipfile.ZipFile(APK) as z:
    infos = z.infolist()
    names = [i.filename for i in infos]
    dex = z.read('classes.dex')

    print('=== ① 语音资源形态（小文件在，大模型走独立分发）===')
    asr = sorted(n for n in names if n.startswith('assets/asr/'))
    # 小文件必须在（VAD/词表兜底，0.9MB）
    for need in ('assets/asr/tokens.txt', 'assets/asr/silero_vad.onnx'):
        if need not in asr:
            print('   ✗ 缺小文件: %s' % need)
            sys.exit(1)
        print('   ✓ %s  %.1f MB（随包）' % (need, z.getinfo(need).file_size / 1048576))
    # 大模型必须不在（走交付 zip 独立分发，见 pack_delivery.py）
    big = [n for n in asr
           if n.endswith('.onnx') and z.getinfo(n).file_size > 200 * 1024 * 1024]
    if big:
        print('   ✗ APK 里仍有大模型:')
        for n in big:
            print('      %s  %.1f MB' % (n, z.getinfo(n).file_size / 1048576))
        sys.exit(1)
    print('   ✓ 无大模型（sensevoice.int8.onnx 走交付 zip 独立分发）')

    print()
    print('=== ② 说话（TTS）相关代码（必须在）===')
    # dex 里的类名以字符串形式存在
    for cls in (b'TtsEngine', b'TtsCodec', b'TtsProvider', b'audio/speech',
                b'onMouthStart'):
        hit = cls in dex
        print('   %-16s %s' % (cls.decode(), '✓' if hit else '✗ 缺失!'))
        if not hit:
            sys.exit(1)
    print('   ✓ 云端 TTS 链路完整（说话能力不受影响）')

    print()
    print('=== ③ 其它资产（不能被误删）===')
    webp = [n for n in names if n.startswith('assets/webp/')]
    jsons = [n for n in names if n.startswith('assets/') and n.endswith('.json')]
    so = [n for n in names if n.endswith('.so')]
    print('   webp 动画: %d 个' % len(webp))
    print('   json     : %d 个' % len(jsons))
    print('   native so: %d 个' % len(so))
    for n in so:
        print('      %s  %.1f MB' % (n, z.getinfo(n).file_size / 1048576))
    if len(webp) < 50:
        print('   ✗ webp 数量异常偏少')
        sys.exit(1)
    if len(so) < 4:
        print('   ✗ native 库数量异常偏少')
        sys.exit(1)
    print('   ✓ 资产完整')

    print()
    total = sum(i.file_size for i in infos) / 1048576
    print('=== 合计 ===')
    print('   解压后 %.1f MB，APK 文件本身约 %.1f MB'
          % (total, __import__('os').path.getsize(APK) / 1048576))
print()
print('⇒ 语音模型已移除，说话能力完整 ✓')
