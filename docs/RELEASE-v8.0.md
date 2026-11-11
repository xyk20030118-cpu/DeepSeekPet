# DeepSeekPet v8.0

桌面宠物 App 的 v8.0 交付版。**工作模式 = DeepSeek Harness 本体**，proot + Ubuntu + Node
全部打在 APK 里，装完即用，不需要电脑、不需要联网。

```
versionName 8.0 · versionCode 80 · minSdk 24 · arm64-v8a
开发版包名 com.deepseek.pet        正式版包名 com.doropet.release
```

## 下载

| 文件 | 大小 | MD5 |
|---|---|---|
| `DeepSeekPet_v8.0_dev.zip` | 291.7 MB | `0c96ee54ca0a6509a0a825aed3056627` |
| `DeepSeekPet_v8.0_release.zip` | 291.9 MB | `0e8372cec7ec2fa90bc17e9c43a3a141` |
| `DeepSeekPet_v8.0_project.zip` | 905.1 MB | `677acfe9d1cf733411b9dd06da42000f` |

单独取 APK 的话：

| APK | 大小 | MD5 |
|---|---|---|
| `DeepSeekPet_v8.0_rebuilt.apk`（开发版） | 292.3 MB | `e81c5f95187bcb24ff18f90df98f46a5` |
| `DeepSeekPet_v8.0_release.apk`（正式版） | 292.5 MB | `97a2649fa8a5dfec459460fa1229e9b3` |

## 装 & 用

1. 装 APK（允许"未知来源"）。★ 设备上装请先把 APK 拷到 `/data/local/tmp/`
   （`/sdcard` 是 `noexec`，直接装会被 SELinux 挡）
2. 打开桌宠 → 用任意一个入口进**工作模式**：
   点顶部角色卡 / 长按桌宠浮窗 →「进入工作模式」/ 设置 → 陪伴 → 工作模式
3. 第一次会自动解 Ubuntu（约 800 MB，进度在屏幕底部），解完自动起宿主，页面就出来了
4. 之后每次进都是秒开

## 本版重点（相对上一版）

- **默认端口固定 30800**，保底 30801 并记住：端口不漂 ⇒ 网页里配的模型 / API Key 不再每次重配
- 修好「复用已在跑的宿主」这条路径（旧代码读错配置格，永远命中不了）
- 修 `killLeftovers` 对 root 进程失效（Android `hidepid` 让 App 侧看不到 root 进程）：
  清理整段丢进 `su` 里跑
- 修「只杀 proot 会留下 node」：匹配面同时覆盖 `libproot*` 与 `/opt/node/bin/node`

## 已验证 / 未验证（如实写）

**真机验过**（arm64 · PA2671 / Android 16 / KernelSU root）：

- 宿主监听 30800（`/proc/net/tcp` 里 `0x7850` LISTEN），页面正常打开
- 强制停止后再进：复用已在跑的宿主，端口不变 ⇒ 网页配置还在
- 30800 被杀不掉的 root 孤儿占死时：自动退到 30801 并成功打开页面；重启后仍是 30801
- 工作区在 `/sdcard/DeepSeekHarness`，访客内 `touch` 写入 OK
- 长按桌宠 →「进入工作模式」、⚙ 菜单、退出二次确认、文件权限弹窗与跳转（用户手指实测）

**还没验**：

1. root 模式全程（真实 `uid=0`、特权目录挂载、root 宿主失败回退）
2. 在网页里手动重配模型 → 重启后配置是否保住（需要手指输入 Key）
3. 无 root 设备上的同一套流程
4. 工作模式里「添加附件」（`adb` 合成点击不可靠，需手指）

## 排障

```bash
adb logcat -d -s DSPEngine      # 宿主启动全过程，无 root 也能看
```

| 现象 | 怎么办 |
|---|---|
| 卡在"启动 Linux 宿主…" | 正常，首次在解 800 MB（3–8 分钟） |
| 端口 30800 被占 | 会先清残留进程再试；清不掉自动用保底 30801 并一直用它 |
| 左下角「宿主插件启动失败」 | 上游 harness 的插件问题，`.dsh/logs/startup-*.log` 里有具体插件名 |
| 想彻底清掉 Linux 那套 | 设置 → 应用 → DeepSeek桌宠 → 存储 → 清除数据 |

## 校验

```powershell
Get-FileHash .\DeepSeekPet_v8.0_dev.zip -Algorithm MD5
```

## 许可

代码 MIT；素材与第三方组件（dsh-pet / ex-skill / sherpa-onnx / ONNX Runtime / SenseVoice /
Silero VAD / Kotlin stdlib / DeepSeek Harness / Node.js / proot / Ubuntu base）见
仓库里的 `docs/第三方许可声明.md`。签名密钥不随包分发。

## 文件名说明

Release 资产用 **ASCII 文件名**（中文名在下载 URL 里会二次转义，Node 上传时还会被吞掉）：

| Release 资产 | 对应原包 |
|---|---|
| `DeepSeekPet_v8.0_dev.zip` | 开发版 |
| `DeepSeekPet_v8.0_release.zip` | 正式版 |
| `DeepSeekPet_v8.0_project.zip` | 项目移植包（完整工程快照，905 MB） |

另外源码仓库里少 2 个中等体积文件（`deps/kotlin-stdlib-1.9.0.jar`、`assets/webp/待机呼吸休闲.webp`），
取法见仓库 `docs/两个未入库的大文件.md`。
