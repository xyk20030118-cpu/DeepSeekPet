# 项目总览（可直接发给其他 AI 讨论方案）

> 生成时间：2026-10-23 · 供外部 AI 评估"下一步怎么走"用。
> 本文件只写**事实**与**硬约束**；方案部分留白给讨论（末尾有"请对方回答的问题"）。

---

## 一、一句话

把 DeepSeek 官方的 **DeepSeek Harness（一个 Node/Electron 的 agent 内核）** 移植进一个**纯 Android framework 的桌宠 App**，
目前已经做到：**双模式**（陪伴 / 工作）、**工作模式界面照搬桌面端结构**、**内核用 Java 重实现并被插件化**、**全量离线断言套件 78 套全绿**。
**还没做**：真机验证、`/指令` 与 `@文件` 的真功能、自动化任务、创造模式（对话造插件）、多工作区。

---

## 二、两个工程分别是什么

### A. 目标工程：DeepSeekPet（桌宠 App）
| 项 | 值 |
|---|---|
| 包名 / 版本 | `com.deepseek.pet` · versionName 7.6 · versionCode 76 |
| 平台 | Android 7.0+（minSdk 24）· **仅 arm64-v8a** |
| 语言 | **Java 8 语法**（`-source 8 -target 8`，不能用 `var`）· 纯 framework，**零 androidx**、无 Compose、无 Material |
| UI | 全部代码构建视图（`Tokens` 设计令牌 + `UiFactory` 组件），支持新拟态/玻璃态双引擎、明暗、自定义玻璃配色、视频背景 |
| 构建 | **不用 Gradle**：自研链路 `aapt2`(Windows SDK 36.0.0) → `javac` → `d8` → 自研 `assemble.py`（控制 .so 页对齐）→ `apksigner` |
| 现有能力 | 悬浮桌宠（前台服务）、角色包/动作系统、对话（多家服务商 + 本地 GGUF）、语音识别/TTS、余额气泡、联网检索、游戏助手… |
| 代码规模 | `src/` 约 130 个 .java；`MainActivity` 1.1 万行、`FloatingService` 5 千行（单体大文件） |

### B. 来源工程：DeepSeek Harness（官方，v8.0.5-rc.2）
- DeepSeek 开源，公开预览；基于 **Cordis「一切皆插件」**；可桌面端，也可代码启动网页。
  （官方页：https://www.deepseek.com/harness/ · 英文 https://www.deepseek.com/en/harness/）
- 桌面客户端 = Electron + 200+ 个 `@deepseek-ai/dsh-*` 包（LLM 层、agent loop、tools、session 持久化、skills、sandbox、JSON-RPC/ACP server、React 客户端 UI…）。
- **Android 上没有 Node 运行时** ⇒ "把内核整个塞进 APK"不存在，只能**契约移植**。

---

## 三、移植采取的定义（一句话）

> **把 harness 的"骨架与协议"用 Java 重实现，把"手脚"换成手机的手脚，**并让**桌宠的能力成为 harness 的插件**。**

三条落地原则：
1. **内核契约照抄**：事件会话日志（与上游 `session.v3` 同名同构）、system prompt 装配、`SKILL.md` 技能、工具协议（JSON Schema + tool-call/result）、agent loop（turn/step）、权限沙箱、token 计量与压缩。
2. **执行面本地化**：没有 shell；工具 = 应用私有目录读写、网页抓取、技能、待办、时间、正则搜索、文件信息、**桌宠自身状态**。
3. **主题跟 App、结构跟桌面端**（用户明确口径）：配色/明暗/玻璃态/自定义配色/视频背景 **一律走 App 的 `Tokens`**；字号/行高/圆角/间距 **照桌面端 CSS 的量值**。

---

## 四、硬约束（这些直接决定方案能不能落地，请务必先看）

