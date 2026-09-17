package io.github.forensax.nasphotosgateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

/** Injectable boundaries let tests exercise the real traversal/cleanup engine. */
interface ScanMedia {
    suspend fun scan(path: String): MediaScan
    fun entries(accept: (MediaIndexEntry) -> Unit)
    fun indexExists(id: Long): Boolean
}
data class MediaScan(val completed: Boolean, val indexed: Boolean, val readable: Boolean)
open class ScanFiles {
    open fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    open fun children(path: Path, accept: (Path) -> Unit) { Files.newDirectoryStream(path).use { it.forEach(accept) } }
    open fun readable(path: Path): Boolean = Files.newInputStream(path).use { it.read() >= 0 }
    fun missing(root: Path, path: Path): Boolean {
        require(path.startsWith(root))
        var current = root
        val rootAttrs = attributes(root)
        check(rootAttrs.isDirectory && !rootAttrs.isSymbolicLink) { "挂载目录不可访问" }
        for (part in root.relativize(path)) {
            current = current.resolve(part)
            val attrs = try { attributes(current) } catch (_: NoSuchFileException) { return true }
            catch (error: IOException) {
                // Some Android SMB/FUSE versions report EIO for absent paths.
                // Only a successful parent enumeration can establish absence;
                // unreadable parents or a present name preserve the error.
                var present = false
                children(current.parent) { if (it.fileName == current.fileName) present = true }
                if (!present) return true
                throw error
            }
            check(!attrs.isSymbolicLink) { "路径包含符号链接" }
        }
        return false
    }
}
data class ScanStats(
    var visited: Long = 0, var submitted: Long = 0, var indexed: Long = 0,
    var readable: Long = 0, var failed: Long = 0, var skipped: Long = 0,
    var cleaned: Long = 0, var retained: Long = 0,
) {
    fun display() = "已遍历 $visited · 已提交 $submitted · 已索引 $indexed · 可读取 $readable · 失败 $failed · 跳过 $skipped · 已清理 $cleaned · 保留 $retained"
}

class ScanEngine(private val index: ScanIndex, private val media: ScanMedia, private val files: ScanFiles = ScanFiles()) {
    suspend fun scan(
        root: Path, initial: RemoteSnapshot,
        confirm: suspend () -> RemoteSnapshot,
        verifyMount: suspend (String) -> Unit,
        progress: (String) -> Unit,
    ): String {
        val stats = ScanStats()
        val extensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "avif", "dng", "mp4", "mov", "m4v", "3gp", "mkv")
        var partial = !initial.complete
        var readIssue = ""
        var note = if (partial) "NAS 清单不完整，已跳过失效索引清理。" else ""
        val task = coroutineContext
        try {
            verifyMount(initial.mountIdentity)
            check(files.attributes(root).isDirectory) { "挂载目录不可访问" }
            index.enqueue(root.toString())
            while (true) {
                task.ensureActive()
                val item = index.nextWork() ?: break
                val path = java.nio.file.Paths.get(item)
                stats.visited++
                // Verify at directory boundaries and at most every 128 files.
                if (stats.visited % 128L == 1L) verifyMount(initial.mountIdentity)
                try {
                    require(path.startsWith(root))
                    // Detect ancestor replacement/symlinks before any read.
                    if (files.missing(root, path)) throw NoSuchFileException(item)
                    val attrs = files.attributes(path)
                    if (attrs.isSymbolicLink) { stats.skipped++; partial = true; continue }
                    if (path != root && path.fileName.toString().startsWith('.')) { stats.skipped++; continue }
                    if (attrs.isDirectory) {
                        verifyMount(initial.mountIdentity)
                        var hidden = false
                        var listed = false
                        try {
                            index.batch { tick -> files.children(path) { child ->
                                task.ensureActive()
                                if (child.fileName.toString() == ".nomedia") hidden = true
                                val relative = root.relativize(child).toString().replace(java.io.File.separatorChar, '/')
                                if (MediaIndexPolicy.validRelativePath(relative)) { index.stage(child.toString()); tick() }
                                else { stats.failed++; partial = true }
                            } }
                            listed = true
                        } finally { index.finishDirectory(listed && !hidden) }
                        if (hidden) stats.skipped++
                    } else if (attrs.isRegularFile && path.fileName.toString().substringAfterLast('.', "").lowercase() in extensions) {
                        val relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
                        if (initial.complete && !index.contains(initial.slot, relative)) { stats.skipped++; partial = true; continue }
                        if (!files.readable(path)) { stats.failed++; partial = true; continue }
                        task.ensureActive()
                        stats.submitted++
                        val result = media.scan(item)
                        if (result.indexed) stats.indexed++
                        if (result.readable) stats.readable++
                        if (!result.completed || !result.indexed || !result.readable) { stats.failed++; partial = true }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: IOException) { stats.failed++; partial = true; readIssue = error.message.orEmpty().take(200) }
                catch (error: SecurityException) { stats.failed++; partial = true; readIssue = error.message.orEmpty().take(200) }
                progress(stats.display())
            }
            task.ensureActive()
            verifyMount(initial.mountIdentity)
            if (partial || stats.failed > 0) {
                note = "NAS 清单 ${index.count(initial.slot)} 条；扫描或清单不完整，已跳过失效索引清理。" +
                    if (readIssue.isNotEmpty()) "\n读取失败：$readIssue" else ""
            } else {
                index.batch { tick -> media.entries { entry -> task.ensureActive(); index.addMedia(root.toString(), entry); tick() } }
                if (index.missing(initial.slot).isNotEmpty()) {
                    progress("正在再次核对 NAS\n${stats.display()}")
                    val after = confirm()
                    check(after.mountIdentity == initial.mountIdentity) { "挂载发生变化" }
                    if (!after.complete || !index.sameListings(initial.slot, after.slot)) {
                        partial = true; note = "NAS 清单不完整或内容发生变化，已跳过失效索引清理。"
                    } else {
                        // Validate ALL candidates before submitting any absent path.
                        var cursor = -1L
                        while (true) {
                            task.ensureActive()
                            val batch = index.missing(after.slot, cursor)
                            if (batch.isEmpty()) break
                            for (entry in batch) {
                                task.ensureActive()
                                if (index.prefixCollision(root.toString(), entry, after.slot) || !files.missing(root, java.nio.file.Paths.get(entry.path))) stats.retained++
                                else index.markCandidate(entry.id)
                            }
                            cursor = batch.last().id
                        }
                        cursor = -1L
                        while (true) {
                            task.ensureActive()
                            val batch = index.candidates(cursor)
                            if (batch.isEmpty()) break
                            for (entry in batch) {
                                task.ensureActive()
                                verifyMount(after.mountIdentity)
                                if (!files.missing(root, java.nio.file.Paths.get(entry.path))) { stats.retained++; continue }
                                val result = media.scan(entry.path)
                                check(result.completed) { "系统扫描回调超时，已停止后续清理" }
                                if (media.indexExists(entry.id)) stats.retained++ else stats.cleaned++
                                progress(stats.display())
                            }
                            cursor = batch.last().id
                        }
                    }
                }
            }
            return (if (partial) "部分完成" else "完成") + "\n" + stats.display() +
                (if (initial.complete && index.count(initial.slot) == 0L) "\nNAS 目录为空。" else "") +
                (if (note.isNotEmpty()) "\n$note" else "")
        } catch (cancelled: CancellationException) {
            progress("已中断\n${stats.display()}")
            throw cancelled
        } catch (error: Exception) {
            progress("已中断\n${stats.display()}")
            throw error
        }
    }
}
