package com.mediahub.model

/**
 * 电子节目单条目（EPG）。
 *
 * 语义约束：
 * - [channelId] 是 **Provider 自己的频道 id**（对应 `MediaItem.id`），不是外部 XMLTV 的
 *   `channel` 属性；Provider 负责在内部完成映射，上层无需理解 XMLTV 细节。
 * - 时间使用 epoch 毫秒；XMLTV 里的时区偏移必须在解析时归一化，禁止把本地时间直接透传。
 * - 条目是只读展示数据，不落库。
 */
data class EpgProgram(
    val channelId: String,
    val title: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val description: String? = null,
    val iconUrl: String? = null,
)
