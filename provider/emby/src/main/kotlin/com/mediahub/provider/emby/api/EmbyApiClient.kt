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
    private val baseUrl: String,
    private val apiClient: ApiClient,
    private val authHeaderBuilder: EmbyAuthorizationHeaderBuilder,
    private val logger: Logger,
) {

    /** 登录：POST /Users/AuthenticateByName（官方用户名密码认证）。 */
    suspend fun authenticate(username: String, password: String): EmbyAuthenticationResultDto {
        val json = Json.encodeToString(EmbyLoginRequestDto(username = username, pw = password))
        return apiClient.postJson(
            url = "$baseUrl/Users/AuthenticateByName",
            headers = identityHeaders(),
            jsonBody = json,
        )
    }

    /** 当前用户（会话验证用，轻量认证端点）：GET /Users/Me。 */
    suspend fun getCurrentUser(token: String): EmbyUserDto =
        apiClient.get(
            url = "$baseUrl/Users/Me",
            headers = authenticatedHeaders(token),
        )

    /** 主动登出：POST /Sessions/Logout（撤销 token，best-effort）。 */
    suspend fun logout(token: String) {
        apiClient.postNoContent(
            url = "$baseUrl/Sessions/Logout",
            headers = authenticatedHeaders(token),
        )
    }

    /** 未认证请求的客户端身份头。 */
    fun identityHeaders(): Map<String, String> =
        mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build())

    /** 已认证请求：客户端身份头 + X-Emby-Token。 */
    fun authenticatedHeaders(token: String): Map<String, String> =
        identityHeaders() + mapOf(TOKEN_HEADER to token)

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}
