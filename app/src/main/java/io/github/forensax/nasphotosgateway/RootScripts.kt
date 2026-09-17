package io.github.forensax.nasphotosgateway

/** Shell bodies live in an asset so CI can exercise them with a fake mount table. */
object RootScripts {
    const val SOURCE = "/mnt/nas-photos-gateway"
    fun environment(config: GatewayConfig, credentials: Boolean): String {
        config.validate()
        return buildString {
            appendLine("set -eu")
            appendLine("export PATH=/vendor/bin:/system/vendor/bin:/system/bin:/system/xbin:/data/adb/magisk:\$PATH")
            appendLine("TARGET=${shellQuote(config.mountDirectory)}")
            appendLine("SOURCE=${shellQuote(SOURCE)}")
            appendLine("RCLONE=${shellQuote(config.rclonePath)}")
            appendLine("REMOTE=${shellQuote(config.remote)}")
            appendLine("ALLOW_DELETE=${shellQuote(config.allowDelete.toString())}")
            if (credentials) {
                appendLine("[ -x \"\$RCLONE\" ] || { echo '找不到 rclone，请安装 FUSE Magisk 模块并检查路径'; exit 10; }")
                appendLine("export RCLONE_CONFIG=/dev/null")
                appendLine("export RCLONE_CONFIG_NAS_TYPE=smb")
                appendLine("export RCLONE_CONFIG_NAS_HOST=${shellQuote(config.host)}")
                appendLine("export RCLONE_CONFIG_NAS_USER=${shellQuote(config.username)}")
                appendLine("export RCLONE_CONFIG_NAS_DOMAIN=WORKGROUP")
                appendLine("export RCLONE_CONFIG_NAS_PASS=\$(printf '%s\\n' ${shellQuote(config.password)} | \"\$RCLONE\" obscure -)")
                appendLine("[ -n \"\$RCLONE_CONFIG_NAS_PASS\" ] || exit 11")
            }
        }
    }
}
