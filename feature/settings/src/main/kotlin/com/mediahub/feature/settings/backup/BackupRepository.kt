package com.mediahub.feature.settings.backup

import com.mediahub.core.common.AppDispatchers
import com.mediahub.core.common.backup.BackupCrypto
import com.mediahub.core.common.backup.BackupDtos
import com.mediahub.core.common.backup.BackupFileFormat
import com.mediahub.core.common.backup.BackupSerializer
import com.mediahub.core.common.backup.BackupUrlGuard
import com.mediahub.core.database.prefs.UserPreferencesRepository
import com.mediahub.model.MediaServer
import com.mediahub.model.MediaType
import com.mediahub.model.PlaybackEngineMode
import com.mediahub.model.PlaybackProgress
import com.mediahub.model.PlayerGestures
import com.mediahub.model.ServerEndpoint
import com.mediahub.model.ServerType
import com.mediahub.model.SubtitleStyle
import com.mediahub.model.UserPreferences
import com.mediahub.model.activeEndpoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 恢复策略。 */
enum class RestoreStrategy { MERGE, REPLACE_SELECTED }

/** 备份内服务器身份三元组（同 ID 是否同源的裁决依据）。 */
data class BackupIdentity(
    val type: ServerType,
    val normalizedPrimaryUrl: String,
    val username: String?,
)

/** 深度验证后的可恢复载荷：枚举已解析、引用已核对；预览与恢复只消费它，不再触碰 valueOf。 */
class ValidatedRestore internal constructor(
    val payload: BackupDtos.BackupPayload,
    val servers: List<ValidatedServer>,
    val progress: List<ValidatedProgress>,
    val preferences: UserPreferences?,
    /** 引用了载荷中不存在服务器的播放记录数（预览披露，不静默丢弃）。 */
    val orphanProgressRecords: Int,
    val baselineFormatVersion: Int,
    val baselineAppVersion: String,
    val createdAtEpochMs: Long,
)

data class ValidatedServer(
    val backupId: String,
    val name: String,
    val type: ServerType,
    val username: String?,
    val note: String?,
    val isDefault: Boolean,
    val sortOrder: Int,
    val endpoints: List<ValidatedEndpoint>,
    val identity: BackupIdentity,
)

data class ValidatedEndpoint(
    val name: String,
    val url: String,
    val isPrimary: Boolean,
    val enabled: Boolean,
    val sortOrder: Int,
)

data class ValidatedProgress(
    val serverBackupId: String,
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPaused: Boolean,
    val updatedAtEpochMs: Long,
    val itemTitle: String?,
    val itemType: MediaType?,
)

/** 恢复预览（dry-run，零写入；含所选范围、冲突与基线版本）。 */
data class RestorePreview(
    val newServers: Int,
    val identicalServers: Int,
    val conflictingServers: Int,
    val conflictServerNames: List<String>,
    val progressToWrite: Int,
    val orphanProgressRecords: Int,
    val preferencesContained: Boolean,
    val preferencesWillRestore: Boolean,
    val appVersion: String,
    val createdAtEpochMs: Long,
    val baselineFormatVersion: Int,
)

/** 恢复结果。 */
data class RestoreResult(
    val addedServers: Int,
    val overwrittenServers: Int,
    val skippedExistingServers: Int,
    val conflictSkippedServers: Int,
    val restoredProgress: Int,
    val loginsInvalidated: Int,
    val preferencesRestored: Boolean,
)

/** 准备结果（统一验证的出口：错误全部类型化，零写入）。 */
sealed interface PrepareResult {
    data class Prepared(val validated: ValidatedRestore) : PrepareResult
    data class Rejected(val reason: String) : PrepareResult
}

/** 中断恢复结果。 */
sealed interface RecoveryOutcome {
    data object NothingToRecover : RecoveryOutcome
    data object CompletedEarlier : RecoveryOutcome
    data class Continued(val result: RestoreResult) : RecoveryOutcome
    data object RolledBack : RecoveryOutcome
    data class NeedsAttention(val reason: String) : RecoveryOutcome
}

/** 导出被守卫拒绝（如 URL 夹带疑似凭据）。 */
class ExportRejectedException(message: String) : Exception(message)

/** 恢复确认缺失（repository 侧防线，与 UI 按钮状态无关）。 */
class RestoreConfirmationRequiredException :
    Exception("替换所选数据需要先勾选确认")

