package com.mediahub.feature.settings.backup

import com.mediahub.core.common.backup.BackupCrypto
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlayerGestures
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** 恢复策略。 */
enum class RestoreStrategy { MERGE, REPLACE_SELECTED }

/** 恢复预览。 */
data class RestorePreview(
    val newServers: Int,
    val existingServers: Int,
    val progressRecords: Int,
    val preferencesRestore: Boolean,
    val appVersion: String,
    val createdAtEpochMs: Long,
)

/** 恢复结果。 */
data class RestoreResult(
    val addedServers: Int,
    val skippedExistingServers: Int,
    val restoredProgress: Int,
    val preferencesRestored: Boolean,
)

/**
 * 本地备份与还原（Phase 1I-A 重写：组合 BackupDataSource 全量快照 + 偏好）。
 * 排除：密码/Token/Cookie/Authorization/PlaySessionId/临时 URL/缓存/日志/posterUrl。
 * 恢复媒体源后需重新登录（authState 不入备份）。
 */
class BackupRepository @Inject constructor(
    private val backupDataSource: BackupDataSource,
    private val preferencesRepository: UserPreferencesRepository,
) {

    /** 导出：全量快照 → 白名单 DTO → 加密序列化。 */
    suspend fun exportBackup(password: CharArray, appVersion: String): ByteArray {
        val snapshot = backupDataSource.readSnapshot()
        val preferences = preferencesRepository.flow.first()

        val serverDtos = snapshot.servers.map { s ->
            BackupDtos.ServerDto(
                backupId = s.id, name = s.name, type = s.type.name,
                username = s.username, note = s.note,
                isDefault = s.isDefault, sortOrder = s.sortOrder,
                endpoints = s.endpoints.map { ep ->
                    BackupDtos.EndpointDto(
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                },
            )
        }

        val progressDtos = snapshot.progress.map { p ->
            BackupDtos.ProgressDto(
                serverBackupId = p.serverId, itemId = p.itemId,
                positionMs = p.positionMs, durationMs = p.durationMs,
                isPaused = p.isPaused, updatedAtEpochMs = p.updatedAtEpochMs,
                itemTitle = p.itemTitle, itemType = p.itemType?.name,
            )
        }

        val prefsDto = BackupDtos.PreferencesDto(
            playbackEngineMode = preferences.playbackEngineMode.name,
            defaultPlaybackSpeed = preferences.defaultPlaybackSpeed,
            subtitleSizeSp = preferences.subtitleSizeSp,
            enableHardwareDecoding = preferences.enableHardwareDecoding,
            preferDirectPlay = preferences.preferDirectPlay,
            autoPlayNextEpisode = preferences.autoPlayNextEpisode,
            maxBitrateBps = preferences.maxBitrateBps,
            showPlayerInfoOverlay = preferences.showPlayerInfoOverlay,
            autoLandscape = preferences.autoLandscape,
            immersiveBars = preferences.immersiveBars,
            subtitleStyle = BackupDtos.SubtitleStyleDto(
                textColor = preferences.subtitleStyle.textColor,
                backgroundColor = preferences.subtitleStyle.backgroundColor,
                edgeType = preferences.subtitleStyle.edgeType,
                edgeColor = preferences.subtitleStyle.edgeColor,
                textScale = preferences.subtitleStyle.textScale,
                bottomPaddingFraction = preferences.subtitleStyle.bottomPaddingFraction,
                applyEmbeddedStyles = preferences.subtitleStyle.applyEmbeddedStyles,
            ),
            gestures = BackupDtos.PlayerGesturesDto(
                scrubEnabled = preferences.gestures.scrubEnabled,
                doubleTapSeekBackwardEnabled = preferences.gestures.doubleTapSeekBackwardEnabled,
                doubleTapSeekBackwardSeconds = preferences.gestures.doubleTapSeekBackwardSeconds,
                doubleTapSeekForwardEnabled = preferences.gestures.doubleTapSeekForwardEnabled,
                doubleTapSeekForwardSeconds = preferences.gestures.doubleTapSeekForwardSeconds,
                longPressSpeedEnabled = preferences.gestures.longPressSpeedEnabled,
                longPressSpeedMin = preferences.gestures.longPressSpeedMin,
                longPressSpeedMax = preferences.gestures.longPressSpeedMax,
                longPressDirectionalEnabled = preferences.gestures.longPressDirectionalEnabled,
                longPressDefaultSpeed = preferences.gestures.longPressDefaultSpeed,
            ),
        )

        val payload = BackupDtos.BackupPayload(
            manifest = BackupFileFormat.Manifest(
                formatVersion = BackupCrypto.FORMAT_VERSION,
                minimumReaderVersion = BackupCrypto.MINIMUM_READER_VERSION,
                appVersion = appVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                includedSections = listOf("servers", "progress", "preferences"),
                recordCounts = mapOf(
                    "servers" to serverDtos.size,
                    "progress" to progressDtos.size,
                    "preferences" to 1,
                ),
            ),
            servers = serverDtos,
            progress = progressDtos,
            preferences = prefsDto,
        )
        return BackupSerializer.export(payload, password)
    }

    /** 导入：解密返回 payload（不写库——由调用方决定策略后调 restore）。 */
    fun importBackup(bytes: ByteArray, password: CharArray): BackupSerializer.ImportResult =
        BackupSerializer.import(bytes, password)

    /** 恢复预览（dry-run，零写入）。 */
    suspend fun buildPreview(payload: BackupDtos.BackupPayload): RestorePreview {
        val snapshot = backupDataSource.readSnapshot()
        var existingCount = 0; var newCount = 0
        val existingIds = snapshot.servers.map { it.id }.toSet()
        for (dto in payload.servers) {
            if (dto.backupId in existingIds) existingCount++ else newCount++
        }
        return RestorePreview(
            newServers = newCount, existingServers = existingCount,
            progressRecords = payload.progress.size,
            preferencesRestore = payload.preferences != null,
            appVersion = payload.manifest.appVersion,
            createdAtEpochMs = payload.manifest.createdAtEpochMs,
        )
    }

    /** 恢复。MERGE 跳过已有同 id 媒体源；REPLACE 由调用方先删后调 MERGE。 */
    suspend fun restore(payload: BackupDtos.BackupPayload, strategy: RestoreStrategy): RestoreResult {
        val snapshot = backupDataSource.readSnapshot()
        val existingIds = snapshot.servers.map { it.id }.toSet()

        val serversToRestore = mutableListOf<MediaServer>()
        val skipIds = mutableSetOf<String>()
        val idMapping = mutableMapOf<String, String>()

        for (dto in payload.servers) {
            if (strategy == RestoreStrategy.MERGE && dto.backupId in existingIds) {
                idMapping[dto.backupId] = dto.backupId
                skipIds.add(dto.backupId)
                continue
            }
            val server = MediaServer(
                id = dto.backupId, name = dto.name,
                type = ServerType.valueOf(dto.type),
                username = dto.username, note = dto.note,
                isDefault = dto.isDefault, sortOrder = dto.sortOrder,
                createdAtEpochMs = System.currentTimeMillis(),
                endpoints = dto.endpoints.map { ep ->
                    com.mediahub.model.ServerEndpoint(
                        id = "", serverId = dto.backupId,
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                },
            )
            serversToRestore.add(server)
            idMapping[dto.backupId] = dto.backupId
        }

        val progressToRestore = payload.progress.mapNotNull { dto ->
            val serverId = idMapping[dto.serverBackupId] ?: return@mapNotNull null
            PlaybackProgress(
                serverId = serverId, itemId = dto.itemId,
                positionMs = dto.positionMs, durationMs = dto.durationMs,
                isPaused = dto.isPaused, updatedAtEpochMs = dto.updatedAtEpochMs,
                itemTitle = dto.itemTitle, itemType = dto.itemType?.let {
                    runCatching { com.mediahub.model.MediaType.valueOf(it) }.getOrNull()
                },
            )
        }

        backupDataSource.applyRestorePlan(
            RestorePlan(
                servers = serversToRestore,
                progress = progressToRestore,
                skipExistingServerIds = skipIds,
            )
        )

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
                    subtitleStyle = SubtitleStyle(
                        textColor = dto.subtitleStyle.textColor,
                        backgroundColor = dto.subtitleStyle.backgroundColor,
                        edgeType = dto.subtitleStyle.edgeType,
                        edgeColor = dto.subtitleStyle.edgeColor,
                        textScale = dto.subtitleStyle.textScale,
                        bottomPaddingFraction = dto.subtitleStyle.bottomPaddingFraction,
                        applyEmbeddedStyles = dto.subtitleStyle.applyEmbeddedStyles,
                    ),
                    gestures = PlayerGestures(
                        scrubEnabled = dto.gestures.scrubEnabled,
                        doubleTapSeekBackwardEnabled = dto.gestures.doubleTapSeekBackwardEnabled,
                        doubleTapSeekBackwardSeconds = dto.gestures.doubleTapSeekBackwardSeconds,
                        doubleTapSeekForwardEnabled = dto.gestures.doubleTapSeekForwardEnabled,
                        doubleTapSeekForwardSeconds = dto.gestures.doubleTapSeekForwardSeconds,
                        longPressSpeedEnabled = dto.gestures.longPressSpeedEnabled,
                        longPressSpeedMin = dto.gestures.longPressSpeedMin,
                        longPressSpeedMax = dto.gestures.longPressSpeedMax,
                        longPressDirectionalEnabled = dto.gestures.longPressDirectionalEnabled,
                        longPressDefaultSpeed = dto.gestures.longPressDefaultSpeed,
                    ),
                )
            }
            prefsRestored = true
        }

        return RestoreResult(
            addedServers = serversToRestore.size,
            skippedExistingServers = skipIds.size,
            restoredProgress = progressToRestore.size,
            preferencesRestored = prefsRestored,
        )
    }

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
        subtitleStyle = BackupDtos.SubtitleStyleDto(
            textColor = prefs.subtitleStyle.textColor,
            backgroundColor = prefs.subtitleStyle.backgroundColor,
            edgeType = prefs.subtitleStyle.edgeType,
            edgeColor = prefs.subtitleStyle.edgeColor,
            textScale = prefs.subtitleStyle.textScale,
            bottomPaddingFraction = prefs.subtitleStyle.bottomPaddingFraction,
            applyEmbeddedStyles = prefs.subtitleStyle.applyEmbeddedStyles,
        ),
        gestures = BackupDtos.PlayerGesturesDto(
            scrubEnabled = prefs.gestures.scrubEnabled,
            doubleTapSeekBackwardEnabled = prefs.gestures.doubleTapSeekBackwardEnabled,
            doubleTapSeekBackwardSeconds = prefs.gestures.doubleTapSeekBackwardSeconds,
            doubleTapSeekForwardEnabled = prefs.gestures.doubleTapSeekForwardEnabled,
            doubleTapSeekForwardSeconds = prefs.gestures.doubleTapSeekForwardSeconds,
            longPressSpeedEnabled = prefs.gestures.longPressSpeedEnabled,
            longPressSpeedMin = prefs.gestures.longPressSpeedMin,
            longPressSpeedMax = prefs.gestures.longPressSpeedMax,
            longPressDirectionalEnabled = prefs.gestures.longPressDirectionalEnabled,
            longPressDefaultSpeed = prefs.gestures.longPressDefaultSpeed,
        ),
    )
}
