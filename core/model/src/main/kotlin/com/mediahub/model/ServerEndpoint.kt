package com.mediahub.model

/**
 * 媒体源的一条线路（Phase 1B-2.5 Server Management）。
 * 一个 [MediaServer] 可有多条线路（主/备），手动切换。
 * 敏感信息（Token/Cookie）绝不在此存储（见 core:security）。
 */
data class ServerEndpoint(
    val id: String,
    val serverId: String,
    val name: String,
    val url: String,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val lastLatencyMs: Long? = null,
    val lastError: String? = null,
    val lastTestedAtEpochMs: Long? = null,
    // ---- 媒体线路质量（U4-D）----
    val lastApiLatencyMs: Long? = null,
    val lastMediaFirstByteMs: Long? = null,
    val lastMediaThroughputMbps: Double? = null,
    val lastProtocol: String? = null,
    val lastSupportsRange: Boolean? = null,
    val lastHttpCode: Int? = null,
)

/** 当前生效线路：主线路优先，否则第一个 enabled 线路。 */
fun List<ServerEndpoint>.activeEndpoint(): ServerEndpoint? =
    firstOrNull { it.isPrimary && it.enabled } ?: firstOrNull { it.enabled }
