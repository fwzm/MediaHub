package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.MediaSearchProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import java.io.IOException
import okhttp3.Request

/** 该 Provider 类型描述（Factory 与 Provider 共用，见 ADR-015）。 */
internal val WEBDAV_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "webdav",
    serverType = ServerType.WEBDAV,
    displayName = "WebDAV",
    category = ProviderCategory.CLOUD_STORAGE,
    capabilities = setOf(
        ProviderCapability.AUTH,
        ProviderCapability.BROWSE,
        ProviderCapability.SEARCH,
    ),
    authMethod = AuthMethod.BASIC,
    status = ProviderStatus.EXPERIMENTAL,
    description = "WebDAV / NAS 通用协议",
)

/**
 * WebDAV Provider（Phase 0.5 骨架）。
 *
 * 已就绪：能力组合声明、OPTIONS 协议嗅探、Token 会话、异常映射。
 * 待实现（Phase 1，见 TASKS.md）：PROPFIND 文件树、Basic 认证、直链播放。
 */
class WebDavProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger),
    MediaProvider,
    MediaAuthProvider,
    MediaBrowseProvider,
    MediaSearchProvider {

    override val descriptor: ProviderDescriptor = WEBDAV_PROVIDER_DESCRIPTOR

    /** OPTIONS 探测：WebDAV 服务器应返回 200/207 且带 DAV 头。 */
    override suspend fun testConnection(): ConnectionStatus = try {
        val request = Request.Builder()
            .url("${server.baseUrl}/")
            .method("OPTIONS", null)
            .build()
        val start = System.nanoTime()
        mediaHttpClient.okHttpClient().newCall(request).execute().use { response ->
            val latencyMs = (System.nanoTime() - start) / 1_000_000
            val dav = response.header("DAV")
            when {
                response.code in 200..299 || response.code == 207 -> ConnectionStatus(
                    ok = true,
                    latencyMs = latencyMs,
                    message = "WebDAV 可用（HTTP ${response.code}" +
                        if (dav != null) " · DAV $dav" else "" + "）",
                )

                response.code == 401 || response.code == 403 -> ConnectionStatus(
                    ok = false,
                    message = "需要认证（HTTP ${response.code}）",
                )

                else -> ConnectionStatus(ok = false, message = "HTTP ${response.code}")
            }
        }
    } catch (e: IOException) {
        ConnectionStatus(ok = false, message = "连接失败：${e.message}")
    } catch (e: Exception) {
        ConnectionStatus(ok = false, message = "连接失败：${e.message}")
    }

    override suspend fun authHeaders(): Map<String, String> = notYet("WebDAV Basic 认证头")
    override suspend fun authenticate(credentials: Credentials): AuthResult = notYet("WebDAV 认证")
    override suspend fun refreshSession(): AuthResult = notYet("WebDAV 认证刷新")
    override suspend fun currentUser(): MediaUser? = null

    override suspend fun logout() {
        clearSession()
    }

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        notYet("WebDAV PROPFIND 列目录")

    override suspend fun search(query: String, page: PageRequest): PagedResult<MediaItem> =
        notYet("WebDAV 搜索")

    private fun <T> notYet(scope: String): T =
        throw ProviderException.NotYetImplemented(serverId, scope)
}
