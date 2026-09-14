package io.github.forensax.nasphotosgateway

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF176C63), background = Color(0xFFF8FAF9))) {
                GatewayScreen()
            }
        }
    }
}

@Composable
private fun GatewayScreen(model: GatewayViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 20.dp)) {
            Text("NAS Photos Gateway", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text("Pixel · 只读照片网关", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("状态", "设置").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tab == 0) StatusPage(state, model) else SettingsPage(state, model)
            }
        }
    }
}

@Composable
private fun StatusPage(state: GatewayState, model: GatewayViewModel) {
    val context = LocalContext.current
    var openError by remember { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    Text(state.message, style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider()
    StatusLine("SMB", state.connectionStatus)
    StatusLine("挂载", state.mountStatus)
    StatusLine("挂载保护", "只读 · 零磁盘 VFS 缓存")
    StatusLine("配置", if (state.configLocked) "挂载期间锁定" else "可编辑")
    StatusLine("Google Photos", "待实机验证")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("测试连接", !state.busy, Modifier.weight(1f)) { model.execute("test") }
        ActionButton("刷新状态", !state.busy, Modifier.weight(1f)) { model.execute("status") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("挂载", !state.busy, Modifier.weight(1f)) { model.execute("mount") }
        ActionButton("卸载", !state.busy, Modifier.weight(1f)) { model.execute("unmount") }
    }
    HorizontalDivider()
    Text("媒体扫描", fontWeight = FontWeight.SemiBold)
    Text(state.scan, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("扫描媒体", !state.busy, Modifier.weight(1f)) { model.execute("scan") }
        ActionButton("打开相册", true, Modifier.weight(1f)) {
            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
            if (intent == null) openError = "未安装 Google Photos"
            else runCatching { context.startActivity(intent) }.onFailure { openError = "无法打开 Google Photos" }
        }
    }
    if (openError.isNotEmpty()) Text(openError, color = MaterialTheme.colorScheme.error)
    HorizontalDivider()
    ActionButton(if (details) "收起诊断" else "查看诊断", true, Modifier.fillMaxWidth()) { details = !details }
    if (details) Text(state.diagnostics, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SettingsPage(state: GatewayState, model: GatewayViewModel) {
    val context = LocalContext.current
    var error by remember { mutableStateOf("") }
    // Secrets are deliberately excluded from rememberSaveable / Bundle state.
    var config by remember { mutableStateOf(runCatching { model.loadConfig() }.getOrElse { error = "无法解密旧配置，请重新填写"; GatewayConfig() }) }
    val editable = !state.busy && !state.configLocked
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    Text("群晖 SMB", fontWeight = FontWeight.SemiBold)
    ConfigField("NAS IP / 主机名", config.host, editable) { config = config.copy(host = it.trim()) }
    ConfigField("共享目录", config.share, editable) { config = config.copy(share = it) }
    ConfigField("用户名", config.username, editable) { config = config.copy(username = it) }
    OutlinedTextField(value = config.password, onValueChange = { config = config.copy(password = it) }, label = { Text("密码") },
        modifier = Modifier.fillMaxWidth(), enabled = editable, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    ConfigField("NAS 子目录（可留空）", config.subdirectory, editable) { config = config.copy(subdirectory = it) }
    HorizontalDivider()
    Text("本机挂载", fontWeight = FontWeight.SemiBold)
    ConfigField("挂载目录", config.mountDirectory, editable) { config = config.copy(mountDirectory = it.trim()) }
    ConfigField("rclone 路径", config.rclonePath, editable) { config = config.copy(rclonePath = it.trim()) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("开机解锁后恢复")
        Switch(checked = config.restoreAtBoot, enabled = editable, onCheckedChange = { config = config.copy(restoreAtBoot = it) })
    }
    Text("只读模式固定开启", style = MaterialTheme.typography.bodySmall)
    if (state.configLocked) Text("卸载后可修改设置", color = MaterialTheme.colorScheme.primary)
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
    Text(state.message, style = MaterialTheme.typography.bodySmall)
    ActionButton("保存设置", editable, Modifier.fillMaxWidth()) {
        runCatching { config.validate() }.onSuccess { error = ""; model.save(config) }.onFailure { error = it.message.orEmpty() }
    }
    ActionButton("文件访问权限", !state.busy, Modifier.fillMaxWidth()) {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }
                .onFailure { error = "请在系统设置中为本应用开启所有文件访问权限" }
        } else permissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
}

@Composable
private fun ConfigField(label: String, value: String, enabled: Boolean, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
