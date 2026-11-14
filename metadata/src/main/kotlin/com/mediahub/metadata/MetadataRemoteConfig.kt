package com.mediahub.metadata

/**
 * 外部刮削服务的可注入配置（本轮 UI 接线的配置点）。
 *
 * 密钥只经构造注入，**仓库内不保存任何真实密钥**；空字符串表示未配置，
 * 调用方必须降级为「功能不可用 + 零外部请求」而不是崩溃。
 */
data class MetadataRemoteConfig(
    val tmdbApiKey: String = "",
    val mdbListApiKey: String = "",
    val tmdbBaseUrl: String = DEFAULT_TMDB_BASE_URL,
    val tmdbLanguage: String = DEFAULT_LANGUAGE,
    val mdbListBaseUrl: String = DEFAULT_MDBLIST_BASE_URL,
) {
    val hasTmdb: Boolean get() = tmdbApiKey.isNotBlank()

    val hasMdbList: Boolean get() = mdbListApiKey.isNotBlank()

    companion object {
        const val DEFAULT_TMDB_BASE_URL: String = "https://api.themoviedb.org/3"
        const val DEFAULT_MDBLIST_BASE_URL: String = "https://api.mdblist.com"
        const val DEFAULT_LANGUAGE: String = "zh-CN"
    }
}
