package com.mediahub.feature.settings.backup

import android.content.Context
import android.content.SharedPreferences
import android.util.Xml
import com.mediahub.core.common.AppDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Collections
import java.util.WeakHashMap
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

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
        /** 回滚意图已持久化；重启后继续回滚，不重放导入计划。 */
        ROLLING_BACK,
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
        /** 加密私有完整快照的引用；旧版日志保留 JSON 字段以供安全识别。 */
        val protectiveSnapshotRef: String? = null,
    )

    /** 同步落盘地登记一次恢复（在任何变更前调用）。 */
    suspend fun begin(entry: ActiveEntry)

    suspend fun markPhase(planId: String, phase: Phase)

    suspend fun markFailed(planId: String, cause: String)

    suspend fun read(): ActiveEntry?

    suspend fun clear(planId: String)
}

/** Journal errors deliberately omit the stored value, plan contents, and raw exception text. */
class RestoreJournalCorruptedException : IllegalStateException("恢复记录已损坏，需要检查后再继续")
class RestoreJournalPersistenceException : IllegalStateException("恢复记录未能可靠保存，请重新启动应用后检查恢复状态")
class RestoreJournalConflictException : IllegalStateException("恢复记录与当前操作不一致，不能开始或修改恢复")

/**
 * 独立 SharedPreferences 文件，所有实例共享同进程互斥锁；commit 成功是数据写入的前提。
 * 完整保护快照由加密私有存储承载，此处只保存引用。旧版 8 字段记录仍可读取并交恢复层判断。
 */
class SharedPrefsRestoreJournal @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) : RestoreJournal {

    override suspend fun begin(entry: RestoreJournal.ActiveEntry) = locked { prefs ->
        if (readEntry(prefs) != null) throw RestoreJournalConflictException()
        persist(prefs) { putString(KEY_ACTIVE, encode(entry)) }
    }

    override suspend fun markPhase(planId: String, phase: RestoreJournal.Phase) = locked { prefs ->
        val current = matchingEntry(prefs, planId)
        persist(prefs) { putString(KEY_ACTIVE, encode(current.copy(phase = phase, lastError = null))) }
    }

    override suspend fun markFailed(planId: String, cause: String) = locked { prefs ->
        val current = matchingEntry(prefs, planId)
        persist(prefs) { putString(KEY_ACTIVE, encode(current.copy(lastError = SAFE_FAILURE))) }
    }

    override suspend fun read(): RestoreJournal.ActiveEntry? = locked { readEntry(it) }

    override suspend fun clear(planId: String) = locked { prefs ->
        matchingEntry(prefs, planId)
        persist(prefs) { remove(KEY_ACTIVE) }
    }

