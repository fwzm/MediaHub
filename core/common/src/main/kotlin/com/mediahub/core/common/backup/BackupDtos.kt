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
        @SerialName("posterUrl") val posterUrl: String? = null,
        @SerialName("itemType") val itemType: String? = null,
    )

    // ---- 用户偏好 ----

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
        @SerialName("subtitleStyleJson") val subtitleStyleJson: String = "{}",
        @SerialName("gesturesJson") val gesturesJson: String = "{}",
    )

    // ---- 内层完整载荷 ----

    @Serializable
    data class BackupPayload(
        @SerialName("manifest") val manifest: BackupFileFormat.Manifest,
        @SerialName("servers") val servers: List<ServerDto> = emptyList(),
        @SerialName("progress") val progress: List<ProgressDto> = emptyList(),
        @SerialName("preferences") val preferences: PreferencesDto? = null,
    )

    fun encodePayload(payload: BackupPayload): String = json.encodeToString(BackupPayload.serializer(), payload)

    fun decodePayload(data: String): BackupPayload = json.decodeFromString(BackupPayload.serializer(), data)
}
