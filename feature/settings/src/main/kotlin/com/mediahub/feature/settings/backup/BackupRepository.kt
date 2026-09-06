package com.mediahub.feature.settings.backup

import com.mediahub.core.common.backup.BackupCrypto
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.core.database.repository.ServerStore
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.UserPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** 恢复策略。 */
enum class RestoreStrategy { MERGE, REPLACE_SELECTED }

/** 恢复预览。 */
data class RestorePreview(
    val newServers: Int,
    val existingServers: Int,
    val preferencesRestore: Boolean,
    val appVersion: String,
    val createdAtEpochMs: Long,
)

/** 恢复结果。 */
data class RestoreResult(
    val addedServers: Int,
    val skippedExistingServers: Int,
    val preferencesRestored: Boolean,
)

/**
 * 本地备份与还原（Phase 1I-A 首版：媒体源 + 偏好）。
 * 排除：密码/Token/Cookie/Authorization/PlaySessionId/临时 URL/缓存/日志。
 * 恢复媒体源后需重新登录。播放记录备份待 ProgressStore 扩展后追加。
 */
class BackupRepository @Inject constructor(
    private val serverStore: ServerStore,
    private val preferencesRepository: UserPreferencesRepository,
) {

    /** 导出：读取媒体源 + 偏好 → 加密序列化。 */
    suspend fun exportBackup(password: CharArray, appVersion: String): ByteArray {
        val servers = serverStore.observeServers().first()
        val preferences = preferencesRepository.flow.first()

        val payload = BackupDtos.BackupPayload(
            manifest = BackupFileFormat.Manifest(
                formatVersion = BackupCrypto.FORMAT_VERSION,
                minimumReaderVersion = BackupCrypto.MINIMUM_READER_VERSION,
                appVersion = appVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                includedSections = listOf("servers", "preferences"),
                recordCounts = mapOf("servers" to servers.size, "preferences" to 1),
            ),
            servers = servers.map { it.toServerDto() },
            preferences = toPreferencesDto(preferences),
        )
        return BackupSerializer.export(payload, password)
    }

    /** 导入：解密并返回 payload（不写库——由调用方决定策略后调 [restore]）。 */
    fun importBackup(bytes: ByteArray, password: CharArray): BackupSerializer.ImportResult =
        BackupSerializer.import(bytes, password)

    /** 恢复预览（dry-run，不写库）。 */
    suspend fun buildPreview(payload: BackupDtos.BackupPayload): RestorePreview {
        val existing = serverStore.observeServers().first()
        var existingCount = 0
        var newCount = 0
        for (dto in payload.servers) {
            if (existing.any { it.id == dto.backupId }) existingCount++ else newCount++
        }
        return RestorePreview(
            newServers = newCount, existingServers = existingCount,
            preferencesRestore = payload.preferences != null,
            appVersion = payload.manifest.appVersion,
            createdAtEpochMs = payload.manifest.createdAtEpochMs,
        )
    }

    /** 恢复。MERGE 跳过已有同 id 媒体源；REPLACE_SELECTED 由调用方先删后调 MERGE。 */
    suspend fun restore(payload: BackupDtos.BackupPayload, strategy: RestoreStrategy): RestoreResult {
        var added = 0; var skipped = 0
        for (dto in payload.servers) {
            val existing = serverStore.getServer(dto.backupId)
            if (strategy == RestoreStrategy.MERGE && existing != null) { skipped++; continue }
            serverStore.updateServer(fromServerDto(dto))
            added++
        }

        var prefsRestored = false
        payload.preferences?.let { dto ->
            preferencesRepository.update { prefs ->
                prefs.copy(
                    playbackEngineMode = PlaybackEngineMode.valueOf(dto.playbackEngineMode),
                    defaultPlaybackSpeed = dto.defaultPlaybackSpeed,
                    subtitleSizeSp = dto.subtitleSizeSp,
                    enableHardwareDecoding = dto.enableHardwareDecoding,
                    preferDirectPlay = dto.preferDirectPlay,
                    autoPlayNextEpisode = dto.autoPlayNextEpisode,
                    maxBitrateBps = dto.maxBitrateBps,
                    showPlayerInfoOverlay = dto.showPlayerInfoOverlay,
                    autoLandscape = dto.autoLandscape,
                    immersiveBars = dto.immersiveBars,
                )
            }
            prefsRestored = true
        }

        return RestoreResult(addedServers = added, skippedExistingServers = skipped, preferencesRestored = prefsRestored)
    }

    // ---- 映射 ----

    private fun MediaServer.toServerDto() = BackupDtos.ServerDto(
        backupId = id, name = name, type = type.name,
        username = username, note = note,
        isDefault = isDefault, sortOrder = sortOrder,
        endpoints = endpoints.map { ep ->
            BackupDtos.EndpointDto(name = ep.name, url = ep.url, isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder)
        },
    )

    private fun fromServerDto(dto: BackupDtos.ServerDto) = MediaServer(
        id = dto.backupId, name = dto.name, type = ServerType.valueOf(dto.type),
        username = dto.username, note = dto.note,
        isDefault = dto.isDefault, sortOrder = dto.sortOrder,
        createdAtEpochMs = System.currentTimeMillis(),
        endpoints = dto.endpoints.map { ep ->
            ServerEndpoint(id = "", serverId = dto.backupId, name = ep.name, url = ep.url, isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder)
        },
    )

    private fun toPreferencesDto(prefs: UserPreferences) = BackupDtos.PreferencesDto(
        playbackEngineMode = prefs.playbackEngineMode.name,
        defaultPlaybackSpeed = prefs.defaultPlaybackSpeed,
        subtitleSizeSp = prefs.subtitleSizeSp,
        enableHardwareDecoding = prefs.enableHardwareDecoding,
        preferDirectPlay = prefs.preferDirectPlay,
        autoPlayNextEpisode = prefs.autoPlayNextEpisode,
        maxBitrateBps = prefs.maxBitrateBps,
        showPlayerInfoOverlay = prefs.showPlayerInfoOverlay,
        autoLandscape = prefs.autoLandscape,
        immersiveBars = prefs.immersiveBars,
    )
}
