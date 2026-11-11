# DeepSeekPet 双版本构建说明（开发版 / 正式发布版）

> 从 v5.36 起，同一个工程可以产出**两个互不冲突的版本**，可同时装在一台手机上。

## 一、两版差异一览

| | 开发版 | 正式发布版 |
|---|---|---|
| 包名 | `com.deepseek.pet` | `com.doropet.release` |
| versionCode | `80`（原值，逐版 +1） | `1080`（= 1000 + 开发版） |
| versionName | `8.0` | `8.0`（两版同步） |
| 应用名 | DeepSeek桌宠 | DeepSeek桌宠·正式版 |
| 版本类型（关于页） | 开发版 | 正式版 |
| 签名 | `debug.keystore`（CN=Android Debug） | `release.keystore`（CN=曦城那年，RSA 4096，至 2056） |
| 赞赏码 | **不含**（关于页自动隐藏整块） | **含** `assets/reward_qr.png` |
| 产物 | `dist/DeepSeekPet_v8.0_rebuilt.apk` | `dist/DeepSeekPet_v8.0_release.apk` |
| 中间目录 | `build/out/` `build/work/` | `build/out_release/` `build/work_release/` |

**两版可同时安装**，各自独立保存设置与对话数据（包名不同）。

## 二、工程结构

```
DeepSeekPet_v8.0_完整工程/          ← 唯一源工程（开发版）
├── src/com/deepseek/pet/*.java     ← 39 个源文件
├── AndroidManifest.xml             ← 明文清单（package=com.deepseek.pet）
├── assets/reward_qr.png            ← 赞赏码（★ 开发版不含，仅正式版打入）
├── release.keystore                ← 正式版签名库（不在仓库里）
├── debug.keystore                  ← 开发版签名库
├── build/
│   ├── build_pc.sh                 ← ★ 电脑端一键构建（等价两阶段）
│   ├── fetch_aapt2_pc.sh           ← 电脑端 aapt2 获取
│   ├── 1_compile_android.sh        ← 阶段① 资源编译（Android Root Shell）
│   ├── 2_build_linux.sh            ← 阶段② 构建 APK（Linux 工具环境）
│   ├── make_variant.py             ← ★ 变体生成器
│   ├── toolchain-pc/               ← 电脑原生 aapt2（Linux x86-64 已内置）
│   ├── release_src/reward_qr.png   ← 赞赏码源图（正式版据此打入 assets/）
│   ├── assemble.py                 ← APK 组装
│   ├── axml-tools/                 ← AXML 反解/改包名工具
│   └── toolchain/                  ← android.jar / d8.jar / apksigner.jar / ARM64 aapt2

DeepSeekPet_Release/                ← 正式版工程（由 make_variant.py 生成，勿手改）
```

## 二之二、在电脑上构建（推荐）

电脑端**不需要安卓设备**，一条命令跑完两个阶段：

```sh
cd DeepSeekPet_v8.0_完整工程
sh build/fetch_aapt2_pc.sh                  # 一次性：装本机可用的 aapt2
sh build/build_pc.sh                        # 开发版
DSP_VARIANT=release sh build/build_pc.sh    # 正式版
```

与设备端的对应关系：

| | 设备端 | 电脑端 |
|---|---|---|
| 阶段① 资源编译 | `1_compile_android.sh` | 内置进 `build_pc.sh` |
| 阶段② Java/dex/签名 | `2_build_linux.sh` | 内置进 `build_pc.sh` |
| aapt2 | `toolchain/arm-bin/aapt2`（Android） | `toolchain-pc/aapt2`（电脑原生） |
| 正式版工程 | **需手动**跑 `make_variant.py` | **自动生成**（每次构建都重新生成） |

> ★ 电脑端 `DSP_VARIANT=release` 与设备端含义不同：
> 设备端它只是「选变体」（工程目录必须是已生成的正式版工程）；
> 电脑端它会**先调 `make_variant.py` 生成正式版工程**再构建，
> 因此不存在「正式版工程落后于开发版」的问题。
> 正式版默认输出到 `../DeepSeekPet_Release`，可用 `DSP_RELEASE_DIR` 指定。

> ★ 结果一致性：实测 dev / rel 变体的 `classes.dex` 在两端
> **逐字节相同**（md5 `5425520f9c579b02fd807d3305e2537c` /
> `d8361279bccd827ebc5e2b6654529081`）。

> ★ 赞赏码来源：`build/release_src/reward_qr.png`（可用环境变量 `DSP_REWARD_QR` 覆盖）；
> 老路径 `/data/local/tmp/release_src/` 仍兼容。

