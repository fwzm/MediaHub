package com.mediahub.provider.emby.api

import com.mediahub.core.common.ClientIdentity

/**
 * Emby 客户端身份头构建器（官方 schema，见 dev.emby.media REST Reference）：
 * `X-Emby-Authorization: Emby UserId="...", Client="...", Device="...", DeviceId="...", Version="..."`
 *
 * - UserId 可选（登录成功后带上）；登录前省略。
 * - Token 不放此头，始终走 `X-Emby-Token`（与官方认证流程一致）。
 * 集中一处构建，禁止 UI/ViewModel 自行拼 Header。
 */
class EmbyAuthorizationHeaderBuilder(private val identity: ClientIdentity) {

    fun headerName(): String = HEADER_NAME

    fun build(userId: String? = null): String = buildString {
        append("Emby ")
        if (userId != null) append("UserId=\"$userId\", ")
        append("Client=\"${identity.client}\", ")
        append("Device=\"${identity.device}\", ")
        append("DeviceId=\"${identity.deviceId}\", ")
        append("Version=\"${identity.version}\"")
    }

    private companion object {
        const val HEADER_NAME = "X-Emby-Authorization"
    }
}
