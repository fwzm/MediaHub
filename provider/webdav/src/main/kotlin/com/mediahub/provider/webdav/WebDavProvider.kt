package com.mediahub.provider.webdav

import com.mediahub.core.logging.LogTag
import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.security.CredentialVault
import com.mediahub.core.security.TokenStore
import com.mediahub.model.MediaServer
import com.mediahub.model.ServerType
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.MediaProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.base.BaseMediaServerProvider
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * 该 Provider 类型描述（Factory 与 Provider 共用，见 ADR-015）。
 *
 * `declaredCapabilities` 只声明本版本**真实实现**的能力：
 * AUTH（Basic）+ BROWSE（PROPFIND）+ DETAIL（PROPFIND Depth:0）+ PLAYBACK（直链）。
 * **不含 SEARCH**：只读包不实现远端搜索（无 `SEARCH` 方法、也无本地伪搜索）。
 * 声明一个没有可调用实现的 capability 违反 ADR-022（禁止"声明有能力但无可调用实现"）。
 */
internal val WEBDAV_PROVIDER_DESCRIPTOR = ProviderDescriptor(
    id = "webdav",
    serverType = ServerType.WEBDAV,
    displayName = "WebDAV",
    category = ProviderCategory.CLOUD_STORAGE,
    declaredCapabilities = setOf(
        ProviderCapability.AUTH,
        ProviderCapability.BROWSE,
        ProviderCapability.DETAIL,
        ProviderCapability.PLAYBACK,
    ),
    authMethod = AuthMethod.BASIC,
    status = ProviderStatus.EXPERIMENTAL,
    description = "WebDAV / NAS 通用协议（只读：认证、目录浏览、文件详情、直链播放）",
    // 无 GET 探针路径：WebDAV 的协议探针是 OPTIONS，且集合上的 GET 常被 405 拒绝，
    // 不虚构一条 GET 路径给线路质量测试（ADR-039：无探针须显式报不支持）。
    probePath = null,
)

/**
 * WebDAV Provider 的 [MediaProvider] 主责（协议探测）。
 *
 * 能力实现分布（避免巨型类，ADR-027）：
 * - 认证 → [WebDavAuthProvider]
 * - 目录浏览 → [WebDavBrowseProvider]
 * - 播放 → [WebDavPlaybackProvider]
 * - 协议与解析 → [WebDavApi] / [WebDavMultistatusParser]
 *
 * 说明：`main` 上的 [TokenStore] 只有会话令牌存取，没有 PR #18 分支引入的
 * 身份世代 lease API；因此本 Provider **不**装配身份守卫（与 main 上的
 * Emby/Jellyfin 工厂保持一致），避免对外部未合并分支形成编译依赖。
 */
class WebDavProvider(
    server: MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    tokenStore: TokenStore,
    logger: Logger,
    credentialVault: CredentialVault,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, tokenStore, logger),
    MediaProvider {

    private val session = WebDavSession(server, WebDavCredentialStore(credentialVault))

    override val descriptor: ProviderDescriptor = WEBDAV_PROVIDER_DESCRIPTOR

    /** WebDAV 会话头（Basic）。供基类/诊断使用；能力实现各自直接取 [WebDavSession]。 */
    override suspend fun authHeaders(): Map<String, String> =
        mapOf("Authorization" to session.authorization())

    /**
     * 协议级连接测试（ADR-019）：`OPTIONS` + `DAV` 头嗅探。
     *
     * 取消红线（ADR-039）：`CancellationException` 必须原样上抛，
     * 不得折叠为 `ConnectionStatus(ok = false)`。
     */
    override suspend fun testConnection(): ConnectionStatus = withContext(Dispatchers.IO) {
        val baseUrl = WebDavUrls.normalizeBase(server.baseUrl)
        if (baseUrl.isBlank()) {
            return@withContext ConnectionStatus(ok = false, message = "媒体源地址为空")
        }
        val client = mediaHttpClient.okHttpClient().newBuilder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val request = Request.Builder()
            .url(baseUrl)
            .method("OPTIONS", null)
            .build()

        coroutineContext.ensureActive()
        val start = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val latencyMs = (System.nanoTime() - start) / 1_000_000
                val dav = response.header("DAV")
                when {
                    response.code in 200..299 -> ConnectionStatus(
                        ok = true,
                        latencyMs = latencyMs,
                        message = buildString {
                            append("WebDAV 可用（HTTP ${response.code}")
                            if (!dav.isNullOrBlank()) append(" · DAV $dav")
                            append("）")
                        },
                    )

                    response.code == 401 -> ConnectionStatus(
                        ok = false,
                        latencyMs = latencyMs,
                        message = "需要认证（HTTP 401）",
                        errorCode = ProviderException.ErrorCode.AUTH_REQUIRED,
                    )

                    response.code == 403 -> ConnectionStatus(
                        ok = false,
                        latencyMs = latencyMs,
                        message = "没有访问权限（HTTP 403）",
                        errorCode = ProviderException.ErrorCode.HTTP,
                    )

                    else -> ConnectionStatus(
                        ok = false,
                        latencyMs = latencyMs,
                        message = "HTTP ${response.code}",
                        errorCode = ProviderException.ErrorCode.HTTP,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.w(LogTag.NETWORK, "WebDAV OPTIONS 失败 serverId=${server.id}", e)
            ConnectionStatus(
                ok = false,
                message = "连接失败",
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 15L
        const val CALL_TIMEOUT_SECONDS = 20L
    }
}
