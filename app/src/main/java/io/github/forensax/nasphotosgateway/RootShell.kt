package io.github.forensax.nasphotosgateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class ShellResult(val code: Int, val output: String) {
    fun checked(): String {
        check(code == 0) { output.ifBlank { "Root 命令失败，退出码 $code" } }
        return output
    }
}

class RootShell {
    suspend fun run(script: String, seconds: Long = 90, outputLimit: Int = 16384): ShellResult = withContext(Dispatchers.IO) {
        // Only a constant command appears in argv. Credentials travel over stdin.
        val process = ProcessBuilder("su", "-mm", "-c", "/system/bin/sh").redirectErrorStream(true).start()
        val output = StringBuilder()
        var truncated = false
        val reader = thread(isDaemon = true, name = "root-output") {
            try { process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(2048)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    synchronized(output) {
                        val available = outputLimit - output.length
                        if (count > available) truncated = true
                        if (available > 0) output.append(buffer, 0, minOf(count, available))
                    }
                }
            } } catch (_: Exception) { /* Process cleanup may close this stream. */ }
        }
        try {
            thread(isDaemon = true, name = "root-input") {
                try { process.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") } }
                catch (_: Exception) { /* Denied or timed-out su closes stdin. */ }
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Root 操作超时；请刷新状态确认挂载结果，并检查 Magisk 授权或 NAS 网络")
            }
            reader.join(1000)
            check(!reader.isAlive) { "Root 输出尚未结束，已停止操作" }
            check(!synchronized(output) { truncated }) { "NAS 清单或命令输出超过上限，已停止操作；请缩小 NAS 子目录" }
            ShellResult(process.exitValue(), synchronized(output) { output.toString().trim() })
        } finally {
            process.destroy()
            process.inputStream.close()
        }
    }
}
