# Pixel 实机验收

状态：已完成 Pixel / Android 10 / Magisk 30.7 的单张 PNG 验证。其余清单保留为后续测试计划，CI 无法替代设备验证。

## 2026-09-15 实测记录

- NAS 文件：`20260808-204520.png`，583013 字节。
- Root 路径完整读取 SHA-256：`63f651feb97d30ffb38e9b60bb0e288c1ebfa05d69ddb9ce65d1d3f83a5a8a58`。
- 原版只绑定 storage 时，MediaProvider 报 `NoSuchFileException`，媒体库无对应记录。
- 改为 runtime/default 共享映射后，Google Photos、MediaProvider 与网关进程均出现对应只读挂载。
- MediaStore 登记为 image/png，文件大小匹配；Android 10 的 ExifInterface 对 PNG 提示格式警告，宽高列为空，但 Google Photos 解码正常并显示 667 × 732。
- Google Photos 图片详情显示“已备份”“原始画质”“此内容不会占用您的账号存储空间”。这是该图片的界面状态，未单独审计上传流量或下载云端文件比对。
- 全程保留 SELinux Enforcing，源文件与挂载保持只读。

记录：Pixel 型号、Android build、Magisk 版本、rclone/FUSE 模块版本、Google Photos 版本、DSM 版本、网络环境。

| 步骤 | 预期 |
|---|---|
| 无 root / 拒绝 root | 明确错误，无崩溃、无虚假成功 |
| 错误 NAS 地址或密码 | 连接失败；诊断不显示密码 |
| 正确只读账号、小目录 | SMB 读取成功 |
| 目标非空、符号链接、外来挂载 | 挂载被拒绝，原文件保持 |
| 首次挂载与重复挂载 | 源、目标各一个匹配只读挂载，无叠加 |
| 授权文件访问后刷新 | 应用目录可见 |
| 10–20 张 JPEG/HEIC/视频扫描 | URI 非空、首字节可读；不支持格式明确计入失败 |
| `.nomedia` 和隐藏目录 | 跳过 |
| Google Photos 设备文件夹 | 能发现照片；人工打开照片与视频 |
| 开启文件夹备份 | 云端确认原图和完整视频，记录耗时与本机空间 |
| 扫描时断开 NAS | 失败可见，恢复后可重试；关注阻塞超时 |
| 部分 bind 失败 | 设置锁定，可卸载源后重新配置 |
| 正常卸载、重复卸载 | 无误删、无外来进程被终止 |
| 开关机恢复关闭 | 重启不主动挂载 |
| 开关机恢复开启 | 首次解锁且联网后恢复；NAS 不通最多三次尝试 |
| 系统杀死应用、旋转屏幕 | 状态可重新获取，配置可解密 |

若 URI 能读取而 Google Photos 看不到，优先记录命名空间与 MediaProvider 可见性，不将问题直接归因于 NAS。MVP 不自动注入其他应用的 mount namespace。

## 2026-09-18 v0.1.3 验证记录

- 分支：`feature/scan-delete-v0.1.3`，最终提交：`2bc6822`。
- GitHub Actions：25 个 JVM/Robolectric 测试，失败 0，忽略 0；Root 脚本测试、Lint、APK 构建和固定签名校验通过。
- APK：`versionName 0.1.3`、`versionCode 4`，SHA-256：`AB39FE7D3D30872B4680F64A0A44616519FD205DE0477C4375B7A6FFA200F3F1`。
- Pixel `FA69K0304783` 已覆盖安装，显示 `0.1.3 / versionCode 4`；0.1.2 配置仍保留。
- 设置页已显示“允许删除 NAS 文件”，默认关闭；状态页显示媒体缓存不使用磁盘缓存。
- 在 `M:\Photo2\Gateway_Test_20260918_v6` 创建 501 张隔离测试图片并生成 SHA-256 清单：文件数 501，总大小约 1.47 MB。
- 实机已验证重新打开 App、卸载、修改子目录和挂载目录、保存、重新挂载、重新扫描流程。
- 当前 Pixel 的 rclone/FUSE 挂载对该 SMB 目录返回目录为空和文件读取 `I/O error`，因此本轮未将 501 张扫描、NAS 删除和 Google Photos“释放空间”标记为通过；NAS 文件及测试目录保留，未执行批量删除。
- Google Photos 云端备份与“释放空间”需在 FUSE 读取恢复后单独验证；普通删除不能替代该项验收。