    private suspend fun <T> locked(block: (SharedPreferences) -> T): T = withContext(dispatchers.io) {
        operationMutex.withLock {
            val prefs = try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw RestoreJournalPersistenceException()
            }
            if (prefs in uncertainStores) throw RestoreJournalPersistenceException()
            block(prefs)
        }
    }

    private fun matchingEntry(prefs: SharedPreferences, planId: String): RestoreJournal.ActiveEntry {
        val current = readEntry(prefs)
        if (current == null || current.planId != planId) throw RestoreJournalConflictException()
        return current
    }

    private fun readEntry(prefs: SharedPreferences): RestoreJournal.ActiveEntry? {
        val raw = try {
            prefs.getString(KEY_ACTIVE, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ClassCastException) {
            throw RestoreJournalCorruptedException()
        } catch (_: Exception) {
            throw RestoreJournalPersistenceException()
        }
        if (raw == null) {
            verifyMissingPhysicalEntry()
            return null
        }
        return try {
            decode(raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw RestoreJournalCorruptedException()
        }
    }

    /**
     * Android can turn an XML parsing failure into an empty preferences map. Only an absent file or
     * a valid empty map proves Missing for this dedicated, single-key journal. getString above first
     * waits for Android's asynchronous load and its .bak restoration to finish.
     */
    private fun verifyMissingPhysicalEntry() {
        try {
            val directory = File(context.dataDir, "shared_prefs")
            val file = File(directory, "$PREFS_NAME.xml")
            require(!File(directory, "$PREFS_NAME.xml.bak").exists())
            if (!file.exists()) return
            require(file.isFile && file.length() <= MAX_PHYSICAL_BYTES)
            file.inputStream().buffered().use { stream ->
                val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                var rootSeen = false
                var rootClosed = false
                while (true) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            // An active value on disk contradicts the empty cache; never overwrite it.
                            require(!rootSeen && parser.depth == 1 && parser.name == "map")
                            rootSeen = true
                        }
                        XmlPullParser.END_TAG -> {
                            require(rootSeen && !rootClosed && parser.depth == 1 && parser.name == "map")
                            rootClosed = true
                        }
                        XmlPullParser.TEXT, XmlPullParser.CDSECT, XmlPullParser.ENTITY_REF -> require(parser.text.orEmpty().isBlank())
                        XmlPullParser.DOCDECL -> error("Unsupported journal XML")
                        XmlPullParser.END_DOCUMENT -> break
                    }
                    parser.nextToken()
                }
                require(rootSeen && rootClosed)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw RestoreJournalCorruptedException()
        }
    }

    private fun persist(prefs: SharedPreferences, change: SharedPreferences.Editor.() -> Unit) {
        val committed = try {
            prefs.edit().apply(change).commit()
        } catch (cancelled: CancellationException) {
            uncertainStores.add(prefs)
            throw cancelled
        } catch (_: Exception) {
            uncertainStores.add(prefs)
            throw RestoreJournalPersistenceException()
        }
        if (!committed) {
            // Android may already have changed the in-memory map even though commit failed.
            // Never interpret that cache as durable state or let a failed clear appear Missing.
            // A new app process must reopen the persisted record before recovery can continue.
            uncertainStores.add(prefs)
            throw RestoreJournalPersistenceException()
        }
    }

    private fun encode(entry: RestoreJournal.ActiveEntry): String {
        require(entry.planId.isNotBlank() && SEPARATOR !in entry.planId)
        require(entry.startedAtEpochMs >= 0)
        require(entry.protectiveSnapshotRef == null || entry.protectiveSnapshotRef.isNotBlank())
        return listOf(
            entry.planId,
            entry.phase.name,
            encodeField(entry.payloadJson),
            encodeField(entry.planRecordJson),
            encodeField(entry.protectiveSnapshotJson),
            entry.includePreferences.toString(),
            entry.startedAtEpochMs.toString(),
            encodeField(if (entry.lastError == null) "" else SAFE_FAILURE),
            encodeField(entry.protectiveSnapshotRef ?: ""),
        ).joinToString(SEPARATOR)
    }

    private fun decode(raw: String): RestoreJournal.ActiveEntry {
        val parts = raw.split(SEPARATOR)
        require(parts.size == 8 || parts.size == 9)
        require(parts[0].isNotBlank())
        val startedAt = parts[6].toLong()
        require(startedAt >= 0)
        return RestoreJournal.ActiveEntry(
            planId = parts[0],
            phase = RestoreJournal.Phase.valueOf(parts[1]),
            payloadJson = decodeField(parts[2]),
            planRecordJson = decodeField(parts[3]),
            protectiveSnapshotJson = decodeField(parts[4]),
            includePreferences = parts[5].toBooleanStrict(),
            startedAtEpochMs = startedAt,
            lastError = decodeField(parts[7]).takeIf { it.isNotEmpty() }?.let { SAFE_FAILURE },
            protectiveSnapshotRef = parts.getOrNull(8)?.let(::decodeField)?.ifEmpty { null },
        )
    }

    // JSON escapes control characters; reject a raw delimiter instead of generating an unreadable record.
    private fun encodeField(field: String): String {
        require(SEPARATOR !in field)
        return field.length.toString() + ":" + field
    }

    private fun decodeField(field: String): String {
        val idx = field.indexOf(':')
        require(idx > 0)
        val len = field.substring(0, idx).toInt()
        require(len >= 0 && len == field.length - idx - 1)
        return field.substring(idx + 1)
    }

    private companion object {
        const val PREFS_NAME = "mediahub_restore_journal"
        const val KEY_ACTIVE = "active_restore"
        const val SEPARATOR = "\u0001"
        const val SAFE_FAILURE = "恢复过程未完成，需要继续恢复或回滚"
        const val MAX_PHYSICAL_BYTES = 64L * 1024 * 1024
        val operationMutex = Mutex()
        val uncertainStores: MutableSet<SharedPreferences> =
            Collections.newSetFromMap(WeakHashMap<SharedPreferences, Boolean>())
    }
}
