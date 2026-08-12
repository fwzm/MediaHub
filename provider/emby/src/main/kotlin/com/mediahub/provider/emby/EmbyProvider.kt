package com.mediahub.provider.emby

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.model.MediaServer
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import com.mediahub.provider.emby.api.EmbyApiClient

/** Emby 公共身份与协议嗅探；具体能力保持拆分在 auth/api/mapper。 */
class EmbyProvider(
    server: MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    logger: Logger,
    private val embyApi: EmbyApiClient,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, logger) {
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    override suspend fun testConnection(
        request: ConnectionTestRequest,
    ): ConnectionStatus = connectionCheck {
        val info = embyApi.publicSystemInfo()
        if (info.id.isNullOrBlank() || info.version.isNullOrBlank()) {
            throw ProviderException.Connection(serverId, "响应不是有效的 Emby System Info")
        }
        if (info.productName?.contains("Jellyfin", ignoreCase = true) == true) {
            throw ProviderException.Connection(serverId, "检测到 Jellyfin 服务，请选择 Jellyfin")
        }
        "Emby ${info.version} · ${info.serverName ?: info.id}"
    }

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "emby",
            displayName = "Emby",
            description = "Emby 媒体服务器",
            category = ProviderCategory.MEDIA_SERVER,
            capabilities = setOf(
                ProviderCapability.AUTH,
                ProviderCapability.LIBRARY,
                ProviderCapability.PLAYBACK,
                ProviderCapability.SEARCH,
                ProviderCapability.SUBTITLE,
                ProviderCapability.PROGRESS,
                ProviderCapability.MULTI_VERSION,
                ProviderCapability.TRANSCODE,
            ),
            authMethod = AuthMethod.USERNAME_PASSWORD,
            status = ProviderStatus.EXPERIMENTAL,
            sortOrder = 10,
        )
    }
}
