package com.mediahub.core.trakt

/**
 * Trakt 配置注入点（条目 142/146）。
 *
 * 凭据由构建注入（Gradle 属性 → app BuildConfig → Hilt），也允许测试直接构造。
 * 任何字段缺失都只降级为"未配置"，**不抛异常、不发起请求**。
 */
data class TraktConfig(
    val clientId: String? = null,
    val clientSecret: String? = null,
    val accessToken: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 两次请求的最小间隔；Trakt 官方限额约 1000 次 / 5 分钟。 */
    val minRequestIntervalMs: Long = 350L,
) {
    val hasClientId: Boolean get() = !clientId.isNullOrBlank()
    val hasAccessToken: Boolean get() = !accessToken.isNullOrBlank()

    /** 读接口（搜索/日历）需要 client id；写接口（追更增删）还需要 access token。 */
    val canRead: Boolean get() = hasClientId
    val canWrite: Boolean get() = hasClientId && hasAccessToken

    companion object {
        const val DEFAULT_BASE_URL = "https://api.trakt.tv"
    }
}
