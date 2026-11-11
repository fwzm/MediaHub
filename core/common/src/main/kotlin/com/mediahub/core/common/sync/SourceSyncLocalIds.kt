package com.mediahub.core.common.sync

import java.security.MessageDigest

/**
 * 本机来源的同步身份（syncSourceId）派生规则（W6-C）。
 *
 * Windows 的 export 会给"还没有同步身份"的来源生成一个 local- 前缀的随机串并回写到自己的
 * store（sync-cli 实测：local-385c7c48494fcb81，共 22 字符）。Android 这里改成**确定性派生**：
 * 由 deviceId + serverId 求 SHA-256 取前 16 个十六进制字符，同样以 local- 开头、总长 22。
 *
 * 这样做的理由：
 * - 重复导出同一台设备必然得到同一个 id（Windows 靠回写实现同样的稳定性，本端也回写，
 *   但即使回写失败，下一次导出的 id 也不会漂移）；
 * - 不依赖随机源，测试可以断言具体值；
 * - id 只是"记录身份"，跨设备不要求与 Windows 给同一台服务器分配的 id 相同——两端各自
 *   给本地来源编号，导入后以信封里的 id 为准。
 */
object SourceSyncLocalIds {

    const val PREFIX: String = "local-"

    private const val HEX_LENGTH = 16
    private const val SEPARATOR = "\u001F"
    private const val NAMESPACE = "mediahub-source-sync"
    private const val HEX = "0123456789abcdef"

    fun derive(deviceId: String, serverId: String): String =
        PREFIX + sha256(NAMESPACE + SEPARATOR + deviceId.trim() + SEPARATOR + serverId.trim()).take(HEX_LENGTH)

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val unsigned = byte.toInt() and 0xFF
            builder.append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0F])
        }
        return builder.toString()
    }
}
