package com.mediahub.feature.player.danmaku

import com.mediahub.core.danmaku.DanmakuComment
import com.mediahub.core.danmaku.DanmakuMode
import kotlin.math.floor

/**
 * 弹幕可用区域：顶部预留给 OSD/标题栏，底部预留给字幕与进度条控制层。
 * 弹幕轨道只在 [topPx, bottomPx] 内分配，从布局上保证不遮挡 OSD 和字幕（条目 74）。
 */
data class DanmakuSafeArea(
    val topPx: Float,
    val bottomPx: Float,
) {
    val heightPx: Float
        get() = (bottomPx - topPx).coerceAtLeast(0f)

    fun contains(yPx: Float): Boolean = yPx >= topPx && yPx <= bottomPx

    companion object {
        const val TOP_BAR_FRACTION: Float = 0.18f
        const val BOTTOM_RESERVED_FRACTION: Float = 0.30f

        fun forPlayer(
            viewportHeightPx: Float,
            topBarFraction: Float = TOP_BAR_FRACTION,
            bottomReservedFraction: Float = BOTTOM_RESERVED_FRACTION,
        ): DanmakuSafeArea {
            val height = viewportHeightPx.coerceAtLeast(0f)
            val top = height * topBarFraction.coerceIn(0f, 0.5f)
            val bottom = height * (1f - bottomReservedFraction.coerceIn(0f, 0.8f))
            return if (bottom > top) DanmakuSafeArea(top, bottom) else DanmakuSafeArea(0f, 0f)
        }
    }
}

data class DanmakuLayoutConfig(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val safeTopPx: Float = 0f,
    val safeBottomPx: Float = viewportHeightPx,
    val laneHeightPx: Float = 48f,
    val scrollSpeedPxPerSecond: Float = 200f,
    val staticDurationMs: Long = 4_000L,
    val maxLanes: Int = 12,
    val maxActiveVisuals: Int = 120,
)

data class DanmakuVisual(
    val text: String,
    val xPx: Float,
    val baselineYPx: Float,
    val textSizePx: Float,
    val colorArgb: Int,
    val alpha: Float = 1f,
    val commentId: String = "",
)

data class DanmakuPreparedStats(
    val total: Int = 0,
    val placed: Int = 0,
    val dropped: Int = 0,
)

/**
 * 纯 Kotlin 轨道布局：不依赖 Android/Compose，可在 JVM 单测中验证时间轴、避让与安全区。
 *
 * 同屏不重叠策略：每条弹幕占用一条轨道，轨道在弹幕完全离场（或静态展示结束）前不再分配。
 * 轨道不足时丢弃新弹幕，而不是叠字。
 */
class DanmakuLayoutEngine(
    private val config: DanmakuLayoutConfig,
    private val measureText: (text: String, textSizePx: Float) -> Float,
    private val textSizeForSp: (fontSizeSp: Int) -> Float = { it * 1.6f },
) {

    private data class Placement(
        val comment: DanmakuComment,
        val lane: Int,
        val startMs: Long,
        val endMs: Long,
        val widthPx: Float,
        val textSizePx: Float,
        val static: Boolean,
    )

    private val placements = ArrayList<Placement>()
    private val laneCount: Int = run {
        if (config.laneHeightPx <= 0f || config.viewportWidthPx <= 0f) return@run 0
        val available = config.safeBottomPx - config.safeTopPx
        if (available <= 0f) return@run 0
        floor(available / config.laneHeightPx).toInt().coerceIn(0, config.maxLanes)
    }
    private var maxDurationMs: Long = 0L

    var stats: DanmakuPreparedStats = DanmakuPreparedStats()
        private set

    fun prepare(comments: List<DanmakuComment>): DanmakuPreparedStats {
        placements.clear()
        maxDurationMs = 0L
        val valid = comments.asSequence()
            .filter { it.text.isNotBlank() && it.timeMs >= 0L }
            .sortedBy { it.timeMs }
            .toList()

        if (laneCount == 0 || config.scrollSpeedPxPerSecond <= 0f) {
            stats = DanmakuPreparedStats(total = comments.size, placed = 0, dropped = comments.size)
            return stats
        }

        val laneFreeAt = LongArray(laneCount) { Long.MIN_VALUE }
        val seenIds = HashSet<String>(valid.size)
        valid.forEach { comment ->
            if (!seenIds.add(comment.id)) return@forEach
            val textSize = textSizeForSp(comment.fontSizeSp).coerceIn(8f, 96f)
            val width = measureText(comment.text, textSize).coerceIn(1f, config.viewportWidthPx * 4f)
            val static = comment.mode != DanmakuMode.SCROLL
            val duration = if (static) {
                config.staticDurationMs.coerceAtLeast(1L)
            } else {
                (((config.viewportWidthPx + width) / config.scrollSpeedPxPerSecond) * 1000f)
                    .toLong()
                    .coerceAtLeast(1L)
            }
            val lane = laneFreeAt.indexOfFirst { it <= comment.timeMs }
            if (lane < 0) return@forEach

            val endMs = comment.timeMs + duration
            laneFreeAt[lane] = endMs
            placements += Placement(
                comment = comment,
                lane = lane,
                startMs = comment.timeMs,
                endMs = endMs,
                widthPx = width,
                textSizePx = textSize,
                static = static,
            )
            if (duration > maxDurationMs) maxDurationMs = duration
        }

        placements.sortBy { it.startMs }
        stats = DanmakuPreparedStats(
            total = comments.size,
            placed = placements.size,
            dropped = comments.size - placements.size,
        )
        return stats
    }

    fun frameAt(positionMs: Long): List<DanmakuVisual> {
        if (placements.isEmpty() || positionMs < 0L) return emptyList()
        val endExclusive = upperBound(positionMs)
        if (endExclusive == 0) return emptyList()

        val visuals = ArrayList<DanmakuVisual>()
        for (index in endExclusive - 1 downTo 0) {
            val placement = placements[index]
            if (positionMs - placement.startMs > maxDurationMs) break
            if (positionMs > placement.endMs) continue

            val x = if (placement.static) {
                ((config.viewportWidthPx - placement.widthPx) / 2f).coerceAtLeast(0f)
            } else {
                config.viewportWidthPx -
                    (positionMs - placement.startMs) / 1000f * config.scrollSpeedPxPerSecond
            }
            if (!placement.static && x + placement.widthPx < 0f) continue

            val laneIndex = if (placement.comment.mode == DanmakuMode.BOTTOM) {
                laneCount - 1 - placement.lane
            } else {
                placement.lane
            }
            val desiredBaseline =
                config.safeTopPx + laneIndex * config.laneHeightPx + placement.textSizePx * 0.8f
            val baseline = desiredBaseline.coerceIn(
                config.safeTopPx + placement.textSizePx * 0.8f,
                config.safeBottomPx - 1f,
            )
            val color = placement.comment.colorArgb
            visuals += DanmakuVisual(
                text = placement.comment.text,
                xPx = x,
                baselineYPx = baseline,
                textSizePx = placement.textSizePx,
                colorArgb = color,
                alpha = ((color ushr 24) and 0xFF) / 255f,
                commentId = placement.comment.id,
            )
            if (visuals.size >= config.maxActiveVisuals) break
        }
        return visuals
    }

    fun visibleCount(positionMs: Long): Int = frameAt(positionMs).size

    /** placements 按 startMs 升序，返回第一个 startMs > positionMs 的下标。 */
    private fun upperBound(positionMs: Long): Int {
        var low = 0
        var high = placements.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (placements[mid].startMs <= positionMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }
}