---

## 三、构建开发版

```sh
# ① 在 Eta「Android / Root Shell」中：
cd /storage/emulated/0/DeepSeekPet_v8.0_完整工程
sh build/1_compile_android.sh

# ② 切到 Eta「Linux 工具环境」：
sh build/2_build_linux.sh
```

## 四、构建正式版

先确保正式版工程是最新的（开发版改完代码后必须重跑这一步）：

```sh
# 在 Eta「Linux 工具环境」中（需要 python3）：
cd /storage/emulated/0/DeepSeekPet_v8.0_完整工程
rm -rf /storage/emulated/0/DeepSeekPet_Release
python3 build/make_variant.py . /storage/emulated/0/DeepSeekPet_Release release
```

> 电脑端无需手动执行本节：`DSP_VARIANT=release sh build/build_pc.sh` 已包含本步骤。

`make_variant.py` 会自动完成 5 件事：
1. 复制工程（跳过 `dist/`、`build/out*`、`build/work*` 等中间产物）
2. 源码目录 `src/com/deepseek/pet` → `src/com/doropet/release`
3. 全部 `.java` 内 `com.deepseek.pet` → `com.doropet.release`（含 `gen/R.java`）
4. 清单：改包名 / versionCode +1000 / 应用名加「·正式版」
5. 打入 `assets/reward_qr.png`

然后跑两阶段，记得带变体环境变量：

```sh
# ① Android / Root Shell：
cd /storage/emulated/0/DeepSeekPet_Release
DSP_VARIANT=release sh build/1_compile_android.sh

# ② Linux 工具环境：
cd /storage/emulated/0/DeepSeekPet_Release
DSP_VARIANT=release sh build/2_build_linux.sh
```

## 四之二、改完代码后的两处同步

1. **正式版工程**：设备端须重跑 `make_variant.py`（电脑端自动）；
2. **`assets/第三方许可声明.md`**：若改动它，请同步工程根同名文件，二者必须一致
   （前者是 APK 内运行时可读副本，后者是交付文档）。

## 五、改版本号

**只改开发版工程的 `AndroidManifest.xml`**（明文 XML，就地改 `versionCode` / `versionName`），
正式版的 `versionCode` 由 `make_variant.py` 自动 +1000，无需手改。

改完**必须重跑阶段①**（清单会被打进 `resbase.apk`）。

关于页的版本号由 `pi.versionName` 运行时读取，源码内无硬编码。

## 六、安装

```sh
# ★ 必须先把 APK 拷到 /data/local/tmp —— system_server 读不了 /storage/emulated/0（noexec）
cp dist/DeepSeekPet_v8.0_rebuilt.apk /data/local/tmp/
pm install -r -d /data/local/tmp/DeepSeekPet_v8.0_rebuilt.apk

# 正式版同理；若签名变更过，需先卸载：
pm uninstall com.doropet.release
pm install -r -d /data/local/tmp/DeepSeekPet_v8.0_release.apk
```

## 七、签名说明

- **开发版**：`debug.keystore`，口令 `android`，别名 `androiddebugkey`。
  所有开发机构建结果签名一致，可直接覆盖安装升级。
- **正式版**：`release.keystore`（**不在仓库里**：口令与别名由维护者私下保管），
  `CN=曦城那年, OU=DeepSeekPet, O=曦城那年, L=Beijing, ST=Beijing, C=CN`，
  RSA 4096 / SHA384withRSA，有效期至 2056-09-20。

> ⚠️ **正式版密钥务必异地备份**。一旦丢失，已分发的正式版将无法再覆盖升级
> （Android 要求同包名同签名的 APK 才能 `-r` 安装）。
> 换密钥会改变签名指纹，老用户必须卸载重装、数据丢失。

## 八、必做的反向校验

`2_build_linux.sh` 已内置以下校验，任一项失败即中止：

1. APK 内 `classes.dex` 的 md5 == 刚编译产物的 md5
2. 清单 `versionCode` / `versionName` 打印回显
3. 签名证书 DN 打印回显
4. dex 内含本次变体的包名路径

额外建议手工抽查：

```sh
# dex 里的中文字符串（⚠️ Android 的 strings/grep -a 对非 ASCII 无效，必须用 Linux 环境的 python3）
cd /tmp && unzip -o -q <apk> classes.dex
python3 -c "
import io; d=io.open('/tmp/classes.dex','rb').read()
for s in ['作者：曦城那年','正式版','reward_qr.png']:
    print(s, '有' if s.encode('utf-8') in d else '无')
"
```

## 九、版本演进记录

