# 架构说明

## 组件

| 组件 | 职责 |
|---|---|
| MainActivity / Compose | 状态与设置两个页面，统一按钮组件 |
| GatewayViewModel | UI 操作、跨屏幕旋转生命周期 |
| ConfigStore | Keystore AES-GCM、AtomicFile、无明文配置落盘 |
| GatewayRepository | Mutex 串行化保存、挂载、卸载、扫描和恢复；状态流与错误反馈 |
| RootShell / RootScripts | 固定 su 命令，脚本通过 stdin 输入；引用与校验用户参数 |
| assets/gateway.sh | 连接测试、挂载归属检查、FUSE/bind、卸载与诊断 |
| GatewayScanner | 普通应用视角遍历、逐文件扫描、URI 读取验证 |
| BootReceiver / RestoreWorker | 解锁后系统广播入口、联网约束、最多三次退避恢复 |

## 挂载生命周期

保存配置后先测试 NAS 指定目录读取。启动挂载前同步保存 `mountPending`，在清理成功前禁止修改配置，以保留确定的卸载目标。`su -mm` 进入 Magisk 全局命名空间；rclone daemon 承担长期文件读取，APK 负责控制和诊断。

FUSE 源目录固定为 `/mnt/nas-photos-gateway`，设备名固定为 `nas-photos-gateway`。检查 `/proc/self/mountinfo` 的目标路径、FUSE 类型、设备名和只读标志。重复挂载沿用已有匹配挂载。bind 失败时保留源挂载，用户可点击卸载清理；不会吞掉错误或自动删目录。

Android sdcardfs 设备检测 `/mnt/runtime/default/emulated` 的文件系统类型，选择该共享父挂载内的目标目录。映射会传播至 default/read/write/full 和应用内的 storage 路径。已有旧版 storage 私有映射时，验证归属后迁移；迁移失败尝试恢复旧映射。其他设备保持直接 storage 映射，并保留实机验证要求。

卸载前验证源、目标及所有 runtime 对应路径均不存在外来或叠加挂载，然后先卸载共享 bind，清理残留映射，最后卸载源。常规卸载让 rclone daemon 正常结束。无强制卸载、`killall`、递归删除、`sync`、复制或 NAS 写入。

配置采用每应用 Keystore AES-GCM 密钥，IV 随机生成，应用数据备份关闭。rclone 密码经 stdin 交给 `obscure -`，仅在进程环境中保留可逆混淆形式。APK 不在磁盘上生成 rclone.conf，也不把账号密码加入进程命令参数。

## 扫描与兼容性

扫描使用本应用可见的 `/storage/emulated/0/DCIM/...`，随后交给 Android MediaScannerConnection。遍历不跟随符号链接，尊重 `.nomedia` 和隐藏目录；只处理常见媒体扩展名。索引后的每个 URI 会尝试读取一个字节。这只能证明当前应用的 URI 可读，不代表全部文件完整、Google Photos 可读或云端上传完成。

单次扫描最多 500 个媒体、10000 个条目、深度 32；4 分钟软时限在遍历与每文件处理之间检查，单次扫描回调等待 15 秒。SMB/FUSE 阻塞系统调用可能超过软时限。尚无全库增量扫描、分页游标、持续目录监听或自动清理历史 MediaStore 记录。

前台按钮操作在 ViewModel 的 IO 协程执行。开机恢复使用 WorkManager 持久任务，无开机直接启动前台服务。恢复只重新挂载，扫描需要手动触发。手动卸载取消待执行的开机恢复。应用被系统杀死后挂载可能仍然存在；重新启动应用后刷新/卸载。

## 构建验证

Actions 在 Ubuntu + JDK 17 上验证官方 Gradle Wrapper，安装 SDK 35，执行危险路径/归属/只读脚本测试、Kotlin 参数校验单元测试、Lint 和 APK 构建。Gradle distribution 带 SHA-256，wrapper JAR 来自 Gradle v8.11.1 官方仓库且已对照官方校验值。

CI 不包含 root Pixel、DSM、FUSE 真挂载或 Google Photos 测试。详见实机验收清单。

## 官方依据

- [Magisk su 的 mount-master](https://topjohnwu.github.io/Magisk/tools.html)
- [rclone mount 选项](https://rclone.org/commands/rclone_mount/)
- [rclone SMB 后端](https://rclone.org/smb/)
- [rclone obscure 与安全边界](https://rclone.org/commands/rclone_obscure/)
- [MediaScannerConnection](https://developer.android.com/reference/android/media/MediaScannerConnection)
- [所有文件访问权限](https://developer.android.com/training/data-storage/manage-all-files)
- [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)
- [AGP 8.9 兼容性](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
