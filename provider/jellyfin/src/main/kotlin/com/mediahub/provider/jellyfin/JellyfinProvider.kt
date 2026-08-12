package com.mediahub.provider.jellyfin

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Jellyfin 当前只提供协议身份探测；未实现能力不会出现在具体类型或 Handle 上。 */
class JellyfinProvider(
    server: MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, logger) {
    override val descriptor: ProviderDescriptor = DESCRIPTOR

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus = connectionCheck {
        val info = apiClient.get<PublicSystemInfo>("${server.baseUrl.trimEnd('/')}/System/Info/Public")
        if (info.productName?.contains("Jellyfin", ignoreCase = true) != true ||
            info.id.isNullOrBlank() || info.version.isNullOrBlank()
        ) {
            throw ProviderException.Connection(serverId, "响应不是有效的 Jellyfin System Info")
        }
        "Jellyfin ${info.version} · ${info.serverName ?: info.id}"
    }

    @Serializable
    private data class PublicSystemInfo(
        @SerialName("Id") val id: String? = null,
        @SerialName("ServerName") val serverName: String? = null,
        @SerialName("Version") val version: String? = null,
        @SerialName("ProductName") val productName: String? = null,
    )

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "jellyfin",
            displayName = "Jellyfin",
            description = "Jellyfin 媒体服务器",
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
            sortOrder = 20,
        )
    }
}
