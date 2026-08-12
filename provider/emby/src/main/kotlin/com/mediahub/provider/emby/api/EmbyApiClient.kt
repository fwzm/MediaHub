package com.mediahub.provider.emby.api

import com.mediahub.core.network.ApiClient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Phase 1A Emby HTTP 边界：端点、身份 Header 和 Token Header 集中在此。 */
class EmbyApiClient(
    baseUrl: String,
    private val apiClient: ApiClient,
    private val authorization: EmbyAuthorizationHeaderBuilder,
) {
    private val root = EmbyApiRoot.from(baseUrl)

    suspend fun publicSystemInfo(): EmbySystemInfoPublicDto =
        apiClient.get(root.endpoint("System/Info/Public"), identityHeaders())

    suspend fun authenticate(username: String, password: String): EmbyAuthenticationResultDto =
        apiClient.postJson(
            url = root.endpoint("Users/AuthenticateByName"),
            headers = identityHeaders(),
            jsonBody = Json.encodeToString(EmbyLoginRequestDto(username, password)),
        )

    suspend fun currentUser(token: String, userId: String): EmbyUserDto =
        apiClient.get(root.endpoint("Users/Me"), authenticatedHeaders(token, userId))

    suspend fun logout(token: String, userId: String) {
        apiClient.postNoContent(
            root.endpoint("Sessions/Logout"),
            authenticatedHeaders(token, userId),
        )
    }

    fun identityHeaders(userId: String? = null): Map<String, String> =
        mapOf(EmbyAuthorizationHeaderBuilder.HEADER_NAME to authorization.build(userId))

    fun authenticatedHeaders(token: String, userId: String): Map<String, String> =
        identityHeaders(userId) + (TOKEN_HEADER to token)

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
    }
}
