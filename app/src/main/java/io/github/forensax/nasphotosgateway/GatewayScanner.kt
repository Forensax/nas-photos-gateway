package io.github.forensax.nasphotosgateway

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

class GatewayScanner(private val context: Context) {
    fun hasPermission(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    fun visibility(path: String): String = when {
        !hasPermission() -> "未授予文件访问权限"
        !File(path).isDirectory || !File(path).canRead() -> "目录不可读；检查挂载命名空间与 SELinux"
        else -> "目录可访问，文件读取须扫描验证"
    }
    suspend fun scan(path: String, index: ScanIndex, initial: RemoteSnapshot,
        confirmSnapshot: suspend () -> RemoteSnapshot, verifyMount: suspend (String) -> Unit,
        progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        check(hasPermission()) { "请先在设置页授予文件访问权限" }
        check(GatewayConfig.validMountPath(path)) { "扫描目录无效" }
        ScanEngine(index, object : ScanMedia {
            override suspend fun scan(path: String): MediaScan = scanPath(path)
            override fun entries(accept: (MediaIndexEntry) -> Unit) = indexedEntries(path, accept)
            override fun indexExists(id: Long): Boolean = this@GatewayScanner.indexExists(id)
        }).scan(File(path).toPath(), initial, confirmSnapshot, verifyMount, progress)
    }
    private suspend fun scanPath(path: String): MediaScan {
        val callback = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Pair<Boolean, android.net.Uri?>> { continuation ->
                MediaScannerConnection.scanFile(context, arrayOf(path), null) { _, uri ->
                    if (continuation.isActive) continuation.resume(true to uri)
                }
            }
        } ?: return MediaScan(false, false, false)
        val uri = callback.second ?: return MediaScan(true, false, false)
        val readable = try { context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true }
            catch (_: java.io.IOException) { false } catch (_: SecurityException) { false }
        return MediaScan(true, true, readable)
    }
    private fun indexedEntries(root: String, accept: (MediaIndexEntry) -> Unit) {
        val prefix = "$root/".replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val cursor = context.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.Files.FileColumns.MEDIA_TYPE),
            "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'", arrayOf(prefix), null)
            ?: error("无法读取媒体索引，本次跳过清理")
        cursor.use { while (it.moveToNext()) {
            val path = it.getString(1) ?: continue
            accept(MediaIndexEntry(it.getLong(0), path, it.getInt(2)))
        } }
    }
    private fun indexExists(id: Long): Boolean = context.contentResolver.query(
        ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id),
        arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() }
        ?: error("无法确认媒体索引结果，已停止后续清理")
}
