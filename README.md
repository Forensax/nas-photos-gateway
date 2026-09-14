# NAS Photos Gateway

Pixel 上的只读 NAS 照片网关，采用 Jetpack Compose + Magisk Root + rclone/FUSE。Android 9+（API 28），面向侧载与实机实验。

```text
群晖 SMB → rclone FUSE → /mnt/nas-photos-gateway
                       → bind mount → /storage/emulated/0/DCIM/NAS
                       → MediaScannerConnection → MediaStore → Google Photos
```

**0.1.1 已在 Pixel / Android 10 / Magisk 30.7 上验证一张 NAS PNG。** Google Photos 能打开图片，详情显示已备份、原始画质；更多图片、视频、Android 版本及重启恢复仍需继续验证。界面不会将挂载或索引成功显示为云端备份成功。

Android 9/10 的 sdcardfs 设备从 `/mnt/runtime/default/emulated/0/DCIM/NAS` 建立共享映射，再传播到各应用的 `/storage/emulated/0/DCIM/NAS`。直接只绑定 `/storage` 会出现 root 看得到、媒体服务看不到的问题。0.1.1 已修正该路径，并同步处理卸载与旧映射迁移。

## 功能

- 独立状态页和设置页；保存 NAS 地址、共享目录、用户名、密码、子目录、挂载目录和 rclone 路径。
- 全部连接配置使用 Android Keystore AES-GCM 加密保存，禁用系统备份和屏幕截图。
- 通过 rclone SMB 实际读取指定目录测试连接，默认 SMB 端口 445、域 WORKGROUP。
- 使用 `su -mm` 在 Magisk 全局挂载命名空间建立 FUSE 和 bind mount。
- 固定只读，关闭 rclone VFS 磁盘缓存；每个打开文件仍有 4 MiB rclone 内存缓冲。
- 检查挂载归属和只读标志，拒绝非空目标、路径穿越、符号链接和其他程序的挂载。
- 扫描媒体并统计提交数、非空 MediaStore URI 数、URI 首字节可读数及失败数。
- 扫描前刷新当前挂载的目录缓存；通过两次完整的 SMB 实时清单核对已删除文件，重新扫描失效路径并统计实际清理的本地索引。
- 断网、清单不完整、扫描失败或超限、挂载变化、目录内容变化时停止清理；保留仍可见的文件和其他目录的索引。
- 开机首次解锁、网络可用后按设置恢复挂载，最多尝试三次。
- GitHub Actions 执行单元测试、Root 脚本测试、Android Lint，生成 debug APK 和 SHA-256。

## 获取 APK

进入仓库 **Actions → Android CI → 最新成功运行 → Artifacts → NAS-Photos-Gateway-debug**。
解压后安装 `app-debug.apk`。Artifact 保留 30 天；可通过 Run workflow 再次构建。

从 0.1.2 起，主分支和手动构建使用仓库 Secret `NAS_GATEWAY_DEBUG_KEYSTORE` 中的固定调试签名，后续同签名版本可覆盖升级。密钥只在构建期间还原，不进入源码或 Artifact；缺少 Secret 时分发构建失败。PR 验证构建使用临时调试签名。

0.1.0 / 0.1.1 使用旧 runner 的临时签名，首次升级到 0.1.2 仍需先卸载挂载、卸载旧 APK，再安装和填写配置。长期公开分发仍建议切换专用发行签名。

## 首次运行

1. Pixel 安装并启用 Magisk；先安装带 SMB 后端和 FUSE 支持的 [rclone-fuse3-magisk 模块](https://github.com/NewFuture/rclone-fuse3-magisk)，重启。APK 不内置 rclone 或 FUSE。
2. 群晖启用 SMB2/SMB3。为手机建立专用只读账号，仅授权待测试照片目录。先准备 10–20 张照片。
3. 打开应用“设置”，填写 NAS IP、共享目录（如 `photo`）、账号、密码及 NAS 子目录（如 `TestPhotos`）。子目录使用相对路径，留空代表共享根目录。
4. 挂载目录默认 `/storage/emulated/0/DCIM/NAS`。必须为空，并位于主用户 DCIM 的独立子目录；MVP 不支持工作资料或其他 Android 用户。
5. rclone 默认 `/vendor/bin/rclone`（所引用模块的实际安装布局），可修改为模块内的实际 rclone 路径。先保存，再到状态页“测试连接”，允许 Magisk Root 请求。
6. 在设置页点击“文件访问权限”：Android 11+ 开启“所有文件访问”；Android 9/10 允许存储访问。
7. 点击“挂载”“刷新状态”，查看诊断。源目录与目标目录均应出现 `READONLY`。
8. 点击“扫描媒体”。检查“已索引”“可读取”“已清理”；每次最多 500 个媒体 / 10000 个遍历条目，约 4 分钟软时限，深度上限 32。完整的 NAS 清单最多 10000 条、4 Mi 字符，单次清理最多 500 条。超限保留旧索引，请缩小 NAS 子目录；重复扫描从头开始。
9. 点击“打开相册”，在 Google Photos 设备文件夹中检查 NAS，手动启用该文件夹备份。确认云端原图和视频可打开，记录本机存储变化。

不要让 Magisk 模块自己的自动挂载与本应用控制同一个目录。应用通过环境变量提供独立 `nas` remote，使用 `/dev/null` 配置文件，不修改模块原有配置。

## 本地构建

安装 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0，设置 `ANDROID_HOME` 或本地 `local.properties`。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 使用 `gradlew.bat`。APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
Gradle 8.11.1、AGP 8.9.2、Kotlin/Compose 插件 2.1.20、Compose BOM 2025.04.01 固定版本。

## 限制和排查

- `su` 不存在 / 未授权：检查 Magisk；普通 root 实现可能不支持 `-mm`。
- rclone 不存在 / FUSE 失败：核对模块安装、二进制路径、设备 ABI 和模块所需动态库。
- 挂载后目录不可见：检查 Magisk 命名空间隔离；完全关闭并重新打开本应用与 Google Photos。全局命名空间不能保证已运行应用自动看到挂载。
- 索引为 0：检查 `.nomedia`、文件格式、文件权限、MediaProvider 可见性与 SELinux 拒绝。不会自动关闭 SELinux 或注入策略。
- 卸载提示占用：暂停读取后重试。应用使用普通 `umount`，不会强制杀死其他程序。
- 挂载失败后设置仍锁定：点击“卸载”核对并清理部分挂载，再修改配置。
- NAS 删除后 Photos 仍显示“在此设备上”：保持挂载和 NAS 网络可用，点击“扫描媒体”，核对“已清理”。系统扫描回调完成后会再次查询 MediaStore 确认记录已消失；Google Photos 的界面缓存可能需要重新打开应用。
- 清理只提交确认缺失的本地路径给 Android 媒体扫描器；应用不调用文件删除接口，也不访问 Google Photos 的云端删除接口。卸载挂载和断网均不触发清理。
- 重启后没有恢复：先解锁，等待网络和系统任务调度，检查已保存开机恢复开关和 Magisk 常驻授权。系统强制停止应用后需手动打开。
- 本项目不控制 Google Photos 备份队列，不读取 Google 账号，也不实现 Google Photos 上传 API。

详见 [架构说明](docs/ARCHITECTURE.md)、[风险与边界](docs/RISKS.md)、[实机验收](docs/DEVICE-TEST.md)。
