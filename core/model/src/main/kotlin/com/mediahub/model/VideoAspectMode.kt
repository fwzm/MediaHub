package com.mediahub.model

/**
 * 画面比例模式（条目 9）：用户可循环切换，按内容记忆。
 *
 * - [FIT]：保持视频原始比例完整显示（黑边）；
 * - [CROP]：保持比例填满容器，超出部分裁切；
 * - [RATIO_16_9] / [RATIO_4_3]：强制按固定比例完整显示。
 *
 * 记忆键为「serverId + itemId」，下次播放同一内容时恢复。
 */
enum class VideoAspectMode {
    FIT,
    CROP,
    RATIO_16_9,
    RATIO_4_3,
    ;

    /** 固定比例模式的目标比例；自适应/裁切模式返回 null（使用视频自身比例）。 */
    val fixedAspectRatio: Float?
        get() = when (this) {
            RATIO_16_9 -> 16f / 9f
            RATIO_4_3 -> 4f / 3f
            FIT, CROP -> null
        }

    /** 播放器内循环切换顺序。 */
    fun next(): VideoAspectMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

/**
 * 画面比例记忆的纯逻辑（序列化 + 上限淘汰），与 DataStore 解耦以便单测。
 *
 * 序列化格式：条目之间用 `\u001E`，字段之间用 `\u001F`，避免与常见文件路径字符冲突。
 */
object VideoAspectMemory {
    /** 最多记忆多少条内容，防止无限增长。 */
    const val MAX_ENTRIES = 200

    private const val RECORD_SEPARATOR = '\u001E'
    private const val FIELD_SEPARATOR = '\u001F'

    fun key(serverId: String, itemId: String): String = "$serverId/$itemId"

    fun decode(stored: String?): Map<String, VideoAspectMode> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val result = LinkedHashMap<String, VideoAspectMode>()
        stored.split(RECORD_SEPARATOR).forEach { record ->
            if (record.isEmpty()) return@forEach
            val fields = record.split(FIELD_SEPARATOR)
            if (fields.size != 2) return@forEach
            val key = decodeField(fields[0]) ?: return@forEach
            val mode = runCatching { VideoAspectMode.valueOf(fields[1]) }.getOrNull() ?: return@forEach
            result[key] = mode
        }
        return result
    }

    fun encode(overrides: Map<String, VideoAspectMode>): String =
        overrides.entries.joinToString(RECORD_SEPARATOR.toString()) { (key, mode) ->
            encodeField(key) + FIELD_SEPARATOR + mode.name
        }

    /** 键可能包含路径与分隔符，逐字段转义后再拼接。 */
    private fun encodeField(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decodeField(value: String): String? =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull()

    /**
     * 写入一条记忆并保持在 [MAX_ENTRIES] 内：已存在的键移到末尾（最近使用），
     * 超出上限时淘汰最久未写的一条。
     */
    fun remember(
        current: Map<String, VideoAspectMode>,
        key: String,
        mode: VideoAspectMode,
    ): Map<String, VideoAspectMode> {
        val updated = LinkedHashMap(current)
        updated.remove(key)
        updated[key] = mode
        while (updated.size > MAX_ENTRIES) {
            val oldest = updated.keys.firstOrNull() ?: break
            updated.remove(oldest)
        }
        return updated
    }
}
