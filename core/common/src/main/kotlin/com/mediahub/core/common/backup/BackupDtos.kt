package com.mediahub.core.common.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 备份内容白名单 DTO（Phase 1I-A）。
 *
 * 白名单：媒体源（名称/类型/地址/线路/排序）、账号非敏感标识（用户名）。
 * 排除：密码、Token、Cookie、Authorization、DeviceId、PlaySessionId、
 *        PlaybackSource.sessionId、临时播放 URL、带签名的图片 URL、视频缓存、日志。
 *
 * 线路质量测试结果（lastApiLatencyMs 等）属于设备环境，不入备份。
 * SAF 目录授权属于设备授权，不入备份。
 */
object BackupDtos {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    // ---- 媒体源 ----

    @Serializable
    data class EndpointDto(
        @SerialName("name") val name: String,
        @SerialName("url") val url: String,
        @SerialName("isPrimary") val isPrimary: Boolean,
        @SerialName("enabled") val enabled: Boolean,
        @SerialName("sortOrder") val sortOrder: Int,
    )

    @Serializable
    data class ServerDto(
        @SerialName("backupId") val backupId: String,
        @SerialName("name") val name: String,
        @SerialName("type") val type: String,
        @SerialName("username") val username: String? = null,
        @SerialName("note") val note: String? = null,
        @SerialName("isDefault") val isDefault: Boolean = false,
        @SerialName("sortOrder") val sortOrder: Int = 0,
        @SerialName("endpoints") val endpoints: List<EndpointDto> = emptyList(),
    )

    // ---- 播放记录 ----

    @Serializable
    data class ProgressDto(
        @SerialName("serverBackupId") val serverBackupId: String,
        @SerialName("itemId") val itemId: String,
        @SerialName("positionMs") val positionMs: Long,
        @SerialName("durationMs") val durationMs: Long,
        @SerialName("isPaused") val isPaused: Boolean,
        @SerialName("updatedAtEpochMs") val updatedAtEpochMs: Long,
        @SerialName("itemTitle") val itemTitle: String? = null,
        @SerialName("itemType") val itemType: String? = null,
        // posterUrl 排除：临时/带签名 URL 不导出，恢复后重新获取
    )

    // ---- 偏好（显式嵌套 DTO，逐字段白名单；不用任意 JSON 字符串绕过） ----

    @Serializable
    data class SubtitleStyleDto(
        @SerialName("textColor") val textColor: Int,
        @SerialName("backgroundColor") val backgroundColor: Int,
        @SerialName("edgeType") val edgeType: Int,
        @SerialName("edgeColor") val edgeColor: Int,
        @SerialName("textScale") val textScale: Float,
        @SerialName("bottomPaddingFraction") val bottomPaddingFraction: Float,
        @SerialName("applyEmbeddedStyles") val applyEmbeddedStyles: Boolean,
    )

    @Serializable
    data class PlayerGesturesDto(
        @SerialName("scrubEnabled") val scrubEnabled: Boolean,
        @SerialName("doubleTapSeekBackwardEnabled") val doubleTapSeekBackwardEnabled: Boolean,
        @SerialName("doubleTapSeekBackwardSeconds") val doubleTapSeekBackwardSeconds: Int,
        @SerialName("doubleTapSeekForwardEnabled") val doubleTapSeekForwardEnabled: Boolean,
        @SerialName("doubleTapSeekForwardSeconds") val doubleTapSeekForwardSeconds: Int,
        @SerialName("longPressSpeedEnabled") val longPressSpeedEnabled: Boolean,
        @SerialName("longPressSpeedMin") val longPressSpeedMin: Float,
        @SerialName("longPressSpeedMax") val longPressSpeedMax: Float,
        @SerialName("longPressDirectionalEnabled") val longPressDirectionalEnabled: Boolean,
        @SerialName("longPressDefaultSpeed") val longPressDefaultSpeed: Float,
    )

    @Serializable
    data class PreferencesDto(
        @SerialName("playbackEngineMode") val playbackEngineMode: String = "AUTO",
        @SerialName("defaultPlaybackSpeed") val defaultPlaybackSpeed: Float = 1f,
        @SerialName("subtitleSizeSp") val subtitleSizeSp: Int = 18,
        @SerialName("enableHardwareDecoding") val enableHardwareDecoding: Boolean = true,
        @SerialName("preferDirectPlay") val preferDirectPlay: Boolean = true,
        @SerialName("autoPlayNextEpisode") val autoPlayNextEpisode: Boolean = true,
        @SerialName("maxBitrateBps") val maxBitrateBps: Long? = null,
        @SerialName("showPlayerInfoOverlay") val showPlayerInfoOverlay: Boolean = false,
        @SerialName("autoLandscape") val autoLandscape: Boolean = true,
        @SerialName("immersiveBars") val immersiveBars: Boolean = true,
        @SerialName("subtitleStyle") val subtitleStyle: SubtitleStyleDto = SubtitleStyleDto(0xFFFFFFFF.toInt(), 0x00000000, 1, 0xFF000000.toInt(), 1f, 0.08f, true),
        @SerialName("gestures") val gestures: PlayerGesturesDto = PlayerGesturesDto(true, false, 10, false, 10, true, 0.5f, 5.0f, true, 2.0f),
    )

    // ---- 内层完整载荷 ----

    @Serializable
    data class BackupPayload(
        @SerialName("manifest") val manifest: BackupFileFormat.Manifest,
        @SerialName("servers") val servers: List<ServerDto> = emptyList(),
        @SerialName("progress") val progress: List<ProgressDto> = emptyList(),
        @SerialName("preferences") val preferences: PreferencesDto? = null,
    )

    /**
     * 恢复计划决策记录（可持久化，Phase 1I review：中断恢复按此重放，不凭内存重推）。
     * 身份裁决在生成时确定并冻结：重放必须复用同一份决策，保证幂等。
     */
    @Serializable
    data class RestorePlanRecord(
        @SerialName("strategy") val strategy: String, // MERGE | REPLACE_SELECTED
        /** 同身份已有服务器：MERGE 保留本机行（newer-wins 进度）。 */
        @SerialName("skipExistingServerIds") val skipExistingServerIds: List<String> = emptyList(),
        /** 计划写入/覆盖的服务器（REPLACE 全部；MERGE 仅新增）。 */
        @SerialName("overwriteServerIds") val overwriteServerIds: List<String> = emptyList(),
        /** 同 ID 不同来源冲突：本机保留，其播放记录不导入（MERGE）。 */
        @SerialName("conflictSkippedServerIds") val conflictSkippedServerIds: List<String> = emptyList(),
        /** 备份服务器 ID → 本机写入 ID（同源同 ID 恒等映射；冲突时 MERGE 仍映射本机 ID 但进度不导入）。 */
        @SerialName("idRemapping") val idRemapping: Map<String, String> = emptyMap(),
        @SerialName("includePreferences") val includePreferences: Boolean = false,
        @SerialName("baselineFormatVersion") val baselineFormatVersion: Int = 1,
        /** 冻结本次实际进度写入数，重启续作不拿全库记录数冒充恢复数。 */
        @SerialName("restoredProgressCount") val restoredProgressCount: Int? = null,
    )

    fun encodePayload(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun decodePayload(data: String): BackupPayload = json.decodeFromString(BackupPayload.serializer(), data)

    fun encodePlanRecord(record: RestorePlanRecord): String = json.encodeToString(RestorePlanRecord.serializer(), record)

    fun decodePlanRecord(data: String): RestorePlanRecord = json.decodeFromString(RestorePlanRecord.serializer(), data)
}
