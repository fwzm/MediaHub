package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncFingerprints
import com.mediahub.core.common.sync.SourceSyncTransferClient
import com.mediahub.core.common.sync.SourceSyncTransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把一份已经收到的信封交给导入服务的最小口子（W6-23）。
 *
 * 抽成接口是为了让传输层的测试可以在不碰 Room 的情况下断言调用次数与拿到的 JSON 文本；
 * 生产实现由 AndroidSourceSyncService 提供（见 SourceSyncTransferImportService 的次构造器）。
 */
fun interface SourceSyncImportGateway {
    suspend fun importEnvelope(envelopeJson: String): SourceSyncImportResult
}

/**
 * 传输 -> 解码 -> 导入 三段闭环的结果。
 *
 * 三段各自有自己的稳定码来源，不合并：
 * - TransferFailed：帧层失败，码来自 SourceSyncTransferReasons（not-loopback / connect-failed /
 *   frame-truncated / challenge-mismatch / transfer-timeout / io-failed 等），此时一个字节都没进 codec。
 * - EnvelopeRejected：帧层成功但载荷不是合法信封，码来自 SourceSyncCodec（malformed-json /
 *   missing-field:名字 / unsupported-schema-version:版本 等），此时没有调用导入服务。
 * - Imported：导入服务自己的判定，可能是正常导入，也可能是整封拒绝（result.rejectedReason）。
 */
sealed interface SourceSyncTransferImportOutcome {
    /** 传输阶段失败，未进入解码。 */
    data class TransferFailed(val reason: String, val detail: String? = null) : SourceSyncTransferImportOutcome

    /** 载荷解码失败，未调用导入服务。 */
    data class EnvelopeRejected(val reason: String, val message: String? = null) : SourceSyncTransferImportOutcome

    /** 已交给导入服务（result.isRejected 仍可能为 true，例如信封内重复 syncSourceId）。 */
    data class Imported(
        val result: SourceSyncImportResult,
        /**
         * 收到的信封原文（W6-27）。
         *
         * 帧层与解码都通过才走到这里，界面要把这份文本交给 SourceSyncImportSession.loadEnvelope
         * 才能进入"预览 -> 逐条允许 -> 应用"的同一条流程；因此它随结果一起回传，而不是让调用方
         * 自己从 socket 字节里再捞一次。写入仍只由 apply 触发。
         */
        val envelopeJson: String? = null,
        /**
         * 本端是否真的给对端写回了 0x04 回执（W6-40）。
         *
         * 只有监听端这条路（[SourceSyncListenerImportService.receiveAndImport] 开了回执）才可能为 true；
         * 拨号收帧的 W6-23 路径恒为 false。它只是"我确认了"，不改变写入判定。
         */
        val receiptWritten: Boolean = false,
    ) : SourceSyncTransferImportOutcome

    val isTransferFailure: Boolean get() = this is TransferFailed

    val isEnvelopeRejection: Boolean get() = this is EnvelopeRejected

    val isImported: Boolean get() = this is Imported

    /** 当前阶段能给出的唯一稳定码；导入成功且未被拒时为空。 */
    val reasonOrNull: String?
        get() = when (this) {
            is TransferFailed -> reason
            is EnvelopeRejected -> reason
            is Imported -> result.rejectedReason
        }
}

/**
 * W6-23：把 SourceSyncTransferClient.receiveFrom 收到的帧流接进导入服务。
 *
 * 放在 core:database 而不是 core:common，是因为入口要调 AndroidSourceSyncService；反向依赖被禁止。
 * 顺序固定：先由客户端拨号收帧（IO 线程），再由 SourceSyncCodec 解码，只有解码成功才调导入服务。
 */
