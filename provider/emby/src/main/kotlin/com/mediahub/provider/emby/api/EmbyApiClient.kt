package com.mediahub.provider.emby.api

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.model.PageRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

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

    /** 顶层媒体库 Views：GET /emby/Users/{userId}/Views。 */
    suspend fun getUserViews(token: String, userId: String): EmbyQueryResultDto<EmbyBaseItemDto> =
        apiClient.get(
            url = endpointResolver.endpoint("/Users/$userId/Views"),
            headers = authenticatedHeaders(token, userId),
        )

    /** 浏览某容器下的 Items：GET /emby/Users/{userId}/Items?ParentId=...&StartIndex=...&Limit=... */
    suspend fun getUserItems(
        token: String,
        userId: String,
        parentId: String,
        page: PageRequest,
    ): EmbyQueryResultDto<EmbyBaseItemDto> {
        val url = buildUrl(
            path = "/Users/$userId/Items",
            query = mapOf(
                "ParentId" to parentId,
                "StartIndex" to page.offset.toString(),
                "Limit" to page.limit.toString(),
                "Fields" to "PrimaryImageAspectRatio,SortName,Path",
                "EnableUserData" to "true",
            ),
        )
        return apiClient.get(url, authenticatedHeaders(token, userId))
    }

    /** 条目详情：GET /emby/Users/{userId}/Items/{itemId}（官方 UserService）。 */
    suspend fun getUserItem(token: String, userId: String, itemId: String): EmbyUserItemDto {
        val url = endpointResolver.endpoint("/Users/$userId/Items").toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .build()
            .toString()
        return apiClient.get(url, authenticatedHeaders(token, userId))
    }
    /**
     * 播放信息：GET /emby/Items/{itemId}/PlaybackInfo（官方 MediaInfoService）。
     *
     * 无转码红线（Phase 1B-2）：请求固定 EnableTranscoding=false / EnableDirectStream=true，
     * 只询问服务端能否 Direct Stream；绝不申请转码会话。
     */
    suspend fun getPlaybackInfo(
        token: String,
        userId: String,
        itemId: String,
        startTimeTicks: Long?,
        maxStreamingBitrate: Long?,
    ): EmbyPlaybackInfoDto {
        val query = mutableMapOf(
            "UserId" to userId,
            "IsPlayback" to "true",
            "EnableDirectPlay" to "false",
            "EnableDirectStream" to "true",
            "EnableTranscoding" to "false",
            "DeviceProfile" to DEVICE_PROFILE,
        )
        if (startTimeTicks != null && startTimeTicks > 0) {
            query["StartTimeTicks"] = startTimeTicks.toString()
        }
        if (maxStreamingBitrate != null && maxStreamingBitrate > 0) {
            query["MaxStreamingBitrate"] = maxStreamingBitrate.toString()
        }
        val url = buildUrlWithSegments(
            path = "/Items",
            segments = listOf(itemId, "PlaybackInfo"),
            query = query,
        )
        return apiClient.get(url, authenticatedHeaders(token, userId))
    }
    /**
     * 无转码 Direct Stream 播放地址：
     * GET /emby/Videos/{itemId}/stream.{container}?MediaSourceId=...&PlaySessionId=...&static=true
     *
     * 红线：鉴权只走 Header（X-Emby-Token），禁止任何 Token 进 URL（ADR-026）。
     */
    fun directStreamUrl(
        itemId: String,
        container: String,
        mediaSourceId: String?,
        playSessionId: String?,
    ): String {
        val builder = endpointResolver.endpoint("/Videos").toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .addPathSegment("stream.$container")
            .addQueryParameter("static", "true")
        if (!mediaSourceId.isNullOrBlank()) builder.addQueryParameter("MediaSourceId", mediaSourceId)
        if (!playSessionId.isNullOrBlank()) builder.addQueryParameter("PlaySessionId", playSessionId)
        return builder.build().toString()
    }
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

    /** 用 HttpUrl.Builder 安全拼 query（URL encoding），禁止手拼 query string。 */
    private fun buildUrl(path: String, query: Map<String, String>): String {
        val builder = endpointResolver.endpoint(path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }
    /** 带 path segments 的安全 URL 构建（segments 会被 URL 编码）。 */
    private fun buildUrlWithSegments(
        path: String,
        segments: List<String>,
        query: Map<String, String>,
    ): String {
        val builder = endpointResolver.endpoint(path).toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    /** 客户端身份头；userId 存在时带上（官方要求登录后进行带）。 */
    fun identityHeaders(userId: String? = null): Map<String, String> =
        mapOf(authHeaderBuilder.headerName() to authHeaderBuilder.build(userId))

    /** 已认证请求：客户端身份头（含 UserId，官方要求）+ X-Emby-Token。 */
    fun authenticatedHeaders(token: String, userId: String): Map<String, String> =
        identityHeaders(userId) + mapOf(TOKEN_HEADER to token)

    private companion object {
        const val TOKEN_HEADER = "X-Emby-Token"
        /**
         * PlaybackInfo 请求的最小 DeviceProfile（无转码）：告诉服务端只评估
         * Direct Stream 能力，不评估转码组合（Phase 1B-2 红线）。
         */
        private const val DEVICE_PROFILE =
            "{\"EnablePlaybackRemuxing\":true,\"EnableTranscoding\":false," +
                "\"EnableDirectPlay\":false,\"EnableDirectStream\":true}"
    }
}
