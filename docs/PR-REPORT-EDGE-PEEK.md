# PR 报告 · 贴边挂边（探头）· v7.6

> 工程纪律：**三份证据** —— ①纯类/源码断言（可复跑）②反向自检（把代码改坏必须变红）③真机物证（坐标 + 日志 + 截图）。
> 本文件把「贴边挂边」这条功能线的三份证据集中归档，便于 review 与回溯。

- 分支/版本：v7.6（versionCode 76；正式版 1076）
- 代码面：`PeekPose.java`（新增，纯类）、`FloatingService.java`（接线）、`Prefs.java`、`MainActivity.java`、`Memes.java`、`ChatActivity.java`、`VectorIcon.java`
- 判据脚本：`build/fpstest`（纯类全量）、`build/verify_edge_peek.py`、`build/verify_icons.py`、`build/verify_memes_animated.py`、`build/verify_pet_name.py`、`build/verify_zips.py`

## 一、功能概述

「贴边吸附」新增第二形态 **挂边露头**：拖到屏幕左/右缘松手 → 挤一下 → 歪着探出去 → 挂住只露头肩；
身体照常绘制，超出屏幕的部分由屏幕边缘自然裁掉（**不使用 clip**，见 §四-①）。

交互：单击＝抬头 + 正常点击反馈（音效/部位动作/台词/连戳互动）；双击＝「问一句」输入条；
三击＝动作菜单（AI 对话入口）；长按＝功能气泡（其中「拉出来」在挂边态显示）；
拖动＝跟手拉出（拉够就出来、没拉够自己挂回）。

## 二、证据 ①：断言（可复跑）

| 门 | 覆盖 | 结果 |
|---|---|---|
| `build/fpstest` | 纯类全量 583 项，其中 `[35] PeekPose` 37 条（阶段机、跟手位移、`peekedX ⇄ keepForX` 互逆 <0.02、`leanForKeep` 单调、PROBE 终点==设定角度且全程不超、SETTLE 起终点与单调） | 583/583 |
| `build/verify_edge_peek.py` | 47 项接线判据（dockCollapse 分支、拖动接管、余额气泡门控、Tuning 走 Prefs、设置行、双击/三击分派共用、吸附用未放宽范围、留白未就绪重试、越界也认、临时收起冻帧） | 47/47 |
| `build/verify_icons.py` | 15 项（设置页每行都能解析出图标；新图标在 `pathFor` 与 `prototype.html` 双登记） | 15/15 |
| `build/verify_memes_animated.py` | 19 项（动态表情上传/落盘/播放链路） | 19/19 |
| `build/verify_pet_name.py` | 16 项（主页自定义名字全链路） | 16/16 |
| `build/verify_zips.py` | 交付 zip 内 APK 与刚编产物一致 | 三包 OK |

## 三、证据 ②：反向自检（改坏必须变红）

- `build/verify_edge_peek.py` 内置反向检查：把 `PEER_KEEP` 改成 `1.0f`（＝不挂了）⇒ 必须红；
  恢复 ⇒ 必须绿（脚本自动做，红/绿都打印）。
- `build/reverse_check_character_skeleton.py`：30 项故意破坏全被断言抓到（跳过按未验证计）。
- 本轮修掉的两处**会假绿**的地方：
  - `verify_character_skeleton.py` 原来硬编码 `versionName="7.5"` ⇒ 升 7.6 后整条假红；
  - `reverse_check_character_skeleton.py` 的「版本号退回 6.5」锚点也硬编码 7.5 ⇒ 变成 SKIP（按未验证计即失败）。
  两处都改成**版本无关**判据（清单自洽 + 从清单读当前值）。

## 四、五个踩过的坑（review 时重点看）

1. **不能用 `setClipBounds` 只画上半身**：裁切会切掉脸、呼吸时反复切片、LIFT 后脚消失（用户两次报"下半身不显示"）。
   ⇒ 彻底删掉 clip，身体永远整只绘制，靠屏幕边缘自然遮挡。
2. **倾斜要以"肩线"为轴**（`SHOULDER_FRAC=0.34`），以帧中心为轴会像"斜着滑倒"。
3. **拖动必须绝对位移**：早期用"钳制下界"推导，用错了贴边位（`-210` vs 挂边位 `-289`）⇒ 第一次 MOVE 就闪回。
4. **Android 边缘返回手势会抢触摸**：`InputDispatcher: edge-swipe is stealing input gesture` ⇒
   必须 `setSystemGestureExclusionRects(root + petImage)`（三键导航是证明因果的对照组）。
5. **手势分派只许有一份**：挂边分支曾自己抄了一套（只有"长按/单击"）⇒ 双击/三击失效，
   表现为"贴边时菜单和对话都用不了"；现在统一走 `dispatchPetTap`。
   同理，挂边启动的三条静默 return（留白未就绪 / 吸附目标用了放宽范围 / 容差 6dp）
   会让"有概率挂不上"，现在分别是：重试 + 用未放宽范围 + 容差 24dp 且越界也认。

## 五、证据 ③：真机物证（pet411，Android 16，1080×2400，density 2.625）

| 项 | 物证 |
|---|---|
| 几何 | 窗 630×354，身体 207×272；左贴边位 `baseX=-210`，挂边 `x=-289`（=−210−207×0.38）；右缘 `x=740` |
| 挂边 | `DSPPeek: 挂边探头 开 边=左 baseX=-210 bodyW=207`；`x=-289` |
| 点击互动 | `x: -289 → -231`（抬头）+ `DSVoice: 本地台词 scene=click line=主人手欠哦～` |
| 双击/三击 | 双击 ⇒「问问傲娇小深…」输入条；三击 ⇒ 动作菜单（截图 `peek-device/DA1-triple-menu.png`、`DA2-double-ask.png`） |
| 拖动跟手 | 连续 `input swipe`：`-289 → -179 → 0 → 290` 逐点跟手（分步 `input motionevent` 只送 1 个 MOVE，不能用） |
| 角度/深度 | 30°/55% ⇒ `x=-324`；0°/20% ⇒ `x=-251` |
| 预设三档 | 选「只露头」⇒ 角度行变 24°、预设行变「只露头」（截图 `peek-device/EA1-preset.png`） |
| 可靠性 | 普通拖边 6/6、LIFT 窗口内拖边 6/6 全部挂上，无"跳过/放弃"日志 |
| 锁屏/临时收起 | 锁屏→解锁仍 `x=-289`；`HIDE_TEMP`/`SHOW_TEMP` 不破坏姿态（并已冻结帧循环省电） |

截图目录：`D:\用户\DS\peek-device\`（P/U/V/W/X/Y/Z/AA/AB/AC/AD/BB/BC/CA/DA/EA 系列）。

## 六、明确未做

- **子宠（小鲸鱼）不挂边**：只作用于主窗口的 `params` 与触摸；子宠有独立窗口与位置管理，未接。
- **漫游"撞边自动挂"**：`wanderDockOnArrive → snapToNearestEdge → peekBegin` 链路在，
  但漫游方向规则（`ContentInset.roamDirAway`）会优先朝空间大的一侧走，实测 60s 随机游走未触发"目标被边缘夹住"。
  已修：漫游开走前先退出挂边，避免走两步又被"抬头到点"拽回去。
- **动态表情的上传 UI 流程**未自动点验（系统相册选择器不稳定），等价验证用"直接把 GIF 丢进 memes_custom"。
- **名字按角色独立**已实现存储层（`pet_name_<角色id>`，未设过的角色回落全局名），
  但多角色切换的端到端验证还没做（需要导入第二个角色包）。