/** 恢复失败（携带中断阶段与回滚状态）。 */
class RestoreFailedException(
    val phase: RestoreJournal.Phase?,
    val rolledBack: Boolean,
    cause: Throwable?,
) : Exception(cause?.message ?: "恢复失败", cause)

/** 载荷深度验证失败（枚举/字段范围零写入路径）。 */
private class ValidationRejectedException(message: String) : Exception(message)

/**
 * 本地备份与还原（Phase 1I review 重写）。
 *
 * 职责链：导出（值级守卫 + 上限）→ 导入（统一结构验证）→ 深度验证（枚举/引用，
 * 零写入）→ 预览（身份裁决 + 所选范围 + 冲突披露）→ 恢复（登录态隔离 → 单事务写库 →
 * 偏好 → 完成标记；任何中断走 [recoverInterruptedRestore] 前向继续，失败回滚保护快照）。
 *
 * 覆盖规则（预览展示、用户确认后执行）：
 * - MERGE「保留本机已有数据」：同身份已有服务器保留本机行，播放记录较新者胜；
 *   同 ID 不同来源（类型/地址/账号任一不同）视为冲突——本机保留、该源进度不导入；
 *   偏好在 MERGE 下不恢复。
 * - REPLACE_SELECTED：按 ID 覆盖服务器与全部关联进度；身份发生变化的覆盖目标
 *   先阻断旧登录态（Token/Provider 会话清除）；偏好仅在此策略下恢复。
 * 排除：密码/Token/Cookie/Authorization/PlaySessionId/临时 URL/缓存/日志/posterUrl。
 */
