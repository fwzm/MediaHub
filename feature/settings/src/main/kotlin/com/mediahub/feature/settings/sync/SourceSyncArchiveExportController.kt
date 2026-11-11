package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncArchiveArtifact
import com.mediahub.core.database.sync.SourceSyncArchiveExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** W6-64：导出（另存为文件）卡片的可见状态。 */
sealed interface SourceSyncArchiveExportState {

    /** 初始：卡片收起，只有一行「导出（另存为文件）」按钮。 */
    data object Idle : SourceSyncArchiveExportState

    /** 卡片展开，等用户填口令（留空 = 导出明文信封）。[error] 是上一次失败的错误行。 */
    data class AwaitingPassphrase(val error: String? = null) : SourceSyncArchiveExportState

    /** 正在生成产物，重复提交被忽略。 */
    data object Preparing : SourceSyncArchiveExportState

    /** 归档/明文已生成，界面据此拉起系统保存位置选择器。 */
    data class Ready(
        val bytes: ByteArray,
        val fileName: String,
        val encrypted: Boolean,
        val sourceCount: Int,
        val skippedCount: Int,
    ) : SourceSyncArchiveExportState

    /** 用户已选好位置，正在写盘。 */
    data object Saving : SourceSyncArchiveExportState

    /** 写盘成功（成功行带文件名、字节数与是否加密）。 */
    data class Saved(
        val fileName: String,
        val encrypted: Boolean,
        val sourceCount: Int,
        val skippedCount: Int,
        val byteCount: Int,
    ) : SourceSyncArchiveExportState

    /** 失败：[code] 要么是 ArchiveError 原码（写不出来），要么是本层两个写盘码。 */
    data class Failed(val code: String, val message: String? = null) : SourceSyncArchiveExportState
}

/** 导出卡自己的文案（固定说明与免责句仍在 SourceSyncImportCopy）。 */
object SourceSyncArchiveExportCopy {

    const val TITLE: String = "导出（另存为文件）"

    const val HINT: String = "把本机来源导出成一个文件交给别人导入。口令留空导出明文信封（.json），" +
        "填口令（至少 12 个字符）导出加密归档（.mharchive），口令不会随文件保存。"

    const val ACTION: String = "导出"

    const val PASSPHRASE_LABEL: String = "口令（可留空）"

    const val CANCEL: String = "收起"

    /** 成功行。 */
    fun savedLineOf(state: SourceSyncArchiveExportState.Saved): String {
        val kind = if (state.encrypted) "加密归档" else "明文信封"
        return "已导出" + kind + "（" + state.fileName + "，" +
            state.byteCount + " 字节，" + state.sourceCount + " 个来源）"
    }

    /** 失败行：稳定码原样带上，便于对照 Windows CLI。 */
    fun failedLineOf(code: String): String = "导出失败（" + code + "）"
}

/** 生成端口：生产实现 = SourceSyncArchiveExport.of(AndroidSourceSyncService.export())。 */
fun interface SourceSyncArchiveExportPort {
    suspend fun export(passphrase: String?): SourceSyncArchiveArtifact
}

/** 写盘结果（SAF 的三种结局）。 */
sealed interface SourceSyncArchiveWriteResult {
    data object Written : SourceSyncArchiveWriteResult

    /** 目标流不可用（openOutputStream 返回 null）——一个字节都没写。 */
    data object StreamUnavailable : SourceSyncArchiveWriteResult

    data class WriteFailed(val cause: String) : SourceSyncArchiveWriteResult
}

/** 写盘端口：生产实现包 feature/settings 的 BackupFileStore.write。 */
fun interface SourceSyncArchiveFilePort {
    suspend fun write(uriString: String, bytes: ByteArray): SourceSyncArchiveWriteResult
}

/**
 * W6-64：导出侧「另存为文件」的状态机（对齐 Windows SyncCliRunner 的 export 命令语义）。
 *
 * 四步：request() 展开卡片 → prepare(passphrase) 生成产物 → 界面拉起 SAF 保存器 → save(uri) 写盘。
 * 与 W6-63 的解密流程对称：
 * - 口令留空 = 明文导出（与发送卡用的同一份 JSON，一个字节都不重新编码）；
 * - 口令非空 = ArchiveWriter 包成归档，口令策略/凭据拒绝/载荷上限全部由写端判定，码原样透出；
 * - 生成物只在内存里，写盘成功或失败后立刻丢掉字节（不留副本）。
 *
 * 本类不碰 Context / Compose / 数据库，JVM 单测可用替身端口跑全部状态转换。
 */
