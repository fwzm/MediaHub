package com.mediahub.provider.emby.api

import com.mediahub.core.common.ClientIdentity

/**
 * Emby 客户端身份头构建器（官方格式，见 ADR-025）：
 * `X-Emby-Authorization: MediaBrowser Client="...", Device="...", DeviceId="...", Version="..."`
 * 集中一处构建，禁止 UI/ViewModel 自行拼 Header。
 */
class EmbyAuthorizationHeaderBuilder(private val identity: ClientIdentity) {

    fun headerName(): String = HEADER_NAME

    fun build(): String = buildString {
        append("MediaBrowser Client=\"${identity.client}\"")
        append(", Device=\"${identity.device}\"")
        append(", DeviceId=\"${identity.deviceId}\"")
        append(", Version=\"${identity.version}\"")
    }

    private companion object {
        const val HEADER_NAME = "X-Emby-Authorization"
    }
}
