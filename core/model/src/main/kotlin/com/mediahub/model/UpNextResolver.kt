package com.mediahub.model

/**
 * 「下一集」决策（A3 播完自动连播）：纯逻辑，无 IO、无协程、无 Android API。
 *
 * 规则（每条都有单测钉住）：
 * 1. 季号 / 集号 <= 0 或缺失视为「编号未知」，排序时**永远排在已知编号之后**——
 *    绝不允许 0 / null 把特别篇或残缺元数据顶到第一集之前。
 * 2. 同季优先：当前集的下一集若存在就直接用它，否则跨到下一季的第一集。
 * 3. 跨季顺序由 [followingSeasons] 决定：当前季能在列表里定位就按排序位置取后面的季；
 *    定位不到时才退回「季号更大」的比较（未知季号无法比较，不参与）。
 * 4. 本对象不判断观看状态、不判断时长、不访问 Provider：取数与播放由调用方负责。
 */
object UpNextResolver {

    /** 编号是否可用：null 与 <= 0 都算未知（Emby/Jellyfin 的特别篇常给 0）。 */
    fun isKnownNumber(value: Int?): Boolean = value != null && value > 0

    /** 按季号升序；季号未知的季排在最后，同号按 id 稳定排序。 */
    fun orderedSeasons(seasons: List<Season>): List<Season> = seasons.sortedWith(seasonOrder)

    /** 按集号升序（同号再看 indexNumber，最后看 id）；集号未知的集排在最后。 */
    fun orderedEpisodes(episodes: List<Episode>): List<Episode> = episodes.sortedWith(episodeOrder)

    /**
     * 同一季内的下一集。
     *
     * - 当前集能在列表里定位：返回排序位置上的下一条（编号未知的集不会被提前，只会留在末尾）；
     * - 定位不到但集号已知：返回集号更大的第一条（未知编号的集不参与比较）；
     * - 两者都不可用：null（调用方转跨季）。
     */
    fun nextInSeason(
        episodes: List<Episode>,
        currentEpisodeId: String?,
        currentEpisodeNumber: Int?,
    ): Episode? {
        if (episodes.isEmpty()) return null
        val ordered = orderedEpisodes(episodes)
        val index = currentEpisodeId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> ordered.indexOfFirst { it.id == id } }
            ?: -1
        if (index >= 0) return ordered.getOrNull(index + 1)
        val current = currentEpisodeNumber
        if (!isKnownNumber(current)) return null
        return ordered.firstOrNull { isKnownNumber(it.episodeNumber) && it.episodeNumber > current!! }
    }

    /** 一季的第一集（排序后的第一条）；空季返回 null。 */
    fun firstEpisode(episodes: List<Episode>): Episode? = orderedEpisodes(episodes).firstOrNull()

    /**
     * 当前季之后的季（按 [orderedSeasons] 的顺序）。
     *
     * - 当前季能定位：返回它后面的全部季（含季号未知、排序在末尾的季）；
     * - 当前季定位不到但季号已知：只返回季号更大的季（未知季号无法比较，保守跳过）；
     * - 其余情况：空列表（无法判断「后面」是哪里，宁可不连播也不猜）。
     */
    fun followingSeasons(
        seasons: List<Season>,
        currentSeasonId: String?,
        currentSeasonNumber: Int?,
    ): List<Season> {
        if (seasons.isEmpty()) return emptyList()
        val ordered = orderedSeasons(seasons)
        val index = currentSeasonId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> ordered.indexOfFirst { it.id == id } }
            ?: -1
        if (index >= 0) return ordered.drop(index + 1)
        val current = currentSeasonNumber
        if (!isKnownNumber(current)) return emptyList()
        return ordered.filter { season ->
            isKnownNumber(season.seasonNumber) && season.seasonNumber > current!!
        }
    }

    /** 给定季 id 查季号（用于把集映射回「第几季」展示）；查不到返回 null。 */
    fun seasonNumberFor(seasons: List<Season>, seasonId: String?): Int? {
        if (seasonId.isNullOrBlank()) return null
        return seasons.firstOrNull { it.id == seasonId }?.seasonNumber
    }

    /**
     * 一次算完「下一集」（展示层与单测用）：同季优先，其次按 [followingSeasons] 逐季找第一集。
     *
     * @param episodesBySeason 已取到的各季分集；缺失的季当作空季跳过。
     */
    fun resolveNext(
        seasons: List<Season>,
        episodesBySeason: Map<String, List<Episode>>,
        currentSeasonId: String?,
        currentSeasonNumber: Int?,
        currentEpisodeId: String?,
        currentEpisodeNumber: Int?,
    ): UpNextTarget? {
        val currentEpisodes = currentSeasonId?.let { episodesBySeason[it] }.orEmpty()
        val sameSeason = nextInSeason(currentEpisodes, currentEpisodeId, currentEpisodeNumber)
        if (sameSeason != null) {
            return UpNextTarget(
                seasonId = sameSeason.seasonId.ifBlank { currentSeasonId.orEmpty() },
                seasonNumber = currentSeasonNumber ?: seasonNumberFor(seasons, currentSeasonId),
                episode = sameSeason,
                crossSeason = false,
            )
        }
        for (season in followingSeasons(seasons, currentSeasonId, currentSeasonNumber)) {
            val candidate = firstEpisode(episodesBySeason[season.id].orEmpty()) ?: continue
            return UpNextTarget(
                seasonId = season.id,
                seasonNumber = season.seasonNumber,
                episode = candidate,
                crossSeason = true,
            )
        }
        return null
    }

    private val seasonOrder: Comparator<Season> = compareBy<Season>(
        { if (isKnownNumber(it.seasonNumber)) 0 else 1 },
        { if (isKnownNumber(it.seasonNumber)) it.seasonNumber else 0 },
        { it.id },
    )

    private val episodeOrder: Comparator<Episode> = compareBy<Episode>(
        { if (isKnownNumber(it.episodeNumber)) 0 else 1 },
        { if (isKnownNumber(it.episodeNumber)) it.episodeNumber else 0 },
        { if (isKnownNumber(it.indexNumber)) it.indexNumber else Int.MAX_VALUE },
        { it.id },
    )
}

/** 自动连播的目标条目：下一集 + 它所属的季。 */
data class UpNextTarget(
    val seasonId: String,
    val seasonNumber: Int?,
    val episode: Episode,
    /** true = 跨季（上一季最后一集播完），false = 同季下一集。 */
    val crossSeason: Boolean,
)
