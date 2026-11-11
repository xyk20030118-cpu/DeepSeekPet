# Phase 1~3 交付报告（按方案《DeepSeekPet_Harness 融合移植方案》实施）

> 依据：`AGENT_TASK.md` + `docs/01~07`。本报告按方案要求的「每阶段交付」格式写：
> 目标 / 变更文件及理由 / 不变行为 / 配置 / 新增正向与反向测试 / 构建结果 / 真机验证 / 风险与下一步。
> 全程在隔离沙盘 `D:\petport_lab\petbuild`，母本 `D:\petbuild` 零写入。

---

## Phase 1｜同 App 任务闭环

### 目标
桌宠 ↔ 工作台切换时任务不丢；取消传到执行层；防重复；进程重启后"状态待确认"而不是装懂或重跑。

### 变更文件及理由

| 文件 | 变更 | 为什么 |
|---|---|---|
| `dsh/DshTask.java`（新，178 行） | 任务状态机：`CREATED/QUEUED/RUNNING/WAITING_FOR_APPROVAL/WAITING_FOR_BACKEND/CANCELLING/SUCCEEDED/FAILED/CANCELLED/UNKNOWN_AFTER_DISCONNECT` + `terminal()/active()/label()` + JSON | 方案 02 要求"任务状态显式化"，并明确要有"断线/重启后未知"这一档 |
| `dsh/DshTaskStore.java`（新，137 行） | `files/dsh/tasks/<id>.json` + `latest.json`；`recover()` 把**未终结**任务标成 `UNKNOWN_AFTER_DISCONNECT` 并落盘 | 进程被杀后"到底跑没跑完"要有答案；不能当"没跑过"重跑（副作用会执行两次） |
| `DshTaskCoordinator.java`（新，329 行） | 进程级单例：`submit`（幂等：有活动任务 ⇒ 只入队）/`enqueue`/`cancel`/`attach`/`detach`/`snapshot`/`useBackend` | 方案 02 的 Task Coordinator；任务生命周期与 Activity 解耦 |
| `DshWorkActivity.java`（改） | ① `onDestroy` **删掉** `DshBridge.cancelCurrent()`，改成 `coord.detach(...)`；② `send()` 不再 `new Thread + DshBridge.chat`，改成 `coord.submit(...)`；③ 新增 `buildCoordListener()` 订阅状态/正文/工具/错误；④ 状态行显示「任务：运行中 / 排队 N 条」 | 这就是方案目标 A 的核心缺陷：**离开页面 = 取消任务** |

### 不变行为
会话格式、事件日志、工具集、提示词、权限档、插件开关、视频背景、桌宠显示策略**一条都没改**。

### 配置
无新增用户可见开关；任务目录 `files/dsh/tasks/` 随功能自动创建。

### 新增测试
- `build/dshtest` 第 [12] 节（**11 条**）：状态机往返、终态/活动态、非法值归零、
  **重启恢复 ⇒ UNKNOWN 且不自动重跑**、已完成任务不被改。
- `build/verify_dsh_task.py`（新套件，**17 条**）：onDestroy 不取消、页面不自己跑 loop、
  提交幂等、取消穿透链、recover 不重跑。
- `build/reverse_check_dsh_task.py`（新套件，7 项破坏）：A onDestroy 又取消 / B 提交不幂等 / C 恢复装懂。

---

## Phase 2｜工作台交互（/ 命令、@file、@conversation、排队发送）

### 目标
方案 03 的分批顺序：`/` → `@file` → `@conversation` → 附件 → 停止/排队。本轮做前三项 + 排队；附件**明确未支持**。

### 变更文件及理由

| 文件 | 变更 | 为什么 |
|---|---|---|
| `dsh/DshCommand.java`（新，158 行） | 命令表（`/help /new /model /perm /stop /status`，另有 `/plan /attach` 标 **supported=false**）、`parse()`、`helpText()` | 方案要求"命令列表、参数解析、帮助和未知命令错误"；并明确**未实现的要明说**，不能只 Toast 呈现为已实现 |
| `dsh/DshMention.java`（新，171 行） | `@file:` / `@conv:` / 裸写 `@name` 解析；`resolveFiles()` 走 `policy.resolve` + 存在/目录/大小校验；`contextBlock()` 带 `<file path=…>` 标签、**超限报错不静默截断** | 方案要求"文件搜索/选择、路径校验、结构化上下文传递"；路径必须过沙箱 |
| `DshWorkActivity.java`（改） | 输入分支：命令走本地执行；引用先校验再发（失败只提示不发送）；运行中发送 ⇒ 排队并提示「已排队 N 条」；新增 `addLocalNote()`（命令回执/引用报错） | 把提示条升级成真功能 |

