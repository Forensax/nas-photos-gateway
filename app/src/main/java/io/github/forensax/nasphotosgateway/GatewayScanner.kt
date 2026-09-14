package io.github.forensax.nasphotosgateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class GatewayScanner(private val context: Context) {
    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun visibility(path: String): String = when {
        !hasPermission() -> "未授予文件访问权限"
        !File(path).isDirectory || !File(path).canRead() -> "目录不可读；检查挂载命名空间和 SELinux"
        else -> "目录可访问，文件读取须扫描验证"
    }

    suspend fun scan(path: String, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(hasPermission()) { "请先在设置页授予文件访问权限" }
        check(GatewayConfig.validMountPath(path)) { "扫描目录无效" }
        val root = File(path).toPath()
        check(Files.isDirectory(root) && Files.isReadable(root)) { "应用无法读取挂载目录；检查命名空间与 SELinux" }
        val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "dng", "mp4", "mov", "m4v", "3gp", "mkv")
        var submitted = 0
        var indexed = 0
        var readable = 0
        var failed = 0
        var visited = 0
        var capped = false
        var timedOut = false
        val deadline = android.os.SystemClock.elapsedRealtime() + 240_000
        // Bounded MVP scan: lazy traversal, no symlink following, no recursive root shell listing.
        Files.walk(root, 32).use { stream ->
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                coroutineContext.ensureActive()
                if (android.os.SystemClock.elapsedRealtime() >= deadline) { timedOut = true; break }
                if (submitted >= 500 || visited >= 10000) { capped = true; break }
                val next = iterator.next()
                visited++
                if (!Files.isRegularFile(next, LinkOption.NOFOLLOW_LINKS)) continue
                if (next.toFile().extension.lowercase() !in extensions) continue
                // Respect Android hidden-media conventions.
                var parent = next.parent
                var excluded = false
                while (parent != null && parent.startsWith(root)) {
                    if (Files.exists(parent.resolve(".nomedia")) || parent.fileName.toString().startsWith('.')) { excluded = true; break }
                    parent = parent.parent
                }
                if (excluded || next.fileName.toString().startsWith('.')) continue
                submitted++
                val uri = withTimeoutOrNull(15_000) {
                    suspendCancellableCoroutine { continuation ->
                        MediaScannerConnection.scanFile(context, arrayOf(next.toString()), null) { _, scanned ->
                            if (continuation.isActive) continuation.resume(scanned)
                        }
                    }
                }
                if (uri != null) {
                    indexed++
                    val canRead = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
                    }.getOrDefault(false)
                    if (canRead) readable++ else failed++
                } else failed++
                progress("已提交 $submitted · 已索引 $indexed · 可读取 $readable · 失败 $failed")
            }
        }
        "已提交 $submitted · 已索引 $indexed · 可读取 $readable · 失败 $failed" +
            when { capped -> "\n达到 MVP 上限（500 个媒体 / 10000 个条目）；请缩小 NAS 子目录。重复扫描从头开始。"
                timedOut -> "\n达到 4 分钟时限；请缩小 NAS 子目录。"
                submitted == 0 -> "\n未发现媒体；检查空目录、.nomedia 或应用挂载可见性。"
                else -> "" }
    }
}
