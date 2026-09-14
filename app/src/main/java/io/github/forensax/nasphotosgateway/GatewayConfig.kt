package io.github.forensax.nasphotosgateway

data class GatewayConfig(
    val host: String = "",
    val share: String = "photo",
    val username: String = "",
    val password: String = "",
    val subdirectory: String = "",
    val mountDirectory: String = "/storage/emulated/0/DCIM/NAS",
    val rclonePath: String = "/vendor/bin/rclone",
    val restoreAtBoot: Boolean = false,
) {
    fun validate() {
        require(Regex("[a-zA-Z0-9.:-]+").matches(host)) { "请输入 NAS IP 或主机名，不含协议和端口" }
        require(share.isNotBlank() && '/' !in share && '\\' !in share && share !in listOf(".", "..")) { "共享目录只填写名称，例如 photo" }
        require(username.isNotBlank()) { "请输入 SMB 用户名" }
        require(password.isNotEmpty()) { "请输入 SMB 密码" }
        listOf(host, share, username, password, subdirectory).forEach {
            require(it.length <= 1024 && it.none { c -> c.code < 32 || c.code == 127 }) { "配置不能包含换行或控制字符，单项最多 1024 字符" }
        }
        require(!subdirectory.startsWith('/') && '\\' !in subdirectory && subdirectory.split('/').none { it == ".." || it == "." }) { "NAS 子目录必须为相对路径，不能包含 . 或 .." }
        require(validMountPath(mountDirectory)) { "挂载目录须为 /storage/emulated/0/DCIM/ 下的独立子目录；仅支持英文、数字、下划线和连字符" }
        require(Regex("/(system/bin|vendor/bin|system/vendor/bin|data/adb/modules/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*)/rclone").matches(rclonePath)) { "rclone 路径须在系统 bin 或 /data/adb/modules 内" }
    }
    val remote: String get() = "nas:$share" + subdirectory.trimEnd('/').let { if (it.isEmpty()) "" else "/$it" }
    companion object {
        fun validMountPath(path: String) = Regex("/storage/emulated/0/DCIM/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*").matches(path) && path.length < 240
    }
}

/** Quote exactly one POSIX shell argument, including embedded apostrophes. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
