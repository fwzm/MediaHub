package com.mediahub.provider.emby.api

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Emby HTTP 协议封装（Phase 1A：认证与会话）。
 *
 * - URL/path 构造集中在此；
 * - 客户端身份头（X-Emby-Authorization）由 [EmbyAuthorizationHeaderBuilder] 统一构建；
 * - 登录后的请求统一加 `X-Emby-Token`（禁止每个 endpoint 手工拼 Header；
 *   禁止把 AccessToken 放 URL query，见 ADR-026）。
 *
 * 非 2xx 抛 [com.mediahub.core.network.ApiException]；JSON 解析失败抛序列化异常；
 * 网络错误抛 IOException——由上层（EmbyAuthProvider）映射为业务错误。
 */
class EmbyApiClient(
    private val endpointResolver: EmbyEndpointResolver,
    private val apiClient: ApiClient,
    private val authHeaderBuilder: EmbyAuthorizationHeaderBuilder,
    private val logger: Logger,
) {

    /** 登录：POST /emby/Users/AuthenticateByName（官方用户名密码认证）。 */
    suspend fun authenticate(username: String, password: String): EmbyAuthenticationResultDto {
        val json = Json.encodeToString(EmbyLoginRequestDto(username = username, pw = password))
        return apiClient.postJson(
            url = endpointResolver.endpoint("/Users/AuthenticateByName"),
            headers = identityHeaders(),
            jsonBody = json,
        )
    }

    /**
     * 当前用户（会话验证用）：GET /emby/Users/{userId}。
     * 官方 UserService Reference 取单个用户接口为 GET /Users/{Id}，不使用未文档化 /Users/Me。
     */
    suspend fun getCurrentUser(token: String, userId: String): EmbyUserDto =
        apiClient.get(
            url = endpointResolver.endpoint("/Users/$userId"),
            headers = authenticatedHeaders(token, userId),
        )

    /** 服务器公开信息（**无 Token**）：GET /emby/System/Info/Public。
     *  用于会话恢复前校验 remoteServerId，避免把旧 Token 发给另一台服务器（review #2）。 */
    suspend fun getSystemInfoPublic(): SystemInfoPublic =
        apiClient.get(
            url = endpointResolver.endpoint("/System/Info/Public"),
            headers = identityHeaders(),
        )

    /** 主动登出：POST /emby/Sessions/Logout（撤销 token，best-effort）。 */
    suspend fun logout(token: String, userId: String) {
        apiClient.postNoContent(
            url = endpointResolver.endpoint("/Sessions/Logout"),
            headers = authenticatedHeaders(token, userId),
        )
    }

    /** 客户端身份头；userId 存在时带上（官方要求登录后进行带）。 */
    fun identityHeaders(userId: String? = null): Map<String, String> =
        mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build(userId))

    /** 已认证请求：客户端身份头（含 UserId，官方要求）+ X-Emby-Token。 */
    fun authenticatedHeaders(token: String, userId: String): Map<String, String> =
        identityHeaders(userId) + mapOf(TOKEN_HEADER to token)

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}
