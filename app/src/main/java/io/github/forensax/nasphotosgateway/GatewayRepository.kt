package io.github.forensax.nasphotosgateway

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class GatewayState(
    val busy: Boolean = false,
    val message: String = "请先保存连接设置，再测试连接",
    val diagnostics: String = "尚未检查",
    val scan: String = "尚未扫描",
    val configLocked: Boolean = false,
    val mountStatus: String = "尚未检查",
    val connectionStatus: String = "尚未测试",
)

class GatewayRepository(private val context: Context) {
    private val configStore = ConfigStore(context)
    private val preferences = context.getSharedPreferences("gateway-state", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val shell = RootShell()
    private val mutable = MutableStateFlow(GatewayState(
        message = preferences.getString("lastMessage", null) ?: "请先保存连接设置，再测试连接",
        configLocked = preferences.getBoolean("mountPending", false),
    ))
    val state = mutable.asStateFlow()
    fun loadConfig() = configStore.load()
    private fun report(message: String) {
        mutable.value = mutable.value.copy(message = message)
        preferences.edit().putString("lastMessage", message).apply()
    }
    private fun lockConfig(locked: Boolean) {
        // Commit before root mutation: process death must not lose the cleanup target.
        check(preferences.edit().putBoolean("mountPending", locked).commit()) { "无法保存挂载状态" }
        mutable.value = mutable.value.copy(configLocked = locked)
    }
    suspend fun save(config: GatewayConfig) = operation {
        check(!preferences.getBoolean("mountPending", false)) { "请先卸载，再修改连接设置" }
        configStore.save(config)
        mutable.value = mutable.value.copy(connectionStatus = "尚未测试", mountStatus = "尚未检查", diagnostics = "配置已更新，请刷新状态")
        report("设置已保存")
    }
    suspend fun execute(action: String): Boolean = operation {
        val config = configStore.load()
        config.validate()
        when (action) {
            "test" -> {
                mutable.value = mutable.value.copy(connectionStatus = "正在测试")
                root(config, "test_connection", true)
                mutable.value = mutable.value.copy(connectionStatus = "目录读取成功")
                report("SMB 目录读取成功")
            }
            "mount", "restore" -> {
                if (action == "restore" && !config.restoreAtBoot) return@operation
                mutable.value = mutable.value.copy(mountStatus = "正在挂载")
                lockConfig(true)
                root(config, "mount_gateway", true)
                updateDiagnostics(config)
                report("只读挂载已建立；请扫描并验证 Google Photos")
            }
            "unmount" -> {
                mutable.value = mutable.value.copy(mountStatus = "正在卸载")
                root(config, "unmount_gateway", false)
                lockConfig(false)
                mutable.value = mutable.value.copy(scan = "已卸载；历史 MediaStore 记录可能保留")
                updateDiagnostics(config)
                report("挂载已卸载")
            }
            "status" -> { updateDiagnostics(config); report("状态已刷新") }
            "scan" -> {
                val scanner = GatewayScanner(context)
                check(scanner.hasPermission()) { "请先在设置页授予文件访问权限" }
                mutable.value = mutable.value.copy(scan = "正在核对 NAS 实时清单")
                val result = try {
                    val initial = RemoteSnapshot.parse(root(config, "prepare_scan", true, RemoteSnapshot.MAX_OUTPUT))
                    mutable.value = mutable.value.copy(connectionStatus = "目录读取成功", scan = "正在扫描")
                    scanner.scan(
                        config.mountDirectory,
                        initial,
                        confirmSnapshot = { RemoteSnapshot.parse(root(config, "snapshot_gateway", true, RemoteSnapshot.MAX_OUTPUT)) },
                        verifyMount = { expected ->
                            check(root(config, "scan_identity", false).trim() == expected) { "挂载发生变化，已停止清理" }
                        },
                        progress = { progress -> mutable.value = mutable.value.copy(scan = progress) },
                    )
                } catch (error: Exception) {
                    mutable.value = mutable.value.copy(scan = "扫描中断\n${mutable.value.scan}")
                    throw error
                }
                mutable.value = mutable.value.copy(scan = result)
                report("媒体索引核对已结束；云端备份状态请在 Google Photos 中确认")
            }
            else -> error("未知操作")
        }
    }
    private suspend fun root(config: GatewayConfig, function: String, credentials: Boolean, outputLimit: Int = 16384): String {
        val body = context.assets.open("gateway.sh").bufferedReader().use { it.readText() }
        val result = shell.run(RootScripts.environment(config, credentials) + "\n" + body + "\n" + function, outputLimit = outputLimit)
        // Parse machine markers before redaction: a password may itself be "READONLY".
        if (result.code != 0) result.copy(output = result.output.replace(config.password, "[已隐藏]")).checked()
        return result.output
    }
    private suspend fun updateDiagnostics(config: GatewayConfig) {
        val rootStatus = root(config, "status_gateway", false)
        val visible = GatewayScanner(context).visibility(config.mountDirectory)
        val lines = rootStatus.lines()
        val mount = when {
            "READONLY ${config.mountDirectory}" in lines && "READONLY ${RootScripts.SOURCE}" in lines -> "FUSE + bind 只读"
            lines.any { it.startsWith("FOREIGN_MOUNT") } -> "目录存在其他挂载"
            lines.any { it.startsWith("NOT_READONLY") } -> "只读检查失败"
            "UNMOUNTED ${config.mountDirectory}" in lines && "UNMOUNTED ${RootScripts.SOURCE}" in lines -> "未挂载"
            else -> "部分挂载，请检查"
        }
        val display = rootStatus.replace(config.password, "[已隐藏]")
        mutable.value = mutable.value.copy(mountStatus = mount, diagnostics = "$display\n应用访问：$visible\nGoogle Photos 备份：待人工确认")
    }
    private suspend fun operation(block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block(); true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Shell output already redacted; crypto exceptions carry no plaintext.
                report(error.message?.take(1500) ?: "操作失败")
                if (mutable.value.connectionStatus == "正在测试") mutable.value = mutable.value.copy(connectionStatus = "连接失败")
                if (mutable.value.mountStatus.startsWith("正在")) mutable.value = mutable.value.copy(mountStatus = "操作失败，请刷新状态")
                if (mutable.value.scan == "正在扫描") mutable.value = mutable.value.copy(scan = "扫描失败")
                false
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
