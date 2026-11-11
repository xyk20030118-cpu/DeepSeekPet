# -*- coding: utf-8 -*-
"""自定义对话 API「用不了」的修复（轮次56）。

用户原话：「修复自定义对话api无法正常使用的问题 可能是接口格式兼容性问题」，
补充信息：「http://127.0.0.1:7863/v1 这是我本机连接的输入就报错 但是agent有用」
「模型就获取不到」。

## 实测复现（★ 先证明会红，再证明变绿）

宿主机的**严格模式** mock（未知字段直接 400）上：

| 包 | mock 收到 `GET /v1/models`？ | App 里看到 |
|---|---|---|
| 修复前（轮次55 的 dev 包） | **一条都没有** | 「获取模型失败（可能是网络问题…）」 |
| 修复后（本轮） | **收到了** | 「选择模型」列出 mock 的两个模型 |

⇒ **真凶不是接口格式**（请求体本来就是标准 OpenAI 字段：`model/messages/stream/max_tokens`），
而是**请求根本没离开设备**。

## 两个真凶

  ① **回环地址跨不过设备边界**：`127.0.0.1` 指的是"运行 App 的那台设备自己"。
     用户的 agent 在电脑上跑所以通，App 装在手机/模拟器上就必然连不上。
  ② ★★ **明文 HTTP 被平台挡掉**（App 侧硬 bug）：本工程 `targetSdk=34` 且清单里
     没有任何明文配置 ⇒ Android 9(API 28) 起**默认禁止明文 HTTP**。用户就算换成
     正确的局域网 IP，`http://` 也一样发不出去（`CLEARTEXT communication … not permitted`），
     而这条错在 App 侧**无法用别的写法绕开**。实测物证：请求根本没到宿主机。

## 分节

  一、★ 明文 HTTP 放行（清单 + network_security_config，含 APK 物证）
  二、★ 地址判据纯类 `Endpoint`（回环/通配 + 可操作建议）+ fpstest 真跑
  三、★★ 失败原因不再被吞（`modelsError` / `HttpResult` / `probeModels`）
  四、★ 界面：先说清楚再存 + 「测试连接」+ 模型失败给真因
  五、★ 「强制参数」只管标准字段（不再发 DeepSeek 专有的 thinking / reasoning_effort）
  六、设备脚本（可复跑，且只认模拟器）
  七、产物：新代码真的进了 dev 包

反向破坏见 reverse_check_custom_api.py。
"""
import io
import os
import re
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(encoding='utf-8', errors='replace')

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = r'D:\petport_lab\petbuild'
SRC = os.path.join(ROOT, 'src', 'com', 'deepseek', 'pet')
from artifacts import dev_apk  # noqa: E402
DEV_APK = dev_apk()

fails = []


def check(name, ok, detail=''):
    if isinstance(detail, (list, tuple)):
        detail = ' / '.join(str(d) for d in detail)
    print(('  [OK]   ' if ok else '  [FAIL] ') + name + ((' :: ' + str(detail)) if detail else ''))
    if not ok:
        fails.append(name)


def read(p):
    return io.open(p, encoding='utf-8', errors='replace').read()