1. **无 Node / 无 shell**：Android 侧不能跑 harness 本体、不能给模型任意命令执行。
2. **Java 8 语法**、纯 framework、**不允许 androidx/Compose**；新页面必须走 `Tokens` 令牌（有断言查"活代码零 8 位十六进制色值"）。
3. **所有 .java 必须是 LF 换行**（有断言查；CRLF 会红）。
4. **不允许 `AlertDialog` / `ProgressDialog`**（必须走 `UiFactory`，否则深色/玻璃态下会弹白底）。
5. **验证是"断言 + 反向断言"双轨**：任何新能力都要有 `verify_*` 正向判据 + `reverse_check_*`（故意改坏必须变红）。
   全量套件 `build/run_all_tests.bat` 目前 **78 套**，一次跑约 40–45 分钟。
6. **母本零改动**：整个移植在隔离沙盘 `D:\petport_lab\petbuild` 进行，母本 `D:\petbuild` 全程不写（有 SHA-256 快照与自证）。
7. **新功能"默认关"时必须同时给出"不依赖该功能本身"的打开路径**（这条是被一个死锁坑出来的教训）。
8. **版本可辨识**：界面上有 `DshFeature.BUILD_TAG`（当前 `b6`），便于判断用户装的是哪个包。

---

## 五、当前架构

```
com.deepseek.pet.dsh  （内核，纯 Java，可在 PC 上 javac 真编真跑）
├── 会话与事件   DshEvent / DshSession / DshSessionLog / DshJson
├── 协议         DshWire（OpenAI 兼容编码解码，tools / tool_calls / reasoning_content）
├── 提示         DshPrompt（四层装配）· DshSkill（SKILL.md + <available_skills>）
├── 工具         DshTool / DshToolSpec / DshToolRegistry / DshTools（9 个工具）
├── 插件         DshPlugin（7 个内置插件，决定"给模型哪些工具"）
├── 策略         DshPolicy（read-only / workspace-write / full-access + 路径穿越唯一出口）
├── 循环         DshAgentLoop（turn/step 状态机 + maxSteps/maxTokens + 取消）
├── 上下文       DshMeter（token 估算）· DshCompaction（先剪工具结果、后丢最老回合，tool_calls 与结果同生同死）
├── 尺寸         DshDesk（字号/行高/圆角/间距，取自桌面端 CSS；**不含颜色**）
├── 动画         DshTransition / DshTransitionPlayer（淡入/缩放/推入/自定义视频，2.5s 上限、静音、可跳过）
└── 桥接         DshBridge（Android 胶水：路径 / 设置 / HTTP 出口复用 ApiClient / 会话 / 插件开关 / 桌宠状态 / 取消）

com.deepseek.pet      （UI 与 App 侧）
├── DshWorkActivity   工作模式页面（照搬桌面端结构：左栏 / 顶栏+标准模式 / 对话‖轨迹 / 消息流 / 圆角输入框 / 统计行）
├── DshActivity       控制台（权限档 / 步数 / 会话日志 / 技能目录）—— 工作模式抽屉里进入
├── DshModeEntry      陪伴 ⇄ 工作 的切换（先播动画再跳；全文件只有一处 startActivity）
└── PetAvatar         App 头像（与"桌宠形象"两条独立链路，互不影响）
```

### 文件清单（行数，便于估工作量）

