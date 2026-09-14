package io.github.forensax.nasphotosgateway

import org.junit.Assert.*
import org.junit.Test

class GatewayConfigTest {
    private val good = GatewayConfig(host = "192.168.1.10", username = "pixel", password = "a'$(id)\" 密码")
    @Test fun validUnicodeAndShellCharactersAreData() {
        good.copy(share = "家庭照片", subdirectory = "2026/旅行 相片").validate()
        assertEquals("nas:photo/2026/旅行", good.copy(subdirectory = "2026/旅行").remote)
        assertEquals("'a'\"'\"'b'", shellQuote("a'b"))
    }
    @Test fun rejectUnsafeMountDestinations() {
        listOf("/", "/storage/emulated/0/DCIM", "/storage/emulated/0/DCIM/../Download", "/data/media/0", "/storage/emulated/0/DCIM/NAS;id", "/storage/emulated/0/DCIM/NAS/sub dir").forEach {
            assertFalse(it, GatewayConfig.validMountPath(it))
        }
    }
    @Test fun rejectTraversalAndControlCharacters() {
        listOf("../x", "x/../y", "/photo", "x\\y").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { good.copy(subdirectory = path).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { good.copy(password = "abc\nxyz").validate() }
        assertThrows(IllegalArgumentException::class.java) { good.copy(rclonePath = "/data/local/tmp/rclone").validate() }
    }
    @Test fun secretsDoNotBecomeCommandArguments() {
        val script = RootScripts.environment(good, true)
        assertTrue(script.contains("obscure -"))
        assertTrue(script.contains("printf '%s\\n' ${shellQuote(good.password)}"))
        assertFalse(RootScripts.environment(good, false).contains(good.password))
    }
}
