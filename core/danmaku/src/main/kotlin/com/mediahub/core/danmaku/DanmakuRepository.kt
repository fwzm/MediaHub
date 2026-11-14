package com.mediahub.core.danmaku

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * 弹幕仓库：缓存优先、在线源可插拔、失败降级为本地弹幕。
 *
 * 约定：
 * - 任何网络/解析失败都不向上抛异常，写入 [DanmakuSnapshot.error]（条目 59）。
 * - 本地发送的弹幕持久化在独立缓存键下，重新加载时与在线弹幕合并，本地优先。
 * - 同一条目重复加载命中缓存，不重复打网（条目 16 的缓存要求）。
 */
class DanmakuRepository(
    private val cache: DanmakuCache,
    private val sources: List<DanmakuSource> = emptyList(),
    private val maxComments: Int = MAX_COMMENTS,
) {

    /** 每个条目最近一次在线结果，用于本地发送后立即合并显示。 */
    private val onlineMemory = ConcurrentHashMap<String, List<DanmakuComment>>()

    suspend fun load(query: DanmakuQuery): DanmakuSnapshot {
        val local = readLocal(query.itemKey)
        if (!query.onlineEnabled) {
            return DanmakuSnapshot(
                comments = local.sortedWith(ORDER),
                sourceLabel = "本地",
            )
        }

        val key = onlineKey(query.itemKey)
        if (!query.forceRefresh) {
            val cachedPayload = cache.read(key)
            if (cachedPayload != null) {
                val cached = DanmakuCodec.decode(cachedPayload)
                if (cached.isNotEmpty()) {
                    onlineMemory[query.itemKey] = cached
                    return DanmakuSnapshot(
                        comments = merge(local, cached),
                        fromCache = true,
                        sourceLabel = "缓存",
                    )
                }
            }
        }

        if (sources.isEmpty()) {
            return DanmakuSnapshot(
                comments = merge(local, onlineMemory[query.itemKey].orEmpty()),
                sourceLabel = "本地",
                error = "未配置在线弹幕源",
            )
        }

        val errors = ArrayList<String>(sources.size)
        sources.forEach { source ->
            try {
                val fetched = sanitize(source.fetch(query))
                if (fetched.isNotEmpty()) {
                    cache.write(key, DanmakuCodec.encode(fetched))
                    onlineMemory[query.itemKey] = fetched
                    return DanmakuSnapshot(
                        comments = merge(local, fetched),
                        sourceLabel = source.label,
                    )
                }
                errors += "${source.label}: 返回空数据"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                errors += "${source.label}: ${failure.message ?: "拉取失败"}"
            }
        }

        return DanmakuSnapshot(
            comments = merge(local, onlineMemory[query.itemKey].orEmpty()),
            sourceLabel = "本地",
            error = errors.joinToString("；").ifBlank { "弹幕加载失败" },
        )
    }

    /** 按搜索结果的剧集 id 取全片弹幕，并缓存。 */
    suspend fun loadEpisode(query: DanmakuQuery, episodeId: String): DanmakuSnapshot {
        val local = readLocal(query.itemKey)
        val key = "episode:$episodeId"
        if (!query.forceRefresh) {
            val cachedPayload = cache.read(key)
            if (cachedPayload != null) {
                val cached = DanmakuCodec.decode(cachedPayload)
                if (cached.isNotEmpty()) {
                    onlineMemory[query.itemKey] = cached
                    return DanmakuSnapshot(
                        comments = merge(local, cached),
                        fromCache = true,
                        sourceLabel = "缓存",
                    )
                }
            }
        }

        val source = sources.filterIsInstance<DanmakuEpisodeCommentSource>().firstOrNull()
            ?: return DanmakuSnapshot(
                comments = merge(local, onlineMemory[query.itemKey].orEmpty()),
                sourceLabel = "本地",
                error = "未配置在线弹幕源",
            )
        return try {
            val comments = sanitize(source.fetchEpisode(query.danDanPlay, episodeId))
            if (comments.isEmpty()) {
                DanmakuSnapshot(
                    comments = merge(local, onlineMemory[query.itemKey].orEmpty()),
                    sourceLabel = "本地",
                    error = "该剧集暂无弹幕",
                )
            } else {
                cache.write(key, DanmakuCodec.encode(comments))
                onlineMemory[query.itemKey] = comments
                DanmakuSnapshot(
                    comments = merge(local, comments),
                    sourceLabel = "DanDanPlay",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DanmakuSnapshot(
                comments = merge(local, onlineMemory[query.itemKey].orEmpty()),
                sourceLabel = "本地",
                error = failure.message ?: "弹幕加载失败",
            )
        }
    }

    suspend fun searchEpisodes(query: DanmakuQuery, keyword: String): DanmakuSearchResult {
        if (!query.onlineEnabled) return DanmakuSearchResult()
        val source = sources.filterIsInstance<DanmakuEpisodeSearch>().firstOrNull()
            ?: return DanmakuSearchResult(error = "当前弹幕源不支持搜索")
        return try {
            DanmakuSearchResult(episodes = source.searchEpisodes(query.danDanPlay, keyword))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DanmakuSearchResult(error = failure.message ?: "搜索失败")
        }
    }

    /** 发送本地弹幕：写入本地缓存并立即返回合并结果。 */
    suspend fun addLocalComment(itemKey: String, comment: DanmakuComment): DanmakuSnapshot {
        val cleaned = comment.copy(
            text = comment.text.replace('\n', ' ').replace('\t', ' ').trim()
                .take(DanmakuParser.MAX_TEXT_LENGTH),
            timeMs = comment.timeMs.coerceAtLeast(0L),
            source = DanmakuComment.SOURCE_LOCAL,
        )
        val existing = readLocal(itemKey).filterNot { it.id == cleaned.id }
        val updated = (existing + cleaned).sortedWith(ORDER)
        cache.write(localKey(itemKey), DanmakuCodec.encode(updated))
        return DanmakuSnapshot(
            comments = merge(updated, onlineMemory[itemKey].orEmpty()),
            sourceLabel = "本地",
        )
    }

    suspend fun localComments(itemKey: String): List<DanmakuComment> = readLocal(itemKey)

    private suspend fun readLocal(itemKey: String): List<DanmakuComment> {
        val payload = cache.read(localKey(itemKey)) ?: return emptyList()
        return DanmakuCodec.decode(payload)
    }

    private fun sanitize(comments: List<DanmakuComment>): List<DanmakuComment> {
        if (comments.isEmpty()) return emptyList()
        return comments.asSequence()
            .filter { it.text.isNotBlank() && it.timeMs >= 0 }
            .map {
                it.copy(
                    text = it.text.replace('\n', ' ').replace('\t', ' ').trim()
                        .take(DanmakuParser.MAX_TEXT_LENGTH),
                    fontSizeSp = it.fontSizeSp.coerceIn(12, 64),
                )
            }
            .distinctBy { it.id }
            .sortedWith(ORDER)
            .take(maxComments)
            .toList()
    }

    /** 本地弹幕优先，同 id 去重后按时间排序。 */
    private fun merge(
        local: List<DanmakuComment>,
        online: List<DanmakuComment>,
    ): List<DanmakuComment> =
        (local + online)
            .distinctBy { it.id }
            .sortedWith(ORDER)
            .take(maxComments)

    private fun localKey(itemKey: String): String = "local:$itemKey"

    private fun onlineKey(itemKey: String): String = "dandanplay:$itemKey"

    private companion object {
        const val MAX_COMMENTS = 20_000
        val ORDER: Comparator<DanmakuComment> =
            compareBy<DanmakuComment> { it.timeMs }.thenBy { it.id }
    }
}
