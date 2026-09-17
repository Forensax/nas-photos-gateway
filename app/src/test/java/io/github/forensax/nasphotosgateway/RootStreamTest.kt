package io.github.forensax.nasphotosgateway

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class RootStreamTest {
    private val launchRoot: (String) -> Process = { ProcessBuilder("/bin/sh", "-c", it.replace("/system/bin/sh", "/bin/sh")).start() }
    @Test(timeout = 15000) fun streamsLargeOutputSeparatelyFromRedactedErrors() = runBlocking {
        val result = RootShell().stream(
            "awk 'BEGIN { for (i=0;i<5000000;i++) printf \"x\" }'; printf 'password-value' >&2; exit 21",
            "password-value", launchRoot) { reader ->
                var size = 0L; val buffer = CharArray(4096)
                while (true) { val n = reader.read(buffer); if (n < 0) break; size += n }
                size
            }
        assertEquals(5000000L, result.value)
        assertEquals(21, result.code)
        assertEquals("[已隐藏]", result.error)
    }
    @Test(timeout = 15000) fun cancellationTerminatesOnlyTheOwnedProcessTree() = runBlocking {
        val ready = AtomicBoolean(false)
        val survivor = ProcessBuilder("sleep", "30").start()
        val directory = Files.createTempDirectory("stream-test")
        val pidFile = directory.resolve("child.pid")
        try {
            val task = launch(Dispatchers.IO) {
                RootShell().stream("sleep 30 & echo \$! > '${pidFile}'; echo ready; wait", "secret", launchRoot) { reader ->
                    assertEquals("ready", reader.buffered().readLine())
                    ready.set(true)
                    reader.readText()
                }
            }
            withTimeout(5000) { while (!ready.get()) delay(10) }
            val child = Files.readString(pidFile).trim().toLong()
            withTimeout(7000) { task.cancelAndJoin() }
            assertTrue(survivor.isAlive)
            val stat = java.io.File("/proc/$child/stat")
            // A just-killed orphan may briefly remain as a zombie until reaped.
            assertTrue(!stat.exists() || stat.readText().substringAfterLast(") ").startsWith("Z"))
        } finally {
            survivor.destroyForcibly()
            Files.deleteIfExists(pidFile); Files.deleteIfExists(directory)
        }
    }
}
