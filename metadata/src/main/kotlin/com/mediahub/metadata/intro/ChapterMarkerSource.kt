package com.mediahub.metadata.intro

/** 本地章节（绝对毫秒），来自容器元数据或服务端章节列表。 */
data class MediaChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * 本地章节来源：从章节标题识别片头/片尾。
 *
 * 优点：时间戳绝对、离线可用，不依赖媒体时长，天然满足条目 138；
 * 缺点：仅当容器带章节时有效，因此只作为多来源之一参与合并。
 */
class ChapterMarkerSource(
    private val chapters: List<MediaChapter>,
) : IntroMarkerSource {

    override val name: String = "本地章节"
    override val priority: Int = 10

    override suspend fun markers(query: MarkerQuery): List<MediaMarker> =
        chapters.mapNotNull { chapter ->
            val type = typeOf(chapter.title) ?: return@mapNotNull null
            MediaMarker(
                type = type,
                startMs = chapter.startMs,
                endMs = chapter.endMs,
                source = name,
                confidence = 0.4,
                contributors = setOf(name),
            )
        }

    private fun typeOf(title: String): MarkerType? {
        val normalized = title.trim()
        if (INTRO.containsMatchIn(normalized) || normalized.contains("片头")) return MarkerType.INTRO
        if (CREDITS.containsMatchIn(normalized) || normalized.contains("片尾")) return MarkerType.CREDITS
        return null
    }

    private companion object {
        val INTRO = Regex("\\b(intro|opening|op)\\b", RegexOption.IGNORE_CASE)
        val CREDITS = Regex("\\b(credits?|ending|outro)\\b", RegexOption.IGNORE_CASE)
    }
}
