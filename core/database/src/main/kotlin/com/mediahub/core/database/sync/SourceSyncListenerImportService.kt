package com.mediahub.core.database.sync

import com.mediahub.core.common.sync.SourceSyncListenerStart
import com.mediahub.core.common.sync.SourceSyncListenerTransfer
import com.mediahub.core.common.sync.SourceSyncCodec
import com.mediahub.core.common.sync.SourceSyncReceiptOptions
import com.mediahub.core.common.sync.SourceSyncTls
import com.mediahub.core.common.sync.SourceSyncTlsIdentity
import com.mediahub.core.common.sync.SourceSyncTlsOptions
import com.mediahub.core.common.sync.SourceSyncTransferListener
import com.mediahub.core.common.sync.SourceSyncTransferOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/** [SourceSyncListenerImportService.start] 的结果：要么监听加导入都就绪，要么一个稳定码。 */
sealed interface SourceSyncListenerImportStart {

    /** 监听已经在回环上起来，用它 receiveAndImport；不用时 close 释放端口。 */
    class Started(val service: SourceSyncListenerImportService) : SourceSyncListenerImportStart

    /** 监听没有起来（not-loopback / tls-certificate-unavailable / listener-failed）。 */
    class Failed(val code: String, val detail: String? = null) : SourceSyncListenerImportStart
}

/**
 * W6-34：把 SourceSyncTransferListener 收到的信封接进导入服务。
 *
 * 放在 core:database 而不是 core:common，是因为入口要调 AndroidSourceSyncService；反向依赖被禁止
 * （与 W6-23 的 SourceSyncTransferImportService 同一条理由）。
 *
 * 两段职责分得很清：
 * - 监听端（core:common）只负责帧、TLS、认证，失败给的是传输层稳定码；
 * - 这里只负责把字节交给 SourceSyncTransferImportService.importEnvelopeBytes：
 *   解码失败给 EnvelopeRejected（码来自 codec，且**导入服务一次都没被调用**），
 *   解码成功才调导入服务，给出 Imported（result.rejectedReason 仍可能是整封拒绝）。
 *
 * 因此失败路径零写入是结构性的：帧层失败和解码失败都到不了导入服务那一步。
 */
class SourceSyncListenerImportService(
    private val imports: SourceSyncTransferImportService,
    private val listener: SourceSyncTransferListener,
) : Closeable {

    constructor(
        service: AndroidSourceSyncService,
        listener: SourceSyncTransferListener,
    ) : this(SourceSyncTransferImportService(service), listener)

    /** 监听的地址文本（回环）。 */
    val address: String get() = listener.address

    /** 实际绑定的端口；open 时给 0 也会在这里拿到系统分配的端口。 */
    val port: Int get() = listener.port

    /** 监听是否说 TLS 1.3。 */
    val tlsEnabled: Boolean get() = listener.tlsEnabled

    /** 本端证书公钥指纹（小写 64 hex）；明文监听为 null。要告诉对端的就这一个值。 */
    val certificateFingerprint: String? get() = listener.certificateFingerprint

    /** 监听是否已关闭。 */
    val isClosed: Boolean get() = listener.isClosed

    /** 等待对端连上来的预算，毫秒。 */
    val acceptTimeoutMillis: Int get() = listener.acceptTimeoutMillis

    /**
     * 等一条连接，收一封信封，导入它。
     *
     * 阻塞式 accept 与读帧都在 Dispatchers.IO 上做，调用方（ViewModel）不会被卡住。
     *
     * @param expectedChallenge 非空时要求对端的挑战逐字相等，否则 challenge-mismatch；
     *   null 表示接受对端宣布的任何挑战（与 Windows CLI serve 同口径）。
     * @param auth 可选的对端认证（W6-25），五码由 receive 给出。
     * @param receipt 可选的回执（W6-40），默认关。开启时闸门先按 codec 解一次：
     *   解不出来就**刻意沉默**（一个字节都不写，对端看到 receipt-missing），
     *   解出来才写一个裸 0x04 —— 与 Windows CLI 的 receiptGate = TryDecode 同形
     *   （那边也是解码两次：先闸门、后真正导入）。
     * @return 与 W6-23 同一个三段结果类型：TransferFailed / EnvelopeRejected / Imported。
     */
    suspend fun receiveAndImport(
        expectedChallenge: String? = null,
        auth: SourceSyncTransferOptions? = null,
        receipt: SourceSyncReceiptOptions = SourceSyncReceiptOptions.Off,
    ): SourceSyncTransferImportOutcome {
        val gate: ((ByteArray) -> Boolean)? = if (receipt.enabled) {
            { envelope ->
                SourceSyncCodec.tryDecode(envelope.toString(Charsets.UTF_8)) is SourceSyncCodec.DecodeResult.Ok
            }
        } else {
            null
        }
        val received = withContext(Dispatchers.IO) {
            listener.receiveOnce(expectedChallenge, auth, receipt, gate)
        }
        return when (received) {
            is SourceSyncListenerTransfer.Failed ->
                SourceSyncTransferImportOutcome.TransferFailed(received.code, received.detail)

            is SourceSyncListenerTransfer.Received ->
                imports.importEnvelopeBytes(received.envelope).withReceipt(received.receiptWritten)
        }
    }

    /** 把监听端写没写出回执这件事带回结果（只有 Imported 携带，另两段本来就没写）。 */
    private fun SourceSyncTransferImportOutcome.withReceipt(written: Boolean): SourceSyncTransferImportOutcome =
        if (this is SourceSyncTransferImportOutcome.Imported) {
            SourceSyncTransferImportOutcome.Imported(result, envelopeJson, written)
        } else {
            this
        }

    /** 关掉监听并释放端口；重复调用无副作用。 */
    override fun close() {
        listener.close()
    }

    companion object {

        /**
         * 打开一个回环监听并接上导入网关。
         *
         * @param gateway 生产用 AndroidSourceSyncService 包出来的网关；只想解码不写库的场合用
         *   SourceSyncImportGateways.acceptOnly()。
         * @param host null 或空白表示 127.0.0.1；非回环在创建 socket 之前就被拒绝。
         * @param port 0 表示让系统分配。
         */
        fun start(
            gateway: SourceSyncImportGateway,
            host: String? = null,
            port: Int = 0,
            tls: SourceSyncTlsOptions = SourceSyncTlsOptions.Off,
            identity: SourceSyncTlsIdentity = SourceSyncTlsIdentity.NONE,
            acceptTimeoutMs: Int = SourceSyncTransferListener.DEFAULT_ACCEPT_TIMEOUT_MS,
            readTimeoutMs: Int = SourceSyncTransferListener.DEFAULT_READ_TIMEOUT_MS,
            handshakeTimeoutMs: Int = SourceSyncTls.DEFAULT_HANDSHAKE_TIMEOUT_MS,
        ): SourceSyncListenerImportStart {
            val opened = SourceSyncTransferListener.open(
                host = host,
                port = port,
                tls = tls,
                identity = identity,
                acceptTimeoutMs = acceptTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                handshakeTimeoutMs = handshakeTimeoutMs,
            )
            return when (opened) {
                is SourceSyncListenerStart.Failed ->
                    SourceSyncListenerImportStart.Failed(opened.code, opened.detail)

                is SourceSyncListenerStart.Started ->
                    SourceSyncListenerImportStart.Started(
                        SourceSyncListenerImportService(
                            imports = SourceSyncTransferImportService(gateway),
                            listener = opened.listener,
                        ),
                    )
            }
        }
    }
}
