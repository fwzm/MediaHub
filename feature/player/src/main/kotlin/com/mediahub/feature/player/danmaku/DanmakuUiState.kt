package com.mediahub.feature.player.danmaku

import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuEpisode
import com.mediahub.core.danmaku.DanmakuSnapshot

/** 播放页弹幕 UI 状态。评论列表始终按时间排序，由数据层保证。 */
data class DanmakuUiState(
    val enabled: Boolean = true,
    val onlineEnabled: Boolean = false,
    val comments: List<DanmakuComment> = emptyList(),
    val loading: Boolean = false,
    val fromCache: Boolean = false,
    val sourceLabel: String? = null,
    val notice: String? = null,
    val searchQuery: String = "",
    val searchResults: List<DanmakuEpisode> = emptyList(),
    val searching: Boolean = false,
    val opacity: Float = DanmakuDefaults.OPACITY,
    val strokeWidthDp: Float = DanmakuDefaults.STROKE_WIDTH_DP,
) {
    val count: Int get() = comments.size

    val countLabel: String get() = DanmakuCountFormatter.format(count)
}

object DanmakuDefaults {
    const val OPACITY: Float = 0.85f
    const val MIN_OPACITY: Float = 0.1f
    const val STROKE_WIDTH_DP: Float = 1.5f
    const val MAX_STROKE_WIDTH_DP: Float = 6f
    const val MAX_TEXT_LENGTH: Int = 80
}

object DanmakuCountFormatter {
    /** 无本地化歧义的分组格式：1234 -> "1,234"。 */
    fun format(count: Int): String {
        val digits = count.coerceAtLeast(0).toString()
        return digits.reversed()
            .chunked(3)
            .joinToString(",")
            .reversed()
    }
}

enum class DanmakuTextError { EMPTY, TOO_LONG }

data class DanmakuTextValidation(
    val normalized: String,
    val error: DanmakuTextError? = null,
) {
    val canSend: Boolean get() = error == null
}

/** 发送前清洗与校验：空内容与超长内容不进入时间轴（条目 96）。 */
object DanmakuTextPolicy {
    fun validate(raw: String): DanmakuTextValidation {
        val normalized = raw
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim()
        if (normalized.isEmpty()) {
            return DanmakuTextValidation(normalized = normalized, error = DanmakuTextError.EMPTY)
        }
        if (normalized.length > DanmakuDefaults.MAX_TEXT_LENGTH) {
            return DanmakuTextValidation(normalized = normalized, error = DanmakuTextError.TOO_LONG)
        }
        return DanmakuTextValidation(normalized = normalized)
    }
}

fun DanmakuUiState.withSnapshot(snapshot: DanmakuSnapshot): DanmakuUiState = copy(
    comments = snapshot.comments,
    loading = false,
    fromCache = snapshot.fromCache,
    sourceLabel = snapshot.sourceLabel,
    notice = snapshot.error,
)

fun DanmakuUiState.withSearchResults(
    results: List<DanmakuEpisode>,
    error: String? = null,
): DanmakuUiState = copy(
    searchResults = results,
    searching = false,
    notice = error ?: notice,
)

fun DanmakuUiState.withOpacity(value: Float): DanmakuUiState = copy(
    opacity = value.coerceIn(DanmakuDefaults.MIN_OPACITY, 1f),
)

fun DanmakuUiState.withStrokeWidth(valueDp: Float): DanmakuUiState = copy(
    strokeWidthDp = valueDp.coerceIn(0f, DanmakuDefaults.MAX_STROKE_WIDTH_DP),
)
