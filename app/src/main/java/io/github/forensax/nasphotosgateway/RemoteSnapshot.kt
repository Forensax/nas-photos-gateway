package io.github.forensax.nasphotosgateway

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.Reader
import java.text.Normalizer
import java.util.Locale

/** Completion requires both identity markers, a whole JSON document and exit 0. */
data class RemoteSnapshot(val mountIdentity: String, val slot: Int, val complete: Boolean) {
    companion object {
        fun read(input: Reader, slot: Int, index: ScanIndex, active: () -> Unit = {}): RemoteSnapshot {
            index.clearListing(slot)
            var identity = ""
            var complete = false
            try {
                val json = JsonReader(input)
                json.beginObject()
                require(json.nextName() == "identity")
                identity = json.nextString()
                require(validIdentity(identity)) { "NAS 清单缺少有效挂载标记" }
                require(json.nextName() == "entries")
                json.beginArray()
                index.batch { tick ->
                    while (json.hasNext()) {
                        active()
                        var path: String? = null
                        var directory: Boolean? = null
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "Path" -> { require(path == null && json.peek() == JsonToken.STRING); path = json.nextString() }
                                "IsDir" -> { require(directory == null && json.peek() == JsonToken.BOOLEAN); directory = json.nextBoolean() }
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                        index.addPath(slot, requireNotNull(path), requireNotNull(directory))
                        tick()
                    }
                }
                json.endArray()
                require(json.nextName() == "end" && json.nextString() == identity)
                json.endObject()
                require(json.peek() == JsonToken.END_DOCUMENT)
                complete = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: IOException) { /* Partial data never authorizes cleanup. */ }
            catch (_: IllegalArgumentException) { }
            catch (_: IllegalStateException) { }
            require(validIdentity(identity)) { "未取得有效挂载标记，扫描已停止" }
            return RemoteSnapshot(identity, slot, complete)
        }
        fun validIdentity(value: String) = Regex("[0-9]+(:[0-9]+){4}:(ro|rw)").matches(value)
    }
}
data class MediaIndexEntry(val id: Long, val path: String, val mediaType: Int = 1)
object MediaIndexPolicy {
    fun validRelativePath(path: String): Boolean = path.isNotEmpty() && path.length <= 4096 &&
        '\\' !in path && path.none { it.code < 32 || it.code == 127 } &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    fun key(path: String): String = Normalizer.normalize(path, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    fun relativePath(root: String, path: String): String? {
        if (!GatewayConfig.validMountPath(root) || !path.startsWith("$root/")) return null
        return path.substring(root.length + 1).takeIf(::validRelativePath)
    }
}
