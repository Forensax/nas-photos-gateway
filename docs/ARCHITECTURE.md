# 架构说明

## 组件与数据流

- Compose 保留状态、设置两个页面和统一按钮。
- GatewayRepository 通过 Mutex 串行化配置、挂载、卸载、扫描和开机恢复。
- ConfigStore 保留 Keystore AES-GCM 与 AtomicFile；旧配置缺少 allowDelete 时取 false。
- gateway.sh 验证挂载归属、源与目标设备、所有 runtime 映射及实际 ro/rw 模式；卸载不依赖保存模式，允许清理模式不一致的挂载。
- RootShell.stream 使用 stdin 传入脚本与凭据，独立读取 stdout 和 stderr。stdout 无总大小、总时长上限；错误输出有界并脱敏。
- 每个清单 shell 使用随机非敏感 argv 标记，取消前校验标记，再终止该 shell 及其后代；不会使用 killall 或终止 rclone mount/Web UI。解析线程退出后才关闭数据库。
- RemoteSnapshot 流式读取带 identity、entries、end 的 JSON。只有身份匹配、JSON 完整、进程成功退出才是完整清单。
- ScanIndex 使用任务专属临时 SQLite 存放两份清单、遍历队列、媒体记录与清理候选。256 条写事务、128 条候选分页只是批次大小，均不限制总量。退出关闭数据库；下一任务清理进程中断遗留文件。
- ScanEngine 迭代取出路径并处理，逐目录关闭目录流，不使用有深度上限的递归遍历。GatewayScanner 对接 Android MediaScannerConnection、MediaStore 和 URI 读取。

## 完整性与清理

先刷新精确匹配的 rclone mount 缓存，再独立读取 SMB 清单。清单不完整时，挂载身份正常且文件可读仍可扫描；结果标为部分完成并禁止索引清理。

正常扫描时，在目录边界和文件批次核验挂载。文件首字节可读后才提交给 Android；统计区分遍历、提交、索引、URI 可读、失败、跳过、清理、保留，全部使用 Long。

清理仅处理当前挂载目录内图片和视频的 MediaStore 记录。两份完整清单及挂载身份必须一致；先检查全部候选的真实缺失状态和同名前缀，再逐条复核挂载与路径并提交系统扫描，最后查询实际记录是否消失。非媒体记录也参与前缀保护。SQLite 使用归一化路径键与索引查询，无完整路径集合驻留内存。

任一读取错误、取消、目录变化或清单不完整均阻止后续清理。异常时界面保留已处理统计。系统已经收到的异步媒体扫描可能继续完成，远程修改与系统扫描之间仍存在竞态窗口。

## 删除模式

默认使用只读 rclone 与 bind。allowDelete 开启后，rclone 可写、目录具有删除所需权限，既有媒体文件保持只读权限；底层不提供严格的仅删除隔离。映射仍需通过归属、设备号与模式检查。

应用扫描流程自身不调用文件删除接口。Google 相册或其他获得文件访问权限的应用发起删除时，由 FUSE/rclone/SMB 传至 NAS。两种模式都使用 vfs-cache-mode off。

## 生命周期与构建

保留 ViewModel IO 协程和开机 WorkManager 恢复，无新增前台服务或断点续扫。进程被杀后挂载可能仍存在，需要重新打开应用检查。

Actions 使用 JDK 17、SDK 35、固定 Gradle/AGP/Kotlin 版本，完成脚本测试、单元测试、Lint、APK 和签名校验。Root Pixel 与 Google 相册验收独立记录。