| 版本 | 开发版 code | 正式版 code | 说明 |
|---|---|---|---|
| v5.36 | 57 | 1057 | 首次双版本分离；关于页含作者/版本/版本类型/赞赏码（仅正式版） |
| v5.37 | 58 | 1058 | 云端语音合成（多协议）、音色克隆、语音朗读归属、输出风格护栏、启动/退出问候总开关、新应用图标、表情包命中率修复（38%→75%）、表情包目录兜底 |
| v6.0 | 59 | 1059 | App 界面视频背景（仅玻璃态）：视频替换 Mesh + 压暗遮罩、预览裁剪界面、锁定屏幕比例；修复换视频不生效、点开关 NPE 崩溃、开关整页重建闪屏 |
| v6.1 | 60 | 1060 | 修复「预览与实景不一致」：`TextureView.setTransform` 必须用视图空间矩阵（此前叠了两层缩放）、`pendingCrop` 在布局前被丢弃、`applyCrop` 会多裁一点；去掉唯一会变形的「拉伸」铺法；`setTransform` 相关诊断日志 |
| v6.5 | 64 | 1064 | ① 语音模型独立分发：sensevoice.int8.onnx（228MB）不再内置，改为随交付 zip 单独分发，设置页可删除/从文件导入，APK 由 568.6MB 瘦身到 340.5MB；② 长按菜单重做（行宽按 `measureText` 实测、`panelK` 下限放开）与标题栏让位状态栏；③ 贴边吸附：`PetDockState` 状态机（NORMAL/COLLAPSED/EXPANDED），长按菜单手动吸附成细条、向内滑动跟手展开、竖条可沿边缘拖动；④ 桌宠运行时后台不显示 app 任务窗口：清单 `excludeFromRecents` + 运行期踢卡片 + `onPause` 移除任务，划掉卡片不杀桌宠（`onTaskRemoved` 重拉），常驻通知降到 `IMPORTANCE_MIN` 静默渠道 |
| v7.0 | 70 | 1070 | ① **动作系统独立**：新增 `SceneRegistry`（场景清单＝代码侧唯一真相）+ `ActionSystem`（场景层，吃 2.0 `pack.json` 与 1.x 老配置两条路），`PetView` 的硬编码状态 switch 改为场景驱动，并接上此前从没被触发过的 `click` / `drag` 两个分支；② **角色系统骨架**：`PetCharacter` / `CharacterStore`（角色 = 形象＋动作套组＋表情＋性格，记忆按用户要求保持全局），内置角色只读；③ **取消待机随机换动作**：待机只播默认那一个，原来 80 个随机动作进 `pool` 场景（默认关闭，可开）；④ **APK 瘦身 340.6MB → 34.9MB**：106 个动作移到工程 `model/`，另打成「默认角色包.zip」随交付分发；⑤ 删除旧素材层（`custom_webp` + 两份 custom json + 导入 UI，运行时不再读取；设备上旧文件保留，设置页提供「清理旧素材」）；⑥ 删除"代码绘制桌宠本体"230 行死代码；⑦ **（轮次⑳）场景动作可编辑**：按角色的用户覆盖层 `SceneBindings`（`filesDir/scenes/<角色 id>.json`，只替换被改过的场景）+ 设置页「🎬 场景与已绑动作」可改绑/权重/预览/恢复默认 + 「📚 动作库」，并修掉"`pack.json` 里的 weight 从来没生效"；⑧ **（轮次㉑）角色包导入简化**：`anim/pack.json` 变成**可选**（`ClipNaming.synthesizePack` 按文件名自动合成+归类）、**文件名前缀就是场景**（前缀表派生自 `SceneRegistry` 标签，无前缀进 `pool`）、`isClip` 放开 **PNG/JPG 静态动作**、App 内「➕ 添加动作」与场景内「📥 导入动画到此场景」（SAF 多选 → 落用户素材目录 → 走覆盖层绑定，不动角色包一个字节）、导出按**当前绑定**产出可回导的自洽包；⑨ **（轮次㉒）角色切换 UI**：新增纯类 `CharPicker` + 设置页「🎭 当前角色：… ›」可点入口（`● 当前`/`○ 其他`/内置标出、报出角色个数）、切角色写 pref + 重载悬浮窗（每个角色带自己的一整套动作/台词/表情/性格/绑定）、「🗑 删除角色…」（内置不可删、删当前角色自动回内置、连覆盖层/用户素材/自定义菜单一起清），并修掉删除路径误用会 `mkdirs` 的 `clipsDirFor`（删完留空壳目录） |
| v7.5 | 75 | 1075 | ① **（轮次㊾~㊿、51~54）玻璃态自定义配色**：覆盖层挂在唯一令牌入口 `Tokens.of(Context)` 上（100+ 调用点零改动），主色调/背景底色（24 格调色卡 + HSV 三段滑块 + 手填 HEX）、文字颜色自动重推、从背景图取色（四候选）、实时预览卡、配色方案导出/导入（带校验闸）；② **（轮次㊿、55）UI 透明度三项**（底色/卡片/弹窗，各带安全下限）与 **Mesh 光斑可调**（浓度/扩散/跟随主色）；③ **（轮次54、55）配色体检**：设置页直接显示读数，口径是"卡片叠**光斑极值**"的最坏那一处（WCAG AA），调透/调浓读数跟着变；④ **（轮次56）自定义对话 API 可用了**：放行**明文 HTTP**（`network_security_config`；targetSdk 34 起系统默认拦，会静默导致"请求根本没发出去"）、回环地址（`127.0.0.1` 是设备自己）当面提醒 + `Endpoint` 纯类判据、失败原因不再被吞（`modelsError`/`HttpResult`）、新增「🧪 测试连接」、修掉"强制参数"开关会连带发送 DeepSeek 专有 `thinking`/`reasoning_effort` 把第三方网关打成 400；⑤ **（轮次57）点击音效**：戳桌宠响一声，波形**运行时合成**（纯类 `ClickSound` + 播放类 `ClickPlayer`，AudioTrack MODE_STATIC 复用、失败静默），音色 3 档 / 音量 3 档、改档即试听、默认开；⑥ **（轮次58）工程瘦身**：清理旧包物证与过期文档（释放约 66.5 MB），并把"逐轮日志只留最近 5 轮"固化成清理脚本规则；⑦ **（轮次62~64）本地 GGUF 模型纯本机对话**：交叉编译 llama.cpp（arm64-v8a）+ JNI（`libdspllm.so`，Java 入口 `LocalLlm`，+7.04 MB 已 strip）→ 设置页「🤖 本地模型（手机本机跑，不联网）」导入 `.gguf`（**只认文件头 `GGUF`**、流式拷进 `filesDir/models/`、带进度可取消、可切换、可删）→ 对话后端多一项「本地模型（手机本机跑，不联网）」（`ChatProvider.LOCAL_ID="local"`）：**分流点唯一在 `ChatRouter`**，本地分支经 `LocalChat`/`LocalPrompt` 走 `LocalLlm`，**一个 HTTP 都不发**（连联网检索都关掉）、不需要 API Key、不计用量；模型**第一次用到时载入一次后常驻**，换/删/切回在线才卸载；本地链**流式逐字出话**；没导入模型时给明确引导，并如实说明"小模型慢一些、也更笨"。★★ **诚实边界：这个功能至今一次都没在真机上跑过**（包只有 arm64-v8a，本机模拟器是 x86_64）—— 全部验证都是**离线断言**；验收请照 `交付说明.md`「七、5」的 **21 条真机验清单**（装包 → 导入 → 断网对话 → 性能与边界，每条都有可观测判据） |
| v7.6 | 76 | 1076 | ① **贴边挂边（探头）**：`PeekPose` 纯类阶段机（挤入/探出/挂住/小探头/抬头/缩回/收尾），身体整只绘制靠屏幕边缘自然遮挡（**不用 clip**），旋转以肩线为轴；拖动跟手（绝对位移）、单击/双击/三击/长按四路分派与普通态共用一份。② **设置**：贴边形态（挂边露头/压扁竖条）、挂边角度 0~45°、挂出深度 30~95%、挂边预设三档（只露头/半身/全身）。③ **动态表情**：GIF / 动图 WebP / mp4 / webm （按文件头判类型、动图 `AnimatedImageDrawable`、视频 `MediaView` 首帧 + 气泡静音循环播放）。④ **主页自定义名字**（按角色存）。⑤ 设置页图标补齐 10 枚自绘矢量图标（缺图标行 7→0）。⑥ 修若干真机报障：挂边有概率挂不上、挂边点击/菜单/对话不可用、倾斜过头闪回。 |
| v8.0 | 80 | 1080 | ① **工作模式全屏**：去掉占一行的顶栏（⟳/标题/地址/⚙/✕），网页铺满整屏（连系统栏一起收起，边缘轻扫可临时叫出），右上角一颗半透明悬浮 ⚙ 进设置、底部一条极细状态条显示当前地址或失败原因；退出改用返回键或 ⚙ 里的「退出工作模式」。② 版本号升到 8.0（versionCode 80；正式版 1080）。 |