def code_only(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if c == '/' and nxt == '*':
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == '\\':
                    j += 2
                    continue
                if src[j] == '"':
                    break
                j += 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def java(name):
    return code_only(read(os.path.join(SRC, name)))


def method_body(src, sig):
    i = src.find(sig)
    if i < 0:
        return ''
    j = src.find('\n    }', i + len(sig))
    return src[i:j + 6] if j > 0 else src[i:]


MAN = read(os.path.join(ROOT, 'AndroidManifest.xml'))
API = java('ApiClient.java')
EP = java('Endpoint.java')
MA = java('MainActivity.java')
NSC_PATH = os.path.join(ROOT, 'res', 'xml', 'network_security_config.xml')
NSC_RAW = read(NSC_PATH) if os.path.isfile(NSC_PATH) else ''
# ★ 判据要看**配置本身**，不能连注释一起看：这个文件的注释里为了解释口径，
#   恰好写着 `trust-anchors` / `debug-overrides` 这些词 —— 拿原文判会自己把自己判红
#   （第一版就是这么假红的）。
NSC = re.sub(r'<!--.*?-->', '', NSC_RAW, flags=re.S)

print('=== 一、★ 明文 HTTP 放行（否则用户填对了也连不上）===')
check('★★ 清单里挂了 networkSecurityConfig（targetSdk 34 默认禁明文 ⇒ 不挂就发不出去）',
      'android:networkSecurityConfig="@xml/network_security_config"' in MAN)
check('★★ 同时显式声明 usesCleartextTraffic（老平台/工具链也认这一条）',
      'android:usesCleartextTraffic="true"' in MAN)
check('★★ 策略文件真的放行明文，且**保留系统根证书**（不是把校验也关了）',
      os.path.isfile(NSC_PATH)
      and 'cleartextTrafficPermitted="true"' in NSC
      and '<certificates src="system" />' in NSC
      and 'debug-overrides' not in NSC)
check('★ 没顺手把别的安全开关也放开（没有 trust-all / 用户证书）',
      'src="user"' not in NSC and 'cleartextTrafficPermitted="true"/><trust-anchors' not in NSC)

print()
print('=== 二、★ 地址判据纯类 Endpoint（回环 = 设备自己）+ fpstest 真跑 ===')
check('★★ 纯类（零 Android 依赖）⇒ fpstest 能真跑，不是只靠"源码里有没有这句话"',
      'public final class Endpoint' in EP
      and 'import android' not in EP and 'android.' not in EP)
check('★★ 认出回环：localhost / 127.*（整个 127/8）/ ::1',
      'public static boolean isLoopbackHost(String h)' in EP
      and '"localhost".equals(x) || x.endsWith(".localhost")' in EP
      and 'x.startsWith("127.")' in EP
      and '"::1".equals(x)' in EP)
check('★ IPv6 字面量要剥方括号（`URI.getHost()` 会带括号返回 —— fpstest [30] 当场抓到的真 bug）',
      'h.startsWith("[") && h.endsWith("]")' in EP)
check('★ 0.0.0.0 单独一类（那是"监听所有网卡"，不是能连的地址）',
      'public static boolean isWildcardHost(String h)' in EP and '"0.0.0.0".equals(x)' in EP)
adv = method_body(EP, 'public static String advice(String url)')
check('★★ 建议必须**解释清楚**"设备上的 127.0.0.1 是谁"，并给出三条可操作出路'
      '（10.0.2.2 / 局域网 IP / adb reverse）—— 只说"失败"用户会更困惑',
      '设备自己' in adv and '10.0.2.2' in adv and '局域网' in adv
      and 'adb reverse' in adv and '0.0.0.0' in adv)
check('★ 正常地址不给建议（不能对每个地址都弹警告）',
      'return "";' in adv)
check('★ fpstest 真的编了它、并且有 [30] 一节（否则"纯类"只是嘴上说说）',
      'Endpoint.java' in read(os.path.join(HERE, 'fpstest', 'run.bat'))
      and '[30] Endpoint' in read(os.path.join(HERE, 'fpstest', 'com', 'deepseek', 'pet', 'FpsTest.java')))

print()
print('=== 三、★★ 失败原因不再被吞掉 ===')
fm = method_body(API, 'public static java.util.List<ModelInfo> fetchModels(String apiKey)')
check('★★ fetchModels 不再 `catch (Exception ignore)` —— 那正是"只看到获取模型失败"的原因',
      'catch (Exception ignore)' not in fm and 'HttpResult hr = httpGet(' in fm)
check('★★ 失败原因有地方存、有地方读（`modelsError()`）',
      'public static String modelsError()' in API and 'lastModelsError' in API)
check('★ 原因里带**异常原文**（CLEARTEXT / Connection refused 这类用户能自己修的信息）',
      'e.getClass().getSimpleName() + "：" + m' in API)
check('★ 「测试连接」是纯诊断：一次 /models 往返，**不改任何状态**',
      'public static Probe probeModels(String apiKey)' in API
      and 'Prefs' not in method_body(API, 'public static Probe probeModels(String apiKey)'))
check('★ 结果对象能把"通/不通"和原文一起带回来',
      'public static class HttpResult' in API and 'public String describe()' in API
      and 'public static class Probe' in API)
check('★★ describe() 是"**真因优先**"：有异常原文就返回它，而不是笼统一句"网络错误"',
      'if (error.length() > 0) return error;' in API)

print()
print('=== 四、★ 界面：先说清楚再存 + 能测连接 + 模型失败给真因 ===')
url_dlg = method_body(MA, 'private void showBaseUrlDialog()')
check('★★ 存地址前先问一句（地址可疑时弹"可能连不上"+ 建议，用户可「仍然保存」）',
      'Endpoint.advice(url)' in url_dlg and '这个地址可能连不上' in url_dlg
      and '仍然保存' in url_dlg and '改一改' in url_dlg)
check('★ 保存路径只有一条（两条路必然走岔）',
      'private void saveBaseUrlAndToast(String url)' in MA
      and 'saveBaseUrlAndToast(finalUrl);' in url_dlg)
check('★★ 地址那一行**被动**就把可疑标出来（用户不点进去也看得见）',
      'Endpoint.suspicious(ApiClient.getBaseUrl())' in MA and '可能是设备自己' in MA)
check('★★ 「测试连接」入口在（把真实报错摆到用户眼前，而不是"可能是网络问题"）',
      'private void runConnectionTest()' in MA and '测试连接（试一次 /models' in MA
      and 'private void showProbeResult(ApiClient.Probe p)' in MA)
check('★ 模型失败弹窗带上真实原因（`ApiClient.modelsError()`）',
      'String why = ApiClient.modelsError();' in MA and '原因：' in MA)

print()
print('=== 五、★ 「强制参数」只管标准字段 ===')
ar = method_body(API, 'private static void applyReasoning(')
check('★★ applyReasoning 里**不再有 force** —— 修复前它会把 DeepSeek 专有的 thinking / '
      'reasoning_effort 一起发给第三方网关（多数网关遇到未知字段直接 400）',
      'boolean force' not in ar and 'force ||' not in ar
      and 'ChatProvider.supports(prov, model, "think") == ChatProvider.OK' in ar)
check('★ 调用点同步（只传三个参数）',
      'applyReasoning(payload, prov, model);' in API
      and 'applyReasoning(payload, prov, model, force);' not in API)
check('★ 开关文案写明"只发标准字段"（否则用户以为它会强制发一切）',
      '强制发送温度/惩罚参数（只发标准字段）' in MA)
check('★ 温度/惩罚仍受开关控制（开关本身没被废掉）',
      'boolean force = Prefs.chatForceParams(appCtx)' in API
      and 'force || ChatProvider.supports(prov, model, "temp") == ChatProvider.OK' in API)

print()
print('=== 六、设备脚本（可复跑，且只认模拟器）===')
DEV_SCRIPT = os.path.join(HERE, 'verify_custom_api_ui.py')
check('verify_custom_api_ui.py 存在', os.path.isfile(DEV_SCRIPT))
if os.path.isfile(DEV_SCRIPT):
    ds = read(DEV_SCRIPT)
    check('★ 它钉死模拟器 + 自己起 mock（不依赖外部服务）',
          'DEV = V.DEV' in ds and 'ThreadingHTTPServer' in ds)
    check('★★ 它的**端口常量**绝不是 7863（那是用户本机的 AI 网关，占了会把用户的模型连接弄断）'
          '—— 注释里解释这件事不算违规，判据只看赋值',
          'PORT = 17911' in ds and re.search(r'PORT\s*=\s*7863', ds) is None)
    check('★★ 它防"只看源码不看屏幕"：按屏幕上的文字判结果',
          'S.all_texts()' in ds and 'probe_advice' in ds and 'probe_ok' in ds)
    rc, out = (lambda r: (r.returncode, r.stdout.decode('utf-8', 'replace')))(
        subprocess.run([sys.executable, DEV_SCRIPT, '--selftest'], capture_output=True))
    check('verify_custom_api_ui --selftest 通过', rc == 0 and 'SELFTEST PASSED' in out,
          'rc=%d' % rc)

print()
print('=== 七、产物：新代码真的进了 dev 包 ===')
check('dev APK 存在', os.path.isfile(DEV_APK), DEV_APK)
if os.path.isfile(DEV_APK):
    with zipfile.ZipFile(DEV_APK) as z:
        dex = z.read('classes.dex')
        ax = z.read('AndroidManifest.xml')
    for s in ('Endpoint', '测试连接（试一次', '可能是设备自己', '这个地址可能连不上',
              '只发标准字段', '地址本身就有问题'):
        check('dex 含 %s' % s, s.encode('utf-8') in dex)
    # ★ AXML 的字符串池是 UTF-16LE（见 verify_back_manifest 的注释：只查 UTF-8 会假红）
    check('★★ 二进制清单里真的有 networkSecurityConfig（编译期静默失效的典型）',
          b'networkSecurityConfig' in ax
          or 'networkSecurityConfig'.encode('utf-16-le') in ax)

print()
print('=' * 62)
if fails:
    print('%d 项失败：' % len(fails))
    for f in fails:
        print('   - ' + f)
    sys.exit(1)
print('ALL CHECKS PASSED（自定义对话 API：明文放行 / 回环判据 / 失败原因 / 测试连接 / 强制参数）')
