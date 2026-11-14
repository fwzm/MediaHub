package com.mediahub.metadata

import com.mediahub.metadata.match.ManualMatchService
import com.mediahub.metadata.ratings.MdbListRatingsSource
import com.mediahub.metadata.ratings.RatingsService
import com.mediahub.metadata.tmdb.TmdbMetadataProvider

/**
 * 按配置装配好的刮削引擎（UI 层唯一入口）。
 *
 * 缺省密钥时 [available] 为 false，[manualMatch] 的 provider 列表为空：
 * 搜索立即返回空结果且不发起任何网络请求；[ratings] 仍可能返回 TMDB 自带评分
 * （评分来自已获取的详情，不需要额外网络）。
 */
class MetadataEngine(
    val available: Boolean,
    val manualMatch: ManualMatchService,
    val ratings: RatingsService,
    val registry: MetadataRegistry,
) {

    companion object {

        fun create(
            config: MetadataRemoteConfig,
            http: MetadataHttpClient = OkHttpMetadataHttpClient(),
        ): MetadataEngine {
            val providers = if (config.hasTmdb) {
                listOf(
                    TmdbMetadataProvider.create(
                        apiKey = config.tmdbApiKey,
                        baseUrl = config.tmdbBaseUrl,
                        language = config.tmdbLanguage,
                        http = http,
                    ),
                )
            } else {
                emptyList()
            }
            val sources = if (config.hasMdbList) {
                listOf(
                    MdbListRatingsSource(
                        http = http,
                        apiKey = config.mdbListApiKey,
                        baseUrl = config.mdbListBaseUrl,
                    ),
                )
            } else {
                emptyList()
            }
            return MetadataEngine(
                available = providers.isNotEmpty(),
                manualMatch = ManualMatchService(providers),
                ratings = RatingsService(sources),
                registry = DefaultMetadataRegistry(providers),
            )
        }
    }
}
