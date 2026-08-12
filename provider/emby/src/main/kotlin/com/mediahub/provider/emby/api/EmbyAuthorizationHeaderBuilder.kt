package com.mediahub.provider.emby.api

import com.mediahub.core.common.ClientIdentity

/** Emby 官方 Authorization header；字段值做引号/反斜线转义。 */
class EmbyAuthorizationHeaderBuilder(private val identity: ClientIdentity) {
    fun build(userId: String? = null): String = buildString {
        append("Emby")
        if (!userId.isNullOrBlank()) append(" UserId=\"").append(escape(userId)).append('"')
        append(if (userId.isNullOrBlank()) " " else ", ")
        append("Client=\"").append(escape(identity.client)).append("\"")
        append(", Device=\"").append(escape(identity.device)).append("\"")
        append(", DeviceId=\"").append(escape(identity.deviceId)).append("\"")
        append(", Version=\"").append(escape(identity.version)).append('"')
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    companion object {
        const val HEADER_NAME = "Authorization"
    }
}
