# NAS Photos Gateway

Pixel + Magisk Root + 群晖 SMB 照片网关，采用 Jetpack Compose、rclone/FUSE 和 Android MediaStore。Android 9+（API 28），面向侧载。

```text
群晖 SMB → rclone FUSE → /mnt/nas-photos-gateway
                      → /storage/emulated/0/DCIM/NAS
                      → MediaStore → Google Photos
```

## 0.1.3 功能

- 独立状态页、设置页；连接配置使用 Android Keystore AES-GCM 加密保存。
- 测试连接、挂载、卸载、扫描媒体、解锁后恢复挂载。
- 扫描不设媒体数量、遍历条目、目录层数、清单大小、清理数量或任务总时长上限。仍受设备资源、系统路径、单次网络与回调超时限制。
- 流式读取 NAS 清单；临时 SQLite 仅保存路径与状态，分批处理。单个文件或子目录读取失败后继续处理其他内容，结果标为部分完成。
- 清理缺失的本地媒体索引前，核对两次完整 NAS 清单、挂载身份、实际缺失和路径前缀。读取失败、不完整清单、取消或目录变化时跳过清理。
- 默认只读。设置中可开启“允许删除 NAS 文件”，供 Google 相册“释放空间”通过挂载删除 NAS 源文件。
- 两种模式均关闭 VFS 媒体磁盘缓存；临时索引、缩略图及 Google 相册自身仍会占用空间。
- 扫描由现有 ViewModel 协程运行，无后台服务、通知或断点续扫；系统终止应用会中断任务。

## 使用

1. Pixel 安装 Magisk 和支持 SMB/FUSE 的 rclone 模块；默认 rclone 路径为 `/vendor/bin/rclone`。
2. 设置 NAS 地址、共享目录、账号、密码和子目录。挂载目录须为 `/storage/emulated/0/DCIM/` 下的独立空目录。
3. 保存设置，授予 Root 和文件访问权限，点击“测试连接”“挂载”“扫描媒体”。
4. 在 Google 相册设备文件夹中开启相应目录备份，独立确认云端备份结果。索引成功不会被显示为备份成功。
5. 使用相册“释放空间”前，先卸载网关挂载，在设置开启“允许删除 NAS 文件”，保存并重新挂载。NAS 账号需要删除权限。

**开启后，相册释放空间会删除 NAS 源文件。** 它保留 Google 相册云端备份。普通删除与“释放空间”是不同操作；应用不查询云端备份状态。该开关通过可写挂载提供删除能力，不提供严格的仅删除权限隔离，也不自动配置 NAS 回收站。

挂载期间配置锁定，卸载后可修改。开机恢复遵循已保存的模式。模式不一致时需正常卸载后重新挂载。

## 构建与升级

使用 GitHub Actions → Android CI → Run workflow，选择开发分支。Actions 运行 Root 脚本测试、JVM/Robolectric 测试、Android Lint，构建 APK 并上传 APK、SHA-256、验证报告。

0.1.3 使用 `versionCode 4`，沿用 0.1.2 的固定调试签名。分发构建从 Secret `NAS_GATEWAY_DEBUG_KEYSTORE` 读取签名材料并验证证书；缺少 Secret 则失败。下载后可覆盖升级保留配置。PR 构建使用临时签名，不能作为覆盖升级包。

此版本在开发分支构建，未自动发布 Release。构建成功与 Pixel / Google 相册实机验收分别记录。

详见 [架构说明](docs/ARCHITECTURE.md)、[边界说明](docs/RISKS.md)、[实机验收](docs/DEVICE-TEST.md)。
