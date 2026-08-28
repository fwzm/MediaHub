package com.mediahub.provider.emby.mapper

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/**
 * Emby 时间字符串（DateCreated / PremiereDate）解析（Phase 1C-2）。
 *
 * Emby 返回形如 "2021-03-04T00:00:00.0000000Z"（小数秒 0-7 位不等），
 * appendInstant 接受 0-9 位小数秒，解析失败返回 null（缺失字段不得炸整页）。
 */
internal object EmbyDateTimes {

    private val formatter = DateTimeFormatterBuilder().appendInstant().toFormatter()

    fun parseEpochMs(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return runCatching { formatter.parse(raw, Instant::from).toEpochMilli() }.getOrNull()
    }
}
