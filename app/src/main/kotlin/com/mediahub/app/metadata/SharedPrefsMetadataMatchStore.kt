package com.mediahub.app.metadata

import android.content.Context
import com.mediahub.metadata.MetadataMediaType
import com.mediahub.metadata.MetadataSource
import com.mediahub.metadata.match.MetadataMatch
import com.mediahub.metadata.match.MetadataMatchStore
import org.json.JSONObject

/**
 * [MetadataMatchStore] 的 SharedPreferences 实现。
 *
 * 刻意不建表：匹配结果只是「该条目的刮削指向」，不参与媒体库同步、不覆盖 Provider 数据，
 * 因此用轻量键值存储即可，避免 Room 迁移。损坏/缺失一律按「未匹配」处理，不向 UI 抛异常。
 */
class SharedPrefsMetadataMatchStore(context: Context) : MetadataMatchStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun get(serverId: String, itemId: String): MetadataMatch? {
        val raw = prefs.getString(key(serverId, itemId), null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    override suspend fun put(serverId: String, itemId: String, match: MetadataMatch) {
        runCatching {
            prefs.edit().putString(key(serverId, itemId), encode(match)).apply()
        }
    }

    private fun key(serverId: String, itemId: String): String = "$serverId|$itemId"

    private fun encode(match: MetadataMatch): String = JSONObject().apply {
        put(FIELD_SOURCE, match.source.name)
        put(FIELD_ID, match.metadataId)
        put(FIELD_TITLE, match.title)
        match.year?.let { put(FIELD_YEAR, it) }
        match.posterUrl?.let { put(FIELD_POSTER, it) }
        match.mediaType?.let { put(FIELD_MEDIA_TYPE, it.name) }
    }.toString()

    private fun decode(raw: String): MetadataMatch {
        val json = JSONObject(raw)
        val source = MetadataSource.valueOf(json.getString(FIELD_SOURCE))
        return MetadataMatch(
            source = source,
            metadataId = json.getString(FIELD_ID),
            title = json.getString(FIELD_TITLE),
            year = if (json.has(FIELD_YEAR)) json.getInt(FIELD_YEAR) else null,
            posterUrl = if (json.has(FIELD_POSTER)) json.getString(FIELD_POSTER) else null,
            mediaType = if (json.has(FIELD_MEDIA_TYPE)) {
                MetadataMediaType.valueOf(json.getString(FIELD_MEDIA_TYPE))
            } else {
                null
            },
        )
    }

    private companion object {
        const val PREFS_NAME = "metadata_matches"
        const val FIELD_SOURCE = "source"
        const val FIELD_ID = "metadataId"
        const val FIELD_TITLE = "title"
        const val FIELD_YEAR = "year"
        const val FIELD_POSTER = "posterUrl"
        const val FIELD_MEDIA_TYPE = "mediaType"
    }
}