class SourceSyncArchiveExportController(
    private val exportPort: SourceSyncArchiveExportPort,
    private val filePort: SourceSyncArchiveFilePort,
) {

    private val _state = MutableStateFlow<SourceSyncArchiveExportState>(SourceSyncArchiveExportState.Idle)
    val state: StateFlow<SourceSyncArchiveExportState> = _state.asStateFlow()

    // State transitions are confined to the UI owner; only the ports dispatch work.
    private var generation = 0L

    /** 还没写盘的字节数（测试用；不暴露内容）。 */
    val pendingByteCount: Int
        get() = (_state.value as? SourceSyncArchiveExportState.Ready)?.bytes?.size ?: 0

    /** 展开卡片（幂等：已展开或已有产物时不重置）。 */
    fun request() {
        if (_state.value is SourceSyncArchiveExportState.Idle) {
            _state.value = SourceSyncArchiveExportState.AwaitingPassphrase()
        }
    }

    /**
     * 生成产物。空白口令按「明文导出」处理（null 进端口），不下发空串。
     *
     * 口令输入或失败重试时受理；先进入 Preparing，避免挂起期间重复生成。
     */
    suspend fun prepare(passphrase: String) {
        if (_state.value !is SourceSyncArchiveExportState.AwaitingPassphrase &&
            _state.value !is SourceSyncArchiveExportState.Failed) return
        val owner = ++generation
        _state.value = SourceSyncArchiveExportState.Preparing
        try {
            val artifact = exportPort.export(passphrase.takeIf { it.isNotBlank() })
            currentCoroutineContext().ensureActive()
            if (generation != owner) return
            _state.value = when (artifact) {
                is SourceSyncArchiveArtifact.Plain -> SourceSyncArchiveExportState.Ready(
                    artifact.json.toByteArray(Charsets.UTF_8), PLAIN_FILE_NAME, false,
                    artifact.sourceCount, artifact.skippedCount,
                )
                is SourceSyncArchiveArtifact.Sealed -> SourceSyncArchiveExportState.Ready(
                    artifact.bytes, SourceSyncArchiveExport.DEFAULT_FILE_NAME, true,
                    artifact.sourceCount, artifact.skippedCount,
                )
                is SourceSyncArchiveArtifact.Rejected -> SourceSyncArchiveExportState.AwaitingPassphrase(
                    error = SourceSyncArchiveExportCopy.failedLineOf(artifact.reason),
                )
            }
        } catch (cancelled: CancellationException) {
            if (generation == owner) _state.value = SourceSyncArchiveExportState.AwaitingPassphrase()
            throw cancelled
        } catch (_: Exception) {
            if (generation == owner) _state.value = SourceSyncArchiveExportState.Failed(PREPARE_FAILED)
        }
    }

    /** One write per ready artifact. Invalidated results cannot replace a new request. */
    suspend fun save(uriString: String) {
        val ready = _state.value as? SourceSyncArchiveExportState.Ready ?: return
        val owner = generation
        _state.value = SourceSyncArchiveExportState.Saving
        try {
            val result = filePort.write(uriString, ready.bytes)
            currentCoroutineContext().ensureActive()
            if (generation != owner) return
            _state.value = when (result) {
                SourceSyncArchiveWriteResult.Written -> SourceSyncArchiveExportState.Saved(
                    ready.fileName, ready.encrypted, ready.sourceCount, ready.skippedCount, ready.bytes.size,
                )
                SourceSyncArchiveWriteResult.StreamUnavailable -> SourceSyncArchiveExportState.Failed(STREAM_UNAVAILABLE)
                is SourceSyncArchiveWriteResult.WriteFailed -> SourceSyncArchiveExportState.Failed(WRITE_FAILED, result.cause)
            }
        } catch (cancelled: CancellationException) {
            if (generation == owner) _state.value = SourceSyncArchiveExportState.Idle
            throw cancelled
        } catch (_: Exception) {
            if (generation == owner) _state.value = SourceSyncArchiveExportState.Failed(WRITE_FAILED)
        }
    }

    /** Discard an unclaimed picker result; an already-started write may have partial output. */
    fun saveCancelled() {
        if (_state.value is SourceSyncArchiveExportState.Ready || _state.value is SourceSyncArchiveExportState.Saving) dismiss()
    }

    /** Invalidates all pending work. This does not roll back bytes already written by a provider. */
    fun dismiss() {
        generation++
        _state.value = SourceSyncArchiveExportState.Idle
    }

    companion object {
        /** 明文导出的文件名（Windows CLI 的 DefaultEnvelopeFileName 同名）。 */
        const val PLAIN_FILE_NAME: String = "sync-envelope.json"

        /** 目标流不可用（未写入任何数据）。 */
        const val STREAM_UNAVAILABLE: String = "archive-export-stream-unavailable"

        /** 写盘异常。 */
        const val WRITE_FAILED: String = "archive-export-write-failed"

        const val PREPARE_FAILED: String = "archive-export-prepare-failed"
    }
}
