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
        report("设置已保存")
    }
    suspend fun execute(action: String): Boolean = operation {
        val config = configStore.load()
        config.validate()
        when (action) {
            "test" -> { root(config, "test_connection", true); report("SMB 目录读取成功") }
            "mount", "restore" -> {
                if (action == "restore" && !config.restoreAtBoot) return@operation
                lockConfig(true)
                root(config, "mount_gateway", true)
                updateDiagnostics(config)
                report("只读挂载已建立；请扫描并验证 Google Photos")
            }
            "unmount" -> {
                root(config, "unmount_gateway", false)
                lockConfig(false)
                mutable.value = mutable.value.copy(scan = "已卸载；历史 MediaStore 记录可能保留")
                updateDiagnostics(config)
                report("挂载已卸载")
            }
            "status" -> { updateDiagnostics(config); report("状态已刷新") }
            "scan" -> {
                val status = root(config, "status_gateway", false)
                check("READONLY ${config.mountDirectory}\n" in "$status\n") { "请先建立只读挂载" }
                mutable.value = mutable.value.copy(scan = "正在扫描")
                val scanner = GatewayScanner(context)
                val result = scanner.scan(config.mountDirectory) { progress ->
                    mutable.value = mutable.value.copy(scan = progress)
                }
                mutable.value = mutable.value.copy(scan = result)
                report("扫描已结束；云端备份结果请在 Google Photos 中确认")
            }
            else -> error("未知操作")
        }
    }
    private suspend fun root(config: GatewayConfig, function: String, credentials: Boolean): String {
        val body = context.assets.open("gateway.sh").bufferedReader().use { it.readText() }
        val result = shell.run(RootScripts.environment(config, credentials) + "\n" + body + "\n" + function)
        // Never return raw credentials, even if a downstream tool echoes an error.
        return result.copy(output = result.output.replace(config.password, "[已隐藏]")).checked()
    }
    private suspend fun updateDiagnostics(config: GatewayConfig) {
        val rootStatus = root(config, "status_gateway", false)
        val visible = GatewayScanner(context).visibility(config.mountDirectory)
        mutable.value = mutable.value.copy(diagnostics = "$rootStatus\n应用访问：$visible\nGoogle Photos 备份：待人工确认")
    }
    private suspend fun operation(block: suspend () -> Unit): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutable.value = mutable.value.copy(busy = true)
            try { block(); true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                // Shell output already redacted; crypto exceptions carry no plaintext.
                report(error.message?.take(1500) ?: "操作失败")
                if (mutable.value.scan == "正在扫描") mutable.value = mutable.value.copy(scan = "扫描失败")
                false
            } finally { mutable.value = mutable.value.copy(busy = false) }
        }
    }
}