| 文件 | 行 | 说明 |
|---|---|---|
| `DshTools.java` | 603 | 9 个工具实现 |
| `DshBridge.java` | 505 | Android 胶水 |
| `DshSession.java` | 311 | 事件 + 投影 |
| `DshWire.java` | 267 | 协议编解码 |
| `DshAgentLoop.java` | 241 | agent loop |
| `DshTransitionPlayer.java` | 199 | 动画播放（含视频） |
| `DshPolicy.java` | 171 | 权限与沙箱 |
| `DshSkill.java` | 166 | 技能 |
| `DshPlugin.java` | 159 | 插件表 |
| `DshToolRegistry.java` | 156 | 白名单+权限闸 |
| `DshSessionLog.java` | 135 | 追加式 JSONL |
| `DshFs.java` | 136 | 读写与截断 |
| `DshJson.java` | 144 | JSON 写入器 |
| `DshCompaction.java` | 141 | 压缩 |
| `DshTransition.java` | 117 | 动画参数表 |
| `DshEvent.java` | 104 | 事件模型 |
| `DshPrompt.java` | 104 | 提示装配 |
| `DshMeter.java` | 93 | token 计量 |
| `DshToolSpec.java` | 74 | 工具声明 |
| `DshDesk.java` | 65 | 尺寸 |
| `DshFeature.java` | 48 | 开关 |
| `DshTool.java` | 30 | 工具接口 |
| **`DshWorkActivity.java`** | **1754** | **工作模式页面（最大单文件）** |
| `DshActivity.java` | 434 | 控制台 |
| `DshModeEntry.java` | 133 | 模式切换 |
| `PetAvatar.java` | 175 | App 头像 |
| 断言：`dshtest/DshCoreTest.java` | 658 | 纯 Java 真编真跑 **164 条** |
| 断言：`verify_dsh_work.py` / `reverse_check_dsh_work.py` | 336 / 212 | 正向 / **26 项破坏** |
| 断言：`verify_dsh_core.py` / `reverse_check_dsh_core.py` | 182 / 104 | 内核结构断言 / 7 项破坏 |

---

## 六、能力矩阵（现状）

| 能力 | 状态 | 备注 |
|---|---|---|
| 双模式切换（陪伴 ⇄ 工作） | ✅ | 角色卡整卡可点（不画按钮），带可自定义切换动画 + 视频导入 |
| 工作模式页面（结构照搬桌面端） | ✅ | 左栏/页签/消息流/输入框/统计行；配色跟 App 主题 |
| 事件会话日志（与上游同构） | ✅ | `files/dsh/sessions/*.jsonl`，坏行跳过，杀进程不丢 |
| agent loop（多步工具调用） | ✅ | 步数/输出上限、可取消（step 之间生效） |
| 工具 9 个 | ✅ | `list/read/write/stat/search/web_fetch/skill/todo/now` + 插件层 |
| 插件体系（一切皆插件） | ✅ | 7 个内置插件；**关掉的插件对模型不可见** |
| 桌宠自身状态交给模型 | ✅ | `pet_status` 工具 |
| 权限沙箱 | ✅ | 三档 + 路径穿越唯一出口 |
| token 计量与压缩 | ✅ | 估算 + 剪裁 + 丢老回合（配对不拆） |
| 技能（SKILL.md） | ✅ | 名字+描述注入，正文按需加载 |
| 视频背景同步 | ✅ | 复用陪伴模式那套（同源/同压暗/同声音） |
| 桌宠在工作模式显示 | ✅ | `onResume` 发 `ACTION_SHOW_TEMP` |
| 主题（配色/明暗/玻璃/自定义） | ✅ | 全部走 App `Tokens` |
| **`/指令`（斜杠命令）** | ⚠️ 仅提示条 | 输入框下有一行提示，没有真功能 |
| **`@文件或对话`（引用）** | ⚠️ 仅提示条 | 同上 |
| **附件 / 图片** | ❌ | 输入区「＋」只弹 toast「本轮暂未开放」 |
| **插话发送 / 排队消息** | ❌ | 桌面端有 |
| **自动化任务**（桌面左栏第三项） | ❌ | App 已有定时/整点开关，可对接 |
| **创造模式**（对话造插件） | ❌ | 官方能力 |
| **工作区多选 / 导入外部目录** | ❌ | 现在只有一个沙箱根 `files/dsh/workspace` |
| **推理等级（effort）** | ❌ | 只有模型切换 |
| **diff 视图 / 图片预览** | ❌ | 工具结果是纯文本 |
| **真机验证** | ❌ | `adb devices` 长期为空，全部是离线断言 |

---

## 七、验证体系与当前读数（全部可复现）

