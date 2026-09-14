package io.github.forensax.nasphotosgateway

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
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

    suspend fun scan(
        path: String,
        initial: RemoteSnapshot,
        confirmSnapshot: suspend () -> RemoteSnapshot,
        verifyMount: suspend (String) -> Unit,
        progress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
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
                val attrs = Files.readAttributes(next, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isDirectory && next != root && root.relativize(next).nameCount >= 32) { capped = true; break }
                if (!attrs.isRegularFile) continue
                if (next.toFile().extension.lowercase() !in extensions) continue
                // Cached names absent from the live listing are handled only by
                // the separately guarded reconciliation phase below.
                val relative = root.relativize(next).toString().replace(File.separatorChar, '/')
                if (MediaIndexPolicy.key(relative) !in initial.paths) continue
                // Respect Android hidden-media conventions.
                var parent = next.parent
                var excluded = false
                while (parent != null && parent.startsWith(root)) {
                    if (Files.exists(parent.resolve(".nomedia")) || parent.fileName.toString().startsWith('.')) { excluded = true; break }
                    parent = parent.parent
                }
                if (excluded || next.fileName.toString().startsWith('.')) continue
                submitted++
                // A failed NAS read must not be sent to the scanner as a missing
                // file: scanning a missing path can remove an existing record.
                val fileReadable = runCatching { Files.newInputStream(next).use { it.read() >= 0 } }.getOrDefault(false)
                if (!fileReadable) {
                    failed++
                    progress("已提交 $submitted · 已索引 $indexed · 可读取 $readable · 失败 $failed")
                    continue
                }
                val uri = scanPath(next.toString()).uri
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
        var cleaned = 0
        var retained = 0
        var cleanupNote = ""
        if (capped || timedOut || failed > 0) {
            cleanupNote = "\n本次扫描不完整，已跳过失效索引清理。"
        } else {
            val entries = indexedEntries(path)
            if (MediaIndexPolicy.missing(path, entries, initial).isNotEmpty()) {
                progress("正在再次核对 NAS，确认失效索引")
                val confirmed = confirmSnapshot()
                val candidates = MediaIndexPolicy.confirmedMissing(path, entries, initial, confirmed)
                // Validate every candidate before submitting the first missing
                // path, so a permission/I/O error aborts the whole cleanup pass.
                val missing = candidates.filter {
                    !MediaIndexPolicy.hasScanPrefixCollision(path, it, entries, confirmed) && definitelyMissing(root, it.path)
                }
                retained = candidates.size - missing.size
                verifyMount(confirmed.mountIdentity)
                for (entry in missing) {
                    coroutineContext.ensureActive()
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        cleanupNote = "\n清理达到时限，剩余索引保留；请重新扫描。"
                        break
                    }
                    verifyMount(confirmed.mountIdentity)
                    if (!definitelyMissing(root, entry.path)) { retained++; continue }
                    val scanned = scanPath(entry.path)
                    if (!scanned.completed) {
                        cleanupNote = "\n系统扫描回调超时，已停止后续清理；请重新扫描。"
                        break
                    }
                    if (indexExists(entry.id)) retained++ else cleaned++
                    progress("已索引 $indexed · 可读取 $readable · 已清理 $cleaned · 保留 $retained")
                }
                if (retained > 0) cleanupNote += "\n仍有 $retained 条记录或路径存在，已保留。"
            }
        }
        "已提交 $submitted · 已索引 $indexed · 可读取 $readable · 失败 $failed · 已清理 $cleaned" +
            when { capped -> "\n达到 MVP 上限（500 个媒体 / 10000 个条目）；请缩小 NAS 子目录。重复扫描从头开始。"
                timedOut -> "\n达到 4 分钟时限；请缩小 NAS 子目录。"
                submitted == 0 && initial.paths.isEmpty() -> "\nNAS 目录为空。"
                submitted == 0 -> "\n未发现可扫描媒体；检查 .nomedia、格式或应用挂载可见性。"
                else -> "" } + cleanupNote
    }

    private data class ScanCallback(val completed: Boolean, val uri: Uri?)

    private suspend fun scanPath(path: String): ScanCallback = withTimeoutOrNull(15_000) {
        suspendCancellableCoroutine { continuation ->
            MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                if (continuation.isActive) continuation.resume(ScanCallback(true, uri))
            }
        }
    } ?: ScanCallback(false, null)

    private fun indexedEntries(root: String): List<MediaIndexEntry> {
        val data = MediaStore.MediaColumns.DATA
        val id = MediaStore.MediaColumns._ID
        val type = MediaStore.Files.FileColumns.MEDIA_TYPE
        // '_' is allowed in mount names, so escape SQL LIKE wildcards explicitly.
        val prefix = "$root/".replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val cursor = context.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(id, data, type),
            "$data LIKE ? ESCAPE '\\'", arrayOf(prefix), null)
            ?: error("无法读取媒体索引，本次跳过清理")
        return cursor.use {
            val entries = mutableListOf<MediaIndexEntry>()
            while (it.moveToNext()) {
                val entry = MediaIndexEntry(it.getLong(0), it.getString(1) ?: continue, it.getInt(2))
                if (MediaIndexPolicy.relativePath(root, entry.path) != null) entries += entry
                check(entries.size <= RemoteSnapshot.MAX_ENTRIES) { "媒体索引超过 10000 条，本次跳过清理" }
            }
            entries
        }
    }

    private fun indexExists(id: Long): Boolean {
        val uri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
        return context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: error("无法确认媒体索引结果，已停止后续清理")
    }

    private fun definitelyMissing(root: Path, absolute: String): Boolean {
        val rootAttrs = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(rootAttrs.isDirectory && !rootAttrs.isSymbolicLink) { "挂载目录不可访问，已停止清理" }
        val relative = MediaIndexPolicy.relativePath(root.toString(), absolute) ?: return false
        var current = root
        for (part in relative.split('/')) {
            current = current.resolve(part)
            val attrs = try {
                Files.readAttributes(current, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return true
            }
            check(!attrs.isSymbolicLink) { "索引路径包含符号链接，已停止清理" }
        }
        return false
    }
}
