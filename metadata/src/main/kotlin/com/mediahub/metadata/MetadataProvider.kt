package com.mediahub.metadata

import com.mediahub.model.Person

/**
 * 媒体元数据刮削抽象（与"文件在哪"的 StorageProvider 完全分离，见 ADR-011）。
 * 后续实现：TMDB / Bangumi / 豆瓣 / TVDB 等。
 */
interface MetadataProvider {

    suspend fun search(query: String, year: Int? = null): List<MetadataResult>

    suspend fun detail(metadataId: String): MetadataDetail?

    val source: MetadataSource
}

enum class MetadataSource(val label: String) {
    TMDB("TMDB"),
    BANGUMI("Bangumi"),
    DOUBAN("豆瓣"),
    TVDB("TVDB"),
}

data class MetadataResult(
    val source: MetadataSource,
    val id: String,
    val title: String,
    val year: Int? = null,
    val posterUrl: String? = null,
    val overview: String? = null,
)

data class MetadataDetail(
    val source: MetadataSource,
    val id: String,
    val title: String,
    val overview: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val people: List<Person> = emptyList(),
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val rating: Double? = null,
)

/** 刮削源注册表（按源选择实现）。 */
interface MetadataRegistry {
    fun providerFor(source: MetadataSource): MetadataProvider?
}
