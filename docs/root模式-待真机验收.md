# 工作模式 root：修复与待真机验收

## 本轮修复（尚未宣称真机通过）

- `su -c id` 最多等待 12 秒，要求退出码为 0 且输出含 `uid=0`；失败不永久缓存。
- 每次启动只尝试一次 root；失败后重新构造普通模式参数，不带 `/data`、`/system` 等特权 bind，再以普通权限重试。
- root 开关改变会重启宿主，不再仅修改设置而沿用旧身份。
- 停止/重启时按本次启动身份回收 proot/node；root 子进程通过 `su` 回收。
- 普通模式不会仅因清理残留进程而申请 root。

## 已完成的非设备验证

- `verify_harness_client.py`：43 项通过。
- `reverse_check_harness_client.py`：11/11 项破坏被抓到，含无限等待、重复 root、特权 bind 泄漏、开关不重启。
- `build/run_tests_safe.py lab_all_tests_round144.log`：82/82 套通过，退出码 0，源码与快照一致。

## 连接 root 设备后必须验证

1. 正常 root：设置 → 陪伴 → 工作模式 → 打开「以 root 运行宿主」；允许授权；宿主重启后能打开网页。
2. 真实身份与挂载：访客内执行 `id`，应为 `uid=0`；`/mnt/data`、`/mnt/system`、`/mnt/storagevol` 能按权限访问。不要把 proot 的 fake-root `-0` 当成真实 root 的证据。
3. 切回普通模式：关闭开关；宿主重启后 `/mnt/data` 不再以 root 权限开放；网页仍可用。
4. 拒绝 root：打开开关但拒绝授权；应使用普通权限启动，不能永久卡在等待授权。
5. root 启动失败：制造一次失败（例如临时撤权）；日志应出现「root 宿主启动失败，退回普通权限重试」，随后网页可用。
6. 进程回收：root → 普通 → root 连续切换，检查无同 rootfs 的孤儿 proot/node，无多余后台任务，端口无异常占用。
7. 会话回归：root 启动后发一条消息，再切普通并重新进入；会话和配置仍能保存、读取。

## 取证

```sh
adb logcat -d -s DSPEngine
```

需要同时保留：开关操作顺序、root 管理器授权结果、宿主 URL 是否出现、访客 `id` 输出、`/mnt/*` 读结果、最后 80 行 `DSPEngine` 日志。

**当前边界：开发机 `adb devices` 为空；root 的真实身份、挂载和失败回退都尚未在设备上验证。**
