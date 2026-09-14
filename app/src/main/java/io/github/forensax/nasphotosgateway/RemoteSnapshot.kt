package io.github.forensax.nasphotosgateway

import org.json.JSONArray
import org.json.JSONTokener
import java.text.Normalizer
import java.util.Locale

/** A complete, uncached SMB listing bracketed by the same verified mount identity. */
data class RemoteSnapshot(val mountIdentity: String, val paths: Set<String>) {
    companion object {
        const val MAX_ENTRIES = 10000
        const val MAX_OUTPUT = 4 * 1024 * 1024

        fun parse(output: String): RemoteSnapshot {
            require(output.length <= MAX_OUTPUT) { "NAS 清单超过上限，本次跳过清理" }
            val text = output.trim()
            val start = text.lineSequence().first()
            val token = start.removePrefix("GATEWAY_SNAPSHOT ")
            require(start.startsWith("GATEWAY_SNAPSHOT ") && Regex("[0-9]+(:[0-9]+){4}").matches(token)) {
                "NAS 清单缺少有效的挂载标记，本次跳过清理"
            }
            val end = "GATEWAY_SNAPSHOT_END $token"
            require(text.substringAfterLast('\n') == end) { "NAS 清单未完整读取，本次跳过清理" }
            val json = JSONTokener(text.substring(start.length, text.length - end.length).trim())
            val items = json.nextValue()
            require(items is JSONArray && json.nextClean() == '\u0000') { "NAS 清单格式异常，本次跳过清理" }
            require(items.length() <= MAX_ENTRIES) { "NAS 清单超过 10000 个条目，请缩小 NAS 子目录" }
            val paths = buildSet {
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    item.getBoolean("IsDir") // Reject partial or unexpected listing records.
                    val path = item.getString("Path")
                    require(MediaIndexPolicy.validRelativePath(path) && path.split('/').size <= 32) {
                        "NAS 清单含不支持的路径或超过 32 层，本次跳过清理"
                    }
                    add(MediaIndexPolicy.key(path))
                }
            }
            return RemoteSnapshot(token, paths)
        }
    }
}

data class MediaIndexEntry(val id: Long, val path: String, val mediaType: Int = 1)

object MediaIndexPolicy {
    const val MAX_CLEANUP = 500

    fun validRelativePath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 &&
        '\\' !in path && path.none { it.code < 32 || it.code == 127 } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    // SMB can be case insensitive; conservatively retain case/Unicode variants.
    fun key(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun relativePath(root: String, path: String): String? {
        if (!GatewayConfig.validMountPath(root) || !path.startsWith("$root/")) return null
        return path.substring(root.length + 1).takeIf(::validRelativePath)
    }

    fun missing(root: String, entries: List<MediaIndexEntry>, snapshot: RemoteSnapshot): List<MediaIndexEntry> =
        entries.filter { entry -> entry.mediaType in setOf(1, 3) &&
            relativePath(root, entry.path)?.let { key(it) !in snapshot.paths } == true }

    fun hasScanPrefixCollision(root: String, entry: MediaIndexEntry, entries: List<MediaIndexEntry>, snapshot: RemoteSnapshot): Boolean {
        val relative = relativePath(root, entry.path) ?: return true
        // Android 10's missing-file scanner reconciles DATA LIKE 'path%'. A
        // missing a.jpg must never remove the index for a.jpg.backup.png (or an
        // unrelated audio/document row). Retain such ambiguous prefixes.
        return snapshot.paths.any { it.startsWith(key(relative)) } ||
            entries.any { it.id != entry.id && key(it.path).startsWith(key(entry.path)) }
    }

    fun confirmedMissing(root: String, entries: List<MediaIndexEntry>, before: RemoteSnapshot, after: RemoteSnapshot): List<MediaIndexEntry> {
        require(before.mountIdentity == after.mountIdentity) { "挂载发生变化，本次跳过清理" }
        require(before.paths == after.paths) { "NAS 目录内容正在变化，本次跳过清理；请稍后重试" }
        val missing = missing(root, entries, after)
        require(missing.size <= MAX_CLEANUP) { "失效索引超过 500 条，本次跳过清理；请缩小处理范围" }
        return missing
    }
}
