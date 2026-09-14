# NAS Photos Gateway

Pixel 上的只读 NAS 照片网关，采用 Jetpack Compose + Magisk Root + rclone/FUSE。Android 9+（API 28），面向侧载与实机实验。

```text
群晖 SMB → rclone FUSE → /mnt/nas-photos-gateway
                       → bind mount → /storage/emulated/0/DCIM/NAS
                       → MediaScannerConnection → MediaStore → Google Photos
```

**MVP 能编译不代表 Google Photos 兼容已经验证。** 全局挂载可见性、SELinux、MediaProvider 和 Google Photos 的访问策略都需要在目标 Pixel 上验证；界面不会将挂载或索引成功显示为云端备份成功。

## 功能

- 独立状态页和设置页；保存 NAS 地址、共享目录、用户名、密码、子目录、挂载目录和 rclone 路径。
- 全部连接配置使用 Android Keystore AES-GCM 加密保存，禁用系统备份和屏幕截图。
- 通过 rclone SMB 实际读取指定目录测试连接，默认 SMB 端口 445、域 WORKGROUP。
- 使用 `su -mm` 在 Magisk 全局挂载命名空间建立 FUSE 和 bind mount。
- 固定只读，关闭 rclone VFS 磁盘缓存；每个打开文件仍有 4 MiB rclone 内存缓冲。
- 检查挂载归属和只读标志，拒绝非空目标、路径穿越、符号链接和其他程序的挂载。
- 扫描媒体并统计提交数、非空 MediaStore URI 数、URI 首字节可读数及失败数。
- 开机首次解锁、网络可用后按设置恢复挂载，最多尝试三次。
- GitHub Actions 执行单元测试、Root 脚本测试、Android Lint，生成 debug APK 和 SHA-256。

## 获取 APK

进入仓库 **Actions → Android CI → 最新成功运行 → Artifacts → NAS-Photos-Gateway-debug**。
解压后安装 `app-debug.apk`。Artifact 保留 30 天；可通过 Run workflow 再次构建。

每台 Actions runner 默认生成自己的 debug 签名。后续构建可能需要卸载旧版本再安装，配置会丢失；卸载应用前务必先在应用内卸载挂载。长期分发需要配置固定签名，MVP 未包含生产签名密钥。

## 首次运行

1. Pixel 安装并启用 Magisk；先安装带 SMB 后端和 FUSE 支持的 [rclone-fuse3-magisk 模块](https://github.com/NewFuture/rclone-fuse3-magisk)，重启。APK 不内置 rclone 或 FUSE。
2. 群晖启用 SMB2/SMB3。为手机建立专用只读账号，仅授权待测试照片目录。先准备 10–20 张照片。
3. 打开应用“设置”，填写 NAS IP、共享目录（如 `photo`）、账号、密码及 NAS 子目录（如 `TestPhotos`）。子目录使用相对路径，留空代表共享根目录。
4. 挂载目录默认 `/storage/emulated/0/DCIM/NAS`。必须为空，并位于主用户 DCIM 的独立子目录；MVP 不支持工作资料或其他 Android 用户。
5. rclone 默认 `/system/bin/rclone`，可修改为模块内的实际 rclone 路径。先保存，再到状态页“测试连接”，允许 Magisk Root 请求。
6. 在设置页点击“文件访问权限”：Android 11+ 开启“所有文件访问”；Android 9/10 允许存储访问。
7. 点击“挂载”“刷新状态”，查看诊断。源目录与目标目录均应出现 `READONLY`。
8. 点击“扫描媒体”。检查“已索引”和“可读取”是否一致；每次最多 500 个媒体 / 10000 个遍历条目，约 4 分钟软时限，深度上限 32。超限请缩小 NAS 子目录；重复扫描从头开始。
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
- 重启后没有恢复：先解锁，等待网络和系统任务调度，检查已保存开机恢复开关和 Magisk 常驻授权。系统强制停止应用后需手动打开。
- 本项目不控制 Google Photos 备份队列，不读取 Google 账号，也不实现 Google Photos 上传 API。

详见 [架构说明](docs/ARCHITECTURE.md)、[风险与边界](docs/RISKS.md)、[实机验收](docs/DEVICE-TEST.md)。