### 行为细节（写死，便于断言）
- **前导空格 = 不是命令**：想发一句真以 `/` 开头的普通消息，前面加空格即可（`DshCommand.looksLikeCommand` 不 trim）。
- `/perm` 只接受 `read|write|full`，其他参数**报错**而不是当没写。
- `@file` 引用超 256 KB、`@conv` 注入后总长超 32 K 字符 ⇒ **报错并拒绝发送**。
- 队列**不跨进程持久化**：进程被杀时队列清空（理由：重放可能带副作用的操作，风险大于收益）。

### 新增测试
- `dshtest` 第 [13] 节（**22 条**）：命令解析/参数校验/未知命令/未支持命令、`@` 解析（邮箱不误判）、
  引用校验（不存在/越界/2 个上限）、注入块标签与超限。
- `verify_dsh_task.py` 的 [B] 段（**15 条**）。
- 反向项 **D**（未知命令不报错）/ **E**（@file 不校验存在）。

---

## Phase 3｜LocalBackend

### 目标
定最小接口、包装现有本地核心、**行为不变**；UI 不再依赖 agent loop 内部。

### 变更文件及理由

| 文件 | 变更 | 为什么 |
|---|---|---|
| `dsh/DshBackend.java`（新，130 行） | 接口：`getBackendId/getStatus/getCapabilities/submit/query/cancel` + `Status/Capabilities/Request/Callback` | 方案 02 的接口草案；**任务 id 由调用方给**（幂等与"断线先查"都靠它） |
| `dsh/DshLocalBackend.java`（新，137 行） | `implements DshBackend`：`submit` 自开线程跑 `DshBridge.chat`；`cancel` → `DshBridge.cancelCurrent()`；`getCapabilities().tools` 来自 `DshPlugin.visibleTools(...)` | Phase 3 要求"包装现有核心、行为不变"；能力清单必须与模型实际看到的工具一致（不能靠开关伪造） |
| `DshTaskCoordinator.java` | 持 `DshBackend`，提供 `useBackend(...)` | Phase 4 的 RemoteBackend 从这里接入，UI 一行不用改 |

### 不变行为
`DshBridge.chat` 的入参与返回、会话与事件格式、工具集、压缩与计量**全部照旧**。

### 新增测试
- `verify_dsh_task.py` 的 [C] 段（**10 条**）：接口方法齐、能力清单是数据、LocalBackend 实现、
  协调器可换后端、**UI 不再出现 `DshAgentLoop`**、任务 id 由调用方给。
- 反向项 **F**（UI 又自己跑 loop）/ **G**（能力清单不再来自插件）。

---

## 构建结果

```
python D:\petport_lab\tools\build_win.py
→ dev APK 15.3 MB · dex 1638aa05… · 「OK APK 内 dex 与编译产物一致」
→ 连编两次：同一个 dex（1638aa05…）
```

| 项 | 结果 |
|---|---|
| `build\run_all_tests.bat`（**80 套**，78 → 80） | **`ALL SUITES PASSED`**，`[FAIL]` **0**，`[OK]` **4043** 条（`lab_all_testsL.log`） |
| `build/dshtest`（纯 Java 真编真跑） | **203 条**（新增 [12] 任务状态机/[恢复]、[13] 命令与引用） |
| `verify_dsh_task.py` / `reverse_check_dsh_task.py` | **42 项** / **7-7 破坏全部被抓到** |
| 母本 `D:\petbuild` | 940 文件 / 893,391,585 字节，与快照**逐字节一致** |

## 真机验证

**未做**。`adb devices` 仍为空，本轮全部是离线断言。**需要真机的项**（沿用方案 07）：
悬浮窗服务重建是否损坏会话、`targetSdk` 后台限制下长任务存活、Shizuku 授权状态与可访问范围、
以及"返回桌宠后任务继续"的实际观感。

## 风险与下一步

| 风险 | 处置 |
|---|---|
| Coordinator 单例与多 Activity 并发 | 所有状态变更 `synchronized(this)`；监听者数组快照后回调 |
| 队列与取消竞态 | 取消后不再出队（有断言）；队列不跨进程 |
| LocalBackend 只支持"同时一个任务" | 与现状一致（`DshBridge` 是单 loop）；RemoteBackend 接入时按 taskId 分派 |
| 附件仍不可用 | UI 上明确「本轮暂未开放」，方案 03 要求"未实现的按钮禁用或提示未支持"已满足 |

**下一步建议**（按方案 06）：
1. Phase 4 电脑端后端：先定配对/认证 + health/capabilities，再开只读任务；
2. Phase 5 三档能力：`CapabilityProvider` 真实探测（普通 / Shizuku / Root），UI 只读不能伪造；
3. Phase 6 单独立项：自动化、多工作区、对话造插件、推理强度、diff/图片预览。
