# Phase 0｜盘点报告（对照《DeepSeekPet_Harness 融合移植方案》）

> 依据：`AGENT_TASK.md` 第一阶段要求「先交付盘点报告、调用关系、最小改动计划和风险，再改代码」。
> 所有结论都用**真实文件/方法**做依据；读的是沙盘里的代码，不是总览里的描述。

## 一、隔离与基线（已核实）

| 项 | 值 |
|---|---|
| 开发目录（沙盘） | `D:\petport_lab\petbuild`（母本 `D:\petbuild` **零写入**） |
| 母本完整性 | 940 文件 / 893,391,585 字节，与 `F:\PetProject_Backup\snapshot_20261023_021743` 快照**逐字节一致**（每轮结束复验） |
| 构建命令 | `python D:\petport_lab\tools\build_win.py`（aapt2 36.0.0 → javac → d8 → assemble.py → apksigner），产出 `dist\DeepSeekPet_v7.6_rebuilt.apk` |
| 测试入口 | `build\run_all_tests.bat`（盘点时 **78 套**；本轮实施后 **80 套**，约 50 分钟） |
| 当前基线读数 | `ALL SUITES PASSED` / `[FAIL]` 0 / `[OK]` 3944；`dshtest` 164 条；`verify_dsh_work` 正向 ~120 项 + 反向 26 项 |
| APK | 15.3 MB，dex `766c27e7…`，连编两次 dex 一致 |
| 真机 | **无**（`adb devices` 长期为空）⇒ 本阶段全部为离线断言，真机项另列 |

## 二、代码盘点（按方案要求四分类）

### ✅ 已实现（有实现 + 有断言）
| 能力 | 依据 |
|---|---|
| 会话与事件日志（与上游 `session.v3` 同构） | `dsh/DshSession.java`（事件构造/投影）、`dsh/DshSessionLog.java`（追加式 JSONL、坏行跳过） |
| OpenAI 兼容 wire（tools / tool_calls / reasoning_content） | `dsh/DshWire.java` |
| system prompt 装配 / 技能 | `dsh/DshPrompt.java`、`dsh/DshSkill.java`（`SKILL.md` front-matter + `<available_skills>`） |
| 工具协议（JSON Schema + 白名单 + 权限闸 + 错误归一） | `dsh/DshToolSpec.java`、`dsh/DshTool.java`、`dsh/DshToolRegistry.java` |
| 9 个工具 | `dsh/DshTools.java`：`list/read/write/stat/search/web_fetch/skill/todo/now` |
| 插件体系（关掉的插件对模型不可见） | `dsh/DshPlugin.java` + `DshTools.forPlugins(...)` |
| 权限沙箱（路径穿越唯一出口） | `dsh/DshPolicy.resolve(...)`；`minModeOf(...)` 三档 |
| agent loop（turn/step + maxSteps/maxTokens + 取消） | `dsh/DshAgentLoop.java`（`cancel()` 在 step 之间与模型返回后生效） |
| token 计量 / 压缩（配对不拆） | `dsh/DshMeter.java`、`dsh/DshCompaction.java` |
| 双模式切换（陪伴 ⇄ 工作，带可自定义动画/视频） | `DshModeEntry.java`（全文件仅一处 `startActivity`）、`dsh/DshTransition*.java` |
| 工作台页面（结构照搬桌面端，配色跟 App 主题） | `DshWorkActivity.java`（左栏/标准模式/对话‖轨迹/消息流/圆角输入框/统计行） |
| 桌宠在工作台可见 | `DshWorkActivity.onResume()` → `FloatingService.ACTION_SHOW_TEMP`；`Prefs.harnessShowPet` |
| 视频背景同步 | `DshWorkActivity.attachVideoBg(...)`（复用 `VideoBackgroundView` + `Prefs.videoBg*`） |
| App 头像（与"形象"分离） | `PetAvatar.java` |

### ⚠️ 部分实现
| 能力 | 现状 | 缺什么 |
|---|---|---|
| `/` 指令 | 只有 `composerTip` 提示条（`updateComposerTip`） | **没有命令表、没有解析、没有执行** |
| `@file` / `@conversation` | 同上，仅提示文案 | 没有文件检索、没有路径校验、没有上下文注入 |
| 停止 / 取消 | `DshBridge.cancelCurrent()` → `DshAgentLoop.cancel()` 可达 | **但 `DshWorkActivity.onDestroy()` 会无条件 `cancelCurrent()`** ⇒ 离开页面等于取消任务（见下） |
| 任务状态可见性 | 工作台内能看到事件流 | 桌宠/悬浮窗**没有**任务状态（空闲/思考中/调用工具/等待确认/已完成/失败） |

