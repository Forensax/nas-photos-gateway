package io.github.forensax.nasphotosgateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.Reader
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Unbounded stdout, bounded diagnostics, and task-scoped root process cleanup. */
data class StreamResult<T>(val value: T, val code: Int, val error: String)

suspend fun <T> RootShell.stream(script: String, password: String,
    launch: (String) -> Process = { ProcessBuilder("su", "-mm", "-c", it).start() },
    parse: (Reader) -> T): StreamResult<T> {
    val token = "gateway-scan-" + UUID.randomUUID().toString()
    val process = AtomicReference<Process?>()
    val rootPid = AtomicReference<String?>()
    val cancelled = AtomicBoolean(false)
    val done = CountDownLatch(1)
    fun terminate() {
        val pid = rootPid.get()
        if (pid != null) {
            // The UUID is a non-secret argv tag. Verify it before signalling a
            // PID, then stop only this shell and its descendants, never pidof rclone.
            val dollar = '$'
            val killScript = """
                p=$pid
                tr '\000' '\n' < /proc/${dollar}p/cmdline 2>/dev/null | grep -Fx '${token}' >/dev/null || exit 0
                stop_tree() {
                    kill -STOP "${dollar}1" 2>/dev/null || return
                    for child in ${dollar}(cat /proc/${dollar}1/task/${dollar}1/children 2>/dev/null); do stop_tree "${dollar}child"; done
                    kill -KILL "${dollar}1" 2>/dev/null || true
                }
                stop_tree "${dollar}p"
            """.trimIndent()
            runCatching {
                val killer = launch(killScript)
                if (!killer.waitFor(5, TimeUnit.SECONDS)) killer.destroyForcibly()
                killer.inputStream.close(); killer.errorStream.close(); killer.outputStream.close()
            }
        }
        process.get()?.let { proc ->
            proc.destroyForcibly()
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }
        }
    }
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancelled.set(true)
                thread(isDaemon = true, name = "scan-cancel") { terminate() }
            }
            thread(isDaemon = true, name = "scan-stream") {
                var proc: Process? = null
                try {
                    check(!cancelled.get()) { "扫描已取消" }
                    proc = launch("/system/bin/sh -s $token")
                    process.set(proc)
                    val running = proc
                    val errors = StringBuilder()
                    val stderr = thread(isDaemon = true, name = "scan-stderr") {
                        runCatching { running.errorStream.reader().use { reader ->
                            val buffer = CharArray(2048)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                synchronized(errors) {
                                    val available = 16384 - errors.length
                                    if (available > 0) errors.append(buffer, 0, minOf(available, count))
                                }
                            }
                        } }
                    }
                    // Handshake before sending credentials or starting a listing.
                    // Cancellation before this marker can only kill an idle shell.
                    val scriptWriter = running.outputStream.bufferedWriter()
                    scriptWriter.write("printf 'GATEWAY_PROCESS %s\\n' \"$$\"\n")
                    scriptWriter.flush()
                    val value = running.inputStream.bufferedReader().use { reader ->
                        val header = reader.readLine().orEmpty()
                        require(Regex("GATEWAY_PROCESS [0-9]+").matches(header)) { "无法启动 Root 清单进程" }
                        rootPid.set(header.substringAfter(' '))
                        if (cancelled.get()) { terminate(); error("扫描已取消") }
                        thread(isDaemon = true, name = "scan-script") {
                            runCatching { scriptWriter.use { it.write(script); it.write("\nexit\n") } }
                        }
                        val parsed = parse(reader)
                        // A malformed/partial JSON parser may return before EOF.
                        // Keep draining so the producer cannot deadlock on stdout.
                        val discard = CharArray(8192)
                        while (reader.read(discard) >= 0) {
                            if (cancelled.get()) { terminate(); error("扫描已取消") }
                        }
                        parsed
                    }
                    running.waitFor()
                    stderr.join()
                    val error = synchronized(errors) { errors.toString().replace(password, "[已隐藏]").take(1500) }
                    if (continuation.isActive) continuation.resume(StreamResult(value, running.exitValue(), error))
                } catch (error: Throwable) {
                    terminate()
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    proc?.destroy()
                    runCatching { proc?.inputStream?.close() }
                    runCatching { proc?.errorStream?.close() }
                    runCatching { proc?.outputStream?.close() }
                    done.countDown()
                }
            }
        }
    } finally {
        // Never close the task database while its parser worker still uses it.
        withContext(NonCancellable + Dispatchers.IO) { done.await() }
    }
}

