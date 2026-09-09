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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val strategy: RestoreStrategy = RestoreStrategy.MERGE,
    val frozenPlan: PreparedRestorePlan? = null,
)

/** Bound to the validated input and exact local data shown in the preview. */
class PreparedRestorePlan internal constructor(
    internal val validated: ValidatedRestore,
    internal val plan: RestorePlan,
    internal val images: RestoreImages,
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
) : Exception(if (rolledBack) "恢复失败，已还原本机数据；已清除的登录态需重新登录" else "恢复未完成，请先处理中断恢复", cause)

/** 载荷深度验证失败（枚举/字段范围零写入路径）。 */
private class ValidationRejectedException(message: String) : Exception(message)

/**
 * 本地备份与还原：冻结预览、身份隔离与跨存储中断恢复。
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
    private val snapshotStorage: RestoreSnapshotStorage,
) {

    private companion object { val restoreMutex = Mutex() }

    // ---- 导出 ----

    /** 导出：全量快照 → 白名单 DTO → URL 值级守卫 → 加密序列化。 */
    suspend fun exportBackup(password: CharArray, appVersion: String): ByteArray = withContext(dispatchers.io) {
        val snapshot = backupDataSource.readSnapshot()
        val preferences = preferencesRepository.flow.first()
        val payload = buildPayload(snapshot, preferences, appVersion)

        for (server in payload.servers) {
            for (ep in server.endpoints) BackupUrlGuard.inspect(ep.url)?.let { violation ->
                throw ExportRejectedException("存在携带疑似凭据的线路 URL（${describe(violation)}），已拒绝导出；请先修正该线路地址。")
            }
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
                throw ValidationRejectedException("未知媒体源类型")
            }
            // Freeze the identity in the same order used by ServerEndpointDao after persistence.
            val endpoints = dto.endpoints.sortedBy { it.sortOrder }.map { ep ->
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
                        (endpoints.firstOrNull { it.isPrimary && it.enabled } ?: endpoints.firstOrNull { it.enabled })?.url.orEmpty(),
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
                        throw ValidationRejectedException("未知媒体类型")
                    }
                },
            )
        }
        val orphan = progress.count { it.serverBackupId !in serverIds }

        val preferences = payload.preferences?.let { dto ->
            val mode = try {
                PlaybackEngineMode.valueOf(dto.playbackEngineMode)
            } catch (e: IllegalArgumentException) {
                throw ValidationRejectedException("未知播放内核模式")
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

    // ---- Frozen preview and recoverable application ----

    suspend fun buildPreview(validated: ValidatedRestore, strategy: RestoreStrategy): RestorePreview =
        withContext(dispatchers.io) {
            val before = backupDataSource.readSnapshot()
            val prefs = preferencesRepository.flow.first()
            val draftRecord = buildPlanRecord(validated, before, strategy)
            val progress = resolveProgress(validated, draftRecord, before)
            val record = draftRecord.copy(restoredProgressCount = progress.size)
            val plan = RestorePlan(
                "restore-${UUID.randomUUID()}", record,
                validated.servers.filter { it.backupId in record.overwriteServerIds }.map { toMediaServer(it) },
                progress, if (record.includePreferences) validated.preferences else null, before,
            )
            val images = RestoreImages(before, materializeRestorePlan(before, plan), prefs, plan.preferences ?: prefs, record)
            require(hasStableEndpointIdentities(images)) { "线路优先级无法确定稳定身份，已阻止恢复" }
            val localIds = before.servers.map { it.id }.toSet()
            RestorePreview(
                newServers = record.overwriteServerIds.count { it !in localIds },
                identicalServers = record.skipExistingServerIds.size,
                conflictingServers = record.conflictSkippedServerIds.size,
                conflictServerNames = validated.servers.filter { it.backupId in record.conflictSkippedServerIds }.map { it.name },
                progressToWrite = progress.size,
                orphanProgressRecords = validated.orphanProgressRecords,
                preferencesContained = validated.preferences != null,
                preferencesWillRestore = record.includePreferences,
                appVersion = validated.baselineAppVersion,
                createdAtEpochMs = validated.createdAtEpochMs,
                baselineFormatVersion = validated.baselineFormatVersion,
                strategy = strategy,
                frozenPlan = PreparedRestorePlan(validated, plan, images),
            )
        }

    suspend fun restore(
        validated: ValidatedRestore,
        strategy: RestoreStrategy,
        replaceConfirmed: Boolean,
        preview: RestorePreview,
    ): RestoreResult = withContext(dispatchers.io) { restoreMutex.withLock {
        if (strategy == RestoreStrategy.REPLACE_SELECTED && !replaceConfirmed) throw RestoreConfirmationRequiredException()
        val prepared = preview.frozenPlan
            ?: throw RestoreBaselineChangedException()
        if (prepared.validated !== validated || preview.strategy != strategy || prepared.plan.record.strategy != strategy.name) {
            throw RestoreBaselineChangedException()
        }
        // Never stack operations, including one whose recovery is blocked or whose journal is corrupt.
        when (recoverLocked()) {
            is RecoveryOutcome.NeedsAttention -> throw RestoreFailedException(null, false, null)
            else -> Unit
        }
        val images = prepared.images
        if (!images.before.sameData(backupDataSource.readSnapshot()) || preferencesRepository.flow.first() != images.beforePreferences) {
            throw RestoreBaselineChangedException()
        }
        val plan = prepared.plan
        // Full encrypted image must be durably written before the journal permits any mutation.
        snapshotStorage.save(plan.planId, images)
        restoreJournal.begin(RestoreJournal.ActiveEntry(
            planId = plan.planId, phase = RestoreJournal.Phase.PREPARING,
            payloadJson = BackupDtos.encodePayload(validated.payload),
            planRecordJson = BackupDtos.encodePlanRecord(plan.record),
            protectiveSnapshotJson = "", includePreferences = plan.record.includePreferences,
            startedAtEpochMs = System.currentTimeMillis(), protectiveSnapshotRef = plan.planId,
        ))
        loginInvalidator.withIdentityChange(identityChangeTargets(images)) {
            var completed = false
            var databaseWritten = false
            try {
                val invalidated = invalidateChangedIdentities(images)
                backupDataSource.applyRestorePlan(plan)
                databaseWritten = true
                restoreJournal.markPhase(plan.planId, RestoreJournal.Phase.DB_WRITTEN)
                applyPreferences(images, plan.record.includePreferences)
                if (plan.record.includePreferences) restoreJournal.markPhase(plan.planId, RestoreJournal.Phase.PREFERENCES_APPLIED)
                restoreJournal.markPhase(plan.planId, RestoreJournal.Phase.COMPLETED)
                completed = true
                restoreJournal.clear(plan.planId)
                runCatching { snapshotStorage.delete(plan.planId) }
                resultFor(plan, images, invalidated)
            } catch (e: CancellationException) {
                // The persisted phase determines restart behavior; cancellation is never reported as success.
                throw e
            } catch (e: Exception) {
                if (completed) throw RestoreFailedException(RestoreJournal.Phase.COMPLETED, false, e)
                if (e is RestoreBaselineChangedException && !databaseWritten) {
                    // The transaction rejected before its first write; keep concurrent local work and re-preview.
                    restoreJournal.clear(plan.planId)
                    runCatching { snapshotStorage.delete(plan.planId) }
                    throw e
                }
                val phase = runCatching { restoreJournal.read()?.phase }.getOrNull()
                val rolledBack = try {
                    restoreJournal.markPhase(plan.planId, RestoreJournal.Phase.ROLLING_BACK)
                    rollback(images)
                    restoreJournal.clear(plan.planId)
                    runCatching { snapshotStorage.delete(plan.planId) }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
                throw RestoreFailedException(phase, rolledBack, e)
            }
        }
    } }

    suspend fun recoverInterruptedRestore(): RecoveryOutcome = withContext(dispatchers.io) {
        restoreMutex.withLock { recoverLocked() }
    }

    private suspend fun recoverLocked(): RecoveryOutcome {
        val entry = try { restoreJournal.read() } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return RecoveryOutcome.NeedsAttention("恢复日志损坏或不可读，已阻止新的恢复")
        } ?: return RecoveryOutcome.NothingToRecover
        if (entry.phase == RestoreJournal.Phase.COMPLETED) {
            return try {
                restoreJournal.clear(entry.planId)
                entry.protectiveSnapshotRef?.let { runCatching { snapshotStorage.delete(it) } }
                RecoveryOutcome.CompletedEarlier
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                RecoveryOutcome.NeedsAttention("恢复已完成，但完成日志尚未成功清理")
            }
        }
        // Historical entries did not contain an exact before image. Do not invent one from export data.
        val ref = entry.protectiveSnapshotRef
            ?: return RecoveryOutcome.NeedsAttention("旧恢复日志缺少完整保护快照，需人工处理后再恢复")
        if (ref != entry.planId) return RecoveryOutcome.NeedsAttention("恢复日志与保护快照引用不匹配")
        val images = try { snapshotStorage.read(ref) } catch (e: CancellationException) { throw e } catch (_: Exception) {
            return RecoveryOutcome.NeedsAttention("保护快照损坏或不可读取，日志已保留")
        }
        // Old writers could freeze array order while Room later selected a different address.
        // Retain such plans for manual attention; never silently recompute a confirmed identity.
        if (!hasStableEndpointIdentities(images)) {
            return RecoveryOutcome.NeedsAttention("保护快照的线路身份与持久化顺序不一致，日志已保留")
        }
        if (entry.phase == RestoreJournal.Phase.ROLLING_BACK) {
            return loginInvalidator.withIdentityChange(identityChangeTargets(images)) {
                try {
                    rollback(images)
                    restoreJournal.clear(entry.planId)
                    runCatching { snapshotStorage.delete(ref) }
                    RecoveryOutcome.RolledBack
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    RecoveryOutcome.NeedsAttention("回滚尚未完成，日志已保留")
                }
            }
        }
        val current = backupDataSource.readSnapshot()
        if (!current.sameData(images.before) && !current.sameData(images.after)) {
            return RecoveryOutcome.NeedsAttention("中断后本机数据已变化，已阻止自动覆盖")
        }
        if (entry.includePreferences && preferencesRepository.flow.first().let {
                it != images.beforePreferences && it != images.afterPreferences
            }) return RecoveryOutcome.NeedsAttention("中断后本机偏好已变化，已阻止自动覆盖")
        return loginInvalidator.withIdentityChange(identityChangeTargets(images)) {
            var completed = false
            try {
                val record = BackupDtos.decodePlanRecord(entry.planRecordJson)
                require(record.strategy in setOf("MERGE", "REPLACE_SELECTED"))
                require(record == images.record && record.includePreferences == entry.includePreferences)
                val invalidated = invalidateChangedIdentities(images)
                // The encrypted after image contains frozen timestamps, IDs and merge winners.
                if (!current.sameData(images.after)) {
                    backupDataSource.applyRestorePlan(fullImagePlan(entry.planId, images.after, current))
                }
                restoreJournal.markPhase(entry.planId, RestoreJournal.Phase.DB_WRITTEN)
                applyPreferences(images, entry.includePreferences)
                if (entry.includePreferences) restoreJournal.markPhase(entry.planId, RestoreJournal.Phase.PREFERENCES_APPLIED)
                restoreJournal.markPhase(entry.planId, RestoreJournal.Phase.COMPLETED)
                completed = true
                restoreJournal.clear(entry.planId)
                runCatching { snapshotStorage.delete(ref) }
                RecoveryOutcome.Continued(resultFor(
                    RestorePlan(entry.planId, record, images.after.servers.filter { it.id in record.overwriteServerIds },
                        images.after.progress, if (entry.includePreferences) images.afterPreferences else null), images, invalidated,
                ))
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                if (completed) return@withIdentityChange RecoveryOutcome.NeedsAttention("恢复已完成，但完成日志尚未成功清理")
                try {
                    restoreJournal.markPhase(entry.planId, RestoreJournal.Phase.ROLLING_BACK)
                    rollback(images)
                    restoreJournal.clear(entry.planId)
                    runCatching { snapshotStorage.delete(ref) }
                    RecoveryOutcome.RolledBack
                } catch (e: CancellationException) { throw e } catch (_: Exception) {
                    RecoveryOutcome.NeedsAttention("恢复续作及回滚未完成，日志已保留")
                }
            }
        }
    }

    private suspend fun applyPreferences(images: RestoreImages, include: Boolean) {
        if (!include) return
        preferencesRepository.update { current ->
            if (current != images.beforePreferences && current != images.afterPreferences) throw RestoreBaselineChangedException()
            images.afterPreferences
        }
    }

    private suspend fun rollback(images: RestoreImages) {
        val current = backupDataSource.readSnapshot()
        if (!current.sameData(images.before) && !current.sameData(images.after)) throw RestoreBaselineChangedException()
        if (!current.sameData(images.before)) {
            // A resumed rollback may encounter a login created against the restored address.
            // Revoke it before the inverse identity change, while the outer auth block is held.
            invalidateChangedIdentities(images)
            backupDataSource.applyRestorePlan(fullImagePlan("rollback", images.before, current))
        }
        if (images.beforePreferences != images.afterPreferences) preferencesRepository.update { currentPrefs ->
            if (currentPrefs != images.beforePreferences && currentPrefs != images.afterPreferences) throw RestoreBaselineChangedException()
            images.beforePreferences
        }
    }

    private fun fullImagePlan(id: String, image: BackupSnapshot, expected: BackupSnapshot) = RestorePlan(
        id, BackupDtos.RestorePlanRecord("ROLLBACK", overwriteServerIds = image.servers.map { it.id }),
        image.servers, image.progress, null, expected,
    )

    private fun identityChangeTargets(images: RestoreImages): Set<String> {
        val before = images.before.servers.associateBy { it.id }
        return images.after.servers.filter { incoming ->
            val local = before[incoming.id]
            local == null || identity(local) != identity(incoming)
        }.map { it.id }.toSet()
    }

    private suspend fun invalidateChangedIdentities(images: RestoreImages): Int {
        val targets = identityChangeTargets(images)
        for (id in targets) loginInvalidator.invalidate(id)
        return targets.size
    }

    private fun identity(server: MediaServer) = BackupIdentity(server.type,
        BackupUrlGuard.normalizeForIdentity(server.endpoints.activeEndpoint()?.url.orEmpty()), server.username)

    private fun hasStableEndpointIdentities(images: RestoreImages): Boolean =
        (images.before.servers + images.after.servers).all { server ->
            val endpoints = server.endpoints.map {
                BackupDtos.EndpointDto(it.name, it.url, it.isPrimary, it.enabled, it.sortOrder)
            }
            BackupSerializer.hasUnambiguousEndpointIdentity(endpoints) &&
                identity(server) == identity(server.copy(endpoints = server.endpoints.sortedBy { it.sortOrder }))
        }

    private fun resultFor(plan: RestorePlan, images: RestoreImages, invalidated: Int): RestoreResult {
        val beforeIds = images.before.servers.map { it.id }.toSet()
        return RestoreResult(plan.record.overwriteServerIds.count { it !in beforeIds },
            plan.record.overwriteServerIds.count { it in beforeIds }, plan.record.skipExistingServerIds.size,
            plan.record.conflictSkippedServerIds.size, plan.record.restoredProgressCount ?: plan.progress.size,
            invalidated, plan.record.includePreferences)
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
        val localByKey = snapshot.progress.associateBy { it.serverId to it.itemId }
        val byBackupId = validated.servers.associateBy { it.backupId }
        return validated.progress.mapNotNull { vp ->
            val targetId = record.idRemapping[vp.serverBackupId] ?: return@mapNotNull null // 孤立引用：预览已披露
            val source = byBackupId[vp.serverBackupId]
            if (source != null && source.backupId in record.conflictSkippedServerIds) return@mapNotNull null
            if (source != null && source.backupId in record.skipExistingServerIds) {
                val existing = localByKey[targetId to vp.itemId]
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
                // Export has no endpoint IDs. Freeze fresh global IDs in the preview; another local
                // source may already own a predictable backupId-derived endpoint primary key.
                id = UUID.randomUUID().toString(), serverId = vs.backupId,
                name = ep.name, url = ep.url,
                isPrimary = ep.isPrimary, enabled = ep.enabled, sortOrder = ep.sortOrder,
            )
        },
    )

    // ---- 对外导出载荷（保护快照使用独立的加密完整 image） ----

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
                // SAF grants and local device paths are device-bound, never portable backup addresses.
                endpoints = (if (s.type == ServerType.LOCAL) emptyList() else s.endpoints).map { ep ->
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
        is BackupUrlGuard.Violation.UserInfo -> "URL user-info 段"
        is BackupUrlGuard.Violation.SensitiveQuery -> "敏感 query 参数"
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