### ❌ 未实现
| 能力 | 说明 |
|---|---|
| 排队发送 | 运行中再发会被 `setRunning` 拦住（input 禁用），没有队列 |
| 附件 / 图片 | 输入区「＋」只 toast「本轮暂未开放」 |
| 任务与 Activity 解耦 | **执行发生在大 Activity 里**：`DshWorkActivity.send()` 里 `new Thread(... DshBridge.chat ...)` ⇒ Activity 重建/离开就断 |
| 进程重启后的"状态待确认" | 无任务记录，重启后只能看会话日志，不知道有没有跑一半的任务 |
| 电脑端后端（RemoteBackend） | 无服务端、无协议实现、无配对/认证 |
| 三档能力探测（普通/ADB/Shizuku/Root） | 只有 `DshPolicy` 的**产品档位**，没有 Capability Provider；`ShizukuBridge` 存在但未接入工具层 |
| 自动化任务 / 多工作区 / 创造模式 / 推理强度 / diff 视图 | 均无 |

### ❓ 尚不能确认（需要真机）
悬浮窗服务重建时是否损坏会话；`targetSdk` 后台限制下长任务的存活时长；Shizuku 实际授权状态与可访问范围。

## 三、现状调用链（问题所在）

```
DshWorkActivity.send()
  └─ new Thread → DshBridge.chat(ctx, text, cb)
        └─ DshAgentLoop.run(...)          ← 执行发生在 UI 所在的线程里
  onDestroy() → DshBridge.cancelCurrent()  ← ★ 离开工作台 = 取消任务
```
⇒ 违反方案目标 A（"任务运行时返回桌宠不自动取消"）与 02 原则（"UI 不直接执行 Agent loop""任务生命周期独立于 Activity"）。

## 四、对照方案三条目标的差距

| 目标 | 差距 |
|---|---|
| **A 同 App 无缝切换** | 缺 Task Coordinator；`onDestroy` 取消任务；无幂等（连点可能重复提交）；重启后无"待确认"状态 |
| **B 手机 + 电脑双后端** | 只有"手机本地"这一条，且**没有 Backend 抽象**；无协议、无配对、无 health/capabilities/query |
| **C 普通 / ADB / Root 三档** | 只有 Policy 档位；缺 Capability/Authorization/Approval 四层区分；UI 上不能区分"策略允许"与"真实可执行" |

## 五、本轮最小改动计划（Phase 1 → 3，不做 4~6）

| 阶段 | 做什么 | 新增文件 | 为什么先做这个 |
|---|---|---|---|
| **P1 任务闭环** | `DshTask`（状态机）+ `DshTaskStore`（落盘、重启后未终结 ⇒ `UNKNOWN_AFTER_DISCONNECT`）+ `DshTaskCoordinator`（单例、幂等 submit、取消穿透、排队、多监听者、Activity 重建后 attach 拿快照）；`DshWorkActivity` 改为**只订阅不执行**，`onDestroy` 不再取消 | `dsh/DshTask.java`、`dsh/DshTaskStore.java`、`DshTaskCoordinator.java` | 这是"无缝切换"的地基，也是当前最明显的缺陷 |
| **P2 交互** | `/` 命令表与解析（`/help /new /model /perm /plan /stop /status`，未支持项**显式拒绝**不假成功）；`@file`（检索 + 存在性/大小校验 + 注入 `<file>` 块）、`@conversation`（会话摘要注入，长度上限）；运行中发送 ⇒ **排队**并显示"已排队 N 条" | `dsh/DshCommand.java`、`dsh/DshMention.java` | 方案 03 的分批顺序 |
| **P3 后端抽象** | `DshBackend` 接口（getBackendId/getStatus/getCapabilities/submit/query/cancel）+ `DshLocalBackend` 包装现有核心，**行为不变**；UI 不再依赖 `DshAgentLoop` 内部 | `dsh/DshBackend.java`、`dsh/DshLocalBackend.java` | 为 P4 的 RemoteBackend 留出唯一缝 |
| 不做 | RemoteBackend 服务端、三档真实能力探测、自动化/多工作区/创造模式/推理强度/diff | — | 方案 06 明确"单独立项，不要塞进同一提交"；且都要真机/服务端配合 |

## 六、风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 改 `DshWorkActivity` 会打断既有断言锚点（正向 ~120 + 反向 26） | 套件变红 | 保留全部锚点；改完先单跑 `verify_dsh_work` + 反向，再跑全量 |
| 任务落盘与并发（同会话两处写） | 会话日志交错 | `DshTaskStore` 单写者（Coordinator 独占）；日志追加式天然容错 |
| Coordinator 单例与进程重启 | 状态错乱 | 启动时读 `tasks/latest.json`；未终结 ⇒ `UNKNOWN_AFTER_DISCONNECT`，**不自动重跑** |
| 排队消息与取消竞态 | 取消后仍发下一条 | 队列与 task 同生命周期；`CANCELLING` 状态下不再出队 |
| 仍无真机 | 悬浮窗重建、后台存活、Shizuku 授权无读数 | 两类结果分开报告；真机清单单列（沿用方案 07） |
