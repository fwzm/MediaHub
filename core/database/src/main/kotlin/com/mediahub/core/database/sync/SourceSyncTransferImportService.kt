package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceSyncCodec
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
    data class Imported(val result: SourceSyncImportResult) : SourceSyncTransferImportOutcome

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
     */
    suspend fun receiveAndImport(
        host: String,
        port: Int,
        expectedChallenge: String? = null,
    ): SourceSyncTransferImportOutcome {
        val received = withContext(Dispatchers.IO) {
            client.receiveFrom(host, port, expectedChallenge)
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
     */
    suspend fun importEnvelopeBytes(envelopeBytes: ByteArray): SourceSyncTransferImportOutcome {
        val json = envelopeBytes.toString(Charsets.UTF_8)
        return when (val decoded = SourceSyncCodec.tryDecode(json)) {
            is SourceSyncCodec.DecodeResult.Failed ->
                SourceSyncTransferImportOutcome.EnvelopeRejected(decoded.reason, decoded.message)

            is SourceSyncCodec.DecodeResult.Ok ->
                SourceSyncTransferImportOutcome.Imported(gateway.importEnvelope(json))
        }
    }
}
