package com.mediahub.metadata.intro

import com.mediahub.metadata.MetadataException
import kotlin.math.abs

/**
 * 多来源片头片尾合并（条目 127）：
 * - 同类型且时间接近/重叠的标记合并为一条，取优先级最高来源为主来源；
 * - `contributors` 保留全部来源，便于 UI 展示；
 * - 标记非法（负起点、终点不大于起点）或超出已知时长时丢弃；
 * - 未知时长时原样保留绝对时间戳，**不做百分比推算**（条目 138）。
 */
class IntroMarkerService(
    private val sources: List<IntroMarkerSource>,
) {

    suspend fun resolve(query: MarkerQuery): MarkerResolution {
        val collected = mutableListOf<MediaMarker>()
        val failures = mutableListOf<MarkerFailure>()
        for (source in sources) {
            try {
                collected += source.markers(query).mapNotNull { it.sanitized(query.runtimeMs) }
            } catch (e: MetadataException) {
                failures += MarkerFailure(source.name, e.statusCode, e.message.orEmpty())
            }
        }
        return MarkerResolution(markers = merge(collected), failures = failures)
    }

    private fun MediaMarker.sanitized(runtimeMs: Long?): MediaMarker? {
        if (startMs < 0) return null
        if (endMs != null && endMs <= startMs) return null
        if (runtimeMs != null && startMs >= runtimeMs) return null
        return copy(endMs = runtimeMs?.let { endMs?.coerceAtMost(it) } ?: endMs)
    }

    private fun merge(markers: List<MediaMarker>): List<MediaMarker> {
        val priorities = sources.associate { it.name to it.priority }
        val merged = mutableListOf<MediaMarker>()
        markers.sortedWith(compareBy({ it.type.ordinal }, { it.startMs })).forEach { candidate ->
            val index = merged.indexOfLast { it.type == candidate.type && it.sameSegment(candidate) }
            if (index < 0) {
                merged += candidate
            } else {
                val existing = merged[index]
                val primary = if (priorityOf(candidate, priorities) >= priorityOf(existing, priorities)) {
                    candidate
                } else {
                    existing
                }
                merged[index] = primary.copy(
                    startMs = minOf(existing.startMs, candidate.startMs),
                    endMs = latestEnd(existing, candidate),
                    contributors = existing.contributors + candidate.contributors,
                    confidence = maxOf(existing.confidence, candidate.confidence),
                )
            }
        }
        return merged.sortedBy { it.startMs }
    }

    private fun MediaMarker.sameSegment(other: MediaMarker): Boolean {
        val existingEnd = endMs ?: (startMs + START_TOLERANCE_MS)
        val otherEnd = other.endMs ?: (other.startMs + START_TOLERANCE_MS)
        val overlaps = startMs <= otherEnd && other.startMs <= existingEnd
        return overlaps || abs(startMs - other.startMs) <= START_TOLERANCE_MS
    }

    private fun latestEnd(a: MediaMarker, b: MediaMarker): Long? {
        val ends = listOfNotNull(a.endMs, b.endMs)
        return ends.maxOrNull()
    }

    private fun priorityOf(marker: MediaMarker, priorities: Map<String, Int>): Int =
        priorities[marker.source] ?: 0

    private companion object {
        const val START_TOLERANCE_MS = 10_000L
    }
}
