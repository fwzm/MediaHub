package com.mediahub.provider.jellyfin.api

import com.mediahub.core.logging.Logger
import com.mediahub.core.network.ApiClient
import com.mediahub.model.PageRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Jellyfin HTTP 协议封装（Phase 1G-A：认证与会话；Phase 1G-B：Library/Detail/Search/Artwork）。
 *
 * - URL/path 构造集中在 [JellyfinEndpointResolver]（保留反代子路径，无协议前缀知识）；
 * - 认证只走标准 `Authorization: MediaBrowser …` 头（[JellyfinAuthorizationHeaderBuilder]），
 *   **Token 绝不进 URL/query**（ADR-026 同款红线）；X-Emby-* 与 X-MediaBrowser-* legacy 头不使用；
 * - 非 2xx 抛 ApiException；解析失败抛序列化异常；网络错误抛 IOException——由上层
 *   （JellyfinAuthProvider / JellyfinProviderSupport.mapError）映射为业务错误。
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

    // ---- Phase 1G-B：Library / Detail / Search / Artwork ----
    // 现代 Jellyfin 端点（ADR-039 协议证据先行）：/UserViews、/Items、/Items/{id}。
    // /Users/{uid}/Views、/Users/{uid}/Items 系列为官方 [Obsolete] legacy route，不使用。

    /** 顶层媒体库 Views：GET /UserViews?UserId=…（现代 UserViewsController 端点）。 */
    suspend fun getUserViews(token: String, userId: String): JellyfinQueryResultDto<JellyfinItemDto> {
        val url = endpointResolver.endpoint("/UserViews").toHttpUrl().newBuilder()
            .addQueryParameter("UserId", userId)
            .build()
            .toString()
        return apiClient.get(url, authenticatedHeaders(token))
    }

    /**
     * 条目查询（浏览 / 季 / 集 / 搜索共用一份 wire，语义由参数表达）：
     * GET /Items?UserId=…（现代 ItemsController 端点；legacy /Users/{uid}/Items 不使用）。
     *
     * - 浏览（[parentId] 非空、[searchTerm] 空）：**不携带 Recursive**（默认 false，
     *   只取直接子级，ADR-039 红线）；SortBy=SortName 保证跨页稳定顺序。
     * - 季/集：[includeItemTypes] 锁定类型（"Season"/"Episode"）+ SortBy=IndexNumber。
     * - 搜索（[searchTerm] 非空）：Recursive=true + IncludeItemTypes 四类 + relevance 排序
     *   （服务端对含 SearchTerm 的查询主动 prepend SearchScore DESC，与全局搜索语义一致）。
     * - Fields 只含官方 ItemFields 枚举值（ProviderIds/ParentId/SortName 等——
     *   ProductionYear/CommunityRating 为默认渲染属性， UserData 走独立
     *   EnableUserData 参数，**均不进 Fields**，ADR-039 review 修正）。
     * - EnableUserData=true（进度/收藏语义必备）+ EnableTotalRecordCount=true
     *   （PagedResult 分页数学依赖）显式发送，不依赖服务端默认值。
     * - Token 红线：只走 [authenticatedHeaders] 的 Authorization 头，绝不进 URL。
     */
    suspend fun getUserItems(
        token: String,
        userId: String,
        parentId: String? = null,
        page: PageRequest,
        includeItemTypes: String? = null,
        searchTerm: String? = null,
        recursive: Boolean = false,
        sortBy: String? = "SortName",
        fields: String = LIST_FIELDS,
    ): JellyfinQueryResultDto<JellyfinItemDto> {
        val url = buildUrl(
            path = "/Items",
            query = buildMap {
                put("UserId", userId)
                parentId?.let { put("ParentId", it) }
                searchTerm?.let { put("SearchTerm", it) }
                if (recursive) put("Recursive", "true")
                includeItemTypes?.let { put("IncludeItemTypes", it) }
                sortBy?.let {
                    put("SortBy", it)
                    put("SortOrder", "Ascending")
                }
                put("StartIndex", page.offset.toString())
                put("Limit", page.limit.toString())
                put("Fields", fields)
                put("EnableUserData", "true")
                put("EnableTotalRecordCount", "true")
            },
        )
        return apiClient.get(url, authenticatedHeaders(token))
    }

    /** 条目详情（全量端点）：GET /Items/{itemId}?UserId=…（现代端点，无需 Fields 裁剪）。 */
    suspend fun getItemDetail(token: String, userId: String, itemId: String): JellyfinItemDto {
        val url = endpointResolver.endpoint("/Items").toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .addQueryParameter("UserId", userId)
            .build()
            .toString()
        return apiClient.get(url, authenticatedHeaders(token))
    }

    /**
     * 图片地址：GET /Items/{itemId}/Images/{type}?tag&maxWidth&quality。
     *
     * 红线（ADR-026/039）：**URL 永不含 Token/api_key**——鉴权由 app 层
     * ProviderImageAuthContributor 以标准 Authorization Header 注入（Agent A）。
     * 全程 HttpUrl builder（itemId/tag 经编码，禁手拼）。
     */
    fun imageUrl(
        itemId: String,
        imageType: JellyfinImageType,
        tag: String?,
        maxWidth: Int,
        quality: Int = 85,
    ): String {
        val builder = endpointResolver.endpoint("/Items").toHttpUrl().newBuilder()
            .addPathSegment(itemId)
            .addPathSegment("Images")
            .addPathSegment(imageType.wireName)
        tag?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("tag", it) }
        builder.addQueryParameter("maxWidth", maxWidth.toString())
        builder.addQueryParameter("quality", quality.toString())
        return builder.build().toString()
    }

    /** 匿名客户端身份（登录前 / 公共探针）：标准 Authorization，无 Token。 */
    fun identityHeaders(): Map<String, String> =
        mapOf(HEADER_NAME to authHeaderBuilder.build())

    /** 已认证请求：标准 Authorization 头内嵌 Token。 */
    fun authenticatedHeaders(token: String): Map<String, String> =
        mapOf(HEADER_NAME to authHeaderBuilder.build(token))

    private fun buildUrl(path: String, query: Map<String, String>): String {
        val builder = endpointResolver.endpoint(path).toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private companion object {
        const val HEADER_NAME = "Authorization"

        /**
         * 列表查询 Fields——**仅官方 ItemFields 枚举值**（ADR-039 review 修正）：
         * ProductionYear/CommunityRating 为默认渲染属性、UserData 走独立 EnableUserData
         * 参数，均不进 Fields（v10.9.0 ItemFields enum 核对）。
         */
        const val LIST_FIELDS =
            "ParentId,SortName,PrimaryImageAspectRatio,Overview,Genres,ProviderIds"

        /** 请求体序列化：explicitNulls=false 省略未设置字段；ignoreUnknownKeys 兼容版本差异。 */
        private val requestJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