class BackupRepository @Inject constructor(
    private val backupDataSource: BackupDataSource,
    private val preferencesRepository: UserPreferencesRepository,
    private val restoreJournal: RestoreJournal,
    private val loginInvalidator: RestoreLoginInvalidator,
    private val dispatchers: AppDispatchers,
) {

    // ---- 导出 ----

    /** 导出：全量快照 → 白名单 DTO → URL 值级守卫 → 加密序列化。 */
    suspend fun exportBackup(password: CharArray, appVersion: String): ByteArray = withContext(dispatchers.io) {
        val snapshot = backupDataSource.readSnapshot()
        val preferences = preferencesRepository.flow.first()
        val payload = buildPayload(snapshot, preferences, appVersion)

        val urlMap = linkedMapOf<String, String>()
        for (server in payload.servers) {
            for (ep in server.endpoints) urlMap["${server.name}/${ep.name}"] = ep.url
        }
        BackupUrlGuard.inspectAll(urlMap)?.let { violation ->
            throw ExportRejectedException("存在携带疑似凭据的线路 URL（${describe(violation)}），已拒绝导出；请先修正该线路地址。")
        }

        BackupSerializer.export(payload, password)
    }

    // ---- 导入与深度验证 ----

    /** 导入 + 深度验证：全部错误类型化，零写入。 */
    suspend fun prepareRestore(bytes: ByteArray, password: CharArray): PrepareResult = withContext(dispatchers.io) {
        val importResult = BackupSerializer.import(bytes, password)
        when (importResult) {
            is BackupSerializer.ImportResult.Ok -> {
                val validated = try {
                    validate(importResult.payload)
                } catch (e: ValidationRejectedException) {
                    return@withContext PrepareResult.Rejected("备份内容校验失败：${e.message}")
                }
                PrepareResult.Prepared(validated)
            }
            is BackupSerializer.ImportResult.AuthenticationFailed -> PrepareResult.Rejected(importResult.cause)
            is BackupSerializer.ImportResult.Corrupted -> PrepareResult.Rejected("备份文件损坏：${importResult.reason}")
            is BackupSerializer.ImportResult.VersionTooNew ->
                PrepareResult.Rejected("备份版本过新（${importResult.fileVersion}），请升级应用")
            BackupSerializer.ImportResult.NotABackupFile -> PrepareResult.Rejected("不是有效的 MediaHub 备份文件")
        }
    }

    /**
     * 深度验证：解析全部枚举（未知值即拒绝，绝不留给数据库写入后的 valueOf 失败）、
     * 核对进度引用（孤立引用计数披露）、构建身份三元组。
     * 结构级校验（版本/计数/重复 ID/URL）已在 BackupSerializer.validatePayload 完成。
     */
    private fun validate(payload: BackupDtos.BackupPayload): ValidatedRestore {
        val servers = payload.servers.map { dto ->
            val type = try {
                ServerType.valueOf(dto.type)
            } catch (e: IllegalArgumentException) {
                throw ValidationRejectedException("未知媒体源类型「${dto.type}」（服务器 ${dto.backupId}）")
            }
            val endpoints = dto.endpoints.map { ep ->
                ValidatedEndpoint(ep.name, ep.url, ep.isPrimary, ep.enabled, ep.sortOrder)
            }
            ValidatedServer(
                backupId = dto.backupId, name = dto.name, type = type,
                username = dto.username, note = dto.note,
                isDefault = dto.isDefault, sortOrder = dto.sortOrder,
                endpoints = endpoints,
                identity = BackupIdentity(
                    type = type,
                    normalizedPrimaryUrl = BackupUrlGuard.normalizeForIdentity(
                        (endpoints.firstOrNull { it.isPrimary && it.enabled } ?: endpoints.first()).url,
                    ),
                    username = dto.username,
                ),
            )
        }

        val serverIds = servers.map { it.backupId }.toSet()
        val progress = payload.progress.map { dto ->
            ValidatedProgress(
                serverBackupId = dto.serverBackupId, itemId = dto.itemId,
                positionMs = dto.positionMs, durationMs = dto.durationMs,
                isPaused = dto.isPaused, updatedAtEpochMs = dto.updatedAtEpochMs,
                itemTitle = dto.itemTitle,
                itemType = dto.itemType?.let { type ->
                    try {
                        MediaType.valueOf(type)
                    } catch (e: IllegalArgumentException) {
                        throw ValidationRejectedException("未知媒体类型「$type」（播放记录 ${dto.serverBackupId}/${dto.itemId}）")
                    }
                },
            )
        }
        val orphan = progress.count { it.serverBackupId !in serverIds }

        val preferences = payload.preferences?.let { dto ->
            val mode = try {
                PlaybackEngineMode.valueOf(dto.playbackEngineMode)
            } catch (e: IllegalArgumentException) {
                throw ValidationRejectedException("未知播放内核模式「${dto.playbackEngineMode}」")
            }
            UserPreferences(
                playbackEngineMode = mode,
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

        return ValidatedRestore(
            payload = payload,
            servers = servers,
            progress = progress,
            preferences = preferences,
            orphanProgressRecords = orphan,
            baselineFormatVersion = payload.manifest.formatVersion,
            baselineAppVersion = payload.manifest.appVersion,
            createdAtEpochMs = payload.manifest.createdAtEpochMs,
        )
    }

    // ---- 预览 ----

    /** 恢复预览（dry-run，零写入）。冲突、范围、进度写入数与基线版本在此披露。 */
    suspend fun buildPreview(validated: ValidatedRestore, strategy: RestoreStrategy): RestorePreview =
        withContext(dispatchers.io) {
            val snapshot = backupDataSource.readSnapshot()
            val localIds = snapshot.servers.map { it.id }.toSet()
            val record = buildPlanRecord(validated, snapshot, strategy)
            val progressToWrite = resolveProgress(validated, record, snapshot)
            RestorePreview(
                newServers = record.overwriteServerIds.count { it !in localIds },
                identicalServers = record.skipExistingServerIds.size,
                conflictingServers = record.conflictSkippedServerIds.size,
                conflictServerNames = validated.servers
                    .filter { it.backupId in record.conflictSkippedServerIds }
                    .map { it.name },
                progressToWrite = progressToWrite.size,
                orphanProgressRecords = validated.orphanProgressRecords,
                preferencesContained = validated.preferences != null,
                preferencesWillRestore = record.includePreferences,
                appVersion = validated.baselineAppVersion,
                createdAtEpochMs = validated.createdAtEpochMs,
                baselineFormatVersion = validated.baselineFormatVersion,
            )
        }

    // ---- 恢复 ----

    /**
     * 应用用户确认过的恢复计划。
     * @param replaceConfirmed 替换确认（ViewModel 与 UI 之外的第二道防线）。
     */
    suspend fun restore(
        validated: ValidatedRestore,
        strategy: RestoreStrategy,
        replaceConfirmed: Boolean,
    ): RestoreResult = withContext(dispatchers.io) {
        if (strategy == RestoreStrategy.REPLACE_SELECTED && !replaceConfirmed) {
            throw RestoreConfirmationRequiredException()
        }
        // 叠加恢复前必须先处理历史中断（前向完成或回滚），否则拒绝本次操作
        when (val recovery = recoverInterruptedRestore()) {
            is RecoveryOutcome.NeedsAttention ->
                throw RestoreFailedException(null, rolledBack = false, cause = IllegalStateException(recovery.reason))
            else -> {}
        }

        val snapshot = backupDataSource.readSnapshot()
        val record = buildPlanRecord(validated, snapshot, strategy)
        val progress = resolveProgress(validated, record, snapshot)
        val planServers = validated.servers
            .filter { it.backupId in record.overwriteServerIds }
            .map { toMediaServer(it) }
        val planId = "restore-${UUID.randomUUID()}"
        val plan = RestorePlan(
            planId = planId,
            record = record,
            servers = planServers,
            progress = progress,
            preferences = if (record.includePreferences) validated.preferences else null,
        )
        val protectiveJson = BackupDtos.encodePayload(
            buildPayload(snapshot, preferencesRepository.flow.first(), appVersion = "protective"),
        )

        restoreJournal.begin(
            RestoreJournal.ActiveEntry(
                planId = planId,
                phase = RestoreJournal.Phase.PREPARING,
                payloadJson = BackupDtos.encodePayload(validated.payload),
                planRecordJson = BackupDtos.encodePlanRecord(record),
                protectiveSnapshotJson = protectiveJson,
                includePreferences = record.includePreferences,
                startedAtEpochMs = System.currentTimeMillis(),
            )
        )

        try {
            // 先阻断将被覆盖且身份已变的服务器旧登录态（安全优先：即使后续步骤失败也已失效）
            var invalidated = 0
            val localById = snapshot.servers.associateBy { it.id }
            for (id in record.overwriteServerIds) {
                val local = localById[id] ?: continue
                val incoming = validated.servers.first { it.backupId == id }
                if (compareIdentity(local, incoming) == IdentityVerdict.CONFLICTING) {
                    runCatching { loginInvalidator.invalidate(id) }
                        .onSuccess { invalidated++ }
                        .onFailure { restoreJournal.markFailed(planId, "登录态清除失败：${it.message}") }
                }
            }

            backupDataSource.applyRestorePlan(plan)
            restoreJournal.markPhase(planId, RestoreJournal.Phase.DB_WRITTEN)

            var prefsRestored = false
            if (record.includePreferences && plan.preferences != null) {
                preferencesRepository.update { plan.preferences }
                restoreJournal.markPhase(planId, RestoreJournal.Phase.PREFERENCES_APPLIED)
                prefsRestored = true
            }

            restoreJournal.markPhase(planId, RestoreJournal.Phase.COMPLETED)
            restoreJournal.clear(planId)

            RestoreResult(
                addedServers = planServers.count { it.id !in localById },
                overwrittenServers = planServers.count { it.id in localById },
                skippedExistingServers = record.skipExistingServerIds.size,
                conflictSkippedServers = record.conflictSkippedServerIds.size,
                restoredProgress = progress.size,
                loginsInvalidated = invalidated,
                preferencesRestored = prefsRestored,
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 用户取消/中途失败：日志保留在当前阶段，立即尽力回滚到保护快照
            restoreJournal.markFailed(planId, e.message ?: "恢复失败")
            val rollback = runCatching { rollbackFromSnapshot(protectiveJson) }
            if (rollback.isSuccess) {
                restoreJournal.clear(planId)
                throw RestoreFailedException(restoreJournal.read()?.phase, rolledBack = true, cause = e)
            }
            // 回滚也失败：日志保留，下次进入备份页走恢复协议
            throw RestoreFailedException(restoreJournal.read()?.phase, rolledBack = false, cause = e)
        }
    }

    /**
     * 中断恢复协议：前向继续（幂等重放冻结决策）为主，失败则回滚到保护快照。
     * 在进入备份页与每次新恢复前调用。
     */
    suspend fun recoverInterruptedRestore(): RecoveryOutcome = withContext(dispatchers.io) {
        val entry = restoreJournal.read() ?: return@withContext RecoveryOutcome.NothingToRecover
        if (entry.phase == RestoreJournal.Phase.COMPLETED) {
            restoreJournal.clear(entry.planId)
            return@withContext RecoveryOutcome.CompletedEarlier
        }
        try {
            val payload = BackupDtos.decodePayload(entry.payloadJson)
            val validated = validate(payload) // 重放前复验：验证不过 → 回滚路径
            val record = BackupDtos.decodePlanRecord(entry.planRecordJson)
            val snapshot = backupDataSource.readSnapshot()
            val progress = resolveProgress(validated, record, snapshot)
            val planServers = validated.servers
                .filter { it.backupId in record.overwriteServerIds }
                .map { toMediaServer(it) }
            backupDataSource.applyRestorePlan(
                RestorePlan(
                    planId = entry.planId,
                    record = record,
                    servers = planServers,
                    progress = progress,
                    preferences = if (record.includePreferences) validated.preferences else null,
                )
            )
            if (record.includePreferences && validated.preferences != null) {
                preferencesRepository.update { validated.preferences }
            }
            restoreJournal.markPhase(entry.planId, RestoreJournal.Phase.COMPLETED)
            restoreJournal.clear(entry.planId)
            RecoveryOutcome.Continued(
                RestoreResult(
                    addedServers = planServers.count { it.id !in snapshot.servers.map { s -> s.id }.toSet() },
                    overwrittenServers = planServers.count { it.id in snapshot.servers.map { s -> s.id }.toSet() },
                    skippedExistingServers = record.skipExistingServerIds.size,
                    conflictSkippedServers = record.conflictSkippedServerIds.size,
                    restoredProgress = progress.size,
                    loginsInvalidated = 0,
                    preferencesRestored = record.includePreferences,
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            try {
                rollbackFromSnapshot(entry.protectiveSnapshotJson)
                restoreJournal.clear(entry.planId)
                RecoveryOutcome.RolledBack
            } catch (rollbackError: Exception) {
                RecoveryOutcome.NeedsAttention(
                    "恢复中断自动续作失败（${e.message}），回滚也失败（${rollbackError.message}），日志已保留。"
                )
            }
        }
    }

    // ---- 计划构建（冻结决策，恢复与中断重放共用同一路径） ----

    private enum class IdentityVerdict { IDENTICAL, CONFLICTING }

    /** 身份裁决：类型 + 规范化主线路地址 + 账号归属全部一致才算同一来源。 */
    private fun compareIdentity(local: MediaServer, incoming: ValidatedServer): IdentityVerdict {
        val localUrl = BackupUrlGuard.normalizeForIdentity(
            local.endpoints.activeEndpoint()?.url.orEmpty(),
        )
        return if (
            local.type == incoming.identity.type &&
            localUrl == incoming.identity.normalizedPrimaryUrl &&
            local.username == incoming.identity.username
        ) IdentityVerdict.IDENTICAL else IdentityVerdict.CONFLICTING
    }

    private fun buildPlanRecord(
        validated: ValidatedRestore,
        snapshot: BackupSnapshot,
        strategy: RestoreStrategy,
    ): BackupDtos.RestorePlanRecord {
        val localById = snapshot.servers.associateBy { it.id }
        val skip = mutableListOf<String>()
        val overwrite = mutableListOf<String>()
        val conflicts = mutableListOf<String>()
        val remap = linkedMapOf<String, String>()
        for (vs in validated.servers) {
            remap[vs.backupId] = vs.backupId
            val local = localById[vs.backupId]
            when {
                local == null -> overwrite += vs.backupId
                strategy == RestoreStrategy.MERGE -> when (compareIdentity(local, vs)) {
                    IdentityVerdict.IDENTICAL -> skip += vs.backupId
                    IdentityVerdict.CONFLICTING -> conflicts += vs.backupId
                }
                else -> overwrite += vs.backupId
            }
        }
        return BackupDtos.RestorePlanRecord(
            strategy = strategy.name,
            skipExistingServerIds = skip,
            overwriteServerIds = overwrite,
            conflictSkippedServerIds = conflicts,
            idRemapping = remap,
            includePreferences = strategy == RestoreStrategy.REPLACE_SELECTED && validated.preferences != null,
            baselineFormatVersion = validated.baselineFormatVersion,
        )
    }

    /** 决策 → 实际写入的进度列表（MERGE 冲突不导入；同身份已有记录较新者胜）。 */
    private fun resolveProgress(
        validated: ValidatedRestore,
        record: BackupDtos.RestorePlanRecord,
        snapshot: BackupSnapshot,
    ): List<PlaybackProgress> {
        val localByKey = snapshot.progress.associateBy { "${it.serverId}/${it.itemId}" }
        val byBackupId = validated.servers.associateBy { it.backupId }
        return validated.progress.mapNotNull { vp ->
            val targetId = record.idRemapping[vp.serverBackupId] ?: return@mapNotNull null // 孤立引用：预览已披露
            val source = byBackupId[vp.serverBackupId]
            if (source != null && source.backupId in record.conflictSkippedServerIds) return@mapNotNull null
            if (source != null && source.backupId in record.skipExistingServerIds) {
                val existing = localByKey["$targetId/${vp.itemId}"]
                // 本机记录已存在且不旧于备份 → 保留本机（newer-wins）
                if (existing != null && existing.updatedAtEpochMs >= vp.updatedAtEpochMs) return@mapNotNull null
            }
            PlaybackProgress(
                serverId = targetId, itemId = vp.itemId,
                positionMs = vp.positionMs, durationMs = vp.durationMs,
                isPaused = vp.isPaused, updatedAtEpochMs = vp.updatedAtEpochMs,
                itemTitle = vp.itemTitle, itemType = vp.itemType,
            )
        }
    }

    private fun toMediaServer(vs: ValidatedServer): MediaServer = MediaServer(
        id = vs.backupId, name = vs.name, type = vs.type,
        username = vs.username, note = vs.note,
        isDefault = vs.isDefault, sortOrder = vs.sortOrder,
        createdAtEpochMs = System.currentTimeMillis(),
        endpoints = vs.endpoints.map { ep ->
            ServerEndpoint(
                id = "", serverId = vs.backupId,
                name = ep.name, url = ep.url,
                isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
            )
        },
    )

    /** 回滚：按保护快照重建本机状态（信任本地数据，不走验证）。 */
    private suspend fun rollbackFromSnapshot(protectiveJson: String) {
        val protective = BackupDtos.decodePayload(protectiveJson)
        val servers = protective.servers.map { dto ->
            MediaServer(
                id = dto.backupId, name = dto.name,
                type = ServerType.valueOf(dto.type),
                username = dto.username, note = dto.note,
                isDefault = dto.isDefault, sortOrder = dto.sortOrder,
                createdAtEpochMs = System.currentTimeMillis(),
                endpoints = dto.endpoints.map { ep ->
                    ServerEndpoint(
                        id = "", serverId = dto.backupId,
                        name = ep.name, url = ep.url,
                        isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
                    )
                },
            )
        }
        val progress = protective.progress.map { dto ->
            PlaybackProgress(
                serverId = dto.serverBackupId, itemId = dto.itemId,
                positionMs = dto.positionMs, durationMs = dto.durationMs,
                isPaused = dto.isPaused, updatedAtEpochMs = dto.updatedAtEpochMs,
                itemTitle = dto.itemTitle,
                itemType = dto.itemType?.let { runCatching { MediaType.valueOf(it) }.getOrNull() },
            )
        }
        backupDataSource.applyRestorePlan(
            RestorePlan(
                planId = "rollback-${UUID.randomUUID()}",
                record = BackupDtos.RestorePlanRecord(
                    strategy = "ROLLBACK",
                    skipExistingServerIds = emptyList(),
                    overwriteServerIds = servers.map { it.id },
                    conflictSkippedServerIds = emptyList(),
                    idRemapping = servers.associate { it.id to it.id },
                    includePreferences = false,
                ),
                servers = servers,
                progress = progress,
                preferences = null,
            )
        )
    }

    // ---- 载荷构建（导出与保护快照共用） ----

    private suspend fun buildPayload(
        snapshot: BackupSnapshot,
        prefs: UserPreferences,
        appVersion: String,
    ): BackupDtos.BackupPayload {
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
        val prefsDto = toPreferencesDto(prefs)
        return BackupDtos.BackupPayload(
            manifest = BackupFileFormat.Manifest(
                formatVersion = BackupCrypto.FORMAT_VERSION,
                minimumReaderVersion = BackupCrypto.MINIMUM_READER_VERSION,
                appVersion = appVersion,
                createdAtEpochMs = System.currentTimeMillis(),
                includedSections = listOf("servers", "progress", "preferences"),
                recordCounts = mapOf(
                    "servers" to serverDtos.size,
                    "progress" to progressDtos.size,
                    "preferences" to if (prefsDto != null) 1 else 0,
                ),
            ),
            servers = serverDtos,
            progress = progressDtos,
            preferences = prefsDto,
        )
    }

    private fun describe(v: BackupUrlGuard.Violation): String = when (v) {
        is BackupUrlGuard.Violation.UserInfo -> "URL user-info 段（${v.rawUrl}）"
        is BackupUrlGuard.Violation.SensitiveQuery -> "query 参数 ${v.key}（${v.rawUrl}）"
    }

    private fun toPreferencesDto(prefs: UserPreferences): BackupDtos.PreferencesDto = BackupDtos.PreferencesDto(
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
