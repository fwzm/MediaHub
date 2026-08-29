package com.mediahub.provider.jellyfin.api

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Jellyfin HTTP 协议封装（Phase 1G-A：认证与会话）。
 *
 * - URL/path 构造集中在 [JellyfinEndpointResolver]（保留反代子路径，无协议前缀知识）；
 * - 认证只走标准 `Authorization: MediaBrowser …` 头（[JellyfinAuthorizationHeaderBuilder]），
 *   **Token 绝不进 URL/query**（ADR-026 同款红线）；X-Emby-* 与 X-MediaBrowser-* legacy 头不使用；
 * - 非 2xx 抛 ApiException；解析失败抛序列化异常；网络错误抛 IOException——由上层
 *   （JellyfinAuthProvider）映射为业务错误。
 */
class JellyfinApiClient(
    private val endpointResolver: JellyfinEndpointResolver,
    private val apiClient: ApiClient,
    private val authHeaderBuilder: JellyfinAuthorizationHeaderBuilder,
    private val logger: Logger,
) {

    /** 登录：POST /Users/AuthenticateByName（密码只在 body，绝不持久化/日志，ADR-016）。 */
    suspend fun authenticate(username: String, password: String): JellyfinAuthenticationResultDto {
        val json = Json.encodeToString(JellyfinLoginRequestDto(username = username, pw = password))
        return apiClient.postJson(
            url = endpointResolver.endpoint("/Users/AuthenticateByName"),
            headers = identityHeaders(),
            jsonBody = json,
        )
    }

    /** 当前用户（会话恢复验证用）：GET /Users/{userId}，标准 Authorization 带 Token。 */
    suspend fun getCurrentUser(token: String, userId: String): JellyfinUserDto =
        apiClient.get(
            url = endpointResolver.endpoint("/Users/$userId"),
            headers = authenticatedHeaders(token),
        )

    /** 服务器公开信息（**无 Token**）：GET /System/Info/Public（恢复时的防串服身份校验）。 */
    suspend fun getSystemInfoPublic(): JellyfinSystemInfoPublic =
        apiClient.get(
            url = endpointResolver.endpoint("/System/Info/Public"),
            headers = identityHeaders(),
        )

    /** 主动登出：POST /Sessions/Logout（撤销 token，best-effort）。 */
    suspend fun logout(token: String) {
        apiClient.postNoContent(
            url = endpointResolver.endpoint("/Sessions/Logout"),
            headers = authenticatedHeaders(token),
        )
    }

    /** 匿名客户端身份（登录前 / 公共探针）：标准 Authorization，无 Token。 */
    fun identityHeaders(): Map<String, String> =
        mapOf(HEADER_NAME to authHeaderBuilder.build())

    /** 已认证请求：标准 Authorization 头内嵌 Token。 */
    fun authenticatedHeaders(token: String): Map<String, String> =
        mapOf(HEADER_NAME to authHeaderBuilder.build(token))

    private companion object {
        const val HEADER_NAME = "Authorization"

        /** 请求体序列化：explicitNulls=false 省略未设置字段；ignoreUnknownKeys 兼容版本差异。 */
        private val requestJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
