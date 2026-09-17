package io.github.forensax.nasphotosgateway

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext credentials on disk; backups disabled in the manifest. */
class ConfigStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "connection.enc"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nas-gateway-config", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nas-gateway-config", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): GatewayConfig {
        if (!file.baseFile.exists()) return GatewayConfig()
        val bytes = file.readFully()
        require(bytes.size > 28) { "配置文件损坏，请重新配置" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return decode(json)
    }
    @Synchronized fun save(config: GatewayConfig) {
        config.validate()
        val json = JSONObject().apply {
            put("host", config.host); put("share", config.share); put("username", config.username)
            put("password", config.password); put("subdirectory", config.subdirectory)
            put("mountDirectory", config.mountDirectory); put("rclonePath", config.rclonePath)
            put("restoreAtBoot", config.restoreAtBoot)
            put("allowDelete", config.allowDelete)
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    companion object {
        internal fun decode(json: JSONObject) = GatewayConfig(json.getString("host"), json.getString("share"), json.getString("username"),
            json.getString("password"), json.getString("subdirectory"), json.getString("mountDirectory"),
            json.getString("rclonePath"), json.optBoolean("restoreAtBoot"), json.optBoolean("allowDelete", false))
    }
}
