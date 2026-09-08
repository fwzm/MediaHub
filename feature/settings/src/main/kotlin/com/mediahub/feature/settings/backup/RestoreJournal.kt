package com.mediahub.feature.settings.backup

import android.content.Context
import com.mediahub.core.common.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * 恢复操作日志（Phase 1I review：跨 DataStore 的持久化阶段记录）。
 *
 * 恢复 = 登录态失效 → 数据库事务 → 偏好写入，各步落在不同持久化介质，
 * 任一步中断都可能留下半写入状态。[RestoreJournal] 在任何持久化变更前写入
 * 计划与阶段（SharedPreferences commit 同步落盘），下次进入备份页由
 * [BackupRepository.recoverInterruptedRestore] 按「前向继续，失败则回滚」恢复。
 *
 * 各阶段均为幂等操作：前向重放收敛到完整恢复态。
 */
interface RestoreJournal {

    enum class Phase {
        /** 计划已登记，尚未发生任何持久化变更。 */
        PREPARING,
        /** 数据库事务已提交。 */
        DB_WRITTEN,
        /** 数据库 + 偏好均已写入，等待完成标记。 */
        PREFERENCES_APPLIED,
        COMPLETED,
    }

    data class ActiveEntry(
        val planId: String,
        val phase: Phase,
        /** 经验证的备份载荷 JSON（前向恢复输入）。 */
        val payloadJson: String,
        /** 计划决策记录 JSON（前向恢复重放依据）。 */
        val planRecordJson: String,
        /** 恢复前本机全量快照 JSON（前向失败时的回滚输入）。 */
        val protectiveSnapshotJson: String,
        val includePreferences: Boolean,
        val startedAtEpochMs: Long,
        val lastError: String? = null,
    )

    /** 同步落盘地登记一次恢复（在任何变更前调用）。 */
    suspend fun begin(entry: ActiveEntry)

    suspend fun markPhase(planId: String, phase: Phase)

    suspend fun markFailed(planId: String, cause: String)

    suspend fun read(): ActiveEntry?

    suspend fun clear(planId: String)
}

/** 生产实现：独立 SharedPreferences 文件（commit 同步写，独立于用户偏好 DataStore）。 */
class SharedPrefsRestoreJournal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) : RestoreJournal {

    override suspend fun begin(entry: RestoreJournal.ActiveEntry) = write(entry)

    override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) {
        read()?.let { current ->
            if (current.planId == planId) write(current.copy(phase = phase, lastError = null))
        }
    }

    override suspend fun markFailed(planId: String, cause: String) {
        read()?.let { current ->
            if (current.planId == planId) write(current.copy(lastError = cause))
        }
    }

    override suspend fun read(): RestoreJournal.ActiveEntry? = withContext(dispatchers.io) {
        val raw = prefs().getString(KEY_ACTIVE, null) ?: return@withContext null
        runCatching { decode(raw) }.getOrNull()
    }

    override suspend fun clear(planId: String) = withContext(dispatchers.io) {
        val current = prefs().getString(KEY_ACTIVE, null)?.let { runCatching { decode(it) }.getOrNull() }
        if (current == null || current.planId == planId) {
            prefs().edit().remove(KEY_ACTIVE).commit()
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private suspend fun write(entry: RestoreJournal.ActiveEntry) {
        withContext(dispatchers.io) {
            prefs().edit().putString(KEY_ACTIVE, encode(entry)).commit()
        }
    }

    private fun encode(entry: RestoreJournal.ActiveEntry): String =
        listOf(
            entry.planId,
            entry.phase.name,
            encodeField(entry.payloadJson),
            encodeField(entry.planRecordJson),
            encodeField(entry.protectiveSnapshotJson),
            entry.includePreferences.toString(),
            entry.startedAtEpochMs.toString(),
            encodeField(entry.lastError ?: ""),
        ).joinToString(SEPARATOR)

    private fun decode(raw: String): RestoreJournal.ActiveEntry {
        val parts = raw.split(SEPARATOR, limit = 8)
        require(parts.size == 8) { "journal 记录字段数不足" }
        return RestoreJournal.ActiveEntry(
            planId = parts[0],
            phase = RestoreJournal.Phase.valueOf(parts[1]),
            payloadJson = decodeField(parts[2]),
            planRecordJson = decodeField(parts[3]),
            protectiveSnapshotJson = decodeField(parts[4]),
            includePreferences = parts[5].toBooleanStrict(),
            startedAtEpochMs = parts[6].toLong(),
            lastError = decodeField(parts[7]).ifEmpty { null },
        )
    }

    // JSON 含 | 与换行，逐字段长度前缀转义，保证 split 安全
    private fun encodeField(field: String): String = field.length.toString() + ":" + field

    private fun decodeField(field: String): String {
        val idx = field.indexOf(':')
        require(idx >= 0) { "journal 字段格式非法" }
        val len = field.substring(0, idx).toInt()
        return field.substring(idx + 1, idx + 1 + len)
    }

    private companion object {
        const val PREFS_NAME = "mediahub_restore_journal"
        const val KEY_ACTIVE = "active_restore"
        const val SEPARATOR = "\u0001"
    }
}