| 项 | 结果 |
|---|---|
| `build\run_all_tests.bat`（**78 套**） | **`ALL SUITES PASSED`**，`[FAIL]` **0**，`[OK]` **3944** 条（约 45 分钟） |
| `build/dshtest`（纯 Java 真编真跑） | **164 条** |
| `verify_dsh_work.py` + 反向 | 正向 ~120 项 / **26 项破坏全被抓到** |
| `verify_dsh_core.py` + 反向 | 61 项 / 7 项破坏 |
| APK | **15.3 MB**，dex `766c27e7…`，连编两次同一个 dex |
| 母本 `D:\petbuild` | 940 文件 / 893,391,585 字节，与快照**逐字节一致** |

构建命令（沙盘内，Windows）：
```
python D:\petport_lab\tools\build_win.py     # aapt2 36.0.0 + javac + d8 + assemble.py + apksigner
```
Java 编译自检（纯类，不需要 Android 设备）：
```
cd D:\petport_lab\petbuild
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath build\toolchain\android.jar ^
      -cp deps\* -d build\_cls @build\_javac_all.args
```

---

## 八、关键路径（接手要看的文件）

| 想知道什么 | 看哪 |
|---|---|
| 全量设计/实现与"为什么这么做" | `D:\petport_lab\petbuild\DSH移植-设计与实现.md`（757 行，含 11.1~11.15 十版演进） |
| 接手记忆（AI 窗口用） | `D:\petport_lab\petbuild\STATE.md` 顶部块 |
| 用户可见说明 | `D:\petport_lab\petbuild\README.md` |
| 内核入口 | `dsh/DshBridge.java`（Android 胶水）→ `DshAgentLoop.java` → `DshTools.java` |
| 工作模式 UI | `DshWorkActivity.java`（1754 行，最大单文件） |
| 断言 | `build/verify_dsh_work.py`、`build/reverse_check_dsh_work.py`、`build/dshtest/` |

---

## 九、请外部 AI 回答的问题（讨论方案用）

> 目标：**在不违反第四节硬约束的前提下**，把下面这些逐项补上，并按工作量/风险排序。

1. **`/指令` 与 `@文件或对话` 该怎么做？**
   - 约束：不许弹 AlertDialog；输入框上下空间有限；斜杠命令要不要"内置命令表 + 可扩展"？
   - 待定：命令是**纯本地动作**（切换模型/权限/新会话/导出会话）还是**注入提示词**（如 `/plan`）？
2. **附件 / 图片**：没有 SAF 之外的复杂依赖，是否先做"选图 → 复制进沙箱 → 作为 `@文件` 引用"，把多模态留到模型支持？
3. **自动化任务**：App 已有定时/整点开关，是否把"自动化任务"定义成"定时触发一次 agent 运行 + 结果写进会话"，从而复用现有调度？
4. **创造模式（对话造插件）**：Android 上没有动态代码加载（禁反射/禁 dex 热更），
   是否只能做成"用一句话生成一个**工具声明（JSON Schema）+ 命令模板**"的受限插件？边界怎么划？
5. **工作区**：要不要支持"导入外部目录（SAF tree）"作为第二个根？沙箱与 `DshPolicy` 该怎么扩展才不破坏"路径穿越唯一出口"？
6. **推理等级 / 模型能力表**：`ApiClient` 已有模型列表与能力字段，怎么把 effort 做成可持久化、可断言的一档？
7. **工作模式 1754 行单文件**要不要拆？（拆分会牵动很多断言锚点，怎么拆才不掉判据）
8. **没有真机**的前提下，怎么进一步提高"可信度"？（现有手段：纯 Java 真跑、源码结构断言、反向断言、dex 探针、
   以及"同一 dex 连编两次一致"）

---

## 十、希望你（外部 AI）产出的格式

1. **方案 A / B / C**（每项给 1~2 个可行路线，标注"改动面 / 新增文件 / 断言怎么写 / 反向项怎么设计"）
2. **风险清单**：每条注明"违反哪条硬约束"或"哪条判据会红"
3. **排序建议**：按"用户感知价值 ÷ 工作量"排，前 3 项给出可执行的分步施工单
4. **不要建议**：引入 Node/Gradle/androidx/Compose、动态加载插件代码、要求真机才能验证的方案
