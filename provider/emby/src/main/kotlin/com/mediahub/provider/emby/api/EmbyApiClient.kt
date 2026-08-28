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

    /**
     * 浏览某容器下的 Items：GET /emby/Users/{userId}/Items?ParentId=...&StartIndex=...&Limit=...
     *
     * Phase 1C-2：[sortBy]/[sortOrder] 把排序下沉到服务器（分页之前执行）；
     * 均为 null 时不携带 SortBy/SortOrder（服务器默认排序）。
     * [sortOrder] 只允许 Ascending/Descending（Emby wire 值，映射见 EmbySortMapper）。
     */
    suspend fun getUserItems(
        token: String,
        userId: String,
        parentId: String,
        page: PageRequest,
        sortBy: String? = null,
        sortOrder: String? = null,
    ): EmbyQueryResultDto<EmbyBaseItemDto> {
        val url = buildUrl(
            path = "/Users/$userId/Items",
            query = buildMap {
                put("ParentId", parentId)
                put("StartIndex", page.offset.toString())
                put("Limit", page.limit.toString())
                put("Fields", LIBRARY_FIELDS)
                put("EnableUserData", "true")
                sortBy?.let { put("SortBy", it) }
                sortOrder?.let { put("SortOrder", it) }
            },
        )
        return apiClient.get(url, authenticatedHeaders(token, userId))
    }

    /**
     * 全库搜索（Phase 1C-1）：GET /Users/{userId}/Items?SearchTerm=...&Recursive=true。
     *
     * - SearchTerm 走 HttpUrl builder 自动 URL 编码（中文/空格/&/= 均安全），禁止手拼 query。
     * - Recursive=true + 不带 ParentId：跨整个媒体库搜索。
     * - IncludeItemTypes 限定可播放/可浏览的四种类型；排序不传 SortBy（服务器 relevance 即首版权威序）。
     * - Token 红线（ADR-026）：只走 [authenticatedHeaders] 的 X-Emby-Token Header，绝不进 URL。
     */
    suspend fun searchItems(
        token: String,
        userId: String,
        searchTerm: String,
        page: PageRequest,
    ): EmbyQueryResultDto<EmbyBaseItemDto> {
        val url = buildUrl(
            path = "/Users/$userId/Items",
            query = mapOf(
                "SearchTerm" to searchTerm,
                "Recursive" to "true",
                "IncludeItemTypes" to "Movie,Series,Episode,Video",
                "StartIndex" to page.offset.toString(),
                "Limit" to page.limit.toString(),
                "Fields" to SEARCH_FIELDS,
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
     * 播放信息：POST /emby/Items/{itemId}/PlaybackInfo?UserId=...（官方 MediaInfoService POST contract）。
     *
     * 无转码红线（Phase 1B-2.1）：协商参数全部走 typed JSON body
     * （EnableDirectPlay=false / EnableDirectStream=true / EnableTranscoding=false），
     * 只询问服务端能否 Direct Stream；绝不申请转码会话。
     * Token 红线：Token 不进 URL query、不进 JSON body，只走请求头。
     */
    suspend fun getPlaybackInfo(
        token: String,
        userId: String,
        itemId: String,
        startTimeTicks: Long?,
        maxStreamingBitrate: Long?,
    ): EmbyPlaybackInfoDto {
        val request = EmbyPlaybackInfoRequestDto(
            userId = userId,
            startTimeTicks = startTimeTicks?.takeIf { it > 0 },
            maxStreamingBitrate = maxStreamingBitrate?.takeIf { it > 0 },
        )
        val url = buildUrlWithSegments(
            path = "/Items",
            segments = listOf(itemId, "PlaybackInfo"),
            query = mapOf("UserId" to userId),
        )
        return apiClient.postJson(
            url = url,
            headers = authenticatedHeaders(token, userId),
            jsonBody = requestJson.encodeToString(request),
        )
    }
    /**
     * 无转码 Direct Stream 播放地址：
     * GET /emby/Videos/{itemId}/stream.{container}?MediaSourceId=...&PlaySessionId=...&static=true
     *
     * MediaSourceId 与 PlaySessionId 是 stream 请求的必备参数（static=true 表示 Direct Stream），
     * 二者必须始终存在——缺了只能说明上游响应损坏，调用方必须先校验，禁止生成残缺 URL。
     * 红线：鉴权只走 Header（X-Emby-Token），禁止任何 Token 进 URL（ADR-026）。
     */
    fun directStreamUrl(
        itemId: String,
        container: String,
        mediaSourceId: String,
        playSessionId: String,
    ): String {
        val builder = endpointResolver.endpoint("/Videos").toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .addPathSegment("stream.$container")
            .addQueryParameter("MediaSourceId", mediaSourceId)
            .addQueryParameter("PlaySessionId", playSessionId)
            .addQueryParameter("static", "true")
        return builder.build().toString()
    }

    /**
     * 图片（海报/缩略图/背景图）地址：GET /emby/Items/{itemId}/Images/{type}。
     *
     * 红线（ADR-026）：URL 永远不含 Token（鉴权由图片加载器注入 Header，见 app 层
     * EmbyImageAuthInterceptor）；tag 是图片内容哈希（缓存键用途），不是凭据。
     * maxWidth/quality 由服务端缩放，节省流量。
     */
    fun imageUrl(itemId: String, type: EmbyImageType, tag: String?, maxWidth: Int, quality: Int = 85): String =
        buildUrlWithSegments(
            path = "/Items",
            segments = listOf(itemId, "Images", type.wireName),
            query = buildMap {
                tag?.takeIf(String::isNotBlank)?.let { put("tag", it) }
                put("maxWidth", maxWidth.toString())
                put("quality", quality.toString())
            },
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
         * 浏览列表 Fields（官方 Fields 枚举值）。
         * Phase 1C-2 扩展排序/发现字段：DateCreated/CriticRating/PremiereDate/
         * OfficialRating/Size/Bitrate（服务器按需返回，DTO 缺失可空）。
         */
        const val LIBRARY_FIELDS =
            "PrimaryImageAspectRatio,SortName,Path,DateCreated,CriticRating,PremiereDate,OfficialRating,Size,Bitrate"

        /** 搜索结果列表所需字段（官方 Fields 枚举值；海报/年份/评分/简介供结果卡渲染）。 */
        const val SEARCH_FIELDS = "PrimaryImageAspectRatio,SortName,Path,ProductionYear,CommunityRating,Overview"
        /**
         * 请求体序列化：encodeDefaults=true 保证 DeviceProfile 等默认值字段全部输出
         * （官方 server 期望完整字段）；explicitNulls=false 省略未设置的 null 字段。
         */
        private val requestJson = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}
