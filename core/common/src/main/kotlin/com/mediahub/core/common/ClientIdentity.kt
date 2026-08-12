package com.mediahub.core.common

import android.content.Context
import java.util.UUID

/** Emby/Jellyfin 等协议共享的稳定客户端身份。 */
data class ClientIdentity(
    val client: String,
    val device: String,
    val deviceId: String,
    val version: String,
)

/** DeviceId 首次生成后持久化；不使用硬件标识，也不在每次启动时随机变化。 */
class ClientIdentityProvider(
    context: Context,
    private val version: String,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): ClientIdentity {
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        return ClientIdentity(
            client = CLIENT_NAME,
            device = DEVICE_NAME,
            deviceId = deviceId,
            version = version,
        )
    }

    private companion object {
        const val PREFS_NAME = "mediahub_client_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val CLIENT_NAME = "MediaHub"
        const val DEVICE_NAME = "Android"
    }
}
