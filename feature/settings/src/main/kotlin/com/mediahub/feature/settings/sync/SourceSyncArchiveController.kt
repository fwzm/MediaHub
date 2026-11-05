package com.mediahub.feature.settings.sync

import com.mediahub.core.database.sync.SourceSyncDocumentInput
import com.mediahub.core.database.sync.SourceSyncDocumentResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** W6-63：口令卡的可见状态。visible=false 时界面不渲染任何口令控件。 */
data class SourceSyncArchivePrompt(
    val visible: Boolean = false,
    val fileName: String? = null,
    val error: String? = null,
)

/** 口令卡自己的文案（整页固定说明仍在 SourceSyncImportCopy）。 */
object SourceSyncArchiveCopy {

    /** 口令卡正文：说清楚这是加密归档、口令从哪来、不会被保存。 */
    const val HINT: String = "这是加密归档（.mharchive）。请输入导出时设置的口令；" +
        "口令只在本机内存里用一次，不会保存。"

    const val LABEL: String = "口令"

    const val ACTION: String = "解密"

    /** 错误行的固定措辞：原因码原样带上，便于对照 Windows CLI。 */
    fun errorOf(reason: String): String = "解密失败（" + reason + "）"
}

/**
 * 解密端口：生产实现直接调 core:database 的 SourceSyncDocumentInput.resolve。
 *
 * 抽成接口只为让口令流程能在 JVM 单测里跑（真跑一次 PBKDF2-SHA256 600000 轮太慢）。
 */
fun interface SourceSyncArchiveUnlockPort {
    fun unlock(archiveBytes: ByteArray, passphrase: String): SourceSyncDocumentResolution
}

/**
 * W6-63：选到加密归档（.mharchive）之后的口令流程。
 *
 * 只做三件事：嗅探 magic 决定要不要口令、把用户输入的口令交给端口解、把结论交给外层回调。
 * 不碰 Context / Compose / 数据库，也不缓存口令：解开之后立刻丢掉暂存的归档字节。
 *
 * 语义约定（与 Windows SyncCliRunner.TryResolveEnvelope 对齐）：
 * - 非归档一律返回 false，调用方继续走原有明文路径（该路径行为逐字节不变）；
 * - 归档但没口令 ⇒ 稳定码 passphrase-required（与 Windows CLI 同名同义）；
 * - 口令错 / 归档被改 / 结构坏 ⇒ 卡片文案「解密失败」，错误行带 ArchiveError 原码（一个都不改写）；
 * - 解开后回调的是重新编码的明文 JSON，预览与应用仍走既有 SourceSyncImportSession。
 */
class SourceSyncArchiveController(
    private val port: SourceSyncArchiveUnlockPort,
    private val onUnlocked: (envelopeJson: String, fileName: String) -> Unit,
    private val onBlocked: (reason: String, message: String?, fileName: String) -> Unit,
) {
    private val _prompt = MutableStateFlow(SourceSyncArchivePrompt())
    val prompt: StateFlow<SourceSyncArchivePrompt> = _prompt.asStateFlow()

    private var pending: PendingArchive? = null

    /** 暂存归档的字节数（测试用；不暴露内容）。 */
    val pendingByteCount: Int get() = pending?.bytes?.size ?: 0

    /**
     * 看一份刚选中的文件：是归档就进入口令流程并返回 true，不是就返回 false。
     *
     * 换文件时先清掉上一次的暂存，避免"选了 B 却用 A 的字节解"这种串档。
     */
    fun offer(documentBytes: ByteArray, fileName: String): Boolean {
        if (!SourceSyncDocumentInput.looksLikeArchive(documentBytes)) {
            clear()
            return false
        }
        pending = PendingArchive(documentBytes, fileName)
        _prompt.value = SourceSyncArchivePrompt(visible = true, fileName = fileName)
        onBlocked(SourceSyncDocumentInput.PASSPHRASE_REQUIRED, null, fileName)
        return true
    }

    /**
     * 用口令解开暂存的归档。
     *
     * 空白口令直接忽略（不调端口、不动提示）：用户的"还没输完"不是错误。
     * 失败时保留输入框，只换错误行，改一个字符就能重试，不用重新选文件。
     */
    suspend fun unlock(passphrase: String) {
        val selected = pending ?: return
        if (passphrase.isBlank()) return
        when (val resolution = port.unlock(selected.bytes, passphrase)) {
            is SourceSyncDocumentResolution.Decrypted -> {
                clear()
                onUnlocked(resolution.json, selected.fileName)
            }

            is SourceSyncDocumentResolution.Plaintext -> {
                clear()
                onUnlocked(resolution.text, selected.fileName)
            }

            is SourceSyncDocumentResolution.PassphraseRequired -> {
                _prompt.update { it.copy(visible = true, error = null) }
                onBlocked(
                    SourceSyncDocumentInput.PASSPHRASE_REQUIRED,
                    resolution.message,
                    selected.fileName,
                )
            }

            is SourceSyncDocumentResolution.Rejected -> {
                val errorLine = SourceSyncArchiveCopy.errorOf(resolution.reason)
                _prompt.update { it.copy(visible = true, error = errorLine) }
                onBlocked(SourceSyncImportCopy.UNLOCK_FAILED, errorLine, selected.fileName)
            }
        }
    }

    /** 丢掉暂存归档与提示（换文件、回到初始状态、页面销毁时调用）。 */
    fun clear() {
        pending = null
        _prompt.value = SourceSyncArchivePrompt()
    }

    private data class PendingArchive(val bytes: ByteArray, val fileName: String)
}
