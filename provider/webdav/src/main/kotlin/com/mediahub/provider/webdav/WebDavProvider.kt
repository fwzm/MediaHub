package com.mediahub.provider.webdav

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.core.network.ApiException
import com.mediahub.core.network.MediaHttpClient
import com.mediahub.core.network.ServerProbeResult
import com.mediahub.model.MediaItem
import com.mediahub.model.MediaUser
import com.mediahub.model.PageRequest
import com.mediahub.model.PagedResult
import com.mediahub.model.PlaybackOptions
import com.mediahub.model.PlaybackSource
import com.mediahub.provider.api.AuthMethod
import com.mediahub.provider.api.AuthResult
import com.mediahub.provider.api.ConnectionStatus
import com.mediahub.provider.api.ConnectionTestRequest
import com.mediahub.provider.api.CredentialVault
import com.mediahub.provider.api.Credentials
import com.mediahub.provider.api.MediaAuthProvider
import com.mediahub.provider.api.MediaBrowseProvider
import com.mediahub.provider.api.MediaPlaybackProvider
import com.mediahub.provider.api.ProviderCapability
import com.mediahub.provider.api.ProviderCategory
import com.mediahub.provider.api.ProviderDescriptor
import com.mediahub.provider.api.ProviderException
import com.mediahub.provider.api.ProviderStatus
import com.mediahub.provider.api.SessionCredential
import com.mediahub.provider.base.BaseMediaServerProvider
import java.io.IOException
import java.nio.charset.Charset
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** WebDAV Phase 0.5 骨架；OPTIONS 协议探测和 Basic 凭据生命周期已实现。 */
class WebDavProvider(
    server: com.mediahub.model.MediaServer,
    apiClient: ApiClient,
    mediaHttpClient: MediaHttpClient,
    credentialVault: CredentialVault,
    logger: Logger,
) : BaseMediaServerProvider(server, apiClient, mediaHttpClient, credentialVault, logger),
    MediaAuthProvider,
    MediaBrowseProvider,
    MediaPlaybackProvider {

    override val descriptor: ProviderDescriptor = DESCRIPTOR

    override suspend fun testConnection(request: ConnectionTestRequest): ConnectionStatus {
        val headers = when (val credentials = request.credentials) {
            is Credentials.BasicAuth -> mapOf("Authorization" to WebDavBasicAuth.build(credentials.username, credentials.password))
            else -> emptyMap()
        }
        return when (val result = apiClient.probe(server.baseUrl, method = "OPTIONS", headers = headers)) {
            is ServerProbeResult.Success -> {
                val davHeader = result.responseHeaders.entries
                    .firstOrNull { it.key.equals("DAV", ignoreCase = true) }
                    ?.value
                when {
                    // review #10：401/403 = 服务器可能是 WebDAV 但需要认证，不是"未声明能力"
                    result.httpCode == 401 || result.httpCode == 403 -> ConnectionStatus(
                        ok = false,
                        message = "需要认证（HTTP ${result.httpCode}）",
                        errorCode = ProviderException.ErrorCode.AUTH_REQUIRED,
                    )

                    result.httpCode in 200..299 && !davHeader.isNullOrBlank() -> ConnectionStatus(
                        ok = true,
                        latencyMs = result.latencyMs,
                        message = "WebDAV $davHeader · ${result.latencyMs}ms",
                    )

                    else -> ConnectionStatus(
                        ok = false,
                        message = "响应未声明 WebDAV 能力（HTTP ${result.httpCode}）",
                        errorCode = ProviderException.ErrorCode.CONNECTION,
                    )
                }
            }

            is ServerProbeResult.Failure -> ConnectionStatus(
                ok = false,
                message = result.userMessage,
                errorCode = ProviderException.ErrorCode.CONNECTION,
            )
        }
    }

    override suspend fun authenticate(credentials: Credentials): AuthResult {
        val basic = credentials as? Credentials.BasicAuth
            ?: return AuthResult.Failure(ProviderException.AuthFailed(serverId, "WebDAV 需要 Basic 凭据"))
        return try {
            // review #7：OPTIONS 只用于协议探测；认证必须用受保护操作（PROPFIND）验证凭据，
            // 匿名 OPTIONS 成功不代表密码正确。
            val charset = detectBasicCharset()
            when (propfindProbe(basic, charset)) {
                true -> AuthResult.Success(
                    user = MediaUser(serverId = serverId, userId = basic.username, displayName = basic.username),
                    session = SessionCredential.BasicAuth(basic.username, basic.password),
                )
                false -> AuthResult.Failure(ProviderException.AuthFailed(serverId, "用户名或密码错误"))
            }
        } catch (e: ProviderException) {
            AuthResult.Failure(e)
        } catch (e: ApiException) {
            AuthResult.Failure(ProviderException.Http(serverId, e.statusCode, e.url, e.method, e.requestId))
        } catch (e: IOException) {
            AuthResult.Failure(ProviderException.Network(serverId, e))
        } catch (e: Exception) {
            AuthResult.Failure(ProviderException.Unknown(serverId, e))
        }
    }

    /** 探测 WWW-Authenticate 中的 charset（RFC 7617；无声明默认 ISO-8859-1）。 */
    private suspend fun detectBasicCharset(): Charset {
        val request = Request.Builder().url("${server.baseUrl}/").method("OPTIONS", null).build()
        return runCatching {
            mediaHttpClient.okHttpClient().newCall(request).execute().use { response ->
                WebDavBasicAuth.charsetFromChallenge(response.header("WWW-Authenticate"))
            }
        }.getOrDefault(Charset.defaultCharset())
    }

    /** PROPFIND Depth:0（受保护操作）：200/207 = 凭据有效；401/403 = 失败。 */
    private suspend fun propfindProbe(
        basic: Credentials.BasicAuth,
        charset: Charset,
    ): Boolean {
        val body = WebDavBasicAuth.propfindBody().toRequestBody("application/xml".toMediaType())
        val request = Request.Builder()
            .url("${server.baseUrl}/")
            .method("PROPFIND", body)
            .header("Depth", "0")
            .header("Authorization", WebDavBasicAuth.build(basic.username, basic.password, charset))
            .build()
        mediaHttpClient.okHttpClient().newCall(request).execute().use { response ->
            return response.code in 200..299 || response.code == 207
        }
    }

    override suspend fun refreshSession(): AuthResult {
        val session = requireSession() as? SessionCredential.BasicAuth
            ?: return AuthResult.Failure(ProviderException.AuthRequired(serverId))
        return authenticate(Credentials.BasicAuth(session.username, session.password))
    }

    override suspend fun logout() = clearCredentials()

    override suspend fun currentUser(): MediaUser? =
        (credentialVault.readSession(serverId) as? SessionCredential.BasicAuth)?.let {
            MediaUser(serverId = serverId, userId = it.username, displayName = it.username)
        }

    override suspend fun listFolder(folder: MediaItem?, page: PageRequest): PagedResult<MediaItem> =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV PROPFIND 列目录")

    override suspend fun resolvePlayback(item: MediaItem, options: PlaybackOptions): PlaybackSource =
        throw ProviderException.NotYetImplemented(serverId, "WebDAV 播放源解析")

    companion object {
        val DESCRIPTOR = ProviderDescriptor(
            providerId = "webdav",
            displayName = "WebDAV",
            description = "WebDAV / NAS 通用协议",
            category = ProviderCategory.NETWORK_STORAGE,
            capabilities = setOf(
                ProviderCapability.AUTH,
                ProviderCapability.BROWSE,
                ProviderCapability.PLAYBACK,
            ),
            authMethod = AuthMethod.BASIC,
            status = ProviderStatus.EXPERIMENTAL,
            sortOrder = 30,
        )
    }
}