class SourceSyncTransferImportService(
    private val gateway: SourceSyncImportGateway,
    private val client: SourceSyncTransferClient = SourceSyncTransferClient(),
) {

    constructor(
        service: AndroidSourceSyncService,
        client: SourceSyncTransferClient = SourceSyncTransferClient(),
    ) : this(SourceSyncImportGateway { envelopeJson -> service.importEnvelope(envelopeJson) }, client)

    /**
     * 连回环对端收一封信封并导入。
     *
     * expectedChallenge 非空时，帧流里的挑战必须与之逐字相等，否则 challenge-mismatch。
     * @param allowExplicitHost 只有 true 时才允许拨一个显式的非回环单播字面量（W6-52b）；
     *   默认 false，与 W6-23 起的「只连回环」逐字同义（判定与诊断句都在 core:common 的 SourceSyncHostAddress）。
     * @param cancel 可选的本地取消把手（W6-58）：取消会立刻关掉这条连接，本次调用随即以
     *   [com.mediahub.core.common.sync.SourceSyncLocalReasons.CANCELLED] 返回 TransferFailed；
     *   缺省 null 时行为与 W6-52b 逐字相同。
     */
    suspend fun receiveAndImport(
        host: String,
        port: Int,
        expectedChallenge: String? = null,
        allowExplicitHost: Boolean = false,
        cancel: com.mediahub.core.common.sync.SourceSyncCancellation? = null,
    ): SourceSyncTransferImportOutcome {
        val received = withContext(Dispatchers.IO) {
            client.receiveFrom(
                host,
                port,
                expectedChallenge,
                allowExplicitHost = allowExplicitHost,
                cancel = cancel,
            )
        }
        return when (received) {
            is SourceSyncTransferResult.Failed ->
                SourceSyncTransferImportOutcome.TransferFailed(received.reason, received.message)

            is SourceSyncTransferResult.Ok -> importEnvelopeBytes(received.envelope)
        }
    }

    /**
     * 已是内存字节的信封（不再拨号）：先按 UTF-8 解码成文本交给 codec。
     *
     * 非法 UTF-8 不会被替换成 U+FFFD 再往下走——它一定不是合法 JSON，codec 会报 malformed-json。
     *
     * W6-63：若字节其实是离线加密归档（.mharchive），本重载没有口令可用，会稳定拒绝为
     * [SourceSyncDocumentInput.PASSPHRASE_REQUIRED]；要解归档请走带 [passphrase] 的重载。
     */
    suspend fun importEnvelopeBytes(envelopeBytes: ByteArray): SourceSyncTransferImportOutcome =
        importEnvelopeBytes(envelopeBytes, passphrase = null)

    /**
     * W6-63：导入入口的字节分流（对齐 Windows SyncCliRunner.TryResolveEnvelope）。
     *
     * - 明文：与 [importEnvelopeBytes] 逐字节相同的既有路径（宽松 UTF-8 → codec → 网关）；
     * - 归档且无口令：[SourceSyncDocumentInput.PASSPHRASE_REQUIRED]；
     * - 归档且口令错/结构坏：reason 原样透出 ArchiveError 的稳定码（两端同名同序）；
     * - 归档解开后重新编码成明文 JSON，再走同一条 codec → 网关路径。
     *
     * 网关抛出的异常仍然不 catch —— 传输层没有资格把上层的稳定码换成兜底文案。
     */
    suspend fun importEnvelopeBytes(
        envelopeBytes: ByteArray,
        passphrase: String?,
    ): SourceSyncTransferImportOutcome =
        when (val resolution = SourceSyncDocumentInput.resolve(envelopeBytes, passphrase)) {
            is SourceSyncDocumentResolution.Plaintext -> deliverText(resolution.text)

            is SourceSyncDocumentResolution.Decrypted -> deliverText(resolution.json)

            is SourceSyncDocumentResolution.PassphraseRequired -> SourceSyncTransferImportOutcome.EnvelopeRejected(
                SourceSyncDocumentInput.PASSPHRASE_REQUIRED,
                resolution.message,
            )

            is SourceSyncDocumentResolution.Rejected ->
                SourceSyncTransferImportOutcome.EnvelopeRejected(resolution.reason, resolution.message)
        }

    private suspend fun deliverText(json: String): SourceSyncTransferImportOutcome =
        when (val decoded = SourceSyncCodec.tryDecode(json)) {
            is SourceSyncCodec.DecodeResult.Failed ->
                SourceSyncTransferImportOutcome.EnvelopeRejected(decoded.reason, decoded.message)

            is SourceSyncCodec.DecodeResult.Ok ->
                SourceSyncTransferImportOutcome.Imported(gateway.importEnvelope(json), json)
        }
}

/**
 * W6-27：界面上「从 Windows 接收」用的网关。
 *
 * 接收发生在用户预览与逐条允许**之前**：这里若接 [AndroidSourceSyncService]，收到的每一条可写来源
 * 都会被直接写库，逐条审批就形同虚设。acceptOnly 只解码、回显信封身份与内容指纹，一个字节都不写；
 * 真正的写入仍然只经 SourceSyncImportSession.apply()（内部才是 AndroidSourceSyncService）。
 */
object SourceSyncImportGateways {

    fun acceptOnly(): SourceSyncImportGateway = SourceSyncImportGateway { envelopeJson ->
        when (val decoded = SourceSyncCodec.tryDecode(envelopeJson)) {
            is SourceSyncCodec.DecodeResult.Failed -> SourceSyncImportResult(
                rejectedReason = decoded.reason,
                rejectedMessage = decoded.message,
            )

            is SourceSyncCodec.DecodeResult.Ok -> SourceSyncImportResult(
                deviceId = decoded.envelope.deviceId,
                snapshotId = decoded.envelope.snapshotId,
                // 与 AndroidSourceSyncService 同一个指纹算法，界面据此判断"这份信封收过没有"。
                envelopeContentHash = SourceSyncFingerprints.content(decoded.envelope),
            )
        }
    }
}
