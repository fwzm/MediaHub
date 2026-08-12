package com.mediahub.core.common

import android.content.Context
import java.util.UUID

/**
 * 客户端身份（Emby/Jellyfin 的 authorization header 需要，见 ADR-025）。
 * 多服务器协议共用，不复制到各 Provider。
 */
data class ClientIdentity(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String,
)

/**
 * 稳定客户端身份提供者：
 * - DeviceId 首次生成 UUID 并持久化，后续启动保持一致（不每次随机，不使用硬件序列号）。
 */
class ClientIdentityProvider(
    context: Context,
    private val version: String,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): ClientIdentity {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: generateAndSave()
        return ClientIdentity(
            client = CLIENT_NAME,
            device = DEVICE_NAME,
            deviceId = deviceId,
            version = version,
        )
    }

    private fun generateAndSave(): String {
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private companion object {
        const val PREFS_NAME = "mediahub_client_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val CLIENT_NAME = "MediaHub"
        const val DEVICE_NAME = "Android"
    }
}
